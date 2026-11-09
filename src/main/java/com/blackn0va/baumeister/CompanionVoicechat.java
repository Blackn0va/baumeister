package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.VoiceCapture;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Der Begleiter im Spiel hören und sprechen lassen – über Simple Voice Chat (Wunsch 2026-11-04). Ein Vanilla-Server
 * kann einem Client keinen erzeugten Ton schicken;
 * Simple Voice Chat (Mod im Client, Plugin auf dem Server) kann es.
 *
 * Hören: Sprachpakete des Besitzers eines Begleiters → Opus dekodieren (48 kHz) → VoiceCapture sammelt bis zur Pause →
 * 16 kHz an den Baumeister-Host (/begleiter/hoeren, Whisper auf dem GPU-PC) → Text → CompanionService.heardInGame.
 * Mitgeschnitten werden Besitzer eines Begleiters und Mitspieler in Hörweite eines Begleiters (CompanionService.canHear,
 * seit 2026-11-04 – vorher nur Besitzer); Mitspieler erkennt der Host nur mit „Andere Spieler verstehen“. Was jemand zu
 * anderen sagt, wirkt nur, wenn es den Begleiter anspricht oder ein Gespräch mit ihm läuft (CompanionService).
 *
 * Sprechen: Antworttext → Host (/begleiter/stimme, Piper) → 48 kHz → EntityAudioChannel an der Mannequin des
 * Begleiters – alle Spieler in der Nähe mit Simple Voice Chat hören ihn aus seiner Richtung.
 *
 * Nur geladen, wenn das Plugin „voicechat“ läuft (BaumeisterPlugin prüft vorher): Ohne es stünde keine dieser Klassen
 * im Klassenpfad, und der Begleiter bleibt beim Chat und beim Vorlesen in der App.
 *
 * Threads: MicrophonePacketEvent im Netzwerk-Thread von Simple Voice Chat; poll im Bukkit-Takt (asynchron); Antworten
 * über runTask in den Hauptthread. Fehler: fail-open – fällt Simple Voice Chat oder der Host aus, bleibt der Chat.
 */
final class CompanionVoicechat implements VoicechatPlugin, CompanionInGameVoice {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Hörweite des Begleiters im Spiel (Blöcke) – etwas mehr als normale Sprache, er soll gut zu verstehen sein. */
    static final float DISTANCE = 24f;

    private final BaumeisterPlugin plugin;
    private final VoiceCapture capture = new VoiceCapture();
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private volatile VoicechatServerApi api;
    private BukkitTask poller;

