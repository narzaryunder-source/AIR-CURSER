/* TS Station – interface logic. Controller, keyboard, mouse, touch and the AirCursor hand cursor all work. */
"use strict";

const KEY = new URLSearchParams(location.hash.slice(1)).get("k") || "";
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const PAGES = ["home", "library", "store", "search", "performance", "themes", "settings"];

const state = {
  games: [], store: [], job: null, settings: {}, sys: {}, page: "home", shelf: "all", sort: "az", genre: "All",
  storeGenre: "All", storeHero: null, hero: null, rowSel: 0, details: null, padSeen: false, sheetBack: null,
};

async function api(path, body) {
  const opt = { headers: { "X-TS-Key": KEY } };
  if (body !== undefined) { opt.method = "POST"; opt.body = JSON.stringify(body); opt.headers["Content-Type"] = "application/json"; }
  const r = await fetch(path, opt);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}
const enc = encodeURIComponent;
const iconUrl = (id) => `/api/icon/${enc(id)}?k=${KEY}`;
const artUrl = (id) => `/api/art/${enc(id)}?k=${KEY}`;
const storeArtUrl = (id, hd) => `/api/storeart/${enc(id)}?k=${KEY}${hd ? "&hd=1" : ""}`;
const storeIconUrl = (id) => `/api/storeicon/${enc(id)}?k=${KEY}`;
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const size = (mb) => (mb >= 1 ? `${mb} MB` : "< 1 MB");

