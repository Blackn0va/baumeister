package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Bestätigung von Befehlen aus einem Bauplan (Plan B5): nur der Auftraggeber, genau einmal, höchstens 10 Minuten, je
 * Auftraggeber nur die neueste.
 */
class CommandConfirmationsTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger counter = new AtomicInteger();
    private final CommandConfirmations<String> c = new CommandConfirmations<>(now::get,
            () -> "t" + counter.incrementAndGet());

    @Test
    void onlyTheOwnerCanRedeemAndOnlyOnce() {
        String token = c.offer(ADMIN, "abc", "plan");
        CommandConfirmations.Taken<String> foreign = c.take(token, GUEST);
        assertFalse(foreign.ok(), "fremder Spieler");
        assertTrue(foreign.problem().contains("jemand anderem"), foreign.problem());
        CommandConfirmations.Taken<String> own = c.take(token, ADMIN);
        assertTrue(own.ok(), "der fremde Versuch verbraucht die Bestätigung nicht");
        assertEquals("plan", own.offer().payload());
        assertEquals("abc", own.offer().fingerprint());
        assertFalse(c.take(token, ADMIN).ok(), "genau einmal");
    }

    @Test
    void consoleOffersBelongToTheConsoleOnly() {
        String token = c.offer(null, "abc", "plan");
        assertFalse(c.take(token, ADMIN).ok(), "ein Spieler löst keine Bestätigung der Konsole ein");
        assertTrue(c.take(token, null).ok());
    }

    @Test
    void offersExpireAfterTenMinutes() {
        String token = c.offer(ADMIN, "abc", "plan");
        now.addAndGet(CommandConfirmations.TTL_MILLIS - 1);
        assertEquals(1, c.size());
        now.addAndGet(1);
        assertFalse(c.take(token, ADMIN).ok(), "abgelaufen");
        assertEquals(0, c.size());
    }

    @Test
    void aNewOfferReplacesTheOldOneOfTheSameOwner() {
        String old = c.offer(ADMIN, "alt", "plan 1");
        String other = c.offer(GUEST, "fremd", "plan 2");
        String fresh = c.offer(ADMIN, "neu", "plan 3");
        assertFalse(c.take(old, ADMIN).ok(), "ein alter Klick im Chat bestätigt keinen vergessenen Plan");
        assertTrue(c.take(other, GUEST).ok(), "andere Auftraggeber bleiben unberührt");
        assertEquals("neu", c.take(fresh, ADMIN).offer().fingerprint());
    }

    @Test
    void stopDropsTheOwnersOffer() {
        String token = c.offer(ADMIN, "abc", "plan");
        assertFalse(c.drop(GUEST));
        assertTrue(c.pending(ADMIN).isPresent());
        assertTrue(c.drop(ADMIN));
        assertFalse(c.take(token, ADMIN).ok());
    }

    @Test
    void unknownAndMissingTokensRedeemNothing() {
        c.offer(ADMIN, "abc", "plan");
        assertFalse(c.take("gibt-es-nicht", ADMIN).ok());
        assertFalse(c.take(null, ADMIN).ok());
        assertEquals(1, c.size());
    }

    @Test
    void realTokensAreRandomHex() {
        CommandConfirmations<String> real = new CommandConfirmations<>();
        String a = real.offer(ADMIN, "x", "p");
        String b = real.offer(GUEST, "x", "p");
        assertTrue(a.matches("[0-9a-f]{12}"), a);
        assertFalse(a.equals(b));
    }
}
