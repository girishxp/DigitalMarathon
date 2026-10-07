package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTextAreaUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;

/** Small, themed notices whose size never depends on a filesystem path. */
final class ThemeDialog {
    private ThemeDialog() {}

    private record Style(Color page, Color surface, Color text, Color muted,
                         Color border, Color accent, Color selection, boolean dark) {
        private static Style forTheme(boolean dark) {
            return dark
                    ? new Style(new Color(28, 28, 30), new Color(44, 44, 46),
                            new Color(242, 242, 247), new Color(174, 174, 180),
                            new Color(72, 72, 74), new Color(10, 132, 255),
                            new Color(38, 69, 96), true)
                    : new Style(new Color(245, 245, 247), Color.WHITE,
                            new Color(29, 29, 31), new Color(110, 110, 115),
                            new Color(209, 209, 214), new Color(0, 122, 255),
                            new Color(224, 240, 255), false);
        }
    }

    static void certificateSaved(Component parent, boolean dark, Path file) {
        Path absoluteFile = file.toAbsolutePath().normalize();
        JDialog dialog = dialog(parent, "Certificate saved", dark);
        JPanel content = certificateSavedContent(dialog, dark, absoluteFile);
        show(dialog, content, 448, parent);
    }

    static void message(Component parent, boolean dark, String title, String body) {
        JDialog dialog = dialog(parent, title, dark);
        Style style = Style.forTheme(dark);
        JPanel content = root(style);
        addHeading(content, title, style);
        content.add(Box.createVerticalStrut(10));
        content.add(paragraph(body, style, style.text(), 4, 400));
        content.add(Box.createVerticalStrut(20));
        JButton done = button("Done", style, true);
        done.addActionListener(event -> dialog.dispose());
        content.add(buttonRow(done));
        dialog.getRootPane().setDefaultButton(done);
        show(dialog, content, 440, parent);
    }

    static void permissions(Component parent, boolean dark, String platform,
                            Runnable inputMonitoring, Runnable accessibility) {
        JDialog dialog = dialog(parent, "Input permissions", dark);
        JPanel content = permissionsContent(dialog, dark, platform, inputMonitoring, accessibility);
        show(dialog, content, 460, parent);
    }

