# Gedächtnis – StreamWizard Baumeister-Host

<!-- Gefilterte Fassung für das öffentliche Repo: Netz-, Zugangs- und Betriebsdetails des Heimservers fehlen absichtlich, das Kapitel „Deploy“ ganz. Die volle Fassung liegt nur lokal. -->

Stand: 2026-11-16

## Festgelegte Entscheidungen (nicht ohne Rückfrage ändern)
- Sprechen mit dem Begleiter (Nutzer 2026-11-04): Taste HALTEN, Vorgabe hintere Maus-Seitentaste; Mikrofon nur offen,
  solange die Taste gedrückt ist; whisper.cpp im Prozess auf DEMSELBEN ggml wie llama.cpp (v1.9.4 ↔ b10900, CMake
  prüft den Gleichstand). Wer llama.cpp hebt, hebt whisper.cpp mit.
- Host-App-Bedienung (Wunsch 2026-11-01): zwei Schalter „Verbinden“ und „KI einschalten“,
  Satz darüber (gui/simple_mode). In den Einstellungen nur „Der Server läuft …“ (Tunnel ja/nein) und „Beim
  Programmstart …“. Keine weiteren Tunnel- oder Start-Häkchen einführen.
- Eigenständige C++/Qt-App nach kraken-Muster (Präfix `swbh`), ersetzt `llm-host/start-llm.ps1`. Läuft auf einem
  GPU-PC (NVIDIA, 12 GB) ohne Java und ohne PowerShell zur Laufzeit. Das Plugin bleibt unverändert.
- LLM im eigenen Prozess (llama.cpp b10900, Commit `50182a53`), nicht llama-server.exe. Ein KV-Slot, Präfix-Wiederverwendung.
- CUDA nie im Setup; nur als Komponente nach Zustimmung zur NVIDIA-EULA. Ohne Zustimmung Vulkan.
- 14B als Q3_K_M (Nutzerwahl 2026-11-04 nach Messung: Q4_K_M neben Minecraft 41/48 Schichten GPU, GPU 45–49 ms +
  CPU 61–106 ms je Token, ~9 Token/s; Threads 4 → Kerne nur 8 % am Laptop). Gerechnet: 8,7 GB bei 9,1 GB Budget →
  alle Schichten GPU. Qualität für Baupläne gegenüber Q4 nicht gemessen. Budget (frei − 1,5 GiB) gilt für Kontext,
  Turbo UND Schichten.
- Modelle (Wunsch 2026-11-04), am selben Nachmittag zurück („lass die anderen Modelle
  in der Anwendung“). Stand: alle 13 wählbar, Vorgabe 14B Q3_K_M, früheres Q4 als qwen25-14b-q4. Nicht ohne
  Rückfrage Modelle entfernen oder die Vorgabe ändern. Eigene Datei bleibt (tools/train). Passt 8192 samt Entwurf nicht, ist Turbo aus
  (auto_context Schritt 3; gemessen neben Minecraft: 11,1 GB nötig, 10,7 frei → 145 ms je Token ausgelagert).
- Standardmodell Qwen2.5 14B Q4_K_M, KV Q8_0. Kontext seit 2026-10-29 automatisch (Wunsch): llm::auto_context nimmt den größten bis 32768, der samt Turbo-Entwurf in 95 % des freien Grafikspeichers
  passt; reicht es mit Turbo nur für 8192, ohne aber für ≥ 12288, bleibt Turbo aus (Plan vor Tempo). Katalog-n_ctx ist
  nur noch Obergrenze (32768). Vorher fest 8192. Katalog (2026-10-28, Wunsch): alle kraken-Modelle, die mit Kontext 8192 in 12 GB passen – Qwen2.5 14B/7B/3B, Mistral Nemo 12B,
  Llama 3.1 8B, Qwen3.8 9B (unzensiert). Ohne eigene Wahl: größtes Qwen2.5, das ≤ 90 % des Grafikspeichers belegt.
- Bauplan-Grammatik fest (`src/plan/bauplan.gbnf`), kein Schema→GBNF-Wandler. Nur Schema-Name `bauplan`; anderes
  Schema → 400 (fail-closed).
- Autostart Vorgabe AN (Wunsch 2026-10-28): fehlendes Modell wird
  beim ersten Start heruntergeladen. Bauen auf dem GPU-PC mit einem Befehl: `host\einrichten.cmd`.
