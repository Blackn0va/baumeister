package com.blackn0va.baumeister.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deutsche Bedienhinweise für die Farmen (Wunsch 2026-11-08).
 * Vorher sagte der Baumeister nach dem Schnellauftrag nur „Ich baue dir das Bauteil 'eisenfarm'.“ – was der Spieler
 * noch tun muss (Betten, Dorfbewohner, Zombie, Wassereimer, Lava), stand nur in der englischen Modulbeschreibung, die
 * ausschließlich das LLM und Veras Kontext bekamen. Jetzt nennt die Antwort beim Auftrag, was bereitliegen soll, und nach
 * dem fertigen Bau kommen die Schritte (BuildService).
 *
 * Getrennt von der Modulbeschreibung, weil die mit jedem LLM-Auftrag in den Prompt geht und kurz bleiben muss
 * (MachinesTest.machineDescriptionsStayShortForThePrompt). Lagewörter gelten von vorn gesehen, wie die Baupläne: vorn
 * ist, wo der Spieler beim Schnellauftrag steht, rechts ist +x. FarmGuideTest prüft Zahlen und Lagen gegen den Bau –
 * wer eine Farm umbaut, sieht dort, welcher Satz nicht mehr stimmt.
 *
 * Threads: zustandslos. Fehler: unlesbarer Plan → keine Hinweise (bewusst fail-open, siehe inPlan).
 */
public final class FarmGuide {

    /**
     * Hinweise zu einer Farm.
     *
     * @param module     Bauteilname wie im Plan („eisenfarm“)
     * @param title      Name für den Spieler („Eisenfarm“)
     * @param accusative für „Ich baue dir …“ („die Eisenfarm“, „den Bruchsteingenerator“)
     * @param prepare    was der Spieler bereitlegen soll, leer = nichts
     * @param steps      Sätze nach dem Bau, je einer eine Chatzeile
     */
    public record Guide(String module, String title, String accusative, String prepare, List<String> steps) {

        /** Chatzeilen nach dem Bau; die erste nennt die Farm, damit bei mehreren klar ist, wofür sie gelten. */
        public List<String> lines() {
            List<String> out = new ArrayList<>(steps);
            out.set(0, title + " – so geht's: " + steps.getFirst());
            return List.copyOf(out);
        }
    }

    private static final Map<String, Guide> GUIDES = index(List.of(
            new Guide("xp_farm", "XP-Farm", "die XP-Farm", "", List.of(
                    "Stell dich in der Mitte unter der Plattform vor die Glasscheibe am Schacht und warte. Oben im "
                            + "Dunkeln laichen Monster, das Wasser trägt sie in den Schacht.",
                    "Unten kommen sie fast tot an: Schlag durch den Spalt über der Stufe, Creeper sofort. Die Beute "
                            + "landet in den zwei Truhen im Boden vor der Scheibe.",
                    "Je weniger dunkle Höhlen in der Nähe, desto mehr kommen an – Minecraft lässt um dich herum nur "
                            + "eine Höchstzahl Monster zu.")),
            new Guide("weizenfarm", "Weizenfarm", "die Weizenfarm", "9 Wassereimer", List.of(
                    "Leg in jeden der 9 Werfer hinten einen Wassereimer. Der Weizen ist gesät und wächst von selbst "
                            + "(Knochenmehl geht schneller), er braucht aber freien Himmel.",
                    "Ist er reif: Hebel hinten rechts an – das Wasser spült Weizen und Saat in die Truhe vorn rechts, "
                            + "das dauert bis zu einer halben Minute. Hebel aus und wieder an holt das Wasser zurück, "
                            + "dann neu säen.")),
            // Vorher (bis 2026-11-08): ein Zombie, „Golems kommen tagsüber, nachts nicht“. Gemessen schliefen die
            // Bewohner dabei nie wieder ein, sobald sie den Zombie im Stehen sahen – jetzt zwei Zombies
            // (Machines.ironFarm).
            new Guide("eisenfarm", "Eisenfarm", "die Eisenfarm",
                    "3 Betten, 3 Dorfbewohner, 2 Zombies (Spawn-Eier) und 2 Namensschilder", List.of(
                    "Am einfachsten im Kreativmodus. Die Bettenkammer liegt oben hinten: Stell dich auf ihre vordere "
                            + "Glaswand und setz durch den Spalt unter dem Glasdach 3 Betten nebeneinander.",
                    "Auf jedes Bett einen Dorfbewohner (Spawn-Ei, dabei schleichen).",
                    "Hinter den Betten liegen zwei Käfige, unten rechts und oben in der Mitte: Stein hinten abbauen, "
                            + "je einen großen Zombie hinein (kein Baby), Namensschild dran, Stein wieder rein.",
                    "Golems kommen bei Tag und bei Nacht: Im Bett sehen die Bewohner den unteren Zombie, im Stehen "
                            + "tagsüber den oberen. Nachts klappen die Tageslichtsensoren die Falltüren vor den oberen, "
                            + "damit sie wieder einschlafen.",
                    "Die Golems fallen in die Grube und sterben auf den Seelenlagerfeuern, das Eisen kommt in die "
                            + "Truhe unten rechts.")),
            new Guide("huehnerfarm", "Hühnerfarm", "die Hühnerfarm", // umlaut-ok
                    "3 Hühner (Spawn-Eier) oder Eier zum Werfen", List.of(
                    "Setz in jede der 3 Glaszellen ein Huhn: von oben mit dem Spawn-Ei auf den Trichter, dabei "
                            + "schleichen. Oder wirf Eier hinein – aus jedem achten schlüpft eins.",
                    "Ein Huhn legt alle 5 bis 10 Minuten ein Ei, die Trichter bringen es in die Truhe rechts.")),
            new Guide("kuerbisfarm", "Kürbisfarm", "die Kürbisfarm", "", List.of( // umlaut-ok
                    "Die 4 Stängel sind ausgewachsen. Wächst rechts daneben ein Kürbis, merkt es der Beobachter über "
                            + "dem Stängel, und der Kolben schlägt ihn ab – heb ihn auf.")),
            new Guide("melonenfarm", "Melonenfarm", "die Melonenfarm", "", List.of(
                    "Die 4 Stängel sind ausgewachsen. Wächst rechts daneben eine Melone, merkt es der Beobachter über "
                            + "dem Stängel, und der Kolben schlägt sie ab – heb die Scheiben auf.")),
            new Guide("bambusfarm", "Bambusfarm", "die Bambusfarm", "", List.of(
                    "Der Bambus wächst von selbst. Erreicht ein Halm das dritte Stück, stößt der Kolben das zweite "
                            + "weg und der Rest fällt herunter – sammle ihn ein, unten wächst er nach.")),
            new Guide("kaktusfarm", "Kaktusfarm", "die Kaktusfarm", "", List.of(
                    "Die Kakteen wachsen von selbst. Das dritte Stück bricht am Zaun ab und fällt herunter – sammle "
                            + "es ein. Was dabei auf einen Kaktus fällt, wird zerstört.")),
            new Guide("bruchsteingenerator", "Bruchsteingenerator", "den Bruchsteingenerator", "einen Lavaeimer",
                    List.of("Gieß den Lavaeimer von oben in das Fach links, nicht daneben – sonst läuft die Lava aus. "
                            + "In der Öffnung vorn daneben entsteht Bruchstein: abbauen, er kommt gleich nach."))));

