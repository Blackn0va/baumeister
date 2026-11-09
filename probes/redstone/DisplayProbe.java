import com.blackn0va.baumeister.logic.SevenSegment;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Schnellprobe 7-Segment-Anzeige: setzt SevenSegment.compile() direkt (Blick nach Norden wie MachineProbe „n“), stupst
 * jedes Redstone-Teil an (wie BuildJob.refreshRedstone), legt für jeden Wert 0–F die Eingänge als Redstone-Block bzw.
 * Hebel aus an (wie MachineProbe) und liest alle 27 Lampen. Schneller als der Bau über das Plugin (Minuten) – die
 * Schlussprüfung bleibt MachineProbe.
 */
public final class DisplayProbe extends JavaPlugin {

    private World w;
    private int gx;
    private int gy;
    private int gz;
    private final Orientation o = new Orientation(Orientation.Cardinal.NORTH);
    private final List<String> out = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::run, 1L);
    }

    private Block at(Pos local) {
        Pos p = o.toWorldOffset(local);
        return w.getBlockAt(gx + p.x(), gy + p.y(), gz + p.z());
    }

    private void run() {
        w = Bukkit.getWorlds().get(0);
        gx = 0;
        gz = 0;
        gy = w.getHighestBlockYAt(0, 0);
        SevenSegment.Display d = SevenSegment.compile();
        for (int cx = -10; cx <= 10; cx++) {
            for (int cz = -10; cz <= 10; cz++) {
                w.getChunkAt(cx, cz).addPluginChunkTicket(this);
            }
        }
        // Von unten nach oben wie der Bau (sonst fällt Staub ab, dessen Stein noch fehlt).
        List<CompiledBuild.Change> order = new ArrayList<>(d.module().cells());
        order.sort(java.util.Comparator.comparingInt(c -> c.pos().y()));
        for (CompiledBuild.Change c : order) {
            BlockSpec world = o.toWorld(c.block());
            at(c.pos()).setBlockData(Bukkit.createBlockData(world.asString()), true);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (CompiledBuild.Change c : d.module().cells()) {
                Block b = at(c.pos());
                Material t = b.getType();
                if (t == Material.REPEATER || t == Material.COMPARATOR || t == Material.REDSTONE_WIRE
                        || t == Material.REDSTONE_TORCH || t == Material.REDSTONE_WALL_TORCH
                        || t == Material.REDSTONE_LAMP) {
                    for (BlockFace f : new BlockFace[] {BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH,
                        BlockFace.EAST, BlockFace.WEST}) {
                        Block n = b.getRelative(f);
                        if (n.getType().isAir()) {
                            n.setType(Material.GLASS, true);
                            n.setType(Material.AIR, true);
                            break;
                        }
                    }
                }
            }
            step(d, 0);
        }, 20L);
    }

    private void step(SevenSegment.Display d, int v) {
        if (v >= 16) {
            finish();
            return;
        }
        for (int i = 0; i < 4; i++) {
            Block b = at(d.levers().get(i));
            if ((v >> i & 1) == 1) {
                b.setType(Material.REDSTONE_BLOCK, true);
            } else {
                BlockSpec lever = o.toWorld(BlockSpec.of("lever").with("face", "floor").with("facing", "back"));
                b.setBlockData(Bukkit.createBlockData(lever.asString()), true);
            }
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            StringBuilder got = new StringBuilder();
            StringBuilder bad = new StringBuilder();
            for (Map.Entry<Character, List<Pos>> seg : d.segments().entrySet()) {
                StringBuilder lamps = new StringBuilder();
                for (Pos p : seg.getValue()) {
                    lamps.append(at(p).getBlockData() instanceof Lightable l && l.isLit() ? '1' : '0');
                }
                boolean want = SevenSegment.lit(v, seg.getKey());
                String expect = String.valueOf(want ? '1' : '0').repeat(seg.getValue().size());
                got.append(seg.getKey()).append('=').append(lamps).append(' ');
                if (!lamps.toString().equals(expect)) {
                    bad.append(seg.getKey());
                }
            }
            StringBuilder dec = new StringBuilder();
            String order = "afedgcb";
            for (int j = 0; j < d.taps().size(); j++) {
                int power = at(d.taps().get(j)).getBlockData() instanceof org.bukkit.block.data.AnaloguePowerable ap
                        ? ap.getPower() : -1;
                boolean want = SevenSegment.lit(v, order.charAt(j));
                dec.append(order.charAt(j)).append(power > 0 ? '1' : '0').append(want ? "" : "").append(
                        (power > 0) != want ? "!" : "").append(' ');
            }
            out.add(Integer.toHexString(v) + " | " + got + "| decoder " + dec + "| "
                    + (bad.isEmpty() ? "ok" : "FALSCH " + bad));
            step(d, v + 1);
        }, 60L);
    }

    private void finish() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("anzeige.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }
}
