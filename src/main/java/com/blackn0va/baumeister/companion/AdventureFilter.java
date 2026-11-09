package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Was vom Modell im Abenteuer übrig bleibt, BEVOR Vera spricht.
 *
 * Vorfall dev01 2026-11-04 22:24: „Tageslicht“ im Abenteuer → Modell: „Es wird Zeit für das Tageslicht.“ plus
 * {"op":"command","text":"time set day"}. Vera sagte den Satz, dann lehnte das Plugin den Befehl ab („Im Abenteuer gibt
 * es keine Befehle …“) – zwei Sätze, die sich widersprechen, im Voice-Chat sogar gleichzeitig („ich höre zwei
 * Stimmen“). Ebenso „Wo finde ich Diamant?“ → find diamond_ore → „Erze verrate ich nicht“. Jetzt fliegen verbotene
 * Aktionen vorher raus; blieb keine übrig, sagt Vera statt des Versprechens, warum, und bei Erzen die Höhe.
 *
 * Erlaubt bleibt alles, was nicht abkürzt: Rechnen, Rezepte, Lieder, Uhrzeit, Orte merken, goto (zeigt im Abenteuer nur
 * den Weg), Suche nach Nicht-Erzen. Verboten: give, command, build; find auf Erze.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class AdventureFilter {

    /** Häufigste Höhe je Erz (Erzverteilung seit 1.18); Smaragd nur in Bergen, antiker Schutt im Nether. */
    static final Map<String, Integer> ORE_Y = Map.ofEntries(Map.entry("coal", 96), Map.entry("copper", 48),
            Map.entry("iron", 16), Map.entry("gold", -16), Map.entry("lapis", 0), Map.entry("redstone", -58),
            Map.entry("diamond", -58), Map.entry("emerald", 236), Map.entry("ancient_debris", 15),
            Map.entry("quartz", 64));

    static final Map<String, String> ORE_NAME = Map.ofEntries(Map.entry("coal", "Kohle"), Map.entry("copper", "Kupfer"),
            Map.entry("iron", "Eisen"), Map.entry("gold", "Gold"), Map.entry("lapis", "Lapislazuli"),
            Map.entry("redstone", "Redstone"), Map.entry("diamond", "Diamanten"), Map.entry("emerald", "Smaragde"),
            Map.entry("ancient_debris", "Antiker Schutt"), Map.entry("quartz", "Quarz"));

    /** kept: auszuführen; blocked: wie viele verboten waren; ore: Erzschlüssel ("diamond"), wenn nach Erz gefragt. */
    public record Result(List<CompanionReply.Action> kept, int blocked, String ore) {
    }

    private AdventureFilter() {
    }

    public static Result filter(List<CompanionReply.Action> actions) {
        List<CompanionReply.Action> kept = new ArrayList<>();
        int blocked = 0;
        String ore = null;
        for (CompanionReply.Action a : actions) {
            switch (a.op()) {
                case GIVE, COMMAND, BUILD -> blocked++;
                case FIND -> {
                    Optional<String> o = ore(a.block());
                    if (o.isPresent()) {
                        blocked++;
                        ore = o.get();
                    } else {
                        kept.add(a);
                    }
                }
                default -> kept.add(a);
            }
        }
        return new Result(List.copyOf(kept), blocked, ore);
    }

    /** "deepslate_diamond_ore" → "diamond", "ancient_debris" → "ancient_debris", sonst leer. */
    static Optional<String> ore(String block) {
        String b = block == null ? "" : block.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (b.equals("ancient_debris")) {
            return Optional.of("ancient_debris");
        }
        if (!CompanionRules.isOre(b)) {
            return Optional.empty();
        }
        String core = b.replace("deepslate_", "").replace("nether_", "").replace("raw_", "").replace("_ore", "")
                .replace("_block", "");
        return ORE_Y.containsKey(core) ? Optional.of(core) : Optional.empty();
    }

    /** Was Vera statt einer Erzsuche sagt: die Höhe, nicht die Stelle. */
    public static String oreHint(String ore, int playerY) {
        int y = ORE_Y.get(ore);
        String name = ORE_NAME.get(ore);
        if (ore.equals("emerald")) {
            return "Smaragde gibt es nur in Bergen, hoch oben im Stein. Erze zeige ich dir nicht – such selbst!";
        }
        if (ore.equals("ancient_debris") || ore.equals("quartz")) {
            return name + " liegt im Nether" + (ore.equals("ancient_debris") ? ", um Höhe 15" : "")
                    + ". Erze zeige ich dir nicht – such selbst!";
        }
        int down = playerY - y;
        // Mehrzahl braucht „liegen“: „Diamanten liegt …“ stand im ersten Test (2026-11-04).
        String verb = ore.equals("diamond") ? " liegen" : " liegt";
        String where = Math.abs(down) <= 4 ? "Du bist genau richtig"
                : down > 0 ? "Du bist auf Höhe " + playerY + ", also noch " + down + " Blöcke nach unten"
                        : "Du bist auf Höhe " + playerY + ", also " + -down + " Blöcke höher";
        return name + verb + " am meisten um Höhe " + y + ". " + where + ". Die Stelle verrate ich nicht – das wäre "
                + "geschummelt.";
    }
}
