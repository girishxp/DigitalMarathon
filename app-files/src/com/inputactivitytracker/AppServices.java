package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicProgressBarUI;
import javax.swing.plaf.basic.BasicTextAreaUI;
import java.awt.*;
import java.awt.event.*;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Online product services. One update snapshot; review never interrupts local tracking. */
final class AppServices implements AutoCloseable {
    static final String VERSION = runningVersion();
    private static String runningVersion() {
        String manifest = AppServices.class.getPackage().getImplementationVersion();
        if (validVersion(manifest)) return manifest;
        String launcher = System.getProperty("jpackage.app-version", "");
        return validVersion(launcher) ? launcher : "2.1.30";
    }
    private static boolean validVersion(String value) {
        return value != null && value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})");
    }
    final UpdateManager updates;
    final AnalyticsManager analytics;
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService handoffWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Digital Marathon update handoff"); thread.setDaemon(true); return thread;
    });
    private volatile UpdateReviewState state = UpdateReviewState.initial();
    private TrackerWindow owner;
    private ReviewPanel review;
    private boolean reviewPending;
    private boolean analyticsStarted;
    private boolean closed;

    AppServices(Path directory) { this(new UpdateManager(VERSION, directory), new AnalyticsManager(VERSION, directory)); }
    AppServices(UpdateManager updates, AnalyticsManager analytics) {
        this.updates = updates; this.analytics = analytics; updates.setEventSink(analytics::capture);
    }
    String status() { return state.message(); }
    UpdateReviewState updateState() { return state; }
    void observe(Runnable observer) { observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    void capture(String event, Map<String,Object> values) { analytics.capture(event, values); }
    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }
    private void setState(UpdateReviewState next) {
        if (closed) return;
        state = next;
        if (owner != null) owner.updateMiniNotice(next.miniText(), next.hasNotice());
        if (review != null && review.isDisplayable()) review.refresh();
        observers.forEach(Runnable::run);
    }
    void start(TrackerWindow window) {
        owner = window;
        if (!analyticsStarted) { analyticsStarted = true; analytics.start(); }
        updates.start(new UpdateManager.Listener() {
            @Override public void checked(UpdateManager.Release release, boolean manual, String message) {
                onEdt(() -> acceptChecked(release, manual, message));
            }
            @Override public void downloading(long bytes, long total) { onEdt(() -> acceptProgress(bytes, total)); }
            @Override public void downloaded(Path zip) { onEdt(() -> acceptDownloaded(zip)); }
            @Override public void failed(String code, String message) { onEdt(() -> acceptFailure(UpdateReviewState.IssueStage.DOWNLOAD, message, false)); }
            @Override public void checkFailed(String code, String message, boolean manual) {
                onEdt(() -> acceptFailure(UpdateReviewState.IssueStage.CHECK, message, manual));
            }
        });
        if (!updates.autoCheckEnabled()) updates.setAutoCheckEnabled(true);
        setState(state);
    }
    // Callback reducers are also exercised with offline transports and isolated UI profiles.
    void acceptChecked(UpdateManager.Release release, boolean manual, String message) {
        boolean previouslyNoticed = state.hasNotice();
        String previousVersion = state.version();
        setState(state.checked(release, manual, message));
        if (!usableOwner() || owner.isMiniView()) return;
        if (manual || (release != null && (!previouslyNoticed || !previousVersion.equals(state.version())))) reviewUpdate();
    }
    void acceptProgress(long bytes, long total) { setState(state.downloading(bytes, total)); }
    void acceptDownloaded(Path zip) { setState(state.downloaded(zip)); }
    void acceptFailure(UpdateReviewState.IssueStage stage, String message, boolean manual) {
        setState(state.failed(stage, message));
        if (manual && usableOwner() && !owner.isMiniView()) reviewUpdate();
    }
    private boolean usableOwner() { return !closed && owner != null && owner.isDisplayable(); }

    /** The only route into update UI: restore and settle Full View before creating a panel. */
    void reviewUpdate() {
        onEdt(() -> {
            if (!usableOwner() || reviewPending) return;
            if (review != null && review.isDisplayable()) {
                if (owner.isMiniView()) { closeReview(); } else { review.toFront(); review.requestFocus(); return; }
            }
            reviewPending = true;
            owner.reviewUpdateInFullView(() -> {
                reviewPending = false;
                if (!usableOwner() || owner.isMiniView()) return;
                if (review == null || !review.isDisplayable()) review = new ReviewPanel();
                review.refresh(); review.showPanel();
            }, () -> { reviewPending = false; if (usableOwner()) owner.updateMiniNotice(state.miniText(), state.hasNotice()); });
        });
    }
    void beforeEnterMiniView() { closeReview(); }
    void appearanceChanged() { if (review != null && review.isDisplayable()) review.refreshTheme(); }
    private void closeReview() {
        ReviewPanel existing = review; review = null;
        if (existing != null) existing.dispose();
    }
    void downloadUpdate() {
        UpdateReviewState next = state.beginDownload();
        if (next == state) return;
        setState(next); updates.download(next.release());
    }
    private void postpone(boolean skip) {
        if (state.release() == null || state.protectedOperation()) return;
        String version = state.version();
        setState(state.dismissNotice(skip ? "v" + version + " is skipped. Newer versions will still be offered."
                : "We will remind you about v" + version + " in 24 hours."));
        if (skip) updates.skipVersion(version); else updates.remindLater(version);
        closeReview();
    }
    private void retry() {
        if (state.issueStage() == UpdateReviewState.IssueStage.DOWNLOAD) { downloadUpdate(); return; }
        if (state.issueStage() == UpdateReviewState.IssueStage.INSTALL) { saveAndCloseForUpdate(); return; }
        if (state.phase() == UpdateReviewState.Phase.CHECKING || state.protectedOperation()) return;
        setState(state.checking()); updates.checkNow();
    }
    private void showDownloadedUpdate() {
        if (state.zip() == null) return;
        try { Desktop.getDesktop().open(state.zip().getParent().toFile()); }
        catch (Exception error) {
            if (review != null) review.showLocalExplanation("Open the update folder manually: " + state.zip().getParent());
        }
    }
    private void saveAndCloseForUpdate() {
        UpdateReviewState next = state.beginInstallation();
        if (next == state || !usableOwner()) return;
        setState(next);
        handoffWorker.execute(() -> {
            try {
                // Reuse the existing archive validator and recheck the exact verified ZIP before exit.
                Path zip = next.zip();
                if (!Files.isRegularFile(zip) || Files.size(zip) != next.release().size()) throw new java.io.IOException();
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(zip)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = input.read(buffer)) >= 0) { if (count > 0) hash.update(buffer, 0, count); }
                }
                if (!MessageDigest.isEqual(hash.digest(), HexFormat.of().parseHex(next.release().sha256()))) throw new java.io.IOException();
                UpdateManager.validateArchive(zip, next.version());
                onEdt(() -> {
                    if (!usableOwner() || state.phase() != UpdateReviewState.Phase.INSTALLING) return;
                    owner.prepareUpdateClose(() -> owner.closeForPreparedUpdate(this::deferClose), this::deferClose);
                });
            } catch (Exception error) {
                onEdt(() -> setState(new UpdateReviewState(UpdateReviewState.Phase.ISSUE, state.release(), null,
                        0, state.total(), "The downloaded update changed or is unavailable. Keep using the app; Retry downloads a verified copy before closing.",
                        UpdateReviewState.IssueStage.DOWNLOAD, true)));
            }
        });
    }
    private void deferClose(String explanation) { setState(state.installationDeferred(explanation)); }

    private boolean dark() { return owner != null && owner.isDarkAppearance(); }
    private Color page() { return dark() ? new Color(28,28,30) : new Color(245,245,247); }
    private Color ink() { return dark() ? new Color(242,242,247) : new Color(29,29,31); }
    private Color muted() { return dark() ? new Color(174,174,180) : new Color(110,110,115); }
    private Color surface() { return dark() ? new Color(44,44,46) : Color.WHITE; }
    private Color border() { return dark() ? new Color(72,72,74) : new Color(209,209,214); }

    private final class ReviewPanel extends JDialog {
        private final JPanel pagePanel = new JPanel(new BorderLayout(0, 12));
        private final JPanel header = new JPanel();
        private final JPanel body = new ReviewBody();
        private final JLabel heading = new JLabel();
        private final JLabel version = new JLabel();
        private final JTextArea statusText = textArea(1);
        private final JTextArea notes = textArea(9);
        private final JTextArea installSteps = textArea(1);
        private final JScrollPane notesScroll = new JScrollPane(notes);
        private final JLabel notesHeading = new JLabel("Release notes");
        private final JProgressBar progress = new JProgressBar(0, 100);
        private final JPanel actions = new JPanel(new GridLayout(0, 2, 8, 8));
        private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        private final JScrollPane scroll = new JScrollPane(body);
        private final JButton update = button("Update Now", KeyEvent.VK_U, true);
        private final JButton later = button("Remind Me Later", KeyEvent.VK_L, false);
        private final JButton skip = button("Skip This Version", KeyEvent.VK_S, false);
        private final JButton retryButton = button("Retry", KeyEvent.VK_R, true);
        private final JButton folder = button("Show Downloaded Update", KeyEvent.VK_D, false);
        private final JButton saveClose = button("Save & Close for Update", KeyEvent.VK_C, true);
        private final JButton back = button("Back to Mini", KeyEvent.VK_B, false);
        private final JButton closeButton = button("Close Review", KeyEvent.VK_E, false);
        private UpdateReviewState.Phase renderedPhase;
        private String renderedVersion = "";

        ReviewPanel() {
            super(owner, "Digital Marathon Updates", false);
            setIconImages(owner.getIconImages()); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
            setResizable(true);
            pagePanel.setBorder(new EmptyBorder(18,18,18,18));
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22f));
            version.setFont(version.getFont().deriveFont(Font.PLAIN, 12f));
            header.setLayout(new BoxLayout(header,BoxLayout.Y_AXIS)); header.setOpaque(false);
            heading.setAlignmentX(Component.LEFT_ALIGNMENT); version.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(heading); header.add(Box.createVerticalStrut(8)); header.add(version);
            pagePanel.add(header,BorderLayout.NORTH);
            for (JComponent component : new JComponent[]{statusText, progress, notesHeading, notesScroll, installSteps, actions}) {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(component); body.add(Box.createVerticalStrut(12));
            }
            notes.setBorder(new EmptyBorder(12,12,12,12)); notes.setOpaque(true);
            notesScroll.setBorder(BorderFactory.createEmptyBorder());
            notesScroll.setPreferredSize(new Dimension(480,220));
            notesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,260));
            notesScroll.getVerticalScrollBar().setUnitIncrement(18);
            progress.setUI(new BasicProgressBarUI() {
                @Override protected Color getSelectionForeground() { return Color.WHITE; }
                @Override protected Color getSelectionBackground() { return ink(); }
            });
            progress.setStringPainted(true); progress.setBorderPainted(false);
            progress.setPreferredSize(new Dimension(480,24));
            progress.setMaximumSize(new Dimension(Integer.MAX_VALUE,24));
            scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getViewport().addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent event) { fitText(); }
            });
            pagePanel.add(scroll, BorderLayout.CENTER);
            footer.add(back); footer.add(closeButton); pagePanel.add(footer, BorderLayout.SOUTH);
            setContentPane(pagePanel);
            update.addActionListener(e -> downloadUpdate());
            later.addActionListener(e -> postpone(false)); skip.addActionListener(e -> postpone(true));
            retryButton.addActionListener(e -> retry()); folder.addActionListener(e -> showDownloadedUpdate());
            saveClose.addActionListener(e -> saveAndCloseForUpdate());
            back.addActionListener(e -> { closeReview(); owner.returnToMiniAfterUpdateReview(); });
            closeButton.addActionListener(e -> closeReview());
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { if (review == ReviewPanel.this) review = null; }
            });
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-review");
            getRootPane().getActionMap().put("close-review", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { closeReview(); }
            });
            refreshTheme();
        }
        void refresh() {
            if (owner.isMiniView()) { closeReview(); return; }
            UpdateReviewState current = state;
            heading.setText(switch (current.phase()) {
                case AVAILABLE -> "An update is available";
                case DOWNLOADING -> "Downloading update";
                case READY -> "Verified update ready";
                case ISSUE -> "Update needs attention";
                case INSTALLING -> "Saving before the update";
                case CHECKING -> "Checking for updates";
                default -> "Updates";
            });
            version.setText(current.release() == null ? "Installed version " + VERSION
                    : "Digital Marathon " + current.version() + "  ·  Installed " + VERSION);
            statusText.setText(current.message());
            if (!current.version().equals(renderedVersion)) {
                notes.setText(current.release() == null ? "No release notes are available yet."
                        : current.release().notes().isBlank() ? "A new Digital Marathon release is ready." : current.release().notes());
                notes.setCaretPosition(0); renderedVersion = current.version();
            }
            boolean ready = current.zip() != null;
            installSteps.setText("Installation: " + (ready ? "ready for the manual package switch.\n\n1. Show the downloaded update and extract its complete ZIP into a new folder.\n2. Choose Save & Close for Update when your exports or saves have finished.\n3. Open the new Digital Marathon.app or Windows launcher. Your local history and settings remain; the same-day session resumes."
                    : "the installed app is unchanged. Downloading and reviewing keep your session running. The verified ZIP will be extracted into a new folder; no running files are replaced."));
            installSteps.setVisible(ready || current.phase() == UpdateReviewState.Phase.AVAILABLE);
            notesHeading.setVisible(current.release() != null); notesScroll.setVisible(current.release() != null);
            progress.setVisible(current.phase() == UpdateReviewState.Phase.DOWNLOADING || ready);
            progress.setValue(current.progressPercent());
            progress.setString(current.phase() == UpdateReviewState.Phase.DOWNLOADING && current.progressPercent() == 100
                    ? "100% · Verifying package" : current.progressPercent() + "%");
            if (renderedPhase != current.phase()) {
                actions.removeAll();
                switch (current.phase()) {
                    case AVAILABLE -> { actions.add(update); actions.add(later); actions.add(skip); }
                    case READY -> { actions.add(folder); actions.add(saveClose); }
                    case ISSUE -> { actions.add(retryButton); if (ready) actions.add(folder); }
                    default -> { }
                }
                renderedPhase = current.phase();
            }
            back.setVisible(owner.isUpdateReviewFromMini());
            back.setEnabled(current.phase() != UpdateReviewState.Phase.INSTALLING);
            saveClose.setEnabled(current.canInstall()); update.setEnabled(current.canDownload());
            retryButton.setEnabled(current.phase() == UpdateReviewState.Phase.ISSUE);
            getRootPane().setDefaultButton(switch (current.phase()) {
                case AVAILABLE -> update;
                case READY -> folder; // Enter never closes/restarts active work accidentally.
                case ISSUE -> retryButton;
                default -> closeButton;
            });
            fitText(); refreshTheme(); body.revalidate(); body.repaint();
        }
        private void fitText() {
            int width = scroll.getViewport().getWidth();
            if (width < 100) width = 480;
            for (JTextArea area : new JTextArea[]{statusText,installSteps}) {
                area.setSize(width,Short.MAX_VALUE);
                int height = area.getPreferredSize().height;
                area.setMaximumSize(new Dimension(Integer.MAX_VALUE,height));
            }
            int columns = width < 430 ? 1 : 2;
            ((GridLayout)actions.getLayout()).setColumns(columns);
            int rows = (actions.getComponentCount() + columns - 1) / columns;
            int height = rows == 0 ? 0 : rows * update.getPreferredSize().height + (rows - 1) * 8;
            actions.setPreferredSize(new Dimension(width,height));
            actions.setMaximumSize(new Dimension(Integer.MAX_VALUE,height));
            actions.setVisible(rows > 0);
            body.revalidate();
        }
        void showLocalExplanation(String explanation) { statusText.setText(explanation); fitText(); }
        void refreshTheme() {
            getRootPane().putClientProperty("apple.awt.windowAppearance", dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
            pagePanel.setBackground(page()); body.setBackground(page()); footer.setOpaque(false); actions.setOpaque(false);
            scroll.getViewport().setBackground(page());
            for (JLabel label : new JLabel[]{heading, version, notesHeading}) label.setForeground(label == heading ? ink() : muted());
            for (JTextArea area : new JTextArea[]{statusText, notes, installSteps}) area.setForeground(ink());
            notes.setBackground(surface()); notesScroll.getViewport().setBackground(surface()); progress.setForeground(new Color(0,122,255)); progress.setBackground(surface());
            for (JButton button : new JButton[]{update,later,skip,retryButton,folder,saveClose,back,closeButton}) {
                boolean primary = button == update || button == retryButton || button == saveClose;
                button.setBackground(primary ? new Color(0,122,255) : surface()); button.setForeground(primary ? Color.WHITE : ink());
            }
            MacNativeInputBackend.applyNativeWindowAppearance(this, dark()); repaint();
        }
        void showPanel() {
            Rectangle usable = usableBounds(owner);
            int width = Math.min(580, Math.max(280, usable.width - 40));
            int height = Math.min(680, Math.max(260, usable.height - 48));
            setMinimumSize(new Dimension(Math.min(400,width), Math.min(350,height)));
            setSize(width,height);
            setLocation(usable.x + (usable.width-width)/2, usable.y + (usable.height-height)/2);
            setVisible(true);
            (state.phase() == UpdateReviewState.Phase.AVAILABLE ? update
                    : state.phase() == UpdateReviewState.Phase.READY ? folder
                    : state.phase() == UpdateReviewState.Phase.ISSUE ? retryButton : closeButton).requestFocusInWindow();
        }
    }
    /** Wrap to the available width; the outer panel scrolls vertically on small displays. */
    private static final class ReviewBody extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction) {
            return Math.max(18,(orientation == SwingConstants.VERTICAL ? visible.height : visible.width)-18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static Rectangle usableBounds(Window window) {
        GraphicsConfiguration config = window.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(config.getBounds()); Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        bounds.x += insets.left; bounds.y += insets.top;
        bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
        return bounds;
    }
    private static JTextArea textArea(int rows) {
        JTextArea text = new JTextArea(rows, 36); text.setUI(new BasicTextAreaUI());
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false);
        text.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13)); text.setFocusable(true);
        return text;
    }
    private JButton button(String text, int mnemonic, boolean primary) {
        JButton button = new JButton(text); button.setUI(new ServiceButtonUI(primary)); button.setMnemonic(mnemonic);
        button.setContentAreaFilled(false); button.setOpaque(false); button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(9,10,9,10)); button.setFont(button.getFont().deriveFont(Font.PLAIN,12f));
        button.setFocusPainted(false); return button;
    }
    private final class ServiceButtonUI extends BasicButtonUI {
        private final boolean primary;
        ServiceButtonUI(boolean primary) { this.primary = primary; }
        @Override public void paint(Graphics graphics,JComponent component) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                AbstractButton button=(AbstractButton)component; Color fill=button.getBackground();
                if (button.getModel().isPressed()) fill=fill.darker();
                g.setColor(fill);g.fillRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,12,12);
                g.setColor(button.hasFocus() ? new Color(0,122,255) : primary ? fill : border());
                g.drawRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,12,12);
            } finally { g.dispose(); }
            super.paint(graphics,component);
        }
    }
    @Override public void close() {
        closed=true;updates.close();analytics.close();handoffWorker.shutdownNow();
        onEdt(this::closeReview);
    }
}
