package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.dataset.PlanJudge;

import org.junit.jupiter.api.Test;

/**
 * PlanJudge (Lehrset, Messgerät) prüft im selben Scanfenster wie der Server. SiteFrame ist paketprivat, deshalb steht
 * die Gleichheit hier: Wer das Fenster im Plugin ändert, sieht diesen Test rot, statt dass Lehrset und Messung still
 * auf einem anderen Bauplatz urteilen.
 */
class DatasetSiteTest {

    @Test
    void judgeUsesTheScanWindowOfThePlugin() {
        assertEquals(SiteFrame.SCAN_MIN, PlanJudge.SCAN_MIN);
        assertEquals(SiteFrame.SCAN_MAX, PlanJudge.SCAN_MAX);
    }
}
