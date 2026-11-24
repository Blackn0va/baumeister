package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abschalten in einzelnen Schritten, jeder mit eigener Fehlergrenze (onDisable ist eine Thread-Grenze).
 *
 * Vorher lief onDisable als eine Kette: Auf dev01 warf companion.shutdown() bei drei Deploys (2026-11-21 11:24,
 * 2026-11-22 19:19, 2026-11-23 19:26) NoClassDefFoundError (CompanionVoicechat$Stream) – das Deploy hatte das Jar unter
 * dem laufenden Server ersetzt, der alte Classloader fand die noch nie geladene Klasse nicht mehr. Alles danach fiel
 * aus: Stadtleben, Tab-Liste, Rucksack- und Quest-Speichern, Weltbau, Stadt, BuildService, Erde. Jetzt läuft jeder
 * Schritt, auch wenn ein früherer wirft; LinkageError wird mitgefangen, weil genau der Fall dieser war.
 *
 * Wer die Fehlergrenze je Schritt wieder entfernt, verliert beim nächsten werfenden Schritt das Speichern aller
 * späteren (ShutdownStepsTest).
 *
 * Threads: Hauptthread (onDisable). Fehler: je Schritt geloggt (WARNING mit Stacktrace), nie weitergereicht.
 */
final class ShutdownSteps {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private record Step(String name, Runnable action) {
    }

    private final List<Step> steps = new ArrayList<>();

    /** Schritt anhängen; null-Aktion (Dienst nie gestartet) wird übersprungen. */
    ShutdownSteps add(String name, Object service, Runnable action) {
        if (service != null) {
            steps.add(new Step(name, action));
        }
        return this;
    }

    /** Alle Schritte in Reihenfolge. Zurück: Namen der Schritte, die geworfen haben (leer = sauber). */
    List<String> run() {
        List<String> failed = new ArrayList<>();
        for (Step s : steps) {
            try {
                s.action().run();
            } catch (RuntimeException | LinkageError e) {
                failed.add(s.name());
                LOG.log(Level.WARNING, "plugin | abschalten_fehler | schritt=" + s.name() + " grund=" + e, e);
            }
        }
        if (!failed.isEmpty()) {
            LOG.warning(() -> "plugin | abschalten_unvollständig | fehler=" + String.join(",", failed) + " schritte="
                    + steps.size());
        }
        return failed;
    }
}
