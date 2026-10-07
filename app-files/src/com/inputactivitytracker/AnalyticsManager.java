package com.inputactivitytracker;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Privacy-limited, best-effort product analytics. No input history is read here. */
final class AnalyticsManager implements AutoCloseable {
    private static final int QUEUE_LIMIT = 100;
    private static final int BATCH_LIMIT = 20;
    private static final int MAX_ATTEMPTS = 3;
    private static final long FLUSH_DELAY_MS = 1_200;
    private static final long RETRY_DELAY_MS = 30_000;
    private static final long CLOSE_GRACE_MS = 1_500;
    private static final String WIRE_EVENT_PREFIX = "digital_marathon_";
    private static final Set<String> EVENTS = Set.of(
            "app_started", "app_heartbeat", "app_closed", "analytics_enabled",
            "view_changed", "appearance_changed", "range_changed", "distance_unit_changed",
            "capture_privacy_changed", "always_on_top_changed", "tracking_paused", "tracking_resumed",
            "session_reset", "history_cleared", "certificate_opened", "certificate_exported",
            "certificate_export_failed", "csv_exported", "csv_export_failed", "help_opened",
            "permissions_help_opened", "app_error", "update_check_started", "update_check_completed",
            "update_available", "update_download_started", "update_downloaded", "update_install_started",
            "update_install_completed", "update_error", "update_postponed", "update_skipped");
    private static final Map<String, Set<String>> ENUM_PROPERTIES = Map.ofEntries(
            Map.entry("view", Set.of("full", "mini")),
            Map.entry("appearance", Set.of("light", "dark", "system")),
            Map.entry("unit", Set.of("pixels", "millimetres", "centimetres", "metres", "kilometres",
                    "inches", "feet", "yards", "miles", "px", "mm", "cm", "m", "km", "in", "ft", "yd", "mi")),
            Map.entry("stage", Set.of("startup", "check", "fetch", "download", "verify", "extract",
                    "install", "restart", "export", "shutdown")),
            Map.entry("reason", Set.of("user_choice", "network", "http", "timeout", "invalid_metadata",
                    "no_release", "no_asset", "verification_failed", "unsupported_platform", "blocked", "unknown", "remind_later")),
            Map.entry("error_name", Set.of("Error", "Exception", "IOException", "FileSystemException",
                    "AccessDeniedException", "SecurityException", "IllegalArgumentException",
                    "IllegalStateException", "HttpTimeoutException", "HttpConnectTimeoutException",
                    "ConnectException", "UnknownHostException", "SSLException", "InterruptedException",
                    "CompletionException", "CancellationException", "TimeoutException")),
            Map.entry("error_code", Set.of("NETWORK", "HTTP", "TIMEOUT", "IO", "ACCESS_DENIED",
                    "INVALID_METADATA", "NO_ASSET", "DIGEST_MISMATCH", "SIZE_MISMATCH", "EXTRACT_FAILED",
                    "INSTALL_FAILED", "RESTART_FAILED", "UNSUPPORTED_PLATFORM", "UNKNOWN",
                    "archive_corrupt", "archive_too_large", "asset_missing", "cancelled", "checksum_mismatch",
                    "duplicate_asset", "invalid_archive", "invalid_checksum", "invalid_origin", "invalid_redirect",
                    "invalid_size", "invalid_version", "missing_checksum", "release_not_checked", "response_too_large",
                    "size_mismatch", "timeout", "unpublished_release", "unsafe_archive_link", "unsafe_archive_path",
                    "wrong_product", "wrong_version", "release_missing", "http_error", "invalid_response",
                    "connection_or_file_error", "save_failed", "network_disabled")));
    private static final Set<String> BOOLEAN_PROPERTIES = Set.of("enabled", "manual", "automatic");
    private static final Set<String> VERSION_PROPERTIES = Set.of("available_version", "latest_version");

