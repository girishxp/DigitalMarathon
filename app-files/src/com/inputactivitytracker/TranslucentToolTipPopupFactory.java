package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

/**
 * Places short control hints outside the dashboard, and chart details beside
 * the data point being inspected.
 * The popup is a separate nonfocusable window, so changing dashboard transparency
 * leaves its short hints fully readable. The historical class name is retained
 * for compatibility with the application's existing tooltip dismissal hooks.
 */
public final class TranslucentToolTipPopupFactory extends PopupFactory {
    static final String DATA_POINT_TOOLTIP = "digitalMarathon.dataPointTooltip";
    private static final int GAP = 8;
    private static boolean installed;
    private static Popup activePopup;
    private static Component activeOwner;
    private static Window activeWindow;
    private final PopupFactory delegate;

    private TranslucentToolTipPopupFactory(PopupFactory delegate) {
        this.delegate = delegate;
    }

    public static synchronized void install() {
        if (installed) return;
        PopupFactory current = PopupFactory.getSharedInstance();
        if (!(current instanceof TranslucentToolTipPopupFactory)) {
            PopupFactory.setSharedInstance(new TranslucentToolTipPopupFactory(current));
        }
        AWTEventListener dismiss = event -> {
            if (activePopup == null) return;
            if (event instanceof MouseEvent mouse) {
                boolean inspectingChart = mouse.getSource() == activeOwner
                        && activePopup instanceof TooltipPopup popup && popup.dataPointTooltip;
                if ((mouse.getID() == MouseEvent.MOUSE_PRESSED && !inspectingChart)
                        || (mouse.getID() == MouseEvent.MOUSE_EXITED
                        && mouse.getSource() == activeOwner)) {
                    hideActiveTooltip();
                }
            } else if (event instanceof WindowEvent window && window.getWindow() == activeWindow) {
                switch (window.getID()) {
                    case WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_ICONIFIED,
                            WindowEvent.WINDOW_CLOSING, WindowEvent.WINDOW_CLOSED -> hideActiveTooltip();
                    default -> { }
                }
            } else if (event instanceof ComponentEvent component && component.getComponent() == activeWindow) {
                switch (component.getID()) {
                    case ComponentEvent.COMPONENT_MOVED, ComponentEvent.COMPONENT_RESIZED,
                            ComponentEvent.COMPONENT_HIDDEN -> hideActiveTooltip();
                    default -> { }
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(dismiss,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
        installed = true;
    }

    public static synchronized void hideActiveTooltip() {
        Popup popup = activePopup;
        activePopup = null;
        activeOwner = null;
        activeWindow = null;
        if (popup != null) popup.hide();
    }

    /** Update chart values in place without restarting or dismissing its bubble. */
    static synchronized boolean refreshActiveTooltip(Component owner, String text) {
        if (activeOwner != owner || !(activePopup instanceof TooltipPopup popup) || !popup.visible) return false;
        popup.refresh(text);
        return true;
    }

    @Override public Popup getPopup(Component owner, Component contents, int x, int y) throws IllegalArgumentException {
        if (!(contents instanceof JToolTip tooltip) || owner == null) {
            return delegate.getPopup(owner, contents, x, y);
        }
        Window ownerWindow = SwingUtilities.getWindowAncestor(owner);
        if (ownerWindow == null || !ownerWindow.isShowing()) {
            return delegate.getPopup(owner, contents, x, y);
        }

        return new TooltipPopup(owner, tooltip, ownerWindow, new Point(x, y));
    }

    private static final class TooltipPopup extends Popup {
        private final Component owner;
        private final JToolTip tooltip;
        private final Window ownerWindow;
        private final JWindow tooltipWindow;
        private final Point requestedLocation;
        private final boolean dataPointTooltip;
        private boolean visible;

        private TooltipPopup(Component owner, JToolTip tooltip, Window ownerWindow, Point requestedLocation) {
            this.owner = owner;
            this.tooltip = tooltip;
            this.ownerWindow = ownerWindow;
            this.requestedLocation = requestedLocation;
            dataPointTooltip = Boolean.TRUE.equals(tooltip.getClientProperty(DATA_POINT_TOOLTIP));
            tooltipWindow = new JWindow(ownerWindow);
            tooltipWindow.setType(Window.Type.POPUP);
            tooltipWindow.setFocusableWindowState(false);
            tooltipWindow.setAutoRequestFocus(false);
            tooltipWindow.setAlwaysOnTop(ownerWindow.isAlwaysOnTop());
            tooltipWindow.getRootPane().putClientProperty("Window.shadow", Boolean.TRUE);
            try {
                tooltipWindow.setBackground(new Color(0, 0, 0, 0));
            } catch (UnsupportedOperationException | IllegalComponentStateException ignored) {
                Color background = UIManager.getColor("ToolTip.background");
                tooltipWindow.setBackground(background == null ? new Color(250, 250, 252) : background);
            }
            tooltipWindow.getContentPane().add(tooltip);
            refresh(tooltip.getTipText());
        }

        private void refresh(String text) {
            if (text != null && !text.equals(tooltip.getTipText())) tooltip.setTipText(text);
            wrapLongHint(tooltip);
            Rectangle usable = usableScreen(owner);
            Dimension preferred = tooltip.getPreferredSize();
            Dimension size = new Dimension(Math.min(Math.max(1, preferred.width), usable.width),
                    Math.min(Math.max(1, preferred.height), usable.height));
            Point anchor = owner.getLocationOnScreen();
            anchor.translate(owner.getWidth() / 2, owner.getHeight() / 2);
            Point location = dataPointTooltip
                    ? findDataPointLocation(requestedLocation, size, usable)
                    : findLocation(ownerWindow.getBounds(), anchor, size, usable);
            Rectangle bounds = new Rectangle(location, size);
            if (!tooltipWindow.getBounds().equals(bounds)) tooltipWindow.setBounds(bounds);
            tooltip.repaint();
        }

        @Override public void show() {
            if (visible || !owner.isShowing()) return;
            hideActiveTooltip();
            synchronized (TranslucentToolTipPopupFactory.class) {
                activePopup = this;
                activeOwner = owner;
                activeWindow = ownerWindow;
            }
            visible = true;
            // No title is assigned: the dashboard's native opacity helper
            // targets its titled windows and leaves this bubble opaque.
            // WINDOW_OPENED also invokes the app's existing capture-privacy
            // hook, which protects all visible app windows when enabled.
            tooltipWindow.setVisible(true);
        }

        @Override public void hide() {
            visible = false;
            synchronized (TranslucentToolTipPopupFactory.class) {
                if (activePopup == this) {
                    activePopup = null;
                    activeOwner = null;
                    activeWindow = null;
                }
            }
            tooltipWindow.setVisible(false);
            tooltipWindow.dispose();
        }
    }

    /** Keep the chart bubble close to its point, including at screen edges. */
    static Point findDataPointLocation(Point requested, Dimension size, Rectangle screen) {
        int right = screen.x + screen.width;
        int bottom = screen.y + screen.height;
        int x = requested.x + size.width > right ? requested.x - size.width - 24 : requested.x;
        int y = requested.y + size.height > bottom ? requested.y - size.height - 24 : requested.y;
        return new Point(clamp(x, screen.x, right - size.width),
                clamp(y, screen.y, bottom - size.height));
    }

    /** Prefer above the entire window, then below or alongside it. */
    static Point findLocation(Rectangle owner, Point anchor, Dimension size, Rectangle screen) {
        int x = clamp(anchor.x - size.width / 2, screen.x, screen.x + screen.width - size.width);
        if (owner.y - GAP - size.height >= screen.y) {
            return new Point(x, clamp(owner.y - GAP - size.height, screen.y,
                    screen.y + screen.height - size.height));
        }
        if (owner.y + owner.height + GAP + size.height <= screen.y + screen.height) {
            return new Point(x, clamp(owner.y + owner.height + GAP, screen.y,
                    screen.y + screen.height - size.height));
        }
        int y = clamp(anchor.y - size.height / 2, screen.y, screen.y + screen.height - size.height);
        if (owner.x - GAP - size.width >= screen.x) {
            return new Point(clamp(owner.x - GAP - size.width, screen.x,
                    screen.x + screen.width - size.width), y);
        }
        if (owner.x + owner.width + GAP + size.width <= screen.x + screen.width) {
            return new Point(clamp(owner.x + owner.width + GAP, screen.x,
                    screen.x + screen.width - size.width), y);
        }
        // A maximized window can occupy all available screen space. Keep the
        // hint on screen, near its control, when no outside position exists.
        return new Point(x, clamp(anchor.y + GAP, screen.y, screen.y + screen.height - size.height));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Rectangle usableScreen(Component owner) {
        GraphicsConfiguration configuration = owner.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width = Math.max(1, bounds.width - insets.left - insets.right);
        bounds.height = Math.max(1, bounds.height - insets.top - insets.bottom);
        return bounds;
    }

    private static void wrapLongHint(JToolTip tooltip) {
        String text = tooltip.getTipText();
        if (text == null || text.startsWith("<html>")) return;
        if (tooltip.getFontMetrics(tooltip.getFont()).stringWidth(text) <= 320) return;
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        tooltip.setTipText("<html><div style='width: 300px'>" + escaped + "</div></html>");
    }
}
