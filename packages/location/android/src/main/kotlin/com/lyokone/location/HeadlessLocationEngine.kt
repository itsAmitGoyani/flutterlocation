package com.lyokone.location

import android.content.Context
import android.os.Handler
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
 * ([WakeHub]). Main thread only.
 */
internal object HeadlessLocationEngine {
    private const val TAG = "HeadlessLocationEngine"

    private var current: FlutterEngine? = null

    val isRunning: Boolean
        get() = current != null

    /** Starts the engine unless one runs. Returns whether one runs afterwards. */
    fun startIfNone(
        context: Context,
        callbackHandle: Long,
    ): Boolean {
        if (current != null) return true
        current = start(context, callbackHandle)
        return current != null
    }

    /** Destroys the engine now: an Activity-hosted engine takes over. */
    fun destroyNow() {
        val engine = current ?: return
        current = null
        engine.destroy()
        WakeHub.onHeadlessGone()
    }

    /**
     * Destroys the engine on the next main-loop turn: the call that asks for
     * it can run ON this engine, and its result must reach Dart first.
     */
    fun destroyLater(handler: Handler) {
        val engine = current ?: return
        current = null
        handler.post {
            engine.destroy()
            WakeHub.onHeadlessGone()
        }
    }

    /**
     * Returns null when the callback cannot be resolved (a handle from a
     * previous build after an app update) or the engine fails to start; the
     * caller then gives up.
     *
     * `FlutterEngine(context)` registers every plugin of the app through the
     * generated registrant, so the location plugin itself is reachable from
     * the headless isolate.
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
                Log.w(TAG, "The headless callback handle $callbackHandle resolves to nothing (stale after an update?).")
                return null
            }
            val engine = FlutterEngine(context)
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
