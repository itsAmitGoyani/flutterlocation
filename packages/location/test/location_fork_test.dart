import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:location/location.dart';

/// A top-level function: the only kind `PluginUtilities` can hand out a
/// callback handle for.
void headlessEntry() {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('lyokone/location');
  final calls = <MethodCall>[];
  int? answer = 1;

  setUp(() {
    calls.clear();
    answer = 1;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return answer;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('registerHeadlessEntry sends the raw callback handle', () async {
    expect(await Location().registerHeadlessEntry(headlessEntry), isTrue);
    expect(calls, hasLength(1));
    expect(calls.single.method, 'registerHeadlessCallback');
    final handle = (calls.single.arguments as Map)['handle'];
    expect(handle, isA<int>());
    expect(handle, isNot(0));
  });

  test('registerHeadlessEntry refuses a closure without a platform call',
      () async {
    expect(await Location().registerHeadlessEntry(() {}), isFalse);
    expect(calls, isEmpty);
  });

  test('setRelaunchMonitoring passes the flag and reads 1 as true', () async {
    expect(await Location().setRelaunchMonitoring(enable: true), isTrue);
    expect(calls.single.method, 'setRelaunchMonitoring');
    expect((calls.single.arguments as Map)['enable'], isTrue);
    expect((calls.single.arguments as Map).containsKey('latitude'), isFalse);
  });

  test('setRelaunchMonitoring passes the cadence, the speed and the centre',
      () async {
    await Location().setRelaunchMonitoring(
      enable: true,
      heartbeatMs: 300000,
      driveSpeedMps: 6.7,
      fixAccuracyMeters: 50,
      latitude: 23.03,
      longitude: 72.58,
    );
    final Map<Object?, Object?> args = calls.single.arguments as Map;
    expect(args['heartbeatMs'], 300000);
    expect(args['driveSpeedMps'], 6.7);
    expect(args['fixAccuracyMeters'], 50);
    expect(args['latitude'], 23.03);
    expect(args['longitude'], 72.58);
  });

  test('setWakeHandler listens and answers a native wake when it completes',
      () async {
    final received = <Map<String, Object?>>[];
    expect(
      await Location().setWakeHandler((wake) async {
        received.add(wake);
      }),
      isTrue,
    );
    expect(calls.single.method, 'listenForWakes');
    final ByteData? reply = await TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .handlePlatformMessage(
      'lyokone/location',
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('onWake', <String, Object?>{
          'kind': 'leash',
          'latitude': 23.0,
          'longitude': 72.0,
        }),
      ),
      (_) {},
    );
    expect(const StandardMethodCodec().decodeEnvelope(reply!), 1);
    expect(received.single['kind'], 'leash');
    expect(received.single['latitude'], 23.0);
    await Location().setWakeHandler(null);
    expect(calls.last.method, 'stopListeningForWakes');
  });

  test('requestWake sends its kind', () async {
    expect(await Location().requestWake(), isTrue);
    expect(calls.single.method, 'requestWake');
    expect((calls.single.arguments as Map)['kind'], 'refresh');
  });

  test('wasLaunchedByLocationEvent reads 0 as false', () async {
    answer = 0;
    expect(await Location().wasLaunchedByLocationEvent(), isFalse);
    expect(calls.single.method, 'wasLaunchedByLocationEvent');
  });

  test('isBackgroundPermissionGranted reads 1 as true', () async {
    expect(await Location().isBackgroundPermissionGranted(), isTrue);
    expect(calls.single.method, 'isBackgroundPermissionGranted');
  });

  test('setRelaunchMonitoring reads 2 (armed without the leash) as armed',
      () async {
    answer = 2;
    expect(await Location().setRelaunchMonitoring(enable: true), isTrue);
    answer = 0;
    expect(await Location().setRelaunchMonitoring(enable: true), isFalse);
  });

  test('setRelaunchMonitoring passes the boot rule and the fix copy', () async {
    await Location().setRelaunchMonitoring(
      enable: true,
      restoreServiceAtBoot: false,
      fixTitle: 'Updating your location',
      fixBody: 'Members can see where you are',
    );
    final Map<Object?, Object?> args = calls.single.arguments as Map;
    expect(args['restoreServiceAtBoot'], isFalse);
    expect(args['fixTitle'], 'Updating your location');
    expect(args['fixBody'], 'Members can see where you are');
  });

  Future<Object?> platformCall(String method, [Object? arguments]) async {
    final ByteData? reply = await TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .handlePlatformMessage(
      'lyokone/location',
      const StandardMethodCodec()
          .encodeMethodCall(MethodCall(method, arguments)),
      (_) {},
    );
    return const StandardMethodCodec().decodeEnvelope(reply!);
  }

  test('the location launch handler runs on a native location event', () async {
    var launches = 0;
    Location().setLocationLaunchHandler(() async => launches++);
    expect(await platformCall('onLocationLaunch'), 1);
    expect(launches, 1);
    Location().setLocationLaunchHandler(null);
  });

  test('the launch handler and the wake handler share the channel', () async {
    var launches = 0;
    Location().setLocationLaunchHandler(() async => launches++);
    await Location().setWakeHandler((wake) async {});
    await Location().setWakeHandler(null);
    // Dropping the wake handler keeps the launch handler.
    expect(await platformCall('onLocationLaunch'), 1);
    expect(launches, 1);
    expect(await platformCall('onWake', <String, Object?>{'kind': 'x'}), 0);
    Location().setLocationLaunchHandler(null);
  });

  test('backgroundModeState passes the raw state through', () async {
    answer = 2;
    expect(await Location().backgroundModeState(), 2);
    expect(calls.single.method, 'backgroundModeState');
  });

  test('finishHeadlessRun asks the plugin', () async {
    expect(await Location().finishHeadlessRun(), isTrue);
    expect(calls.single.method, 'finishHeadlessRun');
  });

  test('isProtectedDataAvailable reads 0 as false and a missing plugin as true',
      () async {
    answer = 0;
    expect(await Location().isProtectedDataAvailable(), isFalse);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    expect(await Location().isProtectedDataAvailable(), isTrue);
  });

  test('getCurrentFix returns the point, or null when no fix came', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, Object?>{'latitude': 23.0, 'longitude': 72.0};
    });
    final Map<String, Object?>? fix =
        await Location().getCurrentFix(timeoutMs: 5000, highAccuracy: true);
    expect(fix?['latitude'], 23.0);
    expect((calls.single.arguments as Map)['timeoutMs'], 5000);
    expect((calls.single.arguments as Map)['highAccuracy'], isTrue);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => null);
    expect(await Location().getCurrentFix(), isNull);
  });

  test('locationPushToken returns the hex token, null without the plugin',
      () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return 'ab01ff';
    });
    expect(await Location().locationPushToken(), 'ab01ff');
    expect(calls.single.method, 'startMonitoringLocationPushes');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => null);
    expect(await Location().locationPushToken(), isNull);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    expect(await Location().locationPushToken(), isNull);
  });

  test('locationPushToken surfaces the platform error code', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: '3', message: 'no entitlement');
    });
    await expectLater(
      Location().locationPushToken(),
      throwsA(isA<PlatformException>()
          .having((e) => e.code, 'code', '3')),
    );
  });

  test('a platform without the plugin answers false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    expect(await Location().wasLaunchedByLocationEvent(), isFalse);
    expect(await Location().setRelaunchMonitoring(enable: false), isFalse);
    expect(await Location().isBackgroundPermissionGranted(), isFalse);
  });
}