    private FarmGuide() {
    }

    private static Map<String, Guide> index(List<Guide> guides) {
        Map<String, Guide> m = new LinkedHashMap<>();
        for (Guide g : guides) {
            m.put(g.module(), g);
        }
        return Map.copyOf(m);
    }

    /**
     * Hinweise zu genau diesem Bauteil. Gespiegelte oder verdoppelte Fassungen (ModuleTransforms) bekommen keine: Dort
     * stimmen „links/rechts“ oder die Zahlen nicht mehr – lieber nichts sagen als das Falsche.
     */
    public static Optional<Guide> of(String module) {
        return Optional.ofNullable(module == null ? null : GUIDES.get(module));
    }

    /** Alle Bauteile mit Hinweisen (für Tests). */
    static List<String> modules() {
        return List.copyOf(GUIDES.keySet());
    }

    /**
     * Antwort beim Schnellauftrag: was bereitliegen soll, die Schritte kommen nach dem Bau. Höchstens 200 Zeichen –
     * BuildService.acceptPlan kürzt die Antwort mitten im Satz (Texts.clean).
     */
    static Optional<String> acceptReply(String module) {
        return of(module).map(g -> "Ich baue dir " + g.accusative() + "."
                + (g.prepare().isEmpty() ? "" : " Leg schon bereit: " + g.prepare() + ".")
                + " Die Anleitung kommt, sobald alles steht.");
    }

    /**
     * Farmen eines Plans, je Bauteil einmal, in der Reihenfolge des Plans – auch für LLM-Pläne, die eine Farm als
     * Bauteil setzen. Ein unlesbarer Plan gibt bewusst keine Hinweise statt eines Fehlers (fail-open): Er kommt erst
     * nach dem Bau hierher, der Bau steht dann schon, und ein fehlender Hinweis schadet nichts.
     */
    public static List<Guide> inPlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(planJson);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("steps")
                    || !root.getAsJsonObject().get("steps").isJsonArray()) {
                return List.of();
            }
            Map<String, Guide> found = new LinkedHashMap<>();
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray("steps")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject s = el.getAsJsonObject();
                if (s.has("op") && s.get("op").isJsonPrimitive() && s.get("op").getAsString().equals("module")
                        && s.has("name") && s.get("name").isJsonPrimitive()) {
                    String name = s.get("name").getAsString();
                    of(name).ifPresent(g -> found.putIfAbsent(name, g));
                }
            }
            return List.copyOf(found.values());
        } catch (JsonParseException | IllegalStateException e) {
            // fail-open (s. oben): kein Hinweis.
            return List.of();
        }
    }
}
