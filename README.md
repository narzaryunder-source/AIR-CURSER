# AirCursor

**Touchless control for Android – plus a full PC on your phone.**

| App | What it does |
|---|---|
| **AirCursor** | Move a mouse pointer in the air with your index finger (front camera). Pinch to click, hold for long press / right click, hold and move to drag. |
| **AirCursor PC** | A full Ubuntu 24.04 desktop inside the app – Windows-style taskbar, App Store, Firefox, AI apps (Claude, ChatGPT, Gemini, offline AI), files between phone and PC, AirBoost for more FPS. No Termux needed. |

**Download:** https://github.com/narzaryunder-source/AIR-CURSER/releases/latest/download/AirCursor.apk

### First start (for testers)
1. Install the APK (allow "install unknown apps" for your browser if asked).
2. Sign in (Google or phone number) – once; you stay signed in.
3. Follow the setup: camera, accessibility, battery.
4. Home → **Hand control** on. Home → **AirCursor PC** → Install (about 450 MB, Wi-Fi) → Open desktop.
5. Help & guide in the app explains gestures, apps, AI, files and speed. The desktop has a **Getting Started** guide too.

Privacy: hand tracking runs only on the phone; camera images are never saved or sent. AirCursor PC and its files stay inside the app on the phone. Signing out never deletes data.

Owner docs: [Login setup](docs/LOGIN-SETUP.md) · [Business plan](docs/BUSINESS.md) · [Third-party notices](third_party/PROOT-NOTICE.md)

---

# AirCursor 🖱️✋

Phone ko **bina touch kiye** chalao! AirCursor aapke phone par PC jaisa mouse pointer (cursor) dikhata hai.
Aap front camera ke saamne apni **index finger (tarjani ungli)** hilaoge, cursor waise hi move karega.
Pinch karke (anguutha + ungli milake) click hota hai.

> 🔒 **Privacy:** Saara hand tracking phone ke andar hi hota hai (MediaPipe, on-device). Koi photo ya data internet par nahi jaata.

---

> ☀️ **Naya app:** BrightMax – screen ko safe maximum brightness + HDR boost – guide: [brightmax/README.md](brightmax/README.md)

> 🐧 **Naya:** phone par asli Ubuntu Linux desktop (VS Code, Claude Code, Ollama…) – guide: [ubuntu/README.md](ubuntu/README.md)

## 📥 Install kaise kare (sirf phone se)

1. Is page ke **Releases** section me jao (right side me ya neeche "Releases" link), ya seedha yeh link kholo:
   **https://github.com/narzaryunder-source/AIR-CURSER/releases/latest**
2. **Assets** ke neeche **`AirCursor.apk`** par tap karo → download ho jayega.
   (Direct link: `https://github.com/narzaryunder-source/AIR-CURSER/releases/latest/download/AirCursor.apk`)
3. Download complete hone par notification / Files app se `AirCursor.apk` kholo.
4. Agar "Install unknown apps" ka message aaye → **Settings** → browser/Files ke liye **Allow from this source** ON karo → wapas aake **Install** dabao.
5. Naya version aaye to bas naya APK install karo – purane ke upar update ho jayega (settings safe rahengi).

---

## ✅ Permissions (app khud step-by-step guide karega)

App pehli baar khologe to **Setup** screen aayegi. Har step me button hai:

| Step | Kya karna hai | Kyun |
|---|---|---|
| 1. Camera | **Allow** dabao | Haath dekhne ke liye front camera |
| 2. Notifications (Android 13+) | **Allow** | Pause / Recalibrate / Turn off buttons wali notification |
| 3. Restricted settings (Android 13+) | Neeche dekho 👇 | APK se install kiye app ka Accessibility switch grey hota hai |
| 4. Accessibility service | Button dabao → **AirCursor** → ON → Allow | Cursor dikhane aur tap/swipe/Back/Home karne ke liye |
| 5. Battery | **Allow** | Taaki Android background me camera service band na kare |

