package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pin der geschatteten Abhängigkeiten (T-033): Jedes Jar, das ins Plugin-Jar wandert, muss genau die Datei sein, gegen
 * die gebaut und live geprüft wurde. Maven prüft beim Holen nur SHA-1 gegen das Repo (checksumPolicy fail); dieser Test
 * hält zusätzlich SHA-256 fest – liegt im lokalen Maven-Lager eine andere Datei, wird der Bau rot statt still eine
 * fremde Fassung einzupacken. Gleiche Tabelle in lib/README.md; wer die Fassung hebt, zieht beide und
 * THIRD-PARTY-NOTICES.md nach und misst live neu.
 */
class PacketEventsPinTest {

    /** Klasse aus dem Jar → SHA-256 des Jars (gemessen 2026-11-22, SHA-1 jeweils gleich der Repo-Prüfsumme). */
    static final Map<String, String> PINNED = new LinkedHashMap<>();

    static {
        PINNED.put("com.github.retrooper.packetevents.PacketEvents",
                "cf1afcfd1ccffba5108b3227918f1d8d4d0f10fc6b654901cff42d0070e427ca");
        PINNED.put("io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder",
                "13e771d25e75aab7c7c9d3f383464bd956a7bee49f3d948cc74a1870e8440a0f");
        PINNED.put("io.github.retrooper.packetevents.impl.netty.NettyManagerImpl",
                "ca76c618085561a3cb578c743ef88c0be0c559d3e1e7a7d9934cafb3da2fa7d8");
        PINNED.put("net.kyori.adventure.text.Component",
                "551e536b9ea868f30e72c7900a309b35124ee7d4889fa3b3aed0910299751a26");
        PINNED.put("net.kyori.adventure.key.Key",
                "eec172d63db77b40eb7abeeb25f65eedea89bd30264d057b68b12fecb731be5e");
        PINNED.put("net.kyori.adventure.nbt.BinaryTag",
                "f26ef6bff5fcdd817902b4dfd353561d98cb131b4ecb67341ff00ef2ed1336f8");
        PINNED.put("net.kyori.examination.Examinable",
                "c9237ffecb05428f6eff86216246ac70ce0b47b04c08ea7ca35020fde57f8492");
        PINNED.put("net.kyori.examination.string.StringExaminer",
                "7d01fc25a4bb3af0e1662685455f4541fbf4626216ea5846e455c1491e156b8c");
    }

    @Test
    void shadedJarsAreThePinnedOnes() throws Exception {
        for (Map.Entry<String, String> e : PINNED.entrySet()) {
            Class<?> c = Class.forName(e.getKey(), false, getClass().getClassLoader());
            Path jar = Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(Files.isRegularFile(jar), e.getKey() + " kommt nicht aus einem Jar: " + jar);
            assertEquals(e.getValue(), sha256(jar), jar.getFileName() + " (" + e.getKey() + ")");
        }
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
