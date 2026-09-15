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

  test('setSignificantChangeMonitoring passes the flag and reads 1 as true',
      () async {
    expect(
      await Location().setSignificantChangeMonitoring(enable: true),
      isTrue,
    );
    expect(calls.single.method, 'setSignificantChangeMonitoring');
    expect((calls.single.arguments as Map)['enable'], isTrue);
  });

  test('wasLaunchedByLocationEvent reads 0 as false', () async {
    answer = 0;
    expect(await Location().wasLaunchedByLocationEvent(), isFalse);
    expect(calls.single.method, 'wasLaunchedByLocationEvent');
  });

  test('a platform without the plugin answers false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    expect(await Location().wasLaunchedByLocationEvent(), isFalse);
    expect(
      await Location().setSignificantChangeMonitoring(enable: false),
      isFalse,
    );
  });
}
