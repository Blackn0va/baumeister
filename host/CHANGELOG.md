# Änderungen – StreamWizard Baumeister-Host

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionen nach SemVer.

## [Unveröffentlicht]

### Geändert (2026-11-09)
- Vorgabe der Fernadresse ist ein Platzhalter (`wss://dein-server.example.net:25580/kanal/v1`) statt der Adresse
  eines bestimmten Servers. Eingerichtete Installationen behalten ihre gespeicherte Adresse.

### Neu (2026-11-06, Cartoon-Stimme)
- Einstellung „Tonlage“ (normal, fröhlich, Cartoon, extra Cartoon), Vorgabe Cartoon: Die Stimme liegt um den Faktor
  höher, das Tempo bleibt etwa gleich. Gemessen mit probes/piper_cartoon_probe.py (Thorsten medium): Grundton
  108 Hz → 143 Hz bei Cartoon.

### Behoben (2026-11-05)
- „Pageant läuft nicht – Pageant starten …“ erschien, obwohl der Tunnel sich gleich danach verband. Die App erkennt
  Pageant jetzt auch über die Pipe pageant.*, die Zeile ist nur noch ein Hinweis.

### Geändert (2026-11-04, Aussprache)
- Nether, Hardcore und Enderauge klingen englisch statt deutsch („Näther“, „Haadkohr“, „Ender-Auge“) – gemessen
  mit probes/piper_aussprache_probe.py (Thorsten medium). „Creeper“ wurde schon „Krieper“ (kɾˈiːpɜ) gesprochen.

### Geändert (2026-11-04)
- Piper spricht fröhlicher: 8 % schneller, mehr Melodie (length_scale 0,92, noise_scale 0,8, noise_w 0,9). Nicht
  gemessen, nach Gehör einzustellen.

### Behoben (2026-11-04, Voice-Chat)
- Sätze aus Simple Voice Chat kommen an: Die App wies den Ton (`POST /baumeister/v1/begleiter/hoeren`,
  `application/octet-stream`) mit 415 `content_type_nicht_json` ab, weil jeder POST JSON sein musste – Vera hörte im
  Spiel nichts. Octet-stream gilt jetzt nur für diesen Pfad; alle anderen bleiben bei JSON, Browser-Anfragen weiter 403.
- Der Installer duzt (64 Texte des deutschen Inno-Setup-Sprachpakets überschrieben).

### Neu (2026-11-04, Mitspieler)
- Sprechtaste ruht, solange du im Spiel über Simple Voice Chat sprichst (das Plugin meldet es beim Abholen): Vorher
  nahm die App parallel auf – dieselbe Taste V öffnet auch das Menü von Simple Voice Chat – und Sätze kamen doppelt an.
- Einstellung „Andere Spieler verstehen (Simple Voice Chat)“, Vorgabe an: Spricht ein Mitspieler im Spiel Vera an,
  erkennt die App seinen Satz. Vorher nur der eingetragene Spieler – Mitspieler bekamen keine Antwort.

### Behoben (2026-11-04)
- Einstellungen in Reitern (Sprachmodell, Server, Begleiter, Allgemein): Mit den Begleiter-Feldern war das Fenster
  ~1 100 Pixel hoch und ragte über den Bildschirm, „Speichern“ war nicht erreichbar. Jetzt ~540 Pixel.

### Geändert (2026-11-04, ein Modell für alles)
- Alle Modelle wieder wählbar (Wunsch); Vorgabe bleibt das 14B Q3_K_M,
  das frühere 14B Q4_K_M steht als eigene Wahl daneben.
- Das 14B lädt jetzt als Q3_K_M (7,3 GB statt 9,0 GB): Q4_K_M passte neben Minecraft nicht ganz auf die 12-GB-Karte,
  7 von 48 Schichten auf der CPU kosteten gemessen 61–106 ms je Token. Kontext und Turbo rechnen jetzt mit demselben
  Budget samt Puffer wie die Schichtverteilung. CPU-Schichten mit allen physischen Kernen statt 4 Threads
  (gemessen 8 % schneller).
- Kein Auslagern mehr neben Minecraft: Passt das Modell samt 1,5 GiB Puffer nicht in den freien Grafikspeicher,
  liegen nur so viele Schichten auf der GPU, wie hineinpassen, der Rest rechnet auf der CPU (Modellzeile zeigt
  „41/48 Schichten auf der GPU“). Vorher alles auf die GPU: 168–1389 ms je Token, sobald Minecraft mehr belegte.
