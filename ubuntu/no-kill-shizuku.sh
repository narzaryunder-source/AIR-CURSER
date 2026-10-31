#!/data/data/com.termux/files/usr/bin/bash
# Same fix as no-kill.sh, but through the Shizuku app (for phones that close the pairing
# code when you switch apps). Needs: Shizuku running + its "rish" files exported to Download.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/no-kill-shizuku.sh -o no-kill-shizuku.sh && bash no-kill-shizuku.sh
if [ "$(id -u)" = "0" ]; then echo "Pehle 'exit' likho (aap Ubuntu ke andar ho)."; exit 1; fi
G='\033[1;32m'; R='\033[1;31m'; Y='\033[1;33m'; N='\033[0m'

if [ ! -r /sdcard/Download ]; then
    echo "Phone storage ki permission chahiye – 'Allow' dabao..."
    termux-setup-storage
    sleep 4
fi

SRC=""
for d in /sdcard/Download /sdcard/Documents /sdcard /sdcard/Shizuku /sdcard/Download/Shizuku; do
    if [ -f "$d/rish" ] && [ -f "$d/rish_shizuku.dex" ]; then SRC="$d"; break; fi
done
if [ -z "$SRC" ]; then
    printf "${R}rish files nahi mile.${N}\n"
    echo "Shizuku app → 'Use Shizuku in terminal apps' → 'Export files' → Download folder chuno → 'Use this folder' → Allow."
    echo "Phir yeh script dobara chalao."
    exit 1
fi
echo "rish files mile: $SRC"
mkdir -p "$HOME/.rish"
cp "$SRC/rish" "$SRC/rish_shizuku.dex" "$HOME/.rish/"
sed -i 's/PKG/com.termux/g' "$HOME/.rish/rish"
chmod 700 "$HOME/.rish/rish"
chmod 400 "$HOME/.rish/rish_shizuku.dex"   # Android 14 only loads read-only dex files
RISH="$HOME/.rish/rish"

CMD='settings put global settings_enable_monitor_phantom_procs false; /system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/device_config put activity_manager max_phantom_processes 2147483647; echo "monitor=$(settings get global settings_enable_monitor_phantom_procs) max=$(/system/bin/device_config get activity_manager max_phantom_processes)"'

for try in 1 2 3; do
    OUT=$(sh "$RISH" -c "$CMD" 2>&1)
    echo "$OUT" | tail -n 3
    if echo "$OUT" | grep -q "monitor=false"; then
        printf "\n${G}✅ Ho gaya! Android ab Termux/Ubuntu ko signal 9 se band nahi karega.${N}\n"
        echo "   Restart ke baad bhi rahega. Ab chalao:  bash fix.sh"
        exit 0
    fi
    if echo "$OUT" | grep -qiE "not running|binder"; then
        printf "${R}Shizuku chal nahi raha.${N} Shizuku app kholo → 'Start' dabao (Wireless debugging ON). Phir script dobara chalao.\n"
        exit 1
    fi
    printf "${Y}Agar Shizuku ne Termux ke liye 'Allow' poocha ho to Allow dabao, phir yahan Enter dabao...${N}"
    read -r _
done
printf "${R}Setting nahi lagi.${N} Is screen ka screenshot bhejo.\n"