- Plan-Vorschau ist eine NÄHERUNG ohne zweiten Compiler (`gui/plan_view`): Grundformen wie PlanCompiler.java
  (inDisc/inBall < r + 0,5), house/skyscraper ohne Einrichtung, module/logic als Markierung. Nur Aufträge mit
  Grammatik „bauplan“ ersetzen die Vorschau. Grenzen: 600 000 Blöcke je Schritt, 1,5 Mio. verschiedene Blöcke,
  2 Mio. Blockoperationen gesamt (`kMaxPreviewWork`).
- Auftragstext = erste Nutzer-Nachricht mit „Bauauftrag:“ (`server::order_text`, VOR `log::safe` am Zeilenumbruch
  getrennt). Buchführung Qt-frei in `gui/job_tracker`, der Controller ruft sie nur unter `mutex_` auf.
- Meldungen im Infobereich (`gui/notifier`): nur Übergänge; „Tunnel weg“ nur aus „verbunden“ und einmal bis „wieder
  da“; ein Wechsel von `tunnel_generation` zählt wie „Stopped“; gezeigt nur bei inaktivem Fenster; die Fehlerwarnung
  bei verborgenem Fenster hat Vorrang und bleibt auch bei `notifications: false`.


## Gemessen (nicht geraten)
- Schichten (2026-11-04): Neben Minecraft (10,7 GB frei beim Start) lief das 14B trotz passender Schätzung mit
  168–179 ms je Token, nach einem Weltwechsel 1389 ms – Minecraft belegt beim Spielen mehr. Seitdem Puffer
  kGpuReserveBytes = 1,5 GiB und teilweise CPU (gerechnet: 41/48 Schichten bei 10,7 GB frei). Schichtzahl über
  llama vocab_only = 0 (gemessen, GPU-Probe rot) → jetzt aus dem GGUF-Kopf; GPU-Probe mit 7B auf dem Laptop: 9/28
  Schichten bei halbem Budget, Antwort kommt. Tempo auf dem GPU-PC ungemessen; den Puffer dort nachmessen.
- Aussprache (2026-11-04, probes/piper_aussprache_probe.py, Thorsten medium): espeak-ng liest „Creeper“ kɾˈeːpɜ,
  „Redstone“ reːtstˈoːnə, „6:00 Uhr“ als „sechs Uhr null null Uhr“, „z.B.“/„ca.“ beenden den Satz. Richtig schon:
  Server, okay, Hey, Golem, Stronghold. Fehlgriffe: „ou“ → uː, „S-pohn“ liest den Buchstaben S. Tabelle in
  src/voice/speech_text.cpp. Gegenprobe: Uhrzeiten und Abkürzungen aus → test_voice rot.
- Piper (2026-11-04, dieser Laptop, Thorsten medium aus C:/swb/voice-probe/piper): erster Satz 4061 ms (Laden + 3,3 s
  Ton), zweiter 138 ms für 1,9 s Ton.
- Erkennung (2026-11-04, dieser Laptop, Intel-Grafik, whisper turbo-q5 aus C:/swb/voice-probe): Hedda-Aufnahme „Hey
  Vera, gib mir sechzehn Fackeln.“ (3,6 s) → „Hey Vera, gib mir 16 Fackeln.“; Laden samt Aufwärmen 4,5 s, Erkennen
  4,1 s. kraken maß auf der RTX 4070 Ti nach dem Aufwärmen ~0,1 s je kurzem Satz (nicht hier nachgemessen).
- 2026-10-30, dev01-Auftrag über den Tunnel (Qwen2.5 14B, RTX 4070 Ti): Prompt 5780 Token (3845 aus dem Speicher) in
  49,5 s = 39 Token/s gelesen, 396 Token mit 3,9 Token/s geschrieben, 152,2 s; Vorwärmen kurz davor 2563 Token/s,
  eine Probe kurz danach 3209 bzw. 89 Token/s. Ursache außerhalb der App (Grafikkarte belegt) – daraus die
  Tempo-Warnung (JobStats::slow_warning_de, Schwellen 25 %/35 %, ab 200 neu gelesenen bzw. 40 erzeugten Token).