/* ---------- sounds (soft, short, can be turned off) ---------- */
let audio;
function blip(freq = 660, dur = 0.05, vol = 0.035) {
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

/* ---------- pictures: probe once, remember the answer ---------- */
const probed = {};
function probe(url) {
  if (!(url in probed)) {
    probed[url] = new Promise((res) => {
      const img = new Image();
      img.onload = () => res(url); img.onerror = () => res(null); img.src = url;
    });
  }
  return probed[url];
}
const colors = {};
function iconColor(url) {
  if (!colors[url]) {
    colors[url] = new Promise((res) => {
      const img = new Image();
      img.onload = () => {
        try {
          const c = document.createElement("canvas"); c.width = c.height = 16;
          const x = c.getContext("2d"); x.drawImage(img, 0, 0, 16, 16);
          const d = x.getImageData(0, 0, 16, 16).data;
          let r = 0, g = 0, b = 0, n = 0;
          for (let i = 0; i < d.length; i += 4) {
            if (d[i + 3] < 128) continue;
            const w = 1 + (Math.max(d[i], d[i + 1], d[i + 2]) - Math.min(d[i], d[i + 1], d[i + 2])) / 32;   // colourful pixels count more
            r += d[i] * w; g += d[i + 1] * w; b += d[i + 2] * w; n += w;
          }
          res(n ? [r / n, g / n, b / n].map((v) => Math.round(v * 0.55)) : [40, 44, 60]);
        } catch (_) { res([40, 44, 60]); }
      };
      img.onerror = () => res([40, 44, 60]);
      img.src = url;
    });
  }
  return colors[url];
}
const rgb = (c) => `rgb(${c[0]},${c[1]},${c[2]})`;
function paintArt(el, art, icon) {
  probe(art).then((url) => {
    if (url) { el.style.backgroundImage = `url("${url}")`; el.classList.add("has-art"); return; }
    iconColor(icon).then((c) => {
      el.style.background = `radial-gradient(circle at 50% 36%, ${rgb(c.map((v) => Math.min(255, v * 1.7)))} 0%, ${rgb(c)} 55%, #0c0e14 100%)`;
    });
  });
}

/* ---------- game names as a logo ---------- */
function setLogo(el, name) {
  name = String(name);
  const m = name.match(/^(.+?)\s*(?:[:–]\s+|\s-\s|\()(.+?)\)?$/);
  el.innerHTML = m ? `${esc(m[1])}<small>${esc(m[2])}</small>` : esc(name);
  const len = (m ? m[1] : name).length;
  el.classList.toggle("long", len > 11 && len <= 18);
  el.classList.toggle("xlong", len > 18);
}

/* ---------- tiles ---------- */
const STAR = '<svg viewBox="0 0 24 24"><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/></svg>';
function tile(g) {
  const t = document.createElement("button");
  t.className = "tile" + (g.favorite ? " is-fav" : "");
  t.dataset.id = g.id;
  t.innerHTML = `<div class="art"><img class="icon" alt="" src="${iconUrl(g.id)}" onerror="this.remove()"></div>
    <span class="fav">${STAR}</span><span class="t-name"></span><span class="t-src"></span>`;
  t.querySelector(".t-name").textContent = g.name;
  t.querySelector(".t-src").textContent = g.source || "";
  paintArt(t.querySelector(".art"), artUrl(g.id), iconUrl(g.id));
  return t;
}
function addTile() {
  const t = document.createElement("button");
  t.className = "tile add"; t.dataset.add = "1";
  t.innerHTML = '<div class="art">+</div><span class="t-name">Get games</span><span class="t-src">TS Store</span>';
  return t;
}
const gameById = (id) => state.games.find((g) => g.id === id);
const storeById = (id) => state.store.find((s) => s.id === id);
const gameForStore = (sid) => state.games.find((g) => g.store === sid);

/* ---------- home ---------- */
function shelfGames() {
  const g = [...state.games];
  if (state.shelf === "fav") return g.filter((x) => x.favorite).sort((a, b) => a.name.localeCompare(b.name));
  if (state.shelf === "recent") return g.filter((x) => x.last).sort((a, b) => b.last - a.last);
  return g.sort((a, b) => (b.last - a.last) || a.name.localeCompare(b.name));
}
function renderRow() {
  const row = $("#row"); row.innerHTML = "";
  const list = shelfGames();
  list.forEach((g) => row.appendChild(tile(g)));
  row.appendChild(addTile());
  $("#nAll").textContent = state.games.length;
  $("#nFav").textContent = state.games.filter((g) => g.favorite).length;
  $("#nRecent").textContent = state.games.filter((g) => g.last).length;
  state.rowSel = Math.min(state.rowSel, Math.max(0, list.length - 1));
  selectRow(state.rowSel, false);
  if (!list.length) showHero(null);
  $("#count").innerHTML = `<b>${state.games.length}</b> installed · TS Console`;
}
function selectRow(i, sound = true) {
  const tiles = $$("#row .tile");
  if (!tiles.length) return;
  i = Math.max(0, Math.min(i, tiles.length - 1));
  tiles.forEach((t, k) => t.classList.toggle("focus", k === i));
  state.rowSel = i;
  const g = gameById(tiles[i].dataset.id);
  if (g) showHero(g);
  if (sound) blip(720);
}
function ago(t) {
  if (!t) return "Not played yet";
  const d = Math.floor((Date.now() / 1000 - t) / 86400);
  return d < 1 ? "Played today" : d === 1 ? "Played yesterday" : `Played ${d} days ago`;
}
function metaHtml(g) {
  const parts = [`<span class="src">${esc(g.source || "Ubuntu")}</span>`, '<span class="dot">Installed</span>'];
  if (state.sys.profile) parts.push(`<span>Profile <b>${esc(state.sys.profile)}</b></span>`);
  if (g.fit) parts.push(`<span>Runs <b>${esc(g.fit)}</b></span>`);
  parts.push(`<span>${esc(g.count ? `${ago(g.last)} · ${g.count}×` : ago(g.last))}</span>`);
  return parts.join("");
}
let heroTimer, heroToken = 0;
function showHero(g) {
  state.hero = g;
  setLogo($("#heroTitle"), g ? g.name : "Your games live here");
  $("#heroEyebrow").textContent = g ? (g.last ? "Continue playing" : g.genre || "Game") : "TS Station";
  $("#heroChips").innerHTML = g ? metaHtml(g) : '<span class="src">TS Store</span><span>Free games, one tap to install</span>';
  $("#heroAbout").textContent = g ? (g.about || "") : "Open the Store to install free games – racing, building, shooters, strategy and more.";
  $("#btnPlay span").textContent = g ? "Play Game" : "Open Store";
  clearTimeout(heroTimer);
  heroTimer = setTimeout(() => setBackground(g ? artUrl(g.id) : null, g ? iconUrl(g.id) : null), 90);   // fast scrolling skips pictures
}
async function setBackground(art, icon) {
  const tok = ++heroToken;
  const a = $("#bgA"), b = $("#bgB");
  const next = a.classList.contains("on") ? b : a, prev = next === a ? b : a;
  const url = art ? await probe(art) : null;
  const c = !url && icon ? await iconColor(icon) : [26, 30, 42];
  if (tok !== heroToken) return;
  next.removeAttribute("style");
  if (url) {
    next.className = "bg-img"; next.style.backgroundImage = `url("${url}")`;
  } else {
    next.className = "bg-img fallback"; next.style.backgroundColor = rgb(c);
    if (icon) Object.assign(next.style, { backgroundImage: `url("${icon}")`, backgroundSize: "34%", backgroundRepeat: "no-repeat", backgroundPosition: "78% 40%" });
  }
  requestAnimationFrame(() => { next.classList.add("on"); prev.classList.remove("on"); });
}

/* ---------- actions ---------- */
async function play(g) {
  if (!g) { go("store"); return; }
  blip(520, 0.12, 0.05);
  toast(`Starting ${g.name}…`);
  try { await api("/api/launch", { id: g.id }); g.last = Date.now() / 1000; g.count = (g.count || 0) + 1; }
  catch (_) { toast("Could not start the game"); }
}
async function toggleFav(g) {
  if (!g) return;
  const favs = new Set(state.settings.favorites || []);
  if (favs.has(g.id)) favs.delete(g.id); else favs.add(g.id);
  state.settings = await api("/api/settings", { favorites: [...favs] });
  g.favorite = favs.has(g.id);
  toast(g.favorite ? "Added to favourites" : "Removed from favourites");
  blip(880, 0.06);
  const keep = document.activeElement && document.activeElement.closest(".tile");
  const keepId = keep && keep.dataset.id, inRow = keep && keep.closest("#row");
  renderGames();
  if (state.details && state.details.game === g) openDetails(g);
  else if (keepId) {
    const again = $(`${inRow ? "#row" : "#page-" + state.page} .tile[data-id="${CSS.escape(keepId)}"]`);
    if (again) again.focus({ preventScroll: true });
  }
}
function options(g) {
  if (!g) return;
  sheet(g.name, `<p>${esc(g.genre)} · ${esc(ago(g.last))}${g.count ? ` · played ${g.count}×` : ""}</p>`, [
    ["Details", () => openDetails(g)],
    [g.favorite ? "Remove favourite" : "Add to favourites", () => toggleFav(g)],
    ...(g.mine ? [["Remove from collection", () => removeCustom(g)]] : []),
    ...(g.store ? [["Uninstall", () => uninstall(storeById(g.store))]] : []),
    ["Play", () => play(g), true],
  ]);
}
async function removeCustom(g) { await api("/api/remove", { id: g.id }); closeDetails(); await load(false); toast(`${g.name} removed`); }
function addGame() {
  sheet("Add a game", `<p>Any program can be a game in your collection – a game you downloaded, a script or an emulator.</p>
    <label for="agName">Name</label><input id="agName" maxlength="60" placeholder="My game">
    <label for="agCmd">Command to start it</label><input id="agCmd" placeholder="/root/Games/mygame/start.sh">`, [
    ["Cancel", null],
    ["Add", async () => {
      const name = $("#agName").value.trim(), command = $("#agCmd").value.trim();
      if (!name || !command) { toast("Fill in both fields"); return; }
      await api("/api/add", { name, command }); await load(false); toast(`${name} added`);
    }, true],
  ]);
  setTimeout(() => $("#agName").focus(), 50);
}

/* ---------- details ---------- */
const stat = (k, v) => `<div class="stat"><small>${esc(k)}</small><b>${esc(v)}</b></div>`;
const FIT_TEXT = { Smooth: "Runs smooth", Good: "Good · Game profile", Heavy: "Heavy · Turbo profile" };
function button(cls, html, fn) {
  const b = document.createElement("button"); b.className = cls; b.innerHTML = html;
  if (fn) b.onclick = fn; else b.disabled = true;
  return b;
}
const PLAY_ICON = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
function openDetails(g) {
  const s = g.store ? storeById(g.store) : null;
  state.details = { game: g, back: state.details ? state.details.back : document.activeElement };
  detailsArt(artUrl(g.id), iconUrl(g.id));
  $("#dEyebrow").textContent = g.genre || "Game";
  setLogo($("#dTitle"), g.name);
  $("#dChips").innerHTML = metaHtml(g);
  $("#dAbout").textContent = g.about || "No description yet.";
  const acts = $("#dActions"); acts.innerHTML = "";
  acts.append(button("btn-play", `${PLAY_ICON}<span>Play Game</span>`, () => play(g)));
  acts.append(button("btn-ghost", g.favorite ? "★ Favourite" : "☆ Add to favourites", () => toggleFav(g)));
  if (s) acts.append(button("btn-ghost", "Uninstall", () => uninstall(s)));
  if (g.mine) acts.append(button("btn-ghost", "Remove", () => removeCustom(g)));
  $("#dStats").innerHTML = stat("Played", g.count ? `${g.count} time${g.count > 1 ? "s" : ""}` : "Never") +
    stat("Last played", g.last ? ago(g.last).replace("Played ", "") : "–") + stat("Genre", g.genre || "Game") +
    stat("On this phone", FIT_TEXT[g.fit] || "–") + stat("Source", g.source || "Ubuntu");
  showDetails();
}
function openStoreDetails(s) {
  if (!s) return;
  const g = gameForStore(s.id);
  if (s.installed && g) { openDetails(g); return; }
  state.details = { store: s, back: state.details ? state.details.back : document.activeElement };
  detailsArt(storeArtUrl(s.id, true), storeIconUrl(s.id));
  $("#dEyebrow").textContent = `TS Store · ${s.genre}`;
  setLogo($("#dTitle"), s.name);
  $("#dChips").innerHTML = `<span class="src">TS Store</span><span class="dot${s.installed ? "" : " warn"}">${s.installed ? "Installed" : "Not installed"}</span>` +
    `<span>Free</span><span>${size(s.mb)}</span><span>Runs <b>${esc(s.fit)}</b></span>`;
  $("#dAbout").textContent = s.about;
  const acts = $("#dActions"); acts.innerHTML = "";
  const job = state.job && state.job.state === "running" && state.job.id === s.id;
  if (job) acts.append(button("btn-play", `<span>${state.job.action === "install" ? "Installing" : "Removing"}… ${state.job.percent}%</span>`, null));
  else if (s.installed) {
    acts.append(button("btn-play", "<span>Installed – find it in Library</span>", () => go("library")));
    acts.append(button("btn-ghost", "Uninstall", () => uninstall(s)));
  } else acts.append(button("btn-play", '<svg viewBox="0 0 24 24" style="fill:none;stroke:currentColor"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg><span>Install – free</span>', () => install(s)));
  $("#dStats").innerHTML = stat("Price", "Free") + stat("Download", size(s.mb)) + stat("On this phone", FIT_TEXT[s.fit]) +
    stat("Genre", s.genre) + stat("From", "Ubuntu");
  showDetails();
}
function detailsArt(art, icon) {
  const el = $("#dArt"); el.removeAttribute("style"); el.className = "details-art";
  probe(art).then(async (url) => {
    if (url) { el.style.backgroundImage = `url("${url}")`; return; }
    const c = await iconColor(icon);
    el.className = "details-art fallback";
    Object.assign(el.style, { backgroundColor: rgb(c), backgroundImage: `url("${icon}")`, backgroundSize: "30%", backgroundRepeat: "no-repeat", backgroundPosition: "80% 45%" });
  });
}
function showDetails() {
  const wasOpen = !$("#details").hidden;
  $("#details").hidden = false;
  if (!wasOpen) blip(560, 0.08);
  ($("#dActions button:not([disabled])") || $("#dBack")).focus({ preventScroll: true });
}
function closeDetails() {
  if ($("#details").hidden) return;
  const back = state.details && state.details.back;
  $("#details").hidden = true; state.details = null;
  if (back && back.isConnected) back.focus({ preventScroll: true }); else defaultFocus();
  blip(480, 0.05);
}

/* ---------- store ---------- */
function storeCard(s) {
  const b = document.createElement("button");
  b.className = "sgame"; b.dataset.sid = s.id;
  b.innerHTML = `<div class="s-art"><img alt="" src="${storeIconUrl(s.id)}" onerror="this.remove()"></div>
    <div class="s-body"><span class="s-name"></span><span class="s-info"><span class="fit fit-${esc(s.fit)}">${esc(s.fit)}</span>
    <span>${esc(s.genre)}</span><span>${size(s.mb)}</span></span><span class="s-act"></span></div>`;
  b.querySelector(".s-name").textContent = s.name;
  paintArt(b.querySelector(".s-art"), storeArtUrl(s.id), storeIconUrl(s.id));
  cardState(b, s);
  return b;
}
function cardState(b, s) {
  if (state.job && state.job.id === "ts-station") return;
  const act = b.querySelector(".s-act");
  const job = state.job && state.job.state === "running" && state.job.id === s.id ? state.job : null;
  let bar = b.querySelector(".bar");
  if (job) {
    act.className = "s-act"; act.textContent = `${job.action === "install" ? "Installing" : "Removing"}… ${job.percent}%`;
    if (!bar) { bar = document.createElement("div"); bar.className = "bar"; bar.innerHTML = "<i></i>"; b.querySelector(".s-body").append(bar); }
    bar.firstChild.style.width = job.percent + "%";
  } else {
    if (bar) bar.remove();
    act.className = "s-act " + (s.installed ? "have" : "get");
    act.textContent = s.installed ? "✓ Installed" : "Get – free";
  }
}
function renderStore() {
  const genres = ["All", "Runs smooth", ...new Set(state.store.map((s) => s.genre))];
  const tabs = $("#storeTabs"); tabs.innerHTML = "";
  for (const name of genres) {
    const b = document.createElement("button"); b.textContent = name; b.classList.toggle("active", name === state.storeGenre);
    b.onclick = () => { state.storeGenre = name; renderStore(); blip(600); $$("#storeTabs button")[genres.indexOf(name)].focus(); };
    tabs.appendChild(b);
  }
  const list = state.store.filter((s) => state.storeGenre === "All" || (state.storeGenre === "Runs smooth" ? s.fit === "Smooth" : s.genre === state.storeGenre));
  const grid = $("#storeGrid"); grid.innerHTML = "";
  list.forEach((s) => grid.appendChild(storeCard(s)));
  if (!state.storeHero && state.store.length) storeHero(state.store[0]);
}
function storeHero(s) {
  if (!s || state.storeHero === s.id) return;
  state.storeHero = s.id;
  $("#storeHeroTitle").textContent = s.name;
  $("#storeHeroAbout").textContent = `${s.about}  ·  ${s.genre} · ${size(s.mb)} · runs ${s.fit.toLowerCase()}`;
  const el = $("#storeHeroArt");
  probe(storeArtUrl(s.id, true)).then((u) => { if (state.storeHero === s.id) el.style.backgroundImage = u ? `url("${u}")` : "none"; });
}
async function install(s) {
  if (!s) return;
  const r = await api("/api/install", { id: s.id });
  if (!r.ok) { toast(r.error); return; }
  state.job = r.job; toast(`Installing ${s.name}… you can keep browsing`); blip(620, 0.1);
  if (state.details && state.details.store) openStoreDetails(s);
  refreshJob(); watchJob();
}
function uninstall(s) {
  if (!s) return;
  sheet(`Uninstall ${s.name}?`, "<p>The game is removed from this PC. Your saves in the home folder stay.</p>", [["Cancel", null], ["Uninstall", async () => {
    const r = await api("/api/uninstall", { id: s.id });
    if (!r.ok) { toast(r.error); return; }
    state.job = r.job; closeDetails(); refreshJob(); watchJob();
  }, true]]);
}
let jobTimer;
function watchJob() {
  clearInterval(jobTimer);
  jobTimer = setInterval(async () => {
    try { state.job = await api("/api/job"); } catch (_) { return; }
    refreshJob();
    if (state.job.state === "running") return;
    clearInterval(jobTimer);
    const sid = state.job.id, s = storeById(sid);
    if (state.job.state === "done") { blip(660, 0.12); setTimeout(() => blip(990, 0.16), 110); toast(`${s ? s.name : "Game"}: ${state.job.text}`); }
    else toast(`Did not work: ${state.job.text}`);
    await load(false);
    if (state.details && state.details.store && state.details.store.id === sid) openStoreDetails(storeById(sid));
  }, 900);
}
/* ---------- TS Station updates (no terminal needed) ---------- */
async function checkVersion(announce) {
  try {
    const v = await api("/api/version");
    state.version = v;
    $("#verText").textContent = `Version ${v.installed}` + (v.update ? ` · new version ${v.latest} is ready` : v.latest ? " · up to date" : "");
    $("#btnUpdate").textContent = v.update ? `Update to ${v.latest}` : "Check for updates";
    $("#btnUpdate").classList.toggle("primary", !!v.update);
    if (announce && v.update) toast(`TS Station ${v.latest} is ready – Settings → Update`);
    return v;
  } catch (_) { return null; }
}
async function updateStation() {
  const v = state.version && state.version.update ? state.version : await checkVersion(false);
  if (!v || !v.update) { toast(v ? "TS Station is up to date" : "Could not check – is the internet on?"); return; }
  const r = await api("/api/update", {});
  if (!r.ok) { toast(r.error); return; }
  $("#btnUpdate").disabled = true;
  const timer = setInterval(async () => {
    let j; try { j = await api("/api/job"); } catch (_) { return; }
    $("#btnUpdate").textContent = j.state === "running" ? `Updating… ${j.percent}%` : "Check for updates";
    if (j.state === "running") return;
    clearInterval(timer); $("#btnUpdate").disabled = false;
    if (j.state !== "done") { toast(j.text); return; }
    toast("Updated – restarting TS Station…");
    try { await api("/api/restart", {}); } catch (_) { /* restarting */ }
    setTimeout(() => location.reload(), 2500);
  }, 900);
}
$("#btnUpdate").onclick = updateStation;

function refreshJob() {
  for (const b of $$(".sgame")) { const s = storeById(b.dataset.sid); if (s) cardState(b, s); }
  const j = state.job;
  if (state.details && state.details.store && j && state.details.store.id === j.id && j.state === "running") {
    const p = $("#dActions .btn-play span"); if (p) p.textContent = `${j.action === "install" ? "Installing" : "Removing"}… ${j.percent}%`;
  }
}

/* ---------- pages ---------- */
function go(page, focus = true) {
  if (!PAGES.includes(page)) return;
  closeDetails();
  state.page = page;
  document.body.classList.toggle("on-home", page === "home");
  $$("#nav button").forEach((b) => b.classList.toggle("active", b.dataset.page === page));
  $$(".page").forEach((p) => p.classList.toggle("active", p.id === "page-" + page));
  blip(600, 0.04);
  if (page === "performance") renderPerf();
  if (page === "home") showHero(state.hero);
  if (focus) {
    const target = page === "home" ? $$("#row .tile")[state.rowSel] : page === "search" ? $("#searchBox") : firstIn($("#page-" + page));
    if (target) target.focus({ preventScroll: true });
  }
}
function firstIn(root) { return focusables().find((e) => root.contains(e)); }
function sortedLibrary() {
  const list = state.games.filter((g) => state.genre === "All" || g.genre === state.genre);
  if (state.sort === "recent") return list.sort((a, b) => (b.last - a.last) || a.name.localeCompare(b.name));
  if (state.sort === "most") return list.sort((a, b) => (b.count - a.count) || a.name.localeCompare(b.name));
  return list.sort((a, b) => a.name.localeCompare(b.name));
}
function renderLibrary() {
  const genres = ["All", ...new Set(state.games.map((g) => g.genre))];
  if (!genres.includes(state.genre)) state.genre = "All";
  const tabs = $("#genreTabs"); tabs.innerHTML = "";
  for (const name of genres) {
    const b = document.createElement("button");
    b.innerHTML = `${esc(name)} <i>${name === "All" ? state.games.length : state.games.filter((g) => g.genre === name).length}</i>`;
    b.classList.toggle("active", name === state.genre);
    b.onclick = () => { state.genre = name; renderLibrary(); blip(600); $$("#genreTabs button")[genres.indexOf(name)].focus(); };
    tabs.appendChild(b);
  }
  $("#btnSort").textContent = "Sort: " + { az: "A–Z", recent: "Recently played", most: "Most played" }[state.sort];
  const grid = $("#libGrid"); grid.innerHTML = "";
  sortedLibrary().forEach((g) => grid.appendChild(tile(g)));
  grid.appendChild(addTile());
}
function renderSearch() {
  const q = $("#searchBox").value.trim().toLowerCase();
  const hit = (x) => (x.name + " " + x.about + " " + x.genre).toLowerCase().includes(q);
  const grid = $("#searchGrid"); grid.innerHTML = "";
  const store = $("#searchStore"); store.innerHTML = "";
  if (!q) return;
  state.games.filter(hit).forEach((g) => grid.appendChild(tile(g)));
  state.store.filter((s) => !s.installed && hit(s)).forEach((s) => store.appendChild(storeCard(s)));
}
async function renderPerf() {
  const box = $("#perfCards");
  try {
    const s = state.sys = await api("/api/system");
    const cards = [
      ["Profile", s.profile || "–", `Desktop ${s.resolution || "–"} · smaller = more FPS`],
      ["Game graphics", s.graphics, "AirCursor toolbar → AirBoost → Graphics"],
      ["Steady FPS", s.fpsLimit ? `${s.fpsLimit} FPS` : "No limit", "Even frame pacing, cooler phone"],
      ["Memory free", `${s.memFreeGB} / ${s.memTotalGB} GB`, "Close unused apps before big games"],
      ["CPU cores", String(s.cores), "All cores draw the graphics"],
      ["Programs running", String(s.programs), s.programLimit === "off" ? "Android limit lifted" : "Android limit: about 32"],
      ["Storage free", `${s.storageFreeGB} GB`, "For games and projects"],
      ["FPS overlay", s.overlay ? "On" : "Off", "Right Shift + F12 in a game"],
    ];
    box.innerHTML = cards.map(([k, v, n]) => `<div class="card"><small>${esc(k)}</small><b>${esc(v)}</b><span>${esc(n)}</span></div>`).join("");
  } catch (_) { box.textContent = "Could not read the system."; }
}
function renderGames() { renderRow(); renderLibrary(); renderSearch(); }

/* ---------- sheet + toast ---------- */
function sheet(title, html, actions) {
  $("#sheetTitle").textContent = title; $("#sheetBody").innerHTML = html;
  const box = $("#sheetActions"); box.innerHTML = "";
  for (const [label, fn, primary] of actions) {
    const b = document.createElement("button"); b.className = "btn" + (primary ? " primary" : ""); b.textContent = label;
    b.onclick = async () => { closeSheet(); if (fn) await fn(); }; box.appendChild(b);
  }
  state.sheetBack = document.activeElement;
  $("#sheet").hidden = false;
  ($("#sheetActions .primary") || $("#sheetActions button")).focus();
}
function closeSheet() {
  if ($("#sheet").hidden) return;
  $("#sheet").hidden = true;
  if (state.sheetBack && state.sheetBack.isConnected) state.sheetBack.focus({ preventScroll: true });
}
let toastTimer;
function toast(msg) {
  const t = $("#toast"); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 3000);
}

