package com.blackn0va.baumeister.companion;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vera benutzt Dinge wie ein Mitspieler: Türen, Tore, Falltüren, Hebel, Knöpfe, Glocken, Notenblöcke, Kisten.
 *
 * Wunsch 2026-11-07. Vorher
 * konnte sie nur bauen lassen, geben und reden; „mach die Tür auf“ ging ans Modell, das keine passende Aktion hatte.
 *
 * Hier nur Sprache und Blockarten (ohne Bukkit, testbar). Ob sie darf (Fähigkeit BENUTZEN im Admin-Buch, Schutz von
 * Grundstücken über ein simuliertes Rechtsklick-Ereignis des Spielers) und das Schalten selbst entscheidet
 * CompanionService. Von selbst (AUSPROBIEREN) fasst sie nur Harmloses an (harmless): Glocke, Notenblock, Holztür,
 * Falltür, Tor – nie Hebel oder Knöpfe, denn dahinter kann TNT oder eine fremde Maschine hängen.
 *
 * Muster mit (?U): Ohne UNICODE_CHARACTER_CLASS ist \b in Java reines ASCII – „schließ“ (ß am Ende) und „öffne“
 * (ö am Anfang) fanden nie eine Wortgrenze (gemessen: CompanionUseTest rot, 2026-11-07).
 *
 * Threads: zustandslos. Fehler: keine (null = kein Auftrag).
 */
public final class CompanionUse {

    public enum Target {
        DOOR, GATE, TRAPDOOR, LEVER, BUTTON, BELL, NOTE, CHEST
    }

    /** open: true = auf/an, false = zu/aus, null = umschalten bzw. einmal drücken. */
    public record Use(Target target, Boolean open) {
    }

    private CompanionUse() {
    }

    private static final String ART = "(?:die|das|den|dem|der|diese[nrs]?|eine?n?)?\\s*";
    private static final Pattern NOUN = Pattern.compile("(?iuU)\\b(falltür|klapptür|tür|haustür|tor|gartentor|"
            + "zauntor|hebel|schalter|knopf|taster|button|glocke|klingel|notenblock|kiste|truhe|fass)\\b");
    private static final Pattern VERB = Pattern.compile("(?iuU)\\b(mach|mache|öffne|öffnest|schließ|schließe|"
            + "schliess|drück|drücke|druck|betätig|betätige|leg|lege|schalt|schalte|läut|läute|klingel|klingle|"
            + "schlag|spiel|spiele|kannst\\s+du|könntest\\s+du)\\b");
    private static final Pattern OPEN = Pattern.compile(
            "(?iuU)\\b(auf|aufmach\\w*|öffne\\w*|an|anmach\\w*|ein|einschalt\\w*|anschalt\\w*)\\b");
    private static final Pattern CLOSE = Pattern.compile(
            "(?iuU)\\b(zu|zumach\\w*|schließ\\w*|schliess\\w*|aus|ausmach\\w*|ausschalt\\w*)\\b");
    private static final Pattern SHUT_VERB = Pattern.compile("(?iuU)\\b(schließ|schliess)\\w*\\b");
    private static final Pattern OPEN_VERB = Pattern.compile("(?iuU)\\böffne\\w*\\b");

