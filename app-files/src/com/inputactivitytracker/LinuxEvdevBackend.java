package com.inputactivitytracker;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Passive Linux evdev reader used primarily for Wayland.
 *
 * The backend never grabs an input device (no EVIOCGRAB), so it cannot prevent
 * the desktop from receiving keyboard or mouse events. Readers are disposable:
 * after suspend/resume, or after a device node is recreated, they can be closed
 * and reopened immediately without restarting the application.
 */
final class LinuxEvdevBackend implements AutoCloseable {
    record StartResult(int openedDevices, boolean keyboardAvailable, boolean pointerAvailable) {}
    private record DeviceCapabilities(boolean keyboard, boolean pointer, boolean relativePointer, boolean absolutePointer) {}

    private static final class ReaderHandle {
        final Path device;
        final FileInputStream stream;
        final DeviceCapabilities capabilities;
        final long generation;
        volatile Thread thread;
        volatile boolean active = true;

        ReaderHandle(Path device, FileInputStream stream, DeviceCapabilities capabilities, long generation) {
            this.device = device;
            this.stream = stream;
            this.capabilities = capabilities;
            this.generation = generation;
        }
    }

    private static final int EVENT_SIZE_64 = 24;
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;
    private static final int EV_REL = 0x02;
    private static final int EV_ABS = 0x03;
    private static final int SYN_REPORT = 0;
    private static final int REL_X = 0;
    private static final int REL_Y = 1;
    private static final int ABS_X = 0;
    private static final int ABS_Y = 1;

    private final ActivityTracker tracker;
    private final List<ReaderHandle> readers = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean relativeMouseActivated;

