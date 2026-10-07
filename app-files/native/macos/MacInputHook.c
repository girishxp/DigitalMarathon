#define _DARWIN_C_SOURCE 1

#include <sys/types.h>
#include <CoreGraphics/CoreGraphics.h>
#include <CoreFoundation/CoreFoundation.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>
#include <string.h>
#include <time.h>

/*
 * macOS input bridge for Input Activity Tracker.
 *
 * The Quartz event tap is the low-latency primary source.
 *
 * This is the input pipeline from the user-confirmed working v2.0.6 build.
 * Window appearance and sleep/wake notifications live in a separate Objective-C
 * translation unit. A dedicated wake-safe 60 Hz POSIX fallback sampler reads
 * the physical HID/session key state independently of the
 * Java window and independently of the event-tap run loop. This is important
 * on macOS versions that throttle inactive Java/AWT windows or occasionally
 * deliver a session tap only while the application is frontmost.
 *
 * Only numeric key transitions and click signals are passed to Java. No key is
 * translated to a character and no key identity is persisted by the app.
 */

static pthread_mutex_t gLock = PTHREAD_MUTEX_INITIALIZER;
static CFRunLoopRef gRunLoop = NULL;
static CFMachPortRef gEventTap = NULL;
static JavaVM *gJvm = NULL;
static jobject gBackend = NULL;
static jmethodID gReadyMethod = NULL;
static jmethodID gKeyPressedMethod = NULL;
static jmethodID gKeyReleasedMethod = NULL;
static jmethodID gMouseClickMethod = NULL;
static jmethodID gSystemWakeMethod = NULL;

static pthread_t gSamplerThread;
static bool gSamplerThreadStarted = false;
static atomic_bool gSamplerShouldRun = ATOMIC_VAR_INIT(false);
static atomic_bool gSamplerPaused = ATOMIC_VAR_INIT(false);
static atomic_bool gSamplerReseedRequested = ATOMIC_VAR_INIT(false);

