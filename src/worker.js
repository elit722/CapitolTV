/* =====================================================================
   Capitol TV — Worker Cloudflare
   - Connexion Discord obligatoire (OAuth2) + session signée (cookie)
   - Durable Object « Game » : état des Jeux, alertes, équipe, présence,
     bannissements, différé de diffusion, statut Twitch (alarme)
   ===================================================================== */
import { DurableObject } from "cloudflare:workers";

const COOKIE = "ctv_s";
const STATE_COOKIE = "ctv_state";
const SESSION_DAYS = 7;
const enc = new TextEncoder();
const dec = new TextDecoder();

/* ---------- utilitaires ---------- */
const b64u = buf => {
  let s = "";
  const a = new Uint8Array(buf);
  for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
const fromB64u = s => {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  return Uint8Array.from(bin, c => c.charCodeAt(0));
};
const hmacKey = secret => crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);

export async function signSession(payload, secret) {
  const body = b64u(enc.encode(JSON.stringify(payload)));
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(secret), enc.encode(body));
  return body + "." + b64u(sig);
}
async function readSession(req, env) { return verifyToken(getCookie(req, COOKIE), env); }
async function verifyToken(raw, env) {
  if (!raw || !env.SESSION_SECRET) return null;
  const [body, sig] = raw.split(".");
  if (!body || !sig) return null;
  try {
    const ok = await crypto.subtle.verify("HMAC", await hmacKey(env.SESSION_SECRET), fromB64u(sig), enc.encode(body));
    if (!ok) return null;
    const p = JSON.parse(dec.decode(fromB64u(body)));
    if (!p.exp || p.exp < Date.now()) return null;
    return p;
  } catch (e) { return null; }
}
function getCookie(req, name) {
  const h = req.headers.get("Cookie") || "";
  for (const part of h.split(/;\s*/)) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i) === name) return decodeURIComponent(part.slice(i + 1));
  }
  return null;
}
const setCookie = (name, value, maxAge) => `${name}=${encodeURIComponent(value)}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${maxAge}`;
const redirect = (to, cookies = []) => {
  const h = new Headers({ Location: to, "Cache-Control": "no-store" });
  cookies.forEach(c => h.append("Set-Cookie", c));
  return new Response(null, { status: 302, headers: h });
};
const avatarUrl = u => u.avatar
  ? `https://cdn.discordapp.com/avatars/${u.id}/${u.avatar}.${u.avatar.startsWith("a_") ? "gif" : "png"}?size=128`
  : `https://cdn.discordapp.com/embed/avatars/${Number((BigInt(u.id) >> 22n) % 6n)}.png`;

/* ---------- Worker (routage + authentification) ---------- */
const PUBLIC_PATHS = new Set(["/login", "/login.html", "/logo.png", "/emblem.png", "/favicon.ico"]);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      if (p === "/auth/login") return discordLogin(env, url);
      if (p === "/auth/callback") return discordCallback(req, env, url);
      if (p === "/auth/logout") return redirect("/login", [setCookie(COOKIE, "", 0)]);
      if (p.startsWith("/overlay/")) return overlayLogin(env, p.slice(9));
      if (p.startsWith("/failover/")) return failoverRoute(req, env, url);
      // site de secours activé : tout le monde (site, mods, serveurs, OBS) est renvoyé vers le nouveau site
      const mv = await movedTo(env);
      if (mv && p !== "/ws" && p !== "/mod/ws") {
        if (p.startsWith("/whip/")) return req.method === "GET" ? Response.json({ moved: mv }, { headers: WHIP_CORS }) : Response.json({ moved: mv }, { status: 409, headers: WHIP_CORS });
        if (p.startsWith("/api/")) return Response.json({ moved: mv, msg: "Le site a déménagé : " + mv }, { status: 409 });
        const c = getCookie(req, COOKIE);
        return redirect(c ? `${mv}/failover/session?c=${encodeURIComponent(c)}&to=${encodeURIComponent(p + url.search)}` : mv + p + url.search);
      }
      if (PUBLIC_PATHS.has(p)) return env.ASSETS.fetch(req);
      if (p.startsWith("/whip/")) {
        // diffusion vers Capitol TV (mod Minecraft ou OBS) : la clé de diffusion fait office d'accès
        if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: WHIP_CORS });
        return env.GAME.getByName("main").fetch(new Request(new URL(p + new URL(req.url).search, "https://game"), { method: req.method, headers: req.headers, body: req.method === "POST" || req.method === "PATCH" ? req.body : null }));
      }

      let user = await readSession(req, env);
      let guestCookie = null;
      if (!user && (p === "/ws" || p === "/" || p === "/index.html")) {
        // accès sans compte, si la régie l'a ouvert
        const open = await env.GAME.getByName("main").fetch("https://game/public").then(r => r.json()).then(j => !!j.open).catch(() => false);
        if (open) {
          let gid = getCookie(req, "ctv_g");
          if (!gid || !/^[a-z0-9]{12,32}$/.test(gid)) { gid = crypto.randomUUID().replace(/-/g, "").slice(0, 20); guestCookie = setCookie("ctv_g", gid, 30 * 86400); }
          user = { id: "g_" + gid, name: "Invité", av: "", guest: true, exp: Date.now() + 864e5 };
        }
      }
      // hymne de l'hommage : lu aussi par le mod en jeu (sans compte), seulement le son choisi
      // images des projections dans le ciel (lues par le mod en jeu, sans compte)
      if (p.startsWith("/skyimg/")) {
        const k = p.slice(8).replace(/[^a-f0-9]/g, "").slice(0, 32);
        const b = k && env.MEDIA ? await env.MEDIA.get("skyimg:" + k, "arrayBuffer") : null;
        return b ? new Response(b, { headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=86400" } }) : new Response("Image inconnue", { status: 404 });
      }
      if (p === "/hymn") return env.GAME.getByName("main").fetch(new Request(new URL("/hymn", "https://game"), { headers: req.headers }));
      if (p === "/mod/ws") {
        if (req.headers.get("Upgrade") !== "websocket") return new Response("WebSocket attendu", { status: 426 });
        const key = req.headers.get("X-CTV-Server-Key");
        let who;
        if (key) who = { server: true, key: String(key).slice(0, 100) };
        else {
          const tok = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
          const u = await verifyToken(tok, env);
          if (!u || !u.mod) return new Response("Jeton du mod invalide ou expiré", { status: 401 });
          who = { user: u };
        }
        const h = new Headers(req.headers);
        h.set("X-CTV-Mod", JSON.stringify(who));
        return env.GAME.getByName("main").fetch(new Request(new URL("/mod", url), { headers: h, method: "GET" }));
      }
      if (p === "/ws") {
        if (!user) return new Response("Connexion requise", { status: 401 });
        if (req.headers.get("Upgrade") !== "websocket") return new Response("WebSocket attendu", { status: 426 });
        const h = new Headers(req.headers);
        h.set("X-CTV-User", JSON.stringify(user));
        return env.GAME.getByName("main").fetch(new Request(req, { headers: h }));
      }
      if (!user) {
        const accept = req.headers.get("Accept") || "";
        if (req.method === "GET" && (accept.includes("text/html") || p === "/")) return env.ASSETS.fetch(new Request(new URL("/login", url)));
        return new Response("Connexion requise", { status: 401 });
      }
      if (p.startsWith("/media/")) return serveMedia(req, env, p.slice(7));
      if (p === "/twitchbot/start" || p === "/twitchbot/cb") {
        const h = new Headers(); h.set("X-CTV-User", JSON.stringify(user)); h.set("X-CTV-Origin", url.origin);
        return env.GAME.getByName("main").fetch(new Request(new URL(p + url.search, "https://game"), { headers: h }));
      }
      if (p === "/dvr/up" && req.method === "PUT") return env.GAME.getByName("main").fetch(new Request(new URL(p + url.search, "https://game"), { method: "PUT", headers: req.headers, body: await req.arrayBuffer() }));
      if (p.startsWith("/dvr/s/") && req.method === "GET") {
        // segments du différé partagé (si le bucket public n'est pas configuré) : réservé à l'équipe et à OBS
        if (!dvrStore(env)) return new Response("Indisponible", { status: 404 });
        const o = await dvrStore(env).get(decodeURIComponent(p.slice(7)));
        if (!o) return new Response("Introuvable", { status: 404 });
        return new Response(o.body, { headers: { "Content-Type": o.httpMetadata && o.httpMetadata.contentType || "video/webm", "Cache-Control": "private, max-age=3600" } });
      }
      if (p.startsWith("/terrain/")) return env.GAME.getByName("main").fetch(new Request(new URL(p, "https://game"), { method: "GET" }));
      if (p.startsWith("/clip/")) {
        const h = new Headers(req.headers); h.set("X-CTV-User", JSON.stringify(user));
        return env.GAME.getByName("main").fetch(new Request(new URL(p, "https://game"), { method: "GET", headers: h }));
      }
      if (p.startsWith("/api/media") || p.startsWith("/api/rtc") || p.startsWith("/api/clips")) {
        if (p.startsWith("/api/media") && (user.guest || user.overlay)) return new Response("Réservé à l'équipe", { status: 403 });
        const h = new Headers(req.headers);
        h.set("X-CTV-User", JSON.stringify(user));
        return env.GAME.getByName("main").fetch(new Request(new URL(p + url.search, "https://game"), { method: req.method, headers: h, body: req.method === "GET" || req.method === "HEAD" ? null : req.body }));
      }
      const res = await env.ASSETS.fetch(req);
      if ((res.headers.get("Content-Type") || "").includes("text/html")) {
        const r = new Response(res.body, res);
        r.headers.set("Cache-Control", "no-store");
        if (guestCookie) r.headers.append("Set-Cookie", guestCookie);
        return r;
      }
      return res;
    } catch (e) {
      return new Response("Erreur du serveur : " + e.message, { status: 500 });
    }
  }
};

/* ---------- médias préenregistrés (stockés en morceaux dans KV) ---------- */
const MEDIA_CHUNK = 8 * 1024 * 1024;
/* niveaux de qualité des flux natifs (mod / OBS) : le joueur choisit, la régie fixe un plafond */
const VQ_LEVELS = {
  off: { h: 0, kbps: 0, fps: 0 },
  eco: { h: 480, kbps: 2000, fps: 30 },
  std: { h: 720, kbps: 4500, fps: 30 },
  hd: { h: 1080, kbps: 7000, fps: 30 },
  max: { h: 1080, kbps: 10000, fps: 60 }
};
const MAX_ITEMS = 30;
const NOW_KINDS = ["flash", "gift", "tribute"];
const cleanBgm = b => ({ ids: (Array.isArray(b && b.ids) ? b.ids : []).map(x => String(x).replace(/[^a-z0-9]/gi, "").slice(0, 40)).filter(Boolean).slice(0, 40), vol: Math.max(0, Math.min(100, Math.round(+(b && b.vol) || 60))) });
const VQ_ORDER = ["eco", "std", "hd", "max"];
const WHIP_CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, DELETE, PATCH, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, Authorization", "Access-Control-Expose-Headers": "Location" };
const RTC_API = "https://rtc.live.cloudflare.com/v1/apps/";
async function serveMedia(req, env, id) {
  if (!env.MEDIA) return new Response("Stockage des médias non configuré", { status: 503 });
  id = String(id).replace(/[^a-z0-9]/gi, "").slice(0, 40);
  let meta = await env.MEDIA.get("meta:" + id, "json");
  if (!meta) { const b = await pullFrom(env, "meta:" + id); if (b) try { meta = JSON.parse(new TextDecoder().decode(b)); } catch (e) {} }
  if (!meta) return new Response("Média introuvable", { status: 404 });
  const size = meta.size, cs = meta.cs || MEDIA_CHUNK;
  let start = 0, end = size - 1, status = 200;
  const rg = /bytes=(\d*)-(\d*)/.exec(req.headers.get("Range") || "");
  if (rg && (rg[1] || rg[2])) {
    if (rg[1]) { start = +rg[1]; end = rg[2] ? Math.min(+rg[2], size - 1) : size - 1; }
    else { start = Math.max(0, size - +rg[2]); }
    if (start > end || start >= size) return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${size}` } });
    status = 206;
  }
  const h = new Headers({ "Content-Type": meta.mime || "application/octet-stream", "Accept-Ranges": "bytes", "Content-Length": String(end - start + 1), "Cache-Control": "private, max-age=3600" });
  if (status === 206) h.set("Content-Range", `bytes ${start}-${end}/${size}`);
  if (req.method === "HEAD") return new Response(null, { status, headers: h });
  const first = Math.floor(start / cs), last = Math.floor(end / cs);
  const { readable, writable } = new FixedLengthStream(end - start + 1);
  (async () => {
    const w = writable.getWriter();
    try {
      for (let n = first; n <= last; n++) {
        const buf = (await env.MEDIA.get(`c:${id}:${n}`, "arrayBuffer")) || (await pullFrom(env, `c:${id}:${n}`));
        if (!buf) throw new Error("morceau manquant");
        const a = n === first ? start - n * cs : 0, b = n === last ? end - n * cs + 1 : buf.byteLength;
        await w.write(new Uint8Array(buf, a, b - a));
      }
      await w.close();
    } catch (e) { try { await w.abort(e); } catch (x) {} }
  })();
  return new Response(readable, { status, headers: h });
}

/* ---------- site de secours (2e compte Cloudflare) ---------- */
const FO_SECRET = env => env.FAILOVER_SECRET || env.SESSION_SECRET || "";
let MOVED = { url: "", t: 0 };
async function movedTo(env) {
  if (Date.now() - MOVED.t < 10000) return MOVED.url;
  try { const j = await env.GAME.getByName("main").fetch("https://game/moved").then(r => r.json()); MOVED = { url: j.url || "", t: Date.now() }; }
  catch (e) { MOVED.t = Date.now(); }
  return MOVED.url;
}
async function failoverRoute(req, env, url) {
  const p = url.pathname;
  if (p === "/failover/session") {
    // même SESSION_SECRET sur les deux sites : la session reste valable, personne n'a à se reconnecter
    const c = url.searchParams.get("c") || "", to = url.searchParams.get("to") || "/";
    const u = await verifyToken(c, env);
    const dest = to.startsWith("/") && !to.startsWith("//") ? to : "/";
    return u ? redirect(dest, [setCookie(COOKIE, c, Math.max(60, Math.round((u.exp - Date.now()) / 1000)))]) : redirect(dest);
  }
  const sec = FO_SECRET(env);
  if (!sec || req.headers.get("X-CTV-Failover") !== sec) return new Response("Accès refusé", { status: 403 });
  if (p === "/failover/kv") {
    const k = url.searchParams.get("k") || "";
    if (!env.MEDIA || !/^(meta|c):[a-z0-9:]+$/i.test(k)) return new Response(null, { status: 404 });
    const v = await env.MEDIA.get(k, "arrayBuffer"); return v ? new Response(v) : new Response(null, { status: 404 });
  }
  if (p === "/failover/clip") {
    const k = url.searchParams.get("k") || "";
    if (!env.CLIPS || !/^clip\/[a-z0-9]+\/\d+$/i.test(k)) return new Response(null, { status: 404 });
    const o = await env.CLIPS.get(k); return o ? new Response(o.body) : new Response(null, { status: 404 });
  }
  return env.GAME.getByName("main").fetch(new Request(new URL(p, "https://game"), { method: req.method, headers: req.headers, body: req.method === "POST" ? req.body : null }));
}
/* média absent du site de secours : on le reprend au site d'origine, une fois */
async function pullFrom(env, k) {
  try {
    const j = await env.GAME.getByName("main").fetch("https://game/moved").then(r => r.json());
    if (!j.from) return null;
    const r = await fetch(j.from + "/failover/kv?k=" + encodeURIComponent(k), { headers: { "X-CTV-Failover": FO_SECRET(env) } });
    if (!r.ok) return null;
    const buf = await r.arrayBuffer(); await env.MEDIA.put(k, buf); return buf;
  } catch (e) { return null; }
}

function discordLogin(env, url) {
  if (!env.DISCORD_CLIENT_ID || !env.DISCORD_CLIENT_SECRET || !env.SESSION_SECRET)
    return new Response("Configuration incomplète : DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET et SESSION_SECRET sont requis (voir README).", { status: 500 });
  const state = crypto.randomUUID();
  const auth = new URL("https://discord.com/oauth2/authorize");
  auth.search = new URLSearchParams({
    client_id: env.DISCORD_CLIENT_ID,
    response_type: "code",
    redirect_uri: url.origin + "/auth/callback",
    scope: env.DISCORD_GUILD_ID ? "identify guilds.members.read" : "identify",
    state,
    prompt: "none"
  }).toString();
  return redirect(auth.toString(), [setCookie(STATE_COOKIE, state, 600)]);
}

async function discordCallback(req, env, url) {
  const fail = e => redirect("/login?e=" + e, [setCookie(STATE_COOKIE, "", 0)]);
  if (url.searchParams.get("error")) return fail("denied");
  const code = url.searchParams.get("code"), state = url.searchParams.get("state");
  if (!code || !state || state !== getCookie(req, STATE_COOKIE)) return fail("state");

  const tr = await fetch("https://discord.com/api/v10/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.DISCORD_CLIENT_ID,
      client_secret: env.DISCORD_CLIENT_SECRET,
      grant_type: "authorization_code",
      code,
      redirect_uri: url.origin + "/auth/callback"
    })
  });
  const tok = await tr.json().catch(() => ({}));
  if (!tok.access_token) return fail("token");
  const auth = { Authorization: "Bearer " + tok.access_token };

  const me = await fetch("https://discord.com/api/v10/users/@me", { headers: auth }).then(r => r.json()).catch(() => null);
  if (!me || !me.id) return fail("token");

  if (env.DISCORD_GUILD_ID) {
    const m = await fetch(`https://discord.com/api/v10/users/@me/guilds/${env.DISCORD_GUILD_ID}/member`, { headers: auth });
    if (!m.ok) return fail("guild");
  }

  const user = { id: me.id, name: String(me.global_name || me.username || "Citoyen").slice(0, 32), av: avatarUrl(me), exp: Date.now() + SESSION_DAYS * 864e5 };
  const val = await signSession(user, env.SESSION_SECRET);
  return redirect("/", [setCookie(COOKIE, val, SESSION_DAYS * 86400), setCookie(STATE_COOKIE, "", 0)]);
}

async function overlayLogin(env, key) {
  if (!env.OVERLAY_KEY || !env.SESSION_SECRET || key !== env.OVERLAY_KEY) return new Response("Clé d'overlay invalide", { status: 403 });
  const days = 365;
  const val = await signSession({ id: "overlay", name: "Overlay OBS", av: "", overlay: true, exp: Date.now() + days * 864e5 }, env.SESSION_SECRET);
  return redirect("/#overlay-direct", [setCookie(COOKIE, val, days * 86400)]);
}

/* =====================================================================
   Durable Object : la régie centrale
   ===================================================================== */
