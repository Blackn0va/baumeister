package com.blackn0va.baumeister;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /mods (Wunsch 2026-11-07): Welche Minecraft-Fassung und welche Mods der Server braucht, und woher. Ein Server kann
 * keine Mods an
 * Clients schicken; das Paket baut tools/modpaket/Modpaket-Erstellen.ps1 auf dem Spiele-PC und legt es mit -Hochladen
 * unter plugins/Baumeister/modpaket/ ab. Von dort liest /mods die Liste (…-mods.txt); den Download-Link setzt der
 * Admin in config.yml unter mods.link (z. B. ein geteilter Ordner) – ohne Link verweist /mods an den Admin.
 *
 * Threads: Hauptthread (Befehl). Fehler: unlesbare Liste → ohne Liste weiter (fail-open: reine Auskunft).
 */
final class ModsInfo {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Eine Zeile der Liste: Datei, Quelle („Modrinth“ / „im Paket“), Fassung. */
    record Entry(String file, String source, String version) {
    }

    /** Kopf und Zeilen der Liste. */
    record Listing(String header, List<Entry> entries) {
    }

    private ModsInfo() {
    }

    /** Liste aus Modpaket-Erstellen.ps1: erste Zeile Kopf, danach „datei TAB quelle TAB fassung TAB projekt“. */
    static Listing parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        String header = lines.isEmpty() ? "" : lines.getFirst();
        for (String l : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] p = l.split("\t");
            if (p.length >= 3 && p[0].endsWith(".jar")) {
                out.add(new Entry(p[0], p[1], p[2]));
            }
        }
        return new Listing(header, List.copyOf(out));
    }

    /** Neueste Liste im Ordner (Name sortiert, die Fassung steht im Namen). */
    static Optional<Path> newestList(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith("-mods.txt")).max(Comparator.naturalOrder());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mods | liste_unlesbar | ordner=" + dir, e);
            return Optional.empty();
        }
    }

    /** Minecraft-Fassung dieses Servers (getMinecraftVersion gibt es nur in der Paper-API, gebaut wird gegen Spigot). */
    static String minecraftVersion() {
        return minecraftVersion(Bukkit.getBukkitVersion());
    }

    /**
     * „1.21.11“ aus „1.21.11-R0.1-SNAPSHOT“ (Spigot), „26.2“ aus „26.2.build.135-…“ (Paper 26.x). Vorher „alles vor dem
     * ersten Bindestrich“: Auf Paper 26.2 stand in /mods „Minecraft 26.2.build.135“ – diese Fassung bietet kein Launcher
     * an, und das Modpaket hieß danach (gemessen 2026-11-08 auf einem frischen Paper 26.2-135). Nur die führenden
     * Zahlen zählen; ohne Zahl am Anfang bleibt der Text, wie er ist.
     */
    static String minecraftVersion(String bukkitVersion) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+){1,2})").matcher(bukkitVersion);
        return m.find() ? m.group(1) : bukkitVersion;
    }

    static void show(CommandSender to, Path dataFolder, String link, ModDownloads downloads) {
        Texts.info(to, "Server: Minecraft " + minecraftVersion() + " – im Launcher genau diese Fassung mit "
                + "Fabric starten. Für Sprache mit Vera: Simple Voice Chat; Fabric API gehört immer dazu.");
        Optional<Path> list = newestList(dataFolder.resolve("modpaket"));
        if (list.isPresent()) {
            try {
                Listing l = parse(Files.readAllLines(list.get(), StandardCharsets.UTF_8));
                Texts.info(to, l.header());
                for (Entry e : l.entries()) {
                    Texts.info(to, "• " + e.file() + (e.version().equals("-") ? "" : " (" + e.version() + ")"));
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "mods | liste_unlesbar | datei=" + list.get(), e);
            }
        }
        // Eigener Link vom Server (ModDownloads) vor mods.link: jeder Spieler bekommt seinen, 24 h gültig.
        if (to instanceof Player p && downloads != null) {
            java.util.Optional<String> own = downloads.link(p.getUniqueId(), p.getName());
            if (own.isPresent()) {
                TextComponent t = new TextComponent("§6[Modpaket herunterladen]");
                t.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, own.get()));
                p.spigot().sendMessage(t);
                Texts.info(to, downloads.scheme().equals("https") ? "Dein Link gilt 24 Stunden und nur für dich."
                        : "Dein Link gilt 24 Stunden und nur für dich. Der Server hat noch kein Zertifikat (http), "
                        + "darum fragt der Browser vor dem Download nach.");
                return;
            }
        }
        if (link == null || link.isBlank()) {
            Texts.info(to, "Das Modpaket (Baumeisterland-" + minecraftVersion() + ".mrpack für Prism Launcher "
                    + "oder die Modrinth App, dazu ein ZIP für den normalen Launcher) gibt dir der Admin.");
            return;
        }
        if (to instanceof Player p) {
            TextComponent t = new TextComponent("§6[Modpaket herunterladen]");
            t.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
            p.spigot().sendMessage(t);
        } else {
            to.sendMessage("Modpaket: " + link);
        }
    }
}
