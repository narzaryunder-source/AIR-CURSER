#!/data/data/com.termux/files/usr/bin/bash
# Repairs a half-started Ubuntu desktop (no panel, X cursor) after Android killed Termux.
# Only temporary files and the XFCE session cache are cleaned – no personal files are touched.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/fix.sh -o fix.sh && bash fix.sh
if [ "$(id -u)" = "0" ]; then
    echo "Aap abhi Ubuntu ke andar ho (prompt: root@localhost). Pehle 'exit' likho jab tak prompt '~ \$' na ho, phir yeh command chalao."
    exit 1
fi
U=$(grep -o -- '--user [a-z0-9_-]*' "$PREFIX/bin/ubuntu-desktop" 2>/dev/null | head -n 1 | awk '{print $2}')
U=${U:-user}
echo "==> Updating the ubuntu-desktop and ubuntu-stop commands (user: $U)"

cat > "$PREFIX/bin/ubuntu-stop" <<'STOP_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Stops the desktop and removes its temporary files (your files are not touched).
pkill -f "startxfce4|xfce4-session|xfwm4|xfce4-panel|xfdesktop|xfconfd|xfsettingsd|Thunar|dbus-launch" 2>/dev/null
pkill -f "virgl_test_server" 2>/dev/null
pkill -f "termux-x11" 2>/dev/null
pkill -f "com.termux.x11" 2>/dev/null
pulseaudio --kill 2>/dev/null
sleep 1
rm -rf "$TMPDIR/.X11-unix" "$TMPDIR/.X0-lock" "$TMPDIR/.ICE-unix" 2>/dev/null
exit 0
STOP_EOF

cat > "$PREFIX/bin/ubuntu-desktop" <<LAUNCH_EOF
#!/data/data/com.termux/files/usr/bin/bash
# Starts the Ubuntu XFCE desktop in Termux:X11.  Use --gpu for OpenGL acceleration (VirGL).
if [ "\$(id -u)" = "0" ]; then
    echo "Aap Ubuntu ke andar ho. Pehle 'exit' likho jab tak prompt '~ \\\$' na ho, phir ubuntu-desktop chalao."
    exit 1
fi
ubuntu-stop >/dev/null 2>&1
# Old session state from a killed run can stop the panel / window manager from starting.
proot-distro login ubuntu --user $U -- sh -c 'rm -rf "\$HOME/.cache/sessions"' >/dev/null 2>&1
export XDG_RUNTIME_DIR="\$TMPDIR"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 >/dev/null 2>&1
termux-x11 :0 >/dev/null 2>&1 &
sleep 3
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1
sleep 1
GPU_ENV=""
if [ "\${1:-}" = "--gpu" ] && command -v virgl_test_server_android >/dev/null 2>&1; then
    virgl_test_server_android >/dev/null 2>&1 &
    GPU_ENV="GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0"
fi
echo "Starting Ubuntu desktop... (first start can take 1-2 minutes)"
proot-distro login ubuntu --user $U --shared-tmp -- \\
    env DISPLAY=:0 PULSE_SERVER=tcp:127.0.0.1 \$GPU_ENV dbus-launch --exit-with-session startxfce4
LAUNCH_EOF
chmod +x "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-stop"

echo "==> Making sure wallpaper/icons can be shown"
proot-distro login ubuntu -- sh -c 'dpkg -s librsvg2-common >/dev/null 2>&1 || (apt-get update >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y librsvg2-common)'

echo "==> Starting the desktop – open the Termux:X11 app"
exec ubuntu-desktop
