package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockTiers.WorldFacts;

import org.junit.jupiter.api.Test;

/**
 * Stufen der Baurechte (Plan B1): Freigabeliste, fail-closed. Dazu die Umgehungsversuche aus dem Plan – Klick-,
 * Hover-, nbt-, score- und selector-Texte in Schildern und Büchern, Passagiere in Bienen, Beutetabellen,
 * Gegenstand-Bestandteile, verschachtelte Behälter und unbekannte Schlüssel.
 */
class BlockTiersTest {

    private static Tier tier(String block) throws PlanException {
        return tier(block, WorldFacts.OVERWORLD);
    }

    private static Tier tier(String block, WorldFacts facts) throws PlanException {
        return BlockTiers.place(BlockSpec.parseTrusted(block), facts).tier();
    }

    private static String sign(String messages) {
        return "oak_sign{front_text:{messages:[" + messages + "]}}";
    }

    private static String chest(String items) {
        return "chest{Items:[" + items + "]}";
    }

    private static String item(String id, String components) {
        return "{Slot:0b,id:\"minecraft:" + id + "\",count:1" + (components.isEmpty() ? "" : ",components:{"
                + components + "}") + "}";
    }

    @Test
    void plainBlocksAndStatesAreForEveryone() throws Exception {
        assertEquals(Tier.EVERYONE, tier("stone"));
        assertEquals(Tier.EVERYONE, tier("oak_stairs[facing=left,half=top]"));
        assertEquals(Tier.EVERYONE, tier("air"));
        assertEquals(Tier.EVERYONE, tier("sunflower[half=upper]"));
        assertEquals(Tier.EVERYONE, tier("sculk_shrieker[can_summon=false]"));
        assertEquals(Tier.EVERYONE, tier("cinnabar_bricks"), "neu in 26.2, in der Freigabeliste");
    }

    @Test
    void dangerousBlocksNeedTheDangerousTier() throws Exception {
        for (String id : new String[] {"lava", "fire", "soul_fire", "tnt", "spawner", "trial_spawner", "vault",
            "bedrock", "reinforced_deepslate", "barrier", "light", "structure_void", "nether_portal", "end_portal",
            "end_portal_frame", "end_gateway", "moving_piston", "piston_head", "infested_stone",
            "infested_deepslate", "wither_skeleton_skull", "wither_skeleton_wall_skull", "dragon_egg",
            "creaking_heart", "lava_cauldron", "frogspawn", "potent_sulfur"}) {
            assertEquals(Tier.DANGEROUS, tier(id), id);
        }
        assertEquals(Tier.DANGEROUS, tier("sculk_shrieker[can_summon=true]"));
    }

