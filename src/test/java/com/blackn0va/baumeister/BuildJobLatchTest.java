package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Repeater;
import org.junit.jupiter.api.Test;

/** 2026-11-14: Speicher eines frischen Baus beginnen bei 0 (akkumulator_4bit nach Süden startete mit 8). */
class BuildJobLatchTest {

    private static BlockData repeater(boolean locked, boolean powered) {
        return (BlockData) Proxy.newProxyInstance(Repeater.class.getClassLoader(), new Class<?>[] {Repeater.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "isLocked" -> locked;
                    case "isPowered" -> powered;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static BlockData other() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
                (proxy, m, args) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void clearsOnlyLockedRepeatersThatAreOn() {
        assertTrue(BuildJob.frozenHigh(repeater(true, true)));
        // Offen: folgt seinem Eingang, Minecraft rechnet ihn selbst nach – nicht anfassen.
        assertFalse(BuildJob.frozenHigh(repeater(false, true)));
        assertFalse(BuildJob.frozenHigh(repeater(true, false)));
        assertFalse(BuildJob.frozenHigh(repeater(false, false)));
        assertFalse(BuildJob.frozenHigh(other()));
    }
}
