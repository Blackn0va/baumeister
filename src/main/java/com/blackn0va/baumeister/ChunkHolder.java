package com.blackn0va.baumeister;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Hält die Chunks eines Stadt-/Weltbaus mit Plugin-Ticket geladen – unter Paper ohne den Hauptthread anzuhalten.
 *
 * Vorher (bis 2026-11-01) rief CityJob für jeden fehlenden Chunk sofort world.addPluginChunkTicket auf. Der Server lädt
 * bzw. erzeugt den Chunk dabei synchron im Hauptthread. Gemessen auf dev01 (Paper, Erdwelt, Kachel 366/6585,
 * 1,17 Mio. Blöcke): längstes Ticket 3599 ms, längster Tick 3792 ms, 431 Ticks über 50 ms – bei 0 Full GC und längster
 * GC-Pause 308 ms im selben Zeitraum; die Spitzen kamen also aus den Tickets, nicht aus der Speicherbereinigung.
 *
 * Jetzt: Fehlt der Chunk und kann der Server asynchron laden (Paper: World.getChunkAtAsync, in der Spigot-API, gegen
 * die das Plugin baut, nicht vorhanden – deshalb über Reflexion), wird er dort bestellt; das Ticket kommt erst, wenn
 * er geladen ist, und kostet dann nur noch den Eintrag. Bis dahin wartet die Bauschleife wie bisher an
 * isChunkLoaded. Unter Spigot (keine Methode) bleibt es beim synchronen Ticket wie vorher.
 * Wer wieder direkt das Ticket nimmt, bringt die Sekunden-Ticks beim Weltbau zurück.
 *
 * Ein Chunk, der aus der Liste fällt (älteste zuerst, höchstens maxHeld) oder dessen Bau endet, bevor das Laden fertig
 * ist, bekommt nachträglich kein Ticket mehr – sonst bliebe er für immer geladen.
 *
 * Nur im Hauptthread benutzen; Paper erfüllt die Futures von getChunkAtAsync im Hauptthread.
 */
final class ChunkHolder {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Was der Halter von der Welt braucht – im Test ein Fake, im Betrieb {@link #of(World, Plugin)}. */
    interface Chunks {
        boolean isLoaded(int cx, int cz);

        void addTicket(int cx, int cz);

        void removeTicket(int cx, int cz);

        /** Lädt bzw. erzeugt den Chunk abseits des Hauptthreads; null = kann der Server nicht (Spigot). */
        CompletableFuture<?> loadAsync(int cx, int cz);
    }

    private final Chunks chunks;
    private final int maxHeld;
    /** Chunks dieses Baus, älteste zuerst – mit Ticket oder mit laufender asynchroner Bestellung. */
    private final LinkedHashSet<Long> held = new LinkedHashSet<>();
    private boolean open = true;
    private int tickets;
    private long ticketNanos;
    private long maxTicketNanos;
    private int asyncOrders;
    private int asyncFailures;

    ChunkHolder(Chunks chunks, int maxHeld) {
        this.chunks = chunks;
        this.maxHeld = maxHeld;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    boolean contains(long key) {
        return held.contains(key);
    }

    int size() {
        return held.size();
    }

    /** Hält den Chunk (bestellt ihn, wenn nötig) und gibt den ältesten frei, sobald es mehr als maxHeld sind. */
    void hold(int cx, int cz) {
        long k = key(cx, cz);
        if (!open || !held.add(k)) {
            return;
        }
        CompletableFuture<?> order = chunks.isLoaded(cx, cz) ? null : chunks.loadAsync(cx, cz);
        if (order == null) {
            ticket(cx, cz);
        } else {
            asyncOrders++;
            order.whenComplete((c, err) -> {
                if (err != null) {
                    asyncFailures++;
                    LOG.log(Level.WARNING, "stadt | chunk_async_fehlgeschlagen | chunk=" + cx + "," + cz
                            + " – nehme das Ticket direkt", err);
                }
                // Inzwischen freigegeben oder Bau vorbei: kein Ticket mehr, sonst bliebe der Chunk ewig geladen.
                if (open && held.contains(k)) {
                    ticket(cx, cz);
                }
            });
        }
        if (held.size() > maxHeld) {
            long old = held.removeFirst();
            chunks.removeTicket((int) (old >> 32), (int) old);
        }
    }

    private void ticket(int cx, int cz) {
        long t0 = System.nanoTime();
        chunks.addTicket(cx, cz);
        long took = System.nanoTime() - t0;
        tickets++;
        ticketNanos += took;
        maxTicketNanos = Math.max(maxTicketNanos, took);
    }

    /** Gibt alle Tickets frei; danach nimmt der Halter nichts mehr an (auch keine späten asynchronen Ergebnisse). */
    void releaseAll() {
        open = false;
        for (long k : held) {
            chunks.removeTicket((int) (k >> 32), (int) k);
        }
        held.clear();
    }

    int tickets() {
        return tickets;
    }

    long ticketNanos() {
        return ticketNanos;
    }

    long maxTicketNanos() {
        return maxTicketNanos;
    }

    int asyncOrders() {
        return asyncOrders;
    }

    int asyncFailures() {
        return asyncFailures;
    }

    /** Die echte Welt; getChunkAtAsync nur, wenn der Server es anbietet (Paper). */
    static Chunks of(World world, Plugin plugin) {
        Method async = asyncMethod(world);
        return new Chunks() {
            @Override
            public boolean isLoaded(int cx, int cz) {
                return world.isChunkLoaded(cx, cz);
            }

            @Override
            public void addTicket(int cx, int cz) {
                world.addPluginChunkTicket(cx, cz, plugin);
            }

            @Override
            public void removeTicket(int cx, int cz) {
                world.removePluginChunkTicket(cx, cz, plugin);
            }

            @Override
            public CompletableFuture<?> loadAsync(int cx, int cz) {
                if (async == null) {
                    return null;
                }
                try {
                    return (CompletableFuture<?>) async.invoke(world, cx, cz);
                } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                    // fail-open auf den alten Weg: Das synchrone Ticket ist langsam, aber richtig.
                    LOG.log(Level.WARNING, "stadt | chunk_async_aus | grund=" + e, e);
                    return null;
                }
            }
        };
    }

    /** World.getChunkAtAsync(int, int) mit CompletableFuture als Rückgabe, sonst null (Spigot). */
    static Method asyncMethod(Object world) {
        if (world == null) {
            return null;
        }
        try {
            Method m = world.getClass().getMethod("getChunkAtAsync", int.class, int.class);
            return CompletableFuture.class.isAssignableFrom(m.getReturnType()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;  // Spigot: kein asynchrones Laden, das synchrone Ticket bleibt
        }
    }
}
