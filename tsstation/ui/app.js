/* TS Station – interface logic. Keyboard, mouse, touch and the AirCursor hand cursor all work. */
"use strict";

const KEY = new URLSearchParams(location.hash.slice(1)).get("k") || "";
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const PAGES = ["home", "library", "search", "performance", "themes", "settings"];

const state = { games: [], settings: {}, page: "home", shelf: "all", focus: 0, inHero: false, art: {}, colors: {} };

async function api(path, body) {
  const opt = body === undefined ? {} : { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json" } };
  opt.headers = Object.assign(opt.headers || {}, { "X-TS-Key": KEY });
  const r = await fetch(path, opt);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}
const iconUrl = (id) => `/api/icon/${encodeURIComponent(id)}?k=${KEY}`;
const artUrl = (id) => `/api/art/${encodeURIComponent(id)}?k=${KEY}`;

/* ---------- sounds (soft, short, can be turned off) ---------- */
let audio;
function blip(freq = 660, dur = 0.05, vol = 0.04) {
  if (!state.settings.sounds) return;
  try {
    audio = audio || new AudioContext();
    const o = audio.createOscillator(), g = audio.createGain();
    o.type = "sine"; o.frequency.value = freq;
    g.gain.setValueAtTime(vol, audio.currentTime);
    g.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + dur);
    o.connect(g).connect(audio.destination); o.start(); o.stop(audio.currentTime + dur);
  } catch (_) { /* no sound device */ }
}

/* ---------- art: the game's own screenshot, else a colour field from its icon ---------- */
function probeArt(id) {
  if (id in state.art) return Promise.resolve(state.art[id]);
  return new Promise((res) => {
    const img = new Image();
    img.onload = () => res((state.art[id] = artUrl(id)));
    img.onerror = () => res((state.art[id] = null));
    img.src = artUrl(id);
  });
}
function iconColor(id) {
  if (state.colors[id]) return Promise.resolve(state.colors[id]);
  return new Promise((res) => {
    const img = new Image();
    img.onload = () => {
      try {
        const c = document.createElement("canvas"); c.width = c.height = 16;
        const x = c.getContext("2d"); x.drawImage(img, 0, 0, 16, 16);
        const d = x.getImageData(0, 0, 16, 16).data;
        let r = 0, g = 0, b = 0, n = 0;
        for (let i = 0; i < d.length; i += 4) {
          if (d[i + 3] < 128) continue;
          const mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]);
          const w = 1 + (mx - mn) / 32;           // colourful pixels count more
          r += d[i] * w; g += d[i + 1] * w; b += d[i + 2] * w; n += w;
        }
        const col = n ? [r / n, g / n, b / n].map((v) => Math.round(v * 0.55)) : [40, 44, 60];
        res((state.colors[id] = col));
      } catch (_) { res((state.colors[id] = [40, 44, 60])); }
    };
    img.onerror = () => res((state.colors[id] = [40, 44, 60]));
    img.src = iconUrl(id);
  });
}
const rgb = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

/* ---------- tiles ---------- */
const STAR = '<svg viewBox="0 0 24 24"><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/></svg>';
function tile(g, i) {
  const t = document.createElement("button");
  t.className = "tile" + (g.favorite ? " is-fav" : "");
  t.dataset.id = g.id; t.dataset.i = i;
  t.innerHTML = `<div class="art"><img class="icon" alt="" src="${iconUrl(g.id)}" onerror="this.remove()"></div>
    <span class="fav">${STAR}</span><span class="name"></span>`;
  t.querySelector(".name").textContent = g.name;
  const art = t.querySelector(".art");
  probeArt(g.id).then((url) => {
    if (url) {
      art.style.backgroundImage = `url("${url}")`;
      const ic = art.querySelector(".icon");
      if (ic) { ic.style.width = "30%"; ic.style.alignSelf = "end"; ic.style.marginBottom = "10px"; }
    } else {
      iconColor(g.id).then((c) => {
        art.style.background = `radial-gradient(circle at 50% 38%, ${rgb(c.map((v) => Math.min(255, v * 1.6)))} 0%, ${rgb(c)} 55%, #0d0f15 100%)`;
      });
    }
  });
  return t;
}
function addTile() {
  const t = document.createElement("button");
  t.className = "tile add"; t.dataset.add = "1";
  t.innerHTML = '<div class="art">+<br>Add a game</div><span class="name">Add a game</span>';
  return t;
}

