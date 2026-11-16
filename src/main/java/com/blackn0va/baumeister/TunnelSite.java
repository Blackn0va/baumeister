package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.TunnelNature;
import com.blackn0va.baumeister.plan.TunnelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;

/**
 * Sicht in den Boden für TunnelShape (Wunsch 2026-11-16): lokal wie SiteFrame mit Abstand 0, also ab den Füßen des
 * Spielers bzw. dem Ende des fertigen Stücks. Was ein Block ist, entscheidet TunnelNature nach der ID; hier kommen die
 * Blockdaten dazu (Wasser in einem Block, von Hand gesetztes Laub) und die Weltgrenzen: Die unterste Schicht bleibt
 * wie bei jedem Bau unberührt (BuildService.startJob, „Weltboden“), ebenso die oberste.
 *
 * Oberfläche (sky): über dem obersten Block, der Bewegung hemmt (ohne Laub), und darunter über Wasser und Bewuchs
 * hinweg bis zum ersten festen – ein See zählt also zum Himmel, sein Grund zum Boden. Je Säule gemerkt.
 *
 * ownFace: Beim Weiterbauen steht vor dem fertigen Stück dessen Abschluss aus eigenen Steinen (TunnelShape setzt ihn,
 * wo Höhle oder Wasser war). Ohne Ausnahme hielte der neue Abschnitt ihn für Gebautes und grübe gar nicht erst.
 *
 * Threads: Hauptthread (liest die Welt). Fehler: keine.
 */
final class TunnelSite implements TunnelShape.Site {

    private final SiteFrame frame;
    private final World world;
    private final boolean nether;
    private final Set<Pos> ownFace;
    private final Set<String> ownIds;
    private final Map<Long, Integer> tops = new HashMap<>();

    TunnelSite(SiteFrame frame, Set<Pos> ownFace, Set<String> ownIds) {
        this.frame = frame;
        this.world = frame.world();
        this.nether = world.getEnvironment() == World.Environment.NETHER;
        this.ownFace = ownFace;
        this.ownIds = ownIds;
    }

    @Override
    public TunnelShape.Ground at(Pos local) {
        Block b = frame.blockAt(local);
        if (b.getY() <= world.getMinHeight() || b.getY() >= world.getMaxHeight() - 1) {
            return TunnelShape.Ground.FORBIDDEN;
        }
        if (ownFace.contains(local) && ownIds.contains(b.getType().getKey().getKey())) {
            return TunnelShape.Ground.SOLID;
        }
        return classify(b, nether);
    }

    @Override
    public boolean sky(Pos local) {
        Block b = frame.blockAt(local);
        return b.getY() > top(b.getX(), b.getZ());
    }

    @Override
    public String id(Pos local) {
        return frame.blockAt(local).getType().getKey().getKey();
    }

    /** Was der Block ist – auch für den Bau selbst (BuildJob fragt vor jedem Abriss, siehe TunnelService.Dig). */
    static TunnelShape.Ground classify(Block b, boolean nether) {
        BlockData d = b.getBlockData();
        if (d instanceof Leaves leaves && leaves.isPersistent()) {
            return TunnelShape.Ground.BUILT;  // von Hand gesetzt
        }
        boolean wet = d instanceof Waterlogged w && w.isWaterlogged();
        return TunnelNature.of(b.getType().getKey().getKey(), wet, nether);
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
