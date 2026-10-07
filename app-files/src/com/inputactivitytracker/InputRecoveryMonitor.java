package com.inputactivitytracker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform suspend/resume and input-backend health monitor.
 *
 * There is no single Java callback that is reliable for laptop sleep/wake on
 * every supported desktop. A short scheduling gap is therefore used as a
 * portable resume signal. The callback also runs a lightweight health check
 * once per second so a native hook or evdev reader that was removed during
 * suspend can be restored without waiting for user interaction.
 */
final class InputRecoveryMonitor implements AutoCloseable {
    interface Listener {
        void onRecoveryCheck(boolean resumeDetected);
    }

    private static final long CHECK_INTERVAL_MS = 250L;
    private static final long HEALTH_INTERVAL_MS = 1000L;
    private static final long RESUME_GAP_MS = 1250L;

    private final Listener listener;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean callbackRunning = new AtomicBoolean(false);

    private volatile long lastWallMillis = System.currentTimeMillis();
    private volatile long lastNanoTime = System.nanoTime();
    private volatile long lastHealthMillis;

    InputRecoveryMonitor(Listener listener) {
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "input-activity-resume-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::checkSafely,
                CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void checkSafely() {
        long nowWall = System.currentTimeMillis();
        long nowNano = System.nanoTime();
        long wallGap = nowWall - lastWallMillis;
        long nanoGap = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nowNano - lastNanoTime));
        lastWallMillis = nowWall;
        lastNanoTime = nowNano;

        // Some operating systems stop the monotonic clock while suspended and
        // others continue it, so either clock can identify the wake-up gap.
        boolean resumed = wallGap > RESUME_GAP_MS || nanoGap > RESUME_GAP_MS;
        boolean healthDue = resumed || nowWall - lastHealthMillis >= HEALTH_INTERVAL_MS;
        if (!healthDue || !callbackRunning.compareAndSet(false, true)) return;

        lastHealthMillis = nowWall;
        try {
            listener.onRecoveryCheck(resumed);
        } catch (Throwable ignored) {
            // Recovery must never be able to interfere with desktop input.
        } finally {
            callbackRunning.set(false);
        }
    }

    @Override public void close() {
        scheduler.shutdownNow();
    }
}
