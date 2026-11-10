#!/bin/bash
# AirCursor PC look.
#   dock     – modern: dark theme, app dock at the bottom centre, clean desktop (default)
#   windows  – Windows-style taskbar with Start, pinned apps and clock
#   auto     – every desktop start: the first time the dock; later only the newest look updates
#   refresh  – the look only (theme, icons, font, wallpaper, tidy desktop); the dock stays as it is
#   upgrade  – in the background: real logos for web shortcuts; the modern theme package, then refresh
# Change it any time: AirCursor toolbar → lightning → PC style, or: aircursor-style dock|windows
MODE="${1:-auto}"
MARK="$HOME/.config/xfce4/aircursor-style"
LOOK="$HOME/.config/xfce4/aircursor-look-2"
LIB=/usr/local/lib/cloudpc
. /tmp/cloudpc-session.env 2>/dev/null
command -v xfconf-query >/dev/null || exit 0
mkdir -p "$(dirname "$MARK")"
q() { xfconf-query "$@" >/dev/null 2>&1; }

# ---- Look: dark windows, clean font, modern icons ----
look() {
    local theme=Adwaita-dark icons=""
    [ -d /usr/share/themes/Greybird-dark ] && theme=Greybird-dark
    [ -d /usr/share/themes/Arc-Dark ] && theme=Arc-Dark
    [ -d /usr/share/icons/elementary-xfce-dark ] && icons=elementary-xfce-dark
    [ -d /usr/share/icons/Papirus-Dark ] && icons=Papirus-Dark
    q -c xsettings -p /Net/ThemeName --create -t string -s "$theme"
    if [ -d "/usr/share/themes/$theme/xfwm4" ]; then q -c xfwm4 -p /general/theme --create -t string -s "$theme"; fi
    [ -n "$icons" ] && q -c xsettings -p /Net/IconThemeName --create -t string -s "$icons"
    if fc-list 2>/dev/null | grep -qi 'Inter'; then
        q -c xsettings -p /Gtk/FontName --create -t string -s "Inter 10"
        q -c xfwm4 -p /general/title_font --create -t string -s "Inter Semi-Bold 10"
    fi
    q -c xsettings -p /Xft/Antialias --create -t int -s 1
    q -c xsettings -p /Xft/HintStyle --create -t string -s hintslight
    q -c xsettings -p /Xft/RGBA --create -t string -s rgb
    q -c xfwm4 -p /general/button_layout --create -t string -s "O|HMC"
    q -c xfwm4 -p /general/title_alignment --create -t string -s center
    q -c xfwm4 -p /general/use_compositing --create -t bool -s false
    # Desktop: clean icons, no drive icons.
    q -c xfce4-desktop -p /desktop-icons/icon-size --create -t uint -s 48
    q -c xfce4-desktop -p /desktop-icons/use-custom-font-size --create -t bool -s true
    q -c xfce4-desktop -p /desktop-icons/font-size --create -t double -s 9.5
    q -c xfce4-desktop -p /desktop-icons/file-icons/show-filesystem --create -t bool -s false
    q -c xfce4-desktop -p /desktop-icons/file-icons/show-home --create -t bool -s false
    q -c xfce4-desktop -p /desktop-icons/file-icons/show-trash --create -t bool -s false
    q -c xfce4-desktop -p /desktop-icons/file-icons/show-removable --create -t bool -s false
    q -c xfce4-desktop -p /desktop-icons/tooltip-size --create -t double -s 64
}