    LinuxEvdevBackend(ActivityTracker tracker) {
        this.tracker = tracker;
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    static boolean isWaylandSession() {
        String wayland = System.getenv("WAYLAND_DISPLAY");
        String session = System.getenv("XDG_SESSION_TYPE");
        return (wayland != null && !wayland.isBlank()) || "wayland".equalsIgnoreCase(session);
    }

    synchronized StartResult start() {
        if (closed || !isLinux()) return new StartResult(0, false, false);
        if (running && !readers.isEmpty()) {
            return new StartResult(activeReaderCount(), isKeyboardAvailable(), isPointerAvailable());
        }
        return startReaders();
    }

    synchronized StartResult recoverAfterSystemResume() {
        if (closed || !isLinux()) return new StartResult(0, false, false);
        stopReaders();
        return startReaders();
    }

    boolean isKeyboardAvailable() {
        for (ReaderHandle reader : readers) {
            if (reader.active && reader.capabilities.keyboard()) return true;
        }
        return false;
    }

    boolean isPointerAvailable() {
        for (ReaderHandle reader : readers) {
            if (reader.active && reader.capabilities.pointer()) return true;
        }
        return false;
    }

    boolean hasActiveReaders() {
        return activeReaderCount() > 0;
    }

    private int activeReaderCount() {
        int count = 0;
        for (ReaderHandle reader : readers) if (reader.active) count++;
        return count;
    }

    private StartResult startReaders() {
        Path input = Paths.get("/dev/input");
        if (!Files.isDirectory(input)) {
            running = false;
            return new StartResult(0, false, false);
        }

        long currentGeneration = generation.incrementAndGet();
        running = true;
        int opened = 0;
        boolean keyboard = false;
        boolean pointer = false;

        try (DirectoryStream<Path> devices = Files.newDirectoryStream(input, "event*")) {
            List<Path> ordered = new ArrayList<>();
            for (Path device : devices) ordered.add(device);
            ordered.sort(Comparator.comparing(Path::toString));

            for (Path device : ordered) {
                DeviceCapabilities capabilities = capabilitiesFor(device);
                if (!capabilities.keyboard() && !capabilities.pointer()) continue;
                try {
                    FileInputStream stream = new FileInputStream(device.toFile());
                    ReaderHandle reader = new ReaderHandle(device, stream, capabilities, currentGeneration);
                    Thread thread = new Thread(
                            () -> readDevice(reader),
                            "input-activity-evdev-" + device.getFileName());
                    thread.setDaemon(true);
                    reader.thread = thread;
                    readers.add(reader);
                    thread.start();
                    opened++;
                    keyboard |= capabilities.keyboard();
                    pointer |= capabilities.pointer();
                } catch (IOException ignored) {
                    // Permission can differ per device; keep scanning all event nodes.
                }
            }
        } catch (IOException ignored) {
            running = false;
            return new StartResult(0, false, false);
        }

        if (opened == 0) running = false;
        if (opened > 0) {
            String coverage = keyboard && pointer ? "keyboard and pointer" : keyboard ? "keyboard" : "pointer";
            tracker.updateInputStatus("Linux Wayland/evdev active • " + coverage + " • " + opened + " devices", keyboard);
        }
        return new StartResult(opened, keyboard, pointer);
    }

    private void readDevice(ReaderHandle reader) {
        byte[] bytes = new byte[EVENT_SIZE_64];
        int relX = 0;
        int relY = 0;
        int absX = 0;
        int absY = 0;
        int lastAbsX = 0;
        int lastAbsY = 0;
        boolean haveAbsX = false;
        boolean haveAbsY = false;
        boolean haveLastAbs = false;

        try {
            while (running && reader.generation == generation.get()) {
                if (!readFully(reader.stream, bytes)) break;
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                int type = Short.toUnsignedInt(buffer.getShort(16));
                int code = Short.toUnsignedInt(buffer.getShort(18));
                int value = buffer.getInt(20);

                if (type == EV_KEY) {
                    if (reader.capabilities.pointer() && isMouseButton(code)) {
                        if (value == 1) tracker.recordMouseClick();
                    } else if (reader.capabilities.keyboard()) {
                        if (value == 1) tracker.recordKeyPressed(code);
                        else if (value == 0) tracker.recordKeyReleased(code);
                    }
                } else if (type == EV_REL && reader.capabilities.relativePointer()) {
                    if (code == REL_X) relX += value;
                    else if (code == REL_Y) relY += value;
                } else if (type == EV_ABS && reader.capabilities.absolutePointer()) {
                    if (code == ABS_X) { absX = value; haveAbsX = true; }
                    else if (code == ABS_Y) { absY = value; haveAbsY = true; }
                } else if (type == EV_SYN && code == SYN_REPORT) {
                    double dx = 0.0;
                    double dy = 0.0;
                    if (relX != 0 || relY != 0) {
                        dx = relX;
                        dy = relY;
                    } else if (haveAbsX && haveAbsY) {
                        if (haveLastAbs) {
                            dx = absX - lastAbsX;
                            dy = absY - lastAbsY;
                        }
                        lastAbsX = absX;
                        lastAbsY = absY;
                        haveLastAbs = true;
                    }
                    relX = 0;
                    relY = 0;
                    if (dx != 0.0 || dy != 0.0) {
                        activateRelativeMouse();
                        tracker.recordRelativeMouse(dx, dy);
                    }
                }
            }
        } catch (IOException ignored) {
            // Device removed, session suspended, or stream closed for recovery.
        } finally {
            reader.active = false;
            readers.remove(reader);
            try { reader.stream.close(); } catch (IOException ignored) { }
            restorePointerPollingIfNeeded();
        }
    }

    private void activateRelativeMouse() {
        if (!relativeMouseActivated) {
            synchronized (this) {
                if (!relativeMouseActivated) {
                    relativeMouseActivated = true;
                    tracker.useRelativeMouseBackend(true);
                }
            }
        }
    }

    private void restorePointerPollingIfNeeded() {
        if (!relativeMouseActivated || isPointerAvailable()) return;
        synchronized (this) {
            if (relativeMouseActivated && !isPointerAvailable()) {
                relativeMouseActivated = false;
                tracker.useRelativeMouseBackend(false);
            }
        }
    }

    private void stopReaders() {
        running = false;
        generation.incrementAndGet();
        List<ReaderHandle> snapshot = new ArrayList<>(readers);
        readers.clear();
        for (ReaderHandle reader : snapshot) {
            reader.active = false;
            try { reader.stream.close(); } catch (IOException ignored) { }
            Thread thread = reader.thread;
            if (thread != null) thread.interrupt();
        }
        if (relativeMouseActivated) {
            relativeMouseActivated = false;
            tracker.useRelativeMouseBackend(false);
        }
    }

    private static DeviceCapabilities capabilitiesFor(Path eventDevice) {
        String eventName = eventDevice.getFileName().toString();
        Path base = Paths.get("/sys/class/input", eventName, "device", "capabilities");
        Path key = base.resolve("key");
        Path rel = base.resolve("rel");
        Path abs = base.resolve("abs");

        boolean keyboard = hasBit(key, 30) || hasBit(key, 57) || hasBit(key, 28) || hasBit(key, 1);
        boolean relative = hasBit(rel, REL_X) && hasBit(rel, REL_Y);
        boolean absolute = hasBit(abs, ABS_X) && hasBit(abs, ABS_Y);
        boolean mouseButtons = hasBit(key, 0x110) || hasBit(key, 0x111) || hasBit(key, 0x112);
        boolean pointer = relative || absolute || mouseButtons;
        return new DeviceCapabilities(keyboard, pointer, relative, absolute);
    }

    private static boolean hasBit(Path capabilityFile, int bit) {
        try {
            String content = Files.readString(capabilityFile, StandardCharsets.US_ASCII).trim();
            if (content.isEmpty()) return false;
            String[] words = content.split("\\s+");
            int wordIndex = bit / 64;
            int arrayIndex = words.length - 1 - wordIndex;
            if (arrayIndex < 0 || arrayIndex >= words.length) return false;
            BigInteger word = new BigInteger(words[arrayIndex], 16);
            return word.testBit(bit % 64);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return true;
    }

    private static boolean isMouseButton(int code) {
        return code >= 0x110 && code <= 0x11F;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        stopReaders();
    }
}
