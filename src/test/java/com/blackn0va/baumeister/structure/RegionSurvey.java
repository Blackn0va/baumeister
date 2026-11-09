package com.blackn0va.baumeister.structure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Messprobe, kein Unit-Test: Wie ist eine Technik-Wiki-Welt aufgebaut? Blöcke je Höhe, häufigste Blöcke, Schilder mit
 * Text und Ort. Grundlage für das Ausschneiden (TechnikWikiImport) – erst messen, dann schneiden.
 *
 * Aufruf: java -cp … com.blackn0va.baumeister.structure.RegionSurvey &lt;weltordner&gt; [schilder-anzahl]
 */
public final class RegionSurvey {

    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        int showSigns = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        List<RegionWorld.Block> blocks = new ArrayList<>();
        List<RegionWorld.Entity> entities = new ArrayList<>();
        long t0 = System.nanoTime();
        RegionWorld.scan(world, blocks::add, entities::add);
        System.out.printf("Welt %s: %d Blöcke, %d Blockobjekte, %d ms%n", world.getFileName(), blocks.size(),
                entities.size(), (System.nanoTime() - t0) / 1_000_000);
        Map<Integer, Integer> perY = new TreeMap<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (RegionWorld.Block b : blocks) {
            perY.merge(b.y(), 1, Integer::sum);
            minX = Math.min(minX, b.x());
            maxX = Math.max(maxX, b.x());
            minZ = Math.min(minZ, b.z());
            maxZ = Math.max(maxZ, b.z());
        }
        System.out.printf("Ausdehnung x %d..%d, z %d..%d%n", minX, maxX, minZ, maxZ);
        System.out.println("Blöcke je Höhe (y: Anzahl):");
        perY.forEach((y, n) -> System.out.printf("  %4d: %d%n", y, n));
        System.out.println("Häufigste Blöcke:");
        RegionWorld.histogram(blocks).entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(45)
                .forEach(e -> System.out.printf("  %-45s %d%n", e.getKey(), e.getValue()));
        Map<String, Integer> kinds = new TreeMap<>();
        entities.forEach(e -> kinds.merge(e.id(), 1, Integer::sum));
        System.out.println("Blockobjekte: " + kinds);
        int shown = 0;
        for (RegionWorld.Entity e : entities) {
            if (e.id().endsWith("sign") && shown < showSigns) {
                String text = RegionWorld.signText(e.data());
                if (!text.isBlank()) {
                    System.out.printf("  Schild %d %d %d: %s%n", e.x(), e.y(), e.z(), text);
                    shown++;
                }
            }
        }
    }
}
