#import "LocationPlugin.h"

#ifdef COCOAPODS
@import CoreLocation;
#if TARGET_OS_IOS
@import BackgroundTasks;
@import UserNotifications;
#endif
#else
#import <CoreLocation/CoreLocation.h>
#if TARGET_OS_IOS
#import <BackgroundTasks/BackgroundTasks.h>
#import <UserNotifications/UserNotifications.h>
#endif
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

// AutoLNK fork (4.10): the location alert and the background refresh.
// The refresh task iOS handed this process, until it completes (a BGTask;
// typed id, so the iOS 11 target of this pod needs no availability guard).
@property(strong, nonatomic) id refreshTask;
// iOS launched this process for the refresh task: Dart takes the
// background path, as for a location launch.
@property(assign, nonatomic) BOOL launchedForRefresh;
// Background time held after an authorization change, so the status report
// that Dart sends can leave before iOS suspends the app.
@property(assign, nonatomic) UIBackgroundTaskIdentifier reportHold;
// Dart takes refresh runs in this process (setBackgroundRefreshHandler).
@property(assign, nonatomic) BOOL refreshListening;
// A refresh request was wanted when this process launched: a launch into
// the background with no scene may be that run, before iOS hands the task
// over.
@property(assign, nonatomic) BOOL refreshWantedAtLaunch;
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

// AutoLNK fork (4.10), after Life360 on iOS: one notification while the
// location switch is off or the grant is "Never", for a user whom Dart
// marked as wanting the share. Life360 words both cases as "location
// services are off", and so does this alert.
static NSString *const kAlertIdentifier = @"lyokone.location.alert";
static NSString *const kAlertPostedKey = @"lyokone_location_alert_posted";
static NSString *const kAlertShareWantedKey =
    @"lyokone_location_alert_share_wanted";
static NSString *const kAlertCopyKey = @"lyokone_location_alert_copy";
// The background refresh task: `<bundle id>.location-refresh`, which the
// app lists in BGTaskSchedulerPermittedIdentifiers. Life360 asks for its
// own `reportpermissions` task 599 s ahead after each run.
static NSString *const kRefreshSuffix = @".location-refresh";
static NSString *const kRefreshWantedKey = @"lyokone_location_refresh_wanted";
static const NSTimeInterval kRefreshIntervalSeconds = 600.0;
// The time a backgrounded app keeps after an authorization change: iOS
// allows about 30 s of background time.
static const NSTimeInterval kReportHoldSeconds = 25.0;
// iOS lets a process register a task identifier once.
static BOOL refreshRegistered = NO;

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
  // Each return to the app checks the location alert (4.10): a cause that
  // went while the app slept clears the stale posted flag here, and one
  // that holds posts, as Life360 notifies at each app open. Both names,
  // because a UIScene app may skip the application one.
  [[NSNotificationCenter defaultCenter]
      addObserver:instance
         selector:@selector(appBecameActive:)
             name:UIApplicationDidBecomeActiveNotification
           object:nil];
  if (@available(iOS 13.0, *)) {
    [[NSNotificationCenter defaultCenter]
        addObserver:instance
           selector:@selector(appBecameActive:)
               name:UISceneDidActivateNotification
             object:nil];
  }
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
#if TARGET_OS_IOS
    self.reportHold = UIBackgroundTaskInvalid;
