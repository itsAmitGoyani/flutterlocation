#import "LocationPlugin.h"

#ifdef COCOAPODS
@import CoreLocation;
#else
#import <CoreLocation/CoreLocation.h>
#endif

@interface LocationPlugin () <FlutterStreamHandler, CLLocationManagerDelegate>
@property(strong, nonatomic) CLLocationManager *clLocationManager;
@property(copy, nonatomic) FlutterResult flutterResult;
@property(assign, nonatomic) BOOL locationWanted;
@property(assign, nonatomic) BOOL permissionWanted;
// Needed to prevent instant firing of the previous known location
@property(assign, nonatomic) int waitNextLocation;

@property(copy, nonatomic) FlutterEventSink flutterEventSink;
@property(assign, nonatomic) BOOL flutterListening;
@property(assign, nonatomic) BOOL hasInit;
@property(assign, nonatomic) BOOL applicationHasLocationBackgroundMode;

// AutoLNK fork: the relaunch after a termination (see armRelaunchMonitoring).
// The plugin's own channel, for the calls it makes to Dart.
@property(strong, nonatomic) FlutterMethodChannel *channel;
// iOS launched or woke this process for a location event.
@property(assign, nonatomic) BOOL launchedForLocation;
// The relaunch monitors were armed when this process launched.
@property(assign, nonatomic) BOOL armedAtLaunch;
// Armed at launch while the grant read "not determined": the authorization
// callback arms them once it reads Always.
@property(assign, nonatomic) BOOL armOnAlways;
// The three relaunch monitors are on; mirrors the persisted flag.
@property(assign, nonatomic) BOOL relaunchArmed;
// The region around the phone whose exit relaunches the app.
@property(strong, nonatomic) CLCircularRegion *leash;
// Ordinary updates the plugin started by itself at a relaunch, before Dart listens.
@property(assign, nonatomic) BOOL keepAliveUpdates;
@property(strong, nonatomic) NSTimer *dartWatchdog;
// When the leash last moved: a delivered fix moves it at most every
// kLeashRecenterMinIntervalSeconds, since a drive delivers one each second.
@property(assign, nonatomic) NSTimeInterval lastLeashMoveAt;
// A location event came before the first unlock after a reboot: the
// keep-alive starts once the phone is unlocked.
@property(assign, nonatomic) BOOL keepAliveOnUnlock;
// NSUserDefaults could not be written (before the first unlock); written
// when protected data becomes available.
@property(assign, nonatomic) BOOL defaultsDirty;
// This process launched before the first unlock, when NSUserDefaults read
// as empty: the unlock reads the armed flag again.
@property(assign, nonatomic) BOOL launchedLocked;
// A one-shot getLocation runs at the best accuracy; the profile's accuracy
// comes back with its fix.
@property(assign, nonatomic) BOOL oneShotAccuracyRaised;
@property(assign, nonatomic) CLLocationAccuracy savedAccuracy;
@property(assign, nonatomic) NSUInteger oneShotGeneration;
@end

static NSString *const kRelaunchArmedKey = @"lyokone_location_relaunch_armed";
static NSString *const kLeashLatitudeKey = @"lyokone_location_leash_lat";
static NSString *const kLeashLongitudeKey = @"lyokone_location_leash_lng";
static NSString *const kLeashIdentifier = @"lyokone.location.leash";
// Region monitoring is reliable from about 100 m; the leash moves with the
// phone, so a small radius costs nothing while the app is alive.
static const CLLocationDistance kLeashRadiusMeters = 150.0;
static const CLLocationDistance kLeashRecenterMeters = 75.0;
static const CLLocationAccuracy kLeashMaxAccuracyMeters = 200.0;
static const NSTimeInterval kLeashRecenterMinIntervalSeconds = 30.0;
// How long a relaunched process keeps its own updates before it decides the
// Dart side never came. The Dart launch waits up to 20 s for Remote Config
// on such a launch, and then starts the share.
static const NSTimeInterval kDartWatchdogSeconds = 90.0;
// The longest a one-shot read keeps the best accuracy without a fix.
static const NSTimeInterval kOneShotAccuracySeconds = 30.0;

@implementation LocationPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
  FlutterMethodChannel *channel =
      [FlutterMethodChannel methodChannelWithName:@"lyokone/location"
                                  binaryMessenger:registrar.messenger];
  FlutterEventChannel *stream =
      [FlutterEventChannel eventChannelWithName:@"lyokone/locationstream"
                                binaryMessenger:registrar.messenger];

  LocationPlugin *instance = [[LocationPlugin alloc] init];
  instance.channel = channel;
  [registrar addMethodCallDelegate:instance channel:channel];
  [stream setStreamHandler:instance];
