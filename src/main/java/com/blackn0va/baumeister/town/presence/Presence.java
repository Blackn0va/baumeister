package com.blackn0va.baumeister.town.presence;

import com.blackn0va.baumeister.MannequinCompat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/**
 * Spieler-Auftritt der Stadtleben-Bewohner (Vertrag v013, AK-40; Nutzer 2026-11-22: Bewohner „eher dumm“, sollen wie
 * echte Spieler auftauchen, auch im Tab): Tab-Eintrag mit Name, Mannequin-Skin, Ping und Spielmodus bei jedem Spieler,
 * Chat im Spielerformat „<Lotte> …“, gelbe Beitritts- und Verlassenszeile, Ausrüstung zum Beruf, Arm schwingen bei der
 * Arbeit, ab und zu schleichen.
 *
 * TownLife meldet nur drei Dinge (appeared, left, gesture) und fragt chatLine – damit die Einhängepunkte in TownLife
 * klein bleiben (T-035 baut dort parallel die Bewegung um). Die Bewohner bleiben Mannequins für Wegsuche und Physik;
 * hier entstehen nur Pakete an Clients und Chatzeilen. Auf dem Server gibt es keinen zusätzlichen Player: Server-Ping,
 * Schlaf, Rechte und Vera sehen die Bewohner nicht (Nicht-Ziel des Vertrags).
 *
 * Bewusst fail-open: Alles hier ist Anzeige. Wirft PacketEvents bei einem Spieler, fehlt nur dort der Eintrag
 * (Warnung einmal, danach fein); wirft das Anziehen, läuft der Bewohner ohne Ausrüstung. Nie bis in den Takt von
 * TownLife – sonst bliebe ein schon eingetragener Bewohner halb erschienen (Gedächtnis „Fallen“: erst eintragen, dann
 * schmücken).
 *
 * Threads: Hauptthread. Fehler: werden geloggt, nie geworfen.
 */
public final class Presence {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /**
     * Neuen Spielern die Bewohner erst nach so vielen Ticks schicken. Beim PlayerJoinEvent schickt der Server selbst
     * noch seine Spielerliste; käme unsere davor, wäre sie trotzdem da – aber PacketEvents kennt die Verbindung erst,
     * wenn der Login durch ist. Zwei Ticks schaden nicht und vermeiden den Wettlauf.
     */
    static final long JOIN_DELAY_TICKS = 2L;

    /** Was Presence vom Server braucht – im Betrieb Bukkit, im Test ein Fake. */
    public interface Server {
        Collection<? extends Player> players();

        void later(Runnable r, long ticks);
    }

    private final Server server;
    private final Tab tab;
    private PresenceSettings settings = PresenceSettings.defaults();
    /** Bewohnername → Tab-Eintrag, in der Reihenfolge des Erscheinens. */
    private final Map<String, TabEntry> entries = new LinkedHashMap<>();
    private final Map<String, Gestures.State> gestures = new HashMap<>();
    private boolean sendWarned;
    private boolean dressWarned;

    public Presence(Server server, Tab tab) {
        this.server = server;
        this.tab = tab;
    }

    public void configure(PresenceSettings s) {
        this.settings = s;
    }

    public PresenceSettings settings() {
        return settings;
    }

    public boolean tabActive() {
        return tab.active();
    }

    /** Für Test und Status: die Einträge, die gerade bei allen Spielern stehen. */
    public List<TabEntry> entries() {
        return List.copyOf(entries.values());
    }

    // ---------------------------------------------------------------- Kommen und Gehen

    /** Ein Bewohner ist erschienen (TownLife.spawn, nach adopt und dress). */
    public void appeared(String name, String job, int color, Mannequin m) {
        try {
            Outfit.dress(m, job, color);
        } catch (RuntimeException e) {
            if (!dressWarned) {
                dressWarned = true;
                LOG.log(Level.WARNING, "stadtleben | ausruestung_fehlgeschlagen | grund=" + e, e);
            }
        }
        if (tab.active() && !entries.containsKey(name)) {
            Optional<UUID> id = ResidentIdentity.profileId(name, taken(name));
            if (id.isPresent()) {
                TabEntry e = new TabEntry(name, id.get(), ResidentIdentity.latency(name));
                entries.put(name, e);
                for (Player p : server.players()) {
                    show(p, List.of(e));
                }
                LOG.fine(() -> "stadtleben | tab_eintrag | bewohner=" + name.toLowerCase(Locale.ROOT) + " uuid="
                        + e.id() + " ping=" + e.latency());
            } else {
                LOG.warning(() -> "stadtleben | tab_keine_uuid | bewohner=" + name.toLowerCase(Locale.ROOT));
            }
        }
        if (settings.announce()) {
            broadcast(joinLine(name));
        }
    }

    /** Ein Bewohner ist weg (TownLife.forget: fern, schlafen, entladen, aus, abschalten …). */
    public void left(String name) {
        gestures.remove(name);
        TabEntry e = entries.remove(name);
        if (e != null) {
            for (Player p : server.players()) {
                hide(p, List.of(e.id()));
            }
        }
        if (settings.announce()) {
            broadcast(quitLine(name));
        }
    }

