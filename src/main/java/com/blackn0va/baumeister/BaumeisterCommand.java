package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

/** /bm – alles, was nicht über den Chat läuft. */
final class BaumeisterCommand implements CommandExecutor, TabCompleter {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /**
     * Ein Unterbefehl, wie onCommand ihn annimmt: Hauptname, Rechtestufe, Aliase. travel = braucht baumeister.reisen
     * (Reisepunkte); der Dreierkonstruktor hält die bestehenden Zeilen unverändert.
     */
    /**
     * either: reine Anzeige ohne Eingriff in die Welt (Minikarte, 2026-10-31) – für baumeister.bauen ODER
     * baumeister.reisen. Vorher gab es nur „Bauen“ oder „Reisen“; die Karte nur Reisenden zu geben, hätte sie jedem
     * Spieler genommen, der nur bauen darf.
     */
    private record Sub(String name, boolean adminOnly, boolean travel, boolean either, List<String> aliases) {

        Sub(String name, boolean adminOnly, List<String> aliases) {
            this(name, adminOnly, false, false, aliases);
        }

        Sub(String name, boolean adminOnly, boolean travel, List<String> aliases) {
            this(name, adminOnly, travel, false, aliases);
        }

        /** Reise-Befehle hängen nur an baumeister.reisen, either an einem der beiden, alle übrigen an baumeister.bauen. */
        boolean visible(boolean admin, boolean mayBuild, boolean mayTravel) {
            return admin || (!adminOnly && (either ? mayBuild || mayTravel : travel ? mayTravel : mayBuild));
        }
    }

    /**
     * Vorher eine flache Namensliste: Spieler bekamen bauteil, spawn, stadt … vorgeschlagen und liefen dann in
     * „Dafür fehlt dir baumeister.admin.“, Aliase wie dorf oder undo kannte die Vervollständigung gar nicht. Die
     * Tabelle spiegelt jetzt die case-Zweige und requireAdmin-Aufrufe in onCommand. Wer dort einen Befehl ergänzt oder
     * seine Rechte ändert, muss diese Zeile mitziehen – sonst zeigt die Vervollständigung wieder etwas anderes, als der
     * Befehl tut. Die eigentliche Grenze bleibt requireAdmin; das hier blendet nur aus (ASVS V4.1.1).
     */
    private static final List<Sub> SUBS = List.of(
            new Sub("bau", false, List.of("baue", "build")),
            new Sub("stop", false, List.of()),
            new Sub("rückgängig", false, List.of("rueckgaengig", "undo")), // umlaut-ok: Alias ohne Umlaut-Tastatur
            new Sub("status", false, List.of()),
            new Sub("bildschirm", false, List.of("monitor", "fernseher")),
            new Sub("bauteile", false, List.of()),
            new Sub("schematics", false, List.of("schematic", "sammlung")),
            // Kistenfenster der Sammlung (2026-11-08): baut über /bm bau, also mit dessen Rechten.
            new Sub("katalog", false, List.of()),
            // Sprache vom Server an/aus, Stimme wählen, Probe (2026-11-08): schreibt config.yml, nur Admins.
            new Sub("sprache", true, List.of("stimme")),
            new Sub("fortschritt", false, List.of()),
            new Sub("komm", false, List.of()),
            new Sub("vorschau", false, List.of("preview")),
            new Sub("warteschlange", false, List.of("queue")),
            new Sub("orte", false, true, List.of()),
            // Minikarte (2026-10-31): Anzeige ohne Eingriff in die Welt – mit Bauen- oder Reisen-Recht nutzbar.
            new Sub("karte", false, false, true, List.of("map", "minimap")),
            new Sub("ort", false, true, List.of()),
            // Begleiter (2026-11-04): hängt an baumeister.begleiter (Vorgabe: jeder), geprüft in onCommand. In der
            // Vervollständigung wie die Karte für Bauende und Reisende sichtbar.
            new Sub("begleiter", false, false, true, List.of("companion")),
            new Sub("bauteil", true, List.of()),
            new Sub("spawn", true, List.of()),
            new Sub("entfernen", true, List.of("remove")),
            new Sub("bauenbei", true, List.of()),
            // Portale (2026-11-15): entzündet einen leeren Obsidianrahmen und führt zu Warp, Welt oder Spawn.
            new Sub("portal", true, List.of()),
            new Sub("maschine", true, List.of()),
            // Befehlsblock zeigen/setzen (2026-11-08): braucht baumeister.admin.befehlsblock, ein Kind von
            // baumeister.admin – in der Vervollständigung deshalb wie ein Admin-Befehl. Die Grenze prüft
            // CommandBlockEditor.run selbst.
            new Sub("befehlsblock", true, List.of("commandblock")),
            // Antwort auf die Befehlsliste eines Plans (Plan B5, 2026-11-08): nur wer sie bekommen hat, kann sie
            // einlösen (BuildService.answerCommands, an den Auftraggeber gebunden) – das sind Admins und die Konsole.
            new Sub("befehle", true, List.of("commands")),
            // Selbsttest „jeder Block“ (Plan B9, 2026-11-08): nur Admins und Konsole, überschreibt die Testzelle.
            new Sub("selbsttest", true, List.of("selftest")),
            new Sub("logik", true, List.of("logic", "schaltung")),
            new Sub("logikbei", true, List.of()),
            new Sub("neuladen", true, List.of("reload")),
            new Sub("leistung", true, List.of("perf", "tps")),
            // Lag-Wächter (2026-11-14): Leerlauf-Aktivität eines Bereichs messen – nur Admins und Konsole.
            new Sub("lagprobe", true, List.of()),
            // Bauwerke des Spiels als Vorlagen erzeugen (2026-11-15) – nur Admins und Konsole.
            new Sub("spielbauwerk", true, List.of()),
            new Sub("stadt", true, List.of("dorf", "city")),
            new Sub("stadtwelt", true, List.of()),
            new Sub("gelaende", true, List.of("gelände", "terrain")),
            new Sub("erde", true, List.of("earth")),
            new Sub("reise", true, List.of("travel")),
            new Sub("wo", true, List.of("where")),
            new Sub("weltbau", true, List.of("erdbau")),
            new Sub("pause", true, List.of()),
            new Sub("weiter", true, List.of()),
            new Sub("tempo", true, List.of()),
            // Seit 2026-10-30 auch für Spieler: Helfer für den eigenen Bau (HelperWish). Den Stadtbau steuern weiter
            // nur Admins – das prüft der Befehl selbst.
            new Sub("helfer", false, List.of()),
            new Sub("kartenbild", true, List.of()));

    private static final String TRAVEL = "baumeister.reisen";
    private static final String BUILD = "baumeister.bauen";
    private static final List<String> PLACE_VERBS = List.of("merken", "löschen");
    private static final List<String> DIRECTIONS = List.of("n", "o", "s", "w");
    // Stützwerte innerhalb der Grenzen von CityService.control (tempo 50–50 000, helfer 0–16).
    private static final List<String> TEMPO_VALUES = List.of("500", "1000", "2000", "5000");
    private static final List<String> HELPER_VALUES = List.of("0", "2", "4", "8", "auto");
    private static final List<String> CITY_VERBS = List.of("los", "abbrechen");
    /** /bm stadt los echt – an der Stelle wie in der Erdwelt (CityPlacement). */
    private static final List<String> REAL_SPOT = List.of("echt", "richtig", "geo");
    private static final List<String> WORLD_BUILD_VERBS = List.of("auto", "rückgängig", "status", "stop");
    private static final List<String> ON_OFF = List.of("an", "aus");
    private static final List<String> MAP_VERBS = List.of("zoom", "drehen", "punkt", "punkte", "ziel", "weg", "status");
    private static final List<String> MAP_ZOOMS = List.of("0,5", "1", "2", "4");
    private static final List<String> RELOAD_VERBS = List.of("erzwingen");
    private static final List<String> PREVIEW_WORDS = List.of("ja", "nein");
    private static final List<String> COMMAND_ANSWERS = List.of("ja", "ohne", "nein");
    private static final List<String> SELF_TEST_PARTS = List.of("blöcke", "daten", "stabil");
    private static final List<String> VOICE_VERBS = List.of("status", "an", "aus", "stimme", "vera", "ton", "probe");
    private static final List<String> QUEUE_VERBS = List.of("abbrechen");
    private static final List<String> SCREEN_VERBS = List.of("animation", "uhr", "laufschrift", "zeig", "liste",
            "weg", "groß", "klein", "4x3", "bei");
    private static final List<String> COMPANION_VERBS = List.of("weg", "abenteuer", "charakter", "rechte", "recht");
    private static final List<String> ADVENTURE_VERBS = List.of("normal", "hardcore", "stand", "aus", "team",
            "annehmen");
    static final String COMPANION = "baumeister.begleiter";
    private static final List<String> BUILD_EXAMPLES = List.of("kleine Kapelle aus Stein", "Holzhaus mit Garten",
            "Turm mit Wendeltreppe");
    private static final List<String> PLACE_EXAMPLES = List.of("Hepberg", "Grand Canyon", "Mount Everest",
            "48.82,11.46");

