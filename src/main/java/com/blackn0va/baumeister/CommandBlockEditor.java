package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.Snbt;
import com.blackn0va.baumeister.plan.Tier;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.CommandBlock;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * /bm befehlsblock [befehl] – den Befehlsblock, auf den der Spieler schaut, zeigen oder seinen Befehl setzen (Plan B8,
 * Wunsch 2026-11-08).
 *
 * Warum ein eigener Befehl: Das Bearbeitungsfenster öffnet Minecraft nur im Kreativmodus mit OP-Stufe 2 oder dem Recht
 * minecraft.commandblock (gemessen im Bytecode von Paper 26.2). Dieser Befehl geht in jedem Spielmodus, braucht
 * baumeister.admin.befehlsblock (Kind von baumeister.admin) und die Baurechte an der Stelle (RegionCheck, wie ein Bau).
 * Gesetzt wird wie beim Bauen über „data merge“ mit der neu geschriebenen SNBT-Fassung (Snbt.write) – ein Befehl mit
 * Anführungszeichen kann so nie aus seiner Zeichenkette ausbrechen. Relative Koordinaten (~, ^) sind hier erlaubt:
 * Der Block wird nicht gedreht, er steht schon.
 *
 * Threads: nur Hauptthread. Fehler: als Satz an den Spieler, nie geworfen.
 */
final class CommandBlockEditor {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Wie weit der Blick reicht – etwas mehr als die Kreativ-Reichweite. */
    static final int REACH = 8;
    /** Hilfsziel auf der Haupt-Anzeigetafel, nur für die Dauer einer Abfrage (auto). */
    private static final String PROBE = "bm_befehl";

    private CommandBlockEditor() {
    }

    static void run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel – schau dabei auf den Befehlsblock.");
            return;
        }
        if (!p.hasPermission(Tier.COMMANDS.permission())) {
            Texts.info(p, "Dafür fehlt dir " + Tier.COMMANDS.permission() + ".");
            return;
        }
        Block b = p.getTargetBlockExact(REACH);
        if (b == null || !(b.getState() instanceof CommandBlock cb)) {
            Texts.info(p, "Schau auf einen Befehlsblock (höchstens " + REACH + " Blöcke weit).");
            return;
        }
        // Baurechte an der Stelle wie bei einem Bau: Ein Befehlsblock in einem fremden Plot bleibt, wie er ist.
        if (!RegionCheck.allowed(p, b, b.getBlockData())) {
            Texts.info(p, "Hier darfst du nicht bauen – den Befehlsblock lasse ich, wie er ist.");
            return;
        }
        if (args.length < 2) {
            show(p, b, cb);
            return;
        }
        String command = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String problem = problem(command);
        if (problem != null) {
            Texts.info(p, problem);
            return;
        }
        BlockDataWriter writer = new BlockDataWriter();
        // Derselbe Helfer wie mergeCommand (Test): Vorher baute run den SNBT-Text selbst, und ein roher Befehl hier
        // wäre durch alle Tests gegangen (Gegenprobe 2026-11-08).
        writer.apply(b, commandData(command), new BlockDataWriter.Expectation(command, null, -1));
        boolean ok = writer.applied() == 1;
        LOG.info(() -> String.format("befehlsblock | %s | spieler=%s welt=%s x=%d y=%d z=%d zeichen=%d befehl=%s",
                ok ? "gesetzt" : "abgelehnt", p.getName(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                command.length(), Texts.clean(command, 60)));
        Texts.info(p, ok ? "Befehl gesetzt: " + Texts.clean(command, 120)
                : "Der Server hat den Befehl nicht übernommen – Einzelheiten im Server-Log.");
    }

    /** Was der Befehlsblock ist und tut; der Befehl zum Anklicken (schlägt /bm befehlsblock <befehl> vor). */
    private static void show(Player p, Block b, CommandBlock cb) {
        String command = cb.getCommand() == null ? "" : cb.getCommand();
        boolean conditional = b.getBlockData() instanceof org.bukkit.block.data.type.CommandBlock d
                && d.isConditional();
        Boolean auto = auto(b);
        Texts.info(p, typeLabel(b.getType().getKey().getKey()) + ", " + (conditional ? "bedingt" : "unbedingt") + ", "
                + (auto == null ? "Aktivierung nicht lesbar" : auto ? "immer aktiv" : "braucht Redstone") + " (bei "
                + b.getX() + " " + b.getY() + " " + b.getZ() + ")");
        TextComponent line = new TextComponent("[Baumeister] Befehl: ");
        line.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        TextComponent cmd = new TextComponent(command.isEmpty() ? "(leer)" : Texts.clean(command, 240));
        cmd.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        cmd.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/bm befehlsblock " + command));
        cmd.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Klicken zum Bearbeiten")));
        p.spigot().sendMessage(line, cmd);
    }

    /**
     * Ist der Befehlsblock „immer aktiv“ (auto)? Die Spigot-API kennt das nicht. Abgefragt über die Anzeigetafel:
     * „execute store success score … if data block … {auto:1b}“ schreibt 1 oder 0, das liest die API. Das Hilfsziel
     * lebt nur für diese Abfrage. null = nicht lesbar.
     */
    static Boolean auto(Block b) {
        Scoreboard main = Bukkit.getScoreboardManager() == null ? null
                : Bukkit.getScoreboardManager().getMainScoreboard();
        if (main == null) {
            return null;
        }
        Objective o = main.getObjective(PROBE);
        boolean created = o == null;
        if (created) {
            o = main.registerNewObjective(PROBE, Criteria.DUMMY, "Baumeister");
        }
        try {
            String holder = "#auto";
            o.getScore(holder).setScore(-1);
            Bukkit.dispatchCommand(BlockDataWriter.quietSender(), "minecraft:execute in " + b.getWorld().getKey()
                    + " store success score " + holder + " " + PROBE + " if data block " + b.getX() + " " + b.getY()
                    + " " + b.getZ() + " {auto:1b}");
            Score s = o.getScore(holder);
            return !s.isScoreSet() || s.getScore() < 0 ? null : s.getScore() == 1;
        } finally {
            if (created) {
                o.unregister();
            }
        }
    }

    /** null = Befehl taugt; sonst der Grund. Nur Länge und Steuerzeichen – ~ und ^ sind hier erlaubt (Klassenkommentar). */
    static String problem(String command) {
        if (command.length() > com.blackn0va.baumeister.plan.CommandText.MAX_LENGTH) {
            return "Der Befehl ist zu lang (" + command.length() + " Zeichen, höchstens "
                    + com.blackn0va.baumeister.plan.CommandText.MAX_LENGTH + ").";
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "Der Befehl enthält Steuerzeichen.";
            }
        }
        return null;
    }

    static String typeLabel(String id) {
        return switch (id) {
            case "repeating_command_block" -> "Wiederhol-Befehlsblock";
            case "chain_command_block" -> "Ketten-Befehlsblock";
            default -> "Impuls-Befehlsblock";
        };
    }

    /** Der data-merge-Befehl, den run schickt – ohne Server prüfbar (Ausbruch aus der Zeichenkette). */
    static String mergeCommand(String worldKey, int x, int y, int z, String command) {
        return BlockDataWriter.command(worldKey, x, y, z, commandData(command));
    }

    /** Blockdaten mit dem Befehl, neu geschrieben (Snbt.write maskiert " und \\) – für run und mergeCommand. */
    static String commandData(String command) {
        return Snbt.write(new Nbt.Compound(Map.of("Command", new Nbt.Str(command))));
    }
}
