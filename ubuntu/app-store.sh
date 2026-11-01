#!/bin/bash
# AirCursor App Store: tick apps and press Install – no terminal needed.
# Installed inside Ubuntu as /usr/local/bin/aircursor-store
export GSK_RENDERER=cairo NO_AT_BRIDGE=1   # software drawing: faster and safer inside proot
export LANG="${LANG:-C.UTF-8}"; [ "$LANG" = "C" ] || [ "$LANG" = "POSIX" ] && export LANG=C.UTF-8
SEARCH="Search 60,000+ apps"
LOG=/tmp/aircursor-store.log

# Rough download sizes (MB) so people know what they are starting.
size_of() {
    case "$1" in
        libreoffice) echo 300 ;; gimp) echo 120 ;; vlc) echo 60 ;; gdrive) echo 25 ;; shotcut) echo 150 ;;
        kdenlive) echo 450 ;; inkscape) echo 120 ;; vscode) echo 110 ;; claude) echo 80 ;; python) echo 40 ;;
        games) echo 280 ;; luanti) echo 60 ;; games3d) echo 750 ;; blender) echo 350 ;; ollama) echo 1500 ;;
        *) echo 50 ;;
    esac
}

# Is an App Store app already installed? (shown as "Installed" in the list)
is_installed() {
    case "$1" in
        libreoffice) command -v libreoffice >/dev/null ;; gimp) command -v gimp >/dev/null ;;
        vlc) command -v vlc >/dev/null ;; gdrive) command -v google-drive >/dev/null ;;
        vscode) [ -x /usr/share/code/code ] ;; claude) command -v claude >/dev/null ;;
        python) command -v pip3 >/dev/null ;; inkscape) command -v inkscape >/dev/null ;;
        shotcut) command -v shotcut >/dev/null ;; kdenlive) command -v kdenlive >/dev/null ;;
        games) [ -x /usr/games/supertux2 ] || command -v supertux2 >/dev/null ;;
        luanti) command -v luanti >/dev/null || command -v minetest >/dev/null ;;
        games3d) [ -x /usr/games/supertuxkart ] || command -v supertuxkart >/dev/null ;;
        blender) command -v blender >/dev/null ;; ollama) command -v ollama >/dev/null ;;
        *) return 1 ;;
    esac
}

status_of() {   # "Installed" or the download size
    if is_installed "$1"; then echo "Installed"; else echo "$(size_of "$1") MB"; fi
}

