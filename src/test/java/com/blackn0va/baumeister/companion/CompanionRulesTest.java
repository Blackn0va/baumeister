package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Was der Begleiter darf (Geben, Erze) und wie er Richtung, Uhrzeit und Lieder ausgibt. */
class CompanionRulesTest {

    @Test
    void givesOnlyInCreativeOrWithPermission() {
        assertFalse(CompanionRules.mayGive(false, false), "Überleben ohne Recht: nichts");
        assertTrue(CompanionRules.mayGive(true, false));
        assertTrue(CompanionRules.mayGive(false, true));
    }

    @Test
    void oresNeedCreativeOrPermissionOtherBlocksDoNot() {
        assertFalse(CompanionRules.mayFind("diamond_ore", false, false));
        assertFalse(CompanionRules.mayFind("minecraft:deepslate_iron_ore", false, false));
        assertFalse(CompanionRules.mayFind("ancient_debris", false, false));
        assertTrue(CompanionRules.mayFind("diamond_ore", true, false));
        assertTrue(CompanionRules.mayFind("diamond_ore", false, true));
        assertTrue(CompanionRules.mayFind("oak_log", false, false));
        assertTrue(CompanionRules.mayFind("water", false, false));
    }

    @Test
    void directionUsesMinecraftCompass() {
        assertEquals("etwa 20 Blöcke nördlich", CompanionRules.direction(0, 0, -20));
        assertEquals("etwa 20 Blöcke östlich, 5 tiefer", CompanionRules.direction(20, -5, 0));
        assertEquals("etwa 14 Blöcke südwestlich, 3 höher", CompanionRules.direction(-10, 3, 10));
        assertEquals("direkt hier, 12 tiefer", CompanionRules.direction(1, -12, 0));
    }

    @Test
    void clockStartsAtSixInTheMorning() {
        assertEquals("6:00", CompanionRules.clock(0));
        assertEquals("12:00", CompanionRules.clock(6000));
        assertEquals("0:00", CompanionRules.clock(18000));
        assertEquals("18:30", CompanionRules.clock(12500));
        assertEquals("6:00", CompanionRules.clock(24000));
    }

    @Test
    void songsAreFoundByLooseNameAndStayInNoteBlockRange() {
        assertNotNull(Songs.byName("Alle meine Entchen"));
        assertNotNull(Songs.byName("happy-birthday"));
        assertNull(Songs.byName("bohemian_rhapsody"));
        for (String n : Songs.names()) {
            for (Songs.Note note : Songs.byName(n).notes()) {
                assertTrue(note.step() >= 0 && note.step() <= 24, n);
                assertTrue(note.ticks() > 0, n);
            }
        }
    }

    @Test
    void commandsNeedTheirOwnPermissionFirst() {
        assertEquals(CompanionRules.CommandDecision.DENIED, CompanionRules.decide("fly", false));
        assertEquals(CompanionRules.CommandDecision.DENIED, CompanionRules.decide(null, false));
        assertEquals(CompanionRules.CommandDecision.RUN, CompanionRules.decide("fly", true));
        assertEquals(CompanionRules.CommandDecision.INVALID, CompanionRules.decide(null, true));
    }

    @Test
    void seriousCommandsAskFirstHarmlessOnesRunDirectly() {
        for (String s : new String[] {"stop", "minecraft:stop", "op Steve", "ban Steve", "lp user x permission set *",
                "kill @e", "tp @a ~ ~ ~", "effect give @e[type=zombie] glowing", "/set stone", "mv delete welt",
                "plot delete", "execute as @p run say hi", "whitelist off", "gamerule keepInventory true", "clear"}) {
            assertEquals(CompanionRules.CommandDecision.CONFIRM, CompanionRules.decide(s, true), s);
        }
        for (String s : new String[] {"fly", "gamemode creative", "time set day", "weather clear", "tp Steve",
                "effect give @s night_vision 600", "give @s tnt 640", "heal", "xp add @s 30 levels", "mv tp nether",
                "plot home", "spawn", "say email@adresse", "tp 10 64 -20"}) {
            assertEquals(CompanionRules.CommandDecision.RUN, CompanionRules.decide(s, true), s);
        }
    }

