package com.lyokone.location

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONException
import org.json.JSONObject

const val kDefaultChannelName: String = "Location background service"
const val kDefaultNotificationTitle: String = "Location background service running"
const val kDefaultNotificationIconName: String = "navigation_empty_icon"

data class NotificationOptions(
    val channelName: String = kDefaultChannelName,
    val title: String = kDefaultNotificationTitle,
    val iconName: String = kDefaultNotificationIconName,
    val subtitle: String? = null,
    val description: String? = null,
    val color: Int? = null,
    val onTapBringToFront: Boolean = false
) {
    /**
     * The options as a JSON document, so the service can post the same
     * notification again after the process died and came back without a
     * Flutter engine (see [FlutterLocationService.onStartCommand]).
     */
    fun toJson(): String =
        JSONObject()
            .put(KEY_CHANNEL_NAME, channelName)
            .put(KEY_TITLE, title)
            .put(KEY_ICON_NAME, iconName)
            .putOpt(KEY_SUBTITLE, subtitle)
            .putOpt(KEY_DESCRIPTION, description)
            .putOpt(KEY_COLOR, color)
            .put(KEY_ON_TAP_BRING_TO_FRONT, onTapBringToFront)
            .toString()

    companion object {
        private const val KEY_CHANNEL_NAME = "channelName"
        private const val KEY_TITLE = "title"
        private const val KEY_ICON_NAME = "iconName"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_COLOR = "color"
        private const val KEY_ON_TAP_BRING_TO_FRONT = "onTapBringToFront"

        /** Reads what [toJson] wrote; null for nothing or an unreadable document. */
        fun fromJson(raw: String?): NotificationOptions? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(raw)
                NotificationOptions(
                    channelName = json.optString(KEY_CHANNEL_NAME, kDefaultChannelName),
                    title = json.optString(KEY_TITLE, kDefaultNotificationTitle),
                    iconName = json.optString(KEY_ICON_NAME, kDefaultNotificationIconName),
                    subtitle = json.stringOrNull(KEY_SUBTITLE),
                    description = json.stringOrNull(KEY_DESCRIPTION),
                    color = if (json.has(KEY_COLOR) && !json.isNull(KEY_COLOR)) json.getInt(KEY_COLOR) else null,
                    onTapBringToFront = json.optBoolean(KEY_ON_TAP_BRING_TO_FRONT, false)
                )
            } catch (e: JSONException) {
                null
            }
        }

        private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    }
}

class BackgroundNotification(
    private val context: Context,
    private val channelId: String,
    private val notificationId: Int
) {
    private var options: NotificationOptions = NotificationOptions()
    private var builder: NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    init {
        updateNotification(options, false)
    }

    private fun getDrawableId(iconName: String): Int {
        return context.resources.getIdentifier(iconName, "drawable", context.packageName)
    }

    private fun buildBringToFrontIntent(): PendingIntent? {
        val intent: Intent? = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.setPackage(null)
            ?.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        return if (intent != null) {
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }
    }

    private fun updateChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = NotificationManagerCompat.from(context)
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        options: NotificationOptions,
        notify: Boolean
    ) {
        val iconId = getDrawableId(options.iconName).let {
            if (it != 0) it else getDrawableId(kDefaultNotificationIconName)
        }
        builder = builder
            .setContentTitle(options.title)
            .setSmallIcon(iconId)
            .setContentText(options.subtitle)
            .setSubText(options.description)

        builder = if (options.color != null) {
            builder.setColor(options.color).setColorized(true)
        } else {
            builder.setColor(0).setColorized(false)
        }

        builder = if (options.onTapBringToFront) {
            builder.setContentIntent(buildBringToFrontIntent())
        } else {
            builder.setContentIntent(null)
        }

        if (notify) {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())
        }
    }

    fun updateOptions(options: NotificationOptions, isVisible: Boolean) {
        if (options.channelName != this.options.channelName) {
            updateChannel(options.channelName)
        }

        updateNotification(options, isVisible)

        this.options = options
    }

    fun build(): Notification {
        updateChannel(options.channelName)
        return builder.build()
    }
}

