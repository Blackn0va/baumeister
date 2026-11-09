package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Schlüssel für die Tests, je Lauf neu mit dem keytool des laufenden JDK erzeugt – kein Schlüssel liegt im Repo
 * (.gitignore sperrt *.p12 nicht, deshalb gar nicht erst anlegen). Alle liegen in einem Ordner, der auch
 * $CREDENTIALS_DIRECTORY der Test-Relays ist (relay.p12, relay.pass).
 *
 * relay: Server-Schlüssel (EC P-256). app, app2: gepinnte Apps. fremd: gültiges EC-Zertifikat ohne Pin. rsa: RSA-2048
 * (das Relay lehnt RSA als eigenen Schlüssel ab).
 */
final class TestKeys {

    private static TestKeys instance;

    final Path dir;
    final Path password;
    final String passwordText;
    private final Map<String, Path> stores = new ConcurrentHashMap<>();

    static {
        // Vor dem ersten TLS-Zugriff im Test-Prozess (siehe Tls.hardenJsse).
        Tls.hardenJsse();
    }

    static synchronized TestKeys get() throws Exception {
        if (instance == null) {
            instance = new TestKeys();
        }
        return instance;
    }

    private TestKeys() throws Exception {
        dir = Files.createTempDirectory("relay-schluessel-");  // umlaut-ok: ASCII-Ordnername
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
        byte[] r = new byte[18];
        new SecureRandom().nextBytes(r);
        passwordText = "t" + HexFormat.of().formatHex(r);
        password = dir.resolve("relay.pass");
        Files.writeString(password, passwordText + "\n", StandardCharsets.UTF_8);
        List<Thread> threads = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        for (String[] spec : new String[][] {{"relay", "EC"}, {"app", "EC"}, {"app2", "EC"}, {"fremd", "EC"},
            {"rsa", "RSA"}}) {
            Thread t = new Thread(() -> {
                try {
                    generate(spec[0], spec[1]);
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        if (!errors.isEmpty()) {
            throw errors.get(0);
        }
    }

    static Path keytool() {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
    }

    private void generate(String alias, String algorithm) throws Exception {
        Path store = dir.resolve(alias + ".p12");
        List<String> cmd = new ArrayList<>(List.of(keytool().toString(), "-genkeypair", "-alias", alias,
                "-keyalg", algorithm, "-validity", "2", "-dname", "CN=" + alias + "-test", "-keystore",
                store.toString(), "-storetype", "PKCS12", "-storepass:file", password.toString()));
        if (algorithm.equals("EC")) {
            cmd.addAll(List.of("-groupname", "secp256r1", "-sigalg", "SHA256withECDSA"));
        } else {
            cmd.addAll(List.of("-keysize", "2048", "-sigalg", "SHA256withRSA"));
        }
        run(cmd);
        stores.put(alias, store);
    }

    static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool scheiterte: " + String.join(" ", cmd) + "\n" + out);
        }
        return out;
    }

    Path store(String alias) {
        return stores.get(alias);
    }

    KeyStore load(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store(alias))) {
            ks.load(in, passwordText.toCharArray());
        }
        return ks;
    }

    PrivateKey key(String alias) throws Exception {
        return (PrivateKey) load(alias).getKey(alias, passwordText.toCharArray());
    }

    X509Certificate cert(String alias) throws Exception {
        return (X509Certificate) load(alias).getCertificate(alias);
    }

    String pin(String alias) throws Exception {
        return Pins.of(cert(alias));
    }

    private static void delete(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            System.err.println("Testschlüssel nicht gelöscht: " + root + " (" + e + ")");
        }
    }
}
