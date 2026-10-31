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
for p in procps librsvg2-common dbus-x11 xfwm4 xfce4-panel xfdesktop4 xfce4-session xfce4-terminal thunar; do
    dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"
done
if [ -n "$need" ]; then
    echo "Installing missing parts:$need"
    apt-get update >/dev/null 2>&1
    apt-get install -y --no-install-recommends $need
fi

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

# Extra apps menu
[ -f /tmp/aircursor-ubuntu-extras.sh ] && install -m 0755 /tmp/aircursor-ubuntu-extras.sh /usr/local/bin/ubuntu-extras

# The user's desktop
if id "$U" >/dev/null 2>&1; then
    if [ "$RESET" = "1" ] && [ -d "$H/.config/xfce4" ]; then
        B="$H/.config/xfce4-backup-$(date +%Y%m%d-%H%M%S)"
        mv "$H/.config/xfce4" "$B"
        echo "Old desktop settings kept as backup: $B"
    fi
    rm -rf "$H/.cache/sessions"
    mkdir -p "$H/Desktop"
    if [ ! -f "$H/Desktop/Install-Apps.desktop" ]; then
        cat > "$H/Desktop/Install-Apps.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=Install Apps (VS Code, Claude Code, Ollama, Blender...)
Exec=xfce4-terminal --hold -e "sudo ubuntu-extras"
Icon=system-software-install
Terminal=false
DESK
        chmod +x "$H/Desktop/Install-Apps.desktop"
    fi
    chown -R "$U:$U" "$H" 2>/dev/null
fi
echo "Repair inside Ubuntu done."
