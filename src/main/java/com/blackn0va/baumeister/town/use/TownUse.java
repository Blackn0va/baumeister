package com.blackn0va.baumeister.town.use;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.town.DayPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import org.bukkit.Location;

/**
 * Bewohner benutzen Dinge wie Spieler (Vertrag v013, AK-44; Nutzer 2026-11-22: „aktuell laufen sie dumm rum, sie
 * sollen dinge bauen, nutzen und so weiter“). Vorher liefen sie nur von Ort zu Ort: Türen waren Mauern (Wegsuche ohne
 * Türen), nachts verschwanden sie, und „Arbeit“ war Armschwingen auf der Wiese.
 *
 * Jetzt, je Phase des Tagesablaufs (TownLife fragt target statt des alten Ziels):
 * - Unterwegs: Türen, Tore und Falltüren (nicht Eisen) zählen für die Wegsuche als Durchgang, wenn der Bewohner sie
 *   benutzen darf (blocks); er öffnet sie direkt vor sich (pass) und schließt sie, sobald er durch ist (tick).
 * - Nachts (SLEEP): ein freies Bett nahe der Wohnung, hinlegen; steht ein Spieler am Bett oder ist es belegt, steht er
 *   auf. Kein Bett: wie vorher (heim und verschwinden).
 * - Mittags (PLAZA): manchmal auf eine Treppenstufe oder Plattenbank in der Nähe setzen.
 * - Bei der Arbeit (WORK): zum passenden Arbeitsblock (UseBlocks.workBlocks), davor stehen, Arm, Geräusch, Partikel.
 *
 * Weltänderung NUR der Tür-/Torzustand (UseWorld.setOpen), und nur wenn mayUse ja sagt – sonst weder öffnen noch
 * durchgehen (fail-closed, ASVS V4.1.5, OWASP A01). Das Bett bekommt kein occupied, ein Behälter wird nie geöffnet.
 *
 * Threads: Hauptthread. Fehler: keine Ausnahmen aus der Logik; fehlende Blöcke (Chunk entladen) heißen „nicht da“.
 */
public final class TownUse {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** So weit sucht ein Bewohner ein Bett um seine Wohnung (waagrecht / senkrecht). */
    public static final int BED_RADIUS = 12;
    public static final int BED_DY = 4;
    /** Bänke um den Bummelplatz. */
    public static final int SEAT_RADIUS = 8;
    public static final int SEAT_DY = 2;
    /** Arbeitsblöcke um den Arbeitsort. */
    public static final int WORK_RADIUS = 8;
    public static final int WORK_DY = 3;
    /** So nah gilt ein Stehplatz als erreicht – etwas mehr als TownLife.ARRIVE (1,2), dort hält der Bewohner an. */
    public static final double REACH = 1.3;
    /** Liegehöhe über der Bettunterkante wie beim Spieler (Minecraft setPosToBed: +0,6875). */
    public static final double LIE_Y = 0.6875;
    /** Steht ein Spieler so nah am Bett, will er es: Der Bewohner steht auf bzw. nimmt es nicht. */
    public static final double PLAYER_WANTS_BED = 2.5;
    /** Nachts so lange zum Bett laufen dürfen (statt Population.NIGHT_LEAVE_MS), dann verschwindet er doch. */
    public static final long BED_WALK_MS = 120_000L;
    /** Sitzen: Wahrscheinlichkeit je erreichtem Bummelplatz, Dauer, Pause bis zum nächsten Mal (Ticks). */
    public static final double SIT_CHANCE = 0.4;
    public static final int SIT_MIN_TICKS = 300;
    public static final int SIT_MAX_TICKS = 800;
    public static final int SIT_AGAIN_TICKS = 1200;
    /** Tür wieder zu: frühestens nach DOOR_MIN_TICKS, sobald er DOOR_PAST weg ist; spätestens nach DOOR_MAX_TICKS. */
    public static final int DOOR_MIN_TICKS = 10;
    public static final double DOOR_PAST = 1.6;
    public static final int DOOR_MAX_TICKS = 200;
    /** Auch wenn jemand im Durchgang steht: nach so vielen Ticks zu (sonst bliebe sie bei einem Wartenden ewig auf). */
    public static final int DOOR_HARD_MAX_TICKS = 600;
    /** Antwort der Rechteprüfung so lange merken (Ticks) – die Wegsuche fragt dieselbe Tür viele Male. */
    public static final int RIGHTS_TTL_TICKS = 400;
    static final int RIGHTS_MAX = 1024;
    /** So oft neu suchen, wenn nichts gefunden wurde (Ticks). */
    static final int BED_SCAN_TICKS = 200;
    static final int WORK_SCAN_TICKS = 600;