- 2026-10-28, Laptop (Core Ultra 7 155U, Intel-Arc-iGPU über Vulkan, `allow_integrated` nur für den Test),
  Qwen2.5-VL-7B Q4_K_M als eigene Datei, Kontext 8192, KV F16, LlmProbe gegen die App:
  - Laden 5 s (29/29 Schichten auf der GPU, KV 448 MiB, Flash-Attention an).
  - „ein kleines Haus“: 100,5 s, 425 Token, 5,5 Token/s, Prompt 2230 Token in 23,8 s (kalt) → 13 Schritte,
    235 Blöcke, 0 Warnungen.
  - „ein Turm aus Stein“ direkt danach: 54,1 s, 304 Token, 5,7 Token/s, Prompt 2231 Token, davon 2222 aus dem
    KV-Speicher, 0,7 s → 8 Schritte, 250 Blöcke, 0 Warnungen.
  - Zum Vergleich llama-server auf derselben iGPU: 6,8 Token/s (Plugin-Gedächtnis). Nicht A/B gemessen, woher der
    Unterschied kommt (Grammatik neben der Kette, Temperatur 0,4).
- Schätzung 14B, Kontext 8192, KV Q8_0: 10,4 GB (`estimate_vram_bytes`, nicht gemessen).
- GPU-PC des Nutzers 2026-10-28: Qwen2.5 14B auf RTX 4070 Ti (Vulkan) in 4 s geladen, KV Q8_0 (sein Protokoll).
- Laptop, Qwen2.5 3B über die Modellwahl (Download 1,9 GB in 60 s, SHA-256 geprüft, von selbst geladen):
  „ein kleines Haus“ 43,7 s, 428 Token, 12,8 Token/s; „ein Turm aus Stein“ 23,8 s, 311 Token, 13,1 Token/s –
  beide gültig, 0 Warnungen, aber nahe an den Prompt-Beispielen.
- Erster Bau (llama.cpp + Vulkan-Shader, Ninja): rund 15 min; danach Neubau eigener Teile Sekunden.

- Aussprache (2026-11-16, piper_aussprache_probe.py, Thorsten medium aus C:/swb/voice-probe/piper): Mob mˈoːp, Mobs
  mˈoːps (langes o; die Notiz vom 04.11. „Mob richtig“ war falsch) → Mobb/Mobbs mˈɔp(p)s; Lag lˈɑːk → Läg lˈɛːk;
  „Blöcke/s“ → „ˈɛs“; „Stück/min“ → „mˈiːn“; „1:1“ → „ˈaɪns ˈaɪns“; Klammern ohne Pause; „&“ → betontes ˈʊnt;
  „Glas+Eisen“ schon richtig (plˈʊs). Regeln in speech_text.cpp, gleich wie SpeechText.java.
- Zeit je Token (Laptop, Arc-iGPU, Qwen2.5 3B): GPU 80,7 ms, Token-Auswahl CPU 1,0 ms, Rest 2,4 ms → fast alles GPU.
- Turbo (Entwurf Qwen2.5 0.5B Q8_0) auf der Arc-iGPU: 97–100 % der Entwurfs-Token übernommen, aber 9,7 statt
  11,9 Token/s – die iGPU braucht für 9 Token 415 ms gegen 66,5 ms für eines. Die Lade-Messung schaltet Turbo dort
  deshalb ab. Auf einer RTX 4070 Ti NICHT gemessen (keine Karte hier) – dort zeigt das Protokoll beim Laden die
  Zahlen („Spekulatives Dekodieren an (…)“ oder „Turbo lohnt auf dieser GPU nicht (…)“).
- 2026-10-29, Laptop, Release: Vorschau 70 × fill 84³ ohne Gesamtbudget 6,9 s; mit `kMaxPreviewWork` 2 Mio.
  10 × fill 84³ 0,61 s (3 Mio.: 0,77 s). Neuzeichnen der Draufsicht 265 × 265 je Zelle 40 ms, jetzt zwischengespeichert.
- 2026-10-29: `build.ps1` configure/build/test grün, 7/7 Testprogramme (Grammatik mit den neuen Bauformen).

## Sicherheitsregeln (mit Gegenprobe belegt)
- Gesamtfrist 60 s je Anfrage (2026-11-16, kRequestDeadlineMs): auf 24 h gesetzt → Slowloris-Test rot (Verbindung
  nach 4 s offen, keine Logzeile lesen_gesamtfrist), mit Frist 15/15 grün. Ladesperre (harden_image_loads,
  ProcessImageLoadPolicy NoRemoteImages + NoLowMandatoryLabelImages): ohne Bits → test_dll_guard rot, mit grün.
