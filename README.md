# AirCursor 🖱️✋

Phone ko **bina touch kiye** chalao! AirCursor aapke phone par PC jaisa mouse pointer (cursor) dikhata hai.
Aap front camera ke saamne apni **index finger (tarjani ungli)** hilaoge, cursor waise hi move karega.
Pinch karke (anguutha + ungli milake) click hota hai.

> 🔒 **Privacy:** Saara hand tracking phone ke andar hi hota hai (MediaPipe, on-device). Koi photo ya data internet par nahi jaata.

---

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
| 🤏 Pinch pakad ke haath hilao | **Drag** (chhodne par release) |
| ✌️ Do ungli (index + middle) khadi, haath upar/neeche | **Smooth scroll** – cursor apni jagah **ruka rehta hai**, page chalta hai. Jitna door haath, utni tez scroll (PC ke auto-scroll jaisa). Haath wapas beech me lao to rukega |
| ✌️ Do ungli, haath left/right | **Horizontal scroll** (pages / photos) |
| ✋ Khula panja tezi se **left** swipe | **Back** |
| ✋ Khula panja **1 second** bilkul still | **Home** (hari ring bharegi) |
| ✋ Khula panja **neeche** swipe | **Notifications** kholo |
| ✋ Khula panja **upar** swipe | **Recent apps** |
| ✊ Mutthi 1.5s | **Pause / Resume** |

Har gesture ko Settings me alag se ON/OFF kar sakte ho. Gesture chalne par screen par 1 second ke liye chhota label aata hai ("Back", "Home", "Paused"...).

Click, long press aur gestures par phone halka sa **vibrate** karta hai, taaki bina chhue pata chale ki click hua (Settings me band kar sakte ho).

Cursor ke rang: ⚪ normal · 🟡 pinch daba hua · 🟠 drag · 🔵 scroll. Haath dikhna band ho to cursor wahin ruk jata hai aur 2 second baad halka (50%) ho jata hai.

---

## ⚙️ Settings (app me)

- **Active box margin** (default 15%): camera frame ka andar wala hissa poori screen se map hota hai, isliye haath ko kinaron tak le jaane ki zaroorat nahi.
- **Sensitivity:** zyada = kam haath hilana padega.
- **Smoothing (min cutoff) / Responsiveness (beta):** One Euro filter. Cursor kaanp raha ho to smoothing kam karo; late chal raha ho to beta badhao.
- **Jitter dead zone:** chhoti hilne ko ignore karta hai.
- **Pinch distance:** pinch kitna paas hone par count ho.
- **Scroll speed, Reverse scroll, Cursor size, Tracking FPS**
- **Debug overlay:** screen par FPS, pose, pinch value dikhata hai.
- **Use GPU:** zyada fast; problem ho to off karo.
- **Recalibrate:** apni finger aaram wali jagah rakho aur Recalibrate dabao – active box wahin center ho jayega.
- **Test: live camera preview:** camera, haath ka skeleton aur active box (peela) dikhata hai.

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
