package com.inputactivitytracker;

import com.inputactivitytracker.ActivityModels.Group;
import com.inputactivitytracker.ActivityModels.Snapshot;
import com.inputactivitytracker.ActivityModels.Totals;
import com.inputactivitytracker.RangeOption.TimeWindow;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicCheckBoxUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

final class TrackerWindow extends JFrame {
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean IS_MACOS = OS_NAME.contains("mac");
    private static final boolean APPLE_CHROME = IS_MACOS || Boolean.getBoolean("digitalmarathon.forceAppleChrome");
    private static final boolean CAPTURE_PRIVACY_AVAILABLE = CapturePrivacySupport.isPotentiallyAvailable()
            || Boolean.getBoolean("digitalmarathon.forceCapturePrivacyUI");
    private record Palette(
            Color page,
            Color text,
            Color muted,
            Color surface,
            Color control,
            Color input,
            Color border,
            Color blueTint,
            Color greenTint,
            Color amberTint,
            Color violetTint,
            Color chartBar,
            Color chartTrend,
            Color chartAxis,
            Color success,
            Color warning,
            Color selection
    ) {}

    /*
     * Low-saturation palettes keep the dashboard calm during long work sessions.
     * The metric tints are intentionally close to the surface color rather than
     * using strong, opaque blocks of blue/green/amber/violet.
     */
    // Apple-inspired system palette: neutral surfaces, SF-style contrast and
    // familiar macOS accent colors while retaining the app's four metric tints.
    private static final Palette LIGHT_THEME = new Palette(
            new Color(245, 245, 247), new Color(29, 29, 31), new Color(110, 110, 115),
            new Color(255, 255, 255), new Color(242, 242, 247), new Color(255, 255, 255),
            new Color(209, 209, 214), new Color(235, 245, 255), new Color(237, 250, 240),
            new Color(255, 247, 232), new Color(246, 240, 255), new Color(0, 122, 255),
            new Color(255, 69, 58), new Color(229, 229, 234), new Color(52, 199, 89), new Color(255, 149, 0),
            new Color(224, 240, 255));

    private static final Palette DARK_THEME = new Palette(
            new Color(28, 28, 30), new Color(242, 242, 247), new Color(142, 142, 147),
            new Color(44, 44, 46), new Color(58, 58, 60), new Color(36, 36, 38),
            new Color(72, 72, 74), new Color(31, 45, 61), new Color(31, 55, 43),
            new Color(64, 52, 31), new Color(53, 43, 69), new Color(10, 132, 255),
            new Color(255, 69, 58), new Color(72, 72, 74), new Color(48, 209, 88), new Color(255, 159, 10),
            new Color(38, 69, 96));

    private static final Color PAGE = LIGHT_THEME.page();
    private static final Color TEXT = LIGHT_THEME.text();
    private static final Color MUTED = LIGHT_THEME.muted();
    private static final Color BLUE_TINT = LIGHT_THEME.blueTint();
    private static final Color GREEN_TINT = LIGHT_THEME.greenTint();
    private static final Color AMBER_TINT = LIGHT_THEME.amberTint();
    private static final Color VIOLET_TINT = LIGHT_THEME.violetTint();

    private final ActivityTracker tracker;
    private final HistoryStore store;
    private final ZoneId zone;
    private final Preferences preferences = Preferences.userNodeForPackage(TrackerWindow.class);
    private final ExecutorService queryExecutor;
    private final AtomicLong queryGeneration = new AtomicLong();
    private final AtomicBoolean liveRefreshQueued = new AtomicBoolean();
    private final AtomicBoolean dataRefreshQueued = new AtomicBoolean();
    private ScheduledExecutorService refreshExecutor;
    private final AtomicInteger busyDataOperations = new AtomicInteger();
    private boolean updateClosePreparing;
    private boolean updateClosePrepared;

    private JPanel fullPanel;
    private JPanel miniPanel;
    private Rectangle fullBounds;
    private Point miniLocation;
    private boolean miniMode;
    private boolean viewTransitionInProgress;
    private boolean updateReviewTransitionInProgress;
    private Timer updateReviewResizeTimer;
    private MiniReviewState updateReviewMiniState;
    private record MiniReviewState(Rectangle bounds, Rectangle normalBounds, int transparency, boolean pinPreference,
                                   boolean alwaysOnTop) {}
    private Timer miniLocationSaveTimer;
    private Timer fullBoundsSaveTimer;
    private boolean darkTheme;
    private AppServices services;

    void attachServices(AppServices services) { this.services = services; }
    boolean isDarkAppearance() { return darkTheme; }
    private void usage(String event) { usage(event, Map.of()); }
    private void usage(String event, Map<String, Object> properties) {
        if (services != null) services.capture(event, properties);
    }
    private int transparencyPercent;
    private boolean capturePrivacyEnabled;
    private boolean capturePrivacyApplied;

    private RoundedPanel filtersPanel;
    private RoundedPanel chartCard;
    private RoundedPanel tableCard;
    private JTable intervalTable;
    private JScrollPane tableScroll;
    private JButton themeButton;
    private JLabel capturePrivacyIndicator;
    private JSlider transparencySlider;
    private JLabel transparencyValueLabel;
    private JSlider miniTransparencySlider;
    private JLabel miniTransparencyValueLabel;
    private boolean updatingTransparencyControls;
    private JToggleButton capturePrivacyToggle;

    private MetricCard mouseCard;
    private MetricCard keysCard;
    private MetricCard clicksCard;
    private MetricCard activeCard;
    private MiniMouseMetric miniMouseActivity;
    private MiniMetric miniKeys;
    private JTextArea statusLabel;
    private JButton startStopButton;
    private JToggleButton alwaysOnTopButton;
    private JButton miniFullViewButton;
    private MiniUpdateNotice miniUpdateNotice;
    private boolean miniUpdateBadge;
    private JButton miniThemeButton;
    private JLabel miniCapturePrivacyIndicator;
    private JLabel miniRangeLabel;
    private JLabel miniActiveLabel;
    private JLabel miniActiveValueLabel;
    private MiniFooterDivider miniFooterSeparator;
    private ActivityChartPanel chartPanel;
    private DailyTableModel tableModel;

    private JComboBox<RangeOption> rangeCombo;
    private JComboBox<UnitConverter.Unit> unitCombo;
    private JSpinner ppiSpinner;
    private JPanel customRangePanel;
    private JSpinner customStartSpinner;
    private JSpinner customEndSpinner;

    private volatile Totals displayedTotals = Totals.zero();
    private volatile List<Group> displayedGroups = List.of();
    private volatile List<Group> displayedDaily = List.of();
    private int dataRefreshCounter;

