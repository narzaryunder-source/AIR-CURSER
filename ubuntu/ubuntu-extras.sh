#!/bin/bash
# Extra apps for Ubuntu on Android (runs inside Ubuntu as root: sudo ubuntu-extras)
set -u
export DEBIAN_FRONTEND=noninteractive

if [ "$(id -u)" != "0" ]; then
    exec sudo "$0" "$@"
fi
REAL_USER="${SUDO_USER:-user}"
# Home folder of the desktop user ("/root" on the AirCursor built-in Cloud PC, "/home/user" in Termux).
REAL_HOME=$(getent passwd "$REAL_USER" 2>/dev/null | cut -d: -f6)
REAL_HOME=${REAL_HOME:-/home/$REAL_USER}
# An install that was interrupted (e.g. Android killed Termux) is finished first.
dpkg --configure -a >/dev/null 2>&1
apt-get -f install -y >/dev/null 2>&1

ok() { printf '\033[1;32m%s\033[0m\n' "$*"; }
bad() { printf '\033[1;31m%s\033[0m\n' "$*"; }

install_vscode() {
    # code-server = VS Code in the browser (the CodeWithHarry guide's way). One download (about 240 MB).
    echo "Installing VS Code (code-server)..."
    local arch tag url tgz=/tmp/code-server.tar.gz
    case "$(uname -m)" in
        aarch64|arm64) arch=arm64 ;;
        x86_64) arch=amd64 ;;
        *) bad "VS Code needs a 64-bit phone"; return ;;
    esac
    tag=$(wget -qO- https://api.github.com/repos/coder/code-server/releases/latest 2>/dev/null |
        grep -m1 '"tag_name"' | sed 's/[^0-9.]*\([0-9][0-9.]*\).*/\1/')
    [ -n "$tag" ] || tag=4.16.1   # the version of the guide, if GitHub's answer does not come
    url="https://github.com/coder/code-server/releases/download/v$tag/code-server-$tag-linux-$arch.tar.gz"
    rm -rf /opt/code-server.new && mkdir -p /opt/code-server.new
    if wget -O "$tgz" "$url" && tar -xzf "$tgz" -C /opt/code-server.new --strip-components=1; then
        rm -rf /opt/code-server
        mv /opt/code-server.new /opt/code-server
        ln -sf /opt/code-server/bin/code-server /usr/local/bin/code-server
        ok "VS Code installed (code-server $tag)"
    else
        rm -rf /opt/code-server.new
        bad "VS Code install failed – check the internet and try again"
    fi
    rm -f "$tgz"
}

install_node() {   # $1 = lowest Node.js version the app needs (default 18)
    local need="${1:-18}"
    if command -v node >/dev/null 2>&1 && [ "$(node -v | cut -c2- | cut -d. -f1)" -ge "$need" ]; then return 0; fi
    echo "Installing Node.js 22..."
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs
}

install_npm_tool() {   # $1 = name, $2 = npm package, $3 = command, $4 = lowest Node.js version
    echo "Installing $1..."
    if install_node "${4:-18}" && npm install -g "$2" && command -v "$3" >/dev/null 2>&1; then
        ok "$1 installed. Open it from AI Apps, or run:  $3"
    else
        bad "$1 install failed – check the internet and try again"
    fi
}

install_claude_desktop() {
    # Claude Desktop (Anthropic's official Linux app, beta): Anthropic's own package repository, so it
    # updates with the system. Without the "recommended" extras: they are for Cowork, which needs a
    # virtual machine (KVM) that phones do not give apps. gnome-keyring keeps the sign-in saved.
    echo "Installing Claude Desktop (about 180 MB download)..."
    mkdir -p /usr/share/keyrings
    if curl -fsSLo /usr/share/keyrings/claude-desktop-archive-keyring.asc https://downloads.claude.ai/claude-desktop/key.asc; then
        echo "deb [arch=amd64,arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] https://downloads.claude.ai/claude-desktop/apt/stable stable main" \
            > /etc/apt/sources.list.d/claude-desktop.list
        if apt-get update && apt-get install -y --no-install-recommends claude-desktop gnome-keyring; then
            ok "Claude Desktop installed. Open Claude from the Start menu or its desktop icon, then sign in."
            return 0
        fi
    fi
    bad "Claude Desktop install failed – check the internet and free space, then try again"
}

install_claude_code() {
    if install_node && npm install -g @anthropic-ai/claude-code; then
        ok "Claude Code installed. Open a terminal, run:  claude   (log in with your Claude account)"
    else
        bad "Claude Code install failed"
    fi
}