    private final String currentVersion;
    private final Path settingsFile;
    private final Configuration configuration;
    private final Transport transport;
    private final ScheduledExecutorService executor;
    private final ExecutorService preferenceWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "digital-marathon-analytics-preferences");
        thread.setDaemon(true);
        return thread;
    });
    private final LongSupplier clock;
    private final boolean forcedNetworkDisabled;
    private final String installId;
    private final String sessionId = UUID.randomUUID().toString();
    private final long firstSeenAt;
    private final ArrayDeque<QueuedEvent> queue = new ArrayDeque<>();
    private Boolean preference;
    private long launchCount;
    private long startedAt;
    private long lastSeenAt;
    private long preferenceChangedAt;
    private long generation;
    private boolean started;
    private boolean closing;
    private boolean stopped;
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> heartbeatTask;
    private CompletableFuture<Integer> inFlight;

    AnalyticsManager(String currentVersion, Path dataDirectory) {
        this(currentVersion, dataDirectory, Configuration.load(), new HttpsTransport(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "digital-marathon-analytics");
                    thread.setDaemon(true);
                    return thread;
                }), System::currentTimeMillis, false);
    }

    // Mock transport/scheduler/clock keep verification entirely offline.
    AnalyticsManager(String currentVersion, Path dataDirectory, Configuration configuration,
                     Transport transport, ScheduledExecutorService executor, LongSupplier clock,
                     boolean forcedNetworkDisabled) {
        this.currentVersion = validVersion(currentVersion) ? currentVersion : "unknown";
        this.settingsFile = dataDirectory.resolve("analytics-settings.json");
        this.configuration = configuration;
        this.transport = transport;
        this.executor = executor;
        this.clock = clock;
        this.forcedNetworkDisabled = forcedNetworkDisabled;
        Map<String, Object> saved = readSettings(settingsFile);
        this.installId = validUuid(saved.get("installId"))
                ? saved.get("installId").toString() : UUID.randomUUID().toString();
        long now = Math.max(0, clock.getAsLong());
        this.firstSeenAt = nonnegativeLong(saved.get("firstSeenAt"), now);
        this.lastSeenAt = now;
        this.launchCount = Math.min(1_000_000, nonnegativeLong(saved.get("launchCount"), 0));
        this.preferenceChangedAt = nonnegativeLong(saved.get("preferenceChangedAt"), 0);
        this.preference = saved.get("enabled") instanceof Boolean value ? value : null;
    }

    synchronized boolean configured() {
        return configuration.valid() && !networkDisabled();
    }

    synchronized boolean enabled() {
        return !stopped && configured() && (preference != null ? preference : configuration.defaultEnabled());
    }

    synchronized void setEnabled(boolean value) {
        if (stopped || closing) return;
        preference = value;
        preferenceChangedAt = Math.max(0, clock.getAsLong());
        saveSettingsAsync();
        if (!enabled()) {
            generation++;
            queue.clear();
            cancel(flushTask);
            flushTask = null;
            cancel(heartbeatTask);
            heartbeatTask = null;
            CompletableFuture<Integer> request = inFlight;
            inFlight = null;
            if (request != null) request.cancel(true);
        } else if (started) {
            startHeartbeat();
            enqueue("analytics_enabled", Map.of());
        }
    }

    synchronized void start() {
        if (started || stopped || closing) return;
        started = true;
        startedAt = Math.max(0, clock.getAsLong());
        lastSeenAt = startedAt;
        launchCount = Math.min(1_000_000, launchCount + 1);
        if (preference == null && configured()) preference = configuration.defaultEnabled();
        saveSettingsAsync();
        if (enabled()) {
            enqueue("app_started", Map.of());
            startHeartbeat();
        }
    }

    synchronized void capture(String event, Map<String, Object> properties) {
        if (!started || closing || !enabled() || event == null || !EVENTS.contains(event)) return;
        enqueue(event, sanitize(properties));
    }

    private void enqueue(String event, Map<String, Object> properties) {
        if (!enabled()) return;
        Map<String, Object> all = commonProperties();
        all.putAll(properties);
        if (event.equals("app_heartbeat") || event.equals("app_closed")) {
            all.put("session_minutes", Math.max(0, (clock.getAsLong() - startedAt) / 60_000));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("event", WIRE_EVENT_PREFIX + event);
        item.put("properties", all);
        item.put("timestamp", Instant.ofEpochMilli(Math.max(0, clock.getAsLong())).toString());
        if (queue.size() >= QUEUE_LIMIT) queue.removeFirst();
        queue.addLast(new QueuedEvent(item, 0));
        scheduleFlush(FLUSH_DELAY_MS);
    }

    private Map<String, Object> commonProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("distinct_id", installId);
        properties.put("$session_id", sessionId);
        properties.put("session_id", sessionId);
        properties.put("app_name", "Digital Marathon");
        properties.put("app_id", "digital-marathon");
        properties.put("app_version", currentVersion);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        properties.put("platform", os.contains("mac") ? "darwin" : os.contains("win") ? "win32"
                : os.contains("linux") ? "linux" : "other");
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        properties.put("architecture", arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                : arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                : arch.equals("x86") || arch.equals("i386") ? "x86" : "other");
        properties.put("os_release", numericRelease(System.getProperty("os.version", "")));
        properties.put("java_version", numericRelease(System.getProperty("java.version", "")));
        properties.put("runtime_mode", "portable");
        properties.put("launch_count", launchCount);
        properties.put("install_age_days", Math.max(0, (clock.getAsLong() - firstSeenAt) / 86_400_000));
        properties.put("$process_person_profile", false);
        properties.put("$geoip_disable", true);
        return properties;
    }

    private void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) return;
        long interval = configuration.heartbeat().toMillis();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            synchronized (AnalyticsManager.this) {
                if (!closing && enabled()) enqueue("app_heartbeat", Map.of());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void scheduleFlush(long delayMs) {
        if (stopped || inFlight != null || queue.isEmpty() || !enabled()) return;
        if (flushTask == null || flushTask.isDone()) {
            flushTask = executor.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void flush() {
        flushTask = null;
        if (stopped || !enabled() || inFlight != null || queue.isEmpty()) return;
        List<QueuedEvent> batch = new ArrayList<>(BATCH_LIMIT);
        while (batch.size() < BATCH_LIMIT && !queue.isEmpty()) batch.add(queue.removeFirst());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", configuration.publicToken());
        payload.put("batch", batch.stream().map(QueuedEvent::value).toList());
        long requestGeneration = generation;
        CompletableFuture<Integer> request;
        try {
            // Sending is started under the same lock as opt-out. Disable cancels
            // this future and invalidates its completion before clearing the queue.
            request = transport.send(configuration.endpoint(), JsonCodec.stringify(payload), currentVersion);
            inFlight = request;
        } catch (RuntimeException ignored) {
            completeBatch(requestGeneration, batch, 0, true);
            return;
        }
        request.whenComplete((status, error) -> {
            try {
                executor.execute(() -> completeBatch(requestGeneration, batch,
                        status == null ? 0 : status, error != null));
            } catch (RejectedExecutionException ignored) {
                // The app has already closed; analytics never delay shutdown.
            }
        });
    }

    private synchronized void completeBatch(long requestGeneration, List<QueuedEvent> batch,
                                             int status, boolean failed) {
        if (requestGeneration != generation || stopped) return;
        inFlight = null;
        if (!enabled()) return;
        boolean retryable = failed || status == 0 || status == 408 || status == 429 || status >= 500;
        if (retryable && !closing) {
            // Keep the most recent usage if the bounded queue is already full.
            for (int index = batch.size() - 1; index >= 0; index--) {
                QueuedEvent event = batch.get(index);
                if (event.attempts() + 1 < MAX_ATTEMPTS && queue.size() < QUEUE_LIMIT) {
                    queue.addFirst(new QueuedEvent(event.value(), event.attempts() + 1));
                }
            }
        }
        if (!queue.isEmpty()) scheduleFlush(closing ? 0 : retryable ? RETRY_DELAY_MS : FLUSH_DELAY_MS);
    }

    @Override public void close() {
        CompletableFuture<Void> localWrite;
        synchronized (this) {
            if (closing || stopped) return;
            if (started && enabled()) {
                enqueue("app_closed", Map.of());
                // Prioritize the final lifecycle event during the short grace period.
                queue.addFirst(queue.removeLast());
            }
            closing = true;
            cancel(heartbeatTask);
            heartbeatTask = null;
            cancel(flushTask);
            flushTask = null;
            lastSeenAt = Math.max(0, clock.getAsLong());
            localWrite = saveSettingsAsync();
            if (enabled() && inFlight == null) executor.execute(this::flush);
            // This is best effort: daemon networking cannot hold the app open.
            executor.schedule(this::stop, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
        }
        try {
            // Only a tiny local preference write is drained, on an independent
            // executor. A blocked or outstanding HTTP request is never awaited.
            localWrite.get(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) { }
        preferenceWriter.shutdown();
    }

    private synchronized void stop() {
        stopped = true;
        generation++;
        queue.clear();
        cancel(flushTask);
        cancel(heartbeatTask);
        CompletableFuture<Integer> request = inFlight;
        inFlight = null;
        if (request != null) request.cancel(true);
        executor.shutdown();
    }

    private boolean networkDisabled() {
        return forcedNetworkDisabled || Boolean.getBoolean("digitalmarathon.networkDisabled");
    }

    private CompletableFuture<Void> saveSettingsAsync() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("installId", installId);
        saved.put("firstSeenAt", firstSeenAt);
        saved.put("lastSeenAt", lastSeenAt);
        saved.put("launchCount", launchCount);
        if (preference != null) saved.put("enabled", preference);
        if (preferenceChangedAt > 0) saved.put("preferenceChangedAt", preferenceChangedAt);
        try {
            return CompletableFuture.runAsync(() -> writeSettings(saved), preferenceWriter);
        } catch (RejectedExecutionException ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private void writeSettings(Map<String, Object> saved) {
        Path temporary = settingsFile.resolveSibling("analytics-settings-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(temporary, JsonCodec.stringify(saved) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Failure to save analytics preferences cannot interrupt tracking.
        } finally {
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    private static Map<String, Object> readSettings(Path path) {
        try {
            if (Files.isRegularFile(path) && Files.size(path) <= 65_536) {
                return JsonCodec.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        return Map.of();
    }

    private static Map<String, Object> sanitize(Map<String, Object> properties) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (properties == null) return sanitized;
        properties.forEach((key, value) -> {
            if (key == null || value == null) return;
            if (BOOLEAN_PROPERTIES.contains(key) && value instanceof Boolean) sanitized.put(key, value);
            else if (ENUM_PROPERTIES.containsKey(key) && value instanceof String text) {
                String category = key.equals("unit") ? text.toLowerCase(Locale.ROOT) : text;
                if (ENUM_PROPERTIES.get(key).contains(category)) sanitized.put(key, category);
            }
            else if (VERSION_PROPERTIES.contains(key) && value instanceof String text && validVersion(text)) {
                sanitized.put(key, text);
            }
        });
        return sanitized;
    }

    private static boolean validVersion(String value) {
        return value != null && value.matches("v?\\d{1,5}\\.\\d{1,5}\\.\\d{1,8}(?:[-+][a-zA-Z0-9.-]{1,32})?");
    }

    private static String numericRelease(String value) {
        return value != null && value.matches("[0-9][0-9._+\\-]{0,39}") ? value : "other";
    }

    private static boolean validUuid(Object value) {
        if (!(value instanceof String text)) return false;
        try { return UUID.fromString(text).toString().equals(text); } catch (Exception ignored) { return false; }
    }

    private static long nonnegativeLong(Object value, long fallback) {
        if (!(value instanceof Number number)) return fallback;
        return Math.max(0, number.longValue());
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }

    private record QueuedEvent(Map<String, Object> value, int attempts) { }

    record Configuration(URI endpoint, String publicToken, boolean defaultEnabled, Duration heartbeat) {
        boolean valid() {
            return endpoint != null && "https".equalsIgnoreCase(endpoint.getScheme())
                    && endpoint.getHost() != null && endpoint.getUserInfo() == null
                    && endpoint.getQuery() == null && endpoint.getFragment() == null
                    && "/batch/".equals(endpoint.getPath()) && publicToken != null
                    && publicToken.matches("phc_[a-zA-Z0-9_-]{8,250}")
                    && heartbeat != null && !heartbeat.isNegative() && !heartbeat.isZero();
        }

        static Configuration load() {
            try (InputStream input = AnalyticsManager.class.getResourceAsStream("/analytics-config.json")) {
                if (input == null) return unconfigured();
                byte[] bytes = input.readNBytes(65_537);
                if (bytes.length > 65_536) return unconfigured();
                return from(JsonCodec.parseObject(new String(bytes, StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                return unconfigured();
            }
        }

        static Configuration from(Map<String, Object> values) {
            try {
                if (!"posthog".equals(values.get("provider"))) return unconfigured();
                URI host = URI.create(String.valueOf(values.getOrDefault("host", "")));
                if (!"https".equalsIgnoreCase(host.getScheme()) || host.getHost() == null
                        || host.getUserInfo() != null || host.getQuery() != null || host.getFragment() != null
                        || !(host.getPath().isEmpty() || host.getPath().equals("/"))) return unconfigured();
                String key = String.valueOf(values.getOrDefault("apiKey", ""));
                boolean defaultEnabled = values.get("defaultEnabled") instanceof Boolean value && value;
                long minutes = Math.max(5, Math.min(60, nonnegativeLong(values.get("heartbeatMinutes"), 10)));
                return new Configuration(host.resolve("/batch/"), key, defaultEnabled, Duration.ofMinutes(minutes));
            } catch (Exception ignored) {
                return unconfigured();
            }
        }

        static Configuration unconfigured() {
            return new Configuration(null, "", false, Duration.ofMinutes(10));
        }
    }

    interface Transport {
        CompletableFuture<Integer> send(URI endpoint, String jsonPayload, String version);
    }

    private static final class HttpsTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override public CompletableFuture<Integer> send(URI endpoint, String payload, String version) {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DigitalMarathon/" + version)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            CompletableFuture<HttpResponse<Void>> exchange = client.sendAsync(request,
                    HttpResponse.BodyHandlers.discarding());
            CompletableFuture<Integer> result = new CompletableFuture<>() {
                @Override public boolean cancel(boolean mayInterruptIfRunning) {
                    exchange.cancel(mayInterruptIfRunning);
                    return super.cancel(mayInterruptIfRunning);
                }
            };
            exchange.whenComplete((response, error) -> {
                if (error != null) result.completeExceptionally(error);
                else result.complete(response.statusCode());
            });
            result.orTimeout(12, TimeUnit.SECONDS).whenComplete((status, error) -> {
                if (error != null && !exchange.isDone()) exchange.cancel(true);
            });
            return result;
        }
    }
}
