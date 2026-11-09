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
- ⏳ Recherche, Gedächtnis für Folgeaufträge und Plot-Modus gibt es nur im Plugin
