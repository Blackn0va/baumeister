package com.blackn0va.baumeister.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Server-Module aus dem Plugin-Kanal baumeister:module (AK-39, Vertrag v012 „modul_json“): Parser mit den Grenzen des
 * Vertrags und der letzte gültige Stand für den Bildschirm. Reine Anzeige – geschaltet wird nur über /bm module im
 * Plugin (Rechte dort, fail-closed). Deshalb hier fail-open: Eine kaputte Nachricht wird verworfen und geloggt, der
 * vorige Stand bleibt stehen.
 *
 * Streng statt nachsichtig: Der Vertrag sagt „nur diese Felder“. Ein unbekanntes Feld, ein Zustand außerhalb von
 * aktiv|aus|wartet|fehler oder ein zu langer Text verwirft die ganze Nachricht, nicht nur das eine Modul – sonst zeigt
 * der Bildschirm still eine halbe Liste, und niemand merkt, dass Plugin und Mod auseinanderlaufen.
 *
 * Threads: parse ist rein; accept/clear/current über volatile (Netz-Rückruf und Bildschirm). Fehler: parse wirft
 * nicht, sondern liefert {@link Result#reason()}.
 */
final class ServerModules {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");

    /**
     * Obergrenze der Nutzlast in Byte. Im Vertrag stehen nur Feldgrenzen; 64 Module × knapp 1 KB (alle Texte am
     * Anschlag, Umlaute zu 2 Byte) passen hinein, eine verirrte große Nachricht nicht. Dieselbe Zahl prüft
     * ModulePayload schon beim Lesen, damit kein Riesenpuffer entsteht.
     */
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_MODULES = 64;
    static final int MAX_NEEDS = 16;
    static final int MAX_NAME = 40;
    static final int MAX_DESCRIPTION = 160;
    static final int MAX_REASON = 120;
    /** id wie im Vertrag: [a-z0-9-]{1,32}; dieselbe Form für die Einträge in needs (sie nennen Modul-ids). */
    static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
    static final Set<String> STATES = Set.of("aktiv", "aus", "wartet", "fehler");
    private static final Set<String> TOP_FIELDS = Set.of("version", "modules");
    private static final Set<String> MODULE_FIELDS = Set.of("id", "name", "description", "on", "state", "reason",
            "needs");

    record Module(String id, String name, String description, boolean on, String state, String reason,
            List<String> needs) {
    }

    /** modules != null ⇔ gültig; sonst nennt reason den ersten Verstoß (für das Log, nie für den Spieler). */
    record Result(List<Module> modules, String reason) {
        boolean ok() {
            return modules != null;
        }

        static Result rejected(String reason) {
            return new Result(null, reason);
        }
    }

    /** null = noch nichts empfangen (seit Beitritt) → Bildschirm sagt „Server meldet keine Module“. */
    private static volatile List<Module> current;

    private ServerModules() {
    }

    static List<Module> current() {
        return current;
    }

    /** Beim Beitritt und Verlassen: Die Module eines anderen Servers gehören nicht auf diesen Bildschirm. */
    static void clear() {
        current = null;
    }

    /** Nachricht aus dem Kanal übernehmen; kaputt → verwerfen, loggen, alter Stand bleibt. */
    static Result accept(byte[] utf8) {
        Result r = parse(utf8);
        if (r.ok()) {
            current = r.modules();
            LOG.info(() -> "module | server_empfangen | anzahl=" + r.modules().size());
        } else {
            LOG.warning(() -> "module | server_verworfen | grund=" + r.reason() + " bytes="
                    + (utf8 == null ? -1 : utf8.length));
        }
        return r;
    }

    static Result parse(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            return Result.rejected("leer");
        }
        if (utf8.length > MAX_BYTES) {
            return Result.rejected("zu_gross");
        }
        String text;
        try {
            // Strikt dekodieren: Der Standardweg (new String) ersetzt kaputte Bytes still durch U+FFFD.
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            return Result.rejected("kein_utf8");
        }
        JsonElement root;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            // STRICT: Gson liest sonst nachsichtig (unquotierte Schlüssel, ' statt ", Kommentare) – das wäre schon kein
            // modul_json mehr.
            reader.setStrictness(Strictness.STRICT);
            root = JsonParser.parseReader(reader);
            // parseReader liest nur den ersten Wert – ohne diesen Blick dahinter ginge „{…} Müll“ als gültig durch.
            // Strikt wirft peek bei weiteren Daten selbst (MalformedJsonException); der Vergleich fängt den Rest.
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return Result.rejected("kein_json");
            }
        } catch (JsonParseException | IOException | IllegalStateException e) {
            return Result.rejected("kein_json");
        }
        if (!root.isJsonObject()) {
            return Result.rejected("kein_objekt");
        }
        JsonObject o = root.getAsJsonObject();
        String extra = unknownField(o, TOP_FIELDS);
        if (extra != null) {
            return Result.rejected("unbekanntes_feld:" + extra);
        }
        Integer version = integer(o.get("version"));
        if (version == null || version != 1) {
            return Result.rejected("version");
        }
        JsonElement list = o.get("modules");
        if (list == null || !list.isJsonArray()) {
            return Result.rejected("modules_fehlt");
        }
        JsonArray arr = list.getAsJsonArray();
        if (arr.size() > MAX_MODULES) {
            return Result.rejected("zu_viele_module");
        }
        List<Module> out = new ArrayList<>(arr.size());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) {
                return Result.rejected("modul_kein_objekt:" + i);
            }
            JsonObject m = e.getAsJsonObject();
            String bad = unknownField(m, MODULE_FIELDS);
            if (bad != null) {
                return Result.rejected("unbekanntes_feld:" + i + "." + bad);
            }
            String id = string(m.get("id"));
            if (id == null || !ID.matcher(id).matches()) {
                return Result.rejected("id:" + i);
            }
            if (!ids.add(id)) {
                return Result.rejected("id_doppelt:" + id);
            }
            String name = string(m.get("name"));
            if (name == null || name.isBlank() || !text(name, MAX_NAME)) {
                return Result.rejected("name:" + id);
            }
            String description = optionalString(m, "description");
            if (description == null || !text(description, MAX_DESCRIPTION)) {
                return Result.rejected("description:" + id);
            }
            Boolean on = bool(m.get("on"));
            if (on == null) {
                return Result.rejected("on:" + id);
            }
            String state = string(m.get("state"));
            if (state == null || !STATES.contains(state)) {
                return Result.rejected("state:" + id);
            }
            String reason = optionalString(m, "reason");
            if (reason == null || !text(reason, MAX_REASON)) {
                return Result.rejected("reason:" + id);
            }
            List<String> needs = needs(m.get("needs"));
            if (needs == null) {
                return Result.rejected("needs:" + id);
            }
            out.add(new Module(id, name, description, on, state, reason, needs));
        }
        return new Result(List.copyOf(out), null);
    }

    private static String unknownField(JsonObject o, Set<String> allowed) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                return e.getKey().length() > 32 ? e.getKey().substring(0, 32) : e.getKey();
            }
        }
        return null;
    }

    /**
     * Länge in Zeichen (Codepunkten) wie im Vertrag, nicht in UTF-16-Einheiten. Steuerzeichen und das Formatzeichen §
     * fallen durch: Font malt §-Codes auch in Component.literal als Farbe bzw. Verschleierung (§k) – ein Grund vom
     * Server könnte sich sonst als etwas anderes ausgeben.
     */
    private static boolean text(String s, int max) {
        if (s.codePointCount(0, s.length()) > max) {
            return false;
        }
        return s.codePoints().noneMatch(c -> c < 0x20 || c == 0x7F || c == '§' || (c >= 0x80 && c < 0xA0));
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** Fehlt das Feld (oder ist null), gilt "" – ein aktives Modul hat keinen Grund. Ein Nicht-Text ist kaputt. */
    private static String optionalString(JsonObject m, String key) {
        JsonElement e = m.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return string(e);
    }

    private static Boolean bool(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
    }

    private static Integer integer(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(p.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;  // 1.5 oder 1e99 ist keine Fassung – Prüfergebnis, kein Fehlerfall
        }
    }

    private static List<String> needs(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return List.of();
        }
        if (!e.isJsonArray() || e.getAsJsonArray().size() > MAX_NEEDS) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement n : e.getAsJsonArray()) {
            String s = string(n);
            if (s == null || !ID.matcher(s).matches()) {
                return null;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }
}
