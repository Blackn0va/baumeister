package com.blackn0va.baumeister;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;

/**
 * Reisepunkte: zu jeder gebauten Stadt, jedem Gelände und jedem gemerkten Ort ein Sprungpunkt (/bm orte, /bm ort).
 * Vorher musste man sich die Koordinaten eines Stadtbaus selbst notieren; die Geländeorte lagen 65 536 Blöcke
 * auseinander und waren nach dem ersten Besuch nur noch über /tp mit geratenen Zahlen erreichbar.
 *
 * Dauerhaft in plugins/Baumeister/orte.json. Geschrieben wird erst in orte.json.tmp, dann umbenannt – ein Absturz
 * mitten im Schreiben darf die Liste nie halb hinterlassen (wie CityStages.writeCache). Eine unlesbare Datei wird beim
 * Laden beiseitegelegt (orte.json.defekt-&lt;Zeit&gt;) statt beim nächsten Speichern stumm überschrieben.
 *
 * Namen sind eindeutig über einen Suchschlüssel: klein, Umlaute ausgeschrieben, Akzente weg, Leerraum zusammengefasst
 * – „München“, „muenchen“ und „MÜNCHEN“ sind derselbe Ort (umlaut-ok). Angezeigt wird der Name wie angelegt.
 *
 * Threads: alle öffentlichen Methoden synchronisiert; das Erdwelt-Paket darf remember() auch abseits des Hauptthreads
 * aufrufen. Fehler: Speichern wirft IOException und rollt den Speicherstand zurück (Platte = Speicher); remember()
 * schluckt sie mit Logzeile, weil ein fehlender Sprungpunkt keinen Bau aufhalten soll.
 */
public final class PlaceRegistry {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int MAX_NAME = 64;
    static final int PAGE_SIZE = 10;
    /** Wörter, die /bm ort als Unterbefehl liest; ein Ort genau dieses Namens wäre sonst nur schwer erreichbar. */
    static final List<String> VERBS = List.of("merken", "löschen", "loeschen"); // umlaut-ok: Eingabe ohne Umlaute

    /** Art eines Orts; label steht in orte.json, title in der Anzeige. */
    public enum Kind {
        CITY("stadt", "Stadt"),
        TERRAIN("gelaende", "Gelände"),
        EARTH("erde", "Erde"),
        CUSTOM("eigen", "eigen");

        private final String label;
        private final String title;

        Kind(String label, String title) {
            this.label = label;
            this.title = title;
        }

        public String label() {
            return label;
        }

        public String title() {
            return title;
        }

