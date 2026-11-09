# Messproben

Kleine Programme, die eine Frage durch Messen klären statt durch Vermuten. Laufen gegen Piper aus
`%USERPROFILE%\.local\opt\piper-messung` (Piper 2023.11.14-2, Thorsten medium, SHA-256 wie in `deps.lock.json`).

| Frage | Ergebnis (Datum) | Verwendet in |
|---|---|---|
| Wie spricht Piper Minecraft-Wörter aus? (`piper_aussprache_probe.py`) | „Creeper“ schon „Krieper“ (kɾˈiːpɜ); Nether → „Näther“, Hardcore → „Haadkohr“, Enderauge → „Ender-Auge“ treffen besser (2026-11-04) | `src/voice/speech_text.cpp` |
| Wie hoch klingt Vera, wenn Piper um r langsamer spricht und um r schneller abgespielt wird? (`piper_cartoon_probe.py`) | Grundton 108 / 128 / 143 / 146 Hz bei r = 1,0 / 1,2 / 1,3 / 1,4; Dauer 5,4 / 5,0 / 4,7 / 5,0 s – über 1,4 kaum mehr Höhe (2026-11-06) | `src/voice/voice_style.hpp`, `voice_out.cpp` (`with_pitch`) |
| Was tut WinHTTP bei einer beidseitig gepinnten wss://-Verbindung – Pin vor dem ersten Byte, TPM-Schlüssel, Rahmen, Keepalive, TLS 1.2? (`winhttp_pin/`, Gegenstelle JSSE wie das Relay) | Serverzertifikat im Rückruf SENDING_REQUEST lesbar, Abbruch dort → 0 HTTP-Bytes beim Server; TPM-Schlüssel mit CRYPT_KEY_PROV_INFO geht (ohne: 12186); jede Nachricht ein Rahmen, Empfang in ~16-KiB-Stücken; keine Empfangsfrist am WebSocket; Einzelheiten in `winhttp_pin/README.md` (2026-11-08) | `src/win/pinned_ws.cpp`, `src/win/client_cert.cpp`, `src/relay/relay_link.cpp` |
