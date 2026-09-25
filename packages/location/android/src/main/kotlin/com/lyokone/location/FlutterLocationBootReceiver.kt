package com.lyokone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the location foreground service back after a reboot or an app
 * update when background mode was wanted. Both actions are exemptions from
 * the Android 12+ restriction on foreground service starts from the
 * background.
 */
class FlutterLocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                FlutterLocationService.restoreIfWanted(context.applicationContext)
        }
    }
}
