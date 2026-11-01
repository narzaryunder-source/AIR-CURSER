#!/data/data/com.termux/files/usr/bin/bash
# Writes the Termux commands ubuntu-desktop, ubuntu-stop, ubuntu-shell and ubuntu-wallpaper.
# Used by setup.sh and fix.sh:  install_launchers <linux-user>

install_launchers() {
    local U="$1"

    cat > "$PREFIX/bin/ubuntu-stop" <<'STOP_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Stops the desktop and removes its temporary files (your files are not touched).
pkill -f "startxfce4|xfce4-session|xfwm4|xfce4-panel|xfdesktop|xfconfd|xfsettingsd|Thunar|dbus-launch|aircursor-heal" 2>/dev/null
pkill -f "virgl_test_server" 2>/dev/null
pkill -f "termux-x11|com.termux.x11" 2>/dev/null
pulseaudio --kill 2>/dev/null
termux-wake-unlock 2>/dev/null
sleep 1
pkill -9 -f "xfce4-session|xfwm4|xfce4-panel|xfdesktop|xfconfd" 2>/dev/null
rm -rf "$TMPDIR/.X11-unix" "$TMPDIR/.X0-lock" "$TMPDIR/.ICE-unix" 2>/dev/null
exit 0
STOP_EOF

    cat > "$PREFIX/bin/ubuntu-desktop" <<'LAUNCH_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Starts the Ubuntu XFCE desktop in Termux:X11.  Use --gpu for OpenGL acceleration (VirGL).
if [ "$(id -u)" = "0" ]; then
    echo "Aap Ubuntu ke andar ho (root@localhost). Pehle 'exit' likho jab tak prompt '~ \$' na ho, phir ubuntu-desktop chalao."
    exit 1
fi
ubuntu-stop >/dev/null 2>&1
# Keep Termux awake so Android does not kill it while you are in the Termux:X11 app.
termux-wake-lock 2>/dev/null
# Old session state from a killed run can stop the panel / window manager from starting.
proot-distro login ubuntu --user __USER__ -- sh -c 'rm -rf "$HOME/.cache/sessions"' >/dev/null 2>&1
export XDG_RUNTIME_DIR="$TMPDIR"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 >/dev/null 2>&1
termux-x11 :0 >/dev/null 2>&1 &
sleep 3
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1
sleep 1
# AirBoost profile (ubuntu-boost): GPU = VirGL virtual graphics card, LIGHT = fewer background jobs
BOOST_GPU=0
BOOST_LIGHT=0
[ -f "$HOME/.aircursor-boost" ] && . "$HOME/.aircursor-boost"
[ "${1:-}" = "--gpu" ] && BOOST_GPU=1
[ "${1:-}" = "--cpu" ] && BOOST_GPU=0
GPU_ENV=""
if [ "$BOOST_GPU" = "1" ] && command -v virgl_test_server_android >/dev/null 2>&1; then
    virgl_test_server_android >/dev/null 2>&1 &
    GPU_ENV="GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0"
    echo "  AirBoost: VirGL virtual graphics card ON"
fi
LOG="$HOME/ubuntu-desktop.log"
echo ""
echo "  Ubuntu desktop is starting – open the Termux:X11 app (first start: 1-2 minutes)."
echo "  Stop: press CTRL+C here, or run ubuntu-stop in a new session."
echo "  (Technical messages are saved in ~/ubuntu-desktop.log)"
proot-distro login ubuntu --user __USER__ --shared-tmp -- \
    env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games \
    DISPLAY=:0 PULSE_SERVER=tcp:127.0.0.1 AIRCURSOR_LIGHT=$BOOST_LIGHT $GPU_ENV \
    dbus-launch --exit-with-session startxfce4 >"$LOG" 2>&1
echo "  Desktop stopped."
LAUNCH_EOF

    cat > "$PREFIX/bin/ubuntu-boost" <<'BOOST_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# AirBoost: speed profiles for the Ubuntu desktop.
#   ubuntu-boost performance | balanced | quality | status
P="${1:-status}"
case "$P" in
    performance) GPU=1; LIGHT=1; SCALE="150-200%" ;;
    balanced)    GPU=1; LIGHT=1; SCALE="125-150%" ;;
    quality)     GPU=0; LIGHT=0; SCALE="100%" ;;
    status|*)
        echo "AirBoost profiles:"
        echo "  ubuntu-boost performance   VirGL GPU + light mode   (games, fastest)"
        echo "  ubuntu-boost balanced      VirGL GPU + light mode   (daily use)"
        echo "  ubuntu-boost quality       CPU graphics, everything on"
        echo "  ubuntu-benchmark           measure: is GPU or CPU faster on THIS phone?"
        if [ -f "$HOME/.aircursor-boost" ]; then echo; echo "Current:"; cat "$HOME/.aircursor-boost"; fi
        exit 0 ;;
esac
printf 'BOOST_PROFILE=%s\nBOOST_GPU=%s\nBOOST_LIGHT=%s\n' "$P" "$GPU" "$LIGHT" > "$HOME/.aircursor-boost"
echo "AirBoost profile: $P  (GPU=$GPU, light mode=$LIGHT)"
echo ""
echo "Render scaling (our 'DLSS-lite': fewer pixels = more FPS) – set once in Termux:X11:"
echo "  Termux:X11 -> settings (gear) -> Output -> Display resolution mode = scaled"
echo "  -> Display scale = $SCALE   (text bigger = fewer pixels = faster)"
echo ""
echo "Apply now:  ubuntu-desktop"
BOOST_EOF

    cat > "$PREFIX/bin/ubuntu-benchmark" <<'BENCH_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Measures graphics speed with CPU rendering and with the VirGL virtual GPU (needs the desktop running).
