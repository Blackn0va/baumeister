package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.ChatColor;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Presence: Tab ein/aus, neue Spieler, UUID-Kollision, Rückfall, Chatformat, Kommen/Gehen-Schalter. */
class PresenceTest {

    /** Spieler, der mitschreibt, was er im Chat liest. */
    static final class FakePlayer {
        final UUID id;
        final String name;
        final List<String> heard = new CopyOnWriteArrayList<>();
        boolean online = true;
        final Player proxy;

        FakePlayer(String name, UUID id) {
            this.name = name;
            this.id = id;
            proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getUniqueId" -> this.id;
                        case "getName" -> this.name;
                        case "isOnline" -> online;
                        case "sendMessage" -> {
                            if (a[0] instanceof String s) {
                                heard.add(s);
                            }
                            yield null;
                        }
                        case "equals" -> p == a[0];
                        case "hashCode" -> this.id.hashCode();
                        case "toString" -> this.name;
                        default -> none(m.getReturnType());
                    });
        }
    }

    /** Schreibt jedes Paket mit: „show Probant Lotte=uuid“ bzw. „hide Probant uuid“. */
    static final class FakeTab implements Tab {
        final List<String> sent = new ArrayList<>();
        boolean on = true;
        /** Dieser Spieler wirft beim Senden (Verbindung geht gerade zu). */
        UUID broken;

        @Override
        public boolean active() {
            return on;
        }

        @Override
        public void show(Player to, List<TabEntry> entries) {
            if (to.getUniqueId().equals(broken)) {
                throw new IllegalStateException("Kanal zu");
            }
            StringBuilder b = new StringBuilder("show " + to.getName());
            entries.forEach(e -> b.append(' ').append(e.name()).append('=').append(e.id()));
            sent.add(b.toString());
        }

        @Override
        public void hide(Player to, List<UUID> ids) {
            StringBuilder b = new StringBuilder("hide " + to.getName());
            ids.forEach(i -> b.append(' ').append(i));
            sent.add(b.toString());
        }

        long count(String prefix) {
            return sent.stream().filter(s -> s.startsWith(prefix)).count();
        }
    }

    static final class FakeServer implements Presence.Server {
        final List<Player> players = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public Collection<? extends Player> players() {
            return players;
        }

        @Override
        public void later(Runnable r, long ticks) {
            later.add(r);
            delays.add(ticks);
        }

        void runLater() {
            List<Runnable> now = new ArrayList<>(later);
            later.clear();
            now.forEach(Runnable::run);
        }
    }

    private final FakeServer server = new FakeServer();
    private final FakeTab tab = new FakeTab();
    private final FakePlayer anna = new FakePlayer("Anna", UUID.randomUUID());
    private final FakePlayer bert = new FakePlayer("Bert", UUID.randomUUID());

    private static final Mannequin BARE = (Mannequin) Proxy.newProxyInstance(Mannequin.class.getClassLoader(),
            new Class<?>[] {Mannequin.class}, (p, m, a) -> none(m.getReturnType()));

    private Presence presence() {
        server.players.add(anna.proxy);
        server.players.add(bert.proxy);
        return new Presence(server, tab);
    }

    static Object none(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class || t == long.class) {
            return t == int.class ? 0 : 0L;
        }
        if (t == double.class || t == float.class) {
            return t == double.class ? 0.0 : 0f;
        }
        return null;
    }

    /** Erscheint ein Bewohner, steht er bei JEDEM Spieler in der Liste; geht er, wird er bei jedem entfernt. */
    @Test
    void residentIsListedForEveryPlayerAndRemovedWhenGone() {
        Presence p = presence();
        p.appeared("Lotte", "Bäckerin", 0xF2E8D5, BARE);
        assertEquals(1, p.entries().size());
        TabEntry e = p.entries().getFirst();
        assertEquals("Lotte", e.name());
        assertEquals(2, e.id().version());
        assertEquals(ResidentIdentity.latency("Lotte"), e.latency());
        assertEquals(List.of("show Anna Lotte=" + e.id(), "show Bert Lotte=" + e.id()), tab.sent);
        tab.sent.clear();
        p.left("Lotte");
        assertTrue(p.entries().isEmpty());
        assertEquals(List.of("hide Anna " + e.id(), "hide Bert " + e.id()), tab.sent);
    }

    /** Wer später kommt, bekommt alle vorhandenen Bewohner – nach JOIN_DELAY_TICKS, nur wenn er noch da ist. */
    @Test
    void joiningPlayerGetsExistingResidents() {
        Presence p = presence();
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        p.appeared("Emil", "Schmied", 0, BARE);
        tab.sent.clear();
        FakePlayer cara = new FakePlayer("Cara", UUID.randomUUID());
        server.players.add(cara.proxy);
        p.joined(cara.proxy);
        assertTrue(tab.sent.isEmpty(), "erst nach der Verzögerung");
        assertEquals(List.of(Presence.JOIN_DELAY_TICKS), server.delays);
        server.runLater();
        assertEquals(1, tab.sent.size());
        assertTrue(tab.sent.getFirst().startsWith("show Cara Lotte="), tab.sent.toString());
        assertTrue(tab.sent.getFirst().contains(" Emil="), tab.sent.toString());
        FakePlayer gone = new FakePlayer("Dora", UUID.randomUUID());
        p.joined(gone.proxy);
        gone.online = false;
        tab.sent.clear();
        server.runLater();
        assertTrue(tab.sent.isEmpty(), "schon wieder weg: nichts schicken");
    }

    /** Plugin aus: alle Einträge bei allen Spielern weg, danach leer. */
    @Test
    void shutdownRemovesAllEntries() {
        Presence p = presence();
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        p.appeared("Emil", "Schmied", 0, BARE);
        tab.sent.clear();
        p.shutdown();
        assertEquals(2, tab.count("hide "));
        assertTrue(tab.sent.getFirst().split(" ").length == 4, tab.sent.toString());
        assertTrue(p.entries().isEmpty());
    }

    /** UUID-Kollision: Kein Bewohner bekommt die UUID eines online Spielers. */
    @Test
    void residentNeverGetsTheUuidOfAnOnlinePlayer() {
        UUID lotte = ResidentIdentity.profileId("Lotte", u -> false).orElseThrow();
        FakePlayer twin = new FakePlayer("Zwilling", lotte);
        server.players.add(twin.proxy);
        Presence p = new Presence(server, tab);
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        assertNotEquals(lotte, p.entries().getFirst().id());
    }

    /** Kommt ein Spieler mit der UUID eines Bewohners, bekommt der Bewohner bei den anderen eine neue. */
    @Test
    void joiningPlayerWithResidentUuidMovesTheResident() {
        Presence p = presence();
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        UUID old = p.entries().getFirst().id();
        tab.sent.clear();
        FakePlayer twin = new FakePlayer("Zwilling", old);
        server.players.add(twin.proxy);
        p.joined(twin.proxy);
        UUID fresh = p.entries().getFirst().id();
        assertNotEquals(old, fresh);
        assertEquals(2, fresh.version());
        assertTrue(tab.sent.contains("hide Anna " + old), tab.sent.toString());
        assertTrue(tab.sent.contains("show Anna Lotte=" + fresh), tab.sent.toString());
        assertFalse(tab.sent.contains("hide Zwilling " + old), "dem Spieler selbst nicht seinen eigenen Eintrag nehmen");
        server.runLater();
        assertTrue(tab.sent.getLast().startsWith("show Zwilling Lotte=" + fresh), tab.sent.toString());
    }

    /** Rückfall ohne PacketEvents: keine Einträge, kein Paket, keine Ausnahme – die Zeilen kommen trotzdem. */
    @Test
    void fallbackWithoutTabSendsNothing() {
        server.players.add(anna.proxy);
        Presence p = new Presence(server, Tab.NONE);
        assertFalse(p.tabActive());
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        p.joined(anna.proxy);
        server.runLater();
        p.left("Lotte");
        p.shutdown();
        assertTrue(p.entries().isEmpty());
        assertTrue(server.later.isEmpty());
        assertEquals(2, anna.heard.size(), anna.heard.toString());
    }

    /** Wirft PacketEvents bei einem Spieler, bekommt der andere den Eintrag trotzdem, und nichts fliegt bis TownLife. */
    @Test
    void brokenConnectionDoesNotStopOthers() {
        Presence p = presence();
        tab.broken = anna.id;
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        assertEquals(1, tab.count("show Bert Lotte="));
        assertEquals(0, tab.count("show Anna"));
    }

    /** Chat wie ein Spieler: „<Lotte> Text“, ohne Farbe und ohne Beruf. */
    @Test
    void chatLineIsPlayerFormat() {
        assertEquals("<Lotte> Frische Brötchen!", Presence.chatLine("Lotte", "Frische Brötchen!"));
    }

    /** Kommen/Gehen: gelbe Zeile wie bei Spielern, abschaltbar über stadtleben.kommen-gehen-melden. */
    @Test
    void joinAndQuitLinesAreYellowAndSwitchable() {
        Presence p = presence();
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        p.left("Lotte");
        assertEquals(List.of(ChatColor.YELLOW + "Lotte hat das Spiel betreten",
                ChatColor.YELLOW + "Lotte hat das Spiel verlassen"), anna.heard);
        assertEquals(anna.heard, bert.heard);
        anna.heard.clear();
        p.configure(new PresenceSettings(false));
        p.appeared("Lotte", "Bäckerin", 0, BARE);
        p.left("Lotte");
        assertTrue(anna.heard.isEmpty(), anna.heard.toString());
        assertEquals(2, tab.count("show Anna"), "Tab bleibt, nur die Zeilen sind aus");
    }
}
