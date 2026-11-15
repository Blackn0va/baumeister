# Messprobe Mannequin-Physik

## Schwimmen (2026-11-07, Paper 1.21.11-132)

Wunsch. `SwimProbe.java`: Ein Geist-Spieler schwimmt drei Strecken in
gemauerten Becken (6 tief, 0,12 Block je Tick), Vera folgt mit der Folgeregel von CompanionService – „jetzt“ mit der
Laufsteuerung von dev01 (eingefroren in `WalkDeployed.java`), „neu“ mit `CompanionWalk`. Rohdaten:
`schwimmen-2026-11-07.txt`.

| Strecke | jetzt: Teleports / festgehangen / Abstand Mittel / Max / Tiefenfehler / an Land | neu |
|---|---|---|
| see (28 an der Oberfläche) | 0 / 0 / 3,3 / 4,1 / – / – | 0 / 0 / 3,1 / 4,1 / – / – |
| tauchen (5 hinab, 12 am Grund, hoch) | 2 / 0 / 3,4 / 4,2 / 2,5 / – | 0 / 0 / 3,3 / 4,1 / 0,3 / – |
| ufer (über 1 Block Rand an Land) | 1 / 22 / 3,9 / 12,5 / – / Tick 192 (Teleport) | 0 / 2 / 3,2 / 4,1 / – / Tick 153 |

Geist an Land: Tick 137. Zwischenschritt mit Ausstieg 0,3 (Vanilla-Wert bei Kollision im Wasser): Vera verließ das
Wasser, stieg ~0,7 und fiel zurück, an Land wieder nur per Teleport (Tick 189) – deshalb `CLIMB_OUT` 0,5.

```bash
javac --release 21 -encoding UTF-8 -cp lib/spigot-api-1.21.11-R0.2-SNAPSHOT.jar -d out probes/mannequin/SwimProbe.java probes/mannequin/WalkDeployed.java src/main/java/com/blackn0va/baumeister/companion/CompanionWalk.java src/main/java/com/blackn0va/baumeister/companion/CompanionFollow.java src/main/java/com/blackn0va/baumeister/companion/CompanionPath.java
```

`plugin.yml` mit `main: SwimProbe` nach `out/`, `jar cf SwimProbe.jar -C out .`, als einziges Probe-Plugin in
`plugins/`, `level-name` auf eine frische flache Welt. Nicht gemessen: Strömung, Sprintschwimmen, echter Spieler.

**Mitfahren** (`CompanionRide`): Auf dem Spigot-Testserver per RCON geprüft – ein Mannequin steigt als zweiter Gast in
ein Boot (Passagiere Schwein + Mannequin). Die Bukkit-API ließe auch einen dritten zu (Einsteigen erzwungen), deshalb
zählt `CompanionRide.seats` die Plätze selbst.

Klärt durch Messen, wie sich ein Mannequin (Veras Körper) bewegt, wenn man ihm Schwerkraft und Bewegung lässt, und ob
die Laufsteuerung des Plugins (`CompanionWalk`) auf einem echten Server ankommt. Ein Wegwerfserver ohne Spieler, alle
Spuren laufen gleichzeitig in eigenen Chunks: Ohne Spieler tickt eine Welt Entities nur 300 Ticks lang.

