package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.ComfortFlowTest.Req;
import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * AK-8 (Absturz-Audit 2026-10-30): Prüfsumme des Bauplatzes und sichere Ankunft bei /bm ort lesen Blöcke erst, wenn
 * deren Chunks über ChunkPrefetch geladen sind. Ein ungeladener Chunk wird nie still als leer gelesen – der
 * Überbau-Schutz hängt an der Prüfsumme (fail-closed).
 */
class ArrivalAndFingerprintLoadTest {

    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** Chunks unter dem Scan-Kasten (lokal x −16..32, z −6..36). */
    private static final long[] KEYS = keys();

    /** Chunk mit dem Haus des Nachbarn (lokal x 20..24, z 30..34). */
    private static final long HOUSE = ChunkPrefetch.key(1, 1);

    private static long[] keys() {
        List<Long> k = new ArrayList<>();
        for (int cx = Math.floorDiv(SiteFrame.SCAN_MIN.x(), 16); cx <= Math.floorDiv(SiteFrame.SCAN_MAX.x(), 16); cx++) {
            for (int cz = Math.floorDiv(SiteFrame.SCAN_MIN.z(), 16); cz <= Math.floorDiv(SiteFrame.SCAN_MAX.z(), 16);
                    cz++) {
                k.add(ChunkPrefetch.key(cx, cz));
            }
        }
        return k.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Gelände: Stein unter y 0, ein Haus aus Brettern, sonst Luft. Ein ungeladener Chunk liest sich als Luft – so
     * sähe ein Lesen ohne Ladeprüfung aus; unloadedReads zählt, ob das je passiert.
     */
    private static final class Site implements Function<Pos, String> {
        final ChunkPrefetchTest.FakeHost host;
        int unloadedReads;

        Site(ChunkPrefetchTest.FakeHost host) {
            this.host = host;
        }

        @Override
        public String apply(Pos p) {
            if (!host.loaded(Math.floorDiv(p.x(), 16), Math.floorDiv(p.z(), 16))) {
                unloadedReads++;
                return "air";
            }
            if (p.y() < 0) {
                return "stone";
            }
            boolean house = p.x() >= 20 && p.x() <= 24 && p.z() >= 30 && p.z() <= 34 && p.y() <= 3;
            return house ? "oak_planks" : "air";
        }
    }

    /** Port wie ComfortService: loadSite und fingerprint über SiteFingerprint, mit Ersatz-Server statt Welt. */
    private static final class LoadingPort implements ComfortFlow.Port<Req, String> {
        final ChunkPrefetchTest.FakeHost host;
        final Site site;
        boolean busy;
        final List<String> built = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        LoadingPort(ChunkPrefetchTest.FakeHost host) {
            this.host = host;
            this.site = new Site(host);
        }

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public UUID owner(Req r) {
            return r.owner();
        }

        @Override
        public String ownerName(Req r) {
            return r.name();
        }

        @Override
        public String prompt(Req r) {
            return r.prompt();
        }

        @Override
        public Req refresh(Req r) {
            return r;
        }

        @Override
        public void plan(Req r, boolean preview) {
        }

        @Override
        public long fingerprint(Req r) {
            return SiteFingerprint.ofLoaded(host, KEYS, site);
        }

        @Override
        public void loadSite(Req r, Runnable then) {
            SiteFingerprint.whenLoaded(host, KEYS, then);
        }

        @Override
        public void previewOffered(Req r) {
        }

        @Override
        public void build(Req r, String plan) {
            built.add(plan);
        }

        @Override
        public void tell(Req r, String text) {
            said.add(text);
        }

        @Override
        public String where(Req r) {
            return "bei 0 / 64 / 0";
        }
    }

    private static ChunkPrefetchTest.FakeHost allLoaded(boolean paper) {
        ChunkPrefetchTest.FakeHost host = new ChunkPrefetchTest.FakeHost(paper);
        for (long k : KEYS) {
            host.loaded.add(k);
        }
        return host;
    }

    /** Server weiterdrehen, bis nichts mehr lädt (Paper: Futures fertig, Spigot: Takt). */
    private static void settle(ChunkPrefetchTest.FakeHost host) {
        for (int i = 0; i < 100; i++) {
            host.pending.forEach(f -> f.complete(null));
            host.pending.clear();
            if (!host.tick() && host.pending.isEmpty() && host.main.isEmpty()) {
                return;
            }
        }
    }

    /** Vorschau zeigen (Prüfsumme beim Zeigen) und danach den Haus-Chunk entladen – der Spieler ging weiter. */
    private static ComfortFlow<Req, String> previewThenWalkAway(LoadingPort port) {
        ComfortFlow<Req, String> flow = new ComfortFlow<>(port, 10, 2, 120_000, () -> 1_000_000L);
        Req r = new Req(ANNA, "Anna", "bau einen turm", 0);
        assertTrue(flow.divert(r, true, "turm", () -> new ComfortFlow.Shown("world",
                BuildPreview.Box.of(0, 64, 0, 6, 70, 8), 300, port.fingerprint(r))));
        assertTrue(flow.previews().has(ANNA), "Vorschau nicht angelegt");
        port.host.loaded.remove(HOUSE);
        return flow;
    }

    // Prüfsumme: ungeladene Chunks werden vorher geladen, nicht als leer gelesen
    @Test
    void fingerprintLoadsUnloadedChunksFirstInsteadOfReadingThemAsEmpty() {
        for (boolean paper : new boolean[] {true, false}) {
            LoadingPort port = new LoadingPort(allLoaded(paper));
            ComfortFlow<Req, String> flow = previewThenWalkAway(port);

            assertEquals(ComfortFlow.Outcome.LOADING, flow.confirm(ANNA), "erst laden, nicht sofort lesen");
            assertEquals(List.of(), port.built, "vor dem Laden gebaut");
            settle(port.host);

            assertEquals(List.of("turm"), port.built, "nach dem Laden muss die unveränderte Vorschau bauen: "
                    + port.said);
            assertEquals(0, port.site.unloadedReads, "ungeladener Chunk wurde gelesen (paper=" + paper + ")");
            assertTrue(port.host.tickets.isEmpty(), "Tickets nach dem Vergleich nicht freigegeben");
        }
    }

    @Test
    void anUnloadedChunkIsNeverCountedAsEmpty() {
        ChunkPrefetchTest.FakeHost host = allLoaded(false);
        Site site = new Site(host);
        long loaded = SiteFingerprint.ofLoaded(host, KEYS, site);
        host.loaded.remove(HOUSE);

        assertEquals(SiteFingerprint.UNREADABLE, SiteFingerprint.ofLoaded(host, KEYS, site));
        assertEquals(0, site.unloadedReads, "ofLoaded hat einen ungeladenen Chunk gelesen");
        // Ohne Ladeprüfung sähe der Bauplatz ohne Haus aus – genau das darf nie als Prüfsumme gelten.
        assertNotEquals(loaded, SiteFingerprint.of(site), "Testgelände taugt nicht: Haus fällt nicht auf");
        assertNotEquals(loaded, SiteFingerprint.UNREADABLE);
    }

    @Test
    void aSiteThatCannotBeLoadedIsNotBuilt() {
        LoadingPort port = new LoadingPort(allLoaded(false));
        ComfortFlow<Req, String> flow = previewThenWalkAway(port);
        port.host.broken = Set.of(HOUSE);

        assertEquals(ComfortFlow.Outcome.LOADING, flow.confirm(ANNA));
        settle(port.host);

        assertEquals(List.of(), port.built, "ungeprüft gebaut");
        assertEquals(0, port.site.unloadedReads);
        assertTrue(port.said.getLast().contains("nicht laden"), port.said.toString());
    }

    @Test
    void whileTheSiteLoadsNoQueuedOrderMovesUp() {
        LoadingPort port = new LoadingPort(allLoaded(false));
        ComfortFlow<Req, String> flow = previewThenWalkAway(port);
        assertEquals(ComfortFlow.Outcome.LOADING, flow.confirm(ANNA));

        // Ein zweites „ja“ bzw. ein eingereihter Auftrag darf nicht parallel starten.
        assertTrue(flow.enqueue(new Req(UUID.randomUUID(), "Bert", "bau einen brunnen", 0), false));
        flow.pump();
        assertEquals(1, flow.queue().size(), "Auftrag rückte nach, während der Bauplatz lud");
        settle(port.host);
        assertEquals(List.of("turm"), port.built);
    }

    @Test
    void aLoadedSiteKeepsTheFingerprintOfBefore() {
        // Bis 2026-11-21 stand die Summe so in SiteFingerprint.of(SiteFrame); sie muss Wert für Wert gleich bleiben,
        // sonst passen Vorschauen über /bm neuladen hinweg nicht mehr.
        ChunkPrefetchTest.FakeHost host = allLoaded(false);
        Site site = new Site(host);
        long h = 1125899906842597L;
        for (int x = SiteFrame.SCAN_MIN.x(); x <= SiteFrame.SCAN_MAX.x(); x++) {
            for (int y = SiteFrame.SCAN_MIN.y(); y <= SiteFrame.SCAN_MAX.y(); y++) {
                for (int z = SiteFrame.SCAN_MIN.z(); z <= SiteFrame.SCAN_MAX.z(); z++) {
                    String id = site.apply(new Pos(x, y, z));
                    h = 31 * h + (id == null ? -1 : SiteFingerprint.bucket(id));
                }
            }
        }
        assertEquals(h, SiteFingerprint.ofLoaded(host, KEYS, site));
    }

    // ---------------------------------------------------------------- /bm ort

    /** Säule bei x 5, z 7: bis y 69 Stein, wenn der Chunk geladen ist; ungeladen läse sie sich als Luft. */
    private static PlaceRegistry.BlockProbe column(ChunkPrefetchTest.FakeHost host, int[] unloadedReads) {
        return (x, y, z) -> {
            if (!host.loaded(x >> 4, z >> 4)) {
                unloadedReads[0]++;
                return true;
            }
            return y >= 70;
        };
    }

    @Test
    void travelLoadsTheTargetChunkBeforeLookingForASafeHeight() {
        for (boolean paper : new boolean[] {true, false}) {
            ChunkPrefetchTest.FakeHost host = new ChunkPrefetchTest.FakeHost(paper);
            int[] unloadedReads = {0};
            List<PlaceRegistry.Arrival> got = new ArrayList<>();
            PlaceRegistry.arrival(host, 5, 62, 7, -64, 320, column(host, unloadedReads), got::add);
            assertEquals(List.of(), got, "Säule im Tick des Befehls gelesen – vorher laden");

            settle(host);
            assertEquals(List.of(new PlaceRegistry.Arrival(true, OptionalInt.of(70))), got,
                    "ungeladene Säule als frei gelesen (paper=" + paper + ")");
            assertEquals(0, unloadedReads[0]);
            assertTrue(host.tickets.isEmpty(), "Ticket nicht freigegeben");
        }
    }

    @Test
    void travelToATargetThatCannotBeLoadedDoesNotTeleport() {
        ChunkPrefetchTest.FakeHost host = new ChunkPrefetchTest.FakeHost(false);
        host.broken = Set.of(ChunkPrefetch.key(0, 0));
        int[] unloadedReads = {0};
        List<PlaceRegistry.Arrival> got = new ArrayList<>();
        PlaceRegistry.arrival(host, 5, 62, 7, -64, 320, column(host, unloadedReads), got::add);
        settle(host);

        assertEquals(1, got.size());
        assertFalse(got.getFirst().loaded(), "nicht ladbarer Ort als geladen gemeldet");
        assertEquals(0, unloadedReads[0], "ungeladene Säule gelesen");
    }

    @Test
    void travelToALoadedTargetAnswersInTheSameTick() {
        ChunkPrefetchTest.FakeHost host = new ChunkPrefetchTest.FakeHost(false);
        host.loaded.add(ChunkPrefetch.key(0, 0));
        List<PlaceRegistry.Arrival> got = new ArrayList<>();
        PlaceRegistry.arrival(host, 5, 62, 7, -64, 320, column(host, new int[1]), got::add);
        assertEquals(List.of(new PlaceRegistry.Arrival(true, OptionalInt.of(70))), got, "Normalfall ohne Umweg");
    }
}
