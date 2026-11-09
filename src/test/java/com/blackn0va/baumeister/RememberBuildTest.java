package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Snbt;

import java.util.List;
import java.util.UUID;

import org.enginehub.linbus.tree.LinByteTag;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinFloatTag;
import org.enginehub.linbus.tree.LinIntArrayTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.jupiter.api.Test;

/**
 * Veras „merk dir das Bauwerk“ und Bauteil-Dateien (Plan B6): Bereichsprüfung fail-closed, Namen mit Besitzer,
 * Blockdaten aus WorldEdit als Baum.
 */
class RememberBuildTest {

    @Test
    void areaCheckVisitsEveryCellWhenAllIsAllowed() {
        int[] seen = {0};
        AreaCheck check = new AreaCheck(2, 64, 2, 0, 63, 0, (x, y, z) -> {
            seen[0]++;
            return true;
        });
        int steps = TimeSlice.run(() -> 0L, 1_000L, 1, 1000, check::step);
        assertTrue(check.done());
        assertTrue(check.allowed());
        assertEquals(3 * 2 * 3, seen[0]);
        assertEquals(18, steps);
    }

    /** Ein einziger Block im Bereich, an dem der Spieler nicht bauen darf, verhindert das Merken (fail-closed). */
    @Test
    void areaCheckStopsAtTheFirstForbiddenBlockAndNamesIt() {
        AreaCheck check = new AreaCheck(0, 0, 0, 4, 4, 4, (x, y, z) -> !(x == 3 && y == 1 && z == 2));
        while (check.step()) {
            // weiter, bis fertig oder verweigert
        }
        assertTrue(check.done());
        assertFalse(check.allowed());
        assertArrayEquals(new int[] {3, 1, 2}, check.deniedAt());
        assertEquals(25 + 2 * 5 + 3 + 1, check.checked(), "keine Zelle nach dem Nein");
    }

    @Test
    void areaCheckIsNotAllowedBeforeItIsDone() {
        AreaCheck check = new AreaCheck(0, 0, 0, 9, 9, 9, (x, y, z) -> true);
        check.step();
        assertFalse(check.allowed(), "halb geprüft ist nicht erlaubt");
    }

    @Test
    void savedNamesBelongToTheirOwner() {
        UUID heiko = UUID.randomUUID();
        UUID gast = UUID.randomUUID();
        assertNull(SchematicLibrary.overwriteProblem("turm", false, null, null, gast, false), "neuer Name");
        assertNull(SchematicLibrary.overwriteProblem("turm", true, heiko.toString(), "Heiko", heiko, false),
                "eigener Name");
        assertTrue(SchematicLibrary.overwriteProblem("turm", true, heiko.toString(), "Heiko", gast, false)
                .contains("Heiko"), "fremder Name");
        assertTrue(SchematicLibrary.overwriteProblem("turm", true, null, null, gast, false).contains("Server"),
                "alter Eintrag ohne Besitzer");
        assertNull(SchematicLibrary.overwriteProblem("turm", true, heiko.toString(), "Heiko", gast, true), "Admin");
    }

    @Test
    void worldEditDataBecomesTheSameTreeTheParserWouldBuild() {
        LinCompoundTag tag = LinCompoundTag.builder()
                .put("Command", LinStringTag.of("say \"hallo\" \\ welt"))
                .put("auto", LinByteTag.of((byte) 1))
                .put("pos", LinIntArrayTag.of(1, 2, 3))
                .put("speed", LinFloatTag.of(0.5f))
                .put("lines", LinListTag.of(LinTagType.stringTag(), List.of(LinStringTag.of("a"), LinStringTag.of("b"))))
                .build();
        assertEquals("{Command:\"say \\\"hallo\\\" \\\\ welt\",auto:1b,pos:[I;1,2,3],speed:0.5f,lines:[\"a\",\"b\"]}",
                Snbt.write(SchematicLibrary.toNbt(tag)));
    }

    /**
     * Befund 4: Bauteil-Daten nur mit dem Vermerk eines Admins. Altbestand (vor 2026-11-08 für jeden gespeichert) und
     * Einträge von Nicht-Admins laden nur Zustände.
     */
    @Test
    void moduleDataLoadsOnlyWithTheAdminMark() {
        assertTrue(SchematicLibrary.trustsData("admin"));
        assertFalse(SchematicLibrary.trustsData(null), "Altbestand ohne Vermerk");
        assertFalse(SchematicLibrary.trustsData("Admin"));
        assertFalse(SchematicLibrary.trustsData("ohne"));
    }

    /** Sicherheitsprüfung 2026-11-08, Befund 2: Die Größe steht fest, bevor ein einziger Block gefragt wird. */
    @Test
    void oversizedAreasAreRefusedBeforeAnyBlockIsAsked() {
        assertNull(SchematicLibrary.sizeProblem(32, 32, 32));
        assertTrue(SchematicLibrary.sizeProblem(33, 1, 1).contains("höchstens 32"));
        assertTrue(SchematicLibrary.sizeProblem(1, 1, 20_001).contains("20001"));
    }

    /**
     * Befund 5: Die stille Bereichsprüfung merkt sich das Urteil der Schutz-Plugins und bricht das Ereignis dann ab –
     * CoreProtect (MONITOR, ignoreCancelled) sieht es nie. Gewöhnliche Abbau-Ereignisse bleiben unberührt.
     */
    @Test
    void quietProbesKeepTheVerdictAndHideTheEventFromLoggers() {
        org.bukkit.block.Block block = proxy(org.bukkit.block.Block.class);
        // Kein Player-Proxy: Player verweist auf Bungee-Klassen, die im Test fehlen; das Ereignis prüft null nicht.
        org.bukkit.entity.Player player = null;
        RegionCheck.QuietProbes probes = new RegionCheck.QuietProbes();
        RegionCheck.ProbeBreakEvent allowed = new RegionCheck.ProbeBreakEvent(block, player);
        probes.onProbe(allowed);
        assertEquals(Boolean.TRUE, allowed.verdict());
        assertTrue(allowed.isCancelled(), "abgebrochen, damit MONITOR-Hörer es überspringen");
        RegionCheck.ProbeBreakEvent denied = new RegionCheck.ProbeBreakEvent(block, player);
        denied.setCancelled(true);  // WorldGuard hat abgelehnt
        probes.onProbe(denied);
        assertEquals(Boolean.FALSE, denied.verdict());
        org.bukkit.event.block.BlockBreakEvent real = new org.bukkit.event.block.BlockBreakEvent(block, player);
        probes.onProbe(real);
        assertFalse(real.isCancelled(), "echter Abbau bleibt, wie er ist");
        assertNull(new RegionCheck.ProbeBreakEvent(block, player).verdict(), "ohne Hörer: kein Urteil");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == int.class ? 0 : null);
    }
}