#endif
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
  } else if ([call.method isEqualToString:@"setLocationAlertCopy"]) {
#if TARGET_OS_IOS
    [self storeAlertCopy:call.arguments];
    result(@1);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"syncLocationAlerts"]) {
#if TARGET_OS_IOS
    id wanted = call.arguments[@"shareWanted"];
    [[NSUserDefaults standardUserDefaults]
        setBool:[wanted isKindOfClass:[NSNumber class]] && [wanted boolValue]
         forKey:kAlertShareWantedKey];
    [self syncLocationAlert];
    result(@1);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"scheduleBackgroundRefresh"]) {
#if TARGET_OS_IOS
    [self ensureRefreshScheduled:result];
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"cancelBackgroundRefresh"]) {
#if TARGET_OS_IOS
    [self cancelRefresh];
    result(@1);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"completeBackgroundRefresh"]) {
#if TARGET_OS_IOS
    result([self completeRefreshTask:YES] ? @1 : @0);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"listenForBackgroundRefresh"]) {
#if TARGET_OS_IOS
    id listen = call.arguments[@"listen"];
    self.refreshListening =
        [listen isKindOfClass:[NSNumber class]] && [listen boolValue];
    // A task that came before Dart listened runs now.
    if (self.refreshListening && self.refreshTask != nil) {
      [self deliverRefreshToDart];
    }
    result(@1);
#else
    result(@0);
#endif
  } else if ([call.method isEqualToString:@"getCurrentFix"]) {
    // Android only: the wakes that need it run on Android.
    result(nil);
  } else if ([call.method isEqualToString:@"wasLaunchedByLocationEvent"]) {
#if TARGET_OS_IOS
    result(self.launchedForLocation || self.launchedForRefresh ||
                   [self isBackgroundLaunchWhileArmed]
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
  } else if ([call.method isEqualToString:@"startMonitoringLocationPushes"]) {
#if TARGET_OS_IOS
    [self startMonitoringLocationPushes:result];
#else
    result(nil);
#endif
  } else if ([call.method isEqualToString:@"registerHeadlessCallback"]) {
    // Android only: iOS relaunches the whole app for a location event.
    result(@0);
  } else {
    result(FlutterMethodNotImplemented);
  }
}

