package com.blackn0va.baumeister.town.memory;

import com.blackn0va.baumeister.town.DayPlan;
import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownLines;
import com.blackn0va.baumeister.town.TownTalk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Alles, was das Reden der Bewohner klüger macht (Vertrag v013, AK-41, T-034), an einer Stelle: Gedächtnis
 * (TownMemory, Platte), laufendes Gespräch (Conversation, nur Speicher), Pseudonyme (Pseudonyms) und feste Sätze mit
 * Wiederholsperre (TownLines).
 *
 * Warum eine eigene Klasse statt Methoden in TownLife: TownLife baut T-035 parallel um (Laufen, Ziele); dort stehen
 * nur Aufrufe, hier die Logik. Wer sie zurück nach TownLife zieht, erzeugt Zusammenführungskonflikte in jeder
 * Bewegungsänderung. Bukkit-Typen (Location, Player) nur als Eingabe – Lage und Prompt baut TownTalk ohne Bukkit.
 *
 * Spieler kommen hier nur als Pseudonym heraus: situation() und alias() geben nie getName() weiter; scrub() ersetzt
 * Namen in Chatzeilen, bevor sie ins Gespräch oder in einen Fakt gehen.
 *
 * Threads: nur Hauptthread. Fehler: open() fällt bei kaputtem Datenordner auf Speicher zurück (geloggt, fail-open mit
 * Absicht – siehe TownMemory); save() loggt statt zu werfen.
 */
public final class TownVoice {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Bis hierhin gilt ein laufender Bau als „in der Nähe“ (Blöcke). */
    public static final double BUILD_NEAR = 48.0;

    private final TownMemory memory;
    private final Pseudonyms aliases;
    private final Conversation talk = new Conversation();
    private final TownLines lines = new TownLines();

    TownVoice(TownMemory memory, Pseudonyms aliases) {
        this.memory = memory;
        this.aliases = aliases;
    }

    /** Nur im Speicher (Tests, Plugin ohne Datenordner). */
    public static TownVoice inMemory() {
        return new TownVoice(TownMemory.inMemory(), Pseudonyms.ephemeral());
    }

