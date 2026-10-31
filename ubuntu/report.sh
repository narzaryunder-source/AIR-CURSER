#!/data/data/com.termux/files/usr/bin/bash
# Prints a short report about the running Ubuntu desktop (and tries to start the window
# manager + panel, showing their errors). Run in a NEW Termux session while the desktop is open.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/report.sh -o report.sh && bash report.sh
if [ "$(id -u)" = "0" ]; then echo "Pehle 'exit' likho (aap Ubuntu ke andar ho)."; exit 1; fi
U=$(grep -o -- '--user [a-z0-9_-]*' "$PREFIX/bin/ubuntu-desktop" 2>/dev/null | head -n 1 | awk '{print $2}')
U=${U:-user}
echo "===== AirCursor Ubuntu report ====="
pkg list-installed 2>/dev/null | grep -E "^(proot-distro|termux-x11-nightly)/" | cut -d' ' -f1,2
echo "-- login as '$U' gives:"
proot-distro login ubuntu --user "$U" -- sh -c 'echo "   uid=$(id -u) name=$(id -un) home=$HOME"' 2>&1 | tail -n 2
echo "-- running now:"
for p in xfce4-session xfwm4 xfce4-panel xfdesktop Thunar; do
    if pgrep -f "$p" >/dev/null; then echo "   $p: yes"; else echo "   $p: NO"; fi
done
echo "-- important lines from ~/ubuntu-desktop.log:"
grep -iE "error|fail|crash|abort|segment|cannot|unable|another|refus|killed" "$HOME/ubuntu-desktop.log" 2>/dev/null \
    | grep -viE "_NET_CURRENT|system bus|DPMS|xubuntu-wallpaper|G_IS_|qdata|g_file_get_path" | tail -n 12 | cut -c1-150
echo "-- trying to start window manager + panel (10 s):"
OUT="$TMPDIR/aircursor-try.log"
proot-distro login ubuntu --user "$U" --shared-tmp -- env DISPLAY=:0 sh -c \
    'pgrep -x xfwm4 >/dev/null || xfwm4 --replace & sleep 4; pgrep -x xfce4-panel >/dev/null || xfce4-panel & sleep 6' \
    >"$OUT" 2>&1 &
sleep 11
for p in xfwm4 xfce4-panel; do
    if pgrep -f "$p" >/dev/null; then echo "   $p: running"; else echo "   $p: NOT running"; fi
done
grep -viE "_NET_CURRENT|system bus|DPMS|G_IS_" "$OUT" | tail -n 12 | cut -c1-150
echo "===== end (screenshot bhejo) ====="
