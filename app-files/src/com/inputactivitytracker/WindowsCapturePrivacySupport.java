package com.inputactivitytracker;

import java.awt.Component;
import java.awt.Window;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Windows implementation backed by SetWindowDisplayAffinity. */
final class WindowsCapturePrivacySupport {
    private static final int WDA_NONE = 0x00000000;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    private static final Field PEER_FIELD;
    private static final MethodHandle SET_WINDOW_DISPLAY_AFFINITY;

    static {
        try {
            PEER_FIELD = Component.class.getDeclaredField("peer");
            PEER_FIELD.setAccessible(true);

            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            MemorySegment function = user32.find("SetWindowDisplayAffinity")
                    .orElseThrow(() -> new UnsatisfiedLinkError("SetWindowDisplayAffinity was not found in user32.dll"));
            SET_WINDOW_DISPLAY_AFFINITY = linker.downcallHandle(
                    function,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
        } catch (Throwable error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private WindowsCapturePrivacySupport() {}

    /**
     * @return null on success; otherwise a short user-facing error detail.
     */
    static String apply(boolean enabled) {
        int affinity = enabled ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
        int matched = 0;
        int failed = 0;
        String firstFailure = null;

        for (Window window : Window.getWindows()) {
            if (window == null || !window.isDisplayable() || !window.isVisible()) continue;
            try {
                long hwnd = windowHandle(window);
                if (hwnd == 0L) continue;
                matched++;
                int success = (int) SET_WINDOW_DISPLAY_AFFINITY.invoke(
                        MemorySegment.ofAddress(hwnd), affinity);
                if (success == 0) {
                    failed++;
                    if (firstFailure == null) firstFailure = "SetWindowDisplayAffinity returned FALSE";
                }
            } catch (Throwable error) {
                failed++;
                if (firstFailure == null) firstFailure = safeMessage(error);
            }
        }

        if (matched == 0) {
            return "Windows could not find a visible Digital Marathon window to protect.";
        }
        if (failed > 0) {
            return "Windows could not apply capture privacy to " + failed + " of " + matched
                    + " app window(s)" + (firstFailure == null ? "." : ": " + firstFailure);
        }
        return null;
    }

    private static long windowHandle(Window window) throws Exception {
        Object peer = PEER_FIELD.get(window);
        if (peer == null) return 0L;

        Method getHWnd = null;
        Class<?> type = peer.getClass();
        while (type != null && getHWnd == null) {
            try {
                getHWnd = type.getDeclaredMethod("getHWnd");
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        if (getHWnd == null) return 0L;
        getHWnd.setAccessible(true);
        Object value = getHWnd.invoke(peer);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
