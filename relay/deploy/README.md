# Baumeister-Relay – Einrichtung von Hand

Das Relay verbindet das Minecraft-Plugin mit der Baumeister-App auf einem anderen PC
([docs/fernverbindung.md](../../docs/fernverbindung.md), Protokoll [docs/kanal-v1.md](../../docs/kanal-v1.md)):

```
Plugin ──HTTP──▶ 127.0.0.1:18080 [Relay, Benutzer baumeister-relay, Port dauerhaft belegt]
Baumeister-App ══ wss:// TLS + Client-Zertifikat, beide Seiten gepinnt ══▶ <dein-server>:25580 → Router → Relay
```

Der schnelle Weg ist `relay/einrichten.sh` aus dem Paket ([docs/installation.md](../../docs/installation.md),
Stufe 3). Diese Seite zeigt dieselben Schritte einzeln – zum Nachvollziehen oder wenn du Pfade anders willst.

Das Relay hält 18080 auch ohne App belegt (sonst könnte sich ein beliebiger lokaler Prozess dort als App ausgeben)
und setzt jede Plugin-Verbindung ohne angemeldete App sofort zurück – für das Plugin sieht das aus wie eine fehlende
Verbindung. Es erfindet nie 502/503/504. Quellcode und Tests: [`relay/`](..).

| Datei | Ziel auf dem Server |
|---|---|
| `relay/target/baumeister-relay.jar` (gebaut) | `/opt/baumeister-relay/baumeister-relay.jar` (root, 0644) |
| [`baumeister-relay.service`](baumeister-relay.service) | `/etc/systemd/system/baumeister-relay.service` |
| [`relay.properties.beispiel`](relay.properties.beispiel) | `/etc/baumeister-relay/relay.properties` (root, 0644) |
| [`schluessel-erzeugen.sh`](schluessel-erzeugen.sh) <!-- umlaut-ok: Dateiname --> | einmal ausführen, erzeugt `/etc/baumeister-relay/relay.p12` und `relay.pass` (beide 0600 root) |

## Voraussetzungen

- Linux mit systemd ≥ 249 (`SocketBindAllow`; ≥ 247 für `ProtectProc`).
- Java 21 oder neuer mit `keytool`. Die Unit nennt `/opt/temurin-25-jre`; liegt dein Java woanders, den Pfad in
  `ExecStart` und `KEYTOOL=…` anpassen (das macht `einrichten.sh` von selbst).
- Im Router eine Portfreigabe TCP 25580 auf den Server – sonst nichts.

## Einrichten

Bauen: `mvn -q -f relay/pom.xml package` – baut und testet, Ergebnis `relay/target/baumeister-relay.jar` (fester
Zeitstempel im POM: gleicher Commit und gleiches JDK ergeben dieselben Bytes, `sha256sum` zum Abgleich).

Auf dem Server als root:

```sh
systemctl --version | head -n 1
/opt/temurin-25-jre/bin/java -version
useradd --system --user-group --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin baumeister-relay
install -d -m 0755 -o root -g root /opt/baumeister-relay /etc/baumeister-relay
install -m 0644 -o root -g root baumeister-relay.jar /opt/baumeister-relay/
sha256sum /opt/baumeister-relay/baumeister-relay.jar   # gegen den eigenen Bau vergleichen
sh schluessel-erzeugen.sh                       # gibt den Server-Pin aus → in die App (umlaut-ok: Dateiname)
install -m 0644 -o root -g root relay.properties.beispiel /etc/baumeister-relay/relay.properties
nano /etc/baumeister-relay/relay.properties     # erlaubt.mein-pc=sha256:… (Fingerabdruck aus der App)
env CREDENTIALS_DIRECTORY=/etc/baumeister-relay /opt/temurin-25-jre/bin/java -jar /opt/baumeister-relay/baumeister-relay.jar --prüfen
```

`--prüfen` endet mit 0, wenn alles stimmt, mit 2, wenn noch keine App eingetragen ist, und mit 78 samt deutschem
Grund, wenn Datei oder Schlüssel unbrauchbar sind. `--fingerabdruck` gibt den Server-Pin noch einmal aus.
Ohne UTF-8 im Terminal geht auch `--pruefen`. <!-- umlaut-ok: ASCII-Schreibweise des Schalters -->

Dann den Dienst starten. 18080 muss frei sein, sonst versucht das Relay es alle 5 s erneut:

```sh
ss -ltnp 'sport = :18080'                       # belegt noch etwas den Port (z. B. ein alter SSH-Tunnel)?
install -m 0644 -o root -g root baumeister-relay.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now baumeister-relay
journalctl -u baumeister-relay -n 30             # „relay | gestartet | … lokal=127.0.0.1:18080 öffentlich=0.0.0.0:25580“
ss -ltnp | grep -E ':18080|:25580'
cat /run/baumeister-relay/status                 # app=-  bzw.  app=mein-pc seit=…
```

