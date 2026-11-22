package com.blackn0va.baumeister.module;

import com.blackn0va.baumeister.module.ModuleDef.Effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Das Modulverzeichnis (Vertrag v012, AK-37): jede Funktion des Plugins als benanntes Modul mit Schalter, Zustand und
 * Grund – an EINER Stelle, für /bm module, das Kistenmenü, die Host-App und die Mod.
 *
 * Bewusst eine Schicht über der Fachlogik: Das Verzeichnis liest nur Fakten (Facts, gesammelt von ModuleService im
 * Hauptpaket) und rechnet daraus den Zustand. Kein Dienst wurde dafür umgebaut. Wer hier einen Zustand ändert, ändert
 * nur die Anzeige; ob eine Funktion läuft, entscheidet weiter ihr eigener Schlüssel und ihr eigener Code.
 *
 * Die Liste ist das, was es im Plugin wirklich gibt (gelesen in config.yml, plugin.yml und BaumeisterPlugin.onEnable,
 * 2026-11-22). Reihenfolge = Anzeige-Reihenfolge: zuerst die KI-Funktionen, dann Welt, dann Alltag, dann Technik.
 *
 * Threads: beliebig (reine Rechnung). Fehler: keine.
 */
public final class ModuleDirectory {

    /** Grund, wenn das Stadtleben ohne KI läuft (stadtleben.ohne-ki) – so vom Dirigenten für Host und Mod festgelegt. */
    public static final String WITHOUT_KI_REASON = "ohne KI, feste Sätze";

    /** Bedarf „ki“: lokales Sprachmodell über llm.url. */
    public static final String NEED_KI = "ki";

    public static final List<ModuleDef> ALL = List.of(
            def("bau-ki", "Bau-KI", "Baupläne vom lokalen Sprachmodell: „Baumeister, bau mir …“, Vorschau, "
                    + "Warteschlange und Rückgängig.", null, Effect.SOFORT, NEED_KI),
            def("begleiter", "Vera", "Begleiterin für jeden Spieler: „Hey Vera“ – folgt, antwortet, rechnet, sucht "
                    + "Blöcke und gibt Gegenstände.", "begleiter.an", Effect.NEUSTART, NEED_KI),
            def("abenteuer", "Abenteuer mit Vera", "Vera spielt Minecraft mit dir durch – vom ersten Holz bis zum "
                    + "Enderdrachen, normal oder Hardcore.", null, Effect.SOFORT, "begleiter"),
            def("stadtleben", "Stadtleben", "KI-Bewohner laufen tagsüber nahe den Spielern ihren Tagesablauf, "
                    + "grüßen und reden; ohne KI nur mit stadtleben.ohne-ki, dann mit festen Sätzen.", "stadtleben.an", Effect.SOFORT, NEED_KI),
            def("stadt", "Städte aus OpenStreetMap", "Echte Orte 1:1 aus OpenStreetMap bauen: /bm stadt <Ort>.",
                    null, Effect.SOFORT, "netz"),
            def("erde", "Erde 1:1", "Die ganze Erde als eine Welt, die beim Erkunden entsteht: /bm erde, /bm reise.",
                    null, Effect.SOFORT),
            def("weltbau", "Weltbau", "OpenStreetMap Kachel für Kachel auf das Gelände der Erdwelt.", null,
                    Effect.SOFORT, "erde", "netz"),
            def("karte", "Minikarte", "Karte in der Hand, die mitwandert: Plotgrenze, Baustelle, Baumeister und "
                    + "Helfer.", "karte.an", Effect.NEUSTART),
            def("rucksack", "Rucksack", "Kiste im Inventar mit sehr viel Platz, gespeichert auf dem Server: "
                    + "/rucksack.", "rucksack.an", Effect.NEUSTART),
            def("sprache", "Sprache vom Server", "Baumeister und Vera sprechen mit Piper-Stimmen vom Server, ohne "
                    + "Baumeister-App.", "sprache.an", Effect.SOFORT, "piper"),
            def("voicechat", "Simple Voice Chat", "Vera im Spiel hören und mit ihr sprechen – braucht das Plugin "
                    + "voicechat.", null, Effect.SOFORT, "voicechat"),
            def("aufgaben", "Tagesaufgaben", "Drei Aufgaben je Tag mit Punkten, Serie und Rangliste: /aufgaben.",
                    "aufgaben.an", Effect.NEUSTART),
            def("warp", "Warps", "Sprungpunkte für alle: /warp, /warps, /setwarp, /delwarp.", "warp.an",
                    Effect.NEUSTART),
            def("portale", "Portale", "Obsidianrahmen zu Warp, Welt oder Spawn: /bm portal.", null, Effect.SOFORT),
            def("hilfe", "Hilfebuch", "Hilfebuch für alle: /hilfe, beim ersten Beitreten einmal von selbst.", null,
                    Effect.SOFORT),
            def("bauteile", "Bauteile", "WorldEdit-Auswahl als Bauteil speichern, das die Bau-KI verwenden kann.",
                    null, Effect.SOFORT, "worldedit"),
            def("reparatur", "Reparatur und Kisten-Auskunft", "Vera setzt Creeper- und Feuerschäden zurück und sagt, "
                    + "wer an einer Kiste war – braucht CoreProtect.", null, Effect.SOFORT, "coreprotect"),
            def("mods-download", "Mods-Download", "/mods gibt jedem Spieler einen eigenen Download-Link auf das "
                    + "Modpaket vom Server.", "mods.download.an", Effect.NEUSTART),
            def("fernverbindung", "Fernverbindung", "Die Baumeister-App kommt über das Relay auf den Server und "
                    + "spricht für eingetragene Spieler.", null, Effect.SOFORT),
            def("host-anzeige", "Anzeige in der App", "Stadt- und Weltbau als Karte und Auftragsergebnisse in der "
                    + "Baumeister-App.", "gui.stadt-anzeige", Effect.NEUSTART));

