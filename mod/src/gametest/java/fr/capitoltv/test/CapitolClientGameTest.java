package fr.capitoltv.test;

import com.google.gson.JsonParser;
import fr.capitoltv.client.CapitolClient;
import fr.capitoltv.client.ClientConfig;
import fr.capitoltv.client.Data;
import fr.capitoltv.client.Hud;
import fr.capitoltv.client.gui.CapitolScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Démarre un vrai client, charge des données de démonstration et photographie l'interface. */
@SuppressWarnings("UnstableApiUsage")
public class CapitolClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            context.runOnClient(c -> {
                long now = System.currentTimeMillis();
                String me = c.player.getGameProfile().getName();
                String demo = """
                {"t":"hello","now":NOW,"delay":120,"srv":true,
                 "me":{"id":"1","name":"Caesar","perm":"admin","cam":"chef","sup":true,"perms":["admin"],"rid":"org"},
                 "roles":[{"id":"org","name":"Organisation","color":"#d6b261","perms":["admin"],"locked":true},{"id":"real","name":"Réalisation","color":"#e0514a","perms":["program","mod"]},{"id":"cam","name":"Caméraman","color":"#5fbf8a","perms":["cam"]}],
                 "onair":[],
                 "tribs":[{"id":"a","name":"Katniss","mc":"ME","login":"katniss","num":1,"district":12,"status":"alive"},
                          {"id":"b","name":"Peeta","mc":"PeetaMC","login":"peeta","num":2,"district":12,"status":"injured"},
                          {"id":"c","name":"Rue","mc":"RueMC","login":"rue","num":3,"district":11,"status":"dead"}],
                 "team":[{"id":"1","name":"Caesar","cam":"chef","perm":"admin","rid":"org","online":true,"sup":true},
                         {"id":"2","name":"CamOne","cam":"cam","perm":"member","rid":"cam","twitch":"camone","online":true}],
                 "cams":[{"id":"2","name":"CamOne","cam":"cam","online":true,"st":"rec","follow":"a","mc":"CamOne","at":NOW,"pos":{"x":40,"y":70,"z":-20,"d":"minecraft:overworld"}}],
                 "alerts":[{"id":"x1","ch":"katniss","type":"Combat","detail":"Peeta attaque Katniss au lac","by":"CamOne","ts":NOW,"urgent":true,"rec":true},
                           {"id":"x2","ch":"peeta","type":"Alliance","detail":"Alliance avec les carrières ?","by":"Caesar","ts":NOW,"claimedBy":"CamOne"}],
                 "feed":[{"id":"f1","k":"meet","tr":"a","text":"⌖ Rencontre : Katniss et Peeta (10 blocs)","src":"carte","ts":NOW},
                         {"id":"f2","k":"hit","tr":"a","text":"⚔ Peeta attaque Katniss — 3,5 ❤ (reste 6,5 ❤)","src":"serveur","ts":NOW,"x":12,"y":64,"z":8,"d":"minecraft:overworld"},
                         {"id":"f3","k":"death","tr":"c","text":"☠ Rue est morte (une flèche)","src":"serveur","ts":NOW}],
                 "comms":[{"id":"m1","ch":"all","from":"2","name":"CamOne","text":"Je suis sur Katniss","ts":NOW},
                          {"id":"m2","ch":"all","from":"1","name":"Caesar","sup":true,"text":"Parfait, on passe à l'antenne dans 2 min","ts":NOW}],
                 "reqs":[{"id":"r1","by":"1","byName":"Caesar","tr":"b","text":"Couvre Peeta","to":"2","toName":"CamOne","state":"open","ts":NOW}],
                 "pos":{"a":{"x":12,"y":64,"z":8,"d":"minecraft:overworld","hp":13,"ts":NOW,"src":"serveur"},
                        "b":{"x":30,"y":64,"z":20,"d":"minecraft:overworld","hp":15,"ts":NOW,"src":"serveur"},
                        "c":{"x":-40,"y":63,"z":-30,"d":"minecraft:overworld","hp":0,"ts":NOW,"src":"serveur"}}}
                """.replace("NOW", String.valueOf(now)).replace("\"ME\"", "\"" + me + "\"");
                Data.loadDemo(JsonParser.parseString(demo).getAsJsonObject());
                CapitolClient.demo = true;
                CapitolClient.setFollow("a");
                ClientConfig c2 = ClientConfig.get();
                c2.cameraMode = true; c2.state = "rec";
                Hud.toast("Staff · Caesar", "Parfait, on passe à l'antenne dans 2 min", Hud.GOLD, 60);
            });
            context.waitTicks(40);
            context.takeScreenshot("ctv-01-camera-mode");
            context.runOnClient(c -> { Data.onAirIds.add("1"); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-02-onair-clean");
            context.runOnClient(c -> { Data.onAirIds.clear(); ClientConfig.get().cameraMode = false; });
            context.waitTicks(5);
            context.takeScreenshot("ctv-03-normal-hud");
            String[] tabs = {"ACTIONS", "FEED", "MAP", "SIGNAL", "COMMS", "TEAM", "REQS", "ROLES", "SETTINGS", "WIKI"};
            for (int i = 0; i < tabs.length; i++) {
                final String t = tabs[i];
                context.runOnClient(c -> c.setScreen(CapitolScreen.on(t)));
                context.waitTicks(10);
                context.takeScreenshot("ctv-1" + i + "-menu-" + t.toLowerCase());
            }
            // bibliothèque vidéo native + capture du monde seul, menu ouvert par-dessus
            context.runOnClient(c -> fr.capitoltv.CapitolTV.LOG.info("CTV-WEBRTC " + fr.capitoltv.client.Streamer.selfTest()));
            context.runOnClient(c -> { ClientConfig.get().dockMenu = true; c.setScreen(CapitolScreen.on("COMMS")); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-21-menu-dock");
            context.runOnClient(c -> fr.capitoltv.client.Streamer.testShot = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("ctv-22-flux-envoye.png"));
            context.waitTicks(20);
            context.runOnClient(c -> fr.capitoltv.CapitolTV.LOG.info("CTV-HOOK " + fr.capitoltv.client.Streamer.hooked));
            context.runOnClient(c -> fr.capitoltv.client.Streamer.testFast = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("ctv-23-capture-rapide.png"));
            context.waitTicks(20);
            context.runOnClient(c -> { ClientConfig.get().dockMenu = false; CapitolScreen.setPage = 3; c.setScreen(CapitolScreen.on("SETTINGS")); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-23-menu-diffusion");
            context.runOnClient(c -> { fr.capitoltv.client.Data.serverKey = "test"; fr.capitoltv.client.Data.serverWho = "Katniss"; c.setScreen(new fr.capitoltv.client.gui.StreamScreen(null, true)); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-25-ma-diffusion");
            context.runOnClient(c -> { fr.capitoltv.client.Data.serverKey = ""; c.setScreen(null); });
            context.runOnClient(c -> { c.setScreen(null); fr.capitoltv.client.CamMoves.picker = true; });
            context.waitTicks(5);
            context.takeScreenshot("ctv-24-mouvements");
            context.runOnClient(c -> fr.capitoltv.client.CamMoves.picker = false);
            context.runOnClient(c -> { CapitolScreen.setPage = 2; c.setScreen(CapitolScreen.on("SETTINGS")); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-19-menu-settings-camera");
            // plan en préparation : flux propre + voyant « PRÊT »
            context.runOnClient(c -> { c.setScreen(null); ClientConfig.get().cameraMode = true; fr.capitoltv.client.Data.prepIds.add("1"); });
            context.waitTicks(10);
            context.takeScreenshot("ctv-04-prep");
            context.runOnClient(c -> { fr.capitoltv.client.Data.prepIds.clear(); ClientConfig.get().cameraMode = false; c.setScreen(null); });
            // hommage dans le ciel, de nuit
            sp.getServer().runCommand("time set 18000");
            context.runOnClient(c -> { c.player.setYaw(180f); c.player.setPitch(-28f); });
            context.waitTicks(20);
            context.runOnClient(c -> fr.capitoltv.client.Overlays.onServerMsg("{\"t\":\"sky\",\"per\":6,\"title\":\"Ils sont tombés\",\"list\":[{\"name\":\"Rue\",\"d\":11,\"num\":3,\"mc\":\"" + c.player.getGameProfile().getName() + "\"}]}"));
            context.waitTicks(20 * 6);
            context.takeScreenshot("ctv-30-hommage-intro");
            context.waitTicks(20 * 5);
            context.takeScreenshot("ctv-31-hommage-visage");
            context.runOnClient(c -> { c.player.setYaw(140f); c.player.setPitch(-22f); });
            context.waitTicks(5);
            context.takeScreenshot("ctv-32-hommage-bas");
            context.runOnClient(c -> fr.capitoltv.client.Overlays.onServerMsg("{\"t\":\"recap\",\"n\":\"x\",\"name\":\"Rue\",\"district\":11,\"place\":3,\"lines\":[\"3e place sur 24\",\"Tombée face à Cato\",\"Temps de survie : 1 h 12\"]}"));
            context.waitTicks(10);
            context.takeScreenshot("ctv-33-recap");
            // projection personnalisée (texte de fond + texte devant)
            context.runOnClient(c -> { c.setScreen(null); c.player.setYaw(180f); c.player.setPitch(-30f); fr.capitoltv.client.Overlays.onServerMsg("{\"t\":\"sky\",\"kind\":\"show\",\"title\":\"Message du Capitole\",\"scenes\":[{\"bg\":\"JOUR 3\",\"fg\":\"Le festin commence a l'aube\",\"dur\":8}]}"); });
            context.waitTicks(20 * 10);
            context.takeScreenshot("ctv-34-projection");
        }
    }
}
