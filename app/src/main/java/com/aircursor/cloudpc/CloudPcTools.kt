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
    const val VERSION = 26
    private const val LIB = "usr/local/lib/cloudpc"
    private const val MARK = "$LIB/.tools-version"
    private val SCRIPTS = listOf(
        "app-store.sh", "app-store.py", "ubuntu-extras.sh", "install-deb.sh", "windows-layout.sh", "wallpaper-gui.sh",
        "launch.sh", "apps-setup.sh", "style.sh", "airstore.py", "airstore.html", "web-icons.py",
    )

    fun needsSetup(c: Context): Boolean =
        File(CloudPcEngine.rootfs(c), MARK).let { !it.exists() || it.readText().trim() != VERSION.toString() }

    /** Packages the tools need (App Store window, dialogs). Failures (no internet) are not fatal. */
    private const val TOOL_PACKAGES = "zenity wget xdg-utils libnotify-bin python3-gi gir1.2-gtk-3.0 xclip " +
        "xdotool xfce4-screenshooter xarchiver thunar-archive-plugin unzip zip p7zip-full " +
        "pulseaudio pulseaudio-utils pavucontrol"
    const val PACKAGES_SCRIPT =
        "apt-get install -y --no-install-recommends $TOOL_PACKAGES >/dev/null 2>&1 || " +
            "(apt-get update >/dev/null 2>&1 && apt-get install -y --no-install-recommends $TOOL_PACKAGES) " +
            "|| echo 'Tools packages: no internet, will retry later'"

    private fun asset(c: Context, name: String) = c.assets.open("cloudpc/$name").bufferedReader().use { it.readText() }

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
            "cloudpc-launch" to "launch.sh",
            "cloudpc-apps" to "apps-setup.sh",
            "ubuntu-extras" to "ubuntu-extras.sh",
            "aircursor-install-deb" to "install-deb.sh",
            "aircursor-windows-layout" to "windows-layout.sh",
            "aircursor-wallpaper" to "wallpaper-gui.sh",
            "aircursor-style" to "style.sh",
        )
        for ((name, script) in wrappers) {
            put("usr/local/bin/$name", "#!/bin/sh\nexport SUDO_USER=root\nexec bash /$LIB/$script \"\$@\"\n", exec = true)
        }
        // App Store: the new window (GTK) when Python's GTK support is there, else the simple one.
        put(
            "usr/local/bin/aircursor-store",
            "#!/bin/sh\nexport SUDO_USER=root\n" +
                "if python3 -c 'import gi; gi.require_version(\"Gtk\", \"3.0\"); from gi.repository import Gtk' 2>/dev/null; then\n" +
                "    exec python3 /$LIB/app-store.py \"\$@\"\nfi\nexec bash /$LIB/app-store.sh \"\$@\"\n",
            exec = true,
        )
        // After every app install/remove (App Store, terminal, .deb from the browser): fix the app,
        // add its desktop icon and Start menu favourite.
        put(
            "etc/apt/apt.conf.d/80-cloudpc-apps",
            "DPkg::Post-Invoke { \"if [ -x /usr/local/bin/cloudpc-apps ]; then /usr/local/bin/cloudpc-apps >/dev/null 2>&1 || true; fi\"; };\n",
        )
        // Desktop helpers a phone does not need: each would count against Android's 32-program limit.
        for (name in HIDDEN_AUTOSTART) {
            put("root/.config/autostart/$name", "[Desktop Entry]\nType=Application\nName=$name\nHidden=true\n")
        }
        // Fast settings for 3D games on phones (only settings the person has not changed).
        addMissing(File(CloudPcEngine.rootfs(c), "root/.minetest/minetest.conf"), MINETEST_FAST, root, "root/.minetest/minetest.conf")
        // Games: FPS overlay (Right Shift + F12 hides it) and the steady FPS limit.
        put("etc/cloudpc/MangoHud.conf", AirBoost.mangoHud(AirBoost.fpsLimit(c)))
        put("usr/local/bin/aircursor-set-wallpaper", SET_WALLPAPER, exec = true)
        put("usr/local/bin/cloudpc-ai-offline", AI_OFFLINE, exec = true)
        put("usr/local/bin/cloudpc-light", LIGHT_MODE, exec = true)
        put("usr/local/bin/cloudpc-resize", RESIZE, exec = true)
        put("usr/local/bin/cloudpc-security-update", SECURITY_UPDATE, exec = true)
        put("usr/local/bin/cloudpc-benchmark", BENCHMARK, exec = true)
        put("usr/local/bin/cloudpc-vscode", VSCODE, exec = true)
        // Logins in the phone's own browser (fast, Google sign-in works): Claude Code and any tool
        // that opens a web page through these names.
        put("usr/local/bin/cloudpc-open-on-phone", OPEN_ON_PHONE, exec = true)
        for (name in listOf("xdg-open", "sensible-browser", "x-www-browser", "www-browser")) {
            put("$LIB/phone-browser/$name", "#!/bin/sh\nexec cloudpc-open-on-phone \"\$@\"\n", exec = true)
        }
        put("usr/local/bin/cloudpc-claude", CLAUDE, exec = true)
        put("usr/local/bin/cloudpc-web", WEB, exec = true)
        put("usr/local/bin/cloudpc-windows-keys", WINDOWS_KEYS, exec = true)
        // Sound: PulseAudio → "Phone speaker" → the AirCursor app.
        put("etc/cloudpc/pulse.pa", PULSE_PA)
        put("etc/pulse/client.conf.d/50-cloudpc.conf", "default-server = unix:/tmp/pulse-native\nautospawn = no\nenable-shm = no\n")
        // Files, GitHub, Google Drive
        put("usr/local/bin/cloudpc-send-to-phone", SEND_TO_PHONE, exec = true)
        // Focus boost: the app in front gets the phone's fast cores (started with the desktop).
        put("usr/local/bin/cloudpc-focus", FOCUS_BOOST, exec = true)
        // VS Code: light settings for a phone (only when the person has no settings of their own yet).
        for (rel in listOf("root/.config/Code/User/settings.json", "root/.vscode-server/data/Machine/settings.json")) {
            if (!File(CloudPcEngine.rootfs(c), rel).exists()) put(rel, VSCODE_SETTINGS)
        }
        // Air, AirCursor's browser (phone engine): the PC's default browser, Start menu, desktop icon.
        put("usr/local/bin/cloudpc-air", AIR, exec = true)
        // App windows in Air (no tabs or address bar): Air Store, TS Station, YouTube, web apps.
        put("usr/local/bin/cloudpc-app", APP, exec = true)
        put("usr/share/pixmaps/air-browser.svg", asset(c, "air-logo.svg"))
        val airEntry = "[Desktop Entry]\nType=Application\nName=Air\nGenericName=Web Browser\n" +
            "Comment=Fast browser on the phone's own engine – videos, AI apps, Google sign-in\n" +
            "Exec=cloudpc-air %u\nIcon=/usr/share/pixmaps/air-browser.svg\nTerminal=false\nStartupNotify=false\n" +
            "Categories=Network;WebBrowser;\nMimeType=text/html;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;\n"
        put("usr/share/applications/cloudpc-air.desktop", airEntry)
        if (!File(CloudPcEngine.rootfs(c), "var/lib/cloudpc/air-icon").exists()) {
            put("root/Desktop/Air.desktop", airEntry, exec = true)
            put("var/lib/cloudpc/air-icon", "1\n")
        }
        // Air Store: the app store, shown in Air (phone engine); the old App Store when Air is not there.
        put("usr/local/bin/airstore", AIRSTORE, exec = true)
        put("usr/share/pixmaps/airstore.svg", AIRSTORE_ICON)
        put("usr/share/applications/airstore.desktop",
            entry("Air Store", "airstore", "/usr/share/pixmaps/airstore.svg", "System;",
                "Apps, music, films, AI and games – one tap to get them"))
        // YouTube in Air (phone engine), its own app window: Start menu + one desktop icon (made once).
        val ytIcon = File(CloudPcEngine.rootfs(c), "usr/share/pixmaps/aircursor-web/youtube.com.png")
            .takeIf { it.isFile }?.let { "/usr/share/pixmaps/aircursor-web/youtube.com.png" } ?: "youtube"
        val yt = entry("YouTube", "cloudpc-web https://www.youtube.com/", ytIcon, "AudioVideo;Video;Network;",
            "YouTube, smooth – runs on the phone's own engine in a window over the PC")
        put("usr/share/applications/cloudpc-youtube.desktop", yt)
        val ytDesk = File(CloudPcEngine.rootfs(c), "root/Desktop/YouTube.desktop")
        if (!File(CloudPcEngine.rootfs(c), "var/lib/cloudpc/youtube-icon").exists() ||
            (ytDesk.isFile && ytDesk.readText().contains("m.youtube.com"))
        ) {
            put("root/Desktop/YouTube.desktop", yt, exec = true)
            put("var/lib/cloudpc/youtube-icon", "1\n")
        }
        // Smooth video: the phone's own video chip plays it in a window over the PC (Win+Y).
        put("usr/local/bin/cloudpc-phone-video", PHONE_VIDEO, exec = true)
        put("usr/share/applications/cloudpc-phone-video.desktop",
            entry("Smooth video (phone)", "cloudpc-phone-video", "video-display", "AudioVideo;Video;",
                "Play the video from the browser smoothly on the phone's video chip (Win+Y)"))
        put("usr/local/bin/cloudpc-github", GITHUB, exec = true)
        put("usr/local/bin/cloudpc-gdrive", GDRIVE, exec = true)
        put("usr/share/applications/cloudpc-github.desktop",
            entry("GitHub", "cloudpc-github", "github", "Development;", "Clone, publish and push your projects"))
        put("usr/share/applications/cloudpc-gdrive.desktop",
            entry("Google Drive", "cloudpc-gdrive", "folder-remote", "Network;", "Sync a folder with your Google Drive"))
        val r0 = CloudPcEngine.rootfs(c)
        for (d in listOf("Documents", "Pictures", "Music", "Videos", "Projects", "Google Drive")) File(r0, "root/$d").mkdirs()
        // Files sidebar: the folders people use most.
        val marks = File(r0, "root/.config/gtk-3.0/bookmarks")
        val have = if (marks.isFile) marks.readText() else ""
        val want = listOf("Projects", "Documents", "Downloads", "Google%20Drive Google Drive",
            "Desktop/To%20phone To phone", "Downloads/From%20phone From phone")
        val add = want.map { "file:///root/$it" }.filter { line -> !have.contains(line.substringBefore(' ')) }
        if (add.isNotEmpty()) put("root/.config/gtk-3.0/bookmarks", have + (if (have.isEmpty() || have.endsWith("\n")) "" else "\n") + add.joinToString("\n", postfix = "\n"))
        // Files right-click: "Send to phone".
        val uca = File(r0, "root/.config/Thunar/uca.xml")
        val ucaText = when {
            !uca.isFile -> File(r0, "etc/xdg/Thunar/uca.xml").takeIf { it.isFile }?.readText()
                ?: "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<actions>\n</actions>\n"
            else -> uca.readText()
        }
        var ucaNew = ucaText
        if (!ucaNew.contains("aircursor-send-to-phone")) ucaNew = ucaNew.replace("</actions>", UCA_SEND + "</actions>")
        if (!ucaNew.contains("aircursor-play-on-phone")) ucaNew = ucaNew.replace("</actions>", UCA_VIDEO + "</actions>")
        if (ucaNew != ucaText && ucaNew.contains("</actions>")) put("root/.config/Thunar/uca.xml", ucaNew)
        // Older Google Drive icon opened a terminal: use the new window.
        val oldDrive = File(r0, "root/Desktop/Google-Drive.desktop")
        if (oldDrive.isFile && oldDrive.readText().contains("google-drive")) {
            put("root/Desktop/Google-Drive.desktop", File(r0, "usr/share/applications/cloudpc-gdrive.desktop").readText(), exec = true)
        }
        // Claude's tools inside the PC (also handy for the person in the terminal).
        put("usr/local/bin/pc-doctor", PC_DOCTOR, exec = true)
        put("usr/local/bin/pc-open", PC_OPEN, exec = true)
        put("usr/local/bin/pc-screenshot", PC_SCREENSHOT, exec = true)
        val memory = File(CloudPcEngine.rootfs(c), "root/.claude/CLAUDE.md")
        if (!memory.exists() || memory.readText().startsWith("<!-- AirCursor PC")) put("root/.claude/CLAUDE.md", CLAUDE_MD)
        // apt waits for a running background update instead of failing with "could not get lock".
        put("etc/apt/apt.conf.d/02-cloudpc-lock", "DPkg::Lock::Timeout \"600\";\n")

        // "Getting Started" guide (opens in the browser)
        put("usr/share/aircursor/guide.html", c.assets.open("cloudpc/guide.html").bufferedReader().use { it.readText() })
        put(
            "usr/share/applications/aircursor-guide.desktop",
            entry(
                "Getting Started",
                "sh -c \"firefox /usr/share/aircursor/guide.html || xdg-open /usr/share/aircursor/guide.html\"",
                "help-browser", "Utility;", "How to use AirCursor PC",
            ),
        )

        // Menu entries
        // The old App Store is gone from menus and the desktop: only Air Store. It stays hidden as the
        // fallback for phones where Air cannot run (airstore opens it there).
        put("usr/share/applications/aircursor-store.desktop",
            entry("App Store (fallback)", "aircursor-store", "system-software-install", "System;", "Fallback for Air Store") + "NoDisplay=true\n")
        put("usr/share/applications/aircursor-wallpaper.desktop", entry("Change Wallpaper", "aircursor-wallpaper", "preferences-desktop-wallpaper", "Settings;DesktopSettings;", "Change the desktop picture"))
        put(
            "usr/share/applications/aircursor-install-deb.desktop",
            "[Desktop Entry]\nType=Application\nName=Install app package\nExec=aircursor-install-deb %f\n" +
                "Icon=system-software-install\nNoDisplay=true\nMimeType=application/vnd.debian.binary-package;\n",
        )
        for (ai in AI_APPS) put("usr/share/applications/${ai.id}.desktop", ai.desktopEntry())

        // Windows-style taskbar on the first desktop start (the script remembers it ran).
        put(
            "etc/xdg/autostart/aircursor-windows-layout.desktop",
            "[Desktop Entry]\nType=Application\nName=AirCursor PC style\nExec=aircursor-style auto\n" +
                "OnlyShowIn=XFCE;\nNoDisplay=true\n",
        )
        // The look: the wallpaper (real photo "City lights" from the International Space Station,
        // NASA, public domain; the older "Night" stays available) and the PC logo for the dock.
        put("usr/share/backgrounds/aircursor/night.svg", asset(c, "aircursor-wallpaper.svg"))
        CloudPcEngine.writeGuestBytes(root, "usr/share/backgrounds/aircursor/city.jpg",
            c.assets.open("cloudpc/aircursor-city.jpg").use { it.readBytes() })
        put("usr/share/backgrounds/aircursor/CREDITS.txt",
            "city.jpg – \"The US East Coast before dawn\", photographed by NASA astronaut Reid Wiseman from the\n" +
                "International Space Station (ISS040-E-089021). NASA image, public domain.\n" +
                "night.svg – AirCursor PC's own wallpaper.\n")
        put("usr/share/pixmaps/aircursor-pc.svg", asset(c, "aircursor-logo.svg"))
        put("usr/share/pixmaps/aircursor-start.svg", asset(c, "aircursor-start.svg"))
        // Firefox: fewer background processes (less RAM on a phone), no first-run pages.
        put("etc/firefox/policies/policies.json", FIREFOX_POLICIES)
        put("usr/lib/firefox/defaults/pref/aircursor-pc.js", FIREFOX_PREFS)
        // Brave (default browser, also AI web apps): smooth YouTube and fewer background jobs.
        put("etc/brave/policies/managed/aircursor-pc.json", BRAVE_POLICIES)
        put(
            "usr/share/applications/cloudpc-benchmark.desktop",
            entry("Benchmark", "cloudpc-benchmark", "utilities-system-monitor", "System;", "Test graphics speed (FPS) with your AirBoost profile"),
        )

        // Desktop icons (only added once, so the user can delete them). A clean desktop: the main
        // ones on it, the other tools in a "Tools" folder.
        val desk = "root/Desktop"
        val icons = listOf(
            "Getting-Started.desktop" to "usr/share/applications/aircursor-guide.desktop",
            "Air-Store.desktop" to "usr/share/applications/airstore.desktop",
            "Tools/Benchmark.desktop" to "usr/share/applications/cloudpc-benchmark.desktop",
            "Tools/Firefox.desktop" to "usr/share/applications/firefox.desktop",
            "Tools/Terminal.desktop" to "usr/share/applications/xfce4-terminal.desktop",
            "Tools/Change-Wallpaper.desktop" to "usr/share/applications/aircursor-wallpaper.desktop",
            "Tools/GitHub.desktop" to "usr/share/applications/cloudpc-github.desktop",
            "Tools/Google-Drive.desktop" to "usr/share/applications/cloudpc-gdrive.desktop",
        )
        val r = CloudPcEngine.rootfs(c)
        for ((name, src) in icons) {
            val target = File(r, "$desk/$name")
            val source = File(r, src)
            // Already there – on the desktop itself or moved into Tools by the person or the tidy-up.
            val there = target.exists() || File(r, "$desk/${name.substringAfterLast('/')}").exists() ||
                File(r, "$desk/Tools/${name.substringAfterLast('/')}").exists()
            // The guide icon is ours: always refresh it. Other icons are added once (the user may delete them).
            val refresh = name == "Getting-Started.desktop" && target.exists()
            if ((refresh || !there) && source.isFile) put("$desk/$name", source.readText(), exec = true)
        }
        // Old App Store icons made by earlier versions go (the Air Store icon stays).
        for (old in listOf("App-Store.desktop", "aircursor-store.desktop", "Tools/App-Store.desktop", "Tools/aircursor-store.desktop")) {
            File(r, "$desk/$old").takeIf { it.isFile && it.readText().contains("aircursor-store") }?.delete()
        }
        for (ai in AI_APPS) {
            val rel = "$desk/AI Apps/${ai.id}.desktop"
            put(rel, ai.desktopEntry(), exec = true)   // ours: kept up to date (browser choice etc.)
        }
        // Folders for moving files between the phone and the PC (toolbar → Files).
        File(r, "$desk/$TO_PHONE").mkdirs()
        File(r, "root/Downloads/$FROM_PHONE").mkdirs()
        put(MARK, "$VERSION\n")
    }

    private val HIDDEN_AUTOSTART = listOf(
        "at-spi-dbus-bus.desktop", "xscreensaver.desktop", "xfce4-screensaver.desktop", "light-locker.desktop",
        "xfce4-power-manager.desktop", "print-applet.desktop", "polkit-gnome-authentication-agent-1.desktop",
        "xfce-polkit.desktop", "gnome-keyring-pkcs11.desktop", "gnome-keyring-secrets.desktop",
        "gnome-keyring-ssh.desktop", "geoclue-demo-agent.desktop", "update-notifier.desktop", "xiccd.desktop",
        "blueman.desktop", "nm-applet.desktop", "pulseaudio.desktop", "xfce4-clipman-plugin-autostart.desktop",
        "tracker-miner-fs-3.desktop", "org.gnome.Evolution-alarm-notify.desktop",
    )

    /** Minetest on a phone CPU: no shaders and effects, shorter view range, steady 45 FPS. */
    private val MINETEST_FAST = listOf(
        "enable_shaders" to "false", "smooth_lighting" to "false", "enable_particles" to "false",
        "enable_clouds" to "false", "enable_3d_clouds" to "false", "enable_waving_water" to "false",
        "enable_waving_leaves" to "false", "enable_waving_plants" to "false", "opaque_water" to "true",
        "connected_glass" to "false", "mip_map" to "false", "fsaa" to "0", "viewing_range" to "60",
        "fps_max" to "45", "enable_minimap" to "false",
    )

    /** Adds only the keys that are not in the file yet, so the person's own choices stay. */
    private fun addMissing(file: File, settings: List<Pair<String, String>>, root: java.nio.file.Path, rel: String) {
        val old = if (file.isFile) file.readText() else ""
        val have = old.lines().mapNotNull { l -> l.substringBefore('=').trim().takeIf { it.isNotEmpty() && !it.startsWith("#") } }.toSet()
        val add = settings.filter { it.first !in have }
        if (add.isEmpty()) return
        val text = buildString {
            append(old)
            if (old.isNotEmpty() && !old.endsWith("\n")) append('\n')
            append("# AirCursor PC: fast settings for phones (change them in the game's settings)\n")
            for ((k, v) in add) append("$k = $v\n")
        }
        CloudPcEngine.writeGuestFile(root, rel, text)
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
        AiApp("cloudpc-ai-claude", "Claude (AI)", "cloudpc-web https://claude.ai", "web-browser", "Claude AI assistant (website)"),
        AiApp("cloudpc-ai-chatgpt", "ChatGPT (AI)", "cloudpc-web https://chatgpt.com", "web-browser", "ChatGPT (website)"),
        AiApp("cloudpc-ai-gemini", "Gemini (AI)", "cloudpc-web https://gemini.google.com", "web-browser", "Google Gemini (website)"),
        AiApp("cloudpc-ai-offline", "Offline AI (Ollama)", "cloudpc-ai-offline", "utilities-terminal", "AI chat without internet – small models, slow on phones"),
        AiApp(
            "cloudpc-ai-claude-code", "Claude Code", "xfce4-terminal --title=\"Claude Code\" -x cloudpc-claude",
            "utilities-terminal", "AI coding assistant in the terminal – you sign in on your phone",
        ),
        AiApp(
            "cloudpc-ai-pc-assistant", "PC Assistant (Claude)",
            "xfce4-terminal --title=\"PC Assistant\" -x cloudpc-claude --assistant",
            "utilities-system-monitor", "Claude checks and fixes the PC: speed, FPS, apps – and builds and opens apps for you",
        ),
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
        . /tmp/cloudpc-session.env 2>/dev/null
        # No animations; windows move and resize as an outline (far less drawing for the phone).
        xfconf-query -c xsettings -p /Gtk/EnableAnimations --create -t bool -s false
        xfconf-query -c xfwm4 -p /general/box_move --create -t bool -s true
        xfconf-query -c xfwm4 -p /general/box_resize --create -t bool -s true
        exit 0
    """.trimIndent() + "\n"

    /**
     * VS Code. Most phones give apps only a 39-bit address space; the desktop VS Code window
     * (Chromium inside) needs more and crashes there ("window terminated unexpectedly, code 5").
     * Then VS Code runs as Microsoft's own VS Code Server and opens in Firefox: same editor,
     * terminal and extensions. A random key keeps other apps on the phone out.
     */
    private val VSCODE = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        export LANG=C.UTF-8
        CODE=/usr/share/code
        CONF=/root/.config/cloudpc-vscode
        mkdir -p "${'$'}CONF" && chmod 700 "${'$'}CONF"
        MODE=${'$'}(cat "${'$'}CONF/mode" 2>/dev/null)
        if [ -z "${'$'}MODE" ]; then
            BITS=${'$'}(awk '!/vsyscall/ { split(${'$'}1, a, "-"); if (length(a[2]) > m) m = length(a[2]) } END { print m }' /proc/self/maps)
            if [ "${'$'}{BITS:-12}" -le 10 ]; then MODE=web; else MODE=desktop; fi
        fi
        # The desktop window crashed here before ("renderer process gone")? Then use the browser mode.
        if [ "${'$'}MODE" = desktop ] && grep -rqsi "process gone" /root/.config/Code/logs 2>/dev/null; then
            MODE=web
            echo web > "${'$'}CONF/mode"
        fi
        if [ "${'$'}MODE" = desktop ]; then
            exec "${'$'}CODE/code" "${'$'}@"
        fi
        PORT=8765
        [ -s "${'$'}CONF/token" ] || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "${'$'}CONF/token"
        TOKEN=${'$'}(cat "${'$'}CONF/token")
        FOLDER=/root
        for a in "${'$'}@"; do
            case "${'$'}a" in --*|"") continue ;; esac
            if [ -d "${'$'}a" ]; then FOLDER=${'$'}(readlink -f "${'$'}a"); elif [ -f "${'$'}a" ]; then FOLDER=${'$'}(dirname "${'$'}(readlink -f "${'$'}a")"); fi
        done
        up() { (exec 3<>"/dev/tcp/127.0.0.1/${'$'}PORT") 2>/dev/null; }
        if ! up; then
            notify-send -a "AirCursor PC" -i code "Starting VS Code…" \
                "The first start downloads the VS Code editor (about 70 MB). Keep the internet on." >/dev/null 2>&1 &
            T="${'$'}CODE/bin/code-tunnel"
            [ -x "${'$'}T" ] || T="${'$'}CODE/bin/code"
            cd /root || exit 1
            nohup "${'$'}T" serve-web --host 127.0.0.1 --port "${'$'}PORT" --connection-token "${'$'}TOKEN" \
                --accept-server-license-terms > /tmp/vscode-web.log 2>&1 &
            for i in ${'$'}(seq 1 180); do up && break; sleep 1; done
            if ! up; then
                zenity --error --no-markup --width=560 --title="VS Code" \
                    --text="VS Code could not start. Check the internet and try again.

${'$'}(tail -n 6 /tmp/vscode-web.log)" >/dev/null 2>&1
                exit 1
            fi
        fi
        exec cloudpc-web "http://127.0.0.1:${'$'}PORT/?tkn=${'$'}TOKEN&folder=${'$'}FOLDER"
    """.trimIndent() + "\n"

    /** Opens a web link in the phone's browser: the AirCursor app watches the clipboard for this marker. */
    private val OPEN_ON_PHONE = """
        #!/bin/sh
        URL="${'$'}1"
        case "${'$'}URL" in
            http://*|https://*) ;;
            *) exec /usr/bin/xdg-open "${'$'}@" ;;
        esac
        . /tmp/cloudpc-session.env 2>/dev/null
        # The desktop hands the clipboard to the AirCursor app (it asks twice: types, then text).
        printf '%s' "aircursor-open:${'$'}URL" | timeout 15 xclip -quiet -selection clipboard >/dev/null 2>&1 &
        notify-send -a "AirCursor PC" -i web-browser "Opening on your phone…"             "Sign in there, then come back to AirCursor PC." >/dev/null 2>&1 &
        echo ""
        echo "  The page opens in your phone's browser. Sign in there, then come back here."
        echo "  (Link: ${'$'}URL)"
        echo ""
        exit 0
    """.trimIndent() + "\n"

    /** PulseAudio: one virtual "Phone speaker"; its sound goes to the AirCursor app through a private socket. */
    private val PULSE_PA = """
        load-module module-null-sink sink_name=phone rate=44100 channels=2 sink_properties=device.description=Phone-speaker
        set-default-sink phone
        load-module module-native-protocol-unix auth-anonymous=1 socket=/tmp/pulse-native
        load-module module-simple-protocol-unix socket=/tmp/cloudpc-audio.sock source=phone.monitor record=true playback=false format=s16le rate=44100 channels=2
    """.trimIndent() + "\n"

    /** Files → right-click → Send to phone (a folder or several files are zipped first). */
    private val SEND_TO_PHONE = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        [ ${'$'}# -gt 0 ] || exit 1
        if [ ${'$'}# -eq 1 ] && [ -f "${'$'}1" ]; then
            F=${'$'}(readlink -f "${'$'}1")
        else
            mkdir -p /tmp/aircursor-share
            NAME=${'$'}(basename "${'$'}1")
            [ ${'$'}# -gt 1 ] && NAME="files"
            F="/tmp/aircursor-share/${'$'}NAME.zip"
            rm -f "${'$'}F"
            cd "${'$'}(dirname "${'$'}(readlink -f "${'$'}1")")" || exit 1
            args=(); for a in "${'$'}@"; do args+=("${'$'}(basename "${'$'}a")"); done
            zip -qr "${'$'}F" "${'$'}{args[@]}" || { zenity --error --text="Could not pack the files."; exit 1; }
        fi
        printf '%s' "aircursor-save:${'$'}F" | timeout 15 xclip -quiet -selection clipboard >/dev/null 2>&1 &
        notify-send -a "AirCursor PC" -i phone "Send to phone" "Choose where to save it on your phone." >/dev/null 2>&1 &
        exit 0
    """.trimIndent() + "\n"

    /** GitHub: sign in (on the phone), clone, publish a folder, save and get changes. */
    private val GITHUB = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        export LANG=C.UTF-8 GSK_RENDERER=cairo NO_AT_BRIDGE=1
        T="GitHub"
        P=/root/Projects
        mkdir -p "${'$'}P"
        if ! command -v gh >/dev/null 2>&1; then
            zenity --question --width=420 --title="${'$'}T" --ok-label="Install" \
                --text="Install the GitHub tools (git + GitHub CLI, about 20 MB)?" || exit 0
            ( ubuntu-extras install github 2>&1 | sed -u 's/^/# /' ) |
                zenity --progress --pulsate --auto-close --no-cancel --width=480 --title="${'$'}T" --text="Installing…"
            command -v gh >/dev/null 2>&1 || { zenity --error --text="Could not install. Check the internet."; exit 1; }
        fi
        if ! gh auth status >/dev/null 2>&1; then
            zenity --info --width=480 --title="${'$'}T" \
                --text="Sign in to GitHub:\n\n1. A terminal shows a code (like ABCD-1234).\n2. GitHub's page opens in your PHONE's browser – type the code there and tap Authorize.\n3. Come back here."
            xfce4-terminal --title="GitHub sign-in" --hold -x env PATH="/usr/local/lib/cloudpc/phone-browser:${'$'}PATH" \
                BROWSER=cloudpc-open-on-phone gh auth login -h github.com -p https -w
            gh auth status >/dev/null 2>&1 || { zenity --error --text="Not signed in yet. Open GitHub again to retry."; exit 0; }
            gh auth setup-git >/dev/null 2>&1
            LOGIN=${'$'}(gh api user -q .login 2>/dev/null); ID=${'$'}(gh api user -q .id 2>/dev/null)
            [ -n "${'$'}LOGIN" ] && git config --global user.name "${'$'}LOGIN"
            [ -n "${'$'}ID" ] && git config --global user.email "${'$'}ID+${'$'}LOGIN@users.noreply.github.com"
            git config --global init.defaultBranch main
        fi
        pick_repo() {
            zenity --file-selection --directory --filename="${'$'}P/" --title="Choose the project folder"
        }
        run() {   # title, then a shell command
            local title="${'$'}1"; shift
            ( bash -c "${'$'}*" 2>&1 | sed -u 's/^/# /'; echo "# Done." ) |
                zenity --progress --pulsate --auto-close --width=520 --title="${'$'}title" --text="${'$'}title…"
        }
        while true; do
            C=${'$'}(zenity --list --width=560 --height=420 --title="${'$'}T" \
                --text="Signed in as ${'$'}(gh api user -q .login 2>/dev/null). Your projects live in Home → Projects." \
                --column=id --column="What do you want to do?" --hide-column=1 --print-column=1 \
                clone "Download (clone) a repository" \
                publish "Publish a folder as a new repository" \
                push "Save my changes to GitHub (push)" \
                pull "Get the latest changes (pull)" \
                folder "Open the Projects folder" \
                web "Open github.com" \
                logout "Sign out") || exit 0
            case "${'$'}C" in
                clone)
                    R=${'$'}(zenity --entry --width=460 --title="Clone" --text="Repository (owner/name or a GitHub link):") || continue
                    [ -n "${'$'}R" ] && run "Downloading ${'$'}R" "cd '${'$'}P' && gh repo clone '${'$'}R'"
                    setsid thunar "${'$'}P" >/dev/null 2>&1 & ;;
                publish)
                    D=${'$'}(pick_repo) || continue
                    N=${'$'}(zenity --entry --title="Publish" --text="Name on GitHub:" --entry-text="${'$'}(basename "${'$'}D")") || continue
                    V=${'$'}(zenity --list --title="Publish" --text="Who can see it?" --column=v private public | head -n1)
                    run "Publishing ${'$'}N" "cd '${'$'}D' && { [ -d .git ] || git init; } && git add -A && { git commit -m 'First upload from AirCursor PC' || true; } && gh repo create '${'$'}N' --${'$'}{V:-private} --source . --push" ;;
                push)
                    D=${'$'}(pick_repo) || continue
                    M=${'$'}(zenity --entry --title="Save changes" --text="What did you change? (short note)" --entry-text="Update from AirCursor PC") || continue
                    run "Saving changes" "cd '${'$'}D' && git add -A && { git commit -m '${'$'}{M//\'/}' || true; } && git push" ;;
                pull)
                    D=${'$'}(pick_repo) || continue
                    run "Getting changes" "cd '${'$'}D' && git pull" ;;
                folder) setsid thunar "${'$'}P" >/dev/null 2>&1 & ;;
                web) setsid cloudpc-web https://github.com >/dev/null 2>&1 & ;;
                logout) gh auth logout -h github.com >/dev/null 2>&1 <<< "Y"; exit 0 ;;
            esac
        done
    """.trimIndent() + "\n"

    /** Google Drive: sign in on the phone, a "Google Drive" folder in Home that syncs both ways. */
    private val GDRIVE = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        export LANG=C.UTF-8 GSK_RENDERER=cairo NO_AT_BRIDGE=1
        T="Google Drive"
        DIR="/root/Google Drive"
        REMOTE="gdrive:AirCursor PC"
        MARK=/root/.config/cloudpc/gdrive-synced
        mkdir -p "${'$'}DIR" /root/.config/cloudpc
        if ! command -v rclone >/dev/null 2>&1; then
            zenity --question --width=420 --title="${'$'}T" --ok-label="Install" \
                --text="Install Google Drive support (about 25 MB)?" || exit 0
            ( ubuntu-extras install gdrive 2>&1 | sed -u 's/^/# /' ) |
                zenity --progress --pulsate --auto-close --no-cancel --width=480 --title="${'$'}T" --text="Installing…"
            command -v rclone >/dev/null 2>&1 || { zenity --error --text="Could not install. Check the internet."; exit 1; }
        fi
        if ! rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
            zenity --question --width=480 --title="${'$'}T" --ok-label="Sign in" \
                --text="Connect your Google Drive (15 GB free).\n\nGoogle's sign-in opens in your PHONE's browser. Sign in, tap Allow, then come back here." || exit 0
            PATH="/usr/local/lib/cloudpc/phone-browser:${'$'}PATH" BROWSER=cloudpc-open-on-phone \
                rclone config create gdrive drive scope=drive > /tmp/gdrive-login.log 2>&1 &
            PID=${'$'}!
            ( while kill -0 ${'$'}PID 2>/dev/null; do echo "# Waiting for the sign-in on your phone…"; sleep 2; done ) |
                zenity --progress --pulsate --auto-close --width=480 --title="${'$'}T" --text="Waiting for the sign-in on your phone…"
            kill ${'$'}PID 2>/dev/null
            rclone listremotes 2>/dev/null | grep -q '^gdrive:' || { zenity --error --text="Sign-in did not finish. Try again."; exit 1; }
            rclone mkdir "${'$'}REMOTE" >/dev/null 2>&1
        fi
        sync_now() {
            local extra=""
            [ -f "${'$'}MARK" ] || extra="--resync"
            ( rclone bisync "${'$'}DIR" "${'$'}REMOTE" ${'$'}extra --create-empty-src-dirs -v 2>&1 | sed -u 's/^/# /'
              echo "${'$'}{PIPESTATUS[0]}" > /tmp/gdrive-sync.rc ) |
                zenity --progress --pulsate --auto-close --width=560 --title="${'$'}T" --text="Syncing…"
            if [ "${'$'}(cat /tmp/gdrive-sync.rc 2>/dev/null)" = 0 ]; then
                touch "${'$'}MARK"
                notify-send -a "AirCursor PC" -i folder-remote "Google Drive" "Synced." >/dev/null 2>&1
            else
                rm -f "${'$'}MARK"
                zenity --error --width=460 --text="Sync did not finish (internet?). Try again – the next sync starts fresh."
            fi
        }
        while true; do
            C=${'$'}(zenity --list --width=560 --height=380 --title="${'$'}T" \
                --text="Home → Google Drive  ⇄  the \"AirCursor PC\" folder in your Drive" \
                --column=id --column="What do you want to do?" --hide-column=1 --print-column=1 \
                sync "Sync now (both ways)" \
                open "Open the Google Drive folder" \
                web "Open drive.google.com (all your files)" \
                out "Disconnect Google Drive") || exit 0
            case "${'$'}C" in
                sync) sync_now ;;
                open) setsid thunar "${'$'}DIR" >/dev/null 2>&1 & ;;
                web) setsid cloudpc-web https://drive.google.com >/dev/null 2>&1 & ;;
                out) zenity --question --text="Disconnect Google Drive? Files stay in both places." &&
                        { rclone config delete gdrive; rm -f "${'$'}MARK"; exit 0; } ;;
            esac
        done
    """.trimIndent() + "\n"

    /** Thunar: "Send to phone" in the right-click menu. */
    private const val UCA_SEND = "<action>\n\t<icon>phone</icon>\n\t<name>Send to phone</name>\n" +
        "\t<unique-id>aircursor-send-to-phone</unique-id>\n\t<command>cloudpc-send-to-phone %F</command>\n" +
        "\t<description>Save a copy on the phone</description>\n\t<patterns>*</patterns>\n\t<directories/>\n" +
        "\t<audio-files/>\n\t<image-files/>\n\t<other-files/>\n\t<text-files/>\n\t<video-files/>\n</action>\n"

    private const val UCA_VIDEO = "<action>\n\t<icon>video-display</icon>\n\t<name>Play smoothly on the phone</name>\n" +
        "\t<unique-id>aircursor-play-on-phone</unique-id>\n\t<command>cloudpc-phone-video %f</command>\n" +
        "\t<description>Play the video on the phone's video chip</description>\n\t<patterns>*</patterns>\n" +
        "\t<video-files/>\n</action>\n"

    /**
     * Video → the phone's own player. Without an argument: the browser window in front gives its
     * address (Ctrl+L, Ctrl+C), its video is paused (MPRIS) and the phone continues at the same second.
     */
    private val PHONE_VIDEO = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        say() { notify-send -a "AirCursor PC" -i video-display "Smooth video" "${'$'}1" >/dev/null 2>&1 & }
        send() { printf '%s' "aircursor-video:${'$'}1" | timeout 15 xclip -quiet -selection clipboard >/dev/null 2>&1 & }
        ARG="${'$'}1"
        if [ -n "${'$'}ARG" ] && [ -f "${'$'}ARG" ]; then
            send "0|file://${'$'}(readlink -f "${'$'}ARG")"
            exit 0
        fi
        case "${'$'}ARG" in
            http://*|https://*) send "0|${'$'}ARG"; exit 0 ;;
        esac
        WID=${'$'}(xdotool getactivewindow 2>/dev/null)
        NAME=${'$'}(xdotool getwindowname "${'$'}WID" 2>/dev/null)
        case "${'$'}NAME" in
            *Brave*|*Firefox*|*Chromium*|*Chrome*) ;;
            *) say "Open the video in Brave or Firefox first, then press Win+Y (or the ▶ button in the toolbar)."; exit 1 ;;
        esac
        # The page's address, from the address bar.
        xdotool key --clearmodifiers ctrl+l; sleep 0.25
        xdotool key --clearmodifiers ctrl+c; sleep 0.3
        xdotool key --clearmodifiers Escape
        URL=${'$'}(timeout 2 xclip -o -selection clipboard 2>/dev/null | head -n 1)
        case "${'$'}URL" in
            http://*|https://*) ;;
            *) say "Could not read the page address. Copy the video link and press Win+Y again."; exit 1 ;;
        esac
        # Pause it in the browser and remember where it was (media controls over the session bus).
        POS=0
        for P in ${'$'}(dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus                 org.freedesktop.DBus.ListNames 2>/dev/null | grep -o 'org\.mpris\.MediaPlayer2\.[A-Za-z0-9._-]*'); do
            ST=${'$'}(dbus-send --session --print-reply --dest="${'$'}P" /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get                 string:org.mpris.MediaPlayer2.Player string:PlaybackStatus 2>/dev/null | grep -o 'Playing')
            [ -n "${'$'}ST" ] || continue
            US=${'$'}(dbus-send --session --print-reply --dest="${'$'}P" /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties.Get                 string:org.mpris.MediaPlayer2.Player string:Position 2>/dev/null | grep -o 'int64 [0-9]*' | cut -d' ' -f2)
            [ -n "${'$'}US" ] && POS=${'$'}(( US / 1000000 ))
            dbus-send --session --type=method_call --dest="${'$'}P" /org/mpris/MediaPlayer2 org.mpris.MediaPlayer2.Player.Pause 2>/dev/null
        done
        send "${'$'}POS|${'$'}URL"
        exit 0
    """.trimIndent() + "\n"

    /** VS Code on a phone: no animations, no background update / telemetry work, fewer watched files. */
    private val VSCODE_SETTINGS = """
        {
            "workbench.reduceMotion": "on",
            "editor.smoothScrolling": false,
            "workbench.list.smoothScrolling": false,
            "terminal.integrated.smoothScrolling": false,
            "editor.cursorSmoothCaretAnimation": "off",
            "editor.minimap.enabled": false,
            "workbench.startupEditor": "none",
            "workbench.enableExperiments": false,
            "telemetry.telemetryLevel": "off",
            "update.mode": "none",
            "extensions.autoUpdate": false,
            "extensions.autoCheckUpdates": false,
            "git.autofetch": false,
            "search.followSymlinks": false,
            "typescript.tsserver.maxTsServerMemory": 1536,
            "files.watcherExclude": {
                "**/node_modules/**": true,
                "**/.git/objects/**": true,
                "**/.venv/**": true,
                "**/__pycache__/**": true,
                "**/dist/**": true,
                "**/build/**": true
            }
        }
    """.trimIndent() + "\n"

    /**
     * Focus boost: phones have 2–4 fast and 4–6 power-saving cores. The app in front may use every
     * core; apps in the background (other windows started from the menu) move to the power-saving
     * cores, so the fast ones are free for what you use. Only CPU placement changes (can be undone
     * any time), never priorities. Off: create ~/.config/cloudpc/no-focus-boost
     */
    private val FOCUS_BOOST = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        exec 9>/tmp/cloudpc-focus.lock
        flock -n 9 || exit 0
        command -v taskset >/dev/null && command -v xdotool >/dev/null || exit 0
        CORES=""
        for d in /sys/devices/system/cpu/cpu[0-9]*; do
            f=${'$'}(cat "${'$'}d/cpufreq/cpuinfo_max_freq" 2>/dev/null) || continue
            CORES="${'$'}CORES ${'$'}{d##*cpu}:${'$'}f"
        done
        MIN=${'$'}(for x in ${'$'}CORES; do echo "${'$'}{x#*:}"; done | sort -n | head -n 1)
        MAX=${'$'}(for x in ${'$'}CORES; do echo "${'$'}{x#*:}"; done | sort -n | tail -n 1)
        [ -n "${'$'}MIN" ] && [ "${'$'}MIN" != "${'$'}MAX" ] || exit 0     # all cores alike: nothing to gain
        LITTLE=${'$'}(for x in ${'$'}CORES; do [ "${'$'}{x#*:}" = "${'$'}MIN" ] && echo "${'$'}{x%%:*}"; done | paste -sd, -)
        EVERY=${'$'}(for x in ${'$'}CORES; do echo "${'$'}{x%%:*}"; done | paste -sd, -)
        declare -A PLACED
        LAST=""
        while sleep 2; do
            if [ -f /root/.config/cloudpc/no-focus-boost ]; then
                for p in "${'$'}{!PLACED[@]}"; do [ "${'$'}{PLACED[${'$'}p]}" = L ] && taskset -a -p -c "${'$'}EVERY" "${'$'}p" >/dev/null 2>&1; done
                PLACED=(); LAST=""; sleep 30; continue
            fi
            W=${'$'}(xdotool getactivewindow getwindowpid 2>/dev/null)
            [ -n "${'$'}W" ] && [ "${'$'}W" != "${'$'}LAST" ] || continue
            LAST=${'$'}W
            # Every app started from the menu runs under its own cloudpc-launch: one group per app.
            while read -r pid kind; do
                [ "${'$'}{PLACED[${'$'}pid]}" = "${'$'}kind" ] && continue
                if [ "${'$'}kind" = L ]; then cpus=${'$'}LITTLE; else cpus=${'$'}EVERY; fi
                taskset -a -p -c "${'$'}cpus" "${'$'}pid" >/dev/null 2>&1 && PLACED[${'$'}pid]=${'$'}kind
            done < <(ps -eo pid=,ppid=,args= | awk -v act="${'$'}W" '
                { pp[${'$'}1] = ${'$'}2; a = ${'$'}0; sub(/^ *[0-9]+ +[0-9]+ +/, "", a); launch[${'$'}1] = (a ~ /cloudpc\/launch\.sh/) }
                END {
                    for (p in pp) { r = ""; q = p; n = 0
                        while ((q in pp) && n < 64) { if (launch[q]) { r = q; break }; q = pp[q]; n++ }
                        root[p] = r }
                    mine = root[act]
                    for (p in pp) if (root[p] != "" && !launch[p]) print p, (root[p] == mine ? "B" : "L")
                }')
            # Forget programs that have ended.
            for p in "${'$'}{!PLACED[@]}"; do [ -d "/proc/${'$'}p" ] || unset "PLACED[${'$'}p]"; done
        done
    """.trimIndent() + "\n"

    /** Air: links from the PC open in AirCursor's browser on the phone's engine (over the PC). */
    private val AIR = """
        #!/bin/sh
        . /tmp/cloudpc-session.env 2>/dev/null
        URL="${'$'}1"
        case "${'$'}URL" in
            "") URL="air:home" ;;
            http://*|https://*) ;;
            *)  # Local files and other kinds of links: the PC's own browser.
                if [ -x /usr/bin/brave-browser-stable ]; then
                    exec cloudpc-launch brave-browser.desktop /usr/bin/brave-browser-stable "${'$'}URL"
                fi
                command -v firefox >/dev/null && exec firefox "${'$'}URL"
                exit 1 ;;
        esac
        printf '%s' "aircursor-web:${'$'}URL" | timeout 15 xclip -quiet -selection clipboard >/dev/null 2>&1 &
        exit 0
    """.trimIndent() + "\n"

    /** Air Store: the store window is a page in Air; this starts its small server in the PC first. */
    private val AIRSTORE = """
        #!/bin/sh
        . /tmp/cloudpc-session.env 2>/dev/null
        LIB=/$LIB
        command -v cloudpc-air >/dev/null 2>&1 || exec aircursor-store "${'$'}@"
        URL=${'$'}(head -n 1 /tmp/airstore.url 2>/dev/null)
        # Still running from before: open it again (fast). Else start it.
        if [ -z "${'$'}URL" ] || ! pgrep -f "python3 ${'$'}LIB/airstore.py${'$'}" >/dev/null 2>&1; then
            pkill -f "python3 ${'$'}LIB/airstore.py${'$'}" 2>/dev/null
            rm -f /tmp/airstore.url
            python3 "${'$'}LIB/airstore.py" > /tmp/airstore.url 2> /tmp/airstore.log &
            URL=""
            for i in ${'$'}(seq 1 100); do
                URL=${'$'}(head -n 1 /tmp/airstore.url 2>/dev/null)
                [ -n "${'$'}URL" ] && break
                sleep 0.1
            done
        fi
        [ -n "${'$'}URL" ] || exec aircursor-store "${'$'}@"
        # Its own small app window in Air (no tabs or address bar), like an app.
        command -v cloudpc-app >/dev/null 2>&1 && exec cloudpc-app airstore win "Air Store" "${'$'}URL"
        exec cloudpc-air "${'$'}URL"
    """.trimIndent() + "\n"

    private val AIRSTORE_ICON = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
          <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#3fa9ff"/><stop offset="1" stop-color="#1a62f0"/></linearGradient></defs>
          <rect x="4" y="4" width="56" height="56" rx="16" fill="url(#g)"/>
          <path d="M20 46 L32 22 L44 46 M25 37 H39" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
          <circle cx="32" cy="16" r="3" fill="#fff"/>
        </svg>
    """.trimIndent() + "\n"

    /** Windows-style keys and window snapping (set once; change them in Settings → Keyboard). */
    private val WINDOWS_KEYS = """
        #!/bin/sh
        STAMP=/var/lib/cloudpc/windows-keys-1
        . /tmp/cloudpc-session.env 2>/dev/null
        k() { xfconf-query -c xfce4-keyboard-shortcuts -p "${'$'}1" --create -t string -s "${'$'}2"; }
        mkdir -p /var/lib/cloudpc
        # Keys added later: each set once (the person can change them in Settings → Keyboard).
        if [ ! -f /var/lib/cloudpc/windows-keys-2 ]; then
            k "/commands/custom/<Super>y" "cloudpc-phone-video"
            touch /var/lib/cloudpc/windows-keys-2
        fi
        [ -f "${'$'}STAMP" ] && exit 0
        k "/commands/custom/<Super>e" "thunar"
        k "/commands/custom/<Super>r" "xfce4-appfinder --collapsed"
        k "/commands/custom/<Super>i" "xfce4-settings-manager"
        k "/commands/custom/<Primary><Shift>Escape" "xfce4-taskmanager"
        k "/commands/custom/Print" "xfce4-screenshooter -f"
        k "/commands/custom/<Super><Shift>s" "xfce4-screenshooter -r"
        k "/xfwm4/custom/<Super>Left" "tile_left_key"
        k "/xfwm4/custom/<Super>Right" "tile_right_key"
        k "/xfwm4/custom/<Super>Up" "maximize_window_key"
        k "/xfwm4/custom/<Super>Down" "hide_window_key"
        k "/xfwm4/custom/<Super>d" "show_desktop_key"
        xfconf-query -c xfwm4 -p /general/tile_on_move --create -t bool -s true
        xfconf-query -c xfwm4 -p /general/snap_to_border --create -t bool -s true
        mkdir -p /var/lib/cloudpc && touch "${'$'}STAMP"
        exit 0
    """.trimIndent() + "\n"

    /** Health report for the person and for Claude: what makes the PC slow or broken. */
    private val PC_DOCTOR = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        echo "== AirCursor PC health =="
        echo "CPU cores: ${'$'}(nproc)"
        free -h | head -n 2
        df -h / | awk 'NR==2 {print "Storage: " ${'$'}4 " free of " ${'$'}2}'
        N=${'$'}(ls -d /proc/[0-9]* 2>/dev/null | wc -l)
        echo "Programs running: ${'$'}N   (Android's 32-program limit: ${'$'}(cat /etc/cloudpc/child-limit 2>/dev/null || echo unknown))"
        echo "Desktop size: ${'$'}(xrandr 2>/dev/null | awk '/current/ {print ${'$'}8 "x" ${'$'}10}' | tr -d ,)"
        echo "Game graphics: ${'$'}(cat /etc/cloudpc/gpu-mode 2>/dev/null || echo cpu)   ${'$'}(grep -m1 '^fps_limit' /etc/cloudpc/MangoHud.conf 2>/dev/null)"
        echo "Default browser: ${'$'}(grep -m1 '^WebBrowser=' /root/.config/xfce4/helpers.rc 2>/dev/null | cut -d= -f2 || echo firefox)"
        echo
        echo "== Programs using the most CPU =="
        ps -eo pid,pcpu,rss,comm --sort=-pcpu | head -n 11
        echo
        echo "== Programs using the most memory (RSS in KB) =="
        ps -eo pid,rss,comm --sort=-rss | head -n 9
        echo
        echo "== Problems =="
        dpkg --audit 2>/dev/null | head -n 5
        [ -s /tmp/cloudpc-app-errors.log ] && { echo "Recent app errors:"; tail -n 8 /tmp/cloudpc-app-errors.log; }
        getent hosts deb.debian.org >/dev/null 2>&1 || getent hosts ports.ubuntu.com >/dev/null 2>&1 || echo "No internet (DNS) right now."
        echo "(done)"
    """.trimIndent() + "\n"

    /** Opens an app by name, a file or a web link on the desktop (for the person and for Claude). */
    private val PC_OPEN = """
        #!/bin/bash
        . /tmp/cloudpc-session.env 2>/dev/null
        [ ${'$'}# -gt 0 ] || { echo "Usage: pc-open <app name | file | link>"; exit 1; }
        T="${'$'}*"
        if [ -e "${'$'}T" ] || [[ "${'$'}T" == *://* ]]; then
            setsid xdg-open "${'$'}T" >/dev/null 2>&1 &
            echo "Opened: ${'$'}T"; exit 0
        fi
        want=${'$'}(echo "${'$'}T" | tr '[:upper:]' '[:lower:]')
        exact=""; part=""
        for f in /usr/local/share/applications/*.desktop /usr/share/applications/*.desktop; do
            [ -f "${'$'}f" ] || continue
            grep -qi '^NoDisplay=true' "${'$'}f" && continue
            id=${'$'}(basename "${'$'}f" .desktop | tr '[:upper:]' '[:lower:]')
            name=${'$'}(grep -m1 '^Name=' "${'$'}f" | cut -d= -f2- | tr '[:upper:]' '[:lower:]')
            if [ "${'$'}id" = "${'$'}want" ] || [ "${'$'}name" = "${'$'}want" ]; then exact="${'$'}f"; break; fi
            [ -z "${'$'}part" ] && case "${'$'}name ${'$'}id" in *"${'$'}want"*) part="${'$'}f" ;; esac
        done
        F="${'$'}{exact:-${'$'}part}"
        if [ -n "${'$'}F" ]; then
            if command -v gio >/dev/null 2>&1; then setsid gio launch "${'$'}F" >/dev/null 2>&1 &
            else setsid exo-open "${'$'}F" >/dev/null 2>&1 &
            fi
            echo "Opening ${'$'}(grep -m1 '^Name=' "${'$'}F" | cut -d= -f2-)"; exit 0
        fi
        if command -v "${'$'}1" >/dev/null 2>&1; then
            setsid "${'$'}@" >/dev/null 2>&1 &
            echo "Started ${'$'}1"; exit 0
        fi
        echo "No app called \"${'$'}T\" found."; exit 1
    """.trimIndent() + "\n"

    /** Saves a picture of the desktop (Claude can look at it). */
    private val PC_SCREENSHOT = """
        #!/bin/sh
        . /tmp/cloudpc-session.env 2>/dev/null
        OUT="${'$'}{1:-/tmp/pc-screen.png}"
        python3 - "${'$'}OUT" <<'PY'
        import sys, gi
        gi.require_version("Gdk", "3.0")
        from gi.repository import Gdk
        w = Gdk.get_default_root_window()
        pb = Gdk.pixbuf_get_from_window(w, 0, 0, w.get_width(), w.get_height())
        pb.savev(sys.argv[1], "png", [], [])
        print(sys.argv[1])
        PY
    """.trimIndent() + "\n"

    /** What Claude Code knows about this PC (its memory file). */
    private val CLAUDE_MD = """
        <!-- AirCursor PC: written by the AirCursor app. Put your own notes in /root/CLAUDE.md -->
        # AirCursor PC – notes for Claude

        You run inside AirCursor PC: Ubuntu 24.04 (ARM64) inside the AirCursor Android app (proot: you are
        root inside, but there is no systemd, no kernel modules and no real hardware access). The person
        uses it on a phone (touch, hand gestures, Bluetooth keyboard and mouse). Answer in simple Hinglish
        unless they write in another language.

        ## Tools you can use
        - `pc-doctor` – health report: memory, storage, programs vs Android's limit, screen size, FPS
          limit, game graphics, top CPU/memory programs, recent app errors.
        - `pc-screenshot` – saves the desktop to /tmp/pc-screen.png; read that image to see the screen.
        - `pc-open <app name | file | link>` – opens it on the desktop (e.g. `pc-open firefox`,
          `pc-open "LibreOffice Writer"`, `pc-open ~/site/index.html`).
        - `xdotool` – click and type in apps (DISPLAY=:1), e.g. `xdotool mousemove 400 300 click 1`,
          `xdotool type "hello"`, `xdotool key ctrl+s`. Take a screenshot first to find positions.
        - Install software with `apt-get install -y <package>` (no sudo needed). Air Store apps:
          `ubuntu-extras install <id>` (vscode, coding, python, games, luanti, brave, …).
        - Projects: Python 3, git, gcc; Node.js after `ubuntu-extras install coding`. GUI programs you
          build show up on the desktop when started (use `pc-open` or start them in the background).

        ## Speed and FPS
        - Everything is drawn by the CPU (llvmpipe, all cores). Fewer pixels = more FPS:
          `cloudpc-resize 960 432` (or 800 360 for games, 1280 576 for sharp text). The app's AirBoost
          setting sets its own size again at the next start.
        - `cloudpc-light 0` stops background helpers and animations.
        - Games: FPS limit in /etc/cloudpc/MangoHud.conf (`fps_limit=`, 30/45 is steadiest); phone GPU
          when /etc/cloudpc/gpu-mode says `virgl` (games started from the menu use it).
        - Android allows about 32 programs for the whole PC unless the person lifted the limit
          (/etc/cloudpc/child-limit). Over it, Android kills programs (exit code 137). Close programs
          that are not needed before heavy apps. Fewer browser tabs help a lot.
        - Brave is the default browser; it is lighter on heavy sites than Firefox.

        ## Rules
        - Ask before deleting the person's files or removing apps. The phone's own files are not reachable.
        - Never kill the desktop itself: Xtigervnc/Xvnc, xfce4-session, xfwm4, xfce4-panel, xfdesktop,
          xfsettingsd, dbus-daemon.
        - No systemd: start servers in the background yourself (e.g. `nohup ollama serve >/tmp/ollama.log 2>&1 &`).
    """.trimIndent() + "\n"

    /** Opens a website like an app: own window in the default browser (Brave: app window, no tabs). */
    private val WEB = """
        #!/bin/sh
        # cloudpc-web URL [Title] – a web app (shortcuts, Start menu): its own app window in Air.
        URL="${'$'}1"
        TITLE="${'$'}2"
        [ -n "${'$'}URL" ] || exit 1
        # Air: the phone's own browser engine shows the site over the PC (GPU, hardware video, fast
        # JavaScript, Google sign-in) – YouTube and AI chats are far too heavy for the PC's CPU-drawn
        # browser. Off (use Brave instead): toolbar → lightning → Web links.
        if [ -z "${'$'}CLOUDPC_PC_BROWSER" ] && [ ! -f /root/.config/cloudpc/web-in-pc-browser ]; then
            case "${'$'}URL" in
                http://*|https://*)
                    HOST=${'$'}(printf '%s' "${'$'}URL" | sed -E 's#^[a-zA-Z]+://([^/:?]+).*#\1#' | tr 'A-Z' 'a-z')
                    case "${'$'}HOST" in
                        music.youtube.com) K=ytmusic; T="YouTube Music" ;;
                        *youtube.com|youtu.be) K=youtube; T=YouTube ;;
                        *spotify.com) K=spotify; T=Spotify ;;
                        *jiosaavn.com) K=jiosaavn; T=JioSaavn ;;
                        *gaana.com) K=gaana; T=Gaana ;;
                        *netflix.com) K=netflix; T=Netflix ;;
                        *primevideo.com) K=primevideo; T="Prime Video" ;;
                        *hotstar.com) K=hotstar; T=JioHotstar ;;
                        *twitch.tv) K=twitch; T=Twitch ;;
                        *whatsapp.com) K=whatsapp; T=WhatsApp ;;
                        chatgpt.com|*.chatgpt.com) K=chatgpt; T=ChatGPT ;;
                        claude.ai) K=claude; T=Claude ;;
                        gemini.google.com) K=gemini; T=Gemini ;;
                        github.com) K=github; T=GitHub ;;
                        mail.google.com) K=gmail; T=Gmail ;;
                        drive.google.com) K=gdrive; T="Google Drive" ;;
                        *)
                            K=${'$'}(printf '%s' "${'$'}HOST" | sed -E 's/^(www|m|app|open|web)\.//; s/[^a-z0-9.-]//g')
                            T=${'$'}(printf '%s' "${'$'}K" | cut -d. -f1 | awk '{print toupper(substr(${'$'}0,1,1)) substr(${'$'}0,2)}') ;;
                    esac
                    [ -n "${'$'}TITLE" ] && T="${'$'}TITLE"
                    command -v cloudpc-app >/dev/null 2>&1 && exec cloudpc-app "${'$'}K" max "${'$'}T" "${'$'}URL"
                    exec cloudpc-air "${'$'}URL" ;;
            esac
        fi
        # Brave unless the person chose Firefox as the default browser.
        if [ -x /usr/bin/brave-browser-stable ] && ! { grep -qs '^WebBrowser=firefox' /root/.config/xfce4/helpers.rc && command -v firefox >/dev/null; }; then
            exec cloudpc-launch brave-browser.desktop /usr/bin/brave-browser-stable --app="${'$'}URL"
        fi
        command -v firefox >/dev/null && exec firefox --new-window "${'$'}URL"
        exec xdg-open "${'$'}URL"
    """.trimIndent() + "\n"

    /** cloudpc-app KEY max|win TITLE URL – an app window in Air (no tabs, no address bar). */
    private val APP = """
        #!/bin/sh
        . /tmp/cloudpc-session.env 2>/dev/null
        KEY=${'$'}(printf '%s' "${'$'}1" | tr -cd 'a-zA-Z0-9._-' | cut -c1-40)
        SIZE="${'$'}2"
        TITLE=${'$'}(printf '%s' "${'$'}3" | tr -d '|' | cut -c1-60)
        URL="${'$'}4"
        case "${'$'}SIZE" in max|win) ;; *) SIZE=max ;; esac
        case "${'$'}URL" in http://*|https://*) ;; *) echo "usage: cloudpc-app KEY max|win TITLE URL"; exit 1 ;; esac
        if [ -n "${'$'}CLOUDPC_PC_BROWSER" ] || [ -f /root/.config/cloudpc/web-in-pc-browser ]; then
            CLOUDPC_PC_BROWSER=1 exec cloudpc-web "${'$'}URL"
        fi
        printf '%s' "aircursor-app:${'$'}{KEY:-app}|${'$'}SIZE|${'$'}{TITLE:-App}|${'$'}URL" | timeout 15 xclip -quiet -selection clipboard >/dev/null 2>&1 &
        exit 0
    """.trimIndent() + "\n"

    /** Claude Code in a terminal, with sign-in in the phone's browser. */
    private val CLAUDE = """
        #!/bin/bash
        export PATH="/$LIB/phone-browser:${'$'}PATH" BROWSER=cloudpc-open-on-phone
        mkdir -p /root/Projects
        cd /root/Projects || cd /root || exit 1
        if ! command -v claude >/dev/null 2>&1; then
            echo "Claude Code is not installed yet: Air Store → AI & Coding → Claude Code → Install."
            exec bash
        fi
        # Claude may use the PC's own tools without asking each time (they only look or open things).
        python3 - <<'PY' 2>/dev/null
        import json, os
        p = "/root/.claude/settings.json"
        os.makedirs(os.path.dirname(p), exist_ok=True)
        try:
            d = json.load(open(p))
        except Exception:
            d = {}
        allow = d.setdefault("permissions", {}).setdefault("allow", [])
        for rule in ["Bash(pc-doctor)", "Bash(pc-doctor:*)", "Bash(pc-screenshot)", "Bash(pc-screenshot:*)",
                     "Bash(pc-open:*)", "Bash(xdotool:*)", "Bash(xrandr:*)", "Bash(free:*)", "Bash(df:*)",
                     "Bash(ps:*)", "Bash(nproc)", "Bash(cloudpc-light:*)", "Bash(cloudpc-resize:*)",
                     "Read(//tmp/**)"]:
            if rule not in allow:
                allow.append(rule)
        json.dump(d, open(p, "w"), indent=2)
        PY
        clear
        echo "Claude Code"
        echo "Sign-in opens in your PHONE's browser (fast, Google sign-in works)."
        echo "After signing in, come back to AirCursor PC. If Claude Code asks for a code:"
        echo "copy it on the phone, then AirCursor toolbar → Paste, then press Enter."
        echo ""
        if [ "${'$'}1" = "--assistant" ]; then
            shift
            echo "PC Assistant: Claude checks this PC and fixes speed, FPS and app problems."
            echo ""
            set -- "Namaste! Run pc-doctor and look at the screen with pc-screenshot. Tell me in simple Hinglish what makes this PC slow or broken, then fix it step by step (ask before deleting anything)." "${'$'}@"
        fi
        claude "${'$'}@"
        exec bash
    """.trimIndent() + "\n"

    /** Changes the desktop size at once (live AirBoost), no restart needed. Arguments: width height. */
    private val RESIZE = """
        #!/bin/sh
        W="${'$'}1"; H="${'$'}2"
        [ -n "${'$'}W" ] && [ -n "${'$'}H" ] || exit 1
        . /tmp/cloudpc-session.env 2>/dev/null
        M="${'$'}{W}x${'$'}{H}"
        xfconf-query -c displays -p /Notify --create -t int -s 0 >/dev/null 2>&1
        OUT=${'$'}(xrandr 2>/dev/null | awk '/ (dis)?connected/ {print ${'$'}1; exit}')
        OUT=${'$'}{OUT:-VNC-0}
        xrandr --newmode "${'$'}M" 60 ${'$'}W ${'$'}W ${'$'}W ${'$'}W ${'$'}H ${'$'}H ${'$'}H ${'$'}H >/dev/null 2>&1
        xrandr --addmode "${'$'}OUT" "${'$'}M" >/dev/null 2>&1
        xrandr --output "${'$'}OUT" --mode "${'$'}M"
        sleep 1
        pkill -f '^xfce4-display-settings' 2>/dev/null
        exit 0
    """.trimIndent() + "\n"

    /** Keeps Firefox (its security fixes) up to date – quietly, at most every 3 days. */
    private val SECURITY_UPDATE = """
        #!/bin/sh
        STAMP=/var/lib/cloudpc/security-update
        mkdir -p /var/lib/cloudpc
        if [ -f "${'$'}STAMP" ] && [ ${'$'}(( ${'$'}(date +%s) - ${'$'}(stat -c %Y "${'$'}STAMP") )) -lt 259200 ]; then exit 0; fi
        dpkg -s firefox >/dev/null 2>&1 || exit 0
        apt-get update -qq >/dev/null 2>&1 || exit 0
        # Never swap Firefox under a running browser; try again next start.
        pgrep -x firefox >/dev/null 2>&1 && exit 0
        apt-get install -y -qq --only-upgrade firefox >/dev/null 2>&1 && touch "${'$'}STAMP"
        exit 0
    """.trimIndent() + "\n"

    /** Graphics speed test (glxgears): CPU, and the phone GPU (VirGL) when it is switched on. */
    private val BENCHMARK = """
        #!/bin/bash
        export LANG=C.UTF-8 GSK_RENDERER=cairo NO_AT_BRIDGE=1 vblank_mode=0
        export LP_NUM_THREADS="${'$'}{LP_NUM_THREADS:-${'$'}(nproc)}"
        if ! command -v glxgears >/dev/null 2>&1; then
            ( apt-get install -y --no-install-recommends mesa-utils 2>&1 | sed -u 's/^/# /' ) \
                | zenity --progress --pulsate --auto-close --no-cancel --width=480 --title="Benchmark" --text="Preparing the test…"
        fi
        command -v glxgears >/dev/null 2>&1 || { zenity --error --text="The test could not be installed. Check the internet and try again."; exit 1; }
        RES=${'$'}(xrandr 2>/dev/null | awk '/current/ {print ${'$'}8"x"${'$'}10}' | tr -d ,)
        measure() {   # ${'$'}1 = title, rest = environment for the test
            local title="${'$'}1"; shift
            env "${'$'}@" glxgears -geometry 640x360 > /tmp/glxgears.out 2>&1 &
            local pid=${'$'}!
            for i in ${'$'}(seq 1 12); do echo ${'$'}((i * 100 / 12)); echo "# ${'$'}title… ${'$'}((12 - i)) s"; sleep 1; done \
                | zenity --progress --auto-close --no-cancel --width=420 --title="Benchmark" --text="${'$'}title…"
            kill ${'$'}pid 2>/dev/null
            wait ${'$'}pid 2>/dev/null
            grep -o '[0-9.]* FPS' /tmp/glxgears.out | tail -n 1 | cut -d' ' -f1 | cut -d. -f1
        }
        CPU=${'$'}(measure "Testing the CPU (llvmpipe)" GALLIUM_DRIVER=llvmpipe)
        TEXT="CPU graphics: ${'$'}{CPU:-?} FPS  (${'$'}LP_NUM_THREADS cores)"
        if [ "${'$'}(cat /etc/cloudpc/gpu-mode 2>/dev/null)" = virgl ] && [ -S /tmp/.virgl_test ]; then
            GPU=${'$'}(measure "Testing the phone GPU (VirGL)" GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0)
            TEXT="${'$'}TEXT\nPhone GPU (VirGL): ${'$'}{GPU:-did not run} FPS\n\nGames use the phone GPU now. If a game is slower or looks wrong, switch Graphics back to CPU in AirBoost."
        else
            TEXT="${'$'}TEXT\nPhone GPU (VirGL): off\n\nTo compare: toolbar → AirBoost → Graphics → Phone GPU, then run this test again."
        fi
        zenity --info --width=460 --title="Benchmark" \
            --text="${'$'}TEXT\n\nDesktop size: ${'$'}{RES:-?}\nFor more FPS: AirBoost → Turbo or Game, Steady FPS 30/45."
    """.trimIndent() + "\n"

    /** Firefox defaults: no warning about the Linux sandbox that cannot exist inside the Android app. */
    private val FIREFOX_PREFS = """
        // AirCursor PC: Firefox runs inside Android's app sandbox, which keeps the whole PC away from the
        // phone's other apps and data. Firefox's own Linux sandbox cannot run there, so do not warn about it.
        pref("security.sandbox.warn_unprivileged_namespaces", false);
        pref("dom.security.https_only_mode", true);
        // Fewer Firefox programs (Android allows an app only ~32): separate processes only for sites you
        // are logged in to, like Firefox for Android. (Drawing, network and add-ons keep Firefox's normal
        // setup: moving them into the main process made big sites such as AI chats hang.)
        pref("fission.webContentIsolationStrategy", 2);
        pref("dom.ipc.processCount", 2);
        pref("dom.ipc.processCount.webIsolated", 1);
        pref("dom.ipc.processPrelaunch.enabled", false);
        // Smooth videos on a phone CPU: YouTube sends H.264, which decodes several times faster in
        // software than VP9 or AV1. Multi-core software drawing, fewer session saves.
        pref("media.av1.enabled", false);
        pref("media.mediasource.vp9.enabled", false);
        // No "Speech Dispatcher won't open" bar (there is no speech engine in the PC).
        pref("media.webspeech.synth.enabled", false);
        pref("gfx.webrender.software", true);
        pref("browser.sessionstore.interval", 60000);
    """.trimIndent() + "\n"

    /**
     * Brave: "enhanced-h264ify" makes YouTube send H.264 instead of VP9/AV1 (much lighter to decode
     * without a video chip), memory saver for background tabs, and Brave's extra background services
     * (Rewards, Wallet, VPN, Tor, News, Leo) off – each of them is one more program and CPU work.
     */
    private val BRAVE_POLICIES = """
        {
          "ExtensionInstallForcelist": ["omkfmpieigblcllmkgbflkikinpkodlk;https://clients2.google.com/service/update2/crx"],
          "HighEfficiencyModeEnabled": true,
          "BackgroundModeEnabled": false,
          "MetricsReportingEnabled": false,
          "BraveRewardsDisabled": true,
          "BraveWalletDisabled": true,
          "BraveVPNDisabled": true,
          "BraveNewsDisabled": true,
          "BraveTalkDisabled": true,
          "BraveAIChatEnabled": false,
          "TorDisabled": true
        }
    """.trimIndent() + "\n"

    private val FIREFOX_POLICIES = """
        {
          "policies": {
            "DisableAppUpdate": true,
            "DontCheckDefaultBrowser": true,
            "OverrideFirstRunPage": "",
            "HttpsOnlyMode": "enabled",
            "EnableTrackingProtection": { "Value": true },
            "Preferences": {
              "dom.ipc.processCount": { "Value": 2, "Status": "default" },
              "browser.tabs.unloadOnLowMemory": { "Value": true, "Status": "default" }
            }
          }
        }
    """.trimIndent() + "\n"
}
