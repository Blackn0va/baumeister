package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** Tick-Budget des Stadtbaus: gesetzte Blöcke zählen voll, gleiche Zellen ein Viertel (einstellbar). */
class BuildBudgetTest {

    /** Wie CityJob.work: Zellen anfassen, solange Budget da ist; jede n-te wird gesetzt. */
    private static int cellsInOneTick(BuildBudget b, double setShare) {
        int cells = 0;
        double acc = 0;
        while (b.left()) {
            b.touched();
            acc += setShare;
            if (acc >= 1) {
                acc -= 1;
                b.placed();
            }
            cells++;
        }
        return cells;
    }

    private static int cellsInOneTick(int blocks, double setShare) {
        return cellsInOneTick(new BuildBudget(blocks), setShare);
    }

    @Test
    void unchangedCellsCostOnlyAQuarter() {
        // Gemessen 2026-10-29 (Bad Kreuznach 400 m): 54 % der Zellen gesetzt, 46 % schon richtig. Vorher je Zelle eine
        // Einheit: 2 000 Zellen je Tick. Jetzt mehr Zellen bei gleicher Zahl gesetzter Blöcke.
        int cells = cellsInOneTick(2000, 0.54);
        assertTrue(cells >= 3000, "Zellen je Tick bei 46 % gleichen: " + cells);
    }

    @Test
    void placedBlocksStillCostTheFullBudget() {
        // Werden alle Zellen gesetzt, bleibt es bei „Blöcke je Tick“ – das Budget wird nicht großzügiger.
        assertEquals(2000, cellsInOneTick(2000, 1.0));
    }

    @Test
    void secondRunOverFinishedCityIsFourTimesFaster() {
        assertEquals(2000 * BuildBudget.DEFAULT_SAME_PER_BLOCK, cellsInOneTick(2000, 0.0));
    }

    @Test
    void tinyBudgetStillMakesProgress() {
        assertTrue(cellsInOneTick(0, 1.0) >= 1, "Budget 0 (Helfer > Budget) darf nicht stehen bleiben");
    }

    @Test
    void ratioOneRestoresTheOldCellBudget() {
        // Prüfbefund 2026-10-29: Das Verhältnis 1:4 ist auf dev01 (Celeron) nicht gemessen. Mit 1 muss exakt das alte
        // Verhalten gelten – je Zelle eine Einheit, egal ob gesetzt –, sonst taugt der Schalter nicht als Rückweg.
        assertEquals(500, cellsInOneTick(new BuildBudget(500, 1), 0.0), "über fertigem Gelände");
        assertEquals(500, cellsInOneTick(new BuildBudget(500, 1), 0.54), "gemischt");
        assertEquals(500, cellsInOneTick(new BuildBudget(500, 1), 1.0), "alles neu");
        assertEquals(1000, cellsInOneTick(new BuildBudget(500, 2), 0.0), "Verhältnis 2");
    }

    @Test
    void ratioComesFromConfigWithDefaultForOldConfigs() {
        // Die config.yml auf dev01 wird nicht ersetzt: ohne Schlüssel gilt die Vorgabe aus dem Code.
        assertEquals(BuildBudget.DEFAULT_SAME_PER_BLOCK, BuildBudget.samePerBlock(new MemoryConfiguration()));
        assertEquals(BuildBudget.DEFAULT_SAME_PER_BLOCK, BuildBudget.samePerBlock(null));
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set(BuildBudget.CONFIG_KEY, 1);
        assertEquals(1, BuildBudget.samePerBlock(cfg));
        cfg.set(BuildBudget.CONFIG_KEY, 0);
        assertEquals(1, BuildBudget.samePerBlock(cfg), "0 hieße: gleiche Zellen kosten nichts – nie ohne Ende");
        cfg.set(BuildBudget.CONFIG_KEY, 99);
        assertEquals(BuildBudget.MAX_SAME_PER_BLOCK, BuildBudget.samePerBlock(cfg));
    }
}
