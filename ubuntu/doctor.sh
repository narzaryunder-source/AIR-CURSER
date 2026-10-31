#!/data/data/com.termux/files/usr/bin/bash
# Checks the Ubuntu desktop setup and tries a test start. Changes nothing, deletes nothing.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/doctor.sh -o doctor.sh && bash doctor.sh
G='\033[1;32m'; R='\033[1;31m'; Y='\033[1;33m'; N='\033[0m'
ok() { printf "${G}[OK]${N} %s\n" "$*"; }
no() { printf "${R}[X]${N} %s\n   ${Y}-> %s${N}\n" "$1" "$2"; PROBLEMS=$((PROBLEMS + 1)); }
PROBLEMS=0
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

echo "=== AirCursor Ubuntu doctor ==="
command -v proot-distro >/dev/null && ok "proot-distro installed" \
    || no "proot-distro missing" "Setup command dobara chalao (setup.sh)"
[ -d "$ROOTFS" ] && ok "Ubuntu installed" \
    || no "Ubuntu not installed" "Setup pura nahi hua. setup.sh dobara chalao aur 'Done!' aane tak ruko"
[ -x "$ROOTFS/usr/bin/startxfce4" ] && ok "XFCE desktop installed" \
    || no "XFCE desktop missing" "Setup beech me ruka tha. setup.sh dobara chalao (kuch delete nahi hoga)"
[ -x "$ROOTFS/usr/bin/dbus-launch" ] && ok "dbus-x11 installed" \
    || no "dbus-x11 missing" "setup.sh dobara chalao"
[ -x "$PREFIX/bin/ubuntu-desktop" ] && ok "ubuntu-desktop command exists" \
    || no "ubuntu-desktop command missing" "setup.sh dobara chalao"
U=$(grep -o -- '--user [a-z0-9_-]*' "$PREFIX/bin/ubuntu-desktop" 2>/dev/null | awk '{print $2}')
if [ -n "$U" ] && grep -q "^$U:" "$ROOTFS/etc/passwd" 2>/dev/null; then ok "Linux user '$U' exists"
else no "Linux user missing" "setup.sh dobara chalao"; fi
command -v termux-x11 >/dev/null && ok "termux-x11 package installed" \
    || no "termux-x11 package missing" "Termux me chalao: pkg install x11-repo && pkg install termux-x11-nightly"
if pm list packages 2>/dev/null | grep -q "com.termux.x11"; then ok "Termux:X11 app installed"
else printf "${Y}[?]${N} Termux:X11 app check nahi ho paya – make sure the app from GitHub (Nightly) is installed\n"; fi

if [ "$PROBLEMS" -gt 0 ]; then
    printf "\n${R}%s problem(s). Upar ke '->' wale steps karo, phir dobara doctor chalao.${N}\n" "$PROBLEMS"
    exit 1
fi

echo
echo "=== Test start (30 seconds) ==="
LOG="$HOME/ubuntu-desktop.log"
ubuntu-stop >/dev/null 2>&1
export XDG_RUNTIME_DIR="$TMPDIR"
termux-x11 :0 >"$HOME/termux-x11.log" 2>&1 &
sleep 4
if [ -S "$TMPDIR/.X11-unix/X0" ]; then ok "X server started (display :0)"
else no "X server did not start" "Termux:X11 app aur termux-x11-nightly package dono 'nightly' hone chahiye. Log: ~/termux-x11.log"; fi
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1
sleep 2
proot-distro login ubuntu --user "$U" --shared-tmp -- env DISPLAY=:0 dbus-launch --exit-with-session startxfce4 >"$LOG" 2>&1 &
sleep 25
if pgrep -f xfce4-session >/dev/null || pgrep -f xfwm4 >/dev/null; then
    ok "XFCE desktop is running – Termux:X11 app kholo, desktop dikhna chahiye"
    echo "   (Agar ab bhi kaala hai: Termux:X11 me 3 ungliyon se tap ya notification se 'Preferences' -> Output -> Display resolution mode: native)"
else
    no "XFCE did not start" "Neeche ka error ka screenshot bhejo"
    echo "----- last lines of $LOG -----"
    tail -n 25 "$LOG"
fi
echo
echo "Band karne ke liye: ubuntu-stop"
