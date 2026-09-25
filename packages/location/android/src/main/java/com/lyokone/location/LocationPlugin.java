package com.lyokone.location;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

/**
 * LocationPlugin.
 *
 * <p>Binds the location service from the ENGINE with the application context, not from the
 * Activity: a headless engine (one the service starts after the app's engine went) has no
 * Activity and still needs the service. The Activity only adds what a prompt or a settings
 * dialog needs, and marks this instance as the app's own engine ({@link #activityHosted()}).
 */
public class LocationPlugin implements FlutterPlugin, ActivityAware {
    private static final String TAG = "LocationPlugin";
    @Nullable
    private MethodCallHandlerImpl methodCallHandler;
    @Nullable
    private StreamHandlerImpl streamHandlerImpl;
    @Nullable
    private FlutterLocationService locationService;
    @Nullable
    private ActivityPluginBinding activityBinding;
    @Nullable
    private Context context;
    private boolean bound;
    private boolean activityHosted;

    /**
     * True once this engine had an Activity: it is the app itself, not a background engine (a
     * push handler, the location service's headless one). Stays true across a configuration
     * change.
     */
    public boolean activityHosted() {
        return activityHosted;
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        final Context appContext = binding.getApplicationContext();
        context = appContext;
        methodCallHandler = new MethodCallHandlerImpl(appContext, this::noteUse);
        methodCallHandler.startListening(binding.getBinaryMessenger());
        streamHandlerImpl = new StreamHandlerImpl(this::noteUse);
        streamHandlerImpl.startListening(binding.getBinaryMessenger());
        try {
            bound = appContext.bindService(new Intent(appContext, FlutterLocationService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Could not bind the location service.", e);
            bound = false;
        }
        if (!bound) {
            methodCallHandler.serviceUnavailable();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        dropService();
        if (bound) {
            bound = false;
            try {
                if (context != null) {
                    context.unbindService(serviceConnection);
                }
            } catch (IllegalArgumentException e) {
                // Not bound any more -- nothing to undo.
            }
        }
        if (methodCallHandler != null) {
            methodCallHandler.stopListening();
            methodCallHandler = null;
        }
        if (streamHandlerImpl != null) {
            streamHandlerImpl.stopListening();
            streamHandlerImpl = null;
        }
        context = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityHosted = true;
        // The app opened: a headless engine the service runs must go before
        // this engine's Dart code runs (see FlutterLocationService.takeOverFromHeadless).
        FlutterLocationService.takeOverFromHeadless();
        activityBinding = binding;
        if (locationService != null) {
            bindActivity(locationService, binding);
        }
    }

    @Override
    public void onDetachedFromActivity() {
        if (locationService != null) {
            unbindActivity(locationService);
        }
        activityBinding = null;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected: " + name);
            if (service instanceof FlutterLocationService.LocalBinder) {
                initialize(((FlutterLocationService.LocalBinder) service).getService());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected:" + name);
            dropService();
        }
    };

    private void noteUse() {
        if (locationService != null) {
            locationService.noteConsumer(this);
        }
    }

    private void initialize(FlutterLocationService service) {
        locationService = service;
        service.bindPlugin(this);

        // The service first, then the location: setting the location drains
        // the calls that arrived before the bind completed, and some of them
        // need the service.
        if (methodCallHandler != null) {
            methodCallHandler.setLocationService(service);
            methodCallHandler.setLocation(service.getLocation());
        }
        if (streamHandlerImpl != null) {
            streamHandlerImpl.setLocation(service.getLocation());
        }

        if (activityBinding != null) {
            bindActivity(service, activityBinding);
        }
    }

    private void bindActivity(FlutterLocationService service, ActivityPluginBinding binding) {
        service.setActivity(binding.getActivity());
        if (service.getLocationActivityResultListener() != null) {
            binding.addActivityResultListener(service.getLocationActivityResultListener());
        }
        if (service.getLocationRequestPermissionsResultListener() != null) {
            binding.addRequestPermissionsResultListener(service.getLocationRequestPermissionsResultListener());
        }
        binding.addRequestPermissionsResultListener(service.getServiceRequestPermissionsResultListener());
    }

    private void unbindActivity(FlutterLocationService service) {
        if (activityBinding != null) {
            activityBinding.removeRequestPermissionsResultListener(service.getServiceRequestPermissionsResultListener());
            if (service.getLocationRequestPermissionsResultListener() != null) {
                activityBinding.removeRequestPermissionsResultListener(service.getLocationRequestPermissionsResultListener());
            }
            if (service.getLocationActivityResultListener() != null) {
                activityBinding.removeActivityResultListener(service.getLocationActivityResultListener());
            }
        }
        service.setActivity(null);
    }

    private void dropService() {
        final FlutterLocationService service = locationService;
        if (service == null) {
            return;
        }
        unbindActivity(service);

        if (streamHandlerImpl != null) {
            streamHandlerImpl.setLocation(null);
        }
        if (methodCallHandler != null) {
            methodCallHandler.setLocationService(null);
            methodCallHandler.setLocation(null);
        }

        service.unbindPlugin(this);
        locationService = null;
    }
}
