package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.Roster;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Profil-UUID der Bewohner: nie die eines echten Spielers, gleicher Kopf wie das Mannequin, stabil. */
class ResidentIdentityTest {

    private static UUID id(String name) {
        return ResidentIdentity.profileId(name, u -> false).orElseThrow();
    }

    /** Version 2 und IETF-Variante – Mojang vergibt Version 4, offline-mode Version 3. */
    @Test
    void residentUuidsAreVersionTwoNeverLikePlayers() {
        for (Roster.Person p : Roster.all()) {
            UUID u = id(p.name());
            assertEquals(ResidentIdentity.VERSION, u.version(), p.name());
            assertEquals(2, u.variant(), p.name());
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + p.name()).getBytes(StandardCharsets.UTF_8));
            assertEquals(3, offline.version());
            assertNotEquals(offline, u, p.name());
        }
    }

    /** Der Kopf in der Liste ist der Mannequin-Standardskin (Index 0 = slim/alex, gemessen in den Client-Jars). */
    @Test
    void residentUuidsShowTheMannequinSkin() {
        for (Roster.Person p : Roster.all()) {
            assertEquals(0, Math.floorMod(id(p.name()).hashCode(), 18), p.name());
        }
    }

    /** Gleicher Name → gleiche UUID (nach jedem Neustart), verschiedene Namen → verschiedene UUIDs. */
    @Test
    void residentUuidsAreStableAndDistinct() {
        Set<UUID> all = new HashSet<>();
        for (Roster.Person p : Roster.all()) {
            assertEquals(id(p.name()), id(p.name()));
            assertTrue(all.add(id(p.name())), p.name());
        }
    }

    /** Belegt ein Spieler (oder ein anderer Bewohner) die UUID, kommt eine andere – weiter Version 2, gleicher Skin. */
    @Test
    void takenUuidIsSkipped() {
        UUID first = id("Lotte");
        UUID second = ResidentIdentity.profileId("Lotte", first::equals).orElseThrow();
        assertNotEquals(first, second);
        assertEquals(2, second.version());
        assertEquals(0, Math.floorMod(second.hashCode(), 18));
    }

    /** Ist alles belegt, gibt es keinen Eintrag statt einer Ausnahme (dann nur Mannequin, ohne Tab). */
    @Test
    void noFreeUuidGivesEmpty() {
        assertEquals(Optional.empty(), ResidentIdentity.profileId("Lotte", u -> true));
    }

    /** Ping wie ein Spieler mit gutem Anschluss, fest je Name. */
    @Test
    void latencyIsStableAndPlausible() {
        for (Roster.Person p : Roster.all()) {
            int ms = ResidentIdentity.latency(p.name());
            assertTrue(ms >= 18 && ms < 75, p.name() + " " + ms);
            assertEquals(ms, ResidentIdentity.latency(p.name()));
        }
    }
}
