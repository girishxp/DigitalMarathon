#define _DARWIN_C_SOURCE 1

#include <sys/types.h>
#import <AppKit/AppKit.h>
#import <objc/runtime.h>
#include <dispatch/dispatch.h>
#include <jni.h>
#include <stdint.h>

/* Input backend wake hooks implemented in MacInputHook.c. */
extern void InputActivityMacHandleSleep(void);
extern void InputActivityMacHandleWake(void);

/*
 * Register for system sleep/wake once the native library is loaded. Using
 * NSWorkspace notifications gives the input backend an immediate lid-open
 * signal instead of waiting for the first key/mouse event or a multi-second
 * Java watchdog.
 */
__attribute__((constructor))
static void InstallDigitalMarathonWakeObserver(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSNotificationCenter *center = [[NSWorkspace sharedWorkspace] notificationCenter];
        [center addObserverForName:NSWorkspaceWillSleepNotification
                           object:nil
                            queue:[NSOperationQueue mainQueue]
                       usingBlock:^(NSNotification *note) {
                           (void) note;
                           InputActivityMacHandleSleep();
                       }];
        [center addObserverForName:NSWorkspaceDidWakeNotification
                           object:nil
                            queue:[NSOperationQueue mainQueue]
                       usingBlock:^(NSNotification *note) {
                           (void) note;
                           InputActivityMacHandleWake();
                       }];
    });
}

/*
 * Window-only native helpers.
 *
 * Kept deliberately separate from MacInputHook.c. The input bridge remains a
 * small CoreGraphics/CoreFoundation event-tap implementation, while this file
 * touches AppKit only to configure the existing Java window.
 */
static char DigitalMarathonControlledDragging;

/*
 * The bundled OpenJDK CPlatformWindow owns the real NSWindow pointer. Resolve
 * that specific peer instead of selecting windows by their mutable title.
 * No AppKit object is dereferenced on Java's EDT; it is validated against
 * NSApp's current windows when the queued main-thread operation executes.
 */
static NSWindow *DigitalMarathonJavaWindowPointer(JNIEnv *env, jobject java_window) {
    if (java_window == NULL) return nil;
    jclass component_class = (*env)->FindClass(env, "java/awt/Component");
    if (component_class == NULL) goto failed;
    jfieldID peer_field = (*env)->GetFieldID(env, component_class, "peer", "Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, component_class);
    if (peer_field == NULL) goto failed;
    jobject peer = (*env)->GetObjectField(env, java_window, peer_field);
    if (peer == NULL) return nil;
    jclass peer_class = (*env)->GetObjectClass(env, peer);
    jmethodID platform_method = (*env)->GetMethodID(env, peer_class, "getPlatformWindow", "()Lsun/lwawt/PlatformWindow;");
    (*env)->DeleteLocalRef(env, peer_class);
    if (platform_method == NULL) {
        (*env)->DeleteLocalRef(env, peer);
        goto failed;
    }
    jobject platform = (*env)->CallObjectMethod(env, peer, platform_method);
    (*env)->DeleteLocalRef(env, peer);
    if ((*env)->ExceptionCheck(env)) goto failed;
    if (platform == NULL) return nil;
    jclass platform_class = (*env)->GetObjectClass(env, platform);
    jfieldID pointer_field = (*env)->GetFieldID(env, platform_class, "ptr", "J");
    (*env)->DeleteLocalRef(env, platform_class);
    if (pointer_field == NULL) {
        (*env)->DeleteLocalRef(env, platform);
        goto failed;
    }
    jlong pointer = (*env)->GetLongField(env, platform, pointer_field);
    (*env)->DeleteLocalRef(env, platform);
    return (NSWindow *)(intptr_t) pointer;

failed:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return nil;
}

static NSWindow *DigitalMarathonExistingWindow(NSWindow *pointer) {
    for (NSWindow *window in [NSApp windows]) {
        if (window == pointer) return window;
    }
    return nil;
}

static void DigitalMarathonLockNativeDragging(NSWindow *window) {
    [window setMovableByWindowBackground:NO];
    // draggableWindowBackground=false alone does not disable native title-bar
    // dragging. Both transparency bars overlap that area in Full/Mini View.
    // Programmatic setFrame/setLocation used by WindowDragSupport still works.
    [window setMovable:NO];
}

static void DigitalMarathonConfigureDragging(NSWindow *window) {
    objc_setAssociatedObject(window, &DigitalMarathonControlledDragging,
                             @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    DigitalMarathonLockNativeDragging(window);
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // Enforce the policy before AppKit processes mouse-down, rather than
        // after Swing receives it. Style changes can reset native properties;
        // this also removes the race that lets Cocoa steal the first gesture.
        [NSEvent addLocalMonitorForEventsMatchingMask:(NSEventMaskLeftMouseDown | NSEventMaskLeftMouseDragged)
                                             handler:^NSEvent *(NSEvent *event) {
            NSWindow *target = [event window];
            if (target != nil && objc_getAssociatedObject(target, &DigitalMarathonControlledDragging) != nil) {
                DigitalMarathonLockNativeDragging(target);
            }
            return event; // Preserve the original event for AWT/Swing.
        }];
    });
}

