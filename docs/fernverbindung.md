# Fernverbindung – Baumeister-App auf einem anderen PC

Läuft der Minecraft-Server auf einem Rechner ohne Grafikkarte, rechnet die KI in der Baumeister-App auf deinem PC.
Die App verbindet sich dafür von sich aus über das Internet mit dem **Relay**, einem kleinen Dienst auf dem Server.
Das Relay nimmt die Anfragen des Plugins an und reicht sie über diese eine Verbindung an die App weiter; die
Antworten kommen denselben Weg zurück.

```
Plugin ──HTTP──▶ 127.0.0.1:18080 [Relay, eigener Benutzer, Port dauerhaft belegt]
                                        ▲
Baumeister-App (dein PC) ══ wss:// TLS, beide Seiten gepinnt ══ <dein-server>:25580 → Router → Relay
```

- **Einrichten:** [installation.md, Stufe 3](installation.md#stufe-3--ki-auf-einem-anderen-pc) (ein Skript) oder
  von Hand nach [relay/deploy/README.md](../relay/deploy/README.md).
- **Protokoll:** [kanal-v1.md](kanal-v1.md), Quellcode in [`relay/`](../relay).
- **App:** Einstellungen → Minecraft-Server → „Der Server läuft“ → *im Internet über eine Adresse (HTTPS)*.

## Was sie schützt

| Gefahr | Gegenmittel | Norm |
|---|---|---|
| Ein Fremder gibt sich als Relay aus (DNS, Router, falsche Weiterleitung) | Die App prüft den genauen Fingerabdruck des Server-Zertifikats, **bevor** ein Byte einer HTTP-Anfrage hinausgeht (gemessen: 0 Byte). Der TLS-Handshake samt Client-Zertifikat geht vorher hinaus, bei TLS 1.2 unverschlüsselt – das Zertifikat ist öffentlich und trägt einen neutralen Namen. Es gibt keine CA und kein „trotzdem verbinden“ | ASVS V9.2.1, OWASP A02 |
| Ein Fremder gibt sich als App aus | Das Relay verlangt ein Client-Zertifikat, dessen Fingerabdruck eingetragen ist. Der Schlüssel der App liegt nicht exportierbar im TPM, sonst im Schlüsselspeicher von Windows | ASVS V9.2.3, V6.4.2, OWASP A07 |
| Ein entzogener Zugang wirkt nach | Es gibt keine Sitzungs-Wiederaufnahme. Das Relay liest die Liste alle 2 s neu und beendet die laufende Sitzung eines gelöschten Pins (Close 1008 `pin_entzogen`) | OWASP A07 |
| Durchprobieren und Scans auf 25580 | Handshake-Frist 10 s, höchstens 4 gleichzeitige Handshakes je IPv4-Adresse bzw. IPv6-/64 (32 insgesamt), Sperre 10 s bis 10 min. Ohne gültiges Zertifikat endet alles im TLS-Handshake; mit gültigem höchstens 2 offene Anmeldungen je Pin | ASVS V11.1.4 |
| Ein anderer Prozess auf dem Server belegt 18080 und spielt die App | Das Relay hält 18080 belegt, solange es läuft – auch ohne App und auch, wenn 25580 gerade nicht bindbar ist. Frei ist der Port nur kurz beim Neustart nach einem Absturz (5 s) und bei kaputter Konfiguration (Exit 78) bis zur Reparatur. Über „gehört“ kommen Sätze, die Vera als Worte eines Spielers behandelt | OWASP A04 |
| Eine App spricht für fremde Spieler | `fernverbindung.apps.<name>.spieler` in der Plugin-`config.yml` bindet Gesprochenes an die Spieler dieser App (Kopf `X-Baumeister-App`; das Relay nimmt nur Status 200–599, eine 1xx-Antwort könnte den Kopf sonst fälschen). Gesprochenes bestätigt nie einen Befehl, das „ja“ muss getippt sein | OWASP A01 |
| Das Relay wird zum Sprungbrett | Weitergeleitet werden nur `/health`, `/v1/…` und `/baumeister/v1/…` aus einer Freigabeliste, beide Seiten prüfen. Grenzen: Anfrage 2 MiB, Antwort 16 MiB, gepuffert zusammen höchstens 40 % des Heaps (12,4 MiB bei `-Xmx32m`), 6 parallel, 64 wartend | ASVS V5.1.3, V12.1.1 |
| Ein Fehler im Relay | Eigener Benutzer ohne Shell, kein root, gehärtete Unit (`NoNewPrivileges`, `ProtectSystem=strict`, `MemoryMax=160M`, `SocketBindAllow` nur 18080/25580), aktuelles Java statt eines alten System-Java (dessen TLS wäre das Einzige aus dem Internet Erreichbare). Der Schlüssel kommt über `LoadCredential=` | OWASP A05, A06 |
| Mehr offene Ports | Nach außen braucht es nur 25580/tcp. SSH gehört nicht ins Internet | OWASP A05 |

### Was sie bewusst nicht schützt

- **Jede eingetragene App sieht alles.** Das Relay gibt jeden Auftrag an die zuerst angemeldete App mit freiem Platz:
  Bau-Aufträge von Admins, Gespräche mit Vera, Sprachaufnahmen aller Spieler – und ihre Antworten gelten für alle.
  Die Bindung an Spieler gilt nur für „gehört“. Deshalb **nur eigene Geräte eintragen**, nie die App eines Mitspielers.
- **18080 nimmt jeden Prozess auf dem Server an**, wie ein SSH-Tunnel auch. Ein fremder Prozess dort hätte ohnehin
  mehr Möglichkeiten.
- **Bauteile, die ein Admin gespeichert hat**, gelten als vertrauenswürdig – auch ihre Befehlsblöcke laufen ohne Klick,
  wenn das Modell so ein Bauteil in einen Admin-Bau setzt. Bauteile von Nicht-Admins tragen keine Daten.
- **Ohne TPM** liegt der Schlüssel der App im Schlüsselspeicher von Windows. Das Export-Verbot ist dort keine harte
  Sperre: Schadcode im selben Windows-Konto könnte ihn kopieren. Dann den Pin auf dem Server entziehen.

## Im Router

Nur eine Portfreigabe: TCP 25580 auf den Server, über IPv4. Vorher nachsehen, dass es keine Freigabe für Port 22 und
keinen „Exposed Host“ gibt. **Von außen testen**, z. B. über einen Handy-Hotspot – aus dem eigenen Netz beweist ein
Test nur, dass der Router seine eigene öffentliche Adresse zurück ins Netz leitet (NAT-Loopback). Im eigenen Netz geht
als Adresse auch `wss://<lan-ip-des-servers>:25580/kanal/v1`, dann braucht es keine Freigabe.

## Betrieb

- **Zustand:** `/run/baumeister-relay/status` (`app=-` oder `app=<name> seit=<Zeit>`).
- **Log:** `journalctl -u baumeister-relay`. Das Log enthält keine Inhalte und keine ganzen Pins.
- **App eintragen oder entziehen:** die Zeile `erlaubt.<name>=…` in `/etc/baumeister-relay/relay.properties`
  anlegen oder löschen. Das wirkt ohne Neustart nach spätestens 2 s. Ist die Datei nach einer Änderung ungültig, gilt
  **kein** Pin mehr, bis sie wieder stimmt. Ein Tippfehler hebt einen Entzug so nie auf.
- **Sprache nur für die eigenen Spieler:** in der Plugin-`config.yml` `fernverbindung.apps.<name>.spieler` mit den
  Spielern an diesem PC. Dann gilt Gesprochenes über diese App nur für sie.
- **Schlüssel des Servers wechseln:** Die App nimmt zwei Server-Fingerabdrücke an. Ablauf in
  [relay/deploy/README.md](../relay/deploy/README.md).

## Fehlerbilder

| Was du siehst | Ursache | Abhilfe |
|---|---|---|
| App: „Kein Fingerabdruck des Servers eingetragen“ | Feld leer | Server-Pin aus der Einrichtung eintragen (`--fingerabdruck` zeigt ihn erneut) |
| App-Log: „falscher Fingerabdruck des Servers – gezeigt sha256:…“ | Vertippt, oder unter der Adresse antwortet ein anderes Gerät | Auf dem Server mit `--fingerabdruck` vergleichen. Stimmt er, zeigt die Weiterleitung auf das falsche Gerät |
| App verbindet sich, bricht im Handshake ab; Journal des Relays nennt einen unbekannten Pin | Fingerabdruck der App nicht eingetragen | Zeile `erlaubt.<name>=…` anlegen |
| Zeitüberschreitung, nur von außen | Portfreigabe fehlt, oder der DNS-Name zeigt auf eine alte Adresse | Router prüfen; `nslookup <dein-server>` gegen die öffentliche Adresse vergleichen |
| Nach Fehlversuchen 10 min gesperrt | Die Sperre gilt je Adresse. Hotspot (CGNAT) und NAT-Loopback teilen sich Adressen mit anderen | Warten oder `systemctl restart baumeister-relay` |
| Im Spiel: „Das LLM ist nicht erreichbar, auch nach drei Versuchen …“ | Keine App angemeldet, das Relay setzt die Plugin-Verbindung sofort zurück | Status-Datei ansehen, App starten |

## Verwaltung von unterwegs

Das Relay trägt nur die Aufträge der App, keine Verwaltung. Für SSH von unterwegs ist ein VPN der bessere Weg
(z. B. WireGuard im Router): Ohne passenden Schlüssel antwortet es auf kein Paket. Port 22 direkt freizugeben heißt,
dass er im Internet ständig durchprobiert wird.
