package com.blackn0va.baumeister.relay;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Grenzen für Verbindungen ohne Anmeldung auf dem öffentlichen Port (Plan A2; ASVS V11.1.4, OWASP A04):
 * - je IPv4-Adresse bzw. IPv6-/64 höchstens perAddress gleichzeitige Handshakes (ein /64 gehört einem Anschluss –
 *   je Adresse zu zählen hieße 2^64 Versuche gleichzeitig),
 * - insgesamt höchstens unauthenticated,
 * - Sperre nach jedem Fehlschlag (TLS, Pin, Upgrade, hallo, Frist): 10 s, verdoppelt je weiterem Fehlschlag bis 10 min.
 *   Eine Ablehnung WÄHREND einer Sperre verlängert sie nicht – sonst käme eine App, die alle 10 s neu versucht, nie
 *   wieder heraus.
 * - Die Sperrliste ist begrenzt (banEntries); voll: zuerst Abgelaufenes, dann die am frühesten endende Sperre raus.
 *   Wer mit vielen Adressen Sperren verdrängt, gewinnt damit nur weitere Versuche – die Gesamtgrenze bleibt.
 *
 * Angemeldete Sitzungen zählen nicht mit: Nach dem Handshake mit gültigem Pin gibt PublicListener die Zählung frei
 * (release). Nach erfolgreichem hallo vergisst success() die Fehlschläge dieser Adresse.
 *
 * Bekannte Grenze: Hinter einem gemeinsamen NAT (Mobilfunk-CGNAT, NAT-Loopback des Routers) teilen sich mehrere
 * Geräte eine Adresse – ein fremder Fehlschlag sperrt dann auch die echte App, höchstens 10 min.
 *
 * Threads: alle Methoden synchronisiert (kurz, ohne Ein-/Ausgabe). Fehler: keine.
 */
final class HandshakeGate {

    private final Limits limits;
    private final Map<String, Entry> entries = new HashMap<>();
    private int active;

    private static final class Entry {
        int active;
        int failures;
        long bannedUntil;
        long lastSeen;
    }

    /** Zugelassene Verbindung; release() genau einmal (spätere Aufrufe sind wirkungslos). */
    static final class Ticket {
        final String key;
        private boolean released;

        Ticket(String key) {
            this.key = key;
        }
    }

    /** Ergebnis von enter(): ticket oder refusal (gesperrt, zu_viele, voll). */
    record Admission(Ticket ticket, String refusal) {
    }

    HandshakeGate(Limits limits) {
        this.limits = limits;
    }

    synchronized Admission enter(InetAddress address, long now) {
        String key = key(address);
        Entry e = entries.get(key);
        if (e != null && e.bannedUntil - now > 0) {
            return new Admission(null, "gesperrt");
        }
        if (active >= limits.unauthenticated()) {
            return new Admission(null, "voll");
        }
        if (e != null && e.active >= limits.perAddress()) {
            return new Admission(null, "zu_viele");
        }
        if (e == null) {
            if (entries.size() >= limits.banEntries() && !evict(now)) {
                return new Admission(null, "voll");
            }
            e = new Entry();
            entries.put(key, e);
        }
        e.active++;
        e.lastSeen = now;
        active++;
        return new Admission(new Ticket(key), null);
    }

    /** Verbindung zählt nicht mehr als „ohne Anmeldung“ (angemeldet oder beendet). */
    synchronized void release(Ticket t) {
        if (t == null || t.released) {
            return;
        }
        t.released = true;
        active--;
        Entry e = entries.get(t.key);
        if (e != null && e.active > 0) {
            e.active--;
        }
    }

    /** Fehlschlag: Sperre 10 s · 2^(n-1), höchstens banMax. */
    synchronized void failure(String key, long now) {
        Entry e = entries.get(key);
        if (e == null) {
            if (entries.size() >= limits.banEntries() && !evict(now)) {
                return;
            }
            e = new Entry();
            entries.put(key, e);
        }
        e.failures = Math.min(e.failures + 1, 30);
        long ban = limits.banMin().toNanos() << Math.min(e.failures - 1, 20);
        e.bannedUntil = now + Math.min(ban, limits.banMax().toNanos());
        e.lastSeen = now;
    }

    /** Angemeldet und hallo gelungen: Fehlschläge dieser Adresse vergessen. */
    synchronized void success(String key) {
        Entry e = entries.get(key);
        if (e != null) {
            e.failures = 0;
            e.bannedUntil = 0;
            if (e.active == 0) {
                entries.remove(key);
            }
        }
    }

    /** Vom Takt: ruhige Einträge ohne Sperre nach einer Stunde vergessen. */
    synchronized void cleanup(long now) {
        long hour = 3_600_000_000_000L;
        entries.values().removeIf(e -> e.active == 0 && e.bannedUntil - now <= 0 && now - e.lastSeen > hour);
    }

    synchronized int activeCount() {
        return active;
    }

    synchronized int size() {
        return entries.size();
    }

    /** Platz schaffen: erst Abgelaufenes ohne laufende Verbindung, dann die früheste Sperre. false: alles belegt. */
    private boolean evict(long now) {
        Iterator<Entry> it = entries.values().iterator();
        Entry earliest = null;
        String earliestKey = null;
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.active == 0 && e.bannedUntil - now <= 0) {
                it.remove();
                return true;
            }
        }
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            Entry e = me.getValue();
            if (e.active == 0 && (earliest == null || e.bannedUntil - earliest.bannedUntil < 0)) {
                earliest = e;
                earliestKey = me.getKey();
            }
        }
        if (earliestKey == null) {
            return false;
        }
        entries.remove(earliestKey);
        return true;
    }

    /** IPv4: die Adresse; IPv6: das /64 (die ersten 8 Bytes). */
    static String key(InetAddress a) {
        if (a instanceof Inet4Address) {
            return "v4:" + a.getHostAddress();
        }
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            StringBuilder s = new StringBuilder("v6:");
            for (int i = 0; i < 8; i += 2) {
                s.append(Integer.toHexString(((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF))).append(':');
            }
            return s.append(":/64").toString();
        }
        return "?:" + a.getHostAddress();
    }
}
