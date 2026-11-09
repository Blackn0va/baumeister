# Kanal v1 – Host-App ↔ Relay (verbindlich)

Stand: 2026-11-08. Die Host-App auf einem PC mit Grafikkarte verbindet sich über eine Adresse im Internet mit dem
Relay auf dem Minecraft-Server, statt über einen SSH-Tunnel. Relay (`relay/`, Java) und App (`host/`,
C++) bauen nach diesem Text. **Beide Testsuiten lesen `docs/kanal-v1-testvektoren.json`** (Erzeuger:
`tools/kanal/testvektoren.py`). Wer hier etwas ändert, ändert Vektoren und beide Seiten mit – oder macht Kanal v2.

```
Plugin ──HTTP──▶ 127.0.0.1:18080 [Relay]  ◀══ wss:// gepinnt beidseitig ══  Host-App
                 (immer gebunden)            <dein-server>:25580 → Router → Relay:25580
```

## 1. Transport
- Adresse `wss://<host>:<port>/kanal/v1`, Vorgabe-Port 25580.
- **TLS 1.3**, ersatzweise TLS 1.2 nur mit ECDHE und AEAD (AES-GCM, ChaCha20-Poly1305) – Windows 10 kann kein
  TLS 1.3 in Schannel. Keine Sitzungs-Wiederaufnahme: Jede Verbindung prüft die Pins neu, ein entzogener Pin gilt sofort.
- **Gegenseitig gepinnt, keine CA, keine Namensprüfung** (ein exakter Pin ist strenger als jede Namensprüfung):
  - Pin = `sha256:` + 64 Hex-Zeichen klein über die **DER-Bytes des Blattzertifikats** (nicht nur des Schlüssels).
  - Relay: Das Client-Zertifikat ist Pflicht, die Kette genau **1** Zertifikat lang, sein Pin steht in der Liste. Pins
    haben Namen `[a-z0-9-]{1,32}` (z. B. `gpu-pc`, `laptop`).
  - App: bis zu **2** Server-Pins (für einen Schlüsselwechsel). Ohne Pin keine Verbindung – „unbekannte CA ignorieren“
    gibt es nur zusammen mit einem Pin.
  - Schlüssel beider Seiten: EC P-256, selbst signiert.
- **Upgrade** (RFC 6455): `GET /kanal/v1 HTTP/1.1` mit `Host`, `Upgrade: websocket`, `Connection: Upgrade`,
  `Sec-WebSocket-Key`, `Sec-WebSocket-Version: 13`, `Sec-WebSocket-Protocol: baumeister-kanal.v1`. Kopf ≤ 8 KiB, kein
  Body, sonst nichts nötig. Antwort `101` mit `Sec-WebSocket-Accept` und demselben Protokoll; alles andere: 400, 404
  oder 426 und Verbindung zu. Der Upgrade-Request trägt keine Daten – die App prüft den Server-Pin spätestens vor dem
  Abschluss des Upgrades.
- Keine WebSocket-Erweiterungen (kein `permessage-deflate`): RSV-Bits sind immer 0.

## 2. Rahmen
- Nachrichten nur als **Binärrahmen** (`0x2`) mit FIN=1 – keine Fragmente, keine Fortsetzungsrahmen.
- Steuerrahmen: Ping, Pong, Close, Nutzlast ≤ 125 Bytes.
- App → Relay **maskiert**, Relay → App **unmaskiert**.
- Nutzlast eines Binärrahmens ≤ 2 + 4096 + 65 536 Bytes.
- Verstoß: Close mit Code (Abschnitt 8) und Grund aus den Testvektoren (`textrahmen`, `rsv`, `maske_fehlt`,
  `maske_vom_server`, `fragment`, `steuerrahmen_zu_lang`).

## 3. Nachricht (Nutzlast eines Binärrahmens)
```
u16 Kopflänge (Big Endian, 1..4096) | Kopf: JSON-Objekt, UTF-8 | Datenstück: 0..65 536 Bytes
```
- Sender schreiben den Kopf kompakt. **Leser nehmen jede Schlüsselreihenfolge und Leerraum an und ignorieren
  unbekannte Felder.** Ein unbekannter Typ `t` ist dagegen ein Protokollfehler.
- `id`: u32, 1 … 4 294 967 295. Das Relay vergibt sie je Verbindung aufsteigend, die App antwortet mit derselben.

