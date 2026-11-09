package com.blackn0va.baumeister;

/**
 * Einziger Weg zu CompanionVoicechat: Diese Klasse wird erst geladen, wenn BaumeisterPlugin festgestellt hat, dass das
 * Plugin „voicechat“ läuft. So taucht in BaumeisterPlugin selbst kein Typ auf, der Voice-Chat-Klassen nachzieht.
 *
 * Threads: Hauptthread (onEnable). Fehler: null, wenn der Dienst von Simple Voice Chat fehlt.
 */
final class VoicechatBoot {

    private VoicechatBoot() {
    }

    static CompanionInGameVoice start(BaumeisterPlugin plugin) {
        return CompanionVoicechat.install(plugin);
    }
}
