package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BlockTiers;
import com.blackn0va.baumeister.plan.Tier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Stufen beim Setzen (Plan B1/B6): Rechte im Moment des Setzens, Überschreiben braucht die Stufe des alten Blocks,
 * Paare und Bauteile ganz oder gar nicht.
 */
class PlacementGateTest {

    /** Zelle mit fester Stufe; removal steht für den Block, der beim Fragen dort steht. */
    private record Cell(Tier place, String placeId, String standing, int group) implements PlacementGate.Cell {

        Cell(Tier place, String placeId, String standing) {
            this(place, placeId, standing, 0);
        }

        static Cell of(String block, String standing) throws Exception {
            BlockTiers.Verdict v = BlockTiers.place(BlockSpec.parseTrusted(block), BlockTiers.WorldFacts.OVERWORLD);
            return new Cell(v.tier(), v.reason(), standing);
        }

        /** Dieselbe Zelle als Teil des Bauteils g. */
        Cell inGroup(int g) {
            return new Cell(place, placeId, standing, g);
        }

        @Override
        public Tier placeTier() {
            return place;
        }

        @Override
        public String placeReason() {
            return placeId;
        }

        @Override
        public Tier removalTier() {
            return BlockTiers.removal(standing).tier();
        }

        @Override
        public String removalReason() {
            return BlockTiers.removal(standing).reason();
        }
    }

    private static final BuildRights PLAIN = BuildRights.player(false, false, false, false, true);
    private static final BuildRights ADMIN = BuildRights.player(true, true, true, false, true);

    private static PlacementGate.Decision once(BuildRights r, PlacementGate.Cell... cells) {
        return new PlacementGate(() -> r).action(List.of(cells), g -> List.of());
    }

    @Test
    void consoleMayEverythingOfflinePlayersOnlyWhatEveryoneMay() {
        for (Tier t : Tier.values()) {
            assertTrue(BuildRights.CONSOLE.allows(t), t.name());
        }
        assertEquals(List.of(Tier.EVERYONE), BuildRights.OFFLINE.allowed());
    }

    @Test
    void creativeCountsOnlyForContentsAndOnlyInTheWorldOfTheBuild() {
        assertTrue(BuildRights.player(false, false, false, true, true).allows(Tier.CONTENTS));
        assertFalse(BuildRights.player(false, false, false, true, false).allows(Tier.CONTENTS), "andere Welt");
        assertFalse(BuildRights.player(false, false, false, true, true).allows(Tier.DANGEROUS));
        assertTrue(BuildRights.player(true, false, false, false, false).allows(Tier.CONTENTS), "Recht reicht");
    }

    @Test
    void higherTiersIncludeTheLowerOnes() {
        BuildRights commands = BuildRights.player(false, false, true, false, false);
        assertEquals(List.of(Tier.values()), commands.allowed());
        BuildRights dangerous = BuildRights.player(false, true, false, false, false);
        assertEquals(List.of(Tier.EVERYONE, Tier.CONTENTS, Tier.DANGEROUS), dangerous.allowed());
        assertEquals(List.of(Tier.EVERYONE), PLAIN.allowed());
    }

    @Test
    void nonAdminsMayNotRemoveBedrockOrOverwriteAnAdminsCommandBlock() throws Exception {
        PlacementGate.Decision bedrock = once(PLAIN, Cell.of("air", "bedrock"));
        assertFalse(bedrock.allowed(), "Luft ist kein Freibrief");
        assertEquals(Tier.DANGEROUS, bedrock.missing());
        PlacementGate.Decision command = once(PLAIN, Cell.of("stone", "command_block"));
        assertFalse(command.allowed());
        assertEquals(Tier.COMMANDS, command.missing());
        assertTrue(once(ADMIN, Cell.of("air", "bedrock")).allowed());
        assertTrue(once(PLAIN, Cell.of("air", "lava")).allowed(), "Lava zuschütten darf jeder");
        assertTrue(once(PLAIN, Cell.of("stone", "grass_block")).allowed());
    }

    @Test
    void dangerousAndCommandBlocksNeedTheirTier() throws Exception {
        assertEquals(Tier.DANGEROUS, once(PLAIN, Cell.of("lava", "air")).missing());
        assertEquals(Tier.COMMANDS, once(PLAIN, Cell.of("command_block", "air")).missing());
        assertEquals(Tier.CONTENTS, once(PLAIN, Cell.of("chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}",
                "air")).missing());
        assertTrue(once(ADMIN, Cell.of("command_block{Command:\"say hi\"}", "air")).allowed());
    }

