package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Prüfsumme des Bauplatzes: Was ohne Spieler wächst oder vergeht, darf ein „ja“ nicht scheitern lassen; was Spieler
 * bauen, muss weiter auffallen.
 */
class SiteFingerprintTest {

    @Test
    void naturalChangesDoNotCount() {
        assertEquals(SiteFingerprint.bucket("dirt"), SiteFingerprint.bucket("grass_block"), "Gras breitet sich aus");
        assertEquals(SiteFingerprint.bucket("dirt"), SiteFingerprint.bucket("farmland"), "Ackerboden trocknet");
        assertEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("sugar_cane"), "Zuckerrohr wächst");
        assertEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("bamboo"));
        assertEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("snow"), "Schnee fällt");
        assertEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("fire"));
        assertEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("vine"));
        assertEquals(SiteFingerprint.bucket("water"), SiteFingerprint.bucket("kelp_plant"), "Seetang wächst");
        assertEquals(SiteFingerprint.bucket("water"), SiteFingerprint.bucket("ice"), "Wasser friert");
    }

    @Test
    void playerBuildsStillCount() {
        assertNotEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("oak_planks"));
        assertNotEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("oak_leaves"), "Laub setzen Spieler");
        assertNotEquals(SiteFingerprint.bucket("air"), SiteFingerprint.bucket("wheat"), "Felder legen Spieler an");
        assertNotEquals(SiteFingerprint.bucket("dirt"), SiteFingerprint.bucket("stone"));
        assertNotEquals(SiteFingerprint.bucket("stone"), SiteFingerprint.bucket("cobblestone"));
        assertNotEquals(SiteFingerprint.bucket("water"), SiteFingerprint.bucket("glass"));
    }
}
