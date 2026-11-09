package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.blackn0va.baumeister.companion.CompanionRouting.Speaker;
import com.blackn0va.baumeister.companion.CompanionRouting.Spot;

class CompanionRoutingTest {

    static final UUID WORLD = new UUID(1, 1);
    static final UUID NETHER = new UUID(1, 2);
    static final UUID OWNER = new UUID(2, 1);
    static final UUID GUEST = new UUID(2, 2);
    static final UUID FAR = new UUID(2, 3);
    static final UUID OWNER2 = new UUID(2, 4);

    @Test
    void guestNextToVeraIsAnsweredByHer() {
        List<Spot> spots = List.of(new Spot(OWNER, WORLD, 0, 64, 0));
        assertEquals(Optional.of(OWNER), CompanionRouting.pick(new Speaker(GUEST, WORLD, 5, 64, 5), spots, 16));
    }

    @Test
    void ownCompanionWinsEvenWhenAnotherIsCloser() {
        List<Spot> spots = List.of(new Spot(OWNER, WORLD, 0, 64, 0), new Spot(GUEST, WORLD, 100, 64, 0));
        assertEquals(Optional.of(GUEST), CompanionRouting.pick(new Speaker(GUEST, WORLD, 1, 64, 0), spots, 16));
    }

    @Test
    void nearestForeignCompanionAnswers() {
        List<Spot> spots = List.of(new Spot(OWNER, WORLD, 0, 64, 0), new Spot(OWNER2, WORLD, 10, 64, 0));
        assertEquals(Optional.of(OWNER2), CompanionRouting.pick(new Speaker(GUEST, WORLD, 8, 64, 0), spots, 16));
    }

    @Test
    void outOfRangeOrOtherWorldIsNotHeard() {
        List<Spot> spots = List.of(new Spot(OWNER, WORLD, 0, 64, 0));
        assertTrue(CompanionRouting.pick(new Speaker(GUEST, WORLD, 17, 64, 0), spots, 16).isEmpty());
        assertTrue(CompanionRouting.pick(new Speaker(GUEST, NETHER, 1, 64, 0), spots, 16).isEmpty());
    }

    @Test
    void voiceChatListensToOwnerAndNearbyPlayersOnly() {
        List<Spot> spots = List.of(new Spot(OWNER, WORLD, 0, 64, 0));
        Set<UUID> l = CompanionRouting.listeners(List.of(new Speaker(OWNER, NETHER, 0, 0, 0),
                new Speaker(GUEST, WORLD, 3, 64, 3), new Speaker(FAR, WORLD, 300, 64, 0)), spots, 16);
        assertEquals(Set.of(OWNER, GUEST), l);
    }
}
