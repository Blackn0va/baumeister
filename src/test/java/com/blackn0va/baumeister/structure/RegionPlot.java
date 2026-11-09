package com.blackn0va.baumeister.structure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Messprobe: Draufsicht eines Felds einer Technik-Wiki-Welt als Text (höchster Block über dem Boden je Spalte) plus
 * alle Schilder darin. Zeichen: R Redstone, = Verstärker/Komparator, i Fackel, P Kolben, L Hebel/Knopf, # andere
 * Blöcke, . nur Boden; dazu die Höhe der Spalte über dem Boden als Ziffer in einer zweiten Karte.
 *
 * Aufruf: … RegionPlot &lt;weltordner&gt; &lt;schildtext-teil&gt; [radius]
 */
public final class RegionPlot {

    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        String needle = args[1].toLowerCase(java.util.Locale.ROOT);
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 34;
        List<RegionWorld.Entity> signs = new ArrayList<>();
        RegionWorld.scan(world, b -> { }, e -> {
            if (e.id().endsWith("sign")) {
                signs.add(e);
            }
        });
        RegionWorld.Entity anchor = signs.stream().filter(s -> s.y() < 40
                && RegionWorld.signText(s.data()).toLowerCase(java.util.Locale.ROOT).contains(needle)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("kein Schild mit " + needle));
        int cx = anchor.x();
        int cz = anchor.z();
        System.out.println("Anker: " + RegionWorld.signText(anchor.data()) + " bei " + cx + " " + anchor.y() + " " + cz);
        Map<Long, String> top = new HashMap<>();
        Map<Long, Integer> height = new HashMap<>();
        RegionWorld.scan(world, b -> {
            if (b.y() >= 26 && Math.abs(b.x() - cx) <= radius && Math.abs(b.z() - cz) <= radius) {
                long k = ((long) b.x() << 32) ^ (b.z() & 0xffffffffL);
                if (height.getOrDefault(k, -1) < b.y()) {
                    height.put(k, b.y());
                    top.put(k, b.state());
                }
            }
        }, e -> { });
        for (int z = cz - radius; z <= cz + radius; z++) {
            StringBuilder a = new StringBuilder();
            StringBuilder h = new StringBuilder();
            for (int x = cx - radius; x <= cx + radius; x++) {
                long k = ((long) x << 32) ^ (z & 0xffffffffL);
                String s = top.get(k);
                a.append(s == null ? '.' : glyph(s));
                Integer y = height.get(k);
                h.append(y == null ? '.' : Character.forDigit(Math.min(35, y - 25), 36));
            }
            System.out.println(String.format("%5d ", z) + a + "   " + h);
        }
        System.out.println("Schilder im Feld:");
        for (RegionWorld.Entity s : signs) {
            if (Math.abs(s.x() - cx) <= radius && Math.abs(s.z() - cz) <= radius) {
                System.out.printf("  %d %d %d: %s%n", s.x(), s.y(), s.z(), RegionWorld.signText(s.data()));
            }
        }
    }

    private static char glyph(String s) {
        if (s.contains("redstone_wire")) {
            return 'R';
        }
        if (s.contains("repeater") || s.contains("comparator")) {
            return '=';
        }
        if (s.contains("torch")) {
            return 'i';
        }
        if (s.contains("piston")) {
            return 'P';
        }
        if (s.contains("lever") || s.contains("button")) {
            return 'L';
        }
        if (s.contains("lamp")) {
            return 'o';
        }
        if (s.contains("sign")) {
            return 'S';
        }
        return '#';
    }
}
