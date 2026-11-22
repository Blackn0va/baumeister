package com.blackn0va.baumeister.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** /bm module an|aus: nur Admins (fail-closed), schreibt den bestehenden Schlüssel, sagt ehrlich, wann es wirkt. */
class ModuleSwitchTest {

    /** config.yml im Speicher; live = welche Module applyLive annimmt. */
    static class FakeTarget implements ModuleSwitch.Target {
        final Map<String, Boolean> config = new HashMap<>(Map.of("stadtleben.an", false, "karte.an", true,
                "sprache.an", false));
        final List<String> writes = new ArrayList<>();
        final List<String> live = new ArrayList<>();

        @Override
        public boolean current(String key) {
            return Boolean.TRUE.equals(config.get(key));
        }

        @Override
        public void write(String key, boolean on) {
            writes.add(key + "=" + on);
            config.put(key, on);
        }

        @Override
        public boolean applyLive(ModuleDef def, boolean on) {
            live.add(def.id() + "=" + on);
            config.put(def.key(), on);
            return true;
        }
    }

    @Test
    void withoutAdminNothingIsWritten() {
        FakeTarget t = new FakeTarget();
        for (String id : List.of("stadtleben", "karte", "gibtsnicht", "bau-ki")) {
            ModuleSwitch.Result r = ModuleSwitch.run(false, "an", id, t);
            assertEquals(ModuleSwitch.Outcome.NO_RIGHT, r.outcome(), id);
            assertTrue(r.lines().getFirst().contains("baumeister.admin"), r.lines().toString());
        }
        ModuleSwitch.run(false, "aus", "karte", t);
        assertTrue(t.writes.isEmpty(), t.writes.toString());
        assertTrue(t.live.isEmpty(), t.live.toString());
        assertFalse(t.current("stadtleben.an"));
    }

    @Test
    void adminSwitchesTownLifeLive() {
        FakeTarget t = new FakeTarget();
        ModuleSwitch.Result r = ModuleSwitch.run(true, "an", "stadtleben", t);
        assertEquals(ModuleSwitch.Outcome.SWITCHED_NOW, r.outcome());
        assertEquals(List.of("stadtleben=true"), t.live);
        assertTrue(r.lines().getFirst().contains("sofort"), r.lines().toString());
        assertTrue(r.changed());
    }

    @Test
    void adminSwitchOfARestartModuleWritesTheKeyAndSaysRestart() {
        FakeTarget t = new FakeTarget();
        ModuleSwitch.Result r = ModuleSwitch.run(true, "aus", "karte", t);
        assertEquals(ModuleSwitch.Outcome.SWITCHED_AFTER_RESTART, r.outcome());
        assertEquals(List.of("karte.an=false"), t.writes);
        assertTrue(t.live.isEmpty(), "karte wirkt nicht im Lauf: " + t.live);
        assertTrue(r.lines().getFirst().contains("Neustart"), r.lines().toString());
    }

    @Test
    void unknownFixedAndUnchangedModulesWriteNothing() {
        FakeTarget t = new FakeTarget();
        assertEquals(ModuleSwitch.Outcome.UNKNOWN, ModuleSwitch.run(true, "an", "gibtsnicht", t).outcome());
        assertEquals(ModuleSwitch.Outcome.NO_SWITCH, ModuleSwitch.run(true, "aus", "bau-ki", t).outcome());
        assertEquals(ModuleSwitch.Outcome.UNCHANGED, ModuleSwitch.run(true, "an", "karte", t).outcome());
        assertEquals(ModuleSwitch.Outcome.USAGE, ModuleSwitch.run(true, "an", null, t).outcome());
        assertEquals(ModuleSwitch.Outcome.USAGE, ModuleSwitch.run(true, "umschalten", "karte", t).outcome());
        assertTrue(t.writes.isEmpty() && t.live.isEmpty());
    }

    @Test
    void liveSwitchThatFailsFallsBackToWritingWithRestartHint() {
        FakeTarget t = new FakeTarget() {
            @Override
            public boolean applyLive(ModuleDef def, boolean on) {
                return false;
            }
        };
        ModuleSwitch.Result r = ModuleSwitch.run(true, "an", "stadtleben", t);
        assertEquals(ModuleSwitch.Outcome.SWITCHED_AFTER_RESTART, r.outcome());
        assertEquals(List.of("stadtleben.an=true"), t.writes);
    }
}