| Frage | Ergebnis (2026-11-06, Paper 1.21.11-132) | Verwendet in |
|---|---|---|
| Was sperrt die Physik? | Nur `immovable` (`Mannequin.isImmobile`/`isEffectiveAi`, javap der Server-Jar). Unbeweglich: kein Fallen, `setVelocity` wirkungslos; Teleport geht weiter | `CompanionService.walkMode` |
| Fällt sie mit Schwerkraft? | 5 Blöcke in 15 Ticks, danach `isOnGround` | `CompanionWalk` |
| Wie schnell läuft sie über `setVelocity`? | 0,25 je Tick ergibt genau 0,25 Block je Tick | `CompanionWalk.SPEED` |
| Stufen? | Halbe Stufe von selbst (step_height 0,6); voller Block nur mit Sprung 0,42 | `CompanionWalk` (Sprung bei Hindernis) |
| Mauer, Leiter? | 4 hohe Mauer hält auf; Leiter klettert sie mit 0,12 je Tick hoch | `CompanionWalk.STUCK_TICKS` |
| Wasser? | Sinkt auf den Grund; +0,04 je Tick hält sie an der Oberfläche | `CompanionWalk.SWIM_UP` |
| Teleport-Schritte wie vorher? | Gehen durch die Mauer (36 Blöcke in 80 Ticks) | Grund für den Umbau |
| Laufsteuerung auf Parcours (Block, Stufe, Becken, 15 Blöcke) | Angekommen nach 57 Ticks (flach: 52) | `CompanionWalk` |
| Vor 4 hoher Mauer | Nach 40 Ticks Teleport, dann angekommen | `CompanionWalk.STUCK_TICKS` |

Rohdaten: `ergebnis-2026-11-06.txt` (Attribute, Endlagen). Nicht gemessen: Spigot statt Paper, wie der Client das
Laufen zeigt (Beinbewegung), Spieler in der Nähe.

## Im Weg (2026-11-15, Paper 26.2)

Rückmeldung des Nutzers: „manchmal steht der vera im weg rum und checkt das nicht, er soll sich mehr wie ein mensch
verhalten“. `WegProbe.java`: Ein Geist-Spieler läuft auf Vera zu oder an ihr vorbei, je Lage zwei Veras nebeneinander –
„jetzt“ (Steuerung bis 2026-11-15: nah genug → stehen bleiben) und „neu“ (`CompanionYield.decide` vor dem Folgen, wie
`CompanionService.yieldStep`). Gesteuert alle 2 Ticks wie im Dienst. Gezählt: Aufrufe mit Überlappung (berührt) und mit
Vera bis 2,5 Blöcke voraus in seiner Bahn (im Weg).

| Lage | berührt jetzt → neu | im Weg jetzt → neu | Verwendet in |
|---|---|---|---|
| frontal, freies Feld | 3 → 0 | 5 → 1 | `CompanionYield` (Bahn) |
| frontal, 3 breiter Gang | 3 → 0 | 5 → 4 | `CompanionYield` (Bahn) |
| Vera in der einzigen Tür | 4 → 0 | 6 → 0 | `CompanionYield` (Platz) |
| 1 breiter Gang, er kehrt um | 4 → 0 | 6 → 4 (1 Platztausch) | `CompanionYield.lead` ohne Nische → tauschen |
| normales Folgen (Vergleich) | 0 → 0 | 0 → 0, Abstand 3,6/5,7 gleich | – |

Gegenprobe zur Tauschregel: Lief Vera ohne Nische weiter vor ihm her, waren es im engen Gang 10 Berührungen und 22
Aufrufe im Weg. Nicht gemessen: echter Spieler (Schieben durch den Client, Klicks), Zielstrahl beim Bauen, mehrere
Spieler, Wasser, Nether, Leitern.

Übersetzen wie oben, zusätzlich mit `src/main/java/com/blackn0va/baumeister/companion/CompanionYield.java`;
`plugin.yml` mit `main: WegProbe`. Server: frisches Paper 26.2, `online-mode=false`, flache Welt mit Lagen
(`generator-settings`), Aktivierungsbereich 0, nur `WegProbe.jar` in `plugins/`. Ergebnis in
`plugins/WegProbe/weg.txt`.

## Wiederholen

1. Paper-Jar samt `libraries/`, `versions/`, `cache/` in einen leeren Ordner (von dev01, kein Netz nötig).
2. `eula=true`, `server.properties` mit `online-mode=false`, `server-ip=127.0.0.1`, `level-type=minecraft\:flat`;
   `spigot.yml` mit allen `entity-activation-range`-Werten auf 0 (sonst ticken ferne Entities nur gedrosselt).
