# BrightMax ☀️

Native Android app (Kotlin + Jetpack Compose, minSdk 26, targetSdk 35) that pushes the screen to the
highest brightness the phone **officially** allows — and, on Android 14+, unlocks the display's extra
**HDR headroom** — with safety checks that cannot be skipped.

> Only official Android APIs are used. No root, no kernel or hardware access. Everything BrightMax changes is
> put back when the boost ends.

---

## Hindi / Hinglish quick start

1. APK install karo (neeche "Install" dekho).
2. App kholo → **disclaimer** padho → **"I understand / मैं समझ गया"**.
3. **"Allow changing system brightness"** card par **Grant** → *Allow modifying system settings* ON → wapas aao.
4. Bada gola button dabao → **Boost ON**. Slider se 50–100 % chuno, ya **MAX**.
5. Doosre apps me bhi on/off ke liye: **Floating button** switch ON (*Display over other apps* allow karo) aur
   Quick Settings me **BrightMax** tile add karo.
6. Garam hone par (40 °C) boost 20 % kam, 43 °C par band. Battery 15 % se kam, screen off, ya timer khatam → band.

Phone ki language Hindi ho to app Hindi me dikhega. Android 13+ par sirf app ki language bhi badal sakte ho:
*Settings → Apps → BrightMax → Language*.

---

## Features

