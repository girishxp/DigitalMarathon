package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTextAreaUI;
import java.awt.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/** Optional online services; tracking and local history work independently. */
final class AppServices implements AutoCloseable {
    static final String VERSION = runningVersion();
    private static String runningVersion() {
        String manifest = AppServices.class.getPackage().getImplementationVersion();
        if (validVersion(manifest)) return manifest;
        String launcher = System.getProperty("jpackage.app-version", "");
        return validVersion(launcher) ? launcher : "2.1.27";
    }
    private static boolean validVersion(String value) {
        return value != null && value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})");
    }
    final UpdateManager updates;
    final AnalyticsManager analytics;
    private final Preferences preferences = Preferences.userNodeForPackage(AppServices.class);
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private TrackerWindow owner;
    private JDialog progress;
    private JProgressBar progressBar;
    private volatile String status = "Checks run while Digital Marathon is open.";
    private boolean releaseShowing;
    private boolean analyticsStarted;
    private JDialog consentNotice;

    AppServices(Path directory) {
        this(new UpdateManager(VERSION, directory), new AnalyticsManager(VERSION, directory));
    }
    AppServices(UpdateManager updates, AnalyticsManager analytics) {
        this.updates = updates;
        this.analytics = analytics;
        updates.setEventSink(analytics::capture);
    }
    String status() { return status; }
    void observe(Runnable observer) { observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    private void status(String text) {
        status = text;
        SwingUtilities.invokeLater(() -> observers.forEach(Runnable::run));
    }
    void capture(String event, Map<String, Object> values) { analytics.capture(event, values); }
    void setAnalyticsEnabled(boolean enabled) {
        analytics.setEnabled(enabled);
        preferences.putBoolean("analyticsNoticeShown", true);
        if (consentNotice != null) { consentNotice.dispose(); consentNotice = null; }
        startAnalytics();
        SwingUtilities.invokeLater(() -> observers.forEach(Runnable::run));
    }
    void start(TrackerWindow window) {
        owner = window;
        startUpdates();
        if (analytics.configured() && !preferences.getBoolean("analyticsNoticeShown", false)) {
            JDialog notice = dialog("Updates & Privacy");
            consentNotice = notice;
            JPanel content = content("Help improve Digital Marathon",
                    "Optional anonymous analytics report app version, operating system, sessions, feature use and update use. "
                    + "Your mouse/key/click totals, activity history, certificate names and file paths are never sent. "
                    + "Change this choice any time in Help → Updates & Privacy.");
            JButton off = button("Keep analytics off", false);
            off.addActionListener(e -> setAnalyticsEnabled(false));
            JButton on = button("Enable anonymous analytics", true);
            on.addActionListener(e -> setAnalyticsEnabled(true));
            content.add(row(off, on));
            notice.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            notice.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) {
                    setAnalyticsEnabled(false);
                }
            });
            show(notice, content, 510);
        } else startAnalytics();
    }
    private void startAnalytics() {
        if (!analyticsStarted) { analyticsStarted = true; analytics.start(); }
    }
    private void startUpdates() {
        updates.start(new UpdateManager.Listener() {
            @Override public void checked(UpdateManager.Release release, boolean manual, String message) {
                status(message);
                SwingUtilities.invokeLater(() -> {
                    if (owner == null || !owner.isDisplayable()) return;
                    if (release != null) showRelease(release);
                    else if (manual) ThemeDialog.message(owner, owner.isDarkAppearance(), "Updates", message);
                });
            }
            @Override public void downloading(long bytes, long total) {
                SwingUtilities.invokeLater(() -> {
                    if (progressBar != null) progressBar.setValue(total > 0 ? (int)Math.min(100, bytes * 100 / total) : 0);
                });
            }
            @Override public void downloaded(Path zip) {
                status("A verified update is ready.");
                SwingUtilities.invokeLater(() -> {
                    closeProgress();
                    if (owner == null || !owner.isDisplayable()) return;
                    JDialog ready = dialog("Update downloaded");
                    JPanel content = content("Verified update ready", "Quit Digital Marathon, extract the downloaded ZIP, "
                            + "then open the new Digital Marathon.app or Windows launcher. Your saved history and settings "
                            + "stay in your user profile. Keep the complete new folder together.");
                    JTextArea file = paragraph(zip.getFileName().toString());
                    file.setForeground(muted()); content.add(file); content.add(Box.createVerticalStrut(15));
                    JButton folder = button("Show downloaded update", true);
                    folder.addActionListener(e -> {
                        try { Desktop.getDesktop().open(zip.getParent().toFile()); }
                        catch (Exception error) { ThemeDialog.message(ready, owner.isDarkAppearance(), "Downloaded update", zip.getParent().toString()); }
                    });
                    JButton done = button("Done", false); done.addActionListener(e -> ready.dispose());
                    content.add(row(folder, done)); show(ready, content, 500);
                });
            }
            @Override public void failed(String code, String message) {
                status(message);
                SwingUtilities.invokeLater(() -> {
                    closeProgress();
                    if (owner != null && owner.isDisplayable()) ThemeDialog.message(owner, owner.isDarkAppearance(), "Update unavailable", message);
                });
            }
        });
    }
    private void showRelease(UpdateManager.Release release) {
        if (releaseShowing) return;
        releaseShowing = true;
        JDialog dialog = dialog("Update available");
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { releaseShowing = false; }
            @Override public void windowClosing(java.awt.event.WindowEvent event) { updates.remindLater(release.version()); }
        });
        JPanel content = content("Digital Marathon " + release.version() + " is available",
                "Download the verified update when you are ready. You can keep using Digital Marathon while it downloads.");
        JTextArea notes = paragraph(release.notes().isBlank() ? "A new Digital Marathon release is ready." : release.notes());
        Color noteSurface = dark() ? new Color(44,44,46) : Color.WHITE;
        notes.setOpaque(true); notes.setBackground(noteSurface); notes.setBorder(new EmptyBorder(10,10,10,10));
        notes.setRows(5); JScrollPane scroll = new JScrollPane(notes); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(noteSurface);
        scroll.setPreferredSize(new Dimension(460, 120)); content.add(scroll); content.add(Box.createVerticalStrut(18));
        JButton skip = button("Skip this version", false);
        skip.addActionListener(e -> { updates.skipVersion(release.version()); dialog.dispose(); });
        JButton later = button("Remind me later", false);
        later.addActionListener(e -> { updates.remindLater(release.version()); dialog.dispose(); });
        JButton download = button("Download update", true);
        download.addActionListener(e -> {
            dialog.dispose();
            progress = dialog("Downloading update");
            JPanel panel = content("Downloading Digital Marathon " + release.version(), "The download is checked before it is offered for installation.");
            progressBar = new JProgressBar(0, 100); progressBar.setStringPainted(true); panel.add(progressBar);
            show(progress, panel, 470); updates.download(release);
        });
        content.add(row(skip, later, download)); show(dialog, content, 550);
    }
    private void closeProgress() { if (progress != null) progress.dispose(); progress = null; progressBar = null; }
    private boolean dark() { return owner != null && owner.isDarkAppearance(); }
    private Color page() { return dark() ? new Color(28,28,30) : new Color(245,245,247); }
    private Color ink() { return dark() ? new Color(242,242,247) : new Color(29,29,31); }
    private Color muted() { return dark() ? new Color(174,174,180) : new Color(110,110,115); }
    private JDialog dialog(String title) {
        JDialog dialog = new JDialog(owner, title, false);
        dialog.setIconImages(owner.getIconImages()); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().putClientProperty("apple.awt.windowAppearance", dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        dialog.getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        dialog.setResizable(false); return dialog;
    }
    private JPanel content(String title, String body) {
        JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(page()); panel.setBorder(new EmptyBorder(20,20,20,20));
        JLabel heading = new JLabel(title); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 19f));
        heading.setForeground(ink()); heading.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(heading);
        panel.add(Box.createVerticalStrut(10)); panel.add(paragraph(body)); panel.add(Box.createVerticalStrut(18)); return panel;
    }
    private JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setUI(new BasicTextAreaUI());
        area.setEditable(false); area.setFocusable(false); area.setOpaque(false); area.setForeground(ink());
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); area.setAlignmentX(Component.LEFT_ALIGNMENT); return area;
    }
    private JButton button(String text, boolean primary) {
        JButton button = new JButton(text); button.setUI(new ServiceButtonUI(primary)); button.setFocusPainted(false);
        button.setContentAreaFilled(false); button.setOpaque(false); button.setBorderPainted(false);
        button.setBackground(primary ? new Color(0,122,255) : (dark() ? new Color(44,44,46) : Color.WHITE));
        button.setForeground(primary ? Color.WHITE : ink()); button.setBorder(new EmptyBorder(9,12,9,12)); return button;
    }
    private final class ServiceButtonUI extends BasicButtonUI {
        private final boolean primary;
        private ServiceButtonUI(boolean primary) { this.primary = primary; }
        @Override public void paint(Graphics graphics, JComponent component) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                AbstractButton button = (AbstractButton)component;
                Color fill = button.getBackground();
                if (button.getModel().isPressed()) fill = primary ? fill.darker() : (dark() ? new Color(58,58,60) : new Color(235,235,240));
                g.setColor(fill); g.fillRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,12,12);
                g.setColor(primary ? fill : (dark() ? new Color(72,72,74) : new Color(209,209,214)));
                g.drawRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,12,12);
            } finally { g.dispose(); }
            super.paint(graphics, component);
        }
    }
    private JPanel row(JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT); for (JButton button : buttons) panel.add(button); return panel;
    }
    private void show(JDialog dialog, JPanel content, int width) {
        // Measure wrapped bodies at their actual width before packing the fixed-size notice.
        for (Component component : content.getComponents()) {
            if (component instanceof JTextArea area) {
                area.setSize(width - 40, Short.MAX_VALUE);
                int height = area.getPreferredSize().height;
                area.setPreferredSize(new Dimension(width - 40, height));
                area.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            }
        }
        dialog.setContentPane(content); content.setPreferredSize(new Dimension(width, content.getPreferredSize().height));
        dialog.pack(); MacNativeInputBackend.applyNativeWindowAppearance(dialog, dark());
        dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }
    @Override public void close() { updates.close(); analytics.close(); }
}
