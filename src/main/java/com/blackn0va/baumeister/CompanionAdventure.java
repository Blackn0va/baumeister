package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.Adventure;
import com.blackn0va.baumeister.companion.AdventureBook;
import com.blackn0va.baumeister.companion.CompanionChat;
import com.blackn0va.baumeister.companion.CompanionRules;
import com.blackn0va.baumeister.companion.ModeWatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Das Abenteuer im Spiel: Vera begleitet einen Überlebens- oder Hardcore-Lauf in der Farmwelt (Etappen in Adventure,
 * Spielbuch in AdventureBook). Sie erkennt den Fortschritt, sagt das nächste Ziel, zeigt auf Nachfrage den Weg (Bäume,
 * Dorf, Ruinenportal, Netherfestung, Festung, Drache), warnt vor Nacht, Hunger, wenig Leben und Creepern und kämpft im
 * normalen Modus mit.
 *
 * Fair gespielt: Solange ein Lauf in der Farmwelt läuft, gibt Vera nichts, führt keine Befehle aus, teleportiert nicht
 * und lässt den Baumeister nicht bauen (CompanionService fragt fair()). Rechte eines Admins ändern daran nichts – sonst
 * wäre „gib mir Diamanten“ die Abkürzung durch die ganze Geschichte. Erze zeigt sie nie (kein Röntgenblick), nur die
 * Höhe, in der man suchen muss.
 *
 * Nur in den Welten aus begleiter.abenteuer.welten (Vorgabe: die Farmwelt world samt Nether und End). Merkmale zählen
 * nur im Überlebens- oder Abenteuermodus: Im Kreativmodus genommene Gegenstände wären sonst Fortschritt.
 *
 * Threads: Hauptthread; Schreiben des Spielbuchs asynchron (atomar über .neu). Fehler: Lesen/Schreiben fail-open mit
 * Log – das Abenteuer ist Spiel, kein Schutz.
 */
final class CompanionAdventure {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Wie oft Fortschritt und Warnungen geprüft werden (Wanduhr – /tick rate soll Vera nicht hetzen). */
    static final long OBSERVE_NANOS = 3_000_000_000L;
    /** Takt eines Schlags von Vera; etwa ein Eisenschwert. */
    static final long STRIKE_NANOS = 700_000_000L;
    static final double STRIKE_DAMAGE = 5.0;
    static final double STRIKE_REACH = 3.2;
    /** So weit um den Spieler sucht Vera Monster. */
    static final double GUARD = 8;
    private static final long CREEPER_WARN_NANOS = 20_000_000_000L;
    /** Eisen um Höhe 16, Diamanten um −58 (Erzverteilung seit 1.18). */
    static final int IRON_Y = 16;
    static final int DIAMOND_Y = -58;

    /** Was CompanionAdventure vom Begleiter braucht – CompanionService liefert es. */
    interface Host {
        void say(Player p, String text);

        Mannequin companion(Player p);

        void summon(Player p);

        void trail(Player p, Location target);
    }

    /** Flüchtiger Zustand je Spieler (nicht im Spielbuch). */
    private static final class Watch {
        long lastObserve = Long.MIN_VALUE / 2;
        long lastStrike = Long.MIN_VALUE / 2;
        long lastCreeper = Long.MIN_VALUE / 2;
        long nightDay = -1;
        boolean healthWarned;
        boolean hungerWarned;
        Entity target;
    }

    private final BaumeisterPlugin plugin;
    private final Host host;
    private AdventureBook book = new AdventureBook();
    private final Map<UUID, Watch> watch = new HashMap<>();
    /** Letzter Spielmodus je Spieler – überlebt Start und Ende eines Abenteuers (ModeWatch). */
    private final ModeWatch modes = new ModeWatch();
    /**
     * Ziel immer im Blick: Boss-Leiste oben mit Etappe und Ziel, Füllstand = Fortschritt. Nur sichtbar, solange der
     * Spieler im Abenteuer UND in der Farmwelt ist; anderswo verschwindet sie (die Geschichte gilt dort nicht).
     */
    private final Map<UUID, org.bukkit.boss.BossBar> bars = new HashMap<>();
    private Set<String> worlds = MainWorlds.expand(MainWorlds.DEFAULT, "world");
    private boolean fight = true;

