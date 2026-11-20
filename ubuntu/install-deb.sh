#!/bin/bash
# Double-click a downloaded .deb file to install it (like a Windows setup file).
# Installed inside Ubuntu as /usr/local/bin/aircursor-install-deb
export GSK_RENDERER=cairo NO_AT_BRIDGE=1
export LANG="${LANG:-C.UTF-8}"; [ "$LANG" = "C" ] || [ "$LANG" = "POSIX" ] && export LANG=C.UTF-8
F="${1:-}"
[ -f "$F" ] || { zenity --error --text="File not found: $F"; exit 1; }
F=$(readlink -f "$F")
ARCH=$(dpkg-deb -f "$F" Architecture 2>/dev/null)
NAME=$(dpkg-deb -f "$F" Package 2>/dev/null)
HERE=$(dpkg --print-architecture)   # arm64 on phones

installed() { dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q "install ok installed"; }
# Shows the install's output in a progress window; the full text is kept in $LOG.
LOG=$(mktemp /tmp/install-app.XXXXXX)
trap 'rm -f "$LOG"' EXIT
progress() {   # $1 = title, rest = command
    local title="$1"; shift
    ( "$@" 2>&1 | tee "$LOG" | grep --line-buffered -v '^@@' | sed -u -r 's/\x1b\[[0-9;]*m//g; s/^/# /' ) \
        | zenity --progress --pulsate --auto-close --no-cancel --width=620 --title="$title"
}
# The lines that say what went wrong (apt's "E:" lines, dpkg errors), else the last lines.
why() {
    local e
    e=$(grep -E '^(E:|dpkg: error|dpkg: dependency problems|Errors were encountered| [^ ].* : Depends:)' "$LOG" | tail -n 6)
    [ -n "$e" ] || e=$(grep -vE '^[[:space:]]*$|^@@' "$LOG" | tail -n 6)
    printf '%s' "$e" | cut -c1-160
}
done_message() {
    if installed "$NAME"; then
        command -v add_shortcuts >/dev/null && add_shortcuts "$(comm -13 <(echo "$BEFORE") <(ls /usr/share/applications/*.desktop 2>/dev/null | sort))"
        zenity --info --text="Installed. It is on the desktop and in the Start menu."
    else
        zenity --error --no-markup --width=620 --title="Not installed" \
            --text="The app could not be installed.

$(why)

Check the internet connection and the free space, then try again."
        exit 1
    fi
}

# Desktop shortcut + Start menu favourite for the new app (same as the App Store).
STORE=/usr/local/lib/cloudpc/app-store.sh
[ -f "$STORE" ] || STORE=/usr/local/bin/aircursor-store
grep -q AIRCURSOR_STORE_LIB "$STORE" 2>/dev/null && AIRCURSOR_STORE_LIB=1 . "$STORE"
BEFORE=$(ls /usr/share/applications/*.desktop 2>/dev/null | sort)

if [ "$ARCH" != "$HERE" ] && [ "$ARCH" != "all" ]; then
    # The computer version of an app the Air Store has for phones: install that from its maker.
    case "$NAME" in
        claude-desktop) ID=claude-desktop; LABEL="Claude Desktop (Anthropic)" ;;
        antigravity) ID=antigravity-ide; LABEL="Antigravity IDE (Google)" ;;
        brave-browser) ID=brave; LABEL="Brave browser" ;;
        *) ID="" ;;
    esac
    if [ -n "$ID" ]; then
        zenity --question --width=460 --title="Install app" --ok-label="Install" --cancel-label="Cancel" \
            --text="This file is the version for computers (${ARCH}).\n\nInstall the phone version (ARM64) of ${LABEL} from its maker instead?" || exit 0
        progress "Installing $LABEL" sudo ubuntu-extras install "$ID"
        done_message
        exit 0
    fi
    zenity --error --width=460 --text="This app is not made for the phone's processor (it is: ${ARCH:-unknown}).\n\nDownload the ARM64 (aarch64) .deb from the website instead, or look for the app in Air Store."
    exit 1
fi

zenity --question --width=380 --title="Install app" --ok-label="Install" --cancel-label="Cancel" \
    --text="Install this app?\n\n${NAME:-$(basename "$F")}" || exit 0
# A fresh app list first (old lists name files the servers no longer have), and without the
# "recommended" extras: much smaller downloads on a phone (Claude Desktop's would add a virtual
# machine phones cannot run).
install_file() {
    sudo apt-get update || true
    sudo apt-get install -y --no-install-recommends "$F"
}
progress "Installing ${NAME:-app}" install_file
done_message
