package com.lyokone.location

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Whether Android hides the notification of a foreground service that
 * starts now (AutoLNK fork, 4.02).
 *
 * Android 12+ holds the notification of a new foreground service back for
 * ten seconds, so a run that ends sooner shows none. It grants that to an
 * app once in about two minutes: a start that hides its notification makes
 * the app eligible again ten seconds plus two minutes later. A start before
 * that shows its notification at once (`ActiveServices.withinFgsDeferRateLimit`,
 * the same code in Android 12 to 16). The system measures on the uptime
 * clock, which stands still in deep sleep, so two minutes there can be a
 * quarter of an hour on the wall.
 *
 * The app has no other foreground service than the two of this plugin. Each
 * tells this object when it enters the foreground, and a fix run asks before
 * it starts, so it never shows a notification. Every start counts, also one
 * that Android showed at once and that opened no window: the answer errs to
 * the side of no run.
 */
internal object NotificationDeferral {
    private const val PREFS_NAME = "flutter_location_prefs"
    private const val KEY_HIDES_AGAIN_AT = "fgs_notification_hides_again_at"
    private const val KEY_BOOT_COUNT = "fgs_notification_boot_count"

    /** Android's own numbers: 10 s of deferral, then 2 min of exclusion. */
    private const val SYSTEM_WINDOW_MS = 130_000L

    /** The system reads its clock after this object does. */
    private const val MARGIN_MS = 10_000L

    /**
     * Whether a foreground service that starts now keeps its notification
     * hidden for ten seconds. Never before Android 12, which shows every
     * one at once.
     */
    @JvmStatic
    fun hidesNow(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // The uptime clock starts over at a boot, and so does the system's window.
        if (prefs.getInt(KEY_BOOT_COUNT, -1) != bootCount(context)) return true
        return SystemClock.uptimeMillis() >= prefs.getLong(KEY_HIDES_AGAIN_AT, 0L)
    }

    /** A service of this app enters the foreground now. */
    @JvmStatic
    fun onForegroundStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HIDES_AGAIN_AT, SystemClock.uptimeMillis() + SYSTEM_WINDOW_MS + MARGIN_MS)
            .putInt(KEY_BOOT_COUNT, bootCount(context))
            .apply()
    }

    private fun bootCount(context: Context): Int =
        try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        } catch (e: Exception) {
            0
        }
}