    CompanionAdventure(BaumeisterPlugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    void configure(List<String> worldNames, boolean fightAlong) {
        // „hauptwelt“ = level-name samt Nether und End (MainWorlds); die Welten sind beim Aktivieren geladen.
        this.worlds = MainWorlds.expand(worldNames, MainWorlds.mainName());
        this.fight = fightAlong;
        // Tippfehler in begleiter.abenteuer.welten fielen sonst erst im Spiel auf („gibt es nur in der Farmwelt“).
        // Multiverse lädt Welten nach dem Plugin: geprüft wird einen Moment später.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String w : worlds) {
                if (Bukkit.getWorlds().stream().noneMatch(x -> x.getName().equalsIgnoreCase(w))) {
                    LOG.warning(() -> "abenteuer | welt_fehlt | welt=" + w
                            + " – begleiter.abenteuer.welten prüfen, dort gibt es kein Abenteuer");
                }
            }
        }, 200L);
    }

    boolean allowed(World w) {
        return w != null && worlds.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * Läuft für p gerade ein Abenteuer in seiner Welt UND spielt er im Überlebens-/Abenteuermodus? Dann gilt Fair-Play
     * (kein Geben, keine Befehle …). Im Kreativmodus pausiert das Abenteuer – vorher blieb die Sperre stehen, obwohl
     * der Spieler umgeschaltet hatte (Wunsch 2026-11-04).
     */
    boolean fair(Player p) {
        return book.run(p.getUniqueId()).isPresent() && allowed(p.getWorld()) && playing(p);
    }

    static boolean playing(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    boolean running(Player p) {
        return book.run(p.getUniqueId()).isPresent();
    }

    // ---------------------------------------------------------------- Sätze

    /**
     * true = erledigt. STATUS/WAY/HINT ohne laufendes Abenteuer: in der Farmwelt der Hinweis, wie man startet; sonst
     * weiter an das LLM. Vorfall dev01 2026-11-04 21:48: „Wo geht's lang?“ vor dem Start ging an das Modell, das zweimal
     * „tp @p …“ erfand und den Spieler 150 Blöcke weit versetzte.
     */
    boolean handle(Player p, CompanionChat.AdventureCommand c) {
        boolean query = c == CompanionChat.AdventureCommand.STATUS || c == CompanionChat.AdventureCommand.WAY
                || c == CompanionChat.AdventureCommand.HINT;
        if (query && !running(p) && allowed(p.getWorld())) {
            host.say(p, "Noch läuft kein Abenteuer. Sag „lass uns Minecraft spielen“ oder „… hardcore“, dann zeige ich "
                    + "dir den Weg.");
            return true;
        }
        switch (c) {
            case START_NORMAL -> start(p, Adventure.Mode.NORMAL);
            case START_HARDCORE -> start(p, Adventure.Mode.HARDCORE);
            case STOP -> {
                if (!running(p)) {
                    return false;
                }
                stop(p);
            }
            case STATUS -> {
                if (!running(p)) {
                    return false;
                }
                status(p);
            }
            case WAY -> {
                if (!running(p)) {
                    return false;
                }
                way(p);
            }
            case HINT -> {
                if (!running(p)) {
                    return false;
                }
                hint(p);
            }
        }
        return true;
    }

    void start(Player p, Adventure.Mode mode) {
        if (!allowed(p.getWorld())) {
            host.say(p, "Unser Abenteuer gibt es nur in der " + MainWorlds.label(plugin.getConfig())
                    + ". Geh dorthin, dann fangen wir an.");
            return;
        }
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            host.say(p, "Im Kreativmodus ist das kein Abenteuer. Schalt auf Überleben, dann geht es los.");
            return;
        }
        AdventureBook.Run r = book.start(p.getUniqueId(), mode, System.currentTimeMillis(), flags(p));
        watch.remove(p.getUniqueId());
        save();
        host.summon(p);
        arm(p, mode);
        LOG.info(() -> "abenteuer | start | spieler=" + p.getName() + " modus=" + mode.name().toLowerCase(Locale.ROOT)
                + " etappe=" + r.stage());
        String intro = mode == Adventure.Mode.HARDCORE
                ? "Hardcore! Du hast nur ein Leben, und ich gebe dir nur Rat – pass gut auf dich auf."
                : "Auf geht's! Ich bin an deiner Seite und kämpfe mit.";
        String already = r.stage() > 0 ? " Du hast schon " + r.stage() + " Etappen geschafft." : "";
        host.say(p, intro + already + " " + next(r.stage()));
    }

    void stop(Player p) {
        book.run(p.getUniqueId()).ifPresent(r -> {
            book.stop(p.getUniqueId());
            save();
            disarm(p);
            LOG.info(() -> "abenteuer | ende | spieler=" + p.getName() + " etappe=" + r.stage());
            host.say(p, "Gut, das Abenteuer ist beendet. Du warst bei " + Adventure.progress(r.stage()) + ".");
        });
    }

    void status(Player p) {
        book.run(p.getUniqueId()).ifPresent(r -> {
            long minutes = (System.currentTimeMillis() - r.startedMillis()) / 60_000;
            String mode = r.mode() == Adventure.Mode.HARDCORE ? "Hardcore" : "normal";
            host.say(p, Adventure.progress(r.stage()) + ", " + mode + ", seit " + minutes + " Minuten"
                    + (r.mode() == Adventure.Mode.NORMAL && r.deaths() > 0 ? ", " + r.deaths() + " mal gestorben" : "")
                    + ".");
        });
    }

    void hint(Player p) {
        book.run(p.getUniqueId()).ifPresent(r -> host.say(p, Adventure.finished(r.stage())
                ? "Wir haben alles geschafft! Wenn du magst, fangen wir ein neues Abenteuer an."
                : Adventure.STAGES.get(r.stage()).goal() + " " + Adventure.STAGES.get(r.stage()).hint()));
    }

    /** „Wo geht's lang?“ – je Etappe ein Ziel, gerechnet im Plugin; Erze nie, nur die Höhe. */
    void way(Player p) {
        AdventureBook.Run r = book.run(p.getUniqueId()).orElse(null);
        if (r == null) {
            return;
        }
        if (Adventure.finished(r.stage())) {
            hint(p);
            return;
        }
        World w = p.getWorld();
        Location at = p.getLocation();
        World.Environment env = w.getEnvironment();
        Adventure.Guide guide = Adventure.STAGES.get(r.stage()).guide();
        long t0 = System.nanoTime();
        try {
            guide(p, w, at, env, guide);
        } finally {
            // Gemessen statt geschätzt: locateNearestStructure läuft im Hauptthread (Celeron auf dev01). Steht hier
            // regelmäßig mehr als ~50 ms, gehört die Suche in einen Zwischenspeicher je Region.
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "abenteuer | weg | spieler=" + p.getName() + " ziel=" + guide.name().toLowerCase(Locale.ROOT)
                    + " ms=" + ms);
        }
    }

    private void guide(Player p, World w, Location at, World.Environment env, Adventure.Guide guide) {
        switch (guide) {
            case TREES -> point(p, nearestLog(p), "Da stehen Bäume", "Hier sehe ich keine Bäume. Geh zu einer Wiese "
                    + "oder in einen Wald – am Horizont erkennst du sie an den grünen Kronen.");
            case VILLAGE -> point(p, env == World.Environment.NORMAL ? nearestVillage(at) : null,
                    "Da liegt ein Dorf mit Betten, Feldern und Tieren",
                    "Kein Dorf in der Nähe. Schafe für Wolle und Tiere fürs Essen findest du auf Wiesen.");
            case CAVES -> host.say(p, depth(at.getBlockY(), IRON_Y, "Eisen"));
            case DIAMONDS -> host.say(p, depth(at.getBlockY(), DIAMOND_Y, "Diamanten"));
            case RUINED_PORTAL -> point(p, env == World.Environment.NORMAL
                    ? structure(at, org.bukkit.generator.structure.StructureType.RUINED_PORTAL, 24) : null,
                    "Da liegt ein Ruinenportal, oft mit Obsidian und einer Truhe",
                    "Ein Ruinenportal finde ich nicht. Lava liegt in tiefen Höhlen und an Lavaseen.");
            case FORTRESS -> {
                if (env != World.Environment.NETHER) {
                    host.say(p, "Lohen gibt es nur im Nether. Geh durch dein Portal, dann zeige ich dir die Festung.");
                } else {
                    point(p, structure(at, org.bukkit.generator.structure.StructureType.FORTRESS, 24),
                            "Da steht eine Netherfestung", "Hier finde ich keine Festung. Lauf ein Stück weiter, am "
                                    + "besten an einem Lavameer entlang.");
                }
            }
            case PEARLS -> host.say(p, env == World.Environment.NETHER
                    ? "Im blauen Wirrwald wimmelt es von Endermen. Schau dich nach blauem Boden um."
                    : "Endermen kommen nachts überall, in der Wüste sieht man sie gut. Oder tausch mit einem Bauern.");
            case STRONGHOLD -> {
                if (env != World.Environment.NORMAL) {
                    host.say(p, "Die Festung liegt in der Oberwelt. Geh zurück und wirf dort ein Enderauge.");
                } else if (!p.getInventory().contains(Material.ENDER_EYE)) {
                    host.say(p, "Mach erst Enderaugen: Lohenstaub und Enderperle. Ein geworfenes Auge zeigt die Festung.");
                } else {
                    point(p, structure(at, org.bukkit.generator.structure.StructureType.STRONGHOLD, 100),
                            "Die Festung liegt", "Wirf ein Enderauge und lauf ihm nach.");
                }
            }
            case DRAGON -> point(p, env == World.Environment.THE_END ? new Location(w, 0.5, at.getY(), 0.5) : null,
                    "Der Brunnen mit dem Drachen liegt", "Den Drachen gibt es nur im End.");
            case NONE -> hint(p);
        }
    }

    private void point(Player p, Location to, String found, String none) {
        if (to == null) {
            host.say(p, none);
            return;
        }
        Location f = p.getLocation();
        host.say(p, found + " – " + CompanionRules.direction(to.getX() - f.getX(), to.getY() - f.getY(),
                to.getZ() - f.getZ()) + ".");
        host.trail(p, to);
    }

    static String depth(int y, int target, String what) {
        int down = y - target;
        if (Math.abs(down) <= 4) {
            return "Du bist auf der richtigen Höhe für " + what + " – such in den Gängen und Höhlen ringsum.";
        }
        return down > 0 ? "Du bist auf Höhe " + y + ". " + what + " liegt am meisten um Höhe " + target + ", also noch "
                + down + " Blöcke nach unten. Grab treppenförmig oder folg einer Höhle."
                : "Du bist auf Höhe " + y + ", schon tiefer als nötig – " + what + " liegt um Höhe " + target + ".";
    }

    /** Nächster Baumstamm in 24 Blöcken (jede zweite Säule, ±8 Höhe) – grob genügt, Bäume sind breit. */
    private static Location nearestLog(Player p) {
        Location c = p.getLocation();
        World w = c.getWorld();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dz = -24; dz <= 24; dz += 2) {
                int x = c.getBlockX() + dx;
                int z = c.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = c.getBlockY() + 8; y >= c.getBlockY() - 4; y--) {
                    if (w.getBlockAt(x, y, z).getType().getKey().getKey().endsWith("_log")) {
                        double d = dx * dx + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = new Location(w, x + 0.5, y, z + 0.5);
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    private static Location nearestVillage(Location at) {
        Location best = null;
        for (org.bukkit.generator.structure.Structure s : List.of(
                org.bukkit.generator.structure.Structure.VILLAGE_PLAINS,
                org.bukkit.generator.structure.Structure.VILLAGE_DESERT,
                org.bukkit.generator.structure.Structure.VILLAGE_SAVANNA,
                org.bukkit.generator.structure.Structure.VILLAGE_SNOWY,
                org.bukkit.generator.structure.Structure.VILLAGE_TAIGA)) {
            org.bukkit.util.StructureSearchResult r = at.getWorld().locateNearestStructure(at, s, 16, false);
            if (r != null && (best == null || r.getLocation().distanceSquared(at) < best.distanceSquared(at))) {
                best = r.getLocation();
            }
        }
        return best;
    }

    private static Location structure(Location at, org.bukkit.generator.structure.StructureType type, int chunks) {
        org.bukkit.util.StructureSearchResult r = at.getWorld().locateNearestStructure(at, type, chunks, false);
        return r == null ? null : r.getLocation();
    }

    // ---------------------------------------------------------------- Takt

    /** Aus CompanionService.tick (alle 2 Ticks): Kämpfen im Takt, Fortschritt und Warnungen alle 3 s nach Wanduhr. */
    void tick(Player p) {
        AdventureBook.Run r = book.run(p.getUniqueId()).orElse(null);
        if (r != null && allowed(p.getWorld()) && !p.isDead() && modeChanged(p, r)) {
            return;
        }
        r = book.run(p.getUniqueId()).orElse(null);
        bar(p, r != null && allowed(p.getWorld()) && playing(p) ? r : null);
        if (r == null || !allowed(p.getWorld()) || p.isDead() || !playing(p)) {
            return;
        }
        Watch wt = watch.computeIfAbsent(p.getUniqueId(), id -> new Watch());
        long now = System.nanoTime();
        if (r.mode() == Adventure.Mode.NORMAL && fight) {
            guard(p, wt, now);
        } else {
            wt.target = null;
        }
        if (now - wt.lastObserve < OBSERVE_NANOS) {
            return;
        }
        wt.lastObserve = now;
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            observe(p);
        }
        warn(p, r, wt, now);
    }

    private void bar(Player p, AdventureBook.Run r) {
        org.bukkit.boss.BossBar bar = bars.get(p.getUniqueId());
        if (r == null || Adventure.finished(r.stage())) {
            if (bar != null) {
                bar.removeAll();
                bars.remove(p.getUniqueId());
            }
            return;
        }
        String title = Adventure.progress(r.stage()) + " – " + Adventure.STAGES.get(r.stage()).goal()
                + (r.mode() == Adventure.Mode.HARDCORE ? "  ♥ Hardcore" : "");
        if (bar == null) {
            bar = Bukkit.createBossBar(title, r.mode() == Adventure.Mode.HARDCORE ? org.bukkit.boss.BarColor.RED
                    : org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SEGMENTED_12);
            bars.put(p.getUniqueId(), bar);
        }
        if (!bar.getTitle().equals(title)) {
            bar.setTitle(title);
        }
        bar.setProgress(Math.clamp((double) r.stage() / Adventure.STAGES.size(), 0.0, 1.0));
        if (!bar.getPlayers().contains(p)) {
            bar.addPlayer(p);
        }
    }

    /** Spieler weg: Leiste und flüchtigen Zustand freigeben (der Lauf im Spielbuch bleibt). */
    void quit(Player p) {
        watch.remove(p.getUniqueId());
        modes.forget(p.getUniqueId());
        org.bukkit.boss.BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    /**
     * Wechsel in den oder aus dem Kreativmodus. Normal: pausieren und später weiter. Hardcore: Das Abenteuer endet –
     * ein Leben, in dem man sich Diamanten herbeizaubern kann, ist kein Hardcore. Ohne Bestleistung.
     * true = Lauf beendet (tick hört dann auf).
     */
    private boolean modeChanged(Player p, AdventureBook.Run r) {
        ModeWatch.Change c = modes.observe(p.getUniqueId(), playing(p));
        return c != ModeWatch.Change.NONE && react(p, r, c == ModeWatch.Change.PAUSED);
    }

    /**
     * Aus dem Ereignis PlayerGameModeChangeEvent (einen Tick später, dann gilt der neue Modus). Vorher nur im Takt des
     * Begleiters geprüft – ohne gerufene Vera ging ein Wechsel verloren (ModeWatch). true = das Abenteuer hat selbst
     * etwas dazu gesagt, CompanionService schweigt dann.
     */
    boolean gameModeChanged(Player p) {
        ModeWatch.Change c = modes.observe(p.getUniqueId(), playing(p));
        AdventureBook.Run r = book.run(p.getUniqueId()).orElse(null);
        if (c == ModeWatch.Change.NONE || r == null || !allowed(p.getWorld()) || p.isDead()) {
            return false;
        }
        react(p, r, c == ModeWatch.Change.PAUSED);
        return true;
    }

    /** now = jetzt pausiert (Kreativ/Zuschauer). true = Lauf beendet. */
    private boolean react(Player p, AdventureBook.Run r, boolean now) {
        if (now && r.mode() == Adventure.Mode.HARDCORE) {
            book.stop(p.getUniqueId());
            save();
            disarm(p);
            LOG.info(() -> "abenteuer | ende | spieler=" + p.getName() + " grund=kreativ etappe=" + r.stage());
            host.say(p, "Kreativmodus im Hardcore? Dann ist unser Hardcore-Abenteuer vorbei – du kamst bis "
                    + Adventure.progress(r.stage()) + ". Sag „lass uns hardcore spielen“ für einen neuen Versuch.");
            return true;
        }
        LOG.info(() -> "abenteuer | " + (now ? "pause" : "weiter") + " | spieler=" + p.getName() + " modus="
                + p.getGameMode().name().toLowerCase(Locale.ROOT));
        host.say(p, now ? "Kreativmodus – unser Abenteuer macht Pause. Jetzt darf ich dir wieder alles geben und bauen "
                + "lassen. Schalt auf Überleben, dann geht's weiter!"
                : "Zurück im Überlebensmodus – weiter geht's mit unserem Abenteuer! " + next(r.stage()));
        // Schwert weg in der Pause (HARDCORE heißt in arm: nichts in der Hand), danach wieder wie der Lauf es will.
        arm(p, now ? Adventure.Mode.HARDCORE : r.mode());
        return false;
    }

    /** Wohin Vera gerade läuft, um zu kämpfen; null = normal folgen. */
    Location fightGoal(Player p) {
        Watch wt = watch.get(p.getUniqueId());
        if (wt == null || wt.target == null || !wt.target.isValid() || wt.target.isDead()
                || !wt.target.getWorld().equals(p.getWorld())) {
            return null;
        }
        return wt.target.getLocation();
    }

    private void guard(Player p, Watch wt, long now) {
        Mannequin m = host.companion(p);
        if (m == null || !m.getWorld().equals(p.getWorld())) {
            wt.target = null;
            return;
        }
        Entity target = null;
        double bestD = GUARD * GUARD;
        for (Entity e : p.getNearbyEntities(GUARD, 4, GUARD)) {
            if (e instanceof Monster mob && !mob.isDead()) {
                double d = e.getLocation().distanceSquared(p.getLocation());
                if (d < bestD) {
                    bestD = d;
                    target = e;
                }
            }
        }
        wt.target = target;
        if (target == null || now - wt.lastStrike < STRIKE_NANOS) {
            return;
        }
        if (target.getLocation().distanceSquared(m.getLocation()) <= STRIKE_REACH * STRIKE_REACH
                && target instanceof org.bukkit.entity.LivingEntity living) {
            wt.lastStrike = now;
            BuilderNpc.lookAt(m, living.getEyeLocation());
            m.swingMainHand();
            living.damage(STRIKE_DAMAGE, m);
        }
    }

    /**
     * Merkmale aus dem Inventar eintragen – im Team für alle (AdventureBook.observeTeam): Schafft einer die Etappe,
     * feiern alle, die online sind.
     */
    private void observe(Player p) {
        applyTeam(p, flags(p));
    }

    private void applyTeam(Player p, Set<String> flags) {
        Map<UUID, AdventureBook.Run[]> changes = book.observeTeam(p.getUniqueId(), flags, System.currentTimeMillis());
        boolean any = false;
        for (Map.Entry<UUID, AdventureBook.Run[]> e : changes.entrySet()) {
            AdventureBook.Run before = e.getValue()[0];
            AdventureBook.Run after = e.getValue()[1];
            if (after.stage() == before.stage() && after.flags().equals(before.flags())) {
                continue;
            }
            any = true;
            Player member = Bukkit.getPlayer(e.getKey());
            if (member != null && after.stage() > before.stage()) {
                progressed(member, before, after);
            }
        }
        if (any) {
            save();
        }
    }

    // ---------------------------------------------------------------- Team

    /** Offene Einladungen: Gast → (Gastgeber, Zeitpunkt). */
    private final Map<UUID, Map.Entry<UUID, Long>> invites = new HashMap<>();
    /** Eine Einladung gilt zwei Minuten. */
    static final long INVITE_MILLIS = 120_000;

    /** „Spiel mit Alex zusammen“: lädt einen Mitspieler ins laufende Abenteuer ein. */
    void invite(Player p, String name) {
        if (!running(p)) {
            host.say(p, "Starte erst ein Abenteuer („lass uns Minecraft spielen“), dann lade ich jemanden dazu ein.");
            return;
        }
        Player guest = name == null ? null : Bukkit.getPlayerExact(name.strip());
        if (guest == null || !p.canSee(guest)) {
            host.say(p, "Ich finde " + Texts.clean(name, 16) + " gerade nicht auf dem Server.");
            return;
        }
        if (guest.equals(p)) {
            host.say(p, "Mit dir selbst spielst du schon!");
            return;
        }
        if (book.team(p.getUniqueId()).contains(guest.getUniqueId())) {
            host.say(p, guest.getName() + " ist schon in deinem Team.");
            return;
        }
        invites.put(guest.getUniqueId(), Map.entry(p.getUniqueId(), System.currentTimeMillis()));
        AdventureBook.Run r = book.run(p.getUniqueId()).orElseThrow();
        LOG.info(() -> "abenteuer | einladung | von=" + p.getName() + " an=" + guest.getName());
        host.say(p, "Ich habe " + guest.getName() + " eingeladen. Sagt " + guest.getName() + " ja, spielt ihr ab jetzt "
                + "zusammen.");
        Texts.infoLink(guest, "Ja, ich spiele mit!", "/bm begleiter abenteuer annehmen",
                "Ins Abenteuer von " + p.getName() + " einsteigen",
                " – " + p.getName() + " lädt dich ins " + (r.mode() == Adventure.Mode.HARDCORE ? "Hardcore-" : "")
                        + "Abenteuer ein (" + Adventure.progress(r.stage()) + "). Du kannst auch „"
                        + "Vera, ja“ sagen.");
        if (r.mode() == Adventure.Mode.HARDCORE) {
            Texts.info(guest, "Achtung, Hardcore im Team: Stirbt einer, ist das Abenteuer für alle vorbei.");
        }
    }

    boolean hasInvite(Player guest) {
        Map.Entry<UUID, Long> inv = invites.get(guest.getUniqueId());
        return inv != null && System.currentTimeMillis() - inv.getValue() <= INVITE_MILLIS;
    }

    /** Einladung annehmen: Gast übernimmt den Stand des Gastgebers, Vera kommt dazu. */
    void accept(Player guest) {
        Map.Entry<UUID, Long> inv = invites.remove(guest.getUniqueId());
        Player hostPlayer = inv == null ? null : Bukkit.getPlayer(inv.getKey());
        if (inv == null || System.currentTimeMillis() - inv.getValue() > INVITE_MILLIS || hostPlayer == null) {
            Texts.info(guest, "Da ist gerade keine Einladung offen.");
            return;
        }
        if (!allowed(guest.getWorld()) || !playing(guest)) {
            Texts.info(guest, "Für das Abenteuer musst du in der " + MainWorlds.label(plugin.getConfig())
                    + " und im Überleben sein.");
            invites.put(guest.getUniqueId(), inv);
            return;
        }
        if (!book.join(hostPlayer.getUniqueId(), guest.getUniqueId())) {
            Texts.info(guest, hostPlayer.getName() + " hat das Abenteuer inzwischen beendet.");
            return;
        }
        save();
        AdventureBook.Run r = book.run(guest.getUniqueId()).orElseThrow();
        watch.remove(guest.getUniqueId());
        host.summon(guest);
        arm(guest, r.mode());
        LOG.info(() -> "abenteuer | team | gastgeber=" + hostPlayer.getName() + " gast=" + guest.getName()
                + " etappe=" + r.stage());
        String members = String.join(", ", book.team(guest.getUniqueId()).stream().map(Bukkit::getOfflinePlayer)
                .map(o -> o.getName() == null ? "?" : o.getName()).toList());
        for (UUID id : book.team(guest.getUniqueId())) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) {
                host.say(m, "Wir sind ein Team: " + members + "! Was einer schafft, zählt für alle. "
                        + next(r.stage()));
            }
        }
        // Gleich prüfen, ob der Gast schon etwas mitbringt, das dem Team weiterhilft.
        applyTeam(guest, flags(guest));
    }

    private void progressed(Player p, AdventureBook.Run before, AdventureBook.Run after) {
        String done = Adventure.STAGES.get(after.stage() - 1).title();
        LOG.info(() -> "abenteuer | etappe | spieler=" + p.getName() + " von=" + before.stage() + " auf="
                + after.stage());
        if (Adventure.finished(after.stage())) {
            celebrate(p, "Enderdrache besiegt!", "Abenteuer geschafft", true);
            // Das ganze Tagebuch zum Schluss – danach endet der Lauf, das Buch wäre sonst weg.
            showDiary(p, after);
            long minutes = (System.currentTimeMillis() - after.startedMillis()) / 60_000;
            host.say(p, "Wir haben den Enderdrachen besiegt! Das ganze Abenteuer in " + minutes + " Minuten"
                    + (after.mode() == Adventure.Mode.HARDCORE ? ", mit einem einzigen Leben – Wahnsinn!" : "!"));
            book.stop(p.getUniqueId());
            save();
            disarm(p);
            return;
        }
        celebrate(p, done, Adventure.progress(after.stage()), false);
        hop(p);
        host.say(p, pick(CHEERS) + " " + done + " geschafft! " + next(after.stage()));
    }

    /** „Zeig mein Tagebuch“: Buch mit allen Etappen und Zeiten (AdventureDiary), geöffnet ohne Gegenstand. */
    void openDiary(Player p) {
        AdventureBook.Run r = book.run(p.getUniqueId()).orElse(null);
        if (r == null) {
            host.say(p, "Noch steht nichts in deinem Tagebuch. Sag „lass uns Minecraft spielen“, dann schreiben wir es "
                    + "zusammen!");
            return;
        }
        showDiary(p, r);
        host.say(p, "Hier ist dein Tagebuch – " + Adventure.progress(r.stage()) + "!");
    }

    /**
     * Öffnet das Buch nur zum Lesen (Player.openBook): Es landet nie im Inventar – sonst wäre es im Abenteuer ein
     * geschenkter Gegenstand, und Fair-Play gilt auch hier.
     */
    private static void showDiary(Player p, AdventureBook.Run r) {
        ItemStack bookItem = new ItemStack(Material.WRITTEN_BOOK);
        if (bookItem.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta meta) {
            meta.setTitle("Abenteuer");
            meta.setAuthor("Vera");
            meta.setPages(com.blackn0va.baumeister.companion.AdventureDiary.pages(r, p.getName(),
                    System.currentTimeMillis()));
            bookItem.setItemMeta(meta);
        }
        p.openBook(bookItem);
    }

    /** „Wer ist am weitesten?“ – die fünf Weitesten auf dem Server, mit Namen; der Fragende erfährt seinen Platz. */
    String rankingText(Player asker) {
        List<AdventureBook.Rank> top = book.ranking(Integer.MAX_VALUE);
        if (top.isEmpty()) {
            return "Noch hat niemand ein Abenteuer angefangen. Sag „lass uns Minecraft spielen“ – du wärst sofort vorne!";
        }
        StringBuilder sb = new StringBuilder();
        int place = 0;
        int mine = -1;
        for (AdventureBook.Rank rk : top) {
            place++;
            if (rk.player().equals(asker.getUniqueId())) {
                mine = place;
            }
            if (place <= 5) {
                String name = Bukkit.getOfflinePlayer(rk.player()).getName();
                sb.append(sb.isEmpty() ? "" : ", ").append(place).append(". ").append(name == null ? "?" : name)
                        .append(" mit Etappe ").append(Math.min(rk.score() + 1, Adventure.STAGES.size()))
                        .append(rk.stage() >= 0 && rk.mode() == Adventure.Mode.HARDCORE ? " im Hardcore" : "");
            }
        }
        return "Rangliste: " + sb + "." + (mine > 0 ? " Du bist auf Platz " + mine + "!" : " Du bist noch nicht dabei.");
    }

    /** Vera hüpft vor Freude: dreimal kurz hoch und runter (nur Aussehen, keine Schwerkraft nötig). */
    private void hop(Player p) {
        Mannequin m = host.companion(p);
        if (m == null) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            final double dy = i % 2 == 0 ? 0.5 : -0.5;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Mannequin now = host.companion(p);
                if (now != null) {
                    now.teleport(now.getLocation().add(0, dy, 0));
                }
            }, 3L + i * 4L);
        }
    }

    /** Wechselnde Jubelrufe – derselbe Satz bei jeder Etappe klingt nach Maschine, nicht nach Freundin. */
    static final List<String> CHEERS = List.of("Juhu!", "Wahnsinn!", "Stark!", "Yes!", "Super gemacht!",
            "Hammer!", "Na also!");
    static final List<String> CREEPER = List.of("Vorsicht, ein Creeper", "Achtung, Creeper", "Psst – da schleicht ein "
            + "Creeper", "Lauf, ein Creeper");

    static String pick(List<String> options) {
        return options.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size()));
    }

    /** Titel, Klang und Totem-Partikel – eine geschaffte Etappe soll sich wie ein Erfolg anfühlen. */
    private static void celebrate(Player p, String done, String next, boolean finale) {
        p.sendTitle((finale ? "§6" : "§a") + done, "§7" + next, 10, 60, 20);
        p.playSound(p.getLocation(), finale ? org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE
                : org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0),
                finale ? 200 : 60, 0.6, 0.8, 0.6, 0.4);
    }

    private static String next(int stage) {
        return Adventure.finished(stage) ? "" : "Nächstes Ziel: " + Adventure.STAGES.get(stage).goal()
                + " Frag mich „wo geht's lang?“ oder „was jetzt?“.";
    }

    private void warn(Player p, AdventureBook.Run r, Watch wt, long now) {
        boolean hardcore = r.mode() == Adventure.Mode.HARDCORE;
        double health = p.getHealth();
        double low = hardcore ? 10 : 6;
        if (health <= low && !wt.healthWarned) {
            wt.healthWarned = true;
            host.say(p, "Vorsicht, nur noch " + Math.round(health / 2) + " Herzen! Zieh dich zurück und iss etwas.");
            return;
        }
        if (health >= low + 4) {
            wt.healthWarned = false;
        }
        if (p.getFoodLevel() <= 6 && !wt.hungerWarned) {
            wt.hungerWarned = true;
            host.say(p, "Du hast Hunger – iss bald etwas, sonst heilst du nicht mehr.");
            return;
        }
        if (p.getFoodLevel() >= 12) {
            wt.hungerWarned = false;
        }
        for (Entity e : p.getNearbyEntities(6, 3, 6)) {
            if (e instanceof Creeper c && now - wt.lastCreeper >= CREEPER_WARN_NANOS) {
                wt.lastCreeper = now;
                Location f = p.getLocation();
                // „Creeper“ bleibt im Text (Chat); fürs Ohr schreibt die App es um (host speech_text.cpp, „Krieper“).
                host.say(p, pick(CREEPER) + " " + CompanionRules.direction(c.getLocation().getX() - f.getX(), 0,
                        c.getLocation().getZ() - f.getZ()) + "!");
                return;
            }
        }
        World w = p.getWorld();
        long time = w.getTime();
        long day = w.getFullTime() / 24_000;
        if (w.getEnvironment() == World.Environment.NORMAL && time >= 12_000 && time <= 13_500 && wt.nightDay != day) {
            wt.nightDay = day;
            boolean bed = r.flags().contains("bett");
            host.say(p, bed ? "Die Sonne geht unter – Zeit fürs Bett, sonst kommen die Monster."
                    : "Die Sonne geht unter, gleich kommen Monster! Grab dich ein oder bau schnell eine kleine Hütte.");
        }
    }

    // ---------------------------------------------------------------- Ereignisse

    /** PlayerDeathEvent (BaumeisterListener): Hardcore endet, normal geht es mit einem Tod mehr weiter. */
    void died(Player p) {
        if (!allowed(p.getWorld())) {
            return;
        }
        book.died(p.getUniqueId()).ifPresent(d -> {
            save();
            LOG.info(() -> "abenteuer | tod | spieler=" + p.getName() + " etappe=" + d.stage() + " vorbei=" + d.over()
                    + " team=" + d.teammates().size());
            for (UUID id : d.teammates()) {
                Player m = Bukkit.getPlayer(id);
                if (m != null) {
                    disarm(m);
                    host.say(m, p.getName() + " ist gestorben – unser Hardcore-Abenteuer ist für das ganze Team vorbei. "
                            + "Ihr kamt bis " + Adventure.progress(d.stage()) + ".");
                }
            }
            if (d.over()) {
                disarm(p);
                host.say(p, "Oh nein … Das Hardcore-Abenteuer ist vorbei, du kamst bis " + Adventure.progress(d.stage())
                        + "." + (d.newBest() ? " Das ist deine neue Bestleistung!" : "")
                        + " Sag „lass uns hardcore spielen“, dann fangen wir neu an.");
            } else {
                host.say(p, "Autsch! Deine Sachen liegen, wo du gestorben bist. Frag mich „wo bin ich gestorben?“, "
                        + "dann zeige ich dir den Weg.");
            }
        });
    }

    /** EntityDeathEvent des Enderdrachen: Wer in derselben Welt im Abenteuer ist, hat die letzte Etappe. */
    void dragonKilled(World w) {
        for (Player p : w.getPlayers()) {
            if (running(p)) {
                applyTeam(p, Set.of("drache"));
            }
        }
    }

    /** Beim Rufen und Starten: Schwert in Veras Hand, wenn sie mitkämpft. */
    void arm(Player p, Adventure.Mode mode) {
        Mannequin m = host.companion(p);
        if (m != null) {
            BuilderNpc.hold(m, mode == Adventure.Mode.NORMAL && fight ? Material.IRON_SWORD : null);
        }
    }

    void armIfRunning(Player p) {
        book.run(p.getUniqueId()).ifPresent(r -> arm(p, r.mode()));
    }

    private void disarm(Player p) {
        quit(p);
        Mannequin m = host.companion(p);
        if (m != null) {
            BuilderNpc.hold(m, null);
        }
    }

    /** Für das LLM: worum es gerade geht, damit Vera passend zur Etappe redet. */
    String context(Player p) {
        return book.run(p.getUniqueId()).filter(r -> allowed(p.getWorld())).map(r -> "Abenteuer läuft ("
                + (r.mode() == Adventure.Mode.HARDCORE ? "Hardcore, ein Leben" : "normal") + "), "
                + Adventure.progress(r.stage()) + (Adventure.finished(r.stage()) ? ""
                        : ", Ziel: " + Adventure.STAGES.get(r.stage()).goal())
                + " Fair spielen: nichts geben, keine Befehle, nur Tipps.").orElse("");
    }

    static Set<String> flags(Player p) {
        Map<String, Integer> items = new HashMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                items.merge(it.getType().getKey().getKey(), it.getAmount(), Integer::sum);
            }
        }
        return Adventure.flags(items, p.getRespawnLocation() != null,
                p.getWorld().getEnvironment().name().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- Spielbuch

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("begleiter-abenteuer.json");
    }

    void load() {
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        try {
            book = AdventureBook.fromJson(Files.readString(f));
            if (book.skipped() > 0) {
                LOG.warning(() -> "abenteuer | einträge_übersprungen | anzahl=" + book.skipped() + " datei=" + f);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "abenteuer | lesen_fehlgeschlagen | datei=" + f + " – Abenteuer beginnen neu", e);
        }
    }

    private final java.util.concurrent.atomic.AtomicLong saveSeq = new java.util.concurrent.atomic.AtomicLong();
    private long written;

    /**
     * Asynchron, aber nie ein älterer Stand über einem neueren: Bukkits Async-Pool hat keine Reihenfolge, zwei Saves
     * kurz hintereinander (Etappe + Tod) könnten sich überholen. Jeder Auftrag trägt eine Nummer, geschrieben wird nur,
     * was neuer ist als das zuletzt Geschriebene.
     */
    private void save() {
        String json = book.toJson();
        Path f = file();
        long seq = saveSeq.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (saveSeq) {
                if (seq <= written) {
                    return;
                }
                written = seq;
                write(f, json);
            }
        });
    }

    private static void write(Path f, String json) {
        {
            try {
                Path tmp = f.resolveSibling(f.getFileName() + ".neu");
                Files.createDirectories(f.getParent());
                Files.writeString(tmp, json);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "abenteuer | schreiben_fehlgeschlagen | datei=" + f, e);
            }
        }
    }
}