3. Übersetzen und starten:

```bash
javac --release 21 -cp lib/spigot-api-1.21.11-R0.2-SNAPSHOT.jar -d out probes/mannequin/MannequinProbe.java src/main/java/com/blackn0va/baumeister/companion/CompanionWalk.java
```

`plugin.yml` nach `out/`, `jar cf MannequinProbe.jar -C out .`, in `plugins/` legen, `java -jar paper-….jar nogui`.
Die Probe beendet den Server selbst und schreibt `plugins/MannequinProbe/spuren.csv` und `notizen.txt`.

## Wegsuche und Posen (2026-11-06)

`VeraPathProbe.java` lässt Vera (CompanionWalk) auf sieben Parcours einmal geradeaus wie bisher und einmal mit
`CompanionPath` (A*) laufen. Rohdaten: `weg-2026-11-06.txt`.

| Parcours | Geradeaus | Mit Wegsuche |
|---|---|---|
| Mauer 4 hoch, 13 breit | Teleport (Tick 59) | angekommen Tick 83, kein Teleport |
| U-Falle 3 hoch | Teleport (Tick 50) | angekommen Tick 87 |
| Höhle: Tunnel 1 breit, 2 hoch, 4 Kurven | Teleport (Tick 55) | angekommen Tick 92 |
| Bergauf: Treppe 1 breit, 6 hoch, Seiten offen | Tick 65 | Tick 62 (Sprung vor der Stufe) |
| Steg 1 breit mit 2 Kurven über einer Grube | 5 Stürze, Teleport | Tick 63, 0 Stürze |
| Leiter 5 hoch | Tick 44 | Tick 44 |
| Treppe 1-2-3 | Tick 52 | Tick 47 |

## Folgen in Höhlen (2026-11-07)

`FollowProbe.java`: Ein Geist-Spieler (Koordinaten, 0,216 Block je Tick wie Gehen) läuft fünf Strecken ab, Vera folgt
mit derselben Steuerung wie `CompanionService` – einmal mit dem Ziel bis 2026-11-07 („alt“: schräg hinter dem Spieler,
Teleport aufs Ziel), einmal mit `CompanionFollow` („neu“). Rohdaten: `folgen-2026-11-07.txt`.

| Strecke | alt: Teleports / Ticks im Stein / Schweben / Abstand Mittel / Max | neu |
|---|---|---|
| Gang 1 breit, Kurven, Stufen hoch und runter | 0 / 0 / 0 / 5,9 / 8,9 | 0 / 0 / 0 / 5,3 / 8,1 |
| Gang mit Parallelgang 3 tiefer daneben | 1 / 120 / 0 / 7,4 / 22,8 | 0 / 0 / 0 / 4,3 / 6,8 |
| Wendeltreppe 3×3, 12 Stufen | 0 / 0 / 0 / 6,2 / 11,3 | 0 / 0 / 0 / 4,7 / 8,9 |
| Steg an der Schlucht (6 tief) | 0 / 0 / 59 / 3,9 / 4,2 | 0 / 0 / 0 / 3,9 / 4,2 |
| Offenes Feld mit Bogen | 0 / 0 / 0 / 4,2 / 5,6 | 0 / 0 / 0 / 4,3 / 5,5 |

Übersetzen wie oben, mit `src/main/java/com/blackn0va/baumeister/companion/{CompanionFollow,CompanionPath,
CompanionWalk}.java`; `level-name` auf eine frische Welt stellen. Nicht gemessen: Sprinten, echte Höhlen aus dem
Weltgenerator, Spigot, ein echter Spieler (der Geist steht nie im Block und springt nicht).

## Folgen 2: Umkehren, Abbiegen, Natur-Welt (2026-11-07)

