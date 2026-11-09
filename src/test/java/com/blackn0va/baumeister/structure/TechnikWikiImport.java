package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Schneidet die einzelnen Bauten aus den Download-Welten des deutschsprachigen Minecraft Technik Wikis aus und legt sie
 * als Strukturdateien (.nbt) samt index.tsv ab – für plugins/Baumeister/schematics/ (Wunsch 2026-11-08).
 *
 * Quelle: github.com/MinecraftTechnikWiki/{Redstone,Mechanik,Schienen}-Welt, Release 1.21a (Minecraft 1.21.11),
 * verlinkt von de.minecraft.wiki/w/Technik:Download-Welten; Wiki-Inhalte stehen unter CC BY-NC-SA 3.0. Die Dateien
 * bleiben auf dem eigenen Server – nicht im Repo, nicht im Paket für Dritte (die Repos haben keine eigene Lizenz).
 * Planet Minecraft dagegen verbietet systematisches Abgreifen und Weitergabe (Terms of Use 5 und 7) – von dort nichts.
 *
 * Aufbau der Welten (gemessen, RegionSurvey/RegionPlot): Boden bis y=25 (Sandstein, Stein, Grundgestein), je Technik
 * ein Feld im 32er-Raster mit einem Schilderhaus in der Mitte (Wandschild auf y=28 mit dem Namen), die Varianten als
 * getrennte Inseln darum. Geschnitten wird deshalb je Insel: Spalten mit Bau-Blöcken, die sich (auch über Eck)
 * berühren, bilden einen Bau; das Schilderhaus selbst fällt weg; der Name kommt vom nächsten Schild auf y=28.
 *
 * Weggelassen (mit Zählung im Bericht): Bauten mit Befehls-, Struktur- oder Verbundblöcken (ohne ihre Befehle tun sie
 * nichts, und Befehle aus fremden Dateien setzt der Baumeister nie), Barrieren und Lichtblöcke (unsichtbare Wände der
 * Schauwelt), Inseln unter 8 Blöcken (Deko) und über 96 × 64 × 96 (verbundene Bahnnetze).
 *
 * Aufruf: … TechnikWikiImport &lt;weltordner&gt; &lt;welt-name&gt; &lt;ausgabeordner&gt;
 * Threads: einer. Fehler: IOException bricht ab; ein unlesbarer Blockzustand lässt nur diesen Block weg (gezählt).
 */
public final class TechnikWikiImport {

    static final int GROUND_TOP = 25;
    static final int MARKER_Y = 28;
    static final int MAX_XZ = 96;
    static final int MAX_Y = 64;
    static final int MIN_BLOCKS = 8;
    private static final Set<String> GROUND = Set.of("minecraft:sandstone", "minecraft:stone", "minecraft:bedrock");
    private static final Set<String> DROP = Set.of("minecraft:barrier", "minecraft:light", "minecraft:structure_void");
    private static final Set<String> REFUSE = Set.of("minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:jigsaw");

