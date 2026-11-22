package com.blackn0va.baumeister.town.work;

import java.util.Locale;

/**
 * Wer im Feld welche Arbeit tut (AK-43). Nur drei Berufe aus Roster ändern Blöcke; alle anderen arbeiten weiter an
 * ihrem Arbeitsort ohne Weltänderung.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public enum FieldJob {
    /** Reife Feldfrüchte ernten und nachpflanzen. */
    FARMER("iron_hoe"),
    /** Natürliche Bäume fällen, Setzling pflanzen. */
    LUMBERJACK("iron_axe"),
    /** Wege (Trampelpfad) und Blumen nach einem kleinen Muster. */
    GARDENER("iron_shovel");

    private final String tool;

    FieldJob(String tool) {
        this.tool = tool;
    }

    /**
     * Werkzeug in der Hand während der Feldarbeit (Material-Name). Eigenes statt Roster.tool: Dort hält der Bauer
     * Weizen und die Gärtnerin eine Holzhacke – zum Ernten und Wegebauen sieht man so nicht, was sie tun.
     */
    public String tool() {
        return tool;
    }

    /** Beruf aus Roster.Person.job(); null = kein Feldberuf. */
    public static FieldJob of(String job) {
        if (job == null) {
            return null;
        }
        return switch (job.trim().toLowerCase(Locale.ROOT)) {
            case "bauer", "bäuerin" -> FARMER;
            case "holzfäller", "holzfällerin" -> LUMBERJACK;
            case "gärtner", "gärtnerin" -> GARDENER;
            default -> null;
        };
    }
}
