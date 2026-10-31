#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu + XFCE4 desktop on Android (Termux + Termux:X11, no root).
# Run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/setup.sh -o setup.sh && bash setup.sh
set -euo pipefail

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }

if [ -z "${PREFIX:-}" ] || [ ! -d "/data/data/com.termux" ]; then
    echo "Run this script inside the Termux app on Android."
    exit 1
fi

DISTRO="ubuntu"
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"

# ---------------------------------------------------------------- checks
FREE_KB=$(df -k "$HOME" | awk 'NR==2 {print $4}')
if [ "${FREE_KB:-0}" -lt 6000000 ]; then
    warn "Only $((FREE_KB / 1024)) MB free. Ubuntu + desktop needs about 4-6 GB (more for extra apps)."
    read -r -p "Continue anyway? [y/N] " ans
    [ "${ans:-n}" = "y" ] || exit 1
fi

read -r -p "Linux username to create [user]: " UBU_USER
UBU_USER=${UBU_USER:-user}
if ! printf '%s' "$UBU_USER" | grep -Eq '^[a-z][a-z0-9_-]{0,30}$'; then
    echo "Use small letters/numbers only, e.g. user"
    exit 1
fi

# ---------------------------------------------------------------- Termux packages
say "Updating Termux packages (this can take a few minutes)"
yes | pkg update -y -o Dpkg::Options::="--force-confnew" || true
yes | pkg upgrade -y -o Dpkg::Options::="--force-confnew" || true
pkg install -y x11-repo
pkg install -y proot-distro termux-x11-nightly pulseaudio wget
pkg install -y virglrenderer-android || warn "virglrenderer-android not available: GPU mode will be off"

# ---------------------------------------------------------------- Ubuntu rootfs
if [ -d "$ROOTFS" ]; then
    say "Ubuntu is already installed – keeping it (nothing is deleted)"
else
    say "Downloading and installing Ubuntu"
    proot-distro install "$DISTRO"
fi

# ---------------------------------------------------------------- setup inside Ubuntu
say "Installing the XFCE4 desktop inside Ubuntu (largest step, 10-30 min)"
cat > "$ROOTFS/root/aircursor-inner-setup.sh" <<'INNER_EOF'
#!/bin/bash
set -e
U="$1"
export DEBIAN_FRONTEND=noninteractive
ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime || true

apt-get update
apt-get -y -o Dpkg::Options::="--force-confnew" upgrade || true
apt-get install -y --no-install-recommends \
    sudo locales dbus-x11 at-spi2-core ca-certificates curl wget git nano gnupg \
    xfce4 xfce4-terminal xfce4-taskmanager thunar mousepad ristretto \
    fonts-dejavu-core fonts-noto-color-emoji xdg-utils pulseaudio-utils
# Nice-to-have packages (names differ between Ubuntu releases, so each is optional)
for p in xfce4-whiskermenu-plugin papirus-icon-theme adwaita-icon-theme-full adwaita-icon-theme \
         file-roller xarchiver htop; do
    apt-get install -y --no-install-recommends "$p" >/dev/null 2>&1 || true
done

sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen 2>/dev/null || true
locale-gen en_US.UTF-8 >/dev/null 2>&1 || true

# Firefox as a normal package (Ubuntu's own "firefox" is a snap, which cannot run here)
if ! command -v firefox >/dev/null 2>&1; then
    install -d -m 0755 /etc/apt/keyrings
    if wget -qO /etc/apt/keyrings/packages.mozilla.org.asc https://packages.mozilla.org/apt/repo-signing-key.gpg; then
        echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
            > /etc/apt/sources.list.d/mozilla.list
        printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
        # Never install Ubuntu's snap-only firefox stub (it breaks apt inside proot)
        printf 'Package: firefox*\nPin: release o=Ubuntu\nPin-Priority: -1\n' > /etc/apt/preferences.d/no-snap-firefox
        apt-get update && apt-get install -y firefox || echo "[!] Firefox could not be installed"
    fi
fi

# Normal user with sudo
if ! id "$U" >/dev/null 2>&1; then
    useradd -m -s /bin/bash "$U"
fi
echo "$U ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/$U"
chmod 0440 "/etc/sudoers.d/$U"

# Desktop shortcut for the extra apps installer
mkdir -p "/home/$U/Desktop"
cat > "/home/$U/Desktop/Install-Apps.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=Install Apps (VS Code, Claude Code, Ollama, Blender...)
Exec=xfce4-terminal --hold -e "sudo ubuntu-extras"
Icon=system-software-install
Terminal=false
DESK
chmod +x "/home/$U/Desktop/Install-Apps.desktop"
chown -R "$U:$U" "/home/$U"
echo "Inner setup done."
INNER_EOF

# Extra apps menu (copied into Ubuntu)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/ubuntu-extras.sh" ]; then
    cp "$SCRIPT_DIR/ubuntu-extras.sh" "$ROOTFS/usr/local/bin/ubuntu-extras"
else
    wget -qO "$ROOTFS/usr/local/bin/ubuntu-extras" \
        https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/ubuntu-extras.sh
fi
chmod +x "$ROOTFS/usr/local/bin/ubuntu-extras"

proot-distro login "$DISTRO" -- bash /root/aircursor-inner-setup.sh "$UBU_USER"
rm -f "$ROOTFS/root/aircursor-inner-setup.sh"

# ---------------------------------------------------------------- launchers in Termux
say "Creating commands: ubuntu-desktop, ubuntu-stop, ubuntu-shell"
cat > "$PREFIX/bin/ubuntu-desktop" <<LAUNCH_EOF
#!/data/data/com.termux/files/usr/bin/bash
# Starts the Ubuntu XFCE desktop in Termux:X11.  Use --gpu for OpenGL acceleration (VirGL).
ubuntu-stop >/dev/null 2>&1
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
proot-distro login $DISTRO --user $UBU_USER --shared-tmp -- \\
    env DISPLAY=:0 PULSE_SERVER=tcp:127.0.0.1 \$GPU_ENV dbus-launch --exit-with-session startxfce4
LAUNCH_EOF

cat > "$PREFIX/bin/ubuntu-stop" <<'STOP_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Stops the desktop (only programs; no files are touched).
pkill -f "startxfce4" 2>/dev/null
pkill -f "xfce4-session" 2>/dev/null
pkill -f "virgl_test_server" 2>/dev/null
pkill -f "termux-x11" 2>/dev/null
pkill -f "com.termux.x11" 2>/dev/null
pulseaudio --kill 2>/dev/null
exit 0
STOP_EOF

cat > "$PREFIX/bin/ubuntu-shell" <<SHELL_EOF
#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu terminal without the desktop (for Claude Code, Ollama, coding...).
proot-distro login $DISTRO --user $UBU_USER --shared-tmp
SHELL_EOF
chmod +x "$PREFIX/bin/ubuntu-desktop" "$PREFIX/bin/ubuntu-stop" "$PREFIX/bin/ubuntu-shell"

say "Done!"
cat <<DONE

  Start desktop :  ubuntu-desktop        (with GPU: ubuntu-desktop --gpu)
  Stop desktop  :  ubuntu-stop
  Terminal only :  ubuntu-shell
  Extra apps    :  double-tap "Install Apps" on the Ubuntu desktop
                   (or run: ubuntu-shell  then  sudo ubuntu-extras)

  Make sure the Termux:X11 app is installed (see ubuntu/README.md).
DONE
