package com.inputactivitytracker;

import com.inputactivitytracker.ActivityModels.Group;
import com.inputactivitytracker.ActivityModels.Totals;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class HistoryStore {
    private static final int MAGIC = 0x49415432; // IAT2
    private static final int FORMAT_VERSION = 1;
    private static final int RECORD_BYTES = Long.BYTES + Double.BYTES + Long.BYTES + Long.BYTES + Double.BYTES;
    private static final long COMPACTION_JOURNAL_RECORDS = 20_000L;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE, d MMM yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("d MMM, HH:mm");
    private static final DateTimeFormatter SHORT_HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter RANGE_DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter SHORT_WEEK = DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM yy");

    private static final class MutableBucket {
        double mouse;
        long keys;
        long clicks;
        double active;

        MutableBucket() {}
        MutableBucket(double mouse, long keys, long clicks, double active) {
            this.mouse = mouse;
            this.keys = keys;
            this.clicks = clicks;
            this.active = active;
        }
        void add(Totals totals) {
            mouse += totals.mousePixels();
            keys += totals.keyPresses();
            clicks += totals.mouseClicks();
            active += totals.activeSeconds();
        }
        Totals totals() { return new Totals(mouse, keys, clicks, active); }
    }

    private final Path directory;
    private final Path dataFile;
    private final Path legacyCsvFile;
    private final TreeMap<Long, MutableBucket> buckets = new TreeMap<>();
    private final TreeMap<Long, MutableBucket> pendingDeltas = new TreeMap<>();
    private long journalRecordsSinceCompaction;
    private LocalDate lastCompactionDay = LocalDate.now();

    HistoryStore(Path directory) throws IOException {
        this.directory = directory;
        this.dataFile = directory.resolve("activity-buckets.dat");
        this.legacyCsvFile = directory.resolve("activity-buckets.csv");
        Files.createDirectories(directory);

        if (Files.isRegularFile(dataFile)) {
            loadBinary();
        } else if (Files.isRegularFile(legacyCsvFile)) {
            loadLegacyCsv();
            compact();
        } else if (migrateLegacySqlite()) {
            compact();
        }
    }

    synchronized void add(Instant at, Totals delta) {
        if (delta == null || isZero(delta)) return;
        long minute = Math.floorDiv(at.getEpochSecond(), 60L);
        buckets.computeIfAbsent(minute, ignored -> new MutableBucket()).add(delta);
        pendingDeltas.computeIfAbsent(minute, ignored -> new MutableBucket()).add(delta);
    }

    synchronized Totals totals(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) return Totals.zero();
        long startMinute = Math.floorDiv(start.getEpochSecond(), 60L);
        long endMinute = Math.floorDiv(end.minusNanos(1).getEpochSecond(), 60L);
        double mouse = 0.0;
        long keys = 0L;
        long clicks = 0L;
        double active = 0.0;
        for (MutableBucket bucket : buckets.subMap(startMinute, true, endMinute, true).values()) {
            mouse += bucket.mouse;
            keys += bucket.keys;
            clicks += bucket.clicks;
            active += bucket.active;
        }
        return new Totals(mouse, keys, clicks, active);
    }

    synchronized List<Group> groups(Instant start, Instant end, ZoneId zone) {
        if (start == null || end == null || !end.isAfter(start)) return List.of();
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofHours(18)) <= 0) {
            return groupedFixed(start, end, zone, Duration.ofMinutes(duration.toHours() <= 2 ? 10 : 60));
        }
        long days = Math.max(1L, duration.toDays());
        if (days <= 45) return groupedDaily(start, end, zone);
        if (days <= 210) return groupedWeekly(start, end, zone);
        if (days <= 900) return groupedMonthly(start, end, zone);
        return groupedQuarterly(start, end, zone);
    }

    synchronized List<Group> dailyGroups(Instant start, Instant end, ZoneId zone) {
        return groupedDaily(start, end, zone);
    }

    private List<Group> groupedFixed(Instant start, Instant end, ZoneId zone, Duration step) {
        List<Group> groups = new ArrayList<>();
        long stepSeconds = step.toSeconds();
        long aligned = Math.floorDiv(start.getEpochSecond(), stepSeconds) * stepSeconds;
        Instant cursor = Instant.ofEpochSecond(aligned);
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(step);
            Instant groupStart = cursor.isBefore(start) ? start : cursor;
            Instant groupEnd = next.isAfter(end) ? end : next;
            Totals totals = totals(groupStart, groupEnd);
            ZonedDateTime local = cursor.atZone(zone);
            groups.add(new Group(HOUR_LABEL.format(local), SHORT_HOUR.format(local), groupStart, groupEnd, totals));
            cursor = next;
        }
        return groups;
    }

    private List<Group> groupedDaily(Instant start, Instant end, ZoneId zone) {
        List<Group> groups = new ArrayList<>();
        LocalDate first = start.atZone(zone).toLocalDate();
        LocalDate last = end.minusNanos(1).atZone(zone).toLocalDate();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            Instant queryStart = dayStart.isBefore(start) ? start : dayStart;
            Instant queryEnd = dayEnd.isAfter(end) ? end : dayEnd;
            Totals totals = totals(queryStart, queryEnd);
            groups.add(new Group(DAY_LABEL.format(date), SHORT_DAY.format(date), queryStart, queryEnd, totals));
        }
        return groups;
    }

    private List<Group> groupedWeekly(Instant start, Instant end, ZoneId zone) {
        List<Group> groups = new ArrayList<>();
        LocalDate cursor = start.atZone(zone).toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (cursor.atStartOfDay(zone).toInstant().isBefore(end)) {
            LocalDate nextDate = cursor.plusWeeks(1);
            Instant periodStart = cursor.atStartOfDay(zone).toInstant();
            Instant periodEnd = nextDate.atStartOfDay(zone).toInstant();
            Instant queryStart = periodStart.isBefore(start) ? start : periodStart;
            Instant queryEnd = periodEnd.isAfter(end) ? end : periodEnd;
            LocalDate displayEnd = nextDate.minusDays(1);
            String label = RANGE_DAY.format(cursor) + " - " + RANGE_DAY.format(displayEnd);
            groups.add(new Group(label, SHORT_WEEK.format(cursor), queryStart, queryEnd, totals(queryStart, queryEnd)));
            cursor = nextDate;
        }
        return groups;
    }

    private List<Group> groupedMonthly(Instant start, Instant end, ZoneId zone) {
        List<Group> groups = new ArrayList<>();
        LocalDate cursor = start.atZone(zone).toLocalDate().withDayOfMonth(1);
        while (cursor.atStartOfDay(zone).toInstant().isBefore(end)) {
            LocalDate nextDate = cursor.plusMonths(1);
            Instant periodStart = cursor.atStartOfDay(zone).toInstant();
            Instant periodEnd = nextDate.atStartOfDay(zone).toInstant();
            Instant queryStart = periodStart.isBefore(start) ? start : periodStart;
            Instant queryEnd = periodEnd.isAfter(end) ? end : periodEnd;
            groups.add(new Group(MONTH_LABEL.format(cursor), SHORT_MONTH.format(cursor), queryStart, queryEnd,
                    totals(queryStart, queryEnd)));
            cursor = nextDate;
        }
        return groups;
    }

    private List<Group> groupedQuarterly(Instant start, Instant end, ZoneId zone) {
        List<Group> groups = new ArrayList<>();
        LocalDate date = start.atZone(zone).toLocalDate();
        int quarterStartMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate cursor = LocalDate.of(date.getYear(), quarterStartMonth, 1);
        while (cursor.atStartOfDay(zone).toInstant().isBefore(end)) {
            LocalDate nextDate = cursor.plusMonths(3);
            Instant periodStart = cursor.atStartOfDay(zone).toInstant();
            Instant periodEnd = nextDate.atStartOfDay(zone).toInstant();
            Instant queryStart = periodStart.isBefore(start) ? start : periodStart;
            Instant queryEnd = periodEnd.isAfter(end) ? end : periodEnd;
            int quarter = ((cursor.getMonthValue() - 1) / 3) + 1;
            String label = "Q" + quarter + " " + cursor.getYear();
            String shortLabel = "Q" + quarter + " " + String.format(Locale.ROOT, "%02d", cursor.getYear() % 100);
            groups.add(new Group(label, shortLabel, queryStart, queryEnd, totals(queryStart, queryEnd)));
            cursor = nextDate;
        }
        return groups;
    }

    synchronized void clear() throws IOException {
        buckets.clear();
        pendingDeltas.clear();
        compact();
    }

    synchronized void flush() throws IOException {
        if (!pendingDeltas.isEmpty()) {
            ensureBinaryHeader();
            try (FileOutputStream file = new FileOutputStream(dataFile.toFile(), true);
                 BufferedOutputStream buffered = new BufferedOutputStream(file);
                 DataOutputStream output = new DataOutputStream(buffered)) {
                for (Map.Entry<Long, MutableBucket> entry : pendingDeltas.entrySet()) {
                    writeRecord(output, entry.getKey(), entry.getValue());
                    journalRecordsSinceCompaction++;
                }
                output.flush();
                file.getFD().sync();
            }
            pendingDeltas.clear();
        }

        LocalDate today = LocalDate.now();
        if (!today.equals(lastCompactionDay) || journalRecordsSinceCompaction >= COMPACTION_JOURNAL_RECORDS) {
            compact();
        }
    }

    synchronized void export(Path file, Instant start, Instant end, ZoneId zone, UnitConverter.Unit unit, double ppi) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        List<Group> rows = dailyGroups(start, end, zone);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write("date,mouse_distance,unit,key_presses,mouse_clicks,active_seconds");
            writer.newLine();
            for (Group group : rows) {
                Totals t = group.totals();
                double converted = UnitConverter.convert(t.mousePixels(), unit, ppi);
                writer.write(csv(group.label()) + "," + converted + "," + unit.symbol() + "," + t.keyPresses() + "," + t.mouseClicks() + "," + t.activeSeconds());
                writer.newLine();
            }
        }
    }

    private void ensureBinaryHeader() throws IOException {
        if (Files.isRegularFile(dataFile) && Files.size(dataFile) >= Integer.BYTES * 2L) return;
        try (FileOutputStream file = new FileOutputStream(dataFile.toFile(), false);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.flush();
            file.getFD().sync();
        }
    }

    private void compact() throws IOException {
        Files.createDirectories(directory);
        Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try (FileOutputStream file = new FileOutputStream(temp.toFile(), false);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            for (Map.Entry<Long, MutableBucket> entry : buckets.entrySet()) {
                writeRecord(output, entry.getKey(), entry.getValue());
            }
            output.flush();
            file.getFD().sync();
        }
        try {
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING);
        }
        pendingDeltas.clear();
        journalRecordsSinceCompaction = 0L;
        lastCompactionDay = LocalDate.now();
    }

    private static void writeRecord(DataOutputStream output, long minute, MutableBucket bucket) throws IOException {
        output.writeLong(minute);
        output.writeDouble(bucket.mouse);
        output.writeLong(bucket.keys);
        output.writeLong(bucket.clicks);
        output.writeDouble(bucket.active);
    }

    private void loadBinary() throws IOException {
        long records = 0L;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                throw new IOException("Unsupported activity history format.");
            }
            while (true) {
                try {
                    long minute = input.readLong();
                    MutableBucket delta = new MutableBucket(input.readDouble(), input.readLong(), input.readLong(), input.readDouble());
                    buckets.computeIfAbsent(minute, ignored -> new MutableBucket()).add(delta.totals());
                    records++;
                } catch (EOFException end) {
                    break;
                }
            }
        }
        journalRecordsSinceCompaction = Math.max(0L, records - buckets.size());
        if (journalRecordsSinceCompaction >= COMPACTION_JOURNAL_RECORDS) compact();
    }

    private void loadLegacyCsv() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(legacyCsvFile, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; if (line.startsWith("epoch_minute")) continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 5) continue;
                try {
                    long minute = Long.parseLong(parts[0]);
                    MutableBucket bucket = new MutableBucket(
                            Double.parseDouble(parts[1]), Long.parseLong(parts[2]),
                            Long.parseLong(parts[3]), Double.parseDouble(parts[4]));
                    buckets.computeIfAbsent(minute, ignored -> new MutableBucket()).add(bucket.totals());
                } catch (NumberFormatException ignored) {
                    // Skip corrupt rows and retain the rest.
                }
            }
        }
    }

    private boolean migrateLegacySqlite() {
        Path legacy = directory.resolve("activity.db");
        if (!Files.isRegularFile(legacy)) return false;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String sqlite = null;
        if (os.contains("mac") && Files.isExecutable(Paths.get("/usr/bin/sqlite3"))) {
            sqlite = "/usr/bin/sqlite3";
        } else if (os.contains("linux")) {
            for (String candidate : List.of("/usr/bin/sqlite3", "/usr/local/bin/sqlite3")) {
                if (Files.isExecutable(Paths.get(candidate))) { sqlite = candidate; break; }
            }
        }
        if (sqlite == null) return false;

        String query = "SELECT CAST(bucket_epoch/60 AS INTEGER), mouse_pixels, key_presses, "
                + "mouse_clicks, COALESCE(active_seconds,0) FROM minute_activity ORDER BY bucket_epoch;";
        Process process = null;
        try {
            process = new ProcessBuilder(sqlite, "-csv", legacy.toString(), query)
                    .redirectErrorStream(true)
                    .start();
            boolean importedAny = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split(",", -1);
                    if (parts.length < 5) continue;
                    try {
                        long minute = Long.parseLong(parts[0]);
                        MutableBucket bucket = new MutableBucket(
                                Double.parseDouble(parts[1]), Long.parseLong(parts[2]),
                                Long.parseLong(parts[3]), Double.parseDouble(parts[4]));
                        buckets.computeIfAbsent(minute, ignored -> new MutableBucket()).add(bucket.totals());
                        importedAny = true;
                    } catch (NumberFormatException ignored) {
                        // Ignore diagnostic or corrupt output lines.
                    }
                }
            }
            int exit = process.waitFor();
            return exit == 0 && importedAny;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static boolean isZero(Totals totals) {
        return totals.mousePixels() == 0.0 && totals.keyPresses() == 0L
                && totals.mouseClicks() == 0L && totals.activeSeconds() == 0.0;
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
