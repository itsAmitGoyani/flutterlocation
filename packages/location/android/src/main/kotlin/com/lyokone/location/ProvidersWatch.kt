package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.util.Log

/**
 * One receiver per process for the phone's location switch (AutoLNK 4.10).
 * The `PROVIDERS_CHANGED` broadcast is implicit, so only a live, registered
 * receiver gets it. The drive service, the watch service and the app's
 * process start all ask for this one, so whichever part of the app lives
 * hears the switch. A flip syncs the two alerts ([LocationAlert]) and queues
 * a `services` wake, so the Dart side reports the switch to the viewers.
 *
 * Registered on the application context and never unregistered: it lives as
 * long as the process, and a second registration is a no-op.
 */
internal object ProvidersWatch {
    private const val TAG = "ProvidersWatch"

    private var receiver: BroadcastReceiver? = null
    private var servicesWereEnabled: Boolean? = null

    @JvmStatic
    @Synchronized
    fun register(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val listener =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = onChanged(context.applicationContext)
            }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        filter.addAction(LocationManager.MODE_CHANGED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(listener, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "The location switch receiver was not registered.", e)
            return
        }
        receiver = listener
        servicesWereEnabled = LocationAlert.servicesEnabled(app)
        Log.d(TAG, "Listening for the location switch.")
    }

    private fun onChanged(app: Context) {
        val enabled = LocationAlert.servicesEnabled(app)
        if (servicesWereEnabled == enabled) return
        servicesWereEnabled = enabled
        Log.i(TAG, "Location services " + (if (enabled) "on." else "off."))
        if (!WakeMonitor.isArmed(app)) return
        LocationAlert.sync(app, shareWanted = true)
        // Dart reads the switch and reports it to the viewers
        // (LocationWakeHandler, kind `services`).
        WakeHub.enqueue(app, mapOf("kind" to "services", "ts" to System.currentTimeMillis().toDouble()))
    }
}