#if TARGET_OS_IOS
// Apple's location pushes (iOS 15+): the token a server passes to APNs for
// the topic `<bundle id>.location-query`. The app's Location Push Service
// Extension runs for each push, even while the app is terminated. Answers
// the token as hex, nil below iOS 15, or a FlutterError whose code is the
// CLLocationPushServiceError code (1 no extension, 2 no push environment,
// 3 no entitlement, 4 unsupported platform). Answered once, on the main
// thread: the completion may come on any queue.
- (void)startMonitoringLocationPushes:(FlutterResult)result {
  if (@available(iOS 15.0, *)) {
    __block BOOL answered = NO;
    [self.clLocationManager startMonitoringLocationPushesWithCompletion:^(
                                NSData *_Nullable token,
                                NSError *_Nullable error) {
      dispatch_async(dispatch_get_main_queue(), ^{
        if (answered) {
          return;
        }
        answered = YES;
        if (error != nil || token == nil) {
          long code = error != nil ? (long)error.code : -1L;
          result([FlutterError
              errorWithCode:[NSString stringWithFormat:@"%ld", code]
                    message:error != nil ? error.localizedDescription
                                         : @"No location push token."
                    details:nil]);
          return;
        }
        NSMutableString *hex =
            [NSMutableString stringWithCapacity:token.length * 2];
        const unsigned char *bytes = token.bytes;
        for (NSUInteger i = 0; i < token.length; i++) {
          [hex appendFormat:@"%02x", bytes[i]];
        }
        result(hex);
      });
    }];
  } else {
    result(nil);
  }
}
#endif

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
  // AutoLNK (4.10): the alert follows the switch and the grant at once,
  // with no Dart, and a backgrounded app keeps time for Dart's report.
  [self syncLocationAlert];
  [self holdBackgroundTimeForReport];
  // Dart reads the grant again: in the background nothing else tells it
  // (the app's motion stream is closed there), so a grant that came back
  // while the app slept would wait for the next open.
  [self.channel invokeMethod:@"onAuthorizationChanged"
                   arguments:@{@"status" : @(status)}];
  if (status == kCLAuthorizationStatusAuthorizedAlways) {
    // An armed launch that read "not determined" arms once Always is known.
    if (self.armOnAlways) {
      [self armRelaunchMonitoring];
    }
  }
  // A grant below Always keeps the monitors (4.10, after Life360). Only
  // Dart disarms them, when the share is known to be gone (a sign-out, no
  // server). iOS drops an app's significant-change and visit requests when
  // the grant goes; Life360 starts its significant-change monitoring again
  // while it runs under the lower grant, and iOS then delivers a first fix
  // the moment Always comes back, which wakes it. The same restart here.
  if (status != kCLAuthorizationStatusAuthorizedAlways &&
      status != kCLAuthorizationStatusNotDetermined && self.relaunchArmed) {
    [self restartChangeMonitorsForReturn];
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
  // Or with a refresh request pending (4.10): a member whose grant is gone
  // has no monitors armed, and the refresh run must still take the
  // background path.
  if (!self.armedAtLaunch && !self.refreshWantedAtLaunch) {
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

// Significant change and visits again, under a grant that delivers nothing
// now, so iOS holds them for the return of Always (see the authorization
// callback). The leash stays as it is: iOS keeps or drops regions itself.
- (void)restartChangeMonitorsForReturn {
  CLLocationManager *manager = self.clLocationManager;
  if ([CLLocationManager significantLocationChangeMonitoringAvailable]) {
    [manager stopMonitoringSignificantLocationChanges];
    [manager startMonitoringSignificantLocationChanges];
  }
  [manager stopMonitoringVisits];
  [manager startMonitoringVisits];
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
  // iOS accepts a task handler only before the launch finishes.
  [self registerRefreshTask];
  BOOL byLocation =
      launchOptions[UIApplicationLaunchOptionsLocationKey] != nil;
  BOOL protectedData = application.isProtectedDataAvailable;
  // Before the first unlock NSUserDefaults reads as empty; the leash the
  // system kept tells whether the monitors were armed.
  BOOL armed = protectedData && [[NSUserDefaults standardUserDefaults]
                                    boolForKey:kRelaunchArmedKey];
  self.refreshWantedAtLaunch =
      protectedData &&
      [[NSUserDefaults standardUserDefaults] boolForKey:kRefreshWantedKey];
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
  }
  // A grant below Always keeps the monitors armed (4.10): see the
  // authorization callback.
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

#pragma mark - AutoLNK fork: the location alert (4.10)

- (void)appBecameActive:(NSNotification *)notification {
  [self syncLocationAlert];
}

// The app's words, kept for a launch that has no Dart yet. Strings only.
- (void)storeAlertCopy:(id)arguments {
  if (![arguments isKindOfClass:[NSDictionary class]]) {
    return;
  }
  NSMutableDictionary<NSString *, NSString *> *copy =
      [NSMutableDictionary dictionary];
  [(NSDictionary *)arguments
      enumerateKeysAndObjectsUsingBlock:^(id key, id value, BOOL *stop) {
        if ([key isKindOfClass:[NSString class]] &&
            [value isKindOfClass:[NSString class]]) {
          copy[key] = value;
        }
      }];
  [[NSUserDefaults standardUserDefaults] setObject:copy forKey:kAlertCopyKey];
}

- (NSString *)alertText:(NSString *)key fallback:(NSString *)fallback {
  NSDictionary *copy =
      [[NSUserDefaults standardUserDefaults] dictionaryForKey:kAlertCopyKey];
  id value = copy[key];
  return [value isKindOfClass:[NSString class]] && [value length] > 0
             ? value
             : fallback;
}

// The switch is off, or this app's grant is "Never". With the switch off
// iOS reports "denied" for every app, so the switch is read first.
- (BOOL)locationUnusable {
  if (![CLLocationManager locationServicesEnabled]) {
    return YES;
  }
  return [self authorizationStatus] == kCLAuthorizationStatusDenied;
}

// Posts the alert while its cause holds for a user whom Dart marked as one
// who wants the share, and removes it when the cause goes. Posted once per
// cause: the flag stays until the cause goes, so many syncs make one
// notification, and an alert the user dismissed comes back only with a new
// cause. Idempotent: every authorization change and every sync calls it.
- (void)syncLocationAlert {
  [self initLocation];
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  BOOL cause = [defaults boolForKey:kAlertShareWantedKey] &&
               [self locationUnusable];
  BOOL posted = [defaults boolForKey:kAlertPostedKey];
  UNUserNotificationCenter *center =
      [UNUserNotificationCenter currentNotificationCenter];
  if (cause && !posted) {
    [defaults setBool:YES forKey:kAlertPostedKey];
    UNMutableNotificationContent *content =
        [[UNMutableNotificationContent alloc] init];
    content.title = [self alertText:@"servicesOffTitle"
                           fallback:@"Location services are off"];
    content.body =
        [self alertText:@"servicesOffBody"
               fallback:@"You turned off location services, which stopped your "
                        @"location sharing. Turn location services on to "
                        @"reconnect."];
    content.sound = [UNNotificationSound defaultSound];
    if (@available(iOS 15.0, *)) {
      // Life360's alert is time sensitive. Without the entitlement iOS
      // delivers it as an ordinary alert.
      content.interruptionLevel = UNNotificationInterruptionLevelTimeSensitive;
    }
    UNNotificationRequest *request =
        [UNNotificationRequest requestWithIdentifier:kAlertIdentifier
                                             content:content
                                             trigger:nil];
    [center addNotificationRequest:request
             withCompletionHandler:^(NSError *_Nullable error) {
               if (error == nil) {
                 return;
               }
               // Notifications are off, for example: the next sync tries
               // again.
               NSLog(@"[Location] location alert not posted: %@", error);
               dispatch_async(dispatch_get_main_queue(), ^{
                 [[NSUserDefaults standardUserDefaults]
                     removeObjectForKey:kAlertPostedKey];
               });
             }];
  } else if (!cause && posted) {
    [defaults removeObjectForKey:kAlertPostedKey];
    [center removeDeliveredNotificationsWithIdentifiers:@[ kAlertIdentifier ]];
    [center
        removePendingNotificationRequestsWithIdentifiers:@[ kAlertIdentifier ]];
  }
}

#pragma mark - AutoLNK fork: background time for the report (4.10)

// A backgrounded app that hears of a lost switch or grant loses its
// location updates too, and iOS would suspend it before Dart's status
// report leaves. Each change holds kReportHoldSeconds afresh: the new hold
// starts before the old one ends, so the time never drops to zero between.
- (void)holdBackgroundTimeForReport {
  UIApplication *application = UIApplication.sharedApplication;
  if (application.applicationState != UIApplicationStateBackground) {
    return;
  }
  __weak LocationPlugin *weakSelf = self;
  __block UIBackgroundTaskIdentifier hold = UIBackgroundTaskInvalid;
  hold = [application
      beginBackgroundTaskWithName:@"lyokone.location.report"
                expirationHandler:^{
                  [weakSelf endReportHold:hold];
                }];
  if (hold == UIBackgroundTaskInvalid) {
    return;
  }
  UIBackgroundTaskIdentifier previous = self.reportHold;
  self.reportHold = hold;
  if (previous != UIBackgroundTaskInvalid) {
    [application endBackgroundTask:previous];
  }
  dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW,
                    (int64_t)(kReportHoldSeconds * NSEC_PER_SEC)),
      dispatch_get_main_queue(), ^{
        [weakSelf endReportHold:hold];
      });
}

