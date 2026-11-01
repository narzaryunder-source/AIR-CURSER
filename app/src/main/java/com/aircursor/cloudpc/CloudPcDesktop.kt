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
        return File(r, MARK_DESKTOP).exists() && File(r, "usr/bin/Xvnc").exists()
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
            fonts-dejavu-core fonts-noto-color-emoji adwaita-icon-theme elementary-xfce-icon-theme greybird-gtk-theme
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
        W="${'$'}{1:-1280}"; H="${'$'}{2:-720}"
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
        Xvnc :1 -geometry "${'$'}{W}x${'$'}{H}" -depth 24 -dpi 96 -SecurityTypes None \
            -rfbunixpath "${'$'}SOCK" -rfbport -1 -AlwaysShared -AcceptSetDesktopSize &
        XPID=${'$'}!
        for i in ${'$'}(seq 1 150); do [ -S "${'$'}SOCK" ] && break; sleep 0.2; done
        echo CLOUDPC_VNC_READY
        export DISPLAY=:1
        export GTK_THEME=Greybird
        dbus-launch --exit-with-session startxfce4 &
        wait ${'$'}XPID
    """.trimIndent() + "\n"

    /** Writes the start script into Ubuntu (every start, so updates of the app reach it). */
    fun writeStartScript(c: Context) {
        CloudPcEngine.writeGuestFile(CloudPcEngine.rootfs(c).toPath(), SCRIPT, START_SCRIPT, executable = true)
    }

    fun startCommand(width: Int, height: Int) = "bash /$SCRIPT $width $height"
}