/* ---------- hint bar ---------- */
function updateHints() {
  const pad = state.settings.padHints || state.padSeen;
  const k = (t, cls = "") => `<kbd class="${cls}">${t}</kbd>`;
  const items = pad
    ? [[k("✚", "pad"), "Navigate"], [k("A", "pad a"), "Select"], [k("B", "pad b"), "Back"], [k("X", "pad x"), "Details"],
       [k("Y", "pad y"), "Favourite"], [k("≡", "pad"), "Options"], [k("L1") + k("R1"), "Pages"]]
    : [[k("WASD"), "Navigate"], [k("↵"), "Activate"], [k("Esc"), "Back"], [k("F1"), "Details"], [k("O"), "Options"],
       [k("F"), "Favourite"], [k("Q") + k("E"), "Pages"]];
  $("#keys").innerHTML = items.map(([a, b]) => `<span>${a}${b}</span>`).join("");
}

/* ---------- moving the focus (keyboard, controller) ---------- */
function focusables() {
  let scope;
  if (!$("#sheet").hidden) scope = [$("#sheet")];
  else if (!$("#details").hidden) scope = [$("#details")];
  else scope = [$("#top"), $("#page-" + state.page)];
  const out = [];
  for (const root of scope) {
    for (const e of root.querySelectorAll("button, input")) {
      if (!e.disabled && e.getClientRects().length) out.push(e);
    }
  }
  return out;
}
function move(dir) {
  const all = focusables();
  const cur = document.activeElement;
  if (!all.includes(cur)) { defaultFocus(); return; }
  const r = cur.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  const horiz = dir === "left" || dir === "right";
  let best = null, bestScore = Infinity;
  for (const e of all) {
    if (e === cur) continue;
    const q = e.getBoundingClientRect();
    const qx = q.left + q.width / 2, qy = q.top + q.height / 2;
    let main;
    if (dir === "right") { if (qx <= cx + 1) continue; main = q.left - r.right; }
    else if (dir === "left") { if (qx >= cx - 1) continue; main = r.left - q.right; }
    else if (dir === "down") { if (qy <= cy + 1) continue; main = q.top - r.bottom; }
    else { if (qy >= cy - 1) continue; main = r.top - q.bottom; }
    const side = horiz ? Math.max(0, q.top - r.bottom, r.top - q.bottom) : Math.max(0, q.left - r.right, r.left - q.right);
    const score = Math.max(main, -8) + side * 4 + Math.abs(horiz ? qy - cy : qx - cx) * 0.05;
    if (score < bestScore) { bestScore = score; best = e; }
  }
  // Home: up from the shelf goes to Play; down from Play goes back to the chosen game.
  if (dir === "up" && cur.closest("#row") && !$("#btnPlay").disabled) best = $("#btnPlay");
  if (dir === "down" && (cur === $("#btnPlay") || cur === $("#btnMore"))) best = $$("#row .tile")[state.rowSel] || best;
  if (best) { focusEl(best); blip(740, 0.035, 0.025); }
}
function focusEl(e) {
  if (!e) return;
  e.focus({ preventScroll: true });
  e.scrollIntoView({ block: "nearest", inline: "nearest", behavior: state.settings.theme === "minimal" ? "auto" : "smooth" });
}
function defaultFocus() {
  if (!$("#sheet").hidden) { ($("#sheetActions .primary") || $("#sheetActions button")).focus(); return; }
  if (!$("#details").hidden) { ($("#dActions button:not([disabled])") || $("#dBack")).focus(); return; }
  focusEl(state.page === "home" ? $$("#row .tile")[state.rowSel] : firstIn($("#page-" + state.page)));
}
function focusedGame() {
  if (state.details && state.details.game) return state.details.game;
  const e = document.activeElement;
  const t = e && e.closest && e.closest(".tile");
  if (t && t.dataset.id) return gameById(t.dataset.id);
  return state.page === "home" ? state.hero : null;
}
function focusedStore() {
  const e = document.activeElement;
  const c = e && e.closest && e.closest(".sgame");
  return c ? storeById(c.dataset.sid) : null;
}
function activate() {
  const e = document.activeElement;
  if (!e || e === document.body) { defaultFocus(); return; }
  e.click();
}
function details() {
  if (state.details) return;
  const s = focusedStore();
  if (s) { openStoreDetails(s); return; }
  const g = focusedGame();
  if (g) openDetails(g);
}
function back() {
  if (!$("#sheet").hidden) { closeSheet(); return; }
  if (!$("#details").hidden) { closeDetails(); return; }
  if (state.page !== "home") { go("home"); return; }
  focusEl($$("#row .tile")[state.rowSel]);
}
function page(step) {
  if (!$("#sheet").hidden) return;
  const i = PAGES.indexOf(state.page);
  go(PAGES[(i + step + PAGES.length) % PAGES.length]);
}
function optionsKey() { const s = focusedStore(); if (s) openStoreDetails(s); else options(focusedGame()); }

