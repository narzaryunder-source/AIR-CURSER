# Air Drive – Pro ka 100 GB cloud drive (owner setup)

Air Drive har Pro user ko **100 GB private storage** deta hai. Files user ke PC mein hi
**encrypt** hoti hain, phir upload hoti hain. Isliye aap (owner) bhi kisi user ki files ke naam
ya content nahi dekh sakte, sirf kitni jagah use hui wo dikhta hai.

Kaise kaam karta hai:

```
AirCursor PC (rclone, encryption) ──upload──▶ Cloudflare R2 (aapka bucket)
            │                                      ▲
            └── Pro key ──▶ Worker (ye folder) ────┘  1 ghante ka password, sirf us user ke folder ka
```

- **Worker** sirf Pro key check karta hai, 40 seats ginta hai, aur har user ko
  1 ghante ka password deta hai jo sirf `u/<phone code>/` folder khol sakta hai.
- **100 GB bhar gaya** to password sirf download ka milta hai (upload band).
- **40 seats** ka matlab: jinka plan chal raha hai unme se 40 se zyada phone ek saath nahi jud sakte.
  Jiska plan khatam ho jaata hai, uski seat apne aap khaali ho jaati hai.

## Kharcha (Cloudflare R2, Nov 2026 ke rate – sign up se pehle khud check karo)

- Storage: pehle 10 GB free, uske baad lagbhag **$0.015 per GB per mahina**.
- Download ka koi charge nahi (R2 mein "egress" free hai).
- Agar 40 log × 100 GB poora bhar dein = 4,000 GB, to lagbhag $60 (≈ ₹5,000) per mahina.
  Shuru mein log kam bharte hain, isliye bill bhi kam aayega.
- 40 × ₹159 = ₹6,360 per mahina aamdani. Isliye rate badle to `QUOTA_GB` ya price soch-samajh ke rakhna.
- Workers aur KV free plan mein aa jaate hain (roz 1 lakh requests tak).

## Setup (ek baar, lagbhag 20 minute)

Saare sign-in **aap khud** apne browser mein karoge. Apna password kabhi kisi ko mat dena.

1. **Cloudflare account:** https://dash.cloudflare.com/sign-up par apne email se banao.
2. **R2 chalu karo:** dashboard → **R2 Object Storage** → *Purchase R2* / *Enable*.
   Card dena padta hai, kyunki 10 GB ke upar paise lagte hain.
3. **Bucket banao:** R2 → *Create bucket* → naam `aircursor-drive`, location Automatic.
4. **R2 API token banao:**
   - R2 → *Manage R2 API Tokens* → *Create API token*.
   - Permission: **Object Read & Write**, sirf `aircursor-drive` bucket.
   - Banne ke baad **Access Key ID** aur **Secret Access Key** kahin safe note karo.
     Secret sirf ek baar dikhta hai.
   - Isi page par aapka **Account ID** bhi dikhta hai.
5. **Worker deploy karo.** Do raaste hain, ek chuno.

   **(a) Computer se (aasaan, sab ek saath)** — Node.js chahiye:
   ```bash
   cd server/airdrive
   npx wrangler login
   npx wrangler kv namespace create USERS
   ```
   Teesri command jo `id` deti hai, use `wrangler.toml` ki `id = ""` mein daalo.
   Phir `wrangler.toml` mein ye bharo:
   - `SELLER_PUBLIC_KEY`: wahi jo `cloudpc/pro.json` mein `publicKey` hai.
   - `ACCOUNT_ID`
   - `PARENT_ACCESS_KEY_ID`

   Uske baad:
   ```bash
   npx wrangler secret put PARENT_SECRET_ACCESS_KEY
   ```
   Ye secret maangega, step 4 wala **Secret Access Key** paste karo. Phir:
   ```bash
   npx wrangler deploy
   ```

   **(b) Sirf browser se (phone par bhi chalega):**
   1. Workers & Pages → *Create* → *Hello World* Worker → naam `aircursor-airdrive` → Deploy.
   2. *Edit code* kholo, `worker.js` ka poora text paste karo, aur Deploy.
   3. Storage & Databases → KV → *Create namespace* → naam `USERS`.
   4. Worker → Settings → **Bindings** mein do binding jodo:
      - R2 bucket: naam `DRIVE`, bucket `aircursor-drive`.
      - KV namespace: naam `USERS`, namespace `USERS`.
   5. Worker → Settings → **Variables and Secrets** mein ye jodo:
      - Text: `SELLER_PUBLIC_KEY`, `ACCOUNT_ID`, `BUCKET` = `aircursor-drive`, `PARENT_ACCESS_KEY_ID`,
        `SEATS` = `40`, `QUOTA_GB` = `100`.
      - **Secret**: `PARENT_SECRET_ACCESS_KEY`.
6. **Check karo:** browser mein `https://aircursor-airdrive.<aapka-naam>.workers.dev/v1/seats` kholo.
   Jawab mein `{"total":40,"taken":0,"left":40}` aana chahiye.
7. **App ko batao:** GitHub par `cloudpc/pro.json` mein ye do value daalo, aur Commit karo:
   ```json
   "driveUrl": "https://aircursor-airdrive.<aapka-naam>.workers.dev",
   "driveGb": 100,
   "seatsTotal": 40
   ```
   Naya APK nahi chahiye. App agli baar khulte hi naya `pro.json` le leta hai.

## User ko kya dikhega

- Pro screen par: "☁️ Air Drive: 100 GB…" aur "👥 Sirf 40 log: N jagah baaki".
- PC mein **Air Drive** app hoga, jisme ye options hain:
  - Sync now (dono taraf)
  - Folder kholo
  - Storage used
  - Recovery key
  - Disconnect
- Pehli baar app ek **recovery key** dikhayega. User ko ye zaroor save karni hai, kyunki naye
  phone par files isi key se khulti hain. Aapke paas ye key nahi hoti, aur honi bhi nahi chahiye.

## Test

`node server/airdrive/test.mjs`: Worker ke offline checks chalata hai. Isme Cloudflare account
nahi chahiye. Ye licence check, seats, usage aur password ka format check karta hai.
