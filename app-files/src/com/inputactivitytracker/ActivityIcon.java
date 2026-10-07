package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/** Small vector icons used by the activity cards in Full and Mini views. */
final class ActivityIcon implements Icon {
    enum Kind { MOUSE, KEYBOARD, CLICK, CLOCK }

    private final Kind kind;
    private final Color color;
    private final int size;
    private final float strokeWidth;

    ActivityIcon(Kind kind, Color color, int size) {
        this(kind, color, size, 1.45f);
    }

    ActivityIcon(Kind kind, Color color, int size, float strokeWidth) {
        this.kind = kind;
        this.color = color;
        this.size = Math.max(10, size);
        this.strokeWidth = Math.max(0.75f, strokeWidth);
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            double scale = size / 16.0;
            g.scale(scale, scale);
            g.setColor(color);
            g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case MOUSE -> paintMouse(g, false);
                case CLICK -> paintMouse(g, true);
                case KEYBOARD -> paintKeyboard(g);
                case CLOCK -> {
                    g.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
                    g.draw(new Line2D.Double(8, 4, 8, 8));
                    g.draw(new Line2D.Double(8, 8, 11, 9.5));
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static void paintMouse(Graphics2D g, boolean clickAccent) {
        g.draw(new RoundRectangle2D.Double(4.1, 1.4, 7.8, 13.0, 3.9, 3.9));
        g.draw(new Line2D.Double(8, 1.8, 8, 6.1));
        g.draw(new Line2D.Double(4.5, 6.0, 11.5, 6.0));
        if (clickAccent) {
            g.fill(new Ellipse2D.Double(6.9, 3.0, 2.2, 2.2));
            g.draw(new Line2D.Double(12.7, 1.5, 14.5, 0.6));
            g.draw(new Line2D.Double(13.2, 4.0, 15.3, 4.0));
            g.draw(new Line2D.Double(12.6, 6.4, 14.2, 7.4));
        }
    }

    private static void paintKeyboard(Graphics2D g) {
        g.drawRoundRect(1, 3, 14, 10, 2, 2);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                double px = 2.5 + col * 2.25;
                double py = 4.6 + row * 2.35;
                g.fill(new RoundRectangle2D.Double(px, py, 1.35, 1.15, 0.35, 0.35));
            }
        }
        g.fill(new RoundRectangle2D.Double(4.0, 9.4, 8.0, 1.25, 0.5, 0.5));
    }
}
