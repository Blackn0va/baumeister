package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Grenzen ohne Anmeldung mit fester Uhr: je Adresse bzw. /64, insgesamt, Sperre 10 s → 10 min, begrenzte Sperrliste.
 */
class HandshakeGateTest {

    private static final long S = 1_000_000_000L;

    private static HandshakeGate gate(int perAddress, int total, int entries) {
        Limits.Builder b = new Limits.Builder();
        b.perAddress = perAddress;
        b.unauthenticated = total;
        b.banEntries = entries;
        b.banMin = Duration.ofSeconds(10);
        b.banMax = Duration.ofMinutes(10);
        return new HandshakeGate(b.build());
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void addressLimitCountsConcurrentHandshakesPerAddress() throws Exception {
        HandshakeGate g = gate(2, 32, 100);
        HandshakeGate.Admission a = g.enter(ip("198.51.100.7"), 0);
        HandshakeGate.Admission b = g.enter(ip("198.51.100.7"), 0);
        assertNotNull(a.ticket());
        assertNotNull(b.ticket());
        assertEquals("zu_viele", g.enter(ip("198.51.100.7"), 0).refusal());
        assertNotNull(g.enter(ip("198.51.100.8"), 0).ticket(), "andere Adresse ist frei");
        g.release(a.ticket());
        assertNotNull(g.enter(ip("198.51.100.7"), 0).ticket());
    }

    /** Ein /64 gehört einem Anschluss: Wer 2^64 Adressen hat, bekommt trotzdem nur die Grenze eines Anschlusses. */
    @Test
    void ipv6AddressesOfOneSlash64ShareTheirLimit() throws Exception {
        HandshakeGate g = gate(1, 32, 100);
        assertNotNull(g.enter(ip("2001:db8:1:2::1"), 0).ticket());
        assertEquals("zu_viele", g.enter(ip("2001:db8:1:2:ffff:ffff:ffff:9"), 0).refusal());
        assertNotNull(g.enter(ip("2001:db8:1:3::1"), 0).ticket());
        assertEquals(HandshakeGate.key(ip("2001:db8:1:2::1")), HandshakeGate.key(ip("2001:db8:1:2:abcd::1")));
        assertEquals("v4:203.0.113.9", HandshakeGate.key(ip("203.0.113.9")));
    }

    @Test
    void totalLimitCountsAllUnauthenticatedConnections() throws Exception {
        HandshakeGate g = gate(4, 3, 100);
        for (int i = 1; i <= 3; i++) {
            assertNotNull(g.enter(ip("192.0.2." + i), 0).ticket());
        }
        assertEquals("voll", g.enter(ip("192.0.2.99"), 0).refusal());
        assertEquals(3, g.activeCount());
    }

    @Test
    void releasingATicketTwiceFreesItsSlotOnlyOnce() throws Exception {
        HandshakeGate g = gate(4, 2, 100);
        HandshakeGate.Ticket t = g.enter(ip("192.0.2.1"), 0).ticket();
        assertNotNull(g.enter(ip("192.0.2.2"), 0).ticket());
        g.release(t);
        g.release(t);
        assertEquals(1, g.activeCount());
        assertNotNull(g.enter(ip("192.0.2.3"), 0).ticket());
        assertEquals("voll", g.enter(ip("192.0.2.4"), 0).refusal());
    }

    @Test
    void banGrowsExponentiallyFrom10SecondsTo10Minutes() throws Exception {
        HandshakeGate g = gate(4, 32, 100);
        String key = HandshakeGate.key(ip("198.51.100.1"));
        long now = 0;
        long[] expected = {10, 20, 40, 80, 160, 320, 600, 600};
        for (long seconds : expected) {
            g.failure(key, now);
            assertEquals("gesperrt", g.enter(ip("198.51.100.1"), now + seconds * S - 1).refusal(), "Sperre " + seconds);
            HandshakeGate.Admission after = g.enter(ip("198.51.100.1"), now + seconds * S);
            assertNotNull(after.ticket(), "nach " + seconds + " s wieder frei");
            g.release(after.ticket());
            now += seconds * S;
        }
    }

    /** Versuche während der Sperre verlängern sie nicht – sonst käme eine App mit 10-s-Takt nie wieder herein. */
    @Test
    void refusalsDuringABanDoNotExtendIt() throws Exception {
        HandshakeGate g = gate(4, 32, 100);
        g.failure(HandshakeGate.key(ip("198.51.100.2")), 0);
        for (long t = 1; t < 10; t++) {
            assertEquals("gesperrt", g.enter(ip("198.51.100.2"), t * S).refusal());
        }
        assertNotNull(g.enter(ip("198.51.100.2"), 10 * S).ticket());
    }

    @Test
    void successForgetsEarlierFailures() throws Exception {
        HandshakeGate g = gate(4, 32, 100);
        String key = HandshakeGate.key(ip("198.51.100.3"));
        g.failure(key, 0);
        g.failure(key, 0);
        g.success(key);
        assertNotNull(g.enter(ip("198.51.100.3"), 1).ticket());
        g.failure(key, 100 * S);
        assertEquals("gesperrt", g.enter(ip("198.51.100.3"), 109 * S).refusal());
        assertNotNull(g.enter(ip("198.51.100.3"), 110 * S).ticket(), "nach Erfolg wieder ab 10 s");
    }

    @Test
    void banListIsBoundedAndEvictsExpiredEntriesFirst() throws Exception {
        HandshakeGate g = gate(4, 32, 3);
        g.failure(HandshakeGate.key(ip("192.0.2.1")), 0);
        g.failure(HandshakeGate.key(ip("192.0.2.2")), 50 * S);
        g.failure(HandshakeGate.key(ip("192.0.2.3")), 50 * S);
        assertEquals(3, g.size());
        // Bei 20 s ist nur die erste Sperre abgelaufen: Sie macht Platz, die anderen bleiben.
        g.failure(HandshakeGate.key(ip("192.0.2.4")), 20 * S);
        assertEquals(3, g.size());
        assertEquals("gesperrt", g.enter(ip("192.0.2.2"), 21 * S).refusal());
        assertEquals("gesperrt", g.enter(ip("192.0.2.4"), 21 * S).refusal());
        // Nichts abgelaufen: Die am frühesten endende Sperre geht.
        g.failure(HandshakeGate.key(ip("192.0.2.5")), 22 * S);
        assertEquals(3, g.size());
        assertNull(g.enter(ip("192.0.2.4"), 23 * S).refusal(), "früheste Sperre verdrängt");
    }

    @Test
    void quietEntriesAreForgottenAfterAnHour() throws Exception {
        HandshakeGate g = gate(4, 32, 100);
        g.failure(HandshakeGate.key(ip("192.0.2.9")), 0);
        g.cleanup(30 * 60 * S);
        assertEquals(1, g.size());
        g.cleanup(3_700 * S);
        assertEquals(0, g.size());
    }
}
