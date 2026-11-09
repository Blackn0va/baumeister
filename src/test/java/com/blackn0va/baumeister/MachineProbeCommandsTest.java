package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Pos;

import org.junit.jupiter.api.Test;

/**
 * Befehle der Maschinenprobe treffen auf Paper mit EssentialsX und auf Spigot den Vanilla-Befehl (2026-11-07: auf der
 * 26.2-Kopie von dev01 überschreibt EssentialsX item, kill, time und weather – die Weizenfarm galt dort als kaputt,
 * weil ihre Spender leer blieben; „execute … run minecraft:kill“ lehnt Spigot ab).
 */
class MachineProbeCommandsTest {

    @Test
    void prefixesOnlyTheCommandItself() {
        assertEquals("minecraft:item replace block 1 2 3 container.0 with minecraft:water_bucket",
                MachineProbe.vanilla("item replace block 1 2 3 container.0 with minecraft:water_bucket"));
        assertEquals("minecraft:time query gametime", MachineProbe.vanilla("time query gametime"));
        // Hinter „run“ bleibt es, wie es ist – Spigot kennt dort keinen Namensraum.
        assertEquals("minecraft:execute positioned 1 2 3 run setblock 1 2 3 minecraft:air",
                MachineProbe.vanilla("execute positioned 1 2 3 run setblock 1 2 3 minecraft:air"));
    }

    @Test
    void leavesNamespacedPluginAndNbtTextAlone() {
        assertEquals("minecraft:kill @e", MachineProbe.vanilla("minecraft:kill @e"));
        assertEquals("bm status", MachineProbe.vanilla("bm status"));
        assertEquals("minecraft:summon minecraft:item 3 4 5 {Item:{id:\"minecraft:egg\",count:1}}",
                MachineProbe.vanilla("summon minecraft:item 3 4 5 {Item:{id:\"minecraft:egg\",count:1}}"));
    }

    @Test
    void selectorPositionBecomesTheWorldBlockCentre() {
        // Richtung Nord, Ecke (0,120,0): lokal (1,2,1) ist Weltblock (1,122,-1) – wie im Lauf des Bruchsteingenerators.
        Orientation north = new Orientation(Orientation.Cardinal.NORTH);
        assertEquals("kill @e[x=1.5,y=122,z=-0.5,type=minecraft:chicken,distance=..2]",
                MachineProbe.localToWorld("kill @e[{@1,2,1},type=minecraft:chicken,distance=..2]", new Pos(0, 120, 0),
                        north));
        assertEquals("setblock 1 122 -1 minecraft:air",
                MachineProbe.localToWorld("setblock {1,2,1} minecraft:air", new Pos(0, 120, 0), north));
    }

    @Test
    void sleepWaitNamesSecondsAndEveryBed() {
        // Eisenfarm 2026-11-08: Die Probe wartet auf echten Schlaf statt last_slept vorzugeben.
        MachineProbe.SleepWait w = MachineProbe.sleepWait("#schlaf 90 {1,10,12} {2,10,12} {3,10,12}");
        assertEquals(90, w.seconds());
        assertEquals(java.util.List.of(new Pos(1, 10, 12), new Pos(2, 10, 12), new Pos(3, 10, 12)), w.beds());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MachineProbe.sleepWait("#schlaf 90"));
    }
}
