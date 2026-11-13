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
# Heavy apps (3D, video and photo tools) get the same speed profile as games – smaller desktop while
# they run, background helpers off, OpenGL on its own thread – but no FPS overlay.
HEAVY=0
case "${ID,,} ${NAME,,}" in
    *blender*|*kdenlive*|*openshot*|*shotcut*|*krita*|*obs-studio*|*com.obsproject*|*darktable*|*godot*|*scribus*|*stellarium*)
        HEAVY=1; GAME=1 ;;
esac
GAME_W=960; [ $HEAVY = 1 ] && GAME_W=1120
# Big 3D games draw every pixel on the CPU: a smaller picture is the one thing that really adds FPS
# (the app sharpens it on the phone's GPU).
case "${ID,,} ${NAME,,}" in
    *supertuxkart*|*torcs*|*trigger*|*openarena*|*warzone*|*megaglest*|*tuxracer*|*etr*|*neverball*|*neverputt*|*bzflag*|*minetest*|*luanti*|*foobillard*)
        [ $HEAVY = 0 ] && GAME_W=800 ;;
esac

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
BROWSER=0   # a Chromium web browser (Brave, Chromium, Vivaldi…), not an Electron app
if [ -n "$REAL" ]; then
    D=$(dirname "$REAL")
    if [ -e "$D/chrome_100_percent.pak" ] || [ -e "$D/chrome-sandbox" ] || [ -e "$D/resources.pak" ]; then
        ELECTRON=1
        [ -e "$D/resources/app.asar" ] || [ -d "$D/resources/app" ] || BROWSER=1
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
        if [ $i -eq $P ] && [ $BROWSER = 1 ]; then
            # Browsers: keep WebGL working (websites' "verify you are human" checks need it), draw
            # pages on the CPU; no warning bar about the sandbox flag. Second try: no GPU at all.
            cmd+=(--no-sandbox --test-type)
            # Fewer browser programs: one per site (Chromium's "site isolation") makes YouTube and AI
            # sites start 6–12 programs – too much CPU, and Android closes programs above its limit,
            # so pages never finish loading. A few shared ones are much faster here. No smooth-scroll
            # animation (every wheel step drawn at once: feels quicker with a CPU-drawn screen).
            cmd+=(--renderer-process-limit=3 --disable-site-isolation-trials
                --disable-features=IsolateOrigins,site-per-process,BackForwardCache --disable-smooth-scrolling)
            if [ "$safe" = 0 ]; then
                cmd+=(--no-zygote --ignore-gpu-blocklist --disable-gpu-compositing)
            else
                cmd+=(--disable-gpu --in-process-gpu)
            fi
        elif [ $i -eq $P ] && [ $HEAVY = 1 ] && [[ "${ARGS[$i]}" == *blender* ]] && [ ! -f /root/.config/cloudpc/no-blender-preset ]; then
            # Blender on a phone's CPU-drawn screen: the cheap viewport settings (each one only if this
            # Blender version has it). Turn off: create ~/.config/cloudpc/no-blender-preset
            cmd+=(--python-expr $'import bpy\nP = bpy.context.preferences\nfor o, k, v in ((P.system, "viewport_aa", "OFF"), (P.system, "anisotropic_filter", "FILTER_0"), (P.system, "use_overlay_smooth_wire", False), (P.system, "memory_cache_limit", 1024), (P.edit, "undo_steps", 16), (P.view, "smooth_view", 0), (P.view, "show_splash", False)):\n    try:\n        setattr(o, k, v)\n    except Exception:\n        pass\n')
        elif [ $i -eq $P ] && [ $ELECTRON = 1 ]; then
            # Electron apps – first try: no zygote and no GPU process = 3 programs fewer, software
            # drawing. Second try: Chromium's normal start (only the sandbox off).
            cmd+=(--no-sandbox)
            [ "$safe" = 0 ] && cmd+=(--no-zygote --disable-gpu --in-process-gpu)
        fi
    done
    t0=$(date +%s)
    if [ "$safe" = 1 ]; then
        # Safe mode: simplest drawing paths for GTK, Qt and OpenGL.
        env GSK_RENDERER=cairo QT_X11_NO_MITSHM=1 QT_QUICK_BACKEND=software QT_XCB_GL_INTEGRATION=none \
            LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe GDK_BACKEND=x11 "${cmd[@]}" </dev/null >"$LOG" 2>&1
    elif [ $GAME = 1 ]; then
        # Games: OpenGL work on its own CPU thread; FPS overlay + steady FPS limit (MangoHud).
        # AirBoost → Graphics: the phone's GPU through VirGL, or the Snapdragon GPU directly (Turnip).
        local gpu=()
        local mode
        mode=$(cat /etc/cloudpc/gpu-mode 2>/dev/null)
        if [ "$mode" = turnip ] && [ -f /opt/aircursor-turnip/gpu.env ] && [ -e /dev/kgsl-3d0 ]; then
            # Snapdragon phones: OpenGL through Zink on Turnip, straight to the Adreno GPU.
            mapfile -t gpu < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' /opt/aircursor-turnip/gpu.env)
        elif [ "$mode" = virgl ] && [ -S /tmp/.virgl_test ]; then
            gpu=(GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0)
        fi
        if [ $HEAVY = 0 ] && command -v mangohud >/dev/null 2>&1 && [ ! -f /root/.config/cloudpc/no-game-overlay ]; then
            env "${gpu[@]}" mesa_glthread=true MANGOHUD_CONFIGFILE=/etc/cloudpc/MangoHud.conf \
                mangohud --dlsym "${cmd[@]}" </dev/null >"$LOG" 2>&1
        else
            env "${gpu[@]}" mesa_glthread=true "${cmd[@]}" </dev/null >"$LOG" 2>&1
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