    record Sign(int x, int y, int z, String text) {
    }

    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        String worldName = args[1];
        Path out = Path.of(args[2]);
        checkKeys();
        Files.createDirectories(out);
        Map<Long, String> build = new HashMap<>();
        // Unter der Oberfläche (y 13–25) alles, was kein Boden ist: vergrabenes Redstone, Feldböden. Es verbindet KEINE
        // Inseln – gemessen 2026-11-08 hingen sonst ganze Feldreihen über eingelassene Böden zusammen (704 × 832).
        Map<Long, String> under = new HashMap<>();
        List<Sign> signs = new ArrayList<>();
        int[] dropped = new int[1];
        // Durchgang 1: alle Bau-Blöcke (alles außer Boden bis y=25) und die Schilder.
        RegionWorld.scan(world, b -> {
            String id = id(b.state());
            if (DROP.contains(id)) {
                dropped[0]++;
                return;
            }
            if (b.y() <= GROUND_TOP && GROUND.contains(id)) {
                return;
            }
            if (b.y() < GROUND_TOP - 12) {
                return;
            }
            (b.y() > GROUND_TOP ? build : under).put(key(b.x(), b.y(), b.z()), b.state());
        }, e -> {
            if (e.id().endsWith("sign")) {
                String t = RegionWorld.signText(e.data());
                if (!t.isBlank()) {
                    signs.add(new Sign(e.x(), e.y(), e.z(), t));
                }
            }
        });
        List<Sign> y28 = signs.stream().filter(s -> s.y() == MARKER_Y).toList();
        // Spalten (x,z) mit Bau-Blöcken → Inseln über 8er-Nachbarschaft.
        Map<Long, List<Long>> columns = new HashMap<>();
        for (long k : build.keySet()) {
            columns.computeIfAbsent(col(x(k), z(k)), c -> new ArrayList<>()).add(k);
        }
        Set<Long> seen = new HashSet<>();
        List<List<Long>> islands = new ArrayList<>();
        for (long c : columns.keySet()) {
            if (!seen.add(c)) {
                continue;
            }
            List<Long> island = new ArrayList<>();
            ArrayDeque<Long> todo = new ArrayDeque<>(List.of(c));
            while (!todo.isEmpty()) {
                long cur = todo.poll();
                island.add(cur);
                int cx = (int) (cur >> 32);
                int cz = (int) cur;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = col(cx + dx, cz + dz);
                        if (columns.containsKey(n) && seen.add(n)) {
                            todo.add(n);
                        }
                    }
                }
            }
            islands.add(island);
        }
        // Schilderhaus = Insel mit Schild auf y=28, höchstens 9 × 9 und höchstens 4 über dem Boden (gemessen am Feld
        // Taktgeber: 9 × 9, Höhe 3). Hinweisschilder an Varianten hängen ebenfalls auf y=28 – deren Inseln sind größer.
        List<Sign> markers = new ArrayList<>();
        Set<Long> markerColumns = new HashSet<>();
        for (List<Long> island : islands) {
            int ix0 = Integer.MAX_VALUE;
            int ix1 = Integer.MIN_VALUE;
            int iz0 = Integer.MAX_VALUE;
            int iz1 = Integer.MIN_VALUE;
            int top = Integer.MIN_VALUE;
            Set<Long> cols = new HashSet<>(island);
            for (long c : island) {
                ix0 = Math.min(ix0, (int) (c >> 32));
                ix1 = Math.max(ix1, (int) (c >> 32));
                iz0 = Math.min(iz0, (int) c);
                iz1 = Math.max(iz1, (int) c);
                for (long k : columns.get(c)) {
                    top = Math.max(top, y(k));
                }
            }
            List<Sign> here = y28.stream().filter(s -> cols.contains(col(s.x(), s.z()))).toList();
            if (here.isEmpty()) {
                continue;
            }
            boolean small = ix1 - ix0 < 9 && iz1 - iz0 < 9 && top - GROUND_TOP <= 4;
            if (small) {
                markers.addAll(here);
                markerColumns.addAll(island);
            } else {
                System.out.printf("  kein Schilderhaus (%d×%d, Höhe %d): %s%n", ix1 - ix0 + 1, iz1 - iz0 + 1,
                        top - GROUND_TOP, here.stream().map(Sign::text).toList());
            }
        }
        // Durchgang 2: Boden unter den Inseln (eine Lage unter dem tiefsten Bau-Block je Spalte bis y=25) nachholen –
        // Redstone, Schienen und Pflanzen brauchen ihn als Halt.
        Map<Long, Integer> lowestUnder = new HashMap<>();
        for (long k : under.keySet()) {
            lowestUnder.merge(col(x(k), z(k)), y(k), Math::min);
        }
        Map<Long, Integer> floorFrom = new HashMap<>();
        for (List<Long> island : islands) {
            for (long c : island) {
                Integer low = lowestUnder.get(c);
                floorFrom.put(c, low == null ? GROUND_TOP : low - 1);
            }
        }
        Map<Long, String> ground = new HashMap<>();
        RegionWorld.scan(world, b -> {
            if (b.y() <= GROUND_TOP && GROUND.contains(id(b.state()))) {
                Integer from = floorFrom.get(col(b.x(), b.z()));
                if (from != null && b.y() >= from) {
                    ground.put(key(b.x(), b.y(), b.z()), b.state());
                }
            }
        }, e -> { });

        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, List<Map.Entry<String, Map<Pos, BlockSpec>>>> perPlot = new TreeMap<>();
        Map<String, int[]> anchor = new HashMap<>();
        int unreadable = 0;
        for (List<Long> island : islands) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            List<long[]> cells = new ArrayList<>();
            boolean refuse = false;
            boolean marker = false;
            int buildBlocks = 0;
            for (long c : island) {
                for (long k : columns.get(c)) {
                    String id = id(build.get(k));
                    refuse |= REFUSE.contains(id);
                    buildBlocks++;
                    cells.add(new long[] {k});
                }
                marker |= markerColumns.contains(c);
            }
            if (marker) {
                skipped.merge("schilderhaus", 1, Integer::sum);
                continue;
            }
            if (refuse) {
                skipped.merge("befehls-/strukturblock", 1, Integer::sum);
                continue;
            }
            if (buildBlocks < MIN_BLOCKS) {
                skipped.merge("unter " + MIN_BLOCKS + " Blöcke", 1, Integer::sum);
                continue;
            }
            Map<Long, String> all = new HashMap<>();
            for (long[] c : cells) {
                all.put(c[0], build.get(c[0]));
            }
            for (long c : island) {
                int cx = (int) (c >> 32);
                int cz = (int) c;
                for (int y = floorFrom.get(c); y <= GROUND_TOP; y++) {
                    String g = under.get(key(cx, y, cz));
                    if (g == null) {
                        g = ground.get(key(cx, y, cz));
                    }
                    if (g != null) {
                        refuse |= REFUSE.contains(id(g));
                        all.putIfAbsent(key(cx, y, cz), g);
                    }
                }
            }
            if (refuse) {
                skipped.merge("befehls-/strukturblock", 1, Integer::sum);
                continue;
            }
            for (long k : all.keySet()) {
                minX = Math.min(minX, x(k));
                maxX = Math.max(maxX, x(k));
                minY = Math.min(minY, y(k));
                maxY = Math.max(maxY, y(k));
                minZ = Math.min(minZ, z(k));
                maxZ = Math.max(maxZ, z(k));
            }
            if (maxX - minX + 1 > MAX_XZ || maxZ - minZ + 1 > MAX_XZ || maxY - minY + 1 > MAX_Y) {
                skipped.merge("zu groß", 1, Integer::sum);
                continue;
            }
            Map<Pos, BlockSpec> spec = new LinkedHashMap<>();
            for (Map.Entry<Long, String> e : all.entrySet()) {
                try {
                    spec.put(new Pos(x(e.getKey()) - minX, y(e.getKey()) - minY, z(e.getKey()) - minZ),
                            BlockSpec.parse(e.getValue()));
                } catch (RuntimeException | com.blackn0va.baumeister.plan.PlanException ex) {
                    unreadable++;
                }
            }
            int midX = (minX + maxX) / 2;
            int midZ = (minZ + maxZ) / 2;
            Sign near = markers.stream().min(Comparator.comparingDouble(s -> Math.hypot(s.x() - midX, s.z() - midZ)))
                    .orElse(null);
            if (near == null || Math.hypot(near.x() - midX, near.z() - midZ) > 48) {
                // Ohne Namensschild in der Nähe weiß niemand, was das ist – und niemand kann es gezielt bauen lassen.
                skipped.merge("ohne Namensschild", 1, Integer::sum);
                continue;
            }
            String plot = near.text();
            perPlot.computeIfAbsent(plot, p -> new ArrayList<>()).add(Map.entry(minZ + ":" + minX, spec));
            if (near != null) {
                anchor.put(plot, new int[] {near.x(), near.z()});
            }
        }
        // Je Feld nach Lage nummerieren (von Nord nach Süd, dann West nach Ost), damit die Nummer bei gleichem
        // Weltstand gleich bleibt.
        List<String> index = new ArrayList<>();
        index.add("# datei\tname\tanzeige\tbeschreibung\tquelle");
        int files = 0;
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, List<Map.Entry<String, Map<Pos, BlockSpec>>>> e : perPlot.entrySet()) {
            String display = tidy(e.getKey());
            String base = slug(display);
            List<Map.Entry<String, Map<Pos, BlockSpec>>> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing((Map.Entry<String, Map<Pos, BlockSpec>> m) -> Integer.parseInt(
                    m.getKey().split(":")[0])).thenComparing(m -> Integer.parseInt(m.getKey().split(":")[1])));
            for (int i = 0; i < list.size(); i++) {
                String name = trim(base, 32 - 1 - String.valueOf(i + 1).length()) + "_" + (i + 1);
                if (!names.add(name)) {
                    name = trim(base, 26) + "_" + Integer.toHexString(files);
                    names.add(name);
                }
                Map<Pos, BlockSpec> cells = list.get(i).getValue();
                String file = name + ".nbt";
                Files.write(out.resolve(file), NbtWriter.structureNbt(cells));
                int[] a = anchor.get(e.getKey());
                Pos size = size(cells);
                index.add(String.join("\t", file, name, display + ", Variante " + (i + 1),
                        display + " aus der " + worldName + " (" + size.x() + "×" + size.y() + "×" + size.z() + ", "
                                + cells.size() + " Blöcke" + (a == null ? "" : ", Feld bei " + a[0] + " " + a[1])
                                + "), ungeprüft",
                        "Technik Wiki (de.minecraft.wiki), " + worldName + " 1.21a, CC BY-NC-SA 3.0"));
                files++;
            }
        }
        Files.write(out.resolve("index.tsv"), index, StandardCharsets.UTF_8);
        System.out.printf("%s: %d Inseln, %d Dateien in %d Feldern, weggelassen %s, Barrieren/Licht entfernt %d, "
                + "unlesbare Blöcke %d%n", worldName, islands.size(), files, perPlot.size(), skipped, dropped[0],
                unreadable);
    }

    static Pos size(Map<Pos, BlockSpec> cells) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (Pos p : cells.keySet()) {
            x = Math.max(x, p.x() + 1);
            y = Math.max(y, p.y() + 1);
            z = Math.max(z, p.z() + 1);
        }
        return new Pos(x, y, z);
    }

    /** „Tür- und Toranlage“, „Musik- Walzenspieler“ → ohne Silbentrennung der Schildzeilen. */
    static String tidy(String sign) {
        // „Stein- und …“ bleibt (Ergänzungsstrich), „Musik- walzen…“ wird zusammengezogen, „Spieler- Dropfarm“ wird
        // „Spieler-Dropfarm“ (Schildzeilen trennen mit „- “).
        String s = sign.replaceAll("\\s+", " ").strip();
        s = s.replaceAll("(\\p{L})- (?!(?:und|oder|bzw)\\b)(\\p{Ll})", "$1$2");
        return s.replaceAll("(\\p{L})- (\\p{Lu})", "$1-$2");
    }

    /** Bauteil-Name: klein, nur a–z 0–9 _, Umlaute ausgeschrieben (Namen tippt man auch ohne Umlaut-Tastatur). */
    static String slug(String display) {
        String s = display.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss"); // umlaut-ok: Bauteil-Namen sind ASCII
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "bau" : s;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).replaceAll("_+$", "");
    }

    private static String id(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) << 38) | ((long) (y + 512) << 26) | (z + (1 << 20));
    }

    private static int x(long k) {
        return (int) (k >>> 38) - (1 << 20);
    }

    private static int y(long k) {
        return (int) ((k >>> 26) & 0xfff) - 512;
    }

    private static int z(long k) {
        return (int) (k & ((1 << 26) - 1)) - (1 << 20);
    }

    private static long col(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private TechnikWikiImport() {
    }

    /** Selbstprüfung der Schlüssel-Packung über die ganze Weltgröße der drei Welten. */
    static void checkKeys() throws IOException {
        for (int[] p : new int[][] {{-1536, -64, 1535}, {1535, 319, -1536}, {0, 0, 0}}) {
            long k = key(p[0], p[1], p[2]);
            if (x(k) != p[0] || y(k) != p[1] || z(k) != p[2]) {
                throw new IOException("Schlüssel kaputt für " + java.util.Arrays.toString(p));
            }
        }
    }
}
