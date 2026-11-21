package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Deutsche Bedienhinweise der Farmen (Nutzer 2026-11-08). Jede Zahl und jedes Lagewort im Hinweis ist hier am Bau
 * nachgezählt – „9 Werfer hinten“, „Truhe unten rechts“, „getöntes Glas oben“: Wer eine Farm umbaut, sieht hier, welcher
 * Satz nicht mehr stimmt, statt dass der Spieler es im Spiel merkt. Lage von vorn gesehen, rechts = +x (wie Baupläne).
 */
class FarmGuideTest {

    private static final Machines LIB = new Machines();

    private static Machines.Machine machine(String module) {
        return LIB.machine(module).orElseThrow(() -> new AssertionError("kein Bauteil " + module));
    }

    private static Map<Pos, BlockSpec> cells(String module) {
        Map<Pos, BlockSpec> out = new HashMap<>();
        for (Change c : machine(module).module().cells()) {
            out.put(c.pos(), c.block());
        }
        return out;
    }

    private static List<Pos> find(Map<Pos, BlockSpec> c, String id) {
        return c.entrySet().stream().filter(e -> e.getValue().id().equals(id)).map(Map.Entry::getKey).toList();
    }

    private static FarmGuide.Guide guide(String module) {
        return FarmGuide.of(module).orElseThrow(() -> new AssertionError("kein Hinweis für " + module));
    }

    private static String text(String module) {
        return String.join(" ", guide(module).steps());
    }

    @Test
    void everyFarmOrderHasAGermanGuideAndEveryGuideABuild() {
        for (String module : new TreeSet<>(QuickOrders.FARMS.values())) {
            FarmGuide.Guide g = guide(module);
            assertFalse(g.title().isBlank() || g.steps().isEmpty(), module);
            machine(module);
        }
        // Umgekehrt: kein Hinweis für ein Bauteil, das es nicht gibt – ein Tippfehler im Namen fände nie einen Plan.
        for (String module : FarmGuide.modules()) {
            machine(module);
        }
    }

    @Test
    void repliesAndLinesFitTheChat() {
        for (String module : FarmGuide.modules()) {
            FarmGuide.Guide g = guide(module);
            String reply = FarmGuide.acceptReply(module).orElseThrow();
            // BuildService.acceptPlan kürzt die Antwort auf 200 Zeichen, mitten im Satz.
            assertTrue(reply.length() <= 200, module + ": " + reply.length() + " Zeichen");
            assertTrue(reply.contains(g.title()) && reply.contains(g.prepare()), reply);
            List<String> lines = g.lines();
            assertTrue(lines.getFirst().startsWith(g.title() + " – so geht's: "), lines.getFirst());
            for (String line : lines) {
                assertTrue(line.length() <= 240, module + ": " + line.length() + " Zeichen: " + line);
            }
        }
    }

    @Test
    void wheatGuideMatchesTheBuild() {
        Map<Pos, BlockSpec> c = cells("weizenfarm");
        Pos size = machine("weizenfarm").module().size();
        List<Pos> dispensers = find(c, "dispenser");
        assertEquals(9, dispensers.size());
        assertTrue(dispensers.stream().allMatch(p -> p.z() == size.z() - 1), "Werfer hinten");
        Pos lever = find(c, "lever").getFirst();
        assertTrue(lever.x() == size.x() - 1 && lever.z() == size.z() - 1, "Hebel hinten rechts: " + lever);
        Pos chest = find(c, "chest").getFirst();
        assertTrue(chest.x() == size.x() - 1 && chest.z() == 0, "Truhe vorn rechts: " + chest);
        assertFalse(find(c, "wheat").isEmpty(), "gesät");
        String t = text("weizenfarm");
        assertTrue(t.contains("9 Werfer hinten") && t.contains("Hebel hinten rechts") && t.contains("Truhe vorn rechts")
                && t.contains("gesät"), t);
        assertEquals("9 Wassereimer", guide("weizenfarm").prepare());
    }

