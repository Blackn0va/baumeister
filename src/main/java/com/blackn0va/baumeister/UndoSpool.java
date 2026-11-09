package com.blackn0va.baumeister;

import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.block.data.BlockData;

/**
 * Rückgängig-Daten eines Stadtbaus auf der Platte statt im Speicher.
 *
 * Vorher hielt CityJob je geänderten Block einen Eintrag (Block + BlockData, ~100 Byte) im Speicher und schaltete
 * Rückgängig ab 500 000 Blöcken ab – auf dev01 schon beim Dorf Hepberg (504 468 Blöcke, 2026-10-28). Eine Großstadt hat
 * zig Millionen. Jetzt: je PART Einträge eine gzip-Teildatei (x, y, z, Zustands-Nr.; die Zustände als Text mit
 * eigenem Wörterbuch je Teil). Zurückgesetzt wird Teil für Teil von hinten, innerhalb eines Teils rückwärts – so
 * gewinnt bei doppelt gesetzten Blöcken (Boden, dann Haus) der echte Ursprungszustand. Im Speicher liegt dabei nur ein
 * Teil.
 *
 * Teilgröße: bis 2026-10-29 200 000 Einträge. Ein Teil wird beim Zurücksetzen in EINEM Tick gelesen; gemessen
 * (UndoSpoolProbe, Laptop, 1 Mio. Einträge) mit dem alten Leser 21–43 ms je Teil, mit dem neuen (read) 9–13 ms,
 * mit 50 000 je Teil 2–4 ms (kalt 13 ms) bei 0,2 % mehr Platte (3 323 statt 3 315 KB). Wer PART wieder anhebt,
 * verlängert die Tick-Spitze beim Rückgängig im gleichen Verhältnis – auf dev01 (Celeron) ein Mehrfaches.
 *
 * Threads: nur Hauptthread. Fehler: IOException beim Schreiben schaltet Rückgängig für diesen Bau ab (der Bau läuft
 * weiter – bewusst fail-open, ein voller Datenträger soll keine halbe Stadt stehen lassen).
 */
final class UndoSpool {

    static final int PART = 50_000;
    private static final int NEW_STATE = Integer.MIN_VALUE;

    /** Ein geladener Teil: Einträge in Schreibreihenfolge. */
    record Part(int[] x, int[] y, int[] z, String[] state, int size) {
    }

    private final Path dir;
    private DataOutputStream out;
    private final Map<String, Integer> dict = new HashMap<>();
    private static final int TEXT_CACHE = 4096;
    private final Map<BlockData, String> texts = new HashMap<>();
    private int inPart;
    private int parts;
    private long count;
    private boolean failed;

    UndoSpool(Path dir) {
        this.dir = dir;
    }

    /**
     * Ein schon geschriebener Ordner, nach einem Neustart vom UndoJournal geprüft: nur noch lesen und löschen. Schreiben
     * finge wieder bei teil-00001 an und überschriebe den ältesten Teil – deshalb gesperrt (readOnly).
     */
    static UndoSpool existing(Path dir, long count) {
        UndoSpool s = new UndoSpool(dir);
        s.count = count;
        s.readOnly = true;
        return s;
    }

    private boolean readOnly;

    Path dir() {
        return dir;
    }

    long count() {
        return count;
    }

    boolean failed() {
        return failed;
    }

    /**
     * Wie record(x, y, z, String), aber mit dem Zustand als BlockData: Der Text (getAsString) wird je Zustand nur
     * einmal gebaut. Vorher rief CityJob getAsString für jeden gesetzten Block – gemessen 2026-10-29 mit JFR
     * (Bad Kreuznach 400 m): 17 von 127 Proben des Stadtbaus im Server-Thread lagen im Rückgängig, davon 7 allein in
     * getAsString. Schlüssel ist die BlockData selbst: CraftBlockData vergleicht und hasht über den Zustand des
     * Servers (javap, 1.21.11), zwei Abfragen desselben Zustands treffen also denselben Eintrag.
     */
    boolean record(int x, int y, int z, BlockData state) {
        String text = texts.get(state);
        if (text == null) {
            text = state.getAsString();
            if (texts.size() >= TEXT_CACHE) {
                texts.clear();  // Ein Ort hat Dutzende Zustände; mehr heißt kaputte Daten – nicht unbegrenzt wachsen
            }
            texts.put(state, text);
        }
        return record(x, y, z, text);
    }

