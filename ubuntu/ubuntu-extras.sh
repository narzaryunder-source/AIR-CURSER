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
        0) exit 0 ;;
        *) echo "Unknown choice" ;;
    esac
    chown -R "$REAL_USER:$REAL_USER" "/home/$REAL_USER" 2>/dev/null || true
done
