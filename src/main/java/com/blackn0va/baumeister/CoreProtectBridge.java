package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.WorldWatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.result.ContainerResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * BlockHistory über die CoreProtect-API (CE 24.1 auf dev01, APIVersion geprüft). API-Form per javap am JAR von dev01
 * gemessen (2026-11-05): containerLookup(Location, int) → ContainerResult (getTimestamp in ms, Aktion 0 = remove,
 * 1 = add), performLookup/performRollback(int Sekunden, users, …, action_list, radius, Location) → String[] je
 * Eintrag. Aktion 0 in action_list = Block entfernt.
 *
 * Threads: Abfragen im Bukkit-Async-Pool, Antwort im Hauptthread. Fehler: breit gefangen an der Thread-Grenze (eine
 * fremde Bibliothek), geloggt; dann leeres Ergebnis bzw. -1 – Vera sagt dann, dass sie nichts weiß.
 */
final class CoreProtectBridge implements BlockHistory {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Mit API 10 kamen containerLookup und die Result-Klassen. */
    static final int MIN_API = 10;

    private final Plugin plugin;
    private final CoreProtectAPI api;

    private CoreProtectBridge(Plugin plugin, CoreProtectAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** null, wenn CoreProtect fehlt, die API aus ist oder zu alt. */
    static BlockHistory connect(Plugin plugin) {
        Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (!(cp instanceof CoreProtect core) || !cp.isEnabled()) {
            return null;
        }
        CoreProtectAPI api = core.getAPI();
        if (api == null || !api.isEnabled() || api.APIVersion() < MIN_API) {
            LOG.warning(() -> "protokoll | coreprotect_api_unbrauchbar | version="
                    + (api == null ? "?" : api.APIVersion()) + " – Reparieren und Kisten-Auskunft aus");
            return null;
        }
        LOG.info(() -> "protokoll | coreprotect | api=" + api.APIVersion());
        return new CoreProtectBridge(plugin, api);
    }

    private <T> void async(String what, java.util.function.Supplier<T> work, T fallback, Consumer<T> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "protokoll | abfrage_fehlgeschlagen | was=" + what, e);
                result = fallback;
            }
            T r = result == null ? fallback : result;
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(r));
        });
    }

    @Override
    public void chest(Location at, int seconds, Consumer<List<WorldWatch.ChestEntry>> done) {
        Location l = at.clone();
        async("kiste", () -> {
            List<ContainerResult> rows = api.containerLookup(l, seconds);
            List<WorldWatch.ChestEntry> out = new ArrayList<>();
            if (rows != null) {
                for (ContainerResult r : rows) {
                    if (r.isRolledBack() || r.getType() == null || (r.getActionId() != 0 && r.getActionId() != 1)) {
                        continue;
                    }
                    out.add(new WorldWatch.ChestEntry(r.getPlayer(), r.getActionId() == 1,
                            r.getType().getKey().getKey(), r.getAmount(), r.getTimestamp()));
                }
            }
            return out;
        }, List.of(), done);
    }

    @Override
    public void placedBy(Block block, int seconds, Consumer<Optional<String>> done) {
        Block b = block;
        async("gesetzt_von", () -> {
            List<String[]> rows = api.blockLookup(b, seconds);
            if (rows != null) {
                for (String[] row : rows) {
                    CoreProtectAPI.ParseResult r = api.parseResult(row);
                    if (r.getActionId() == 1 && !r.isRolledBack()) {
                        return Optional.of(r.getPlayer());
                    }
                }
            }
            return Optional.<String>empty();
        }, Optional.empty(), done);
    }

    @Override
    public void countDamage(Location at, int radius, int seconds, Consumer<Integer> done) {
        Location l = at.clone();
        async("schaden_zählen", () -> {
            List<String[]> rows = api.performLookup(seconds, WorldWatch.DAMAGE_CAUSES, null, null, null, List.of(0),
                    radius, l);
            if (rows == null) {
                return 0;
            }
            int n = 0;
            for (String[] row : rows) {
                if (!api.parseResult(row).isRolledBack()) {
                    n++;
                }
            }
            return n;
        }, -1, done);
    }

    @Override
    public void repair(Location at, int radius, int seconds, Consumer<Integer> done) {
        Location l = at.clone();
        async("reparieren", () -> {
            List<String[]> rows = api.performRollback(seconds, WorldWatch.DAMAGE_CAUSES, null, null, null, List.of(0),
                    radius, l);
            return rows == null ? -1 : rows.size();
        }, -1, done);
    }
}
