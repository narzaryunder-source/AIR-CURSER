// Offline test of the Air Drive Worker: licence check, seats, usage, credential format.
// Run: node server/airdrive/test.mjs   (Node 18+; no Cloudflare account needed)
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, createHmac, createHash } from "node:crypto";
import worker, { verifyLicence, derToRaw } from "./worker.js";

const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const PUB = publicKey.export({ type: "spki", format: "der" }).toString("base64");
const b64url = (b) => Buffer.from(b).toString("base64url");
const today = Math.floor(Date.now() / 86400000);

// The same key format as the app's License.issue (Java SHA256withECDSA = DER signature).
function issue(code, expiry, plan = "pro") {
  const payload = `${code}|${expiry}|${plan}`;
  const sig = sign("sha256", Buffer.from(payload), privateKey); // DER by default, like Java
  return "CPC1-" + b64url(payload) + "." + b64url(sig);
}

class KV {
  constructor() { this.m = new Map(); }
  async get(k, o) { const v = this.m.get(k); return v === undefined ? null : (o && o.type === "json" ? JSON.parse(v.value) : v.value); }
  async put(k, value, o) { this.m.set(k, { value, metadata: o && o.metadata }); }
  async list({ prefix }) {
    return { keys: [...this.m.entries()].filter(([k]) => k.startsWith(prefix)).map(([name, v]) => ({ name, metadata: v.metadata })), list_complete: true };
  }
}
class R2 {
  constructor(objs) { this.objs = objs; }
  async list({ prefix }) { return { objects: this.objs.filter((o) => o.key.startsWith(prefix)), truncated: false }; }
}

const env = {
  SELLER_PUBLIC_KEY: PUB, ACCOUNT_ID: "acc123", BUCKET: "aircursor-drive", PARENT_ACCESS_KEY_ID: "parentid",
  PARENT_SECRET_ACCESS_KEY: "parentsecret", SEATS: "2", QUOTA_GB: "0.000001",
  USERS: new KV(), DRIVE: new R2([{ key: "u/AAAA-BBBB/files/x", size: 500 }, { key: "u/CCCC-DDDD/files/y", size: 5000 }]),
};
const call = (path, method = "GET", key) =>
  worker.fetch(new Request("https://drive.example" + path, { method, headers: key ? { authorization: "Bearer " + key } : {} }), env);

// Licence checks.
const good = issue("AAAA-BBBB", today + 30);
assert.equal((await verifyLicence(good, PUB, today)).code, "AAAA-BBBB");
assert.equal((await verifyLicence(issue("AAAA-BBBB", today - 1), PUB, today)).error, "expired");
assert.equal((await verifyLicence(good.slice(0, -4) + "AAAA", PUB, today)).error, "bad_signature");
assert.equal((await verifyLicence("hello", PUB, today)).error, "malformed");
const other = generateKeyPairSync("ec", { namedCurve: "P-256" }).publicKey.export({ type: "spki", format: "der" }).toString("base64");
assert.equal((await verifyLicence(good, other, today)).error, "bad_signature");
assert.equal(derToRaw(new Uint8Array([1, 2, 3])), null);

// Session: credentials scoped to the phone's folder; usage under quota (1074 bytes) → read-write.
let r = await call("/v1/session", "POST", good);
assert.equal(r.status, 200);
let s = await r.json();
assert.equal(s.prefix, "u/AAAA-BBBB/");
assert.equal(s.bucket, "aircursor-drive");
assert.equal(s.endpoint, "https://acc123.r2.cloudflarestorage.com");
assert.equal(s.readOnly, false);
assert.equal(s.usedBytes, 500);
assert.equal(s.accessKeyId, "parentid");
// The session token is base64("jwt/" + JWT) signed with the parent secret; secret = sha256(jwt).
const jwt = Buffer.from(s.sessionToken, "base64").toString().replace(/^jwt\//, "");
const [h, p, sg] = jwt.split(".");
assert.equal(createHmac("sha256", "parentsecret").update(h + "." + p).digest("base64url"), sg);
assert.equal(createHash("sha256").update(jwt).digest("hex"), s.secretAccessKey);
const claims = JSON.parse(Buffer.from(p, "base64url").toString());
assert.deepEqual(claims.paths.prefixPaths, ["u/AAAA-BBBB/"]);
assert.equal(claims.scope, "object-read-write");
assert.equal(claims.aud, "acc123.r2.cloudflarestorage.com");
assert.equal(claims.iss, "parentid");
assert.equal(claims.sub, "acc123");

// A full folder → read-only.
r = await call("/v1/session", "POST", issue("CCCC-DDDD", today + 5));
s = await r.json();
assert.equal(s.readOnly, true);
assert.equal(JSON.parse(Buffer.from(Buffer.from(s.sessionToken, "base64").toString().slice(4).split(".")[1], "base64url")).scope, "object-read-only");

// Seats: 2 total, both taken → a third phone is refused; the same phone again is fine.
assert.deepEqual(await (await call("/v1/seats")).json(), { total: 2, taken: 2, left: 0 });
r = await call("/v1/session", "POST", issue("EEEE-FFFF", today + 30));
assert.equal(r.status, 403);
assert.equal((await r.json()).error, "seats_full");
assert.equal((await call("/v1/session", "POST", good)).status, 200);
// No key / bad key.
assert.equal((await call("/v1/session", "POST")).status, 401);
assert.equal((await call("/v1/usage", "GET", "CPC1-x.y")).status, 401);
assert.equal((await call("/nope")).status, 404);
console.log("air drive worker: all checks passed");