# Wallpaper: real photo "City lights" (NASA) – the first time, or when it is still our old one.
wallpaper() {
    local img=/usr/share/backgrounds/aircursor/city.jpg
    [ -f "$img" ] && command -v aircursor-set-wallpaper >/dev/null || return 0
    local now
    now=$(xfconf-query -c xfce4-desktop -l -v 2>/dev/null | grep '/last-image ' | head -n 1 | awk '{print $2}')
    case "$now" in
        ""|*aircursor/night.svg|*xubuntu-wallpaper*|*/backdrops/*|*/backgrounds/xfce/*) aircursor-set-wallpaper "$img" >/dev/null 2>&1 ;;
    esac
}

# Clean desktop: our extra shortcuts go into a "Tools" folder (moved, never deleted); one Air Store.
tidy() {
    local d="$HOME/Desktop" t="$HOME/Desktop/Tools"
    [ -d "$d" ] || return 0
    mkdir -p "$t"
    for f in Benchmark.desktop Firefox.desktop firefox.desktop Terminal.desktop Change-Wallpaper.desktop GitHub.desktop \
        Google-Drive.desktop; do
        [ -f "$d/$f" ] && [ ! -e "$t/$f" ] && mv "$d/$f" "$t/$f"
    done
    # Only Air Store: the old App Store icons go.
    for f in App-Store.desktop aircursor-store.desktop; do rm -f "$d/$f" "$t/$f"; done
    [ -f "$d/Air-Store.desktop" ] && rm -f "$d/airstore.desktop"
    [ -f "$d/YouTube.desktop" ] && rm -f "$d/cloudpc-youtube.desktop"
    rmdir "$t" 2>/dev/null
    return 0
}

refresh() {
    look
    wallpaper
    tidy
    xfdesktop --reload >/dev/null 2>&1 &
    touch "$LOOK"
}

# The modern theme package for PCs made before it was in the ready-made image (3 MB; low priority,
# in the background), then the look again. Real logos for web shortcuts every start (fast when done).
upgrade() {
    python3 "$LIB/web-icons.py" >/dev/null 2>&1
    local need=""
    [ -d /usr/share/themes/Arc-Dark ] || need="$need arc-theme"
    [ -n "$need" ] || return 0
    [ -f "$HOME/.config/xfce4/aircursor-look-tried" ] && [ -n "$(find "$HOME/.config/xfce4/aircursor-look-tried" -mmin -720 2>/dev/null)" ] && return 0
    touch "$HOME/.config/xfce4/aircursor-look-tried"
    nice -n 15 apt-get install -y --no-install-recommends $need >/dev/null 2>&1 ||
        { nice -n 15 apt-get update >/dev/null 2>&1 && nice -n 15 apt-get install -y --no-install-recommends $need >/dev/null 2>&1; } ||
        return 0
    # Newly installed: the modern look once (later changes in Settings → Appearance stay).
    refresh
}

case "$MODE" in
    refresh) refresh; exit 0 ;;
    upgrade) upgrade; exit 0 ;;
    auto)
        if [ -f "$MARK" ]; then
            # Not the first start: newest look once, logos every start (fast when they are there).
            sleep 8
            if [ ! -f "$LOOK" ]; then refresh; fi
            ( sleep 50; upgrade ) >/dev/null 2>&1 &
            exit 0
        fi
        sleep 6   # let the session finish starting
        MODE=dock
        ;;
esac
if [ "$MODE" = windows ]; then
    bash "$LIB/windows-layout.sh" --force
    look
    echo windows > "$MARK"
    exit 0
fi

look

# ---- The dock ----
P=xfce4-panel
LT=$(grep -o 'name="length" type="[a-z]*"' /etc/xdg/xfce4/panel/default.xml 2>/dev/null | head -n 1 | sed 's/.*type="\([a-z]*\)"/\1/')
LT=${LT:-uint}
xfconf-query -c $P -p /panels -rR 2>/dev/null
xfconf-query -c $P -p /plugins -rR 2>/dev/null
IDS=()
N=0
add() { N=$((N + 1)); q -c $P -p /plugins/plugin-$N --create -t string -s "$1"; IDS+=(-t int -s $N); }
launcher() {
    local f=$1
    [ -f "/usr/share/applications/$f" ] || return 0
    add launcher
    mkdir -p "$HOME/.config/xfce4/panel/launcher-$N"
    cp "/usr/share/applications/$f" "$HOME/.config/xfce4/panel/launcher-$N/"
    q -c $P -p /plugins/plugin-$N/items --create --force-array -t string -s "$f"
}

# Apps menu (all apps, search) as the first dock icon: the AirCursor PC eagle.
if dpkg -s xfce4-whiskermenu-plugin >/dev/null 2>&1; then add whiskermenu; else add applicationsmenu; fi
q -c $P -p /plugins/plugin-$N/show-button-title --create -t bool -s false
START=/usr/share/pixmaps/aircursor-start.svg
[ -f "$START" ] || START=/usr/share/pixmaps/aircursor-pc.svg
q -c $P -p /plugins/plugin-$N/button-icon --create -t string -s "$START"
q -c $P -p /plugins/plugin-$N/show-button-icon --create -t bool -s true
q -c $P -p /plugins/plugin-$N/menu-width --create -t int -s 560
q -c $P -p /plugins/plugin-$N/menu-height --create -t int -s 480
for f in thunar.desktop cloudpc-air.desktop cloudpc-youtube.desktop airstore.desktop xfce4-terminal.desktop; do
    launcher "$f"
done
add separator; q -c $P -p /plugins/plugin-$N/style --create -t uint -s 1
add tasklist
q -c $P -p /plugins/plugin-$N/show-labels --create -t bool -s false
q -c $P -p /plugins/plugin-$N/flat-buttons --create -t bool -s true
q -c $P -p /plugins/plugin-$N/show-handle --create -t bool -s false
q -c $P -p /plugins/plugin-$N/grouping --create -t uint -s 1
add separator; q -c $P -p /plugins/plugin-$N/style --create -t uint -s 1
add systray
q -c $P -p /plugins/plugin-$N/square-icons --create -t bool -s true
add clock
q -c $P -p /plugins/plugin-$N/mode --create -t uint -s 2
q -c $P -p /plugins/plugin-$N/digital-layout --create -t uint -s 3
q -c $P -p /plugins/plugin-$N/digital-time-format --create -t string -s "%H:%M"
q -c $P -p /plugins/plugin-$N/digital-format --create -t string -s "%H:%M"

q -c $P -p /panels --create --force-array -t int -s 1
q -c $P -p /panels/dark-mode --create -t bool -s true
q -c $P -p /panels/panel-1/position --create -t string -s "p=10;x=0;y=0"   # bottom centre
q -c $P -p /panels/panel-1/length --create -t "$LT" -s 1
q -c $P -p /panels/panel-1/length-adjust --create -t bool -s true
q -c $P -p /panels/panel-1/position-locked --create -t bool -s true
q -c $P -p /panels/panel-1/size --create -t uint -s 52
q -c $P -p /panels/panel-1/icon-size --create -t uint -s 32
q -c $P -p /panels/panel-1/background-style --create -t uint -s 1
xfconf-query -c $P -p /panels/panel-1/background-rgba --create \
    -t double -s 0.075 -t double -s 0.082 -t double -s 0.110 -t double -s 0.94 2>/dev/null
q -c $P -p /panels/panel-1/plugin-ids --create --force-array "${IDS[@]}"

# Win key opens the apps menu; .deb files install with a double click.
q -c xfce4-keyboard-shortcuts -p /commands/custom/Super_L --create -t string -s "xfce4-popup-whiskermenu"
xdg-mime default aircursor-install-deb.desktop application/vnd.debian.binary-package 2>/dev/null

wallpaper
tidy
touch "$LOOK"

xfce4-panel -r >/dev/null 2>&1 &
( sleep 5; pgrep -x xfce4-panel >/dev/null || xfce4-panel >/dev/null 2>&1 & ) &
xfdesktop --reload >/dev/null 2>&1 &
echo dock > "$MARK"
( sleep 40; upgrade ) >/dev/null 2>&1 &
