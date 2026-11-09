package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Gedächtnis für Folgeaufträge: Koordinaten packen, Plan verschieben, alter Plan im LLM-Text. */
class BuildMemoryTest {

    @Test
    void packedPositionsRoundTripIncludingNegativeCoordinates() {
        int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {30_000_000, 319, -30_000_000}, {-29_999_999, -2032, 12_345},
            {123, 2000, -456}};
        for (int[] c : cases) {
            long k = BuildService.pack(c[0], c[1], c[2]);
            assertEquals(c[0], BuildService.unpackX(k), "x von " + List.of(c[0], c[1], c[2]));
            assertEquals(c[1], BuildService.unpackY(k), "y");
            assertEquals(c[2], BuildService.unpackZ(k), "z");
        }
    }

    @Test
    void translateShiftsOnlyHorizontalCoordinatesOfEveryStep() {
        String plan = "{\"design\":\"d\",\"reply\":\"r\",\"mode\":\"new\",\"steps\":[{\"op\":\"fill\",\"block\":\"stone\","
                + "\"from\":[0,0,0],\"to\":[6,0,6]},{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]}]}";
        var out = JsonParser.parseString(BuildService.translate(plan, -3, 2)).getAsJsonObject();
        var steps = out.getAsJsonArray("steps");
        assertEquals("[-3,0,2]", steps.get(0).getAsJsonObject().get("from").toString());
        assertEquals("[3,0,8]", steps.get(0).getAsJsonObject().get("to").toString());
        assertEquals("[0,1,6]", steps.get(1).getAsJsonObject().get("pos").toString());
        assertFalse(out.has("reply"), "reply gehört nicht ins Gedächtnis");
    }

    @Test
    void previousBuildAndViewsReachTheLlm() {
        // Haus-Ecke auf flachem Boden, damit die Ansichten erscheinen (freier flacher Platz → keine Ansichten).
        SiteSurvey s = SiteSurvey.scan(new Pos(-4, -1, -2), new Pos(8, 6, 10), p -> {
            if (p.y() == -1) {
                return "grass_block";
            }
            boolean wall = p.y() >= 0 && p.y() <= 3 && p.x() >= 0 && p.x() <= 4 && p.z() >= 2 && p.z() <= 6
                    && (p.x() == 0 || p.x() == 4 || p.z() == 2 || p.z() == 6);
            return wall ? "oak_planks" : "air";
        });
        ModuleLibrary none = new ModuleLibrary() {
            @Override
            public Optional<Module> find(String name) {
                return Optional.empty();
            }

            @Override
            public List<Module> all() {
                return List.of();
            }
        };
        String msg = BuildService.userMessage("mach das Dach rot", s, 3, none, "{\"steps\":[]}", "bau ein Haus", 5);
        assertTrue(msg.contains("TOP VIEW") && msg.contains("FRONT VIEW") && msg.contains("Legend"), msg);
        assertTrue(msg.contains("PREVIOUS BUILD") && msg.contains("bau ein Haus") && msg.contains("mode \"update\""),
                msg);
        assertTrue(msg.contains("#"), "Wände als # in den Ansichten");
        String plain = BuildService.userMessage("bau ein Haus", s, 3, none);
        assertFalse(plain.contains("PREVIOUS BUILD"));
    }
}
