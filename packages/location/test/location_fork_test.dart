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
      latitude: 23.03,
      longitude: 72.58,
    );
    final Map<Object?, Object?> args = calls.single.arguments as Map;
    expect(args['heartbeatMs'], 300000);
    expect(args['driveSpeedMps'], 6.7);
    expect(args['latitude'], 23.03);
    expect(args['longitude'], 72.58);
  });

  test('setWakeHandler listens and answers a native wake when it completes',
      () async {
    final List<Map<String, Object?>> received = <Map<String, Object?>>[];
    expect(
      await Location().setWakeHandler((Map<String, Object?> wake) async {
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
      (ByteData? _) {},
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

  test('a platform without the plugin answers false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    expect(await Location().wasLaunchedByLocationEvent(), isFalse);
    expect(await Location().setRelaunchMonitoring(enable: false), isFalse);
    expect(await Location().isBackgroundPermissionGranted(), isFalse);
  });
}
