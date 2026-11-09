# Messprobe winhttp_pin – gepinnte wss://-Verbindung über WinHTTP

Frage hinter der Fernverbindung (Kanal v1, `docs/kanal-v1.md`): Was tut WinHTTP wirklich, wenn die App eine beidseitig
gepinnte TLS-Verbindung mit eigenem, nicht exportierbarem Schlüssel aufbaut? Gegenstelle ist ein JSSE-Server wie das
Relay (`RelayProbe.java`: TLS 1.3/1.2, nur ECDHE + AEAD, Client-Zertifikat Pflicht, Kette genau 1), der jede
Beobachtung mitschreibt. Schlüssel beider Seiten entstehen je Lauf neu (keytool bzw. NCrypt) und werden am Ende gelöscht.

```
pwsh -File host\probes\winhttp_pin\run_probe.ps1                       # alle Szenarien, Mitschrift in host\out\probe-winhttp-pin
pwsh -Command "& 'host\probes\winhttp_pin\run_probe.ps1' -Nur voll,text"   # einzelne Szenarien
```

Gemessen 2026-11-08 auf Windows 11 Pro 26300 (Laptop, TPM 2.0, JDK 21.0.12), aus der Claude-App heraus (MSIX). Die
Schlüssel-Schritte zusätzlich von außen (WMI, ohne Paketkontext) – siehe letzte Zeile.