/* ---------- keyboard (a controller on the phone arrives here as keys too) ---------- */
const KEYS = {
  ArrowLeft: () => move("left"), a: () => move("left"), ArrowRight: () => move("right"), d: () => move("right"),
  ArrowUp: () => move("up"), w: () => move("up"), ArrowDown: () => move("down"), s: () => move("down"),
  Enter: activate, Escape: back, Backspace: back, " ": details, F1: details, i: details,
  o: optionsKey, ContextMenu: optionsKey, f: () => toggleFav(focusedGame()),
  q: () => page(-1), "[": () => page(-1), PageUp: () => page(-1), e: () => page(1), "]": () => page(1), PageDown: () => page(1),
};
document.addEventListener("keydown", (e) => {
  if (document.body.classList.contains("booting")) { start(); e.preventDefault(); return; }
  const k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
  if (e.target.matches && e.target.matches("input:not(.switch)")) {   // typing
    if (k === "Escape") { e.preventDefault(); e.target.blur(); back(); }
    else if (k === "ArrowDown" || k === "Enter") { e.preventDefault(); move("down"); }
    else if (k === "ArrowUp") { e.preventDefault(); move("up"); }
    return;
  }
  if (e.ctrlKey || e.altKey || e.metaKey) return;
  const fn = KEYS[k];
  if (fn) { e.preventDefault(); fn(); }
});

