package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an app update: arms the wake sources again (the system
 * clears geofences and activity transitions at a reboot), then brings the
 * share back. Both broadcasts are exemptions from the Android 12+
 * restriction on foreground service starts from the background.
 *
 * The always-on mode restores its wanted service. The drive-only mode ran
 * the service only for a drive, which a reboot ends, so it drops that wish
 * (a crash or a force stop can leave it set) and takes one fix instead.
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
                if (FlutterLocationService.restoresAtBoot(app)) {
                    FlutterLocationService.restoreIfWanted(app)
                    return
                }
                FlutterLocationService.dropWish(app)
                if (!WakeMonitor.isArmed(app)) return
                val wake = mapOf("kind" to "boot", "ts" to System.currentTimeMillis().toDouble())
                if (!FlutterLocationService.startForFix(app, wake)) WakeHub.enqueue(app, wake)
            }
        }
    }
}
