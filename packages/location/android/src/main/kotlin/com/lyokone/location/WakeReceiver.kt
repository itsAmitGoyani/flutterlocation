package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * The events [WakeMonitor] armed. A drive signal (a vehicle transition, or a
 * leash exit at car speed) starts the foreground service at once: both
 * events exempt the app from the Android 12+ background start restriction,
 * and a drive needs continuous GPS. Every other event becomes a short run
 * without a service ([WakeHub]), so it shows no notification.
 *
 * While the service already runs, the live stream carries the share and an
 * event only moves the leash.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        if (!WakeMonitor.isArmed(app)) return
        when (intent.action) {
            WakeMonitor.ACTION_HEARTBEAT -> onHeartbeat(app)
            WakeMonitor.ACTION_GEOFENCE -> onGeofence(app, intent)
            WakeMonitor.ACTION_TRANSITION -> onTransition(app, intent)
        }
    }

    private fun onHeartbeat(app: Context) {
        // The next one first, so nothing below can end the chain.
        WakeMonitor.scheduleHeartbeat(app)
        if (FlutterLocationService.isForegroundNow()) return
        WakeHub.enqueue(app, mapOf("kind" to "heartbeat", "ts" to System.currentTimeMillis().toDouble()))
    }

    private fun onGeofence(
        app: Context,
        intent: Intent,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error ${event.errorCode}.")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val location = event.triggeringLocation
        // The next exit must be a real move from here.
        if (location != null) WakeMonitor.setLeash(app, location.latitude, location.longitude)
        if (FlutterLocationService.isForegroundNow()) return
        val drive = location != null && location.hasSpeed() && location.speed >= WakeMonitor.driveSpeedMps(app)
        val kind = if (drive && FlutterLocationService.startForDrive(app)) "drive" else "leash"
        WakeHub.enqueue(app, (location?.let { pointOf(it) } ?: emptyMap()) + ("kind" to kind))
    }

    private fun onTransition(
        app: Context,
        intent: Intent,
    ) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        if (FlutterLocationService.isForegroundNow()) return
        val enter = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        val vehicle = enter && event.activityType == DetectedActivity.IN_VEHICLE
        val kind = if (vehicle && FlutterLocationService.startForDrive(app)) "drive" else "activity"
        // The event clock is the monotonic elapsed-realtime clock.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos) / 1_000_000
        WakeHub.enqueue(
            app,
            mapOf(
                "kind" to kind,
                "activity" to activityName(event.activityType),
                "transition" to if (enter) "enter" else "exit",
                "ts" to (System.currentTimeMillis() - ageMs).toDouble(),
            ),
        )
    }

    private fun pointOf(location: Location): Map<String, Any?> =
        buildMap {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
            if (location.hasSpeed()) put("speed", location.speed.toDouble())
            if (location.hasBearing()) put("heading", location.bearing.toDouble())
            put("time", location.time.toDouble())
        }

    private fun activityName(type: Int): String =
        when (type) {
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.STILL -> "STILL"
            else -> "UNKNOWN"
        }

    private companion object {
        const val TAG = "WakeReceiver"
    }
}