static void clear_java_exception(JNIEnv *env) {
    if (env != NULL && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static JNIEnv *current_or_attached_env(bool *attached_here) {
    if (attached_here != NULL) *attached_here = false;

    pthread_mutex_lock(&gLock);
    JavaVM *jvm = gJvm;
    pthread_mutex_unlock(&gLock);
    if (jvm == NULL) return NULL;

    JNIEnv *env = NULL;
    jint result = (*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_8);
    if (result == JNI_OK) return env;
    if (result != JNI_EDETACHED) return NULL;

    if ((*jvm)->AttachCurrentThread(jvm, (void **) &env, NULL) != JNI_OK) {
        return NULL;
    }
    if (attached_here != NULL) *attached_here = true;
    return env;
}

static void detach_if_needed(bool attached_here) {
    if (!attached_here) return;
    pthread_mutex_lock(&gLock);
    JavaVM *jvm = gJvm;
    pthread_mutex_unlock(&gLock);
    if (jvm != NULL) (*jvm)->DetachCurrentThread(jvm);
}

static void notify_ready(JNIEnv *env, bool success, int permission_state, const char *detail) {
    if (env == NULL) return;

    pthread_mutex_lock(&gLock);
    jobject backend = gBackend == NULL ? NULL : (*env)->NewLocalRef(env, gBackend);
    jmethodID ready_method = gReadyMethod;
    pthread_mutex_unlock(&gLock);
    if (backend == NULL || ready_method == NULL) {
        if (backend != NULL) (*env)->DeleteLocalRef(env, backend);
        return;
    }

    jstring message = (*env)->NewStringUTF(env, detail == NULL ? "" : detail);
    if (message != NULL) {
        (*env)->CallVoidMethod(env, backend, ready_method,
                               success ? JNI_TRUE : JNI_FALSE,
                               (jint) permission_state,
                               message);
        (*env)->DeleteLocalRef(env, message);
    }
    (*env)->DeleteLocalRef(env, backend);
    clear_java_exception(env);
}

static void call_key_method(jmethodID requested_method, int key_code) {
    bool attached_here = false;
    JNIEnv *env = current_or_attached_env(&attached_here);
    if (env == NULL) return;

    pthread_mutex_lock(&gLock);
    jmethodID method = requested_method;
    jobject backend = gBackend == NULL ? NULL : (*env)->NewLocalRef(env, gBackend);
    pthread_mutex_unlock(&gLock);

    if (backend != NULL && method != NULL) {
        (*env)->CallVoidMethod(env, backend, method, (jint) key_code);
        (*env)->DeleteLocalRef(env, backend);
        clear_java_exception(env);
    }
    detach_if_needed(attached_here);
}

static void call_mouse_click(void) {
    bool attached_here = false;
    JNIEnv *env = current_or_attached_env(&attached_here);
    if (env == NULL) return;

    pthread_mutex_lock(&gLock);
    jmethodID method = gMouseClickMethod;
    jobject backend = gBackend == NULL ? NULL : (*env)->NewLocalRef(env, gBackend);
    pthread_mutex_unlock(&gLock);

    if (backend != NULL && method != NULL) {
        (*env)->CallVoidMethod(env, backend, method);
        (*env)->DeleteLocalRef(env, backend);
        clear_java_exception(env);
    }
    detach_if_needed(attached_here);
}

static void call_system_wake(void) {
    bool attached_here = false;
    JNIEnv *env = current_or_attached_env(&attached_here);
    if (env == NULL) return;

    pthread_mutex_lock(&gLock);
    jmethodID method = gSystemWakeMethod;
    jobject backend = gBackend == NULL ? NULL : (*env)->NewLocalRef(env, gBackend);
    pthread_mutex_unlock(&gLock);

    if (backend != NULL && method != NULL) {
        (*env)->CallVoidMethod(env, backend, method);
        (*env)->DeleteLocalRef(env, backend);
        clear_java_exception(env);
    }
    detach_if_needed(attached_here);
}

static bool physical_key_is_down(CGKeyCode key_code) {
    /* HID state is closest to physical hardware. Combined session state is
       retained as a fallback for keyboards exposed through software layers. */
    bool hid = CGEventSourceKeyState(kCGEventSourceStateHIDSystemState, key_code);
    bool session = CGEventSourceKeyState(kCGEventSourceStateCombinedSessionState, key_code);
    return hid || session;
}

static void *key_sampler_main(void *unused) {
    (void) unused;

    bool attached_here = false;
    JNIEnv *env = current_or_attached_env(&attached_here);
    if (env == NULL) return NULL;

    bool previous[128];
    memset(previous, 0, sizeof(previous));
    bool seeded = false;
    /* The sampler is a fallback for unusual keyboards. 60 Hz is ample because
       the passive Quartz event tap remains the primary source, and it avoids
       hammering CoreGraphics while the WindowServer is resuming from sleep. */
    const struct timespec interval = {0, 16666667L}; /* approximately 60 Hz */

    while (atomic_load_explicit(&gSamplerShouldRun, memory_order_acquire)) {
        if (atomic_load_explicit(&gSamplerPaused, memory_order_acquire)) {
            seeded = false;
            nanosleep(&interval, NULL);
            continue;
        }

        bool sampled[128];
        for (int key_code = 0; key_code < 128; key_code++) {
            sampled[key_code] = physical_key_is_down((CGKeyCode) key_code);
        }

        bool force_reseed = atomic_exchange_explicit(
                &gSamplerReseedRequested, false, memory_order_acq_rel);
        if (!seeded || force_reseed) {
            /* Never synthesize a burst of stale press/release transitions after
               lid-open. Seed from the hardware state that exists after wake. */
            memcpy(previous, sampled, sizeof(previous));
            seeded = true;
        } else {
            for (int key_code = 0; key_code < 128; key_code++) {
                bool is_down = sampled[key_code];
                bool was_down = previous[key_code];
                if (key_code == 57) {
                    /* Caps Lock reports a toggle state. Every transition is one
                       physical press, followed by an immediate logical release. */
                    if (is_down != was_down) {
                        call_key_method(gKeyPressedMethod, key_code);
                        call_key_method(gKeyReleasedMethod, key_code);
                    }
                } else if (is_down && !was_down) {
                    call_key_method(gKeyPressedMethod, key_code);
                } else if (!is_down && was_down) {
                    call_key_method(gKeyReleasedMethod, key_code);
                }
                previous[key_code] = is_down;
            }
        }
        nanosleep(&interval, NULL);
    }

    for (int key_code = 0; key_code < 128; key_code++) {
        if (key_code != 57 && previous[key_code]) {
            call_key_method(gKeyReleasedMethod, key_code);
        }
    }

    detach_if_needed(attached_here);
    return NULL;
}

static bool start_key_sampler(void) {
    if (gSamplerThreadStarted) return true;
    atomic_store_explicit(&gSamplerPaused, false, memory_order_release);
    atomic_store_explicit(&gSamplerReseedRequested, true, memory_order_release);
    atomic_store_explicit(&gSamplerShouldRun, true, memory_order_release);
    int result = pthread_create(&gSamplerThread, NULL, key_sampler_main, NULL);
    if (result != 0) {
        atomic_store_explicit(&gSamplerShouldRun, false, memory_order_release);
        return false;
    }
    gSamplerThreadStarted = true;
    return true;
}

static void stop_key_sampler(void) {
    if (!gSamplerThreadStarted) return;
    atomic_store_explicit(&gSamplerShouldRun, false, memory_order_release);
    if (!pthread_equal(pthread_self(), gSamplerThread)) {
        pthread_join(gSamplerThread, NULL);
    }
    gSamplerThreadStarted = false;
}

static void handle_flags_changed(CGEventRef event) {
    int key_code = (int) CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
    if (key_code < 0 || key_code >= 128) return;

    if (key_code == 57) {
        call_key_method(gKeyPressedMethod, key_code);
        call_key_method(gKeyReleasedMethod, key_code);
        return;
    }

    if (physical_key_is_down((CGKeyCode) key_code)) {
        call_key_method(gKeyPressedMethod, key_code);
    } else {
        call_key_method(gKeyReleasedMethod, key_code);
    }
}

static CGEventRef event_callback(CGEventTapProxy proxy,
                                 CGEventType type,
                                 CGEventRef event,
                                 void *user_info) {
    (void) proxy;
    (void) user_info;

    if (type == kCGEventTapDisabledByTimeout || type == kCGEventTapDisabledByUserInput) {
        pthread_mutex_lock(&gLock);
        CFMachPortRef tap = gEventTap;
        if (tap != NULL) CFRetain(tap);
        pthread_mutex_unlock(&gLock);
        if (tap != NULL) {
            CGEventTapEnable(tap, true);
            CFRelease(tap);
        }
        return event;
    }

    switch (type) {
        case kCGEventKeyDown: {
            bool is_repeat = CGEventGetIntegerValueField(event, kCGKeyboardEventAutorepeat) != 0;
            if (!is_repeat) {
                int key_code = (int) CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
                call_key_method(gKeyPressedMethod, key_code);
            }
            break;
        }
        case kCGEventKeyUp: {
            int key_code = (int) CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode);
            call_key_method(gKeyReleasedMethod, key_code);
            break;
        }
        case kCGEventFlagsChanged:
            handle_flags_changed(event);
            break;
        case kCGEventLeftMouseDown:
        case kCGEventRightMouseDown:
        case kCGEventOtherMouseDown:
            call_mouse_click();
            break;
        default:
            break;
    }
    return event;
}

static void clear_backend(JNIEnv *env) {
    pthread_mutex_lock(&gLock);
    jobject backend = gBackend;
    gBackend = NULL;
    gJvm = NULL;
    gReadyMethod = NULL;
    gKeyPressedMethod = NULL;
    gKeyReleasedMethod = NULL;
    gMouseClickMethod = NULL;
    gSystemWakeMethod = NULL;
    pthread_mutex_unlock(&gLock);

    if (env != NULL && backend != NULL) {
        (*env)->DeleteGlobalRef(env, backend);
    }
}

JNIEXPORT void JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeRun(JNIEnv *env, jobject self) {
    if (env == NULL || self == NULL) return;

    pthread_mutex_lock(&gLock);
    if (gBackend != NULL || gRunLoop != NULL) {
        pthread_mutex_unlock(&gLock);
        jclass clazz = (*env)->GetObjectClass(env, self);
        jmethodID ready = clazz == NULL ? NULL : (*env)->GetMethodID(
                env, clazz, "onNativeReady", "(ZILjava/lang/String;)V");
        if (ready != NULL) {
            jstring message = (*env)->NewStringUTF(env, "A macOS input listener is already running");
            (*env)->CallVoidMethod(env, self, ready, JNI_FALSE, (jint) 0, message);
            if (message != NULL) (*env)->DeleteLocalRef(env, message);
            clear_java_exception(env);
        }
        if (clazz != NULL) (*env)->DeleteLocalRef(env, clazz);
        return;
    }

    jclass clazz = (*env)->GetObjectClass(env, self);
    if (clazz == NULL) {
        pthread_mutex_unlock(&gLock);
        clear_java_exception(env);
        return;
    }

    (*env)->GetJavaVM(env, &gJvm);
    gBackend = (*env)->NewGlobalRef(env, self);
    gReadyMethod = (*env)->GetMethodID(env, clazz, "onNativeReady", "(ZILjava/lang/String;)V");
    gKeyPressedMethod = (*env)->GetMethodID(env, clazz, "onNativeKeyPressed", "(I)V");
    gKeyReleasedMethod = (*env)->GetMethodID(env, clazz, "onNativeKeyReleased", "(I)V");
    gMouseClickMethod = (*env)->GetMethodID(env, clazz, "onNativeMouseClick", "()V");
    gSystemWakeMethod = (*env)->GetMethodID(env, clazz, "onNativeSystemWake", "()V");
    (*env)->DeleteLocalRef(env, clazz);

    bool method_error = gBackend == NULL || gReadyMethod == NULL ||
                        gKeyPressedMethod == NULL || gKeyReleasedMethod == NULL ||
                        gMouseClickMethod == NULL || gSystemWakeMethod == NULL;
    pthread_mutex_unlock(&gLock);

    if (method_error) {
        clear_java_exception(env);
        notify_ready(env, false, 0, "JNI callback setup failed");
        clear_backend(env);
        return;
    }

    bool preflight = CGPreflightListenEventAccess();
    if (!preflight) {
        (void) CGRequestListenEventAccess();
        preflight = CGPreflightListenEventAccess();
    }
    if (!preflight) {
        /* Do not report a false-green listener based only on the physical-state
         * sampler. A rebuilt ad-hoc app can leave a visually enabled but stale
         * TCC entry. Java retries automatically after the user enables the
         * newly built bundle. */
        notify_ready(env, false, 1,
                     "Input Monitoring has not approved this rebuilt application bundle");
        clear_backend(env);
        return;
    }

    CGEventMask mask = CGEventMaskBit(kCGEventKeyDown)
                     | CGEventMaskBit(kCGEventKeyUp)
                     | CGEventMaskBit(kCGEventFlagsChanged)
                     | CGEventMaskBit(kCGEventLeftMouseDown)
                     | CGEventMaskBit(kCGEventRightMouseDown)
                     | CGEventMaskBit(kCGEventOtherMouseDown);

    /* This app observes input; it must never become an active event filter.
       A passive listen-only tap cannot modify or divert the user's keyboard or
       mouse stream, which is especially important during lid-close/lid-open. */
    bool using_hid_tap = false;
    CFMachPortRef tap = CGEventTapCreate(kCGSessionEventTap,
                                         kCGHeadInsertEventTap,
                                         kCGEventTapOptionListenOnly,
                                         mask,
                                         event_callback,
                                         NULL);
    if (tap == NULL) {
        using_hid_tap = true;
        tap = CGEventTapCreate(kCGHIDEventTap,
                               kCGHeadInsertEventTap,
                               kCGEventTapOptionListenOnly,
                               mask,
                               event_callback,
                               NULL);
    }

    if (tap == NULL) {
        notify_ready(env, false, preflight ? 2 : 1,
                     preflight
                        ? "The Quartz event tap returned no handle"
                        : "Enable the newly built app under Privacy & Security > Input Monitoring");
        clear_backend(env);
        return;
    }

    CFRunLoopSourceRef source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0);
    if (source == NULL) {
        CFRelease(tap);
        notify_ready(env, false, 2, "Could not create the Quartz run-loop source");
        clear_backend(env);
        return;
    }

    CFRunLoopRef run_loop = CFRunLoopGetCurrent();
    CFRetain(run_loop);

    pthread_mutex_lock(&gLock);
    gEventTap = tap;
    gRunLoop = run_loop;
    pthread_mutex_unlock(&gLock);

    CFRunLoopAddSource(run_loop, source, kCFRunLoopCommonModes);
    CGEventTapEnable(tap, true);
    if (!CGEventTapIsEnabled(tap)) {
        CFRunLoopRemoveSource(run_loop, source, kCFRunLoopCommonModes);
        pthread_mutex_lock(&gLock);
        gEventTap = NULL;
        gRunLoop = NULL;
        pthread_mutex_unlock(&gLock);
        CFRelease(source);
        CFRelease(tap);
        CFRelease(run_loop);
        notify_ready(env, false, preflight ? 2 : 1,
                     "macOS created the event tap but did not enable it");
        clear_backend(env);
        return;
    }

    bool sampler_started = start_key_sampler();
    if (sampler_started) {
        notify_ready(env, true, 0,
                     using_hid_tap
                        ? "passive HID tap + wake-safe 60 Hz key fallback"
                        : "passive session tap + wake-safe 60 Hz key fallback");
    } else {
        notify_ready(env, true, 0,
                     using_hid_tap
                        ? "passive HID event tap (physical sampler unavailable)"
                        : "passive session event tap (physical sampler unavailable)");
    }

    CFRunLoopRun();

    stop_key_sampler();
    CFRunLoopRemoveSource(run_loop, source, kCFRunLoopCommonModes);
    CGEventTapEnable(tap, false);

    pthread_mutex_lock(&gLock);
    gEventTap = NULL;
    gRunLoop = NULL;
    pthread_mutex_unlock(&gLock);

    CFRelease(source);
    CFRelease(tap);
    CFRelease(run_loop);
    clear_backend(env);
}

