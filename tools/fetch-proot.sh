#!/bin/bash
# Refreshes the proot engine bundled in the APK (app/src/main/jniLibs/arm64-v8a).
# Takes the latest Termux builds (process_vm variant that works at a high targetSdk),
# checks their SHA-256 against the repository index and stores them as lib*.so so that
# Android extracts them to the app's native library folder (the only place an app may
# execute files from on Android 10+).
set -euo pipefail
REPO="https://packages.termux.dev/apt/termux-main"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

fetch() { # package name
    local meta file sum
    meta=$(awk -v p="$1" '$0=="Package: "p{f=1} f&&/^$/{exit} f' "$WORK/Packages")
    file=$(awk '/^Filename:/{print $2}' <<<"$meta")
    sum=$(awk '/^SHA256:/{print $2}' <<<"$meta")
    echo "$1: $file"
    curl -fsSL "$REPO/$file" -o "$WORK/$1.deb"
    echo "$sum  $WORK/$1.deb" | sha256sum -c - >/dev/null
    dpkg-deb -x "$WORK/$1.deb" "$WORK/x"
}
fetch proot
fetch libtalloc
fetch libandroid-shmem

U="$WORK/x/data/data/com.termux/files/usr"
mkdir -p "$OUT"
cp "$U/bin/proot" "$OUT/libproot.so"
cp "$U/libexec/proot/loader" "$OUT/libproot-loader.so"
cp "$(readlink -f "$U/lib/libtalloc.so.2")" "$OUT/libtalloc.so"
cp "$U/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
ls -la "$OUT"
