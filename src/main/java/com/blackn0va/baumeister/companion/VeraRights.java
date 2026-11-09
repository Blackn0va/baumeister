package com.blackn0va.baumeister.companion;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Was Vera für wen darf – vom Admin im Buch verwaltet (Wunsch 2026-11-07).
 *
 * Vorher hing jede Fähigkeit fest an einem Bukkit-Recht mit Vorgabe „op“; ändern ging nur über LuckPerms. Jetzt hat
 * jede Fähigkeit eine Stufe (ALLE, OPS, AUS), dazu Einträge je Spieler (erlauben/verbieten). Reihenfolge:
 *   1. Eintrag für den Spieler im Buch – auch ein Verbot für einen Admin gilt (bewusste Entscheidung des Admins).
 *   2. Vergebenes Bukkit-Recht (LuckPerms, oder baumeister.admin über children): erlaubt.
 *   3. Stufe der Fähigkeit.
 * Bukkit-Vorgaben zählen in Schritt 2 NICHT mit: Die Rechte stehen in plugin.yml mit „default: false“. Gemessen
 * (javap PermissibleBase.recalculatePermissions, 2026-11-07): Vorgaberechte landen in derselben Liste wie vergebene,
 * isPermissionSet kann sie nicht unterscheiden. Wer die Vorgaben dort wieder auf „true“ oder „op“ stellt, macht die
 * Stufe AUS für alle bzw. alle Ops wirkungslos.
 *
 * Fail-closed (ASVS V4.1.3, OWASP A01): Unbekannte oder kaputte Werte beim Laden fallen auf die Vorgabe der Fähigkeit
 * zurück, nie auf ALLE; der Aufrufer erfährt, dass etwas nicht stimmte.
 *
 * Threads: Lesen aus jedem Thread (ConcurrentHashMap/volatile), Ändern aus dem Hauptthread. Fehler: wirft nicht.
 */
public final class VeraRights {