APPS=/usr/share/applications
menu_snapshot() { ls "$APPS"/*.desktop 2>/dev/null | sort; }

# Like a Windows installer: new apps get a desktop shortcut (games in a Games folder) and a
# place in the Start menu favourites, so they are easy to find.
add_shortcuts() {   # $1 = list of new .desktop files (one per line)
    local f base dest names=()
    while IFS= read -r f; do
        [ -f "$f" ] || continue
        base=$(basename "$f")
        grep -qi '^NoDisplay=true' "$f" && continue
        case "$base" in
            aircursor-*|cloudpc-*|*-url-handler.desktop|libreoffice-draw*|libreoffice-math*|libreoffice-base*|libreoffice-xsltfilter*|libreoffice-startcenter*) continue ;;
        esac
        if grep -qiE '^Categories=.*Game' "$f"; then dest="$HOME/Desktop/Games"; else dest="$HOME/Desktop"; fi
        mkdir -p "$dest"
        cp "$f" "$dest/$base" && chmod +x "$dest/$base"
        names+=("$base")
    done <<< "$1"
    [ ${#names[@]} -eq 0 ] && return
    # Start menu (Whisker) favourites
    local pid p cur all=() seen=" "
    for p in $(xfconf-query -c xfce4-panel -l 2>/dev/null | grep -E '^/plugins/plugin-[0-9]+$'); do
        [ "$(xfconf-query -c xfce4-panel -p "$p" 2>/dev/null)" = "whiskermenu" ] && pid="$p" && break
    done
    [ -z "$pid" ] && return
    cur=$(xfconf-query -c xfce4-panel -p "$pid/favorites" 2>/dev/null | grep '\.desktop$')
    [ -z "$cur" ] && cur=$(printf '%s\n' xfce4-web-browser.desktop xfce4-file-manager.desktop xfce4-terminal-emulator.desktop)
    for p in $cur "${names[@]}"; do
        case "$seen" in *" $p "*) continue ;; esac
        seen="$seen$p "
        all+=(-t string -s "$p")
    done
    xfconf-query -c xfce4-panel -p "$pid/favorites" --create --force-array "${all[@]}" 2>/dev/null
    xfdesktop --reload >/dev/null 2>&1 &
}

progress_install() {   # $1 = window title, rest = ids/packages for ubuntu-extras
    local title="$1"; shift
    local before
    before=$(menu_snapshot)
    # Numbers from "@@PCT n" move the bar; "[i/n] Installing X" is kept as the heading of every line.
    ( sudo /usr/local/bin/ubuntu-extras install "$@" 2>&1 | tee "$LOG" \
        | sed -u -r 's/\x1b\[[0-9;]*m//g
            /^@@PCT /{s/^@@PCT //;p;d}
            /^\[[0-9]+\/[0-9]+\] /{h;s/^/# /;p;d}
            G
            s/^(.*)\n(.*)$/# \2  –  \1/' ) \
        | zenity --progress --auto-close --no-cancel --width=640 --title="$title" --text="Starting…"
    if command -v cloudpc-apps >/dev/null 2>&1; then cloudpc-apps; else add_shortcuts "$(comm -13 <(echo "$before") <(menu_snapshot))"; fi
    local failed
    failed=$(sed -r 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -E '^(Failed: |  - )' | sed -r 's/^Failed: //; s/^  - //' | head -n 8)
    if [ -n "$failed" ]; then
        zenity --warning --width=420 --title="App Store" \
            --text="Some apps could not be installed:\n\n$failed\n\nCheck the internet connection and try them again."
    else
        zenity --info --width=400 --title="App Store" \
            --text="Done. Your new apps are on the desktop and in the Start menu (Favourites)."
    fi
}

confirm_big() {   # warns before long installs
    local n=$# total=0 heavy=""
    for id in "$@"; do
        total=$((total + $(size_of "$id")))
        case "$id" in kdenlive|games3d|blender|ollama) heavy="yes" ;; esac
    done
    if [ "$n" -le 3 ] && [ -z "$heavy" ] && [ "$total" -lt 600 ]; then return 0; fi
    local mins=$(( total / 25 + 5 ))
    zenity --question --width=460 --title="App Store" --ok-label="Install" --cancel-label="Choose again" \
        --text="You selected $n app(s) – about $total MB.\n\nOn a phone this can take around $mins minutes. Keep the phone charging and AirCursor PC open until it finishes.\n\nTip: installing 2–3 apps at a time is faster to try out."
}

search_flow() {
    local term rows sel
    term=$(zenity --entry --title="$SEARCH" --width=420 \
        --text="Type what you need in English, for example:\nchess, paint, music, pdf, calculator") || return
    [ -z "$term" ] && return
    rows=()
    while IFS= read -r line; do
        rows+=(FALSE "${line%% - *}" "${line#* - }")
    done < <(apt-cache search -- "$term" 2>/dev/null | head -n 80)
    if [ ${#rows[@]} -eq 0 ]; then
        zenity --info --text="Nothing found for \"$term\". Try another word."
        return
    fi
    sel=$(zenity --list --checklist --title="Results: $term" --width=760 --height=560 \
        --text="Tick what you want, then tap Install" --ok-label="Install" --cancel-label="Back" \
        --column="✓" --column="Package" --column="Description" --print-column=2 --separator=" " "${rows[@]}") || return
    # shellcheck disable=SC2086
    [ -n "$sel" ] && confirm_big $sel && progress_install "Installing apps" $sel
}

# Testing: AIRCURSOR_STORE_LIB=1 . app-store.sh  (load the functions only)
[ "${AIRCURSOR_STORE_LIB:-}" = 1 ] && return 0

while true; do
    S() { status_of "$1"; }
    CHOICE=$(zenity --list --checklist --title="App Store" --width=780 --height=620 \
        --text="Tick the apps you want and tap Install. (Windows .exe programs do not run here – these are Linux apps.)" \
        --ok-label="Install" --cancel-label="Close" --extra-button="$SEARCH" \
        --column="✓" --column="id" --column="App" --column="What it is" --column="Status" \
        --hide-column=2 --print-column=2 --separator=" " \
        FALSE libreoffice "LibreOffice" "Documents, spreadsheets, presentations" "$(S libreoffice)" \
        FALSE gimp "GIMP" "Photo editing" "$(S gimp)" \
        FALSE vlc "VLC" "Video and music player" "$(S vlc)" \
        FALSE gdrive "Google Drive" "15 GB free cloud storage" "$(S gdrive)" \
        FALSE vscode "VS Code" "Code editor" "$(S vscode)" \
        FALSE python "Python" "Learn programming" "$(S python)" \
        FALSE claude "Claude Code" "AI coding assistant (Claude account needed)" "$(S claude)" \
        FALSE inkscape "Inkscape" "Logos and posters" "$(S inkscape)" \
        FALSE shotcut "Shotcut" "Simple video editor" "$(S shotcut)" \
        FALSE games "Games pack" "SuperTux, X-Moto, 2048, Chess, Solitaire, OpenTTD…" "$(S games)" \
        FALSE luanti "Luanti" "Block building game" "$(S luanti)" \
        FALSE kdenlive "Kdenlive" "Advanced video editor (heavy)" "$(S kdenlive)" \
        FALSE blender "Blender" "3D modelling (heavy, basic use)" "$(S blender)" \
        FALSE games3d "3D games" "SuperTuxKart, Neverball, Tux Racer, Luanti (heavy)" "$(S games3d)" \
        FALSE ollama "Offline AI" "AI chat without internet (very heavy, slow)" "$(S ollama)")
    RC=$?
    if [ "$CHOICE" = "$SEARCH" ]; then search_flow; continue; fi
    [ $RC -ne 0 ] && exit 0
    if [ -z "$CHOICE" ]; then
        zenity --info --text="Tick at least one app first."
        continue
    fi
    # shellcheck disable=SC2086
    confirm_big $CHOICE || continue
    # shellcheck disable=SC2086
    progress_install "Installing apps" $CHOICE
done
