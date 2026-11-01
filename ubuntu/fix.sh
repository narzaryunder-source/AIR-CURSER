#!/data/data/com.termux/files/usr/bin/bash
# Full repair of the Ubuntu desktop (no panel / X cursor / root desktop / black wallpaper).
# Moves old desktop settings to a backup folder (nothing is deleted) and starts the desktop.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/fix.sh -o fix.sh && bash fix.sh
if [ "$(id -u)" = "0" ]; then
    echo "Aap abhi Ubuntu ke andar ho (root@localhost). Pehle 'exit' likho jab tak prompt '~ \$' na ho, phir yeh command chalao."
    exit 1
fi
BASE="https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu"
# Which Linux user the desktop runs as (saved once; never "0"/root).
U=$(cat "$HOME/.aircursor-ubuntu-user" 2>/dev/null)
[ -z "$U" ] && U=$(grep -o -- 'login ubuntu --user [a-z][a-z0-9_-]*' "$PREFIX/bin/ubuntu-desktop" 2>/dev/null | head -n 1 | awk '{print $4}')
if [ -z "$U" ] || [ "$U" = "root" ]; then
    U=$(proot-distro login ubuntu -- sh -c 'if id user >/dev/null 2>&1; then echo user; else awk -F: '"'"'$3>=1000 && $3<60000 && $1!="nobody" {print $1; exit}'"'"' /etc/passwd; fi' 2>/dev/null | tail -n 1)
fi
U=${U:-user}
echo "$U" > "$HOME/.aircursor-ubuntu-user"

echo "==> [1/4] Stopping everything that is still running (old desktops, Ubuntu shells)"
ubuntu-stop >/dev/null 2>&1
pkill -f "proot" 2>/dev/null
sleep 2
pkill -9 -f "proot" 2>/dev/null
rm -rf "$TMPDIR/.X11-unix" "$TMPDIR/.X0-lock" "$TMPDIR/.ICE-unix" 2>/dev/null

echo "==> [2/4] Downloading the latest repair files"
for f in launchers.sh inner-repair.sh ubuntu-extras.sh app-store.sh install-deb.sh windows-layout.sh wallpaper-gui.sh VERSION; do
    curl -fsSL "$BASE/$f" -o "$TMPDIR/aircursor-$f" || { echo "Download failed: $f (internet?)"; exit 1; }
done

echo "==> [3/4] Repairing Ubuntu desktop for user '$U' (fresh desktop settings, self-repair, wallpaper)"
RESET=1
[ "${1:-}" = "--keep" ] && RESET=0
proot-distro login ubuntu --shared-tmp -- bash /tmp/aircursor-inner-repair.sh "$U" "$RESET"
# shellcheck source=/dev/null
. "$TMPDIR/aircursor-launchers.sh"
install_launchers "$U"
tr -dc '0-9' < "$TMPDIR/aircursor-VERSION" > "$HOME/.aircursor-ubuntu-version"
rm -f "$TMPDIR"/aircursor-*.sh "$TMPDIR/aircursor-VERSION"

echo "==> [4/4] Starting the desktop"
AIRCURSOR_NO_UPDATE=1 exec ubuntu-desktop
