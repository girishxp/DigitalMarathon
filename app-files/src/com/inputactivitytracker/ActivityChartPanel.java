package com.inputactivitytracker;

import com.inputactivitytracker.ActivityModels.Group;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

/**
 * Combined bar + trend-line activity chart.
 *
 * The Y axis intentionally uses a 0-100 relative activity index because mouse
 * distance, key presses and clicks have different physical units. Hovering any
 * bar/line interval exposes the original values in the selected distance unit.
 */
final class ActivityChartPanel extends JPanel {
    private List<Group> groups = List.of();
    private Color axisColor = new Color(218, 226, 238);
    private Color labelColor = new Color(101, 112, 134);
    private Color barColor = new Color(104, 159, 211);
    private Color trendColor = new Color(214, 92, 92);
    private UnitConverter.Unit unit = UnitConverter.Unit.MILLIMETRES;
    private double ppi = 96.0;
    private int hoverIndex = -1;
    private Popup detailsPopup;
    private int detailsIndex = -1;

    private static final int LEFT = 54;
    private static final int RIGHT = 18;
    private static final int TOP = 28;
    private static final int BOTTOM = 31;

    ActivityChartPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(820, 155));
        // Chart details belong beside the interval being inspected. Manage
        // their lifetime here rather than using the short toolbar-hint timer,
        // which also dismisses a tooltip when the user clicks its component.
        ToolTipManager.sharedInstance().unregisterComponent(this);
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                inspectInterval(event);
            }

            @Override public void mouseDragged(MouseEvent event) {
                inspectInterval(event);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent event) {
                inspectInterval(event);
            }

            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) inspectInterval(event);
            }

            @Override public void mouseExited(MouseEvent event) {
                clearInspection();
            }
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) {
                clearInspection();
            }
        });
    }

    void setGroups(List<Group> groups) {
        this.groups = groups == null ? List.of() : List.copyOf(groups);
        if (hoverIndex >= this.groups.size()) clearInspection();
        refreshVisibleDetails();
        repaint();
    }

    void setDisplayContext(UnitConverter.Unit unit, double ppi) {
        this.unit = unit == null ? UnitConverter.Unit.MILLIMETRES : unit;
        this.ppi = Math.max(1.0, ppi);
        refreshVisibleDetails();
    }

    void applyTheme(Color axisColor, Color labelColor, Color barColor, Color trendColor) {
        this.axisColor = axisColor;
        this.labelColor = labelColor;
        this.barColor = barColor;
        this.trendColor = trendColor;
        SwingUtilities.invokeLater(this::refreshVisibleDetails);
        repaint();
    }

    @Override public String getToolTipText(MouseEvent event) {
        int index = indexAt(event.getX(), event.getY());
        return detailsText(index);
    }

    @Override public JToolTip createToolTip() {
        JToolTip tooltip = super.createToolTip();
        tooltip.putClientProperty(TranslucentToolTipPopupFactory.DATA_POINT_TOOLTIP, Boolean.TRUE);
        return tooltip;
    }

    private String detailsText(int index) {
        if (index < 0 || index >= groups.size()) return null;
        Group group = groups.get(index);
        double score = activityScores()[index] * 100.0;
        String mouse = UnitConverter.formatDistance(group.totals().mousePixels(), unit, ppi);
        String keys = String.format(Locale.getDefault(), "%,d", group.totals().keyPresses());
        String clicks = String.format(Locale.getDefault(), "%,d", group.totals().mouseClicks());
        String active = UnitConverter.formatDuration(group.totals().activeSeconds());
        return "<html><b>" + html(group.label()) + "</b>"
                + "<br>Mouse distance: " + html(mouse)
                + "<br>Key presses: " + html(keys)
                + "<br>Mouse clicks: " + html(clicks)
                + "<br>Active time: " + html(active)
                + "<br>Relative activity: " + String.format(Locale.getDefault(), "%.0f%%", score)
                + "</html>";
    }

    private void inspectInterval(MouseEvent event) {
        int next = indexAt(event.getX(), event.getY());
        if (next != hoverIndex) {
            hoverIndex = next;
            repaint();
        }
        if (next < 0 || !isShowing()) {
            hideDetails();
            return;
        }
        String text = detailsText(next);
        if (detailsIndex == next
                && TranslucentToolTipPopupFactory.refreshActiveTooltip(this, text)) return;

        hideDetails();
        Point anchor = getLocationOnScreen();
        anchor.translate(event.getX() + 12, event.getY() + 16);
        JToolTip tooltip = createToolTip();
        tooltip.setTipText(text);
        detailsPopup = PopupFactory.getSharedInstance().getPopup(
                this, tooltip, anchor.x, anchor.y);
        detailsIndex = next;
        detailsPopup.show();
    }

    private void refreshVisibleDetails() {
        if (detailsPopup == null || detailsIndex < 0) return;
        String text = detailsText(detailsIndex);
        if (text == null) hideDetails();
        else TranslucentToolTipPopupFactory.refreshActiveTooltip(this, text);
    }

    private void clearInspection() {
        hideDetails();
        if (hoverIndex != -1) {
            hoverIndex = -1;
            repaint();
        }
    }

    private void hideDetails() {
        if (detailsPopup != null) detailsPopup.hide();
        detailsPopup = null;
        detailsIndex = -1;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int plotW = Math.max(1, w - LEFT - RIGHT);
            int plotH = Math.max(1, h - TOP - BOTTOM);
            int baseline = TOP + plotH;

            drawGridAndAxis(g, plotW, plotH, baseline);

            if (groups.isEmpty()) {
                g.setColor(labelColor);
                g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                String text = "No activity in this period";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2);
                return;
            }

            double[] scores = activityScores();
            double slot = plotW / (double) groups.size();
            int barWidth = (int) Math.max(3, Math.min(24, slot * 0.56));
            int[] xs = new int[groups.size()];
            int[] ys = new int[groups.size()];

            for (int i = 0; i < groups.size(); i++) {
                int barHeight = scores[i] <= 0.0 ? 2 : Math.max(3, (int) Math.round(plotH * scores[i]));
                int centerX = LEFT + (int) Math.round((i + 0.5) * slot);
                int x = centerX - barWidth / 2;
                int y = baseline - barHeight;
                xs[i] = centerX;
                ys[i] = y;

                // Inspection emphasizes the coral point only. Keep the bars
                // and plot surface steady while the user follows the line.
                g.setColor(withAlpha(barColor, 135));
                g.fill(new RoundRectangle2D.Double(x, y, barWidth, barHeight, 6, 6));
                g.setColor(withAlpha(barColor, 205));
                g.setStroke(new BasicStroke(1.0f));
                g.draw(new RoundRectangle2D.Double(x, y, barWidth, barHeight, 6, 6));
            }

            // Join the top-centres of the bars so the direction of the trend is
            // instantly visible while the bars still show individual intervals.
            Path2D line = new Path2D.Double();
            for (int i = 0; i < xs.length; i++) {
                if (i == 0) line.moveTo(xs[i], ys[i]);
                else line.lineTo(xs[i], ys[i]);
            }
            // A thin coral/red line is deliberately distinct from the soft blue
            // bars so the direction of change reads quickly without overpowering
            // the interval values.
            g.setColor(withAlpha(trendColor, 238));
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);

            for (int i = 0; i < xs.length; i++) {
                double radius = i == hoverIndex ? 4.0 : 2.7;
                g.setColor(trendColor);
                g.fill(new Ellipse2D.Double(xs[i] - radius, ys[i] - radius, radius * 2, radius * 2));
                g.setColor(withAlpha(Color.WHITE, i == hoverIndex ? 230 : 195));
                double inner = i == hoverIndex ? 1.8 : 1.15;
                g.fill(new Ellipse2D.Double(xs[i] - inner, ys[i] - inner, inner * 2, inner * 2));
            }

            drawXLabels(g, slot, h);

            g.setFont(getFont().deriveFont(Font.PLAIN, 9.5f));
            g.setColor(labelColor);
            String hint = "Hover or click any interval for mouse, keys, clicks and active time";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(hint, Math.max(LEFT, w - RIGHT - fm.stringWidth(hint)), 13);
        } finally {
            g.dispose();
        }
    }

    private void drawGridAndAxis(Graphics2D g, int plotW, int plotH, int baseline) {
        g.setFont(getFont().deriveFont(Font.PLAIN, 9.5f));
        FontMetrics fm = g.getFontMetrics();
        for (int percent = 0; percent <= 100; percent += 25) {
            int y = baseline - (int) Math.round(plotH * (percent / 100.0));
            g.setColor(withAlpha(axisColor, percent == 0 ? 230 : 145));
            g.setStroke(new BasicStroke(percent == 0 ? 1.2f : 0.8f));
            g.drawLine(LEFT, y, LEFT + plotW, y);
            g.setColor(labelColor);
            String label = percent + "%";
            g.drawString(label, LEFT - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        g.setColor(labelColor);
        g.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
        g.drawString("Relative activity", 4, 13);
    }

    private void drawXLabels(Graphics2D g, double slot, int height) {
        int labelCount = Math.min(7, groups.size());
        g.setColor(labelColor);
        g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < labelCount; i++) {
            int index = labelCount == 1 ? 0
                    : (int) Math.round(i * (groups.size() - 1.0) / (labelCount - 1.0));
            String label = groups.get(index).shortLabel();
            int centerX = LEFT + (int) Math.round((index + 0.5) * slot);
            int x = centerX - fm.stringWidth(label) / 2;
            x = Math.max(LEFT, Math.min(getWidth() - RIGHT - fm.stringWidth(label), x));
            g.drawString(label, x, height - 8);
        }
    }

    private double[] activityScores() {
        double maxMouse = 0.0;
        long maxKeys = 0L;
        long maxClicks = 0L;
        for (Group group : groups) {
            maxMouse = Math.max(maxMouse, group.totals().mousePixels());
            maxKeys = Math.max(maxKeys, group.totals().keyPresses());
            maxClicks = Math.max(maxClicks, group.totals().mouseClicks());
        }

        double[] scores = new double[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            double mouseScore = maxMouse > 0 ? group.totals().mousePixels() / maxMouse : 0.0;
            double keyScore = maxKeys > 0 ? group.totals().keyPresses() / (double) maxKeys : 0.0;
            double clickScore = maxClicks > 0 ? group.totals().mouseClicks() / (double) maxClicks : 0.0;
            // Preserve the original chart's meaning: an interval reaches 100%
            // when any one of its activity measures is the maximum in view.
            scores[i] = Math.max(mouseScore, Math.max(keyScore, clickScore));
        }
        return scores;
    }

    private int indexAt(int x, int y) {
        if (groups.isEmpty()) return -1;
        int plotW = Math.max(1, getWidth() - LEFT - RIGHT);
        int plotH = Math.max(1, getHeight() - TOP - BOTTOM);
        int baseline = TOP + plotH;
        if (x < LEFT || x > LEFT + plotW || y < TOP - 8 || y > baseline + 4) return -1;
        double slot = plotW / (double) groups.size();
        int index = (int) Math.floor((x - LEFT) / slot);
        return Math.max(0, Math.min(groups.size() - 1, index));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
