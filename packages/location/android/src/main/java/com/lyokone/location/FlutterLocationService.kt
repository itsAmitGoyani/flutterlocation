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
import android.location.Location
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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

    /**
     * The notification of a short fix run: its own copy, and deferred, so
     * Android 12+ shows nothing for a run that ends within 10 s. The
     * options the app set stay for a drive.
     */
    fun buildFix(
        title: String?,
        text: String?,
    ): Notification {
        updateChannel(options.channelName)
        val iconId =
            getDrawableId(options.iconName).let {
                if (it != 0) it else getDrawableId(kDefaultNotificationIconName)
            }
        val fix =
            NotificationCompat.Builder(context, channelId)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(iconId)
                .setContentTitle(title ?: options.title)
                .setContentText(text)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        if (options.onTapBringToFront) fix.setContentIntent(buildBringToFrontIntent())
        return fix.build()
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

        /** A short fix run for a wake ([startForFix]); the wake rides in [EXTRA_WAKE]. */
        const val ACTION_FIX = "com.lyokone.location.action.FIX"
        private const val EXTRA_WAKE = "wake"

        private const val PREFS_NAME = "flutter_location_prefs"
        private const val PREFS_KEY_WANTED = "background_mode_wanted"
        private const val PREFS_KEY_CALLBACK = "headless_callback_handle"
        private const val PREFS_KEY_NOTIFICATION = "notification_options"
        private const val PREFS_KEY_RESTORE_AT_BOOT = "restore_at_boot"
        private const val PREFS_KEY_FIX_TITLE = "fix_title"
        private const val PREFS_KEY_FIX_BODY = "fix_body"

        /**
         * How long a fix run waits for its fix. The run then ends inside the
         * 10 s for which Android 12+ holds back the notification of a
         * foreground service, so a run at rest shows none.
         */
        private const val FIX_RUN_MS = 7_000L
        private const val FIX_RUN_LIMIT_MS = 7_500L

        /** A fix this recent serves the run; an older one is taken again. */
        private const val FIX_MAX_AGE_MS = 5_000L

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

        /**
         * Starts a short fix run for a system wake (a leash exit, an
         * activity change, a refresh push, a reboot): the foreground for one
         * fix, the only way a sleeping app gets a fresh one (Android gives a
         * background app a few fixes an hour). Only an exempt event may start
         * it; a refusal answers false, and the caller queues the wake without
         * a fix. The fix rides the wake to Dart; at car speed the run becomes
         * the drive probe.
         */
        @JvmStatic
        fun startForFix(
            context: Context,
            wake: Map<String, Any?>,
        ): Boolean {
            if (!hasBackgroundLocationPermission(context)) return false
            return try {
                val intent =
                    Intent(context, FlutterLocationService::class.java)
                        .setAction(ACTION_FIX)
                        .putExtra(EXTRA_WAKE, JSONObject(wake).toString())
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "The system refused the fix run of the foreground service.", e)
                false
            }
        }

        /**
         * What the app says about its service (the wake options of
         * `setRelaunchMonitoring`): whether a reboot brings the service back
         * (the old always-on mode) or only runs one fix (the drive-only
         * mode), and the copy of a fix run's notification.
         */
        @JvmStatic
        fun saveWakeOptions(
            context: Context,
            restoreAtBoot: Boolean?,
            fixTitle: String?,
            fixBody: String?,
        ) {
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            if (restoreAtBoot != null) editor.putBoolean(PREFS_KEY_RESTORE_AT_BOOT, restoreAtBoot)
            if (fixTitle != null) editor.putString(PREFS_KEY_FIX_TITLE, fixTitle)
            if (fixBody != null) editor.putString(PREFS_KEY_FIX_BODY, fixBody)
            editor.apply()
        }

        /** Whether a reboot restores a wanted service; true when the app never said. */
        @JvmStatic
        fun restoresAtBoot(context: Context): Boolean = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREFS_KEY_RESTORE_AT_BOOT, true)

        /**
         * Forgets the wish for the service: in the drive-only mode a reboot
         * ends any drive, and a wish that a crash or a force stop left behind
         * would post the drive notification at a later boot.
         */
        @JvmStatic
        fun dropWish(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREFS_KEY_WANTED, false).apply()
        }

        /**
         * Whether the service of this process runs in the foreground for the
         * share (a drive, the always-on mode). A short fix run does not count:
         * an event during one must still act.
         */
        @JvmStatic
        fun isForegroundNow(): Boolean = instance?.let { it.isInForegroundMode() && !it.fixRunOnly } == true

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

    /**
     * The foreground runs for one fix only ([runFix]), not for the share. It
     * answers 0 to Dart, never restarts sticky, and stops at the fix unless
     * a drive or Dart claims it first ([adoptFixRun]).
     */
    private var fixRunOnly = false

    /** The wakes that wait for the fix of the current run. */
    private val fixRunWakes = mutableListOf<Map<String, Any?>>()
    private var fixCancel: CancellationTokenSource? = null
    private var fixDone = true
    private val fixLimit = Runnable { finishFix(null) }

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
            ACTION_FIX -> runFix(intent.getStringExtra(EXTRA_WAKE))
            // The boot receiver: a foreground start the system waits for.
            ACTION_RESTORE -> restoreForeground(fromBoot = true)
            // A null intent is the system's sticky restart after a process
            // kill. Both come without an engine.
            else -> restoreForeground(fromBoot = false)
        }
        // A fix run must never come back after a kill; the share does.
        return if (isForeground && !fixRunOnly) START_STICKY else START_NOT_STICKY
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
        mainHandler.removeCallbacks(fixLimit)
        fixCancel?.cancel()
        fixCancel = null
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

    /**
     * 1: the mode runs and Dart owns it. 2: a native start runs unclaimed.
     * 0: off, or only a short fix run.
     */
    fun backgroundModeAnswer(): Int =
        when {
            !isForeground || fixRunOnly -> 0
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
     * state; a refusal only costs the restart, not the mode. A short fix run
     * that is on becomes the mode.
     */
    fun enableBackgroundMode(): Boolean {
        if (isForeground) {
            Log.d(TAG, "Service already in foreground mode.")
            if (fixRunOnly) adoptFixRun()
            return true
        }
        Log.d(TAG, "Start service in foreground mode.")

        val notification = backgroundNotification!!.build()
        try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, locationServiceType())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground mode.", e)
            return false
        }

        isForeground = true
        markStarted()
        return true
    }

    private fun locationServiceType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    /** A started, sticky service with the wish persisted: what outlives the process. */
    private fun markStarted() {
        try {
            applicationContext.startService(Intent(applicationContext, FlutterLocationService::class.java).setAction(ACTION_START))
            prefs.edit().putBoolean(PREFS_KEY_WANTED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "The foreground service could not be marked started; it will not outlive the process.", e)
        }
    }

    /**
     * Stops the foreground and the started state. The headless engine stays:
     * a Dart side that asked for this is still running its reconcile (the
     * rest centre, the last points, the trip end) and ends its own run
     * ([HeadlessLocationEngine.finish]).
     */
    fun disableBackgroundMode() {
        Log.d(TAG, "Stop service in foreground.")
        probeUnclaimed = false
        fixRunOnly = false
        mainHandler.removeCallbacks(probeEnd)
        leaveForeground()

        prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
        cancelDartCheck()
        // Bound plugins keep the service alive; this only clears the started
        // state so the system never restarts it.
        stopSelf()
    }

    private fun leaveForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
    }

    /**
     * A sticky restart or the boot receiver: the process is back without an
     * engine. Re-post the persisted notification, then bring a Dart consumer
     * up. A refusal (the permission is gone, the system declines the
     * foreground start) stops the service so no orphan notification stays,
     * and a wake still tells Dart, which then sends what it can.
     */
    private fun restoreForeground(fromBoot: Boolean) {
        if (isForeground) {
            if (!fixRunOnly) scheduleDartCheck(0)
            return
        }
        val permitted = hasBackgroundLocationPermission(applicationContext)
        if (!prefs.getBoolean(PREFS_KEY_WANTED, false) || !permitted) {
            Log.d(TAG, "Restarted without a wanted background mode or without the permission: stopping.")
            prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
            // The boot start is a foreground start: it must reach the
            // foreground before it stops, or Android ends the app.
            if (fromBoot && permitted) enterAndLeaveForeground() else stopSelf()
            return
        }
        if (!enableBackgroundMode()) {
            // Android 12+ lists no exemption for a sticky restart.
            Log.w(TAG, "The system refused the foreground restart: stopping.")
            prefs.edit().putBoolean(PREFS_KEY_WANTED, false).apply()
            stopSelf()
            WakeHub.enqueue(applicationContext, mapOf("kind" to "refresh", "ts" to System.currentTimeMillis().toDouble()))
            return
        }
        // A restored drive claims it; a phone at rest lets it go.
        openProbeWindow()
        scheduleDartCheck(0)
    }

    /** A foreground start that has nothing to do: reach the foreground, then leave it. */
    private fun enterAndLeaveForeground() {
        try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, backgroundNotification!!.buildFix(null, null), locationServiceType())
            leaveForeground()
        } catch (e: Exception) {
            Log.w(TAG, "The foreground start could not be answered.", e)
        }
        stopSelf()
    }

    /**
     * A drive signal: the foreground now, then a Dart consumer. No persisted
     * wish is needed first: [enableBackgroundMode] persists it. A fix run
     * that is on becomes the drive probe.
     */
    private fun startForDriveNow() {
        if (isForeground) {
            if (fixRunOnly) {
                adoptFixRun()
                openProbeWindow()
            } else if (probeUnclaimed) {
                // Dart's own service stays Dart's; an unclaimed one gets more time.
                openProbeWindow()
            }
            scheduleDartCheck(0)
            return
        }
        // The grant was checked before the start; Android kills the process
        // when it goes, so only a failed foreground start reaches this.
        if (!hasBackgroundLocationPermission(applicationContext) || !enableBackgroundMode()) {
            Log.w(TAG, "The drive start of the foreground service failed: stopping.")
            stopSelf()
            return
        }
        openProbeWindow()
        scheduleDartCheck(0)
    }

    /**
     * One fix for a wake. The foreground makes the app a foreground app for
     * location, so the fix comes at once instead of a few times an hour. The
     * run ends at the fix, inside the 10 s notification delay, unless the
     * fix shows car speed (the run becomes the drive probe) or Dart claims
     * the service meanwhile.
     */
    private fun runFix(raw: String?) {
        val wake = parseWake(raw)
        if (isForeground && !fixRunOnly) {
            // The share streams already: the wake only tells Dart.
            WakeHub.enqueue(applicationContext, wake)
            return
        }
        if (fixRunOnly) {
            // A fix is on its way; this wake rides it.
            fixRunWakes.add(wake)
            return
        }
        if (!hasBackgroundLocationPermission(applicationContext) || !enterForegroundForFix()) {
            stopSelf()
            WakeHub.enqueue(applicationContext, wake)
            return
        }
        fixRunWakes.add(wake)
        requestFix()
    }

    private fun enterForegroundForFix(): Boolean {
        val notification = backgroundNotification?.buildFix(prefs.getString(PREFS_KEY_FIX_TITLE, null), prefs.getString(PREFS_KEY_FIX_BODY, null)) ?: return false
        return try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, locationServiceType())
            isForeground = true
            fixRunOnly = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "The fix run could not enter the foreground.", e)
            false
        }
    }

    private fun requestFix() {
        val cancel = CancellationTokenSource()
        fixCancel = cancel
        fixDone = false
        mainHandler.removeCallbacks(fixLimit)
        mainHandler.postDelayed(fixLimit, FIX_RUN_LIMIT_MS)
        try {
            val request =
                CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(FIX_RUN_MS)
                    .setMaxUpdateAgeMillis(FIX_MAX_AGE_MS)
                    .build()
            LocationServices.getFusedLocationProviderClient(applicationContext)
                .getCurrentLocation(request, cancel.token)
                .addOnCompleteListener { task -> finishFix(if (task.isSuccessful) task.result else null) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission for the fix run.", e)
            finishFix(null)
        }
    }

    /** Once per run: the fix (or none) goes to Dart with every wake that waited for it. */
    private fun finishFix(fix: Location?) {
        if (fixDone) return
        fixDone = true
        mainHandler.removeCallbacks(fixLimit)
        fixCancel?.cancel()
        fixCancel = null
        Log.i(TAG, "Fix run ended with " + if (fix == null) "no fix." else if (fix.hasAccuracy()) "a fix of ${fix.accuracy.toInt()} m." else "a fix.")
        val app = applicationContext
        val wakes = fixRunWakes.toList()
        fixRunWakes.clear()
        if (fix != null && fix.hasAccuracy() && fix.accuracy <= WakeMonitor.LEASH_MAX_ACCURACY_M) {
            WakeMonitor.setLeash(app, fix.latitude, fix.longitude)
        }
        val point = fix?.let { WakeHub.pointOf(it) } ?: emptyMap()
        val drive = fix != null && fix.hasSpeed() && fix.speed >= WakeMonitor.driveSpeedMps(app)
        if (drive) {
            if (fixRunOnly) {
                adoptFixRun()
                openProbeWindow()
                scheduleDartCheck(0)
            }
            WakeHub.enqueue(app, point + ("kind" to "drive") + ("fixRun" to true) + ("ts" to System.currentTimeMillis().toDouble()))
            return
        }
        if (fixRunOnly) {
            fixRunOnly = false
            leaveForeground()
            stopSelf()
        }
        for (wake in wakes) WakeHub.enqueue(app, wake + point + ("fixRun" to true))
    }

    /** The fix run becomes the mode: a drive signal, a drive fix, or a Dart claim. */
    private fun adoptFixRun() {
        fixRunOnly = false
        markStarted()
        // The drive copy, not the fix copy.
        try {
            backgroundNotification?.let { NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, it.build()) }
        } catch (e: SecurityException) {
            Log.w(TAG, "The drive notification could not replace the fix notification.", e)
        }
    }

    private fun parseWake(raw: String?): Map<String, Any?> {
        if (raw.isNullOrEmpty()) return mapOf("kind" to "refresh", "ts" to System.currentTimeMillis().toDouble())
        return try {
            val json = JSONObject(raw)
            val out = HashMap<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.opt(key)?.takeUnless { it == JSONObject.NULL }
            }
            out
        } catch (e: JSONException) {
            mapOf("kind" to "refresh", "ts" to System.currentTimeMillis().toDouble())
        }
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
        if (isForeground && !fixRunOnly && !hasForegroundEngine) scheduleDartCheck(DART_CHECK_DELAY_MS)
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
        if (!isForeground || fixRunOnly || consumer != null || HeadlessLocationEngine.isRunning || hasForegroundEngine) return
        val handle = prefs.getLong(PREFS_KEY_CALLBACK, 0L)
        if (handle == 0L) {
            Log.w(TAG, "No headless entry registered: stopping the foreground service.")
            disableBackgroundMode()
            return
        }
        Log.d(TAG, "No Dart consumer: starting the headless engine.")
        if (!HeadlessLocationEngine.startIfNone(applicationContext, handle)) disableBackgroundMode()
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
