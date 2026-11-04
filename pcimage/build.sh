#!/bin/bash
# Builds the ready-made AirCursor PC (Ubuntu arm64 + desktop + Brave + tools) on GitHub's arm64
# computer. Output in pcimage/out: aircursor-pc-<V>-arm64.tar.xz and pc-image.json (for the app).
# The workflow publishes both to the "pc-image" release; the app reads pc-image.json for the newest one.
set -euo pipefail
cd "$(dirname "$0")"
V=$(cat VERSION)
REPO=${GITHUB_REPOSITORY:-narzaryunder-source/AIR-CURSER}
BASE_URL=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
BASE_SHA=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
[ "$(uname -m)" = aarch64 ] || { echo "Needs an arm64 computer"; exit 1; }
W=$PWD/work
R=$W/root
sudo rm -rf "$W" out
mkdir -p "$R" out
curl -fL --retry 5 -o "$W/base.tar.gz" "$BASE_URL"
echo "$BASE_SHA  $W/base.tar.gz" | sha256sum -c -
sudo tar -xzf "$W/base.tar.gz" -C "$R"
sudo rm -f "$R/etc/resolv.conf"
sudo cp /etc/resolv.conf "$R/etc/resolv.conf"
sudo install -m 755 inside.sh "$R/tmp/inside.sh"
cleanup() { sudo umount -R "$R/proc" "$R/sys" "$R/dev" 2>/dev/null || true; }
trap cleanup EXIT
sudo mount -t proc proc "$R/proc"
sudo mount --rbind /sys "$R/sys" && sudo mount --make-rslave "$R/sys"
sudo mount --rbind /dev "$R/dev" && sudo mount --make-rslave "$R/dev"
sudo chroot "$R" /bin/bash /tmp/inside.sh | tee "$W/inside.log"
grep -q AIRCURSOR_IMAGE_OK "$W/inside.log"
cleanup
trap - EXIT
# The app writes its own network settings on the phone.
sudo rm -f "$R/etc/resolv.conf" "$R/tmp/inside.sh"
UNPACKED=$(sudo du -sb "$R" | cut -f1)
NAME=aircursor-pc-$V-arm64.tar.xz
sudo tar --numeric-owner -C "$R" -cf - . | xz -T0 -6 > "out/$NAME"
SIZE=$(stat -c %s "out/$NAME")
SHA=$(sha256sum "out/$NAME" | cut -d' ' -f1)
cat > out/pc-image.json <<JSON
{
  "version": $V,
  "file": "$NAME",
  "url": "https://github.com/$REPO/releases/download/pc-image/$NAME",
  "size": $SIZE,
  "sha256": "$SHA",
  "unpacked": $UNPACKED
}
JSON
cat out/pc-image.json
