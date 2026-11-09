package com.blackn0va.baumeister;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Für wen darf eine Baumeister-App sprechen? Gilt für Sätze, die die App über ihr Mikrofon erkannt hat und über
 * „gehört“ liefert (CompanionVoiceLink, Long-Poll /baumeister/v1/begleiter/gehoert). umlaut-ok: URL-Pfad ASCII
 *
 * Vorher galt jeder dieser Sätze als Wort des Spielers, den die Antwort nennt (CompanionService.heard) – wer
 * 127.0.0.1:18080 bediente, sprach für jeden Spieler, auch „ja“ auf eine Rückfrage. Mit der Verbindung über die URL
 * (Plan A4, 2026-11-08) hängen Apps mit eigenem Pin-Namen am Relay, und das Relay schreibt diesen Namen an jede Antwort
 * als X-Baumeister-App (docs/kanal-v1.md, Abschnitt 6).
 *
 * Jetzt: Steht unter fernverbindung.apps mindestens ein Eintrag, gilt ein Satz nur für einen Spieler, der bei genau
 * der App eingetragen ist, deren Name im Kopf steht. Ohne Kopf, mit unbekanntem oder ungültigem Namen und für jeden
 * anderen Spieler wird er verworfen (fail-closed, ASVS V4.1.5, OWASP A01). Ein Abschnitt, der da ist, aber nicht
 * lesbar, bindet ebenfalls – an keine App. Unlesbares macht die Bindung also strenger, nie lockerer.
 *
 * Ohne Eintrag bleibt es wie bisher: Über den SSH-Tunnel spricht nur die eine App des Admins, und dort gibt es keinen
 * Kopf – eine Pflicht hätte die Sprache auf dev01 abgeschaltet, bevor das Relay läuft. Das sagt CompanionVoiceLink.from
 * beim Start im Log. Wer die Bindung ohne Eintrag erzwingt, schaltet den Tunnelbetrieb stumm; wer sie mit Eintrag
 * lockert, lässt jede App wieder für jeden sprechen.
 *
 * Bewusst NICHT für „hören“ (Simple Voice Chat): Dort schneidet der Server den Ton des Spielers selbst mit und weiß,
 * wer spricht – die App schreibt nur ab und kann keinen anderen Spieler nennen. Was sie trotzdem falsch abschreibt,
 * fängt die Regel „Gesprochenes bestätigt keine Befehle“ ab (CompanionRules.pendingAnswer).
 *
 * Threads: unveränderlich, aus jedem Thread lesbar. Fehler: wirft nicht.
 */
final class VoiceAppBinding {

    /** Kopf, den das Relay an jede Antwort setzt (docs/kanal-v1.md, Abschnitt 6). */
    static final String HEADER = "X-Baumeister-App";
    static final String SECTION = "fernverbindung.apps";
    /** Wie die Pin-Namen im Relay (docs/kanal-v1.md, Abschnitt 1). */
    static final Pattern APP_NAME = Pattern.compile("[a-z0-9-]{1,32}");
    /** Minecraft-Spielername, wie CompanionVoiceLink.heardUri ihn durchlässt. */
    static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Ergebnis der Prüfung; code steht im Log (snake_case wie die übrigen Gründe). */
    enum Verdict {
        ACCEPT("angenommen"),
        NO_APP("app_fehlt"),
        UNKNOWN_APP("app_unbekannt"),
        NOT_LISTED("spieler_nicht_bei_app");

        final String code;

        Verdict(String code) {
            this.code = code;
        }
    }

    private static final VoiceAppBinding UNBOUND = new VoiceAppBinding(false, Map.of());

    private final boolean bound;
    /** App → Spieler in Kleinschreibung (Minecraft-Namen sind ohne Groß/klein eindeutig, getPlayerExact ebenso). */
    private final Map<String, Set<String>> players;

    private VoiceAppBinding(boolean bound, Map<String, Set<String>> players) {
        this.bound = bound;
        this.players = players;
    }

    static VoiceAppBinding unbound() {
        return UNBOUND;
    }

