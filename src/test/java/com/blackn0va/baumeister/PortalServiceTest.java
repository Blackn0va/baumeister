package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Schildnamen über Portalen (2026-11-16): lesbar statt „World_nether“. */
class PortalServiceTest {

    @Test
    void labelsReadLikeNames() {
        assertEquals("Lobby", PortalService.niceName("spawn"));
        assertEquals("Nether", PortalService.niceName("welt:world_nether"));
        assertEquals("End", PortalService.niceName("welt:world_the_end"));
        assertEquals("Nether", PortalService.niceName("welt:vera_nether"));
        assertEquals("Plotwelt", PortalService.niceName("welt:plotwelt"));
        assertEquals("Farmwelt", PortalService.niceName("warp:farmwelt"));
        assertEquals("Mein Haus", PortalService.niceName("warp:mein_haus"));
        assertEquals("Stadt Mitte", PortalService.niceName("warp:stadt-mitte"));
        // Ein Warp darf „nether“ heißen und bleibt dann sein Name, nur Welten werden zu Nether/End.
        assertEquals("Mein Nether", PortalService.niceName("warp:mein_nether"));
    }
}
