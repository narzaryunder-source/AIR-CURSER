#!/bin/bash
# Snapdragon GPU for AirCursor PC: Mesa's Turnip (Vulkan for Adreno, talking to the phone's own
# GPU device /dev/kgsl-3d0 – no root needed) and Zink (OpenGL on top of Vulkan), built for the
# PC (Ubuntu 24.04 arm64). Installed in its own folder, so the PC's normal drawing stays as it is.
# Runs on GitHub's arm64 Ubuntu 24.04 runner; output: gpu/out/aircursor-turnip-arm64.tar.xz
set -euo pipefail
MESA=mesa-25.3.6
PREFIX=/opt/aircursor-turnip
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/out
WORK=${WORK:-/tmp/turnip-build}
SUDO=$(command -v sudo || true)

if [ "${SKIP_DEPS:-0}" != 1 ]; then
    $SUDO apt-get update
    $SUDO apt-get install -y --no-install-recommends build-essential ninja-build pkg-config \
        python3-venv bison flex glslang-tools curl xz-utils \
        libexpat1-dev zlib1g-dev libzstd-dev libglvnd-dev libdrm-dev \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev libxshmfence-dev \
        libxcb1-dev libxcb-dri2-0-dev libxcb-dri3-dev libxcb-present-dev libxcb-shm0-dev \
        libxcb-randr0-dev libxcb-sync-dev libxcb-xfixes0-dev libxcb-glx0-dev libxcb-keysyms1-dev libvulkan1
fi

mkdir -p "$WORK" "$OUT"
cd "$WORK"
python3 -m venv venv
. venv/bin/activate
pip install --quiet 'meson==1.6.1' mako pyyaml packaging ply pycparser
[ -d "$MESA" ] || curl -fsSL "https://archive.mesa3d.org/$MESA.tar.xz" | tar xJ
cd "$MESA"
rm -rf build
meson setup build --prefix="$PREFIX" --libdir=lib --buildtype=release -Db_ndebug=true \
    -Dplatforms=x11 -Dgallium-drivers=zink -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
    -Dglx=dri -Degl=enabled -Dgbm=disabled -Dgles1=disabled -Dgles2=enabled -Dglvnd=enabled \
    -Dllvm=disabled -Dvideo-codecs= -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dbuild-tests=false -Dtools= -Dvulkan-layers=
if [ "${CONFIGURE_ONLY:-0}" = 1 ]; then exit 0; fi
ninja -C build
rm -rf "$WORK/root"
DESTDIR="$WORK/root" ninja -C build install
cd "$WORK/root"
# Only what the PC needs: no headers, build files or the helper tools Mesa's build brought along.
rm -rf ".$PREFIX/include" ".$PREFIX/lib/pkgconfig" ".$PREFIX/bin" ".$PREFIX/share/man" ".$PREFIX/share/doc"
rm -f ".$PREFIX"/lib/libarchive.so* ".$PREFIX"/lib/libxml2.so*
# Small libraries the PC may not have: the Vulkan loader (Zink opens it) and two X helpers.
for l in libvulkan.so.1 libxcb-keysyms.so.1 libxshmfence.so.1; do
    src=$(ldconfig -p | awk -v n="$l" '$1 == n { print $NF; exit }')
    [ -n "$src" ] || { echo "missing $l on the build machine"; exit 1; }
    cp -L "$src" ".$PREFIX/lib/$l"
done
find . -name '*.so*' -type f -exec strip --strip-unneeded {} + 2>/dev/null || true
cat > ".$PREFIX/VERSION" <<V
$MESA turnip-kgsl + zink (built $(date -u +%Y-%m-%d))
V
cp "$WORK/$MESA/docs/license.rst" ".$PREFIX/LICENSE-MESA.rst"
# What a game needs to draw on the Snapdragon GPU (read by the PC's game launcher and Benchmark):
# OpenGL through Zink on Turnip, which talks to /dev/kgsl-3d0. The picture goes to the desktop
# through shared memory (the desktop has no DRI3).
ICD=$(cd ".$PREFIX/share/vulkan/icd.d" && ls freedreno_icd.*.json | head -n 1)
EGLV=$(cd ".$PREFIX/share/glvnd/egl_vendor.d" && ls *mesa*.json | head -n 1)
cat > ".$PREFIX/gpu.env" <<V
LD_LIBRARY_PATH=$PREFIX/lib
VK_ICD_FILENAMES=$PREFIX/share/vulkan/icd.d/$ICD
__GLX_VENDOR_LIBRARY_NAME=mesa
__EGL_VENDOR_LIBRARY_FILENAMES=$PREFIX/share/glvnd/egl_vendor.d/$EGLV
GALLIUM_DRIVER=zink
MESA_LOADER_DRIVER_OVERRIDE=zink
TU_DEBUG=noconform
MESA_VK_WSI_DEBUG=sw
V
cat ".$PREFIX/gpu.env"
tar -cJf "$OUT/aircursor-turnip-arm64.tar.xz" "./${PREFIX#/}"
ls -la "$OUT"
find ".$PREFIX" -type f | sort
