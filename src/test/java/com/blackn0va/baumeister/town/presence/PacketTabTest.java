package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Was im Player-Info-Paket steht: Name, UUID, gelistet, Ping, Überleben – und welche Aktionen. */
class PacketTabTest {

    @Test
    void playerInfoCarriesNamePingGameModeAndIsListed() {
        UUID id = ResidentIdentity.profileId("Lotte", u -> false).orElseThrow();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = PacketTab.infos(List.of(new TabEntry("Lotte", id,
                42)));
        assertEquals(1, infos.size());
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo i = infos.getFirst();
        assertEquals(id, i.getProfileId());
        assertEquals("Lotte", i.getGameProfile().getName());
        assertTrue(i.getGameProfile().getTextureProperties().isEmpty(), "ohne Textur = Mannequin-Standardskin");
        assertTrue(i.isListed(), "ohne listed steht der Eintrag nicht in der Liste");
        assertEquals(42, i.getLatency());
        assertEquals(GameMode.SURVIVAL, i.getGameMode());
        assertNull(i.getDisplayName(), "Profilname anzeigen");
    }

    @Test
    void addActionsIncludeListedAndLatency() {
        assertEquals(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY), PacketTab.ACTIONS);
    }
}
