package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SchematicCatalog;
import com.blackn0va.baumeister.plan.TemplateModules;
import com.blackn0va.baumeister.structure.StructureFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Bauwerk-Dateien als Bauteile – ohne WorldEdit (Wunsch 2026-11-08). Jede .schem (v1–v3), .schematic (MCEdit, vor
 * 1.13), .litematic und .nbt unter plugins/Baumeister/schematics/ (auch in Unterordnern)
 * wird ein Bauteil: /bm maschine &lt;name&gt;, „bau mir einen Taktgeber“ im Chat (SchematicCatalog.match) und als Hinweis
 * im LLM-Auftrag, wenn der Name darin vorkommt.
 *
 * Namen: Liegt im Ordner eine index.tsv (datei, name, anzeige, beschreibung, quelle – so schreibt sie der Import der
 * Technik-Wiki-Welten), gelten deren Angaben. Sonst heißt das Bauteil wie die Datei, klein und mit _ statt
 * Sonderzeichen. Namen außerhalb [a-z0-9_]{2,32} und doppelte Namen fallen weg (Warnung im Log).
 *
 * Gelesen wird erst beim ersten Bau eines Bauteils (StructureFile, Grenzen gegen kaputte Dateien dort), danach aus
 * einem kleinen Speicher. Blockdaten (Truhen, Schilder, Befehle) liest StructureFile nicht – Bauten aus fremden Dateien
 * bringen also nie Inhalte oder Befehle mit; jeder Block geht beim Setzen durch die Stufen (PlacementGate) wie bei
 * jedem anderen Bau.
 *
 * Ordner neu lesen: höchstens alle 30 s, wenn sich Anzahl oder jüngste Änderungszeit der Dateien geändert hat – neue
 * Dateien gelten ohne Neustart.
 *
 * Threads: Hauptthread (Befehle, Planen). Fehler: unlesbare Datei → Bauteil fehlt, einmal im Log; nie eine Ausnahme
 * nach außen (fail-open: dann baut eben das LLM).
 */
final class FileSchematics implements ModuleLibrary {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final long RESCAN_MILLIS = 30_000;
    private static final int CACHE = 48;
    private static final int MAX_DEPTH = 4;
    /** Sperrliste im Schematic-Ordner (readBlocked). */
    static final String BLOCKED = "gesperrt.txt";

    /** Eintrag samt Datei. */
    /** ground: Lagen unter der Oberfläche (6. Spalte der index.tsv, sonst 0); game: aus /bm spielbauwerk. */
    record Item(SchematicCatalog.Entry entry, Path file, int ground, boolean game) {
    }

