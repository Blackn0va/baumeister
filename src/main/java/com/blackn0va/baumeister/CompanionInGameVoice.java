package com.blackn0va.baumeister;

import java.util.UUID;

import org.bukkit.entity.Entity;

/**
 * Stimme im Spiel, ohne Typen von Simple Voice Chat: CompanionService kennt nur diese Schnittstelle, die Umsetzung
 * (CompanionVoicechat) wird nur geladen, wenn das Plugin „voicechat“ läuft. Wie bei WorldEdit (BaumeisterPlugin hält
 * die Bibliothek als Object): Stünde ein Voice-Chat-Typ in CompanionService, liefe der Server ohne Simple Voice Chat in
 * NoClassDefFoundError.
 *
 * Threads und Fehler: siehe CompanionVoicechat.
 */
interface CompanionInGameVoice {

    /** Spieler hat Simple Voice Chat verbunden – dann spricht der Begleiter im Spiel statt über die App. */
    boolean connected(UUID player);

    /** 48 kHz mono an der Entity abspielen; false = nicht möglich (dann bleibt es beim Chat). */
    boolean play(Entity speaker, short[] pcm48k);

    /**
     * Wie play, aber an einen Spieler gerichtet: Fällt er ihr ins Wort (BargeIn), bricht die Umsetzung ab und meldet
     * CompanionService.voiceInterrupted.
     */
    default boolean play(Entity speaker, UUID addressee, short[] pcm48k) {
        return play(speaker, pcm48k);
    }

    /** Angefangene Aufnahme des Spielers verwerfen. */
    void forget(UUID player);

    void shutdown();
}
