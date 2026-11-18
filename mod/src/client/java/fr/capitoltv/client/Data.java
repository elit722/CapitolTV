package fr.capitoltv.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** État partagé avec le site Capitol TV (mis à jour par la liaison WebSocket, lu par l'interface). */
public final class Data {
    private Data() { }

    /* ---------- modèles ---------- */
    public static final class Trib { public String id = "", name = "", mc = "", login = "", status = "alive"; public int num, district; }
    public static final class Alert { public String id = "", ch = "", type = "", detail = "", by = "", claimedBy = "", uid = ""; public long ts; public boolean urgent, handled, rec; }
    public static final class Feed { public String id = "", k = "", tr = "", text = "", src = "", d = ""; public long ts; public double x, y, z; public boolean hasPos; }
    public static final class Msg { public String id = "", ch = "all", to = "", toName = "", from = "", name = "", text = "", k = "msg"; public boolean sup; public long ts; }
    public static final class Req { public String id = "", by = "", byName = "", tr = "", text = "", to = "", toName = "", state = "open", taker = "", takerName = ""; public long ts; public boolean bySup; }
    public static final class Cam { public String id = "", name = "", cam = "", st = "", follow = "", mc = "", d = ""; public boolean online; public double x, y, z; public long at; public boolean hasPos; }
    public static final class Pos { public double x, y, z, hp = -1; public String d = "", src = ""; public long ts; }
    public static final class Member { public String id = "", name = "", cam = "", perm = ""; public boolean online, sup; }

    /* ---------- état ---------- */
    public static String meId = "", meName = "", meCam = "", mePerm = "";
    public static boolean meSup;
    public static int delay;
    public static long skew;
    public static boolean serverMod;
    public static boolean ready;
    public static final Map<String, Trib> tribs = new LinkedHashMap<>();
    public static final Map<String, Alert> alerts = new HashMap<>();
    public static final List<Feed> feed = new ArrayList<>();
    public static final List<Msg> msgs = new ArrayList<>();
    public static final Map<String, Req> reqs = new LinkedHashMap<>();
    public static final Map<String, Cam> cams = new LinkedHashMap<>();
    public static final Map<String, Pos> pos = new HashMap<>();
    public static final List<Member> team = new ArrayList<>();
    public static String lastSentAlertId = "";
    public static long lastReportTs;

    public static long now() { return System.currentTimeMillis() + skew; }

    /** Temps restant avant que l'action passe à l'écran (différé de diffusion), en ms (<= 0 : déjà à l'antenne). */
    public static long airIn(long ts) { return ts + delay * 1000L - now(); }

    public static String airLabel(long ts) {
        if (delay <= 0) return "";
        long ms = airIn(ts);
        if (ms <= 0) return "à l'antenne";
        long s = (ms + 999) / 1000;
        return "écran dans " + (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    public static String ago(long ts) {
        long s = Math.max(0, (now() - ts) / 1000);
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min";
        return (s / 3600) + " h";
    }

    public static Trib tribById(String id) { return id == null ? null : tribs.get(id); }
    public static Trib tribByLogin(String l) { for (Trib t : tribs.values()) if (t.login.equals(l)) return t; return null; }
    public static Trib tribByMc(String mc) {
        if (mc == null || mc.isEmpty()) return null;
        for (Trib t : tribs.values()) if (!t.mc.isEmpty() && t.mc.equalsIgnoreCase(mc)) return t;
        return null;
    }
    public static List<Trib> tribList() {
        List<Trib> l = new ArrayList<>(tribs.values());
        l.sort(Comparator.comparingInt(t -> t.num));
        return l;
    }
    public static List<Trib> aliveTribs() {
        List<Trib> l = tribList();
        l.removeIf(t -> "dead".equals(t.status));
        return l;
    }
    public static String tribLabel(String id) {
        Trib t = tribById(id);
        return t == null ? "?" : String.format(Locale.ROOT, "%03d %s", t.num, t.name);
    }
    public static String memberName(String id) {
        for (Member m : team) if (m.id.equals(id)) return m.name;
        Cam c = cams.get(id);
        return c != null ? c.name : "?";
    }
    public static Req myOpenAssignment() {
        Req best = null;
        for (Req r : reqs.values()) {
            if ("done".equals(r.state)) continue;
            if (meId.equals(r.to) || meId.equals(r.taker)) if (best == null || r.ts > best.ts) best = r;
        }
        return best;
    }

    /* ---------- lecture JSON ---------- */
    static String s(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
    static long l(JsonObject o, String k) { JsonElement e = o.get(k); try { return e == null || e.isJsonNull() ? 0 : e.getAsLong(); } catch (Exception x) { return 0; } }
    static double d(JsonObject o, String k) { JsonElement e = o.get(k); try { return e == null || e.isJsonNull() ? 0 : e.getAsDouble(); } catch (Exception x) { return 0; } }
    static boolean b(JsonObject o, String k) { JsonElement e = o.get(k); try { return e != null && !e.isJsonNull() && e.getAsBoolean(); } catch (Exception x) { return false; } }
    static JsonArray arr(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray(); }
    static JsonObject obj(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }

    static Trib trib(JsonObject o) {
        Trib t = new Trib();
        t.id = s(o, "id"); t.name = s(o, "name"); t.mc = s(o, "mc"); t.login = s(o, "login"); t.status = s(o, "status");
        t.num = (int) l(o, "num"); t.district = (int) l(o, "district");
        return t;
    }
    static Alert alert(String id, JsonObject o) {
        Alert a = new Alert();
        a.id = id; a.ch = s(o, "ch"); a.type = s(o, "type"); a.detail = s(o, "detail"); a.by = s(o, "by");
        a.claimedBy = s(o, "claimedBy"); a.uid = s(o, "uid"); a.ts = l(o, "ts");
        a.urgent = b(o, "urgent"); a.handled = b(o, "handled"); a.rec = b(o, "rec");
        return a;
    }
    static Feed feedOf(JsonObject o) {
        Feed f = new Feed();
        f.id = s(o, "id"); f.k = s(o, "k"); f.tr = s(o, "tr"); f.text = s(o, "text"); f.src = s(o, "src"); f.ts = l(o, "ts");
        f.d = s(o, "d"); f.hasPos = o.has("x");
        f.x = d(o, "x"); f.y = d(o, "y"); f.z = d(o, "z");
        return f;
    }
    static Msg msg(JsonObject o) {
        Msg m = new Msg();
        m.id = s(o, "id"); m.ch = s(o, "ch"); m.to = s(o, "to"); m.toName = s(o, "toName"); m.from = s(o, "from");
        m.name = s(o, "name"); m.text = s(o, "text"); m.k = s(o, "k"); m.sup = b(o, "sup"); m.ts = l(o, "ts");
        if (m.k.isEmpty()) m.k = "msg";
        return m;
    }
    static Req req(JsonObject o) {
        Req r = new Req();
        r.id = s(o, "id"); r.by = s(o, "by"); r.byName = s(o, "byName"); r.tr = s(o, "tr"); r.text = s(o, "text");
        r.to = s(o, "to"); r.toName = s(o, "toName"); r.state = s(o, "state"); r.taker = s(o, "taker"); r.takerName = s(o, "takerName");
        r.ts = l(o, "ts"); r.bySup = b(o, "bySup");
        return r;
    }
    static Cam cam(JsonObject o) {
        Cam c = new Cam();
        c.id = s(o, "id"); c.name = s(o, "name"); c.cam = s(o, "cam"); c.st = s(o, "st"); c.follow = s(o, "follow"); c.mc = s(o, "mc");
        c.online = b(o, "online"); c.at = l(o, "at");
        JsonObject p = obj(o, "pos");
        if (p.has("x")) { c.hasPos = true; c.x = d(p, "x"); c.y = d(p, "y"); c.z = d(p, "z"); c.d = s(p, "d"); }
        return c;
    }
    static Pos posOf(JsonObject o) {
        Pos p = new Pos();
        p.x = d(o, "x"); p.y = d(o, "y"); p.z = d(o, "z"); p.d = s(o, "d"); p.src = s(o, "src"); p.ts = l(o, "ts");
        p.hp = o.has("hp") ? d(o, "hp") : -1;
        return p;
    }

    /* ---------- application des messages ---------- */
    static void setTribs(JsonArray a) {
        tribs.clear();
        for (JsonElement e : a) if (e.isJsonObject()) { Trib t = trib(e.getAsJsonObject()); tribs.put(t.id, t); }
    }
    static void setCams(JsonArray a) {
        cams.clear();
        for (JsonElement e : a) if (e.isJsonObject()) { Cam c = cam(e.getAsJsonObject()); cams.put(c.id, c); }
    }
    static void setTeam(JsonArray a) {
        team.clear();
        for (JsonElement e : a) if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            Member m = new Member();
            m.id = s(o, "id"); m.name = s(o, "name"); m.cam = s(o, "cam"); m.perm = s(o, "perm"); m.online = b(o, "online"); m.sup = b(o, "sup");
            team.add(m);
        }
    }
    static void mergePos(JsonObject p) {
        for (Map.Entry<String, JsonElement> en : p.entrySet()) {
            if (en.getValue().isJsonObject()) pos.put(en.getKey(), posOf(en.getValue().getAsJsonObject()));
        }
    }
    static void addFeed(Feed f) {
        for (Feed x : feed) if (x.id.equals(f.id)) { x.text = f.text; x.src = f.src; return; }
        feed.add(f);
        if (feed.size() > 400) feed.remove(0);
    }
    static void addMsg(Msg m) {
        for (Msg x : msgs) if (x.id.equals(m.id)) return;
        msgs.add(m);
        if (msgs.size() > 400) msgs.remove(0);
    }

    public static void reset() {
        ready = false; tribs.clear(); alerts.clear(); feed.clear(); msgs.clear(); reqs.clear(); cams.clear(); pos.clear(); team.clear();
    }

    static void hello(JsonObject m) {
        reset();
        JsonObject me = obj(m, "me");
        meId = s(me, "id"); meName = s(me, "name"); meCam = s(me, "cam"); mePerm = s(me, "perm"); meSup = b(me, "sup");
        skew = l(m, "now") - System.currentTimeMillis();
        delay = (int) l(m, "delay");
        serverMod = b(m, "srv");
        setTribs(arr(m, "tribs"));
        setCams(arr(m, "cams"));
        setTeam(arr(m, "team"));
        for (JsonElement e : arr(m, "alerts")) if (e.isJsonObject()) { JsonObject o = e.getAsJsonObject(); Alert a = alert(s(o, "id"), o); alerts.put(a.id, a); }
        for (JsonElement e : arr(m, "feed")) if (e.isJsonObject()) addFeed(feedOf(e.getAsJsonObject()));
        for (JsonElement e : arr(m, "comms")) if (e.isJsonObject()) addMsg(msg(e.getAsJsonObject()));
        for (JsonElement e : arr(m, "reqs")) if (e.isJsonObject()) { Req r = req(e.getAsJsonObject()); reqs.put(r.id, r); }
        mergePos(obj(m, "pos"));
        ready = true;
    }

    public static List<Alert> alertList(boolean pendingOnly) {
        List<Alert> l = new ArrayList<>(alerts.values());
        if (pendingOnly) l.removeIf(a -> a.handled);
        l.sort((a, b) -> Long.compare(b.ts, a.ts));
        return l;
    }
}
