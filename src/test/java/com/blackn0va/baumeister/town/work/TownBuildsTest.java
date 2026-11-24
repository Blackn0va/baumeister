package com.blackn0va.baumeister.town.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bauen im Baufeld ohne Server (Vertrag v013, AK-45): Katalog aus vorhandenen Plan-Formen ohne Behälter und Redstone,
 * Platzwahl nur auf freiem, ebenem Boden im Feld (Überbau-Schutz vor dem Bau), Schutzstellen in fester Reihenfolge,
 * Merken über Neustart und Rückgängig-Erkennung.
 */
class TownBuildsTest {

    private static final TownBuilds BUILDS = new TownBuilds(id -> true);

    private static Field field(int radius, boolean build) {
        return new Field("dorf", "testwelt", 0, 64, 0, radius, UUID.randomUUID(), build);
    }

    /**
     * Jede Art an jeder Ecke des Feldes: alle Aufträge, die fit zulässt. Über plan allein käme immer zuerst der Zaun
     * (Art reihum nach Anzahl der Bauten) – Hütte und Brunnen wären nie geprüft.
     */
    private static List<TownBuilds.Order> sweep(Field f, Plot p) {
        List<TownBuilds.Order> out = new java.util.ArrayList<>();
        for (TownBuilds.Kind k : TownBuilds.Kind.values()) {
            for (int x = f.x() - f.radius(); x <= f.x() + f.radius(); x++) {
                for (int z = f.z() - f.radius(); z <= f.z() + f.radius(); z++) {
                    for (com.blackn0va.baumeister.plan.Orientation.Cardinal c
                            : com.blackn0va.baumeister.plan.Orientation.Cardinal.values()) {
                        TownBuilds.Order o = BUILDS.fit(f, p, k, x, z, c, key -> false);
                        if (o != null) {
                            out.add(o);
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void everyKindTranslatesWithoutContainersOrRedstone() {
        assertEquals(EnumSet.allOf(TownBuilds.Kind.class), BUILDS.kinds(), "Katalog-Eintrag übersetzt nicht");
        for (TownBuilds.Kind k : TownBuilds.Kind.values()) {
            assertTrue(BUILDS.blocks(k) > 0, k.key());
            for (String t : BUILDS.types(k)) {
                assertFalse(Safety.forbidden(t), k.key() + " setzt " + t + " (Behälter oder Redstone)");
                assertFalse(t.equals("lava") || t.equals("tnt") || t.equals("fire"), k.key() + ": " + t);
            }
        }
        assertTrue(BUILDS.blocks(TownBuilds.Kind.HUETTE) <= 200, "Kleinbau ist kein Kleinbau mehr");
    }

    /**
     * Auf freier Wiese (Feld Radius 10 wie in der Live-Probe) bekommt jede Art ihren Platz, reihum. Live-Probe T-037,
     * Lauf 1: Weil die erste passende Ecke vor der Art gewann, entstanden in 20 min nur Zaun, Laterne und Marktstand.
     */
    @Test
    void everyKindFindsRoomOnAnOpenField() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        Field f = field(10, true);
        TownBuilds.Built dummy = new TownBuilds.Built(TownBuilds.Kind.ZAUN, 0, 64, 0, 0, 0, 0, 64, 0, "oak_fence", 0);
        for (int i = 0; i < TownBuilds.Kind.values().length; i++) {
            List<TownBuilds.Built> standing = java.util.Collections.nCopies(i, dummy);
            TownBuilds.Found found = BUILDS.plan(f, g, standing, 0, k -> false);
            assertNotNull(found.order(), "kein Platz für " + TownBuilds.Kind.values()[i]);
            assertEquals(TownBuilds.Kind.values()[i], found.order().kind(), "statt der Art reihum etwas anderes");
        }
    }

    @Test
    void nothingIsPlannedOutsideTheFieldOrOverAnything() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        // Ein Haus aus Stein am Rand, eine Kiste, ein Hebel, Weizen: nichts davon darf überbaut oder berührt werden.
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                g.set(x, 64, z, "cobblestone");
            }
        }
        g.set(-4, 64, 4, "chest");
        g.set(4, 64, -4, "lever");
        g.crop(-4, 64, -4, "wheat", 7);
        Field f = field(8, true);
        List<TownBuilds.Order> orders = sweep(f, g);
        assertFalse(orders.isEmpty(), "auf freier Wiese kein Platz gefunden");
        for (TownBuilds.Order o : orders) {
            for (int[] c : o.cells()) {
                String before = g.type(c[0], c[1], c[2]);
                assertTrue(f.contains("testwelt", c[0], c[1], c[2]), o.kind() + " ragt aus dem Feld");
                assertTrue(c[1] >= 64 ? TownBuilds.REPLACEABLE.contains(before) : Safety.GROUND.contains(before),
                        o.kind() + " überbaut " + before + " bei " + c[0] + "," + c[1] + "," + c[2]);
                assertFalse(Safety.nearForbidden(g, c[0], c[1], c[2]), o.kind() + " neben Kiste/Hebel");
                // Abstand: kein Block direkt am Steinhaus.
                assertFalse(c[0] >= 1 && c[0] <= 7 && c[2] >= 1 && c[2] <= 7, o.kind() + " klebt am Bestand");
            }
        }
    }

    /**
     * Ein Blätterdach vier Ebenen über dem Boden (Obstwiese, y 68): Nur was darunter passt (Zaun, Laterne, Beet,
     * Marktstand, Brunnen) darf hin – nie ein Block ins Laub. Gegenprobe T-037: Ohne die Zellprüfung in fit stand das
     * Dach der Hütte im Laub (tiefer hängendes Laub fängt schon die Bodensuche ab).
     */
    @Test
    void floatingBlocksAreNeverBuiltInto() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                g.set(x, 68, z, "oak_leaves");
            }
        }
        Field f = field(8, true);
        List<TownBuilds.Order> orders = sweep(f, g);
        assertTrue(orders.stream().anyMatch(o -> o.kind() == TownBuilds.Kind.LATERNE), "Laterne passt nicht unters Laub");
        for (TownBuilds.Order o : orders) {
            for (int[] c : o.cells()) {
                assertFalse(g.type(c[0], c[1], c[2]).equals("oak_leaves"), o.kind() + " baut ins Laub bei " + c[0]
                        + "," + c[1] + "," + c[2]);
            }
        }
    }

    @Test
    void slopesAndHolesAreNoBuildingGround() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        // Treppenstufen alle zwei Blöcke: nirgends sind drei Spalten eben.
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (Math.floorMod(x + z, 2) == 0) {
                    g.set(x, 64, z, "grass_block");
                    g.set(x, 63, z, "dirt");
                }
            }
        }
        assertTrue(sweep(field(8, true), g).isEmpty(), "am Hang gebaut");
        FieldWorkTest.Grid water = new FieldWorkTest.Grid();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                water.set(x, 63, z, "water");
            }
        }
        assertTrue(sweep(field(8, true), water).isEmpty(), "auf Wasser gebaut");
    }

    /**
     * Baufeld mit Gärtnerin (Radius 4 wie live): Ihr Wegkreuz durch die Mitte und die Blumen am Rand (Regel aus
     * Planner.garden). Über plan – so wie TownLife sucht, Raster und Art reihum – entstehen nacheinander auch andere
     * Bauten als Laternen, und nie steht ein Bau auf dem Weg. Live gemessen (T-038, Paper 26.2): ohne Weg im Rand und
     * mit dem Raster ab dem Feldrand vier Laternen und sonst nichts.
     */
    @Test
    void gardenerPathsLeaveRoomForBuilds() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        Field f = field(4, true);
        java.util.Set<Long> paths = new java.util.HashSet<>();
        for (int dx = -f.radius(); dx <= f.radius(); dx++) {
            for (int dz = -f.radius(); dz <= f.radius(); dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if ((dx == 0 || dz == 0) && ring <= f.radius() - 1) {
                    g.set(dx, 63, dz, "dirt_path");
                    paths.add(((long) dx << 32) | (dz & 0xFFFFFFFFL));
                } else if (ring == f.radius() - 1 && Math.floorMod(dx + dz, 3) == 0) {
                    g.set(dx, 64, dz, "poppy");
                }
            }
        }
        List<TownBuilds.Built> standing = new java.util.ArrayList<>();
        java.util.Set<TownBuilds.Kind> kinds = EnumSet.noneOf(TownBuilds.Kind.class);
        int cursor = 0;
        for (int i = 0; i < 6; i++) {
            TownBuilds.Found found = BUILDS.plan(f, g, standing, cursor, k -> false);
            cursor = found.cursor();
            TownBuilds.Order o = found.order();
            if (o == null) {
                continue;
            }
            kinds.add(o.kind());
            for (int[] c : o.cells()) {
                assertFalse(paths.contains(((long) c[0] << 32) | (c[2] & 0xFFFFFFFFL)),
                        o.kind() + " steht auf dem Weg bei " + c[0] + "," + c[2]);
                g.set(c[0], c[1], c[2], c[1] >= 64 ? "oak_planks" : "farmland");
            }
            standing.add(new TownBuilds.Built(o.kind(), o.x(), o.y(), o.z(), o.x(), o.z(), o.x(), o.y(), o.z(),
                    "oak_planks", 0));
        }
        assertTrue(kinds.stream().anyMatch(k -> k != TownBuilds.Kind.LATERNE), "nach dem Wegkreuz nur noch: " + kinds);
    }

    @Test
    void cornersSitBesideTheCenterLines() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {-4, -1, 1, 4}, TownBuilds.offsets(4));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {-10, -7, -4, -1, 1, 4, 7, 10},
                TownBuilds.offsets(10));
    }

    @Test
    void gateChecksEverySwitchInOrder() {
        BuildSettings on = new BuildSettings(true, 2, 1);
        Field yes = field(8, true);
        assertNull(TownBuilds.gate(on, true, yes, false, 0, 1000, 0));
        assertNotNull(TownBuilds.gate(new BuildSettings(false, 2, 1), true, yes, false, 0, 1000, 0), "Schalter aus");
        assertNotNull(TownBuilds.gate(on, false, yes, false, 0, 1000, 0), "Weltänderung aus");
        assertNotNull(TownBuilds.gate(on, true, field(8, false), false, 0, 1000, 0), "kein Baufeld");
        assertNotNull(TownBuilds.gate(on, true, null, false, 0, 1000, 0), "kein Feld");
        assertNotNull(TownBuilds.gate(on, true, yes, true, 0, 1000, 0), "zweiter laufender Bau");
        assertNotNull(TownBuilds.gate(on, true, yes, false, 2, 1000, 0), "Obergrenze");
        assertNull(TownBuilds.gate(on, true, yes, false, 1, 1000, 0));
        assertNotNull(TownBuilds.gate(on, true, yes, false, 1, 1000, 2000), "Pause");
    }

    @Test
    void rememberedBuildCountsUntilItIsGone() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        TownBuilds.Order o = sweep(field(8, true), g).getFirst();
        assertNull(BUILDS.remember(o, g, 1), "nichts gebaut, trotzdem gemerkt");
        List<String> types = BUILDS.types(o.kind());
        for (int i = 0; i < o.cells().size(); i++) {
            int[] c = o.cells().get(i);
            g.set(c[0], c[1], c[2], types.get(i));
        }
        TownBuilds.Built b = BUILDS.remember(o, g, 1);
        assertNotNull(b);
        assertTrue(b.standing(g));
        g.set(b.ax(), b.ay(), b.az(), "air");
        assertFalse(b.standing(g), "abgerissener Bau steht noch");
        assertTrue(TownBuilds.standing(List.of(b), g).isEmpty());
    }

    /** Das Beet ersetzt Boden durch Boden – nach einem Rückgängig darf es trotzdem nicht weiter zählen. */
    @Test
    void bedAnchorsAboveTheGroundSoUndoIsSeen() {
        FieldWorkTest.Grid g = new FieldWorkTest.Grid();
        TownBuilds.Order o = BUILDS.fit(field(8, true), g, TownBuilds.Kind.BEET, -3, -3, k -> false);
        assertNotNull(o, "Beet passt nicht auf freie Wiese");
        List<String> types = BUILDS.types(o.kind());
        for (int i = 0; i < o.cells().size(); i++) {
            int[] c = o.cells().get(i);
            g.set(c[0], c[1], c[2], types.get(i));
        }
        TownBuilds.Built b = BUILDS.remember(o, g, 1);
        assertNotNull(b);
        assertTrue(b.ay() >= o.y(), "Merkstelle im Boden");
        for (int[] c : o.cells()) {
            if (c[1] >= o.y()) {
                g.set(c[0], c[1], c[2], "air");
            }
        }
        assertFalse(b.standing(g), "Beet nach dem Rückgängig noch gezählt");
    }

    @Test
    void buildFlagAndBuildsSurviveARestartAndOldFilesReadAsNoBuildField(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(Fields.FILE);
        Fields fs = new Fields(file);
        fs.load();
        assertNull(fs.put(new Field("dorf", "testwelt", 0, 64, 0, 8, null)));
        assertFalse(fs.get("dorf").build(), "neues Feld ist Baufeld");
        assertNull(fs.setBuild("dorf", true));
        TownBuilds.Built b = new TownBuilds.Built(TownBuilds.Kind.ZAUN, -5, 64, -5, -1, -5, -5, 64, -5, "oak_fence",
                42);
        assertTrue(fs.setBuilt("dorf", List.of(b)));
        Fields again = new Fields(file);
        assertTrue(again.load().isEmpty());
        assertTrue(again.get("dorf").build());
        assertEquals(List.of(b), again.built("dorf"));
        assertNotNull(again.setBuild("gibtsnicht", true));
        // Neu gesetzt: kein Baufeld, keine alten Bauten.
        assertNull(again.put(new Field("dorf", "testwelt", 10, 64, 10, 8, null)));
        assertFalse(again.get("dorf").build());
        assertTrue(again.built("dorf").isEmpty());

        Files.writeString(file, "{\"fassung\":1,\"felder\":[{\"name\":\"alt\",\"welt\":\"w\",\"x\":0,\"y\":64,"
                + "\"z\":0,\"radius\":4,\"besitzer\":null}]}", StandardCharsets.UTF_8);
        Fields old = new Fields(file);
        assertTrue(old.load().isEmpty());
        assertFalse(old.get("alt").build(), "alte Datei als Baufeld gelesen");
        assertTrue(old.built("alt").isEmpty());
    }

    @Test
    void buildSettingsFollowTheTemplateAndClamp() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.File(
                "src/main/resources/config.yml"));
        assertTrue(bundled.isBoolean(BuildSettings.KEY_ENABLED), "Schlüssel fehlt in der Vorlage");
        assertEquals(BuildSettings.defaults(), BuildSettings.from(bundled), "Vorlage weicht von der Vorgabe ab");
        assertFalse(BuildSettings.from(bundled).enabled(), "Vorgabe aus");
        YamlConfiguration c = new YamlConfiguration();
        c.set(BuildSettings.KEY_PER_FIELD, 1000);
        c.set(BuildSettings.KEY_PAUSE, -5);
        assertEquals(BuildSettings.MAX_PER_FIELD, BuildSettings.from(c).perField());
        assertEquals(BuildSettings.MIN_PAUSE, BuildSettings.from(c).pauseMinutes());
        assertEquals(BuildSettings.defaults(), BuildSettings.from(null));
    }
}