### ⚠️ "Restricted setting" / Accessibility switch grey hai? (Android 13+)
APK se install kiye apps ke liye Android yeh extra step maangta hai:
1. Pehle ek baar Accessibility settings me AirCursor ka switch ON karne ki koshish karo (grey/"Restricted" dikhega – koi baat nahi).
2. Ab **Settings → Apps → AirCursor** (App info) kholo – app me "Open App info" button bhi hai.
3. Upar right me **⋮ (teen dots)** dabao → **"Allow restricted settings"** → PIN/fingerprint daalo.
4. Wapas Accessibility me jao → AirCursor ON karo. Ho gaya! ✅

---

## ▶️ On / Off kaise kare

- **App me bada switch** (ON/OFF)
- **Quick Settings tile:** notification panel neeche kheecho → ✎ (edit) → "AirCursor" tile ko upar drag karo → ab ek tap me on/off
- **Notification buttons:** Pause/Resume, Recalibrate, Turn off
- **Hawa me:** ✊ mutthi (fist) **1.5 second** pakdo → Pause / Resume (cursor ke around laal ring bharegi)
- Screen off hone par camera apne aap ruk jata hai, unlock karne par phir chalu (Settings me band kar sakte ho)

> Android 14 rule: camera wali service sirf tab start ho sakti hai jab app screen par dikh rahi ho. Isliye tile dabane par ek pal ke liye invisible screen khulti hai – yeh normal hai.

---

## ✋ Gesture list

| Gesture (hawa me) | Kya hoga |
|---|---|
| ☝️ Haath / index finger hilao | Cursor move (**Mouse mode**: PC mouse jaisa – dheere hilao to precise, tez hilao to door tak; paas ho ya door, same feel) |
| 🤏 Jaldi se pinch (anguutha + index milao, chhodo) | **Tap / Click** |
| 🤏 Pinch karke 0.5s ruko (hilo mat) | **Long press** |
| 🤏 Pinch pakad ke haath hilao | **Drag** (chhodne par release). Drag ke beech ungliyan thodi khul jayein to bhi drag nahi tootega. Simple mode me bhi chalta hai |
| ☝️ Cursor 1.2s still rakho (**Dwell click** ON ho to) | **Tap bina pinch** – neeli ring bharegi |
| ✌️ Do ungli (index + middle) khadi, haath upar/neeche | **Smooth scroll** – cursor apni jagah **ruka rehta hai**, page chalta hai. Jitna door haath, utni tez scroll (PC ke auto-scroll jaisa). Haath wapas beech me lao to rukega |
| ✌️ Do ungli, haath left/right | **Horizontal scroll** (pages / photos) |
| ✋ Khula panja tezi se **left** swipe | **Back** |
| ✋ Khula panja **1 second** bilkul still | **Home** (hari ring bharegi) |
| ✋ Khula panja **neeche** swipe | **Notifications** kholo |
| ✋ Khula panja **upar** swipe | **Recent apps** |
| ✊ Mutthi **2s bilkul still** (setting ON ho to – default OFF) | **Pause / Resume**. Click/drag/scroll ke 0.8s baad tak koi pause/home nahi hota, aur hilti hui mutthi kabhi pause nahi karti |

Har gesture ko Settings me alag se ON/OFF kar sakte ho. Gesture chalne par screen par 1 second ke liye chhota label aata hai ("Back", "Home", "Paused"...).

Click, long press aur gestures par phone halka sa **vibrate** karta hai, taaki bina chhue pata chale ki click hua (Settings me band kar sakte ho).

Cursor ke rang: ⚪ normal · 🟡 pinch daba hua · 🟠 drag · 🔵 scroll. Haath dikhna band ho to cursor wahin ruk jata hai aur 2 second baad halka (50%) ho jata hai.

---

## ⚙️ Settings (app me)

