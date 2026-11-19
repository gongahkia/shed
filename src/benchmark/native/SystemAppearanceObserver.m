#import <AppKit/AppKit.h>

// Disposable AppKit control, never linked into Shed. All observation ownership stays on the main thread.
static void *AppearanceContext = &AppearanceContext;
static NSUInteger callbacks = 0;
static NSString *request = @"startup";

static void emit(NSString *event, NSDictionary *extra) {
    NSMutableDictionary *row = [@{
        @"event": event, @"request": request, @"epoch_ms": @([NSDate.date timeIntervalSince1970] * 1000),
        @"main_thread": @(NSThread.isMainThread), @"callbacks": @(callbacks),
        @"effective": NSApp.effectiveAppearance.name ?: @"nil",
        @"explicit": NSApp.appearance.name ?: @"nil"
    } mutableCopy];
    [row addEntriesFromDictionary:extra];
    NSData *data = [NSJSONSerialization dataWithJSONObject:row options:0 error:NULL];
    puts([[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
    fflush(stdout);
}

@interface AppearanceObserver : NSObject
@property BOOL observing;
- (void)start;
- (void)stop;
@end

@implementation AppearanceObserver
- (void)start {
    if (self.observing) { emit(@"already_registered", @{}); return; }
    self.observing = YES;
    double began = NSProcessInfo.processInfo.systemUptime;
    [NSApp addObserver:self forKeyPath:@"effectiveAppearance" options:NSKeyValueObservingOptionInitial | NSKeyValueObservingOptionNew context:AppearanceContext];
    emit(@"registered", @{@"registration_ms": @((NSProcessInfo.processInfo.systemUptime - began) * 1000)});
}
- (void)stop {
    if (self.observing) {
        [NSApp removeObserver:self forKeyPath:@"effectiveAppearance" context:AppearanceContext];
        self.observing = NO;
    }
    emit(@"unregistered", @{});
}
- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != AppearanceContext) { [super observeValueForKeyPath:keyPath ofObject:object change:change context:context]; return; }
    callbacks++;
    emit(@"appearance", @{});
}
@end

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        [NSApp finishLaunching];
        AppearanceObserver *observer = [AppearanceObserver new];
        [observer start];
        emit(@"ready", @{});
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            char *line = NULL; size_t capacity = 0;
            while (getline(&line, &capacity, stdin) > 0) {
                NSString *command = [[NSString stringWithUTF8String:line] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
                dispatch_async(dispatch_get_main_queue(), ^{
                    @try {
                        request = command;
                        if ([command isEqual:@"register"]) [observer start];
                        else if ([command isEqual:@"unregister"]) [observer stop];
                        else if ([command isEqual:@"pin-light"]) NSApp.appearance = [NSAppearance appearanceNamed:NSAppearanceNameAqua];
                        else if ([command isEqual:@"follow"]) NSApp.appearance = nil;
                        else if ([command isEqual:@"exit"]) { [observer stop]; emit(@"exit", @{}); exit(0); }
                        emit(@"ack", @{@"observing": @(observer.observing)});
                    } @catch (NSException *exception) {
                        emit(@"error", @{@"name": exception.name, @"reason": exception.reason ?: @""});
                        exit(2);
                    }
                });
            }
            free(line);
            dispatch_async(dispatch_get_main_queue(), ^{ [observer stop]; exit(0); });
        });
        [NSApp run];
    }
    return 0;
}
