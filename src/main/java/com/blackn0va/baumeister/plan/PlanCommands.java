package com.blackn0va.baumeister.plan;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Befehle in einem Bauplan finden (Plan B5, Vorarbeit): Blockdaten der Stufe „Befehle“, die aus Plantext stammen (das
 * Modell oder bauenbei schreibt sie), nicht aus einem Bauteil oder Code.
 *
 * Solche Zellen setzt der Bau nur, nachdem der Auftraggeber (Admin oder Konsole) genau diese Liste angeklickt hat
 * (B5: „Der Admin bekommt alle Befehle aufgelistet und muss sie anklicken und bestätigen“; BuildService.startJob,
 * CommandConfirmations), sonst gar nicht (fail-closed): Ein Modell, das über die Recherche (Wikipedia) einen fremden
 * Text untergeschoben bekommt, könnte sonst einem Admin einen Befehlsblock mit „op …“ in den Bau legen. Wer das ohne
 * Bestätigung freigibt, öffnet genau diesen Weg. Dazu ist „immer aktiv“ (auto:1b) aus Plantext verboten
 * ({@link #withoutAutoStart}): Auch ein bestätigter Befehl läuft erst, wenn jemand einen Knopf oder Hebel betätigt.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class PlanCommands {

    private PlanCommands() {
    }

    /**
     * Eine Zelle mit Befehls-Daten: Planposition, Block, Grund (BlockTiers), der Befehl, falls einer drinsteht, ob das
     * Modell „immer aktiv“ verlangt hat (wird auf „braucht Redstone“ gesetzt) und – falls die Zelle nie bestätigt werden
     * kann – warum nicht (refusal, sonst null).
     */
    public record Found(Pos pos, String block, String reason, String command, boolean autoStart, String refusal) {

        public boolean confirmable() {
            return refusal == null;
        }
    }

    /**
     * Was ein Befehlsblock aus Plantext tragen darf, um bestätigbar zu sein (Sicherheitsprüfung 2026-11-08, H1): nur
     * der Befehl und auto. Vorher zeigte die Liste bei Spawner-Daten, Bienen, Klick-Texten oder einem CustomName nur
     * eine Kategorie („Daten eines Admin-Blocks“), gesetzt wurde nach dem Klick aber alles – samt verschachteltem auto:1b
     * in einem fallenden Befehlsblock. Was die Liste nicht vollständig zeigen kann, kann man nicht bestätigen.
     */
    private static final Set<String> CONFIRMABLE_KEYS = Set.of("Command", "auto");

    /**
     * Daneben im Plan liefe ein bestätigter Befehl von selbst los (M3): Dauerstrom, Takt oder Melder, die beim Bauen
     * selbst auslösen (Beobachter, Sculk-Sensoren reagieren auf die gesetzten Blöcke). Knopf, Hebel aus und
     * Druckplatte aus sind Auslöser von Hand – die sind erlaubt (Befehlsknopf).
     */
    private static final Set<String> POWER_SOURCES = Set.of("redstone_block", "redstone_torch", "redstone_wall_torch",
            "observer", "daylight_detector", "repeater", "comparator", "redstone_wire", "sculk_sensor",
            "calibrated_sculk_sensor", "target", "lightning_rod", "detector_rail");

    /** Ein Block, der den Befehlsblock daneben von selbst auslösen kann (Plan-Nachbar); null = keiner. */
    static String powerSource(BlockSpec s) {
        if (s == null) {
            return null;
        }
        if (POWER_SOURCES.contains(s.id())) {
            return s.id();
        }
        if ("true".equals(s.props().get("powered")) || s.props().containsKey("power")
                && !"0".equals(s.props().get("power"))) {
            return s.id() + " (an)";
        }
        return null;
    }

    /**
     * Warum diese Zelle (Plantext, Stufe Befehle) nie bestätigt werden kann; null = bestätigbar. Nur Impuls- und
     * Ketten-Befehlsblöcke mit genau Command (und auto). Wiederhol-Befehlsblöcke nie (M3): Einer mit Strom daneben
     * liefe jeden Takt, und „braucht Redstone“ schützt dann nicht.
     */
    static String refusal(BlockSpec s) {
        if (!s.id().equals("command_block") && !s.id().equals("chain_command_block")) {
            return s.id().equals("repeating_command_block") ? "Wiederhol-Befehlsblöcke setze ich aus einem Bauplan nie"
                    : "solche Daten setze ich aus einem Bauplan nie (" + BlockTiers.nbt(s.id(), s.nbt()).reason() + ")";
        }
        if (!(s.nbt().get("Command") instanceof Nbt.Str)) {
            return "Befehlsblock ohne Befehl, aber mit anderen Daten";
        }
        for (String key : s.nbt().entries().keySet()) {
            if (!CONFIRMABLE_KEYS.contains(key)) {
                return "Befehlsblock mit weiteren Daten (" + key + ") – nur Befehl und auto sind bestätigbar";
            }
        }
        return null;
    }

    /** Braucht diese Zelle die Bestätigung eines Admins? Daten aus Plantext mit Stufe COMMANDS. */
    public static boolean needsConfirmation(BlockSpec spec) {
        return spec.nbt() != null && !spec.trustedNbt()
                && BlockTiers.nbt(spec.id(), spec.nbt()).tier() == Tier.COMMANDS;
    }

    /**
     * Platzhalter {x,y,z} in Befehlen sind Plankoordinaten und müssen mit dem Plan wandern – sonst zeigte ein
     * Befehlsblock nach dem Verschieben (Gedächtnis, Schnellauftrag zur Seite, Plot, Anbau an die Wand) auf die alte
     * Stelle. Nur Blöcke mit Daten; ein unlesbarer Block bleibt, wie er ist (der Compiler meldet ihn).
     */
    public static void shiftPlaceholders(JsonObject step, int dx, int dy, int dz) {
        if (!step.has("block") || !step.get("block").isJsonPrimitive()) {
            return;
        }
        String raw = step.get("block").getAsString();
        if (raw.indexOf('{') < 0) {
            return;
        }
        try {
            BlockSpec spec = BlockSpec.parse(raw);
            BlockSpec moved = spec.mapPlaceholders(q -> q.add(dx, dy, dz));
            if (moved != spec) {
                step.addProperty("block", moved.toPlanText());
            }
        } catch (PlanException e) {
            // Unlesbar bleibt unlesbar – der Compiler lässt den Schritt dann mit Meldung aus (PlanCompiler.emit).
            return;
        }
    }

    /**
     * Alle Zellen eines Baus mit Befehls-Daten aus Plantext – für die Liste an den Admin (B5). Bestätigbar sind nur die
     * ohne refusal (reine Befehlsblöcke, keine Stromquelle im Plan daneben); die übrigen fallen immer weg. Ob in der
     * WELT Strom anliegt, prüft BuildService beim Fragen und beim Bauen.
     */
    public static List<Found> find(CompiledBuild build) {
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        List<Found> out = new ArrayList<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                BlockSpec s = c.block();
                if (!needsConfirmation(s)) {
                    continue;
                }
                String cmd = s.nbt().get("Command") instanceof Nbt.Str str ? str.value() : null;
                String refusal = refusal(s);
                if (refusal == null) {
                    for (Pos d : NEIGHBOURS) {
                        String src = powerSource(cells.get(c.pos().add(d.x(), d.y(), d.z())));
                        if (src != null) {
                            refusal = "Stromquelle daneben im Plan (" + src + ") – liefe beim Bauen von selbst los";
                            break;
                        }
                    }
                }
                out.add(new Found(c.pos(), s.id(), BlockTiers.nbt(s.id(), s.nbt()).reason(), cmd, autoStart(s),
                        refusal));
            }
        }
        return out;
    }

    private static final List<Pos> NEIGHBOURS = List.of(new Pos(1, 0, 0), new Pos(-1, 0, 0), new Pos(0, 1, 0),
            new Pos(0, -1, 0), new Pos(0, 0, 1), new Pos(0, 0, -1));

    /**
     * Prüfsumme über genau die bestätigbaren Zellen (find, ohne refusal): Planposition, Block samt Zuständen und die neu
     * geschriebenen Daten. Die Bestätigung gilt nur für diese Fassung – ändert sich der Plan zwischen Liste und Klick
     * (anderer Bauplatz, neu geplant), passt sie nicht mehr, und es wird neu gefragt. Leer bei keiner solchen Zelle.
     */
    public static String fingerprint(CompiledBuild build) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in der JVM", e);
        }
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        int n = 0;
        for (Found f : find(build)) {
            if (!f.confirmable()) {
                continue;
            }
            BlockSpec s = cells.get(f.pos());
            String line = f.pos().x() + "," + f.pos().y() + "," + f.pos().z() + "|" + s.asString() + "|" + s.nbtText()
                    + '\n';
            md.update(line.getBytes(StandardCharsets.UTF_8));
            n++;
        }
        return n == 0 ? "" : HexFormat.of().formatHex(md.digest());
    }

    /**
     * Befehl so, wie er wirklich läuft, aber lesbar für die Bestätigung: ungekürzt, und alles außer Buchstaben,
     * Ziffern, Satz- und Sonderzeichen sowie dem Leerzeichen als \\uXXXX. Sonst zeigte die Liste etwas anderes, als
     * bestätigt wird: § färbt die Chatzeile um, eine Richtungsumkehr (U+202E) dreht den Text, Nullbreiten-Zeichen
     * verstecken Teile (OWASP A04). Wer hier wieder kürzt oder Zeichen durchlässt, lässt den Admin Unsichtbares
     * bestätigen.
     */
    public static String visible(String command) {
        StringBuilder sb = new StringBuilder(command.length());
        command.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean plain = cp == ' ' || cp != 0xA7 && switch (type) {
                case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                        Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
                        Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION,
                        Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
                default -> false;
            };
            if (plain) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(cp <= 0xFFFF ? String.format("\\u%04X", cp) : String.format("\\u{%X}", cp));
            }
        });
        return sb.toString();
    }

    /**
     * Verlangt der Plantext „immer aktiv“ (auto ungleich 0) an einem Block, der damit von selbst losläuft? Das sind
     * Impuls- (läuft beim Setzen einmal) und Wiederhol-Befehlsblöcke (jeden Takt). Ein Ketten-Befehlsblock läuft auch
     * mit auto:1b nur, wenn der Block davor auslöst – ohne auto:1b liefe keine Kette, deshalb bleibt er (B7, 2026-11-08).
     * Daten aus Bauteilen und Code zählen nicht.
     */
    public static boolean autoStart(BlockSpec s) {
        if (s.nbt() == null || s.trustedNbt() || !s.nbt().has("auto") || s.id().equals("chain_command_block")) {
            return false;
        }
        return !(s.nbt().get("auto") instanceof Nbt.Num num && num.isInteger() && num.asLong() == 0);
    }

    /**
     * „Immer aktiv“ aus Plantext auf „braucht Redstone“ (auto:0b) – Gedächtnis 2026-11-08: „auto:1b vom LLM ist
     * verboten“. Ein bestätigter Befehl läuft so nie von selbst los, schon gar nicht in einer Schleife. Wer das
     * entfernt, lässt das Modell einen sich selbst auslösenden Wiederholungs-Befehlsblock bauen.
     */
    public static BlockSpec withoutAutoStart(BlockSpec s) {
        if (!autoStart(s)) {
            return s;
        }
        return new BlockSpec(s.id(), s.props(), s.explicit(), s.nbt().with("auto", Nbt.Num.ofByte(0)), false,
                s.pending());
    }
}
