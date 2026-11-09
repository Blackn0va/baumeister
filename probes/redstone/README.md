# Messprobe Redstone

Baut acht kleine Schaltungen auf einem Paper-Wegwerfserver, lässt sie 40 Ticks laufen und schreibt jeden Block als
`BlockData.getAsString()` heraus. Klärt, wie die Zustände wirklich heißen und welche Seite eines Bauteils der Eingang
ist. Die Ausgabe ist zugleich die Testdatei `src/test/resources/redstone/probe-2026-11-06.txt` für
`RedstoneReaderTest`.

| Frage | Ergebnis (2026-11-06, Paper 1.21.11-132) | Verwendet in |
|---|---|---|
| Welche Seite eines Verstärkers ist der Eingang? | Die `facing`-Seite: `facing=west` hinter einem Hebel im Westen an, Lampe leuchtet; `facing=east` aus, Lampe aus. Dazu `DiodeBlock.getInputSignal` liest `pos.relative(FACING)` (javap) | `RedstoneReader.diodeFinding` |
| Wie weit trägt Staub? | 15 am Hebel, je Block eins weniger, der 16. hat 0; Lampe nach 17 Blöcken aus | Fund „Signal aufgebraucht“ |
| Fackel als NICHT | Wandfackel `facing=east` hängt am Block im Westen; der hat Strom → `lit=false` | `RedstoneReader.torchBase` |
| Seitlich gesperrter Verstärker | `locked=true, powered=false` trotz Signal am Eingang | Fund „gesperrt“ |
| Beobachter | Gibt hinten aus (`ObserverBlock.updateNeighborsInFront`, javap), `powered=false` ohne Änderung vor ihm | Beschreibung „schaut nach …“ |
| Komparator an Truhe (10 Stapel) | `powered=true`, Staub dahinter Stärke 6 | Beschreibung |
| Lampe 2 Blöcke neben dem Hebel | aus | Fund „Dazwischen fehlt eine Verbindung“ |

Nicht gemessen: Spigot statt Paper, Schaltungen über mehrere Höhen, Takte und Flipflops (Zustand wechselt im Tick).

## Wiederholen

Wie `probes/mannequin/README.md` (gleicher Wegwerfserver), mit `RedstoneProbe.java` und `plugin.yml` aus diesem
Ordner. Ergebnis in `plugins/RedstoneProbe/bloecke.txt`.

## Ausprobieren und Umbauen (2026-11-06)

`LabProbe.java` baut vier Gatter je zweimal und lässt `RedstoneLab` die Hebel durchschalten – ohne und mit
Anstupsen der Teile neben dem Block, an dem ein Hebel hängt. `FixProbe.java` setzt die Umbauten aus `RedstoneReader`.
Rohdaten: `labor-2026-11-06.txt`.

| Frage | Ergebnis | Verwendet in |
|---|---|---|
| Weckt ein per Plugin umgelegter Hebel die Nachbarn seines Blocks? | Nein: „durch Block“, NICHT und UND „immer aus“; nur ODER (Staub direkt am Hebel) stimmte. Mit Anstupsen 4/4 richtig (A und B · A oder B · nicht A · A) | `RedstoneLab.refresh` |
| Wann ablesen? | 4 stille Ticks ohne Mindestwartezeit waren zu früh (Lampe geht 4 Ticks nach dem Signal aus): NICHT ergab „A, merkt“, UND „B“. Mit mindestens 10 Ticks, dann 6 still: richtig | `RedstoneLab.MIN_WAIT` |
| Startzustand | Schritt 0 schaltet keinen Hebel – ohne Auffrischen vorher stand die Lampe des NICHT falsch („immer aus, merkt“) | `RedstoneLab.refresh` vor Schritt 0 |
| Helfen die Umbauten? | Verstärker umgedreht und Verstärker ans Ende eines aufgebrauchten Signals: beide Lampen `lit=false` → `lit=true` | `RedstoneRepair` |

## 7-Segment-Anzeige (2026-11-07)

`DisplayProbe.java` setzt `SevenSegment.compile()` direkt (Blick nach Norden), stupst jedes Teil an wie der Bau, legt
für jeden Wert 0–F die Eingänge an (Redstone-Block wie `MachineProbe`) und liest alle 27 Lampen sowie jede Decoder-
Leitung. Schnell zum Fehlersuchen; die Schlussprüfung ist `MachineProbe … anzeige` mit echtem Bau durch den NPC.

| Frage | Ergebnis | Verwendet in |
|---|---|---|
| Rechnet der Decoder mit 35 Termen (doppelt so viele wie bisher geprüft)? | Ja, alle 7 Leitungen bei allen 16 Werten richtig | `LogicCircuit.grid` |
| Warum zeigte die Ziffer den vorigen Wert? | Verstärkerregel verglich mit dem ganzen Rest des Wegs – auf fast jedem Block ein Verstärker, je 2 Ticks. Nach 60 Ticks alle Segmente einen Schritt zurück, nach 200 Ticks nur noch b (längste Leitung) falsch | `SevenSegment.route` |
| Mit neuer Regel | 16/16 nach 60 Ticks, 39 Verstärker auf den Leitungen | `SevenSegmentTest.linesHaveFewRepeaters` |
| Echter Bau (Spigot-Testserver, NPC, `MachineProbe anzeige` in n, o, s, w) | BESTANDEN in allen vier Richtungen: je 432/432 Prüfungen (16 Werte × 27 Lampen) | `Machines.display` |
