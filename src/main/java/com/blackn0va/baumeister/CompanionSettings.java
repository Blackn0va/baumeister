package com.blackn0va.baumeister;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Einstellungen des Begleiters (config.yml, Abschnitt begleiter). Grenzen so, dass ein verstellter Wert nichts kaputt
 * macht: Name 2–16 Zeichen aus Buchstaben (er ist zugleich das Rufwort und landet in einer Regex, dort gequotet),
 * höchstens 64 Gegenstände je Aktion wie im Schema.
 */
record CompanionSettings(boolean enabled, String name, double distance, long conversationNanos, int maxGive,
        int maxTokens, java.util.List<String> adventureWorlds, boolean adventureFight, boolean chatter,
        boolean bargeIn) {

    /**
     * Vorgabe: die Hauptwelt samt Nether und End (MainWorlds). Vorher fest world/world_nether/world_the_end, die
     * Farmwelt von dev01 – auf fremden Servern mit anderem level-name gab es so nirgends ein Abenteuer.
     */
    static final java.util.List<String> DEFAULT_ADVENTURE_WORLDS = MainWorlds.DEFAULT;

    static final String DEFAULT_NAME = "Vera";

    static CompanionSettings from(ConfigurationSection root) {
        if (root == null) {
            return new CompanionSettings(true, DEFAULT_NAME, 2.5, 30_000_000_000L, 64, 400, DEFAULT_ADVENTURE_WORLDS,
                    true, true, true);
        }
        String raw = root.getString("begleiter.name", DEFAULT_NAME);
        String name = raw != null && raw.trim().matches("[\\p{L}][\\p{L} ]{1,15}") ? raw.trim() : DEFAULT_NAME;
        return new CompanionSettings(
                root.getBoolean("begleiter.an", true),
                name,
                Math.clamp(root.getDouble("begleiter.abstand", 2.5), 1.5, 8.0),
                Math.clamp(root.getLong("begleiter.gespräch-sekunden", 30), 0, 600) * 1_000_000_000L,
                Math.clamp(root.getInt("begleiter.max-geben", 64), 1, 64),
                Math.clamp(root.getInt("begleiter.max-tokens", 400), 100, 2000),
                root.isList("begleiter.abenteuer.welten")
                        ? java.util.List.copyOf(root.getStringList("begleiter.abenteuer.welten"))
                        : DEFAULT_ADVENTURE_WORLDS,
                root.getBoolean("begleiter.abenteuer.mitkämpfen", true),
                root.getBoolean("begleiter.plapperton", true),
                root.getBoolean("begleiter.unterbrechen", true));
    }
}