/* ---------- controller seen by the browser itself (USB, or when the system passes it on) ---------- */
const PAD = { 0: activate, 1: back, 2: details, 3: () => toggleFav(focusedGame()), 4: () => page(-1), 5: () => page(1),
  8: details, 9: optionsKey, 12: () => move("up"), 13: () => move("down"), 14: () => move("left"), 15: () => move("right") };
let padPrev = {}, padRepeat = {}, padLoop = false;
function pollPads() {
  const pads = navigator.getGamepads ? [...navigator.getGamepads()].filter(Boolean) : [];
  if (!pads.length) { padLoop = false; return; }
  const now = performance.now(), down = {};
  for (const p of pads) {
    p.buttons.forEach((b, i) => { if (b.pressed) down[i] = true; });
    const [x = 0, y = 0] = p.axes;
    if (x < -0.55) down[14] = true;
    if (x > 0.55) down[15] = true;
    if (y < -0.55) down[12] = true;
    if (y > 0.55) down[13] = true;
  }
  for (const i of Object.keys(PAD)) {
    if (down[i] && !padPrev[i]) {
      if (document.body.classList.contains("booting")) start(); else PAD[i]();
      padRepeat[i] = now + 380;
    } else if (down[i] && i >= 12 && now > padRepeat[i]) { PAD[i](); padRepeat[i] = now + 120; }
  }
  padPrev = down;
  requestAnimationFrame(pollPads);
}
window.addEventListener("gamepadconnected", () => {
  state.padSeen = true; updateHints(); toast("Controller connected");
  if (!padLoop) { padLoop = true; requestAnimationFrame(pollPads); }
});

