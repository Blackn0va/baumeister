package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schlüssel des Relays: Der Pin ist der SHA-256-Fingerabdruck, den auch keytool zeigt (so prüft ihn der Mensch auf
 * dev01 nach), und alles außer genau einem EC-P-256-Schlüssel mit Kette 1 wird abgelehnt.
 */
class KeyMaterialTest {

    @TempDir
    Path dir;

    @Test
    void pinIsTheSha256FingerprintThatKeytoolPrints() throws Exception {
        TestKeys k = TestKeys.get();
        String out = TestKeys.run(List.of(TestKeys.keytool().toString(), "-list", "-v", "-alias", "relay", "-keystore",
                k.store("relay").toString(), "-storetype", "PKCS12", "-storepass:file", k.password.toString()));
        Matcher m = Pattern.compile("SHA256: ([0-9A-F:]{95})").matcher(out);
        assertTrue(m.find(), out);
        String fromKeytool = "sha256:" + m.group(1).replace(":", "").toLowerCase(Locale.ROOT);
        KeyMaterial km = KeyMaterial.load(k.store("relay"), k.password);
        assertEquals(fromKeytool, km.pin());
        assertFalse(km.toString().contains(km.pin()), "toString zeigt den ganzen Pin");
    }

    @Test
    void wrongPasswordIsAConfigError() throws Exception {
        TestKeys k = TestKeys.get();
        Path wrong = dir.resolve("falsch.pass");
        Files.writeString(wrong, "falsch\n", StandardCharsets.UTF_8);
        String m = assertThrows(ConfigException.class, () -> KeyMaterial.load(k.store("relay"), wrong)).getMessage();
        assertTrue(m.contains("lässt sich nicht öffnen"), m);
        assertFalse(m.contains(k.passwordText), "Passwort in der Meldung");
    }

    /**
     * P-256 an den Kurvenparametern erkannt, nicht am Namen: JDK 21 nennt die Kurve per OID, JDK 25 „secp256r1“ – auf
     * dev01 (Temurin 25) lehnte das Relay 2026-11-08 so seinen eigenen Schlüssel ab. Eine andere Kurve bleibt draußen.
     */
    @Test
    void p256IsRecognisedByItsParametersOnEveryJdk() throws Exception {
        for (String curve : new String[] {"secp256r1", "secp384r1"}) {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec(curve));
            java.security.interfaces.ECPrivateKey k =
                    (java.security.interfaces.ECPrivateKey) g.generateKeyPair().getPrivate();
            assertEquals(curve.equals("secp256r1"), KeyMaterial.isP256(k), curve + " auf " + Runtime.version());
        }
    }

    @Test
    void rsaKeyIsRejected() throws Exception {
        TestKeys k = TestKeys.get();
        String m = assertThrows(ConfigException.class, () -> KeyMaterial.load(k.store("rsa"), k.password))
                .getMessage();
        assertTrue(m.contains("EC P-256"), m);
    }

    @Test
    void keystoreWithTwoKeysIsRejected() throws Exception {
        TestKeys k = TestKeys.get();
        KeyStore two = KeyStore.getInstance("PKCS12");
        two.load(null, null);
        char[] pw = k.passwordText.toCharArray();
        two.setKeyEntry("app", k.key("app"), pw, new Certificate[] {k.cert("app")});
        two.setKeyEntry("app2", k.key("app2"), pw, new Certificate[] {k.cert("app2")});
        Path p = dir.resolve("zwei.p12");
        try (OutputStream o = Files.newOutputStream(p)) {
            two.store(o, pw);
        }
        String m = assertThrows(ConfigException.class, () -> KeyMaterial.load(p, k.password)).getMessage();
        assertTrue(m.contains("enthält 2 Schlüssel"), m);
    }

    @Test
    void passwordFileIsTheFirstLineWithoutLineEnd() throws Exception {
        Path p = dir.resolve("p");
        Files.write(p, "geheim\r\nzweite Zeile".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("geheim".toCharArray(), KeyMaterial.password(p));
        Files.write(p, "\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(ConfigException.class, () -> KeyMaterial.password(p)).getMessage().contains("leer"));
        Files.write(p, new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertTrue(assertThrows(ConfigException.class, () -> KeyMaterial.password(p)).getMessage().contains("UTF-8"));
        Files.write(p, new byte[1025]);
        assertTrue(assertThrows(ConfigException.class, () -> KeyMaterial.password(p)).getMessage().contains("1 KiB"));
        assertTrue(assertThrows(ConfigException.class, () -> KeyMaterial.password(dir.resolve("fehlt")))
                .getMessage().contains("nicht lesbar"));
    }

    @Test
    void missingKeystoreIsAConfigError() throws Exception {
        TestKeys k = TestKeys.get();
        assertThrows(ConfigException.class, () -> KeyMaterial.load(dir.resolve("fehlt.p12"), k.password));
    }
}
