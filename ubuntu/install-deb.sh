#!/bin/bash
# Double-click a downloaded .deb file to install it (like a Windows setup file).
# Installed inside Ubuntu as /usr/local/bin/aircursor-install-deb
export GSK_RENDERER=cairo NO_AT_BRIDGE=1
F="${1:-}"
[ -f "$F" ] || { zenity --error --text="File nahi mili: $F"; exit 1; }
ARCH=$(dpkg-deb -f "$F" Architecture 2>/dev/null)
NAME=$(dpkg-deb -f "$F" Package 2>/dev/null)
if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "all" ]; then
    zenity --error --width=420 --text="Yeh app phone ke processor (ARM64) ke liye nahi hai (yeh: ${ARCH:-unknown}).\n\nWebsite se 'ARM64' / 'aarch64' wala .deb download karo."
    exit 1
fi
zenity --question --width=380 --title="Install app" --ok-label="Install" --cancel-label="Cancel" \
    --text="Yeh app install karein?\n\n${NAME:-$(basename "$F")}" || exit 0
( sudo apt-get install -y "$(readlink -f "$F")" 2>&1 | sed -u -r 's/\x1b\[[0-9;]*m//g; s/^/# /' ) \
    | zenity --progress --pulsate --auto-close --no-cancel --width=620 --title="Installing ${NAME:-app}"
if dpkg -s "$NAME" >/dev/null 2>&1; then
    zenity --info --text="Installed ✅ – Start menu me dekho."
else
    zenity --error --text="Install nahi hua. Shayad internet ya koi zaroori cheez missing hai."
fi