- Nur noch Qwen2.5 14B im Katalog (vorher 12 wählbare Modelle); eine früher gewählte Auswahl fällt beim Start auf das
  14B zurück. Eigene GGUF-Dateien gehen weiter.
- Turbo-Entwurf geht von selbst aus, wenn nicht einmal Kontext 8192 samt Entwurf in den freien Grafikspeicher passt –
  gemessen auf dem GPU-PC neben Minecraft: ausgelagert 145 ms je Token, System-Prompt 1369 Token in 62 s.

### Neu (2026-11-04, Begleiter im Spiel und natürliche Stimme)
- Bessere deutsche Aussprache: Uhrzeiten („6 Uhr“ statt „sechs Uhr null null Uhr“), Rechenzeichen, Dezimalkomma,
  Minecraft-Englisch („Krieper“, „Rädstohn“, „Tschank“), Namen mit Ziffern, Abkürzungen (die vorher den Satz
  abbrachen) und Emojis – gemessen an den Phonemen von Piper (`probes/piper_aussprache_probe.py`).
- Grammatik `begleiter` kennt die Aktion `command`.
- Natürliche Stimme: Piper wie in kraken (Vorgabe Thorsten medium), lädt nach Wahl in den Einstellungen; die
  Windows-Stimme springt ein, solange Piper lädt oder ausfällt. Erster Satz ~4 s (Laden), danach ~0,14 s je Satz.
- Simple Voice Chat: `/begleiter/hoeren` (Ton aus dem Spiel → Text) und `/begleiter/stimme` (Text → 48-kHz-Ton);
  nur dieser Pfad darf 1 MiB groß sein, alle anderen bleiben bei 256 KiB.
- Mikrofon: Pegelanzeige, Mikrofontest mit Wiedergabe, automatische Verstärkung, Verstärkung von Hand, Stille an den
  Rändern abschneiden, weicher Begrenzer, Übersteuerungswarnung, Signaltöne, Bedienart Halten/Umschalten.

### Neu (2026-11-04, Begleiter)
- Grammatik `begleiter` (`src/plan/begleiter.gbnf`) für die Antworten des Begleiters: ein Satz, höchstens drei
  Aktionen; `response_format.json_schema.name == "begleiter"` wählt sie.
- Vorlesen über die Windows-Sprachausgabe (SAPI, Modul `voice`): `POST /baumeister/v1/begleiter/sagen`; gesprochen
  wird nur, was dem eingetragenen Minecraft-Namen gilt. Einstellungen: „Dein Minecraft-Name“, „Antworten vorlesen“,
  „Stimme“.
- `GET /baumeister/v1/begleiter/gehoert`: Long-Poll (20 s) für erkannte Sprache; Warteschlange auf 16 Sätze begrenzt.
- Höchstens 8 statt 4 gleichzeitige Verbindungen – der Long-Poll hält dauerhaft eine.
- Sprechen mit dem Begleiter: Taste halten (Vorgabe hintere Maus-Seitentaste), sprechen, loslassen. whisper.cpp v1.9.4
  (wie kraken, ggml 0.23.0 = llama.cpp b10900) erkennt auf dem PC; das Modell (574 MB) lädt die App erst, wenn
  „Mit dem Begleiter sprechen“ eingeschaltet wird. Einstellungen: Taste, Mikrofon.

### Geändert (2026-11-01, einfachere Bedienung)
- Hauptfenster: zwei Schalter statt sechs Knöpfen – **„Verbinden/Trennen“** (Server und, wenn nötig, Tunnel) und
  **„KI einschalten/ausschalten“** (lädt das Modell, fehlt die Datei, erst herunter). Darüber ein Satz, was gerade
  geht, z. B. „Verbunden ohne KI: Stadt- und Weltbau laufen, Bauaufträge an die KI lehnt das Plugin ab.“
- Einstellungen: eine Frage **„Der Server läuft auf einem anderen Rechner / auf diesem PC“** statt der Häkchen
  „Tunnel mitstarten“ und „lokaler Server“; die SSH-Felder erscheinen nur beim anderen Rechner. **„Beim
  Programmstart: verbinden und KI / nur verbinden / nichts“** statt zweier abhängiger Häkchen.
- Infobereich: Verbinden, Trennen, KI ein, KI aus.
- `--screenshot <png>` legt zusätzlich `<png>-einstellungen.png` mit dem Einstellungsdialog ab.