- 1-MiB-Grenze nur für /hoeren (2026-11-04): Grenze für alle Pfade → test_companion_api und test_http rot.
  Mikrofon-Kette: Automatik ohne Wirkung, Ränder nicht abgeschnitten, Ende ohne Sprache → rot; Begrenzer aus war
  zuerst grün (Automatik senkte ohnehin ab) – Fall „+30 dB von Hand“ ergänzt, dann rot.
- Sprechen (2026-11-04): Tastenwiederholung nicht entprellt → Hook-Test (SendInput F24) und PressEdge rot; Geräusch-
  Klammern nicht gefiltert → clean_transcript rot; Stille an Whisper → Utterance rot; mit allem 10/10, Stufe stage
  (PE-Importe gegen die Freigabeliste) grün.
- Begleiter (2026-11-04): Vorlesen nur für den eingetragenen Spieler (server::speak_for); Gegenprobe „nur Länge
  vergleichen“ war zunächst grün – Testfall mit gleich langem fremdem Namen ergänzt, dann rot. valid_player_name immer
  true → test_companion_api rot.
- Kein Vorgabeziel (2026-11-01): TunnelConfig::target wieder = kLegacyTarget → test_tunnel.cpp:197/199 rot; Übernahme
  alter Dateien aus → test_app_config.cpp:111/112 rot; mit beidem 9/9 Testprogramme grün.
- Absturz-Protokoll (2026-11-01): SetUnhandledExceptionFilter/set_terminate aus → swbh_test_win rot (Kind endet nicht
  mit 0xC0000005, kein Abbild, keine Zeile; 2 von 8 Fällen), an → 8/8 grün. Ende-Grund „erster gewinnt“ aus →
  test_exit_report.cpp:17/18 rot. Echte EXE: Bildschirmfoto-Lauf schreibt „beendet: Bildschirmfoto fertig (Exit 0)“ und
  räumt die Markierung; nach `taskkill /F` meldet der nächste Start „Letzter Lauf endete ohne Schlusszeile (…, Prozess
  16204)“.
- Kontext automatisch (2026-10-29): Katalog-Obergrenze zurück auf 8192 → test_models rot (n_ctx == 32768; 4070-Ti-Fall
  bleibt bei 8192 mit Turbo statt 16384 ohne), alte Config-Regel aus (n_ctx 8192 bleibt 8192) → test_app_config rot
  („8192 == 0“); mit beidem 7/7 Testprogramme grün. Die VRAM-Zahlen sind GESCHÄTZT (estimate_load_bytes), auf der
  4070 Ti nicht gemessen – dort beim ersten Start die Konsolenzeile „Kontext automatisch: …“ und die Token/s ansehen.
- Turbo ändert nichts am Ergebnis: Temperatur 0, „ein kleiner Wachturm“, mit erzwungenem Turbo (81 % Treffer) Text
  byteweise gleich wie ohne; Gegenprobe Entwurf ungeprüft übernommen → Text verschieden (2026-10-28).
- Vorhandenes Modell behalten: lokale Prüfung aus → test_keep_model.cpp:42 rot (Download versucht), an → 61/61
  grün (2026-10-28).
- Host-Prüfung (DNS-Rebinding): aus → `swbh_test_server` rot (test_http.cpp:99 REQUIRE, test_http_server.cpp:79
  403 erwartet), an → 16/16 Fälle, 122/122 Prüfungen grün (2026-10-28).
- Origin/Sec-Fetch-Site → 403: aus → rot (test_http.cpp:109, test_http_server.cpp:82), an → 122/122 grün (2026-10-28).
- Runde 5 (2026-10-29), je rot ohne / grün mit: `notifications` nicht gelesen (test_app_config.cpp:56); Tunnel-Klammer
  aus (test_notifier.cpp:51/54); Generationswechsel ignoriert (test_notifier.cpp:152/154); verdrängte Meldung verworfen
  (test_notifier.cpp:171); freie Antwort ersetzt Plan (test_plan_preview.cpp:114); Scheibe `<= r`
  (test_plan_view.cpp:88); Gesamtbudget aus (test_plan_view.cpp:169); Schnitt am Doppel-Leerzeichen nach `log::safe`
  (test_chat_api.cpp:168); Bild je paintEvent (test_plan_preview.cpp:158/160).

## Fallen
- Gegenprobe aus Git Bash: `pwsh -Command "& '$PWD\build.ps1'"` lädt nichts – $PWD ist ein POSIX-Pfad
  (/c/Users/…). Windows-Pfad ausschreiben; sonst testet man unbemerkt die alten EXEs (2026-11-16, vier Läufe „grün“).
