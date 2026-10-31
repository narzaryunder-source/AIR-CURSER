#!/bin/bash
# Extra apps for Ubuntu on Android (runs inside Ubuntu as root: sudo ubuntu-extras)
set -u
export DEBIAN_FRONTEND=noninteractive

if [ "$(id -u)" != "0" ]; then
    exec sudo "$0" "$@"
fi
REAL_USER="${SUDO_USER:-user}"
# An install that was interrupted (e.g. Android killed Termux) is finished first.
dpkg --configure -a >/dev/null 2>&1
apt-get -f install -y >/dev/null 2>&1

ok() { printf '\033[1;32m%s\033[0m\n' "$*"; }
bad() { printf '\033[1;31m%s\033[0m\n' "$*"; }

install_vscode() {
    echo "Installing VS Code (ARM64)..."
    local deb=/tmp/vscode-arm64.deb
    if wget -O "$deb" "https://code.visualstudio.com/sha/download?build=stable&os=linux-deb-arm64" &&
        apt-get install -y "$deb"; then
        # Electron apps need --no-sandbox inside proot
        sed -i 's|^Exec=/usr/share/code/code |Exec=/usr/share/code/code --no-sandbox |' \
            /usr/share/applications/code*.desktop 2>/dev/null || true
        printf '#!/bin/sh\nexec /usr/share/code/code --no-sandbox "$@"\n' > /usr/local/bin/vscode
        chmod +x /usr/local/bin/vscode
        ok "VS Code installed (menu: Development > Visual Studio Code, or run: vscode)"
    else
        bad "VS Code install failed"
    fi
    rm -f "$deb"
}

install_node() {
    if command -v node >/dev/null 2>&1 && [ "$(node -v | cut -c2- | cut -d. -f1)" -ge 18 ]; then return 0; fi
    echo "Installing Node.js 20..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt-get install -y nodejs
}

install_claude_code() {
    if install_node && npm install -g @anthropic-ai/claude-code; then
        ok "Claude Code installed. Open a terminal, run:  claude   (log in with your Claude account)"
    else
        bad "Claude Code install failed"
    fi
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
    mkdir -p "/home/$REAL_USER/Desktop"
    cat > "/home/$REAL_USER/Desktop/Google-Drive.desktop" <<'GDD'
[Desktop Entry]
Type=Application
Name=Google Drive
Exec=xfce4-terminal --hold -e google-drive
Icon=folder-remote
Terminal=false
GDD
    chmod +x "/home/$REAL_USER/Desktop/Google-Drive.desktop"
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
    echo "Installing light offline games (2D, smooth on phones)..."
    install_each supertux frozen-bubble pingus openttd openttd-opengfx \
        aisleriot gnome-mines gnome-sudoku gnome-chess stockfish quadrapassel
    ok "Games: Applications -> Games. (SuperTux = Mario jaisa, OpenTTD = train/transport tycoon,"
    ok "Frozen Bubble, Pingus, Solitaire, Mines, Sudoku, Chess, Quadrapassel = Tetris)"
}

install_games_3d() {
    echo "Installing 3D games (heavier – start the desktop with: ubuntu-desktop --gpu)..."
    install_each supertuxkart
    apt-get install -y --no-install-recommends luanti >/dev/null 2>&1 && ok "  + luanti (Minecraft jaisa)" \
        || install_each minetest
    ok "3D games: Applications -> Games. Settings me graphics LOW rakho. SuperTuxKart data bada hai (~600 MB)."
}

install_apt() {
    echo "Installing: $*"
    if apt-get install -y "$@"; then ok "Installed: $*"; else bad "Failed: $*"; fi
}

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
    # Games go to /usr/games (not on the PATH here): make them start from terminal and menu
    for f in /usr/games/*; do [ -f "$f" ] && ln -sf "$f" /usr/local/bin/; done
    chown -R "$REAL_USER:$REAL_USER" "/home/$REAL_USER" 2>/dev/null || true
done
