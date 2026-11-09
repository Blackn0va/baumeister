package com.blackn0va.baumeister;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Offene Bestätigungen für Befehle aus einem Bauplan (Plan B5, Wunsch 2026-11-08). Ohne Bukkit, damit die Regeln
 * testbar sind.
 *
 * Eine Bestätigung gilt genau einmal, nur für den, der den Auftrag gegeben hat (owner; null = Konsole), höchstens
 * {@link #TTL_MILLIS} lang und nur für genau die Befehle, die er gesehen hat (fingerprint, PlanCommands.fingerprint).
 * Je Auftraggeber ist höchstens eine offen – eine neue ersetzt die alte, sonst bestätigte ein alter Klick im Chat einen
 * Plan, den er längst vergessen hat. Das Token ist zufällig (SecureRandom), entscheidend ist aber die Bindung an den
 * Auftraggeber: Ein fremder Spieler, der das Token errät oder aus dem Chat abschreibt, kann es nicht einlösen.
 *
 * Wer die Bindung an owner oder den Ablauf entfernt, lässt einen anderen Spieler (oder einen alten Klick) Befehle in
 * fremde Bauten bestätigen (ASVS V4.2.1, OWASP A01).
 *
 * Threads: nur Hauptthread. Fehler: keine (unbekannt, fremd oder abgelaufen = leer).
 */
final class CommandConfirmations<T> {

    /** 10 Minuten: genug, um die Liste in Ruhe zu lesen; danach muss neu geplant werden. */
    static final long TTL_MILLIS = 10 * 60 * 1000L;

    /** Eine offene Bestätigung. payload trägt, was zum Weiterbauen nötig ist (BuildService). */
    record Offer<T>(String token, UUID owner, String fingerprint, long expiresAt, T payload) {
    }

    /** Ergebnis von {@link #take}: Bestätigung oder deutscher Grund, warum nicht. */
    record Taken<T>(Offer<T> offer, String problem) {

        boolean ok() {
            return offer != null;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Offer<T>> open = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Supplier<String> tokens;

    CommandConfirmations() {
        this(System::currentTimeMillis, CommandConfirmations::randomToken);
    }

    /** Für Tests: eigene Uhr und Token-Quelle. */
    CommandConfirmations(LongSupplier clock, Supplier<String> tokens) {
        this.clock = clock;
        this.tokens = tokens;
    }

    /** Neue Bestätigung für owner (null = Konsole); eine offene desselben Auftraggebers verfällt. Zurück: Token. */
    String offer(UUID owner, String fingerprint, T payload) {
        dropExpired();
        open.values().removeIf(o -> Objects.equals(o.owner(), owner));
        String token = tokens.get();
        while (open.containsKey(token)) {
            token = tokens.get();
        }
        open.put(token, new Offer<>(token, owner, fingerprint, clock.getAsLong() + TTL_MILLIS, payload));
        return token;
    }

    /**
     * Bestätigung einlösen (genau einmal). who = wer klickt oder tippt (null = Konsole). Fremde, unbekannte und
     * abgelaufene Tokens lösen nichts aus; ein fremdes Token bleibt für seinen Besitzer gültig.
     */
    Taken<T> take(String token, UUID who) {
        dropExpired();
        Offer<T> o = token == null ? null : open.get(token);
        if (o == null) {
            return new Taken<>(null, "Diese Bestätigung gibt es nicht (mehr) – sie gilt 10 Minuten und nur einmal.");
        }
        if (!Objects.equals(o.owner(), who)) {
            return new Taken<>(null, "Diese Bestätigung gehört zu einem Auftrag von jemand anderem.");
        }
        open.remove(token);
        return new Taken<>(o, null);
    }

    /** Offene Bestätigung von owner verwerfen (Abbruch, stopp). true = es gab eine. */
    boolean drop(UUID owner) {
        return open.values().removeIf(o -> Objects.equals(o.owner(), owner));
    }

    /** Offene Bestätigung von owner, falls eine da ist (Status, Tests). */
    Optional<Offer<T>> pending(UUID owner) {
        dropExpired();
        return open.values().stream().filter(o -> Objects.equals(o.owner(), owner)).findFirst();
    }

    int size() {
        dropExpired();
        return open.size();
    }

    private void dropExpired() {
        long now = clock.getAsLong();
        for (Iterator<Offer<T>> it = open.values().iterator(); it.hasNext();) {
            if (it.next().expiresAt() <= now) {
                it.remove();
            }
        }
    }

    private static String randomToken() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