    /** Für Tests und from(): App → Spielernamen; ungültige Namen fallen weg, die Bindung bleibt. */
    static VoiceAppBinding of(Map<String, ? extends java.util.Collection<String>> apps) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        apps.forEach((app, names) -> {
            if (APP_NAME.matcher(app).matches()) {
                Set<String> valid = names.stream().filter(n -> n != null && PLAYER_NAME.matcher(n).matches())
                        .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
                map.put(app, Collections.unmodifiableSet(valid));
            }
        });
        // Nicht Map.copyOf: Dessen Reihenfolge wechselt von JVM zu JVM (gemessen 2026-11-08: describe() lieferte in
        // einem Testlauf „gpu-pc=2,laptop=1“, im nächsten „laptop=1,gpu-pc=2“; Probe mit zwei Schlüsseln 6:6 in 12
        // JVM-Starts) – das Log soll der Reihenfolge der Config folgen.
        return new VoiceAppBinding(true, Collections.unmodifiableMap(map));
    }

    /** fernverbindung.apps.&lt;name&gt;.spieler; Fehler meldet ConfigCheck, hier gelten sie als „kein Spieler“. */
    static VoiceAppBinding from(ConfigurationSection config) {
        Object raw = config.get(SECTION);
        if (raw == null || emptyValue(raw)) {
            return UNBOUND;
        }
        if (!(raw instanceof ConfigurationSection apps)) {
            // Ein Wert statt eines Abschnitts („apps: gpu-pc“) ist ein Versuch zu binden, nur falsch geschrieben.
            return of(Map.of());
        }
        Set<String> names = apps.getKeys(false);
        if (names.isEmpty()) {
            return UNBOUND;
        }
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : names) {
            List<?> list = apps.getList(name + ".spieler");
            List<String> players = new java.util.ArrayList<>();
            if (list != null) {
                for (Object o : list) {
                    // Rein numerische Namen liest YAML als Zahl („- 12345“) – auch das ist ein gültiger Spielername.
                    if (o instanceof String || o instanceof Integer || o instanceof Long) {
                        players.add(String.valueOf(o).strip());
                    }
                }
            }
            map.put(name, players);
        }
        return of(map);
    }

    /** „apps: []“ und „apps: ''“ meinen dasselbe wie „apps: {}“ – leer, also ungebunden (auch für ConfigCheck). */
    static boolean emptyValue(Object raw) {
        return raw instanceof List<?> l && l.isEmpty() || raw instanceof String s && s.isBlank();
    }

    /** Ist die Sprache an Apps gebunden (mindestens ein Eintrag oder ein unlesbarer Abschnitt)? */
    boolean bound() {
        return bound;
    }

    /**
     * @param app    alle Werte des Kopfes X-Baumeister-App (HttpHeaders.allValues)
     * @param player Spieler, für den die App den Satz meldet
     */
    Verdict check(List<String> app, String player) {
        if (!bound) {
            return Verdict.ACCEPT;
        }
        if (app == null || app.isEmpty()) {
            return Verdict.NO_APP;
        }
        // Zwei Köpfe sind nicht eindeutig – dann ist es keine bekannte App.
        String name = app.size() == 1 ? app.getFirst().strip() : "";
        Set<String> listed = APP_NAME.matcher(name).matches() ? players.get(name) : null;
        if (listed == null) {
            return Verdict.UNKNOWN_APP;
        }
        return player != null && listed.contains(player.toLowerCase(Locale.ROOT)) ? Verdict.ACCEPT
                : Verdict.NOT_LISTED;
    }

    /** Fürs Log: „gpu-pc=2,laptop=1“ (Anzahl Spieler je App), „-“ ohne App. */
    String describe() {
        return players.isEmpty() ? "-" : players.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size())
                .collect(Collectors.joining(","));
    }

    /** Wert aus dem Kopf fürs Log: nur ein gültiger App-Name, sonst „ungültig“ bzw. „-“ (keine Log-Injektion). */
    static String logName(List<String> app) {
        if (app == null || app.isEmpty()) {
            return "-";
        }
        String name = app.getFirst().strip();
        return app.size() == 1 && APP_NAME.matcher(name).matches() ? name : "ungültig";
    }
}
