# Gedächtnis – Baumeister

<!-- Gefilterte Fassung für das öffentliche Repo: Netz-, Zugangs- und Betriebsdetails des Heimservers fehlen absichtlich, das Kapitel „Deploy“ ganz. Die volle Fassung liegt nur lokal. -->

Stand: 2026-11-22

## Festgelegte Entscheidungen (nicht ohne Rückfrage ändern)
- Orchester-Erweiterung (2026-11-20/21, Nutzer: „erweiter … alle plugins und anwendungen“, Integration „Ohne
  Rückfrage“): Vertrag und Aufgaben in `.claude/orchestrierung/` (lokal, ausgeschlossen). OHNE Rückfrage entschieden:
  Burghof ab Seite 9 = Pflasterweg Tor→Brunnen, kleiner bleibt leer; Bogenbrücke mit erhöhtem Deck = Stufenrampe bis
  GROUND_Y an beiden Enden, höchstens 24 Stufen, fit kappt (27f9b39). Rücknahme: Hof-/Rampen-Zweig in Structures.
  Akkumulator mit Löschen (18e778a): eigene Maschine `akkumulator_mit_loeschen` neben akkumulator_4bit (gleicher
  Code, L schaltet 4 Komparatoren auf Subtrahieren, Takt schreibt 0); MachinesTest.TESTED_LAYOUT hält alle 47 alten
  Maschinen fest (sortiert – Bau-Reihenfolge NICHT gesichert). MachineProbe wartet 16 s nach dem Bau. <!-- umlaut-ok -->
  Client-Gedächtnis (39b3dda): ClientMemory je Welt, 1 h/48 Blöcke wie im Plugin, nur im Speicher; Rückgängig setzt
  es auf den Stand davor. Ungetestet: dass ClientBuilder remember/undoMemory aufruft (Aufrufe entfernen bleibt
  grün), forget() ohne Aufrufer, abgebrochener Bau ohne Block bleibt im Gedächtnis. → Seit e2b132a über den echten
  Builder getestet, Abbruch vor dem ersten Block fällt heraus; Placer im Test nachgebaut (runClientGameTest offen).
  Ladeprüfung (1dff973): SiteFingerprint und /bm ort über ChunkPrefetch; nicht ladbar = „nicht prüfbar“ → keine
  Vorschau/kein Bau bzw. kein Teleport (fail-closed). BuildJob/SiteFrame bleiben fail-open. Lästig, nicht falsch:
  entlädt Paper einen Chunk zwischen Laden und Rückruf, wird abgelehnt. Nicht live auf Paper/Spigot gemessen.
- Doku-Gliederung (2026-11-19, Nutzer: „mach das git repo schöner, verständlicher, für den Anwender und für den
  Entwickler … beschreibungen was aktuell geht und was nicht“): README = Einstieg „Wo fange ich an?“, Bilder,
  Tabelle „Was geht, was noch nicht“ (✅ läuft · 🧪 nicht mit Spieler gesehen · ⚠️ Grenzen · ❌), Belege im
  Handbuch „Stand“. docs/spielen.md für Spieler, docs/entwicklung.md für Entwickler, docs/README.md als Übersicht.
  Stand-Tabelle bei jeder Funktionsänderung nachziehen. Neue Bilder nur über ReadmeBilder (TunnelBilder für
  Tunnel/Treppe/Brücke), nie Spiel-Texturen. Nachtrag (Nutzer: „du hast die brücken, tunnel und treppen vergessen …
  im git repo“): Leitsatz, Kopfbild (Burg, Brücke, Tunnel, Treppe mit Namen), GitHub-About und Topics (tunnel,
  bridge, stairs) nennen sie; /bm-Hilfe nennt jeden Spielerbefehl, plugin.yml jeden Unterbefehl – je ein Test
  (BaumeisterCommandTest) hält das fest.
- Rückgängig-Tiefe 50 (2026-11-17, Nutzer: „Ja, auf 50“ auf „Rückgängig-Stapel auf 50 Einträge erhöhen und das
  Herausfallen loggen?“): Vorgabe und dev01 `bau.undo-tiefe: 50` (ConfigCheck-Grenze bleibt 1–50). Herausfallen loggt
  `rückgängig | aus_dem_stapel | besitzer= blöcke= alter_min= tiefe=` (UndoStack.dropLine). OHNE Rückfrage
  entschieden: nur die neuesten 5 (UndoStack.IN_MEMORY) halten Ursprünge im Heap, ältere ziehen auf ihre fertige
  Journal-Kopie um (offload/writtenCopy/rekey); ohne Rückgängig über Neustart bleiben alle im Speicher. /bm status:
  „…, N davon von der Platte“. Lag-Wache über UndoStack.take (findet auch den umgezogenen Eintrag). Rücknahme:
  undo-tiefe 5, offload-Aufruf in BuildService.trimUndo entfernen.
- Tunnel Runde E (2026-11-16, Nutzer: „er hört nicht mehr auf tunnel zu bauen“, „wie mache ich die treppe nach
  oben?“, „override … da steht was gebautes, da baue ich nicht“, „die treppe muss auch … von oben nach unten“,
  „edles design für tunnel und brücken“, „grundgestein … abbauen wenns im weg ist“, „alles was er abbaut … in einer
  vollen kiste … nicht nur für den admin“). OHNE Rückfrage entschieden, im Abschluss genannt:
  - Stopp: TunnelService.chains (Besitzer → Auftragsnummer, Konsole = UUID 0/0). stop() nimmt die Nummer heraus, ein
    Stück mit ungültiger Nummer startet nicht („tunnel | nicht_gestartet“); eigene wartende Aufträge fallen mit weg
    (ComfortService.dropQueued). Ein neuer Grabauftrag löst den eigenen laufenden ab („tunnel | umgeschaltet“, nicht
    hinten anstellen). `/bm tunnel|treppe|brücke` gehen NIE ans LLM (unverstanden = Beispieltext); Länge > 10 000 =
    Absage; „tunnel stopp“ = stopp (TunnelOrder.problem, vor Warteschlange und LLM in BuildService.request).
  - „trotzdem“ (TunnelOrder.FORCE) nur mit baumeister.admin: BUILT → Fels (TunnelNature.forced). Bleibt: FORBIDDEN,
    Behälter mit Inhalt (BuildJob schont sie immer), Grundstücke. Kreuzt ein Tunnel mit „trotzdem“ einen Gang quer,
    wird es eine Kreuzung (Raum des Gangs, TunnelScan.passage/crossing/room, je Stück höchstens 4); Treppe oder Gang
    längs → ohne „trotzdem“ neu planen, hält davor („tunnel | nicht_hindurch“). Gesucht wird an JEDEM Abschnitt mit
    abgetragenem Gebautem (Bretter davor verdeckten sonst den Gang). Ohne „trotzdem“ nur Hinweis (builtText).
  - Treppe aus dem Gang: TunnelScan.passage (Gang ohne Ende, around fand mitten im langen Tunnel keins) + room →
    Hülle des Gangs abbaubar, Innenraum frei. Zur Seitenwand schauen; längs stünden die Stufen im Gang.
  - Treppe nach unten: Stufenzahl, „bis y|Höhe N“ (TARGET, −64…320) oder bis zur untersten Schicht; Höhlen
    abgedichtet. KEIN Halt vor Hohlräumen (ARRIVED verworfen: letzte Stufe über der Decke bzw. 4 Blöcke Sturz). In
    einen Gang nie quer hinein – der Weg ist eine Treppe nach oben aus dem Gang.
  - Grundgestein: baumeister.bauen.gefaehrlich → über minHeight abbaubar, nie y == minHeight, im Nether nie ≥ 127.
  - Aussehen: Sockel (unterste Wandreihe, nicht im Gurtbogen), Mittelstreifen (Bodenmitte ab Breite 3), Gurtbogen
    gemeißelt (Tiefenschiefer: chiseled_deepslate statt polished_deepslate), Nether-Streifen gilded_blackstone.
    Brücke: Pfosten alle 3 (POST_EVERY, Laterne auf jedem zweiten), Deck-Mittelstreifen, Kapitell oben am Pfeiler
    (Brücke unter dem Deck bei base−1, Röhre in der Ecke unter der Wand bei base). Kisten-Stile ohne Sockel/Streifen;
    Weiterbauen übernimmt sie nur, wenn der Bestand sie hat (einheitlich).
  - Abraum: SpoilStore je Auftrag über alle Stücke; 27 Stapel → Fass in Nische (eigene Wand/Sockel, Fels dahinter,
    Fußhöhe, Öffnung zum Gang), Rest am Ende (auch bei Stopp/Ablösen). Frei = Fässer gratis; Kiste = erst die
    Materialkiste füllen, je Fass ein Fass oder eine Kiste daraus; ohne → Rest am Ende neben die Kiste. Brücke ohne
    Kiste sammelt nicht.
  Rücknahme bei Widerspruch: FORCE-Wörter leeren; POST_EVERY/trim/runner in TunnelStyle auf null; stow() aus Run.
- Tunnel gerade über die Karte (2026-11-16, Wunsch „Ein Tunnel muss straight gerade gehen – er wird irgendwann durch
  ca die komplette Karte gehen“). OHNE Rückfrage entschieden (Nutzer will nicht gefragt werden) – im Abschluss genannt,
  Bestätigung steht aus: Über Tal/Schlucht/See/Meer geht er als geschlossene Röhre auf derselben Höhe weiter (Fenster
  aus Glas in Augenhöhe, nie im Gurtbogen; Pfeiler unter jedem zweiten Licht bis 48 tief; Abschluss auch im Freien),
  „durch den Berg/bis ins Freie/auf die andere Seite“ = altes Ende (Spec.daylight). Weiterbauen sucht in vier
  Richtungen (TunnelScan.around: Blick nur, wenn dort Fels am Ende; sonst nächstes Fels-Ende; offenes Ende zuletzt);
  „grab einen Tunnel“ den Gang entlang = fortsetzen, zur Wand = Abzweig; „bau weiter“ ohne Erkanntes = Absage (vorher
  neuer Tunnel in Blickrichtung). Längen bis 10 000, Stücke à bau.max-ausdehnung im selben Rahmen; „durch die ganze
  Karte“ + schnell = 10 000, Schnellgang ohne Ende gibt es nicht (erzeugt Chunks ohne Halt). Mineschächte (nur
  mineshaft/mineshaft_mesa, nur ihre Steine samt Fackeln, nur in den Teil-Kästen) gräbt er durch; Festung, Antike
  Stadt, Prüfungskammer, Dorf, Bastion bleiben Halt. Weltrand = FORBIDDEN nach der Vanilla-Blockregel (isInside).
  Rücknahme bei Widerspruch: daylight zum Standard machen bzw. TunnelNature.DIGGABLE_STRUCTURES leeren.
- Tunnel, Treppe nach oben, Brücke (2026-11-16, Wünsche „er macht mit, auch im Schnellverfahren“, „schöne Lichter,
  nicht Fackeln“, „Brücke ohne Ende, auch über Meere“, „Tunnel und Brücken erkennen und dort weiterbauen“):
  eigener Weg ohne LLM (TunnelOrder → TunnelScan → TunnelStyle → TunnelShape über TunnelSite → BuildJob mit Dig,
  TunnelService). Rückfragen beantwortet: Materialkiste NUR für Tunnel/Treppe/Brücke (alle anderen Bauten bleiben
  frei); Abgebautes ALLES in die Kiste (Überlauf daneben, auch bei Admins, wenn eine Kiste da ist); „erkennen“ heißt
  Bestehendes fortsetzen, KEIN selbständiger Wechsel zwischen Brücke und Tunnel. Frei bauen: `baumeister.admin` oder
  Kreativmodus; „aus der Kiste“ zwingt die Kiste. Bauen aus der Kiste: kein Rückgängig, kein Baubuch (sonst Erz
  doppelt bzw. Reparatur ohne Bezahlung). Ohne Länge mitbauen (≤ 3 Abschnitte vor dem Spieler, Stück für Stück
  64 Abschnitte ohne Ende), nur mit Spieler – von der Konsole festes Stück. Gegraben wird nur Natur (TunnelNature);
  Gebautes innen beendet den Bau davor, in der Hülle bleibt es als Wand; Keller/Wiese vor dem Fels überspringt ein
  neuer Tunnel. Reihenfolge je Abschnitt: Hülle → Front des nächsten zu (Luft/Flüssiges) → innen ausräumen
  (Flüssiges zuerst) → Bogen, Kette, Laterne. „Brücke“ geht an den neuen Weg; die alte Bauform heißt im Buch
  „Bogenbrücke“ (Bogen-/Balken-/Zug-/Hängebrücke bleiben bei QuickForms). Paletten nur aus Blöcken, die 1.21.11 UND
  26.2 haben (TunnelStyleTest); `chain` heißt dort `iron_chain`.
- Lobby/Portalhalle (2026-11-15, `plan/Lobby`): Der Plan setzt nur Obsidian-Rahmen, nie nether_portal (Stufe
  gefährlich, sonst Absage je Portal für Spieler ohne Recht). Entzünden und Verbinden ist ein eigener Admin-Schritt.
- Bedienung (2026-11-08): Hilfebuch für alles mit wenigen Einträgen; lange Listen (Schematics) im Kistenfenster
  (/bm katalog), gebaut immer über /bm bau. Inhaltsverzeichnis über mehrere Seiten.
- README kurz mit Bildern, Volltext in docs/handbuch.md. Bilder zeichnet ReadmeBilder (src/test/.../bilder) aus dem
  echten Code, ohne Spiel-Texturen; neu: siehe Kopf der Klasse.
- Kommentare zitieren keine Chat-Nachrichten mehr wörtlich, nur „Wunsch <Datum>“ plus Begründung.
- Schematics (2026-11-08, Wunsch):
  Von Planet Minecraft NICHTS automatisch – deren Terms of Use verbieten „systematically extract data“ (5) und
  „Redistribute any content“ (7), jedes Werk gehört seinem Ersteller; einzelne Downloads legt der Nutzer selbst in den
  Ordner. Stattdessen die Download-Welten des Technik Wikis (GitHub MinecraftTechnikWiki, CC BY-NC-SA 3.0 laut Wiki,
  Repos ohne Lizenzdatei): Ausschnitte nur auf dev01 (`plugins/Baumeister/schematics/technik-wiki/`), nie ins Repo,
  nie ins Paket für Dritte. Bauten mit Befehls-/Struktur-/Verbundblöcken werden nicht importiert; StructureFile liest
  keine Blockdaten, also setzt kein Schematic je Inhalte oder Befehle.
- Stufen beim Setzen (2026-11-08, P3): alle · Inhalte (Kreativmodus online in der Welt des Baus oder
  `baumeister.bauen.inhalte`) · gefährlich (`baumeister.admin.gefaehrlich`) · Befehle (`baumeister.admin.befehlsblock`), <!-- umlaut-ok: Rechte-/Dateiname -->
  Kinder von `baumeister.admin` (LuckPerms-Admins mit `*`). Je Bautakt neu gelesen (BuildRights, PlacementGate);
  unbekannte IDs und Schlüssel → Befehle; Grundgestein, Barriere, Portalrahmen, Spawner abtragen braucht gefährlich.
  Die Admin-Stufen liegen absichtlich NICHT unter `baumeister.bauen.*` (sonst gibt der Platzhalter Befehlsblöcke frei).
  Nur echte Konsole und RCON gelten als Konsole, Befehlsblöcke/execute as nur „für alle“. Umlaut-ok: Rechtenamen ASCII.
- Befehle aus Plantext (2026-11-08, B5): bestätigbar ist nur ein Impuls- oder Ketten-Befehlsblock mit genau `Command`
  (+`auto`), ohne Stromquelle daneben (Plan und Welt, beim Fragen und beim Bauen). Wiederhol-Befehlsblöcke, Spawner-
  und Entity-Daten, Klick-Texte aus Plantext: nie. Klick gilt 10 min, nur für den Auftraggeber, nur für die Prüfsumme;
  `auto:1b` am Impuls-Block → `0b`, an Ketten-Blöcken bleibt es. Recherche-Text im Prompt deckelt den Auftrag auf
  Inhalte (vererbt über das Gedächtnis) – Abweichung vom Plan („keine Recherche für Admins“), weil die dem
  Hauptnutzer die echten Maße genommen hätte und Lava nicht abgehalten hätte.
- Vera-Befehle (2026-11-08, Sicherheitsprüfung H2): ungefragt nur aus der Freigabeliste in CompanionRules
  (Zeit, Wetter, Chat, eigener Spielmodus/Flug/Heilen/Effekt/XP/Geben ohne Daten/Teleport, mv tp, plot home); alles
  andere, `{…}`, fremde Ziele → getipptes „ja“. Nie zurück auf eine Sperrliste.
- Bauteil-Daten (2026-11-08): nur mit `daten: admin` in bauteile.yml; Altbestand und Nicht-Admins laden ohne Daten.
- Bauplan-Blöcke mit Daten (2026-11-08, P5): `block` bis 1000 Zeichen in der Form `id[zustand]{snbt}` (Schema, GBNF
  `str1000`); window/roof/gable bleiben 80. Der Prompt-Abschnitt „BLOCK DATA“ ist englisch wie der ganze Prompt; die
  Zeile `Rechte: inhalte=…, gefaehrlich=…, befehle=…` muss wortgleich bleiben (GrammarDriftTest), fehlt sie, gilt
  alles als „nein“. `auto:1b` vom LLM ist verboten.
- Sprache über die App (2026-11-08, P5): `fernverbindung.apps.<name>.spieler` bindet „gehört“ an Spieler je App
  (`X-Baumeister-App` vom Relay); leer = wie beim Tunnel. Gesprochenes bestätigt NIE Befehle – „ja“ nur getippt; auch
  harmlose gesprochene Befehle fragen nach. „hören“ (Simple Voice Chat) bleibt ungebunden.
- Bedienhinweise der Farmen (Wunsch 2026-11-08): deutsch in `FarmGuide`, NICHT in der Modulbeschreibung
  – die geht englisch in jeden LLM-Prompt und bleibt ≤ 220 Zeichen. Beim Auftrag nur „Leg schon bereit: …“ (≤ 200
  Zeichen, acceptPlan kürzt), die Schritte nach dem vollständigen Bau (BuildService.tellGuides, Log `bau | anleitung`),
  an den BuildJob gebunden (eine Reparatur sagt sie nicht noch einmal). Gespiegelte/verdoppelte Fassungen bekommen keine
  (links/rechts, Zahlen stimmen dort nicht). FarmGuideTest bindet jede Zahl und Lage an den Bau.
- Craften (2026-11-07): Vera stellt nur aus dem Inventar des Spielers her, genau die Zutaten, Reste zurück – kein
  Vervielfältiger, daher ohne eigenes Recht. Benannte/verzauberte Gegenstände nimmt sie nicht. Ohne Zutaten im
  Kreativmodus: Geben über give() (dort gelten die Rechte). Mitfahren nur bei freiem Platz nach Vanilla (CompanionRide).
- Farmen (2026-11-07, Wunsch): Die geprüften Farmen enthalten keine Lava und
  keine Betten (seit den Stufen 2026-11-08 darf ein Admin Lava setzen, Betten setzt jeder, wo sie gehen – die Farmen
  bleiben trotzdem so). Bruchsteingenerator: Lava gießt der Spieler. Eisenfarm:
  Seelenlagerfeuer statt Lavaklinge, Betten stellt der Spieler. Farmwörter je Farm eng (QuickOrders.FARM_WORDS);
  zwei Farmen oder fremde Zusätze → LLM.
- `/mods` (2026-11-07): Ein Server kann Mods nicht an Clients schicken. Paket über tools/modpaket (Spiele-PC),
  Download-Link nur über `mods.link`; leer = „gibt dir der Admin“. Baumeister-Client nie öffentlich hochladen.
- Vera-Rechte (2026-11-07, Wunsch): VeraRights + CompanionRights,
  Datei vera-rechte.yml. Reihenfolge Eintrag → vergebenes Recht → Stufe. Die Vera-Rechte stehen in plugin.yml mit
  „default: false“ – Bukkit zählt Vorgaben als gesetzt (javap PermissibleBase), sonst wäre die Stufe „Aus“
  wirkungslos. Einträge je Spieler als PermissionAttachment. Admin nur durch Ops, nie das eigene. Kaputte Datei:
  Vorgaben, Änderungen gesperrt.
- Charakter (2026-11-07): Vorgabe ANPASSEN; frech nur mit Fähigkeit FRECH. Auch frech keine Schimpfwörter über
  „Mist“, nichts Sexuelles, keine Beleidigung echter Eigenschaften (CompanionCharacterTest prüft die Sätze).
- Von selbst fasst Vera nur Harmloses an (Glocke, Notenblock, Holztür/-falltür/-tor), nie Hebel, Knöpfe oder Kisten.
- Begleiter im Spiel (Nutzer 2026-11-04): **Simple Voice Chat** (Client: Fabric + Mod statt OptiFine) für Hören und
  Sprechen; Stimme **Piper wie in kraken** (Vorgabe Thorsten medium). Erkennen und Sprechen rechnet der Host auf dem
  GPU-PC, das Plugin schickt nur Ton hin und her. Nur CompanionVoicechat kennt Voice-Chat-Typen (Test prüft das).
- Begleiter (Wunsch 2026-11-04): Sprache mit Mikrofon per **Taste gedrückt halten**, **kein
  Horror**, Geben **nur im Kreativmodus oder mit Recht**, **Mannequin** wie der Baumeister, Name einstellbar (Vorgabe
  Vera). Sprache läuft in der Host-App auf dem PC des Spielers (Client ist Vanilla + OptiFine, keine Fabric-Mod →
  kein Simple Voice Chat); über den Tunnel geht nur Text. Rechnen und Suchen im Plugin, nie im Modell.
- Mitspieler (Wunsch 2026-11-04): CompanionRouting – eigener Begleiter zuerst,
  sonst der nächste in 16 Blöcken derselben Welt. Voice-Chat schneidet Besitzer und Spieler in Hörweite mit (Liste
  alle 10 Takte, volatile). Host erkennt Fremde nur mit `fremde_verstehen` (Vorgabe an, server::may_hear) – ersetzt
  die frühere Regel „nur der eingetragene Spieler“. Befehle (komm/bleib/weg/rückgängig) nur vom Besitzer; Gespräch
  ohne Namen und „ja“ auf Rückfragen nur vom selben Spieler (Buddy.partner/pendingBy). Gegenproben: nächster fremder
  Begleiter aus → CompanionRoutingTest rot (3); Schalter wirkungslos → swbh_test_server rot.
- Voice-Chat vor App-Mikrofon (Nutzer 2026-11-04): Plugin hängt `?imspiel=Name,…` an den Long-Poll (Liste alle
  10 Takte), Host `own_player_in_game` → `SpeechInput::pause_until(+60 s)` (Frist statt Schalter, verstummtes Plugin
  sperrt nicht ewig). Plugin verwirft zusätzlich App-Sätze von Spielern mit verbundenem Voice-Chat. Gegenproben:
  heardUri ohne Liste → CompanionVoiceLinkTest rot; own_player_in_game immer falsch → swbh_test_server rot.
- Pool (2026-11-04, plan/Pool): Schnellauftrag wie Bunker – Wasser y −1..−Tiefe, darüber frei (sonst SEALED_WATER),
  Leiter waterlogged=true, Terrasse smooth_stone. Nur Poolwort + Größe + Füll-/Beschreibungswörter; „zu dem Bunker“,
  „mit Rutsche“, „aus Glas“ gehen ans LLM. Vera-Direktweg CompanionChat.buildOrder („bau|baue|bauen (wir)“ +
  Artikel/mir/uns…, „kannst du mir … bauen?“ ohne Fragewort) – „baut“ bewusst nicht (verhörtes „Brot“).
  Gegenproben: pool() aus → PoolTest rot (2), buildOrder leer → CompanionChatTest rot, POOL-Muster aus →
  SpokenWordsTest rot.
- Runde Spaß (2026-11-04): Jokes (fester Stapel), AdventureBook.ranking, AdventureDiary (Run.doneAt je Etappe,
  JSON „zeiten“, ältere Bücher ohne Zeiten bleiben lesbar), Player.openBook statt Gegenstand. weltbau.auto ist und
  bleibt false (WorldBuildDefaultsTest) – dev01 meldete bei jedem Start auto=false. Gegenproben: Rangliste
  unsortiert, Witze mit Wiederholung, Witz-/Tagebuch-Satz aus, Etappenzeiten aus → je rot.
- Aussprache gemessen 2026-11-04 22:45 (Piper 2023.11.14-2 + Thorsten medium in %USERPROFILE%\.local\opt\
  piper-messung, SHA-256 = deps.lock.json): Creeper → kɾˈeːpɜ, mit „Krieper“ kɾˈiːpɜ (stand schon in der Tabelle);
  neu Nether→Näther, Hardcore→Haadkohr, Enderauge→Ender-Auge. Vera-Rückkehr über Neustart: begleiter-wiederkommen.json
  (shutdown schreibt synchron, start liest und ruft join für alle Online-Spieler).
