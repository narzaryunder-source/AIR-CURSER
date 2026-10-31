#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu + XFCE4 desktop on Android (Termux + Termux:X11, no root).
# Run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/setup.sh -o setup.sh && bash setup.sh
set -euo pipefail

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- progress display
# Shows "[#####.....]  40%  Step 4/10: ...  (time: 3m 12s)" and, during long steps,
# prints a line every minute so you can see the script is still working.
AC_TOTAL=10
AC_START=${AC_START:-$(date +%s)}
AC_HB=""
ac_hb_stop() { if [ -n "$AC_HB" ]; then kill "$AC_HB" 2>/dev/null || true; AC_HB=""; fi; }
trap ac_hb_stop EXIT
step() {
    ac_hb_stop
    local n="$1" p="$2" text="$3" bar="" i
    local fill=$(( p * 25 / 100 ))
    for ((i = 0; i < 25; i++)); do if [ "$i" -lt "$fill" ]; then bar+="#"; else bar+="."; fi; done
    local el=$(( $(date +%s) - AC_START ))
    printf '\n\033[1;36m[%s] %3d%%   Step %s/%s: %s   (time: %dm %02ds)\033[0m\n' \
        "$bar" "$p" "$n" "$AC_TOTAL" "$text" $((el / 60)) $((el % 60))
    ( m=0; while sleep 60; do m=$((m + 1))
        printf '\033[1;33m   ... still working on step %s/%s (%s min) – please wait, do not close Termux\033[0m\n' \
            "$n" "$AC_TOTAL" "$m"
      done ) &
    AC_HB=$!
}

if [ -z "${PREFIX:-}" ] || [ ! -d "/data/data/com.termux" ]; then
    echo "Run this script inside the Termux app on Android."
    exit 1
fi

DISTRO="ubuntu"
# Works with old and new proot-distro (the rootfs folder moved between versions):
# everything inside Ubuntu is done through "proot-distro login", files go through /tmp.
SHARED_TMP="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$SHARED_TMP"
ubuntu_installed() { proot-distro login "$DISTRO" -- true >/dev/null 2>&1; }

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
step 1 0 "Updating Termux packages"
yes | pkg update -y -o Dpkg::Options::="--force-confnew" || true
yes | pkg upgrade -y -o Dpkg::Options::="--force-confnew" || true
step 2 10 "Installing Termux tools (proot-distro, Termux:X11 support, sound)"
pkg install -y x11-repo
pkg install -y proot-distro termux-x11-nightly pulseaudio wget
pkg install -y virglrenderer-android || warn "virglrenderer-android not available: GPU mode will be off"

# ---------------------------------------------------------------- Ubuntu rootfs
step 3 18 "Downloading and installing Ubuntu"
if ubuntu_installed; then
    say "Ubuntu is already installed – keeping it (nothing is deleted)"
else
    proot-distro install "$DISTRO"
fi

# ---------------------------------------------------------------- setup inside Ubuntu
cat > "$SHARED_TMP/aircursor-inner-setup.sh" <<'INNER_EOF'
#!/bin/bash
set -e
U="$1"
AC_START="$2"

# ---------------------------------------------------------------- progress display
# Shows "[#####.....]  40%  Step 4/10: ...  (time: 3m 12s)" and, during long steps,
# prints a line every minute so you can see the script is still working.
AC_TOTAL=10
AC_START=${AC_START:-$(date +%s)}
AC_HB=""
ac_hb_stop() { if [ -n "$AC_HB" ]; then kill "$AC_HB" 2>/dev/null || true; AC_HB=""; fi; }
trap ac_hb_stop EXIT
step() {
    ac_hb_stop
    local n="$1" p="$2" text="$3" bar="" i
    local fill=$(( p * 25 / 100 ))
    for ((i = 0; i < 25; i++)); do if [ "$i" -lt "$fill" ]; then bar+="#"; else bar+="."; fi; done
    local el=$(( $(date +%s) - AC_START ))
    printf '\n\033[1;36m[%s] %3d%%   Step %s/%s: %s   (time: %dm %02ds)\033[0m\n' \
        "$bar" "$p" "$n" "$AC_TOTAL" "$text" $((el / 60)) $((el % 60))
    ( m=0; while sleep 60; do m=$((m + 1))
        printf '\033[1;33m   ... still working on step %s/%s (%s min) – please wait, do not close Termux\033[0m\n' \
            "$n" "$AC_TOTAL" "$m"
      done ) &
    AC_HB=$!
}

APT_OPTS=(-y -o Dpkg::Progress-Fancy=1 -o APT::Color=1)
export DEBIAN_FRONTEND=noninteractive
ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime || true