#if TARGET_OS_IOS
  // For the launch options: a relaunch for a location event must restart
  // the monitors and the updates before any Dart code runs.
  [registrar addApplicationDelegate:instance];
  [[NSNotificationCenter defaultCenter]
      addObserver:instance
         selector:@selector(protectedDataDidBecomeAvailable:)
             name:UIApplicationProtectedDataDidBecomeAvailable
           object:nil];
#endif
}

- (instancetype)init {
  self = [super init];

  if (self) {
    self.locationWanted = NO;
    self.permissionWanted = NO;
    self.flutterListening = NO;
    self.waitNextLocation = 2;
    self.hasInit = NO;
  }
  return self;
}

- (void)initLocation {
  if (!(self.hasInit)) {
    self.hasInit = YES;

    NSArray *backgroundModes =
        [NSBundle.mainBundle objectForInfoDictionaryKey:@"UIBackgroundModes"];
    self.applicationHasLocationBackgroundMode =
        [backgroundModes containsObject:@"location"];

    self.clLocationManager = [[CLLocationManager alloc] init];
    self.clLocationManager.delegate = self;
    self.clLocationManager.desiredAccuracy = kCLLocationAccuracyBest;
    self.clLocationManager.pausesLocationUpdatesAutomatically = true;
  }
}

