#!/data/data/com.termux/files/usr/bin/bash
# One-time fix: stop Android 12+ from killing Termux's processes ("signal 9", phantom process killer).
# Uses the phone's own Wireless debugging – no PC needed. Changes only this Android setting.
#   curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/no-kill.sh -o no-kill.sh && bash no-kill.sh
if [ "$(id -u)" = "0" ]; then echo "Pehle 'exit' likho (aap Ubuntu ke andar ho)."; exit 1; fi
Y='\033[1;33m'; G='\033[1;32m'; R='\033[1;31m'; N='\033[0m'

echo "==> Installing adb (small, one time)"
command -v adb >/dev/null || pkg install -y android-tools || { echo "Install failed – internet?"; exit 1; }

printf "\n${Y}STEP A:${N} Phone WiFi ON karo (koi bhi WiFi/hotspot, internet zaroori nahi).\n"
printf "${Y}STEP B:${N} Split screen: upar Termux, neeche Settings.\n"
printf "        Settings → Additional settings → Developer options → ${Y}Wireless debugging${N} → ON → Allow\n"
printf "${Y}STEP C:${N} Wireless debugging ke andar ${Y}'Pair device with pairing code'${N} dabao.\n"
printf "        Dialog me 6 ankon ka CODE aur 'IP address & Port' dikhega (jaise 192.168.1.5:${Y}41234${N}).\n\n"
read -r -p "Pairing PORT likho (':' ke baad wala number): " PP
read -r -p "Pairing CODE likho (6 ank): " PC
if ! adb pair "127.0.0.1:$PP" "$PC"; then
    printf "${R}Pairing nahi hua.${N} Dialog band ho gaya ho to dobara 'Pair device with pairing code' dabao aur script phir chalao.\n"
    exit 1
fi

printf "\n${Y}STEP D:${N} Ab Wireless debugging ki ${Y}main screen${N} par jo 'IP address & Port' hai (ALAG port), uska port likho.\n"
read -r -p "Connect PORT likho: " CP
adb connect "127.0.0.1:$CP"
D="127.0.0.1:$CP"
sleep 1
adb -s "$D" shell settings put global settings_enable_monitor_phantom_procs false
adb -s "$D" shell "/system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/device_config put activity_manager max_phantom_processes 2147483647" >/dev/null 2>&1

V=$(adb -s "$D" shell settings get global settings_enable_monitor_phantom_procs 2>/dev/null | tr -d '\r')
M=$(adb -s "$D" shell /system/bin/device_config get activity_manager max_phantom_processes 2>/dev/null | tr -d '\r')
adb disconnect "$D" >/dev/null 2>&1
echo
echo "   monitor_phantom_procs = $V   (chahiye: false)"
echo "   max_phantom_processes = $M   (chahiye: 2147483647)"
if [ "$V" = "false" ] || [ "$M" = "2147483647" ]; then
    printf "${G}✅ Ho gaya! Android ab Termux/Ubuntu ko signal 9 se band nahi karega.${N}\n"
    printf "   Ab Wireless debugging OFF kar sakte ho. Phone restart ke baad bhi yeh setting rahegi.\n"
    printf "   Desktop chalao:  bash fix.sh   (ya ubuntu-desktop)\n"
else
    printf "${R}Setting nahi lagi.${N} Is screen ka screenshot bhejo.\n"
fi
