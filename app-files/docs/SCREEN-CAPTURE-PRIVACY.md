# Screen Capture Privacy - Digital Marathon 2.1.29

Digital Marathon's Full View includes the **Hide from screenshots & sharing** toggle. Both Full View and Mini View show the same small shield status indicator in the top toolbar, next to the slim **0-75% transparency bar with its rounded tab handle**. The bar and surrounding controls keep their existing sizes. Mini View has no privacy toggle; change the setting in Full View. A checked/accented shield means protection is currently applied; the neutral shield means it is off or could not be applied. The setting is off by default.

The shield reflects the operating system's applied result. Its tooltip explains the state without covering the live counters. Transparency is a separate appearance setting and does not imply capture protection.

The Full View control's outer surface and border stay neutral in both states. Only its switch track and knob change to show on or off; enabling protection does not turn the whole control blue.

The nearby transparency tab uses a slightly deeper soft tint in both appearances without a halo or changes to its geometry. The Pearl & Aqua brand uses modestly darker color and improved small-icon rendering for clearer detail on your display in the Mac app icon and the running app's Dock/taskbar icon, plus Full View, certificates, Help and the PDF guide. This is separate from the shield, which still indicates capture status.

## Platform behavior

- **macOS:** uses AppKit window sharing (`NSWindowSharingNone` while protected, standard read-only sharing when disabled).
- **Windows 10/11:** uses `SetWindowDisplayAffinity` with `WDA_EXCLUDEFROMCAPTURE` while protected and `WDA_NONE` when disabled. The intended exclusion behavior is available on Windows 10 version 2004 and later.
- **Linux / KDE Plasma 6.7+:** installs/updates a dedicated KWin window rule using `excludefromcapture=true`, then asks KWin to reload its rules. The app uses a stable X11/XWayland WM_CLASS so the rule can match Digital Marathon consistently.
- **Other Linux desktops:** GNOME, COSMIC, wlroots-based desktops and generic X11/Wayland do not currently expose one portable application-controlled per-window screenshot/screencast exclusion API. Digital Marathon therefore leaves the toggle off and explains the limitation rather than showing a false protected state.

## Important limitation

Capture-exclusion APIs are privacy aids, not DRM. Their behavior depends on the operating system, compositor, and capture application. They do not stop an external camera from photographing the screen, and some privileged or nonstandard capture paths may bypass normal exclusion mechanisms.
