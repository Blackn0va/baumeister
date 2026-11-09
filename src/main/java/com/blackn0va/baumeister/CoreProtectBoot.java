package com.blackn0va.baumeister;

/**
 * Einziger Weg zu CoreProtectBridge (wie VoicechatBoot): erst geladen, wenn BaumeisterPlugin weiß, dass CoreProtect
 * läuft. So zieht BaumeisterPlugin keine CoreProtect-Klasse nach, und ohne das Plugin startet alles andere.
 *
 * Threads: Hauptthread (onEnable). Fehler: null, wenn die API fehlt oder zu alt ist.
 */
final class CoreProtectBoot {

    private CoreProtectBoot() {
    }

    static BlockHistory start(BaumeisterPlugin plugin) {
        return CoreProtectBridge.connect(plugin);
    }
}