    /**
     * Was ModuleService beim Rechnen weiß. config/atStart: Wert jedes Modulschlüssels jetzt bzw. beim Serverstart
     * (Unterschied = Schalter wartet auf den Neustart). llmUp null = noch nicht geprüft. plugins in Kleinbuchstaben.
     * townWithoutKi = stadtleben.ohne-ki: Bewohner auch ohne KI, dann mit festen Sätzen.
     */
    public record Facts(Map<String, Boolean> config, Map<String, Boolean> atStart, Boolean llmUp,
            boolean townKiAnswered, boolean townKiUp, boolean earthWorld, Set<String> plugins, boolean voiceRunning,
            String voiceProblem, boolean modDownloadsRunning, boolean hostDisplayRunning, boolean cityRunning,
            boolean worldBuildAuto, int remoteApps, boolean townWithoutKi) {

        public Facts {
            config = Map.copyOf(config);
            atStart = Map.copyOf(atStart);
            plugins = Set.copyOf(plugins);
        }

        boolean on(String key) {
            return Boolean.TRUE.equals(config.get(key));
        }

        /** Läuft gerade mit diesem Wert (beim Start gelesen); fehlt der Startwert, gilt der jetzige. */
        boolean running(ModuleDef d) {
            if (d.key() == null) {
                return true;
            }
            if (d.effect() == Effect.SOFORT || !atStart.containsKey(d.key())) {
                return on(d.key());
            }
            return Boolean.TRUE.equals(atStart.get(d.key()));
        }
    }

    private ModuleDirectory() {
    }

    private static ModuleDef def(String id, String name, String description, String key, Effect effect,
            String... needs) {
        return new ModuleDef(id, name, description, key, effect, List.of(needs));
    }

