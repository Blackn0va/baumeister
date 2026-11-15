# Proben: Lobby und Portalhalle

`LichtProbe.java` misst das Blocklicht in einem gebauten Bereich mit der Lichtberechnung des Servers, nicht mit einer
eigenen Rechnung. Gezählt wird jede Stelle, auf der ein Monster stehen könnte: Luft, darüber Luft, darunter ein voller
undurchsichtiger Block. Monster brauchen seit 1.18 Blocklicht 0.

| Frage | Ergebnis (Datum) | Verwendet in |
|---|---|---|
| Spawnt in der größten Lobby (81 × 81, „riesen große lobys“) nachts etwas? | Nein: 5 447 Stellen, 0 dunkel, niedrigstes Blocklicht 4, die meisten 10–13 (2026-11-15, Paper 26.2, flache Welt) | `Lobby` (Lichtringe, Lichtring am Platz), `LobbyTest.nothingCanSpawnInsideAtNight` |
| Stimmt die eigene Rechnung im Test mit dem Server überein? | Ja für die Aussage „nirgends dunkel“; ohne den Lichtring am Platz meldet der Test dunkle Stellen mitten auf dem Platz (Gegenprobe) | `LobbyTest` |
| Wie lange braucht die größte Lobby, und ruckelt der Server? | 131 961 Aktionen, 32 129 Blöcke gesetzt, 28,5 s, längster Tick 33 ms, kein Tick über 50 ms | – |
| Stehen die Teile in der Welt dort, wo der Plan sie hat? | 17 von 17 Stichproben (`execute if block`): Weg, Eingang, Seiten- und Rückportal, Kuppel, Dach, Platz, Säule, Fensterband | – |

## Wiederholen

Übersetzen gegen die Spigot-API wie die anderen Proben, `plugin.yml` mit `main: LichtProbe`. Auf einem Probeserver
den Baumeister zuerst bauen lassen (Konsole: `bm bauenbei <welt> <x> <y> <z> s riesen große lobys`), dann
`plugins/LichtProbe/bereich.txt` mit `welt x0 y0 z0 x1 y1 z1` (Innenraum) anlegen und den Server mit `LichtProbe.jar`
neu starten. Ergebnis 100 Ticks nach dem Start in `plugins/LichtProbe/licht.txt`.
