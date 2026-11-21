package fr.capitoltv.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.capitoltv.CapitolTV;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientConfig {
    public String url = "https://capitol-tv.refugeemeraudien-direction.workers.dev";
    public String token = "";
    /** actif | rec | pause | inactif */
    public String state = "actif";
    /** all | sup | none */
    public String notif = "all";
    public boolean sound = true;
    public boolean hud = true;
    /** tl | tr | bl */
    public String hudPos = "tl";
    public boolean toasts = true;
    public boolean autoReport = true;
    public boolean showCamsOnMap = true;
    public boolean alertsToasts = true;
    public boolean feedToasts = false;
    /** mode caméra : HUD du jeu masqué, barre d'outils caméra (touches 1 à 9) */
    public boolean cameraMode = true;
    /** flux propre quand la régie t'affiche : plus aucune interface à l'écran (maintenir Alt gauche pour voir) */
    public boolean cleanOnAir = true;
    /** tout masquer en permanence (flux propre même hors antenne) */
    public boolean cleanAlways = false;
    /** voyant d'antenne : visible | discret | cache */
    public String tally = "visible";
    /** passer automatiquement en spectateur quand on est caméra */
    public boolean autoSpectator = true;
    /** diffusion de l'image du jeu vers Capitol TV (sans OBS) */
    public boolean streamOn = false;
    public String streamKey = "";
    public boolean streamMic = false;
    public int streamFps = 30;
    public int streamKbps = 4000;
    public int streamHeight = 720;
    /** niveau de qualité choisi par le joueur : off | eco | std | hd | max (vide = pas encore choisi) */
    public String streamLevel = "";
    /** l'écran « Ma diffusion » a déjà été proposé à la première connexion */
    public boolean streamAsked = false;
    /** baisse automatique de la qualité si le jeu ralentit */
    public boolean streamAdapt = true;
    /** son du jeu envoyé avec la POV (sinon le micro si activé) */
    public boolean gameAudio = true;
    /** menu réduit sur le côté : on voit le jeu et on peut bouger */
    public boolean dockMenu = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static ClientConfig inst;

    public static ClientConfig get() {
        if (inst == null) load();
        return inst;
    }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("capitoltv.json"); }

    public static void load() {
        try {
            Path p = path();
            inst = Files.exists(p) ? GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), ClientConfig.class) : null;
        } catch (Exception e) {
            CapitolTV.LOG.warn("Capitol TV : configuration illisible, valeurs par défaut ({})", e.toString());
        }
        if (inst == null) inst = new ClientConfig();
        save();
    }

    public static void save() {
        try { Files.writeString(path(), GSON.toJson(inst), StandardCharsets.UTF_8); }
        catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : impossible d'enregistrer la configuration ({})", e.toString()); }
    }
}
