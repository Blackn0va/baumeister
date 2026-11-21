package com.blackn0va.baumeister;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Chunks einer Baustelle laden, BEVOR der Server-Thread sie liest – ohne ihn dabei anzuhalten.
 *
 * Gemessen 2026-11-14 (Paper-26.2-Kopie von dev01, echtes Gelände, schneller PC, „bauenbei … 8 mal 5 Stockwerke“ an
 * einer nie erzeugten Stelle): Der Befehl hielt den Server-Thread 4,4 s bzw. 2,5 s an (Bauplatz-Scan, SiteFrame), die
 * Prüfphase danach brauchte 1,2–2,0 s in 1–2 Ticks (BuildJob liest jede Zelle) – beides synchrones Erzeugen von
 * Chunks. Auf dem Celeron ein Mehrfaches, der Watchdog schlägt bei 60 s zu. Betroffen sind Aufträge ohne Spieler
 * daneben: /bm bauenbei, die Warteschlange, Vera.
 *
 * Jetzt: Fehlt ein Chunk, holt Paper ihn asynchron (World#getChunkAtAsync, per Reflexion – das Plugin baut gegen die
 * Spigot-API); auf Spigot werden höchstens perTick Chunks je Tick synchron geladen. Jeder geladene Chunk bekommt ein
 * Plugin-Ticket, bis release() läuft, damit er bis zum Lesen nicht wieder entladen wird. Sind schon alle geladen
 * (der Normalfall mit einem Spieler daneben), läuft then sofort, im selben Tick – wie vorher.
 *
 * Threads: ensure() und then auf dem Server-Thread; Paper vervollständigt im eigenen Thread, der Rückruf springt über
 * mainThread zurück. Fehler: Ein Chunk, der sich nicht laden lässt, wird geloggt und übersprungen. Für BuildJob und
 * SiteFrame ist das fail-open (das Lesen lädt ihn synchron wie früher – langsamer, aber nicht falsch); Prüfsumme
 * (SiteFingerprint) und /bm ort (PlaceRegistry.arrival) fragen danach host.loaded() und sind fail-closed (2026-11-21).
 */
final class ChunkPrefetch {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Was ChunkPrefetch vom Server braucht – im Test ein Zähler. */
    interface Host {
        boolean loaded(int cx, int cz);

        /** Paper: Chunk im Hintergrund laden bzw. erzeugen; null, wenn es das nicht gibt (Spigot). */
        CompletableFuture<?> loadAsync(int cx, int cz);

        /** Synchron laden (Spigot), samt Erzeugen. */
        void loadNow(int cx, int cz);

        void hold(int cx, int cz);

        void release(int cx, int cz);

        /** Aufgabe auf den Server-Thread (aus einem fremden Thread). */
        void mainThread(Runnable r);

        /** Läuft der Aufrufer gerade auf dem Server-Thread? */
        boolean onMainThread();

        /** Aufgabe jeden Tick, bis sie false liefert. */
        void everyTick(java.util.function.BooleanSupplier task);
    }

    private final Host host;
    private final long[] keys;
    private final int perTick;
    private final java.util.Set<Long> heldKeys = new java.util.LinkedHashSet<>();
    private boolean released;

    private ChunkPrefetch(Host host, long[] keys, int perTick) {
        this.host = host;
        this.keys = keys;
        this.perTick = Math.max(1, perTick);
    }

    /**
     * Fehlende der Chunks keys (cx &lt;&lt; 32 | cz) laden und danach then aufrufen; Rückgabe: der Vorgang, dessen
     * release() die Tickets wieder freigibt (spätestens, wenn then gelesen hat). null, wenn nichts zu laden war –
     * then lief dann schon.
     */
    static ChunkPrefetch ensure(Host host, long[] keys, int perTick, Consumer<ChunkPrefetch> then) {
        List<Long> missing = new ArrayList<>();
        for (long k : keys) {
            if (!host.loaded(cx(k), cz(k))) {
                missing.add(k);
            }
        }
        if (missing.isEmpty()) {
            then.accept(null);
            return null;
        }
        long[] todo = missing.stream().mapToLong(Long::longValue).toArray();
        ChunkPrefetch p = new ChunkPrefetch(host, todo, perTick);
        p.start(then);
        return p;
    }

    /** Wie viele Chunks gerade gehalten werden (für Log und Test). */
    int held() {
        return heldKeys.size();
    }

