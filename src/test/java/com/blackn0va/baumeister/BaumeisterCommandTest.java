package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Tab-Vervollständigung von /bm: nach Rechten gefiltert, Aliase wie Hauptbefehl, Werte für tempo/helfer. */
class BaumeisterCommandTest {

    private static final List<String> MODULES = List.of("ofen", "farm");
    private static final List<String> WORLDS = List.of("world", "stadtwelt");
    private static final List<String> PLAYERS = List.of("Heiko", "Gast");

    private static List<String> admin(String... args) {
        return BaumeisterCommand.complete(args, true, MODULES, WORLDS, PLAYERS);
    }

    private static List<String> player(String... args) {
        return BaumeisterCommand.complete(args, false, MODULES, WORLDS, PLAYERS);
    }

    private static final List<String> PLACES = List.of("Bad Kreuznach", "Bad Sobernheim", "München", "Grand Canyon");

    private static List<String> traveller(String... args) {
        return BaumeisterCommand.complete(args, false, true, MODULES, WORLDS, PLAYERS, PLACES);
    }

    private static List<String> adminWithPlaces(String... args) {
        return BaumeisterCommand.complete(args, true, true, MODULES, WORLDS, PLAYERS, PLACES);
    }

    @Test
    void playersDoNotSeeAdminCommands() {
        // tunnel, treppe, brücke, weiterbauen seit 2026-11-16 (Graben und Brücken, Rechte wie bau).
        assertEquals(List.of("bau", "tunnel", "treppe", "brücke", "weiterbauen", "stop", "rückgängig", "status",
                "bildschirm", "bauteile", "schematics", "katalog", "fortschritt", "komm",
                "vorschau", "warteschlange", "karte", "begleiter", "stadtleben", "module", "helfer"), player(""));
    }

    @Test
    void adminsSeeEveryMainName() {
        List<String> all = admin("");
        assertEquals(51, all.size(), all.toString());  // 51 seit module (2026-11-22)
        assertTrue(all.containsAll(List.of("portal", "einrichten")));
        assertTrue(all.containsAll(List.of("befehle", "befehlsblock", "selbsttest")));
        assertTrue(all.containsAll(List.of("bauteil", "spawn", "stadt", "stadtwelt", "tempo", "helfer", "logik", "logikbei",
                "erde", "reise", "wo", "weltbau", "orte", "ort", "karte", "kartenbild")));
        // Stufe 1 nur Hauptnamen – Aliase dürfen nicht als Dubletten auftauchen.
        assertTrue(all.stream().noneMatch(s -> List.of("dorf", "city", "undo", "reload", "remove", "build")
                .contains(s)), all.toString());
        assertTrue(!all.contains("erdbau"), all.toString());
    }

    @Test
    void aliasesCompleteLikeTheirMainCommand() {
        assertEquals(admin("stadt", ""), admin("dorf", ""));
        assertEquals(admin("stadt", ""), admin("city", ""));
        assertEquals(admin("stadt", "los", ""), admin("dorf", "los", ""));
        assertEquals(admin("stadt", "los", ""), admin("CITY", "start", ""));
        assertEquals(List.of("los", "abbrechen"), admin("dorf", ""));
        // „echt“ zuerst: an der echten Stelle wie in der Erdwelt (CityPlacement), danach die Welten.
        assertEquals(java.util.stream.Stream.concat(java.util.stream.Stream.of("echt"), WORLDS.stream()).toList(),
                admin("city", "start", ""));
        assertEquals(admin("bau", ""), player("build", ""));
    }

    @Test
    void performanceAndForcedReloadAreAdminOnly() {
        assertTrue(admin("").contains("leistung"));
        assertFalse(player("").contains("leistung"));
        assertEquals(List.of(), player("leistung", ""));
        assertEquals(List.of("erzwingen"), admin("neuladen", ""));
        assertEquals(List.of("erzwingen"), admin("reload", "e"));
        assertEquals(List.of(), player("neuladen", ""));
        assertTrue(BaumeisterCommand.needsBuildRight("leistung"));
        assertTrue(BaumeisterCommand.needsBuildRight("tps"));
    }