- **Active box margin** (default 15%): camera frame ka andar wala hissa poori screen se map hota hai, isliye haath ko kinaron tak le jaane ki zaroorat nahi.
- **Sensitivity:** zyada = kam haath hilana padega.
- **Smoothing (min cutoff) / Responsiveness (beta):** One Euro filter. Cursor kaanp raha ho to smoothing kam karo; late chal raha ho to beta badhao.
- **Anti-shake:** chhoti hilne ko ignore karta hai. **🎯 Auto-tune** button dabao, ungli 2 second still rakho – app aapke haath ki kaanp naap ke sahi value khud set karega.
- **Swap left / right:** cursor ulta chale (haath right, cursor left) to ON karo.
- **Dwell click:** pinch ke bina click – cursor 1.2s still = click.
- **Click sound:** click par halki "beep".
- **Smart battery saver** (default ON): 10s tak haath na dikhe to camera kam check karta hai; haath aate hi turant full speed.
- **Pinch distance:** pinch kitna paas hone par count ho.
- **Scroll speed, Reverse scroll, Cursor size, Tracking FPS**
- **Debug overlay:** screen par FPS, pose, pinch value dikhata hai.
- **Use GPU:** zyada fast; problem ho to off karo.
- **Recalibrate:** apni finger aaram wali jagah rakho aur Recalibrate dabao – active box wahin center ho jayega.
- **Test: live camera preview:** camera, haath ka skeleton aur active box (peela) dikhata hai.

### 🖥️ PC Mode (kaam chalao PC)
Home screen → **"🖥️ Open PC Mode"** (ya phone ke app drawer me **"AirCursor PC"** icon). Phone ko landscape me rakho.
- **Desktop:** wallpaper, icons, neeche **taskbar** (Start, khule windows, pinned apps, battery, clock).
- **Start menu:** apps search, saare apps, Settings, Lock, **Exit PC mode**.
- **Windows:** Files, Notepad, Calculator, Paint asli khidkiyon me – title bar se khiskao, kone (◢) se chhota-bada, — ☐ ✕ buttons, title par double-tap = maximize.
- **Right-click / long-press:** desktop par (wallpaper, new note), app par (Open, Pin to taskbar, App info).
- **Doosre apps:** Start menu se kholo. "Open apps as windows" ON ho aur phone support kare (Developer options → *Enable freeform windows*) to chhoti khidki me khulenge, warna full screen.
- **Taskbar har app ke upar:** Start, Desktop, Back, Apps (Alt+Tab), Split screen, Notifications. **▾** dabake chhota karo, **▴ PC** se wapas.
- **Keyboard:** Win = Start menu · Alt+Tab = agla window/app · Alt+F4 = window band · Win+D = desktop · Win+←/→ = split screen · Win+L = lock · **Alt + Arrow keys = cursor chalao, Alt + Enter = click**.
- Keyboard shortcuts ke liye agar kaam na kare to ek baar Accessibility me AirCursor OFF → ON karo (naya keyboard permission).

**Data safety (phone ka data delete nahi hoga):**
- Files app sirf wahi folder dekhta hai jo aap chunte ho (koi "All files access" permission nahi).
- **Delete karne ka option hi nahi hai** – "Move to Recycle Bin" file ko `AirCursor Recycle Bin` folder me rakhta hai, wahan se **Restore**.
- Copy/Paste, Rename, New folder hamesha **naya naam** banate hain – koi file overwrite nahi hoti.
- Notepad ke notes sirf app ke andar save hote hain; Paint har baar **nayi** picture banata hai (Pictures/AirCursor).

### 🔒 Security: Owner hand lock + PIN
Home screen → **"🔒 Security"**:
1. **PIN banao** (4–8 digits). Iske baad AirCursor app kholne par PIN maanga jayega.
2. **Register my hand:** haath camera se 30–60 cm door, **khula panja**, ungliyan seedhi, achhi roshni. Progress 100% hone tak haath dikhate raho (thoda sa ghuma sakte ho).
3. **"Only my hand can control"** ON → ab doosre ka haath ignore hoga (screen par "🔒 Not the owner's hand" aayega).
4. **Live test:** kisi aur se haath dikhwao. Agar unka haath bhi "Owner" dikhe to **Strictness → Strict** karo.
- Haath dobara register karna, lock band karna, PIN badalna ya hatana – sab ke liye **PIN** chahiye.
- **PIN bhool gaye?** Lock screen par "Forgot PIN" → phone ka fingerprint / screen lock → PIN hat jayega, naya bana lo.
- ⚠️ Haath ki pehchaan haddiyon ke proportion se hoti hai – fingerprint jitni pakki nahi. Bilkul milta-julta haath pass ho sakta hai. Sab data sirf phone me rehta hai.

