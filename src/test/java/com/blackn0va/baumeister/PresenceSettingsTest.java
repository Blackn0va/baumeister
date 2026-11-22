package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.presence.PresenceSettings;

import java.io.File;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** stadtleben.kommen-gehen-melden steht in der Vorlage (Vorgabe an), wird gelesen und von ConfigCheck geprüft. */
class PresenceSettingsTest {

    @Test
    void announceKeyIsInTheTemplateReadAndChecked() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(bundled.isBoolean(PresenceSettings.KEY_ANNOUNCE));
        assertTrue(PresenceSettings.from(bundled).announce());
        YamlConfiguration off = new YamlConfiguration();
        off.set(PresenceSettings.KEY_ANNOUNCE, false);
        assertFalse(PresenceSettings.from(off).announce());
        assertTrue(PresenceSettings.from(new YamlConfiguration()).announce(), "fehlt = Vorgabe");
        YamlConfiguration bad = new YamlConfiguration();
        bad.set(PresenceSettings.KEY_ANNOUNCE, "nein");
        List<String> warnings = ConfigCheck.check(bad, bundled);
        assertTrue(warnings.stream().anyMatch(w -> w.contains(PresenceSettings.KEY_ANNOUNCE)), warnings.toString());
    }
}
