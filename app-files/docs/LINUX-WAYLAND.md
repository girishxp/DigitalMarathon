# Linux Wayland input access - Digital Marathon 2.1.27

Wayland intentionally prevents ordinary applications from observing global keyboard and pointer events. Digital Marathon supports Wayland by reading Linux `evdev` events directly. It records only numerical totals; key identities are kept in memory only long enough to avoid counting a held key repeatedly.

Linux keeps the existing **`Start Screen Recorder - Linux.sh`** launcher and builds on first launch. The direct Mac `.app` and Windows `.bat` files in the same package do not replace the Linux launcher. Use the status line and compact themed **Permissions help** window in Full View to check input access. These UI changes do not grant device access automatically.

## Check the session type

```bash
echo "$XDG_SESSION_TYPE"
```

If it prints `wayland`, check whether the active user can read input event devices:

```bash
ls -l /dev/input/event*
```

## Option A: active-user udev rule

This repository includes `app-files/scripts/99-digital-marathon.rules`:

```bash
sudo cp app-files/scripts/99-digital-marathon.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger
```

Sign out and sign in again. Some distributions require a restart.

## Option B: input group

On distributions that use an `input` group:

```bash
sudo usermod -aG input "$USER"
```

Sign out and sign in again.

## Security note

Access to `/dev/input/event*` can allow software to observe keyboard and pointing-device activity. Grant it only to software and local users you trust. The included application stores totals only, but the operating-system permission itself is broad.

## Troubleshooting

Run the tracker from a terminal and inspect `app-files/digital-marathon-startup.log`. The status line in Full View reports whether Linux/X11 or Linux/Wayland/evdev monitoring is active.
