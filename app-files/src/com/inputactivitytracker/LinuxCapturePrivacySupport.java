package com.inputactivitytracker;

import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linux capture privacy support for KWin/Plasma's per-window capture exclusion rule. */
final class LinuxCapturePrivacySupport {
    private static final String WM_CLASS = "digital-marathon";
    private static final String RULE_ID = "7f7f1c92-1e50-4e9b-9d6f-dad6bbbd2d16";
    private static final String RULE_DESCRIPTION = "Digital Marathon Screen Capture Privacy";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:^|\\s)(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(4);

    private LinuxCapturePrivacySupport() {}

    /** Set a stable X11/XWayland WM_CLASS before the first Swing window is created. */
    static void configureStableWindowClass() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Class<?> toolkitClass = toolkit.getClass();
            if (!"sun.awt.X11.XToolkit".equals(toolkitClass.getName())) return;
            Field field = toolkitClass.getDeclaredField("awtAppClassName");
            field.setAccessible(true);
            field.set(null, WM_CLASS);
        } catch (Throwable ignored) {
            // The KWin rule also matches Digital Marathon's default Java WM_CLASS.
        }
    }

    static CapturePrivacySupport.Result apply(boolean enabled) {
        if (!enabled && !isKdeSession()) {
            return CapturePrivacySupport.Result.success("Capture privacy is off.");
        }

        if (!isKdeSession()) {
            return CapturePrivacySupport.Result.unsupported(
                    "This Linux desktop does not provide a standard per-window capture exclusion API. "
                            + "Digital Marathon can enforce this automatically on KDE Plasma 6.7 or later.");
        }

        Version plasma = detectPlasmaVersion();
        if (plasma == null || !plasma.atLeast(6, 7)) {
            String found = plasma == null ? "an unknown Plasma version" : "Plasma " + plasma;
            return CapturePrivacySupport.Result.unsupported(
                    "Linux capture exclusion requires KDE Plasma 6.7 or later; this session reports " + found + ".");
        }

        String kwrite = findCommand("kwriteconfig6");
        String kread = findCommand("kreadconfig6");
        String qdbus = firstCommand("qdbus6", "qdbus");
        if (kwrite == null || kread == null || qdbus == null) {
            return CapturePrivacySupport.Result.failure(
                    "KDE capture privacy tools are incomplete. kwriteconfig6, kreadconfig6 and qdbus6/qdbus are required.");
        }

        try {
            String existingRules = run(kread, "--file", "kwinrulesrc", "--group", "General", "--key", "rules").output().trim();
            // Put Digital Marathon's rule first because KWin evaluates matching
            // window rules in list order and the first rule for an attribute wins.
            Set<String> rules = new LinkedHashSet<>();
            rules.add(RULE_ID);
            if (!existingRules.isBlank()) {
                for (String item : existingRules.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isBlank() && !RULE_ID.equals(trimmed)) rules.add(trimmed);
                }
            }

            write(kwrite, RULE_ID, "Description", RULE_DESCRIPTION);
            write(kwrite, RULE_ID, "wmclass", "(digital-marathon)|(com-inputactivitytracker-Main)");
            write(kwrite, RULE_ID, "wmclassmatch", "3");
            write(kwrite, RULE_ID, "wmclasscomplete", "false", true);
            write(kwrite, RULE_ID, "excludefromcapture", Boolean.toString(enabled), true);
            write(kwrite, RULE_ID, "excludefromcapturerule", "2");

            String joined = String.join(",", rules);
            runChecked(kwrite, "--file", "kwinrulesrc", "--group", "General", "--key", "rules", joined);
            runChecked(kwrite, "--file", "kwinrulesrc", "--group", "General", "--key", "count", Integer.toString(rules.size()));

            CommandResult reconfigure = run(qdbus, "org.kde.KWin", "/KWin", "reconfigure");
            if (reconfigure.exitCode() != 0) {
                return CapturePrivacySupport.Result.failure(
                        "The KDE privacy rule was saved, but KWin could not reload it: " + reconfigure.output().trim());
            }

            return CapturePrivacySupport.Result.success(enabled
                    ? "KDE Plasma capture exclusion is enabled."
                    : "KDE Plasma capture exclusion is disabled.");
        } catch (Exception error) {
            return CapturePrivacySupport.Result.failure(
                    "KDE Plasma could not update the screen-capture privacy rule: " + safeMessage(error));
        }
    }

    private static void write(String kwrite, String group, String key, String value) throws Exception {
        write(kwrite, group, key, value, false);
    }

    private static void write(String kwrite, String group, String key, String value, boolean boolType) throws Exception {
        List<String> args = new ArrayList<>(List.of(kwrite, "--file", "kwinrulesrc", "--group", group, "--key", key));
        if (boolType) {
            args.add("--type");
            args.add("bool");
        }
        args.add(value);
        runChecked(args.toArray(String[]::new));
    }

    private static boolean isKdeSession() {
        String current = env("XDG_CURRENT_DESKTOP");
        String session = env("DESKTOP_SESSION");
        String kde = env("KDE_FULL_SESSION");
        return current.contains("kde") || current.contains("plasma")
                || session.contains("kde") || session.contains("plasma")
                || "true".equals(kde) || "1".equals(kde);
    }

    private static Version detectPlasmaVersion() {
        for (String commandName : List.of("plasmashell", "kwin_wayland", "kwin_x11")) {
            String command = findCommand(commandName);
            if (command == null) continue;
            try {
                CommandResult result = run(command, "--version");
                Matcher matcher = VERSION_PATTERN.matcher(result.output());
                if (matcher.find()) {
                    return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                            matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String firstCommand(String... names) {
        for (String name : names) {
            String path = findCommand(name);
            if (path != null) return path;
        }
        return null;
    }

    private static String findCommand(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void runChecked(String... command) throws Exception {
        CommandResult result = run(command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + result.output().trim());
        }
    }

    private static CommandResult run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (Exception ignored) {}
        }, "digital-marathon-kde-command-output");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + String.join(" ", command));
        }
        reader.join(500);
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private record CommandResult(int exitCode, String output) {}

    private record Version(int major, int minor, int patch) {
        boolean atLeast(int requiredMajor, int requiredMinor) {
            return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor);
        }

        @Override public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