    TrackerWindow(ActivityTracker tracker, HistoryStore store, ZoneId zone) {
        super("Digital Marathon");
        this.tracker = tracker;
        this.store = store;
        this.zone = zone;
        this.queryExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "digital-marathon-history-query");
            thread.setDaemon(true);
            return thread;
        });

        darkTheme = preferences.getBoolean("darkTheme", false);
        transparencyPercent = normaliseTransparency(preferences.getInt("transparencyPercent", 0));
        capturePrivacyEnabled = preferences.getBoolean("capturePrivacyEnabled", false);
        capturePrivacyApplied = false;
        miniLocation = loadSavedMiniLocation();
        fullBounds = loadSavedFullBounds();
        boolean restoreMiniMode = preferences.getBoolean("lastViewMini", false);
        configureInstantToolTips();
        configureWindow();
        fullPanel = buildFullPanel();
        miniPanel = buildMiniPanel();
        setContentPane(fullPanel);
        applyTheme();
        setMinimumSize(new Dimension(840, 680));
        if (fullBounds != null) {
            setBounds(fullBounds);
        } else {
            setSize(880, 740);
            setLocationRelativeTo(null);
            fullBounds = getBounds();
        }
        if (restoreMiniMode) enterMiniMode();
        installTimers();
        requestDataRefresh(true);
    }

    private static void configureInstantToolTips() {
        ToolTipManager toolTips = ToolTipManager.sharedInstance();
        toolTips.setInitialDelay(550);
        toolTips.setReshowDelay(150);
        toolTips.setDismissDelay(5000);
        toolTips.setLightWeightPopupEnabled(false);
        TranslucentToolTipPopupFactory.install();
        UIManager.put("ToolTipUI", AppleToolTipUI.class.getName());
        UIManager.put("ToolTip.background", new Color(255, 255, 255));
        UIManager.put("ToolTip.foreground", new Color(29, 29, 31));
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(5, 8, 5, 8));
    }

    private void configureWindow() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setBackground(PAGE);
        setIconImages(AppBrandIcon.windowImages(loadIcon()));
        JRootPane rootPane = getRootPane();
        if (APPLE_CHROME) {
            // Native macOS full-size content view: the app surface continues behind
            // the traffic-light area instead of reserving a separate title-bar band.
            rootPane.putClientProperty("apple.awt.fullWindowContent", Boolean.TRUE);
            rootPane.putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
            rootPane.putClientProperty("apple.awt.windowTitleVisible", Boolean.FALSE);
            // Native background dragging treats the entire lightweight Swing
            // surface as draggable, including sliders. Restrict dragging to
            // explicit noninteractive header/footer areas instead.
            rootPane.putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        } else {
            rootPane.putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        }
        WindowDragSupport.install(this, () -> miniMode, () -> !viewTransitionInProgress);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                applyCurrentTransparency(false);
                applyCurrentCapturePrivacy(false);
                // When the app starts directly in Mini View, the native window peer
                // does not exist yet when enterMiniMode() first requests chrome changes.
                // Reapply after WINDOW_OPENED so the disabled macOS zoom/full-screen
                // traffic-light is actually hidden rather than appearing as a grey circle.
                if (APPLE_CHROME && miniMode) {
                    SwingUtilities.invokeLater(() -> MacNativeInputBackend.applyNativeMiniWindowChrome(true));
                }
            }

            @Override public void windowDeactivated(WindowEvent event) {
                TranslucentToolTipPopupFactory.hideActiveTooltip();
            }

            @Override public void windowClosing(WindowEvent event) {
                shutdownAndExit();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) {
                if (viewTransitionInProgress) return;
                if (miniMode) {
                    miniLocation = new Point(getLocation());
                    scheduleMiniLocationSave();
                } else {
                    fullBounds = getBounds();
                    scheduleFullBoundsSave();
                }
            }

            @Override public void componentResized(ComponentEvent event) {
                if (miniMode || viewTransitionInProgress) return;
                fullBounds = getBounds();
                scheduleFullBoundsSave();
            }
        });

        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof WindowEvent windowEvent)) return;
            if (windowEvent.getID() != WindowEvent.WINDOW_OPENED) return;
            if (!capturePrivacyApplied) return;
            SwingUtilities.invokeLater(() -> CapturePrivacySupport.apply(true));
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    private JPanel buildFullPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(PAGE);
        root.setBorder(new EmptyBorder(APPLE_CHROME ? 8 : 12, 20, 18, 20));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        if (APPLE_CHROME) titleRow.add(Box.createHorizontalStrut(58));
        JLabel icon = new JLabel(new AppBrandIcon(loadHeaderIcon(), 28, 28));
        JLabel title = new JLabel("Digital Marathon");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        setThemeRole(title, "title");
        JButton miniButton = viewModeButton("Mini View", false);
        miniButton.setToolTipText("Switch to Mini View");
        miniButton.setPreferredSize(new Dimension(112, 32));
        miniButton.setMinimumSize(new Dimension(112, 32));
        miniButton.setMaximumSize(new Dimension(112, 32));
        miniButton.setMnemonic(KeyEvent.VK_M);
        miniButton.addActionListener(event -> enterMiniMode());
        titleRow.add(icon);
        titleRow.add(Box.createHorizontalStrut(8));
        titleRow.add(title);
        titleRow.add(Box.createHorizontalGlue());
        titleRow.add(miniButton);
        titleRow.add(Box.createHorizontalStrut(10));
        titleRow.add(buildTransparencyControl(false));
        titleRow.add(Box.createHorizontalStrut(6));
        capturePrivacyIndicator = privacyIndicatorLabel(15, 32);
        capturePrivacyIndicator.setVisible(CAPTURE_PRIVACY_AVAILABLE);
        titleRow.add(capturePrivacyIndicator);
        titleRow.add(Box.createHorizontalStrut(CAPTURE_PRIVACY_AVAILABLE ? 4 : 0));
        themeButton = iconButton();
        setThemeRole(themeButton, "miniToolbarButton");
        themeButton.addActionListener(event -> toggleTheme());
        titleRow.add(themeButton);
        titleRow.add(Box.createHorizontalStrut(4));
        JButton helpButton = iconButton();
        setThemeRole(helpButton, "miniToolbarButton");
        helpButton.setText("?");
        helpButton.setFont(helpButton.getFont().deriveFont(Font.PLAIN, 18f));
        helpButton.setToolTipText("Open Help & About");
        helpButton.getAccessibleContext().setAccessibleName("Help and About");
        helpButton.addActionListener(event -> showHelpCenter("Help"));
        titleRow.add(helpButton);
        header.add(titleRow);
        header.add(Box.createVerticalStrut(12));

        statusLabel = new JTextArea("Starting input monitor...");
        statusLabel.setEditable(false);
        statusLabel.setFocusable(false);
        statusLabel.setOpaque(false);
        statusLabel.setLineWrap(true);
        statusLabel.setWrapStyleWord(true);
        statusLabel.setRows(2);
        statusLabel.setBorder(new EmptyBorder(1, 0, 0, 16));
        statusLabel.setForeground(MUTED);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        setThemeRole(statusLabel, "status");
        JPanel statusRow = new JPanel(new BorderLayout(16, 0));
        statusRow.setOpaque(false);
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusRow.add(statusLabel, BorderLayout.CENTER);
        JButton reportButton = fancyReportButton();
        reportButton.setPreferredSize(new Dimension(266, 34));
        reportButton.setMinimumSize(new Dimension(266, 34));
        reportButton.setMaximumSize(new Dimension(266, 34));
        reportButton.setToolTipText("View your report and download a certificate");
        reportButton.addActionListener(event -> showReport());
        statusRow.add(reportButton, BorderLayout.EAST);
        header.add(statusRow);
        root.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        filtersPanel = new RoundedPanel(new GridBagLayout(), PAGE, 14);
        filtersPanel.setBorder(new EmptyBorder(3, 0, 8, 0));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        rangeCombo = new JComboBox<>(RangeOption.defaults());
        String savedRangeKind = preferences.get("rangeKind", "");
        if (!savedRangeKind.isBlank()) {
            try {
                rangeCombo.setSelectedItem(RangeOption.find(RangeOption.Kind.valueOf(savedRangeKind)));
            } catch (IllegalArgumentException ignored) {
                rangeCombo.setSelectedIndex(0);
            }
        } else {
            int legacyIndex = preferences.getInt("rangeIndex", 0);
            // In releases through 2.0.23 index 10 was Custom range. Preserve
            // that choice even though the new long-duration ranges are inserted
            // before Custom range.
            if (legacyIndex == 10) rangeCombo.setSelectedItem(RangeOption.find(RangeOption.Kind.CUSTOM));
            else rangeCombo.setSelectedIndex(Math.max(0, Math.min(rangeCombo.getItemCount() - 1, legacyIndex)));
        }
        rangeCombo.addActionListener(event -> {
            RangeOption selected = (RangeOption) rangeCombo.getSelectedItem();
            if (selected != null) {
                preferences.put("rangeKind", selected.kind().name());
                usage("range_changed");
            }
            preferences.putInt("rangeIndex", rangeCombo.getSelectedIndex());
            updateCustomRangeVisibility();
            requestDataRefresh(true);
        });
        addFilter(filtersPanel, gc, 0, "Range", rangeCombo);

        unitCombo = new JComboBox<>(UnitConverter.Unit.values());
        try {
            unitCombo.setSelectedItem(UnitConverter.Unit.valueOf(preferences.get("unit", UnitConverter.Unit.MILLIMETRES.name())));
        } catch (IllegalArgumentException ignored) {
            unitCombo.setSelectedItem(UnitConverter.Unit.MILLIMETRES);
        }
        unitCombo.addActionListener(event -> {
            UnitConverter.Unit unit = selectedUnit();
            preferences.put("unit", unit.name());
            usage("distance_unit_changed", Map.of("unit", unit.name()));
            updateVisibleValues();
            updateTableAndChart();
        });
        addFilter(filtersPanel, gc, 1, "Distance unit", unitCombo);

        ppiSpinner = new JSpinner(new SpinnerNumberModel(preferences.getDouble("ppi", 96.0), 20.0, 1000.0, 1.0));
        ppiSpinner.setPreferredSize(new Dimension(64, 30));
        ppiSpinner.addChangeListener(event -> {
            preferences.putDouble("ppi", selectedPpi());
            updateVisibleValues();
            updateTableAndChart();
        });
        addFilter(filtersPanel, gc, 2, "Display PPI", ppiSpinner);

        JButton permissions = secondaryButton("Permissions help");
        permissions.addActionListener(event -> showPermissionsHelp());
        gc.gridx = 3;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.EAST;
        filtersPanel.add(permissions, gc);

        customStartSpinner = dateSpinner(java.util.Date.from(Instant.now().minus(Duration.ofDays(1))));
        customEndSpinner = dateSpinner(new java.util.Date());
        customStartSpinner.addChangeListener(event -> requestDataRefresh(true));
        customEndSpinner.addChangeListener(event -> requestDataRefresh(true));
        customRangePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        customRangePanel.setOpaque(false);
        customRangePanel.add(new JLabel("From"));
        customRangePanel.add(customStartSpinner);
        customRangePanel.add(new JLabel("To"));
        customRangePanel.add(customEndSpinner);
        gc.gridy = 1;
        gc.gridx = 0;
        gc.gridwidth = 4;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        filtersPanel.add(customRangePanel, gc);
        updateCustomRangeVisibility();

        filtersPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        body.add(filtersPanel);
        body.add(Box.createVerticalStrut(10));

        JPanel cards = new JPanel(new GridLayout(1, 4, 10, 0));
        cards.setOpaque(false);
        mouseCard = new MetricCard("Mouse distance", BLUE_TINT, ActivityIcon.Kind.MOUSE);
        keysCard = new MetricCard("Key presses", GREEN_TINT, ActivityIcon.Kind.KEYBOARD);
        clicksCard = new MetricCard("Mouse clicks", AMBER_TINT, ActivityIcon.Kind.CLICK);
        activeCard = new MetricCard("Active time", VIOLET_TINT, ActivityIcon.Kind.CLOCK);
        cards.add(mouseCard);
        cards.add(keysCard);
        cards.add(clicksCard);
        cards.add(activeCard);
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        body.add(cards);
        body.add(Box.createVerticalStrut(10));

        chartCard = new RoundedPanel(new BorderLayout(), Color.WHITE, 14);
        chartCard.setBorder(new EmptyBorder(12, 14, 8, 14));
        JLabel chartTitle = new JLabel("Activity trend");
        chartTitle.setForeground(TEXT);
        chartTitle.setFont(chartTitle.getFont().deriveFont(Font.BOLD, 14f));
        setThemeRole(chartTitle, "title");
        JLabel chartLegend = new JLabel("Bars = activity   •   Line = trend   ·   Relative activity 0–100");
        chartLegend.setFont(chartLegend.getFont().deriveFont(Font.PLAIN, 11f));
        setThemeRole(chartLegend, "muted");
        JPanel chartHeader = new JPanel(new BorderLayout());
        chartHeader.setOpaque(false);
        chartHeader.add(chartTitle, BorderLayout.WEST);
        chartHeader.add(chartLegend, BorderLayout.EAST);
        chartPanel = new ActivityChartPanel();
        chartCard.add(chartHeader, BorderLayout.NORTH);
        chartCard.add(chartPanel, BorderLayout.CENTER);
        chartCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 205));
        body.add(chartCard);
        body.add(Box.createVerticalStrut(10));

        tableCard = new RoundedPanel(new BorderLayout(0, 6), Color.WHITE, 14);
        tableCard.setBorder(new EmptyBorder(12, 14, 10, 14));
        JLabel tableTitle = new JLabel("Interval breakdown");
        tableTitle.setForeground(TEXT);
        tableTitle.setFont(tableTitle.getFont().deriveFont(Font.BOLD, 14f));
        setThemeRole(tableTitle, "title");
        tableModel = new DailyTableModel();
        intervalTable = new JTable(tableModel);
        intervalTable.setFillsViewportHeight(true);
        intervalTable.setRowHeight(28);
        intervalTable.setShowVerticalLines(false);
        intervalTable.setGridColor(new Color(232, 237, 244));
        intervalTable.getTableHeader().setReorderingAllowed(false);
        intervalTable.getTableHeader().setDefaultRenderer((table, value, selected, focused, row, column) -> {
            Palette colors = palette();
            JLabel heading = new JLabel(value == null ? "" : value.toString());
            heading.setOpaque(true);
            heading.setBackground(colors.control());
            heading.setForeground(colors.muted());
            heading.setFont(table.getFont().deriveFont(Font.PLAIN, 11f));
            heading.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, colors.border()),
                    new EmptyBorder(6, 6, 6, 6)));
            return heading;
        });
        tableScroll = new JScrollPane(intervalTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableCard.add(tableTitle, BorderLayout.NORTH);
        tableCard.add(tableScroll, BorderLayout.CENTER);
        tableCard.setPreferredSize(new Dimension(850, 148));
        body.add(tableCard);
        body.add(Box.createVerticalStrut(8));

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        if (CAPTURE_PRIVACY_AVAILABLE) {
            actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
            actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        } else {
            actions.setLayout(new GridLayout(1, 4, 10, 0));
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        actions.setPreferredSize(new Dimension(850, 38));
        actions.setMinimumSize(new Dimension(600, 38));
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        if (CAPTURE_PRIVACY_AVAILABLE) {
            capturePrivacyToggle = new JToggleButton("Hide from screenshots & sharing");
            setThemeRole(capturePrivacyToggle, "capturePrivacyToggle");
            capturePrivacyToggle.setFocusPainted(false);
            capturePrivacyToggle.setFocusable(true);
            capturePrivacyToggle.setFont(capturePrivacyToggle.getFont().deriveFont(Font.PLAIN, 11f));
            capturePrivacyToggle.setMargin(new Insets(5, 8, 5, 44));
            capturePrivacyToggle.setPreferredSize(new Dimension(240, 38));
            capturePrivacyToggle.setMinimumSize(new Dimension(238, 38));
            capturePrivacyToggle.setMaximumSize(new Dimension(270, 38));
            capturePrivacyToggle.setSelected(capturePrivacyApplied);
            capturePrivacyToggle.setToolTipText("Hide this window from screenshots and sharing");
            capturePrivacyToggle.getAccessibleContext().setAccessibleName("Screen Capture Privacy");
            capturePrivacyToggle.addActionListener(event -> toggleCapturePrivacy());
        }

        startStopButton = secondaryButton("Pause Tracking");
        setThemeRole(startStopButton, "trackingButton");
        startStopButton.setIcon(new BottomActionIcon(BottomActionIcon.Kind.PAUSE));
        startStopButton.setToolTipText("Pause or resume activity collection");
        startStopButton.addActionListener(event -> toggleTracking());
        if (CAPTURE_PRIVACY_AVAILABLE) {
            configureBottomActionButton(startStopButton);
            // Reserve room for the longer Resume Tracking label and its icon.
            startStopButton.setPreferredSize(new Dimension(154, 38));
            startStopButton.setMinimumSize(new Dimension(150, 38));
            startStopButton.setMaximumSize(new Dimension(164, 38));
        }
        JButton reset = secondaryButton("Reset Session");
        reset.setIcon(new BottomActionIcon(BottomActionIcon.Kind.RESET));
        reset.setToolTipText("Reset the current session; keep saved history");
        reset.addActionListener(event -> {
            tracker.resetSession();
            usage("session_reset");
            requestDataRefresh(true);
        });
        if (CAPTURE_PRIVACY_AVAILABLE) configureBottomActionButton(reset);
        JButton export = secondaryButton("Export CSV");
        export.setIcon(new BottomActionIcon(BottomActionIcon.Kind.DOWNLOAD));
        export.setToolTipText("Save this time range as CSV");
        export.addActionListener(event -> exportCsv());
        if (CAPTURE_PRIVACY_AVAILABLE) configureBottomActionButton(export);
        JButton clear = secondaryButton("Clear History");
        clear.setIcon(new BottomActionIcon(BottomActionIcon.Kind.TRASH));
        setThemeRole(clear, "dangerButton");
        clear.setToolTipText("Delete all saved activity history");
        clear.addActionListener(event -> clearHistory());
        if (CAPTURE_PRIVACY_AVAILABLE) configureBottomActionButton(clear);

        if (CAPTURE_PRIVACY_AVAILABLE) {
            actions.add(capturePrivacyToggle);
            actions.add(Box.createHorizontalStrut(8));
            actions.add(startStopButton);
            actions.add(Box.createHorizontalStrut(8));
            actions.add(reset);
            actions.add(Box.createHorizontalStrut(8));
            actions.add(export);
            actions.add(Box.createHorizontalStrut(8));
            actions.add(clear);
        } else {
            actions.add(startStopButton);
            actions.add(reset);
            actions.add(export);
            actions.add(clear);
        }
        body.add(actions);

        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildMiniPanel() {
        JPanel root = new JPanel();
        root.setBackground(PAGE);
        // Keep the classic compact Mini View footprint. On macOS the traffic lights
        // occupy the left side of this same top row; app controls are packed to the right.
        root.setBorder(new EmptyBorder(APPLE_CHROME ? 3 : 4, 6, 3, 6));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setPreferredSize(new Dimension(262, 88));

        JPanel topRow = new JPanel();
        topRow.setOpaque(false);
        topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Match the compact toolbar proportions in the supplied reference header.
        topRow.setPreferredSize(new Dimension(250, 23));
        topRow.setMaximumSize(new Dimension(250, 23));

        // Keep the original 262 × 88 footprint and leave the counters/footer below
        // this row untouched. The new toolbar fits beside the two traffic lights.
        miniCapturePrivacyIndicator = privacyIndicatorLabel(13, 23);
        miniCapturePrivacyIndicator.setVisible(CAPTURE_PRIVACY_AVAILABLE);
        if (APPLE_CHROME) {
            JPanel chromeSpacer = new JPanel(new BorderLayout());
            chromeSpacer.setOpaque(false);
            chromeSpacer.setPreferredSize(new Dimension(52, 23));
            chromeSpacer.setMinimumSize(new Dimension(52, 23));
            chromeSpacer.setMaximumSize(new Dimension(52, 23));
            topRow.add(chromeSpacer);
        } else {
            topRow.add(Box.createHorizontalGlue());
        }
        if (CAPTURE_PRIVACY_AVAILABLE) {
            topRow.add(miniCapturePrivacyIndicator);
            topRow.add(Box.createHorizontalStrut(3));
        }
        topRow.add(buildTransparencyControl(true));
        topRow.add(Box.createHorizontalStrut(3));

        miniThemeButton = miniToolbarButton(new Dimension(22, 23));
        miniThemeButton.setToolTipText("Switch appearance");
        miniThemeButton.addActionListener(event -> toggleTheme());
        topRow.add(miniThemeButton);
        topRow.add(Box.createHorizontalStrut(3));

        alwaysOnTopButton = miniToggleButton(new Dimension(22, 23));
        alwaysOnTopButton.setSelected(preferences.getBoolean("alwaysOnTop", true));
        alwaysOnTopButton.setToolTipText("Keep this window on top");
        alwaysOnTopButton.getAccessibleContext().setAccessibleName("Always on top");
        alwaysOnTopButton.addActionListener(event -> {
            boolean selected = alwaysOnTopButton.isSelected();
            preferences.putBoolean("alwaysOnTop", selected);
            if (miniMode) setAlwaysOnTop(selected);
            updateMiniPinButton();
            usage("always_on_top_changed", Map.of("enabled", selected));
        });
        topRow.add(alwaysOnTopButton);
        topRow.add(Box.createHorizontalStrut(3));

        miniFullViewButton = miniToolbarButton(new Dimension(22, 23));
        miniFullViewButton.setIcon(new ExpandCornersIcon(12));
        miniFullViewButton.setToolTipText("Open Full View");
        miniFullViewButton.getAccessibleContext().setAccessibleName("Open Full View");
        miniFullViewButton.setFocusable(true);
        miniFullViewButton.putClientProperty("miniUpdateReviewControl", Boolean.TRUE);
        miniFullViewButton.addActionListener(event -> {
            if (miniUpdateBadge && services != null) services.reviewUpdate();
            else exitMiniMode();
        });
        topRow.add(miniFullViewButton);
        root.add(topRow);
        // Preserve the exact counter/range positions below the header.
        // The notice occupies the existing seven-pixel toolbar gap. Its empty
        // state still reserves exactly that space, keeping every Mini control,
        // counter and footer at the original position and the window at 262×88.
        miniUpdateNotice = new MiniUpdateNotice();
        miniUpdateNotice.addActionListener(event -> {
            if (services != null) services.reviewUpdate();
        });
        root.add(miniUpdateNotice);

        JPanel counters = new JPanel();
        counters.setOpaque(false);
        counters.setLayout(new BoxLayout(counters, BoxLayout.X_AXIS));
        miniMouseActivity = new MiniMouseMetric(BLUE_TINT, 155);
        miniKeys = new MiniMetric("Keys", GREEN_TINT, 81, ActivityIcon.Kind.KEYBOARD);
        counters.add(miniMouseActivity);
        counters.add(Box.createHorizontalStrut(6));
        counters.add(miniKeys);
        counters.setAlignmentX(Component.LEFT_ALIGNMENT);
        counters.setPreferredSize(new Dimension(242, 36));
        counters.setMinimumSize(new Dimension(242, 36));
        counters.setMaximumSize(new Dimension(242, 36));
        root.add(counters);
        root.add(Box.createVerticalStrut(1));

        JPanel rangeRow = new JPanel();
        rangeRow.setOpaque(false);
        rangeRow.setLayout(new BoxLayout(rangeRow, BoxLayout.X_AXIS));
        rangeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        rangeRow.setPreferredSize(new Dimension(242, 12));
        rangeRow.setMinimumSize(new Dimension(242, 12));
        rangeRow.setMaximumSize(new Dimension(242, 12));

        // Keep the existing Mini View footprint and counters untouched. Align the
        // footer divider exactly beneath the Mouse/Clicks divider above. The left
        // group uses the same 99 px split point and 7 px content inset as the
        // MiniMouseMetric, so the calendar icon and range text are left-aligned
        // with the Mouse section while Active remains balanced on the right.
        JPanel rangeGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        rangeGroup.setOpaque(false);
        rangeGroup.setPreferredSize(new Dimension(99, 12));
        rangeGroup.setMinimumSize(new Dimension(99, 12));
        rangeGroup.setMaximumSize(new Dimension(99, 12));

        miniRangeLabel = new JLabel("Current session");
        miniRangeLabel.setFont(miniRangeLabel.getFont().deriveFont(Font.PLAIN, 9.6f));
        miniRangeLabel.setForeground(MUTED);
        miniRangeLabel.setIcon(new CalendarRangeIcon(new Color(74, 144, 222), 12));
        miniRangeLabel.setIconTextGap(3);
        miniRangeLabel.setHorizontalAlignment(SwingConstants.LEFT);
        rangeGroup.add(miniRangeLabel);
        rangeRow.add(rangeGroup);

        // Use a dedicated painted divider instead of JSeparator. Aqua/Basic separator
        // delegates can render a one-pixel vertical separator almost invisibly at this
        // compact height; this component guarantees a crisp themed 2 px divider.
        miniFooterSeparator = new MiniFooterDivider(LIGHT_THEME.border(), 2, 12);
        rangeRow.add(miniFooterSeparator);

        // Left-align the Active group so its clock icon begins directly under the
        // Clicks icon above. An 8 px inset matches the click-column content alignment
        // while preserving the existing divider and overall Mini View dimensions.
        JPanel activeGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        activeGroup.setOpaque(false);
        activeGroup.setPreferredSize(new Dimension(141, 12));
        activeGroup.setMinimumSize(new Dimension(141, 12));
        activeGroup.setMaximumSize(new Dimension(141, 12));
        miniActiveLabel = new JLabel("Active");
        miniActiveLabel.setFont(miniActiveLabel.getFont().deriveFont(Font.PLAIN, 9.6f));
        miniActiveLabel.setForeground(MUTED);
        miniActiveLabel.setIcon(new MiniActiveClockIcon(LIGHT_THEME.success(), 12));
        miniActiveLabel.setIconTextGap(3);
        miniActiveValueLabel = new JLabel("0s");
        miniActiveValueLabel.setFont(miniActiveValueLabel.getFont().deriveFont(Font.BOLD, 9.8f));
        miniActiveValueLabel.setForeground(LIGHT_THEME.success());
        activeGroup.add(miniActiveLabel);
        activeGroup.add(miniActiveValueLabel);
        rangeRow.add(activeGroup);

        root.add(rangeRow);
        return root;
    }

    private void installTimers() {
        refreshExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "input-activity-live-display");
            thread.setDaemon(true);
            return thread;
        });

        // A scheduled worker keeps posting refreshes even when macOS has moved
        // keyboard focus to another application. Swing Timer can be throttled
        // with an inactive Java window on some macOS releases.
        refreshExecutor.scheduleAtFixedRate(() -> {
            if (!liveRefreshQueued.compareAndSet(false, true)) return;
            SwingUtilities.invokeLater(() -> {
                try {
                    refreshLiveUi();
                } finally {
                    liveRefreshQueued.set(false);
                }
            });
        }, 0L, 16L, TimeUnit.MILLISECONDS);

        refreshExecutor.scheduleAtFixedRate(() -> {
            if (!dataRefreshQueued.compareAndSet(false, true)) return;
            SwingUtilities.invokeLater(() -> {
                try {
                    dataRefreshCounter++;
                    boolean groups = dataRefreshCounter % 4 == 0;
                    if (!miniMode || selectedRange().kind() != RangeOption.Kind.SESSION) {
                        requestDataRefresh(groups);
                    }
                } finally {
                    dataRefreshQueued.set(false);
                }
            });
        }, 250L, 250L, TimeUnit.MILLISECONDS);

    }

    private void refreshLiveUi() {
            Snapshot snapshot = tracker.snapshot();
            RangeOption selected = selectedRange();
            if (selected.kind() == RangeOption.Kind.SESSION) displayedTotals = snapshot.totals();
            statusLabel.setText(!snapshot.running() ? "Monitoring paused"
                    : snapshot.globalKeysAvailable() ? "Monitoring active · keyboard and mouse"
                    : snapshot.inputStatus());
            statusLabel.setToolTipText(snapshot.inputStatus());
            Palette palette = palette();
            statusLabel.setForeground(snapshot.globalKeysAvailable() ? palette.success() : palette.warning());
            startStopButton.setText(snapshot.running() ? "Pause Tracking" : "Resume Tracking");
            BottomActionIcon.Kind trackingIcon = snapshot.running()
                    ? BottomActionIcon.Kind.PAUSE : BottomActionIcon.Kind.PLAY;
            if (!(startStopButton.getIcon() instanceof BottomActionIcon icon) || icon.kind != trackingIcon) {
                startStopButton.setIcon(new BottomActionIcon(trackingIcon));
            }
            updateVisibleValues();
    }

    private void requestDataRefresh(boolean includeGroups) {
        RangeOption range = selectedRange();
        Snapshot snapshot = tracker.snapshot();
        Instant customStart = ((java.util.Date) customStartSpinner.getValue()).toInstant();
        Instant customEnd = ((java.util.Date) customEndSpinner.getValue()).toInstant();
        TimeWindow window = range.kind() == RangeOption.Kind.SESSION
                ? new TimeWindow(snapshot.sessionStart(), Instant.now())
                : range.resolve(zone, customStart, customEnd);
        long generation = queryGeneration.incrementAndGet();
        queryExecutor.submit(() -> {
            Totals totals = range.kind() == RangeOption.Kind.SESSION ? snapshot.totals() : store.totals(window.start(), window.end());
            List<Group> groups = includeGroups ? store.groups(window.start(), window.end(), zone) : displayedGroups;
            List<Group> daily = includeGroups ? store.dailyGroups(window.start(), window.end(), zone) : displayedDaily;
            SwingUtilities.invokeLater(() -> {
                if (generation != queryGeneration.get()) return;
                displayedTotals = totals;
                if (includeGroups) {
                    displayedGroups = groups;
                    displayedDaily = daily;
                    updateTableAndChart();
                }
                updateVisibleValues();
            });
        });
    }

    private void updateVisibleValues() {
        Totals totals = displayedTotals;
        String mouse = UnitConverter.formatDistance(totals.mousePixels(), selectedUnit(), selectedPpi());
        mouseCard.setValue(mouse);
        keysCard.setValue(String.format(Locale.getDefault(), "%,d", totals.keyPresses()));
        clicksCard.setValue(String.format(Locale.getDefault(), "%,d", totals.mouseClicks()));
        activeCard.setValue(UnitConverter.formatDuration(totals.activeSeconds()));
        miniMouseActivity.setValues(mouse, String.format(Locale.getDefault(), "%,d", totals.mouseClicks()));
        miniKeys.setValue(String.format(Locale.getDefault(), "%,d", totals.keyPresses()));
        if (miniRangeLabel != null) miniRangeLabel.setText(selectedRange().label());
        if (miniActiveValueLabel != null) miniActiveValueLabel.setText(UnitConverter.formatDuration(totals.activeSeconds()));
    }

    private void updateTableAndChart() {
        chartPanel.setDisplayContext(selectedUnit(), selectedPpi());
        chartPanel.setGroups(displayedGroups);
        tableModel.setRows(displayedDaily, selectedUnit(), selectedPpi());
    }

    private void enterMiniMode() {
        if (miniMode || viewTransitionInProgress || updateReviewTransitionInProgress) return;
        if (services != null) services.beforeEnterMiniView();
        if (miniMode || viewTransitionInProgress || updateReviewTransitionInProgress) return;
        if (updateReviewMiniState != null) {
            restoreMiniAfterUpdateReview(false);
            return;
        }
        viewTransitionInProgress = true;
        fullBounds = getBounds();
        persistFullBounds(fullBounds);
        Point targetLocation = miniLocation != null
                ? new Point(miniLocation)
                : new Point(fullBounds.x, fullBounds.y);

        miniMode = true;
        usage("view_changed", Map.of("view", "mini"));
        setAlwaysOnTop(alwaysOnTopButton.isSelected());
        setResizable(false);
        getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        setTitle("Digital Marathon — Mini");
        setContentPane(miniPanel);
        setMinimumSize(new Dimension(262, 88));
        setSize(262, 88);
        Point visibleLocation = keepOnScreen(targetLocation, getSize());
        setLocation(visibleLocation);
        miniLocation = new Point(visibleLocation);
        alwaysOnTopButton.setEnabled(true);
        applyTheme();
        validate();
        repaint();
        if (APPLE_CHROME) {
            getRootPane().putClientProperty("apple.awt.fullscreenable", Boolean.FALSE);
            SwingUtilities.invokeLater(() -> MacNativeInputBackend.applyNativeMiniWindowChrome(true));
        }

        // The same native window is reused, so its current opacity already
        // carries into Mini View. Do not call AppKit during this content swap:
        // doing so can contend with AWT's peer lock and delay title-bar dragging.
        // Clear the transition guard now so the very first manual move is saved.
        preferences.putBoolean("lastViewMini", true);
        viewTransitionInProgress = false;
    }

    private void exitMiniMode() {
        if (!miniMode || viewTransitionInProgress || updateReviewTransitionInProgress) return;
        viewTransitionInProgress = true;
        rememberMiniLocation();
        miniMode = false;
        usage("view_changed", Map.of("view", "full"));
        setAlwaysOnTop(false);
        setResizable(true);
        getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.FALSE);
        setTitle("Digital Marathon");
        setContentPane(fullPanel);
        setMinimumSize(new Dimension(840, 680));
        if (fullBounds != null) setBounds(fullBounds);
        else setSize(880, 740);
        validate();
        repaint();
        if (APPLE_CHROME) {
            getRootPane().putClientProperty("apple.awt.fullscreenable", Boolean.TRUE);
            SwingUtilities.invokeLater(() -> MacNativeInputBackend.applyNativeMiniWindowChrome(false));
        }
        requestDataRefresh(true);
        // Opacity belongs to the reused native window and needs no reapply.
        // Release the guard synchronously so repeated toggles cannot leave the
        // window in a temporarily non-interactive state.
        preferences.putBoolean("lastViewMini", false);
        viewTransitionInProgress = false;
    }

    boolean isMiniView() { return miniMode; }

    boolean isUpdateReviewFromMini() { return updateReviewMiniState != null; }

    /** Shared updater state only changes the compact notice and its button badge. */
    void updateMiniNotice(String text, boolean badge) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateMiniNotice(text, badge));
            return;
        }
        miniUpdateBadge = badge;
        if (miniUpdateNotice != null) {
            String notice = text == null ? "" : text.trim();
            miniUpdateNotice.setText(notice);
            miniUpdateNotice.setEnabled(!notice.isEmpty());
            miniUpdateNotice.setFocusable(!notice.isEmpty());
            miniUpdateNotice.setToolTipText(null);
            miniUpdateNotice.getAccessibleContext().setAccessibleName(
                    notice.isEmpty() ? "No update notice" : notice);
            miniUpdateNotice.getAccessibleContext().setAccessibleDescription(
                    "Opens update review in Full View; current work continues");
            miniUpdateNotice.repaint();
        }
        updateMiniFullViewButton();
    }

    /** Expand before any update panel is created, then wait for stable full layout. */
    void reviewUpdateInFullView(Runnable onReady, Runnable onFailure) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> reviewUpdateInFullView(onReady, onFailure));
            return;
        }
        if (updateReviewTransitionInProgress || viewTransitionInProgress) return;
        if (!miniMode) {
            SwingUtilities.invokeLater(() -> {
                if (!miniMode && !viewTransitionInProgress) onReady.run();
                else onFailure.run();
            });
            return;
        }
        updateReviewMiniState = new MiniReviewState(new Rectangle(getBounds()),
                fullBounds == null ? null : new Rectangle(fullBounds), transparencyPercent,
                alwaysOnTopButton.isSelected(), isAlwaysOnTop());
        try {
            exitMiniMode();
            if (miniMode || getContentPane() != fullPanel) {
                failUpdateReviewExpansion(onFailure);
                return;
            }
            updateReviewTransitionInProgress = true;
            viewTransitionInProgress = true;
            final long started = System.nanoTime();
            final Rectangle[] previous = {new Rectangle(getBounds())};
            final int[] stableSamples = {0};
            updateReviewResizeTimer = new Timer(60, event -> {
                Rectangle current = getBounds();
                boolean fullLayout = !miniMode && getContentPane() == fullPanel
                        && getWidth() >= 840 && getHeight() >= 680
                        && fullPanel.getWidth() > 0 && fullPanel.getHeight() > 0;
                stableSamples[0] = fullLayout && current.equals(previous[0])
                        ? stableSamples[0] + 1 : 0;
                previous[0] = new Rectangle(current);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                // A few queued paints/resizes must pass before exposing a dialog.
                // This prevents attaching a normal-size panel to the old Mini peer.
                if (elapsedMillis >= 240 && stableSamples[0] >= 3) {
                    stopUpdateReviewResizeWait();
                    try { onReady.run(); }
                    catch (RuntimeException error) { failUpdateReviewExpansion(onFailure); }
                } else if (elapsedMillis >= 2400 || !isDisplayable()) {
                    failUpdateReviewExpansion(onFailure);
                }
            });
            updateReviewResizeTimer.start();
        } catch (RuntimeException error) {
            failUpdateReviewExpansion(onFailure);
        }
    }

    private void stopUpdateReviewResizeWait() {
        if (updateReviewResizeTimer != null) updateReviewResizeTimer.stop();
        updateReviewResizeTimer = null;
        updateReviewTransitionInProgress = false;
        viewTransitionInProgress = false;
    }

    private void failUpdateReviewExpansion(Runnable onFailure) {
        stopUpdateReviewResizeWait();
        restoreMiniAfterUpdateReview(true);
        onFailure.run();
    }

    /** The caller closes its update panel before asking the owner to shrink. */
    void returnToMiniAfterUpdateReview() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::returnToMiniAfterUpdateReview);
            return;
        }
        stopUpdateReviewResizeWait();
        restoreMiniAfterUpdateReview(false);
    }

    private void restoreMiniAfterUpdateReview(boolean expansionFailed) {
        MiniReviewState saved = updateReviewMiniState;
        if (saved == null) return;
        updateReviewMiniState = null;
        miniLocation = new Point(saved.bounds().getLocation());
        alwaysOnTopButton.setSelected(saved.pinPreference());
        preferences.putBoolean("alwaysOnTop", saved.pinPreference());
        enterMiniMode();
        if (miniMode) {
            setSize(saved.bounds().getSize());
            Point location = keepOnScreen(saved.bounds().getLocation(), saved.bounds().getSize());
            setLocation(location);
            miniLocation = new Point(location);
            persistMiniLocation(location);
            setAlwaysOnTop(saved.alwaysOnTop());
            changeTransparency(saved.transparency(), false);
            preferences.putInt("transparencyPercent", transparencyPercent);
            preferences.putBoolean("lastViewMini", true);
        }
        if (expansionFailed && saved.normalBounds() != null) {
            fullBounds = new Rectangle(saved.normalBounds());
            persistFullBounds(fullBounds);
        }
    }

    private Rectangle loadSavedFullBounds() {
        int missing = Integer.MIN_VALUE;
        int x = preferences.getInt("fullWindowX", missing);
        int y = preferences.getInt("fullWindowY", missing);
        int width = preferences.getInt("fullWindowWidth", missing);
        int height = preferences.getInt("fullWindowHeight", missing);
        if (x == missing || y == missing || width == missing || height == missing) return null;

        Dimension size = new Dimension(Math.max(800, width), Math.max(590, height));
        Point visibleLocation = keepOnScreen(new Point(x, y), size);
        return new Rectangle(visibleLocation, size);
    }

    private Point loadSavedMiniLocation() {
        int missing = Integer.MIN_VALUE;
        int x = preferences.getInt("miniWindowX", missing);
        int y = preferences.getInt("miniWindowY", missing);
        return x == missing || y == missing ? null : new Point(x, y);
    }

    private void rememberMiniLocation() {
        Point location = getLocation();
        miniLocation = new Point(location);
        persistMiniLocation(location);
    }

    private void scheduleMiniLocationSave() {
        if (miniLocationSaveTimer == null) {
            miniLocationSaveTimer = new Timer(180, event -> {
                Point location = miniLocation;
                if (location != null) persistMiniLocation(location);
            });
            miniLocationSaveTimer.setRepeats(false);
        }
        miniLocationSaveTimer.restart();
    }

    private void persistMiniLocation(Point location) {
        preferences.putInt("miniWindowX", location.x);
        preferences.putInt("miniWindowY", location.y);
    }

    private void rememberFullBounds() {
        Rectangle bounds = getBounds();
        fullBounds = new Rectangle(bounds);
        persistFullBounds(bounds);
    }

    private void scheduleFullBoundsSave() {
        if (fullBoundsSaveTimer == null) {
            fullBoundsSaveTimer = new Timer(180, event -> {
                Rectangle bounds = fullBounds;
                if (bounds != null) persistFullBounds(bounds);
            });
            fullBoundsSaveTimer.setRepeats(false);
        }
        fullBoundsSaveTimer.restart();
    }

    private void persistFullBounds(Rectangle bounds) {
        preferences.putInt("fullWindowX", bounds.x);
        preferences.putInt("fullWindowY", bounds.y);
        preferences.putInt("fullWindowWidth", bounds.width);
        preferences.putInt("fullWindowHeight", bounds.height);
    }

    private void rememberCurrentWindowState() {
        preferences.putBoolean("lastViewMini", miniMode);
        if (miniMode) rememberMiniLocation();
        else rememberFullBounds();
    }

    private void flushPreferences() {
        try {
            preferences.flush();
        } catch (BackingStoreException ignored) {
            // Preferences are also persisted incrementally while the app runs.
        }
    }

    private static Point keepOnScreen(Point requested, Dimension size) {
        int width = Math.max(1, size.width);
        int height = Math.max(1, size.height);
        Rectangle requestedBounds = new Rectangle(requested.x, requested.y, width, height);
        Rectangle best = null;
        long bestIntersection = -1L;
        double bestDistance = Double.POSITIVE_INFINITY;

        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : environment.getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = new Rectangle(configuration.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            Rectangle usable = new Rectangle(
                    bounds.x + insets.left,
                    bounds.y + insets.top,
                    Math.max(1, bounds.width - insets.left - insets.right),
                    Math.max(1, bounds.height - insets.top - insets.bottom));

            Rectangle intersection = requestedBounds.intersection(usable);
            long area = intersection.isEmpty() ? 0L : (long) intersection.width * intersection.height;
            double dx = requested.getX() - usable.getCenterX();
            double dy = requested.getY() - usable.getCenterY();
            double distance = dx * dx + dy * dy;
            if (area > bestIntersection || (area == bestIntersection && distance < bestDistance)) {
                best = usable;
                bestIntersection = area;
                bestDistance = distance;
            }
        }

        if (best == null) {
            best = environment.getMaximumWindowBounds();
        }
        int maxX = Math.max(best.x, best.x + best.width - width);
        int maxY = Math.max(best.y, best.y + best.height - height);
        int x = Math.max(best.x, Math.min(requested.x, maxX));
        int y = Math.max(best.y, Math.min(requested.y, maxY));
        return new Point(x, y);
    }

    private Palette palette() {
        return darkTheme ? DARK_THEME : LIGHT_THEME;
    }

    private void toggleTheme() {
        darkTheme = !darkTheme;
        usage("appearance_changed", Map.of("appearance", darkTheme ? "dark" : "light"));
        preferences.putBoolean("darkTheme", darkTheme);
        applyTheme();
        if (services != null) services.appearanceChanged();
    }

    private JPanel buildTransparencyControl(boolean compact) {
        JPanel control = new JPanel();
        control.setOpaque(false);
        control.putClientProperty(WindowDragSupport.NO_WINDOW_DRAG, Boolean.TRUE);
        control.setLayout(new BoxLayout(control, BoxLayout.X_AXIS));
        if (!compact) {
            JLabel label = new JLabel("Transparency");
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 10.5f));
            setThemeRole(label, "muted");
            control.add(label);
            control.add(Box.createHorizontalStrut(5));
        }
        JSlider slider = new JSlider(0, 75, transparencyPercent);
        slider.setOpaque(false);
        slider.setFocusable(true);
        slider.setRequestFocusEnabled(false);
        slider.setBorder(BorderFactory.createEmptyBorder());
        slider.setToolTipText("Window transparency: 0–75%");
        slider.getAccessibleContext().setAccessibleName(compact
                ? "Mini View transparency" : "Full View transparency");
        slider.getAccessibleContext().setAccessibleDescription("Zero is opaque; seventy-five percent is most transparent");
        Dimension sliderSize = new Dimension(compact ? 63 : 66, compact ? 21 : 28);
        slider.setPreferredSize(sliderSize);
        slider.setMinimumSize(sliderSize);
        slider.setMaximumSize(sliderSize);
        slider.setUI(new SlimTransparencySliderUI(slider, palette(), darkTheme));
        slider.addChangeListener(event -> {
            if (updatingTransparencyControls) return;
            TranslucentToolTipPopupFactory.hideActiveTooltip();
            changeTransparency(slider.getValue(), !slider.getValueIsAdjusting());
        });
        installImmediateTooltipDismiss(slider);
        JLabel value = new JLabel(transparencyPercent + "%", SwingConstants.RIGHT);
        value.setFont(value.getFont().deriveFont(Font.PLAIN, compact ? 9f : 11f));
        Dimension valueSize = new Dimension(compact ? 23 : 28, compact ? 21 : 28);
        value.setPreferredSize(valueSize);
        value.setMinimumSize(valueSize);
        value.setMaximumSize(valueSize);
        setThemeRole(value, "muted");
        control.add(slider);
        control.add(Box.createHorizontalStrut(3));
        control.add(value);
        if (compact) {
            miniTransparencySlider = slider;
            miniTransparencyValueLabel = value;
        } else {
            transparencySlider = slider;
            transparencyValueLabel = value;
        }
        Dimension size = new Dimension(compact ? 89 : 190, compact ? 23 : 32);
        control.setPreferredSize(size);
        control.setMinimumSize(size);
        control.setMaximumSize(size);
        return control;
    }

    private void changeTransparency(int value, boolean showError) {
        int next = normaliseTransparency(value);
        if (!applyTransparency(next, showError)) {
            updateTransparencyControls();
            return;
        }
        transparencyPercent = next;
        // Save once at release (or a keyboard step), avoiding preferences work
        // on every pointer movement while the bar is being dragged.
        if (showError) preferences.putInt("transparencyPercent", transparencyPercent);
        updateTransparencyControls();
    }

    private void applyCurrentTransparency(boolean showError) {
        if (applyTransparency(transparencyPercent, showError)) return;
        transparencyPercent = 0;
        preferences.putInt("transparencyPercent", 0);
        try {
            if (isDisplayable()) setOpacity(1.0f);
        } catch (RuntimeException ignored) {
            // The fallback is already the platform's normal opaque window.
        }
        updateTransparencyControls();
    }

    private boolean applyTransparency(int percent, boolean showError) {
        if (!isDisplayable()) return true;
        float opacity = Math.max(0.25f, 1.0f - (percent / 100.0f));
        WindowOpacitySupport.Result result = WindowOpacitySupport.apply(this, opacity);
        if (result.success()) return true;
        if (showError) {
            JOptionPane.showMessageDialog(this,
                    "Window transparency could not be applied on this desktop session.\n\n"
                            + result.detail(),
                    "Transparency unavailable", JOptionPane.INFORMATION_MESSAGE);
        }
        return false;
    }

    private void toggleCapturePrivacy() {
        if (capturePrivacyToggle == null) return;
        boolean requested = capturePrivacyToggle.isSelected();

        CapturePrivacySupport.Result result = CapturePrivacySupport.apply(requested);
        if (!result.success()) {
            capturePrivacyToggle.setSelected(capturePrivacyApplied);
            updateCapturePrivacyControls();
            String detail = result.detail() == null || result.detail().isBlank()
                    ? "Screen Capture Privacy could not be changed right now."
                    : result.detail();
            JOptionPane.showMessageDialog(
                    this,
                    detail,
                    result.supported() ? "Screen Capture Privacy unavailable" : "Screen Capture Privacy not supported",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        capturePrivacyEnabled = requested;
        capturePrivacyApplied = requested;
        preferences.putBoolean("capturePrivacyEnabled", capturePrivacyEnabled);
        updateCapturePrivacyControls();
        usage("capture_privacy_changed", Map.of("enabled", requested));
    }

    private boolean applyCurrentCapturePrivacy(boolean showError) {
        CapturePrivacySupport.Result result = CapturePrivacySupport.apply(capturePrivacyEnabled);
        capturePrivacyApplied = result.success() && capturePrivacyEnabled;
        updateCapturePrivacyControls();
        if (!result.success() && showError) {
            String detail = result.detail() == null || result.detail().isBlank()
                    ? "Screen Capture Privacy could not be applied to this window."
                    : result.detail();
            JOptionPane.showMessageDialog(
                    this,
                    detail,
                    result.supported() ? "Screen Capture Privacy unavailable" : "Screen Capture Privacy not supported",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        return result.success();
    }

    private void updateCapturePrivacyControls() {
        Palette palette = palette();
        Color protectedColor = palette.success();
        Color neutral = darkTheme ? new Color(190, 198, 210) : new Color(104, 114, 128);
        Color iconColor = capturePrivacyApplied ? protectedColor : neutral;

        if (capturePrivacyToggle != null) {
            capturePrivacyToggle.setSelected(capturePrivacyApplied);
            capturePrivacyToggle.setText("Hide from screenshots & sharing");
            capturePrivacyToggle.setForeground(palette.muted());
            capturePrivacyToggle.getAccessibleContext().setAccessibleDescription(capturePrivacyApplied
                    ? "Digital Marathon is hidden from supported screenshots and screen sharing."
                    : "Digital Marathon can appear in screenshots and screen sharing.");
        }
        if (capturePrivacyIndicator != null) {
            capturePrivacyIndicator.setIcon(new PrivacyShieldIcon(capturePrivacyApplied, iconColor, 14));
            capturePrivacyIndicator.getAccessibleContext().setAccessibleName(capturePrivacyApplied
                    ? "Screen Capture Privacy protected"
                    : "Screen Capture Privacy visible");
            capturePrivacyIndicator.setToolTipText(capturePrivacyApplied
                    ? "Hidden from screenshots and sharing" : "Visible in screenshots and sharing");
        }
        if (miniCapturePrivacyIndicator != null) {
            miniCapturePrivacyIndicator.setIcon(new PrivacyShieldIcon(capturePrivacyApplied, iconColor, 12));
            miniCapturePrivacyIndicator.getAccessibleContext().setAccessibleName(capturePrivacyApplied
                    ? "Screen Capture Privacy protected"
                    : "Screen Capture Privacy visible");
            miniCapturePrivacyIndicator.setToolTipText(capturePrivacyApplied
                    ? "Hidden from screenshots and sharing" : "Visible in screenshots and sharing");
        }
    }

    private static JLabel privacyIndicatorLabel(int iconSize, int height) {
        JLabel label = new JLabel();
        label.setOpaque(false);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(iconSize + 4, height));
        label.setMinimumSize(new Dimension(iconSize + 4, height));
        label.setMaximumSize(new Dimension(iconSize + 4, height));
        label.setToolTipText(null);
        return label;
    }

    private static void configureBottomActionButton(JButton button) {
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 11f));
        button.setMargin(new Insets(5, 8, 5, 8));
        button.setIconTextGap(6);
        button.setPreferredSize(new Dimension(128, 38));
        button.setMinimumSize(new Dimension(105, 38));
        button.setMaximumSize(new Dimension(145, 38));
    }

    private static int normaliseTransparency(int value) {
        return Math.max(0, Math.min(75, value));
    }

    private void applyTheme() {
        Palette palette = palette();
        // Ask macOS to style the title bar for the selected appearance. All content
        // controls below use Basic Swing delegates so Aqua cannot silently replace
        // our requested foreground colors with unreadable white-on-white controls.
        getRootPane().putClientProperty("apple.awt.windowAppearance",
                darkTheme ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        setBackground(palette.page());
        if (fullPanel != null) fullPanel.setBackground(palette.page());
        if (miniPanel != null) miniPanel.setBackground(palette.page());

        if (filtersPanel != null) filtersPanel.setPanelColors(palette.page(), palette.page());
        if (chartCard != null) chartCard.setPanelColors(palette.surface(), palette.border());
        if (tableCard != null) tableCard.setPanelColors(palette.surface(), palette.border());

        if (mouseCard != null) mouseCard.applyTheme(palette.blueTint(), palette.border(), palette.text(),
                darkTheme ? new Color(112, 174, 226) : new Color(45, 116, 195));
        if (keysCard != null) keysCard.applyTheme(palette.greenTint(), palette.border(), palette.text(),
                darkTheme ? new Color(105, 193, 146) : new Color(54, 135, 92));
        if (clicksCard != null) clicksCard.applyTheme(palette.amberTint(), palette.border(), palette.text(),
                darkTheme ? new Color(226, 166, 108) : new Color(174, 116, 40));
        if (activeCard != null) activeCard.applyTheme(palette.violetTint(), palette.border(), palette.text(),
                darkTheme ? new Color(185, 161, 228) : new Color(121, 91, 173));
        Color miniMouseAccent = darkTheme ? new Color(112, 174, 226) : new Color(58, 126, 194);
        Color miniClickAccent = darkTheme ? new Color(226, 166, 108) : new Color(194, 119, 58);
        Color miniKeyAccent = darkTheme ? new Color(105, 193, 146) : new Color(54, 145, 96);
        if (chartPanel != null) chartPanel.applyTheme(palette.chartAxis(), palette.muted(), palette.chartBar(), palette.chartTrend());

        applyThemeToTree(fullPanel, palette);
        applyThemeToTree(miniPanel, palette);

        // Reapply Mini View semantic accents after the generic tree pass. The tree
        // intentionally normalises ordinary labels, so Mini View's three headings
        // need their distinct non-black colors restored afterward.
        if (miniMouseActivity != null) miniMouseActivity.applyTheme(
                palette.blueTint(), palette.border(), palette.text(), miniMouseAccent, miniClickAccent, palette.control());
        if (miniKeys != null) miniKeys.applyTheme(palette.greenTint(), palette.border(), palette.text(), miniKeyAccent);
        if (miniRangeLabel != null) {
            miniRangeLabel.setForeground(palette.muted());
            miniRangeLabel.setIcon(new CalendarRangeIcon(palette.chartBar(), 12));
        }
        if (miniActiveLabel != null) {
            miniActiveLabel.setForeground(palette.muted());
            miniActiveLabel.setIcon(new MiniActiveClockIcon(palette.success(), 12));
        }
        if (miniActiveValueLabel != null) miniActiveValueLabel.setForeground(palette.success());
        if (miniUpdateNotice != null) miniUpdateNotice.setForeground(
                darkTheme ? new Color(118, 170, 229) : new Color(53, 110, 183));
        if (miniFooterSeparator != null) {
            miniFooterSeparator.setLineColor(darkTheme
                    ? mixColor(palette.border(), palette.text(), 0.28f)
                    : mixColor(palette.border(), palette.text(), 0.20f));
        }

        if (intervalTable != null) {
            intervalTable.setBackground(palette.surface());
            intervalTable.setForeground(palette.text());
            intervalTable.setGridColor(palette.border());
            intervalTable.setSelectionBackground(palette.selection());
            intervalTable.setSelectionForeground(palette.text());
            intervalTable.getTableHeader().setBackground(palette.control());
            intervalTable.getTableHeader().setForeground(palette.text());
        }
        if (tableScroll != null) {
            tableScroll.setBackground(palette.surface());
            tableScroll.getViewport().setBackground(palette.surface());
        }

        if (statusLabel != null) {
            Snapshot snapshot = tracker.snapshot();
            statusLabel.setForeground(snapshot.globalKeysAvailable() ? palette.success() : palette.warning());
        }

        UIManager.put("ToolTip.background", darkTheme ? new Color(44, 44, 46) : new Color(250, 250, 252));
        UIManager.put("ToolTip.foreground", darkTheme ? new Color(245, 245, 247) : new Color(29, 29, 31));
        UIManager.put("ToolTip.outline", darkTheme ? new Color(78, 78, 82) : new Color(210, 210, 215));
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(7, 10, 7, 10));
        UIManager.put("OptionPane.background", palette.page());
        UIManager.put("OptionPane.messageForeground", palette.text());
        UIManager.put("Panel.background", palette.page());
        UIManager.put("Table.background", palette.surface());
        UIManager.put("Table.foreground", palette.text());
        UIManager.put("Table.gridColor", palette.border());
        UIManager.put("TableHeader.background", palette.control());
        UIManager.put("TableHeader.foreground", palette.text());

        updateThemeButton();
        updateTransparencyControls();
        updateCapturePrivacyControls();
        revalidate();
        repaint();
    }

    private void applyThemeToTree(Component component, Palette palette) {
        if (component == null) return;
        if (component instanceof JComponent swingComponent) {
            Object roleValue = swingComponent.getClientProperty("themeRole");
            String role = roleValue == null ? "" : roleValue.toString();

            if (swingComponent instanceof JLabel label) {
                Object fixedForeground = swingComponent.getClientProperty("fixedForeground");
                if (fixedForeground instanceof Color color) {
                    label.setForeground(color);
                } else {
                    label.setForeground("muted".equals(role) ? palette.muted() : palette.text());
                }
            } else if (swingComponent instanceof JToggleButton toggleButton) {
                if ("miniPinButton".equals(role)) {
                    toggleButton.setUI(new AppleToolbarButtonUI(palette, darkTheme));
                    toggleButton.setForeground(palette.text());
                    toggleButton.setBackground(palette.control());
                    toggleButton.setOpaque(false);
                    toggleButton.setContentAreaFilled(false);
                    toggleButton.setBorder(new EmptyBorder(2, 4, 2, 4));
                } else if ("capturePrivacyToggle".equals(role)) {
                    toggleButton.setUI(new PrivacyToggleButtonUI(palette, darkTheme));
                    toggleButton.setFont(new Font(toggleButton.getFont().getFamily(), Font.PLAIN, 11));
                    toggleButton.setForeground(palette.muted());
                    toggleButton.setBackground(palette.page());
                    toggleButton.setOpaque(false);
                    toggleButton.setContentAreaFilled(false);
                    toggleButton.setBorder(new EmptyBorder(5, 8, 5, 44));
                    toggleButton.setIcon(null);
                    toggleButton.setSelectedIcon(null);
                    toggleButton.setHorizontalAlignment(SwingConstants.LEFT);
                    toggleButton.setIconTextGap(6);
                }
            } else if (swingComponent instanceof JButton button) {
                // Aqua may ignore setForeground() while drawing a native light button
                // in a dark Java window. A Basic delegate gives identical, explicit
                // contrast on macOS, Windows and Linux.
                boolean primary = "primaryButton".equals(role);
                boolean trackingAction = "trackingButton".equals(role);
                boolean viewMode = "viewModeButton".equals(role);
                boolean reportButton = "reportButton".equals(role);
                boolean downloadButton = "downloadButton".equals(role);
                boolean miniToolbar = "miniToolbarButton".equals(role);
                boolean compactArrow = button.getParent() instanceof JComboBox<?> ||
                        button.getParent() instanceof JSpinner;
                Color viewBackground = mixColor(palette.selection(), palette.chartBar(), darkTheme ? 0.34f : 0.24f);
                Color outline = viewMode ? palette.chartBar() : palette.border();
                if (button instanceof MiniUpdateNotice) {
                    button.setUI(new BasicButtonUI());
                    button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                    button.setForeground(palette.chartBar());
                    button.setBackground(palette.page());
                } else if (miniToolbar) {
                    button.setUI(new AppleToolbarButtonUI(palette, darkTheme));
                    button.setForeground(palette.text());
                    button.setBackground(palette.control());
                } else if (downloadButton) {
                    button.setUI(new BrightDownloadButtonUI(palette, darkTheme));
                    button.setForeground(Color.WHITE);
                    button.setBackground(palette.chartBar());
                    button.setIcon(new ReportMedalIcon(new Color(255, 255, 255), new Color(255, 221, 92), 22));
                } else if (reportButton) {
                    button.setUI(new AppleActionButtonUI(palette, darkTheme, true));
                    button.setForeground(darkTheme ? new Color(125, 181, 246) : new Color(41, 103, 190));
                    button.setBackground(palette.selection());
                    button.setIcon(new ReportMedalIcon(button.getForeground(), button.getForeground(), 15));
                } else if (viewMode) {
                    button.setUI(new AppleActionButtonUI(palette, darkTheme, true));
                    button.setForeground(palette.text());
                    button.setBackground(viewBackground);
                } else if (compactArrow) {
                    button.setUI(new BasicButtonUI());
                    button.setForeground(palette.text());
                    button.setBackground(palette.control());
                } else {
                    button.setUI(new AppleActionButtonUI(palette, darkTheme, primary, trackingAction));
                    button.setForeground("dangerButton".equals(role)
                            ? (darkTheme ? new Color(236, 172, 162) : new Color(172, 79, 70))
                            : primary ? palette.chartBar() : palette.text());
                    button.setBackground(primary ? palette.selection() : palette.control());
                }
                button.setRolloverEnabled(!trackingAction);
                boolean applePainted = !compactArrow;
                button.setOpaque(compactArrow);
                button.setContentAreaFilled(compactArrow);
                button.setBorderPainted(compactArrow);
                Insets margin = button.getMargin();
                if (margin == null) margin = new Insets(5, 10, 5, 10);
                if (compactArrow) {
                    button.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
                } else {
                    button.setBorder(new EmptyBorder(margin.top, margin.left, margin.bottom, margin.right));
                }
            } else if (swingComponent instanceof JCheckBox checkBox) {
                checkBox.setUI(new BasicCheckBoxUI());
                checkBox.setEnabled(true);
                checkBox.setForeground(palette.text());
                checkBox.setBackground(palette.page());
                checkBox.setOpaque(false);
                CheckBoxGlyph glyph = new CheckBoxGlyph(
                        palette.control(), palette.selection(), palette.border(), palette.text());
                checkBox.setIcon(glyph);
                checkBox.setSelectedIcon(glyph);
                checkBox.setDisabledIcon(glyph);
                checkBox.setDisabledSelectedIcon(glyph);
                checkBox.setIconTextGap(4);
            } else if (swingComponent instanceof JComboBox<?> comboBox) {
                comboBox.setUI(new SoftComboBoxUI(
                        palette.input(), palette.control(), palette.text(), palette.border()));
                comboBox.setForeground(palette.text());
                comboBox.setBackground(palette.input());
                comboBox.setBorder(BorderFactory.createLineBorder(palette.border(), 1, true));
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(
                            JList<?> list, Object value, int index, boolean selected, boolean hasFocus) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(
                                list, value, index, selected, hasFocus);
                        label.setOpaque(true);
                        label.setText(value == null ? "" : value.toString());
                        label.setForeground(palette.text());
                        label.setBackground(selected ? palette.selection() : palette.input());
                        label.setBorder(new EmptyBorder(3, 8, 3, 8));
                        return label;
                    }
                });
            } else if (swingComponent instanceof JSpinner spinner) {
                spinner.setUI(new BasicSpinnerUI());
                spinner.setForeground(palette.text());
                spinner.setBackground(palette.input());
                spinner.setBorder(BorderFactory.createLineBorder(palette.border(), 1, true));
                JComponent editor = spinner.getEditor();
                if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
                    defaultEditor.getTextField().setUI(new BasicTextFieldUI());
                    defaultEditor.getTextField().setForeground(palette.text());
                    defaultEditor.getTextField().setBackground(palette.input());
                    defaultEditor.getTextField().setCaretColor(palette.text());
                    defaultEditor.getTextField().setBorder(new EmptyBorder(2, 5, 2, 5));
                }
            } else if (swingComponent instanceof JTextField textField) {
                textField.setUI(new BasicTextFieldUI());
                textField.setForeground(palette.text());
                textField.setBackground(palette.input());
                textField.setCaretColor(palette.text());
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(palette.border(), 1, true),
                        new EmptyBorder(2, 5, 2, 5)));
            } else if (swingComponent instanceof JTextArea textArea) {
                if (!"status".equals(role)) textArea.setForeground(palette.text());
                if (textArea.isOpaque()) textArea.setBackground(palette.input());
                textArea.setCaretColor(palette.text());
            } else if (swingComponent instanceof JTable table) {
                table.setBackground(palette.surface());
                table.setForeground(palette.text());
                table.setGridColor(palette.border());
            } else if (swingComponent instanceof JScrollPane scrollPane) {
                scrollPane.setBackground(palette.surface());
                scrollPane.getViewport().setBackground(palette.surface());
            } else if (swingComponent instanceof JPanel panel && panel.isOpaque()) {
                panel.setBackground(palette.page());
            }
        }

        if (component instanceof Container container) {
            Component[] children = container.getComponents();
            for (Component child : children) applyThemeToTree(child, palette);
        }
    }

    private void updateThemeButton() {
        Palette palette = palette();
        String tooltip = darkTheme ? "Switch to light appearance" : "Switch to dark appearance";
        if (themeButton != null) {
            themeButton.setIcon(new ThemeToggleIcon(darkTheme, palette.text()));
            themeButton.setToolTipText(tooltip);
            themeButton.getAccessibleContext().setAccessibleName(tooltip);
        }
        if (miniThemeButton != null) {
            Color miniNeutral = darkTheme ? new Color(190, 198, 210) : new Color(74, 85, 103);
            miniThemeButton.setIcon(new ThemeToggleIcon(darkTheme, miniNeutral, 12));
            miniThemeButton.setToolTipText(tooltip);
            miniThemeButton.getAccessibleContext().setAccessibleName(tooltip);
        }
    }

    private void updateTransparencyControls() {
        updatingTransparencyControls = true;
        try {
            for (JSlider slider : new JSlider[] {transparencySlider, miniTransparencySlider}) {
                if (slider == null) continue;
                slider.setValue(transparencyPercent);
                if (slider.getUI() instanceof SlimTransparencySliderUI ui) {
                    // Keep the same UI/listener throughout a drag. A theme or
                    // value update only changes paint colors and the model.
                    ui.updateAppearance(palette(), darkTheme);
                } else {
                    slider.setUI(new SlimTransparencySliderUI(slider, palette(), darkTheme));
                }
            }
            if (transparencyValueLabel != null) transparencyValueLabel.setText(transparencyPercent + "%");
            if (miniTransparencyValueLabel != null) miniTransparencyValueLabel.setText(transparencyPercent + "%");
        } finally {
            updatingTransparencyControls = false;
        }
        updateMiniFullViewButton();
        updateMiniPinButton();
    }

    private void updateMiniFullViewButton() {
        if (miniFullViewButton == null) return;
        miniFullViewButton.setIcon(new ExpandCornersIcon(12, miniUpdateBadge, palette().chartBar()));
        miniFullViewButton.setForeground(darkTheme ? new Color(118, 170, 229) : new Color(53, 110, 183));
        String action = miniUpdateBadge ? "Open Full View to review update" : "Open Full View";
        miniFullViewButton.setToolTipText(action);
        miniFullViewButton.getAccessibleContext().setAccessibleName(action);
    }

    /** A keyboard-accessible link painted in the existing compact toolbar gap. */
    private static final class MiniUpdateNotice extends JButton {
        MiniUpdateNotice() {
            super("");
            setUI(new BasicButtonUI());
            putClientProperty(WindowDragSupport.NO_WINDOW_DRAG, Boolean.TRUE);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setMargin(new Insets(0, 0, 0, 0));
            // Use a plain Font rather than an Aqua FontUIResource: a delegate
            // change must not silently replace the seven-pixel notice font.
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension size = new Dimension(250, 7);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            setEnabled(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { repaint(); }
                @Override public void focusLost(FocusEvent event) { repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            String text = getText();
            if (text == null || text.isEmpty()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                g.setColor(getForeground());
                int width = g.getFontMetrics().stringWidth(text);
                float x = Math.max(1f, (getWidth() - width) / 2f);
                g.drawString(text, x, 5.7f);
                if (hasFocus()) g.drawLine(Math.round(x), 6, Math.round(x) + width, 6);
            } finally { g.dispose(); }
        }
    }

    private void updateMiniPinButton() {
        if (alwaysOnTopButton == null) return;
        Palette palette = palette();
        boolean selected = alwaysOnTopButton.isSelected();
        Color neutral = darkTheme ? new Color(190, 198, 210) : new Color(74, 85, 103);
        Color accent = darkTheme ? new Color(118, 170, 229) : new Color(52, 113, 195);
        Color ink = selected ? accent : neutral;
        alwaysOnTopButton.setIcon(new PinIcon(selected, ink, 12));
        alwaysOnTopButton.setToolTipText(selected
                ? "Always on top is on"
                : "Keep this window on top");
    }

    private static final class SlimTransparencySliderUI extends BasicSliderUI {
        private Palette colors;
        private boolean dark;

        SlimTransparencySliderUI(JSlider slider, Palette colors, boolean dark) {
            super(slider);
            this.colors = colors;
            this.dark = dark;
        }

        void updateAppearance(Palette nextColors, boolean nextDark) {
            if (colors == nextColors && dark == nextDark) return;
            colors = nextColors;
            dark = nextDark;
            slider.repaint();
        }

        @Override protected Dimension getThumbSize() { return new Dimension(14, 14); }

        @Override protected TrackListener createTrackListener(JSlider slider) {
            return new TrackListener() {
                private boolean pointerDown;
                private int dragOffset;

                @Override public void mousePressed(MouseEvent event) {
                    if (!slider.isEnabled() || !SwingUtilities.isLeftMouseButton(event)) return;
                    calculateGeometry();
                    pointerDown = true;
                    // Grabbing the thumb preserves its position. Pressing
                    // anywhere else on the bar jumps directly to that value.
                    dragOffset = thumbRect.contains(event.getPoint())
                            ? event.getX() - (thumbRect.x + thumbRect.width / 2) : 0;
                    if (slider.isRequestFocusEnabled()) slider.requestFocusInWindow();
                    slider.setValueIsAdjusting(true);
                    updateValue(event);
                    event.consume();
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (!pointerDown || !slider.isEnabled()) return;
                    updateValue(event);
                    event.consume();
                }

                @Override public void mouseReleased(MouseEvent event) {
                    if (!pointerDown) return;
                    // Preserve the final pointer position when native motion
                    // events are coalesced immediately before release.
                    updateValue(event);
                    pointerDown = false;
                    dragOffset = 0;
                    slider.setValueIsAdjusting(false);
                    slider.repaint();
                    event.consume();
                }

                private void updateValue(MouseEvent event) {
                    slider.setValue(valueForXPosition(event.getX() - dragOffset));
                }
            };
        }

        @Override public void paintTrack(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int centerY = trackRect.y + trackRect.height / 2;
                int valueX = xPositionForValue(slider.getValue());
                g.setColor(colors.border());
                g.fillRoundRect(trackRect.x, centerY - 1, trackRect.width, 3, 3, 3);
                g.setColor(colors.chartBar());
                g.fillRoundRect(trackRect.x, centerY - 1, Math.max(0, valueX - trackRect.x), 3, 3, 3);
            } finally { g.dispose(); }
        }

        @Override public void paintThumb(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Center the approved small tab in the existing 14 × 14 hit
                // rectangle; the bar geometry and its drag target stay intact.
                int tabY = thumbRect.y + (thumbRect.height - 10) / 2;
                g.setColor(dark ? new Color(205, 220, 233) : new Color(221, 232, 242));
                g.fillRoundRect(thumbRect.x, tabY, 14, 10, 6, 6);
                g.setColor(dark ? new Color(156, 161, 169) : new Color(185, 191, 202));
                g.drawRoundRect(thumbRect.x, tabY, 13, 9, 6, 6);
            } finally { g.dispose(); }
        }

        @Override public void paintFocus(Graphics graphics) {
            // Keep the neutral tab unchanged when keyboard focus is present.
        }
    }

    private static JButton iconButton() {
        JButton button = new JButton();
        setThemeRole(button, "iconButton");
        button.setFocusPainted(false);
        button.setMargin(new Insets(3, 3, 3, 3));
        button.setPreferredSize(new Dimension(34, 32));
        button.setMinimumSize(new Dimension(34, 32));
        button.setMaximumSize(new Dimension(34, 32));
        return button;
    }

    private static JButton miniToolbarButton(Dimension size) {
        JButton button = new JButton();
        setThemeRole(button, "miniToolbarButton");
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setMargin(new Insets(1, 2, 1, 2));
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        installImmediateTooltipDismiss(button);
        return button;
    }

    private static JToggleButton miniToggleButton(Dimension size) {
        JToggleButton button = new JToggleButton();
        setThemeRole(button, "miniPinButton");
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 2, 1, 2));
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        installImmediateTooltipDismiss(button);
        return button;
    }

    private static void installImmediateTooltipDismiss(JComponent component) {
        component.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent event) {
                TranslucentToolTipPopupFactory.hideActiveTooltip();
            }
            @Override public void mousePressed(MouseEvent event) {
                TranslucentToolTipPopupFactory.hideActiveTooltip();
            }
        });
    }

    private static void setThemeRole(JComponent component, String role) {
        component.putClientProperty("themeRole", role);
    }

    private static final class AppleToolbarButtonUI extends BasicButtonUI {
        private final Palette palette;
        private final boolean dark;

        private AppleToolbarButtonUI(Palette palette, boolean dark) {
            this.palette = palette;
            this.dark = dark;
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = new Color(255, 255, 255, dark ? 14 : 68);
                if (button.isSelected()) fill = new Color(0, 122, 255, dark ? 68 : 30);
                else if (model.isPressed()) fill = new Color(palette.text().getRed(), palette.text().getGreen(), palette.text().getBlue(), 34);
                else if (model.isRollover()) fill = new Color(palette.text().getRed(), palette.text().getGreen(), palette.text().getBlue(), 20);
                g.setColor(fill);
                g.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 10, 10);
                g.setColor(new Color(palette.border().getRed(), palette.border().getGreen(), palette.border().getBlue(), 72));
                g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 10, 10);
                if (button.hasFocus() && Boolean.TRUE.equals(button.getClientProperty("miniUpdateReviewControl"))) {
                    g.setColor(palette.muted());
                    g.drawLine(6, component.getHeight() - 4, component.getWidth() - 7, component.getHeight() - 4);
                }
            } finally {
                g.dispose();
            }
            super.paint(graphics, component);
        }
    }

    /** Compact Full View toggle with a native-looking privacy switch at the right edge. */
    private static final class PrivacyToggleButtonUI extends BasicButtonUI {
        private final Palette palette;
        private final boolean dark;

        private PrivacyToggleButtonUI(Palette palette, boolean dark) {
            this.palette = palette;
            this.dark = dark;
        }

        @Override protected void setTextShiftOffset() {
            // The switch itself is the state indicator; keep its label still.
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = Math.max(1, component.getWidth());
                int h = Math.max(1, component.getHeight());
                Color fill = dark ? new Color(255, 255, 255, 16) : new Color(255, 255, 255, 190);
                g.setColor(fill);
                g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
                Color stroke = new Color(palette.border().getRed(), palette.border().getGreen(), palette.border().getBlue(), dark ? 185 : 205);
                g.setColor(stroke);
                g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

                int switchW = 30;
                int switchH = 18;
                int sx = w - switchW - 8;
                int sy = (h - switchH) / 2;
                Color track = button.isSelected()
                        ? (dark ? new Color(10, 132, 255) : new Color(0, 122, 255))
                        : (dark ? new Color(84, 84, 88) : new Color(199, 199, 204));
                g.setColor(track);
                g.fillRoundRect(sx, sy, switchW, switchH, switchH, switchH);
                int knob = 14;
                int kx = button.isSelected() ? sx + switchW - knob - 2 : sx + 2;
                int ky = sy + 2;
                g.setColor(new Color(252, 252, 252));
                g.fillOval(kx, ky, knob, knob);
            } finally {
                g.dispose();
            }
            super.paint(graphics, component);
        }
    }

    /** Rounded macOS-style push button used throughout Full View. */
    private static final class AppleActionButtonUI extends BasicButtonUI {
        private final Palette palette;
        private final boolean dark;
        private final boolean accent;
        private final boolean stateOnly;

        private AppleActionButtonUI(Palette palette, boolean dark, boolean accent) {
            this(palette, dark, accent, false);
        }

        private AppleActionButtonUI(Palette palette, boolean dark, boolean accent, boolean stateOnly) {
            this.palette = palette;
            this.dark = dark;
            this.accent = accent;
            this.stateOnly = stateOnly;
        }

        @Override protected void setTextShiftOffset() {
            if (!stateOnly) super.setTextShiftOffset();
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = Math.max(1, component.getWidth());
                int h = Math.max(1, component.getHeight());
                Color fill;
                Color stroke;
                if (accent) {
                    fill = dark ? new Color(10, 132, 255, 72) : new Color(0, 122, 255, 32);
                    stroke = dark ? new Color(64, 156, 255, 165) : new Color(0, 122, 255, 120);
                } else {
                    fill = dark ? new Color(255, 255, 255, 18) : new Color(255, 255, 255, 178);
                    stroke = new Color(palette.border().getRed(), palette.border().getGreen(), palette.border().getBlue(), dark ? 190 : 210);
                }
                if (!button.isEnabled()) {
                    fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), Math.max(10, fill.getAlpha()/2));
                    stroke = new Color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), Math.max(45, stroke.getAlpha()/2));
                } else if (!stateOnly && model.isPressed()) {
                    fill = accent ? (dark ? new Color(10,132,255,108) : new Color(0,122,255,58))
                            : (dark ? new Color(255,255,255,34) : new Color(232,232,237,230));
                } else if (!stateOnly && model.isRollover()) {
                    fill = accent ? (dark ? new Color(10,132,255,92) : new Color(0,122,255,46))
                            : (dark ? new Color(255,255,255,28) : Color.WHITE);
                }
                int arc = Math.min(14, Math.max(10, h - 14));
                g.setColor(fill);
                g.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g.setColor(stroke);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                if (!stateOnly && model.isRollover() && button.isEnabled()) {
                    g.setColor(new Color(255,255,255,dark ? 10 : 60));
                    g.drawRoundRect(1, 1, Math.max(0,w-3), Math.max(0,h-3), Math.max(8,arc-2), Math.max(8,arc-2));
                }
            } finally {
                g.dispose();
            }
            super.paint(graphics, component);
        }
    }

    /** Small outline actions from the approved Full View design. */
    private static final class BottomActionIcon implements Icon {
        private enum Kind { PAUSE, PLAY, RESET, DOWNLOAD, TRASH }
        private final Kind kind;
        private BottomActionIcon(Kind kind) { this.kind = kind; }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.getForeground());
                g.setStroke(new BasicStroke(1.15f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case PAUSE -> {
                        g.drawRoundRect(3, 2, 2, 10, 1, 1);
                        g.drawRoundRect(9, 2, 2, 10, 1, 1);
                    }
                    case PLAY -> g.drawPolygon(new int[] {4, 11, 4}, new int[] {2, 7, 12}, 3);
                    case RESET -> {
                        g.drawArc(2, 2, 10, 10, 35, 295);
                        g.drawLine(2, 1, 2, 5);
                        g.drawLine(2, 5, 6, 5);
                    }
                    case DOWNLOAD -> {
                        g.drawLine(7, 1, 7, 9);
                        g.drawLine(4, 6, 7, 9);
                        g.drawLine(7, 9, 10, 6);
                        g.drawLine(2, 9, 2, 12);
                        g.drawLine(2, 12, 12, 12);
                        g.drawLine(12, 12, 12, 9);
                    }
                    case TRASH -> {
                        g.drawLine(2, 3, 12, 3);
                        g.drawLine(5, 1, 9, 1);
                        g.drawLine(5, 1, 5, 3);
                        g.drawLine(9, 1, 9, 3);
                        g.drawRoundRect(3, 3, 8, 10, 1, 1);
                        g.drawLine(6, 6, 6, 10);
                        g.drawLine(8, 6, 8, 10);
                    }
                }
            } finally { g.dispose(); }
        }
    }

    /** Four-corner expand glyph matching the supplied Mini View reference. */
    private static final class ExpandCornersIcon implements Icon {
        private final int size;
        private final boolean updateBadge;
        private final Color badgeColor;
        private ExpandCornersIcon(int size) { this(size, false, Color.BLUE); }
        private ExpandCornersIcon(int size, boolean updateBadge, Color badgeColor) {
            this.size = size;
            this.updateBadge = updateBadge;
            this.badgeColor = badgeColor;
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.getForeground());
                g.setStroke(new BasicStroke(0.95f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Short, separated corner strokes match the reference expand glyph
                // instead of visually closing into a square at Retina scale.
                int m=2, l=2, r=size-3, b=size-3;
                g.drawLine(x+m, y+m+l, x+m, y+m); g.drawLine(x+m, y+m, x+m+l, y+m);
                g.drawLine(x+r-l, y+m, x+r, y+m); g.drawLine(x+r, y+m, x+r, y+m+l);
                g.drawLine(x+m, y+b-l, x+m, y+b); g.drawLine(x+m, y+b, x+m+l, y+b);
                g.drawLine(x+r-l, y+b, x+r, y+b); g.drawLine(x+r, y+b-l, x+r, y+b);
                if (updateBadge) {
                    g.setColor(badgeColor);
                    g.fillOval(x + size - 4, y, 4, 4);
                }
            } finally { g.dispose(); }
        }
    }

    /** Compact calendar glyph used by Mini View to show the selected Full View range. */
    private static final class CalendarRangeIcon implements Icon {
        private final Color color;
        private final int size;

        private CalendarRangeIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(0.95f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int left = x + 1;
                int top = y + 2;
                int right = x + size - 2;
                int bottom = y + size - 1;
                g.drawRoundRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top), 2, 2);
                g.drawLine(left + 1, top + 3, right - 1, top + 3);
                g.drawLine(left + 2, y + 1, left + 2, top + 2);
                g.drawLine(right - 2, y + 1, right - 2, top + 2);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        g.fillRect(left + 2 + col * 2, top + 5 + row * 2, 1, 1);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Guaranteed visible vertical divider for the Mini View footer. */
    private static final class MiniFooterDivider extends JComponent {
        private Color lineColor;
        private final int lineWidth;

        private MiniFooterDivider(Color lineColor, int lineWidth, int height) {
            this.lineColor = lineColor;
            this.lineWidth = Math.max(1, lineWidth);
            Dimension size = new Dimension(this.lineWidth, Math.max(1, height));
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            setOpaque(false);
        }

        private void setLineColor(Color color) {
            this.lineColor = color;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(lineColor);
                int x = Math.max(0, (getWidth() - lineWidth) / 2);
                g.fillRoundRect(x, 0, lineWidth, getHeight(), lineWidth, lineWidth);
            } finally {
                g.dispose();
            }
        }
    }

    /** Small clock glyph for Mini View's active-time footer status. */
    private static final class MiniActiveClockIcon implements Icon {
        private final Color color;
        private final int size;

        private MiniActiveClockIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(0.95f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int left = x + 1;
                int top = y + 1;
                int diameter = Math.max(4, size - 3);
                g.drawOval(left, top, diameter, diameter);
                int cx = left + diameter / 2;
                int cy = top + diameter / 2;
                g.drawLine(cx, cy, cx, top + 2);
                g.drawLine(cx, cy, cx + 2, cy + 1);
                // Tiny crown makes the symbol read as active-time/stopwatch rather than a status dot.
                g.drawLine(cx - 1, y, cx + 1, y);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class PinIcon implements Icon {
        private final boolean selected;
        private final Color color;
        private final int size;

        private PinIcon(boolean selected, Color color, int size) {
            this.selected = selected;
            this.color = color;
            this.size = Math.max(9, size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                double scale = size / 15.0;
                g.scale(scale, scale);
                g.setColor(color);
                g.setStroke(new BasicStroke(0.85f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Upright outline push-pin, scaled to the same visual weight as the reference toolbar.
                int cx = 7;
                Path2D body = new Path2D.Float();
                // Keep the same overall height but narrow the outline to the
                // proportions of the supplied reference push-pin.
                body.moveTo(cx - 2.5, 2);
                body.lineTo(cx + 2.5, 2);
                body.lineTo(cx + 1.8, 6);
                body.lineTo(cx + 3.2, 8);
                body.lineTo(cx - 3.2, 8);
                body.lineTo(cx - 1.8, 6);
                body.closePath();
                if (selected) {
                    Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 38);
                    g.setColor(fill);
                    g.fill(body);
                    g.setColor(color);
                }
                g.draw(body);
                g.drawLine(cx, 8, cx, 13);
                g.drawLine(cx - 2, 13, cx + 2, 13);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ThemeToggleIcon implements Icon {
        private final boolean showSun;
        private final Color color;
        private final int size;

        private ThemeToggleIcon(boolean showSun, Color color) {
            this(showSun, color, 16);
        }

        private ThemeToggleIcon(boolean showSun, Color color, int size) {
            this.showSun = showSun;
            this.color = color;
            this.size = Math.max(9, size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                double scale = size / 16.0;
                g.scale(scale, scale);
                g.setColor(color);
                g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (showSun) {
                    g.fillOval(5, 5, 6, 6);
                    int cx = 8;
                    int cy = 8;
                    int[][] rays = {{0,-7},{0,7},{-7,0},{7,0},{-5,-5},{5,5},{-5,5},{5,-5}};
                    for (int[] ray : rays) {
                        int innerX = cx + (int) Math.round(ray[0] * 0.65);
                        int innerY = cy + (int) Math.round(ray[1] * 0.65);
                        g.drawLine(innerX, innerY, cx + ray[0], cy + ray[1]);
                    }
                } else {
                    Area crescent = new Area(new Ellipse2D.Double(2, 2, 12, 12));
                    crescent.subtract(new Area(new Ellipse2D.Double(7, 0, 11, 11)));
                    g.fill(crescent);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Small, non-interactive status glyph for Screen Capture Privacy. */
    private static final class PrivacyShieldIcon implements Icon {
        private final boolean protectedFromCapture;
        private final Color color;
        private final int size;

        private PrivacyShieldIcon(boolean protectedFromCapture, Color color, int size) {
            this.protectedFromCapture = protectedFromCapture;
            this.color = color;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(Math.max(1.4f, size / 8.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Path2D.Float shield = new Path2D.Float();
                float left = x + size * 0.18f;
                float right = x + size * 0.82f;
                float top = y + size * 0.10f;
                float mid = y + size * 0.58f;
                float bottom = y + size * 0.90f;
                float center = x + size * 0.50f;
                shield.moveTo(center, top);
                shield.curveTo(x + size * 0.38f, y + size * 0.17f, left, y + size * 0.20f, left, y + size * 0.30f);
                shield.lineTo(left, mid);
                shield.curveTo(left, y + size * 0.70f, x + size * 0.34f, y + size * 0.82f, center, bottom);
                shield.curveTo(x + size * 0.66f, y + size * 0.82f, right, y + size * 0.70f, right, mid);
                shield.lineTo(right, y + size * 0.30f);
                shield.curveTo(right, y + size * 0.20f, x + size * 0.62f, y + size * 0.17f, center, top);
                g.draw(shield);

                if (protectedFromCapture) {
                    Path2D.Float check = new Path2D.Float();
                    check.moveTo(x + size * 0.32f, y + size * 0.51f);
                    check.lineTo(x + size * 0.44f, y + size * 0.63f);
                    check.lineTo(x + size * 0.69f, y + size * 0.37f);
                    g.draw(check);
                } else {
                    g.drawLine(Math.round(x + size * 0.36f), Math.round(y + size * 0.52f),
                            Math.round(x + size * 0.64f), Math.round(y + size * 0.52f));
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final class MiniOpacityIcon implements Icon {
        private final Color color;
        private final int size;

        private MiniOpacityIcon(Color color) {
            this(color, 15);
        }

        private MiniOpacityIcon(Color color, int size) {
            this.color = color;
            this.size = Math.max(8, size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                double scale = size / 15.0;
                g.scale(scale, scale);
                g.setStroke(new BasicStroke(0.9f));
                int d = 13;
                Shape circle = new Ellipse2D.Float(1, 1, d, d);
                g.setClip(circle);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
                g.fillRect(1, 1, d / 2 + 1, d);
                g.setClip(null);
                g.setColor(color);
                g.draw(circle);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class TransparencyIcon implements Icon {
        private final int transparency;
        private final Color foreground;
        private final Color secondary;

        private TransparencyIcon(int transparency, Color foreground, Color secondary) {
            this.transparency = transparency;
            this.foreground = foreground;
            this.secondary = secondary;
        }

        @Override public int getIconWidth() { return 30; }
        @Override public int getIconHeight() { return 18; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int width = getIconWidth();
                int height = getIconHeight();
                int tile = 5;
                Shape clip = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1f, height - 1f, 7f, 7f);
                g.setClip(clip);
                for (int row = 0; row < 4; row++) {
                    for (int column = 0; column < 6; column++) {
                        g.setColor(((row + column) & 1) == 0
                                ? new Color(secondary.getRed(), secondary.getGreen(), secondary.getBlue(), 74)
                                : new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 30));
                        g.fillRect(x + column * tile, y + row * tile, tile, tile);
                    }
                }

                int panelAlpha = Math.max(120, Math.round(235 * (1.0f - transparency / 100.0f)));
                Color panel = new Color(
                        component.getBackground().getRed(),
                        component.getBackground().getGreen(),
                        component.getBackground().getBlue(),
                        panelAlpha);
                g.setColor(panel);
                g.fillRoundRect(x + 2, y + 2, width - 4, height - 4, 6, 6);
                g.setClip(null);
                g.setColor(foreground);
                g.setStroke(new BasicStroke(1.1f));
                g.drawRoundRect(x + 1, y + 1, width - 3, height - 3, 6, 6);

                String label = transparency + "%";
                Font font = new Font(Font.SANS_SERIF, Font.BOLD, 9);
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics(font);
                int textX = x + (width - metrics.stringWidth(label)) / 2;
                int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(label, textX, textY);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Compact, language-independent view icon. The inward arrows indicate
     * Mini View; the outward arrows indicate returning to Full View.
     */
    private static final class ViewModeIcon implements Icon {
        private final boolean expand;
        private final int size;

        private ViewModeIcon(boolean expand, int size) {
            this.expand = expand;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.getForeground());
                g.setStroke(new BasicStroke(Math.max(1.2f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                int left = x + 1;
                int top = y + 2;
                int right = x + size - 2;
                int bottom = y + size - 3;
                g.drawRoundRect(left, top, right - left, bottom - top, 3, 3);

                int inset = Math.max(3, size / 4);
                if (expand) {
                    // Outward corners.
                    g.drawLine(x + inset, y + inset, x + 2, y + 2);
                    g.drawLine(x + 2, y + 2, x + 2, y + inset - 1);
                    g.drawLine(x + 2, y + 2, x + inset - 1, y + 2);
                    g.drawLine(x + size - inset - 1, y + size - inset - 1, x + size - 2, y + size - 2);
                    g.drawLine(x + size - 2, y + size - 2, x + size - 2, y + size - inset);
                    g.drawLine(x + size - 2, y + size - 2, x + size - inset, y + size - 2);
                } else {
                    // Inward corners.
                    int centre = size / 2;
                    g.drawLine(x + 2, y + 2, x + centre - 1, y + centre - 1);
                    g.drawLine(x + centre - 1, y + centre - 1, x + centre - 1, y + centre - 4);
                    g.drawLine(x + centre - 1, y + centre - 1, x + centre - 4, y + centre - 1);
                    g.drawLine(x + size - 2, y + size - 2, x + centre + 1, y + centre + 1);
                    g.drawLine(x + centre + 1, y + centre + 1, x + centre + 1, y + centre + 4);
                    g.drawLine(x + centre + 1, y + centre + 1, x + centre + 4, y + centre + 1);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Explicit checkbox painting avoids the macOS Aqua delegate making an
     * enabled Mini View checkbox look disabled in a custom dark window.
     */
    private static final class CheckBoxGlyph implements Icon {
        private final Color normalFill;
        private final Color selectedFill;
        private final Color border;
        private final Color check;

        private CheckBoxGlyph(Color normalFill, Color selectedFill, Color border, Color check) {
            this.normalFill = normalFill;
            this.selectedFill = selectedFill;
            this.border = border;
            this.check = check;
        }

        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            AbstractButton button = component instanceof AbstractButton abstractButton ? abstractButton : null;
            boolean selected = button != null && button.isSelected();
            boolean armed = button != null && button.getModel().isArmed();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = selected ? selectedFill : normalFill;
                if (armed) fill = blend(fill, check, 0.12f);
                g.setColor(fill);
                g.fillRoundRect(x + 1, y + 1, 12, 12, 4, 4);
                g.setColor(border);
                g.drawRoundRect(x + 1, y + 1, 12, 12, 4, 4);
                if (selected) {
                    g.setColor(check);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(x + 3, y + 7, x + 6, y + 10);
                    g.drawLine(x + 6, y + 10, x + 11, y + 4);
                }
            } finally {
                g.dispose();
            }
        }

        private static Color blend(Color first, Color second, float secondWeight) {
            float firstWeight = 1.0f - secondWeight;
            return new Color(
                    Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
                    Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
                    Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight),
                    Math.round(first.getAlpha() * firstWeight + second.getAlpha() * secondWeight));
        }
    }

    /**
     * Paints the selected combo-box value explicitly. Platform delegates can
     * otherwise leave a light current-value field inside a dark themed window,
     * making the selected range or unit appear blank.
     */
    private static final class SoftComboBoxUI extends BasicComboBoxUI {
        private final Color input;
        private final Color control;
        private final Color text;
        private final Color border;

        private SoftComboBoxUI(Color input, Color control, Color text, Color border) {
            this.input = input;
            this.control = control;
            this.text = text;
            this.border = border;
        }

        @Override protected JButton createArrowButton() {
            JButton button = new JButton("▾");
            button.setUI(new BasicButtonUI());
            button.setFocusable(false);
            button.setFocusPainted(false);
            button.setForeground(text);
            button.setBackground(control);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, border));
            button.setMargin(new Insets(0, 4, 0, 4));
            return button;
        }

        @Override public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean hasFocus) {
            graphics.setColor(input);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override public void paintCurrentValue(Graphics graphics, Rectangle bounds, boolean hasFocus) {
            paintCurrentValueBackground(graphics, bounds, hasFocus);
            Object selected = comboBox.getSelectedItem();
            if (selected == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(comboBox.getFont());
                g.setColor(text);
                g.setClip(bounds);
                FontMetrics metrics = g.getFontMetrics();
                int baseline = bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(selected.toString(), bounds.x + 8, baseline);
            } finally {
                g.dispose();
            }
        }
    }

    private void showReport() {
        usage("certificate_opened");
        RangeOption range = selectedRange();
        Snapshot snapshot = tracker.snapshot();
        Instant customStart = ((java.util.Date) customStartSpinner.getValue()).toInstant();
        Instant customEnd = ((java.util.Date) customEndSpinner.getValue()).toInstant();
        TimeWindow window = range.kind() == RangeOption.Kind.SESSION
                ? new TimeWindow(snapshot.sessionStart(), Instant.now())
                : range.resolve(zone, customStart, customEnd);
        UnitConverter.Unit unit = selectedUnit();
        double ppi = selectedPpi();
        String rangeLabel = range.label();

        queryExecutor.submit(() -> {
            Totals totals = range.kind() == RangeOption.Kind.SESSION
                    ? snapshot.totals()
                    : store.totals(window.start(), window.end());
            SwingUtilities.invokeLater(() -> showReportDialog(rangeLabel, totals, unit, ppi));
        });
    }

    private void showReportDialog(String rangeLabel, Totals totals, UnitConverter.Unit unit, double ppi) {
        Palette palette = palette();
        Color reportText = darkTheme ? new Color(243, 244, 247) : new Color(32, 33, 39);
        Color reportMuted = darkTheme ? new Color(183, 188, 199) : new Color(98, 103, 115);
        Color reportBlue = darkTheme ? new Color(131, 183, 255) : new Color(23, 105, 204);
        Color reportGreen = darkTheme ? new Color(128, 216, 162) : new Color(36, 119, 70);
        Color reportOrange = darkTheme ? new Color(255, 193, 132) : new Color(164, 91, 16);
        Color reportViolet = darkTheme ? new Color(200, 168, 255) : new Color(118, 80, 184);
        Color reportGold = darkTheme ? new Color(223, 194, 121) : new Color(140, 105, 29);
        Color[] metricTints = darkTheme
                ? new Color[] {new Color(40, 62, 89), new Color(41, 63, 50), new Color(73, 55, 40), new Color(61, 50, 81)}
                : new Color[] {new Color(234, 243, 255), new Color(237, 247, 239), new Color(255, 243, 229), new Color(243, 237, 253)};

        JDialog dialog = new JDialog(this, "My Digital Marathon Certificate", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setIconImages(AppBrandIcon.windowImages(loadHeaderIcon()));
        dialog.getRootPane().putClientProperty("apple.awt.windowAppearance",
                darkTheme ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");

        double distanceKm = UnitConverter.convert(totals.mousePixels(), UnitConverter.Unit.KILOMETRES, ppi);
        String distance = UnitConverter.formatDistance(totals.mousePixels(), unit, ppi);
        String active = UnitConverter.formatDuration(totals.activeSeconds());
        String keys = String.format(Locale.getDefault(), "%,d", totals.keyPresses());
        String clicks = String.format(Locale.getDefault(), "%,d", totals.mouseClicks());
        int score = activityScore(totals, distanceKm);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(palette.page());
        root.setBorder(new EmptyBorder(22, 22, 16, 22));
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setOpaque(false);
        JPanel headingText = new JPanel();
        headingText.setOpaque(false);
        headingText.setLayout(new BoxLayout(headingText, BoxLayout.Y_AXIS));
        JLabel title = classicReportLabel("Your Digital Marathon", 23f, false, reportText);
        headingText.add(title);
        headingText.add(Box.createVerticalStrut(5));
        headingText.add(classicReportLabel("Work done · Digital edition", 12f, false, reportMuted));
        headingText.add(Box.createVerticalStrut(8));
        JLabel headline = classicReportLabel(reportHeadline(totals, distanceKm), 13f, false, reportText);
        headingText.add(headline);
        heading.add(headingText, BorderLayout.CENTER);
        JPanel rangeAndSeal = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        rangeAndSeal.setOpaque(false);
        JLabel reportRange = classicReportLabel(rangeLabel, 12f, false, reportMuted);
        reportRange.setIcon(new CalendarRangeIcon(reportMuted, 15));
        reportRange.setIconTextGap(6);
        rangeAndSeal.add(reportRange);
        JLabel awardSeal = new JLabel(new GoldenCertificateSealIcon(68));
        awardSeal.getAccessibleContext().setAccessibleName("Digital Marathon gold laptop award");
        rangeAndSeal.add(awardSeal);
        heading.add(rangeAndSeal, BorderLayout.EAST);
        addClassicReportSection(content, heading, 20);

        JPanel nameRow = new JPanel(new BorderLayout(10, 0));
        nameRow.setOpaque(false);
        JPanel nameField = new JPanel(new BorderLayout(0, 6));
        nameField.setOpaque(false);
        JLabel nameLabel = classicReportLabel("Name on Certificate", 12f, false, reportText);
        nameField.add(nameLabel, BorderLayout.NORTH);
        JTextField certificateName = new ClassicCertificateNameField(
                preferences.get("certificateName", ""), reportMuted, palette.surface(), palette.border());
        certificateName.setPreferredSize(new Dimension(350, 36));
        certificateName.setFont(certificateName.getFont().deriveFont(Font.PLAIN, 13f));
        certificateName.setToolTipText("Your name on the downloaded certificate");
        certificateName.getAccessibleContext().setAccessibleName("Name on Certificate");
        nameLabel.setLabelFor(certificateName);
        nameField.add(certificateName, BorderLayout.CENTER);
        nameRow.add(nameField, BorderLayout.CENTER);
        JButton download = new JButton("Download Certificate");
        download.setFont(download.getFont().deriveFont(Font.PLAIN, 13f));
        download.setPreferredSize(new Dimension(191, 36));
        download.setToolTipText("Save a JPEG certificate in Downloads");
        download.addActionListener(event -> exportCertificateJpeg(
                dialog, certificateName.getText(), rangeLabel, totals, unit, ppi));
        JPanel downloadPanel = new JPanel(new BorderLayout());
        downloadPanel.setOpaque(false);
        downloadPanel.setBorder(new EmptyBorder(21, 0, 0, 0));
        downloadPanel.add(download, BorderLayout.NORTH);
        nameRow.add(downloadPanel, BorderLayout.EAST);
        addClassicReportSection(content, nameRow, 10);
        addClassicReportSection(content,
                classicReportLabel("The app saves a JPEG certificate in Downloads", 11f, false, reportMuted), 12);

        JPanel metrics = new JPanel(new GridLayout(1, 4, 10, 0));
        metrics.setOpaque(false);
        metrics.add(classicReportMetricCard("Mouse distance", distance, "Mouse on tour",
                mouseEquivalent(distanceKm), mouseReportLine(distanceKm), ActivityIcon.Kind.MOUSE,
                metricTints[0], reportBlue, reportMuted));
        metrics.add(classicReportMetricCard("Key presses", keys, "Keyboard cardio",
                keyEquivalent(totals.keyPresses(), totals.activeSeconds()), keyReportLine(totals.keyPresses()),
                ActivityIcon.Kind.KEYBOARD, metricTints[1], reportGreen, reportMuted));
        metrics.add(classicReportMetricCard("Mouse clicks", clicks, "Click-finger reps",
                clickEquivalent(totals.mouseClicks(), totals.activeSeconds()), clickReportLine(totals.mouseClicks()),
                ActivityIcon.Kind.CLICK, metricTints[2], reportOrange, reportMuted));
        metrics.add(classicReportMetricCard("Active time", active, "Time on the clock",
                activeEquivalent(totals.activeSeconds()), activeReportLine(totals.activeSeconds()), ActivityIcon.Kind.CLOCK,
                metricTints[3], reportViolet, reportMuted));
        addClassicReportSection(content, metrics, 18);

        JPanel meterSection = new JPanel(new BorderLayout(0, 7));
        meterSection.setOpaque(false);
        JPanel meterHeading = new JPanel(new BorderLayout());
        meterHeading.setOpaque(false);
        meterHeading.add(classicReportLabel("Activity Meter", 13f, false, reportText), BorderLayout.WEST);
        meterHeading.add(classicReportLabel(score + " / 100 · " + activityVibe(score), 11f, false, reportBlue), BorderLayout.EAST);
        meterSection.add(meterHeading, BorderLayout.NORTH);
        ClassicCertificateMeter meter = new ClassicCertificateMeter(score,
                darkTheme ? new Color(64, 73, 87) : new Color(223, 232, 242), reportBlue);
        meter.setPreferredSize(new Dimension(500, 7));
        meter.getAccessibleContext().setAccessibleName("Activity Meter: " + score + " of 100");
        meterSection.add(meter, BorderLayout.CENTER);
        JPanel endpoints = new JPanel(new BorderLayout());
        endpoints.setOpaque(false);
        endpoints.add(classicReportLabel("Warm-up", 11f, false, reportMuted), BorderLayout.WEST);
        endpoints.add(classicReportLabel("Marathon pace", 11f, false, reportMuted), BorderLayout.EAST);
        meterSection.add(endpoints, BorderLayout.SOUTH);
        addClassicReportSection(content, meterSection, 19);

        addClassicReportSection(content,
                classicReportLabel("Achievements Unlocked", 13f, false, reportText), 9);
        JPanel achievements = new JPanel(new GridLayout(1, 3, 10, 0));
        achievements.setOpaque(false);
        String[] badges = reportBadges(totals, distanceKm);
        for (String badge : badges) {
            RoundedPanel achievement = new RoundedPanel(new BorderLayout(9, 0), palette.surface(), 22);
            achievement.setPanelColors(palette.surface(), palette.surface());
            achievement.setBorder(new EmptyBorder(12, 12, 12, 12));
            JLabel achievementIcon = new JLabel(new ClassicAchievementIcon(reportGold, 19));
            achievementIcon.setVerticalAlignment(SwingConstants.TOP);
            achievement.add(achievementIcon, BorderLayout.WEST);
            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(classicReportLabel(badge, 12f, false, reportText));
            text.add(Box.createVerticalStrut(3));
            text.add(classicReportLabel("<html><body style='width:125px'>"
                    + achievementDescription(badge) + "</body></html>", 11f, false, reportMuted));
            achievement.add(text, BorderLayout.CENTER);
            achievements.add(achievement);
        }
        addClassicReportSection(content, achievements, 18);

        JPanel verdict = new JPanel(new BorderLayout(14, 0));
        verdict.setOpaque(false);
        verdict.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.border()), new EmptyBorder(16, 0, 0, 0)));
        JPanel verdictTitle = new JPanel();
        verdictTitle.setOpaque(false);
        verdictTitle.setLayout(new BoxLayout(verdictTitle, BoxLayout.Y_AXIS));
        verdictTitle.add(classicReportLabel("Finish-Line Verdict", 12f, false, reportMuted));
        verdictTitle.add(Box.createVerticalStrut(3));
        verdictTitle.add(classicReportLabel(reportVerdictParts(totals, distanceKm)[0], 14f, false, reportText));
        verdict.add(verdictTitle, BorderLayout.WEST);
        JLabel verdictDetail = classicReportLabel("<html><body style='width:245px'>"
                + reportVerdictParts(totals, distanceKm)[1] + "</body></html>", 12f, false, reportMuted);
        verdict.add(verdictDetail, BorderLayout.EAST);
        addClassicReportSection(content, verdict, 13);
        addClassicReportSection(content, classicReportLabel(
                "For fun: input activity is not a productivity score. No typed content is included.",
                11f, false, reportMuted), 0);

        JScrollPane reportScroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        reportScroll.setBorder(BorderFactory.createEmptyBorder());
        reportScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(reportScroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        JButton close = secondaryButton("Close");
        close.setPreferredSize(new Dimension(80, 30));
        close.addActionListener(event -> dialog.dispose());
        footer.add(close);
        root.add(footer, BorderLayout.SOUTH);

        applyThemeToTree(root, palette);
        reportScroll.getViewport().setBackground(palette.page());
        certificateName.setBorder(new ClassicCertificateFieldBorder(palette.border()));
        certificateName.setOpaque(false);
        Color downloadFill = darkTheme ? new Color(129, 179, 255) : new Color(18, 108, 219);
        Color downloadText = darkTheme ? new Color(19, 43, 75) : Color.WHITE;
        download.setUI(new ClassicCertificateDownloadUI(downloadFill));
        download.setForeground(downloadText);
        download.setIcon(new CertificateDownloadIcon(downloadText, 16));
        download.setIconTextGap(7);
        download.setBorder(new EmptyBorder(8, 12, 8, 12));
        download.setOpaque(false);
        download.setContentAreaFilled(false);
        download.setBorderPainted(false);
        download.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dialog.setContentPane(root);
        dialog.setMinimumSize(new Dimension(760, 620));
        dialog.setSize(780, 740);
        dialog.addNotify();
        MacNativeInputBackend.applyNativeWindowAppearance(dialog, darkTheme);
        dialog.getRootPane().setDefaultButton(download);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static JLabel classicReportLabel(String text, float size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        label.putClientProperty("fixedForeground", color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void addClassicReportSection(JPanel content, JComponent section, int gap) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension preferred = section.getPreferredSize();
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        content.add(section);
        if (gap > 0) content.add(Box.createVerticalStrut(gap));
    }

    private static RoundedPanel classicReportMetricCard(
            String title, String value, String playful, String equivalent, String commentary,
            ActivityIcon.Kind kind, Color tint, Color accent, Color muted) {
        RoundedPanel card = new RoundedPanel(null, tint, 24);
        card.setPanelColors(tint, tint);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(11, 12, 10, 12));
        JLabel label = classicReportLabel(title, 11f, false, accent);
        label.setIcon(new ActivityIcon(kind, accent, 14));
        label.setIconTextGap(5);
        card.add(label);
        card.add(Box.createVerticalStrut(4));
        JLabel valueLabel = new ClassicReportValueLabel(value, accent);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueLabel.putClientProperty("fixedForeground", accent);
        valueLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        card.add(valueLabel);
        card.add(Box.createVerticalStrut(7));
        card.add(classicReportLabel(playful, 11f, false, accent));
        card.add(Box.createVerticalStrut(2));
        card.add(classicReportLabel("<html><body style='width:105px'>" + equivalent + "</body></html>",
                11f, false, muted));
        card.setToolTipText(commentary);
        card.getAccessibleContext().setAccessibleDescription(title + ": " + value + ". "
                + equivalent + ". " + commentary);
        card.setPreferredSize(new Dimension(150, 138));
        return card;
    }

    private static String[] reportVerdictParts(Totals totals, double distanceKm) {
        String verdict = overallReportVerdict(totals, distanceKm);
        int divider = verdict.indexOf(" - ");
        return divider < 0 ? new String[] {verdict, ""}
                : new String[] {verdict.substring(0, divider), verdict.substring(divider + 3)};
    }

    private static String certificateVerdictName(Totals totals, double distanceKm) {
        String title = reportVerdictParts(totals, distanceKm)[0].toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String word : title.split(" ")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String achievementDescription(String badge) {
        return switch (badge) {
            case "Screen Endurance" -> "A full day of digital activity.";
            case "Keyboard Cardio" -> "You kept the keys moving.";
            case "Click Commander" -> "Your click finger stayed busy.";
            case "Mouse Explorer" -> "Your cursor covered real mileage.";
            case "Marathon Finisher" -> "A complete 42.195 km cursor marathon.";
            case "Warm-up Crew" -> "You got the session moving.";
            case "Digital Pacer" -> "Every movement counts.";
            default -> "A steady day at your desk.";
        };
    }

    private static final class ClassicReportValueLabel extends JLabel {
        private ClassicReportValueLabel(String text, Color color) {
            super(text);
            setFont(getFont().deriveFont(Font.PLAIN, 22f));
            setForeground(color);
            setPreferredSize(new Dimension(140, 28));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                fitCertificateFont(g, getText(), Math.max(1, getWidth()));
                g.setColor(getForeground());
                FontMetrics fm = g.getFontMetrics();
                g.drawString(getText(), 0, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally { g.dispose(); }
        }
    }

    private static final class ClassicCertificateNameField extends JTextField {
        private final Color placeholder;
        private final Color fill;
        private ClassicCertificateNameField(String text, Color placeholder, Color fill, Color border) {
            super(text);
            this.placeholder = placeholder;
            this.fill = fill;
            setBorder(new ClassicCertificateFieldBorder(border));
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            } finally { g.dispose(); }
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D text = (Graphics2D) graphics.create();
                try {
                    text.setFont(getFont());
                    text.setColor(placeholder);
                    FontMetrics fm = text.getFontMetrics();
                    text.drawString("Enter your name", getInsets().left,
                            (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                } finally { text.dispose(); }
            }
        }
    }

    private static final class ClassicCertificateFieldBorder extends javax.swing.border.AbstractBorder {
        private final Color color;
        private ClassicCertificateFieldBorder(Color color) { this.color = color; }
        @Override public Insets getBorderInsets(Component component) { return new Insets(8, 11, 8, 11); }
        @Override public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(8, 11, 8, 11);
            return insets;
        }
        @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.drawRoundRect(x, y, width - 1, height - 1, 16, 16);
            } finally { g.dispose(); }
        }
    }

    private static final class ClassicCertificateDownloadUI extends BasicButtonUI {
        private final Color fill;
        private ClassicCertificateDownloadUI(Color fill) { this.fill = fill; }
        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(button.getModel().isPressed() ? fill.darker()
                        : button.getModel().isRollover() ? mixColor(fill, Color.WHITE, 0.10f) : fill);
                g.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 16, 16);
                if (button.hasFocus()) {
                    g.setColor(new Color(255, 255, 255, 160));
                    g.drawRoundRect(2, 2, component.getWidth() - 5, component.getHeight() - 5, 12, 12);
                }
            } finally { g.dispose(); }
            super.paint(graphics, component);
        }
    }

    private static final class CertificateDownloadIcon implements Icon {
        private final Color color;
        private final int size;
        private CertificateDownloadIcon(Color color, int size) { this.color = color; this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(size / 16.0, size / 16.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(8, 1, 8, 10);
                g.drawLine(4, 6, 8, 10);
                g.drawLine(8, 10, 12, 6);
                g.drawLine(2, 11, 2, 14);
                g.drawLine(2, 14, 14, 14);
                g.drawLine(14, 14, 14, 11);
            } finally { g.dispose(); }
        }
    }

    private static final class ClassicCertificateMeter extends JPanel {
        private final int score;
        private final Color track;
        private final Color fill;
        private ClassicCertificateMeter(int score, Color track, Color fill) {
            this.score = Math.max(0, Math.min(100, score));
            this.track = track;
            this.fill = fill;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(track);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 7, 7);
                g.setColor(fill);
                int width = (int) Math.round(getWidth() * score / 100.0);
                if (width > 0) g.fillRoundRect(0, 0, width, getHeight(), 7, 7);
            } finally { g.dispose(); }
        }
    }

    private static final class ClassicAchievementIcon implements Icon {
        private final Color color;
        private final int size;
        private ClassicAchievementIcon(Color color, int size) { this.color = color; this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(size / 19.0, size / 19.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawRoundRect(5, 2, 9, 9, 3, 3);
                g.drawArc(2, 2, 5, 7, 90, 180);
                g.drawArc(12, 2, 5, 7, -90, 180);
                g.drawLine(9, 11, 9, 16);
                g.drawLine(5, 17, 14, 17);
            } finally { g.dispose(); }
        }
    }

    private void exportCertificateJpeg(
            Component parent, String name, String rangeLabel, Totals totals, UnitConverter.Unit unit, double ppi) {
        String recipient = name == null ? "" : name.trim();
        if (recipient.isBlank()) {
            ThemeDialog.message(parent, darkTheme, "Name required",
                    "Enter a name in Name on certificate before downloading.");
            return;
        }
        preferences.put("certificateName", recipient);

        String safeName = recipient.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+", "").replaceAll("-+$", "");
        if (safeName.isBlank()) safeName = "digital-marathon";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path downloads = defaultDownloadsDirectory();
        File output = downloads.resolve(safeName + "-digital-marathon-certificate-" + timestamp + ".jpg").toFile();

        try {
            Files.createDirectories(downloads);
            BufferedImage certificate = buildCertificateImage(recipient, rangeLabel, totals, unit, ppi);
            CertificateJpegWriter.write(certificate, output);
            usage("certificate_exported");
            ThemeDialog.certificateSaved(parent, darkTheme, output.toPath());
        } catch (IOException error) {
            usage("certificate_export_failed", Map.of("error_code", "save_failed"));
            ThemeDialog.message(parent, darkTheme, "Certificate could not be saved",
                    "Check that your Downloads folder is available and try again.\n\n" + error.getMessage());
        }
    }

    private static Path defaultDownloadsDirectory() {
        String homeText = System.getProperty("user.home", ".");
        Path home = Path.of(homeText);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            Path userDirs = home.resolve(".config").resolve("user-dirs.dirs");
            if (Files.isRegularFile(userDirs)) {
                try {
                    for (String line : Files.readAllLines(userDirs)) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("XDG_DOWNLOAD_DIR=")) continue;
                        String value = trimmed.substring("XDG_DOWNLOAD_DIR=".length()).trim();
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                        value = value.replace("$HOME", homeText).replace("${HOME}", homeText);
                        if (!value.isBlank()) return Path.of(value);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Fall back to the conventional per-user Downloads folder.
                }
            }
        }
        return home.resolve("Downloads");
    }

    private BufferedImage buildCertificateImage(
            String recipient, String rangeLabel, Totals totals, UnitConverter.Unit unit, double ppi) {
        final int width = 1600;
        final int height = 1200;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Color ink = new Color(32, 33, 39);
            Color muted = new Color(98, 103, 115);
            Color blue = new Color(23, 105, 204);
            Color green = new Color(36, 119, 70);
            Color orange = new Color(164, 91, 16);
            Color violet = new Color(118, 80, 184);
            Color gold = new Color(140, 105, 29);
            Color paper = new Color(255, 253, 247);
            Color hairline = new Color(216, 205, 178);
            g.setColor(paper);
            g.fillRect(0, 0, width, height);
            g.setColor(gold);
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(32, 32, width - 64, height - 64, 26, 26);
            g.setColor(hairline);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(52, 52, width - 104, height - 104, 14, 14);

            // Match the Classic Award preview: centered branding and quiet typography.
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            String brandName = "Digital Marathon";
            int brandTextWidth = g.getFontMetrics().stringWidth(brandName);
            int brandX = (width - 58 - 16 - brandTextWidth) / 2;
            new AppBrandIcon(loadHeaderIcon(), 58, 58).paintIcon(null, g, brandX, 65);
            g.setColor(gold);
            g.drawString(brandName, brandX + 74, 101);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22).deriveFont(
                    java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.14f)));
            drawCentered(g, "ACHIEVEMENT CERTIFICATE", width / 2, 159);
            g.setColor(ink);
            g.setFont(new Font("Georgia", Font.PLAIN, 54));
            drawCentered(g, "Digital Marathon", width / 2, 222);
            drawCentered(g, "Achievement Certificate", width / 2, 282);

            // Preserve the user's original gold laptop award exactly at top right.
            drawGoldenCertificateSeal(g, 1420, 127, 66);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            drawCentered(g, "Proudly presented to", width / 2, 327);
            g.setColor(ink);
            g.setFont(new Font("Georgia", Font.PLAIN, 62));
            drawFittedCentered(g, recipient, width / 2, 399, width - 224);

            double distanceKm = UnitConverter.convert(totals.mousePixels(), UnitConverter.Unit.KILOMETRES, ppi);
            int score = activityScore(totals, distanceKm);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            drawFittedCentered(g, "For keeping the digital miles moving as a "
                    + certificateVerdictName(totals, distanceKm) + ".", width / 2, 447, 1210);
            g.setColor(gold);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            drawCentered(g, rangeLabel, width / 2, 479);

            String[] labels = {"Mouse distance", "Key presses", "Mouse clicks", "Active time"};
            String[] values = {
                    UnitConverter.formatDistance(totals.mousePixels(), unit, ppi),
                    String.format(Locale.getDefault(), "%,d", totals.keyPresses()),
                    String.format(Locale.getDefault(), "%,d", totals.mouseClicks()),
                    UnitConverter.formatDuration(totals.activeSeconds())};
            String[] equivalents = {mouseEquivalent(distanceKm),
                    keyEquivalent(totals.keyPresses(), totals.activeSeconds()),
                    clickEquivalent(totals.mouseClicks(), totals.activeSeconds()),
                    activeEquivalent(totals.activeSeconds())};
            Color[] accents = {blue, green, orange, violet};
            int columnsX = 112;
            int columnsWidth = width - columnsX * 2;
            int columnWidth = columnsWidth / 4;
            for (int i = 0; i < 4; i++) {
                int centerX = columnsX + i * columnWidth + columnWidth / 2;
                g.setColor(accents[i]);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36));
                drawFittedCentered(g, values[i], centerX, 554, columnWidth - 20);
                g.setColor(muted);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
                drawCentered(g, labels[i], centerX, 590);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                drawWrappedCentered(g, equivalents[i], centerX, 623, columnWidth - 36, 23, 2);
            }

            // The report's activity details remain available on the downloaded certificate.
            int left = 112;
            int right = width - left;
            g.setColor(ink);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
            g.drawString("Activity Meter", left, 699);
            g.setColor(blue);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
            drawRightAligned(g, score + " / 100 · " + activityVibe(score), right, 699);
            g.setColor(new Color(223, 232, 242));
            g.fillRoundRect(left, 719, right - left, 12, 12, 12);
            g.setColor(blue);
            int meterWidth = (int) Math.round((right - left) * score / 100.0);
            if (meterWidth > 0) g.fillRoundRect(left, 719, meterWidth, 12, 12, 12);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            g.drawString("Warm-up", left, 758);
            drawRightAligned(g, "Marathon pace", right, 758);

            g.setColor(ink);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
            g.drawString("Achievements Unlocked", left, 805);
            String[] badges = reportBadges(totals, distanceKm);
            int achievementGap = 22;
            int achievementWidth = (right - left - 2 * achievementGap) / 3;
            for (int i = 0; i < badges.length; i++) {
                int x = left + i * (achievementWidth + achievementGap);
                g.setColor(Color.WHITE);
                g.fillRoundRect(x, 825, achievementWidth, 96, 22, 22);
                new ClassicAchievementIcon(gold, 32).paintIcon(null, g, x + 22, 844);
                g.setColor(ink);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 23));
                drawFittedString(g, badges[i], x + 70, 859, achievementWidth - 90);
                g.setColor(muted);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
                drawWrapped(g, achievementDescription(badges[i]), x + 70, 888,
                        achievementWidth - 90, 22, 2);
            }

            g.setColor(hairline);
            g.drawLine(left, 940, right, 940);
            String[] verdict = reportVerdictParts(totals, distanceKm);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            g.drawString("Finish-Line Verdict", left, 978);
            g.setColor(ink);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
            drawFittedString(g, verdict[0], left, 1015, 700);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            drawWrapped(g, verdict[1], 858, 976, right - 858, 29, 2);

            g.setColor(hairline);
            g.drawLine(left, 1046, right, 1046);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
            g.drawString("Awarded by", left, 1083);
            drawRightAligned(g, "Generated", right, 1083);
            g.setColor(ink);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            g.drawString("Digital Marathon · Girish Gupta", left, 1115);
            drawRightAligned(g, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")),
                    right, 1115);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            g.drawString("girish.gupta@gmail.com", left, 1142);
            g.setColor(gold);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            drawCentered(g, "FINISHER", width / 2, 1113);
            g.setColor(muted);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            drawCentered(g, "For fun: input activity is not a productivity score. No typed content is included.",
                    width / 2, 1185);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static final class GoldenCertificateSealIcon implements Icon {
        private final int size;

        private GoldenCertificateSealIcon(int size) {
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D seal = (Graphics2D) graphics.create();
            try {
                seal.translate(x, y);
                seal.scale(size / 176.0, size / 176.0);
                drawGoldenCertificateSeal(seal, 88, 68, 64);
            } finally {
                seal.dispose();
            }
        }
    }

    private static void drawGoldenCertificateSeal(Graphics2D g, int centerX, int centerY, int radius) {
        Graphics2D seal = (Graphics2D) g.create();
        try {
            seal.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            seal.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            Color bronze = new Color(137, 82, 5);
            Color deepGold = new Color(187, 111, 8);
            Color richGold = new Color(236, 157, 18);
            Color brightGold = new Color(255, 199, 42);
            Color lemonGold = new Color(255, 231, 106);
            Color champagne = new Color(255, 247, 202);

            // Ribbon tails sit behind the medallion and use darker lower edges so
            // the seal reads as dimensional rather than as a flat badge.
            Polygon leftRibbon = new Polygon(
                    new int[] {centerX - 34, centerX - 4, centerX - 14, centerX - 43},
                    new int[] {centerY + 41, centerY + 50, centerY + 100, centerY + 78}, 4);
            Polygon rightRibbon = new Polygon(
                    new int[] {centerX + 4, centerX + 34, centerX + 43, centerX + 14},
                    new int[] {centerY + 50, centerY + 41, centerY + 78, centerY + 100}, 4);
            seal.setPaint(new LinearGradientPaint(centerX, centerY + 34, centerX, centerY + 102,
                    new float[] {0f, 0.48f, 1f}, new Color[] {lemonGold, richGold, deepGold}));
            seal.fillPolygon(leftRibbon);
            seal.fillPolygon(rightRibbon);
            seal.setColor(new Color(128, 77, 5));
            seal.setStroke(new BasicStroke(1.6f));
            seal.drawPolygon(leftRibbon);
            seal.drawPolygon(rightRibbon);

            // Starburst edge plus a soft offset shadow create the 3D stamp effect.
            int points = 48;
            int[] xs = new int[points];
            int[] ys = new int[points];
            for (int i = 0; i < points; i++) {
                double angle = -Math.PI / 2.0 + (Math.PI * 2.0 * i / points);
                double r = (i % 2 == 0) ? radius : radius * 0.86;
                xs[i] = centerX + (int) Math.round(Math.cos(angle) * r);
                ys[i] = centerY + (int) Math.round(Math.sin(angle) * r);
            }
            Polygon burst = new Polygon(xs, ys, points);
            seal.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.24f));
            seal.translate(5, 7);
            seal.setColor(new Color(89, 57, 7));
            seal.fillPolygon(burst);
            seal.translate(-5, -7);
            seal.setComposite(AlphaComposite.SrcOver);

            seal.setPaint(new RadialGradientPaint(
                    new Point(centerX - radius / 3, centerY - radius / 3), radius * 1.25f,
                    new float[] {0f, 0.28f, 0.68f, 1f},
                    new Color[] {new Color(255, 251, 195), lemonGold, brightGold, deepGold}));
            seal.fillPolygon(burst);
            seal.setColor(bronze);
            seal.setStroke(new BasicStroke(2.2f));
            seal.drawPolygon(burst);

            // Embossed outer rings: dark lower rim, bright upper rim and a specular arc.
            int ring = (int) Math.round(radius * 1.48);
            int rx = centerX - ring / 2;
            int ry = centerY - ring / 2;
            seal.setColor(new Color(150, 91, 5));
            seal.setStroke(new BasicStroke(6f));
            seal.drawOval(rx + 1, ry + 3, ring, ring);
            seal.setColor(new Color(255, 220, 82));
            seal.setStroke(new BasicStroke(4f));
            seal.drawOval(rx, ry, ring, ring);
            seal.setColor(new Color(255, 249, 190));
            seal.setStroke(new BasicStroke(2.5f));
            seal.drawArc(rx + 4, ry + 4, ring - 8, ring - 8, 34, 116);

            int inner = (int) Math.round(radius * 1.13);
            int ix = centerX - inner / 2;
            int iy = centerY - inner / 2;
            seal.setPaint(new RadialGradientPaint(
                    new Point(centerX - inner / 5, centerY - inner / 4), inner * 0.75f,
                    new float[] {0f, 0.5f, 1f},
                    new Color[] {champagne, lemonGold, richGold}));
            seal.fillOval(ix, iy, inner, inner);
            seal.setColor(deepGold);
            seal.setStroke(new BasicStroke(3f));
            seal.drawOval(ix, iy, inner, inner);
            seal.setColor(new Color(255, 244, 175));
            seal.setStroke(new BasicStroke(1.8f));
            seal.drawArc(ix + 5, iy + 5, inner - 10, inner - 10, 20, 150);

            // Bright golden laptop emblem replaces the previous star/monogram.
            int laptopW = Math.max(34, (int) Math.round(radius * 0.78));
            int laptopH = Math.max(24, (int) Math.round(radius * 0.50));
            int screenX = centerX - laptopW / 2;
            int screenY = centerY - laptopH / 2 - 1;
            seal.setPaint(new LinearGradientPaint(screenX, screenY, screenX, screenY + laptopH,
                    new float[] {0f, 0.45f, 1f},
                    new Color[] {new Color(255, 252, 184), brightGold, new Color(232, 143, 7)}));
            seal.fillRoundRect(screenX, screenY, laptopW, laptopH, 7, 7);
            seal.setColor(new Color(129, 76, 4));
            seal.setStroke(new BasicStroke(2.3f));
            seal.drawRoundRect(screenX, screenY, laptopW, laptopH, 7, 7);

            // Screen shine and a tiny golden marathon line add depth without text.
            seal.setColor(new Color(255, 249, 190));
            seal.setStroke(new BasicStroke(2f));
            seal.drawLine(screenX + 7, screenY + 7, screenX + laptopW - 12, screenY + 7);
            seal.setColor(new Color(160, 94, 5));
            seal.setStroke(new BasicStroke(2.2f));
            int midY = screenY + laptopH / 2 + 2;
            seal.drawLine(screenX + 10, midY, screenX + laptopW / 2 - 3, midY);
            seal.drawLine(screenX + laptopW / 2 + 3, midY, screenX + laptopW - 10, midY);

            int baseY = screenY + laptopH + 4;
            Polygon base = new Polygon(
                    new int[] {screenX - 7, screenX + laptopW + 7, screenX + laptopW - 1, screenX + 1},
                    new int[] {baseY, baseY, baseY + 9, baseY + 9}, 4);
            seal.setPaint(new GradientPaint(screenX, baseY, lemonGold, screenX, baseY + 10, deepGold));
            seal.fillPolygon(base);
            seal.setColor(new Color(129, 76, 4));
            seal.setStroke(new BasicStroke(1.7f));
            seal.drawPolygon(base);

            // Tiny glints make the metallic surface feel polished.
            seal.setColor(new Color(255, 251, 207));
            seal.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            seal.drawLine(centerX - radius + 12, centerY - radius / 3, centerX - radius + 23, centerY - radius / 3);
            seal.drawLine(centerX - radius + 18, centerY - radius / 3 - 6, centerX - radius + 18, centerY - radius / 3 + 6);
            seal.drawLine(centerX + radius - 21, centerY + radius / 4, centerX + radius - 11, centerY + radius / 4);
            seal.drawLine(centerX + radius - 16, centerY + radius / 4 - 5, centerX + radius - 16, centerY + radius / 4 + 5);
        } finally {
            seal.dispose();
        }
    }

    private static void drawCertificateMetric(
            Graphics2D g, int x, int y, int width, int height, Color background, Color accent, Icon icon,
            String label, String value, String detail) {
        g.setColor(background);
        g.fillRoundRect(x, y, width, height, 24, 24);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, width, height, 24, 24);
        g.fillRoundRect(x + 16, y + 18, 8, height - 36, 8, 8);
        if (icon != null) icon.paintIcon(null, g, x + 36, y + 16);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString(label, x + 78, y + 39);
        g.setColor(accent);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 31));
        drawFittedString(g, value, x + 38, y + 82, width - 76);
        g.setColor(accent);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        drawWrapped(g, detail, x + 38, y + 106, width - 58, 17, 2);
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int baselineY) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, centerX - metrics.stringWidth(text) / 2, baselineY);
    }

    private static void drawRightAligned(Graphics2D g, String text, int rightX, int baselineY) {
        g.drawString(text, rightX - g.getFontMetrics().stringWidth(text), baselineY);
    }

    private static void drawWrappedCentered(
            Graphics2D g, String text, int centerX, int baselineY, int maxWidth, int lineHeight, int maxLines) {
        FontMetrics metrics = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int lineNumber = 0;
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && metrics.stringWidth(candidate) > maxWidth) {
                drawCentered(g, line.toString(), centerX, baselineY + lineNumber * lineHeight);
                lineNumber++;
                if (lineNumber >= maxLines) return;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (lineNumber < maxLines && line.length() > 0) {
            drawCentered(g, line.toString(), centerX, baselineY + lineNumber * lineHeight);
        }
    }

    private static void drawFittedCentered(Graphics2D g, String text, int centerX, int baselineY, int maxWidth) {
        Font originalFont = g.getFont();
        fitCertificateFont(g, text, maxWidth);
        drawCentered(g, text, centerX, baselineY);
        g.setFont(originalFont);
    }

    private static void drawFittedString(Graphics2D g, String text, int x, int baselineY, int maxWidth) {
        Font originalFont = g.getFont();
        fitCertificateFont(g, text, maxWidth);
        g.drawString(text, x, baselineY);
        g.setFont(originalFont);
    }

    private static void fitCertificateFont(Graphics2D g, String text, int maxWidth) {
        int textWidth = g.getFontMetrics().stringWidth(text);
        if (textWidth > maxWidth && textWidth > 0) {
            g.setFont(g.getFont().deriveFont(Math.max(8f,
                    g.getFont().getSize2D() * maxWidth / textWidth)));
        }
    }

    private static void drawWrapped(
            Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        if (text == null || text.isBlank()) return;
        FontMetrics metrics = g.getFontMetrics();
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        int lineNumber = 0;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0) {
                g.drawString(line.toString(), x, y + lineNumber * lineHeight);
                lineNumber++;
                line.setLength(0);
                line.append(word);
                if (lineNumber >= maxLines) return;
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (lineNumber < maxLines && line.length() > 0) {
            g.drawString(line.toString(), x, y + lineNumber * lineHeight);
        }
    }

    private RoundedPanel reportMetricCard(
            String title, String value, String detail, String joke, Icon icon, Color tint, Color accent, Palette palette) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(10, 0), tint, 18);
        card.setPanelColors(tint, mixColor(palette.border(), accent, darkTheme ? 0.38f : 0.30f));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setVerticalAlignment(SwingConstants.TOP);
        card.add(iconLabel, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 9.8f));
        setThemeRole(titleLabel, "muted");
        titleLabel.putClientProperty("fixedForeground", accent);
        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 21f));
        setThemeRole(valueLabel, "title");
        valueLabel.putClientProperty("fixedForeground", accent);
        JLabel detailLabel = new JLabel(detail);
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.BOLD, 10.5f));
        setThemeRole(detailLabel, "title");
        detailLabel.putClientProperty("fixedForeground", accent);
        JLabel noteLabel = new JLabel("<html><body style='width:220px'>" + joke + "</body></html>");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.PLAIN, 10.3f));
        setThemeRole(noteLabel, "muted");
        text.add(titleLabel);
        text.add(Box.createVerticalStrut(1));
        text.add(valueLabel);
        text.add(Box.createVerticalStrut(2));
        text.add(detailLabel);
        text.add(Box.createVerticalStrut(3));
        text.add(noteLabel);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private static JLabel reportBadge(String text, Color background, Palette palette) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBackground(background);
        label.setForeground(palette.text());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.border(), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        return label;
    }

    private static String reportSummaryHtml(
            String active, Totals totals, String distance, Color timeColor, Color keyColor, Color clickColor, Color mouseColor) {
        return "<html><body style='width:500px'><span>You clocked </span><b><font color='" + htmlColor(timeColor) + "'>" + active
                + "</font></b><span>, hit </span><b><font color='" + htmlColor(keyColor) + "'>"
                + String.format(Locale.getDefault(), "%,d", totals.keyPresses())
                + "</font></b><span> keys, clicked </span><b><font color='" + htmlColor(clickColor) + "'>"
                + String.format(Locale.getDefault(), "%,d", totals.mouseClicks())
                + "</font></b><span> times, and sent your mouse on a </span><b><font color='"
                + htmlColor(mouseColor) + "'>" + distance + "</font></b><span> adventure.</span></body></html>";
    }

    private static String htmlColor(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static int activityScore(Totals totals, double distanceKm) {
        double hours = Math.max(0.0, totals.activeSeconds()) / 3600.0;
        double time = Math.min(1.0, hours / 8.0) * 40.0;
        double keys = Math.min(1.0, Math.max(0L, totals.keyPresses()) / 18_000.0) * 25.0;
        double clicks = Math.min(1.0, Math.max(0L, totals.mouseClicks()) / 6_000.0) * 20.0;
        double mouse = Math.min(1.0, Math.max(0.0, distanceKm) / 1.0) * 15.0;
        return Math.max(0, Math.min(100, (int) Math.round(time + keys + clicks + mouse)));
    }

    private static String activityVibe(int score) {
        if (score < 12) return "WARM-UP";
        if (score < 30) return "LIGHT JOG";
        if (score < 50) return "STEADY STRIDE";
        if (score < 70) return "SERIOUS MILEAGE";
        if (score < 90) return "BEAST MODE";
        return "ULTRA MODE";
    }

    private static String reportHeadline(Totals totals, double distanceKm) {
        double hours = Math.max(0.0, totals.activeSeconds()) / 3600.0;
        if (hours < 0.05 && totals.keyPresses() == 0L && totals.mouseClicks() == 0L && distanceKm < 0.001) {
            return "Your devices basically had a spa day.";
        }
        if (hours >= 12.0) return "Your keyboard would like to schedule a recovery meeting.";
        if (totals.keyPresses() >= 50_000L) return "That keyboard did not come here to play.";
        if (totals.mouseClicks() >= 20_000L) return "Your click finger has earned overtime.";
        if (distanceKm >= 5.0) return "Your mouse has been sightseeing without you.";
        if (hours >= 8.0) return "A full-on digital endurance day.";
        if (hours >= 4.0) return "Solid mileage. Your desk definitely noticed.";
        if (hours >= 1.0) return "A respectable digital training session.";
        return "Short session, surprisingly busy fingers.";
    }

    private static String activeEquivalent(double seconds) {
        double minutes = Math.max(0.0, seconds) / 60.0;
        long blocks = Math.round(minutes / 25.0);
        if (blocks <= 0) return "Less than one 25-minute activity block";
        return "About " + String.format(Locale.getDefault(), "%,d", blocks) + " x 25-minute activity blocks";
    }

    private static String mouseEquivalent(double kilometres) {
        double km = Math.max(0.0, kilometres);
        if (km < 0.01) return "Still near the digital starting line";
        double trackLaps = km / 0.4;
        if (trackLaps < 1.0) return String.format(Locale.getDefault(), "%.0f m of cursor travel", km * 1000.0);
        return String.format(Locale.getDefault(), "About %.1f laps of a 400 m running track", trackLaps);
    }

    private static String clickEquivalent(long clicks, double activeSeconds) {
        if (clicks <= 0L) return "0 click-finger reps";
        double hours = Math.max(0.0, activeSeconds) / 3600.0;
        if (hours < 0.05) return String.format(Locale.getDefault(), "%,d total click-finger reps", clicks);
        return String.format(Locale.getDefault(), "About %,.0f clicks per active hour", clicks / hours);
    }

    private static String keyEquivalent(long keys, double activeSeconds) {
        if (keys <= 0L) return "0 keyboard reps";
        double minutes = Math.max(0.0, activeSeconds) / 60.0;
        if (minutes < 1.0) return String.format(Locale.getDefault(), "%,d total keyboard reps", keys);
        return String.format(Locale.getDefault(), "About %,.0f key hits per active minute", keys / minutes);
    }

    private static String[] reportBadges(Totals totals, double distanceKm) {
        java.util.ArrayList<String> badges = new java.util.ArrayList<>();
        if (totals.activeSeconds() >= 8 * 3600.0) badges.add("Screen Endurance");
        if (totals.keyPresses() >= 10_000L) badges.add("Keyboard Cardio");
        if (totals.mouseClicks() >= 3_000L) badges.add("Click Commander");
        if (distanceKm >= 1.0) badges.add("Mouse Explorer");
        if (distanceKm >= 42.195) badges.add("Marathon Finisher");
        if (badges.isEmpty()) badges.add("Warm-up Crew");
        while (badges.size() < 3) {
            String fallback = switch (badges.size()) {
                case 1 -> "Digital Pacer";
                default -> "Desk Athlete";
            };
            if (!badges.contains(fallback)) badges.add(fallback); else break;
        }
        return badges.stream().limit(3).toArray(String[]::new);
    }

    private static String activeReportLine(double seconds) {
        double hours = Math.max(0.0, seconds) / 3600.0;
        if (hours < 0.05) return "Barely off the starting line. Even champions have rest days.";
        if (hours < 1.0) return "A quick digital warm-up lap. Efficient entrance, efficient exit.";
        if (hours < 4.0) return "A respectable screen-side training session.";
        if (hours < 8.0) return "Your device definitely knew you were there.";
        if (hours < 12.0) return "That is a full digital endurance day. Hydration encouraged.";
        return "Your chair may be drafting a formal complaint.";
    }

    private static String mouseReportLine(double kilometres) {
        double km = Math.max(0.0, kilometres);
        if (km < 0.001) return "Your mouse is still stretching at the start line.";
        double marathons = km / 42.195;
        if (marathons >= 1.0) {
            return String.format(Locale.getDefault(), "That is %.2f full mouse marathons. Someone get it a medal!", marathons);
        }
        double percent = marathons * 100.0;
        if (percent < 0.1) return "A tiny warm-up, but every pixel counts.";
        return String.format(Locale.getDefault(), "You covered %.1f%% of a 42.195 km marathon. Your mouse is training.", percent);
    }

    private static String clickReportLine(long clicks) {
        if (clicks <= 0L) return "Your mouse button enjoyed the quiet life.";
        if (clicks < 100L) return "A light tapping session. Very civilized.";
        if (clicks < 1_000L) return "Your click finger got a decent workout.";
        if (clicks < 10_000L) return "Your mouse button definitely earned its coffee.";
        if (clicks < 50_000L) return "Your click finger deserves a tiny medal and a snack.";
        return "Your mouse button is requesting annual leave.";
    }

    private static String keyReportLine(long keys) {
        if (keys <= 0L) return "Your keyboard had a peaceful day.";
        if (keys < 500L) return "A gentle keyboard warm-up. No keys were emotionally harmed.";
        if (keys < 5_000L) return "Your keyboard got a solid workout.";
        if (keys < 25_000L) return "Those keys have definitely done some miles.";
        if (keys < 100_000L) return "Your keyboard deserves a hydration break.";
        return "Your keyboard may now qualify as an endurance athlete.";
    }

    private static String overallReportVerdict(Totals totals, double distanceKm) {
        if (totals.activeSeconds() < 60.0 && totals.keyPresses() == 0L &&
                totals.mouseClicks() == 0L && distanceKm < 0.001) {
            return "REST DAY HERO - spectacular commitment to doing absolutely nothing.";
        }
        if (distanceKm >= 42.195) return "MOUSE MARATHON LEGEND - your cursor crossed an actual marathon finish line.";
        if (totals.keyPresses() >= 100_000L) return "KEYBOARD ENDURANCE CHAMPION - those keys need an ice bath.";
        if (totals.mouseClicks() >= 25_000L) return "CLICKING MACHINE - your mouse put in industrial-grade reps.";
        if (totals.activeSeconds() >= 12 * 3600.0) return "DIGITAL ULTRA-RUNNER - impressive mileage; your chair has questions.";
        if (totals.activeSeconds() >= 8 * 3600.0) return "DIGITAL MARATHONER - a proper endurance session completed.";
        if (totals.activeSeconds() >= 4 * 3600.0) return "DESK ATHLETE - solid work, serious finger mileage.";
        if (totals.activeSeconds() >= 2 * 3600.0) return "STEADY PACER - useful digital mileage without the drama.";
        return "DIGITAL SPRINTER - short, sharp and surprisingly busy.";
    }

    private static final class ColorfulMetricIcon implements Icon {
        private enum Kind { CLOCK, MOUSE, CLICK, KEYBOARD }
        private final Kind kind;
        private final Color primary;
        private final Color secondary;
        private final int size;

        private ColorfulMetricIcon(Kind kind, Color primary, Color secondary, int size) {
            this.kind = kind;
            this.primary = primary;
            this.secondary = secondary;
            this.size = Math.max(20, size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);
                double scale = size / 36.0;
                g.scale(scale, scale);

                // Soft, colorful tile so every symbol reads clearly at small sizes.
                g.setPaint(new LinearGradientPaint(0f, 0f, 36f, 36f,
                        new float[] {0f, 1f},
                        new Color[] {mixColor(primary, Color.WHITE, 0.78f), mixColor(secondary, Color.WHITE, 0.73f)}));
                g.fillRoundRect(0, 0, 36, 36, 12, 12);
                g.setColor(new Color(primary.getRed(), primary.getGreen(), primary.getBlue(), 90));
                g.setStroke(new BasicStroke(1.1f));
                g.drawRoundRect(1, 1, 34, 34, 11, 11);

                if (kind == Kind.CLOCK) {
                    // Golden clock with violet rim and blue/orange hands.
                    g.setColor(new Color(255, 213, 80));
                    g.fillOval(7, 6, 22, 22);
                    g.setColor(primary);
                    g.setStroke(new BasicStroke(2.3f));
                    g.drawOval(7, 6, 22, 22);
                    g.setColor(new Color(65, 105, 225));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(18, 17, 18, 10);
                    g.setColor(new Color(235, 91, 67));
                    g.drawLine(18, 17, 24, 20);
                    g.fillOval(15, 14, 6, 6);
                    g.setColor(new Color(54, 183, 118));
                    g.fillRoundRect(13, 2, 10, 4, 3, 3);
                } else if (kind == Kind.MOUSE) {
                    // Cyan mouse with purple wheel and green motion trail.
                    g.setColor(new Color(73, 177, 233));
                    g.fillRoundRect(11, 5, 16, 25, 10, 10);
                    g.setColor(new Color(34, 91, 150));
                    g.setStroke(new BasicStroke(1.8f));
                    g.drawRoundRect(11, 5, 16, 25, 10, 10);
                    g.drawLine(19, 6, 19, 14);
                    g.setColor(new Color(132, 83, 220));
                    g.fillRoundRect(17, 8, 4, 7, 3, 3);
                    g.setColor(new Color(48, 181, 117));
                    g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawArc(4, 19, 11, 10, 95, 135);
                    g.drawArc(2, 23, 13, 9, 100, 120);
                    g.setColor(new Color(244, 174, 55));
                    g.fillOval(26, 5, 5, 5);
                } else if (kind == Kind.CLICK) {
                    // Orange click finger plus a bright multicolor click burst.
                    g.setColor(new Color(247, 164, 64));
                    g.fillRoundRect(14, 11, 7, 17, 5, 5);
                    g.fillRoundRect(18, 17, 11, 10, 6, 6);
                    g.setColor(new Color(166, 91, 35));
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRoundRect(14, 11, 7, 17, 5, 5);
                    g.drawRoundRect(18, 17, 11, 10, 6, 6);
                    g.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(new Color(126, 87, 230));
                    g.drawLine(19, 7, 19, 3);
                    g.setColor(new Color(48, 151, 219));
                    g.drawLine(24, 9, 28, 5);
                    g.setColor(new Color(53, 183, 120));
                    g.drawLine(27, 14, 32, 13);
                    g.setColor(new Color(237, 79, 97));
                    g.drawLine(13, 9, 10, 5);
                    g.setColor(new Color(244, 174, 55));
                    g.fillOval(17, 5, 5, 5);
                } else {
                    // Green keyboard with individually colored key caps.
                    g.setColor(new Color(61, 174, 126));
                    g.fillRoundRect(5, 9, 27, 19, 5, 5);
                    g.setColor(new Color(30, 112, 82));
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRoundRect(5, 9, 27, 19, 5, 5);
                    Color[] keyColors = {
                            new Color(126, 87, 230), new Color(48, 151, 219),
                            new Color(244, 174, 55), new Color(237, 79, 97)
                    };
                    for (int row = 0; row < 2; row++) {
                        for (int col = 0; col < 5; col++) {
                            g.setColor(keyColors[(row + col) % keyColors.length]);
                            g.fillRoundRect(8 + col * 4, 12 + row * 5, 3, 3, 1, 1);
                        }
                    }
                    g.setColor(new Color(250, 250, 250));
                    g.fillRoundRect(10, 23, 17, 3, 2, 2);
                    g.setColor(new Color(48, 151, 219));
                    g.fillOval(29, 5, 5, 5);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ReportClockIcon implements Icon {
        private final Color color;
        private final int size;

        private ReportClockIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pad = 2;
                int diameter = size - pad * 2 - 1;
                g.drawOval(x + pad, y + pad, diameter, diameter);
                int cx = x + size / 2;
                int cy = y + size / 2;
                g.drawLine(cx, cy, cx, y + 6);
                g.drawLine(cx, cy, x + size - 6, cy + 3);
                g.fillOval(cx - 2, cy - 2, 4, 4);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ReportMedalIcon implements Icon {
        private final Color ribbon;
        private final Color medal;
        private final int size;

        private ReportMedalIcon(Color ribbon, Color medal, int size) {
            this.ribbon = ribbon;
            this.medal = medal;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int cx = x + size / 2;
                int top = y + 1;
                int medalRadius = Math.max(4, Math.round(size * 0.25f));
                int medalDiameter = medalRadius * 2;
                int medalTop = y + size - medalDiameter - 1;
                int ribbonHalf = Math.max(3, Math.round(size * 0.19f));
                int ribbonInner = Math.max(1, Math.round(size * 0.06f));
                g.setColor(ribbon);
                Polygon left = new Polygon(
                        new int[] {cx - ribbonHalf, cx - ribbonInner, cx - 2, cx - ribbonHalf - 1},
                        new int[] {top, top, medalTop + 3, medalTop + 1}, 4);
                Polygon right = new Polygon(
                        new int[] {cx + ribbonInner, cx + ribbonHalf, cx + ribbonHalf + 1, cx + 2},
                        new int[] {top, top, medalTop + 1, medalTop + 3}, 4);
                g.fillPolygon(left);
                g.fillPolygon(right);
                g.setColor(medal);
                g.fillOval(cx - medalRadius, medalTop, medalDiameter, medalDiameter);
                g.setColor(ribbon.darker());
                g.setStroke(new BasicStroke(Math.max(1f, size / 18f)));
                g.drawOval(cx - medalRadius, medalTop, medalDiameter, medalDiameter);

                double outer = medalRadius * 0.62;
                double inner = outer * 0.46;
                double centreY = medalTop + medalRadius + 0.4;
                Polygon star = new Polygon();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    double radius = (i % 2 == 0) ? outer : inner;
                    star.addPoint(
                            (int) Math.round(cx + Math.cos(angle) * radius),
                            (int) Math.round(centreY + Math.sin(angle) * radius));
                }
                g.drawPolygon(star);
            } finally {
                g.dispose();
            }
        }
    }

    private void toggleTracking() {
        if (tracker.snapshot().running()) { tracker.stop(); usage("tracking_paused"); }
        else { tracker.start(); usage("tracking_resumed"); }
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export activity as CSV");
        chooser.setSelectedFile(new java.io.File("input-activity-report.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        RangeOption range = selectedRange();
        Snapshot snapshot = tracker.snapshot();
        TimeWindow window = range.kind() == RangeOption.Kind.SESSION
                ? new TimeWindow(snapshot.sessionStart(), Instant.now())
                : range.resolve(zone, ((java.util.Date) customStartSpinner.getValue()).toInstant(), ((java.util.Date) customEndSpinner.getValue()).toInstant());
        Path file = chooser.getSelectedFile().toPath();
        UnitConverter.Unit unit = selectedUnit();
        double ppi = selectedPpi();
        busyDataOperations.incrementAndGet();
        queryExecutor.submit(() -> {
            try {
                store.export(file, window.start(), window.end(), zone, unit, ppi);
                usage("csv_exported");
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "CSV exported to:\n" + file.toAbsolutePath(), "Export complete", JOptionPane.INFORMATION_MESSAGE));
            } catch (IOException error) {
                usage("csv_export_failed", Map.of("error_code", "save_failed"));
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Could not export CSV:\n" + error.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE));
            } finally {
                busyDataOperations.decrementAndGet();
            }
        });
    }

    private void clearHistory() {
        int result = JOptionPane.showConfirmDialog(this,
                "Delete all saved activity history? This cannot be undone.",
                "Clear activity history", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;
        busyDataOperations.incrementAndGet();
        queryExecutor.submit(() -> {
            try {
                store.clear();
                usage("history_cleared");
                SwingUtilities.invokeLater(() -> requestDataRefresh(true));
            } catch (IOException error) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Could not clear history:\n" + error.getMessage(), "Clear failed", JOptionPane.ERROR_MESSAGE));
            } finally {
                busyDataOperations.decrementAndGet();
            }
        });
    }

    private void showHelpCenter(String section) {
        usage("help_opened");
        HelpCenterDialog dialog = new HelpCenterDialog(this, darkTheme, section, services);
        dialog.setVisible(true);
    }

    private void showPermissionsHelp() {
        usage("permissions_help_opened");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ThemeDialog.permissions(this, darkTheme,
                os.contains("mac") ? "mac" : os.contains("linux") ? "linux" : "windows",
                () -> openMacPrivacyPane("Privacy_ListenEvent"),
                () -> openMacPrivacyPane("Privacy_Accessibility"));
    }

    private void openMacPrivacyPane(String pane) {
        try {
            new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?" + pane).start();
        } catch (IOException error) {
            ThemeDialog.message(this, darkTheme, "Open System Settings",
                    "Open System Settings → Privacy & Security manually, then choose Input Monitoring or Accessibility.");
        }
    }

    private void shutdownAndExit() {
        stopUpdateReviewResizeWait();
        rememberCurrentWindowState();
        flushPreferences();
        setVisible(false);
        if (refreshExecutor != null) refreshExecutor.shutdownNow();
        queryExecutor.shutdownNow();
        tracker.close();
        dispose();
        System.exit(0);
    }

    /** Preparatory persistence never pauses tracking or creates a restart handoff. */
    void prepareUpdateClose(Runnable ready, java.util.function.Consumer<String> failure) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> prepareUpdateClose(ready, failure));
            return;
        }
        if (updateClosePreparing) return;
        String waitReason = updateCloseWaitReason();
        if (waitReason != null) {
            failure.accept(waitReason);
            return;
        }
        updateClosePreparing = true;
        updateClosePrepared = false;
        rememberCurrentWindowState();
        queryExecutor.submit(() -> {
            try {
                preferences.flush();
                tracker.flushUpdateHistory();
                SwingUtilities.invokeLater(() -> {
                    updateClosePreparing = false;
                    updateClosePrepared = true;
                    ready.run();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    updateClosePreparing = false;
                    failure.accept("Your session could not be saved safely. Tracking continues. "
                            + "Try again after resolving the storage issue.");
                });
            }
        });
    }

    /** Only the explicit, confirmed update action calls this final close step. */
    void closeForPreparedUpdate(java.util.function.Consumer<String> failure) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> closeForPreparedUpdate(failure));
            return;
        }
        if (!updateClosePrepared) {
            failure.accept("Save the current session before closing for this update.");
            return;
        }
        String waitReason = updateCloseWaitReason();
        if (waitReason != null) {
            updateClosePrepared = false;
            failure.accept(waitReason);
            return;
        }
        try {
            rememberCurrentWindowState();
            preferences.flush();
            // Freeze and write the final snapshot immediately before exit so
            // any counts accrued during preparation are included on reopening.
            tracker.finishUpdateSession(AppPaths.dataDirectory());
            shutdownAndExit();
        } catch (Exception error) {
            updateClosePrepared = false;
            failure.accept("Your session could not be saved safely. Tracking continues. "
                    + "Try again after resolving the storage issue.");
        }
    }

    private String updateCloseWaitReason() {
        if (viewTransitionInProgress || updateReviewTransitionInProgress)
            return "Wait for the view to finish resizing, then try again. Tracking continues.";
        if (busyDataOperations.get() > 0)
            return "Wait for the CSV export or history operation to finish, then try again. Tracking continues.";
        for (Window child : getOwnedWindows()) {
            if (child instanceof Dialog dialog && dialog.isModal() && dialog.isShowing())
                return "Finish or close the current dialog before closing for an update. Tracking continues.";
        }
        return null;
    }

    private RangeOption selectedRange() {
        RangeOption selected = (RangeOption) rangeCombo.getSelectedItem();
        return selected == null ? RangeOption.defaults()[0] : selected;
    }

    private UnitConverter.Unit selectedUnit() {
        UnitConverter.Unit selected = (UnitConverter.Unit) unitCombo.getSelectedItem();
        return selected == null ? UnitConverter.Unit.MILLIMETRES : selected;
    }

    private double selectedPpi() {
        return ((Number) ppiSpinner.getValue()).doubleValue();
    }

    private void updateCustomRangeVisibility() {
        if (customRangePanel != null) {
            customRangePanel.setVisible(selectedRange().kind() == RangeOption.Kind.CUSTOM);
            customRangePanel.getParent().revalidate();
        }
    }

    private static void addFilter(JPanel panel, GridBagConstraints gc, int x, String label, JComponent component) {
        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.X_AXIS));
        JLabel caption = new JLabel(label);
        caption.setForeground(MUTED);
        caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 11f));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        setThemeRole(caption, "muted");
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.add(caption);
        group.add(Box.createHorizontalStrut(7));
        Dimension fieldSize = new Dimension(component instanceof JComboBox<?> ? (x == 0 ? 142 : 154) : 64, 30);
        component.setPreferredSize(fieldSize);
        component.setMinimumSize(fieldSize);
        component.setMaximumSize(fieldSize);
        group.add(component);
        gc.gridx = x;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.weightx = x == 0 ? 0.6 : 0.0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
        panel.add(group, gc);
    }

    private static JSpinner dateSpinner(java.util.Date value) {
        JSpinner spinner = new JSpinner(new SpinnerDateModel(value, null, null, java.util.Calendar.MINUTE));
        spinner.setEditor(new JSpinner.DateEditor(spinner, "yyyy-MM-dd HH:mm"));
        spinner.setPreferredSize(new Dimension(145, 28));
        return spinner;
    }


    /**
     * Rounded, high-contrast rendering for the two view-switch buttons. Using
     * one explicit painter keeps the same soft corners on macOS, Windows and
     * Linux instead of relying on each platform's native square button skin.
     */
    private static final class RoundedViewButtonUI extends BasicButtonUI {
        private final Color outline;

        private RoundedViewButtonUI(Color outline) {
            this.outline = outline;
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ButtonModel model = button.getModel();
                Color fill = button.getBackground();
                if (model.isPressed() && model.isArmed()) {
                    fill = mixColor(fill, button.getForeground(), 0.16f);
                } else if (model.isRollover()) {
                    fill = mixColor(fill, button.getForeground(), 0.07f);
                }

                int width = Math.max(1, component.getWidth() - 1);
                int height = Math.max(1, component.getHeight() - 1);
                int arc = Math.min(14, Math.max(8, component.getHeight() - 6));
                g.setColor(fill);
                g.fillRoundRect(0, 0, width, height, arc, arc);
                g.setColor(outline);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, Math.max(0, width - 2), Math.max(0, height - 2), arc, arc);

                if (button.isFocusPainted() && button.hasFocus()) {
                    g.setColor(new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), 105));
                    g.setStroke(new BasicStroke(1f));
                    g.drawRoundRect(3, 3, Math.max(0, width - 6), Math.max(0, height - 6),
                            Math.max(6, arc - 3), Math.max(6, arc - 3));
                }
            } finally {
                g.dispose();
            }
            super.paint(graphics, component);
        }
    }

    private static JButton fancyDownloadCertificateButton() {
        JButton button = new JButton("Download Certificate");
        setThemeRole(button, "downloadButton");
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11.5f));
        button.setMargin(new Insets(5, 12, 5, 12));
        button.setIconTextGap(7);
        button.setPreferredSize(new Dimension(190, 34));
        button.setMinimumSize(new Dimension(190, 34));
        return button;
    }

    private static final class BrightDownloadButtonUI extends BasicButtonUI {
        private final Palette palette;
        private final boolean dark;

        private BrightDownloadButtonUI(Palette palette, boolean dark) {
            this.palette = palette;
            this.dark = dark;
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = component.getWidth();
                int height = component.getHeight();
                ButtonModel model = button.getModel();
                Color left = new Color(112, 76, 205);
                Color middle = new Color(45, 132, 210);
                Color right = new Color(236, 174, 54);
                if (model.isRollover()) {
                    left = mixColor(left, Color.WHITE, 0.15f);
                    middle = mixColor(middle, Color.WHITE, 0.15f);
                    right = mixColor(right, Color.WHITE, 0.15f);
                }
                if (model.isPressed()) {
                    left = left.darker(); middle = middle.darker(); right = right.darker();
                }
                g.setPaint(new LinearGradientPaint(0f, 0f, Math.max(1, width), 0f,
                        new float[] {0f, 0.58f, 1f}, new Color[] {left, middle, right}));
                g.fillRoundRect(0, 0, width - 1, height - 1, 18, 18);
                g.setColor(new Color(255, 255, 255, dark ? 105 : 160));
                g.setStroke(new BasicStroke(1.4f));
                g.drawRoundRect(1, 1, Math.max(0, width - 3), Math.max(0, height - 3), 17, 17);
                if (model.isRollover()) {
                    g.setColor(new Color(255, 255, 255, 38));
                    g.fillRoundRect(4, 4, Math.max(0, width - 8), Math.max(0, height / 2), 13, 13);
                }
            } finally { g.dispose(); }
            super.paint(graphics, component);
        }
    }

    private static JButton fancyReportButton() {
        JButton button = new JButton("My Digital Marathon Certificate");
        setThemeRole(button, "reportButton");
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11.5f));
        button.setMargin(new Insets(3, 10, 3, 12));
        button.setIconTextGap(7);
        button.setPreferredSize(new Dimension(240, 30));
        button.setMinimumSize(new Dimension(240, 30));
        button.setMaximumSize(new Dimension(240, 30));
        button.getAccessibleContext().setAccessibleName("My Digital Marathon Certificate");
        return button;
    }

    private static final class FancyReportButtonUI extends BasicButtonUI {
        private final Palette palette;
        private final boolean dark;

        private FancyReportButtonUI(Palette palette, boolean dark) {
            this.palette = palette;
            this.dark = dark;
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = component.getWidth();
                int height = component.getHeight();
                ButtonModel model = button.getModel();
                Color left = mixColor(palette.chartBar(), palette.violetTint(), dark ? 0.12f : 0.18f);
                Color right = mixColor(palette.chartBar(), palette.greenTint(), dark ? 0.18f : 0.28f);
                if (model.isRollover()) {
                    left = mixColor(left, Color.WHITE, dark ? 0.08f : 0.18f);
                    right = mixColor(right, Color.WHITE, dark ? 0.08f : 0.18f);
                }
                if (model.isPressed()) {
                    left = left.darker();
                    right = right.darker();
                }
                g.setPaint(new GradientPaint(0, 0, left, width, height, right));
                g.fillRoundRect(0, 0, width - 1, height - 1, 18, 18);
                g.setColor(new Color(255, 255, 255, dark ? 58 : 105));
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(1, 1, Math.max(0, width - 3), Math.max(0, height - 3), 17, 17);
                if (model.isRollover()) {
                    g.setColor(new Color(255, 255, 255, dark ? 18 : 42));
                    g.fillRoundRect(4, 4, Math.max(0, width - 8), Math.max(0, height / 2 - 2), 13, 13);
                }
            } finally {
                g.dispose();
            }
            super.paint(graphics, component);
        }
    }

    private static final class ReportSparkIcon implements Icon {
        private final Color outline;
        private final int size;

        private ReportSparkIcon(Color outline, int size) {
            this.outline = outline;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = size / 20.0;
                g.translate(x, y);
                g.scale(scale, scale);

                g.setColor(new Color(255, 255, 255, 225));
                g.fillRoundRect(1, 1, 18, 18, 6, 6);
                g.setColor(new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), 145));
                g.setStroke(new BasicStroke(1.0f));
                g.drawRoundRect(1, 1, 18, 18, 6, 6);

                g.setColor(new Color(126, 87, 230));
                g.fillRoundRect(4, 11, 3, 5, 2, 2);
                g.setColor(new Color(48, 151, 219));
                g.fillRoundRect(8, 8, 3, 8, 2, 2);
                g.setColor(new Color(53, 183, 120));
                g.fillRoundRect(12, 5, 3, 11, 2, 2);

                g.setColor(new Color(244, 174, 55));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(5, 7, 8, 5);
                g.drawLine(8, 5, 10, 6);
                g.drawLine(10, 6, 14, 3);
                g.fillOval(13, 2, 3, 3);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ReportFinishFlagIcon implements Icon {
        private final Color accent;
        private final Color line;
        private final int size;

        private ReportFinishFlagIcon(Color accent, Color line, int size) {
            this.accent = accent;
            this.line = line;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = size / 52.0;
                g.translate(x, y);
                g.scale(scale, scale);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(line);
                g.drawLine(12, 7, 12, 44);
                g.drawLine(8, 44, 26, 44);
                g.setColor(accent);
                g.fillRoundRect(14, 8, 28, 20, 3, 3);
                Color[] flagColors = {
                        new Color(126, 87, 230), new Color(48, 151, 219),
                        new Color(53, 183, 120), new Color(244, 174, 55)
                };
                int cell = 7;
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 4; col++) {
                        g.setColor((row + col) % 2 == 0
                                ? new Color(255, 255, 255, 225)
                                : flagColors[(row + col) % flagColors.length]);
                        g.fillRect(14 + col * cell, 8 + row * cell, cell, cell);
                    }
                }
                g.setColor(line);
                g.drawLine(36, 35, 41, 35);
                g.drawLine(38, 32, 38, 38);
                g.drawLine(28, 39, 32, 43);
                g.drawLine(32, 39, 28, 43);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ReportActivityMeter extends JComponent {
        private final int percent;
        private final Color track;
        private final Color fill;

        private ReportActivityMeter(int percent, Color track, Color fill) {
            this.percent = Math.max(0, Math.min(100, percent));
            this.track = track;
            this.fill = fill;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int height = Math.max(6, getHeight());
                int width = Math.max(1, getWidth());
                int arc = height;
                g.setColor(track);
                g.fillRoundRect(0, 0, width, height, arc, arc);
                int fillWidth = Math.max(percent > 0 ? height : 0, (int) Math.round(width * percent / 100.0));
                if (fillWidth > 0) {
                    float end = Math.max(1f, fillWidth);
                    g.setPaint(new LinearGradientPaint(
                            0f, 0f, end, 0f,
                            new float[] {0f, 0.34f, 0.68f, 1f},
                            new Color[] {
                                    new Color(126, 87, 230),
                                    new Color(48, 151, 219),
                                    new Color(53, 183, 120),
                                    new Color(244, 174, 55)
                            }));
                    g.fillRoundRect(0, 0, Math.min(width, fillWidth), height, arc, arc);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static JButton primaryButton(String text) {
        JButton button = secondaryButton(text);
        setThemeRole(button, "primaryButton");
        // Keep native button painting on macOS/Windows/Linux, but never use white text on
        // a platform theme that may ignore the requested blue background.
        button.setForeground(TEXT);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setMargin(new Insets(7, 14, 7, 14));
        return button;
    }

    private static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        setThemeRole(button, "secondaryButton");
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setMargin(new Insets(6, 12, 6, 12));
        button.setMinimumSize(new Dimension(110, 32));
        return button;
    }

    private static JButton viewModeButton(String text, boolean expand) {
        JButton button = secondaryButton(text);
        setThemeRole(button, "viewModeButton");
        button.setIcon(new ViewModeIcon(expand, expand ? 13 : 16));
        button.setIconTextGap(expand ? 4 : 7);
        button.setFont(button.getFont().deriveFont(Font.BOLD, expand ? 10f : 12f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static Color mixColor(Color first, Color second, float secondWeight) {
        float bounded = Math.max(0f, Math.min(1f, secondWeight));
        float firstWeight = 1.0f - bounded;
        return new Color(
                Math.round(first.getRed() * firstWeight + second.getRed() * bounded),
                Math.round(first.getGreen() * firstWeight + second.getGreen() * bounded),
                Math.round(first.getBlue() * firstWeight + second.getBlue() * bounded),
                Math.round(first.getAlpha() * firstWeight + second.getAlpha() * bounded));
    }

    private Image loadIcon() {
        return loadImageResource(APPLE_CHROME ? "/app-icon-mac.png" : "/app-icon.png");
    }

    private Image loadHeaderIcon() {
        return loadImageResource("/header-icon.png");
    }

    private Image loadImageResource(String resource) {
        try (InputStream stream = TrackerWindow.class.getResourceAsStream(resource)) {
            if (stream != null) return ImageIO.read(stream);
        } catch (IOException ignored) {}
        return new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    private final class DailyTableModel extends AbstractTableModel {
        private final String[] columns = {"Interval", "Mouse", "Keys", "Clicks", "Active"};
        private List<Group> rows = List.of();
        private UnitConverter.Unit unit = UnitConverter.Unit.MILLIMETRES;
        private double ppi = 96.0;

        void setRows(List<Group> rows, UnitConverter.Unit unit, double ppi) {
            this.rows = rows == null ? List.of() : List.copyOf(rows);
            this.unit = unit;
            this.ppi = ppi;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Group group = rows.get(rowIndex);
            Totals totals = group.totals();
            return switch (columnIndex) {
                case 0 -> group.label();
                case 1 -> UnitConverter.formatDistance(totals.mousePixels(), unit, ppi);
                case 2 -> String.format(Locale.getDefault(), "%,d", totals.keyPresses());
                case 3 -> String.format(Locale.getDefault(), "%,d", totals.mouseClicks());
                case 4 -> UnitConverter.formatDuration(totals.activeSeconds());
                default -> "";
            };
        }
    }
}