Messen unter Last, z. B. während eines Stadtbaus:

```sh
systemctl show -p MemoryCurrent,MemoryPeak,TasksCurrent baumeister-relay
grep -E 'VmRSS|VmHWM' /proc/$(systemctl show -p MainPID --value baumeister-relay)/status
journalctl -u baumeister-relay | grep 'relay | speicher'   # alle 10 min: Heap, Budget, Apps, Anfragen
```

## App eintragen, Pin entziehen

- **Eintragen:** Zeile `erlaubt.<name>=sha256:<64 hex>` in `/etc/baumeister-relay/relay.properties`. Gilt ohne
  Neustart spätestens nach 2 s (`relay | pins_neu_geladen` im Journal). Name `[a-z0-9-]`, er steht in jeder Antwort als
  `X-Baumeister-App`.
- **Entziehen:** Zeile löschen. Die laufende Sitzung dieses Pins endet binnen 2 s mit Close 1008 `pin_entzogen`, jede
  neue Verbindung scheitert im Handshake. Es gibt keine Sitzungs-Wiederaufnahme, die das umgehen könnte.
- **Vertippt:** Ist die geänderte Datei ungültig, gilt **kein** Pin mehr (fail-closed, `relay | konfig_ungültig` im
  Journal), bis sie wieder stimmt. Ein Tippfehler hebt einen Entzug nie stillschweigend auf.
- Andere Änderungen (Ports, Schlüssel) wirken erst nach `systemctl restart baumeister-relay`.

## Schlüssel wechseln

Die App nimmt zwei Server-Pins an. Neuen Schlüssel unter anderem Namen erzeugen (Skript anpassen oder `keytool` von
Hand wie im Skript), seinen Pin als zweiten Pin in die App, dann `relay.p12`/`relay.pass` tauschen,
`systemctl restart baumeister-relay`, alten Pin aus der App entfernen.

## Rückweg

```sh
systemctl disable --now baumeister-relay         # gibt 18080 und 25580 frei
rm /etc/systemd/system/baumeister-relay.service && systemctl daemon-reload
```

Schlüssel und Konfiguration können in `/etc/baumeister-relay` bleiben (oder mit `rm -r` weg), der Benutzer mit
`userdel baumeister-relay`. Die Portfreigabe 25580 im Router wieder entfernen.

## Gemessen (nicht geraten)

`RelayMeasurementTest` (nur auf Wunsch: `mvn -f relay/pom.xml test -Drelay.messen=ja -Dtest=RelayMeasurementTest`)
startet das Relay mit genau den JVM-Optionen aus der Unit und belastet es. 2026-11-08, Windows 11, Temurin 21.0.12 –
auf dem eigenen Server mit den Befehlen oben nachmessen:

| Größe | Wert |
|---|---|
| Heap-Obergrenze (`maxMemory` bei `-Xmx32m`, SerialGC) / Byte-Budget (40 %) | 30 MiB / 12,4 MiB |
| Arbeitsspeicher nach dem Start | 53 MiB |
| Arbeitsspeicher nach Last, Höchststand | 75 MiB, **76 MiB** |
| Metaspace belegt (Deckel 32 MiB) | 7,6 MiB |
| Code-Cache höchstens belegt (Deckel 16 MiB) | 6,9 MiB |
| Anmeldungen (TLS 1.3, Upgrade, hallo) | 70/s |
| Durchsatz App → Plugin / Plugin → App / 6 parallel | 28 / 19 / 29 MiB/s |

Last: 100 Anmeldungen, je 30 Antworten à 4 MiB und Anfragen à 2 MiB, 10 × 6 parallele Antworten à 3,5 MiB, dazu ein
Plugin, das 16 MiB nicht abholt (das Budget bricht die Antwort ab). Der Durchsatz reicht weit: Ein privater
Internet-Upload ist um ein Vielfaches langsamer.

## Grenzen

- Sperren gelten je IPv4-Adresse bzw. IPv6-/64. Teilt sich die App eine Adresse mit Fremden (Mobilfunk-CGNAT,
  NAT-Loopback des Routers im eigenen Netz), kann ein fremder Fehlschlag sie bis zu 10 min aussperren.
- `MemoryMax=160M` ist der Deckel für den Fehlerfall. Im Speicherbudget des Servers sind 0,1 GiB für das Relay
  vorzusehen (Summe der JVM-Deckel 96 MiB, gemessen höchstens 76 MiB).