    @Test
    void tempoSuggestsValues() {
        assertEquals(List.of("500", "1000", "2000", "5000"), admin("tempo", ""));
        assertEquals(List.of("500", "5000"), admin("tempo", "5"));
        assertEquals(List.of(), admin("tempo", "500", ""));
    }

    @Test
    void helperSuggestsValues() {
        assertEquals(List.of("0", "2", "4", "8", "auto"), admin("helfer", ""));
        assertEquals(List.of("4"), admin("helfer", "4"));
        // Seit 2026-10-30 auch für Spieler – Helfer für den eigenen Bau.
        assertEquals(List.of("0", "2", "4", "8", "auto"), player("helfer", ""));
    }

    /**
     * /bm stadtleben an|aus|messung nur für Admins – geprüft, bevor der Befehl das Plugin anfasst (hier null: käme er
     * durch, flöge eine NullPointerException). Gegenprobe 2026-11-22: ohne requireAdmin blieb die Suite grün.
     */
    @Test
    void townLifeSwitchNeedsAdmin() {
        List<String> said = new java.util.ArrayList<>();
        org.bukkit.command.CommandSender builder = (org.bukkit.command.CommandSender) java.lang.reflect.Proxy
                .newProxyInstance(org.bukkit.command.CommandSender.class.getClassLoader(),
                        new Class<?>[] {org.bukkit.command.CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                            case "hasPermission" -> "baumeister.bauen".equals(String.valueOf(a[0]));
                            case "sendMessage" -> {
                                said.add(String.valueOf(a[0]));
                                yield null;
                            }
                            case "getName" -> "Gast";
                            default -> throw new UnsupportedOperationException(m.getName());
                        });
        BaumeisterCommand cmd = new BaumeisterCommand(null);
        for (String verb : List.of("an", "aus", "messung")) {
            said.clear();
            assertTrue(cmd.onCommand(builder, null, "bm", new String[] {"stadtleben", verb}));
            assertEquals(1, said.size(), verb + ": " + said);
            assertTrue(said.getFirst().contains("baumeister.admin"), said.getFirst());
        }
    }

    @Test
    void prefixFilterApplies() {
        assertEquals(List.of("stop", "status", "stadtleben", "stadt", "stadtwelt"), admin("st"));
        assertEquals(List.of("stop", "status", "stadtleben"), player("st"));
        // Stadtleben (2026-11-21): schalten nur Admins – Spieler sehen nur status. vergessen (v013, AK-41) und feld
        // (AK-43) ebenso.
        assertEquals(List.of("an", "aus", "status", "messung", "vergessen", "feld"), admin("stadtleben", ""));
        assertEquals(List.of("setzen", "entfernen", "liste"), admin("stadtleben", "feld", ""));
        assertEquals(List.of(), player("stadtleben", "feld", ""), "Feld-Wörter nur für Admins");
        assertEquals(List.of("status"), player("stadtleben", ""));
        assertEquals(List.of("stop", "status", "stadtleben", "stadt", "stadtwelt"), admin("ST"));
        assertEquals(List.of("abbrechen"), admin("stadt", "ab"));
        assertEquals(List.of("stadtwelt"), admin("stadt", "los", "sta"));
        assertEquals(List.of("Heiko"), admin("spawn", "he"));
        assertEquals(List.of("ofen"), admin("maschine", "o"));
    }

    @Test
    void adminSubcommandArgsHiddenForPlayers() {
        assertEquals(List.of(), player("tempo", ""));
        assertEquals(List.of(), player("stadt", ""));
        assertEquals(List.of(), player("dorf", "los", ""));
        assertEquals(List.of(), player("spawn", ""));
        assertEquals(List.of(), player("bauenbei", ""));
        assertEquals(List.of(), player("maschine", ""));
        assertEquals(List.of(), player("bauteil", "x", ""));
    }