- (void)handleMethodCall:(FlutterMethodCall *)call
                  result:(FlutterResult)result {
  [self initLocation];
  if ([call.method isEqualToString:@"changeSettings"]) {
#ifdef DEBUG
    NSLog(@"[Location] changeSettings(%@)", call.arguments);
#endif
    if ([CLLocationManager locationServicesEnabled]) {
      // AutoLNK: Dart sends the accuracy as the LocationAccuracy index, a
      // number. Upstream looked it up in a dictionary keyed by strings, so the
      // lookup always failed, and every profile ran at accuracy 0, the best
      // one: GPS at full power, a still phone included. The same failed
      // lookup turned auto-pause off whatever Dart asked for.
      CLLocationAccuracy accuracy =
          [self accuracyForIndex:call.arguments[@"accuracy"]];
      if (self.oneShotAccuracyRaised) {
        // A one-shot read runs at the best accuracy; this one comes back
        // with its fix.
        self.savedAccuracy = accuracy;
      } else {
        self.clLocationManager.desiredAccuracy = accuracy;
      }
      id filter = call.arguments[@"distanceFilter"];
      double distanceFilter =
          [filter isKindOfClass:[NSNumber class]] ? [filter doubleValue] : 0;
      if (distanceFilter == 0) {
        distanceFilter = kCLDistanceFilterNone;
      }
      self.clLocationManager.distanceFilter = distanceFilter;
      id pauses = call.arguments[@"pausesLocationUpdatesAutomatically"];
      self.clLocationManager.pausesLocationUpdatesAutomatically =
          [pauses isKindOfClass:[NSNumber class]] ? [pauses boolValue] : NO;
      result(@1);
    } else {
      // Never leave the Dart call without an answer.
      result(@0);
    }
  } else if ([call.method isEqualToString:@"isBackgroundModeEnabled"] ||
             [call.method isEqualToString:@"backgroundModeState"]) {
    // AutoLNK: one answer. Upstream answered twice on iOS 9 and later, and
    // not at all without the location background mode.
    if (self.applicationHasLocationBackgroundMode) {
      result(self.clLocationManager.allowsBackgroundLocationUpdates ? @1 : @0);
    } else {
      result(@0);
    }
  } else if ([call.method isEqualToString:@"enableBackgroundMode"]) {
    BOOL enable = [call.arguments[@"enable"] boolValue];
    if (self.applicationHasLocationBackgroundMode) {
      if (@available(iOS 9.0, *)) {
        self.clLocationManager.allowsBackgroundLocationUpdates = enable;
      }
      if (@available(iOS 11.0, *)) {
        // AutoLNK: never raise the background location indicator (the blue
        // status bar pill, the arrow inside the Dynamic Island). iOS reads
        // this flag only for an app with Always authorization; a While Using
        // app gets the indicator from the system whatever the flag says.
        self.clLocationManager.showsBackgroundLocationIndicator = NO;
      }
      result(enable ? @1 : @0);
    } else {
      result(@0);
    }
  } else if ([call.method isEqualToString:@"getLocation"]) {
    if (![CLLocationManager locationServicesEnabled]) {
      result([FlutterError
          errorWithCode:@"SERVICE_STATUS_DISABLED"
                message:@"Failed to get location. Location services disabled"
                details:nil]);
      return;
    }
    if ([CLLocationManager authorizationStatus] ==
        kCLAuthorizationStatusDenied) {
      // Location services are requested but user has denied
      NSString *message =
          @"The user explicitly denied the use of location services for this "
           "app or location services are currently disabled in Settings.";
      result([FlutterError errorWithCode:@"PERMISSION_DENIED"
                                 message:message
                                 details:nil]);
      return;
    }

    self.flutterResult = result;
    self.locationWanted = YES;
    // AutoLNK: a one-shot read asks for a precise fix, whatever accuracy the
    // stream's profile runs at.
    [self raiseAccuracyForOneShot];

    if ([self isPermissionGranted]) {
      [self.clLocationManager startUpdatingLocation];
    } else {
      [self requestPermission];
      if ([self isPermissionGranted]) {
        [self.clLocationManager startUpdatingLocation];
      }
    }
  } else if ([call.method isEqualToString:@"hasPermission"]) {
    if ([self isPermissionGranted]) {
      result([self isHighAccuracyPermitted] ? @1 : @3);
    } else {
      result(@0);
    }
  } else if ([call.method isEqualToString:@"requestPermission"]) {
    if ([self isPermissionGranted]) {
      result([self isHighAccuracyPermitted] ? @1 : @3);
    } else if ([CLLocationManager authorizationStatus] ==
               kCLAuthorizationStatusNotDetermined) {
      self.flutterResult = result;
      self.permissionWanted = YES;
      [self requestPermission];
    } else {
      result(@2);
    }
  } else if ([call.method isEqualToString:@"serviceEnabled"]) {
    if ([CLLocationManager locationServicesEnabled]) {
      result(@1);
    } else {
      result(@0);
    }
  } else if ([call.method isEqualToString:@"requestService"]) {
    if ([CLLocationManager locationServicesEnabled]) {
      result(@1);
    } else {
#if TARGET_OS_OSX
      NSAlert *alert = [[NSAlert alloc] init];
      [alert setMessageText:@"Location is Disabled"];
      [alert setInformativeText:
                 @"To use location, go to your System Preferences > Security & "
                 @"Privacy > Privacy > Location Services."];
      [alert addButtonWithTitle:@"Open"];
      [alert addButtonWithTitle:@"Cancel"];
      [alert beginSheetModalForWindow:NSApplication.sharedApplication.mainWindow
                    completionHandler:^(NSModalResponse returnCode) {
                      if (returnCode == NSAlertFirstButtonReturn) {
                        NSString *urlString =
                            @"x-apple.systempreferences:com.apple.preference."
                            @"security?Privacy_LocationServices";
                        [[NSWorkspace sharedWorkspace]
                            openURL:[NSURL URLWithString:urlString]];
                      }
                    }];
#else
      UIAlertView *alert = [[UIAlertView alloc]
              initWithTitle:@"Location is Disabled"
                    message:@"To use location, go to your Settings App > "
                            @"Privacy > Location Services."
                   delegate:self
          cancelButtonTitle:@"Cancel"
          otherButtonTitles:nil];
      [alert show];
#endif
      result(@0);
    }
  } else if ([call.method isEqualToString:@"setRelaunchMonitoring"]) {
#if TARGET_OS_IOS
    BOOL enable = [call.arguments[@"enable"] boolValue];
    if (!enable) {
      [self disarmRelaunchMonitoring];
      result(@0);
    } else if (![self isAlwaysAuthorized]) {
      // The monitors relaunch nothing without Always; keep none.
      result(@0);
    } else {
      [self armRelaunchMonitoring];
      // The spot where the app goes to sleep, when Dart knows it.
      id lat = call.arguments[@"latitude"];
      id lng = call.arguments[@"longitude"];
      if ([lat isKindOfClass:[NSNumber class]] &&
          [lng isKindOfClass:[NSNumber class]]) {
        [self setLeashAt:CLLocationCoordinate2DMake([lat doubleValue],
                                                    [lng doubleValue])];
      }
      // 2: armed, but region monitoring needs precise location, so the
      // leash cannot fire; significant change and visits still run.
      result([self isHighAccuracyPermitted] ? @1 : @2);
    }
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"listenForWakes"] ||
             [call.method isEqualToString:@"stopListeningForWakes"] ||
             [call.method isEqualToString:@"requestWake"] ||
             [call.method isEqualToString:@"finishHeadlessRun"]) {
    // Android only: iOS relaunches the whole app and streams from Dart.
    result(@0);
  } else if ([call.method isEqualToString:@"getCurrentFix"]) {
    // Android only: the wakes that need it run on Android.
    result(nil);
  } else if ([call.method isEqualToString:@"wasLaunchedByLocationEvent"]) {
#if TARGET_OS_IOS
    result(self.launchedForLocation || [self isBackgroundLaunchWhileArmed]
               ? @1
               : @0);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"isProtectedDataAvailable"]) {
#if TARGET_OS_IOS
    result(UIApplication.sharedApplication.isProtectedDataAvailable ? @1 : @0);
#else
    result(@1);
#endif
  } else if ([call.method isEqualToString:@"isBackgroundPermissionGranted"]) {
#if TARGET_OS_IOS
    result([self isAlwaysAuthorized] ? @1 : @0);
#else
    result([self isPermissionGranted] ? @1 : @0);
#endif
  } else if ([call.method isEqualToString:@"registerHeadlessCallback"]) {
    // Android only: iOS relaunches the whole app for a location event.
    result(@0);
  } else {
    result(FlutterMethodNotImplemented);
  }
}

