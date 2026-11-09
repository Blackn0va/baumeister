package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.RequestHints;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recherche vor dem Planen: Nennt der Auftrag ein echtes Bauwerk („bau den Eiffelturm“, „den Kölner Dom“), holt der
 * Baumeister Maße, Art, Baustil, Material und eine kurze Beschreibung aus Wikipedia und Wikidata und gibt sie dem LLM
 * mit (Wunsch 2026-10-30). Vorher kannte das Modell nur seine Erinnerung – Größe und Form schätzte es frei.
 *
 * Ablauf: Wikipedia-Suche mit den Wörtern des Auftrags ohne Füllwörter; der erste Treffer zählt nur, wenn jedes Wort
 * seines Titels im Auftrag steht (sonst holte „bau ein kleines Haus“ einen beliebigen Artikel). Dann das
 * Wikidata-Objekt des Artikels: Höhe P2048, Länge P2043, Breite P2049 (nur Meter oder Fuß), Stockwerke P1101, Art P31,
 * Baustil P149, Material P186. Ohne Maß keine Referenz – „Haus“ (Wikidata Q3947) hat keine.
 *
 * Bewusst fail-open: Netz weg, Dienst langsam (5 s je Anfrage), Antwort kaputt – dann plant das LLM wie vorher. Die
 * Recherche liefert nur Text und Zahlen für den Prompt; was gebaut wird, entscheiden weiter Grammatik, Compiler,
 * Plan-Prüfer, Überbau-Schutz und Rechteprüfung. Fremder Text gelangt so höchstens als Beschreibung ins Prompt (auf 400
 * Zeichen gekürzt) und kann keine Aktion außerhalb des Bauplans auslösen.
 *
 * Wikimedia-Regeln: eigener User-Agent, eine Anfrage zur Zeit je Auftrag, Ergebnisse 30 Tage auf der Platte
 * (plugins/Baumeister/wissen/) und im Speicher – derselbe Auftrag fragt nie zweimal.
 *
 * Threads: lookup() asynchron (HttpClient), cached() beliebig. Fehler: werden geloggt, nie geworfen.
 */
final class ReferenceLookup {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /**
     * Wikimedia verlangt im User-Agent eine Kontaktangabe (URL oder E-Mail). Gemessen 2026-10-30: Ohne sie kam ab der
     * dritten Recherche in 4 s HTTP 429. Die Angabe kommt aus llm.recherche-kontakt.
     */
    private static final String USER_AGENT_BASE = "Baumeister-Minecraft-Plugin/0.1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_AGE = Duration.ofDays(30);
    private static final String METRE = "http://www.wikidata.org/entity/Q11573";
    private static final String FOOT = "http://www.wikidata.org/entity/Q3710";
    private static final Set<String> FILLER = Set.of("bau", "baue", "bauen", "baust", "mir", "uns", "bitte", "mal",
            "den", "die", "das", "der", "dem", "des", "ein", "eine", "einen", "einem", "nach", "nachbauen", "nachbau",
            "hier", "hin", "mit", "von", "vom", "im", "in", "an", "auf", "originalgetreu", "echt", "echten", "echte",
            "wie", "original", "kannst", "du", "baumeister", "und", "so", "gross", "grosse", "grossen", "richtig");