// Ends [hold] when it is still the current one; a replaced hold was ended
// when its successor began.
- (void)endReportHold:(UIBackgroundTaskIdentifier)hold {
  if (hold == UIBackgroundTaskInvalid || self.reportHold != hold) {
    return;
  }
  self.reportHold = UIBackgroundTaskInvalid;
  [UIApplication.sharedApplication endBackgroundTask:hold];
}

#pragma mark - AutoLNK fork: the background refresh (4.10)

- (NSString *)refreshIdentifier {
  NSString *bundle = NSBundle.mainBundle.bundleIdentifier;
  return bundle == nil ? nil : [bundle stringByAppendingString:kRefreshSuffix];
}

// Registers the refresh task's handler, once per process, and only when the
// app lists the identifier: iOS throws for an identifier it does not list.
- (void)registerRefreshTask {
  if (@available(iOS 13.0, *)) {
    if (refreshRegistered) {
      return;
    }
    NSString *identifier = [self refreshIdentifier];
    id permitted = [NSBundle.mainBundle
        objectForInfoDictionaryKey:@"BGTaskSchedulerPermittedIdentifiers"];
    if (identifier == nil || ![permitted isKindOfClass:[NSArray class]] ||
        ![(NSArray *)permitted containsObject:identifier]) {
      return;
    }
    refreshRegistered = YES;
    __weak LocationPlugin *weakSelf = self;
    [BGTaskScheduler.sharedScheduler
        registerForTaskWithIdentifier:identifier
                           usingQueue:dispatch_get_main_queue()
                        launchHandler:^(__kindof BGTask *_Nonnull task) {
                          LocationPlugin *strongSelf = weakSelf;
                          if (strongSelf == nil) {
                            [task setTaskCompletedWithSuccess:NO];
                            return;
                          }
                          [strongSelf runRefreshTask:task];
                        }];
  }
}

