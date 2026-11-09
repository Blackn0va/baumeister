import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.redstone.RedstoneReader;
import com.blackn0va.baumeister.redstone.RedstoneRepair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Messprobe Umbauen: Verstärker falsch herum und Signal nach 17 Staub aufgebraucht – RedstoneRepair setzt die
 * Umbauten aus RedstoneReader, danach wird abgelesen, ob die Lampe leuchtet.
 */
public final class FixProbe extends JavaPlugin {

    private World w;
    private int g;
    private final List<String> out = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::run, 1L);
    }

    private void set(int x, int z, String data) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, g, z).setBlockData(Bukkit.createBlockData("minecraft:" + data), true);
    }

    private void run() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 1;
        // Spur 1: Verstärker falsch herum.
        set(1, 0, "redstone_wire");
        set(2, 0, "redstone_wire");
        set(3, 0, "repeater[facing=east]");
        set(4, 0, "redstone_lamp");
        set(0, 0, "lever[face=floor,facing=east,powered=true]");
        // Spur 2: 17 Staub, Lampe am Ende.
        for (int i = 1; i <= 17; i++) {
            set(32 + i, 0, "redstone_wire");
        }
        set(50, 0, "redstone_lamp");
        set(32, 0, "lever[face=floor,facing=east,powered=true]");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (int[] lane : new int[][] {{0, 4}, {32, 50}}) {
                Map<Pos, String> blocks = new HashMap<>();
                for (int x = lane[0] - 1; x <= lane[1] + 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = w.getBlockAt(x, g, z);
                        if (!b.getType().isAir()) {
                            blocks.put(new Pos(x, g, z), b.getBlockData().getAsString());
                        }
                    }
                }
                RedstoneReader.Report r = RedstoneReader.read(blocks, s -> s);
                List<RedstoneRepair.Applied> done = RedstoneRepair.apply(w, r.findings(), b -> true);
                out.add("spur " + lane[0] + " | vorher lampe=" + w.getBlockAt(lane[1], g, 0).getBlockData()
                        .getAsString() + " | umbauten=" + done.size() + " " + done.stream()
                        .map(a -> a.finding().fixText()).toList());
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                out.add("nachher spur 0 lampe=" + w.getBlockAt(4, g, 0).getBlockData().getAsString());
                out.add("nachher spur 32 lampe=" + w.getBlockAt(50, g, 0).getBlockData().getAsString());
                try {
                    Files.createDirectories(getDataFolder().toPath());
                    Files.write(getDataFolder().toPath().resolve("umbau.txt"), out, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
                }
                getLogger().info("probe | fertig");
                Bukkit.shutdown();
            }, 30L);
        }, 20L);
    }
}
