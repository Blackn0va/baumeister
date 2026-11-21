package com.blackn0va.baumeister.client;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ein laufender Bau im Client: Zielzustände in Bau-Reihenfolge (die des Compilers – von unten nach oben, Abschnitt
 * für Abschnitt), wie weit gebaut ist, ob pausiert, und was vorher an jeder Stelle stand (für Rückgängig).
 *
 * Pause ist der Normalfall der Kontrolle: Jede Eingabe des Spielers setzt state auf PAUSED (Control), der Spieler hat
 * die Figur sofort wieder; weiter macht genau dort weiter, wo angehalten wurde – auch wenn der Spieler inzwischen
 * etwas geändert hat (der Abgleich mit der Welt kommt bei jedem Ziel neu).
 *
 * Threads: nur Client-Thread. Fehler: unbrauchbare Zustände zählen in invalid, der Rest wird gebaut.
 */
final class ClientBuild {

    enum State {
        RUNNING, PAUSED, VERIFYING, DONE, CANCELLED
    }

    /** companion = zweite Hälfte (Tür oben, Bett-Kopf): setzt das Spiel mit der ersten, nur prüfen. */
    record Target(BlockPos pos, BlockState state, boolean companion) {
    }

    final String order;
    final List<Target> targets;
    /** Aus ClientPlanner.Planned: Nachbesserungen ohne LLM und LLM-Runden – nur für die Meldung an die App. */
    List<String> fixes = List.of();
    int rounds;
    final boolean undo;
    final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
    /**
     * Gedächtnis vor diesem Bau (ClientBuilder.remember) – Rückgängig setzt es zurück (ClientBuilder.undoMemory).
     * remembered = der Bau kam überhaupt ins Gedächtnis; memoryBefore null heißt dann „vorher leer“.
     */
    ClientMemory.Entry<Map<BlockPos, BlockState>> memoryBefore;
    boolean remembered;
    /**
     * Die Welt (ClientLevel), in der der Bau angelegt wurde – nur als Identität verglichen (sameWorld). Vorher hing der
     * Bau an keiner Welt: Nach einem Dimensions-, Welt- oder Serverwechsel baute tick() mit den absoluten Koordinaten
     * in der neuen Welt weiter, und /kbau rückgängig schrieb die alten Zustände dort hinein. Object statt ClientLevel,
     * damit der Unit-Test ohne Minecraft-Start auskommt.
     */
    final Object world;
    private final LongSupplier clock;
    final long startedNanos;
    /** Pausen zählen nicht zur Bauzeit: Beginn der laufenden Pause (-1 = keine) und Summe der beendeten. */
    private long pausedSince = -1;
    private long pausedNanos;
    /** Aktive Zeit beim Start des laufenden Durchgangs (startPass) – Basis der Restzeit. */
    private long passStartActive;
    State state = State.RUNNING;
    String pauseReason = "";
    int next;
    int placed;
    int skipped;
    int invalid;
    /** Zweiter Durchgang: Ziele, die nach dem ersten nicht stimmen (Nachbar-Updates, verweigerte Klicks). */
    int passes;

    ClientBuild(String order, List<Target> targets, boolean undo, int invalid, Object world) {
        this(order, targets, undo, invalid, world, System::nanoTime);
    }

    /** Für Tests: eigene Uhr in Nanosekunden. */
    ClientBuild(String order, List<Target> targets, boolean undo, int invalid, Object world, LongSupplier clock) {
        this.order = order;
        this.targets = targets;
        this.undo = undo;
        this.invalid = invalid;
        this.world = world;
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
    }

    /** Gehört der Bau zu dieser Welt? Keine Welt (Menü, getrennt) zählt als fremd. */
    static boolean sameWorld(ClientBuild b, Object level) {
        return level != null && b.world == level;
    }

    /**
     * Eigenschaften, die das Spiel aus Strom und Nachbarn selbst berechnet. Sie im Prüfdurchgang mitzuvergleichen hieß:
     * Ein Trichter „enabled=true“ laut Plan steht ohne Strom auf false, ein Redstone-Draht verbindet sich anders – der
     * Bau riss solche Blöcke ab und setzte sie neu, die Stelle blieb „falsch“ (Spieltest 2026-10-31, Maschinenhalle:
     * 21 → 36 Stellen; der Wunsch). Was der Plan festlegt (Block, Richtung, Hälfte,
     * Verzögerung, Modus …) zählt weiter.
     */
    static final java.util.Set<String> DYNAMIC = java.util.Set.of("power", "powered", "enabled", "lit", "triggered",
            "locked", "attached", "north", "south", "east", "west", "up", "down", "shape", "in_wall", "occupied",
            "distance", "waterlogged", "has_book", "has_record", "bottom", "extended", "short");

    /** Gleicher Block, gleiche festgelegte Eigenschaften (ohne DYNAMIC). */
    static boolean sameIntent(BlockState have, BlockState want) {
        if (have.equals(want)) {
            return true;
        }
        if (have.getBlock() != want.getBlock()) {
            return false;
        }
        for (var prop : want.getProperties()) {
            if (!DYNAMIC.contains(prop.getName()) && !have.getValue(prop).equals(want.getValue(prop))) {
                return false;
            }
        }
        return true;
    }

    Target current() {
        return next < targets.size() ? targets.get(next) : null;
    }

    int total() {
        return targets.size();
    }

