/**
 * Air Drive – the 100 GB cloud drive of AirCursor PC Pro (Cloudflare Worker + R2).
 *
 * The Worker never stores or sees anyone's files. It only:
 *   1. checks the person's Pro licence key (the same CPC1-… key the app uses, ECDSA P-256),
 *   2. gives each phone one seat (at most SEATS phones with a running plan, e.g. 40),
 *   3. hands out short-lived R2 credentials that can only reach that phone's own folder
 *      (u/<phone code>/), read-only when the folder is full (QUOTA_GB, e.g. 100).
 * The PC uploads straight to R2 with rclone, encrypted with a key that only the PC knows –
 * the owner of the bucket sees file sizes, never names or contents.
 *
 * Endpoints:
 *   GET  /v1/seats     → {"total": 40, "taken": 12, "left": 28}
 *   POST /v1/session   (Authorization: Bearer CPC1-…) → credentials + usage
 *   GET  /v1/usage     (Authorization: Bearer CPC1-…) → {"usedBytes", "quotaBytes"}
 *
 * Settings (see README.md): vars SELLER_PUBLIC_KEY, ACCOUNT_ID, BUCKET, PARENT_ACCESS_KEY_ID,
 * SEATS, QUOTA_GB; secret PARENT_SECRET_ACCESS_KEY; bindings DRIVE (R2 bucket), USERS (KV).
 */

const DAY = 86400000;
const enc = new TextEncoder();

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
      if (url.pathname === "/" || url.pathname === "") {
        return cors(new Response("AirCursor Air Drive is running.\n", { headers: { "content-type": "text/plain" } }));
      }
      if (url.pathname === "/v1/seats" && request.method === "GET") {
        const taken = await countSeats(env);
        const total = seats(env);
        return json({ total, taken, left: Math.max(0, total - taken) });
      }
      if (url.pathname === "/v1/session" && request.method === "POST") return await session(request, env);
      if (url.pathname === "/v1/usage" && request.method === "GET") {
        const lic = await licence(request, env);
        if (lic.error) return json({ error: lic.error }, 401);
        const used = await usage(env, lic.code, true);
        return json({ usedBytes: used, quotaBytes: quota(env) });
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: "server", detail: String(e && e.message || e).slice(0, 200) }, 500);
    }
  },
};

// ---- settings -------------------------------------------------------------------------------

const seats = (env) => parseInt(env.SEATS || "40", 10);
const quota = (env) => Math.round(parseFloat(env.QUOTA_GB || "100") * 1024 ** 3);
const today = () => Math.floor(Date.now() / DAY);
const prefixOf = (code) => `u/${code}/`;

// ---- the licence key ------------------------------------------------------------------------

/** CPC1-<base64url payload>.<base64url DER signature>, payload "CODE|EXPIRY_DAY|PLAN". */
export async function verifyLicence(key, publicKeyB64, day) {
  const k = (key || "").replace(/\s+/g, "");
  if (!k.startsWith("CPC1-")) return { error: "malformed" };
  const parts = k.slice(5).split(".");
  if (parts.length !== 2) return { error: "malformed" };
  let payload, sig;
  try {
    payload = b64urlDecode(parts[0]);
    sig = derToRaw(b64urlDecode(parts[1]));
  } catch (_) {
    return { error: "malformed" };
  }
  if (!sig) return { error: "bad_signature" };
  let pub;
  try {
    pub = await crypto.subtle.importKey("spki", b64Decode(publicKeyB64), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  } catch (_) {
    return { error: "no_seller_key" };
  }
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pub, sig, payload);
  if (!ok) return { error: "bad_signature" };
  const f = new TextDecoder().decode(payload).split("|");
  const expiry = parseInt(f[1], 10);
  if (f.length !== 3 || !/^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(f[0]) || !Number.isFinite(expiry)) return { error: "malformed" };
  if (day > expiry) return { error: "expired", code: f[0], expiry };
  return { code: f[0], expiry, plan: f[2] };
}

async function licence(request, env) {
  const h = request.headers.get("authorization") || "";
  const key = h.toLowerCase().startsWith("bearer ") ? h.slice(7) : "";
  return verifyLicence(key, env.SELLER_PUBLIC_KEY || "", today());
}

// ---- seats ----------------------------------------------------------------------------------

