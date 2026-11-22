package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.blackn0va.baumeister.town.TownSettings;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Konfiguration des Stadtlebens: Vorgaben = Vorlage, Grenzen = ConfigCheck.RANGES (Vertrag v008, AK-22/AK-26). */
class TownSettingsTest {

    @Test
    void templateEqualsCodeDefaultsAndIsOff() throws Exception {
        TownSettings fromTemplate = TownSettings.from(ConfigMigrationTest.bundled());
        assertEquals(TownSettings.defaults(), fromTemplate);
        assertEquals(TownSettings.from(new YamlConfiguration()), fromTemplate);
        assertFalse(fromTemplate.enabled());
        assertEquals(4, fromTemplate.count());
    }

    @Test
    void clampsMatchConfigCheckRanges() {
        ConfigCheck.Range count = ConfigCheck.RANGES.get(TownSettings.KEY_COUNT);
        ConfigCheck.Range gap = ConfigCheck.RANGES.get(TownSettings.KEY_GAP);
        assertEquals((int) count.min(), at(-5).count());
        assertEquals((int) count.max(), at(99).count());
        assertEquals((long) gap.min() * 1000, gap(1).talkGapMillis());
        assertEquals((long) gap.max() * 1000, gap(99_999).talkGapMillis());
    }

    private static TownSettings at(int n) {
        YamlConfiguration c = new YamlConfiguration();
        c.set(TownSettings.KEY_COUNT, n);
        return TownSettings.from(c);
    }

    private static TownSettings gap(int s) {
        YamlConfiguration c = new YamlConfiguration();
        c.set(TownSettings.KEY_GAP, s);
        return TownSettings.from(c);
    }
}
