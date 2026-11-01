package com.aircursor.cloudpc

import android.content.Context
import java.io.File

/**
 * The Cloud PC desktop: XFCE drawn by TigerVNC (Xvnc) into a private unix socket inside the
 * app's own folder – no network port, so no other app on the phone can watch or control it.
 */
object CloudPcDesktop {
    const val MARK_DESKTOP = ".cloudpc-desktop"
    const val GUEST_SOCKET = "/tmp/cloudpc-vnc.sock"
    private const val SCRIPT = "usr/local/bin/cloudpc-desktop"

    fun isInstalled(c: Context): Boolean {
        val r = CloudPcEngine.rootfs(c)
        // Ubuntu's /usr/bin/Xvnc is a link to /etc/alternatives/… – an absolute path that only makes
        // sense inside Ubuntu, so look at the files themselves and never follow links from Android.
        val bin = java.nio.file.Paths.get(r.absolutePath, "usr/bin")
        val nofollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        return File(r, MARK_DESKTOP).exists() &&
            (java.nio.file.Files.exists(bin.resolve("Xtigervnc"), nofollow) ||
                java.nio.file.Files.exists(bin.resolve("Xvnc"), nofollow))
    }

    /** Where the VNC socket appears on the Android side. */
    fun socketPath(c: Context) = File(CloudPcEngine.rootfs(c), GUEST_SOCKET.removePrefix("/")).absolutePath

    /** Installs XFCE + TigerVNC (+ Firefox when possible). Takes 5–15 minutes on a phone. */
    val INSTALL_SCRIPT = """
        set -e
        echo "==> apt-get update"
        apt-get update
        echo "==> Desktop (XFCE) + screen server (TigerVNC)"
        apt-get install -y --no-install-recommends \
            xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd xfce4-taskmanager mousepad ristretto \
            tigervnc-standalone-server dbus-x11 x11-xserver-utils at-spi2-core librsvg2-common \
            fonts-dejavu-core fonts-noto-color-emoji adwaita-icon-theme elementary-xfce-icon-theme greybird-gtk-theme \
            zenity wget xdg-utils libnotify-bin
        echo "==> Firefox (from Mozilla, Ubuntu's own is a snap that cannot run here)"
        (
            set -e
            mkdir -p /etc/apt/keyrings
            curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg -o /etc/apt/keyrings/packages.mozilla.org.asc
            echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
                > /etc/apt/sources.list.d/mozilla.list
            printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
            printf 'Package: firefox*\nPin: release o=Ubuntu\nPin-Priority: -1\n' > /etc/apt/preferences.d/no-snap-firefox
            apt-get update
            apt-get install -y firefox
        ) || echo "Firefox abhi nahi lag paya (baad me try karenge) – desktop phir bhi chalega"
        echo CLOUDPC_DESKTOP_OK
    """.trimIndent()

