#!/bin/bash
# AirCursor PC app launcher: cloudpc-launch <app.desktop> <command> [args...]
# Every app in the Start menu and on the desktop opens through this. It
#  - shows "Opening …" right away (big apps need a while on a phone),
#  - gives Chromium/Electron apps (VS Code, browsers, chat apps) the flags they need here,
#  - gives games the game settings (FPS overlay + FPS limit when MangoHud is installed),
#  - tries once more with safe settings if the app closes at once,
#  - and explains the error instead of silently doing nothing.
. /tmp/cloudpc-session.env 2>/dev/null
export LANG="${LANG:-C.UTF-8}" NO_AT_BRIDGE=1
ID="$1"; shift
[ $# -gt 0 ] || exit 1

SRC=/usr/share/applications/$ID
[ -f "$SRC" ] || SRC=/usr/local/share/applications/$ID
field() { grep -m1 "^$1=" "$SRC" 2>/dev/null | cut -d= -f2-; }
NAME=$(field Name); NAME=${NAME:-${ID%.desktop}}
ICON=$(field Icon)
GAME=0; field Categories | grep -qi 'game' && GAME=1

# Which argument is the program? (skip "env VAR=value")
ARGS=("$@")
P=0
if [ "${ARGS[0]}" = env ]; then
    P=1
    while [ $P -lt ${#ARGS[@]} ] && [[ "${ARGS[$P]}" == *=* ]]; do P=$((P + 1)); done
fi
BIN=$(command -v "${ARGS[$P]}" 2>/dev/null)
REAL=$(readlink -f "$BIN" 2>/dev/null)
ELECTRON=0
if [ -n "$REAL" ]; then
    D=$(dirname "$REAL")
    if [ -e "$D/chrome_100_percent.pak" ] || [ -e "$D/chrome-sandbox" ] || [ -e "$D/resources.pak" ]; then
        ELECTRON=1
    elif [ "$(stat -c %s "$REAL" 2>/dev/null || echo 99999)" -lt 8000 ] &&
        grep -qsE 'chrome-sandbox|/usr/share/code/|electron' "$REAL"; then
        ELECTRON=1   # a small start script for an Electron app
    fi
fi

LOG=$(mktemp /tmp/cloudpc-app.XXXXXX)
trap 'rm -f "$LOG"' EXIT

# Android 12+ lets an app run only 32 programs; it closes the rest at once (signal 9).
LIMIT=$(cat /etc/cloudpc/child-limit 2>/dev/null || echo unknown)
programs() { ls -d /proc/[0-9]* 2>/dev/null | wc -l; }
N=$(programs)
HINT=""
if [ "$LIMIT" != off ] && [ "$N" -ge 24 ]; then
    HINT="$N of 32 programs are running – close windows you do not need first."
fi
notify-send -a "AirCursor PC" -i "${ICON:-system-run}" -t 5000 "Opening $NAME…" \
    "${HINT:-Big apps can take up to a minute the first time.}" >/dev/null 2>&1 &

run() {   # $1 = 1 for the safe second try
    local safe="$1" cmd=() i t0
    for ((i = 0; i < ${#ARGS[@]}; i++)); do
        cmd+=("${ARGS[$i]}")
        if [ $i -eq $P ] && [ $ELECTRON = 1 ]; then
            # First try: no zygote and no GPU process = 3 programs fewer, software drawing.
            # Second try: Chromium's normal start (only the sandbox off).
            cmd+=(--no-sandbox)
            [ "$safe" = 0 ] && cmd+=(--no-zygote --disable-gpu --in-process-gpu)
        fi
    done
    t0=$(date +%s)
    if [ "$safe" = 1 ]; then
        # Safe mode: simplest drawing paths for GTK, Qt and OpenGL.
        env GSK_RENDERER=cairo QT_X11_NO_MITSHM=1 QT_QUICK_BACKEND=software QT_XCB_GL_INTEGRATION=none \
            LIBGL_ALWAYS_SOFTWARE=1 GDK_BACKEND=x11 "${cmd[@]}" </dev/null >"$LOG" 2>&1
    elif [ $GAME = 1 ]; then
        # Games: OpenGL work on its own CPU thread; FPS overlay + steady FPS limit (MangoHud).
        if command -v mangohud >/dev/null 2>&1 && [ ! -f /root/.config/cloudpc/no-game-overlay ]; then
            env mesa_glthread=true MANGOHUD_CONFIGFILE=/etc/cloudpc/MangoHud.conf \
                mangohud --dlsym "${cmd[@]}" </dev/null >"$LOG" 2>&1
        else
            env mesa_glthread=true "${cmd[@]}" </dev/null >"$LOG" 2>&1
        fi
    else
        "${cmd[@]}" </dev/null >"$LOG" 2>&1
    fi
    RC=$?
    DUR=$(( $(date +%s) - t0 ))
}

limit_message() {
    zenity --error --no-markup --width=560 --title="$NAME" \
        --text="Android closed $NAME.

This phone lets one app run only 32 programs at once (now: $(programs)). The desktop needs about 20, and $NAME needs several more.

Fix it once (2 minutes): open the AirCursor app → AirCursor PC → \"Let big apps run\" and follow the steps (Developer options → Disable child process restrictions).

Until then: close other windows (for example Firefox) and open $NAME again.
(Already lifted? Then the phone ran out of memory: close other phone apps and try again.)" >/dev/null 2>&1
}

run 0
if [ $RC -eq 137 ] && [ "$LIMIT" != off ]; then
    echo "CLOUDPC_APP: $NAME was closed by Android (program limit, $(programs) running)" >&2
    limit_message
    exit $RC
fi
if [ $RC -ne 0 ] && [ $RC -ne 130 ] && [ $RC -ne 143 ] && [ $DUR -lt 25 ]; then
    run 1
    if [ $RC -eq 137 ] && [ "$LIMIT" != off ]; then
        limit_message
        exit $RC
    fi
    if [ $RC -ne 0 ] && [ $RC -ne 130 ] && [ $RC -ne 143 ] && [ $DUR -lt 25 ]; then
        echo "CLOUDPC_APP: $NAME could not open (code $RC): $(grep -v '^[[:space:]]*$' "$LOG" | tail -n 3 | tr '\n' ' ' | cut -c1-300)" >&2
        MSG=$(grep -v '^[[:space:]]*$' "$LOG" | grep -viE 'libEGL warning|dbind-WARNING|Gtk-Message|^ATTENTION' |
            tail -n 8 | cut -c1-150)
        zenity --error --no-markup --width=560 --title="$NAME" \
            --text="$NAME could not open (code $RC).

${MSG:-No details.}

Tips: try once more. If it keeps failing: toolbar → Close → Stop desktop, open it again, then open the app." \
            >/dev/null 2>&1
    fi
fi
exit $RC
