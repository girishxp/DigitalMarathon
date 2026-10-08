package com.inputactivitytracker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Public GitHub release checks and verified portable downloads. Never replaces installed files. */
public final class UpdateManager implements AutoCloseable {
    public record Release(String version, String notes, String releaseUrl, String downloadUrl,
                          long size, String sha256, String checksumUrl) {}

    /** Callbacks run on the daemon worker; Swing callers must dispatch UI changes to the EDT. */
    public interface Listener {
        void checked(Release release, boolean manual, String status);
        void downloading(long bytes, long total);
        void downloaded(Path zip);
        void failed(String code, String userMessage);
        /** Explicit stage for shared review state; older callers retain their previous callbacks. */
        default void checkFailed(String code, String userMessage, boolean manual) {
            if (manual) failed(code, userMessage);
            else checked(null, false, userMessage);
        }
    }

    static final URI API = URI.create("https://api.github.com/repos/girishxp/DigitalMarathon/releases/latest");
    static final String DOWNLOAD_PREFIX = "https://github.com/girishxp/DigitalMarathon/releases/download/v";
    static final String RELEASE_PREFIX = "https://github.com/girishxp/DigitalMarathon/releases/tag/v";
    private static final Pattern VERSION = Pattern.compile("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED = 3L * 1024 * 1024 * 1024;
    private static final long MAX_ENTRY = 1024L * 1024 * 1024;
    private static final Set<String> DOWNLOAD_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private final String currentVersion;
    private final Path dataDirectory;
    private final Path preferencesPath;
    private final Transport transport;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private volatile Listener listener;
    private volatile BiConsumer<String, Map<String, Object>> eventSink = (name, props) -> {};
    private volatile boolean closed;
    private volatile boolean started;
    private volatile boolean automatic = true;
    private String skippedVersion = "";
    private String remindVersion = "";
    private long remindAfter;
    private volatile Release latestRelease;

    public UpdateManager(String currentVersion, Path dataDirectory) {
        this(currentVersion, dataDirectory, new HttpsTransport(), Clock.systemUTC());
    }

    /** Package-private transport injection for offline QA; production endpoints remain fixed. */
    UpdateManager(String currentVersion, Path dataDirectory, Transport transport, Clock clock) {
        if (!VERSION.matcher(currentVersion).matches()) throw new IllegalArgumentException("A semantic app version is required");
        this.currentVersion = currentVersion;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.preferencesPath = this.dataDirectory.resolve("update-preferences.json");
        this.transport = transport;
        this.clock = clock;
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Digital Marathon Updates");
            thread.setDaemon(true); return thread;
        });
        readPreferences();
    }

    public synchronized void start(Listener listener) {
        if (closed) return;
        this.listener = listener;
        if (started) return;
        started = true;
        worker.schedule(() -> { if (automatic) check(false); }, 2500, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(() -> { if (automatic) check(false); }, 15, 15, TimeUnit.MINUTES);
    }

    public void setEventSink(BiConsumer<String, Map<String, Object>> sink) {
        eventSink = sink == null ? (name, props) -> {} : sink;
    }

    public void checkNow() { enqueue(() -> check(true)); }
    public boolean autoCheckEnabled() { return automatic; }

    public void setAutoCheckEnabled(boolean enabled) {
        automatic = enabled;
        enqueue(() -> {
            savePreferences();
            if (enabled) check(false);
            else checked(null, false, "Automatic update checks are off. You can still check manually.");
        });
    }

    public void remindLater(String version) {
        requireVersion(version);
        enqueue(() -> {
            synchronized (this) {
                remindVersion = version;
                remindAfter = clock.millis() + TimeUnit.HOURS.toMillis(24);
                if (skippedVersion.equals(version)) skippedVersion = "";
            }
            savePreferences();
            event("update_postponed", Map.of("available_version", version, "reason", "remind_later"));
            checked(null, false, "We will remind you about v" + version + " in 24 hours.");
        });
    }

    public void skipVersion(String version) {
        requireVersion(version);
        enqueue(() -> {
            synchronized (this) { skippedVersion = version; remindVersion = ""; remindAfter = 0; }
            savePreferences();
            event("update_skipped", Map.of("available_version", version));
            checked(null, false, "v" + version + " is skipped. Newer versions will still be offered.");
        });
    }

    public void download(Release release) {
        if (closed || release == null || !downloading.compareAndSet(false, true)) return;
        enqueue(() -> {
            Path partial = null;
            String errorCode = null;
            try {
                validateRelease(release);
                event("update_download_started", Map.of("available_version", release.version()));
                Path cache = dataDirectory.resolve("updates");
                Files.createDirectories(cache);
                partial = Files.createTempFile(cache, ".digital-marathon-update-", ".partial");
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long received = 0;
                long lastProgress = 0;
                long transferDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
                try (Response response = request(URI.create(release.downloadUrl()), false);
                     InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial)) {
                    long contentLength = response.contentLength();
                    if (contentLength >= 0 && contentLength != release.size()) throw problem("size_mismatch");
                    byte[] buffer = new byte[64 * 1024];
                    progress(0, release.size());
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        interrupted();
                        if (System.nanoTime() > transferDeadline) throw problem("timeout");
                        received += count;
                        if (received > release.size() || received > MAX_DOWNLOAD) throw problem("size_mismatch");
                        hash.update(buffer, 0, count); output.write(buffer, 0, count);
                        if (clock.millis() - lastProgress >= 100) { progress(received, release.size()); lastProgress = clock.millis(); }
                    }
                }
                if (received != release.size()) throw problem("size_mismatch");
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(release.sha256()), hash.digest())) throw problem("checksum_mismatch");
                validateArchive(partial, release.version());
                interrupted();
                Path finalPath = cache.resolve(assetName(release.version()));
                moveAtomically(partial, finalPath); partial = null;
                synchronized (this) {
                    if (skippedVersion.equals(release.version())) skippedVersion = "";
                    if (remindVersion.equals(release.version())) { remindVersion = ""; remindAfter = 0; }
                }
                savePreferences();
                progress(received, release.size());
                event("update_downloaded", Map.of("available_version", release.version()));
                Listener callback = listener;
                if (!closed && callback != null) try { callback.downloaded(finalPath); } catch (RuntimeException ignored) {}
            } catch (Exception ex) {
                errorCode = failureCode(ex);
                event("update_error", Map.of("stage", "download", "error_code", errorCode));
            } finally {
                if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
                downloading.set(false);
            }
            if (errorCode != null) failed(errorCode, "The update could not be verified or downloaded. Your installed app and history have not changed. Please try again.");
        });
    }

    private void check(boolean manual) {
        if (closed || downloading.get() || (!manual && !automatic)) return;
        event("update_check_started", Map.of("manual", manual));
        try {
            Map<String, Object> release = JsonCodec.parseObject(readText(API, true, 5 * 1024 * 1024));
            if (!Boolean.FALSE.equals(release.get("draft")) || !Boolean.FALSE.equals(release.get("prerelease"))) throw problem("unpublished_release");
            String tag = string(release, "tag_name");
            if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) throw problem("invalid_version");
            String version = tag.substring(1);
            if (compareVersions(version, currentVersion) <= 0) {
                latestRelease = null;
                event("update_check_completed", Map.of("manual", manual, "latest_version", version));
                checked(null, manual, "Digital Marathon " + currentVersion + " is up to date.");
                return;
            }
            List<?> assets = release.get("assets") instanceof List<?> list ? list : Collections.emptyList();
            Map<String, Object> asset = exactAsset(assets, assetName(version));
            String downloadUrl = string(asset, "browser_download_url");
            validateAssetUri(downloadUrl, version, assetName(version));
            long size = integer(asset.get("size"));
            if (size <= 0 || size > MAX_DOWNLOAD) throw problem("invalid_size");
            String digest = string(asset, "digest");
            String sha = "";
            String checksumUrl = "";
            if (digest.startsWith("sha256:") && SHA256.matcher(digest.substring(7)).matches()) sha = digest.substring(7).toLowerCase(Locale.ROOT);
            if (sha.isEmpty()) {
                Map<String, Object> checksum = exactAsset(assets, checksumName(version));
                checksumUrl = string(checksum, "browser_download_url");
                validateAssetUri(checksumUrl, version, checksumName(version));
                if (integer(checksum.get("size")) > 128 * 1024) throw problem("invalid_checksum");
                sha = checksumFor(readText(URI.create(checksumUrl), false, 128 * 1024), assetName(version));
            }
            String notes = string(release, "body");
            if (notes.length() > 6000) notes = notes.substring(0, 6000);
            Release found = new Release(version, notes, RELEASE_PREFIX + version, downloadUrl, size, sha, checksumUrl);
            latestRelease = found;
            event("update_check_completed", Map.of("manual", manual, "latest_version", version));
            synchronized (this) {
                if (!manual && skippedVersion.equals(version)) {
                    checked(null, false, "v" + version + " is skipped. You can still check manually."); return;
                }
                if (!manual && remindVersion.equals(version) && remindAfter > clock.millis()) {
                    checked(null, false, "The v" + version + " reminder is postponed."); return;
                }
            }
            event("update_available", Map.of("available_version", version, "manual", manual));
            checked(found, manual, "Digital Marathon v" + version + " is available.");
        } catch (Exception ex) {
            // A transient check failure cannot invalidate a previously checked release or ready ZIP.
            String code = failureCode(ex);
            event("update_error", Map.of("stage", "check", "error_code", code, "manual", manual));
            checkFailed(code, manual
                    ? "We could not check for a published update. Please check your connection and try again later."
                    : "Automatic update check could not finish. It will try again later.", manual);
        }
    }

    private void validateRelease(Release release) throws IOException {
        requireVersion(release.version());
        if (compareVersions(release.version(), currentVersion) <= 0) throw problem("invalid_version");
        if (!SHA256.matcher(release.sha256() == null ? "" : release.sha256()).matches()) throw problem("missing_checksum");
        if (release.size() <= 0 || release.size() > MAX_DOWNLOAD) throw problem("invalid_size");
        validateAssetUri(release.downloadUrl(), release.version(), assetName(release.version()));
        if (!release.releaseUrl().equals(RELEASE_PREFIX + release.version())) throw problem("invalid_origin");
        Release known = latestRelease;
        if (known == null || !known.equals(release)) throw problem("release_not_checked");
    }

    private Response request(URI original, boolean api) throws IOException {
        URI endpoint = original;
        long requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (int redirects = 0; redirects <= 5; redirects++) {
            validateHttps(endpoint, api);
            interrupted();
            if (System.nanoTime() > requestDeadline) throw problem("timeout");
            Response response = transport.get(endpoint, currentVersion);
            if (System.nanoTime() > requestDeadline) { response.close(); throw problem("timeout"); }
            if (Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                String location = response.location(); response.close();
                if (redirects == 5 || location == null || location.isBlank()) throw problem("invalid_redirect");
                try { endpoint = endpoint.resolve(location); } catch (IllegalArgumentException ex) { throw problem("invalid_redirect"); }
                continue;
            }
            if (response.status() != 200) { response.close(); throw problem(response.status() == 404 ? "release_missing" : "http_error"); }
            return response;
        }
        throw problem("invalid_redirect");
    }

    private String readText(URI url, boolean api, int maximum) throws IOException {
        try (Response response = request(url, api); InputStream input = response.body()) {
            if (response.contentLength() > maximum) throw problem("response_too_large");
            return new String(readLimited(input, maximum), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long responseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if (System.nanoTime() > responseDeadline) throw problem("timeout");
            if ((long) out.size() + count > maximum) throw problem("response_too_large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void validateHttps(URI uri, boolean api) throws IOException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw problem("invalid_origin");
        if (api ? !host.equalsIgnoreCase("api.github.com") : !DOWNLOAD_HOSTS.contains(host.toLowerCase(Locale.ROOT))) throw problem("invalid_origin");
    }

    private static void validateAssetUri(String value, String version, String name) throws IOException {
        try {
            URI uri = URI.create(value);
            validateHttps(uri, false);
            if (!value.equals(DOWNLOAD_PREFIX + version + "/" + name)) throw problem("invalid_origin");
        } catch (IllegalArgumentException ex) { throw problem("invalid_origin"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exactAsset(List<?> assets, String name) throws IOException {
        Map<String, Object> found = null;
        for (Object item : assets) {
            if (item instanceof Map<?, ?> map && name.equals(map.get("name"))) {
                if (found != null) throw problem("duplicate_asset");
                found = (Map<String, Object>) map;
            }
        }
        if (found == null) throw problem("asset_missing");
        return found;
    }

    private static String checksumFor(String text, String name) throws IOException {
        String found = null;
        Pattern line = Pattern.compile("^([0-9a-fA-F]{64})[ \\t]+\\*?" + Pattern.quote(name) + "$", Pattern.MULTILINE);
        Matcher matcher = line.matcher(text.replace("\r\n", "\n"));
        while (matcher.find()) {
            if (found != null) throw problem("invalid_checksum");
            found = matcher.group(1).toLowerCase(Locale.ROOT);
        }
        if (found == null) throw problem("missing_checksum");
        return found;
    }

    static void validateArchive(Path zip, String version) throws IOException {
        inspectCentralDirectory(zip);
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            int count = 0;
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                interrupted();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 20000 || !names.add(name)) throw problem("invalid_archive");
                if (!name.startsWith("digital-marathon/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                        || name.indexOf(':') >= 0 || name.contains("//")) throw problem("unsafe_archive_path");
                for (String part : name.split("/")) {
                    if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) throw problem("unsafe_archive_path");
                    String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
                    if (base.matches("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) throw problem("unsafe_archive_path");
                }
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY) throw problem("archive_too_large");
                CRC32 crc = new CRC32(); long bytes = 0;
                try (InputStream input = archive.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        interrupted(); bytes += read; expanded += read;
                        if (bytes > MAX_ENTRY || expanded > MAX_EXPANDED) throw problem("archive_too_large");
                        crc.update(buffer, 0, read);
                    }
                }
                if (bytes != entry.getSize() || crc.getValue() != entry.getCrc()) throw problem("archive_corrupt");
            }
            String prefix = "digital-marathon/";
            for (String required : List.of("app-files/VERSION", "app-files/app/digital-marathon.jar",
                    "Digital Marathon.app/Contents/Info.plist", "Digital Marathon.app/Contents/app/digital-marathon.jar",
                    "Start Digital Marathon - Windows.bat")) {
                ZipEntry entry = archive.getEntry(prefix + required);
                if (entry == null || entry.isDirectory()) throw problem("wrong_product");
            }
            try (InputStream input = archive.getInputStream(archive.getEntry(prefix + "app-files/VERSION"))) {
                if (!version.equals(new String(readLimited(input, 128), StandardCharsets.UTF_8).trim())) throw problem("wrong_version");
            }
            for (String jar : List.of("app-files/app/digital-marathon.jar", "Digital Marathon.app/Contents/app/digital-marathon.jar")) {
                try (JarInputStream input = new JarInputStream(archive.getInputStream(archive.getEntry(prefix + jar)))) {
                    Manifest manifest = input.getManifest();
                    if (manifest == null || !"Digital Marathon".equals(manifest.getMainAttributes().getValue("Implementation-Title"))
                            || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))
                            || !"com.inputactivitytracker.Main".equals(manifest.getMainAttributes().getValue("Main-Class"))) throw problem("wrong_product");
                }
            }
        }
    }

    /** Reject encrypted, multipart, link/device entries before a user extracts this portable ZIP. */
    private static void inspectCentralDirectory(Path zip) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(zip.toFile(), "r")) {
            long length = file.length();
            int tailLength = (int) Math.min(length, 65557);
            if (tailLength < 22 || length > MAX_DOWNLOAD) throw problem("invalid_archive");
            byte[] tail = new byte[tailLength]; file.seek(length - tailLength); file.readFully(tail);
            int end = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (little(tail, i, 4) == 0x06054b50L && i + 22 + little(tail, i + 20, 2) == tail.length) { end = i; break; }
            }
            if (end < 0 || little(tail, end + 4, 2) != 0 || little(tail, end + 6, 2) != 0) throw problem("invalid_archive");
            int count = (int) little(tail, end + 10, 2);
            if (count == 65535 || count > 20000 || count != little(tail, end + 8, 2)) throw problem("invalid_archive");
            long centralLength = little(tail, end + 12, 4), offset = little(tail, end + 16, 4);
            long endPosition = length - tailLength + end;
            if (centralLength > 16 * 1024 * 1024 || offset + centralLength != endPosition) throw problem("invalid_archive");
            file.seek(offset);
            byte[] header = new byte[46];
            for (int n = 0; n < count; n++) {
                file.readFully(header);
                if (little(header, 0, 4) != 0x02014b50L || (little(header, 8, 2) & 1) != 0
                        || little(header, 34, 2) != 0) throw problem("invalid_archive");
                int platform = header[5] & 255;
                int mode = (int) (little(header, 38, 4) >>> 16) & 0xf000;
                if (platform == 3 && mode != 0 && mode != 0x8000 && mode != 0x4000) throw problem("unsafe_archive_link");
                long next = file.getFilePointer() + little(header, 28, 2) + little(header, 30, 2) + little(header, 32, 2);
                if (next > offset + centralLength) throw problem("invalid_archive");
                file.seek(next);
            }
            if (file.getFilePointer() != offset + centralLength) throw problem("invalid_archive");
        }
    }

    private static long little(byte[] bytes, int at, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value |= (long) (bytes[at + i] & 255) << (i * 8);
        return value;
    }

    public static int compareVersions(String first, String second) {
        requireVersion(first); requireVersion(second);
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int difference = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (difference != 0) return difference;
        }
        return 0;
    }

    private static void requireVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) throw new IllegalArgumentException("Invalid semantic version");
    }

    static String assetName(String version) { return "digital-marathon-cross-platform-v" + version + "-click-to-launch.zip"; }
    static String checksumName(String version) { return "SHA256SUMS-v" + version + ".txt"; }
    private static String string(Map<String, Object> map, String key) { return map.get(key) instanceof String text ? text : ""; }
    private static long integer(Object value) throws IOException {
        if (!(value instanceof Number number)) throw problem("invalid_size");
        try { return new BigDecimal(number.toString()).longValueExact(); } catch (ArithmeticException | NumberFormatException ex) { throw problem("invalid_size"); }
    }

    private void readPreferences() {
        try {
            if (!Files.isRegularFile(preferencesPath) || Files.size(preferencesPath) > 64 * 1024) return;
            Map<String, Object> prefs = JsonCodec.parseObject(Files.readString(preferencesPath));
            automatic = !Boolean.FALSE.equals(prefs.get("automatic"));
            String skip = string(prefs, "skippedVersion"), remind = string(prefs, "remindVersion");
            skippedVersion = VERSION.matcher(skip).matches() ? skip : "";
            remindVersion = VERSION.matcher(remind).matches() ? remind : "";
            if (prefs.get("remindAfter") instanceof Number value) {
                long proposed = value.longValue();
                if (proposed >= 0 && proposed <= clock.millis() + TimeUnit.HOURS.toMillis(24)) remindAfter = proposed;
            }
        } catch (IOException | IllegalArgumentException ignored) {}
    }

    private synchronized void savePreferences() {
        Path temporary = null;
        try {
            Files.createDirectories(dataDirectory);
            temporary = Files.createTempFile(dataDirectory, ".update-preferences-", ".tmp");
            Map<String, Object> prefs = new LinkedHashMap<>();
            prefs.put("automatic", automatic); prefs.put("skippedVersion", skippedVersion);
            prefs.put("remindVersion", remindVersion); prefs.put("remindAfter", remindAfter);
            Files.writeString(temporary, JsonCodec.stringify(prefs) + "\n");
            moveAtomically(temporary, preferencesPath); temporary = null;
        } catch (IOException ignored) {
            // A preferences write failure never interrupts tracking or a valid download.
        } finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void enqueue(Runnable action) {
        synchronized (this) { if (!closed) worker.execute(action); }
    }
    private void checked(Release release, boolean manual, String status) {
        Listener callback = listener;
        if (!closed && callback != null) try { callback.checked(release, manual, status); } catch (RuntimeException ignored) {}
    }
    private void progress(long bytes, long total) {
        Listener callback = listener;
        if (!closed && callback != null) try { callback.downloading(bytes, total); } catch (RuntimeException ignored) {}
    }
    private void failed(String code, String message) {
        Listener callback = listener;
        if (!closed && callback != null) try { callback.failed(code, message); } catch (RuntimeException ignored) {}
    }
    private void checkFailed(String code, String message, boolean manual) {
        Listener callback = listener;
        if (!closed && callback != null) try { callback.checkFailed(code, message, manual); } catch (RuntimeException ignored) {}
    }
    private void event(String name, Map<String, Object> props) {
        if (!closed) try { eventSink.accept(name, props); } catch (RuntimeException ignored) {}
    }
    private static void interrupted() throws IOException { if (Thread.currentThread().isInterrupted()) throw problem("cancelled"); }
    private static UpdateFailure problem(String code) { return new UpdateFailure(code); }
    private static String failureCode(Exception ex) {
        if (ex instanceof UpdateFailure failure) return failure.code;
        if (ex instanceof java.net.SocketTimeoutException) return "timeout";
        if (ex instanceof java.util.zip.ZipException) return "archive_corrupt";
        if (ex instanceof IllegalArgumentException) return "invalid_response";
        return "connection_or_file_error";
    }
    private static final class UpdateFailure extends IOException {
        final String code;
        UpdateFailure(String code) { super(code); this.code = code; }
    }

    @Override public synchronized void close() { closed = true; listener = null; worker.shutdownNow(); }

    interface Transport { Response get(URI uri, String version) throws IOException; }
    interface Response extends AutoCloseable {
        int status(); String location(); long contentLength(); InputStream body() throws IOException;
        @Override void close() throws IOException;
    }
    private static final class HttpsTransport implements Transport {
        @Override public Response get(URI uri, String version) throws IOException {
            if (Boolean.getBoolean("digitalmarathon.networkDisabled")) throw problem("network_disabled");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", uri.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Digital-Marathon/" + version);
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            try {
                int status = connection.getResponseCode();
                return new Response() {
                    private InputStream input;
                    public int status() { return status; }
                    public String location() { return connection.getHeaderField("Location"); }
                    public long contentLength() { return connection.getContentLengthLong(); }
                    public InputStream body() throws IOException { if (input == null) input = connection.getInputStream(); return input; }
                    public void close() throws IOException { try { if (input != null) input.close(); } finally { connection.disconnect(); } }
                };
            } catch (IOException ex) { connection.disconnect(); throw ex; }
        }
    }
}
