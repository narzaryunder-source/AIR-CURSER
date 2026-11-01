#!/bin/bash
# Refreshes the VirGL server bundled in the APK (app/src/main/jniLibs/arm64-v8a): the Termux build
# of virglrenderer-android. AirCursor runs it with the phone's own OpenGL ES driver (no ANGLE), so
# Linux games can draw on the phone's GPU ("Phone GPU" switch). SHA-256 is checked against the
# repository index; files are stored as lib*.so so Android extracts them to the native library folder.
set -euo pipefail
REPO="https://packages.termux.dev/apt/termux-main"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"
meta=$(awk '$0=="Package: virglrenderer-android"{f=1} f&&/^$/{exit} f' "$WORK/Packages")
file=$(awk '/^Filename:/{print $2}' <<<"$meta")
sum=$(awk '/^SHA256:/{print $2}' <<<"$meta")
echo "virglrenderer-android: $file"
curl -fsSL "$REPO/$file" -o "$WORK/v.deb"
echo "$sum  $WORK/v.deb" | sha256sum -c - >/dev/null
dpkg-deb -x "$WORK/v.deb" "$WORK/x"
U="$WORK/x/data/data/com.termux/files/usr"
cp "$U/bin/virgl_test_server_android" "$OUT/libvirgl_server.so"
cp "$U/opt/virglrenderer-android/lib/libvirglrenderer.so" "$OUT/libvirglrenderer.so"
cp "$U/opt/virglrenderer-android/lib/libepoxy.so" "$OUT/libepoxy.so"
ls -la "$OUT"
