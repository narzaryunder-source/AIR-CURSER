# 💰 Cloud PC: duniya tak kaise pahunchaye aur paise kaise kamaye

Yeh plan sirf phone se chalaya ja sakta hai. Laptop, company ya website ki zaroorat nahi.

---

## 1. Kya bech rahe ho

| Product | Price | Kya milta hai |
|---|---|---|
| **AirCursor** (hand cursor) | **FREE** hamesha | Hawa me ungli se phone chalana: click, drag, scroll, dwell click, auto-tune |
| **Cloud PC Pro** | **₹159 / month** ya **₹999 / year** | Cloud PC one-tap start (icon, quick tile, app), PC Mode, WhatsApp support |
| Free trial | 7 din | Pro switch ON hone ke baad har naye phone ko |

**Hand cursor free kyun?** Yeh accessibility (divyang / haath se touch na kar paane wale logon ki madad) feature hai. Free rakhne se:
- log app zyada install karenge,
- Play Store policy aasaan rahegi,
- aur naam achha banega.

Paise Cloud PC se aayenge.

> ⚠️ **Imaandari zaroori hai.** "Cloud PC" aapke phone par hi chalta hai. Woh internet par kisi server par nahi chalta.
> Ads aur videos me bolo **"Apne phone ko PC banao"**. Yeh kabhi mat bolo:
> - "cloud server"
> - "free RAM"
> - "Windows"
>
> Galat promise karoge to refund maangenge aur complaint hogi.

---

## 2. Aaj hi shuru karo: 6 steps (sirf phone se)

### Step 1: Naya AirCursor install karo
Link: https://github.com/narzaryunder-source/AIR-CURSER/releases/latest/download/AirCursor.apk

### Step 2: Apni "seller key" banao (sirf ek baar)
1. AirCursor kholo, phir **⭐ Cloud PC Pro** par jao.
2. Upar **"⭐ Cloud PC Pro"** likha hai. Us title par **7 baar tap** karo. **Owner tools** khul jayega.
3. **Create seller key** dabao.
4. **Copy BACKUP** dabao. Ise apne Google Drive, Google Keep ya kisi private note me paste karke save karo.
   - 🔒 Yeh BACKUP **kisi ko mat dena**. Jiske paas yeh hoga, woh keys bana sakta hai.
   - Phone badla ya app uninstall hua? Owner tools kholo, **Restore backup** me paste karo.

### Step 3: Public key copy karo
Owner tools me **Copy public key** dabao. Yeh secret nahi hai.

### Step 4: GitHub par plan ON karo (browser se)
1. Phone browser me kholo: https://github.com/narzaryunder-source/AIR-CURSER/blob/main/cloudpc/pro.json
2. ✏️ (Edit) dabao. Values aise bharo:
   ```json
   {
     "enabled": true,
     "priceMonthly": 159,
     "priceYearly": 999,
     "trialDays": 7,
     "upiId": "aapka-upi@okaxis",
     "payeeName": "Aapka Naam",
     "whatsapp": "919876543210",
     "publicKey": "Step 3 wali key yahan paste"
   }
   ```
   - `whatsapp`: country code 91 ke saath, bina + aur spaces ke.
3. **Commit changes** dabao. Naya APK banane ki zaroorat nahi; apps khud naya plan padh lenge (internet ho to).
4. Price badalna ho ya Pro band karna ho? Isi file me edit karo (`"enabled": false` = sab free).

### Step 5: Khud test karo
1. AirCursor band karke dobara kholo, phir Cloud PC Pro par jao. **FREE TRIAL – 7 din** dikhna chahiye.
2. Apna phone code copy karo, jaise `K7QM-X29D`.
3. Owner tools me woh code daalo, **30 din** dabao, phir **Copy key** dabao.
4. Upar license box me paste karke **Activate** dabao. **PRO ACTIVE** dikhega. ✅

### Step 6: Roz ka selling flow (har customer ke liye 1 minute)
1. Customer app me **₹159** dabata hai. GPay, PhonePe ya Paytm khulta hai aur paisa seedha aapke UPI me aata hai.
2. Customer **WhatsApp par bhejo** dabata hai. Aapko screenshot aur uska phone code milta hai.
3. Apne UPI app me check karo ki paisa sach me aaya ya nahi (screenshot fake ho sakta hai).
4. Owner tools me customer ka code daalo, **30 din** ya **365 din** dabao, phir **Share** karke WhatsApp par key bhejo.
5. Customer key paste karke **Activate** karta hai. Ho gaya.

Har key sirf usi phone par chalti hai jiska code tha. Expiry wale din ke baad key band ho jaati hai, phir naya plan lena padta hai.

---

## 3. Duniya ke saamne kaise laaye (marketing)

**Videos sabse bada hathiyar hain. Roz 1 short video daalo.**

