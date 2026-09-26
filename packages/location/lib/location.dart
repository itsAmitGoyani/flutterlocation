// Ignored since there is a bug in the coverage report tool
// https://github.com/dart-lang/coverage/issues/339 coverage:ignore-file
import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:location_platform_interface/location_platform_interface.dart';

export 'package:location_platform_interface/location_platform_interface.dart'
    show LocationAccuracy, LocationData, PermissionStatus;

/// The main access point to the `location` plugin.
class Location implements LocationPlatform {
  /// Initializes the plugin and starts listening for potential platform events.
  factory Location() => instance;

  Location._();

  /// Singleton instance of this class. Use it instead of the factory
  /// constructor to make it explicit that you're using a singleton, not
  /// creating a new `Location` instance each time.
  static Location instance = Location._();

  /// Changes settings of the location request.
  ///
  /// The [accuracy] argument is controlling the precision of the
  /// [LocationData]. The [interval] and [distanceFilter] are controlling how
  /// often a new location is sent through [onLocationChanged]. The
  /// [pausesLocationUpdatesAutomatically] argument indicates whether the
  /// underlying location manager object may pause location updates.
  ///
  /// [interval] and [distanceFilter] are not used on web.
  @override
  Future<bool> changeSettings({
    LocationAccuracy? accuracy = LocationAccuracy.high,
    int? interval = 1000,
    double? distanceFilter = 0,
    bool? pausesLocationUpdatesAutomatically = true,
  }) {
    return LocationPlatform.instance.changeSettings(
      accuracy: accuracy,
      interval: interval,
      distanceFilter: distanceFilter,
      pausesLocationUpdatesAutomatically: pausesLocationUpdatesAutomatically,
    );
  }

  /// Checks if service is enabled in the background mode.
  ///
  /// AutoLNK fork, Android: a service the system started (a drive signal, a
  /// sticky restart, a reboot) answers false until Dart claims it with
  /// [enableBackgroundMode], so a release that runs before the wake arrives
  /// leaves it alone. Unclaimed, it stops itself after three minutes.
  @override
  Future<bool> isBackgroundModeEnabled() {
    return LocationPlatform.instance.isBackgroundModeEnabled();
  }

  /// Enables or disables service in the background mode.
  @override
  Future<bool> enableBackgroundMode({bool? enable = true}) {
    return LocationPlatform.instance.enableBackgroundMode(enable: enable);
  }

  /// Gets the current location of the user.
  ///
  /// Throws an error if the app has no permission to access location. Returns a
  /// [LocationData] object.
  @override
  Future<LocationData> getLocation() async {
    return LocationPlatform.instance.getLocation();
  }

  /// Checks if the app has permission to access location.
  ///
  /// If the result is [PermissionStatus.deniedForever], no dialog will be shown
  /// on [requestPermission]. Returns a [PermissionStatus] object.
  @override
  Future<PermissionStatus> hasPermission() {
    return LocationPlatform.instance.hasPermission();
  }

  /// Requests permission to access location.
  ///
  /// If the result is [PermissionStatus.deniedForever], no dialog will be shown
  /// on [requestPermission]. Returns a [PermissionStatus] object.
  @override
  Future<PermissionStatus> requestPermission() {
    return LocationPlatform.instance.requestPermission();
  }

  /// Checks if the location service is enabled.
  @override
  Future<bool> serviceEnabled() {
    return LocationPlatform.instance.serviceEnabled();
  }

  /// Request the activation of the location service.
  @override
  Future<bool> requestService() {
    return LocationPlatform.instance.requestService();
  }

  /// Returns a stream of [LocationData] objects. The frequency and accuracy of
  /// this stream can be changed with [changeSettings]
  ///
  /// Throws an error if the app has no permission to access location.
  @override
  Stream<LocationData> get onLocationChanged {
    return LocationPlatform.instance.onLocationChanged;
  }

  /// Change options of sticky background notification on Android.
  ///
  /// This method only applies to Android and allows for customizing the
  /// notification, which is shown when [enableBackgroundMode] is set to true.
  ///
  /// Uses [title] as the notification's content title and searches for a
  /// drawable resource with the given [iconName]. If no matching resource is
  /// found, no icon is shown. The content text will be set to [subtitle], while
  /// the sub text will be set to [description]. The notification [color] can
  /// also be customized.
  ///
  /// When [onTapBringToFront] is set to true, tapping the notification will
  /// bring the activity back to the front.
  ///
  /// Both [title] and [channelName] will be set to defaults, if no values are
  /// provided. All other null arguments will be ignored.
  ///
  /// Returns [AndroidNotificationData] if the notification is currently being
  /// shown. This can be used to change the notification from other parts of the
  /// app.
  ///
  /// For Android SDK versions above 25, uses [channelName] for the
  /// [NotificationChannel](https://developer.android.com/reference/android/app/NotificationChannel).
  @override
  Future<AndroidNotificationData?> changeNotificationOptions({
    String? channelName,
    String? title,
    String? iconName,
    String? subtitle,
    String? description,
    Color? color,
    bool? onTapBringToFront,
  }) {
    return LocationPlatform.instance.changeNotificationOptions(
      channelName: channelName,
      title: title,
      iconName: iconName,
      subtitle: subtitle,
      description: description,
      color: color,
      onTapBringToFront: onTapBringToFront,
    );
  }
  // ---------------------------------------------------------------------------
  // The terminated state (AutoLNK fork). On the plugin's own channel, so the
  // platform interface package stays as published.
  // ---------------------------------------------------------------------------