    boolean active() {
        return state == State.RUNNING || state == State.PAUSED || state == State.VERIFYING;
    }

    void pause(String reason) {
        if (state == State.RUNNING || state == State.VERIFYING) {
            state = State.PAUSED;
            pauseReason = reason;
            pausedSince = clock.getAsLong();
        }
    }

    /** Weiter an derselben Stelle; die Pausendauer fällt aus Bau- und Restzeit heraus. */
    void resume() {
        if (state != State.PAUSED) {
            return;
        }
        if (pausedSince >= 0) {
            pausedNanos += clock.getAsLong() - pausedSince;
            pausedSince = -1;
        }
        state = current() == null ? State.VERIFYING : State.RUNNING;
    }

    /**
     * Bauzeit ohne Pausen (eine laufende Pause zählt bis jetzt nicht mit). Vorher Zeit seit Anlage: Nach 5 min Pause
     * bei 100/1000 in 10 s zeigte die Aktionsleiste ~51 min statt ~1:30, und „N Blöcke in Y s“ enthielt die Pause.
     */
    double activeSeconds() {
        return activeNanos() / 1e9;
    }

    private long activeNanos() {
        long now = clock.getAsLong();
        return now - startedNanos - pausedNanos - (pausedSince >= 0 ? now - pausedSince : 0);
    }

    /** Neuer Durchgang (Nachbessern): next beginnt wieder bei 0, die Restzeit misst ab hier. */
    void startPass() {
        passStartActive = activeNanos();
    }

    /**
     * Aktive Zeit im laufenden Durchgang – die Rate für die Restzeit. Vorher die Gesamtzeit: Im Nachbesserungsdurchgang
     * (next wieder 0) ergab das bei 50 Stellen nach 100 s Hauptbau und next=10 „noch ~6:40“ für 40 Blöcke. Wer hier
     * activeSeconds() übergibt, hat diese Überschätzung wieder.
     */
    double passSeconds() {
        return (activeNanos() - passStartActive) / 1e9;
    }

    /**
     * Zeile für die Aktionsleiste über der Hotbar, solange gebaut wird. Vorher sah man den Stand nur über /kbau status
     * – bei Bauten von mehreren Minuten wirkte ein laufender Bau wie ein hängender (Wunsch). Restzeit erst ab 10 Zielen
     * und 3 s, vorher schwankt die Rate zu stark für eine ehrliche Angabe.
     */
    static String progressLine(int next, int total, double seconds, String pauseReason) {
        int pct = total <= 0 ? 100 : (int) Math.floor(next * 100.0 / total);
        StringBuilder sb = new StringBuilder("§6Baumeister §f");
        sb.append(pauseReason != null ? "pausiert" : "baut").append(' ').append(next).append('/').append(total)
                .append(" · ").append(pct).append(" %");
        if (pauseReason != null) {
            sb.append(" · ").append(pauseReason).append(" · [K] weiter");
        } else if (next >= 10 && seconds >= 3 && next < total) {
            long rest = Math.round((total - next) * seconds / next);
            sb.append(" · noch ~").append(rest / 60).append(':').append(String.format("%02d", rest % 60));
        }
        return sb.toString();
    }

    /**
     * Plan → Welt. Lokale Koordinaten wie im Plugin (SiteFrame): Ursprung bei den Füßen plus ahead nach vorn, x rechts,
     * z vom Spieler weg; shift wie BuildService.startJob (freier Platz: mittig vor den Spieler).
     */
    static ClientBuild of(String order, CompiledBuild build, Orientation o, BlockPos feet, int ahead, int shiftX,
            int shiftZ, HolderLookup<Block> blocks, Object world) {
        List<Target> out = new ArrayList<>(build.actions().size());
        int invalid = 0;
        for (CompiledBuild.Action a : build.actions()) {
            for (int i = 0; i < a.changes().size(); i++) {
                CompiledBuild.Change c = a.changes().get(i);
                Pos w = o.toWorldOffset(c.pos().add(shiftX, 0, shiftZ + ahead));
                BlockSpec spec = o.toWorld(c.block());
                try {
                    BlockState st = BlockStateParser.parseForBlock(blocks, spec.asString(), false).blockState();
                    out.add(new Target(feet.offset(w.x(), w.y(), w.z()), st, i > 0));
                } catch (CommandSyntaxException e) {
                    // Gezählt statt verworfen: Der Plan hat den Katalog schon bestanden; ein Zustand, den diese
                    // Spielfassung anders schreibt, kostet einen Block, nicht den Bau.
                    invalid++;
                }
            }
        }
        return new ClientBuild(order, out, false, invalid, world);
    }

    /** Rückgängig: alte Zustände in umgekehrter Reihenfolge (oben zuerst, damit nichts in der Luft hängt). */
    ClientBuild undoBuild() {
        List<Target> back = new ArrayList<>(originals.size());
        List<Map.Entry<BlockPos, BlockState>> all = new ArrayList<>(originals.entrySet());
        for (int i = all.size() - 1; i >= 0; i--) {
            back.add(new Target(all.get(i).getKey(), all.get(i).getValue(), false));
        }
        // Gleiche Welt wie der Bau: Die Originale gelten nur dort (ClientBuilder.undo prüft sie mit sameWorld).
        return new ClientBuild("Rückgängig: " + order, back, true, 0, world);
    }
}