# Game mode: background helpers and animations off while games run (frees CPU for FPS).
[ $GAME = 1 ] && command -v cloudpc-light >/dev/null 2>&1 && (cloudpc-light 0 >/dev/null 2>&1 &)

# Game resolution: a big desktop (Balanced / Quality profile) makes 3D games draw up to 2.8× more
# pixels on the CPU. While the game runs the desktop is 960 wide (the Game profile, sharpened by the
# app); afterwards it goes back. Off: create ~/.config/cloudpc/no-game-resolution
GAME_RES=""
if [ $GAME = 1 ] && [ ! -f /root/.config/cloudpc/no-game-resolution ] && command -v cloudpc-resize >/dev/null 2>&1; then
    CUR=$(xrandr 2>/dev/null | awk '/ current / {gsub(",", "", $10); print $8 " " $10; exit}')
    CW=${CUR% *}; CH=${CUR#* }
    # A game started meanwhile by another launcher (still running) already changed it: leave it.
    OWNER=$(cut -d' ' -f1 /tmp/cloudpc-game-res 2>/dev/null)
    if [ -n "$OWNER" ] && kill -0 "$OWNER" 2>/dev/null; then CUR=""; fi
    if [ -n "$CUR" ] && [ "$CW" -gt "$GAME_W" ] 2>/dev/null; then
        NH=$(( CH * GAME_W / CW / 2 * 2 ))
        if cloudpc-resize "$GAME_W" "$NH" >/dev/null 2>&1; then
            GAME_RES="$CW $CH"
            echo "$$ $GAME_RES" > /tmp/cloudpc-game-res
        fi
    fi
fi
# A game is on: the phone hides its menu bar and dock (the whole screen for the game) and the
# session helper rests.
GAME_ON=0
if [ $GAME = 1 ] && [ $HEAVY = 0 ]; then echo "$$" > /tmp/cloudpc-game-on; GAME_ON=1; fi
restore_resolution() {
    if [ $GAME_ON = 1 ]; then
        GAME_ON=0
        [ "$(cat /tmp/cloudpc-game-on 2>/dev/null)" = "$$" ] && rm -f /tmp/cloudpc-game-on
    fi
    [ -n "$GAME_RES" ] || return 0
    rm -f /tmp/cloudpc-game-res
    cloudpc-resize $GAME_RES >/dev/null 2>&1
    GAME_RES=""
}

trap 'restore_resolution; rm -f "$LOG"' EXIT
run 0
if [ $RC -eq 137 ] && [ "$LIMIT" != off ]; then
    echo "CLOUDPC_APP: $NAME was closed by Android (program limit, $(programs) running)" | tee -a /tmp/cloudpc-app-errors.log >&2
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
        echo "CLOUDPC_APP: $NAME could not open (code $RC): $(grep -v '^[[:space:]]*$' "$LOG" | tail -n 3 | tr '\n' ' ' | cut -c1-300)" |
            tee -a /tmp/cloudpc-app-errors.log >&2
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