    private final BaumeisterPlugin plugin;

    BaumeisterCommand(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        // Vorher hing /bm in plugin.yml allein an baumeister.bauen – ein Spieler mit nur baumeister.reisen bekam
        // schon von Bukkit „keine Berechtigung“ und erreichte orte/ort nie. Jetzt lässt plugin.yml beide Rechte durch,
        // und das Bauen-Recht wird HIER geprüft: fail-closed für alles außer den Reise-Befehlen, auch für
        // Unterbefehle, die in SUBS (noch) fehlen. Wer diese Zeile entfernt, ohne plugin.yml zurückzudrehen, gibt
        // reinen Reisenden bau, stop, rückgängig … frei (ASVS V4.1.3, OWASP A01).
        if (needsBuildRight(sub) && !sender.hasPermission(BUILD)) {
            if (resolve(sub) == null) {
                help(sender, label);
            } else {
                Texts.info(sender, "Dafür fehlt dir " + BUILD + ".");
            }
            return true;
        }
        // Seit 2026-11-04 lässt plugin.yml auch baumeister.begleiter allein durch (jeder darf seinen Begleiter rufen).
        // Reise- und Kartenbefehle verließen sich bisher darauf, dass Bukkit nur Bauende und Reisende durchlässt –
        // deshalb hier ausdrücklich: Außer begleiter braucht alles baumeister.bauen oder baumeister.reisen. Wer diese
        // Prüfung entfernt, gibt jedem Spieler orte, ort und karte frei (ASVS V4.1.3, OWASP A01).
        if (!companionSub(sub) && !sender.hasPermission(BUILD) && !sender.hasPermission(TRAVEL)) {
            Texts.info(sender, "Dafür fehlt dir " + BUILD + " oder " + TRAVEL + ".");
            return true;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        switch (sub) {
            case "bau", "baue", "build" -> {
                if (!(sender instanceof Player p)) {
                    Texts.info(sender, "Von der Konsole: /" + label + " bauenbei <welt> <x> <y> <z> <n|o|s|w> <auftrag>");
                    return true;
                }
                plugin.service().requestFromPlayer(p, rest);
            }
            case "stop" -> {
                if (!plugin.city().control(sender, "stop", null)) {
                    plugin.service().stop(sender);
                }
            }
            case "stadt", "dorf", "city" -> city(sender, label, args);
            case "stadtwelt" -> cityWorld(sender);
            case "gelaende", "gelände", "terrain" -> terrain(sender, label, args);
            case "erde", "earth" -> {
                if (requireAdmin(sender)) {
                    plugin.earth().goHome(sender);
                }
            }
            case "reise", "travel" -> travel(sender, label, args);
            case "wo", "where" -> where(sender, label, args);
            case "weltbau", "erdbau" -> {
                if (requireAdmin(sender)) {
                    plugin.weltbau().command(sender, label, args);
                }
            }
            // Ohne Stadtbau gehören die Helfer zum normalen Bau; der prüft selbst, wessen Bau es ist (mayTouch).
            case "helfer" -> {
                if (!plugin.city().running()) {
                    plugin.service().helpers(sender, args.length > 1 ? args[1] : null);
                } else if (requireAdmin(sender)) {
                    plugin.city().control(sender, sub, args.length > 1 ? args[1] : null);
                }
            }
            case "pause", "weiter", "tempo", "fortschritt", "komm" -> {
                if (!sub.equals("fortschritt") && !sub.equals("komm") && !requireAdmin(sender)) {
                    return true;
                }
                if (!plugin.city().control(sender, sub, args.length > 1 ? args[1] : null)) {
                    Texts.info(sender, "Gerade läuft kein Stadtbau.");
                }
            }
            case "rückgängig", "rueckgaengig", "undo" -> { // umlaut-ok: Eingabe ohne Umlaut-Tastatur
                // Ein laufender Stadtbau legt seinen Rückgängig-Eintrag erst beim Ende ab – also erst stoppen.
                if (plugin.city().running() && sender.hasPermission("baumeister.admin")) {
                    plugin.city().control(sender, "stop", null);
                }
                plugin.service().undo(sender);
            }
            case "status" -> status(sender);
            case "bildschirm", "monitor", "fernseher" -> {
                if (plugin.screens() != null) {
                    plugin.screens().command(sender, rest);
                }
            }
            case "vorschau", "preview" -> plugin.service().comfort().previewCommand(sender, label, args);
            case "warteschlange", "queue" -> plugin.service().comfort().queueCommand(sender, args);
            case "bauteile" -> listModules(sender);
            case "schematics", "schematic", "sammlung" -> listSchematics(sender, label, args);
            case "katalog" -> {
                if (sender instanceof org.bukkit.entity.Player p && plugin.schematicMenu() != null) {
                    plugin.schematicMenu().open(p, rest);
                } else {
                    listSchematics(sender, label, args);
                }
            }
            case "sprache", "stimme" -> {
                if (requireAdmin(sender)) {
                    VoiceControl.run(plugin, sender, args);
                }
            }
            case "bauteil" -> saveModule(sender, label, args);
            case "spawn" -> spawn(sender, args);
            case "entfernen", "remove" -> {
                if (requireAdmin(sender)) {
                    plugin.npc().remove();
                    Texts.info(sender, "NPC entfernt.");
                }
            }
            case "bauenbei" -> buildAt(sender, label, args);
            case "portal" -> {
                if (requireAdmin(sender) && plugin.portals() != null) {
                    plugin.portals().command(sender, label, args);
                }
            }
            case "maschine" -> buildModuleAt(sender, label, args);
            case "befehlsblock", "commandblock" -> CommandBlockEditor.run(sender, args);
            case "befehle", "commands" -> plugin.service().answerCommands(sender, args);
            case "selbsttest", "selftest" -> selfTest(sender, args);
            case "logik", "logic", "schaltung" -> buildLogic(sender, label, args);
            case "logikbei" -> buildLogicAt(sender, label, args);
            case "neuladen", "reload" -> {
                if (requireAdmin(sender)) {
                    plugin.configReload().run(sender, args);
                }
            }
            case "spielbauwerk" -> {
                if (requireAdmin(sender)) {
                    plugin.gameStructures().command(sender, args);
                }
            }
            case "lagprobe" -> {
                if (requireAdmin(sender)) {
                    lagProbe(sender, label, args);
                }
            }
            case "leistung", "perf", "tps" -> {
                if (requireAdmin(sender)) {
                    PerformanceReport.send(plugin, sender, plugin.ticks(), plugin.configReload().restartPending());
                }
            }
            case "orte" -> listPlaces(sender, args);
            case "karte", "map", "minimap" -> {
                if (plugin.minimap() != null) {
                    plugin.minimap().command(sender, args);
                }
            }
            case "kartenbild" -> {
                if (!sender.hasPermission("baumeister.admin")) {
                    Texts.info(sender, "Dafür fehlt dir baumeister.admin.");
                } else if (plugin.minimap() != null) {
                    plugin.minimap().imageCommand(sender, args);
                }
            }
            case "ort" -> place(sender, label, args);
            case "begleiter", "companion" -> companion(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    static boolean companionSub(String typed) {
        Sub s = resolve(typed);
        return s != null && s.name().equals("begleiter");
    }

    private void companion(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            Texts.info(sender, "Den Begleiter rufen nur Spieler.");
            return;
        }
        if (!p.hasPermission(COMPANION)) {
            Texts.info(p, "Dafür fehlt dir " + COMPANION + ".");
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("recht") || args[1].equalsIgnoreCase("rechte"))) {
            rights(p, args);
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("charakter")) {
            com.blackn0va.baumeister.companion.CompanionCharacter.Kind k =
                    com.blackn0va.baumeister.companion.CompanionCharacter.Kind.parse(args[2]);
            if (k == null) {
                Texts.info(p, "Charakter: kumpel, frech oder anpassen.");
            } else {
                plugin.companion().setCharacter(p, k);
                if (plugin.help() != null) {
                    plugin.help().openChapter(p, com.blackn0va.baumeister.help.HelpBook.CHARACTER_CHAPTER);
                }
            }
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("weg")) {
            plugin.companion().dismiss(p);
        } else if (args.length >= 4 && args[1].equalsIgnoreCase("abenteuer") && args[2].equalsIgnoreCase("team")) {
            plugin.companion().adventureInvite(p, args[3]);
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("abenteuer")) {
            plugin.companion().adventureCommand(p, args.length >= 3 ? args[2].toLowerCase(java.util.Locale.ROOT)
                    : "normal");
        } else {
            plugin.companion().summon(p);
        }
    }

    /**
     * Rechte im Admin-Buch (Wunsch 2026-11-07):
     *   /bm begleiter rechte [spieler]                       – Buch: Stufen und Spielerliste bzw. Seite eines Spielers
     *   /bm begleiter recht &lt;fähigkeit&gt; &lt;alle|ops|aus&gt;           – Stufe einer Fähigkeit Veras
     *   /bm begleiter recht &lt;recht&gt; &lt;erlauben|verbieten|standard&gt; &lt;spieler&gt; – Eintrag für einen Spieler
     * Nur mit baumeister.admin (fail-closed, ASVS V4.1.3). baumeister.admin selbst vergibt oder entzieht nur ein Op, und
     * nie sich selbst – sonst sperrte sich ein Admin mit einem Klick aus, oder ein Nicht-Op machte andere zu Admins.
     */
    private void rights(org.bukkit.entity.Player p, String[] args) {
        if (!p.hasPermission("baumeister.admin")) {
            Texts.info(p, "Rechte verwaltet nur ein Admin (baumeister.admin).");
            return;
        }
        CompanionRights r = plugin.companion().rights();
        if (args[1].equalsIgnoreCase("rechte") || args.length < 4) {
            if (args.length >= 3 && plugin.help() != null) {
                org.bukkit.OfflinePlayer t = known(args[2]);
                if (t == null) {
                    Texts.info(p, "Den Spieler „" + Texts.clean(args[2], 32) + "“ kenne ich nicht.");
                    return;
                }
                plugin.help().openPlayerRights(p, t);
            } else if (plugin.help() != null) {
                plugin.help().openChapter(p, com.blackn0va.baumeister.help.HelpBook.RIGHTS_CHAPTER);
            }
            return;
        }
        com.blackn0va.baumeister.companion.VeraRights.Ability a =
                com.blackn0va.baumeister.companion.VeraRights.Ability.parse(args[2]);
        if (a == null) {
            Texts.info(p, "Unbekanntes Recht „" + Texts.clean(args[2], 32) + "“.");
            return;
        }
        String verb = args[3].toLowerCase(Locale.ROOT);
        if (args.length >= 5) {
            if (!verb.matches("erlauben|ja|an|verbieten|nein|aus|standard|zurück|weg")) {
                Texts.info(p, "Für einen Spieler: erlauben, verbieten oder standard.");
                return;
            }
            Boolean allow = verb.matches("erlauben|ja|an") ? Boolean.TRUE
                    : verb.matches("verbieten|nein|aus") ? Boolean.FALSE : null;
            org.bukkit.OfflinePlayer t = known(args[4]);
            if (t == null) {
                Texts.info(p, "Den Spieler „" + Texts.clean(args[4], 32) + "“ kenne ich nicht.");
                return;
            }
            if (a == com.blackn0va.baumeister.companion.VeraRights.Ability.ADMIN
                    && (!p.isOp() || t.getUniqueId().equals(p.getUniqueId()))) {
                Texts.info(p, t.getUniqueId().equals(p.getUniqueId()) ? "Dein eigenes Admin-Recht änderst du hier nicht."
                        : "Admin vergibt oder entzieht nur ein Op.");
                return;
            }
            String err = r.setPlayer(t.getUniqueId(), t.getName(), a, allow);
            if (err != null) {
                Texts.info(p, err);
                return;
            }
            org.bukkit.entity.Player online = t.getPlayer();
            if (online != null) {
                r.apply(online);
            }
            plugin.getLogger().info("begleiter | recht_spieler | admin=" + p.getName() + " spieler=" + t.getName()
                    + " recht=" + a.key() + " wert=" + (allow == null ? "standard" : allow));
            Texts.info(p, t.getName() + ": " + a.label() + " → " + (allow == null ? "Standard" : allow ? "erlaubt"
                    : "verboten") + ".");
            if (plugin.help() != null) {
                plugin.help().openPlayerRights(p, t);
            }
            return;
        }
        com.blackn0va.baumeister.companion.VeraRights.Level l =
                com.blackn0va.baumeister.companion.VeraRights.Level.parse(verb);
        if (l == null || !a.vera()) {
            Texts.info(p, a.vera() ? "Stufe: alle, ops oder aus." : a.label() + " hat keine Stufe – nur Einträge je "
                    + "Spieler (/bm begleiter recht " + a.key() + " verbieten <spieler>).");
            return;
        }
        String err = r.setLevel(a, l);
        if (err != null) {
            Texts.info(p, err);
            return;
        }
        plugin.getLogger().info("begleiter | recht_stufe | admin=" + p.getName() + " recht=" + a.key() + " stufe=" + l);
        Texts.info(p, a.label() + " → " + l.label() + ".");
        if (plugin.help() != null) {
            plugin.help().openChapter(p, com.blackn0va.baumeister.help.HelpBook.RIGHTS_CHAPTER);
        }
    }

    /** Spieler, der schon einmal hier war (online zuerst); sonst null – keine Abfrage bei Mojang. */
    private static org.bukkit.OfflinePlayer known(String name) {
        org.bukkit.entity.Player on = org.bukkit.Bukkit.getPlayerExact(name);
        if (on != null) {
            return on;
        }
        for (org.bukkit.OfflinePlayer o : org.bukkit.Bukkit.getOfflinePlayers()) {
            if (o.getName() != null && o.getName().equalsIgnoreCase(name)) {
                return o;
            }
        }
        return null;
    }

    /** true, wenn der Unterbefehl baumeister.bauen braucht – alles außer orte/ort/karte, Unbekanntes eingeschlossen. */
    static boolean needsBuildRight(String typed) {
        Sub s = resolve(typed);
        return s == null || !(s.travel() || s.either());
    }

    private void help(CommandSender s, String label) {
        String n = plugin.npc().name();
        if (s.hasPermission(BUILD)) {
            Texts.info(s, "Im Chat: \"" + n + ", bau mir eine kleine Kapelle aus Stein\"");
            Texts.info(s, "/" + label + " bau <auftrag> · stop · rückgängig · status · bauteile");
            Texts.info(s, "/" + label + " vorschau <auftrag> (Umriss zeigen, \"ja\" baut) · warteschlange [abbrechen]");
            Texts.info(s, "/" + label + " katalog [suchwort] – alle Schematics zum Durchklicken, Klick baut vor dir");
            Texts.info(s, "/" + label + " helfer <0–" + HelperWish.MAX + "|auto> – Helfer für deine Bauten, auch mitten im "
                    + "Bau; oder im Auftrag \"… mit 4 Helfern\", im Chat \"mehr helfer\"");
        }
        if (s.hasPermission(TRAVEL)) {
            Texts.info(s, "/" + label + " karte – Minikarte in die Hand; Linksklick zoomt hinein, Rechtsklick hinaus "
                    + "(Schleichen: schneller) · karte punkt <Name> · punkte · ziel <Name> · weg <Name> · drehen an|aus");
            Texts.info(s, "/" + label + " orte [seite|suchwort] · ort <Name> – Sprungpunkte zu gebauten Städten und Orten"
                    + (s.hasPermission("baumeister.admin") ? " · ort merken <Name> · ort löschen <Name>" : ""));
        }
        if (s.hasPermission("baumeister.admin")) {
            Texts.info(s, "/" + label + " bauteil <name> <vorne n|o|s|w> <beschreibung> – WorldEdit-Auswahl als Bauteil");
            Texts.info(s, "/" + label + " spawn [skin-spieler] · entfernen · neuladen · bauenbei <welt> <x> <y> <z> <n|o|s|w> <auftrag>");
            Texts.info(s, "/" + label + " portal <warp|welt|spawn> [Anzeige] – leeren Obsidianrahmen entzünden und "
                    + "verbinden (in ihm stehen oder hindurchschauen) · portal entfernen · portal liste");
            Texts.info(s, "/" + label + " leistung – TPS, Tick-Zeiten, Heap, Chunks und Aufträge der letzten Minute · "
                    + "neuladen erzwingen – Bau-Einstellungen auch mit vollem Rückgängig-Stapel übernehmen");
            Texts.info(s, "/" + label + " maschine <name> [<welt> <x> <y> <z> <n|o|s|w>] – Bauteil direkt bauen, ohne LLM");
            Texts.info(s, "/" + label + " sprache [an|aus|stimme <name>|vera <name>|ton normal|cartoon|probe] – Stimmen vom "
                    + "Server schalten und wählen (stimme = Baumeister, vera und ton = Vera)");
        }
        if (s.hasPermission(com.blackn0va.baumeister.plan.Tier.COMMANDS.permission())) {
            Texts.info(s, "/" + label + " befehlsblock [befehl] – Befehlsblock, auf den du schaust, zeigen (Art, bedingt, "
                    + "immer aktiv, Befehl zum Anklicken) oder seinen Befehl setzen; in jedem Spielmodus");
        }
        if (s.hasPermission("baumeister.admin")) {
            Texts.info(s, "/" + label + " logik <Formel> – Redstone-Schaltung aus einer Formel, z. B. "
                    + "\"Q = A und (B oder nicht C)\", mehrere Ausgänge mit ; getrennt (\"S = A xor B; C = A und B\"); "
                    + "Konsole: logikbei <welt> <x> <y> <z> <n|o|s|w> <Formel>");
            Texts.info(s, "/" + label + " stadt <Ort|lat,lon> [radius] · stadt los [echt | <welt> <x> <y> <z>] · stadt abbrechen – "
                    + "echter Ort 1:1 aus OpenStreetMap");
            Texts.info(s, "/" + label + " weltbau <Ort|lat,lon> [radius] · weltbau auto an|aus · status · stop · "
                    + "rückgängig [tx tz] – OpenStreetMap auf das Gelände der Erdwelt");
            Texts.info(s, "/" + label + " pause · weiter · tempo <blöcke/tick> · helfer <n> · fortschritt · komm – oder im "
                    + "Chat: \"" + n + ", schneller\"");
            Texts.info(s, "/" + label + " erde · reise <Ort|lat,lon> · wo [x z] – die ganze Erde 1:1 als eine Welt");
        }
        // Begleiter darf jeder rufen (plugin.yml) – deshalb außerhalb der Admin-Zeilen.
        if (s.hasPermission(COMPANION)) {
            Texts.info(s, "/" + label + " begleiter [weg] – Vera rufen; im Chat \"Hey Vera, …\"");
            Texts.info(s, "/" + label + " begleiter abenteuer team <spieler> | annehmen – zusammen spielen, "
                    + "was einer schafft, zählt für alle");
            Texts.info(s, "/" + label + " begleiter abenteuer [normal|hardcore|stand|aus] – Minecraft mit Vera "
                    + "durchspielen (nur " + MainWorlds.label(plugin.getConfig()) + "); im Chat \"Vera, lass uns Minecraft "
                    + "spielen\"");
        }
    }

    private void listModules(CommandSender s) {
        List<ModuleLibrary.Module> all = plugin.service().modules().all();
        if (all.isEmpty()) {
            Texts.info(s, "Noch keine Bauteile. Auswahl mit WorldEdit, dann /bm bauteil <name> <vorne> <beschreibung>.");
            return;
        }
        for (ModuleLibrary.Module m : all) {
            Texts.info(s, m.name() + " (" + m.size().x() + "×" + m.size().y() + "×" + m.size().z() + "): "
                    + m.description());
        }
    }

    /**
     * /bm schematics [suchwort] – die Familien der Bauwerk-Dateien (FileSchematics) mit Anzahl der Varianten, höchstens
     * 30 Zeilen. Wer bauen darf, darf auch stöbern; gebaut wird mit „bau mir einen Taktgeber [Variante 3]“.
     */
    private void listSchematics(CommandSender s, String label, String[] args) {
        FileSchematics files = plugin.fileSchematics();
        com.blackn0va.baumeister.plan.SchematicCatalog cat = files == null ? null : files.catalog();
        if (cat == null || cat.size() == 0) {
            Texts.info(s, "Noch keine Schematics. Lege .schem, .litematic oder .nbt in plugins/Baumeister/schematics/.");
            return;
        }
        String filter = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
        List<Map.Entry<String, Integer>> fams = cat.families(filter);
        Texts.info(s, cat.size() + " Schematics in " + cat.families("").size() + " Familien"
                + (filter.isEmpty() ? "" : ", passend zu „" + Texts.clean(filter, 30) + "“: " + fams.size()) + ".");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (Map.Entry<String, Integer> f : fams) {
            String part = f.getKey() + (f.getValue() > 1 ? " (" + f.getValue() + ")" : "");
            if (line.length() + part.length() > 90) {
                Texts.info(s, line.toString());
                line.setLength(0);
                if (++lines >= 30) {
                    Texts.info(s, "… mehr mit /" + label + " schematics <suchwort>");
                    return;
                }
            }
            line.append(line.length() == 0 ? "" : " · ").append(part);
        }
        if (line.length() > 0) {
            Texts.info(s, line.toString());
        }
        Texts.info(s, "Bauen: „Baumeister, bau mir einen " + (fams.isEmpty() ? "Taktgeber" : fams.getFirst().getKey())
                + "“ – mit „Variante 2“ die nächste.");
    }

    /**
     * Speichert die WorldEdit-Auswahl als Bauteil. „vorne“ = Himmelsrichtung, in die die
     * Vorderseite zeigt – also dorthin, wo man steht, wenn man das Bauteil benutzt.
     */
    private void saveModule(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel – es wird deine WorldEdit-Auswahl gespeichert.");
            return;
        }
        if (!(plugin.schematics() instanceof SchematicLibrary lib)) {
            Texts.info(sender, "Bauteile brauchen WorldEdit – das ist nicht geladen.");
            return;
        }
        if (args.length < 4) {
            Texts.info(sender, "/" + label + " bauteil <name> <vorne n|o|s|w> <beschreibung …>");
            return;
        }
        Cardinal front;
        try {
            front = Cardinal.parse(args[2]);
        } catch (IllegalArgumentException e) {
            Texts.info(sender, "Richtung: n, o, s oder w.");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        String description = Texts.clean(String.join(" ", Arrays.copyOfRange(args, 3, args.length)), 160);
        try {
            String problem = lib.save(p, name, front, description);
            Texts.info(sender, problem != null ? problem
                    : "Bauteil '" + name + "' gespeichert. Das LLM kann es ab jetzt verwenden.");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "bauteile | speichern_fehlgeschlagen | name=" + name, e);
            Texts.info(sender, "Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    private boolean requireAdmin(CommandSender s) {
        if (!s.hasPermission("baumeister.admin")) {
            Texts.info(s, "Dafür fehlt dir baumeister.admin.");
            return false;
        }
        return true;
    }

    private void status(CommandSender s) {
        Texts.info(s, "Zustand: " + plugin.service().statusLine());
        Texts.info(s, "NPC: " + (plugin.npc().get() != null ? "geladen" : "nicht geladen/nicht gesetzt"));
        plugin.service().llm().health().thenAccept(ok -> Bukkit.getScheduler().runTask(plugin,
                () -> Texts.info(s, "LLM: " + (ok ? "erreichbar" : "NICHT erreichbar – läuft BaumeisterHost auf dem PC und ist er verbunden?"))));
    }

    private void spawn(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel – der NPC erscheint an deiner Position.");
            return;
        }
        PlayerProfile skin = null;
        if (args.length >= 2) {
            Player source = Bukkit.getPlayerExact(args[1]);
            if (source == null) {
                Texts.info(sender, "Spieler " + args[1] + " ist nicht online – ohne ihn kein Skin.");
                return;
            }
            // Profil eines Online-Spielers hat die Texturen schon – kein Abruf bei Mojang nötig.
            skin = source.getPlayerProfile();
        }
        plugin.npc().spawn(p.getLocation(), skin);
        Texts.info(sender, "Da bin ich! Sprich mich im Chat an: " + plugin.npc().name() + ", bau …");
    }

    /** Für Konsole/RCON und Tests: Bauplatz explizit, keine Spieler-Rechteprüfung (nur Admins). */
    private void buildAt(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 7) {
            Texts.info(sender, "/" + label + " bauenbei <welt> <x> <y> <z> <n|o|s|w> <auftrag>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            Texts.info(sender, "Welt " + args[1] + " gibt es nicht.");
            return;
        }
        int x;
        int y;
        int z;
        Cardinal facing;
        try {
            x = Integer.parseInt(args[2]);
            y = Integer.parseInt(args[3]);
            z = Integer.parseInt(args[4]);
            facing = Cardinal.parse(args[5]);
        } catch (IllegalArgumentException e) {
            Texts.info(sender, "Koordinaten oder Richtung ungültig: " + e.getMessage());
            return;
        }
        String prompt = String.join(" ", Arrays.copyOfRange(args, 6, args.length));
        plugin.service().requestAt(sender, new Location(world, x, y, z), facing, prompt);
    }

    /**
     * Bauteil direkt bauen, ohne LLM: /bm maschine &lt;name&gt; &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;n|o|s|w&gt;. Die Koordinaten sind
     * die vordere linke untere Ecke, die Richtung ist die Blickrichtung des Betrachters (Vorderseite zeigt zu ihm).
     * Im Spiel genügt der Name: dann vor den eigenen Füßen in Blickrichtung.
     */
    /**
     * /bm selbsttest blöcke|daten|stabil [datei] [welt x y z] (Plan B9). Die Liste liegt im Plugin-Ordner (z. B.
     * blockstates/26.2.txt.gz) – ein Pfad außerhalb wird abgelehnt. Ohne Koordinaten: drei Blöcke vor dem Spieler.
     */
    private void selfTest(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        String usage = "/bm selbsttest blöcke|daten|stabil [datei im Plugin-Ordner] [welt x y z]";
        if (args.length < 2) {
            Texts.info(sender, usage);
            return;
        }
        String part = args[1].toLowerCase(Locale.ROOT).replace("bloecke", "blöcke");
        if (!SELF_TEST_PARTS.contains(part)) {
            Texts.info(sender, usage);
            return;
        }
        int next = 2;
        java.nio.file.Path file = null;
        if (!part.equals("daten")) {
            if (args.length < 3) {
                Texts.info(sender, usage);
                return;
            }
            java.nio.file.Path base = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            file = base.resolve(args[2]).normalize();
            // Nur Dateien im Plugin-Ordner (ASVS V12.3.1) – auch ein Admin liest so keine beliebige Datei des Servers.
            if (!file.startsWith(base) || !java.nio.file.Files.isRegularFile(file)) {
                Texts.info(sender, "Die Liste " + Texts.clean(args[2], 80) + " gibt es im Plugin-Ordner nicht.");
                return;
            }
            next = 3;
        }
        Block at;
        if (args.length >= next + 4) {
            World w = Bukkit.getWorld(args[next]);
            Integer x = parseInt(args[next + 1]);
            Integer y = parseInt(args[next + 2]);
            Integer z = parseInt(args[next + 3]);
            if (w == null || x == null || y == null || z == null) {
                Texts.info(sender, usage);
                return;
            }
            at = w.getBlockAt(x, y, z);
        } else if (sender instanceof Player p) {
            Pos ahead = new Orientation(Cardinal.fromYaw(p.getLocation().getYaw())).toWorldOffset(new Pos(0, 0, 3));
            at = p.getLocation().getBlock().getRelative(ahead.x(), 0, ahead.z());
        } else {
            Texts.info(sender, "Von der Konsole mit Ort: " + usage);
            return;
        }
        try {
            BlockSelfTest.start(plugin, sender, part, file, at, r -> BlockSelfTest.finish(plugin, sender, r));
        } catch (IOException e) {
            Texts.info(sender, "Selbsttest nicht gestartet: " + Texts.clean(e.getMessage(), 120));
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void buildModuleAt(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length == 2 && sender instanceof Player p) {
            Location feet = p.getLocation().getBlock().getLocation();
            Cardinal facing = Cardinal.fromYaw(p.getLocation().getYaw());
            Pos ahead = new Orientation(facing).toWorldOffset(new Pos(0, 0, 2));
            Location corner = feet.clone().add(ahead.x(), ahead.y(), ahead.z());
            plugin.service().requestModuleAt(sender, corner, facing, args[1].toLowerCase(Locale.ROOT));
            return;
        }
        if (args.length != 7) {
            Texts.info(sender, "/" + label + " maschine <name> [<welt> <x> <y> <z> <n|o|s|w>]");
            return;
        }
        World world = Bukkit.getWorld(args[2]);
        if (world == null) {
            Texts.info(sender, "Welt " + args[2] + " gibt es nicht.");
            return;
        }
        try {
            Location corner = new Location(world, Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]));
            plugin.service().requestModuleAt(sender, corner, Cardinal.parse(args[6]), args[1].toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Texts.info(sender, "Koordinaten oder Richtung ungültig: " + e.getMessage());
        }
    }

    /**
     * /bm gelaende &lt;Ort|lat,lon&gt; [radius] – echtes Gelände 1:1 als eigene Welt (TerrainService, DemGenerator).
     * Letztes Wort eine Zahl → Radius in Metern.
     */
    private void terrain(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            Texts.info(sender, "/" + label + " gelaende <Ort|lat,lon> [radius m] – z. B. /" + label
                    + " gelaende Grand Canyon 8000; /" + label + " gelaende einrichten – hohe Welt für 1:1 in der Höhe");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("einrichten")) {
            plugin.terrain().install(sender);
            return;
        }
        int end = args.length;
        Integer radius = null;
        if (args.length > 2 && args[end - 1].matches("\\d{3,5}")) {
            radius = Integer.parseInt(args[end - 1]);
            end--;
        }
        plugin.terrain().build(sender, plugin.city().osm(), String.join(" ", Arrays.copyOfRange(args, 1, end)), radius);
    }

    /** /bm reise &lt;Ort|lat,lon&gt; – in der Erdwelt hinreisen (EarthService.travel). */
    private void travel(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            Texts.info(sender, "/" + label + " reise <Ort|lat,lon> – z. B. /" + label + " reise Grand Canyon");
            return;
        }
        plugin.earth().travel(sender, plugin.city().osm(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    /** /bm wo [x z] – lat/lon, Höhe, Maßstab und Nordrichtung in der Erdwelt (EarthService.where). */
    private void where(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length == 1) {
            plugin.earth().where(sender, null, null);
            return;
        }
        if (args.length != 3) {
            Texts.info(sender, "/" + label + " wo [x z]");
            return;
        }
        try {
            plugin.earth().where(sender, Double.parseDouble(args[1]), Double.parseDouble(args[2]));
        } catch (NumberFormatException e) {
            Texts.info(sender, "Koordinaten ungültig: " + e.getMessage());
        }
    }

    /** /bm logik &lt;Formel&gt; – Schaltung zwei Blöcke vor den Füßen, in Blickrichtung (wie /bm maschine). */
    private void buildLogic(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2 || !(sender instanceof Player p)) {
            Texts.info(sender, "/" + label + " logik <Formel>, z. B. Q = A und (B oder nicht C) – von der Konsole: "
                    + "logikbei <welt> <x> <y> <z> <n|o|s|w> <Formel>");
            return;
        }
        Location feet = p.getLocation().getBlock().getLocation();
        Cardinal facing = Cardinal.fromYaw(p.getLocation().getYaw());
        Pos ahead = new Orientation(facing).toWorldOffset(new Pos(0, 0, 2));
        Location corner = feet.clone().add(ahead.x(), ahead.y(), ahead.z());
        plugin.service().requestLogicAt(sender, corner, facing, String.join(" ", Arrays.copyOfRange(args, 1,
                args.length)));
    }

    /** /bm logikbei &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;n|o|s|w&gt; &lt;Formel&gt; – Grundlage der LogicProbe. */
    private void buildLogicAt(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 7) {
            Texts.info(sender, "/" + label + " logikbei <welt> <x> <y> <z> <n|o|s|w> <Formel>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            Texts.info(sender, "Welt " + args[1] + " gibt es nicht.");
            return;
        }
        try {
            Location corner = new Location(world, Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]));
            plugin.service().requestLogicAt(sender, corner, Cardinal.parse(args[5]),
                    String.join(" ", Arrays.copyOfRange(args, 6, args.length)));
        } catch (IllegalArgumentException e) {
            Texts.info(sender, "Koordinaten oder Richtung ungültig: " + e.getMessage());
        }
    }

    /** /bm stadt &lt;ort…&gt; [radius] · stadt los [welt x y z] · stadt abbrechen. */
    private void city(CommandSender sender, String label, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            Texts.info(sender, "/" + label + " stadt <Ort|lat,lon> [radius] – z. B. /" + label + " stadt Bad Kreuznach 200");
            return;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (verb.equals("los") || verb.equals("start")) {
            if (args.length == 2) {
                plugin.city().start(sender, null);
                return;
            }
            if (args.length == 3 && REAL_SPOT.contains(args[2].toLowerCase(Locale.ROOT))) {
                plugin.city().startReal(sender);
                return;
            }
            World world = args.length == 6 ? Bukkit.getWorld(args[2]) : null;
            if (world == null) {
                Texts.info(sender, "/" + label + " stadt los [<welt> <x> <y> <z>] – Welt unbekannt oder Angabe unvollständig.");
                return;
            }
            try {
                plugin.city().start(sender, new Location(world, Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                        Integer.parseInt(args[5])));
            } catch (NumberFormatException e) {
                Texts.info(sender, "Koordinaten ungültig: " + e.getMessage());
            }
            return;
        }
        if (verb.equals("abbrechen") || verb.equals("verwerfen")) {
            plugin.city().discard(sender);
            return;
        }
        // Letztes Wort eine Zahl → Radius in Metern, außer bei „lat,lon“ ohne Leerzeichen-Radius.
        int end = args.length;
        Integer radius = null;
        if (args.length > 2 && args[end - 1].matches("\\d{2,4}")) {
            radius = Integer.parseInt(args[end - 1]);
            end--;
        }
        plugin.city().prepare(sender, String.join(" ", Arrays.copyOfRange(args, 1, end)), radius);
    }

    /**
     * Flache Welt für Stadtbauten anlegen bzw. laden und hinreisen. Gemessen 2026-10-28: 400 m um Bad Kreuznach in der
     * Flachwelt 53 s ohne „Can't keep up“; auf frischem Gelände einer normalen Welt kostet jeder neue Chunk den
     * Hauptthread über 200 ms (dev01: bis 21 s Stillstand). Eine Großstadt gehört deshalb in eine flache Welt.
     */
    private void cityWorld(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        World w = BaumeisterPlugin.loadCityWorld(true);
        if (w == null) {
            Texts.info(sender, "Die Stadtwelt ließ sich nicht anlegen – siehe Serverlog.");
            return;
        }
        if (sender instanceof Player p) {
            p.teleport(new Location(w, 0.5, w.getHighestBlockYAt(0, 0) + 1, 0.5));
            Texts.info(sender, "Willkommen in der Stadtwelt (flach, ohne Monster). Hier: /bm stadt <Ort> [radius], dann "
                    + "/bm stadt los. Zurück geht es mit /spawn oder /tp.");
        } else {
            Texts.info(sender, "Stadtwelt bereit: /bm stadt los " + w.getName() + " 0 " + (w.getHighestBlockYAt(0, 0))
                    + " 0");
        }
    }

    private boolean requireTravel(CommandSender s) {
        if (!s.hasPermission(TRAVEL)) {
            Texts.info(s, "Dafür fehlt dir " + TRAVEL + ".");
            return false;
        }
        return true;
    }

    /**
     * /bm orte [suchwort] [seite] – im Spiel ist jeder Name anklickbar (/bm ort &lt;Name&gt;), die Konsole bekommt Text.
     * Ein letztes Wort aus Ziffern ist die Seite, alles davor das Suchwort.
     */
    private void listPlaces(CommandSender sender, String[] args) {
        if (!requireTravel(sender)) {
            return;
        }
        int end = args.length;
        int page = 1;
        if (end > 1 && args[end - 1].matches("\\d{1,4}")) {
            page = Integer.parseInt(args[end - 1]);
            end--;
        }
        String term = end > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, end)) : null;
        List<PlaceRegistry.Place> found = term == null ? plugin.places().all() : plugin.places().search(term);
        if (found.isEmpty()) {
            Texts.info(sender, term == null ? "Noch keine Orte. Sie entstehen von selbst mit /bm stadt los und "
                    + "/bm gelaende – oder mit /bm ort merken <Name>." : "Kein Ort passt zu „" + Texts.clean(term, 64)
                    + "“. Alle: /bm orte");
            return;
        }
        PlaceRegistry.Page p = PlaceRegistry.page(found, page, PlaceRegistry.PAGE_SIZE);
        Texts.info(sender, (term == null ? "Orte" : "Orte zu „" + Texts.clean(term, 64) + "“") + ", Seite " + p.page()
                + "/" + p.pages() + " (" + p.total() + " insgesamt)" + (sender instanceof Player ? " – anklicken zum "
                + "Hinreisen:" : ":"));
        for (PlaceRegistry.Place place : p.items()) {
            String where = place.world() + " " + coords(place);
            Texts.infoLink(sender, place.name(), "/bm ort " + place.name(), place.kind().title() + "\nWelt: "
                    + place.world() + "\n" + coords(place) + "\nKlicken: hinreisen", " · " + place.kind().title()
                    + " · " + where);
        }
        if (p.page() < p.pages()) {
            Texts.info(sender, "Weiter: /bm orte " + (term == null ? "" : term + " ") + (p.page() + 1));
        }
    }

    private static String coords(PlaceRegistry.Place p) {
        return (int) Math.floor(p.x()) + " " + (int) Math.floor(p.y()) + " " + (int) Math.floor(p.z());
    }

    /**
     * /bm ort &lt;Name&gt; · ort merken &lt;Name&gt; · ort löschen &lt;Name&gt;. Heißt ein Ort genau wie die ganze Eingabe, gewinnt
     * das Hinreisen – sonst wäre ein Ort „Merken Nord“ (es gibt ein Dorf Merken bei Düren) per Klick nicht erreichbar,
     * der Klick würde stattdessen einen Ort „Nord“ anlegen.
     */
    private void place(CommandSender sender, String label, String[] args) {
        if (!requireTravel(sender)) {
            return;
        }
        if (args.length < 2) {
            Texts.info(sender, "/" + label + " ort <Name> – hinreisen; Liste: /" + label + " orte");
            return;
        }
        String whole = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (plugin.places().find(whole) == null && PlaceRegistry.VERBS.contains(verb)) {
            if (!requireAdmin(sender)) {
                return;
            }
            if (args.length < 3) {
                Texts.info(sender, "/" + label + " ort " + verb + " <Name>");
                return;
            }
            String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (verb.equals("merken")) {
                rememberHere(sender, name);
            } else {
                forgetPlace(sender, name);
            }
            return;
        }
        // „/bm ort Eberdingen Keplerstraße 5“: Steht der ganze Text nicht als Reisepunkt, kann er Stadt + Straße sein.
        if (plugin.places().find(whole) == null && plugin.city() != null
                && plugin.city().travelToStreet(sender, Arrays.asList(args).subList(1, args.length))) {
            return;
        }
        travel(sender, whole);
    }

    private void rememberHere(CommandSender sender, String name) {
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel – gemerkt wird die Stelle, an der du stehst.");
            return;
        }
        Location at = p.getLocation();
        boolean existed = plugin.places().find(name) != null;
        try {
            PlaceRegistry.Place saved = plugin.places().put(name, PlaceRegistry.Kind.CUSTOM, at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            Texts.info(sender, "Ort „" + saved.name() + "“ " + (existed ? "aktualisiert" : "gemerkt") + ": "
                    + saved.world() + " " + coords(saved) + ". Hin geht es mit /bm ort " + saved.name());
        } catch (IllegalArgumentException e) {
            Texts.info(sender, e.getMessage());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "orte | speichern_fehlgeschlagen | name=" + Texts.clean(name, 64), e);
            Texts.info(sender, "Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    private void forgetPlace(CommandSender sender, String name) {
        // Auch die Straßen der Stadt (CityIndex): Ein gelöschter Ort soll nicht über /bm ort <Ort> <Straße> weiterleben.
        boolean streets = plugin.city() != null && plugin.city().cities().remove(name);
        try {
            boolean place = plugin.places().remove(name);
            Texts.info(sender, place || streets ? "Ort „" + Texts.clean(name, 64) + "“ gelöscht"
                    + (streets ? " – auch seine Straßen (/bm ort " + Texts.clean(name, 64) + " <Straße>)." : ".")
                    : "Einen Ort „" + Texts.clean(name, 64) + "“ gibt es nicht. Liste: /bm orte");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "orte | löschen_fehlgeschlagen | name=" + Texts.clean(name, 64), e);
            Texts.info(sender, "Löschen fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Hinreisen mit sicherer Ankunft (PlaceRegistry.safeY): Fuß- und Kopfblock frei, sonst höher. Flüssigkeiten
     * zählen als belegt – ein Sprung in einen inzwischen gefluteten Stadtteil soll an der Oberfläche enden.
     */
    private void travel(CommandSender sender, String name) {
        PlaceRegistry.Place p = plugin.places().find(name);
        if (p == null) {
            List<String> similar = plugin.places().search(name).stream().limit(5).map(PlaceRegistry.Place::name)
                    .toList();
            Texts.info(sender, "Einen Ort „" + Texts.clean(name, 64) + "“ gibt es nicht."
                    + (similar.isEmpty() ? " Liste: /bm orte" : " Meintest du: " + String.join(", ", similar) + "?"));
            return;
        }
        if (!(sender instanceof Player player)) {
            Texts.info(sender, p.name() + ": " + p.world() + " " + coords(p) + " – hinreisen geht nur im Spiel.");
            return;
        }
        World w = Bukkit.getWorld(p.world());
        if (w == null) {
            Texts.info(sender, "Die Welt „" + p.world() + "“ von " + p.name() + " ist nicht geladen (gelöscht, "
                    + "umbenannt oder noch kein Neustart nach /bm gelaende einrichten?). Ohne sie kann ich dich nicht "
                    + "hinbringen.");
            return;
        }
        arrive(player, new Location(w, p.x(), p.y(), p.z(), p.yaw(), p.pitch()), p.name());
    }

    /**
     * Sicher ankommen (PlaceRegistry.safeY) – für Reisepunkte und für Straßen (CityService.travelToStreet). Von der
     * Konsole nur die Koordinaten.
     */
    static void arrive(CommandSender sender, Location at, String label) {
        World w = at.getWorld();
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        if (!(sender instanceof Player player)) {
            Texts.info(sender, label + ": " + w.getName() + " " + bx + " " + by + " " + bz
                    + " – hinreisen geht nur im Spiel.");
            return;
        }
        OptionalInt safe = PlaceRegistry.safeY(bx, by, bz, w.getMinHeight(), w.getMaxHeight(),
                (x, y, z) -> isFree(w.getBlockAt(x, y, z)));
        if (safe.isEmpty()) {
            Texts.info(sender, "Bei " + label + " ist bis zur Weltdecke kein Platz frei – ich bringe dich nicht "
                    + "in eine Wand.");
            return;
        }
        double y = safe.getAsInt() == by ? at.getY() : safe.getAsInt();
        if (!player.teleport(new Location(w, at.getX(), y, at.getZ(), at.getYaw(), at.getPitch()))) {
            Texts.info(sender, "Die Reise nach " + label + " wurde abgelehnt (ein anderes Plugin hat sie verhindert).");
            return;
        }
        Texts.info(sender, "Willkommen in " + label + " (" + w.getName() + " " + bx + " " + (int) Math.floor(y) + " "
                + bz + ").");
        LOG.info(() -> "orte | gereist | spieler=" + player.getName() + " name=" + label + " höher="
                + (safe.getAsInt() - by));
    }

    /**
     * /bm lagprobe &lt;welt&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; [sekunden] – zählt, was im Bereich von selbst schaltet
     * (LagWatch). Antwort erst nach der Messzeit; RCON wartet nicht, das Ergebnis steht dann im Log
     * („lag | gemessen“) und geht an den Absender.
     */
    private void lagProbe(CommandSender sender, String label, String[] args) {
        if (args.length < 8) {
            Texts.info(sender, "/" + label + " lagprobe <welt> <x1> <y1> <z1> <x2> <y2> <z2> [sekunden 1..60]");
            return;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) {
            Texts.info(sender, "Welt " + Texts.clean(args[1], 64) + " gibt es nicht.");
            return;
        }
        int[] c = new int[6];
        int seconds;
        try {
            for (int i = 0; i < 6; i++) {
                c[i] = Integer.parseInt(args[2 + i]);
            }
            seconds = args.length > 8 ? Math.clamp(Integer.parseInt(args[8]), 1, 60) : 10;
        } catch (NumberFormatException e) {
            Texts.info(sender, "Koordinaten und Sekunden als ganze Zahlen angeben.");
            return;
        }
        LagWatch.Box box = LagWatch.Box.of(w, c[0], c[1], c[2], c[3], c[4], c[5]);
        Texts.info(sender, "Ich messe " + seconds + " s …");
        plugin.lagWatch().watch(box, seconds * 20, r -> Texts.info(sender, "Lagprobe: " + r.line()));
    }

    private static boolean isFree(Block b) {
        return b.isPassable() && !b.isLiquid();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Portalziele (Warps, Welten, spawn) kennt nur der laufende Dienst – deshalb hier statt in complete().
        if (args.length == 2 && args[0].equalsIgnoreCase("portal") && sender.hasPermission("baumeister.admin")
                && plugin.portals() != null) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return plugin.portals().targets().stream().filter(t -> t.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
        List<String> modules = plugin.service().modules().all().stream().map(ModuleLibrary.Module::name).toList();
        List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).toList();
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        boolean travel = sender.hasPermission(TRAVEL);
        return complete(args, sender.hasPermission("baumeister.admin"), sender.hasPermission(BUILD), travel, modules,
                worlds, players, travel ? plugin.places().names() : List.of());
    }

    /** Stand vor den Reisepunkten: Admin darf reisen (plugin.yml-Kind), Spieler ohne Angabe nicht. */
    static List<String> complete(String[] args, boolean admin, List<String> modules, List<String> worlds,
            List<String> players) {
        return complete(args, admin, true, admin, modules, worlds, players, List.of());
    }

    /** Spieler mit baumeister.bauen (Stand, als /bm nur mit diesem Recht erreichbar war). */
    static List<String> complete(String[] args, boolean admin, boolean travel, List<String> modules,
            List<String> worlds, List<String> players, List<String> places) {
        return complete(args, admin, true, travel, modules, worlds, players, places);
    }

    /**
     * Vorschläge ohne Bukkit-Zugriff, damit die Rechtefilterung ohne Server testbar ist. Stufe 1 nur Hauptnamen (ein
     * Alias daneben wäre doppelt), ab Stufe 2 zählt ein Alias wie sein Hauptbefehl. Ein Admin-Befehl ohne admin
     * liefert auch in den Argumenten nichts – sonst verrieten Welt- und Spielernamen, dass es ihn gibt. build ist
     * nötig, seit /bm auch mit nur baumeister.reisen erreichbar ist: Ein reiner Reisender sähe sonst bau, stop …
     * und liefe in „Dafür fehlt dir baumeister.bauen.“
     */
    static List<String> complete(String[] args, boolean admin, boolean build, boolean travel, List<String> modules,
            List<String> worlds, List<String> players, List<String> places) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return startingWith(SUBS.stream().filter(s -> s.visible(admin, build, travel)).map(Sub::name).toList(),
                    args[0]);
        }
        Sub sub = resolve(args[0]);
        if (sub == null || !sub.visible(admin, build, travel)) {
            return List.of();
        }
        String last = args[args.length - 1];
        List<String> options = switch (sub.name()) {
            case "bau" -> args.length == 2 ? BUILD_EXAMPLES : List.of();
            case "befehle" -> args.length == 2 ? COMMAND_ANSWERS : List.of();
            case "selbsttest" -> args.length == 2 ? SELF_TEST_PARTS : List.of();
            // Stimmnamen wählt das Buch (Kapitel „Stimme“) – hier ohne Bukkit, damit die Vervollständigung testbar bleibt.
            case "sprache" -> args.length == 2 ? VOICE_VERBS : List.of();
            case "vorschau" -> {
                if (args.length != 2) {
                    yield List.of();
                }
                List<String> both = new ArrayList<>(PREVIEW_WORDS);
                both.addAll(BUILD_EXAMPLES);
                yield both;
            }
            case "warteschlange" -> args.length == 2 ? QUEUE_VERBS : List.of();
            case "tempo" -> args.length == 2 ? TEMPO_VALUES : List.of();
            case "neuladen" -> args.length == 2 ? RELOAD_VERBS : List.of();
            case "helfer" -> args.length == 2 ? HELPER_VALUES : List.of();
            case "karte" -> args.length == 2 ? MAP_VERBS : args.length == 3 && args[1].equalsIgnoreCase("zoom")
                    ? MAP_ZOOMS : args.length == 3 && args[1].equalsIgnoreCase("drehen") ? ON_OFF
                    : args.length == 3 && args[1].equalsIgnoreCase("ziel") ? places : List.of();
            case "kartenbild" -> args.length == 2 ? worlds : List.of();
            case "bildschirm" -> args.length == 2 ? SCREEN_VERBS : List.of();
            case "begleiter" -> args.length == 2 ? COMPANION_VERBS
                    : args.length == 3 && args[1].equalsIgnoreCase("abenteuer") ? ADVENTURE_VERBS : List.of();
            case "bauteil" -> args.length == 3 ? DIRECTIONS : List.of();
            case "spawn" -> args.length == 2 ? players : List.of();
            case "bauenbei" -> args.length == 2 ? worlds : args.length == 6 ? DIRECTIONS : List.of();
            case "maschine" -> args.length == 2 ? modules : args.length == 7 ? DIRECTIONS : List.of();
            case "reise" -> args.length == 2 ? PLACE_EXAMPLES : List.of();
            case "stadt" -> {
                if (args.length == 2) {
                    yield CITY_VERBS;
                }
                // „start“ ist in city() gleichwertig zu „los“; „abbrechen“/„verwerfen“ nehmen keine Argumente.
                String verb = args[1].toLowerCase(Locale.ROOT);
                if (args.length == 3 && (verb.equals("los") || verb.equals("start"))) {
                    List<String> spots = new ArrayList<>(worlds);
                    spots.add(0, "echt");
                    yield spots;
                }
                yield List.of();
            }
            case "weltbau" -> args.length == 2 ? WORLD_BUILD_VERBS
                    : args.length == 3 && args[1].equalsIgnoreCase("auto") ? ON_OFF : List.of();
            case "ort" -> {
                String first = args[1].toLowerCase(Locale.ROOT);
                if (args.length > 2 && admin && PlaceRegistry.VERBS.contains(first)) {
                    // „merken“ will einen NEUEN Namen – Vorschläge wären dort nur im Weg.
                    yield first.equals("merken") ? List.of() : nameWords(places, args, 2);
                }
                List<String> names = nameWords(places, args, 1);
                if (args.length == 2 && admin) {
                    List<String> both = new ArrayList<>(PLACE_VERBS);
                    both.addAll(names);
                    yield both;
                }
                yield names;
            }
            default -> List.of();
        };
        return startingWith(options, last);
    }

    /**
     * Ortsnamen mit Leerzeichen („Bad Kreuznach“) kommen bei Bukkit als mehrere Argumente an, der Client ersetzt aber
     * nur das gerade getippte Wort. Vorgeschlagen wird deshalb der Rest des Namens ab diesem Wort: „/bm ort “ + Tab
     * setzt den ganzen Namen ein, „/bm ort Bad K“ + Tab ergänzt „Kreuznach“. Nur das erste Wort vorzuschlagen hätte
     * bei „Bad Kreuznach“ und „Bad Sobernheim“ zwei gleiche Vorschläge „Bad“ ergeben.
     */
    static List<String> nameWords(List<String> names, String[] args, int from) {
        String typed = String.join(" ", Arrays.copyOfRange(args, from, args.length)).toLowerCase(Locale.ROOT);
        int index = args.length - 1 - from;
        return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).map(n -> n.split(" "))
                .filter(words -> index < words.length)
                .map(words -> String.join(" ", Arrays.copyOfRange(words, index, words.length))).distinct().toList();
    }

    private static Sub resolve(String typed) {
        String key = typed.toLowerCase(Locale.ROOT);
        for (Sub s : SUBS) {
            if (s.name().equals(key) || s.aliases().contains(key)) {
                return s;
            }
        }
        return null;
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String start = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }
}
