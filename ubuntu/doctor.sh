#!/data/data/com.termux/files/usr/bin/bash
# Checks the Ubuntu desktop setup and tries a test start. Changes nothing, deletes nothing.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/doctor.sh -o doctor.sh && bash doctor.sh
G='\033[1;32m'; R='\033[1;31m'; Y='\033[1;33m'; N='\033[0m'
ok() { printf "${G}[OK]${N} %s\n" "$*"; }
no() { printf "${R}[X]${N} %s\n   ${Y}-> %s${N}\n" "$1" "$2"; PROBLEMS=$((PROBLEMS + 1)); }
PROBLEMS=0
FIX="setup.sh dobara chalao (kuch delete nahi hoga, jo ho chuka woh skip hoga)"

if [ "$(id -u)" = "0" ]; then
    echo "Aap abhi Ubuntu ke andar ho (prompt: root@localhost). Pehle 'exit' likho jab tak prompt '~ \$' na ho, phir yeh command chalao."
    exit 1
fi
echo "=== AirCursor Ubuntu doctor ==="
if ! command -v proot-distro >/dev/null; then
    no "proot-distro missing" "$FIX"
elif ! proot-distro login ubuntu -- true >/dev/null 2>&1; then
    no "Ubuntu not installed" "$FIX"
else
    ok "Ubuntu installed"
    U=$(grep -o -- '--user [a-z0-9_-]*' "$PREFIX/bin/ubuntu-desktop" 2>/dev/null | head -n 1 | awk '{print $2}')
    # One login that reports what is inside Ubuntu.
    INFO=$(proot-distro login ubuntu -- sh -c "
        [ -x /usr/bin/startxfce4 ] && echo XFCE
        [ -x /usr/bin/dbus-launch ] && echo DBUS
        [ -x /usr/local/bin/ubuntu-extras ] && echo EXTRAS
    " 2>/dev/null)
    echo "$INFO" | grep -q XFCE && ok "XFCE desktop installed" || no "XFCE desktop missing" "$FIX"
    echo "$INFO" | grep -q DBUS && ok "dbus-x11 installed" || no "dbus-x11 missing" "$FIX"
    # Check the user with its own login (more reliable than inside the combined check above).
    if [ -n "$U" ] && proot-distro login ubuntu -- id "$U" >/dev/null 2>&1; then ok "Linux user '$U' exists"
    else no "Linux user missing" "$FIX"; fi
    echo "$INFO" | grep -q EXTRAS && ok "Install Apps menu present" || printf "${Y}[?]${N} Install Apps menu missing (optional)\n"
fi
[ -x "$PREFIX/bin/ubuntu-desktop" ] && ok "ubuntu-desktop command exists" || no "ubuntu-desktop command missing" "$FIX"
command -v termux-x11 >/dev/null && ok "termux-x11 package installed" \
    || no "termux-x11 package missing" "Termux me chalao: pkg install x11-repo && pkg install termux-x11-nightly"
if pm list packages 2>/dev/null | grep -q "com.termux.x11"; then ok "Termux:X11 app installed"
else printf "${Y}[?]${N} Termux:X11 app check nahi ho paya – GitHub (Nightly) wala app installed hona chahiye\n"; fi

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
else
    no "XFCE did not start" "Neeche ke error ka screenshot bhejo"
    echo "----- last lines of $LOG -----"
    tail -n 25 "$LOG"
fi
echo
echo "Band karne ke liye: ubuntu-stop"
