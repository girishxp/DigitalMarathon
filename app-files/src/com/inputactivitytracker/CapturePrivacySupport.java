package com.inputactivitytracker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/** Platform dispatcher for the optional screen-capture privacy control. */
final class CapturePrivacySupport {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean IS_MACOS = OS.contains("mac");
    private static final boolean IS_WINDOWS = OS.contains("win");
    private static final boolean IS_LINUX = OS.contains("linux");

    record Result(boolean success, boolean supported, String detail) {
        static Result success(String detail) {
            return new Result(true, true, detail == null ? "" : detail);
        }

        static Result unsupported(String detail) {
            return new Result(false, false, detail == null ? "" : detail);
        }

        static Result failure(String detail) {
            return new Result(false, true, detail == null ? "" : detail);
        }
    }

    private CapturePrivacySupport() {}

    static boolean isPotentiallyAvailable() {
        return IS_MACOS || IS_WINDOWS || IS_LINUX;
    }

    static Result apply(boolean enabled) {
        if (IS_MACOS) {
            boolean applied = MacNativeInputBackend.applyNativeWindowCapturePrivacy(enabled);
            return applied
                    ? Result.success(enabled
                            ? "macOS window capture privacy is enabled."
                            : "macOS window capture privacy is disabled.")
                    : Result.failure("macOS could not change the window sharing policy right now.");
        }

        if (IS_WINDOWS) {
            return applyWindows(enabled);
        }

        if (IS_LINUX) {
            return LinuxCapturePrivacySupport.apply(enabled);
        }

        if (!enabled) return Result.success("Capture privacy is off.");
        return Result.unsupported("Screen Capture Privacy is not available on this operating system.");
    }

    /*
     * The Windows implementation uses the Java 21 Foreign Function API, which
     * is a preview API in Java 21. Loading it reflectively keeps macOS/Linux
     * launchers free of any preview-runtime requirement.
     */
    private static Result applyWindows(boolean enabled) {
        try {
            Class<?> implementation = Class.forName("com.inputactivitytracker.WindowsCapturePrivacySupport");
            Method method = implementation.getDeclaredMethod("apply", boolean.class);
            method.setAccessible(true);
            Object result = method.invoke(null, enabled);
            String error = result instanceof String ? (String) result : null;
            return error == null || error.isBlank()
                    ? Result.success(enabled
                            ? "Windows capture exclusion is enabled."
                            : "Windows capture exclusion is disabled.")
                    : Result.failure(error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return Result.failure("Windows could not change capture privacy: " + safeMessage(cause));
        } catch (Throwable error) {
            return Result.failure("Windows capture privacy could not start: " + safeMessage(error));
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
