package com.blackn0va.baumeister;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /bm einrichten: ein frischer Server in vier Schritten (Wunsch 2026-11-15: „ich will auch einen frischen server
 * einrichten können mit dem baumeister, mit diesen portal hallen burgen, riesen große lobys“). Lobby bauen, Weltspawn
 * auf den Platz der Lobby setzen, Portale verbinden, Burgen und Hallen dazu – alles per Klick im Chat.
 *
 * Warum ein Ablauf und kein „mach alles“-Knopf: Wo die Lobby steht, wohin ihre Portale führen und welche Welten es
 * gibt, entscheidet der Admin. Ein Knopf, der das rät, baut im Zweifel eine 81 × 81 große Halle an die falsche Stelle.
 *
 * Weltspawn mit respawn_radius 0: Im Überleben landen neue Spieler sonst irgendwo bis 10 Blöcke um den Spawn (Vorgabe
 * von Minecraft) – auf dem Dach, im Fensterband oder neben der Lobby statt auf dem Platz.
 *
 * Threads: Hauptthread. Fehler: Meldungen an den Admin; nichts wird ohne seinen Klick geändert.
 */
final class SetupGuide {

    /** Ein Schritt: Text und Knöpfe in einer Zeile. Ohne Server baubar, damit der Test jeden Knopf prüfen kann. */
    record Step(String text, List<Texts.Choice> buttons) {
    }

    private SetupGuide() {
    }

    static List<Step> steps(String label) {
        String bm = "/" + label;
        return List.of(
                new Step(" ← 1. Lobby vor dir bauen (Glaskuppel, Platz, Portalrahmen)", List.of(
                        new Texts.Choice("Klein", bm + " bau eine kleine Lobby", "33 × 33, 3 Portalrahmen"),
                        new Texts.Choice("Normal", bm + " bau eine Lobby", "49 × 49, 5 Portalrahmen"),
                        new Texts.Choice("Groß", bm + " bau eine große Lobby", "65 × 65, 7 Portalrahmen"),
                        new Texts.Choice("Riesig", bm + " bau eine riesige Lobby", "81 × 81, 9 Portalrahmen"))),
                new Step(" ← 2. auf dem Platz in der Mitte stehen und klicken", List.of(
                        new Texts.Choice("Weltspawn hierher", bm + " einrichten spawn",
                                "Neue Spieler und alle ohne Bett landen genau hier"))),
                new Step(" ← 3. in einen leeren Rahmen stellen: " + bm + " portal <Ziel>", List.of(
                        new Texts.Choice("Ziele und Portale", bm + " portal liste",
                                "Verbundene Portale; Ziele sind spawn, jede Welt und jeder Warp"))),
                new Step(" ← 4. mehr bauen", List.of(
                        new Texts.Choice("Portalhalle", bm + " bau eine Portalhalle", "Halle mit 7 Portalrahmen"),
                        new Texts.Choice("Burg", bm + " bau eine Burg", "Burg mit Türmen, Wehrgang und Tor"),
                        new Texts.Choice("Große Burg", bm + " bau eine große Burg", "größere Burg"),
                        new Texts.Choice("Stadtmauer", bm + " bau eine Stadtmauer", "Mauer mit Zinnen"))));
    }

