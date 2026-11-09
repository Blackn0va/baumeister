package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Minecraft-Witze für Vera, ohne Modell (Wunsch 2026-11-04). Das 14B-Modell
 * erzählt auf „erzähl einen Witz“ meist denselben einen Witz oder erklärt ihn hinterher; eine feste Liste ist sofort da,
 * kindgerecht und wiederholt sich erst, wenn alle dran waren (gemischter Stapel je Begleiter).
 *
 * Wörter wie „Creeper“ stehen im Original – die App schreibt sie fürs Ohr um (host speech_text.cpp).
 *
 * Threads: eine Instanz je Begleiter, nur Hauptthread. Fehler: keine.
 */
public final class Jokes {

    static final List<String> ALL = List.of(
            "Warum hat der Creeper keine Freunde? Weil er bei jeder Umarmung explodiert.",
            "Was macht ein Skelett im Winter? Es zittert sich durch – mit nichts am Leib außer Knochen.",
            "Warum gehen Endermen nie ins Kino? Weil sie jeden Film nur von hinten sehen – sobald du hinschaust, sind "
                    + "sie weg.",
            "Was sagt ein Zombie beim Bäcker? Ein Brot, bitte – und ein bisschen Gehirn, falls noch was da ist. Ach "
                    + "nein, Brot reicht.",
            "Warum ist Steve so gut in Mathe? Er rechnet alles in Blöcken.",
            "Wie nennt man einen Schaf-Rapper? Mäh-C Hammer!",
            "Was ist das Lieblingsessen eines Creepers? Ssssssalat.",
            "Warum hat der Dorfbewohner eine große Nase? Damit er gute Angebote schon von Weitem riecht. Hmm!",
            "Warum spielt der Ghast keine Karten? Weil er bei jedem Verlust losheult.",
            "Was macht ein Huhn im Nether? Es wird ein Brathähnchen.",
            "Warum baut der Baumeister nie mit Sand? Weil ihm sonst alles zusammenrieselt.",
            "Wie verabschiedet sich ein Slime? Wir bleiben in Kontakt – ganz klebrig!",
            "Was sagt die Werkbank zur Truhe? Du bist echt voll von dir.",
            "Warum ist der Enderdrache so schlecht gelaunt? Er hat Kristall-Kopfschmerzen.",
            "Warum nimmt der Spieler eine Leiter mit zur Schule? Weil er aufsteigen will – in die nächste Etappe!");

    private final Random random;
    private final List<String> deck = new ArrayList<>();

    public Jokes(Random random) {
        this.random = random;
    }

    /** Nächster Witz aus dem gemischten Stapel; ist er leer, wird neu gemischt. */
    public String next() {
        if (deck.isEmpty()) {
            deck.addAll(ALL);
            Collections.shuffle(deck, random);
        }
        return deck.removeLast();
    }
}