    @Test
    void ironGuideMatchesTheBuild() {
        Map<Pos, BlockSpec> c = cells("eisenfarm");
        Pos size = machine("eisenfarm").module().size();
        // Bettplätze: ausdrücklich freigeräumte Felder auf Betthöhe, je zwei hintereinander, oben in der hinteren Hälfte.
        List<Pos> bedCells = c.entrySet().stream().filter(e -> e.getValue().isAir() && e.getKey().y() == 10
                && c.get(e.getKey().add(0, -1, 0)) != null && c.get(e.getKey().add(0, -1, 0)).id().equals("stone")
                && c.get(e.getKey().add(0, 3, 0)) != null && c.get(e.getKey().add(0, 3, 0)).id().equals("glass"))
                .map(Map.Entry::getKey).toList();
        assertEquals(6, bedCells.size(), "3 Betten à 2 Felder");
        assertTrue(bedCells.stream().allMatch(p -> p.y() > size.y() / 2 && p.z() > size.z() / 2), "oben hinten");
        int front = bedCells.stream().mapToInt(Pos::z).min().orElseThrow();
        int back = bedCells.stream().mapToInt(Pos::z).max().orElseThrow();
        for (int x = 1; x <= 3; x++) {
            // „Stell dich auf ihre vordere Glaswand und setz durch den Spalt unter dem Glasdach …“
            assertEquals("glass", c.get(new Pos(x, 11, front - 1)).id(), "vordere Glaswand");
            assertTrue(c.get(new Pos(x, 12, front - 1)) == null, "Platz über der Glaswand");
            assertEquals("glass", c.get(new Pos(x, 13, front)).id(), "Glasdach über der Kammer");
            assertTrue(c.get(new Pos(x, 12, front)) == null, "Spalt unter dem Glasdach");
        }
        // „Hinter den Betten zwei Käfige, unten rechts und oben in der Mitte: Stein hinten abbauen …“: je zwei freie
        // Felder hinter der Bettreihe, der obere in der mittleren Spalte, der untere rechts davon und tiefer; dahinter
        // Stein (die Wand, die der Spieler abbaut). Über dem oberen getöntes Glas (Sonne).
        // Käfig = zwei freie Felder übereinander (die einzelnen Felder neben dem Kopf des oberen zählen nicht).
        List<Pos> cages = c.entrySet().stream().filter(e -> e.getValue().isAir() && e.getKey().z() > back)
                .map(Map.Entry::getKey).filter(p -> c.get(p.add(0, 1, 0)) != null && c.get(p.add(0, 1, 0)).isAir())
                .toList();
        assertEquals(2, cages.size(), "zwei Käfige: " + cages);
        Pos low = cages.stream().min(java.util.Comparator.comparingInt(Pos::y)).orElseThrow();
        Pos high = cages.stream().max(java.util.Comparator.comparingInt(Pos::y)).orElseThrow();
        assertEquals(2, high.x(), "oben in der Mitte");
        assertTrue(low.x() > high.x() && high.y() > low.y(), "unten rechts: " + low);
        for (Pos p : cages) {
            assertEquals("stone", c.get(p.add(0, 0, 1)).id(), "Stein hinten am Käfig " + p);
            assertEquals("stone", c.get(p.add(0, 1, 1)).id(), "Stein hinten am Käfig " + p.add(0, 1, 0));
        }
        List<Pos> tinted = find(c, "tinted_glass");
        assertEquals(List.of(high.add(0, 2, 0)), tinted, "getöntes Glas über dem oberen Zombie");
        assertEquals(3, find(c, "daylight_detector").size(), "Tageslichtsensoren");
        assertEquals(3, find(c, "iron_trapdoor").size(), "Falltüren");
        Pos chest = find(c, "chest").getFirst();
        assertTrue(chest.x() == size.x() - 1 && chest.y() == 1, "Truhe unten rechts: " + chest);
        assertFalse(find(c, "soul_campfire").isEmpty());
        String t = text("eisenfarm");
        assertTrue(t.contains("3 Betten nebeneinander") && t.contains("unten rechts und oben in der Mitte")
                && t.contains("Stein hinten abbauen") && t.contains("Tageslichtsensoren")
                && t.contains("Truhe unten rechts") && t.contains("Seelenlagerfeuern"), t);
        assertTrue(t.contains("bei Tag und bei Nacht") && !t.contains("nachts nicht"), t);
        assertTrue(guide("eisenfarm").prepare().startsWith("3 Betten, 3 Dorfbewohner, 2 Zombies"));
    }

