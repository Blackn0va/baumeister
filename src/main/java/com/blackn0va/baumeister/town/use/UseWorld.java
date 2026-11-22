package com.blackn0va.baumeister.town.use;

import java.util.UUID;

/**
 * Was das Benutzen (Vertrag v013, AK-44) von einer Welt braucht – im Betrieb BukkitUse.world, im Test ein Fake
 * (TownUseTest). Eigene Schnittstelle statt Bukkit-Blöcke: Tags und Blockdaten laden ohne Server nicht, und dann wäre
 * jede Regel (welche Tür, welches Bett, welche Bank) ungeprüft – wie vor der Env-Schnittstelle von TownLife.
 *
 * Die einzige schreibende Methode ist setOpen. Sie ist bewusst nur für Türen, Tore und Falltüren da (UseBlocks.door):
 * Bewohner ändern nichts anderes, nie einen Behälter, nie Redstone (Nicht-Ziel des Vertrags).
 *
 * Threads: Hauptthread. Fehler: at liefert null für ungeladene Chunks; mayUse liefert false bei jedem Fehler.
 */
public interface UseWorld {

    /**
     * Ein Block, so weit das Benutzen ihn braucht. type = Material-Name (OAK_DOOR); facing = Richtung (NORTH …) oder
     * leer; half = Hälfte (Treppe/Tür TOP|BOTTOM, Stufe TOP|BOTTOM|DOUBLE, Bett HEAD|FOOT) oder leer; open = offen
     * (Openable); occupied = Bett belegt (Spieler oder Dorfbewohner schläft).
     */
    record Block(String type, String facing, String half, boolean open, boolean occupied) {

        public static Block of(String type) {
            return new Block(type, "", "", false, false);
        }
    }

    /** Name der Welt (Schlüssel für Belegung und Rechte-Puffer). */
    String name();

    /** Block bei (x, y, z); null = Chunk nicht geladen oder außerhalb der Bauhöhe. Lädt nie einen Chunk. */
    Block at(int x, int y, int z);

    /** Begehbar ohne Flüssigkeit, Feuer, Spinnweben (wie BukkitEnv.standNear). Ungeladen = false. */
    boolean passable(int x, int y, int z);

    /** Trägt (fester Boden, kein Magma, kein Laub). Ungeladen = false. */
    boolean solid(int x, int y, int z);

    /** Tür, Tor oder Falltür auf/zu stellen; true = gestellt. Nur für UseBlocks.door, sonst false ohne Änderung. */
    boolean setOpen(int x, int y, int z, boolean open);

    /**
     * Darf der Bewohner who diesen Block benutzen? Wie ein Rechtsklick: ein Interact-Event, das WorldGuard,
     * PlotSquared &amp; Co. absagen können. Fail-closed (ASVS V4.1.5): abgesagt, Ausnahme oder Bewohner weg → false.
     */
    boolean mayUse(UUID who, int x, int y, int z);

    /** Geräusch (Minecraft-Schlüssel) am Punkt, für alle in Hörweite. */
    void sound(String key, double x, double y, double z);

    /** Steht ein Spieler (kein Zuschauer) höchstens r Blöcke vom Punkt? */
    boolean playerNear(double x, double y, double z, double r);
}
