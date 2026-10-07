package com.inputactivitytracker;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Applies uniform opacity without forcing the application to remove native
 * window decorations. The public AWT API rejects translucent decorated Frames,
 * although each platform peer can apply native window alpha directly.
 */
final class WindowOpacitySupport {
    record Result(boolean success, String detail) {}

    private WindowOpacitySupport() {}

    static Result apply(Window window, float opacity) {
        if (window == null) return new Result(false, "Window is not available");
        float value = Math.max(0.25f, Math.min(1.0f, opacity));

        // On macOS, apply alpha through AppKit. This is the supported native
        // path for a decorated NSWindow and avoids AWT's decorated-Frame guard.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                && MacNativeInputBackend.applyNativeWindowOpacity(window, value)) {
            // AppKit composites uniform alpha itself. Repainting and flushing
            // the complete Swing surface per pointer movement adds avoidable
            // latency and is not required to apply the new transparency.
            return new Result(true, "Native macOS NSWindow alpha");
        }

        // Installed builds open the relevant java.desktop packages so this
        // peer call can retain the normal macOS/Windows/Linux title bar.
        Result peerResult = applyThroughPeer(window, value);
        if (peerResult.success()) return peerResult;

        // Public API fallback for undecorated windows and platforms where it is
        // supported directly.
        try {
            window.setOpacity(value);
            return new Result(true, "AWT window opacity");
        } catch (UnsupportedOperationException | IllegalArgumentException |
                 IllegalComponentStateException | SecurityException error) {
            String detail = safeMessage(error);
            if (peerResult.detail() != null && !peerResult.detail().isBlank()) {
                detail = peerResult.detail() + "; " + detail;
            }
            return new Result(false, detail);
        }
    }

    private static Result applyThroughPeer(Window window, float opacity) {
        try {
            Field peerField = Component.class.getDeclaredField("peer");
            peerField.setAccessible(true);
            Object peer = peerField.get(window);
            if (peer == null) return new Result(false, "Native window is not ready yet");

            Method method = findMethod(peer.getClass(), "setOpacity", float.class);
            if (method == null) return new Result(false, "Native window opacity method was not found");
            method.setAccessible(true);
            method.invoke(peer, opacity);
            return new Result(true, "Native decorated-window opacity");
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException error) {
            return new Result(false, safeMessage(error));
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return new Result(false, safeMessage(cause));
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown opacity error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