// One run: the next request first (a run that iOS cuts short must not end
// the chain), the alert, then Dart. A live engine that listens gets
// onBackgroundRefresh at once. On a cold launch Dart takes the background
// path (wasLaunchedByLocationEvent); its share start listens, which hands
// the task over, and the start also completes the task when it ends.
- (void)runRefreshTask:(BGTask *)task API_AVAILABLE(ios(13.0)) {
  [self submitRefreshRequestAfter:kRefreshIntervalSeconds];
  [self completeRefreshTask:YES];
  self.refreshTask = task;
  self.launchedForRefresh = YES;
  __weak LocationPlugin *weakSelf = self;
  __weak BGTask *weakTask = task;
  task.expirationHandler = ^{
    dispatch_async(dispatch_get_main_queue(), ^{
      LocationPlugin *strongSelf = weakSelf;
      if (strongSelf != nil && strongSelf.refreshTask == weakTask) {
        [strongSelf completeRefreshTask:NO];
      }
    });
  };
  [self syncLocationAlert];
  if (self.refreshListening) {
    [self deliverRefreshToDart];
  }
}

// Hands the pending task to Dart's refresh handler and completes it when
// the handler answers. A handler that is gone answers "not implemented":
// the task then waits for the expiry or for completeBackgroundRefresh.
- (void)deliverRefreshToDart {
  id task = self.refreshTask;
  if (task == nil) {
    return;
  }
  __weak LocationPlugin *weakSelf = self;
  __weak id weakTask = task;
  [self.channel
      invokeMethod:@"onBackgroundRefresh"
         arguments:nil
            result:^(id _Nullable answer) {
              if (answer == FlutterMethodNotImplemented ||
                  [answer isKindOfClass:[FlutterError class]]) {
                return;
              }
              LocationPlugin *strongSelf = weakSelf;
              if (strongSelf != nil && strongSelf.refreshTask == weakTask) {
                [strongSelf completeRefreshTask:YES];
              }
            }];
}

// Completes the pending refresh task; NO when none is pending.
- (BOOL)completeRefreshTask:(BOOL)success {
  if (@available(iOS 13.0, *)) {
    BGTask *task = self.refreshTask;
    if (task == nil) {
      return NO;
    }
    self.refreshTask = nil;
    [task setTaskCompletedWithSuccess:success];
    return YES;
  }
  return NO;
}

- (BOOL)submitRefreshRequestAfter:(NSTimeInterval)delay
    API_AVAILABLE(ios(13.0)) {
  if (!refreshRegistered) {
    return NO;
  }
  BGAppRefreshTaskRequest *request =
      [[BGAppRefreshTaskRequest alloc] initWithIdentifier:[self refreshIdentifier]];
  request.earliestBeginDate = [NSDate dateWithTimeIntervalSinceNow:delay];
  NSError *error = nil;
  if (![BGTaskScheduler.sharedScheduler submitTaskRequest:request
                                                    error:&error]) {
    // Background App Refresh off, Low Power Mode, or a simulator: no run.
    NSLog(@"[Location] background refresh not scheduled: %@", error);
    return NO;
  }
  return YES;
}

// Keeps one request pending. A request that already waits keeps its date,
// so the frequent syncs of a live app never push the run out of reach.
- (void)ensureRefreshScheduled:(FlutterResult)result {
  if (@available(iOS 13.0, *)) {
    if (!refreshRegistered) {
      result(@0);
      return;
    }
    [[NSUserDefaults standardUserDefaults] setBool:YES
                                            forKey:kRefreshWantedKey];
    NSString *identifier = [self refreshIdentifier];
    __weak LocationPlugin *weakSelf = self;
    [BGTaskScheduler.sharedScheduler getPendingTaskRequestsWithCompletionHandler:^(
                                         NSArray<BGTaskRequest *> *requests) {
      BOOL pending = NO;
      for (BGTaskRequest *request in requests) {
        if ([request.identifier isEqualToString:identifier]) {
          pending = YES;
          break;
        }
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        LocationPlugin *strongSelf = weakSelf;
        BOOL scheduled =
            pending ||
            (strongSelf != nil &&
             [strongSelf submitRefreshRequestAfter:kRefreshIntervalSeconds]);
        result(scheduled ? @1 : @0);
      });
    }];
  } else {
    result(@0);
  }
}

- (void)cancelRefresh {
  [[NSUserDefaults standardUserDefaults] removeObjectForKey:kRefreshWantedKey];
  if (@available(iOS 13.0, *)) {
    if (refreshRegistered) {
      [BGTaskScheduler.sharedScheduler
          cancelTaskRequestWithIdentifier:[self refreshIdentifier]];
    }
  }
}
#endif

@end
