package com.blackn0va.baumeister.town.use;

import java.util.UUID;

/**
 * Der Körper eines Bewohners, so weit das Benutzen ihn bewegt – im Betrieb BukkitUse.actor (Mannequin), im Test ein
 * Fake, der jede Bewegung mitschreibt.
 *
 * Threads: Hauptthread. Fehler: Darstellung ist Schmuck – die Bukkit-Seite schluckt nichts still, meldet aber über
 * Rückgabewerte (sit == null), statt zu werfen.
 */
public interface Actor {

    UUID id();

    double x();

    double y();

    double z();

    /** Zum Punkt schauen. */
    void face(double x, double y, double z);

    /** Arm schwingen. */
    void swing();

    /**
     * Ins Bett legen: an (x, y, z), Körperdrehung yaw, Pose liegend, ohne Schwerkraft und unbeweglich (sonst rutscht er
     * vom Bett). Das Bett selbst bleibt unverändert – kein occupied, der Spieler-Schlaf zählt ihn nicht.
     */
    void lie(double x, double y, double z, float yaw);

    /**
     * Hinsetzen: Sitz bei (x, y, z) mit Blick yaw. Rückgabe räumt den Sitz wieder ab (aufstehen); null = ging nicht.
     */
    Runnable sit(double x, double y, double z, float yaw);

    /** Aufstehen (nach lie): Pose stehend, Schwerkraft an, beweglich, an (x, y, z). */
    void rise(double x, double y, double z);

    /** Geräusch (Minecraft-Schlüssel, z. B. „block.anvil.use“) und Partikel (Particle-Name oder leer) am Punkt. */
    void effect(String sound, String particle, double x, double y, double z);
}