    @Test
    void chickenGuideMatchesTheBuild() {
        Map<Pos, BlockSpec> c = cells("huehnerfarm"); // umlaut-ok
        Pos size = machine("huehnerfarm").module().size(); // umlaut-ok
        List<Pos> cellsWithHopper = find(c, "hopper").stream()
                .filter(p -> c.get(p).props().getOrDefault("facing", "").equals("front")).toList();
        assertEquals(3, cellsWithHopper.size());
        for (Pos h : cellsWithHopper) {
            // Zelle aus Glas, oben offen.
            for (Pos side : List.of(h.add(1, 1, 0), h.add(-1, 1, 0), h.add(0, 1, 1), h.add(0, 1, -1))) {
                assertEquals("glass", c.get(side).id(), "Zellwand " + side);
            }
            assertTrue(c.get(h.add(0, 1, 0)) == null && c.get(h.add(0, 2, 0)) == null
                    && c.get(h.add(0, 3, 0)) == null, "Zelle oben offen über " + h);
        }
        Pos chest = find(c, "chest").getFirst();
        assertEquals(size.x() - 1, chest.x(), "Truhe rechts");
        String t = text("huehnerfarm"); // umlaut-ok
        assertTrue(t.contains("3 Glaszellen") && t.contains("Truhe rechts") && t.contains("von oben"), t);
    }

    @Test
    void cobbleGuideMatchesTheBuild() {
        Machines.Machine m = machine("bruchsteingenerator");
        Map<Pos, BlockSpec> c = cells("bruchsteingenerator");
        Pos lava = m.outputs().stream().filter(p -> p.name().equals("LAVA")).findFirst().orElseThrow().at();
        Pos cobble = m.outputs().stream().filter(p -> p.name().equals("BRUCHSTEIN")).findFirst().orElseThrow().at();
        assertTrue(lava.x() < cobble.x() && lava.y() == cobble.y() && lava.z() == cobble.z(), "Fach links daneben");
        assertTrue(c.get(lava.add(0, 1, 0)) == null, "Fach von oben offen");
        BlockSpec opening = c.get(cobble.add(0, 0, -1));
        assertTrue(opening == null || opening.isAir(), "Öffnung vorn");
        String t = text("bruchsteingenerator");
        assertTrue(t.contains("von oben in das Fach links") && t.contains("Öffnung vorn"), t);
        assertEquals("einen Lavaeimer", guide("bruchsteingenerator").prepare());
    }

    @Test
    void xpGuideMatchesTheBuild() {
        Map<Pos, BlockSpec> c = cells("xp_farm");
        List<Pos> chests = find(c, "chest");
        assertEquals(2, chests.size(), "zwei Truhen");
        for (Pos p : chests) {
            assertEquals(0, p.y(), "im Boden");
            assertEquals("glass", c.get(p.add(0, 1, 1)).id(), "Truhe vor der Scheibe: " + p);
            assertEquals("smooth_stone_slab", c.get(p.add(0, 2, 1)).id(), "Stufe über der Scheibe: " + p);
        }
        String t = text("xp_farm");
        assertTrue(t.contains("zwei Truhen im Boden vor der Scheibe") && t.contains("Spalt über der Stufe"), t);
    }

    @Test
    void plantFarmGuidesMatchTheBuild() {
        for (String module : List.of("kuerbisfarm", "melonenfarm")) { // umlaut-ok
            Map<Pos, BlockSpec> c = cells(module);
            List<Pos> stems = c.entrySet().stream().filter(e -> e.getValue().id().endsWith("_stem"))
                    .map(Map.Entry::getKey).toList();
            assertEquals(4, stems.size(), module);
            for (Pos s : stems) {
                assertEquals("7", c.get(s).props().get("age"), module + " ausgewachsen");
                assertEquals("dirt", c.get(s.add(1, -1, 0)).id(), module + " Fruchtfeld rechts daneben");
                assertEquals("observer", c.get(s.add(0, 1, 0)).id(), module + " Beobachter über dem Stängel");
            }
            String t = text(module);
            assertTrue(t.contains("4 Stängel sind ausgewachsen") && t.contains("rechts daneben")
                    && t.contains("Beobachter über dem Stängel"), t);
        }
        assertFalse(find(cells("bambusfarm"), "bamboo").isEmpty(), "Bambus gepflanzt");
        Map<Pos, BlockSpec> cactus = cells("kaktusfarm");
        assertFalse(find(cactus, "cactus").isEmpty(), "Kakteen gepflanzt");
        // „Das dritte Stück bricht am Zaun ab“: Zaunpfosten auf Höhe des dritten Stücks neben jedem Kaktus.
        for (Pos f : find(cactus, "oak_fence")) {
            assertEquals("cactus", cactus.get(f.add(-1, -1, 0)).id(), "zweites Stück unter dem Zaun links: " + f);
        }
    }

