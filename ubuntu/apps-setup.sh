#!/bin/bash
# AirCursor PC: keeps every installed app easy to open. Runs after every apt/dpkg run (also for
# .deb files downloaded in the browser) and at desktop start.
#  - Start menu / desktop entries open through cloudpc-launch (copies in /usr/local/share/applications,
#    the originals from the packages stay untouched)
#  - fixes for apps that refuse to run inside the PC (VS Code, VLC, Electron apps)
#  - new apps get a desktop icon (games in the Games folder) and a Start menu favourite
SRC=/usr/share/applications
OVR=/usr/local/share/applications
STATE=/var/lib/cloudpc
H=/root
mkdir -p "$OVR" "$STATE" 2>/dev/null || exit 0
exec 9>"$STATE/apps.lock"
flock -w 60 9 2>/dev/null

# ---- App fixes -------------------------------------------------------------------------------
# VLC refuses to start as root (the PC's user): the usual one-word patch.
if [ -f /usr/bin/vlc ] && grep -q geteuid /usr/bin/vlc 2>/dev/null; then
    sed -i 's/geteuid/getppid/' /usr/bin/vlc 2>/dev/null
fi
# VS Code: no Chromium sandbox here, software drawing, passwords without a keyring.
if [ -x /usr/share/code/code ]; then
    for w in vscode code; do
        printf '#!/bin/sh\nexec /usr/share/code/bin/code --no-sandbox --user-data-dir=/root/.config/Code "$@"\n' > /usr/local/bin/$w
        chmod +x /usr/local/bin/$w
    done
    mkdir -p "$H/.vscode"
    [ -f "$H/.vscode/argv.json" ] || cat > "$H/.vscode/argv.json" <<'JSON'
{
    "disable-hardware-acceleration": true,
    "password-store": "basic",
    "enable-crash-reporter": false
}
JSON
    # Older AirCursor versions pointed the menu entry at a wrapper: point it back at the app.
    sed -i 's|^Exec=/usr/local/bin/vscode |Exec=/usr/share/code/code |' "$SRC"/code*.desktop 2>/dev/null
fi
# Games installed in /usr/games: also start from the terminal.
for f in /usr/games/*; do [ -f "$f" ] && [ ! -e "/usr/local/bin/${f##*/}" ] && ln -s "$f" /usr/local/bin/ 2>/dev/null; done

# ---- Which entries are apps a person installed? ----------------------------------------------
is_base() {   # parts of the desktop itself: no wrapper, no extra icon
    case "$1" in
        xfce4-*|xfce-*|org.xfce.*|thunar*|Thunar*|exo-*|firefox*|aircursor-*|cloudpc-*|*url-handler*|debian-*|\
        display-im6*|vim*|gvim*|nm-*|gcr-*|org.gnome.Zenity*|zenity*|xdg-*|yelp*|python3*|htop*|info.desktop|\
        panel-*|mimeinfo*|openjdk-*|libreoffice-startcenter*|libreoffice-xsltfilter*|ca.desrt.*|*-settings.desktop|\
        org.freedesktop.*|gnome-system-monitor*|software-properties*|update-manager*|im-config*) return 0 ;;
    esac
    return 1
}
visible() {   # $1 = file: shown in menus and not a terminal tool
    ! grep -qiE '^(NoDisplay|Hidden)=true' "$1" && ! grep -qiE '^Terminal=true' "$1" &&
        ! grep -qiE '^Categories=.*(Settings|X-XFCE-SettingsDialog)' "$1"
}

# ---- Start menu copies that open through cloudpc-launch ----------------------------------------
# Only entries that are new or changed since the last run are looked at (fast after every install).
SIG="$STATE/apps.sig"
NEWSIG=$(stat -c '%n|%s|%Y' "$SRC"/*.desktop 2>/dev/null | sort)
while IFS= read -r f; do
    [ -f "$f" ] || continue
    id=${f##*/}
    is_base "$id" && continue
    if visible "$f"; then
        sed -e "s|^Exec=|Exec=cloudpc-launch $id |" -e '/^X-CloudPC-Wrapped=/d' \
            -e '0,/^\[Desktop Entry\]/s//[Desktop Entry]\nX-CloudPC-Wrapped=true/' "$f" > "$OVR/.$id.tmp" &&
            mv -f "$OVR/.$id.tmp" "$OVR/$id"
    else
        rm -f "$OVR/$id"
    fi
done < <(comm -13 <(sort "$SIG" 2>/dev/null) <(echo "$NEWSIG") | cut -d'|' -f1)
echo "$NEWSIG" > "$SIG"
OURS=$(grep -l '^X-CloudPC-Wrapped=true' "$OVR"/*.desktop 2>/dev/null)
# Apps that were removed: remove our copies and their icons.
APPS=()
while IFS= read -r f; do
    [ -n "$f" ] || continue
    id=${f##*/}
    if [ -f "$SRC/$id" ]; then APPS+=("$id"); else rm -f "$f" "$H/Desktop/$id" "$H/Desktop/Games/$id"; fi
done <<< "$OURS"

# ---- Desktop icons + Start menu favourites for new apps ---------------------------------------
KNOWN="$STATE/apps.known"
touch "$KNOWN"
NEW=()
for id in "${APPS[@]}"; do
    if grep -qxF "$id" "$KNOWN"; then
        continue
    fi
    echo "$id" >> "$KNOWN"
    case "$id" in libreoffice-draw*|libreoffice-math*|libreoffice-base*) continue ;; esac
    if grep -qiE '^Categories=.*Game' "$OVR/$id"; then d="$H/Desktop/Games"; else d="$H/Desktop"; NEW+=("$id"); fi
    mkdir -p "$d"
    # Replace plain copies made by older versions; otherwise add the icon once.
    for old in "$H/Desktop/$id" "$H/Desktop/Games/$id"; do
        [ -f "$old" ] && ! grep -q '^X-CloudPC-Wrapped=true' "$old" && rm -f "$old"
    done
    [ -e "$H/Desktop/$id" ] || [ -e "$H/Desktop/Games/$id" ] || { cp "$OVR/$id" "$d/$id" && chmod +x "$d/$id"; }
done
[ ${#NEW[@]} -eq 0 ] && exit 0

. /tmp/cloudpc-session.env 2>/dev/null
[ -n "$DBUS_SESSION_BUS_ADDRESS" ] || exit 0
pid=""
for p in $(timeout 5 xfconf-query -c xfce4-panel -l 2>/dev/null | grep -E '^/plugins/plugin-[0-9]+$'); do
    [ "$(timeout 5 xfconf-query -c xfce4-panel -p "$p" 2>/dev/null)" = "whiskermenu" ] && pid="$p" && break
done
if [ -n "$pid" ]; then
    cur=$(timeout 5 xfconf-query -c xfce4-panel -p "$pid/favorites" 2>/dev/null | grep '\.desktop$')
    [ -z "$cur" ] && cur=$(printf '%s\n' xfce4-web-browser.desktop xfce4-file-manager.desktop xfce4-terminal-emulator.desktop)
    all=(); seen=" "
    for p in $cur "${NEW[@]}"; do
        case "$p" in libreoffice-draw*|libreoffice-math*|libreoffice-base*) continue ;; esac
        case "$seen" in *" $p "*) continue ;; esac
        seen="$seen$p "
        all+=(-t string -s "$p")
    done
    timeout 5 xfconf-query -c xfce4-panel -p "$pid/favorites" --create --force-array "${all[@]}" 2>/dev/null
fi
exit 0
