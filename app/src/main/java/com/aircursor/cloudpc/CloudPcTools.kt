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
    const val VERSION = 15
    private const val LIB = "usr/local/lib/cloudpc"
    private const val MARK = "$LIB/.tools-version"
    private val SCRIPTS = listOf(
        "app-store.sh", "app-store.py", "ubuntu-extras.sh", "install-deb.sh", "windows-layout.sh", "wallpaper-gui.sh",
        "launch.sh", "apps-setup.sh",
    )

    fun needsSetup(c: Context): Boolean =
        File(CloudPcEngine.rootfs(c), MARK).let { !it.exists() || it.readText().trim() != VERSION.toString() }

    /** Packages the tools need (App Store window, dialogs). Failures (no internet) are not fatal. */
    private const val TOOL_PACKAGES = "zenity wget xdg-utils libnotify-bin python3-gi gir1.2-gtk-3.0 xclip " +
        "xdotool xfce4-screenshooter xarchiver thunar-archive-plugin unzip zip p7zip-full"
    const val PACKAGES_SCRIPT =
        "apt-get install -y --no-install-recommends $TOOL_PACKAGES >/dev/null 2>&1 || " +
            "(apt-get update >/dev/null 2>&1 && apt-get install -y --no-install-recommends $TOOL_PACKAGES) " +
            "|| echo 'Tools packages: no internet, will retry later'"

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
        put("usr/share/applications/aircursor-store.desktop", entry("App Store", "aircursor-store", "system-software-install", "System;", "Apps, games and AI – install with one tap"))
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
            "[Desktop Entry]\nType=Application\nName=Cloud PC Windows-style layout\nExec=aircursor-windows-layout\n" +
                "OnlyShowIn=XFCE;\nNoDisplay=true\n",
        )
        // Firefox: fewer background processes (less RAM on a phone), no first-run pages.
        put("etc/firefox/policies/policies.json", FIREFOX_POLICIES)
        put("usr/lib/firefox/defaults/pref/aircursor-pc.js", FIREFOX_PREFS)
        put(
            "usr/share/applications/cloudpc-benchmark.desktop",
            entry("Benchmark", "cloudpc-benchmark", "utilities-system-monitor", "System;", "Test graphics speed (FPS) with your AirBoost profile"),
        )

        // Desktop icons (only added once, so the user can delete them).
        val desk = "root/Desktop"
        val icons = listOf(
            "Getting-Started.desktop" to "usr/share/applications/aircursor-guide.desktop",
            "Benchmark.desktop" to "usr/share/applications/cloudpc-benchmark.desktop",
            "App-Store.desktop" to "usr/share/applications/aircursor-store.desktop",
            "Firefox.desktop" to "usr/share/applications/firefox.desktop",
            "Terminal.desktop" to "usr/share/applications/xfce4-terminal.desktop",
            "Change-Wallpaper.desktop" to "usr/share/applications/aircursor-wallpaper.desktop",
        )
        val r = CloudPcEngine.rootfs(c)
        for ((name, src) in icons) {
            val target = File(r, "$desk/$name")
            val source = File(r, src)
            // The guide icon is ours: always refresh it. Other icons are added once (the user may delete them).
            val refresh = name == "Getting-Started.desktop" && target.exists()
            if ((refresh || !target.exists()) && source.isFile) put("$desk/$name", source.readText(), exec = true)
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

    /** Windows-style keys and window snapping (set once; change them in Settings → Keyboard). */
    private val WINDOWS_KEYS = """
        #!/bin/sh
        STAMP=/var/lib/cloudpc/windows-keys-1
        [ -f "${'$'}STAMP" ] && exit 0
        . /tmp/cloudpc-session.env 2>/dev/null
        k() { xfconf-query -c xfce4-keyboard-shortcuts -p "${'$'}1" --create -t string -s "${'$'}2"; }
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
        - Install software with `apt-get install -y <package>` (no sudo needed). App Store apps:
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
        URL="${'$'}1"
        [ -n "${'$'}URL" ] || exit 1
        if grep -qs '^WebBrowser=brave-browser' /root/.config/xfce4/helpers.rc && [ -x /usr/bin/brave-browser-stable ]; then
            exec cloudpc-launch brave-browser.desktop /usr/bin/brave-browser-stable --app="${'$'}URL"
        fi
        exec firefox --new-window "${'$'}URL"
    """.trimIndent() + "\n"

    /** Claude Code in a terminal, with sign-in in the phone's browser. */
    private val CLAUDE = """
        #!/bin/bash
        export PATH="/$LIB/phone-browser:${'$'}PATH" BROWSER=cloudpc-open-on-phone
        cd /root || exit 1
        if ! command -v claude >/dev/null 2>&1; then
            echo "Claude Code is not installed yet: App Store → AI & Coding → Claude Code → Install."
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
        // Speed on a phone CPU: YouTube uses VP9 instead of the much heavier AV1, multi-core software
        // drawing, fewer page animations, fewer session saves.
        pref("media.av1.enabled", false);
        // No "Speech Dispatcher won't open" bar (there is no speech engine in the PC).
        pref("media.webspeech.synth.enabled", false);
        pref("gfx.webrender.software", true);
        pref("browser.sessionstore.interval", 60000);
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
