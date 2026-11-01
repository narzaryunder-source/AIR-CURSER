#!/bin/bash
# Windows-style desktop: one taskbar at the bottom with a Start menu, pinned apps, open windows,
# tray and clock; dark theme; window buttons on the right. Runs once per user (first login).
# Run again any time:  aircursor-windows-layout --force
MARK="$HOME/.config/xfce4/aircursor-windows-layout-v1"
[ -f "$MARK" ] && [ "${1:-}" != "--force" ] && exit 0
command -v xfconf-query >/dev/null || exit 0
[ "${1:-}" != "--force" ] && sleep 6   # let the session finish starting
q() { xfconf-query "$@" >/dev/null 2>&1; }

# Theme
THEME=Adwaita-dark
[ -d /usr/share/themes/Greybird-dark ] && THEME=Greybird-dark
q -c xsettings -p /Net/ThemeName --create -t string -s "$THEME"
q -c xfwm4 -p /general/theme --create -t string -s "$THEME"
q -c xfwm4 -p /general/button_layout --create -t string -s "O|HMC"
q -c xfwm4 -p /general/use_compositing --create -t bool -s false

# Desktop icons like Windows
q -c xfce4-desktop -p /desktop-icons/file-icons/show-filesystem --create -t bool -s false
q -c xfce4-desktop -p /desktop-icons/file-icons/show-trash --create -t bool -s true
q -c xfce4-desktop -p /desktop-icons/file-icons/show-removable --create -t bool -s false

# One taskbar at the bottom
P=xfce4-panel
LT=$(grep -o 'name="length" type="[a-z]*"' /etc/xdg/xfce4/panel/default.xml 2>/dev/null | head -n 1 | sed 's/.*type="\([a-z]*\)"/\1/')
LT=${LT:-uint}
xfconf-query -c $P -p /panels -rR 2>/dev/null
xfconf-query -c $P -p /plugins -rR 2>/dev/null
IDS=()
N=0
add() { N=$((N + 1)); q -c $P -p /plugins/plugin-$N --create -t string -s "$1"; IDS+=(-t int -s $N); }

if dpkg -s xfce4-whiskermenu-plugin >/dev/null 2>&1; then add whiskermenu; else add applicationsmenu; fi
q -c $P -p /plugins/plugin-$N/button-title --create -t string -s " Start"
q -c $P -p /plugins/plugin-$N/show-button-title --create -t bool -s true
add separator; q -c $P -p /plugins/plugin-$N/style --create -t uint -s 0
for app in thunar.desktop firefox.desktop xfce4-terminal.desktop aircursor-store.desktop; do
    f=$(ls /usr/share/applications 2>/dev/null | grep -ix "$app" | head -n 1)
    [ -z "$f" ] && continue
    add launcher
    mkdir -p "$HOME/.config/xfce4/panel/launcher-$N"
    cp "/usr/share/applications/$f" "$HOME/.config/xfce4/panel/launcher-$N/"
    q -c $P -p /plugins/plugin-$N/items --create --force-array -t string -s "$f"
done
add separator; q -c $P -p /plugins/plugin-$N/style --create -t uint -s 0
add tasklist;  q -c $P -p /plugins/plugin-$N/show-labels --create -t bool -s false
add separator; q -c $P -p /plugins/plugin-$N/expand --create -t bool -s true
q -c $P -p /plugins/plugin-$N/style --create -t uint -s 0
add systray
add clock
add showdesktop

q -c $P -p /panels --create --force-array -t int -s 1
q -c $P -p /panels/dark-mode --create -t bool -s true
q -c $P -p /panels/panel-1/position --create -t string -s "p=8;x=0;y=0"
q -c $P -p /panels/panel-1/length --create -t "$LT" -s 100
q -c $P -p /panels/panel-1/position-locked --create -t bool -s true
q -c $P -p /panels/panel-1/size --create -t uint -s 44
q -c $P -p /panels/panel-1/plugin-ids --create --force-array "${IDS[@]}"

# Win key opens the Start menu; double-clicking a .deb file installs it
q -c xfce4-keyboard-shortcuts -p /commands/custom/Super_L --create -t string -s "xfce4-popup-whiskermenu"
xdg-mime default aircursor-install-deb.desktop application/vnd.debian.binary-package 2>/dev/null

xfce4-panel -r >/dev/null 2>&1 &
xfdesktop --reload >/dev/null 2>&1 &
mkdir -p "$(dirname "$MARK")" && touch "$MARK"