// The LocationAccuracy index Dart sends, as a Core Location accuracy:
// powerSave, low, balanced, high, navigation, reduced.
- (CLLocationAccuracy)accuracyForIndex:(id)index {
  NSInteger value =
      [index isKindOfClass:[NSNumber class]] ? [index integerValue] : 3;
  switch (value) {
    case 0:
      return kCLLocationAccuracyKilometer;
    case 1:
      return kCLLocationAccuracyHundredMeters;
    case 2:
      return kCLLocationAccuracyNearestTenMeters;
    case 4:
      return kCLLocationAccuracyBestForNavigation;
    case 5:
      if (@available(iOS 14, *)) {
        return kCLLocationAccuracyReduced;
      }
      return kCLLocationAccuracyHundredMeters;
    default:
      return kCLLocationAccuracyBest;
  }
}

// A one-shot read raises the accuracy for its own fix only; a read that
// gets no fix gives it back after kOneShotAccuracySeconds.
- (void)raiseAccuracyForOneShot {
  self.oneShotGeneration += 1;
  NSUInteger generation = self.oneShotGeneration;
  if (!self.oneShotAccuracyRaised) {
    self.savedAccuracy = self.clLocationManager.desiredAccuracy;
    self.oneShotAccuracyRaised = YES;
    self.clLocationManager.desiredAccuracy = kCLLocationAccuracyBest;
  }
  __weak LocationPlugin *weakSelf = self;
  dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW,
                    (int64_t)(kOneShotAccuracySeconds * NSEC_PER_SEC)),
      dispatch_get_main_queue(), ^{
        LocationPlugin *strongSelf = weakSelf;
        if (strongSelf != nil && strongSelf.oneShotGeneration == generation) {
          [strongSelf restoreAccuracyAfterOneShot];
        }
      });
}

- (void)restoreAccuracyAfterOneShot {
  if (!self.oneShotAccuracyRaised) {
    return;
  }
  self.oneShotAccuracyRaised = NO;
  self.clLocationManager.desiredAccuracy = self.savedAccuracy;
}

- (void)requestPermission {
#if TARGET_OS_OSX
  if ([[NSBundle mainBundle]
          objectForInfoDictionaryKey:@"NSLocationWhenInUseUsageDescription"] !=
      nil) {
    if (@available(macOS 10.15, *)) {
      [self.clLocationManager requestAlwaysAuthorization];
    }
  }
#else
  if ([[NSBundle mainBundle]
          objectForInfoDictionaryKey:@"NSLocationWhenInUseUsageDescription"] !=
      nil) {
    [self.clLocationManager requestWhenInUseAuthorization];
  } else if ([[NSBundle mainBundle] objectForInfoDictionaryKey:
                                        @"NSLocationAlwaysUsageDescription"] !=
             nil) {
    [self.clLocationManager requestAlwaysAuthorization];
  }
#endif
  else {
    [NSException
         raise:NSInternalInconsistencyException
        format:@"To use location in iOS8 and above you need to define either "
                "NSLocationWhenInUseUsageDescription or "
                "NSLocationAlwaysUsageDescription in the app "
                "bundle's Info.plist file"];
  }
}

- (BOOL)isHighAccuracyPermitted {
#if __IPHONE_14_0
  if (@available(iOS 14.0, *)) {
    CLAccuracyAuthorization accuracy =
        [self.clLocationManager accuracyAuthorization];
    if (accuracy == CLAccuracyAuthorizationReducedAccuracy) {
      return NO;
    }
  }
#endif
  return YES;
}

