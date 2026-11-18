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
async function readSession(req, env) {
  const raw = getCookie(req, COOKIE);
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
const PUBLIC_PATHS = new Set(["/login", "/login.html", "/logo.png", "/favicon.ico"]);

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
const SCALARS = ["ticker", "don", "fund", "day", "startTs", "cooldown", "lastHommage", "flash", "delay", "hideChat", "lock", "sponsor", "publicAccess"];
const STATUSES = ["alive", "injured", "missing", "dead"];
const PERMS = ["admin", "mod", "member"];
const LOGIN_RE = /^[a-z0-9_]{3,25}$/;
const cleanLogin = s => { s = String(s || "").toLowerCase(); return LOGIN_RE.test(s) ? s : null; };
const isStaff = a => a && (a.perm === "admin" || a.perm === "mod");
const defaultBoard = () => ({
  tributes: [], ticker: "", don: { label: "Sponsoriser", url: "" }, fund: { raised: 0, goal: 0, cur: "€" },
  day: 0, startTs: null, cooldown: 20, lastHommage: 0, events: [], flash: null, delay: 0, hideChat: true, publicAccess: false,
  fund: { raised: 0, goal: 0, cur: "€", show: true }, fundLog: [],
  lock: { mode: "off", tz: "Europe/Paris", windows: [], msg: "" },
  sponsor: { url: "", label: "Sponsoriser un tribut", text: "", partners: [] }
});

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
      const m = await this.ctx.storage.get(["board", "alerts", "team", "banned", "live"]);
      this.board = { ...defaultBoard(), ...(m.get("board") || {}) };
      this.alerts = m.get("alerts") || {};
      this.team = m.get("team") || [];
      this.banned = m.get("banned") || {};
      this.live = m.get("live") || null;
    })();
    return this.loading;
  }

  get owners() { return String(this.env.OWNER_DISCORD_IDS || "").split(/[,\s]+/).filter(Boolean); }
  permOf(id) {
    if (this.owners.includes(id)) return "admin";
    const m = this.team.find(x => x.id === id);
    return m && (m.perm === "admin" || m.perm === "mod") ? m.perm : "viewer";
  }
  sockets() { return this.ctx.getWebSockets(); }
  att(ws) { try { return ws.deserializeAttachment(); } catch (e) { return null; } }
  send(ws, obj) { try { ws.send(typeof obj === "string" ? obj : JSON.stringify(obj)); } catch (e) {} }
  fullView(a) { return isStaff(a) && !a.pub; }

  /* ---------- connexion ---------- */
  async fetch(req) {
    await this.load();
    if (new URL(req.url).pathname === "/public") return Response.json({ open: !!this.board.publicAccess });
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
      this.team.push({ id: user.id, name: user.name, av: user.av, role: "Organisation", perm: "admin", bio: "" });
      teamChanged = true;
    }
    const tm = this.team.find(m => m.id === user.id);
    if (tm && (tm.name !== user.name || tm.av !== user.av)) { tm.name = user.name; tm.av = user.av; teamChanged = true; }
    if (teamChanged) { await this.ctx.storage.put("team", this.team); this.broadcast({ t: "team", team: this.team }); }

    const a = { id: user.id, name: user.name, av: user.av || "", perm: user.overlay || user.guest ? "viewer" : this.permOf(user.id), overlay: !!user.overlay, guest: !!user.guest, ch: null, chs: [], at: Date.now(), sub: false, pub: false };
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
      me: { id: a.id, name: a.name, av: a.av, perm: a.perm, pub: a.pub, lock: !!a.lock, guest: !!a.guest, tribute: this.tributeOf(a.id) },
      board: this.viewFor(a),
      alerts: isStaff(a) ? this.alerts : undefined,
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
    for (const ws of this.sockets()) { const a = this.att(ws); if (a && (!filter || filter(a))) this.send(ws, s); }
  }
  broadcastBoard({ viewersOnly = false } = {}) {
    let full, pub, locked;
    for (const ws of this.sockets()) {
      const a = this.att(ws); if (!a) continue;
      const f = this.fullView(a);
      if (viewersOnly && f) continue;
      if (f) this.send(ws, full = full || JSON.stringify({ t: "board", board: this.board }));
      else if (a.lock) this.send(ws, locked = locked || JSON.stringify({ t: "board", board: this.lockedBoard() }));
      else this.send(ws, pub = pub || JSON.stringify({ t: "board", board: this.publicBoard() }));
    }
  }
  presence(full) {
    const users = new Map(), counts = {}; let guests = 0;
    for (const ws of this.sockets()) {
      const a = this.att(ws);
      if (!a || a.overlay) continue;
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
        const a = this.att(ws); if (!a) continue;
        if (a.lock) this.send(ws, JSON.stringify({ t: "presence", p: { n: this.presence(false).n, counts: {}, team: {} } }));
        else if (a.sub) { full = full || JSON.stringify({ t: "presence", p: this.presence(true) }); this.send(ws, full); }
        else this.send(ws, light);
      }
    }, 2500);
  }
  refreshPerms() {
    for (const ws of this.sockets()) {
      const a = this.att(ws);
      if (!a || a.overlay) continue;
      const p = this.permOf(a.id);
      if (p !== a.perm) { a.perm = p; a.lock = this.isLocked(a); ws.serializeAttachment(a); this.send(ws, this.hello(a)); }
    }
  }

  /* ---------- messages ---------- */
  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 400000) return;
    await this.load();
    let m; try { m = JSON.parse(msg); } catch (e) { return; }
    const a = this.att(ws); if (!a) return;
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
        await this.scheduleAlarm();
        reply(true); break;
      }
      case "alertUpd": {
        if (!staff) return deny();
        const al = this.alerts[m.id]; if (!al) return reply(false, { msg: "Alerte introuvable." });
        const p = {};
        if ("handled" in (m.p || {})) p.handled = !!m.p.handled;
        if ("claimedBy" in (m.p || {})) p.claimedBy = String(m.p.claimedBy || "").slice(0, 32);
        Object.assign(al, p);
        await this.ctx.storage.put("alerts", this.alerts);
        this.broadcast({ t: "alertUpd", id: m.id, p }, isStaff);
        reply(true); break;
      }
      case "alertsClear": {
        if (!admin) return deny();
        this.alerts = {}; await this.ctx.storage.put("alerts", this.alerts);
        this.broadcast({ t: "alerts", all: {} }, isStaff); reply(true); break;
      }
      case "team": {
        if (!admin) return deny();
        this.team = this.cleanTeam(m.team);
        await this.ctx.storage.put("team", this.team);
        this.broadcast({ t: "team", team: this.team });
        this.refreshPerms(); this.refreshLocks(); this.schedulePresence(); reply(true); break;
      }
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
      case "twitchPoll": {
        if (!admin) return deny();
        await this.pollTwitch(true); reply(true, { live: this.live }); break;
      }
    }
  }

  async onAlert(ws, a, raw, reply) {
    if (a.overlay) return reply(false, { msg: "Non autorisé." });
    if (a.guest) return reply(false, { msg: "Connecte-toi avec Discord pour envoyer un signalement." });
    if (a.lock) return reply(false, { msg: "Les flux sont fermés aux tributs en ce moment." });
    const ch = cleanLogin(raw.ch);
    if (!ch || !this.board.tributes.find(t => t.login === ch)) return reply(false, { msg: "Tribut inconnu." });
    const now = Date.now(), cd = (this.board.cooldown || 0) * 1000, last = this.lastAlert.get(a.id) || 0;
    if (!isStaff(a) && now - last < cd) return reply(false, { msg: `Patiente encore ${Math.ceil((cd - (now - last)) / 1000)} s avant un nouveau signalement.` });
    this.lastAlert.set(a.id, now);
    const id = now.toString(36) + Math.random().toString(36).slice(2, 7);
    const al = {
      ch, type: TYPES.includes(raw.type) ? raw.type : "Autre",
      detail: String(raw.detail || "").trim().slice(0, 300) || "(aucun détail)",
      urgent: !!raw.urgent, by: a.name, uid: a.id, av: a.av, ts: now, handled: false
    };
    this.alerts[id] = al;
    const ids = Object.keys(this.alerts).sort((x, y) => this.alerts[x].ts - this.alerts[y].ts);
    while (ids.length > 400) delete this.alerts[ids.shift()];
    await this.ctx.storage.put("alerts", this.alerts);
    this.broadcast({ t: "alert", id, a: al }, isStaff);
    reply(true);
  }

  applyPatch(p) {
    const b = this.board, now = Date.now();
    if (p.set && typeof p.set === "object") {
      for (const [k, v] of Object.entries(p.set)) if (SCALARS.includes(k)) b[k] = v;
      if (p.set.flash && typeof p.set.flash === "object") b.flash = { ...p.set.flash, ts: now };
      if (p.set.fund && typeof p.set.fund === "object") { b.fund = { goal: Math.max(0, +p.set.fund.goal || 0), cur: String(p.set.fund.cur || "€").slice(0, 4), show: p.set.fund.show !== false, raised: 0 }; this.recalcFund(); }
      b.publicAccess = !!b.publicAccess;
      b.delay = Math.max(0, Math.min(900, parseInt(b.delay, 10) || 0));
      b.cooldown = Math.max(0, Math.min(600, parseInt(b.cooldown, 10) || 0));
    }
    if (Array.isArray(p.tributes)) for (const t of p.tributes.slice(0, 999)) {
      if (!t || !t.id || !cleanLogin(t.login)) continue;
      const i = b.tributes.findIndex(x => x.id === t.id);
      const prev = i >= 0 ? b.tributes[i] : null;
      if (!STATUSES.includes(t.status)) t.status = "alive";
      t.discordId = String(t.discordId || "").replace(/\D/g, "").slice(0, 20);
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
    return arr.slice(0, 100).map(m => ({
      id: String(m && m.id || "").replace(/\D/g, "").slice(0, 20),
      name: String(m.name || "").slice(0, 32), av: String(m.av || "").slice(0, 300),
      role: String(m.role || "").slice(0, 40), perm: PERMS.includes(m.perm) ? m.perm : "member",
      bio: String(m.bio || "").slice(0, 200)
    })).filter(m => m.id && !seen.has(m.id) && seen.add(m.id));
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch (e) {}
    this.schedulePresence();
  }
  async webSocketError() { this.schedulePresence(); }

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
