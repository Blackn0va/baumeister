package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

/**
 * Statusdatei für das Login-Banner (docs/kanal-v1.md, Abschnitt 6): eine Zeile je angemeldeter App
 * „app=&lt;pin-name&gt; seit=&lt;ISO-8601 UTC&gt;“, ohne App „app=-“. Ein Login-Banner kann nicht mehr am
 * gebundenen Port 18080 ablesen, ob die App da ist – das Relay hält den Port immer.
 *
 * Geschrieben wird über eine Nachbardatei und atomares Umbenennen: Das Banner liest nie eine halbe Datei.
 * Bewusst fail-open: Scheitert das Schreiben, läuft das Relay weiter (nur die Anzeige ist dann veraltet) und meldet
 * es im Log – die Datei steuert nichts.
 *
 * Threads: write() synchronisiert. Fehler: werden geloggt, nie geworfen.
 */
final class StatusFile {

    private static final Logger LOG = RelayLog.LOG;

    record Entry(String app, Instant since) {
    }

    private final Path path;

    StatusFile(Path path) {
        this.path = path;
    }

    synchronized void write(List<Entry> apps) {
        StringBuilder b = new StringBuilder();
        if (apps.isEmpty()) {
            b.append("app=-\n");
        }
        for (Entry e : apps) {
            b.append("app=").append(e.app()).append(" seit=").append(e.since().truncatedTo(ChronoUnit.SECONDS))
                    .append('\n');
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".neu");
        IOException last = null;
        // Bis zu 10 Versuche: Unter Windows scheitert das Ersetzen, solange ein Leser die Datei offen hat
        // (AccessDeniedException, gemessen 2026-11-08 im Test, der die Datei im 10-ms-Takt liest). Linux ersetzt auch
        // offene Dateien; dort greift der erste Versuch.
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.writeString(tmp, b, StandardCharsets.UTF_8);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
                pause();
            }
        }
        LOG.warning("relay | statusdatei_fehler | datei=" + path + " grund=" + last.getClass().getSimpleName());
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