- Slowloris-Test: Schickt der Client beim Schließen noch ein Byte, kommt RST statt FIN und Windows verwirft das 408
  beim Client. Der Test prüft deshalb Schließen + Logzeile, das 408 nur, wenn es ankommt.
- Ein leeres `target` hieß bis 0.1.x „dev01“ (die Einstellungen speicherten die Vorgabe als leer). Seit `version` 2
  heißt leer „kein Server“; nur Dateien ohne `version` bekommen beim Laden `kLegacyTarget`. Wer die Übernahme
  entfernt, nimmt dem eingerichteten GPU-PC still den Tunnel.
- Absturz-Protokoll fängt kein `__fastfail` (/GS, `std::abort` mit Fehlerbericht, Heap-Beschädigung) – dort bleibt nur
  die Lauf-Markierung und die Ereignisanzeige. Der Filter gibt EXCEPTION_CONTINUE_SEARCH zurück, damit Ereignis 1000
  erhalten bleibt.
- Verlauf: Die Vorschau-Spalte ist ~450 px schmal – mehr als vier Spalten schieben „Ergebnis im Spiel“ aus dem Bild
  (Bildschirmfoto 2026-10-31). Details gehören in Tooltips.
- Ergebnis-Zuordnung über den Auftragstext: Das Plugin schickt den Text ohne Plot-/Helfer-Angabe, genau wie in
  „Bauauftrag: …“ – ändert sich dort etwas, landen Ergebnisse als eigene Zeile statt beim Auftrag.
- Recherche-Schalter: Die App ist nur Schalter und Anzeige, die Abfragen macht das Plugin. Antwortet die App nicht
  (älterer Stand, 404, Tunnel weg), recherchiert das Plugin trotzdem (fail-open, 2 s). Wer „aus“ hart erzwingen will,
  stellt zusätzlich `llm.recherche: false` in der Plugin-Config.
- Recherche-Meldungen erscheinen nicht als Serverzeile „→ 200“ in der Konsole (wie /stadt), sondern als eigene Zeile.
- Der Lade-Thread ruft vor load() cancel() auf – ohne Zurücksetzen brach der Abbruch-Rückruf jede Messprobe ab
  (Turbo-Messung −1 ms). load() setzt den Merker jetzt zurück.
- `SWBH_TURBO=immer` erzwingt Turbo (nur Messproben).
- Der Host-Kopf trägt den Tunnel-Port (18080), nicht den lokalen – die Host-Prüfung erlaubt deshalb jeden Port.
- GBNF von llama.cpp: Zeilenumbrüche in einer Regel nur innerhalb von Klammern.
- `deps.lock.json → toolchain.iscc` steht mit `%LOCALAPPDATA%` (seit 2026-11-09 ohne Benutzernamen); build.ps1 und einrichten.ps1 lösen das auf.
- host\ samt out\ kopiert → CMake bricht ab („CMakeCache.txt directory … is different“, 2026-10-28). build.ps1
  erkennt den fremden Bauordner jetzt und legt ihn neu an. `-Clean` scheiterte vorher an der eigenen `.build.lock`.
- host\ allein (ohne Plugin-Ordner): Der Grammatik-Test nimmt dann die Abschrift `tests/plan/data/`. Mit
  Plugin-Ordner meldet er, wenn die Abschrift des Schemas veraltet ist.
- Smart App Control auf dem GPU-PC sperrt frisch gebaute Test-EXEs (ctest `0xC0E90002`, „Not Run“, 2026-10-28).
  Deshalb baut build.ps1 Tests nur mit `-Tests` (SWBH_BUILD_TESTS=OFF sonst). Hier vor jeder Abgabe `-Tests`.
- Modelle liegen in `%LOCALAPPDATA%\StreamWizard\Baumeister\models` – kein Code und kein Setup löscht sie (geprüft
  2026-10-28). Vorher lud die App eine vorhandene Datei ohne `.verified` neu herunter; jetzt lokale SHA-256-Prüfung.
- Pfadlänge: vulkan-shaders-gen legt seine CMake-Probe ~165 Zeichen unter dem Bauordner an. Ab ~75 Zeichen Bauordner
  scheitert MSVC („C1083 … Invalid argument“, GPU-PC 2026-10-28, Zip in Downloads). build.ps1 nimmt ab 60 Zeichen
  `%LOCALAPPDATA%\BaumeisterBau\<8 Hex>`; ein ausdrücklich zu langes -BuildDir bricht mit Meldung ab.
