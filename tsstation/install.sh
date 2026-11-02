#!/bin/bash
# Installs (or updates) TS Station in this AirCursor PC. Only this PC changes – nothing else.
#   wget -qO- https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/tsstation/install.sh | bash
set -e
SRC=https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/tsstation
DIR=/opt/ts-station
echo "Installing TS Station…"
mkdir -p "$DIR/ui"
for f in server.py ts-station ts-station.svg ui/index.html ui/app.css ui/app.js; do
    wget -qO "$DIR/$f.new" "$SRC/$f" && mv -f "$DIR/$f.new" "$DIR/$f"
done
chmod +x "$DIR/ts-station" "$DIR/server.py"
ln -sf "$DIR/ts-station" /usr/local/bin/ts-station
apt-get install -y --no-install-recommends fonts-inter >/dev/null 2>&1 || echo "(Inter font not installed – a standard font is used)"
cat > /usr/share/applications/ts-station.desktop <<DESK
[Desktop Entry]
Type=Application
Name=TS Station
Comment=Your games, console style
Exec=ts-station
Icon=$DIR/ts-station.svg
Terminal=false
Categories=Utility;
DESK
mkdir -p /root/Desktop
cp /usr/share/applications/ts-station.desktop /root/Desktop/TS-Station.desktop
chmod +x /root/Desktop/TS-Station.desktop
echo "Done. Open TS Station from the desktop icon (or type: ts-station)."
