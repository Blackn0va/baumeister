package com.blackn0va.baumeister.town.presence;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

/**
 * Tab-Einträge über PacketEvents 2.14.0: Player-Info-Update (ADD_PLAYER, Spielmodus, gelistet, Ping) und
 * Player-Info-Remove. Nur Pakete an den Client – auf dem Server entsteht kein Spieler: Server-Ping, Schlaf, Rechte und
 * Vera zählen weiter nur echte Player (Vertrag v013, Nicht-Ziel).
 *
 * Konstruktoren und Aktionen per javap aus packetevents-api-2.14.0.jar (2026-11-22), nicht geraten:
 * PlayerInfo(UserProfile, boolean listed, int latency, GameMode, Component displayName, RemoteChatSession).
 * displayName null = der Client zeigt den Profilnamen (wie bei Spielern ohne Team), chatSession null = kein signierter
 * Chat (Bewohner sprechen über Systemzeilen). Je Empfänger ein eigenes Paket-Objekt – vorsichtshalber, nicht gemessen,
 * ob PacketEvents ein geteiltes Objekt je Verbindung neu schreibt; bei höchstens 12 Bewohnern kostet das nichts.
 *
 * Threads: Hauptthread. Fehler: wirft, was PacketEvents wirft; Presence fängt je Spieler.
 */
final class PacketTab implements Tab {

    /** Was ein neuer Eintrag mitbringt. Ohne UPDATE_LISTED zeigt der Client ihn seit 1.19.3 nicht in der Liste. */
    static final EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> ACTIONS = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public void show(Player to, List<TabEntry> entries) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(to,
                new WrapperPlayServerPlayerInfoUpdate(EnumSet.copyOf(ACTIONS), infos(entries)));
    }

    @Override
    public void hide(Player to, List<UUID> ids) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(to,
                new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(ids)));
    }

    /** Die Einträge, wie sie im Paket stehen: Name, UUID, gelistet, Ping, Überleben, ohne Textur (siehe ResidentIdentity). */
    static List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos(List<TabEntry> entries) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> out = new ArrayList<>();
        for (TabEntry e : entries) {
            out.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(new UserProfile(e.id(), e.name()), true,
                    e.latency(), GameMode.SURVIVAL, null, null));
        }
        return out;
    }
}
