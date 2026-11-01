#!/bin/bash
# AirCursor App Store: tick apps and press Install – no terminal needed.
# Installed inside Ubuntu as /usr/local/bin/aircursor-store
export GSK_RENDERER=cairo NO_AT_BRIDGE=1   # software drawing: faster and safer inside proot
SEARCH="Search 60,000+ apps"

progress_install() {   # $1 = window title, rest = ids/packages for ubuntu-extras
    local title="$1"; shift
    ( sudo /usr/local/bin/ubuntu-extras install "$@" 2>&1 \
        | sed -u -r 's/\x1b\[[0-9;]*m//g; s/^/# /' ) \
        | zenity --progress --pulsate --auto-close --no-cancel --width=620 --title="$title" \
            --text="Installing... (internet chahiye, kuch minute lag sakte hain)"
    zenity --info --width=360 --title="App Store" \
        --text="Ho gaya ✅\n\nApps Start menu me milenge.\n(Kuch fail hua ho to dobara try karo.)"
}

search_flow() {
    local term rows sel
    term=$(zenity --entry --title="$SEARCH" --width=420 \
        --text="App ka naam ya kaam likho (English), jaise:\nchess, paint, music, pdf, calculator") || return
    [ -z "$term" ] && return
    rows=()
    while IFS= read -r line; do
        rows+=(FALSE "${line%% - *}" "${line#* - }")
    done < <(apt-cache search -- "$term" 2>/dev/null | head -n 80)
    if [ ${#rows[@]} -eq 0 ]; then
        zenity --info --text="'$term' ke liye kuch nahi mila. Doosra shabd try karo."
        return
    fi
    sel=$(zenity --list --checklist --title="Results: $term" --width=760 --height=560 \
        --text="Jo chahiye tick karo, phir Install dabao" --ok-label="Install" --cancel-label="Back" \
        --column="✓" --column="Package" --column="Kya hai" --print-column=2 --separator=" " "${rows[@]}") || return
    [ -n "$sel" ] && progress_install "Installing $sel" $sel
}

while true; do
    CHOICE=$(zenity --list --checklist --title="AirCursor App Store" --width=760 --height=600 \
        --text="Apps tick karo aur Install dabao. (Windows .exe nahi chalte – yeh Linux apps hain)" \
        --ok-label="Install" --cancel-label="Close" --extra-button="$SEARCH" \
        --column="✓" --column="id" --column="App" --column="Kya hai" --hide-column=2 --print-column=2 --separator=" " \
        FALSE libreoffice "LibreOffice" "Word / Excel / PowerPoint files" \
        FALSE gimp "GIMP" "Photo editing (Photoshop jaisa)" \
        FALSE vlc "VLC" "Video aur music player" \
        FALSE gdrive "Google Drive" "15 GB free cloud storage" \
        FALSE shotcut "Shotcut" "Video editor (simple)" \
        FALSE kdenlive "Kdenlive" "Video editor (advanced, heavy)" \
        FALSE inkscape "Inkscape" "Logo / poster design" \
        FALSE vscode "VS Code" "Code editor" \
        FALSE claude "Claude Code" "AI coding (Claude Pro/API chahiye)" \
        FALSE python "Python" "Programming seekho" \
        FALSE games "Games pack" "SuperTux, Chess, Solitaire, OpenTTD, Tetris..." \
        FALSE luanti "Luanti" "Minecraft jaisa game (thoda heavy)" \
        FALSE games3d "3D games" "SuperTuxKart racing + Luanti (heavy)" \
        FALSE blender "Blender" "3D modelling (sirf basic kaam)" \
        FALSE ollama "Ollama" "Offline AI chat (phone par dheere)")
    RC=$?
    if [ "$CHOICE" = "$SEARCH" ]; then search_flow; continue; fi
    [ $RC -ne 0 ] && exit 0
    if [ -z "$CHOICE" ]; then
        zenity --info --text="Pehle kam se kam ek app tick karo."
        continue
    fi
    progress_install "Installing apps" $CHOICE
done
