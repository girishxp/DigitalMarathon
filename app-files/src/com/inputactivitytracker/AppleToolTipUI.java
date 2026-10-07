package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicToolTipUI;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** A small, solid tooltip surface that remains readable in either appearance. */
public final class AppleToolTipUI extends BasicToolTipUI {
    private static final AppleToolTipUI INSTANCE = new AppleToolTipUI();

    public static ComponentUI createUI(JComponent component) { return INSTANCE; }

    @Override public void installUI(JComponent component) {
        super.installUI(component);
        component.setOpaque(false);
        component.setBorder(new EmptyBorder(7, 10, 7, 10));
    }

    @Override public void paint(Graphics graphics, JComponent component) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color background = UIManager.getColor("ToolTip.background");
            Color foreground = UIManager.getColor("ToolTip.foreground");
            Color border = UIManager.getColor("ToolTip.outline");
            if (background == null) background = new Color(250, 250, 252);
            if (foreground == null) foreground = new Color(29, 29, 31);
            if (border == null) border = new Color(210, 210, 215);
            component.setForeground(foreground);
            g.setColor(new Color(background.getRed(), background.getGreen(), background.getBlue()));
            g.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
            g.setColor(border);
            g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
        } finally {
            g.dispose();
        }
        super.paint(graphics, component);
    }
}