/* ---------- mouse, touch, hand cursor ---------- */
function start() {
  if (!document.body.classList.contains("booting")) return;
  document.body.classList.remove("booting");
  blip(440, 0.18, 0.05); setTimeout(() => blip(660, 0.22, 0.04), 120);
  if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(() => {});
  setTimeout(defaultFocus, 60);
}
$("#boot").addEventListener("click", start);
$("#nav").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) go(b.dataset.page); });
$$("[data-goto]").forEach((b) => (b.onclick = () => go(b.dataset.goto)));
$("#shelfTabs").addEventListener("click", (e) => {
  const b = e.target.closest("button"); if (!b) return;
  state.shelf = b.dataset.shelf; $$("#shelfTabs button").forEach((x) => x.classList.toggle("active", x === b));
  state.rowSel = 0; renderRow(); blip(600);
});
let pressedSelected = false;
$("#row").addEventListener("pointerdown", (e) => { const t = e.target.closest(".tile"); pressedSelected = !!(t && t.classList.contains("focus")); });
$("#row").addEventListener("focusin", (e) => {
  const t = e.target.closest(".tile"); if (!t) return;
  const i = $$("#row .tile").indexOf(t);
  if (i !== state.rowSel || !t.classList.contains("focus")) selectRow(i, false);
});
$("#row").addEventListener("click", (e) => {
  const t = e.target.closest(".tile"); if (!t) return;
  if (t.dataset.add) { go("store"); return; }
  if (e.detail === 0 || pressedSelected) play(gameById(t.dataset.id));   // Enter / A plays; tap = select, tap again = play
  else t.focus({ preventScroll: true });
  pressedSelected = false;
});
function gridClick(e) {
  const t = e.target.closest(".tile"); if (!t) return;
  if (t.dataset.add) { go("store"); return; }
  const g = gameById(t.dataset.id);
  if (e.detail === 0) play(g); else openDetails(g);
}
$("#libGrid").addEventListener("click", gridClick);
$("#searchGrid").addEventListener("click", gridClick);
function storeClick(e) { const c = e.target.closest(".sgame"); if (c) openStoreDetails(storeById(c.dataset.sid)); }
$("#storeGrid").addEventListener("click", storeClick);
$("#searchStore").addEventListener("click", storeClick);
$("#storeGrid").addEventListener("focusin", (e) => { const c = e.target.closest(".sgame"); if (c) storeHero(storeById(c.dataset.sid)); });
$("#rowPrev").onclick = () => { selectRow(state.rowSel - 1); focusEl($$("#row .tile")[state.rowSel]); };
$("#rowNext").onclick = () => { selectRow(state.rowSel + 1); focusEl($$("#row .tile")[state.rowSel]); };
$("#btnPlay").onclick = () => play(state.hero);
$("#btnMore").onclick = () => (state.hero ? openDetails(state.hero) : go("store"));
$("#dBack").onclick = closeDetails;
$("#btnSort").onclick = () => { state.sort = { az: "recent", recent: "most", most: "az" }[state.sort]; renderLibrary(); blip(600); $("#btnSort").focus(); };
$("#searchBox").addEventListener("input", renderSearch);
$("#sheet").addEventListener("click", (e) => { if (e.target.id === "sheet") closeSheet(); });
$("#btnAdd").onclick = addGame;
$("#btnRescan").onclick = async () => { await load(false); toast(`${state.games.length} games found`); };
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
$("#setPad").addEventListener("change", async (e) => { state.settings = await api("/api/settings", { padHints: e.target.checked }); updateHints(); });
$("#btnFull").onclick = () => {
  const p = document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen();
  if (p) p.catch(() => {});
};
$("#btnQuit").onclick = () => sheet("Exit TS Station?", "<p>Your games and settings stay saved.</p>", [["Cancel", null], ["Exit", async () => {
  try { await api("/api/quit", {}); } catch (_) { /* already closed */ }
  document.body.innerHTML = '<div style="display:grid;place-items:center;height:100vh;color:#888;font:14px Inter,sans-serif">TS Station closed – you can close this window.</div>';
  window.close();
}, true]]);

