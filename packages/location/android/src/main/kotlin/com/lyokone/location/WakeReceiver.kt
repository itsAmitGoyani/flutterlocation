package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The events [WakeMonitor] armed. A drive signal (a vehicle transition, or a
 * leash exit at car speed) starts the foreground service at once: both
 * events exempt the app from the Android 12+ background start restriction,
 * and a drive needs continuous GPS. A leash exit, a stop and a parked car
 * start a short fix run ([FlutterLocationService.startForFix]) under the
 * same exemption, so the wake carries a fresh fix; the other events become
 * a short run without a service ([WakeHub]).
 *
 * While the service already runs for the share, the live stream carries it,
 * and an event only moves the leash.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        if (!WakeMonitor.isArmed(app)) return
        if (!FlutterLocationService.hasBackgroundLocationPermission(app)) {
            // The grant went while the app was dead: nothing may wake it now.
            Log.d(TAG, "The background location grant is gone: disarming the wake sources.")
            WakeMonitor.disarm(app)
            return
        }
        // The job write happens on WorkManager's thread; the broadcast's
        // wake lock must last until then.
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val finish = {
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        main.postDelayed({ finish() }, ASYNC_LIMIT_MS)
        try {
            when (intent.action) {
                WakeMonitor.ACTION_HEARTBEAT -> onHeartbeat(app, finish)
                WakeMonitor.ACTION_GEOFENCE -> onGeofence(app, intent, finish)
                WakeMonitor.ACTION_TRANSITION -> onTransition(app, intent, finish)
                WakeMonitor.ACTION_FIX -> onFix(app, intent, finish)
                else -> finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "A wake event failed.", e)
            finish()
        }
    }

    private fun onHeartbeat(
        app: Context,
        finish: () -> Unit,
    ) {
        // The next one first, so nothing below can end the chain.
        WakeMonitor.scheduleHeartbeat(app)
        if (FlutterLocationService.isForegroundNow()) return finish()
        // A dropped leash cannot report a move; add it again.
        val leashOk = WakeMonitor.isLeashOk(app)
        if (!leashOk) WakeMonitor.armLeash(app)
        WakeHub.enqueue(app, mapOf("kind" to "heartbeat", "ts" to now(), "leashOk" to leashOk), finish)
    }

    private fun onGeofence(
        app: Context,
        intent: Intent,
        finish: () -> Unit,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return finish()
        if (event.hasError()) {
            Log.w(TAG, "Geofence error ${event.errorCode}.")
            // Play services dropped every geofence (Google Location Accuracy
            // off); the heartbeat adds the leash again.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) WakeMonitor.onLeashLost(app)
            return finish()
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return finish()
        val location = event.triggeringLocation
        if (FlutterLocationService.isForegroundNow()) {
            // The share streams: the leash only follows the phone.
            if (location != null) WakeMonitor.setLeash(app, location.latitude, location.longitude)
            return finish()
        }
        val point = location?.let { WakeHub.pointOf(it) } ?: emptyMap()
        val driveFix = location?.takeIf { it.hasSpeed() && it.speed >= WakeMonitor.driveSpeedMps(app) }
        if (driveFix != null && FlutterLocationService.startForDrive(app)) {
            WakeMonitor.setLeash(app, driveFix.latitude, driveFix.longitude)
            WakeHub.enqueue(app, point + ("kind" to "drive") + ("ts" to now()), finish)
            return
        }
        // The fix run re-centres the leash on its own fix.
        if (FlutterLocationService.startForFix(app, point + ("kind" to "leash") + ("ts" to now()))) return finish()
        // No fix run: the exit point is the best centre there is.
        if (location != null) WakeMonitor.setLeash(app, location.latitude, location.longitude)
        WakeHub.enqueue(app, point + ("kind" to "leash") + ("ts" to now()), finish)
    }

    private fun onTransition(
        app: Context,
        intent: Intent,
        finish: () -> Unit,
    ) {
        if (!ActivityTransitionResult.hasResult(intent)) return finish()
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        val last = events.lastOrNull() ?: return finish()
        if (FlutterLocationService.isForegroundNow()) return finish()
        val wake =
            mapOf(
                "kind" to "activity",
                "activity" to activityName(last.activityType),
                "transition" to if (last.isEnter()) "enter" else "exit",
                // The event clock is the monotonic elapsed-realtime clock.
                "ts" to (System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - last.elapsedRealTimeNanos) / 1_000_000).toDouble(),
            )
        if (startsDrive(events) && FlutterLocationService.startForDrive(app)) {
            WakeHub.enqueue(app, wake + ("kind" to "drive"), finish)
            return
        }
        // A stop and a parked car fix the place the phone now rests at.
        val rests = (last.activityType == DetectedActivity.STILL && last.isEnter()) || (last.activityType == DetectedActivity.IN_VEHICLE && !last.isEnter())
        if (rests && FlutterLocationService.startForFix(app, wake)) return finish()
        WakeHub.enqueue(app, wake, finish)
    }

    /**
     * One of the fused provider's own background fixes at rest: a real point
     * without a service. At car speed it starts the drive, like a leash exit.
     * Since 4.02 nothing arms that request; the branch stays for the request
     * an install updated from 4.01 holds until [WakeMonitor] removes it.
     */
    private fun onFix(
        app: Context,
        intent: Intent,
        finish: () -> Unit,
    ) {
        if (!LocationResult.hasResult(intent)) return finish()
        val location = LocationResult.extractResult(intent)?.lastLocation ?: return finish()
        if (FlutterLocationService.isForegroundNow()) return finish()
        // The leash follows the phone under its own rules (accuracy, spacing).
        WakeMonitor.onFix(app, location)
        val point = WakeHub.pointOf(location)
        if (location.hasSpeed() && location.speed >= WakeMonitor.driveSpeedMps(app) && FlutterLocationService.startForDrive(app)) {
            WakeHub.enqueue(app, point + ("kind" to "drive") + ("ts" to now()), finish)
            return
        }
        WakeHub.enqueue(app, point + ("kind" to "fix") + ("ts" to now()), finish)
    }

    /**
     * A batch can hold more than one event. A vehicle enter that no later
     * vehicle exit of the same batch undoes is a drive, whatever event came
     * last.
     */
    private fun startsDrive(events: List<ActivityTransitionEvent>): Boolean {
        var driving = false
        for (event in events) {
            if (event.activityType == DetectedActivity.IN_VEHICLE) driving = event.isEnter()
        }
        return driving
    }

    private fun ActivityTransitionEvent.isEnter(): Boolean = transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER

    private fun now(): Double = System.currentTimeMillis().toDouble()

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

        /** A receiver that went async must finish well inside the broadcast timeout. */
        const val ASYNC_LIMIT_MS = 5_000L
    }
}