    /** /bm einrichten [spawn]. Das Recht (baumeister.admin) prüft BaumeisterCommand vorher. */
    static void run(BaumeisterPlugin plugin, CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("spawn")) {
            spawnCommand(sender, label, args);
            return;
        }
        Texts.info(sender, "Server einrichten – Schritt für Schritt:");
        for (Step s : steps(label)) {
            Texts.choices(sender, s.buttons(), s.text());
        }
        World main = plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().getFirst();
        int portals = plugin.portals() == null ? 0 : plugin.portals().count();
        int warps = plugin.warps() == null ? 0 : plugin.warps().names().size();
        if (main != null) {
            Location sp = main.getSpawnLocation();
            Texts.info(sender, "Stand: Weltspawn von " + main.getName() + " bei " + sp.getBlockX() + " "
                    + sp.getBlockY() + " " + sp.getBlockZ() + " · " + portals + " Portal(e) · " + warps
                    + " Warp(s). Warps setzt du mit /setwarp <Name> dort, wohin ein Portal führen soll.");
        }
    }

    /** Im Spiel: wo er steht. Konsole: /bm einrichten spawn &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;. */
    private static void spawnCommand(CommandSender sender, String label, String[] args) {
        if (sender instanceof Player p) {
            setSpawn(sender, p.getLocation());
            return;
        }
        if (args.length < 6) {
            Texts.info(sender, "Von der Konsole: /" + label + " einrichten spawn <welt> <x> <y> <z>");
            return;
        }
        World w = Bukkit.getWorld(args[2]);
        if (w == null) {
            Texts.info(sender, "Welt „" + Texts.clean(args[2], 32) + "“ gibt es nicht.");
            return;
        }
        try {
            setSpawn(sender, new Location(w, Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                    Integer.parseInt(args[5])));
        } catch (NumberFormatException e) {
            Texts.info(sender, "x, y und z müssen ganze Zahlen sein.");
        }
    }

    private static void setSpawn(CommandSender to, Location at) {
        // Blockmitte und Blick auf die nächste Himmelsrichtung: Neue Spieler stehen mittig und schauen geradeaus.
        float yaw = Math.round(at.getYaw() / 90f) * 90f;
        Location spawn = new Location(at.getWorld(), at.getBlockX() + 0.5, at.getY(), at.getBlockZ() + 0.5, yaw, 0f);
        World w = at.getWorld();
        boolean set = w.setSpawnLocation(spawn);
        GameRule<Integer> rule = respawnRadius();
        boolean radius = rule != null && w.setGameRule(rule, 0);
        if (!set) {
            Texts.info(to, "Der Server hat den Weltspawn nicht übernommen.");
            return;
        }
        Texts.info(to, "Weltspawn von " + w.getName() + " steht jetzt hier (" + at.getBlockX() + " " + at.getBlockY()
                + " " + at.getBlockZ() + ", Blick nach " + direction(yaw) + "). Neue Spieler und alle ohne Bett landen "
                + (radius ? "genau hier." : "hier – respawn_radius ließ sich nicht auf 0 setzen, sie landen bis 10 "
                        + "Blöcke daneben (/gamerule respawn_radius 0 von Hand)."));
        java.util.logging.Logger.getLogger("Baumeister").info(() -> "einrichten | weltspawn | welt=" + w.getName()
                + " x=" + at.getBlockX() + " y=" + at.getBlockY() + " z=" + at.getBlockZ() + " radius_null=" + radius);
    }

    /**
     * Die Spielregel per Reflexion – kein Feld- und kein Methodenzugriff auf GameRule im Bytecode. Gemessen 2026-11-15
     * auf Paper 26.2 mit dem gegen Spigot 1.21.11 übersetzten Jar: GameRule.RESPAWN_RADIUS warf NoSuchFieldError (Paper
     * nennt das Feld SPAWN_RADIUS), GameRule.values() dann IncompatibleClassChangeError – GameRule ist in der
     * Spigot-API ein Interface, in der Paper-API eine abstrakte Klasse, also bricht jeder direkte Aufruf eine der beiden
     * Fassungen. Reflexion kennt den Unterschied nicht; World#setGameRule ist in beiden eine Interface-Methode mit
     * gleicher Signatur. Gesucht wird nach dem Namen: respawn_radius (seit 1.21.11), davor spawnRadius.
     *
     * Bewusst fail-open: Ohne die Regel steht der Weltspawn trotzdem, nur landen neue Spieler bis 10 Blöcke daneben –
     * das sagt die Meldung. null = nicht gefunden.
     */
    @SuppressWarnings("unchecked")
    static GameRule<Integer> respawnRadius() {
        try {
            Class<?> type = Class.forName("org.bukkit.GameRule");
            Object all = type.getMethod("values").invoke(null);
            java.lang.reflect.Method name = type.getMethod("getName");
            for (int i = 0; i < java.lang.reflect.Array.getLength(all); i++) {
                Object rule = java.lang.reflect.Array.get(all, i);
                String n = String.valueOf(name.invoke(rule)).toLowerCase(Locale.ROOT);
                n = n.substring(n.indexOf(':') + 1);
                if (n.equals("respawn_radius") || n.equals("spawnradius")) {
                    return (GameRule<Integer>) rule;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            java.util.logging.Logger.getLogger("Baumeister").log(java.util.logging.Level.WARNING,
                    "einrichten | spielregel_nicht_lesbar | respawn_radius bleibt, wie sie ist", e);
        }
        return null;
    }

    static String direction(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 0 -> "Süden";
            case 1 -> "Westen";
            case 2 -> "Norden";
            default -> "Osten";
        };
    }

    /** Nur für Log und Tests: „bau …“-Aufträge der Knöpfe, ohne „/bm “. */
    static List<String> buildOrders(String label) {
        String prefix = ("/" + label + " bau ").toLowerCase(Locale.ROOT);
        return steps(label).stream().flatMap(s -> s.buttons().stream()).map(Texts.Choice::command)
                .filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix)).map(c -> c.substring(prefix.length()))
                .toList();
    }
}
