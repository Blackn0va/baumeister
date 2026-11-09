package com.blackn0va.baumeister;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Werte unter komfort: in config.yml – Fortschrittsbalken, Warteschlange, Bauvorschau, Rückgängig über Neustart.
 *
 * Eigener Record statt neuer Felder in Settings: Settings wird positionsweise gebaut, und jede neue Komponente hätte
 * alle Aufrufer berührt. Jeder Schlüssel hat hier seine Vorgabe – die Config auf dev01 wird bei einem Deploy nicht
 * ersetzt (Gedächtnis, Deploy 18:48), ein fehlender Schlüssel muss also dasselbe tun wie die Vorlage.
 *
 * Threads: zustandslos, von überall. Fehler: keine; Unsinn wird auf den erlaubten Bereich begrenzt.
 */
record ComfortSettings(
        boolean bossBar,
        int bossBarRadius,
        int queueMax,
        int queuePerPlayer,
        int previewSeconds,
        boolean undoAcrossRestart,
        int undoMaxDays,
        int undoMaxMb) {

    static final boolean BOSS_BAR = true;
    static final int BOSS_BAR_RADIUS = 64;
    static final int QUEUE_MAX = 10;
    static final int QUEUE_PER_PLAYER = 2;
    static final int PREVIEW_SECONDS = 120;
    static final boolean UNDO_ACROSS_RESTART = true;
    static final int UNDO_MAX_DAYS = 7;
    static final int UNDO_MAX_MB = 512;

    /** Vorgaben ohne Config – so verhält sich ein Server, dessen config.yml den Abschnitt komfort: noch nicht kennt. */
    static ComfortSettings defaults() {
        return new ComfortSettings(BOSS_BAR, BOSS_BAR_RADIUS, QUEUE_MAX, QUEUE_PER_PLAYER, PREVIEW_SECONDS,
                UNDO_ACROSS_RESTART, UNDO_MAX_DAYS, UNDO_MAX_MB);
    }

    static ComfortSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new ComfortSettings(
                root.getBoolean("komfort.bossbar", BOSS_BAR),
                Math.clamp(root.getInt("komfort.bossbar-umkreis", BOSS_BAR_RADIUS), 0, 512),
                // 0 schaltet die Warteschlange ab: dann gilt wieder „Ich baue gerade noch.“
                Math.clamp(root.getInt("komfort.warteschlange-max", QUEUE_MAX), 0, 100),
                Math.clamp(root.getInt("komfort.warteschlange-je-spieler", QUEUE_PER_PLAYER), 1, 20),
                Math.clamp(root.getInt("komfort.vorschau-sekunden", PREVIEW_SECONDS), 10, 3600),
                root.getBoolean("komfort.rückgängig-über-neustart", UNDO_ACROSS_RESTART),
                Math.clamp(root.getInt("komfort.rückgängig-max-tage", UNDO_MAX_DAYS), 1, 365),
                Math.clamp(root.getInt("komfort.rückgängig-max-mb", UNDO_MAX_MB), 1, 100_000));
    }
}