    private final Path folder;
    private final LongSupplier clock;
    private Map<String, Item> items = Map.of();
    private SchematicCatalog catalog = new SchematicCatalog(List.of());
    private String signature = "";
    private long lastScan = Long.MIN_VALUE / 2;
    private final Map<String, Module> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Module> eldest) {
            return size() > CACHE;
        }
    };
    private final java.util.Set<String> reported = new java.util.HashSet<>();

    FileSchematics(Path folder) {
        this(folder, System::currentTimeMillis);
    }

    FileSchematics(Path folder, LongSupplier clock) {
        this.folder = folder.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** Verzeichnis (nach einem Blick in den Ordner, höchstens alle 30 s). */
    synchronized SchematicCatalog catalog() {
        refresh();
        return catalog;
    }

    @Override
    public synchronized Optional<Module> find(String name) {
        refresh();
        String n = name.toLowerCase(Locale.ROOT);
        Item item = items.get(n);
        if (item == null) {
            return Optional.empty();
        }
        Module cached = cache.get(n);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            // Spiel-Vorlagen samt ausgegrabener Luft (Fallgrube, Keller); alle anderen wie bisher ohne Luft.
            StructureFile.Structure s = item.game() ? StructureFile.readKeepingAir(item.file())
                    : StructureFile.read(item.file());
            List<TemplateModules.RawCell> raw = new ArrayList<>(s.cells().size());
            for (Map.Entry<Pos, BlockSpec> e : s.cells().entrySet()) {
                raw.add(new TemplateModules.RawCell(e.getKey().x(), e.getKey().y(), e.getKey().z(),
                        e.getValue().asString()));
            }
            String desc = item.entry().description().isEmpty() ? item.entry().display()
                    : item.entry().description();
            Module m = TemplateModules.build(n, desc, raw, item.game()).module();
            cache.put(n, m);
            if (!s.unreadable().isEmpty()) {
                LOG.warning(() -> "schematics | bloecke_unbekannt | name=" + n + " " + s.unreadable());
            }
            return Optional.of(m);
        } catch (IOException | RuntimeException e) {
            if (reported.add(n)) {
                LOG.log(Level.WARNING, "schematics | unlesbar | name=" + n + " datei=" + folder.relativize(item.file())
                        + " grund=" + e.getMessage());
            }
            return Optional.empty();
        }
    }

    /** Bodenversatz aus der 6. Spalte; 0 bei fehlender oder unlesbarer Zahl (Dateien von vor 2026-11-15). */
    static int ground(String[] row) {
        if (row == null || row.length < 6 || !row[5].strip().matches("\\d{1,3}")) {
            return 0;
        }
        return Integer.parseInt(row[5].strip());
    }

    /** Wie tief eine Vorlage unter den Füßen beginnt (FileSchematics-Eintrag); 0, wenn unbekannt. */
    public synchronized int groundOf(String name) {
        refresh();
        Item item = items.get(name.toLowerCase(Locale.ROOT));
        return item == null ? 0 : item.ground();
    }

    /** Leer: Hunderte Namen gehören nicht in jeden LLM-Prompt (SchematicCatalog.hint statt dessen). */
    @Override
    public List<Module> all() {
        return List.of();
    }

    private void refresh() {
        long now = clock.getAsLong();
        if (now - lastScan < RESCAN_MILLIS) {
            return;
        }
        lastScan = now;
        List<Path> files = files();
        long newest = 0;
        for (Path f : files) {
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
            } catch (IOException e) {
                LOG.log(Level.FINE, "schematics | zeit", e);
            }
        }
        String sig = files.size() + ":" + newest;
        if (sig.equals(signature)) {
            return;
        }
        signature = sig;
        Map<String, Item> next = new LinkedHashMap<>();
        Map<Path, Map<String, String[]>> indexes = new HashMap<>();
        java.util.Set<String> blocked = readBlocked(folder);
        int bad = 0;
        int barred = 0;
        for (Path f : files) {
            String fileName = f.getFileName().toString();
            if (fileName.equals("index.tsv") || fileName.equals(BLOCKED)) {
                continue;
            }
            String[] row = indexes.computeIfAbsent(f.getParent(), FileSchematics::readIndex).get(fileName);
            String name = row != null && row.length > 1 ? row[1].strip() : slug(stem(fileName));
            if (!SchematicCatalog.NAME.matcher(name).matches() || next.containsKey(name)) {
                bad++;
                continue;
            }
            if (blocked.contains(name)) {
                barred++;
                continue;
            }
            SchematicCatalog.Entry e = SchematicCatalog.entry(name, row != null && row.length > 2 ? row[2] : null,
                    row != null && row.length > 3 ? row[3] : null, row != null && row.length > 4 ? row[4] : null);
            boolean game = GameStructures.SOURCE.equals(e.source());
            if (row == null && f.getParent() != null && f.getParent().getFileName().toString().equals("spiel")) {
                // In spiel/ zählt nur, was eine index-Zeile hat: Eine Datei ohne Zeile ist ein Rest eines abgebrochenen
                // Laufs (Review 2026-11-15: ungestrichener Gesteinsklotz als eigene Familie im Katalog).
                bad++;
                continue;
            }
            next.put(name, new Item(e, f, ground(row), game));
        }
        items = Map.copyOf(next);
        catalog = new SchematicCatalog(next.values().stream().map(Item::entry).toList(), GameStructures.ALIASES,
                Map.of(GameStructures.TAG, GameStructures.SOURCE, "technik", "Technik Wiki"));
        cache.clear();
        int skipped = bad;
        int locked = barred;
        LOG.info(() -> "schematics | bereit | ordner=" + folder.getFileName() + " bauteile=" + next.size()
                + " familien=" + catalog.families("").size() + " verworfen=" + skipped + " gesperrt=" + locked);
    }

    /**
     * gesperrt.txt im Schematic-Ordner: je Zeile ein Bauteilname, „#“ leitet einen Grund ein. Gesperrte Namen stehen
     * weder im Katalog noch lassen sie sich bauen. Anlass 2026-11-14 (Wunsch: keine Lag-Maschinen): Die Leerlauf-
     * Messung aller 804 Technik-Wiki-Bauten (LagWatch) fand vier Dauerläufer über 50 Schaltvorgängen je Sekunde ohne
     * Eingabe; die Liste liegt beim Server, weil die Namen aus der fremden Sammlung kommen. Fehlt die Datei, ist nichts
     * gesperrt.
     */
    static java.util.Set<String> readBlocked(Path folder) {
        Path f = folder.resolve(BLOCKED);
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String n = line.replaceAll("#.*", "").strip().toLowerCase(Locale.ROOT);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        } catch (IOException e) {
            // fail-open für den Katalog: Ohne lesbare Liste ist nichts gesperrt, der Lag-Wächter greift nach dem Bau.
            LOG.log(Level.WARNING, "schematics | sperrliste_unlesbar | datei=" + f, e);
        }
        return out;
    }

    /** Bauwerk-Dateien und index.tsv unter dem Ordner, nur echte Dateien darin (keine Links nach draußen). */
    private List<Path> files() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(folder, MAX_DEPTH)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toAbsolutePath().normalize().startsWith(folder))
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".nbt") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".litematic")
                                || n.equals("index.tsv") || n.equals(BLOCKED);
                    }).sorted().toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "schematics | ordner_unlesbar | ordner=" + folder, e);
            return List.of();
        }
    }

    /** index.tsv: datei → Spalten (datei, name, anzeige, beschreibung, quelle); Zeilen mit # sind Kommentar. */
    static Map<String, String[]> readIndex(Path dir) {
        Path idx = dir.resolve("index.tsv");
        Map<String, String[]> out = new HashMap<>();
        if (!Files.isRegularFile(idx)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(idx, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length >= 2) {
                    out.put(cols[0].strip(), cols);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "schematics | index_unlesbar | datei=" + idx, e);
        }
        return out;
    }

    static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Dateiname → Bauteil-Name: klein, Umlaute ausgeschrieben, sonst nur a–z 0–9 _, höchstens 32 Zeichen. */
    static String slug(String stem) {
        String s = com.blackn0va.baumeister.plan.RequestHints.normalize(stem).replace(' ', '_');
        return s.length() > 32 ? s.substring(0, 32).replaceAll("_+$", "") : s;
    }
}
