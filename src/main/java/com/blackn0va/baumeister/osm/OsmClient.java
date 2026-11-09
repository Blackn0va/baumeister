package com.blackn0va.baumeister.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Kartendaten holen: Ortsname → Koordinaten über Nominatim, Gebäude/Straßen/Flächen über die Overpass-API.
 *
 * Nutzungsregeln der öffentlichen Dienste: eigener User-Agent (Nominatim verlangt ihn), höchstens eine Abfrage zur Zeit
 * (CityService lässt nur einen Import laufen). Keine Nutzerdaten in den Abfragen – nur der Ortsname bzw. Koordinaten.
 * Redirect.NEVER wie im LlmClient: Ein Umleitungsziel wäre ein Fehler, kein Ziel.
 */
public final class OsmClient {

    /**
     * Ort mit Koordinaten. extent: Radius in Metern, der das Rechteck des Orts aus Nominatim (boundingbox) ganz
     * abdeckt; 0 = unbekannt (Koordinaten statt Ortsname).
     */
    public record Place(String name, double lat, double lon, int extent) {
    }

    /**
     * Postleitzahl statt Ortsname (Wunsch 2026-10-30): „71735“, „plz 71735“, „8001 ch“. Vorher ging eine PLZ als
     * Freitext an Nominatim und traf, was zuerst
     * passte – irgendwo auf der Welt. Jetzt die strukturierte Suche postalcode=…, mit Land: ausdrücklich angegeben,
     * sonst bei fünf Ziffern stadt.plz-land (Vorgabe de), bei vier Ziffern (Österreich, Schweiz, …) ohne Einschränkung.
     */
    static final Pattern POSTCODE = Pattern.compile("^\\s*(?:plz\\s+)?(\\d{4,5})(?:\\s+([A-Za-z]{2}))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COORDS = Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");
    private static final String USER_AGENT = "Baumeister-Minecraft-Plugin/0.1";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /**
     * Overpass-Instanzen in Reihenfolge. dev01 2026-10-28: overpass-api.de antwortete 1,5 min lang nur mit 502, drei
     * Wiederholungen derselben Instanz halfen nicht. Jetzt fragt ein „ausgelastet“ sofort die nächste Instanz.
     */
    private final List<String> overpassUrls;
    private final String nominatimUrl;
    /** Land für fünfstellige Postleitzahlen ohne Landesangabe (stadt.plz-land), leer = jedes. */
    private final String postcodeCountry;

    public OsmClient(List<String> overpassUrls, String nominatimUrl) {
        this(overpassUrls, nominatimUrl, "de");
    }

    public OsmClient(List<String> overpassUrls, String nominatimUrl, String postcodeCountry) {
        if (overpassUrls.isEmpty()) {
            throw new IllegalArgumentException("Keine Overpass-Adresse konfiguriert.");
        }
        this.overpassUrls = List.copyOf(overpassUrls);
        this.nominatimUrl = nominatimUrl;
        this.postcodeCountry = postcodeCountry == null ? "" : postcodeCountry.trim().toLowerCase(Locale.ROOT);
    }

    /** Nominatim-Anfrage für einen Ortsnamen oder eine Postleitzahl (POSTCODE). Rein, testbar. */
    static String searchUrl(String nominatimUrl, String query, String postcodeCountry) {
        String base = nominatimUrl + "?format=json&limit=1&accept-language=de&addressdetails=1";
        Matcher m = POSTCODE.matcher(query);
        if (!m.matches()) {
            return base + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        String code = m.group(1);
        String country = m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT)
                : code.length() == 5 ? postcodeCountry : "";
        return base + "&postalcode=" + code + (country.matches("[a-z]{2}") ? "&countrycodes=" + country : "");
    }

