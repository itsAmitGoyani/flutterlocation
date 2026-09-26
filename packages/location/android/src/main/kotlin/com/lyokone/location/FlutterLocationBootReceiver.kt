package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an app update: brings the location foreground service
 * back when background mode was wanted (a drive), and arms the wake sources
 * again, since the system clears geofences, activity transitions and alarms
 * on both. Both actions are exemptions from the Android 12+ restriction on
 * foreground service starts from the background.
 */
class FlutterLocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext
                WakeMonitor.rearmIfArmed(app)
                FlutterLocationService.restoreIfWanted(app)
            }
        }
    }
}