JNIEXPORT jboolean JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeConfigureWindowDragging(
        JNIEnv *env, jclass type, jobject java_window) {
    (void) type;
    NSWindow *pointer = DigitalMarathonJavaWindowPointer(env, java_window);
    if (pointer == nil) return JNI_FALSE;
    void (^configure_block)(void) = ^{
        NSWindow *window = DigitalMarathonExistingWindow(pointer);
        if (window != nil) DigitalMarathonConfigureDragging(window);
    };
    if ([NSThread isMainThread]) configure_block();
    else dispatch_async(dispatch_get_main_queue(), configure_block);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeSetWindowAppearance(
        JNIEnv *env, jclass type, jobject java_window, jboolean dark_theme) {
    (void) type;
    NSWindow *pointer = DigitalMarathonJavaWindowPointer(env, java_window);
    if (pointer == nil) return JNI_FALSE;
    BOOL dark = dark_theme == JNI_TRUE;
    void (^appearance_block)(void) = ^{
        NSWindow *window = DigitalMarathonExistingWindow(pointer);
        if (window != nil) {
            [window setAppearance:[NSAppearance appearanceNamed:(dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua)]];
        }
    };
    if ([NSThread isMainThread]) appearance_block();
    else dispatch_async(dispatch_get_main_queue(), appearance_block);
    return JNI_TRUE;
}

/* Read-only verification; callers use a diagnostics worker, never Swing's EDT. */
JNIEXPORT jint JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeGetWindowDraggingState(
        JNIEnv *env, jclass type, jobject java_window) {
    (void) type;
    NSWindow *pointer = DigitalMarathonJavaWindowPointer(env, java_window);
    if (pointer == nil) return -1;
    __block jint state = -1;
    void (^read_block)(void) = ^{
        NSWindow *window = DigitalMarathonExistingWindow(pointer);
        if (window == nil) return;
        state = ([window isMovable] ? 1 : 0) | ([window isMovableByWindowBackground] ? 2 : 0)
                | (objc_getAssociatedObject(window, &DigitalMarathonControlledDragging) != nil ? 4 : 0);
    };
    if ([NSThread isMainThread]) read_block();
    else dispatch_sync(dispatch_get_main_queue(), read_block);
    return state;
}

JNIEXPORT jboolean JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeSetWindowOpacity(
        JNIEnv *env, jclass type, jobject java_window, jfloat requested_opacity) {
    (void) type;

    NSWindow *pointer = DigitalMarathonJavaWindowPointer(env, java_window);
    if (pointer == nil) return JNI_FALSE;

    CGFloat opacity = (CGFloat) requested_opacity;
    if (opacity < 0.25) opacity = 0.25;
    if (opacity > 1.0) opacity = 1.0;

    void (^apply_block)(void) = ^{
        NSWindow *window = DigitalMarathonExistingWindow(pointer);
        if (window != nil) {
            DigitalMarathonConfigureDragging(window);
            [window setAlphaValue:opacity];
        }
    };

    /* Never synchronously wait for AppKit from Swing's EDT. */
    if ([NSThread isMainThread]) apply_block();
    else dispatch_async(dispatch_get_main_queue(), apply_block);
    return JNI_TRUE;
}

/*
 * Mini View is deliberately fixed-size. Hide the macOS zoom/full-screen
 * traffic-light button while Mini View is active, and restore it for Full View.
 */
JNIEXPORT jboolean JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeSetMiniWindowChrome(
        JNIEnv *env, jclass type, jboolean mini_mode) {
    (void) env;
    (void) type;

    BOOL mini = mini_mode == JNI_TRUE;
    void (^apply_block)(void) = ^{
        for (NSWindow *window in [NSApp windows]) {
            NSString *title = [window title];
            if (title != nil && [title hasPrefix:@"Digital Marathon"]) {
                NSButton *zoomButton = [window standardWindowButton:NSWindowZoomButton];
                if (zoomButton != nil) {
                    [zoomButton setEnabled:!mini];
                    [zoomButton setHidden:mini];
                }
                NSUInteger styleMask = [window styleMask];
                if (mini) {
                    [window setStyleMask:(styleMask & ~NSWindowStyleMaskResizable)];
                } else {
                    [window setStyleMask:(styleMask | NSWindowStyleMaskResizable)];
                }
                if (objc_getAssociatedObject(window, &DigitalMarathonControlledDragging) != nil) {
                    DigitalMarathonLockNativeDragging(window);
                }
            }
        }
    };

    if ([NSThread isMainThread]) apply_block();
    else dispatch_async(dispatch_get_main_queue(), apply_block);
    return JNI_TRUE;
}

/*
 * Optional screen-capture privacy for the Digital Marathon window.
 *
 * NSWindowSharingNone tells the macOS window server not to expose this
 * window's contents to normal window sharing/capture APIs. Turning the option
 * off restores the standard read-only sharing behavior so screenshots and
 * screen sharing work normally again.
 */
JNIEXPORT jboolean JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeSetWindowCapturePrivacy(
        JNIEnv *env, jclass type, jboolean enabled) {
    (void) env;
    (void) type;

    BOOL protect = enabled == JNI_TRUE;
    void (^apply_block)(void) = ^{
        for (NSWindow *window in [NSApp windows]) {
            if ([window isVisible]) {
                [window setSharingType:(protect ? NSWindowSharingNone : NSWindowSharingReadOnly)];
            }
        }
    };

    if ([NSThread isMainThread]) apply_block();
    else dispatch_async(dispatch_get_main_queue(), apply_block);
    return JNI_TRUE;
}