cpu_arch() {   # arm64 on phones; x64 on computers
    case "$(uname -m)" in
        aarch64|arm64) echo arm64 ;;
        x86_64|amd64) echo x64 ;;
        *) echo "" ;;
    esac
}

install_opencode() {
    # OpenCode (open-source coding agent, opencode.ai): one program, official Linux build.
    local arch tgz=/tmp/opencode.tar.gz dir=/tmp/opencode.new
    arch=$(cpu_arch)
    [ -n "$arch" ] || { bad "OpenCode needs a 64-bit phone"; return 1; }
    echo "Installing OpenCode (about 120 MB)..."
    rm -rf "$dir" && mkdir -p "$dir"
    if curl -fL --retry 3 -o "$tgz" "https://opencode.ai/update/api/latest/cli/opencode/opencode-linux-$arch.tar.gz" &&
        tar -xzf "$tgz" -C "$dir" && [ -x "$dir/opencode" ]; then
        install -m 755 "$dir/opencode" /usr/local/bin/opencode
        ok "OpenCode installed. Open it from the dock or menu (AI & Coding), or run:  opencode"
    else
        bad "OpenCode install failed – check the internet and try again"
    fi
    rm -rf "$tgz" "$dir"
}

install_antigravity_cli() {
    # Google Antigravity CLI ("agy"): Google's own installer, which checks its download (SHA-512).
    [ -n "$(cpu_arch)" ] || { bad "Antigravity needs a 64-bit phone"; return 1; }
    echo "Installing Antigravity CLI (about 90 MB download)..."
    if curl -fsSL https://antigravity.google/cli/install.sh | bash -s -- --dir /usr/local/bin && [ -x /usr/local/bin/agy ]; then
        ok "Antigravity CLI installed. Open it from the dock or menu (AI & Coding), or run:  agy"
    else
        bad "Antigravity CLI install failed – check the internet and try again"
    fi
}

install_antigravity_ide() {
    # Google Antigravity IDE (a big code editor app): Google's own package repository, so it updates
    # with the system. About 720 MB installed – slow on most phones.
    echo "Installing Antigravity IDE (big: about 150 MB download, 720 MB on the phone)..."
    mkdir -p /etc/apt/keyrings
    # The key is in text form (.asc): apt reads it as it is, no gpg needed.
    if curl -fsSLo /etc/apt/keyrings/antigravity-repo-key.asc https://us-central1-apt.pkg.dev/doc/repo-signing-key.gpg; then
        echo "deb [signed-by=/etc/apt/keyrings/antigravity-repo-key.asc] https://us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev/ antigravity-debian main" \
            > /etc/apt/sources.list.d/antigravity.list
        if apt-get update && apt-get install -y --no-install-recommends antigravity; then
            ok "Antigravity IDE installed. Open it from the menu (Development). The first start takes a while."
            return 0
        fi
    fi
    bad "Antigravity IDE install failed – check the internet and free space, then try again"
}

install_ollama() {
    echo "Installing Ollama (offline AI). Small models only on phones!"
    apt-get install -y zstd >/dev/null 2>&1 || true
    if curl -fsSL https://ollama.com/install.sh | sh; then
        cat > /usr/local/bin/ai-chat <<'AI'
#!/bin/sh
# Starts the Ollama server (no systemd here) and chats with a small model.
MODEL="${1:-qwen2.5:1.5b}"
pgrep -x ollama >/dev/null || (ollama serve >/tmp/ollama.log 2>&1 &)
sleep 3
exec ollama run "$MODEL"
AI
        chmod +x /usr/local/bin/ai-chat
        ok "Ollama installed. Run:  ai-chat            (model qwen2.5:1.5b, ~1 GB download)"
        ok "Smaller/faster:          ai-chat qwen2.5:0.5b"
    else
        bad "Ollama install failed"
    fi
}

install_brave() {
    # Brave: Chrome-like browser with a built-in ad blocker (lighter pages). Official repository.
    echo "Installing Brave browser (ARM64)..."
    apt-get install -y --no-install-recommends curl ca-certificates >/dev/null 2>&1 || true
    mkdir -p /usr/share/keyrings
    if curl -fsSLo /usr/share/keyrings/brave-browser-archive-keyring.gpg \
            https://brave-browser-apt-release.s3.brave.com/brave-browser-archive-keyring.gpg &&
        echo "deb [signed-by=/usr/share/keyrings/brave-browser-archive-keyring.gpg] https://brave-browser-apt-release.s3.brave.com/ stable main" \
            > /etc/apt/sources.list.d/brave-browser-release.list &&
        apt-get update && apt-get install -y brave-browser; then
        ok "Brave installed (desktop icon, Start menu → Internet)"
    else
        bad "Failed: Brave"
    fi
}

