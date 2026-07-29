# Remote desktop (xrdp) into the Ubuntu board

How to get a working RDP desktop on the QCS8550/Ubuntu 22.04 board (see
[UBUNTU-BOARD.md](UBUNTU-BOARD.md)) so you can drive it from a Windows
machine's Remote Desktop Connection instead of an adb shell. This is
independent of the `app-rn` deploy path — it's for interactive debugging
(browsing docs, eyeballing X11 output, running GUI tools) on the board itself.

## 1. Prerequisite: the board needs a real network interface

UBUNTU-BOARD.md §5 says "the board has no network interface" — that was true
for the specific unit/image this doc's UBUNTU-BOARD.md was written against,
but is **not universal**: a board on this same image can come up with a real
`eth0` on the lab LAN (DHCP, same subnet as your workstation), with full
internet egress. Check before assuming either way:

```bash
adb shell "ip addr show eth0"
```

If it shows an `inet` address, RDP can go straight over that LAN IP — no
`adb forward` tunneling needed, unlike the browser/search-relay tunnels
`deploy-linux.sh` sets up. If it only shows loopback, you're on the
no-network variant and would need a different transport (out of scope here).

Also confirm nothing is blocking the port on the board:

```bash
adb shell "which ufw; which iptables"   # neither present on the tested image
```

## 2. Install and start xrdp

```bash
adb shell "apt-get update && apt-get install -y xrdp xorgxrdp"
adb shell "systemctl enable --now xrdp"
adb shell "systemctl is-active xrdp; ss -tlnp | grep 3389"
```

`xrdp` is systemd-managed and enabled by default on install, so it survives a
reboot. It listens on `0.0.0.0:3389` (`security_layer=negotiate` in
`/etc/xrdp/xrdp.ini`) with no further config needed for a LAN-only setup.

## 3. Set a login

Check what accounts are actually usable — a locked account (`passwd -S`
reports `L`) can't authenticate over RDP even if it exists:

```bash
adb shell "passwd -S root"      # P = has a password; L = locked
```

Since you already have root over adb, you don't need the old password to set
a new one:

```bash
adb shell "echo 'root:<new-password>' | chpasswd"
```

**Do not commit a real password into this repo or any doc.** Pick something
you'll remember, or generate one and store it in a password manager. This
board is reachable to everything else on that LAN once xrdp is up, not just
your machine — treat the credential accordingly.

PAM's `xrdp-sesman` service (`/etc/pam.d/xrdp-sesman`) has no `pam_securetty`
restriction on the tested image, so root login over RDP is not blocked at the
PAM layer the way console/`su` root login sometimes is.

## 4. Install a desktop environment

The stock image ships **no window manager at all** — only `gnome-terminal`
and some GNOME libraries, no `xfwm4`/`gnome-session`/etc. Without one, xrdp
connects but you get a blank/gray screen. Install one and point the RDP
user's session at it:

```bash
adb shell "DEBIAN_FRONTEND=noninteractive apt-get install -y xfce4"
adb shell "echo 'startxfce4' > /root/.xsession"
adb shell "systemctl restart xrdp"
```

(`/root/.xsession` is read by `/etc/xrdp/startwm.sh` via `/etc/X11/Xsession`;
if you set up a non-root RDP login instead, put it in that user's home
directory.)

At this point RDP from Windows (`mstsc` → `<board-ip>` → the login from §3)
gets you a real XFCE desktop.

## 5. Two apps that don't work out of the box, and why

Getting a desktop up is not the same as getting a *usable* one — two default
apps fail silently/confusingly on this image, both for reasons specific to
running a minimal image as root:

### Terminal: `gnome-terminal` crashes (exit status 8)

`gnome-terminal` is preinstalled, but launching it from the XFCE panel/menu
fails with, in `/root/.xsession-errors`:

```
Error calling StartServiceByName for org.gnome.Terminal: Process org.gnome.Terminal exited with status 8
```

The dbus-activated `gnome-terminal-server` path assumes a fuller GNOME
session than this image provides. Fix: install `xfce4-terminal` (a plain
process, no dbus activation) and make it the default:

```bash
adb shell "DEBIAN_FRONTEND=noninteractive apt-get install -y xfce4-terminal"
adb shell "update-alternatives --set x-terminal-emulator /usr/bin/xfce4-terminal.wrapper"
```

### Web browser: WebKitGTK browsers (epiphany, etc.) crash

There's no browser installed at all initially (clicking the panel's browser
icon gives "Failed to execute default Web Browser. Input/output error" /
"Couldn't find a suitable web browser!" in the session log). Installing
`epiphany-browser` doesn't fix it — its `WebKitWebProcess` crashes with:

```
/usr/lib/aarch64-linux-gnu/webkit2gtk-4.1/WebKitWebProcess: error while loading shared libraries: liblog.so.0: cannot open shared object file: No such file or directory
```

`liblog.so.0` genuinely exists and resolves fine via `ldconfig`/`ldd` outside
a sandbox — the failure is inside WebKitGTK's `bwrap` (bubblewrap) sandbox,
whose private mount namespace doesn't see it. Root cause: this board's kernel
doesn't support unprivileged user namespaces, which `bwrap` needs:

```bash
adb shell "unshare --user --pid echo works"   # "unshare: unshare failed: Invalid argument"
```

`WEBKIT_DISABLE_SANDBOX=1` does **not** work around this — the crash is
identical with or without it. Any WebKitGTK-based browser (and by the same
logic, Electron/Chromium-sandboxed apps) will hit this same wall on this
board. The fix is to use a browser that doesn't sandbox itself:

```bash
adb shell "DEBIAN_FRONTEND=noninteractive apt-get install -y netsurf-gtk"
```

`netsurf-gtk` isn't in XFCE's built-in list of known browsers
(`/usr/share/xfce4/helpers/*.desktop`), so register it as one and set it as
the default so the panel icon and `exo-open`-based launches pick it up:

```bash
adb shell "cat > /usr/share/xfce4/helpers/netsurf-gtk.desktop << 'EOF'
[Desktop Entry]
Version=1.0
Icon=netsurf
Type=X-XFCE-Helper
Name=NetSurf
StartupNotify=true
X-XFCE-Binaries=netsurf-gtk;
X-XFCE-Category=WebBrowser
X-XFCE-Commands=%B;
X-XFCE-CommandsWithParameter=%B \"%s\";
EOF"
adb shell "printf 'TerminalEmulator=xfce4-terminal\nWebBrowser=netsurf-gtk\nFileManager=thunar\n' > /root/.config/xfce4/helpers.rc"
```

(`thunar`, XFCE's file manager, was already installed by the `xfce4`
metapackage and needs no fix.)

## 6. Connecting from Windows

```
mstsc → Computer: <board-ip-from-§1>
        Username: root (or whichever account you set up in §3)
        Password: <whatever you set in §3>
```

## 7. Known gaps

- **DHCP IP.** If the board gets its address via DHCP, it can change across
  reboots. Re-check with `adb shell "ip addr show eth0"` if RDP stops
  connecting.
- **No Electron/Chromium-sandboxed apps.** Same root cause as §5's browser
  issue — anything relying on a user-namespace sandbox will fail the same
  way on this board's kernel. `netsurf-gtk` is a workaround for browsing
  specifically, not a general fix.
- **Root login for RDP is a convenience choice for a lab/dev board**, not a
  hardened setup — the board is exposed to the whole LAN segment it's on.
  For anything more persistent or less trusted, use a dedicated non-root
  account instead of root.
