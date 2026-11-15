package com.blackn0va.baumeister.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Register der verbundenen Portale: Suche je Zelle, Ersetzen, Speichern. */
class PortalBookTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static PortalBook.Portal portal(int x0, int z0, String target) {
        return new PortalBook.Portal("world", new PortalFrame.Opening(PortalFrame.Axis.Z, x0, 64, z0, 3, 4), target,
                "Farmwelt", ADMIN, "");
    }

    @Test
    void everyCellOfTheOpeningFindsThePortal() {
        PortalBook b = new PortalBook();
        b.put(portal(5, 10, "warp:farmwelt"));
        for (int z = 10; z <= 12; z++) {
            for (int y = 64; y <= 67; y++) {
                assertTrue(b.at("world", 5, y, z).isPresent(), y + "," + z);
            }
        }
        assertTrue(b.at("world", 5, 68, 11).isEmpty(), "Rahmen gehört nicht dazu");
        assertTrue(b.at("world_nether", 5, 64, 11).isEmpty(), "andere Welt");
    }

    @Test
    void aNewPortalOnTheSameCellsReplacesTheOld() {
        PortalBook b = new PortalBook();
        b.put(portal(5, 10, "warp:farmwelt"));
        List<PortalBook.Portal> replaced = b.put(portal(5, 10, "welt:plotwelt"));
        assertEquals(1, replaced.size());
        assertEquals(1, b.size());
        assertEquals("welt:plotwelt", b.at("world", 5, 64, 10).orElseThrow().target());
    }

    @Test
    void removingFreesEveryCell() {
        PortalBook b = new PortalBook();
        PortalBook.Portal p = portal(5, 10, "spawn");
        b.put(p);
        assertTrue(b.remove(p.id()).isPresent());
        assertTrue(b.at("world", 5, 65, 11).isEmpty());
        assertEquals(0, b.size());
    }

    @Test
    void jsonKeepsEverything() {
        PortalBook b = new PortalBook();
        b.put(portal(5, 10, "warp:farmwelt").withLabelEntity("0b6c7f6e-1111-2222-3333-444455556666"));
        b.put(new PortalBook.Portal("world", new PortalFrame.Opening(PortalFrame.Axis.X, -2, 64, 79, 5, 6), "spawn",
                "Lobby", ADMIN, ""));
        PortalBook back = PortalBook.fromJson(b.toJson());
        assertEquals(b.list(), back.list());
        assertTrue(back.at("world", 0, 69, 79).isPresent());
    }

    @Test
    void onlyKnownTargetFormsAreAccepted() {
        assertTrue(PortalBook.validTarget("spawn"));
        assertTrue(PortalBook.validTarget("warp:farmwelt"));
        assertTrue(PortalBook.validTarget("welt:world_nether"));
        assertFalse(PortalBook.validTarget("warp:"));
        assertFalse(PortalBook.validTarget("warp:a b"), "Leerzeichen stünden sonst in Klickbefehlen");
        assertFalse(PortalBook.validTarget("welt:../../etc"), "kein Pfad");
        assertFalse(PortalBook.validTarget("http://x"));
        assertFalse(PortalBook.validTarget(null));
        assertThrows(IllegalArgumentException.class, () -> new PortalBook().put(portal(0, 0, "nirgendwo")));
    }

    @Test
    void anUnreadableFileIsAnErrorNotAnEmptyRegister() {
        assertThrows(IllegalArgumentException.class, () -> PortalBook.fromJson("{kaputt"));
        assertThrows(IllegalArgumentException.class, () -> PortalBook.fromJson("{\"portale\":[{\"welt\":\"w\","
                + "\"achse\":\"z\",\"x\":0,\"y\":0,\"z\":0,\"breite\":1,\"hoch\":4,\"ziel\":\"spawn\","
                + "\"von\":\"00000000-0000-0000-0000-000000000001\"}]}"), "1 breit hält kein Portal");
    }
}