| `t` | Richtung | Felder | Datenstück |
|---|---|---|---|
| `hallo` | App zuerst, Relay antwortet | `v` = 1; `app` bzw. `relay` = Kennung (≤ 64 Zeichen); App: `max` = parallele Aufträge 1…6 | leer |
| `anfrage` | Relay → App | `id`, `m` (`GET`/`POST`), `p` (Pfad samt Abfrage, Abschnitt 5), `ct` (optional), `len` (0 … 2 MiB) | erstes Stück |
| `antwort` | App → Relay | `id`, `s` (200 … 599 – keine 1xx, siehe unten), `ct` (optional), `len` (0 … 16 MiB) | erstes Stück |
| `daten` | beide | `id` | nächstes Stück |
| `abbruch` | beide | `id` | leer |

- Eine Anfrage bzw. Antwort ist fertig, wenn die Summe ihrer Stücke `len` erreicht. Mehr Bytes als `len`:
  Protokollfehler.
- `ct`: höchstens 128 druckbare ASCII-Zeichen ohne CR/LF (`typ_ungueltig`).
- `s` ab 200 (2026-11-08, Sicherheitsprüfung M1): Eine 1xx-Zwischenantwort verwirft der HttpClient des Plugins und
  liest den Body als endgültige Antwort – damit ließen sich Kopfzeilen wie `X-Baumeister-App` unterschieben.
- Vor dem `hallo` ist jede andere Nachricht ein Protokollfehler. `v` ≠ 1: Close 1008.
- `antwort`/`daten` mit unbekannter `id` (z. B. nach einem `abbruch` unterwegs): Nachricht verwerfen und zählen,
  kein Close.
- Nach `abbruch` gehen zu dieser `id` keine Nachrichten mehr raus; was noch eintrifft, wird verworfen.
- Gründe für Ablehnung (Testvektoren): `kopf_leer`, `kopf_zu_lang`, `kopf_abgeschnitten`, `kopf_kein_utf8`,
  `kopf_kein_objekt`, `typ_unbekannt`, `id_fehlt`, `id_ungueltig`, `status_ungueltig`, `stueck_zu_gross`,
  `laenge_ungueltig`, `pfad_ungueltig`, `methode_ungueltig`, `typ_ungueltig`.

## 4. Lebenszeichen, Gleichzeitigkeit, Grenzen
- Das Relay pingt alle **20 s**. Die App schickt alle **20 s** einen **unaufgeforderten Pong** und beantwortet Pings
  von selbst – WinHTTP kann keine Pings senden (gemessen 2026-11-08, `host/probes/winhttp_pin`). Das Relay zählt
  **jeden** Rahmen als Lebenszeichen, ein unaufgeforderter Pong ist kein Fehler. Kommt **45 s** lang kein Rahmen,
  gilt die Verbindung für das Relay als tot. Die App hat am WebSocket keine Empfangsfrist; eine tote Leitung meldet
  ihr TCP (Keepalive).
- `id`s steigen streng; schickt das Relay mehr als `max` gleichzeitig, antwortet die App auf den überzähligen mit
  `abbruch`.
- Verbindung tot oder geschlossen: Das Relay schließt alle offenen lokalen Anfragen **ohne Antwort**; die App bricht
  alle lokalen Aufträge ab (ihr Server hört dann auf zu rechnen).
- Das Relay schickt höchstens `max` Aufträge gleichzeitig (Vorgabe 6 – der App-Server erlaubt 8 Verbindungen);
  weitere warten (≤ 64). Konnte eine Anfrage 30 s lang an keine App gehen: lokale Verbindung zu (RST).
- Relay: Byte-Budget aller gepufferten Daten ≤ 48 MiB; darüber wird die größte offene Antwort abgebrochen.
- Relay wartet auf das Ende einer Antwort höchstens 1 900 s (Plugin erlaubt `llm.timeout-sekunden` bis 1 800).
- Nur **eine** App-Verbindung je Pin gleichzeitig; eine neue mit demselben Pin ersetzt die alte (Close 1001).

