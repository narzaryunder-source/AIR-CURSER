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
# Old session state from a killed run can stop the panel / window manager from starting.
proot-distro login ubuntu --user __USER__ -- sh -c 'rm -rf "$HOME/.cache/sessions"' >/dev/null 2>&1
export XDG_RUNTIME_DIR="$TMPDIR"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 >/dev/null 2>&1
termux-x11 :0 >/dev/null 2>&1 &
sleep 3
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1
sleep 1
GPU_ENV=""
if [ "${1:-}" = "--gpu" ] && command -v virgl_test_server_android >/dev/null 2>&1; then
    virgl_test_server_android >/dev/null 2>&1 &
    GPU_ENV="GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0"
fi
LOG="$HOME/ubuntu-desktop.log"
echo ""
echo "  Ubuntu desktop is starting – open the Termux:X11 app (first start: 1-2 minutes)."
echo "  Stop: press CTRL+C here, or run ubuntu-stop in a new session."
echo "  (Technical messages are saved in ~/ubuntu-desktop.log)"
proot-distro login ubuntu --user __USER__ --shared-tmp -- \
    env DISPLAY=:0 PULSE_SERVER=tcp:127.0.0.1 $GPU_ENV dbus-launch --exit-with-session startxfce4 >"$LOG" 2>&1
echo "  Desktop stopped."
LAUNCH_EOF

    cat > "$PREFIX/bin/ubuntu-shell" <<'SHELL_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu terminal without the desktop (for Claude Code, Ollama, coding...). Type 'exit' to leave.
proot-distro login ubuntu --user __USER__ --shared-tmp
SHELL_EOF

    cat > "$PREFIX/bin/ubuntu-wallpaper" <<'WALL_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Sets a photo from the phone as the Ubuntu wallpaper (the photo itself is only copied).
#   ubuntu-wallpaper                         -> newest photo in Download / Pictures / Camera
#   ubuntu-wallpaper /sdcard/Download/x.jpg  -> this photo
IMG="${1:-}"
if [ -z "$IMG" ]; then
    IMG=$(ls -t /sdcard/Download/*.jpg /sdcard/Download/*.jpeg /sdcard/Download/*.png \
        /sdcard/Pictures/*.jpg /sdcard/Pictures/*.jpeg /sdcard/Pictures/*.png \
        /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -n 1)
fi
if [ -z "$IMG" ] || [ ! -r "$IMG" ]; then
    echo "Photo nahi mili. Yeh karo:"
    echo "  1) termux-setup-storage   (Allow dabao)"
    echo "  2) photo ko phone ke Download folder me save karo (.jpg ya .png)"
    echo "  3) phir: ubuntu-wallpaper"
    exit 1
fi
EXT=$(printf '%s' "${IMG##*.}" | tr 'A-Z' 'a-z')
cp "$IMG" "$TMPDIR/aircursor-wallpaper.$EXT" || exit 1
proot-distro login ubuntu --shared-tmp -- sh -c "mkdir -p /usr/share/xfce4/backdrops && \
    cp /tmp/aircursor-wallpaper.$EXT /usr/share/xfce4/backdrops/aircursor-wallpaper.$EXT && \
    ln -sf /usr/share/xfce4/backdrops/aircursor-wallpaper.$EXT /usr/share/xfce4/backdrops/xubuntu-wallpaper.png"
rm -f "$TMPDIR/aircursor-wallpaper.$EXT"
echo "Wallpaper set: $IMG"
echo "Desktop dobara chalao taaki dikhe: ubuntu-desktop"
WALL_EOF

    sed -i "s/__USER__/$U/g" "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-shell"
    chmod +x "$PREFIX/bin/ubuntu-stop" "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-shell" "$PREFIX/bin/ubuntu-wallpaper"
}
