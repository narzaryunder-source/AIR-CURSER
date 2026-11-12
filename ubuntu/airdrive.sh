#!/bin/bash
# cloudpc-airdrive – Air Drive: AirCursor PC Pro's 100 GB cloud drive.
#
# Home → "Air Drive" folder ⇄ your private space in the cloud (both ways, with rclone).
# Files are encrypted here, in the PC, before they leave the phone: names and contents are
# unreadable for anyone else – the owner of the service included. The key stays in this PC
# (and in the recovery key you save); without it nobody can open the files.
#
# The AirCursor app puts the Pro licence key and the service address into
# /root/.config/cloudpc/ (pro-license, airdrive-url) while Pro is active.
. /tmp/cloudpc-session.env 2>/dev/null
export LANG=C.UTF-8 GSK_RENDERER=cairo NO_AT_BRIDGE=1
T="Air Drive"
DIR="/root/Air Drive"
CFG=/root/.config/cloudpc
CONF="$CFG/airdrive.conf"        # rclone settings (short-lived access, renewed every sync)
KEYS="$CFG/airdrive-keys"         # the encryption key (two random passwords) – never leaves the PC
MARK="$CFG/airdrive-synced"
export RCLONE_CONFIG="$CONF"
mkdir -p "$DIR" "$CFG"
chmod 700 "$CFG"

LIC=$(tr -d ' \n\r' < "$CFG/pro-license" 2>/dev/null)
URL=$(tr -d ' \n\r' < "$CFG/airdrive-url" 2>/dev/null)
URL=${URL%/}

info() { zenity --info --no-markup --width=480 --title="$T" --text="$1" >/dev/null 2>&1; }
fail() { zenity --error --no-markup --width=480 --title="$T" --text="$1" >/dev/null 2>&1; }
gb() { awk -v b="$1" 'BEGIN { printf (b >= 1073741824 ? "%.1f GB" : "%.0f MB"), (b >= 1073741824 ? b / 1073741824 : b / 1048576) }'; }

if [ -z "$LIC" ] || [ -z "$URL" ]; then
    info "Air Drive is part of AirCursor PC Pro: 100 GB of private, encrypted storage for your files.

Get Pro in the AirCursor app (Pro screen), then open Air Drive again."
    exit 0
fi

if ! command -v rclone >/dev/null 2>&1; then
    zenity --question --width=420 --title="$T" --ok-label="Install" \
        --text="Air Drive needs its sync tool (about 25 MB). Install it now?" || exit 0
    ( ubuntu-extras install rclone 2>&1 | sed -u 's/^/# /' ) |
        zenity --progress --pulsate --auto-close --no-cancel --width=480 --title="$T" --text="Installing…"
    command -v rclone >/dev/null 2>&1 || { fail "Could not install. Check the internet."; exit 1; }
fi

# The encryption key: made once, here. Shown to the person to keep safe (it is the only way to
# open the files on a new phone).
new_keys() {
    local a b
    a=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
    b=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
    umask 077
    printf 'AIRDRIVE1-%s-%s\n' "$a" "$b" > "$KEYS"
}
show_recovery() {
    zenity --info --width=560 --title="$T – recovery key" --text="Your Air Drive recovery key:

<b><tt>$(cat "$KEYS")</tt></b>

Keep it somewhere safe (a note on paper or a password manager). It opens your files on a new
phone. Without it nobody – not even AirCursor – can open them." >/dev/null 2>&1
}
if [ ! -s "$KEYS" ]; then
    C=$(zenity --list --width=560 --height=260 --title="$T" \
        --text="Set up Air Drive (100 GB, encrypted on this PC)" \
        --column=id --column="Choose" --hide-column=1 --print-column=1 \
        new "First time: make a new encryption key" \
        restore "I have a recovery key (from another phone)") || exit 0
    if [ "$C" = restore ]; then
        K=$(zenity --entry --width=560 --title="$T" --text="Paste your recovery key (AIRDRIVE1-…):") || exit 0
        K=$(printf '%s' "$K" | tr -d ' \n\r')
        [[ "$K" =~ ^AIRDRIVE1-[A-Za-z0-9]{20,}-[A-Za-z0-9]{20,}$ ]] || { fail "That is not a recovery key."; exit 1; }
        umask 077
        printf '%s\n' "$K" > "$KEYS"
        rm -f "$MARK"
    else
        new_keys
        show_recovery
    fi
fi

