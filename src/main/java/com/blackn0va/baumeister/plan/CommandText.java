package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Befehle in Befehlsblöcken eines Bauplans (Plan B2): Platzhalter und relative Koordinaten.
 *
 * Ein Bau wird gedreht und gespiegelt, ein Befehl darin nicht. „setblock ~3 ~ ~“ zeigt nach dem Drehen auf eine
 * andere Stelle als geplant, „^“ (lokal zur Blickrichtung des Befehlsblocks) ebenso. Deshalb nennen Pläne Stellen über
 * Platzhalter in Plankoordinaten: {@code {x,y,z}} ist die Blockecke (wird zu „X Y Z“), {@code {@x,y,z}} die Blockmitte
 * (wird zu „X.5 Y.5 Z.5“). Die rechnet der Bau mit derselben Drehung in Weltkoordinaten um wie die Blöcke selbst.
 * „~“ bleibt erlaubt, wenn x und z keinen Versatz haben (die Höhe dreht nicht), „^“ nie.
 *
 * Ersetzt wird nur im geparsten Command-Wert eines Befehlsblocks (Nbt-Baum), nie im Rohtext eines Plans – sonst
 * ließen sich Platzhalter in Schlüssel oder andere Werte schmuggeln.
 *
 * Threads: zustandslos. Fehler: PlanException mit deutschem Satz.
 */
public final class CommandText {

    /** Länge, die Minecraft einem Befehlsblock höchstens lässt. */
    public static final int MAX_LENGTH = 32_500;
    /** Platzhalter: {x,y,z} oder {@x,y,z}, ganze Zahlen ohne Leerraum. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(@?)(-?\\d{1,6}),(-?\\d{1,6}),(-?\\d{1,6})\\}");
    private static final Pattern RELATIVE = Pattern.compile("~(-?(?:\\d+\\.?\\d*|\\.\\d+))?");
    private static final Pattern NUMBER = Pattern.compile("-?(?:\\d+\\.?\\d*|\\.\\d+)");

    private CommandText() {
    }

    /** Ein Platzhalter: center = {@…} (Blockmitte), sonst Blockecke. */
    public record Placeholder(boolean center, Pos pos) {
    }

    /**
     * Prüft einen Befehl aus einem Bauplan: Länge, keine Steuerzeichen, kein „^“, „~“ nur ohne Versatz in x und z.
     * Liefert den Befehl unverändert zurück.
     */
    public static String check(String command) throws PlanException {
        if (command.length() > MAX_LENGTH) {
            throw new PlanException("Befehl zu lang: " + command.length() + " Zeichen, erlaubt sind " + MAX_LENGTH + ".");
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new PlanException("Befehl mit Steuerzeichen.");
            }
        }
        String[] tokens = command.trim().split("\\s+");
        List<String> run = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("^")) {
                throw new PlanException("Befehl mit ^-Koordinaten (" + t + "): Die drehen nicht mit dem Bau – nimm "
                        + "{x,y,z} in Plankoordinaten.");
            }
            if (RELATIVE.matcher(t).matches() || NUMBER.matcher(t).matches()) {
                run.add(t);
                continue;
            }
            checkRun(run);
            run.clear();
        }
        checkRun(run);
        return command;
    }

    /**
     * Folge von Koordinaten-Wörtern: je drei sind x y z. x und z dürfen relativ nur ohne Versatz sein; was nicht in ein
     * Dreier passt (Drehwinkel „~90 ~“), darf gar keinen relativen Versatz haben.
     */
    private static void checkRun(List<String> run) throws PlanException {
        int full = run.size() / 3 * 3;
        for (int i = 0; i < run.size(); i++) {
            String t = run.get(i);
            boolean y = i < full && i % 3 == 1;
            if (!y && hasOffset(t)) {
                throw new PlanException("Befehl mit relativem Versatz in x oder z (" + t + "): Der dreht nicht mit dem "
                        + "Bau – nimm {x,y,z} in Plankoordinaten.");
            }
        }
    }

    private static boolean hasOffset(String token) {
        Matcher m = RELATIVE.matcher(token);
        if (!m.matches() || m.group(1) == null) {
            return false;
        }
        return Double.parseDouble(m.group(1)) != 0;
    }

    public static List<Placeholder> placeholders(String command) {
        List<Placeholder> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(command);
        while (m.find()) {
            out.add(new Placeholder(!m.group(1).isEmpty(), new Pos(Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)))));
        }
        return out;
    }

    /** Platzhalter umrechnen und als Platzhalter stehen lassen (Bauteil drehen, verschieben, spiegeln). */
    public static String mapPositions(String command, UnaryOperator<Pos> map) {
        return replace(command, (center, p) -> {
            Pos q = map.apply(p);
            return "{" + (center ? "@" : "") + q.x() + "," + q.y() + "," + q.z() + "}";
        });
    }

    /**
     * Platzhalter durch Weltkoordinaten ersetzen. toWorld bildet eine Planposition auf die Weltposition des Blocks ab
     * ({x, y, z} als int[3]).
     */
    public static String render(String command, Function<Pos, int[]> toWorld) {
        return replace(command, (center, p) -> {
            int[] w = toWorld.apply(p);
            return center ? w[0] + ".5 " + w[1] + ".5 " + w[2] + ".5" : w[0] + " " + w[1] + " " + w[2];
        });
    }

    private interface Replacer {
        String apply(boolean center, Pos p);
    }

    private static String replace(String command, Replacer r) {
        Matcher m = PLACEHOLDER.matcher(command);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Pos p = new Pos(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(!m.group(1).isEmpty(), p)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
