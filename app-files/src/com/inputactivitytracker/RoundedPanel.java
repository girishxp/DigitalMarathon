package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;

class RoundedPanel extends JPanel {
    private Color backgroundColor;
    private final int radius;
    private Color borderColor;

    RoundedPanel(LayoutManager layout, Color backgroundColor, int radius) {
        super(layout);
        this.backgroundColor = backgroundColor;
        this.radius = radius;
        this.borderColor = new Color(221, 228, 238);
        setOpaque(false);
    }

    void setPanelColors(Color backgroundColor, Color borderColor) {
        this.backgroundColor = backgroundColor;
        this.borderColor = borderColor;
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(backgroundColor);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g.setColor(borderColor);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }
}
