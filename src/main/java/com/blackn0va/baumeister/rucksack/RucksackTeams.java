package com.blackn0va.baumeister.rucksack;

import java.util.regex.Pattern;

/**
 * Regeln der Teamkisten ohne Bukkit. Der Name landet im Dateinamen (rucksack/team-&lt;name&gt;.yml): nur a–z, 0–9, -
 * und _, 2–16 Zeichen – kein „..“, kein Schrägstrich, kein Weg aus dem Ordner heraus (ASVS V12.3.1, OWASP A01).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class RucksackTeams {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{2,16}");

    private RucksackTeams() {
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
