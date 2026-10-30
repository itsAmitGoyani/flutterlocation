package com.lyokone.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The two alerts the phone shows its own user about the location share
 * (AutoLNK 4.10, after Life360): "Location services are off" and "Location
 * permissions are off". Native, so a wake receiver posts them for a dead
 * app without a Dart engine, and Dart asks for the same ones through
 * [sync] while it runs. The app sends the localised copy with [setCopy];
 * English is the fallback.
 *
 * [sync] is idempotent: it posts an alert whose cause holds and cancels one
 * whose cause went, so every caller may run it on every event. An alert
 * re-posted with the same cause makes no new sound.
 */
object LocationAlert {
    private const val TAG = "LocationAlert"
    private const val PREFS_NAME = "flutter_location_prefs"
    const val CHANNEL_ID = "flutter_location_alerts"
    private const val ID_SERVICES_OFF = 75419
    private const val ID_PERMISSION_OFF = 75420
    private const val KEY_CHANNEL_NAME = "alert_channel_name"
    private const val KEY_SERVICES_TITLE = "alert_services_off_title"
    private const val KEY_SERVICES_BODY = "alert_services_off_body"
    private const val KEY_PERMISSION_TITLE = "alert_permission_off_title"
    private const val KEY_PERMISSION_BODY = "alert_permission_off_body"
    private const val KEY_ICON_NAME = "alert_icon_name"
    private const val DEFAULT_CHANNEL_NAME = "Location alerts"
    private const val DEFAULT_SERVICES_TITLE = "Location services are off"
    private const val DEFAULT_SERVICES_BODY =
        "You turned off location services, which stopped your location sharing. Turn location services on to reconnect."
    private const val DEFAULT_PERMISSION_TITLE = "Location permissions are off"
    private const val DEFAULT_PERMISSION_BODY =
        "Location sharing only works if the app can access your location all the time. Set the location permission to \"Allow all the time\"."

    private val copyKeys =
        mapOf(
            "channelName" to KEY_CHANNEL_NAME,
            "servicesOffTitle" to KEY_SERVICES_TITLE,
            "servicesOffBody" to KEY_SERVICES_BODY,
            "permissionOffTitle" to KEY_PERMISSION_TITLE,
            "permissionOffBody" to KEY_PERMISSION_BODY,
            "iconName" to KEY_ICON_NAME,
        )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The app's localised copy, kept for the runs that have no Dart engine. */
    @JvmStatic
    fun setCopy(
        context: Context,
        copy: Map<String, String?>,
    ) {
        val editor = prefs(context).edit()
        for ((key, pref) in copyKeys) {
            val value = copy[key]
            if (value.isNullOrEmpty()) editor.remove(pref) else editor.putString(pref, value)
        }
        editor.apply()
    }

    /** Whether the phone's location switch is on. */
    @JvmStatic
    fun servicesEnabled(context: Context): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return manager.isLocationEnabled
        @Suppress("DEPRECATION")
        return Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
    }

    /**
     * Posts or cancels both alerts by the phone's state. [shareWanted] says
     * whether the user belongs to a server that wants the share: without
     * it nothing is posted and both alerts are cancelled. With the switch
     * off the grant cannot help, so only the switch alert shows then.
     */
    @JvmStatic
    fun sync(
        context: Context,
        shareWanted: Boolean,
    ) {
        val app = context.applicationContext
        val servicesOff = shareWanted && !servicesEnabled(app)
        val permissionOff = shareWanted && !servicesOff && !FlutterLocationService.hasBackgroundLocationPermission(app)
        show(app, ID_SERVICES_OFF, servicesOff, KEY_SERVICES_TITLE, DEFAULT_SERVICES_TITLE, KEY_SERVICES_BODY, DEFAULT_SERVICES_BODY)
        show(app, ID_PERMISSION_OFF, permissionOff, KEY_PERMISSION_TITLE, DEFAULT_PERMISSION_TITLE, KEY_PERMISSION_BODY, DEFAULT_PERMISSION_BODY)
    }

    private fun show(
        app: Context,
        id: Int,
        wanted: Boolean,
        titleKey: String,
        titleDefault: String,
        bodyKey: String,
        bodyDefault: String,
    ) {
        val manager = NotificationManagerCompat.from(app)
        if (!wanted) {
            manager.cancel(id)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(app, manager)
        val p = prefs(app)
        val title = p.getString(titleKey, null) ?: titleDefault
        val body = p.getString(bodyKey, null) ?: bodyDefault
        val builder =
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(iconId(app))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        launchIntent(app)?.let { builder.setContentIntent(it) }
        try {
            manager.notify(id, builder.build())
            Log.i(TAG, "Alert $id posted.")
        } catch (e: SecurityException) {
            Log.w(TAG, "The alert could not be posted.", e)
        }
    }

    private fun ensureChannel(
        app: Context,
        manager: NotificationManagerCompat,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = prefs(app).getString(KEY_CHANNEL_NAME, null) ?: DEFAULT_CHANNEL_NAME
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun iconId(app: Context): Int {
        val wanted = prefs(app).getString(KEY_ICON_NAME, null) ?: kDefaultNotificationIconName
        val id = app.resources.getIdentifier(wanted, "drawable", app.packageName)
        if (id != 0) return id
        val fallback = app.resources.getIdentifier(kDefaultNotificationIconName, "drawable", app.packageName)
        return if (fallback != 0) fallback else app.applicationInfo.icon
    }

    private fun launchIntent(app: Context): PendingIntent? {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
