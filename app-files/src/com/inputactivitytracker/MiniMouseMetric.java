package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;

/** Compact, visibly divided pointer summary used by Mini View. */
final class MiniMouseMetric extends RoundedPanel {
    private final JLabel mouseTitle;
    private final JLabel clicksTitle;
    private final JLabel distanceLabel;
    private final JLabel clicksLabel;
    private final Divider divider;
    private final Font distanceBaseFont;
    private final Font clicksBaseFont;
    private final int availableDistanceWidth;
    private final int availableClicksWidth;

    MiniMouseMetric(Color tint, int width) {
        super(new BorderLayout(), tint, 14);
        setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 6));

        int clickWidth = 42;
        int dividerWidth = 2;
        int horizontalGaps = 10;
        int distanceWidth = Math.max(86, width - 13 - clickWidth - dividerWidth - horizontalGaps);

        mouseTitle = new JLabel("MOUSE");
        mouseTitle.setForeground(new Color(82, 133, 184));
        mouseTitle.setFont(mouseTitle.getFont().deriveFont(Font.PLAIN, 7.3f));
        mouseTitle.setIcon(new ActivityIcon(ActivityIcon.Kind.MOUSE, mouseTitle.getForeground(), 10, 0.85f));
        mouseTitle.setIconTextGap(3);

        clicksTitle = new JLabel("CLICKS", SwingConstants.CENTER);
        clicksTitle.setForeground(new Color(174, 116, 72));
        clicksTitle.setFont(clicksTitle.getFont().deriveFont(Font.PLAIN, 7.2f));
        clicksTitle.setIcon(new ActivityIcon(ActivityIcon.Kind.CLICK, clicksTitle.getForeground(), 10, 0.85f));
        clicksTitle.setIconTextGap(2);

        distanceBaseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(11.6f);
        distanceLabel = new JLabel("0 mm");
        distanceLabel.setForeground(new Color(38, 50, 73));
        distanceLabel.setFont(distanceBaseFont);

        clicksBaseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(11.6f);
        clicksLabel = new JLabel("0", SwingConstants.CENTER);
        clicksLabel.setForeground(new Color(38, 50, 73));
        clicksLabel.setFont(clicksBaseFont);

        JPanel mouseColumn = column(mouseTitle, distanceLabel, distanceWidth);
        JPanel clickColumn = column(clicksTitle, clicksLabel, clickWidth);

        divider = new Divider(new Color(190, 204, 220));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.X_AXIS));
        content.add(mouseColumn);
        content.add(Box.createHorizontalStrut(4));
        content.add(divider);
        content.add(Box.createHorizontalStrut(4));
        content.add(clickColumn);
        add(content, BorderLayout.CENTER);

        setPreferredSize(new Dimension(width, 36));
        setMinimumSize(new Dimension(width, 36));
        setMaximumSize(new Dimension(width, 36));
        availableDistanceWidth = distanceWidth;
        availableClicksWidth = clickWidth;
    }

    private static JPanel column(JLabel title, JLabel value, int width) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setOpaque(false);
        panel.add(title, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(width, 30));
        panel.setMinimumSize(new Dimension(width, 30));
        panel.setMaximumSize(new Dimension(width, 30));
        return panel;
    }

    void setValues(String distance, String clicks) {
        distanceLabel.setText(distance);
        clicksLabel.setText(clicks);
        fitLabel(distanceLabel, distanceBaseFont, distance, availableDistanceWidth, 9.5f);
        fitLabel(clicksLabel, clicksBaseFont, clicks, availableClicksWidth, 10.0f);
    }

    private static void fitLabel(JLabel label, Font base, String value, int width, float minimum) {
        float size = base.getSize2D();
        Font fitted = base;
        while (size > minimum && label.getFontMetrics(fitted).stringWidth(value) > width) {
            size -= 0.5f;
            fitted = base.deriveFont(size);
        }
        label.setFont(fitted);
    }

    void applyTheme(Color background, Color border, Color text, Color mouseAccent, Color clickAccent, Color ignoredPillBackground) {
        setPanelColors(background, border);
        mouseTitle.setForeground(mouseAccent);
        mouseTitle.setIcon(new ActivityIcon(ActivityIcon.Kind.MOUSE, mouseAccent, 10, 0.85f));
        clicksTitle.setForeground(clickAccent);
        clicksTitle.setIcon(new ActivityIcon(ActivityIcon.Kind.CLICK, clickAccent, 10, 0.85f));
        distanceLabel.setForeground(text);
        clicksLabel.setForeground(text);
        divider.setLineColor(border);
    }

    /** A painted divider is more reliable than the platform JSeparator on macOS. */
    private static final class Divider extends JComponent {
        private Color lineColor;

        private Divider(Color lineColor) {
            this.lineColor = lineColor;
            setOpaque(false);
            Dimension size = new Dimension(2, 30);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        private void setLineColor(Color lineColor) {
            this.lineColor = lineColor;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(lineColor);
                g.fillRoundRect(0, 1, 2, Math.max(1, getHeight() - 2), 2, 2);
            } finally {
                g.dispose();
            }
        }
    }
}