### ☁️ Cloud PC built-in (BETA – Termux nahi chahiye)
- Home screen → **"☁️ Cloud PC built-in"** → **Install** (Wi-Fi par, ~30 MB download + setup 2–5 min).
- Ubuntu 24.04 LTS AirCursor ke andar hi chalta hai (proot engine APK me hai). Har phone ka apna alag Cloud PC – files kisi se mix nahi hoti.
- **🖥 Desktop install karo** (XFCE + Firefox, ~400 MB) → **🖥 Desktop kholo**. Desktop AirCursor ke andar hi dikhta hai (Termux:X11 nahi chahiye).
- Desktop me **haath ka cursor = asli mouse**: hover, pinch = click, pinch hold = right click, pinch + move = drag, do ungli = scroll.
- Touch se: tap = click, daba ke rakho = right click, khiskao = drag, do ungli = scroll. Right side **☰** = keyboard, Esc, Tab, Ctrl, Alt, Win, arrows.
- Desktop ek private socket par chalta hai (koi network port nahi) – phone ka koi doosra app use dekh/chala nahi sakta.
- **Windows jaisa look** (pehli baar apne aap): neeche taskbar + **Start** button, pinned apps, dark theme, Win key = Start.
- **App Store** (desktop icon): apps tick karo → Install. **AI Apps** folder: Claude, ChatGPT, Gemini, Offline AI (Ollama).
- **🚀 AirBoost** (Cloud PC screen par): Performance / Balanced / Quality – kam pixels par chalakar FSR-style sharpening se saaf dikhata hai (DLSS jaisa idea), light mode background kaam band karta hai.
- Desktop toolbar (☰): ⌨ keyboard · ✋ haath ka cursor ON/OFF · 📋 phone ka copy kiya text paste · 🖼 phone ki photo wallpaper · ⚡ turant boost. Desktop me copy kiya text apne aap phone clipboard me aata hai.
- Install, **Info**, **Update**, command chalao (jaise `apt-get install -y htop`).
- **Hatao** button sirf Cloud PC ki apni files hatata hai – phone ki photos/files ko kabhi nahi chhuta.

### ☁️ Cloud PC (Termux wala, ek tap)
- Phone ki home screen par **"Cloud PC"** icon, notification shade me **"Cloud PC" tile**, ya app me Cloud PC card → **▶ Start**.
- Kuch kharab ho / update chahiye → app me **🛠 Repair / Update** – background me chalta hai, **Termux me kuch type nahi karna** (Termux session limit ka jhanjhat khatam).
- Cloud PC aapke phone par hi chalta hai (Termux + Ubuntu). Files phone me rehti hain.

### ⭐ Cloud PC Pro (₹159/month) aur updates
- App me **"⭐ Cloud PC Pro"**: plan, UPI se payment, license key. Hand cursor **hamesha free**.
- Naya version aaye to Home screen par **"🆕 Naya version"** card aur Download button.
- Business / paise kamane ka poora plan: [docs/BUSINESS.md](docs/BUSINESS.md).

### 🎯 Simple mode (default ON)
- **Cursor + pinch se click + pinch-move se drag**. Scroll, palm, fist wale gestures band – isliye koi pose cursor ko rokta nahi, cursor hamesha haath ke saath chalta hai.
- Baaki gestures chahiye to Home screen par "Simple mode" OFF karo.
- Pause/Turn off: notification buttons ya Quick Settings tile se.

### 🎨 Camera image (stripes fix)
- Kuch phones camera ka colour galat bhejte hain → preview me hari/gulabi lakeerein. **Auto** mode shuru ke frames check karta hai aur lakeerein mile to **black & white** image use karta hai (hand tracking ke liye kaafi, aur tez bhi).
- Settings → Performance → Camera image: Auto / Colour / Black & white.

