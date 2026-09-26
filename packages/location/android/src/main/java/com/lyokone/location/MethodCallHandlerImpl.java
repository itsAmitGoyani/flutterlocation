package com.lyokone.location;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * The method channel. {@code onUse} runs on every call that reaches the service: the plugin
 * tells the service this engine consumes the location (see
 * {@code FlutterLocationService.noteConsumer}).
 */
final class MethodCallHandlerImpl implements MethodCallHandler {
    private static final String TAG = "MethodCallHandlerImpl";

    private final Context context;
    private final LocationPlugin owner;
    private final Runnable onUse;

    /**
     * Calls that arrived before the service connected. The service binds asynchronously, so the
     * first calls of a fresh engine (a headless one above all) can precede it; they run, in
     * order, once it is there.
     */
    private final List<PendingCall> pending = new ArrayList<>();
    private boolean serviceUnavailable;

    private FlutterLocation location;
    private FlutterLocationService locationService;

    @Nullable
    private MethodChannel channel;

    private static final String METHOD_CHANNEL_NAME = "lyokone/location";

    private static final class PendingCall {
        final MethodCall call;
        final Result result;

        PendingCall(MethodCall call, Result result) {
            this.call = call;
            this.result = result;
        }
    }

    MethodCallHandlerImpl(Context context, LocationPlugin owner, Runnable onUse) {
        this.context = context;
        this.owner = owner;
        this.onUse = onUse;
    }

    void setLocation(FlutterLocation location) {
        this.location = location;
        if (location == null) {
            return;
        }
        final List<PendingCall> queued = new ArrayList<>(pending);
        pending.clear();
        for (PendingCall p : queued) {
            onMethodCall(p.call, p.result);
        }
    }

    void setLocationService(FlutterLocationService locationService) {
        this.locationService = locationService;
    }

    /** The service could not be bound at all: nothing will ever answer, so the waiters are told now. */
    void serviceUnavailable() {
        serviceUnavailable = true;
        rejectPending();
    }