    /** false, wenn Rückgängig für diesen Bau aus ist (Schreibfehler). */
    boolean record(int x, int y, int z, String state) {
        if (readOnly) {
            throw new IllegalStateException("Rückgängig-Eintrag aus einem früheren Lauf ist schreibgeschützt: " + dir);
        }
        if (failed) {
            return false;
        }
        try {
            if (out == null || inPart >= PART) {
                closePart();
                Files.createDirectories(dir);
                parts++;
                out = new DataOutputStream(new BufferedOutputStream(gzip(Files.newOutputStream(
                        dir.resolve(String.format("teil-%05d.bin.gz", parts)))), 1 << 16));
                dict.clear();
                inPart = 0;
            }
            Integer id = dict.get(state);
            if (id == null) {
                id = dict.size();
                dict.put(state, id);
                out.writeInt(NEW_STATE);
                out.writeUTF(state);
            }
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeInt(id);
            inPart++;
            count++;
            return true;
        } catch (IOException e) {
            failed = true;
            closeQuietly();
            throw new IllegalStateException("Rückgängig-Datei nicht schreibbar: " + e.getMessage(), e);
        }
    }

    /** Schreiben beenden; danach sind die Teile vollständig lesbar. */
    void finish() {
        try {
            closePart();
        } catch (IOException e) {
            failed = true;
            throw new IllegalStateException("Rückgängig-Datei nicht abschließbar: " + e.getMessage(), e);
        }
    }

    private void closePart() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    private void closeQuietly() {
        try {
            closePart();
        } catch (IOException e) {
            // Schreiben ist schon gescheitert und gemeldet; der zweite Fehler ändert nichts mehr.
            out = null;
        }
    }

    /** Teildateien, die letzte zuerst. */
    List<Path> partsNewestFirst() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("teil-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
    }

    /**
     * Einen Teil lesen. Läuft beim Zurücksetzen im Hauptthread, innerhalb eines Ticks (BuildService.restoreSpool) –
     * die Dauer ist eine Tick-Spitze.
     *
     * Vorher: DataInputStream.readInt über einen BufferedInputStream, vier Aufrufe je Eintrag durch die Puffer-Sperre.
     * Gemessen 2026-10-29 (UndoSpoolProbe, Laptop, 200 000 Einträge je Teil): 21–43 ms je Teil, auf dev01 (Celeron)
     * ein Mehrfaches. Jetzt: erst ganz entpacken (ein Teil sind höchstens PART × 16 Byte plus Zustandstexte), dann aus
     * einem ByteBuffer lesen – gleiches Format (DataOutputStream schreibt big-endian, writeUTF = 2 Byte Länge +
     * modifiziertes UTF-8, gelesen über DataInputStream.readUTF wie bisher).
     */
    static Part read(Path file) throws IOException {
        return read(file, Integer.MAX_VALUE);
    }

    /**
     * Mit Obergrenze für Einträge und Zustände je Teil: IOException „teil_zu_groß“, sobald es mehr werden. Für Dateien
     * aus einem früheren Lauf (UndoJournal.check mit PART) – ohne Grenze verdoppelt read() seine Felder unbegrenzt, und
     * eine präparierte gzip-Datei (wenige KB, Milliarden Einträge) triebe den ganzen Server in einen OutOfMemoryError.
     * Seit read() den Teil erst ganz entpackt, greift die Grenze schon beim Entpacken (maxBytes): Sonst läge die
     * entpackte Bombe vollständig im Speicher, bevor der erste Eintrag gezählt ist.
     */
    static Part read(Path file, int maxEntries) throws IOException {
        byte[] raw;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            if (maxEntries == Integer.MAX_VALUE) {
                raw = in.readAllBytes();
            } else {
                int limit = maxBytes(maxEntries);
                raw = in.readNBytes(limit + 1);
                if (raw.length > limit) {
                    throw new IOException("teil_zu_groß");
                }
            }
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        List<String> states = new ArrayList<>();
        int cap = Math.max(16, raw.length / 16);
        int[] xs = new int[cap];
        int[] ys = new int[cap];
        int[] zs = new int[cap];
        String[] st = new String[cap];
        int n = 0;
        while (buf.remaining() >= 4) {
            int x = buf.getInt();
            if (x == NEW_STATE) {
                if (states.size() >= maxEntries) {
                    throw new IOException("teil_zu_groß");
                }
                // Prüfbefund 2026-10-29: Fehlten nach der Marke die 2 Byte Länge, warf getShort eine
                // IndexOutOfBoundsException – BuildService.restoreSpool fängt nur IOException, sie ginge aus dem Tick.
                if (buf.remaining() < 2) {
                    throw new EOFException("Rückgängig-Teil abgeschnitten: " + file.getFileName());
                }
                int len = Short.toUnsignedInt(buf.getShort(buf.position()));
                if (buf.remaining() < 2 + len) {
                    throw new EOFException("Rückgängig-Teil abgeschnitten: " + file.getFileName());
                }
                DataInputStream utf = new DataInputStream(new ByteArrayInputStream(raw, buf.position(), 2 + len));
                states.add(utf.readUTF());
                buf.position(buf.position() + 2 + len);
                continue;
            }
            if (n >= maxEntries) {
                throw new IOException("teil_zu_groß");
            }
            if (buf.remaining() < 12) {
                throw new EOFException("Rückgängig-Teil abgeschnitten: " + file.getFileName());
            }
            if (n == cap) {
                cap *= 2;
                xs = Arrays.copyOf(xs, cap);
                ys = Arrays.copyOf(ys, cap);
                zs = Arrays.copyOf(zs, cap);
                st = Arrays.copyOf(st, cap);
            }
            xs[n] = x;
            ys[n] = buf.getInt();
            zs[n] = buf.getInt();
            int id = buf.getInt();
            if (id < 0 || id >= states.size()) {
                throw new IOException("Rückgängig-Teil kaputt (Zustand " + id + "): " + file.getFileName());
            }
            st[n] = states.get(id);
            n++;
        }
        if (buf.hasRemaining()) {
            throw new EOFException("Rückgängig-Teil abgeschnitten: " + file.getFileName());
        }
        return new Part(xs, ys, zs, st, n);
    }