const TYPES = ["Combat", "Alliance", "Trahison", "Mort imminente", "Cadeau sponsor", "Moment drôle", "Révélation", "Autre"];
const SCALARS = ["ticker", "don", "fund", "day", "startTs", "cooldown", "lastHommage", "flash", "delay", "hideChat", "lock", "sponsor", "publicAccess", "map", "theme", "autoCanon", "tick", "skyHommage", "hymn"];
const STATUSES = ["alive", "injured", "missing", "dead"];
const PERMS = ["admin", "mod", "member"];
const LOGIN_RE = /^[a-z0-9_]{3,25}$/;
const cleanLogin = s => { s = String(s || "").toLowerCase(); return LOGIN_RE.test(s) ? s : null; };
const isStaff = a => a && (a.perm === "admin" || a.perm === "mod");
const CAMROLES = ["cam", "chef"];
/* différé partagé : bucket dédié s'il existe, sinon le bucket des clips (préfixe dvr/) */
function dvrStore(env) {
  if (env.DVR) return env.DVR;
  const b = env.CLIPS; if (!b) return null;
  return { put: (k, v, o) => b.put("dvr/" + k, v, o), get: k => b.get("dvr/" + k), delete: k => b.delete(Array.isArray(k) ? k.map(x => "dvr/" + x) : "dvr/" + k),
    list: async o => { const r = await b.list({ ...o, prefix: "dvr/" + (o.prefix || "") }); return { ...r, objects: r.objects.map(x => ({ ...x, key: x.key.slice(4) })) }; } };
}
/* volume (en %) d'une entrée du mixeur à l'instant t, fondu compris */
/* SoundCloud : identifiant client public (lu sur le site, gardé en cache), recherche et liens collés */
async function scClientId(env, force) {
  if (!force) { const c = await env.MEDIA.get("sc:cid").catch(() => null); if (c) return c; }
  const UA = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" };
  const h = await (await fetch("https://soundcloud.com/", { headers: UA })).text();
  const urls = [...h.matchAll(/<script[^>]+src="(https:\/\/a-v2\.sndcdn\.com\/assets\/[^"]+\.js)"/g)].map(m => m[1]).reverse();
  for (const u of urls.slice(0, 8)) { const js = await (await fetch(u, { headers: UA })).text(); const m = js.match(/client_id\s*[:=]\s*"([a-zA-Z0-9]{32})"/); if (m) { await env.MEDIA.put("sc:cid", m[1], { expirationTtl: 86400 }).catch(() => {}); return m[1]; } }
  throw new Error("client_id introuvable");
}
async function scSearch(env, q, retry = true) {
  const cid = await scClientId(env, !retry);
  const link = /^https?:\/\/(www\.|m\.|on\.)?soundcloud\.com\//i.test(q);
  const u = link ? "https://api-v2.soundcloud.com/resolve?url=" + encodeURIComponent(q) + "&client_id=" + cid : "https://api-v2.soundcloud.com/search/tracks?limit=20&q=" + encodeURIComponent(q.replace(/^art(iste)?\s*:/i, "")) + "&client_id=" + cid;
  const r = await fetch(u, { headers: { Accept: "application/json" } });
  if ((r.status === 401 || r.status === 403) && retry) return scSearch(env, q, false);
  if (!r.ok) throw new Error("SoundCloud " + r.status);
  const d = await r.json(), arr = link ? (d.kind === "track" ? [d] : (d.tracks || [])) : (d.collection || []);
  return arr.filter(t => t && t.kind === "track" && t.streamable !== false && t.policy !== "BLOCK" && t.policy !== "SNIP").slice(0, 15).map(t => ({
    sc: String(t.id), title: String(t.title || "").slice(0, 100), creator: String((t.publisher_metadata && t.publisher_metadata.artist) || (t.user || {}).username || "").slice(0, 60),
    dur: Math.round((t.full_duration || t.duration || 0) / 1000), cover: String(t.artwork_url || (t.user || {}).avatar_url || ""), url: String(t.permalink_url || "") }));
}
/* hymne joué pendant l'hommage (Direct + en jeu) : un son des médias (OGG conseillé : lu aussi par le jeu) */
function normHymn(h) { h = h && typeof h === "object" ? h : {}; return { on: h.on !== false, vol: Math.max(0, Math.min(100, Math.round(h.vol == null ? 70 : +h.vol || 0))), id: String(h.id || "").replace(/[^a-z0-9]/gi, "").slice(0, 40) }; }
/* SoundCloud : flux audio d'un titre (MP3 direct si possible, sinon segments HLS mis bout à bout) */
async function scTrack(env, sid, retry = true) {
  const cid = await scClientId(env, !retry);
  const r = await fetch("https://api-v2.soundcloud.com/tracks/" + sid + "?client_id=" + cid, { headers: { Accept: "application/json" } });
  if ((r.status === 401 || r.status === 403) && retry) return scTrack(env, sid, false);
  if (!r.ok) throw new Error("SoundCloud " + r.status);
  return { t: await r.json(), cid };
}
async function scSource(env, sid) {
  const { t, cid } = await scTrack(env, sid);
  const tcs = ((t.media || {}).transcodings || []).filter(x => x && x.url && x.format && !/encrypted/.test(x.format.protocol || "") && !x.snipped);
  const rank = x => (x.format.protocol === "progressive" ? 0 : 10) + (/mpeg/.test(x.format.mime_type) ? 0 : /mp4|aac/.test(x.format.mime_type) ? 1 : 5);
  tcs.sort((a, b) => rank(a) - rank(b));
  const tc = tcs[0];
  if (!tc || rank(tc) >= 15) throw new Error("Ce titre n'est pas lisible hors de SoundCloud");
  const j = await fetch(tc.url + (tc.url.includes("?") ? "&" : "?") + "client_id=" + cid + (t.track_authorization ? "&track_authorization=" + t.track_authorization : "")).then(r => r.ok ? r.json() : null);
  if (!j || !j.url) throw new Error("Flux SoundCloud refusé");
  const mime = /mpeg/.test(tc.format.mime_type) ? "audio/mpeg" : /ogg|opus/.test(tc.format.mime_type) ? "audio/ogg" : "audio/mp4";
  const dur = Math.round((t.full_duration || t.duration || 0) / 1000);
  if (tc.format.protocol === "progressive") return { mime, dur, urls: [j.url], prog: true };
  const m3 = await fetch(j.url).then(r => r.ok ? r.text() : "");
  const urls = [];
  for (const line of m3.split("\n").map(x => x.trim())) {
    const mp = line.match(/^#EXT-X-MAP:.*URI="([^"]+)"/); if (mp) { urls.push(new URL(mp[1], j.url).toString()); continue; }
    if (line && !line.startsWith("#")) urls.push(new URL(line, j.url).toString());
  }
  if (!urls.length) throw new Error("Flux SoundCloud vide");
  return { mime, dur, urls, prog: false };
}
/* YouTube : identifiant d'une vidéo dans un lien (ou l'identifiant seul) */
function ytId(s) {
  s = String(s || "").trim();
  if (/^[\w-]{11}$/.test(s) && /[A-Z0-9_-]/.test(s) && /[a-z]/i.test(s)) return s;
  const m = s.match(/(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/|music\.youtube\.com\/watch\?(?:.*&)?v=)([\w-]{11})/i);
  return m ? m[1] : "";
}
const ytDur = t => String(t || "").split(":").reduce((a, x) => a * 60 + (parseInt(x, 10) || 0), 0);
async function ytScrape(q) {
  const r = await fetch("https://www.youtube.com/results?hl=fr&gl=FR&sp=EgIQAQ%253D%253D&search_query=" + encodeURIComponent(q), {
    headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36", "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8", Cookie: "CONSENT=YES+cb; SOCS=CAI" } });
  if (!r.ok) return [];
  const h = await r.text(), i = h.indexOf("ytInitialData"); if (i < 0) return [];
  const st = h.indexOf("{", i); let d = 0, e = st, inS = false, esc = false;
  for (; e < h.length; e++) { const c = h[e]; if (inS) { if (esc) esc = false; else if (c === "\\") esc = true; else if (c === '"') inS = false; continue; } if (c === '"') inS = true; else if (c === "{") d++; else if (c === "}" && --d === 0) break; }
  let data; try { data = JSON.parse(h.slice(st, e + 1)); } catch (x) { return []; }
  const out = [], walk = o => { if (!o || typeof o !== "object" || out.length >= 15) return; if (o.videoRenderer && o.videoRenderer.videoId) { const v = o.videoRenderer; out.push({ yt: v.videoId, title: String(((v.title || {}).runs || [{}])[0].text || "").slice(0, 100), creator: String(((v.ownerText || v.longBylineText || {}).runs || [{}])[0].text || "").slice(0, 60), dur: ytDur((v.lengthText || {}).simpleText) }); return; } for (const k in o) walk(o[k]); };
  walk(data);
  return out.filter(x => x.dur > 0 && x.dur < 3 * 3600);
}
async function ytApiSearch(q, key) {
  const r = await fetch("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=15&q=" + encodeURIComponent(q) + "&key=" + key);
  const j = await r.json(); const ids = (j.items || []).map(x => x.id.videoId).filter(Boolean); if (!ids.length) return [];
  const d = await (await fetch("https://www.googleapis.com/youtube/v3/videos?part=contentDetails,snippet&id=" + ids.join(",") + "&key=" + key)).json();
  const iso = s => { const m = String(s || "").match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/) || []; return (+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0); };
  return (d.items || []).map(v => ({ yt: v.id, title: String(v.snippet.title || "").slice(0, 100), creator: String(v.snippet.channelTitle || "").slice(0, 60), dur: iso(v.contentDetails.duration) }));
}
function mixAt(e, t = Date.now()) {
  if (!e) return 100;
  if (!e.dur || t >= e.at + e.dur) return e.v;
  return e.v0 + (e.v - e.v0) * Math.max(0, (t - e.at) / e.dur);
}
function hash32(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const rid8 = () => crypto.randomUUID().replace(/-/g, "").slice(0, 10);
const isCrew = a => !!a && !a.guest && !a.overlay && !a.server && (isStaff(a) || CAMROLES.includes(a.cam) || !!a.team);
const hasPerm = (a, k) => !!a && (a.perm === "admin" || (Array.isArray(a.perms) && a.perms.includes(k)));
const isSup = a => !!a && (isStaff(a) || a.cam === "chef" || hasPerm(a, "program"));
/* rôles du staff : chaque membre a un rôle, chaque rôle donne des permissions */
const ROLE_PERMS = ["admin", "mod", "program", "roles", "chef", "cam", "special"];
const defaultRoles = () => [
  { id: "org", name: "Organisation", color: "#d6b261", perms: ["admin"], locked: true },
  { id: "real", name: "Réalisation", color: "#e0514a", perms: ["program", "mod", "special", "chef"] },
  { id: "modo", name: "Modération", color: "#5aa0e6", perms: ["mod"] },
  { id: "chefcam", name: "Responsable caméra", color: "#b07ce8", perms: ["chef", "cam", "special"] },
  { id: "cam", name: "Caméraman", color: "#5fbf8a", perms: ["cam"] },
  { id: "equipe", name: "Équipe", color: "#9a9284", perms: [] }
];
const SLUG = s => String(s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 24);
const defaultBoard = () => ({
  tributes: [], ticker: "", don: { label: "Sponsoriser", url: "" }, fund: { raised: 0, goal: 0, cur: "€" },
  day: 0, startTs: null, cooldown: 20, lastHommage: 0, events: [], flash: null, delay: 0, hideChat: true, publicAccess: false, autoCanon: true, skyHommage: true,
  fund: { raised: 0, goal: 0, cur: "€", show: true }, fundLog: [],
  lock: { mode: "off", tz: "Europe/Paris", windows: [], msg: "" },
  sponsor: { url: "", label: "Sponsoriser un tribut", text: "", partners: [] },
  map: { on: false, staffOnly: true }
});


/* ---------- textes des actes annonciateurs ---------- */
const CAUSE_FR = {
  player: "coups", mob: "une créature", mobAttackNoAggro: "une créature", arrow: "une flèche", trident: "un trident", thrown: "un projectile",
  mobProjectile: "un projectile", fall: "une chute", lava: "la lave", inFire: "le feu", onFire: "les flammes", campfire: "un feu de camp",
  hotFloor: "le magma", drown: "la noyade", explosion: "une explosion", "explosion.player": "une explosion", fireworks: "un feu d'artifice",
  magic: "la magie", indirectMagic: "une potion", wither: "l'effet wither", witherSkull: "un crâne de wither", starve: "la faim",
  cactus: "un cactus", sweetBerryBush: "des buissons de baies", freeze: "le gel", lightningBolt: "la foudre", fallingBlock: "un bloc tombé",
  anvil: "une enclume", fallingStalactite: "une stalactite", stalagmite: "une stalagmite", thorns: "des épines", outOfWorld: "le vide",
  generic: "une cause inconnue", genericKill: "la régie (/kill)", inWall: "la suffocation", cramming: "l'écrasement", flyIntoWall: "un mur (élytres)",
  dragonBreath: "le souffle du dragon", sonic_boom: "le Warden", mace_smash: "un coup de masse", sting: "une piqûre", badRespawnPoint: "une explosion de lit",
  fireball: "une boule de feu", unattributedFireball: "une boule de feu", windCharge: "une charge de vent", spit: "un crachat de lama", dryout: "la sécheresse",
  outsideBorder: "la bordure du monde", ender_pearl: "une perle de l'End", trap: "un piège"
};
const VIA_FR = { "minecraft:arrow": "flèche", "minecraft:spectral_arrow": "flèche spectrale", "minecraft:trident": "trident", "minecraft:tnt": "TNT", "minecraft:fireball": "boule de feu", "minecraft:snowball": "boule de neige", "minecraft:potion": "potion", "minecraft:wind_charge": "charge de vent" };
const MOB_FR = { "minecraft:zombie": "un zombie", "minecraft:skeleton": "un squelette", "minecraft:creeper": "un creeper", "minecraft:spider": "une araignée", "minecraft:cave_spider": "une araignée venimeuse", "minecraft:wolf": "un loup", "minecraft:husk": "un zombie momifié", "minecraft:stray": "un vagabond", "minecraft:drowned": "un noyé", "minecraft:witch": "une sorcière", "minecraft:enderman": "un enderman", "minecraft:pillager": "un pillard", "minecraft:vindicator": "un vindicateur", "minecraft:bee": "une abeille", "minecraft:slime": "un slime", "minecraft:phantom": "un phantom", "minecraft:iron_golem": "un golem de fer", "minecraft:warden": "le Warden", "minecraft:polar_bear": "un ours polaire", "minecraft:goat": "une chèvre", "minecraft:piglin": "un piglin", "minecraft:hoglin": "un hoglin", "minecraft:blaze": "un blaze", "minecraft:ghast": "un ghast" };
const EFF_FR = {
  speed: "Vitesse", slowness: "Lenteur", haste: "Célérité", mining_fatigue: "Fatigue", strength: "Force", instant_health: "Soin instantané",
  instant_damage: "Dégâts instantanés", jump_boost: "Saut amélioré", nausea: "Nausée", regeneration: "Régénération", resistance: "Résistance",
  fire_resistance: "Résistance au feu", water_breathing: "Respiration aquatique", invisibility: "Invisibilité", blindness: "Cécité",
  night_vision: "Vision nocturne", hunger: "Faim", weakness: "Faiblesse", poison: "Poison", wither: "Wither", health_boost: "Bonus de vie",
  absorption: "Absorption", saturation: "Saturation", glowing: "Surbrillance", levitation: "Lévitation", luck: "Chance", unluck: "Malchance",
  slow_falling: "Chute lente", conduit_power: "Force du conduit", dolphins_grace: "Grâce du dauphin", bad_omen: "Mauvais présage",
  hero_of_the_village: "Héros du village", darkness: "Obscurité", trial_omen: "Présage d'épreuve", raid_omen: "Présage de raid",
  wind_charged: "Chargé de vent", weaving: "Tissage", oozing: "Suintement", infested: "Infestation"
};

/* ---------- fenêtres horaires de blocage ---------- */
const DAYS = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
const hm = s => { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || "")); return m ? (+m[1]) * 60 + (+m[2]) : null; };
function localClock(ts, tz) {
  let parts;
  try { parts = new Intl.DateTimeFormat("en-US", { timeZone: tz || "Europe/Paris", weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date(ts)); }
  catch (e) { parts = new Intl.DateTimeFormat("en-US", { timeZone: "Europe/Paris", weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date(ts)); }
  const g = t => (parts.find(p => p.type === t) || {}).value;
  return { day: DAYS[g("weekday")], min: (+g("hour") % 24) * 60 + (+g("minute")) };
}
export function lockActive(lock, ts = Date.now()) {
  if (!lock || lock.mode === "off") return false;
  if (lock.mode === "on") return true;
  const { day, min } = localClock(ts, lock.tz);
  const prev = (day + 6) % 7;
  return (lock.windows || []).some(w => {
    const f = hm(w.from), t = hm(w.to), days = Array.isArray(w.days) ? w.days : [];
    if (f == null || t == null || f === t) return false;
    if (f < t) return days.includes(day) && min >= f && min < t;
    return (days.includes(day) && min >= f) || (days.includes(prev) && min < t);
  });
}

export class Game extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.loading = null;
    this.presT = null;
    this.lastAlert = new Map();
    this.revealCursor = Date.now();
    // le ping/pong des clients ne réveille pas l'objet
    try { this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong")); } catch (e) {}
  }

  load() {
    if (!this.loading) this.loading = (async () => {
      const m = await this.ctx.storage.get(["board", "alerts", "team", "banned", "live", "cam", "camst", "srvkey", "roles", "prog", "media", "srvcams", "skeys", "pubs", "poll", "bets", "clips", "vpref", "usage", "moved", "from", "perf", "terrain", "tbot", "phist", "dvrtok", "mix", "tstats", "kbs", "zones", "scjobs"]);
      this.board = { ...defaultBoard(), ...(m.get("board") || {}) };
      this.alerts = m.get("alerts") || {};
      this.team = m.get("team") || [];
      this.banned = m.get("banned") || {};
      this.live = m.get("live") || null;
      const c = m.get("cam") || {};
      this.camData = { feed: c.feed || [], comms: c.comms || [], reqs: c.reqs || {} };
      this.camSt = m.get("camst") || {};
      this.srvKey = m.get("srvkey") || "";
      this.terrain = m.get("terrain") || {};
      this.phist = m.get("phist") || []; this.dvrTok = m.get("dvrtok") || ""; this.mix = m.get("mix") || {}; this.tstats = m.get("tstats") || {}; this.kbs = m.get("kbs") || {}; this.zones = m.get("zones") || []; this.scJobs = m.get("scjobs") || [];
      // anciens titres SoundCloud (lecteur intégré) : copiés dans les médias au prochain réveil
      for (const x of (m.get("media") || [])) if (x.sc && !x.n && !x.dl && !this.scJobs.some(j => j.id === x.id)) { x.dl = 1; this.scJobs.push({ id: x.id, i: 0, parts: 0, size: 0 }); }
      this.tbot = { on: false, channel: "", ann: { poll: true, result: true, death: true, victor: true, bets: true }, cmds: true, ...(m.get("tbot") || {}) };
      this.roles = m.get("roles") || null;
      if (!this.roles) {
        // première fois : rôles par défaut + migration des anciens accès
        this.roles = defaultRoles();
        for (const t of this.team) t.rid = t.perm === "admin" ? "org" : t.perm === "mod" ? "modo" : t.cam === "chef" ? "chefcam" : t.cam === "cam" ? "cam" : "equipe";
        this.ctx.storage.put("roles", this.roles); this.ctx.storage.put("team", this.team);
      }
      this.prog = m.get("prog") || { scenes: [{ id: "s1", name: "Plan 1", items: [] }], live: "", preview: "s1", trans: { type: "logo", ms: 1400 }, seq: 0, title: "" };
      this.media = m.get("media") || [];
      this.srvCams = m.get("srvcams") || [];
      this.skeys = m.get("skeys") || {};
      this.pubs = m.get("pubs") || {};
      this.vpref = m.get("vpref") || {};
      this.usage = m.get("usage") || { m: "", b: 0 };
      this.moved = m.get("moved") || null; this.from = m.get("from") || "";
      this.perf = m.get("perf") || {};
      this.poll = m.get("poll") || null;
      this.bets = m.get("bets") || { open: true, users: {}, settled: null };
      this.clips = m.get("clips") || [];
      this.talk = this.talk || { open: [], regie: null, cams: {}, req: {}, speak: {} };
      this.cleanSince = this.cleanSince || {};
      this.pos = this.pos || {};
      this.near = this.near || new Map();
      this.cool = this.cool || new Map();
    })();
    return this.loading;
  }

  get owners() { return String(this.env.OWNER_DISCORD_IDS || "").split(/[,\s]+/).filter(Boolean); }
  roleOf(id) { const m = this.team.find(x => x.id === id); return m ? this.roles.find(r => r.id === m.rid) || null : null; }
  permsOf(id) {
    if (this.owners.includes(id)) return ["admin", ...ROLE_PERMS.filter(p => p !== "admin")];
    const r = this.roleOf(id); return r ? r.perms.slice() : [];
  }
  permOf(id) {
    const p = this.permsOf(id);
    return p.includes("admin") ? "admin" : p.includes("mod") ? "mod" : "viewer";
  }
  camOf(id) { const p = this.permsOf(id); return p.includes("chef") ? "chef" : p.includes("cam") ? "cam" : ""; }
  inTeam(id) { return this.owners.includes(id) || this.team.some(x => x.id === id); }
  sockets() { return this.ctx.getWebSockets(); }
  att(ws) { try { return ws.deserializeAttachment(); } catch (e) { return null; } }
  send(ws, obj) { try { ws.send(typeof obj === "string" ? obj : JSON.stringify(obj)); } catch (e) {} }
  fullView(a) { return isStaff(a) && !a.pub; }

  /* ---------- connexion ---------- */
  async fetch(req) {
    await this.load();
    const path = new URL(req.url).pathname;
    if (path === "/public") return Response.json({ open: !!this.board.publicAccess });
    if (path === "/moved") return Response.json({ url: this.moved ? this.moved.url : "", from: this.from || "" });
    if (path === "/failover/ping") return Response.json({ ok: true, rtc: this.rtcOn(), clips: !!this.env.CLIPS, media: !!this.env.MEDIA, tributes: this.board.tributes.length, moved: this.moved ? this.moved.url : "" });
    if (path === "/failover/import" && req.method === "POST") return this.foImport(req);
    if (path === "/mod") return this.modConnect(req);
    if (path.startsWith("/api/media")) return this.mediaApi(req, path);
    if (path.startsWith("/terrain/")) return this.terrainGet(path);
    if (path === "/hymn") {
      const h = normHymn(this.board.hymn), v = new URL(req.url).searchParams.get("v") || "";
      const id = v && (v === h.id || v === this.skyMusic) ? v : h.id;
      return id ? serveMedia(req, this.env, id) : new Response("Aucun hymne choisi", { status: 404 });
    }
    if (path === "/dvr/up") return this.dvrUpload(req);
    if (path === "/twitchbot/start" || path === "/twitchbot/cb") return this.botOAuth(req, path);
    if (this.tbot && this.tbot.on && this.tbot.channel && !this.botWs && !this.botConnecting && Date.now() - (this.botTry || 0) > 20000) { this.botTry = Date.now(); this.botStart(); }
    if (path.startsWith("/api/rtc")) return this.rtcApi(req, path);
    if (path.startsWith("/api/clips") || path.startsWith("/clip/")) return this.clipApi(req, path);
    if (path.startsWith("/whip/")) return this.whip(req, path);
    let user = null;
    try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    if (!user || !user.id) return new Response("Connexion requise", { status: 401 });

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);

    if (this.moved) { this.send(server, { t: "moved", url: this.moved.url }); server.close(4010, "Site déplacé"); return new Response(null, { status: 101, webSocket: client }); }
    if (user.guest && !this.board.publicAccess) {
      this.send(server, { t: "auth" }); server.close(4001, "Connexion requise");
      return new Response(null, { status: 101, webSocket: client });
    }
    if (this.banned[user.id]) {
      this.send(server, { t: "kicked" });
      server.close(4003, "Accès retiré");
      return new Response(null, { status: 101, webSocket: client });
    }

    // l'équipe se met à jour avec le pseudo / l'avatar Discord
    let teamChanged = false;
    if (user.guest) user.overlay = false;
    if (this.owners.includes(user.id) && !this.team.find(m => m.id === user.id)) {
      this.team.push({ id: user.id, name: user.name, av: user.av, role: "Organisation", rid: "org", perm: "admin", bio: "" });
      teamChanged = true;
    }
    const tm = this.team.find(m => m.id === user.id);
    if (tm && (tm.name !== user.name || tm.av !== user.av)) { tm.name = user.name; tm.av = user.av; teamChanged = true; }
    if (teamChanged) { await this.ctx.storage.put("team", this.team); this.broadcast({ t: "team", team: this.team }); }

    const anon = user.overlay || user.guest;
    const a = { id: user.id, name: user.name, av: user.av || "", perm: anon ? "viewer" : this.permOf(user.id), cam: anon ? "" : this.camOf(user.id), perms: anon ? [] : this.permsOf(user.id), team: !anon && this.inTeam(user.id), overlay: !!user.overlay, guest: !!user.guest, ch: null, chs: [], at: Date.now(), sub: false, pub: false, map: false };
    a.lock = this.isLocked(a);
    server.serializeAttachment(a);
    this.send(server, this.hello(a));
    this.schedulePresence();
    await this.scheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  hello(a) {
    return {
      t: "hello", now: Date.now(),
      me: { id: a.id, name: a.name, av: a.av, perm: a.perm, pub: a.pub, overlay: !!a.overlay, obsKey: hasPerm(a, "program") && !a.lock && this.env.OVERLAY_KEY ? this.env.OVERLAY_KEY : undefined, lock: !!a.lock, guest: !!a.guest, tribute: this.tributeOf(a.id), cam: a.cam || "", sup: isSup(a), crew: isCrew(a) && !a.lock, map: this.mapAllowed(a), perms: a.perms || [], rid: (this.team.find(x => x.id === a.id) || {}).rid || "", kb: isCrew(a) ? this.kbs[a.id] : undefined },
      roles: this.roles, prog: a.lock || !(isCrew(a) || a.overlay) ? undefined : this.progPub(), progHist: a.lock || !(isCrew(a) || a.overlay) ? undefined : this.progHistFor(),
      dvrBase: isCrew(a) || a.overlay ? (this.env.DVR_PUBLIC || "") : undefined, mix: isCrew(a) || a.overlay ? this.mix : undefined, zones: isCrew(a) ? this.zones || [] : undefined, progEd: hasPerm(a, "program") && !a.lock ? this.prog : undefined,
      terrain: this.terrain || {}, bot: isStaff(a) ? this.botPublic() : undefined, media: isCrew(a) ? this.media : undefined, srvCams: isCrew(a) ? this.srvCams : undefined,
      pubs: this.pubsFor(a), rtc: { on: this.rtcOn(), pub: false, capOff: !!this.board.capOff, perf: isCrew(a) ? this.perf : undefined, use: isCrew(a) || a.overlay ? this.useInfo() : undefined, vq: { trib: this.vqCap("t:"), cam: this.vqCap("c:") }, vpref: isCrew(a) && !a.lock ? this.vpref : undefined },
      talk: this.talkSup(a) ? this.talkState() : undefined, clips: this.clipsFor(a),
      skeys: hasPerm(a, "program") || a.perm === "admin" ? this.allKeys() : undefined,
      mykeys: a.guest || a.overlay ? undefined : this.myKeys(a.id),
      poll: this.pollPublic(a.id), bets: this.betsPublic(a.id),
      onair: isCrew(a) ? { ids: this.onAirCams(), prep: this.prepCams(), since: this.cleanSince || {}, delay: this.board.delay || 0 } : undefined,
      cam: isCrew(a) && !a.lock ? this.camView(a) : undefined,
      board: this.viewFor(a),
      alerts: isCrew(a) && !a.lock ? this.alerts : undefined,
      banned: isStaff(a) ? this.banned : undefined,
      team: this.team, live: a.lock ? null : this.live,
      presence: a.lock ? { n: this.presence(false).n, counts: {}, team: {} } : this.presence(a.sub)
    };
  }
  viewFor(a) { return this.fullView(a) ? this.board : a.lock ? this.lockedBoard() : this.publicBoard(); }

  /* ---------- tributs joués par un compte Discord : blocage des flux ---------- */
  tributeOf(uid) { const t = this.board.tributes.find(x => x.discordId && x.discordId === uid); return t ? t.id : null; }
  isLocked(a) {
    if (!a || a.overlay || isStaff(a)) return false;
    const t = this.board.tributes.find(x => x.discordId && x.discordId === a.id);
    if (!t || t.status === "dead") return false;
    return lockActive(this.board.lock);
  }
  refreshLocks() {
    for (const ws of this.sockets()) {
      const a = this.att(ws); if (!a) continue;
      const l = this.isLocked(a);
      if (l !== !!a.lock) { a.lock = l; if (l) { a.ch = null; a.chs = []; } ws.serializeAttachment(a); this.send(ws, this.hello(a)); }
    }
  }
  lockedBoard() {
    const b = this.publicBoard();
    return { ...b, tributes: b.tributes.map(t => ({ ...t, login: "" })) };
  }

  /* ---------- vue publique (différé) ---------- */
  publicBoard(now = Date.now()) {
    let b = this.board; const D = (b.delay || 0) * 1000, cut = now - D;
    const events = b.events.filter(e => e.k !== "mark");
    b = { ...b, fundLog: undefined, flashQ: undefined };
    const tributes = b.tributes.map(t => {
      const x = { ...t }; delete x.discordId;
      // différé : le dernier changement de statut n'est pas encore « passé à l'antenne »
      if (D && x.statusAt && x.statusAt > cut && x.prevStatus && x.prevStatus !== x.status) {
        x.status = x.prevStatus;
        if (x.status !== "dead") { x.diedAt = 0; x.killer = ""; x.cause = ""; x.place = 0; }
      }
      return x;
    });
    if (!D) return { ...b, tributes, events };
    // annonces, parachutes et hommages : immédiats (pas de différé)
    return {
      ...b, tributes,
      events: events.filter(e => NOW_KINDS.includes(e.k) || e.now || e.ts <= cut).map(e => NOW_KINDS.includes(e.k) || e.now ? e : ({ ...e, ts: e.ts + D })),
      flash: b.flash || null
    };
  }
  revealTimes() {
    const b = this.board, D = (b.delay || 0) * 1000, out = [];
    if (!D) return out;
    for (const t of b.tributes) if (t.statusAt) out.push(t.statusAt + D);
    for (const e of b.events) if (e.k !== "mark" && !NOW_KINDS.includes(e.k) && !e.now) out.push(e.ts + D);
    for (const q of b.flashQ || []) out.push(q.at);
    return out;
  }

  /* ---------- diffusion ---------- */
  broadcast(obj, filter) {
    const s = JSON.stringify(obj);
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server && (!filter || filter(a))) this.send(ws, s); }
  }
  /* équipe : sockets du mod + membres de l'équipe connectés au site */
  crewcast(obj, filter) {
    const s = JSON.stringify(obj);
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.server && (a.mod || (isCrew(a) && !a.lock)) && (!filter || filter(a))) this.send(ws, s); }
  }
  modcast(obj, filter) {
    const s = JSON.stringify(obj);
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && a.mod && (!filter || filter(a))) this.send(ws, s); }
  }
  broadcastBoard({ viewersOnly = false } = {}) {
    let full, pub, locked;
    for (const ws of this.sockets()) {
      const a = this.att(ws); if (!a || a.mod || a.server) continue;
      const f = this.fullView(a);
      if (viewersOnly && f) continue;
      if (f) this.send(ws, full = full || JSON.stringify({ t: "board", board: this.board }));
      else if (a.lock) this.send(ws, locked = locked || JSON.stringify({ t: "board", board: this.lockedBoard() }));
      else this.send(ws, pub = pub || JSON.stringify({ t: "board", board: this.publicBoard() }));
    }
    if (!viewersOnly) this.pushTribs();
  }
  pushTribs() {
    const j = JSON.stringify({ t: "tribs", tribs: this.tribsForMod(), delay: this.board.delay || 0 });
    if (j === this.lastTribs) return;
    this.lastTribs = j;
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && a.mod) this.send(ws, j); }
  }
  presence(full) {
    const users = new Map(), counts = {}; let guests = 0;
    for (const ws of this.sockets()) {
      const a = this.att(ws);
      if (!a || a.overlay || a.mod || a.server) continue;
      if (a.guest) { guests++; if (a.ch) counts[a.ch] = (counts[a.ch] || 0) + 1; continue; }
      let u = users.get(a.id);
      if (!u) { u = { id: a.id, name: a.name, av: a.av, perm: a.perm, ch: null, chs: new Set(), at: a.at }; users.set(a.id, u); }
      if (a.ch) { u.ch = a.ch; u.chs.add(a.ch); }
      (a.chs || []).forEach(c => u.chs.add(c));
      u.at = Math.min(u.at, a.at);
    }
    for (const u of users.values()) for (const c of u.chs) counts[c] = (counts[c] || 0) + 1;
    const team = {};
    for (const m of this.team) { const u = users.get(m.id); if (u) team[m.id] = u.ch || ""; }
    const out = { n: users.size + guests, guests, counts, team };
    if (full) {
      const rank = u => (u.perm === "admin" ? 0 : u.perm === "mod" ? 1 : 2);
      out.users = [...users.values()].sort((x, y) => rank(x) - rank(y) || x.at - y.at).slice(0, 600)
        .map(u => ({ id: u.id, name: u.name, av: u.av, perm: u.perm, ch: u.ch }));
    }
    return out;
  }
  schedulePresence() {
    if (this.presT) return;
    this.presT = setTimeout(() => {
      this.presT = null;
      const light = JSON.stringify({ t: "presence", p: this.presence(false) });
      let full = null;
      for (const ws of this.sockets()) {
        const a = this.att(ws); if (!a || a.mod || a.server) continue;
        if (a.lock) this.send(ws, JSON.stringify({ t: "presence", p: { n: this.presence(false).n, counts: {}, team: {} } }));
        else if (a.sub) { full = full || JSON.stringify({ t: "presence", p: this.presence(true) }); this.send(ws, full); }
        else this.send(ws, light);
      }
    }, 2500);
  }
  refreshPerms() {
    for (const ws of this.sockets()) {
      const a = this.att(ws);
      if (!a || a.overlay || a.guest || a.server) continue;
      const p = this.permOf(a.id), c = this.camOf(a.id), ps = this.permsOf(a.id), tm = this.inTeam(a.id);
      if (p === a.perm && c === (a.cam || "") && JSON.stringify(ps) === JSON.stringify(a.perms || []) && tm === !!a.team) continue;
      a.perm = p; a.cam = c; a.perms = ps; a.team = tm;
      if (a.mod) {
        if (!isCrew(a)) { this.send(ws, { t: "err", msg: "Tu n'es plus dans l'équipe caméra." }); try { ws.close(4003, "Retiré de l'équipe caméra"); } catch (e) {} continue; }
        ws.serializeAttachment(a); this.send(ws, this.modHello(a)); continue;
      }
      a.lock = this.isLocked(a); ws.serializeAttachment(a); this.send(ws, this.hello(a));
    }
  }

  /* ---------- messages ---------- */
  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 400000) return;
    await this.load();
    let m; try { m = JSON.parse(msg); } catch (e) { return; }
    const a = this.att(ws); if (!a) return;
    if (a.mod || a.server) return this.onModMessage(ws, a, m);
    const admin = a.perm === "admin", staff = isStaff(a);
    const reply = (ok, extra = {}) => { if (m.rid) this.send(ws, { t: ok ? "ok" : "err", rid: m.rid, ...extra }); else if (!ok) this.send(ws, { t: "err", ...extra }); };
    const deny = () => reply(false, { msg: "Action réservée à la régie." });

    switch (m.t) {
      case "watch": {
        if (a.lock) break;
        a.ch = cleanLogin(m.ch);
        a.chs = (Array.isArray(m.chs) ? m.chs : []).slice(0, 9).map(cleanLogin).filter(Boolean);
        ws.serializeAttachment(a); this.schedulePresence(); break;
      }
      case "sub": {
        a.sub = !!m.on; ws.serializeAttachment(a);
        if (a.sub) this.send(ws, { t: "presence", p: this.presence(true) });
        break;
      }
      case "view": {
        if (!staff) return deny();
        a.pub = !!m.pub; ws.serializeAttachment(a);
        this.send(ws, this.hello(a)); break;
      }
      case "alert": return this.onAlert(ws, a, m.a || {}, reply);
      case "tstats": this.statTick(); return reply(true, this.statsFor(a));
      case "skyShow": {
        if (!isCrew(a) || a.lock || !(a.perm === "admin" || hasPerm(a, "program"))) return deny();
        const clean = x => String(x || "").replace(/\s+/g, " ").trim().slice(0, 60);
        const scenes = (Array.isArray(m.scenes) ? m.scenes : []).slice(0, 30).map(x => ({ bg: clean(x.bg), fg: clean(x.fg), img: /^[a-f0-9]{24}$/.test(String(x.img || "")) ? "/skyimg/" + x.img : "", dur: Math.max(3, Math.min(30, Math.round(+x.dur || 7))) })).filter(x => x.bg || x.fg || x.img);
        if (!scenes.length) return reply(false, { msg: "Aucune scène à projeter." });
        const music = String(m.music || "").replace(/[^a-z0-9]/gi, "").slice(0, 40);
        this.skyMusic = music;
        this.sendServers({ t: "sky", kind: "show", title: clean(m.title), scenes, hymn: music ? { path: "/hymn?v=" + music, vol: Math.max(0, Math.min(100, Math.round(+m.vol || 70))) } : undefined });
        const total = 5 + scenes.reduce((t, x) => t + x.dur, 0);
        return reply(true, { total, servers: [...this.sockets()].filter(w => (this.att(w) || {}).server).length });
      }
      case "zones": {
        if (!isCrew(a) || a.lock || !(isSup(a) || hasPerm(a, "program"))) return deny();
        this.zones = (Array.isArray(m.zones) ? m.zones : []).slice(0, 40).map(z => ({ id: String(z.id || rid8()).slice(0, 12), name: String(z.name || "Zone interdite").slice(0, 40), d: String(z.d || "minecraft:overworld").slice(0, 60),
          x1: Math.round(Math.min(+z.x1, +z.x2)), z1: Math.round(Math.min(+z.z1, +z.z2)), x2: Math.round(Math.max(+z.x1, +z.x2)), z2: Math.round(Math.max(+z.z1, +z.z2)) })).filter(z => isFinite(z.x1) && isFinite(z.z1) && isFinite(z.x2) && isFinite(z.z2));
        await this.ctx.storage.put("zones", this.zones);
        this.crewcast({ t: "zones", zones: this.zones });
        return reply(true);
      }
      case "kb": {
        if (!isCrew(a) || a.guest) return deny();
        const o = {}; for (const [k, v] of Object.entries(m.kb || {}).slice(0, 80)) if (/^(antenne|regie)\.\w{1,20}$/.test(k) && typeof v === "string" && v.length <= 12) o[k] = v;
        this.kbs[a.id] = o; await this.ctx.storage.put("kbs", this.kbs); return reply(true);
      }
      case "progTake": this.statTick(); return this.onProgOp(a, m, reply);
      case "progMix": {
        // volumes de l'émission (général, musique, effets, chaque source), avec fondus : appliqués tout de suite sur le Direct
        if (!hasPerm(a, "program") || a.lock) return deny();
        const keys = Array.isArray(m.keys) ? m.keys : [m.key], now = Date.now(), dur = Math.max(0, Math.min(600000, Math.round(+m.dur || 0)));
        for (const raw of keys.slice(0, 40)) {
          const k = String(raw || "").slice(0, 60); if (!/^(master|bgm|sfx|(trib|cam|media)\|[\w-]{1,40})$/.test(k)) continue;
          const cur = mixAt(this.mix[k], now), to = m.rel != null ? Math.round(cur * (1 + Math.max(-1, Math.min(2, +m.rel || 0)))) : m.v != null ? Math.round(+m.v) : Math.round(cur);
          if (!isFinite(to)) continue;
          const v = Math.max(0, Math.min(150, to));
          if (v === 100 && !dur) delete this.mix[k]; else this.mix[k] = { v0: cur, v, at: now, dur };
          if (m.mute !== undefined) { if (!this.mix[k]) this.mix[k] = { v0: 100, v: 100, at: now, dur: 0 }; this.mix[k].mute = !!m.mute; }
        }
        this.broadcast({ t: "mix", mix: this.mix }, x => (isCrew(x) && !x.lock) || x.overlay);
        clearTimeout(this.mixT); this.mixT = setTimeout(() => this.ctx.storage.put("mix", this.mix), 1500);
        reply(true); break;
      }
      case "fund": {
        if (!admin) return deny();
        const amount = Math.round((parseFloat(m.amount) || 0) * 100) / 100;
        if (!amount || Math.abs(amount) > 1e7) return reply(false, { msg: "Montant invalide." });
        const e = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), amount, note: String(m.note || "").slice(0, 80), by: a.name, ts: Date.now() };
        this.board.fundLog = [...(this.board.fundLog || []), e].slice(-1000);
        this.recalcFund();
        await this.ctx.storage.put("board", this.board);
        this.broadcastBoard();
        if (amount > 0 && this.board.fund.show) this.broadcast({ t: "fundUp", amount, total: this.board.fund.raised }, x => !x.lock);
        reply(true); break;
      }
      case "fundDel": {
        if (!admin) return deny();
        this.board.fundLog = (this.board.fundLog || []).filter(e => e.id !== m.id);
        this.recalcFund();
        await this.ctx.storage.put("board", this.board);
        this.broadcastBoard(); reply(true); break;
      }
      case "patch": {
        if (!admin || a.pub) return deny();
        this.applyPatch(m.p || {});
        await this.ctx.storage.put("board", this.board);
        this.checkVictor();
        if (!this.board.publicAccess) for (const s of this.sockets()) { const x = this.att(s); if (x && x.guest) { this.send(s, { t: "auth" }); try { s.close(4001, "Connexion requise"); } catch (e) {} } }
        this.refreshLocks();
        this.broadcastBoard();
        if (this.prog.live) this.pushProg(false);
        this.botTick();
        this.dvrWantPush();
        await this.scheduleAlarm();
        reply(true); break;
      }
      case "alertUpd": {
        if (!isCrew(a) || a.lock) return deny();
        const al = this.alerts[m.id]; if (!al) return reply(false, { msg: "Alerte introuvable." });
        const p = {};
        if ("handled" in (m.p || {})) p.handled = !!m.p.handled;
        if ("claimedBy" in (m.p || {})) p.claimedBy = String(m.p.claimedBy || "").slice(0, 32);
        Object.assign(al, p);
        await this.ctx.storage.put("alerts", this.alerts);
        this.broadcast({ t: "alertUpd", id: m.id, p }, x => isCrew(x) && !x.lock);
        this.modcast({ t: "alertUpd", id: m.id, p });
        reply(true); break;
      }
      case "alertsClear": {
        if (!admin) return deny();
        this.alerts = {}; await this.ctx.storage.put("alerts", this.alerts);
        this.broadcast({ t: "alerts", all: {} }, x => isCrew(x) && !x.lock); this.modcast({ t: "alerts", all: {} }); reply(true); break;
      }
      case "team": case "setRole": case "roles": return this.onRolesOp(a, m, reply);
      case "progScreen": case "progBgm": case "progApply": case "progMode": case "progSave": case "progTake": case "progSfx": case "progGfx": case "progQueue": case "progReplay": case "progAuto": case "progClip": return this.onProgOp(a, m, reply);
      case "skeyNew": {
        if (!hasPerm(a, "program") && !admin) return deny();
        const src = String(m.src || ""); if (!/^[tc]:[\w-]{1,40}$/.test(src)) return reply(false, { msg: "Source inconnue." });
        for (const [k, v] of Object.entries(this.skeys)) if (v === src) delete this.skeys[k];
        const key = this.keyFor(src);
        if (this.pubs[src]) { delete this.pubs[src]; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); }
        reply(true, { key }); this.broadcast({ t: "skeys", keys: this.allKeys() }, x => hasPerm(x, "program") || x.perm === "admin"); break;
      }
      case "rtcUse": {
        // compteur de consommation vidéo (ce que Cloudflare facture : données envoyées aux écrans)
        if (!(isCrew(a) || a.overlay)) return;
        const mo = new Date().toISOString().slice(0, 7), b = Math.max(0, Math.min(5e10, +m.b || 0));
        if (this.usage.m !== mo) this.usage = { m: mo, b: 0 };
        const was = this.useInfo().save;
        this.usage.b += b; this.ctx.storage.put("usage", this.usage);
        const now = Date.now(), info = this.useInfo();
        if (was !== info.save || now - (this.useAt || 0) > 30000) { this.useAt = now; this.crewcast({ t: "rtcUse", use: info }, x => !x.mod); for (const ws of this.sockets()) { const o = this.att(ws); if (o && o.overlay) this.send(ws, { t: "rtcUse", use: info }); } }
        break;
      }
      case "failover": {
        if (!admin) return deny();
        const to = String(m.to || "").trim().replace(/\/+$/, "");
        if (!/^(https:\/\/[a-z0-9.-]+(:\d+)?|http:\/\/localhost:\d+)$/i.test(to)) return reply(false, { msg: "Adresse invalide (ex. https://capitol-tv.compte2.workers.dev)" });
        const H = { "X-CTV-Failover": FO_SECRET(this.env), "Content-Type": "application/json" };
        let ping;
        try { const r = await fetch(to + "/failover/ping", { headers: H }); if (!r.ok) throw new Error(r.status === 403 ? "les deux sites n'ont pas le même SESSION_SECRET" : "réponse " + r.status); ping = await r.json(); }
        catch (e) { return reply(false, { msg: "Site de secours injoignable : " + e.message }); }
        if (!m.go) return reply(true, { ping });
        // copie de tout l'état (tributs, équipe, clés, plans, réglages…) puis bascule de tout le monde
        const all = await this.ctx.storage.list(), data = {};
        for (const [k, v] of all) if (!["pubs", "moved", "from"].includes(k)) data[k] = v;
        try { const r = await fetch(to + "/failover/import", { method: "POST", headers: H, body: JSON.stringify({ data, from: String(m.from || "").replace(/\/+$/, "") }) }); if (!r.ok) throw new Error("import refusé (" + r.status + ")"); }
        catch (e) { return reply(false, { msg: "Copie impossible : " + e.message }); }
        this.moved = { url: to, at: Date.now(), by: a.name }; await this.ctx.storage.put("moved", this.moved);
        reply(true, { ping });
        for (const ws of this.sockets()) { this.send(ws, { t: "moved", url: to }); }
        setTimeout(() => { for (const ws of this.sockets()) try { ws.close(4010, "Site déplacé"); } catch (e) {} }, 1500);
        return;
      }
      case "povBlock": {
        if (!(admin || hasPerm(a, "program"))) return deny();
        const src = String(m.src || "").slice(0, 44); if (!/^[tc]:/.test(src)) return reply(false);
        if (m.on) { this.vpref[src] = "blocked"; if (this.pubs[src]) { delete this.pubs[src]; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); } }
        else if (this.vpref[src] === "blocked") delete this.vpref[src];
        this.ctx.storage.put("vpref", this.vpref);
        this.crewcast({ t: "vpref", vpref: this.vpref }, x => !x.mod && !x.lock);
        return reply(true);
      }
      case "rtcBudget": {
        if (!admin) return deny();
        this.board.useBudget = Math.max(50, Math.min(100000, Math.round(+m.gb || 900)));
        this.ctx.storage.put("board", this.board);
        this.crewcast({ t: "rtcUse", use: this.useInfo() }, x => !x.mod);
        return reply(true);
      }
      case "rtcCfg": {
        if (!admin) return deny();
        this.board.rtcPublic = false;
        if (m.capOff != null) {
          this.board.capOff = !!m.capOff;
          if (this.board.capOff && Object.keys(this.pubs).length) { this.pubs = {}; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); }
        }
        if (m.vq) { const c = (x, d) => VQ_LEVELS[x] && x !== "off" ? x : d; this.board.vq = { trib: c(m.vq.trib, "hd"), cam: c(m.vq.cam, "max") }; }
        await this.ctx.storage.put("board", this.board);
        this.broadcastHelloAll(); reply(true); break;
      }
      case "pollStart": case "pollEnd": case "vote": return this.onPollOp(a, m, reply);
      case "talkOpen": case "talkSpeak": case "talkDrop": {
        if (!this.talkSup(a)) return deny();
        const T = this.talk;
        if (m.t === "talkOpen") {
          const ids = m.all ? this.talkCams().map(x => x.id) : [String(m.uid || "")];
          for (const id of ids) { T.open = T.open.filter(x => x !== id); if (m.on) T.open.push(id); delete T.req[id]; }
          for (const id of ids) this.sendMod(id, { t: "talkCh", open: T.open.includes(id), regie: !!T.regie, by: a.name });
          if (!m.on) for (const id of ids) delete T.cams[id];
        } else if (m.t === "talkSpeak") {
          T.speak.regie = !!m.on; T.speakBy = a.name;
          for (const id of T.open) this.sendMod(id, { t: "talkSpeak", who: "regie", name: a.name, on: !!m.on });
        } else if (m.t === "talkDrop") { delete T.req[String(m.uid || "")]; }
        this.pushTalk(); reply(true); break;
      }
      case "clipLive": return this.onProgOp(a, { ...m, t: "progClip" }, reply);
      case "bet": case "betsOpen": case "betsReset": return this.onBetOp(a, m, reply);
      case "ban": {
        if (!staff) return deny();
        const uid = String(m.uid || "");
        if (!uid || uid === a.id || this.permOf(uid) === "admin" || (this.permOf(uid) === "mod" && !admin)) return reply(false, { msg: "Impossible de bannir ce compte." });
        this.banned[uid] = { name: String(m.name || "").slice(0, 32), by: a.name, at: Date.now() };
        await this.ctx.storage.put("banned", this.banned);
        for (const s of this.sockets()) { const x = this.att(s); if (x && x.id === uid) { this.send(s, { t: "kicked" }); try { s.close(4003, "Accès retiré"); } catch (e) {} } }
        this.broadcast({ t: "banned", list: this.banned }, isStaff);
        this.schedulePresence(); reply(true); break;
      }
      case "unban": {
        if (!staff) return deny();
        delete this.banned[String(m.uid || "")];
        await this.ctx.storage.put("banned", this.banned);
        this.broadcast({ t: "banned", list: this.banned }, isStaff); reply(true); break;
      }
      case "mapSub": {
        a.map = !!m.on; ws.serializeAttachment(a);
        if (a.map) this.send(ws, { t: "pos", p: this.posFor(a), cams: isCrew(a) && !a.lock && !a.pub ? this.camsList() : undefined });
        break;
      }
      case "modToken": {
        if (!isCrew(a) || a.lock) return reply(false, { msg: "La régie doit d'abord te donner le rôle Caméra dans l'équipe." });
        if (!this.env.SESSION_SECRET) return reply(false, { msg: "SESSION_SECRET manquant." });
        const token = await signSession({ id: a.id, name: a.name, av: a.av, mod: 1, exp: Date.now() + 60 * 864e5 }, this.env.SESSION_SECRET);
        reply(true, { token }); break;
      }
      case "botCfg": {
        if (a.perm !== "admin") return reply(false, { msg: "Réservé aux administrateurs." });
        const c = m.cfg || {}, T = this.tbot;
        if (c.channel !== undefined) T.channel = String(c.channel || "").toLowerCase().replace(/^#|https?:\/\/(www\.)?twitch\.tv\//g, "").replace(/[^a-z0-9_]/g, "").slice(0, 25);
        if (c.on !== undefined) T.on = !!c.on;
        if (c.cmds !== undefined) T.cmds = !!c.cmds;
        if (c.ann) for (const k of ["poll", "result", "death", "victor", "bets"]) if (c.ann[k] !== undefined) T.ann[k] = !!c.ann[k];
        if (c.logout) delete T.auth;
        await this.ctx.storage.put("tbot", T);
        this.botClose(); if (T.on && T.channel) this.botStart();
        this.botStatus(); reply(true, { st: this.botPublic() }); break;
      }
      case "botSay": {
        if (a.perm !== "admin" && !hasPerm(a, "program")) return reply(false, { msg: "Réservé à la régie." });
        if (!this.botCanSay()) return reply(false, { msg: "Le bot n'est pas connecté avec un compte (lecture seule)." });
        this.botSay(String(m.text || "").slice(0, 450)); reply(true); break;
      }
      case "dvrCan": {
        if (!(isCrew(a) || a.overlay)) return reply(false);
        a.dvrPri = Math.max(0, Math.min(3, +m.pri || 0)); a.dvrAt = a.dvrAt || Date.now(); ws.serializeAttachment(a);
        this.dvrElect(); reply(true); break;
      }
      case "dvrIdx": {
        if (!(isCrew(a) || a.overlay)) return reply(false);
        const src = String(m.src || ""); if (!/^[tc]:[\w-]{1,40}$/.test(src)) return reply(false);
        reply(true, { list: await this.dvrList(src) }); break;
      }
      case "terrainScan": {
        if (!isCrew(a) || a.lock) return reply(false, { msg: "Réservé à l'équipe." });
        let on = false; for (const ws2 of this.sockets()) { const b = this.att(ws2); if (b && b.server) on = true; }
        if (!on) return reply(false, { msg: "Le mod serveur n'est pas connecté (clé serveur dans Carte & caméras)." });
        this.sendServers({ t: "scan", dim: String(m.dim || "minecraft:overworld").slice(0, 60), cx: Math.round(+m.cx || 0), cz: Math.round(+m.cz || 0), r: Math.max(16, Math.min(2048, Math.round(+m.r || 256))) });
        reply(true); break;
      }
      case "srvKey": {
        if (!admin) return deny();
        if (!this.srvKey || m.regen) {
          this.srvKey = [...crypto.getRandomValues(new Uint8Array(24))].map(b => b.toString(16).padStart(2, "0")).join("");
          await this.ctx.storage.put("srvkey", this.srvKey);
          for (const s of this.sockets()) { const x = this.att(s); if (x && x.server) try { s.close(4003, "Clé serveur changée"); } catch (e) {} }
        }
        reply(true, { key: this.srvKey }); break;
      }
      case "camClear": {
        if (!admin) return deny();
        this.camData = { feed: [], comms: [], reqs: {} }; this.saveCam(true);
        this.crewcast({ t: "commsClear" }); reply(true); break;
      }
      case "msg": case "buzz": case "req": case "reqUpd": case "special": {
        if (!isCrew(a) || a.lock) return deny();
        return this.onCrewOp(a, m, reply);
      }
      case "twitchPoll": {
        if (!admin) return deny();
        await this.pollTwitch(true); reply(true, { live: this.live }); break;
      }
    }
  }

  async onAlert(ws, a, raw, reply, extra = {}) {
    if (a.overlay) return reply(false, { msg: "Non autorisé." });
    if (a.guest) return reply(false, { msg: "Connecte-toi avec Discord pour envoyer un signalement." });
    if (a.lock) return reply(false, { msg: "Les flux sont fermés aux tributs en ce moment." });
    const ch = cleanLogin(raw.ch);
    if (!ch || !this.board.tributes.find(t => t.login === ch)) return reply(false, { msg: "Tribut inconnu." });
    const now = Date.now(), cd = (this.board.cooldown || 0) * 1000, last = this.lastAlert.get(a.id) || 0;
    if (!isCrew(a) && now - last < cd) return reply(false, { msg: `Patiente encore ${Math.ceil((cd - (now - last)) / 1000)} s avant un nouveau signalement.` });
    this.lastAlert.set(a.id, now);
    const id = now.toString(36) + Math.random().toString(36).slice(2, 7);
    const al = {
      ch, type: TYPES.includes(raw.type) ? raw.type : "Autre",
      detail: String(raw.detail || "").trim().slice(0, 300) || "(aucun détail)",
      urgent: !!raw.urgent, by: a.name, uid: a.id, av: a.av, ts: now, handled: false, ...extra
    };
    this.alerts[id] = al;
    const ids = Object.keys(this.alerts).sort((x, y) => this.alerts[x].ts - this.alerts[y].ts);
    while (ids.length > 400) delete this.alerts[ids.shift()];
    await this.ctx.storage.put("alerts", this.alerts);
    this.broadcast({ t: "alert", id, a: al }, x => isCrew(x) && !x.lock);
    this.modcast({ t: "alert", id, a: al });
    if (isCrew(a)) this.dvrWantPush();
    reply(true, { id });
  }

  applyPatch(p) {
    const b = this.board, now = Date.now();
    if (p.set && typeof p.set === "object") {
      for (const [k, v] of Object.entries(p.set)) if (SCALARS.includes(k)) b[k] = v;
      if (p.set.flash && typeof p.set.flash === "object") {
        b.flash = { ...p.set.flash, ts: now };
        if (b.flash.k === "tribute" && b.skyHommage) {
          const list = (Array.isArray(b.flash.ids) ? b.flash.ids : []).map(id => b.tributes.find(t => t.id === id)).filter(Boolean).slice(0, 24).map(t => ({ name: t.name, d: t.district || 0, num: t.num || 0, mc: t.mc || "" }));
          const hy = normHymn(p.set.hymn || b.hymn);
          this.sendServers({ t: "sky", list, title: String(b.flash.title || "").slice(0, 80), per: 7, hymn: hy.on && hy.id ? { path: "/hymn?v=" + hy.id, vol: hy.vol } : undefined });
        }
      }
      if (p.set.fund && typeof p.set.fund === "object") { b.fund = { goal: Math.max(0, +p.set.fund.goal || 0), cur: String(p.set.fund.cur || "€").slice(0, 4), show: p.set.fund.show !== false, raised: 0 }; this.recalcFund(); }
      if (b.theme) { const t = b.theme || {}, hx = v => /^#[0-9a-f]{6}$/i.test(v || "") ? v : ""; b.theme = { name: String(t.name || "").slice(0, 40), gold: hx(t.gold), red: hx(t.red), font: ["unbounded", "syne", "grotesk", "oswald", "cinzel"].includes(t.font) ? t.font : "" }; }
      b.publicAccess = !!b.publicAccess;
      b.autoCanon = b.autoCanon !== false; b.skyHommage = b.skyHommage !== false; b.hymn = normHymn(b.hymn);
      if (b.tick) { const t = b.tick; b.tick = { msgs: (Array.isArray(t.msgs) ? t.msgs : []).filter(m => m && m.text).slice(0, 12).map(m => ({ id: String(m.id || "").slice(0, 20), text: String(m.text).slice(0, 200), until: +m.until || 0 })), off: (Array.isArray(t.off) ? t.off : []).map(String).slice(0, 10) }; if (p.set.tick) b.ticker = ""; }
      b.map = { on: !!(b.map && b.map.on), staffOnly: !(b.map && b.map.staffOnly === false) };
      b.delay = Math.max(0, Math.min(900, parseInt(b.delay, 10) || 0));
      b.cooldown = Math.max(0, Math.min(600, parseInt(b.cooldown, 10) || 0));
    }
    if (Array.isArray(p.tributes)) for (const t of p.tributes.slice(0, 999)) {
      if (!t || !t.id || !cleanLogin(t.login)) continue;
      const i = b.tributes.findIndex(x => x.id === t.id);
      const prev = i >= 0 ? b.tributes[i] : null;
      if (!STATUSES.includes(t.status)) t.status = "alive";
      t.discordId = String(t.discordId || "").replace(/\D/g, "").slice(0, 20);
      t.mc = String(t.mc || "").replace(/[^A-Za-z0-9_]/g, "").slice(0, 16);
      // horodatage serveur des changements de statut (sert au différé)
      if (prev && prev.status === t.status) { t.statusAt = prev.statusAt || 0; t.prevStatus = prev.prevStatus || ""; t.diedAt = prev.diedAt || t.diedAt || 0; }
      else { t.prevStatus = t.status; t.statusAt = prev ? now : 0; }
      if (t.status === "dead" && (!prev || prev.status !== "dead")) t.diedAt = now;
      if (t.status === "injured" && (!prev || prev.status !== "injured")) t.injAt = now;
      if (i >= 0) b.tributes[i] = t; else if (b.tributes.length < 999) b.tributes.push(t);
    }
    if (Array.isArray(p.delTributes)) { const d = new Set(p.delTributes); b.tributes = b.tributes.filter(t => !d.has(t.id)); }
    if (Array.isArray(p.events)) {
      const have = new Set(b.events.map(e => e.id));
      for (const e of p.events) if (e && e.id && !have.has(e.id)) b.events.push({ ...e, ts: now, now: true, text: String(e.text || "").slice(0, 400) });
    }
    if (Array.isArray(p.delEvents)) { const d = new Set(p.delEvents); b.events = b.events.filter(e => !d.has(e.id)); }
    b.events.sort((x, y) => x.ts - y.ts);
    if (b.events.length > 300) b.events = b.events.slice(-300);
  }

  recalcFund() {
    const b = this.board;
    b.fund = { raised: 0, goal: 0, cur: "€", show: true, ...(b.fund || {}) };
    b.fund.raised = Math.round((b.fundLog || []).reduce((s, e) => s + (e.amount || 0), 0) * 100) / 100;
  }

  cleanTeam(arr) {
    if (!Array.isArray(arr)) return this.team;
    const seen = new Set();
    return arr.slice(0, 150).map(m => {
      const rid = this.roles.some(r => r.id === m.rid) ? m.rid : "equipe";
      const perms = (this.roles.find(r => r.id === rid) || { perms: [] }).perms;
      return {
        id: String(m && m.id || "").replace(/\D/g, "").slice(0, 20),
        name: String(m.name || "").slice(0, 32), av: String(m.av || "").slice(0, 300),
        role: String(m.role || "").slice(0, 40), rid,
        perm: perms.includes("admin") ? "admin" : perms.includes("mod") ? "mod" : "member", cam: perms.includes("chef") ? "chef" : perms.includes("cam") ? "cam" : "",
        twitch: cleanLogin(m.twitch) || "", vsrc: ["auto", "ctv", "twitch"].includes(m.vsrc) ? m.vsrc : "auto", mc: String(m.mc || "").replace(/[^A-Za-z0-9_]/g, "").slice(0, 16), bio: String(m.bio || "").slice(0, 200)
      };
    }).filter(m => m.id && !seen.has(m.id) && seen.add(m.id));
  }
  cleanRoles(arr) {
    if (!Array.isArray(arr)) return this.roles;
    const seen = new Set(), out = [];
    for (const r of arr.slice(0, 30)) {
      let id = SLUG(r && (r.id || r.name)) || "role";
      while (seen.has(id)) id += "-2";
      seen.add(id);
      out.push({ id, name: String(r.name || "Rôle").slice(0, 30), color: /^#[0-9a-f]{6}$/i.test(r.color) ? r.color : "#9a9284", perms: (Array.isArray(r.perms) ? r.perms : []).filter(p => ROLE_PERMS.includes(p)), locked: id === "org" });
    }
    if (!out.some(r => r.id === "org")) out.unshift(defaultRoles()[0]);
    out.find(r => r.id === "org").perms = ["admin"];
    if (!out.some(r => r.id === "equipe")) out.push(defaultRoles()[5]);
    return out;
  }
  /* gestion des rôles : depuis le site comme depuis le jeu */
  async onRolesOp(a, m, reply) {
    setTimeout(() => this.pushTalk(), 300);
    const admin = a.perm === "admin";
    if (!hasPerm(a, "roles")) return reply(false, { msg: "Il faut la permission « Gérer les rôles »." });
    const adminRole = rid => !!(this.roles.find(r => r.id === rid) || { perms: [] }).perms.includes("admin");
    const touchesAdmin = (before, after) => {
      for (const x of after) { const old = before.find(y => y.id === x.id); if ((old && adminRole(old.rid)) !== adminRole(x.rid)) return true; }
      return before.some(y => adminRole(y.rid) && !after.find(x => x.id === y.id));
    };
    if (m.t === "roles") {
      const next = this.cleanRoles(m.roles);
      if (!admin) {
        const bad = next.some(r => r.perms.includes("admin") && !(this.roles.find(o => o.id === r.id) || { perms: [] }).perms.includes("admin"))
          || next.some(r => r.perms.includes("roles") && !(this.roles.find(o => o.id === r.id) || { perms: [] }).perms.includes("roles"));
        if (bad) return reply(false, { msg: "Seule la régie peut donner les permissions « Régie » et « Gérer les rôles »." });
      }
      this.roles = next;
      this.team = this.cleanTeam(this.team);
      await this.ctx.storage.put("roles", this.roles); await this.ctx.storage.put("team", this.team);
    } else {
      let list;
      if (m.t === "setRole") {
        if (!this.team.some(x => x.id === m.uid)) return reply(false, { msg: "Membre inconnu." });
        list = this.team.map(x => x.id === m.uid ? { ...x, rid: m.role } : x);
      } else list = m.team;
      const next = this.cleanTeam(list);
      if (!admin && touchesAdmin(this.team, next)) return reply(false, { msg: "Seule la régie peut donner ou retirer le rôle Organisation." });
      if (!admin && next.some(x => { const old = this.team.find(y => y.id === x.id); return (!old || old.rid !== x.rid) && (this.roles.find(r => r.id === x.rid) || { perms: [] }).perms.includes("roles"); }))
        return reply(false, { msg: "Seule la régie peut donner un rôle qui gère les rôles." });
      this.team = next;
      await this.ctx.storage.put("team", this.team);
    }
    this.broadcast({ t: "team", team: this.team, roles: this.roles });
    this.modcast({ t: "roles", roles: this.roles });
    this.refreshPerms(); this.refreshLocks(); this.schedulePresence();
    this.modcast({ t: "team", team: this.teamForMod() }); this.camsChanged();
    if (this.prog.live) this.pushProg(false);
    return reply(true);
  }

  /* =====================================================================
     Émission en direct : scènes multi-caméras composées par la réalisation
     ===================================================================== */
  srcOf(it) {
    if (it.k === "trib") { const t = this.board.tributes.find(x => x.id === it.ref); return t ? { login: t.login, name: t.name, sub: t.district ? "District " + t.district : "", st: t.status, src: "t:" + t.id, vsrc: t.vsrc || "auto", mc: t.mc || "" } : null; }
    if (it.k === "cam") { const m = this.team.find(x => x.id === it.ref); return m && (m.twitch || this.pubs["c:" + m.id]) ? { login: m.twitch || "", name: m.name, sub: "Caméra", src: "c:" + m.id, vsrc: m.vsrc || "auto", mc: m.mc || "" } : null; }
    if (it.k === "media") { const md = this.media.find(x => x.id === it.ref); return md ? { media: md.id, name: md.name, sub: md.kind === "video" ? "Vidéo" : md.kind === "image" ? "Image" : "Son", kind: md.kind, mime: md.mime, dur: md.dur || 0 } : null; }
    return null;
  }
  /* ce qui est réellement à l'antenne : en mode manuel, l'état figé au dernier « Appliquer » */
  liveItems() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.live); if (!sc) return [];
    return this.prog.apply === "manual" && Array.isArray(this.prog.airItems) && this.prog.airScene === sc.id ? this.prog.airItems : sc.items;
  }
  liveGfx() { return (this.prog.apply === "manual" && this.prog.airGfx ? this.prog.airGfx : this.prog.gfx) || {}; }
  freezeAir() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.live);
    this.prog.airScene = sc ? sc.id : ""; this.prog.airItems = sc ? JSON.parse(JSON.stringify(sc.items)) : [];
    this.prog.airGfx = JSON.parse(JSON.stringify(this.prog.gfx || {}));
  }
  progPublic() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.live);
    const starts = this.prog.starts || (this.prog.starts = {});
    // média ajouté au plan déjà à l'antenne : il démarre maintenant (sinon chacun le relançait en boucle)
    if (sc) for (const it of this.liveItems()) if (it.k === "media" && !starts[it.ref]) { starts[it.ref] = Date.now(); this.ctx.storage.put("prog", this.prog); }
    const items = sc ? this.liveItems().map(it => { const s = this.srcOf(it); return s ? { id: it.id, k: it.k, ref: it.ref, x: it.x, y: it.y, w: it.w, h: it.h, z: it.z, label: it.label !== false, audio: !!it.audio, head: !!it.head, hide: !!it.hide, loop: !!it.loop, vol: it.vol == null ? 100 : it.vol, d: it.d || 0, at: starts[it.ref] || 0, ...s } : null; }).filter(Boolean) : [];
    const fx = this.prog.sfx, md = fx && this.media.find(x => x.id === fx.id);
    return { seq: this.prog.seq, scene: sc ? sc.name : "", items, trans: this.prog.trans, title: this.prog.title || "", on: !!sc,
      gfx: this.liveGfx(), replay: this.prog.replay && Date.now() < this.prog.replay.end ? this.prog.replay : null, next: this.prog.next || null,
      clip: this.prog.clip && Date.now() < this.prog.clip.end ? this.prog.clip : null,
      screen: this.prog.screen || null, bgm: this.bgmPublic(sc),
      sfx: md ? { id: md.id, name: md.name, kind: md.kind, at: fx.at, vol: fx.vol, loop: !!fx.loop, dur: md.dur || 0 } : null };
  }
  /* musique de fond : celle du plan à l'antenne, sinon celle de tous les plans */
  bgmPublic(sc) {
    const own = sc && sc.bgm && sc.bgm.ids.length ? { ...sc.bgm, at: this.prog.liveAt || 0, scope: "scene" } : null;
    const g = this.prog.bgm && this.prog.bgm.ids && this.prog.bgm.ids.length ? { ...this.prog.bgm, scope: "all" } : null;
    const b = own || g; if (!b) return null;
    const tracks = b.ids.map(id => this.media.find(x => x.id === id)).filter(x => x && x.kind === "audio" && !x.dl).map(x => ({ id: x.id, name: x.name, dur: x.dur || 0, credit: x.credit || "", yt: x.yt || undefined, sc: x.sc && !x.n ? x.sc : undefined }));
    return tracks.length ? { tracks, at: b.at || 0, vol: b.vol == null ? 60 : b.vol, scope: b.scope } : null;
  }
  onAirCams() {
    return [...new Set(this.liveItems().filter(it => it.k === "cam").map(it => it.ref))];
  }
  cleanProg(p) {
    const num = (v, d, lo, hi) => { v = +v; return isFinite(v) ? Math.max(lo, Math.min(hi, Math.round(v * 100) / 100)) : d; };
    const scenes = (Array.isArray(p.scenes) ? p.scenes : []).slice(0, 40).map((sc, i) => ({
      id: String(sc.id || "s" + i).replace(/[^a-z0-9_-]/gi, "").slice(0, 20) || "s" + i,
      name: String(sc.name || "Plan " + (i + 1)).slice(0, 40),
      items: (Array.isArray(sc.items) ? sc.items : []).slice(0, MAX_ITEMS).map((it, j) => ({
        id: String(it.id || "i" + j).replace(/[^a-z0-9_-]/gi, "").slice(0, 20) || "i" + j,
        k: ["cam", "media"].includes(it.k) ? it.k : "trib", ref: String(it.ref || "").slice(0, 40), loop: !!it.loop, vol: num(it.vol, 100, 0, 100),
        x: num(it.x, 0, -50, 150), y: num(it.y, 0, -50, 150), w: num(it.w, 50, 2, 200), h: num(it.h, 50, 2, 200), z: num(it.z, j, 0, 99),
        label: it.label !== false, audio: !!it.audio, am: !!it.am, head: !!it.head, hide: !!it.hide, d: num(it.d, 0, 0, 4000)
      })),
      bgm: cleanBgm(sc.bgm)
    }));
    if (!scenes.length) scenes.push({ id: "s1", name: "Plan 1", items: [] });
    const t = p.trans || {};
    return { scenes, live: scenes.some(x => x.id === this.prog.live) ? this.prog.live : "", preview: scenes.some(x => x.id === p.preview) ? p.preview : scenes[0].id,
      trans: { type: ["logo", "fade", "cut"].includes(t.type) ? t.type : "logo", ms: num(t.ms, 1400, 300, 4000) }, seq: this.prog.seq, title: String(p.title || "").slice(0, 80),
      starts: this.prog.starts || {}, sfx: this.prog.sfx || null,
      favs: (Array.isArray(p.favs) ? p.favs : []).slice(0, 12).map(x => scenes.some(sc => sc.id === x) ? x : ""),
      gfx: this.prog.gfx || {}, queue: this.prog.queue || [], qrun: this.prog.qrun || null, next: this.prog.next || null, replay: this.prog.replay || null, autoBack: this.prog.autoBack !== false, prevLive: this.prog.prevLive || "",
      apply: this.prog.apply === "manual" ? "manual" : "auto", screen: this.prog.screen || null, bgm: this.prog.bgm || null, liveAt: this.prog.liveAt || 0, airScene: this.prog.airScene || "", airItems: this.prog.airItems || null, airGfx: this.prog.airGfx || null };
  }
  /* état de l'antenne horodaté : la page Direct l'affiche exactement un différé plus tard, pour tout le monde */
  progPub(newShot) {
    const p = this.progPublic(), key = JSON.stringify(p), now = Date.now();
    if (key !== this.progKey || !this.progAt) {
      const first = !this.progKey;
      this.progKey = key;
      // après un redémarrage, on garde l'heure enregistrée si l'antenne n'a pas changé
      const last = this.phist[this.phist.length - 1];
      if (first && last && last.key === hash32(key)) this.progAt = last.at;
      else {
        this.progAt = now;
        const keep = now - Math.max(60, (this.board.delay || 0) + 60) * 1000;
        const h = this.phist.filter(e => e.at >= keep);
        const before = this.phist.filter(e => e.at < keep).pop();
        this.phist = [...(before ? [before] : []), ...h, { at: now, key: hash32(key), p, cut: !!newShot }].slice(-60);
        this.ctx.storage.put("phist", this.phist);
      }
    }
    return { ...p, pubAt: this.progAt };
  }
  progHistFor() {
    const D = (this.board.delay || 0) * 1000; if (!D) return [];
    const from = Date.now() - D - 5000, h = this.phist, i = h.findIndex(e => e.at >= from);
    const list = i < 0 ? h.slice(-1) : h.slice(Math.max(0, i - 1));
    return list.map(e => ({ ...e.p, pubAt: e.at, cut: e.cut }));
  }
  pushProg(newShot) {
    const pub = JSON.stringify({ t: "prog", p: this.progPub(newShot), cut: !!newShot });
    // la page Direct est réservée à l'équipe et au lien OBS
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server && !a.lock && (isCrew(a) || a.overlay)) this.send(ws, pub); }
    this.pushOnAir(true);
  }
  prepCams() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.preview);
    const live = new Set(this.onAirCams());
    return sc ? [...new Set(sc.items.filter(it => it.k === "cam" && !live.has(it.ref)).map(it => it.ref))] : [];
  }
  /** Antenne + plan préparé → caméras. Retient depuis quand chaque caméra est en flux propre (différé). */
  pushOnAir(force) {
    const ids = this.onAirCams(), prep = this.prepCams(), now = Date.now(), cs = this.cleanSince || (this.cleanSince = {});
    const set = new Set([...ids, ...prep]);
    for (const id of Object.keys(cs)) if (!set.has(id)) delete cs[id];
    for (const id of set) if (!cs[id]) cs[id] = now;
    const key = JSON.stringify([ids, prep]);
    if (!force && key === this.lastOnAir) return;
    this.lastOnAir = key;
    const msg = { t: "onair", ids, prep, since: cs, delay: this.board.delay || 0 };
    this.modcast(msg);
    this.crewcast(msg, x => !x.mod);
  }
  async mediaApi(req, path) {
    let user = null;
    try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    const J = (o, st = 200) => Response.json(o, { status: st });
    if (!user || !user.id || !hasPerm({ perm: this.permOf(user.id), perms: this.permsOf(user.id) }, "program")) return J({ msg: "Il faut la permission « Réalisation de l'émission »." }, 403);
    if (!this.env.MEDIA) return J({ msg: "Stockage des médias pas encore activé sur le serveur." }, 503);
    const parts = path.split("/").filter(Boolean); // api, media, id?, n?
    const id = String(parts[2] || "").replace(/[^a-z0-9]/gi, "");
    const pushList = () => { this.ctx.storage.put("media", this.media); this.crewcast({ t: "media", list: this.media }, x => !x.mod); };
    // n'importe quelle musique : recherche YouTube (lue ensuite par le lecteur YouTube intégré, rien n'est téléchargé)
    if (req.method === "GET" && id === "yt") {
      const q = (new URL(req.url).searchParams.get("q") || "").trim().slice(0, 100);
      if (q.length < 2) return J({ list: [] });
      const one = ytId(q);
      if (one) {
        const o = await fetch("https://www.youtube.com/oembed?format=json&url=" + encodeURIComponent("https://www.youtube.com/watch?v=" + one)).then(r => r.ok ? r.json() : null).catch(() => null);
        return J({ list: o ? [{ yt: one, title: String(o.title || "Vidéo YouTube").slice(0, 100), creator: String(o.author_name || "").slice(0, 60), dur: 0 }] : [] });
      }
      const ck = "yts:" + q.toLowerCase();
      const hit = await this.env.MEDIA.get(ck, "json").catch(() => null);
      if (hit && Date.now() - hit.at < 86400000) return J({ list: hit.list });
      let list = [];
      if (this.env.YT_API_KEY) list = await ytApiSearch(q, this.env.YT_API_KEY).catch(() => []);
      if (!list.length) list = await ytScrape(q).catch(() => []);
      if (!list.length) return J({ list: [], msg: "YouTube ne répond pas pour le moment : colle directement le lien d'une vidéo." });
      await this.env.MEDIA.put(ck, JSON.stringify({ at: Date.now(), list }), { expirationTtl: 172800 }).catch(() => {});
      return J({ list });
    }
    // SoundCloud : recherche et lecture complète dans le site (lecteur SoundCloud intégré, sans pub)
    if (req.method === "GET" && id === "sc") {
      const q = (new URL(req.url).searchParams.get("q") || "").trim().slice(0, 200);
      if (q.length < 2) return J({ list: [] });
      const ck = "scs:" + q.toLowerCase();
      const hit = await this.env.MEDIA.get(ck, "json").catch(() => null);
      if (hit && Date.now() - hit.at < 6 * 3600000) return J({ list: hit.list });
      let list = [];
      try { list = await scSearch(this.env, q); } catch (e) { return J({ list: [], msg: "SoundCloud ne répond pas pour le moment : colle directement le lien d'un titre SoundCloud." }); }
      await this.env.MEDIA.put(ck, JSON.stringify({ at: Date.now(), list }), { expirationTtl: 43200 }).catch(() => {});
      return J({ list });
    }
    // image d'une scène projetée dans le ciel (PNG préparé par le navigateur)
    if (req.method === "POST" && id === "skyimg") {
      const buf = new Uint8Array(await req.arrayBuffer());
      if (!buf.length || buf.length > 600000 || buf[0] !== 0x89 || buf[1] !== 0x50) return J({ msg: "Image PNG attendue (600 Ko max)." }, 400);
      const k = [...new Uint8Array(await crypto.subtle.digest("SHA-256", buf))].slice(0, 12).map(x => x.toString(16).padStart(2, "0")).join("");
      await this.env.MEDIA.put("skyimg:" + k, buf, { expirationTtl: 60 * 86400 });
      return J({ ok: true, key: k });
    }
    // écoute avant ajout : le son passe par le site (lecteur audio normal, rien d'extérieur)
    if (req.method === "GET" && id === "scplay") {
      const sid = String(new URL(req.url).searchParams.get("s") || "").replace(/\D/g, "").slice(0, 20);
      let src; try { src = await scSource(this.env, sid); } catch (e) { return new Response(e.message, { status: 502 }); }
      if (src.prog) { const r = await fetch(src.urls[0]); return new Response(r.body, { headers: { "Content-Type": src.mime, "Cache-Control": "no-store" } }); }
      const { readable, writable } = new TransformStream();
      (async () => { const w = writable.getWriter(); try { for (const u of src.urls.slice(0, 45)) { const r = await fetch(u); if (!r.ok) break; await w.write(new Uint8Array(await r.arrayBuffer())); } await w.close(); } catch (e) { try { await w.abort(e); } catch (x) {} } })();
      return new Response(readable, { headers: { "Content-Type": src.mime, "Cache-Control": "no-store" } });
    }
    // ajout : le titre est copié dans les médias du site → il se lit comme un MP3 importé (Direct, régie, mixeur)
    if (req.method === "POST" && id === "addsc") {
      const b = await req.json().catch(() => ({})), sid = String(b.sc || "").replace(/\D/g, "").slice(0, 20);
      if (!sid) return J({ msg: "Titre SoundCloud invalide." }, 400);
      const have = this.media.find(x => x.sc === sid);
      if (have && have.n && !have.dl) return J({ ok: true, media: have });
      const meta = have || { id: crypto.randomUUID().replace(/-/g, "").slice(0, 20), name: String(b.name || "Musique").slice(0, 100), kind: "audio", sc: sid, size: 0, dur: Math.max(0, +b.dur || 0), slot: "", by: user.name, at: Date.now(), n: 0, credit: String(b.credit || "").slice(0, 160) };
      meta.mime = "audio/mpeg"; meta.dl = 1; meta.n = 0;
      if (!have) this.media = [...this.media, meta].slice(-120);
      await this.env.MEDIA.put("meta:" + meta.id, JSON.stringify(meta));
      pushList();
      try { await this.scStart(meta); } catch (e) { return J({ msg: "SoundCloud : " + e.message }, 502); }
      return J({ ok: true, media: meta });
    }
    // Deezer : recherche dans tout le catalogue (extrait de 30 s pour écouter ; la lecture complète se fait dans l'appli Deezer)
    if (req.method === "GET" && id === "dz") {
      const q = (new URL(req.url).searchParams.get("q") || "").trim().slice(0, 100);
      if (q.length < 2) return J({ list: [] });
      const art = /^art(iste)?\s*:/i.test(q) ? q.replace(/^art(iste)?\s*:/i, "").trim() : "";
      const r = await fetch("https://api.deezer.com/search?limit=15&q=" + encodeURIComponent(art ? 'artist:"' + art + '"' : q)).catch(() => null);
      const d = r && r.ok ? await r.json().catch(() => ({})) : null;
      if (!d || d.error) return J({ list: [], msg: "Deezer ne répond pas pour le moment." });
      return J({ list: (d.data || []).map(x => ({ id: x.id, title: String(x.title || "").slice(0, 100), creator: String((x.artist || {}).name || "").slice(0, 60), dur: +x.duration || 0, preview: String(x.preview || ""), link: String(x.link || ""), cover: String((x.album || {}).cover_small || "") })) });
    }
    if (req.method === "POST" && id === "addyt") {
      const b = await req.json().catch(() => ({})), v = ytId(String(b.yt || ""));
      if (!v) return J({ msg: "Vidéo YouTube invalide." }, 400);
      const have = this.media.find(x => x.yt === v); if (have) return J({ ok: true, media: have });
      const nid = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
      const meta = { id: nid, name: String(b.name || "Musique").slice(0, 100), mime: "video/youtube", kind: "audio", yt: v, size: 0, dur: Math.max(0, +b.dur || 0), slot: "", by: user.name, at: Date.now(), n: 0, credit: String(b.credit || "").slice(0, 160) };
      await this.env.MEDIA.put("meta:" + nid, JSON.stringify(meta));
      this.media = [...this.media, meta].slice(-120);
      pushList();
      return J({ ok: true, media: meta });
    }
    // recherche de musiques libres de droits (Openverse : Jamendo, Freesound, Wikimedia…), sans rien télécharger
    if (req.method === "GET" && id === "search") {
      const q = (new URL(req.url).searchParams.get("q") || "").trim().slice(0, 80);
      if (q.length < 2) return J({ list: [] });
      const ck = "ovs:" + q.toLowerCase();
      const hit = await this.env.MEDIA.get(ck, "json").catch(() => null);
      if (hit && Date.now() - hit.at < 86400000) return J({ list: hit.list });
      // « art:nom » : toutes les musiques de cet artiste
      const art = /^art(iste)?\s*:/i.test(q) ? q.replace(/^art(iste)?\s*:/i, "").trim() : "";
      const base = art ? "creator=" + encodeURIComponent(art) : "q=" + encodeURIComponent(q);
      const ask = async extra => fetch("https://api.openverse.org/v1/audio/?page_size=20&" + base + extra, { headers: { "User-Agent": "CapitolTV/1.0 (charity Hunger Games show)", Accept: "application/json" } });
      let r = await ask("&license_type=commercial&category=music").catch(() => null);
      if (r && r.status === 400) r = await ask("&license_type=commercial").catch(() => null);
      if (!r || !r.ok) return J({ msg: r && r.status === 429 ? "Trop de recherches d'un coup : réessaie dans une minute." : "Recherche en ligne indisponible pour le moment." }, 502);
      const d = await r.json().catch(() => ({}));
      const list = (d.results || []).filter(x => x && x.url && /^https:\/\//.test(x.url)).map(x => ({
        ext: String(x.id || "").slice(0, 60), title: String(x.title || "Sans titre").slice(0, 80), creator: String(x.creator || "").slice(0, 60),
        license: ("CC " + String(x.license || "").toUpperCase() + " " + String(x.license_version || "")).trim(), url: x.url, dur: Math.round((+x.duration || 0) / 1000),
        src: String(x.source || x.provider || "").slice(0, 30), page: x.foreign_landing_url || "" }));
      await this.env.MEDIA.put(ck, JSON.stringify({ at: Date.now(), list }), { expirationTtl: 172800 }).catch(() => {});
      return J({ list });
    }
    // importe une musique trouvée en ligne dans les médias (le serveur la télécharge)
    if (req.method === "POST" && id === "import") {
      const b = await req.json().catch(() => ({}));
      let u; try { u = new URL(String(b.url || "")); } catch (e) { return J({ msg: "Adresse invalide." }, 400); }
      if (u.protocol !== "https:") return J({ msg: "Adresse invalide." }, 400);
      const r = await fetch(u.toString(), { headers: { "User-Agent": "CapitolTV/1.0" }, redirect: "follow" }).catch(() => null);
      if (!r || !r.ok) return J({ msg: "Téléchargement impossible depuis la source." }, 502);
      const mime0 = (r.headers.get("content-type") || "").split(";")[0].trim();
      if (mime0 && !/^audio\/|octet-stream|binary/.test(mime0)) return J({ msg: "Ce lien ne donne pas un fichier son." }, 400);
      const mime = /^audio\//.test(mime0) ? mime0 : /\.ogg(\?|$)/i.test(u.pathname) ? "audio/ogg" : /\.wav(\?|$)/i.test(u.pathname) ? "audio/wav" : "audio/mpeg";
      const buf = await r.arrayBuffer();
      if (!buf.byteLength || buf.byteLength > 60 * 1024 * 1024) return J({ msg: "Fichier vide ou trop gros (60 Mo maximum)." }, 400);
      const nid = crypto.randomUUID().replace(/-/g, "").slice(0, 20), n = Math.ceil(buf.byteLength / MEDIA_CHUNK);
      for (let i = 0; i < n; i++) await this.env.MEDIA.put(`c:${nid}:${i}`, buf.slice(i * MEDIA_CHUNK, (i + 1) * MEDIA_CHUNK));
      const meta = { id: nid, name: String(b.name || "Musique").slice(0, 80), mime, kind: "audio", size: buf.byteLength, dur: Math.max(0, +b.dur || 0), slot: "", by: user.name, at: Date.now(), n, cs: MEDIA_CHUNK, credit: String(b.credit || "").slice(0, 160), page: String(b.page || "").slice(0, 300) };
      await this.env.MEDIA.put("meta:" + nid, JSON.stringify(meta));
      this.media = [...this.media, meta].slice(-80);
      pushList();
      return J({ ok: true, media: meta });
    }
    if (req.method === "POST" && id === "new") {
      const b = await req.json().catch(() => ({}));
      const size = Math.floor(+b.size || 0);
      if (!size || size > 300 * 1024 * 1024) return J({ msg: "Fichier vide ou trop gros (300 Mo maximum)." }, 400);
      const mime = String(b.mime || "").slice(0, 80);
      const kind = /^video\//.test(mime) ? "video" : /^audio\//.test(mime) ? "audio" : /^image\//.test(mime) ? "image" : "";
      if (!kind) return J({ msg: "Format non pris en charge : vidéo (mp4, webm), son (mp3, ogg, wav) ou image." }, 400);
      const nid = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
      this.uploads = this.uploads || {};
      this.uploads[nid] = { id: nid, name: String(b.name || "Média").slice(0, 80), mime, kind, size, dur: Math.max(0, +b.dur || 0), slot: String(b.slot || "").slice(0, 20), by: user.name, n: Math.ceil(size / MEDIA_CHUNK), got: new Set() };
      return J({ id: nid, cs: MEDIA_CHUNK });
    }
    if (req.method === "PUT" && id) {
      const up = (this.uploads || {})[id], n = parseInt(parts[3], 10);
      if (!up) return J({ msg: "Envoi expiré : recommence." }, 404);
      if (!(n >= 0 && n < up.n)) return J({ msg: "Morceau invalide." }, 400);
      const buf = await req.arrayBuffer();
      await this.env.MEDIA.put(`c:${id}:${n}`, buf);
      up.got.add(n);
      if (up.got.size < up.n) return J({ ok: true, left: up.n - up.got.size });
      delete this.uploads[id];
      const meta = { id, name: up.name, mime: up.mime, kind: up.kind, size: up.size, dur: up.dur, slot: up.slot, by: up.by, at: Date.now(), n: up.n, cs: MEDIA_CHUNK };
      await this.env.MEDIA.put("meta:" + id, JSON.stringify(meta));
      if (up.slot) {
        for (const old of this.media.filter(x => x.slot === up.slot)) await this.dropMedia(old.id);
      }
      this.media = [...this.media.filter(x => x.id !== id), meta].slice(-80);
      pushList();
      return J({ ok: true, media: meta });
    }
    if (req.method === "DELETE" && id) {
      await this.dropMedia(id);
      pushList();
      return J({ ok: true });
    }
    if (req.method === "PATCH" && id) {
      const b = await req.json().catch(() => ({})), md = this.media.find(x => x.id === id);
      if (!md) return J({ msg: "Média introuvable." }, 404);
      if (b.name) md.name = String(b.name).slice(0, 80);
      if (b.dur > 0) md.dur = +b.dur;
      pushList();
      return J({ ok: true });
    }
    return J({ list: this.media });
  }
  async dropMedia(id) {
    const md = this.media.find(x => x.id === id);
    this.media = this.media.filter(x => x.id !== id);
    if (this.prog.sfx && this.prog.sfx.id === id) { this.prog.sfx = null; this.ctx.storage.put("prog", this.prog); this.pushProg(false); }
    if (!md) return;
    try { await this.env.MEDIA.delete("meta:" + id); for (let n = 0; n < (md.n || 1); n++) await this.env.MEDIA.delete(`c:${id}:${n}`); } catch (e) {}
  }

  /* plan suivant : file de plans programmés, sinon retour automatique après une vidéo */
  planNext(sc, now) {
    const q = this.prog.queue || [], run = this.prog.qrun;
    if (run && run.i >= 0 && run.i < q.length) {
      const e = q[run.i];
      let dur = e.dur;
      if (!dur) dur = this.mediaDur(sc);
      if (dur > 0 && run.i + 1 < q.length) { this.prog.next = { at: now + dur * 1000, scene: q[run.i + 1].scene, why: "file" }; return; }
      if (dur > 0 && run.i + 1 >= q.length) { this.prog.next = { at: now + dur * 1000, scene: "", why: "fin de file", end: true }; return; }
      this.prog.next = null; return;
    }
    const md = this.mediaDur(sc);
    if (this.prog.autoBack !== false && md > 0 && this.prog.prevLive && this.prog.scenes.some(x => x.id === this.prog.prevLive))
      this.prog.next = { at: now + md * 1000 + 300, scene: this.prog.prevLive, why: "fin du média" };
    else this.prog.next = null;
  }
  /* durée du plan quand il ne contient que des médias joués une fois (vidéo ou son) */
  mediaDur(sc) {
    if (!sc) return 0;
    const md = sc.items.filter(i => i.k === "media").map(i => ({ i, m: this.media.find(x => x.id === i.ref) })).filter(x => x.m && x.m.kind !== "image" && !x.i.loop && x.m.dur > 0);
    return md.length ? Math.max(...md.map(x => x.m.dur)) : 0;
  }
  async advanceQueue(a) {
    const q = this.prog.queue || [], run = this.prog.qrun; if (!run) return;
    run.i++;
    if (run.i >= q.length) { this.prog.qrun = null; this.prog.next = null; return; }
    await this.takeScene(q[run.i].scene, true);
  }
  async takeScene(sceneId, fromQueue) {
    const sc = this.prog.scenes.find(x => x.id === sceneId); if (!sc) return;
    const old = this.prog.scenes.find(x => x.id === this.prog.live), prevStarts = this.prog.starts || {}, starts = {}, now = Date.now();
    const was = new Set(old ? old.items.filter(i => i.k === "media").map(i => i.ref) : []);
    for (const it of sc.items) if (it.k === "media") starts[it.ref] = was.has(it.ref) && prevStarts[it.ref] ? prevStarts[it.ref] : now;
    this.prog.starts = starts;
    if (this.prog.live && this.prog.live !== sc.id) this.prog.prevLive = this.prog.live;
    if (this.prog.live !== sc.id) this.prog.liveAt = Date.now();
    this.prog.live = sc.id; this.prog.seq = (this.prog.seq || 0) + 1;
    this.freezeAir();
    if (!fromQueue) this.prog.qrun = null;
    this.planNext(sc, now);
  }
  async runNext() {
    const n = this.prog.next; if (!n || Date.now() < n.at - 200) return;
    this.prog.next = null;
    if (n.end) this.prog.qrun = null;
    else if (n.why === "file" && this.prog.qrun) await this.advanceQueue();
    else await this.takeScene(n.scene, false);
    await this.ctx.storage.put("prog", this.prog);
    this.broadcast({ t: "progEd", prog: this.prog, by: "" }, x => hasPerm(x, "program") && !x.lock);
    this.pushProg(true);
  }

  /* ---------- vidéo native : diffusion depuis le mod ou OBS (Cloudflare Realtime) ---------- */
  rtcOn() { return !!(this.env.RTC_APP_ID && this.env.RTC_APP_SECRET); }
  async sfu(path, method, body) {
    const r = await fetch(RTC_API + this.env.RTC_APP_ID + path, { method, headers: { Authorization: "Bearer " + this.env.RTC_APP_SECRET, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || j.errorCode) throw new Error((j.errorDescription || j.errorCode || "SFU " + r.status));
    return j;
  }
  keyFor(src) {
    for (const [k, v] of Object.entries(this.skeys)) if (v === src) return k;
    const k = (crypto.randomUUID() + crypto.randomUUID()).replace(/-/g, "").slice(0, 32);
    this.skeys[k] = src; this.ctx.storage.put("skeys", this.skeys);
    return k;
  }
  allKeys() {
    const out = {};
    for (const t of this.board.tributes) out["t:" + t.id] = this.keyFor("t:" + t.id);
    for (const m of this.team) out["c:" + m.id] = this.keyFor("c:" + m.id);
    return out;
  }
  myKeys(uid) {
    const out = {};
    if (this.inTeam(uid)) out["c:" + uid] = this.keyFor("c:" + uid);
    const t = this.board.tributes.find(x => x.discordId && x.discordId === uid);
    if (t) out["t:" + t.id] = this.keyFor("t:" + t.id);
    return out;
  }
  rtcAllowed(a) { return !!a && this.rtcOn() && !a.lock && (isCrew(a) || a.overlay); }
  pubsFor(a) {
    if (!this.rtcAllowed(a)) return {};
    const out = {};
    for (const [src, p] of Object.entries(this.pubs)) out[src] = { at: p.at, via: p.via, kinds: p.tracks.map(t => t.kind), sim: !!p.sim, lvl: p.lvl || "" };
    return out;
  }
  pushPubs() {
    for (const ws of this.sockets()) { const a = this.att(ws); if (!a || a.mod || a.server) continue; this.send(ws, { t: "pubs", pubs: this.pubsFor(a) }); }
    this.dvrWantPush();
  }
  broadcastHelloAll() { for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server) this.send(ws, this.hello(a)); } }
  async whip(req, path) {
    const parts = path.split("/").filter(Boolean); // whip, key, sid?
    const key = String(parts[1] || ""), src = this.skeys[key];
    const H = extra => ({ ...WHIP_CORS, ...extra });
    if (!src) return new Response("Clé de diffusion inconnue", { status: 404, headers: H() });
    if (!this.rtcOn()) return new Response("Vidéo native non configurée sur le serveur", { status: 503, headers: H() });
    if (req.method === "DELETE") {
      if (this.pubs[src] && (!parts[2] || this.pubs[src].sid === parts[2])) { delete this.pubs[src]; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); }
      return new Response(null, { status: 200, headers: H() });
    }
    if (req.method === "PATCH") return new Response(null, { status: 204, headers: H() });
    if (req.method === "GET") {
      // le joueur annonce son niveau de qualité choisi (?lvl=…), on renvoie la qualité effective
      const lvl0 = new URL(req.url).searchParams.get("lvl");
      // le joueur n'indique plus que son accord : « off » (refus) ou n'importe quel autre niveau (accord)
      const lvl = lvl0 === "off" ? "off" : lvl0 ? "on" : "";
      if (this.vpref[src] === "blocked") return Response.json(this.vqFor(src), { headers: H() });
      if (lvl && this.vpref[src] !== lvl && !(lvl === "on" && !this.vpref[src])) {
        this.vpref[src] = lvl; this.ctx.storage.put("vpref", this.vpref);
        if (lvl === "off" && this.pubs[src]) { delete this.pubs[src]; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); }
        this.crewcast({ t: "vpref", vpref: this.vpref }, x => !x.mod && !x.lock);
      }
      return Response.json(this.vqFor(src), { headers: H() });
    }
    if (req.method !== "POST") return new Response("Méthode non prise en charge", { status: 405, headers: H() });
    if (parts[2] === "perf") return this.perfReport(src, req, H);
    if (this.board.capOff) return new Response("Capture vidéo coupée par la régie", { status: 503, headers: H() });
    if (this.vpref[src] === "blocked") return new Response("Capture coupée par la régie", { status: 403, headers: H() });
    const sdp = await req.text();
    if (!sdp.includes("v=0") || sdp.length > 60000) return new Response("Offre SDP attendue", { status: 400, headers: H() });
    // sections média de l'offre : on publie chaque piste audio / vidéo
    const tracks = [];
    for (const sec of sdp.split(/\r?\nm=/).slice(1)) {
      const kind = sec.startsWith("video") ? "video" : sec.startsWith("audio") ? "audio" : "";
      const mid = (/\na=mid:(\S+)/.exec(sec) || [])[1];
      if (kind && mid != null && !/\na=(inactive|recvonly)/.test(sec)) tracks.push({ location: "local", mid, trackName: kind + "-" + mid });
    }
    if (!tracks.length) return new Response("Aucune piste à publier", { status: 400, headers: H() });
    try {
      const ses = await this.sfu("/sessions/new", "POST");
      const r = await this.sfu(`/sessions/${ses.sessionId}/tracks/new`, "POST", { sessionDescription: { type: "offer", sdp }, tracks });
      const ok = (r.tracks || []).filter(t => !t.errorCode).map(t => ({ name: t.trackName, kind: String(t.trackName).split("-")[0] }));
      if (!ok.length) throw new Error("pistes refusées");
      const ua = req.headers.get("User-Agent") || "";
      this.pubs[src] = { sid: ses.sessionId, tracks: ok, at: Date.now(), via: /CapitolTV/i.test(ua) ? "mod" : /obs/i.test(ua) ? "obs" : "autre", sim: /\na=simulcast:/.test(sdp), lvl: this.vqFor(src).level };
      this.ctx.storage.put("pubs", this.pubs);
      this.pushPubs();
      const who = this.srcName(src);
      this.crewcast({ t: "info", msg: "Flux vidéo reçu : " + who + " (" + this.pubs[src].via + ")" }, x => !x.mod && hasPerm(x, "program"));
      return new Response(r.sessionDescription.sdp, { status: 201, headers: H({ "Content-Type": "application/sdp", Location: `/whip/${key}/${ses.sessionId}` }) });
    } catch (e) {
      return new Response("Erreur de diffusion : " + e.message, { status: 502, headers: H() });
    }
  }
  /* le mod signale que la capture fait trop ramer le joueur : alerte pour l'orga */
  async perfReport(src, req, H) {
    let j = {}; try { j = await req.json(); } catch (e) {}
    const base = Math.round(+j.base || 0), fps = Math.round(+j.fps || 0), now = Date.now();
    const prev = this.perf[src] || {};
    this.perf[src] = { base, fps, h: Math.round(+j.h || 0), at: now, warned: prev.warned || 0 };
    if (now - (prev.warned || 0) > 10 * 60000 && base > 0 && fps < base * 0.75) {
      this.perf[src].warned = now;
      const t = src[0] === "t" ? this.board.tributes.find(x => "t:" + x.id === src) : null, m = src[0] === "c" ? this.team.find(x => "c:" + x.id === src) : null;
      const id = now.toString(36) + Math.random().toString(36).slice(2, 7);
      const al = { ch: t ? t.login : "", who: t ? t.name : m ? m.name : src, type: "Capture POV", detail: `La capture fait ramer ${t ? t.name : m ? m.name : "ce joueur"} : ${base} → ${fps} images/s malgré l'anti-lag. Tu peux couper sa POV (elle ne sera plus utilisable en régie).`, urgent: false, by: "Capitol TV", uid: "", ts: now, handled: false, perf: src };
      this.alerts[id] = al; await this.ctx.storage.put("alerts", this.alerts);
      this.broadcast({ t: "alert", id, a: al }, x => isCrew(x) && !x.lock);
    }
    this.ctx.storage.put("perf", this.perf);
    this.crewcast({ t: "perf", perf: this.perf }, x => !x.mod && !x.lock);
    return Response.json({ ok: true }, { headers: H() });
  }
  async foImport(req) {
    let j; try { j = await req.json(); } catch (e) { return new Response("JSON attendu", { status: 400 }); }
    if (!j || typeof j.data !== "object") return new Response("Données manquantes", { status: 400 });
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server) this.send(ws, { t: "reload" }); try { ws.close(4011, "Mise à jour"); } catch (e) {} }
    await this.ctx.storage.deleteAll();
    const entries = Object.entries(j.data).filter(([k]) => !["pubs", "moved", "from"].includes(k));
    for (let i = 0; i < entries.length; i += 100) await this.ctx.storage.put(Object.fromEntries(entries.slice(i, i + 100)));
    await this.ctx.storage.put("from", String(j.from || ""));
    this.loading = null; await this.load();
    this.pubs = {}; this.moved = null; MOVED = { url: "", t: 0 };
    return Response.json({ ok: true, keys: entries.length });
  }
  useInfo() {
    const mo = new Date().toISOString().slice(0, 7), b = this.usage && this.usage.m === mo ? this.usage.b : 0;
    const gb = Math.round(b / 1e8) / 10, budget = this.board.useBudget || 900;
    return { gb, budget, save: gb >= budget };
  }
  /* plafond de qualité fixé par la régie (par type de flux) */
  vqCap(src) {
    const q = this.board.vq || {}, k = src[0] === "t" ? "trib" : "cam";
    const v = typeof q[k] === "string" ? q[k] : "";
    return VQ_LEVELS[v] && v !== "off" ? v : (k === "trib" ? "hd" : "max");
  }
  /* qualité effective d'un flux : choix du joueur, limité par le plafond de la régie */
  vqFor(src) {
    // plus de choix côté joueur : toujours la qualité maximale autorisée, le mod baisse seul si le PC souffre
    const cap = this.vqCap(src), pref = this.vpref[src] || "";
    const off = pref === "off" || pref === "blocked" || !!this.board.capOff;
    const level = off ? "off" : cap, L = VQ_LEVELS[level];
    return { level, pref, cap, h: L.h, kbps: L.kbps, fps: L.fps, low: { h: 360, kbps: 600 }, blocked: pref === "blocked", capOff: !!this.board.capOff };
  }
  srcName(src) {
    const id = src.slice(2);
    if (src[0] === "t") { const t = this.board.tributes.find(x => x.id === id); return t ? t.name : "tribut"; }
    const m = this.team.find(x => x.id === id); return m ? m.name : "caméra";
  }
  async rtcApi(req, path) {
    let user = null;
    try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    const J = (o, st = 200) => Response.json(o, { status: st });
    if (!user || !user.id) return J({ msg: "Connexion requise" }, 401);
    const anon = user.overlay || user.guest;
    const a = { id: user.id, overlay: !!user.overlay, guest: !!user.guest, perm: anon ? "viewer" : this.permOf(user.id), cam: anon ? "" : this.camOf(user.id), perms: anon ? [] : this.permsOf(user.id), team: !anon && this.inTeam(user.id) };
    a.lock = this.isLocked(a);
    if (!this.rtcAllowed(a)) return J({ msg: "Flux réservés à l'équipe." }, 403);
    const b = await req.json().catch(() => ({}));
    try {
      if (path.endsWith("/talk")) {
        if (!this.talkSup(a)) return J({ msg: "Réservé à la régie." }, 403);
        const sdp = String(b.sdp || ""), mid = (/\nm=audio[\s\S]*?\na=mid:(\S+)/.exec(sdp) || [])[1];
        if (mid == null) return J({ msg: "Offre sans audio" }, 400);
        const ses = await this.sfu("/sessions/new", "POST");
        const r = await this.sfu(`/sessions/${ses.sessionId}/tracks/new`, "POST", { sessionDescription: { type: "offer", sdp }, tracks: [{ location: "local", mid, trackName: "regie" }] });
        this.talk.regie = { sid: ses.sessionId, tracks: [{ name: "regie", kind: "audio" }], by: user.name || "Régie", at: Date.now() };
        for (const id of this.talk.open) this.sendMod(id, { t: "talkCh", open: true, regie: true });
        this.pushTalk();
        return J({ sdp: r.sessionDescription.sdp });
      }
      if (path.endsWith("/pull")) {
        const talkSrc = String(b.src || "").startsWith("talk:");
        if (talkSrc && !this.talkSup(a)) return J({ msg: "Réservé à la régie." }, 403);
        const pub = talkSrc ? this.talk.cams[String(b.src).slice(5)] : this.pubs[String(b.src || "")];
        if (!pub) return J({ msg: "Ce flux n'est pas en ligne." }, 404);
        const ses = await this.sfu("/sessions/new", "POST");
        // couche basse (vignettes de l'Antenne) ou pleine qualité (programme, Direct, OBS)
        const rid = b.layer === "q" ? "q" : "f";
        const r = await this.sfu(`/sessions/${ses.sessionId}/tracks/new`, "POST", { tracks: pub.tracks.map(t => ({ location: "remote", sessionId: pub.sid, trackName: t.name, ...(pub.sim && t.kind === "video" ? { simulcast: { preferredRid: rid, priorityOrdering: "none", ridNotAvailable: "asciibetical" } } : {}) })) });
        const bad = (r.tracks || []).filter(t => t.errorCode);
        if (bad.length === pub.tracks.length) {
          // l'émetteur a disparu sans prévenir
          if (!talkSrc && this.pubs[b.src] === pub) { delete this.pubs[b.src]; this.ctx.storage.put("pubs", this.pubs); this.pushPubs(); }
          return J({ msg: "Flux interrompu." }, 410);
        }
        return J({ sid: ses.sessionId, offer: r.sessionDescription, renego: !!r.requiresImmediateRenegotiation, tracks: r.tracks });
      }
      if (path.endsWith("/answer")) {
        const sid = String(b.sid || "").replace(/[^\w-]/g, "");
        await this.sfu(`/sessions/${sid}/renegotiate`, "PUT", { sessionDescription: { type: "answer", sdp: String(b.sdp || "") } });
        return J({ ok: true });
      }
    } catch (e) { return J({ msg: "Vidéo : " + e.message }, 502); }
    return J({ msg: "?" }, 404);
  }

  /* ---------- interphone régie ↔ caméras ---------- */
  talkSup(a) { return !!a && !a.mod && !a.server && !a.overlay && !a.guest && (hasPerm(a, "program") || a.perm === "admin" || a.cam === "chef"); }
  talkCams() {
    const on = new Set(); for (const ws of this.sockets()) { const x = this.att(ws); if (x && x.mod) on.add(x.id); }
    return this.team.filter(m => CAMROLES.includes(this.camOf(m.id)) || on.has(m.id)).map(m => ({ id: m.id, name: m.name, online: on.has(m.id) }));
  }
  talkState() {
    const T = this.talk;
    return { cams: this.talkCams(), open: T.open, req: T.req, speak: T.speak, pubs: Object.fromEntries(Object.entries(T.cams).map(([k, v]) => [k, v.at])), regie: T.regie ? { by: T.regie.by, at: T.regie.at } : null };
  }
  pushTalk() {
    clearTimeout(this.talkT);
    this.talkT = setTimeout(() => { const st = this.talkState(); this.broadcast({ t: "talk", talk: st }, x => this.talkSup(x)); }, 150);
  }
  sendMod(uid, msg) { for (const ws of this.sockets()) { const x = this.att(ws); if (x && x.mod && x.id === uid) this.send(ws, msg); } }

  /* ---------- banque de clips ---------- */
  clipVisible(c, a) {
    if (a && (isCrew(a) || a.overlay) && !a.lock) return true;
    return c.kind === "twitch" || Date.now() >= (c.at || 0) + (this.board.delay || 0) * 1000;
  }
  clipsFor(a) {
    return this.clips.filter(c => c.ready && this.clipVisible(c, a)).map(c => ({ id: c.id, title: c.title, kind: c.kind, slug: c.slug || "", tid: c.tid || "", tname: c.tname || "", by: c.byName, mine: a && c.by === a.id, at: c.at, dur: c.dur || 0, parts: c.parts ? c.parts.length : 0, mime: c.mime || "", pinned: !!c.pinned, size: c.size || 0, trim: c.trim, pt: c.pt }));
  }
  pushClips() {
    clearTimeout(this.clipT);
    this.clipT = setTimeout(() => { for (const ws of this.sockets()) { const x = this.att(ws); if (!x || x.mod || x.server) continue; this.send(ws, { t: "clips", clips: this.clipsFor(x) }); } }, 300);
  }
  async dropClip(id) {
    const c = this.clips.find(x => x.id === id); this.clips = this.clips.filter(x => x.id !== id);
    if (c && c.kind === "ctv" && this.env.CLIPS) for (let n = 0; n < (c.parts || []).length; n++) try { await this.env.CLIPS.delete(`clip/${id}/${n}`); } catch (e) {}
  }
  /* ménage : on garde la banque sous 8 Go (les clips épinglés ne sont jamais supprimés) */
  async trimClips() {
    const MAX = 8 * 1024 * 1024 * 1024;
    let tot = this.clips.reduce((n, c) => n + (c.size || 0), 0);
    const old = this.clips.filter(c => !c.pinned && c.kind === "ctv").sort((x, y) => x.at - y.at);
    while (tot > MAX && old.length) { const c = old.shift(); tot -= c.size || 0; await this.dropClip(c.id); }
  }
  async clipApi(req, path) {
    let user = null;
    try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    const J = (o, st = 200) => Response.json(o, { status: st });
    if (!user || !user.id) return J({ msg: "Connexion requise" }, 401);
    const anon = user.overlay || user.guest;
    const a = { id: user.id, name: user.name, overlay: !!user.overlay, guest: !!user.guest, perm: anon ? "viewer" : this.permOf(user.id), cam: anon ? "" : this.camOf(user.id), perms: anon ? [] : this.permsOf(user.id), team: !anon && this.inTeam(user.id) };
    a.lock = this.isLocked(a);
    const mod = hasPerm(a, "program") || a.perm === "admin";
    const parts = path.split("/").filter(Boolean);
    // lecture d'un morceau : /clip/<id>/<n>
    if (parts[0] === "clip") {
      const c = this.clips.find(x => x.id === parts[1]);
      if (!c || !c.ready || !this.clipVisible(c, a) || !this.env.CLIPS) return new Response("Clip introuvable", { status: 404 });
      const ck = `clip/${c.id}/${parseInt(parts[2], 10) || 0}`;
      let o = await this.env.CLIPS.get(ck);
      if (!o && this.from) {
        // site de secours : clip repris une fois au site d'origine
        try { const r = await fetch(this.from + "/failover/clip?k=" + encodeURIComponent(ck), { headers: { "X-CTV-Failover": FO_SECRET(this.env) } }); if (r.ok) { const b = await r.arrayBuffer(); await this.env.CLIPS.put(ck, b); return new Response(b, { headers: { "Content-Type": c.mime || "video/webm" } }); } } catch (e) {}
      }
      if (!o) return new Response("Morceau introuvable", { status: 404 });
      return new Response(o.body, { headers: { "Content-Type": c.mime || "video/webm", "Cache-Control": "private, max-age=86400" } });
    }
    const id = String(parts[2] || "").replace(/[^a-z0-9]/gi, "");
    const save = () => { this.ctx.storage.put("clips", this.clips); this.pushClips(); };
    if (req.method === "GET") return J({ clips: this.clipsFor(a), used: mod ? this.clips.reduce((n, c) => n + (c.size || 0), 0) : undefined });
    if (req.method === "POST" && id === "twitch") {
      if (anon || a.lock) return J({ msg: "Connecte-toi pour ajouter un clip." }, 403);
      const b = await req.json().catch(() => ({}));
      const url = String(b.url || "");
      const slug = (/clips\.twitch\.tv\/(?:embed\?clip=)?([\w-]+)/.exec(url) || /twitch\.tv\/[\w]+\/clip\/([\w-]+)/.exec(url) || [])[1];
      if (!slug) return J({ msg: "Colle le lien d'un clip Twitch (clips.twitch.tv/… ou twitch.tv/chaine/clip/…)." }, 400);
      if (this.clips.some(c => c.slug === slug)) return J({ msg: "Ce clip est déjà dans la banque." }, 409);
      const t = this.board.tributes.find(x => x.id === b.tid);
      this.clips.push({ id: rid8(), kind: "twitch", slug, title: String(b.title || "Clip").slice(0, 90), tid: t ? t.id : "", tname: t ? t.name : "", by: a.id, byName: a.name || "?", at: Date.now(), dur: 30, ready: true });
      save(); return J({ ok: true });
    }
    if (req.method === "POST" && id === "new") {
      if (!(isCrew(a) || a.overlay) || a.lock || anon && !a.overlay) return J({ msg: "Clips vidéo réservés à l'équipe." }, 403);
      if (!this.env.CLIPS) return J({ msg: "Stockage des clips non configuré." }, 503);
      const b = await req.json().catch(() => ({}));
      const n = Math.max(1, Math.min(40, parseInt(b.n, 10) || 0));
      const t = this.board.tributes.find(x => x.id === b.tid), cm = this.team.find(x => "c:" + x.id === b.src);
      const c = { id: rid8(), kind: "ctv", title: String(b.title || "Clip").slice(0, 90), src: String(b.src || "").slice(0, 44), tid: t ? t.id : "", tname: t ? t.name : cm ? cm.name : "", by: a.id, byName: a.name || "Régie", at: Math.min(Date.now(), +b.at || Date.now()), dur: Math.max(1, Math.min(120, +b.dur || 30)), mime: String(b.mime || "video/webm").slice(0, 60), parts: new Array(n).fill(0), size: 0, ready: false,
        trim: b.trim && isFinite(+b.trim.in) ? { in: Math.max(0, Math.min(30, +b.trim.in)), dur: Math.max(1, Math.min(120, +b.trim.dur || +b.dur || 30)) } : undefined,
        pt: b.pt && isFinite(+b.pt.x) ? { x: Math.max(0, Math.min(1, +b.pt.x)), z: Math.max(1, Math.min(3, +b.pt.z || 1)) } : undefined };
      this.clips.push(c); this.ctx.storage.put("clips", this.clips);
      return J({ id: c.id });
    }
    if (req.method === "PUT" && id) {
      const c = this.clips.find(x => x.id === id), n = parseInt(parts[3], 10);
      if (!c || c.ready || c.by !== a.id) return J({ msg: "Clip inconnu." }, 404);
      if (!(n >= 0 && n < c.parts.length)) return J({ msg: "Morceau invalide." }, 400);
      const buf = await req.arrayBuffer();
      if (buf.byteLength > 30 * 1024 * 1024) return J({ msg: "Morceau trop gros." }, 413);
      await this.env.CLIPS.put(`clip/${id}/${n}`, buf, { httpMetadata: { contentType: c.mime } });
      c.parts[n] = buf.byteLength;
      if (c.parts.every(x => x > 0)) { c.ready = true; c.size = c.parts.reduce((x, y) => x + y, 0); await this.trimClips(); save(); }
      else this.ctx.storage.put("clips", this.clips);
      return J({ ok: true, ready: c.ready });
    }
    if (req.method === "PATCH" && id) {
      const c = this.clips.find(x => x.id === id); if (!c) return J({ msg: "Clip introuvable." }, 404);
      if (!mod && c.by !== a.id) return J({ msg: "Non autorisé." }, 403);
      const b = await req.json().catch(() => ({}));
      if (b.title) c.title = String(b.title).slice(0, 90);
      if (b.pinned != null && mod) c.pinned = !!b.pinned;
      save(); return J({ ok: true });
    }
    if (req.method === "DELETE") {
      if (id === "old") {
        if (!mod) return J({ msg: "Réservé à la régie." }, 403);
        const days = Math.max(0, +new URL(req.url).searchParams.get("days") || 0), lim = Date.now() - days * 86400000;
        for (const c of this.clips.filter(c => !c.pinned && c.at < lim)) await this.dropClip(c.id);
        save(); return J({ ok: true });
      }
      const c = this.clips.find(x => x.id === id); if (!c) return J({ ok: true });
      if (!mod && c.by !== a.id) return J({ msg: "Non autorisé." }, 403);
      await this.dropClip(id); save(); return J({ ok: true });
    }
    return J({ msg: "?" }, 404);
  }

  /* ---------- sondages à l'antenne ---------- */
  pollPublic(uid) {
    const p = this.poll; if (!p) return null;
    const counts = p.opts.map(() => 0);
    for (const v of Object.values(p.votes)) if (counts[v] != null) counts[v]++;
    return { id: p.id, q: p.q, opts: p.opts, counts, total: Object.keys(p.votes).length, at: p.at, end: p.end, closed: !!p.closed || (p.end && Date.now() > p.end), mine: uid && p.votes[uid] != null ? p.votes[uid] : null };
  }
  pushPoll() {
    clearTimeout(this.pollT);
    this.pollT = setTimeout(() => { for (const ws of this.sockets()) { const a = this.att(ws); if (!a || a.mod || a.server) continue; this.send(ws, { t: "poll", poll: this.pollPublic(a.id) }); } }, 400);
  }
  async onPollOp(a, m, reply) {
    const prog = hasPerm(a, "program") || a.perm === "admin";
    if (m.t === "pollStart") {
      if (!prog) return reply(false, { msg: "Réservé à la réalisation." });
      const opts = (Array.isArray(m.opts) ? m.opts : []).map(x => String(x || "").trim().slice(0, 60)).filter(Boolean).slice(0, 6);
      const q = String(m.q || "").trim().slice(0, 140);
      if (!q || opts.length < 2) return reply(false, { msg: "Une question et au moins deux réponses." });
      const dur = Math.max(0, Math.min(3600, +m.dur || 0));
      this.poll = { id: rid8(), q, opts, at: Date.now(), end: dur ? Date.now() + dur * 1000 : 0, votes: {} };
      if (this.tbot.ann.poll) this.botSay(`📊 SONDAGE : ${q} — ${opts.map((o, i) => "!" + (i + 1) + " " + o).join(" · ")}${dur ? " (" + (dur >= 60 ? Math.round(dur / 60) + " min" : dur + " s") + ")" : ""}`);
      this.scheduleAlarm();
    } else if (m.t === "pollEnd") {
      if (!prog) return reply(false, { msg: "Réservé à la réalisation." });
      if (!m.clear && this.poll && !this.poll.closed) this.botPollResult(this.poll);
      if (m.clear) this.poll = null; else if (this.poll) this.poll.closed = true;
    } else {
      const p = this.poll;
      if (!p || p.id !== m.id || p.closed || (p.end && Date.now() > p.end)) return reply(false, { msg: "Le sondage est terminé." });
      if (a.overlay) return reply(false, { msg: "Non autorisé." });
      const i = parseInt(m.i, 10); if (!(i >= 0 && i < p.opts.length)) return reply(false, { msg: "Réponse inconnue." });
      p.votes[a.id] = i;
    }
    this.ctx.storage.put("poll", this.poll);
    this.pushPoll();
    return reply(true);
  }

  /* ---------- bot Twitch : sondages, pronostics et annonces dans le chat ---------- */
  botPublic() {
    const T = this.tbot || {};
    return { on: !!T.on, channel: T.channel || "", cmds: T.cmds !== false, ann: T.ann || {}, account: T.auth ? T.auth.login : "", state: this.botState || "off", err: this.botErr || "", oauth: !!(this.env.TWITCH_CLIENT_ID && this.env.TWITCH_CLIENT_SECRET) };
  }
  botStatus() { const st = this.botPublic(); for (const ws of this.sockets()) { const a = this.att(ws); if (a && isStaff(a)) this.send(ws, { t: "bot", bot: st }); } }
  botCanSay() { return !!(this.botWs && this.tbot.auth && this.botState === "on"); }
  async botToken() {
    const A = this.tbot.auth; if (!A) return null;
    if (A.exp > Date.now() + 120000) return A.access;
    if (!A.refresh || !this.env.TWITCH_CLIENT_ID) return A.access;
    const r = await fetch("https://id.twitch.tv/oauth2/token", { method: "POST", body: new URLSearchParams({ client_id: this.env.TWITCH_CLIENT_ID, client_secret: this.env.TWITCH_CLIENT_SECRET, grant_type: "refresh_token", refresh_token: A.refresh }) }).catch(() => null);
    const j = r ? await r.json().catch(() => ({})) : {};
    if (!j.access_token) { this.botErr = "Le compte du bot doit être reconnecté (Réglages › Twitch)."; return null; }
    Object.assign(A, { access: j.access_token, refresh: j.refresh_token || A.refresh, exp: Date.now() + (j.expires_in || 3600) * 1000 });
    await this.ctx.storage.put("tbot", this.tbot);
    return A.access;
  }
  async botStart() {
    const T = this.tbot; if (!T.on || !T.channel || this.botWs || this.botConnecting) return;
    this.botConnecting = true; this.botState = "connecting"; this.botErr = "";
    try {
      const tok = T.auth ? await this.botToken() : null;
      const r = await fetch("https://irc-ws.chat.twitch.tv", { headers: { Upgrade: "websocket" } });
      const ws = r.webSocket; if (!ws) throw new Error("connexion au chat refusée");
      ws.accept(); this.botWs = ws; this.botLast = Date.now();
      ws.send("CAP REQ :twitch.tv/tags twitch.tv/commands");
      if (tok && T.auth) { ws.send("PASS oauth:" + tok); ws.send("NICK " + T.auth.login); }
      else { ws.send("PASS SCHMOOPIIE"); ws.send("NICK justinfan" + (10000 + Math.floor(Math.random() * 80000))); }
      ws.send("JOIN #" + T.channel);
      ws.addEventListener("message", e => { this.botLast = Date.now(); String(e.data).split("\r\n").forEach(l => l && this.botLine(l)); });
      const gone = why => { if (this.botWs !== ws) return; this.botWs = null; this.botState = T.on ? "retry" : "off"; if (why) this.botErr = why; this.botStatus(); if (T.on) setTimeout(() => this.botStart(), 15000); };
      ws.addEventListener("close", () => gone(""));
      ws.addEventListener("error", () => gone("connexion perdue"));
    } catch (e) { this.botWs = null; this.botState = "retry"; this.botErr = String(e.message || e); }
    finally { this.botConnecting = false; this.botStatus(); }
  }
  botClose() { const ws = this.botWs; this.botWs = null; this.botState = "off"; if (ws) try { ws.close(1000, "arrêt"); } catch (e) {} }
  botSay(text) {
    if (!text || !this.botCanSay()) return;
    this.botQ = this.botQ || []; this.botQ.push(text.slice(0, 480)); if (this.botQ.length > 30) this.botQ.shift();
    this.botFlush();
  }
  botFlush() {
    if (this.botFT || !this.botQ || !this.botQ.length) return;
    const ws = this.botWs; if (!ws) return;
    try { ws.send("PRIVMSG #" + this.tbot.channel + " :" + this.botQ.shift()); } catch (e) {}
    this.botFT = setTimeout(() => { this.botFT = null; this.botFlush(); }, 1600);
  }
  botLine(l) {
    if (l.startsWith("PING")) { try { this.botWs.send("PONG" + l.slice(4)); } catch (e) {} return; }
    const m = l.match(/^(?:@(\S+) )?:(\S+?)(?:!\S+)? (\S+)(?: (\S+))?(?: :(.*))?$/); if (!m) return;
    const [, tagS, , cmd, , text] = m;
    if (cmd === "001" || cmd === "JOIN" || cmd === "ROOMSTATE") { if (this.botState !== "on") { this.botState = "on"; this.botErr = ""; this.botStatus(); } return; }
    if (cmd === "NOTICE" && /authentication failed|improperly formatted/i.test(text || "")) { this.botErr = "Connexion du compte refusée : reconnecte le compte du bot."; if (this.tbot.auth) this.tbot.auth.exp = 0; this.botStatus(); return; }
    if (cmd !== "PRIVMSG" || !text) return;
    const tags = {}; (tagS || "").split(";").forEach(kv => { const i = kv.indexOf("="); if (i > 0) tags[kv.slice(0, i)] = kv.slice(i + 1); });
    if (this.tbot.cmds === false || text[0] !== "!") return;
    const uid = tags["user-id"]; if (!uid) return;
    this.botCmd({ id: "tw:" + uid, name: (tags["display-name"] || "viewer").replace(/\\s/g, " ").slice(0, 30) + " (Twitch)", nick: tags["display-name"] || "viewer" }, text.trim());
  }
  botCmd(u, text) {
    const [c0, ...args] = text.slice(1).split(/\s+/), c = (c0 || "").toLowerCase();
    const now = Date.now(), p = this.poll;
    const voteIdx = /^\d$/.test(c) ? +c - 1 : c === "vote" && /^\d$/.test(args[0] || "") ? +args[0] - 1 : -1;
    if (voteIdx >= 0) {
      if (!p || p.closed || (p.end && now > p.end) || voteIdx >= p.opts.length) return;
      p.votes[u.id] = voteIdx; this.ctx.storage.put("poll", p); this.pushPoll(); return;
    }
    if (c === "pari" || c === "parier" || c === "bet") {
      const B = this.bets; if (!B.open || B.settled) return this.botReply(u, "les pronostics sont fermés.");
      const q = (args[0] || "").toLowerCase(), t = /^\d+$/.test(q) ? this.board.tributes.find(x => x.num === +q) : this.board.tributes.find(x => x.login.toLowerCase() === q || String(x.name).toLowerCase() === q);
      if (!t) return this.botReply(u, "tribut inconnu. Exemple : !pari 7 200");
      if (t.status === "dead") return this.botReply(u, t.name + " est déjà tombé(e).");
      const us = this.betUser({ id: u.id, name: u.name }), amt = Math.max(10, Math.min(us.pts, Math.round(+args[1] || 100)));
      if (!us.pts) return this.botReply(u, "plus de points à miser.");
      us.pts -= amt; us.b[t.id] = (us.b[t.id] || 0) + amt;
      this.ctx.storage.put("bets", this.bets); this.pushBets();
      return this.botReply(u, `${amt} points sur ${t.name} (n°${t.num}). Reste : ${us.pts}.`);
    }
    if (c === "points" || c === "score") {
      const us = this.bets.users[u.id]; if (!us) return this.botReply(u, "tu n'as pas encore parié : 1000 points t'attendent, !pari <n°> <points>.");
      const pub = this.betsPublic(u.id); return this.botReply(u, `${pub.me ? pub.me.pts : us.pts} points${pub.me && pub.me.rank ? " · " + pub.me.rank + "e sur " + pub.players : ""}.`);
    }
    if (c === "tributs" || c === "vivants") {
      const pb = this.publicBoard(), alive = pb.tributes.filter(t => t.status !== "dead").length;
      return this.botReply(u, `${alive} tribut${alive > 1 ? "s" : ""} encore en lice sur ${pb.tributes.length}.`);
    }
    if (c === "aide" || c === "capitol" || c === "commandes") return this.botReply(u, "!1 !2… voter au sondage · !pari <n° tribut> <points> · !points · !tributs");
  }
  botReply(u, msg) { const k = u.id; this.botCd = this.botCd || {}; if (Date.now() - (this.botCd[k] || 0) < 4000) return; this.botCd[k] = Date.now(); this.botSay("@" + u.nick + " " + msg); }
  botPollResult(p) {
    if (!this.tbot.ann.result) return;
    const pub = this.pollPublic(null); if (!pub || !pub.total) return this.botSay("📊 Sondage terminé : " + p.q + " — aucun vote.");
    const best = pub.counts.indexOf(Math.max(...pub.counts));
    this.botSay(`📊 Résultat : ${p.q} → ${p.opts[best]} (${Math.round(pub.counts[best] / pub.total * 100)} %, ${pub.total} vote${pub.total > 1 ? "s" : ""})`);
  }
  /* annonces dans le chat, au rythme du différé (comme le site) */
  botTick() {
    if (!this.botCanSay()) return;
    const T = this.tbot, D = (this.board.delay || 0) * 1000, now = Date.now();
    if (this.botEvAt === undefined) { this.botEvAt = now - D; return; }
    for (const e of this.board.events || []) {
      if (!e.ts || e.ts + D > now || e.ts + D <= this.botEvAt) continue;
      const t = e.tid && this.board.tributes.find(x => x.id === e.tid);
      if (e.k === "death" && T.ann.death && t) this.botSay(`💀 Coup de canon : ${t.name}${t.district ? " (District " + t.district + ")" : ""} est tombé(e).`);
      if (e.k === "victor" && T.ann.victor && t) this.botSay(`👑 ${t.name} remporte les Jeux !`);
    }
    this.botEvAt = now;
    if (this.poll && this.poll.end && now > this.poll.end && !this.poll.botDone) { this.poll.botDone = true; this.botPollResult(this.poll); }
  }
  async botOAuth(req, path) {
    let user = null; try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    if (!user || this.permOf(user.id) !== "admin") return new Response("Réservé aux administrateurs.", { status: 403 });
    const cid = this.env.TWITCH_CLIENT_ID, sec = this.env.TWITCH_CLIENT_SECRET;
    if (!cid || !sec) return new Response("Ajoute d'abord TWITCH_CLIENT_ID et TWITCH_CLIENT_SECRET (voir README).", { status: 400 });
    const u = new URL(req.url), origin = req.headers.get("X-CTV-Origin") || "";
    if (path === "/twitchbot/start") {
      this.botState0 = crypto.randomUUID();
      const back = u.searchParams.get("o") || origin;
      this.botBack = back;
      const q = new URLSearchParams({ client_id: cid, redirect_uri: back + "/twitchbot/cb", response_type: "code", scope: "chat:read chat:edit", force_verify: "true", state: this.botState0 });
      return Response.redirect("https://id.twitch.tv/oauth2/authorize?" + q, 302);
    }
    if (u.searchParams.get("state") !== this.botState0 || !u.searchParams.get("code")) return new Response("Lien expiré : recommence depuis Réglages › Twitch.", { status: 400 });
    const r = await fetch("https://id.twitch.tv/oauth2/token", { method: "POST", body: new URLSearchParams({ client_id: cid, client_secret: sec, code: u.searchParams.get("code"), grant_type: "authorization_code", redirect_uri: this.botBack + "/twitchbot/cb" }) });
    const j = await r.json().catch(() => ({}));
    if (!j.access_token) return new Response("Twitch a refusé la connexion du bot.", { status: 400 });
    const v = await fetch("https://id.twitch.tv/oauth2/validate", { headers: { Authorization: "OAuth " + j.access_token } }).then(x => x.json()).catch(() => ({}));
    this.tbot.auth = { login: String(v.login || "").toLowerCase(), access: j.access_token, refresh: j.refresh_token || "", exp: Date.now() + (j.expires_in || 3600) * 1000 };
    await this.ctx.storage.put("tbot", this.tbot);
    this.botClose(); if (this.tbot.on && this.tbot.channel) this.botStart();
    this.botStatus();
    return Response.redirect(this.botBack + "/#reglages-twitch", 302);
  }

  /* ---------- pronostics « qui va gagner ? » (points fictifs) ---------- */
  betUser(a) {
    const u = this.bets.users[a.id] || (this.bets.users[a.id] = { n: a.name || "Spectateur", pts: 1000, b: {} });
    u.n = a.name || u.n; return u;
  }
  victorEvent() { return this.board.events.find(e => e.k === "victor" && e.tid); }
  betsPublic(uid) {
    const B = this.bets, now = Date.now(), D = (this.board.delay || 0) * 1000;
    const st = B.settled && now >= B.settled.at + D ? B.settled : null;
    const pool = {};
    for (const u of Object.values(B.users)) for (const [tid, v] of Object.entries(u.b)) pool[tid] = (pool[tid] || 0) + v;
    const rows = Object.entries(B.users).map(([id, u]) => ({ id, n: u.n, pts: st && st.pts[id] != null ? st.pts[id] : u.pts + Object.values(u.b).reduce((x, y) => x + y, 0) * 0, stake: Object.values(u.b).reduce((x, y) => x + y, 0) }));
    rows.sort((x, y) => (y.pts + (st ? 0 : y.stake)) - (x.pts + (st ? 0 : x.stake)));
    const me = uid && B.users[uid] ? { pts: st && st.pts[uid] != null ? st.pts[uid] : B.users[uid].pts, b: B.users[uid].b, rank: rows.findIndex(r => r.id === uid) + 1 } : null;
    return { open: !!B.open && !st, pool, top: rows.slice(0, 15).map(r => ({ n: r.n, pts: r.pts, stake: r.stake })), players: rows.length, me, winner: st ? st.tid : null };
  }
  pushBets() {
    clearTimeout(this.betT);
    this.betT = setTimeout(() => { for (const ws of this.sockets()) { const a = this.att(ws); if (!a || a.mod || a.server) continue; this.send(ws, { t: "bets", bets: this.betsPublic(a.id) }); } }, 600);
  }
  checkVictor() {
    const v = this.victorEvent(), B = this.bets;
    if (!v || (B.settled && B.settled.tid === v.tid)) return;
    // répartition mutuelle : les mises sur le vainqueur se partagent toute la cagnotte
    let pool = 0, onWin = 0;
    for (const u of Object.values(B.users)) for (const [tid, x] of Object.entries(u.b)) { pool += x; if (tid === v.tid) onWin += x; }
    const pts = {};
    for (const [id, u] of Object.entries(B.users)) {
      const stake = u.b[v.tid] || 0;
      pts[id] = u.pts + (onWin ? Math.round(stake / onWin * pool) : Object.values(u.b).reduce((x, y) => x + y, 0));
    }
    B.settled = { tid: v.tid, at: v.ts, pts };
    this.ctx.storage.put("bets", B);
    this.pushBets();
    this.scheduleAlarm();
  }
  async onBetOp(a, m, reply) {
    const B = this.bets, prog = hasPerm(a, "program") || a.perm === "admin";
    if (m.t === "betsOpen") { if (!prog) return reply(false, { msg: "Réservé à la régie." }); B.open = !!m.on; }
    else if (m.t === "betsReset") { if (a.perm !== "admin") return reply(false, { msg: "Réservé à la régie." }); this.bets = { open: true, users: {}, settled: null }; }
    else {
      if (a.overlay || a.lock) return reply(false, { msg: "Non autorisé." });
      if (!B.open || B.settled) return reply(false, { msg: "Les pronostics sont fermés." });
      const t = this.board.tributes.find(x => x.id === m.tid); if (!t) return reply(false, { msg: "Tribut inconnu." });
      const u = this.betUser(a), cur = u.b[t.id] || 0;
      const amt = Math.round(+m.amt || 0);
      const next = Math.max(0, cur + amt);
      if (next - cur > u.pts) return reply(false, { msg: "Pas assez de points." });
      u.pts -= next - cur; if (next) u.b[t.id] = next; else delete u.b[t.id];
    }
    this.ctx.storage.put("bets", this.bets);
    this.pushBets();
    return reply(true);
  }

  async onProgOp(a, m, reply) {
    if (!hasPerm(a, "program") || a.lock) return reply(false, { msg: "Il faut la permission « Réalisation de l'émission »." });
    const wasLive = JSON.stringify(this.progPublic());
    if (m.t === "progSave") {
      this.prog = this.cleanProg(m.prog || {});
    } else if (m.t === "progGfx") {
      const g = m.gfx || {}, str = (v, n) => String(v || "").slice(0, n);
      this.prog.gfx = {
        lower: { on: !!(g.lower && g.lower.on), ref: str(g.lower && g.lower.ref, 40), auto: !!(g.lower && g.lower.auto), text: str(g.lower && g.lower.text, 80) },
        breaking: { on: !!(g.breaking && g.breaking.on), text: str(g.breaking && g.breaking.text, 200) },
        alive: { on: !!(g.alive && g.alive.on) }, map: { on: !!(g.map && g.map.on) }, poll: { on: !!(g.poll && g.poll.on) }, bets: { on: !!(g.bets && g.bets.on) }
      };
    } else if (m.t === "progScreen") {
      // écran « hors antenne » ou « maintenance » par-dessus l'émission
      const k = ["off", "maint"].includes(m.kind) ? m.kind : "";
      this.prog.screen = k ? { kind: k, until: k === "maint" && +m.min > 0 ? Date.now() + Math.min(600, +m.min) * 60000 : 0, msg: String(m.msg || "").slice(0, 120), at: Date.now() } : null;
    } else if (m.t === "progBgm") {
      const b = cleanBgm({ ids: m.ids, vol: m.vol });
      if (m.scope === "all") this.prog.bgm = b.ids.length ? { ...b, at: Date.now() } : null;
      else { const sc = this.prog.scenes.find(x => x.id === m.scope); if (!sc) return reply(false, { msg: "Plan introuvable." }); sc.bgm = b; }
    } else if (m.t === "progApply") {
      // mode manuel : toutes les modifications du plan à l'antenne (et de l'habillage) partent d'un coup
      this.freezeAir();
    } else if (m.t === "progMode") {
      this.prog.apply = m.apply === "manual" ? "manual" : "auto";
      this.freezeAir();
    } else if (m.t === "progAuto") {
      this.prog.autoBack = !!m.on;
    } else if (m.t === "progQueue") {
      const q = (Array.isArray(m.queue) ? m.queue : []).slice(0, 30).map(x => ({ id: String(x.id || rid8()).slice(0, 12), scene: String(x.scene || "").slice(0, 20), dur: Math.max(0, Math.min(3600, Math.round(+x.dur || 0))) })).filter(x => this.prog.scenes.some(s => s.id === x.scene));
      this.prog.queue = q;
      if (m.run === true && q.length) { this.prog.qrun = { i: -1 }; await this.advanceQueue(a); }
      else if (m.run === false) { this.prog.qrun = null; this.prog.next = null; }
      else if (this.prog.qrun && this.prog.qrun.i >= q.length) { this.prog.qrun = null; this.prog.next = null; }
    } else if (m.t === "progReplay") {
      if (m.stop) this.prog.replay = null;
      else {
        const src = String(m.src || "").slice(0, 44), rate = Math.max(0.25, Math.min(1, +m.rate || 0.5)), now = Date.now();
        const from = +m.from > now - 15 * 60000 && +m.from < now ? +m.from : 0, to = from && +m.to > from ? Math.min(+m.to, from + 60000) : 0;
        const sec = from ? Math.max(1, (to - from) / 1000) : Math.max(5, Math.min(60, +m.sec || 30));
        const nm = String(m.name || "").slice(0, 60);
        this.prog.replay = { src, sec, rate, name: nm, at: now, end: now + sec / rate * 1000 + 800, from: from || undefined, to: to || undefined };
      }
    } else if (m.t === "progClip") {
      const c = m.id && this.clips.find(x => x.id === m.id);
      this.prog.clip = c ? { id: c.id, kind: c.kind, slug: c.slug || "", parts: c.parts ? c.parts.length : 0, mime: c.mime || "", title: c.title, name: c.tname || "", trim: c.trim, at: Date.now(), end: Date.now() + ((c.trim && c.trim.dur) || c.dur || 30) * 1000 + 1500 } : null;
    } else if (m.t === "progSfx") {
      const md = m.id && this.media.find(x => x.id === m.id);
      this.prog.sfx = md ? { id: md.id, at: Date.now(), vol: Math.max(0, Math.min(100, +m.vol || 100)), loop: !!m.loop } : null;
    } else {
      const sc = this.prog.scenes.find(x => x.id === m.scene);
      if (m.scene && !sc) return reply(false, { msg: "Plan introuvable." });
      // départ des médias : on garde l'instant de départ s'ils étaient déjà à l'antenne
      const old = this.prog.scenes.find(x => x.id === this.prog.live), prevStarts = this.prog.starts || {}, starts = {}, now = Date.now();
      const was = new Set(old ? old.items.filter(i => i.k === "media").map(i => i.ref) : []);
      if (sc) for (const it of sc.items) if (it.k === "media") starts[it.ref] = was.has(it.ref) && !m.restart && prevStarts[it.ref] ? prevStarts[it.ref] : now;
      this.prog.starts = starts;
      if (this.prog.live && this.prog.live !== (sc ? sc.id : "")) this.prog.prevLive = this.prog.live;
      if (!sc || this.prog.live !== sc.id) this.prog.liveAt = Date.now();
      this.prog.live = sc ? sc.id : "";
      this.freezeAir();
      if (!m.fromQueue && this.prog.qrun) { this.prog.qrun = null; }
      this.planNext(sc, now);
      this.prog.seq = (this.prog.seq || 0) + 1;
      if (m.trans) this.prog.trans = this.cleanProg({ ...this.prog, trans: m.trans }).trans;
    }
    await this.ctx.storage.put("prog", this.prog);
    this.broadcast({ t: "progEd", prog: this.prog, by: a.id }, x => hasPerm(x, "program") && !x.lock);
    this.dvrWantPush();
    if (m.t === "progTake") this.pushProg(true);
    else if (JSON.stringify(this.progPublic()) !== wasLive) this.pushProg(false);
    else this.pushOnAir(false);
    await this.scheduleAlarm();
    return reply(true);
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch (e) {}
    const a = this.att(ws);
    if (a && a.dvrPri != null) this.dvrElect(ws);
    if (a && a.server) this.crewcast({ t: "srv", on: this.serverOn(ws) });
    if (a && a.mod) { this.camsChanged(); if (this.talk && this.talk.cams[a.id]) { delete this.talk.cams[a.id]; delete this.talk.speak[a.id]; this.pushTalk(); } }
    this.schedulePresence();
  }
  async webSocketError() { this.schedulePresence(); }


  /* =====================================================================
     Caméras en jeu : mod Minecraft, comms, demandes, actes, carte
     ===================================================================== */
  aliveTributeAccount(a) { const t = this.board.tributes.find(x => x.discordId && x.discordId === a.id); return !!t && t.status !== "dead"; }
  mapAllowed(a) {
    if (!a || a.lock || a.overlay) return false;
    if (isCrew(a)) return true;
    if (this.aliveTributeAccount(a)) return false;
    return !!(this.board.map && this.board.map.on && !this.board.map.staffOnly);
  }
  camView(a) {
    return {
      feed: this.camData.feed.slice(-150), comms: this.camData.comms.filter(x => this.msgVisible(x, a)).slice(-150),
      reqs: Object.values(this.camData.reqs), cams: this.camsList(), srv: this.serverOn(), team: this.teamForMod()
    };
  }
  serverOn(except) { return this.sockets().some(ws => ws !== except && (this.att(ws) || {}).server); }
  tribsForMod() { return this.board.tributes.map(t => ({ id: t.id, name: t.name, mc: t.mc || "", login: t.login, num: t.num || 0, district: t.district || 0, status: t.status })); }
  onlineIds() {
    const site = new Set(), mod = new Set();
    for (const ws of this.sockets()) { const a = this.att(ws); if (!a || a.guest || a.overlay || a.server) continue; (a.mod ? mod : site).add(a.id); }
    return { site, mod };
  }
  teamForMod() {
    const { site, mod } = this.onlineIds();
    return this.team.map(m => ({ id: m.id, name: m.name, cam: m.cam || "", perm: m.perm, rid: m.rid || "", twitch: m.twitch || "", mc: m.mc || "", online: site.has(m.id) || mod.has(m.id), sup: m.perm === "admin" || m.perm === "mod" || m.cam === "chef" || (this.roles.find(r => r.id === m.rid) || { perms: [] }).perms.includes("program") || this.owners.includes(m.id) }));
  }
  camsList() {
    const { mod } = this.onlineIds(), ids = new Set();
    for (const m of this.team) if (CAMROLES.includes(m.cam)) ids.add(m.id);
    for (const id of mod) ids.add(id);
    return [...ids].map(id => {
      const m = this.team.find(x => x.id === id) || {}, st = this.camSt[id] || {};
      return { id, name: m.name || st.name || "?", cam: m.cam || (this.owners.includes(id) ? "chef" : ""), online: mod.has(id), st: st.state || "actif", follow: st.follow || "", mc: st.mc || m.mc || "", pos: st.pos || null, at: st.at || 0 };
    });
  }
  camsChanged() {
    if (this.talk) this.pushTalk();
    if (this.camsT) return;
    this.camsT = setTimeout(() => {
      this.camsT = null;
      this.crewcast({ t: "cams", cams: this.camsList() });
      this.crewcast({ t: "team", team: this.teamForMod() }, x => x.mod);
      this.sendServers({ t: "cams", names: this.camNames(), sups: this.supNames() });
    }, 1500);
  }
  camNames() {
    const out = new Set();
    for (const m of this.team) if (m.mc && CAMROLES.includes(this.camOf(m.id))) out.add(m.mc);
    for (const [id, st] of Object.entries(this.camSt)) if (st.mc && CAMROLES.includes(this.camOf(id))) out.add(st.mc);
    return [...out];
  }
  /** comptes Minecraft du staff à partir de responsable caméra (peuvent se rendre audibles en jeu) */
  supNames() {
    const out = new Set();
    for (const m of this.team) { if (!m.mc) continue; const p = this.permsOf(m.id); if (p.some(x => ["admin", "mod", "chef", "program"].includes(x))) out.add(m.mc); }
    return [...out];
  }
  sendServers(obj) { const j = JSON.stringify(obj); for (const ws of this.sockets()) { const a = this.att(ws); if (a && a.server) this.send(ws, j); } }
  saveCam(now) {
    clearTimeout(this.camSaveT);
    const go = () => { this.camSaveT = null; this.ctx.storage.put("cam", this.camData); this.ctx.storage.put("camst", this.camSt); };
    if (now) go(); else this.camSaveT = setTimeout(go, 4000);
  }

  async modConnect(req) {
    let who = null;
    try { who = JSON.parse(req.headers.get("X-CTV-Mod") || "null"); } catch (e) {}
    if (!who) return new Response("Requête invalide", { status: 400 });
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    if (this.moved) { this.send(server, { t: "moved", url: this.moved.url }); try { server.close(4010, "Site déplacé"); } catch (e) {} return new Response(null, { status: 101, webSocket: client }); }
    const refuse = (code, msg) => { this.send(server, { t: "err", msg }); try { server.close(code, msg); } catch (e) {} return new Response(null, { status: 101, webSocket: client }); };
    if (who.server) {
      if (!this.srvKey || who.key !== this.srvKey) return refuse(4003, "Clé serveur invalide (régie › Carte & caméras)");
      server.serializeAttachment({ id: "server", name: "Serveur Minecraft", server: true, at: Date.now() });
      this.send(server, { t: "hello", server: true, now: Date.now(), names: this.camNames(), sups: this.supNames(), terrain: Object.fromEntries(Object.entries(this.terrain || {}).map(([d, x]) => [d, x.id])) });
      this.crewcast({ t: "srv", on: true });
      return new Response(null, { status: 101, webSocket: client });
    }
    const u = who.user || {};
    if (!u.id || this.banned[u.id]) return refuse(4003, "Accès retiré");
    const a = { id: u.id, name: (this.team.find(m => m.id === u.id) || {}).name || u.name, av: u.av || "", perm: this.permOf(u.id), cam: this.camOf(u.id), perms: this.permsOf(u.id), team: this.inTeam(u.id), mod: true, at: Date.now() };
    if (!isCrew(a)) return refuse(4003, "Ton compte ne fait pas partie de l'équipe Capitol TV");
    server.serializeAttachment(a);
    this.camSt[a.id] = { ...(this.camSt[a.id] || {}), name: a.name, at: Date.now() };
    this.send(server, this.modHello(a));
    this.camsChanged();
    return new Response(null, { status: 101, webSocket: client });
  }
  modHello(a) {
    const alerts = Object.entries(this.alerts).sort((x, y) => y[1].ts - x[1].ts).slice(0, 80).map(([id, al]) => ({ id, ...al }));
    return {
      t: "hello", now: Date.now(), delay: this.board.delay || 0, srv: this.serverOn(),
      me: { id: a.id, name: a.name, perm: a.perm, cam: a.cam || "", sup: isSup(a), perms: a.perms || [], rid: (this.team.find(x => x.id === a.id) || {}).rid || "" },
      roles: this.roles, onair: this.onAirCams(), prep: this.prepCams(), streamKey: this.keyFor("c:" + a.id), vq: this.vqFor("c:" + a.id),
      talk: { open: this.talk.open.includes(a.id), regie: !!this.talk.regie },
      tribs: this.tribsForMod(), alerts, cams: this.camsList(), team: this.teamForMod(),
      feed: this.camData.feed.slice(-150), comms: this.camData.comms.filter(x => this.msgVisible(x, a)).slice(-150),
      reqs: Object.values(this.camData.reqs), pos: this.latestPos(), zones: this.zones || []
    };
  }

  /* ---------- différé partagé : un navigateur (OBS de préférence) enregistre les POV de l'antenne
     et les dépose par morceaux de 3 s ; toutes les pages Direct relisent le même passé ---------- */
  dvrElect(closing) {
    let best = null, bestA = null, cur = null;
    for (const ws of this.sockets()) {
      if (ws === closing) continue;
      const a = this.att(ws); if (!a || a.dvrPri == null || a.dvrPri <= 0) continue;
      if (a.dvrRec) cur = ws;
      if (!best || a.dvrPri > bestA.dvrPri || (a.dvrPri === bestA.dvrPri && (a.dvrAt || 0) < (bestA.dvrAt || 0))) { best = ws; bestA = a; }
    }
    if (cur && best && this.att(cur).dvrPri >= bestA.dvrPri) return;
    if (!this.dvrTok) { this.dvrTok = crypto.randomUUID(); this.ctx.storage.put("dvrtok", this.dvrTok); }
    if (cur && cur !== best) { const a = this.att(cur); a.dvrRec = false; cur.serializeAttachment(a); this.send(cur, { t: "dvrRole", on: false }); }
    if (best) { bestA.dvrRec = true; best.serializeAttachment(bestA); this.send(best, { t: "dvrRole", on: true, tok: this.dvrTok, want: this.dvrWant() }); }
  }
  /* sources que l'enregistreur doit garder : antenne, aperçu, et POV signalées par les caméras (jusqu'à ce qu'elles passent en différé) */
  dvrWant() {
    const now = Date.now(), D = (this.board.delay || 0) * 1000, w = {};
    if (!D) return w;
    const add = (src, ly, until) => { if (!src) return; const o = w[src]; if (!o || o.until < until || (ly === "f" && o.ly !== "f")) w[src] = { ly: o && o.ly === "f" ? "f" : ly, until: Math.max(until, o ? o.until : 0) }; };
    const big = it => (+it.w || 0) * (+it.h || 0) >= 1600;
    // toutes les POV en direct sont enregistrées dès qu'elles émettent : une source posée en régie montre
    // tout de suite l'image d'il y a un différé (même horloge pour tout le monde), sans nouveau compte à rebours
    const live = Object.keys(this.pubs || {}).filter(k => /^[tc]:/.test(k));
    for (const src of live) add(src, live.length <= 10 ? "f" : "q", now + D + 3600000);
    for (const id of [this.prog.live, this.prog.preview]) {
      const sc = (this.prog.scenes || []).find(x => x.id === id); if (!sc) continue;
      for (const it of sc.items || []) { if (it.k === "media") continue; const x = this.srcOf(it); if (x && x.src) add(x.src, big(it) ? "f" : "q", now + D + 3600000); }
    }
    for (const al of Object.values(this.alerts || {})) {
      if (!al.ts || al.ts < now - D - 90000) continue;
      const crew = al.src === "mod" || this.team.some(t => t.id === al.uid); if (!crew) continue;
      const t = this.board.tributes.find(x => x.login === al.ch); if (t) add("t:" + t.id, "q", al.ts + D + 90000);
      if (this.team.some(x => x.id === al.uid)) add("c:" + al.uid, "q", al.ts + D + 90000);
    }
    return w;
  }
  dvrWantPush() {
    const want = this.dvrWant(), j = JSON.stringify(Object.keys(want).sort().map(k => k + want[k].ly));
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && a.dvrRec) this.send(ws, { t: "dvrWant", want }); }
    this.dvrWantKey = j;
  }
  async dvrUpload(req) {
    const u = new URL(req.url), J = (o, st = 200) => Response.json(o, { status: st });
    if (!dvrStore(this.env)) return J({ msg: "Stockage du différé non configuré." }, 503);
    if (!this.dvrTok || req.headers.get("X-DVR-Tok") !== this.dvrTok) return J({ msg: "Pas l'enregistreur." }, 403);
    const src = u.searchParams.get("src") || "", t = Math.round(+u.searchParams.get("t")), end = Math.round(+u.searchParams.get("end"));
    const ly = u.searchParams.get("ly") === "q" ? "q" : "f", mt = (u.searchParams.get("mt") || "video/webm").slice(0, 60);
    if (!/^[tc]:[\w-]{1,40}$/.test(src) || !(t > 0) || !(end > t) || end - t > 15000 || Math.abs(Date.now() - end) > 120000) return J({ msg: "Morceau invalide." }, 400);
    const L = await this.dvrList(src);
    // déjà couvert (par un morceau de même qualité ou meilleure) : rien à faire
    if (L.some(e => e.t <= t + 400 && e.end >= end - 400 && (e.ly === "f" || ly === "q"))) return J({ ok: true, skip: true });
    const buf = await req.arrayBuffer();
    if (!buf.byteLength || buf.byteLength > 12 * 1024 * 1024) return J({ msg: "Taille invalide." }, 400);
    const k = src + "/" + t + "-" + end + "-" + ly + "-" + crypto.randomUUID().slice(0, 10);
    await dvrStore(this.env).put(k, buf, { httpMetadata: { contentType: mt } });
    const e = { t, end, u: k, ly, mt };
    L.push(e); L.sort((a, b) => a.t - b.t);
    const keep = Date.now() - ((this.board.delay || 0) + 150) * 1000, old = [];
    while (L.length && L[0].end < keep) old.push(L.shift().u);
    if (old.length) dvrStore(this.env).delete(old).catch(() => {});
    const msg = JSON.stringify({ t: "dvr", src, seg: e });
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server && !a.lock && (isCrew(a) || a.overlay)) this.send(ws, msg); }
    return J({ ok: true });
  }
  async dvrList(src) {
    this.dvr = this.dvr || {};
    if (this.dvr[src]) return this.dvr[src];
    const L = [];
    // après un redémarrage, on retrouve les morceaux récents dans le stockage
    if (dvrStore(this.env)) {
      const keep = Date.now() - ((this.board.delay || 0) + 150) * 1000;
      let cur; for (let i = 0; i < 5; i++) {
        const r = await dvrStore(this.env).list({ prefix: src + "/", cursor: cur, limit: 1000 }).catch(() => null); if (!r) break;
        const old = [];
        for (const o of r.objects) { const m = o.key.slice(src.length + 1).match(/^(\d+)-(\d+)-([fq])-/); if (m && +m[2] >= keep) L.push({ t: +m[1], end: +m[2], u: o.key, ly: m[3], mt: "video/webm" }); else old.push(o.key); }
        if (old.length) dvrStore(this.env).delete(old).catch(() => {});
        if (!r.truncated) break; cur = r.cursor;
      }
      L.sort((a, b) => a.t - b.t);
    }
    return (this.dvr[src] = L);
  }
  /* fond de carte : images du relevé du terrain (couleurs, hauteurs) */
  async terrainGet(path) {
    const [, , dim, kind] = path.split("/"), d = decodeURIComponent(dim || ""), meta = (this.terrain || {})[d];
    if (!meta || !["c", "h"].includes(kind) || !this.env.MEDIA) return new Response("Introuvable", { status: 404 });
    const buf = await this.env.MEDIA.get("terrain:" + d + ":" + kind, "arrayBuffer");
    if (!buf) return new Response("Introuvable", { status: 404 });
    return new Response(buf, { headers: { "Content-Type": "image/png", "Cache-Control": "private, max-age=31536000, immutable" } });
  }
  async onTerrainPart(m) {
    const id = String(m.id || "").slice(0, 20), parts = Math.max(1, Math.min(200, +m.parts || 1));
    this.tbuf = this.tbuf || {};
    for (const k of Object.keys(this.tbuf)) if (k !== id && Date.now() - this.tbuf[k].at > 600000) delete this.tbuf[k];
    const b = this.tbuf[id] = this.tbuf[id] || { at: Date.now(), got: {}, n: 0 };
    const k = +m.part; if (!(k >= 0 && k < parts) || b.got[k]) return;
    b.got[k] = { kind: m.kind === "h" ? "h" : "c", data: String(m.data || "") }; b.n++;
    if (b.n < parts) return;
    delete this.tbuf[id];
    const dim = String(m.dim || "minecraft:overworld").replace(/[^a-z0-9_:./-]/gi, "").slice(0, 60);
    for (const kind of ["c", "h"]) {
      let s64 = ""; for (let i = 0; i < parts; i++) if (b.got[i] && b.got[i].kind === kind) s64 += b.got[i].data;
      const bin = Uint8Array.from(atob(s64), ch => ch.charCodeAt(0));
      if (this.env.MEDIA) await this.env.MEDIA.put("terrain:" + dim + ":" + kind, bin);
    }
    const meta = { id, dim, x0: Math.round(+m.x0 || 0), z0: Math.round(+m.z0 || 0), step: Math.max(1, +m.step || 1), w: +m.w || 0, h: +m.h || 0, at: Date.now() };
    this.terrain = { ...(this.terrain || {}), [dim]: meta };
    await this.ctx.storage.put("terrain", this.terrain);
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server) this.send(ws, { t: "terrain", terrain: this.terrain }); }
  }
  async onModMessage(ws, a, m) {
    const reply = (ok, extra = {}) => { if (m.rid) this.send(ws, { t: ok ? "ok" : "err", rid: m.rid, ...extra }); else if (!ok && extra.msg) this.send(ws, { t: "err", ...extra }); };
    if (a.server) {
      if (m.t === "terrain") return this.onTerrainPart(m);
      if (m.t === "terrainMsg") { this.crewcast({ t: "terrainMsg", msg: String(m.msg || "").slice(0, 300) }, x => !x.mod); return; }
      if (m.t === "srvCams") {
        this.srvCams = (Array.isArray(m.names) ? m.names : []).map(n => String(n).replace(/[^A-Za-z0-9_]/g, "").slice(0, 16)).filter(Boolean).slice(0, 60);
        this.ctx.storage.put("srvcams", this.srvCams);
        this.crewcast({ t: "srvCams", names: this.srvCams }, x => !x.mod);
        return;
      }
      if (m.t === "pkeys") {
        // le mod serveur demande la clé de diffusion des joueurs connectés : aucune manipulation côté joueur
        const out = {};
        for (const raw of (Array.isArray(m.names) ? m.names : []).slice(0, 200)) {
          const n = String(raw || "").replace(/[^A-Za-z0-9_]/g, "").slice(0, 16), low = n.toLowerCase(); if (!n) continue;
          const t = this.tribByMc(n) || this.board.tributes.find(x => !x.mc && (x.login === low || String(x.name || "").toLowerCase() === low));
          const cam = !t && (this.team.find(x => x.mc && x.mc.toLowerCase() === low) || null);
          const src = t ? "t:" + t.id : cam ? "c:" + cam.id : "";
          if (src) out[n] = { key: this.keyFor(src), who: t ? t.name : cam.name, kind: t ? "trib" : "cam", pref: this.vpref[src] || "" };
          // caméras : jeton du site (comms, carte, actions) fourni aussi, plus rien à copier depuis « Mon compte »
          if (cam && CAMROLES.includes(this.camOf(cam.id)) && this.env.SESSION_SECRET) out[n].tok = await signSession({ id: cam.id, name: cam.name, av: cam.av || "", mod: 1, exp: Date.now() + 60 * 864e5 }, this.env.SESSION_SECRET);
        }
        this.send(ws, { t: "pkeys", keys: out });
        return;
      }
      if (m.t === "seen") this.ingestSeen(Array.isArray(m.list) ? m.list : [], "serveur", true);
      else if (m.t === "ev") this.ingestEv(Array.isArray(m.list) ? m.list : [], "serveur", true, a);
      return;
    }
    switch (m.t) {
      case "talkReq": {
        if (this.talk.open.includes(a.id)) { this.send(ws, { t: "talkCh", open: true, regie: !!this.talk.regie }); break; }
        this.talk.req[a.id] = Date.now(); this.pushTalk();
        this.crewcast({ t: "info", msg: a.name + " demande à parler à la régie (interphone)" }, x => !x.mod && this.talkSup(x));
        this.send(ws, { t: "info", msg: "Demande envoyée à la régie" });
        break;
      }
      case "talkSpeak": {
        if (!this.talk.open.includes(a.id)) break;
        this.talk.speak[a.id] = !!m.on; this.pushTalk(); break;
      }
      case "talkPub": case "talkPull": case "talkAns": {
        if (!this.talk.open.includes(a.id)) return reply(false, { msg: "Canal régie fermé" });
        try {
          if (m.t === "talkPub") {
            const sdp = String(m.sdp || ""), mid = (/\nm=audio[\s\S]*?\na=mid:(\S+)/.exec(sdp) || [])[1];
            if (mid == null) return reply(false, { msg: "Offre sans audio" });
            const ses = await this.sfu("/sessions/new", "POST");
            const r = await this.sfu(`/sessions/${ses.sessionId}/tracks/new`, "POST", { sessionDescription: { type: "offer", sdp }, tracks: [{ location: "local", mid, trackName: "talk" }] });
            this.talk.cams[a.id] = { sid: ses.sessionId, tracks: [{ name: "talk", kind: "audio" }], at: Date.now() };
            this.pushTalk();
            return reply(true, { sdp: r.sessionDescription.sdp });
          }
          if (m.t === "talkPull") {
            const g = this.talk.regie; if (!g) return reply(false, { msg: "La régie n'a pas encore ouvert son micro" });
            const ses = await this.sfu("/sessions/new", "POST");
            const r = await this.sfu(`/sessions/${ses.sessionId}/tracks/new`, "POST", { tracks: g.tracks.map(t => ({ location: "remote", sessionId: g.sid, trackName: t.name })) });
            return reply(true, { sid: ses.sessionId, sdp: r.sessionDescription.sdp });
          }
          await this.sfu(`/sessions/${String(m.sid || "").replace(/[^\w-]/g, "")}/renegotiate`, "PUT", { sessionDescription: { type: "answer", sdp: String(m.sdp || "") } });
          return reply(true);
        } catch (e) { return reply(false, { msg: "Interphone : " + e.message }); }
      }
      case "st": {
        const st = this.camSt[a.id] = { ...(this.camSt[a.id] || {}), name: a.name, at: Date.now() };
        if (["actif", "rec", "pause", "inactif"].includes(m.state)) st.state = m.state;
        st.follow = this.board.tributes.some(t => t.id === m.follow) ? m.follow : "";
        if (typeof m.mc === "string") st.mc = m.mc.replace(/[^A-Za-z0-9_]/g, "").slice(0, 16);
        if (["all", "sup", "none"].includes(m.notif)) st.notif = m.notif;
        const p = m.pos;
        if (p && isFinite(p.x) && isFinite(p.z) && !(this.serverOn() && st.posSrv && Date.now() - st.posSrv < 5000))
          st.pos = { x: +p.x, y: +p.y || 0, z: +p.z, d: String(p.d || "").slice(0, 60) };
        this.camsChanged(); this.saveCam();
        break;
      }
      case "seen": if (!a.lockSeen) this.ingestSeen(Array.isArray(m.list) ? m.list.slice(0, 64) : [], "cam " + a.name, false); break;
      case "ev": this.ingestEv(Array.isArray(m.list) ? m.list.slice(0, 30) : [], "cam " + a.name, false, a); break;
      case "report": {
        const t = this.board.tributes.find(x => x.id === m.tr);
        if (!t) return reply(false, { msg: "Tribut inconnu." });
        await this.onAlert(ws, a, { ch: t.login, type: m.type, detail: m.detail, urgent: m.urgent }, (ok, x) => { if (!ok) reply(false, x); else this.send(ws, { t: "info", msg: "Signalement transmis à la régie" + (this.board.delay ? " (écran dans " + Math.round(this.board.delay / 60 * 10) / 10 + " min)" : "") }); }, { rec: !!m.rec, src: "mod" });
        if (m.rec) { const st = this.camSt[a.id] = { ...(this.camSt[a.id] || {}), follow: t.id, state: "rec", at: Date.now() }; this.camsChanged(); }
        break;
      }
      case "alertUpd": {
        const al = this.alerts[m.id]; if (!al) return;
        const p = {};
        if ("handled" in (m.p || {})) p.handled = !!m.p.handled;
        if ("claimedBy" in (m.p || {})) p.claimedBy = String(m.p.claimedBy || "").slice(0, 32);
        Object.assign(al, p);
        await this.ctx.storage.put("alerts", this.alerts);
        this.broadcast({ t: "alertUpd", id: m.id, p }, x => isCrew(x) && !x.lock);
        this.modcast({ t: "alertUpd", id: m.id, p });
        break;
      }
      case "msg": case "buzz": case "req": case "reqUpd": case "special": return this.onCrewOp(a, m, reply);
      case "setRole": case "roles": return this.onRolesOp(a, m, reply);
    }
  }

  /* ---------- comms & demandes (site et mod) ---------- */
  msgVisible(x, a) { return x.ch !== "dm" || x.from === a.id || x.to === a.id; }
  pushMsg(x) {
    this.camData.comms.push(x);
    if (this.camData.comms.length > 400) this.camData.comms.splice(0, this.camData.comms.length - 400);
    this.saveCam();
    this.crewcast({ t: "msg", m: x }, a => this.msgVisible(x, a));
  }
  onCrewOp(a, m, reply) {
    const now = Date.now(), id = now.toString(36) + Math.random().toString(36).slice(2, 6);
    const sup = isSup(a);
    switch (m.t) {
      case "msg": case "buzz": {
        const text = String(m.text || "").trim().slice(0, 400);
        if (!text) return reply(false, { msg: "Message vide." });
        if (m.t === "buzz" && !sup) return reply(false, { msg: "Seuls la régie et le responsable caméra peuvent « buzzer »." });
        const ch = m.t === "buzz" ? "dm" : ["all", "cams", "dm"].includes(m.ch) ? m.ch : "all";
        const x = { id, ts: now, ch, from: a.id, name: a.name, sup, text, k: m.t === "buzz" ? "buzz" : "msg" };
        if (ch === "dm") {
          const to = String(m.to || "");
          const tm = this.team.find(t => t.id === to);
          if (!tm) return reply(false, { msg: "Destinataire inconnu." });
          x.to = to; x.toName = tm.name;
        }
        this.pushMsg(x); return reply(true);
      }
      case "req": {
        const t = this.board.tributes.find(x => x.id === m.tr);
        if (!t) return reply(false, { msg: "Tribut inconnu." });
        const r = { id, ts: now, by: a.id, byName: a.name, bySup: sup, tr: t.id, text: String(m.text || "").trim().slice(0, 200), state: "open", to: "", toName: "", taker: "", takerName: "" };
        if (m.to) { const tm = this.team.find(x => x.id === m.to); if (tm) { r.to = tm.id; r.toName = tm.name; } }
        this.camData.reqs[id] = r;
        const ids = Object.keys(this.camData.reqs).sort((x, y) => this.camData.reqs[x].ts - this.camData.reqs[y].ts);
        while (ids.length > 150) delete this.camData.reqs[ids.shift()];
        this.saveCam();
        this.crewcast({ t: "req", r }); return reply(true, { id });
      }
      case "reqUpd": {
        const r = this.camData.reqs[m.id]; if (!r) return reply(false, { msg: "Demande introuvable." });
        const op = m.op;
        if (op === "del") { if (!sup && r.by !== a.id) return reply(false, { msg: "Non autorisé." }); delete this.camData.reqs[m.id]; this.saveCam(); this.crewcast({ t: "reqDel", id: m.id }); return reply(true); }
        if (op === "take") { if (r.state === "done") return reply(false, { msg: "Demande déjà terminée." }); r.state = "taken"; r.taker = a.id; r.takerName = a.name; }
        else if (op === "done") { if (!sup && r.taker !== a.id && r.by !== a.id && r.to !== a.id) return reply(false, { msg: "Non autorisé." }); r.state = "done"; if (!r.taker) { r.taker = a.id; r.takerName = a.name; } }
        else if (op === "drop") { if (!sup && r.taker !== a.id) return reply(false, { msg: "Non autorisé." }); r.state = "open"; r.taker = ""; r.takerName = ""; }
        else if (op === "reopen") { r.state = "open"; }
        else return reply(false, { msg: "Opération inconnue." });
        r.upd = now; this.saveCam();
        this.crewcast({ t: "req", r }); return reply(true);
      }
      case "special": {
        if (!sup && !hasPerm(a, "special")) return reply(false, { msg: "Il faut la permission « Annonces spéciales »." });
        const t = this.board.tributes.find(x => x.id === m.tr);
        this.addFeed({ k: "special", tr: t ? t.id : "", n: t ? t.name : "", text: String(m.text || "").trim().slice(0, 200), src: a.name });
        return reply(true);
      }
    }
  }

  /* ---------- positions ---------- */
  tribByMc(n) { n = String(n || "").toLowerCase(); return n ? this.board.tributes.find(t => t.mc && t.mc.toLowerCase() === n) : null; }
  latestPos() {
    const out = {};
    for (const [id, arr] of Object.entries(this.pos)) if (arr.length) out[id] = arr[arr.length - 1];
    return out;
  }
  posFor(a) {
    if (!this.mapAllowed(a)) return {};
    const full = isCrew(a) && !a.pub;
    const D = (this.board.delay || 0) * 1000;
    if (full || !D) {
      const l = this.latestPos();
      if (full) return l;
      const o = {}; for (const [k, v] of Object.entries(l)) o[k] = { x: v.x, y: v.y, z: v.z, d: v.d, ts: v.ts }; return o;
    }
    const cut = Date.now() - D, out = {};
    for (const [id, arr] of Object.entries(this.pos)) {
      let p = null;
      for (let i = arr.length - 1; i >= 0; i--) if (arr[i].ts <= cut) { p = arr[i]; break; }
      if (p) out[id] = { x: p.x, y: p.y, z: p.z, d: p.d, ts: p.ts + D };
    }
    return out;
  }
  setPos(t, p, src) {
    const now = Date.now();
    const arr = this.pos[t.id] || (this.pos[t.id] = []);
    const last = arr[arr.length - 1];
    const q = { x: Math.round(+p.x * 10) / 10, y: Math.round(+p.y * 10) / 10, z: Math.round(+p.z * 10) / 10, d: String(p.d || "minecraft:overworld").slice(0, 60), ts: now, src };
    if (isFinite(+p.hp)) q.hp = Math.round(+p.hp * 10) / 10;
    if (last && last.d === q.d && t.status !== "dead") { const dd = Math.hypot(q.x - last.x, q.z - last.z); if (dd > 0.3 && dd < 60) this.stat(t.id).dist += dd; }
    if (last && last.d === q.d && Math.abs(last.x - q.x) < 0.6 && Math.abs(last.z - q.z) < 0.6 && Math.abs(last.y - q.y) < 0.6 && last.hp === q.hp && now - last.ts < 5000) { last.ts = now; last.src = src; }
    else arr.push(q);
    const keep = now - Math.max(20 * 60000, (this.board.delay || 0) * 1000 + 5 * 60000);
    while (arr.length && (arr[0].ts < keep || arr.length > 1500)) arr.shift();
    // points de vie bas
    if (last && isFinite(last.hp) && isFinite(q.hp) && last.hp > 6 && q.hp <= 6 && q.hp > 0 && t.status !== "dead") {
      const ck = "low|" + t.id;
      if (now - (this.cool.get(ck) || 0) > 60000) { this.cool.set(ck, now); this.addFeed({ k: "lowhp", tr: t.id, n: t.name, hp: q.hp, x: q.x, y: q.y, z: q.z, d: q.d, src }); }
    }
    this.posDirty = true;
  }
  ingestSeen(list, src, isServer) {
    const now = Date.now();
    for (const p of list) {
      if (!p || !isFinite(+p.x) || !isFinite(+p.z)) continue;
      const t = this.tribByMc(p.n);
      if (t) {
        if (!isServer) { const arr = this.pos[t.id], last = arr && arr[arr.length - 1]; if (last && last.src === "serveur" && now - last.ts < 6000) continue; }
        if (isServer && p.sp) continue;
        this.setPos(t, p, src);
      } else if (isServer) {
        const n = String(p.n || "").toLowerCase();
        for (const [uid, st] of Object.entries(this.camSt)) if (st.mc && st.mc.toLowerCase() === n) { st.pos = { x: +p.x, y: +p.y || 0, z: +p.z, d: String(p.d || "").slice(0, 60) }; st.posSrv = now; }
      }
    }
    this.checkMeet();
    this.flushPosSoon();
  }
  checkMeet() {
    const now = Date.now(), live = [];
    for (const t of this.board.tributes) {
      if (t.status === "dead") continue;
      const arr = this.pos[t.id], p = arr && arr[arr.length - 1];
      if (p && now - p.ts < 15000) live.push([t, p]);
    }
    for (let i = 0; i < live.length; i++) for (let j = i + 1; j < live.length; j++) {
      const [a, p] = live[i], [b, q] = live[j];
      const key = a.id < b.id ? a.id + "|" + b.id : b.id + "|" + a.id;
      const d = p.d === q.d ? Math.hypot(p.x - q.x, p.z - q.z) : Infinity;
      if (d <= 16 && !this.near.has(key)) {
        this.near.set(key, now);
        if (now - (this.cool.get("meet|" + key) || 0) > 5 * 60000) {
          this.cool.set("meet|" + key, now);
          this.addFeed({ k: "meet", tr: a.id, tr2: b.id, n: a.name, n2: b.name, dist: Math.round(d), x: Math.round((p.x + q.x) / 2), y: Math.round(p.y), z: Math.round((p.z + q.z) / 2), d: p.d, src: "carte" });
        }
      } else if (d > 40 && this.near.has(key)) this.near.delete(key);
    }
  }
  /* statistiques de fin (équitables : visibles seulement une fois le tribut tombé ou les Jeux finis) */
  stat(id) { return this.tstats[id] || (this.tstats[id] = { dist: 0, air: 0, vmin: 0, peak: 0 }); }
  statTick() {
    const now = Date.now(), dt = Math.min(120000, now - (this.statAt || now)); this.statAt = now;
    if (!dt) return;
    const live = this.prog && this.prog.live && !this.prog.screen ? this.liveItems() : [];
    for (const it of live) if (it.k === "trib" && !it.hide) this.stat(it.ref).air += dt / 1000;
    const cnt = this.presence(false).counts, tw = (this.live && this.live.data) || {};
    for (const t of this.board.tributes) {
      if (t.status === "dead") continue;
      const v = (cnt[t.login] || 0) + ((tw[t.login] || {}).v || 0); if (!v) continue;
      const st = this.stat(t.id); st.vmin += v * dt / 60000; if (v > st.peak) st.peak = v;
    }
    if (now - (this.statSave || 0) > 60000) { this.statSave = now; this.ctx.storage.put("tstats", this.tstats); }
  }
  statsFor(a) {
    const b = this.publicBoard(), over = !!this.victorEvent(), crew = isCrew(a) && !a.lock, out = {}, st0 = this.board.startTs || 0;
    for (const t of (crew ? this.board : b).tributes) {
      if (!crew && !over && t.status !== "dead") continue;
      const s = this.tstats[t.id] || { dist: 0, air: 0, vmin: 0, peak: 0 };
      const end = t.status === "dead" ? t.diedAt || 0 : (over ? (this.victorEvent() || {}).ts || Date.now() : Date.now());
      out[t.id] = { dist: Math.round(s.dist), air: Math.round(s.air), vmin: Math.round(s.vmin), peak: s.peak, alive: st0 && end ? Math.max(0, Math.round((end - st0) / 1000)) : 0 };
    }
    return { stats: out, over };
  }
  flushPosSoon() {
    if (this.posT || !this.posDirty) return;
    this.posT = setTimeout(() => {
      this.posT = null; this.posDirty = false;
      const live = JSON.stringify({ t: "pos", p: this.latestPos() });
      let pub = null;
      for (const ws of this.sockets()) {
        const a = this.att(ws); if (!a || a.server) continue;
        if (a.mod) { this.send(ws, live); continue; }
        if (!a.map) continue;
        if (isCrew(a) && !a.pub && !a.lock) this.send(ws, live);
        else if (this.mapAllowed(a)) this.send(ws, pub = pub || JSON.stringify({ t: "pos", p: this.posFor(a), pub: true }));
      }
      if (Object.values(this.camSt).some(s => s.posSrv && Date.now() - s.posSrv < 3000)) this.camsChanged();
    }, 2000);
  }

  /* ---------- actes annonciateurs ---------- */
  ingestEv(list, src, isServer, a) {
    const srvOn = this.serverOn();
    for (const e of list) {
      if (!e || typeof e !== "object") continue;
      const k = String(e.k || "");
      if (k === "special") {
        if (!isServer && !isSup(a)) continue;
        const t = this.tribByMc(e.n);
        this.addFeed({ k, tr: t ? t.id : "", n: t ? t.name : "", text: String(e.text || "").slice(0, 200), src: isServer ? "serveur" + (e.by ? " · " + String(e.by).slice(0, 32) : "") : src });
        continue;
      }
      if (!["hit", "death", "effect", "totem"].includes(k)) continue;
      const t = this.tribByMc(e.n);
      if (!t) continue;
      if (!isServer && srvOn && k !== "totem") continue;
      const byT = e.byP ? this.tribByMc(e.by) : null;
      const ev = { k, tr: t.id, n: t.name, nmc: String(e.n || "").slice(0, 16), cause: String(e.cause || "").slice(0, 40), by: byT ? byT.name : String(e.by || "").slice(0, 40), byTr: byT ? byT.id : "", byT: String(e.byT || "").slice(0, 60), via: String(e.via || "").slice(0, 60), src };
      if (isFinite(+e.amt) && +e.amt > 0) ev.amt = Math.round(+e.amt * 10) / 10;
      if (isFinite(+e.hp)) ev.hp = Math.round(+e.hp * 10) / 10;
      if (isFinite(+e.x) && isFinite(+e.z)) { ev.x = Math.round(+e.x); ev.y = Math.round(+e.y || 0); ev.z = Math.round(+e.z); ev.d = String(e.d || "").slice(0, 60); }
      if (k === "effect") { ev.eff = String(e.eff || "").slice(0, 60); ev.effN = String(e.effN || "").slice(0, 40); ev.lvl = Math.max(1, Math.min(255, parseInt(e.lvl, 10) || 1)); ev.dur = parseInt(e.dur, 10) || 0; }
      const win = { hit: 2500, death: 20000, effect: 30000, totem: 5000 }[k];
      const key = [k, ev.tr, ev.cause, ev.by, ev.eff || ""].join("|"), now = Date.now();
      const dup = this.camData.feed.slice(-40).reverse().find(x => x.key === key && now - x.ts < win);
      if (dup) { if (!dup.amt && ev.amt) { dup.amt = ev.amt; dup.hp = ev.hp; dup.text = this.evText(dup); this.crewcast({ t: "feed", list: [dup] }); } continue; }
      ev.key = key;
      this.addFeed(ev);
      if (k === "death" && (isServer || !srvOn)) this.autoKill(t, ev);
      if (k === "death" && isServer) this.sendRecap(t, ev);
      if (isFinite(ev.hp) && ev.x != null) this.setPos(t, { x: ev.x, y: ev.y, z: ev.z, d: ev.d, hp: ev.hp }, src);
    }
    this.flushPosSoon();
  }
  /* récap affiché au tribut sur son écran de mort */
  sendRecap(t, ev) {
    const b = this.board, now = Date.now(), st = this.tstats[t.id] || { dist: 0, air: 0, peak: 0 };
    const place = t.place || b.tributes.filter(x => x.id !== t.id && x.status !== "dead").length + 1;
    const dur = s => { s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? h + " h " + String(m).padStart(2, "0") : m + " min"; };
    const lines = [place ? place + (place === 1 ? "re" : "e") + " place sur " + b.tributes.length : "", ev.by ? "Tombé·e face à " + ev.by : ev.cause ? "Cause : " + ev.cause : "", b.startTs ? "Temps de survie : " + dur((now - b.startTs) / 1000) : "",
      st.dist ? "Distance parcourue : " + (st.dist >= 1000 ? (st.dist / 1000).toFixed(1).replace(".", ",") + " km" : Math.round(st.dist) + " m") : "", st.air ? "Temps à l'antenne : " + dur(st.air) : "", st.peak ? "Jusqu'à " + st.peak + " spectateurs" : ""].filter(Boolean);
    this.sendServers({ t: "recap", n: ev.nmc || t.mc, name: t.name, district: t.district || 0, num: t.num || 0, place, lines });
  }
  autoKill(t, ev) {
    const b = this.board; if (!b.autoCanon || t.status === "dead") return;
    const now = Date.now(), D = (b.delay || 0) * 1000;
    const place = b.tributes.filter(x => x.status !== "dead").length;
    t.prevStatus = t.status; t.status = "dead"; t.statusAt = now; t.diedAt = now;
    t.killer = ev.byTr || (ev.by ? "arena" : ""); t.cause = String(CAUSE_FR[ev.cause] || ev.cause || "").slice(0, 60); t.place = place;
    const kn = ev.byTr ? ev.by : ev.by ? "l'arène" : "";
    b.events.push({ id: now.toString(36) + "a", k: "death", tid: t.id, ts: now, auto: true, text: `${t.name}${t.district ? " (D" + t.district + ")" : ""} est tombé${kn ? " face à " + kn : ""}${t.cause ? " — " + t.cause : ""} · ${place}e place` });
    b.flashQ = [...(b.flashQ || []), { id: now.toString(36) + "c", k: "death", tid: t.id, dur: 10, at: now + D }].slice(-20);
    if (!D) this.flushFlashQ(now);
    this.ctx.storage.put("board", b);
    this.broadcastBoard(); this.botTick(); this.scheduleAlarm();
  }
  flushFlashQ(now = Date.now()) {
    const b = this.board, due = (b.flashQ || []).filter(q => q.at <= now + 300);
    if (!due.length) return false;
    b.flashQ = b.flashQ.filter(q => q.at > now + 300);
    const q = due[due.length - 1];
    b.flash = { id: q.id, k: q.k, tid: q.tid, dur: q.dur, ts: q.at };
    return true;
  }
  addFeed(ev) {
    const now = Date.now();
    ev.id = ev.id || now.toString(36) + Math.random().toString(36).slice(2, 6);
    ev.ts = ev.ts || now;
    ev.text = this.evText(ev);
    this.camData.feed.push(ev);
    if (this.camData.feed.length > 500) this.camData.feed.splice(0, this.camData.feed.length - 500);
    this.saveCam();
    this.crewcast({ t: "feed", list: [ev] });
  }
  evText(e) {
    const hearts = v => (Math.round(v / 2 * 10) / 10).toString().replace(".", ",") + " ❤";
    const cause = CAUSE_FR[e.cause] || (e.cause ? e.cause : "");
    const via = e.via && VIA_FR[e.via] ? " (" + VIA_FR[e.via] + ")" : "";
    const dmg = (e.amt ? " — " + hearts(e.amt) : "") + (isFinite(e.hp) && e.hp > 0 ? " (reste " + hearts(e.hp) + ")" : "");
    const mob = e.byT && MOB_FR[e.byT] ? MOB_FR[e.byT] : e.by;
    switch (e.k) {
      case "hit":
        if (e.byTr) return "⚔ " + e.by + " attaque " + e.n + (e.cause && e.cause !== "player" ? " (" + cause + ")" : via) + dmg;
        if (e.by) return "✖ " + e.n + " blessé par " + mob + (e.cause && !["mob", "player"].includes(e.cause) ? " (" + cause + ")" : via) + dmg;
        return "✖ " + e.n + " blessé : " + (cause || "cause inconnue") + dmg;
      case "death":
        return "☠ " + e.n + " est mort" + (e.byTr ? ", tué par " + e.by : e.by ? " (" + mob + ")" : "") + (cause && !["player", "mob"].includes(e.cause) ? " — " + cause : "");
      case "effect": {
        const id = String(e.eff || "").replace(/^minecraft:/, "");
        const roman = ["", "", " II", " III", " IV", " V"][e.lvl] || (e.lvl > 1 ? " " + e.lvl : "");
        return "✦ " + e.n + " : " + (EFF_FR[id] || e.effN || id) + roman + (e.dur > 0 ? " (" + (e.dur >= 60 ? Math.floor(e.dur / 60) + " min " + (e.dur % 60 ? e.dur % 60 + " s" : "") : e.dur + " s") + ")" : e.dur < 0 ? " (permanent)" : "");
      }
      case "totem": return "✪ " + e.n + " a utilisé un totem d'immortalité !";
      case "lowhp": return "⚠ " + e.n + " n'a plus que " + hearts(e.hp);
      case "meet": return "⌖ Rencontre : " + e.n + " et " + e.n2 + " (" + e.dist + " blocs)";
      case "special": return "★ " + (e.n ? e.n + " : " : "") + e.text;
    }
    return e.text || "";
  }

  /* ---------- alarme : Twitch + révélations du différé ---------- */
  twitchOn() { return !!(this.env.TWITCH_CLIENT_ID && this.env.TWITCH_CLIENT_SECRET); }
  async scheduleAlarm() {
    await this.load();
    const now = Date.now();
    let t = Infinity;
    for (const r of this.revealTimes()) if (r > now && r < t) t = r;
    if (this.twitchOn() && this.sockets().length) {
      const last = (this.live && this.live.ts) || 0;
      t = Math.min(t, Math.max(now + 1000, last + 60000));
    }
    if (this.prog && this.prog.live && this.sockets().length) t = Math.min(t, now + 30000);
    if (this.board.lock && this.board.lock.mode === "schedule" && this.sockets().length && this.board.tributes.some(x => x.discordId))
      t = Math.min(t, (Math.floor(now / 60000) + 1) * 60000 + 1000);
    if (this.prog && this.prog.next && this.prog.next.at) t = Math.min(t, Math.max(now + 200, this.prog.next.at));
    if (this.prog && this.prog.replay && this.prog.replay.end > now) t = Math.min(t, this.prog.replay.end + 100);
    if (this.prog && this.prog.clip && this.prog.clip.end > now) t = Math.min(t, this.prog.clip.end + 100);
    if (this.bets && this.bets.settled) { const r = this.bets.settled.at + (this.board.delay || 0) * 1000; if (r > now) t = Math.min(t, r + 300); }
    if (this.poll && this.poll.end > now) t = Math.min(t, this.poll.end + 200);
    if (this.tbot && this.tbot.on && this.tbot.channel) t = Math.min(t, now + 45000);
    if (this.scJobs && this.scJobs.length) t = Math.min(t, now + 400);
    if (t === Infinity) return;
    const cur = await this.ctx.storage.getAlarm();
    if (!cur || cur > t || cur < now) await this.ctx.storage.setAlarm(t);
  }
  async alarm() {
    await this.load();
    const now = Date.now();
    if (this.twitchOn() && this.sockets().length && now - ((this.live && this.live.ts) || 0) >= 55000) await this.pollTwitch();
    this.refreshLocks();
    const revealed = this.revealTimes().some(r => r > this.revealCursor - 1500 && r <= now + 500);
    this.revealCursor = now;
    if (this.flushFlashQ(now)) { await this.ctx.storage.put("board", this.board); this.broadcastBoard(); }
    else if (revealed) this.broadcastBoard({ viewersOnly: true });
    if (this.prog.next && now >= this.prog.next.at - 200) await this.runNext();
    if (this.prog.replay && now >= this.prog.replay.end) { this.prog.replay = null; this.pushProg(false); }
    if (this.prog.clip && now >= this.prog.clip.end) { this.prog.clip = null; this.pushProg(false); }
    if (this.bets.settled) { const r = this.bets.settled.at + (this.board.delay || 0) * 1000; if (r <= now + 500 && r > now - 60000) this.pushBets(); }
    if (this.poll && this.poll.end && this.poll.end <= now + 300 && this.poll.end > now - 5000) this.pushPoll();
    if (this.tbot && this.tbot.on && this.tbot.channel) { if (!this.botWs) this.botStart(); else if (now - (this.botLast || 0) > 360000) { this.botClose(); this.botStart(); } }
    this.botTick(); this.statTick();
    if (this.scJobs && this.scJobs.length) await this.scWork();
    await this.scheduleAlarm();
  }
  /* ---------- copie d'un titre SoundCloud dans les médias (par lots : peu de requêtes à la fois) ---------- */
  async scStart(meta) {
    const src = await scSource(this.env, meta.sc);
    meta.mime = src.mime; if (src.dur) meta.dur = src.dur;
    this.scJobs = (this.scJobs || []).filter(j => j.id !== meta.id);
    this.scJobs.push({ id: meta.id, urls: src.urls, i: 0, parts: 0, size: 0 });
    await this.ctx.storage.put("scjobs", this.scJobs);
    await this.scWork(30);
  }
  async scWork(budget = 40) {
    const jobs = this.scJobs || []; if (!jobs.length) return;
    const job = jobs[0], meta = this.media.find(x => x.id === job.id);
    if (!meta) { jobs.shift(); await this.ctx.storage.put("scjobs", jobs); return; }
    try {
      if (!job.urls) { const src = await scSource(this.env, meta.sc); job.urls = src.urls; meta.mime = src.mime; if (src.dur) meta.dur = src.dur; budget -= 3; }
      const bufs = [];
      while (job.i < job.urls.length && budget-- > 0) {
        const r = await fetch(job.urls[job.i]);
        if (!r.ok) throw new Error("segment " + job.i + " : " + r.status);
        bufs.push(new Uint8Array(await r.arrayBuffer())); job.i++;
      }
      const tot = bufs.reduce((a, b) => a + b.byteLength, 0), cat = new Uint8Array(tot); let o = 0;
      for (const b of bufs) { cat.set(b, o); o += b.byteLength; }
      if (tot) { await this.env.MEDIA.put(`scb:${job.id}:${job.parts}`, cat, { expirationTtl: 86400 }); job.parts++; job.size += tot; }
      meta.dlp = Math.round(job.i / job.urls.length * 100);
      if (job.i >= job.urls.length) {
        // assemblage en morceaux de 8 Mo, comme un fichier importé
        const all = new Uint8Array(job.size); let p = 0;
        for (let k = 0; k < job.parts; k++) { const b = await this.env.MEDIA.get(`scb:${job.id}:${k}`, "arrayBuffer"); if (!b) throw new Error("lot perdu"); all.set(new Uint8Array(b), p); p += b.byteLength; }
        const n = Math.ceil(job.size / MEDIA_CHUNK);
        for (let i = 0; i < n; i++) await this.env.MEDIA.put(`c:${job.id}:${i}`, all.slice(i * MEDIA_CHUNK, (i + 1) * MEDIA_CHUNK));
        for (let k = 0; k < job.parts; k++) this.env.MEDIA.delete(`scb:${job.id}:${k}`).catch(() => {});
        Object.assign(meta, { size: job.size, n, cs: MEDIA_CHUNK }); delete meta.dl; delete meta.dlp;
        jobs.shift();
      }
    } catch (e) {
      meta.dl = 2; meta.dlErr = String(e.message || e).slice(0, 120); jobs.shift();
    }
    await this.env.MEDIA.put("meta:" + meta.id, JSON.stringify(meta));
    await this.ctx.storage.put("scjobs", jobs);
    await this.ctx.storage.put("media", this.media);
    this.crewcast({ t: "media", list: this.media }, x => !x.mod);
    if (jobs.length) await this.scheduleAlarm();
  }
  async twitchToken(force) {
    let tk = force ? null : (this.ttk || await this.ctx.storage.get("ttk"));
    if (tk && tk.exp > Date.now() + 60000) return (this.ttk = tk).v;
    const r = await fetch("https://id.twitch.tv/oauth2/token", {
      method: "POST",
      body: new URLSearchParams({ client_id: this.env.TWITCH_CLIENT_ID, client_secret: this.env.TWITCH_CLIENT_SECRET, grant_type: "client_credentials" })
    });
    const j = await r.json().catch(() => ({}));
    if (!j.access_token) throw new Error("Jeton Twitch refusé (vérifie TWITCH_CLIENT_ID / TWITCH_CLIENT_SECRET)");
    tk = { v: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000 };
    this.ttk = tk; await this.ctx.storage.put("ttk", tk);
    return tk.v;
  }
  async pollTwitch(retry = true) {
    if (!this.twitchOn()) return;
    try {
      const logins = this.board.tributes.map(t => t.login), data = {};
      if (logins.length) {
        const tok = await this.twitchToken();
        for (let i = 0; i < logins.length; i += 100) {
          const q = logins.slice(i, i + 100).map(l => "user_login=" + encodeURIComponent(l)).join("&");
          const r = await fetch("https://api.twitch.tv/helix/streams?first=100&" + q, { headers: { "Client-Id": this.env.TWITCH_CLIENT_ID, Authorization: "Bearer " + tok } });
          if (r.status === 401 && retry) { this.ttk = null; await this.ctx.storage.delete("ttk"); return this.pollTwitch(false); }
          if (!r.ok) throw new Error("Twitch HTTP " + r.status);
          const j = await r.json();
          for (const s of j.data || []) data[String(s.user_login).toLowerCase()] = { v: s.viewer_count, t: String(s.title || "").slice(0, 140), g: s.game_name, s: Date.parse(s.started_at) };
        }
      }
      this.live = { ts: Date.now(), data };
      await this.ctx.storage.put("live", this.live);
      this.broadcast({ t: "live", live: this.live }, x => !x.lock);
    } catch (e) {
      this.broadcast({ t: "err", msg: "Statut Twitch : " + e.message }, a => a.perm === "admin");
    }
  }
}
