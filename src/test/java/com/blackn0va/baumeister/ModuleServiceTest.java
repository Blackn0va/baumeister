package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.module.ModuleDef;
import com.blackn0va.baumeister.module.ModuleDirectory;
import com.blackn0va.baumeister.module.ModuleEntry;
import com.blackn0va.baumeister.module.ModuleState;
import com.blackn0va.baumeister.module.ModuleSwitch;
import com.blackn0va.baumeister.module.SendGate;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.junit.jupiter.api.Test;

/**
 * Verdrahtung des Modulverzeichnisses (Vertrag v012, AK-37): Kanal baumeister:module nur an Spieler mit Mod, bei
 * Änderung und bei der Kanal-Anmeldung; POST an /baumeister/v1/module mit Bremse und fail-open; Schalten über den
 * Befehl nur mit Admin; Tab-Vervollständigung; Anmeldung in onEnable.
 */
class ModuleServiceTest {

    private static final long S = 1_000_000_000L;

    /** Spieler, der eine Nutzlast bekommen kann; mod = Client hat den Kanal angemeldet. */
    private static Player player(String name, boolean mod) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getListeningPluginChannels" -> mod ? Set.of(ModuleService.CHANNEL) : Set.of();
                    case "getName" -> name;
                    case "isOnline" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    case "toString" -> name;
                    default -> null;
                });
    }

    private static CommandSender sender(boolean admin, List<String> said) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> admin || !ModuleSwitch.ADMIN.equals(String.valueOf(a[0]));
                    case "sendMessage" -> {
                        said.add(String.valueOf(a[0]));
                        yield null;
                    }
                    case "getName" -> "Gast";
                    default -> null;
                });
    }

    /** Fakten zum Verstellen, Spieler, gesendete Nutzlasten, Schalterwerte. */
    private static final class FakeEnv implements ModuleService.Env {
        final Map<String, Boolean> config = new HashMap<>(Map.of("stadtleben.an", false, "karte.an", true));
        boolean townUp = true;
        final List<Player> players = new ArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        int llmChecks;

        @Override
        public ModuleDirectory.Facts facts() {
            return new ModuleDirectory.Facts(config, Map.of(), true, true, townUp, true, Set.of(), false, null,
                    false, true, false, false, 0, false);
        }

        @Override
        public Collection<? extends Player> players() {
            return players;
        }

        @Override
        public void send(Player p, byte[] payload) {
            sent.add(p.getName() + ":" + new String(payload, StandardCharsets.UTF_8));
        }

        @Override
        public void checkLlm(Consumer<Boolean> done) {
            llmChecks++;
            done.accept(true);
        }

        @Override
        public void later(Runnable r, long ticks) {
            later.add(r);
        }

        @Override
        public ModuleSwitch.Target target(CommandSender by) {
            return new ModuleSwitch.Target() {
                @Override
                public boolean current(String key) {
                    return Boolean.TRUE.equals(config.get(key));
                }

                @Override
                public void write(String key, boolean on) {
                    writes.add(key + "=" + on);
                    config.put(key, on);
                }

                @Override
                public boolean applyLive(ModuleDef def, boolean on) {
                    if (!def.id().equals("stadtleben")) {
                        return false;
                    }
                    write(def.key(), on);
                    return true;
                }
            };
        }

        @Override
        public void openMenu(Player p) {
        }
    }

    /** Fake-Host: zeichnet Ziel und Körper auf, antwortet mit status (oder wirft). */
    private static final class FakeHost implements HostReporter.Transport {
        final List<String> bodies = new ArrayList<>();
        final List<URI> targets = new ArrayList<>();
        int status = 204;
        boolean explode;

        @Override
        public CompletableFuture<Integer> post(URI uri, String json) {
            if (explode) {
                throw new IllegalStateException("kaputt");
            }
            targets.add(uri);
            bodies.add(json);
            return CompletableFuture.completedFuture(status);
        }
    }

    private static final URI HOST = URI.create("http://127.0.0.1:18080/baumeister/v1/module");

    @Test
    void channelGoesOnlyToPlayersWithTheModAndOnlyOnChange() {
        FakeEnv env = new FakeEnv();
        env.players.add(player("mitMod", true));
        env.players.add(player("ohneMod", false));
        long[] now = {0};
        ModuleService s = new ModuleService(env, null, new FakeHost(), () -> now[0]);
        s.refresh();
        assertEquals(1, env.sent.size(), env.sent.toString());
        assertTrue(env.sent.getFirst().startsWith("mitMod:{\"version\":1,\"modules\":["), env.sent.getFirst());
        now[0] += 5 * S;
        s.refresh();
        assertEquals(1, env.sent.size(), "unverändert erneut gesendet");
        env.config.put("stadtleben.an", true);
        s.refresh();
        assertEquals(2, env.sent.size(), "Änderung nicht gesendet");
        assertTrue(env.sent.get(1).contains("\"id\":\"stadtleben\",\"name\":\"Stadtleben\""), env.sent.get(1));
    }

    @Test
    void registeringTheChannelSendsTheCurrentStateAtOnce() {
        FakeEnv env = new FakeEnv();
        ModuleService s = new ModuleService(env, null, new FakeHost(), () -> 0);
        s.onRegister(new PlayerRegisterChannelEvent(player("neu", true), ModuleService.CHANNEL));
        s.onRegister(new PlayerRegisterChannelEvent(player("anderer", true), "minecraft:brand"));
        assertEquals(1, env.sent.size(), env.sent.toString());
        assertTrue(env.sent.getFirst().startsWith("neu:{\"version\":1"), env.sent.getFirst());
    }

    @Test
    void postGoesToTheModulePathAtMostEverySixtySeconds() {
        FakeEnv env = new FakeEnv();
        FakeHost host = new FakeHost();
        long[] now = {0};
        ModuleService s = new ModuleService(env, HOST, host, () -> now[0]);
        s.refresh();
        assertEquals(List.of(HOST), host.targets);
        assertEquals(s.lastJson(), host.bodies.getFirst());
        env.config.put("stadtleben.an", true);
        now[0] += 10 * S;
        s.refresh();
        assertEquals(1, host.bodies.size(), "zweiter POST nach 10 s");
        now[0] += SendGate.MIN_GAP_NANOS;
        s.refresh();
        assertEquals(2, host.bodies.size());
        assertTrue(host.bodies.get(1).contains("\"id\":\"stadtleben\""));
    }

    @Test
    void hostFailureIsFailOpenAndRetried() {
        FakeEnv env = new FakeEnv();
        env.players.add(player("mitMod", true));
        FakeHost host = new FakeHost();
        host.status = 503;
        long[] now = {0};
        ModuleService s = new ModuleService(env, HOST, host, () -> now[0]);
        s.refresh();
        now[0] += SendGate.MIN_GAP_NANOS;
        s.refresh();
        assertEquals(2, host.bodies.size(), "nicht zugestellter Stand nicht wiederholt");
        // Ein werfender Sender beendet weder den Takt noch den Kanal.
        host.explode = true;
        env.config.put("stadtleben.an", true);
        now[0] += SendGate.MIN_GAP_NANOS;
        s.refresh();
        assertEquals(2, env.sent.size(), env.sent.toString());
        host.explode = false;
        host.status = 204;
        now[0] += SendGate.MIN_GAP_NANOS;
        s.refresh();
        assertEquals(3, host.bodies.size());
    }

    @Test
    void healthOfTheBuildKiIsAskedAtMostOncePerMinute() {
        FakeEnv env = new FakeEnv();
        long[] now = {0};
        ModuleService s = new ModuleService(env, null, new FakeHost(), () -> now[0]);
        s.refresh();
        now[0] += 30 * S;
        s.refresh();
        assertEquals(1, env.llmChecks);
        now[0] += 30 * S;
        s.refresh();
        assertEquals(2, env.llmChecks);
        assertEquals(Boolean.TRUE, s.llmUp());
    }

    @Test
    void commandWithoutAdminWritesNothingAndWithAdminSwitchesAndPushes() {
        FakeEnv env = new FakeEnv();
        env.players.add(player("mitMod", true));
        ModuleService s = new ModuleService(env, null, new FakeHost(), () -> 0);
        s.refresh();
        List<String> said = new ArrayList<>();
        s.command(sender(false, said), new String[] {"module", "an", "stadtleben"});
        assertTrue(env.writes.isEmpty(), env.writes.toString());
        assertTrue(said.getFirst().contains("baumeister.admin"), said.toString());
        assertEquals(1, env.sent.size());
        s.command(sender(true, said), new String[] {"module", "an", "stadtleben"});
        assertEquals(List.of("stadtleben.an=true"), env.writes);
        assertEquals(2, env.sent.size(), "Änderung nicht sofort an die Mod");
    }

    @Test
    void listColoursEachStateAndShowsTheReason() {
        FakeEnv env = new FakeEnv();
        env.config.put("stadtleben.an", true);
        env.townUp = false;
        ModuleService s = new ModuleService(env, null, new FakeHost(), () -> 0);
        List<String> said = new ArrayList<>();
        s.command(sender(false, said), new String[] {"module"});
        String town = said.stream().filter(l -> l.contains("Stadtleben")).findFirst().orElseThrow();
        assertTrue(town.contains(ModuleState.WARTET.color() + "wartet"), town);
        assertTrue(town.contains("wartet auf KI"), town);
        assertTrue(said.getLast().contains("nur Admins"), said.getLast());
        assertEquals(ModuleDirectory.ALL.size() + 2, said.size());
    }

    @Test
    void voiceProblemNeverCarriesAPath() {
        assertEquals("sprache.piper zeigt auf kein ausführbares Piper",
                ModuleService.voiceProblem("sprache.piper zeigt auf kein ausführbares Piper (C:\\Users\\x\\piper.exe)"));
        assertFalse(ModuleService.voiceProblem("sprache.stimme: Modell oder /home/x/de.onnx.json fehlt")
                .contains("/home"));
        assertNull(ModuleService.voiceProblem(null));
    }

    @Test
    void tabCompletionOffersSwitchWordsAndIdsOnlyToAdmins() {
        List<String> none = List.of();
        assertTrue(BaumeisterCommand.complete(new String[] {"mod"}, false, true, false, none, none, none, none)
                .contains("module"));
        assertEquals(List.of("liste", "menü"), BaumeisterCommand.complete(new String[] {"module", ""}, false, true,
                false, none, none, none, none));
        assertTrue(BaumeisterCommand.complete(new String[] {"module", ""}, true, true, true, none, none, none, none)
                .containsAll(List.of("an", "aus")));
        assertEquals(none, BaumeisterCommand.complete(new String[] {"module", "an", ""}, false, true, false, none,
                none, none, none));
        List<String> ids = BaumeisterCommand.complete(new String[] {"module", "an", "st"}, true, true, true, none,
                none, none, none);
        assertEquals(List.of("stadtleben"), ids);
    }

    @Test
    void menuClickSwitchesOnlyForAdminsAndLoreShowsStateAndReason() {
        ModuleEntry town = new ModuleEntry(ModuleDirectory.find("stadtleben").orElseThrow(), true,
                ModuleState.WARTET, "wartet auf KI – ohne KI keine Bewohner");
        assertEquals("bm module aus stadtleben", ModuleMenu.clickCommand(town, true).orElseThrow());
        assertTrue(ModuleMenu.clickCommand(town, false).isEmpty());
        ModuleEntry fixed = new ModuleEntry(ModuleDirectory.find("bau-ki").orElseThrow(), true, ModuleState.AKTIV, "");
        assertTrue(ModuleMenu.clickCommand(fixed, true).isEmpty(), "fester Teil schaltbar");
        String lore = String.join("\n", ModuleMenu.lore(town, false));
        assertTrue(lore.contains("Zustand: " + ModuleState.WARTET.color() + "wartet"), lore);
        assertTrue(lore.contains("wartet auf KI"), lore);
        assertTrue(lore.contains("Schalten nur mit baumeister.admin"), lore);
        assertFalse(lore.contains("Klick:"), lore);
        assertTrue(String.join("\n", ModuleMenu.lore(town, true)).contains("Klick: ausschalten"));
    }

    /** onEnable meldet Menü und Dienst an; start() öffnet den Kanal und hört auf Anmeldung und Beitritt. */
    @Test
    void pluginStartWiresChannelMenuAndListeners() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/com/blackn0va/baumeister/BaumeisterPlugin.java"),
                StandardCharsets.UTF_8);
        int enable = plugin.indexOf("public void onEnable()");
        int disable = plugin.indexOf("public void onDisable()");
        String body = plugin.substring(enable, disable);
        assertTrue(body.contains("registerEvents(moduleMenu, this)"), "Menü nicht als Listener angemeldet");
        assertTrue(body.contains("modules = ModuleService.from(this, moduleMenu)"));
        assertTrue(body.contains("modules.start(this)"));
        String service = Files.readString(Path.of("src/main/java/com/blackn0va/baumeister/ModuleService.java"),
                StandardCharsets.UTF_8);
        int start = service.indexOf("void start(Plugin plugin)");
        String startBody = service.substring(start, service.indexOf("void stop()"));
        assertTrue(startBody.contains("registerOutgoingPluginChannel(plugin, CHANNEL)"), startBody);
        assertTrue(startBody.contains("registerEvents(this, plugin)"), startBody);
        assertEquals("baumeister:module", ModuleService.CHANNEL);
        assertEquals("/baumeister/v1/module", ModuleService.PATH);
        assertEquals(URI.create("http://127.0.0.1:18080/baumeister/v1/module"),
                HostReporter.target("", URI.create("http://127.0.0.1:18080/v1/chat/completions"))
                        .resolve(ModuleService.PATH));
    }
}
