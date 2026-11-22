package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownTalk;
import com.blackn0va.baumeister.town.memory.TownVoice;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Messprobe mit echter KI (Vertrag v013, AK-41): Lotte, Emil und ein Spieler unter Pseudonym reden drei Runden. Zeigt
 * Prompt-Folge, Antworten und Antwortzeiten. Läuft nur mit -Dstadtleben.ki.probe=http://127.0.0.1:8080 (llama-server
 * oder BaumeisterHost) – in der normalen Suite übersprungen, weil sie einen Modell-Host braucht.
 *
 * Derselbe Weg wie im Spiel: TownVoice (Lage, Gespräch, Gedächtnis), TownTalk.system/user, der Bewohner-Client
 * (TownLife.client, 20-s-Frist, Begleiter-Schema) und TownTalk.spoken als Filter.
 */
@EnabledIfSystemProperty(named = "stadtleben.ki.probe", matches = "https?://.+")
class TownTalkLlmProbeTest {

    @Test
    void twoResidentsAndAPlayerTalkThreeRounds() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.set("llm.url", System.getProperty("stadtleben.ki.probe") + "/v1/chat/completions");
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of("src/main/resources/begleiter-schema.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        LlmClient llm = TownLife.client(Settings.from(c), schema, "lokal");

        List<Player> players = new ArrayList<>();
        World w = TownLifeTest.world(players, true);
        Player steve = TownLifeTest.player(new Location(w, 3, 64, 0), new CopyOnWriteArrayList<>(), "Zxqvbnm7");
        players.add(steve);
        Roster.Person lotte = Roster.all().get(0);
        Roster.Person emil = Roster.all().get(1);
        Map<Roster.Person, Location> town = new HashMap<>();
        town.put(lotte, new Location(w, 0, 64, 0));
        town.put(emil, new Location(w, 2, 64, 1));
        TownVoice voice = TownVoice.inMemory();
        String alias = voice.alias(steve);

        List<String> playerLines = List.of("Hallo Lotte, was backst du heute?",
                "Klingt lecker! Und was meint Emil dazu?", "Ich bau gleich ein Haus, braucht ihr was dafür?");
        List<Long> times = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            long now = System.currentTimeMillis();
            String heard = voice.scrub(playerLines.get(round), players);
            TownTalk.Situation sl = voice.situation(lotte, town.get(lotte), false, players, town, null, now);
            voice.heard(lotte, steve, heard, town.get(lotte), town, now);
            String lotteSays = ask(llm, lotte, sl, TownTalk.fromPlayer(alias, heard), times, prompts);
            System.out.printf("Runde %d%n  %s: %s%n  Lotte (%d ms): %s%n", round + 1, alias, heard, times.getLast(),
                    lotteSays);
            voice.said(lotte, lotteSays, town.get(lotte), town, System.currentTimeMillis());
            TownTalk.Situation se = voice.situation(emil, town.get(emil), false, players, town, null,
                    System.currentTimeMillis());
            String emilSays = ask(llm, emil, se, TownTalk.meetingReply(lotte), times, prompts);
            System.out.printf("  Emil (%d ms): %s%n", times.getLast(), emilSays);
            voice.said(emil, emilSays, town.get(emil), town, System.currentTimeMillis());
        }
        System.out.println("Letzter Prompt an Emil:\n" + prompts.getLast());
        long sum = 0;
        long max = 0;
        for (long t : times) {
            sum += t;
            max = Math.max(max, t);
        }
        System.out.printf("Antwortzeiten: Mittel %d ms, höchstens %d ms, %d Anfragen%n", sum / times.size(), max,
                times.size());
        for (String p : prompts) {
            assertFalse(p.contains("Zxqvbnm7"), "Spielername an die KI");
        }
        assertEquals(6, times.size());
        assertTrue(prompts.getLast().contains("Bisher im Gespräch"), prompts.getLast());
    }

    private static String ask(LlmClient llm, Roster.Person p, TownTalk.Situation s, String user, List<Long> times,
            List<String> prompts) throws Exception {
        String sys = TownTalk.system(p, s);
        String u = TownTalk.user(s, user);
        prompts.add(sys + "\n---\n" + u);
        long t0 = System.nanoTime();
        LlmClient.Reply r = llm.complete(List.of(LlmClient.message("system", sys), LlmClient.message("user", u)))
                .get(30, TimeUnit.SECONDS);
        times.add((System.nanoTime() - t0) / 1_000_000);
        Optional<String> said = TownTalk.spoken(r.content(), p.name());
        return said.orElse("(Filter/leer → fester Satz) roh=" + r.content());
    }
}
