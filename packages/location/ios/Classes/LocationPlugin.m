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
// iOS launched this process for a location event (UIApplicationLaunchOptionsLocationKey).
@property(assign, nonatomic) BOOL launchedForLocation;
// The three relaunch monitors are on; mirrors the persisted flag.
@property(assign, nonatomic) BOOL relaunchArmed;
// The region around the phone whose exit relaunches the app.
@property(strong, nonatomic) CLCircularRegion *leash;
// Ordinary updates the plugin started by itself at a relaunch, before Dart listens.
@property(assign, nonatomic) BOOL keepAliveUpdates;
@property(strong, nonatomic) NSTimer *dartWatchdog;
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
// How long a relaunched process keeps its own updates before it decides the
// Dart side never came.
static const NSTimeInterval kDartWatchdogSeconds = 60.0;

@implementation LocationPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
  FlutterMethodChannel *channel =
      [FlutterMethodChannel methodChannelWithName:@"lyokone/location"
                                  binaryMessenger:registrar.messenger];
  FlutterEventChannel *stream =
      [FlutterEventChannel eventChannelWithName:@"lyokone/locationstream"
                                binaryMessenger:registrar.messenger];

  LocationPlugin *instance = [[LocationPlugin alloc] init];
  [registrar addMethodCallDelegate:instance channel:channel];
  [stream setStreamHandler:instance];
#if TARGET_OS_IOS
  // For the launch options: a relaunch for a location event must restart
  // the monitors and the updates before any Dart code runs.
  [registrar addApplicationDelegate:instance];
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
      CLLocationAccuracy reducedAccuracy = kCLLocationAccuracyHundredMeters;
      if (@available(iOS 14, *)) {
        reducedAccuracy = kCLLocationAccuracyReduced;
      }
      NSDictionary *dictionary = @{
        @"0" : @(kCLLocationAccuracyKilometer),
        @"1" : @(kCLLocationAccuracyHundredMeters),
        @"2" : @(kCLLocationAccuracyNearestTenMeters),
        @"3" : @(kCLLocationAccuracyBest),
        @"4" : @(kCLLocationAccuracyBestForNavigation),
        @"5" : @(reducedAccuracy)
      };

      self.clLocationManager.desiredAccuracy =
          [dictionary[call.arguments[@"accuracy"]] doubleValue];
      double distanceFilter = [call.arguments[@"distanceFilter"] doubleValue];
      if (distanceFilter == 0) {
        distanceFilter = kCLDistanceFilterNone;
      }
      self.clLocationManager.distanceFilter = distanceFilter;
      self.clLocationManager.pausesLocationUpdatesAutomatically =
          [dictionary[call.arguments[@"pausesLocationUpdatesAutomatically"]]
              boolValue];
      result(@1);
    }
  } else if ([call.method isEqualToString:@"isBackgroundModeEnabled"]) {
    if (self.applicationHasLocationBackgroundMode) {
      if (@available(iOS 9.0, *)) {
        result(self.clLocationManager.allowsBackgroundLocationUpdates ? @1
                                                                      : @0);
      }
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
      result(@1);
    }
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"wasLaunchedByLocationEvent"]) {
#if TARGET_OS_IOS
    result(self.launchedForLocation ? @1 : @0);
#else
    result(@0);
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
  if (self.relaunchArmed && status != kCLAuthorizationStatusAuthorizedAlways) {
    // The grant no longer allows a background relaunch: keep no monitor.
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
- (BOOL)isAlwaysAuthorized {
  return [CLLocationManager authorizationStatus] ==
         kCLAuthorizationStatusAuthorizedAlways;
}

// iOS relaunches a terminated app, under Always, for three services only:
// significant change, visits and region monitoring (Apple's authorization
// table). After a user swipe only region monitoring is documented as
// reliable, so a circular "leash" around the phone runs beside the other
// two: leaving it relaunches the app, which then starts the ordinary
// updates itself (see application:didFinishLaunchingWithOptions:).
- (void)armRelaunchMonitoring {
  [self initLocation];
  CLLocationManager *manager = self.clLocationManager;
  if ([CLLocationManager significantLocationChangeMonitoringAvailable]) {
    [manager startMonitoringSignificantLocationChanges];
  }
  [manager startMonitoringVisits];
  self.relaunchArmed = YES;
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults setBool:YES forKey:kRelaunchArmedKey];
  // The leash comes back where it was; the next fix moves it when needed.
  if ([defaults objectForKey:kLeashLatitudeKey] != nil &&
      [defaults objectForKey:kLeashLongitudeKey] != nil) {
    [self setLeashAt:CLLocationCoordinate2DMake(
                         [defaults doubleForKey:kLeashLatitudeKey],
                         [defaults doubleForKey:kLeashLongitudeKey])];
  } else if (manager.location != nil) {
    [self setLeashAt:manager.location.coordinate];
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
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults removeObjectForKey:kRelaunchArmedKey];
  [defaults removeObjectForKey:kLeashLatitudeKey];
  [defaults removeObjectForKey:kLeashLongitudeKey];
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
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults setDouble:centre.latitude forKey:kLeashLatitudeKey];
  [defaults setDouble:centre.longitude forKey:kLeashLongitudeKey];
}

// Per delivered fix: one distance, and a new region only past the
// re-centre distance (about every 75 m of travel, never while still).
- (void)recenterLeashIfNeeded:(CLLocation *)location {
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

// A launch for a location event under Always: the ordinary updates start
// NOW, before any Dart runs, so iOS keeps the app alive as a location app
// while the Dart side boots. Dart takes them over when it listens, or
// turns everything off; the watchdog stops them if Dart never comes.
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

// The pending event reaches only a manager that monitors again after the
// relaunch, so the monitors restart here, at once, on every launch that
// had them armed. Only a launch FOR a location event also starts the
// keep-alive updates; a user launch leaves the updates to Dart.
- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
  BOOL byLocation =
      launchOptions[UIApplicationLaunchOptionsLocationKey] != nil;
  BOOL armed =
      [[NSUserDefaults standardUserDefaults] boolForKey:kRelaunchArmedKey];
  if (!byLocation && !armed) {
    return YES;
  }
  [self initLocation];
  if (byLocation) {
    self.launchedForLocation = YES;
  }
  if ([self isAlwaysAuthorized]) {
    [self armRelaunchMonitoring];
    if (byLocation) {
      [self startKeepAliveUpdates];
    }
  } else {
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
  // The wake itself is the value: a relaunched app starts updates in
  // didFinishLaunching, a running one already streams. Re-centre where
  // the system says the phone is now, so the next exit is a real move.
  [self recenterLeashIfNeeded:manager.location];
}

- (void)locationManager:(CLLocationManager *)manager
               didVisit:(CLVisit *)visit {
  // An arrival or a departure: the relaunch is the value; nothing to do.
}

- (void)locationManager:(CLLocationManager *)manager
    monitoringDidFailForRegion:(CLRegion *)region
                     withError:(NSError *)error {
  NSLog(@"[Location] region monitoring failed for %@: %@", region.identifier,
        error);
}
#endif

@end