/* ---------- home ---------- */
function shelfGames() {
  const g = [...state.games];
  if (state.shelf === "fav") return g.filter((x) => x.favorite);
  if (state.shelf === "recent") return g.filter((x) => x.last).sort((a, b) => b.last - a.last);
  return g.sort((a, b) => (b.last - a.last) || a.name.localeCompare(b.name));
}
function renderRow() {
  const row = $("#row"); row.innerHTML = "";
  const list = shelfGames();
  list.forEach((g, i) => row.appendChild(tile(g, i)));
  row.appendChild(addTile());
  state.focus = Math.min(state.focus, Math.max(0, list.length - 1));
  focusTile(state.focus, false);
  if (!list.length) showHero(null);
  $("#count").textContent = `${state.games.length} games installed`;
}
function current() { return shelfGames()[state.focus]; }
function ago(t) {
  if (!t) return "Not played yet";
  const d = Math.floor((Date.now() / 1000 - t) / 86400);
  return d < 1 ? "Played today" : d === 1 ? "Played yesterday" : `Played ${d} days ago`;
}
let heroToken = 0;
async function showHero(g) {
  const tok = ++heroToken;
  $("#heroTitle").textContent = g ? g.name : "Your games live here";
  $("#heroAbout").textContent = g ? (g.about || "") : "Install games from the App Store, or add any game in Settings.";
  $("#heroEyebrow").textContent = g && g.last ? "Continue playing" : "From your collection";
  const chips = $("#heroChips"); chips.innerHTML = "";
  if (g) {
    for (const v of [g.genre, "Installed", ago(g.last), g.count ? `Played ${g.count}×` : ""]) {
      if (!v) continue;
      const c = document.createElement("span"); c.className = "chip"; c.textContent = v; chips.appendChild(c);
    }
  }
  $("#btnPlay").disabled = !g;
  // background crossfade
  const a = $("#bgA"), b = $("#bgB");
  const next = a.classList.contains("on") ? b : a, prev = next === a ? b : a;
  const url = g ? await probeArt(g.id) : null;
  if (tok !== heroToken) return;
  next.removeAttribute("style");
  if (url) {
    next.className = "bg-img";
    next.style.backgroundImage = `url("${url}")`;
  } else {
    const c = g ? await iconColor(g.id) : [30, 34, 48];
    if (tok !== heroToken) return;
    next.className = "bg-img fallback";
    next.style.backgroundColor = rgb(c);
    if (g) {
      next.style.backgroundImage = `url("${iconUrl(g.id)}")`;
      next.style.backgroundSize = "contain"; next.style.backgroundRepeat = "no-repeat"; next.style.backgroundPosition = "80% 40%";
    }
  }
  requestAnimationFrame(() => { next.classList.add("on"); prev.classList.remove("on"); });
}
function focusTile(i, sound = true) {
  const tiles = $$("#row .tile");
  if (!tiles.length) return;
  i = Math.max(0, Math.min(i, tiles.length - 1));
  tiles.forEach((t) => t.classList.remove("focus"));
  const t = tiles[i]; t.classList.add("focus");
  t.scrollIntoView({ inline: "nearest", block: "nearest", behavior: "smooth" });
  state.focus = i; state.inHero = false;
  $("#btnPlay").classList.remove("focus");
  if (!t.dataset.add) showHero(shelfGames()[i]);
  if (sound) blip(720);
}

