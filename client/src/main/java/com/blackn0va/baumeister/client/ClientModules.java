package com.blackn0va.baumeister.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Die eigenen Funktionen der Mod als Module mit an/aus (AK-39, 2026-11-22) – eine Schicht über ClientSettings, keine
 * neue Quelle: Jeder Schalter liest und schreibt genau ein bestehendes Feld (reportResults gab es schon, die anderen
 * drei sind neu). Wer hier eine eigene Modul-Datei anlegt, hat zwei Stände, die auseinanderlaufen.
 *
 * Nicht schaltbar sind das Bauen selbst (ohne das tut die Mod nichts) und der Vorrang deiner Eingaben (Control) – den
 * abzuschalten hieße, dass die Mod gegen dich weiterbaut.
 *
 * Threads: Client-Thread (Bildschirm, Befehl). Fehler: Speichern scheitert nur mit Logzeile (ClientSettings.save) –
 * der Schalter wirkt dann bis zum Neustart.
 */
final class ClientModules {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");

    /**
     * Ein Modul der Mod. nameKey/descriptionKey sind Sprachschlüssel (assets/baumeister-client/lang). Alle vier wirken
     * sofort, weil ihre Stellen das Feld bei jedem Aufruf neu lesen (Auftrag, HUD-Bild, Tick, Meldung) – wer eines in
     * einem Konstruktor einfriert, braucht wieder einen Neustart.
     */
    record Module(String id, String nameKey, String descriptionKey, Predicate<ClientSettings> on,
            BiConsumer<ClientSettings, Boolean> set) {
    }

    static final List<Module> ALL = List.of(
            // id „nachtrag“, Anzeige „Gedächtnis“: ids sind ASCII ([a-z0-9-] wie im Plugin), ohne Umlaut-Umschrift.
            new Module("nachtrag", "baumeister-client.module.nachtrag",
                    "baumeister-client.module.nachtrag.desc", s -> s.followUps, (s, v) -> s.followUps = v),
            new Module("statuszeile", "baumeister-client.module.statuszeile",
                    "baumeister-client.module.statuszeile.desc", s -> s.hudStatus, (s, v) -> s.hudStatus = v),
            new Module("aktionsleiste", "baumeister-client.module.aktionsleiste",
                    "baumeister-client.module.aktionsleiste.desc", s -> s.progressBar, (s, v) -> s.progressBar = v),
            new Module("ergebnis-melden", "baumeister-client.module.ergebnis-melden",
                    "baumeister-client.module.ergebnis-melden.desc", s -> s.reportResults,
                    (s, v) -> s.reportResults = v));

    private ClientModules() {
    }

    static Optional<Module> byId(String id) {
        return ALL.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /**
     * Schaltet das Modul um und speichert sofort, nicht erst beim Schließen des Bildschirms: Ein Absturz oder Alt+F4
     * verlöre sonst den Schalter. Ohne das save hier fällt ClientModulesTest rot (Gegenprobe 2026-11-22).
     *
     * @return neuer Zustand
     */
    static boolean toggle(ClientSettings settings, Module module, Path file) {
        boolean now = !module.on().test(settings);
        module.set().accept(settings, now);
        settings.save(file);
        LOG.info(() -> "module | geschaltet | id=" + module.id() + " an=" + now);
        return now;
    }
}
