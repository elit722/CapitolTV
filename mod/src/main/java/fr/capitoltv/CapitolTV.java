package fr.capitoltv;

import fr.capitoltv.net.CamAccessPayload;
import fr.capitoltv.net.StreamKeyPayload;
import fr.capitoltv.net.SpecPayload;
import fr.capitoltv.net.TpPayload;
import fr.capitoltv.server.ServerBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CapitolTV implements ModInitializer {
    public static final String ID = "capitoltv";
    public static final Logger LOG = LoggerFactory.getLogger("CapitolTV");

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(TpPayload.ID, TpPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SpecPayload.ID, SpecPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CamAccessPayload.ID, CamAccessPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamKeyPayload.ID, StreamKeyPayload.CODEC);
        ServerBridge.init();
    }
}