    public enum Level {
        ALLE("Alle"), OPS("Ops"), AUS("Aus");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Level parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "alle", "all", "jeder", "an" -> ALLE;
                case "ops", "op", "admins", "admin" -> OPS;
                case "aus", "off", "niemand", "keiner" -> AUS;
                default -> null;
            };
        }
    }

    /**
     * Recht: Schlüssel (Buch, Datei, Befehl), Bukkit-Recht, Vorgabe, Text fürs Buch (≤ 13 Zeichen, mit „: Alle“ eine
     * Buchzeile). Veras Fähigkeiten (vera = true) haben eine Stufe im Buch; die übrigen Rechte des Plugins (Wunsch
     * 2026-11-07) nur Einträge je Spieler – ihre Vorgabe steht in plugin.yml,
     * und der Eintrag wirkt als PermissionAttachment (CompanionRights.apply) bei jeder Prüfung im Plugin.
     */
    public enum Ability {
        GEBEN("geben", "baumeister.begleiter.geben", Level.OPS, "Geben", true),
        SUCHEN("suchen", "baumeister.begleiter.suchen", Level.OPS, "Erze suchen", true),
        REISEN("reisen", "baumeister.begleiter.reisen", Level.OPS, "Teleport", true),
        BEFEHLE("befehle", "baumeister.begleiter.befehle", Level.OPS, "Befehle", true),
        REPARIEREN("reparieren", "baumeister.reparieren", Level.OPS, "Reparieren", true),
        KISTEN("kisten", "baumeister.kisten", Level.OPS, "Fremde Kisten", true),
        BENUTZEN("benutzen", "baumeister.begleiter.benutzen", Level.ALLE, "Benutzen", true),
        AUSPROBIEREN("ausprobieren", "baumeister.begleiter.ausprobieren", Level.ALLE, "Ausprobieren", true),
        FRECH("frech", "baumeister.begleiter.frech", Level.ALLE, "Frech sein", true),
        BEGLEITER("vera", "baumeister.begleiter", Level.ALLE, "Vera rufen", false),
        BAUEN("bauen", "baumeister.bauen", Level.OPS, "Bauen", false),
        ORTE("orte", "baumeister.reisen", Level.OPS, "Orte, Karte", false),
        RUCKSACK("rucksack", "baumeister.rucksack", Level.ALLE, "Rucksack", false),
        AUFGABEN("aufgaben", "baumeister.aufgaben", Level.ALLE, "Aufgaben", false),
        WARP("warp", "baumeister.warp", Level.ALLE, "Warps", false),
        WARP_SETZEN("warpsetzen", "baumeister.warp.setzen", Level.OPS, "Warp setzen", false),
        WARP_UNBEGRENZT("warpvoll", "baumeister.warp.unbegrenzt", Level.OPS, "Warp o. Limit", false),
        WARP_SOFORT("warpsofort", "baumeister.warp.sofort", Level.OPS, "Warp sofort", false),
        ADMIN("admin", "baumeister.admin", Level.OPS, "Admin", false);

        private final String key;
        private final String node;
        private final Level fallback;
        private final String label;
        private final boolean vera;

        Ability(String key, String node, Level fallback, String label, boolean vera) {
            this.key = key;
            this.node = node;
            this.fallback = fallback;
            this.label = label;
            this.vera = vera;
        }

        /** Veras Fähigkeit mit Stufe im Buch? Sonst nur Einträge je Spieler. */
        public boolean vera() {
            return vera;
        }

        public String key() {
            return key;
        }

        public String node() {
            return node;
        }

        public Level fallback() {
            return fallback;
        }

        public String label() {
            return label;
        }

        public static Ability parse(String s) {
            if (s == null) {
                return null;
            }
            String k = s.trim().toLowerCase(Locale.ROOT);
            for (Ability a : values()) {
                if (a.key.equals(k)) {
                    return a;
                }
            }
            return null;
        }
    }

    /** Wer fragt: op, und ob das Bukkit-Recht der Fähigkeit vergeben ist (hasPermission bei „default: false“). */
    public record Who(boolean op, boolean granted) {
    }

    private final Map<Ability, Level> levels = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Ability, Boolean>> players = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public Level level(Ability a) {
        return levels.getOrDefault(a, a.fallback());
    }

    /** Nur Veras Fähigkeiten haben eine Stufe; für die übrigen Rechte gilt plugin.yml (dann ohne Wirkung). */
    public void level(Ability a, Level l) {
        if (a.vera()) {
            levels.put(a, l);
        }
    }

    /** null = Stufe gilt; sonst ausdrücklich erlaubt/verboten. */
    public Boolean player(UUID id, Ability a) {
        Map<Ability, Boolean> m = players.get(id);
        return m == null ? null : m.get(a);
    }

    /** allow null = Eintrag löschen (Stufe gilt wieder). */
    public void player(UUID id, String name, Ability a, Boolean allow) {
        if (allow == null) {
            Map<Ability, Boolean> m = players.get(id);
            if (m != null) {
                m.remove(a);
                if (m.isEmpty()) {
                    players.remove(id);
                    names.remove(id);
                }
            }
            return;
        }
        players.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(a, allow);
        if (name != null) {
            names.put(id, name);
        }
    }

    public boolean allowed(Ability a, UUID id, Who who) {
        Boolean own = player(id, a);
        if (own != null) {
            return own;
        }
        if (who.granted()) {
            return true;
        }
        return switch (level(a)) {
            case ALLE -> true;
            case OPS -> who.op();
            case AUS -> false;
        };
    }

    /** Spieler mit eigenen Einträgen: Name → (Fähigkeit → erlaubt), für Buch und Datei. */
    public Map<String, Map<Ability, Boolean>> entries() {
        Map<String, Map<Ability, Boolean>> out = new LinkedHashMap<>();
        players.forEach((id, m) -> out.put(names.getOrDefault(id, id.toString()), new EnumMap<>(m)));
        return out;
    }

    /** Als einfache Struktur für die Datei: stufen.{key}=alle|ops|aus, spieler.{uuid}.{key}=true|false, .name. */
    public Map<String, Object> save() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> lv = new LinkedHashMap<>();
        for (Ability a : Ability.values()) {
            if (a.vera()) {
                lv.put(a.key(), level(a).name().toLowerCase(Locale.ROOT));
            }
        }
        root.put("stufen", lv);
        Map<String, Object> pl = new LinkedHashMap<>();
        players.forEach((id, m) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            String n = names.get(id);
            if (n != null) {
                e.put("name", n);
            }
            m.forEach((a, v) -> e.put(a.key(), v));
            pl.put(id.toString(), e);
        });
        root.put("spieler", pl);
        return root;
    }

    /**
     * Aus der Datei laden. Rückgabe: Anzahl verworfener Einträge (unbekannte Fähigkeit, Stufe, UUID oder Wert) – sie
     * fallen auf die Vorgabe zurück (fail-closed), der Aufrufer meldet es.
     */
    public int load(Map<String, ?> stufen, Map<String, ? extends Map<String, ?>> spieler) {
        levels.clear();
        players.clear();
        names.clear();
        int bad = 0;
        if (stufen != null) {
            for (Map.Entry<String, ?> e : stufen.entrySet()) {
                Ability a = Ability.parse(e.getKey());
                Level l = e.getValue() instanceof String s ? Level.parse(s) : null;
                if (a == null || !a.vera() || l == null) {
                    bad++;
                    continue;
                }
                levels.put(a, l);
            }
        }
        if (spieler != null) {
            for (Map.Entry<String, ? extends Map<String, ?>> e : spieler.entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ex) {
                    bad++;
                    continue;
                }
                Object n = e.getValue() == null ? null : e.getValue().get("name");
                for (Map.Entry<String, ?> v : e.getValue() == null ? Map.<String, Object>of().entrySet()
                        : e.getValue().entrySet()) {
                    if (v.getKey().equals("name")) {
                        continue;
                    }
                    Ability a = Ability.parse(v.getKey());
                    if (a == null || !(v.getValue() instanceof Boolean b)) {
                        bad++;
                        continue;
                    }
                    player(id, n instanceof String s ? s : null, a, b);
                }
            }
        }
        return bad;
    }
}
