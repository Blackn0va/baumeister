package com.blackn0va.baumeister.town.presence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Profil-UUID und Ping eines Bewohners in der Tab-Liste (Vertrag v013, AK-40).
 *
 * UUID: Version 2. Echte Spieler haben Version 4 (Mojang, online-mode) oder Version 3 (offline-mode,
 * UUID.nameUUIDFromBytes("OfflinePlayer:" + name), gemessen im Client-Jar 26.2: UUIDUtil.createOfflinePlayerUUID) –
 * Version 2 kommt bei Spielern nicht vor, ein Bewohner kann also mit keinem echten Spieler zusammenfallen. Dazu prüft
 * profileId die online Spieler und die übrigen Bewohner (taken), falls ein Proxy oder Plugin eigene UUIDs vergibt.
 * Stabil aus dem Namen abgeleitet: Lotte hat nach jedem Neustart dieselbe UUID, also denselben Kopf in der Liste.
 *
 * Skin: Die Bewohner tragen kein Profil (TownLife setzt keins), der Client malt sie mit ClientMannequin.DEFAULT_SKIN =
 * DefaultPlayerSkin.get(NIL_UUID) = Index 0 von 18 (slim/alex). Für einen Tab-Eintrag ohne Textur nimmt der Client
 * DefaultPlayerSkin.get(uuid) = DEFAULT_SKINS[floorMod(uuid.hashCode(), 18)]. Gemessen 2026-11-22 per javap in den
 * Client-Jars 1.21.11 und 26.2 (gleich). Deshalb wird nur eine UUID genommen, deren hashCode auf Index 0 fällt – sonst
 * stünde in der Liste ein anderer Kopf als in der Welt. Wer MANNEQUIN_SKIN ändert, ohne TownLife einen Skin zu geben,
 * bekommt genau das.
 *
 * Threads: zustandslos. Fehler: keine Ausnahme; findet sich keine freie UUID, leer (dann ohne Tab-Eintrag).
 */
public final class ResidentIdentity {

    /** UUID-Version der Bewohner (DCE Security, bei Spielern nie vergeben). */
    public static final int VERSION = 2;
    /** Zahl der Standard-Skins im Client (DefaultPlayerSkin.DEFAULT_SKINS, 1.21.11 und 26.2). */
    static final int DEFAULT_SKINS = 18;
    /** Index des Mannequin-Standard-Skins (slim/alex), siehe Kopf. */
    static final int MANNEQUIN_SKIN = 0;
    /** Bei 18 Eimern trifft im Mittel jeder 18. Versuch; 10 000 Versuche reichen für jede denkbare Belegung. */
    static final int MAX_TRIES = 10_000;
    /** Spanne des angezeigten Pings: wie ein Spieler mit gutem Anschluss (alle Balken grün). */
    static final int MIN_LATENCY = 18;
    static final int LATENCY_SPAN = 57;

    private ResidentIdentity() {
    }

    /** Profil-UUID für name: Version 2, Mannequin-Skin, nicht in taken; leer, wenn keine frei ist. */
    public static Optional<UUID> profileId(String name, Predicate<UUID> taken) {
        for (int salt = 0; salt < MAX_TRIES; salt++) {
            UUID id = candidate(name, salt);
            if (skinIndex(id) == MANNEQUIN_SKIN && !taken.test(id)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** Welchen Standard-Skin der Client für diese UUID zeigt (DefaultPlayerSkin.get). */
    static int skinIndex(UUID id) {
        return Math.floorMod(id.hashCode(), DEFAULT_SKINS);
    }

    /** Version-2-UUID aus SHA-256 über Name und Zähler; Variante IETF wie jede andere UUID. */
    static UUID candidate(String name, int salt) {
        byte[] h = sha256("baumeister/bewohner/" + name.toLowerCase(Locale.ROOT) + "/" + salt);
        ByteBuffer b = ByteBuffer.wrap(h);
        long msb = b.getLong();
        long lsb = b.getLong();
        msb = (msb & ~0xF000L) | ((long) VERSION << 12);
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** Angezeigter Ping, fest je Name: ein Bewohner, dessen Balken springen, sähe nach Verbindungsproblem aus. */
    public static int latency(String name) {
        return MIN_LATENCY + Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode() * 31, LATENCY_SPAN);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Jede Java-Laufzeit muss SHA-256 haben (MessageDigest-Pflichtliste); fehlt es, ist die JVM kaputt.
            throw new IllegalStateException("SHA-256 fehlt in dieser Java-Laufzeit", e);
        }
    }
}
