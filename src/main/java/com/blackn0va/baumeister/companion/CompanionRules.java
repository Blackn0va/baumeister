package com.blackn0va.baumeister.companion;

import java.util.Locale;
import java.util.Set;

/**
 * Was der Begleiter darf und wie er Ergebnisse ausspricht – rein, ohne Bukkit, damit die Rechteprüfung ihren eigenen
 * Test mit Gegenprobe hat.
 *
 * Festgelegt mit dem Nutzer (2026-11-04): Geben nur im Kreativmodus oder mit Recht baumeister.begleiter.geben. Sonst
 * wäre der Begleiter im Überlebensmodus ein Cheat für jeden, der ihn ruft (ASVS V4.1.3: Rechte serverseitig prüfen –
 * das LLM entscheidet nie selbst, ob es geben darf). Erze suchen ist im Überlebensmodus ebenso ein Röntgenblick und
 * hängt an baumeister.begleiter.suchen.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionRules {

    private CompanionRules() {
    }

    /** fail-closed: nur Kreativmodus oder ausdrückliches Recht. */
    public static boolean mayGive(boolean creative, boolean permission) {
        return creative || permission;
    }

    /** Gewöhnliche Blöcke darf jeder suchen lassen, Erze nur im Kreativmodus oder mit Recht. */
    /**
     * „Wo finde ich Diamant?“ meint das Erz, nicht den Diamantblock: Rohstoffwort → Erz-ID, sonst null. (ItemNames
     * löst „diamant“ zum Gegenstand auf, und als Block wäre das der Diamantblock.)
     */
    public static String oreFor(String word) {
        if (word == null) {
            return null;
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue");
        return switch (w) {
            case "diamant", "diamanten", "diamonds", "diamond", "dias" -> "diamond_ore";
            case "eisen", "eisenerz", "iron" -> "iron_ore";
            case "gold", "golderz" -> "gold_ore";
            case "kohle", "kohlen", "coal" -> "coal_ore";
            case "kupfer", "copper" -> "copper_ore";
            case "redstone" -> "redstone_ore";
            case "lapis", "lapislazuli" -> "lapis_ore";
            case "smaragd", "smaragde", "emerald" -> "emerald_ore";
            case "netherquarz", "quarz" -> "nether_quartz_ore";
            case "antiker schrott", "schrott", "netherit", "netherite" -> "ancient_debris";
            default -> null;
        };
    }

    /**
     * Alle Formen eines Erzes, die die Suche zählen soll (Wunsch 2026-11-08). Vorher
     * suchte Vera genau ein Material – „wo finde ich Diamanten?“ fand nur diamond_ore. Seit 1.18 liegen die meisten
     * Diamanten unter y = 0 als deepslate_diamond_ore; Vera sagte dort „finde ich kein diamond ore“, obwohl drei Blöcke
     * daneben eins lag. Gold im Nether ist nether_gold_ore. Andere Blöcke: nur sie selbst. Rein, testbar.
     */
    public static java.util.List<String> oreFamily(String blockId) {
        String b = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (!b.endsWith("_ore")) {
            return java.util.List.of(b);
        }
        String base = b.startsWith("deepslate_") ? b.substring("deepslate_".length()) : b;
        java.util.List<String> out = new java.util.ArrayList<>(java.util.List.of(b));
        if (!base.startsWith("nether_")) {
            String other = b.equals(base) ? "deepslate_" + base : base;
            out.add(other);
        }
        if (base.equals("gold_ore")) {
            out.add("nether_gold_ore");
        }
        if (b.equals("nether_gold_ore")) {
            out.add("gold_ore");
            out.add("deepslate_gold_ore");
        }
        return java.util.List.copyOf(out);
    }

    /** Ein Ort für die Ortssuche: wie Vera ihn nennt und welche Bauwerke (Minecraft-Schlüssel) dazugehören. */
    public record Place(String label, java.util.List<String> structures) {
    }

    private static final java.util.Map<String, Place> PLACES = placeTable();

    private static java.util.Map<String, Place> placeTable() {
        java.util.Map<String, Place> m = new java.util.HashMap<>();
        Place village = new Place("Dorf", java.util.List.of("village_plains", "village_desert", "village_savanna",
                "village_snowy", "village_taiga"));
        for (String w : new String[] {"dorf", "dorfes", "doerfer", "village"}) { // umlaut-ok: normalisierte Form
            m.put(w, village);
        }
        Place fortress = new Place("Netherfestung", java.util.List.of("fortress"));
        for (String w : new String[] {"netherfestung", "nether festung", "festung im nether", "fortress"}) {
            m.put(w, fortress);
        }
        // „Festung“ allein: im Nether die Netherfestung, in der Oberwelt die Festung mit dem Endportal – gesucht
        // werden beide, Minecraft findet in jeder Dimension nur, was es dort gibt.
        m.put("festung", new Place("Festung", java.util.List.of("stronghold", "fortress")));
        Place stronghold = new Place("Festung mit dem Endportal", java.util.List.of("stronghold"));
        for (String w : new String[] {"endportal", "end portal", "stronghold", "festung mit dem endportal"}) {
            m.put(w, stronghold);
        }
        m.put("bastion", new Place("Bastionsruine", java.util.List.of("bastion_remnant")));
        m.put("bastionsruine", m.get("bastion"));
        Place monument = new Place("Ozeanmonument", java.util.List.of("monument"));
        m.put("ozeanmonument", monument);
        m.put("monument", monument);
        Place mansion = new Place("Waldanwesen", java.util.List.of("mansion"));
        m.put("waldanwesen", mansion);
        m.put("anwesen", mansion);
        Place outpost = new Place("Plünderer-Außenposten", java.util.List.of("pillager_outpost"));
        for (String w : new String[] {"aussenposten", "pluenderer aussenposten", "pluendereraussenposten", // umlaut-ok
                "outpost"}) {
            m.put(w, outpost);
        }
        m.put("wuestentempel", new Place("Wüstentempel", java.util.List.of("desert_pyramid"))); // umlaut-ok
        m.put("pyramide", m.get("wuestentempel")); // umlaut-ok
        m.put("dschungeltempel", new Place("Dschungeltempel", java.util.List.of("jungle_pyramid")));
        m.put("hexenhuette", new Place("Hexenhütte", java.util.List.of("swamp_hut"))); // umlaut-ok
        m.put("iglu", new Place("Iglu", java.util.List.of("igloo")));
        m.put("antike stadt", new Place("antike Stadt", java.util.List.of("ancient_city")));
        m.put("pruefungskammer", new Place("Prüfungskammer", java.util.List.of("trial_chambers"))); // umlaut-ok
        m.put("schiffswrack", new Place("Schiffswrack", java.util.List.of("shipwreck", "shipwreck_beached")));
        m.put("ozeanruine", new Place("Ozeanruine", java.util.List.of("ocean_ruin_cold", "ocean_ruin_warm")));
        m.put("ruinenportal", new Place("Portalruine", java.util.List.of("ruined_portal", "ruined_portal_desert",
                "ruined_portal_jungle", "ruined_portal_swamp", "ruined_portal_mountain", "ruined_portal_ocean",
                "ruined_portal_nether")));
        m.put("portalruine", m.get("ruinenportal"));
        Place mine = new Place("verlassene Mine", java.util.List.of("mineshaft", "mineshaft_mesa"));
        m.put("mine", mine);
        m.put("verlassene mine", mine);
        m.put("bergwerk", mine);
        m.put("endstadt", new Place("Endstadt", java.util.List.of("end_city")));
        return java.util.Map.copyOf(m);
    }

    /**
     * „das nächste Dorf“, „die nächste Netherfestung“, „eine Bastion“ → Ort für die Ortssuche (Idee aus Verity JE:
     * get_nearest_village, get_nearest_nether_fortress), sonst leer. Umlaute und Artikel/„nächste“ egal. Rein.
     */
    public static java.util.Optional<Place> placeFor(String words) {
        if (words == null) {
            return java.util.Optional.empty();
        }
        String w = words.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss").replaceAll("[^a-z ]+", " ").replaceAll("\\s+", " ").strip(); // umlaut-ok
        w = w.replaceFirst("^(?:(?:ein|eine|einen|das|die|der|den|dem)\\s+)?(?:naechste[nsr]?\\s+)?", ""); // umlaut-ok
        w = w.replaceFirst("\\s+(?:hier|in der naehe)$", ""); // umlaut-ok
        return java.util.Optional.ofNullable(PLACES.get(w));
    }

    public static boolean mayFind(String blockId, boolean creative, boolean permission) {
        return !isOre(blockId) || creative || permission;
    }

    /** Was mit einer Befehlszeile des Begleiters geschieht – in dieser Reihenfolge geprüft. */
    public enum CommandDecision {
        /** Recht baumeister.begleiter.befehle fehlt. */
        DENIED,
        /** Leer oder mit Steuerzeichen. */
        INVALID,
        /** Folgenreich: erst nach „ja“ des Spielers. */
        CONFIRM,
        RUN
    }

    /**
     * Admin-Wunsch (Wunsch 2026-11-04) Deshalb führt
     * sie beliebige Befehle aus – aber immer ALS der Spieler (CompanionService.runCommand), also nur mit dessen eigenen
     * Rechten, und nur mit baumeister.begleiter.befehle (Vorgabe op). Das LLM entscheidet nie über Rechte
     * (ASVS V4.1.3, OWASP A01). Wer DENIED zurückdreht, lässt jeden Spieler über das Modell Befehle absetzen, die
     * dann zwar an seinen Rechten scheitern, aber Prompt-Injection aus dem Chat in Befehle verwandeln.
     *
     * Rückfrage statt Sperre: Ein Hörfehler von Whisper („stopp“ → stop) oder ein kleines Modell darf den Server nicht
     * herunterfahren oder alle Spieler bannen. Gesperrt wird nichts – der Admin sagt „ja“.
     */
    public static CommandDecision decide(String line, boolean permission) {
        if (!permission) {
            return CommandDecision.DENIED;
        }
        if (line == null) {
            return CommandDecision.INVALID;
        }
        return needsConfirmation(line) ? CommandDecision.CONFIRM : CommandDecision.RUN;
    }

    /** Was eine Zeile mit einer offenen Befehls-Rückfrage macht (CompanionService.handle). */
    public enum PendingAnswer {
        /** Getipptes „ja“ vom selben Spieler in der Frist: ausführen. */
        RUN,
        /** „nein“ in der Frist – auch gesprochen, Abbrechen ist nie gefährlich. */
        CANCEL,
        /** Gesprochenes „ja“: nicht ausführen, die Rückfrage bleibt mit ihrer Frist stehen, bis „ja“ getippt kommt. */
        TYPE_IT,
        /** Keine Antwort, zu spät oder von jemand anderem: Rückfrage verfällt, die Zeile geht normal weiter. */
        EXPIRED
    }

    /**
     * Plan A4 (Wunsch 2026-11-08): Ein „ja“ aus
     * der Spracherkennung führt keinen Befehl aus. Vorher galt es wie getippt – Whisper hört „ja“ auch in Geräuschen,
     * und über den Sprachweg (CompanionVoiceLink, „gehört“) kommt Text, den nicht der Minecraft-Client des Spielers
     * geschickt hat. Getippter Chat kommt dagegen nur über dessen angemeldete Verbindung.
     *
     * Admin heißt hier: jeder, für den Vera Befehle ausführt (baumeister.begleiter.befehle; baumeister.admin vererbt
     * es, Ops haben es über die Stufe). Bewusst nicht nur op/baumeister.admin: Vera führt Befehle als der Spieler aus,
     * und welche Befehle der darf, weiß das Plugin nicht – LuckPerms kann einem Nicht-Op alles geben. Fail-closed
     * (ASVS V4.1.5, OWASP A01). Wer das wieder an die Rechte knüpft, lässt Gesprochenes für jeden mit unbekannten
     * Befehlsrechten durch.
     */
    public static PendingAnswer pendingAnswer(Boolean yes, boolean fresh, boolean spoken) {
        if (!fresh || yes == null) {
            return PendingAnswer.EXPIRED;
        }
        if (!yes) {
            return PendingAnswer.CANCEL;
        }
        return spoken ? PendingAnswer.TYPE_IT : PendingAnswer.RUN;
    }

    /**
     * Braucht ein Befehlsbündel eine Rückfrage? Getippt nur, wenn eine Zeile folgenreich ist (needsConfirmation).
     * Gesprochen immer (Plan A4, siehe pendingAnswer): Vorher lief „Vera, mach Tag“ über die Spracherkennung sofort –
     * ein falsch erkannter oder untergeschobener Satz hätte so jeden harmlos aussehenden Befehl des Admins ausgelöst.
     * Wer das zurückdreht, braucht für Befehle aus Gesprochenem gar keine getippte Bestätigung mehr.
     */
    public static boolean bundleNeedsConfirmation(java.util.List<String> lines, boolean spoken) {
        return bundleNeedsConfirmation(lines, spoken, null);
    }

    /** Wie oben; self = der Spieler, für den Vera handelt (needsConfirmation). */
    public static boolean bundleNeedsConfirmation(java.util.List<String> lines, boolean spoken, String self) {
        return spoken || lines.stream().anyMatch(l -> needsConfirmation(l, self));
    }

    /**
     * Befehlszeile aus der Aktion command: ohne führenden Schrägstrich, Leerraum zusammengezogen. null bei leer oder
     * Steuerzeichen – ein Zeilenumbruch täuschte sonst eine zweite Zeile im Server-Log vor (ASVS V7.3.1).
     */
    public static String commandLine(String raw) {
        if (raw == null) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                return null;
            }
        }
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);  // nur einer: WorldEdit „//set“ bleibt „/set“
        }
        s = s.trim().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    private static final Set<String> SETBLOCK_MODES = Set.of("destroy", "keep", "replace", "strict");
    private static final java.util.regex.Pattern SMALL_INT = java.util.regex.Pattern.compile("-?(?:\\d|1[0-6])");

    /**
     * Häufige Modellfehler bei setblock/fill/summon gerade ziehen, bevor der Befehl läuft. Vorfall dev01 2026-11-04
     * 20:23: „Stapel 10 TNT und zünde sie an“ → „setblock ~ ~ 1 tnt“ (y = 1 absolut, tief unter der Erde), dann
     * fünfmal „setblock ~ ~ ~-1 tnt 10“ (die 10 macht den Befehl ungültig) – der Spieler sah nichts passieren, Vera
     * wiederholte denselben Fehler, weil sie die Fehlermeldung nie sieht.
     *
     * Nur zwei Regeln, beide ohne Bedeutungsänderung eines gültigen Befehls:
     * – In einem Koordinatentripel mit mindestens einem „~“ wird eine nackte kleine Zahl (|n| ≤ 16) zu „~n“. Absolut
     *   gemischt mit größerer Zahl („~ 64 ~“: auf Höhe 64) bleibt – eine Höhe von 0 bis 16 neben „~“ meint das Modell
     *   nie, ein Versatz von 1 bis 16 dagegen ständig (der Fall oben).
     * – setblock: Wörter nach Block und Modus fallen weg („tnt 10“ → „tnt“).
     * Alles andere (Selektoren, ^, NBT) bleibt, wie es ist.
     */
    public static String repairCommand(String line) {
        if (line == null) {
            return null;
        }
        String[] w = line.split(" ");
        String cmd = w[0].toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        switch (cmd) {
            case "setblock" -> {
                relativeTriple(w, 1);
                if (w.length > 5) {
                    int keep = w.length >= 6 && SETBLOCK_MODES.contains(w[5].toLowerCase(Locale.ROOT)) ? 6 : 5;
                    w = java.util.Arrays.copyOf(w, keep);
                }
            }
            case "fill" -> {
                relativeTriple(w, 1);
                relativeTriple(w, 4);
            }
            case "summon" -> relativeTriple(w, 2);
            default -> {
                return line;
            }
        }
        return String.join(" ", w);
    }

    private static void relativeTriple(String[] w, int at) {
        if (w.length < at + 3) {
            return;
        }
        boolean relative = false;
        for (int i = at; i < at + 3; i++) {
            relative |= w[i].startsWith("~");
        }
        if (!relative) {
            return;
        }
        for (int i = at; i < at + 3; i++) {
            if (SMALL_INT.matcher(w[i]).matches()) {
                w[i] = "~" + w[i];
            }
        }
    }

    /**
     * Ohne Rückfrage läuft nur, was harmlos ist und höchstens den Spieler selbst betrifft (zweite Sicherheitsprüfung
     * 2026-11-08, H2). Vorher fragte Vera nur bei einer Sperrliste nach – „summon falling_block … {TileEntityData:
     * {Command:…,auto:1b}}“, „data merge block …“ oder „gamemode creative Mallory“ aus einer Modell-Antwort liefen
     * sofort als der Op, und ein Befehl, an den beim Schreiben der Liste keiner dachte, ebenso. Jetzt eine Freigabeliste
     * (OWASP A04, ASVS V5.1.3); alles andere, alles mit {Daten} oder [Bestandteilen], @a/@e/@r und WorldEdit (//…) erst
     * nach getipptem „ja“. Wer wieder eine Sperrliste nimmt, lässt jeden vergessenen Befehl ungefragt laufen.
     *
     * self: Name des Spielers, für den Vera handelt (Ziel „er selbst“); null = nur @s/@p und „ohne Ziel“ zählen.
     */
    public static boolean needsConfirmation(String line, String self) {
        String l = line.toLowerCase(Locale.ROOT).trim();
        if (l.isEmpty() || l.startsWith("/") || l.contains("{") || l.contains("}")) {
            return true;
        }
        if (l.matches("(?s).*(^|\\s)@[aer](\\[.*|\\s.*|$)")) {
            return true;
        }
        String[] w = l.split("\\s+");
        String first = w[0];
        int colon = first.indexOf(':');
        if (colon >= 0) {
            String ns = first.substring(0, colon);
            if (!ns.equals("minecraft") && !ns.equals("essentials")) {
                return true;
            }
            first = first.substring(colon + 1);
        }
        String me = self == null ? null : self.toLowerCase(Locale.ROOT);
        return !switch (first) {
            // Nur Anzeige, Chat oder die ganze Welt gleich für alle (Tageszeit, Wetter) – nichts davon trifft einen
            // anderen Spieler oder baut etwas.
            case "time", "weather", "say", "me", "msg", "tell", "w", "list", "seed", "help", "locate" -> true;
            case "gamemode", "gm" -> w.length <= 3 && selfTarget(w, 2, me);
            case "gmc", "gms", "gma", "gmsp", "fly", "heal", "feed", "spawn", "spawnpoint" ->
                    w.length <= 2 && selfTarget(w, 1, me);
            case "effect" -> w.length >= 3 && (w[1].equals("give") || w[1].equals("clear")) && selfTarget(w, 2, me);
            case "xp", "experience" -> w.length >= 3 && (w[1].equals("add") || w[1].equals("set")
                    || w[1].equals("query")) && selfTarget(w, 2, me);
            // Geben nur sich selbst und ohne Bestandteile ([…] bzw. {…} oben): ein Op kann sich alles selbst nehmen.
            case "give" -> w.length >= 3 && w.length <= 4 && selfTarget(w, 1, me) && !w[2].contains("[");
            case "tp", "teleport" -> w.length == 2 || w.length == 4 && coordinates(w, 1) || w.length >= 3
                    && selfTarget(w, 1, me);
            case "mv" -> w.length == 3 && w[1].equals("tp");
            case "plot", "p", "p2" -> w.length == 2 && (w[1].equals("home") || w[1].equals("h"));
            default -> false;
        };
    }

    /** Zeile ohne Spielername (Tests, ältere Aufrufe): nur @s/@p und „ohne Ziel“ gelten als der Spieler selbst. */
    public static boolean needsConfirmation(String line) {
        return needsConfirmation(line, null);
    }

    /** Ist w[at] (falls vorhanden) der Spieler selbst? Fehlt das Ziel, meint der Befehl ihn auch. */
    private static boolean selfTarget(String[] w, int at, String self) {
        if (w.length <= at) {
            return true;
        }
        String t = w[at];
        return t.equals("@s") || t.equals("@p") || self != null && t.equals(self);
    }

    private static boolean coordinates(String[] w, int from) {
        for (int i = from; i < from + 3 && i < w.length; i++) {
            if (!w[i].matches("[~^]?-?\\d*(\\.\\d+)?") || w[i].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Quader, den ein Befehl verändert (Blockkoordinaten, beide Ecken eingeschlossen), und der neue Block. */
    public record Area(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        public long volume() {
            return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        }
    }

    /**
     * Bereich von fill, setblock und clone – für die Rückfrage in Worten und für „mach das rückgängig“. Vorfall
     * 2026-11-04 12:19: „kannst du die Anlage hier reparieren?“ → „fill ~-5 ~ ~-5 ~5 ~5 ~5 redstone“, Rückfrage nur
     * „Soll ich wirklich /fill … ausführen?“ – gesprochen unverständlich, und CoreProtect protokolliert vanilla-fill
     * nicht. Scheiterte nur, weil „redstone“ kein Block ist.
     *
     * Koordinaten absolut oder mit ~ (relativ zum Spieler); ^ (Blickrichtung) und Selektoren → null (dann keine
     * Sicherung, aber die Rückfrage bleibt).
     */
    public static Area changedArea(String line, int px, int py, int pz) {
        if (line == null) {
            return null;
        }
        String[] w = line.trim().split(" ");
        String cmd = w[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
        }
        int[] base = {px, py, pz};
        switch (cmd) {
            case "fill" -> {
                int[] a = w.length >= 8 ? coords(w, 1, base) : null;
                int[] c = w.length >= 8 ? coords(w, 4, base) : null;
                return a == null || c == null ? null : box(a, c, blockName(w[7]));
            }
            case "setblock" -> {
                int[] a = w.length >= 5 ? coords(w, 1, base) : null;
                return a == null ? null : box(a, a, blockName(w[4]));
            }
            case "clone" -> {
                int[] a = w.length >= 10 ? coords(w, 1, base) : null;
                int[] c = w.length >= 10 ? coords(w, 4, base) : null;
                int[] d = w.length >= 10 ? coords(w, 7, base) : null;
                if (a == null || c == null || d == null) {
                    return null;
                }
                int[] far = {d[0] + Math.abs(c[0] - a[0]), d[1] + Math.abs(c[1] - a[1]), d[2] + Math.abs(c[2] - a[2])};
                return box(d, far, "");
            }
            default -> {
                return null;
            }
        }
    }

    /** Was der Spieler vor dem „ja“ hört: Zahl der Blöcke und was daraus wird. */
    public static String describeChange(Area a) {
        String n = a.volume() == 1 ? "einen Block" : a.volume() + " Blöcke";
        if (a.block().equals("air") || a.block().equals("cave_air")) {
            return "Das entfernt " + n + " um dich herum.";
        }
        if (a.block().isEmpty()) {
            return "Das überschreibt " + n + ".";
        }
        return "Das macht " + n + " um dich herum zu " + a.block().replace('_', ' ') + ".";
    }

    private static Area box(int[] a, int[] c, String block) {
        return new Area(Math.min(a[0], c[0]), Math.min(a[1], c[1]), Math.min(a[2], c[2]), Math.max(a[0], c[0]),
                Math.max(a[1], c[1]), Math.max(a[2], c[2]), block);
    }

    private static String blockName(String s) {
        String b = s.toLowerCase(Locale.ROOT);
        int state = b.indexOf('[');
        if (state >= 0) {
            b = b.substring(0, state);
        }
        return b.startsWith("minecraft:") ? b.substring("minecraft:".length()) : b;
    }

    private static int[] coords(String[] w, int at, int[] base) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            Integer v = coord(w[at + i], base[i]);
            if (v == null) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    static Integer coord(String s, int base) {
        try {
            if (s.startsWith("^")) {
                return null;
            }
            if (s.startsWith("~")) {
                String r = s.substring(1);
                return base + (r.isEmpty() ? 0 : (int) Math.floor(Double.parseDouble(r)));
            }
            return (int) Math.floor(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;  // Selektor oder Tippfehler: kein Bereich, Befehl läuft trotzdem wie getippt
        }
    }

    public static boolean isOre(String blockId) {
        String b = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return b.endsWith("_ore") || b.equals("ancient_debris") || b.equals("raw_iron_block")
                || b.equals("raw_gold_block") || b.equals("raw_copper_block");
    }

    /**
     * Richtung vom Spieler zum Ziel in Worten: Himmelsrichtung (Minecraft: −z = Norden, +x = Osten) und Höhe.
     * „etwa 23 Blöcke nordöstlich, 12 tiefer“.
     */
    public static String direction(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        String where;
        if (horizontal < 2) {
            where = "direkt hier";
        } else {
            double deg = Math.toDegrees(Math.atan2(dx, -dz));  // 0 = Norden, 90 = Osten
            if (deg < 0) {
                deg += 360;
            }
            String[] names = {"nördlich", "nordöstlich", "östlich", "südöstlich", "südlich", "südwestlich",
                    "westlich", "nordwestlich"};
            where = "etwa " + Math.round(horizontal) + " Blöcke " + names[(int) Math.round(deg / 45) % 8];
        }
        long h = Math.round(dy);
        String height = h <= -2 ? ", " + (-h) + " tiefer" : h >= 2 ? ", " + h + " höher" : "";
        return where + height;
    }

    /** Minecraft-Zeit (0 = 6:00 Uhr, 24000 Ticks je Tag) als „14:30“. */
    public static String clock(long ticks) {
        long t = Math.floorMod(ticks + 6000, 24000);
        long hours = t / 1000;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%d:%02d", hours, minutes);
    }
}
