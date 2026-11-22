package com.blackn0va.baumeister.town.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Das laufende Gespräch je Bewohner: die letzten KEEP Sätze, die er gesagt oder gehört hat, höchstens FRESH_MS alt
 * (Vertrag v013, AK-41 „Gespräche mit Zusammenhang“). Vorher bekam das Modell nur die eine Zeile, auf die es
 * antworten sollte – Emil wusste nicht, was Lotte eben gesagt hatte, und beide redeten aneinander vorbei.
 *
 * Nur im Speicher, nie auf der Platte und nie im Log: Das ist kein Chat-Mitschnitt (Nicht-Ziel), sondern das, was ein
 * Mensch von den letzten zwei Minuten noch weiß. Spieler stehen hier nur unter Pseudonym, ihre Zeilen sind schon um
 * fremde Spielernamen bereinigt (TownTalk.scrub), bevor sie hier ankommen.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
public final class Conversation {

    /** So viele Sätze gehen in den Prompt (Auftrag: die letzten 3–4). */
    public static final int KEEP = 4;
    /** Älter = ein anderes Gespräch. */
    public static final long FRESH_MS = 2 * 60_000L;

    /** Ein Satz: wer (Bewohnername oder Pseudonym), was, wann. */
    public record Line(String speaker, String text, long at) {
    }

    private final Map<String, Deque<Line>> byResident = new HashMap<>();

    /** Satz von speaker, gehört von allen listeners (Bewohnernamen; der Sprecher gehört dazu, wenn er Bewohner ist). */
    public void add(Collection<String> listeners, String speaker, String text, long now) {
        if (speaker == null || text == null || text.isBlank()) {
            return;
        }
        Line line = new Line(speaker, text, now);
        for (String who : listeners) {
            Deque<Line> d = byResident.computeIfAbsent(TownMemory.key(who), k -> new ArrayDeque<>());
            d.addLast(line);
            while (d.size() > KEEP) {
                d.removeFirst();
            }
        }
    }

    /** Die frischen Sätze, älteste zuerst. */
    public List<Line> recent(String resident, long now) {
        Deque<Line> d = byResident.get(TownMemory.key(resident));
        if (d == null) {
            return List.of();
        }
        for (Iterator<Line> it = d.iterator(); it.hasNext();) {
            if (now - it.next().at() > FRESH_MS) {
                it.remove();
            }
        }
        return new ArrayList<>(d);
    }

    /** Bewohner geht oder wird vergessen. */
    public void drop(String resident) {
        byResident.remove(TownMemory.key(resident));
    }

    public void clear() {
        byResident.clear();
    }
}