/* Called by the AppKit workspace sleep/wake observer in MacWindowSupport.m.
 * These functions never install an active event filter. Sleep pauses the
 * physical-state fallback; wake immediately rearms the passive tap, reseeds
 * key state, and clears Java-side transient pressed-key/pointer state. */
void InputActivityMacHandleSleep(void) {
    atomic_store_explicit(&gSamplerPaused, true, memory_order_release);
    atomic_store_explicit(&gSamplerReseedRequested, true, memory_order_release);
}

void InputActivityMacHandleWake(void) {
    atomic_store_explicit(&gSamplerReseedRequested, true, memory_order_release);
    atomic_store_explicit(&gSamplerPaused, false, memory_order_release);

    pthread_mutex_lock(&gLock);
    CFMachPortRef tap = gEventTap;
    CFRunLoopRef run_loop = gRunLoop;
    if (tap != NULL) CFRetain(tap);
    if (run_loop != NULL) CFRetain(run_loop);
    pthread_mutex_unlock(&gLock);

    if (tap != NULL) {
        if (!CGEventTapIsEnabled(tap)) CGEventTapEnable(tap, true);
        CFRelease(tap);
    }
    if (run_loop != NULL) {
        CFRunLoopWakeUp(run_loop);
        CFRelease(run_loop);
    }

    call_system_wake();
}

JNIEXPORT void JNICALL
Java_com_inputactivitytracker_MacNativeInputBackend_nativeStop(JNIEnv *env, jobject self) {
    (void) env;
    (void) self;

    atomic_store_explicit(&gSamplerShouldRun, false, memory_order_release);

    pthread_mutex_lock(&gLock);
    CFRunLoopRef run_loop = gRunLoop;
    if (run_loop != NULL) CFRetain(run_loop);
    pthread_mutex_unlock(&gLock);

    if (run_loop != NULL) {
        CFRunLoopStop(run_loop);
        CFRelease(run_loop);
    }
}