    /** Aus dir (plugins/Baumeister/stadtleben): Schlüssel und Gedächtnis; was nicht geht, läuft im Speicher. */
    public static TownVoice open(Path dir) {
        Pseudonyms a;
        try {
            a = Pseudonyms.open(dir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadtleben | pseudonym_schlüssel_fehlt | grund=" + e, e);
            a = Pseudonyms.ephemeral();
        }
        TownMemory m = new TownMemory(dir.resolve(TownMemory.FILE));
        try {
            m.load(System.currentTimeMillis());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadtleben | gedächtnis_unlesbar | grund=" + e, e);
        }
        return new TownVoice(m, a);
    }

    public TownMemory memory() {
        return memory;
    }

    public String alias(Player p) {
        return aliases.of(p.getUniqueId());
    }

    /** Ein fester Satz für Anlass, Person und die Lage in w (Tageszeit, Wetter), nie zweimal kurz hintereinander. */
    public String fixed(TownLines.Occasion o, Roster.Person p, World w, int roll) {
        return lines.pick(o, p, DayPlan.phase(w.getTime()), TownTalk.Weather.of(w.hasStorm(), w.isThundering()),
                roll);
    }

    /** Chatzeile ohne Spielernamen: jeder Name eines Spielers online wird zu seinem Pseudonym. */
    public String scrub(String heard, Collection<? extends Player> online) {
        Map<String, String> byName = new HashMap<>();
        for (Player p : online) {
            if (p.getName() != null) {
                byName.put(p.getName(), alias(p));
            }
        }
        return TownTalk.scrub(heard, byName);
    }

    /** Ein Spieler sprach den Bewohner an (heard schon ohne Namen): Fakt merken und ins Gespräch der Zuhörer. */
    public void heard(Roster.Person listener, Player from, String heard, Location at, Map<Roster.Person, Location> town,
            long now) {
        String alias = alias(from);
        memory.remember(listener.name(), Fact.heardFrom(alias, heard), now);
        talk.add(listeners(listener, at, town), alias, heard, now);
    }

    /** Ein Bewohner hat etwas gesagt: Sperre für feste Sätze und Gespräch aller Bewohner in Hörweite. */
    public void said(Roster.Person speaker, String text, Location at, Map<Roster.Person, Location> town, long now) {
        lines.said(text);
        talk.add(listeners(speaker, at, town), speaker.name(), text, now);
    }

    /** Bewohner hat einen Spieler im Vorbeigehen gegrüßt. */
    public void passedBy(Roster.Person p, Player player, long now) {
        memory.remember(p.name(), Fact.passedBy(alias(player)), now);
    }

    /** Zwei Bewohner haben sich getroffen. */
    public void met(Roster.Person a, Roster.Person b, long now) {
        memory.remember(a.name(), Fact.met(b.name()), now);
        memory.remember(b.name(), Fact.met(a.name()), now);
    }

    /**
     * Die Lage für den Prompt: Uhrzeit, Wetter, Tätigkeit, laufender Bau, wer dabei ist (Spieler nur als Pseudonym),
     * das laufende Gespräch und das Gedächtnis. Schlechtes Wetter und ein Bau in der Nähe werden zum Erlebnis.
     * working = er steht an seinem Arbeitsort; town = alle Bewohner mit Ort (er selbst darf dabei sein).
     */
    public TownTalk.Situation situation(Roster.Person p, Location at, boolean working,
            Collection<? extends Player> online, Map<Roster.Person, Location> town, Location buildSite, long now) {
        World w = at.getWorld();
        long time = w.getTime();
        DayPlan.Phase phase = DayPlan.phase(time);
        TownTalk.Weather weather = TownTalk.Weather.of(w.hasStorm(), w.isThundering());
        if (weather != TownTalk.Weather.CLEAR) {
            memory.remember(p.name(), Fact.weather(weather == TownTalk.Weather.THUNDER), now);
        }
        boolean building = buildSite != null && w.equals(buildSite.getWorld())
                && buildSite.distanceSquared(at) <= BUILD_NEAR * BUILD_NEAR;
        if (building) {
            memory.remember(p.name(), Fact.sawBuilding(), now);
        }
        List<String> players = new ArrayList<>();
        for (Player pl : online) {
            if (pl.getGameMode() != GameMode.SPECTATOR && earshot(at, pl.getLocation())) {
                players.add(alias(pl));
            }
        }
        List<String> others = new ArrayList<>();
        for (Map.Entry<Roster.Person, Location> o : town.entrySet()) {
            if (!o.getKey().equals(p) && earshot(at, o.getValue())) {
                others.add(o.getKey().name() + " (" + o.getKey().job() + ")");
            }
        }
        return new TownTalk.Situation(phase, DayPlan.hour(time), weather, working ? "arbeitet gerade"
                : phase.label(), building, players, others, talk.recent(p.name(), now), memory.facts(p.name(), now));
    }

    /**
     * /bm stadtleben vergessen [bewohner]: resident = null löscht alle. Gedächtnis und Gespräch weg, sofort gespeichert.
     * Zahl der gelöschten Fakten; -1 = kein Bewohner dieses Namens im Roster.
     */
    public int forget(String resident) {
        int n;
        if (resident == null) {
            talk.clear();
            n = memory.forgetAll();
        } else {
            Roster.Person who = null;
            for (Roster.Person p : Roster.all()) {
                if (p.name().equalsIgnoreCase(resident.trim())) {
                    who = p;
                }
            }
            if (who == null) {
                return -1;
            }
            talk.drop(who.name());
            n = memory.forget(who.name());
        }
        save();
        return n;
    }

    /** Bewohner ist gegangen: sein laufendes Gespräch endet (das Gedächtnis bleibt). */
    public void left(Roster.Person p) {
        talk.drop(p.name());
    }

    /** Speichern, wenn sich etwas geändert hat. */
    public void save() {
        try {
            memory.save(System.currentTimeMillis());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadtleben | gedächtnis_nicht_gespeichert | grund=" + e, e);
        }
    }

    private static List<String> listeners(Roster.Person speaker, Location at, Map<Roster.Person, Location> town) {
        List<String> out = new ArrayList<>();
        out.add(speaker.name());
        for (Map.Entry<Roster.Person, Location> o : town.entrySet()) {
            if (!o.getKey().equals(speaker) && earshot(at, o.getValue())) {
                out.add(o.getKey().name());
            }
        }
        return out;
    }

    private static boolean earshot(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) <= TownTalk.HEAR * TownTalk.HEAR;
    }
}
