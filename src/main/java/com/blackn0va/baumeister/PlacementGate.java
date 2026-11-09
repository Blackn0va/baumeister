package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Tier;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Entscheidet beim Setzen, ob eine Aktion kommt (Plan B1/B6). Ohne Bukkit, damit die Regeln testbar sind.
 *
 * Je Zelle zählt die höhere Stufe aus „was hier hin soll“ (BlockTiers.place, beim Umrechnen bestimmt) und „was hier
 * gerade steht“ (BlockTiers.removal, im Moment der Entscheidung gelesen – Luft über Grundgestein ist kein Freibrief).
 * Eine Aktion (Paar wie Tür oder Bett) kommt ganz oder gar nicht. Ein Bauteil (Gruppe ≠ 0) ebenso: Beim ersten
 * Block des Bauteils wird über ALLE seine Zellen entschieden und das Ergebnis behalten – sonst stünde eine halbe
 * Maschine da, wenn eine einzige Zelle eine höhere Stufe braucht.
 *
 * Jede Aktion prüft zusätzlich IMMER ihre eigenen Zellen und JEDES Bauteil, das in ihr vorkommt (Sicherheitsprüfung
 * 2026-11-08, Befund 1): Vorher zählte nur die Gruppe der ersten Zelle. Ein Paar aus einer Bauteil-Hälfte (Truhe
 * links, geprüft) und einer Plan-Hälfte (Truhe rechts mit Spawn-Eiern, Gruppe 0) kam so ungeprüft durch, ebenso eine
 * Tür-Oberhälfte über Grundgestein. Wer das wieder auf die erste Zelle verkürzt, öffnet genau diesen Weg.
 *
 * Die Rechte holt jede Entscheidung frisch (rights.get()), nie beim Auftrag: wer zwischen Warteschlange und Dranwerden
 * den Kreativmodus verlässt, bekommt keine Inhalte mehr. Wer das wieder beim Auftrag liest, öffnet diese Lücke. Eine
 * behaltene JA-Entscheidung über ein Bauteil gilt nur, solange die Rechte dieselben sind (Befund 7): Ändern sie sich,
 * wird neu entschieden. Ein NEIN bleibt NEIN – sonst stünde eine halbe Maschine da, wenn jemand mitten im Bauteil
 * Rechte bekommt.
 *
 * Threads: nur Hauptthread (BuildJob). Fehler: keine.
 */
final class PlacementGate {

    /** Eine Zelle: Stufe des neuen Blocks und (beim Entscheiden gelesen) des Blocks, der dort steht. */
    interface Cell {

        Tier placeTier();

        String placeReason();

        /** Stufe zum Überschreiben des Blocks, der JETZT an der Stelle steht. */
        Tier removalTier();

        String removalReason();

        /** Bauteil der Zelle; 0 = keins. */
        default int group() {
            return 0;
        }
    }

    /** allowed = true: setzen. Sonst die fehlende Stufe und der Grund (deutsch). */
    record Decision(boolean allowed, Tier missing, String reason) {

        static final Decision YES = new Decision(true, Tier.EVERYONE, "");
    }

    /** Behaltene Entscheidung über ein Bauteil samt den Rechten, unter denen sie fiel. */
    private record Kept(BuildRights rights, Decision decision) {
    }

    private final Supplier<BuildRights> rights;
    private final Map<Integer, Kept> groups = new HashMap<>();

    PlacementGate(Supplier<BuildRights> rights) {
        this.rights = rights;
    }

    /**
     * Entscheidung für eine Aktion: ihre eigenen Zellen mit den Rechten dieses Moments, dazu jedes Bauteil, das in ihr
     * vorkommt, über alle seine Zellen (wholeGroup) – beim ersten Mal, danach behalten (siehe Klassenkommentar).
     */
    Decision action(List<? extends Cell> cells, IntFunction<List<? extends Cell>> wholeGroup) {
        BuildRights now = rights.get();
        Decision own = decide(cells, now);
        if (!own.allowed()) {
            // Eine Zelle eines Bauteils fällt weg: Dann fällt der Rest des Bauteils auch weg (ganz oder gar nicht),
            // auch wenn die Rechte später zurückkommen.
            for (Cell c : cells) {
                if (c.group() != 0) {
                    groups.put(c.group(), new Kept(now, own));
                }
            }
            return own;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (Cell c : cells) {
            if (c.group() != 0 && seen.add(c.group())) {
                Decision d = group(c.group(), now, wholeGroup);
                if (!d.allowed()) {
                    return d;
                }
            }
        }
        return Decision.YES;
    }

    /** Wurde über das Bauteil schon entschieden (und wie)? null = noch nicht. */
    Decision groupDecision(int group) {
        Kept k = groups.get(group);
        return k == null ? null : k.decision();
    }

    private Decision group(int g, BuildRights now, IntFunction<List<? extends Cell>> wholeGroup) {
        Kept k = groups.get(g);
        if (k != null && (!k.decision().allowed() || k.rights().equals(now))) {
            return k.decision();
        }
        Decision d = decide(wholeGroup.apply(g), now);
        groups.put(g, new Kept(now, d));
        return d;
    }

    private static Decision decide(List<? extends Cell> cells, BuildRights r) {
        for (Cell c : cells) {
            if (!r.allows(c.placeTier())) {
                return new Decision(false, c.placeTier(), c.placeReason());
            }
            Tier removal = c.removalTier();
            if (!r.allows(removal)) {
                return new Decision(false, removal, c.removalReason());
            }
        }
        return Decision.YES;
    }
}
