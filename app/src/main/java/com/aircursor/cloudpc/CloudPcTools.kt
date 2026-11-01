package com.aircursor.cloudpc

import android.content.Context
import java.io.File

/**
 * Desktop tools inside the built-in Cloud PC: App Store, Windows-style taskbar, wallpaper,
 * .deb installer and AI apps. The scripts are the same ones the Termux setup uses (bundled in
 * the APK as assets), plus a few small helpers for running as root.
 */
object CloudPcTools {
    /** Bump when the files below change: they are written again on the next desktop start. */
    const val VERSION = 2
    private const val LIB = "usr/local/lib/cloudpc"
    private const val MARK = "$LIB/.tools-version"
    private val SCRIPTS = listOf("app-store.sh", "ubuntu-extras.sh", "install-deb.sh", "windows-layout.sh", "wallpaper-gui.sh")

    fun needsSetup(c: Context): Boolean =
        File(CloudPcEngine.rootfs(c), MARK).let { !it.exists() || it.readText().trim() != VERSION.toString() }

    /** Packages the tools need (small). Installed once; failures (no internet) are not fatal. */
    const val PACKAGES_SCRIPT =
        "apt-get install -y --no-install-recommends zenity wget xdg-utils libnotify-bin >/dev/null 2>&1 || " +
            "(apt-get update >/dev/null 2>&1 && apt-get install -y --no-install-recommends zenity wget xdg-utils libnotify-bin) " +
            "|| echo 'Tools packages: internet nahi mila, baad me dobara try hoga'"

    fun writeFiles(c: Context) {
        val root = CloudPcEngine.rootfs(c).toPath()
        fun put(rel: String, text: String, exec: Boolean = false) = CloudPcEngine.writeGuestFile(root, rel, text, exec)

        for (s in SCRIPTS) {
            val text = c.assets.open("cloudpc/$s").bufferedReader().use { it.readText() }
            put("$LIB/$s", text, exec = true)
        }
        // Cloud PC runs as root: "sudo" in the shared scripts simply runs the command.
        put(
            "usr/local/bin/sudo",
            """
            #!/bin/sh
            # Cloud PC (AirCursor) runs as root, so sudo just runs the command.
            while [ ${'$'}# -gt 0 ]; do
                case "${'$'}1" in
                    -u|-g) shift 2 ;;
                    --) shift; break ;;
                    -*) shift ;;
                    *) break ;;
                esac
            done
            export SUDO_USER="${'$'}{SUDO_USER:-root}"
            exec "${'$'}@"
            """.trimIndent() + "\n",
            exec = true,
        )
        val wrappers = mapOf(
            "aircursor-store" to "app-store.sh",
            "ubuntu-extras" to "ubuntu-extras.sh",
            "aircursor-install-deb" to "install-deb.sh",
            "aircursor-windows-layout" to "windows-layout.sh",
            "aircursor-wallpaper" to "wallpaper-gui.sh",
        )
        for ((name, script) in wrappers) {
            put("usr/local/bin/$name", "#!/bin/sh\nexport SUDO_USER=root\nexec bash /$LIB/$script \"\$@\"\n", exec = true)
        }
        put("usr/local/bin/aircursor-set-wallpaper", SET_WALLPAPER, exec = true)
        put("usr/local/bin/cloudpc-ai-offline", AI_OFFLINE, exec = true)
        put("usr/local/bin/cloudpc-light", LIGHT_MODE, exec = true)

        // "Getting Started" guide (opens in the browser)
        put("usr/share/aircursor/guide.html", c.assets.open("cloudpc/guide.html").bufferedReader().use { it.readText() })
        put(
            "usr/share/applications/aircursor-guide.desktop",
            entry("Getting Started", "xdg-open /usr/share/aircursor/guide.html", "help-browser", "Utility;", "How to use AirCursor PC"),
        )

        // Menu entries
        put("usr/share/applications/aircursor-store.desktop", entry("App Store", "aircursor-store", "system-software-install", "System;", "Apps install karo – tick karo aur Install"))
        put("usr/share/applications/aircursor-wallpaper.desktop", entry("Change Wallpaper", "aircursor-wallpaper", "preferences-desktop-wallpaper", "Settings;DesktopSettings;", "Wallpaper badlo"))
        put(
            "usr/share/applications/aircursor-install-deb.desktop",
            "[Desktop Entry]\nType=Application\nName=Install app package\nExec=aircursor-install-deb %f\n" +
                "Icon=system-software-install\nNoDisplay=true\nMimeType=application/vnd.debian.binary-package;\n",
        )
        for (ai in AI_APPS) put("usr/share/applications/${ai.id}.desktop", ai.desktopEntry())

        // Windows-style taskbar on the first desktop start (the script remembers it ran).
        put(
            "etc/xdg/autostart/aircursor-windows-layout.desktop",
            "[Desktop Entry]\nType=Application\nName=Cloud PC Windows-style layout\nExec=aircursor-windows-layout\n" +
                "OnlyShowIn=XFCE;\nNoDisplay=true\n",
        )
        // Firefox: fewer background processes (less RAM on a phone), no first-run pages.
        put("etc/firefox/policies/policies.json", FIREFOX_POLICIES)

        // Desktop icons (only added once, so the user can delete them).
        val desk = "root/Desktop"
        val icons = listOf(
            "Getting-Started.desktop" to "usr/share/applications/aircursor-guide.desktop",
            "App-Store.desktop" to "usr/share/applications/aircursor-store.desktop",
            "Firefox.desktop" to "usr/share/applications/firefox.desktop",
            "Terminal.desktop" to "usr/share/applications/xfce4-terminal.desktop",
            "Change-Wallpaper.desktop" to "usr/share/applications/aircursor-wallpaper.desktop",
        )
        val r = CloudPcEngine.rootfs(c)
        for ((name, src) in icons) {
            val target = File(r, "$desk/$name")
            val source = File(r, src)
            if (!target.exists() && source.isFile) put("$desk/$name", source.readText(), exec = true)
        }
        for (ai in AI_APPS) {
            val rel = "$desk/AI Apps/${ai.id}.desktop"
            if (!File(r, rel).exists()) put(rel, ai.desktopEntry(), exec = true)
        }
        // Folders for moving files between the phone and the PC (toolbar → Files).
        File(r, "$desk/$TO_PHONE").mkdirs()
        File(r, "root/Downloads/$FROM_PHONE").mkdirs()
        put(MARK, "$VERSION\n")
    }

