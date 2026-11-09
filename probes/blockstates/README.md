# Messprobe Blockzustände

Welche Blockzustände gibt es auf beiden Server-Fassungen? `blockstates.py` startet den Datengenerator des
Vanilla-Server-Jars (`--reports`) in einem leeren Temp-Ordner außerhalb des Repos, liest `reports/blocks.json` und
schreibt je Zustand eine Zeile `minecraft:id[k=v,…]` nach `src/test/resources/blockstates/<fassung>.txt.gz`. Nur
Python-Standardbibliothek; heruntergeladen wird nichts – die Jars liegen im Cache der dev01-Probe.

| Frage | Ergebnis (2026-11-08) | Verwendet in |
|---|---|---|
| Wie viele Zustände hat 26.2? | 1 196 Blöcke, 32 366 Zustände (31 967 mit Eigenschaften, höchstens 7 je Zustand) | `BlockStatesRoundTripTest.COUNTS` |
| … und 1.21.11? | 1 166 Blöcke, 29 671 Zustände (29 282 mit Eigenschaften) | `BlockStatesRoundTripTest.COUNTS` |
| Was kam mit 26.2 dazu? | 30 Blöcke (Zinnober, Schwefel samt Stufen/Treppen/Mauern, `sulfur_spike`, `golden_dandelion`) mit 2 495 Zuständen; `note_block` +200 (Instrumente `trumpet`, `trumpet_exposed`, `trumpet_weathered`, `trumpet_oxidized`). Kein Zustand von 1.21.11 fehlt in 26.2. | – |
| Reihenfolge der Eigenschaften? | Wie Minecraft sie schreibt: meist alphabetisch, aber 276 Zustände nicht – Truhen und Kupfertruhen `type,facing,waterlogged`, `piston_head` und `moving_piston` `type` zuerst. Deshalb vergleicht der Test als Map. | `BlockStatesRoundTripTest.split` |
| Kommt jeder Zustand unverändert durch `BlockSpec.parse(…).asString()` (Stand e343df0)? | Ja: 0 von 62 037 abgelehnt oder verändert | `BlockStatesRoundTripTest` |
| Gleiches Jar = gleiche Datei? | Ja, zweiter Lauf byte-gleich (gzip ohne Zeitstempel und Dateinamen) | – |

Nicht geprüft: ob der Server jeden Zustand über `Bukkit.createBlockData` annimmt und so stehen lässt – das geht nur im
laufenden Spiel (Selbsttest „jeder Block“, Plan B9).

## Aufruf

Aus der Repo-Wurzel; 26.x braucht Java 25, 1.21.x Java 21 (das Skript liest `version.json` im Jar und bricht mit zu
altem Java ab). Je Fassung etwa 12 s.

```bash
python -I probes/blockstates/blockstates.py --java %USERPROFILE%/.local/opt/jdk-25.0.5+8/bin/java.exe C:/swb/dev01-probe/1.21.11/cache/mojang_26.2.jar src/test/resources/blockstates
python -I probes/blockstates/blockstates.py --java java C:/swb/dev01-probe/1.21.11/cache/mojang_1.21.11.jar src/test/resources/blockstates
```

Darin läuft der Generator so (Arbeitsverzeichnis = Temp-Ordner, dorthin entpackt der Bundler `versions/` und
`libraries/`):

```bash
java -DbundlerMainClass=net.minecraft.data.Main -jar <server.jar> --reports --output <temp>/out
```

`--behalten` lässt den Temp-Ordner samt `gen.log` stehen.

## Stand der Dateien

| Fassung | Server-Jar (SHA-256) | Liste | Liste (SHA-256) |
|---|---|---|---|
| 26.2 | `cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5` | `26.2.txt.gz`, 117 751 Bytes | `3be45eb80e14b1b91433f7b07ef2eb74261136fef320e71fc70054acd6b34639` |
| 1.21.11 | `f83b8e093865806f931c7e34aae41b177d4c076335263dd124c75d6d65dd1726` | `1.21.11.txt.gz`, 108 713 Bytes | `25135464c615757613b4e6ffefd60e67349f289adab26cdbda3928267a5fd98b` |

Kommt eine neue Fassung dazu: Skript laufen lassen, `COUNTS` im Test und die Tabellen hier nachziehen.
