package com.lyokone.location

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * The system events that wake a sleeping app at rest (Android, AutoLNK fork).
 *
 * The foreground service runs only while the app needs continuous GPS in the
 * background: a drive. At rest the app holds no service and no location
 * request of its own, so Android shows no notification. Four sources wake it
 * instead. The system holds all four, so they outlive the process and a
 * swipe from Recents:
 *
 * - a "leash" geofence around the phone: an exit means the phone moved;
 * - activity transitions (vehicle, foot, bicycle, still), with the Motion
 *   permission;
 * - a heartbeat alarm that Doze allows, so a still member stays online;
 * - the fused provider's own background fixes, delivered through a
 *   PendingIntent: Android thins them to a few an hour for a background
 *   app, and each one carries a real point.
 *
 * [WakeReceiver] turns each event into a drive start or a short run
 * ([WakeHub]). The wish is persisted, and the boot receiver re-arms it after
 * a reboot or an app update ([rearmIfArmed]).
 */
internal object WakeMonitor {
    private const val TAG = "WakeMonitor"

    const val ACTION_GEOFENCE = "com.lyokone.location.action.WAKE_GEOFENCE"
    const val ACTION_TRANSITION = "com.lyokone.location.action.WAKE_TRANSITION"
    const val ACTION_HEARTBEAT = "com.lyokone.location.action.WAKE_HEARTBEAT"
    const val ACTION_FIX = "com.lyokone.location.action.WAKE_FIX"

    private const val PREFS_NAME = "flutter_location_prefs"
    private const val KEY_ARMED = "wake_armed"
    private const val KEY_HEARTBEAT_MS = "wake_heartbeat_ms"
    private const val KEY_DRIVE_SPEED = "wake_drive_speed_mps"
    private const val KEY_LEASH_LAT = "wake_leash_lat"
    private const val KEY_LEASH_LNG = "wake_leash_lng"
    private const val KEY_LEASH_OK = "wake_leash_ok"
    private const val KEY_LAST_RECENTRE = "wake_leash_recentred_at"

    private const val LEASH_ID = "lyokone.location.leash"

    /** Region monitoring is reliable from about 100 m. The iOS leash uses the same numbers. */
    private const val LEASH_RADIUS_M = 150f
    private const val LEASH_RECENTRE_M = 75f

    /**
     * The coarsest fix the leash moves to. A centre as coarse as the radius
     * puts a still phone outside its own leash, and every exit then wakes
     * the app for nothing.
     */
    const val LEASH_MAX_ACCURACY_M = 75f

    /** A delivered fix moves the leash at most this often: during a drive the fixes come every second. */
    private const val LEASH_RECENTRE_MIN_INTERVAL_MS = 30_000L

    private const val DEFAULT_HEARTBEAT_MS = 300_000L
    private const val MIN_HEARTBEAT_MS = 60_000L
    private const val DEFAULT_DRIVE_SPEED_MPS = 6.7f

    private const val RC_GEOFENCE = 0x4C01
    private const val RC_TRANSITION = 0x4C02
    private const val RC_HEARTBEAT = 0x4C03
    private const val RC_FIX = 0x4C04

    /**
     * The at-rest request: balanced power every five minutes, batched up to
     * ten. Android itself thins it to a few fixes an hour for a background
     * app without a service, so the request costs no more than the system
     * allows anyway.
     */
    private const val AT_REST_INTERVAL_MS = 300_000L
    private const val AT_REST_MIN_INTERVAL_MS = 120_000L
    private const val AT_REST_MAX_DELAY_MS = 600_000L

