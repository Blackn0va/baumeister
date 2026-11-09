import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Messprobe Redstone: baut kleine Schaltungen auf einer flachen Welt, lässt sie 40 Ticks laufen und schreibt jeden
 * Block als BlockData-Text (getAsString) heraus. Klärt, wie die Zustände wirklich heißen und welche Seite eines
 * Verstärkers der Eingang ist – die Texte sind zugleich die Testdaten von RedstoneReaderTest.
 */
public final class RedstoneProbe extends JavaPlugin {

    private World w;
    private int g;
    private final List<String> out = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::build, 1L);
    }

    private void build() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 1;
        int lane = 0;
        // 1 + 2: Verstärker in beiden Richtungen hinter Hebel und Staub, Lampe dahinter. Welche leuchtet?
        for (String facing : List.of("west", "east")) {
            int x = lane++ * 32;
            set(x + 1, "redstone_wire");
            set(x + 2, "redstone_wire");
            set(x + 3, "repeater[facing=" + facing + "]");
            set(x + 4, "redstone_lamp");
            set(x, "lever[face=floor,facing=east,powered=true]");
        }
        // 3: 17 Staub hinter dem Hebel – wo endet das Signal?
        {
            int x = lane++ * 32;
            for (int i = 1; i <= 17; i++) {
                set(x + i, "redstone_wire");
            }
            set(x + 18, "redstone_lamp");
            set(x, "lever[face=floor,facing=east,powered=true]");
        }
        // 4: Fackel als NICHT: Hebel → Staub → Stein, Wandfackel am Stein, Lampe dahinter.
        {
            int x = lane++ * 32;
            set(x + 1, "redstone_wire");
            set(x + 2, "stone");
            setAt(x + 3, g, 0, "redstone_wall_torch[facing=east]");
            set(x + 4, "redstone_lamp");
            set(x, "lever[face=floor,facing=east,powered=true]");
        }
        // 5: gesperrter Verstärker (seitlich von einem zweiten Verstärker gespeist).
        {
            int x = lane++ * 32;
            set(x + 1, "redstone_wire");
            set(x + 2, "repeater[facing=west]");
            set(x + 3, "redstone_lamp");
            setAt(x + 2, g, 1, "repeater[facing=south]");
            setAt(x + 2, g, 2, "redstone_block");
            set(x, "lever[face=floor,facing=east,powered=false]");
            Bukkit.getScheduler().runTaskLater(this, () -> set(x, "lever[face=floor,facing=east,powered=true]"), 10L);
        }
        // 6: Kolben direkt neben dem Hebel; Beobachter vor einem Block.
        {
            int x = lane++ * 32;
            set(x + 1, "piston[facing=up]");
            set(x, "lever[face=floor,facing=east,powered=true]");
            setAt(x + 4, g, 0, "observer[facing=east]");
            setAt(x + 3, g, 0, "redstone_lamp");
        }
        // 7: Komparator an einer Truhe mit 10 Stapeln Stein.
        {
            int x = lane++ * 32;
            set(x, "chest[facing=north]");
            Block chest = w.getBlockAt(x, g, 0);
            for (int i = 0; i < 10; i++) {
                ((Chest) chest.getState()).getBlockInventory().setItem(i, new ItemStack(Material.STONE, 64));
            }
            set(x + 1, "comparator[facing=west]");
            set(x + 2, "redstone_wire");
            set(x + 3, "redstone_lamp");
        }
        // 8: Lampe ohne jede Verbindung (Hebel zwei Blöcke daneben).
        {
            int x = lane++ * 32;
            set(x, "lever[face=floor,facing=east,powered=true]");
            set(x + 2, "redstone_lamp");
        }
        Bukkit.getScheduler().runTaskLater(this, this::dump, 40L);
    }

    private void set(int x, String data) {
        setAt(x, g, 0, data);
    }

    private void setAt(int x, int y, int z, String data) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData("minecraft:" + data), true);
    }

    private void dump() {
        for (int lane = 0; lane < 8; lane++) {
            for (int x = lane * 32 - 1; x <= lane * 32 + 19; x++) {
                for (int z = -1; z <= 2; z++) {
                    for (int y = g; y <= g + 1; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) {
                            out.add(lane + " " + (x - lane * 32) + " " + (y - g) + " " + z + " "
                                    + b.getBlockData().getAsString());
                        }
                    }
                }
            }
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("bloecke.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig | bloecke=" + out.size());
        Bukkit.shutdown();
    }
}
