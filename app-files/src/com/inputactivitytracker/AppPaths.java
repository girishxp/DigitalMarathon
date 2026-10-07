package com.inputactivitytracker;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

final class AppPaths {
    private AppPaths() {}

    static Path dataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        Path preferred;
        Path legacy;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path roaming = appData != null && !appData.isBlank()
                    ? Paths.get(appData)
                    : Paths.get(home, "AppData", "Roaming");
            preferred = roaming.resolve("DigitalMarathon");
            legacy = roaming.resolve("InputActivityTracker");
        } else {
            preferred = Paths.get(home, ".digital_marathon");
            legacy = Paths.get(home, ".input_activity_tracker");
        }
        migrateLegacyData(preferred, legacy);
        return preferred;
    }

    private static void migrateLegacyData(Path preferred, Path legacy) {
        if (Files.exists(preferred) || !Files.isDirectory(legacy)) return;
        try {
            Files.createDirectories(preferred);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacy)) {
                for (Path source : stream) {
                    if (!Files.isRegularFile(source)) continue;
                    Files.copy(source, preferred.resolve(source.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException ignored) {
            // A failed migration never prevents startup; the new directory will
            // be created by HistoryStore and the legacy data remains untouched.
        }
    }
}
