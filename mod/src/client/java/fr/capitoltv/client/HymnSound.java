package fr.capitoltv.client;

import fr.capitoltv.CapitolTV;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.RepeatingAudioStream;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Hymne de l'hommage (fichier .ogg servi par le site) : monte en douceur, redescend et s'arrête proprement. */
final class HymnSound extends MovingSoundInstance {
    private static final SoundEvent EVENT = SoundEvent.of(Identifier.of("capitoltv", "hymne"));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static String cachedUrl = ""; private static byte[] cached;

    private final byte[] data; private final float base;
    private int age, out = -1;

    private HymnSound(byte[] data, float vol) {
        super(EVENT, SoundCategory.MASTER, SoundInstance.createRandom());
        this.data = data; this.base = vol;
        this.volume = 0.001f; this.repeat = true; this.repeatDelay = 0; this.relative = true;
        this.attenuationType = SoundInstance.AttenuationType.NONE; this.x = 0; this.y = 0; this.z = 0;
    }

    static void load(String url, float vol, Consumer<HymnSound> then) {
        if (url.equals(cachedUrl) && cached != null) { then.accept(new HymnSound(cached, vol)); return; }
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "CapitolTV-Cam/1.0").build(), HttpResponse.BodyHandlers.ofByteArray())
            .thenAccept(r -> {
                if (r.statusCode() != 200) { CapitolTV.LOG.warn("Capitol TV : hymne indisponible ({})", r.statusCode()); return; }
                byte[] b = r.body(); cachedUrl = url; cached = b;
                MinecraftClient.getInstance().execute(() -> then.accept(new HymnSound(b, vol)));
            });
    }

    void fadeOut() { if (out < 0) out = 80; }

    @Override public boolean shouldAlwaysPlay() { return true; }

    @Override
    public void tick() {
        age++;
        if (out >= 0) { out--; float k = out / 80f; volume = Math.max(0.001f, base * k * k * (3 - 2 * k)) * Math.min(1f, age / 50f); if (out <= 0) setDone(); return; }
        float k = Math.min(1f, age / 50f);
        volume = Math.max(0.001f, base * k * k * (3 - 2 * k));
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
        return CompletableFuture.supplyAsync(() -> {
            try { return new RepeatingAudioStream(OggAudioStream::new, new ByteArrayInputStream(data)); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        });
    }
}
