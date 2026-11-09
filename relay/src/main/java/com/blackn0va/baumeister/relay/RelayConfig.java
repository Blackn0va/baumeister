package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Konfiguration des Relays (Vorgabe /etc/baumeister-relay/relay.properties, Beispiel in
 * relay/deploy/relay.properties.beispiel).
 *
 * Eigener, strenger Leser statt java.util.Properties: Properties liest ISO-8859-1 (Umlaute in Schlüsseln gingen
 * kaputt), nimmt doppelte Schlüssel stillschweigend (der letzte gewinnt – ein zweiter erlaubt.gpu-pc überschriebe den
 * ersten) und kennt Fortsetzungszeilen. Hier: UTF-8, eine Zeile „schlüssel=wert“, # für Kommentare; doppelte oder
 * unbekannte Schlüssel sind ein Fehler. Ein Tippfehler wie „lokal.adrese“ fiele sonst still auf die Vorgabe zurück.
 *
 * Fail-closed (ASVS V14.1.3): Jeder Fehler ist eine ConfigException mit deutschem Satz, das Relay startet dann nicht.
 * Fehlen dürfen nur Werte mit Vorgabe aus der Spezifikation (Adressen, Ports, Statusdatei). Keine erlaubte App ist kein
 * Fehler: Das Relay hält dann trotzdem 18080 belegt und weist jede App ab – siehe Main (--prüfen endet mit 2).
 *
 * Dateiverweise: absoluter Pfad oder {@code credential:<name>} = $CREDENTIALS_DIRECTORY/<name> (systemd
 * LoadCredential=; die Quelldatei bleibt 0600 root, der Dienstbenutzer liest nur die Kopie in seinem Credential-Ordner).
 *
 * Threads: unveränderlich. Fehler: ConfigException.
 */