    private void rejectPending() {
        final List<PendingCall> queued = new ArrayList<>(pending);
        pending.clear();
        for (PendingCall p : queued) {
            p.result.error("NO_ACTIVITY", "Location service is not available.", null);
        }
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        // Answered without the service: the wake sources, the wake delivery
        // and platform facts.
        switch (call.method) {
            case "wasLaunchedByLocationEvent":
                result.success(0);
                return;
            case "isBackgroundPermissionGranted":
                result.success(FlutterLocationService.hasBackgroundLocationPermission(context) ? 1 : 0);
                return;
            case "setRelaunchMonitoring":
                onSetRelaunchMonitoring(call, result);
                return;
            case "listenForWakes":
                WakeHub.listen(owner);
                result.success(1);
                return;
            case "stopListeningForWakes":
                WakeHub.unlisten(owner);
                result.success(1);
                return;
            case "requestWake":
                onRequestWake(call, result);
                return;
            default:
                break;
        }
        if (location == null) {
            if (serviceUnavailable) {
                result.error("NO_ACTIVITY", "Location service is not available.", null);
            } else {
                pending.add(new PendingCall(call, result));
            }
            return;
        }
        onUse.run();
        switch (call.method) {
            case "changeSettings":
                onChangeSettings(call, result);
                break;
            case "getLocation":
                onGetLocation(result);
                break;
            case "hasPermission":
                onHasPermission(result);
                break;
            case "requestPermission":
                onRequestPermission(result);
                break;
            case "serviceEnabled":
                onServiceEnabled(result);
                break;
            case "requestService":
                location.requestService(result);
                break;
            case "isBackgroundModeEnabled":
                isBackgroundModeEnabled(result);
                break;
            case "enableBackgroundMode":
                enableBackgroundMode(call, result);
                break;
            case "changeNotificationOptions":
                onChangeNotificationOptions(call, result);
                break;
            case "registerHeadlessCallback":
                onRegisterHeadlessCallback(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * Arms or disarms the wake sources ({@link WakeMonitor}): the leash
     * geofence, the activity transitions and the heartbeat alarm.
     */
    private void onSetRelaunchMonitoring(MethodCall call, Result result) {
        final Boolean enable = call.argument("enable");
        final Number heartbeatMs = call.argument("heartbeatMs");
        final Number driveSpeedMps = call.argument("driveSpeedMps");
        final Number latitude = call.argument("latitude");
        final Number longitude = call.argument("longitude");
        final boolean armed = WakeMonitor.setArmed(
                context,
                Boolean.TRUE.equals(enable),
                heartbeatMs == null ? null : heartbeatMs.longValue(),
                driveSpeedMps == null ? null : driveSpeedMps.floatValue(),
                latitude == null ? null : latitude.doubleValue(),
                longitude == null ? null : longitude.doubleValue());
        result.success(armed ? 1 : 0);
    }

    /**
     * A wake asked from Dart: the push isolate for a viewer. It never uploads
     * itself; the engine that shares takes the wake.
     */
    private void onRequestWake(MethodCall call, Result result) {
        final Map<String, Object> wake = new HashMap<>();
        final Object kind = call.argument("kind");
        wake.put("kind", kind != null ? kind.toString() : "refresh");
        wake.put("ts", (double) System.currentTimeMillis());
        WakeHub.enqueue(context, wake);
        result.success(1);
    }

    /** Native to Dart: one wake for this engine's Dart side. Main thread. */
    void invokeOnWake(Map<String, Object> wake, Result result) {
        if (channel == null) {
            result.error("NO_CHANNEL", "The method channel is gone.", null);
            return;
        }
        channel.invokeMethod("onWake", wake, result);
    }

    /**
     * Registers this instance as a method call handler on the given
     * {@code messenger}.
     */
    void startListening(BinaryMessenger messenger) {
        if (channel != null) {
            Log.wtf(TAG, "Setting a method call handler before the last was disposed.");
            stopListening();
        }

        channel = new MethodChannel(messenger, METHOD_CHANNEL_NAME);
        channel.setMethodCallHandler(this);
    }

    /**
     * Clears this instance from listening to method calls.
     */
    void stopListening() {
        rejectPending();
        if (channel == null) {
            Log.d(TAG, "Tried to stop listening when no MethodChannel had been initialized.");
            return;
        }

        channel.setMethodCallHandler(null);
        channel = null;
    }

    private void onChangeSettings(MethodCall call, Result result) {
        try {
            final Integer locationAccuracy = location.mapFlutterAccuracy.get((Integer) call.argument("accuracy"));
            final Long updateIntervalMilliseconds = new Long((int) call.argument("interval"));
            final Long fastestUpdateIntervalMilliseconds = updateIntervalMilliseconds / 2;
            final Float distanceFilter = new Float((double) call.argument("distanceFilter"));

            location.changeSettings(locationAccuracy, updateIntervalMilliseconds, fastestUpdateIntervalMilliseconds,
                    distanceFilter);

            result.success(1);
        } catch (Exception e) {
            result.error("CHANGE_SETTINGS_ERROR",
                    "An unexcepted error happened during location settings change:" + e.getMessage(), null);
        }
    }

    private void onGetLocation(Result result) {
        location.getLocationResult = result;
        if (!location.checkPermissions()) {
            location.requestPermissions();
        } else {
            location.startRequestingLocation();
        }
    }

    private void onHasPermission(Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(1);
            return;
        }

        if (location.checkPermissions()) {
            result.success(1);
        } else {
            result.success(0);
        }
    }

    private void onServiceEnabled(Result result) {
        try {
            result.success(location.checkServiceEnabled() ? 1 : 0);
        } catch (Exception e) {
            result.error("SERVICE_STATUS_ERROR", "Location service status couldn't be determined", null);
        }
    }

    private void onRequestPermission(Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(1);
            return;
        }

        location.result = result;
        location.requestPermissions();
    }

    /**
     * 2 for a service a native start runs and Dart has not claimed: Dart reads
     * it as off, so a release before the wake arrives leaves it alone, and an
     * acquire claims it through {@code enableBackgroundMode}.
     */
    private void isBackgroundModeEnabled(Result result) {
        if (locationService != null) {
            result.success(this.locationService.backgroundModeAnswer());
        } else {
            result.success(0);
        }
    }

    private void enableBackgroundMode(MethodCall call, Result result) {
        final Boolean enable = call.argument("enable");
        if (locationService != null && enable != null) {
            if (locationService.checkBackgroundPermissions()) {
                if (enable) {
                    locationService.claimForDart();
                    // 0 when the system refused the foreground start (Android 12+
                    // without an exemption): a plain answer, never a thrown error.
                    result.success(locationService.enableBackgroundMode() ? 1 : 0);
                } else {
                    locationService.disableBackgroundMode();

                    result.success(0);
                }
            } else {
                if (enable) {
                    locationService.setResult(result);
                    locationService.requestBackgroundPermissions();
                } else {
                    locationService.disableBackgroundMode();

                    result.success(0);
                }
            }
        } else {
            result.success(0);
        }
    }

    /**
     * The Dart entry point the service runs on a headless engine when it outlives the app's
     * engine; a raw {@code PluginUtilities} callback handle.
     */
    private void onRegisterHeadlessCallback(MethodCall call, Result result) {
        final Number raw = call.argument("handle");
        final long handle = raw == null ? 0L : raw.longValue();
        if (handle == 0L || locationService == null) {
            result.success(0);
            return;
        }
        locationService.setHeadlessCallback(handle);
        result.success(1);
    }

    private void onChangeNotificationOptions(MethodCall call, Result result) {
        try {
            String passedChannelName = call.argument("channelName");
            String channelName = passedChannelName != null
                    ? passedChannelName
                    : FlutterLocationServiceKt.kDefaultChannelName;

            String passedTitle = call.argument("title");
            String title = passedTitle != null
                    ? passedTitle
                    : FlutterLocationServiceKt.kDefaultNotificationTitle;

            String passedIconName = call.argument("iconName");
            String iconName = passedIconName != null
                    ? passedIconName
                    : FlutterLocationServiceKt.kDefaultNotificationIconName;

            String subtitle = call.argument("subtitle");
            String description = call.argument("description");
            Boolean onTapBringToFront = call.argument("onTapBringToFront");
            if (onTapBringToFront == null) {
                onTapBringToFront = false;
            }

            String hexColor = call.argument("color");
            Integer color = null;
            if (hexColor != null) {
                color = Color.parseColor(hexColor);
            }

            NotificationOptions options = new NotificationOptions(
                    channelName,
                    title,
                    iconName,
                    subtitle,
                    description,
                    color,
                    onTapBringToFront);
            Map<String, Object> notificationMeta = this.locationService.changeNotificationOptions(options);
            result.success(notificationMeta);
        } catch (Exception e) {
            result.error("CHANGE_NOTIFICATION_OPTIONS_ERROR",
                    "An unexpected error happened during notification options change:" + e.getMessage(), null);
        }
    }
}
