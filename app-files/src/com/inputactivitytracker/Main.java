package com.inputactivitytracker;

import javax.swing.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    private static final Object backendRecoveryLock = new Object();
    private static final long EVDEV_RETRY_INTERVAL_MS = 5000L;

    private static NativeHookBridge nativeHook;
    private static MacNativeInputBackend macNativeInput;
    private static LinuxEvdevBackend evdev;
    private static ActivityTracker tracker;
    private static AppServices appServices;
    private static ScheduledExecutorService inputRetryScheduler;
    private static InputRecoveryMonitor inputRecoveryMonitor;
    private static String backendOs = "";
    private static boolean backendWayland;
    private static long lastEvdevRetryMillis;

    private Main() {}

    public static void main(String[] args) {
        System.setProperty("java.awt.application.name", "Digital Marathon");
        System.setProperty("apple.awt.application.name", "Digital Marathon");
        System.setProperty("apple.awt.application.appearance", "system");
        LinuxCapturePrivacySupport.configureStableWindowClass();

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            ToolTipManager toolTips = ToolTipManager.sharedInstance();
            toolTips.setInitialDelay(550);
            toolTips.setReshowDelay(150);
            toolTips.setDismissDelay(5000);
            toolTips.setLightWeightPopupEnabled(false);
            TranslucentToolTipPopupFactory.install();
            UIManager.put("ToolTipUI", AppleToolTipUI.class.getName());
            UIManager.put("ToolTip.background", new java.awt.Color(250, 250, 252));
            UIManager.put("ToolTip.foreground", new java.awt.Color(29, 29, 31));
            UIManager.put("ToolTip.outline", new java.awt.Color(210, 210, 215));
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 12);
            UIManager.put("TextComponent.arc", 10);
        } catch (Exception ignored) {}

        try {
            Path dataDirectory = AppPaths.dataDirectory();
            HistoryStore store = new HistoryStore(dataDirectory);
            tracker = new ActivityTracker(store, ZoneId.systemDefault());
            tracker.restoreUpdateSession(dataDirectory);
            try { appServices = new AppServices(dataDirectory); }
            catch (RuntimeException serviceError) {
                // Optional online services must never prevent local tracking.
                System.err.println("Online update/analytics services are unavailable.");
            }

            Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdown, "input-activity-shutdown"));

            SwingUtilities.invokeLater(() -> {
                TrackerWindow window = new TrackerWindow(tracker, store, ZoneId.systemDefault());
                window.attachServices(appServices);
                window.setVisible(true);
                Thread inputThread = new Thread(Main::startInputBackends, "input-activity-input-backend");
                inputThread.setDaemon(true);
                inputThread.start();
                if (appServices != null) {
                    try { appServices.start(window); }
                    catch (RuntimeException serviceError) {
                        System.err.println("Optional online services could not start; local tracking continues.");
                    }
                }
            });
        } catch (Exception error) {
            error.printStackTrace();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Digital Marathon could not start:\n" + error.getMessage(),
                    "Startup failed", JOptionPane.ERROR_MESSAGE));
        }
    }

    private static void startInputBackends() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        backendOs = os;
        backendWayland = os.contains("linux") && LinuxEvdevBackend.isWaylandSession();

        synchronized (backendRecoveryLock) {
            if (os.contains("mac")) {
                startMacNativeBackendWithRetry();
            } else if (backendWayland) {
                startLinuxWaylandBackends();
            } else {
                boolean hookStarted = ensureNativeHook(false);
                if (!hookStarted && os.contains("linux")) {
                    LinuxEvdevBackend.StartResult result = ensureEvdev(true);
                    if (!result.keyboardAvailable()) {
                        tracker.updateInputStatus("Linux global input unavailable • see LINUX-WAYLAND.md", false);
                    }
                }
            }
        }
        startCrossPlatformRecoveryMonitor();
    }

    private static void startLinuxWaylandBackends() {
        LinuxEvdevBackend.StartResult result = ensureEvdev(true);
        if (!result.keyboardAvailable()) {
            tracker.updateInputStatus("Wayland detected • /dev/input keyboard permission is required", false);
            ensureNativeHook(false);
        }
    }

    private static boolean ensureNativeHook(boolean forceRestart) {
        if (nativeHook == null) nativeHook = new NativeHookBridge(tracker);
        if (forceRestart) return nativeHook.recoverAfterSystemResume();
        if (nativeHook.isHealthy()) return true;
        return nativeHook.start();
    }

    private static void closeNativeHook() {
        if (nativeHook == null) return;
        try { nativeHook.close(); } catch (Exception ignored) {}
        nativeHook = null;
    }

    private static LinuxEvdevBackend.StartResult ensureEvdev(boolean forceRestart) {
        long now = System.currentTimeMillis();
        if (evdev == null) {
            evdev = new LinuxEvdevBackend(tracker);
            lastEvdevRetryMillis = now;
            return evdev.start();
        }
        if (forceRestart || !evdev.hasActiveReaders()) {
            lastEvdevRetryMillis = now;
            return evdev.recoverAfterSystemResume();
        }
        if (!evdev.isKeyboardAvailable() && now - lastEvdevRetryMillis >= EVDEV_RETRY_INTERVAL_MS) {
            lastEvdevRetryMillis = now;
            return evdev.recoverAfterSystemResume();
        }
        return new LinuxEvdevBackend.StartResult(
                evdev.hasActiveReaders() ? 1 : 0,
                evdev.isKeyboardAvailable(), evdev.isPointerAvailable());
    }

    private static void closeEvdev() {
        if (evdev == null) return;
        try { evdev.close(); } catch (Exception ignored) {}
        evdev = null;
    }

    private static void startCrossPlatformRecoveryMonitor() {
        synchronized (backendRecoveryLock) {
            if (inputRecoveryMonitor != null) return;
            inputRecoveryMonitor = new InputRecoveryMonitor(Main::recoverInputBackends);
            inputRecoveryMonitor.start();
        }
    }

    private static void recoverInputBackends(boolean resumeDetected) {
        synchronized (backendRecoveryLock) {
            if (tracker == null) return;

            if (resumeDetected) {
                // Clear held-key and pointer state on every operating system.
                // This prevents a key-up lost during sleep from suppressing the
                // first real key press after the lid/system wakes.
                tracker.prepareForSystemResume();
            }

            String os = backendOs;
            if (os.contains("mac")) {
                MacNativeInputBackend backend = macNativeInput;
                if (backend != null && !backend.isReady()) backend.start();
                return;
            }

            if (os.contains("win")) {
                ensureNativeHook(resumeDetected);
                return;
            }

            if (!os.contains("linux")) {
                ensureNativeHook(resumeDetected);
                return;
            }

            if (backendWayland) {
                LinuxEvdevBackend.StartResult result = ensureEvdev(resumeDetected);
                if (result.keyboardAvailable()) {
                    // evdev is the preferred Wayland source. Do not leave a
                    // fallback native hook running or input could be counted twice.
                    closeNativeHook();
                } else {
                    ensureNativeHook(resumeDetected);
                }
                return;
            }

            // Linux/X11 normally uses JNativeHook. If suspend removed the hook,
            // re-register it. evdev remains a fallback only when the hook fails.
            boolean hookHealthy = ensureNativeHook(resumeDetected);
            if (hookHealthy) {
                closeEvdev();
            } else {
                ensureEvdev(resumeDetected);
            }
        }
    }

    private static void startMacNativeBackendWithRetry() {
        macNativeInput = new MacNativeInputBackend(tracker);
        boolean started = macNativeInput.start();
        if (!started) {
            tracker.updateInputStatus(
                    "Waiting for macOS Input Monitoring permission • the tracker will retry automatically", false);
        }

        // macOS also has an AppKit wake callback in the native library. Keep a
        // sub-second permission/backend retry in addition to the generic monitor.
        inputRetryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "input-activity-macos-permission-retry");
            thread.setDaemon(true);
            return thread;
        });
        inputRetryScheduler.scheduleWithFixedDelay(() -> {
            MacNativeInputBackend backend = macNativeInput;
            if (backend == null || backend.isReady()) return;
            backend.start();
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    private static synchronized void shutdown() {
        if (appServices != null) {
            try { appServices.close(); } catch (Exception ignored) {}
            appServices = null;
        }
        synchronized (backendRecoveryLock) {
            if (inputRecoveryMonitor != null) {
                try { inputRecoveryMonitor.close(); } catch (Exception ignored) {}
                inputRecoveryMonitor = null;
            }
            if (inputRetryScheduler != null) {
                inputRetryScheduler.shutdownNow();
                inputRetryScheduler = null;
            }
            if (macNativeInput != null) {
                try { macNativeInput.close(); } catch (Exception ignored) {}
                macNativeInput = null;
            }
            closeNativeHook();
            closeEvdev();
            if (tracker != null) {
                try { tracker.close(); } catch (Exception ignored) {}
                tracker = null;
            }
        }
    }
}
