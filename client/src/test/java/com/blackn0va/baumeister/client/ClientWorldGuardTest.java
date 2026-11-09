package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

/** Bau und Rückgängig gehören zu der Welt, in der gebaut wurde (2026-11-01). */
class ClientWorldGuardTest {

    private final Object worldA = new Object();
    private final Object worldB = new Object();

    private ClientBuild buildIn(Object world) {
        return new ClientBuild("bau ein Haus", new ArrayList<>(), false, 0, world);
    }

    @Test
    void buildStaysInItsOwnWorldOnly() {
        ClientBuild b = buildIn(worldA);
        assertTrue(ClientBuild.sameWorld(b, worldA));
        assertFalse(ClientBuild.sameWorld(b, worldB), "Dimension, Welt oder Server gewechselt");
    }

    @Test
    void noWorldCountsAsForeign() {
        assertFalse(ClientBuild.sameWorld(buildIn(worldA), null), "Menü bzw. Verbindung getrennt");
    }

    @Test
    void undoBuildBelongsToTheWorldOfTheBuild() {
        ClientBuild back = buildIn(worldA).undoBuild();
        assertSame(worldA, back.world);
        assertTrue(ClientBuild.sameWorld(back, worldA));
        assertFalse(ClientBuild.sameWorld(back, worldB));
    }
}