install_github() {
    install_apt git gh
    command -v gh >/dev/null 2>&1 && ok "GitHub ready: desktop icon GitHub (sign in, clone, publish, push)"
}

install_gdrive() {
    echo "Installing Google Drive support (rclone)..."
    if ! apt-get install -y rclone; then bad "rclone install failed"; return; fi
    cat > /usr/local/bin/google-drive <<'GD'
#!/bin/bash
# Your Google Drive (15 GB free) in a browser file manager. First time: log in to Google.
# Your password is typed only on Google's own page – nobody else sees it.
export DISPLAY="${DISPLAY:-:0}"
if ! rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
    echo "Pehli baar: browser khulega -> Google me login karo -> 'Allow' dabao."
    echo "(Browser na khule to neeche dikh rahe http://127.0.0.1:53682/... link ko Firefox me kholo)"
    rclone config create gdrive drive scope=drive || { echo "Login nahi hua. Dobara chalao: google-drive"; exit 1; }
fi
echo ""
echo "Google Drive file manager browser me khul raha hai (http://127.0.0.1:5572)."
echo "Is window ko khula rakho. Band karne par Drive bhi band."
exec rclone rcd --rc-web-gui --rc-no-auth --rc-addr 127.0.0.1:5572
GD
    chmod +x /usr/local/bin/google-drive
    mkdir -p "$REAL_HOME/Desktop"
    cat > "$REAL_HOME/Desktop/Google-Drive.desktop" <<'GDD'
[Desktop Entry]
Type=Application
Name=Google Drive
Exec=cloudpc-gdrive
Icon=folder-remote
Terminal=false
GDD
    chmod +x "$REAL_HOME/Desktop/Google-Drive.desktop"
    ok "Google Drive ready. Desktop par 'Google Drive' icon, ya terminal me:  google-drive"
    ok "Commands bhi: rclone ls gdrive:   |   rclone copy ~/Documents gdrive:Backup"
}

install_each() {
    # Installs packages one by one, so one missing name does not stop the rest.
    for p in "$@"; do
        if apt-get install -y --no-install-recommends "$p" >/dev/null 2>&1; then ok "  + $p"
        else bad "  - $p (not available, skipped)"; fi
    done
}

install_games_light() {
    echo "Installing games that run smoothly on phones (2D, offline)..."
    install_each supertux frozen-bubble pingus xmoto 2048-qt openttd openttd-opengfx \
        aisleriot gnome-mines gnome-sudoku gnome-chess stockfish quadrapassel
    ok "Games: Desktop → Games folder, or Start → Games."
    ok "SuperTux (platformer), Frozen Bubble, Pingus, X-Moto (bike), 2048, OpenTTD (transport tycoon),"
    ok "Solitaire, Mines, Sudoku, Chess, Quadrapassel (falling blocks)."
}

install_games_3d() {
    echo "Installing 3D games (use the Game profile in AirBoost for the best FPS)..."
    install_each supertuxkart neverball extremetuxracer
    apt-get install -y --no-install-recommends luanti >/dev/null 2>&1 && ok "  + luanti (block building)" \
        || install_each minetest
    ok "3D games: Desktop → Games folder. Set graphics to LOW inside each game."
}

install_game_tools() {
    # FPS overlay + steady FPS limit for games (AirCursor PC starts games with it), FPS test tool.
    apt-get install -y --no-install-recommends mangohud mesa-utils >/dev/null 2>&1 || true
}

install_coding() {
    install_apt git build-essential python3 python3-pip python3-venv curl
    install_node || bad "Node.js could not be installed"
    ok "Coding tools ready: git, gcc/g++, make, python3, pip, node, npm"
}

install_apt() {
    echo "Installing: $*"
    # Without "recommended" extras: much smaller downloads, much faster on a phone.
    if apt-get install -y --no-install-recommends "$@"; then ok "Installed: $*"; else bad "Failed: $*"; fi
}