| Feature | How it works (public API) |
|---|---|
| **Max brightness** | `WindowManager.LayoutParams.screenBrightness` on the app window and the floating button's window, plus `Settings.System.SCREEN_BRIGHTNESS` (0–255) with adaptive brightness switched to manual. The original brightness **and** auto-brightness mode are saved to disk first and restored afterwards. |
| **HDR Boost** (Android 14+) | `window.colorMode = ActivityInfo.COLOR_MODE_HDR`; on Android 15+ also `setDesiredHdrHeadroom()` at the API maximum (10000, the system clamps it to the panel's real limit). A 1-pixel Ultra-HDR (gain-mapped) image is kept on screen so real HDR content is present. Checks `Display.isHdr()`, `Display.getHdrCapabilities()`, supported HDR types and `isHdrSdrRatioAvailable()` first; if the display has no HDR, the app says so and uses max brightness only. |
| **Works over other apps** | Foreground service (`specialUse` type) with a persistent notification (**OFF** action, live countdown), a draggable floating ON/OFF button, and a **Quick Settings tile**. |
| **Level slider** | 50 %–100 % in 5 % steps, plus a **MAX** button. Changes apply live while boosting. |
| **Live status card** | Current level, HDR supported (and types), reported peak nits, live HDR headroom (×), battery temperature, battery %, time left. |
| **English + Hindi** | `values/` and `values-hi/`, with per-app language support (`locales_config.xml`). |

### Safety (always on)

| Rule | Behaviour |
|---|---|
| Battery ≥ 40 °C | Boost reduced by 20 % (level × 0.8) **and HDR paused**. Back to full only after cooling below 38 °C (hysteresis, so it doesn't flicker). Notification + toast. |
| Battery ≥ 43 °C | Boost turned **off**, user notified. Can't be restarted until it cools. |
| Android thermal status | Extra guard via `PowerManager` thermal API (Android 10+): *severe* → reduce (same as 40 °C), *critical* or worse → off. Many phones report *moderate/severe* just from charging or a warm room, so those never block the boost; the live value is shown in the status card. |
| Battery < 15 % | Boost off (and can't start). |
| Screen turns off | Boost off. |
| Auto-off timer | 5 / 10 / 15 / 30 / 60 min, **default 10**. Warning notification + red ring on the button 1 minute before the end. |
| One-time disclaimer | Must be accepted before the first boost (heat + battery drain warning). |
| Restore original brightness | On boost off, service stop (`onDestroy`), notification **Close**, crash (uncaught-exception handler), **shutdown** (`ShutdownReceiver`, manifest + dynamic), and — if the process was killed outright (force-stop / low memory) — on the next process start and on **boot** (`BootReceiver`). |

### What "HDR Boost" really does (honest limits)

Android's HDR mode lets the panel go **above its normal maximum** (the extra "nits" used for HDR video and
Ultra-HDR photos). Android keeps normal (SDR) white at the same level and spends the extra headroom on HDR
pixels. In practice:

* The **"HDR test"** square in the HDR card is HDR content — on a supporting phone it looks clearly brighter
  than the plain white square next to it. The **HDR headroom now** row shows the live ratio (e.g. 1.00× → 2.5×).
* How much of the rest of the screen benefits depends on the phone maker's display driver; some phones raise the
  whole panel, most only the HDR parts.
* Below Android 14, HDR Boost is disabled with a clear message and BrightMax uses max brightness only.
* If the display reports no HDR (BrightMax checks `isHdr()`, HDR capabilities, every display mode's HDR types,
  `isHdrSdrRatioAvailable()` and `Configuration.isScreenHdr()`), it says so and uses max brightness only, but
  offers **"Try HDR anyway"**. HDR mode is harmless on such a panel; if *HDR headroom now* stays at 1.00× or
  *Not reported* while boosting, that phone simply does not give apps extra HDR brightness.

---

## Project layout

```
brightmax/
├── app/src/main/java/com/brightmax/
│   ├── BrightMaxApp.kt            # restores leftover brightness on process start, crash handler, channels
│   ├── brightness/SystemBrightness.kt   # Settings.System write + persisted backup/restore
│   ├── hdr/HdrController.kt       # HDR capability checks, window/LayoutParams HDR, gain-mapped HDR bitmap
│   ├── safety/SafetyPolicy.kt     # pure, unit-tested safety rules (temps, battery, timer, levels)
│   ├── safety/BatteryMonitor.kt   # battery temperature/level + thermal status as Flows
│   ├── data/AppPrefs.kt           # settings as StateFlow (SharedPreferences)
│   ├── service/BoostService.kt    # foreground service: boost, timer, safety, overlay, notification
│   ├── service/BoostRepository.kt # shared StateFlow<BoostState>
│   ├── service/FloatingOverlay.kt # draggable ON/OFF button (+ window brightness, HDR, 1-px HDR image)
│   ├── service/BoostTileService.kt# Quick Settings tile
│   ├── service/Notifier.kt        # channels, ongoing notification, alerts
│   ├── receiver/Receivers.kt      # ShutdownReceiver, BootReceiver
│   └── ui/                        # MainActivity, MainViewModel (StateFlow), Compose screen, dark theme
└── app/src/test/…/SafetyPolicyTest.kt
```

Architecture: **UI (Compose) → MainViewModel (StateFlow<UiState>) → BoostService / AppPrefs**; the service
publishes `BoostState` through `BoostRepository`, which the UI, the floating button and the tile all observe.

---

## Build

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
cd brightmax
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or set ANDROID_HOME
./gradlew testDebugUnitTest        # safety-rule unit tests
./gradlew lintDebug                # Android Lint (fails on errors)
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
```

Or open the `brightmax/` folder in Android Studio.

CI: `.github/workflows/brightmax.yml` runs tests, lint and `assembleDebug` for every change under `brightmax/`,
uploads the APK as a build artifact, and on `main` publishes a `brightmax-vN` release with `BrightMax.apk`
(it is **not** marked "latest", so the AirCursor download link is unchanged).

## Install

* `adb install -r app/build/outputs/apk/debug/app-debug.apk`, **or**
* copy the APK to the phone and open it (allow *Install unknown apps* for your file manager/browser).

The debug key is committed (`app/debug.keystore`) so newer builds install over older ones.

---

## Permissions & first run

| Permission | Why | How it's asked |
|---|---|---|
| Modify system settings (`WRITE_SETTINGS`) | **Required** to set system brightness and restore it. | Red card + explanation dialog → opens *Modify system settings* for BrightMax. |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Optional, for the floating button. | Explanation dialog when you turn on *Floating button*. |
| Notifications (Android 13+) | Recommended: OFF button and safety alerts. Without it, alerts still show as toasts. | Asked right after the disclaimer; card with *Allow* / *Open settings* if denied. |
| Foreground service (special use) | Keeps the boost and safety checks running while other apps are open. | Install-time. |

Add the tile: pull down Quick Settings → ✎ edit → drag **BrightMax** into the active tiles.

---

## Test checklist (on a real phone, Android 14 recommended)

Useful commands while connected with `adb`:

```bash
adb shell settings get system screen_brightness        # 255 while boosted at 100 %
adb shell settings get system screen_brightness_mode   # 0 while boosted (manual)
adb shell dumpsys battery set temp 405                 # fake 40.5 °C  (tenths of °C)
adb shell dumpsys battery set temp 432                 # fake 43.2 °C
adb shell dumpsys battery set level 14                 # fake 14 % battery
adb shell dumpsys battery reset                        # back to real values
adb shell cmd thermalservice override-status 3         # fake "severe" thermal status (Android 10+)
adb shell cmd thermalservice reset
```

**First run & permissions**
- [ ] Fresh install → disclaimer appears and cannot be dismissed by tapping outside; after accepting it never appears again.
- [ ] Notification permission is asked after the disclaimer (Android 13+).
- [ ] Without *Modify system settings*, tapping the big button opens the explanation → settings; after granting and returning, the red card disappears.
- [ ] Turning on *Floating button* without the overlay permission shows the explanation → settings.

**Boost**
- [ ] Note your brightness and auto-brightness setting. Tap the big button → screen goes to max, `screen_brightness` = 255, mode = 0.
- [ ] Slider to 50 % → value 128; **MAX** → back to 255 (while boosting, changes apply when you release the slider).
- [ ] Tap again → **original brightness and auto-brightness mode come back exactly**.
- [ ] Status card: level, HDR yes/no, battery temperature and % update live.

**HDR (Android 14+, HDR display)**
- [ ] HDR card says "Supported"; during boost it says "Active", the *HDR headroom now* value rises above 1.00×, and the HDR test square looks brighter than the white square.
- [ ] HDR switch off → boost continues without HDR. On a phone that reports no HDR the card says so and the switch becomes **Try HDR anyway**.

**Over other apps**
- [ ] Boost on → open another app: brightness stays max; the floating button shows the level and countdown; drag it around.
- [ ] Tap the floating button → boost off (button stays, notification says "Boost OFF" with ON / Close). Tap again → on.
- [ ] Long-press the floating button → BrightMax opens.
- [ ] Notification **OFF** → boost off and brightness restored. **Close** → everything gone.
- [ ] Quick Settings tile toggles on/off and shows the level as subtitle; from the lock screen it asks to unlock first.

**Safety**
- [ ] `dumpsys battery set temp 405` while boosting → level drops 20 % (255 → 204), HDR pauses, "Phone is warm" alert. `set temp 370` → back to full.
- [ ] `set temp 432` → boost off, alert "Battery too hot"; tapping ON is refused with the same message.
- [ ] `set level 14` → boost off ("Battery below 15 %"); can't start again until above 15 %.
- [ ] `cmd thermalservice override-status 3` (severe) → boost reduced 20 %, still on. `override-status 4` (critical) → boost off.
- [ ] Press the power button while boosted → boost off; after unlocking, the original brightness is back.
- [ ] Set the timer to 5 min → warning notification + red ring at 1:00 left → boost off at 0:00.

**Restore in bad situations**
- [ ] Boosted → `adb shell am force-stop com.brightmax` (no callbacks run) → open the app again → original brightness restored instantly.
- [ ] If the system kills the process while boosted, it restarts the service, which restores the original brightness and does **not** boost again.
- [ ] Boosted → reboot the phone → after boot, original brightness is back.
- [ ] Revoke *Modify system settings* while idle → app shows the red card again; boost can't start.

**Language & UI**
- [ ] Phone language Hindi (or per-app language Hindi on Android 13+) → all text in Hindi.
- [ ] Dark UI everywhere; TalkBack reads the big toggle and floating button state.