### Geändert (2026-11-01, für jeden Server)
- Kein fest eingetragener Server mehr: Neue Installationen starten ohne Ziel und bedienen nur einen Minecraft-Server auf
  diesem PC, bis in den Einstellungen **Server-Adresse** und **Benutzer** stehen (vorher ein Feld „benutzer@host“ mit
  dev01 als Vorgabe). Die Tunnel-Karte zeigt dann „Server eintragen …“. Alte `config.local.json` (ohne `version`)
  mit leerem Ziel behalten dev01, damit ein eingerichteter PC nach dem Update weiter verbindet.
- Alle Texte sprechen vom „Server“ statt von dev01 (App, Tunnel-Meldungen, Infobereich, Setup, Einrichtungsskript).

### Neu (2026-11-01, ohne KI verbinden)
- **„Nur verbinden“** (Knopf und Infobereich): Server und Tunnel ohne Sprachmodell – Stadt- und Weltbau laufen im
  Plugin ohne KI, die App zeigt den Fortschritt, die Grafikkarte bleibt frei. Einstellung „… und dabei das
  Sprachmodell laden“ (`modell_beim_start`) wählt das auch für den Programmstart.
- „Modell herunterladen“ erscheint nur, solange die Datei fehlt.

### Neu (2026-11-01, Absturz- und Ende-Protokoll)
- Absturz-Protokoll (`win/crash_guard`): Unbehandelte Ausnahmen und `std::terminate` schreiben eine Zeile „Absturz: …“
  und ein Abbild `baumeister-absturz-JJJJMMTT-hhmmss.dmp` in den Log-Ordner (die 5 neuesten bleiben). Anlass: Nach dem
  Tunnelabriss vom 2026-11-01 verriet das Protokoll nicht, ob die App abgestürzt oder beendet worden war.
- Die Schlusszeile nennt den Grund: „Beenden“ im Infobereich, Fenster geschlossen, Windows-Abmeldung (sofort beim
  Ankündigen protokolliert) oder Bildschirmfoto. „Alles stoppen“ steht ebenfalls im Protokoll.
- Lauf-Markierung `sitzung-offen.txt`: Endete der letzte Lauf ohne Schlusszeile (Task-Manager, Stromausfall, Absturz
  am Filter vorbei), meldet der nächste Start das als Warnung mit Startzeit und Prozess-ID.

### Behoben (2026-11-01, Fenster ohne Infobereich)
- Ohne Infobereich-Symbol blieb die App nach dem Schließen des Fensters unsichtbar stehen und hielt Port und Tunnel;
  jetzt beendet sie sich.

### Behoben (2026-11-01)
- Ergebnis-Zuordnung: Auftrag aus der Chat-Anfrage und aus `/baumeister/v1/ergebnis` bekommen denselben Schlüssel
  (`order_key`) – vorher 160 Codepunkte gegen 200 Byte, lange Aufträge trafen nie.
- Verlauf: ältere LLM-Runden desselben Auftrags heißen „nachgebessert“ (`HistoryEntry::superseded`).

### Neu (2026-11-01)
- Live-Konsole: „Speichern …“ sichert den Inhalt als UTF-8-Textdatei (`baumeister-konsole-JJJJMMTT-hhmmss.txt`,
  Vorschlag im Dokumente-Ordner) mit Kopfzeile aus Version und Zeitpunkt, atomar geschrieben (`gui/console_export`).

### Neu (2026-10-31, Nachbesserungen sichtbar)
- `/baumeister/v1/ergebnis` nimmt `korrekturen` (bis 300 Byte) und `runden` (0–20) an; beides optional, ältere
  Plugins melden wie bisher. Die Kurzzeile heißt dann „gebaut, selbst nachgebessert: … Blöcke“.
- Reiter „Verlauf“: Kopfzeile mit der Zählung gebaut / selbst nachgebessert / verworfen / abgesagt, Nachbesserungen
  und LLM-Runden im Tooltip des Ergebnisses, die Konsole nennt die Nachbesserungen.

### Neu (2026-10-31, Modelle und Client-Mod)
- Mehr Modelle zur Auswahl: Qwen2.5 Coder 14B, 7B, 3B und 1.5B, Phi-3.5 mini (3,8B), Llama 3.2 3B – Größe, SHA-256 und
  Lizenz aus der Hub-API. Die JSON-Syntax erzwingt ohnehin die Grammatik; ob Code-Modelle inhaltlich besser planen,
  ist nicht gemessen (Vergleich mit LlmProbe auf dem GPU-PC).
