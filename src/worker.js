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
      if (PUBLIC_PATHS.has(p)) return env.ASSETS.fetch(req);

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
      if (p.startsWith("/api/media")) {
        if (user.guest || user.overlay) return new Response("Réservé à l'équipe", { status: 403 });
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
async function serveMedia(req, env, id) {
  if (!env.MEDIA) return new Response("Stockage des médias non configuré", { status: 503 });
  id = String(id).replace(/[^a-z0-9]/gi, "").slice(0, 40);
  const meta = await env.MEDIA.get("meta:" + id, "json");
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
        const buf = await env.MEDIA.get(`c:${id}:${n}`, "arrayBuffer");
        if (!buf) throw new Error("morceau manquant");
        const a = n === first ? start - n * cs : 0, b = n === last ? end - n * cs + 1 : buf.byteLength;
        await w.write(new Uint8Array(buf, a, b - a));
      }
      await w.close();
    } catch (e) { try { await w.abort(e); } catch (x) {} }
  })();
  return new Response(readable, { status, headers: h });
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
  return redirect("/#overlay", [setCookie(COOKIE, val, days * 86400)]);
}

/* =====================================================================
   Durable Object : la régie centrale
   ===================================================================== */
const TYPES = ["Combat", "Alliance", "Trahison", "Mort imminente", "Cadeau sponsor", "Moment drôle", "Révélation", "Autre"];
const SCALARS = ["ticker", "don", "fund", "day", "startTs", "cooldown", "lastHommage", "flash", "delay", "hideChat", "lock", "sponsor", "publicAccess", "map"];
const STATUSES = ["alive", "injured", "missing", "dead"];
const PERMS = ["admin", "mod", "member"];
const LOGIN_RE = /^[a-z0-9_]{3,25}$/;
const cleanLogin = s => { s = String(s || "").toLowerCase(); return LOGIN_RE.test(s) ? s : null; };
const isStaff = a => a && (a.perm === "admin" || a.perm === "mod");
const CAMROLES = ["cam", "chef"];
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
  day: 0, startTs: null, cooldown: 20, lastHommage: 0, events: [], flash: null, delay: 0, hideChat: true, publicAccess: false,
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
      const m = await this.ctx.storage.get(["board", "alerts", "team", "banned", "live", "cam", "camst", "srvkey", "roles", "prog", "media", "srvcams"]);
      this.board = { ...defaultBoard(), ...(m.get("board") || {}) };
      this.alerts = m.get("alerts") || {};
      this.team = m.get("team") || [];
      this.banned = m.get("banned") || {};
      this.live = m.get("live") || null;
      const c = m.get("cam") || {};
      this.camData = { feed: c.feed || [], comms: c.comms || [], reqs: c.reqs || {} };
      this.camSt = m.get("camst") || {};
      this.srvKey = m.get("srvkey") || "";
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
    if (path === "/mod") return this.modConnect(req);
    if (path.startsWith("/api/media")) return this.mediaApi(req, path);
    let user = null;
    try { user = JSON.parse(req.headers.get("X-CTV-User") || "null"); } catch (e) {}
    if (!user || !user.id) return new Response("Connexion requise", { status: 401 });

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);

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
      me: { id: a.id, name: a.name, av: a.av, perm: a.perm, pub: a.pub, lock: !!a.lock, guest: !!a.guest, tribute: this.tributeOf(a.id), cam: a.cam || "", sup: isSup(a), crew: isCrew(a) && !a.lock, map: this.mapAllowed(a), perms: a.perms || [], rid: (this.team.find(x => x.id === a.id) || {}).rid || "" },
      roles: this.roles, prog: a.lock ? undefined : this.progPublic(), progEd: hasPerm(a, "program") && !a.lock ? this.prog : undefined,
      media: isCrew(a) ? this.media : undefined, srvCams: isCrew(a) ? this.srvCams : undefined,
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
    b = { ...b, fundLog: undefined };
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
    return {
      ...b, tributes,
      events: events.filter(e => e.ts <= cut).map(e => ({ ...e, ts: e.ts + D })),
      flash: b.flash && b.flash.ts <= cut ? { ...b.flash, ts: b.flash.ts + D } : null
    };
  }
  revealTimes() {
    const b = this.board, D = (b.delay || 0) * 1000, out = [];
    if (!D) return out;
    for (const t of b.tributes) if (t.statusAt) out.push(t.statusAt + D);
    for (const e of b.events) if (e.k !== "mark") out.push(e.ts + D);
    if (b.flash && b.flash.ts) out.push(b.flash.ts + D);
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
        if (!this.board.publicAccess) for (const s of this.sockets()) { const x = this.att(s); if (x && x.guest) { this.send(s, { t: "auth" }); try { s.close(4001, "Connexion requise"); } catch (e) {} } }
        this.refreshLocks();
        this.broadcastBoard();
        if (this.prog.live) this.pushProg(false);
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
      case "progSave": case "progTake": case "progSfx": return this.onProgOp(a, m, reply);
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
    reply(true, { id });
  }

  applyPatch(p) {
    const b = this.board, now = Date.now();
    if (p.set && typeof p.set === "object") {
      for (const [k, v] of Object.entries(p.set)) if (SCALARS.includes(k)) b[k] = v;
      if (p.set.flash && typeof p.set.flash === "object") b.flash = { ...p.set.flash, ts: now };
      if (p.set.fund && typeof p.set.fund === "object") { b.fund = { goal: Math.max(0, +p.set.fund.goal || 0), cur: String(p.set.fund.cur || "€").slice(0, 4), show: p.set.fund.show !== false, raised: 0 }; this.recalcFund(); }
      b.publicAccess = !!b.publicAccess;
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
      else { t.prevStatus = prev ? prev.status : t.status; t.statusAt = prev ? now : 0; }
      if (t.status === "dead" && (!prev || prev.status !== "dead")) t.diedAt = now;
      if (t.status === "injured" && (!prev || prev.status !== "injured")) t.injAt = now;
      if (i >= 0) b.tributes[i] = t; else if (b.tributes.length < 999) b.tributes.push(t);
    }
    if (Array.isArray(p.delTributes)) { const d = new Set(p.delTributes); b.tributes = b.tributes.filter(t => !d.has(t.id)); }
    if (Array.isArray(p.events)) {
      const have = new Set(b.events.map(e => e.id));
      for (const e of p.events) if (e && e.id && !have.has(e.id)) b.events.push({ ...e, ts: now, text: String(e.text || "").slice(0, 400) });
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
        twitch: cleanLogin(m.twitch) || "", mc: String(m.mc || "").replace(/[^A-Za-z0-9_]/g, "").slice(0, 16), bio: String(m.bio || "").slice(0, 200)
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
    if (it.k === "trib") { const t = this.board.tributes.find(x => x.id === it.ref); return t ? { login: t.login, name: t.name, sub: t.district ? "District " + t.district : "", st: t.status } : null; }
    if (it.k === "cam") { const m = this.team.find(x => x.id === it.ref); return m && m.twitch ? { login: m.twitch, name: m.name, sub: "Caméra" } : null; }
    if (it.k === "media") { const md = this.media.find(x => x.id === it.ref); return md ? { media: md.id, name: md.name, sub: md.kind === "video" ? "Vidéo" : md.kind === "image" ? "Image" : "Son", kind: md.kind, mime: md.mime, dur: md.dur || 0 } : null; }
    return null;
  }
  progPublic() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.live);
    const starts = this.prog.starts || {};
    const items = sc ? sc.items.map(it => { const s = this.srcOf(it); return s ? { id: it.id, k: it.k, ref: it.ref, x: it.x, y: it.y, w: it.w, h: it.h, z: it.z, label: it.label !== false, audio: !!it.audio, loop: !!it.loop, vol: it.vol == null ? 100 : it.vol, at: starts[it.ref] || 0, ...s } : null; }).filter(Boolean) : [];
    const fx = this.prog.sfx, md = fx && this.media.find(x => x.id === fx.id);
    return { seq: this.prog.seq, scene: sc ? sc.name : "", items, trans: this.prog.trans, title: this.prog.title || "", on: !!sc,
      sfx: md ? { id: md.id, name: md.name, kind: md.kind, at: fx.at, vol: fx.vol, loop: !!fx.loop, dur: md.dur || 0 } : null };
  }
  onAirCams() {
    const sc = this.prog.scenes.find(x => x.id === this.prog.live);
    return sc ? [...new Set(sc.items.filter(it => it.k === "cam").map(it => it.ref))] : [];
  }
  cleanProg(p) {
    const num = (v, d, lo, hi) => { v = +v; return isFinite(v) ? Math.max(lo, Math.min(hi, Math.round(v * 100) / 100)) : d; };
    const scenes = (Array.isArray(p.scenes) ? p.scenes : []).slice(0, 40).map((sc, i) => ({
      id: String(sc.id || "s" + i).replace(/[^a-z0-9_-]/gi, "").slice(0, 20) || "s" + i,
      name: String(sc.name || "Plan " + (i + 1)).slice(0, 40),
      items: (Array.isArray(sc.items) ? sc.items : []).slice(0, 16).map((it, j) => ({
        id: String(it.id || "i" + j).replace(/[^a-z0-9_-]/gi, "").slice(0, 20) || "i" + j,
        k: ["cam", "media"].includes(it.k) ? it.k : "trib", ref: String(it.ref || "").slice(0, 40), loop: !!it.loop, vol: num(it.vol, 100, 0, 100),
        x: num(it.x, 0, -50, 150), y: num(it.y, 0, -50, 150), w: num(it.w, 50, 2, 200), h: num(it.h, 50, 2, 200), z: num(it.z, j, 0, 99),
        label: it.label !== false, audio: !!it.audio
      }))
    }));
    if (!scenes.length) scenes.push({ id: "s1", name: "Plan 1", items: [] });
    const t = p.trans || {};
    return { scenes, live: scenes.some(x => x.id === this.prog.live) ? this.prog.live : "", preview: scenes.some(x => x.id === p.preview) ? p.preview : scenes[0].id,
      trans: { type: ["logo", "fade", "cut"].includes(t.type) ? t.type : "logo", ms: num(t.ms, 1400, 300, 4000) }, seq: this.prog.seq, title: String(p.title || "").slice(0, 80),
      starts: this.prog.starts || {}, sfx: this.prog.sfx || null };
  }
  pushProg(newShot) {
    const pub = JSON.stringify({ t: "prog", p: this.progPublic(), cut: !!newShot });
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && !a.mod && !a.server && !a.lock) this.send(ws, pub); }
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
      this.media = [...this.media.filter(x => x.id !== id), meta].slice(-60);
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

  async onProgOp(a, m, reply) {
    if (!hasPerm(a, "program") || a.lock) return reply(false, { msg: "Il faut la permission « Réalisation de l'émission »." });
    const wasLive = JSON.stringify(this.progPublic());
    if (m.t === "progSave") {
      this.prog = this.cleanProg(m.prog || {});
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
      this.prog.live = sc ? sc.id : "";
      this.prog.seq = (this.prog.seq || 0) + 1;
      if (m.trans) this.prog.trans = this.cleanProg({ ...this.prog, trans: m.trans }).trans;
    }
    await this.ctx.storage.put("prog", this.prog);
    this.broadcast({ t: "progEd", prog: this.prog, by: a.id }, x => hasPerm(x, "program") && !x.lock);
    if (m.t === "progTake") this.pushProg(true);
    else if (JSON.stringify(this.progPublic()) !== wasLive) this.pushProg(false);
    else this.pushOnAir(false);
    return reply(true);
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch (e) {}
    const a = this.att(ws);
    if (a && a.server) this.crewcast({ t: "srv", on: this.serverOn(ws) });
    if (a && a.mod) this.camsChanged();
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
    if (this.camsT) return;
    this.camsT = setTimeout(() => {
      this.camsT = null;
      this.crewcast({ t: "cams", cams: this.camsList() });
      this.crewcast({ t: "team", team: this.teamForMod() }, x => x.mod);
      this.sendServers({ t: "cams", names: this.camNames() });
    }, 1500);
  }
  camNames() {
    const out = new Set();
    for (const m of this.team) if (m.mc && CAMROLES.includes(this.camOf(m.id))) out.add(m.mc);
    for (const [id, st] of Object.entries(this.camSt)) if (st.mc && CAMROLES.includes(this.camOf(id))) out.add(st.mc);
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
    const refuse = (code, msg) => { this.send(server, { t: "err", msg }); try { server.close(code, msg); } catch (e) {} return new Response(null, { status: 101, webSocket: client }); };
    if (who.server) {
      if (!this.srvKey || who.key !== this.srvKey) return refuse(4003, "Clé serveur invalide (régie › Carte & caméras)");
      server.serializeAttachment({ id: "server", name: "Serveur Minecraft", server: true, at: Date.now() });
      this.send(server, { t: "hello", server: true, now: Date.now(), names: this.camNames() });
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
      roles: this.roles, onair: this.onAirCams(), prep: this.prepCams(),
      tribs: this.tribsForMod(), alerts, cams: this.camsList(), team: this.teamForMod(),
      feed: this.camData.feed.slice(-150), comms: this.camData.comms.filter(x => this.msgVisible(x, a)).slice(-150),
      reqs: Object.values(this.camData.reqs), pos: this.latestPos()
    };
  }

  async onModMessage(ws, a, m) {
    const reply = (ok, extra = {}) => { if (m.rid) this.send(ws, { t: ok ? "ok" : "err", rid: m.rid, ...extra }); else if (!ok && extra.msg) this.send(ws, { t: "err", ...extra }); };
    if (a.server) {
      if (m.t === "srvCams") {
        this.srvCams = (Array.isArray(m.names) ? m.names : []).map(n => String(n).replace(/[^A-Za-z0-9_]/g, "").slice(0, 16)).filter(Boolean).slice(0, 60);
        this.ctx.storage.put("srvcams", this.srvCams);
        this.crewcast({ t: "srvCams", names: this.srvCams }, x => !x.mod);
        return;
      }
      if (m.t === "seen") this.ingestSeen(Array.isArray(m.list) ? m.list : [], "serveur", true);
      else if (m.t === "ev") this.ingestEv(Array.isArray(m.list) ? m.list : [], "serveur", true, a);
      return;
    }
    switch (m.t) {
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
      const ev = { k, tr: t.id, n: t.name, cause: String(e.cause || "").slice(0, 40), by: byT ? byT.name : String(e.by || "").slice(0, 40), byTr: byT ? byT.id : "", byT: String(e.byT || "").slice(0, 60), via: String(e.via || "").slice(0, 60), src };
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
      if (isFinite(ev.hp) && ev.x != null) this.setPos(t, { x: ev.x, y: ev.y, z: ev.z, d: ev.d, hp: ev.hp }, src);
    }
    this.flushPosSoon();
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
    if (this.board.lock && this.board.lock.mode === "schedule" && this.sockets().length && this.board.tributes.some(x => x.discordId))
      t = Math.min(t, (Math.floor(now / 60000) + 1) * 60000 + 1000);
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
    if (revealed) this.broadcastBoard({ viewersOnly: true });
    await this.scheduleAlarm();
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