- Ja/Nein-Rückfrage vor dem Download: Beim Bedientest 2026-10-28 kam ein Klick auf „Nein“ als Zustimmung an
  (7,5-GB-Download gestartet, nach 370 MB abgebrochen). Ursache nicht geklärt; jetzt eigene Knöpfe, „Abbrechen“ als
  Vorgabe, gewertet nur der Download-Knopf – mit Abbrechen nachgeprüft.
- Kein Pillow auf diesem Rechner: Icon über `resources/branding/make_icon.py` (reines Python).
- `log::safe` macht aus „\n\n“ zwei Leerzeichen – den Auftrag VOR `log::safe` trennen; ein Schnitt am
  Doppel-Leerzeichen kürzte „ein  Haus“ zu „ein“.
- Gegenproben unter /W4 /WX: `if (false && …)` bricht mit C4127 ab – Rücknahmen mit nicht-konstanter Bedingung.
- Widget-Tests brauchen `QT_QPA_PLATFORM=minimal` (setzt der Test selbst; offscreen fehlt in obs-deps) und den PATH
  aus ctest. Direkt gestartet hängt `swbh_test_gui_widgets.exe` – über `ctest -R … -V` starten.

## Offen
- Setup 2026-11-16 (Stand 34ade66/de0bab8, SHA-256 20407474…) unsigniert, auf dem GPU-PC nicht installiert – am
  2026-11-16 20:5x dem Nutzer als Datei geschickt (Host seit de0bab8 unverändert). Relay-Status zu der Zeit `app=-`:
  keine App verbunden. Nach dem Update in der App „Mit Windows starten“ setzen ([InstallDelete] entfernt die alte
  Autostart-Verknüpfung); Einstellungen (Version 3, Relay-Adresse, Pins) bleiben in %LOCALAPPDATA%.
- Begleiter-Sprechen nicht im echten Fenster mit Mikrofon und Maus-Seitentaste geprüft (nur Hook mit simulierter
  F24, Erkennung mit Testaufnahme); Tempo auf der RTX 4070 Ti nicht gemessen; Erkennung und Sprachmodell zusammen im
  Grafikspeicher ungemessen. Name im Vortext kommt seit 381255a (2026-11-21) aus `begleiter=` im Long-Poll
  (geprüft: ≤ 96 roh, nur Buchstaben/Leerzeichen, sonst „Vera“); ob die Erkennung damit besser wird, ist nicht
  gemessen. Controller-Weiterleitung seit b1a1395 unter Test (companion_wiring, swbh_test_gui_wiring).
- CUDA/NVIDIA nicht prüfbar (keine Karte hier) – auf dem GPU-PC prüfen.
- 14B-Download (8,4 GB) nur nach Freigabe; Ende-zu-Ende über den Tunnel mit `/bm status`.
- Setup unsigniert.
- Meldungen im Infobereich am GPU-PC mit echtem Tunnelabbruch prüfen (hier nur Unit-Test und Bildschirmfoto).
- Ende-Grund nicht im echten Fenster geprüft: Windows-Abmeldung (commitDataRequest), Infobereich „Beenden“, Fenster
  ohne Infobereich – nur Bildschirmfoto-Lauf und `taskkill` gemessen.
- Plan-Vorschau gegen den Bau: seit e23daea an 50 echten Plänen 35/46 zellgenau (vorher 16/41), Probe
  `host/probes/plan_preview` + `PreviewCellsProbe`. Offen: Ersatzblöcke (6 Pläne) als Hinweis zeigen, Module mit
  Maßen vom Plugin melden lassen (4), Bauformen aus Structures (1); Vorschau zeigt auch Pläne, die das Plugin
  ablehnt. Im echten Fenster nicht angesehen.
- `MainWindow::refresh`/`notify_events`: seit b1a1395 über `window_feed` getestet (Plan genau einmal, verdrängte
  Meldung nicht erneut). Dedupe hängt auch am Notifier (Plannummer) – „nicht erneut“ sichern nur plan_changed und
  der Abrufzähler. `swbh_test_server` war 2026-11-21 in einem Gesamtlauf einmal rot, einzeln grün – Wackler, Ursache
  nicht gesucht; bei Häufung ansehen.
