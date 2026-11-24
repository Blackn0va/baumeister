import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Messprobe Rechte (T-038, 2026-11-24): Gibt einem Spieler, der KEIN Op ist, einzelne Rechte über ein
 * PermissionAttachment – ohne Rechte-Plugin und ohne Download. Zweck: Stadtleben-Feldarbeit und Bewohner-Bauten
 * laufen im Namen eines Admins (baumeister.admin). Mit einem Op-Bot war das WorldGuard-Verbot unbelegt, denn Op hat
 * jedes Recht, also auch worldguard.region.bypass.&lt;welt&gt; – WorldGuard winkt dann alles durch (T-037).
 *
 * Befehle (RCON oder Konsole):
 *   rechteprobe gib &lt;spieler&gt; &lt;recht&gt;    Recht geben (bleibt über Wiederkommen, nur im Speicher)
 *   rechteprobe nimm &lt;spieler&gt; &lt;recht&gt;   Recht wieder nehmen
 *   rechteprobe zeig &lt;spieler&gt;            op, baumeister.admin, worldguard.region.bypass.&lt;welt&gt;
 *   rechteprobe frag &lt;spieler&gt; &lt;welt&gt; x y z
 *       Vergleich ohne Baumeister: Abbau- und Setz-Ereignis im Namen des Spielers an dieser Stelle, Urteil der
 *       Schutz-Plugins (nichts geändert)
 *   rechteprobe typen &lt;welt&gt; x0 z0 x1 z1 y  Blocknamen einer Schicht (Platzwahl der Baufelder nachsehen)
 *   rechteprobe sag &lt;spieler&gt; &lt;text …&gt;
 *       Chatzeile im Namen des Spielers (Player.chat, wie getippt) – für Zuruf an Bewohner („Lotte, komm mit“), ohne
 *       Chat-Pakete im Probe-Bot
 * Jede Antwort steht zusätzlich im Server-Log mit Präfix „rechteprobe |“.
 *
 * Nicht ins Plugin-Jar – nur für Testserver.
 */
public final class RechteProbe extends JavaPlugin implements Listener {

    private final Map<String, Set<String>> granted = new HashMap<>();
    private final Map<String, PermissionAttachment> attachments = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        attachments.remove(key(e.getPlayer().getName()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            say(sender, "aufruf: gib|nimm <spieler> <recht> | zeig <spieler> | frag <spieler> <welt> x y z");
            return true;
        }
        String name = args[1];
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "gib" -> {
                if (args.length < 3) {
                    say(sender, "fehlt: recht");
                    return true;
                }
                granted.computeIfAbsent(key(name), k -> new LinkedHashSet<>()).add(args[2]);
                Player p = Bukkit.getPlayerExact(name);
                if (p != null) {
                    apply(p);
                }
                say(sender, "gegeben spieler=" + name + " recht=" + args[2] + " online=" + (p != null));
            }
            case "nimm" -> {
                if (args.length < 3) {
                    say(sender, "fehlt: recht");
                    return true;
                }
                Set<String> s = granted.get(key(name));
                if (s != null) {
                    s.remove(args[2]);
                }
                Player p = Bukkit.getPlayerExact(name);
                if (p != null) {
                    apply(p);
                }
                say(sender, "genommen spieler=" + name + " recht=" + args[2]);
            }
            case "zeig" -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null) {
                    say(sender, "offline spieler=" + name);
                    return true;
                }
                String world = p.getWorld().getName();
                say(sender, "zeig spieler=" + name + " op=" + p.isOp() + " baumeister.admin="
                        + p.hasPermission("baumeister.admin") + " worldguard.region.bypass." + world + "="
                        + p.hasPermission("worldguard.region.bypass." + world) + " welt=" + world);
            }
            case "frag" -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || args.length < 6) {
                    say(sender, "frag braucht spieler online, welt x y z");
                    return true;
                }
                World w = Bukkit.getWorld(args[2]);
                if (w == null) {
                    say(sender, "welt unbekannt: " + args[2]);
                    return true;
                }
                Block b = w.getBlockAt(Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
                BlockBreakEvent br = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(br);
                BlockPlaceEvent pl = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN),
                        new ItemStack(Material.STONE), p, true, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(pl);
                say(sender, "frag spieler=" + name + " x=" + b.getX() + " y=" + b.getY() + " z=" + b.getZ()
                        + " abbau_erlaubt=" + !br.isCancelled() + " setzen_erlaubt=" + (!pl.isCancelled()
                        && pl.canBuild()));
            }
            case "typen" -> {
                World w = Bukkit.getWorld(name);
                if (w == null || args.length < 7) {
                    say(sender, "typen braucht welt x0 z0 x1 z1 y");
                    return true;
                }
                int x0 = Integer.parseInt(args[2]);
                int z0 = Integer.parseInt(args[3]);
                int x1 = Integer.parseInt(args[4]);
                int z1 = Integer.parseInt(args[5]);
                int y = Integer.parseInt(args[6]);
                for (int z = z0; z <= z1; z++) {
                    StringBuilder line = new StringBuilder("typen y=" + y + " z=" + z + ":");
                    for (int x = x0; x <= x1; x++) {
                        line.append(' ').append(w.getBlockAt(x, y, z).getType().getKey().getKey());
                    }
                    say(sender, line.toString());
                }
            }
            case "sag" -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || args.length < 3) {
                    say(sender, "sag braucht spieler online und text");
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                p.chat(text);
                say(sender, "gesagt spieler=" + name + " text=" + text);
            }
            default -> say(sender, "unbekannt: " + args[0]);
        }
        return true;
    }

    private void apply(Player p) {
        PermissionAttachment old = attachments.remove(key(p.getName()));
        if (old != null) {
            p.removeAttachment(old);
        }
        Set<String> s = granted.get(key(p.getName()));
        if (s == null || s.isEmpty()) {
            return;
        }
        PermissionAttachment a = p.addAttachment(this);
        for (String perm : s) {
            a.setPermission(perm, true);
        }
        attachments.put(key(p.getName()), a);
        getLogger().info("rechteprobe | angehängt | spieler=" + p.getName() + " rechte=" + s);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private void say(CommandSender sender, String text) {
        getLogger().info("rechteprobe | " + text);
        sender.sendMessage(text);
    }
}