/* ---------- actions ---------- */
async function play(g) {
  if (!g) return;
  blip(520, 0.12, 0.05);
  toast(`Starting ${g.name}…`);
  try { await api("/api/launch", { id: g.id }); g.last = Date.now() / 1000; g.count++; } catch (_) { toast("Could not start the game"); }
}
async function toggleFav(g) {
  if (!g) return;
  const favs = new Set(state.settings.favorites || []);
  if (favs.has(g.id)) favs.delete(g.id); else favs.add(g.id);
  state.settings = await api("/api/settings", { favorites: [...favs] });
  g.favorite = favs.has(g.id);
  toast(g.favorite ? "Added to favourites" : "Removed from favourites");
  renderAll(false);
}
function options(g) {
  if (!g) return;
  sheet(g.name, `<p>${esc(g.about || "No description.")}</p><p>${esc(g.genre)} · ${esc(ago(g.last))}${g.count ? ` · played ${g.count}×` : ""}</p>`, [
    [g.favorite ? "Remove favourite" : "Add to favourites", () => toggleFav(g)],
    ...(g.mine ? [["Remove from collection", async () => { await api("/api/remove", { id: g.id }); await load(); }]] : []),
    ["Play", () => play(g), true],
  ]);
}
function addGame() {
  sheet("Add a game", `<p>Any program can be a game in your collection – for example a game you downloaded, a script or an emulator.</p>
    <label>Name</label><input id="agName" maxlength="60" placeholder="My game">
    <label>Command to start it</label><input id="agCmd" placeholder="/root/Games/mygame/start.sh">`, [
    ["Cancel", null],
    ["Add", async () => {
      const name = $("#agName").value.trim(), command = $("#agCmd").value.trim();
      if (!name || !command) { toast("Fill in both fields"); return; }
      await api("/api/add", { name, command }); await load(); toast(`${name} added`);
    }, true],
  ]);
  setTimeout(() => $("#agName").focus(), 50);
}

/* ---------- pages ---------- */
function go(page) {
  if (!PAGES.includes(page)) return;
  state.page = page;
  $$("#nav button").forEach((b) => b.classList.toggle("active", b.dataset.page === page));
  $$(".page").forEach((p) => p.classList.toggle("active", p.id === "page-" + page));
  blip(600, 0.04);
  if (page === "search") setTimeout(() => $("#searchBox").focus(), 30);
  if (page === "performance") renderPerf();
  if (page === "home") showHero(current());
}
function renderGrid(el, list, withAdd) {
  el.innerHTML = "";
  list.forEach((g, i) => el.appendChild(tile(g, i)));
  if (withAdd) el.appendChild(addTile());
}
let genre = "All";
function renderLibrary() {
  const genres = ["All", ...new Set(state.games.map((g) => g.genre))];
  const tabs = $("#genreTabs"); tabs.innerHTML = "";
  for (const name of genres) {
    const b = document.createElement("button"); b.textContent = name; b.classList.toggle("active", name === genre);
    b.onclick = () => { genre = name; renderLibrary(); }; tabs.appendChild(b);
  }
  const list = state.games.filter((g) => genre === "All" || g.genre === genre).sort((a, b) => a.name.localeCompare(b.name));
  renderGrid($("#libGrid"), list, true);
}
function renderSearch() {
  const q = $("#searchBox").value.trim().toLowerCase();
  const list = !q ? [] : state.games.filter((g) => (g.name + " " + g.about + " " + g.genre).toLowerCase().includes(q));
  renderGrid($("#searchGrid"), list, false);
}
async function renderPerf() {
  const box = $("#perfCards");
  try {
    const s = await api("/api/system");
    const cards = [
      ["Game graphics", s.graphics, "AirCursor toolbar → AirBoost → Graphics"],
      ["Steady FPS", s.fpsLimit ? `${s.fpsLimit} FPS` : "No limit", "Even frame pacing, cooler phone"],
      ["Desktop size", s.resolution || "–", "Fewer pixels = more FPS (Turbo profile)"],
      ["Memory free", `${s.memFreeGB} / ${s.memTotalGB} GB`, "Close unused apps before big games"],
      ["CPU cores", String(s.cores), "All cores draw the graphics"],
      ["Programs running", String(s.programs), s.programLimit === "off" ? "Android limit lifted" : "Android limit: about 32"],
      ["Storage free", `${s.storageFreeGB} GB`, "For games and projects"],
      ["FPS overlay", s.overlay ? "On" : "Off", "Right Shift + F12 in a game"],
    ];
    box.innerHTML = "";
    for (const [k, v, n] of cards) {
      const c = document.createElement("div"); c.className = "card";
      c.innerHTML = "<small></small><b></b><span></span>";
      c.querySelector("small").textContent = k; c.querySelector("b").textContent = v; c.querySelector("span").textContent = n;
      box.appendChild(c);
    }
  } catch (_) { box.textContent = "Could not read the system."; }
}
function renderAll(resetFocus = true) {
  if (resetFocus) state.focus = 0;
  renderRow(); renderLibrary(); renderSearch();
}