        /** „gelände“ mit Umlaut gilt wie „gelaende“; unbekannt → IllegalArgumentException. */
        public static Kind parse(String text) {
            String k = key(text == null ? "" : text);
            for (Kind kind : values()) {
                if (kind.label.equals(k)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unbekannte Art: " + text);
        }
    }

    /**
     * Ein Sprungpunkt. name wie angelegt, created bleibt beim Aktualisieren erhalten.
     *
     * Nur endliche Koordinaten: Vorher nahm put() auch NaN/Infinity an, orte.json enthielt danach `"x": NaN`, und
     * Gson las das beim nächsten Start nachsichtig wieder ein – der Ort stand in der Liste, die Anreise baute eine
     * Location mit NaN (gemessen mit der Prüfer-Probe 2026-10-28). Die Prüfung steht hier im Konstruktor, damit
     * put(), remember() und das Laden (fromJson) sie nicht einzeln vergessen können. Wer sie entfernt, lässt
     * rechnende Aufrufer (Erdwelt-Projektion) wieder kaputte Punkte dauerhaft ablegen (ASVS V5.1.4).
     */
    public record Place(String name, Kind kind, String world, double x, double y, double z, float yaw, float pitch,
            Instant created) {

        public Place {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)
                    || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Koordinaten müssen endliche Zahlen sein: " + x + " " + y + " "
                        + z + " (Blick " + yaw + "/" + pitch + ")");
            }
        }
    }

    /** Eine Seite der Liste; page ist 1-basiert und schon auf 1..pages begrenzt. */
    record Page(List<Place> items, int page, int pages, int total) {
    }

    /** Abfrage eines Blocks für safeY – im Spiel die Welt, im Test eine Fake-Säule. */
    @FunctionalInterface
    interface BlockProbe {
        boolean free(int x, int y, int z);
    }

    private final Path file;
    private final Clock clock;
    private final Map<String, Place> places = new LinkedHashMap<>();
    /** false, wenn orte.json unlesbar war und sich nicht beiseitelegen ließ – dann nie darüberschreiben. */
    private boolean writable = true;

    public PlaceRegistry(Path file) {
        this(file, Clock.systemUTC());
    }

    PlaceRegistry(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ Namen

    /**
     * Suchschlüssel: Umlaute zuerst ausschreiben (ä → ae), dann übrige Akzente entfernen. Andersherum würde NFD
     * aus „München“ „munchen“ machen (wie TerrainService.slug) – und „muenchen“ fände den Ort nicht (umlaut-ok).
     *
     * Davor NFC: Kommt ein Umlaut zerlegt an (u + U+0308, etwa aus eingefügtem Text oder NFD-Quellen), griff
     * replace("ü", …) vorher nicht, und NFD strich nur das Trema – „München“ in NFD wurde „munchen“ (gemessen mit
     * der Prüfer-Probe 2026-10-28). Wer das NFC entfernt, macht die Gleichheit München = muenchen wieder von der
     * Kodierung der Eingabe abhängig (umlaut-ok).
     */
    static String key(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"); // umlaut-ok
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Anzeigename: Farbcodes und Steuerzeichen raus (Texts.clean), Leerraum zusammengefasst, höchstens 64 Zeichen. */
    static String cleanName(String name) {
        String s = Texts.clean(name == null ? "" : name.replaceAll("\\s+", " "), MAX_NAME);
        if (key(s).isEmpty()) {
            throw new IllegalArgumentException("Der Name ist leer.");
        }
        return s;
    }

    // ------------------------------------------------------------------ Laden / Speichern

    /** Liest orte.json; fehlt sie, bleibt die Liste leer. Liefert die Zahl der geladenen Orte. */
    synchronized int load() {
        places.clear();
        writable = true;
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        int skipped = 0;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("orte") || !root.get("orte").isJsonArray()) {
                throw new JsonParseException("Feld orte fehlt oder ist keine Liste");
            }
            for (JsonElement e : root.getAsJsonArray("orte")) {
                try {
                    Place p = fromJson(e.getAsJsonObject());
                    places.put(key(p.name()), p);
                } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ex) {
                    // Ein kaputter Eintrag kostet nur diesen Ort, nicht die ganze Liste.
                    skipped++;
                    LOG.log(Level.WARNING, "orte | eintrag_ungültig | datei=" + file + " eintrag=" + e, ex);
                }
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            places.clear();
            setAside(e);
            return 0;
        }
        int count = places.size();
        int bad = skipped;
        LOG.info(() -> "orte | geladen | anzahl=" + count + " übersprungen=" + bad);
        return count;
    }

    /**
     * Unlesbare Datei umbenennen, damit das nächste Speichern sie nicht überschreibt. Gelingt auch das nicht, bleibt
     * die Liste schreibgeschützt (fail-closed für die Daten): lieber keine neuen Orte als die alten verlieren.
     */
    private void setAside(Exception cause) {
        Path aside = file.resolveSibling(file.getFileName() + ".defekt-" + clock.millis());
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(Level.WARNING, "orte | datei_defekt | datei=" + file + " beiseite=" + aside, cause);
        } catch (IOException e) {
            writable = false;
            e.addSuppressed(cause);
            LOG.log(Level.SEVERE, "orte | datei_defekt_nicht_verschiebbar | datei=" + file
                    + " – Orte werden nicht gespeichert, bis die Datei repariert ist", e);
        }
    }

    private void save() throws IOException {
        if (!writable) {
            throw new IOException(file.getFileName() + " ist unlesbar und ließ sich nicht beiseitelegen – siehe "
                    + "Serverlog.");
        }
        JsonArray arr = new JsonArray();
        for (Place p : sorted(places.values())) {
            arr.add(toJson(p));
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("orte", arr);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static JsonObject toJson(Place p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name());
        o.addProperty("art", p.kind().label());
        o.addProperty("welt", p.world());
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        o.addProperty("yaw", p.yaw());
        o.addProperty("pitch", p.pitch());
        o.addProperty("angelegt", p.created().toString());
        return o;
    }

    private static Place fromJson(JsonObject o) {
        for (String field : List.of("name", "art", "welt", "x", "y", "z", "angelegt")) {
            if (!o.has(field) || o.get(field).isJsonNull()) {
                throw new IllegalArgumentException("Feld " + field + " fehlt");
            }
        }
        try {
            return new Place(cleanName(o.get("name").getAsString()), Kind.parse(o.get("art").getAsString()),
                    o.get("welt").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                    o.get("z").getAsDouble(), o.has("yaw") ? o.get("yaw").getAsFloat() : 0f,
                    o.has("pitch") ? o.get("pitch").getAsFloat() : 0f, Instant.parse(o.get("angelegt").getAsString()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Anlagedatum unlesbar: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Ändern

    /**
     * Ort anlegen oder – gleicher Suchschlüssel – aktualisieren: Stelle, Welt, Art und Blickrichtung neu, Name und
     * Anlagedatum bleiben. Scheitert das Speichern, gilt wieder der alte Stand (Platte = Speicher).
     */
    synchronized Place put(String name, Kind kind, String world, double x, double y, double z, float yaw,
            float pitch) throws IOException {
        String display = cleanName(name);
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("Ohne Welt kein Ort.");
        }
        String k = key(display);
        Place old = places.get(k);
        Place p = new Place(old != null ? old.name() : display, kind, world, x, y, z, yaw, pitch,
                old != null ? old.created() : clock.instant());
        places.put(k, p);
        try {
            save();
        } catch (IOException e) {
            if (old != null) {
                places.put(k, old);
            } else {
                places.remove(k);
            }
            throw e;
        }
        LOG.info(() -> String.format(Locale.ROOT, "orte | %s | name=%s art=%s welt=%s x=%.1f y=%.1f z=%.1f",
                old != null ? "aktualisiert" : "gemerkt", p.name(), kind.label(), world, x, y, z));
        return p;
    }

    /**
     * Für automatische Einträge (Stadtbau, Gelände, Erdwelt). Gibt es den Namen schon mit einer ANDEREN Art, landet
     * der neue Punkt unter „Name (Art)“ – sonst würde das Gelände „München“ den Sprungpunkt der Stadt „München“
     * überschreiben, und der Stadtbau wäre nur noch per /tp erreichbar. Gleiche Art → aktualisieren.
     *
     * Der Grundname wird VOR dem Anhängen gekürzt, sodass der Zusatz immer ganz bleibt. Vorher kürzte cleanName erst
     * „Name (Art)“ auf 64 Zeichen – bei einem Namen ab 64 Zeichen fiel der Zusatz weg, der Schlüssel war wieder der
     * alte, und das Gelände überschrieb die Stadt (Prüfer-Probe 2026-10-28: „Sankt “ + 10 × „Irgendwo“ → 1 Ort
     * statt 2). Belegt der Name mit Zusatz schon einen Ort anderer Art (etwa einen gemerkten „München (Gelände)“),
     * wird nichts überschrieben: IllegalArgumentException, remember() loggt sie.
     */
    synchronized Place rememberAuto(String name, Kind kind, String world, double x, double y, double z, float yaw,
            float pitch) throws IOException {
        String display = cleanName(name);
        Place same = places.get(key(display));
        if (same != null && same.kind() != kind) {
            String suffix = " (" + kind.title() + ")";
            String base = display.length() + suffix.length() > MAX_NAME
                    ? Texts.clean(display, MAX_NAME - suffix.length()) : display;
            display = cleanName(base + suffix);
            Place taken = places.get(key(display));
            if (taken != null && taken.kind() != kind) {
                throw new IllegalArgumentException("Name „" + display + "“ ist schon ein Ort der Art "
                        + taken.kind().label() + " – nicht überschrieben.");
            }
        }
        return put(display, kind, world, x, y, z, yaw, pitch);
    }

    /**
     * Öffentlicher Einstieg für andere Teile des Plugins (Erdwelt-Paket): Stelle merken, doppelter Name aktualisiert.
     * Gibt null zurück, wenn nichts gespeichert wurde (leerer Name, Location ohne Welt, Koordinaten nicht endlich,
     * Name mit Artzusatz schon anderweitig belegt, Platte voll) – mit Logzeile.
     * Bewusst fail-open: Ein fehlender Sprungpunkt ist ein Komfortverlust, kein Sicherheitsproblem, und darf keinen
     * Bau abbrechen.
     */
    public Place remember(String name, Location where, Kind kind) {
        if (where == null || where.getWorld() == null) {
            LOG.warning(() -> "orte | ohne_welt | name=" + name + " art=" + kind.label());
            return null;
        }
        try {
            return rememberAuto(name, kind, where.getWorld().getName(), where.getX(), where.getY(), where.getZ(),
                    where.getYaw(), where.getPitch());
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "orte | speichern_fehlgeschlagen | name=" + name + " art=" + kind.label(), e);
            return null;
        }
    }

    /** Wie remember(name, where, Kind), Art als Text („stadt“, „gelaende“, „erde“, „eigen“). */
    public Place remember(String name, Location where, String kind) {
        Kind k;
        try {
            k = Kind.parse(kind);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "orte | art_unbekannt | name=" + name + " art=" + kind, e);
            return null;
        }
        return remember(name, where, k);
    }

    /** true, wenn es den Ort gab. */
    synchronized boolean remove(String name) throws IOException {
        String k = key(name);
        Place old = places.remove(k);
        if (old == null) {
            return false;
        }
        try {
            save();
        } catch (IOException e) {
            places.put(k, old);
            throw e;
        }
        LOG.info(() -> "orte | gelöscht | name=" + old.name());
        return true;
    }

    // ------------------------------------------------------------------ Lesen

    synchronized Place find(String name) {
        return places.get(key(name));
    }

    /** Alle Orte nach Suchschlüssel sortiert (München neben Münster, nicht hinter Zwickau). */
    synchronized List<Place> all() {
        return sorted(places.values());
    }

    synchronized List<String> names() {
        return sorted(places.values()).stream().map(Place::name).toList();
    }

    /** Orte, deren Suchschlüssel das Suchwort enthält – „muen“ findet München. */
    synchronized List<Place> search(String term) {
        String k = key(term);
        return sorted(places.values()).stream().filter(p -> key(p.name()).contains(k)).toList();
    }

    private static List<Place> sorted(Collection<Place> values) {
        List<Place> list = new ArrayList<>(values);
        list.sort(Comparator.comparing((Place p) -> key(p.name())).thenComparing(Place::name));
        return list;
    }

    /** Seite page (1-basiert) zu je size Einträgen; außerhalb des Bereichs → erste bzw. letzte Seite. */
    static Page page(List<Place> all, int page, int size) {
        int pages = Math.max(1, (all.size() + size - 1) / size);
        int p = Math.max(1, Math.min(pages, page));
        int from = (p - 1) * size;
        return new Page(all.subList(from, Math.min(all.size(), from + size)), p, pages, all.size());
    }

    /** Ergebnis von arrival: loaded = false heißt, der Zielchunk ließ sich nicht laden; dann ist y leer. */
    record Arrival(boolean loaded, OptionalInt y) {
    }

    /**
     * safeY erst, wenn der Chunk der Zielsäule geladen ist (ChunkPrefetch: Paper asynchron, Spigot im nächsten
     * Tick), dann then. Absturz-Audit 2026-10-30 (AK-8): Vorher las /bm ort die Säule sofort; ein ferner Ort wurde
     * dabei synchron im Server-Thread geladen bzw. erzeugt. Lässt er sich nicht laden, wird nichts gelesen –
     * fail-closed: kein Teleport ins Ungeprüfte, eine ungeladene Säule ist nicht „frei“. Wer das Vorladen entfernt,
     * holt den Stillstand zurück (ArrivalAndFingerprintLoadTest).
     */
    static void arrival(ChunkPrefetch.Host host, int x, int y, int z, int minY, int maxY, BlockProbe probe,
            Consumer<Arrival> then) {
        int cx = x >> 4;
        int cz = z >> 4;
        ChunkPrefetch.ensure(host, new long[] {ChunkPrefetch.key(cx, cz)}, 1, p -> {
            try {
                then.accept(host.loaded(cx, cz) ? new Arrival(true, safeY(x, y, z, minY, maxY, probe))
                        : new Arrival(false, OptionalInt.empty()));
            } finally {
                if (p != null) {
                    p.release();
                }
            }
        });
    }

    /**
     * Sichere Ankunftshöhe: ab dem Zielblock nach oben die erste Stelle, an der Fuß- und Kopfblock frei sind. Vorher
     * (/tp auf gemerkte Koordinaten) landete man nach einem Umbau in der neuen Decke und erstickte. Nach unten wird
     * bewusst nicht gesucht – ein Ort in der Luft ist ein Aussichtspunkt, kein Fehler. Leer, wenn bis zur Weltdecke
     * nichts frei ist. Liest ohne Ladeprüfung – von /bm ort nur über arrival aufrufen.
     */
    static OptionalInt safeY(int x, int y, int z, int minY, int maxY, BlockProbe probe) {
        int start = Math.max(minY, Math.min(maxY - 2, y));
        for (int fy = start; fy <= maxY - 2; fy++) {
            if (probe.free(x, fy, z) && probe.free(x, fy + 1, z)) {
                return OptionalInt.of(fy);
            }
        }
        return OptionalInt.empty();
    }
}