- (BOOL)isPermissionGranted {
  BOOL isPermissionGranted = NO;
  CLAuthorizationStatus status = [CLLocationManager authorizationStatus];

#if TARGET_OS_OSX
  if (status == kCLAuthorizationStatusAuthorized) {
    // Location services are available
    isPermissionGranted = YES;
  } else if (@available(macOS 10.12, *)) {
    if (status == kCLAuthorizationStatusAuthorizedAlways) {
      // Location services are available
      isPermissionGranted = YES;
    }
  }
#else // if TARGET_OS_IOS
  if (status == kCLAuthorizationStatusAuthorizedWhenInUse ||
      status == kCLAuthorizationStatusAuthorizedAlways) {
    // Location services are available
    isPermissionGranted = YES;
  }
#endif
  else if (status == kCLAuthorizationStatusDenied ||
           status == kCLAuthorizationStatusRestricted) {
    // Location services are requested but user has denied / the app is
    // restricted from getting location
    isPermissionGranted = NO;
  } else if (status == kCLAuthorizationStatusNotDetermined) {
    // Location services never requested / the user still haven't decide
    isPermissionGranted = NO;
  } else {
    isPermissionGranted = NO;
  }

  return isPermissionGranted;
}

- (FlutterError *)onListenWithArguments:(id)arguments
                              eventSink:(FlutterEventSink)events {
  self.flutterEventSink = events;
  self.flutterListening = YES;
#if TARGET_OS_IOS
  // Dart owns the updates from here: a relaunch's keep-alive is handed over.
  [self cancelDartWatchdog];
  self.keepAliveUpdates = NO;
#endif

  if ([self isPermissionGranted]) {
    [self.clLocationManager startUpdatingLocation];
  } else {
    [self requestPermission];
  }

  return nil;
}

- (FlutterError *)onCancelWithArguments:(id)arguments {
  self.flutterListening = NO;
  [self.clLocationManager stopUpdatingLocation];
  return nil;
}

#pragma mark - CLLocationManagerDelegate Methods

- (void)locationManager:(CLLocationManager *)manager
     didUpdateLocations:(NSArray<CLLocation *> *)locations {
#if TARGET_OS_IOS
  if (!self.flutterListening && !self.keepAliveUpdates &&
      !self.locationWanted) {
    // No updates of ours run, so this is a significant-change delivery: a
    // relaunch or a wake for it.
    [self noteMonitorEvent];
  }
#endif
  if (self.waitNextLocation > 0) {
    self.waitNextLocation -= 1;
    return;
  }
  CLLocation *location = locations.lastObject;
#if TARGET_OS_IOS
  [self recenterLeashIfNeeded:location];
#endif

  NSTimeInterval timeInSeconds = [location.timestamp timeIntervalSince1970];
  BOOL superiorToIos10 =
      [UIDevice currentDevice].systemVersion.floatValue >= 10;
  NSDictionary<NSString *, NSNumber *> *coordinatesDict = @{
    @"latitude" : @(location.coordinate.latitude),
    @"longitude" : @(location.coordinate.longitude),
    @"accuracy" : @(location.horizontalAccuracy),
    @"verticalAccuracy" : @(location.verticalAccuracy),
    @"altitude" : @(location.altitude),
    @"speed" : @(location.speed),
    @"speed_accuracy" : superiorToIos10 ? @(location.speedAccuracy) : @0.0,
    @"heading" : @(location.course),
    @"time" :
        @(((double)timeInSeconds) * 1000.0) // in milliseconds since the epoch
  };

  if (self.locationWanted) {
    self.locationWanted = NO;
    self.flutterResult(coordinatesDict);
    [self restoreAccuracyAfterOneShot];
  }
  if (self.flutterListening) {
    self.flutterEventSink(coordinatesDict);
  } else if (!self.keepAliveUpdates) {
    // A relaunch's own updates stay on until Dart listens or the watchdog
    // stops them; any other unlistened fix ends the request as before.
    [self.clLocationManager stopUpdatingLocation];
    self.waitNextLocation = 2;
  }
}

- (void)locationManager:(CLLocationManager *)manager
    didChangeAuthorizationStatus:(CLAuthorizationStatus)status {
#if TARGET_OS_IOS
  if (status == kCLAuthorizationStatusAuthorizedAlways) {
    // An armed launch that read "not determined" arms once Always is known.
    if (self.armOnAlways) {
      [self armRelaunchMonitoring];
    }
  } else if (status != kCLAuthorizationStatusNotDetermined &&
             (self.relaunchArmed || self.armOnAlways) &&
             [CLLocationManager locationServicesEnabled]) {
    // The grant no longer allows a background relaunch: keep no monitor.
    // With Location Services off every app reads Denied, and the monitors
    // stay for when they come back.
    [self disarmRelaunchMonitoring];
  }
#endif
  if (status == kCLAuthorizationStatusDenied) {
    if (self.permissionWanted) {
      self.permissionWanted = NO;
      self.flutterResult(@0);
    }
  }
#if TARGET_OS_OSX
  else if (status == kCLAuthorizationStatusAuthorized) {
    if (self.permissionWanted) {
      self.permissionWanted = NO;
      self.flutterResult(@1);
    }

    if (self.locationWanted || self.flutterListening) {
      [self.clLocationManager startUpdatingLocation];
    }
  } else if (@available(macOS 10.12, *)) {
    if (status == kCLAuthorizationStatusAuthorizedAlways) {
      if (self.permissionWanted) {
        self.permissionWanted = NO;
        self.flutterResult(@1);
      }

      if (self.locationWanted || self.flutterListening) {
        [self.clLocationManager startUpdatingLocation];
      }
    }
  }
#else // if TARGET_OS_IOS
  else if (status == kCLAuthorizationStatusAuthorizedWhenInUse ||
           status == kCLAuthorizationStatusAuthorizedAlways) {
    if (self.permissionWanted) {
      self.permissionWanted = NO;
      self.flutterResult([self isHighAccuracyPermitted] ? @1 : @3);
    }

    if (self.locationWanted || self.flutterListening) {
      [self.clLocationManager startUpdatingLocation];
    }
  }
#endif
}

