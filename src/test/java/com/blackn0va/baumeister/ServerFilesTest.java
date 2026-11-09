package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Die Server-Dateien für dev01 gegen die Eckdaten des Rechners: Heap passt in den RAM, jede JVM-Option ist begründet,
 * der Dienst wertet den Stopp als Erfolg, die Doku nennt das echte HeapGuard-Budget. Dazu die Relay-Dateien
 * (relay/deploy), die mit jedem Paket ausgeliefert werden.
 *
 * Seit 2026-11-09 liegen die dev01-Dateien (Unit, start.sh, Sicherung, Login-Banner, Betriebsdoku) im privaten
 * Ordner betrieb/ – eigenes Git-Repo, nie im öffentlichen. Ohne ihn werden die dev01-Prüfungen übersprungen
 * (assumeTrue), die Relay-Prüfungen laufen immer. Wer die Annahme zur harten Bedingung macht, bricht jeden Bau
 * außerhalb des eigenen Rechners.
 */
class ServerFilesTest {

    private static final long GIB = 1024L * 1024 * 1024;
    /** dev01, gelesen 2026-10-29: 7,4 – free -h zeigt GiB (Gi), deshalb als GiB gerechnet. */
    private static final double RAM_GIB = 7.4;
    /** JVM außerhalb des Heaps (Metaspace, Code-Cache, Threads, Netty, G1) – geschätzt, siehe betrieb/docs/server.md. */
    private static final double JVM_OVERHEAD_GIB = 1.3;
    /**
     * Linux, sshd, journald und ein Rest Dateicache – mindestens. Vorher stand hier 0,6, in start.sh „~1 GB“ und in
     * betrieb/docs/server.md „~1,1 GB“ (Prüfbefund 2026-10-29): Der Test war dadurch schwächer als die Rechnung, die er
     * absichern soll. Jetzt eine Zahl an allen drei Stellen (docsAndScriptUseTheSameCalculation).
     */
    private static final double SYSTEM_GIB = 1.0;
    /**
     * Relay (relay/deploy, seit 2026-11-08): gemessen höchstens 76 MiB Arbeitsspeicher unter Last
     * (RelayMeasurementTest, Windows, Temurin 21.0.12, JVM-Optionen der Unit); die Summe seiner JVM-Deckel (-Xmx,
     * Metaspace, Code-Cache, Direktpuffer) ist 96 MiB. 0,1 GiB deckt beides – damit ist der Rest von 1,1 GiB aus
     * betrieb/docs/server.md bis auf 1,0 GiB System aufgebraucht. MemoryMax=160M ist nur der harte Deckel im Fehlerfall; liefe
     * das Relay bis dorthin, blieben dem System 0,94 statt 1,0 GiB. Auf dev01 nachmessen (VmHWM, MemoryPeak).
     */
    private static final double RELAY_GIB = 0.1;

    private static final Path DEV01 = Path.of("betrieb/server/dev01");
    private static final Path START = DEV01.resolve("start.sh");
    private static final Path DROP_IN = DEV01.resolve("minecraft.service.d/baumeister.conf");
    private static final Path DEV01_DOC = Path.of("betrieb/docs/server.md");
    private static final Path RELAY = Path.of("relay/deploy");

