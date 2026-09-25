package com.lyokone.location

import android.content.Context
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.view.FlutterCallbackInformation

/**
 * Runs the app's registered Dart callback on an engine of its own, with no
 * Activity and no view: the Dart side of location sharing keeps running
 * after the app's engine is gone.
 */
internal object HeadlessLocationEngine {
    private const val TAG = "HeadlessLocationEngine"

    /**
     * Main thread. Returns null when the callback cannot be resolved (a handle
     * from a previous build after an app update) or the engine fails to
     * start; the caller then gives up the foreground service.
     *
     * `FlutterEngine(context)` registers every plugin of the app through the
     * generated registrant, so the location plugin itself is reachable from
     * the headless isolate.
     */
    fun start(
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