- Ergebnisse der Client-Mod (`"quelle":"client"`) stehen im Verlauf als „gebaut (Client): …“.

### Neu (2026-10-31, Ergebnis im Spiel und Verlauf)
- Das Plugin meldet nach jedem Auftrag, was im Spiel daraus wurde (`POST /baumeister/v1/ergebnis`: gebaut mit Blöcken
  und Plot, verworfen, abgesagt, abgebrochen). Die Karte „Letzter Auftrag“ zeigt „Im Spiel: …“ (grün bzw. gelb), die
  Konsole ebenso.
- Reiter „Verlauf“ neben Plan und Stadt: die letzten 50 Aufträge mit Zeit, Auftrag, LLM-Dauer und Tempo (Modell im
  Tooltip) und Ergebnis im Spiel; Bauten ohne LLM (Fort Knox, Bunker, Hochhaus) stehen dort auch.
- Modellhinweis: Unter 12 Milliarden Parametern steht in der Modell-Karte und der Konsole „Für freie Baupläne zu klein
  – Qwen2.5 14B empfohlen“ (gemessen: Qwen2.5 3B und Llama 3.1 8B wiederholten Schritte bis zu 40-mal).

### Behoben (2026-10-30, Modellwechsel)
- Die Tempo-Warnung vergisst ihren Maßstab, wenn ein anderes Modell geladen wird. Vorher galt nach dem Wechsel von
  Qwen2.5 3B mit Turbo (200,7 Token/s) auf Llama 3.1 8B der alte Bestwert, und jeder Auftrag hieß „Langsam“.

### Neu (2026-10-30, Recherche und Tempo-Warnung)
- Einstellung „Internet-Recherche erlauben“ (Vorgabe an, `research` in `config.local.json`): Das Plugin fragt vor
  jeder Recherche `GET /baumeister/v1/recherche`; aus = keine einzige Abfrage bei Wikipedia/Wikidata, das Modell plant
  aus dem Gedächtnis. Wirkt sofort, ohne Neustart. Antwortet die App nicht (ältere App, 404), recherchiert das Plugin
  wie bisher.
- Was das Plugin nachgeschlagen hat, meldet es per `POST /baumeister/v1/recherche`; die App zeigt es in der Konsole
  („Recherche (Internet): Kölner Dom – 157 m, 1:1“) und auf der Karte „Letzter Auftrag“ („Nachgeschlagen: …“).
  Meldungen werden streng geprüft (Titel Pflicht, Zahlen in Grenzen, UTF-8-sicher gekürzt).
- Tempo-Warnung: Liest ein Auftrag den Prompt mit weniger als 25 % des Vorwärm-Tempos oder schreibt er mit weniger als
  35 % des bisher besten Tempos, steht auf der Karte und in der Konsole „Langsam: … – belegt ein Spiel oder ein anderes
  Programm die Grafikkarte?“. Anlass: dev01 2026-10-30, ein Auftrag mit 39 statt ~3 200 Token/s beim Lesen.

### Neu (2026-10-29, Stadtansicht und lokaler Server)
- Reiter „Stadt“ neben der Plan-Vorschau: Stadt- und Weltbau als Karte, wie das Plugin sie meldet
  (`POST /baumeister/v1/stadt`, streng geprüft, höchstens 16 Mio. Zellen je Auftrag) – fertige Etappen, laufende Etappe
  mit Rahmen, Position des Baumeisters, Etappe/Blöcke/Tempo/Restzeit, Legende; Mausrad zoomt, Ziehen verschiebt,
  Doppelklick passt ein. Beim ersten Stadtbau wechselt die App einmal von selbst auf den Reiter.
- Weltbau im Reiter „Stadt“: Kachelraster der Erdwelt (fertig grün, gescheitert rot, wartend gestrichelt, laufend
  Akzent), Spieler als Punkte, Zeile „Weltbau: n Kacheln fertig · … · Automatik an/aus“; frühere Kacheln bleiben als
  Hintergrund stehen (bei Platzmangel fallen die ältesten zuerst).