step 4 30 "Checking earlier installs and updating Ubuntu"
# Finish an install that was interrupted earlier (safe to run every time)
dpkg --configure -a || true
# The big optional icon pack is no longer used: if an earlier run was stopped while it was
# unpacking, remove that half-installed package (only inside Ubuntu; phone files are not touched).
if dpkg -s papirus-icon-theme >/dev/null 2>&1 && ! dpkg -s papirus-icon-theme | grep -q 'Status: install ok installed'; then
    echo "Removing the half-installed optional icon pack..."
    dpkg --remove --force-remove-reinstreq papirus-icon-theme || true
fi
apt-get -f install -y || true
apt-get update
apt-get "${APT_OPTS[@]}" -o Dpkg::Options::="--force-confnew" upgrade || true
step 5 40 "Installing the XFCE desktop (biggest step, 10-30 min)"
apt-get install "${APT_OPTS[@]}" --no-install-recommends \
    sudo locales dbus-x11 at-spi2-core ca-certificates curl wget git nano gnupg \
    xfce4 xfce4-terminal xfce4-taskmanager thunar mousepad ristretto \
    fonts-dejavu-core fonts-noto-color-emoji xdg-utils pulseaudio-utils librsvg2-common
# Nice-to-have packages (names differ between Ubuntu releases, so each is optional)
step 6 70 "Installing small extras"
for p in xfce4-whiskermenu-plugin adwaita-icon-theme xarchiver htop; do
    echo "      - $p"
    apt-get install -y --no-install-recommends "$p" >/dev/null 2>&1 || echo "        (skipped)"
done
step 7 76 "Language settings"

sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen 2>/dev/null || true
locale-gen en_US.UTF-8 >/dev/null 2>&1 || true

step 8 80 "Installing Firefox"
# Firefox as a normal package (Ubuntu's own "firefox" is a snap, which cannot run here)
if ! command -v firefox >/dev/null 2>&1; then
    install -d -m 0755 /etc/apt/keyrings
    if wget -qO /etc/apt/keyrings/packages.mozilla.org.asc https://packages.mozilla.org/apt/repo-signing-key.gpg; then
        echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
            > /etc/apt/sources.list.d/mozilla.list
        printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
        # Never install Ubuntu's snap-only firefox stub (it breaks apt inside proot)
        printf 'Package: firefox*\nPin: release o=Ubuntu\nPin-Priority: -1\n' > /etc/apt/preferences.d/no-snap-firefox
        apt-get update && apt-get install "${APT_OPTS[@]}" firefox || echo "[!] Firefox could not be installed"
    fi
fi

step 9 90 "Creating your user and desktop shortcuts"
# Normal user with sudo
if ! id "$U" >/dev/null 2>&1; then
    useradd -m -s /bin/bash "$U"
fi
echo "$U ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/$U"
chmod 0440 "/etc/sudoers.d/$U"

# Extra apps menu
if [ -f /tmp/aircursor-ubuntu-extras.sh ]; then
    install -m 0755 /tmp/aircursor-ubuntu-extras.sh /usr/local/bin/ubuntu-extras
fi

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
ac_hb_stop
INNER_EOF

# Extra apps menu (copied into Ubuntu)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/ubuntu-extras.sh" ]; then
    cp "$SCRIPT_DIR/ubuntu-extras.sh" "$SHARED_TMP/aircursor-ubuntu-extras.sh"
else
    wget -qO "$SHARED_TMP/aircursor-ubuntu-extras.sh" \
        https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/ubuntu-extras.sh
fi

ac_hb_stop
proot-distro login "$DISTRO" --shared-tmp -- bash /tmp/aircursor-inner-setup.sh "$UBU_USER" "$AC_START"
rm -f "$SHARED_TMP/aircursor-inner-setup.sh" "$SHARED_TMP/aircursor-ubuntu-extras.sh"

# ---------------------------------------------------------------- launchers in Termux
step 10 96 "Creating commands: ubuntu-desktop, ubuntu-stop, ubuntu-shell"
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

ac_hb_stop
EL=$(( $(date +%s) - AC_START ))
printf '\n\033[1;32m[#########################] 100%%   All %s steps finished in %dm %02ds\033[0m\n' "$AC_TOTAL" $((EL / 60)) $((EL % 60))
say "Done!"
cat <<DONE

  Start desktop :  ubuntu-desktop        (with GPU: ubuntu-desktop --gpu)
  Stop desktop  :  ubuntu-stop
  Terminal only :  ubuntu-shell
  Extra apps    :  double-tap "Install Apps" on the Ubuntu desktop
                   (or run: ubuntu-shell  then  sudo ubuntu-extras)

  Make sure the Termux:X11 app is installed (see ubuntu/README.md).
DONE