    @Test
    void planScanFindsEachFarmOnceInOrderAndNothingElse() {
        String plan = "{\"steps\":[{\"op\":\"module\",\"name\":\"weizenfarm\"},"
                + "{\"op\":\"module\",\"name\":\"sammelstelle\"},{\"op\":\"wall\"},"
                + "{\"op\":\"module\",\"name\":\"eisenfarm\"},{\"op\":\"module\",\"name\":\"weizenfarm\"},"
                + "{\"op\":\"module\",\"name\":\"eisenfarm" + ModuleTransforms.MIRRORED + "\"}]}";
        assertEquals(List.of("weizenfarm", "eisenfarm"),
                FarmGuide.inPlan(plan).stream().map(FarmGuide.Guide::module).toList());
        assertTrue(FarmGuide.inPlan(null).isEmpty());
        assertTrue(FarmGuide.inPlan("kein json {").isEmpty());
        assertTrue(FarmGuide.inPlan("[1,2]").isEmpty());
        assertTrue(FarmGuide.inPlan("{\"steps\":[{\"op\":[1],\"name\":\"eisenfarm\"}]}").isEmpty());
        assertTrue(FarmGuide.acceptReply("sammelstelle").isEmpty(), "Bauteile ohne Hinweis behalten ihre Antwort");
    }

    @Test
    void quickOrderPlanCarriesTheGuideToTheBuildEnd() {
        // Der Schnellauftrag schreibt den Bauteilnamen so in den Plan, dass BuildService die Hinweise nach dem Bau
        // wiederfindet, und antwortet beim Auftrag mit dem, was bereitliegen soll.
        String plan = QuickOrders.farm("bau mir eine Eisenfarm").orElseThrow();
        assertEquals(List.of("eisenfarm"), FarmGuide.inPlan(plan).stream().map(FarmGuide.Guide::module).toList());
        String reply = JsonParser.parseString(plan).getAsJsonObject().get("reply").getAsString();
        assertTrue(reply.startsWith("Ich baue dir die Eisenfarm. Leg schon bereit: 3 Betten"), reply);
        assertTrue(reply.endsWith("Die Anleitung kommt, sobald alles steht."), reply);
    }

    /**
     * Mehrsorten-Sortierer (2026-11-21): Zahl der Filter, Lage von Einwurf und Rest-Truhe und die Sorten aus dem Namen
     * stimmen mit dem Bau überein. Gespiegelt gibt es keinen Hinweis (links/rechts vertauscht), der Ein-Sorten-Sortierer
     * behält seine Antwort ohne Hinweis.
     */
    @Test
    void multiSorterGuideMatchesTheBuild() {
        for (int n = Machines.MULTI_SORTER_MIN; n <= Machines.MULTI_SORTER_MAX; n++) {
            String module = Machines.MULTI_SORTER + n;
            Map<Pos, BlockSpec> c = cells(module);
            String t = text(module);
            assertTrue(t.contains("der " + n + " mittleren Trichter"), t);
            long hoppersOverChests = find(c, "chest").stream().filter(p -> p.y() == 1)
                    .filter(p -> c.get(p.add(0, 1, 1)) != null && c.get(p.add(0, 1, 1)).id().equals("hopper")).count();
            assertEquals(n, hoppersOverChests, module);
            // „Trichter vorn links“: Einwurf bei x=0, z=0; „Truhe hinten rechts“: die einzige Truhe oben, ganz rechts.
            assertEquals("hopper", c.get(new Pos(0, 3, 0)).id());
            List<Pos> top = find(c, "chest").stream().filter(p -> p.y() == 3).toList();
            assertEquals(1, top.size());
            assertEquals(machine(module).module().size().x() - 4, top.getFirst().x());
            assertTrue(top.getFirst().z() > 0, "Rest hinten");
            assertTrue(FarmGuide.acceptReply(module).orElseThrow().length() <= 200);
            assertTrue(guide(module).prepare().contains("18 Stück"));
        }
        FarmGuide.Guide full = guide("item_sortierer_2:cobblestone,sand");
        assertTrue(full.prepare().isEmpty(), "vorbefüllt: nichts bereitlegen");
        assertTrue(String.join(" ", full.steps()).contains("von links nach rechts: cobblestone, sand."));
        assertTrue(guide("item_sortierer_3:sand").steps().getFirst().startsWith("Füll jeden leeren der 3"));
        assertTrue(FarmGuide.of("item_sortierer_3_gespiegelt").isEmpty());
        assertTrue(FarmGuide.of("item_sortierer_9").isEmpty());
        assertTrue(FarmGuide.acceptReply("item_sortierer").isEmpty());
    }
}
