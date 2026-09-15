package com.lyokone.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * LocationPlugin.
 *
 * Binds the location service from the ENGINE with the application context,
 * not from the Activity: a headless engine (one the service starts after
 * the app's engine went) has no Activity and still needs the service. The
 * Activity only adds what a prompt or a settings dialog needs, and marks
 * this instance as the app's own engine ([activityHosted]).
 */
class LocationPlugin : FlutterPlugin, ActivityAware {
    private var methodCallHandler: MethodCallHandlerImpl? = null
    private var streamHandlerImpl: StreamHandlerImpl? = null
    private var locationService: FlutterLocationService? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var context: Context? = null
    private var bound = false

    /**
     * True once this engine had an Activity: it is the app itself, not a
     * background engine (a push handler, the location service's headless
     * one). Stays true across a configuration change.
     */
    var activityHosted: Boolean = false
        private set

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                Log.d(TAG, "Service connected: $name")
                if (service is FlutterLocationService.LocalBinder) {
                    initialize(service.getService())
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Service disconnected: $name")
                dropService()
            }
        }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val appContext = binding.applicationContext
        context = appContext
        methodCallHandler =
            MethodCallHandlerImpl(::noteUse).apply {
                startListening(binding.binaryMessenger)
            }
        streamHandlerImpl =
            StreamHandlerImpl(::noteUse).apply {
                startListening(binding.binaryMessenger)
            }
        bound =
            try {
                appContext.bindService(
                    Intent(appContext, FlutterLocationService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not bind the location service.", e)
                false
            }
        if (!bound) {
            methodCallHandler?.serviceUnavailable()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        dropService()
        if (bound) {
            bound = false
            try {
                context?.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // Not bound any more -- nothing to undo.
            }
        }
        methodCallHandler?.stopListening()
        methodCallHandler = null
        streamHandlerImpl?.stopListening()
        streamHandlerImpl = null
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityHosted = true
        // The app opened: a headless engine the service runs must go before
        // this engine's Dart code runs (see FlutterLocationService.takeOverFromHeadless).
        FlutterLocationService.takeOverFromHeadless()
        activityBinding = binding
        locationService?.let { bindActivity(it, binding) }
    }

    override fun onDetachedFromActivity() {
        locationService?.let { unbindActivity(it) }
        activityBinding = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    private fun noteUse() {
        locationService?.noteConsumer(this)
    }

    private fun initialize(service: FlutterLocationService) {
        locationService = service
        service.bindPlugin(this)

        // The service first, then the location: setting the location drains
        // the calls that arrived before the bind completed, and some of them
        // need the service.
        methodCallHandler?.setLocationService(service)
        methodCallHandler?.setLocation(service.location)
        streamHandlerImpl?.setLocation(service.location)

        activityBinding?.let { bindActivity(service, it) }
    }

    private fun bindActivity(
        service: FlutterLocationService,
        binding: ActivityPluginBinding,
    ) {
        service.setActivity(binding.activity)
        service.locationActivityResultListener?.let(binding::addActivityResultListener)
        service.locationRequestPermissionsResultListener?.let(binding::addRequestPermissionsResultListener)
        binding.addRequestPermissionsResultListener(service.serviceRequestPermissionsResultListener)
    }

    private fun unbindActivity(service: FlutterLocationService) {
        activityBinding?.let { binding ->
            binding.removeRequestPermissionsResultListener(service.serviceRequestPermissionsResultListener)
            service.locationRequestPermissionsResultListener?.let(binding::removeRequestPermissionsResultListener)
            service.locationActivityResultListener?.let(binding::removeActivityResultListener)
        }
        service.setActivity(null)
    }

    private fun dropService() {
        val service = locationService ?: return
        unbindActivity(service)

        streamHandlerImpl?.setLocation(null)
        methodCallHandler?.setLocationService(null)
        methodCallHandler?.setLocation(null)

        service.unbindPlugin(this)
        locationService = null
    }

    companion object {
        private const val TAG = "LocationPlugin"
    }
}
