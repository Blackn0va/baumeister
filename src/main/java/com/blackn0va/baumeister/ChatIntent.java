package com.blackn0va.baumeister;

import java.util.Locale;

/**
 * Steuerwörter im Chat an den Baumeister („Baumeister, stopp“) – erkennen und entscheiden, wer sie bekommt.
 *
 * Vorher (bis 2026-10-28) lag die Wortliste in CityService.handleChat und wirkte nur, solange eine Stadt lief. Sonst
 * ging „stopp“, „status“ oder „hör auf“ als Bauauftrag ans LLM: Ein Normalbau ließ sich im Chat nicht anhalten, und
 * „status“ im Leerlauf kostete einen Planungslauf von mehreren Sekunden, aus dem im schlimmsten Fall ein Bau wurde.
 * Jetzt gilt: Ein Steuerwort geht nie ans LLM. Ohne Stadtbau landen Stopp, Status und Rückgängig beim normalen Bau,
 * Tempo und Pause bekommen die Antwort „Gerade läuft kein Stadtbau.“. Wer {@link #route} wieder auf „nur bei
 * laufender Stadt“ zurückdreht, schickt „stopp“ während eines Normalbaus wieder als Auftrag ans LLM.
 *
 * „mehr helfer“/„weniger helfer“ gehen seit 2026-10-30 ohne Stadt an den normalen Bau (HelperWish, Wunsch). Vorher kam
 * auch dort „Gerade läuft kein
 * Stadtbau.“, obwohl ein Haus mit Helfern lief.
 *
 * Es zählt nur die ganze Nachricht: „stopp“ ist ein Steuerwort, „stopp die Mauer bei der Brücke“ ein Bauauftrag.
 *
 * Threads: zustandslos, von überall. Fehler: keine.
 */
final class ChatIntent {

    enum Kind {
        STOP, UNDO, STATUS, COME, PAUSE, RESUME, FASTER, SLOWER, MORE_HELPERS, FEWER_HELPERS, BUILD
    }

    /** Wer die Nachricht bekommt. NOT_RUNNING = Stadtsteuerung ohne laufende Stadt, der Spieler bekommt nur Antwort. */
    enum Target {
        CITY, BUILD_SERVICE, LLM, NOT_RUNNING
    }

    private ChatIntent() {
    }

    /** Normalisiert wie früher CityService.handleChat: getrimmt, klein (deutsch), Satzzeichen am Ende weg. */
    static Kind parse(String text) {
        String t = text.trim().toLowerCase(Locale.GERMAN).replaceAll("[!.?]+$", "");
        return switch (t) {
            case "stopp", "stop", "hör auf", "aufhören", "abbrechen" -> Kind.STOP;
            // „rueckgaengig“ für Tastaturen ohne Umlaute, wie /bm rueckgaengig. umlaut-ok
            case "rückgängig", "rueckgaengig", "mach das rückgängig", "weg damit", "undo" -> Kind.UNDO; // umlaut-ok
            case "fortschritt", "wie weit bist du", "status" -> Kind.STATUS;
            case "komm", "komm her", "komm zu mir", "hierher" -> Kind.COME;
            case "pause", "pausiere", "halt", "warte" -> Kind.PAUSE;
            case "weiter", "mach weiter", "weitermachen" -> Kind.RESUME;
            case "schneller", "mach schneller", "gib gas" -> Kind.FASTER;
            case "langsamer", "mach langsamer" -> Kind.SLOWER;
            case "mehr helfer" -> Kind.MORE_HELPERS;
            case "weniger helfer" -> Kind.FEWER_HELPERS;
            default -> Kind.BUILD;
        };
    }

    /**
     * buildBusy entscheidet bewusst nichts: „stopp“ im Leerlauf geht ebenfalls an den normalen Bau, der dann „Ich baue
     * gerade nichts.“ bzw. während der Planung „Ich plane noch …“ antwortet – nie ans LLM.
     */
    static Target route(Kind kind, boolean cityRunning, boolean buildBusy) {
        return switch (kind) {
            case BUILD -> Target.LLM;
            // „komm“ holt den NPC auch ohne Stadtbau (CityService.control kennt den Fall).
            case COME -> Target.CITY;
            case STOP, STATUS, MORE_HELPERS, FEWER_HELPERS -> cityRunning ? Target.CITY : Target.BUILD_SERVICE;
            // Wie /bm rückgängig: der Aufrufer stoppt eine laufende Stadt vorher selbst.
            case UNDO -> Target.BUILD_SERVICE;
            case PAUSE, RESUME, FASTER, SLOWER -> cityRunning ? Target.CITY : Target.NOT_RUNNING;
        };
    }

    /** Aktion für CityService.control; null für Arten, die nie an die Stadt gehen. */
    static String cityAction(Kind kind) {
        return switch (kind) {
            case STOP -> "stop";
            case STATUS -> "fortschritt";
            case COME -> "komm";
            case PAUSE -> "pause";
            case RESUME -> "weiter";
            case FASTER -> "schneller";
            case SLOWER -> "langsamer";
            case MORE_HELPERS -> "mehrhelfer";
            case FEWER_HELPERS -> "wenigerhelfer";
            case UNDO, BUILD -> null;
        };
    }
}
