package com.blackn0va.baumeister.town.memory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ein kurzer Fakt im Gedächtnis eines Bewohners (Vertrag v013, AK-41): „Spieler KM fragte nach Brot.“ oder „Ich habe
 * Emil getroffen.“
 *
 * Warum nur über die Fabriken unten und nie aus Freitext: Das Nicht-Ziel verbietet einen Chat-Mitschnitt. Ein Fakt
 * besteht deshalb nur aus festen Satzbausteinen, einem Pseudonym (Pseudonyms), einem Bewohnernamen aus dem Roster
 * und einem Thema aus der festen Liste TOPICS – die Chatzeile selbst wird nur nach Stichwörtern durchsucht und nie
 * gespeichert. Wer hier einen Konstruktor mit String öffnet, öffnet den Weg für rohe Chatzeilen auf die Platte.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public final class Fact {

    /** Thema einer Spielerfrage: Stichwort-Muster → Wort im Fakt. Reihenfolge = Vorrang. */
    record Topic(Pattern words, String noun) {
    }

    static final List<Topic> TOPICS = List.of(
            topic("brot|brötchen|back|kuchen|semmel|teig|mehl", "Brot"),
            topic("fisch|angel|see|meer|boot", "Fischen"),
            topic("holz|baum|bäume|wald|axt|fällen", "Holz"),
            topic("eisen|schmied|hufeisen|werkzeug|schwert|amboss", "Werkzeug"),
            topic("blume|garten|rose|pflanz|beet|gießen", "dem Garten"),
            topic("buch|bücher|lesen|bibliothek|geschichte", "Büchern"),
            topic("weizen|ernte|feld|acker|kartoffel|karotte|kuh|schaf|huhn", "der Ernte"),
            topic("bild|malen|farbe|gemälde|pinsel", "Bildern"),
            topic("post|brief|paket", "der Post"),
            topic("krank|gesund|heil|trank|verletz|arzt|ärztin", "der Gesundheit"),
            topic("musik|lied|sing|spiel(st)? du|flöte|gitarre|ton", "Musik"),
            topic("smaragd|handel|kauf|verkauf|preis|ware|markt", "dem Handel"),
            topic("bau|haus|häuser|turm|brücke|mauer|dach", "dem Bauen"),
            topic("wetter|regen|sonne|gewitter|schnee|wolke", "dem Wetter"),
            topic("weg|wo ist|wo finde|wohin|richtung", "dem Weg"),
            topic("essen|hunger|mittag|frühstück|abendessen", "dem Essen"),
            topic("arbeit|beruf|job|tust du|machst du", "meiner Arbeit"),
            topic("geht'?s|geht es|wie läuft|alles gut|wie bist du", "meinem Befinden"));
    private static final Pattern THANKS = Pattern.compile("(?iuU)\\b(danke|dankeschön|merci|thx|vielen dank)\\b");

    private final String text;

    private Fact(String text) {
        this.text = text;
    }

    private static Topic topic(String words, String noun) {
        // (?U): \b sonst nur ASCII – „ärztin“, „bäume“ träfen am Wortanfang nicht (UmlautBoundaryTest).
        return new Topic(Pattern.compile("(?iuU)\\b(" + words + ")"), noun);
    }

    public String text() {
        return text;
    }

    /** Thema einer Spielerzeile aus TOPICS, sonst leer. Die Zeile selbst geht nirgends hin. */
    public static Optional<String> topicOf(String heard) {
        if (heard == null) {
            return Optional.empty();
        }
        String s = heard.toLowerCase(Locale.ROOT);
        for (Topic t : TOPICS) {
            if (t.words().matcher(s).find()) {
                return Optional.of(t.noun());
            }
        }
        return Optional.empty();
    }

    /** Was ein Spieler (alias) zu diesem Bewohner sagte – als Thema, nie als Wortlaut. */
    public static Fact heardFrom(String alias, String heard) {
        if (heard != null && THANKS.matcher(heard).find()) {
            return new Fact(alias + " hat sich bei mir bedankt.");
        }
        return topicOf(heard).map(t -> new Fact(alias + " fragte mich nach " + t + "."))
                .orElseGet(() -> new Fact(alias + " hat mit mir geplaudert."));
    }

    /** Der Bewohner hat alias im Vorbeigehen gesehen und gegrüßt. */
    public static Fact passedBy(String alias) {
        return new Fact(alias + " kam bei mir vorbei.");
    }

    /** Ein anderer Bewohner (Name aus dem Roster). */
    public static Fact met(String resident) {
        return new Fact("Ich habe " + resident + " getroffen und geplaudert.");
    }

    /** In der Nähe lief ein Bau des Baumeisters. */
    public static Fact sawBuilding() {
        return new Fact("In der Nähe wurde gebaut.");
    }

    /** Schlechtes Wetter, das der Bewohner draußen erlebt hat. */
    public static Fact weather(boolean thunder) {
        return new Fact("Ich habe " + (thunder ? "Gewitter" : "Regen") + " erlebt.");
    }

    /** Für das Laden: nur Sätze, die eine der Fabriken erzeugen kann (siehe accepts). */
    static Optional<Fact> restore(String text) {
        return accepts(text) ? Optional.of(new Fact(text)) : Optional.empty();
    }

    /**
     * Passt text auf eine der Formen oben? Beim Laden geprüft: Wer die Datei von Hand mit einem Chatzitat füllt,
     * bekommt es nicht in den Prompt. Pseudonym genau „Spieler “ + zwei Großbuchstaben, Bewohnername ein Wort.
     */
    static boolean accepts(String text) {
        if (text == null || text.length() > 120) {
            return false;
        }
        String alias = "Spieler [A-Z]{2}";
        StringBuilder nouns = new StringBuilder();
        for (Topic t : TOPICS) {
            nouns.append(nouns.isEmpty() ? "" : "|").append(Pattern.quote(t.noun()));
        }
        return text.matches(alias + " (hat sich bei mir bedankt\\.|hat mit mir geplaudert\\.|kam bei mir vorbei\\."
                + "|fragte mich nach (" + nouns + ")\\.)")
                || text.matches("Ich habe \\p{Lu}\\p{Ll}{1,15} getroffen und geplaudert\\.")
                || text.equals("In der Nähe wurde gebaut.")
                || text.matches("Ich habe (Regen|Gewitter) erlebt\\.");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Fact f && f.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
