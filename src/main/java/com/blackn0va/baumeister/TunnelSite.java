package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.TunnelNature;
import com.blackn0va.baumeister.plan.TunnelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;

/**
 * Sicht in den Boden für TunnelShape (Wunsch 2026-11-16): lokal wie SiteFrame mit Abstand 0, also ab den Füßen des
 * Spielers bzw. dem Ende des fertigen Stücks. Was ein Block ist, entscheidet TunnelNature nach der ID; hier kommen die
 * Blockdaten dazu (Wasser in einem Block, von Hand gesetztes Laub) und die Weltgrenzen: Die unterste Schicht bleibt
 * wie bei jedem Bau unberührt (BuildService.startJob, „Weltboden“), ebenso die oberste. Außerhalb des Weltrands
 * (WorldBorder) ist ebenfalls Schluss – ein Tunnel quer über die Karte (Wunsch 2026-11-16) endet dort, statt
 * Abschnitte zu setzen, die niemand betreten kann.
 *
 * Oberfläche (sky): über dem obersten Block, der Bewegung hemmt (ohne Laub), und darunter über Wasser und Bewuchs
 * hinweg bis zum ersten festen – ein See zählt also zum Himmel, sein Grund zum Boden. Je Säule gemerkt.
 *
 * ownCells: Beim Weiterbauen steht vor dem fertigen Stück dessen Abschluss aus eigenen Steinen (TunnelShape setzt
 * ihn, wo Höhle oder Wasser war); eine Treppe aus einem Gang heraus durchbricht dessen Hülle (TunnelScan.room). Ohne
 * Ausnahme hielte der neue Abschnitt sie für Gebautes und grübe gar nicht erst. room: Innenraum dieses Gangs – dort
 * baut die Treppe nichts hinein (TunnelShape.Site.room). force („trotzdem“, nur Admins): Gebautes wie Fels.
 * bedrock: Grundgestein über der untersten Schicht wie Fels (TunnelNature.bedrockDiggable).
 *
 * structures: Bretter, Zäune und Schienen eines Mineschachts, den die Welt erzeugt hat, sind Natur (TunnelNature
 * .inStructure) – null = ohne diese Ausnahme (Erkennen eines Gangs braucht sie nicht).
 *
 * Threads: Hauptthread (liest die Welt). Fehler: keine.
 */
final class TunnelSite implements TunnelShape.Site {

    /** Block-ID für Ansagen außerhalb des Weltrands (TunnelStyle.german kennt sie). */
    static final String BORDER = "world_border";

    private final SiteFrame frame;
    private final World world;
    private final WorldBorder border;
    private final boolean nether;
    private final Set<Pos> ownCells;
    private final Set<String> ownIds;
    private final WorldStructures structures;
    private final Set<Pos> room;
    private final boolean force;
    private final boolean bedrock;
    private final Map<Long, Integer> tops = new HashMap<>();
    /** Zellen, die nur als Teil eines Mineschachts Natur sind – für den Plan-Log („schacht=“). */
    private final Set<Pos> mineshaft = new HashSet<>();
    /** Gebautes, das nur „trotzdem“ zu Fels macht – dort sucht TunnelService einen Gang (Kreuzung). */
    private final Set<Pos> forced = new HashSet<>();

    TunnelSite(SiteFrame frame, Set<Pos> ownCells, Set<String> ownIds, WorldStructures structures) {
        this(frame, ownCells, ownIds, structures, Set.of(), false, false);
    }

    TunnelSite(SiteFrame frame, Set<Pos> ownCells, Set<String> ownIds, WorldStructures structures, Set<Pos> room,
            boolean force, boolean bedrock) {
        this.frame = frame;
        this.world = frame.world();
        this.border = world.getWorldBorder();
        this.nether = world.getEnvironment() == World.Environment.NETHER;
        this.ownCells = ownCells;
        this.ownIds = ownIds;
        this.structures = structures;
        this.room = room;
        this.force = force;
        this.bedrock = bedrock;
    }