    /** Tickets freigeben; mehrfach aufrufbar. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        for (long k : heldKeys) {
            host.release(cx(k), cz(k));
        }
        heldKeys.clear();
    }

    private void start(Consumer<ChunkPrefetch> then) {
        long t0 = System.nanoTime();
        Consumer<ChunkPrefetch> logged = p -> {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "chunks | vorgeladen | anzahl=" + keys.length + " gehalten=" + heldKeys.size()
                    + " dauer_ms=" + ms);
            then.accept(p);
        };
        CompletableFuture<?>[] futures = new CompletableFuture<?>[keys.length];
        boolean async = true;
        for (int i = 0; i < keys.length && async; i++) {
            long k = keys[i];
            CompletableFuture<?> f = host.loadAsync(cx(k), cz(k));
            async = f != null;
            // Sofort halten, wenn der Chunk fertig ist: Gemessen 2026-11-14 (Paper 26.2) waren von 16 asynchron
            // geladenen Chunks im nächsten Tick nur noch 1 bzw. 6 geladen – ohne Ticket entlädt Paper sie gleich
            // wieder. Paper vervollständigt auf dem Server-Thread; sonst holt holdLoaded sie am Ende.
            futures[i] = f == null ? null : f.whenComplete((c, e) -> {
                if (e == null && host.onMainThread()) {
                    holdOne(k);
                }
            });
        }
        if (async) {
            LOG.fine(() -> "chunks | vorladen | anzahl=" + keys.length + " weg=paper");
            CompletableFuture.allOf(futures).whenComplete((v, err) -> host.mainThread(() -> {
                if (err != null) {
                    LOG.log(Level.WARNING, "chunks | vorladen_fehlgeschlagen | anzahl=" + keys.length, err);
                }
                holdLoaded();
                logged.accept(this);
            }));
            return;
        }
        int[] next = {0};
        host.everyTick(() -> {
            for (int n = 0; n < perTick && next[0] < keys.length; n++, next[0]++) {
                long k = keys[next[0]];
                try {
                    host.loadNow(cx(k), cz(k));
                    holdOne(k);  // sonst darf der Server ihn bis zum Lesen wieder entladen
                } catch (RuntimeException e) {
                    // siehe Kopf: fail-open für BuildJob/SiteFrame, Prüfsumme und /bm ort fragen loaded() (fail-closed).
                    LOG.log(Level.WARNING, "chunks | laden_fehlgeschlagen | cx=" + cx(k) + " cz=" + cz(k), e);
                }
            }
            if (next[0] < keys.length) {
                return true;
            }
            holdLoaded();
            logged.accept(this);
            return false;
        });
    }

    private void holdLoaded() {
        for (long k : keys) {
            holdOne(k);
        }
    }

    private void holdOne(long k) {
        if (!released && !heldKeys.contains(k) && host.loaded(cx(k), cz(k))) {
            host.hold(cx(k), cz(k));
            heldKeys.add(k);
        }
    }

    static int cx(long key) {
        return (int) (key >> 32);
    }

    static int cz(long key) {
        return (int) key;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Der echte Server: Paper-Lader per Reflexion, sonst Spigot-Weg. */
    static Host of(Plugin plugin, World w) {
        Method async = paperAsync(w);
        return new Host() {
            @Override
            public boolean loaded(int cx, int cz) {
                return w.isChunkLoaded(cx, cz);
            }

            @Override
            public CompletableFuture<?> loadAsync(int cx, int cz) {
                if (async == null) {
                    return null;
                }
                try {
                    return (CompletableFuture<?>) async.invoke(w, cx, cz);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.log(Level.FINE, "chunks | paper_async_nicht_nutzbar", e);
                    return null;
                }
            }

            @Override
            public void loadNow(int cx, int cz) {
                w.getChunkAt(cx, cz);
            }

            @Override
            public void hold(int cx, int cz) {
                w.addPluginChunkTicket(cx, cz, plugin);
            }

            @Override
            public void release(int cx, int cz) {
                w.removePluginChunkTicket(cx, cz, plugin);
            }

            @Override
            public void mainThread(Runnable r) {
                if (plugin.isEnabled()) {
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, r);
                }
            }

            @Override
            public boolean onMainThread() {
                return org.bukkit.Bukkit.isPrimaryThread();
            }

            @Override
            public void everyTick(java.util.function.BooleanSupplier task) {
                org.bukkit.scheduler.BukkitTask[] self = new org.bukkit.scheduler.BukkitTask[1];
                self[0] = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (!task.getAsBoolean()) {
                        self[0].cancel();
                    }
                }, 1L, 1L);
            }
        };
    }

    /** Paper: World#getChunkAtAsync(int, int) → CompletableFuture&lt;Chunk&gt;; Spigot hat es nicht. */
    private static Method paperAsync(World w) {
        try {
            Method m = w.getClass().getMethod("getChunkAtAsync", int.class, int.class);
            return CompletableFuture.class.isAssignableFrom(m.getReturnType()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