    const val TO_PHONE = "To phone"
    const val FROM_PHONE = "From phone"

    private fun entry(name: String, exec: String, icon: String, categories: String, comment: String) =
        "[Desktop Entry]\nType=Application\nName=$name\nComment=$comment\nExec=$exec\nIcon=$icon\n" +
            "Terminal=false\nCategories=$categories\n"

    private data class AiApp(val id: String, val name: String, val exec: String, val icon: String, val comment: String) {
        fun desktopEntry() = "[Desktop Entry]\nType=Application\nName=$name\nComment=$comment\nExec=$exec\n" +
            "Icon=$icon\nTerminal=false\nCategories=Network;Education;\nKeywords=AI;chat;assistant;\n"
    }

    private val AI_APPS = listOf(
        AiApp("cloudpc-ai-claude", "Claude (AI)", "firefox --new-window https://claude.ai", "web-browser", "Claude AI assistant (website)"),
        AiApp("cloudpc-ai-chatgpt", "ChatGPT (AI)", "firefox --new-window https://chatgpt.com", "web-browser", "ChatGPT (website)"),
        AiApp("cloudpc-ai-gemini", "Gemini (AI)", "firefox --new-window https://gemini.google.com", "web-browser", "Google Gemini (website)"),
        AiApp("cloudpc-ai-offline", "Offline AI (Ollama)", "cloudpc-ai-offline", "utilities-terminal", "Bina internet AI chat – chhote models, phone par dheere"),
    )

    private val SET_WALLPAPER = """
        #!/bin/bash
        # Sets a picture as the wallpaper of the running Cloud PC desktop (used by the phone's photo picker).
        IMG="${'$'}1"
        [ -f "${'$'}IMG" ] || { echo "Photo nahi mili: ${'$'}IMG"; exit 1; }
        . /tmp/cloudpc-session.env 2>/dev/null
        export DISPLAY=:1
        case "${'$'}IMG" in *.webp|*.WEBP) dpkg -s webp-pixbuf-loader >/dev/null 2>&1 || apt-get install -y webp-pixbuf-loader >/dev/null 2>&1 ;; esac
        ln -sf "${'$'}IMG" /usr/share/xfce4/backdrops/xubuntu-wallpaper.png 2>/dev/null
        MON=${'$'}(xrandr --listmonitors 2>/dev/null | awk 'NR>1 {print ${'$'}NF}' | head -n 1)
        MON=${'$'}{MON:-VNC-0}
        for ws in 0 1 2 3; do
            xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor${'$'}MON/workspace${'$'}ws/last-image --create -t string -s "${'$'}IMG"
            xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor${'$'}MON/workspace${'$'}ws/image-style --create -t int -s 5
        done
        for p in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep -E '/last-image${'$'}'); do
            xfconf-query -c xfce4-desktop -p "${'$'}p" -s "${'$'}IMG"
        done
        xfdesktop --reload >/dev/null 2>&1 &
        echo "Wallpaper set"
    """.trimIndent() + "\n"

    private val AI_OFFLINE = """
        #!/bin/bash
        # Offline AI chat (Ollama). Installs it on first use.
        export GSK_RENDERER=cairo NO_AT_BRIDGE=1
        if ! command -v ai-chat >/dev/null 2>&1; then
            zenity --question --width=440 --title="Offline AI" --ok-label="Install" --cancel-label="Baad me" \
                --text="Offline AI (Ollama) abhi install nahi hai.\n\nInstall karein? (~1.5 GB download, phone par AI dheere chalta hai)" || exit 0
            ( ubuntu-extras install ollama 2>&1 | sed -u -r 's/\x1b\[[0-9;]*m//g; s/^/# /' ) \
                | zenity --progress --pulsate --auto-close --no-cancel --width=600 --title="Installing Offline AI"
            command -v ai-chat >/dev/null 2>&1 || { zenity --error --text="Install nahi hua (internet?)"; exit 1; }
        fi
        exec xfce4-terminal --title="Offline AI" -x bash -lc 'echo "Pehli baar model download hoga (~1 GB)."; ai-chat; read -r -p "Band karne ke liye Enter dabao"'
    """.trimIndent() + "\n"

    /** AirBoost light mode: stops background jobs a phone does not need. */
    private val LIGHT_MODE = """
        #!/bin/sh
        sleep "${'$'}{1:-25}"
        pkill -x tumblerd; pkill -x xfce4-power-manager; pkill -f xfce4-screensaver; pkill -x light-locker; pkill -f xfce4-notes
        exit 0
    """.trimIndent() + "\n"

    private val FIREFOX_POLICIES = """
        {
          "policies": {
            "DisableAppUpdate": true,
            "DontCheckDefaultBrowser": true,
            "OverrideFirstRunPage": "",
            "Preferences": {
              "dom.ipc.processCount": { "Value": 2, "Status": "default" },
              "browser.tabs.unloadOnLowMemory": { "Value": true, "Status": "default" }
            }
          }
        }
    """.trimIndent() + "\n"
}
