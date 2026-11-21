# Plan-Vorschau gegen den Bau (T-017, AK-19)

Frage: Zeigt die Plan-Vorschau der Host-App (`src/gui/plan_view`) dieselben Zellen, die das Plugin aus dem Plan baut?

## Ablauf

1. Java-Seite (Repo-Wurzel, nach `mvn test-compile`): Zellen des PlanCompilers je Plan.
   ```
   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.PreviewCellsProbe <pläne> <java-aus>
   ```
   Schreibt `<name>.compiler.txt` (PlanCompiler direkt, Katalog `tiers/block-ids.txt`, eingebaute Maschinen) und
   `<name>.review.txt` (Bau nach PlanReview über `PlanJudge`).
2. Host-Seite (nach `build.ps1 -Stage configure,build,test`, Programm im Bauordner unter `tests/gui/`):
   ```
   swbh_probe_plan_preview.exe <pläne> <host-aus>
   ```
3. Vergleich: `python -I probes/plan_preview/vergleich.py <java-aus> <host-aus> [compiler|review] [-v]`

Eingabe sind die Aufzeichnungen des Hosts (`auftrag`, `antwort`, `versuch`). Echte Aufzeichnungen sind Spielertexte
und liegen **nie** im Repo. Für den Unit-Test gibt es nachgebaute Pläne unter `tests/plan/data/vorschau-*.json`
(Erzeuger `tests/plan/data/vorschau_erzeugen.py`).

## Ergebnis (2026-11-21, 50 echte Pläne von dev01, gegen „compiler“)

| | vorher | nachher |
|---|---|---|
| Vorschau zeigt etwas, wo das Plugin baut | 41 von 46 | 46 von 46 |
| davon zellgenau | 16 | 35 |
| Zellen „anders“ / „fehlt“ / „zuviel“ | 4 852 / 4 440 / 2 | 1 577 / 4 568 / 1 |

Behoben (je Gegenprobe in `test_plan_view.cpp`): Hausfenster auf den Feldern von `houseWindow`, Leiter und
Einrichtung im Haus, Leiterschacht und Lampen im Hochhaus, Dachfirst und Deckel als Platte, `fill` mit `hollow`,
Bett und hohe Pflanze zweiteilig, halbe Tür entfällt wie im Plugin, feste Umschreibungen aus `BlockRules`
(Aliase, `frosted_ice`), abgeschnittene Antwort zeigt die vollständigen Schritte (`JsonRepair`).

Bewusst nicht nachgebaut (Rest der 11 Pläne):

| Art | Pläne | Zellen | Warum nicht |
|---|---|---|---|
| Ersatzblock des Server-Katalogs (unbekannter Name → Stein, `ItemNames`) | 6 | ~1 560 | braucht Blockliste und Namenssuche samt Übersetzung des Plugins |
| `module`/`logic` als Markierung | 4 | ~1 840 | Zellen kennt nur die Bibliothek des Plugins |
| Bauform `castle` (auch `wall`, `bridge`, `garden`, `tree`) | 1 | 2 732 | `plan/Structures.java`, gut 1 100 Zeilen |
| Plan, den der Parser des Plugins ablehnt, zeigt die Vorschau trotzdem | 4 | – | Prüfung bleibt im Plugin (PlanReview repariert) |

Gegen „review“ (Bau nach PlanReview): vorher 9 von 35, nachher 17 von 38 zellgenau. Der Rest sind Nachbesserungen
von PlanReview (Hohlmachen, Stützen, weggelassene Blöcke, Reparaturrunden) – die kennt die Vorschau aus der Antwort
allein nicht. Diese Seite nutzt den Katalog von `PlanJudge` (jeder Material-Name), also mit Vorsicht lesen.
