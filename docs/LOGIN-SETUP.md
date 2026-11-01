# Login chalu karna (Google + Phone OTP) – sirf phone se, ek baar

AirCursor ka login **Firebase** se chalta hai. Firebase Google ki free service hai.
Jab tak yeh setup nahi hota, app chalegi lekin login ki jagah sirf "naam likho → Continue" aayega.

Setup ke baad:
- Log **Google** se ya **phone number + OTP** se login karenge.
- Ek baar login karne ke baad wo phone par login hi rahenge, app update hone par bhi.
- Aap Firebase console me dekh paoge ki **kisne kab login kiya** (admin panel ka pehla step).

---

## Step 1: Firebase project banao
1. Phone browser me kholo: https://console.firebase.google.com (apne Google account se login).
2. **Create a project** dabao → naam `AirCursor` → Continue.
3. Google Analytics: **OFF** → **Create project**.

## Step 2: Android app jodo
1. Project ke home par **Android** icon (Add app) dabao.
2. **Android package name**: `com.aircursor`
3. **App nickname**: `AirCursor`
4. **Debug signing certificate SHA-1** me yeh daalo:
   ```
   71:E0:CE:F3:CA:D3:CB:E9:DE:F4:33:60:F7:F9:15:93:8B:F2:BC:3F
   ```
5. **Register app** dabao.
6. **Download google-services.json** dabao. File phone ke Downloads me aa jayegi.
7. Baaki steps skip karo (Next → Next → Continue to console).

## Step 3: SHA-256 bhi jodo (phone OTP ke liye zaroori)
1. ⚙️ **Project settings** → neeche **Your apps → AirCursor** → **Add fingerprint**.
2. Yeh paste karke Save karo:
   ```
   45:35:53:0C:FB:E8:67:AC:0A:A1:E1:9E:E9:23:05:34:9E:D9:63:6E:B1:9A:54:98:E0:53:F5:4E:66:D9:92:0F
   ```

## Step 4: Login methods ON karo
1. Left menu → **Build → Authentication** → **Get started**.
2. **Sign-in method** tab:
   - **Google** → Enable → support email chuno → **Save**.
   - **Phone** → Enable → **Save**.
3. **Phone OTP ki baat:**
   - Asli SMS ke liye Firebase **Blaze plan** (card jodna) maangta hai. India me ek SMS ka lagbhag ₹1–4 lagta hai.
   - Testing ke liye free tareeka: Phone → **Phone numbers for testing** me 10 tak number aur unka fixed code daal do, jaise `+91 9999999999` → `123456`. In numbers par SMS nahi jaata aur paisa nahi lagta.
   - Google login hamesha free hai. Testers ko pehle wahi bolo.

## Step 5: google-services.json GitHub par daalo
1. Phone browser me kholo: https://github.com/narzaryunder-source/AIR-CURSER/tree/main/app
2. **Add file → Upload files** dabao. Step 2 wali `google-services.json` chuno.
   - File ka naam bilkul `google-services.json` hi hona chahiye.
3. **Commit changes** dabao.
4. 5–10 minute me naya APK ban jayega (GitHub Actions). Use install karo. Ab login screen par **Continue with Google** aur **Continue with phone number** dikhenge.

## Kisne login kiya kaise dekhein
Firebase console → **Authentication → Users**. Yahan har user ka phone/email, kab account bana aur last login dikhta hai.
Subscription aur admin panel isi par aage banenge.

## Zaroori baatein
- `google-services.json` secret nahi hai, lekin repo **public** hai. Baad me Play Store ke liye apni private signing key banayenge aur uska SHA-1 yahan add karenge.
- Sign out karne se phone ka koi data delete nahi hota. AirCursor PC ki files phone me hi rehti hain.