    /**
     * Höchste entpackte Größe eines Teils mit maxEntries Einträgen: 16 Byte je Eintrag plus 16 MiB für die
     * Zustandstexte (ein Teil von PART = 50 000 Einträgen hat in der Praxis einige hundert verschiedene Zustände zu je
     * unter 200 Byte). Für PART also ~17 MB statt unbegrenzt.
     */
    static int maxBytes(int maxEntries) {
        return (int) Math.min(Integer.MAX_VALUE - 16L, maxEntries * 16L + (16L << 20));
    }

    /** Ordner samt Teilen löschen (nach dem Zurücksetzen oder wenn der Eintrag aus dem Stapel fällt). */
    void delete() {
        closeQuietly();
        deleteDir(dir);
    }

    static void deleteDir(Path dir) {
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (isLink(dir)) {
                // Nur der Verweis selbst – nie das Ziel (siehe deleteTree).
                Files.deleteIfExists(dir);
                return;
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            deleteTree(dir);
        } catch (IOException e) {
            // Nur Plattenplatz; beim nächsten Start räumt BaumeisterPlugin den Ordner rückgängig/ erneut.
            java.util.logging.Logger.getLogger("Baumeister").warning("rückgängig | löschen_fehlgeschlagen | ordner="
                    + dir.getFileName() + " grund=" + e.getMessage());
        }
    }

    /**
     * Verknüpfung im Sinne von „nicht hineinlaufen“: Symlink oder Windows-Junction. Gemessen 2026-10-29 (Probe,
     * Java 21 auf Windows 11): Eine Junction meldet isSymbolicLink=false, isDirectory(NOFOLLOW_LINKS)=true und nur
     * isOther=true – und Files.walk läuft hinein. Vorher löschte deleteDir mit Files.walk; eine Junction unter
     * rückgängig/ hätte so den Inhalt ihres Ziels gelöscht, beim Start sogar für den ganzen Ordner.
     */
    static boolean isLink(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return a.isSymbolicLink() || a.isOther();
    }

    /** Tiefe zuerst, ohne je einer Verknüpfung zu folgen; eine Verknüpfung wird als Eintrag entfernt (Ziel bleibt). */
    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !isLink(p)) {
            List<Path> children;
            try (Stream<Path> s = Files.list(p)) {
                children = s.toList();
            }
            for (Path c : children) {
                deleteTree(c);
            }
        }
        Files.deleteIfExists(p);
    }

    /** Stufe 1: ~5× kleiner bei kaum Rechenzeit – die Daten (Koordinaten, wenige Zustände) wiederholen sich stark. */
    private static OutputStream gzip(OutputStream raw) throws IOException {
        return new GZIPOutputStream(raw, 1 << 16) {
            {
                def.setLevel(Deflater.BEST_SPEED);
            }
        };
    }
}
