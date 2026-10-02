package com.lyokone.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONException
import org.json.JSONObject

/**
 * One fix for a system wake, in a short foreground run of its own (AutoLNK
 * fork, 4.02).
 *
 * The foreground makes the app a foreground app for location, so the fix
 * comes at once instead of a few times an hour. The run ends inside the ten
 * seconds for which Android 12+ holds back the notification of a foreground
 * service, so it shows none.
 *
 * Three rules keep that true:
 *
 * - It is a service of its own, created for one run and destroyed at its
 *   end. Until 4.02 the run lived inside [FlutterLocationService], which the
 *   plugin keeps bound. Android remembers per service object that its
 *   notification was held back or shown, so every second run showed its
 *   notification at once, and after a drive every run did (system event log
 *   of a Mi 11i, 2026-10-01). A new object starts clean.
 * - A run starts only while Android hides a new notification
 *   ([NotificationDeferral]): once in about two minutes of awake time. A
 *   wake in between gets no run; its caller queues it without a fix, and
 *   the Dart side decides what the point is worth.
 * - One object runs once. A start that reaches an object after its run only
 *   answers the system and stops.
 *
 * The run does not take the first fix it gets: a first fix is often a
 * network fix of 60 to 100 m. It listens until a fix is sharp
 * ([WakeMonitor.fixAccuracyM]) or the time is up, and then takes the best
 * one. A fix that proves car speed hands over to the drive service
 * ([FlutterLocationService.startForDrive]).
 */
