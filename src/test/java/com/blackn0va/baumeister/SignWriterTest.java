package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * Herunterfahren: onDisable → CityService.shutdown → CityJob.cancel → finish → SignWriter.close. Das Plugin ist dann
 * schon abgeschaltet; ein neuer Takt würfe IllegalPluginAccessException mitten in finish() (vor dem Abschluss des
 * Rückgängig-Speichers). Ohne Server: Bukkit.getScheduler() gäbe hier gar null – jeder Versuch, einen Takt anzumelden,
 * scheitert also sofort.
 *
 * Bauende (Prüfbefund 2026-10-29): finish() gibt direkt nach close() die Chunk-Tickets frei. Die Schilder der letzten
 * Abschnitte müssen ihren Text deshalb schon in close() bekommen, nicht erst im nächsten Takt. Welt, Block und Schild
 * sind hier Proxys über die Bukkit-Schnittstellen – jeder Aufruf außerhalb der erwarteten scheitert laut.
 */
class SignWriterTest {

    /** enabled[0] = Plugin an; im Test umschaltbar. */
    private static Plugin plugin(boolean[] enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled[0];
                    case "getName" -> "Baumeister";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Baumeister (" + (enabled[0] ? "an" : "aus") + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Plugin disabledPlugin() {
        return plugin(new boolean[] {false});
    }

    /** BlockData-Attrappe: gleich nur mit sich selbst, Name zum Lesen der Meldungen. */
    private static BlockData data(String name) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString", "getAsString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Ein Schild mit Text je Seite; waxed[0] = gewachst, updates[0] = Zahl der update()-Aufrufe. */
    private record FakeSign(Map<Side, String[]> text, boolean[] waxed, int[] updates) {

        FakeSign() {
            this(new HashMap<>(), new boolean[1], new int[1]);
        }

        Sign proxy(boolean broken) {
            return (Sign) Proxy.newProxyInstance(Sign.class.getClassLoader(), new Class<?>[] {Sign.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getSide" -> {
                            if (broken) {
                                throw new IllegalStateException("Blockentität kaputt");
                            }
                            Side side = (Side) args[0];
                            String[] lines = text.computeIfAbsent(side, k -> new String[4]);
                            yield Proxy.newProxyInstance(SignSide.class.getClassLoader(),
                                    new Class<?>[] {SignSide.class}, (p2, m2, a2) -> {
                                        if (m2.getName().equals("setLine")) {
                                            lines[(int) a2[0]] = (String) a2[1];
                                            return null;
                                        }
                                        throw new UnsupportedOperationException(m2.getName());
                                    });
                        }
                        case "setWaxed" -> {
                            waxed[0] = (boolean) args[0];
                            yield null;
                        }
                        case "update" -> {
                            updates[0]++;
                            yield true;
                        }
                        case "hashCode" -> 2;
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Schild";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** Welt mit genau einem Schild an (x, y, z), alle Chunks geladen. */
    private static World world(int x, int y, int z, BlockData signData, Sign sign) {
        BlockData air = data("minecraft:air");
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> true;
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "getHighestBlockYAt" -> y;
                    case "getBlockAt" -> {
                        boolean here = args.length == 3 && (int) args[0] == x && (int) args[1] == y
                                && (int) args[2] == z;
                        yield Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                                (p2, m2, a2) -> switch (m2.getName()) {
                                    case "getBlockData" -> here ? signData : air;
                                    case "getState" -> {
                                        if (!here) {
                                            throw new UnsupportedOperationException("getState auf Luft");
                                        }
                                        yield sign;
                                    }
                                    default -> throw new UnsupportedOperationException(m2.getName());
                                });
                    }
                    case "hashCode" -> 3;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Welt";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Welt, in der kein Chunk geladen ist – das Schild wartet, SignWriter lädt nie selbst. */
    private static World unloadedWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> false;
                    case "hashCode" -> 4;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Welt ohne Chunks";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void closingWhileThePluginShutsDownRegistersNoTask() {
        // Chunk nicht geladen: Das Schild bleibt nach dem Durchgang in close() in der Schlange – ensureTask() darf bei
        // abgeschaltetem Plugin trotzdem keinen Takt anmelden.
        SignWriter w = new SignWriter(disabledPlugin(), unloadedWorld(), 0, 64, 0, false);
        CityPlanner.Sign s = new CityPlanner.Sign(1, 3, 1, "birch_sign[rotation=0]", List.of("Hauptstraße"));
        assertDoesNotThrow(() -> w.add(List.of(s)));
        assertDoesNotThrow(w::close);
    }

    @Test
    void signsOfTheLastSectionsGetTheirTextAtCloseWhileTheChunksAreStillHeld() {
        BlockData signData = data("minecraft:birch_sign[rotation=4]");
        FakeSign sign = new FakeSign();
        // Bauplatz bei (100, 64, 200); das Schild steht relativ bei (5, 3, −7).
        World world = world(105, 67, 193, signData, sign.proxy(false));
        List<String> asked = new ArrayList<>();
        boolean[] enabled = {false};
        SignWriter w = new SignWriter(plugin(enabled), world, 100, 64, 200, false, b -> {
            asked.add(b);
            return signData;
        });
        // add() bei abgeschaltetem Plugin: kein Takt (ohne Server gäbe es keinen Scheduler). Danach AN: Bliebe nach
        // close() etwas in der Schlange, meldete ensureTask einen Takt an – Bukkit.getScheduler() ist hier null, der
        // Test würde rot.
        w.add(List.of(new CityPlanner.Sign(5, 3, -7, "birch_sign[rotation=4]", List.of("Kurfürsten-", "straße"))));
        enabled[0] = true;
        w.close();
        assertEquals(List.of("minecraft:birch_sign[rotation=4]"), asked);
        for (Side side : Side.values()) {
            String[] lines = sign.text().get(side);
            assertTrue(lines != null, "Seite ohne Text: " + side);
            assertEquals(List.of("Kurfürsten-", "straße", "", ""), List.of(lines), "Text auf " + side);
        }
        assertTrue(sign.waxed()[0], "nicht gewachst");
        assertEquals(1, sign.updates()[0]);
    }

    @Test
    void aBrokenSignAtCloseDoesNotAbortTheBuildsFinish() {
        BlockData signData = data("minecraft:birch_sign[rotation=0]");
        World world = world(1, 67, 1, signData, new FakeSign().proxy(true));
        SignWriter w = new SignWriter(disabledPlugin(), world, 0, 64, 0, false, b -> signData);
        // Würfe close() hier, übersprange CityJob.finish() das Freigeben der Tickets und den Rückgängig-Abschluss.
        w.add(List.of(new CityPlanner.Sign(1, 3, 1, "birch_sign[rotation=0]", List.of("Hauptstraße"))));
        assertDoesNotThrow(w::close);
    }
}
