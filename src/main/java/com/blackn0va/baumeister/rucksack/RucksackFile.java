package com.blackn0va.baumeister.rucksack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Datei eines Rucksacks: plugins/Baumeister/rucksack/&lt;uuid&gt;.yml, Format
 * {@code version: 1, seiten: {<seite>: {<platz>: <ItemStack>}}} – nur belegte Plätze.
 *
 * Warum YAML: Die Bukkit-Serialisierung trägt die Datenversion des Gegenstands mit, ein späteres Minecraft wandelt
 * alte Einträge beim Laden um. Das Plugin baut gegen spigot-api, Paper-Bytes (serializeAsBytes) gibt es dort nicht.
 *
 * Fail-closed (der Inhalt eines Rucksacks ist oft alles, was ein Spieler hat): Eine Datei, die sich nicht lesen lässt
 * oder in der ein Eintrag kein Gegenstand ist (z. B. unbekannte Sorte nach einem Rückschritt der Serverfassung),
 * gilt als BROKEN. Dann öffnet der Rucksack nicht und wird nie überschrieben – sonst wären die nicht erkannten Sachen
 * beim nächsten Speichern weg.
 *
 * Schreiben: erst in .neu, die bisherige Fassung wird zu .vorher, dann atomar umbenennen.
 *
 * Threads: beliebig, aber je Datei nur einer zugleich (RucksackService schreibt über einen Einzel-Thread). Fehler:
 * IOException beim Schreiben, Lesefehler als Status.
 */
public final class RucksackFile {

    public static final int VERSION = 1;

    public enum Status {
        MISSING, OK, BROKEN
    }

    /** config nur bei OK; reason nur bei BROKEN. */
    public record Loaded(Status status, YamlConfiguration config, String reason) {
    }

    private RucksackFile() {
    }

    public static Loaded load(Path file) {
        if (!Files.exists(file)) {
            return new Loaded(Status.MISSING, null, null);
        }
        YamlConfiguration yc = new YamlConfiguration();
        try {
            yc.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            return new Loaded(Status.BROKEN, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        int version = yc.getInt("version", -1);
        if (version != VERSION) {
            return new Loaded(Status.BROKEN, null, "version=" + version);
        }
        return new Loaded(Status.OK, yc, null);
    }

    /**
     * Prüft, dass jeder gespeicherte Platz einen Gegenstand der erwarteten Art trägt und in den Rucksack passt.
     * Liefert den Grund oder null.
     */
    public static String check(YamlConfiguration yc, Class<?> itemType, int slots) {
        ConfigurationSection pages = yc.getConfigurationSection("seiten");
        if (pages == null) {
            return null;
        }
        for (String p : pages.getKeys(false)) {
            ConfigurationSection page = pages.getConfigurationSection(p);
            int pi;
            try {
                pi = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return "seite=" + p;
            }
            if (page == null) {
                return "seite=" + p + " leer";
            }
            for (String s : page.getKeys(false)) {
                int si;
                try {
                    si = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return "platz=" + p + "/" + s;
                }
                long index = (long) pi * RucksackLogic.PER_PAGE + si;
                if (pi < 0 || si < 0 || si >= RucksackLogic.PER_PAGE || index >= slots) {
                    // Weniger Seiten eingestellt als belegt: lieber nicht öffnen als die hinteren Seiten verlieren.
                    return "platz_außerhalb=" + p + "/" + s;
                }
                if (!itemType.isInstance(page.get(s))) {
                    return "kein_gegenstand=" + p + "/" + s;
                }
            }
        }
        return null;
    }

    public static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".neu");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".vorher"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
