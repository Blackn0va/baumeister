import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.redstone.RedstoneLab;
import com.blackn0va.baumeister.redstone.RedstoneReader;

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
 * Messprobe Ausprobieren: baut vier Gatter (UND aus Fackeln, ODER, NICHT, Hebel durch einen Block) je zweimal und
 * lässt RedstoneLab sie durchschalten – einmal ohne, einmal mit Anstupsen. Erwartet wird je Gatter die Formel daneben.
 */
public final class LabProbe extends JavaPlugin {

    private World w;
    private int g;
    private final List<String> out = new ArrayList<>();
    private int pending;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::run, 1L);
    }

    private void set(int x, int y, int z, String data) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData("minecraft:" + data), true);
    }

    private void build(String gate, int x) {
        switch (gate) {
            case "und" -> {
                set(x + 1, g, 0, "stone");
                set(x + 1, g, 2, "stone");
                set(x + 1, g, 1, "stone");
                set(x + 1, g + 1, 0, "redstone_torch");
                set(x + 1, g + 1, 2, "redstone_torch");
                set(x + 1, g + 1, 1, "redstone_wire");
                set(x + 2, g, 1, "redstone_wall_torch[facing=east]");
                set(x + 3, g, 1, "redstone_lamp");
                set(x, g, 0, "lever[face=wall,facing=west,powered=false]");
                set(x, g, 2, "lever[face=wall,facing=west,powered=false]");
            }
            case "oder" -> {
                for (int i = 1; i <= 3; i++) {
                    set(x + i, g, 0, "redstone_wire");
                }
                set(x + 4, g, 0, "redstone_lamp");
                set(x, g, 0, "lever[face=floor,facing=east,powered=false]");
                set(x + 2, g, 1, "lever[face=floor,facing=east,powered=false]");
            }
            case "nicht" -> {
                set(x + 1, g, 0, "stone");
                set(x + 2, g, 0, "redstone_wall_torch[facing=east]");
                set(x + 3, g, 0, "redstone_lamp");
                set(x, g, 0, "lever[face=wall,facing=west,powered=false]");
            }
            case "durch_block" -> {
                set(x + 1, g, 0, "stone");
                set(x + 2, g, 0, "redstone_wire");
                set(x + 3, g, 0, "redstone_wire");
                set(x + 4, g, 0, "redstone_lamp");
                set(x, g, 0, "lever[face=wall,facing=west,powered=false]");
            }
            default -> throw new IllegalArgumentException(gate);
        }
    }

    private void run() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 1;
        int lane = 0;
        List<Object[]> todo = new ArrayList<>();
        for (String gate : List.of("und", "oder", "nicht", "durch_block")) {
            for (boolean poke : List.of(false, true)) {
                int x = lane++ * 32;
                build(gate, x);
                todo.add(new Object[] {gate, poke, x});
            }
        }
        // Erst einen Augenblick laufen lassen, damit die Fackeln ihren Anfangszustand haben.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Object[] t : todo) {
                start((String) t[0], (boolean) t[1], (int) t[2]);
            }
        }, 20L);
    }

    private void start(String gate, boolean poke, int x) {
        Map<Pos, String> blocks = new HashMap<>();
        for (int dx = -1; dx <= 6; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 3; dz++) {
                    Block b = w.getBlockAt(x + dx, g + dy, dz);
                    if (!b.getType().isAir()) {
                        blocks.put(new Pos(b.getX(), b.getY(), b.getZ()), b.getBlockData().getAsString());
                    }
                }
            }
        }
        RedstoneReader.Report r = RedstoneReader.read(blocks, s -> s);
        String name = gate + (poke ? "_angestupst" : "_ohne");
        if (r.empty()) {
            out.add(name + " | keine_schaltung");
            return;
        }
        pending++;
        String refused = RedstoneLab.start(this, w, r.circuits().get(0), b -> true, poke, res -> {
            StringBuilder sb = new StringBuilder(name + " | schritte=" + res.steps() + " ms=" + res.millis());
            res.outputs().forEach(o -> sb.append(" | ").append(o.formula()).append(o.memory() ? " (merkt)" : ""));
            if (res.error() != null) {
                sb.append(" | fehler=").append(res.error());
            }
            out.add(sb.toString());
            if (--pending == 0) {
                finish();
            }
        });
        if (refused != null) {
            out.add(name + " | abgelehnt=" + refused);
            if (--pending == 0) {
                finish();
            }
        }
    }

    private void finish() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("labor.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }
}