| Jagah | Kya daalna hai |
|---|---|
| YouTube Shorts, Instagram Reels | 15–30 sec: "Phone ko bina chhue chalao ✋" / "₹159 me phone ko PC banao" / Cloud PC me Luanti game / VS Code |
| YouTube (lamba video) | "Phone ko PC kaise banaye – poora setup Hindi me" (10 min) |
| WhatsApp Channel / Telegram group | Updates, tips, support. Har video me link do |
| Reddit (r/termux, r/androidapps), XDA forum | English post + demo video (yahan tech log hain) |
| Divyang / accessibility NGOs, special schools | Free hand cursor dikhao. Achhi khabar, log share karte hain |
| College students | "Laptop nahi? Phone par coding / LibreOffice" |

**Video ideas jo viral hote hain:**
- Before/after
- "Main ne bina laptop ke app banaya"
- Phone par Minecraft jaisa game (Luanti)
- Haath hila ke Instagram scroll karna

**Har video ke end me:** "Link bio me – 7 din free".

**Launch offer (pehle 100 log):** pehla mahina ₹99. GitHub par `priceMonthly` 99 karo, baad me 159 wapas.

---

## 4. Kitna kama sakte ho (seedha hisaab)

| Paying users | Monthly kamaai |
|---|---|
| 50 | ₹7,950 |
| 100 | ₹15,900 |
| 500 | ₹79,500 |
| 1,000 | ₹1,59,000 |

Aam taur par free users me se **2–5%** paise dete hain. Matlab 100 paying users ke liye lagbhag **3,000–5,000 installs** chahiye. Isliye videos zaroori hain.

---

## 5. Play Store (baad me, jab 50+ users ho jayein)

- **Developer account:** $25 ek baar (lagbhag ₹2,100), ID verification ke saath.
  - Naye personal account ko pehle **12 testers × 14 din** closed testing karni padti hai.
- **Accessibility policy:** AirCursor sach me accessibility tool hai (bina touch phone chalana). Form me yeh sab dena padega:
  - saaf explanation,
  - app ke andar disclosure,
  - demo video.
- **Payments:** Play Store se diye gaye app me digital features ke liye **Google Play Billing** zaroori hai (subscription par ~15% fee).
  - UPI wala direct tareeka sirf GitHub / website wale APK ke liye hai.
  - Isliye: Play version = Play Billing (code change, baad me karenge), GitHub version = UPI.
- **Termux** Play Store wala purana hai. Cloud PC ke liye Termux hamesha **F-Droid / GitHub** se.

---

## 6. Legal aur tax (simple)

- **GST:** ₹20 lakh/saal se kam turnover par services ke liye aam taur par GST registration zaroori nahi. Is limit ke paas pahuncho to kisi **CA** se baat karo.
- **Income tax:** yeh kamaai apni ITR me dikhao.
- **Udyam registration:** free hai (udyamregistration.gov.in) aur baad me bank / Razorpay me kaam aata hai.
- **Refund policy** likh do (WhatsApp channel / bio me), jaise "3 din me kaam na kare to poora refund". Bharosa badhta hai.
- **Open source:** Ubuntu, XFCE, Termux, Firefox free software hain. Aap Ubuntu nahi bech rahe; aap apni app, one-tap setup aur support ka paisa le rahe ho. Yeh theek hai.
  - Microsoft / Windows ka naam ya logo use mat karo.
  - Ubuntu ka logo apne brand ki tarah mat lagao. "Cloud PC (runs Ubuntu)" likhna theek hai.

---

## 7. Aage badhne par (automatic payments)

Jab roz 5+ customers aane lagein, manual key dena thakaa dega. Tab yeh karo:
1. **Razorpay** account banao (PAN + bank, individual bhi chalega). Subscription / UPI AutoPay link milta hai.
2. Payment par key khud bane, iske liye ek chhota server (Firebase / Cloudflare Worker) lagega. Yeh hum agla step bana sakte hain.
3. Play Store version me Play Billing.

---

## 8. Suraksha (zaroori)

- **Seller BACKUP leak ho gaya?**
  1. Owner tools me naya seller key banao. Pehle app data clear karo, phir **Create seller key** dabao.
  2. GitHub `pro.json` me nayi `publicKey` daalo.
  3. Purani saari keys band ho jayengi. Paying customers ko nayi key bhejo.
- Koi aur apne phone par Owner tools khol bhi le, to bhi nuksaan nahi. Uski banayi keys aapki public key se match nahi karti, isliye kaam nahi karengi.
- Har app ko crack kiya ja sakta hai. ₹159 ke plan ke liye yeh system kaafi hai. Asli taakat aapka support aur updates hain.

---

## 9. Support FAQ (customers ko copy-paste karke bhejo)

- **Termux "session limit" / naya session nahi banta:**
  1. Notification shade kholo.
  2. Termux notification par **Exit** dabao.
  3. Termux dobara kholo.
- **Cloud PC kharab ho gaya / black screen:** AirCursor → Cloud PC → **🛠 Repair / Update**. 5 minute ruko, phir Cloud PC icon dabao.
- **Cursor kaanpta hai:** AirCursor → **🎯 Auto-tune** → ungli 2 second still.
- **Cursor ulta chalta hai:** Settings → **Swap left / right** ON karo.
- **Pinch se click mushkil hai:** Settings → **Dwell click** ON karo (cursor 1.2s still rakhne se click).
