#!/bin/bash
# Double-click a downloaded AppImage to install it (Cursor, LM Studio and many other apps ship one).
# An AppImage normally runs through FUSE, which apps cannot use on a phone, so it is unpacked once
# into /opt/appimages/<app> and gets a Start menu entry and a desktop icon like any other app.
# Installed inside Ubuntu as /usr/local/bin/aircursor-install-appimage
export GSK_RENDERER=cairo NO_AT_BRIDGE=1
export LANG="${LANG:-C.UTF-8}"; [ "$LANG" = "C" ] || [ "$LANG" = "POSIX" ] && export LANG=C.UTF-8
F="${1:-}"
QUIET="${AIRCURSOR_QUIET:-0}"   # tests and scripts: no questions
say() { [ "$QUIET" = 1 ] && { echo "$2"; return 0; }; zenity "--$1" --no-markup --width=500 --text="$2" >/dev/null 2>&1; }
[ -f "$F" ] || { say error "File not found: $F"; exit 1; }
F=$(readlink -f "$F")
DEST_ROOT="${AIRCURSOR_APPIMAGE_DIR:-/opt/appimages}"
MENU="${AIRCURSOR_MENU_DIR:-/usr/share/applications}"

# Which processor is it made for? (an ELF program: bytes 18-19)
case "$(od -An -t x1 -j 18 -N 2 "$F" 2>/dev/null | tr -d ' \n')" in
    b700) FOR=aarch64 ;;
    3e00) FOR=x86_64 ;;
    *) FOR=unknown ;;
esac
if [ "$FOR" != "$(uname -m)" ]; then
    say error "This AppImage is not made for the phone's processor (it is: $FOR).

Download the ARM64 (aarch64) AppImage from the website instead, or look for the app in Air Store."
    exit 1
fi
FILE_NAME=$(basename "$F")
if [ "$QUIET" != 1 ]; then
    zenity --question --no-markup --width=420 --title="Install app" --ok-label="Install" --cancel-label="Cancel" \
        --text="Install this app?

$FILE_NAME" >/dev/null 2>&1 || exit 0
fi

