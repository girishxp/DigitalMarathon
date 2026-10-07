package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Window movement from noninteractive header and Mini View footer surfaces. */
final class WindowDragSupport {
    static final String NO_WINDOW_DRAG = "digitalmarathon.noWindowDrag";

    private WindowDragSupport() {}

    static void install(JFrame window, BooleanSupplier miniMode, BooleanSupplier dragAllowed) {
        AWTEventListener drag = new AWTEventListener() {
            private Point pressPoint;
            private Point windowPoint;

            @Override public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent mouse)) return;
                if (mouse.getID() == MouseEvent.MOUSE_RELEASED) {
                    pressPoint = null;
                    windowPoint = null;
                    return;
                }
                if (mouse.getID() == MouseEvent.MOUSE_PRESSED) {
                    pressPoint = null;
                    windowPoint = null;
                    if (!SwingUtilities.isLeftMouseButton(mouse) || !dragAllowed.getAsBoolean()) return;
                    if (!(mouse.getSource() instanceof Component source)) return;
                    if (source != window && SwingUtilities.getWindowAncestor(source) != window) return;
                    // Lightweight labels/glue without their own mouse listeners
                    // may deliver events to the root pane or JFrame. Hit-test
                    // the pointer against the actual content hierarchy instead
                    // of assuming the event source is the visual target.
                    // Mouse-local coordinates remain correct even when a
                    // display's absolute event origin differs from AWT's
                    // window origin (for example after display reconfiguration).
                    Point contentPoint = SwingUtilities.convertPoint(source,
                            mouse.getPoint(), window.getContentPane());
                    Component target = SwingUtilities.getDeepestComponentAt(window.getContentPane(),
                            contentPoint.x, contentPoint.y);
                    if (target == null || !isDragRegion(window.getContentPane(), target, miniMode.getAsBoolean())) return;
                    TranslucentToolTipPopupFactory.hideActiveTooltip();
                    pressPoint = mouse.getLocationOnScreen();
                    windowPoint = window.getLocation();
                    mouse.consume();
                } else if (mouse.getID() == MouseEvent.MOUSE_DRAGGED && pressPoint != null) {
                    if (!window.isShowing() || !dragAllowed.getAsBoolean()) return;
                    if ((mouse.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
                    Point pointer = mouse.getLocationOnScreen();
                    window.setLocation(windowPoint.x + pointer.x - pressPoint.x,
                            windowPoint.y + pointer.y - pressPoint.y);
                    mouse.consume();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(drag,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { configureNativeDragging(); }

            @Override public void windowActivated(WindowEvent event) { configureNativeDragging(); }

            @Override public void windowClosed(WindowEvent event) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(drag);
            }

            private void configureNativeDragging() {
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                    MacNativeInputBackend.configureNativeWindowDragging(window);
                }
            }
        });
    }

    static boolean isDragRegion(Container content, Component source, boolean miniMode) {
        Component header = null;
        Component footer = null;
        if (miniMode && content.getComponentCount() > 0) {
            header = content.getComponent(0);
            footer = content.getComponent(content.getComponentCount() - 1);
        } else if (content.getLayout() instanceof BorderLayout layout) {
            Component north = layout.getLayoutComponent(BorderLayout.NORTH);
            if (north instanceof Container heading && heading.getComponentCount() > 0) {
                header = heading.getComponent(0);
            }
        }
        if (!within(source, header) && !within(source, footer)) return false;
        for (Component component = source; component != null && component != content;
             component = component.getParent()) {
            if (component instanceof JComponent swing
                    && Boolean.TRUE.equals(swing.getClientProperty(NO_WINDOW_DRAG))) return false;
            if (component instanceof AbstractButton || component instanceof JSlider
                    || component instanceof JComboBox<?> || component instanceof JSpinner
                    || component instanceof JTextComponent || component instanceof JScrollBar
                    || component instanceof JList<?> || component instanceof JTable) return false;
        }
        return true;
    }

    private static boolean within(Component source, Component region) {
        return region != null && (source == region || SwingUtilities.isDescendingFrom(source, region));
    }
}
