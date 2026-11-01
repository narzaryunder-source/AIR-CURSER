#!/bin/bash
# "Change Wallpaper": pick any photo from the phone and it becomes the desktop wallpaper at once.
# Installed inside Ubuntu as /usr/local/bin/aircursor-wallpaper (runs as the desktop user).
export GSK_RENDERER=cairo NO_AT_BRIDGE=1
START="$HOME/Pictures"
for d in /sdcard/Pictures /sdcard/Download /sdcard/DCIM /sdcard; do
    if [ -r "$d" ] && ls "$d" >/dev/null 2>&1; then START="$d"; break; fi
done
if [ "$START" = "$HOME/Pictures" ]; then
    zenity --info --width=460 --title="Change Wallpaper" \
        --text="Phone ki photos abhi nahi dikh rahi.\n\nEk baar: phone Settings → Apps → Termux → Permissions → Files / Photos → Allow.\nPhir yeh app dobara kholo.\n\n(Abhi ke liye Ubuntu ke Pictures folder se chun sakte ho.)"
    mkdir -p "$START"
fi

IMG=$(zenity --file-selection --title="Wallpaper ke liye photo chuno" --filename="$START/" \
    --file-filter="Photos | *.jpg *.jpeg *.png *.webp *.JPG *.JPEG *.PNG *.WEBP") || exit 0
[ -f "$IMG" ] || exit 0

# Keep a copy inside Ubuntu, so the wallpaper stays even if the phone photo is moved
DEST_DIR="$HOME/Pictures/Wallpapers"
mkdir -p "$DEST_DIR"
EXT=$(printf '%s' "${IMG##*.}" | tr 'A-Z' 'a-z')
DEST="$DEST_DIR/wallpaper-$(date +%Y%m%d-%H%M%S).$EXT"
cp "$IMG" "$DEST" || { zenity --error --text="Photo copy nahi hui."; exit 1; }

# .webp photos need an extra image loader
if [ "$EXT" = "webp" ] && ! dpkg -s webp-pixbuf-loader >/dev/null 2>&1; then
    sudo -n apt-get install -y webp-pixbuf-loader >/dev/null 2>&1
fi

# 1) default wallpaper for new sessions / settings resets
sudo -n ln -sf "$DEST" /usr/share/xfce4/backdrops/xubuntu-wallpaper.png 2>/dev/null
# 2) every screen/workspace that already has its own wallpaper setting
for p in $(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep -E '/last-image$'); do
    xfconf-query -c xfce4-desktop -p "$p" -s "$DEST"
    xfconf-query -c xfce4-desktop -p "${p%/last-image}/image-style" -s 5 2>/dev/null   # 5 = zoomed (fills screen)
done
xfdesktop --reload >/dev/null 2>&1 &
notify-send "Wallpaper changed" 2>/dev/null || true
