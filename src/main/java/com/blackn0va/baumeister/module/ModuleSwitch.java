package com.blackn0va.baumeister.module;

import java.util.List;
import java.util.Optional;

/**
 * /bm module an|aus &lt;id&gt; ohne Bukkit, damit Rechte und Ablauf ohne Server prüfbar sind.
 *
 * Reihenfolge mit Absicht: das Recht ZUERST, vor jeder Auskunft über die id (ASVS V4.1.1/V4.1.3, OWASP A01;
 * fail-closed wie BaumeisterCommand.townLife). Wer die Prüfung hinter find() schiebt, verrät Nicht-Admins zwar nur
 * Modulnamen – wer sie ganz entfernt, lässt jeden mit /bm-Zugang Stadtleben, Vera oder den Mods-Download schalten
 * (ModuleSwitchTest.withoutAdminNothingIsWritten).
 *
 * Geschrieben wird nur der bestehende Schlüssel aus ModuleDef.key (Vertrag v012, non_goals); ob er sofort wirkt,
 * entscheidet Target.applyLive – nur Module mit Effect.SOFORT werden dort gefragt.
 *
 * Threads: Hauptthread (Target schreibt config.yml). Fehler: keine – jede Absage ist eine Zeile.
 */
public final class ModuleSwitch {

    /** Recht für das Schalten – dasselbe wie für /bm stadtleben an|aus und /bm sprache. */
    public static final String ADMIN = "baumeister.admin";

    /** Was ein Schaltvorgang braucht; im Spiel ModuleService, im Test ein Fake. */
    public interface Target {
        /** Aktueller Wert in config.yml. */
        boolean current(String key);

        /** Wert in config.yml setzen und speichern. */
        void write(String key, boolean on);

        /**
         * Nur für Effect.SOFORT: den Wert im laufenden Dienst übernehmen (Stadtleben: TownLife.setEnabled, schreibt
         * selbst; Sprache: reopenVoice). false = ging nicht, dann gilt der Hinweis „nach Neustart“.
         */
        boolean applyLive(ModuleDef def, boolean on);
    }

    public enum Outcome {
        NO_RIGHT,
        USAGE,
        UNKNOWN,
        NO_SWITCH,
        UNCHANGED,
        SWITCHED_NOW,
        SWITCHED_AFTER_RESTART
    }

    public record Result(Outcome outcome, List<String> lines) {

        public boolean changed() {
            return outcome == Outcome.SWITCHED_NOW || outcome == Outcome.SWITCHED_AFTER_RESTART;
        }
    }

    private ModuleSwitch() {
    }

    /** verb „an“ oder „aus“; id darf null sein (dann Hinweis auf die Form). */
    public static Result run(boolean admin, String verb, String id, Target t) {
        if (!admin) {
            return new Result(Outcome.NO_RIGHT, List.of("Dafür fehlt dir " + ADMIN + "."));
        }
        boolean on = "an".equals(verb);
        if (!on && !"aus".equals(verb) || id == null || id.isBlank()) {
            return new Result(Outcome.USAGE, List.of("/bm module an|aus <modul> – /bm module zeigt alle."));
        }
        Optional<ModuleDef> found = ModuleDirectory.find(id);
        if (found.isEmpty()) {
            return new Result(Outcome.UNKNOWN, List.of("Kein Modul „" + ModuleJson.cut(id, 32)
                    + "“ – /bm module zeigt alle."));
        }
        ModuleDef d = found.get();
        if (!d.switchable()) {
            return new Result(Outcome.NO_SWITCH, List.of(d.name() + " hat keinen eigenen Schalter"
                    + (d.needs().isEmpty() ? " – es läuft immer." : " – es läuft, wenn das da ist: "
                            + String.join(", ", d.needs()) + ".")));
        }
        if (t.current(d.key()) == on) {
            return new Result(Outcome.UNCHANGED, List.of(d.name() + " ist schon " + verb + " (" + d.key() + ")."));
        }
        if (d.effect() == ModuleDef.Effect.SOFORT && t.applyLive(d, on)) {
            return new Result(Outcome.SWITCHED_NOW, List.of(d.name() + " " + verb + " – wirkt sofort."));
        }
        t.write(d.key(), on);
        return new Result(Outcome.SWITCHED_AFTER_RESTART, List.of(d.name() + " " + verb + " – in config.yml "
                + "gespeichert (" + d.key() + "), wirkt nach einem Neustart des Servers; /bm neuladen reicht dafür "
                + "nicht."));
    }
}