    public enum Mode {
        NONE, TO_BED, LYING, TO_SEAT, SITTING, TO_BENCH, WORKING
    }

    record Pos(String world, int x, int y, int z) {
    }

    private record Opened(Pos pos, UseWorld world, UUID who, long tick, UseBlocks.DoorKind kind) {
    }

    private record Rights(boolean allowed, long until) {
    }

    /** Was ein Bewohner gerade benutzt. */
    private static final class State {
        Mode mode = Mode.NONE;
        DayPlan.Goal goal;
        UseWorld world;
        /** Belegter Block (Bettkopf, Sitz, Arbeitsblock). */
        Pos claim;
        String facing = "";
        /** Wohin er läuft (Stehplatz) bzw. wo er ist (Liegeplatz, Sitz, vor dem Arbeitsblock); fest, nicht je Tick neu. */
        Location spot;
        double standX;
        double standY;
        double standZ;
        Runnable unseat;
        long until;
        long nextScan;
        long nextAct;
        long sitAgain;
        Location lastBase;
        boolean triedSeat;
    }

    private final RandomGenerator random;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<Pos, UUID> claims = new HashMap<>();
    private final List<Opened> opened = new ArrayList<>();
    private final Map<String, Rights> rights = new HashMap<>();
    private UseSettings settings = UseSettings.defaults();
    /** Name eines Bewohners fürs Log (TownLife setzt TownTalk.key); ohne: die ersten 8 Zeichen der UUID. */
    private Function<UUID, String> namer = id -> id.toString().substring(0, 8);
    private int doorsOpened;
    /** Letzter Takt aus tick (nur fürs Log von tür_zu). */
    private long lastTick;
    private int doorsClosed;
    private int doorsRefused;

    public TownUse(RandomGenerator random) {
        this.random = random;
    }

    public void configure(UseSettings s) {
        this.settings = s;
        if (!s.doorsOn()) {
            closeAll();
        }
    }

    public UseSettings settings() {
        return settings;
    }

    public void namer(Function<UUID, String> n) {
        this.namer = n;
    }

