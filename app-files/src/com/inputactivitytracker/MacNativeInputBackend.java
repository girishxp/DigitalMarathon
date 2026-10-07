package com.inputactivitytracker;

import java.awt.Window;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native macOS Quartz input backend.
 *
 * This uses a passive HID/session Quartz event tap plus a wake-safe 60 Hz
 * physical-key-state fallback sampler. It remains active
 * while the Swing window is unfocused. Only numeric press, release, and click
 * signals are sent to Java; typed text and key identities are never stored.
 */
final class MacNativeInputBackend implements AutoCloseable {
    private static final String LIBRARY_NAME = "InputActivityMacHook";
    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean(false);

    private final ActivityTracker tracker;
    private final Object lifecycleLock = new Object();

    private volatile Thread nativeThread;
    private volatile CountDownLatch startupLatch;
    private volatile boolean ready;
    private volatile boolean closed;

    MacNativeInputBackend(ActivityTracker tracker) {
        this.tracker = tracker;
    }

    boolean start() {
        synchronized (lifecycleLock) {
            if (closed) return false;
            if (nativeThread != null && nativeThread.isAlive()) return ready;

            try {
                loadLibraryOnce();
            } catch (Throwable error) {
                tracker.updateInputStatus(
                        "macOS native input component could not load • " + safeMessage(error), false);
                return false;
            }

            ready = false;
            startupLatch = new CountDownLatch(1);
            Thread thread = new Thread(() -> {
                try {
                    nativeRun();
                } catch (Throwable error) {
                    onNativeReady(false, 0, "Native input listener stopped: " + safeMessage(error));
                } finally {
                    boolean unexpectedStop;
                    synchronized (lifecycleLock) {
                        unexpectedStop = !closed && ready;
                        ready = false;
                        if (nativeThread == Thread.currentThread()) nativeThread = null;
                    }
                    if (unexpectedStop) {
                        tracker.updateInputStatus(
                                "macOS input listener stopped unexpectedly • retrying automatically", false);
                    }
                }
            }, "input-activity-macos-quartz");
            thread.setDaemon(true);
            nativeThread = thread;
            thread.start();
        }

        try {
            CountDownLatch latch = startupLatch;
            if (latch != null && !latch.await(5, TimeUnit.SECONDS)) {
                tracker.updateInputStatus("macOS input listener is taking longer than expected to start", false);
                return false;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ready;
    }

    private static void loadLibraryOnce() {
        if (LIBRARY_LOADED.compareAndSet(false, true)) {
            try {
                System.loadLibrary(LIBRARY_NAME);
            } catch (Throwable error) {
                LIBRARY_LOADED.set(false);
                throw error;
            }
        }
    }

    @SuppressWarnings("unused") // Called from JNI.
    private void onNativeReady(boolean success, int permissionState, String detail) {
        ready = success;
        if (success) {
            String mode = detail == null || detail.isBlank() ? "" : " • " + detail;
            tracker.updateInputStatus(
                    "Global keyboard and mouse monitoring active • macOS native Quartz" + mode, true);
        } else {
            String suffix = detail == null || detail.isBlank() ? "" : " • " + detail;
            if (permissionState == 1) {
                tracker.updateInputStatus(
                        "Keys need macOS Input Monitoring access for this app build" + suffix, false);
            } else if (permissionState == 2) {
                tracker.updateInputStatus(
                        "macOS granted input access, but the native event listener could not start" + suffix, false);
            } else {
                tracker.updateInputStatus("macOS native input listener unavailable" + suffix, false);
            }
        }
        CountDownLatch latch = startupLatch;
        if (latch != null) latch.countDown();
    }

    @SuppressWarnings("unused") // Called from JNI.
    private void onNativeKeyPressed(int keyCode) {
        tracker.recordKeyPressed(keyCode);
    }

    @SuppressWarnings("unused") // Called from JNI.
    private void onNativeKeyReleased(int keyCode) {
        tracker.recordKeyReleased(keyCode);
    }

    @SuppressWarnings("unused") // Called from JNI.
    private void onNativeMouseClick() {
        tracker.recordMouseClick();
    }

    @SuppressWarnings("unused") // Called from JNI after macOS wake notification.
    private void onNativeSystemWake() {
        tracker.prepareForSystemResume();
    }

    boolean isReady() {
        return ready;
    }

    static boolean applyNativeWindowOpacity(Window window, float opacity) {
        try {
            loadLibraryOnce();
            return nativeSetWindowOpacity(window, Math.max(0.25f, Math.min(1.0f, opacity)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Leave movement to WindowDragSupport, including controls inside the native title bar. */
    static boolean configureNativeWindowDragging(Window window) {
        try {
            loadLibraryOnce();
            return nativeConfigureWindowDragging(window);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Match native title-bar controls to the application's selected theme. */
    static boolean applyNativeWindowAppearance(Window window, boolean dark) {
        try {
            loadLibraryOnce();
            return nativeSetWindowAppearance(window, dark);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Read-only diagnostics (call off the EDT): movable=1, draggable background=2, protected identity=4. */
    static int nativeWindowDraggingState(Window window) {
        try {
            loadLibraryOnce();
            return nativeGetWindowDraggingState(window);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean applyNativeMiniWindowChrome(boolean miniMode) {
        try {
            loadLibraryOnce();
            return nativeSetMiniWindowChrome(miniMode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean applyNativeWindowCapturePrivacy(boolean enabled) {
        try {
            loadLibraryOnce();
            return nativeSetWindowCapturePrivacy(enabled);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            ready = false;
            try {
                nativeStop();
            } catch (Throwable ignored) {
                // Best effort during shutdown.
            }
            Thread thread = nativeThread;
            if (thread != null) thread.interrupt();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private native void nativeRun();
    private native void nativeStop();
    private static native boolean nativeSetWindowOpacity(Window window, float opacity);
    private static native boolean nativeConfigureWindowDragging(Window window);
    private static native boolean nativeSetWindowAppearance(Window window, boolean dark);
    private static native int nativeGetWindowDraggingState(Window window);
    private static native boolean nativeSetMiniWindowChrome(boolean miniMode);
    private static native boolean nativeSetWindowCapturePrivacy(boolean enabled);
}
