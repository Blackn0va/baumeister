package com.blackn0va.baumeister.town.presence;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;

import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

import org.bukkit.plugin.Plugin;

/**
 * Die echten PacketEvents-Aufrufe für PacketBoot, getrennt, damit PacketBoot ohne Server testbar bleibt.
 *
 * Einstellungen vor load(), alle mit Grund:
 * - checkForUpdates(false): sonst fragt PacketEvents beim Start GitHub nach einer neuen Fassung – ein externer Dienst,
 *   den der Admin nicht bestellt hat; die Fassung ist ohnehin gepinnt.
 * - bStats(false): sonst meldet PacketEvents Nutzungszahlen an bStats.org (Telemetrie ohne Zustimmung).
 * - kickOnPacketException(false): PacketEvents hängt sich in JEDE Verbindung. Wirft es bei einem fremden Paket, soll
 *   der Spieler bleiben – der Tab-Eintrag eines Bewohners ist keinen Rauswurf wert.
 *
 * Threads: Hauptthread. Fehler: wirft, was PacketEvents wirft; PacketBoot fängt.
 */
final class PacketEventsEngine implements PacketBoot.Engine {

    private final Plugin plugin;

    PacketEventsEngine(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void load() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
        PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(false).kickOnPacketException(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void init() {
        PacketEvents.getAPI().init();
    }

    @Override
    public void terminate() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
    }

    @Override
    public String version() {
        ServerVersion v = PacketEvents.getAPI().getServerManager().getVersion();
        return v == null ? "unbekannt" : v.getReleaseName();
    }

    @Override
    public boolean supported() {
        ServerVersion v = PacketEvents.getAPI().getServerManager().getVersion();
        // Mannequins gibt es ab 1.21.9; ERROR heißt: PacketEvents kennt die Fassung nicht (neuer als 2.14.0).
        return v != null && v != ServerVersion.ERROR && v.isNewerThanOrEquals(ServerVersion.V_1_21_9);
    }
}