label_for() {
    case "$1" in
        vscode) echo "VS Code" ;; claude) echo "Claude Code" ;; ollama) echo "Offline AI (Ollama)" ;;
        claude-desktop) echo "Claude Desktop" ;; codex) echo "Codex CLI" ;; gemini-cli) echo "Gemini CLI" ;;
        copilot-cli) echo "GitHub Copilot CLI" ;; opencode) echo "OpenCode" ;; antigravity) echo "Antigravity CLI" ;;
        antigravity-ide) echo "Antigravity IDE" ;;
        blender) echo "Blender" ;; libreoffice) echo "LibreOffice" ;; gimp) echo "GIMP" ;;
        inkscape) echo "Inkscape" ;; python) echo "Python" ;; vlc) echo "VLC" ;; gdrive) echo "Google Drive" ;; brave) echo "Brave" ;; github) echo "GitHub" ;;
        shotcut) echo "Shotcut" ;; kdenlive) echo "Kdenlive" ;; games) echo "Games pack" ;;
        games3d) echo "3D games" ;; luanti) echo "Minetest" ;; coding) echo "Coding tools" ;;
        trigger) echo "Trigger Rally" ;; tuxracer) echo "Extreme Tux Racer" ;; *) echo "$1" ;;
    esac
}

after_install() {
    # Games go to /usr/games (not on the PATH here): make them start from terminal and menu
    for f in /usr/games/*; do [ -f "$f" ] && ln -sf "$f" /usr/local/bin/; done
    chown -R "$REAL_USER:$REAL_USER" "$REAL_HOME" 2>/dev/null || true
}

install_id() {
    case "$1" in
        vscode) install_vscode ;;
        claude) install_claude_code ;;
        claude-desktop) install_claude_desktop ;;
        codex) install_npm_tool "Codex CLI" @openai/codex codex 18 ;;
        gemini-cli) install_npm_tool "Gemini CLI" @google/gemini-cli gemini 20 ;;
        copilot-cli) install_npm_tool "GitHub Copilot CLI" @github/copilot copilot 22 ;;
        opencode) install_opencode ;;
        antigravity) install_antigravity_cli ;;
        antigravity-ide) install_antigravity_ide ;;
        ollama) install_ollama ;;
        blender) install_apt blender ;;
        libreoffice) install_apt libreoffice ;;
        gimp) install_apt gimp ;;
        inkscape) install_apt inkscape ;;
        python) install_apt python3 python3-pip python3-venv ;;
        vlc) install_apt vlc ;;
        gdrive) install_gdrive ;;
        brave) install_brave ;;
        github) install_github ;;
        shotcut) install_apt shotcut ;;
        kdenlive) install_apt kdenlive ;;
        coding) install_coding ;;
        games) install_games_light; install_game_tools ;;
        games3d) install_games_3d; install_game_tools ;;
        luanti) { apt-get install -y --no-install-recommends luanti || install_apt minetest; }; install_game_tools ;;
        supertuxkart|neverball|openarena|torcs) install_apt "$1"; install_game_tools ;;
        trigger) install_apt trigger-rally; install_game_tools ;;
        tuxracer) install_apt extremetuxracer; install_game_tools ;;
        openttd) install_apt openttd openttd-opengfx; install_game_tools ;;
        gnome-chess) install_apt gnome-chess stockfish ;;
        hedgewars|wesnoth|warzone2100|teeworlds|xmoto|frozen-bubble|pingus|chromium-bsu|blockout2|lbreakouthd)
            install_apt "$1"; install_game_tools ;;
        *) install_apt "$1" ;;   # any Ubuntu package name (App Store search)
    esac
}

# Removes an App Store app (its files in the home folder stay).
remove_id() {
    local pkgs=""
    case "$1" in
        vscode) rm -rf /opt/code-server; rm -f /usr/local/bin/code-server; dpkg -s code >/dev/null 2>&1 && pkgs="code" ;;
        libreoffice) pkgs=$(dpkg-query -W -f='${Package}\n' 'libreoffice*' 2>/dev/null | tr '\n' ' ') ;;
        claude) npm uninstall -g @anthropic-ai/claude-code; return ;;
        claude-desktop) pkgs="claude-desktop"; rm -f /etc/apt/sources.list.d/claude-desktop.list ;;
        appimage-*) rm -rf "/opt/appimages/${1#appimage-}" "/usr/share/applications/$1.desktop"; ok "Removed"; return ;;
        codex) npm uninstall -g @openai/codex; return ;;
        gemini-cli) npm uninstall -g @google/gemini-cli; return ;;
        copilot-cli) npm uninstall -g @github/copilot; return ;;
        opencode) rm -f /usr/local/bin/opencode; return ;;
        antigravity) rm -f /usr/local/bin/agy; return ;;
        antigravity-ide) pkgs="antigravity"; rm -f /etc/apt/sources.list.d/antigravity.list /etc/apt/keyrings/antigravity-repo-key.asc ;;
        ollama) rm -rf /usr/local/bin/ollama /usr/local/bin/ai-chat /usr/local/lib/ollama /usr/share/ollama; return ;;
        gdrive) pkgs="rclone"; rm -f /usr/local/bin/google-drive "$REAL_HOME/Desktop/Google-Drive.desktop" ;;
        brave) pkgs="brave-browser brave-keyring" ;;
        github) pkgs="gh" ;;
        games) pkgs="supertux frozen-bubble pingus xmoto 2048-qt openttd openttd-opengfx aisleriot gnome-mines gnome-sudoku gnome-chess stockfish quadrapassel" ;;
        games3d) pkgs="supertuxkart neverball extremetuxracer minetest luanti" ;;
        luanti) pkgs="minetest luanti" ;;
        trigger) pkgs="trigger-rally trigger-rally-data" ;;
        tuxracer) pkgs="extremetuxracer extremetuxracer-data" ;;
        supertuxkart|neverball|openarena|torcs) pkgs="$1 $1-data" ;;
        openttd) pkgs="openttd openttd-data openttd-opengfx" ;;
        gnome-chess) pkgs="gnome-chess stockfish" ;;
        wesnoth) pkgs=$(dpkg-query -W -f='${Package}\n' 'wesnoth*' 2>/dev/null | tr '\n' ' ') ;;
        hedgewars|warzone2100|xmoto|frozen-bubble|pingus|teeworlds|blockout2|lbreakouthd|chromium-bsu)
            pkgs="$1 $(dpkg-query -W -f='${Package}\n' "$1-data" "$1-music" 2>/dev/null | tr '\n' ' ')" ;;
        python|coding|python3*|xfce4*|firefox*|zenity|tigervnc*|dbus*) bad "This is part of the PC and stays."; return ;;
        *) pkgs="$1" ;;
    esac
    local p list=""
    for p in $pkgs; do dpkg -s "$p" >/dev/null 2>&1 && list="$list $p"; done
    [ -n "$list" ] || { ok "Nothing to remove"; return; }
    # shellcheck disable=SC2086
    apt-get remove -y $list && apt-get autoremove -y && ok "Removed:$list" || bad "Failed: remove$list"
}

if [ "${1:-}" = "remove" ]; then
    shift
    for id in "$@"; do echo "Removing $(label_for "$id")…"; remove_id "$id"; done
    exit 0
fi

# Non-interactive mode, used by the App Store:  ubuntu-extras install <id|package> ...
# Prints "@@PCT n" (overall percent) and "[i/n] Installing <app>" so the App Store can show progress.
if [ "${1:-}" = "install" ]; then
    shift
    total=$#
    i=0
    echo "@@PCT 1"
    echo "[0/$total] Updating the app list…"
    apt-get update >/dev/null 2>&1 || true
    for id in "$@"; do
        i=$((i + 1))
        echo "@@PCT $(( (i - 1) * 100 / total + 1 ))"
        echo "[$i/$total] Installing $(label_for "$id")…"
        install_id "$id"
        after_install
    done
    echo "@@PCT 100"
    exit 0
fi

while true; do
    cat <<MENU

  ===== Install apps (Ubuntu on Android) =====
   1) VS Code
   2) Claude Code (AI coding in terminal)
   3) Ollama (offline AI chat, small models)
   4) Blender (3D, basic use only on phones)
   5) LibreOffice (Word/Excel/PowerPoint files)
   6) GIMP (photo editing)
   7) Inkscape (vector design)
   8) Python 3 + pip + venv
   9) VLC (video/music player)
  10) Google Drive (15 GB free cloud storage)
  11) Shotcut (video editor, simple – 720p/1080p)
  12) Kdenlive (video editor, more features, heavier)
  13) Games – light & offline (SuperTux, OpenTTD, Chess, Solitaire...)
  14) Games – 3D (SuperTuxKart racing, Luanti/Minecraft-like) – heavier
   0) Exit
MENU
    read -r -p "  Choose a number: " c
    apt-get update >/dev/null 2>&1 || true
    case "$c" in
        1) install_vscode ;;
        2) install_claude_code ;;
        3) install_ollama ;;
        4) install_apt blender ;;
        5) install_apt libreoffice ;;
        6) install_apt gimp ;;
        7) install_apt inkscape ;;
        8) install_apt python3 python3-pip python3-venv ;;
        9) install_apt vlc ;;
        10) install_gdrive ;;
        11) install_apt shotcut
            ok "Shotcut: Applications -> Multimedia. Tip: Settings -> Preview scaling -> 360p for smooth editing" ;;
        13) install_games_light ;;
        14) install_games_3d ;;
        12) install_apt kdenlive
            ok "Kdenlive: Applications -> Multimedia. Tip: use proxy clips (Project settings) for smooth editing" ;;
        0) exit 0 ;;
        *) echo "Unknown choice" ;;
    esac
    after_install
done