  static const MethodChannel _forkChannel = MethodChannel('lyokone/location');

  /// Android only. Registers the top-level or static Dart function the plugin
  /// runs on a headless engine when the started foreground service outlives
  /// the app's Flutter engine: a swipe-away, a process kill, a reboot or an
  /// app update while [enableBackgroundMode] is on.
  ///
  /// Register on every start: the handle changes with every build. Returns
  /// false where nothing runs headless (iOS, macOS, web) and when
  /// [entryPoint] is not a top-level or static function.
  Future<bool> registerHeadlessEntry(Function entryPoint) async {
    final CallbackHandle? handle = PluginUtilities.getCallbackHandle(entryPoint);
    if (handle == null) {
      return false;
    }
    return _invokeFlag(
      'registerHeadlessCallback',
      <String, Object?>{'handle': handle.toRawHandle()},
    );
  }

  /// Arms or disarms the system events that wake a sleeping or terminated
  /// app under Always.
  ///
  /// iOS: significant-change monitoring, visits monitoring and a region
  /// "leash" around the phone. The plugin re-arms them by itself on every
  /// launch that had them armed, and on a launch for a location event it
  /// also starts the ordinary updates before any Dart code runs.
  ///
  /// Android: a leash geofence, the activity transitions (with the Motion
  /// permission) and a heartbeat alarm every [heartbeatMs] that Doze allows.
  /// A vehicle transition, or a leash exit faster than [driveSpeedMps],
  /// starts the foreground service for a drive; every other event reaches
  /// the handler of [setWakeHandler] without a service. The boot receiver
  /// re-arms them after a reboot or an app update.
  ///
  /// [latitude] and [longitude] put the leash on the spot where the app goes
  /// to sleep. Returns false without the background location grant.
  Future<bool> setRelaunchMonitoring({
    required bool enable,
    int? heartbeatMs,
    double? driveSpeedMps,
    double? latitude,
    double? longitude,
  }) {
    return _invokeFlag(
      'setRelaunchMonitoring',
      <String, Object?>{
        'enable': enable,
        if (heartbeatMs != null) 'heartbeatMs': heartbeatMs,
        if (driveSpeedMps != null) 'driveSpeedMps': driveSpeedMps,
        if (latitude != null && longitude != null) 'latitude': latitude,
        if (latitude != null && longitude != null) 'longitude': longitude,
      },
    );
  }

  static Future<void> Function(Map<String, Object?> wake)? _wakeHandler;

  /// Android only. Makes this engine's Dart side the one that takes the
  /// system wakes: `kind` is `drive`, `leash`, `activity`, `heartbeat` or
  /// `refresh`, and a leash wake carries the point of its exit
  /// (`latitude`, `longitude`, `accuracy`, `speed`, `heading`, `time`). The
  /// wake counts as done when [handler] completes. Null stops listening.
  Future<bool> setWakeHandler(
    Future<void> Function(Map<String, Object?> wake)? handler,
  ) {
    _wakeHandler = handler;
    if (handler == null) {
      _forkChannel.setMethodCallHandler(null);
      return _invokeFlag('stopListeningForWakes');
    }
    _forkChannel.setMethodCallHandler(_onPlatformCall);
    return _invokeFlag('listenForWakes');
  }

  static Future<Object?> _onPlatformCall(MethodCall call) async {
    if (call.method != 'onWake') {
      throw MissingPluginException('No handler for ${call.method}');
    }
    final Future<void> Function(Map<String, Object?> wake)? handler =
        _wakeHandler;
    if (handler == null) {
      return 0;
    }
    final Object? raw = call.arguments;
    final Map<String, Object?> wake = raw is Map
        ? raw.map((Object? k, Object? v) => MapEntry<String, Object?>('$k', v))
        : <String, Object?>{};
    await handler(wake);
    return 1;
  }

  /// Android only. Asks for a wake of [kind] on the engine that shares; the
  /// push isolate uses it for a viewer, so it never uploads itself.
  Future<bool> requestWake({String kind = 'refresh'}) {
    return _invokeFlag('requestWake', <String, Object?>{'kind': kind});
  }

  /// iOS only. True when the system launched this process for a location
  /// event (`UIApplication.LaunchOptionsKey.location`): the app runs in the
  /// background with no UI and should start its location work itself.
  Future<bool> wasLaunchedByLocationEvent() {
    return _invokeFlag('wasLaunchedByLocationEvent');
  }

  /// Whether location may be read from the background: Always on iOS,
  /// `ACCESS_BACKGROUND_LOCATION` on Android 10+ (the fine grant below it).
  /// Answered from the platform, so a headless engine can ask before its
  /// service is bound.
  Future<bool> isBackgroundPermissionGranted() {
    return _invokeFlag('isBackgroundPermissionGranted');
  }

  Future<bool> _invokeFlag(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      final int? value =
          await _forkChannel.invokeMethod<int>(method, arguments);
      return value == 1;
    } on MissingPluginException {
      return false;
    }
  }
}
