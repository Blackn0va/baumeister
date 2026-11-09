package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit-Seite der Fortschrittsbalken: sammelt alle 10 Ticks die laufenden Aufträge (Bau, Stadtbau, Weltbau) und die
 * Spielerpositionen und gibt sie an ProgressBars. Die Dienste werden je Takt neu geholt – /bm neuladen ersetzt
 * BuildService, ein festgehaltener Verweis zeigte danach auf den alten.
 *
 * Nicht-persistente BossBars (Bukkit.createBossBar ohne Schlüssel): Ein Absturz mitten im Bau lässt nach dem Neustart
 * keinen Balken zurück – ein KeyedBossBar stünde in level.dat und hinge beim nächsten Start ohne Auftrag herum.
 *
 * Threads: nur Hauptthread (Bukkit-Takt). Fehler: Ausnahme im Takt wird geloggt und schaltet nur die Balken ab, nie den
 * Bau (bewusst fail-open – ein Balken ist Komfort).
 */
final class ProgressBarTask {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final long PERIOD_TICKS = 10L;

    private final BaumeisterPlugin plugin;
    private final ProgressBars bars;
    private BukkitTask task;

    private ProgressBarTask(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.bars = new ProgressBars(ProgressBarTask::bukkitBar, ComfortSettings.BOSS_BAR_RADIUS);
    }

    static ProgressBarTask start(BaumeisterPlugin plugin) {
        ProgressBarTask t = new ProgressBarTask(plugin);
        t.task = Bukkit.getScheduler().runTaskTimer(plugin, t::tickGuarded, PERIOD_TICKS, PERIOD_TICKS);
        return t;
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        bars.clear();
    }

    private void tickGuarded() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "balken | fehler | balken werden ausgeblendet", e);
            bars.clear();
        }
    }

    private void tick() {
        ComfortSettings c = ComfortSettings.from(plugin.getConfig());
        if (!c.bossBar()) {
            bars.clear();
            return;
        }
        bars.radius(c.bossBarRadius());
        List<ProgressBars.Job> jobs = new ArrayList<>();
        BuildService service = plugin.service();
        BuildJob b = service == null ? null : service.activeJob();
        if (b != null) {
            Location at = b.center();
            boolean checking = b.checking();
            int total = Math.max(1, b.total());
            double p = checking ? (double) b.checked() / total : (double) b.done() / total;
            String title = checking ? plugin.npc().name() + " prüft den Bauplatz …"
                    : plugin.npc().name() + " baut: " + b.done() + " / " + b.total();
            jobs.add(new ProgressBars.Job("bau-" + System.identityHashCode(b), ProgressBars.Kind.BUILD, title, p,
                    b.ownerId(), at.getWorld().getName(), at.getX(), at.getZ()));
        }
        CityService city = plugin.city();
        addCity(jobs, city == null ? null : city.activeJob(), ProgressBars.Kind.CITY, "Stadtbau");
        WorldBuildService weltbau = plugin.weltbau();
        addCity(jobs, weltbau == null ? null : weltbau.activeJob(), ProgressBars.Kind.WORLD, "Weltbau");
        List<ProgressBars.Viewer> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            online.add(new ProgressBars.Viewer(p.getUniqueId(), l.getWorld().getName(), l.getX(), l.getZ()));
        }
        bars.update(jobs, online);
    }

    private static void addCity(List<ProgressBars.Job> jobs, CityJob j, ProgressBars.Kind kind, String label) {
        if (j == null) {
            return;
        }
        Location at = j.center();
        double p = j.fraction();
        jobs.add(new ProgressBars.Job(kind + "-" + System.identityHashCode(j), kind, label + ": " + j.place() + " – "
                + Math.round(p * 100) + " %" + (j.paused() ? " (Pause)" : ""), p, j.owner(), at.getWorld().getName(),
                at.getX(), at.getZ()));
    }

    private static ProgressBars.Bar bukkitBar(String key, ProgressBars.Kind kind) {
        BarColor color = switch (kind) {
            case BUILD -> BarColor.PURPLE;
            case CITY -> BarColor.BLUE;
            case WORLD -> BarColor.GREEN;
        };
        BossBar bar = Bukkit.createBossBar("", color, BarStyle.SEGMENTED_10);
        return new ProgressBars.Bar() {
            @Override
            public void title(String title) {
                if (!title.equals(bar.getTitle())) {
                    bar.setTitle(title);
                }
            }

            @Override
            public void progress(double progress) {
                bar.setProgress(progress);
            }

            @Override
            public void viewers(Set<UUID> players) {
                Set<UUID> have = new HashSet<>();
                for (Player p : List.copyOf(bar.getPlayers())) {
                    if (!players.contains(p.getUniqueId()) || !p.isOnline()) {
                        bar.removePlayer(p);
                    } else {
                        have.add(p.getUniqueId());
                    }
                }
                for (UUID id : players) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && !have.contains(id)) {
                        bar.addPlayer(p);
                    }
                }
            }

            @Override
            public void remove() {
                bar.removeAll();
                bar.setVisible(false);
            }
        };
    }
}
