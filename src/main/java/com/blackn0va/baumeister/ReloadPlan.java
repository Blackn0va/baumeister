package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Entscheidet bei /bm neuladen, was sofort gilt, was bis nach dem laufenden Bau wartet und was erst ein Neustart
 * übernimmt – ohne Bukkit, damit es testbar ist.
 *
 * Warum: Vorher ersetzte /bm neuladen immer den ganzen BuildService und verweigerte sich, solange irgendetwas lief
 * (Bau, Stadtbau, Weltbau-Kachel). Zwei Folgen: Wer während eines stundenlangen Stadtbaus die Overpass-Liste
 * änderte, musste warten oder abbrechen; und der neue BuildService fing mit leerem Rückgängig-Stapel und leerem
 * Gedächtnis an – nach „neuladen“ ließ sich der letzte Bau (bis zu undo-tiefe Bauten samt Stadt) nicht mehr
 * zurückdrehen, ohne dass es jemand sagte. Jetzt wird der BuildService nur ersetzt, wenn sich seine Einstellungen
 * (Settings) wirklich ändern, nie während eines Auftrags, und mit vollem Rückgängig-Stapel nur auf „erzwingen“.
 * Wer das zurückdreht, löscht beim Ändern einer Overpass-Adresse den Rückgängig-Stapel aller Spieler.
 *
 * Wann was gilt (gelesen im Code, 2026-10-29):
 * - npc/llm/bau/helfer/pläne-speichern → Settings, fest im BuildService und LlmClient → nur durch Ersetzen.
 * - stadt.overpass-urls, stadt.nominatim-url → CityService.reload() baut den OsmClient neu, laufende Abfragen
 *   behalten den alten.
 * - stadt.overpass-url (alte Einzeladresse) → wirkt wie MAP_SERVICES, solange die Datei keine eigene, nicht leere
 *   stadt.overpass-urls nennt (CityService.overpassUrls, 2026-11-01). Bis dahin las CityService die Liste samt
 *   JAR-Vorgaben, die Einzeladresse wirkte nie, und neuladen meldete „wirkt nicht“ (Prüfbefund 2026-10-29).
 * - übrige stadt.* und weltbau.* → bei jedem Stadtbau-Start bzw. jeder Kachel frisch gelesen; ein laufender Stadtbau
 *   behält seine Werte (dort /bm tempo, /bm helfer).
 * - weltbau.auto, weltbau.abstand-sekunden → nur im Konstruktor von WorldBuildService.
 * - weltbau.rückgängig-tiefe → im Konstruktor UND nach jeder fertigen Kachel (onJobFinished: undo.depth(…)), gilt
 *   also ab der nächsten fertigen Kachel. Vorher stand sie in RESTART_ONLY; neuladen und /bm leistung mahnten einen
 *   Neustart an, der nichts änderte (Prüfbefund 2026-10-29).
 * - erde.* → nur beim Start von EarthService; erde.projektion ist ohnehin in erde/welt.json festgeschrieben.
 *
 * Threads: beliebig (reine Rechnung). Fehler: keine.
 */
final class ReloadPlan {

    /** Schlüssel, die erst ein Neustart übernimmt. */
    static final Set<String> RESTART_ONLY = Set.of("weltbau.auto", "weltbau.abstand-sekunden");
    static final Set<String> MAP_SERVICES = Set.of("stadt.overpass-urls", "stadt.nominatim-url");
    /** Alte Einzeladresse: wirkt, solange die Datei keine eigene stadt.overpass-urls nennt (siehe Klassenkommentar). */
    static final String OVERPASS_SINGLE = "stadt.overpass-url";
    /** Wird nach jeder fertigen Weltbau-Kachel neu gelesen. */
    static final String WORLD_UNDO_DEPTH = "weltbau.rückgängig-tiefe";
    /** Vom Plugin selbst gepflegt (/bm spawn), kein Admin-Wert. */
    static final String NPC_UUID = "npc.uuid";