    /** Auftrag im Satz („mach die Tür auf“, „drück mal den Knopf“, „läute die Glocke“), sonst null. */
    public static Use parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher n = NOUN.matcher(text);
        if (!n.find() || !VERB.matcher(text).find()) {
            return null;
        }
        // „baue eine Tür“ ist ein Bauauftrag, „wo ist die Kiste“ eine Frage – beides nicht hier.
        if (text.matches("(?iuU).*\\b(bau|baue|baust|bauen|wo|wer|welche[nrs]?|was)\\b.*")) {
            return null;
        }
        String noun = n.group(1).toLowerCase(Locale.ROOT);
        Target t = switch (noun) {
            case "falltür", "klapptür" -> Target.TRAPDOOR;
            case "tür", "haustür" -> Target.DOOR;
            case "tor", "gartentor", "zauntor" -> Target.GATE;
            case "hebel", "schalter" -> Target.LEVER;
            case "knopf", "taster", "button" -> Target.BUTTON;
            case "glocke", "klingel" -> Target.BELL;
            case "notenblock" -> Target.NOTE;
            default -> Target.CHEST;
        };
        // Richtung: zuerst das, was hinter dem Gegenstand steht („mach die Tür zu“), dann das Verb („schließ die Tür“).
        String rest = text.substring(n.end());
        Boolean open = CLOSE.matcher(rest).find() ? Boolean.FALSE
                : OPEN.matcher(rest).find() ? Boolean.TRUE
                : SHUT_VERB.matcher(text).find() ? Boolean.FALSE
                : OPEN_VERB.matcher(text).find() ? Boolean.TRUE : null;
        if (t == Target.BUTTON || t == Target.BELL || t == Target.NOTE) {
            open = null;
        } else if (t == Target.CHEST) {
            open = Boolean.TRUE;
        }
        return new Use(t, open);
    }

    /**
     * Ziel aus dem Wort der Modell-Aktion („use“, Feld block): englisch wie im Prompt oder deutsch; null, wenn
     * unbekannt (dann fragt Vera nach statt zu raten).
     */
    public static Target target(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.strip().toLowerCase(Locale.ROOT)) {
            case "door", "tür", "haustür" -> Target.DOOR;
            case "gate", "tor", "zauntor", "fence_gate" -> Target.GATE;
            case "trapdoor", "falltür", "klapptür" -> Target.TRAPDOOR;
            case "lever", "hebel", "schalter" -> Target.LEVER;
            case "button", "knopf", "taster" -> Target.BUTTON;
            case "bell", "glocke", "klingel" -> Target.BELL;
            case "note", "note_block", "notenblock" -> Target.NOTE;
            case "chest", "kiste", "truhe", "barrel", "fass" -> Target.CHEST;
            default -> null;
        };
    }

    /** Richtung aus dem Wort der Modell-Aktion (Feld text): open/close (an/aus), sonst null = umschalten. */
    public static Boolean direction(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.strip().toLowerCase(Locale.ROOT)) {
            case "open", "on", "auf", "an", "öffnen" -> Boolean.TRUE;
            case "close", "off", "zu", "aus", "schließen" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Passt ein Block (Material-Name wie „OAK_DOOR“) zum Ziel? */
    public static boolean matches(Target t, String material) {
        if (material == null) {
            return false;
        }
        String m = material.toUpperCase(Locale.ROOT);
        return switch (t) {
            case DOOR -> m.endsWith("_DOOR");
            case GATE -> m.endsWith("_FENCE_GATE");
            case TRAPDOOR -> m.endsWith("_TRAPDOOR");
            case LEVER -> m.equals("LEVER");
            case BUTTON -> m.endsWith("_BUTTON");
            case BELL -> m.equals("BELL");
            case NOTE -> m.equals("NOTE_BLOCK");
            case CHEST -> m.equals("CHEST") || m.equals("TRAPPED_CHEST") || m.equals("BARREL");
        };
    }

    /** Nur mit Redstone zu öffnen (Eisentür, Eisenfalltür)? */
    public static boolean needsRedstone(String material) {
        return material != null && material.toUpperCase(Locale.ROOT).startsWith("IRON_");
    }

    /** Darf sie das von selbst ausprobieren? Nur Harmloses, nie Hebel/Knöpfe/Kisten. */
    public static Target harmless(String material) {
        if (material == null || needsRedstone(material)) {
            return null;
        }
        for (Target t : new Target[] {Target.BELL, Target.NOTE, Target.DOOR, Target.TRAPDOOR, Target.GATE}) {
            if (matches(t, material)) {
                return t;
            }
        }
        return null;
    }
}
