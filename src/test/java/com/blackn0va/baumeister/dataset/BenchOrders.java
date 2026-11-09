package com.blackn0va.baumeister.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Die festen Testaufträge des Messgeräts (src/test/resources/dataset/bench-orders.txt). Eine Liste für Messgerät und
 * Generator: Der Generator schließt sie aus dem Lehrset aus, das Messgerät fragt genau sie ab.
 *
 * Threads: zustandslos. Fehler: UncheckedIOException, wenn die Datei fehlt.
 */
public final class BenchOrders {

    private BenchOrders() {
    }

    public static List<String> load() {
        try (InputStream in = BenchOrders.class.getResourceAsStream("/dataset/bench-orders.txt")) {
            if (in == null) {
                throw new IOException("Ressource fehlt: /dataset/bench-orders.txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