WORK=$(mktemp -d /tmp/appimage.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
unpack() {
    cp "$F" "$WORK/app.AppImage" && chmod +x "$WORK/app.AppImage" || return 1
    # The AppImage's own unpacker (no FUSE needed for this)…
    (cd "$WORK" && ./app.AppImage --appimage-extract >/dev/null 2>&1) && [ -d "$WORK/squashfs-root" ] && return 0
    rm -rf "$WORK/squashfs-root"
    # …else unsquashfs from where the packed files start (right after the ELF program).
    command -v unsquashfs >/dev/null 2>&1 || apt-get install -y --no-install-recommends squashfs-tools >/dev/null 2>&1
    local off
    off=$(python3 -I -c '
import struct, sys
with open(sys.argv[1], "rb") as f:
    h = f.read(64)
if h[:4] != b"\x7fELF":
    sys.exit(1)
if h[4] == 2:
    shoff, = struct.unpack_from("<Q", h, 0x28); shentsize, shnum = struct.unpack_from("<HH", h, 0x3A)
else:
    shoff, = struct.unpack_from("<I", h, 0x20); shentsize, shnum = struct.unpack_from("<HH", h, 0x2E)
print(shoff + shentsize * shnum)' "$F") || return 1
    unsquashfs -q -d "$WORK/squashfs-root" -o "$off" "$F" >/dev/null 2>&1 && [ -d "$WORK/squashfs-root" ]
}
if [ "$QUIET" = 1 ]; then
    unpack; OK=$?
else
    ( unpack; echo $? > "$WORK/rc" ) | zenity --progress --pulsate --auto-close --no-cancel --width=420 \
        --title="Installing" --text="Unpacking $FILE_NAME…" >/dev/null 2>&1
    OK=$(cat "$WORK/rc" 2>/dev/null || echo 1)
fi
R="$WORK/squashfs-root"
if [ "$OK" != 0 ] || [ ! -e "$R/AppRun" ]; then
    say error "$FILE_NAME could not be unpacked. The file may be damaged: download it again."
    exit 1
fi

# The app's own menu entry (top level of the AppImage) gives its name, icon and link types.
DESK=$(find "$R" -maxdepth 1 -name '*.desktop' | head -n 1)
ENTRY="$WORK/entry.desktop"
: > "$ENTRY"
[ -n "$DESK" ] && sed -n '1,/^\[Desktop Action/p' "$DESK" > "$ENTRY" 2>/dev/null   # main section only
field() { grep -m1 "^$1=" "$ENTRY" | cut -d= -f2-; }
NAME=$(field Name)
BASE=${DESK##*/}; BASE=${BASE%.desktop}
[ -n "$BASE" ] || BASE=${FILE_NAME%.*}
# A short id: "Cursor-1.2.3-aarch64.AppImage" / "cursor.desktop" → "cursor"
ID=$(printf '%s' "$BASE" | tr 'A-Z' 'a-z' | sed -E 's/[-_ .]?(v?[0-9][0-9.]*)?([-_.](aarch64|arm64|x86_64|amd64))?$//; s/[^a-z0-9.-]+/-/g; s/^[.-]+|-+$//g')
# Never "." or ".." (that would be /opt/appimages itself or /opt): a plain name only.
case "$ID" in ""|*..*) ID=app ;; esac
NAME=${NAME:-$BASE}
DEST="$DEST_ROOT/$ID"
mkdir -p "$DEST_ROOT"
rm -rf "$DEST.old"
[ -e "$DEST" ] && mv "$DEST" "$DEST.old"
if ! mv "$R" "$DEST"; then
    [ -e "$DEST.old" ] && mv "$DEST.old" "$DEST"
    say error "$NAME could not be installed: not enough free space?"
    exit 1
fi
rm -rf "$DEST.old"
chmod 755 "$DEST"

# Icon: the one the entry names, else the AppImage's own picture.
ICON_NAME=$(field Icon)
ICON=""
for c in "$DEST/$ICON_NAME.png" "$DEST/$ICON_NAME.svg" "$DEST/$ICON_NAME" "$DEST/.DirIcon"; do
    [ -n "$ICON_NAME" ] || [ "$c" = "$DEST/.DirIcon" ] || continue
    if [ -f "$c" ]; then ICON=$(readlink -f "$c"); break; fi
done
[ -n "$ICON" ] || ICON=application-x-executable
# The command: the AppImage's starter with the entry's own arguments (%U for links, %F for files).
ARGS=$(field Exec | awk '{ $1 = ""; sub(/^ /, ""); print }')
OUT="$MENU/appimage-$ID.desktop"
{
    echo "[Desktop Entry]"
    echo "Type=Application"
    echo "Name=$NAME"
    c=$(field Comment); [ -n "$c" ] && echo "Comment=$c"
    echo "Exec=$DEST/AppRun${ARGS:+ $ARGS}"
    echo "Icon=$ICON"
    c=$(field Terminal); echo "Terminal=${c:-false}"
    c=$(field Categories); echo "Categories=${c:-Utility;}"
    c=$(field MimeType); [ -n "$c" ] && echo "MimeType=$c"
    c=$(field StartupWMClass); [ -n "$c" ] && echo "StartupWMClass=$c"
    echo "X-AppImage-File=$FILE_NAME"
} > "$OUT"
chmod 644 "$OUT"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$MENU" 2>/dev/null
# Start menu copy that opens through cloudpc-launch (Electron flags), desktop icon, favourite.
command -v cloudpc-apps >/dev/null 2>&1 && cloudpc-apps >/dev/null 2>&1
echo "Installed: $NAME ($DEST)"
say info "$NAME is installed. It is on the desktop and in the Start menu."
exit 0