## 5. Pfade (das Relay prüft vor dem Senden, die App prüft erneut)
- Erlaubt: genau `/health`, oder `/v1/…` bzw. `/baumeister/v1/…` mit Segmenten aus `[A-Za-z0-9_-]`, getrennt durch
  `/`; optional `?` und eine Abfrage aus `[A-Za-z0-9_.~%=&+,-]`. Insgesamt ≤ 2 048 Zeichen. Das Komma kam am
  2026-11-08 dazu: Das Plugin schickt `?imspiel=Name1,Name2` (CompanionVoiceLink.heardUri), die erste Fassung hätte
  den Long-Poll ab zwei Spielern im Voice-Chat mit 404 beendet (gefunden beim Bau des Relays).
- Verboten: Segmente `.`/`..`, `%2e`/`%2E` (im ganzen Pfad **samt Abfrage**), `%` ohne zwei Hex-Ziffern dahinter,
  kodierte Steuerzeichen `%00`–`%1F` und `%7F` (ein kodiertes NUL kann im C++-Server einen String abschneiden),
  `//`, `\`, `#`, Leerzeichen, Steuerzeichen, absolute URLs, abschließendes `/` – die Liste `pfade` in den
  Testvektoren ist verbindlich. Weicht das Relay hier ab, schließt die App die ganze Verbindung.

## 6. Relay ↔ Plugin (Loopback, `127.0.0.1:18080`)
- **Immer gebunden**, damit sich kein anderer Prozess dort einhängen kann (über `gehört` kommen Sätze, die Vera als
  Worte eines Spielers behandelt). Ohne angemeldete App: annehmen und sofort mit RST schließen (`SO_LINGER 0`) – das
  Plugin sieht das wie einen geschlossenen Tunnel.
- HTTP/1.1, eine Anfrage je Verbindung, Kopf ≤ 16 KiB, `Content-Length` Pflicht bei POST, kein `chunked`, Body
  ≤ 2 MiB (sonst 413). Pfad nicht erlaubt: 404 vom Relay selbst.
- Antwort an das Plugin: Status und `Content-Type` der App **unverändert**, `Content-Length`,
  `X-Baumeister-App: <pin-name>`, `Connection: close`. Das Relay erfindet **nie** 502/503/504 – der LlmClient schickte
  sonst die ganze Generierung neu.
- Legt das Plugin auf, bevor die Antwort da ist (EOF am Socket): `abbruch` an die App.
- Statusdatei `/run/baumeister-relay/status` (`app=<pin-name> seit=<iso>` oder `app=-`) für das Login-Banner.

## 7. App ↔ eigener Server
- Jeder Auftrag ist eine eigene Loopback-HTTP-Anfrage an `127.0.0.1:<server_port>`: Methode, Pfad, `Content-Type`,
  Body. Den `Host`-Kopf setzt der Transport (Loopback), so greifen die Prüfungen des Servers unverändert.
- Antwort: Status, `Content-Type`, Body → `antwort` und `daten`.
- Eigener Server nicht erreichbar: `abbruch`. `abbruch` vom Relay oder Kanalverlust: lokale Verbindung schließen.

## 8. Close-Codes
`1000` normal · `1001` geht weg (Neustart, ersetzte Verbindung) · `1002` Protokollfehler · `1003` falscher
Datentyp · `1008` Richtlinie (Pin entzogen, falsche Version) · `1009` zu groß · `1011` interner Fehler.
Der Close-Grund ist der Code aus Abschnitt 2/3 (z. B. `kopf_zu_lang`). Dazu von der App: `hallo_fehlt`,
`hallo_doppelt`, `version_ungueltig` (1008), `kennung_ungueltig`, `max_ungueltig`, `rahmen_zu_gross` (1009).
Zuordnung: 1003 nur für `textrahmen`, 1009 für `stueck_zu_gross` und `rahmen_zu_gross`, sonst 1002.
Fragmentierte Rahmen setzt WinHTTP auf der App-Seite still zusammen – das Relay schickt ohnehin keine.

## 9. Testvektoren (`docs/kanal-v1-testvektoren.json`)
- `websocket`: Schlüssel → Accept (RFC 6455, Abschnitt 1.3).
- `pin`: Testzertifikat (nur öffentlich) und sein Pin.
- `gueltig`: Rahmen → erwarteter Kopf und Daten; große Rahmen als SHA-256 mit Erzeugungsregel.
- `ungueltig`: Rahmen → Ablehnung mit Grund.
- `pfade`: erlaubt und verboten.
