package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.VeraRights;
import com.blackn0va.baumeister.companion.VeraRights.Ability;
import com.blackn0va.baumeister.companion.VeraRights.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * Veras Rechte auf dem Server (VeraRights) mit Datei plugins/Baumeister/vera-rechte.yml. Geändert wird im Admin-Buch
 * (/bm begleiter recht …), die Datei ist nur der Speicher.
 *
 * Kaputte Datei (YAML-Fehler): Vorgaben gelten, Änderungen werden abgelehnt und die Datei bleibt unangetastet, bis der
 * Admin sie repariert oder löscht – sonst überschriebe der nächste Klick im Buch seine Einträge (fail-closed, ASVS
 * V4.1.3). Geschrieben wird atomar (.neu, dann umbenennen), wie bei begleiter-wiederkommen.json.
 *
 * Einträge je Spieler wirken zusätzlich als PermissionAttachment am Spieler (apply, bei Beitritt und jeder Änderung):
 * Dann gilt ein „verboten“ für Bauen oder Rucksack bei JEDER Prüfung im Plugin, nicht nur bei Vera. Ein vergebenes
 * LuckPerms-Recht verliert gegen den Anhang nicht immer (LuckPerms ordnet selbst) – im Zweifel dort pflegen.
 *
 * Threads: allowed aus jedem Thread, Ändern, Laden und apply im Hauptthread. Fehler: wirft nicht; Rückgabe null = ok, sonst
 * deutscher Grund.
 */
final class CompanionRights {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String FILE = "vera-rechte.yml";

    private final Path file;
    private final Plugin plugin;
    private final VeraRights rights = new VeraRights();
    private final Map<UUID, PermissionAttachment> attached = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean broken;

    CompanionRights(Plugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.file = dataFolder.toPath().resolve(FILE);
        load();
    }

    /** Einträge des Spielers als Rechte-Anhang setzen (alter Anhang weg). Beim Beitritt und nach jeder Änderung. */
    void apply(Player p) {
        PermissionAttachment old = attached.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException e) {
                // Anhang gehörte zu einem früheren Player-Objekt (neu beigetreten) – nichts zu entfernen.
                LOG.fine(() -> "begleiter | rechte_anhang_alt | spieler=" + p.getName());
            }
        }
        Map<Ability, Boolean> own = new java.util.EnumMap<>(Ability.class);
        for (Ability a : Ability.values()) {
            Boolean v = rights.player(p.getUniqueId(), a);
            if (v != null) {
                own.put(a, v);
            }
        }
        if (own.isEmpty()) {
            return;
        }
        PermissionAttachment att = p.addAttachment(plugin);
        own.forEach((a, v) -> att.setPermission(a.node(), v));
        attached.put(p.getUniqueId(), att);
    }

    void forget(UUID player) {
        attached.remove(player);
    }

    void load() {
        broken = false;
        if (!Files.exists(file)) {
            rights.load(Map.of(), Map.of());
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            broken = true;
            rights.load(Map.of(), Map.of());
            LOG.severe(() -> "begleiter | rechte_unlesbar | datei=" + file + " grund=" + e.getClass().getSimpleName()
                    + " – Vorgaben gelten, Änderungen gesperrt bis zur Reparatur");
            return;
        }
        Map<String, Object> stufen = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection("stufen");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                stufen.put(k, s.get(k));
            }
        }
        Map<String, Map<String, Object>> spieler = new LinkedHashMap<>();
        ConfigurationSection p = y.getConfigurationSection("spieler");
        if (p != null) {
            for (String id : p.getKeys(false)) {
                ConfigurationSection e = p.getConfigurationSection(id);
                spieler.put(id, e == null ? null : e.getValues(false));
            }
        }
        int bad = rights.load(stufen, spieler);
        if (bad > 0) {
            LOG.warning(() -> "begleiter | rechte_teilweise_verworfen | datei=" + file + " einträge=" + bad
                    + " – dort gilt die Vorgabe");
        }
        LOG.info(() -> "begleiter | rechte_geladen | spieler_einträge=" + rights.entries().size());
    }

    boolean allowed(Player p, Ability a) {
        return rights.allowed(a, p.getUniqueId(), new VeraRights.Who(p.isOp(), p.hasPermission(a.node())));
    }

    VeraRights view() {
        return rights;
    }

    boolean broken() {
        return broken;
    }

    String setLevel(Ability a, Level l) {
        if (broken) {
            return FILE + " ist beschädigt – erst reparieren oder löschen, dann /bm neuladen.";
        }
        Level before = rights.level(a);
        rights.level(a, l);
        String err = save();
        if (err != null) {
            rights.level(a, before);
        }
        return err;
    }

    String setPlayer(UUID id, String name, Ability a, Boolean allow) {
        if (broken) {
            return FILE + " ist beschädigt – erst reparieren oder löschen, dann /bm neuladen.";
        }
        Boolean before = rights.player(id, a);
        rights.player(id, name, a, allow);
        String err = save();
        if (err != null) {
            rights.player(id, name, a, before);
        }
        return err;
    }

    private String save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of(
                "Veras Rechte – geändert über das Admin-Buch (/hilfe → Vera-Rechte) oder /bm begleiter recht.",
                "stufen: alle | ops | aus. spieler: <uuid>: <fähigkeit>: true/false (vor der Stufe).",
                "Ein vergebenes Bukkit-Recht (LuckPerms, baumeister.admin) erlaubt zusätzlich."));
        rights.save().forEach(y::set);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE + ".neu");
            Files.writeString(tmp, y.saveToString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return null;
        } catch (IOException e) {
            LOG.warning(() -> "begleiter | rechte_schreiben_fehlgeschlagen | datei=" + file + " grund=" + e);
            return "Konnte " + FILE + " nicht schreiben – nichts geändert.";
        }
    }
}