    /** Maße in Metern (0 = unbekannt) und Beschreibung; title = Wikipedia-Artikel. */
    record Reference(String title, String kind, String style, String material, double height, double length,
            double width, int floors, String extract) {

        boolean hasSize() {
            return height > 0 || length > 0 || width > 0 || floors > 0;
        }

        /** Gleichmäßiger Maßstab, damit das größte Maß in maxExtent passt (8 Blöcke Luft); 1 = 1:1. */
        double scale(int maxExtent) {
            return scale(maxExtent, 0);
        }

        /**
         * Wie {@link #scale(int)}, zusätzlich passt die Grundfläche in maxFootprint (0 = keine Grenze). Plot-Modus
         * (2026-10-31): Das World Trade Center (63 m breit) auf einem 42er-Plot – ohne diese Grenze kam die Höhe aus
         * bau.max-ausdehnung, die Breite aber nicht auf den Plot.
         */
        double scale(int maxExtent, int maxFootprint) {
            double biggest = Math.max(height, Math.max(length, width));
            double s = biggest <= 0 ? 1 : Math.min(1, (maxExtent - 8) / biggest);
            double foot = Math.max(length, width);
            return maxFootprint > 0 && foot > 0 ? Math.min(s, maxFootprint / foot) : s;
        }

        /** Zielhöhe in Blöcken (0 = unbekannt) für den Plan-Prüfer. */
        int targetHeight(int maxExtent) {
            return targetHeight(maxExtent, 0);
        }

        int targetHeight(int maxExtent, int maxFootprint) {
            return height <= 0 ? 0 : (int) Math.round(height * scale(maxExtent, maxFootprint));
        }

        /** Englisch wie der System-Prompt; Zahlen schon in Blöcken. */
        String prompt(int maxExtent) {
            return prompt(maxExtent, 0);
        }

        String prompt(int maxExtent, int maxFootprint) {
            double s = scale(maxExtent, maxFootprint);
            List<String> facts = new ArrayList<>();
            if (!kind.isBlank()) {
                facts.add("type: " + kind);
            }
            if (height > 0) {
                facts.add("height " + blocks(height, s) + " blocks");
            }
            if (length > 0) {
                facts.add("length " + blocks(length, s) + " blocks");
            }
            if (width > 0) {
                facts.add("width " + blocks(width, s) + " blocks");
            }
            if (floors > 0) {
                facts.add(floors + " storeys");
            }
            if (!style.isBlank()) {
                facts.add("architectural style: " + style);
            }
            if (!material.isBlank()) {
                facts.add("material: " + material);
            }
            String scale = s >= 1 ? "at real size (1 block = 1 m)"
                    : String.format(Locale.ROOT, "scaled down to %.0f %% so it fits the build limit", s * 100);
            return "REAL BUILDING (Wikipedia/Wikidata, \"" + title + "\"): " + String.join(", ", facts) + "; " + scale
                    + ". Build THIS building with these proportions and its characteristic shape, not a generic one. "
                    + "Description: " + extract + "\n";
        }

        private static int blocks(double metres, double scale) {
            return Math.max(1, (int) Math.round(metres * scale));
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private final String wikipedia;
    private final String wikidata;
    private final Path cacheDir;
    private final Map<String, Optional<Reference>> memory = new ConcurrentHashMap<>();
    private final String userAgent;
    /**
     * Schalter und Anzeige in der Baumeister-App (GET/POST /baumeister/v1/recherche) oder null. Wunsch 2026-10-30 – wer
     * in der App „Internet-Recherche erlauben“ abwählt, bekommt keine einzige Abfrage
     * nach außen. Antwortet die App nicht (aus, älter ohne Schnittstelle), entscheidet llm.recherche allein.
     */
    private final URI host;

    ReferenceLookup(String wikipediaApi, String wikidataApi, Path cacheDir, String contact) {
        this(wikipediaApi, wikidataApi, cacheDir, contact, null);
    }

    ReferenceLookup(String wikipediaApi, String wikidataApi, Path cacheDir, String contact, URI host) {
        this.wikipedia = wikipediaApi;
        this.wikidata = wikidataApi;
        this.cacheDir = cacheDir;
        this.userAgent = USER_AGENT_BASE + (contact == null || contact.isBlank() ? "" : " (" + contact.trim() + ")");
        this.host = host;
    }

    /** Erlaubt die App die Recherche? true auch, wenn sie nicht antwortet (dann gilt nur llm.recherche). */
    CompletableFuture<Boolean> allowedByHost() {
        if (host == null) {
            return CompletableFuture.completedFuture(true);
        }
        HttpRequest req = HttpRequest.newBuilder(host).timeout(Duration.ofSeconds(2)).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((r, err) -> {
            if (err != null || r.statusCode() != 200) {
                return true;
            }
            try {
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                return !o.has("erlaubt") || o.get("erlaubt").getAsBoolean();
            } catch (RuntimeException e) {
                return true;
            }
        });
    }

    /** Treffer an die App melden (Konsole, Karte „Letzter Auftrag“). Fail-open, nur Anzeige. */
    void report(String prompt, Reference r, int maxExtent) {
        report(prompt, r, maxExtent, 0);
    }

    void report(String prompt, Reference r, int maxExtent, int maxFootprint) {
        if (host == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("auftrag", Texts.clean(prompt, 200));
        o.addProperty("titel", r.title());
        o.addProperty("hoehe_m", r.height());
        o.addProperty("zielhoehe", r.targetHeight(maxExtent, maxFootprint));
        o.addProperty("massstab", r.scale(maxExtent, maxFootprint));
        HttpRequest req = HttpRequest.newBuilder(host).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((x, err) -> {
            if (err != null) {
                LOG.fine(() -> "recherche | app_nicht_erreichbar | " + err.getMessage());
            }
        });
    }

    /** Suchwörter: Auftrag ohne Füllwörter und Zahlen; leer = nichts zu suchen. */
    static List<String> words(String prompt) {
        List<String> out = new ArrayList<>();
        for (String t : RequestHints.normalize(prompt).split(" ")) {
            if (!t.isBlank() && !FILLER.contains(t) && !t.matches("\\d+")) {
                out.add(t);
            }
        }
        return out;
    }

    /** Was im Speicher liegt (für den Plan-Prüfer nach der LLM-Antwort); leer, wenn nie gesucht oder nichts gefunden. */
    Optional<Reference> cached(String prompt) {
        return memory.getOrDefault(key(prompt), Optional.empty());
    }

    CompletableFuture<Optional<Reference>> lookup(String prompt) {
        String key = key(prompt);
        Optional<Reference> known = memory.get(key);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        Optional<Reference> disk = readDisk(key);
        if (disk != null) {
            memory.put(key, disk);
            return CompletableFuture.completedFuture(disk);
        }
        List<String> words = words(prompt);
        if (words.isEmpty() || words.size() > 6) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return allowedByHost().thenCompose(ok -> {
            if (!ok) {
                LOG.info(() -> "recherche | in_der_app_aus | auftrag=" + Texts.clean(prompt, 64));
                return CompletableFuture.completedFuture(Optional.<Reference>empty());
            }
            return search(prompt, key, words);
        });
    }

    private CompletableFuture<Optional<Reference>> search(String prompt, String key, List<String> words) {
        long t0 = System.nanoTime();
        String search = wikipedia + "?action=query&format=json&list=search&srlimit=1&srprop=&srsearch="
                + enc(String.join(" ", words));
        return get(search).thenCompose(s -> {
            String title = firstTitle(s);
            if (title == null || !titleInPrompt(title, words)) {
                return CompletableFuture.completedFuture(Optional.<Reference>empty());
            }
            String page = wikipedia + "?action=query&format=json&redirects=1&prop=pageprops%7Cextracts&exintro=1"
                    + "&explaintext=1&exsentences=3&ppprop=wikibase_item&titles=" + enc(title);
            return get(page).thenCompose(p -> entity(title, p));
        }).handle((ref, err) -> {
            Optional<Reference> r = err != null || ref == null ? Optional.<Reference>empty() : ref;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                LOG.info(() -> "recherche | fehlgeschlagen | auftrag=" + Texts.clean(prompt, 64) + " grund="
                        + cause.getMessage() + " – ohne Referenz weiter");
                // Weder Platte noch Speicher: Ein Fehler (429, Netz weg) soll beim nächsten Auftrag neu fragen
                // dürfen. Vorher merkte sich der Speicher „nichts gefunden“ bis zum Neustart.
                return r;
            }
            writeDisk(key, r);
            memory.put(key, r);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "recherche | " + (r.isPresent() ? "gefunden | titel=" + r.get().title() + " höhe_m="
                    + r.get().height() + " länge_m=" + r.get().length() + " breite_m=" + r.get().width() + " stockwerke="
                    + r.get().floors() : "nichts") + " | dauer_ms=" + ms);
            return r;
        });
    }

    private CompletableFuture<Optional<Reference>> entity(String title, String pageJson) {
        JsonObject page = firstPage(pageJson);
        if (page == null || !page.has("pageprops") || !page.getAsJsonObject("pageprops").has("wikibase_item")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String qid = page.getAsJsonObject("pageprops").get("wikibase_item").getAsString();
        String extract = page.has("extract") ? Texts.clean(page.get("extract").getAsString(), 400) : "";
        String url = wikidata + "?action=wbgetentities&format=json&props=claims&ids=" + enc(qid);
        return get(url).thenCompose(e -> {
            JsonObject claims = claims(e, qid);
            if (claims == null) {
                return CompletableFuture.completedFuture(Optional.<Reference>empty());
            }
            double h = metres(claims, "P2048");
            double l = metres(claims, "P2043");
            double w = metres(claims, "P2049");
            int f = (int) Math.round(amount(claims, "P1101"));
            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(items(claims, "P31"));
            ids.addAll(items(claims, "P149"));
            ids.addAll(items(claims, "P186"));
            if (h <= 0 && l <= 0 && w <= 0 && f <= 0) {
                return CompletableFuture.completedFuture(Optional.<Reference>empty());
            }
            CompletableFuture<Map<String, String>> labels = ids.isEmpty()
                    ? CompletableFuture.completedFuture(Map.of())
                    : get(wikidata + "?action=wbgetentities&format=json&props=labels&languages=en&ids="
                            + enc(String.join("|", ids))).thenApply(ReferenceLookup::labels);
            return labels.thenApply(lab -> Optional.of(new Reference(title, join(items(claims, "P31"), lab),
                    join(items(claims, "P149"), lab), join(items(claims, "P186"), lab), h, l, w, f, extract)));
        });
    }

    // ---------------------------------------------------------------- JSON, rein und testbar

    static String firstTitle(String json) {
        try {
            JsonArray hits = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("query")
                    .getAsJsonArray("search");
            return hits.isEmpty() ? null : hits.get(0).getAsJsonObject().get("title").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Jedes Wort des Titels (ohne Klammerzusatz) steht im Auftrag. */
    static boolean titleInPrompt(String title, List<String> words) {
        String plain = title.replaceAll("\\(.*\\)", " ");
        List<String> titleWords = List.of(RequestHints.normalize(plain).split(" "));
        return !titleWords.isEmpty() && titleWords.stream().allMatch(t -> t.isBlank() || words.contains(t));
    }

    private static JsonObject firstPage(String json) {
        try {
            JsonObject pages = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("query")
                    .getAsJsonObject("pages");
            for (Map.Entry<String, JsonElement> e : pages.entrySet()) {
                return e.getValue().getAsJsonObject();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static JsonObject claims(String json, String qid) {
        try {
            return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("entities").getAsJsonObject(qid)
                    .getAsJsonObject("claims");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Erster Wert der Eigenschaft in Metern; Fuß umgerechnet, andere Einheiten 0. */
    static double metres(JsonObject claims, String prop) {
        JsonObject v = value(claims, prop);
        if (v == null || !v.has("amount")) {
            return 0;
        }
        double a = Double.parseDouble(v.get("amount").getAsString());
        String unit = v.has("unit") ? v.get("unit").getAsString() : "";
        return unit.equals(METRE) ? a : unit.equals(FOOT) ? a * 0.3048 : 0;
    }

    private static double amount(JsonObject claims, String prop) {
        JsonObject v = value(claims, prop);
        try {
            return v == null || !v.has("amount") ? 0 : Double.parseDouble(v.get("amount").getAsString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Der als „preferred“ markierte Wert, sonst der erste. Vorher immer der erste: Beim Eiffelturm war das 324 m
     * (Stand vor 2022), der bevorzugte Wert ist 330 m.
     */
    private static JsonObject value(JsonObject claims, String prop) {
        try {
            if (!claims.has(prop)) {
                return null;
            }
            JsonArray all = claims.getAsJsonArray(prop);
            JsonObject chosen = all.get(0).getAsJsonObject();
            for (JsonElement c : all) {
                JsonObject o = c.getAsJsonObject();
                if (o.has("rank") && "preferred".equals(o.get("rank").getAsString())) {
                    chosen = o;
                    break;
                }
            }
            JsonObject snak = chosen.getAsJsonObject("mainsnak");
            JsonElement dv = snak.has("datavalue") ? snak.getAsJsonObject("datavalue").get("value") : null;
            return dv != null && dv.isJsonObject() ? dv.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> items(JsonObject claims, String prop) {
        List<String> out = new ArrayList<>();
        try {
            if (!claims.has(prop)) {
                return out;
            }
            for (JsonElement c : claims.getAsJsonArray(prop)) {
                JsonObject snak = c.getAsJsonObject().getAsJsonObject("mainsnak");
                if (snak.has("datavalue")) {
                    out.add(snak.getAsJsonObject("datavalue").getAsJsonObject("value").get("id").getAsString());
                }
                if (out.size() == 3) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.fine(() -> "recherche | wikidata_wert_unlesbar | eigenschaft=" + prop);
        }
        return out;
    }

    private static Map<String, String> labels(String json) {
        Map<String, String> out = new java.util.HashMap<>();
        try {
            JsonObject ents = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("entities");
            for (Map.Entry<String, JsonElement> e : ents.entrySet()) {
                JsonObject l = e.getValue().getAsJsonObject().getAsJsonObject("labels");
                if (l != null && l.has("en")) {
                    out.put(e.getKey(), l.getAsJsonObject("en").get("value").getAsString());
                }
            }
        } catch (RuntimeException e) {
            LOG.fine("recherche | bezeichnungen_unlesbar");
        }
        return out;
    }

    private static String join(List<String> ids, Map<String, String> labels) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String l = labels.get(id);
            if (l != null) {
                out.add(Texts.clean(l, 40));
            }
        }
        return String.join(", ", out);
    }

    // ---------------------------------------------------------------- Netz und Platte

    private CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", userAgent)
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode() + " von " + req.uri().getHost());
            }
            return r.body();
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String key(String prompt) {
        return String.join("_", words(prompt));
    }

    /** null = nicht auf der Platte (oder abgelaufen); Optional.empty() = früher gesucht, nichts gefunden. */
    private Optional<Reference> readDisk(String key) {
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Path f = cacheDir.resolve(key + ".json");
        try {
            if (!Files.isRegularFile(f) || Files.getLastModifiedTime(f).toInstant().isBefore(Instant.now()
                    .minus(MAX_AGE))) {
                return null;
            }
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("titel")) {
                return Optional.empty();
            }
            return Optional.of(new Reference(o.get("titel").getAsString(), o.get("art").getAsString(),
                    o.get("stil").getAsString(), o.get("material").getAsString(), o.get("höhe").getAsDouble(),
                    o.get("länge").getAsDouble(), o.get("breite").getAsDouble(), o.get("stockwerke").getAsInt(),
                    o.get("beschreibung").getAsString()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "recherche | zwischenspeicher_unlesbar | datei=" + f.getFileName(), e);
            return null;
        }
    }

    private void writeDisk(String key, Optional<Reference> r) {
        if (key.isEmpty() || key.length() > 120) {
            return;
        }
        JsonObject o = new JsonObject();
        r.ifPresent(x -> {
            o.addProperty("titel", x.title());
            o.addProperty("art", x.kind());
            o.addProperty("stil", x.style());
            o.addProperty("material", x.material());
            o.addProperty("höhe", x.height());
            o.addProperty("länge", x.length());
            o.addProperty("breite", x.width());
            o.addProperty("stockwerke", x.floors());
            o.addProperty("beschreibung", x.extract());
        });
        try {
            Files.createDirectories(cacheDir);
            Path f = cacheDir.resolve(key + ".json");
            Path tmp = cacheDir.resolve(key + ".json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.INFO, "recherche | zwischenspeicher_schreiben_fehlgeschlagen | schlüssel=" + key, e);
        }
    }
}
