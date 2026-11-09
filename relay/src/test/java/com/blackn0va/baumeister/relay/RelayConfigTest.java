package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Konfiguration fail-closed: Jede Unklarheit ist ein Fehler mit deutschem Satz, nie eine stille Vorgabe.
 */
class RelayConfigTest {

    private static final String PIN = "sha256:" + "ab".repeat(32);
    private static final String PIN2 = "sha256:" + "cd".repeat(32);

    @TempDir
    Path dir;

    private Map<String, String> env() {
        return Map.of("CREDENTIALS_DIRECTORY", dir.toString());
    }

    private static final String KEYS = "schlüssel.datei=credential:relay.p12\nschlüssel.passwort=credential:relay.pass\n";

    private RelayConfig load(String text) throws Exception {
        Path f = dir.resolve("relay.properties");
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return RelayConfig.load(f, env());
    }

    private String error(String text) {
        return assertThrows(ConfigException.class, () -> load(text)).getMessage();
    }

    @Test
    void minimalFileUsesTheDefaultsOfTheSpec() throws Exception {
        RelayConfig c = load(KEYS + "erlaubt.gpu-pc=" + PIN + "\n");
        assertEquals("127.0.0.1", c.local().getAddress().getHostAddress());
        assertEquals(18_080, c.local().getPort());
        assertEquals("0.0.0.0", c.external().getAddress().getHostAddress());
        assertEquals(25_580, c.external().getPort());
        assertEquals(Path.of("/run/baumeister-relay/status"), c.status());
        assertEquals(Map.of("gpu-pc", PIN), c.pins());
        assertEquals(dir.resolve("relay.p12"), c.keystore());
        assertEquals(dir.resolve("relay.pass"), c.password());
    }

    @Test
    void credentialReferencesNeedTheCredentialsDirectory() throws Exception {
        Path f = dir.resolve("relay.properties");
        Files.writeString(f, KEYS, StandardCharsets.UTF_8);
        String m = assertThrows(ConfigException.class, () -> RelayConfig.load(f, Map.of())).getMessage();
        assertTrue(m.contains("CREDENTIALS_DIRECTORY"), m);
        assertTrue(error("schlüssel.datei=credential:../x\nschlüssel.passwort=credential:relay.pass\n")
                .contains("absoluter Pfad oder credential"));
        assertTrue(error("schlüssel.datei=credential:..\nschlüssel.passwort=credential:relay.pass\n")
                .contains("kein Credential-Name"));
        assertTrue(error("schlüssel.passwort=credential:relay.pass\n").contains("schlüssel.datei fehlt"));
        assertTrue(error("schlüssel.datei=relay.p12\nschlüssel.passwort=credential:relay.pass\n")
                .contains("absoluter Pfad"));
    }

    /** Ein Tippfehler fiele sonst still auf die Vorgabe zurück; ein doppelter Pin-Name überschriebe den ersten. */
    @Test
    void unknownAndDuplicateKeysAreErrors() {
        assertTrue(error(KEYS + "lokal.adrese=127.0.0.1\n").contains("Unbekannter Schlüssel „lokal.adrese“"));
        assertTrue(error(KEYS + "erlaubt.gpu-pc=" + PIN + "\nerlaubt.gpu-pc=" + PIN2 + "\n").contains("doppelt"));
        assertTrue(error(KEYS + "erlaubt.a=" + PIN + "\nerlaubt.b=" + PIN + "\n").contains("steht schon bei"));
    }

    /** Die Plugin-Seite hat keine Anmeldung – sie darf nie außerhalb von Loopback lauschen. */
    @Test
    void pluginSideMustBeLoopback() throws Exception {
        for (String bad : List.of("0.0.0.0", "192.168.178.47", "::", "[::]", "10.0.0.1")) {
            assertTrue(error(KEYS + "lokal.adresse=" + bad + "\n").contains("Loopback"), bad);
        }
        for (String ok : List.of("127.0.0.1", "::1", "[::1]", "127.0.0.5")) {
            assertTrue(load(KEYS + "lokal.adresse=" + ok + "\n").local().getAddress().isLoopbackAddress(), ok);
        }
    }

    /** Nur IP-Literale: Ein Name hieße DNS beim Start. */
    @Test
    void addressesMustBeIpLiterals() {
        for (String bad : List.of("localhost", "dev01", "dein-server.example.net", "256.0.0.1", "1.2.3", "::g")) {
            assertTrue(error(KEYS + "öffentlich.adresse=" + bad + "\n").contains("IP-Adresse"), bad);
        }
    }

    @Test
    void portsMustBeInRangeAndDistinct() {
        for (String bad : List.of("70000", "-1", "abc", "1e3", "")) {
            String m = error(KEYS + "lokal.port=" + bad + "\n");
            assertTrue(m.contains("kein Port") || m.contains("leer"), bad + ": " + m);
        }
        assertTrue(error(KEYS + "lokal.port=25580\n").contains("gleich"));
    }

    @Test
    void pinsNeedAValidNameAndTheExactFormat() {
        for (String bad : List.of("sha256:" + "AB".repeat(32), "sha256:" + "ab".repeat(31) + "a", "ab".repeat(32),
                "sha1:" + "ab".repeat(32), "sha256:" + "ab".repeat(33), "sha256:" + "ag".repeat(32))) {
            assertTrue(error(KEYS + "erlaubt.gpu-pc=" + bad + "\n").contains("Pin muss"), bad);
        }
        for (String bad : List.of("GPU", "gpu_pc", "a".repeat(33), "gpu.pc", "")) {
            String m = error(KEYS + "erlaubt." + bad + "=" + PIN + "\n");
            assertTrue(m.contains("ungültig"), bad + ": " + m);
        }
    }

    @Test
    void statusFileMustBeAbsolute() {
        assertTrue(error(KEYS + "status.datei=status\n").contains("absoluter Pfad"));
    }

    @Test
    void fileMustBeUtf8AndSmall() throws Exception {
        Path f = dir.resolve("relay.properties");
        Files.write(f, ("# Schl" + (char) 0xFC + "ssel\n" + KEYS).getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(assertThrows(ConfigException.class, () -> RelayConfig.load(f, env())).getMessage()
                .contains("UTF-8"));
        Files.writeString(f, KEYS + "#" + "x".repeat(70_000) + "\n", StandardCharsets.UTF_8);
        assertTrue(assertThrows(ConfigException.class, () -> RelayConfig.load(f, env())).getMessage()
                .contains("64 KiB"));
        assertTrue(assertThrows(ConfigException.class, () -> RelayConfig.load(dir.resolve("fehlt.properties"),
                env())).getMessage().contains("nicht lesbar"));
    }

    @Test
    void bomCrlfCommentsAndSpacesAreAccepted() throws Exception {
        RelayConfig c = load((char) 0xFEFF + "# Kommentar\r\n  lokal.port = 18081 \r\n\r\n" + KEYS.replace("\n",
                "\r\n") + "erlaubt.laptop=" + PIN2 + "\r\n");
        assertEquals(18_081, c.local().getPort());
        assertEquals(Map.of("laptop", PIN2), c.pins());
    }

    @Test
    void emptyValuesAndLinesWithoutEqualsAreErrors() {
        assertTrue(error(KEYS + "lokal.port=\n").contains("leer"));
        assertTrue(error(KEYS + "lokal.port\n").contains("schlüssel=wert"));
        assertTrue(error(KEYS + "=18080\n").contains("schlüssel=wert"));
    }

    @Test
    void zeroPinsIsAllowedSoThePortStaysTaken() throws Exception {
        assertTrue(load(KEYS).pins().isEmpty());
    }
}