    /**
     * Zweite Sicherheitsprüfung 2026-11-08, H2: Freigabeliste statt Sperrliste. Was nicht harmlos ist und nur den
     * Spieler selbst betrifft, fragt nach – auch wenn es auf keiner Liste stand (Daten, andere Spieler, Bauen).
     */
    @Test
    void onlyHarmlessSelfCommandsRunWithoutAskingEverythingElseAsks() {
        String me = "Blackkn0va";
        for (String s : new String[] {
            "summon falling_block ~ ~2 ~ {BlockState:{Name:\"command_block\"},TileEntityData:{Command:\"op x\",auto:1b}}",
            "data merge block 1 2 3 {Command:\"op x\",auto:1b}", "gamemode creative Mallory", "give Mallory diamond 64",
            "give @s diamond_sword[enchantments={levels:{sharpness:255}}]", "tp Steve Mallory", "fly Steve",
            "give @s diamond_sword{Enchantments:[{id:sharpness,lvl:255}]}", "say {\"text\":\"x\"}",
            "setblock ~ ~ ~ command_block", "fill ~ ~ ~ ~1 ~1 ~1 stone", "function x:y", "effect give Mallory speed",
            "attribute @s generic.max_health base set 1000", "worldedit:set stone", "trigger x", "spreadplayers 0 0 1 2 false Mallory"}) {
            assertTrue(CompanionRules.needsConfirmation(s, me), s);
        }
        for (String s : new String[] {"gamemode creative Blackkn0va", "give blackkn0va diamond 3", "fly blackkn0va",
                "minecraft:time set day", "essentials:heal"}) {
            assertFalse(CompanionRules.needsConfirmation(s, me), s);
        }
        assertTrue(CompanionRules.bundleNeedsConfirmation(java.util.List.of("time set day", "give Mallory tnt 1"),
                false, me), "einer im Bündel fragt für alle");
    }

    /**
     * Plan A4 (Wunsch 2026-11-08): Ein „ja“ aus der
     * Spracherkennung führt nichts aus und lässt die Rückfrage stehen; getippt führt es aus, „nein“ bricht immer ab.
     */
    @Test
    void spokenYesNeverRunsCommandsTypedYesDoes() {
        Boolean yes = CompanionChat.confirm("Ja!");
        Boolean no = CompanionChat.confirm("nein");
        assertEquals(CompanionRules.PendingAnswer.RUN, CompanionRules.pendingAnswer(yes, true, false));
        assertEquals(CompanionRules.PendingAnswer.TYPE_IT, CompanionRules.pendingAnswer(yes, true, true));
        assertEquals(CompanionRules.PendingAnswer.CANCEL, CompanionRules.pendingAnswer(no, true, true));
        assertEquals(CompanionRules.PendingAnswer.CANCEL, CompanionRules.pendingAnswer(no, true, false));
        // Zu spät oder von jemand anderem (fresh=false), oder gar keine Antwort: Die Rückfrage verfällt.
        assertEquals(CompanionRules.PendingAnswer.EXPIRED, CompanionRules.pendingAnswer(yes, false, false));
        assertEquals(CompanionRules.PendingAnswer.EXPIRED, CompanionRules.pendingAnswer(yes, false, true));
        assertEquals(CompanionRules.PendingAnswer.EXPIRED,
                CompanionRules.pendingAnswer(CompanionChat.confirm("wie spät ist es"), true, true));
    }

    /** Gesprochen fragt Vera auch bei Harmlosem nach („Vera, mach Tag“ ohne Modell), getippt nur bei Folgenreichem. */
    @Test
    void spokenCommandsAlwaysAskEvenHarmlessOnes() {
        java.util.List<String> day = CompanionIntent.command("mach Tag", new CompanionIntent.Known(false)).orElseThrow()
                .actions().stream().filter(a -> a.op() == CompanionReply.Op.COMMAND).map(CompanionReply.Action::text)
                .toList();
        assertEquals(java.util.List.of("time set day"), day);
        assertFalse(CompanionRules.bundleNeedsConfirmation(day, false), "getippt läuft Harmloses sofort");
        assertTrue(CompanionRules.bundleNeedsConfirmation(day, true), "gesprochen nie ohne getipptes ja");
        assertTrue(CompanionRules.bundleNeedsConfirmation(java.util.List.of("time set day", "op Steve"), false));
    }

    @Test
    void commandLineDropsOneSlashAndRejectsControlCharacters() {
        assertEquals("gamemode creative", CompanionRules.commandLine("  /gamemode   creative "));
        assertEquals("/set stone", CompanionRules.commandLine("//set stone"));
        assertNull(CompanionRules.commandLine("say hi\nop Steve"));
        assertNull(CompanionRules.commandLine(" / "));
        assertNull(CompanionRules.commandLine(null));
    }

