package com.inputactivitytracker;

import java.lang.reflect.*;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JNativeHook bridge used on Windows and Linux/X11 (and as a Linux fallback).
 *
 * Native callbacks are intentionally handed off to a dedicated Java worker so
 * the operating-system hook callback never waits on tracker state or disk/UI
 * work. The hook can also be re-registered after suspend/resume if the desktop
 * removed it while the machine was asleep.
 */
final class NativeHookBridge implements AutoCloseable {
    private final ActivityTracker tracker;
    private final Object lifecycleLock = new Object();
    private final AtomicLong eventGeneration = new AtomicLong();
    private final ThreadPoolExecutor eventExecutor;

    private Class<?> globalScreenClass;
    private Class<?> keyListenerClass;
    private Class<?> mouseListenerClass;
    private Class<?> motionListenerClass;
    private Object keyListener;
    private Object mouseListener;
    private Object motionListener;
    private volatile boolean registered;
    private volatile boolean closed;

    NativeHookBridge(ActivityTracker tracker) {
        this.tracker = tracker;
        this.eventExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096),
                runnable -> {
                    Thread thread = new Thread(runnable, "input-activity-nativehook-events");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    boolean start() {
        synchronized (lifecycleLock) {
            if (closed) return false;
            if (registered && nativeHookReportedRegistered()) return true;
            unbindHook();
            return bindHook();
        }
    }

    /**
     * Re-register the native hook after a detected system resume. This is done
     * off the Swing thread by InputRecoveryMonitor and is safe even when the
     * previous operating-system hook survived the sleep cycle.
     */
    boolean recoverAfterSystemResume() {
        synchronized (lifecycleLock) {
            if (closed) return false;
            eventGeneration.incrementAndGet(); // discard stale pre-sleep events
            eventExecutor.getQueue().clear();
            // If the operating-system hook survived suspend, keep it in place so
            // the very first post-wake input event is still observed. Re-register
            // only when the hook actually disappeared.
            if (registered && nativeHookReportedRegistered()) return true;
            unbindHook();
            // Give a dead native hook thread a brief background-only teardown
            // window before re-registering. This never blocks desktop input/UI.
            try { Thread.sleep(120L); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return bindHook();
        }
    }

    boolean isHealthy() {
        synchronized (lifecycleLock) {
            return !closed && registered && nativeHookReportedRegistered();
        }
    }

    private boolean bindHook() {
        try {
            ensureTypesLoaded();
            invokeStatic(globalScreenClass, "registerNativeHook");
            registered = true;

            ensureListenersCreated();
            invokeStatic(globalScreenClass, "addNativeKeyListener", keyListenerClass, keyListener);
            invokeStatic(globalScreenClass, "addNativeMouseListener", mouseListenerClass, mouseListener);
            invokeStatic(globalScreenClass, "addNativeMouseMotionListener", motionListenerClass, motionListener);

            tracker.updateInputStatus(platformReadyMessage(), true);
            return true;
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            tracker.updateInputStatus(platformFailureMessage(cause), false);
            unbindHook();
            return false;
        }
    }

    private void ensureTypesLoaded() throws ClassNotFoundException {
        if (globalScreenClass != null) return;
        String prefix = detectPackagePrefix();
        Logger hookLogger = Logger.getLogger(prefix);
        hookLogger.setLevel(Level.OFF);
        hookLogger.setUseParentHandlers(false);

        globalScreenClass = Class.forName(prefix + ".GlobalScreen");
        keyListenerClass = Class.forName(prefix + ".keyboard.NativeKeyListener");
        mouseListenerClass = Class.forName(prefix + ".mouse.NativeMouseListener");
        motionListenerClass = Class.forName(prefix + ".mouse.NativeMouseMotionListener");
    }

    private void ensureListenersCreated() {
        if (keyListener != null) return;
        keyListener = Proxy.newProxyInstance(
                keyListenerClass.getClassLoader(), new Class<?>[]{keyListenerClass}, this::handleKeyInvocation);
        mouseListener = Proxy.newProxyInstance(
                mouseListenerClass.getClassLoader(), new Class<?>[]{mouseListenerClass}, this::handleMouseInvocation);
        motionListener = Proxy.newProxyInstance(
                motionListenerClass.getClassLoader(), new Class<?>[]{motionListenerClass}, this::handleMotionInvocation);
    }

    private static String detectPackagePrefix() throws ClassNotFoundException {
        try {
            Class.forName("com.github.kwhat.jnativehook.GlobalScreen");
            return "com.github.kwhat.jnativehook";
        } catch (ClassNotFoundException ignored) {
            Class.forName("org.jnativehook.GlobalScreen");
            return "org.jnativehook";
        }
    }

    private Object handleKeyInvocation(Object proxy, Method method, Object[] args) throws Exception {
        Object objectResult = handleObjectMethod(proxy, method, args);
        if (objectResult != null) return objectResult;
        if (args == null || args.length == 0 || args[0] == null) return null;
        Object event = args[0];
        int keyCode = ((Number) event.getClass().getMethod("getKeyCode").invoke(event)).intValue();
        long generation = eventGeneration.get();
        switch (method.getName()) {
            case "nativeKeyPressed" -> submitEvent(generation, () -> tracker.recordKeyPressed(keyCode));
            case "nativeKeyReleased" -> submitEvent(generation, () -> tracker.recordKeyReleased(keyCode));
            default -> { }
        }
        return null;
    }

    private Object handleMouseInvocation(Object proxy, Method method, Object[] args) {
        Object objectResult = handleObjectMethod(proxy, method, args);
        if (objectResult != null) return objectResult;
        if ("nativeMousePressed".equals(method.getName())) {
            long generation = eventGeneration.get();
            submitEvent(generation, tracker::recordMouseClick);
        }
        return null;
    }

    private Object handleMotionInvocation(Object proxy, Method method, Object[] args) {
        Object objectResult = handleObjectMethod(proxy, method, args);
        if (objectResult != null) return objectResult;
        // Mouse distance is sampled independently at 250 Hz for smoother cross-platform updates.
        return null;
    }

    private void submitEvent(long generation, Runnable event) {
        if (closed || generation != eventGeneration.get()) return;
        try {
            eventExecutor.execute(() -> {
                if (!closed && generation == eventGeneration.get()) event.run();
            });
        } catch (RuntimeException ignored) {
            // Never allow a full/shutting-down queue to delay the native hook.
        }
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "InputActivityTrackerNativeListener";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            default -> null;
        };
    }

    private boolean nativeHookReportedRegistered() {
        if (!registered || globalScreenClass == null) return false;
        try {
            Object result = globalScreenClass.getMethod("isNativeHookRegistered").invoke(null);
            return result instanceof Boolean value ? value : registered;
        } catch (Throwable ignored) {
            // Older JNativeHook builds may not expose the health query. In that
            // case the local registration flag is still useful between resumes.
            return registered;
        }
    }

    private void unbindHook() {
        if (globalScreenClass != null) {
            if (keyListenerClass != null && keyListener != null) {
                try { invokeStatic(globalScreenClass, "removeNativeKeyListener", keyListenerClass, keyListener); }
                catch (Throwable ignored) { }
            }
            if (mouseListenerClass != null && mouseListener != null) {
                try { invokeStatic(globalScreenClass, "removeNativeMouseListener", mouseListenerClass, mouseListener); }
                catch (Throwable ignored) { }
            }
            if (motionListenerClass != null && motionListener != null) {
                try { invokeStatic(globalScreenClass, "removeNativeMouseMotionListener", motionListenerClass, motionListener); }
                catch (Throwable ignored) { }
            }
            if (registered) {
                try { invokeStatic(globalScreenClass, "unregisterNativeHook"); } catch (Throwable ignored) { }
            }
        }
        registered = false;
    }

    private static void invokeStatic(Class<?> type, String name) throws Exception {
        type.getMethod(name).invoke(null);
    }

    private static void invokeStatic(Class<?> type, String name, Class<?> argumentType, Object argument) throws Exception {
        type.getMethod(name, argumentType).invoke(null, argument);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String platformReadyMessage() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "Global mouse and keyboard monitoring active • macOS";
        if (os.contains("win")) return "Global mouse and keyboard monitoring active • Windows";
        return "Global mouse and keyboard monitoring active • Linux/X11";
    }

    private static String platformFailureMessage(Throwable error) {
        String detail = error == null || error.getMessage() == null
                ? "permission or native hook error" : error.getMessage();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "Keys need macOS Input Monitoring and Accessibility access • " + detail;
        }
        if (os.contains("linux")) {
            return "Global hook unavailable; Linux Wayland may require /dev/input permission • " + detail;
        }
        return "Global keyboard hook unavailable • " + detail;
    }

    @Override public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            eventGeneration.incrementAndGet();
            unbindHook();
        }
        eventExecutor.shutdownNow();
        eventExecutor.getQueue().clear();
    }
}
