# Mitmachen

Schön, dass du mitbauen willst. Jeder Beitrag läuft über einen Pull Request und wird vor dem Mergen vom
Maintainer geprüft und freigegeben – direkt auf `main` schreibt niemand außer ihm.

## Ablauf

1. **Issue zuerst** bei allem, was größer als ein Tippfehler ist – dann klären wir vorher, ob es passt.
2. **Fork**, dann ein Branch: `feature/…`, `fix/…` oder `docs/…`.
3. **Bauen und testen** (unten), dann Pull Request gegen `main`. Die Vorlage fragt ab, was gebraucht wird.
4. Rückfragen im Pull Request beantworten. Gemergt wird erst nach Freigabe.

Mit dem Pull Request stellst du deinen Beitrag unter die Lizenz dieses Projekts (`LICENSE`).

## Bauen und testen

| Teil | Voraussetzung | Befehl |
|---|---|---|
| Plugin (`src/`) | Java 21, Maven, die Server-APIs in `lib/` ([woher](lib/README.md)) | `mvn package` |
| Relay (`relay/`) | Java 21, Maven | `mvn -f relay/pom.xml package` |
| Host (`host/`) | Windows, Visual Studio Build Tools 2022, Qt 6 – `host/einrichten.ps1` richtet alles ein | `pwsh -File host/build.ps1` |
| Client-Mod (`client/`) | Java 25 | `./gradlew build` in `client/` |

Alle Tests müssen grün sein. Was du nicht bauen oder testen konntest (z. B. den Host ohne Windows), schreib bitte
in den Pull Request – das ist kein Ablehnungsgrund, nur wichtig zu wissen.

## Worauf es ankommt

- **Sprache:** Texte im Spiel, Kommentare, Doku und Commit-Nachrichten auf Deutsch, mit echten Umlauten (ä ö ü ß,
  nie ae/oe/ue). Namen von Klassen, Methoden und Variablen auf Englisch.
- **Kommentare erklären das Warum:** was vorher war, warum es falsch war, was passiert, wenn man es zurückdreht –
  nicht, was der Code tut.
- **Jeder Fix mit Test.** Am besten mit Gegenprobe: Test ohne den Fix rot, mit dem Fix grün.
- **Sicherheit bleibt fail-closed.** Rechteprüfungen (wer was bauen darf), Pinning und Signaturprüfung werden nicht
  gelockert. Was ein Spieler nicht selbst setzen dürfte, setzt auch der Baumeister nicht.
- **Keine Geheimnisse im Code** – keine Tokens, Passwörter, Schlüssel, keine echten Server-Adressen.
- **Kein fremder Inhalt ohne Lizenz:** keine Minecraft-Texturen, keine Schematics oder Texte aus fremden Quellen,
  deren Lizenz das nicht erlaubt. Neue Abhängigkeiten mit Lizenz in `THIRD-PARTY-NOTICES.md`.
- **Klein halten.** Ein Pull Request, ein Thema. Kein Umformatieren von Dateien, die sonst nicht angefasst werden.
- Stil der Datei übernehmen, in der du arbeitest: 4 Leerzeichen, UTF-8, LF (PowerShell-Dateien: UTF-8 mit BOM,
  CRLF).

## Commits

[Conventional Commits](https://www.conventionalcommits.org/) auf Deutsch, Betreff als Satz ohne Punkt, der Text
sagt, warum:

```
fix: Fackeln an Wänden zeigen nach dem Drehen wieder von der Wand weg

Vorher drehte toLocal nur facing, nicht die Wandseite – nach Süden gebaut hingen sie in der Luft.
```

## Fragen

Einfach ein Issue aufmachen. Sicherheitslücken bitte **nicht** öffentlich, sondern wie in [SECURITY.md](SECURITY.md)
beschrieben.
