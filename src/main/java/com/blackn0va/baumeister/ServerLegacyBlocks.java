package com.blackn0va.baumeister;

import com.blackn0va.baumeister.structure.StructureFile;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Alte Zahlen-IDs (MCEdit-.schematic, vor 1.13) über die Umrechnung des Servers (Wunsch 2026-11-08). Jeder Spigot- und
 * Paper-Server bringt sie mit (CraftLegacy), weil er alte
 * Welten hochrüsten muss – Spigot 1.21.11 und Paper 26.2-135 nachgesehen. Keine eigene oder kopierte Tabelle: Was der
 * Server umrechnet, ist genau das, was er auch beim Laden einer alten Welt täte.
 *
 * Der erste Aufruf weckt die Legacy-Unterstützung des Servers (einmalig, Log „Initializing Legacy Material
 * Support“, gemessen etwa eine Sekunde) – darum erst, wenn wirklich eine alte Datei gelesen wird.
 *
 * Threads: Hauptthread (FileSchematics.find). Fehler: unbekannte Nummer → null (StructureFile zählt sie als unlesbar).
 */
final class ServerLegacyBlocks implements StructureFile.Legacy {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private Material[] byId;

    @Override
    public synchronized String state(int id, int data) {
        if (byId == null) {
            byId = table();
        }
        if (id < 0 || id >= byId.length || byId[id] == null) {
            return null;
        }
        try {
            @SuppressWarnings("deprecation")
            BlockData bd = Bukkit.getUnsafe().fromLegacy(byId[id], (byte) data);
            return bd == null || bd.getMaterial().isAir() && id != 0 ? null : bd.getAsString();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "schematics | legacy_unbekannt | id=" + id + ":" + data, e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Material[] table() {
        Material[] out = new Material[4096];
        int n = 0;
        for (Material m : Material.values()) {
            if (m.isLegacy() && m.isBlock()) {
                int id = m.getId();
                if (id >= 0 && id < out.length && out[id] == null) {
                    out[id] = m;
                    n++;
                }
            }
        }
        int count = n;
        LOG.info(() -> "schematics | legacy_bereit | alte_blöcke=" + count);
        return out;
    }
}
