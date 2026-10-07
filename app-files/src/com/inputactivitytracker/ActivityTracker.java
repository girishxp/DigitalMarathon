package com.inputactivitytracker;

import com.inputactivitytracker.ActivityModels.Snapshot;
import com.inputactivitytracker.ActivityModels.Totals;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.time.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

final class ActivityTracker implements AutoCloseable {
    private final Object lock = new Object();
    private final HistoryStore store;
    private final ZoneId zone;
    private final ScheduledExecutorService scheduler;
    private final Set<Integer> pressedKeys = new HashSet<>();

    private boolean running = true;
    private boolean pointerPollingEnabled = true;
    private Point lastPointer;
    private long lastPointerNanos;
    private Instant sessionStart = Instant.now();
    private LocalDate sessionDate;

    private double sessionMouse;
    private long sessionKeys;
    private long sessionClicks;
    private double sessionActive;

    private double pendingMouse;
    private long pendingKeys;
    private long pendingClicks;
    private double pendingActive;
    private long lastActiveNanos;
    private long lastFlushWindow = -1L;

    private String inputStatus = "Starting global input monitor…";
    private boolean globalKeysAvailable;

    ActivityTracker(HistoryStore store, ZoneId zone) {
        this.store = store;
        this.zone = zone;
        this.sessionDate = LocalDate.now(zone);
        this.lastActiveNanos = System.nanoTime();
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "input-activity-tracker-worker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::samplePointerSafely, 0L, 4L, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::tickSafely, 250L, 250L, TimeUnit.MILLISECONDS);
    }