    /** Was der Aufrufer gerade weiß. undoPresent = /bm rückgängig hätte etwas zurückzudrehen. */
    record Input(Settings active, Settings fresh, Map<String, Object> before, Map<String, Object> after,
            boolean buildBusy, boolean undoPresent, boolean force, boolean cityRunning, boolean worldBuildRunning) {
    }

    /**
     * @param replaceBuildService BuildService (mit Settings, LLM, NPC-Name) neu anlegen
     * @param reloadModules       Bauteile von der Platte neu lesen, ohne den Dienst zu ersetzen
     * @param lines               Antwort an den Admin, je Zeile ein Satz
     * @param restartKeys         geänderte Schlüssel, die erst nach einem Neustart wirken
     * @param changed             alle geänderten Schlüssel (für das Log)
     */
    record Outcome(boolean replaceBuildService, boolean reloadModules, List<String> lines, Set<String> restartKeys,
            Set<String> changed) {
    }

    private ReloadPlan() {
    }

    static Outcome decide(Input in) {
        Set<String> changed = changedKeys(in.before(), in.after());
        changed.remove(NPC_UUID);
        List<String> lines = new ArrayList<>();
        Set<String> restart = new TreeSet<>();

        boolean replace = false;
        Set<String> buildKeys = pick(changed, ReloadPlan::isBuildKey);
        if (!in.active().equals(in.fresh())) {
            String which = buildKeys.isEmpty() ? "" : " (" + String.join(", ", buildKeys) + ")";
            if (in.buildBusy()) {
                lines.add("Bau- und LLM-Einstellungen" + which + " warten: Der laufende Auftrag behält die alten. "
                        + "Nach dem Bau /bm neuladen noch einmal.");
            } else if (in.undoPresent() && !in.force()) {
                lines.add("Bau- und LLM-Einstellungen" + which + " NICHT übernommen: Dafür fängt der Baumeister "
                        + "neu an, und der Rückgängig-Stapel wäre weg. Erst /bm rückgängig, oder "
                        + "/bm neuladen erzwingen.");
            } else {
                replace = true;
                // Der neue BuildService fängt immer mit leerem Gedächtnis an (Nachträge der letzten Stunde) – auch
                // ohne Rückgängig-Stapel. Vorher hieß es dann nur „übernommen.“ (Prüfbefund 2026-10-29).
                lines.add("Bau- und LLM-Einstellungen übernommen" + which + (in.undoPresent()
                        ? " – Rückgängig-Stapel, Gedächtnis und Nachträge sind geleert."
                        : " – Gedächtnis und Nachträge fangen neu an."));
            }
        }

        Set<String> maps = pick(changed, MAP_SERVICES::contains);
        if (changed.contains(OVERPASS_SINGLE)) {
            // Seit CityService.overpassUrls (2026-11-01) gilt die Einzeladresse, solange die Datei keine eigene Liste
            // nennt. after mischt die Vorlage ein: Eine Liste gleich der Vorgabe heißt „nicht in der Datei“. Nennt die
            // Datei genau die Vorgabe-Liste selbst, stimmt die Meldung nicht – ConfigCheck sagt dann „ignoriert“.
            if (in.after().get("stadt.overpass-urls") instanceof List<?> l
                    && (l.isEmpty() || l.equals(CityService.OVERPASS_DEFAULT))) {
                maps.add(OVERPASS_SINGLE);
            } else {
                lines.add(OVERPASS_SINGLE + " geändert – wirkt nicht: Die Datei nennt eine eigene Liste "
                        + "stadt.overpass-urls, die hat Vorrang. Eigene Instanz dort eintragen.");
            }
        }
        if (!maps.isEmpty()) {
            lines.add("Kartendienste übernommen (" + String.join(", ", maps) + "); laufende Abfragen enden noch "
                    + "beim alten Dienst.");
        }

        // Ohne den Ausschluss der Einzeladresse stünde sie zugleich als „wirkt nicht“ und als „Stadtbau … übernommen“
        // in der Antwort (ReloadPlanTest.ownOverpassUrlBesideOwnListIsNotReportedAsTakenOver).
        Set<String> city = pick(changed, k -> k.startsWith("stadt.") && !MAP_SERVICES.contains(k)
                && !k.equals(OVERPASS_SINGLE));
        if (!city.isEmpty()) {
            lines.add("Stadtbau: " + String.join(", ", city) + (in.cityRunning() || in.worldBuildRunning()
                    ? " – gilt ab dem nächsten Stadtbau bzw. der nächsten Weltbau-Kachel; im Lauf: /bm tempo, "
                            + "/bm helfer."
                    : " – übernommen."));
        }

        Set<String> world = pick(changed, k -> k.startsWith("weltbau.") && !RESTART_ONLY.contains(k)
                && !k.equals(WORLD_UNDO_DEPTH));
        if (!world.isEmpty()) {
            lines.add("Weltbau: " + String.join(", ", world) + (in.worldBuildRunning()
                    ? " – gilt ab der nächsten Kachel." : " – übernommen."));
        }
        if (changed.contains(WORLD_UNDO_DEPTH)) {
            lines.add("Weltbau: " + WORLD_UNDO_DEPTH + " – gilt ab der nächsten fertigen Kachel.");
        }

        // Stadtleben (2026-11-21): wirkt sofort (TownLife.reload). Vorher fehlte der Zweig – „anzahl: 12“ meldete
        // „Keine Änderung“ und blieb bei 4 (Live-Probe T-020).
        Set<String> town = pick(changed, k -> k.startsWith("stadtleben."));
        if (!town.isEmpty()) {
            lines.add("Stadtleben: " + String.join(", ", town) + " – übernommen.");
        }

        for (String k : changed) {
            if (needsRestart(k)) {
                restart.add(k);
            }
        }
        if (!restart.isEmpty()) {
            lines.add("Erst nach einem Neustart: " + String.join(", ", restart)
                    + (restart.contains("weltbau.auto") ? " (die Automatik sofort: /bm weltbau auto an|aus)" : "")
                    + (restart.contains("erde.projektion")
                            ? "; erde.projektion gilt nie mehr – sie steht in erde/welt.json fest" : "")
                    + ".");
        }

        if (lines.isEmpty()) {
            lines.add("Keine Änderung gegenüber den aktiven Einstellungen.");
        }
        // Bauteile (WorldEdit-Schematics) liest die Bibliothek beim Neuladen frisch von der Platte. Mit neuem
        // BuildService geschieht das ohnehin; ohne nur im Leerlauf – ein Plan in Arbeit soll seine Bauteile behalten.
        boolean modules = !replace && !in.buildBusy();
        return new Outcome(replace, modules, lines, restart, changed);
    }

    static boolean needsRestart(String key) {
        return RESTART_ONLY.contains(key) || key.startsWith("erde.");
    }

    /** Neustart-Schlüssel, deren Wert jetzt anders ist als beim Start des Servers. */
    static Set<String> restartPending(Map<String, Object> atStart, Map<String, Object> now) {
        return pick(changedKeys(atStart, now), ReloadPlan::needsRestart);
    }

    static boolean isBuildKey(String key) {
        return key.startsWith("npc.") || key.startsWith("llm.") || key.startsWith("bau.")
                || key.startsWith("helfer.") || key.equals("pläne-speichern");
    }

    /** Schlüssel, deren Wert sich unterscheidet – auch neu dazugekommene und entfernte. */
    static Set<String> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> all = new LinkedHashSet<>(before.keySet());
        all.addAll(after.keySet());
        Set<String> out = new TreeSet<>();
        for (String k : all) {
            if (!Objects.equals(before.get(k), after.get(k))) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<String> pick(Set<String> keys, Predicate<String> p) {
        Set<String> out = new TreeSet<>();
        for (String k : keys) {
            if (p.test(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
