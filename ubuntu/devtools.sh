#!/bin/bash
# Developer Setup for AirCursor PC: what you need to build apps and websites here, and a check that
# it really works (a local Python web server, FFmpeg encoding a video, Node.js, git, a C compiler).
#   cloudpc-devtools           install what is missing (with a progress window), then show the check
#   cloudpc-devtools --check   only the check, as text (for a terminal or an AI coding tool)
. /tmp/cloudpc-session.env 2>/dev/null
export LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive

PKGS="python3 python3-pip python3-venv ffmpeg git nodejs npm build-essential sqlite3 unzip curl"

missing() {
    local m=""
    for p in $PKGS; do
        dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed" || m="$m $p"
    done
    echo "$m"
}

# One line per check: "ok|name|detail" or "no|name|detail".
check() {
    local v
    if v=$(python3 --version 2>&1); then echo "ok|Python|$v"; else echo "no|Python|not installed"; fi
    if python3 -m venv --help >/dev/null 2>&1 && python3 -m pip --version >/dev/null 2>&1; then
        echo "ok|pip + venv|$(python3 -m pip --version 2>/dev/null | cut -d' ' -f1-2)"
    else
        echo "no|pip + venv|missing"
    fi
    # A real local web server: start one, fetch a page from it, stop it.
    local port=$(( 18000 + RANDOM % 2000 )) dir
    dir=$(mktemp -d)
    echo "AirCursor PC local server works" > "$dir/index.html"
    (cd "$dir" && exec python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1) &
    local pid=$! got=""
    for _ in $(seq 1 30); do
        got=$(curl -s --max-time 1 "http://127.0.0.1:$port/" 2>/dev/null) && [ -n "$got" ] && break
        sleep 0.2
    done
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    rm -rf "$dir"
    if [ "$got" = "AirCursor PC local server works" ]; then
        echo "ok|Local Python server|http://127.0.0.1:$port answered"
    else
        echo "no|Local Python server|did not answer"
    fi
    # FFmpeg: encode a one-second test video (H.264 if available) and read it back.
    if command -v ffmpeg >/dev/null 2>&1; then
        local out
        out=$(mktemp --suffix=.mp4)
        if ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=320x240:rate=15 \
            -c:v libx264 -pix_fmt yuv420p "$out" 2>/dev/null ||
            ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=320x240:rate=15 "$out" 2>/dev/null; then
            if ffprobe -v error -show_entries format=duration -of csv=p=0 "$out" >/dev/null 2>&1; then
                echo "ok|FFmpeg|$(ffmpeg -version 2>/dev/null | head -n1 | cut -d' ' -f1-3) · made a test video"
            else
                echo "no|FFmpeg|the test video could not be read"
            fi
        else
            echo "no|FFmpeg|could not make a test video"
        fi
        rm -f "$out"
    else
        echo "no|FFmpeg|not installed"
    fi
    if v=$(node --version 2>/dev/null); then echo "ok|Node.js + npm|node $v · npm $(npm --version 2>/dev/null)"; else echo "no|Node.js + npm|not installed"; fi
    if v=$(git --version 2>/dev/null); then echo "ok|git|$v"; else echo "no|git|not installed"; fi
    if v=$(gcc --version 2>/dev/null | head -n1); then echo "ok|C/C++ compiler|$v"; else echo "no|C/C++ compiler|not installed"; fi
}

report_text() {
    local all=0
    while IFS='|' read -r st name detail; do
        if [ "$st" = ok ]; then printf '  ✓ %-20s %s\n' "$name" "$detail"; else printf '  ✗ %-20s %s\n' "$name" "$detail"; all=1; fi
    done
    return $all
}

if [ "$1" = "--check" ]; then
    echo "AirCursor PC – developer check (Ubuntu $(. /etc/os-release 2>/dev/null; echo "$VERSION_ID"), $(uname -m))"
    check | report_text
    rc=$?
    [ $rc = 0 ] && echo "Everything is ready." || echo "Missing parts: run  cloudpc-devtools  (or: sudo apt-get install -y$(missing))"
    exit $rc
fi

need=$(missing)
if [ -n "$need" ]; then
    if [ -n "$DISPLAY" ] && command -v zenity >/dev/null 2>&1; then
        zenity --question --width=520 --title="Developer Setup" --ok-label="Install" \
            --text="To build apps and websites in AirCursor PC this installs:\n\n$(echo $need | tr ' ' '\n' | sed 's/^/  • /')\n\nAbout 300–500 MB from Ubuntu. Continue?" 2>/dev/null || exit 0
        ( apt-get update 2>&1 | sed -u 's/^/# /'
          apt-get install -y --no-install-recommends $need 2>&1 | sed -u 's/^/# /' ) |
            zenity --progress --pulsate --auto-close --no-cancel --width=600 --title="Developer Setup" --text="Installing…" 2>/dev/null
    else
        apt-get update && apt-get install -y --no-install-recommends $need
    fi
fi

RESULT=$(check)
TEXT=$(echo "$RESULT" | while IFS='|' read -r st name detail; do
    [ "$st" = ok ] && echo "✓  $name — $detail" || echo "✗  $name — $detail"
done)
if [ -n "$DISPLAY" ] && command -v zenity >/dev/null 2>&1; then
    zenity --info --no-markup --width=620 --title="Developer Setup" \
        --text="$TEXT

Your local server opens in Air: http://127.0.0.1:PORT (the same phone).
Check again any time in a terminal:  cloudpc-devtools --check" 2>/dev/null
else
    echo "$TEXT"
fi