    @Test
    void positionalSuggestionsStayWhereTheyWere() {
        assertEquals(List.of("n", "o", "s", "w"), admin("bauteil", "ofen", ""));
        assertEquals(List.of(), admin("bauteil", ""));
        assertEquals(WORLDS, admin("bauenbei", ""));
        assertEquals(List.of("n", "o", "s", "w"), admin("bauenbei", "world", "1", "2", "3", ""));
        assertEquals(MODULES, admin("maschine", ""));
        assertEquals(List.of("n", "o", "s", "w"), admin("maschine", "ofen", "world", "1", "2", "3", ""));
        assertEquals(PLAYERS, admin("spawn", ""));
        assertEquals(List.of(), admin("stadt", "abbrechen", ""));
        assertEquals(List.of(), admin("stadt", "verwerfen", ""));
        assertEquals(List.of(), admin("unbekannt", ""));
    }

    @Test
    void earthCommandsAreForAdminsAndTravelSuggestsPlaces() {
        assertEquals(List.of(), player("er"));
        assertEquals(List.of("erde"), admin("er"));
        assertEquals(List.of("wo"), admin("wo"));
        assertEquals(List.of("Hepberg", "Grand Canyon", "Mount Everest", "48.82,11.46"), admin("reise", ""));
        assertEquals(admin("reise", ""), admin("travel", ""));
        assertEquals(List.of("Grand Canyon"), admin("reise", "gr"));
        assertEquals(List.of(), player("reise", ""));
        assertEquals(List.of(), admin("reise", "Hepberg", ""));
        assertEquals(List.of(), admin("wo", ""));
    }

    @Test
    void worldBuildSuggestsItsVerbsOnlyToAdmins() {
        assertEquals(List.of("auto", "rückgängig", "status", "stop"), admin("weltbau", ""));
        assertEquals(List.of("an", "aus"), admin("weltbau", "auto", ""));
        assertEquals(List.of("aus"), admin("erdbau", "AUTO", "au"));
        assertEquals(List.of(), admin("weltbau", "Bad", ""));
        assertEquals(List.of(), player("weltbau", ""));
        assertEquals(List.of("weiterbauen", "weltbau", "weiter"), admin("we"));
        assertEquals(List.of("weiterbauen"), player("we"));
    }

    @Test
    void buildOffersGenericExamplesToPlayers() {
        List<String> examples = player("bau", "");
        assertTrue(examples.size() >= 2 && examples.size() <= 3, examples.toString());
        assertEquals(examples, player("baue", ""));
        assertEquals(List.of("kleine Kapelle aus Stein"), player("bau", "KLEINE"));
        assertEquals(List.of(), player("undo", ""));
        assertEquals(List.of(), player("bau", "kleine", ""));
    }

    @Test
    void travelCommandsNeedTheTravelRight() {
        assertEquals(List.of("bau", "tunnel", "treppe", "brücke", "weiterbauen", "stop", "rückgängig", "status",
                "bildschirm", "bauteile", "schematics", "katalog", "fortschritt", "komm", "vorschau", "warteschlange",
                "orte", "karte", "ort", "begleiter", "stadtleben", "module", "helfer"), traveller(""));
        assertEquals(List.of(), player("ort", ""));
        assertEquals(List.of(), player("orte", ""));
    }

