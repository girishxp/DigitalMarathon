package com.inputactivitytracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** In-application help, troubleshooting, about, guide and contact centre. */
final class HelpCenterDialog extends JDialog {
    private static final boolean APPLE_CHROME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
            || Boolean.getBoolean("digitalmarathon.forceAppleChrome");
    private static final String VERSION = AppServices.VERSION;
    private static final String EMAIL = "girish.gupta@gmail.com";

    private final boolean dark;
    private final AppServices services;
    private Runnable serviceObserver;
    private final Color page;
    private final Color surface;
    private final Color text;
    private final Color muted;
    private final Color border;
    private final Color accent;
    private final Color selection;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JList<String> navigation;
    private final Map<String, JComponent> sections = new LinkedHashMap<>();

    HelpCenterDialog(Window owner, boolean darkTheme, String initialSection) {
        this(owner, darkTheme, initialSection, null);
    }

    HelpCenterDialog(Window owner, boolean darkTheme, String initialSection, AppServices services) {
        super(owner, "Digital Marathon - Help & About", ModalityType.MODELESS);
        dark = darkTheme;
        this.services = services;
        page = dark ? new Color(28, 28, 30) : new Color(245, 245, 247);
        surface = dark ? new Color(44, 44, 46) : new Color(255, 255, 255);
        text = dark ? new Color(242, 242, 247) : new Color(29, 29, 31);
        muted = dark ? new Color(142, 142, 147) : new Color(110, 110, 115);
        border = dark ? new Color(72, 72, 74) : new Color(209, 209, 214);
        accent = dark ? new Color(10, 132, 255) : new Color(0, 122, 255);
        selection = dark ? new Color(38, 69, 96) : new Color(224, 240, 255);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        if (APPLE_CHROME) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", Boolean.TRUE);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", Boolean.FALSE);
            getRootPane().putClientProperty("apple.awt.draggableWindowBackground", Boolean.TRUE);
        }
        setMinimumSize(new Dimension(720, 500));
        setSize(840, 580);
        setLocationRelativeTo(owner);
        getContentPane().setBackground(page);

        sections.put("Overview", page("Overview", overviewHtml()));
        sections.put("Help", page("Using the tracker", helpHtml()));
        sections.put("Troubleshooting", page("Troubleshooting", troubleshootingHtml()));
        sections.put("About", aboutPage());
        sections.put("Contact Us", contactPage());

        navigation = new JList<>(sections.keySet().toArray(String[]::new));
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navigation.setFixedCellHeight(42);
        navigation.setBorder(new EmptyBorder(7, 7, 7, 7));
        navigation.setBackground(page);
        navigation.setForeground(text);
        navigation.setSelectionBackground(selection);
        navigation.setSelectionForeground(text);
        navigation.setFont(navigation.getFont().deriveFont(Font.BOLD, 13f));
        navigation.setCellRenderer(new AppleSidebarRenderer());
        navigation.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && navigation.getSelectedValue() != null) {
                cards.show(cardPanel, navigation.getSelectedValue());
            }
        });

        for (Map.Entry<String, JComponent> entry : sections.entrySet()) {
            cardPanel.add(entry.getValue(), entry.getKey());
        }
        cardPanel.setBackground(page);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(page);
        header.setBorder(new EmptyBorder(APPLE_CHROME ? 34 : 16, 20, 12, 20));
        JLabel appIcon = new JLabel(new AppBrandIcon(loadHeaderIcon(), 32, 32));
        appIcon.setVerticalAlignment(SwingConstants.TOP);
        JPanel headerText = new JPanel();
        headerText.setOpaque(false);
        headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("Help & About");
        heading.setForeground(text);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("Guidance, troubleshooting, product information and support");
        subtitle.setForeground(muted);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        JLabel versionInfo = new JLabel("Version " + VERSION);
        versionInfo.setForeground(muted);
        versionInfo.setFont(versionInfo.getFont().deriveFont(Font.PLAIN, 10f));
        headerText.add(heading);
        headerText.add(Box.createVerticalStrut(2));
        headerText.add(subtitle);
        headerText.add(Box.createVerticalStrut(3));
        headerText.add(versionInfo);
        header.add(appIcon, BorderLayout.WEST);
        header.add(headerText, BorderLayout.CENTER);

        JScrollPane navigationScroll = new JScrollPane(navigation);
        navigationScroll.setBorder(BorderFactory.createEmptyBorder());
        navigationScroll.getViewport().setBackground(page);
        navigationScroll.setOpaque(false);
        navigationScroll.getViewport().setOpaque(false);
        navigationScroll.setPreferredSize(new Dimension(178, 0));
        RoundedPanel sidebarCard = new RoundedPanel(new BorderLayout(), surface, 18);
        sidebarCard.setPanelColors(surface, border);
        sidebarCard.setBorder(new EmptyBorder(5, 5, 5, 5));
        sidebarCard.add(navigationScroll, BorderLayout.CENTER);

        JPanel centre = new JPanel(new BorderLayout(12, 0));
        centre.setBackground(page);
        centre.setBorder(new EmptyBorder(0, 20, 12, 20));
        centre.add(sidebarCard, BorderLayout.WEST);
        centre.add(cardPanel, BorderLayout.CENTER);

        JButton guide = actionButton("Open Complete PDF Guide", true);
        guide.addActionListener(event -> openGuide());
        JButton close = actionButton("Close", false);
        close.addActionListener(event -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footer.setBackground(page);
        footer.setBorder(new EmptyBorder(2, 20, 16, 20));
        footer.add(guide);
        footer.add(close);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        String section = sections.containsKey(initialSection) ? initialSection : "Overview";
        navigation.setSelectedValue(section, true);
    }

    private JComponent page(String title, String html) {
        RoundedPanel panel = new RoundedPanel(new BorderLayout(0, 10), surface, 18);
        panel.setPanelColors(surface, border);
        panel.setBorder(new EmptyBorder(18, 20, 18, 20));

        JLabel heading = new JLabel(title);
        heading.setForeground(text);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));

        JEditorPane content = new JEditorPane("text/html", wrapHtml(html));
        content.setEditable(false);
        content.setFocusable(true);
        content.setOpaque(true);
        content.setBackground(surface);
        content.setForeground(text);
        content.setBorder(null);
        content.setCaretPosition(0);
        content.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                openUri(event.getURL().toString());
            }
        });
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(surface);

        panel.add(heading, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent aboutPage() {
        JComponent panel = page("About Digital Marathon", aboutHtml());
        JPanel controls = new JPanel(); controls.setOpaque(false); controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JTextArea status = new JTextArea(services == null ? "Online services are unavailable in this test window." : services.status(), 2, 40);
        status.setEditable(false); status.setFocusable(false); status.setOpaque(false);
        status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        status.setForeground(muted); status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        JButton check = actionButton("Check for updates", true);
        check.setEnabled(services != null); check.addActionListener(e -> {
            check.setEnabled(false); status.setText("Checking for updates…"); services.updates.checkNow();
        });
        controls.add(check); controls.add(Box.createVerticalStrut(8)); controls.add(status);
        panel.add(controls, BorderLayout.SOUTH);
        if (services != null) {
            serviceObserver = () -> {
                status.setText(services.status()); check.setEnabled(true);
            };
            services.observe(serviceObserver);
        }
        return panel;
    }

    @Override public void dispose() {
        if (services != null && serviceObserver != null) services.unobserve(serviceObserver);
        super.dispose();
    }

    private JComponent contactPage() {
        RoundedPanel panel = new RoundedPanel(new BorderLayout(), surface, 18);
        panel.setPanelColors(surface, border);
        panel.setBorder(new EmptyBorder(24, 24, 24, 24));
        JPanel contentPanel = new JPanel();
        contentPanel.setOpaque(false);
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Contact Us");
        title.setForeground(text);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel name = new JLabel("Girish Gupta");
        name.setForeground(text);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 17f));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel role = new JLabel("Creator, Product Designer & Developer of Digital Marathon");
        role.setForeground(muted);
        role.setFont(role.getFont().deriveFont(Font.PLAIN, 13f));
        role.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea message = new JTextArea(
                "Questions, feedback and ideas for future improvements are welcome. "
                        + "Please include your operating system and app version when reporting an issue.");
        message.setEditable(false);
        message.setFocusable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setForeground(text);
        message.setFont(message.getFont().deriveFont(Font.PLAIN, 13f));
        message.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        message.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton email = actionButton("Email " + EMAIL, true);
        email.setAlignmentX(Component.LEFT_ALIGNMENT);
        email.addActionListener(event -> openEmail());

        contentPanel.add(title);
        contentPanel.add(Box.createVerticalStrut(24));
        contentPanel.add(name);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(role);
        contentPanel.add(Box.createVerticalStrut(20));
        contentPanel.add(message);
        contentPanel.add(Box.createVerticalStrut(18));
        contentPanel.add(email);
        contentPanel.add(Box.createVerticalGlue());
        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    private String wrapHtml(String body) {
        String background = hex(surface);
        String foreground = hex(text);
        String secondary = hex(muted);
        String link = hex(accent);
        return "<html><head><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "font-size:12px;line-height:1.52;color:" + foreground + ";background:" + background + ";margin:0;}"
                + "h2{font-size:16px;margin:12px 0 6px 0;color:" + foreground + ";}"
                + "p{margin:5px 0 10px 0;} ul{margin:5px 0 12px 18px;padding:0;} li{margin:4px 0;}"
                + ".note{color:" + secondary + ";background:" + (dark ? "#2C2C2E" : "#F2F2F7")
                + ";padding:10px;border-radius:10px;} .version{font-size:10px;color:" + secondary + ";}"
                + " a{color:" + link + ";text-decoration:none;}"
                + "code{font-family:monospace;background:" + (dark ? "#1C1C1E" : "#F2F2F7") + ";padding:2px 4px;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static String overviewHtml() {
        return "<p><b>Digital Marathon</b> makes everyday computer activity visible without recording what you type.</p>"
                + "<h2>What it measures</h2><ul>"
                + "<li>On-screen mouse travel in pixels, metric units or imperial units</li>"
                + "<li>Total physical keyboard presses</li>"
                + "<li>Total mouse clicks and active tracking time</li>"
                + "<li>Daily, weekly, monthly and multi-year history, charts and CSV exports</li>"
                + "<li>A playful Digital Marathon achievement report with an activity meter, achievements and a named downloadable certificate</li></ul>"
                + "<h2>Why it can be useful</h2><p>Use the totals and trends for personal activity awareness, ergonomic reflection, work-pattern comparison and curiosity about how far a digital workday really travels.</p>"
                + "<p class='note'>Privacy: only numerical totals are saved. Typed text, key identities, clipboard content, screenshots, window titles and application names are never stored.</p>";
    }

    private static String helpHtml() {
        return "<h2>Start and pause</h2><p>Tracking starts automatically. Use <b>Pause Tracking</b> to temporarily exclude activity and <b>Resume Tracking</b> to resume. The button keeps a neutral surface and border when running, paused, hovered or pressed; only its text and icon change with tracking state. Active time is elapsed collection time while tracking is running; it is not a measure of attention or productivity.</p>"
                + "<h2>Choose a period</h2><p>Use Current session, Last 1/2/6/12 hours, Today, Yesterday, This week, Last 7 days, Last 30 days, Last 6 months, Last 1 year, Last 2 years, Last 5 years or Custom range. Choose <b>Custom range</b> to show the From and To date/time fields. The selected range applies to Full View, Mini View, the chart, interval table, CSV export and certificate.</p>"
                + "<h2>Full View</h2><p>The Classic Mac layout keeps the refined <b>Pearl &amp; Aqua</b> Digital Marathon icon and monitoring status visible. Modestly darker color and improved rendering make small icons clearer on your display. Full View and Help keep their header icon sizes. Small icons are filtered at the display's actual pixel resolution for clearer detail on Retina and other displays. The brand is used for the Mac app icon and the running app's Dock/taskbar icon, plus Full View, certificates, Help and PDF guide. Colored cards distinguish <b>Mouse distance in blue</b>, <b>Key presses in green</b>, <b>Mouse clicks in amber</b> and <b>Active time in violet</b>. Range, distance unit, Display PPI and Permissions remain available. The bottom actions are <b>Pause/Resume Tracking</b>, <b>Reset Session</b>, <b>Export CSV</b> and <b>Clear History</b>.</p>"
                + "<h2>Activity trend</h2><p>The legend reads <b>Bars = activity</b> and <b>Line = trend</b>. Soft blue bars show each interval's relative activity. A thin coral line connects the bar tops to show rises and falls. The Y axis uses a 0-100% relative scale. Hover or click anywhere along the trend line to see the closest recorded interval's date/time, mouse distance, key presses, mouse clicks, active time and relative activity in an opaque detail card near that point. The values are actual interval totals, with no invented intermediate values. Only the line point highlights; bars keep their normal appearance and there is no shaded interval band. The detail stays visible during live updates while that interval is selected. Interval breakdown retains the original numerical totals.</p>"
                + "<h2>My Digital Marathon Certificate</h2><p>Choose a range and click the labeled <b>My Digital Marathon Certificate</b> button in Full View. The report retains <b>Mouse distance</b>, <b>Key presses</b>, <b>Mouse clicks</b> and <b>Active time</b>, with the playful Mouse on tour, Keyboard cardio, Click-finger reps and Time on the clock captions, plus the for-fun activity meter, achievements and finish-line verdict. Enter the <b>Name on Certificate</b>, then choose <b>Download Certificate</b>. The JPEG is saved automatically in your operating system's <b>Downloads</b> folder with a timestamped filename. Higher-quality JPEG export uses <b>97% quality</b> and <b>full-color 4:4:4 sampling</b> to retain finer detail and colored edges. A compact themed <b>Certificate saved</b> notice shows the filename and folder. Use <b>Open Certificate</b>, <b>Show in Folder</b>, or <b>Copy file location</b> for the complete path; <b>Done</b> closes the notice. File-opening actions depend on desktop support. The report and downloaded JPEG follow the selected <b>Classic Award</b> design. The report uses a flat blue download button, compact colored totals, a slim activity meter and achievement tiles. The JPEG uses warm paper, a fine gold double border and centered recipient name. It keeps the original gold laptop award badge at the <b>top right</b>, the selected range, colored totals and <b>Awarded by Digital Marathon</b> attribution to <b>Girish Gupta</b>, girish.gupta@gmail.com. Mouse travel is also compared with a 42.195 km marathon. This report is for fun, not a productivity score, and contains no typed content.</p>"
                + "<h2>Distance units and Display PPI</h2><p>Choose Pixels, Millimetres, Centimetres, Metres, Kilometres, Inches, Feet, Yards or Miles. Pixels are exact screen coordinates. Metric and imperial values are estimates calculated from <b>Display PPI</b>; pointer acceleration, display scaling and multiple displays can affect their relationship to physical desk movement.</p>"
                + "<h2>Mini View</h2><p>The familiar Mini View body is preserved: Mouse and Clicks share the soft-blue card, Keys keeps its soft-green card, and the range row retains its green <b>Active</b> timer with hours, minutes and seconds. Only the top toolbar is refreshed with the capture-status shield, slim transparency bar, light/dark appearance, Always on Top pin and Full View control. The pin highlights when enabled.</p>"
                + "<h2>Theme, transparency and tooltips</h2><p>Both views share light/dark appearance and a slim <b>0-75% transparency slider</b> with a <b>rounded tab handle</b>. A slightly deeper soft tint makes the tab clearer in both themes, without a halo. Bar, tab and surrounding control geometry stays the same. Drag the tab or click the bar to change transparency continuously: 0% is fully opaque and 75% is most transparent. The percentage is shown beside the bar. Smooth dragging changes transparency without moving the window, and keyboard arrow-key adjustment remains available. Move the window using clear header space or Mini View's bottom range strip. Short, opaque tooltips appear away from window content so they do not cover the Mini View counters. Hints dismiss when you leave the control. Mini View values intentionally have no tooltips.</p>"
                + "<h2>Screen Capture Privacy</h2><p>Use the <b>Hide from screenshots &amp; sharing</b> toggle in Full View. Its outer surface and border stay neutral in both states; only the switch track and knob change, without turning the whole control blue. The shield in both top toolbars reflects whether protection is applied. It is off by default; an unavailable platform never shows a false protected state. Capture exclusion depends on the operating system and capture application and cannot block an external camera or every capture method. See the platform guide for supported desktops.</p>"
                + "<h2>Permissions help</h2><p>The Full View control opens a compact window in the current light or dark theme. On macOS, follow the short Input Monitoring steps and use <b>Open Input Monitoring</b> or <b>Open Accessibility</b> to reach the matching System Settings pane. Close returns to the dashboard. Detailed recovery steps are under <b>Help &gt; Troubleshooting</b>.</p>"
                + "<h2>Reset, clear and export</h2><p><b>Reset Session</b> zeros the current live session without deleting saved history. <b>Clear History</b> deletes saved activity history only after a confirmation; it cannot be undone. <b>Export CSV</b> saves the selected period for your own spreadsheet or analysis.</p>"
                + "<h2>Remembered view and daily rollover</h2><p>The next launch restores Full or Mini View and its saved position; Full View also remembers its size. Theme, unit, Display PPI and transparency are remembered. At local midnight the current day starts again at zero while the completed day remains in saved history and Interval breakdown.</p>"
                + "<h2>Launch and installation help</h2><p>Extract the one shared folder, then double-click <b>Digital Marathon.app</b> on an Apple Silicon Mac or <b>Start Digital Marathon - Windows.bat</b> on Windows x64. Both include Java and need no terminal, build or first-launch download. Linux retains its existing launcher and first-launch build. Open <b>Digital-Marathon-Product-Guide.pdf</b> or top-level <b>QUICK_START.txt</b> for current launch and troubleshooting steps.</p>";
    }

    private static String troubleshootingHtml() {
        return "<h2>macOS: Gatekeeper blocks Digital Marathon.app</h2><p>Try right-click &gt; <b>Open</b>. After a blocked attempt, open System Settings &gt; Privacy &amp; Security and use <b>Open Anyway</b> for the trusted app. The complete one-time steps are in QUICK_START.txt.</p>"
                + "<h2>macOS: Keys or clicks stay at zero</h2><p>Enable the packaged <b>Digital Marathon.app</b> under Privacy &amp; Security &gt; Input Monitoring. Enable Accessibility as well if requested. The app retries automatically after permission is enabled; quit and reopen if macOS asks. If the switches are already on but counting still fails, run <b>REPAIR_MAC_PERMISSIONS.command</b>, re-enable Digital Marathon and reopen it from its current location.</p>"
                + "<h2>macOS: App requirements</h2><p>This package supports <b>Apple Silicon Macs with macOS 11 or later</b>. Java and the native components are included, so Apple Command Line Tools and a build are not required. An Intel Mac app is not included.</p>"
                + "<h2>macOS Terminal</h2><p>Terminal Secure Keyboard Entry intentionally blocks global keyboard observation. Digital Marathon does not bypass that security boundary.</p>"
                + "<h2>Windows: Global totals stay zero</h2><p>Check Windows Defender, endpoint-security or application-control software for a blocked global input hook, then restart Digital Marathon after allowing it.</p>"
                + "<h2>Windows: Launcher reports an error</h2><p>Extract the entire ZIP before opening <b>Start Digital Marathon - Windows.bat</b>. Keep the .bat file beside the complete <b>app-files</b> folder. Review the startup and application logs in <code>%LOCALAPPDATA%\\DigitalMarathon\\logs</code>. The bundled launcher does not need a Java installation, build or download.</p>"
                + "<h2>Linux Wayland</h2><p>Wayland restricts global input. Follow LINUX-WAYLAND.md or QUICK_START.txt to grant the required <code>/dev/input/event*</code> access using the included udev rule or the distribution's input group.</p>"
                + "<h2>Linux: Build or launch problem</h2><p>Review <code>app-files/digital-marathon-startup.log</code>. Linux retains its original launcher and builds the application on first launch.</p>"
                + "<h2>Current launch instructions</h2><p>The top-level <b>QUICK_START.txt</b>, README and version 2.1.28 PDF product guide describe the same shared folder and click-to-launch Mac and Windows files.</p>"
                + "<h2>Mouse distance seems inaccurate</h2><p>Verify Display PPI. Metric/imperial values estimate screen cursor travel; pointer acceleration, scaling and multiple displays can change the relationship to physical desk movement.</p>";
    }

    private static String aboutHtml() {
        return "<p><b>Digital Marathon</b><br><span class='version'>Version " + VERSION + "</span></p>"
                + "<p>A cross-platform desktop utility for macOS, Windows and Linux that turns mouse travel, keyboard presses, clicks and active time into live totals and long-term trends.</p>"
                + "<h2>Designed for</h2><ul><li>Personal activity awareness</li><li>Ergonomic habit reviews</li><li>Work-pattern comparison</li><li>Long-term digital activity curiosity</li></ul>"
                + "<h2>Updates</h2><p>Checks run at startup and every 15 minutes while the app is open. Download verified updates while continuing your session. Extract into a new folder; restart when ready to load the new version. Your local history stays in place.</p>"
                + "<h2>Basic usage reporting</h2><p>App version, platform, sessions, feature use and update outcomes are reported automatically. Activity totals, typed text, certificate names, file paths and history stay local. No session recording or person profiles are created.</p>"
                + "<p class='note'>Created by Girish Gupta. Copyright 2026. The shared package includes the complete product guide, quick start, release notes, platform help, source, resources and applicable open-source license texts.</p>";
    }

    private JButton actionButton(String label, boolean emphasized) {
        JButton button = new JButton(label);
        button.setUI(new AppleHelpButtonUI(emphasized));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setFont(button.getFont().deriveFont(emphasized ? Font.BOLD : Font.PLAIN, 12f));
        button.setForeground(emphasized ? Color.WHITE : text);
        button.setBorder(new EmptyBorder(8, 14, 8, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private final class AppleHelpButtonUI extends BasicButtonUI {
        private final boolean emphasized;
        private AppleHelpButtonUI(boolean emphasized) { this.emphasized = emphasized; }

        @Override public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = emphasized ? accent : (dark ? new Color(255,255,255,20) : new Color(255,255,255,190));
                if (model.isRollover()) fill = emphasized ? accent.brighter() : (dark ? new Color(255,255,255,30) : Color.WHITE);
                if (model.isPressed()) fill = emphasized ? accent.darker() : (dark ? new Color(255,255,255,38) : new Color(232,232,237));
                g.setColor(fill);
                g.fillRoundRect(0, 0, component.getWidth()-1, component.getHeight()-1, 14, 14);
                g.setColor(emphasized ? new Color(255,255,255,95) : border);
                g.drawRoundRect(0, 0, component.getWidth()-1, component.getHeight()-1, 14, 14);
            } finally { g.dispose(); }
            super.paint(graphics, component);
        }
    }

    private final class AppleSidebarRenderer extends JLabel implements ListCellRenderer<String> {
        AppleSidebarRenderer() {
            setOpaque(false);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setFont(getFont().deriveFont(Font.BOLD, 12.5f));
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                                  int index, boolean selected, boolean cellHasFocus) {
            setText(value);
            setForeground(selected ? accent : text);
            putClientProperty("selected", selected);
            return this;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean selected = Boolean.TRUE.equals(getClientProperty("selected"));
                if (selected) {
                    g.setColor(selection);
                    g.fillRoundRect(3, 4, getWidth()-6, getHeight()-8, 12, 12);
                }
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    }

    private Image loadHeaderIcon() {
        try (InputStream stream = HelpCenterDialog.class.getResourceAsStream("/header-icon.png")) {
            if (stream != null) return javax.imageio.ImageIO.read(stream);
        } catch (IOException ignored) {}
        return new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    private void openGuide() {
        try (InputStream stream = HelpCenterDialog.class.getResourceAsStream("/Digital-Marathon-Product-Guide.pdf")) {
            if (stream == null) throw new IOException("The embedded guide was not found.");
            Path guideDirectory = AppPaths.dataDirectory().resolve("guide");
            Files.createDirectories(guideDirectory);
            Path target = guideDirectory.resolve("Digital-Marathon-Product-Guide-v" + VERSION + ".pdf");
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            if (!Desktop.isDesktopSupported()) throw new IOException("Opening files is not supported on this desktop.");
            Desktop.getDesktop().open(target.toFile());
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this,
                    "Could not open the product guide:\n" + error.getMessage(),
                    "Guide unavailable", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void openEmail() {
        String subject = "Digital Marathon " + VERSION + " feedback";
        String uri = "mailto:" + EMAIL + "?subject=" + subject.replace(" ", "%20");
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
                throw new IOException("No default email application is configured.");
            }
            Desktop.getDesktop().mail(URI.create(uri));
        } catch (Exception error) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(EMAIL), null);
            JOptionPane.showMessageDialog(this,
                    "The email address has been copied to the clipboard:\n" + EMAIL,
                    "Contact Girish Gupta", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void openUri(String uri) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(uri));
        } catch (Exception ignored) {
            // Hyperlinks are supplementary; the complete guide remains available locally.
        }
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