### 🖱️ Mouse mode (default ON)
- Cursor PC mouse ki tarah chalta hai: jitna haath hilaoge utna cursor (haath ke size ke hisaab se), isliye **camera se paas ho ya door – same feel**.
- **Acceleration:** dheere = precise (chhote buttons ke liye), tez = poori screen jaldi paar.
- Cursor ungli ki jagah **knuckles** (ungli ki jad) se chalta hai – pinch karte waqt cursor nahi khisakta, click ekdum sahi jagah.
- **Anti-shake:** haath ki halki kaanp ignore hoti hai, cursor bilkul still rehta hai.
- Cursor screen ke kinare tak pahunch jaye aur haath aur aage jaye to cursor kinare par ruk jata hai (mouse jaisa). Wapas aane par turant chalta hai.
- Off karoge to purana "yellow box" mode (camera box = screen).

### ⚡ Smoothness / FPS (kaise kaam karta hai)
- **Cursor screen ke refresh rate par chalta hai (60 / 90 / 120 Hz).** Camera frames ke beech me cursor ki position *predict* aur *interpolate* hoti hai, isliye 30 fps camera par bhi cursor smooth dikhta hai.
- **Lag compensation (prediction)** slider: zyada = cursor finger ke saath aur tez chalega (lag kam), lekin rukte waqt thoda aage nikal sakta hai. Default 60%.
- **Camera tracking FPS** phone ke front camera par depend karta hai: zyada tar phones **30 fps** dete hain. "Stable 30 fps camera" (default ON) camera ko andar ki roshni me 15 fps tak girne se rokta hai. "60 fps camera" experimental hai – kuch phones par preview me **rang-birangi lakeerein** aati hain, aisa ho to use OFF rakho. 100 fps camera tracking phones par possible nahi hai – isliye smoothness prediction se aati hai.
- **Processor: Auto** – pehli baar haath dikhane par app GPU aur CPU dono test karta hai (kuch second) aur jo tez ho wahi use karta hai. Chaaho to GPU/CPU khud chuno.
- **Camera resolution:** Low = sabse fast, High = door se haath better pakadta hai.
- **Debug overlay** ON karo: screen par Tracking fps, Camera fps, Inference ms aur Render fps dikhega.
- Scroll ke baad ya click ke baad cursor **jump nahi karta**, dheere se wapas finger par aata hai.

### Tips
- Phone ko stand par rakho, chehra/haath camera se 30–60 cm door.
- Achhi roshni me best kaam karta hai.
- Ek hi haath camera me rakho.

---

## 🛠️ Developers ke liye

- Kotlin, minSdk 26, targetSdk 34, Jetpack Compose, CameraX (640×480, KEEP_ONLY_LATEST), MediaPipe HandLandmarker (LIVE_STREAM).
- `hand_landmarker.task` model build ke time Gradle task `downloadHandModel` se `app/src/main/assets/` me download hota hai.
- Build: `./gradlew assembleDebug` · Tests: `./gradlew testDebugUnitTest`
- Gesture logic (`app/src/main/java/com/aircursor/core/`) pure Kotlin hai aur unit tested hai: One Euro filter, pinch hysteresis, coordinate mapping, gesture engine.
- Har `main` push par GitHub Actions APK banata hai, artifact upload karta hai aur **Release** (`v1`, `v2`, ...) me `AirCursor.apk` daalta hai.

| File | Kaam |
|---|---|
| `service/HandTrackingService.kt` | Foreground camera service: CameraX + MediaPipe + gesture engine |
| `service/CursorAccessibilityService.kt` | Cursor overlay + dispatchGesture / performGlobalAction |
| `service/CursorView.kt` | Arrow cursor, ripple, progress ring, label, FPS |
| `service/AirCursorTileService.kt` | Quick Settings tile |
| `core/GestureEngine.kt` | Saare air gestures ki state machine |
