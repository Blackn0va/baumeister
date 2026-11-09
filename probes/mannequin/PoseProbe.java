import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Messprobe Posen: Behält ein Mannequin eine gesetzte Pose (Ducken, Schwimmen, Gleiten, Schlafen) über 40 Ticks?
 * Auf Paper über setPose(Pose, boolean fest), auf Spigot über Mannequin.setPose(Pose) – per Reflexion wie
 * MannequinCompat.pose, damit diese Probe genau den Weg des Plugins misst.
 */
public final class PoseProbe extends JavaPlugin {

    private final List<String> out = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::run, 1L);
    }

    private void run() {
        World w = Bukkit.getWorlds().get(0);
        int g = w.getHighestBlockYAt(0, 0) + 1;
        List<Mannequin> ms = new ArrayList<>();
        List<Pose> poses = List.of(Pose.STANDING, Pose.SNEAKING, Pose.SWIMMING, Pose.FALL_FLYING, Pose.SLEEPING);
        for (int i = 0; i < poses.size(); i++) {
            Location at = new Location(w, i * 4 + 0.5, g, 0.5);
            w.getChunkAt(at).addPluginChunkTicket(this);
            Mannequin m = w.spawn(at, Mannequin.class, e -> {
                e.setPersistent(false);
                e.setGravity(true);
                e.setImmovable(false);
            });
            String how = pose(m, poses.get(i));
            out.add("gesetzt | " + poses.get(i) + " | weg=" + how + " | sofort=" + m.getPose());
            ms.add(m);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (int i = 0; i < ms.size(); i++) {
                out.add("nach_40_ticks | " + poses.get(i) + " | ist=" + ms.get(i).getPose());
            }
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.write(getDataFolder().toPath().resolve("posen.txt"), out, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
            }
            getLogger().info("probe | fertig");
            Bukkit.shutdown();
        }, 40L);
    }

    static String pose(Mannequin m, Pose p) {
        try {
            Method fixed = m.getClass().getMethod("setPose", Pose.class, boolean.class);
            fixed.invoke(m, p, true);
            return "paper_fest";
        } catch (ReflectiveOperationException e) {
            m.setPose(p);
            return "spigot";
        }
    }
}
