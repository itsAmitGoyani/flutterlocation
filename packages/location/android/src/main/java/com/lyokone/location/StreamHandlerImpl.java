package com.lyokone.location;

import android.util.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;

/**
 * The location stream. {@code onUse} runs when a listener attaches: the plugin tells the
 * service this engine consumes the location (see {@code FlutterLocationService.noteConsumer}).
 */
class StreamHandlerImpl implements StreamHandler {
    private static final String TAG = "StreamHandlerImpl";

    private final Runnable onUse;

    private FlutterLocation location;
    private EventChannel channel;

    /**
     * A listener that arrived before the service connected. The service binds asynchronously,
     * so the first {@code onLocationChanged.listen()} of a fresh engine (a headless one above
     * all) can precede it.
     */
    private EventSink pendingSink;

    private static final String STREAM_CHANNEL_NAME = "lyokone/locationstream";

    StreamHandlerImpl(Runnable onUse) {
        this.onUse = onUse;
    }

    void setLocation(FlutterLocation location) {
        this.location = location;
        final EventSink sink = pendingSink;
        if (location != null && sink != null) {
            pendingSink = null;
            attach(location, sink);
        }
    }

    /**
     * Registers this instance as a stream events handler on the given
     * {@code messenger}.
     */
    void startListening(BinaryMessenger messenger) {
        if (channel != null) {
            Log.wtf(TAG, "Setting a method call handler before the last was disposed.");
            stopListening();
        }

        channel = new EventChannel(messenger, STREAM_CHANNEL_NAME);
        channel.setStreamHandler(this);
    }

    /**
     * Clears this instance from listening to stream events.
     */
    void stopListening() {
        pendingSink = null;
        if (channel == null) {
            Log.d(TAG, "Tried to stop listening when no MethodChannel had been initialized.");
            return;
        }

        channel.setStreamHandler(null);
        channel = null;
    }

    @Override
    public void onListen(Object arguments, final EventSink eventsSink) {
        final FlutterLocation location = this.location;
        if (location == null) {
            pendingSink = eventsSink;
            return;
        }
        attach(location, eventsSink);
    }

    private void attach(FlutterLocation location, EventSink eventsSink) {
        onUse.run();
        location.events = eventsSink;

        if (!location.checkPermissions()) {
            if (location.activity != null) {
                location.requestPermissions();
            } else {
                // Nothing can prompt without an activity; the app decides
                // what to do with a denied stream.
                eventsSink.error("PERMISSION_DENIED", "Location permission is not granted and no activity can request it", null);
            }
            return;
        }
        location.startRequestingLocation();
    }

    @Override
    public void onCancel(Object arguments) {
        pendingSink = null;
        final FlutterLocation location = this.location;
        if (location == null) {
            return;
        }
        if (location.mFusedLocationClient != null && location.mLocationCallback != null) {
            location.mFusedLocationClient.removeLocationUpdates(location.mLocationCallback);
        }
        location.events = null;
    }

}