    private val transitions: List<ActivityTransition> by lazy {
        listOf(
            DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
            DetectedActivity.WALKING to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.RUNNING to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.ON_BICYCLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        ).map { (activity, transition) ->
            ActivityTransition.Builder().setActivityType(activity).setActivityTransition(transition).build()
        }
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isArmed(context: Context): Boolean = prefs(context).getBoolean(KEY_ARMED, false)

    fun driveSpeedMps(context: Context): Float = prefs(context).getFloat(KEY_DRIVE_SPEED, DEFAULT_DRIVE_SPEED_MPS)

    /**
     * Whether Play services holds the leash now. False after a failed add,
     * and after GEOFENCE_NOT_AVAILABLE (Google Location Accuracy off), when
     * the system drops every geofence. The heartbeat adds it again.
     */
    fun isLeashOk(context: Context): Boolean = prefs(context).getBoolean(KEY_LEASH_OK, false)

    /** Play services dropped the leash (GEOFENCE_NOT_AVAILABLE). */
    fun onLeashLost(context: Context) {
        prefs(context).edit().putBoolean(KEY_LEASH_OK, false).apply()
    }

    /**
     * Arms every source, or disarms them all. Idempotent: an armed call
     * registers everything again, because a grant may have changed. A
     * [latitude] and [longitude] put the leash on the spot where the app goes
     * to sleep. Returns whether the sources are armed afterwards.
     */
    @JvmStatic
    fun setArmed(
        context: Context,
        enable: Boolean,
        heartbeatMs: Long?,
        driveSpeedMps: Float?,
        latitude: Double?,
        longitude: Double?,
        restoreServiceAtBoot: Boolean?,
        fixTitle: String?,
        fixBody: String?,
    ): Boolean {
        val app = context.applicationContext
        FlutterLocationService.saveWakeOptions(app, restoreServiceAtBoot, fixTitle, fixBody)
        if (!enable || !FlutterLocationService.hasBackgroundLocationPermission(app)) {
            disarm(app)
            return false
        }
        val editor = prefs(app).edit().putBoolean(KEY_ARMED, true)
        if (heartbeatMs != null) editor.putLong(KEY_HEARTBEAT_MS, heartbeatMs.coerceAtLeast(MIN_HEARTBEAT_MS))
        if (driveSpeedMps != null && driveSpeedMps > 0f) editor.putFloat(KEY_DRIVE_SPEED, driveSpeedMps)
        editor.apply()
        if (latitude != null && longitude != null) {
            setLeash(app, latitude, longitude)
        } else {
            armLeash(app)
        }
        armTransitions(app)
        scheduleHeartbeat(app)
        armAtRestFixes(app)
        return true
    }

    /** A reboot or an app update cleared every registration: put them back. */
    @JvmStatic
    fun rearmIfArmed(context: Context) {
        val app = context.applicationContext
        if (!isArmed(app)) return
        if (!FlutterLocationService.hasBackgroundLocationPermission(app)) {
            disarm(app)
            return
        }
        armLeash(app)
        armTransitions(app)
        scheduleHeartbeat(app)
        armAtRestFixes(app)
    }

    @JvmStatic
    fun disarm(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!p.getBoolean(KEY_ARMED, false) && !p.contains(KEY_LEASH_LAT)) return
        p.edit().putBoolean(KEY_ARMED, false).putBoolean(KEY_LEASH_OK, false).remove(KEY_LEASH_LAT).remove(KEY_LEASH_LNG).apply()
        try {
            LocationServices.getGeofencingClient(app).removeGeofences(listOf(LEASH_ID))
        } catch (e: Exception) {
            Log.w(TAG, "The leash geofence could not be removed.", e)
        }
        removeTransitions(app)
        removeAtRestFixes(app)
        (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pendingIntent(app, ACTION_HEARTBEAT, RC_HEARTBEAT, mutable = false))
    }

    /** Every fix the plugin delivers: the leash follows the phone once it left the centre. */
    @JvmStatic
    fun onFix(
        context: Context,
        location: Location,
    ) {
        if (!isArmed(context)) return
        if (!location.hasAccuracy() || location.accuracy > LEASH_MAX_ACCURACY_M) return
        val now = SystemClock.elapsedRealtime()
        // Monotonic, so a reboot starts it over; a stored time ahead of now is from before one.
        val last = prefs(context).getLong(KEY_LAST_RECENTRE, 0L).let { if (it > now) 0L else it }
        if (now - last < LEASH_RECENTRE_MIN_INTERVAL_MS) return
        val centre = savedCentre(context)
        if (centre != null) {
            val distance = FloatArray(1)
            Location.distanceBetween(centre.first, centre.second, location.latitude, location.longitude, distance)
            if (distance[0] < LEASH_RECENTRE_M) return
        }
        setLeash(context, location.latitude, location.longitude)
    }