record RelayConfig(Path file, InetSocketAddress local, InetSocketAddress external, Path keystore, Path password,
        Path status, Map<String, String> pins) {

    static final Path DEFAULT_FILE = Path.of("/etc/baumeister-relay/relay.properties");
    static final String LOCAL_ADDRESS = "lokal.adresse";
    static final String LOCAL_PORT = "lokal.port";
    static final String EXTERNAL_ADDRESS = "öffentlich.adresse";
    static final String EXTERNAL_PORT = "öffentlich.port";
    static final String KEYSTORE = "schlüssel.datei";
    static final String PASSWORD = "schlüssel.passwort";
    static final String STATUS = "status.datei";
    static final String PIN_PREFIX = "erlaubt.";

    private static final int MAX_FILE = 64 * 1024;
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
            + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    /** Beginnt mit Hex-Ziffer oder „:“ – nur dann behandelt InetAddress den Text als Literal und fragt kein DNS. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]{1,44}");
    private static final Pattern CREDENTIAL = Pattern.compile("credential:([A-Za-z0-9_.-]{1,64})");

    RelayConfig {
        pins = Collections.unmodifiableMap(new LinkedHashMap<>(pins));
    }

    /** env: Umgebung des Prozesses (für $CREDENTIALS_DIRECTORY). */
    static RelayConfig load(Path file, Map<String, String> env) throws ConfigException {
        Map<String, String> values = read(file);
        Map<String, String> pins = new LinkedHashMap<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(PIN_PREFIX)) {
                String name = key.substring(PIN_PREFIX.length());
                if (!Pins.NAME.matcher(name).matches()) {
                    throw new ConfigException("Name „" + name + "“ in " + key + " ungültig: erlaubt sind 1–32 Zeichen "
                            + "aus a–z, 0–9 und „-“.");
                }
                if (!Pins.PIN.matcher(e.getValue()).matches()) {
                    throw new ConfigException(key + ": Pin muss „sha256:“ plus 64 kleine Hex-Zeichen sein.");
                }
                String other = seen.putIfAbsent(e.getValue(), name);
                if (other != null) {
                    throw new ConfigException("Pin von „" + name + "“ steht schon bei „" + other
                            + "“ – je Zertifikat genau ein Name (er steht in X-Baumeister-App).");
                }
                pins.put(name, e.getValue());
            } else if (!key.equals(LOCAL_ADDRESS) && !key.equals(LOCAL_PORT) && !key.equals(EXTERNAL_ADDRESS)
                    && !key.equals(EXTERNAL_PORT) && !key.equals(KEYSTORE) && !key.equals(PASSWORD)
                    && !key.equals(STATUS)) {
                throw new ConfigException("Unbekannter Schlüssel „" + key + "“ in " + file
                        + " (Tippfehler? Bekannt: " + String.join(", ", LOCAL_ADDRESS, LOCAL_PORT, EXTERNAL_ADDRESS,
                        EXTERNAL_PORT, KEYSTORE, PASSWORD, STATUS) + ", " + PIN_PREFIX + "<name>).");
            }
        }
        InetAddress localAddress = address(values.getOrDefault(LOCAL_ADDRESS, "127.0.0.1"), LOCAL_ADDRESS);
        if (!localAddress.isLoopbackAddress()) {
            throw new ConfigException(LOCAL_ADDRESS + " muss eine Loopback-Adresse sein (127.0.0.1 oder ::1) – sonst "
                    + "stünde die Plugin-Seite ohne Anmeldung im Netz.");
        }
        InetSocketAddress local = new InetSocketAddress(localAddress,
                port(values.getOrDefault(LOCAL_PORT, "18080"), LOCAL_PORT));
        InetSocketAddress external = new InetSocketAddress(
                address(values.getOrDefault(EXTERNAL_ADDRESS, "0.0.0.0"), EXTERNAL_ADDRESS),
                port(values.getOrDefault(EXTERNAL_PORT, "25580"), EXTERNAL_PORT));
        if (local.getPort() != 0 && local.getPort() == external.getPort()) {
            throw new ConfigException(LOCAL_PORT + " und " + EXTERNAL_PORT + " sind gleich (" + local.getPort() + ").");
        }
        Path keystore = fileRef(values.get(KEYSTORE), KEYSTORE, env);
        Path password = fileRef(values.get(PASSWORD), PASSWORD, env);
        String statusText = values.getOrDefault(STATUS, "/run/baumeister-relay/status");
        if (!absolute(statusText)) {
            throw new ConfigException(STATUS + " muss ein absoluter Pfad sein.");
        }
        Path status = path(statusText, STATUS);
        return new RelayConfig(file, local, external, keystore, password, status, pins);
    }

    /** Zeilen „schlüssel=wert“ in Dateireihenfolge; doppelte Schlüssel sind ein Fehler. */
    static Map<String, String> read(Path file) throws ConfigException {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE) {
                throw new ConfigException(file + " ist größer als 64 KiB – das ist keine Relay-Konfiguration.");
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ConfigException("Konfiguration " + file + " nicht lesbar (" + e.getClass().getSimpleName()
                    + "). Vorlage: relay/deploy/relay.properties.beispiel.", e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ConfigException(file + " ist kein gültiges UTF-8 (Umlaute in Latin-1 gespeichert?).", e);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new ConfigException(file + ", Zeile " + (i + 1) + ": „schlüssel=wert“ erwartet.");
            }
            String key = trimmed.substring(0, eq).strip();
            String value = trimmed.substring(eq + 1).strip();
            if (key.isEmpty() || value.isEmpty()) {
                throw new ConfigException(file + ", Zeile " + (i + 1) + ": Schlüssel oder Wert leer.");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new ConfigException(file + ", Zeile " + (i + 1) + ": „" + key + "“ steht doppelt.");
            }
        }
        return values;
    }

    /** Nur IP-Literale: Ein Name hieße DNS beim Start, und wer DNS lenkt, lenkte das Binden. */
    private static InetAddress address(String text, String key) throws ConfigException {
        String t = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        if (!IPV4.matcher(t).matches() && !(t.contains(":") && IPV6.matcher(t).matches())) {
            throw new ConfigException(key + ": „" + text + "“ ist keine IP-Adresse (Namen sind nicht erlaubt).");
        }
        try {
            return InetAddress.getByName(t);
        } catch (UnknownHostException e) {
            throw new ConfigException(key + ": „" + text + "“ ist keine gültige IP-Adresse.", e);
        }
    }

    private static int port(String text, String key) throws ConfigException {
        if (!text.matches("[0-9]{1,5}") || Integer.parseInt(text) > 65_535) {
            throw new ConfigException(key + ": „" + text + "“ ist kein Port (0–65535; 0 nur für Tests).");
        }
        return Integer.parseInt(text);
    }

    private static Path fileRef(String text, String key, Map<String, String> env) throws ConfigException {
        if (text == null) {
            throw new ConfigException(key + " fehlt (z. B. " + key + "=credential:"
                    + (key.equals(KEYSTORE) ? "relay.p12" : "relay.pass") + ").");
        }
        var m = CREDENTIAL.matcher(text);
        if (m.matches()) {
            String name = m.group(1);
            if (name.equals(".") || name.equals("..")) {
                throw new ConfigException(key + ": „" + name + "“ ist kein Credential-Name.");
            }
            String dir = env.get("CREDENTIALS_DIRECTORY");
            if (dir == null || dir.isBlank() || !absolute(dir)) {
                throw new ConfigException(key + "=" + text + " braucht $CREDENTIALS_DIRECTORY (systemd LoadCredential=). "
                        + "Von Hand als root: env CREDENTIALS_DIRECTORY=/etc/baumeister-relay … --prüfen");
            }
            return path(dir, "CREDENTIALS_DIRECTORY").resolve(name);
        }
        if (!absolute(text)) {
            throw new ConfigException(key + ": „" + text + "“ muss ein absoluter Pfad oder credential:<name> sein.");
        }
        return path(text, key);
    }

    /** Absolut auf dem laufenden System – oder ein Linux-Pfad mit „/“ (Tests unter Windows prüfen dev01-Dateien). */
    private static boolean absolute(String text) {
        try {
            return text.startsWith("/") || Path.of(text).isAbsolute();
        } catch (InvalidPathException e) {
            // Unter Windows etwa „credential:../x“ (Doppelpunkt) – kein Pfad, also auch kein absoluter.
            return false;
        }
    }

    private static Path path(String text, String key) throws ConfigException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new ConfigException(key + ": „" + text + "“ ist kein gültiger Pfad.", e);
        }
    }
}
