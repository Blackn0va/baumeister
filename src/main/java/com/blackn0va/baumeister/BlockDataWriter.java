package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Nbt;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

/**
 * Schreibt Blockdaten nach dem Setzen (Plan B4): „minecraft:data merge block“ im selben Tick, nur mit der neu
 * geschriebenen SNBT-Fassung (Snbt.write) und nur, wenn die Zelle wirklich gesetzt wurde und der Blocktyp noch stimmt
 * (prüft der Aufrufer, BuildJob). Danach wird über die Bukkit-API nachgesehen, wo das geht – Befehl des
 * Befehlsblocks, Schildzeilen, Zahl der Gegenstände –, und eine Abweichung geloggt.
 *
 * Absender: Unter Paper ein stiller Absender (Bukkit.createCommandSender, per Reflexion – das Plugin kompiliert gegen
 * die Spigot-API); unter Spigot die Konsole. Die Konsole meldet jedes „data merge“ allen Ops im Spiel als
 * „[Server: …]“ (Spielregel sendCommandFeedback) – auf Spigot also eine Zeile je Block mit Daten.
 *
 * Threads: nur Hauptthread. Fehler: abgelehnte Befehle werden gezählt und geloggt, nie geworfen.
 */
final class BlockDataWriter {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Was nach dem Schreiben über die Bukkit-API sichtbar sein muss; null bzw. −1 = nicht prüfbar. */
    record Expectation(String command, List<String> signLines, int itemStacks) {

        static final Expectation NONE = new Expectation(null, null, -1);

        /** Aus den neu geschriebenen Daten (Platzhalter schon in Weltkoordinaten). Ohne Server testbar. */
        static Expectation of(String blockId, Nbt.Compound nbt) {
            if (nbt == null) {
                return NONE;
            }
            String command = blockId.endsWith("command_block") && nbt.get("Command") instanceof Nbt.Str s
                    ? s.value() : null;
            List<String> lines = null;
            if (blockId.endsWith("_sign") && nbt.get("front_text") instanceof Nbt.Compound front
                    && front.get("messages") instanceof Nbt.ListTag messages && messages.items().size() == 4) {
                List<String> plain = new ArrayList<>();
                for (Nbt m : messages.items()) {
                    if (m instanceof Nbt.Str s && !s.value().trim().startsWith("{") && !s.value().trim().startsWith("[")) {
                        plain.add(s.value());
                    }
                }
                lines = plain.size() == 4 ? List.copyOf(plain) : null;
            }
            int items = nbt.get("Items") instanceof Nbt.ListTag l ? l.items().size() : -1;
            return new Expectation(command, lines, items);
        }
    }

    private final CommandSender sender;
    private final boolean silent;
    private int applied;
    private int failed;

    BlockDataWriter() {
        CommandSender quiet = paperSilentSender();
        this.silent = quiet != null;
        this.sender = quiet != null ? quiet : Bukkit.getConsoleSender();
    }

    /** Absender für Hilfsbefehle (Paper still, sonst Konsole) – auch für /bm befehlsblock. */
    static CommandSender quietSender() {
        CommandSender quiet = paperSilentSender();
        return quiet != null ? quiet : Bukkit.getConsoleSender();
    }

    /** Daten an b schreiben (Welt über execute in, sonst landete es in der Hauptwelt) und nachsehen. */
    void apply(Block b, String snbt, Expectation expect) {
        String cmd = command(b.getWorld().getKey().toString(), b.getX(), b.getY(), b.getZ(), snbt);
        try {
            Bukkit.dispatchCommand(sender, cmd);
        } catch (CommandException e) {
            failed++;
            LOG.log(Level.WARNING, "bau | daten_fehler | x=" + b.getX() + " y=" + b.getY() + " z=" + b.getZ(), e);
            return;
        }
        String mismatch = mismatch(b.getState(), expect);
        if (mismatch != null) {
            failed++;
            LOG.warning(() -> "bau | daten_abweichung | x=" + b.getX() + " y=" + b.getY() + " z=" + b.getZ()
                    + " block=" + b.getType().getKey().getKey() + " grund=" + mismatch + " still=" + silent);
            return;
        }
        applied++;
    }

    /**
     * Der Befehl für eine Zelle. snbt ist immer die neu geschriebene Fassung (Snbt.write): eine einzige Verbindung,
     * Zeichenketten in Anführungszeichen mit maskiertem Anführungszeichen und Rückstrich – danach kann im Befehl nichts
     * mehr folgen.
     */
    static String command(String worldKey, int x, int y, int z, String snbt) {
        return "minecraft:execute in " + worldKey + " run data merge block " + x + " " + y + " " + z + " " + snbt;
    }

    int applied() {
        return applied;
    }

    int failed() {
        return failed;
    }

    /** null = passt (oder nicht prüfbar). */
    static String mismatch(BlockState state, Expectation e) {
        if (e.command() != null) {
            if (!(state instanceof org.bukkit.block.CommandBlock cb)) {
                return "kein Befehlsblock";
            }
            if (!e.command().equals(cb.getCommand())) {
                return "Befehl weicht ab";
            }
        }
        if (e.signLines() != null && state instanceof Sign sign) {
            SignSide front = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) {
                if (!e.signLines().get(i).equals(front.getLine(i))) {
                    return "Schildzeile " + (i + 1) + " weicht ab";
                }
            }
        }
        if (e.itemStacks() >= 0 && state instanceof Container c) {
            int n = 0;
            for (ItemStack it : c.getSnapshotInventory().getContents()) {
                if (it != null && !it.getType().isAir()) {
                    n++;
                }
            }
            if (n != e.itemStacks()) {
                return n + " statt " + e.itemStacks() + " Gegenstände";
            }
        }
        return null;
    }

    /**
     * Paper (dev01, 26.2): Bukkit.createCommandSender(Consumer) – Rückmeldungen gehen an den Verbraucher statt an Ops und
     * Konsole. Spigot hat die Methode nicht → null.
     */
    private static CommandSender paperSilentSender() {
        try {
            Method m = Bukkit.class.getMethod("createCommandSender", Consumer.class);
            Consumer<Object> sink = msg -> LOG.finest(() -> "bau | daten_rückmeldung | " + msg);
            return (CommandSender) m.invoke(null, sink);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "bau | stiller_absender_fehlt | Spigot – die Konsole schreibt die Daten", e);
            return null;
        }
    }
}
