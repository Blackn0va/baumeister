package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stand eines Stadtbaus über Pause und Neustart (Nutzer 2026-10-29). */
class CityResumeTest {

    private static CityResume.State eberdingen() {
        return new CityResume.State("Eberdingen", 48.8784, 8.9670, 6500, 12_000_000L, "stadtwelt", -3000, -61, 4000,
                724, 2449, false, UUID.fromString("6f2b4a1e-0000-4000-8000-000000000001"), "Blackkn0va", 41);
    }

    @Test
    void savedStateComesBackUnchanged(@TempDir Path dir) {
        CityResume r = new CityResume(dir.resolve("stadt").resolve("fortsetzen.json"));
        r.save(eberdingen());
        assertTrue(r.exists());
        assertEquals(eberdingen(), r.load().orElseThrow());
        r.save(eberdingen().at(725, true));
        CityResume.State s = r.load().orElseThrow();
        assertEquals(725, s.stage());
        assertTrue(s.paused());
        r.delete();
        assertFalse(r.exists());
        assertTrue(r.load().isEmpty());
    }

    @Test
    void brokenOrImplausibleFilesAreNeverGuessedButPutAside(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("fortsetzen.json");
        CityResume r = new CityResume(f);
        for (String bad : new String[] {"{kaputt",
            "{\"v\":1,\"ort\":\"x\",\"lat\":48,\"lon\":9,\"radius\":500,\"welt\":\"w\",\"x\":0,\"y\":0,\"z\":0,"
                    + "\"etappe\":9,\"etappen\":5}",
            "{\"v\":1,\"ort\":\"x\",\"lat\":48,\"lon\":9,\"radius\":500,\"welt\":\"w\",\"etappe\":0,\"etappen\":5}",
            "{\"v\":1,\"ort\":\"x\",\"lat\":148,\"lon\":9,\"radius\":500,\"welt\":\"w\",\"x\":0,\"y\":0,\"z\":0,"
                    + "\"etappe\":0,\"etappen\":5}"}) {
            Files.writeString(f, bad);
            assertTrue(r.load().isEmpty(), bad);
            assertFalse(Files.exists(f), "beiseitegelegt: " + bad);
            assertTrue(Files.exists(dir.resolve("fortsetzen.json.kaputt")));
        }
    }

    @Test
    void standFromBeforeTheTravelSpotStillLoads(@TempDir Path dir) throws Exception {
        // dev01 hatte am 2026-10-30 einen Stand ohne reise_gebäude – er muss weiter fortsetzbar sein.
        Path f = dir.resolve("fortsetzen.json");
        Files.writeString(f, "{\"v\":1,\"ort\":\"Eberdingen\",\"lat\":48.88,\"lon\":8.94,\"radius\":2000,\"welt\":"
                + "\"stadtwelt\",\"x\":-468,\"y\":-61,\"z\":2476,\"etappe\":15,\"etappen\":225,\"pause\":true}");
        CityResume.State s = new CityResume(f).load().orElseThrow();
        assertEquals(0, s.travel());
        assertEquals(15, s.stage());
    }
}