    /**
     * Auftrag im Kreativmodus eingereiht, Kreativ verlassen, dann dran: keine Inhalte. Die Rechte liest das Tor bei
     * jeder Entscheidung, nicht beim Auftrag (Plan B1).
     */
    @Test
    void queuedOrderLosesContentsWhenCreativeIsGoneBeforePlacement() throws Exception {
        AtomicReference<BuildRights> now = new AtomicReference<>(BuildRights.player(false, false, false, true, true));
        PlacementGate gate = new PlacementGate(now::get);
        Cell chest = Cell.of("chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}", "air");
        now.set(BuildRights.player(false, false, false, false, true));
        PlacementGate.Decision d = gate.action(List.of(chest), g -> List.of());
        assertFalse(d.allowed(), "Kreativ beim Setzen weg");
        assertEquals(Tier.CONTENTS, d.missing());
        now.set(BuildRights.player(false, false, false, true, true));
        assertTrue(gate.action(List.of(chest), g -> List.of()).allowed(), "wieder im Kreativmodus");
    }

    /** Plan B6: Ein Bauteil, in dem eine einzige Zelle eine höhere Stufe braucht, entfällt ganz. */
    @Test
    void moduleIsAllOrNothingDecidedOverTheWholeGroup() throws Exception {
        Cell plain = Cell.of("stone", "air");
        Cell lever = Cell.of("lever", "air");
        Cell command = Cell.of("command_block", "air");
        AtomicReference<BuildRights> now = new AtomicReference<>(PLAIN);
        PlacementGate gate = new PlacementGate(now::get);
        List<PlacementGate.Cell> group = List.of(plain, lever, command);
        PlacementGate.Decision first = gate.action(List.of(plain.inGroup(7)), g -> group);
        assertFalse(first.allowed(), "der Befehlsblock weiter hinten sperrt schon den ersten Stein");
        assertEquals(Tier.COMMANDS, first.missing());
        now.set(ADMIN);
        assertFalse(gate.action(List.of(lever.inGroup(7)), g -> group).allowed(),
                "einmal entschieden, ganz oder gar nicht");
        assertTrue(gate.action(List.of(plain), g -> List.of()).allowed(), "Zellen außerhalb des Bauteils");
        assertTrue(gate.action(List.of(plain.inGroup(8)), g -> group).allowed(), "anderes Bauteil, jetzt mit Rechten");
    }

    /**
     * Sicherheitsprüfung 2026-11-08, Befund 1: Paar aus Bauteil-Hälfte (geprüft, harmlos) und Plan-Hälfte (Gruppe 0).
     * Vorher entschied nur die Gruppe der ersten Zelle – die zweite Hälfte kam ungeprüft durch.
     */
    @Test
    void mixedPairChecksBothHalves() throws Exception {
        Cell moduleHalf = Cell.of("chest[type=left]", "air").inGroup(3);
        Cell planHalf = Cell.of("chest[type=right]{Items:[{Slot:0b,id:\"minecraft:wither_spawn_egg\",count:64}]}",
                "air");
        PlacementGate gate = new PlacementGate(() -> PLAIN);
        PlacementGate.Decision d = gate.action(List.of(moduleHalf, planHalf), g -> List.of(moduleHalf));
        assertFalse(d.allowed(), "Spawn-Eier in der Plan-Hälfte");
        assertEquals(Tier.DANGEROUS, d.missing());
        Cell doorTop = new Cell(Tier.EVERYONE, "", "bedrock");
        Cell doorBottom = Cell.of("oak_door[half=lower]", "air").inGroup(4);
        assertFalse(new PlacementGate(() -> PLAIN).action(List.of(doorBottom, doorTop), g -> List.of(doorBottom))
                .allowed(), "Oberhälfte über Grundgestein");
        Cell other = Cell.of("command_block{Command:\"say x\"}", "air").inGroup(5);
        assertFalse(new PlacementGate(() -> PLAIN).action(List.of(moduleHalf, other),
                g -> g == 3 ? List.of(moduleHalf) : List.of(other)).allowed(), "zweites Bauteil in derselben Aktion");
    }

    /**
     * Befund 7: Ein behaltenes JA gilt nur unter denselben Rechten. Kreativ mitten im Bauteil verlassen: Auch ein
     * harmloser Stein des Bauteils wird neu entschieden – über die Truhe weiter hinten – und fällt weg.
     */
    @Test
    void keptModuleDecisionIsRedoneWhenRightsChange() throws Exception {
        Cell stone = Cell.of("stone", "air").inGroup(9);
        Cell cobble = Cell.of("cobblestone", "air").inGroup(9);
        Cell chest = Cell.of("chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}", "air").inGroup(9);
        List<PlacementGate.Cell> group = List.of(stone, cobble, chest);
        AtomicReference<BuildRights> now = new AtomicReference<>(BuildRights.player(false, false, false, true, true));
        PlacementGate gate = new PlacementGate(now::get);
        assertTrue(gate.action(List.of(stone), g -> group).allowed(), "im Kreativmodus");
        now.set(PLAIN);
        PlacementGate.Decision d = gate.action(List.of(cobble), g -> group);
        assertFalse(d.allowed(), "Rechte geändert: nicht mit dem alten JA weiterbauen");
        assertEquals(Tier.CONTENTS, d.missing());
    }

