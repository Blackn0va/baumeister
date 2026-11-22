package com.blackn0va.baumeister.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * modul_json, Version 1 (Vertrag v012, „interface.modul_json“) – EIN Format für Host-POST und Plugin-Kanal:
 * {"version":1,"modules":[{"id","name","description","on","state","reason","needs":[…]}]}.
 *
 * Gegenstücke, die parallel dagegen gebaut werden und gleich bleiben müssen: Host-App T-031 (POST
 * /baumeister/v1/module, lehnt zu Großes und Kaputtes mit 400 ab) und Client-Mod T-032 (Kanal baumeister:module).
 * Deshalb kappt die Plugin-Seite selbst auf die Vertragsgrenzen, statt sich auf die Gegenseite zu verlassen: id
 * [a-z0-9-]{1,32}, name ≤ 40, description ≤ 160, reason ≤ 120 Zeichen. Gezählt in UTF-16-Einheiten (Java- und
 * Qt-String-Länge) – das ist nie mehr als die Zahl der Codepunkte, eine Grenze in Codepunkten hält es also ebenfalls
 * ein. Ein Surrogatpaar wird nie zerschnitten. Steuerzeichen fallen weg, Gson maskiert Anführungszeichen, Backslash
 * und den Rest.
 *
 * Nur diese Felder, keine Spielernamen, Adressen oder Geheimnisse (Vertrag, OWASP A01/ASVS V8.3.4): Die Gründe
 * stammen aus festen Texten in ModuleDirectory; der einzige fremde Text ist LocalVoice.Settings.problem(), das einen
 * Dateipfad nennen kann – deshalb nimmt ModuleService dort nur den Satz ohne Pfad (siehe voiceProblem).
 *
 * Threads: beliebig. Fehler: keine – ein Modul mit ungültiger id fällt heraus (fail-closed für die Gegenseite).
 */
public final class ModuleJson {

    public static final int VERSION = 1;
    public static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
    public static final int MAX_NAME = 40;
    public static final int MAX_DESCRIPTION = 160;
    public static final int MAX_REASON = 120;
    /** Obergrenzen, die der Vertrag nicht nennt – großzügig über dem heutigen Stand (20 Module, je ≤ 3 Bedarfe). */
    public static final int MAX_MODULES = 64;
    public static final int MAX_NEEDS = 8;

    private ModuleJson() {
    }

    public static String encode(List<ModuleEntry> modules) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray arr = new JsonArray();
        for (ModuleEntry m : modules) {
            if (arr.size() >= MAX_MODULES) {
                break;
            }
            if (!ID.matcher(m.def().id()).matches()) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", m.def().id());
            o.addProperty("name", cut(m.def().name(), MAX_NAME));
            o.addProperty("description", cut(m.def().description(), MAX_DESCRIPTION));
            o.addProperty("on", m.on());
            o.addProperty("state", m.state().wire());
            o.addProperty("reason", cut(m.reason(), MAX_REASON));
            JsonArray needs = new JsonArray();
            for (String n : m.def().needs()) {
                if (needs.size() < MAX_NEEDS && ID.matcher(n).matches()) {
                    needs.add(n);
                }
            }
            o.add("needs", needs);
            arr.add(o);
        }
        root.add("modules", arr);
        return root.toString();
    }

    /** Nutzlast für den Plugin-Kanal: das JSON als rohe UTF-8-Bytes, ohne Längenpräfix. */
    public static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Steuerzeichen (auch Zeilenumbruch) weg, außen getrimmt, höchstens max UTF-16-Einheiten; gekürzt endet der Text
     * auf „…“, das mitzählt.
     */
    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                b.append(' ');
            } else if (Character.getType(cp) != Character.FORMAT) {
                // Unsichtbare Formatzeichen (Richtungswechsel U+202E u. ä.) weg: Sie drehen in Host und Mod die
                // Anzeige um, ohne dass man es dem Text ansieht.
                b.appendCodePoint(cp);
            }
        });
        String s = b.toString().strip();
        if (s.length() <= max) {
            return s;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end).stripTrailing() + "…";
    }
}