- Zwei Stimmen (2026-11-04 22:24): mehrere say() kurz hintereinander → je eigener Audiokanal, gleichzeitig.
  Jetzt Buddy.voiceChain (Erzeugen nacheinander) + CompanionVoicechat.play-Warteschlange je Entität (setOnStopped,
  höchstens 3 warten). Im Abenteuer AdventureFilter VOR dem Sprechen (give/command/build/Erz-find raus). Kreativ:
  fair() verlangt Überleben/Abenteuermodus; normal Pause, Hardcore Ende ohne Bestleistung. Outfit CompanionService.
  outfit (Elytren, Leder #8b5cf6 + Amethyst/Flow, Laterne). Aussprache „Creeper“: Tabelle in host speech_text.cpp
  hat „Krieper“ schon – der Nutzer hört es trotzdem deutsch; neu messen geht nur mit Piper (liegt nur auf dem GPU-PC).
  Gegenproben: Filter aus → AdventureFilterTest rot (2), BUILD_MISHEARD/Name am Ende/Abendreuer/Hilfe je aus →
  CompanionChatTest rot.
- Voice-Chat 415 (dev01 2026-11-04 22:11, gemessen im App-Log): check_request verlangte JSON für JEDEN POST, der Ton
  auf /hoeren kommt als octet-stream → jeder Satz abgewiesen. Jetzt nur dort octet-stream (test_http „Ton aus dem
  Spiel …“). Meine companion_api-Tests riefen den Handler direkt auf und sahen die Prüfung nie – neue Endpunkte immer
  auch durch check_request testen. Gegenprobe: Ausnahme aus → swbh-Tests 1 rot, an → 10/10.
- Wiederkommen (2026-11-04 21:52–21:57 gemessen): quit merkt den Besitzer (comeBack), join ruft Vera nach 40 Ticks
  zurück, auch nach Neustart bei laufendem Abenteuer. Ohne Vera schneidet CompanionVoicechat niemanden mit
  (canHear) – bewusst so lassen (Datenschutz), deshalb Vera zurückholen statt alle mitschneiden. Installer: [Messages]
  de.* in include/messages.iss duzen (64 Schlüssel aus German.isl 6.7.3).
- Abenteuer (2026-11-04, companion/Adventure + AdventureBook, CompanionAdventure): Farmwelt = `world` (+ Nether/End;
  Portal/Warp „farmwelt“, Region spawninfarmwelt). Fortschritt aus Inventar-Merkmalen WÄHREND des Laufs, NICHT aus
  Errungenschaften (serverweit, bei Admins längst erledigt). Merkmale nur im Überleben/Abenteuermodus gezählt, bleiben
  im Spielbuch. Etappen nur vorwärts, Sprung zur weitesten. Hardcore = ein Leben fürs Abenteuer, Spielmodus bleibt
  (kein Zuschauer). Fair-Play (fair()): kein give/command/Teleport/build und keine Erze – auch für Admins.
  Mitkämpfen nur normal: Monster ≤ 8 um den Spieler, Schlag 5 Schaden alle 0,7 s nach Wanduhr. Wege über
  locateNearestStructure (Dorf: 5 Typen à 16 Chunks; Festung 100 Chunks nur mit Enderauge), sync auf Nachfrage.
  Gegenproben: Sprung aus → AdventureTest rot, Hardcore-Ende aus → AdventureTest rot (2), adventure() leer →
  CompanionChatTest rot. Kämpfen, Wege, Warnungen und fair() ohne Test (Bukkit).
- Schnellauftrag mit Überbau (2026-11-04, plan/QuickPlacement): statt verwerfen kleinste Verschiebung (Manhattan,
  ≤ 40, erst seitlich, dann weg vom Spieler), Grundriss + 2 Abstand frei von allen Bauwerks-Kästen und nicht auf dem
  Spieler; danach DERSELBE Zellen-Überbau-Schutz auf dem verschobenen Plan. Nicht bei Anbauwunsch (AttachPlanner.WORDS).
  „Fort, Max“/„Fortnox“ → Fort Knox (SpokenWords). Vera-Direktweg geben: CompanionChat.giveOrder + ItemNames.
  resolveStrict (ohne Tippfehler-Nähe, sonst Modell); ohne Zahl 16 bzw. 1 bei Stapelgröße 1.
- Vera-Befehle (2026-11-04): alle COMMAND-Aktionen einer Antwort sind ein Bündel – eine Rückfrage für alle, Sicherung
  aller Bereiche VOR dem ersten. CompanionRules.repairCommand: nackte Zahl ≤ 16 neben „~“ → „~n“, setblock ohne Wörter
  nach Block/Modus. Host-Ausfall im Log über OutageLog (Beginn, alle 15 min, Rückkehr) statt je Minute; Minikarte
  scannt/speichert nach Wanduhr (`/tick rate 2000` machte aus 10 min 6 s). Prompt-Beispiele: 20.
  Gegenproben: shift aus → QuickPlacementTest rot (2), FORT_KNOX aus → SpokenWordsTest rot, giveOrder leer →
  CompanionChatTest rot, resolveStrict mit byTypo → ItemNamesTest rot, repairCommand aus → CompanionRulesTest rot,
  OutageLog ohne Zustand → OutageLogTest rot (2). Wanduhr der Minikarte ohne Test.
- Vera-Fähigkeiten 2026-11-04: mark/goto/where/remind/look. Teleport nur Kreativ oder `baumeister.begleiter.reisen`
  (sonst Richtung). Orte in `begleiter-orte.json` (atomar, 20 je Spieler). Erinnerungen nach Wanduhr im Takt, nicht
  über Neustart. Beispiele im Prompt: 20 (Plugin- und Host-Test zählen mit).
- Küste der Erdwelt (2026-11-01): im WELTBAU aus der OSM-Küstenlinie (SeaMask → Marker im CityPlanner → TerrainFit am
  Meeresspiegel `seaLevelY` = round(−shift)), nicht im Generator – das Höhenmodell bleibt unverändert (Regel unten),
  der Weltbau hat sein Rückgängig. Land links der Linie; OsmImport dreht bei gespiegelter Projektion um. Abtrag
  höchstens 24 Blöcke (MAX_CARVE), Tiefe 1 + (Abstand−1)/2 bis 12, Strand bis 3 Zellen vom Meer und ±3 Blöcke.

- Client-Mod (`client/`, 2026-10-31; seit 2026-11-07 Minecraft 26.2, Fassung 0.2.0): Fabric, Loom 1.19.4
  (`net.fabricmc.fabric-loom`, ohne Mappings – 26.x ist nicht verschleiert, `implementation` statt
  `modImplementation`), Gradle 9.8.2 mit JAVA_HOME `%USERPROFILE%\.local\opt\jdk-25.0.5+8`. Umbenennungen 26.x:
  ClientCommands, KeyMappingHelper, GuiGraphicsExtractor.text, sendSystemMessage/sendOverlayMessage, gui.screen(),
  getConnection().waitForChunksRender(). Plan-Logik aus `../src/main/java` (plan, logic), keine
  Kopie. Spieler-Bauweise: Ziele strikt nach y sortiert, Standplatz per Breitensuche (erst über der Zielschicht),
  Klick immer mit Schleichen (Eingabe-Paket an/aus um den Klick), Zustand über `block_state`; Rückstellung ans Ende
  der eigenen Schicht, ein Versuch zählt erst nach 10 Ticks, nach 3 ausgelassen. Vergleich ohne berechnete
  Eigenschaften (ClientBuild.DYNAMIC). Befehls-Bauweise nur mit `Permissions.COMMANDS_GAMEMASTER`.

- Plot-Modus (2026-10-31): nur wenn der Auftrag den Plot nennt („auf meinem Plot“, „Grundstück“, „Parzelle“) und der
  Spieler auf einem steht. PlotSquared über Reflexion (PlotSquaredPlots, keine Abhängigkeit, kein Download); nicht
  lesbar = kein Plot (gebaut wird wie sonst, die Rechteprüfung des Baus bleibt). Rahmen = Plotecke zur Blickrichtung
  (PlotLayout.frame), Hauptbau mittig, Gestaltung ZUERST und der Hauptbau danach (gewinnt jede Zelle). Überbau-Schutz
  im Plot-Modus erst auf dem fertigen Plotplan in startJob (vorher stand der Bau noch nicht an seiner Stelle).
  Fremder Plot: Absage vor dem LLM (Admins ausgenommen).
- Ergebnis an die App: ResultReporter ↔ host/src/server/result_api.cpp, Felder auftrag/ergebnis/grund/bloecke/plot (umlaut-ok)
  (ASCII auf der Leitung), ergebnis aus gebaut|verworfen|abgesagt|abgebrochen. Zuordnung im Verlauf über
  den Auftragstext ohne Plot-/Helfer-Angabe (BuildService.orderText = was ans LLM ging).
- Schnellgang-Schätzung mit BuildJob.WALK_FACTOR 3 (gemessen 2,9–3,3).

- Bessere Pläne (2026-10-30, „die baupläne allgemein klappen nicht so doll“): Doppelte Schritte fallen in
  PlanParser.normalize weg, das LETZTE Vorkommen bleibt (ein Schritt schreibt jede Zelle neu, das frühere ändert am
  Endzustand nichts; das erste zu behalten verlöre „A über B“ bei A, B, A). PlanReview lässt Kleinfehler ohne LLM weg:
  WINDOW immer, FLOATING nur Gruppen ≤ 3 und zusammen ≤ 8 – größere Teile gehen weiter in die Reparaturrunde. DOOR
  bleibt beim LLM (eine fehlende Tür wäre der Eingang).
- Bunker/Fort Knox/Taschenrechner sind Schnellaufträge (Bunker.java, QuickOrders). Fort Knox nach Wikipedia
  (United States Bullion Depository): 32 × 37 m, 13 m, Tresor zwei Geschosse, Wachposten an den vier Ecken. Unter
  der Erde wird mit fill air ausgehöhlt, die Leiter ist der letzte schreibende Schritt (Löcher in den Decken).
- Weltboden: BuildService.startJob sagt ab, wenn by + min.y ≤ getMinHeight (unterste Schicht bleibt).

- Helfer bei normalen Bauten (2026-10-30, HelperWish): Auftrag „mit N Helfern“ (nur dieser Bau, fürs LLM
  herausgeschnitten) → stehender Wunsch je Spieler (`/bm helfer`, Chat „mehr helfer“; nur im Speicher, Neustart =
  Größenregel) → Größenregel `helfer.*`. 0–16 wie im Stadtbau. Im Lauf wird der Rest des Abschnitts neu verteilt
  (Identität, nicht equals), im Schnellgang Tempo × Arbeiter/Arbeiter_bei_Start.
- NPC = Mannequin (Spigot-API ab 1.21.9), kein Citizens, kein NMS.
- LLM liefert Grundformen (fill/walls/roof …), keine Einzelblöcke; Koordinaten lokal (front/back/left/right), Drehung im Plugin.
- Spätere Schritte überschreiben den Zustand, nicht den Zeitpunkt (Fenster entstehen mit der Wand).
- Kompiliert gegen die JARs aus dev01 (`lib/`, systemPath) statt Spigot-Nexus.
- Stadtbau (OSM) ohne LLM und ohne Rechteprüfung je Block, dafür nur `baumeister.admin`. Setzen ohne Physik
  (`setBlockData(data, false)`), gleiche Blöcke übersprungen. Maßstab 1 Block = 1 m, Gelände flach (keine Höhendaten).
- Chat-Bitte „<Ort> nachbauen“ nur mit „nach/nachbauen“ am Ende – sonst gingen normale Bauaufträge an Overpass.

- Stadtbau in Etappen 256 m, Kartenkacheln 1280 m (= 5 × 5 Etappen, symmetrisch um die Mitte). Gebäude gehören der
  Etappe ihres Schwerpunkts, Boden nie unter Gebäuden. Nächste Etappe erst planen, wenn ≤ 40 Abschnitte in der
  Warteschlange sind.
- Hochhäuser plant das LLM nicht selbst, sondern über `op: skyscraper` (Geschoss = 4 Blöcke). Module nur bei
  ausdrücklichem Maschinenwunsch.
- Logik nie vom LLM als Einzelblöcke: `op: logic` mit Formel → LogicCircuit (Quine-McCluskey → festes UND-ODER-Raster,
  Leitungen bei z = 8i, umgekehrte bei 8i+4, Sammler eine Ebene höher). Grenzen **5 Eingänge / 4 Ausgänge / zusammen
  16 Terme** – GEÄNDERT am 2026-10-29 auf Wunsch, vorher 5 Eingänge / 5 Terme
  (S1 des 2-Bit-Addierers braucht allein 6; 16 ist die längste Minimalform bei 5 Eingängen). Mehrere Ausgänge als
  Formeln mit „;“ in EINEM `expr` (Host-Grammatik und Schema unverändert), Lampen hinten in Formelreihenfolge.
  Verstärker in Lücken-Spalten so spät wie möglich; Schaltungen bis 5 Terme Block für Block wie 2026-10-28
  (Fingerabdruck `LogicCompilerTest.TESTED_LAYOUT`). Kein benannter Ausgang als Eingang (keine Rückführung);
  Vorgabenamen weichen aus (Q → Y → Z).
- Logik in Stockwerken (2026-11-07, Wunsch): je Stockwerk höchstens
  4 Ausgänge und 16 Terme, der Reihe nach gefüllt; insgesamt 16 Ausgänge, je Ausgang 16 Terme, 4 Stockwerke.
  „3d“ vor der Formel = jeder Ausgang für sich (ceil(n/4) je Stockwerk). Stockwerk f = flaches Raster um (+2, 4f, 0)
  verschoben. Je Eingang ein Turm bei x=0, z=8i: Staub (1,1) → Stein (0,1) → Fackel/Stein im Wechsel, Verstärker
  (1,4f+1) in den Einspeise-Stein (2,4f+1) an Stelle des Hebels. Staub zuerst, weil ein vom Plugin umgelegter Hebel
  den Block, an dem er hängt, nicht weckt. Eine Ebene bleibt Block für Block wie vorher (Fingerabdruck). `expr` bis
  480 Zeichen in LogicExpr, plan-schema.json und bauplan.gbnf (GrammarDriftTest hält sie gleich).
- Bauformen castle/wall/bridge/garden/tree plant das LLM nicht selbst; Geometrie in `plan/Structures`, gedreht werden
  Positionen UND Blockzustände. Grenzen je Form KAPPT `Structures.fit` mit Warnung, abgelehnt wird nur außerhalb des
  Schemas. `wall` verlangt from.y == to.y (ein Zeichen neben `walls`). Der Linter zählt Bauformen über ihre echten
  Zellen, nie über einen Kasten. Bauform-Wörter nur als ganze Wörter plus feste Komposita-Liste; ein Wort nach „aus“
  ist Material.
- Plan-Prüfer (PlanReview): schwer = Linter-ERROR + schwebende Teile → Reparaturrunde, danach `llm.plan-prüfer`
  (Vorgabe streng = Absage bzw. Hochhaus-Rückfall). Gelände und Bestand zählen als Halt. Der Linter selbst bleibt
  fail-open (Ausnahme → ungeprüft bauen); der Überbau-Schutz bleibt fail-closed und wortgleich.
- Anbau (AttachPlanner): breites Wortmuster nur für die Anschlussprüfung, enges für Wandvorgabe und Ausrichtung,
  abgeschaltet durch Stockwerk/Etage/aufstock/Dach; Satzende-„an“ nur mit Bau-Verb. Wand am größten Bauwerk, genannte
  Seite gilt, sonst meiste freie Tiefe. Keine Vorgabe bei Folgeaufträgen mit Gedächtnis.
- Warteschlange speichert nur den Auftrag, keine Freigabe: Rechteprüfung erst beim Dranwerden (fail-closed),
  höchstens 10 gesamt / 2 je Spieler. Vorschau hängt am Auftrag (`Request.previewOnly`), Entscheidungen in
  ComfortFlow ohne Bukkit; Bestätigen nur bei unveränderter Prüfsumme des Bauplatzes (SiteFingerprint).
- Rückgängig über Neustart: Ordner mit `eintrag.json` (zuletzt und atomar geschrieben); Laden fail-closed (Muster,
  keine Verknüpfungen, lückenlose Teile, höchstens PART Einträge je Teil, Zustände von `Bukkit.createBlockData`
  akzeptiert, Summe = Zettel), sonst löschen. Weltname nur `[A-Za-z0-9_.-]{1,64}`.
- Config-Migration ergänzt nur, ändert nie einen Wert: Sicherung (10) → atomar schreiben → gegenlesen. Kaputtes YAML
  bleibt unangetastet. Vorlagenwerte = Vorgaben im Code (ConfigCheckTest), Grenzen in `ConfigCheck.RANGES` mitziehen.
- `/bm neuladen` nur über ConfigReload (erst getrennt lesen und prüfen, dann reloadConfig); BuildService nur bei
  geänderten Settings neu, nie während eines Auftrags, bei vollem Rückgängig-Stapel nur mit „erzwingen“. Neustart
  nötig nur für `erde.*`, `weltbau.auto`, `weltbau.abstand-sekunden`.
- Stadtbau mit Helfern = Bau nacheinander: Ein Abschnitt beginnt erst, wenn kein früherer mit berührendem Rechteck
  unfertig ist (StagePrep.takeNext). Keine Etappe wird im Hauptthread vorbereitet, auch Etappe 0 nicht. Tempo-Budget:
  gesetzter Block = `stadt.gleiche-zellen-je-block` Einheiten, jede angefasste Zelle 1; harte Grenze bleibt ms-pro-tick.
- Stadtausstattung: Zwischenspeicher-Name = „q“ + 8 Hex von SHA-256 über den Abfrageinhalt (`OsmClient.cacheTag`).
  Schildtext schreibt SignWriter in eigenem Takt; in `CityJob.finish()` steht `signs.close()` VOR dem Freigeben der
  Chunk-Tickets. „Frei?“ nur mit Wissen aus dem Rand (24 Blöcke), gesetzt nur im Ausschnitt. Brückenhöhe nur aus dem
  Modell, `layer>0` ohne `bridge` nur mit Querung. building:part-Gruppen einmal je CityModel.
- Wasser der Erdwelt: Meer nur mit Verbindung zum Ozean (BasinMap, Zoom 7, Flutfüllung ohne Fenster); Seefläche im
  geschlossenen Becken nur bei flachem Fenster 12 × 12; die Höhe (top) bleibt exakt, nur das Wasser ändert sich.
  Nicht ändern: deepSeed nicht unter 1 025, kein Suchfenster in BasinMap, kein Seespiegel je Zoom-7-Komponente, kein
  Fenster für die 0-m-Regel (Gründe in BasinMap/WaterTable).
- Gedächtnis je Spieler im Speicher (1 h, 48 Blöcke Umkreis), kein Speichern auf Platte. Nachtrag = vollständiger
  neuer Plan (`mode: update`), Wegräumen nur eigener Blöcke, die noch unverändert stehen.
- Gelände 1:1 senkrecht nur über die Datapack-Dimension `baumeister:gelaende` (y −2032..2031) mit Populator; das
  Plugin bleibt POSTWORLD (ein Plugin-Generator für Datapack-Dimensionen ginge nur mit load: STARTUP). Orte liegen
  in einer Welt bei x = Platz · 65 536. Das Datapack schreibt nur `/bm gelaende einrichten`, nie automatisch.
- Erdwelt = zweite Dimension `baumeister:erde` im selben Datapack, gleicher dimension_type. Projektion Airocean (Gray,
  Fullers Netz, Eck-Fix auf exaktes Ikosaeder, Maßstab 6 642 732, gedreht: in Hepberg Nord = −z), festgeschrieben in
  `erde/welt.json` (ProjectionChoice, fail-closed). Gewählt 2026-10-28 nach Messung von 5 Projektionen an 56 Orten.
- Höhenbezug der Erdwelt zweistufig (ReliefField): nah Zoom 10, 12 km, 15 % Abschwächung, voll bis 1,6 km
  (garantiert Gipfel unter y 1990); fern Zoom 7, 700 km, 1 %, eigenes 0,25°-Gitter (Täler und Hochflächen
  gleichmäßig). HÖHENMODELL NICHT ÄNDERN, sobald eine Erdwelt erkundet ist: neue Chunks passten nicht mehr zu
  alten (auf dem Testserver nach der Umstellung gesehen: Bezug 0 → ~730 an der Phantom Ranch).
- Weltbau: Kacheln 1024 × 1024 Blöcke, Höhen im Weltbau-Thread, eigenes Rückgängig (TileUndo), nie über den Stapel
  von /bm rückgängig. Kacheln über der Datumsgrenze: zwei Abfragen (GeoBox.parts), zusammengeführt in OverpassMerge
  – nie ein Rechteck mit West > Ost.
- /bm in plugin.yml mit `permission: baumeister.bauen;baumeister.reisen`; das Bauen-Recht prüft
  BaumeisterCommand.onCommand fail-closed für alles außer orte/ort.

- Stadtansicht (2026-10-29): Schnittstelle /baumeister/v1/stadt Version 1 (HostReporter.java/CityRaster.java ↔
  host/src/server/city_api.cpp – gleich halten), Rasterseite höchstens 360 (256-KiB-Grenze des Host-Servers), Antwort
  200 statt 204. Reine Anzeige, fail-open; Raster im StagePrep-Hintergrund, nie im Takt.
- Fortsetzen (2026-10-29): Stand in `stadt/fortsetzen.json` (CityResume, v=1). Fortsetzungspunkt = kleinste unfertige
  Etappe (CityJob.resumeStage) – lieber eine Etappe doppelt prüfen als eine überspringen. Lesen fail-closed:
  unplausible Datei → `.kaputt`, abweichende Etappenzahl → kein Fortsetzen (Etappe k läge woanders). Ganze Karte vorab
  mit genau einer Overpass-Abfrage zugleich (Kette in CityStages.downloadAll), Bau und Vorabladen teilen sich eine
  laufende Abfrage (inflight).
- Zellgröße der Stadtansicht nach Ortsgröße (CityRaster.cellFor, Ziel 8 Mio. Zellen je Auftrag, Host-Grenze 16 Mio.).

- PlotSquared-Schutz (2026-10-30): ChunkLoadGuard ersetzt PlotSquareds RegisteredListener
  `com.plotsquared.bukkit.listener.EntitySpawnListener` für ChunkLoadEvent (gleiche Priorität) und ruft ihn nur, wenn
  `World.isChunkLoaded` wahr ist, sonst einen Tick später. Fail-open mit Warnung, wenn der Handler fehlt; beim
  Abschalten werden die Originale zurückgehängt. Rückgängig: genau eines zur Zeit (BuildService.restoring,
  undoQueue), busy() schließt es ein.

- Reisepunkt einer Stadt (2026-10-30): TravelSpot – Etappe mit den meisten Gebäudeabschnitten (Section.building),
  Schwerpunkt ihrer Gebäude; Bestwert in CityResume.State.travel („reise_gebäude“, fehlt in alten Ständen → 0).
  Gezählt werden Abschnitte, nicht Blöcke.

- Echte Stelle (2026-10-30): `/bm stadt los echt` nimmt die Projektion der Erdwelt (EarthService.projection, sonst
  erde.projektion) – Stadtwelt und Erdwelt teilen damit x/z. Ohne `echt` unverändert unter dem Spieler.
- PLZ (2026-10-30): OsmClient.POSTCODE → Nominatim `postalcode=` (+ `countrycodes`: angegeben, sonst bei 5 Ziffern
  stadt.plz-land, bei 4 Ziffern keins). Name „PLZ Ort“ aus address. Radius-Erkennung im Befehl (2–4 Ziffern am Ende)
  bleibt: „/bm stadt 8001“ ist eine PLZ, „/bm stadt 71735 2000“ PLZ + Radius.

- Zwischenschicht Vorlagen (2026-11-04, „wenn nicht via KI, dann eine Zwischenschicht“): TemplateCatalog +
  TemplateModules (ohne Bukkit) + VanillaTemplates (StructureManager, Server-JAR). Streng vor dem LLM nur bei reinen
  Vorlagen-Wörtern; ein schlichtes „Haus“ ohne Stilwort bleibt beim LLM (Material-/Zusatzwünsche). Locker als
  Rückfall nur bei neuem eigenständigen Bau ohne Gedächtnis (OrderIntent), nach GiveUp oder LLM-Fehler.
  VanillaTemplates.all() bleibt LEER – sonst stünden 189 Namen in jedem Bauprompt. Vorderseite = waagrechter
  jigsaw (jedes Dorfhaus hat genau einen, gemessen tools/vorlagen_probe.py an 322 Vorlagen), Bodenschicht = seine
  Höhe; Luft nur über der Bodenschicht. Gegenproben: Vorderseite fest Nord → TemplateModulesTest rot (3); Häuser mit
  facing right rechts der Straße → TemplateCatalogTest rot; schlichtes Haus streng → rot; Luft unter dem Boden → rot.
- Hörfehler (2026-11-04): SpokenWords.fix am Eingang von BuildService.request, nicht nur in der Spracherkennung –
  Vera reicht das falsche Wort weiter. Muster eng (r + 1–3 Buchstaben + ston/stone/stoun, Redstein, Rotstein).
  Unschärfe in TemplateCatalog nur ab 5 Zeichen und nur bei genau einem Kandidaten. Gegenproben: ohne nearKnown /
  ohne „cold“ → TemplateCatalogTest rot; ohne fix → QuickOrders findet den Vorfall-Satz nicht (SpokenWordsTest).
- Namen (2026-11-04): ItemNames.resolve über das Register des Servers (BukkitBlockCatalog.BLOCKS/ITEMS) –
  genau → Tabelle → Mehrzahl/Präfixe → Wortmenge → deutsch Wort für Wort → Tippfehler; mehrdeutig = kein Treffer.
  BlockRules: Alias redstone → redstone_wire VOR der Registersuche (sonst redstone_block). Gegenproben: Alias aus,
  Registersuche aus, nur genaue Treffer → ItemNamesTest rot. isItem/isBlock brauchen den Server – Tests nehmen die
  Enum-Namen. Kein Nachdenk-Hinweis mehr bei Vera (Ticks + /tick rate). Baubuch-Eintrag hat info (Antwort +
  Bauteil-Beschreibungen, ≤ 900 Zeichen), Vera bekommt sie als [Bau hier].
- Umbau-Schutz (2026-11-04, UpdateGuard): update räumt höchstens 25 % des alten Baus weg (oder < 64 Blöcke), außer
  der Auftrag will wegnehmen/ersetzen (entfern, weg, ohne, statt, kleiner …). Sonst Anbau: alter Bau bleibt, Gedächtnis
  = alter Plan + neue Schritte, eigener Baubuch-Eintrag. Prompt: reine Anbauten liefern nur neue Schritte (passt in
  8 192 Token). Korrekturrunde mit LLM-Fehler → letzten Plan bauen. frosted_ice → packed_ice (BlockRules.UNSTABLE).
  Gegenproben: Schutz aus → UpdateGuardTest rot (2), UNSTABLE aus → ItemNamesTest rot.
- Gemessen 2026-11-04 16:17–16:20 (CoreProtect co_command): Das fehlende Dach der Prozessor-Werkstatt kam von
  `//set 0` des Spielers, nicht vom Plugin; die Reparatur setzte es jeweils zurück (server/dev01/probes/
  baubuch_vergleich.py liest Regionsdateien gegen das Baubuch).
- Maschinenhalle (2026-10-30): vage Maschinen-Aufträge laufen über MachineHall (QuickOrders), nicht über das LLM –
  die Aufstellung nach Bibliotheksmaßen ist Rechnung, die das Modell gemessen nicht kann. Angehängtes ohne Halt lässt
  PlanReview selbst weg (keine Reparaturrunde dafür).

- Straßenreise (2026-10-30): CityIndex (`stadt/staedte.json`, Format wie fortsetzen.json) + StreetFinder in den
  Zwischenspeicher-Kacheln (CityStages.cachedTiles, nie Netz), Projektion wie OsmImport.project. Toleranz 1 Zeichen
  je angefangene 8 (mit 6 traf „Hauptstrasse“ die Hauffstraße). Städte von vor 2026-10-30 stehen nur im Verzeichnis,
  wenn sie nachgetragen wurden.

- Recherche (2026-10-30): ReferenceLookup – Wikipedia-Suche, Titel muss im Auftrag stehen, Wikidata P2048/P2043/
  P2049 (Meter/Fuß, bevorzugter Rang), P1101, P31/P149/P186. Fail-open, Fehler werden NICHT gemerkt (nur Treffer und
  „nichts“). Zielhöhe geht über PlanReview.Context.targetHeight an heightProblem (±20 %, mind. 3 Blöcke).
- Paper-Verträglichkeit: Log-Weiterleitung nur, wenn getLogger() != LOG (unter Paper derselbe Logger). Mannequin über
  MannequinCompat (Reflexion auf API-Methoden): Spigot setDescription(String)/setHideDescription/setPlayerProfile,
  Paper setDescription(Component)/setProfile(ResolvableProfile). Prüfung: Plugin-Quellen gegen paper-api aus dem
  Paper-JAR kompilieren (META-INF/libraries) – vorher 5 Fehler, nachher 0.
- Recherche-Schalter in der App: GET /baumeister/v1/recherche {"erlaubt":…}; ohne Antwort gilt llm.recherche.

- Eigenes Modell (2026-10-31, tools/train/README.md): Lehrset nur aus Plänen, die `PlanJudge.rejection` ohne JEDEN
  Befund bestehen (angenommen im ersten Versuch, keine Nachbesserung durch PlanReview, keine Compiler-Warnung, kein
  Linter-Hinweis) – strenger als das Plugin, sonst lernt das Modell die Fehler, die PlanReview biegt. Geprüft wird auf
  der freien Wiese im Scanfenster von SiteFrame (DatasetSiteTest) mit den Config-Vorgaben (150 000 / 192 / abstand 3 /
  max-überbauen 12). Nutzernachricht = PlanPrompt wie im Plugin. Die Testaufträge `src/test/resources/dataset/
  bench-orders.txt` stehen nie im Lehrset; Liste nur mit Neumessung ändern. Grundmodell Qwen2.5-Coder-1.5B
  (Apache-2.0), 3B nur nicht kommerziell. Verlust nur über Antwort-Token (`logits_to_keep`), zu lange Einträge
  überspringen statt abschneiden. Export Q8_0 über `convert_hf_to_gguf.py` derselben llama.cpp-Fassung (b10900). Kein
  Katalogeintrag in host/deps.lock.json ohne Hugging-Face-repo/revision – bis dahin „Eigene GGUF-Datei“.

- Vorbilder (2026-11-01, Paket `structure`, Bukkit-frei): Bauwerk-Dateien (.schem v1–v3, .litematic, .nbt) liest
  ein eigener NBT-Leser nach den öffentlichen Formatbeschreibungen – aus crd2333/minecraft-processor (ohne
  Lizenzdatei) ist nur die Idee einer gemeinsamen Zwischenform übernommen, kein Code. MCEdit (.schematic mit Zahlen-IDs)
  und Bedrock bewusst nicht. Vorderseite wie bauteile.yml („vorne“, `Orientation(front.opposite())`). Der
  Rückübersetzer (`PlanDecompiler`) schreibt NIE in Luft des Originals; Zylinderform aus `PlanCompiler.inDisc`,
  nicht nachgebaut. Ins Lehrset nur mit vorbilder.json (Datei + Auftrag), höchstens 40 Schritte, ≥ 85 % des
  Originals und strenge Prüfung ohne Befund.
- Lizenz (2026-11-09, vom Nutzer an mich übergeben): GPL-3.0-or-later für das ganze Repo. Warum: Spigot-API und
  WorldEdit stehen selbst unter GPL-3.0, der Host liefert espeak-ng (GPL-3.0-or-later) mit, und für ein
  Community-Projekt bleiben Forks so offen. MIT hätte geschlossene Ableger erlaubt. Text = aktuelle FSF-Fassung
  (https-Links, SHA-256 3972dc97…); Plugin-JAR trägt sie als META-INF/LICENSE, das Host-Setup als LICENSE.txt
  (ISCC bricht ohne ab, Gegenprobe 2026-11-09: ohne exit 2, mit exit 0).

## Gemessen (nicht geraten)
- Rückgängig-Tiefe 50 (2026-11-17): dev01 Heap fest 5 GB (`-Xmx5G`, Xms = Xmx), nach GC 0,48 GB belegt (gc.log
  17:37, 0 Spieler); 87 Bauten aus den Logs der letzten Tage: Median 1 297 Blöcke, p90 5 221, größter 45 802; erlaubt
  150 000 je Plan, 1 000 000 je Bauteil. Live (Tiefe 7, 8 Brücken à 44 Blöcke über die Konsole, `stapel_probe.py`):
  Spigot 1.21.11 und Paper 26.2 je 14/14 mit 2276c35, je 12/14 mit 982214c (keine Logzeile, kein Plattenhinweis).
  Gegenproben 7/7 rot (`gegenprobe_stapel50.py`). Heap (`heap_probe.py`, Paper 26.2, Tiefe 50, 12 Tunnelstücke à
  4 032 Blöcke im Steinriegel, `jcmd GC.class_histogram`): 982214c hält 48 384 CraftBlock (= 12 × 4 032), 2276c35
  20 160 (= 5 × 4 032), Status „7 davon von der Platte“, je 12/12 zurückgenommen. Je Block im Speicher rund 125 Byte
  (CraftBlock 25, CraftBlockData 25, HashMap$Node 32, LinkedHashMap$Entry 40) – 50 × 150 000 Blöcke wären ~0,9 GB.
- Vorfall dev01 2026-11-16 („er hört nicht mehr auf“): „/bm stop“ fiel zweimal in die Pause zwischen zwei Stücken
  (chunk-Vorladen) – kein laufender Bau, das nächste Stück startete. „/bm tunnel 20000 schnell“ wartete hinter dem
  endlosen Mitbau und ging als LLM-Auftrag durch (fill air 49 × 43 × 4 bei y −41…−38 um x −1124 z −283, über dem
  Tunnel im Fels), „/bm tunnel stopp“ ebenfalls ans LLM (Redstone-Block um x −2379 y −38). Rückgängig-Einträge
  rückgängig/bau-…28–32 vorhanden, aber LIFO je Besitzer: #29 (Redstone) und #30 (Hohlraum) liegen unter den
  neueren „tunnel groß“-Stücken.
- Runde E live (Probe `betrieb/probes/tunnel/runde_e_probe.py`, Jar b6649c95…): Spigot 1.21.11 und Paper 26.2 je
  40/40 – Tunnel 300 = 192 + 108 mit 4 Fässern (27/27/27/20 Stapel Bruchstein, Fußhöhe, Öffnung zum Gang), Sockel/
  Streifen/Gurtbogen; Treppe aus der Mitte des 300er-Gangs (je 150 bis zu den Enden) 32 Abschnitte bis SURFACE, Gang
  daneben frei; Tunnel quer: ohne „trotzdem“ BUILT nach 18, „bau weiter 25 schnell trotzdem“ → kreuzung, alter Gang
  frei, Decke zu; Treppe runter bis y −40 (22, DEPTH, Stufe facing=north); Treppe runter an den Gang BUILT nach 28,
  mit „trotzdem“ nicht_hindurch (Plan mit trotzdem 44/FORBIDDEN → neu 28/BUILT), Gang unberührt; Bretter mit
  „trotzdem“ durch; Grundgestein im Gang weg, −64 bleibt; Brücke 40 mit Pfosten/Laternen/Kapitellen; 20000 →
  Absage ohne Auftrag/LLM; stop nach dem ersten Stück, Ablösen, „tunnel stopp“ ohne LLM. Längster Tick 25 ms
  (Spigot) / 35 ms (Paper) über alle Probebauten. Alte Proben mit neuem Aussehen: tunnel_probe 33/33,
  tunnel_gerade_probe 36/36, je beide Server. Erster Lauf 39/40: Erwartung falsch (Schnellgang setzt 192 Abschnitte
  in 3 s, abgelöst wurde erst das zweite Stück).
- K3 live (Bretter vor dem Gang, „tunnel 30 schnell trotzdem“): Jar 7b110825… (Suche nur am ersten abgetragenen
  Abschnitt) 1/3 – keine Kreuzung, Wände quer im alten Gang bei z 9038; Jar b6649c95… (jeder Abschnitt) 3/3. Beide
  Server gleich.
- Gegenproben Runde E: 20/20 rot (`gegenprobe_runde_e.py`: Stopp/zu lang/„hör auf“/„trotzdem“ erkennen und wirken,
  unterste Schicht, Netherdach, Hülle ohne Raum, Zielhöhe, Stufenrichtung, Treppe runter erkennen, Sockel,
  Mittelstreifen, Pfosten, Kapitell, Sockel-Zählung, Fass-Stapel, Gang ohne Ende, Kreuzungssuche, Raum verschoben),
  Nachlauf grün. Gesamtlauf siehe Deploy.
- Tunnel gerade über die Karte live (2026-11-16, Probe `betrieb/probes/tunnel/tunnel_gerade_probe.py` über
  RCON/bauenbei): Paper 26.2 und Spigot 1.21.11 je 36/36 – Berg–Tal–Berg–See–Berg 160 Abschnitte, 3 851 Aktionen in
  2,9 s, längster Tick 26/25 ms; Weiterbauen mit Blick nach Osten folgt dem Gang nach Süden; versetzt stehend bleibt
  die Mitte; „durch den Berg“ THROUGH nach 31; 250 = 192 + 58; 200 Röhre im Freien = 192 + 8 über die Stückgrenze.
  Erster Lauf 29/32: ECHTER Fehler – Weiterbauen hinter dem eigenen Abschluss hielt am ersten Block (BuildJob fragt
  vor jedem Abriss Dig.foreign, das ownFace nicht kannte: „fremdes_im_weg … deepslate_bricks“, 21 von 335 Aktionen);
  behoben mit Run.face, danach grün. Zwei Probe-Erwartungen waren falsch (8110 ist Gurtbogen; Weltrand s. Fallen).
- Stil-Drift beim Weiterbauen: direkt am Ende stehend las TunnelScan nur 2 Abschnitte, ein Gurtbogen gewann
  (Fortsetzung „polierter Tiefenschiefer“ statt Tiefenschieferziegel) – jetzt Stichprobe auch hinter dem Spieler.
- Mit dem neuen Jar alte `tunnel_probe.py` je 33/33. Gegenproben 18/18 rot (G1–G16 `gegenprobe_gerade.py`, G17
  Stichprobe nur ab den Füßen, G18 Kastenmaximum ausgeschlossen), Nachlauf grün; 1 652 Tests grün.
- Tunnel/Treppe/Brücke live (2026-11-16, Probe `tunnel_probe.py` über RCON/bauenbei, flache Welt bei y −60 →
  Tiefenschiefer-Palette): Paper 26.2 (vera-probe) und Spigot 1.21.11 (baumeister-testserver) je 33/33. Tunnel 30
  im Schnellgang 664 Aktionen in 1,7 s, längster Tick 17/26 ms; Lava innen, Wasser in der Wand, Kies über der Decke,
  Höhle darüber, Lavasee voraus – Gang trocken, Kies liegt, 28 Frontabschlüsse kamen als Ziegel zurück in die Kiste
  (591 Bruchstein). Aus der Kiste (Konsole „aus der kiste“) genau: 256→112 Steinziegel, 32→8 Treppen, 4→2 Laternen,
  252 Bruchstein = 12 × 21; wartet auf fehlende Treppen (`bau | material_fehlt`) und läuft nach dem Nachlegen weiter;
  kein Rückgängig-Eintrag. Treppe 36 Stufen in 45 s (Block für Block, 1 je Tick). Brücke 40 mit Pfeilern bis −61.
- Erster Lauf 19/30: Erkennung einer Brücke, deren Deck auf Höhe der Wasseroberfläche liegt, hielt das Wasser
  hinter dem Ende für Deck – Fortsetzung 28 Blöcke weiter hinten am Beckenrand. Behoben (TunnelScan.solid),
  Unit-Test vorher rot (Ende z 70 statt 19), danach grün; Rest des ersten Laufs waren Fehler der Probe (Palette
  unter y 0, `data get` gekürzt).
- Spiel-Vorlagen neu (1736e8e): Wüstentempel Variante 2 live auf ebener Fläche – 9 TNT und 93 Luftzellen unter der
  Oberfläche, nichts über y=80; ohne Bodenversatz 0/0 und 736 Sandstein schwebend. Satz 114/114, 4,7 MB.
- PlacementGate lässt gefährliche Zellen NICHT je Zelle aus, sondern ein ganzes Bauteil weg (Gruppe ≠ 0) – meine
  frühere Notiz war falsch. Seit 1736e8e gehen nur Spiel-Vorlagen (sp_…) Zelle für Zelle (ModuleLibrary.cellByCell).
- Laub nach dem Nachbau (2026-11-15, Paper-Kopie, laub.sh): 8 Baumarten je 2× gebaut, Kopie A sofort per
  `fill … [persistent=true] replace …[persistent=false]` gezählt, Kopie B nach 90 s randomTickSpeed 300 (≈ 2,5 h
  Spielzeit): höchstens 2 von 58–303 Laubblöcken weg (≤ 1,4 %). UndoCheck taugt dafür nicht – es vergleicht nur
  die Blockart und hält Luft für frei. Wüstenbrunnen/Eisstachel brauchen Sand/Schneeblock unter dem Startpunkt.
- Herkunftsspalte der Technik-Wiki-index.tsv: „Technik Wiki (de.minecraft.wiki), …“ – die freie Suche „minecraft“
  trifft sie mit; deshalb das genaue Stichwort „spiel“ für die Spiel-Bauwerke.
- Offizielle deutsche Bauwerksnamen (de.minecraft.wiki/w/Bauwerk, 2026-11-15): Dorf, Mine, Festung, Netherfestung,
  Wüstentempel, Dschungeltempel, Sumpfhütte, Ozeanmonument, Endsiedlung, Iglu, Waldanwesen, Ozeanruine,
  Schiffswrack, Plünderer-Außenposten, Portalruine, Bastionsruine, Antike Stätte, Pfadruine, Prüfungskammern.
  Abweichende eigene Familien tragen sie als Zweitnamen (GameStructures.ALIASES); Festung/Mine bewusst zweiwortig.
- Farmen ernten sich beim Bau selbst, weil der Baumeister mit Nachbar-Updates setzt wie ein Spieler (setLikeABuild,
  Physik an): Ein Beobachter feuert, wenn die Pflanze vor ihm erscheint – von Hand gebaut genauso. Die Farm läuft
  danach normal, die erste Ernte liegt im Trichter. Kein Baufehler; bewusst nicht „behoben“ (Physik aus bräche
  Zäune, Treppen und Redstone, siehe setLikeABuild).
- Spiel-Vorlagen, Inhalt (2026-11-15, Zählprobe über die .nbt): Bastionen bis 1714 Lava, Netherfestung bis 767 000
  Einträge (735 000 Luft), Prüfungskammern bis 983 Wasser, Dörfer 2–42 Wasser, Ozeanmonument 0 Wasser (Becken füllt
  das Spiel nur unter dem Meeresspiegel), antike Stadt nur ~200 Luft (steckt im Fels). Lava und Spawner setzt der
  Baumeister nur mit Stufe „gefährlich“ (PlacementGate lässt sie sonst je Zelle aus). Nachbau: Monument 13270 in
  114 s, Anwesen 40824 in 115 s, Festung 21460 in 119 s, Mine 2720 in 67 s – längster Tick ≤ 30 ms, Lag 0/s. Das
  Monument setzt das Spiel nur mit Ozean ringsum (Tiefsee-Flachwelt baumeister_vorlagen_meer).
- `prüfung_ms` in „bau | los/ende“ zählt das Warten aufs Vorladen mit: antike Stadt in frischem Gelände 6345 ms bei
  prüf_ticks=4 – davon 6118 ms Weltgenerierung von 289 Chunks (JFR: PerlinNoise, Aquifer, SurfaceRules), asynchron,
  kein Tick stockt (längster 41 ms). Kein Fehler; in erkundetem Gelände fällt es weg (2026-11-15).
- TemplateCatalog fing vor a084108 9 von 19 Spiel-Familien ab (Wüstentempel → desert_temple_1 = Tempelhaus aus dem
  Wüstendorf, Dörfer → Straßendorf, antike Stadt → uralte_stadt-Einzelstück, Iglu/Portalruine/Schiffswrack →
  Einzelteile; „… 2“ ebenso, nur „… Variante 2“ kam durch). Gegenprobe live über bauenbei, alt/neu im Commit.
- Spiel-Bauwerke als Vorlagen (2026-11-15, Paper-26.2-Kopie): `/place structure` trägt keinen Strukturstart ein,
  Chunk#getStructures bleibt leer – Umriss deshalb per Blockvergleich gegen die Flachwelt. Standard-Flachwelt
  schneidet unten ab (Wüstentempel 21×15×21), schwebender Boden lässt Fundamente bis zum Grundgestein wachsen
  (21×137×21); Gestein bis y=63 plus Streichen unberührten Untergrunds ergibt 21×25×21 samt Fallgrube. Antike
  Stadt 216×31×190, 54 000 Blöcke nach dem Streichen, Nachbau 53949/53949 in 119 s, längster Tick 29 ms, Lag 0/s.
  Alle 19 Arten je 3× gespeichert (57/57), Iglu teils mit Keller (10×35×7). Höhlen um die antike Stadt fehlen:
  die Geländeanpassung (beard_box) läuft nur in der Weltgenerierung.
- Vorrat fester Sätze auf dev01 (2026-11-08 22:54, Celeron, nice 19, während der Server lief): Vera 221 Sätze in
  259 s (kerstin-low, Tonhöhe 1,25; 23 MB), Baumeister 7 in 16 s (thorsten-medium); kein „Can't keep up“ im Log.
  Jede Stimmwahl im Buch rechnet den Vorrat der neuen Stimme neu (eigener Ordner je Stimme und Tonhöhe).
- Host-Log 2026-11-08 21:26–21:37 (Qwen2.5 14B Q3_K_M, RTX 4070 Ti, Minecraft im Vordergrund): 16–23 Token/s statt
  ~95 (App: Prompt 298 statt 2663 Token/s – Spiel belegt die GPU). Haus aus Fichtenholz in 11,4 s richtig;
  Schematics redstone_fackel_fabrik_1 und gluecksspielmaschine_1 direkt (818/818); Pyramide direkt 227/227.
  „Kanalbrücke“: bridge ohne block + Mauerzeilen bis max_tokens (122 s), Reparatur identisch (84 s), verworfen →
  PlanAutoFix.FORM_BLOCKS ergänzt das Material. Spieler steht bei x 29 999 639 (Weltrand).
- Host-Log des Nutzers 2026-11-08 21:02–21:11 (GPU-PC RTX 4070 Ti, App über das Relay): Qwen2.5 14B Q3_K_M passt
  neben Minecraft (8,7 GB, CUDA, Prompt 4511 Token in 1,8 s vorgewärmt). Mit Llama 3.2 3B (vom Nutzer gewählt, App
  warnt „zu klein“): Bauplan mit 33 × set elytra (Gegenstand → Ersatzblock Stein, gebaut), „Pyramide“ als 66-Block-
  Mauerstreifen, Vera gab auf eine Frage zu Befehlsblöcken 2 Elytren; Spracherkennung „Um ja eine Pürame“. Folgen:
  Pyramide als QuickForm, unbekannte Blöcke in die Reparaturrunde. Empfehlung bleibt Qwen2.5 14B.
- Stufenpyramide (2026-11-08, Prüfer streng, HouseSite): ein Ring je Lage 227 Blöcke, Accept ohne Korrektur und
  Befund; zwei Ringe 395 Blöcke, genauso Accept – meine Annahme „ein Ring schwebt“ war falsch (Probe widerlegt),
  gebaut wird mit einem Ring. Live (fremd-paper, bauenbei): ohne LLM-Anfrage, 227/227 Aktionen in 49 s.
- Akkumulator Süd (2026-11-14, Spigot-Testserver ohne Last): ohne Fix 3/3 FEHLER, Diagnose (AkkuState im
  Scratchpad): Slave-Verstärker Stufe 3 nach dem Bau „an/zu“, Nord „aus/zu“. Fix 6eb5eab (verriegelte Verstärker,
  die an sind, nach dem Bau auf aus): Süd 3/3, N/O/W BESTANDEN. Regression 47 × 4 = 186/188 – rot nur melonenfarm
  Nord (Stängel wuchs beim Bau an; Probe nimmt seitdem attached_*_stem) und xp_farm Süd (Skelett kam in 4 Wartezeiten
  nicht an); `speicher_gelöscht` griff in 188 Bauten genau einmal (akkumulator Süd).
- Rückgängig mit Prüfung (2026-11-14, 5c224a9, xor_gatter, ein Block danach Gold, einer abgebaut): altes JAR
  überschreibt das Gold, neues lässt es stehen (`verändert_behalten=1`), übrige 55 Zellen weg.
- Paper-26.2-Regression (2026-11-14, Stand e1e6a0a = dev01): MachineProbe 47 × 4 = 188/188 BESTANDEN.
- Paper 26.2 neue Maschinen (T-018, 2026-11-21, fremd-paper, Paper 26.2-135, Stand 58287a1): MachineProbe
  akkumulator_mit_loeschen, item_sortierer_3, _3:coal,iron_ingot,gold_ingot, _8 (+ akkumulator_4bit, item_sortierer als
  Vergleich) 24/24; LogicProbe 3dalle 6 × 4 = 24/24; lag je_s=0.0 überall. Kein Paper-eigener Fehler. Nicht gemessen:
  item_sortierer_2 und _4 bis _7, andere Sortenlisten.
- Lag-Wächter live (Paper-Kopie, waechter.jar): Taktgeber 18,3/s; Grenze 10 → „lag | maschine_abgebaut“, 21 Blöcke
  zurück, 0 Verstärker übrig; Grenze 100 → bleibt stehen. Vorlagen dort geladen=263 fehlend=0 (ac4717c).
- Lag-Leerlauf eingebauter Maschinen (Spigot, 8 s): takt 18,3/s, nachtlicht 0,3/s, 45 weitere 0/s. Schematics bisher:
  Bambusfarm bis 30,7/s, Basaltgenerator 24,7/s (Beobachter-Ernte, gewollt). Quellen: Planet Minecraft nein (ToS),
  Technik-Wiki-Datenpakete nein (Befehle je Tick), DuskScorpio CC0 nein (Duper/Wither = Lag/Exploit).
- Paper-26.2-Kopie (2026-11-14, Stand 1d7928e): akkumulator_4bit Süd 2/2 und Nord BESTANDEN, `speicher_gelöscht=1`
  nur bei Süd (wie Spigot); Rückgängig-Probe gold_bleibt=true. Das Paper-Log schreibt Windows-1252 – nach Umlauten
  mit `grep -a` und Platzhalter suchen (`speicher_gel.scht`).
- Client-Fix 6163de8 (/kbau stop) ist noch nicht im Modpaket auf dev01 – kommt mit dem nächsten Modpaket.
- Endprüfung nach P3/P4 auf Spigot 1.21.11 (2026-11-08, endprobe.sh): MachineProbe 47 Maschinen × 4 Richtungen =
  187/188 BESTANDEN; FEHLER nur akkumulator_4bit Süd (Ursache 2026-11-14 gefunden, siehe oben).
  Selbsttest B9: blöcke 118 684 Zustände, 118 488 gleich, 196 abweichend – alle „braucht Halt“: 192 schräge
  Antriebs-/Sensor-/Aktivierungsschienen (ascending_* fällt in der freien Testzelle ab) und 4 soul_fire (nur auf
  Seelensand); daten 25/25; stabil 1140/1154 (Braustand ohne Flaschen, Blitzableiter powered, Brüchiges Eis altert,
  Augenblüte schließt sich – Minecraft gleicht an). 26.2-Kopie läuft noch.
- Vera-Suche (2026-11-08): Erz-Familien (Tiefenschiefer, Nether-Gold) und Ortssuche per locateNearestStructure im
  Umkreis 16 Chunks, Abbruch nach 150 ms – Grundlage: Abenteuer-Suche auf dev01 im Schnitt 93 ms, max 132 ms
  (Netherfestung, 2 Läufe). Gegenproben: ohne Tiefenschiefer, „nächste“ nicht abgeschnitten → rot.
- Technik-Wiki-Welten (2026-11-08, RegionSurvey/RegionPlot, `C:\swb\technik-wiki`): Boden bis y=25 (Sandstein, Stein,
  Grundgestein), je Technik ein Feld im 32er-Raster mit Schilderhaus (≤ 9 × 9, Höhe ≤ 4, Name als Wandschild auf
  y=28), Varianten als getrennte Inseln. Eingelassene Feldböden unter y=26 verbinden ganze Feldreihen (Insel 704 × 832)
  – Inseln deshalb nur aus Blöcken ab y=26, Darunterliegendes je Spalte nachgeholt. Ergebnis: 804 Bauten in 196
  Techniken (Redstone 382, Mechanik 331, Schienen 91), 318 mit Befehlsblöcken weggelassen, 350 ohne Namensschild.
- Frischer Paper 26.2-135, nur Baumeister, level-name=meinewelt (2026-11-08, `C:\swb\fremd-paper`): Start ohne
  ERROR vom Plugin; dreimal `abenteuer | welt_fehlt` (feste world-Listen), Tagesaufgaben zählten nirgends, `/mods`
  nannte „26.2.build.135“ (Bukkit-Fassung auf Paper 26.x: `26.2.build.135-…`), das Hilfebuch verlinkte Farmwelt,
  Plotwelt und /plot. Alles behoben (cf5bc99). INFO ohne Handlungsbedarf: `bauteile | nur_eingebaute` ohne
  WorldEdit, `erde | welt_fehlt`, `begleiter | host_nicht_erreichbar`.
- Eisenfarm rund um die Uhr (2026-11-08, P4, MachineProbe mit echtem Schlaf „#schlaf“): Wer den Zombie im Stehen
  sieht, bleibt in Panik und legt sich nie hin (90 s Nacht, 0/3 Betten); Zombie weg → alle drei schlafen nach 1 s.
  Schlafende sinken auf die Bettoberkante (+0,5625, Auge 0,7625 über dem Bett). Jetzt Nachtzombie hinter einem
  Schlitz nur für Liegende, Tagzombie hinter Eisenfalltüren mit umgekehrten Tageslichtsensoren. Zeit bis Schlaf /
  Golem nachts / Golem am Tag / wieder Schlaf / Golem nachts – Spigot 1.21.11: N 5,3/13,1/33,5/1,4/35,3 s,
  O 3,9/43,9/35,9/1,1/80,0, S 4,8/26,2/32,5/9,8/28,2, W 4,4/19,1/37,5/0,9/48,5; 26.2-Kopie: N 4,4/23,5/30,6/1,7/47,1,
  O 3,6/26,3/30,2/1,7/47,1, S 4,2/55,0/33,1/1,4/34,8, W 5,0/24,9/29,6/1,6/48,0 – alle BESTANDEN. Gegenproben: ohne
  Sensoren „SCHLAF FEHLT: 0/3“ (beide Server), ohne Boden unter dem Nachtzombie nachts 240 s kein Golem.
- XP-Farm-Bahnen (2026-11-08, P4, XpTrace je 20 Zombies, Pos/Leben/Fallweite ~10/s): kein Zombie starb am Fall.
  Ausfälle vorher: trockene Bahn nach dem Bau Richtung Süd (2/20) und Sprung aus dem 1 tiefen Kanal (Nord, West je
  2/20). Nachher (Fläche 2 über dem Wasser, Quellen eine Lage höher in Nischen) je Richtung 20/0/0, Ankunft median
  6,1 s, längste 18,2 s; 26.2-Kopie 40/0/0. Server-Gegenprobe: alte Farm 6 von 80 hängen, neue 0 von 80.
- Blockzustände aus dem Datengenerator (2026-11-08, `probes/blockstates/`): 26.2 hat 1 196 Blöcke mit 32 366
  Zuständen, 1.21.11 1 166 mit 29 671; kein Zustand von 1.21.11 fehlt in 26.2. Alle 62 037 kommen unverändert durch
  BlockSpec (BlockStatesRoundTripTest). Eigenschaften stehen nicht immer alphabetisch (276 Zustände, z. B. Truhe).
- Prompt-Abschnitt „BLOCK DATA“: +317 Token je Auftrag (4 189 → 4 506, Qwen2-Vokabular).
- Bedienhinweise live (2026-11-08, Spigot-Testserver, `bm bauenbei … bau mir eine Bambusfarm`): alte Jar antwortet
  „Ich baue dir das Bauteil 'bambusfarm'.“, nach `bau | ende` nichts; neue Jar „Ich baue dir die Bambusfarm. Die
  Anleitung kommt, sobald alles steht.“, nach `bau | ende` `bau | anleitung | bauteile=bambusfarm zeilen=1`, Eisenfarm
  3 Zeilen. Gegenprobe Unit-Tests (alte Truhen, alte Antwort, falsche Lage im Text): 7 rot, mit Änderung alle grün.
- XP-Farm mit Truhen vor der Scheibe (2026-11-08, MachineProbe mit Beuteschritt): ein Diamant auf dem Teppich liegt nach
  786–1052 ms in der Truhe (Spigot n/e/s/w; 26.2-Kopie n/e/s/w 789–794 ms), ohne den Trichter unter dem Glas
  (ohne=9,0,8) bleibt sie leer – FEHLER. 26.2-Kopie 4/4 bestanden, Spigot 4/4 mit einer Wiederholung in Süd (Zombie, s.
  Offen).
- Farmen 2026-11-07 (Spigot 1.21.11, MachineProbe, je mit Gegenprobe „ohne=…“, die FEHLER meldet): weizenfarm in
  allen 4 Richtungen – Ernte in der Truhe nach 3,4–30,6 s (Trichterkette 2,5 Gegenstände/s), Wasser nach 2,1 s
  zurück, Ackerland bleibt bei random_tick_speed 1000 auf Feuchte 7; ohne die wassergefüllte Stufe erreicht die
  Spülflut (4,2,2) gar nicht und der Acker trocknet. eisenfarm (Teich, Probe bei Tag, 2026-11-08, überholt durch die Fassung Tag+Nacht oben): 26.2-Kopie
  4/4 Richtungen, Spigot 3/4 (Süd: Golem erst nach über 150 s, dann in der Grube tot – warum so spät, offen);
  Golem nach 2–26 s in der Grube, tot und Eisen in der Truhe rund 28 s später; ohne Lagerfeuer lebt er nach 90 s
  noch. bruchsteingenerator ohne
  Wasserquelle: kein Bruchstein; huehnerfarm ohne mittleren Trichter: kein Ei; kuerbis-/melonenfarm ohne Beobachter:
  Frucht bleibt; bambusfarm ohne Beobachter, kaktusfarm ohne Zaun: nichts bricht.
- Serverliste dev01 2026-11-07 (server/dev01/probes/serverliste_probe.py): Paper 26.2, Protokoll 776, MOTD mit „✦“
  und „•“ kommt an, Bild 64 × 64.
- Folgen 3 (2026-11-07, FollowProbe, drei Fassungen jetzt/neu/Spieler): Ziel = Spieler (goalPlayer) überall am besten.
  Natur-Welt Abstand Mittel 4,2/6,5/4,9/5,9/5,9 → neu 2,8/4,8/3,9/4,6/5,4 → Spieler 2,4/3,8/3,5/3,8/3,7, Max
  bis 5,4/7,0; flach 11 Strecken weg-vom-Spieler 20/15/8. Im Spiel 18:00–18:02 vorher: „ziel_abstand=0.0“ bei 5–6
  Blöcken (Ziel lag bei Vera). CompanionFollow.goal bleibt nur als Vergleich in der Probe.
- Stimme im Spiel (Log 2026-11-07 17:58–18:14): Begrüßung direkt nach dem Beitreten 443 ms spät (≈ 240 ms echt,
  200 ms waren Vorlauf, falsch mitgezählt) → Kodierer jetzt vorgewärmt; alle späteren Sätze 201–202 ms mit genau 9
  „späten“ Rahmen = nur Vorlauf, echte Verspätung ≈ 0. Zählweise seitdem ab Strombeginn.
- Bildschirm (Spigot-Testserver 2026-11-07, RCON): Rahmen mit Karte, unsichtbar, fest, Blick zum Betrachter;
  Rückwand gesetzt; GIF 12 Bilder geladen; nach Neustart 4 Bildschirme geladen. Fehler gefunden: zweiter Bildschirm
  an derselben Stelle wurde gebaut (Rahmen sind Entities) → Prüfung auf Hanging + Überschneidung, danach abgelehnt.
  Im Spiel nicht angesehen (Zeichnen nur mit Zuschauer).
- Stimme im Voice-Chat (2026-11-07, Ruckeln von außen gemeldet): App liefert schnell (von dev01
  gemessen 0,04/0,08/0,24 s für 0,7/1,5/4,5 s Sprache, 1,8 MB/s durch den Tunnel). dev01-GC in 9 min: 40 Pausen,
  Median 39 ms, max 132 ms. Simple Voice Chat 1.21.11 (Quelle gelesen): Server-Player ohne Vorlauf, Endmarke je
  Satz; Client 32 OpenAL-Puffer, bei leerem Puffer erst output_buffer_size=5 Stille-Rahmen. → AudioPacing (Vorlauf
  10, fester Plan, Neubeginn ab 300 ms Rückstand). Im Spiel nicht nachgehört; Log „stimme_verspätet“ ab 100 ms.
- Folgen im Spiel (Log 2026-11-07 17:15, Diagnose „weg_vom_spieler“): Vera 48 Blöcke weg, Ziel-Abstand 0,0, Spur 16
  Punkte – Spur schrieb nicht mehr mit, Ziel = alter Spur-Punkt am Start. Ursache Stand-Erkennung (nur Fußmitte,
  isSolid) bzw. Boot; behoben mit isOnGround + Ecken, Trail.fresh, Teleport nach Abstand zum Spieler.
- Umlaute: 27 statische Muster mit \b neben Umlauten ohne (?U) (UmlautBoundaryTest). Abdeckung ohne Modell auf
  178 echten Sätzen unverändert 72 % – die Fälle kommen dort nicht vor; Tagebuch und Grundstück per Test belegt.
- Folgen in Höhlen (2026-11-07, probes/mannequin/FollowProbe, Paper 1.21.11, Geist-Spieler 0,216 Block/Tick):
  altes Ziel „schräg hinter dem Spieler“ im Gang mit Parallelgang 120 Ticks im Stein, 1 Teleport, bis 22,8 Blöcke
  zurück; CompanionFollow (Spur, freie Sicht, Teleport auf die Spur) 0 / 0 / 6,8. Wendeltreppe Abstand Mittel 6,2 →
  4,7; Schluchtsteg 59 Schwebe-Ticks → 0; offenes Feld unverändert (4,2 / 4,3).
- Spracherkennung auf dev01 (2026-11-07, Celeron J4125, 4 Kerne ohne AVX, 1,2 GB frei, whisper.cpp 927cfce3 mit
  -march=native, 2 Threads, nice 19): large-v3-turbo-q5_0 für 3,3 s Deutsch 920 s (Encoder 915 s, Spitze 728 MB),
  Text fehlerfrei; mit Audiofenster -ac 256 153 s (652 MB), Text fehlerfrei. Turbo auf dev01 ausgeschlossen.
  Kleinere Modelle (tiny/base) nicht gemessen – kein Modell auf dem Rechner, Download nicht freigegeben.
  Hochgerechnet (Encodergröße, nicht gemessen): base ~5 s, tiny ~2 s je Satz mit -ac 256.
- Vera ohne Modell (2026-11-07, probes/offline/Cover, 171 Sätze aus dem dev01-Log): vorher 33 %, nachher 74 %.
  Rest: Verhörer, Folgesätze, Gestaltungswünsche. Antworten ohne Modell laufen durch CompanionService.perform –
  dieselben Rechte und Fair-Play-Filter wie Modellantworten.
- 7-Segment-Anzeige (2026-11-07): Decoder mit 35 Termen rechnet richtig (DisplayProbe, alle Leitungen). Falle: zu
  viele Verstärker = Verzögerung (2 Ticks je Stück) – Anzeige hinkte einen Wert hinterher. Echter Bau MachineProbe
  in allen 4 Richtungen je 432/432 (Spigot-Testserver C:\swb\baumeister-testserver, RCON 25598).
- Redstone per Plugin schalten (2026-11-06, probes/redstone/LabProbe): Ein umgelegter Hebel weckt nicht die Nachbarn
  seines Blocks – ohne Anstupsen 1/4 Gatter richtig, mit 4/4. Ablesen erst nach 10 Ticks, dann 6 still (Lampe geht
  4 Ticks verzögert aus). Vor Schritt 0 auffrischen. Umbauten (FixProbe): beide Lampen danach an.
- Wegsuche (2026-11-06, probes/mannequin/VeraPathProbe, Paper): mit CompanionPath auf 7 Parcours (Mauer, U-Falle,
  Höhle 1×2 mit Kurven, Treppe 1 breit, Steg über Grube, Leiter, Stufen) 0 Teleports, 0 Stürze; geradeaus 4 Teleports
  und 5 Stürze vom Steg. Suche 0,03–1,7 ms. Posen: alle 5 bleiben mit Paper setPose(Pose, true).
- Simple Voice Chat 2.6.27 (javap; REA hat für Java-Archive keinen Anbieter): registerVolumeCategory,
  AudioChannel.setCategory, AudioPlayer.stopPlaying, createEncoder(OpusEncoderMode) vorhanden, auch in API 2.6.0.
- Spielmodus-Wechsel gingen verloren (Log 2026-11-05 19:07–20:06): nur im Begleiter-Takt geprüft, Zustand bei
  Abenteuer-Start gelöscht. Jetzt PlayerGameModeChangeEvent + ModeWatch.
- Redstone-Zustände (2026-11-06, Paper 1.21.11-132, probes/redstone, Testdaten in src/test/resources/redstone): Verstärker/Komparator lesen auf der facing-Seite (javap DiodeBlock + Lauf: facing=west hinter Hebel im Westen an), Staub 15 → 0 am 16. Block, Wandfackel hängt am Block gegenüber facing, seitlich gesperrt = locked=true, Beobachter gibt hinten aus. RedstoneReader liest nur Zustände, simuliert nichts.
- Mannequin-Physik (2026-11-06, Paper 1.21.11-132, probes/mannequin): nur `immovable` sperrt die Physik. Mit
  Schwerkraft und beweglich: fällt 5 Blöcke in 15 Ticks, `setVelocity` 0,25/Tick = 0,25 Block/Tick, Stufe 0,6 von
  selbst, voller Block nur mit Sprung 0,42, Leiter ja, im Wasser sinkt sie (+0,04/Tick hält sie oben). Teleport-
  Schritte (vorher) gehen durch Mauern. CompanionWalk-Parcours 15 Blöcke: 57 Ticks (flach 52), 4 hohe Mauer: nach
  40 Ticks Teleport. Seitdem läuft Vera; schweben nur noch, wenn der Spieler fliegt oder das Ziel keinen Boden hat.
- Bauprompt (2026-11-04): 7451 Token bei „ein Haus“ (14B, 8192 Kontext → 741 Token für den Plan, Prompt 6,6 s).
  Modulbibliothek allein ~6100 Zeichen/~1850 Token. Seit PlanPrompt.wantsMachineDetails ohne Redstone-Wort nur
  Name+Größe: Haus 1128 statt 6555 Zeichen ohne Scan (Gegenprobe: immer Details → PlanPromptTest rot).
- Sydney Höhenmodell (2026-11-01, TerrariumGridProbe, Zoom 15 aus dem Cache von dev01, 845 Proben −33,850…−33,885 /
  151,188…151,225): kein Wert genau 0, Hafenbecken 0–5 m mit Streuung unter 0, Cockle Bay 11,71 m. Die Wasserregeln
  (WaterTable) machen daraus Land. Kachel 15064/5262 (RegionRenderProbe, 23 Regionen): Wasser 0,6 %, Darling Harbour
  grüne Böschung.
- Küste live (2026-11-01, Kachel 15067/5258 Rose Bay, Fassung 135f9a6): 10 von 16 Etappen mit Meer (bis 65.536
  Zellen je Etappe), meer_hoch 0, übernommen=true, längster Tick 129 ms, 27.765 Blöcke/s. RegionRenderProbe
  (docs/messungen/sydney-rose-bay-mit-kueste-2026-11-01.png): Wasser 49,1 % (Innenstadt vorher 0,6 %), aber
  23 Landproben an der Uferlinie in Gruben bis y −200 (Gras, Sand, Straßen ganz unten) → FILL_MARK.
- Sydney in OSM (2026-11-01, Overpass, Ausschnitt −33,866…−33,855 / 151,205…151,222): KEINE natural=coastline, der
  Hafen ist die Relation „Port Jackson“ (15522136) bzw. „Sydney Harbour“ (1252425), natural=water, water=harbour.
  Die erste Küsten-Fassung (f4bbad1) setzte deshalb an Kachel 15064/5261 keine einzige Meereszelle (kein Log
  „weltbau | küste“, 0× coastline in allen neuen Kartenkacheln). Kachel lief mit längstem Tick 229 ms.
- ChunkHolder auf dev01 (2026-11-01, Sydney-Kachel 15064/5262, 12,9 Mio. Blöcke in 8 min): längster Tick 343 ms,
  7 Ticks über 50 ms, keine Lücke über 1 s, chunks_async 3213, längstes Ticket 0 ms (vorher 3599), 24.698 Blöcke/s.
- dev01 Weltbau nach GC-Tuning und CPU-Regler (2026-11-01 12:37–12:39, Kachel 366/6585, 1.174.150 Blöcke, 2 min):
  9.062 Blöcke/s, längster Tick 3792 ms, 431 Ticks über 50 ms, 48 über 200 ms, 0 Full GC, 0 Evacuation Failure,
  längste GC-Pause 308 ms. Dieselbe Stelle vorher (11:43, Zählung 6582, 650.345 Blöcke – anderer Inhalt, also nur
  grob vergleichbar): 5.228 Blöcke/s, 4229 ms, 515 Ticks über 50 ms. Die verbliebenen Sekunden-Ticks kamen aus
  addPluginChunkTicket (längstes Ticket 3599 ms) – synchrones Erzeugen im Hauptthread, nicht aus der GC.
- dev01 CPU-Regler (2026-11-01, server/dev01/probes/tick_burst_probe.py, je 2 Läufe): Tick-artiger Burst alle 50 ms
  schedutil 28,2/28,4 ms, performance 11,7/11,7 ms. Seit 12:06 performance dauerhaft (cpu-performance.service).
- dev01 Speicher beim Weltbau (gc.log 2026-11-01): 76 Full GC (1,4–3,3 s) an einem Tag. Muster: Old Gen wächst je
  Young-GC um ~120 MB, Markierung mit 1 Thread (Concurrent Workers: 1) wird nicht fertig → Evacuation Failure → Full
  GC 2,8 s. Der längste Tick des Weltbaus (7809 ms, Kachel 365) fällt in diese Pausen. Kachel 365 vorher: 73.301
  Blöcke in 94 s, 223 Ticks über 50 ms.
- dev01 GC-Pausen 2026-11-01 11:56–19:43 (alle gc.log*, 1900 Pausen, Weltbau Sydney): Young Normal p50 96/p95 213 ms,
  Mixed p50 156/p95 269/max 529 ms; 868 Pausen über 100 ms, 216 über 200 ms. Eden ~248 Regionen à 8 MB (~2 GB,
  G1NewSizePercent=30/Max 40). TROTZ ConcGCThreads=2 zwei Full GC: 13:59:49 2241 ms (5120→2111 MB, Kachel
  15063/5262, deren längste Lücke 2474 ms) und 15:00:34 1201 ms (5111→1110 MB). Die Aussage „0 Full GC seit
  ConcGCThreads=2“ galt nur für Kachel 366/6585. Bereits in Ordnung: THP always (6,05 GB AnonHugePages im Java-Prozess),
  Swap 0, PSI 0, Paper io-/worker-threads Vorgabe, delay-chunk-unloads-by 10s, kein LaggRemover-JAR mehr in plugins/.
  Multiverse keep-spawn-in-memory=true für plotwelt, test, world, world_nether, world_the_end (Kosten nicht gemessen).
- Client-Spieltest nach der Workflow-Runde (Weltwechsel, Stopp-Ticket, DeferLedger, Pausen-Uhr, 2026-11-01): Befehl 6 s,
  Spieler 105 s, Halle 72 s, Rückgängig 70 s, 1185/1185 und 811/811, 0 ausgelassen – unverändert.
- Stadtansicht-Raster je Etappe (StagePrepRasterProbe, Bad Kreuznach 120 m, 225 413 Blöcke, Laptop, 30 Läufe,
  2026-11-01): ohne 47,5 ms, mit 53,7 ms (Zelle 1) – ~6 ms im Hintergrund-Pool, nicht auf dem Server-Thread. Deshalb
  bewusst NICHT abgeschaltet, wenn der Host fehlt.
- Stadt- und Weltbau rufen nie das LLM (nur Overpass/Nominatim und Höhenkacheln); die einzige Kopplung war das Warten
  auf BuildService.busy() mit „thinking“ – seit 2026-11-01 buildingInWorld().
- Client-Spieltest nach der Aktionsleisten-Anzeige (2026-11-01): Befehl 6 s, Spieler 105 s, Halle 74 s,
  Rückgängig 70 s – gleich wie vorher, 1185/1185 und 811/811.

- Vorbilder an den WorldEdit-Schematics der Serverkopie (`lokale Serverkopie 1.21.11`, 24 Dateien, nur gelesen,
  2026-11-01): 13 Sponge v2 gelesen, 10 im alten MCEdit-Format abgelehnt, 1 zu groß (327×140×327). Rückbau
  verlustfrei bei allen 11 rückübersetzbaren exakt (100 %, 0 Blöcke zu viel), aber 121–2054 Schritte (Turm 548,
  Wohnbauten ~455). Mit 40 Schritten trifft der Plan 36–92 % (Wohnbauten 91–92 %, Kanone 89 %, Turm 70 %).
  Keine Datei besteht die strenge Prüfung – auch die exakten Originale nicht: frei stehende Türen, Säulen und Glas
  ohne Halt (Minecraft hat keine Schwerkraft, der Linter prüft Halt). Im Lehrset deshalb 0 Vorbilder aus diesen
  Dateien. Zylinder senkten den Turm von 1086 auf 548 Schritte; 8er-Nachbarschaft nur für runde Formen (für Wände
  machte sie sorter.schem von 862 auf 992 Schritte schlechter). Umbenennungen grass/grass_path/chain kosteten vor
  `BlockStates.upgrade` 32 Blöcke in vier Dateien.

- Eigenes Modell, Grundlinie 2026-10-31/11-01 (PlanBench, 24 Testaufträge, Host-Bau `bh-runde5` vom 2026-10-29,
  Qwen2.5 7B Instruct Q4_K_M auf der Intel-iGPU über Vulkan, Kontext 8192, eine Reparaturrunde): 20/24 sofort
  angenommen, 1 nach Reparatur (Haus mit Pool), 3 verworfen (Wachturm und Kirche: Tür nicht in der Wand, auch nach der
  Reparatur; Windmühle: Reparaturrunde passte nicht mehr in 8192 Token – Kontext, kein Planfehler). Nur 6/24 sauber
  nach Lehrset-Maßstab: Die Annahme lebt von PlanReview. 8/24 Antworten liefen bis an die Kappung (~1950 Token,
  je ~410 s – Dorf, Burg mit Bäumen, zwei Maschinen, Brunnen, Schmiede, Stall, Marktstand, Bauernhof) und gingen nur
  über die Syntaxreparatur durch. 5311 s gesamt, 22 261 Token, 4,2 Token/s. Ergebnis: `target/bench/` (nicht im Repo).
- Schlanker Prompt (T-015, 2026-11-21, PlanBench vorher/nachher gleiche Startzeile): Tokens 22 416 → 15 032 (−33 %),
  Zeit 4 968 s → 3 108 s (−37 %), Kappungen 2 → 0, verworfen 3 → 4, sauber 10 → 9. MODULE LIBRARY und TECHNICAL
  RULES hängen an `PlanPrompt.offersModules` (Maschinenwunsch ODER Modulname ODER MACHINE_WORDS ohne PLAIN_WORDS
  tür/tor/licht/lampe). Falle: Wer ein technisches Wort aus MACHINE_WORDS nimmt oder die Bedingung auf machineWanted
  verengt, nimmt Aufträgen wie „Pistontür“, „Lampe mit Tageslichtsensor“, „Zugbrücke“ die Bauteile (PlanPromptTest).
  Zu viel Liste durch Teilwörter (Glockenturm, Kornspeicher, Mausefalle) ist bewusst hingenommen – so war es vorher
  immer. Türen und Modulmantel seit T-021 gerichtet (siehe unten).
- Gegenprobe Lehrset-Filter: Generator mit absichtlich schwebenden Laternen – Filter an: Tests grün, 23 Pläne
  verworfen („ohne Halt weggelassen“); Filter aus: `everyGeneratedPlanPassesStrictReview` rot; zurückgestellt: grün.
  Richter selbst: eingemauertes Wasser, Klotz in der Luft, Hochhaus ohne skyscraper, Vollzylinder → abgelehnt, Turm
  aus dem System-Prompt → sauber (DatasetGeneratorTest).
- Training, CPU-Probe 2026-10-31 (torch 2.11.0+cpu, transformers 4.57.6, peft 0.21.2, Python 3.14.7, Zufallsmodell
  2 Schichten, Tokenizer aus der Qwen2.5-7B-GGUF): 6 Schritte, Logits für alle ~6500 Positionen 208 s; nur für die
  Antwort (`logits_to_keep`) 14 s, Verlust je Schritt gleich, Selbsttest 11,942070 gegen 11,942071. Export über
  `convert_hf_to_gguf.py` b10900 (F16, 25,5 MB); die Host-App lädt die GGUF und beantwortet einen Testauftrag mit
  grammatikgültigem JSON (181,9 Token/s, verworfen – Zufallsgewichte). Nicht geprüft: CUDA, bitsandbytes, echtes
  Training, Rechenzeit auf dem GPU-PC.

- Minikarte, Paper-Testkopie 2026-10-31 (`/bm kartenbild` per RCON, Gebiete per forceload geladen): Stadt in der
  stadtwelt (23725/16) bei Zoom 1 und 4 sowie Plotwelt 0/0 als PNG angesehen – Straßen, Dächer mit Firstschattierung,
  Plotraster und beanspruchter Plot erkennbar. Lesen 36–49 µs je Chunk (erster Lauf mit JIT 276 µs), Zusammensetzen
  3,6–6 ms je Bild (erster Lauf 30 ms), 1 122 Chunks für Zoom 4 in 48 ms. Nicht geprüft: render() mit echtem Spieler
  (Marker, Blickrichtung, Plotrahmen, Auffrischen) – dafür braucht es einen Client.
- Minikarte Vorab-Scan, Testkopie 2026-11-01 (Entwickler-PC, nicht dev01): 287 Regionsdateien (stadtwelt 224,
  testwelt 59, plotwelt 4), 72 599 fertige Chunks in 7,2 s auf dem Scan-Thread; Server-Thread währenddessen 4 % eines
  Kerns. Übersicht danach 726 KB auf der Platte. Bilder ohne einen geladenen Chunk: Zoom 16 die ganze Stadt, Zoom 64
  zwei Städte (die zweite ~6 km westlich, nie besucht). Auf dev01 gemessen: 992 Dateien in 79,8 s (Deploy-Eintrag
  2026-11-01 10:32).
- Minikarte Ausbau, Testkopie 2026-10-31/11-01: Straßen weiß (Belag auf Bodenhöhe ohne Hohlraum darunter), Karte
  gedreht (135°) und Übersicht angesehen. Übersicht: 1 258 Chunks beim forceload mitgeschrieben, beim Freigeben noch
  einmal (2 516), 4 Regionsdateien (192 B bis 4,7 KB, gzip); nach Neustart ohne geladenen Chunk Bilder bei 8, 32 und
  64 Blöcken je Punkt nur aus der Platte, auch gedreht. Zusammensetzen 4,5–18 ms je Bild (8: 47 ms im ersten Lauf).
  Ganz herausgezoomt berührt ein Bild 256–312 Regionen (Eckentest); der Umkreis der Drehung wären über 500 gewesen.
- Minikarte, stufenloser Zoom, Testkopie 2026-10-31: Bilder bei 0,5 (Stadt vergrößert, deckt sich mit der Mitte des
  1:1-Bilds), 1,6 (mehr Stadt, 196 Chunks, 9,5 ms) und 0,25 (Plot-Kreuzung, Ränder auf den Kanten) angesehen; Zoom 9
  abgewiesen. Pixel und Marker rechnen beide mit Mitten – mit Pixelanfängen stand bei 0,512 Block −299 auf −300
  (MinimapImageTest, Gegenprobe).

- Alle dev01-Logs bis 2026-10-31 (nur gelesen, logscan.sh): 136 LLM-Anfragen, 55 Pläne verworfen, 66
  Reparaturrunden, 13× `llm | fehler`. Verwerfgründe: 26× Modul ohne erkannten Maschinenwunsch (kombischloss,
  klingel – meist das richtige Modul), 13× Referenzhöhe (WTC 130/60/45 statt 184, Everest 1), 9× Grundriss
  (skyscraper unter 5×5, house 32×1), 4× schwebende Teile (93–712 Blöcke), 2× knapp zu groß (13×201×5, 35×3×241 bei
  192), 1× Vollklotz. Das LLM wiederholte den Fehler in der Reparaturrunde fast immer. `llm | fehler`: 7×
  ConnectException, 3× HTTP 503, 2× Verbindung ohne Antwort, 1× HTTP 400. Daraus PlanAutoFix und die Wiederholversuche
  im LlmClient; je Log-Fall ein Test in PlanAutoFixTest. Gegenproben: alte Prüfkette → 9/9 rot, neue → grün;
  LlmClient ohne Wiederholung → 3 Tests rot; Host ohne Einlesen/Zählen der Korrekturen → server- und gui-Test rot.
  Nicht gemessen: wie viele der früher verworfenen Aufträge jetzt im Spiel gut aussehen – das zeigt erst der Betrieb.

- Prozessor-Bausteine, MachineProbe auf der Paper-Testkopie (testwelt, y 120), NORTH und EAST, 2026-10-31:
  d_speicher, register_4bit, alu_1bit (32 Belegungen × 2 Ausgänge), vergleicher_2bit, addierer_2bit – alle
  BESTANDEN. Erster Lauf: register_4bit Schritt 5 FEHLER (Takt aus und Daten im selben Schritt, Q1/Q3 verloren) –
  Haltezeit; der Ablauf trennt jetzt Takt und Daten, danach BESTANDEN. Prozessor-Werkstatt („bau mir einen Prozessor
  aus redstone“): 26 073/26 073 in 111,9 s, längster Tick 113 ms.
- Prozessor Stufe 2, MachineProbe Paper-Testkopie, NORTH und EAST, 2026-10-31: addierer_4bit 13 Stichproben
  BESTANDEN; Übertrag durch alle vier Stufen bis 2,6 s (stimmt nach 2,3–2,6 s, inkl. RCON) – deshalb Wartezeit 5 s für
  Bauteile breiter als 57, mit 1,2 s wäre die Probe rot. akkumulator_4bit 16 Schritte (3+3, +5, +5 → 0 Überlauf, 15,
  +1 → 0) BESTANDEN, neuer Wert nach 0,7–1,3 s. Gegenproben NORTH: Master-Riegel delay 1 statt 3 → trotzdem BESTANDEN
  (delay 3 ist nur Reserve); Master-Riegel weg → FEHLER, AKKU rechnet bei T an weiter (Schritt 2: nach 0,7 s 3, nach
  5 s 10).

- Client-Mod, Spieltest im echten Client (Einzelspieler, Kreativ, 2026-10-31): Hochhaus 2 Stockwerke per Befehl 6 s,
  wie ein Spieler 105 s (vorher mit geradem Anflug 52 s, aber eingesperrt unter Dächern); Maschinenhalle mit
  Übernahme per „W“ 74 s, 811/811; Rückgängig 70 s. Fehler, die der Test (rot) gefunden hat, je behoben und grün:
  Befehle ohne OP (1185/2364 falsch), Figur unter dem Dach (8 min Hänger), Trichter öffnet Inventar (Schleichen),
  Standplatz in der Zielschicht (135 Lücken), Reichweite Zellmitte/Auge (13 Blöcke in 10 s), berechnete Zustände
  (36 „falsch“), Wandfackel als Stehfackel, Behälter vor ihrem Nachbarn (3 Versuche im selben Tick).

- Plot-Modus, Paper-Testkopie mit PlotSquared-Plotwelt (Standard: Plot 42, Straße 7, Höhe 62), 2026-10-31:
  Plot 1;1 = Welt 4..45 × 4..45. Hochhaus 2 Stockwerke „auf dem plot“: mittig verschoben 13/13, 8 652 Aktionen in
  14,3 s. Bunker auf Plot 2;1 mit Blick nach Osten: 13 030 Aktionen in 54,8 s. Fort Knox auf 1;2: „passt nicht“ (44 ×
  53). Hochhaus noch einmal auf 1;1: Überbau-Schutz (238 Blöcke). Blockproben per execute if block, alle 7 an der
  berechneten Weltstelle (Hecke in beiden Ecken, Eingang, Weg, Fassade, Bunkerleiter 13 unter dem Plot).
- Schnellgang mit Laufen: Bunker (6 302 Aktionen, 3 Arbeiter) 79,1 s statt 330,9 s, längster Tick 77 statt 352 ms.
- 752 Plugin-Tests grün; Gegenproben rot/grün: Plotecke, Plotgröße im Prüfer, WALK_FACTOR, Zuordnung im Verlauf (Host).

- Bunker/Fort Knox/Rechner, Paper-Testkopie mit echtem Gelände (normalwelt, Spawn −336/69/240), 2026-10-30:
  Fort Knox 28 191/28 191 Aktionen (11 907 gesetzt, Rest war schon Luft) in 52,9 s, Schnellgang 12/Tick, längster
  Tick 99 ms; Bunker (2 Ebenen 21 × 21) 6 302/6 302 in 330,9 s – Block für Block, weil die Schätzung (105 s) unter
  der Zieldauer lag, die Helfer unter der Erde aber laufen müssen; Taschenrechner 2 545/2 545 in 140,4 s. Flache
  Stadtwelt (y −60): „zu_tief“, Absage mit Text, nichts gebaut. 742 Tests grün; Gegenproben rot/grün: doppelte
  Schritte, Kleinfehler, Aushöhlen, Tempo-Maßstab nach Modellwechsel (Host).

- Spigot gegen Paper 1.21.11 (#132), 2026-10-30, zwei frische gleiche Kopien des Tempo-Servers, 2 Kerne, Xmx 3G,
  gleiches Plugin (sha256 ec7e0740…), je zwei Stadtbauten Bad Kreuznach 400 m (1 077 014 Blöcke, 16 Helfer),
  nacheinander, nichts parallel. Durchsatz gleich (19 144/20 064 gegen 19 307/20 280 Blöcke/s, 53–59 s). Spitzen
  deutlich kleiner unter Paper: längster Tick 1914/1019 gegen 823/321 ms, Ticks über 200 ms 5/3 gegen 1/1,
  Ticket-Zeit (synchrones Chunk-Laden) 4794/2204 gegen 1147/145 ms, längstes Ticket 1890/1013 gegen 780/32 ms. Leerlauf
  unter Paper 1,3 statt 0,5 ms Rechenzeit, Heap 1135 statt 700 MB. Erster Paper-Start 34 s (einmaliges Patchen), Spigot
  1,7 s. Paper-Lauf ohne ERROR/WARN. Je ein Lauf, schneller Desktop statt Celeron – Richtung belegt, Faktor nicht.

- Helfer im normalen Bau, Testserver 2026-10-30 (Spigot, 2 Kerne, Schnellweg ohne LLM): Hochhaus 3 Stockwerke „mit 4
  Helfern“ → los helfer=4, im Lauf 8 (offen=52), dann 2 (offen=20), Ende 3388/3388, längster Tick 14 ms. Danach ohne
  Angabe mit stehendem Wunsch 2: Hochhaus 20 Stockwerke (20 796 Aktionen) im Schnellgang 9/Tick, im Lauf 6 Helfer →
  21/Tick, fertig in 22,3 s, längster Tick 40 ms. Gegenprobe: Routing „mehr helfer“ und Wunsch-Vorrang je rot/grün.
  Unter Paper (gleiche Jar): Hochhaus 20 Stockwerke „mit 4 Helfern“, im Lauf 8 → 9 → 16/Tick, 20 796/20 796 in
  26,4 s, längster Tick 65 ms, keine Ausnahme. FALLE beim Testen: Steht am Bauplatz schon ein Bau, lehnt der
  Überbau-Schutz den Schnellweg ab und der Auftrag geht ans LLM – Testplätze weit weg von früheren Bauten wählen.
- Recherche live (2026-10-30, je eine Kette): Eiffelturm 1,7 s (erst 324 m = erster Wert, bevorzugt 330 m → 184
  Blöcke bei max-ausdehnung 192), Kölner Dom 1,3 s (157 × 144,5 × 86,25 m, Gothic, 1:1). Danach HTTP 429 von
  Wikidata/Wikipedia bei 5 Recherchen in 4 s mit User-Agent ohne Kontaktangabe. Vergleich am Modell (mit/ohne
  Referenz) noch offen: Tunnel des GPU-PCs war zu (dev01 :18080 ohne Lauscher).
- Straßenreise: Messprobe mit 4 Eberdingen-Kacheln, „kepplerstrasse“ → Keplerstraße in 25–45 ms, „keplerstr 5“ vor
  Haus 5, „12“ und „999“ → Straßenmitte mit Hinweis. Live (Testserver, Bad Kreuznach `echt`): „wilhelmstrasse“ →
  x 143710 z 6605325 (white_concrete auf y −61, Luft darüber), „Viktoriastr 3“ → x 143706 z 6605214 (gray_concrete).
- LLM-Tempo (2026-10-30, Qwen2.5 14B auf RTX 4070 Ti über den Tunnel): Auftrag „fette maschine“ 18:09–18:13 mit 3,9 /
  10,5 / 21,4 Token/s und 258 / 94 / 46 ms GPU je Token (Prompt 5 780 Token, 49,5 s). Um 18:3x Messprobe: ohne
  Grammatik 134 t/s, mit Grammatik bauplan 89 t/s, mit 11 358 Token Eingabe 3 209 t/s Prompt und 83 t/s, gesamt
  7,2 s. Grammatik und Kontextlänge erklären den Einbruch also nicht; Ursache auf dem GPU-PC (Vermutung: Minecraft
  im Vordergrund belegt die GPU) – noch nicht gemessen.
- „bau mir eine fette maschine“ per LlmProbe gegen dasselbe Modell: alter Prompt → ein NICHT-Gatter (10 Blöcke);
  neuer Prompt → 6 nützliche Anlagen in Halle, aber an x 0, 2, 4 … (überlappend) und Boden als 12×5×12-Klotz
  (Prüfer: Vollklotz) – daher der Schnellauftrag. Live (Testserver): Halle 1 726 Blöcke in 91 s, kein LLM-Aufruf.
- Projektion airocean (Messprobe 2026-10-30): München x 382 504 z 6 821 329, Nürnberg x 349 996 z 6 667 891,
  Abstand 156,8 km (echt ~151 km); Sydney x 15,4 Mio. – alles innerhalb ±30 Mio. Live (Testserver): Bad Kreuznach
  mit `echt` bei x 144 081 z 6 605 203 = Projektion, Reisepunkt wanderte zur Etappe mit 241 Gebäuden, 25/25 Chunks
  mit Hausblöcken. Nominatim für PLZ 71735: Grenzrelation postal_code, boundingbox ≈ 6,5 × 9 km, address.village
  „Eberdingen“. Live-Planung PLZ 55543 auf dem Testserver: Nominatim ok, danach Overpass überlastet (502/504) – den
  Kurznamen belegt nur der Unit-Test.
- Eberdingen auf dev01 (2026-10-30, Weltdateien gelesen): Nominatim-Mitte (-468/2476) = Acker, 81 Chunks nur
  `coarse_dirt`/Feldwege, Etappen 1–15 laut Planung 0 Gebäude. Gebäudehaufen: x −1073 z 1247 (größter), x 870 z 3074,
  x 1120 z 1555. Fertig 17:42 (225/225, 7 075 742 Blöcke). Testserver mit TravelSpot (Bad Kreuznach 400 m): Punkt
  wandert 82 → 241 Gebäude nach x 11725 z 16, dort 25/25 Chunks mit Hausblöcken.
- Nach dem Fix (Tempo-Testserver mit PlotSquared 7.6.0, 2026-10-30): Rückgängig 1 077 014 Blöcke in 5 s mit 238
  Tickets; Stadtbau währenddessen abgewiesen, zweites Rückgängig eingereiht und danach 1 049 949 Blöcke in 5 s; 0
  Fehler. Den Absturz selbst löst der schnelle PC auch OHNE Fix nicht aus (0 Fehler, längster Tick 380 ms) – der
  Schutz ist nur über Unit-Test und die Logzeile `umgehängt=1` belegt.
- Fortsetzen live (Tempo-Testserver 2026-10-29, Bad Kreuznach 400 m, 13 Etappen, 100 Blöcke/Tick): Pause bei 6/13,
  `stop`, Neustart → nach 10 s pausiert bei Etappe 6 fortgesetzt; `weiter`, `stop` bei 9/13, Neustart → laufend bei
  Etappe 9 weiter, diese Etappe 0 gesetzt / 27 677 geprüft (kein Doppelbau); `/bm stop` löscht die Datei.
  Ungedrosselt (2000/Tick, flache Welt) baut derselbe Ort in 38 s. Erste Planung brauchte eine Overpass-Abfrage
  (48 s), weil die alte Kachel ein anderes Abfrage-Präfix hatte.
- Weltbau-Ansicht live (2026-10-29, hoher Testserver + Host lokal): Kachel 366/6584 850 320 Blöcke in 47 s (16/16),
  danach 365/6584 im Bau; Karte zeigt die fertige Kachel weiter neben der laufenden, Kachelraster und Kopfzeilen
  (Bild host/docs/weltbauansicht.png). Vorher, mit dem Fehler: dieselbe Kachel 0 Blöcke, 3 Etappen übersprungen.
- 2026-10-28 dev01, Llama 3.1 8B (Wunsch → Vollzylinder 40 hoch
  (67 240 Blöcke in einem Schritt), Reparatur = 47 Wandstreifen; „20 stöckig“ → 60 Logikgatter-Module in der Fassade.
  Ursache für `skyscraper` und die Modulregel.
- Hochhaus über Ersatz-LLM (`FakeLlm`, Plan aus dem Prompt-Beispiel) auf dem Testserver: 31 111 Aktionen im
  Schnellgang (13 je Tick) in 45 s; Säulenprobe (`ColumnProbe`): Deckenband alle 4, 3 Reihen Glas, Leiter y −59..60
  durchgehend und hängt, Eingang 3 × 3, Licht in jeder Decke, Brüstung, Antenne. x ist bei Blick nach Süden gespiegelt.
- Stadt-Last, Testserver, 400 m Bad Kreuznach (13 Etappen, ~1,0–1,5 Mio. Blöcke), frisches Gelände:
  | Variante | Rückstand (Server) | eigene Ticks > 200 ms | Dauer |
  |---|---|---|---|
  | Flachwelt, alter Stand | – | – (längster 1,6 s) | 53 s |
  | Normalwelt, alter Stand | 2,6 s | 21 | 61 s |
  | Normalwelt, Bremse 8 Chunks/25 ms | 2,5 s | 17 | 61 s |
  | Normalwelt, Bremse 2 Chunks ohne Ticket (Flachwelt) | – | – | 6 min 52 s |
  | Normalwelt, alle Chunks einer Etappe auf einmal mit Ticket | 17,9 s | 8 (längster 5,1 s) | 71 s |
  | Normalwelt, Tickets fest 8 je Tick | 22,8 s | 66 | 87 s |
  | Normalwelt, Lastregelung (jetzt) | 2,8 s (einmal, Start) | 11 | 68 s |
  Spigot lädt beim Plugin-Ticket offenbar sofort selbst – kein Erzeugen im Hintergrund. Auf dem Laptop war der alte
  Stand nicht dramatisch; die 21 s von dev01 (Celeron) sind mit der Lastregelung NICHT nachgemessen.
- Rückgängig auf der Platte: 2,8 MB für 1 015 084 Blöcke.
- Runde 3 (2026-10-28, Workflow: 6 Bereiche analysiert, 47 Vorschläge, 40 nach Gegenprüfung, 11 Pakete in eigenen
  Arbeitskopien umgesetzt und je unabhängig geprüft). Testserver danach: kombischloss, pfeilfalle, zaehler,
  zuckerrohrfarm je n/o/s/w bestanden (16/16); Hochhaus „20 Stockwerke“ ohne LLM in 52 s; house 9×11, 2 Geschosse,
  1 081 Aktionen in 24 s, Einrichtung nachgemessen (BlockScanProbe); volle Truhen im Bauplatz → Bau bricht vor dem
  ersten Block ab, Inhalt unversehrt. Plugin 163 Tests, Host 6/6 Testprogramme grün.
- Ungeprüft aus Runde 3: Chat-Steuerwörter und Tab-Vervollständigung mit echtem Spieler; Stadt-Innenhöfe/Plätze im
  Spiel (nur Unit-Tests, München-Cache); Overpass-gzip gegen echte Spiegel; Host-Vorwärmen und Kappen auf GPU;
  Autostart nach echtem Neustart. Marienplatz bleibt leer (Platz ist eine Relation, die Abfrage holt nur Gebäude-
  Relationen).
- dev01 Hepberg ganz (2026-10-28 18:51–19:01, abgebrochen per /bm rückgängig): 37/193 Etappen, 1,29 Mio. Blöcke;
  1 032 Lücken über 100 ms, längste 289 ms, keine über 1 s – Lastregelung (75 ms, halbieren) fiel auf 0,31 Chunks je
  Tick, gebaut wurde fast nur im geladenen Bereich um den Spieler. Nachgespielt in LoadControlTest: alte Regelung
  1 496 Chunks in 10 min, neue (150 ms, 70 %, Untergrenze 0,25) über 6 000. Ganz Hepberg ≈ 43 000 Chunks.
- dev01 Hepberg ganz mit Lastregelung 150 ms (19:07–19:51, Nutzer): 193/193 Etappen, 5 260 998 Blöcke in 43 min,
  chunk_rate am Ende 4,18 (vorher 0,31), 836 Lücken über 100 ms, eine über 1 s (1,17 s). Fix auf dev01 belegt.
- Sichtweite dev01 `view-distance=10` = 160 m – mehr einer Stadt sieht der Client nicht, egal was gebaut ist.
- München Mitte, 700 m, in `/bm stadtwelt` (Testserver): 37 Etappen, 5 Kacheln (4,6 MB + 4 × ~0,6–0,9 MB, je
  33–44 s beim Dienst). 1. Lauf: 6 548 210 Blöcke in 6 min, längste Lücke 332 ms – aber 9 Etappen weg, weil Overpass
  einmal 502 lieferte und die gescheiterte Kachel im Speicher blieb. Behoben (502/503 = später nochmal, Kachel raus,
  Etappe 2× nach 30 s neu). 2. Lauf: alle 37 Etappen, zweimal „ausgelastet“ → beide Wiederholungen klappten,
  längste Lücke 218 ms.
- Neue Maschinen halbaddierer, rs_speicher, automatiktuer, wasserfahrstuhl: auf Anhieb bestanden; alle 10 Maschinen
  in n/o/s/w = 40/40 bestanden (2026-10-28).
- Zweite Runde (nand, nor, xnor, lampenreihe, klingel, glocke, abwaerts_fahrstuhl, nachtlicht, t_flipflop,
  sammelstelle): 20 Maschinen × n/o/s/w = 80/80. T-Flipflop mit zwei Spendern fiel durch – beide feuern im selben
  Tick, der zweite schob den Gegenstand zurück (richtungsabhängig); Kupferbirne (1.21) schaltet zuverlässig.
- Hepberg „ganzer Ort“ (Testserver, Stadtwelt): Nominatim-Rechteck → Radius 1 872 m, 193 Etappen, 9 Kacheln. Overpass
  am Abend überlastet: overpass-api.de 502, kumi.systems und private.coffee je 200 s ohne Antwort; die erste Kachel kam
  nach 15 min von overpass-api.de (36 s); später lieferte kumi.systems nach 122 s. Zeitlimit je Anfrage 150 s statt 200 s.
  Ergebnis: 193/193 Etappen, 5 228 712 Blöcke in 31 min (meist Warten auf Overpass), längste Lücke 1,27 s.
- dev01: Celeron J4125, 4 Kerne, 7,4 GB RAM, **kein AVX/AVX2**. Spigot läuft mit `-Xmx8G` auf 7,4 GB RAM (nicht angefasst).
- Qwen2.5-VL-7B Q4_K_M auf Core Ultra 7 155U: CPU 8,3 t/s Ausgabe, 37 t/s Prompt; Arc-iGPU (Vulkan) 6,8 / 29 → CPU ist Standard.
- Plan: 318–457 Tokens, 42–102 s (erster Aufruf länger, danach Prompt-Cache).
- Planqualität 7B: Haus sehr gut (kopiert das Beispiel), Turm mal hohl mal massiv, Tür/Fenster teils innen; Brunnen schwach (Wasser eingemauert).
- Lokaler Spigot 1.21.11 (byte-gleiches JAR, sha256 6481503f…): 259/259 Blöcke wie geplant, Tür (beide Hälften, facing), Treppen (Richtung), Scheiben (Verbindung entlang der Wand) korrekt; Rückgängig 259/259 Luft; Stopp bei 44 → 44 zurückgebaut; NPC nach Neustart über UUID wiedergefunden.
- Testserver 2026-10-28 (Laptop-CPU, Server und LLM teilen sich die CPU: 4–6 t/s):
  - Bauplatz-Scan 13–63 ms, Haus mit 2 Helfern 235/235, Konsolenzeilen je Schritt.
  - Anbau mit 7B: ohne Anschlussstellen setzte es die Werkstatt 5 Blöcke neben das Haus (und stapelte 7 Dächer);
    mit Anschlussstellen traf es die Wandebene x=4, baute aber AUF statt neben der Wand → Überbau-Schutz verwarf
    beide Versuche (16 Blöcke), Haus blieb unberührt. Räumliches Planen reicht beim 7B für Anbauten nicht.
  - Bauteil „lampe“ (WorldEdit-Schematic, vorne s): LLM wählte `module`, Kopie Block für Block gleich der Quelle,
    Hebel `face=wall,facing=south` wie im Original. Funktion per Klick nicht prüfbar (kein Spieler; `setblock`
    auf den Hebel schaltet auch die Quelle nicht).
- `Material.isBlock()` braucht ab 1.21 die Server-Registry – in Probe/Tests nur `Material.getMaterial`.
- `Logger.setParent(pluginLogger)` verliert das „[Baumeister]“-Präfix → Handler, der an `PluginLogger.log()` weiterreicht.

- Eingebaute Maschinen (2026-10-28, Testserver, Bau durch den NPC über `/bm maschine`): nicht/oder/und/xor-Gatter,
  takt, kolbentuer – Wahrheitstabellen in n/o/s/w alle bestanden (24/24). LLM (Qwen2.5 3B, Laptop) wählte für
  „Kolbentür in eine Steinmauer“ richtig `module kolbentuer`, plante die Mauer aber mit den Scan-Grenzen (−16..32) →
  „zu groß“, zweimal verworfen. Mit 14B noch nicht geprüft.

- Stadtbau (2026-10-28, Testserver, Laptop): 150 m um 49.8451,7.8676 (Bad Kreuznach) → 96 Gebäude, 298 804 Blöcke
  geplant. Overpass 42–47 s, aus dem Zwischenspeicher 0,4 s. Bau mit 4 Helfern, 2000 Blöcke/Tick: 158 879 gesetzt in
  6 s (zweiter Lauf 9 s), ~40–50 k Blöcke/s, kein „Can't keep up“. Rückgängig vorher fest 400/Tick (≈20 s gerechnet,
  nach 4 s erst teilweise), jetzt `size/100` je Tick: 5,4 s. Raster-Probe (`CityMapProbe`): 709/2888 Punkte mit Decke
  in 3 m, nach Rückgängig 0. Pause/Weiter/Tempo/Helfer 8→1 (Rest des Helfers neu verteilt, 201→208 Abschnitte)/Stop
  über RCON geprüft.

- Logik in Stockwerken (2026-11-07, Spigot-Testserver, `LogicProbe … n 3d`, volle Wahrheitstabelle): 4/4 –
  „3d S = A xor B; C = A & B“, 5-Bit-Parität + UND (17 Terme, 2 Stockwerke, 32 Zeilen), 3-zu-8-Dekoder (8 Lampen,
  2 Stockwerke), 2-Bit-Addierer mit „3d“ (3 Stockwerke). Wartezeit je Stockwerk über dem ersten +0,6 s. Gegenprobe am Server: Turm ohne Verstärker → FEHLER (C bleibt bei A=1, B=1 aus), mit → BESTANDEN.
- Schwimmen (2026-11-07, SwimProbe, Paper 1.21.11): tauchen jetzt → neu Tiefenfehler 2,5 → 0,3, Teleports 2 → 0;
  Ufer mit 1 Block Rand: jetzt nur per Teleport an Land (Tick 192), neu von selbst Tick 153 (Geist 137). Ausstieg 0,3
  reichte nicht (stieg ~0,7, fiel zurück), 0,5 schon. Craft-Rezepte auf Paper 26.2 (CraftProbe): 10/10 lesbar.
- XP-Farm (2026-11-07, MachineProbe auf Spigot 1.21.11, Spur über RCON): Zombie treibt 7 Blöcke in ~4 s, fällt in
  ~1 s. Kanal auf y=22 → unten mit 3 Leben (Fallstrecke zählt erst ganz außerhalb des Wassers, ~1 Block weniger als
  gerechnet), auf y=23 → 2 Leben; Skelett ebenso. Schacht bleibt trocken.
- Logik-Compiler (2026-10-28, Testserver, `LogicProbe`, 11 Formeln bis 5 Eingänge, alle Eingangskombinationen):
  n/o/s/w 44/44. Danach alle 24 Maschinen × n/o/s/w erneut (Regression durch das Anstoßen der Verstärker).
- Hohe Welt (2026-10-28, zweiter Testserver `C:\swb\baumeister-hoch`, RCON 25612): Datapack-Dimension lädt unter
  Spigot 1.21.11 als Welt `<Hauptwelt>_baumeister_gelaende`; setblock bei y 2031 geht, 2032 „out of this world“.
  WorldCreator kennt nur Oberwelt/Nether/End (Bytecode CraftServer.createWorld). Bukkit-Populatoren laufen auch in
  Welten ohne Plugin-Generator (ChunkGenerator.applyBiomeDecoration). Format von dimension_type in 1.21.11 neu
  (has_fixed_time, skybox, cardinal_light, timelines – ohne ultrawarm/natural/effects).
- Grand Canyon, Phantom Ranch 36.1055,−112.0947, 3 km: 49 Kacheln (5,3 MB) in 7 s, min 727 m, max 1 668 m, Bezug 0
  (echte Meereshöhe). 256 frische Chunks in 4,3 s (davon ~3,5 s synchrones forceload). 6 Stellen: Oberfläche =
  Höhendaten auf den Meter (775, 819, 945, 811, 938, 781).
- Projektionen (2026-10-28, 56 Orte, Workflow): Airocean Längen ±11,3 % (Median 7,9 %), ω ≤ 13°, Naht nur im
  Südatlantik; Snyder-Ikosaeder ±15 %; BTE konform ω ≈ 0, aber Maßstab bis +113 % und Rundweg-Fehler bis 6,9 m;
  azimutal um Hepberg Europa < 1,5 %, Australien ×3; Sinus 6° 0,1 %, aber Leerkeile ~250 km in Mitteleuropa.
- Erdwelt live (Testserver, 2026-10-28/29): Hepberg y 426, Marienplatz 525, Phantom Ranch 775 (Bezug 0, alter
  Stand), Amsterdam 5, Nordsee Wasser 0 / Boden −45, Zugspitze 2004 (abgesenkt), Everest 2019 (gekappt); je Chunk
  ~20 ms. Grand Canyon 36,16° N, 22,5 km Schnitt mit zweistufigem Bezug: Bezug 685…760 (0,33 % Neigung), Schlucht
  818 statt 796 m.
- Weltbau Hepberg: Kachel 366/6584 860 549 Blöcke in 59 s (16/16 Etappen, ~26 000 Blöcke/s, längster Tick 1,1–1,6 s),
  365/6584 1 330 988 Blöcke; Overpass einmal 502, Ausweichdienst lieferte nach 72 s.
- Speicher hohe Welt: 16 × 16 festgehaltene Chunks samt Ladering +289 MB, normale Welt +36 MB (Faktor 8); je
  Abschnitt ~450 Byte auch für Luft, 254 Abschnitte je Chunk. Mit 3 GB Heap: OutOfMemoryError beim Speichern nach
  einer Kachel (MAX_HELD 900 + Hunderte festgehaltener Messpunkte, je ~289 Vorstufen-Chunks Ring), danach
  beschädigte Chunks („Failed to load chunk … EOFException“). Mit MAX_HELD 300 für hohe Welten: gleiche Kachel bei
  3 GB ohne OOM, Spitze 2,6 GB, bereinigt danach 191 MB. Mit HeapGuard über geladene hohe Chunks: drei weitere
  Kacheln am Stück (367/6582, 368/6582, 367/6583), je ~3 s Pause, kein OOM, keine beschädigten Chunks.
- Runde 5 (2026-10-29, 9 Pakete): Plugin 649 Tests grün, Host `build.ps1` configure/build/test grün, 7/7
  Testprogramme (Grammatik mit den Bauformen). Live auf der Testkopie `C:\swb\baumeister-tempo` (Laptop):
  - Config-Migration: 38 fehlende Schlüssel ergänzt, kein vorhandener Wert geändert (Vergleich vorher/nachher),
    Sicherung angelegt. `/bm leistung` antwortet über RCON.
  - Schnellaufträge ohne LLM: Burg 25 × 25 2 665 Blöcke in 127 s (längster Tick 7 ms), Brücke 444/444 in 58 s,
    Garten 161/161 mit 24 Blumen.
  - Warteschlange: zwei Aufträge eingereiht und nacheinander gebaut, dritter mit „schon 2 Aufträge“ abgewiesen.
    Rückgängig über Neustart: Burg vom Stapel geladen („rückgängig | geladen | einträge=1“) und zurückgebaut.
  - Stadt 150 m Bad Kreuznach mit neuer Overpass-Abfrage (overpass-api.de 502, kumi 504, private.coffee 504, nach
    ~5 min geliefert): 151 141 Blöcke in 9 s, 39 Laternen, 770 Gleisblöcke, 53 Zäune, 30 Mauerstücke, 5 Schilder
    beschriftet; ein Tick 0,8–1,2 s beim ersten Chunk (bekannt).
  - Tempo 400 m Bad Kreuznach (vorerzeugte Chunks, 4 Helfer): alt 55–57 s, neu 40,6–40,9 s (~26 000 Blöcke/s),
    längster Tick 36–45 ms; mit `gleiche-zellen-je-block: 1` 56,9 s wie der alte Stand. Alter Stand mit 4 Helfern wich
    4 457 Blöcke vom Bau nacheinander ab, neuer 1–7 (Gras/Erde durch Zufallsticks). Hochhaus 30 Stockwerke:
    Prüfung 5,2 s → 18 ms.
  - Redstone (Testserver): 28 Maschinen × 4 = 112/112, Logik 16 Formeln × 4 = 64/64. NPC baut ~25 Blöcke/s, der
    2-Bit-Addierer brauchte 2 min 20 s. Zellen der 11 alten Probeformeln gleich dem Stand b8ec700.
- Erdwelt (2026-10-29, `C:\swb\baumeister-hoch`): Hepberg y 426 (unverändert), Nordsee Wasser 0 / Boden −21, Polder
  Haarlemmermeer trocken −6, Badwater trocken (y −1075 wegen Absenkung durch die Gebirge im Umkreis), Totes Meer
  Wasser auf −200 (Kappung), 0 Fehler im Log. Frische Chunks unter Last 36–40 → 11–22 ms. Terrarium-Kacheln: Wasser
  zu 100 % ganzzahlig, Land unter 0 m in SRTM-Gebieten 18–29 % „3 von 4 Ecken“ ganzzahlig → Fenster 12 × 12
  (Atyrau 31 → 3 % nass). Neumalen (erste Fassung): 81 geschätzte Chunks, nach Neustart y 1 349 = Rechnung.
- Ansichten, Haus 9 × 11: ganzer Scan 2 695 Zeichen, Ausschnitt ±8 1 181 (SiteSurveyViewsTest); Token-Zeit mit echtem
  LLM nicht gemessen.
- Java 21 / Windows 11: Eine Junction meldet `isSymbolicLink=false`, nur `isOther=true`, und `Files.walk` läuft
  hinein. Alter Neuladen-Weg blockierte bei laufender Hintergrundaufgabe 15 028 ms, mit Übergabe des Threads 0,2 s.
- Temurin 21.0.12: `Runtime.maxMemory()` = `-Xmx` unter G1; G1 mit `SurvivorRatio=32` lehnt Heaps unter 256 MB ab;
  Bukkits `YamlConfiguration.loadConfiguration` schluckt YAML-Fehler und liefert eine leere Config.
- Kompakte Objektköpfe (dev01, Paper 26.2, 5-GiB-Heap, gc.log, gelesen 2026-11-08): belegt nach der Pause 451 MB bei
  65 s, 456 MB bei 895 s, danach 457–460 MB über 10 h Leerlauf, 0 Full GC; nach dem Deploy 460 MB bei 342 s. Ohne die
  Option 498 MB bei 282 s (Sitzung davor; die JVM schreibt `UseCompactObjectHeaders 1` bzw. `0` ins gc.log) – 8–9 %
  weniger, wie nach 45 s (431 statt 473 MB). Genau bei 280 s kam mit Option keine Pause.

- Vera im Weg (2026-11-15, WegProbe mit Geist-Spieler, Paper 26.2, `C:\swb\vera-probe`, Skript
  `betrieb/probes/vera-weg/wegprobe.sh`): Aufrufe mit Berührung / mit Vera in seiner Bahn, alt → neu: freies Feld
  3 → 0 / 5 → 1, 3 breiter Gang 3 → 0 / 5 → 4, einzige Tür 4 → 0 / 6 → 0, 1 breiter Gang mit Umkehr 4 → 0 / 6 → 4
  (1 Platztausch), normales Folgen unverändert (Abstand 3,6/5,7). Ohne Tauschregel (vorauslaufen bis zur Nische)
  im engen Gang 10 / 22. Gesteuert alle 2 Ticks wie im Dienst. Nicht gemessen: echter Spieler, mehrere, Wasser.
- Lobby (2026-11-15, vera-probe Paper 26.2 flach, `bauenbei … riesen große lobys`): 81 × 81, 131 961 Aktionen,
  32 129 Blöcke in 28,5 s, längster Tick 33 ms, 0 Ticks über 50 ms; 17/17 Stichproben am Plan-Ort. LichtProbe
  (`probes/lobby`, Lichtberechnung des Servers): 5 447 begehbare Stellen, 0 dunkel, Minimum 4. Schritte je Variante
  29–59 (Grenze 60), Zellen bis 131 961 (Grenze 150 000) – mehr Portale oder größer geht nur mit höheren Grenzen.
- Portale (2026-11-15, vera-probe, Portalhalle per bauenbei bei 2000 −60 2000 s): `setblock … fire` in einen
  gebauten Rahmen lässt Minecraft selbst entzünden – Seitenportal axis=z, Rückportal axis=x, dieselben Achsen wie
  `/bm portal` (12/12 Blöcke axis=z). Überlebt Neustart (portale=1, Schild da); Rahmenblock → Stein löscht das Portal
  wie im Spiel, Liste meldet „erloschen“; Entfernen 12/12 wieder Luft. Sprung mit echtem Spieler NICHT geprüft.
- `/bm einrichten spawn` (2026-11-15, derselbe Jar): Paper 26.2 (vera-probe) und Spigot 1.21.11 (baumeister-testserver)
  je respawn_radius 10 → 0, Weltspawn gesetzt, keine Ausnahme. Portale auf Spigot wie auf Paper: Halle 3 811 Blöcke,
  Feuer entzündet axis=z, `/bm portal` 12/12 axis=z, ein Schild.
- `/bm portal alle` (2026-11-16, vera-probe, Portalhalle mit 7 Rahmen, einer verbunden, Ziele nur Nether/End):
  2 verbunden, 1 behalten, 4 ohne Ziel gemeldet. PortalScan prüft nur die untere linke Ecke jeder Öffnung.
- Stadtleben-Arbeitsorte (T-023, 2026-11-22, Spigot 1.21.11, 12 Bewohner, Stub-Host): Mittag ohne Bau 0,103 ms je Tick
  (p99 0,780, 36 Wegsuchen), mit Bau + Lag-Wache 0,040 (p99 0,290, 13), danach 0,066; Arbeit ohne Bau 0,093, mit 0,072.
  Live `arbeitsort | quelle=warp` (EssentialsX) und `quelle=reisepunkt`. EssentialsX nur per Reflexion, 60 s gepuffert –
  Falle: eine kaputte Warp-Datei (ohne `world`) schreibt bei jeder getWarp-Abfrage einen Stacktrace. Ortsquellen und
  chooseWork fangen RuntimeException (fail-open, Arbeitsort ist kein Schutz); wer den catch entfernt, lässt einen
  Bewohner ohne Arbeitsort verfolgt und die Ausnahme beim Scheduler.

- Tür und Modulmantel (T-021, 2026-11-22, Qwen2.5 7B Vulkan 8192, Temp 0,4): Wachturm, Zuckerrohrfarm+Schmelzanlage,
  Kirche, Leuchtturm, Pferdestall vorher 3/5 angenommen, nachher 5/5, `tür_gerichtet` bei Wachturm und Leuchtturm.
  Gleiche Antworten mit altem/neuem Code: 2 verworfen → 1. `PlanAutoFix.doors`: Außenraum 3D geflutet, Rang
  umschlossener Innenraum vor Lage vor Abstand; nie Becken- oder Modul-/Logikzelle. `moduleShells` nur für fill aus
  SHELL_FILLERS (Sand, Kies, Erde, Gras, Ton, Wasser – NICHT Stein/Bruchstein: Sockel), Kasten schneidet das Modul und
  steht ≤ 2 seitlich/≤ 1 hoch über; vorher zerschnitt die erste Fassung Hallenwände (90 Blöcke) und Böden und nahm
  Truhen am Modul weg (Gegenprobe). Eine breite Sand-/Wasserwand an zwei Modulen vorbei bleibt (9 Füllblöcke) – gewollt.
- Item-Sortierer auf Paper 26.2-135 (T-022, 2026-11-22): _2, _4–_7 ohne und mit Sorten, N/O/S/W, 40/40 BESTANDEN.
  Falle: der Prüfablauf füttert fest `dirt` als Fremdgegenstand (Machines.java ~2257/2289) – steht `dirt` in der
  Sortenliste, fallen _4/_6/_7 durch (FREMD3=1), ohne Fehler der Maschine.
## Sicherheitsregeln (mit Gegenprobe belegt)
- Sicherheitsprüfung 1 zu P3 (2026-11-08), je Befund Gegenprobe rot → grün: Paar aus Bauteil- und Plan-Hälfte prüft
  beide (PlacementGateTest), JA eines Bauteils nur unter denselben Rechten, NEIN bleibt; „merk dir“ prüft Größe/Name
  vorher und fragt still (Prüf-Ereignis auf HIGHEST abgebrochen, kein CoreProtect-Eintrag); Topfscherben, Feuerwerk,
  Verzierung, Instrument, Bannermuster nach Wert; Text-JSON tiefer 32 → Befehle (Gson-Grenze 255 galt als Text);
  Admin-Vermerk für Bauteil-Daten; nur echte Konsole; Fallschutz nur neben gerade gesetzten Zellen; bold nur Zahl;
  Snbt.write wirft bei rohen Steuerzeichen. Unabhängige Gegenprobe (sw-gegenprobe) bestätigte die sechs Fixes von P3.
- Runde „alle elf“ (2026-11-05), je Regel Gegenprobe rot → grün: Herausnehmen entfernt (sonst Duplikat),
  Teamname nur [a-z0-9_-]{2,16} (Dateiname), fremder Warp nicht versetzbar, GWarp-Namen geprüft, Aufgabe nur einmal
  bezahlt, Serie nur bei Folgetag, Hardcore-Team endet gemeinsam, „spiel mit mir“ keine Einladung, Verdoppeln hält
  Türen ganz, Reparieren nur #-Verursacher (nie Spieler).
- Reparieren (CoreProtect-Rollback) ist ein möglicher Vervielfältiger (aufgehobene Trümmer bleiben) → Recht
  baumeister.reparieren Vorgabe op; Kisten-Verlauf nur für selbst gesetzte Kisten (blockLookup Aktion 1) oder
  baumeister.kisten. CoreProtect-Namen gemessen in co_user (dev01): #creeper #ghast #fire #lightning #enderman.
- Rucksack (RucksackService, 2026-11-05): Inhalt je SPIELER auf dem Server, Kiste nur Schlüssel (PDC
  baumeister:rucksack = UUID) – Kopien kopieren nichts. Ein Fenster je Spieler, nicht im Kreativmodus, Datei mit
  Nicht-Gegenstand oder Platz außerhalb der Seiten → zu und nie überschreiben (RucksackFile.check). Cache bleibt
  nach dem Verlassen im Speicher (sonst Neuladen vor dem Schreiben → Duplikat). Gegenproben: Auffüllen aus,
  Riesenstapel-Schutz aus, Gegenstandsprüfung aus, Seitengrenze aus → je rot.
- MinepacksStable am 2026-11-05 abgeschaltet und auf Wunsch gelöscht (JAR und plugins/MinepacksStable): Der
  einzige Rucksack (<Spieler>) war leer, geprüft unmittelbar vorher. Das alte
  plugins/Minepacks (ohne JAR seit 2026-03, 7 Rucksäcke von 2021/22) auf Wunsch ebenfalls gelöscht, nicht übernommen.
- 2026-11-04 Reparieren (BuildLedger): Schaden = anderes MATERIAL, nicht anderer Zustand (sonst stellte Vera
  umgelegte Hebel zurück) – Gegenprobe voller Zustand → BuildLedgerTest rot. Nach „rückgängig“ fällt der Eintrag weg
  (sonst baute „reparier“ den rückgängig gemachten Bau wieder) – Gegenprobe forget wirkungslos → rot. Reparatur nur
  für Besitzer oder baumeister.admin, Bauen-Recht nötig, Rechteprüfung wie beim Bauen. 942 Tests grün.
  Grenze: Bauten vor dem Einspielen stehen nicht im Baubuch; im Spiel noch nicht geprüft.
- 2026-11-04 13:49 Vorfall: „ein Haus“/„ein neues Haus“ zweimal als update des alten Hauses (12 bzw. 10 Blöcke am
  alten Platz). plan/OrderIntent: neues eigenständiges Bauwerk ohne Bezugswort → kein Gedächtnis. Gegenprobe: immer
  „kein neuer Bau“ → OrderIntentTest rot; mit allem 935 Tests grün. Grenze: „noch einen Turm daneben“ und alles mit
  „das/dieses/neben“ bleibt beim Gedächtnis.
- 2026-11-04 12:19 Vorfall: „Anlage reparieren“ → fill ~-5 ~ ~-5 ~5 ~5 ~5 redstone, nach „ja“ ausgeführt, scheiterte nur
  an der falschen Block-ID. Seitdem Rückfrage in Worten und Sicherung vor fill/setblock/clone (≤ 32 768 Blöcke).
  Gegenprobe: ~-Koordinaten ohne Spielerbezug → CompanionRulesTest rot; mit allem 930 Tests grün.
- 2026-11-04 Befehle über Vera: CompanionRules.decide ohne Rechteprüfung → CompanionRulesTest rot; ohne Rückfrage →
  rot; mit allem 927 Tests grün. Ausgeführt wird über p.chat("/…") als der Spieler (PlayerCommandPreprocessEvent,
  Server-Log), nie über die Konsole.
- 2026-11-04 Begleiter: CompanionRules.mayGive immer true → CompanionRulesTest rot; mayFind immer true → rot;
  /bm-Prüfung „bauen oder reisen“ aus → CompanionPluginTest (Spieler nur mit baumeister.begleiter öffnet karte/orte/ort)
  rot; mit allem 914 Tests grün. Host: choose_grammar ohne begleiter → test_plan rot; valid_player_name immer true →
  test_server rot; speak_for nur Länge → erst grün (Lücke: kein gleich langer fremder Name), Testfall ergänzt → rot.
- 2026-11-01 Umbauwunsch nur als Wortanfang (PlanReview.isRebuildWish): altes Teilwort-Muster → Geländer/dreißig/Rändern
  heben den Überbau-Schutz auf (rot), Stämme → grün; einreißen/niederreißen ohne eigene Stämme rot, mit grün.
- 2026-11-01 Workflow-Runde (Finden → Skeptiker → Umsetzen, 13 Agenten): 14 Punkte, je Gegenprobe ohne Fix rot / mit
  Fix grün (Zeilen in den Commit-Bodies 0925d42, 3f20cd5, 3c85f55, 315e5ca, ef9417d). Gesamt: Plugin 858, Host 8/8,
  Client 23 Unit-Tests grün.
- 2026-11-01 Redstone vor Ticket-Freigabe (BuildJob.afterBuild): alte Reihenfolge → BuildJobAfterBuildTest rot, neue grün.
- 2026-11-01 vorgemerkter Plan startet nur bei freier Baustelle (DeferredStart): „frei“ ignoriert → rot, an → grün.
- 2026-11-01 Konsole sichern (host, test_console_export): ohne CRLF-Umwandlung 1 von 4 rot, mit grün.
- 2026-11-01 Client-Aktionsleiste (ProgressLineTest): Restzeit ohne Mindestmenge → 1 von 4 rot, mit grün.
- 2026-11-01 Start fail-closed bei kaputter config.yml (ConfigMigration.brokenReason): Erkennung aus →
  `brokenYamlIsReportedSoThePluginStaysOff` rot, an → grün. Testserver: Plugin aus, Datei byte-gleich (sha 23a94ca9…).
- 2026-11-01 kein normaler Bau neben Stadt-/Weltbau (BuildService.conflictMessage): Sperre aus → 2 von 3 Tests in
  BuildConflictTest rot, an → grün.
- 2026-11-01 fill+hollow baut hohl: ohne Fix `fillWithHollowFlagBuildsAShellLikeHollow` rot (125 statt 98), mit grün.
- Recherche (2026-10-30), je ohne Fix rot, mit Fix grün: bevorzugter Wert ignoriert; Fehler (429) gemerkt; Titel
  nicht gegen den Auftrag geprüft; Höhe im Plan-Prüfer nicht geprüft. Paper-Logger: ohne Fix StackOverflowError beim
  Start (Testserver), mit Fix startet das Plugin.
- CityIndex.remove (2026-10-30): ohne Speichern → nach Neustart gewinnt der tote Eintrag wieder (19 erwartet, −468),
  mit Speichern grün.
- Straßenreise (2026-10-30), je ohne Fix rot, mit Fix grün: Toleranz 1 je 6 → „andere Straße nicht raten“ rot;
  Schlusspunkt doppelt im Hausmittel → Haus-Test rot (35 statt 37); Hausnummer nicht gesucht → rot; Stadt mit PLZ
  im Namen nur exakt → CityIndexTest rot.
- Plan-Prüfer/Maschinenhalle (2026-10-30), je ohne Fix rot, mit Fix grün: buriedModules aus → 2 Linter-Tests rot;
  Rückmeldung ohne „jede Art zuerst“ → rot; Fackeln wieder ans LLM → rot; Hallenabstand x += 2 → Abstandstest rot;
  Halle nicht eingehängt → 3 rot.
- Echte Stelle/PLZ (2026-10-30), je ohne Fix rot, mit Fix grün: lat/lon vertauscht → 2 rot; ohne Grenzprüfung → rot;
  PLZ als Freitext → 3 rot; Name ohne PLZ-Kurzform → rot.
- Reisepunkt (2026-10-30), je ohne Fix rot, mit Fix grün: Bodenabschnitte mitzählen → „Acker ohne Gebäude: Punkt
  bleibt“ rot; Bestwert vergessen → Weiler schlägt Dorf und Neustart wirft den Ortskern hinaus (2 rot);
  `reise_gebäude` nicht speichern → Hin-und-Rückweg rot.
- Absturzschutz (2026-10-30), je ohne Fix rot, mit Fix grün: RestoreJob ohne isChunkLoaded → „nie in einen
  ungeladenen Chunk“ 0 erwartet, 4000 geliefert; ohne Uhr → 64 erwartet, 4000; gemerkten Chunk nicht neu bestellen →
  WAITING statt DONE; ChunkLoadGuard ruft ungeladen sofort → PlotSquared 1× statt 0× aufgerufen.
- Ganze Karte vorab (2026-10-29): ohne Kette (alle Kacheln zugleich) → CityStagesPrefetchTest rot („nie zwei
  Abfragen gleichzeitig ==> expected: <1> but was: <4>“), mit Kette grün. Das Weltbau-Vorausladen hat keinen
  Unit-Test (Bukkit-Takt) und griff im Test auf dem Land nicht (s. Fallen).
- Fortsetzen (2026-10-29): Fortsetzungspunkt ohne ladende Etappe → CityJobTest rot (7 erwartet, 8 geliefert);
  CityResume ohne Prüfung etappe ≤ etappen → CityResumeTest rot; cellFor immer 1 → CityRasterTest rot (5 erwartet).
  Mit Fix je grün.
- Sockel im Raster (2026-10-29): ohne Sockel-Fix → CityRasterTest.plinthUnderHousesOnASlopeNeverBreaksTheRaster rot
  (ArrayIndexOutOfBounds −1), ohne Absicherung in StagePrep → stagePrepBuildsEvenWhenTheRasterFails rot; Weltbau-Melder:
  Vergleich mit der letzten Sendung aus → HostReporterTest rot; Host: Hintergrund beim Kachelwechsel aus → 2 Tests rot.
- Stadtansicht (2026-10-29): Vorrang aus → CityRasterTest rot (Laterne überdeckt Straße), Brückenregel aus → rot,
  „eine Anfrage zugleich“ aus → HostReporterTest 2 rot; Host: Längenprüfung der Zellen aus → test_city_api rot,
  Einmal-Malen aus → test_city_view rot (image_builds); mit allem grün (Plugin 669 Tests, Host 8/8 Programme).
- Syntax-Reparatur (2026-10-29): JsonRepair im PlanParser abgeschaltet → JsonRepairTest 5 von 9 rot, darunter
  wörtlich der Fehler vom GPU-PC („End of input at line 1 column 1999 path $.steps[26]“); mit Reparatur 9/9 grün
  (26 bzw. 9 vollständige Schritte aus den beiden echten abgeschnittenen Antworten, abgeschnittenes Haus über
  PlanReview als Accept statt Reparaturrunde).
- Bezug fern: ohne ferne Stufe → `withoutTheFarFieldTheCanyonWouldBeFlattened` (eingebaute Gegenprobe) Schlucht
  < 95 %; mit → ≥ 95 %. Abschwächung 0 → `mountainTiltsTheLandAtMostFifteenPercent` rot (Flachland 1 509 m abgesenkt),
  0,15 → grün (2026-10-28).
- Erdwelt-Paket (Workflow, Prüfer + Nachbesserung, je Test rot ohne Fix): globales Kachelschloss, Speichergrenze,
  Fehlschlag gemerkt, Drehung, Eck-Fix, Datapack überschreibt, Grundgestein unter Steilwand, Wasser in steilen
  Säulen, bilinearer Bezug, 972 Abrufe bei toter Kachel, kaputte Antwort im Cache, Pol-Koordinaten.
- Weltbau-Paket: Datumsgrenze (dLon 360°), Höhen im Takt, Drossel nach synchronem Wurf, Lücken als fertig, Rückgängig
  auf dem Spielerstapel – je Test rot ohne, grün mit Fix.
- Reisepunkte: langer Name überschreibt andere Art, NaN-Koordinaten, NFD-Umlaute, reisen allein – je rot/grün.
- Logik-Lampe: ODER-Leitung ohne zusätzlichen Draht vor der Lampe → LogicProbe 11/11 FEHLER (letzter Draht bog zur
  End-Fackel ab), mit → bestanden. Ohne Anstoßen der Verstärker → XOR/Parität/4 Eingänge falscher Startzustand,
  mit → 11/11 (2026-10-28).
- Grammatik `mode`: alter Host-Test (Pläne ohne `mode`) gegen die neue Grammatik → 6 Prüfungen rot (abgelehnt),
  Testdaten mit `mode` → 6/6 Testprogramme grün; dazu Ablehnung von fehlendem und unbekanntem `mode` (2026-10-28).
- Etappen: Boden unter Häusern zugelassen → `plannedInStages…` rot (4 Bodenzellen unter Häusern); Gebäude in jeder
  berührten Etappe → rot (ein Gebäude 4×); alte Kachelauslegung (1024 m, Zuordnung über die Etappenmitte) →
  `stageLiesInsideItsMapTile` rot („Etappe −512/0 ragt aus Kachel 0/0“). Mit allem: 71/71 grün (2026-10-28).
- Lastregelung: alte Werte → LoadControlTest rot (1 496 Chunks, Rate 0,22), neue → grün (2026-10-28).
- Spitzer Helm: ohne Streckung der Schichten → `churchTowerStands…` rot (Turm endet bei 42), mit → grün.
- Ausweichdienst: immer nur die erste Overpass-Instanz → `OverpassMirrorTest` Timeout, mit Umschalten → grün.
- rs_speicher ohne letztes Drahtstück der Rückführung (T2 → X1) → Probe FEHLER in Schritt 4 (Q fällt auf 0, sobald S
  aus ist), mit → bestanden (2026-10-28).
- Hochhaus-Test fand einen echten Fehler (Dach innen Bodenbelag statt Fassade) – rot, behoben, grün.
- Chat-Stadtbitte nur mit „nach“ am Ende: Pflicht entfernt → `ordinaryBuildOrdersStayWithTheLlm` rot („ein kleines
  Haus aus Fichtenholz“ als Ort erkannt), wieder an → 63/63 grün (2026-10-28).
- Funktionsprobe der Maschinen: XOR ohne die beiden Verstärker gebaut → Probe FEHLER (A=B=1 → Lampe an, Rest 15−9=6
  wie gerechnet), mit Verstärkern → bestanden (2026-10-28).
- Sperrliste (Lava, TNT, Feuer, Befehlsblöcke, Betten …): ausgeschaltet → `forbiddenBlocksAreSkippedWithWarning` rot (6 statt 1 Block), an → grün (2026-10-28).
- Halt vor Angehängtem: Regel aus → `attachedBlocksComeAfterTheirSupportInTheSameStep` rot („expected wall_torch but was stone“), an → grün (2026-10-28).
- Überbau-Schutz live: zweimal 16 Blöcke erkannt und verworfen, nichts gesetzt (Testserver 2026-10-28).
- BuildJob hält Chunks (2026-10-29): Ohne Spieler in der Nähe (bauenbei von der Konsole, Auftrag aus der
  Warteschlange, Spieler weitergegangen) stand der NPC in einem entladenen Chunk („NPC: nicht geladen“), Bau still bei
  0. Jetzt Plugin-Chunk-Tickets über das Rechteck der Baustelle + 1 Chunk Rand von start() bis finish()
  (`BuildJob.holdChunks/chunkKeys`, BuildJobChunksTest).
- Reichweite (2026-10-29): standFor bleibt auf Bodenhöhe, Blöcke mehr als ~3 unter dem Boden waren nie in Reichweite
  (4,3) → Brücke zweimal 15 min bei 0/444. Jetzt setzt der NPC vom Standplatz aus, wenn er angekommen ist. Live:
  vorher 0/444 hängend, danach 444/444 in 58 s.
- Straßenschilder (2026-10-29): StagePrep baute den Stadtplan über einen Konstruktor ohne Schilder neu → Schilder ohne
  Text. Fix, Konstruktor ohne signs entfernt (Compiler erzwingt die Weitergabe). StagePrepTest.preparedPlanKeepsTheStreetSigns
  ohne Fix rot („but was: <[]>“), mit grün; live „stadt | schilder_fertig | beschriftet=5 verworfen=0“, Schild zeigt
  „Burgenland-/straße“, gewachst.
- UndoSpool.read (2026-10-29): Tempo entpackt einen Teil ganz, Komfort begrenzt Einträge gegen präparierte gzip-Dateien
  → zusammengeführt: die Grenze greift schon beim Entpacken (maxBytes = 16 B je Eintrag + 16 MiB). Der alte
  `BaumeisterPlugin.reload()` (leerte den Rückgängig-Stapel) ist entfernt, `/bm neuladen` nur noch über ConfigReload.
- Runde 5, je Test rot ohne / grün mit Fix (2026-10-29, Einzelheiten in den Testnamen):
  - Logik: Verstärker aus → Signalstärke-Test rot und live FEHLER (2-Bit-Addierer, volladdierer CO); Gruppen ohne
    Leerspalte → Netztest; Rückführung erlaubt → Ablehnungstest; Verstärker überall → Fingerabdruck
    (`smallSingleCircuitsKeepTheTestedLayout`); „ß“ aus dem Namensmuster → 4 Tests; item_sortierer ohne Sperrdraht
    → live FEHLER.
  - Bauformen (nur Unit-Tests): Durchfahrt zu → Hof nicht erreichbar; Zustände nicht mitgedreht → Leiter an Luft;
    GBNF ohne width → GrammarDriftTest; Linter mit Kasten statt Zellen; `wall` mit y 1..3 angenommen; Wortende-
    Erkennung („Kindergarten“). Der Eintrag in UNCHECKED_OPS ist NICHT belegt.
  - Plan-Prüfer/Anbau: Vollklotz → genau 1 Reparatur, Tür in gemeinsamer Wand, Ausrichtung in vier Richtungen,
    Klemmen, Satzende-„an“ nur mit Bau-Verb, Keller unter dem Haus, Hügel-Plattform schwebt nicht. Überbau-Schutz nach
    der Ausrichtung weiter fail-closed (15 Blöcke Durchbruch → Reparatur, dann Absage).
  - Komfort: Vorschau baut nie, veränderter Bauplatz baut nicht (auch aus der Warteschlange), Junction nicht
    durchlaufen, unbekannter Blockzustand verworfen bzw. übersprungen, Teil über PART abgelehnt, Neuladen wartet nicht
    auf dem Hauptthread.
  - Betrieb: kaputtes YAML beim Neuladen → `ConfigReloadTest.brokenFileChangesNothing`; BuildService immer ersetzt →
    `ReloadPlanTest.unchangedBuildSettingsKeepTheBuildServiceAndItsUndoStack`; Migration ohne Vorfahr-Sperre.
  - Tempo: Viertel-Budget, Chunk-Reihenfolge (3 441 statt 36 Chunkwechsel), kaputter Teil (IndexOutOfBounds statt
    IOException), Planreihenfolge an geteilten Wänden, Rest eines Helfers vorn, Etappe 0 nicht im Hauptthread.
  - Stadt: Kirchturm-Basis, Gleise, Zaunlücke, Waldbaum nicht auf dem Weg, Schilder an der Etappengrenze (4 statt 2),
    alte v2-Kachel, SignWriter beim Herunterfahren (NPE ohne isEnabled-Wächter), Gebäudegruppen unter Last (> 20 s →
    0,74 s), Laternen neben separaten Gehwegen.
  - Erde: DEEP_FILL vor der Senken-Prüfung → 1 856 Wasserblöcke über dem See; Seefläche nach 3 von 4 Ecken → 166 Pfützen;
    globales Schloss in BasinMap → Timeout; RepaintJob ohne Nachprüfung → Spielerblöcke überschrieben; Datumsgrenze
    nach Teil 1 „fertig“ → halbe Kachel.
- Schwimmen, Mitfahren, Craften (2026-11-07), je rot ohne / grün mit: Wasser nur nach oben wie vorher und kein
  Ausstieg an der Kante → CompanionWalkTest; Truhenboot mit zwei Plätzen → CompanionRideTest; Zuteilung ohne die im
  selben Craft verplanten Zutaten → CompanionCraftTest (erst nach neuem Test rot: eine Planke füllte beide Felder);
  Craft-Wunsch nicht erkannt → CompanionChatTest.
- Update 26.2, XP-Farm, Korrekturrunde (2026-11-07), je rot ohne / grün mit: Pflichtschlüssel has_ender_dragon_fight
  nicht ergänzt → TerrainServiceTest (und live: 26.2 startete nicht); Karte nimmt den ersten dimensions-Treffer →
  MinimapRegionDirTest; Korrekturrunde hängt ans volle Gespräch → PlanPromptTest; XP-Farm ohne Teppich →
  MachinesTest; Farm-Wörter ohne Schnellauftrag → FarmOrdersTest. Live: Kanal y=22 → Zombie mit 3 Leben (Probe rot),
  y=23 → bestanden.
- Logik in Stockwerken (2026-11-07), je rot ohne / grün mit: „3“ als 3D-Wort (3 zu 8 ungefragt gestapelt) und
  „3-D“ nicht zusammengezogen → LogicOrdersTest; Lampen alle unten angesagt → 3 Tests; Dekoder-Formel vertippt →
  Wahrheitstabellen-Test; Host-Grammatik mit alter Grenze 160 → swbh_test_plan; Turm ohne Verstärker → live FEHLER.
- Stadtleben (T-020, 2026-11-22, Probe-Client + Qwen2.5-7B CPU): 12 Bewohner Spigot 1.21.11 Mittel 0,081 ms/p99 0,190 ms,
  Paper 26.2 0,062–0,087/0,160–0,440 ms je Tick; einzelne Ticks bis 40 ms auf Paper ohne eigene Arbeit im Tick
  (status nennt Ursache: erschienen/teleports/wegsuchen = 0 → vermutlich GC/JIT). KI 3,3–10,5 s, Frist 20 s bricht
  in llama sofort ab („cancel task“). Host weg → alle fort nach 7 s (Spigot) / 24 s (Paper). dev01 nicht gemessen.

## Fallen
- Mannequin ist kein Mob: `setChestplateDropChance` u. ä. werfen (T-020: 31 verwaiste Mannequins in 2 min). Bewohner
  zuerst eintragen, dann einkleiden. Wackeln auf der Stelle zählt bei CompanionWalk als Bewegung – Stadtleben misst
  Annäherung ans Teilziel (town/Progress), sonst hängt ein Bewohner minutenlang mit Tausenden Wegsuchen (Paper, Greta).
- Stadtleben-Client erbt sonst `llm.timeout-sekunden` 900: `orTimeout` beendet die HTTP-Anfrage NICHT, llama rechnet
  weiter und Bewohner-Anfragen stapeln sich vor Bauaufträgen. Deshalb `TownLife.clientSettings` (20 s) und Budget erst
  nach Ende der HTTP-Anfrage frei. Der Host nimmt nur Schema „bauplan“/„begleiter“; Bewohner nutzen „begleiter“ und
  verwerfen jede Aktion. Eine Chatzeile mit Bewohnernamen in Hörweite geht an das Stadtleben, nicht an Vera.
- Probe-Client für Live-Proben ohne Minecraft-Client: Python-Bot (Protokoll 774 = 1.21.11, 776 = Paper 26.2, 11
  Paket-IDs der Spielphase verschoben, IDs aus dem Datengenerator). Er druckt das Längenbyte des Chattextes mit – ein
  „'“ vor Antworten ist ein Lesefehler des Bots. Liegt nur im Scratchpad, nicht im Repo.
- Vorschau im eingebauten Browser: Dateien zeigt er nur als Schnappschuss (data:), file://-Bilder laden dort
  nicht, Seitenwerkzeuge greifen außerhalb des Projektordners nicht. Vorschau nach target/vorschau/
  (ignoriert) schreiben; Mermaid-Fehler zeigt der Seitentitel („– FEHLER“), Bilder direkt ansehen.
- Probeläufe nie überlappen (2026-11-17): Ein Lauf ist erst fertig, wenn sein Prozess beendet ist, nicht wenn er sein
  Ergebnis gedruckt hat – sein `finally` schrieb die Probe-Config (Tiefe 5) mitten in den nächsten Lauf, der dann mit
  Tiefe 5 lief. TaskStop beendet unter Windows nur die Hülle (bash/timeout); python und java darunter laufen weiter –
  `Stop-Process -Id`. Lange Läufe im Vordergrund mit Zeitgrenze statt im Hintergrund.
- Tunnel von der Konsole in freier Luft der flachen Welt: plant 0 Abschnitte (`abschnitte=0`), auch neben alten
  Probegängen – Steinriegel davor (`heap_probe.py`).
- Rückgängig-Stapel bis 2026-11-17: `bau.undo-tiefe` 5, trimUndo warf ältere Einträge STILL samt Dateien raus – fünf
  kleine Treppenstücke am 16.11. um 23:05 löschten den Hohlraum-Eintrag. Seit 2276c35 Tiefe 50 und Logzeile
  `aus_dem_stapel`; eine Config mit ausdrücklich `undo-tiefe: 5` behält 5 (Migration ändert keine Werte).
- TunnelScan.around braucht ein Ende ≤ MAX_WALK (128) vor dem Spieler – mitten in einem langen Tunnel gibt es keins.
  Für Raum/Kreuzung TunnelScan.passage nehmen (erkennt den Gang ohne Ende).
- Schnellgang: 192 Abschnitte stehen in ~3 s. Probe-Erwartungen „nach X s noch kurz“ gelten erst ab dem zweiten
  Stück.
- Bash: `cd X && (A) & (B); wait` – das cd gilt nur für die Hintergrundgruppe; B lief im Repo-Ordner und legte dort
  eine Ausgabedatei ab (2026-11-16, entfernt). Absolute Pfade in jeder Gruppe.
- Relay- oder Plugin-Jar vom Server mit einem frischen Bau vergleichen: Die Klassenbytes unterscheiden sich schon durch
  die Lambda-Nummerierung (anderer javac-Stand, lambda$serve$1 ↔ $2) und Zeilennummern – `javap -c -p -constants`
  beider Fassungen vergleichen, nicht die Prüfsumme.
- Host-Setup als Update löscht die alte Autostart-Verknüpfung ({userstartup}, [InstallDelete]): Am GPU-PC danach in
  der App „Mit Windows starten“ setzen, sonst läuft sie nach dem nächsten Windows-Start nicht von selbst.
- `/worldborder center 0 0` setzt die Mitte auf die Blockmitte (0,5); WorldBorder.isInside prüft den Block an seiner
  Ecke (BlockPos, javap Spigot 1.21.11) – der Block, durch den der Rand läuft, zählt noch als innen.
- Spigot-Probeserver: `locate` fängt ein Plugin ab („/locate [player]“) – `minecraft:locate` nehmen.
- org.bukkit.generator.structure.Structure hat eine statische Initialisierung über die Server-Registry: in
  Unit-Tests nicht ableitbar. WorldStructures nimmt deshalb eine Kastenquelle, der Test speist Kästen direkt ein.
- StructurePiece.getBoundingBox: Maximum einschließlich (javap Spigot und Paper reichen minX…maxZ unverändert durch),
  BoundingBox.contains schließt es aus – eigener Vergleich mit ≤.
- Log-Filter auf „Exception“ trifft „gui | ergebnis_nicht_gemeldet … CompletionException – reine Anzeige“ (INFO,
  Host-App fehlt) – auf SEVERE/ERROR filtern. Plan-Log „schacht=N“ zählt befragte Zellen, nicht nur abgebaute.
- Probewelten sind flach bei y −60: Dort wählt der Tunnel die Tiefenschiefer-Palette (y < 0), nicht Steinziegel –
  Erwartungen der Probe danach richten.
- `data get block … Items` über RCON kürzt lange Listen („{Slot:...“) – Kisteninhalt mit `execute if items block
  x y z container.* minecraft:<id>` zählen („Count: N“).
- Python-Änderungen mit vielen Anführungszeichen nicht als Bash-Heredoc (2026-11-16: „unexpected EOF while looking
  for matching `''“, nichts geändert) – Skript als Datei schreiben und ausführen.
- README.md liegt im Arbeitsbaum mit CRLF (Index LF, eol=lf): Ersetzungen vorher auf \n normalisieren.
- „lag“ als Verb wurde seit 2026-11-15 zu „Läg“ – Aussprache-Regeln immer mit deutschen Gegenbeispielen testen.
- Gegen PC-Zeiten messen täuscht: dev01 rechnet etwa 5-mal langsamer (Logzeiten 2026-11-15). Was auf dem PC 1 s
  dauert, steht dort 5–6 s im Hauptthread – große Pläne immer mit der Riesen-Lobby gegenmessen.
- `Long` als HashMap-Schlüssel aus `x << 32 | z`: Long.hashCode = x ^ z, auf Rastern ~100 Werte → Baumknoten. Mit
  ungerader Konstante multiplizieren (bleibt eindeutig).
- **GameRule bricht zwischen Spigot 1.21.11 und Paper 26.2:** In der Spigot-API ein Interface (Feld RESPAWN_RADIUS),
  in der Paper-API eine abstrakte Klasse (Feld SPAWN_RADIUS). Gegen Spigot übersetzt warf Paper erst
  NoSuchFieldError (Feld), dann IncompatibleClassChangeError „must be Methodref constant“ (GameRule.values()). Nur per
  Reflexion holen, setzen über World#setGameRule (Interface in beiden). Paper hat setGameRuleValue(String, String),
  Spigot 1.21.11 nicht mehr. Vor jedem neuen API-Typ mit javap in beiden Jars nachsehen, ob Klasse oder Interface.
- Gegenprobe für Portalrahmen ohne Spieler: `setblock <innen> fire` entzündet einen gültigen Obsidianrahmen wie ein
  Feuerzeug – Minecraft sagt so selbst, ob ein Rahmen taugt und welche Achse er hat.
- RCON-Antworten mit „→“ brechen `print` auf der Windows-Konsole (cp1252) – Helfer schreibt UTF-8 über stdout.buffer.
- Git Bash: `awk length` zählt Bytes, nicht Zeichen – Zeilen mit „“, –, × wirken zu lang. Länge mit Python prüfen.
- Warten auf „Exception“ im Probe-Log greift sofort: spark/oshi schreibt beim Start eine. Erst ab `bau | los` suchen.
- Nach `bauenbei` ohne Spieler sind die Chunks wieder entladen: `execute if block` meldet „not loaded“ – vorher
  `forceload add x0 z0 x1 z1`.
- `git archive` enthält `lib/` nicht (ignoriert): Baukopie vor `mvn -o package` mit `cp -r lib` ergänzen.
- Paper 26 legt Zusatzwelten unter `world/dimensions/minecraft/<name>/` ab, nicht neben `world/`. Eine Probe, die
  `$SRV/<name>` löscht, lädt still die alte Welt samt alter Generator-Einstellungen (2026-11-15: zwei Läufe
  umsonst, bis die y-Werte gleich blieben).
- forceload bleibt in der Welt gespeichert: Ein Prüfstand, der je Bauteil an neuer Stelle `forceload add` setzt und nie
  entfernt, lässt den nächsten Start alle diese Chunks laden – Spigot-Testserver mit -Xmx2G lief 2026-11-15 nach der
  Lag-Messung (804 Bereiche) in einen OutOfMemoryError und hielt beim Stopp nicht an. Aufräumen: mit -Xmx8G starten,
  `forceload remove all` (je Dimension), stoppen. Prüfstände geben ihren Bereich nach jeder Messung frei.
- PlotSquared meldet bei jedem Stopp auf 26.2 16 × „CRITICAL ERROR IN VALIDATION TASK“/„PLOT AREA CANNOT BE NULL“ –
  beim Zählen von ERROR nach einem Neustart herausrechnen (gemessen 2026-11-08: jeder Stopp seit dem Update).
- Probe-Skripte, die auf „Done (“ im latest.log warten, sehen nach einem Neustart zuerst das ALTE Log und legen zu
  früh los (2026-11-08: 45 Befehle ins Leere, je 120 s Warten). Auf eine Zeile mit der neuen Uhrzeit warten oder die
  Zeilenzahl vorher merken. Und TaskStop beendet nur die äußere Shell – das Skript selbst läuft weiter (zweimal
  passiert): danach per Kommandozeile suchen und beenden.
- Verriegelte Verstärker (Master-Slave, Register) fangen beim Bau Zwischenzustände ein – je nach Richtung. Deshalb
  löscht refreshRedstone sie nach dem Bau (2026-11-14). Wer eine Schaltung baut, die mit einer 1 im Speicher starten
  soll, muss die nach dem Bau setzen, nicht im Plan.
- Während MachineProbe läuft, nie im Arbeitsordner `mvn test`/`test-compile`: Probe und RconSend laden aus
  target/classes und target/test-classes (ClassNotFoundException mitten im Lauf). Bauen in einer Kopie
  (`C:\swb\bm-bau`). Und Klassenpfade in Probe-Skripten als `C:/…`, nie `/c/…` – Java unter Windows kennt
  MSYS-Pfade nicht.
- Dorfbewohner in Panik schlafen nie (VillagerGoalPackages.getPanicPackage hat kein SleepInBed) – und ohne Schlaf in
  den letzten 24 000 Ticks ruft keiner einen Golem. Eine Eisenfarm, deren Zombie man im Stehen sieht, läuft
  höchstens einen Tag. Proben, die `last_slept` vorgeben, verbergen das: echten Schlaf abwarten (MachineProbe
  „#schlaf“).
- Wasser, das während des Baus neben noch offenen Feldern liegt, fließt zum nächsten Loch und bekommt danach keinen
  Tick mehr: Bahn bleibt trocken. Quellen eine Baulage über die Bahnen legen (der Plan baut von unten) und in
  Nischen (Steg, Deckel), sonst laufen sie seitlich über.
- Ohne Spieler in der Nähe friert Spigot Mobs auf trockenem Boden ein (Aktivierungsweite 32): ein hängender Zombie
  in der Probe ist dann kein Wegsuch-Fehler.
- Golem (1,4 breit) an Wänden aus vollen Blöcken: Mit 0,2 in der Wand bleibt er am nächsten Wandblock hängen.
  Seitenwände von Golemfläche und Grube als Glasscheiben.
- `python -I` ignoriert PYTHONIOENCODING – für UTF-8-Ausgabe `python -I -X utf8`.
- Gegenprobe: „rot“ wegen eines Übersetzungsfehlers beweist nichts. Erst eine Assertion-Zeile zählt (scratchpad-Werkzeug
  gegenprobe.py zeigt sie); zweimal passiert am 2026-11-08 (Befehlsprüfung im Schnellauftrag, {Daten} bei Vera).
- Test-Proxy für `Player` scheitert (net.md_5.bungee.api.dialog.Dialog fehlt im Testpfad) – in Ereignis-Tests null als
  Spieler nehmen.
- Relay-Tests: abgebrochene Anmeldungen vor dem hallo zählen bei HandshakeGate als Fehlversuche – eine zweite
  Anmeldung derselben Adresse kurz danach wird zurückgesetzt (so gewollt).
- Spigot meldet jede data-merge-Zeile von der Konsole an alle Ops; Paper nimmt den stillen Absender.
- Nach jedem Minecraft-Update `src/main/resources/tiers/*.txt` neu erzeugen (Befehl im Dateikopf), sonst brauchen neue
  Blöcke die Stufe Befehle.
- llama.cpp-Grammatik: Wiederholungsgrenzen über 2000 werden still zu `*` (unbegrenzt) – `str1000` liegt darunter.
- Der Datengenerator (Bundler) entpackt ins Arbeitsverzeichnis – immer in einem Temp-Ordner starten.
- `Map.copyOf` hat je JVM eine andere Reihenfolge (6:6 in 12 Starts) – wo Reihenfolge zählt, `LinkedHashMap`.
- Surefire: `-Dtest=A+B` wählt KEINEN Test (sieht grün aus) – `-Dtest=A,B` nehmen.
- Eine Truhe unter Glas GEHT AUF (Glas leitet nicht, ChestBlock), erreichbar ist sie aber nur, wenn die Oberseite oder
  eine Seite an Luft grenzt – Glas lässt keinen Klick durch. Die XP-Farm hatte das vom 2026-11-07 bis 2026-11-08, die
  MachineProbe sah es nicht (sie liest den Inhalt per Befehl). Prüft jetzt MachinesTest.everyChestCanBeOpenedAndReached.
- Probe-Mobs, die `kill` trifft, lassen Beute fallen: Wer danach „noch keine Beute in der Truhe“ prüft, nimmt einen
  Gegenstand, den sie nie tragen (xp_farm: Diamant statt Knochen).
- Spielregeln heißen seit 1.21.11 snake_case: `gamerule random_tick_speed`, nicht randomTickSpeed („Incorrect
  argument“). MachineProbe wertet abgelehnte Befehle jetzt als durchgefallen – vorher lief der Ablauf weiter.
- Weizen hält sich nur bei Licht ≥ 8 (CropBlock.canSurvive), sonst platzt er beim nächsten Nachbar-Update ab. Die
  Probe räumte nur 8 Blöcke hoch; Dach einer alten xp_farm-Probe darüber → 4 Felder fehlten, nur in Richtung Nord.
  Jetzt räumt sie bis zur höchsten Maschine (CLEAR_HIGH).
- Dorfbewohner sehen den Zombie nicht durch Glas (hasLineOfSight prüft Kollisionsformen): Wände in Augenhöhe offen
  lassen (Spalt unter dem Dach). Zombie unter Klarglas verbrennt am Tag (Himmelslicht 15) – getöntes Glas.
- Wasserfläche mit Quellen nur vorn: Endet das Wasser vor der Grube, bleibt ein Golem, der zum Zombie will, an der
  Kante stehen; fließt es am Ende in ein Loch, zieht es (26.2-Kopie) in eine einzige Bahn, die anderen bleiben
  trocken, und ein Golem auf dem Trockenen steht ohne Spieler still. Beides hält die Farm ganz an (ein lebender Golem
  sperrt den nächsten). Eisenfarm daher als Teich aus Quellen, nur die letzte Reihe fließt in die Grube.
- MachineProbe-Quader für Mobs wächst je Achse um bis zu 2 Felder in WELT-Richtung (Abstand + 0,99, Minecraft legt
  +1 drauf) – in Richtung Nord also zur Fläche hin. Golem-Quader deshalb nur unten in der Grube (y 3–4).
- Schlafende Bewohner haben das Auge 0,9 über dem Bett (Pose SLEEPING, 0,2 über der Liegehöhe 0,6875): Durch das
  Glas über dem Bett-Fußende sehen sie den Zombie nicht – nachts keine Golems (26.2-Kopie: 150 s ohne, am Tag 2–26 s).
  Eine obere Stufe hilft NICHT: Die Sichtlinie steigt innerhalb des Blocks über +0,5. Mein Fehler 2026-11-08: nur an
  der Blockkante gerechnet; bestandene Nachtläufe kamen von frisch beschworenen, noch wachen Bewohnern. Die Probe
  stellt deshalb Tag ein (`time set day` geht auf 26.2 und Spigot).
- Minecraft 26.x: `time query daytime` gibt es nicht mehr („timeline“), Prädikat `time_check` verlangt `clock`.
  `time query gametime` geht weiter. RCON schneidet lange `data get`-Antworten mit „…“ ab – Pfad angeben
  (`Brain.memories."minecraft:last_slept"`), sonst fehlen Einträge scheinbar.
- dev01-Kopie mit EssentialsX: `item`, `kill`, `time`, `weather` sind dort Essentials-Befehle – Probe schickt alles mit
  `minecraft:` (MachineProbe.vanilla); Antworten mit „Fehler:“/„Befehlshilfe:“ gelten als abgelehnt.
- Golems laichen nicht auf Glas und brauchen 3 Felder Luft (Füße dürfen im Wasser stehen), Bewohner müssen in den
  letzten 24 000 Ticks geschlafen haben – Probe gibt `last_slept` in der Zukunft vor statt eine Nacht zu warten.
- Lava, die von oben in Wasser fließt, macht das Wasser zu Stein; Bruchstein nur mit Wasser seitlich (oder oben).
- `setblock` Kaktus prüft sich nicht selbst (erst beim Nachbar-Update); `setblock` Frucht neben dem Stängel hängt ihn
  nicht an – die Probe setzt den angehängten Stängel (`plan:`-Block, wird mitgedreht).
- Beobachter auf dem Fruchtfeld sähe den Kolbenkopf und schaltete im Kreis – er schaut auf den Stängel.
- Bukkit `addPassenger` (wie `/ride … mount`) erzwingt das Einsteigen: Ein Boot nahm im Test einen dritten Gast.
  Plätze selbst zählen (CompanionRide.seats). Und: Ein Passagier, den man teleportiert, fliegt aus dem Sitz.
- `Material.isItem()` braucht in Unit-Tests ein Server-Register (NPE) – Namenslisten wie ItemNamesTest.ITEMS nehmen.
- Paper 26.x: World#getWorldFolder() ist für jede Welt der Spielstand-Ordner (`./world`); Regionsdateien liegen unter
  `world/dimensions/<ns>/<name>/region`, datapacks weiter unter `world/datapacks`. Nie „ersten Treffer“ nehmen.
- Selektor-Quader `dx/dy/dz` endet bei x+dx, nicht am Ende des Blocks x+dx (gemessen: Mob bei z=-8,3 außerhalb von
  `z=-10,dz=1`) – für ganze Blöcke +0,99.
- gc.log auf dev01: Laufzeit hinter der Uhrzeit und ab etwa 2 s mit Komma (`[…+0100][4,653s][info][gc]`, die ersten
  Zeilen noch `[1.575s]`), die Belegung („…M->460M“) nur in der Zeile mit dem Tag `gc`, nicht in `gc,start`. Ein
  Filter auf `^\[[0-9.]+s\]` findet nichts – so stand er im Deploy-Skript, vor dem Lauf 2026-11-08 korrigiert. Ob
  eine Sitzung kompakte Objektköpfe hatte, steht in ihrem gc.log: `UseCompactObjectHeaders 1`.
- Simple Voice Chat: Nie je Satz einen neuen AudioPlayer – der schickt am Ende eine Endmarke, der Client leert und
  schiebt 100 ms Stille ein. Vorlauf nicht über ~25 Rahmen: der Client hat 32 Puffer und springt sonst vor.
- Folgen: Ziel nie an die Blickrichtung hängen (Spieler schauen zu Vera → Ziel springt auf die andere Seite) und ohne
  Weg nie den NÄCHSTEN Spur-Punkt samt „weiter auf der Spur“ (alte Wegstücke). CompanionFollowTest prüft beides.
- Java-Regex: \b ist ohne (?U) reines ASCII. „schließ“ und „öffne“ fanden keine Wortgrenze (CompanionUseTest
  2026-11-07). Ältere Muster mit (?iu) und Umlaut am Wortrand (z. B. in CompanionIntent) haben dieselbe Lücke.
- Hilfebuch blättert per Befehl (/hilfe seite N → Buch neu, Seite N vorn, HelpBook.rotate): Nutzer konnte nicht
  blättern, obwohl die Messprobe (dev01 20:18) 12 Seiten und korrekte change_page zeigte – Ursache im Client nicht
  geklärt. Das Buch im Inventar ist nur Platzhalter; Rechtsklick (Interact DENY) öffnet das frische.
- Hilfebuch: Buchklicks können nur run_command/change_page – kein Chat, kein suggest_command. Darum /vera <satz>
  (onChat mit „Vera, …“) und /hilfe tippen <befehl> (Chatzeile mit suggest_command). Buchseite ≈ 14 Zeilen × 19
  Zeichen; HelpBookTest prüft jede Seite, auch mit 40 Spielern/60 Warps (Folgeseiten).
- dev01 aufgeräumt 2026-11-05: GWarp.jar und GWarp-Daten nach sicherung/ (Warps übernommen in warps.json),
  Cartographer2, LaggRemover, Updater, PluginMetrics als sicherung/verwaiste-pluginordner-2026-11-05.tar.gz.
- Vera hört nur, wenn sie da ist (canHear, Datenschutz) – und per Sprache kann man sie deshalb nicht rufen. Die Liste
  begleiter-wiederkommen.json (ComeBackList) muss beim Beitreten STEHEN bleiben und wird bei jeder Änderung gespeichert.
  Vorfall 2026-11-05 17:27: Liste leer, Spieler im Voice-Chat, niemand hörte zu. Gegenprobe: remove beim Beitreten → 2/3 rot.
- Throttle.start() gibt die Drossel NICHT selbst frei – end() ist Sache des Aufrufers. Wer eine Antwort nicht durch
  einen eigenen end()-Pfad führt (Vorausladen), nimmt startReleasing(). Vorfall 2026-10-31 auf dev01: nach dem ersten
  Vorausladen (HTTP 502) stand der Weltbau bis zum Neustart (Log: kachel_gewählt, danach keine karte_anfrage mehr).
- Lehrset und Grammatik: PlanParser ist großzügig, die Grammatik im Host nicht – sie legt die Feldreihenfolge je
  Schritt fest und begrenzt Werte (height ≤ 48, design ≤ 700 …). 2026-11-01 hätte sie 214 von 4000 Lehrplänen nie
  erzeugen können (QuickOrders.skyscraperJson: window_block vor from). Seitdem prüft PlanJudge.rejection zuerst
  SchemaCheck (gegen plan-schema.json, das GrammarDriftTest mit der GBNF gleich hält), PlanText sortiert die Felder,
  PlanDecompiler baut keine Zylinder über 48. Gegenprobe: ohne Umsortieren DatasetGeneratorTest
  .quickSkyscraperIsRenderedInGrammarOrder rot; Lehrset danach 0/4000 nicht grammatikgerecht (Python-Gegenleser).
  Im Plugin harmlos (Schnellaufträge laufen nicht durch die Grammatik), deshalb dort nicht geändert.
- Lehrset-Katalog (T-019, 2026-11-21): PlanJudge zählte jeden Namen mit `Material.getMaterial != null` als Block –
  auch Gegenstände (redstone, potato, string); ohne allIds keine ItemNames-Suche. Jetzt `PlanJudge.SERVER_BLOCKS`
  (BlockTiers.knownBlock + tiers/block-ids.txt), also dieselbe Auflösung wie BukkitBlockCatalog. Gemessen: Lehrset
  0/4000 geändert, PlanBench 2/66 Urteile. Gegenprobe: PlanJudgeItemTest mit altem Katalog 3/5 rot. Falle:
  `PlanJudge.loadBlockIds` liest block-ids.txt ein zweites Mal (BlockTiers.knownBlocks ist paketprivat) – beide
  synchron halten. `LlmProbe` nutzt seit T-021 denselben Katalog.
- Testaufträge sind auch als Teilstück gesperrt (DatasetGenerator.nearBench): „Bau mir einen Wachturm aus Holz“ stand
  vorher fast wörtlich im Lehrset.
- Lehrset: 21 % der Einträge (793 von 3805, 2026-11-01) sind Aufträge, die das Plugin als Schnellauftrag ohne LLM
  baut (Bauformen, Hochhaus, Maschinen, Sonderbauten). Sie lehren die Ops, kommen beim LLM aber so nie an.
- `PlanCompiler.needsSupport` sucht Wortteile („lantern“, „torch“ …). Seelaterne und Kürbislaterne sind Vollblöcke
  und stehen deshalb ausdrücklich in `FULL_BLOCKS` (seit 2026-11-01; vorher „ohne Halt“ gemeldet und von PlanReview
  weggelassen). Neue Marker nur nach Abgleich mit allen Blocknamen des Servers, sonst fällt wieder ein Vollblock hinein.
  Gegenprobe: PlanLinterTest.seaLanternAndJackOLanternAreFullBlocks ohne Fix rot, mit grün; an den exakten
  Rückübersetzungen tower.schem „ohne Halt“ 88 → 0 (schwebend 6 → 0), <eigener Bau>.schem 64 → 46.
- Vorbild-Bericht: `java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.VorbildImport
  <ordner>` – Vorderseite dort immer s, nur zum Messen.
- Messgerät gegen eine ältere Host-Fassung: Bau `bh-runde5` (2026-10-29) kappt den Kontext im Katalog auf 8192, eine
  „eigene“ GGUF ebenfalls; der Prompt eines Testauftrags hat 6267 Token, max_tokens wird dann auf ~1925 gekappt
  (Host-Log `max_tokens auf 1925 gekappt`). Die Zahlen gelten für diesen Kontext; auf dem GPU-PC wählt die App mehr.
- Das Messgerät läuft ohne Recherche – die 13 dev01-Verwerfungen wegen Referenzhöhe misst es nicht.
- Maschinenwunsch: RequestHints zählt die Wörter aus `MachineHall.WORDS`/`CPU_WORDS` als ganze Wörter mit (seit
  2026-11-01). Vorher fehlten „fabrik“ und „computer“, der Plan zu „bau mir eine Fabrik“ verlor in PlanReview alle
  Anlagen (15 von ~450 Lehrset-Versuchen verworfen). Gegenprobe: RequestHintsTest ohne Fix 2 rot, mit Fix grün; Lehrset
  danach 0 Verwerfungen. Neue Maschinenwörter nur in MachineHall eintragen, nicht in einer zweiten Liste.
- `plugins/Baumeister/karten/` gehört dem Stadt- und Weltbau (OSM-Zwischenspeicher). Bilder der Minikarte liegen
  deshalb unter `minikarte/`.
- Vorab-Scan liest Regionsdateien, während der Server sie schreibt: Ein halb geschriebener Chunk zählt als
  übersprungen, nicht als Fehler; die Merkliste (minikarte/<welt>/gescannt.properties, Änderungszeit:Größe) holt ihn
  beim nächsten Durchgang. LZ4-Regionen (region-file-compression=lz4) liest der Scan nicht – dev01 nutzt deflate.
  Eigene Dimensionen (Erd-, Geländewelt) liegen unter <welt>/dimensions/<ns>/<name>/region, nicht unter region/.
- Minikarte-Übersicht: Plattenzugriffe NUR über den einen io-Thread – zwei Threads könnten eine ältere Fassung einer
  Region nach der neueren schreiben. Die Platte füllt beim Laden nur Lücken; was seit dem Start gelesen wurde, ist
  neuer (OverviewStoreTest).
- Minikarte: liest nie einen ungeladenen Chunk und ruft nie `getTPS` – genau das hat Cartographer2 auf dev01 den
  Server-Thread gekostet (docs/server.md). Lesen nur im Tick-Budget (`karte.ms-pro-tick`), nie in render().
- PlanAutoFix richtet nur Bauqualität. Überbau-Schutz, Ausdehnung, Blockzahl und Rechte laufen weiter NACH den
  Reparaturen auf dem fertigen Bau (fail-closed). Wer eine Reparatur hinter checkOverbuild schiebt, baut ungeprüft.
- Tests der Reparaturrunde brauchen einen Fehler, den PlanAutoFix nicht richtet (eingemauertes Wasser) – mit
  Vollklotz oder Schwebendem prüfen sie seit 2026-10-31 nichts mehr.
- `fill` mit `hollow:true` ist im Compiler massiv (nur cylinder/sphere kennen hollow), der Linter hält es aber für
  hohl – PlanAutoFix macht aus einem massiven fill deshalb op `hollow`, nicht hollow=true.
- Paper + Cartographer2: Auf dev01 hielt Cartographers TPS-Abfrage (jeden Tick `Bukkit.getTPS`, unter Paper teuer)
  den Server-Thread bei 100 % ohne Spieler; auf der Testkopie nicht nachstellbar (1 % über 20 min). Ein Plugin, das
  jeden Tick `getTPS` ruft, unter Paper zuerst verdächtigen – Stichproben mit `jcmd <pid> Thread.print`.
- Rückführung (Akkumulator): Ein offener Speicher in einer Schleife rechnet im Kreis, solange er offen ist – nur
  Master-Slave (zwei Riegel, gegenläufig) addiert genau einmal je Takt. Gemessen, siehe oben.
- Taktschiene neben fremdem Draht: Hinter der Summenlampe des Volladdierers ist x 15 frei, x 16–19 ist Draht des
  Übertragsnetzes – der Akkumulator beginnt die Taktschiene deshalb eine Reihe weiter hinten.
- MachineProbe räumt in Scheiben: bei 110 breiten Bauteilen (Radius 112) wären 16 Spalten über der fill-Grenze
  32 768; die Scheibenbreite richtet sich jetzt nach dem Radius.
- Register/D-Speicher: Daten erst ändern, wenn der Takt aus ist (Riegel greift 2–3 Ticks nach der Flanke). Eine
  Probe, die beides im selben Schritt schaltet, misst einen Bedienfehler, keinen Schaltungsfehler.
- Bauteilgröße: Logik-Bausteine dürfen bis 57 breit und 44 tief sein (Raster des Logik-Compilers); die frühere
  32er-Grenze stand nur im Test.
- Client-Spieltest: Alles, was die Mod anfasst, über ctx.runOnClient/computeOnClient (Test-Thread); Cheats per
  adjustSettings(setAllowCommands) – `/op` gibt es nur auf Servern; `-Dfabric.client.gametest.disableNetworkSynchronizer`
  (die Mod sendet Eingabe-Pakete selbst). Gradle meldet BUILD SUCCESSFUL auch, wenn das Spielfenster geschlossen
  wurde – Ergebnis an „bau | ende“ und den Bildschirmfotos ablesen, nicht am Exitcode.
- Minecraft 1.21.11: `ResourceLocation` heißt `Identifier`; Wandfackeln teilen den Anzeigenamen mit der Stehfackel
  (nach dem Registernamen unterscheiden); ein gesetzter Block erscheint im Client erst mit der Antwort des Servers.
- Paper meldet beim Start flacher Testwelten „ERROR: No key layers in MapLike[{}]“ (leere generator-settings) – kommt
  nicht vom Plugin, beim Zählen von ERROR herausfiltern.
- Schnellauftrags-Antworten höchstens 200 Zeichen: BuildService.acceptPlan kürzt mit Texts.clean(…, 200) mitten im
  Satz (BunkerTest.repliesFitIntoTheChatLine).
- Testserver-Welten sind flach (Boden −61): Bunker und Fort Knox dort testen heißt Absage „zu tief“ – für echte
  Läufe die normalwelt nehmen (in bench-paper: level-name=normalwelt).
- Wikimedia drosselt ohne Kontakt im User-Agent schon nach wenigen Abfragen (HTTP 429) – llm.recherche-kontakt.
- Paper: getLogger() eines Plugins ist der Logger mit dem Plugin-Namen; ein Handler daran, der in getLogger() loggt,
  ruft sich selbst auf.
- PlotSquared + Spigot: Jedes synchrone Chunk-Laden (setBlockData/getBlockAt in entladenem Chunk, auch
  `addPluginChunkTicket`, das laut Bytecode sofort getChunkAt ruft) läuft durch PlotSquareds getEntities. Neue Pfade,
  die Blöcke anfassen, deshalb immer mit isChunkLoaded + gedrosselten Tickets bauen (Muster CityJob/RestoreJob).
- Ein neuer Stadtbau auf dem Celeron mit 16 Helfern hielt Ticks bis 6,2 s an (dev01 2026-10-30) – `/bm helfer 16`
  dort nicht empfehlen.
- Testwerkzeug RconSend fügt alle Argumente zu EINEM Befehl zusammen – je Befehl ein Aufruf (2026-10-29: „los …“ und
  „bm tempo 100“ kamen als ein Befehl an und scheiterten).
- Ein fortgesetzter Stadtbau startet wieder mit `stadt.blöcke-pro-tick` – `/bm tempo` gilt nur bis zum Neustart.
- Weltbau auf dem Land: Kachel baut in 13–19 s, aber 20 s Drossel + 25–42 s Overpass-Antwort (bei Überlast Minuten)
  je Kachel (Testserver 2026-10-29) – Vorausladen kann erst nach der Drossel fragen und hilft dort nicht; nur bei
  Stadtkacheln, die länger als 20 s bauen. Nächster Hebel wäre mehrere Landkacheln in EINER Abfrage (offen).
- dev01 hatte `stadt.blöcke-pro-tick: 500` (vorsichtig gesetzt 2026-10-28, vor der Lastregelung). Die Zeitgrenze
  `ms-pro-tick` (25 ms, CityJob.work prüft die Uhr im Takt) begrenzt den Takt unabhängig davon – 2000 lässt den Celeron
  nur bis zu dieser Grenze arbeiten.
- Zellen mit Materialnummer TerrainFit.PLINTH (−1) stehen in keiner Palette – jeder, der Plan-Zellen liest, muss das
  abfangen. Anzeige-Code (CityRaster) läuft nur noch hinter einem catch in StagePrep: Er darf den Bau nie aufhalten.
- Host-Server begrenzt jede Anfrage auf 256 KiB (http.hpp kMaxBodyBytes) – ein 512²-Raster als Base64 (≈ 350 KB) prallte
  mit 413 ab; deshalb Rasterseite 360. Und 204 geht dort nicht (Content-Length wird immer gesetzt).
- Den selbst gebauten Host kennt das Bildschirmwerkzeug nicht – Fensterbild über PrintWindow (PowerShell + user32).
- Zwei QApplication in einem Testprogramm stürzen ab – Widget-Tests der Stadtansicht laufen als eigenes Programm.
- Hohe Welten und Speicher: `forceload` hält um jeden Chunk einen Ring von Vorstufen (bis Stufe 44 → ±13 Chunks),
  in 4 064-hohen Welten je ~114 KB. Messpunkte nach der Messung immer `forceload remove all`, sonst bleiben sie über
  Neustarts. Heap-Messung mit `jcmd <pid> GC.run` + `GC.heap_info`; ohne GC zeigt G1 Müll als belegt.
- Ein Speicherwächter über MemoryPoolMXBean.getCollectionUsage hängt auf ruhigen Servern (G1 räumt die alte
  Generation nicht auf, Wert blieb 9 min stehen); dev01 hat -XX:+DisableExplicitGC. Deshalb zählt HeapGuard geladene
  hohe Chunks.
- `/bm wo` und `/bm reise` antworten asynchron – über RCON kommt keine Antwort an. Messen mit EarthSurfaceProbe.
- Testserver hart beenden, während er speichert, beschädigt Chunks. Erst `stop` und warten.
- Hebel per `setblock` schalten löst keine Updates am Trägerblock aus → Funktionsprobe setzt Redstone-Blöcke an die
  Teststelle (bei der Kolbentür an den Block, an dem der Hebel hängt).
- RCON `execute if block` ohne Spieler: „That position is not loaded“ – Probe muss Chunks per `forceload` laden und
  jede andere Antwort als Fehler werten (erste Fassung zählte sie als Block, Karte voller '^').
- Overpass/Nominatim sind öffentliche Dienste: keine Testschleifen dagegen, Zwischenspeicher nutzen.
- dev01 pausiert nach 60 s ohne Spieler – Konsolen-Bauten (`bauenbei`) laufen dort erst weiter, wenn jemand online ist.
- llama-server ohne `--cors-origins` erlaubt jeder Webseite Zugriff (Warnung beim Start).
- Unbekannte Blocknamen → Alias/Ersatzblock; Aliase nur aus echten Plänen in `pläne/` nachtragen.
- **Datapack-Dimension nie wieder entfernen:** level.dat speichert sie; ohne Datapack bricht der Serverstart ab
  („Failed to get element ResourceKey[minecraft:dimension_type / …]“, gemessen 2026-10-28). Rückweg nur mit
  `--safeMode` oder einer gesicherten level.dat.
- Ein von einem Plugin gesetzter Verstärker prüft seinen Eingang nicht → nach dem Bau Glas daneben setzen und wieder
  entfernen (BuildJob.refreshRedstone). Ein Draht neben einer Fackel biegt zu ihr ab.
- Die Probe läuft aus `target/classes` – während sie läuft, nicht dort bauen (Kopie im scratchpad).
- `World.setSpawnLocation` ist in der Datapack-Dimension wirkungslos (gemessen 2026-10-28: Plugin setzte den
  Spawn bei jedem Start neu, `mv info` zeigte weiter 8/−2032/8; Spawn der Hauptwelt unberührt). Ein Spawn auf festem
  Boden für /mvtp geht nur über Multiverse selbst (`/mv setspawn` im Spiel an der Stelle).
- Blöcke zählen per „fill … sponge replace X“ und zurück: Blumen auf grober Erde fallen ab, sobald die Erde kurz
  Schwamm ist; Schwamm saugt Wasser auf. Nur Blöcke zählen, auf denen nichts steht. Schilder nie so (Text weg) –
  Schilder per „clone … filtered“ finden (zählt, ohne die Quelle zu ändern).
- `gamerule max_block_modifications` (1.21.11, früher commandModificationBlockLimit) gilt je Welt:
  „execute in <welt> run gamerule …“. `forceload` höchstens 256 Chunks je Befehl.
- Die Tempo-Testkopie hielt 529 alte forceload-Chunks aus Messungen – nach Messungen immer „forceload remove all“ in
  JEDER Welt.
- Overpass-Altbestand vor den Etappen („lat_lon_radius.json“) wird bis zu 30 Tage weiter benutzt (Hepberg auf dev01):
  dort fehlen Gleise, Zäune, Bäume und Laternenwege, bis die Datei abläuft. Zum Testen beiseitelegen.
- Messen auf dem Laptop: Parallel laufende Arbeitskopien und Server erzeugen Fremdlast (frische Chunks streuen
  43–93 s) – auf vorerzeugten Chunks und abwechselnd alt/neu vergleichen. Den eigenen Server über den RCON-Port finden.
  RCON-Probe erst nach „RCON running on“ senden. Der Scratchpad wird von parallelen Agenten geteilt: eigener Unterordner.
- Regen dunkelt Tageslichtsensoren ab: Proben mit Sensor setzen vorher `weather clear`. Schläft der Rechner während
  einer Probe, steht der Server („Can't keep up“), die nächste Richtung läuft in den alten Bau – Richtung neu starten.
- Item-Sortierer: Platzhalter im Filtertrichter müssen umbenannt sein, sonst zieht er die Sorte mit; bis er gefüllt
  ist, zieht er alles.
- Verknüpfungen unter Windows: Junctions erkennt nur `isOther()` – deshalb `UndoSpool.isLink` und nie `Files.walk`
  zum Löschen. Rückgängig-Zustände nie ungeschützt mit `Bukkit.createBlockData` im Timer parsen (StateCache).
- Plugin-Chunk-Tickets gibt es nur einmal je Plugin und Chunk: `removePluginChunkTicket` nimmt auch die eines zweiten
  Halters weg. Bukkit-Takte aus `finish()` nur mit `plugin.isEnabled()` anmelden (onDisable → finish).
- Etwas je Etappe „über alle Gebäude“ zu rechnen fällt mit Testorten (2 Teile) nie auf – Lasttests mit vielen
  building:part (BuildingGroupLoadTest).
- Elevation als Methodenreferenz liefert über die Default-Methode vier gleiche Ecken (Mischwert) – wer Ecken braucht,
  überschreibt beide Methoden. Totes Meer und Jordantal liegen durch `meerestiefe-max` auf y −200 (Kappung).
- `stadt.overpass-url` (Einzeladresse) wirkt seit 2026-11-01 wieder (CityService.overpassUrls liest die Datei ohne
  JAR-Vorgaben). Steht in der Datei eine eigene Liste, gewinnt sie.
- Proben mit Ersatz-java beweisen nichts über JVM-Optionen: Die erste Vorprüfung in `start.sh` (-Xmx64m) fiel mit
  echtem JDK immer durch. `start.sh` muss LF haben. TickMonitor zählt lange Abstände erst nach 1 100 Ticks ohne
  Spieler als Pause (pause-when-empty-seconds=60).

## Deploy

Nur in der lokalen Fassung (Betriebsprotokoll des Servers).
## Offen
- Stadtleben v1 (T-020): auf dev01 aus, Nutzer schaltet selbst. Offen: Takt auf dem Celeron,
  sichtbare Tätigkeiten im echten Client, Arbeitsort an einem Bau nur per Unit-Test (live nur Warp und
  Reisepunkt), Paper mit EssentialsX nicht gemessen, Stehplatz auf einem Dach ≤ 12 über einem Warp nicht abgefangen,
  Listener-Test liest nur den Quelltext von onEnable (T-023). v2 (Händler, Fahrzeuge, Polizei, Bauen/Ernten mit Rechten)
  erst nach Rückmeldung des Nutzers zu v1.
- Tunnel gerade: vom Nutzer zu bestätigen – Röhre statt Halt über Tal/Wasser und Durchgraben von Mineschächten
  (beides ohne Rückfrage entschieden). Nicht im Spiel geprüft: Mitbauen in die Röhre (Ansage am ersten Fenster),
  Weiterbauen per Chat mit echtem Spieler; Nether-Festungen bleiben Halt.
- Tunnel/Brücke: Mitbauen mit echtem Spieler (vorausgraben, warten, nächstes Stück ohne Ende), Kürzen an einer
  Grundstücksgrenze (cutBefore), Vera als Auftraggeberin und „schneller/langsamer“ im Chat nur per Unit-Test bzw.
  gar nicht live – braucht einen Spieler. Vera gräbt nicht selbst (sie reicht an den Baumeister weiter).
- Runde E (2026-11-16): vom Nutzer zu bestätigen – kein Halt der Treppe nach unten vor Hohlräumen, Treppe nie quer in
  einen Gang (Weg: Treppe nach oben aus dem Gang), Kreuzung nur mit „trotzdem“. Nicht live geprüft: Fässer aus der
  Kiste bezahlt (Spieler ohne Admin), Rest neben die Kiste ohne Fass, Warteschlange beim Stopp (braucht Spieler),
  schacht_probe mit dem neuen Jar (braucht eine frisch erzeugte Welt).
- Rückgängig-Tiefe 50: vom Nutzer zu bestätigen, dass nur 5 Einträge im Speicher bleiben (ohne Rückfrage
  entschieden).
- Lobby: Eine eigene Schematic namens „lobby“ würde vom Schnellweg überdeckt (QuickOrders vor den Dateien). Auf dev01
  gibt es keine; bei Bedarf exakten Dateinamen Vorrang geben. `/bm einrichten` steht (d7772b1).
- Portale: Sprung mit echtem Spieler, Piglin-Sperre und Schild über einem Portal in ungeladenem Chunk (Entfernen von
  der Konsole aus der Ferne lässt das Schild stehen, Log `schild_nicht_geladen`) nicht im Spiel geprüft.
- Pflanzen fallen aus Technik-Wiki-Farmen ab (2026-11-15, DiffProbe, Spigot): feldfruchtfarm_2 86–91 Karotten → Luft + 28 → Wasser,
  netherwarzenfarm_2 56 Warzen, kakaobohnenfarm_1 84 Kakao, tropfsteinfarm_2, chorusfruchtfarm_2 – deterministisch, mittags gleich.
  Belegt: Halt darunter vorhanden (Seelensand, Ackerboden); Mini-Schematics (Warze/Karotte/Kakao, NbtAccess) bauen fehlerfrei.
  Widerlegt: Licht; fließendes Wasser (Schematics tragen je Farm einen Strang level 1–7, aber Flüssigkeiten zuletzt + ohne
  Fließstufen änderte nichts – zurückgenommen). Hinweis: netherwarzenfarm_2 hatte im Leerlauf trichter=13, also Drops nach
  dem Bau (außerhalb des Fallschutz-Fensters). Aufgeklärt (2026-11-15 01:5x): direkt nach dem Bau stehen in den 56 Warzen-
  zellen Wasser und eine Warze als Gegenstand, 8 s später ist das Wasser weg – die Farm erntet sich beim Bauen einmal selbst
  (ihr eigener Wasser-Mechanismus, vermutlich von den gesetzten Blöcken ausgelöst; Gegenstände in den Trichtern). Kein Baufehler,
  nicht behoben. Wer sie „reparieren“ will: Ernte-Auslöser erst nach dem Bau scharf machen – nicht untersucht.
  111 von 804 Schematics weichen nach dem Bau ab, 16 über 10 % (fast alle Farmen).
- xp_farm Süd (2026-11-14): Skelett kam in der Regression in 4 Wartezeiten nicht unten an; danach 5/5 BESTANDEN
  (Spigot, ohne Last) – seltener Ausreißer der Mob-Bewegung, Ursache nicht gesucht. Erst bei Häufung die Bahn ändern.
- Im Spiel nicht geprüft: Buchkapitel Schnellbau/Suchen/Sammlung/Mods/Stimme, Katalogfenster, Probesatz, Cartoon-Ton.
- Verity JE 6.0.0 (2026-11-08, umgesetzt: Erz-Familien, Ortssuche, Ollama – siehe Gemessen; mit REA und javap nur gelesen, verity-6.0.0.jar auf dem Desktop): Forge 1.20.1, „All Rights
  Reserved“ – kein Code, keine Grafik übernehmen. LLM über LangChain4j (Ollama, OpenAI, Gemini, Mistral), Sprache lokal
  mit sherpa-onnx, Ollama-Installer im Mod. Werkzeuge: give_item, get_nearest_ore_location, get_nearest_village,
  get_nearest_nether_fortress, teleport_to_player, follow, memory, place_torch, blast_monsters, Karma. Ideen offen:
  Erz-Suche für Vera, Karma; Ollama als einfacherer Unterbau für fremde Server (OpenAI-kompatibel) – ob unser
  json_schema dort greift, ist NICHT geprüft.
- Schematics: Funktion der ausgeschnittenen Wiki-Schaltungen nicht gemessen (nur baubar); alte .schematic (MCEdit)
  und große Schematics (Olymp, bedwars) nur per Unit-Test, nicht live gebaut; Vorbilder fürs Lehrset aus den Wiki-Bauten erst nach Klärung, ob ein trainiertes Modell unter
  „share alike“ fällt. Gelände: Senken-Regel (WaterTable) noch nur in der Erdwelt. Logik-Ausgänge ohne Schilder.
- Bedienhinweise nicht von einem Spieler im Spiel gelesen. Eisenfarm: Betten und Bewohner durch den Spalt unter dem
  Glasdach (von der vorderen Glaswand aus) und der Zombie über das getönte Glas sind am Bau gerechnet (Sichtlinien),
  nicht ausprobiert; im Überlebensmodus bekommt man Dorfbewohner dort kaum hinauf.
- Farmen auf dev01, die vor P4 (2026-11-08) gebaut wurden, haben die alte Eisenfarm (läuft höchstens einen Tag)
  bzw. die alte XP-Farm (Bahn kann trocken bleiben). Sie bauen sich nicht selbst um: neu bauen lassen.
- Abenteuer nicht im Spiel geprüft: ob Mannequin-Schläge Monster sichtbar treffen, Laufen am Boden in Höhlen,
  Last von locateNearestStructure auf dem Celeron (Log „abenteuer | weg | ms=“ abwarten).
- Rückgängig mit Prüfung (2026-11-14): Stadtbau-Rückgängig (Spool) nicht live geprüft, nur Format-/Journal-Tests.
  Einträge von vor dem Update (Format 1) setzen weiter blind zurück.
- Verdrahtung ohne Unit-Test (nur Code gelesen, live zu prüfen): BuildJob liest Rechte je Takt, startJob hält
  Plan-Befehle zurück, Veras „merk dir“ nutzt rememberProblem und die stille Prüfung.
- Vera-Befehle und Aussprache: nicht im Spiel geprüft; die App auf dem GPU-PC lief mit Qwen2.5 Coder 1.5B (zu klein).
- Begleiter: auf dev01 seit 2026-11-04, aber nicht im Spiel getestet. Sprechen (Host: Taste halten → whisper.cpp →
  Long-Poll /gehoert) gebaut und mit Testaufnahme geprüft, nicht mit echtem Mikrofon im Spiel. Der Begleiter-Prompt
  überschreibt im Host das Vorwärmen (prefix.json) des Bauplan-Prompts – der erste Bauauftrag danach rechnet den
  System-Prompt neu. Skin des Begleiters ist die Mannequin-Vorgabe.
- Küste: Gruben-Auffüllung (FILL_MARK) gebaut, nicht eingespielt – nach dem Einspielen an einer Hafenkachel mit
  RegionRenderProbe „Landproben unter −20“ zählen (vorher 23). Gebaute Kacheln ändern sich nicht. Grenzen der
  Plausibilitätsprüfung (ein Drittel / die Hälfte) sind geschätzt; Strände sind schmal (17,8 % der Uferproben Sand).
- Was den Old-Gen-Zuwachs beim Weltbau trägt, ist nicht gemessen (Klassenhistogramm während einer Kachel); seit
  ConcGCThreads=2 keine Full GC mehr beim Weltbau beobachtet.
- dev01 GC: Young-Pausen p50 96–156 ms bei ~2 GB Eden, zwei Full GC am 2026-11-01 trotz ConcGCThreads=2 (Gemessen).
  start.sh mit MaxGCPauseMillis 100 / G1NewSizePercent 10 seit 2026-11-01 21:27 auf dev01. Gemessen 2026-11-02 an 3
  kleinen Kacheln: Young p50 78 statt 127–158 ms, max ≤ 261 ms, 0 Full GC, keine Lücke > 1 s; aber doppelt so viele
  Pausen, GC-Zeit gleich, Lücken > 100 ms je Minute 32–35 (vorher 12–32). Große Kachel (Sydney) noch offen; Tabellen je Kachel
  und Messweg in docs/server.md (probes/gc_window_probe.py). Vergleichsgröße sind `lücken_über_100ms`, nicht
  längster_tick_ms – GC-Pausen fallen zwischen die Ticks. Was die Old Gen füllt: `jcmd <pid> GC.class_histogram -all`
  während einer Kachel (-all = ohne Full GC).
- Eigenes Modell trainieren (GPU-PC, `tools/train/train_qlora.py`), als „Eigene GGUF-Datei“ laden und mit
  `PlanBench` gegen dieselben 24 Testaufträge messen; bitsandbytes/CUDA und die Rechenzeit sind ungeprüft. Danach
  entscheiden, ob es auf Hugging Face soll (dann Katalogeintrag aus `modell-eintrag.json`).
- Echte LLM-Pläne von dev01 (`plugins/Baumeister/pläne/`, nur die letzten 50) einsammeln: `DatasetGenerator --pläne`.
  Die Dateien tragen kein Urteil; der Import prüft neu.
- Vorbilder: saubere, frei stehende Bauten sammeln (eigene per `//copy` + `//schem save`, fremde nur mit klarer
  Lizenz), je Datei Auftrag und Vorderseite in vorbilder.json; dann `DatasetGenerator --vorbilder <ordner>`. Erkennung
  von Dächern: seit 311dde1 werden Treppenreihen aus ≥ 2 ansteigenden Lagen ein roof (PlanDecompiler, einlagige
  Doppelbank/Freitreppe nicht); Kugeln fehlen noch.
- Lehrset um Reparaturrunden erweitern (kaputter Plan + Rückmeldung → korrigierter Plan) – heute nur Erstantworten.
- Minikarte im Spiel ausprobieren: Drehung beim Umsehen (flüssig genug? ROTATE_TICKS 3), Wegpunkte und Ziel am Rand,
  Reisepunkt-Marker, Zoom mit Schleichen, Plotrahmen gedreht, ob der Klick im Kreativmodus keinen Block bricht; und mit
  einem Spieler die CPU des Server-Threads messen; ohne Client nur Bild und Lesezeit geprüft.
- PlanAutoFix im Betrieb beobachten: `plan | grundriss_gerichtet|maschine_gerichtet|facing_gerichtet|größe_gerichtet|
  maschine_angenommen|hohl_gemacht|stützen_gesetzt|höhe_abweichend_gebaut` und `llm | wiederholung` in den
  dev01-Logs zählen (bis f09f3f0 hießen die ersten zwei grundriss_angepasst|module_entfernt); NUR Logs nach dem
  letzten Deploy zählen – 2026-11-21 hatte ich ältere mitgezählt (58 von 72 Verwerfungen längst behoben). Ab
  2026-11-01 verworfen: 4 Überbau, 4 „set überschreibt Maschinenblock“, 3 schwebend, je 1 Wasser/Tür/Block fehlt.
  „set überschreibt Maschinenblock“ mit gleichem Block richtet seit ff5240d `plan | modulzelle_gerichtet`.
  Pfeiler aus Stein unter einem Glasdach sehen ggf. fremd aus.
- Nachbesserungen nach bestätigter Vorschau (ComfortService.Planned trägt fixes/rounds, 2026-11-01): nur Code und
  Kompilieren, nicht im Spiel geprüft (Vorschau gibt es nur für Spieler, nicht über RCON).
- Plan vormerken während Stadt-/Weltbau (DeferredStart, 2026-11-01) nicht live geprüft: ein Stadtbau-Test fragt
  Overpass ab (externer Dienst) – mit echtem Spieler prüfen: KI-Auftrag geben, sofort /bm stadt, nach dem Stadtbau
  muss der Plan von selbst starten; „stopp“ dazwischen verwirft ihn.
- Prozessor Stufe 3: Steuerwerk – Befehle aus einem kleinen Speicher (ROM aus Hebeln/Blöcken), Zähler als
  Programmzähler, Befehl wählt Operation (ALU-Scheiben statt reiner Addierer) und Löschen des Akkumulators. Heute hat
  der Akkumulator kein Löschen: zurück auf 0 nur durch passende Addition (Überlauf).
- Prozessor-Werkstatt mit Akkumulator nur über MachineHallTest (Plan, Übersetzung, Linter) belegt, nicht als ganze
  Halle im Spiel gebaut; das Bauteil selbst ist per MachineProbe geprüft.
- Geländewelt: Bei gleichem Namen wie in der Erdwelt heißt der Gelände-Reisepunkt „Grand Canyon (Gelände)“
  (PlaceRegistry.rememberAuto, nur im Code gelesen – im Spiel nicht nachgesehen). Seit bae0b0f (2026-11-14) führt
  derselbe Ort mit gleichem Radius an seinen ersten Platz zurück; die drei Grand-Canyon-Plätze auf dev01 bleiben
  (Platz n bei x = n × 65 536, z = 0), erst nach dem Einspielen kommen keine neuen dazu.
- Aus dem Absturz-Audit 2026-10-30: BuildJob.holdChunks und SiteFrame.survey laden seit e1e6a0a (2026-11-14) vorher
  über ChunkPrefetch (Paper asynchron, Spigot 2 je Tick; gemessen 4,4 s → 0,08 s). SiteFingerprint und /bm ort seit
  1dff973 ebenso (fail-closed). Nach dem Vorladen bleibt eine Lücke von 0,2–0,55 s: JFR (2026-11-14, Paper-Kopie, 8 mal 5 Stockwerke)
  zeigt 33 Stichproben ≈ 330 ms ohne Brennpunkt – Compiler ~10 (order/nearestNeighbour/needsSupport), Scan 3,
  startJob/dropBox 4; CompileProbe: Compiler kalt 104 ms, warm ~25 ms. Kalte JIT-Arbeit, bewusst nicht ausgelagert. Früher:
  2026-11-01 bewusst NICHT geändert: ungeladene Chunks als leer zu lesen hieße, der Überbau-Schutz übersieht dort
  Bauwerke; asynchrones Laden gibt es nur in der Paper-API (Plugin baut gegen Spigot-API). Erst messen (Celeron);
  `/bm ort` lud safeY bis 1dff973 ohne
  Ladeprüfung. Ein vom Neustart unterbrochenes Rückgängig bleibt seit a77dc2f erhalten (live: altes JAR räumte
  danach einen ÄLTEREN Bau weg, neues setzt den unterbrochenen fort). Stadtwelt-Sichtweite
  32 auf dev01 überdenken (Spieler mit flyspeed 20 erzeugt dort sehr viele Chunk-Ladungen).
- Fortsetzen: Jeder Neustart legt einen eigenen Rückgängig-Eintrag an; bei vielen Neustarts eines großen Orts fallen
  die ältesten Abschnitte aus dem Stapel (`bau.undo-tiefe`). Mit Host-Stadtansicht und auf dev01 nicht live geprüft;
  Eberdingen (2449 Etappen, Zellgröße 5) nicht erneut gebaut.
- Erdwelt (auf dev01 seit 2026-10-29): /bm reise, /bm erde, Klick in /bm orte und weltbau auto mit echtem Spieler
  prüfen, dabei Speicher und Tick-Zeit auf dem Celeron messen; Sichtweite der Erdwelt bei 10 lassen (Speicher, s. o.).
  Weltbau über die Datumsgrenze nur mit Testdaten geprüft; Neumalen über Schnappschuss nicht live gemessen (kein
  MSPT-Wert), Gänge unter der Oberfläche geschätzter Chunks werden dabei gefüllt.
- Erd-Grenzeffekt (bewusst offen): Schon erzeugte Chunks behalten ihr altes Wasser; grenzt ein alter, bis 0 gefüllter
  Chunk an einen neuen trockenen Polder, läuft es hinein. Auf dev01 vor dem Deploy prüfen, ob dort erkundete Chunks
  unter 0 m im Landesinneren liegen.
- Gedächtnis/Nachtrag und die Zeichenansichten mit einem echten LLM im Spiel prüfen (nur Unit-Tests).
- Gelände: auf dev01 einrichten (Neustart, Sichtweite der Geländewelt festlegen); Stadt auf Gelände setzen; Orte
  unter Meereshöhe im Landesinneren laufen voll Wasser; Bäume/Flüsse fehlen; bestehendes Gelände einer Welt wird
  nicht umgeformt.
- Rechteprüfung gegen WorldGuard/PlotSquared mit echtem Spieler testen (PlotSquared könnte pro verweigertem Event eine Meldung schicken; der Bau bricht beim ersten Nein ab, also höchstens eine).
- Chat-Auftrag eines echten Spielers testen.
- Besseres Modell für freie Entwürfe prüfen (Qwen3-8B o. ä.), mit `LlmProbe` gegen dieselben drei Aufträge messen.
- Weltbau-Rückgängig (TileUndo) übersteht seit 942fa18 den Neustart (rückgängig-weltbau/, Zettel mit „kachel“); live nur mit
  einem nachgebauten Eintrag geprüft (10/10 Zellen), nicht mit einer echten Weltbau-Kachel (braucht OSM).
- Lastregelung auf dev01 (Celeron, normale Welt) nachmessen; vorher Städte dort in `/bm stadtwelt` bauen.
- Tempo auf dev01 nicht gemessen (bewusst offen): `stadt.gleiche-zellen-je-block` 4 gegen 1, Tickzeit und Lücken;
  ruckelt es, auf 1 setzen (kein neuer Build). Weltbau-Etappen der Erdwelt mit dem neuen Tempo nicht gemessen.
- Stadtbau: Chat-Steuerwörter mit echtem Spieler prüfen; Gelände (Höhenmodell) fehlt; Innenausbau schlicht.
  Stadtausstattung nur gezählt (150 m): Blockzustände unter 1.21.11 (Schienen, Mauern, Schilddrehung), Brückenrampen
  und Laternenzahl am Münchner Cache im Spiel ansehen. Bahnbrücken liegen flach, Gleise am Hang ohne aufsteigende
  Schienen, Talbrücken mit Stufe in der Mitte; Wald und Parkplätze nur als Way. Nach dem Deploy holt jede Kachel
  einmal neu (neuer Zwischenspeicher-Name) – Overpass-Last beim ersten Bau eines schon gebauten Orts.
- Logik in Stockwerken: seit 1a0ee23 (2026-11-21) auf dem Spigot-Testserver in N/O/S/W gemessen, 6 Formeln × 4 =
  24/24 BESTANDEN, bis 4-zu-16-Dekoder (16 Lampen, 4 Stockwerke) und „3d“ (LogicProbe 3dmax/3dalle);
  im Spiel nicht angesehen; ob das echte Modell „3d“ und lange Formeln nutzt, ungeprüft; die Grenze 480 im
  Modellplan wirkt erst mit der neuen Host-App auf dem Spiele-PC.
- Logik (bewusst offen): Ausgänge ohne Schilder (nur Reihenfolge links → rechts). Ein mehrstelliges Rechenwerk aus
  verketteten Volladdierern geht nicht (keine Rückführung, 3 Bit bräuchten 6 Eingänge). Ob ein echtes LLM mehrere
  Formeln mit „;“ nutzt, ungeprüft. Impulsverlängerer nur mit ~1,2 s Puls geprüft; Item-Sortierer seit 353c4bc für 2–8 Sorten
  (item_sortierer_N, Spigot 4/4, Paper 26.2 alle 4/4, T-018/T-022).
- Bauformen: Mauer und Baum nicht im Spiel gebaut, Burg/Brücke/Garten nur als Schnellauftrag; mit echtem LLM prüfen,
  ob es die ops nutzt. Burg-Hof leer, Brücke ohne Rampen. Gerade Mauern ab 9 haben seit 9b75e5f ein Tor (live geprüft, noch nicht auf dev01).
- Plan-Prüfer und Anbau mit fester Wand (die frühere Idee „Anbau deterministisch vorbereiten“ ist umgesetzt) mit
  echtem LLM im Spiel prüfen; messen, ob „streng“ zu viele Absagen bringt, sonst `reparieren`. Bewusste Abweichung:
  Die Rückmeldung des Plan-Prüfers an das LLM ist englisch (System-Prompt englisch), im Chat deutsch.
- Komfort mit echtem Spieler: Balken, Vorschau (Partikel, ja/nein, Ablauf, veränderter Bauplatz, „ja“ während eines
  Baus), Rückgängig nach `/bm neuladen`. Auf dev01 messen: Partikellast (400 Punkte je Vorschau), Prüfsumme des
  Bauplatzes, Prüfung der Rückgängig-Einträge beim Start.
- Betrieb: `/bm neuladen` im laufenden Server prüfen. `server/dev01/start.sh` und Drop-in nur mit Bestätigung einspielen,
  danach free/vmstat/RSS, `/bm leistung` und gc.log messen.