    /** Sender, der nur die angegebenen Rechte hat; alles andere am Interface ist hier nicht gefragt. */
    private static CommandSender senderWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, methodArgs) -> {
                    if (method.getName().equals("hasPermission") && methodArgs.length == 1
                            && methodArgs[0] instanceof String p) {
                        return granted.contains(p);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** permission von /bm genau wie in plugin.yml – per Muster, weil snakeyaml im Testpfad fehlt. */
    private static String commandPermissionFromPluginYml() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^    permission: (\\S+)\\s*$").matcher(yml);
        assertTrue(m.find(), "permission am Befehl baumeister fehlt");
        return m.group(1);
    }

    @Test
    void travelRightAloneGetsPastBukkitsCommandGate() throws IOException {
        // Bukkits eigene Prüfung (Command.testPermissionSilent) mit dem Wert aus plugin.yml – kein Nachbau.
        Command bm = new Command("baumeister") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        bm.setPermission(commandPermissionFromPluginYml());
        assertTrue(bm.testPermissionSilent(senderWith("baumeister.reisen")), "nur reisen");
        assertTrue(bm.testPermissionSilent(senderWith("baumeister.bauen")), "nur bauen");
        assertFalse(bm.testPermissionSilent(senderWith()), "ohne Recht bleibt Bukkit die Grenze");
        assertFalse(bm.testPermissionSilent(senderWith("baumeister.irgendwas")));
    }

    @Test
    void everythingButTravelNeedsTheBuildRight() {
        for (String travel : List.of("orte", "ort", "ORT", "Orte")) {
            assertFalse(BaumeisterCommand.needsBuildRight(travel), travel);
        }
        for (String build : List.of("bau", "baue", "build", "stop", "rückgängig", "rueckgaengig", "undo", "status",
                "bauteile", "fortschritt", "komm", "bauteil", "spawn", "entfernen", "remove", "bauenbei", "maschine",
                "logik", "logic", "schaltung", "logikbei", "neuladen", "reload", "stadt", "dorf", "city", "stadtwelt",
                "gelaende", "gelände", "terrain", "pause", "weiter", "tempo", "helfer", "vorschau", "preview",
                "warteschlange", "queue", "schematics", "sammlung")) { // umlaut-ok: Alias
            assertTrue(BaumeisterCommand.needsBuildRight(build), build);
        }
        // Unbekanntes fail-closed: ein später ergänzter Unterbefehl ohne SUBS-Zeile ist nicht frei.
        assertTrue(BaumeisterCommand.needsBuildRight("unbekannt"));
        // Minikarte: Anzeige, mit Bauen- ODER Reisen-Recht; das Bild ab Konsole bleibt beim Bauen-Recht (und Admin).
        assertFalse(BaumeisterCommand.needsBuildRight("karte"));
        assertFalse(BaumeisterCommand.needsBuildRight("map"));
        assertTrue(BaumeisterCommand.needsBuildRight("kartenbild"));
        assertTrue(BaumeisterCommand.needsBuildRight("ortex"));
    }

    @Test
    void travellerWithoutBuildRightSeesOnlyTravelCommands() {
        List<String> none = List.of();
        assertEquals(List.of("orte", "karte", "ort", "begleiter", "module"), BaumeisterCommand.complete(new String[] {""}, false, false, true,
                MODULES, WORLDS, PLAYERS, PLACES));
        assertEquals(PLACES, BaumeisterCommand.complete(new String[] {"ort", ""}, false, false, true, MODULES, WORLDS,
                PLAYERS, PLACES));
        assertEquals(none, BaumeisterCommand.complete(new String[] {"bau", ""}, false, false, true, MODULES, WORLDS,
                PLAYERS, PLACES));
        assertEquals(none, BaumeisterCommand.complete(new String[] {""}, false, false, false, MODULES, WORLDS,
                PLAYERS, PLACES));
    }

    @Test
    void travelSuggestsPlaceNamesWordByWord() {
        assertEquals(PLACES, traveller("ort", ""));
        assertEquals(List.of("Bad Kreuznach", "Bad Sobernheim"), traveller("ort", "b"));
        assertEquals(List.of("Kreuznach", "Sobernheim"), traveller("ort", "Bad", ""));
        assertEquals(List.of("Kreuznach"), traveller("ort", "bad", "k"));
        assertEquals(List.of(), traveller("ort", "München", ""));
    }

    @Test
    void onlyAdminsSeeRememberAndDelete() {
        assertTrue(traveller("ort", "").stream().noneMatch(s -> s.equals("merken") || s.equals("löschen")));
        List<String> admin = adminWithPlaces("ort", "");
        assertEquals(List.of("merken", "löschen"), admin.subList(0, 2));
        assertTrue(admin.containsAll(PLACES));
        assertEquals(List.of("Kreuznach", "Sobernheim"), adminWithPlaces("ort", "löschen", "Bad", ""));
        assertEquals(List.of("München"), adminWithPlaces("ort", "loeschen", "m"));
        assertEquals(List.of(), adminWithPlaces("ort", "merken", ""));
        // Ohne Admin ist „löschen“ nur ein Wort eines Ortsnamens – hier keiner.
        assertEquals(List.of(), traveller("ort", "löschen", ""));
    }

    /**
     * „/bm grab ein tunnel“ (dev01 2026-11-16): zeigte dreimal nur die Hilfe. Grabsätze gehen jetzt den Grabweg,
     * alles andere bleibt bei der Hilfe – ein Tippfehler baut nichts.
     */
    /**
     * Jeder Befehl, den ein Spieler benutzen darf, steht in der Hilfe zu /bm. Hinweis des Nutzers 2026-11-19: Tunnel,
     * Treppe, Brücke und Weiterbauen fehlten dort seit 2026-11-16 – dazu Bildschirm, Schematics, Fortschritt und Komm.
     */
    @Test
    void everyPlayerCommandIsInTheHelp() {
        Set<String> rights = Set.of("baumeister.bauen", "baumeister.reisen", "baumeister.begleiter");
        String help = String.join("\n", BaumeisterCommand.helpLines("bm", "Baumeister", rights::contains, "world"));
        for (String sub : BaumeisterCommand.subcommands(true)) {
            Matcher m = Pattern.compile("(?<!\\p{L})" + Pattern.quote(sub) + "(?!\\p{L})").matcher(help);
            assertTrue(m.find(), sub + " fehlt in der Hilfe");
        }
        assertFalse(help.contains("bauenbei"), "Admin-Befehle nicht in der Spieler-Hilfe");
    }

    /** plugin.yml nennt unter usage jeden Unterbefehl – dort fehlten neun, darunter tunnel, treppe und brücke. */
    @Test
    void pluginYmlUsageNamesEverySubcommand() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("usage: /<command> <([^>]+)>").matcher(yml);
        assertTrue(m.find(), "usage fehlt");
        Set<String> usage = Set.of(m.group(1).split("\\|"));
        for (String sub : BaumeisterCommand.subcommands(false)) {
            assertTrue(usage.contains(sub), sub + " fehlt in plugin.yml unter usage");
        }
    }

    @Test
    void aDigSentenceAfterBmIsAnOrderAndAnythingElseStaysHelp() {
        assertTrue(BaumeisterCommand.digSentence("grab ein tunnel"));
        assertTrue(BaumeisterCommand.digSentence("grab einen Tunnel nach Norden 40 schnell"));
        assertTrue(BaumeisterCommand.digSentence("bau mir eine Treppe nach unten"));
        assertTrue(BaumeisterCommand.digSentence("hör auf mit dem Tunnel"), "stopp");
        assertTrue(BaumeisterCommand.digSentence("grab einen tunnel 20000 schnell"), "Absage statt Hilfe");
        assertFalse(BaumeisterCommand.digSentence("hilfe"));
        assertFalse(BaumeisterCommand.digSentence("statu"));
        assertFalse(BaumeisterCommand.digSentence("bau mir ein haus"), "Bauaufträge nur mit /bm bau");
    }

    @Test
    void previewAndQueueCompleteForPlayersButNotForTravellersOnly() {
        List<String> preview = player("vorschau", "");
        assertEquals(List.of("ja", "nein"), preview.subList(0, 2));
        assertTrue(preview.contains("kleine Kapelle aus Stein"), preview.toString());
        assertEquals(List.of("nein"), player("preview", "n"));
        assertEquals(List.of("abbrechen"), player("warteschlange", ""));
        assertEquals(List.of("abbrechen"), player("queue", "ab"));
        assertEquals(List.of(), player("warteschlange", "abbrechen", ""));
        assertEquals(List.of(), BaumeisterCommand.complete(new String[] {"vorschau", ""}, false, false, true, MODULES,
                WORLDS, PLAYERS, PLACES));
    }
}
