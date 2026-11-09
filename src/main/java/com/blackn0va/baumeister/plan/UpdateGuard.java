package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Schutz des alten Baus bei "mode":"update": Ein Umbau darf nur wegräumen, was der Spieler weghaben will.
 *
 * Vorfall dev01 2026-11-04 18:05: „Baue mir zu dem Bunker noch einen Pool“. Der System-Prompt verlangt bei update den
 * VOLLSTÄNDIGEN Plan (alter Bau plus Änderung), das 14B-Modell lieferte aber nur 505 Token – den Pool und ein paar
 * Bunkerteile. startJob räumte daraufhin 6 410 Blöcke des Bunkers weg. Der erste Versuch zwei Minuten vorher scheiterte
 * am anderen Ende: Der vollständige Plan passte nicht in die 8 192 Token Kontext (abgeschnitten nach 1 697 Token).
 * Jetzt gilt: Nennt der Auftrag kein Wegnehmen oder Ersetzen und fehlt im neuen Plan mehr als {@link #MAX_SHARE} des
 * alten Baus, ist es ein Anbau – der alte Bau bleibt stehen, und beide Pläne werden für das Gedächtnis vereint.
 * Wer das zurückdreht, lässt jede Ergänzung den halben Bau abreißen, sobald das Modell den alten Teil kürzt.
 *
 * Threads: zustandslos. Fehler: {@link #merge} wirft bei ungültigem JSON (Aufrufer fängt an der Grenze).
 */
public final class UpdateGuard {

    /** Mehr als ein Viertel weg ohne Abrisswunsch ist kein Umbau mehr, sondern ein gekürzter Plan. */
    static final double MAX_SHARE = 0.25;
    /** Kleine Umbauten (Tür versetzen, Zaun kürzen) räumen ein paar Blöcke weg, auch ohne Abrisswort. */
    static final int MIN_BLOCKS = 64;

    /** Wortanfänge, mit denen der Spieler Teile weghaben oder ersetzen will. */
    static final List<String> REMOVAL = List.of("entfern", " weg ", "abreiß", "abreis", "reiß", "lösch",
            "ohne", "ersetz", "statt", "anstatt", "anstelle", "kleiner", "schmaler", "niedriger", "kürzer", "abbau",
            "bau um", "baue um", "umbau", "neu bau", "neu machen", "komplett neu", "remove", "replace", "instead",
            "smaller", "tear");

    private UpdateGuard() {
    }

    /** Ob der Auftrag ausdrücklich Teile wegnehmen oder ersetzen will. */
    public static boolean asksRemoval(String prompt) {
        // Satzzeichen zu Leerzeichen: „mach den Zaun weg.“ trifft ' weg ', „Wege“ nicht.
        String p = " " + prompt.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ") + " ";
        for (String w : REMOVAL) {
            if (p.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param oldCells Blöcke des alten Baus, die noch so dastehen
     * @param gone     davon im neuen Plan nicht mehr enthalten
     * @return true = alten Bau stehen lassen (Anbau statt Ersatz)
     */
    public static boolean keepOld(String prompt, int oldCells, int gone) {
        if (gone <= 0 || oldCells <= 0 || asksRemoval(prompt)) {
            return false;
        }
        if (gone >= oldCells) {
            // Nichts vom alten Bau im Plan: Das Modell hat nur den neuen Teil geliefert (so verlangt es der
            // System-Prompt bei reinen Anbauten) – auch bei kleinen alten Bauten unter MIN_BLOCKS.
            return true;
        }
        return gone >= MIN_BLOCKS && gone > oldCells * MAX_SHARE;
    }

    /** Alter Plan plus neue Schritte (Gedächtnis nach einem Anbau). Design und Antwort kommen vom neuen Plan. */
    public static String merge(String oldPlan, String newPlan) {
        JsonObject older = JsonParser.parseString(oldPlan).getAsJsonObject();
        JsonObject newer = JsonParser.parseString(newPlan).getAsJsonObject();
        JsonArray steps = new JsonArray();
        if (older.has("steps") && older.get("steps").isJsonArray()) {
            steps.addAll(older.getAsJsonArray("steps"));
        }
        if (newer.has("steps") && newer.get("steps").isJsonArray()) {
            steps.addAll(newer.getAsJsonArray("steps"));
        }
        JsonObject out = newer.deepCopy();
        out.add("steps", steps);
        out.addProperty("mode", "new");
        return out.toString();
    }
}