    private CompanionVoicechat(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Nur aufrufen, wenn das Plugin „voicechat“ geladen ist. null, wenn der Dienst fehlt. */
    static CompanionVoicechat install(BaumeisterPlugin plugin) {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            LOG.info("begleiter | voicechat_fehlt | grund=kein_dienst – Begleiter bleibt beim Chat");
            return null;
        }
        CompanionVoicechat hook = new CompanionVoicechat(plugin);
        service.registerPlugin(hook);
        hook.poller = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, hook::poll, 20L, 4L);
        LOG.info("begleiter | voicechat | angemeldet – Sprechen und Hören im Spiel über Simple Voice Chat");
        return hook;
    }

    @Override
    public void shutdown() {
        if (poller != null) {
            poller.cancel();
        }
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
        streams.values().forEach(Stream::cut);
        streams.clear();
    }

    @Override
    public String getPluginId() {
        return "baumeister";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        // Die Server-API kommt mit VoicechatServerStartedEvent.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> {
            api = e.getVoicechat();
            registerCategory(e.getVoicechat());
        });
        registration.registerEvent(MicrophonePacketEvent.class, this::microphone);
    }

    /** Lautstärke-Kategorie: höchstens 16 Zeichen aus a–z und _ (Vorgabe von Simple Voice Chat). */
    static final String CATEGORY = "baumeister_vera";

    /**
     * Eigener Regler „Begleiterin“ in den Voice-Chat-Einstellungen jedes Spielers (registerVolumeCategory, belegt mit
     * javap an voicechat-bukkit 2.6.27 und der API 2.6.0, 2026-11-06). Vorher lief Vera im allgemeinen Kanal mit –
     * wer sie leiser wollte, musste alle Spieler leiser drehen. Fehlt die Methode (ältere Fassung), bleibt es dabei.
     */
    private void registerCategory(VoicechatServerApi server) {
        try {
            server.registerVolumeCategory(server.volumeCategoryBuilder()
                    .setId(CATEGORY)
                    .setName("Begleiterin")
                    .setDescription("Stimme von Vera, der Begleiterin aus dem Baumeister")
                    .setIcon(sparkIcon())
                    .build());
        } catch (RuntimeException | LinkageError e) {
            // Bewusst fail-open: nur der eigene Regler fehlt, Vera spricht weiter im allgemeinen Kanal.
            LOG.info(() -> "begleiter | voicechat_kategorie_fehlt | grund=" + e.getClass().getSimpleName());
        }
    }

    /** 16×16 ARGB, goldener Funke. Symmetrisch – Zeilen/Spalten egal. */
    static int[][] sparkIcon() {
        int[][] px = new int[16][16];
        int gold = 0xFFFFCD3C;
        int core = 0xFFFFFFE6;
        for (int i = 1; i <= 6; i++) {
            int a = i <= 4 ? gold : 0xA0FFCD3C;
            px[7 - i][7] = a;
            px[8 + i][8] = a;
            px[7][7 - i] = a;
            px[8][8 + i] = a;
            px[8 + i][7] = a;
            px[7 - i][8] = a;
            px[7][8 + i] = a;
            px[8][7 - i] = a;
        }
        for (int x = 6; x <= 9; x++) {
            for (int y = 6; y <= 9; y++) {
                px[x][y] = gold;
            }
        }
        px[7][7] = core;
        px[8][8] = core;
        px[7][8] = core;
        px[8][7] = core;
        return px;
    }

    /** Netzwerk-Thread: nur Besitzer eines Begleiters; dekodieren und sammeln, nichts Schweres. */
    private void microphone(MicrophonePacketEvent e) {
        VoicechatConnection sender = e.getSenderConnection();
        VoicechatServerApi server = api;
        if (sender == null || server == null) {
            return;
        }
        UUID id = sender.getPlayer().getUuid();
        CompanionService companion = plugin.companion();
        if (companion == null || !companion.canHear(id)) {
            return;
        }
        byte[] opus = e.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            return;
        }
        OpusDecoder decoder = decoders.computeIfAbsent(id, k -> server.createDecoder());
        short[] pcm = decoder.decode(opus);
        long now = System.currentTimeMillis();
        if (pcm != null && pcm.length > 0) {
            capture.feed(id, pcm, now);
        }
        UUID speaker = talkingTo.get(id);
        if (speaker != null && companion.bargeIn() && bargeIn.feed(id, now)) {
            interrupt(id, speaker);
        }
    }

    /** Wem Vera gerade spricht: Spieler → Entity des Begleiters. Netzwerk- und Hauptthread. */
    private final Map<UUID, UUID> talkingTo = new ConcurrentHashMap<>();
    private final com.blackn0va.baumeister.companion.BargeIn bargeIn =
            new com.blackn0va.baumeister.companion.BargeIn();

    /**
     * Ins Wort gefallen: laufenden Satz anhalten, Wartende verwerfen, CompanionService meldet es der Kette (die
     * noch nicht erzeugten Sätze entfallen dort). Vorher redete Vera jeden Satz zu Ende, auch wenn man längst etwas
     * Neues fragte.
     */
    private void interrupt(UUID player, UUID speaker) {
        talkingTo.remove(player, speaker);
        Stream st = streams.get(speaker);
        if (st != null) {
            st.cut();
        }
        LOG.info(() -> "begleiter | unterbrochen | spieler=" + player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.companion() != null) {
                plugin.companion().voiceInterrupted(player);
            }
        });
    }

    /** Bukkit-Takt (asynchron): fertige Sätze an den Host, Text zurück in den Hauptthread. */
    private void poll() {
        CompanionService companion = plugin.companion();
        CompanionVoiceLink link = companion == null ? null : companion.voiceLink();
        for (VoiceCapture.Done d : capture.poll(System.currentTimeMillis())) {
            OpusDecoder decoder = decoders.get(d.player());
            if (decoder != null) {
                decoder.resetState();
            }
            Player p = Bukkit.getPlayer(d.player());
            if (p == null) {
                continue;
            }
            if (link == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.companion() != null) {
                        plugin.companion().cannotHear(p);
                    }
                });
                continue;
            }
            String name = p.getName();
            // Leer = App aus oder Erkennung abgeschaltet: Vera sagt es (DeafNotice), statt den Satz still zu verwerfen.
            link.hear(name, d.pcm16kLe()).thenAccept(text -> Bukkit.getScheduler().runTask(plugin, () -> {
                Player now = Bukkit.getPlayer(d.player());
                if (now == null || plugin.companion() == null) {
                    return;
                }
                if (text.isEmpty()) {
                    plugin.companion().cannotHear(now);
                    return;
                }
                plugin.companion().canHearAgain(now);
                if (!text.get().isBlank()) {
                    plugin.companion().heardInGame(now, text.get());
                }
            }));
        }
    }

    /** Spieler weg oder Begleiter weggeschickt: Angefangenes verwerfen, Decoder schließen. */
    @Override
    public void forget(UUID player) {
        capture.drop(player);
        OpusDecoder d = decoders.remove(player);
        if (d != null) {
            d.close();
        }
    }

    /** Hat der Spieler Simple Voice Chat verbunden (und nicht abgeschaltet)? Dann spricht der Begleiter im Spiel. */
    @Override
    public boolean connected(UUID player) {
        VoicechatServerApi server = api;
        if (server == null) {
            return false;
        }
        VoicechatConnection c = server.getConnectionOf(player);
        return c != null && c.isConnected() && !c.isDisabled();
    }

    /**
     * Ton an der Entity abspielen. false, wenn Simple Voice Chat noch nicht bereit ist.
     *
     * Hauptthread. Spricht der Begleiter noch, kommt der Satz in die Warteschlange seines Stroms und läuft nahtlos
     * danach – vorher bekam jeder Satz einen eigenen Player, und der nächste startete erst über den Haupttakt
     * (≥ 50 ms Lücke, beim Client leerer Puffer, 100 ms Stille; „die Stimme laggt“, 2026-11-07, AudioPacing). Höchstens
     * MAX_QUEUED Sätze warten; ältere fallen weg, damit Vera nicht minutenlang Überholtes nachredet.
     */
    @Override
    public boolean play(Entity speaker, UUID addressee, short[] pcm48k) {
        if (addressee != null) {
            talkingTo.put(addressee, speaker.getUniqueId());
            bargeIn.reset(addressee);
        }
        return play(speaker, pcm48k);
    }

    @Override
    public boolean play(Entity speaker, short[] pcm48k) {
        VoicechatServerApi server = api;
        if (server == null || pcm48k.length == 0) {
            return false;
        }
        UUID id = speaker.getUniqueId();
        Stream st = streams.get(id);
        if (st == null || st.channel.isClosed()) {
            EntityAudioChannel channel = server.createEntityAudioChannel(UUID.randomUUID(), server.fromEntity(speaker));
            if (channel == null) {
                return false;
            }
            channel.setDistance(DISTANCE);
            channel.setCategory(CATEGORY);
            st = new Stream(id, channel, server);
            streams.put(id, st);
        }
        st.add(pcm48k);
        return true;
    }

    /** Seit Satz-für-Satz (SpeechChunks, bis 4 Stücke je Antwort) mehr als vorher 3 – sonst fiele der Anfang weg. */
    static final int MAX_QUEUED = 6;
    /** So lange ohne neuen Satz, dann endet der Sende-Faden (Endmarke an die Clients). */
    static final long IDLE_NS = 1_000_000_000L;
    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();

    /**
     * Ein Sprecher-Strom: Warteschlange der Sätze, ein Sende-Faden mit AudioPacing (Vorlauf, fester Plan, Nachholen),
     * Opus-Kodierung im Faden. Der Faden endet nach IDLE_NS ohne Ton und schickt dann die Endmarke (flush) – wie der
     * Player von Simple Voice Chat, nur einmal je Gespräch statt je Satz.
     *
     * Threads: add/cut aus dem Hauptthread bzw. Netzwerk-Thread, Senden im eigenen Faden; Warteschlange unter this.
     */
    private final class Stream implements Runnable {
        final UUID speaker;
        final EntityAudioChannel channel;
        final VoicechatServerApi server;
        private final java.util.ArrayDeque<short[]> sentences = new java.util.ArrayDeque<>();
        private int epoch;
        private Thread thread;

        Stream(UUID speaker, EntityAudioChannel channel, VoicechatServerApi server) {
            this.speaker = speaker;
            this.channel = channel;
            this.server = server;
        }

        synchronized void add(short[] pcm) {
            sentences.addLast(pcm);
            while (sentences.size() > MAX_QUEUED) {
                sentences.removeFirst();
            }
            if (thread == null || !thread.isAlive()) {
                thread = new Thread(this, "baumeister-stimme-" + speaker.toString().substring(0, 8));
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, e) -> LOG.warning(() -> "begleiter | stimme_fehler | grund="
                        + e));
                thread.start();
            }
            notifyAll();
        }

        /** Ins Wort gefallen: Wartendes weg, laufender Satz bricht ab (beim Client spielt höchstens das Polster aus). */
        synchronized void cut() {
            sentences.clear();
            epoch++;
            notifyAll();
        }

        private synchronized short[] next(long idleUntil) throws InterruptedException {
            while (sentences.isEmpty()) {
                long left = idleUntil - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                wait(Math.max(1, left / 1_000_000));
            }
            return sentences.pollFirst();
        }

        private synchronized int epoch() {
            return epoch;
        }

        private synchronized boolean idle() {
            return sentences.isEmpty();
        }

        @Override
        public void run() {
            OpusEncoder encoder = server.createEncoder();
            com.blackn0va.baumeister.companion.AudioPacing pace = new com.blackn0va.baumeister.companion.AudioPacing();
            // Vorwärmen, bevor der Zeitplan beginnt: Der erste Satz nach dem Start lag im Spiel 240 ms hinter dem Plan
            // (Log dev01 2026-11-07 17:58, gleich nach dem Beitreten – kalter Kodierer und JIT auf dem Celeron); das
            // überstieg das Polster beim Client, die Begrüßung „wackelte“.
            short[] silence = new short[FRAME];
            for (int w = 0; w < 5; w++) {
                encoder.encode(silence);
            }
            encoder.resetState();
            try {
                while (true) {
                    short[] pcm = next(System.nanoTime() + IDLE_NS);
                    if (pcm == null) {
                        return;
                    }
                    int myEpoch = epoch();
                    int frames = (pcm.length + FRAME - 1) / FRAME;
                    long worstLate = 0;
                    int late = 0;
                    boolean fresh = false;
                    int i = 0;
                    for (; i < frames && myEpoch == epoch(); i++) {
                        short[] frame = new short[FRAME];
                        System.arraycopy(pcm, i * FRAME, frame, 0, Math.min(FRAME, pcm.length - i * FRAME));
                        long due = pace.due(System.nanoTime());
                        fresh |= pace.restarted();
                        long wait = due - System.nanoTime();
                        if (wait > 0) {
                            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        }
                        channel.send(encoder.encode(frame));
                        // Verspätung erst ab Strombeginn: Vorlauf-Rahmen sind absichtlich „überfällig“ (bis 200 ms) –
                        // vorher zählten sie mit, und jeder Satz meldete 200 ms (Log 2026-11-07 18:01).
                        long lateNs = System.nanoTime() - Math.max(due, pace.started());
                        worstLate = Math.max(worstLate, lateNs);
                        if (lateNs > 40_000_000L) {
                            late++;
                        }
                        pace.sent();
                    }
                    if (myEpoch != epoch()) {
                        pace.stop();
                        encoder.resetState();
                    }
                    if (idle()) {
                        // Letzter Rahmen raus und nichts wartet: Ab jetzt ist Sprechen des Spielers kein Ins-Wort-Fallen
                        // mehr (wie vorher beim Ende des Players).
                        talkingTo.values().removeIf(speaker::equals);
                    }
                    long worst = worstLate / 1_000_000L;
                    int lateCount = late;
                    int sentFrames = i;
                    boolean restarted = fresh;
                    // Messwert fürs Log: wie spät der Faden gegenüber dem Plan sendete (Speicherpausen). Liegt das über
                    // dem Polster (AudioPacing.MAX_BEHIND), hörte der Spieler trotzdem eine Lücke.
                    LOG.fine(() -> "begleiter | stimme_gesendet | rahmen=" + sentFrames + " neu_angefangen=" + restarted
                            + " spät_max_ms=" + worst + " spät_über_40ms=" + lateCount);
                    if (worst > 100) {
                        LOG.info(() -> "begleiter | stimme_verspätet | rahmen=" + sentFrames + " spät_max_ms=" + worst
                                + " spät_über_40ms=" + lateCount + " polster_ms=" + (CUSHION_MS));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                encoder.close();
                channel.flush();
                // Fertig gesprochen: Ab jetzt ist Sprechen des Spielers kein Ins-Wort-Fallen mehr.
                talkingTo.values().removeIf(speaker::equals);
            }
        }
    }

    /** Polster beim Client in ms (AudioPacing.MAX_BEHIND) – für die Logzeile. */
    private static final long CUSHION_MS =
            com.blackn0va.baumeister.companion.AudioPacing.MAX_BEHIND / 1_000_000L;
    /** Rahmen von Simple Voice Chat: 20 ms bei 48 kHz mono. */
    private static final int FRAME = 960;
}
