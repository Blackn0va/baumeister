package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Vorarbeit einer Etappe für CityJob, im Hintergrund statt im Bukkit-Takt: Zellen jedes Abschnitts nach Chunks
 * ordnen, das Rechteck jedes Abschnitts und die Liste der vorzubestellenden Chunks rechnen.
 *
 * Vorher lief das Rechteck im Hauptthread: orderChunks ging beim Übernehmen einer Etappe über jede Zelle (gemessen
 * 2026-10-29 mit JFR, Bad Kreuznach 400 m: 7 von 127 Proben des Stadtbaus im Server-Thread lagen in orderChunks), und
 * die Zellen kamen in der Reihenfolge des Planers – innerhalb einer Schicht in Einfügereihenfolge der Karte, also
 * kreuz und quer über bis zu neun Chunks. Jetzt: je Schicht (y bleibt die erste Sortierstufe – Häuser wachsen weiter
 * von unten nach oben, TerrainFit setzt Sockel vor Wände) Chunk für Chunk, darin Zeile für Zeile. Innerhalb eines
 * Abschnitts ändert das keinen Block: Jede Stelle ist dort höchstens einmal belegt (CityPlanner sammelt über eine Map
 * je Stelle), gesetzt wird ohne Physik. Die Sortierung ist stabil; selbst doppelte Stellen behielten ihre Reihenfolge.
 * Zwischen Abschnitten sorgt CityJob über die Rechtecke (box) für die Reihenfolge des Plans.
 *
 * Wer das wieder in den Hauptthread zieht, kostet jede Etappenübernahme einen Gang über Hunderttausende Zellen im
 * Takt.
 *
 * Threads: rein, beliebig (CityJob ruft es im ForkJoin-Pool). Fehler: keine.
 */
final class StagePrep {

    /**
     * Geplante Etappe: Abschnitte mit geordneten Zellen, je Abschnitt sein Rechteck {minX, minZ, maxX, maxZ} in
     * Plankoordinaten (gleiche Reihenfolge wie plan.sections(), null für leere Abschnitte) und die Chunks in
     * Bau-Reihenfolge (Schlüssel wie CityJob: cx << 32 ^ cz).
     */
    record Prepared(CityPlanner.Plan plan, List<int[]> boxes, long[] chunks, CityRaster.Raster raster) {
    }

    private StagePrep() {
    }

    /** baseX/baseZ: Weltkoordinate des Planursprungs (Zelle x/z liegt bei baseX + x / baseZ + z). */
    static Prepared prepare(CityPlanner.Plan p, int baseX, int baseZ) {
        return prepare(p, baseX, baseZ, 0);
    }

    /** raster: wie unten mit 1 Block je Zelle (true) bzw. ohne Draufsicht (false). */
    static Prepared prepare(CityPlanner.Plan p, int baseX, int baseZ, boolean raster) {
        return prepare(p, baseX, baseZ, raster ? 1 : 0);
    }

    /**
     * rasterCell &gt; 0: Draufsicht für die Stadtansicht im Host mitrechnen (CityRaster, mindestens so viele Blöcke je
     * Zelle) – hier im Hintergrund, nie im Takt. 0 = ohne.
     */
    static Prepared prepare(CityPlanner.Plan p, int baseX, int baseZ, int rasterCell) {
        boolean raster = rasterCell > 0;
        List<Section> sections = new ArrayList<>(p.sections().size());
        List<int[]> boxes = new ArrayList<>(p.sections().size());
        for (Section s : p.sections()) {
            sections.add(new Section(s.label(), s.cx(), s.cz(), chunkOrder(s.cells(), baseX, baseZ), s.building()));
            boxes.add(box(s.cells()));
        }
        // Schilder mitnehmen: Vorher rief das den Konstruktor ohne signs (Zusammenführung Tempo/Stadt 2026-10-29),
        // die Straßenschilder standen danach ohne Text (Testserver, Bad Kreuznach: Schild bei -3105/-57/-3060 leer,
        // keine einzige Zeile von SignWriter im Log).
        CityPlanner.Plan ordered = new CityPlanner.Plan(p.palette(), List.copyOf(sections), p.buildings(), p.roads(),
                p.blocks(), p.minX(), p.minZ(), p.maxX(), p.maxZ(), p.signs());
        return new Prepared(ordered, java.util.Collections.unmodifiableList(boxes), chunks(boxes, baseX, baseZ),
                raster ? rasterOrNull(p, baseX, baseZ, rasterCell) : null);
    }

    /**
     * Die Draufsicht ist reine Anzeige im Host und darf den Bau nie aufhalten. Vorher warf ein Fehler im Raster aus
     * prepare heraus – die Etappe galt als gescheitert, wurde zweimal wiederholt und dann übersprungen (Weltbau-Kachel
     * 366/6584 auf dem Testserver 2026-10-29: 0 Blöcke). Jetzt: Fehler loggen, Etappe ohne Raster bauen. Bewusst
     * fail-open – es geht um eine Karte, nicht um Sicherheit.
     */
    private static CityRaster.Raster rasterOrNull(CityPlanner.Plan p, int baseX, int baseZ, int cell) {
        try {
            return CityRaster.of(p, baseX, baseZ, cell);
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger("Baumeister").log(java.util.logging.Level.WARNING,
                    "gui | raster_fehler | etappe ohne Draufsicht", e);
            return null;
        }
    }

