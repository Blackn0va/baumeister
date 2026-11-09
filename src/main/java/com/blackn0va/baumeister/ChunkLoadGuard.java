package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * Schutz vor der PlotSquared-Rekursion im ChunkLoadEvent auf Spigot.
 *
 * PlotSquared ruft in jedem ChunkLoadEvent – in JEDER Welt, auch der Stadtwelt, die keine Plot-Welt ist –
 * chunk.getEntities() auf (EntitySpawnListener.onChunkLoad, Bytecode in 7.5.12 und 7.6.0 gleich, auf GitHub main
 * unverändert; kein Schalter in settings.yml). Gilt der Chunk in diesem Moment noch nicht als geladen, lädt Spigots
 * CraftChunk.getEntities ihn synchron nach (CraftChunk.java:123–124 → CraftWorld.getChunkAt). Dieser Ladevorgang
 * arbeitet die Ticketverwaltung samt weiterer wartender ChunkLoadEvents ab – die wieder bei PlotSquared landen, eine
 * Stackebene tiefer. dev01 2026-10-30 16:52–16:59: zuerst NPEs aus DistanceManager (Wiedereintritt), dann
 * StackOverflowError, 7 670 Fehlerspuren, 895 MB Log, Watchdog nach 60 s, Server aus. Paper hat das serverseitig
 * entschärft (getEntities lädt dort nicht nach), Spigot 4598 nicht; PlotSquared-Issue #2413 (2019) blieb ohne Änderung.
 *
 * Jetzt hängt sich der Baumeister vor genau diesen einen Handler: Gilt der Chunk als geladen, läuft PlotSquared sofort
 * wie vorher. Sonst einen Tick später und nur, wenn der Chunk dann geladen ist – PlotSquared prüft dort ohnehin nur
 * die Wesen des Chunks gegen die Plot-Grenzen (testCreate), ein entladener Chunk hat keine. Der verschachtelte
 * Ladeweg entsteht so nie, gleich wer den Chunk lädt (Bau, Rückgängig, ein schnell fliegender Spieler).
 *
 * Bewusst fail-open: Fehlt der Handler (kein PlotSquared, andere Fassung mit anderem Klassennamen), bleibt alles, wie es
 * ist, und das Log sagt es. Wer den Schutz entfernt, macht jeden großen Bau auf Spigot mit PlotSquared wieder
 * absturzgefährdet; wer die Bedingung „geladen“ weglässt, ruft PlotSquared wieder mitten im Laden auf.
 *
 * Threads: Hauptthread (Bukkit-Ereignisse und Scheduler). Fehler: Eine Ausnahme aus dem verschobenen Aufruf wird
 * protokolliert wie Bukkit es für Ereignisse tut („Could not pass event“), nie geschluckt.
 */
final class ChunkLoadGuard implements Listener {

    static final String PLUGIN = "PlotSquared";
    static final String LISTENER = "com.plotsquared.bukkit.listener.EntitySpawnListener";

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Gilt der Chunk als geladen? Im Betrieb World.isChunkLoaded – dieselbe Prüfung wie CraftChunk.isLoaded. */
    interface Loaded {
        boolean test(World world, int cx, int cz);
    }

    /** Erkennbar umgehängter Handler: install() hängt ihn nie ein zweites Mal um, uninstall() findet ihn wieder. */
    static final class Guarded extends RegisteredListener {
        private final RegisteredListener original;

        Guarded(RegisteredListener original, EventExecutor executor) {
            super(original.getListener(), executor, original.getPriority(), original.getPlugin(),
                    original.isIgnoringCancelled());
            this.original = original;
        }

        RegisteredListener original() {
            return original;
        }
    }

    private final Consumer<Runnable> nextTick;

    ChunkLoadGuard(Consumer<Runnable> nextTick) {
        this.nextTick = nextTick;
    }

    static boolean matches(String pluginName, String listenerClass) {
        return PLUGIN.equals(pluginName) && LISTENER.equals(listenerClass);
    }

    /**
     * Der Ersatz-Ausführer: original sofort, wenn geladen; sonst einen Tick später, wenn dann geladen; sonst gar nicht.
     * Rein und ohne Bukkit-Server testbar (ChunkLoadGuardTest).
     */
    static EventExecutor executor(RegisteredListener original, Loaded loaded, Consumer<Runnable> nextTick) {
        return (listener, event) -> {
            if (!(event instanceof ChunkLoadEvent e)) {
                original.callEvent(event);
                return;
            }
            Chunk c = e.getChunk();
            World w = e.getWorld();
            if (loaded.test(w, c.getX(), c.getZ())) {
                original.callEvent(event);
                return;
            }
            nextTick.accept(() -> {
                if (!loaded.test(w, c.getX(), c.getZ())) {
                    return;  // wieder entladen: keine Wesen, nichts zu prüfen
                }
                try {
                    original.callEvent(event);
                } catch (EventException ex) {
                    LOG.log(Level.SEVERE, "Could not pass event ChunkLoadEvent to " + original.getPlugin().getName()
                            + " (verschoben vom Baumeister-ChunkLoadGuard)", ex.getCause() != null ? ex.getCause() : ex);
                }
            });
        };
    }

    /** Umhängen; Anzahl der neu geschützten Handler (0, wenn schon geschützt oder PlotSquared fehlt). */
    int install() {
        HandlerList handlers = ChunkLoadEvent.getHandlerList();
        List<RegisteredListener> found = new ArrayList<>();
        for (RegisteredListener rl : handlers.getRegisteredListeners()) {
            if (!(rl instanceof Guarded) && matches(rl.getPlugin().getName(), rl.getListener().getClass().getName())) {
                found.add(rl);
            }
        }
        for (RegisteredListener rl : found) {
            handlers.unregister(rl);
            handlers.register(new Guarded(rl, executor(rl, World::isChunkLoaded, nextTick)));
        }
        return found.size();
    }

    /** Beim Abschalten des Baumeisters: Originale zurück, sonst hinge PlotSquared an Code eines entladenen Plugins. */
    int uninstall() {
        HandlerList handlers = ChunkLoadEvent.getHandlerList();
        int n = 0;
        for (RegisteredListener rl : handlers.getRegisteredListeners()) {
            if (rl instanceof Guarded g) {
                handlers.unregister(g);
                handlers.register(g.original());
                n++;
            }
        }
        return n;
    }

    /** Start des Servers bzw. PlotSquared später (neu) geladen: dann erst gibt es dessen Handler. */
    void installAndLog(String when) {
        int n = install();
        boolean present = org.bukkit.Bukkit.getPluginManager().getPlugin(PLUGIN) != null;
        if (n > 0) {
            LOG.info(() -> "schutz | plotsquared_chunkload | umgehängt=" + n + " anlass=" + when);
        } else if (present && !guarded()) {
            LOG.warning("schutz | plotsquared_chunkload_fehlt | anlass=" + when + " – Handler " + LISTENER
                    + " nicht gefunden (andere PlotSquared-Fassung?); große Bauten sind auf Spigot dann absturzgefährdet");
        }
    }

    private static boolean guarded() {
        for (RegisteredListener rl : ChunkLoadEvent.getHandlerList().getRegisteredListeners()) {
            if (rl instanceof Guarded) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (PLUGIN.equals(e.getPlugin().getName())) {
            // Einen Tick später: PlotSquared trägt seine Handler in onEnable ein, also nach diesem Ereignis.
            nextTick.accept(() -> installAndLog("plotsquared_aktiviert"));
        }
    }
}
