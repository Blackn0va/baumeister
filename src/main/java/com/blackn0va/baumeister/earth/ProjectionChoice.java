package com.blackn0va.baumeister.earth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;

/**
 * Welche Projektion die Erdwelt benutzt – einmal festgelegt, für immer.
 *
 * Warum festschreiben: Jeder erzeugte Chunk trägt die Projektion in sich. Würde config erde.projektion später
 * umgestellt, entstünden neue Chunks nach einer anderen Karte als die alten daneben (Kontinente an zwei Stellen,
 * Kanten aus Meer). Deshalb schreibt der erste Start MIT geladener Erdwelt die Wahl in erde/welt.json; danach gilt
 * fail-closed immer die gespeicherte, eine abweichende Konfiguration gibt nur eine Warnung. Ohne geladene Erdwelt
 * wird nichts geschrieben – dann ist auch noch kein Chunk entstanden.
 *
 * Fehler: Eine kaputte oder unbekannte gespeicherte Wahl ist IOException (die Erdwelt bleibt dann aus, statt mit
 * einer geratenen Projektion weiterzubauen); ein unbekannter Konfigurationswert IllegalArgumentException.
 */
public final class ProjectionChoice {

    public static final String AIROCEAN = "airocean";
    public static final String SINUS6 = "sinus6";
    public static final String DEFAULT = AIROCEAN;

    /**
     * @param name    gültige Wahl
     * @param warning Text für das Log, wenn Konfiguration und gespeicherte Wahl abweichen, sonst null
     * @param written true, wenn die Wahl gerade erst festgeschrieben wurde
     */
    public record Result(String name, EarthProjection projection, String warning, boolean written) {
    }

    private ProjectionChoice() {
    }

    /** Projektion zum Namen; unbekannt → IllegalArgumentException. */
    public static EarthProjection byName(String name) {
        return switch (name) {
            case AIROCEAN -> new AiroceanProjection();
            case SINUS6 -> new Sinus6Projection();
            default -> throw new IllegalArgumentException("erde.projektion muss airocean oder sinus6 sein: " + name);
        };
    }

    /**
     * @param file        erde/welt.json
     * @param configured  Wert aus config.yml (null = Vorgabe)
     * @param worldLoaded Erdwelt geladen → Wahl festschreiben, falls noch nicht geschehen
     */
    public static Result resolve(Path file, String configured, boolean worldLoaded) throws IOException {
        String conf = configured == null || configured.isBlank() ? DEFAULT
                : configured.trim().toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(file)) {
            String stored = readStored(file);
            EarthProjection p;
            try {
                p = byName(stored);
            } catch (IllegalArgumentException e) {
                throw new IOException(file + ": unbekannte Projektion „" + stored + "“", e);
            }
            String warning = stored.equals(conf) ? null
                    : "config erde.projektion=" + conf + ", die Erdwelt ist aber mit " + stored + " angelegt – es "
                    + "gilt " + stored + " (sonst passten neue Chunks nicht zu den alten)";
            return new Result(stored, p, warning, false);
        }
        EarthProjection p = byName(conf);
        if (!worldLoaded) {
            return new Result(conf, p, null, false);
        }
        JsonObject o = new JsonObject();
        o.addProperty("projektion", conf);
        o.addProperty("festgelegt", Instant.now().toString());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "welt", ".tmp");
        try {
            Files.writeString(tmp, o + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Result(conf, p, null, true);
    }

    private static String readStored(Path file) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject o = root.getAsJsonObject();
            if (!o.has("projektion")) {
                throw new IOException(file + ": „projektion“ fehlt");
            }
            return o.get("projektion").getAsString().trim().toLowerCase(Locale.ROOT);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IOException(file + " ist kaputt: " + e.getMessage(), e);
        }
    }
}