    /** Modul nach id (Groß-/Kleinschreibung egal). */
    public static Optional<ModuleDef> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String want = id.strip().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(d -> d.id().equals(want)).findFirst();
    }

    /** Alle Module mit Zustand. */
    public static List<ModuleEntry> evaluate(Facts f) {
        List<ModuleEntry> out = new ArrayList<>();
        for (ModuleDef d : ALL) {
            out.add(evaluate(d, f));
        }
        return out;
    }

    static ModuleEntry evaluate(ModuleDef d, Facts f) {
        boolean on = d.key() == null || f.on(d.key());
        // Schalter geändert, aber erst ein Neustart übernimmt ihn: Das ist der häufigste „warum geht das nicht?“-Fall,
        // deshalb vor allem anderen.
        if (d.key() != null && d.effect() == Effect.NEUSTART && f.atStart().containsKey(d.key())
                && f.on(d.key()) != f.running(d)) {
            return new ModuleEntry(d, on, ModuleState.WARTET, on ? "an nach dem nächsten Neustart"
                    : "läuft noch, aus nach dem nächsten Neustart");
        }
        if (!on) {
            return new ModuleEntry(d, false, ModuleState.AUS, "ausgeschaltet (" + d.key() + ")");
        }
        return switch (d.id()) {
            case "bau-ki" -> f.llmUp() == null ? entry(d, ModuleState.WARTET, "KI noch nicht geprüft")
                    : f.llmUp() ? entry(d, ModuleState.AKTIV, "")
                    : entry(d, ModuleState.FEHLER, "KI nicht erreichbar (llm.url)");
            case "begleiter" -> kiWait(d, f.llmUp());
            case "abenteuer" -> f.running(ModuleDirectory.find("begleiter").orElseThrow())
                    ? entry(d, ModuleState.AKTIV, "") : entry(d, ModuleState.WARTET, "braucht Vera (begleiter.an)");
            // Ohne KI, aber ohne-ki erlaubt: läuft – als „aktiv“ mit Grund, kein eigener Zustand (Host T-031 und Mod
            // T-032 nehmen nur aktiv|aus|wartet|fehler an, Dirigent 2026-11-22).
            case "stadtleben" -> f.townWithoutKi() && !(f.townKiAnswered() && f.townKiUp())
                    ? entry(d, ModuleState.AKTIV, WITHOUT_KI_REASON)
                    : !f.townKiAnswered() ? entry(d, ModuleState.WARTET, "KI noch nicht geprüft")
                    : f.townKiUp() ? entry(d, ModuleState.AKTIV, "Bewohner kommen tagsüber")
                    : entry(d, ModuleState.WARTET, "wartet auf KI – ohne KI keine Bewohner");
            case "stadt" -> entry(d, ModuleState.AKTIV, f.cityRunning() ? "baut gerade" : "");
            case "erde" -> f.earthWorld() ? entry(d, ModuleState.AKTIV, "")
                    : entry(d, ModuleState.WARTET, "Erdwelt fehlt – /bm gelaende einrichten, dann Neustart");
            case "weltbau" -> !f.earthWorld() ? entry(d, ModuleState.WARTET, "braucht die Erdwelt")
                    : entry(d, ModuleState.AKTIV, f.worldBuildAuto() ? "Automatik an" : "");
            case "sprache" -> f.voiceRunning() ? entry(d, ModuleState.AKTIV, "")
                    : entry(d, ModuleState.FEHLER, f.voiceProblem() == null || f.voiceProblem().isBlank()
                            ? "Stimme startet nicht (Log)" : f.voiceProblem());
            case "voicechat" -> plugin(d, f, "voicechat", "Plugin voicechat fehlt");
            case "bauteile" -> plugin(d, f, "worldedit", "Plugin WorldEdit fehlt");
            case "reparatur" -> plugin(d, f, "coreprotect", "Plugin CoreProtect fehlt");
            case "mods-download" -> f.modDownloadsRunning() ? entry(d, ModuleState.AKTIV, "")
                    : entry(d, ModuleState.FEHLER, "Download-Seite läuft nicht – Adresse, Port, Zertifikat (Log)");
            case "host-anzeige" -> f.hostDisplayRunning() ? entry(d, ModuleState.AKTIV, "")
                    : entry(d, ModuleState.FEHLER, "keine gültige Adresse (gui.url, llm.url)");
            case "fernverbindung" -> entry(d, ModuleState.AKTIV, f.remoteApps() == 0
                    ? "keine App eingetragen – jede App spricht für den Spieler, den sie nennt"
                    : f.remoteApps() + (f.remoteApps() == 1 ? " App eingetragen" : " Apps eingetragen"));
            default -> entry(d, ModuleState.AKTIV, "");
        };
    }

    private static ModuleEntry kiWait(ModuleDef d, Boolean llmUp) {
        if (llmUp == null) {
            return entry(d, ModuleState.WARTET, "KI noch nicht geprüft");
        }
        return llmUp ? entry(d, ModuleState.AKTIV, "") : entry(d, ModuleState.WARTET, "wartet auf KI");
    }

    private static ModuleEntry plugin(ModuleDef d, Facts f, String plugin, String missing) {
        return f.plugins().contains(plugin) ? entry(d, ModuleState.AKTIV, "") : entry(d, ModuleState.WARTET, missing);
    }

    private static ModuleEntry entry(ModuleDef d, ModuleState s, String reason) {
        return new ModuleEntry(d, true, s, reason);
    }
}