- „Lokaler Minecraft-Server auf diesem PC“ (Vorgabe an): Die App lauscht zusätzlich auf `127.0.0.1:18080`, ein auf
  diesen PC kopierter Server erreicht sie mit unveränderter Plugin-Config ohne Tunnel. Belegter Port → Warnung, der
  Tunnel zu dev01 bleibt unberührt.

### Geändert (2026-10-29, Kontext)
- Kontext automatisch nach freiem Grafikspeicher statt fest 8192 (Einstellung „Automatisch“, Wert 0 als `kontext`
  in `config.local.json`): der größte bis 32768, der samt Turbo-Entwurf in 95 % des freien Speichers passt. Reicht es
  mit Turbo nur für 8192, ohne aber für mindestens 12288, bleibt Turbo aus. Auf einer RTX 4070 Ti mit 12 GB frei:
  16384 ohne Turbo (geschätzt 11,2 GB). Anlass: Mit 8192 brach die Reparaturrunde ab („Prompt zu lang (8246 Token)“).
- Katalog-Obergrenze je Modell 32768 statt 8192; eine alte `config.local.json` mit `n_ctx` 8192 (die frühere Vorgabe)
  gilt als „Automatisch“, andere Werte bleiben. Wer Turbo erzwingen will, stellt den Kontext auf 8192.
- Die Warnung vor zu wenig Grafikspeicher zählt jetzt das Turbo-Entwurfsmodell mit; beim Neuladen wird das alte Modell
  vor dem Messen freigegeben.

### Neu (2026-10-29, Runde 5)
- Plan-Vorschau neben der Live-Konsole: der zuletzt erzeugte Bauplan als Draufsicht oder senkrechter Schnitt
  (z-Regler), mit Auftragstext, Token, Dauer, Token/s, Schritten, Blockzahl und Maßen. Näherung ohne zweiten Compiler;
  Module und Logikschaltungen erscheinen als Markierung.
- Meldungen im Infobereich bei Tunnel weg/wieder da, Modell bereit und Bauplan fertig/fehlgeschlagen, nur wenn das
  Fenster nicht vorne ist; abschaltbar in den Einstellungen (`notifications` in `config.local.json`, Vorgabe an).
- Statistik seit Programmstart in der Karte „Letzter Auftrag“: Aufträge, Fehlschläge, Ø Dauer, Ø Token/s, schnellster
  und langsamster Auftrag, Anteil des Prompts aus dem Speicher.

### Geändert (2026-10-29, Runde 5)
- Grammatik: neue ops `castle`, `wall`, `bridge`, `garden`, `tree` und Felder `width` (1–9) und `style`
  (`arch`/`beam`/`round`/`cone`/`square`) – Plugin und Host zusammen aktualisieren.
- Hauptfenster größer (1240 × 820), Konsole und Vorschau in einem verschiebbaren Teiler.

### Behoben (2026-10-29, Runde 5)
- Plan-Vorschau: Pläne aus vielen überlappenden Großschritten froren das Fenster für Sekunden ein (70 × fill 84³ =
  6,9 s). Jetzt bricht die Vorschau nach 2 Mio. Blockoperationen mit Hinweis ab (gemessen höchstens 0,61 s).
- Vorschau und Meldung zeigen in Reparaturrunden den ursprünglichen Auftrag statt „The plan is invalid: …“; ein Auftrag
  mit doppeltem Leerzeichen wird nicht mehr abgeschnitten.
- Ein Tunnel-Neustart aus den Einstellungen meldet nicht mehr „Tunnel getrennt“ und „wieder da“. Eine Meldung, die in
  dieselbe Abfrage wie eine Fehlerwarnung fällt, kommt eine Abfrage später, statt verloren zu gehen.

### Geändert (2026-10-28, Logik und Gedächtnis)
- Grammatik: Pflichtfeld `mode` (`new`/`update`) zwischen `reply` und `steps`, neue op `logic` mit Feld `expr`
  (Formel, höchstens 160 Zeichen). Pläne ohne `mode` lehnt die Grammatik ab – das Plugin braucht es, um einen
  Nachtrag vom Neubau zu unterscheiden. Plugin und Host müssen zusammen aktualisiert werden.

### Neu (2026-10-28, Runde 3)
- `max_tokens` wird an den freien Kontext gekappt statt HTTP 400; der System-Prompt wird nach dem Laden vorgewärmt.
- Autostart aus der App (HKCU Run, beachtet „Autostart deaktiviert“ im Task-Manager), stiller Start im Infobereich
  (`--im-hintergrund`), Sprechblasen bei Fehlern. Die alte Autostart-Verknüpfung des Installers entfällt: nach dem
  Update einmal in den Einstellungen das Häkchen setzen.