    // Package visibility also lets layout verification inspect these panels without
    // showing a window or opening files, Settings, or the system clipboard.
    static JPanel certificateSavedContent(JDialog dialog, boolean dark, Path file) {
        Style style = Style.forTheme(dark);
        JPanel content = root(style);
        addHeading(content, "Certificate saved", style);
        content.add(Box.createVerticalStrut(5));
        content.add(paragraph("Your JPEG is ready in " + folderName(file) + ".",
                style, style.muted(), 1, 408));
        content.add(Box.createVerticalStrut(14));

        JPanel fileCard = new RoundedSurface(style);
        fileCard.setLayout(new BorderLayout());
        fileCard.setBorder(new EmptyBorder(10, 12, 10, 12));
        fileCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        fileCard.setPreferredSize(new Dimension(408, 62));
        String fullName = file.getFileName().toString();
        JTextArea filename = paragraph(abbreviate(fullName, 100), style, style.text(), 2, 384);
        filename.setToolTipText("Saved in " + folderName(file)
                + ". Use Copy file location for the complete path.");
        filename.getAccessibleContext().setAccessibleName("Saved certificate filename");
        filename.getAccessibleContext().setAccessibleDescription(file.toString());
        fileCard.add(filename, BorderLayout.CENTER);
        content.add(fileCard);
        content.add(Box.createVerticalStrut(8));

        JButton copy = linkButton("Copy file location", style);
        copy.getAccessibleContext().setAccessibleDescription("Copy the complete saved certificate path to the clipboard");
        copy.addActionListener(event -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(file.toString()), null);
                copy.setText("Location copied");
            } catch (IllegalStateException | SecurityException error) {
                copy.setText("Could not copy location");
                copy.setToolTipText(file.toString());
            }
        });
        content.add(copy);
        content.add(Box.createVerticalStrut(17));

        JButton open = button("Open Certificate", style, true);
        JButton folder = button("Show in Folder", style, false);
        JButton done = button("Done", style, false);
        open.setEnabled(desktopSupports(Desktop.Action.OPEN));
        folder.setEnabled(desktopSupports(Desktop.Action.BROWSE_FILE_DIR)
                || desktopSupports(Desktop.Action.OPEN));
        open.addActionListener(event -> {
            try {
                Desktop.getDesktop().open(file.toFile());
            } catch (IOException | RuntimeException error) {
                message(dialog, dark, "Could not open certificate",
                        "Your certificate is saved. Open it from " + folderName(file) + ".");
            }
        });
        folder.addActionListener(event -> {
            try {
                if (desktopSupports(Desktop.Action.BROWSE_FILE_DIR)) {
                    Desktop.getDesktop().browseFileDirectory(file.toFile());
                } else {
                    Desktop.getDesktop().open(file.getParent().toFile());
                }
            } catch (IOException | RuntimeException error) {
                message(dialog, dark, "Could not open folder",
                        "Your certificate is saved. Use Copy file location to find it.");
            }
        });
        done.addActionListener(event -> { if (dialog != null) dialog.dispose(); });
        content.add(buttonRow(open, folder, done));
        if (dialog != null) dialog.getRootPane().setDefaultButton(done);
        return content;
    }

    static JPanel permissionsContent(JDialog dialog, boolean dark, String platform,
                                     Runnable inputMonitoring, Runnable accessibility) {
        Style style = Style.forTheme(dark);
        JPanel content = root(style);
        addHeading(content, "Input permissions", style);
        content.add(Box.createVerticalStrut(6));
        boolean mac = "mac".equals(platform);
        boolean linux = "linux".equals(platform);
        content.add(paragraph(mac ? "Allow keyboard counting on your Mac."
                        : linux ? "Keep keyboard and mouse counting working."
                        : "Windows is usually ready to count input.",
                style, style.muted(), 1, 420));
        content.add(Box.createVerticalStrut(16));

        JPanel instructions = new RoundedSurface(style);
        instructions.setLayout(new BorderLayout());
        instructions.setBorder(new EmptyBorder(12, 13, 12, 13));
        instructions.setAlignmentX(Component.LEFT_ALIGNMENT);
        String steps = mac
                ? "1. Open Input Monitoring.\n2. Turn on Digital Marathon for this app copy.\n3. Return here and check that keys are counting."
                : linux
                ? "On X11, no extra setup is usually needed.\n\nOn Wayland, follow LINUX-WAYLAND.md in the app folder to enable input access, then sign out and back in."
                : "If security software asks, allow Digital Marathon to monitor input activity.\n\nThe app does not need administrator access for normal use.";
        int rows = mac ? 4 : 6;
        instructions.add(paragraph(steps, style, style.text(), rows, 394));
        instructions.setPreferredSize(new Dimension(420, mac ? 94 : 120));
        instructions.setMaximumSize(new Dimension(Integer.MAX_VALUE, mac ? 94 : 120));
        content.add(instructions);
        content.add(Box.createVerticalStrut(13));
        if (mac) {
            content.add(paragraph("Keys still at zero? Turn the permission off and on once. Enable Accessibility too if macOS requests it. More recovery steps are in Help → Troubleshooting.",
                    style, style.muted(), 3, 420));
            content.add(Box.createVerticalStrut(12));
        }
        content.add(paragraph("Only input counts are saved. Typed text and key identities are never stored.",
                style, style.muted(), 2, 420));
        content.add(Box.createVerticalStrut(20));

        JButton close = button("Close", style, false);
        close.addActionListener(event -> { if (dialog != null) dialog.dispose(); });
        if (mac) {
            JButton input = button("Open Input Monitoring", style, true);
            input.setFont(input.getFont().deriveFont(11f));
            input.addActionListener(event -> inputMonitoring.run());
            JButton access = button("Open Accessibility", style, false);
            access.setFont(access.getFont().deriveFont(11f));
            access.addActionListener(event -> accessibility.run());
            content.add(buttonRow(input, access, close));
        } else {
            content.add(buttonRow(close));
        }
        if (dialog != null) dialog.getRootPane().setDefaultButton(close);
        return content;
    }

    private static JDialog dialog(Component parent, String title, boolean dark) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.DOCUMENT_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        if (owner != null) dialog.setIconImages(owner.getIconImages());
        dialog.getRootPane().putClientProperty("apple.awt.windowAppearance",
                dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        dialog.getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        dialog.getRootPane().putClientProperty("digitalMarathon.darkTheme", dark);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        return dialog;
    }

    private static JPanel root(Style style) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(style.page());
        root.setBorder(new EmptyBorder(19, 20, 18, 20));
        return root;
    }

    private static void addHeading(JPanel content, String title, Style style) {
        JLabel heading = new JLabel(title);
        heading.setForeground(style.text());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
    }

    private static JTextArea paragraph(String text, Style style, Color color, int rows, int width) {
        JTextArea area = new JTextArea(text);
        area.setUI(new BasicTextAreaUI());
        area.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 12f));
        area.setForeground(color);
        area.setCaretColor(style.accent());
        area.setSelectionColor(style.selection());
        area.setSelectedTextColor(style.text());
        area.setOpaque(false);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        int height = area.getFontMetrics(area.getFont()).getHeight() * rows;
        area.setPreferredSize(new Dimension(width, height));
        area.setMinimumSize(new Dimension(0, height));
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return area;
    }

    private static JPanel buttonRow(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JButton button : buttons) row.add(button);
        Dimension preferred = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        return row;
    }

    private static JButton button(String text, Style style, boolean primary) {
        JButton button = new JButton(text);
        button.setUI(new RoundedButtonUI(style, primary));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(8, 11, 8, 11));
        button.setForeground(primary ? Color.WHITE : style.text());
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setRolloverEnabled(true);
        return button;
    }

    private static JButton linkButton(String text, Style style) {
        JButton button = new JButton(text);
        button.setUI(new BasicButtonUI());
        button.setForeground(style.accent());
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 11f));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(2, 0, 2, 0));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    private static void show(JDialog dialog, JPanel content, int width, Component parent) {
        dialog.setContentPane(content);
        Dimension preferred = content.getPreferredSize();
        content.setPreferredSize(new Dimension(width - 2, preferred.height));
        dialog.pack();
        MacNativeInputBackend.applyNativeWindowAppearance(dialog,
                Boolean.TRUE.equals(dialog.getRootPane().getClientProperty("digitalMarathon.darkTheme")));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static boolean desktopSupports(Desktop.Action action) {
        try {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String folderName(Path file) {
        Path folder = file.getParent();
        return folder == null || folder.getFileName() == null ? "your chosen folder" : folder.getFileName().toString();
    }

    private static String abbreviate(String value, int maximum) {
        if (value.length() <= maximum) return value;
        int first = (maximum - 1) * 2 / 3;
        return value.substring(0, first) + "…" + value.substring(value.length() - (maximum - first - 1));
    }

    private static final class RoundedSurface extends JPanel {
        private final Style style;
        private RoundedSurface(Style style) { this.style = style; setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(style.surface());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
                g.setColor(style.border());
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedButtonUI extends BasicButtonUI {
        private final Style style;
        private final boolean primary;
        private RoundedButtonUI(Style style, boolean primary) { this.style = style; this.primary = primary; }
        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = primary ? style.accent() : style.surface();
                if (button.getModel().isPressed()) fill = primary ? fill.darker() : style.selection();
                else if (button.getModel().isRollover()) fill = primary ? fill.brighter() : style.selection();
                if (!button.isEnabled()) fill = style.surface();
                g.setColor(fill);
                g.fillRoundRect(1, 1, component.getWidth() - 2, component.getHeight() - 2, 10, 10);
                g.setColor(primary && button.isEnabled() ? fill : style.border());
                g.drawRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 10, 10);
                if (button.hasFocus()) {
                    g.setColor(style.accent());
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
                }
            } finally { g.dispose(); }
            super.paint(graphics, component);
        }
        @Override protected void paintFocus(Graphics graphics, AbstractButton button,
                                            Rectangle view, Rectangle text, Rectangle icon) {
            // The rounded keyboard focus outline is painted above.
        }
    }
}
