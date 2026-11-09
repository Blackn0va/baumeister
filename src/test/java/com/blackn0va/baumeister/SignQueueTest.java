package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Schildtexte werden nach dem Block geschrieben (SignWriter): höchstens perRun je Lauf, reihum, nach Bauende mit
 * Gnadenfrist. Ohne Bukkit – der Schreiber ist hier eine Liste von Stellen, an denen „das Schild schon steht“.
 */
class SignQueueTest {

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("s" + i);
        }
        return out;
    }

    @Test
    void writesOnlySignsWhoseBlockStandsAndKeepsTheRest() {
        SignQueue<String> q = new SignQueue<>(16, 1_000);
        q.add(names(3));
        Set<String> standing = new HashSet<>(Set.of("s0", "s2"));
        List<String> written = new ArrayList<>();
        q.run(s -> {
            if (standing.contains(s)) {
                written.add(s);
                return SignQueue.Result.WRITTEN;
            }
            return SignQueue.Result.NOT_READY;
        }, 0);
        assertEquals(List.of("s0", "s2"), written);
        assertEquals(1, q.pending(), "s1 wartet auf seinen Block");
        standing.add("s1");
        q.run(s -> standing.contains(s) ? SignQueue.Result.WRITTEN : SignQueue.Result.NOT_READY, 1);
        assertEquals(0, q.pending());
        assertEquals(3, q.written());
    }

    @Test
    void atMostPerRunAttemptsAndAnUnreadyHeadDoesNotBlock() {
        SignQueue<String> q = new SignQueue<>(16, 1_000);
        q.add(names(40));
        int[] attempts = {0};
        // s0 steht nie: Würde die Schlange am Kopf hängen bleiben, käme sonst keiner dran.
        q.run(s -> {
            attempts[0]++;
            return s.equals("s0") ? SignQueue.Result.NOT_READY : SignQueue.Result.WRITTEN;
        }, 0);
        assertEquals(16, attempts[0], "Versuche je Lauf begrenzt");
        assertEquals(15, q.written());
        for (int i = 0; i < 3; i++) {
            q.run(s -> s.equals("s0") ? SignQueue.Result.NOT_READY : SignQueue.Result.WRITTEN, i + 1);
        }
        assertEquals(39, q.written());
        assertEquals(1, q.pending());
    }

    @Test
    void afterTheBuildUnplacedSignsAreDroppedOnceTheGraceRunsOut() {
        SignQueue<String> q = new SignQueue<>(16, 1_000);
        q.add(names(2));
        q.close(10_000);
        q.run(s -> SignQueue.Result.NOT_READY, 10_500);
        assertEquals(2, q.pending(), "innerhalb der Frist bleibt es");
        q.run(s -> SignQueue.Result.NOT_READY, 11_001);
        assertEquals(0, q.pending());
        assertEquals(2, q.dropped());
        assertTrue(q.closed());
    }

    @Test
    void unknownSignBlockIsDroppedAtOnce() {
        SignQueue<String> q = new SignQueue<>(16, 1_000);
        q.add(names(1));
        q.run(s -> SignQueue.Result.DROP, 0);
        assertEquals(0, q.pending());
        assertEquals(1, q.dropped());
    }
}
