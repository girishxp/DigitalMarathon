# Digital Marathon 2.1.28

## Automatic basic usage analytics and simpler Help

- Starts basic usage analytics automatically at launch, including existing installations. Removes the first-run choice, analytics toggle and Updates & Privacy section. Manual Check for updates is in Help > About. Restores automatic update checks if an older preference disabled them; no update-check switch is shown.
- Keeps the shared PulseStudio PostHog project, separate Digital Marathon event names and app filters. PulseStudio's dashboard and project settings remain unchanged. Activity/input totals, typed text, certificate names, paths, ranges, history and exports stay local.
- Keeps update downloads and verification in the background while tracking and the current session continue. The verified-package notice makes clear that users can install later and restart only to activate the new version; users may extract into a new folder while the app continues and restart when ready to run the new launcher.
- Documents the Mac Publish Digital Marathon.command and Windows Publish Digital Marathon - Windows.bat owner tools. Either publishes the same combined Latest release; a .command file does not run on Windows, and the PulseStudio publisher belongs to PulseStudio.
- Updates current Help, README, Quick Start, publishing/analytics notes, platform notes, license notice and product guide to 2.1.28. Preserves the approved artwork, transparency controls, Mini View body, certificate, tracking and numerical-history behavior.

---

# Digital Marathon 2.1.27

## GitHub updates and optional anonymous usage

- Checks GitHub at startup and every 15 minutes while the app is running, with a manual Check for Updates action and remembered automatic-check preference.
- Offers Download Update, a 24-hour reminder or Skip This Version; verifies the downloaded ZIP against its mandatory release SHA-256 checksum. Installation remains a manual quit, extract and relaunch step.
- Adds Help > Updates & Privacy and a first-run explanation for opt-in anonymous usage, off by default. The existing PulseStudio PostHog project is configured with separate Digital Marathon insight filters and dashboard; activity/input totals, names, paths, ranges, history and exports are excluded.
- Adds owner-operated Mac/Windows publishers with dry-run, source/package/version/origin guards and draft upload verification before publication as Latest.
- Documents GitHub aggregate download counts and the configured shared PostHog project with app_id digital-marathon and digital_marathon_ event names, without new project, billing plan or card setup. The existing PulseStudio dashboard stays unchanged. Digital Marathon events disable GeoIP enrichment and person profiles, with no autocapture or session recording. Updates the product guide and metadata to 2.1.27.
- Preserves the approved Dock artwork, all transparency controls, the Mini View body, certificate layout and local tracking/history behavior.

---

# Digital Marathon 2.1.26

## Larger macOS Dock icon

- Gives the Pearl & Aqua artwork a slightly larger visible footprint in the macOS Dock and Finder.
- Keeps the approved artwork, in-app and certificate icons, Help/PDF icon sizes, transparency controls and tracking behavior unchanged.
- Updates current version metadata and documentation to 2.1.26.

---

# Digital Marathon 2.1.25

## Icon clarity and certificate export

- Refines **Pearl & Aqua** with modestly darker color for the Mac app icon and the running app's Dock/taskbar icon, plus Full View, certificates, Help and the PDF guide.
- Improves small-icon filtering at the display's actual pixel resolution while keeping the existing icon sizes.
- Saves certificate JPEGs at explicit **97% quality** with **full-color 4:4:4 sampling**, retaining finer detail and colored edges than the previous default 75% quality and 4:2:0 sampling.
- Updates current Help, documentation, version metadata and the ten-page PDF guide to 2.1.25. The Classic Award layout, tracking, saved history and approved controls are preserved.

---

# Digital Marathon 2.1.24

## Icon and tab clarity

- Refines **Pearl & Aqua** with deeper color and cleaner detail for clearer small-size display in the Mac app icon, running app's Dock/taskbar icon, Full View, certificates, Help and PDF guide.
- Keeps the existing Full View and Help header icon sizes, with sharper rendering on Retina and other high-resolution displays.
- Gives both rounded transparency tabs slightly deeper tints: `#DDE8F2` in light appearance and `#CDDCE9` in dark appearance. Original bar, tab and control dimensions remain unchanged, with no halo.
- Preserves smooth dragging, keyboard adjustment, existing tracking/history behavior and the Classic Award certificate layout.
- Updates current Help, documentation, version metadata and the ten-page PDF guide to 2.1.24.

---

# Digital Marathon 2.1.23

## Brand and tab appearance

- Applies the approved **Pearl & Aqua** brand to the Mac app icon and the running app's Dock/taskbar icon, plus Full View, certificate report and JPEG, Help and PDF guide.
- Uses a very light rounded-tab fill in both views: `#EEF3F8` in light appearance and `#E2E9F0` in dark appearance. Existing bar, tab and surrounding control geometry is preserved, with no halo.
- Retains smooth transparency dragging, the synchronized 0-75% range and keyboard arrow-key adjustment.
- Updates current Help, README, Quick Start, platform notes, notices, version metadata and the ten-page PDF guide to 2.1.23.

## Preserved behavior

The Mini View body, neutral tracking/capture controls, real chart interval details, compact themed dialogs and Classic Award certificate layout remain unchanged. The gold laptop award badge is retained. Input counting, saved history and the shared Mac/Windows launch folder are preserved.

---

# Digital Marathon 2.1.22

