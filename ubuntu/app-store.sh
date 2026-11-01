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
        games) echo 250 ;; luanti) echo 60 ;; games3d) echo 650 ;; blender) echo 350 ;; ollama) echo 1500 ;;
        *) echo 50 ;;
    esac
}

progress_install() {   # $1 = window title, rest = ids/packages for ubuntu-extras
    local title="$1"; shift
    # Numbers from "@@PCT n" move the bar; "[i/n] Installing X" is kept as the heading of every line.
    ( sudo /usr/local/bin/ubuntu-extras install "$@" 2>&1 | tee "$LOG" \
        | sed -u -r 's/\x1b\[[0-9;]*m//g
            /^@@PCT /{s/^@@PCT //;p;d}
            /^\[[0-9]+\/[0-9]+\] /{h;s/^/# /;p;d}
            G
            s/^(.*)\n(.*)$/# \2  –  \1/' ) \
        | zenity --progress --auto-close --no-cancel --width=640 --title="$title" --text="Starting…"
    local failed
    failed=$(sed -r 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -E '^(Failed: |  - )' | sed -r 's/^Failed: //; s/^  - //' | head -n 8)
    if [ -n "$failed" ]; then
        zenity --warning --width=420 --title="App Store" \
            --text="Some apps could not be installed:\n\n$failed\n\nCheck the internet connection and try them again."
    else
        zenity --info --width=380 --title="App Store" --text="Done. Your new apps are in the Start menu."
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

while true; do
    CHOICE=$(zenity --list --checklist --title="App Store" --width=780 --height=620 \
        --text="Tick the apps you want and tap Install. (Windows .exe programs do not run here – these are Linux apps.)" \
        --ok-label="Install" --cancel-label="Close" --extra-button="$SEARCH" \
        --column="✓" --column="id" --column="App" --column="What it is" --column="Size" \
        --hide-column=2 --print-column=2 --separator=" " \
        FALSE libreoffice "LibreOffice" "Documents, spreadsheets, presentations" "300 MB" \
        FALSE gimp "GIMP" "Photo editing" "120 MB" \
        FALSE vlc "VLC" "Video and music player" "60 MB" \
        FALSE gdrive "Google Drive" "15 GB free cloud storage" "25 MB" \
        FALSE vscode "VS Code" "Code editor" "110 MB" \
        FALSE python "Python" "Learn programming" "40 MB" \
        FALSE claude "Claude Code" "AI coding assistant (Claude account needed)" "80 MB" \
        FALSE inkscape "Inkscape" "Logos and posters" "120 MB" \
        FALSE shotcut "Shotcut" "Simple video editor" "150 MB" \
        FALSE games "Games pack" "SuperTux, Chess, Solitaire, OpenTTD, Tetris…" "250 MB" \
        FALSE luanti "Luanti" "Block building game" "60 MB" \
        FALSE kdenlive "Kdenlive" "Advanced video editor (heavy)" "450 MB" \
        FALSE blender "Blender" "3D modelling (heavy, basic use)" "350 MB" \
        FALSE games3d "3D games" "SuperTuxKart racing + Luanti (heavy)" "650 MB" \
        FALSE ollama "Offline AI" "AI chat without internet (very heavy, slow)" "1.5 GB")
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
