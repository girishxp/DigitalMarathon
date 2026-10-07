package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;

final class MiniMetric extends RoundedPanel {
    private final JLabel titleLabel;
    private final JLabel valueLabel;
    private final Font valueBaseFont;
    private final int availableTextWidth;
    private final ActivityIcon.Kind iconKind;

    MiniMetric(String title, Color tint, int width) {
        this(title, tint, width, null);
    }

    MiniMetric(String title, Color tint, int width, ActivityIcon.Kind iconKind) {
        super(new BorderLayout(0, 1), tint, 14);
        this.iconKind = iconKind;
        setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        titleLabel = new JLabel(title.toUpperCase());
        titleLabel.setForeground(new Color(75, 142, 108));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 7.3f));
        if (iconKind != null) {
            titleLabel.setIcon(new ActivityIcon(iconKind, titleLabel.getForeground(), 10, 0.85f));
            titleLabel.setIconTextGap(4);
        }
        valueBaseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(11.6f);
        valueLabel = new JLabel("0");
        valueLabel.setForeground(new Color(23, 32, 51));
        valueLabel.setFont(valueBaseFont);
        add(titleLabel, BorderLayout.NORTH);
        add(valueLabel, BorderLayout.CENTER);
        setPreferredSize(new Dimension(width, 36));
        setMinimumSize(new Dimension(width, 36));
        setMaximumSize(new Dimension(width, 36));
        availableTextWidth = Math.max(28, width - 16);
    }

    void setValue(String value) {
        valueLabel.setText(value);
        float size = valueBaseFont.getSize2D();
        Font fitted = valueBaseFont;
        while (size > 10.5f && valueLabel.getFontMetrics(fitted).stringWidth(value) > availableTextWidth) {
            size -= 0.5f;
            fitted = valueBaseFont.deriveFont(size);
        }
        valueLabel.setFont(fitted);
    }

    void applyTheme(Color background, Color border, Color text, Color accent) {
        setPanelColors(background, border);
        titleLabel.setForeground(accent);
        valueLabel.setForeground(text);
        if (iconKind != null) titleLabel.setIcon(new ActivityIcon(iconKind, accent, 10, 0.85f));
    }
}
