package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.CompanionChat;
import com.blackn0va.baumeister.companion.CompanionReply;
import com.blackn0va.baumeister.companion.CompanionRules;
import com.blackn0va.baumeister.companion.VeraRights.Ability;
import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.companion.MathExpr;
import com.blackn0va.baumeister.companion.RecipeText;
import com.blackn0va.baumeister.companion.Songs;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Der Begleiter (Wunsch 2026-11-04). Je Spieler höchstens einer: eine Mannequin mit Namen aus begleiter.name (Vorgabe
 * Vera),
 * die folgt, im Chat antwortet und Aktionen ausführt.
 *
 * Ablauf eines Satzes: Anrede erkennen (CompanionChat) → Steuerwort ohne LLM (komm, bleib, tschüss) oder Frage an das
 * LLM mit Grammatik „begleiter“ → Antwort sprechen (Chat, Text über dem Kopf, Vorlesen im Host) → Aktionen im
 * Hauptthread mit Rechteprüfung (CompanionRules, fail-closed). Gerechnet und gesucht wird hier, nicht im Modell.
 *
 * Nicht persistent wie die Helfer des Baumeisters: Nach einem Neustart räumt BaumeisterListener.onEntitiesLoad übrig
 * gebliebene Begleiter weg; der Spieler ruft ihn neu („Hey Vera“).
 *
 * Threads: alles im Hauptthread; nur die LLM-Antwort und die Blocksuche über ChunkSnapshots laufen außerhalb und
 * springen per runTask zurück. Fehler: wirft nicht in den Spielablauf – ein ausgefallenes LLM wird gesagt, nicht
 * geworfen.
 */
