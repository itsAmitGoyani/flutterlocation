package com.lyokone.location

import android.content.Context
import android.util.Log

/**
 * The grant check that opens every wake event and every start of the watch
 * service (AutoLNK 4.10). A grant that went keeps the heartbeat and the
 * transitions as the watch for its return, posts the alert and queues one
 * `grant_lost` wake so Dart reports the loss. A grant that came back arms
 * every source again and queues a `grant_back` wake so Dart restarts the
 * share. Else the alerts follow the switch.
 */
internal object WakeGuard {
    private const val TAG = "WakeGuard"

    /**
     * Returns true when the grant holds and the caller may do its work.
     * [onQueued] runs once the wake this check queues is stored, or at once
     * when it queues none: a receiver that went async finishes in it.
     * [heartbeat] says the event is the heartbeat, whose next alarm must be
     * set whatever the grant does.
     */
    @JvmStatic
    @JvmOverloads
    fun check(
        context: Context,
        heartbeat: Boolean = false,
        onQueued: (() -> Unit)? = null,
    ): Boolean {
        val app = context.applicationContext
        if (!FlutterLocationService.hasBackgroundLocationPermission(app)) {
            Log.d(TAG, "The background location grant is gone: keeping the heartbeat and the transitions as a watch.")
            WakeMonitor.onGrantLost(app)
            if (heartbeat) WakeMonitor.scheduleHeartbeat(app)
            LocationAlert.sync(app, shareWanted = true)
            if (WakeMonitor.claimGrantLostReport(app)) {
                WakeHub.enqueue(app, mapOf("kind" to "grant_lost", "ts" to now()), onQueued)
            } else {
                onQueued?.invoke()
            }
            return false
        }
        if (WakeMonitor.isGrantLost(app)) {
            Log.d(TAG, "The background location grant is back: arming the wake sources again.")
            WakeMonitor.rearmIfArmed(app)
            LocationAlert.sync(app, shareWanted = true)
            WakeHub.enqueue(app, mapOf("kind" to "grant_back", "ts" to now()), onQueued)
            return false
        }
        LocationAlert.sync(app, shareWanted = true)
        return true
    }

    private fun now(): Double = System.currentTimeMillis().toDouble()
}
