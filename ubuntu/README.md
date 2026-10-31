# 🐧 Ubuntu Desktop on Android (Termux + Termux:X11)

Phone par asli **Ubuntu Linux + XFCE4 desktop** – bina root ke. Ek command se setup.

## ✅ Kya chalega, kya nahi (seedhi baat)

| Cheez | Haal |
|---|---|
| Ubuntu desktop (XFCE4), Firefox, file manager, terminal | ✅ Achha chalta hai |
| VS Code, Python, git, Node.js | ✅ Chalta hai |
| **Claude Code** (AI coding) | ✅ Chalta hai (Claude account chahiye) |
| LibreOffice, GIMP, Inkscape, VLC | ✅ Chalte hain (thode slow) |
| **Ollama** (offline AI) | ⚠️ Sirf chhote models (0.5B–1.5B), dheere |
| **Blender** | ⚠️ Khulta hai, sirf basic kaam |
| Cursor AI / Google Antigravity | ⚠️ Sirf agar unka Linux **ARM64** version mile; guarantee nahi |
| Windows apps / PC games | ❌ Yahan bahut slow. Iske liye **Winlator** app try karo (sirf taakatwar phones) |

**Phone chahiye:** RAM 6 GB+ (4 GB par sirf basic), **6–10 GB khaali storage**, Android 10+.

## 📥 Step 1: 2 apps install karo (Play Store se NAHI)
1. **Termux** → https://github.com/termux/termux-app/releases
   (ya F-Droid). File: `termux-app_..._arm64-v8a.apk`
   - Play Store wala Termux purana hai, woh kaam nahi karega.
2. **Termux:X11** → https://github.com/termux/termux-x11/releases
   ("Nightly" release → `app-arm64-v8a-debug.apk`)

## ⚙️ Step 2: Android 12+ zaroori setting
Android 12 se zyada wale phones Termux ke processes ko apne aap band kar dete hain (desktop achanak band ho jata hai). Isse roko:
- **Settings → About phone → Build number** par 7 baar tap (Developer options on).
- **Settings → Developer options → "Disable child process restrictions" ON** (Android 14 me yeh option hota hai).
- Termux ko **Battery → Unrestricted / No restrictions** kar do.

## 🚀 Step 3: Ek command se setup
Termux kholo aur yeh paste karke Enter dabao:
```bash
curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/setup.sh -o setup.sh && bash setup.sh
```
- Username poochega → `user` likh do (ya Enter).
- **20–40 minute** lagenge (internet par depend). WiFi par chalao, ~2–3 GB data lagega.
- Script **kuch bhi delete nahi karti**. Ubuntu pehle se ho to wahi rakhti hai.

## 🖥️ Step 4: Desktop chalao
```bash
ubuntu-desktop
```
Termux:X11 apne aap khulega aur Ubuntu desktop dikhega.

| Command (Termux me) | Kaam |
|---|---|
| `ubuntu-desktop` | Desktop start |
| `ubuntu-desktop --gpu` | GPU ke saath (3D thoda tez; agar gadbad ho to bina `--gpu` chalao) |
| `ubuntu-stop` | Desktop band (files safe rehti hain) |
| `ubuntu-shell` | Sirf Ubuntu terminal (Claude Code / Ollama ke liye halka) |
| `ubuntu-wallpaper` | Download folder ki sabse nayi photo ko wallpaper banao (pehle `termux-setup-storage`) |
| `bash fix.sh` | Desktop me panel/dock gayab, X cursor, kaali screen – sab theek karta hai |

> ⚠️ Yeh sab commands **Termux** me chalti hain (prompt `~ $`). Agar prompt `root@localhost` ya `user@localhost` hai to aap Ubuntu ke andar ho – pehle `exit` likho.

## 📦 Step 5: Apps install karo
Ubuntu desktop par **"Install Apps"** icon par double-tap (pehli baar "Launch Anyway" dabao). Menu aayega:

1 VS Code · 2 Claude Code · 3 Ollama · 4 Blender · 5 LibreOffice · 6 GIMP · 7 Inkscape · 8 Python · 9 VLC

Ya terminal me: `sudo ubuntu-extras`

- **Claude Code:** terminal me `claude` likho, phir login.
- **Ollama:** `ai-chat` (model `qwen2.5:1.5b`) ya tez wala `ai-chat qwen2.5:0.5b`.
- **VS Code:** menu → Development, ya `vscode`.

## 🖱️ AirCursor ke saath
- AirCursor ka **Simple mode** ON rakho (cursor + pinch click).
- Termux:X11 me: **⋮ / Preferences → Input → Touchscreen input mode → "Direct touch"**, taaki AirCursor ka click wahi lage jahan cursor hai.
- Ubuntu use karte waqt AirCursor ka **PC Mode band** rakho (taskbar Ubuntu ke upar na aaye).
- Keyboard/mouse (OTG ya Bluetooth) lagao to Ubuntu me seedha kaam karte hain.

## 🧯 Problem?
| Problem | Hal |
|---|---|
| Desktop achanak band | Step 2 wali setting + battery unrestricted |
| Kaali screen / panel-dock gayab / X cursor | `curl -fsSL https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/ubuntu/fix.sh -o fix.sh && bash fix.sh` (purani settings backup me rehti hain) |
| Termux achanak band | Recent apps me Termux ko 🔒 Lock karo, Battery → background activity ON |
| `--gpu` se gadbad | Bina `--gpu` chalao |
| Setup beech me ruk gaya | Wahi command dobara chalao – jo ho chuka woh dobara nahi hoga, kuch delete nahi hoga |
| Storage full | Termux me `pkg clean`, Ubuntu me `sudo apt clean` |
