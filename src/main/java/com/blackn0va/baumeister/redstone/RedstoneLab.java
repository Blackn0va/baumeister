package com.blackn0va.baumeister.redstone;

import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Schaltet die Hebel einer Schaltung wirklich durch (RedstoneExperiment.plan), liest nach jedem Schritt die Ausgänge
 * ab, sobald sie 4 Ticks still stehen (höchstens 40), und stellt am Ende jeden Hebel zurück.
 *
 * Nur Hebel: Knöpfe und Druckplatten springen von selbst zurück, Takte laufen ohnehin. Verweigert wird bei TNT,
 * Werfern, Spendern, Werkbank-Automaten, Kolben und Schienen – dort löst Durchschalten etwas aus, das sich nicht
 * zurückstellen lässt (ein normaler Kolben schiebt einen Block weg und zieht ihn nicht zurück).
 *
 * poke: nach dem Umschalten die Redstone-Teile neben dem Block anstupsen, an dem der Hebel hängt (Glas in eine freie
 * Nachbarzelle und wieder weg, wie BuildJob.refreshRedstone). Gemessen 2026-11-06 (probes/redstone/LabProbe): Ein per
 * Plugin umgelegter Hebel weckt nur seine eigenen Nachbarn, nicht die seines Blocks – Hebel an einem Stein, Staub auf
 * der anderen Seite: ohne Anstupsen „immer aus“, mit „A“. Wer es abschaltet, misst Schaltungen über Blöcke falsch.
 *
 * Threads: Hauptthread (Bukkit-Takt). Fehler: Bricht jemand einen Hebel ab, endet das Experiment mit Fehlertext;
 * zurückgestellt wird, was noch da ist.
 */
public final class RedstoneLab {

    static final Set<String> RISKY = Set.of("tnt", "dispenser", "dropper", "crafter", "piston", "sticky_piston",
            "powered_rail", "activator_rail");
    /**
     * Gemessen 2026-11-06 (probes/redstone/LabProbe): Vorher 4 stille Ticks ohne Mindestwartezeit – eine Lampe geht
     * erst 4 Ticks nach dem Signal aus, eine Fackel schaltet nach 2. Abgelesen wurde zu früh, jede Zeile rutschte
     * einen Schritt weiter (NICHT ergab „A, merkt sich etwas“, UND ergab „B“). Jetzt erst nach MIN_WAIT, dann
     * STABLE_TICKS still. Wer kürzer wartet, misst den vorigen Schritt.
     */
    static final int MIN_WAIT = 10;
    static final int STABLE_TICKS = 6;
    static final int MAX_WAIT = 60;
    private static final Set<Material> POKED = java.util.EnumSet.of(Material.REPEATER, Material.COMPARATOR,
            Material.REDSTONE_WIRE, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH, Material.REDSTONE_LAMP);
    private static final BlockFace[] POKE = {BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
        BlockFace.WEST};

    /** error != null: abgebrochen. */
    public record Result(List<RedstoneExperiment.Output> outputs, List<String> inputLabels, List<String> unused,
            int steps, long millis, String error) {
    }

    private RedstoneLab() {
    }

