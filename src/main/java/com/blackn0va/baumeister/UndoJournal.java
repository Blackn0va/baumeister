package com.blackn0va.baumeister;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rückgängig über Neustart: der Stapel von /bm rückgängig auf der Platte (plugins/Baumeister/rückgängig/).
 *
 * Vorher lag der Stapel nur im Speicher, und BaumeisterPlugin löschte beim Start den ganzen Ordner – nach jedem
 * Neustart (dev01 startet bei jedem Deploy neu) war ein schiefer Bau nur noch von Hand wegzuräumen (Gedächtnis
 * „Offen“). Jetzt bekommt jeder Eintrag einen Ordner mit Begleitzettel (eintrag.json: Auftraggeber, Welt, Zeit, Zahl
 * der Blöcke) und den Teildateien von UndoSpool. Stadtbauten schreiben ihre Teile ohnehin auf die Platte, dort kommt
 * nur der Zettel dazu; normale Bauten (BuildJob, Rückgängig im Speicher) werden im Hintergrund als Kopie geschrieben.
 * Der Zettel kommt immer zuletzt (atomar umbenannt) – ein Ordner ohne Zettel ist ein abgebrochener Schreibvorgang oder
 * eine Weltbau-Kachel (TileUndo, eigener Speicher) und wird beim Start wie vorher gelöscht.
 *
 * Beim Laden gilt fail-closed (ASVS V5.1.3, V12.3.1 – Dateien als nicht vertrauenswürdige Eingabe): Ordnername nach
 * festem Muster, keine Verknüpfungen (Symlink, Junction) – weder gelesen noch durchlaufen, nur der Verweis selbst wird
 * entfernt –, im Ordner nur der Zettel und lückenlos nummerierte Teile, jeder Teil vollständig lesbar, jeder Zustand ein
 * Blockzustand, die Summe der Einträge gleich der Zahl auf dem Zettel. Ein abgeschnittenes gzip liest UndoSpool.read
 * ohne Fehler als kürzeren Teil – erst der Zahlenvergleich fängt das. Was eine Prüfung nicht besteht, wird nicht in den
 * Stapel genommen, sondern gelöscht. Pfade stehen nie im Zettel; die Welt ist nur ein Name, den Bukkit auflöst.
 *
 * Grenzen: höchstens bau.undo-tiefe Einträge, nicht älter als komfort.rückgängig-max-tage, zusammen höchstens
 * komfort.rückgängig-max-mb – die neuesten zuerst, ältere fallen samt Dateien heraus.
 *
 * Threads: Kopien schreiben, löschen und beim Start prüfen im eigenen Thread „Baumeister-Rückgängig“ (eine Reihe, also
 * nie Löschen vor Schreiben). sweep() läuft synchron in onEnable. Fehler: Schreibfehler sind fail-open (nur geloggt –
 * Rückgängig in der laufenden Sitzung geht weiter, nur der Neustart wird nicht überlebt); Lesefehler fail-closed (s. o.).
 */
