package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeafNoticeTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void tellsAtOnceThenOnlyEveryTenMinutes() {
        DeafNotice n = new DeafNotice();
        assertTrue(n.failed(A, 1_000));
        assertFalse(n.failed(A, 2_000));
        assertFalse(n.failed(A, 1_000 + DeafNotice.REPEAT_MS - 1));
        assertTrue(n.failed(A, 1_000 + DeafNotice.REPEAT_MS));
        assertTrue(n.failed(B, 2_000), "jeder Spieler für sich");
    }

    @Test
    void workingRecognitionStartsOver() {
        DeafNotice n = new DeafNotice();
        assertTrue(n.failed(A, 1_000));
        n.reset(A);
        assertTrue(n.failed(A, 5_000), "nach einem gehörten Satz wird der nächste Ausfall gleich gemeldet");
    }
}