- Grammatik vollständig am Plugin-Schema (skyscraper, house, Koordinaten −64…200); ein Test bricht ab, sobald beide
  auseinanderlaufen.

### Neu
- Eigenständige Windows-App ersetzt `llm-host/start-llm.ps1`: llama.cpp b10900 im eigenen Prozess (Vulkan, CUDA als
  Komponente nach NVIDIA-Zustimmung), OpenAI-kompatibler Server an 127.0.0.1 im Antwortformat von llama-server,
  SSH-Rückwärtstunnel über plink + Pageant mit Neustart und Fehlerdeutung.
- Feste Bauplan-Grammatik (`src/plan/bauplan.gbnf`) passend zu `plan-schema.json` des Plugins.
- Live-Konsole mit Anfragen, Token-Strom, Dauer, Token/s und Tunnel-Ereignissen; Karten für Modell, Rechenweg,
  Server, Tunnel und letzten Auftrag; Infobereich.
- Modellkatalog Qwen2.5 14B (Standard, Kontext 8192, KV Q8_0) und 7B mit festgenagelten Prüfsummen; eigene GGUF-Datei
  mit Warnhinweis.
- Autostart: Die EXE lädt beim ersten Start das Modell herunter und startet Modell, Server und Tunnel von selbst.
- `einrichten.cmd`/`einrichten.ps1`: ein Befehl richtet den GPU-PC ein, baut und startet das Setup.

### Neu (Tempo)
- Turbo: spekulatives Dekodieren mit dem Entwurfsmodell Qwen2.5 0.5B (531 MB, wird bei Qwen2.5 automatisch geladen).
  Jedes Token zieht weiterhin das Hauptmodell – gleiches Ergebnis. Beim Laden misst die App, ob es auf der GPU
  schneller ist, und schaltet es sonst ab. Konsole zeigt je Auftrag die Trefferquote und die Zeit je Token (GPU/CPU).

### Neu (Modelle)
- Modellwahl direkt in der Modell-Karte mit Grafikspeicher je Modell; Umschalten lädt um, fehlende Dateien nach
  Rückfrage herunterladen. Katalog: Qwen2.5 14B/7B/3B, Mistral Nemo 12B, Llama 3.1 8B, Qwen3.8 9B.
- Ohne eigene Wahl wählt die App das größte Qwen2.5, das auf die Grafikkarte passt.

### Neu (Tunnel)
- Anmeldung an dev01 wahlweise per Passwort: DPAPI-verschlüsselt gespeichert, an plink über eine kurzlebige Datei
  (`-pwfile`), nie über die Befehlszeile. Port-/Tunnel-Änderungen wirken nach dem Speichern sofort.

### Geändert
- Tunnel erklärt „Cannot answer interactive prompts“ (Pageant ohne Schlüssel) und meldet fehlendes Pageant in der Konsole.
- Tunnel: plinks letzte Meldung steht in der Statuszeile (vorher nur „Exit 1“); gleiche Ursache nur einmal je Versuch.
- Server-Karte sagt „wartet auf das Modell“, solange keines geladen ist; „Modell laden“ ist während eines Downloads aus.
- `build.ps1` baut und startet die Tests nur noch mit `-Tests`; ohne den Schalter entstehen nur Programm und Setup.

### Behoben
- Vorhandene Modelldatei ohne Prüfmarke (z. B. vom anderen PC kopiert) wird per SHA-256 geprüft und behalten statt
  neu heruntergeladen. Die Modell-Karte verlinkt den Modellordner.
- Bau in tiefen Ordnern (z. B. `Downloads\baumeister-main (1)\baumeister-main\host`): MSVC scheiterte an der
  260-Zeichen-Grenze im Vulkan-Unterprojekt. `build.ps1` baut dann in `%LOCALAPPDATA%\BaumeisterBau\<kennung>`.
- `build.ps1`: Ein kopierter Bauordner (anderer Quellpfad im CMakeCache.txt) wird erkannt und neu angelegt, statt dass
  CMake abbricht; `-Clean` löscht nicht mehr die eigene Sperrdatei.
- Grammatik-Tests laufen auch, wenn nur `host\` kopiert wurde (Abschrift von Schema und Prompt in `tests/plan/data`).
