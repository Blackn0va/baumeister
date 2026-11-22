package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.presence.Presence;
import com.blackn0va.baumeister.town.presence.PresenceListener;
import com.blackn0va.baumeister.town.presence.Tab;
import com.blackn0va.baumeister.town.presence.TabEntry;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.junit.jupiter.api.Test;

/**
 * Die Einhängepunkte von T-033 in TownLife und BaumeisterPlugin: Ein Bewohner, der über den echten Takt (step →
 * spawnIfDue → spawn) erscheint, steht in der Tab-Liste; geht er, ist er weg; spricht er, dann im Spielerformat; der
 * Lebenslauf von PacketEvents hängt an onLoad/onEnable/onDisable.
 */
class TownLifePresenceTest {

    /** Welt, deren spawn ein FakeMannequin anlegt und den Konfigurator darauf anwendet (wie Bukkit). */
    private static World spawningWorld(List<Player> players, TownLifeTest.FakeEnv env,
            List<TownLifeTest.FakeMannequin> born) {
        World[] self = new World[1];
        self[0] = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getPlayers" -> new ArrayList<>(players);
                    case "isChunkLoaded" -> true;
                    case "getHighestBlockYAt" -> 63;
                    case "getTime" -> 1000L;
                    case "getName" -> "testwelt";
                    case "spawn" -> {
                        TownLifeTest.FakeMannequin f = new TownLifeTest.FakeMannequin((Location) a[0], false);
                        @SuppressWarnings("unchecked")
                        Consumer<Object> c = (Consumer<Object>) a[2];
                        c.accept(f.proxy);
                        env.entities.put(f.id, f.proxy);
                        born.add(f);
                        yield f.proxy;
                    }
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Welt";
                    default -> TownLifeTest.none(m.getReturnType());
                });
        return self[0];
    }

    static final class RecordingTab implements Tab {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean active() {
            return true;
        }

        @Override
        public void show(Player to, List<TabEntry> entries) {
            entries.forEach(e -> sent.add("show " + e.name()));
        }

        @Override
        public void hide(Player to, List<UUID> ids) {
            ids.forEach(i -> sent.add("hide " + i));
        }
    }

    @Test
    void residentFromTheRealTickIsListedAndRemovedAgain() {
        TownLifeTest.FakeEnv env = new TownLifeTest.FakeEnv();
        List<Player> worldPlayers = new ArrayList<>();
        List<TownLifeTest.FakeMannequin> born = new ArrayList<>();
        World w = spawningWorld(worldPlayers, env, born);
        List<String> heard = new CopyOnWriteArrayList<>();
        Player probant = TownLifeTest.player(new Location(w, 0.5, 64, 0.5), heard);
        worldPlayers.add(probant);
        env.players.add(probant);
        RecordingTab tab = new RecordingTab();
        Presence presence = new Presence(new Presence.Server() {
            @Override
            public Collection<? extends Player> players() {
                return env.players;
            }

            @Override
            public void later(Runnable r, long ticks) {
                r.run();
            }
        }, tab);
        TownLife life = new TownLife(null, env, TownLifeTest.MARKER);
        life.presence(presence);
        life.configure(new TownSettings(true, 1, 45_000, true), null);
        for (int i = 0; i < 5 && born.isEmpty(); i++) {
            life.step();
        }
        assertEquals(1, born.size(), "der Takt hat keinen Bewohner erscheinen lassen");
        assertEquals(1, presence.entries().size());
        String name = presence.entries().getFirst().name();
        assertEquals(List.of("show " + name), tab.sent);
        assertTrue(heard.contains(org.bukkit.ChatColor.YELLOW + name + " hat das Spiel betreten"), heard.toString());
        UUID id = presence.entries().getFirst().id();
        life.reload(new TownSettings(false, 1, 45_000, true));
        assertTrue(presence.entries().isEmpty(), "aus → Eintrag weg");
        assertEquals("hide " + id, tab.sent.getLast());
        assertTrue(heard.getLast().endsWith(name + " hat das Spiel verlassen"), heard.toString());
    }

    /** Bewohner sprechen wie Spieler (TownLife.speak über Presence.chatLine), auch ohne Presence. */
    @Test
    void residentsSpeakInPlayerFormat() {
        TownLifeTest.Town t = new TownLifeTest.Town(3);
        t.life.configure(TownSettings.defaults().withEnabled(true), null);
        assertTrue(t.life.onChat(t.probant, "Lotte"));
        assertTrue(t.heard.getFirst().startsWith("<Lotte> "), t.heard.toString());
    }

    /**
     * BaumeisterPlugin: PacketBoot.load in onLoad (vor onEnable, Anleitung PacketEvents), PresenceListener in onEnable
     * angemeldet, beim Abschalten erst presence.shutdown, dann packets.disable. Ein JavaPlugin lässt sich ohne Server
     * nicht starten, also prüft der Test den Quelltext ohne Kommentare (wie TownLifeWiringTest).
     */
    @Test
    void pluginLifecycleWiresPacketEventsAndPresence() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/blackn0va/baumeister/BaumeisterPlugin.java"),
                StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertTrue(body(src, "public void onLoad()").contains("PacketBoot.load(this)"), "onLoad lädt PacketEvents nicht");
        String enable = body(src, "public void onEnable()");
        assertTrue(enable.contains("PresenceListener(presence)"), "PresenceListener fehlt in onEnable");
        String load = body(src, "private void load()");
        assertTrue(load.contains("packets.enable()") && load.contains("town.presence(presence)"), load);
        String disable = body(src, "public void onDisable()");
        int town = disable.indexOf("town.shutdown()");
        int pres = disable.indexOf("presence.shutdown()");
        int pack = disable.indexOf("packets.disable()");
        assertTrue(town >= 0 && pres > town && pack > pres, "Reihenfolge town → presence → packets");
        Method onJoin = PresenceListener.class.getMethod("onJoin", org.bukkit.event.player.PlayerJoinEvent.class);
        assertNotNull(onJoin.getAnnotation(EventHandler.class));
        String life = Files.readString(Path.of("src/main/java/com/blackn0va/baumeister/TownLife.java"),
                StandardCharsets.UTF_8);
        assertTrue(body(life, "private void forget(").contains("presence.left("), "forget meldet das Gehen nicht");
    }

    private static String body(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at >= 0, signature + " nicht gefunden");
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("Rumpf von " + signature + " endet nicht");
    }

    /** Zur Sicherheit: Der Fake-Mannequin-Typ reicht für spawn (Mannequin-Schnittstelle). */
    @Test
    void fakeMannequinIsAMannequin() {
        assertTrue(Mannequin.class.isInstance(new TownLifeTest.FakeMannequin(new Location(null, 0, 0, 0), false).proxy));
    }
}
