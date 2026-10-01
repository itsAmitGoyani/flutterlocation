package com.lyokone.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.view.FlutterCallbackInformation

/**
 * Runs the app's registered Dart callback on an engine of its own, with no
 * Activity and no view: the Dart side of location sharing keeps running
 * after the app's engine is gone.
 *
 * One engine per process, whoever asks for it: the service during a drive
 * ([FlutterLocationService]), or the wake hub for a short run at rest
 * ([WakeHub]). The engine lives until its Dart side ends the run
 * ([finish]), the app opens ([destroyNow]), or its Dart side never makes a
 * call ([DART_START_LIMIT_MS]). Main thread only.
 */
object HeadlessLocationEngine {
    private const val TAG = "HeadlessLocationEngine"

    /**
     * How long a new engine may take before its Dart side makes its first
     * call to this plugin. The core init waits up to 20 s for Remote Config;
     * an engine that stays silent past this never started its Dart side, and
     * every wake would wait for it.
     */
    private const val DART_START_LIMIT_MS = 90_000L

    /**
     * The app adds to a headless engine what its own engine gets from the
     * Activity: its own method and event channels. Set it in
     * `Application.onCreate`, before a receiver or a service can start an
     * engine.
     */
    @JvmStatic
    var configureEngine: ((FlutterEngine) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    private var current: FlutterEngine? = null

    /** The engine's own instance of this plugin. */
    private var plugin: LocationPlugin? = null

    private var dartStarted = false

    private val dartStartCheck =
        Runnable {
            if (current != null && !dartStarted) {
                Log.w(TAG, "The headless Dart side made no call in ${DART_START_LIMIT_MS / 1000} s: destroying the engine.")
                destroyNow()
            }
        }

    @JvmStatic
    val isRunning: Boolean
        get() = current != null

    /** Whether [candidate] is the plugin instance of the headless engine. */
    @JvmStatic
    fun owns(candidate: LocationPlugin): Boolean = current != null && plugin === candidate

    /** A call from the headless engine's Dart side proves that it started. */
    @JvmStatic
    fun noteCall(caller: LocationPlugin) {
        if (dartStarted || !owns(caller)) return
        dartStarted = true
        main.removeCallbacks(dartStartCheck)
    }

    /** Starts the engine unless one runs. Returns whether one runs afterwards. */
    @JvmStatic
    fun startIfNone(
        context: Context,
        callbackHandle: Long,
    ): Boolean {
        if (current != null) return true
        val engine = start(context, callbackHandle) ?: return false
        Log.i(TAG, "Headless engine started.")
        current = engine
        plugin = engine.plugins.get(LocationPlugin::class.java) as? LocationPlugin
        dartStarted = false
        main.removeCallbacks(dartStartCheck)
        main.postDelayed(dartStartCheck, DART_START_LIMIT_MS)
        return true
    }

    /** Destroys the engine now: an Activity-hosted engine takes over. */
    @JvmStatic
    fun destroyNow() {
        val engine = current ?: return
        clear()
        engine.destroy()
        WakeHub.onHeadlessGone()
    }

    /**
     * The headless Dart side ended its run: it gave up, or nothing is left
     * to share. Destroyed on the next main-loop turn, because the call that
     * asks for it runs ON this engine, and its result must reach Dart first.
     */
    @JvmStatic
    fun finish() {
        val engine = current ?: return
        clear()
        main.post {
            engine.destroy()
            WakeHub.onHeadlessGone()
        }
    }

    private fun clear() {
        current = null
        plugin = null
        dartStarted = false
        main.removeCallbacks(dartStartCheck)
    }

    /**
     * Returns null when the callback cannot be resolved (the entry function
     * was renamed or moved) or the engine fails to start; the caller then
     * gives up.
     *
     * `FlutterEngine(context)` registers every plugin of the app through the
     * generated registrant, so the location plugin itself is reachable from
     * the headless isolate; [configureEngine] adds the app's own channels.
     */
    private fun start(
        context: Context,
        callbackHandle: Long,
    ): FlutterEngine? {
        return try {
            val loader = FlutterInjector.instance().flutterLoader()
            loader.startInitialization(context)
            loader.ensureInitializationComplete(context, null)
            val callback = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
            if (callback == null) {
                Log.w(TAG, "The headless callback handle $callbackHandle resolves to nothing.")
                return null
            }
            val engine = FlutterEngine(context)
            try {
                configureEngine?.invoke(engine)
            } catch (e: Exception) {
                Log.e(TAG, "The app could not configure the headless engine.", e)
            }
            engine.dartExecutor.executeDartCallback(
                DartExecutor.DartCallback(context.assets, loader.findAppBundlePath(), callback),
            )
            engine
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the headless engine.", e)
            null
        }
    }
}