async function countSeats(env) {
  let taken = 0;
  let cursor;
  const d = today();
  do {
    const page = await env.USERS.list({ prefix: "user:", cursor });
    for (const k of page.keys) {
      const exp = k.metadata && k.metadata.expiry;
      if (typeof exp === "number" ? exp >= d : true) taken++;
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return taken;
}

/** The phone's seat: kept while its plan runs; a new phone gets one only while seats are left. */
async function takeSeat(env, lic) {
  const key = "user:" + lic.code;
  const have = await env.USERS.get(key, { type: "json" });
  if (!have) {
    if ((await countSeats(env)) >= seats(env)) return false;
  }
  if (!have || have.expiry !== lic.expiry) {
    const rec = { expiry: lic.expiry, since: (have && have.since) || today() };
    await env.USERS.put(key, JSON.stringify(rec), { metadata: { expiry: lic.expiry } });
  }
  return true;
}

// ---- usage ----------------------------------------------------------------------------------

/** Bytes in the phone's folder (counted at most every 10 minutes, kept in KV). */
async function usage(env, code, fresh) {
  const key = "usage:" + code;
  if (!fresh) {
    const c = await env.USERS.get(key, { type: "json" });
    if (c && Date.now() - c.at < 10 * 60 * 1000) return c.bytes;
  }
  let bytes = 0;
  let cursor;
  let pages = 0;
  do {
    const page = await env.DRIVE.list({ prefix: prefixOf(code), cursor, limit: 1000 });
    for (const o of page.objects) bytes += o.size;
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor && ++pages < 200);
  await env.USERS.put(key, JSON.stringify({ bytes, at: Date.now() }));
  return bytes;
}

// ---- the session: scoped, short-lived R2 credentials ---------------------------------------

async function session(request, env) {
  const lic = await licence(request, env);
  if (lic.error) return json({ error: lic.error }, 401);
  if (!(await takeSeat(env, lic))) return json({ error: "seats_full", total: seats(env) }, 403);
  const used = await usage(env, lic.code, false);
  const q = quota(env);
  const readOnly = used >= q;
  const endpoint = `https://${env.ACCOUNT_ID}.r2.cloudflarestorage.com`;
  const ttl = 3600;
  const creds = await tempCredentials(env, endpoint, readOnly ? "object-read-only" : "object-read-write",
    [prefixOf(lic.code)], ttl);
  return json({
    endpoint,
    region: "auto",
    bucket: env.BUCKET,
    prefix: prefixOf(lic.code),
    ...creds,
    expiresAt: Date.now() + ttl * 1000,
    usedBytes: used,
    quotaBytes: q,
    readOnly,
    planDaysLeft: lic.expiry - today(),
  });
}

/** R2 temporary credentials, signed here (no API call): HS256 JWT with the parent secret. */
export async function tempCredentials(env, endpoint, scope, prefixes, ttl) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "HS256", typ: "JWT" };
  const claims = {
    bucket: env.BUCKET,
    scope,
    paths: { prefixPaths: prefixes, objectPaths: [] },
    sub: env.ACCOUNT_ID,
    iss: env.PARENT_ACCESS_KEY_ID,
    aud: new URL(endpoint).host,
    iat: now,
    exp: now + ttl,
  };
  const unsigned = b64urlEncode(enc.encode(JSON.stringify(header))) + "." + b64urlEncode(enc.encode(JSON.stringify(claims)));
  const hk = await crypto.subtle.importKey("raw", enc.encode(env.PARENT_SECRET_ACCESS_KEY), { name: "HMAC", hash: "SHA-256" },
    false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", hk, enc.encode(unsigned)));
  const jwt = unsigned + "." + b64urlEncode(sig);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(jwt)));
  return {
    accessKeyId: env.PARENT_ACCESS_KEY_ID,
    secretAccessKey: [...digest].map((b) => b.toString(16).padStart(2, "0")).join(""),
    sessionToken: btoa("jwt/" + jwt),
  };
}

// ---- helpers --------------------------------------------------------------------------------

function json(o, status = 200) {
  return cors(new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } }));
}

function cors(r) {
  r.headers.set("access-control-allow-origin", "*");
  r.headers.set("access-control-allow-headers", "authorization, content-type");
  r.headers.set("cache-control", "no-store");
  return r;
}

function b64Decode(s) {
  const bin = atob(s.replace(/\s+/g, ""));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function b64urlDecode(s) {
  const p = s.replace(/-/g, "+").replace(/_/g, "/");
  return b64Decode(p + "=".repeat((4 - (p.length % 4)) % 4));
}

function b64urlEncode(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Java's SHA256withECDSA gives DER (SEQUENCE of two INTEGERs); WebCrypto wants r‖s (64 bytes). */
export function derToRaw(der) {
  if (der.length < 8 || der[0] !== 0x30) return null;
  let i = 2;
  if (der[1] & 0x80) i = 2 + (der[1] & 0x7f);
  const out = new Uint8Array(64);
  for (let n = 0; n < 2; n++) {
    if (der[i] !== 0x02) return null;
    let len = der[i + 1];
    let start = i + 2;
    i = start + len;
    while (len > 32 && der[start] === 0) { start++; len--; }
    if (len > 32) return null;
    out.set(der.subarray(start, start + len), n * 32 + (32 - len));
  }
  return out;
}
