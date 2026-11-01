#!/bin/bash
# Double-click a downloaded .deb file to install it (like a Windows setup file).
# Installed inside Ubuntu as /usr/local/bin/aircursor-install-deb
export GSK_RENDERER=cairo NO_AT_BRIDGE=1
export LANG="${LANG:-C.UTF-8}"; [ "$LANG" = "C" ] || [ "$LANG" = "POSIX" ] && export LANG=C.UTF-8
F="${1:-}"
[ -f "$F" ] || { zenity --error --text="File not found: $F"; exit 1; }
ARCH=$(dpkg-deb -f "$F" Architecture 2>/dev/null)
NAME=$(dpkg-deb -f "$F" Package 2>/dev/null)
if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "all" ]; then
    zenity --error --width=420 --text="This app is not made for the phone's processor (it is: ${ARCH:-unknown}).\n\nDownload the ARM64 (aarch64) .deb from the website instead."
    exit 1
fi
zenity --question --width=380 --title="Install app" --ok-label="Install" --cancel-label="Cancel" \
    --text="Install this app?\n\n${NAME:-$(basename "$F")}" || exit 0
# Desktop shortcut + Start menu favourite for the new app (same as the App Store).
STORE=/usr/local/lib/cloudpc/app-store.sh
[ -f "$STORE" ] || STORE=/usr/local/bin/aircursor-store
grep -q AIRCURSOR_STORE_LIB "$STORE" 2>/dev/null && AIRCURSOR_STORE_LIB=1 . "$STORE"
BEFORE=$(ls /usr/share/applications/*.desktop 2>/dev/null | sort)
( sudo apt-get install -y "$(readlink -f "$F")" 2>&1 | sed -u -r 's/\x1b\[[0-9;]*m//g; s/^/# /' ) \
    | zenity --progress --pulsate --auto-close --no-cancel --width=620 --title="Installing ${NAME:-app}"
if dpkg -s "$NAME" >/dev/null 2>&1; then
    command -v add_shortcuts >/dev/null && add_shortcuts "$(comm -13 <(echo "$BEFORE") <(ls /usr/share/applications/*.desktop 2>/dev/null | sort))"
    zenity --info --text="Installed. It is on the desktop and in the Start menu."
else
    zenity --error --text="The app could not be installed. Check the internet connection and try again."
fi