final class CompanionService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Gesprächsgedächtnis: so viele Nachrichten (Spieler und Begleiter abwechselnd) gehen mit an das LLM. */
    static final int HISTORY = 16;
    /** Im Gespräch hört der Begleiter nur, wer ihm so nah ist. */
    private static final double HEAR = 16;
    private static final double STEP = 0.45;

    /** Ein gerufener Begleiter. */
    static final String PERM_COMMANDS = "baumeister.begleiter.befehle";
    /**
     * So lange wartet Vera auf das Modell, dann gibt sie auf und ist wieder ansprechbar. Vorher galt die Grenze der
     * Baupläne (llm.timeout-sekunden = 900 s, dazu zwei Wiederholungen): Am 2026-11-04 hing „denkt nach …“ über
     * 2,5 Minuten ohne ein Wort, und jede weitere Frage bekam nur „Moment, ich überlege noch“ (Wunsch). Eine Antwort
     * braucht mit dem 14B unter 3 s; 45 s deckt das erste Laden nach einem Modellwechsel ab.
     */
    static final long ANSWER_DEADLINE_MS = 45_000;
    /** Danach sagt sie einmal, dass sie noch nachdenkt – Stille wirkt wie ein Absturz. */
    /** Größer sichert Vera nicht – so viel ändert auch vanilla-fill höchstens (commandModificationBlockLimit). */
    static final long UNDO_MAX_BLOCKS = 32_768;
    /** So lange gilt eine Rückfrage („Soll ich wirklich …?“). */
    private static final long CONFIRM_NANOS = 30_000_000_000L;
    /** Reparieren: Umkreis (Blöcke) und Zeitraum (Sekunden) – ein Haus samt Garten, Schäden der letzten drei Tage. */
    static final int REPAIR_RADIUS = 20;
    static final int REPAIR_SECONDS = 3 * 24 * 3600;
    /** Kisten-Verlauf: 30 Tage. */
    static final int CHEST_SECONDS = 30 * 24 * 3600;
    /** Nachtwache: Haus = Bett/Respawnpunkt; Wache, solange der Spieler höchstens so weit weg ist … */
    static final double WATCH_NEAR_HOME = 64;
    /** … und für Monster in diesem Umkreis ums Haus. */
    static final double WATCH_RADIUS = 24;

    private static final class Buddy {
        final UUID owner;
        UUID entity;
        boolean staying;
        boolean thinking;
        long lastReplyNanos = Long.MIN_VALUE / 2;
        /** Mit wem er zuletzt sprach – ein Gespräch ohne Namen gilt nur für diesen Spieler (auch Mitspieler). */
        UUID partner;
        /** Wer die offene Rückfrage ausgelöst hat; nur dessen „ja“ zählt. */
        UUID pendingBy;
        final Deque<JsonObject> history = new ArrayDeque<>();
        /** Befehl, der auf „ja“ wartet (CompanionRules.CommandDecision.CONFIRM), sonst null. */
        List<String> pendingCommands;
        long pendingNanos;
        /**
         * Kette der Stimmen im Spiel: Jeder Satz wird erst erzeugt, wenn der vorige fertig ist – sonst überholt ein
         * kurzer Satz („Im Abenteuer …“) einen langen, und beide laufen gleichzeitig (Vorfall 2026-11-04 22:24, „ich
         * höre zwei Stimmen“). Das Nacheinander-Abspielen macht CompanionVoicechat.play.
         */
        CompletableFuture<Void> voiceChain = CompletableFuture.completedFuture(null);
        /** Läuft gerade ein Redstone-Experiment (RedstoneLab)? */
        boolean lab;
        /** Steigt, wenn der Spieler ihr ins Wort fällt – ältere Sätze der Kette werden dann nicht mehr gesprochen. */
        volatile int voiceEpoch;
        /** Laufen von Tick zu Tick (Festhängen, Sprung). */
        final CompanionWalk.State walk = new CompanionWalk.State();
        /** Weg zum Ziel (Wegsuche, sonst Spur, sonst geradeaus) – CompanionFollow.Planner. */
        final com.blackn0va.baumeister.companion.CompanionFollow.Planner planner =
                new com.blackn0va.baumeister.companion.CompanionFollow.Planner();
        /** Läuft sie gerade hinterher (Hysterese, CompanionFollow.shouldWalk)? */
        boolean following;
        /** Abstand zum Besitzer im letzten Takt, Ticks in Folge mit wachsendem Abstand, letzte Meldung (Diagnose). */
        double lastLag;
        int awayTicks;
        long awayLogged;
        /** Standpunkte des Besitzers (CompanionFollow) und ihre Welt – in Höhlen läuft sie dessen Weg nach. */
        final com.blackn0va.baumeister.companion.CompanionFollow.Trail trail =
                new com.blackn0va.baumeister.companion.CompanionFollow.Trail();
        UUID trailWorld;
        /** Ort des Besitzers im letzten Takt (airStep: Fluggeschwindigkeit). */
        Location lastOwnerPos;
        Location prevOwnerPos;
        /** Auf Zuruf benutzen (CompanionUse): Block, Auftrag, Frist in Ticks – bis dahin läuft sie hin. */
        org.bukkit.block.Block useAt;
        com.blackn0va.baumeister.companion.CompanionUse.Use use;
        long useUntil;
        /** Ticks, die sie schon neben dem Spieler steht (Langeweile, Ausprobieren), und wann sie wieder darf. */
        int idleTicks;
        long nextCuriosity;
        boolean wasNight;
        /** Von ihr geöffnete Türen auf dem Weg: Block → Tick des Öffnens (wieder zumachen). */
        final Map<org.bukkit.block.Block, Long> openedDoors = new java.util.HashMap<>();
        /** Kurzer Blick auf ein Lebewesen in der Nähe (lookIdle). */
        UUID glanceAt;
        long glanceUntil;
        long nextGlance;
        /** Witze dieses Begleiters (gemischter Stapel, keine Wiederholung bis alle dran waren). */
        final com.blackn0va.baumeister.companion.Jokes jokes = new com.blackn0va.baumeister.companion.Jokes(
                new java.util.Random());
        /** Blöcke vor dem letzten fill/setblock/clone über Vera – für „mach das rückgängig“; sonst null. */
        List<BlockState> undo;
        UUID undoWorld;
        /** „Reparier mein Haus“ wartet auf „ja“: Mittelpunkt der Reparatur, sonst null (Rückfrage wie pendingCommands). */
        Location pendingRepair;
        /** Letzte Warnung der Nachtwache (ms). */
        long lastWatchWarn;
        /** „Ecke eins / Ecke zwei“ fürs Merken eines Bauwerks, sonst null. */
        Location cornerA;
        Location cornerB;

        Buddy(UUID owner) {
            this.owner = owner;
        }
    }

    private final BaumeisterPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID, Buddy> buddies = new HashMap<>();
    /** Besitzer gerufener Begleiter – auch aus dem Netzwerk-Thread von Simple Voice Chat lesbar (hasCompanion). */
    private final java.util.Set<UUID> owners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Wessen Sprache Simple Voice Chat mitschneidet: Besitzer und alle in Hörweite eines Begleiters (CompanionRouting).
     * Im Takt neu berechnet und als Ganzes ersetzt, damit der Netzwerk-Thread nie einen halben Stand liest.
     */
    private volatile java.util.Set<UUID> listeners = java.util.Set.of();
    /** Namen der Spieler mit verbundenem Simple Voice Chat – für die App (CompanionVoiceLink.heardUri), im Takt. */
    private volatile java.util.Set<String> voiceChatNames = java.util.Set.of();
    private int listenerTicks;
    /** Stimme im Spiel (Simple Voice Chat), null ohne das Plugin „voicechat“. */
    private CompanionInGameVoice inGame;
    private volatile CompanionSettings settings;
    private LlmClient llm;
    private String prompt;
    private CompanionVoiceLink voice;
    /** Stimme auf dem Server (LocalVoice, sprache.*); null = nur die App. */
    private LocalVoice local;
    private BukkitTask follow;
    /** Abenteuer in der Farmwelt (Story, normal/Hardcore) – CompanionAdventure. */
    private final CompanionAdventure adventure;

    /** Was Vera für wen darf (Admin-Buch, vera-rechte.yml). */
    private final CompanionRights rights;

    CompanionRights rights() {
        return rights;
    }

    CompanionService(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "begleiter");
        this.rights = new CompanionRights(plugin, plugin.getDataFolder());
        this.adventure = new CompanionAdventure(plugin, new CompanionAdventure.Host() {
            @Override
            public void say(Player p, String text) {
                Buddy b = buddies.get(p.getUniqueId());
                if (b == null) {
                    CompanionService.this.summon(p, false);
                    b = buddies.get(p.getUniqueId());
                }
                if (b != null) {
                    CompanionService.this.say(b, p, text);
                }
            }

            @Override
            public Mannequin companion(Player p) {
                Buddy b = buddies.get(p.getUniqueId());
                return b == null ? null : entity(b);
            }

            @Override
            public void summon(Player p) {
                if (buddies.get(p.getUniqueId()) == null || entity(buddies.get(p.getUniqueId())) == null) {
                    CompanionService.this.summon(p, false);
                }
            }

            @Override
            public void trail(Player p, Location target) {
                CompanionService.this.trail(p, target);
            }
        });
    }

    /** Veras Farbe – der Akzent von StreamWizard (#8b5cf6). */
    static final org.bukkit.Color VIOLET = org.bukkit.Color.fromRGB(0x8b, 0x5c, 0xf6);

    /**
     * Veras Outfit (Wunsch 2026-11-04): Elytren auf dem Rücken, violette Lederhose und
     * Stiefel mit Amethyst-Verzierung „Flow“, eine Laterne in der linken Hand. Kein Helm – das Gesicht bleibt frei. In
     * der rechten Hand trägt sie im Abenteuer das Schwert (CompanionAdventure.arm). Nur Aussehen: Sie ist unverwundbar,
     * die Teile fallen nie (nicht persistent, wird entfernt statt getötet).
     */
    static void outfit(Mannequin m) {
        org.bukkit.inventory.EntityEquipment eq = m.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setChestplate(new ItemStack(Material.ELYTRA));
        eq.setLeggings(violet(Material.LEATHER_LEGGINGS));
        eq.setBoots(violet(Material.LEATHER_BOOTS));
        eq.setItemInOffHand(new ItemStack(Material.LANTERN));
    }

    private static ItemStack violet(Material leather) {
        ItemStack it = new ItemStack(leather);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
            meta.setColor(VIOLET);
            if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armor) {
                armor.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(
                        org.bukkit.inventory.meta.trim.TrimMaterial.AMETHYST,
                        org.bukkit.inventory.meta.trim.TrimPattern.FLOW));
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Läuft für p ein Abenteuer in der Farmwelt? Dann baut der Baumeister nicht für ihn (BuildService.request). */
    boolean adventureFair(Player p) {
        return adventure.fair(p);
    }

    /** Tod eines Spielers (BaumeisterListener) – fürs Abenteuer. */
    void died(Player p) {
        adventure.died(p);
        if (!adventure.running(p)) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.DEATH, 0);
        }
    }

    /** Enderdrache besiegt (BaumeisterListener) – letzte Etappe für alle im Abenteuer in dieser Welt. */
    void dragonKilled(World w) {
        adventure.dragonKilled(w);
    }

    /** /bm begleiter abenteuer … */
    void adventureCommand(Player p, String what) {
        switch (what) {
            case "hardcore" -> adventure.start(p, com.blackn0va.baumeister.companion.Adventure.Mode.HARDCORE);
            case "annehmen", "ja" -> adventure.accept(p);
            case "aus", "ende", "stop" -> {
                if (!adventure.running(p)) {
                    Texts.info(p, "Es läuft gerade kein Abenteuer.");
                } else {
                    adventure.stop(p);
                }
            }
            case "stand" -> {
                if (!adventure.running(p)) {
                    Texts.info(p, "Es läuft gerade kein Abenteuer. Starten: /bm begleiter abenteuer [hardcore]");
                } else {
                    adventure.status(p);
                }
            }
            default -> adventure.start(p, com.blackn0va.baumeister.companion.Adventure.Mode.NORMAL);
        }
    }

    /** /bm begleiter abenteuer team &lt;spieler&gt;. */
    void adventureInvite(Player p, String name) {
        adventure.invite(p, name);
    }

    /** Einstellungen (neu) übernehmen – beim Start und nach /bm neuladen. */
    void configure(CompanionSettings s, LlmClient client, String systemPrompt, CompanionVoiceLink link) {
        this.settings = s;
        this.llm = client;
        adventure.configure(s.adventureWorlds(), s.adventureFight());
        // Getestete Maschinen des Baumeisters (plan/Machines) – damit Vera weiß, was „bau mir …“ sicher kann, statt
        // Redstone frei zu erfinden.
        String machines = String.join(", ", new com.blackn0va.baumeister.plan.Machines().machines().stream()
                .map(m -> m.module().name()).sorted().toList());
        this.prompt = systemPrompt.replace("{name}", s.name()).replace("{maschinen}", machines);
        if (voice != null) {
            voice.stop();
        }
        this.voice = link;
        if (voice != null && s.enabled()) {
            voice.inGame(() -> voiceChatNames);
            voice.listen((player, text) -> Bukkit.getScheduler().runTask(plugin, () -> heard(player, text)));
        }
        for (Buddy b : buddies.values()) {
            Mannequin m = entity(b);
            if (m != null) {
                m.setCustomName(s.name());
            }
        }
    }

    /** Stimme im Spiel setzen (BaumeisterPlugin, nur mit Simple Voice Chat). */
    void inGameVoice(CompanionInGameVoice voice) {
        this.inGame = voice;
    }

    /** Protokoll (CoreProtect), null ohne CoreProtect – dann sagt Vera, dass sie das nicht nachsehen kann. */
    private BlockHistory history;

    void blockHistory(BlockHistory h) {
        this.history = h;
    }

    /**
     * Thread-sicher: Soll Simple Voice Chat diesen Spieler mitschneiden? Besitzer eines Begleiters oder Mitspieler in
     * Hörweite eines Begleiters. Vorher nur Besitzer – Mitspieler neben Vera wurden nie gehört.
     */
    boolean canHear(UUID player) {
        return owners.contains(player) || listeners.contains(player);
    }

    /** Begleiter, der dem Spieler antwortet: sein eigener, sonst der nächste fremde in Hörweite (CompanionRouting). */
    private Buddy route(Player p) {
        return com.blackn0va.baumeister.companion.CompanionRouting.pick(speaker(p), spots(), HEAR).map(buddies::get).orElse(null);
    }

    private List<com.blackn0va.baumeister.companion.CompanionRouting.Spot> spots() {
        List<com.blackn0va.baumeister.companion.CompanionRouting.Spot> spots = new ArrayList<>();
        for (Buddy b : buddies.values()) {
            Mannequin m = entity(b);
            if (m != null) {
                Location l = m.getLocation();
                spots.add(new com.blackn0va.baumeister.companion.CompanionRouting.Spot(b.owner, l.getWorld().getUID(), l.getX(), l.getY(), l.getZ()));
            }
        }
        return spots;
    }

    private static com.blackn0va.baumeister.companion.CompanionRouting.Speaker speaker(Player p) {
        Location l = p.getLocation();
        return new com.blackn0va.baumeister.companion.CompanionRouting.Speaker(p.getUniqueId(), l.getWorld().getUID(), l.getX(), l.getY(), l.getZ());
    }

    /** Name des Besitzers für die Zeile über dem Kopf („Begleiter von …“). */
    private static String ownerName(Buddy b) {
        Player o = Bukkit.getPlayer(b.owner);
        return o != null ? o.getName() : "?";
    }

    CompanionVoiceLink voiceLink() {
        return voice;
    }

    void start() {
        loadWaypoints();
        adventure.load();
        loadComeBack();
        // Wer schon online ist (/reload, oder Join vor dem Start des Plugins), bekommt Vera wie beim Beitreten.
        for (Player online : Bukkit.getOnlinePlayers()) {
            join(online);
        }
        follow = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    void shutdown() {
        if (follow != null) {
            follow.cancel();
        }
        saveComeBack();
        // Boss-Leisten des Abenteuers gehören dem Server: ohne Abbau blieben sie nach /reload bis zum Relog stehen.
        for (Player online : Bukkit.getOnlinePlayers()) {
            adventure.quit(online);
        }
        if (voice != null) {
            voice.stop();
        }
        for (Buddy b : buddies.values()) {
            Mannequin m = entity(b);
            if (m != null) {
                m.remove();
            }
        }
        buddies.clear();
        owners.clear();
        if (inGame != null) {
            inGame.shutdown();
        }
    }

    String name() {
        return settings.name();
    }

    boolean isCompanion(Entity e) {
        return e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    /** Ein Begleiter, der zu keinem aktuell Gerufenen gehört (Absturz, Neustart) – weg damit. */
    boolean isStale(Entity e) {
        if (!isCompanion(e)) {
            return false;
        }
        for (Buddy b : buddies.values()) {
            if (e.getUniqueId().equals(b.entity)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- rufen und wegschicken

    /** Holt den Begleiter des Spielers zu ihm (spawnt ihn bei Bedarf). */
    void summon(Player p) {
        summon(p, true);
    }

    /**
     * greet=false, wenn der Ruf schon eine Bitte enthält („Vera, reparier die Anlage“): Vorher sagte sie erst „Hallo …
     * Ich bin Vera. Sag einfach Bescheid …“ und gleich danach die Antwort – doppelt gesprochen (Nutzer 2026-11-04).
     */
    void summon(Player p, boolean greet) {
        if (!settings.enabled()) {
            Texts.info(p, "Der Begleiter ist auf diesem Server abgeschaltet (begleiter.an).");
            return;
        }
        Buddy b = buddies.computeIfAbsent(p.getUniqueId(), Buddy::new);
        owners.add(p.getUniqueId());
        if (comeBack.summoned(p.getUniqueId())) {
            saveComeBack();
        }
        b.staying = false;
        Mannequin m = entity(b);
        Location at = beside(p);
        if (m == null) {
            m = at.getWorld().spawn(at, Mannequin.class, e -> {
                e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                e.setCustomName(settings.name());
                e.setCustomNameVisible(true);
                MannequinCompat.description(e, "Begleiter von " + p.getName());
                MannequinCompat.showDescription(e);
                e.setInvulnerable(true);
                e.setGravity(false);
                e.setSilent(true);
                e.setCollidable(false);
                e.setImmovable(true);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(false);
            });
            b.entity = m.getUniqueId();
            outfit(m);
            m.getWorld().spawnParticle(Particle.HEART, m.getLocation().add(0, 2.1, 0), 6, 0.4, 0.3, 0.4, 0);
            adventure.armIfRunning(p);
            LOG.info(() -> "begleiter | gerufen | spieler=" + p.getName() + " welt=" + at.getWorld().getName());
            if (greet) {
                say(b, p, "Hallo " + p.getName() + "! Ich bin " + settings.name() + ". Sag einfach Bescheid, wenn ich "
                        + "helfen kann.");
            }
        } else {
            m.teleport(BuilderNpc.facing(at, p.getEyeLocation()));
            if (greet) {
                say(b, p, "Bin da!");
            }
        }
    }

    void dismiss(Player p) {
        if (comeBack.dismissed(p.getUniqueId())) {
            saveComeBack();
        }
        Buddy b = buddies.remove(p.getUniqueId());
        forgetOwner(p.getUniqueId());
        if (b == null) {
            Texts.info(p, "Dein Begleiter ist gerade nicht da.");
            return;
        }
        Mannequin m = entity(b);
        // Ohne Vera läuft der Takt des Abenteuers nicht (tick nur mit Begleiter) – die Boss-Leiste stünde sonst still
        // und veraltet oben. Der Lauf selbst bleibt im Spielbuch und geht weiter, sobald Vera wieder da ist.
        adventure.quit(p);
        if (m != null) {
            Texts.say(settings.name(), m.getLocation(), p, adventure.running(p)
                    ? "Bis bald! Unser Abenteuer wartet – ruf mich, dann geht es weiter." : "Bis bald!");
            m.remove();
        }
        LOG.info(() -> "begleiter | weg | spieler=" + p.getName());
    }

    /**
     * Wer Vera einmal gerufen hat, bekommt sie bei jedem Beitreten zurück (join), bis „tschüss“ (ComeBackList, dort die
     * Vorfälle 2026-11-04 und 2026-11-05). Ohne Vera schneidet das Plugin niemanden mit (canHear, Datenschutz), während
     * die App-Taste wegen des Voice-Chats ruht – „Hey Vera“ hörte dann niemand.
     *
     * Gespeichert bei jeder Änderung (begleiter-wiederkommen.json), nicht nur beim Herunterfahren: Vorher ging die
     * Liste bei einem Absturz oder einem Neustart mit älterer Fassung verloren.
     */
    private com.blackn0va.baumeister.companion.ComeBackList comeBack = new com.blackn0va.baumeister.companion.ComeBackList();

    /** Hinweis „schreib Vera“ höchstens einmal je Spieler und Serverlauf. */
    private final java.util.Set<UUID> hinted = new java.util.HashSet<>();
    /** Spieler, deren „merk dir das Bauwerk“ gerade geprüft wird – höchstens eine Prüfung je Spieler (Befund 2). */
    private final java.util.Set<UUID> remembering = new java.util.HashSet<>();

    private java.nio.file.Path comeBackFile() {
        return plugin.getDataFolder().toPath().resolve("begleiter-wiederkommen.json");
    }

    /** Beim Start: wer beim letzten Herunterfahren Vera dabeihatte (oder noch nicht wiedergekommen war). */
    private void loadComeBack() {
        java.nio.file.Path f = comeBackFile();
        if (!java.nio.file.Files.exists(f)) {
            return;
        }
        try {
            comeBack = com.blackn0va.baumeister.companion.ComeBackList.fromJson(java.nio.file.Files.readString(f));
            LOG.info(() -> "begleiter | wiederkommen_geladen | anzahl=" + comeBack.size());
        } catch (java.io.IOException | RuntimeException e) {
            // Komfort, kein Schutz: bewusst fail-open – dann ruft man Vera eben selbst.
            LOG.log(java.util.logging.Level.WARNING, "begleiter | wiederkommen_unlesbar | datei=" + f, e);
        }
    }

    /**
     * Synchron im Hauptthread: wenige Byte, und beim Herunterfahren nimmt der Scheduler keine Aufgaben mehr an.
     * Besitzer gerufener Begleiter stehen schon über summoned auf der Liste.
     */
    private void saveComeBack() {
        java.nio.file.Path f = comeBackFile();
        try {
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".neu");
            java.nio.file.Files.writeString(tmp, comeBack.toJson());
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            LOG.log(java.util.logging.Level.WARNING, "begleiter | wiederkommen_schreiben_fehlgeschlagen | datei=" + f,
                    e);
        }
    }

    /** Spieler kommt auf den Server (BaumeisterListener): Vera zurückholen, wenn er sie hatte oder im Abenteuer ist. */
    void join(Player p) {
        rights.apply(p);
        if (settings == null || !settings.enabled() || !p.hasPermission("baumeister.begleiter")) {
            return;
        }
        boolean wants = comeBack.wantsCompanion(p.getUniqueId());
        if (!wants && !adventure.running(p)) {
            hintVoice(p);
            return;
        }
        // Zwei Sekunden warten: Welt und Voice-Chat des Spielers sind beim Join noch nicht fertig.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || buddies.containsKey(p.getUniqueId())) {
                return;
            }
            summon(p, false);
            Buddy b = buddies.get(p.getUniqueId());
            if (b != null) {
                LOG.info(() -> "begleiter | zurück | spieler=" + p.getName() + " abenteuer=" + adventure.running(p));
                say(b, p, adventure.running(p) ? "Da bin ich wieder! Weiter geht's mit unserem Abenteuer."
                        : "Da bin ich wieder!");
            }
        }, 40L);
    }

    /**
     * Wer im Voice-Chat ist, aber Vera nie gerufen hat, wird nicht mitgeschnitten (canHear) – „Hey Vera“ verhallt
     * dann ohne jede Rückmeldung. Deshalb einmal je Serverlauf ein Hinweis, wie man sie holt.
     */
    private void hintVoice(Player p) {
        if (inGame == null || hinted.contains(p.getUniqueId())) {
            return;
        }
        // Nach dem Join braucht Simple Voice Chat ein bis zwei Sekunden für die Verbindung (Log 17:27:35 → 17:27:37).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || buddies.containsKey(p.getUniqueId()) || !inGame.connected(p.getUniqueId())
                    || !hinted.add(p.getUniqueId())) {
                return;
            }
            LOG.info(() -> "begleiter | hinweis_rufen | spieler=" + p.getName());
            Texts.info(p, settings.name() + " hört dich im Voice-Chat erst, wenn sie bei dir ist. Schreib einmal „"
                    + settings.name() + "“ in den Chat – danach kommt sie bei jedem Beitreten von selbst.");
        }, 100L);
    }

    /** Spieler verlässt den Server: Begleiter geht mit (und kommt mit ihm zurück, join). */
    /**
     * Spielmodus gewechselt (BaumeisterListener.onGameMode, einen Tick danach). Das Abenteuer meldet Pause/Weiter selbst.
     * Sonst sagt Vera kurz, was jetzt gilt, und bekommt eine Notiz ins Gespräch: Vorher stand im Verlauf noch „im
     * Überlebensmodus darf ich dir nichts geben“, und das Modell blieb dabei, obwohl der Kontext längst „creative“ sagte
     * (Wunsch 2026-11-06).
     */
    void gameModeChanged(Player p) {
        boolean told = adventure.gameModeChanged(p);
        Buddy b = buddies.get(p.getUniqueId());
        if (b == null) {
            return;
        }
        GameMode gm = p.getGameMode();
        String mode = switch (gm) {
            case CREATIVE -> "Kreativmodus";
            case SURVIVAL -> "Überlebensmodus";
            case ADVENTURE -> "Abenteuermodus";
            case SPECTATOR -> "Zuschauermodus";
        };
        boolean give = gm == GameMode.CREATIVE || rights.allowed(p, Ability.GEBEN);
        remember(b, LlmClient.message("user", "[Hinweis] Ich bin jetzt im " + mode + ". Geben: "
                + (give ? "erlaubt" : "nicht erlaubt") + ". Was vorher dazu gesagt wurde, gilt nicht mehr."));
        JsonObject ack = new JsonObject();
        ack.addProperty("say", "Alles klar, " + mode + ".");
        ack.add("actions", new com.google.gson.JsonArray());
        remember(b, LlmClient.message("assistant", ack.toString()));
        LOG.info(() -> "begleiter | spielmodus | spieler=" + p.getName() + " modus=" + gm.name().toLowerCase(Locale.ROOT)
                + " abenteuer_meldung=" + told);
        if (told || entity(b) == null) {
            return;
        }
        say(b, p, switch (gm) {
            case CREATIVE -> "Kreativmodus! Jetzt darf ich dir alles geben, was du brauchst.";
            case SURVIVAL -> give ? "Überlebensmodus. Geben darf ich dir trotzdem – du hast das Recht dazu."
                    : "Überlebensmodus – ab jetzt sammelst du selbst, ich helfe dir mit Tipps.";
            case ADVENTURE -> "Abenteuermodus – ich bleibe an deiner Seite.";
            case SPECTATOR -> "Zuschauermodus – ich warte hier, bis du zurück bist.";
        });
    }

    void quit(Player p) {
        rights.forget(p.getUniqueId());
        adventure.quit(p);
        Buddy b = buddies.remove(p.getUniqueId());
        forgetOwner(p.getUniqueId());
        if (b != null) {
            Mannequin m = entity(b);
            if (m != null) {
                m.remove();
            }
        }
    }

    // ---------------------------------------------------------------- Chat und Sprache

    /**
     * Chatzeile (Hauptthread). true = für den Begleiter, der Baumeister soll sie nicht mehr sehen. Spricht die Zeile
     * den Baumeister an, gehört sie ihm, auch mitten im Gespräch mit dem Begleiter.
     */
    boolean onChat(Player p, String message) {
        if (settings == null || !settings.enabled() || !p.hasPermission("baumeister.begleiter")) {
            return false;
        }
        String rest = CompanionChat.addressed(settings.name(), message);
        Buddy b = route(p);
        if (rest == null) {
            if (b == null || !inConversation(b, p)
                    || BaumeisterListener.extractPrompt(plugin.npc().name(), message) != null) {
                return false;
            }
            rest = message.trim();
        }
        handle(p, rest, false);
        return true;
    }

    /** Erkannter Satz aus dem Host (Mikrofon). Gilt wie eine Anrede: Wer die Taste drückt, meint den Begleiter. */
    void heard(String playerName, String text) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null || settings == null || !settings.enabled() || !p.hasPermission("baumeister.begleiter")) {
            LOG.info(() -> "begleiter | gehört_verworfen | spieler=" + playerName + " grund=nicht_online_oder_kein_recht");
            return;
        }
        if (inGame != null && inGame.connected(p.getUniqueId())) {
            // Im Voice-Chat hört Vera über Simple Voice Chat; derselbe Satz kam sonst ein zweites Mal über das
            // App-Mikrofon (Nutzer 2026-11-04). Die App lässt ihre Taste dann ohnehin ruhen – das hier fängt die
            // Sekunden ab, bis sie es beim nächsten Abholen erfährt.
            LOG.info(() -> "begleiter | gehört_verworfen | spieler=" + playerName + " grund=voicechat_aktiv");
            return;
        }
        String rest = CompanionChat.addressed(settings.name(), text);
        p.sendMessage(org.bukkit.ChatColor.GRAY + "(gesprochen) " + Texts.clean(text, 200));
        handle(p, rest != null ? rest : text.trim(), true);
    }

    /**
     * Erkannter Satz aus dem Spiel (Simple Voice Chat). Anders als die Haltetaste der App hört Simple Voice Chat alles,
     * was der Spieler sagt – auch zu anderen Spielern. Deshalb wirkt ein Satz nur, wenn er den Begleiter anspricht
     * („Hey Vera, …“) oder ein Gespräch mit ihm läuft; alles andere fällt still weg. Wer das lockert, lässt den
     * Begleiter auf jedes Gespräch im Voice-Chat antworten.
     */
    private final com.blackn0va.baumeister.companion.DeafNotice deaf = new com.blackn0va.baumeister.companion.DeafNotice();

    /** Ein Satz aus Simple Voice Chat ging verloren (App aus): einmal sagen, warum, und wie es trotzdem geht. */
    void cannotHear(Player p) {
        Buddy b = buddies.get(p.getUniqueId());
        if (b == null || entity(b) == null || settings == null
                || !deaf.failed(p.getUniqueId(), System.currentTimeMillis())) {
            return;
        }
        LOG.info(() -> "begleiter | nicht_gehört | spieler=" + p.getName() + " grund=erkennung_nicht_erreichbar");
        say(b, p, "Ich kann dich gerade nicht hören – die Spracherkennung der Baumeister-App antwortet nicht. "
                + "Schreib mir solange im Chat, zum Beispiel „" + settings.name() + ", mach Tag“.");
    }

    void canHearAgain(Player p) {
        deaf.reset(p.getUniqueId());
    }

    void heardInGame(Player p, String text) {
        if (settings == null || !settings.enabled() || !p.hasPermission("baumeister.begleiter")) {
            return;
        }
        Buddy b = route(p);
        String rest = CompanionChat.inGame(settings.name(), text, b != null && inConversation(b, p));
        if (rest == null) {
            return;
        }
        p.sendMessage(org.bukkit.ChatColor.GRAY + "(gesprochen) " + Texts.clean(text, 200));
        handle(p, rest, true);
    }

    private void handle(Player p, String rest, boolean spoken) {
        Buddy b = route(p);
        if (b != null && !b.owner.equals(p.getUniqueId())) {
            Buddy guestOf = b;
            LOG.info(() -> "begleiter | mitspieler | spieler=" + p.getName() + " begleiter_von=" + ownerName(guestOf));
        }
        if (b == null || entity(b) == null) {
            // „Hey Vera“ ruft den Begleiter wie beim Vorbild; mit Frage dahinter antwortet er gleich danach – dann ohne
            // Begrüßung, die Antwort genügt.
            summon(p, rest.isEmpty());
            b = buddies.get(p.getUniqueId());
            if (b == null || rest.isEmpty()) {
                return;
            }
        }
        if (adventure.hasInvite(p) && Boolean.TRUE.equals(CompanionChat.confirm(rest))) {
            adventure.accept(p);
            return;
        }
        if (b.pendingRepair != null) {
            Location at = b.pendingRepair;
            boolean fresh = System.nanoTime() - b.pendingNanos <= CONFIRM_NANOS
                    && p.getUniqueId().equals(b.pendingBy);
            b.pendingRepair = null;
            Boolean yes = CompanionChat.confirm(rest);
            if (fresh && yes != null) {
                if (yes) {
                    runRepair(b, p, at);
                } else {
                    say(b, p, "Gut, dann bleibt es, wie es ist.");
                }
                return;
            }
        }
        if (b.pendingCommands != null) {
            // Rückfrage gilt nur für die nächste Zeile und 30 s; alles andere lässt sie verfallen – ein spätes „ja“ zu
            // etwas anderem darf keinen alten /stop auslösen. Gesprochenes „ja“ führt nie aus (Plan A4,
            // CompanionRules.pendingAnswer): Die Rückfrage bleibt dann stehen, bis „ja“ getippt kommt.
            List<String> pending = b.pendingCommands;
            boolean fresh = System.nanoTime() - b.pendingNanos <= CONFIRM_NANOS
                    && p.getUniqueId().equals(b.pendingBy);
            switch (CompanionRules.pendingAnswer(CompanionChat.confirm(rest), fresh, spoken)) {
                case RUN -> {
                    b.pendingCommands = null;
                    runCommands(b, p, pending);
                    return;
                }
                case CANCEL -> {
                    b.pendingCommands = null;
                    say(b, p, "Gut, dann lasse ich das.");
                    return;
                }
                case TYPE_IT -> {
                    LOG.info(() -> "begleiter | befehl_nicht_bestätigt | spieler=" + p.getName()
                            + " grund=gesprochen – wartet auf getipptes ja");
                    say(b, p, "Befehle bestätigst du bitte getippt – schreib „ja“ in den Chat.");
                    return;
                }
                case EXPIRED -> b.pendingCommands = null;
            }
        }
        tones.computeIfAbsent(p.getUniqueId(), k -> new com.blackn0va.baumeister.companion.CompanionCharacter.Tone())
                .heard(rest);
        com.blackn0va.baumeister.companion.CompanionCharacter.Kind charWish =
                com.blackn0va.baumeister.companion.CompanionCharacter.wish(rest);
        if (charWish != null) {
            setCharacter(p, charWish);
            return;
        }
        com.blackn0va.baumeister.companion.CompanionUse.Use use = com.blackn0va.baumeister.companion.CompanionUse
                .parse(rest);
        if (use != null) {
            use(b, p, use);
            return;
        }
        // Bildschirm mit bewegten Bildern (ScreenService) – auch die kurze Nachfrage „Bewegte Bilder.“ (Log 2026-11-07).
        com.blackn0va.baumeister.screen.ScreenRequest.Request screen =
                com.blackn0va.baumeister.screen.ScreenRequest.parse(rest);
        if (screen != null && plugin.screens() != null) {
            say(b, p, plugin.screens().build(p, screen));
            return;
        }
        CompanionChat.Command cmd = CompanionChat.command(rest);
        if (cmd != null) {
            command(b, p, cmd);
            return;
        }
        if (rest.isEmpty()) {
            say(b, p, "Ja? Was kann ich für dich tun?");
            return;
        }
        if (CompanionChat.askJoke(rest)) {
            say(b, p, b.jokes.next());
            Mannequin jm = entity(b);
            if (jm != null) {
                // Lachen sichtbar machen: Notenpartikel über dem Kopf (cartoonhaft, nur Optik).
                jm.getWorld().spawnParticle(Particle.NOTE, jm.getLocation().add(0, 2.2, 0), 5, 0.4, 0.2, 0.4, 1);
            }
            return;
        }
        if (CompanionChat.askDiary(rest)) {
            adventure.openDiary(p);
            return;
        }
        if (CompanionChat.askRanking(rest)) {
            say(b, p, adventure.rankingText(p));
            return;
        }
        if (CompanionChat.askDeath(rest)) {
            // Ohne Modell: Das LLM traf „goto tod“ nicht verlässlich; travel entscheidet Teleport oder Weg (Fair-Play).
            travel(b, p, "tod");
            return;
        }
        if (rucksack(b, p, rest)) {
            return;
        }
        if (CompanionChat.askHelp(rest) && plugin.help() != null) {
            say(b, p, "Hier ist das Hilfebuch – klick dich einfach durch!");
            plugin.help().open(p);
            return;
        }
        QuestService quests = plugin.quests();
        if (quests != null && CompanionChat.askQuestRanking(rest)) {
            say(b, p, quests.rankingText(p));
            return;
        }
        if (quests != null && CompanionChat.askQuests(rest)) {
            say(b, p, quests.sentence(p));
            return;
        }
        if (buildMemory(b, p, rest)) {
            return;
        }
        if (CompanionChat.askRedstoneLogic(rest)) {
            labRedstone(b, p);
            return;
        }
        if (CompanionChat.askRedstoneCheck(rest)) {
            // Ohne Modell: Das Plugin sieht Richtung und Strom jedes Bauteils (RedstoneReader), das Modell nur die
            // Beschreibung. Kein Redstone in der Nähe → normale Frage ans Modell („warum geht meine Farm nicht“).
            com.blackn0va.baumeister.redstone.RedstoneReader.Report r = RedstoneScan.aroundPlayer(p);
            if (!r.empty()) {
                if (CompanionChat.askRedstoneRepair(rest)) {
                    // Steht der Spieler in einem Bau des Baumeisters, kennt das Baubuch den Sollzustand – das ist die
                    // genauere Reparatur (fehlende oder ersetzte Blöcke), dieselbe wie „reparier das“.
                    if (plugin.service().describeBuildAt(p).isPresent() && p.hasPermission("baumeister.bauen")
                            && !adventure.fair(p)) {
                        say(b, p, plugin.service().repair(p));
                        return;
                    }
                    repairRedstone(b, p, r);
                    return;
                }
                boolean fixable = r.findings().stream().anyMatch(f -> f.fix() != null);
                String tail = fixable ? " Sag „reparier meine Schaltung“, dann baue ich das um." : "";
                String verdict = com.blackn0va.baumeister.redstone.RedstoneReader.verdict(r, RedstoneScan::compass,
                        CompanionReply.MAX_SAY - tail.length()) + tail;
                // Mit Antwort im Log: Am 2026-11-06 stand dort nur „auffällig=3“, welche drei, wusste nur der Chat.
                LOG.info(() -> "begleiter | redstone_prüfung | spieler=" + p.getName() + " schaltungen="
                        + r.circuits().size() + " bauteile=" + r.parts() + " auffällig=" + r.findings().size()
                        + " antwort=" + Texts.clean(verdict, 300));
                say(b, p, verdict);
                return;
            }
        }
        if (CompanionChat.askChest(rest)) {
            chestHistory(b, p);
            return;
        }
        if (CompanionChat.askRepair(rest)) {
            askRepair(b, p);
            return;
        }
        java.util.Optional<String> partner = CompanionChat.adventureInvite(rest);
        if (partner.isPresent()) {
            adventure.invite(p, partner.get());
            return;
        }
        CompanionChat.AdventureCommand story = CompanionChat.adventure(rest);
        if (story != null && adventure.handle(p, story)) {
            // Ohne Modell: Etappen und Wege rechnet das Plugin (CompanionAdventure), das LLM würde sie erfinden.
            LOG.info(() -> "begleiter | abenteuer | spieler=" + p.getName() + " satz=" + story.name()
                    .toLowerCase(Locale.ROOT));
            return;
        }
        java.util.Optional<String> order = CompanionChat.buildOrder(rest);
        if (order.isPresent() && adventure.fair(p)) {
            say(b, p, "Im Abenteuer baust du selbst – der Baumeister macht so lange Pause.");
            return;
        }
        if (order.isPresent() && p.hasPermission("baumeister.bauen")) {
            // Ohne Modell (CompanionChat.buildOrder): sofort, und auch wenn der Host gerade einen Plan rechnet.
            LOG.info(() -> "begleiter | bauauftrag_direkt | spieler=" + p.getName() + " text="
                    + Texts.clean(order.get(), 120));
            String answer = "Ich sag dem Baumeister Bescheid!";
            JsonObject act = new JsonObject();
            act.addProperty("op", "build");
            act.addProperty("text", order.get());
            com.google.gson.JsonArray acts = new com.google.gson.JsonArray();
            acts.add(act);
            JsonObject reply = new JsonObject();
            reply.addProperty("say", answer);
            reply.add("actions", acts);
            // Ins Gespräch wie eine Modellantwort, damit Folgefragen („mach ihn größer“) den Zusammenhang kennen.
            remember(b, LlmClient.message("user", rest));
            remember(b, LlmClient.message("assistant", reply.toString()));
            say(b, p, answer);
            plugin.service().requestFromPlayer(p, order.get());
            return;
        }
        java.util.Optional<CompanionChat.GiveOrder> craftWish = CompanionChat.craftOrder(rest);
        Material craftWanted = craftWish.flatMap(w -> com.blackn0va.baumeister.plan.ItemNames.resolveStrict(w.item(),
                BukkitBlockCatalog.ITEMS)).map(Material::matchMaterial).filter(m -> m.isItem() && !m.isAir())
                .orElse(null);
        if (craftWanted != null) {
            remember(b, LlmClient.message("user", rest));
            craft(b, p, craftWanted, craftWish.get().count(),
                    com.blackn0va.baumeister.companion.CompanionCraft.namesMaterial(craftWish.get().item()));
            return;
        }
        java.util.Optional<CompanionChat.GiveOrder> wish = CompanionChat.giveOrder(rest);
        Material wanted = wish.flatMap(w -> com.blackn0va.baumeister.plan.ItemNames.resolveStrict(w.item(),
                BukkitBlockCatalog.ITEMS)).map(Material::matchMaterial).filter(m -> m.isItem() && !m.isAir())
                .orElse(null);
        if (wanted != null) {
            // Ohne Modell (CompanionChat.giveOrder): nur bei sicherem Treffer im Register, Prüfung wie immer in give().
            int count = wish.get().count() > 0 ? wish.get().count() : wanted.getMaxStackSize() == 1 ? 1
                    : Math.min(16, wanted.getMaxStackSize());
            LOG.info(() -> "begleiter | geben_direkt | spieler=" + p.getName() + " item=" + wanted.getKey().getKey()
                    + " anzahl=" + count);
            String answer = "Bitte schön!";
            JsonObject act = new JsonObject();
            act.addProperty("op", "give");
            act.addProperty("item", wanted.getKey().getKey());
            act.addProperty("count", count);
            com.google.gson.JsonArray acts = new com.google.gson.JsonArray();
            acts.add(act);
            JsonObject reply = new JsonObject();
            reply.addProperty("say", answer);
            reply.add("actions", acts);
            remember(b, LlmClient.message("user", rest));
            remember(b, LlmClient.message("assistant", reply.toString()));
            if (give(b, p, wanted.getKey().getKey(), count)) {
                say(b, p, answer);
            }
            return;
        }
        if (CompanionChat.askFix(rest) && !adventure.fair(p)) {
            // Am Ort entscheiden (CompanionChat.askFix): Baubuch vor Redstone vor Hausschaden.
            if (plugin.service().describeBuildAt(p).isPresent() && p.hasPermission("baumeister.bauen")) {
                LOG.info(() -> "begleiter | reparatur_ohne_modell | spieler=" + p.getName() + " weg=baubuch");
                say(b, p, plugin.service().repair(p));
                return;
            }
            com.blackn0va.baumeister.redstone.RedstoneReader.Report near = RedstoneScan.aroundPlayer(p);
            if (!near.empty()) {
                LOG.info(() -> "begleiter | reparatur_ohne_modell | spieler=" + p.getName() + " weg=redstone");
                repairRedstone(b, p, near);
                return;
            }
            LOG.info(() -> "begleiter | reparatur_ohne_modell | spieler=" + p.getName() + " weg=schaden");
            askRepair(b, p);
            return;
        }
        if (CompanionChat.askHowTo(rest)) {
            java.util.Optional<String> manual = plugin.service().describeBuildAt(p);
            if (manual.isPresent()) {
                LOG.info(() -> "begleiter | bedienung_ohne_modell | spieler=" + p.getName());
                say(b, p, "Das ist " + manual.get());
                return;
            }
        }
        java.util.Optional<CompanionReply.Reply> intent = com.blackn0va.baumeister.companion.CompanionIntent.command(
                rest, new com.blackn0va.baumeister.companion.CompanionIntent.Known(p.getGameMode() == GameMode.CREATIVE));
        if (intent.isPresent()) {
            runIntent(b, p, rest, intent.get(), spoken);
            return;
        }
        ask(b, p, rest, spoken);
    }

    /**
     * Rucksack-Sätze ohne Modell (CompanionChat.rucksack*): Das LLM kennt den Inhalt nicht und würde ihn erfinden.
     * Herausnehmen ist auch im Abenteuer fair – es sind die eigenen Sachen. true = erledigt.
     */
    private boolean rucksack(Buddy b, Player p, String rest) {
        RucksackService rs = plugin.rucksack();
        if (rs == null) {
            return false;
        }
        if (CompanionChat.rucksackOpen(rest)) {
            rs.open(p);
            return true;
        }
        java.util.Optional<String> search = CompanionChat.rucksackSearch(rest);
        if (search.isPresent()) {
            String answer = rs.search(p, search.get());
            if (answer != null) {
                say(b, p, answer);
            }
            return true;
        }
        java.util.Optional<String> collect = CompanionChat.rucksackCollect(rest);
        if (collect.isPresent()) {
            java.util.Optional<Material> m = RucksackService.material(collect.get());
            String answer = m.isEmpty() ? "Was ist denn „" + Texts.clean(collect.get(), 40) + "“? Das kenne ich nicht."
                    : rs.toggleCollect(p, m.get());
            if (answer != null) {
                say(b, p, answer);
            }
            return true;
        }
        java.util.Optional<CompanionChat.GiveOrder> take = CompanionChat.rucksackTake(rest);
        if (take.isEmpty()) {
            return false;
        }
        java.util.Optional<Material> m = RucksackService.material(take.get().item());
        if (m.isEmpty()) {
            say(b, p, "Was ist denn „" + Texts.clean(take.get().item(), 40) + "“? Das finde ich so nicht.");
            return true;
        }
        int want = take.get().count() > 0 ? take.get().count() : m.get().getMaxStackSize();
        int got = rs.take(p, m.get(), want);
        if (got < 0) {
            return true;
        }
        String name = take.get().item();
        say(b, p, got == 0 ? "In deinem Rucksack ist kein " + name + ", oder dein Inventar ist voll."
                : got < want && take.get().count() > 0 ? "Ich hab nur " + got + " " + name + " gefunden – hier!"
                : "Hier, " + got + " " + name + " aus deinem Rucksack!");
        return true;
    }

    // ---------------------------------------------------------------- Bauwerk merken und nachbauen

    /**
     * „Ecke eins“, „Ecke zwei“, „merk dir das Bauwerk als turm“, „bau turm hier gespiegelt / doppelt so groß“ –
     * ohne WorldEdit-Zauberstab (SchematicLibrary.saveCorners) und ohne Modell. true = erledigt. Nachbauen nur, wenn
     * es das Bauteil gibt; sonst geht „bau mir ein Haus hier“ wie bisher an den Baumeister.
     */
    private boolean buildMemory(Buddy b, Player p, String rest) {
        int corner = CompanionChat.corner(rest);
        if (corner > 0) {
            org.bukkit.block.Block target = p.getTargetBlockExact(8);
            Location at = (target != null ? target.getLocation() : p.getLocation().getBlock().getLocation());
            if (corner == 1) {
                b.cornerA = at;
            } else {
                b.cornerB = at;
            }
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0.5, 1.2, 0.5), 12, 0.3, 0.3, 0.3, 0);
            String size = "";
            if (b.cornerA != null && b.cornerB != null && b.cornerA.getWorld().equals(b.cornerB.getWorld())) {
                size = " Das sind " + (Math.abs(b.cornerA.getBlockX() - b.cornerB.getBlockX()) + 1) + " × "
                        + (Math.abs(b.cornerA.getBlockY() - b.cornerB.getBlockY()) + 1) + " × "
                        + (Math.abs(b.cornerA.getBlockZ() - b.cornerB.getBlockZ()) + 1) + " Blöcke – sag „merk dir das "
                        + "Bauwerk als …“.";
            }
            say(b, p, "Ecke " + (corner == 1 ? "eins" : "zwei") + " ist bei " + at.getBlockX() + " " + at.getBlockY()
                    + " " + at.getBlockZ() + "." + size);
            return true;
        }
        java.util.Optional<String> remember = CompanionChat.rememberBuild(rest);
        if (remember.isPresent()) {
            String name = remember.get();
            if (!p.hasPermission("baumeister.bauen")) {
                say(b, p, "Bauwerke merken darf hier nur, wer bauen darf (baumeister.bauen).");
                return true;
            }
            if (!(plugin.schematics() instanceof SchematicLibrary lib)) {
                say(b, p, "Zum Merken braucht der Server WorldEdit – das läuft hier nicht.");
                return true;
            }
            if (b.cornerA == null || b.cornerB == null) {
                say(b, p, "Zeig mir erst die Ecken: Schau auf eine Ecke und sag „Ecke eins“, dann auf die "
                        + "gegenüberliegende und sag „Ecke zwei“.");
                return true;
            }
            // Vorne ist die Seite, von der aus der Spieler draufschaut.
            com.blackn0va.baumeister.plan.Orientation.Cardinal front = com.blackn0va.baumeister.plan.Orientation
                    .Cardinal.fromYaw(p.getLocation().getYaw()).opposite();
            if (!b.cornerA.getWorld().equals(b.cornerB.getWorld())) {
                say(b, p, "Beide Ecken müssen in derselben Welt liegen.");
                return true;
            }
            // Name, Größe und Besitz VOR der Bereichsprüfung (Sicherheitsprüfung 2026-11-08, Befund 2) – sonst lief
            // die Prüfung über einen beliebig großen Quader.
            String problem = lib.rememberProblem(p, b.cornerA, b.cornerB, name);
            if (problem != null) {
                say(b, p, problem);
                return true;
            }
            if (!remembering.add(p.getUniqueId())) {
                say(b, p, "Ich prüfe gerade schon einen Bereich für dich – einen Moment.");
                return true;
            }
            rememberChecked(b, p, lib, b.cornerA, b.cornerB, name, front);
            return true;
        }
        java.util.Optional<CompanionChat.Rebuild> again = CompanionChat.rebuild(rest);
        if (again.isEmpty()) {
            return false;
        }
        String module = again.get().name() + (again.get().mirrored()
                ? com.blackn0va.baumeister.plan.ModuleTransforms.MIRRORED : "") + (again.get().doubled()
                        ? com.blackn0va.baumeister.plan.ModuleTransforms.DOUBLED : "");
        if (!plugin.service().hasModule(again.get().name())) {
            return false;
        }
        if (adventure.fair(p)) {
            say(b, p, "Im Abenteuer baust du selbst – der Baumeister macht so lange Pause.");
            return true;
        }
        if (!p.hasPermission("baumeister.bauen")) {
            say(b, p, "Bauen darf hier nur, wer baumeister.bauen hat.");
            return true;
        }
        if (!plugin.service().hasModule(module)) {
            say(b, p, "So groß wird „" + again.get().name() + "“ zu viel für einen Bau – versuch es ohne „doppelt“.");
            return true;
        }
        Location feet = p.getLocation().getBlock().getLocation();
        com.blackn0va.baumeister.plan.Orientation.Cardinal facing = com.blackn0va.baumeister.plan.Orientation.Cardinal
                .fromYaw(p.getLocation().getYaw());
        com.blackn0va.baumeister.plan.Pos ahead = new com.blackn0va.baumeister.plan.Orientation(facing)
                .toWorldOffset(new com.blackn0va.baumeister.plan.Pos(0, 0, 2));
        LOG.info(() -> "begleiter | nachbauen | spieler=" + p.getName() + " bauteil=" + module);
        say(b, p, "Der Baumeister baut „" + again.get().name() + "“" + (again.get().mirrored() ? " gespiegelt" : "")
                + (again.get().doubled() ? " doppelt so groß" : "") + " vor dir!");
        plugin.service().requestModuleAt(p, feet.clone().add(ahead.x(), ahead.y(), ahead.z()), facing, module);
        return true;
    }

    /**
     * Plan B6: Erst die Baurechte im ganzen Bereich prüfen, dann speichern. Vorher kopierte Vera jeden Bereich, den ein
     * Spieler mit zwei Ecken zeigte – auch aus fremden Plots, samt Truhen-Inhalten und Befehlen. Jetzt fragt sie für
     * jeden Block WorldGuard/PlotSquared, ob der Spieler dort abbauen dürfte (RegionCheck, wie BuildJob), in Portionen
     * von 10 ms je Takt (AreaCheck), und hört beim ersten Nein auf (fail-closed). Offline mitten in der Prüfung: kein
     * Speichern. Blockdaten behält SchematicLibrary nur für Admins; der Name bekommt den Spieler als Besitzer.
     */
    private void rememberChecked(Buddy b, Player p, SchematicLibrary lib, Location a, Location c, String name,
            com.blackn0va.baumeister.plan.Orientation.Cardinal front) {
        UUID id = p.getUniqueId();
        World w = a.getWorld();
        AreaCheck check = new AreaCheck(a.getBlockX(), a.getBlockY(), a.getBlockZ(), c.getBlockX(), c.getBlockY(),
                c.getBlockZ(), (x, y, z) -> {
                    Player now = Bukkit.getPlayer(id);
                    // Nur geladene Chunks: getBlockAt lüde sonst im Hauptthread nach (Befund 2) – nicht geladen = Nein.
                    if (now == null || !w.isChunkLoaded(x >> 4, z >> 4)) {
                        return false;
                    }
                    org.bukkit.block.Block blk = w.getBlockAt(x, y, z);
                    return blk.getType().isAir() || RegionCheck.mayBreakQuietly(now, blk);
                });
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            TimeSlice.run(System::nanoTime, 10_000_000L, 64, 50_000, check::step);
            if (!check.done()) {
                return;
            }
            task.cancel();
            remembering.remove(id);
            Player now = Bukkit.getPlayer(id);
            if (now == null) {
                LOG.info(() -> "begleiter | bauwerk_merken_abgebrochen | name=" + name + " grund=offline");
                return;
            }
            if (!check.allowed()) {
                int[] at = check.deniedAt();
                boolean unloaded = !w.isChunkLoaded(at[0] >> 4, at[2] >> 4);
                LOG.info(() -> "begleiter | bauwerk_merken_verweigert | spieler=" + now.getName() + " name=" + name
                        + " x=" + at[0] + " y=" + at[1] + " z=" + at[2] + (unloaded ? " grund=nicht_geladen" : ""));
                say(b, now, unloaded ? "Der Bereich bei " + at[0] + " " + at[2] + " ist gerade nicht geladen – geh "
                        + "näher heran und sag es noch einmal." : "Bei " + at[0] + " " + at[1] + " " + at[2]
                        + " darfst du nicht bauen – dieses Bauwerk merke ich mir nicht.");
                return;
            }
            try {
                String problem = lib.saveCorners(now, a, c, name, front, "gemerkt von " + now.getName());
                if (problem != null) {
                    say(b, now, problem);
                    return;
                }
            } catch (java.io.IOException e) {
                LOG.log(java.util.logging.Level.WARNING, "begleiter | bauwerk_merken_fehlgeschlagen | name=" + name, e);
                say(b, now, "Das Merken hat nicht geklappt: " + Texts.clean(e.getMessage(), 80));
                return;
            }
            b.cornerA = null;
            b.cornerB = null;
            say(b, now, "Gemerkt als „" + name + "“! Sag „bau " + name + " hier“ – auch „gespiegelt“ oder „doppelt so "
                    + "groß“ geht.");
        }, 1L, 1L);
    }

    // ---------------------------------------------------------------- Protokoll: Kiste, Reparieren

    /**
     * „Wer war an meiner Kiste?“ – Kiste, auf die der Spieler schaut. Nur eigene Kisten (laut Protokoll selbst gesetzt)
     * oder mit baumeister.kisten: Wer an fremden Kisten war, wäre sonst für jeden lesbar (ASVS V4.2.1, OWASP A01).
     */
    private void chestHistory(Buddy b, Player p) {
        if (history == null) {
            say(b, p, "Ich kann das Protokoll gerade nicht lesen – dafür braucht der Server CoreProtect.");
            return;
        }
        org.bukkit.block.Block block = p.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof org.bukkit.block.Container)) {
            say(b, p, "Schau auf die Kiste, die ich prüfen soll, und frag mich dann noch mal.");
            return;
        }
        Location at = block.getLocation();
        history.placedBy(block, Integer.MAX_VALUE / 2, owner -> {
            boolean mine = owner.map(n -> n.equalsIgnoreCase(p.getName())).orElse(false);
            if (!mine && !rights.allowed(p, Ability.KISTEN)) {
                say(b, p, owner.isPresent() ? "Das ist nicht deine Kiste – da schaue ich nicht nach."
                        : "Ich weiß nicht, wem diese Kiste gehört, deshalb schaue ich nicht nach.");
                return;
            }
            history.chest(at, CHEST_SECONDS, rows -> {
                LOG.info(() -> "begleiter | kiste | spieler=" + p.getName() + " einträge=" + rows.size());
                say(b, p, com.blackn0va.baumeister.companion.WorldWatch.chestHistory(rows, System.currentTimeMillis(),
                        5));
            });
        });
    }

    /**
     * „Reparier mein Haus“: zählt Schäden durch Creeper, Feuer, Blitz … (WorldWatch.DAMAGE_CAUSES) im Umkreis und
     * fragt nach. Nie Abbau durch Spieler. Im Abenteuer nicht (selbst reparieren gehört zum Spiel).
     *
     * Recht baumeister.reparieren (Vorgabe op): Was die Explosion als Gegenstand fallen ließ und aufgehoben wurde,
     * gibt es nach dem Zurücksetzen doppelt – für alle freigeben wäre ein Vervielfältiger.
     */
    private void askRepair(Buddy b, Player p) {
        if (adventure.fair(p)) {
            say(b, p, "Im Abenteuer reparierst du selbst – ich zeige dir gern, was fehlt.");
            return;
        }
        if (!rights.allowed(p, Ability.REPARIEREN)) {
            say(b, p, "Reparieren darf ich hier nur für Spieler mit dem Recht baumeister.reparieren.");
            return;
        }
        if (history == null) {
            say(b, p, "Zum Reparieren brauche ich das Protokoll von CoreProtect – das läuft hier nicht.");
            return;
        }
        Location at = p.getLocation();
        say(b, p, "Moment, ich schaue nach Schäden …");
        history.countDamage(at, REPAIR_RADIUS, REPAIR_SECONDS, n -> {
            if (n < 0) {
                say(b, p, "Das Protokoll antwortet gerade nicht. Versuch es gleich noch mal.");
                return;
            }
            if (n == 0) {
                say(b, p, "Hier sehe ich keine Schäden durch Creeper, Feuer oder Blitz aus den letzten drei Tagen.");
                return;
            }
            b.pendingRepair = at;
            b.pendingBy = p.getUniqueId();
            b.pendingNanos = System.nanoTime();
            say(b, p, n + " Blöcke wurden hier in den letzten drei Tagen von Creeper, Feuer oder Blitz zerstört (bis "
                    + REPAIR_RADIUS + " Blöcke um dich). Soll ich sie wieder hinsetzen? Sag ja oder nein.");
        });
    }

    private void runRepair(Buddy b, Player p, Location at) {
        LOG.info(() -> "begleiter | reparieren | spieler=" + p.getName() + " welt=" + at.getWorld().getName() + " x="
                + at.getBlockX() + " z=" + at.getBlockZ());
        history.repair(at, REPAIR_RADIUS, REPAIR_SECONDS, n -> {
            LOG.info(() -> "begleiter | repariert | spieler=" + p.getName() + " änderungen=" + n);
            say(b, p, n < 0 ? "Das hat nicht geklappt – das Protokoll hat abgelehnt."
                    : n == 0 ? "Da war nichts mehr zu tun." : "Fertig! " + n + " Blöcke sind wieder da.");
        });
    }

    /**
     * Nachtwache (im Takt, alle 2 s): Nachts warnt Vera, wenn Monster ums Haus (Bett/Respawnpunkt) schleichen, solange
     * der Spieler in der Nähe ist. Höchstens alle zwei Minuten, damit es nicht nervt.
     */
    private void watch(Buddy b, Player p) {
        World w = p.getWorld();
        boolean night = w.getEnvironment() == World.Environment.NORMAL
                && com.blackn0va.baumeister.companion.WorldWatch.night(w.getTime());
        if (night && !b.wasNight) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.NIGHT, 10 * 60_000L);
        }
        b.wasNight = night;
        if (w.getEnvironment() != World.Environment.NORMAL
                || !com.blackn0va.baumeister.companion.WorldWatch.night(w.getTime())) {
            return;
        }
        Location home = p.getRespawnLocation();
        if (home == null || !w.equals(home.getWorld())
                || home.distanceSquared(p.getLocation()) > WATCH_NEAR_HOME * WATCH_NEAR_HOME) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - b.lastWatchWarn < com.blackn0va.baumeister.companion.WorldWatch.WARN_EVERY_MILLIS) {
            return;
        }
        Map<String, Integer> seen = new java.util.TreeMap<>();
        double sx = 0;
        double sz = 0;
        int n = 0;
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(home, WATCH_RADIUS, 12, WATCH_RADIUS)) {
            if (e instanceof org.bukkit.entity.Monster) {
                seen.merge(e.getType().getKey().getKey(), 1, Integer::sum);
                sx += e.getLocation().getX();
                sz += e.getLocation().getZ();
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        b.lastWatchWarn = now;
        String where = CompanionRules.direction(sx / n - home.getX(), 0, sz / n - home.getZ());
        String what = com.blackn0va.baumeister.companion.WorldWatch.monsters(seen);
        LOG.info(() -> "begleiter | nachtwache | spieler=" + p.getName() + " monster=" + seen);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 0.7f);
        say(b, p, "Achtung! " + what + " bei deinem Haus – " + where + ".");
    }

    private void command(Buddy b, Player p, CompanionChat.Command cmd) {
        if (!b.owner.equals(p.getUniqueId())) {
            // Mitspieler dürfen fragen, aber nicht über fremde Begleiter bestimmen (wegschicken, mitnehmen, rückgängig).
            say(b, p, "Ich begleite gerade " + ownerName(b) + ". Mit /bm begleiter rufst du dir deinen eigenen.");
            return;
        }
        switch (cmd) {
            case COME -> {
                b.staying = false;
                Mannequin m = entity(b);
                if (m != null) {
                    m.teleport(BuilderNpc.facing(beside(p), p.getEyeLocation()));
                }
                say(b, p, "Komme!");
            }
            case FOLLOW -> {
                b.staying = false;
                say(b, p, "Ich folge dir.");
            }
            case STAY -> {
                b.staying = true;
                say(b, p, "Ich warte hier.");
            }
            case DISMISS -> dismiss(p);
            case UNDO -> undo(b, p);
        }
    }

    private boolean inConversation(Buddy b, Player p) {
        Mannequin m = entity(b);
        return m != null && m.getWorld().equals(p.getWorld()) && p.getUniqueId().equals(b.partner)
                && m.getLocation().distanceSquared(p.getLocation()) <= HEAR * HEAR
                && System.nanoTime() - b.lastReplyNanos <= settings.conversationNanos();
    }

    // ---------------------------------------------------------------- LLM

    private void ask(Buddy b, Player p, String text, boolean spoken) {
        if (b.thinking) {
            say(b, p, "Moment, ich überlege noch.");
            return;
        }
        b.thinking = true;
        Mannequin m = entity(b);
        if (m != null) {
            MannequinCompat.description(m, "denkt nach …");
        }
        List<JsonObject> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", prompt.replace("{charakter}",
                com.blackn0va.baumeister.companion.CompanionCharacter.prompt(cheeky(p)))));
        messages.addAll(b.history);
        // Bau hier: Steht der Spieler in einem Baumeister-Bau, kennt Vera dessen Bedienung (Baubuch, BuildLedger).
        String here = plugin.service().describeBuildAt(p).map(s -> "\n[Bau hier] " + s).orElse("");
        String story = adventure.context(p);
        if (!story.isEmpty()) {
            here += "\n[Abenteuer] " + story;
        }
        // Redstone-Frage: die echte Schaltung mitgeben (Richtung, Strom, Auffälliges). Vorher sah das Modell nichts
        // davon und erklärte Schaltungen aus dem Stegreif. Nur bei Redstone-Wörtern – sonst kostet es Kontext.
        if (CompanionChat.aboutRedstone(text)) {
            com.blackn0va.baumeister.redstone.RedstoneReader.Report r = RedstoneScan.aroundPlayer(p);
            if (!r.empty()) {
                here += "\n[Redstone, aus der Welt gelesen] " + com.blackn0va.baumeister.redstone.RedstoneReader
                        .describe(r, RedstoneScan::compass, RedstoneScan.MAX_CHARS);
            }
        }
        messages.add(LlmClient.message("user", text + "\n\n[Kontext] " + context(p) + here));
        long t0 = System.nanoTime();
        LOG.info(() -> "begleiter | frage | spieler=" + p.getName() + " gesprochen=" + spoken + " text="
                + Texts.clean(text, 120));
        CompletableFuture<LlmClient.Reply> f = deadline(llm.complete(messages), ANSWER_DEADLINE_MS);
        // Kein „Moment, ich denke noch nach …“ mehr (Wunsch 2026-11-04). Es hing an Ticks: Mit /tick rate 2000 kam es
        // nach 0,08 s statt 8 s bei fast jeder Frage.
        f.whenComplete((reply, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            b.thinking = false;
            Mannequin now = entity(b);
            if (now != null) {
                MannequinCompat.description(now, "Begleiter von " + ownerName(b));
            }
            if (!p.isOnline() || buddies.get(b.owner) != b) {
                return;
            }
            if (err != null) {
                LOG.info(() -> "begleiter | llm_fehler | grund=" + LlmClient.describe(err) + " ms="
                        + (System.nanoTime() - t0) / 1_000_000);
                // Ohne Modell weiterreden statt nur „Ich kann gerade nicht nachdenken“ (CompanionIntent.chat).
                java.util.Optional<String> offline = com.blackn0va.baumeister.companion.CompanionIntent.chat(text,
                        cheeky(p), java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
                say(b, p, offline.orElse(failure(err)));
                return;
            }
            CompanionReply.Reply r = CompanionReply.parse(reply.content());
            if (r == null) {
                LOG.info(() -> "begleiter | antwort_unlesbar | inhalt=" + Texts.clean(reply.content(), 200));
                say(b, p, "Entschuldige, da ist bei mir etwas durcheinandergeraten. Sag es bitte noch einmal.");
                return;
            }
            remember(b, LlmClient.message("user", text));
            remember(b, LlmClient.message("assistant", reply.content()));
            LOG.info(() -> String.format(Locale.ROOT, "begleiter | antwort | spieler=%s ms=%d aktionen=%d",
                    p.getName(), (System.nanoTime() - t0) / 1_000_000, r.actions().size()));
            perform(b, p, r, spoken);
        }));
    }

    /** Frist für eine Antwort; danach endet f mit TimeoutException (die Anfrage am Host läuft ggf. zu Ende). */
    static <T> CompletableFuture<T> deadline(CompletableFuture<T> f, long millis) {
        return f.orTimeout(millis, TimeUnit.MILLISECONDS);
    }

    /** Was Vera sagt, wenn keine Antwort kam – je nach Grund, damit der Spieler weiß, ob Warten hilft. */
    static String failure(Throwable err) {
        Throwable c = err;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof TimeoutException || c instanceof java.net.http.HttpTimeoutException) {
            return "Das dauert mir zu lange – die Baumeister-App rechnet noch oder lädt gerade ein Modell. "
                    + "Frag mich gleich noch einmal.";
        }
        if (c instanceof LlmClient.LlmException e && "llm_http_503".equals(e.getMessage())) {
            return "Die Baumeister-App ist gerade beschäftigt. Frag mich gleich noch einmal.";
        }
        return "Ich kann gerade nicht nachdenken – läuft die Baumeister-App?";
    }

    private static void remember(Buddy b, JsonObject message) {
        b.history.addLast(message);
        while (b.history.size() > HISTORY) {
            b.history.removeFirst();
        }
    }

    /**
     * Eine Antwort ausführen – vom Modell oder ohne Modell (CompanionIntent): Fair-Play-Filter, sagen, Aktionen,
     * Befehle als Einheit. Ein gemeinsamer Weg, damit Vera mit und ohne Baumeister-App dasselbe tut und dieselben
     * Rechte prüft. spoken: Die Zeile kam aus der Spracherkennung – Befehle daraus nur nach getipptem „ja“ (Plan A4).
     */
    private void perform(Buddy b, Player p, CompanionReply.Reply r, boolean spoken) {
        List<CompanionReply.Action> actions = r.actions();
        String answer = r.say();
        if (adventure.fair(p)) {
            // Verbotenes vor dem Sprechen streichen (AdventureFilter): sonst verspricht Vera erst und lehnt dann ab.
            var filtered = com.blackn0va.baumeister.companion.AdventureFilter.filter(actions);
            if (filtered.blocked() > 0) {
                LOG.info(() -> "begleiter | abenteuer_gefiltert | spieler=" + p.getName() + " gestrichen="
                        + filtered.blocked());
                actions = filtered.kept();
                if (filtered.ore() != null) {
                    answer = com.blackn0va.baumeister.companion.AdventureFilter.oreHint(filtered.ore(),
                            p.getLocation().getBlockY());
                } else if (actions.isEmpty()) {
                    answer = "Das machst du im Abenteuer selbst – ich helfe dir mit Tipps. Frag mich „was jetzt?“.";
                }
            }
        }
        if (answer != null && !answer.isBlank()) {
            say(b, p, answer);
        }
        // Befehle einer Antwort als Einheit (serverCommands): „TNT stapeln und anzünden“ darf nicht zünden,
        // während das Stapeln noch auf das „ja“ wartet.
        List<String> commands = new ArrayList<>();
        for (CompanionReply.Action a : actions) {
            if (a.op() == CompanionReply.Op.COMMAND) {
                commands.add(a.text());
            } else {
                act(b, p, a, spoken);
            }
        }
        if (!commands.isEmpty()) {
            serverCommands(b, p, commands, spoken);
        }
    }

    /**
     * Ohne Modell verstanden (CompanionIntent.command): ins Gespräch wie eine Modellantwort (Folgefragen kennen den
     * Zusammenhang), dann derselbe Weg wie eine Modellantwort. FIND auf einen Namen, der kein Block ist („wo ist mein
     * Zuhause“, „wo ist Alex“), geht an WHERE; „Diamant“ & Co. meinen das Erz.
     */
    private void runIntent(Buddy b, Player p, String text, CompanionReply.Reply r, boolean spoken) {
        List<CompanionReply.Action> acts = new ArrayList<>();
        for (CompanionReply.Action a : r.actions()) {
            if (a.op() == CompanionReply.Op.FIND
                    && com.blackn0va.baumeister.companion.CompanionRules.placeFor(a.block()).isPresent()) {
                acts.add(a);
            } else if (a.op() == CompanionReply.Op.FIND) {
                String ore = com.blackn0va.baumeister.companion.CompanionRules.oreFor(a.block());
                Material m = material(ore != null ? ore : a.block(), true);
                acts.add(m != null && m.isBlock()
                        ? new CompanionReply.Action(CompanionReply.Op.FIND, "", 0, m.getKey().getKey(), a.radius(), "",
                                "", "")
                        : new CompanionReply.Action(CompanionReply.Op.WHERE, "", 0, "", 0, "", "", a.name()));
            } else {
                acts.add(a);
            }
        }
        CompanionReply.Reply done = new CompanionReply.Reply(r.say(), List.copyOf(acts));
        JsonObject reply = new JsonObject();
        reply.addProperty("say", done.say());
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (CompanionReply.Action a : done.actions()) {
            JsonObject o = new JsonObject();
            o.addProperty("op", a.op().name().toLowerCase(Locale.ROOT));
            if (!a.item().isEmpty()) {
                o.addProperty("item", a.item());
            }
            if (a.count() > 0) {
                o.addProperty("count", a.count());
            }
            if (!a.block().isEmpty()) {
                o.addProperty("block", a.block());
            }
            if (!a.text().isEmpty()) {
                o.addProperty("text", a.text());
            }
            if (!a.name().isEmpty()) {
                o.addProperty("name", a.name());
            }
            arr.add(o);
        }
        reply.add("actions", arr);
        remember(b, LlmClient.message("user", text));
        remember(b, LlmClient.message("assistant", reply.toString()));
        LOG.info(() -> "begleiter | ohne_modell | spieler=" + p.getName() + " aktionen=" + done.actions().stream()
                .map(a -> a.op().name().toLowerCase(Locale.ROOT)).toList() + " text=" + Texts.clean(text, 120));
        perform(b, p, done, spoken);
    }

    /** Was das Modell über den Spieler wissen muss – in einer Zeile, damit der Kontext klein bleibt. */
    private String context(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        Map<Material, Integer> inv = new EnumMap<>(Material.class);
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                inv.merge(it.getType(), it.getAmount(), Integer::sum);
            }
        }
        StringBuilder items = new StringBuilder();
        inv.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(10)
                .forEach(e -> items.append(items.isEmpty() ? "" : ", ").append(e.getValue()).append("x ")
                        .append(e.getKey().getKey().getKey()));
        // Rechte stehen im Kontext, damit das Modell nicht Befehle anbietet, die das Plugin dann ablehnt. Geprüft wird
        // trotzdem im Plugin (serverCommand, give) – der Kontext ist nur ein Hinweis.
        return String.format(Locale.ROOT, "Spieler %s, Welt %s, Position %d %d %d, Biom %s, Uhrzeit %s, Wetter %s, "
                + "Leben %.0f/20, Hunger %d/20, Spielmodus %s, Befehle: %s, Geben: %s, Inventar: %s", p.getName(),
                w.getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), l.getBlock().getBiome().getKey().getKey(),
                CompanionRules.clock(w.getTime()), w.isThundering() ? "Gewitter" : w.hasStorm() ? "Regen" : "klar",
                p.getHealth(), p.getFoodLevel(), p.getGameMode().name().toLowerCase(Locale.ROOT),
                rights.allowed(p, Ability.BEFEHLE) ? "erlaubt" : "nicht erlaubt",
                p.getGameMode() == GameMode.CREATIVE || rights.allowed(p, Ability.GEBEN) ? "erlaubt"
                        : "nicht erlaubt",
                items.isEmpty() ? "leer" : items);
    }

    // ---------------------------------------------------------------- Aktionen

    private void act(Buddy b, Player p, CompanionReply.Action a, boolean spoken) {
        switch (a.op()) {
            case GIVE -> give(b, p, a.item(), a.count());
            case FIND -> find(b, p, a.block(), a.radius());
            case CALC -> {
                MathExpr.Result r = MathExpr.eval(a.expr());
                say(b, p, r.ok() ? a.expr() + " = " + MathExpr.format(r.value()) : r.error());
            }
            case FOLLOW -> command(b, p, CompanionChat.Command.FOLLOW);
            case STAY -> command(b, p, CompanionChat.Command.STAY);
            case COME -> command(b, p, CompanionChat.Command.COME);
            case BUILD -> {
                if (adventure.fair(p)) {
                    say(b, p, "Im Abenteuer baust du selbst – der Baumeister macht so lange Pause.");
                } else if (!p.hasPermission("baumeister.bauen")) {
                    say(b, p, "Bauen lassen darfst du leider nicht.");
                } else if (a.text().isBlank()) {
                    say(b, p, "Was soll der Baumeister denn bauen?");
                } else {
                    plugin.service().requestFromPlayer(p, a.text());
                }
            }
            case SONG -> song(b, p, a.name());
            case TIME -> say(b, p, "Es ist " + CompanionRules.clock(p.getWorld().getTime()) + " Uhr.");
            case WEATHER -> say(b, p, p.getWorld().isThundering() ? "Es gewittert."
                    : p.getWorld().hasStorm() ? "Es regnet (oder schneit)." : "Das Wetter ist klar.");
            case COMMAND -> serverCommands(b, p, List.of(a.text()), spoken);
            case RECIPE -> recipe(b, p, a.item());
            case REPAIR -> say(b, p, p.hasPermission("baumeister.bauen") ? plugin.service().repair(p)
                    : "Reparieren lassen darfst du leider nicht.");
            case MARK -> mark(b, p, a.name());
            case GOTO -> travel(b, p, a.name());
            case WHERE -> where(b, p, a.name());
            case REMIND -> remind(b, p, a.count(), a.text());
            case LOOK -> look(b, p);
            case USE -> {
                com.blackn0va.baumeister.companion.CompanionUse.Target t =
                        com.blackn0va.baumeister.companion.CompanionUse.target(a.block());
                if (t == null) {
                    say(b, p, "Was genau soll ich benutzen – Tür, Hebel, Knopf oder Glocke?");
                } else {
                    use(b, p, new com.blackn0va.baumeister.companion.CompanionUse.Use(t,
                            t == com.blackn0va.baumeister.companion.CompanionUse.Target.CHEST ? Boolean.TRUE
                                    : com.blackn0va.baumeister.companion.CompanionUse.direction(a.text())));
                }
            }
            case CHARACTER -> {
                com.blackn0va.baumeister.companion.CompanionCharacter.Kind k =
                        com.blackn0va.baumeister.companion.CompanionCharacter.Kind.parse(a.name());
                if (k != null) {
                    // Ohne eigene Ansage: Das Modell hat in „say“ schon geantwortet.
                    setCharacter(p, k, false);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Orte, Spieler, Erinnerungen, Blick

    /** Gemerkte Orte (Waypoints), Datei begleiter-orte.json im Plugin-Ordner. */
    private com.blackn0va.baumeister.companion.Waypoints waypoints = new com.blackn0va.baumeister.companion.Waypoints();
    /** Offene Erinnerungen, geprüft im Takt nach Wanduhr – Ticks wären mit /tick rate falsch (siehe ask). */
    private final List<Reminder> reminders = new ArrayList<>();
    static final int MAX_REMINDERS = 5;

    private record Reminder(UUID player, long dueMillis, String text) {
    }

    private java.nio.file.Path waypointFile() {
        return plugin.getDataFolder().toPath().resolve("begleiter-orte.json");
    }

    private void loadWaypoints() {
        java.nio.file.Path file = waypointFile();
        if (!java.nio.file.Files.exists(file)) {
            return;
        }
        try {
            waypoints = com.blackn0va.baumeister.companion.Waypoints.fromJson(java.nio.file.Files.readString(file));
        } catch (java.io.IOException e) {
            // Gemerkte Orte sind Komfort: bewusst fail-open, Vera startet dann ohne sie.
            LOG.log(java.util.logging.Level.WARNING, "begleiter | orte_lesen_fehlgeschlagen | datei=" + file, e);
        }
    }

    private void saveWaypoints() {
        String json = waypoints.toJson();
        java.nio.file.Path file = waypointFile();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".neu");
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(tmp, json);
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                LOG.log(java.util.logging.Level.WARNING, "begleiter | orte_schreiben_fehlgeschlagen | datei=" + file, e);
            }
        });
    }

    private void mark(Buddy b, Player p, String name) {
        Location l = p.getLocation();
        var result = waypoints.mark(p.getUniqueId(), name, new com.blackn0va.baumeister.companion.Waypoints.Point(
                l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw()));
        switch (result) {
            case NEW, REPLACED -> {
                saveWaypoints();
                LOG.info(() -> "begleiter | ort_gemerkt | spieler=" + p.getName() + " name=" + Texts.clean(name, 32));
                say(b, p, "Ich habe mir diesen Ort als „" + com.blackn0va.baumeister.companion.Waypoints.normalize(name)
                        + "“ gemerkt.");
            }
            case FULL -> say(b, p, "Ich kann mir höchstens " + com.blackn0va.baumeister.companion.Waypoints.MAX_PER_PLAYER
                    + " Orte merken. Sag mir, welchen ich vergessen soll.");
            case BAD_NAME -> say(b, p, "Wie soll der Ort heißen?");
        }
    }

    /** Ziel zu einem Namen: gemerkter Ort, „tod“ (letzter Tod) oder „bett“ (Bett/Wiedereinstieg). */
    private Location destination(Player p, String name) {
        String n = com.blackn0va.baumeister.companion.Waypoints.normalize(name);
        if (n == null) {
            return null;
        }
        if (n.equals("tod") || n.startsWith("letzter tod") || n.equals("death") || n.equals("sachen")) {
            return p.getLastDeathLocation();
        }
        if (n.equals("bett") || n.equals("bed") || n.equals("respawn")) {
            return p.getRespawnLocation();
        }
        // „bring mich nach Hause“ – der Ort heißt „zuhause“ (Hilfebuch: „merk dir das hier als Zuhause“).
        if (n.equals("hause") || n.equals("haus") || n.equals("daheim") || n.equals("heim") || n.equals("home")) {
            n = "zuhause";
        }
        Location own = waypoints.find(p.getUniqueId(), n).map(e -> {
            var pt = e.getValue();
            World w = Bukkit.getWorld(pt.world());
            return w == null ? null : new Location(w, pt.x(), pt.y(), pt.z(), pt.yaw(), 0);
        }).orElse(null);
        return own != null ? own : warp(name);
    }

    /** Öffentlicher Warp (WarpService) mit dem Namen – „bring mich zur Farmwelt“. */
    private Location warp(String name) {
        WarpService ws = plugin.warps();
        if (ws == null || name == null) {
            return null;
        }
        String n = name.strip().replaceFirst("(?iu)^(?:zur|zum|zu|in die|in den|ins|nach)\\s+", "");
        Location exact = ws.location(n).orElse(null);
        if (exact != null) {
            return exact;
        }
        // Verhört (dev01-Log: „Farbenwelt“, „Plottwelt“): der einzige Warp mit höchstens 2 Abweichungen.
        String near = com.blackn0va.baumeister.companion.CompanionIntent.nearest(n, ws.names());
        return near == null ? null : ws.location(near).orElse(null);
    }

    private void travel(Buddy b, Player p, String name) {
        Location to = destination(p, name);
        if (to == null) {
            List<String> mine = waypoints.names(p.getUniqueId());
            say(b, p, "Den Ort „" + Texts.clean(name, 32) + "“ kenne ich nicht."
                    + (mine.isEmpty() ? " Sag „merk dir das hier als …“, dann bringe ich dich später hin."
                            : " Ich kenne: " + String.join(", ", mine.subList(0, Math.min(6, mine.size()))) + "."));
            return;
        }
        // Teleport nur im Kreativmodus oder mit Recht (wie Geben): Im Überleben wäre er sonst ein Freifahrtschein.
        if (adventure.fair(p)) {
            say(b, p, way(p, to) + " Laufen musst du im Abenteuer selbst – ich zeige dir den Weg.");
            trail(p, to);
            return;
        }
        // Ein öffentlicher Warp ist für jeden mit baumeister.warp ohnehin per /warp erreichbar – dann darf Vera auch.
        boolean publicWarp = to.equals(warp(name)) && p.hasPermission("baumeister.warp");
        if (p.getGameMode() != GameMode.CREATIVE && !publicWarp && !rights.allowed(p, Ability.REISEN)) {
            say(b, p, way(p, to) + " Hinbringen darf ich dich hier leider nicht.");
            return;
        }
        p.teleport(to);
        LOG.info(() -> "begleiter | gereist | spieler=" + p.getName() + " ziel=" + Texts.clean(name, 32));
        Mannequin m = entity(b);
        if (m != null && b.owner.equals(p.getUniqueId())) {
            m.teleport(BuilderNpc.facing(beside(p), p.getEyeLocation()));
        }
        say(b, p, "Da sind wir!");
    }

    /** „etwa 120 Blöcke nordöstlich, 12 tiefer“ oder „in der Welt …“. */
    private static String way(Player p, Location to) {
        if (to.getWorld() == null || !to.getWorld().equals(p.getWorld())) {
            return "Das liegt in der Welt " + (to.getWorld() == null ? "?" : to.getWorld().getName()) + ".";
        }
        Location f = p.getLocation();
        return "Das liegt " + CompanionRules.direction(to.getX() - f.getX(), to.getY() - f.getY(), to.getZ()
                - f.getZ()) + ".";
    }

    private void where(Buddy b, Player p, String name) {
        Player other = name.isBlank() ? null : Bukkit.getPlayer(name.trim());
        if (other != null && other != p && p.canSee(other)) {
            if (!other.getWorld().equals(p.getWorld())) {
                say(b, p, other.getName() + " ist gerade in der Welt " + other.getWorld().getName() + ".");
            } else {
                Location o = other.getLocation();
                Location f = p.getLocation();
                say(b, p, other.getName() + " ist " + CompanionRules.direction(o.getX() - f.getX(), o.getY() - f.getY(),
                        o.getZ() - f.getZ()) + ".");
            }
            return;
        }
        Location to = destination(p, name);
        if (to != null) {
            say(b, p, way(p, to));
            return;
        }
        say(b, p, "„" + Texts.clean(name, 32) + "“ finde ich nicht – weder als Spieler online noch als gemerkten Ort.");
    }

    private void remind(Buddy b, Player p, int minutes, String text) {
        if (minutes <= 0) {
            say(b, p, "In wie vielen Minuten soll ich dich erinnern?");
            return;
        }
        long mine = reminders.stream().filter(r -> r.player().equals(p.getUniqueId())).count();
        if (mine >= MAX_REMINDERS) {
            say(b, p, "Ich habe schon " + MAX_REMINDERS + " Erinnerungen für dich – mehr merke ich mir nicht.");
            return;
        }
        String what = text.isBlank() ? "Du wolltest erinnert werden." : Texts.clean(text, 200);
        reminders.add(new Reminder(p.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L, what));
        LOG.info(() -> "begleiter | erinnerung | spieler=" + p.getName() + " minuten=" + minutes);
    }

    /** Im Takt: fällige Erinnerungen sagen; wer offline ist, verliert sie (nichts liegt über einen Neustart). */
    private void dueReminders() {
        if (reminders.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Reminder> due = reminders.stream().filter(r -> r.dueMillis() <= now).toList();
        reminders.removeAll(due);
        for (Reminder r : due) {
            Player p = Bukkit.getPlayer(r.player());
            if (p == null) {
                continue;
            }
            Buddy b = route(p);
            if (b != null) {
                say(b, p, "Erinnerung: " + r.text());
            } else {
                Texts.say(settings.name(), p.getLocation(), p, "Erinnerung: " + r.text());
            }
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
        }
    }

    private void look(Buddy b, Player p) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.RayTraceResult hit = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 32, 0.3,
                e -> !e.equals(p) && (b.entity == null || !e.getUniqueId().equals(b.entity)));
        org.bukkit.block.Block block = p.getTargetBlockExact(32);
        double blockDist = block == null ? Double.MAX_VALUE : block.getLocation().add(0.5, 0.5, 0.5).distance(eye);
        if (hit != null && hit.getHitEntity() != null
                && hit.getHitPosition().distance(eye.toVector()) < blockDist) {
            Entity e = hit.getHitEntity();
            String what = e instanceof Player o ? "der Spieler " + o.getName()
                    : "ein " + e.getType().getKey().getKey().replace('_', ' ')
                            + (e.getCustomName() != null ? " namens " + Texts.clean(e.getCustomName(), 32) : "");
            say(b, p, "Das ist " + what + ".");
            return;
        }
        if (block == null) {
            say(b, p, "Da sehe ich nichts in der Nähe – nur Luft.");
            return;
        }
        String id = block.getType().getKey().getKey();
        say(b, p, "Das ist " + id.replace('_', ' ') + " (" + id + "), etwa " + Math.round(blockDist) + " Blöcke weg.");
    }

    /**
     * Rezept aus den Rezepten DIESES Servers (RecipeText). Bevorzugt Werkbank, dann Ofen-Arten und Steinsäge; Schmieden
     * und Braustand bleiben außen vor (andere Form, selten gefragt).
     */
    private void recipe(Buddy b, Player p, String item) {
        Material mat = material(item, false);
        if (mat == null || mat.isAir() || !mat.isItem()) {
            say(b, p, "Das kenne ich leider nicht: " + Texts.clean(item, 40) + ".");
            return;
        }
        String id = mat.getKey().getKey();
        String text = null;
        for (org.bukkit.inventory.Recipe r : Bukkit.getRecipesFor(new ItemStack(mat))) {
            int amount = r.getResult().getAmount();
            if (r instanceof org.bukkit.inventory.ShapedRecipe s) {
                Map<Character, String> key = new java.util.HashMap<>();
                s.getChoiceMap().forEach((c, choice) -> {
                    if (choice != null) {
                        key.put(c, choiceName(choice));
                    }
                });
                text = RecipeText.shaped(id, amount, List.of(s.getShape()), key);
                break;
            }
            if (r instanceof org.bukkit.inventory.ShapelessRecipe s) {
                text = RecipeText.shapeless(id, amount, s.getChoiceList().stream().map(CompanionService::choiceName)
                        .toList());
                break;
            }
            if (text == null && r instanceof org.bukkit.inventory.CookingRecipe<?> c) {
                String station = r instanceof org.bukkit.inventory.BlastingRecipe ? "einen Schmelzofen"
                        : r instanceof org.bukkit.inventory.SmokingRecipe ? "einen Räucherofen"
                        : r instanceof org.bukkit.inventory.CampfireRecipe ? "ein Lagerfeuer" : "einen Ofen";
                text = RecipeText.processed(id, amount, choiceName(c.getInputChoice()), station);
            } else if (text == null && r instanceof org.bukkit.inventory.StonecuttingRecipe sc) {
                text = RecipeText.processed(id, amount, choiceName(sc.getInputChoice()), "die Steinsäge");
            }
        }
        say(b, p, text != null ? text : RecipeText.uncraftable(id));
    }

    private static String choiceName(org.bukkit.inventory.RecipeChoice choice) {
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice m) {
            return RecipeText.choiceName(m.getChoices().stream().map(x -> x.getKey().getKey()).toList());
        }
        if (choice instanceof org.bukkit.inventory.RecipeChoice.ExactChoice e) {
            return RecipeText.choiceName(e.getChoices().stream().map(x -> x.getType().getKey().getKey()).toList());
        }
        return "?";
    }

    /**
     * Alle Befehle einer Antwort als Einheit: Braucht einer davon eine Rückfrage, warten alle gemeinsam auf das „ja“
     * und laufen danach in ihrer Reihenfolge. Vorher lief jeder für sich – bei „fill … tnt“ (Rückfrage) und „setblock
     * … redstone_block“ (keine) hätte Vera gezündet, bevor gestapelt war, und eine zweite Rückfrage überschrieb die
     * erste. Jede Zeile geht vorher durch CompanionRules.repairCommand (Vorfall TNT 2026-11-04 20:23).
     *
     * Gesprochen (spoken) fragt Vera immer nach, auch bei Harmlosem, und bestätigt wird nur getippt (Plan A4,
     * CompanionRules.bundleNeedsConfirmation/pendingAnswer). Deshalb sagt jede Rückfrage „tippe“ statt „sag“.
     */
    private void serverCommands(Buddy b, Player p, List<String> raws, boolean spoken) {
        if (adventure.fair(p)) {
            say(b, p, "Im Abenteuer gibt es keine Befehle von mir – wir spielen fair.");
            LOG.info(() -> "begleiter | befehl_abgelehnt | spieler=" + p.getName() + " grund=abenteuer");
            return;
        }
        if (!rights.allowed(p, Ability.BEFEHLE)) {
            say(b, p, "Befehle darf ich für dich leider nicht ausführen.");
            LOG.info(() -> "begleiter | befehl_abgelehnt | spieler=" + p.getName() + " zeile="
                    + Texts.clean(String.join(" ; ", raws), 120));
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String raw : raws) {
            String line = CompanionRules.repairCommand(CompanionRules.commandLine(raw));
            if (line != null) {
                if (!line.equals(CompanionRules.commandLine(raw))) {
                    LOG.info(() -> "begleiter | befehl_repariert | vorher=" + Texts.clean(raw, 120) + " nachher="
                            + Texts.clean(line, 120));
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            say(b, p, "Welchen Befehl soll ich ausführen?");
            return;
        }
        if (!CompanionRules.bundleNeedsConfirmation(lines, spoken, p.getName())) {
            runCommands(b, p, lines);
            return;
        }
        b.pendingCommands = List.copyOf(lines);
        b.pendingNanos = System.nanoTime();
        b.pendingBy = p.getUniqueId();
        LOG.info(() -> "begleiter | befehl_rückfrage | spieler=" + p.getName() + " gesprochen=" + spoken + " zeilen="
                + Texts.clean(String.join(" ; ", lines), 200));
        String answer = " Tippe „ja“ oder „nein“ in den Chat.";
        if (lines.size() == 1) {
            String line = lines.getFirst();
            Location l = p.getLocation();
            CompanionRules.Area area = CompanionRules.changedArea(line, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (area == null) {
                say(b, p, "Soll ich wirklich /" + line + " ausführen?" + answer);
            } else {
                // Gesprochen in Worten, der genaue Befehl steht im Chat – „/fill ~-5 ~ ~-5 …“ versteht beim
                // Zuhören niemand (Vorfall 2026-11-04 12:19, siehe CompanionRules.changedArea).
                p.sendMessage(org.bukkit.ChatColor.GRAY + "Befehl: /" + line);
                say(b, p, CompanionRules.describeChange(area) + " Soll ich das wirklich machen?" + answer);
            }
            return;
        }
        for (String line : lines) {
            p.sendMessage(org.bukkit.ChatColor.GRAY + "Befehl: /" + line);
        }
        say(b, p, "Dafür brauche ich " + lines.size() + " Befehle, sie stehen im Chat. Soll ich sie ausführen?"
                + answer);
    }

    /**
     * Als der Spieler, als hätte er die Zeile getippt: p.chat("/…") statt Bukkit.dispatchCommand, weil nur so
     * PlayerCommandPreprocessEvent läuft – Befehlssperren anderer Plugins (WorldGuard, Essentials) greifen, und das
     * Server-Log zeigt „issued server command“ unter seinem Namen (ASVS V7.1.3). Rechte prüft der Befehl selbst.
     *
     * Sicherung für „mach das rückgängig“ über alle Bereiche der Zeilen, VOR der ersten: Jeder Block stammt so aus dem
     * Stand vor dem ganzen Bündel, auch wenn sich Bereiche überlappen.
     */
    private void runCommands(Buddy b, Player p, List<String> lines) {
        Location l = p.getLocation();
        List<CompanionRules.Area> areas = new ArrayList<>();
        long volume = 0;
        for (String line : lines) {
            CompanionRules.Area area = CompanionRules.changedArea(line, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (area != null) {
                areas.add(area);
                volume += area.volume();
            }
        }
        boolean saved = false;
        if (!areas.isEmpty() && volume <= UNDO_MAX_BLOCKS) {
            // Vorher sichern: CoreProtect protokolliert vanilla-fill nicht, ein Rollback gäbe es sonst nicht.
            List<BlockState> undo = new ArrayList<>();
            for (CompanionRules.Area area : areas) {
                undo.addAll(snapshot(p.getWorld(), area));
            }
            b.undo = undo;
            b.undoWorld = p.getWorld().getUID();
            saved = true;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LOG.info(() -> "begleiter | befehl | spieler=" + p.getName() + " zeile=" + Texts.clean(line, 200));
            boolean last = i == lines.size() - 1;
            p.sendMessage(org.bukkit.ChatColor.GRAY + settings.name() + " führt aus: /" + line
                    + (saved && last ? "  (rückgängig: „" + settings.name() + ", mach das rückgängig“)" : ""));
            p.chat("/" + line);
        }
    }

    private static List<BlockState> snapshot(World w, CompanionRules.Area a) {
        List<BlockState> out = new ArrayList<>((int) a.volume());
        int y0 = Math.max(a.y0(), w.getMinHeight());
        int y1 = Math.min(a.y1(), w.getMaxHeight() - 1);
        for (int x = a.x0(); x <= a.x1(); x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = a.z0(); z <= a.z1(); z++) {
                    out.add(w.getBlockAt(x, y, z).getState());
                }
            }
        }
        return out;
    }

    private void undo(Buddy b, Player p) {
        if (b.undo == null || !p.getWorld().getUID().equals(b.undoWorld)) {
            say(b, p, "Da gibt es nichts, was ich rückgängig machen könnte.");
            return;
        }
        // force=true: auch wenn der Block inzwischen ein anderer ist; applyPhysics=false: kein Nachrutschen von Sand
        // oder Wasser während des Zurücksetzens.
        for (BlockState s : b.undo) {
            s.update(true, false);
        }
        int n = b.undo.size();
        b.undo = null;
        LOG.info(() -> "begleiter | rückgängig | spieler=" + p.getName() + " blöcke=" + n);
        say(b, p, "Erledigt, alles ist wieder wie vorher.");
    }

    /**
     * ID aus dem ganzen Register des Servers (ItemNames): „redstone_comparator“ → comparator, „Fackeln“ → torch.
     * Vorher nur Material.matchMaterial – Vera sagte „Das kenne ich leider nicht: redstone_comparator“ (2026-11-04).
     */
    private static Material material(String raw, boolean block) {
        return com.blackn0va.baumeister.plan.ItemNames.resolve(raw, block ? BukkitBlockCatalog.BLOCKS
                : BukkitBlockCatalog.ITEMS).map(Material::matchMaterial).orElse(null);
    }

    /** true = gegeben; false = abgelehnt oder unbekannt (dann hat Vera es schon gesagt). */
    private boolean give(Buddy b, Player p, String item, int count) {
        if (adventure.fair(p)) {
            // Fair-Play im Abenteuer gilt auch für Admins (CompanionAdventure) – sonst kürzt „gib mir Diamanten“ alles ab.
            say(b, p, "Im Abenteuer schummeln wir nicht – das holst du dir selbst. Frag mich „was jetzt?“, dann sag ich "
                    + "dir, wie.");
            LOG.info(() -> "begleiter | geben_abgelehnt | spieler=" + p.getName() + " grund=abenteuer");
            return false;
        }
        Material mat = material(item, false);
        if (mat == null || !mat.isItem() || mat.isAir()) {
            say(b, p, "Das kenne ich leider nicht: " + Texts.clean(item, 40) + ".");
            return false;
        }
        // Prüfung im Plugin, nie im Modell (ASVS V4.1.3): Das LLM darf versprechen, was es will.
        if (!CompanionRules.mayGive(p.getGameMode() == GameMode.CREATIVE,
                rights.allowed(p, Ability.GEBEN))) {
            say(b, p, "Im Überlebensmodus darf ich dir nichts einfach so geben – das musst du dir selbst holen.");
            LOG.info(() -> "begleiter | geben_abgelehnt | spieler=" + p.getName() + " item=" + mat.getKey().getKey());
            return false;
        }
        int n = Math.max(1, Math.min(count <= 0 ? 1 : count, settings.maxGive()));
        // In Stapeln der echten Größe: 16 Spitzhacken sind 16 Stapel à 1, nicht ein Stapel mit 16.
        int left = n;
        while (left > 0) {
            int part = Math.min(left, mat.getMaxStackSize());
            p.getInventory().addItem(new ItemStack(mat, part)).values()
                    .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            left -= part;
        }
        LOG.info(() -> "begleiter | gegeben | spieler=" + p.getName() + " item=" + mat.getKey().getKey() + " anzahl="
                + n);
        return true;
    }

    private void song(Buddy b, Player p, String name) {
        Songs.Song song = Songs.byName(name);
        Mannequin m = entity(b);
        if (song == null || m == null) {
            say(b, p, "Das Lied kenne ich nicht. Ich kann: " + String.join(", ", Songs.names()) + ".");
            return;
        }
        long delay = 0;
        for (Songs.Note n : song.notes()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Mannequin now = entity(b);
                if (now == null) {
                    return;
                }
                for (Player near : now.getWorld().getPlayers()) {
                    if (near.getLocation().distanceSquared(now.getLocation()) <= 32 * 32) {
                        near.playNote(now.getLocation(), Instrument.PIANO, new Note(n.step()));
                    }
                }
                now.getWorld().spawnParticle(Particle.NOTE, now.getLocation().add(0, 2.2, 0), 1);
            }, delay);
            delay += n.ticks();
        }
    }

    /**
     * Nächster Block der Art: Schnappschüsse der geladenen Chunks im Umkreis (Hauptthread, je Chunk eine Kopie),
     * Suche außerhalb, Antwort zurück im Hauptthread. Nicht geladene Chunks bleiben aus – nichts wird dafür geladen.
     */
    private void find(Buddy b, Player p, String block, int radius) {
        java.util.Optional<com.blackn0va.baumeister.companion.CompanionRules.Place> place =
                com.blackn0va.baumeister.companion.CompanionRules.placeFor(block);
        if (place.isPresent()) {
            locate(b, p, place.get());
            return;
        }
        String ore = com.blackn0va.baumeister.companion.CompanionRules.oreFor(block);
        Material mat = material(ore != null ? ore : block, true);
        if (mat == null || !mat.isBlock()) {
            say(b, p, "Diesen Block kenne ich nicht: " + Texts.clean(block, 40) + ".");
            return;
        }
        if (!CompanionRules.mayFind(mat.getKey().getKey(), p.getGameMode() == GameMode.CREATIVE,
                rights.allowed(p, Ability.SUCHEN) && !adventure.fair(p))) {
            say(b, p, "Erze verrate ich im Überlebensmodus nicht – das wäre geschummelt.");
            return;
        }
        int r = Math.max(4, Math.min(radius <= 0 ? 32 : radius, 64));
        Location from = p.getLocation();
        World w = p.getWorld();
        int cx0 = (from.getBlockX() - r) >> 4;
        int cx1 = (from.getBlockX() + r) >> 4;
        int cz0 = (from.getBlockZ() - r) >> 4;
        int cz1 = (from.getBlockZ() + r) >> 4;
        List<ChunkSnapshot> snaps = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (w.isChunkLoaded(cx, cz)) {
                    snaps.add(w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                }
            }
        }
        int fx = from.getBlockX();
        int fy = from.getBlockY();
        int fz = from.getBlockZ();
        int minY = Math.max(w.getMinHeight(), fy - r);
        int maxY = Math.min(w.getMaxHeight() - 1, fy + r);
        // Alle Formen des Erzes (Tiefenschiefer, Nether-Gold) – CompanionRules.oreFamily.
        java.util.Set<Material> mats = java.util.EnumSet.of(mat);
        for (String id : com.blackn0va.baumeister.companion.CompanionRules.oreFamily(mat.getKey().getKey())) {
            Material m = Material.matchMaterial(id);
            if (m != null && m.isBlock()) {
                mats.add(m);
            }
        }
        CompletableFuture.supplyAsync(() -> nearest(snaps, mats, fx, fy, fz, r, minY, maxY))
                .whenComplete((hit, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    String what = mat.getKey().getKey().replace('_', ' ');
                    if (err != null || hit == null) {
                        say(b, p, "In " + r + " Blöcken Umkreis finde ich kein " + what + ".");
                        return;
                    }
                    say(b, p, "Das nächste " + what + " liegt " + CompanionRules.direction(hit[0] - fx, hit[1] - fy,
                            hit[2] - fz) + ".");
                    trail(p, new Location(w, hit[0] + 0.5, hit[1] + 0.5, hit[2] + 0.5));
                }));
    }

    /**
     * Ortssuche (Idee aus Verity JE: „nächstes Dorf“, „nächste Netherfestung“): Minecrafts eigene Suche
     * (locateNearestStructure) im Umkreis von 16 Chunks (256 Blöcke) um den Spieler, je Bauwerksart, der nächste
     * Treffer gewinnt. Umkreis wie im Abenteuer: Dort kostete die Suche auf dem Celeron von dev01 im Schnitt 93 ms,
     * höchstens 132 ms (2 Läufe bis 2026-11-08). Nach 150 ms hört sie auf (ein Dorf hat 5 Arten) – wer den Umkreis
     * oder die Grenze hebt, lässt den Server bei jeder Frage spürbar stocken. Gleiche Regeln wie Erze: im Überlebensmodus nur mit dem Recht „Erze suchen“ und nie im
     * Abenteuer (Fair Play). Läuft im Hauptthread – die Dauer steht im Log („begleiter | ort_gesucht | ms=“); auf dem
     * Celeron von dev01 zuerst messen, bevor der Umkreis wächst.
     */
    private void locate(Buddy b, Player p, com.blackn0va.baumeister.companion.CompanionRules.Place place) {
        // Wie bei Erzen (mayFind): Kreativmodus oder Recht „Erze suchen“, im Abenteuer nie.
        if (p.getGameMode() != GameMode.CREATIVE && !(rights.allowed(p, Ability.SUCHEN) && !adventure.fair(p))) {
            say(b, p, "Wo ein " + place.label() + " liegt, verrate ich im Überlebensmodus nicht – such es selbst!");
            return;
        }
        Location at = p.getLocation();
        long t0 = System.nanoTime();
        Location best = null;
        for (String key : place.structures()) {
            if ((System.nanoTime() - t0) / 1_000_000 > 150) {
                break;
            }
            org.bukkit.generator.structure.Structure s = org.bukkit.Registry.STRUCTURE.get(
                    org.bukkit.NamespacedKey.minecraft(key));
            if (s == null) {
                continue;
            }
            org.bukkit.util.StructureSearchResult r = at.getWorld().locateNearestStructure(at, s, 16, false);
            if (r != null && (best == null || r.getLocation().distanceSquared(at) < best.distanceSquared(at))) {
                best = r.getLocation();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        boolean found = best != null;
        LOG.info(() -> "begleiter | ort_gesucht | spieler=" + p.getName() + " was=" + place.label() + " treffer="
                + found + " ms=" + ms);
        if (best == null) {
            say(b, p, "Im Umkreis von 256 Blöcken finde ich kein " + place.label() + ".");
            return;
        }
        say(b, p, "Das nächste " + place.label() + " liegt " + CompanionRules.direction(best.getX() - at.getX(), 0,
                best.getZ() - at.getZ()) + ".");
        trail(p, new Location(at.getWorld(), best.getX() + 0.5, at.getY(), best.getZ() + 0.5));
    }

    /** Rein auf den Schnappschüssen; liefert {x, y, z} oder null. */
    static int[] nearest(List<ChunkSnapshot> snaps, java.util.Set<Material> mats, int fx, int fy, int fz, int r,
            int minY, int maxY) {
        int[] best = null;
        long bestD = (long) r * r + 1;
        for (ChunkSnapshot s : snaps) {
            int bx = s.getX() << 4;
            int bz = s.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                int dx = bx + x - fx;
                if (Math.abs(dx) > r) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    int dz = bz + z - fz;
                    if (Math.abs(dz) > r) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        int dy = y - fy;
                        long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                        if (d < bestD && mats.contains(s.getBlockType(x, y, z))) {
                            bestD = d;
                            best = new int[] {bx + x, y, bz + z};
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Partikelspur vom Spieler 8 Blöcke Richtung Ziel, 3 s lang – zeigt die Richtung, verrät nicht durch Wände. */
    private void trail(Player p, Location target) {
        for (int i = 0; i < 6; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !p.getWorld().equals(target.getWorld())) {
                    return;
                }
                Location eye = p.getEyeLocation();
                Vector dir = target.toVector().subtract(eye.toVector());
                double len = Math.min(8, dir.length());
                if (len < 0.5) {
                    return;
                }
                dir.normalize();
                for (double d = 1; d <= len; d += 0.5) {
                    p.spawnParticle(Particle.END_ROD, eye.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
                }
            }, i * 10L);
        }
    }

    // ---------------------------------------------------------------- Bewegung und Sprechen

    private void tick() {
        List<UUID> gone = new ArrayList<>();
        for (Buddy b : buddies.values()) {
            Player p = Bukkit.getPlayer(b.owner);
            Mannequin m = entity(b);
            if (p == null || m == null) {
                gone.add(b.owner);
                if (m != null) {
                    m.remove();
                }
                continue;
            }
            body(b, m, p);
            remember(b, p);
            b.prevOwnerPos = b.lastOwnerPos;
            b.lastOwnerPos = p.getLocation();
            if (b.owner.equals(p.getUniqueId())) {
                if (listenerTicks % 20 == 0) {
                    watch(b, p);
                }
                adventure.tick(p);
                Location foe = adventure.fightGoal(p);
                if (foe != null) {
                    // Mitkämpfen: bis auf Schlagweite an das Monster, Blick darauf (CompanionAdventure.guard schlägt).
                    Vector back = m.getLocation().toVector().subtract(foe.toVector()).setY(0);
                    Location close = back.lengthSquared() < 1.0e-6 ? foe
                            : foe.clone().add(back.normalize().multiply(1.6));
                    move(b, m, p, close, foe.clone().add(0, 1.4, 0), CompanionWalk.FIGHT);
                    continue;
                }
            }
            closeDoors(b, m, p);
            if (b.useAt != null && b.owner.equals(p.getUniqueId())) {
                if (walkToUse(b, m, p)) {
                    continue;
                }
            }
            if (b.staying) {
                lookIdle(b, m, p);
                continue;
            }
            if (rideAlong(b, m, p)) {
                continue;
            }
            Location goal = follow(b, p, m);
            boolean sameWorld = m.getWorld().equals(p.getWorld());
            double lag = sameWorld ? m.getLocation().distance(p.getLocation()) : Double.MAX_VALUE;
            b.following = !sameWorld
                    || com.blackn0va.baumeister.companion.CompanionFollow.shouldWalk(b.following, lag, settings.distance());
            if (b.following) {
                double away = sameWorld ? m.getLocation().distance(goal) : 0;
                move(b, m, p, goal, p.getEyeLocation(), CompanionWalk.speedFor(away));
                b.idleTicks = 0;
                watchAway(b, m, p, goal, lag);
            } else {
                if (b.owner.equals(p.getUniqueId())) {
                    idle(b, m, p);
                }
                // Nah genug: stehen bleiben, aber die Physik weiterlaufen lassen (fallen; im Wasser auf seiner Höhe –
                // taucht der Spieler, bleibt sie bei ihm, statt wie vorher zur Oberfläche zu treiben).
                if (walkMode(m, p, goal) && m.isInWater()) {
                    Vector v = m.getVelocity();
                    m.setVelocity(new Vector(v.getX(), CompanionWalk.swim(p.getLocation().getY()
                            - m.getLocation().getY()), v.getZ()));
                }
                b.walk.reset();
                lookIdle(b, m, p);
            }
        }
        gone.forEach(id -> {
            buddies.remove(id);
            forgetOwner(id);
        });
        dueReminders();
        // Alle 10 Takte reicht: Wer in Hörweite tritt, ist eine halbe Sekunde später zu hören.
        if (++listenerTicks % 10 == 0) {
            List<com.blackn0va.baumeister.companion.CompanionRouting.Speaker> players = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("baumeister.begleiter")) {
                    players.add(speaker(online));
                }
            }
            java.util.Set<String> inVoice = new java.util.HashSet<>();
            if (inGame != null) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (inGame.connected(online.getUniqueId())) {
                        inVoice.add(online.getName());
                    }
                }
            }
            voiceChatNames = java.util.Set.copyOf(inVoice);
            listeners = buddies.isEmpty() ? java.util.Set.of()
                    : java.util.Set.copyOf(com.blackn0va.baumeister.companion.CompanionRouting.listeners(players, spots(), HEAR));
        }
    }

    /**
     * Ein Tick auf goal zu. Laufen mit Schwerkraft (CompanionWalk, gemessen 2026-11-06), wenn das Ziel begehbar ist;
     * sonst wie früher schwebend in Teleport-Schritten: Fliegt der Spieler (Kreativ, Elytra) oder steht das Ziel über
     * Luft, käme sie laufend nie an und fiele nur hinterher. Andere Welt und weit weg: direkt teleportieren.
     */
    private void move(Buddy b, Mannequin m, Player p, Location goal, Location look, double speed) {
        Location cur = m.getLocation();
        if (!cur.getWorld().equals(goal.getWorld())) {
            b.walk.reset();
            BuilderNpc.stepToward(m, goal, look, STEP);
            return;
        }
        if (!walkMode(m, p, goal)) {
            b.walk.reset();
            BuilderNpc.stepToward(m, goal, look, airStep(b, p, cur.distance(goal)));
            return;
        }
        double far = CompanionWalk.TELEPORT_FROM + settings.distance();
        // Auch nach Abstand zum SPIELER: Vorher nur zum Ziel – lag das falsch (abgebrochene Spur), stand sie 48 Blöcke
        // entfernt auf ihrem Ziel und kam nie nach (Log 2026-11-07 17:15).
        if (cur.distanceSquared(goal) > CompanionWalk.TELEPORT_FROM * CompanionWalk.TELEPORT_FROM
                || cur.getWorld().equals(p.getWorld()) && cur.distanceSquared(p.getLocation()) > far * far) {
            b.walk.reset();
            b.planner.reset();
            m.setVelocity(new Vector());
            m.teleport(BuilderNpc.facing(landing(b, goal), look));
            return;
        }
        Location target = waypoint(b, cur, goal);
        Vector v = m.getVelocity();
        CompanionWalk.Step step = CompanionWalk.next(b.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(),
                org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())), target.getX(), target.getY(),
                target.getZ(), speed);
        if (step.teleport()) {
            b.planner.reset();
            m.setVelocity(new Vector());
            m.teleport(BuilderNpc.facing(landing(b, goal), look));
            return;
        }
        m.setVelocity(new Vector(step.vx(), step.vy(), step.vz()));
        if (step.moving()) {
            // In Laufrichtung schauen – rückwärts zum Spieler blickend zu laufen sah aus wie Gleiten.
            Location dir = cur.clone();
            dir.setDirection(new Vector(step.vx(), 0, step.vz()));
            m.setRotation(dir.getYaw(), 0);
        } else {
            BuilderNpc.lookAt(m, look);
        }
    }

    /**
     * Körpersprache (CompanionBody), 4× je Sekunde: Pose wie der Spieler (schleichen, schwimmen, gleiten, schlafen),
     * Fackel in der Nebenhand im Dunkeln. Die Hauptand bleibt dem Abenteuer (Schwert) und dem Bauen.
     */
    private void body(Buddy b, Mannequin m, Player p) {
        if (listenerTicks % 5 != 0) {
            return;
        }
        Vector v = m.getVelocity();
        boolean moving = Math.abs(v.getX()) + Math.abs(v.getZ()) > 0.05;
        boolean near = m.getWorld().equals(p.getWorld()) && m.getLocation().distanceSquared(p.getLocation()) < 64;
        com.blackn0va.baumeister.companion.CompanionBody.Pose pose = com.blackn0va.baumeister.companion.CompanionBody
                .pose(new com.blackn0va.baumeister.companion.CompanionBody.Situation(p.isSneaking(), p.isSwimming(),
                        p.isGliding(), p.isSleeping() && near, m.isInWater(), moving, !m.hasGravity()));
        MannequinCompat.pose(m, org.bukkit.entity.Pose.valueOf(pose.name()));
        org.bukkit.inventory.EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            boolean torch = com.blackn0va.baumeister.companion.CompanionBody.torch(
                    m.getEyeLocation().getBlock().getLightLevel(), pose, m.isInWater());
            boolean has = eq.getItemInOffHand().getType() == Material.TORCH;
            if (torch != has) {
                eq.setItemInOffHand(torch ? new ItemStack(Material.TORCH) : null);
            }
        }
    }

    /**
     * Im Stehen nicht stur auf den Spieler starren: alle 3–7 s für 1,5 s ein Blick auf das nächste Lebewesen in 8
     * Blöcken (Mitspieler, Tiere, Dorfbewohner), sonst auf den Spieler.
     */
    private void lookIdle(Buddy b, Mannequin m, Player p) {
        if (listenerTicks >= b.glanceUntil) {
            b.glanceAt = null;
            if (listenerTicks >= b.nextGlance) {
                b.nextGlance = listenerTicks + 60 + java.util.concurrent.ThreadLocalRandom.current().nextInt(80);
                org.bukkit.entity.LivingEntity best = null;
                double bestD = Double.MAX_VALUE;
                for (Entity e : m.getNearbyEntities(8, 4, 8)) {
                    if (e instanceof org.bukkit.entity.LivingEntity le && !(e instanceof Mannequin) && !e.equals(p)
                            && !le.isInvisible()) {
                        double d = e.getLocation().distanceSquared(m.getLocation());
                        if (d < bestD) {
                            bestD = d;
                            best = le;
                        }
                    }
                }
                if (best != null) {
                    b.glanceAt = best.getUniqueId();
                    b.glanceUntil = listenerTicks + 30;
                }
            }
        }
        Entity g = b.glanceAt == null ? null : Bukkit.getEntity(b.glanceAt);
        BuilderNpc.lookAt(m, g instanceof org.bukkit.entity.LivingEntity le && le.isValid() && le.getWorld().equals(
                m.getWorld()) ? le.getEyeLocation() : p.getEyeLocation());
    }

    /** Freude (Levelaufstieg, Fortschritt): kleiner Sprung, grüne Funken, heller Laut – nur wenn sie dabei ist. */
    void cheer(Player p) {
        Buddy b = buddies.get(p.getUniqueId());
        Mannequin m = b == null ? null : entity(b);
        if (m == null || !m.getWorld().equals(p.getWorld()) || m.getLocation().distanceSquared(p.getLocation()) > 400) {
            return;
        }
        if (m.hasGravity() && m.isOnGround()) {
            Vector v = m.getVelocity();
            m.setVelocity(new Vector(v.getX(), CompanionWalk.JUMP, v.getZ()));
        }
        m.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, m.getLocation().add(0, 2.1, 0), 10, 0.4, 0.3, 0.4, 0);
        if (settings.chatter()) {
            p.playSound(m.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.4f, 1.9f);
        }
    }

    /**
     * Nächster Wegpunkt auf dem Weg zu goal (CompanionPath, A*). Neu geplant wird, wenn es noch keinen Weg gibt, das
     * Ziel mehr als 2 Blöcke weitergewandert ist, sie 10 Ticks festhängt oder der Weg 2 s alt ist – höchstens alle 5
     * Ticks, damit die Suche (bis 900 Knoten) den Takt nicht frisst. Ohne Weg: geradeaus wie vorher, nach 40 Ticks
     * Festhängen Teleport (CompanionWalk).
     */
    private Location waypoint(Buddy b, Location cur, Location goal) {
        Player owner = Bukkit.getPlayer(b.owner);
        boolean doors = owner != null && rights.allowed(owner, Ability.BENUTZEN);
        boolean sameWorld = b.trailWorld != null && b.trailWorld.equals(cur.getWorld().getUID());
        Location ol = owner != null ? owner.getLocation() : null;
        com.blackn0va.baumeister.companion.CompanionFollow.Trail live = sameWorld && ol != null
                ? b.trail.ifFresh(ol.getX(), ol.getY(), ol.getZ()) : null;
        double[] t = b.planner.next(pathBlocks(cur.getWorld(), doors), cur.getX(), cur.getY(), cur.getZ(),
                goal.getX(), goal.getY(), goal.getZ(), live, listenerTicks, b.walk.stuck());
        CompanionPath.Node n = b.planner.waypoint();
        if (n != null) {
            openDoorAhead(b, cur.getWorld(), n);
        }
        return new Location(cur.getWorld(), t[0], t[1], t[2]);
    }

    /**
     * Welt für die Wegsuche. Zäune, Mauern und Tore sind 1,5 hoch: weder Boden noch Stufe (Sprung reicht nicht). Lava,
     * Feuer, Pulverschnee, Spinnweben und Beerensträucher meidet sie, obwohl sie „passierbar“ sind.
     */
    private static CompanionPath.Blocks pathBlocks(World w) {
        return pathBlocks(w, false);
    }

    /** doors: geschlossene Holztüren und Tore zählen als Durchgang (Vera macht sie auf, openDoorAhead). */
    private static CompanionPath.Blocks pathBlocks(World w, boolean doors) {
        return new CompanionPath.Blocks() {
            private Material type(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
                    return Material.BEDROCK;
                }
                return w.getBlockAt(x, y, z).getType();
            }

            private boolean tall(Material t) {
                return org.bukkit.Tag.FENCES.isTagged(t) || org.bukkit.Tag.WALLS.isTagged(t)
                        || org.bukkit.Tag.FENCE_GATES.isTagged(t);
            }

            @Override
            public boolean solid(int x, int y, int z) {
                Material t = type(x, y, z);
                return t.isSolid() && !tall(t);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
                Material t = w.getBlockAt(x, y, z).getType();
                if (t == Material.LAVA || t == Material.FIRE || t == Material.SOUL_FIRE || t == Material.POWDER_SNOW
                        || t == Material.COBWEB || t == Material.SWEET_BERRY_BUSH || t == Material.MAGMA_BLOCK) {
                    return false;
                }
                if (doors && door(t)) {
                    return true;
                }
                return w.getBlockAt(x, y, z).isPassable();
            }

            @Override
            public boolean water(int x, int y, int z) {
                return type(x, y, z) == Material.WATER;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return org.bukkit.Tag.CLIMBABLE.isTagged(type(x, y, z));
            }
        };
    }

    /**
     * Laufen (Schwerkraft an, beweglich) oder schweben (wie vor 2026-11-06)? Laufen nur, wenn der Spieler nicht fliegt
     * und das Ziel auf festem Boden oder im Wasser liegt. Schaltet die Entity nur bei einem Wechsel um. Fällt sie unter
     * die Welt, holt der Teleport sie zurück: Das Herausfallen töten sie trotz setInvulnerable.
     */
    private boolean walkMode(Mannequin m, Player p, Location goal) {
        org.bukkit.block.Block at = goal.getBlock();
        boolean walk = !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()
                && (at.getType() == Material.WATER || at.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid())
                && m.getLocation().getY() > m.getWorld().getMinHeight() + 1;
        if (m.hasGravity() != walk) {
            m.setGravity(walk);
            m.setImmovable(!walk);
            if (!walk) {
                m.setVelocity(new Vector());
            }
        }
        return walk;
    }

    private void forgetOwner(UUID id) {
        owners.remove(id);
        if (inGame != null) {
            inGame.forget(id);
        }
    }

    /**
     * Standpunkt des Spielers für die Spur merken – nur wo er wirklich steht oder schwimmt (Boden unter den Füßen
     * serverseitig geprüft, nicht das vom Client gemeldete isOnGround). Fliegen, Gleiten und Reiten zählen nicht: Dort
     * könnte Vera nicht hin.
     */
    private void remember(Buddy b, Player p) {
        if (!b.owner.equals(p.getUniqueId()) || p.isFlying() || p.isGliding() || p.isInsideVehicle()) {
            return;
        }
        Location l = p.getLocation();
        if (!standing(p)) {
            return;
        }
        UUID world = l.getWorld().getUID();
        if (!world.equals(b.trailWorld)) {
            b.trail.clear();
            b.trailWorld = world;
        }
        b.trail.record(l.getX(), l.getY(), l.getZ());
    }

    /**
     * Steht der Spieler (für die Spur)? Vorher nur „Block 0,1 unter der Fußmitte ist fest“ – auf Schneeschichten, an
     * Blockkanten (Fußmitte über Luft) und auf manchen flachen Blöcken nie; die Spur brach ab, Vera blieb am letzten
     * Punkt stehen (Log 2026-11-07 17:15, siehe CompanionFollow). Jetzt zusätzlich die Bodenmeldung des Clients
     * (isOnGround) und die vier Ecken der Füße. Dass ein Client isOnGround fälschen kann, ist hier egal: Es geht nur um
     * Veras Brotkrumen, um keinen Schutz.
     */
    @SuppressWarnings("deprecation")
    private static boolean standing(Player p) {
        if (p.isInWater() || p.isOnGround()) {
            return true;
        }
        Location l = p.getLocation();
        double[][] corners = {{0, 0}, {0.29, 0.29}, {-0.29, 0.29}, {0.29, -0.29}, {-0.29, -0.29}};
        for (double[] c : corners) {
            org.bukkit.block.Block below = l.clone().add(c[0], -0.1, c[1]).getBlock();
            if (below.getType().isSolid() || !below.isPassable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Craften aus dem Inventar des Spielers (CompanionCraft): Rezept über Bukkit, genau die Zutaten nehmen, Reste
     * (leere Eimer, Flaschen) zurück, Ergebnis ins Inventar (Überzähliges vor die Füße). Werkbank-Rezepte jeder Art,
     * nur aus schlichten Gegenständen – Benanntes und Verzaubertes nimmt sie nicht. Fehlt etwas, sagt sie was; im
     * Kreativmodus gibt sie stattdessen wie bei „gib mir“ (dort prüft give() die Rechte).
     */
    private void craft(Buddy b, Player p, Material want, int count, boolean materialNamed) {
        Mannequin m = entity(b);
        Map<String, Integer> inventory = new java.util.HashMap<>();
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && !it.getType().isAir() && !it.hasItemMeta()) {
                inventory.merge(it.getType().getKey().getKey(), it.getAmount(), Integer::sum);
            }
        }
        Material made = null;
        int perCraft = 1;
        com.blackn0va.baumeister.companion.CompanionCraft.Plan best = null;
        List<java.util.Set<String>> firstSlots = null;
        for (String id : com.blackn0va.baumeister.companion.CompanionCraft.variants(want.getKey().getKey(),
                materialNamed)) {
            Material mat = Material.matchMaterial(id);
            if (mat == null) {
                continue;
            }
            for (CraftRecipes.Option opt : CraftRecipes.options(mat)) {
                List<java.util.Set<String>> slots = opt.slots();
                if (firstSlots == null) {
                    firstSlots = slots;
                }
                int amount = opt.amount();
                int wanted = count > 0 ? count : mat.getMaxStackSize() == 1 ? 1 : amount;
                com.blackn0va.baumeister.companion.CompanionCraft.Plan plan =
                        com.blackn0va.baumeister.companion.CompanionCraft.plan(slots, inventory,
                                (wanted + amount - 1) / amount);
                if (plan.crafts() > 0 && (best == null || plan.crafts() * amount > best.crafts() * perCraft)) {
                    best = plan;
                    made = mat;
                    perCraft = amount;
                }
            }
            if (best != null) {
                break;  // beste Familie zuerst (Diamant vor Eisen …): die erste, die geht, nehmen
            }
        }
        if (best == null) {
            if (firstSlots == null) {
                say(b, p, "Das stellt man nicht an der Werkbank her – " + itemName(want.getKey().getKey()) + ".");
                return;
            }
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
                int n = count > 0 ? count : want.getMaxStackSize() == 1 ? 1 : Math.min(16, want.getMaxStackSize());
                if (give(b, p, want.getKey().getKey(), n)) {
                    say(b, p, "Im Kreativmodus brauchst du keine Zutaten – hier!");
                }
                return;
            }
            List<String> lack = new ArrayList<>();
            for (java.util.Set<String> slot : com.blackn0va.baumeister.companion.CompanionCraft.missing(firstSlots,
                    inventory)) {
                lack.add(itemName(slot.stream().sorted().findFirst().orElse("?"))
                        + (slot.size() > 1 ? " (o. ä.)" : ""));
            }
            say(b, p, "Dafür fehlt dir: " + String.join(", ", new java.util.LinkedHashSet<>(lack)) + ".");
            return;
        }
        for (Map.Entry<String, Integer> e : best.use().entrySet()) {
            Material mat = Material.matchMaterial(e.getKey());
            Map<Integer, org.bukkit.inventory.ItemStack> left = p.getInventory().removeItem(
                    new org.bukkit.inventory.ItemStack(mat, e.getValue()));
            if (!left.isEmpty()) {
                // Kann nicht passieren (gezählt aus demselben Inventar im selben Tick) – fail-closed melden.
                LOG.warning(() -> "begleiter | craften_zutat_fehlt | spieler=" + p.getName() + " zutat=" + e.getKey());
            }
            Material rest = mat.getCraftingRemainingItem();
            if (rest != null && !rest.isAir()) {
                hand(p, new org.bukkit.inventory.ItemStack(rest, e.getValue()));
            }
        }
        int total = best.crafts() * perCraft;
        hand(p, new org.bukkit.inventory.ItemStack(made, total));
        if (m != null) {
            m.swingMainHand();
            m.getWorld().playSound(m.getLocation(), Sound.BLOCK_CRAFTER_CRAFT, 0.8f, 1.1f);
        }
        List<String> parts = new ArrayList<>();
        best.use().forEach((id, n) -> parts.add(n + " " + itemName(id)));
        String madeName = itemName(made.getKey().getKey());
        Material finalMade = made;
        LOG.info(() -> "begleiter | craften | spieler=" + p.getName() + " item=" + finalMade.getKey().getKey()
                + " anzahl=" + total);
        say(b, p, "Hier: " + total + " " + madeName + " – aus " + String.join(", ", parts) + ".");
    }

    /** Ins Inventar, was nicht passt, vor die Füße. */
    private static void hand(Player p, org.bukkit.inventory.ItemStack items) {
        for (org.bukkit.inventory.ItemStack over : p.getInventory().addItem(items).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), over);
        }
    }

    /** Deutscher Name aus ItemNames, sonst die ID mit Leerzeichen. */
    private static String itemName(String id) {
        return com.blackn0va.baumeister.plan.ItemNames.german(id).orElse(id.replace('_', ' '));
    }

    /**
     * Mitfahren (CompanionRide): Sitzt der Besitzer in einem Fahrzeug mit freiem Platz (Boot, Kamel, Glücklicher
     * Ghast), steigt Vera dazu; steigt er aus oder wechselt, steigt sie aus. true = sie fährt mit, der Takt macht sonst
     * nichts – jeder Teleport (Blick, Aufholen) würfe sie aus dem Sitz.
     */
    private boolean rideAlong(Buddy b, Mannequin m, Player p) {
        if (!b.owner.equals(p.getUniqueId())) {
            return false;
        }
        org.bukkit.entity.Entity seat = m.getVehicle();
        org.bukkit.entity.Entity his = p.getVehicle();
        if (seat != null) {
            if (seat.equals(his)) {
                return true;
            }
            m.leaveVehicle();
            LOG.info(() -> "begleiter | aussteigen | spieler=" + p.getName());
            return false;
        }
        if (his == null || !m.getWorld().equals(p.getWorld())) {
            return false;
        }
        String type = his.getType().getKey().getKey();
        double distance = m.getLocation().distance(his.getLocation());
        if (!com.blackn0va.baumeister.companion.CompanionRide.board(type, his.getPassengers().size(), distance, false)) {
            return false;
        }
        m.setVelocity(new Vector());
        if (!his.addPassenger(m)) {
            return false;
        }
        LOG.info(() -> "begleiter | mitfahren | spieler=" + p.getName() + " fahrzeug=" + type);
        return true;
    }

    /**
     * Ziel beim Folgen (CompanionFollow): auf Veras Seite, wenn dort Platz ist und keine Wand dazwischen – sonst auf
     * seiner Spur. Schwimmend wie vorher auf seiner Höhe (beside); fliegend, gleitend oder reitend hinter ihm in der Luft.
     */
    private Location follow(Buddy b, Player p, Mannequin m) {
        if (p.isInWater()) {
            return beside(p);
        }
        if (p.isGliding() || p.isFlying() || p.isInsideVehicle()) {
            // In der Luft: hinter ihm auf seiner Höhe. Vorher fiel das Ziel auf die Bodenspur zurück – Vera flog dorthin,
            // wo er abgehoben hatte.
            Location l = p.getLocation();
            Vector back = l.getDirection().multiply(-settings.distance());
            return l.clone().add(back).add(0, 0.3, 0);
        }
        Location l = p.getLocation();
        boolean sameWorld = l.getWorld().getUID().equals(b.trailWorld);
        // Der Spieler selbst ist das Ziel, anhalten tut die Hysterese bei 3 Blöcken (CompanionFollow.goalPlayer).
        // Gemessen 2026-11-07 (FollowProbe, 11 Strecken + Natur-Welt): überall kleinster Abstand; vorher „auf Veras
        // Seite“ (CompanionFollow.goal) lag das Ziel in den Bergen bei ihr selbst, sie hing 5–6 Blöcke zurück.
        double[] g = com.blackn0va.baumeister.companion.CompanionFollow.goalPlayer(pathBlocks(l.getWorld()),
                l.getX(), l.getY(), l.getZ(), sameWorld ? b.trail : null);
        return new Location(l.getWorld(), g[0], g[1], g[2]);
    }

    /**
     * Diagnose (Wunsch 2026-11-07 – im Log stand davon nichts): Entfernt sie sich
     * 10 Ticks in Folge vom Besitzer, obwohl sie hinterherläuft, eine Zeile mit Weg-Modus, Abständen und Spurlänge –
     * höchstens alle 5 s. So steht der nächste echte Fall im Log, statt nachgestellt werden zu müssen.
     */
    private void watchAway(Buddy b, Mannequin m, Player p, Location goal, double lag) {
        b.awayTicks = lag > settings.distance() + 2 && lag > b.lastLag + 0.03 ? b.awayTicks + 1 : 0;
        b.lastLag = lag;
        long now = System.currentTimeMillis();
        if (b.awayTicks >= 10 && now - b.awayLogged > 5_000) {
            b.awayLogged = now;
            Location c = m.getLocation();
            LOG.info(() -> String.format(Locale.ROOT, "begleiter | weg_vom_spieler | spieler=%s modus=%s abstand=%.1f "
                    + "ziel_abstand=%.1f vera=%d %d %d spieler_pos=%d %d %d spur=%d", p.getName(), b.planner.mode(),
                    lag, c.distance(goal), c.getBlockX(), c.getBlockY(), c.getBlockZ(), p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(), p.getLocation().getBlockZ(), b.trail.size()));
        }
    }

    /**
     * Wohin teleportieren, wenn Laufen nicht reicht: auf die Spur hinter dem Spieler – dort stand er eben, dort ist
     * Platz. Vorher genau aufs Ziel, und das lag in Gängen oft im Fels (FollowProbe 2026-11-07: 120 Ticks im Stein).
     */
    private Location landing(Buddy b, Location goal) {
        Player p = Bukkit.getPlayer(b.owner);
        if (p != null && goal.getWorld() != null && goal.getWorld().getUID().equals(b.trailWorld)) {
            Location l = p.getLocation();
            // Nur eine Spur, die noch mitschreibt – eine abgebrochene führte zurück an den Start.
            if (b.trail.fresh(l.getX(), l.getY(), l.getZ())) {
                double[] t = b.trail.behind(settings.distance());
                if (t != null) {
                    return new Location(goal.getWorld(), t[0], t[1], t[2]);
                }
            }
            if (p.getWorld().equals(goal.getWorld()) && standing(p) && !p.isFlying() && !p.isInsideVehicle()) {
                return beside(p);
            }
        }
        return goal.clone();
    }

    /**
     * Schrittweite im Flug (Elytren, Kreativflug): so schnell wie der Spieler in diesem Tick, plus Aufholen, wenn sie
     * zurückliegt. Vorher fest 0,45 Block je Tick – mit Elytren (1,5–3 Blöcke je Tick, Rakete mehr) fiel sie sofort
     * zurück und sprang nur noch per Teleport hinterher (ab 32 Blöcken in stepToward). Gemessen wird die Bewegung des
     * Spielers selbst (Ort von Tick zu Tick): Player.getVelocity zeigt auf dem Server nicht, was der Client fliegt.
     */
    private double airStep(Buddy b, Player p, double behind) {
        Location now = p.getLocation();
        double speed = 0;
        if (b.prevOwnerPos != null && b.prevOwnerPos.getWorld().equals(now.getWorld())) {
            speed = Math.min(8.0, b.prevOwnerPos.distance(now));
        }
        return Math.max(STEP, Math.min(10.0, speed * 1.1 + Math.max(0, behind - 3) * 0.2));
    }

    /** Neben dem Spieler, schräg hinter ihm, auf seiner Höhe. */
    private Location beside(Player p) {
        Location l = p.getLocation();
        Vector back = l.getDirection().setY(0);
        if (back.lengthSquared() < 1.0e-6) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-settings.distance());
        Vector side = new Vector(-back.getZ(), 0, back.getX()).normalize().multiply(1.2);
        Location at = l.clone().add(back).add(side);
        // Schwimmt der Spieler, bleibt das Ziel auf seiner Höhe: Wasser ist „passierbar“, ground() fände sonst den
        // Seegrund, und Vera liefe unten statt neben ihm zu schwimmen.
        at.setY(p.isInWater() ? l.getY() : ground(at, l.getY()));
        return at;
    }

    /**
     * Stehhöhe an at: auf dem obersten tragenden Block von bis zu 2 über bis 4 unter der Spielerhöhe, mit zwei Blöcken
     * Luft darüber. Vorher stand Vera immer auf Spielerhöhe – neben einem Hang oder einer Grube schwebte sie in der
     * Luft oder steckte im Boden (Gedächtnis „Offen“ 2026-11-04: „Vera schwebt“). Ohne passenden Platz bleibt es bei
     * der Spielerhöhe; dort steht dann kein Boden, und walkMode lässt sie wie früher schweben statt zu fallen.
     */
    static double ground(Location at, double playerY) {
        World w = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int top = (int) Math.floor(playerY) + 2;
        if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return playerY;
        }
        for (int y = top; y >= top - 6; y--) {
            org.bukkit.block.Block floor = w.getBlockAt(x, y - 1, z);
            if (floor.getType().isSolid() && w.getBlockAt(x, y, z).isPassable()
                    && w.getBlockAt(x, y + 1, z).isPassable()) {
                return y;
            }
        }
        return playerY;
    }

    private void say(Buddy b, Player p, String text) {
        b.lastReplyNanos = System.nanoTime();
        b.partner = p.getUniqueId();
        Mannequin m = entity(b);
        String clean = Texts.clean(text, CompanionReply.MAX_SAY);
        Texts.say(settings.name(), m != null ? m.getLocation() : p.getLocation(), p, clean);
        chatter(p, m, clean);
        if (m != null) {
            MannequinCompat.description(m, Texts.clean(clean, 60));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Mannequin now = entity(b);
                if (now != null && !b.thinking) {
                    MannequinCompat.description(now, "Begleiter von " + ownerName(b));
                }
            }, 100L);
        }
        sayAloud(b, p, clean);
    }

    /**
     * Plapperton (Wunsch 2026-11-06): ein kurzer Allay-Laut je Satz, Tonhöhe nach Stimmung
     * (CompanionMood). Nur der angesprochene Spieler hört ihn (Player.playSound), leise – die Stimme bleibt vorn.
     */
    private void chatter(Player p, Mannequin m, String text) {
        if (!settings.chatter()) {
            return;
        }
        com.blackn0va.baumeister.companion.CompanionMood.Mood mood = com.blackn0va.baumeister.companion.CompanionMood
                .of(text);
        Sound s = mood == com.blackn0va.baumeister.companion.CompanionMood.Mood.CALM
                ? Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM : Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM;
        p.playSound(m != null ? m.getLocation() : p.getLocation(), s, 0.35f, mood.pitch());
    }

    /** Server-Stimme setzen (BaumeisterPlugin); null = nur die App. */
    void useLocalVoice(LocalVoice v) {
        this.local = v;
    }

    /** Simple Voice Chat im Spiel (für den Baumeister, der dieselbe Stimme bekommt); null = kein Voice Chat. */
    CompanionInGameVoice inGameVoice() {
        return inGame;
    }

    /** Begleiter des Spielers, wenn er gerade gerufen und geladen ist; sonst null (Probe der Stimme). */
    Entity entityOf(Player p) {
        Buddy b = buddies.get(p.getUniqueId());
        if (b == null || b.entity == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(b.entity);
        return e != null && e.isValid() ? e : null;
    }

    /**
     * Vorlesen: Hat der Spieler Simple Voice Chat verbunden, spricht der Begleiter IM SPIEL aus seiner Position (Stimme
     * vom Server oder vom Host, Piper). Sonst – oder wenn das scheitert – liest die App auf dem PC des Spielers vor
     * (/sagen).
     */
    private void sayAloud(Buddy b, Player p, String text) {
        // Server-Stimme (Piper auf dem Server) vor der App, wenn sprache.quelle=server – oder wenn keine App da ist.
        boolean server = local != null && (local.settings().preferServer() || voice == null);
        if (voice == null && local == null) {
            return;
        }
        if (inGame == null || !inGame.connected(p.getUniqueId())) {
            if (voice != null) {
                voice.say(p.getName(), text);
            }
            return;
        }
        String name = p.getName();
        UUID addressee = p.getUniqueId();
        int epoch = b.voiceEpoch;
        // Satz für Satz (SpeechChunks): Piper rechnet das nächste Stück, während das vorige im Spiel läuft – der erste
        // Ton kommt nach einem Satz statt nach der ganzen Antwort. Fällt der Spieler ihr ins Wort (voiceInterrupted),
        // steigt voiceEpoch, und alle noch nicht gesprochenen Stücke dieser Antwort entfallen.
        for (String chunk : com.blackn0va.baumeister.companion.SpeechChunks.split(text)) {
            b.voiceChain = b.voiceChain.thenCompose(done -> b.voiceEpoch != epoch
                    ? CompletableFuture.completedFuture(java.util.Optional.<short[]>empty())
                    : server ? local.voice(chunk) : voice.voice(name, chunk)).thenAccept(pcm ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (b.voiceEpoch != epoch) {
                            return;
                        }
                        Mannequin m = entity(b);
                        if ((pcm.isEmpty() || m == null || !inGame.play(m, addressee, pcm.get())) && voice != null) {
                            voice.say(name, chunk);  // Host ohne Stimme oder Voice-Chat nicht bereit: wenigstens in der App
                        }
                    })).exceptionally(err -> {
                        // voice() wirft nie (CompanionVoiceLink); falls doch, darf die Kette nicht für immer stehen.
                        LOG.info(() -> "begleiter | stimme_fehler | grund=" + err.getClass().getSimpleName());
                        return null;
                    });
        }
    }

    /**
     * „Reparier meine Schaltung“ (Wunsch 2026-11-06). Nur die sicheren Umbauten aus
     * RedstoneReader (Verstärker umdrehen, Verstärker am Ende eines aufgebrauchten Signals) – gemessen in
     * probes/redstone/FixProbe: beide Lampen danach an. Rechte wie beim Selberbauen (BlockPlaceEvent, ASVS V4.1.3:
     * Vera baut nie mehr, als der Spieler dürfte). 1,5 s später prüft sie nach; hat ein Umbau den Fehler nicht
     * behoben, baut sie ihn zurück. „Mach das rückgängig“ nimmt alles zurück.
     */
    private void repairRedstone(Buddy b, Player p, com.blackn0va.baumeister.redstone.RedstoneReader.Report r) {
        String verdict = com.blackn0va.baumeister.redstone.RedstoneReader.verdict(r, RedstoneScan::compass, 220);
        if (adventure.fair(p)) {
            say(b, p, "Im Abenteuer baust du selbst um. " + verdict);
            return;
        }
        if (r.findings().stream().noneMatch(f -> f.fix() != null)) {
            say(b, p, verdict + (r.findings().stream().anyMatch(f -> !f.hint())
                    ? " Das baue ich nicht von selbst um – da müsste ich raten, was du vorhast." : ""));
            return;
        }
        World w = p.getWorld();
        List<com.blackn0va.baumeister.redstone.RedstoneRepair.Applied> done =
                com.blackn0va.baumeister.redstone.RedstoneRepair.apply(w, r.findings(), blk -> mayBuild(p, blk));
        if (done.isEmpty()) {
            say(b, p, "Hier darfst du nicht bauen – deshalb baue ich nichts um.");
            return;
        }
        b.undo = done.stream().map(com.blackn0va.baumeister.redstone.RedstoneRepair.Applied::before).toList();
        b.undoWorld = w.getUID();
        LOG.info(() -> "begleiter | redstone_umbau | spieler=" + p.getName() + " umbauten=" + done.size());
        say(b, p, String.join(" ", done.stream().map(a -> a.finding().fixText()).toList()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !p.getWorld().equals(w)) {
                return;
            }
            com.blackn0va.baumeister.redstone.RedstoneReader.Report after = RedstoneScan.aroundPlayer(p);
            List<String> back = new ArrayList<>();
            for (com.blackn0va.baumeister.redstone.RedstoneRepair.Applied a : done) {
                // In der Nähe statt genau dort: Der Verstärker kann einen Block vor dem gemeldeten Staub stehen.
                boolean still = after.findings().stream().anyMatch(f -> !f.hint()
                        && Math.abs(f.pos().x() - a.finding().pos().x()) + Math.abs(f.pos().y() - a.finding().pos().y())
                        + Math.abs(f.pos().z() - a.finding().pos().z()) <= 1);
                if (still) {
                    com.blackn0va.baumeister.redstone.RedstoneRepair.revert(a);
                    back.add(com.blackn0va.baumeister.redstone.RedstoneReader.at(a.finding().pos()));
                }
            }
            LOG.info(() -> "begleiter | redstone_umbau_geprüft | spieler=" + p.getName() + " zurückgebaut="
                    + back.size());
            say(b, p, back.isEmpty() ? "Fertig – läuft jetzt. "
                    + com.blackn0va.baumeister.redstone.RedstoneReader.verdict(after, RedstoneScan::compass, 240)
                    : "Bei " + String.join(", ", back) + " hat der Umbau nicht geholfen – ich habe ihn zurückgebaut.");
        }, 30L);
    }

    /**
     * „Was macht diese Schaltung?“: Hebel durchschalten (RedstoneLab, gemessen 4/4 Gatter richtig), Formel je Ausgang
     * sagen und ins Gespräch legen, damit Folgefragen („und wenn ich B umlege?“) sie kennen. Hebel nur mit dem Recht,
     * sie selbst zu benutzen (PlayerInteractEvent: WorldGuard „use“, Grundstücke).
     */
    private void labRedstone(Buddy b, Player p) {
        if (b.lab) {
            say(b, p, "Ich probiere die Schaltung gerade noch aus – einen Moment.");
            return;
        }
        com.blackn0va.baumeister.redstone.RedstoneReader.Report r = RedstoneScan.aroundPlayer(p);
        if (r.empty()) {
            say(b, p, "Ich sehe hier keine Redstone-Schaltung. Stell dich näher dran oder schau sie an.");
            return;
        }
        com.blackn0va.baumeister.redstone.RedstoneReader.Circuit c = pickCircuit(p, r);
        String refused = com.blackn0va.baumeister.redstone.RedstoneLab.start(plugin, p.getWorld(), c,
                blk -> mayUse(p, blk), true, res -> {
                    b.lab = false;
                    if (!p.isOnline()) {
                        return;
                    }
                    if (res.error() != null) {
                        say(b, p, res.error());
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (com.blackn0va.baumeister.redstone.RedstoneExperiment.Output o : res.outputs()) {
                        sb.append(sb.isEmpty() ? "" : " · ").append(o.name()).append(": ").append(o.formula())
                                .append(o.memory() ? " (merkt sich etwas – ein Speicher)" : "");
                    }
                    String legend = String.join(", ", res.inputLabels()) + (res.unused().isEmpty() ? ""
                            : ". Ohne Wirkung: " + String.join(", ", res.unused()));
                    LOG.info(() -> "begleiter | redstone_ausprobiert | spieler=" + p.getName() + " schritte="
                            + res.steps() + " ms=" + res.millis() + " ergebnis=" + Texts.clean(sb + " | " + legend, 300));
                    remember(b, LlmClient.message("user", "[Redstone ausprobiert] " + sb + ". " + legend));
                    JsonObject ack = new JsonObject();
                    ack.addProperty("say", "Ausprobiert.");
                    ack.add("actions", new com.google.gson.JsonArray());
                    remember(b, LlmClient.message("assistant", ack.toString()));
                    say(b, p, "Ausprobiert! " + sb);
                    say(b, p, legend + ".");
                });
        if (refused != null) {
            say(b, p, refused);
            return;
        }
        b.lab = true;
        say(b, p, "Ich probiere die Schaltung aus – ich lege die Hebel einmal durch und stelle sie danach zurück.");
    }

    /** Die Schaltung, auf die der Spieler schaut; sonst die größte. */
    private static com.blackn0va.baumeister.redstone.RedstoneReader.Circuit pickCircuit(Player p,
            com.blackn0va.baumeister.redstone.RedstoneReader.Report r) {
        org.bukkit.block.Block look = p.getTargetBlockExact(8);
        if (look != null) {
            com.blackn0va.baumeister.plan.Pos at = new com.blackn0va.baumeister.plan.Pos(look.getX(), look.getY(),
                    look.getZ());
            for (com.blackn0va.baumeister.redstone.RedstoneReader.Circuit c : r.circuits()) {
                if (c.parts().stream().anyMatch(x -> x.pos().equals(at))) {
                    return c;
                }
            }
        }
        return r.circuits().get(0);
    }

    /** Darf p hier einen Block setzen? Wie BuildJob.allowed: Ereignis nur fragen, nicht ausführen. */
    // ---------------------------------------------------------------- Charakter (CompanionCharacter)

    /** Gewählter Charakter je Spieler (begleiter-charakter.yml); ohne Eintrag ANPASSEN. */
    private final Map<UUID, com.blackn0va.baumeister.companion.CompanionCharacter.Kind> characters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, com.blackn0va.baumeister.companion.CompanionCharacter.Tone> tones =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Letzter Spruch je Spieler und Anlass (banter). */
    private final Map<String, Long> banterAt = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean charactersLoaded;

    private java.nio.file.Path characterFile() {
        return plugin.getDataFolder().toPath().resolve("begleiter-charakter.yml");
    }

    private void loadCharacters() {
        charactersLoaded = true;
        java.nio.file.Path f = characterFile();
        if (!java.nio.file.Files.exists(f)) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration y =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile());
        for (String k : y.getKeys(false)) {
            com.blackn0va.baumeister.companion.CompanionCharacter.Kind kind =
                    com.blackn0va.baumeister.companion.CompanionCharacter.Kind.parse(y.getString(k));
            try {
                if (kind != null) {
                    characters.put(UUID.fromString(k), kind);
                }
            } catch (IllegalArgumentException e) {
                // Komfort, kein Schutz: kaputte Zeile überspringen, der Spieler hat dann wieder ANPASSEN.
                LOG.warning(() -> "begleiter | charakter_zeile_verworfen | schlüssel=" + k);
            }
        }
    }

    private void saveCharacters() {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        characters.forEach((id, k) -> y.set(id.toString(), k.name().toLowerCase(Locale.ROOT)));
        java.nio.file.Path f = characterFile();
        try {
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".neu");
            java.nio.file.Files.writeString(tmp, y.saveToString());
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            LOG.warning(() -> "begleiter | charakter_schreiben_fehlgeschlagen | datei=" + f + " grund=" + e);
        }
    }

    com.blackn0va.baumeister.companion.CompanionCharacter.Kind character(UUID player) {
        if (!charactersLoaded) {
            loadCharacters();
        }
        return characters.getOrDefault(player, com.blackn0va.baumeister.companion.CompanionCharacter.Kind.ANPASSEN);
    }

    /** Ist Vera zu p gerade frech (Charakter, Ton der letzten Sätze, Fähigkeit FRECH im Admin-Buch)? */
    boolean cheeky(Player p) {
        return com.blackn0va.baumeister.companion.CompanionCharacter.cheeky(character(p.getUniqueId()),
                tones.get(p.getUniqueId()), rights.allowed(p, Ability.FRECH));
    }

    private String line(Player p, com.blackn0va.baumeister.companion.CompanionCharacter.Line l) {
        return com.blackn0va.baumeister.companion.CompanionCharacter.line(l, cheeky(p),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
    }

    /** /bm begleiter charakter &lt;art&gt; und „Vera, sei frech“. Frech nur mit der Fähigkeit FRECH. */
    void setCharacter(Player p, com.blackn0va.baumeister.companion.CompanionCharacter.Kind k) {
        setCharacter(p, k, true);
    }

    /** announce false: nur umstellen (Modell-Aktion, die Antwort steht schon in „say“); verboten wird trotzdem gesagt. */
    void setCharacter(Player p, com.blackn0va.baumeister.companion.CompanionCharacter.Kind k, boolean announce) {
        String text;
        if (k != com.blackn0va.baumeister.companion.CompanionCharacter.Kind.KUMPEL
                && !rights.allowed(p, Ability.FRECH)) {
            text = "Frech sein darf ich hier nicht – das hat der Admin abgestellt.";
        } else {
            character(p.getUniqueId());
            characters.put(p.getUniqueId(), k);
            saveCharacters();
            LOG.info(() -> "begleiter | charakter | spieler=" + p.getName() + " art=" + k);
            text = switch (k) {
                case KUMPEL -> "Alles klar, ab jetzt bin ich ganz lieb.";
                case FRECH -> "Oh, endlich darf ich ehrlich sein. Das wird ein Spaß.";
                case ANPASSEN -> "Okay – wie du mir, so ich dir.";
            };
            if (!announce) {
                return;
            }
        }
        Buddy b = buddies.get(p.getUniqueId());
        if (b != null && entity(b) != null) {
            say(b, p, text);
        } else {
            Texts.info(p, settings.name() + ": " + text);
        }
    }

    /**
     * Spruch zu einem Ereignis (Tod, Sturz, Diamanten, Nacht, Langeweile …), wie ein Mitspieler ihn macht – höchstens
     * alle cooldownMs je Spieler und Anlass, nur wenn Vera dabei ist und nicht gerade nachdenkt.
     */
    void banter(Player p, com.blackn0va.baumeister.companion.CompanionCharacter.Line l, long cooldownMs) {
        if (settings == null || !settings.enabled()) {
            return;
        }
        Buddy b = buddies.get(p.getUniqueId());
        Mannequin m = b == null ? null : entity(b);
        if (m == null || b.thinking || !m.getWorld().equals(p.getWorld())
                || m.getLocation().distanceSquared(p.getLocation()) > 24 * 24) {
            return;
        }
        String key = p.getUniqueId() + ":" + l;
        long now = System.currentTimeMillis();
        Long last = banterAt.get(key);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        banterAt.put(key, now);
        say(b, p, line(p, l));
    }

    /** Schaden (BaumeisterListener): Sturz oder kaum noch Leben. */
    void hurt(Player p, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause, double damage) {
        if (p.getHealth() - damage <= 6 && p.getHealth() - damage > 0) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.LOW_HEALTH, 60_000L);
        } else if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && damage >= 3) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.FALL, 90_000L);
        }
    }

    /** Block abgebaut (BaumeisterListener): Diamanten und Antiker Schrott sind einen Spruch wert. */
    void mined(Player p, Material m) {
        if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.DIAMOND, 60_000L);
        }
    }

    /** Hunger gesunken (BaumeisterListener). */
    void hungry(Player p, int food) {
        if (food <= 6 && p.getGameMode() == GameMode.SURVIVAL) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.HUNGRY, 5 * 60_000L);
        }
    }

    // ---------------------------------------------------------------- Benutzen (CompanionUse)

    /** Weiteste Entfernung, aus der Vera etwas bedient (wie die Reichweite eines Spielers). */
    private static final double USE_REACH = 3.5;
    /** So lange (Ticks) läuft sie zu einem Ziel, dann gibt sie auf. */
    private static final int USE_TICKS = 200;

    /** „Vera, mach die Tür auf“: Ziel finden, Rechte prüfen, hinlaufen oder gleich tun. */
    private void use(Buddy b, Player p, com.blackn0va.baumeister.companion.CompanionUse.Use u) {
        if (!rights.allowed(p, Ability.BENUTZEN)) {
            say(b, p, "Dinge benutzen darf ich hier nicht – das hat der Admin abgestellt.");
            return;
        }
        org.bukkit.block.Block target = useTarget(p, u.target());
        if (target == null) {
            say(b, p, line(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.NOTHING_THERE));
            return;
        }
        if (com.blackn0va.baumeister.companion.CompanionUse.needsRedstone(target.getType().name())) {
            say(b, p, line(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.NEEDS_REDSTONE));
            return;
        }
        if (!mayUse(p, target)) {
            say(b, p, line(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.NOT_ALLOWED));
            return;
        }
        Mannequin m = entity(b);
        if (m != null && m.getWorld().equals(target.getWorld())
                && m.getLocation().distance(target.getLocation().add(0.5, 0.5, 0.5)) <= USE_REACH) {
            performUse(b, m, p, target, u, true);
            return;
        }
        b.useAt = target;
        b.use = u;
        b.useUntil = listenerTicks + USE_TICKS;
        LOG.info(() -> "begleiter | benutzen_hinlaufen | spieler=" + p.getName() + " ziel=" + target.getType());
    }

    /** Was p anschaut (bis 6 Blöcke), sonst das Nächste dieser Art in 6 Blöcken um p. */
    private static org.bukkit.block.Block useTarget(Player p,
            com.blackn0va.baumeister.companion.CompanionUse.Target t) {
        org.bukkit.block.Block look = p.getTargetBlockExact(6);
        if (look != null && com.blackn0va.baumeister.companion.CompanionUse.matches(t, look.getType().name())) {
            return look;
        }
        org.bukkit.block.Block best = null;
        double bestD = Double.MAX_VALUE;
        Location at = p.getLocation();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    org.bukkit.block.Block blk = at.getBlock().getRelative(dx, dy, dz);
                    if (com.blackn0va.baumeister.companion.CompanionUse.matches(t, blk.getType().name())) {
                        double d = blk.getLocation().add(0.5, 0.5, 0.5).distanceSquared(at);
                        if (d < bestD) {
                            bestD = d;
                            best = blk;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Takt: zum Ziel laufen und benutzen. true = sie ist damit beschäftigt (nicht folgen). */
    private boolean walkToUse(Buddy b, Mannequin m, Player p) {
        org.bukkit.block.Block t = b.useAt;
        if (!t.getWorld().equals(m.getWorld()) || listenerTicks > b.useUntil) {
            b.useAt = null;
            say(b, p, line(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.CANT_REACH));
            return false;
        }
        Location c = t.getLocation().add(0.5, 0, 0.5);
        if (m.getLocation().distance(c.clone().add(0, 0.5, 0)) <= USE_REACH - 1) {
            org.bukkit.block.Block at = t;
            b.useAt = null;
            performUse(b, m, p, at, b.use, true);
            return true;
        }
        move(b, m, p, c, c.clone().add(0, 0.5, 0), CompanionWalk.SPEED);
        return true;
    }

    /**
     * Wirklich benutzen. Türen über Openable (die zweite Türhälfte zieht Minecraft beim Block-Update selbst nach),
     * Hebel und Knöpfe über RedstoneLab.toggle (mit Anstupsen, sonst schaltet nur die Optik), Glocke und Notenblock
     * über ihren Blockzustand. talk: danach etwas sagen.
     */
    private void performUse(Buddy b, Mannequin m, Player p, org.bukkit.block.Block t,
            com.blackn0va.baumeister.companion.CompanionUse.Use u, boolean talk) {
        BuilderNpc.lookAt(m, t.getLocation().add(0.5, 0.5, 0.5));
        m.swingMainHand();
        com.blackn0va.baumeister.companion.CompanionCharacter.Line said =
                com.blackn0va.baumeister.companion.CompanionCharacter.Line.USED;
        org.bukkit.block.data.BlockData data = t.getBlockData();
        switch (u.target()) {
            case DOOR, GATE, TRAPDOOR -> {
                if (!(data instanceof org.bukkit.block.data.Openable o)) {
                    return;
                }
                boolean open = u.open() != null ? u.open() : !o.isOpen();
                o.setOpen(open);
                t.setBlockData(o, true);
                t.getWorld().playSound(t.getLocation(), open ? Sound.BLOCK_WOODEN_DOOR_OPEN
                        : Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
                said = com.blackn0va.baumeister.companion.CompanionCharacter.Line.DOOR;
            }
            case LEVER -> {
                boolean on = u.open() != null ? u.open()
                        : !(data instanceof org.bukkit.block.data.Powerable pw && pw.isPowered());
                com.blackn0va.baumeister.redstone.RedstoneLab.toggle(t, on);
                t.getWorld().playSound(t.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, on ? 0.6f : 0.5f);
            }
            case BUTTON -> {
                com.blackn0va.baumeister.redstone.RedstoneLab.toggle(t, true);
                t.getWorld().playSound(t.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1f);
                // Wie im Spiel: Steinknopf 20 Ticks, Holzknopf 30 Ticks gedrückt.
                long hold = t.getType().name().startsWith("STONE") || t.getType().name().startsWith("POLISHED") ? 20L : 30L;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (com.blackn0va.baumeister.companion.CompanionUse.matches(
                            com.blackn0va.baumeister.companion.CompanionUse.Target.BUTTON, t.getType().name())) {
                        com.blackn0va.baumeister.redstone.RedstoneLab.toggle(t, false);
                    }
                }, hold);
            }
            case BELL -> {
                if (t.getState() instanceof org.bukkit.block.Bell bell) {
                    bell.ring(m);
                }
                said = com.blackn0va.baumeister.companion.CompanionCharacter.Line.BELL;
            }
            case NOTE -> {
                // Spigot hat keinen Notenblock-Zustand mit play(): Klang aus Instrument und Note selbst spielen
                // (Tonhöhe 2^((Note−12)/12) wie in Minecraft).
                if (data instanceof org.bukkit.block.data.type.NoteBlock nb) {
                    float pitch = (float) Math.pow(2, (nb.getNote().getId() - 12) / 12.0);
                    t.getWorld().playSound(t.getLocation(), nb.getInstrument().getSound(), 1f, pitch);
                }
                m.getWorld().spawnParticle(Particle.NOTE, t.getLocation().add(0.5, 1.2, 0.5), 3, 0.2, 0.1, 0.2, 1);
                said = com.blackn0va.baumeister.companion.CompanionCharacter.Line.NOTE;
            }
            case CHEST -> {
                if (!(t.getState() instanceof org.bukkit.block.Container box)) {
                    return;
                }
                if (!p.getWorld().equals(t.getWorld()) || p.getLocation().distance(t.getLocation()) > 8) {
                    say(b, p, "Komm näher, dann mach ich sie dir auf.");
                    return;
                }
                p.openInventory(box.getInventory());
            }
            default -> {
                return;
            }
        }
        LOG.info(() -> "begleiter | benutzt | spieler=" + p.getName() + " ziel=" + t.getType() + " bei="
                + t.getX() + " " + t.getY() + " " + t.getZ());
        if (talk) {
            say(b, p, line(p, said));
        }
    }

    /**
     * Steht sie neben dem Spieler herum: nach 90 s ein Spruch aus Langeweile, und mit der Fähigkeit AUSPROBIEREN alle
     * 1–2 Minuten etwas Harmloses in 5 Blöcken anfassen (Glocke, Notenblock, Holztür) – wie ein Mitspieler, der nicht
     * stillsitzen kann. Nie Hebel oder Knöpfe (CompanionUse.harmless).
     */
    private void idle(Buddy b, Mannequin m, Player p) {
        b.idleTicks++;
        if (b.idleTicks == 1800) {
            banter(p, com.blackn0va.baumeister.companion.CompanionCharacter.Line.IDLE, 4 * 60_000L);
        }
        if (b.idleTicks < 400 || listenerTicks < b.nextCuriosity || b.thinking || b.lab
                || !rights.allowed(p, Ability.AUSPROBIEREN)) {
            return;
        }
        b.nextCuriosity = listenerTicks + 1200 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1200);
        List<org.bukkit.block.Block> found = new ArrayList<>();
        org.bukkit.block.Block base = m.getLocation().getBlock();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    org.bukkit.block.Block blk = base.getRelative(dx, dy, dz);
                    if (com.blackn0va.baumeister.companion.CompanionUse.harmless(blk.getType().name()) != null) {
                        found.add(blk);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        org.bukkit.block.Block t = found.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(found.size()));
        com.blackn0va.baumeister.companion.CompanionUse.Target kind =
                com.blackn0va.baumeister.companion.CompanionUse.harmless(t.getType().name());
        if (!mayUse(p, t)) {
            return;
        }
        LOG.info(() -> "begleiter | ausprobiert | spieler=" + p.getName() + " ziel=" + t.getType());
        boolean door = kind == com.blackn0va.baumeister.companion.CompanionUse.Target.DOOR
                || kind == com.blackn0va.baumeister.companion.CompanionUse.Target.TRAPDOOR
                || kind == com.blackn0va.baumeister.companion.CompanionUse.Target.GATE;
        if (door) {
            // Auf und gleich wieder zu – sie hinterlässt nichts anders, als sie es vorfand.
            boolean wasOpen = t.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen();
            performUse(b, m, p, t, new com.blackn0va.baumeister.companion.CompanionUse.Use(kind, !wasOpen), false);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Mannequin now = entity(b);
                if (now != null && t.getBlockData() instanceof org.bukkit.block.data.Openable) {
                    performUse(b, now, p, t, new com.blackn0va.baumeister.companion.CompanionUse.Use(kind, wasOpen),
                            false);
                }
            }, 30L);
            return;
        }
        performUse(b, m, p, t, new com.blackn0va.baumeister.companion.CompanionUse.Use(kind, null), true);
    }

    /** Geschlossene Holztür/Tor am nächsten Wegpunkt (Füße oder Kopf): öffnen, wenn sie darf, und merken. */
    private void openDoorAhead(Buddy b, World w, CompanionPath.Node n) {
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner == null || !rights.allowed(owner, Ability.BENUTZEN)) {
            return;
        }
        for (int dy = 0; dy <= 1; dy++) {
            org.bukkit.block.Block blk = w.getBlockAt(n.x(), n.y() + dy, n.z());
            if (door(blk.getType()) && blk.getBlockData() instanceof org.bukkit.block.data.Openable o && !o.isOpen()
                    && mayUse(owner, blk)) {
                o.setOpen(true);
                blk.setBlockData(o, true);
                w.playSound(blk.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
                b.openedDoors.put(blk, (long) listenerTicks);
                return;
            }
        }
    }

    /** Selbst geöffnete Türen wieder schließen, sobald sie und der Spieler durch sind (oder nach 10 s). */
    private void closeDoors(Buddy b, Mannequin m, Player p) {
        if (b.openedDoors.isEmpty()) {
            return;
        }
        b.openedDoors.entrySet().removeIf(e -> {
            org.bukkit.block.Block d = e.getKey();
            Location c = d.getLocation().add(0.5, 0, 0.5);
            boolean away = !m.getWorld().equals(d.getWorld()) || m.getLocation().distance(c) > 2.2
                    && (!p.getWorld().equals(d.getWorld()) || p.getLocation().distance(c) > 1.5);
            if (!away && listenerTicks - e.getValue() < 200) {
                return false;
            }
            if (d.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen()) {
                o.setOpen(false);
                d.setBlockData(o, true);
                d.getWorld().playSound(d.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
            }
            return true;
        });
    }

    /** Holztür oder Tor (keine Eisentür – die braucht Redstone, keine Falltür – die liegt im Weg anders). */
    private static boolean door(Material t) {
        String n = t.name();
        return (n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE")) && !n.startsWith("IRON_");
    }

    private static boolean mayBuild(Player p, org.bukkit.block.Block blk) {
        org.bukkit.event.block.BlockPlaceEvent e = new org.bukkit.event.block.BlockPlaceEvent(blk, blk.getState(),
                blk.getRelative(org.bukkit.block.BlockFace.DOWN), new ItemStack(Material.REPEATER), p, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(e);
        return !e.isCancelled() && e.canBuild();
    }

    /** Darf p diesen Hebel benutzen? Rechtsklick nur fragen (WorldGuard „use“, Grundstücke). */
    private static boolean mayUse(Player p, org.bukkit.block.Block blk) {
        org.bukkit.event.player.PlayerInteractEvent e = new org.bukkit.event.player.PlayerInteractEvent(p,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, blk, org.bukkit.block.BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(e);
        return e.useInteractedBlock() != org.bukkit.event.Event.Result.DENY;
    }

    /** Ins Wort gefallen (CompanionVoicechat, Hauptthread): restliche Sätze der laufenden Antwort verwerfen. */
    void voiceInterrupted(UUID player) {
        for (Buddy b : buddies.values()) {
            if (b.owner.equals(player) || player.equals(b.partner)) {
                b.voiceEpoch++;
            }
        }
    }

    /** Darf man Vera ins Wort fallen (begleiter.unterbrechen)? Aus dem Netzwerk-Thread gelesen – Settings sind unveränderlich. */
    boolean bargeIn() {
        return settings.bargeIn();
    }

    private static Mannequin entityById(UUID id) {
        if (id == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(id);
        return e instanceof Mannequin m && m.isValid() ? m : null;
    }

    private Mannequin entity(Buddy b) {
        return entityById(b.entity);
    }
}