/**
 * The location foreground service.
 *
 * Upstream this is a bound-only service: the plugin binds it from the
 * Activity, so a swipe-away or a process kill ends it and nothing restarts
 * it. Here it is also a STARTED, sticky service once background mode is
 * enabled, it persists what it needs to come back (the wish itself, the
 * notification options, the Dart callback handle), and whenever it is in
 * the foreground with no Flutter engine to feed — after a swipe-away, a
 * sticky restart, a reboot or an app update — it runs a headless engine on
 * the registered Dart callback so the app's own location code keeps
 * running. See [ensureDartConsumer].
 */
class FlutterLocationService : Service(), PluginRegistry.RequestPermissionsResultListener {
    companion object {
        private const val TAG = "FlutterLocationService"

        private const val REQUEST_PERMISSIONS_REQUEST_CODE: Int = 641

        private const val ONGOING_NOTIFICATION_ID = 75418
        private const val CHANNEL_ID = "flutter_location_channel_01"

        /** The explicit start that turns the bound service into a started one. */
        const val ACTION_START = "com.lyokone.location.action.START"

        /** The boot receiver's start: restore the foreground state and the Dart consumer. */
        const val ACTION_RESTORE = "com.lyokone.location.action.RESTORE"

        /** A native drive signal ([WakeReceiver]): the foreground for the drive probe. */
        const val ACTION_DRIVE = "com.lyokone.location.action.DRIVE"

        private const val PREFS_NAME = "flutter_location_prefs"
        private const val PREFS_KEY_WANTED = "background_mode_wanted"
        private const val PREFS_KEY_CALLBACK = "headless_callback_handle"
        private const val PREFS_KEY_NOTIFICATION = "notification_options"

        /**
         * How long the service waits after its Dart consumer went before it
         * starts a headless engine: an engine that is being replaced (the app
         * opened while headless, a takeover) re-binds well inside this.
         */
        private const val DART_CHECK_DELAY_MS = 5000L

        /**
         * How long a service the system started (a drive signal, a sticky
         * restart, the boot receiver) waits for Dart to claim it before it
         * stops itself. Dart's own drive probe holds as long.
         */
        private const val PROBE_WINDOW_MS = 180_000L

        @JvmStatic
        private var instance: FlutterLocationService? = null

        /**
         * An Activity-hosted engine is attaching (the app opened): destroys
         * the headless engine NOW, synchronously, before that engine's Dart
         * code runs. The main isolate then reads every preference after the
         * headless isolate's last write, so nothing the two isolates share
         * (an upload queue, a sequence counter) is read stale.
         *
         * Only the Activity attach counts: other engines register this
         * plugin too (firebase_messaging's background engine for a data
         * push, for one) and must neither replace the headless engine nor
         * be taken for a consumer.
         */
        @JvmStatic
        fun takeOverFromHeadless() {
            if (!HeadlessLocationEngine.isRunning) return
            Log.d(TAG, "A foreground engine attached: destroying the headless engine.")
            instance?.cancelDartCheck()
            HeadlessLocationEngine.destroyNow()
        }

        /**
         * Starts the foreground service for a drive the system just signalled
         * (a vehicle transition, a leash exit at car speed). Both events exempt
         * the app from the Android 12+ background start restriction. A refusal
         * answers false, and the caller falls back to a short run.
         */
        @JvmStatic
        fun startForDrive(context: Context): Boolean {
            if (!hasBackgroundLocationPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, FlutterLocationService::class.java).setAction(ACTION_DRIVE))
                true
            } catch (e: Exception) {
                Log.w(TAG, "The system refused the drive start of the foreground service.", e)
                false
            }
        }

        /** Whether the service of this process runs in the foreground now. */
        @JvmStatic
        fun isForegroundNow(): Boolean = instance?.isInForegroundMode() == true

        /** The registered headless entry, or 0. */
        @JvmStatic
        fun callbackHandle(context: Context): Long = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(PREFS_KEY_CALLBACK, 0L)

        /**
         * Starts the foreground service again after a reboot or an app
         * update, when background mode was wanted and the permission still
         * allows location from the background. Both broadcasts are
         * exemptions from the Android 12+ background start restriction.
         */
        @JvmStatic
        fun restoreIfWanted(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREFS_KEY_WANTED, false)) return
            if (!hasBackgroundLocationPermission(context)) {
                Log.d(TAG, "Background mode was wanted but the permission is gone: not restoring.")
                prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
                return
            }
            val intent = Intent(context, FlutterLocationService::class.java).setAction(ACTION_RESTORE)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore the location foreground service.", e)
            }
        }

        /**
         * Fine or coarse location, plus the background grant on Android 10+:
         * what a location foreground service started from the background
         * needs before the system lets it read the position at all.
         */
        @JvmStatic
        fun hasBackgroundLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Binder given to clients
    private val binder = LocalBinder()

    // Service is foreground
    private var isForeground = false

    private var activity: Activity? = null

    private var backgroundNotification: BackgroundNotification? = null

    private val prefs: SharedPreferences by lazy { applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Every bound plugin instance: one per engine that registered the plugin. */
    private val boundPlugins = mutableSetOf<LocationPlugin>()

    /**
     * The plugin instance whose Dart side last USED the location API (a
     * method call, a stream listener). Binding alone does not make a
     * consumer: a push-handling engine binds and never asks for a fix.
     */
    private var consumer: LocationPlugin? = null

    /** An engine with an Activity is the app itself; a headless engine must never run beside it. */
    private val hasForegroundEngine: Boolean
        get() = boundPlugins.any { it.activityHosted() }

    private val dartCheck = Runnable { ensureDartConsumer() }

    /**
     * The service runs for a native start that Dart has not claimed yet. Dart
     * can reconcile before the wake that explains the start reaches it; while
     * this is set, [backgroundModeAnswer] tells it the mode is not its own,
     * so its release leaves the service alone. Dart claims the service when
     * it takes the token ([claimForDart]); else [probeEnd] stops it.
     */
    private var probeUnclaimed = false

    private val probeEnd = Runnable { endProbe() }

    var location: FlutterLocation? = null
        private set

    // Store result until a permission check is resolved
    var result: MethodChannel.Result? = null

    val locationActivityResultListener: PluginRegistry.ActivityResultListener?
        get() = location

    val locationRequestPermissionsResultListener: PluginRegistry.RequestPermissionsResultListener?
        get() = location

    val serviceRequestPermissionsResultListener: PluginRegistry.RequestPermissionsResultListener
        get() = this

    inner class LocalBinder : Binder() {
        fun getService(): FlutterLocationService = this@FlutterLocationService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating service.")
        instance = this

        location = FlutterLocation(applicationContext, null)
        backgroundNotification = BackgroundNotification(
            applicationContext,
            CHANNEL_ID,
            ONGOING_NOTIFICATION_ID
        )
        // The options the app set last time, so a service that comes back
        // without an engine posts the same notification, not the default.
        NotificationOptions.fromJson(prefs.getString(PREFS_KEY_NOTIFICATION, null))?.let {
            backgroundNotification?.updateOptions(it, false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // enableBackgroundMode() already put the service in the foreground;
            // this start only makes it a started, sticky service.
            ACTION_START -> Unit
            ACTION_DRIVE -> startForDriveNow()
            // A null intent is the system's sticky restart after a process
            // kill; ACTION_RESTORE is the boot receiver. Both come without an
            // engine.
            else -> restoreForeground()
        }
        return if (isForeground) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Binding to location service.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Unbinding from location service.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying service.")
        instance = null
        cancelDartCheck()
        mainHandler.removeCallbacks(probeEnd)
        HeadlessLocationEngine.destroyNow()

        location?.dispose()
        location = null
        backgroundNotification = null

        super.onDestroy()
    }

    /** Context-based: works with no Activity attached (a headless engine). */
    fun checkBackgroundPermissions(): Boolean {
        return location?.checkBackgroundPermissions() ?: hasBackgroundLocationPermission(applicationContext)
    }

    fun requestBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activity = this.activity
            if (activity == null) {
                // No activity can show the prompt: answer the waiter instead
                // of throwing into the caller (a headless engine has none).
                result?.error("MISSING_ACTIVITY", "The background location permission can only be requested while an activity is attached.", null)
                result = null
                return
            }
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        } else {
            location?.result = this.result
            location?.requestPermissions()
            // result passed to Location reference here won't be needed
            this.result = null
        }
    }

    fun isInForegroundMode(): Boolean = isForeground

    /** 1: the mode runs and Dart owns it. 2: a native start runs unclaimed. 0: off. */
    fun backgroundModeAnswer(): Int =
        when {
            !isForeground -> 0
            probeUnclaimed -> 2
            else -> 1
        }

    /** Dart asked for the mode: from now on the service is Dart's to stop. */
    fun claimForDart() {
        probeUnclaimed = false
        mainHandler.removeCallbacks(probeEnd)
    }

    private fun openProbeWindow() {
        probeUnclaimed = true
        mainHandler.removeCallbacks(probeEnd)
        mainHandler.postDelayed(probeEnd, PROBE_WINDOW_MS)
    }

    /** Nobody claimed the native start: no drive, no restored drive. */
    private fun endProbe() {
        if (!probeUnclaimed) return
        probeUnclaimed = false
        if (isForeground) {
            Log.d(TAG, "Nothing claimed the native start of the foreground service: stopping it.")
            disableBackgroundMode()
        }
    }

    /**
     * Starts the service in foreground mode. Returns whether it succeeded:
     * on Android 12+ the system can refuse a foreground service start
     * (`ForegroundServiceStartNotAllowedException` when the app has no
     * qualifying exemption at the time of the call), which used to crash
     * with an unhandled exception instead of a normal Dart-side answer.
     *
     * Once in the foreground the service also starts itself, so it is a
     * started service that the system restarts (sticky) after a process
     * kill, and it remembers the wish for the boot receiver. Starting a
     * service that is already in the foreground is allowed from any app
     * state; a refusal only costs the restart, not the mode.
     */
    fun enableBackgroundMode(): Boolean {
        if (isForeground) {
            Log.d(TAG, "Service already in foreground mode.")
            return true
        }
        Log.d(TAG, "Start service in foreground mode.")

        val notification = backgroundNotification!!.build()
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, foregroundServiceType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground mode.", e)
            return false
        }

        isForeground = true

        try {
            applicationContext.startService(Intent(applicationContext, FlutterLocationService::class.java).setAction(ACTION_START))
            prefs.edit().putBoolean(PREFS_KEY_WANTED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "The foreground service could not be marked started; it will not outlive the process.", e)
        }
        return true
    }

    fun disableBackgroundMode() {
        Log.d(TAG, "Stop service in foreground.")
        probeUnclaimed = false
        mainHandler.removeCallbacks(probeEnd)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        isForeground = false

        prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
        cancelDartCheck()
        // Bound plugins keep the service alive; this only clears the started
        // state so the system never restarts it.
        stopSelf()
        destroyHeadlessEngineLater()
    }

    /**
     * A sticky restart or the boot receiver: the process is back without an
     * engine. Re-post the persisted notification, then bring a Dart consumer
     * up. A refusal (the permission is gone, the system declines the
     * foreground start) stops the service so no orphan notification stays.
     */
    private fun restoreForeground() {
        if (isForeground) {
            scheduleDartCheck(0)
            return
        }
        if (!prefs.getBoolean(PREFS_KEY_WANTED, false) || !hasBackgroundLocationPermission(applicationContext)) {
            Log.d(TAG, "Restarted without a wanted background mode or without the permission: stopping.")
            prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
            stopSelf()
            return
        }
        if (!enableBackgroundMode()) {
            Log.w(TAG, "The system refused the foreground restart: stopping.")
            prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
            stopSelf()
            return
        }
        // A restored drive claims it; a phone at rest lets it go.
        openProbeWindow()
        scheduleDartCheck(0)
    }

    /**
     * A drive signal: the foreground now, then a Dart consumer. No persisted
     * wish is needed first: [enableBackgroundMode] persists it. A refusal
     * stops the service at once, so the system never times the start out.
     */
    private fun startForDriveNow() {
        if (isForeground) {
            // Dart's own service stays Dart's; an unclaimed one gets more time.
            if (probeUnclaimed) openProbeWindow()
            scheduleDartCheck(0)
            return
        }
        if (!hasBackgroundLocationPermission(applicationContext) || !enableBackgroundMode()) {
            Log.w(TAG, "The drive start of the foreground service failed: stopping.")
            stopSelf()
            return
        }
        openProbeWindow()
        scheduleDartCheck(0)
    }

    /** The Dart entry point the headless engine runs; a raw `PluginUtilities` callback handle. */
    fun setHeadlessCallback(handle: Long) {
        prefs.edit().putLong(PREFS_KEY_CALLBACK, handle).apply()
    }

    /** A plugin instance (an engine) is bound. */
    fun bindPlugin(plugin: LocationPlugin) {
        boundPlugins.add(plugin)
        if (plugin.activityHosted()) cancelDartCheck()
    }

    /** That instance's Dart side used the location API: it is the consumer. */
    fun noteConsumer(plugin: LocationPlugin) {
        consumer = plugin
        cancelDartCheck()
    }

    /**
     * A bound engine is gone. When it was the consumer, its stream and
     * pending results cannot be delivered any more; in the foreground with
     * no Activity-hosted engine left, a fresh engine is brought up after
     * [DART_CHECK_DELAY_MS] unless one binds and uses the API first.
     */
    fun unbindPlugin(plugin: LocationPlugin) {
        boundPlugins.remove(plugin)
        if (consumer !== plugin) return
        consumer = null
        location?.onConsumerGone()
        if (isForeground && !hasForegroundEngine) scheduleDartCheck(DART_CHECK_DELAY_MS)
    }

    private fun scheduleDartCheck(delayMs: Long) {
        mainHandler.removeCallbacks(dartCheck)
        mainHandler.postDelayed(dartCheck, delayMs)
    }

    fun cancelDartCheck() {
        mainHandler.removeCallbacks(dartCheck)
    }

    /**
     * In the foreground with no engine to feed: runs the registered Dart
     * callback on a headless engine. Without a registered callback the
     * service cannot do anything useful on its own, so it stops rather than
     * keep a notification for nothing.
     */
    private fun ensureDartConsumer() {
        if (!isForeground || consumer != null || HeadlessLocationEngine.isRunning || hasForegroundEngine) return
        val handle = prefs.getLong(PREFS_KEY_CALLBACK, 0L)
        if (handle == 0L) {
            Log.w(TAG, "No headless entry registered: stopping the foreground service.")
            disableBackgroundMode()
            return
        }
        Log.d(TAG, "No Dart consumer: starting the headless engine.")
        if (!HeadlessLocationEngine.startIfNone(applicationContext, handle)) disableBackgroundMode()
    }

    /**
     * Posted, never inline: `disableBackgroundMode()` runs from a method call
     * handled ON the headless engine, and its result must reach Dart before
     * that engine goes.
     */
    private fun destroyHeadlessEngineLater() {
        HeadlessLocationEngine.destroyLater(mainHandler)
    }

    fun changeNotificationOptions(options: NotificationOptions): Map<String, Any>? {
        backgroundNotification?.updateOptions(options, isForeground)
        prefs.edit().putString(PREFS_KEY_NOTIFICATION, options.toJson()).apply()

        return if (isForeground) {
            mapOf("channelId" to CHANNEL_ID, "notificationId" to ONGOING_NOTIFICATION_ID)
        } else {
            null
        }
    }

    fun setActivity(activity: Activity?) {
        this.activity = activity
        location?.setActivity(activity)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requestCode == REQUEST_PERMISSIONS_REQUEST_CODE && permissions.size == 2 &&
            permissions[0] == Manifest.permission.ACCESS_FINE_LOCATION && permissions[1] == Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, background mode can be enabled
                result?.success(if (enableBackgroundMode()) 1 else 0)
                result = null
            } else {
                if (!shouldShowRequestBackgroundPermissionRationale()) {
                    result?.error(
                        "PERMISSION_DENIED_NEVER_ASK",
                        "Background location permission denied forever - please open app settings",
                        null
                    )
                } else {
                    result?.error("PERMISSION_DENIED", "Background location permission denied", null)
                }
                result = null
            }
        }
        return false
    }

    private fun shouldShowRequestBackgroundPermissionRationale(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } ?: false
        } else {
            false
        }
}
