import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Misst das Blocklicht in einem gebauten Bereich mit der Lichtberechnung des Servers (2026-11-15, Lobby): Frage war,
 * ob LobbyTest.nothingCanSpawnInsideAtNight (eigene Rechnung über die Seelaternen) mit Minecraft übereinstimmt.
 *
 * Bereich aus plugins/LichtProbe/bereich.txt: „welt x0 y0 z0 x1 y1 z1“ (Innenraum). Gezählt wird jede Zelle, auf der
 * ein Monster stehen könnte: Luft, darüber Luft, darunter ein voller undurchsichtiger Block (Platten, Glas und Teppich
 * zählen nicht). Monster brauchen seit 1.18 Blocklicht 0. Ergebnis in plugins/LichtProbe/licht.txt, 100 Ticks nach
 * dem Start, damit die Lichtberechnung der geladenen Chunks fertig ist.
 */
public final class LichtProbe extends JavaPlugin {

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::measure, 100L);
    }

    private void measure() {
        Path dir = getDataFolder().toPath();
        List<String> out = new ArrayList<>();
        try {
            String[] a = Files.readString(dir.resolve("bereich.txt"), StandardCharsets.UTF_8).trim().split("\\s+");
            World w = Bukkit.getWorld(a[0]);
            int x0 = Integer.parseInt(a[1]);
            int y0 = Integer.parseInt(a[2]);
            int z0 = Integer.parseInt(a[3]);
            int x1 = Integer.parseInt(a[4]);
            int y1 = Integer.parseInt(a[5]);
            int z1 = Integer.parseInt(a[6]);
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    w.getChunkAt(cx, cz).load();
                }
            }
            int cells = 0;
            int dark = 0;
            int min = 99;
            int[] histogram = new int[16];
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir() || !b.getRelative(0, 1, 0).getType().isAir()
                                || !b.getRelative(0, -1, 0).getType().isOccluding()) {
                            continue;
                        }
                        int light = b.getLightFromBlocks();
                        cells++;
                        histogram[light]++;
                        min = Math.min(min, light);
                        if (light == 0) {
                            dark++;
                            if (dark <= 20) {
                                out.add("dunkel | x=" + x + " y=" + y + " z=" + z + " unter="
                                        + b.getRelative(0, -1, 0).getType());
                            }
                        }
                    }
                }
            }
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                h.append(i).append(':').append(histogram[i]).append(' ');
            }
            out.addFirst("licht | zellen=" + cells + " dunkel=" + dark + " minimum=" + min + " verteilung="
                    + h.toString().trim());
            Files.write(dir.resolve("licht.txt"), out, StandardCharsets.UTF_8);
            getLogger().info("probe | licht | zellen=" + cells + " dunkel=" + dark + " minimum=" + min);
        } catch (IOException | RuntimeException e) {
            getLogger().severe("probe | fehler | " + e);
        }
    }
}
