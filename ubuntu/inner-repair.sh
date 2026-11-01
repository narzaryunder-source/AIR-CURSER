#!/bin/bash
# Runs inside Ubuntu as root (used by setup.sh and fix.sh).
#   inner-repair.sh <user> <reset-desktop-settings:0|1>
U="$1"
RESET="${2:-0}"
H="/home/$U"
export DEBIAN_FRONTEND=noninteractive

dpkg --configure -a >/dev/null 2>&1

# Packages the desktop needs (installs only what is missing)
need=""
# libgl1 / libglx-mesa0 / libgl1-mesa-dri: the window manager aborts without libGL ("Couldn't open libGL.so.1")
for p in procps librsvg2-common dbus-x11 libgl1 libglx-mesa0 libgl1-mesa-dri libegl1 \
         xfwm4 xfce4-panel xfdesktop4 xfce4-session xfce4-terminal thunar; do
    dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"
done
if [ -n "$need" ]; then
    echo "Installing missing parts:$need"
    apt-get update >/dev/null 2>&1
    apt-get install -y --no-install-recommends $need
fi

# Windows-like extras (each optional): GUI dialogs for the App Store, Start menu, dark theme
for p in zenity xfce4-whiskermenu-plugin greybird-gtk-theme; do
    dpkg -s "$p" >/dev/null 2>&1 || apt-get install -y --no-install-recommends "$p" >/dev/null 2>&1 \
        || echo "  (optional $p not installed)"
done

# The default XFCE wallpaper on Ubuntu points to a file that is not installed: use an XFCE one
mkdir -p /usr/share/xfce4/backdrops
if [ ! -e /usr/share/xfce4/backdrops/xubuntu-wallpaper.png ]; then
    src=$(ls /usr/share/backgrounds/xfce/* 2>/dev/null | head -n 1)
    [ -n "$src" ] && ln -sf "$src" /usr/share/xfce4/backdrops/xubuntu-wallpaper.png
fi

# Less RAM/CPU: no window animations/shadows (Android kills Termux less often)
[ -f /usr/share/xfwm4/defaults ] && sed -i 's/^use_compositing=true/use_compositing=false/' /usr/share/xfwm4/defaults

# Self-repair: after Android kills Termux, the window manager / panel sometimes do not start.
cat > /usr/local/bin/aircursor-heal <<'HEAL'
#!/bin/sh
# AirBoost light mode: stop background jobs a phone does not need (thumbnails, power manager, screensaver)
if [ "$AIRCURSOR_LIGHT" = "1" ]; then
    (sleep 20; pkill -x tumblerd; pkill -x xfce4-power-manager; pkill -x xfce4-screensaver; pkill -x light-locker) &
fi
sleep 12
pgrep -x xfwm4 >/dev/null || (xfwm4 --replace >/tmp/aircursor-xfwm4.log 2>&1 &)
sleep 4
pgrep -x xfce4-panel >/dev/null || (xfce4-panel >/tmp/aircursor-panel.log 2>&1 &)
HEAL
chmod +x /usr/local/bin/aircursor-heal
mkdir -p /etc/xdg/autostart
cat > /etc/xdg/autostart/aircursor-heal.desktop <<'AUTO'
[Desktop Entry]
Type=Application
Name=AirCursor desktop self-repair
Exec=/usr/local/bin/aircursor-heal
NoDisplay=true
AUTO

# Games live in /usr/games, which is not on the PATH inside proot: link them
for f in /usr/games/*; do [ -f "$f" ] && ln -sf "$f" /usr/local/bin/; done
grep -q '/usr/games' /etc/profile.d/aircursor-path.sh 2>/dev/null || \
    echo 'case ":$PATH:" in *:/usr/games:*) ;; *) export PATH="$PATH:/usr/local/games:/usr/games" ;; esac' \
    > /etc/profile.d/aircursor-path.sh

# Extra apps menu, App Store, .deb installer, Windows-style layout
[ -f /tmp/aircursor-ubuntu-extras.sh ] && install -m 0755 /tmp/aircursor-ubuntu-extras.sh /usr/local/bin/ubuntu-extras
[ -f /tmp/aircursor-app-store.sh ] && install -m 0755 /tmp/aircursor-app-store.sh /usr/local/bin/aircursor-store
[ -f /tmp/aircursor-install-deb.sh ] && install -m 0755 /tmp/aircursor-install-deb.sh /usr/local/bin/aircursor-install-deb
[ -f /tmp/aircursor-windows-layout.sh ] && install -m 0755 /tmp/aircursor-windows-layout.sh /usr/local/bin/aircursor-windows-layout
mkdir -p /usr/share/applications
cat > /usr/share/applications/aircursor-store.desktop <<'STORE'
[Desktop Entry]
Type=Application
Name=App Store
Comment=Install apps with one click
Exec=aircursor-store
Icon=system-software-install
Categories=System;Settings;
STORE
cat > /usr/share/applications/aircursor-install-deb.desktop <<'DEBH'
[Desktop Entry]
Type=Application
Name=Install app package
Exec=aircursor-install-deb %f
Icon=system-software-install
NoDisplay=true
MimeType=application/vnd.debian.binary-package;
DEBH
if [ -x /usr/local/bin/aircursor-windows-layout ]; then
    cat > /etc/xdg/autostart/aircursor-windows-layout.desktop <<'WINA'
[Desktop Entry]
Type=Application
Name=AirCursor Windows-style layout (first login only)
Exec=/usr/local/bin/aircursor-windows-layout
NoDisplay=true
WINA
fi

# The user's desktop
if id "$U" >/dev/null 2>&1; then
    if [ "$RESET" = "1" ] && [ -d "$H/.config/xfce4" ]; then
        B="$H/.config/xfce4-backup-$(date +%Y%m%d-%H%M%S)"
        mv "$H/.config/xfce4" "$B"
        echo "Old desktop settings kept as backup: $B"
    fi
    rm -rf "$H/.cache/sessions"
    mkdir -p "$H/Desktop"
    # Desktop icon: the App Store (replaces the old terminal "Install Apps" shortcut)
    rm -f "$H/Desktop/Install-Apps.desktop"
    if [ ! -f "$H/Desktop/App-Store.desktop" ]; then
        cp /usr/share/applications/aircursor-store.desktop "$H/Desktop/App-Store.desktop"
        chmod +x "$H/Desktop/App-Store.desktop"
    fi
    chown -R "$U:$U" "$H" 2>/dev/null
fi
echo "Repair inside Ubuntu done."