    @Override
    public TunnelShape.Ground at(Pos local) {
        Block b = frame.blockAt(local);
        if (b.getY() <= world.getMinHeight() || b.getY() >= world.getMaxHeight() - 1 || outside(b)) {
            return TunnelShape.Ground.FORBIDDEN;
        }
        if (ownCells.contains(local) && ownIds.contains(b.getType().getKey().getKey())) {
            return TunnelShape.Ground.SOLID;
        }
        TunnelShape.Ground g = classify(b, nether);
        TunnelShape.Ground s = inStructure(b, g, structures);
        if (s != g) {
            mineshaft.add(local);
        }
        if (s == TunnelShape.Ground.FORBIDDEN && bedrock && TunnelNature.bedrockDiggable(
                b.getType().getKey().getKey(), b.getY(), world.getMinHeight(), nether)) {
            return TunnelShape.Ground.SOLID;
        }
        TunnelShape.Ground f = TunnelNature.forced(s, force);
        if (f != s) {
            forced.add(local);
        }
        return f;
    }

    /** Abschnitte, in denen „trotzdem“ Gebautes zu Fels gemacht hat, aufsteigend. */
    java.util.SortedSet<Integer> forcedSlices() {
        java.util.SortedSet<Integer> out = new java.util.TreeSet<>();
        for (Pos p : forced) {
            out.add(p.z());
        }
        return out;
    }

    @Override
    public boolean room(Pos local) {
        return room.contains(local);
    }

    /** So viele Zellen gräbt der Plan nur, weil sie zu einem Mineschacht der Welt gehören. */
    int mineshaftCells() {
        return mineshaft.size();
    }

    @Override
    public boolean sky(Pos local) {
        Block b = frame.blockAt(local);
        return b.getY() > top(b.getX(), b.getZ());
    }

    @Override
    public String id(Pos local) {
        Block b = frame.blockAt(local);
        return outside(b) ? BORDER : b.getType().getKey().getKey();
    }

    private boolean outside(Block b) {
        return !border.isInside(b.getLocation().add(0.5, 0.5, 0.5));
    }

    /** Was der Block ist – auch für den Bau selbst (BuildJob fragt vor jedem Abriss, siehe TunnelService.Dig). */
    static TunnelShape.Ground classify(Block b, boolean nether) {
        return plain(b, nether);
    }

    static TunnelShape.Ground classify(Block b, boolean nether, WorldStructures structures) {
        return inStructure(b, classify(b, nether), structures);
    }

    private static TunnelShape.Ground plain(Block b, boolean nether) {
        BlockData d = b.getBlockData();
        if (d instanceof Leaves leaves && leaves.isPersistent()) {
            return TunnelShape.Ground.BUILT;  // von Hand gesetzt
        }
        boolean wet = d instanceof Waterlogged w && w.isWaterlogged();
        return TunnelNature.of(b.getType().getKey().getKey(), wet, nether);
    }

    /** Gebautes im Teil eines Mineschachts der Welt: dessen eigene Steine sind Natur (TunnelNature.inStructure). */
    private static TunnelShape.Ground inStructure(Block b, TunnelShape.Ground g, WorldStructures structures) {
        if (g != TunnelShape.Ground.BUILT || structures == null) {
            return g;
        }
        String structure = structures.diggableAt(b.getX(), b.getY(), b.getZ());
        if (structure == null) {
            return g;
        }
        boolean wet = b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
        return TunnelNature.inStructure(structure, b.getType().getKey().getKey(), wet, g);
    }

    /** Oberster fester Block der Säule; Wasser, Lava und Bewuchs darüber zählen zum Himmel. */
    private int top(int x, int z) {
        long key = (long) x << 32 | z & 0xFFFFFFFFL;
        Integer known = tops.get(key);
        if (known != null) {
            return known;
        }
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > world.getMinHeight()) {
            TunnelShape.Ground g = classify(world.getBlockAt(x, y, z), nether);
            if (g != TunnelShape.Ground.FLUID && g != TunnelShape.Ground.OPEN) {
                break;
            }
            y--;
        }
        tops.put(key, y);
        return y;
    }
}