    /**
     * Prüft und startet. null = läuft (done kommt später im Hauptthread), sonst ein deutscher Satz, warum nicht.
     *
     * @param mayChange darf dieser Hebel umgelegt werden (Rechte des Spielers, WorldGuard, Grundstück)?
     */
    public static String start(Plugin plugin, World w, RedstoneReader.Circuit c, Predicate<Block> mayChange,
            boolean poke, Consumer<Result> done) {
        for (RedstoneReader.Part p : c.parts()) {
            if (RISKY.contains(p.cell().id())) {
                return "In dieser Schaltung steckt " + RedstoneReader.name(p.cell().id()) + " – die schalte ich nicht "
                        + "einfach durch, das könnte etwas auslösen, das ich nicht zurückstellen kann.";
            }
        }
        Comparator<RedstoneReader.Part> order = Comparator.comparingInt((RedstoneReader.Part p) -> p.pos().x())
                .thenComparingInt(p -> p.pos().z()).thenComparingInt(p -> p.pos().y());
        List<RedstoneReader.Part> levers = c.parts().stream().filter(p -> p.cell().id().equals("lever")).sorted(order)
                .toList();
        if (levers.isEmpty()) {
            return "Ich finde in der Schaltung keinen Hebel zum Ausprobieren – Knöpfe und Druckplatten springen von "
                    + "selbst zurück.";
        }
        if (levers.size() > RedstoneExperiment.MAX_INPUTS) {
            return "Die Schaltung hat " + levers.size() + " Hebel – ich probiere höchstens "
                    + RedstoneExperiment.MAX_INPUTS + " aus (32 Stellungen).";
        }
        List<RedstoneReader.Part> outs = c.parts().stream().filter(p -> p.kind() == RedstoneReader.Kind.CONSUMER)
                .sorted(order).limit(RedstoneExperiment.MAX_OUTPUTS).toList();
        if (outs.isEmpty()) {
            return "In der Schaltung ist nichts, das ich ablesen kann – keine Lampe, Tür oder Ähnliches.";
        }
        List<Block> leverBlocks = new ArrayList<>();
        for (RedstoneReader.Part p : levers) {
            Block b = w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z());
            if (!mayChange.test(b)) {
                return "Das ist nicht deine Schaltung – die Hebel schalte ich nicht um.";
            }
            leverBlocks.add(b);
        }
        List<Block> outBlocks = outs.stream().map(p -> w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z())).toList();
        List<Block> pokeBlocks = c.parts().stream().map(p -> w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z()))
                .filter(b -> POKED.contains(b.getType())).toList();
        List<String> names = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < levers.size(); i++) {
            String n = String.valueOf((char) ('A' + i));
            names.add(n);
            labels.add(n + " = Hebel bei " + RedstoneReader.at(levers.get(i).pos()));
        }
        List<String> outNames = outs.stream().map(p -> RedstoneReader.name(p.cell().id()) + " bei "
                + RedstoneReader.at(p.pos())).toList();
        boolean[] original = new boolean[leverBlocks.size()];
        for (int i = 0; i < original.length; i++) {
            original[i] = leverBlocks.get(i).getBlockData() instanceof Powerable pw && pw.isPowered();
        }
        int[] plan = RedstoneExperiment.plan(levers.size());
        boolean[][] readings = new boolean[plan.length][outs.size()];
        long t0 = System.nanoTime();
        new BukkitRunnable() {
            int step = -1;
            int waited;
            int stable;
            boolean[] last;

            @Override
            public void run() {
                if (step >= 0) {
                    boolean[] now = read(outBlocks);
                    stable = last != null && java.util.Arrays.equals(now, last) ? stable + 1 : 0;
                    last = now;
                    waited++;
                    if (waited < MAX_WAIT && (waited < MIN_WAIT || stable < STABLE_TICKS)) {
                        return;
                    }
                    readings[step] = now;
                }
                step++;
                if (step >= plan.length) {
                    cancel();
                    set(leverBlocks, original, pokeBlocks, poke);
                    List<RedstoneExperiment.Output> res = RedstoneExperiment.analyse(readings, levers.size(), names,
                            outNames);
                    done.accept(new Result(res, labels, RedstoneExperiment.unused(res, names), plan.length,
                            (System.nanoTime() - t0) / 1_000_000, null));
                    return;
                }
                boolean[] want = new boolean[leverBlocks.size()];
                for (int i = 0; i < want.length; i++) {
                    want[i] = (plan[step] >> i & 1) == 1;
                }
                if (!set(leverBlocks, want, pokeBlocks, poke)) {
                    cancel();
                    set(leverBlocks, original, pokeBlocks, poke);
                    done.accept(new Result(List.of(), labels, List.of(), step, (System.nanoTime() - t0) / 1_000_000,
                            "Ein Hebel ist verschwunden – ich habe aufgehört und den Rest zurückgestellt."));
                    return;
                }
                if (step == 0 && poke) {
                    refresh(leverBlocks);
                }
                waited = 0;
                stable = 0;
                last = null;
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return null;
    }

    private static final BlockFace[] SIX = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
        BlockFace.EAST, BlockFace.WEST};

    /** Block, an dem der Hebel hängt: Boden darunter, Decke darüber, Wand gegenüber seinem facing. */
    static Block attached(Block lever) {
        if (!(lever.getBlockData() instanceof org.bukkit.block.data.type.Switch sw)) {
            return null;
        }
        return switch (sw.getAttachedFace()) {
            case FLOOR -> lever.getRelative(BlockFace.DOWN);
            case CEILING -> lever.getRelative(BlockFace.UP);
            case WALL -> lever.getRelative(sw.getFacing().getOppositeFace());
        };
    }

    /** Glas in die erste freie Nachbarzelle und gleich wieder weg – löst ein Nachbar-Update an part aus. */
    static void nudge(Block part) {
        for (BlockFace f : POKE) {
            Block n = part.getRelative(f);
            if (n.getType().isAir()) {
                n.setType(Material.GLASS, true);
                n.setType(Material.AIR, true);
                return;
            }
        }
    }

    private static boolean[] read(List<Block> outs) {
        boolean[] r = new boolean[outs.size()];
        for (int i = 0; i < r.length; i++) {
            Block b = outs.get(i);
            Pos p = new Pos(b.getX(), b.getY(), b.getZ());
            RedstoneReader.Cell c = RedstoneReader.Cell.parse(b.getBlockData().getAsString());
            r[i] = new RedstoneReader.Part(p, c, RedstoneReader.Kind.CONSUMER).on();
        }
        return r;
    }

    /** Hebel stellen (mit Physik). false = einer ist kein Hebel mehr. */
    private static boolean set(List<Block> levers, boolean[] want, List<Block> pokeBlocks, boolean poke) {
        boolean ok = true;
        boolean changed = false;
        for (int i = 0; i < levers.size(); i++) {
            Block b = levers.get(i);
            BlockData d = b.getBlockData();
            if (b.getType() != Material.LEVER || !(d instanceof Powerable pw)) {
                ok = false;
                continue;
            }
            if (pw.isPowered() != want[i]) {
                pw.setPowered(want[i]);
                b.setBlockData(pw, true);
                changed = true;
            }
        }
        if (poke && changed) {
            refresh(levers);
        }
        return ok;
    }

    /**
     * Teile neben den Blöcken der Hebel anstupsen. Auch vor dem ersten Ablesen: Gemessen 2026-11-06 stand beim NICHT
     * die Lampe zu Beginn falsch (Schritt 0 schaltet keinen Hebel um, also stupste nichts an), und das Ergebnis hieß
     * „immer aus, merkt sich etwas“.
     */
    /**
     * Hebel oder Knopf schalten wie ein Spieler (Vera auf Zuruf). Ein vom Plugin gesetzter Hebel weckt die Nachbarn
     * seines Trägerblocks nicht (gemessen 2026-11-06, LabProbe) – darum wie im Labor anstupsen: Teile am Träger und
     * direkt am Schalter. Wer das Anstupsen weglässt, schaltet den Hebel nur optisch um.
     */
    public static boolean toggle(Block sw, boolean on) {
        if (!(sw.getBlockData() instanceof org.bukkit.block.data.Powerable pw)) {
            return false;
        }
        pw.setPowered(on);
        sw.setBlockData(pw, true);
        refresh(List.of(sw));
        for (BlockFace side : SIX) {
            Block part = sw.getRelative(side);
            if (POKED.contains(part.getType())) {
                nudge(part);
            }
        }
        return true;
    }

    static void refresh(List<Block> levers) {
        {
            for (Block lever : levers) {
                Block base = attached(lever);
                if (base == null) {
                    continue;
                }
                for (BlockFace side : SIX) {
                    Block part = base.getRelative(side);
                    if (POKED.contains(part.getType())) {
                        nudge(part);
                    }
                }
            }
        }
    }
}
