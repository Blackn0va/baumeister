package com.blackn0va.baumeister.screen;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * „Bau mir einen Bildschirm mit Uhr“, „ein Display mit bewegten Bildern“, „zeig katze.gif auf dem Bildschirm“ –
 * Bildschirm-Auftrag erkennen (ohne Modell).
 *
 * Abgrenzung zur 7-Segment-Anzeige (QuickOrders.display, Machines „anzeige“): Wunsch 2026-11-07 und dann „Bewegt
 * Bilder“ – bekam aber die Zahlenanzeige. Jetzt ist „Display“, „Bildschirm“, „Monitor“,
 * „Fernseher“ ein Bildschirm aus Karten; mit Redstone-Wörtern (Redstone, Segment, Ziffern, Zahlen, Hex, „Anzeige“
 * ohne Bild) bleibt es die geprüfte Redstone-Maschine.
 *
 * Threads: zustandslos. Fehler: keine (null = kein Bildschirm-Auftrag).
 */
public final class ScreenRequest {

    public record Request(ScreenContent.Kind kind, int width, int height, String text, String file) {
    }

    private static final int DEFAULT_W = 3;
    private static final int DEFAULT_H = 2;

    private static final Pattern SCREEN = Pattern.compile("(?iuU)\\b(bildschirm\\w*|display\\w*|monitor\\w*|"
            + "fernseh\\w*|tv|leinwand|videowand|bilderwand|bilderrahmen\\s+mit\\s+bewegten)\\b");
    private static final Pattern MOVING = Pattern.compile("(?iuU)\\b(bewegt\\w*\\s+bild\\w*|bewegte|video\\w*|film\\w*|"
            + "animation\\w*|animiert\\w*|gif\\w*)\\b");
    private static final Pattern REDSTONE = Pattern.compile("(?iuU)\\b(redstone|segment\\w*|7|sieben|ziffer\\w*|"
            + "zahl\\w*|hex\\w*|binär\\w*|binaer\\w*|digitalanzeige|zahlenanzeige|ziffernanzeige|siebensegment\\w*)\\b"); // umlaut-ok: Eingabe ohne Umlaut-Tastatur
    private static final Pattern BUILD_OR_SHOW = Pattern.compile("(?iuU)\\b(bau\\w*|mach\\w*|stell\\w*|häng\\w*|"
            + "zeig\\w*|spiel\\w*|ich\\s+(will|möchte|hätte\\s+gern)|gib\\s+mir|hol\\w*)\\b");
    private static final Pattern WANT = Pattern.compile("(?iuU)\\bmir\\s+(ein|einen|eine)\\b");
    /** Ganze Antwort nur „bewegte Bilder“ o. Ä. – dann auch ohne Verb (Nachfrage des Nutzers, Log 2026-11-07). */
    private static final Pattern ONLY_PICTURES = Pattern.compile("(?iuU)^\\W*(mit\\s+)?(bewegt\\w*\\s+bild\\w*|"
            + "animation\\w*|animiert\\w*|video\\w*|gif\\w*|[A-Za-z0-9_-]{1,48}\\.(gif|png|jpe?g))\\W*$");
    private static final Pattern FILE = Pattern.compile("(?iuU)\\b([A-Za-z0-9_-]{1,48}\\.(gif|png|jpe?g))\\b");
    private static final Pattern SIZE = Pattern.compile("(?iuU)\\b([1-8])\\s*(x|×|mal)\\s*([1-6])\\b");
    private static final Pattern CLOCK = Pattern.compile("(?iuU)\\b(uhr|uhrzeit|zeit\\s+anzeig\\w*)\\b");
    private static final Pattern TICKER = Pattern.compile(
            "(?iuU)\\b(laufschrift|lauftext|schrift|text)\\b\\s*[:„\"]?\\s*(.*)$");
    private static final Pattern BIG = Pattern.compile("(?iuU)\\b(groß\\w*|gross\\w*|riesig\\w*|breit\\w*)\\b");
    private static final Pattern SMALL = Pattern.compile("(?iuU)\\b(klein\\w*|mini)\\b");

    private ScreenRequest() {
    }

    /** Bildschirm-Auftrag im Satz, sonst null. */
    public static Request parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean screen = SCREEN.matcher(text).find();
        boolean moving = MOVING.matcher(text).find();
        Matcher file = FILE.matcher(text);
        boolean hasFile = file.find();
        // Auftrag nur mit Bau-/Zeige-Wort oder „mir ein …“ („bei mir ein Display“ – so kam „bau mir ein Display“ aus
        // der Spracherkennung, Log 2026-11-07). Kurze Antworten wie „Bewegte Bilder.“ zählen ohne Verb: Das war die
        // Nachfrage des Wunsch dagegen ist kein Bauauftrag.
        boolean ask = BUILD_OR_SHOW.matcher(text).find() || WANT.matcher(text).find();
        if (!screen && !moving && !hasFile) {
            return null;
        }
        if (screen && REDSTONE.matcher(text).find() && !moving && !hasFile) {
            return null;
        }
        if (!ask && !ONLY_PICTURES.matcher(text.strip()).matches()) {
            return null;
        }
        int w = DEFAULT_W;
        int h = DEFAULT_H;
        Matcher size = SIZE.matcher(text);
        if (size.find()) {
            w = Integer.parseInt(size.group(1));
            h = Integer.parseInt(size.group(3));
        } else if (BIG.matcher(text).find()) {
            w = 5;
            h = 3;
        } else if (SMALL.matcher(text).find()) {
            w = 2;
            h = 1;
        }
        if (hasFile) {
            return new Request(ScreenContent.Kind.BILD, w, h, "", file.group(1));
        }
        Matcher ticker = TICKER.matcher(text);
        if (ticker.find() && !ticker.group(2).isBlank()) {
            String t = ticker.group(2).replaceAll("[“”\"]+$", "").strip();
            return new Request(ScreenContent.Kind.LAUFSCHRIFT, w, Math.min(h, 2), t.length() > 120 ? t.substring(0, 120)
                    : t, "");
        }
        if (CLOCK.matcher(text).find()) {
            return new Request(ScreenContent.Kind.UHR, w, h, "", "");
        }
        return new Request(ScreenContent.Kind.ANIMATION, w, h, "", "");
    }

    /** Für Logs und Antworten: „Animation 3 × 2“. */
    public static String describe(Request r) {
        String what = switch (r.kind()) {
            case ANIMATION -> "Animation";
            case UHR -> "Uhr";
            case LAUFSCHRIFT -> "Laufschrift";
            case BILD -> r.file().toLowerCase(Locale.ROOT).endsWith(".gif") ? "GIF " + r.file() : "Bild " + r.file();
        };
        return what + " " + r.width() + " × " + r.height();
    }
}