    @Test
    void commandStructureAndTestBlocksNeedTheCommandTier() throws Exception {
        for (String id : new String[] {"command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "jigsaw", "test_block", "test_instance_block"}) {
            assertEquals(Tier.COMMANDS, tier(id), id);
        }
    }

    @Test
    void unknownBlockIdsNeedTheHighestTier() throws Exception {
        assertEquals(Tier.COMMANDS, tier("mega_tnt"));
        assertEquals(Tier.COMMANDS, BlockTiers.removal("mega_tnt").tier());
    }

    @Test
    void bedsAndAnchorsDependOnTheWorld() throws Exception {
        assertEquals(Tier.EVERYONE, tier("red_bed[part=foot]", WorldFacts.OVERWORLD));
        assertEquals(Tier.DANGEROUS, tier("red_bed[part=foot]", WorldFacts.NETHER));
        assertEquals(Tier.DANGEROUS, tier("red_bed", WorldFacts.UNKNOWN));
        assertEquals(Tier.EVERYONE, tier("respawn_anchor[charges=4]", WorldFacts.NETHER));
        assertEquals(Tier.DANGEROUS, tier("respawn_anchor", WorldFacts.OVERWORLD));
    }

    @Test
    void airIsNoFreePassOverBedrockOrCommandBlocks() {
        assertEquals(Tier.DANGEROUS, BlockTiers.removal("bedrock").tier());
        assertEquals(Tier.DANGEROUS, BlockTiers.removal("barrier").tier());
        assertEquals(Tier.DANGEROUS, BlockTiers.removal("spawner").tier());
        assertEquals(Tier.COMMANDS, BlockTiers.removal("command_block").tier());
        assertEquals(Tier.COMMANDS, BlockTiers.removal("repeating_command_block").tier());
        assertEquals(Tier.EVERYONE, BlockTiers.removal("stone").tier());
        assertEquals(Tier.EVERYONE, BlockTiers.removal("lava").tier(), "Lava zuschütten darf jeder");
        assertEquals(Tier.EVERYONE, BlockTiers.removal("air").tier());
    }

    @Test
    void signsWithPlainTextAreForEveryone() throws Exception {
        assertEquals(Tier.EVERYONE, tier(sign("\"Hallo\",\"\",'Welt',{text:\"rot\",color:\"red\",bold:1b}")));
        assertEquals(Tier.EVERYONE, tier("oak_sign{front_text:{messages:[\"a\",\"\",\"\",\"\"],color:\"blue\","
                + "has_glowing_text:1b},back_text:{messages:[\"b\",\"\",\"\",\"\"]},is_waxed:1b}"));
        assertEquals(Tier.EVERYONE, tier(sign("\"[Laden]\",\"{nicht json\",\"\",\"\"")), "Klammern sind Text");
        assertEquals(Tier.EVERYONE, tier(sign("{text:\"s\",shadow_color:[1.0f,0.0f,0.0f,1.0f]},\"\",\"\",\"\"")),
                "Schattenfarbe als [r,g,b,a]");
        // Befund 12: bold & Co. nur als Zahl – vorher ging jede Liste durch.
        assertEquals(Tier.COMMANDS, tier(sign("{text:\"x\",bold:[{text:\"y\"}]},\"\",\"\",\"\"")), "bold als Liste");
        assertEquals(Tier.COMMANDS, tier(sign("{text:\"x\",shadow_color:[1,2]},\"\",\"\",\"\"")), "Schatten kaputt");
    }

    @Test
    void clickHoverNbtScoreAndSelectorTextsNeedTheCommandTier() throws Exception {
        String[] bypass = {
            "{text:\"x\",click_event:{action:\"run_command\",command:\"/op Gast\"}}",
            "{text:\"x\",clickEvent:{action:\"run_command\",value:\"/op Gast\"}}",
            "{text:\"x\",hover_event:{action:\"show_text\",value:\"y\"}}",
            "{text:\"x\",insertion:\"/op Gast\"}",
            "{nbt:\"Inventory\",entity:\"@p\"}",
            "{score:{name:\"@p\",objective:\"geld\"}}",
            "{selector:\"@a\"}",
            "{type:\"nbt\",nbt:\"x\",storage:\"a:b\"}",
            "{keybind:\"key.jump\"}",
            "{text:\"a\",extra:[{text:\"b\",click_event:{action:\"run_command\",command:\"/op Gast\"}}]}",
            "{translate:\"%s\",with:[{selector:\"@a\"}]}",
            "'{\"text\":\"alt\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op Gast\"}}'",
            "'[\"\",{\"text\":\"alt\",\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"y\"}}]'",
        };
        for (String b : bypass) {
            assertEquals(Tier.COMMANDS, tier(sign(b + ",\"\",\"\",\"\"")), b);
            assertEquals(Tier.COMMANDS, tier("oak_sign{back_text:{messages:[\"\",\"\",\"\"," + b + "]}}"), b);
            assertEquals(Tier.COMMANDS, tier("oak_hanging_sign{front_text:{messages:[\"\",\"\",\"\",\"\"],"
                    + "filtered_messages:[" + b + ",\"\",\"\",\"\"]}}"), b);
        }
    }

    /**
     * Gegenprobe 2026-11-08: Gson bricht ab 255 Ebenen ab, und das galt als „kein JSON“ – ein clickEvent in 254
     * Feldern (622 Zeichen) war für alle. Jetzt gilt zu tief als Befehl, flache Klammern bleiben Text.
     */
    @Test
    void deeplyNestedLegacyJsonNeedsTheCommandTier() throws Exception {
        for (int levels : new int[] {33, 254, 300}) {
            String json = "[".repeat(levels) + "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\","
                    + "\"value\":\"/op Gast\"}}" + "]".repeat(levels);
            assertEquals(Tier.COMMANDS, BlockTiers.legacyJson(json).tier(), levels + " Ebenen");
        }
        String harmless = "[".repeat(40) + "\"x\"" + "]".repeat(40);
        assertEquals(Tier.COMMANDS, BlockTiers.legacyJson(harmless).tier(), "auch ohne Klick: so tief schreibt niemand");
        assertEquals(Tier.EVERYONE, BlockTiers.legacyJson("[\"[[[[\",\"]]]]\"]").tier(), "Klammern in Text zählen nicht");
        assertEquals(Tier.EVERYONE, BlockTiers.legacyJson("['{{{{{','x']").tier(), "auch in ' (nachsichtiges JSON)");
        assertEquals(Tier.EVERYONE, BlockTiers.legacyJson("[Laden]").tier());
        assertEquals(2, BlockTiers.jsonDepth("[{\"a\":\"[\\\"]\"}]"), "maskiertes Anführungszeichen beendet nichts");
    }

    @Test
    void booksAreContentsUnlessTheirPagesCarryCommands() throws Exception {
        String plain = "lectern[has_book=true]{Book:{id:\"minecraft:written_book\",count:1,components:{"
                + "\"minecraft:written_book_content\":{title:\"Regeln\",author:\"Heiko\",pages:[\"Seite 1\","
                + "{raw:\"Seite 2\"}]}}}}";
        assertEquals(Tier.CONTENTS, tier(plain));
        String click = plain.replace("\"Seite 1\"",
                "{text:\"Klick\",click_event:{action:\"run_command\",command:\"/op Gast\"}}");
        assertEquals(Tier.COMMANDS, tier(click));
        String filtered = plain.replace("{raw:\"Seite 2\"}", "{raw:\"a\",filtered:{selector:\"@a\"}}");
        assertEquals(Tier.COMMANDS, tier(filtered));
        assertEquals(Tier.CONTENTS, tier("lectern{Book:{id:\"minecraft:writable_book\",count:1,components:{"
                + "\"minecraft:writable_book_content\":{pages:[\"Notiz\"]}}},Page:0}"));
    }

    @Test
    void beesWithPassengersOrAnyEntityDataNeedTheCommandTier() throws Exception {
        assertEquals(Tier.EVERYONE, tier("beehive{bees:[]}"), "leerer Bienenstock");
        assertEquals(Tier.COMMANDS, tier("beehive{bees:[{entity_data:{id:\"minecraft:bee\",Passengers:[{"
                + "id:\"minecraft:tnt\",fuse:0}]},ticks_in_hive:0,min_ticks_in_hive:0}]}"));
        assertEquals(Tier.COMMANDS, tier("bee_nest{bees:[{entity_data:{id:\"minecraft:bee\"}}]}"));
        assertEquals(Tier.COMMANDS, tier("spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}"));
        assertEquals(Tier.COMMANDS, tier("trial_spawner{spawn_potentials:[]}"));
    }

    @Test
    void lootTablesAndLocksNeedTheCommandTier() throws Exception {
        assertEquals(Tier.COMMANDS, tier("chest{LootTable:\"minecraft:chests/simple_dungeon\"}"));
        assertEquals(Tier.COMMANDS, tier("barrel{LootTable:\"minecraft:chests/end_city_treasure\",LootTableSeed:5L}"));
        assertEquals(Tier.COMMANDS, tier("decorated_pot{LootTable:\"minecraft:pots/trial_chambers/corridor\"}"));
        assertEquals(Tier.COMMANDS, tier("suspicious_sand{LootTable:\"minecraft:archaeology/desert_pyramid\"}"));
        assertEquals(Tier.COMMANDS, tier("chest{lock:{items:\"minecraft:diamond\"}}"));
        assertEquals(Tier.COMMANDS, tier("furnace{RecipesUsed:{\"minecraft:iron_ingot\":1000}}"));
    }

    @Test
    void plainItemsInContainersNeedTheContentsTier() throws Exception {
        assertEquals(Tier.CONTENTS, tier(chest(item("diamond", ""))));
        assertEquals(Tier.EVERYONE, tier("chest{Items:[]}"), "leer ist kein Inhalt");
        assertEquals(Tier.CONTENTS, tier(chest(item("diamond_sword", "\"minecraft:damage\":10,"
                + "\"minecraft:custom_name\":\"Klinge\",\"minecraft:enchantments\":{\"minecraft:sharpness\":5}"))));
        assertEquals(Tier.CONTENTS, tier("campfire{Items:[" + item("beef", "") + "],CookingTimes:[I;0,0,0,0]}"));
        assertEquals(Tier.CONTENTS, tier("jukebox{RecordItem:{id:\"minecraft:music_disc_cat\",count:1}}"));
        assertEquals(Tier.CONTENTS, tier("chiseled_bookshelf{Items:[" + item("book", "") + "]}"));
        assertEquals(Tier.CONTENTS, tier("decorated_pot{sherds:[\"minecraft:brick\"],item:{id:\"minecraft:stick\","
                + "count:2}}"));
        assertEquals(Tier.CONTENTS, tier("white_shulker_box{Items:[" + item("cobblestone", "") + "]}"));
    }

    @Test
    void itemIdsAreTieredLikeBlocks() throws Exception {
        assertEquals(Tier.DANGEROUS, tier(chest(item("lava_bucket", ""))));
        assertEquals(Tier.DANGEROUS, tier(chest(item("zombie_spawn_egg", ""))));
        assertEquals(Tier.DANGEROUS, tier(chest(item("end_crystal", ""))));
        assertEquals(Tier.DANGEROUS, tier(chest(item("trial_key", ""))));
        assertEquals(Tier.DANGEROUS, tier(chest(item("flint_and_steel", ""))));
        assertEquals(Tier.COMMANDS, tier(chest(item("command_block", ""))));
        assertEquals(Tier.COMMANDS, tier(chest(item("debug_stick", ""))));
        assertEquals(Tier.COMMANDS, tier(chest(item("mega_sword", ""))), "unbekannter Gegenstand");
    }

    @Test
    void itemComponentsBeyondThePlainOnesNeedTheCommandTier() throws Exception {
        String[] commands = {
            "\"minecraft:custom_data\":{geld:1000}",
            "\"minecraft:can_place_on\":{blocks:\"minecraft:stone\"}",
            "\"minecraft:can_break\":{blocks:\"minecraft:bedrock\"}",
            "\"minecraft:attribute_modifiers\":[{type:\"minecraft:attack_damage\",amount:1000d,id:\"a:b\","
                    + "operation:\"add_value\"}]",
            "\"minecraft:enchantments\":{\"minecraft:sharpness\":6}",
            "\"minecraft:enchantments\":{\"minecraft:super_sharp\":1}",
            "\"minecraft:entity_data\":{id:\"minecraft:wither\"}",
            "\"minecraft:bucket_entity_data\":{Health:1000f}",
            "\"minecraft:block_entity_data\":{id:\"minecraft:command_block\",Command:\"op Gast\"}",
            "\"minecraft:consumable\":{on_consume_effects:[]}",
            "\"minecraft:potion_contents\":{custom_effects:[{id:\"minecraft:instant_damage\",amplifier:100b}]}",
            "\"minecraft:custom_name\":{text:\"x\",click_event:{action:\"run_command\",command:\"/op Gast\"}}",
            "\"minecraft:lore\":[{selector:\"@a\"}]",
            "\"!minecraft:food\":{}",
            "\"minecraft:container_loot\":{loot_table:\"minecraft:chests/simple_dungeon\"}",
        };
        for (String c : commands) {
            assertEquals(Tier.COMMANDS, tier(chest(item("diamond_sword", c))), c);
        }
    }

    /**
     * Sicherheitsprüfung 2026-11-08, Befund 3: Werte freigegebener Bestandteile werden geprüft, nicht nur ihr Name.
     * Vorher: Topf mit pot_decorations [bedrock, command_block] im Überleben zerschlagen = Admin-Gegenstände.
     */
    @Test
    void valuesOfPlainComponentsAreCheckedToo() throws Exception {
        String[] commands = {
            "\"minecraft:pot_decorations\":[\"minecraft:bedrock\",\"minecraft:command_block\"]",
            "\"minecraft:pot_decorations\":[\"minecraft:fake_pottery_sherd\"]",
            "\"minecraft:pot_decorations\":[\"minecraft:brick\",\"minecraft:brick\",\"minecraft:brick\","
                    + "\"minecraft:brick\",\"minecraft:brick\"]",
            "\"minecraft:fireworks\":{flight_duration:3b,explosions:[" + "{shape:\"large_ball\"},".repeat(7)
                    + "{shape:\"large_ball\"}]}",
            "\"minecraft:fireworks\":{flight_duration:100b}",
            "\"minecraft:fireworks\":{explosions:[{shape:\"star\",colors:[I;1,2,3,4,5,6,7,8,9]}]}",
            "\"minecraft:firework_explosion\":{shape:\"burst\",damage:100}",
            "\"minecraft:trim\":{material:{asset_name:\"x\",ingredient:\"minecraft:diamond\"},pattern:\"minecraft:wild\"}",
            "\"minecraft:instrument\":{sound_event:\"minecraft:entity.wither.spawn\",range:1000f,use_duration:1}",
            "\"minecraft:banner_patterns\":[{pattern:{asset_id:\"x\",translation_key:\"y\"},color:\"red\"}]",
            "\"minecraft:damage\":-5",
        };
        for (String c : commands) {
            assertEquals(Tier.COMMANDS, tier(chest(item("decorated_pot", c))), c);
        }
        String[] contents = {
            "\"minecraft:pot_decorations\":[\"minecraft:brick\",\"minecraft:angler_pottery_sherd\"]",
            "\"minecraft:fireworks\":{flight_duration:3b,explosions:[{shape:\"star\",colors:[I;11743532],"
                    + "fade_colors:[I;14602026],has_trail:1b,has_twinkle:0b}]}",
            "\"minecraft:trim\":{material:\"minecraft:diamond\",pattern:\"minecraft:wild\"}",
            "\"minecraft:instrument\":\"minecraft:ponder_goat_horn\"",
            "\"minecraft:banner_patterns\":[{pattern:\"minecraft:creeper\",color:\"green\"}]",
            "\"minecraft:damage\":5",
            "\"minecraft:dyed_color\":16711680",
        };
        for (String c : contents) {
            assertEquals(Tier.CONTENTS, tier(chest(item("decorated_pot", c))), c);
        }
    }

    @Test
    void nestedContainersNeedTheCommandTier() throws Exception {
        assertEquals(Tier.COMMANDS, tier(chest(item("shulker_box", "\"minecraft:container\":[{slot:0,item:{"
                + "id:\"minecraft:diamond\",count:64}}]"))));
        assertEquals(Tier.COMMANDS, tier(chest(item("bundle", "\"minecraft:bundle_contents\":[{"
                + "id:\"minecraft:diamond\",count:64}]"))));
        assertEquals(Tier.COMMANDS, tier(chest(item("crossbow", "\"minecraft:charged_projectiles\":[{"
                + "id:\"minecraft:firework_rocket\",count:1}]"))));
    }

    @Test
    void unknownKeysAndFormsNeedTheHighestTier() throws Exception {
        assertEquals(Tier.COMMANDS, tier("chest{Foo:1b}"));
        assertEquals(Tier.COMMANDS, tier("oak_sign{front_text:{messages:[\"\",\"\",\"\",\"\"]},Foo:1b}"));
        assertEquals(Tier.COMMANDS, tier("oak_sign{front_text:{messages:[\"\",\"\",\"\",\"\"],Bar:1b}}"));
        assertEquals(Tier.COMMANDS, tier("stone{CustomName:\"x\"}"), "Block ohne geprüfte Daten");
        assertEquals(Tier.COMMANDS, tier("comparator{OutputSignal:15}"));
        assertEquals(Tier.COMMANDS, tier(chest("{Slot:0b,id:\"minecraft:stone\",Count:1b,tag:{}}")), "alte Form");
        assertEquals(Tier.COMMANDS, tier(chest("{Slot:0b,id:\"minecraft:stone\",count:200}")), "Menge über 99");
        assertEquals(Tier.COMMANDS, tier(sign("12,\"\",\"\",\"\"")), "Zahl statt Text");
        assertEquals(Tier.COMMANDS, tier("white_banner{patterns:[{pattern:{asset_id:\"a:b\","
                + "translation_key:\"x\"},color:\"red\"}]}"));
        assertEquals(Tier.COMMANDS, tier("command_block{Command:\"say hallo\"}"));
    }

    @Test
    void bannersHeadNamesAndSherdsAreForEveryone() throws Exception {
        assertEquals(Tier.EVERYONE, tier("white_banner{patterns:[{pattern:\"minecraft:stripe_bottom\","
                + "color:\"red\"}],CustomName:\"Fahne\"}"));
        assertEquals(Tier.EVERYONE, tier("player_head{profile:\"Notch\"}"));
        assertEquals(Tier.EVERYONE, tier("player_wall_head{profile:{name:\"Notch\",id:[I;1,2,3,4]}}"));
        assertEquals(Tier.CONTENTS, tier("player_head{profile:{name:\"x\",properties:[{name:\"textures\","
                + "value:\"abc\"}]}}"));
        assertEquals(Tier.EVERYONE, tier("decorated_pot{sherds:[\"minecraft:brick\",\"minecraft:arms_up_pottery_sherd\"]}"));
        assertEquals(Tier.COMMANDS, tier("decorated_pot{sherds:[\"minecraft:diamond\"]}"));
    }

    /** Tippfehler in den eigenen Listen würden still nichts sperren – jede ID muss die Freigabeliste kennen. */
    @Test
    void everyListedIdIsInTheAllowlist() {
        for (java.util.Set<String> set : java.util.List.of(BlockTiers.COMMAND_BLOCKS, BlockTiers.DANGEROUS_BLOCKS,
                BlockTiers.REMOVAL_DANGEROUS)) {
            for (String id : set) {
                assertTrue(BlockTiers.knownBlock(id), id);
            }
        }
        for (java.util.Set<String> set : java.util.List.of(BlockTiers.COMMAND_ITEMS, BlockTiers.DANGEROUS_ITEMS)) {
            for (String id : set) {
                assertTrue(BlockTiers.knownItems().contains(id), id);
            }
        }
        assertTrue(BlockTiers.knownBlocks().size() >= 1196, "1.21.11 + 26.2");
    }
}