    /**
     * Name für Meldungen und Reisepunkt: bei einer Postleitzahl „71735 Eberdingen“ statt der ganzen Adresszeile
     * („71735, Eberdingen, VVG der Stadt Vaihingen an der Enz, …“), sonst display_name wie bisher.
     */
    static String placeName(JsonObject o, String query) {
        String display = o.has("display_name") ? o.get("display_name").getAsString() : query;
        if (!o.has("address") || !o.get("address").isJsonObject()) {
            return display;
        }
        JsonObject a = o.getAsJsonObject("address");
        boolean postcode = "postcode".equals(str(o, "addresstype")) || "postal_code".equals(str(o, "type"));
        if (!postcode || !a.has("postcode")) {
            return display;
        }
        for (String k : new String[] {"city", "town", "village", "suburb", "municipality"}) {
            if (a.has(k)) {
                return a.get(k).getAsString().isBlank() ? display
                        : a.get("postcode").getAsString() + " " + a.get(k).getAsString();
            }
        }
        return display;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /** Anzahl der Overpass-Instanzen (für die Wiederholung: Versuch n nimmt Instanz n mod Anzahl). */
    public int mirrors() {
        return overpassUrls.size();
    }

    /** Sieht die Eingabe wie „lat, lon“ aus (egal ob im gültigen Bereich)? */
    public static boolean looksLikeCoordinates(String query) {
        return COORDS.matcher(query).matches();
    }

    /**
     * „49.845, 7.867“ als Ort, wenn |lat| ≤ maxLat und |lon| ≤ 180 – sonst null. maxLat ist je Welt verschieden: Das
     * Gelände je Ort (Mercator-Kacheln) endet bei 85°, die Erdwelt reicht bis an die Pole.
     */
    public static Place coordinates(String query, double maxLat) {
        Matcher m = COORDS.matcher(query);
        if (!m.matches()) {
            return null;
        }
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(2));
        if (Math.abs(lat) > maxLat || Math.abs(lon) > 180) {
            return null;
        }
        return new Place(String.format(Locale.ROOT, "%.5f, %.5f", lat, lon), lat, lon, 0);
    }