    /** Stabil sortiert nach (y, Chunk-x, Chunk-z, z, x) in Weltkoordinaten. */
    static List<Cell> chunkOrder(List<Cell> cells, int baseX, int baseZ) {
        Cell[] a = cells.toArray(new Cell[0]);
        Arrays.sort(a, Comparator.comparingInt(Cell::y)
                .thenComparingInt((Cell c) -> (baseX + c.x()) >> 4)
                .thenComparingInt((Cell c) -> (baseZ + c.z()) >> 4)
                .thenComparingInt(Cell::z)
                .thenComparingInt(Cell::x));
        return Arrays.asList(a);
    }

    /** Rechteck {minX, minZ, maxX, maxZ} der Zellen (einschließlich), null ohne Zellen. */
    static int[] box(List<Cell> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /** Berühren sich zwei Rechtecke (null = leer, berührt nichts)? */
    static boolean overlaps(int[] a, int[] b) {
        return a != null && b != null && a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    /**
     * Darf ein Arbeiter den Abschnitt mit Rechteck next jetzt beginnen, während die anderen an running arbeiten?
     *
     * Gemessen 2026-10-29 (Testserver, Bad Kreuznach 400 m, 4 Helfer): zwei Läufe desselben Stands unterschieden sich
     * in 18 Chunks, zwei Stände mit anderem Tick-Budget in 94–98 Chunks – 958 Blöcke, fast alle Wandfarben an Stellen,
     * die zwei Gebäude teilen. Ursache: Mehrere Arbeiter bauen Abschnitte gleichzeitig; wer eine geteilte Stelle
     * zuletzt setzt, hing vom Takt ab, nicht vom Plan. Dabei legt CityPlanner die Reihenfolge bewusst fest
     * (Gebäudeteile nach den Gebäuden, „sie überschreiben das Grundgebäude an ihrer Stelle“) – ein Turm konnte vom
     * langsameren Schiff überschrieben werden. Jetzt wartet ein Abschnitt, solange ein früherer, dessen Rechteck seines
     * berührt, noch in Arbeit ist. Weil die Warteschlange in Planreihenfolge abgearbeitet wird, ist jeder laufende
     * Abschnitt früher im Plan; damit entspricht das Ergebnis dem Bau nacheinander, egal wie viele Helfer. Wer die
     * Prüfung entfernt, macht das Ergebnis wieder vom Takt abhängig.
     */
    static boolean blocked(int[] next, List<int[]> running) {
        for (int[] r : running) {
            if (overlaps(next, r)) {
                return true;
            }
        }
        return false;
    }

    /** Wie weit ein Arbeiter in der Warteschlange nach einem freien Abschnitt sucht. */
    static final int LOOK_AHEAD = 64;

    /**
     * Nächsten Abschnitt nehmen, der jetzt beginnen darf: der erste unter den vordersten LOOK_AHEAD, dessen Rechteck
     * weder einen laufenden noch einen vor ihm wartenden Abschnitt berührt. Er wird aus der Warteschlange entfernt;
     * null = keiner frei (Arbeiter wartet diesen Tick).
     *
     * Gemessen 2026-10-29 (Bad Kreuznach 400 m, vorerzeugte Chunks, 4 Helfer): Nur den vordersten zu nehmen und
     * sonst zu warten (blocked allein) hielt die Reihenfolge, kostete aber ein Drittel Tempo – 75–78 s statt 55–57 s,
     * weil der Planer Gebäude von der Mitte nach außen reiht und Nachbarhäuser mit gemeinsamer Wand hintereinander
     * stehen. Vorziehen ist nur erlaubt, wenn der Abschnitt mit keinem früheren unfertigen etwas teilt; dann ist für
     * jede geteilte Stelle die Reihenfolge des Plans weiter gesichert (frühere laufen schon oder warten selbst auf
     * ihn nicht). Wer die Prüfung gegen die wartenden weglässt, lässt ein späteres Haus vor dem früheren Nachbarn
     * bauen – der frühere überschriebe dann die gemeinsame Wand.
     */
    static <T> T takeNext(Deque<T> queue, Function<T, int[]> box, List<int[]> running) {
        List<int[]> earlier = new ArrayList<>();
        int seen = 0;
        for (Iterator<T> it = queue.iterator(); it.hasNext() && seen < LOOK_AHEAD; seen++) {
            T t = it.next();
            int[] b = box.apply(t);
            if (!blocked(b, running) && !blocked(b, earlier)) {
                it.remove();
                return t;
            }
            if (b != null) {
                earlier.add(b);
            }
        }
        return null;
    }

    /**
     * Chunks, die die Rechtecke berühren, in Abschnittsreihenfolge, jeder einmal (wie vorher orderChunks in CityJob,
     * leere Abschnitte ausgenommen).
     */
    static long[] chunks(List<int[]> boxes, int baseX, int baseZ) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (int[] b : boxes) {
            if (b == null) {
                continue;
            }
            for (int cx = (baseX + b[0]) >> 4; cx <= (baseX + b[2]) >> 4; cx++) {
                for (int cz = (baseZ + b[1]) >> 4; cz <= (baseZ + b[3]) >> 4; cz++) {
                    out.add(key(cx, cz));
                }
            }
        }
        long[] r = new long[out.size()];
        int i = 0;
        for (long k : out) {
            r[i++] = k;
        }
        return r;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