    /** dev01-Datei lesen; ohne den privaten Ordner betrieb/ wird der Test übersprungen statt rot. */
    private static String dev01(Path file) throws Exception {
        assumeTrue(Files.isRegularFile(file), "nur mit dem privaten Betriebsordner betrieb/: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String start() throws Exception {
        return dev01(START);
    }

    /** Wert aus den ausgeführten Zeilen – Kommentare nennen den alten Stand (-Xmx8G) und zählen nicht. */
    private static long heap(String text, String flag) {
        String code = String.join("\n", text.lines().filter(l -> !l.trim().startsWith("#")).toList());
        Matcher m = Pattern.compile(flag + "(\\d+)([GgMm])").matcher(code);
        assertTrue(m.find(), flag + " fehlt in start.sh");
        long n = Long.parseLong(m.group(1));
        return m.group(2).equalsIgnoreCase("G") ? n * GIB : n * 1024 * 1024;
    }

    @Test
    void heapFitsIntoTheRamOfDev01WithoutSwap() throws Exception {
        long xmx = heap(start(), "-Xmx");
        double total = xmx / (double) GIB + JVM_OVERHEAD_GIB + SYSTEM_GIB + RELAY_GIB;
        assertTrue(total <= RAM_GIB, String.format("Heap %.1f GiB + JVM %.1f + System %.1f + Relay %.1f = %.2f GiB"
                + " > RAM %.1f GiB", xmx / (double) GIB, JVM_OVERHEAD_GIB, SYSTEM_GIB, RELAY_GIB, total, RAM_GIB));
    }

    @Test
    void docsAndScriptUseTheSameCalculation() throws Exception {
        String s = start();
        String doc = dev01(DEV01_DOC);
        String jvm = String.format(java.util.Locale.GERMANY, "%.1f GiB", JVM_OVERHEAD_GIB);
        String system = String.format(java.util.Locale.GERMANY, "%.1f GiB", SYSTEM_GIB);
        assertTrue(s.contains(jvm) && s.contains("mindestens " + system), "start.sh rechnet anders als der Test");
        assertTrue(doc.contains(jvm) && doc.contains("mindestens " + system), "betrieb/docs/server.md rechnet anders");
    }

    @Test
    void heapIsFixedAndTouchedAtStart() throws Exception {
        String s = start();
        assertEquals(heap(s, "-Xms"), heap(s, "-Xmx"), "Xms = Xmx, sonst wächst G1 erst unter Last");
        assertTrue(s.contains("-XX:+AlwaysPreTouch"));
    }

    @Test
    void everyJvmOptionCarriesItsReason() throws Exception {
        List<String> lines = start().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^(XMX|HEAP|GC|LOG|PROPS)=.*")) {
                String above = i > 0 ? lines.get(i - 1).trim() : "";
                assertTrue(above.startsWith("#"), "Zeile " + (i + 1) + " ohne Begründung darüber: " + lines.get(i));
            }
        }
    }

    @Test
    void scriptRunsUnderLinux() throws Exception {
        String s = start();
        byte[] raw = Files.readAllBytes(START);
        assertTrue(s.startsWith("#!/bin/sh\n"));
        assertFalse(s.contains("\r"), "CRLF: /bin/sh bricht mit „set: Illegal option -\\r“ ab");
        assertFalse(raw.length > 2 && raw[0] == (byte) 0xEF, "BOM vor dem Shebang");
        // exec: SIGTERM von systemd geht direkt an Java; gc.log braucht den Ordner (sonst startet die JVM nicht).
        assertTrue(s.contains("\nexec java "), "ohne exec bekommt die Shell das SIGTERM");
        int mkdir = s.indexOf("\nmkdir -p logs\n");
        assertTrue(mkdir >= 0 && mkdir < s.indexOf("\nexec java "), "logs/ muss vor dem Start der JVM angelegt sein");
        assertTrue(s.contains("set -f"), "-Xlog:gc* ohne Anführungszeichen braucht ausgeschaltete Platzhalter");
    }

    /**
     * Shell-Variablen aus den Zuweisungen NAME="…" am Zeilenanfang, der Reihe nach, mit $NAME-Bezügen aufgelöst –
     * genau die Form, die start.sh benutzt. Eingerückte Zuweisungen (der Ausweichzweig im if) zählen nicht.
     */
    private static Map<String, String> vars(String text) {
        Map<String, String> v = new LinkedHashMap<>();
        Pattern assign = Pattern.compile("^([A-Z]+)=\"(.*)\"$");
        for (String l : text.lines().toList()) {
            Matcher m = assign.matcher(l);
            if (m.matches()) {
                v.put(m.group(1), expand(m.group(2), v));
            }
        }
        return v;
    }

    private static String expand(String s, Map<String, String> v) {
        Matcher m = Pattern.compile("\\$([A-Z]+)").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(v.getOrDefault(m.group(1), "")));
        }
        m.appendTail(b);
        return b.toString();
    }

    /**
     * Die Vorprüfung in start.sh mit dem echten JDK ausführen. Vorher (Prüfbefund 2026-10-29) prüfte sie mit
     * -Xmx64m: Bei so kleinem Heap lehnt G1 SurvivorRatio=32 ab („must be less than or equal to ergonomic
     * SurvivorRatio maximum (8)“, Exit 1, gemessen mit Temurin 21.0.12) – start.sh wäre bei JEDEM Start auf den
     * Grundsatz ohne Feineinstellung ausgewichen. Ein Ersatz-java in der ersten Probe konnte das nicht zeigen.
     */
    @Test
    void preStartCheckAcceptsTheG1OptionsWithARealJdk() throws Exception {
        String s = start();
        Map<String, String> v = vars(s);
        Matcher check = Pattern.compile("(?m)^if ! java (.*) -version").matcher(s);
        assertTrue(check.find(), "Vorprüfung „if ! java … -version“ fehlt");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String a : expand(check.group(1), v).trim().split("\\s+")) {
            cmd.add(a);
        }
        cmd.add("-version");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "java -version hängt");
        assertEquals(0, p.exitValue(), String.join(" ", cmd) + "\n" + out);
        // Die Prüfung muss dieselben G1-Optionen sehen wie der Start, sonst prüft sie etwas anderes.
        assertTrue(cmd.containsAll(List.of(v.get("GC").trim().split("\\s+"))), String.join(" ", cmd));
        assertFalse(cmd.contains("-XX:+AlwaysPreTouch"), "die Prüfung soll keine 5 GB anfassen");
    }

    /** Ganzzahliger Wert einer -XX:Name=n-Option aus der GC-Variable, null ohne die Option. */
    private static Integer xx(String gc, String name) {
        Matcher m = Pattern.compile("-XX:" + name + "=(\\d+)").matcher(gc);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * Gemessen 2026-11-01 auf dev01 (gc.log, Weltbau Sydney): Mit Aikars Ziel 200 ms und Untergrenze 30 % hielt G1 die
     * junge Generation bei ~2 GB, Young-Pausen p50 96–156 ms, zwei Full GC. Das Ziel muss unter 200 liegen, sonst
     * verkleinert G1 nichts, und die Untergrenze muss Platz zum Verkleinern lassen.
     */
    @Test
    void youngGenerationMayShrinkForShortPauses() throws Exception {
        String gc = vars(start()).get("GC");
        Integer pause = xx(gc, "MaxGCPauseMillis");
        Integer min = xx(gc, "G1NewSizePercent");
        Integer max = xx(gc, "G1MaxNewSizePercent");
        assertTrue(pause != null && pause <= 100, "Pausenziel " + pause + " ms – mit 200 bleibt Eden bei ~2 GB");
        assertTrue(min != null && max != null && min <= 10 && min < max,
                "junge Generation " + min + "–" + max + " % – G1 braucht Spielraum nach unten");
    }

    @Test
    void stopWithSigtermCountsAsSuccess() throws Exception {
        String d = dev01(DROP_IN);
        assertTrue(d.contains("[Service]"));
        assertTrue(d.lines().anyMatch(l -> l.trim().equals("SuccessExitStatus=143")), d);
        // Ein leerer SuccessExitStatus= würde die 0 aus der Unit löschen.
        assertFalse(d.lines().anyMatch(l -> l.trim().equals("SuccessExitStatus=")), d);
    }

    @Test
    void docsNameTheHeapGuardBudgetOfThisHeap() throws Exception {
        long xmx = heap(start(), "-Xmx");
        String doc = dev01(DEV01_DOC);
        assertTrue(doc.contains("**" + HeapGuard.budget(xmx) + "**"),
                "betrieb/docs/server.md nennt nicht das Budget " + HeapGuard.budget(xmx) + " für -Xmx " + xmx);
        assertTrue(doc.contains("**" + HeapGuard.budget(8 * GIB) + "**"), "altes Budget zum Vergleich fehlt");
    }

    /**
     * Unit, Plattenregel und Sicherung zeigen auf dieselbe Stelle (2026-11-07: Umzug auf die NVMe unter /srv/minecraft).
     * Vorher stand der Pfad in vier Dateien von Hand; ein vergessener Eintrag hieße: Minecraft startet im leeren
     * Einhängepunkt, oder die tägliche Sicherung packt einen Ordner, der nicht mehr der Server ist.
     */
    @Test
    void unitMountRuleAndBackupAgreeOnTheServerFolder() throws Exception {
        String unit = dev01(DEV01.resolve("minecraft.service"));
        String disk = dev01(DEV01.resolve("minecraft.service.d/platte.conf"));
        String backup = dev01(DEV01.resolve("backup/minecraft-backup.sh"));
        Matcher dir = Pattern.compile("(?m)^WorkingDirectory=(\\S+)$").matcher(unit);
        assertTrue(dir.find(), "WorkingDirectory fehlt");
        String root = dir.group(1);
        assertTrue(unit.contains("\nExecStart=" + root + "/start.sh\n"), "ExecStart nicht im Server-Ordner");
        assertTrue(disk.contains("\nRequiresMountsFor=" + root + "\n"), "Plattenregel zeigt woandershin");
        assertTrue(backup.contains("\nSRC=" + root + "\n"), "Sicherung packt einen anderen Ordner");
        Matcher name = Pattern.compile("(?m)^Description=(.+)$").matcher(unit);
        assertTrue(name.find() && name.group(1).contains("Paper"), "Name der Unit veraltet (Paper seit 2026-10-30)");
    }

    // ------------------------------------------------------------------ Relay (relay/deploy)

    private static String relayFile(String name) throws Exception {
        return Files.readString(RELAY.resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * Aktive Einträge der Unit: Fortsetzungszeilen zusammengefügt, Kommentare und Abschnittsköpfe weg; je Schlüssel
     * alle Werte in Reihenfolge (LoadCredential, SocketBindAllow kommen mehrfach vor).
     */
    private static Map<String, List<String>> directives(String unit) {
        Map<String, List<String>> d = new LinkedHashMap<>();
        for (String line : unit.replace("\\\n", " ").lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";") || l.startsWith("[")) {
                continue;
            }
            int eq = l.indexOf('=');
            assertTrue(eq > 0, "Zeile ohne „=“ in der Relay-Unit: " + l);
            d.computeIfAbsent(l.substring(0, eq), k -> new ArrayList<>()).add(l.substring(eq + 1).replaceAll("\\s+",
                    " ").strip());
        }
        return d;
    }

    private static List<String> relayJvmFlags(Map<String, List<String>> d) {
        String exec = d.get("ExecStart").get(0);
        // Temurin 25 seit der zweiten Sicherheitsprüfung (M4) – nicht ein veraltetes System-Java; relay/einrichten.sh
        // trägt auf anderen Servern das gefundene Java ein.
        Matcher m = Pattern.compile("^/opt/temurin-25-jre/bin/java (.*?) -jar ").matcher(exec);
        assertTrue(m.find(), "ExecStart startet nicht mit /opt/temurin-25-jre/bin/java … -jar: " + exec);
        return List.of(m.group(1).split(" "));
    }

    /** MiB-Wert einer Option wie -Xmx32m oder -XX:MaxMetaspaceSize=32m. */
    private static long mib(List<String> flags, String prefix) {
        for (String f : flags) {
            if (f.startsWith(prefix)) {
                Matcher m = Pattern.compile("(\\d+)([mMgG])$").matcher(f);
                assertTrue(m.find(), "Größe ohne m/g: " + f);
                long n = Long.parseLong(m.group(1));
                return m.group(2).equalsIgnoreCase("g") ? n * 1024 : n;
            }
        }
        throw new AssertionError(prefix + " fehlt in ExecStart der Relay-Unit");
    }

    /** Härtung laut Plan A2; jeder Wert genau so – ein „=no“ oder ein Tippfehler im Schlüssel fiele sonst nicht auf. */
    @Test
    void relayUnitIsHardened() throws Exception {
        Map<String, List<String>> d = directives(relayFile("baumeister-relay.service"));
        String[][] expected = {
            {"NoNewPrivileges", "yes"}, {"ProtectSystem", "strict"}, {"ProtectHome", "yes"}, {"PrivateTmp", "yes"},
            {"PrivateDevices", "yes"}, {"ProtectKernelTunables", "yes"}, {"ProtectKernelModules", "yes"},
            {"ProtectKernelLogs", "yes"}, {"ProtectControlGroups", "yes"},
            {"RestrictAddressFamilies", "AF_INET AF_INET6 AF_UNIX"}, {"RestrictNamespaces", "yes"},
            {"LockPersonality", "yes"}, {"RestrictRealtime", "yes"}, {"RestrictSUIDSGID", "yes"},
            {"CapabilityBoundingSet", ""}, {"AmbientCapabilities", ""}, {"SystemCallFilter", "@system-service"},
            {"SystemCallErrorNumber", "EPERM"}, {"MemoryMax", "160M"}, {"Restart", "on-failure"},
            {"RestartPreventExitStatus", "78"}, {"SuccessExitStatus", "143"}, {"RuntimeDirectory", "baumeister-relay"},
            {"Environment", "MALLOC_ARENA_MAX=2"}};
        for (String[] kv : expected) {
            assertEquals(List.of(kv[1]), d.get(kv[0]), kv[0]);
        }
        assertEquals(List.of("relay.p12:/etc/baumeister-relay/relay.p12", "relay.pass:/etc/baumeister-relay/relay.pass"),
                d.get("LoadCredential"));
    }

    /**
     * MemoryDenyWriteExecute bräche den JIT (die JVM startete nie, 18080 bliebe frei), ProcSubset=pid versteckte
     * /proc/meminfo und /proc/sys vor der JVM. Beides darf nur als begründeter Kommentar in der Unit stehen.
     */
    @Test
    void relayUnitKeepsTheJitWorkingAndProcReadable() throws Exception {
        String unit = relayFile("baumeister-relay.service");
        Map<String, List<String>> d = directives(unit);
        assertFalse(d.containsKey("MemoryDenyWriteExecute"), "MemoryDenyWriteExecute bricht den JIT");
        assertFalse(d.containsKey("ProcSubset"), "ProcSubset=pid versteckt /proc vor der JVM");
        assertTrue(unit.contains("# NICHT MemoryDenyWriteExecute=yes"), "Begründung zu MemoryDenyWriteExecute fehlt");
        assertTrue(unit.contains("NICHT ProcSubset=pid"), "Begründung zu ProcSubset fehlt");
    }

    @Test
    void relayRunsAsItsOwnUserWithTheFixedJvmFlags() throws Exception {
        Map<String, List<String>> d = directives(relayFile("baumeister-relay.service"));
        assertEquals(List.of("baumeister-relay"), d.get("User"));
        assertEquals(List.of("baumeister-relay"), d.get("Group"));
        List<String> flags = relayJvmFlags(d);
        for (String f : List.of("-Xms16m", "-Xmx32m", "-Xss256k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-XX:-UsePerfData", "-XX:+ExitOnOutOfMemoryError")) {
            assertTrue(flags.contains(f), f + " fehlt in ExecStart der Relay-Unit");
        }
        mib(flags, "-XX:MaxMetaspaceSize=");
        mib(flags, "-XX:ReservedCodeCacheSize=");
        assertTrue(d.get("ExecStart").get(0).endsWith(" -jar /opt/baumeister-relay/baumeister-relay.jar --konfig "
                + "/etc/baumeister-relay/relay.properties"), d.get("ExecStart").get(0));
    }

    /** Die Optionen der Unit mit einem echten JDK – eine abgelehnte Option hieße: Relay startet nie, 18080 bleibt frei. */
    @Test
    void relayJvmFlagsAreAcceptedByARealJdk() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(relayJvmFlags(directives(relayFile("baumeister-relay.service"))));
        cmd.add("-version");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "java -version hängt");
        assertEquals(0, p.exitValue(), String.join(" ", cmd) + "\n" + out);
    }

    /**
     * Die JVM-Deckel des Relays passen in seinen Posten im Speicherbudget und unter MemoryMax. Wer -Xmx des Relays
     * hebt, muss RELAY_GIB (und damit die Rechnung für den Minecraft-Heap) mit anfassen.
     */
    @Test
    void relayJvmCapsFitIntoItsBudgetTermAndUnderMemoryMax() throws Exception {
        Map<String, List<String>> d = directives(relayFile("baumeister-relay.service"));
        List<String> flags = relayJvmFlags(d);
        long caps = mib(flags, "-Xmx") + mib(flags, "-XX:MaxMetaspaceSize=") + mib(flags, "-XX:ReservedCodeCacheSize=")
                + mib(flags, "-XX:MaxDirectMemorySize=");
        assertTrue(caps <= RELAY_GIB * 1024, "JVM-Deckel " + caps + " MiB > Relay-Posten " + RELAY_GIB + " GiB");
        Matcher max = Pattern.compile("^(\\d+)M$").matcher(d.get("MemoryMax").get(0));
        assertTrue(max.find() && Long.parseLong(max.group(1)) > caps, "MemoryMax unter den JVM-Deckeln");
    }

    /**
     * Schlüsselskript: EC P-256, 10 Jahre, neutraler Name ohne SAN (zweite Sicherheitsprüfung 2026-11-08, N7: das
     * Zertifikat sieht jeder, der einen Handshake beginnt), Passwort nur aus Datei, Temurin 25, Unix-Zeilenenden –
     * sonst bricht /bin/sh ab.
     */
    @Test
    void relayKeyScriptCreatesThePinnedKeyAsSpecified() throws Exception {
        byte[] raw = Files.readAllBytes(RELAY.resolve("schluessel-erzeugen.sh"));  // umlaut-ok: Dateiname
        String s = new String(raw, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("#!/bin/sh\n"));
        assertFalse(s.contains("\r"), "CRLF im Schlüsselskript");
        for (String part : List.of("set -eu", "umask 077", "-keyalg EC -groupname secp256r1", "-validity 3650",
                "-dname \"CN=relay\"", "KEYTOOL=${KEYTOOL:-/opt/temurin-25-jre/bin/keytool}", "-storetype PKCS12",
                "-storepass:file \"$PASS.neu\"", "chmod 0600", "chown root:root", "head -c 32 /dev/urandom")) {
            assertTrue(s.contains(part), part + " fehlt im Schlüsselskript");
        }
        assertFalse(Pattern.compile("-storepass [^:]").matcher(s).find(), "Passwort auf der Kommandozeile");
        assertFalse(s.contains("-ext \"SAN="), "SAN verrät Adressen");
    }

    /**
     * Beispielkonfiguration, Unit, Plugin und Login-Banner zeigen auf dieselben Ports, Credentials und dieselbe
     * Statusdatei. Ein abweichender Port hieße: SocketBindAllow verbietet das Binden, oder das Plugin spricht ins Leere.
     */
    @Test
    void relayExampleUnitPluginAndBannerAgree() throws Exception {
        String example = relayFile("relay.properties.beispiel");
        Map<String, List<String>> d = directives(relayFile("baumeister-relay.service"));
        Matcher local = Pattern.compile("(?m)^lokal\\.port=(\\d+)$").matcher(example);
        Matcher external = Pattern.compile("(?m)^öffentlich\\.port=(\\d+)$").matcher(example);
        assertTrue(local.find() && external.find(), "Ports fehlen in relay.properties.beispiel");
        assertEquals(List.of("tcp:" + local.group(1), "tcp:" + external.group(1)), d.get("SocketBindAllow"));
        assertEquals(List.of("any"), d.get("SocketBindDeny"));
        assertTrue(example.contains("\nlokal.adresse=127.0.0.1\n"), "Plugin-Seite nicht auf Loopback");
        assertTrue(example.contains("\nschlüssel.datei=credential:relay.p12\n"));
        assertTrue(example.contains("\nschlüssel.passwort=credential:relay.pass\n"));
        String config = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        assertTrue(config.contains("url: http://127.0.0.1:" + local.group(1) + "/v1/chat/completions"),
                "llm.url des Plugins zeigt nicht auf die Plugin-Seite des Relays");
        Matcher status = Pattern.compile("(?m)^status\\.datei=(\\S+)$").matcher(example);
        assertTrue(status.find());
        Path banner = DEV01.resolve("motd/10-blackdev01-info");
        if (Files.isRegularFile(banner)) {
            // Login-Banner von dev01 (privat): liest dieselbe Statusdatei wie die Unit sie anlegt.
            String motd = Files.readString(banner, StandardCharsets.UTF_8);
            assertTrue(motd.contains("RELAY_STATUS=\"" + status.group(1) + "\""), "Login-Banner liest eine andere Datei");
        }
        assertEquals(List.of("baumeister-relay"), d.get("RuntimeDirectory"));
        assertTrue(status.group(1).startsWith("/run/baumeister-relay/"), "Statusdatei liegt nicht im RuntimeDirectory");
    }
}
