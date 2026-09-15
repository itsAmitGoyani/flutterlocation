package com.lyokone.location

import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler

private const val STREAM_CHANNEL_NAME = "lyokone/locationstream"

/**
 * [onUse] runs when a listener attaches: the plugin tells the service this
 * engine consumes the location (see FlutterLocationService.noteConsumer).
 */
internal class StreamHandlerImpl(
    private val onUse: () -> Unit,
) : StreamHandler {
    private var location: FlutterLocation? = null
    private var channel: EventChannel? = null

    /**
     * A listener that arrived before the service connected. The service
     * binds asynchronously, so the first `onLocationChanged.listen()` of a
     * fresh engine (a headless one above all) can precede it.
     */
    private var pendingSink: EventSink? = null

    fun setLocation(location: FlutterLocation?) {
        this.location = location
        val sink = pendingSink
        if (location != null && sink != null) {
            pendingSink = null
            attach(location, sink)
        }
    }

    /**
     * Registers this instance as a stream events handler on the given [messenger].
     */
    fun startListening(messenger: BinaryMessenger) {
        if (channel != null) {
            Log.wtf(TAG, "Setting a method call handler before the last was disposed.")
            stopListening()
        }

        channel =
            EventChannel(messenger, STREAM_CHANNEL_NAME).apply {
                setStreamHandler(this@StreamHandlerImpl)
            }
    }

    /**
     * Clears this instance from listening to stream events.
     */
    fun stopListening() {
        val channel = this.channel
        if (channel == null) {
            Log.d(TAG, "Tried to stop listening when no EventChannel had been initialized.")
            return
        }

        channel.setStreamHandler(null)
        this.channel = null
        pendingSink = null
    }

    override fun onListen(
        arguments: Any?,
        eventsSink: EventSink,
    ) {
        val location = this.location
        if (location == null) {
            pendingSink = eventsSink
            return
        }
        attach(location, eventsSink)
    }

    private fun attach(
        location: FlutterLocation,
        eventsSink: EventSink,
    ) {
        onUse()
        location.events = eventsSink
        if (!location.checkPermissions()) {
            if (location.activity != null) {
                location.requestPermissions()
            } else {
                // Nothing can prompt without an activity; the app decides
                // what to do with a denied stream.
                eventsSink.error("PERMISSION_DENIED", "Location permission is not granted and no activity can request it", null)
            }
            return
        }
        location.startRequestingLocation()
    }

    override fun onCancel(arguments: Any?) {
        pendingSink = null
        val location = this.location ?: return
        location.stopLocationUpdates()
        location.events = null
    }

    companion object {
        private const val TAG = "StreamHandlerImpl"
    }
}
