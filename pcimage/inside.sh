#!/bin/bash
# Runs INSIDE the new Ubuntu (chroot on GitHub's arm64 computer): installs everything the
# AirCursor PC needs, so phones only download and unpack it – no long apt install on the phone.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
# Smaller download: no manuals, documentation or translations (licence/copyright notices stay).
cat > /etc/dpkg/dpkg.cfg.d/01-aircursor-small <<'CFG'
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/doc/*
path-include=/usr/share/doc/*/copyright
path-exclude=/usr/share/locale/*
path-include=/usr/share/locale/en*
path-include=/usr/share/locale/hi*
path-include=/usr/share/locale/locale.alias
CFG
# No services to start inside a build.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
APT="-o Acquire::Retries=5 -y --no-install-recommends"
apt-get update
apt-get $APT install ca-certificates curl gnupg nano procps less
# Desktop (XFCE), screen server (TigerVNC) – the same set the app installs on the phone.
apt-get $APT install \
    xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd xfce4-taskmanager mousepad ristretto \
    tigervnc-standalone-server dbus-x11 dbus-bin x11-xserver-utils x11-utils at-spi2-core librsvg2-common \
    fonts-dejavu-core fonts-noto-color-emoji fonts-inter adwaita-icon-theme elementary-xfce-icon-theme \
    greybird-gtk-theme zenity wget xdg-utils libnotify-bin libglib2.0-bin
# Tools the app uses (App Store window, clipboard, sound, files).
apt-get $APT install \
    python3-gi gir1.2-gtk-3.0 xclip xdotool xfce4-screenshooter xarchiver thunar-archive-plugin \
    unzip zip p7zip-full pulseaudio pulseaudio-utils pavucontrol
# Brave (default browser): official repository.
mkdir -p /usr/share/keyrings
curl -fsSLo /usr/share/keyrings/brave-browser-archive-keyring.gpg \
    https://brave-browser-apt-release.s3.brave.com/brave-browser-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/brave-browser-archive-keyring.gpg] https://brave-browser-apt-release.s3.brave.com/ stable main" \
    > /etc/apt/sources.list.d/brave-browser-release.list
apt-get update
apt-get $APT install brave-browser
# Every phone makes its own machine id at the first start (never shared between phones).
rm -f /etc/machine-id /var/lib/dbus/machine-id
: > /etc/machine-id
# Clean up: package caches, temporary files. (The package lists stay: installing apps works at once.)
apt-get clean
rm -rf /var/cache/apt/*.bin /var/cache/debconf/*-old /var/lib/dpkg/*-old /tmp/* /var/tmp/* /root/.cache /var/log/*.log /var/log/apt/*
rm -f /usr/sbin/policy-rc.d
date -u +%Y-%m-%dT%H:%MZ > /etc/aircursor-pc-image
echo "AIRCURSOR_IMAGE_OK"
