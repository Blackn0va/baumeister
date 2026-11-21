# Baumeister-Client

**Der Baumeister baut mit deiner eigenen Spielfigur – im echten Minecraft-Client, ohne Server-Plugin.**

Fabric-Mod für Minecraft Java 26.2 (bis 0.1.0: 1.21.11). Sie plant genau wie das Plugin (Schnellaufträge ohne LLM, sonst das LLM im
Baumeister-Host mit demselben Prompt, derselben Grammatik und demselben Prüfer) und setzt die Blöcke dann selbst:

- **wie ein Spieler** (Vorgabe): Die Figur fliegt in Reichweite, schaut hin, nimmt den Block in die Hand und setzt
  ihn per Rechtsklick (mit Schleichen, damit Truhen und Trichter nicht aufgehen). Kreativmodus nötig, keine
  OP-Rechte; der Server prüft alles wie bei dir (Plot, Region, Reichweite).
- **per Befehl**: `/setblock` und `/fill`, sehr schnell, braucht OP bzw. Cheats.

**Du hast immer Vorrang:** Jede eigene Bewegung, jeder Klick, jede Mausbewegung und jeder offene Bildschirm hält den
Bau im selben Tick an. `K` (in der Steuerung änderbar) oder `/kbau weiter` macht an derselben Stelle weiter.

Während des Baus zeigt die Aktionsleiste über der Hotbar den Stand: Blöcke, Prozent und die geschätzte Restzeit
(ab 10 Blöcken und 3 s), bei einer Pause den Grund und „[K] weiter“.

## Befehle

| Befehl | Wirkung |
|---|---|
| `/kbau <Auftrag>` | z. B. `/kbau bau einen Bunker`, `/kbau bau ein Haus aus Fichtenholz` |
| `/kbau stop` · `pause` · `weiter` · `status` | Bau steuern |
| `/kbau rückgängig` | den letzten Bau zurücksetzen |
| `/kbau modus spieler` · `modus befehl` | Bauweise |
| `/kbau tempo <n>` | Blöcke bzw. Befehle je Sekunde |
| `/kbau host <url>` | Adresse des Baumeister-Hosts (Vorgabe `http://127.0.0.1:8080/v1/chat/completions`) |

Einstellungen: `config/baumeister-client.json`. Ergebnisse meldet die Mod an die App (Reiter „Verlauf“, „(Client)“).
Unbrauchbare Werte setzt die Mod beim Laden auf die Vorgabe zurück und schreibt das ins Log – auch die KI-Werte
`temperature` (0 bis 2), `maxTokens` (ab 1), `repairAttempts` und `maxOverbuild` (ab 0). `llmTimeoutSeconds`
(Vorgabe 600) ist die Wartezeit auf eine Antwort des Hosts; danach endet die Planung mit einer Meldung im Chat.

## Nachträge (Gedächtnis)

Wie im Plugin merkt sich die Mod deinen letzten Bau: Ein Auftrag binnen einer Stunde und höchstens 48 Blöcke vom
Standort des ersten Auftrags („mach das Dach rot“, „bau noch einen Turm dran“) schickt dem LLM den vorigen Plan mit.
Das LLM liefert dann den vollständigen neuen Plan; was der alte Bau gesetzt hat und der neue nicht mehr enthält, räumt
die Mod zuerst weg – nur, wo noch derselbe Block steht. Kürzt das Modell den alten Bau, ohne dass du Wegnehmen
verlangt hast, bleibt er stehen (Anbau). `/kbau rückgängig` nach dem Nachtrag stellt den Bau davor wieder her, und
das Gedächtnis zeigt wieder auf ihn; nach dem Rückgängig des einzigen Baus ist es leer. Ein mit `/kbau stop`
abgebrochener Bau, von dem noch kein Block stand, fällt aus dem Gedächtnis – der nächste Nachtrag setzt dann auf dem
Bau davor auf.

Neu geplant wird in einer anderen Welt, Dimension oder auf einem anderen Server, nach Ablauf der Stunde und bei einem
neuen eigenständigen Bauwerk („bau mir ein Haus“). Das Gedächtnis liegt nur im Speicher, nie auf der Platte – nach
einem Neustart des Spiels beginnt der nächste Auftrag frisch.

## Bauen und testen

Gradle braucht seit 26.x Java 25 (Loom 1.19.4):

```bash
JAVA_HOME='%USERPROFILE%\.local\opt\jdk-25.0.5+8' ./gradlew build
```

```bash
./gradlew runClientGameTest
```

`build` legt die Mod unter `build/libs/baumeister-client-<ver>.jar` ab (in den `mods`-Ordner einer Fabric-Installation
mit Fabric-Loader 0.20.1 und Fabric API 0.161.0+26.2). `runClientGameTest` startet einen echten Client, legt eine Kreativwelt an und baut
in beiden Bauweisen; danach prüft er Übernahme per Taste und Rückgängig (Bildschirmfotos unter
`build/run/clientGameTest/screenshots`). Während des Laufs nichts im Spielfenster anklicken – das pausiert den Bau
(gewollt) und lässt den Test scheitern.

Die Plan-Logik kommt unverändert aus dem Plugin (`../src/main/java`, Pakete `plan` und `logic`).

## Stand

- ✅ Spieltest im echten Client (2026-10-31): Hochhaus per Befehl 6 s, wie ein Spieler 105 s, Maschinenhalle mit
  Übernahme 74 s (811/811), Rückgängig 70 s – jede Stelle geprüft
- ⏳ Mit LLM im echten Spiel (Host auf dem GPU-PC) noch nicht gelaufen; Flüssigkeiten gehen nur per Befehl
- ✅ Gedächtnis für Nachträge (2026-11-21) in Unit-Tests: gefälschter Host (`ClientPlannerTest`), Frist/Umkreis/Welt
  (`ClientMemoryTest`), Nachtrag und Rückgängig mit echten Blockzuständen (`ClientFollowUpTest`), Verdrahtung im
  echten `ClientBuilder` – Start, Prüfdurchgang, Stopp, Rückgängig (`ClientBuilderMemoryTest`, 2026-11-21)
- ⏳ Nachtrag im echten Spiel mit LLM noch nicht gelaufen
- ⏳ Recherche und Plot-Modus gibt es nur im Plugin