final class UndoJournal {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Ordner unter dem Datenordner – derselbe, den CityService und WorldBuildService für ihre Spools nehmen. */
    static final String DIR = "rückgängig";
    /**
     * Weltbau-Kacheln (2026-11-14): eigener Ordner mit eigenen Grenzen, damit Kacheln weder den Stapel von
     * /bm rückgängig verdrängen (Prüfbefund 2026-10-28, siehe TileUndo) noch beim Start mit ihm um bau.undo-tiefe
     * konkurrieren. Vorher lagen sie ohne Zettel unter DIR, und jeder Start löschte sie.
     */
    static final String TILE_DIR = "rückgängig-weltbau";
    /**
     * Wiederherstellen beim Start (2026-11-17): Einträge im selben Format wie DIR, die der Betreiber hier ablegt,
     * setzt der nächste Start zurück und löscht sie danach (ComfortService.restoreAtStart). WOZU: dev01 hat weder
     * Konsole noch RCON. Den Hohlraum vom 2026-11-16 (LLM, fill air) konnte /bm rückgängig nicht mehr zurücknehmen –
     * sein Eintrag war nach fünf neueren Bauten still aus dem Stapel gefallen –, und ohne Konsole gab es keinen anderen
     * Weg in die laufende Welt als einen Spieler im Spiel. Geprüft wird wie im Stapel (validate, keepKnown),
     * zurückgesetzt wie mit /bm rückgängig: je Zelle nur, wo noch steht, was der Eintrag als gesetzt nennt (UndoCheck).
     * Vertrauensgrenze: Wer hier schreiben kann, kann auch das Plugin-Jar tauschen – der Ordner gibt keine neuen
     * Rechte.
     */
    static final String RESTORE_DIR = "wiederherstellen";
    /** Höchstens so viele Einträge je Start aus RESTORE_DIR; ältere fallen wie im Stapel samt Dateien heraus. */
    static final int RESTORE_MAX_ENTRIES = 20;
    /** Kachel auf dem Zettel: „tx,tz“. */
    private static final Pattern TILE = Pattern.compile("-?\\d{1,7},-?\\d{1,7}");
    static final String MANIFEST = "eintrag.json";
    private static final int VERSION = 1;
    /** Stadtbau: Millisekunden (CityService), normaler Bau: bau-&lt;Millisekunden&gt;-&lt;Nummer&gt; (persistCopy). */
    private static final Pattern DIR_NAME = Pattern.compile("\\d{1,19}|bau-\\d{1,19}-\\d{1,9}");
    private static final Pattern PART_NAME = Pattern.compile("teil-\\d{5}\\.bin\\.gz");
    /** BlockData.getAsString(): minecraft:oak_stairs[facing=north,half=bottom,…]. */
    private static final Pattern STATE = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+(\\[[a-z0-9_=,\\-]*])?");
    private static final Pattern WORLD = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_ .\\-]{1,40}");
    private static final long MAX_MANIFEST_BYTES = 4096;
    static final long MAX_COUNT = 200_000_000L;
    /** MAX_COUNT / UndoSpool.PART aufgerundet. */
    private static final int MAX_PARTS = 1000;
    /** Weltgrenze von Minecraft (29 999 984) plus Luft; y großzügig über die hohen Welten (−2032..2031) hinaus. */
    private static final int MAX_XZ = 30_000_000;
    private static final int MAX_Y = 4096;
    private static final long FUTURE_SLACK_MILLIS = 86_400_000L;
    /**
     * Verschiedene Zustände je Eintrag. Eine Großstadt hat einige Tausend (Treppen, Zäune, Wände mit Richtungen);
     * mehr ist kein Bau mehr, sondern eine präparierte Datei – und jeder Zustand wird beim Laden einmal geparst.
     */
    static final int MAX_STATES = 65_536;

    /** Was auf dem Zettel steht. owner null = Konsole. tile: Weltbau-Kachel „tx,tz“ oder null (normaler Eintrag). */
    record Manifest(UUID owner, String ownerName, String world, long when, long count, String tile) {

        Manifest(UUID owner, String ownerName, String world, long when, long count) {
            this(owner, ownerName, world, when, count, null);
        }

        Manifest withTile(int tx, int tz) {
            return new Manifest(owner, ownerName, world, when, count, tx + "," + tz);
        }
    }

    /**
     * Ein geprüfter Eintrag. bytes = Platz auf der Platte (Zettel + Teile); states = die verschiedenen Zustände, damit
     * der Hauptthread sie vor der Übernahme gegen die laufende Serverfassung prüfen kann (keepKnown).
     */
    record Loaded(Path dir, Manifest manifest, long bytes, Set<String> states) {
    }

    record Limits(int depth, long maxAgeMillis, long maxBytes) {

        static Limits of(int depth, ComfortSettings c) {
            return new Limits(depth, c.undoMaxDays() * 86_400_000L, c.undoMaxMb() * 1024L * 1024L);
        }
    }

    private final Path root;
    private final boolean enabled;
    /**
     * Nicht final: Beim Neuladen übernimmt das neue Journal den Thread des alten (takeOver), damit alles in einer Reihe
     * bleibt, ohne dass der Hauptthread wartet. null = an ein neues Journal übergeben.
     */
    private ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Rückgängig");
        t.setDaemon(true);
        return t;
    });
    /** Welten, deren Name nicht auf den Zettel passt – je Name nur eine Logzeile. */
    private final Set<String> unstorableWorlds = new HashSet<>();
    /** Kopien normaler Bauten: Stapel-Eintrag (Identität) → Ordner. */
    private final Map<Object, Path> copies = new IdentityHashMap<>();
    private long seq;

    UndoJournal(Path root, boolean enabled) {
        this.root = root;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    Path root() {
        return root;
    }

    // ---------------------------------------------------------------- Start

    /**
     * Beim Start, bevor ein Bau einen Spool anlegen kann: alles ohne Zettel löschen (wie vorher den ganzen Ordner),
     * Ordner mit Zettel als Kandidaten zurückgeben. keep=false (Rückgängig über Neustart aus): alles löschen.
     * Ist der Ordner selbst eine Verknüpfung, wird nichts angefasst – sonst liefe das Löschen im Ziel.
     */
    static List<Path> sweep(Path root, boolean keep) {
        return sweep(root, keep, "rückgängig");
    }

    /** Wie sweep(root, keep); area steht vorn in der Logzeile (RESTORE_DIR: „wiederherstellen“). */
    static List<Path> sweep(Path root, boolean keep, String area) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            if (UndoSpool.isLink(root)) {
                LOG.warning(() -> area + " | ordner_ist_verknüpfung | nichts angefasst");
                return List.of();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, area + " | ordner_unlesbar | " + e.getMessage(), e);
            return List.of();
        }
        List<Path> kept = new ArrayList<>();
        int deleted = 0;
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.toList()) {
                if (keep && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !UndoSpool.isLink(p)
                        && Files.isRegularFile(p.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS)) {
                    kept.add(p);
                } else {
                    deleteEntry(p);
                    deleted++;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, area + " | ordner_unlesbar | " + e.getMessage(), e);
        }
        kept.sort(Comparator.comparing(p -> p.getFileName().toString()));
        int d = deleted;
        LOG.info(() -> area + " | start | kandidaten=" + kept.size() + " verwaist_gelöscht=" + d + " behalten=" + keep);
        return kept;
    }

    /** Prüfen im Hintergrund, Ergebnis über toMain auf den Hauptthread (Bukkit.runTask). */
    void loadAsync(List<Path> candidates, Limits limits, Consumer<Runnable> toMain, Consumer<List<Loaded>> done) {
        if (candidates.isEmpty()) {
            return;
        }
        io.execute(() -> {
            List<Loaded> ok = validate(candidates, limits, System.currentTimeMillis());
            toMain.accept(() -> done.accept(ok));
        });
    }

    /**
     * Kandidaten prüfen, nach Zeit absteigend sortieren und die Grenzen anwenden. Verworfene Ordner werden gelöscht.
     * Ist eine Grenze (Tiefe, Platz) erreicht, fallen alle älteren heraus – ein Stapel mit Lücken wäre für den Spieler
     * nicht mehr nachvollziehbar.
     */
    static List<Loaded> validate(List<Path> candidates, Limits limits, long nowMillis) {
        List<Loaded> ok = new ArrayList<>();
        for (Path dir : candidates) {
            try {
                ok.add(check(dir, nowMillis, limits.maxAgeMillis()));
            } catch (IOException | RuntimeException e) {
                // Grenze zu Daten von der Platte (nicht vertrauenswürdig): Jede Ausnahme – kaputtes gzip, falsches
                // JSON, Index außerhalb des Wörterbuchs – heißt „Eintrag unbrauchbar“, nie „Plugin startet nicht“.
                // Bewusst fail-closed: kein Teil-Rückgängig aus einem beschädigten Eintrag.
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.warning(() -> "rückgängig | eintrag_verworfen | ordner=" + safe(dir.getFileName().toString())
                        + " grund=" + safe(why));
                deleteEntry(dir);
            }
        }
        ok.sort(Comparator.comparingLong((Loaded l) -> l.manifest().when())
                .thenComparing(l -> l.dir().getFileName().toString()).reversed());
        List<Loaded> kept = new ArrayList<>();
        long bytes = 0;
        boolean full = false;
        for (Loaded l : ok) {
            full = full || kept.size() >= limits.depth() || bytes + l.bytes() > limits.maxBytes();
            if (full) {
                LOG.info(() -> "rückgängig | eintrag_über_grenze | ordner=" + l.dir().getFileName());
                deleteEntry(l.dir());
                continue;
            }
            kept.add(l);
            bytes += l.bytes();
        }
        return kept;
    }

    /** Einen Eintrag vollständig prüfen; IOException mit kurzem Grund, wenn etwas nicht stimmt. */
    static Loaded check(Path dir, long nowMillis, long maxAgeMillis) throws IOException {
        String name = dir.getFileName().toString();
        if (!DIR_NAME.matcher(name).matches()) {
            throw new IOException("ordnername_ungültig");
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || UndoSpool.isLink(dir)) {
            throw new IOException("kein_ordner");
        }
        Manifest m = readManifest(dir);
        if (m.when() > nowMillis + FUTURE_SLACK_MILLIS) {
            throw new IOException("zeit_in_der_zukunft");
        }
        if (nowMillis - m.when() > maxAgeMillis) {
            throw new IOException("zu_alt");
        }
        List<Path> parts = new ArrayList<>();
        long bytes = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String fn = p.getFileName().toString();
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("keine_datei=" + fn);
                }
                bytes += Files.size(p);
                if (fn.equals(MANIFEST)) {
                    continue;
                }
                if (!PART_NAME.matcher(fn).matches()) {
                    throw new IOException("fremde_datei=" + fn);
                }
                parts.add(p);
            }
        }
        if (parts.isEmpty() || parts.size() > MAX_PARTS) {
            throw new IOException("teile=" + parts.size());
        }
        parts.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (int i = 0; i < parts.size(); i++) {
            if (!parts.get(i).getFileName().toString().equals(String.format("teil-%05d.bin.gz", i + 1))) {
                throw new IOException("teile_lücke_bei=" + (i + 1));
            }
        }
        long total = 0;
        Set<String> states = new HashSet<>();
        for (Path p : parts) {
            // Obergrenze je Teil (UndoSpool schreibt nie mehr als PART): Ohne sie verdoppelte read() seine Felder
            // unbegrenzt, und eine präparierte gzip-Datei triebe den Server in einen OutOfMemoryError.
            UndoSpool.Part part = UndoSpool.read(p, UndoSpool.PART);
            for (int i = 0; i < part.size(); i++) {
                check(states, part.state()[i]);
                // Gesetzter Typ (Format 2) wie ein Zustand: Muster, Obergrenze, beim Start gegen die Serverfassung.
                String placed = part.placedAt(i);
                if (placed != null) {
                    check(states, placed);
                }
                if (Math.abs(part.x()[i]) > MAX_XZ || Math.abs(part.z()[i]) > MAX_XZ || Math.abs(part.y()[i]) > MAX_Y) {
                    throw new IOException("koordinate_ungültig");
                }
            }
            total += part.size();
        }
        if (total != m.count()) {
            throw new IOException("anzahl_passt_nicht zettel=" + m.count() + " teile=" + total);
        }
        return new Loaded(dir, m, bytes, Set.copyOf(states));
    }

    private static void check(Set<String> states, String st) throws IOException {
        if (states.contains(st)) {
            return;
        }
        if (!STATE.matcher(st).matches()) {
            throw new IOException("zustand_ungültig");
        }
        if (states.size() >= MAX_STATES) {
            throw new IOException("zu_viele_zustände");
        }
        states.add(st);
    }

    /**
     * Hauptthread, nach validate: Einträge mit einem Zustand, den known nicht kennt (Bukkit.createBlockData dieser
     * Serverfassung), verwerfen und im io-Thread löschen. Die übrigen kommen zurück. Fail-closed wie validate: lieber
     * kein Rückgängig als eines, das beim Zurücksetzen mitten im Timer scheitert.
     */
    List<Loaded> keepKnown(List<Loaded> loaded, Predicate<String> known) {
        List<Loaded> ok = new ArrayList<>();
        for (Loaded l : loaded) {
            String bad = null;
            for (String st : l.states()) {
                if (!known.test(st)) {
                    bad = st;
                    break;
                }
            }
            if (bad == null) {
                ok.add(l);
                continue;
            }
            // Unentschärft: Der Zustand hat in check() schon das Muster STATE bestanden (keine Steuerzeichen).
            String b = bad;
            LOG.warning(() -> "rückgängig | eintrag_verworfen | ordner=" + safe(l.dir().getFileName().toString())
                    + " grund=zustand_unbekannt zustand=" + b);
            Path dir = l.dir();
            later(() -> deleteEntry(dir));
        }
        return ok;
    }

    // ---------------------------------------------------------------- Zettel

    static Manifest readManifest(Path dir) throws IOException {
        Path f = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || UndoSpool.isLink(f)) {
            throw new IOException("zettel_fehlt");
        }
        if (Files.size(f) > MAX_MANIFEST_BYTES) {
            throw new IOException("zettel_zu_groß");
        }
        JsonElement el;
        try {
            el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IOException("zettel_kein_json", e);
        }
        if (!el.isJsonObject()) {
            throw new IOException("zettel_kein_objekt");
        }
        JsonObject o = el.getAsJsonObject();
        if (number(o, "version") != VERSION) {
            throw new IOException("zettel_version");
        }
        UUID owner = null;
        JsonElement ow = o.get("besitzer");
        if (ow != null && !ow.isJsonNull()) {
            String s = text(o, "besitzer");
            owner = UUID.fromString(s);
            if (!owner.toString().equals(s.toLowerCase(java.util.Locale.ROOT))) {
                throw new IOException("besitzer_ungültig");
            }
        }
        String ownerName = text(o, "name");
        if (!NAME.matcher(ownerName).matches()) {
            throw new IOException("name_ungültig");
        }
        String world = text(o, "welt");
        if (!WORLD.matcher(world).matches() || world.startsWith(".")) {
            throw new IOException("welt_ungültig");
        }
        long when = number(o, "zeit");
        long count = number(o, "anzahl");
        if (when <= 0) {
            throw new IOException("zeit_ungültig");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IOException("anzahl_ungültig");
        }
        String tile = null;
        JsonElement kt = o.get("kachel");
        if (kt != null && !kt.isJsonNull()) {
            tile = text(o, "kachel");
            if (!TILE.matcher(tile).matches()) {
                throw new IOException("kachel_ungültig");
            }
        }
        return new Manifest(owner, ownerName, world, when, count, tile);
    }

    /** Zettel schreiben: erst Hilfsdatei, dann atomar umbenennen – ein halber Zettel darf nie als Zettel gelten. */
    static void writeManifest(Path dir, Manifest m) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        if (m.owner() != null) {
            o.addProperty("besitzer", m.owner().toString());
        }
        o.addProperty("name", m.ownerName());
        o.addProperty("welt", m.world());
        o.addProperty("zeit", m.when());
        o.addProperty("anzahl", m.count());
        if (m.tile() != null) {
            o.addProperty("kachel", m.tile());
        }
        Path tmp = dir.resolve(MANIFEST + ".neu");
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Passt der Weltname auf den Zettel? readManifest verlangt dasselbe Muster. Vorher schrieb persist den Namen
     * ungeprüft; eine Welt mit Umlaut oder Leerzeichen im Namen (Multiverse) bekam scheinbar einen dauerhaften
     * Eintrag, den der nächste Start still als welt_ungültig löschte.
     */
    static boolean storableWorld(String world) {
        return world != null && WORLD.matcher(world).matches() && !world.startsWith(".");
    }

    /** false, wenn der Zettel beim nächsten Start nicht durchginge – dann ehrlich gar nicht erst schreiben. */
    private boolean storable(Manifest m) {
        if (storableWorld(m.world())) {
            return true;
        }
        if (unstorableWorlds.add(m.world())) {
            LOG.info(() -> "rückgängig | welt_name_nicht_speicherbar | welt=" + safe(m.world())
                    + " – Rückgängig in dieser Welt nur bis zum Neustart");
        }
        return false;
    }

    /** Zettel zu einem Stapel-Eintrag (Hauptthread: liest den Weltnamen). */
    static Manifest manifestOf(BuildJob.UndoRecord rec) {
        return new Manifest(rec.owner(), cleanName(rec.ownerName()), rec.world().getName(), rec.when().toEpochMilli(),
                rec.size());
    }

    /**
     * Ursprungszustände eines normalen Baus als Rohdaten – nur auf dem Hauptthread (Block/BlockData), geschrieben wird
     * danach im Hintergrund. Zustände je BlockData einmal als Text: Ein Haus hat Tausende Einträge, aber nur wenige
     * Dutzend verschiedene Zustände.
     */
    static UndoSpool.Part snapshot(List<Map.Entry<org.bukkit.block.Block, org.bukkit.block.data.BlockData>> list,
            Map<org.bukkit.block.Block, org.bukkit.Material> placed) {
        int n = list.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        String[] st = new String[n];
        String[] pl = new String[n];
        Map<org.bukkit.block.data.BlockData, String> names = new java.util.HashMap<>();
        Map<org.bukkit.Material, String> types = new java.util.EnumMap<>(org.bukkit.Material.class);
        for (int i = 0; i < n; i++) {
            org.bukkit.block.Block b = list.get(i).getKey();
            xs[i] = b.getX();
            ys[i] = b.getY();
            zs[i] = b.getZ();
            st[i] = names.computeIfAbsent(list.get(i).getValue(), org.bukkit.block.data.BlockData::getAsString);
            org.bukkit.Material m = placed.get(b);
            pl[i] = m == null ? null : types.computeIfAbsent(m, t -> t.getKey().toString());
        }
        return new UndoSpool.Part(xs, ys, zs, st, pl, n);
    }

    /** Geprüfter Eintrag als Stapel-Eintrag: nur auf der Platte, Spool schreibgeschützt (UndoSpool.existing). */
    static BuildJob.UndoRecord toRecord(Loaded l, org.bukkit.World world) {
        Manifest m = l.manifest();
        return new BuildJob.UndoRecord(m.owner(), m.ownerName(), world, List.of(),
                java.time.Instant.ofEpochMilli(m.when()), UndoSpool.existing(l.dir(), m.count()));
    }

    /** Name für den Zettel: was NAME nicht erlaubt, wird zu „_“ – sonst verwürfe der nächste Start den Eintrag. */
    static String cleanName(String name) {
        String s = name == null || name.isBlank() ? "unbekannt" : name.replaceAll("[^\\p{L}\\p{N}_ .\\-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String text(JsonObject o, String key) throws IOException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isString()) {
            throw new IOException("feld_fehlt=" + key);
        }
        return e.getAsString();
    }

    private static long number(JsonObject o, String key) throws IOException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) {
            throw new IOException("feld_fehlt=" + key);
        }
        try {
            return e.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException ex) {
            throw new IOException("feld_keine_ganzzahl=" + key, ex);
        }
    }

    // ---------------------------------------------------------------- Laufender Betrieb

    /**
     * Stadtbau-Spool unter dem Stapel: nur den Zettel dazulegen (synchron, eine kleine Datei). Liegt der Spool nicht
     * direkt in root (Config-Änderung, Test), bleibt er ohne Zettel – der nächste Start löscht ihn wie bisher.
     */
    void persistSpool(UndoSpool spool, Manifest m) {
        if (!enabled || !storable(m)) {
            return;
        }
        Path dir = spool.dir();
        if (!root.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize().getParent())
                || !DIR_NAME.matcher(dir.getFileName().toString()).matches()) {
            LOG.warning(() -> "rückgängig | spool_außerhalb | ordner=" + safe(dir.getFileName().toString()));
            return;
        }
        try {
            writeManifest(dir, m);
        } catch (IOException e) {
            // fail-open: Rückgängig in dieser Sitzung geht weiter, nur über den Neustart kommt dieser Eintrag nicht.
            LOG.log(Level.WARNING, "rückgängig | zettel_fehlgeschlagen | ordner=" + dir.getFileName(), e);
        }
    }

    /**
     * Normaler Bau: Kopie der Ursprungszustände im Hintergrund schreiben. key ist der Stapel-Eintrag (Identität), über
     * den forget() die Kopie wieder löscht. Die Daten sind schon auf dem Hauptthread aus Block/BlockData gelesen.
     */
    void persistCopy(Object key, UndoSpool.Part data, Manifest m) {
        if (!enabled || data.size() == 0 || !storable(m)) {
            return;
        }
        Path dir = root.resolve("bau-" + m.when() + "-" + (++seq));
        copies.put(key, dir);
        io.execute(() -> writeCopy(dir, data, m));
    }

    /** Synchron schreiben (Hintergrund-Thread, Tests). Bei Fehler: Ordner weg, damit kein halber Eintrag bleibt. */
    static void writeCopy(Path dir, UndoSpool.Part data, Manifest m) {
        try {
            Files.createDirectories(dir);
            UndoSpool spool = new UndoSpool(dir);
            for (int i = 0; i < data.size(); i++) {
                spool.record(data.x()[i], data.y()[i], data.z()[i], data.state()[i], data.placedAt(i));
            }
            spool.finish();
            writeManifest(dir, m);
        } catch (IOException | IllegalStateException e) {
            // fail-open wie persistSpool; IllegalStateException ist UndoSpools Meldung für „nicht schreibbar“.
            LOG.log(Level.WARNING, "rückgängig | kopie_fehlgeschlagen | ordner=" + dir.getFileName(), e);
            UndoSpool.deleteDir(dir);
        }
    }

    /** Eintrag ist aus dem Stapel (zurückgesetzt oder herausgefallen): eigene Kopie löschen. Spools löscht ihr Besitzer. */
    void forget(Object key) {
        Path dir = copies.remove(key);
        if (dir != null) {
            later(() -> deleteEntry(dir));
        }
    }

    /** Hat dieser Eintrag eine Kopie auf der Platte (oder ist sie unterwegs)? Für Tests und Status. */
    boolean hasCopy(Object key) {
        return copies.containsKey(key);
    }

    /**
     * Übernahme nach /bm neuladen: Kopien UND Schreib-Thread des alten Journals gehören jetzt hierher. Vorher rief
     * takeOver old.close() auf, das bis zu 15 s auf dem Hauptthread wartete (awaitTermination) – während der Prüfung
     * beim Start (bis 512 MB gzip auf dem Celeron) oder dem Schreiben einer großen Kopie stand der Server. Ohne das
     * Warten liefe ein forget() im neuen Thread aber vor dem Schreiben im alten, und die Kopie käme samt Zettel nach
     * dem nächsten Neustart wieder. Mit dem übernommenen Thread bleibt alles in einer Reihe, und niemand wartet. Der
     * eigene, noch unbenutzte Thread wird beendet.
     */
    void takeOver(UndoJournal old) {
        copies.putAll(old.copies);
        old.copies.clear();
        seq = Math.max(seq, old.seq);
        unstorableWorlds.addAll(old.unstorableWorlds);
        if (old.io == null) {
            return;
        }
        ExecutorService unused = io;
        io = old.io;
        old.io = null;
        if (unused != null) {
            unused.shutdown();
        }
    }

    /** Aufgabe in der Reihe des Schreib-Threads (nach allem bisher Aufgetragenen) – Löschen, Tests. */
    void later(Runnable task) {
        io.execute(task);
    }

    /** Ausstehendes fertig schreiben (höchstens 15 s), dann den Thread beenden. Nach takeOver: nichts zu tun. */
    void close() {
        if (io == null) {
            return;
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                LOG.warning(() -> "rückgängig | schreiben_nicht_fertig | nach 15 s abgebrochen");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning(() -> "rückgängig | schreiben_unterbrochen");
        }
    }

    /** Wartet, bis alles bisher Aufgetragene erledigt ist – nur für Tests. */
    void drain() throws InterruptedException {
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        io.execute(l::countDown);
        if (!l.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Rückgängig-Thread hängt");
        }
    }

    /**
     * Einen Eintrag entfernen, ohne je einer Verknüpfung zu folgen: Datei → löschen, Ordner → UndoSpool.deleteDir (das
     * bei Symlink oder Junction nur den Verweis entfernt und innen nie hineinläuft).
     */
    static void deleteEntry(Path p) {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            UndoSpool.deleteDir(p);
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.warning(() -> "rückgängig | löschen_fehlgeschlagen | eintrag=" + safe(p.getFileName().toString())
                    + " grund=" + e.getMessage());
        }
    }

    /** Fremde Datei- und Ordnernamen nur entschärft ins Log (keine Steuerzeichen, keine Zeilenumbrüche). */
    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9_.=\\- äöüÄÖÜß]", "?");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }
}