#pragma mark - AutoLNK fork: the relaunch after a termination

#if TARGET_OS_IOS
- (CLAuthorizationStatus)authorizationStatus {
  // The class method reads "not determined" until the process has a
  // manager; the instance property (iOS 14) comes from that manager.
  if (@available(iOS 14.0, *)) {
    return self.clLocationManager.authorizationStatus;
  }
  return [CLLocationManager authorizationStatus];
}

- (BOOL)isAlwaysAuthorized {
  return [self authorizationStatus] == kCLAuthorizationStatusAuthorizedAlways;
}

// A launch into the background with the monitors armed and no scene: a
// UIScene app gets no launch options, and the location event may reach the
// delegate only after Dart asks. A user launch has a scene by then.
- (BOOL)isBackgroundLaunchWhileArmed {
  if (!self.armedAtLaunch) {
    return NO;
  }
  UIApplication *application = UIApplication.sharedApplication;
  if (application.applicationState != UIApplicationStateBackground) {
    return NO;
  }
  if (@available(iOS 13.0, *)) {
    return application.connectedScenes.count == 0;
  }
  return YES;
}

// The leash the system still monitors for this app, or nil. The system
// keeps regions across relaunches and reboots.
- (CLCircularRegion *)monitoredLeash {
  for (CLRegion *region in self.clLocationManager.monitoredRegions) {
    if ([region.identifier isEqualToString:kLeashIdentifier] &&
        [region isKindOfClass:[CLCircularRegion class]]) {
      return (CLCircularRegion *)region;
    }
  }
  return nil;
}

// iOS relaunches a terminated app, under Always, for three services only:
// significant change, visits and region monitoring (Apple's authorization
// table). After a user swipe only region monitoring is documented as
// reliable, so a circular "leash" around the phone runs beside the other
// two: leaving it relaunches the app, which then starts the ordinary
// updates itself (see noteMonitorEvent).
- (void)armRelaunchMonitoring {
  [self initLocation];
  self.armOnAlways = NO;
  CLLocationManager *manager = self.clLocationManager;
  if ([CLLocationManager significantLocationChangeMonitoringAvailable]) {
    [manager startMonitoringSignificantLocationChanges];
  }
  [manager startMonitoringVisits];
  self.relaunchArmed = YES;
  CLCircularRegion *monitored = [self monitoredLeash];
  if (monitored != nil) {
    // The system kept the leash: keep it, with its exit state. Registering
    // it again at the same centre could drop an exit that is on its way.
    self.leash = monitored;
  } else {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    if (UIApplication.sharedApplication.isProtectedDataAvailable &&
        [defaults objectForKey:kLeashLatitudeKey] != nil &&
        [defaults objectForKey:kLeashLongitudeKey] != nil) {
      [self setLeashAt:CLLocationCoordinate2DMake(
                           [defaults doubleForKey:kLeashLatitudeKey],
                           [defaults doubleForKey:kLeashLongitudeKey])];
    } else if (manager.location != nil) {
      [self setLeashAt:manager.location.coordinate];
    }
  }
  [self persistRelaunchState];
  if (self.leash != nil) {
    // A phone already outside the kept leash gets no exit; the state says.
    [manager requestStateForRegion:self.leash];
  }
}

- (void)disarmRelaunchMonitoring {
  CLLocationManager *manager = self.clLocationManager;
  [manager stopMonitoringSignificantLocationChanges];
  [manager stopMonitoringVisits];
  for (CLRegion *region in manager.monitoredRegions) {
    if ([region.identifier isEqualToString:kLeashIdentifier]) {
      [manager stopMonitoringForRegion:region];
    }
  }
  self.leash = nil;
  self.relaunchArmed = NO;
  self.armOnAlways = NO;
  self.keepAliveOnUnlock = NO;
  // A relaunch's own updates go with the monitors.
  [self cancelDartWatchdog];
  if (self.keepAliveUpdates) {
    self.keepAliveUpdates = NO;
    if (!self.flutterListening) {
      [manager stopUpdatingLocation];
      self.waitNextLocation = 2;
    }
  }
  [self persistRelaunchState];
}

