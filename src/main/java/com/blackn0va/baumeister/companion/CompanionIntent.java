package com.blackn0va.baumeister.companion;

import com.blackn0va.baumeister.companion.CompanionReply.Action;
import com.blackn0va.baumeister.companion.CompanionReply.Op;
import com.blackn0va.baumeister.companion.CompanionReply.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Was Vera ohne Modell versteht – als dieselbe Antwort, die das Modell geben würde (say + Aktionen). CompanionService
 * führt sie über denselben Weg aus (act: Rechte, Rückfragen, Fair-Play), also verhält sich Vera mit und ohne
 * Baumeister-App gleich.
 *
 * Grundlage: 171 echte Sätze an Vera aus dem Log von dev01 (bis 2026-11-07). Die häufigsten gingen ans Modell, obwohl
 * sie klare Befehle waren: „Tageslicht“ (7×), „kannst du schneien lassen?“, „bring mich zur Farmwelt“, „wo finde ich
 * Diamant?“, „mach, dass ich fliegen kann“, „was könntest du mir bauen?“. Ohne App hieß die Antwort dann nur „Ich kann
 * gerade nicht nachdenken“ (Wunsch 2026-11-07).
 *
 * Zwei Stufen:
 * - command: eindeutige Befehle – immer ohne Modell, auch wenn es läuft (schneller, und das Modell traf sie nicht
 *   verlässlich).
 * - chat: Gespräch (Gruß, Dank, „wie geht's“, „was kannst du“) – nur, wenn das Modell nicht antwortet.
 *
 * Threads: zustandslos. Fehler: keine; nicht verstanden → leer.
 */
public final class CompanionIntent {

    /** Was das Plugin über den Spieler weiß, soweit es die Antwort ändert. */
    public record Known(boolean creative) {
    }

    private CompanionIntent() {
    }

    private static final Pattern NOT_A_COMMAND = Pattern.compile(
            "(?iuU)^(?:was|wie|warum|wieso|weshalb|wann|wer)\\b|\\b(guten?|schönen?|gute)\\s+(tag|morgen|abend|nacht)\\b");
    /**
     * Tag/Nacht nur in fester Form („mach Tag“, „Tageslicht“, „kannst du es wieder hell machen“) – ein Tageswort
     * irgendwo im Satz („ich will morgen spielen“, „gib mir jeden Tag Brot“) ist kein Befehl.
     */
    private static final String SET_LEAD = "(?:(?:mach|mache|macht|lass|stell|stelle|bitte|wieder|es|soll|sollte|wäre|"
            + "wär|werde|ich\\s+will|ich\\s+möchte|will|möchte|kannst\\s+du|könntest\\s+du)\\s+)*(?:es\\s+)?"
            + "(?:wieder\\s+)?(?:der\\s+|den\\s+)?";
    private static final String SET_TAIL = "(?:\\s+(?:machen|werden|sein|bitte|stellen|lassen|haben))?$";
    private static final Pattern DAY = Pattern.compile(
            "(?iuU)^" + SET_LEAD + "(?:tag|tageslicht|hell|helligkeit|mittag|sonnenaufgang)" + SET_TAIL);
    private static final Pattern NIGHT = Pattern.compile(
            "(?iuU)^" + SET_LEAD + "(?:nacht|dunkel|mitternacht)" + SET_TAIL);
    private static final Pattern SET_VERB = Pattern.compile(
            "(?iuU)\\b(mach|mache|macht|machen|lass|lasse|stell|stelle|werde|wäre|wär|es\\s+soll|soll|bitte|will|"
                    + "möchte|kannst|könntest|wieder)\\b|^\\S+[.!]?$");
    private static final Pattern RAIN = Pattern.compile("(?iuU)\\b(regen|regnen|regnet|regnen\\s+lassen)\\b");
    private static final Pattern SNOW = Pattern.compile("(?iuU)\\b(schnee|schneien|schneit)\\b");
    private static final Pattern THUNDER = Pattern.compile("(?iuU)\\b(gewitter|gewittern|donner|blitz|blitze|sturm)\\b");
    private static final Pattern CLEAR = Pattern.compile(
            "(?iuU)\\b(sonne|sonnig|sonnenschein|schönes\\s+wetter|klares?\\s+wetter|aufhören\\s+zu\\s+regnen|"
                    + "kein(en)?\\s+regen|regen\\s+(aus|weg|stopp))\\b");
    private static final Pattern WEATHER_Q = Pattern.compile(
            "(?iuU)\\b(wie\\s+ist\\s+das\\s+wetter|wird\\s+es\\s+regnen|regnet\\s+es|was\\s+für\\s+wetter)\\b");
    private static final Pattern TIME_Q = Pattern.compile(
            "(?iuU)\\b(wie\\s+spät|wieviel\\s+uhr|wie\\s+viel\\s+uhr|welche\\s+uhrzeit|uhrzeit)\\b");
    private static final Pattern GOTO = Pattern.compile(
            "(?iuU)^(?:(?:bring|bringe|teleportier|teleportiere|beam|beame|schick|schicke|portier)\\w*\\s+(?:mich|uns|dich)"
                    + "\\s+|(?:ich\\s+)?(?:will|möchte)\\s+|(?:geh|gehe|gehen)\\s+(?:wir\\s+)?|lass\\s+uns\\s+)?"
                    + "(?:zur|zum|zu|in\\s+die|in\\s+den|ins|nach|auf\\s+die|auf\\s+den)\\s+(.+?)(?:\\s+(?:bringen|gehen|"
                    + "teleportieren|reisen))?$");
    private static final Pattern FIND = Pattern.compile(
            "(?iuU)^wo\\s+(?:finde\\s+ich|ist|sind|gibt\\s+es|kriege\\s+ich|bekomme\\s+ich|kann\\s+ich)\\s+(?:hier\\s+)?"
                    + "(?:in\\s+der\\s+nähe\\s+)?(?:ein|eine|einen|das|die|der|den|denn\\s+)?\\s*(.+?)"
                    + "(?:\\s+(?:finden|abbauen|kriegen|bekommen))?$");
    private static final Pattern FLY = Pattern.compile("(?iuU)\\b(fliegen|fliege|fliegt|flug|fly)\\b");
    private static final Pattern SUMMON = Pattern.compile(
            "(?iuU)\\b(?:spawn|spawne|spawnen|beschwör|beschwöre|beschwören|erschaffe|erschaffen|ruf|rufe)\\b");
    private static final Map<String, String> MOBS = Map.ofEntries(
            Map.entry("zombie", "zombie"), Map.entry("zombies", "zombie"), Map.entry("skelett", "skeleton"),
            Map.entry("creeper", "creeper"), Map.entry("schwein", "pig"), Map.entry("kuh", "cow"),
            Map.entry("schaf", "sheep"), Map.entry("huhn", "chicken"), Map.entry("pferd", "horse"),
            Map.entry("wolf", "wolf"), Map.entry("hund", "wolf"), Map.entry("katze", "cat"),
            Map.entry("dorfbewohner", "villager"), Map.entry("spinne", "spider"), Map.entry("enderman", "enderman"),
            Map.entry("hexe", "witch"), Map.entry("papagei", "parrot"), Map.entry("fuchs", "fox"),
            Map.entry("panda", "panda"), Map.entry("axolotl", "axolotl"), Map.entry("biene", "bee"));

    /** Eindeutige Befehle → Antwort wie vom Modell; sonst leer (dann Modell bzw. chat). */
    public static Optional<Reply> command(String raw, Known k) {
        String t = CompanionChat.stripLead(raw).replaceAll("[!.?]+$", "").trim();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        if (WEATHER_Q.matcher(t).find()) {
            return Optional.of(reply("", new Action(Op.WEATHER, "", 0, "", 0, "", "", "")));
        }
        if (TIME_Q.matcher(t).find()) {
            return Optional.of(reply("", new Action(Op.TIME, "", 0, "", 0, "", "", "")));
        }
        Matcher find = FIND.matcher(t);
        if (find.matches()) {
            String what = find.group(1).trim();
            // „wo ist mein Zuhause / Alex“ beantwortet WHERE (Orte und Spieler); Blöcke entscheidet CompanionService.
            return Optional.of(reply("", new Action(Op.FIND, "", 0, what, 64, "", "", what)));
        }
        boolean question = NOT_A_COMMAND.matcher(t).find();
        if (!question) {
            List<String> cmds = new ArrayList<>();
            String say = null;
            boolean clear = CLEAR.matcher(t).find();
            if (THUNDER.matcher(t).find()) {
                cmds.add("weather thunder");
                say = "Achtung, es wird stürmisch!";
            } else if (SNOW.matcher(t).find()) {
                cmds.add("weather rain");
                say = "Ich lasse es schneien – Schnee fällt überall, wo es kalt genug ist, sonst regnet es.";
            } else if (RAIN.matcher(t).find() && !clear) {
                cmds.add("weather rain");
                say = "Es fängt gleich an zu regnen.";
            } else if (clear) {
                cmds.add("weather clear");
                say = "Die Sonne kommt raus!";
            }
            // Wetter nur mit Verb („mach“, „lass“, „will“ …) oder als kurzer Satz – „ich mag keinen Regen“ ist kein
            // Befehl.
            boolean setVerb = SET_VERB.matcher(t).find() || t.split("\\s+").length <= 2;
            if (!setVerb) {
                cmds.clear();
                say = null;
            }
            if (DAY.matcher(t).matches()) {
                cmds.add("time set day");
                say = say == null ? "Guten Morgen – ich mache es hell!" : say;
            } else if (NIGHT.matcher(t).matches()) {
                cmds.add("time set night");
                say = say == null ? "Jetzt wird es Nacht – pass auf Monster auf." : say;
            } else if (setVerb && clear && t.toLowerCase(Locale.ROOT).contains("sonne")
                    && t.toLowerCase(Locale.ROOT).contains("schein")) {
                cmds.add("time set day");
            }
            if (!cmds.isEmpty()) {
                List<Action> acts = new ArrayList<>();
                for (String c : cmds) {
                    acts.add(new Action(Op.COMMAND, "", 0, "", 0, "", c, ""));
                }
                return Optional.of(new Reply(say, List.copyOf(acts)));
            }
        }
        if (FLY.matcher(t).find() && !question) {
            if (k.creative()) {
                return Optional.of(new Reply("Im Kreativmodus kannst du schon fliegen: zweimal schnell Leertaste "
                        + "drücken, mit Leertaste hoch, mit Schleichen runter.", List.of()));
            }
            return Optional.of(new Reply("Hier sind Elytren und Raketen: Elytren anziehen, springen, in der Luft "
                    + "Leertaste – mit einer Rakete in der Hand rechtsklicken für Schub.", List.of(
                            new Action(Op.GIVE, "elytra", 1, "", 0, "", "", ""),
                            new Action(Op.GIVE, "firework_rocket", 32, "", 0, "", "", ""))));
        }
        if (SUMMON.matcher(t).find()) {
            for (String w : t.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                String mob = MOBS.get(w);
                if (mob != null) {
                    return Optional.of(new Reply("Achtung, gleich steht hier ein " + w + "!", List.of(
                            new Action(Op.COMMAND, "", 0, "", 0, "", "summon " + mob + " ~2 ~ ~", ""))));
                }
            }
        }
        Matcher go = GOTO.matcher(t);
        if (go.matches()) {
            String where = go.group(1).trim();
            // Ohne Reiseverb („In die Farmwelt.“) nur ein einzelnes Wort – „zum Beispiel ein Haus“ ist keine Reise.
            boolean bare = t.toLowerCase(Locale.ROOT).matches("^(zur|zum|zu|in|ins|nach|auf)\\b.*");
            int words = where.isEmpty() ? 0 : where.split("\\s+").length;
            if (words >= 1 && words <= (bare ? 1 : 3)) {
                return Optional.of(reply("", new Action(Op.GOTO, "", 0, "", 0, "", "", where)));
            }
        }
        return Optional.empty();
    }

    private static final Pattern HELP = Pattern.compile(
            "(?iuU)\\b(was\\s+kannst\\s+du|was\\s+könntest\\s+du|wobei\\s+kannst\\s+du|kannst\\s+du\\s+mir\\s+helfen|"
                    + "hilf\\s+mir|was\\s+können\\s+wir|was\\s+machen\\s+wir|was\\s+soll\\s+ich|mir\\s+ist\\s+langweilig)\\b");
    private static final Pattern BUILD_Q = Pattern.compile("(?iuU)\\bwas\\s+(kannst|könntest|kann)\\s+.*\\bbauen\\b");
    private static final Pattern GREET = Pattern.compile(
            "(?iuU)^(hallo|hi|hey|servus|moin|grüß\\s+dich|guten\\s+(morgen|tag|abend)|na)\\b");
    private static final Pattern HOW = Pattern.compile(
            "(?iuU)\\b(wie\\s+geht'?s|wie\\s+geht\\s+es\\s+dir|und\\s+dir|alles\\s+gut|wie\\s+läuft'?s)\\b");
    private static final Pattern THANKS = Pattern.compile("(?iuU)\\b(danke|dankeschön|vielen\\s+dank|merci|thx)\\b");
    private static final Pattern BYE = Pattern.compile("(?iuU)\\b(gute\\s+nacht|bis\\s+später|bis\\s+dann|ciao)\\b");
    private static final Pattern HEAR = Pattern.compile("(?iuU)\\b(hörst\\s+du\\s+mich|kannst\\s+du\\s+mich\\s+hören|"
            + "kannst\\s+mich\\s+hören|bist\\s+du\\s+da)\\b");
    private static final Pattern YES = Pattern.compile(
            "(?iuU)^(ja|jo|jep|okay|ok|gut|super|cool|klar|passt|bitte|ja,?\\s+bitte|ja,?\\s+gerne|gerne|gern)$");
    private static final Pattern NO = Pattern.compile("(?iuU)^(nein|nö|nee|ne|lieber\\s+nicht)$");
    /** Grob: was Vera nicht beantwortet. Kein Katalog aller Schimpfwörter – nur die im Log vorgekommenen Richtungen. */
    private static final Pattern RUDE = Pattern.compile(
            "(?iuU)\\b(eier\\s+(lutschen|in\\s+den\\s+mund)|lutsch\\w*|blas\\w*\\s+mir|fick\\w*|arschloch|hure|wichser)\\b");

    /** Gespräch, wenn das Modell nicht antwortet. Immer eine kurze, freundliche, wahre Antwort; sonst leer. */
    public static Optional<String> chat(String raw) {
        return chat(raw, false, 0);
    }

    /**
     * Wie chat(raw), im Ton von Veras Charakter (CompanionCharacter): cheeky = frech, pick wählt unter mehreren
     * Sätzen. Was Vera sachlich sagt (Bauliste, Hilfe), bleibt in beiden Tonarten gleich.
     */
    public static Optional<String> chat(String raw, boolean cheeky, int pick) {
        String t = CompanionChat.stripLead(raw).replaceAll("[!.?]+$", "").trim();
        String full = raw == null ? "" : raw.trim();
        if (RUDE.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.RUDE, cheeky, pick));
        }
        if (BUILD_Q.matcher(full).find()) {
            return Optional.of("Der Baumeister baut Häuser, Dörfer, Iglus, Burgen, Brücken, Pools, Bunker und "
                    + "Hochhäuser – und geprüfte Maschinen: Türen, Fahrstühle, Farmen, Logik, einen Taschenrechner, "
                    + "eine 7-Segment-Anzeige und Prozessor-Bausteine. Sag zum Beispiel „bau mir ein Iglu“.");
        }
        if (HELP.matcher(full).find()) {
            return Optional.of("Ich gebe dir Sachen (im Kreativmodus), finde Erze, bringe dich zu Warps, stelle Tag "
                    + "und Wetter, prüfe und repariere Redstone und baue mit dem Baumeister. Sag „Hilfe“ für das Buch.");
        }
        if (HEAR.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.HEAR, cheeky, pick));
        }
        if (HOW.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.HOW, cheeky, pick));
        }
        if (THANKS.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.THANKS, cheeky, pick));
        }
        if (BYE.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.BYE, cheeky, pick));
        }
        if (GREET.matcher(full).find()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.GREET, cheeky, pick));
        }
        if (YES.matcher(t).matches() || YES.matcher(full.replaceAll("[!.?]+$", "")).matches()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.YES, cheeky, pick));
        }
        if (NO.matcher(t).matches() || NO.matcher(full.replaceAll("[!.?]+$", "")).matches()) {
            return Optional.of(CompanionCharacter.line(CompanionCharacter.Line.NO, cheeky, pick));
        }
        return Optional.empty();
    }

    /** Der einzige nächste Name aus names: höchstens 1 Abweichung, ab 6 Buchstaben 2, ab 9 Buchstaben 3 („Farbenwelt“ → farmwelt = 3); sonst null. */
    public static String nearest(String raw, java.util.Collection<String> names) {
        if (raw == null) {
            return null;
        }
        String n = raw.strip().toLowerCase(Locale.ROOT);
        int limit = n.length() >= 9 ? 3 : n.length() >= 6 ? 2 : 1;
        String best = null;
        int bestD = Integer.MAX_VALUE;
        boolean tie = false;
        for (String c : names) {
            int d = distance(n, c.toLowerCase(Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = c;
                tie = false;
            } else if (d == bestD) {
                tie = true;
            }
        }
        return best != null && bestD <= limit && !tie ? best : null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static Reply reply(String say, Action a) {
        return new Reply(say, List.of(a));
    }
}
