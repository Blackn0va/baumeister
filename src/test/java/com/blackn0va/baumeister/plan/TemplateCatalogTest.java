package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class TemplateCatalogTest {

    /** Ersatz für VanillaTemplates: jedes Haus ein Kasten mit Straßenanschluss, Größe aus dem Namen abgeleitet. */
    static final TemplateCatalog.Source SOURCE = name -> {
        if (TemplateCatalog.key(name).isEmpty()) {
            return Optional.empty();
        }
        int w = 5 + Math.floorMod(name.hashCode(), 6);
        int d = 5 + Math.floorMod(name.hashCode() / 7, 5);
        return Optional.of(TemplateModules.build(name, "", TemplateModulesTest.house(w, 6, d, "west_up", 0, 0,
                d / 2)));
    };

    static final ModuleLibrary LIB = new ModuleLibrary() {
        @Override
        public Optional<Module> find(String name) {
            return SOURCE.template(name).map(TemplateModules.Template::module);
        }

        @Override
        public List<Module> all() {
            return List.of();
        }
    };

    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static Optional<TemplateCatalog.Match> strict(String prompt) {
        return TemplateCatalog.match(prompt, SOURCE, TemplateCatalog.Biome.PLAINS, 192, 1, true);
    }

    private static List<JsonObject> modules(String json) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("steps")) {
            if (e.getAsJsonObject().get("op").getAsString().equals("module")) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    @Test
    void everyNameHasAKeyAndNamesAreUnique() {
        List<String> names = TemplateCatalog.names();
        assertEquals(names.size(), new HashSet<>(names).size());
        for (String n : names) {
            assertTrue(TemplateCatalog.key(n).isPresent(), n);
            assertTrue(n.length() <= 32, "Bauteilnamen höchstens 32 Zeichen (Plan-Schema): " + n);
        }
        assertEquals("village/desert/houses/desert_small_house_1", TemplateCatalog.key("desert_small_house_1").get());
        assertEquals("village/plains/town_centers/plains_fountain_01", TemplateCatalog.key("plains_fountain_01").get());
        assertEquals("igloo/top", TemplateCatalog.key("iglu").get());
    }

    @Test
    void namedStructuresBuildWithoutLlm() throws PlanException {
        for (String p : List.of("bau mir ein Iglu", "Kannst du mir ein Piratenschiff bauen?", "ein Ruinenportal",
                "bau einen Wachturm", "eine Schmiede im Schneestil", "baue mir ein Wüstenhaus", "eine Kirche",
                "ein Dorfhaus", "eine Bibliothek im Taiga Stil")) {
            Optional<TemplateCatalog.Match> m = strict(p);
            assertTrue(m.isPresent(), p);
            COMPILER.compile(PlanParser.parse(m.get().json()));
        }
        assertTrue(modules(strict("eine Schmiede im Schneestil").get().json()).getFirst().get("name").getAsString()
                .startsWith("snowy_"));
    }

    @Test
    void moreWholeGameStructuresAndTheirVariantsRotate() throws PlanException {
        // 2026-11-14: alle ganzen Schiffswracks, Fossilien, Netherfossilien und Unterwasserruinen – keine Bruchstücke.
        assertEquals("nether_fossils/fossil_14", TemplateCatalog.key("netherfossil_14").get());
        assertEquals("shipwreck/upsidedown_full_degraded", TemplateCatalog.key("schiffswrack_6").get());
        assertEquals("underwater_ruin/warm_8", TemplateCatalog.key("kleine_ruine_warm_8").get());
        assertTrue(TemplateCatalog.names().stream().noneMatch(n -> TemplateCatalog.key(n).get().contains("half")),
                "halbe Schiffe sind Bruchstücke");
        java.util.Set<String> wrecks = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            Optional<TemplateCatalog.Match> m = TemplateCatalog.match("bau mir ein Schiffswrack", SOURCE,
                    TemplateCatalog.Biome.PLAINS, 192, seed, true);
            assertTrue(m.isPresent());
            wrecks.add(modules(m.get().json()).getFirst().get("name").getAsString());
        }
        assertTrue(wrecks.size() > 1, "Varianten wechseln: " + wrecks);
        assertTrue(wrecks.stream().allMatch(n -> n.startsWith("schiffswrack")), wrecks.toString());
        for (String p : List.of("bau mir ein Netherfossil", "eine kleine Ruine", "ein Fossil", "eine Ruine")) {
            Optional<TemplateCatalog.Match> m = strict(p);
            assertTrue(m.isPresent(), p);
            COMPILER.compile(PlanParser.parse(m.get().json()));
        }
    }

    @Test
    void plainHouseStaysWithLlmUnlessFallback() {
        // Gegenprobe zur Regel „schlichtes Haus bleibt beim LLM“: Material- und Zusatzwünsche kann die Vorlage nicht.
        assertTrue(strict("bau mir ein Haus").isEmpty());
        assertTrue(strict("ein Haus aus Fichtenholz mit Pool").isEmpty());
        assertTrue(strict("ein Iglu mit Pool und Rutsche").isEmpty(), "Zusatzwünsche → LLM");
        Optional<TemplateCatalog.Match> loose = TemplateCatalog.match("ein Haus aus Fichtenholz mit Pool", SOURCE,
                TemplateCatalog.Biome.TAIGA, 192, 1, false);
        assertTrue(loose.isPresent(), "Rückfall nach dem LLM nimmt ein Haus");
        assertTrue(modules(loose.get().json()).getFirst().get("name").getAsString().startsWith("taiga_"));
        assertTrue(TemplateCatalog.match("der Eiffelturm", SOURCE, TemplateCatalog.Biome.PLAINS, 192, 1, false)
                .isEmpty(), "ohne passendes Wort kein falscher Ersatzbau");
    }

    @Test
    void villageHasHousesFacingTheStreetWithoutOverlap() throws PlanException {
        Optional<TemplateCatalog.Match> m = strict("bau mir ein Wüstendorf mit 8 Häusern");
        assertTrue(m.isPresent());
        List<JsonObject> mods = modules(m.get().json());
        assertEquals(9, mods.size(), "8 Häuser + Dorfplatz");
        List<int[]> rects = new ArrayList<>();
        for (JsonObject o : mods) {
            String name = o.get("name").getAsString();
            assertTrue(name.startsWith("desert_"), name);
            Pos size = SOURCE.template(name).get().module().size();
            JsonArray p = o.getAsJsonArray("pos");
            String facing = o.get("facing").getAsString();
            boolean side = !facing.equals("front");
            int x0 = p.get(0).getAsInt();
            int z0 = p.get(2).getAsInt();
            int x1 = x0 + (side ? size.z() : size.x()) - 1;
            int z1 = z0 + (side ? size.x() : size.z()) - 1;
            if (side) {
                assertTrue(x1 < -2 || x0 > 2, "Haus auf der Straße: " + name);
                assertEquals(facing.equals("left"), x0 > 2, "Tür zur Straße: " + name);
            }
            for (int[] r : rects) {
                boolean apart = x1 < r[0] || x0 > r[1] || z1 < r[2] || z0 > r[3];
                assertTrue(apart, "Häuser überlappen: " + name);
            }
            rects.add(new int[] {x0, x1, z0, z1});
        }
        CompiledBuild b = COMPILER.compile(PlanParser.parse(m.get().json()));
        assertTrue(b.blockCount() > 0);
    }

    @Test
    void villageShrinksToMaxExtent() {
        Optional<TemplateCatalog.Match> m = TemplateCatalog.match("ein großes Dorf", SOURCE,
                TemplateCatalog.Biome.PLAINS, 40, 3, true);
        assertTrue(m.isPresent());
        int n = modules(m.get().json()).size();
        assertTrue(n >= 2 && n < 11, "weniger Häuser statt Absage: " + n);
    }

    @Test
    void biomeFromBukkitKey() {
        assertEquals(TemplateCatalog.Biome.SNOWY, TemplateCatalog.biomeOf("minecraft:snowy_taiga"));
        assertEquals(TemplateCatalog.Biome.TAIGA, TemplateCatalog.biomeOf("minecraft:old_growth_spruce_taiga"));
        assertEquals(TemplateCatalog.Biome.DESERT, TemplateCatalog.biomeOf("minecraft:desert"));
        assertEquals(TemplateCatalog.Biome.PLAINS, TemplateCatalog.biomeOf("minecraft:forest"));
        assertEquals(TemplateCatalog.Biome.SNOWY, TemplateCatalog.biomeOf("minecraft:cold_ocean"));
        assertEquals(TemplateCatalog.Biome.PLAINS, TemplateCatalog.biomeOf("minecraft:warm_ocean"));
    }

    @Test
    void speechRecognitionSlipsStillMatch() {
        // dev01 2026-11-04 16:08: Whisper hörte „Iglo“ – das ging ans LLM und wurde ein Eiszylinder.
        assertTrue(strict("bau mir ein Iglo").isPresent());
        assertTrue(strict("baue eine Kirsche").isPresent(), "ein Zeichen daneben");
        assertTrue(strict("eine Bibliotek").isPresent());
        assertTrue(strict("baue ein Geld").isEmpty(), "kurze Wörter nicht unscharf");
        assertTrue(strict("bau mir ein Hause").isEmpty(), "Hauswörter bleiben beim LLM");
    }

    @Test
    void missingTemplatesGiveNoMatch() {
        assertFalse(TemplateCatalog.match("bau mir ein Iglu", n -> Optional.empty(), TemplateCatalog.Biome.PLAINS,
                192, 1, true).isPresent());
    }
}