class FlutterLocationFixService : Service() {
    companion object {
        private const val TAG = "FlutterLocationFixService"
        private const val ACTION_FIX = "com.lyokone.location.action.FIX"
        private const val EXTRA_WAKE = "wake"
        private const val NOTIFICATION_ID = 75419

        /**
         * How long a run listens for a sharp fix. The run then ends inside
         * the 10 s for which Android 12+ holds back the notification.
         */
        private const val FIX_RUN_MS = 7_000L
        private const val FIX_RUN_LIMIT_MS = 7_500L
        private const val FIX_INTERVAL_MS = 1_000L

        /** A fix this recent serves a wake; an older one is taken again. */
        private const val FIX_MAX_AGE_MS = 5_000L

        /** The run in flight. Main thread only, like every member below. */
        private var active: FlutterLocationFixService? = null

        /** The fix of the last run, and when it came (elapsed realtime). */
        private var lastFix: Location? = null
        private var lastFixAtMs = 0L

        private fun recentFix(): Location? = lastFix?.takeIf { SystemClock.elapsedRealtime() - lastFixAtMs <= FIX_MAX_AGE_MS }

        /**
         * Gives [wake] a fix, or answers false. Main thread. Only an event
         * that exempts the app from the Android 12+ background start
         * restriction may call it. On false the caller queues the wake
         * without a fix.
         *
         * While the drive service streams, the wake only tells Dart: the
         * stream already has the fix. A run in flight takes the wake too,
         * and a fix a few seconds old serves it without a run. Else a run
         * starts, unless Android would show its notification.
         */
        @JvmStatic
        fun start(
            context: Context,
            wake: Map<String, Any?>,
        ): Boolean {
            val app = context.applicationContext
            if (!FlutterLocationService.hasBackgroundLocationPermission(app)) return false
            if (FlutterLocationService.isForegroundNow()) {
                WakeHub.enqueue(app, wake)
                return true
            }
            if (active?.join(wake) == true) return true
            val recent = recentFix()
            if (recent != null) {
                WakeHub.enqueue(app, wake + WakeHub.pointOf(recent) + ("fixRun" to true))
                return true
            }
            if (!NotificationDeferral.hidesNow(app)) {
                Log.i(TAG, "No fix run: Android would show its notification.")
                return false
            }
            return try {
                val intent =
                    Intent(app, FlutterLocationFixService::class.java)
                        .setAction(ACTION_FIX)
                        .putExtra(EXTRA_WAKE, JSONObject(wake).toString())
                ContextCompat.startForegroundService(app, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "The system refused the fix run.", e)
                false
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** The wakes that wait for the fix of this run. */
    private val wakes = mutableListOf<Map<String, Any?>>()
    private var running = false
    private var done = false
    private var lastStartId = 0
    private var best: Location? = null
    private val limit = Runnable { finish(best) }

    private val fixes =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) onFix(location)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val app = applicationContext
        val wake = parseWake(intent?.getStringExtra(EXTRA_WAKE))
        lastStartId = startId
        // Every foreground start must reach the foreground, or Android ends
        // the app.
        val entered = enterForeground()
        if (running) {
            // A fix on its way serves every wake that comes meanwhile.
            wakes.add(wake)
        } else if (!entered || done) {
            // No run for this start: the wake goes on with the fix of the
            // run that just ended, or without one.
            val recent = recentFix()
            WakeHub.enqueue(app, if (recent != null) wake + WakeHub.pointOf(recent) + ("fixRun" to true) else wake)
            leave()
        } else {
            active = this
            running = true
            wakes.add(wake)
            requestFix()
        }
        // A run must never come back after a kill.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (active === this) active = null
        main.removeCallbacks(limit)
        stopFixes()
        super.onDestroy()
    }

    /** A wake that came during the run. False once the run has its fix. */
    private fun join(wake: Map<String, Any?>): Boolean {
        if (!running) return false
        wakes.add(wake)
        return true
    }

    private fun enterForeground(): Boolean {
        val app = applicationContext
        val copy = FlutterLocationService.fixCopy(app)
        val builder = BackgroundNotification(app, FlutterLocationService.CHANNEL_ID, NOTIFICATION_ID)
        FlutterLocationService.savedNotificationOptions(app)?.let { builder.updateOptions(it, false) }
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            NotificationDeferral.onForegroundStart(app)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.buildFix(copy.first, copy.second), type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "The fix run could not enter the foreground.", e)
            false
        }
    }

    private fun requestFix() {
        main.postDelayed(limit, FIX_RUN_LIMIT_MS)
        try {
            val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(0L)
                    .setMaxUpdateAgeMillis(FIX_MAX_AGE_MS)
                    .setDurationMillis(FIX_RUN_MS)
                    // The run picks the best fix itself.
                    .setWaitForAccurateLocation(false)
                    .build()
            LocationServices
                .getFusedLocationProviderClient(applicationContext)
                .requestLocationUpdates(request, fixes, Looper.getMainLooper())
                // Location is off, or the grant went: no fix can come.
                .addOnFailureListener { finish(null) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission for the fix run.", e)
            finish(null)
        }
    }

    private fun onFix(location: Location) {
        if (!running) return
        val known = best
        if (known == null || (location.hasAccuracy() && (!known.hasAccuracy() || location.accuracy <= known.accuracy))) best = location
        val sharp = location.hasAccuracy() && location.accuracy <= WakeMonitor.fixAccuracyM(applicationContext)
        if (sharp || WakeMonitor.provesDriveSpeed(applicationContext, location)) finish(location)
    }

    private fun stopFixes() {
        try {
            LocationServices.getFusedLocationProviderClient(applicationContext).removeLocationUpdates(fixes)
        } catch (e: Exception) {
            Log.w(TAG, "The fix request could not be removed.", e)
        }
    }

    /** Once per run: the fix (or none) goes to Dart with every wake that waited for it. */
    private fun finish(fix: Location?) {
        if (!running) return
        running = false
        done = true
        if (active === this) active = null
        main.removeCallbacks(limit)
        stopFixes()
        val app = applicationContext
        val waiting = wakes.toList()
        wakes.clear()
        Log.i(TAG, "Fix run ended with " + if (fix == null) "no fix." else if (fix.hasAccuracy()) "a fix of ${fix.accuracy.toInt()} m." else "a fix.")
        if (fix != null) {
            lastFix = fix
            lastFixAtMs = SystemClock.elapsedRealtime()
            if (fix.hasAccuracy() && fix.accuracy <= WakeMonitor.LEASH_MAX_ACCURACY_M) WakeMonitor.setLeash(app, fix.latitude, fix.longitude)
        }
        val point = fix?.let { WakeHub.pointOf(it) } ?: emptyMap()
        // A car speed the fix can prove: the drive service takes over. Its
        // start goes out while this run is still in the foreground, which
        // lets a background app start a foreground service.
        if (fix != null && WakeMonitor.provesDriveSpeed(app, fix) && FlutterLocationService.startForDrive(app)) {
            WakeHub.enqueue(app, point + ("kind" to "drive") + ("fixRun" to true) + ("ts" to System.currentTimeMillis().toDouble()))
        } else {
            for (wake in waiting) WakeHub.enqueue(app, wake + point + ("fixRun" to true))
        }
        leave()
    }

    /**
     * Out of the foreground, and gone unless a later start is on its way:
     * a stop under a start that has not reached [onStartCommand] yet makes
     * Android end the app for a foreground start without a foreground.
     */
    private fun leave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelfResult(lastStartId)
    }

    private fun parseWake(raw: String?): Map<String, Any?> {
        if (raw.isNullOrEmpty()) return mapOf("kind" to "refresh", "ts" to System.currentTimeMillis().toDouble())
        return try {
            val json = JSONObject(raw)
            val out = HashMap<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.opt(key)?.takeUnless { it == JSONObject.NULL }
            }
            out
        } catch (e: JSONException) {
            mapOf("kind" to "refresh", "ts" to System.currentTimeMillis().toDouble())
        }
    }
}