// The armed flag and the leash centre in NSUserDefaults, for the next
// launch. Before the first unlock after a reboot the store cannot be
// written; the write waits for protected data.
- (void)persistRelaunchState {
  if (!UIApplication.sharedApplication.isProtectedDataAvailable) {
    self.defaultsDirty = YES;
    return;
  }
  self.defaultsDirty = NO;
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  if (self.relaunchArmed) {
    [defaults setBool:YES forKey:kRelaunchArmedKey];
    if (self.leash != nil) {
      [defaults setDouble:self.leash.center.latitude forKey:kLeashLatitudeKey];
      [defaults setDouble:self.leash.center.longitude
                   forKey:kLeashLongitudeKey];
    }
  } else {
    [defaults removeObjectForKey:kRelaunchArmedKey];
    [defaults removeObjectForKey:kLeashLatitudeKey];
    [defaults removeObjectForKey:kLeashLongitudeKey];
  }
}

// One region under one identifier: a new centre replaces the old region.
- (void)setLeashAt:(CLLocationCoordinate2D)centre {
  if (!CLLocationCoordinate2DIsValid(centre)) {
    return;
  }
  if (![CLLocationManager
          isMonitoringAvailableForClass:[CLCircularRegion class]]) {
    return;
  }
  CLLocationManager *manager = self.clLocationManager;
  CLLocationDistance radius =
      MIN(kLeashRadiusMeters, manager.maximumRegionMonitoringDistance);
  CLCircularRegion *region =
      [[CLCircularRegion alloc] initWithCenter:centre
                                        radius:radius
                                    identifier:kLeashIdentifier];
  region.notifyOnEntry = NO;
  region.notifyOnExit = YES;
  [manager startMonitoringForRegion:region];
  self.leash = region;
  self.lastLeashMoveAt = [NSProcessInfo processInfo].systemUptime;
  [self persistRelaunchState];
}

// Per delivered fix: one distance, and a new region only past the
// re-centre distance and at most every kLeashRecenterMinIntervalSeconds, so
// a drive does not replace the region and write its centre every second.
- (void)recenterLeashIfNeeded:(CLLocation *)location {
  if (!self.relaunchArmed || location == nil) {
    return;
  }
  if ([NSProcessInfo processInfo].systemUptime - self.lastLeashMoveAt <
      kLeashRecenterMinIntervalSeconds) {
    return;
  }
  [self moveLeashTo:location];
}

// Moves the leash to a usable fix beyond the re-centre distance, with no
// time limit: an exit must move it at once.
- (void)moveLeashTo:(CLLocation *)location {
  if (!self.relaunchArmed || location == nil) {
    return;
  }
  if (location.horizontalAccuracy < 0 ||
      location.horizontalAccuracy > kLeashMaxAccuracyMeters) {
    return;
  }
  CLCircularRegion *leash = self.leash;
  if (leash != nil) {
    CLLocation *centre =
        [[CLLocation alloc] initWithLatitude:leash.center.latitude
                                   longitude:leash.center.longitude];
    if ([location distanceFromLocation:centre] < kLeashRecenterMeters) {
      return;
    }
  }
  [self setLeashAt:location.coordinate];
}

// A location event (a leash exit, a visit, a significant change) while the
// app runs in the background with nothing of Dart listening: iOS launched
// or woke the app for it. Apple's advice for a UIScene app, whose launch
// options are nil: the delegate callback is the signal. The ordinary
// updates start NOW, so iOS keeps the app alive as a location app while
// the Dart side boots; Dart takes them over when it listens, and the
// watchdog stops them if it never comes.
- (void)noteMonitorEvent {
  if (!self.relaunchArmed || self.flutterListening || self.keepAliveUpdates) {
    return;
  }
  if (![self isAlwaysAuthorized]) {
    return;
  }
  UIApplication *application = UIApplication.sharedApplication;
  if (application.applicationState != UIApplicationStateBackground) {
    return;
  }
  self.launchedForLocation = YES;
  if (!application.isProtectedDataAvailable) {
    // Before the first unlock the app cannot read its session: start once
    // the phone is unlocked.
    self.keepAliveOnUnlock = YES;
    return;
  }
  [self startKeepAliveUpdates];
  [self.channel invokeMethod:@"onLocationLaunch" arguments:nil];
}

