package com.inputactivitytracker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** A one-use local session handoff for the user's explicit portable-update shutdown. */
final class UpdateSession {
    static final String FILE_NAME = "update-session.json";
    record Saved(ActivityModels.Totals totals, Instant start, boolean running) {}
    private UpdateSession() {}

    static void save(Path directory, ActivityModels.Totals totals, Instant start,
                     boolean running, ZoneId zone) throws IOException {
        Files.createDirectories(directory);
        Map<String,Object> values = Map.of("schema", 1L, "app", "digital-marathon",
                "date", LocalDate.now(zone).toString(), "session_start", start.toString(),
                "running", running, "mouse", totals.mousePixels(), "keys", totals.keyPresses(),
                "clicks", totals.mouseClicks(), "active", totals.activeSeconds());
        byte[] bytes = JsonCodec.stringify(values).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(directory, ".update-session-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, directory.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, directory.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static Saved consume(Path directory, ZoneId zone) {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(file) > 16384) throw new IOException("Invalid update session");
            Map<String,Object> values = JsonCodec.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!Long.valueOf(1).equals(values.get("schema")) || !"digital-marathon".equals(values.get("app"))
                    || !LocalDate.now(zone).toString().equals(values.get("date"))
                    || !(values.get("running") instanceof Boolean running)) return null;
            Instant start = Instant.parse((String)values.get("session_start"));
            if (start.isAfter(Instant.now().plusSeconds(5))) return null;
            double mouse = nonnegativeDouble(values.get("mouse"));
            double active = nonnegativeDouble(values.get("active"));
            long keys = nonnegativeLong(values.get("keys"));
            long clicks = nonnegativeLong(values.get("clicks"));
            return new Saved(new ActivityModels.Totals(mouse, keys, clicks, active), start, running);
        } catch (Exception invalid) { return null; }
        finally { try { Files.deleteIfExists(file); } catch (IOException ignored) {} }
    }

    private static double nonnegativeDouble(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException();
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException();
        return result;
    }
    private static long nonnegativeLong(Object value) {
        if (!(value instanceof Long result) || result < 0) throw new IllegalArgumentException();
        return result;
    }
}