U=$(cat "$HOME/.aircursor-ubuntu-user" 2>/dev/null); U=${U:-user}
if [ ! -S "$TMPDIR/.X11-unix/X0" ]; then echo "Pehle desktop chalao: ubuntu-desktop (phir naye session me ubuntu-benchmark)"; exit 1; fi
echo "Preparing test (one time: installs mesa-utils)..."
proot-distro login ubuntu -- sh -c 'command -v glxgears >/dev/null || apt-get install -y mesa-utils >/dev/null 2>&1'
fps() {
    proot-distro login ubuntu --user "$U" --shared-tmp -- env DISPLAY=:0 "$@" timeout 12 glxgears 2>/dev/null \
        | grep -o '[0-9.]* FPS' | tail -n 1 | cut -d' ' -f1
}
echo "Test 1/2: CPU graphics (12 s, a window with gears appears on the desktop)..."
CPU=$(fps)
STARTED=0
if command -v virgl_test_server_android >/dev/null 2>&1; then
    if ! pgrep -f virgl_test_server >/dev/null; then virgl_test_server_android >/dev/null 2>&1 & STARTED=1; sleep 2; fi
    echo "Test 2/2: VirGL virtual GPU (12 s)..."
    GPU=$(fps GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0)
    [ "$STARTED" = 1 ] && pkill -f virgl_test_server
fi
echo ""
echo "  CPU graphics : ${CPU:-failed} FPS"
echo "  VirGL GPU    : ${GPU:-failed} FPS"
if [ -n "$GPU" ] && [ -n "$CPU" ] && awk "BEGIN{exit !($GPU > $CPU * 1.15)}"; then
    echo "  => GPU is faster on your phone:  ubuntu-boost performance"
elif [ -n "$CPU" ]; then
    echo "  => CPU is as fast or faster here:  ubuntu-boost quality   (keep light mode via settings)"
fi
BENCH_EOF

    # Let other apps (AirCursor's "Ubuntu Mode" button) start these commands in Termux.
    mkdir -p "$HOME/.termux"
    if ! grep -q '^allow-external-apps *= *true' "$HOME/.termux/termux.properties" 2>/dev/null; then
        echo 'allow-external-apps = true' >> "$HOME/.termux/termux.properties"
        termux-reload-settings 2>/dev/null
    fi

    cat > "$PREFIX/bin/ubuntu-shell" <<'SHELL_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu terminal without the desktop (for Claude Code, Ollama, coding...). Type 'exit' to leave.
proot-distro login ubuntu --user __USER__ --shared-tmp
SHELL_EOF

    cat > "$PREFIX/bin/ubuntu-wallpaper" <<'WALL_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Sets a photo from the phone as the Ubuntu wallpaper (the photo itself is only copied).
#   ubuntu-wallpaper                 -> newest photo in a folder named "ubuntu-wallpaper",
#                                       else newest in Download / Pictures / Camera
#   ubuntu-wallpaper /sdcard/x.jpg   -> this photo      ubuntu-wallpaper /sdcard/Folder -> newest in it
newest_in() {
    find "$@" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \) \
        -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2-
}
IMG="${1:-}"
if [ -n "$IMG" ] && [ -d "$IMG" ]; then
    IMG=$(newest_in "$IMG")
elif [ -z "$IMG" ]; then
    DIR=$(find /sdcard -maxdepth 3 -type d -iname 'ubuntu-wallpaper' -not -path '*/Android/*' 2>/dev/null | head -n 1)
    [ -n "$DIR" ] && IMG=$(newest_in "$DIR")
    [ -z "$IMG" ] && IMG=$(newest_in /sdcard/Download /sdcard/Pictures /sdcard/DCIM/Camera)
fi
if [ -z "$IMG" ] || [ ! -r "$IMG" ]; then
    echo "Photo nahi mili. Yeh karo:"
    echo "  1) termux-setup-storage   (Allow dabao)"
    echo "  2) photo ko phone me 'ubuntu-wallpaper' folder ya Download me rakho (.jpg .png .webp)"
    echo "  3) phir: ubuntu-wallpaper"
    exit 1
fi
EXT=$(printf '%s' "${IMG##*.}" | tr 'A-Z' 'a-z')
cp "$IMG" "$TMPDIR/aircursor-wallpaper.$EXT" || exit 1
# .webp needs an extra image loader inside Ubuntu
[ "$EXT" = "webp" ] && proot-distro login ubuntu -- sh -c \
    "dpkg -s webp-pixbuf-loader >/dev/null 2>&1 || apt-get install -y webp-pixbuf-loader >/dev/null 2>&1"
proot-distro login ubuntu --shared-tmp -- sh -c "mkdir -p /usr/share/xfce4/backdrops && \
    cp /tmp/aircursor-wallpaper.$EXT /usr/share/xfce4/backdrops/aircursor-wallpaper.$EXT && \
    ln -sf /usr/share/xfce4/backdrops/aircursor-wallpaper.$EXT /usr/share/xfce4/backdrops/xubuntu-wallpaper.png"
rm -f "$TMPDIR/aircursor-wallpaper.$EXT"
echo "Wallpaper set: $IMG"
echo "Desktop dobara chalao taaki dikhe: ubuntu-desktop"
WALL_EOF

    sed -i "s/__USER__/$U/g" "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-shell"
    chmod +x "$PREFIX/bin/ubuntu-stop" "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-shell" "$PREFIX/bin/ubuntu-wallpaper" \
        "$PREFIX/bin/ubuntu-boost" "$PREFIX/bin/ubuntu-benchmark"
}
