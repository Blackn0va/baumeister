package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Arbeitsorte (AK-30): Welt, Radius um die Spieler, geladene Chunks, Belegung, Stehpunkt vor einem Bau. */
class WorkSpotsTest {

    private static final WorkSpots.Loaded ALL = (cx, cz) -> true;
    private static final List<double[]> PLAYER_AT_ORIGIN = List.<double[]>of(new double[] {0, 0});

    private static WorkSpots.Spot warp(String name, double x, double z) {
        return WorkSpots.Spot.point(WorkSpots.Source.WARP, name, "welt", x, 64, z);
    }

    @Test
    void onlySpotsInTheSameWorldNearAPlayerInLoadedChunks() {
        List<WorkSpots.Spot> spots = List.of(warp("nah", 20, 0),
                WorkSpots.Spot.point(WorkSpots.Source.WARP, "nether", "welt_nether", 5, 64, 0),
                warp("fern", WorkSpots.RADIUS + 5, 0), warp("ungeladen", 0, -40));
        WorkSpots.Loaded notSouth = (cx, cz) -> cz >= 0;
        List<WorkSpots.Choice> got = WorkSpots.rank(spots, "welt", 0, 0, PLAYER_AT_ORIGIN, notSouth, Map.of());
        assertEquals(List.of("nah"), got.stream().map(c -> c.spot().name()).toList());
    }

    @Test
    void noPlayersNoSpots() {
        assertTrue(WorkSpots.rank(List.of(warp("nah", 1, 1)), "welt", 0, 0, List.of(), ALL, Map.of()).isEmpty());
    }

    @Test
    void fullSpotIsSkippedLeastUsedComesFirst() {
        List<WorkSpots.Spot> spots = List.of(warp("markt", 5, 0), warp("hafen", 30, 0));
        WorkSpots.Spot markt = spots.getFirst();
        assertEquals("markt", WorkSpots.rank(spots, "welt", 0, 0, PLAYER_AT_ORIGIN, ALL, Map.of()).getFirst().spot()
                .name(), "nächster zuerst");
        assertEquals("hafen", WorkSpots.rank(spots, "welt", 0, 0, PLAYER_AT_ORIGIN, ALL, Map.of(markt.key(), 1))
                .getFirst().spot().name(), "weniger belegt zuerst");
        List<WorkSpots.Choice> full = WorkSpots.rank(spots, "welt", 0, 0, PLAYER_AT_ORIGIN, ALL,
                Map.of(markt.key(), WorkSpots.MAX_PER_SPOT));
        assertEquals(List.of("hafen"), full.stream().map(c -> c.spot().name()).toList());
    }

    @Test
    void sameDistanceWarpBeforePlaceBeforeBuild() {
        List<WorkSpots.Spot> spots = List.of(
                WorkSpots.Spot.point(WorkSpots.Source.BUILD, "b", "welt", 10, 64, 0),
                WorkSpots.Spot.point(WorkSpots.Source.PLACE, "p", "welt", 10, 64, 0), warp("w", 10, 0));
        assertEquals(List.of(WorkSpots.Source.WARP, WorkSpots.Source.PLACE, WorkSpots.Source.BUILD),
                WorkSpots.rank(spots, "welt", 0, 0, PLAYER_AT_ORIGIN, ALL, Map.of()).stream()
                        .map(c -> c.spot().source()).toList());
    }

    /** Vor dem Bau, der Wohnung zugewandt – nie im Rahmen, auch wenn die Wohnung darin liegt. */
    @Test
    void buildIsApproachedFromOutsideItsFrame() {
        WorkSpots.Spot house = new WorkSpots.Spot(WorkSpots.Source.BUILD, "haus", "welt", 10, 64, 10, 19, 19);
        double[] west = WorkSpots.approach(house, 0, 15);
        assertEquals(10 - WorkSpots.BESIDE, west[0], 1e-9);
        assertEquals(15, west[1], 1e-9);
        double[] inside = WorkSpots.approach(house, 18.5, 15);
        assertEquals(20 + WorkSpots.BESIDE, inside[0], 1e-9, "zur nächsten Kante (Ost) hinaus");
        double[] corner = WorkSpots.approach(house, 0, 0);
        assertEquals(10 - WorkSpots.BESIDE, corner[0], 1e-9);
        assertEquals(10 - WorkSpots.BESIDE, corner[1], 1e-9);
        double[] point = WorkSpots.approach(warp("markt", 3, 4), 50, 50);
        assertEquals(3, point[0], 1e-9);
        assertEquals(4, point[1], 1e-9);
    }

    @Test
    void notFiniteSpotsAreDropped() {
        assertTrue(WorkSpots.rank(List.of(warp("kaputt", Double.NaN, 0)), "welt", 0, 0, PLAYER_AT_ORIGIN, ALL,
                Map.of()).isEmpty());
    }
}