## Transparency handle refinement

- Uses the approved rounded tab handle on the transparency bar in both Full View and Mini View.
- Keeps the existing bar and surrounding control sizes, the 0-75% range, smooth dragging and synchronized transparency between views.
- Removes the blue focus circle while retaining keyboard arrow-key adjustment.
- Updates current Help, README, Quick Start, platform notes, version metadata and the ten-page PDF guide to 2.1.22.

## Preserved behavior

Retains the neutral tracking/capture controls, point-only chart highlight and real interval tooltip values from 2.1.21. The compact themed dialogs, Classic Award certificate, Mini View body, shared Mac/Windows launch folder, input counting and saved history remain unchanged.

---

# Digital Marathon 2.1.21

## Interface refinements

- Keeps the outer **Hide from screenshots & sharing** control neutral in both states. Only the switch track and knob change; enabling it does not add a blue background or border.
- Keeps **Pause/Resume Tracking** neutral when running, paused, hovered or pressed. Only its text and icon change with tracking state.
- Removes the chart's shaded interval band and hover styling on bars. Only the selected point on the trend line highlights.
- Shows the closest real interval's original date/time, mouse distance, key presses, mouse clicks, active time and relative activity when hovering or clicking anywhere along the trend line. The tooltip never invents intermediate values.
- Updates Help & About, current documentation, Mac version metadata and the ten-page PDF product guide to 2.1.21.

## Preserved behavior

Retains the 2.1.20 transparency-dragging fix, compact themed save and permissions windows, and Classic Award certificate. The Mini View body, shared click-to-launch Mac/Windows folder, input counting and saved numerical history remain compatible.

---

# Digital Marathon 2.1.20

## Interface corrections

- Prevents macOS window movement while dragging either transparency bar. Clear header space and the Mini View range strip still move the window.
- Removes the rectangular slider focus border. Keyboard focus highlights the handle, while clicks and drags change values continuously from 0% to 75%.
- Restores Activity trend details for hovering or clicking an interval: date/time, mouse distance, key presses, mouse clicks, active time and relative activity. The opaque detail card stays visible during live refreshes.
- Uses the newly requested **Hide from screenshots & sharing** toggle in Full View. Capture-status shields remain in both top toolbars.
- Replaces the oversized certificate-download message with a compact themed **Certificate saved** notice. It shows the filename and folder with **Open Certificate**, **Show in Folder**, **Copy file location** and **Done** actions.
- Replaces the oversized Permissions help message with a compact themed window and short setup steps, plus Input Monitoring and Accessibility shortcuts on macOS.
- Updates Help & About, README, Quick Start, platform notes and the ten-page PDF guide to version 2.1.20.

## Compatibility

The Classic Award report and JPEG retain the improved layout and original gold laptop badge. The Mini View body, shared Mac/Windows launch folder, input counting, saved history, ranges, units and exports are preserved. Clear History still requires confirmation.

---

# Digital Marathon 2.1.19

## Corrections

- Fixes transparency-bar dragging in Full and Mini View. Dragging changes the transparency value without moving the window; both bars remain synchronized from 0% to 75%.
- Full View uses the requested **Hide from screenshots & sharing** checkbox, with the top shield retaining its applied-state indicator.
- Removes the app icon from the Mini View toolbar. The existing Mini View body, range row and green active timer remain unchanged.
- Applies the selected **Classic Award** certificate design to both the in-app report and downloaded JPEG. The original gold laptop award badge stays at the top right.
- Refreshes Help & About, product guide, Quick Start, README and platform notes for this release.

## Compatibility

The shared Mac/Windows launch folder, input counting, saved history, ranges, units, exports and permissions remain compatible. Historical numerical data is preserved; Clear History still requires confirmation.

---

# Digital Marathon 2.1.18

## Interface

- Classic Mac Full View with the Digital Marathon icon, colored activity cards, clear chart legend and visible certificate button.
- Preserves all ranges, distance units, Display PPI, custom date/time fields, Permissions, interval breakdown, Pause/Resume Tracking, Reset Session, Export CSV and Clear History.
- Preserves the existing Mini View body, including its Mouse/Clicks card, Keys card, range row and green active timer. Only the top toolbar is refreshed.
- Shared slim transparency bars support every value from 0% to 75%. Appearance and transparency stay in sync between Full and Mini View.
- Short opaque tooltips appear away from window content so hints do not cover the Mini View counters.
- Capture-status shields remain visible in both top toolbars; the privacy setting stays in Full View.
- Refreshed certificate layout with the original gold laptop award badge at the top right. The report keeps its totals, activity meter, achievements and finish-line verdict.

## Launch and documentation

- One ZIP extracts to one `digital-marathon` folder containing `Digital Marathon.app` for Apple Silicon Mac and `Start Digital Marathon - Windows.bat` for Windows x64.
- Both Mac and Windows include their Java runtimes and launch without a terminal, Java installation, first-launch build or download.
- Linux retains the existing launcher and first-launch build process.
- Version 2.1.18 Help & About, README, Quick Start, platform help and PDF guide describe the same current controls and launch instructions.

## Data compatibility

Saved numerical history stays in the existing per-user data location. Updating or moving the app does not erase it. Reset Session preserves history; Clear History still requires confirmation. The app continues to store totals only, never typed text or individual key identities.
