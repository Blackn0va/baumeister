package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.work.Field;
import com.blackn0va.baumeister.town.work.FieldJob;
import com.blackn0va.baumeister.town.work.FieldWork;
import com.blackn0va.baumeister.town.work.Task;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Der echte Bukkit-Weg der Feldarbeit (TownWork.hands) mit Proxy-Welt und -Block: Die Ereignisse sind die
 * Rechteprüfung (WorldGuard, PlotSquared; fail-closed, ASVS V4.1.5). Abgesagt, canBuild=false oder beim Fragen
 * geworfen → kein Block geändert, keine Ernte. Vorher (Gegenprobe des Dirigenten T-035) blieb
 * `if (e.isCancelled())` → `if (false)` in allen Tests grün, weil FieldWork nur Fake-Hände kannte.
 */
class TownWorkTest {

    /** Ein Block mit veränderlichem Typ; zählt Abbau, Setzen und abgeworfene Gegenstände. */
    static final class FakeBlock {
        Material type;
        final List<String> calls = new ArrayList<>();
        final List<ItemStack> dropped = new ArrayList<>();
        final World world;
        /** Nicht final: Der Welt-Proxy verweist schon im Konstruktor auf ihn (getBlockAt). */
        Block proxy;

        FakeBlock(Material start) {
            type = start;
            world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getBlockAt" -> this.proxy;
                        case "isChunkLoaded" -> false;
                        case "dropItemNaturally" -> {
                            dropped.add((ItemStack) a[1]);
                            yield null;
                        }
                        case "getName" -> "testwelt";
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        default -> TownLifeTest.none(m.getReturnType());
                    });
            proxy = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getType" -> type;
                        case "setType" -> {
                            calls.add("setType " + a[0]);
                            type = (Material) a[0];
                            yield null;
                        }
                        case "getState" -> state(type);
                        case "getRelative" -> p;
                        case "getWorld" -> world;
                        case "getLocation" -> new Location(world, 1, 64, 1);
                        case "getDrops" -> List.of(new ItemStack(Material.WHEAT),
                                new ItemStack(Material.WHEAT_SEEDS, 2));
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        default -> TownLifeTest.none(m.getReturnType());
                    });
        }

        /** Schnappschuss wie Block.getState: update(…) stellt den Typ von damals wieder her. */
        private BlockState state(Material then) {
            return (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                    new Class<?>[] {BlockState.class}, (p, m, a) -> switch (m.getName()) {
                        case "update" -> {
                            calls.add("zurück " + then);
                            type = then;
                            yield true;
                        }
                        case "getType" -> then;
                        default -> TownLifeTest.none(m.getReturnType());
                    });
        }
    }

    private static final Field FIELD = new Field("acker", "testwelt", 0, 64, 0, 6, null);

    private static Player actor() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> "Admin";
                    case "getUniqueId" -> UUID.nameUUIDFromBytes(new byte[] {1});
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    private static FieldWork.Hands hands(FakeBlock b, Consumer<Event> call, List<Event> seen) {
        return TownWork.hands(b.world, actor(), FIELD, FieldJob.FARMER, e -> {
            seen.add(e);
            call.accept(e);
        }, line -> { });
    }

    private static final Task.Change HARVEST = new Task.Change(Task.Kind.BREAK, 1, 64, 1, "wheat", "air");
    private static final Task.Change SOW = new Task.Change(Task.Kind.PLACE, 1, 64, 1, "air", "wheat");

    private static final Consumer<Event> ALLOW = e -> { };
    private static final Consumer<Event> CANCEL = e -> ((Cancellable) e).setCancelled(true);
    private static final Consumer<Event> THROW = e -> {
        throw new IllegalStateException("Schutz-Plugin abgestürzt");
    };

    @Test
    void allowedHarvestBreaksTheBlockAndDropsTheCrop() {
        FakeBlock b = new FakeBlock(Material.WHEAT);
        List<Event> seen = new ArrayList<>();
        assertTrue(hands(b, ALLOW, seen).breakBlock(HARVEST));
        assertTrue(seen.getFirst() instanceof BlockBreakEvent, "kein BlockBreakEvent");
        assertEquals(Material.AIR, b.type);
        // Eine Saat geht ins Nachpflanzen: Weizen + 1 von 2 Samen fallen (ohne Kiste) zu Boden.
        assertEquals(2, b.dropped.size(), "abgeworfen");
    }

    @Test
    void cancelledBreakChangesNothingAndDropsNothing() {
        FakeBlock b = new FakeBlock(Material.WHEAT);
        assertFalse(hands(b, CANCEL, new ArrayList<>()).breakBlock(HARVEST));
        assertEquals(Material.WHEAT, b.type);
        assertTrue(b.calls.isEmpty(), b.calls.toString());
        assertTrue(b.dropped.isEmpty(), "Ernte trotz Verbot");
    }

    @Test
    void breakIsDeniedWhenAskingThrows() {
        FakeBlock b = new FakeBlock(Material.WHEAT);
        assertFalse(hands(b, THROW, new ArrayList<>()).breakBlock(HARVEST));
        assertEquals(Material.WHEAT, b.type);
        assertTrue(b.dropped.isEmpty());
    }

    @Test
    void allowedPlaceFiresTheEventAfterSettingAndKeepsTheBlock() {
        FakeBlock b = new FakeBlock(Material.AIR);
        List<Event> seen = new ArrayList<>();
        Material[] atEvent = new Material[1];
        assertTrue(hands(b, e -> atEvent[0] = b.type, seen).place(SOW));
        assertTrue(seen.getFirst() instanceof BlockPlaceEvent, "kein BlockPlaceEvent");
        assertEquals(Material.WHEAT, atEvent[0], "Ereignis vor dem Setzen – CoreProtect läse Luft");
        assertEquals(Material.WHEAT, b.type);
        assertFalse(b.calls.stream().anyMatch(c -> c.startsWith("zurück")), b.calls.toString());
    }

    @Test
    void cancelledPlaceIsUndone() {
        FakeBlock b = new FakeBlock(Material.AIR);
        assertFalse(hands(b, CANCEL, new ArrayList<>()).place(SOW));
        assertEquals(Material.AIR, b.type, b.calls.toString());
    }

    @Test
    void placeWithoutBuildRightIsUndone() {
        FakeBlock b = new FakeBlock(Material.AIR);
        assertFalse(hands(b, e -> ((BlockPlaceEvent) e).setBuild(false), new ArrayList<>()).place(SOW));
        assertEquals(Material.AIR, b.type, b.calls.toString());
    }

    @Test
    void placeIsUndoneWhenAskingThrows() {
        FakeBlock b = new FakeBlock(Material.AIR);
        assertFalse(hands(b, THROW, new ArrayList<>()).place(SOW));
        assertEquals(Material.AIR, b.type, b.calls.toString());
    }
}
