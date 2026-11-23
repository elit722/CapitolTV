package fr.capitoltv.server;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import fr.capitoltv.CapitolTV;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Extension pour Simple Voice Chat (si le mod est installé sur le serveur) :
 *  - les joueurs n'entendent pas les caméras ni le staff ;
 *  - caméras et staff s'entendent entre eux (à portée normale) ;
 *  - le staff (responsable caméra et plus) peut se rendre audible par les joueurs avec une touche.
 * Les groupes de Simple Voice Chat ne sont pas touchés.
 */
public final class CtvVoice implements VoicechatPlugin {
    @Override public String getPluginId() { return "capitoltv"; }

    @Override public void initialize(VoicechatApi api) { CapitolTV.LOG.info("Capitol TV : chat vocal géré (caméras inaudibles pour les joueurs)"); }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(MicrophonePacketEvent.class, this::onMic);
        // côté client : les voix entendues partent aussi dans la POV diffusée (voir VoiceMix)
        reg.registerEvent(de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent.EntitySound.class, e -> { var t = fr.capitoltv.VoiceTap.sink; if (t != null && e.getRawAudio() != null) t.voice(e.getId(), e.getRawAudio(), null); });
        reg.registerEvent(de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent.LocationalSound.class, e -> { var t = fr.capitoltv.VoiceTap.sink; if (t != null && e.getRawAudio() != null) t.voice(e.getId(), e.getRawAudio(), new double[]{e.getPosition().getX(), e.getPosition().getY(), e.getPosition().getZ()}); });
        reg.registerEvent(de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent.StaticSound.class, e -> { var t = fr.capitoltv.VoiceTap.sink; if (t != null && e.getRawAudio() != null) t.voice(e.getId(), e.getRawAudio(), new double[0]); });
        reg.registerEvent(de.maxhenkel.voicechat.api.events.ClientSoundEvent.class, e -> { var t = fr.capitoltv.VoiceTap.sink; if (t != null && e.getRawAudio() != null) t.own(e.getRawAudio()); });
    }

    private void onMic(MicrophonePacketEvent e) {
        VoicechatConnection from = e.getSenderConnection();
        if (from == null || from.isInGroup()) return;
        if (!(from.getPlayer().getPlayer() instanceof ServerPlayerEntity sp)) return;
        String name = sp.getGameProfile().getName();
        if (!ServerBridge.voiceSilent(name)) return;
        boolean loud = ServerBridge.voiceLoud(name);
        // on prend la main : envoi à la bonne liste de personnes, à la portée normale
        e.cancel();
        VoicechatServerApi api = e.getVoicechat();
        double dist = api.getVoiceChatDistance();
        var server = sp.getServer(); if (server == null) return;
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o == sp || o.getWorld() != sp.getWorld()) continue;
            if (!loud && !ServerBridge.voiceSilent(o.getGameProfile().getName())) continue;
            if (o.squaredDistanceTo(sp) > dist * dist) continue;
            VoicechatConnection c = api.getConnectionOf(o.getUuid());
            if (c == null) continue;
            api.sendLocationalSoundPacketTo(c, e.getPacket().locationalSoundPacketBuilder()
                .position(api.createPosition(sp.getX(), sp.getEyeY(), sp.getZ())).distance((float) dist).build());
        }
    }
}
