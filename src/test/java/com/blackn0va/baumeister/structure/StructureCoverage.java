package com.blackn0va.baumeister.structure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Messprobe (2026-11-08, Wunsch): Liest
 * jede Bauwerk-Datei eines Ordners mit StructureFile und meldet Format, Größe, Blöcke, unlesbare Zustände oder den
 * Grund des Scheiterns. Nur lesen, nichts schreiben.
 *
 * Aufruf: … com.blackn0va.baumeister.structure.StructureCoverage &lt;ordner&gt;
 */
public final class StructureCoverage {

    public static void main(String[] args) throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of(args[0]), 3)) {
            files = s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".litematic")
                        || n.endsWith(".nbt") || n.endsWith(".mcstructure");
            }).sorted().toList();
        }
        int ok = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            long kb = Files.size(f) / 1024;
            try {
                StructureFile.Structure st = StructureFile.read(f);
                int mx = 0;
                int my = 0;
                int mz = 0;
                for (var p : st.cells().keySet()) {
                    mx = Math.max(mx, p.x() + 1);
                    my = Math.max(my, p.y() + 1);
                    mz = Math.max(mz, p.z() + 1);
                }
                int bad = st.unreadable().values().stream().mapToInt(Integer::intValue).sum();
                System.out.printf("OK     %-34s %6d KB %-14s %4dx%3dx%-4d %8d Blöcke, unlesbar %d %s%n", name, kb,
                        st.format(), mx, my, mz, st.cells().size(), bad,
                        bad == 0 ? "" : top(st.unreadable()));
                ok++;
            } catch (Exception e) {
                System.out.printf("FEHLER %-34s %6d KB %s%n", name, kb, e.getMessage());
            }
        }
        System.out.printf("%d von %d gelesen%n", ok, files.size());
    }

    private static String top(Map<String, Integer> m) {
        return m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                .map(e -> e.getKey() + "×" + e.getValue()).toList().toString();
    }
}