    /**
     * One geofence under one id: a new centre replaces the old one. A phone
     * that is already outside the new circle gets its exit at once, so a
     * stale centre can never leave the leash without an exit. The centre is
     * kept whatever the result (a later add uses it); [KEY_LEASH_OK] says
     * whether Play services holds it.
     */
    fun setLeash(
        context: Context,
        latitude: Double,
        longitude: Double,
    ) {
        val app = context.applicationContext
        prefs(app)
            .edit()
            .putLong(KEY_LAST_RECENTRE, SystemClock.elapsedRealtime())
            .putString(KEY_LEASH_LAT, latitude.toString())
            .putString(KEY_LEASH_LNG, longitude.toString())
            .apply()
        val geofence =
            Geofence.Builder()
                .setRequestId(LEASH_ID)
                .setCircularRegion(latitude, longitude, LEASH_RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        val request =
            GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(geofence)
                .build()
        try {
            LocationServices.getGeofencingClient(app)
                .addGeofences(request, pendingIntent(app, ACTION_GEOFENCE, RC_GEOFENCE, mutable = true))
                .addOnSuccessListener { prefs(app).edit().putBoolean(KEY_LEASH_OK, true).apply() }
                .addOnFailureListener {
                    Log.w(TAG, "The leash geofence was not added.", it)
                    prefs(app).edit().putBoolean(KEY_LEASH_OK, false).apply()
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission for the leash geofence.", e)
            prefs(app).edit().putBoolean(KEY_LEASH_OK, false).apply()
        }
    }

    /** The next heartbeat. Inexact and allowed in Doze: no exact-alarm permission is needed. */
    fun scheduleHeartbeat(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + prefs(app).getLong(KEY_HEARTBEAT_MS, DEFAULT_HEARTBEAT_MS)
        val pi = pendingIntent(app, ACTION_HEARTBEAT, RC_HEARTBEAT, mutable = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    /** The leash at the saved centre, else at the last known position. */
    fun armLeash(context: Context) {
        val centre = savedCentre(context)
        if (centre != null) {
            setLeash(context, centre.first, centre.second)
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { location -> if (location != null) setLeash(context, location.latitude, location.longitude) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission for the last known position.", e)
        }
    }

    private fun hasMotionPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun armTransitions(context: Context) {
        if (!hasMotionPermission(context)) {
            removeTransitions(context)
            return
        }
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context, ACTION_TRANSITION, RC_TRANSITION, mutable = true))
                .addOnFailureListener { Log.w(TAG, "The activity transitions were not registered.", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No Motion permission for the activity transitions.", e)
        }
    }

    private fun removeTransitions(context: Context) {
        try {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context, ACTION_TRANSITION, RC_TRANSITION, mutable = true))
        } catch (e: Exception) {
            Log.w(TAG, "The activity transitions could not be removed.", e)
        }
    }

    /**
     * The fused provider's own background fixes, delivered to [WakeReceiver]
     * through a PendingIntent, so they arrive while the app is dead. The same
     * PendingIntent replaces an earlier request, so an armed call is
     * idempotent. Each fix wakes Dart with a real point ([WakeReceiver]
     * kind `fix`).
     */
    private fun armAtRestFixes(context: Context) {
        val request =
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, AT_REST_INTERVAL_MS)
                .setMinUpdateIntervalMillis(AT_REST_MIN_INTERVAL_MS)
                .setMaxUpdateDelayMillis(AT_REST_MAX_DELAY_MS)
                .build()
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(request, pendingIntent(context, ACTION_FIX, RC_FIX, mutable = true))
                .addOnFailureListener { Log.w(TAG, "The at-rest fixes were not requested.", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission for the at-rest fixes.", e)
        }
    }

    private fun removeAtRestFixes(context: Context) {
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(pendingIntent(context, ACTION_FIX, RC_FIX, mutable = true))
        } catch (e: Exception) {
            Log.w(TAG, "The at-rest fixes could not be removed.", e)
        }
    }

    private fun savedCentre(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        val lat = p.getString(KEY_LEASH_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = p.getString(KEY_LEASH_LNG, null)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    /**
     * Mutable for the geofence and the transitions: Play services writes the
     * event into the intent extras, which an immutable intent rejects.
     */
    private fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, WakeReceiver::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !mutable) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
