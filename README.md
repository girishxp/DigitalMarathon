# Digital Marathon 2.1.28

Digital Marathon is a privacy-first desktop activity utility. It tracks numerical mouse-distance, keyboard-press, mouse-click, and active-time totals without storing what you type.

The **Pearl & Aqua** brand icon uses modestly darker color and improved rendering for clearer small icons on your display. It is used for the Mac app icon and the running app's Dock/taskbar icon, plus Full View, certificates, Help and the PDF guide.

Full View and Help keep their existing header icon sizes. Small icons are filtered at the display's actual pixel resolution for clearer detail on Retina and other displays.

Version 2.1.28 starts basic usage analytics automatically and simplifies Help, with manual update checks in About. Verified updates download in the background while the current session continues; restarting is required only to run the new version. The approved larger macOS Dock icon, in-app artwork, certificate layout and transparency bars remain the same.

## Start here

On GitHub, download **digital-marathon-cross-platform-v2.1.28-click-to-launch.zip** from the **Release assets** at [Digital Marathon Releases](https://github.com/girishxp/DigitalMarathon/releases/latest). GitHub's automatic **Source code (zip/tar.gz)** archives contain sanitized source and lack the runtimes/JAR needed for direct launch. The public release becomes available only after owner publication.

Extract the ZIP completely, open the **`digital-marathon`** folder, and double-click the launcher for your computer:

| Computer | File to open |
|---|---|
| Apple Silicon Mac, macOS 11 or later | `Digital Marathon.app` |
| Windows 10/11, x64 | `Start Digital Marathon - Windows.bat` |
| Linux | `Start Screen Recorder - Linux.sh` |

The Mac app and Windows launcher are together in this **one folder**. Both include their Java runtime and launch immediately without installing Java, building the app, using a terminal, or downloading anything. Linux retains the original build-on-first-launch launcher and may download its build tools.

On Windows, keep the entire extracted folder together; the `.bat` launcher needs `app-files/`. On Mac, you may launch from this folder or copy **`Digital Marathon.app`** to Applications. The Mac app is self-contained.

This package supports **Apple Silicon Macs and x64 Windows PCs**. It does not include an Intel Mac app or a native Windows ARM runtime.

For simple first-launch and troubleshooting steps, read **`QUICK_START.txt`**.

## Folder layout

```text
digital-marathon/
|-- Digital Marathon.app
|-- Start Digital Marathon - Windows.bat
|-- Start Screen Recorder - Linux.sh
|-- REPAIR_MAC_PERMISSIONS.command
|-- Publish Digital Marathon.command
|-- Publish Digital Marathon - Windows.bat
|-- ANALYTICS.md
|-- PUBLISHING.md
|-- README.md
|-- QUICK_START.txt
`-- app-files/                      Windows runtime, source, docs and support files
```

The updated product guide is at **`app-files/docs/Digital-Marathon-Product-Guide.pdf`**. It covers version 2.1.28, the shared launch folder, controls, certificate, privacy and troubleshooting. Release changes are listed in **`CHANGELOG.md`**.

## Updates and basic usage analytics

Open **Help > About** and choose **Check for updates** for a manual check. Automatic checks run at startup and every **15 minutes while the app is open**. There are no closed-app notifications. When a newer release is available, choose **Download Update**, **Remind Me Later** for 24 hours, or **Skip This Version**. A manual check can still show a skipped version.

The download runs in the background while tracking and the current session continue. The release ZIP is accepted only when its **SHA-256 checksum** matches. After download, you may continue using the current version and install later. Extract the verified package into a new folder while the current app continues. When ready, quit the current app and open its new Mac `.app` or Windows `.bat`. A restart is needed to activate the new version; the running Java application and native input components are not replaced in place. Your saved numerical history stays outside the launch folder. Offline use and local tracking still work.

Basic usage analytics starts automatically when the app launches, including installations with an older saved opt-out preference. There is no first-run choice or analytics toggle in Help. This release uses the **existing PulseStudio PostHog project**. Its events start with `digital_marathon_`, and its insights and [Digital Marathon dashboard](https://us.posthog.com/project/580438/dashboard/2181978) save `app_id = digital-marathon` filters. The existing PulseStudio dashboard stays unchanged. No new project, billing plan or card setup is required.

Usage events contain basic app/runtime details, random installation/session IDs, launch/session timing and limited state, feature, update and error categories. App-session timing is separate from the tracked Active time counter. Source IP exists in ordinary network transit; the app does not derive location and requests no GeoIP enrichment, person profiles, autocapture or session recording. Input counts, activity totals, typed text, certificate names, file paths, selected ranges, history and exports are never transmitted. GitHub provides aggregate release-download counts. Read **ANALYTICS.md** for the exact fields and reporting limits.

For this app, use **Publish Digital Marathon.command** on Mac or **Publish Digital Marathon - Windows.bat** on Windows. A `.command` file runs on Mac, not Windows; **Publish PulseStudio.command** belongs to PulseStudio. Both Digital Marathon publishers validate a sanitized source snapshot and exact release ZIP, upload a draft, verify both assets, then publish the same repository's Latest release. Git and GitHub CLI are required only for publishing. Publishing a newer Release as Latest makes update prompts available in running Digital Marathon 2.1.27 or later with automatic checks enabled. Pushing source commits alone does not. Read **PUBLISHING.md** before using the owner tools.

## macOS first launch

Open **`Digital Marathon.app`**. If macOS blocks it, try right-clicking the app and choosing **Open**, or use **System Settings > Privacy & Security > Open Anyway** after the blocked attempt.

Enable **Digital Marathon** in **System Settings > Privacy & Security > Input Monitoring** when requested. Accessibility may also be requested as a fallback. The tracker retries automatically after permission is enabled; quit and reopen it if macOS asks.

The **Permissions help** control opens a compact window in the current theme, with short setup steps and **Open Input Monitoring** and **Open Accessibility** shortcuts. Detailed recovery steps remain in Help > Troubleshooting.

The Mac app has a local ad-hoc signature and is not notarized by Apple, so macOS may show a first-open security prompt. It does not need Apple Command Line Tools.

If counting still fails after permissions appear enabled, run **`REPAIR_MAC_PERMISSIONS.command`**, re-enable Digital Marathon in Input Monitoring/Accessibility, and reopen the app. If you moved the app to Applications, reopen it from there after the repair.

## Windows first launch

Open **`Start Digital Marathon - Windows.bat`** after extracting the complete folder. Keep the launcher beside `app-files/` for later launches as well.

Microsoft Defender SmartScreen or endpoint-security software may prompt. For a package you trust, use **More info > Run anyway** when available.

## Using the app

### Classic Mac Full View

The refreshed dashboard keeps the Digital Marathon icon, monitoring status, capture-status shield and all existing controls. Colored cards distinguish **Mouse distance in blue**, **Key presses in green**, **Mouse clicks in amber** and **Active time in violet**.

The range menu includes Current session; Last 1, 2, 6 or 12 hours; Today; Yesterday; This week; Last 7 or 30 days; Last 6 months; Last 1, 2 or 5 years; and Custom range. Select Custom range to show the From and To date/time fields. Distance units, Display PPI and Permissions remain available.

The Activity trend legend explains **Bars = activity** and **Line = trend**. Blue bars show relative activity per interval; the coral line follows the bar tops. Hover or click anywhere along the trend line to see the closest recorded interval's date/time, mouse distance, key presses, mouse clicks, active time and relative activity in an opaque detail card near that point. These are real interval totals; intermediate values are never invented. Only the line point highlights: bars keep their normal appearance and no shaded interval band is added. The detail stays visible during live updates while the interval is selected. The chart uses a relative 0-100% scale because mouse travel, keys and clicks use different units. The interval table keeps the underlying totals.

The four actions remain visible: **Pause/Resume Tracking**, **Reset Session**, **Export CSV** and **Clear History**. Reset Session zeros only the current live session and preserves saved history. Clear History requires confirmation before permanently deleting saved activity history. Export CSV uses the selected range.

**Pause/Resume Tracking** always keeps a neutral button surface and border, including hover and press. Its text and icon show whether tracking is running or paused.

### Remembered window view

Digital Marathon remembers how you last left the app. Close it in **Mini View** and the next launch starts in Mini View at its last screen position. Close it in **Full View** and the next launch restores Full View at its last position and size. Theme, transparency, distance unit and Display PPI are also remembered.

### Mini View and transparency

The Mini View body keeps its familiar layout: Mouse and Clicks share the soft-blue card, Keys stays in the soft-green card, and the bottom strip shows the selected range and green **Active** timer with hours, minutes and seconds. Only its top toolbar is refreshed: capture-status shield, slim transparency bar, appearance, Always on Top pin and Full View.

Full View and Mini View share a **0-75% transparency slider** with a **rounded tab handle**. Its slightly deeper soft tint makes the tab clearer in both light and dark appearance, with no halo. The compact bars, tab geometry and surrounding controls keep their existing sizes. Drag the tab or click the bar to choose a value; 0% is fully opaque and 75% is most transparent. Smooth dragging changes transparency without moving the window, and keyboard arrow-key adjustment remains available. Move the window using clear header space or Mini View's bottom range strip. Tooltips use short wording and an opaque surface, positioned away from the window content so they do not cover counters.

Active time is elapsed collection time while tracking runs. It does not measure attention, task completion or productivity.

### My Digital Marathon Certificate

The labeled **My Digital Marathon Certificate** button opens the report for the selected range. It keeps the four activity totals, marathon-distance comparison, playful activity meter, achievements and finish-line verdict. Enter **Name on Certificate** and choose **Download Certificate** to save a JPEG in Downloads. Each filename includes a timestamp. Certificates use higher-quality JPEG export at 97% quality with full-color 4:4:4 sampling to retain finer detail and colored edges. A compact **Certificate saved** notice shows the filename and folder in the current light or dark theme. Use **Open Certificate**, **Show in Folder**, or **Copy file location** for the complete saved path; **Done** closes the notice. File-opening actions depend on desktop support.

The report and downloaded JPEG follow the selected **Classic Award** design. The report uses a flat blue download button, four colored totals, a slim activity meter and clear achievement tiles. The JPEG uses a warm paper surface, fine gold double border and centered recipient name, with the original **gold laptop award badge at the top right**, colored totals, selected range and award attribution to Digital Marathon and Girish Gupta. Reports and certificates contain totals only. Their activity meter is for fun and is not a productivity score.

### Screen Capture Privacy

Full View includes a **Hide from screenshots & sharing** toggle. It is off by default. A small shield in the Full View and Mini View top toolbars shows whether protection is applied. When enabled, the app requests the capture exclusion supported by your operating system or desktop.

The control's outer surface and border stay neutral in both states. Only the switch track and knob change to show on or off; the whole control does not turn blue.

macOS uses AppKit window-sharing protection. Windows uses `WDA_EXCLUDEFROMCAPTURE`; Windows 10 version 2004 or later provides the intended exclusion behavior. KDE Plasma 6.7 or later uses KWin's per-window capture-exclusion rule. Other Linux desktops currently do not expose an equivalent cross-desktop API, and Digital Marathon reports that limitation.

### Sleep and lid-close resume

Digital Marathon automatically recovers its input monitor after sleep. It clears stale held-key and pointer state when the computer wakes. The macOS monitor is passive and rearms after wake; Windows and Linux/X11 re-register the global hook when needed. Linux Wayland reopens passive evdev readers if device nodes were recreated. Input callbacks hand tracker work to a dedicated worker, and the Linux readers do not grab input devices.

## Linux

Run **`Start Screen Recorder - Linux.sh`**. Linux retains the original first-launch build process; the first launch can take a few minutes and may require internet access. Later launches reuse the built app.

Wayland input-access instructions are in **`app-files/docs/LINUX-WAYLAND.md`**.

## Troubleshooting

- **Mac:** Check any error shown by the app. For input-counting issues, check Input Monitoring and Accessibility permissions or use the repair helper. This direct app launcher does not produce a separate startup log.
- **Windows:** Launch logs are in **`%LOCALAPPDATA%\DigitalMarathon\logs`**. If the launcher reports missing files, extract the complete ZIP again and keep the folder together.
- **Linux:** The original startup/build log is **`app-files/digital-marathon-startup.log`**. The built app is at **`app-files/build/linux/Digital Marathon`**.

## Privacy and saved history

Digital Marathon stores numerical totals only. It does **not** store typed text, individual key identities, clipboard contents, screenshots, application names, or window titles.

Local activity data remains on the computer:

- macOS/Linux: `~/.digital_marathon/activity-buckets.dat`
- Windows: `%APPDATA%\DigitalMarathon\activity-buckets.dat`

Moving the launch folder or Mac app does not move or erase this saved history.

## Supporting files and runtimes

### Build the sanitized source (developers)

GitHub source archives are for developers; ordinary users should choose the combined Release ZIP. Install a **Java 21 JDK**, then set `JAVA_HOME` or put its `javac` and `jar` on `PATH`. Compile the sanitized source with:

```bash
# macOS/Linux
bash app-files/scripts/compile-source.sh
```

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File app-files/scripts/compile-source.ps1
```

These scripts fetch only a missing pinned **JNativeHook 2.2.2** dependency from official Maven Central, compile source/resources/PDF and write `app-files/app/digital-marathon.jar`. They do not launch the application or modify the Mac bundle. Existing platform build scripts can then consume this JAR; a Mac native build also needs Apple Command Line Tools. Source compilation does not add the runtimes missing from GitHub source archives.

Application source, native source, icons, the updated product guide, platform help, and provided license texts are included under `app-files/`. Help & About documents the version 2.1.28 controls and launch guidance. The bundled Java 21 runtimes allow direct Mac and Windows launch. Windows uses Java 21 because its capture-privacy component uses that version's preview API; use the supplied launcher and runtime together. Runtime license notices are included with the runtimes.

The package remains a single ZIP that extracts into `digital-marathon/`, with both the Mac `.app` and Windows `.bat` at the top level.