    /** Eine Logzeile je Zustandswechsel (selten: Tür, Bett, Bank, Arbeitsblock) – Beleg für Live-Proben. */
    private void log(UUID who, String event, Pos p, String extra) {
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | %s | bewohner=%s x=%d y=%d z=%d%s", event,
                namer.apply(who), p.x(), p.y(), p.z(), extra));
    }

    // ---------------------------------------------------------------- Ziel je Phase

    /**
     * Wohin der Bewohner jetzt will: base (das Ziel von TownLife) oder ein Platz an Bett, Bank oder Arbeitsblock.
     * Wechselt goal, steht er erst auf bzw. gibt den Platz frei. Rückgabe bleibt dasselbe Objekt, solange sich das Ziel
     * nicht ändert (TownLife.walk vergleicht per Identität, ob das Teilziel neu zu planen ist).
     */
    public Location target(Actor a, UseWorld w, DayPlan.Goal goal, Location base, String job, long tick) {
        State s = states.computeIfAbsent(a.id(), k -> new State());
        if (goal != s.goal) {
            leave(s, a);
            s.goal = goal;
            s.nextScan = tick;
            s.triedSeat = false;
        }
        if (w == null || base == null) {
            return base;
        }
        return switch (goal) {
            case SLEEP -> sleep(s, a, w, base, tick);
            case PLAZA -> sit(s, a, w, base, tick);
            case WORK -> work(s, a, w, base, job, tick);
            case HOME -> base;
        };
    }

    private Location sleep(State s, Actor a, UseWorld w, Location base, long tick) {
        if (!settings.bedsOn()) {
            leave(s, a);
            return base;
        }
        if (s.mode == Mode.LYING) {
            return s.spot;
        }
        if (s.mode == Mode.TO_BED) {
            if (!bedFree(w, s.claim, a.id())) {
                leave(s, a);
                s.nextScan = tick + BED_SCAN_TICKS;
                return base;
            }
            if (near(a, s.spot)) {
                int[] d = UseBlocks.step(s.facing);
                // Mittelpunkt des Kopfteils, wie beim Spieler; Körperdrehung nach Kopfende (UseBlocks.sleepYaw).
                double lx = s.claim.x() + 0.5 - d[0] * 0.1;
                double ly = s.claim.y() + LIE_Y;
                double lz = s.claim.z() + 0.5 - d[1] * 0.1;
                a.lie(lx, ly, lz, UseBlocks.sleepYaw(s.facing));
                log(a.id(), "bett_liegt", s.claim, " kopf=" + s.facing);
                s.mode = Mode.LYING;
                s.spot = new Location(base.getWorld(), lx, ly, lz);
                return s.spot;
            }
            return s.spot;
        }
        if (tick >= s.nextScan) {
            s.nextScan = tick + BED_SCAN_TICKS;
            Found bed = findBed(w, base, a.id());
            if (bed != null) {
                claim(s, a.id(), w, bed, Mode.TO_BED, base);
                return s.spot;
            }
        }
        return base;
    }

    private Location sit(State s, Actor a, UseWorld w, Location base, long tick) {
        if (!settings.seatsOn()) {
            leave(s, a);
            return base;
        }
        if (base != s.lastBase) {
            // Neuer Bummelplatz (TownLife wählt alle 20–40 s einen): dort darf er wieder überlegen, ob er sich setzt.
            s.lastBase = base;
            s.triedSeat = false;
        }
        if (s.mode == Mode.SITTING) {
            if (tick >= s.until || w.playerNear(s.claim.x() + 0.5, s.claim.y() + 0.5, s.claim.z() + 0.5, 0.6)) {
                leave(s, a);
                s.sitAgain = tick + SIT_AGAIN_TICKS;
                return base;
            }
            return s.spot;
        }
        if (s.mode == Mode.TO_SEAT) {
            if (!UseBlocks.seat(w.at(s.claim.x(), s.claim.y(), s.claim.z()))) {
                leave(s, a);
                return base;
            }
            if (near(a, s.spot)) {
                Runnable undo = a.sit(s.claim.x() + 0.5, s.claim.y() + 0.5, s.claim.z() + 0.5,
                        seatYaw(w, s.claim, s.facing));
                if (undo == null) {
                    leave(s, a);
                    s.sitAgain = tick + SIT_AGAIN_TICKS;
                    return base;
                }
                s.unseat = undo;
                log(a.id(), "sitzt", s.claim, " block=" + w.at(s.claim.x(), s.claim.y(), s.claim.z()).type());
                s.mode = Mode.SITTING;
                s.until = tick + SIT_MIN_TICKS + random.nextInt(SIT_MAX_TICKS - SIT_MIN_TICKS + 1);
            }
            return s.spot;
        }
        if (!s.triedSeat && tick >= s.sitAgain && near(a, base, 2.0)) {
            s.triedSeat = true;
            if (random.nextDouble() < SIT_CHANCE) {
                Found seat = findSeat(w, base, a.id());
                if (seat != null) {
                    claim(s, a.id(), w, seat, Mode.TO_SEAT, base);
                    return s.spot;
                }
            }
        }
        return base;
    }

    private Location work(State s, Actor a, UseWorld w, Location base, String job, long tick) {
        if (!settings.workOn()) {
            leave(s, a);
            return base;
        }
        if (s.mode == Mode.WORKING || s.mode == Mode.TO_BENCH) {
            UseWorld.Block b = w.at(s.claim.x(), s.claim.y(), s.claim.z());
            if (b == null || !UseBlocks.workBlocks(job).contains(b.type())) {
                // Weggeräumt oder Chunk weg: wie vorher am Arbeitsort weiter.
                leave(s, a);
                s.nextScan = tick + WORK_SCAN_TICKS;
                return base;
            }
            if (s.mode == Mode.TO_BENCH && near(a, s.spot)) {
                log(a.id(), "arbeitsblock", s.claim, " block=" + b.type() + " beruf=" + job);
                s.mode = Mode.WORKING;
                s.nextAct = tick;
            }
            if (s.mode == Mode.WORKING && tick >= s.nextAct) {
                double cx = s.claim.x() + 0.5;
                double cy = s.claim.y() + 0.5;
                double cz = s.claim.z() + 0.5;
                UseBlocks.Effect e = UseBlocks.effect(b.type());
                a.face(cx, cy, cz);
                a.swing();
                if (e != null) {
                    a.effect(e.sound(), e.particle(), cx, cy + 0.6, cz);
                }
                s.nextAct = tick + 40 + random.nextInt(61);
            }
            return s.spot;
        }
        if (tick >= s.nextScan && near(a, base, 16.0)) {
            s.nextScan = tick + WORK_SCAN_TICKS;
            Found bench = findBench(w, base, job, a.id());
            if (bench != null) {
                claim(s, a.id(), w, bench, Mode.TO_BENCH, base);
                return s.spot;
            }
        }
        return base;
    }

    private void claim(State s, UUID who, UseWorld w, Found f, Mode mode, Location base) {
        s.world = w;
        s.claim = f.pos;
        s.facing = f.facing;
        s.standX = f.stand[0];
        s.standY = f.stand[1];
        s.standZ = f.stand[2];
        s.spot = new Location(base.getWorld(), f.stand[0], f.stand[1], f.stand[2]);
        s.mode = mode;
        claims.put(f.pos, who);
    }

    /** Platz freigeben; liegt oder sitzt er, steht er auf (a == null: Bewohner schon weg, nur der Sitz wird geräumt). */
    private void leave(State s, Actor a) {
        if ((s.mode == Mode.LYING || s.mode == Mode.SITTING) && a != null && s.claim != null) {
            log(a.id(), "aufgestanden", s.claim, " war=" + s.mode.name().toLowerCase(Locale.ROOT));
        }
        if (s.mode == Mode.LYING && a != null) {
            a.rise(s.standX, s.standY, s.standZ);
        }
        if (s.unseat != null) {
            s.unseat.run();
            s.unseat = null;
        }
        if (s.claim != null) {
            claims.remove(s.claim);
        }
        s.claim = null;
        s.spot = null;
        s.mode = Mode.NONE;
    }

    // ---------------------------------------------------------------- Suchen

    /** Ein gefundener Platz: Block, Richtung und Stehplatz (Füße) davor. */
    private record Found(Pos pos, String facing, double[] stand) {
    }

    private Found findBed(UseWorld w, Location base, UUID who) {
        Found best = null;
        double bestD = Double.MAX_VALUE;
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        for (int dy = -BED_DY; dy <= BED_DY; dy++) {
            for (int dx = -BED_RADIUS; dx <= BED_RADIUS; dx++) {
                for (int dz = -BED_RADIUS; dz <= BED_RADIUS; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    UseWorld.Block b = w.at(x, y, z);
                    if (b == null || !UseBlocks.bed(b.type()) || !"HEAD".equals(b.half())) {
                        continue;
                    }
                    Pos p = new Pos(w.name(), x, y, z);
                    if (!bedFree(w, p, who)) {
                        continue;
                    }
                    int[] d = UseBlocks.step(b.facing());
                    double[] stand = beside(w, new int[][] {{x, y, z}, {x - d[0], y, z - d[1]}});
                    double dist = (double) dx * dx + dy * dy + (double) dz * dz;
                    if (stand != null && dist < bestD) {
                        bestD = dist;
                        best = new Found(p, b.facing(), stand);
                    }
                }
            }
        }
        return best;
    }

    /** Bett frei: noch ein Bettkopf, nicht belegt (Spieler/Dorfbewohner), von keinem anderen genommen, kein Spieler dran. */
    private boolean bedFree(UseWorld w, Pos p, UUID who) {
        UseWorld.Block b = w.at(p.x(), p.y(), p.z());
        if (b == null || !UseBlocks.bed(b.type()) || !"HEAD".equals(b.half()) || b.occupied()) {
            return false;
        }
        UUID owner = claims.get(p);
        if (owner != null && !owner.equals(who)) {
            return false;
        }
        return !w.playerNear(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5, PLAYER_WANTS_BED);
    }

    private Found findSeat(UseWorld w, Location base, UUID who) {
        Found best = null;
        double bestD = Double.MAX_VALUE;
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        for (int dy = -SEAT_DY; dy <= SEAT_DY; dy++) {
            for (int dx = -SEAT_RADIUS; dx <= SEAT_RADIUS; dx++) {
                for (int dz = -SEAT_RADIUS; dz <= SEAT_RADIUS; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    UseWorld.Block b = w.at(x, y, z);
                    if (!bench(w, b, x, y, z)) {
                        continue;
                    }
                    Pos p = new Pos(w.name(), x, y, z);
                    UUID owner = claims.get(p);
                    if (owner != null && !owner.equals(who) || w.playerNear(x + 0.5, y + 1.0, z + 0.5, 1.0)) {
                        continue;
                    }
                    double dist = (double) dx * dx + dy * dy + (double) dz * dz;
                    if (dist < bestD) {
                        bestD = dist;
                        best = new Found(p, b.facing(), new double[] {x + 0.5, y + 1.0, z + 0.5});
                    }
                }
            }
        }
        return best;
    }

    /**
     * Sitzbank: untere Stufe oder Platte, darüber zwei Blöcke frei. Treppe: Vor ihr (gegen facing) Platz für die Beine
     * auf festem Boden, und sie ist kein Teil einer Treppe – keine Stufe schräg darüber dahinter, keine schräg darunter
     * davor. Vorher (Probe am Haus) wäre jede Hausaufgangstreppe eine „Bank“ gewesen. Platte: daneben Platz für die Beine.
     */
    static boolean bench(UseWorld w, UseWorld.Block b, int x, int y, int z) {
        if (!UseBlocks.seat(b) || !w.passable(x, y + 1, z) || !w.passable(x, y + 2, z)) {
            return false;
        }
        if (b.type().endsWith("_STAIRS")) {
            int[] d = UseBlocks.step(b.facing());
            if (d[0] == 0 && d[1] == 0) {
                return false;
            }
            UseWorld.Block up = w.at(x + d[0], y + 1, z + d[1]);
            UseWorld.Block down = w.at(x - d[0], y - 1, z - d[1]);
            if (up != null && up.type().endsWith("_STAIRS") || down != null && down.type().endsWith("_STAIRS")) {
                return false;
            }
            return w.passable(x - d[0], y, z - d[1]) && w.solid(x - d[0], y - 1, z - d[1]);
        }
        return legRoom(w, x, y, z) != null;
    }

    /** Erste Richtung neben einer Platte mit Platz für die Beine (frei auf festem Boden); null = keine. */
    private static String legRoom(UseWorld w, int x, int y, int z) {
        for (String f : new String[] {"NORTH", "EAST", "SOUTH", "WEST"}) {
            int[] d = UseBlocks.step(f);
            if (w.passable(x + d[0], y, z + d[1]) && w.solid(x + d[0], y - 1, z + d[1])) {
                return f;
            }
        }
        return null;
    }

    private static float seatYaw(UseWorld w, Pos p, String facing) {
        UseWorld.Block b = w.at(p.x(), p.y(), p.z());
        if (b != null && b.type().endsWith("_STAIRS")) {
            return UseBlocks.seatYaw(facing);
        }
        // Platte: zur Seite mit Beinfreiheit schauen (seatYaw nimmt die Richtung, von der man wegschaut).
        String legs = legRoom(w, p.x(), p.y(), p.z());
        return legs == null ? 0f : UseBlocks.seatYaw(opposite(legs));
    }

    private static String opposite(String f) {
        return switch (f) {
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            case "EAST" -> "WEST";
            default -> "EAST";
        };
    }

    private Found findBench(UseWorld w, Location base, String job, UUID who) {
        List<String> wanted = UseBlocks.workBlocks(job);
        Found best = null;
        double bestScore = Double.MAX_VALUE;
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        for (int dy = -WORK_DY; dy <= WORK_DY; dy++) {
            for (int dx = -WORK_RADIUS; dx <= WORK_RADIUS; dx++) {
                for (int dz = -WORK_RADIUS; dz <= WORK_RADIUS; dz++) {
                    int x = bx + dx;
                    int y = by + dy;
                    int z = bz + dz;
                    UseWorld.Block b = w.at(x, y, z);
                    int rank = b == null ? -1 : wanted.indexOf(b.type());
                    if (rank < 0) {
                        continue;
                    }
                    Pos p = new Pos(w.name(), x, y, z);
                    UUID owner = claims.get(p);
                    if (owner != null && !owner.equals(who)) {
                        continue;
                    }
                    double[] stand = beside(w, new int[][] {{x, y, z}});
                    // Vorliebe zuerst (Räucherofen vor Werkbank bei der Bäckerin), dann die Nähe.
                    double score = rank * 10_000.0 + dx * dx + dy * dy + dz * dz;
                    if (stand != null && score < bestScore) {
                        bestScore = score;
                        best = new Found(p, b.facing(), stand);
                    }
                }
            }
        }
        return best;
    }

    /** Stehplatz neben einem der Blöcke (gleiche Höhe oder eins darunter): Boden fest, zwei Blöcke frei. */
    private static double[] beside(UseWorld w, int[][] cells) {
        for (int[] c : cells) {
            for (String f : new String[] {"NORTH", "EAST", "SOUTH", "WEST"}) {
                int[] d = UseBlocks.step(f);
                int x = c[0] + d[0];
                int z = c[2] + d[1];
                for (int y : new int[] {c[1], c[1] - 1}) {
                    if (standable(w, x, y, z)) {
                        return new double[] {x + 0.5, y, z + 0.5};
                    }
                }
            }
        }
        return null;
    }

    static boolean standable(UseWorld w, int x, int y, int z) {
        return w.solid(x, y - 1, z) && w.passable(x, y, z) && w.passable(x, y + 1, z);
    }

    // ---------------------------------------------------------------- Türen

    /**
     * Welt für die Wegsuche dieses Bewohners: wie base, aber eine geschlossene Tür, die er benutzen darf, ist ein
     * Durchgang. Darf er nicht (Rechteprüfung nein), bleibt sie eine Wand – der Weg führt dann nicht hindurch.
     */
    public CompanionPath.Blocks blocks(CompanionPath.Blocks base, UseWorld w, UUID who, long tick) {
        if (!settings.doorsOn() || w == null) {
            return base;
        }
        return new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return base.solid(x, y, z);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return base.passable(x, y, z) || closedDoorFor(w, who, x, y, z, tick) != null;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return base.water(x, y, z);
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return base.climbable(x, y, z);
            }

            @Override
            public boolean doorway(int x, int y, int z) {
                return base.doorway(x, y, z);
            }

            @Override
            public boolean stairs(int x, int y, int z) {
                return base.stairs(x, y, z);
            }
        };
    }

    /** Geschlossene Tür, die who benutzen darf; null = keine Tür, offen oder verboten. */
    private UseBlocks.DoorKind closedDoorFor(UseWorld w, UUID who, int x, int y, int z, long tick) {
        UseWorld.Block b = w.at(x, y, z);
        UseBlocks.DoorKind kind = b == null ? null : UseBlocks.door(b.type());
        if (kind == null || b.open() || !allowed(w, who, x, y, z, tick)) {
            return null;
        }
        return kind;
    }

    private boolean allowed(UseWorld w, UUID who, int x, int y, int z, long tick) {
        String key = who + "|" + w.name() + "|" + x + "|" + y + "|" + z;
        Rights r = rights.get(key);
        if (r != null && tick < r.until()) {
            return r.allowed();
        }
        if (rights.size() >= RIGHTS_MAX) {
            rights.values().removeIf(v -> tick >= v.until());
            if (rights.size() >= RIGHTS_MAX) {
                rights.clear();
            }
        }
        boolean ok = w.mayUse(who, x, y, z);
        if (!ok) {
            doorsRefused++;
            log(who, "tür_verweigert", new Pos(w.name(), x, y, z), "");
        }
        rights.put(key, new Rights(ok, tick + RIGHTS_TTL_TICKS));
        return ok;
    }

    /**
     * Unterwegs: Tür direkt vor ihm (0,9 Blöcke in Laufrichtung) oder am nächsten Wegpunkt (tx, tz), Füße und Kopf –
     * öffnen, wenn er darf, und merken, damit tick sie wieder schließt. Eine Tür, die schon offen war, rührt er nicht an
     * (und schließt sie später auch nicht).
     */
    public void pass(Actor a, UseWorld w, double tx, double tz, long tick) {
        if (!settings.doorsOn() || w == null) {
            return;
        }
        double dx = tx - a.x();
        double dz = tz - a.z();
        double h = Math.hypot(dx, dz);
        if (h < 1.0e-6) {
            return;
        }
        double[][] points = {{a.x() + dx / h * 0.9, a.z() + dz / h * 0.9}, {tx, tz}};
        int feet = (int) Math.floor(a.y() + 0.2);
        for (double[] p : points) {
            int x = (int) Math.floor(p[0]);
            int z = (int) Math.floor(p[1]);
            for (int y = feet; y <= feet + 1; y++) {
                UseBlocks.DoorKind kind = closedDoorFor(w, a.id(), x, y, z, tick);
                if (kind != null && w.setOpen(x, y, z, true)) {
                    doorsOpened++;
                    opened.add(new Opened(new Pos(w.name(), x, y, z), w, a.id(), tick, kind));
                    log(a.id(), "tür_auf", new Pos(w.name(), x, y, z), " art=" + kind.name().toLowerCase(Locale.ROOT));
                    w.sound(kind.sound(true), x + 0.5, y + 0.5, z + 0.5);
                }
            }
        }
    }

    /**
     * Jeder Takt (oder seltener): selbst geöffnete Türen schließen, sobald der Bewohner durch ist und niemand im
     * Durchgang steht; Schläfer aufstehen lassen, wenn ein Spieler das Bett will. actors = alle Bewohner, die da sind.
     */
    public void tick(long tick, Map<UUID, Actor> actors) {
        lastTick = tick;
        Iterator<Opened> it = opened.iterator();
        while (it.hasNext()) {
            Opened d = it.next();
            long age = tick - d.tick();
            Actor a = actors.get(d.who());
            double cx = d.pos().x() + 0.5;
            double cz = d.pos().z() + 0.5;
            boolean past = a == null || Math.hypot(a.x() - cx, a.z() - cz) > DOOR_PAST
                    || Math.abs(a.y() - d.pos().y()) > 2.5;
            boolean someoneIn = d.world().playerNear(cx, d.pos().y() + 0.5, cz, 1.2);
            for (Actor o : actors.values()) {
                if (Math.hypot(o.x() - cx, o.z() - cz) < 0.9 && Math.abs(o.y() - d.pos().y()) < 1.5) {
                    someoneIn = true;
                }
            }
            if ((past && age >= DOOR_MIN_TICKS || age >= DOOR_MAX_TICKS) && !someoneIn
                    || age >= DOOR_HARD_MAX_TICKS) {
                close(d);
                it.remove();
            }
        }
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.mode == Mode.LYING && s.world != null && !bedFree(s.world, s.claim, e.getKey())) {
                // Ein Spieler will das Bett (oder es ist weg/belegt): aufstehen, daneben stellen.
                Actor a = actors.get(e.getKey());
                leave(s, a);
                s.nextScan = tick + BED_SCAN_TICKS;
            }
        }
    }

    private void close(Opened d) {
        UseWorld.Block b = d.world().at(d.pos().x(), d.pos().y(), d.pos().z());
        // Nur zurückstellen, was er selbst geöffnet hat und noch offen ist; hat ein Spieler sie schon geschlossen
        // (oder ist der Chunk weg), bleibt sie, wie sie ist.
        if (b != null && UseBlocks.door(b.type()) != null && b.open()
                && d.world().setOpen(d.pos().x(), d.pos().y(), d.pos().z(), false)) {
            doorsClosed++;
            log(d.who(), "tür_zu", d.pos(), " nach_ticks=" + (lastTick - d.tick()));
            d.world().sound(d.kind().sound(false), d.pos().x() + 0.5, d.pos().y() + 0.5, d.pos().z() + 0.5);
        }
    }

    /** Alle selbst geöffneten Türen sofort schließen (Abschalten, Türen aus). */
    public void closeAll() {
        for (Opened d : opened) {
            close(d);
        }
        opened.clear();
    }

    /** Bewohner weg (entfernt, entladen): seine Türen zu, Sitz geräumt, Platz frei. */
    public void forget(UUID id) {
        Iterator<Opened> it = opened.iterator();
        while (it.hasNext()) {
            Opened d = it.next();
            if (d.who().equals(id)) {
                close(d);
                it.remove();
            }
        }
        State s = states.remove(id);
        if (s != null) {
            leave(s, null);
        }
        rights.keySet().removeIf(k -> k.startsWith(id + "|"));
    }

    // ---------------------------------------------------------------- Abfragen

    public Mode mode(UUID id) {
        State s = states.get(id);
        return s == null ? Mode.NONE : s.mode;
    }

    /** Liegt er im Bett? Dann verschwindet er nachts nicht. */
    public boolean sleeping(UUID id) {
        return mode(id) == Mode.LYING;
    }

    /** Hat er ein Bett (auf dem Weg oder liegend)? */
    public boolean hasBed(UUID id) {
        Mode m = mode(id);
        return m == Mode.TO_BED || m == Mode.LYING;
    }

    /** Liegt oder sitzt er? Dann weder ausweichen noch grüßen noch herumdrehen. */
    public boolean resting(UUID id) {
        Mode m = mode(id);
        return m == Mode.LYING || m == Mode.SITTING;
    }

    public int openDoors() {
        return opened.size();
    }

    /** Für /bm stadtleben status: geöffnet, geschlossen, verweigert (seit Start). */
    public int[] doorCounts() {
        return new int[] {doorsOpened, doorsClosed, doorsRefused};
    }

    private static boolean near(Actor a, Location l) {
        return l != null && Math.hypot(a.x() - l.getX(), a.z() - l.getZ()) < REACH && Math.abs(a.y() - l.getY()) < 2.0;
    }

    private static boolean near(Actor a, Location l, double r) {
        return l != null && Math.hypot(a.x() - l.getX(), a.z() - l.getZ()) < r && Math.abs(a.y() - l.getY()) < 3.0;
    }
}