| Frage | Ergebnis (Datum) | Verwendet in |
|---|---|---|
| (a) Ist das Serverzertifikat im Rückruf `SENDING_REQUEST` lesbar, bevor Bytes der Anfrage hinausgehen? | Ja: Rückrufe `CONNECTED_TO_SERVER` (12 ms) → Handshake → `SENDING_REQUEST` mit lesbarem `WINHTTP_OPTION_SERVER_CERT_CONTEXT` (193 ms) → `REQUEST_SENT 243 Bytes` (2026-11-08) | `src/win/pinned_ws.cpp` (`pin_callback`) |
| (a) Lässt sich dort abbrechen? | Ja: `WinHttpCloseHandle` im Rückruf → `WinHttpSendRequest` 12017, die Gegenstelle empfängt **0** HTTP-Bytes. Ohne Abbruch gehen 243 Bytes Upgrade-Anfrage hinaus (ohne Nutzdaten), die zweite Prüfung vor `WinHttpWebSocketCompleteUpgrade` bricht dann ab (2026-11-08) | `src/win/pinned_ws.cpp`, `tests/relay/test_pinned_tls.cpp` |
| Lehnt WinHTTP das selbst signierte Relay ohne Pin-Regel ab? | Ja: 12175, Flags 0x18 (unbekannte CA 0x08 + falscher Name 0x10) – `SECURITY_FLAG_IGNORE_UNKNOWN_CA`/`_CN_INVALID` sind nötig und werden nur mit Pin gesetzt (2026-11-08) | `src/win/pinned_ws.cpp` (Kopfkommentar) |
| Bleibt die Datumsprüfung trotz ignorierter CA? | Ja: abgelaufenes Relay-Zertifikat → 12175, Flags 0x20 (2026-11-08) | `src/win/pinned_ws.hpp` |
| (b) TLS 1.3 mit Client-Zertifikat aus nicht exportierbarem CNG-Schlüssel (TPM)? | Ja: Platform Crypto Provider, ECDSA P-256, Anlegen 388 ms, Zertifikat 180 ms, Signatur je Handshake ~125 ms; Export abgelehnt 0x8009000A; JSSE sieht Kette 1, `TLSv1.3`, `TLS_AES_256_GCM_SHA384` (2026-11-08) | `src/win/client_cert.cpp` |
| (b) Software-Schlüsselspeicher als Rückfall? | Ja: Anlegen 8 ms, Export abgelehnt 0x80090029, gleicher Handshake (2026-11-08) | `src/win/client_cert.cpp` |
| (b) Findet Schannel den Schlüssel nach einem Neustart (frischer Prozess, Zertifikat aus CurrentUser\My)? | Ja mit `CRYPT_KEY_PROV_INFO` am Zertifikat; ohne den Verweis scheitert der Handshake mit **12186** (`ERROR_WINHTTP_CLIENT_CERT_NO_ACCESS_PRIVATE_KEY`, nicht 12185) (2026-11-08) | `src/win/client_cert.cpp` |
| Ohne Client-Zertifikat? | 12044 (`ERROR_WINHTTP_CLIENT_AUTH_CERT_NEEDED`) (2026-11-08) | `src/win/pinned_ws.cpp` |
| Relay kennt den eigenen Pin nicht (Alarm `certificate_unknown`)? | TLS 1.3: Senden ok, `WinHttpReceiveResponse` scheitert mit 0x80090327 (SEC_E_CERT_UNKNOWN); TLS 1.2: schon `WinHttpSendRequest` mit 0x80090327 (2026-11-08) | `src/win/pinned_ws.cpp` (Art `Rejected`) |
| (c) Wie sendet WinHTTP große Nachrichten? | Jede Nachricht EIN Binärrahmen, FIN=1, maskiert, RSV 0 – auch 65 535, 65 536 und 69 634 Bytes; leere Nachricht nur mit `nullptr` (mit Zeiger 87) (2026-11-08) | `src/win/pinned_ws.cpp` (`send_binary`) |
| (c) Wie liefert WinHTTP große Rahmen? | In Stücken: `BINARY_FRAGMENT` je ~16 KiB bzw. je TCP-Lieferung (10 000-Byte-Stücke mit 100 ms Pause → 7 Aufrufe), zuletzt `BINARY_MESSAGE` – unabhängig vom 70-KB-Puffer. Ein vom Server fragmentierter Rahmen (FIN=0 + Fortsetzung) kommt genauso an, ist also nicht unterscheidbar (2026-11-08) | `src/win/pinned_ws.cpp` (`receive`) |
| (c) Falsche Rahmen des Servers? | Maskiert, RSV-Bit, Ping > 125 Bytes: `WinHttpWebSocketReceive` 12152, Verbindung zu. Textrahmen: als `UTF8_MESSAGE` durchgereicht – die App schließt selbst mit 1003 (2026-11-08) | `src/win/pinned_ws.hpp`, `src/relay/relay_link.cpp` |
| (c) Pings des Servers? | Beantwortet WinHTTP selbst (Pong, maskiert, gleiche Nutzlast, ~1 ms); die App sieht keine Steuerrahmen (2026-11-08) | `src/relay/relay_link.hpp` |
| (c) Keepalive der App? | Unaufgeforderter Pong (op 10, maskiert, leer), Vorgabe alle 30 s; `WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL` 20 000 wirkt nur am WebSocket-Handle (Sitzung und Anfrage: 12018) → Pongs bei 20 s und 40 s (2026-11-08) | `src/win/pinned_ws.cpp` |
| (c) Empfangsfrist am WebSocket? | Keine: `WINHTTP_OPTION_RECEIVE_TIMEOUT` am WebSocket-Handle → 12018; eine Frist von 5 s an der Anfrage erbt er nicht (15 s Stille überstanden); 70 s Stille ohne Fehler. Eine tote Leitung meldet nur TCP (nicht gemessen) (2026-11-08) | `src/relay/relay_link.cpp` (eigener hallo-Wächter) |
| (d) Nur TLS 1.2? | Server nur 1.2: `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384` samt Client-Zertifikat aus dem TPM; ebenso, wenn die App nur 1.2 anbietet (Windows 10) (2026-11-08) | `src/win/pinned_ws.cpp` |
| Gilt das auch außerhalb des MSIX-Pakets der Claude-App (wie die echte App)? | Ja: Probe über `Invoke-CimMethod Win32_Process Create` + cmd.exe gestartet – TPM-Schlüssel 278 ms, Zertifikat in CurrentUser\My, frischer Prozess findet und signiert (127 ms), TLS 1.3-Handshake samt Echo bis 69 634 Bytes; Schlüssel und Zertifikat sind innen wie außen dieselben (kein umgeleiteter Speicher) (2026-11-08) | `src/win/client_cert.cpp` |