Wunsch.
`FollowProbe` vergleicht jetzt „jetzt“ (Stand e9c9278, eingefroren in `FollowDeployed.java`) mit „neu“
(`CompanionFollow.goal` + `Planner` + `shouldWalk`). Neue Strecken: Umkehren und Abbiegen im Freien, Gang mit Abzweig,
Runde zwischen Säulen, und „blick_“: der Geist schaut alle 3 s für 1 s zu Vera zurück. Natur-Welt mit
`-Dfolge.wild=true`, `level-type=minecraft:normal`, `level-seed=4711`: 6 Orte, die Strecke (18 Ost, 14 Nord, 16 West,
18 Süd, 14 Ost) läuft der Geist per Wegsuche um Bäume und Hügel herum. Rohdaten: `folgen-2026-11-07.txt`,
`folgen-natur-2026-11-07.txt`.

| Natur-Welt | Abstand Mittel jetzt → neu | Max | weg vom Spieler |
|---|---|---|---|
| wild0 | 4,2 → 2,8 | 5,7 → 5,2 | 0 → 0 |
| wild1 | 6,5 → 4,8 | 8,6 → 6,3 | 1 → 1 |
| wild2 | 4,9 → 3,9 | 8,3 → 5,7 | 6 → 0 |
| wild3 (Blick zurück) | 5,9 → 4,6 | 9,9 → 7,9 | 5 → 1 |
| wild4 (Blick zurück) | 5,9 → 5,4 | 9,0 → 7,8 | 7 → 6 |

Flach (11 Strecken): Abstand Mittel überall gleich oder kleiner (z. B. Gang 5,3 → 4,2, offenes Feld 4,3 → 3,2), 0
Teleports, 0 Ticks im Stein. Erste neue Fassung war schlechter (Spur-Ziel „Punkt nächst bei Vera“ = ihr eigener Platz,
sie blieb im Gang 10 Blöcke zurück) – verworfen. Nicht nachgestellt: der Fall des Nutzers selbst. Wo die Wegsuche im
Spiel scheitert, ist ungemessen; das Plugin protokolliert dafür `begleiter | weg_vom_spieler`. Wild5 hatte keine
Strecke (Wegsuche zwischen Eckpunkten erfolglos), wild0 nur 18 Punkte (zweiter Abschnitt ohne Weg).

## Folgen 3: Spieler als Ziel (2026-11-07)

Dritte Fassung „spieler“ (`CompanionFollow.goalPlayer`): Ziel ist der Spieler selbst, anhalten tut die Hysterese bei
3 Blöcken. Anlass: Im Spiel stand Vera mehrfach genau auf ihrem Ziel und hing 5–6 Blöcke zurück (Log
„weg_vom_spieler … ziel_abstand=0.0“). Abstand im Mittel jetzt / neu / spieler:

| Natur-Welt | jetzt | neu | spieler |
|---|---|---|---|
| wild0 | 4,2 | 2,8 | 2,4 |
| wild1 | 6,5 | 4,8 | 3,8 |
| wild2 | 4,9 | 3,9 | 3,5 |
| wild3 (Blick zurück) | 5,9 | 4,6 | 3,8 |
| wild4 (Blick zurück) | 5,9 | 5,4 | 3,7 |

Flach (11 Strecken) ebenso überall am kleinsten, z. B. Gang 5,3 / 4,2 / 3,3, Runde 4,4 / 3,9 / 3,2; Ticks „weg vom
Spieler“ zusammen 20 / 15 / 8; 0 Teleports, 0 Ticks im Stein.

Eine Suche kostete 0,03–1,7 ms (erste 15 ms, JIT kalt). `PoseProbe.java`: Alle fünf Mannequin-Posen (Stehen, Ducken,
Schwimmen, Gleiten, Schlafen) bleiben unter Paper mit `setPose(Pose, true)` 40 Ticks stehen (`posen-2026-11-06.txt`).
Nicht gemessen: Spigot, enge Kriechgänge (1 hoch – dorthin kommt sie nicht, Teleport), wie der Client die Posen zeigt.