/* ---------- sheet + toast ---------- */
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
function sheet(title, html, actions) {
  $("#sheetTitle").textContent = title; $("#sheetBody").innerHTML = html;
  const box = $("#sheetActions"); box.innerHTML = "";
  for (const [label, fn, primary] of actions) {
    const b = document.createElement("button"); b.className = "btn" + (primary ? " primary" : ""); b.textContent = label;
    b.onclick = async () => { closeSheet(); if (fn) await fn(); }; box.appendChild(b);
  }
  $("#sheet").hidden = false;
}
function closeSheet() { $("#sheet").hidden = true; }
let toastTimer;
function toast(msg) {
  const t = $("#toast"); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 2600);
}

/* ---------- settings + theme ---------- */
function applyTheme(name) {
  document.body.className = document.body.className.replace(/theme-\S+/g, "").trim() + " theme-" + name;
  $$(".theme-card").forEach((c) => c.classList.toggle("active", c.dataset.theme === name));
}
function applyProfile() {
  const n = (state.settings.name || "Player").trim() || "Player";
  $("#avatar").textContent = n.slice(0, 1).toUpperCase();
  $("#setName").value = state.settings.name || "";
  $("#setSounds").checked = !!state.settings.sounds;
}

/* ---------- input ---------- */
function start() {
  if (!document.body.classList.contains("booting")) return;
  document.body.classList.remove("booting");
  blip(440, 0.18, 0.05); setTimeout(() => blip(660, 0.22, 0.04), 120);
  const fs = document.documentElement.requestFullscreen;
  if (fs) document.documentElement.requestFullscreen().catch(() => {});
}
document.addEventListener("keydown", (e) => {
  if (document.body.classList.contains("booting")) { start(); e.preventDefault(); return; }
  if (!$("#sheet").hidden) { if (e.key === "Escape") closeSheet(); return; }
  const typing = e.target.matches("input");
  if (e.key === "Escape") { if (state.page !== "home") go("home"); e.preventDefault(); return; }
  if (typing) return;
  const i = PAGES.indexOf(state.page);
  if (e.key === "q" || e.key === "Q" || e.key === "[") { go(PAGES[(i + PAGES.length - 1) % PAGES.length]); return; }
  if (e.key === "e" || e.key === "E" || e.key === "]") { go(PAGES[(i + 1) % PAGES.length]); return; }
  if (state.page !== "home") return;
  const k = e.key.toLowerCase();
  if (k === "arrowright" || k === "d") { e.preventDefault(); focusTile(state.focus + 1); }
  else if (k === "arrowleft" || k === "a") { e.preventDefault(); focusTile(state.focus - 1); }
  else if (k === "arrowup" || k === "w") {
    e.preventDefault(); state.inHero = true;
    $$("#row .tile").forEach((t) => t.classList.remove("focus")); $("#btnPlay").classList.add("focus"); blip(820);
  } else if (k === "arrowdown" || k === "s") { e.preventDefault(); focusTile(state.focus); }
  else if (k === "enter" || k === " ") {
    e.preventDefault();
    const t = $$("#row .tile")[state.focus];
    if (t && t.dataset.add && !state.inHero) addGame(); else play(current());
  } else if (k === "f") toggleFav(current());
  else if (k === "o") options(current());
});
$("#boot").addEventListener("click", start);
$("#nav").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) go(b.dataset.page); });
$$("[data-goto]").forEach((b) => (b.onclick = () => go(b.dataset.goto)));
$("#shelfTabs").addEventListener("click", (e) => {
  const b = e.target.closest("button"); if (!b) return;
  state.shelf = b.dataset.shelf; $$("#shelfTabs button").forEach((x) => x.classList.toggle("active", x === b));
  state.focus = 0; renderRow(); blip(600);
});
function tileClick(e, where) {
  const t = e.target.closest(".tile"); if (!t) return;
  if (t.dataset.add) { addGame(); return; }
  const g = state.games.find((x) => x.id === t.dataset.id);
  if (where === "row") {
    const i = Number(t.dataset.i);
    if (i === state.focus && t.classList.contains("focus")) play(g); else focusTile(i);   // tap = select, tap again = play
  } else options(g);
}
$("#row").addEventListener("click", (e) => tileClick(e, "row"));
$("#libGrid").addEventListener("click", (e) => tileClick(e, "grid"));
$("#searchGrid").addEventListener("click", (e) => tileClick(e, "grid"));
$("#btnPlay").onclick = () => play(current());
$("#btnMore").onclick = () => options(current());
$("#searchBox").addEventListener("input", renderSearch);
$("#sheet").addEventListener("click", (e) => { if (e.target.id === "sheet") closeSheet(); });
$("#btnAdd").onclick = addGame;
$("#btnRescan").onclick = async () => { await load(); toast(`${state.games.length} games found`); };
$$("[data-tool]").forEach((b) => (b.onclick = async () => { await api("/api/tool", { name: b.dataset.tool }); toast("Opening…"); }));
$("#btnDoctor").onclick = async () => {
  toast("Checking the PC…");
  const r = await api("/api/doctor");
  sheet("PC health", `<pre>${esc(r.text)}</pre>`, [["Close", null, true]]);
};
$("#themeCards").addEventListener("click", async (e) => {
  const c = e.target.closest(".theme-card"); if (!c) return;
  applyTheme(c.dataset.theme); blip(700);
  state.settings = await api("/api/settings", { theme: c.dataset.theme });
});
$("#setName").addEventListener("change", async (e) => { state.settings = await api("/api/settings", { name: e.target.value.trim() }); applyProfile(); });
$("#setSounds").addEventListener("change", async (e) => { state.settings = await api("/api/settings", { sounds: e.target.checked }); });
$("#btnFull").onclick = () => {
  const p = document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen();
  if (p) p.catch(() => {});
};
$("#btnQuit").onclick = () => sheet("Exit TS Station?", "<p>Your games and settings stay saved.</p>", [["Cancel", null], ["Exit", async () => {
  try { await api("/api/quit", {}); } catch (_) { /* already closed */ }
  document.body.innerHTML = '<div style="display:grid;place-items:center;height:100vh;color:#888;font:14px Inter,sans-serif">TS Station closed – you can close this window.</div>';
  window.close();
}, true]]);

function tick() { $("#clock").textContent = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }

async function load() {
  const [{ games }, settings] = await Promise.all([api("/api/games"), api("/api/settings")]);
  state.games = games; state.settings = settings;
  applyTheme(settings.theme || "native"); applyProfile();
  renderAll(true);
}
tick(); setInterval(tick, 15000);
load().catch(() => {
  $("#heroTitle").textContent = "Start TS Station from its desktop icon";
  document.body.classList.remove("booting");
});