    /**
     * Ein Spieler kommt dazu: Er bekommt alle vorhandenen Bewohner. Hat er zufällig die UUID eines Bewohners (nur
     * denkbar, wenn ein Proxy oder Plugin eigene UUIDs vergibt – Version 2 kommt bei Mojang und offline-mode nicht vor),
     * bekommt der Bewohner vorher bei allen eine neue: Zwei Einträge mit einer UUID überschrieben sich im Client.
     */
    public void joined(Player p) {
        UUID pid = p.getUniqueId();
        for (TabEntry e : new ArrayList<>(entries.values())) {
            if (e.id().equals(pid)) {
                reassign(e, p);
            }
        }
        if (entries.isEmpty() || !tab.active()) {
            return;
        }
        server.later(() -> {
            if (p.isOnline() && !entries.isEmpty()) {
                show(p, List.copyOf(entries.values()));
            }
        }, JOIN_DELAY_TICKS);
    }

    private void reassign(TabEntry old, Player joining) {
        entries.remove(old.name());
        for (Player p : server.players()) {
            if (!p.getUniqueId().equals(joining.getUniqueId())) {
                hide(p, List.of(old.id()));
            }
        }
        Set<UUID> busy = new HashSet<>();
        busy.add(joining.getUniqueId());
        Optional<UUID> fresh = ResidentIdentity.profileId(old.name(), id -> busy.contains(id) || taken(old.name())
                .test(id));
        LOG.warning(() -> "stadtleben | tab_uuid_kollision | bewohner=" + old.name().toLowerCase(Locale.ROOT)
                + " neu=" + fresh.isPresent());
        fresh.ifPresent(id -> {
            TabEntry e = new TabEntry(old.name(), id, old.latency());
            entries.put(old.name(), e);
            for (Player p : server.players()) {
                if (!p.getUniqueId().equals(joining.getUniqueId())) {
                    show(p, List.of(e));
                }
            }
        });
    }

    /** Plugin aus: alle Einträge bei allen Spielern entfernen (TownLife.shutdown ruft vorher left je Bewohner). */
    public void shutdown() {
        if (entries.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>();
        entries.values().forEach(e -> ids.add(e.id()));
        for (Player p : server.players()) {
            hide(p, ids);
        }
        entries.clear();
        gestures.clear();
    }

    /** Belegte UUIDs: online Spieler und die anderen Bewohner. */
    private java.util.function.Predicate<UUID> taken(String self) {
        Set<UUID> busy = new HashSet<>();
        for (Player p : server.players()) {
            busy.add(p.getUniqueId());
        }
        for (TabEntry e : entries.values()) {
            if (!e.name().equals(self)) {
                busy.add(e.id());
            }
        }
        return busy::contains;
    }

    // ---------------------------------------------------------------- Chat

    /**
     * Was ein Bewohner sagt, im Format einer Spielerzeile (Client-Sprachdatei chat.type.text = „<%s> %s“, de_de und
     * en_us gleich, gemessen 2026-11-22 im Asset-Index 26.2). Vorher grün „[Lotte, Bäckerin] …“ – wie ein Plugin, nicht
     * wie ein Mitspieler. Der Beruf steht weiter unter dem Namen am Mannequin.
     */
    public static String chatLine(String name, String text) {
        return "<" + name + "> " + text;
    }

    /** Wie die Zeile eines echten Spielers in einem deutschen Client (multiplayer.player.joined, de_de, gemessen). */
    static String joinLine(String name) {
        return ChatColor.YELLOW + name + " hat das Spiel betreten";
    }

    static String quitLine(String name) {
        return ChatColor.YELLOW + name + " hat das Spiel verlassen";
    }

    private void broadcast(String line) {
        for (Player p : server.players()) {
            p.sendMessage(line);
        }
    }

    // ---------------------------------------------------------------- Auftritt

    /** Ein Takt des Bewohners (TownLife.stepResident): Arm schwingen bei der Arbeit, ab und zu schleichen. */
    public void gesture(String name, Mannequin m, Gestures.Activity a, long now) {
        Gestures.State s = gestures.computeIfAbsent(name, k -> new Gestures.State());
        Gestures.Move mv = Gestures.next(s, a, now, n -> ThreadLocalRandom.current().nextLong(Math.max(1, n)));
        if (mv.swing()) {
            m.swingMainHand();
        }
        if (mv.sneak() != null) {
            MannequinCompat.pose(m, mv.sneak() ? Pose.SNEAKING : Pose.STANDING);
        }
    }

    // ---------------------------------------------------------------- Senden

    private void show(Player p, List<TabEntry> list) {
        try {
            tab.show(p, list);
        } catch (RuntimeException | LinkageError e) {
            sendFailed("zeigen", p, e);
        }
    }

    private void hide(Player p, List<UUID> ids) {
        if (!tab.active()) {
            return;
        }
        try {
            tab.hide(p, ids);
        } catch (RuntimeException | LinkageError e) {
            sendFailed("entfernen", p, e);
        }
    }

    private void sendFailed(String what, Player p, Throwable e) {
        // Je Spieler gefangen: Eine Verbindung, die gerade zugeht, darf den übrigen Spielern den Eintrag nicht nehmen.
        if (!sendWarned) {
            sendWarned = true;
            LOG.log(Level.WARNING, "stadtleben | tab_senden_fehlgeschlagen | was=" + what + " grund=" + e, e);
        } else {
            LOG.fine(() -> "stadtleben | tab_senden_fehlgeschlagen | was=" + what + " spieler=" + p.getUniqueId()
                    + " grund=" + e);
        }
    }
}