    /** Starts Xvnc + XFCE. Arguments: width height. Runs until the desktop is stopped. */
    private val START_SCRIPT = """
        #!/bin/bash
        # Cloud PC desktop: XFCE on a private VNC unix socket (no network port).
        # Arguments: width height light(0/1)
        W="${'$'}{1:-1280}"; H="${'$'}{2:-720}"; LIGHT="${'$'}{3:-0}"
        SOCK=$GUEST_SOCKET
        export HOME=/root USER=root LANG=C.UTF-8
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        mkdir -p "${'$'}XDG_RUNTIME_DIR" /tmp/.X11-unix /tmp/.ICE-unix
        chmod 700 "${'$'}XDG_RUNTIME_DIR"; chmod 1777 /tmp/.X11-unix /tmp/.ICE-unix
        rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 "${'$'}SOCK"
        rm -rf /root/.cache/sessions
        # Panel: start with the normal layout instead of the "first start" question.
        P=/root/.config/xfce4/xfconf/xfce-perchannel-xml
        if [ ! -f "${'$'}P/xfce4-panel.xml" ] && [ -f /etc/xdg/xfce4/panel/default.xml ]; then
            mkdir -p "${'$'}P" && cp /etc/xdg/xfce4/panel/default.xml "${'$'}P/xfce4-panel.xml"
        fi
        # Less work for the phone: no window shadows / animations.
        [ -f /usr/share/xfwm4/defaults ] && sed -i 's/^use_compositing=true/use_compositing=false/' /usr/share/xfwm4/defaults
        Xvnc :1 -geometry "${'$'}{W}x${'$'}{H}" -depth 24 -dpi 96 -SecurityTypes None -FrameRate 60 \
            -rfbunixpath "${'$'}SOCK" -rfbport -1 -AlwaysShared -AcceptSetDesktopSize &
        XPID=${'$'}!
        for i in ${'$'}(seq 1 150); do [ -S "${'$'}SOCK" ] && break; sleep 0.2; done
        echo CLOUDPC_VNC_READY
        export DISPLAY=:1 XDG_CURRENT_DESKTOP=XFCE NO_AT_BRIDGE=1
        # Speed: software OpenGL (llvmpipe) on every CPU core, no vsync waits, no GL error checks.
        export LP_NUM_THREADS="${'$'}(nproc 2>/dev/null || echo 4)" GALLIUM_DRIVER=llvmpipe vblank_mode=0 MESA_NO_ERROR=1
        # GTK4 apps: simple CPU drawing (much faster than OpenGL on a software GPU); no DRI3 probing.
        export GSK_RENDERER=cairo LIBGL_DRI3_DISABLE=1
        # Firefox's own Linux sandbox cannot work inside proot; the whole PC already runs inside
        # Android's app sandbox. "Fake no sandbox" = Firefox treats it like a kernel without
        # sandbox support (no crashes, no scary banner), unlike forcing it off.
        export MOZ_FAKE_NO_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
            MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 MOZ_DISABLE_UTILITY_SANDBOX=1
        # One session bus, saved so AirCursor can send commands into the desktop (wallpaper, apps).
        eval "${'$'}(dbus-launch --sh-syntax)"
        {
            echo "export DISPLAY=:1"
            echo "export DBUS_SESSION_BUS_ADDRESS='${'$'}DBUS_SESSION_BUS_ADDRESS'"
            echo "export XDG_RUNTIME_DIR=${'$'}XDG_RUNTIME_DIR NO_AT_BRIDGE=1 LANG=C.UTF-8"
        } > /tmp/cloudpc-session.env
        startxfce4 &
        # One helper (Android allows an app only ~32 programs, so no extra background shells):
        # first-start settings, app fixes, light mode, self-repair for the first minute, browser updates.
        (
            sleep 15
            # Live AirBoost resizes must not pop up the display settings window.
            xfconf-query -c displays -p /Notify --create -t int -s 0 >/dev/null 2>&1
            # One click opens desktop icons and files (easy with the hand cursor and touch). Set once.
            if [ ! -f /var/lib/cloudpc/single-click ]; then
                xfconf-query -c xfce4-desktop -p /desktop-icons/single-click --create -t bool -s true
                xfconf-query -c thunar -p /misc-single-click --create -t bool -s true
                mkdir -p /var/lib/cloudpc && touch /var/lib/cloudpc/single-click
            fi >/dev/null 2>&1
            # Every installed app: working launcher, desktop icon, Start menu entry.
            command -v cloudpc-apps >/dev/null && nice -n 10 cloudpc-apps >/dev/null 2>&1
            for i in 1 2 3 4 5; do
                pgrep -x xfwm4 >/dev/null || (xfwm4 --replace >/dev/null 2>&1 &)
                pgrep -x xfce4-panel >/dev/null || (xfce4-panel >/dev/null 2>&1 &)
                [ "${'$'}i" = 1 ] && [ "${'$'}LIGHT" = "1" ] && command -v cloudpc-light >/dev/null && cloudpc-light 0 >/dev/null 2>&1
                sleep 10
            done
            # Security updates for the browser, quietly (at most every 3 days).
            command -v cloudpc-security-update >/dev/null && nice -n 19 cloudpc-security-update
        ) &
        wait ${'$'}XPID
    """.trimIndent() + "\n"

    /** Writes the start script into Ubuntu (every start, so updates of the app reach it). */
    fun writeStartScript(c: Context) {
        CloudPcEngine.writeGuestFile(CloudPcEngine.rootfs(c).toPath(), SCRIPT, START_SCRIPT, executable = true)
    }

    fun startCommand(width: Int, height: Int, light: Boolean) = "bash /$SCRIPT $width $height ${if (light) 1 else 0}"

    /** A shell command that runs inside the running desktop session (DISPLAY + session bus). */
    fun inSession(command: String) = ". /tmp/cloudpc-session.env 2>/dev/null; $command"
}