    @Test
    void changedAreaReadsRelativeAndAbsoluteCoordinates() {
        // Vorfall 2026-11-04 12:19, Spieler bei -38 63 130
        CompanionRules.Area a = CompanionRules.changedArea("fill ~-5 ~ ~-5 ~5 ~5 ~5 redstone", -38, 63, 130);
        assertEquals(new CompanionRules.Area(-43, 63, 125, -33, 68, 135, "redstone"), a);
        assertEquals(726, a.volume());
        assertEquals("Das macht 726 Blöcke um dich herum zu redstone.", CompanionRules.describeChange(a));
        CompanionRules.Area hole = CompanionRules.changedArea("minecraft:fill 10 70 10 8 60 12 minecraft:air", 0, 0, 0);
        assertEquals(new CompanionRules.Area(8, 60, 10, 10, 70, 12, "air"), hole);
        assertEquals("Das entfernt 99 Blöcke um dich herum.", CompanionRules.describeChange(hole));
        assertEquals(1, CompanionRules.changedArea("setblock ~ ~-1 ~ stone_bricks[facing=north]", 5, 5, 5).volume());
        assertEquals("stone_bricks", CompanionRules.changedArea("setblock ~ ~-1 ~ stone_bricks[x=y]", 0, 0, 0).block());
        CompanionRules.Area clone = CompanionRules.changedArea("clone 0 0 0 2 1 3 100 64 100", 0, 0, 0);
        assertEquals(new CompanionRules.Area(100, 64, 100, 102, 65, 103, ""), clone);
        assertNull(CompanionRules.changedArea("fill ^ ^ ^1 ^2 ^2 ^2 air", 0, 0, 0), "Blickrichtung nicht berechenbar");
        assertNull(CompanionRules.changedArea("fill ~ ~ ~ @p ~ ~ air", 0, 0, 0));
        assertNull(CompanionRules.changedArea("fill ~ ~ ~", 0, 0, 0));
        assertNull(CompanionRules.changedArea("gamemode creative", 0, 0, 0));
    }

    @Test
    void brokenModelCommandsAreStraightened() {
        // dev01 2026-11-04 20:23: TNT-Stapel – y absolut 1 statt relativ, und eine 10 hinter dem Block.
        assertEquals("setblock ~ ~ ~1 tnt", CompanionRules.repairCommand("setblock ~ ~ 1 tnt"));
        assertEquals("setblock ~ ~ ~-1 tnt", CompanionRules.repairCommand("setblock ~ ~ ~-1 tnt 10"));
        assertEquals("setblock ~ ~ ~2 tnt replace", CompanionRules.repairCommand("setblock ~ ~ ~2 tnt replace 7"));
        assertEquals("fill ~ ~ ~4 ~ ~9 ~4 tnt", CompanionRules.repairCommand("fill ~ ~ 4 ~ 9 ~4 tnt"));
        assertEquals("summon tnt ~ ~1 ~3", CompanionRules.repairCommand("summon tnt ~ 1 ~3"));
    }

    @Test
    void validCommandsStayAsTheyAre() {
        for (String s : java.util.List.of("setblock ~ 64 ~ stone", "setblock 10 64 -20 stone", "fill 1 2 3 4 5 6 air",
                "setblock ~ ~ ~ tnt keep", "time set day", "give @s tnt 64", "tp ^ ^ ^5", "fill ~ 100 ~ ~5 120 ~5 air",
                "summon zombie ~ ~ ~ {NoAI:1b}")) {
            assertEquals(s, CompanionRules.repairCommand(s), s);
        }
        assertNull(CompanionRules.repairCommand(null));
    }

    /** Erz-Familien (2026-11-08): Diamant unter y = 0 ist Tiefenschiefer, Gold im Nether nether_gold_ore. */
    @Test
    void oreFamiliesIncludeDeepslateAndNetherGold() {
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("diamond_ore", "deepslate_diamond_ore"),
                CompanionRules.oreFamily("minecraft:diamond_ore"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("deepslate_iron_ore", "iron_ore"),
                CompanionRules.oreFamily("deepslate_iron_ore"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("gold_ore", "deepslate_gold_ore",
                "nether_gold_ore"), CompanionRules.oreFamily("gold_ore"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("nether_quartz_ore"),
                CompanionRules.oreFamily("nether_quartz_ore"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("oak_log"), CompanionRules.oreFamily("oak_log"));
    }

    /** Ortssuche (2026-11-08, Idee aus Verity JE): „das nächste Dorf“, „die nächste Netherfestung“. */
    @Test
    void placeWordsMapToStructures() {
        org.junit.jupiter.api.Assertions.assertEquals("Dorf", CompanionRules.placeFor("das nächste Dorf")
                .orElseThrow().label());
        org.junit.jupiter.api.Assertions.assertTrue(CompanionRules.placeFor("die nächste Netherfestung").orElseThrow()
                .structures().contains("fortress"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("stronghold", "fortress"),
                CompanionRules.placeFor("eine Festung").orElseThrow().structures());
        org.junit.jupiter.api.Assertions.assertEquals("antike Stadt", CompanionRules.placeFor("die nächste antike "
                + "Stadt hier").orElseThrow().label());
        org.junit.jupiter.api.Assertions.assertTrue(CompanionRules.placeFor("Wüstentempel").isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(CompanionRules.placeFor("mein Zuhause").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(CompanionRules.placeFor("Diamant").isEmpty());
    }
}