    void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            lastActiveNanos = System.nanoTime();
            lastPointer = null;
        }
    }

    void stop() {
        synchronized (lock) {
            accrueActiveLocked(System.nanoTime());
            running = false;
            lastPointer = null;
            pressedKeys.clear();
        }
        flushPendingSafely();
    }

    void resetSession() {
        synchronized (lock) {
            accrueActiveLocked(System.nanoTime());
            sessionMouse = 0.0;
            sessionKeys = 0L;
            sessionClicks = 0L;
            sessionActive = 0.0;
            sessionStart = Instant.now();
            sessionDate = LocalDate.now(zone);
            lastPointer = null;
            pressedKeys.clear();
        }
    }

    void recordKeyPressed(int keyCode) {
        synchronized (lock) {
            rolloverIfNeededLocked(Instant.now());
            if (!running) return;
            if (pressedKeys.add(keyCode)) {
                sessionKeys++;
                pendingKeys++;
            }
        }
    }

    void recordKeyReleased(int keyCode) {
        synchronized (lock) {
            pressedKeys.remove(keyCode);
        }
    }

    /**
     * Records one already de-duplicated physical key stroke from the native
     * macOS backend. Quartz key-up delivery can be interrupted by another app,
     * so this path deliberately does not depend on a Java-side pressed-key set.
     */
    void recordNativeKeyStroke() {
        synchronized (lock) {
            rolloverIfNeededLocked(Instant.now());
            if (!running) return;
            sessionKeys++;
            pendingKeys++;
        }
    }

    void recordMouseClick() {
        synchronized (lock) {
            rolloverIfNeededLocked(Instant.now());
            if (!running) return;
            sessionClicks++;
            pendingClicks++;
        }
    }

    void recordRelativeMouse(double dx, double dy) {
        double distance = Math.hypot(dx, dy);
        if (!Double.isFinite(distance) || distance <= 0.0 || distance > 10000.0) return;
        synchronized (lock) {
            rolloverIfNeededLocked(Instant.now());
            if (!running) return;
            sessionMouse += distance;
            pendingMouse += distance;
        }
    }

    void useRelativeMouseBackend(boolean enabled) {
        synchronized (lock) {
            pointerPollingEnabled = !enabled;
            lastPointer = null;
        }
    }

    void updateInputStatus(String status, boolean keysAvailable) {
        synchronized (lock) {
            inputStatus = status == null ? "Input monitor status unavailable" : status;
            globalKeysAvailable = keysAvailable;
        }
    }

    /**
     * Reset transient input state after the operating system wakes from sleep.
     * The counters and session remain intact; only potentially stale held-key
     * and pointer state is cleared so the first real keyboard or mouse event
     * after resume is accepted immediately on every supported platform.
     */
    void prepareForSystemResume() {
        synchronized (lock) {
            pressedKeys.clear();
            lastPointer = null;
            lastPointerNanos = 0L;
            lastActiveNanos = System.nanoTime();
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            accrueActiveLocked(System.nanoTime());
            rolloverIfNeededLocked(Instant.now());
            Totals totals = new Totals(sessionMouse, sessionKeys, sessionClicks, sessionActive);
            return new Snapshot(totals, running, sessionStart, inputStatus, globalKeysAvailable);
        }
    }

    private void samplePointerSafely() {
        try {
            samplePointer();
        } catch (Throwable ignored) {
            // Pointer polling is a fallback. Native input monitoring can still run.
        }
    }

    private void samplePointer() {
        boolean poll;
        boolean currentlyRunning;
        synchronized (lock) {
            poll = pointerPollingEnabled;
            currentlyRunning = running;
        }
        if (!poll || !currentlyRunning) return;

        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) return;
        Point point = info.getLocation();
        long now = System.nanoTime();
        synchronized (lock) {
            rolloverIfNeededLocked(Instant.now());
            if (!running || !pointerPollingEnabled) {
                lastPointer = null;
                return;
            }
            if (lastPointer != null) {
                double distance = lastPointer.distance(point);
                long elapsed = now - lastPointerNanos;
                // Ignore impossible teleports caused by display reconfiguration or wake-from-sleep.
                if (elapsed > 0L && elapsed < TimeUnit.SECONDS.toNanos(2) && distance > 0.0 && distance < 10000.0) {
                    sessionMouse += distance;
                    pendingMouse += distance;
                }
            }
            lastPointer = point;
            lastPointerNanos = now;
        }
    }

    private void tickSafely() {
        try {
            Totals delta;
            Instant now = Instant.now();
            synchronized (lock) {
                rolloverIfNeededLocked(now);
                accrueActiveLocked(System.nanoTime());
                delta = drainPendingLocked();
            }
            store.add(now, delta);
            long flushWindow = Math.floorDiv(now.getEpochSecond(), 5L);
            if (flushWindow != lastFlushWindow) {
                lastFlushWindow = flushWindow;
                store.flush();
            }
        } catch (Throwable error) {
            updateInputStatus("History write problem: " + error.getMessage(), globalKeysAvailable);
        }
    }

    private void rolloverIfNeededLocked(Instant now) {
        LocalDate current = now.atZone(zone).toLocalDate();
        if (current.equals(sessionDate)) return;

        // Attribute any not-yet-written activity to the completed local day.
        Totals completedDayPending = drainPendingLocked();
        Instant completedDayTimestamp = sessionDate.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
        store.add(completedDayTimestamp, completedDayPending);

        sessionMouse = 0.0;
        sessionKeys = 0L;
        sessionClicks = 0L;
        sessionActive = 0.0;
        sessionStart = current.atStartOfDay(zone).toInstant();
        sessionDate = current;
        lastPointer = null;
        pressedKeys.clear();
        lastActiveNanos = System.nanoTime();
    }

    private void accrueActiveLocked(long nowNanos) {
        if (lastActiveNanos == 0L) {
            lastActiveNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - lastActiveNanos;
        lastActiveNanos = nowNanos;
        if (!running || elapsed <= 0L || elapsed > TimeUnit.SECONDS.toNanos(30)) return;
        double seconds = elapsed / 1_000_000_000.0;
        sessionActive += seconds;
        pendingActive += seconds;
    }

    private Totals drainPendingLocked() {
        Totals delta = new Totals(pendingMouse, pendingKeys, pendingClicks, pendingActive);
        pendingMouse = 0.0;
        pendingKeys = 0L;
        pendingClicks = 0L;
        pendingActive = 0.0;
        return delta;
    }

    private void flushPendingSafely() {
        try {
            Totals delta;
            synchronized (lock) {
                delta = drainPendingLocked();
            }
            store.add(Instant.now(), delta);
            store.flush();
        } catch (Exception ignored) {
            // Best effort during shutdown.
        }
    }

    @Override public void close() {
        flushPendingSafely();
        scheduler.shutdownNow();
        try { store.flush(); } catch (Exception ignored) {}
    }

}