    /** „49.845, 7.867“ (bis 85° Breite) direkt, sonst Nominatim-Suche (erster Treffer). */
    public CompletableFuture<Place> resolve(String query) {
        Place direct = coordinates(query, 85);
        if (direct != null) {
            return CompletableFuture.completedFuture(direct);
        }
        String url = searchUrl(nominatimUrl, query, postcodeCountry);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() != 200) {
                throw new IllegalStateException("Ortssuche: HTTP " + r.statusCode());
            }
            JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
            if (arr.isEmpty()) {
                throw new IllegalStateException("Ort nicht gefunden: " + query);
            }
            JsonObject o = arr.get(0).getAsJsonObject();
            String name = placeName(o, query);
            double lat = o.get("lat").getAsDouble();
            double lon = o.get("lon").getAsDouble();
            return new Place(name, lat, lon, extent(o, lat, lon));
        });
    }

    /**
     * Radius um (lat, lon), der Nominatims boundingbox [Süd, Nord, West, Ost] ganz einschließt. Vorher (bis
     * 2026-10-28) galten ohne Angabe fest 150 m – von Hepberg entstand so nur der Ortskern („nur ein Bereich, nicht
     * die ganze Stadt“).
     */
    static int extent(JsonObject o, double lat, double lon) {
        if (!o.has("boundingbox") || !o.get("boundingbox").isJsonArray() || o.getAsJsonArray("boundingbox").size() != 4) {
            return 0;
        }
        JsonArray b = o.getAsJsonArray("boundingbox");
        double cos = Math.cos(Math.toRadians(lat));
        double max = 0;
        for (int i = 0; i < 2; i++) {
            for (int j = 2; j < 4; j++) {
                double dz = (b.get(i).getAsDouble() - lat) * 110_540.0;
                double dx = (b.get(j).getAsDouble() - lon) * 111_320.0 * cos;
                max = Math.max(max, Math.hypot(dx, dz));
            }
        }
        return (int) Math.ceil(max);
    }

    /** Overpass-Abfrage im Umkreis; Antwort roh (OsmImport liest sie). */
    public CompletableFuture<String> fetch(double lat, double lon, int radius) {
        return query(String.format(Locale.ROOT, "(around:%d,%.6f,%.6f)", radius, lat, lon), 0);
    }

    /** Overpass-Abfrage für ein Rechteck (Süd, West, Nord, Ost) – eine Kartenkachel des Etappenbaus. */
    public CompletableFuture<String> fetchBox(double south, double west, double north, double east, int mirror) {
        return query(String.format(Locale.ROOT, "(%.6f,%.6f,%.6f,%.6f)", south, west, north, east), mirror);
    }

    /** Name der Instanz für Log und Meldung, z. B. „overpass-api.de“. */
    public String mirrorName(int mirror) {
        String host = URI.create(overpassUrls.get(Math.floorMod(mirror, overpassUrls.size()))).getHost();
        return host == null ? "?" : host;
    }

    /**
     * Was eine Kartenkachel enthält – ohne Kopf (Zeitlimit) und Ausgabe, mit {@code area} als Platzhalter für Umkreis
     * bzw. Rechteck. building:part: Kirchtürme, Seitenschiffe, Turmhelme mit eigener Höhe – ohne sie ist eine
     * Kathedrale ein Klotz. railway/barrier/amenity=parking seit 2026-10-29 (Stadtausstattung: Gleise, Zäune, Hecken,
     * Mauern, Parkplätze).
     */
    static String selectors(String area) {
        return "(way[\"building\"]" + area + ";relation[\"building\"]" + area
                + ";way[\"building:part\"]" + area + ";relation[\"building:part\"]" + area
                + ";way[\"highway\"]" + area + ";way[\"waterway\"]" + area
                + ";way[\"natural\"~\"^(water|wood|sand|beach)$\"]" + area
                + ";way[\"landuse\"~\"^(forest|grass|meadow|farmland|village_green|recreation_ground|reservoir|basin)$\"]"
                + area + ";way[\"leisure\"~\"^(park|garden|pitch)$\"]" + area + ";node[\"natural\"=\"tree\"]" + area
                + ";way[\"railway\"~\"^(rail|tram|light_rail|narrow_gauge|subway)$\"]" + area
                + ";way[\"barrier\"~\"^(fence|hedge|wall|retaining_wall|city_wall)$\"]" + area
                + ";way[\"amenity\"=\"parking\"]" + area
                // Küstenlinie (2026-11-01, SeaMask): Das Höhenmodell kennt viele Häfen nicht (Sydney), OSM schon.
                + ";way[\"natural\"=\"coastline\"]" + area
                // Wasserflächen als Multipolygon (2026-11-01): Sydneys Hafen hat keine Küstenlinie, sondern ist die
                // Relation „Port Jackson“ (natural=water, water=harbour) – vorher kam er gar nicht an.
                + ";relation[\"natural\"=\"water\"]" + area + ";);";
    }

    /**
     * Kennung des Abfrageinhalts für den Namen der Kartenkachel im Zwischenspeicher: „q“ und die ersten 8 Hex-Zeichen
     * von SHA-256 über {@link #selectors}. Vorher stand dort von Hand „v2“ – wer die Abfrage erweiterte und das
     * Hochzählen vergaß, bekam 30 Tage lang alte Kacheln OHNE die neuen Elemente, ohne jede Meldung (so wäre es mit
     * Gleisen, Zäunen und Parkplätzen am 2026-10-29 gekommen). Jetzt ändert jede Änderung am Inhalt den Namen von
     * selbst; Zeitlimit und Ausgabeform zählen nicht mit, sie ändern die Daten nicht. Kosten: Nach einer Änderung
     * wird jede Kachel einmal neu geholt – bewusst, alte Kacheln ohne Gleise sähen aus wie ein Ort ohne Bahn.
     */
    public static String cacheTag() {
        return CACHE_TAG;
    }

    private static final String CACHE_TAG = cacheTag(selectors("{bbox}"));

    static String cacheTag(String selectors) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(selectors.getBytes(StandardCharsets.UTF_8));
            return "q" + HexFormat.of().formatHex(h, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in dieser Java-Laufzeit", e);
        }
    }

    private CompletableFuture<String> query(String area, int mirror) {
        String overpassUrl = overpassUrls.get(Math.floorMod(mirror, overpassUrls.size()));
        // timeout:140 statt 180: Overpass bricht so selbst ab, bevor der Client nach 150 s aufgibt, und meldet das als
        // HTTP 200 mit „remark: runtime error …“ – erkannt in checkRemark(). Bei 180 hing der Client stumm bis 150 s.
        // Nicht tiefer: overpass.kumi.systems brauchte im Hepberg-Test 2026-10-28 gemessen 122 s für eine Kachel.
        // Wer die remark-Prüfung entfernt, muss zurück auf 180 – sonst wird ein abgebrochener Lauf als leere Kachel
        // 30 Tage zwischengespeichert.
        // „out geom qt“: nach Quadtile statt nach ID sortiert, der Server spart das Umsortieren; OsmImport liest die
        // Elemente ohne Reihenfolgeannahme (Geometrie steht bei „out geom“ in jedem Element selbst).
        String q = "[out:json][timeout:140];" + selectors(area) + "out geom qt;";
        // 150 s: Vorher 200 s – zwei hängende Ausweichdienste kosteten je Runde fast 7 min (Hepberg-Test
        // 2026-10-28). Nicht unter 130 s: overpass.kumi.systems lieferte im selben Test erfolgreich nach 122 s.
        HttpRequest req = HttpRequest.newBuilder(URI.create(overpassUrl)).timeout(Duration.ofSeconds(150))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                // gzip: Overpass-JSON mit „out geom“ ist Koordinatentext und schrumpft auf einen Bruchteil – eine
                // Großstadtkachel ist sonst zig MB über die Leitung. Der HttpClient entpackt nicht selbst (decode()).
                .header("Accept-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(q, StandardCharsets.UTF_8)))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> {
            // 502/503 kommen bei Overpass unter Last ebenso vor wie 429/504 (München 2026-10-28: 502) – alle vier
            // sind „später nochmal“, kein Fehler der Abfrage.
            if (r.statusCode() == 429 || r.statusCode() == 502 || r.statusCode() == 503 || r.statusCode() == 504) {
                throw new BusyException("HTTP " + r.statusCode() + " von " + req.uri().getHost());
            }
            if (r.statusCode() != 200) {
                throw new IllegalStateException("Kartendienst: HTTP " + r.statusCode());
            }
            String body;
            try {
                body = decode(r.body(), r.headers().firstValue("Content-Encoding").orElse(null));
            } catch (IOException e) {
                // Abgeschnittener gzip-Strom = Übertragung abgerissen, kein Fehler der Abfrage: nächste Instanz.
                throw new BusyException("gzip unvollständig von " + req.uri().getHost() + ": " + e.getMessage());
            }
            String remark = runtimeError(body);
            if (remark != null) {
                // Overpass antwortet bei Zeit- oder Speicherüberschreitung mit HTTP 200, leeren (oder halben)
                // elements und dieser Bemerkung. Als Erfolg behandelt, landete eine leere Kachel 30 Tage im
                // Zwischenspeicher. BusyException: CityStages fragt die nächste Instanz und speichert nichts.
                throw new BusyException("Overpass-Abbruch von " + req.uri().getHost() + ": " + remark);
            }
            return body;
        });
    }

    /**
     * Antwortkörper als Text: gzip nur, wenn der Server es per Content-Encoding sagt (Groß-/Kleinschreibung egal),
     * sonst UTF-8 wie bisher. Rein, testbar. Nicht nach den Magic-Bytes raten – ein Dienst, der Accept-Encoding
     * ignoriert, liefert Klartext, und der muss unverändert durchgehen.
     */
    static String decode(byte[] body, String contentEncoding) throws IOException {
        if (contentEncoding != null && contentEncoding.trim().equalsIgnoreCase("gzip")) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Inhalt des obersten JSON-Felds „remark“, wenn es „runtime error“ enthält, sonst null. Streamend gelesen und nur,
     * wenn „"remark"“ überhaupt vorkommt: Die Antwort kann hunderte MB groß sein, ein zweiter vollständiger Baum nur
     * für diese Prüfung wäre doppelter Speicher. Nur die oberste Ebene zählt – ein OSM-Tag „remark“ an einem Gebäude
     * ist kein Abbruch. Unlesbares JSON gilt hier nicht als Abbruch; OsmImport meldet es danach als Fehler.
     */
    static String runtimeError(String body) {
        if (!body.contains("\"remark\"")) {
            return null;
        }
        try (JsonReader rd = new JsonReader(new StringReader(body))) {
            if (rd.peek() != JsonToken.BEGIN_OBJECT) {
                return null;
            }
            rd.beginObject();
            while (rd.hasNext()) {
                String name = rd.nextName();
                if (name.equals("remark") && rd.peek() == JsonToken.STRING) {
                    String remark = rd.nextString();
                    return remark.toLowerCase(Locale.ROOT).contains("runtime error") ? remark : null;
                }
                rd.skipValue();
            }
            return null;
        } catch (IOException | IllegalStateException e) {
            return null;  // fail-open bewusst, siehe Methodenkommentar: der Parser danach scheitert laut
        }
    }

    /** Overpass hat gerade keinen Platz (429/504) – später erneut versuchen lohnt sich. */
    public static final class BusyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(message);
        }
    }
}