/* ---------- settings + theme ---------- */
function applyTheme(name) {
  document.body.className = document.body.className.replace(/theme-\S+/g, "").trim() + " theme-" + name;
  $$(".theme-card").forEach((c) => c.classList.toggle("active", c.dataset.theme === name));
}
function applyProfile() {
  const n = (state.settings.name || "Player").trim() || "Player";
  $("#avatar").textContent = n.slice(0, 1).toUpperCase();
  $("#avatar").title = n;
  $("#setName").value = state.settings.name || "";
  $("#setSounds").checked = !!state.settings.sounds;
  $("#setPad").checked = !!state.settings.padHints;
}
function tick() { $("#clock").textContent = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }

async function load(resetFocus = true) {
  const [{ games }, settings, store, sys] = await Promise.all([
    api("/api/games"), api("/api/settings"), api("/api/store"), api("/api/system").catch(() => ({})),
  ]);
  state.games = games; state.settings = settings; state.store = store.games; state.job = store.job; state.sys = sys;
  applyTheme(settings.theme || "native"); applyProfile(); updateHints();
  if (resetFocus) state.rowSel = 0;
  const hadFocus = document.activeElement && document.activeElement !== document.body;
  renderGames(); renderStore();
  if (state.job && state.job.state === "running" && state.job.id !== "ts-station") watchJob();
  if (hadFocus && !document.activeElement.isConnected) defaultFocus();
  if (hadFocus && document.activeElement === document.body && !document.body.classList.contains("booting")) defaultFocus();
}
tick(); setInterval(tick, 15000);
setTimeout(() => checkVersion(true), 4000);
load().catch(() => {
  setLogo($("#heroTitle"), "Start TS Station from its desktop icon");
  document.body.classList.remove("booting");
});