# A fresh, short-lived access to this phone's own folder (asks the Air Drive service).
connect() {
    local out
    out=$(curl -sS -m 25 -X POST -H "Authorization: Bearer $LIC" "$URL/v1/session" 2>&1) || {
        fail "Air Drive cannot be reached. Check the internet and try again."; return 1; }
    python3 - "$out" "$KEYS" "$CONF" <<'PY' || return 1
import json, os, subprocess, sys
raw, keys, conf = sys.argv[1:4]
try:
    s = json.loads(raw)
except Exception:
    print("bad answer", file=sys.stderr); sys.exit(1)
if "error" in s:
    msg = {"seats_full": "All Pro places are taken right now. Please ask the seller.",
           "expired": "Your Pro plan has ended. Renew it in the AirCursor app.",
           "bad_signature": "Your Pro key is not valid.", "malformed": "Your Pro key is not valid.",
           "no_seller_key": "Air Drive is not set up yet by the seller."}.get(s["error"], "Air Drive: " + s["error"])
    subprocess.run(["zenity", "--error", "--no-markup", "--width=480", "--title=Air Drive", "--text=" + msg])
    sys.exit(1)
k = open(keys).read().strip().split("-")
ob = lambda p: subprocess.run(["rclone", "obscure", p], capture_output=True, text=True).stdout.strip()
remote = "airdrive:%s/%sfiles" % (s["bucket"], s["prefix"])
text = "\n".join([
    "[airdrive]", "type = s3", "provider = Cloudflare", "access_key_id = " + s["accessKeyId"],
    "secret_access_key = " + s["secretAccessKey"], "session_token = " + s["sessionToken"],
    "endpoint = " + s["endpoint"], "region = auto", "no_check_bucket = true", "",
    "[airdrive-crypt]", "type = crypt", "remote = " + remote, "password = " + ob(k[1]), "password2 = " + ob(k[2]),
    "filename_encryption = standard", "directory_name_encryption = true", ""])
fd = os.open(conf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    f.write(text)
with open(conf + ".state", "w") as f:
    json.dump({"used": s.get("usedBytes", 0), "quota": s.get("quotaBytes", 0), "readOnly": s.get("readOnly", False)}, f)
PY
}
state() { python3 -c "import json,sys; print(json.load(open('$CONF.state')).get('$1'))" 2>/dev/null; }

sync_now() {
    connect || return
    local extra="" ro
    ro=$(state readOnly)
    [ -f "$MARK" ] || extra="--resync"
    if [ "$ro" = True ]; then
        zenity --question --width=480 --title="$T" --ok-label="Download only" \
            --text="Your Air Drive is full ($(gb "$(state used)") of $(gb "$(state quota)")). Delete some files to upload again.\n\nDownload the files from the cloud now?" || return
        ( rclone copy airdrive-crypt: "$DIR" -v 2>&1 | sed -u 's/^/# /'; echo "${PIPESTATUS[0]}" > /tmp/airdrive-sync.rc ) |
            zenity --progress --pulsate --auto-close --width=560 --title="$T" --text="Downloading…"
    else
        ( rclone bisync "$DIR" airdrive-crypt: $extra -v 2>&1 | sed -u 's/^/# /'
          echo "${PIPESTATUS[0]}" > /tmp/airdrive-sync.rc ) |
            zenity --progress --pulsate --auto-close --width=560 --title="$T" --text="Syncing (encrypted)…"
    fi
    if [ "$(cat /tmp/airdrive-sync.rc 2>/dev/null)" = 0 ]; then
        [ "$ro" = True ] || touch "$MARK"
        notify-send -a "AirCursor PC" -i folder-remote "Air Drive" "Synced." >/dev/null 2>&1
    else
        rm -f "$MARK"
        fail "Sync did not finish (internet?). Try again – the next sync starts fresh."
    fi
}

usage() {
    connect || return
    info "Air Drive: $(gb "$(state used)") used of $(gb "$(state quota)").

The number is refreshed about every 10 minutes."
}

while true; do
    C=$(zenity --list --width=560 --height=400 --title="$T" \
        --text="Home → Air Drive  ⇄  your private 100 GB in the cloud (encrypted here)" \
        --column=id --column="What do you want to do?" --hide-column=1 --print-column=1 \
        sync "Sync now (both ways)" \
        open "Open the Air Drive folder" \
        used "Storage used" \
        key "Show my recovery key" \
        out "Disconnect this PC (files stay in both places)") || exit 0
    case "$C" in
        sync) sync_now ;;
        open) setsid thunar "$DIR" >/dev/null 2>&1 & ;;
        used) usage ;;
        key) show_recovery ;;
        out) zenity --question --width=480 --title="$T" \
                --text="Disconnect Air Drive from this PC? Your files stay here and in the cloud.\n\nKeep your recovery key: without it the cloud copy cannot be opened again." &&
                { rm -f "$CONF" "$CONF.state" "$MARK"; exit 0; } ;;
    esac
done
