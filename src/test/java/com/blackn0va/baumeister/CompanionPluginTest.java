package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Begleiter im Plugin: Einstellungen, Sprachweg zum Host, Blocksuche, Rechte des Befehls. */
class CompanionPluginTest {

    @Test
    void settingsClampValuesAndRejectOddNames() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("begleiter.name", "Ve(ra)*");
        c.set("begleiter.abstand", 100);
        c.set("begleiter.max-geben", 5000);
        CompanionSettings s = CompanionSettings.from(c);
        assertEquals("Vera", s.name(), "Name mit Sonderzeichen fällt auf die Vorgabe zurück");
        assertEquals(8.0, s.distance());
        assertEquals(64, s.maxGive());
        c.set("begleiter.name", "Max");
        assertEquals("Max", CompanionSettings.from(c).name());
        c.set("begleiter.name", "X");
        assertEquals("Vera", CompanionSettings.from(c).name(), "ein Buchstabe ist kein Rufwort");
    }

    @Test
    void companionCommandIsTheOnlyOneWithoutBuildOrTravelRight() {
        assertTrue(BaumeisterCommand.companionSub("begleiter"));
        assertTrue(BaumeisterCommand.companionSub("companion"));
        assertFalse(BaumeisterCommand.companionSub("karte"));
        assertFalse(BaumeisterCommand.companionSub("ort"));
        assertFalse(BaumeisterCommand.companionSub("unbekannt"));
        assertFalse(BaumeisterCommand.needsBuildRight("begleiter"));
    }

    /**
     * plugin.yml lässt seit dem Begleiter auch baumeister.begleiter allein durch. Ein Spieler mit nur diesem Recht darf
     * weder Karte noch Orte – die Prüfung in onCommand muss vor jedem Zugriff auf das Plugin greifen (plugin hier null:
     * käme der Befehl durch, flöge eine NullPointerException).
     */
    @Test
    void companionRightAloneDoesNotOpenMapOrPlaces() {
        List<String> said = new ArrayList<>();
        org.bukkit.command.CommandSender onlyCompanion = (org.bukkit.command.CommandSender) Proxy.newProxyInstance(
                org.bukkit.command.CommandSender.class.getClassLoader(),
                new Class<?>[] {org.bukkit.command.CommandSender.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "hasPermission" -> "baumeister.begleiter".equals(String.valueOf(args[0]));
                    case "sendMessage" -> {
                        said.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "getName" -> "Gast";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        BaumeisterCommand cmd = new BaumeisterCommand(null);
        for (String sub : List.of("karte", "orte", "ort")) {
            said.clear();
            assertTrue(cmd.onCommand(onlyCompanion, null, "bm", new String[] {sub}));
            assertEquals(1, said.size(), sub);
            assertTrue(said.getFirst().contains("baumeister.bauen oder baumeister.reisen"), said.getFirst());
        }
    }

    /**
     * Wunsch 2026-11-04: Ein Host, der nicht antwortet, darf Vera nicht minutenlang stumm lassen.
     * Der Fake-Host nimmt die Anfrage an und antwortet nie; die Frist muss trotzdem nach kurzer Zeit enden.
     */
    @Test
    void silentHostEndsAtTheDeadlineWithAReason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", ex -> {
            try {
                release.await(30, TimeUnit.SECONDS);  // „rechnet“ – länger als jede Frist im Test
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            YamlConfiguration c = new YamlConfiguration();
            c.set("llm.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            Settings s = Settings.from(c);
            LlmClient llm = new LlmClient(s, new com.google.gson.JsonObject(), "begleiter", 50, new long[0]);
            long t0 = System.nanoTime();
            var f = CompanionService.deadline(llm.complete(List.of(LlmClient.message("user", "hallo"))), 300);
            var err = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> f.get(5, TimeUnit.SECONDS));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 3000, "Frist griff erst nach " + ms + " ms");
            assertTrue(CompanionService.failure(err).startsWith("Das dauert mir zu lange"),
                    CompanionService.failure(err));
        } finally {
            release.countDown();
            server.stop(0);
        }
        assertTrue(CompanionService.failure(new LlmClient.LlmException("llm_http_503")).contains("beschäftigt"));
        assertTrue(CompanionService.failure(new java.net.ConnectException("x")).contains("läuft die Baumeister-App"));
    }

    @Test
    void heardSentencesAreReadDefensively() {
        List<String> got = new ArrayList<>();
        // umlaut-ok: „saetze“ ist der ASCII-Schlüssel der Schnittstelle (host/src/server/companion_api.hpp)
        CompanionVoiceLink.deliver("{\"saetze\":[{\"spieler\":\"Blackkn0va\",\"text\":\"Hey Vera, komm\"},"
                + "{\"spieler\":\"x\"},{\"spieler\":\"y\",\"text\":\"  \"},7]}", (p, t) -> got.add(p + ":" + t));
        assertEquals(List.of("Blackkn0va:Hey Vera, komm"), got);
        CompanionVoiceLink.deliver("kein json", (p, t) -> got.add("falsch"));
        CompanionVoiceLink.deliver("{\"saetze\":[{\"spieler\":\"a\",\"text\":\"" + "x".repeat(401) + "\"}]}",
                (p, t) -> got.add("zu lang"));
        assertEquals(1, got.size());
    }

    @Test
    void sayAndLongPollTalkToTheHost() throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> said = new CopyOnWriteArrayList<>();
        CountDownLatch sayGot = new CountDownLatch(1);
        CountDownLatch heardGot = new CountDownLatch(1);
        server.createContext(CompanionVoiceLink.SAY_PATH, ex -> {
            said.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            sayGot.countDown();
        });
        server.createContext(CompanionVoiceLink.HEARD_PATH, ex -> {
            // umlaut-ok: ASCII-Schlüssel der Schnittstelle
            byte[] body = "{\"saetze\":[{\"spieler\":\"Blackkn0va\",\"text\":\"wie spät ist es\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            CompanionVoiceLink link = new CompanionVoiceLink(base, HttpClient.newHttpClient(), System::nanoTime,
                    VoiceAppBinding.unbound());
            link.say("Blackkn0va", "Hallo!");
            List<String> heard = new CopyOnWriteArrayList<>();
            link.listen((p, t) -> {
                heard.add(p + ":" + t);
                heardGot.countDown();
            });
            assertTrue(sayGot.await(5, TimeUnit.SECONDS));
            assertTrue(heardGot.await(5, TimeUnit.SECONDS));
            link.stop();
            assertTrue(said.getFirst().contains("\"spieler\":\"Blackkn0va\""));
            assertTrue(said.getFirst().contains("\"text\":\"Hallo!\""));
            assertEquals("Blackkn0va:wie spät ist es", heard.getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inGameSoundGoesToTheHostAndVoiceComesBack() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> queries = new CopyOnWriteArrayList<>();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        server.createContext(CompanionVoiceLink.HEAR_PATH, ex -> {
            queries.add(ex.getRequestURI().getRawQuery());
            sizes.add(ex.getRequestBody().readAllBytes().length);
            byte[] body = "{\"text\":\"Hey Vera, komm\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext(CompanionVoiceLink.VOICE_PATH, ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = {1, 0, (byte) 0xFE, (byte) 0xFF};  // 1, -2 little-endian
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            CompanionVoiceLink link = new CompanionVoiceLink(base, HttpClient.newHttpClient(), System::nanoTime,
                    VoiceAppBinding.unbound());
            assertEquals(java.util.Optional.of("Hey Vera, komm"),
                    link.hear("Blackkn0va", new byte[32000]).get(5, TimeUnit.SECONDS));
            assertEquals(List.of("spieler=Blackkn0va"), queries);
            assertEquals(List.of(32000), sizes);
            short[] pcm = link.voice("Blackkn0va", "Hallo!").get(5, TimeUnit.SECONDS).orElseThrow();
            assertArrayEquals(new short[] {1, -2}, pcm);
        } finally {
            server.stop(0);
        }
        // Host weg: leer statt Ausnahme – dann bleibt es beim Chat.
        CompanionVoiceLink gone = new CompanionVoiceLink(URI.create("http://127.0.0.1:1/x"), HttpClient.newHttpClient(),
                System::nanoTime, VoiceAppBinding.unbound());
        assertTrue(gone.hear("Blackkn0va", new byte[32000]).get(10, TimeUnit.SECONDS).isEmpty());
        assertTrue(gone.voice("Blackkn0va", "x").get(10, TimeUnit.SECONDS).isEmpty());
    }

    /**
     * Ohne das Plugin „voicechat“ fehlen dessen Klassen zur Laufzeit. Nur CompanionVoicechat (über VoicechatBoot erst
     * nach der Prüfung geladen) darf sie kennen – stünde ein Voice-Chat-Typ in CompanionService oder BaumeisterPlugin,
     * käme der Server ohne Simple Voice Chat nicht mehr hoch (NoClassDefFoundError).
     */
    @Test
    void onlyTheVoicechatHookReferencesVoicechatClasses() throws IOException {
        java.nio.file.Path classes = java.nio.file.Path.of("target/classes/com/blackn0va/baumeister");
        List<String> offenders = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(classes)) {
            for (java.nio.file.Path f : files.filter(x -> x.toString().endsWith(".class")).toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith("CompanionVoicechat")) {
                    continue;
                }
                String bytes = new String(java.nio.file.Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                if (bytes.contains("de/maxhenkel/voicechat")) {
                    offenders.add(name);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    /** ChunkSnapshot ohne Server: Chunk (cx, cz), überall Luft außer den angegebenen Blöcken. */
    private static ChunkSnapshot chunk(int cx, int cz, int[]... blocks) {
        return (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
                new Class<?>[] {ChunkSnapshot.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getX" -> cx;
                    case "getZ" -> cz;
                    case "getBlockType" -> {
                        for (int[] b : blocks) {
                            if (b[0] == (int) args[0] && b[1] == (int) args[1] && b[2] == (int) args[2]) {
                                yield Material.IRON_ORE;
                            }
                        }
                        yield Material.AIR;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void nearestBlockWinsAndRadiusIsRespected() {
        // Spieler bei (8, 64, 8); Eisen in Chunk 0 bei (12, 60, 8) (Abstand 5,7) und in Chunk 1 bei (17, 64, 8) (9).
        List<ChunkSnapshot> snaps = List.of(chunk(0, 0, new int[] {12, 60, 8}), chunk(1, 0, new int[] {1, 64, 8}));
        assertArrayEquals(new int[] {12, 60, 8}, CompanionService.nearest(snaps, java.util.EnumSet.of(Material.IRON_ORE), 8, 64, 8, 32,
                32, 96));
        assertNull(CompanionService.nearest(snaps, java.util.EnumSet.of(Material.IRON_ORE), 8, 64, 8, 4, 60, 68), "Radius 4: nichts");
        assertArrayEquals(new int[] {17, 64, 8}, CompanionService.nearest(snaps, java.util.EnumSet.of(Material.IRON_ORE), 8, 64, 8, 32, 62,
                96), "unter minY (60 < 62) wird nicht gesucht – dann gewinnt das fernere");
    }

    /**
     * Erz-Familie (2026-11-08): Wer Diamant sucht, zählt auch Tiefenschiefer – hier steht im Schnappschuss nur Eisen,
     * gesucht wird Diamant samt Tiefenschiefer: nichts. Mit Eisen in der Menge: gefunden.
     */
    @Test
    void searchCountsEveryMaterialOfTheFamily() {
        List<ChunkSnapshot> snaps = List.of(chunk(0, 0, new int[] {12, 60, 8}));
        assertNull(CompanionService.nearest(snaps, java.util.EnumSet.of(Material.DIAMOND_ORE,
                Material.DEEPSLATE_DIAMOND_ORE), 8, 64, 8, 32, 32, 96));
        assertArrayEquals(new int[] {12, 60, 8}, CompanionService.nearest(snaps, java.util.EnumSet.of(
                Material.DEEPSLATE_IRON_ORE, Material.IRON_ORE), 8, 64, 8, 32, 32, 96));
    }
}