- (void)protectedDataDidBecomeAvailable:(NSNotification *)notification {
  if (self.launchedLocked) {
    // The launch could not read the armed flag; it can now.
    self.launchedLocked = NO;
    if (!self.relaunchArmed && [[NSUserDefaults standardUserDefaults]
                                   boolForKey:kRelaunchArmedKey]) {
      self.armedAtLaunch = YES;
      [self initLocation];
      if ([self isAlwaysAuthorized]) {
        [self armRelaunchMonitoring];
      }
    }
  }
  if (self.defaultsDirty) {
    [self persistRelaunchState];
  }
  if (!self.keepAliveOnUnlock) {
    return;
  }
  self.keepAliveOnUnlock = NO;
  [self noteMonitorEvent];
}

- (void)startKeepAliveUpdates {
  if (!self.applicationHasLocationBackgroundMode) {
    return;
  }
  CLLocationManager *manager = self.clLocationManager;
  manager.allowsBackgroundLocationUpdates = YES;
  if (@available(iOS 11.0, *)) {
    manager.showsBackgroundLocationIndicator = NO;
  }
  manager.pausesLocationUpdatesAutomatically = NO;
  self.keepAliveUpdates = YES;
  [manager startUpdatingLocation];
  [self cancelDartWatchdog];
  __weak LocationPlugin *weakSelf = self;
  self.dartWatchdog =
      [NSTimer scheduledTimerWithTimeInterval:kDartWatchdogSeconds
                                      repeats:NO
                                        block:^(NSTimer *timer) {
                                          [weakSelf dartWatchdogFired];
                                        }];
}

- (void)dartWatchdogFired {
  self.dartWatchdog = nil;
  if (!self.keepAliveUpdates || self.flutterListening) {
    return;
  }
  self.keepAliveUpdates = NO;
  [self.clLocationManager stopUpdatingLocation];
  self.waitNextLocation = 2;
}

- (void)cancelDartWatchdog {
  [self.dartWatchdog invalidate];
  self.dartWatchdog = nil;
}

#pragma mark FlutterApplicationLifeCycleDelegate

// The pending event reaches only a manager that exists after the relaunch,
// so the manager and the monitors come back here, at once, on every launch
// that had them armed. A UIScene app gets nil launch options, so the launch
// key serves only other hosts; noteMonitorEvent starts the keep-alive when
// the event itself arrives.
- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
  BOOL byLocation =
      launchOptions[UIApplicationLaunchOptionsLocationKey] != nil;
  BOOL protectedData = application.isProtectedDataAvailable;
  // Before the first unlock NSUserDefaults reads as empty; the leash the
  // system kept tells whether the monitors were armed.
  BOOL armed = protectedData && [[NSUserDefaults standardUserDefaults]
                                    boolForKey:kRelaunchArmedKey];
  if (!armed && !byLocation && protectedData) {
    return YES;
  }
  [self initLocation];
  if (!protectedData) {
    self.launchedLocked = YES;
    armed = [self monitoredLeash] != nil;
  }
  self.armedAtLaunch = armed;
  if (!armed) {
    return YES;
  }
  CLAuthorizationStatus status = [self authorizationStatus];
  if (status == kCLAuthorizationStatusAuthorizedAlways) {
    [self armRelaunchMonitoring];
    if (byLocation) {
      [self noteMonitorEvent];
    }
  } else if (status == kCLAuthorizationStatusNotDetermined) {
    // An early read can say this; the authorization callback decides.
    self.armOnAlways = YES;
  } else if ([CLLocationManager locationServicesEnabled]) {
    [self disarmRelaunchMonitoring];
  }
  return YES;
}

#pragma mark CLLocationManagerDelegate (the relaunch monitors)

- (void)locationManager:(CLLocationManager *)manager
          didExitRegion:(CLRegion *)region {
  if (![region.identifier isEqualToString:kLeashIdentifier]) {
    return;
  }
  // The wake itself is the value. Re-centre where the system says the
  // phone is now, so the next exit is a real move.
  [self noteMonitorEvent];
  [self moveLeashTo:manager.location];
}

- (void)locationManager:(CLLocationManager *)manager
      didDetermineState:(CLRegionState)state
              forRegion:(CLRegion *)region {
  if (![region.identifier isEqualToString:kLeashIdentifier]) {
    return;
  }
  // A kept leash the phone already left fires no exit: move it.
  if (state == CLRegionStateOutside) {
    [self moveLeashTo:manager.location];
  }
}

- (void)locationManager:(CLLocationManager *)manager
               didVisit:(CLVisit *)visit {
  // An arrival or a departure: the relaunch is the value.
  [self noteMonitorEvent];
}

- (void)locationManager:(CLLocationManager *)manager
    monitoringDidFailForRegion:(CLRegion *)region
                     withError:(NSError *)error {
  NSLog(@"[Location] region monitoring failed for %@: %@", region.identifier,
        error);
}
#endif

@end
