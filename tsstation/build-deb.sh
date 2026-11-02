#!/bin/bash
# Builds dist/ts-station.deb: the one-click installer for AirCursor PC
# (download it in the PC's browser, then click it – no terminal needed).
set -e
cd "$(dirname "$0")"
V=$(cat VERSION)
B=$(mktemp -d)
trap 'rm -rf "$B"' EXIT
chmod 755 "$B"
mkdir -p "$B/DEBIAN" "$B/opt/ts-station/ui" "$B/usr/bin" "$B/usr/share/applications"
install -m 755 server.py ts-station "$B/opt/ts-station/"
install -m 644 ts-station.svg VERSION "$B/opt/ts-station/"
install -m 644 ui/index.html ui/app.css ui/app.js "$B/opt/ts-station/ui/"
ln -s /opt/ts-station/ts-station "$B/usr/bin/ts-station"
cat > "$B/usr/share/applications/ts-station.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=TS Station
Comment=Your games, console style
Exec=ts-station
Icon=/opt/ts-station/ts-station.svg
Terminal=false
Categories=Utility;
DESK
cat > "$B/DEBIAN/control" <<CTRL
Package: ts-station
Version: $V
Architecture: all
Maintainer: TS Station <noreply@example.com>
Depends: python3
Recommends: fonts-inter
Section: games
Priority: optional
Description: TechOS Station (TS1) – console-style game launcher for AirCursor PC
 Home with big game art, library, free-games store, controller support and themes.
CTRL
cat > "$B/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
# Older installs (install.sh) put a link in /usr/local/bin: the package's own one is used now.
[ -L /usr/local/bin/ts-station ] && rm -f /usr/local/bin/ts-station
# Desktop icon (once; deleting it is respected because the file is only made when missing).
if [ -d /root ] && [ ! -e /root/Desktop/TS-Station.desktop ]; then
    mkdir -p /root/Desktop
    cp /usr/share/applications/ts-station.desktop /root/Desktop/TS-Station.desktop
    chmod +x /root/Desktop/TS-Station.desktop
fi
exit 0
POST
cat > "$B/DEBIAN/postrm" <<'POST'
#!/bin/sh
[ "$1" = remove ] || [ "$1" = purge ] && rm -f /root/Desktop/TS-Station.desktop
exit 0
POST
chmod 755 "$B/DEBIAN/postinst" "$B/DEBIAN/postrm"
mkdir -p dist
dpkg-deb --root-owner-group -Zxz --build "$B" dist/ts-station.deb >/dev/null
echo "dist/ts-station.deb ($V)"
