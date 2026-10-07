package com.inputactivitytracker;

import javax.swing.*;
import java.awt.*;

final class MetricCard extends RoundedPanel {
    private final JLabel titleLabel;
    private final JLabel valueLabel;
    private final ActivityIcon.Kind iconKind;

    MetricCard(String title, Color tint) {
        this(title, tint, null);
    }

    MetricCard(String title, Color tint, ActivityIcon.Kind iconKind) {
        super(new BorderLayout(0, 8), tint, 14);
        this.iconKind = iconKind;
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleLabel = new JLabel(title);
        titleLabel.setForeground(new Color(101, 112, 134));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        if (iconKind != null) {
            titleLabel.setIcon(new ActivityIcon(iconKind, titleLabel.getForeground(), 15));
            titleLabel.setIconTextGap(6);
        }
        titleRow.add(titleLabel);
        titleRow.add(Box.createHorizontalGlue());

        valueLabel = new JLabel("0");
        valueLabel.setForeground(new Color(23, 32, 51));
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 26f));
        add(titleRow, BorderLayout.NORTH);
        add(valueLabel, BorderLayout.CENTER);
        setPreferredSize(new Dimension(190, 92));
    }

    void setValue(String value) {
        valueLabel.setText(value);
    }

    void applyTheme(Color background, Color border, Color text, Color accent) {
        setPanelColors(background, background);
        titleLabel.setForeground(accent);
        valueLabel.setForeground(accent);
        titleLabel.putClientProperty("fixedForeground", accent);
        valueLabel.putClientProperty("fixedForeground", accent);
        if (iconKind != null) titleLabel.setIcon(new ActivityIcon(iconKind, accent, 15));
    }
}