    /** Fällt eine Zelle eines Bauteils weg, fällt der Rest auch weg – auch wenn die Rechte zurückkommen. */
    @Test
    void oneSkippedCellAbandonsTheModuleForGood() throws Exception {
        Cell stone = Cell.of("stone", "air").inGroup(9);
        Cell chest = Cell.of("chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}", "air").inGroup(9);
        Cell cobble = Cell.of("cobblestone", "air").inGroup(9);
        List<PlacementGate.Cell> group = List.of(stone, chest, cobble);
        BuildRights creative = BuildRights.player(false, false, false, true, true);
        AtomicReference<BuildRights> now = new AtomicReference<>(creative);
        PlacementGate gate = new PlacementGate(now::get);
        assertTrue(gate.action(List.of(stone), g -> group).allowed(), "im Kreativmodus");
        now.set(PLAIN);
        assertFalse(gate.action(List.of(chest), g -> group).allowed(), "die Truhe selbst");
        now.set(creative);
        assertFalse(gate.action(List.of(cobble), g -> group).allowed(), "einmal NEIN bleibt NEIN");
    }

    @Test
    void pairsComeTogetherOrNotAtAll() throws Exception {
        Cell foot = new Cell(Tier.EVERYONE, "", "air");
        Cell head = Cell.of("air", "bedrock");
        assertFalse(once(PLAIN, foot, head).allowed());
    }

    /** Plan B5: fremder Text im Prompt (Recherche) deckelt auch Konsole und Admins bei „Inhalte“. */
    @Test
    void capLimitsEvenTheConsoleAndAdmins() {
        BuildRights console = BuildRights.CONSOLE.capped(Tier.CONTENTS);
        assertTrue(console.allows(Tier.CONTENTS));
        assertFalse(console.allows(Tier.DANGEROUS), "keine Lava aus Recherche-Plänen");
        assertFalse(console.allows(Tier.COMMANDS), "keine Befehle aus Recherche-Plänen");
        BuildRights admin = BuildRights.player(true, true, true, false, true).capped(Tier.CONTENTS);
        assertTrue(admin.allows(Tier.CONTENTS));
        assertFalse(admin.allows(Tier.DANGEROUS));
        assertFalse(admin.allows(Tier.COMMANDS));
        assertTrue(BuildRights.player(false, false, false, true, true).capped(Tier.CONTENTS).allows(Tier.CONTENTS),
                "Kreativ in derselben Welt bleibt Kreativ");
        assertFalse(PLAIN.capped(Tier.CONTENTS).allows(Tier.CONTENTS), "eine Obergrenze gibt nichts dazu");
        assertFalse(BuildRights.CONSOLE.capped(Tier.EVERYONE).allows(Tier.CONTENTS));
        assertTrue(BuildRights.CONSOLE.capped(Tier.DANGEROUS).allows(Tier.DANGEROUS));
        assertFalse(BuildRights.CONSOLE.capped(Tier.DANGEROUS).allows(Tier.COMMANDS));
        for (Tier t : Tier.values()) {
            assertEquals(ADMIN.allows(t), ADMIN.capped(Tier.COMMANDS).allows(t), "COMMANDS ändert nichts: " + t);
        }
    }

    /** Befund 9: Nur echte Konsole und RCON gelten als Konsole – ein Befehlsblock bekommt nur, was jeder darf. */
    @Test
    void onlyTheRealConsoleGetsConsoleRights() {
        org.bukkit.command.CommandSender console = senderProxy(org.bukkit.command.ConsoleCommandSender.class);
        org.bukkit.command.CommandSender rcon = senderProxy(org.bukkit.command.RemoteConsoleCommandSender.class);
        org.bukkit.command.CommandSender block = senderProxy(org.bukkit.command.BlockCommandSender.class);
        assertTrue(BuildRights.isConsole(console));
        assertTrue(BuildRights.isConsole(rcon));
        assertFalse(BuildRights.isConsole(block), "Befehlsblock");
        assertTrue(BuildRights.ofSender(console, null).allows(Tier.COMMANDS));
        assertFalse(BuildRights.ofSender(block, null).allows(Tier.CONTENTS), "Befehlsblock darf nur, was jeder darf");
    }

    @SuppressWarnings("unchecked")
    private static <T> T senderProxy(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }

    @Test
    void missingRightIsNamedInTheHint() {
        assertEquals("das Recht baumeister.admin.gefaehrlich", BuildRights.missing(Tier.DANGEROUS)); // umlaut-ok
        assertEquals("den Kreativmodus oder das Recht baumeister.bauen.inhalte", BuildRights.missing(Tier.CONTENTS));
    }
}
