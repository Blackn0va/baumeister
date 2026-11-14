# Changelog

Alle nennenswerten Änderungen. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionen nach SemVer.

## [Unveröffentlicht]

### Neu
- **Mehrere Hochhäuser ohne KI:** „3 mal 20 Stockwerke“ oder „drei Hochhäuser mit 20 Etagen“ baut die Türme sofort
  nebeneinander (bis 8, so viele wie in `bau.max-ausdehnung` passen). Vorher wurde daraus eine Grundfläche 3×20, und
  der Auftrag ging ohne Geschosszahl ans Modell.
- **Lizenz und Mitmachen:** GPL-3.0-or-later (`LICENSE`, im Plugin-JAR und im Host-Setup), `CONTRIBUTING.md`, `SECURITY.md`, Vorlagen für Pull Requests und Issues; README mit Bildern von
  Stadtbau, Maschinen und Logik.
- **Sprache vom Server:** Vera und der Baumeister sprechen im Voice Chat mit Piper auf dem Server, ohne App –
  jeder mit eigener Stimme, Vera als Cartoon-Kumpel (`sprache.vera-tonhöhe`). Aussprache wie in der App
  („Krieper“, „Rädstohn“, Abkürzungen, Uhrzeiten, keine Zeichennamen).
- **Vorrat fester Sätze:** Alle festen Sätze von Vera und dem Baumeister werden einmal vorab gesprochen und kommen
  danach sofort – auch ohne LLM (`sprache.vorrat`).
- **Bedienung im Hilfebuch:** Kapitel Schnellbau, Suchen (Erze und Orte mit Vera), Sammlung, Mods und für Admins
  Stimme (Stimmen wählen, Cartoon-Ton, Probe hören). `/bm sprache` für dasselbe im Chat.
- **Katalog:** `/bm katalog [suchwort]` zeigt alle Schematics als Kistenfenster, Klick baut vor dir.
- **Alle Schematics:** auch alte `.schematic` (MCEdit, vor 1.13) und große Bauwerke bis 1 Mio. Blöcke
  (`schematics.max-blöcke`, `schematics.max-ausdehnung`).
- **Pyramide ohne KI:** „Baumeister, bau mir eine Pyramide [aus Sandstein] [21 breit | 10 hoch]“ baut sofort eine
  Stufenpyramide mit Eingang – wie Burg, Mauer, Brücke, Garten und Baum ohne Sprachmodell.
- **Ollama als KI:** `llm.modell` setzt den Modellnamen in jeder Anfrage – damit läuft der Baumeister auch mit
  Ollama statt der Baumeister-App (`llm.url: http://127.0.0.1:11434/v1/chat/completions`). `/bm status` erkennt
  Ollama als erreichbar.
- **Vera findet Orte:** „Vera, wo ist das nächste Dorf?“ – auch Netherfestung, Festung mit dem Endportal, Bastion,
  Ozeanmonument, Waldanwesen, Außenposten, Tempel, Iglu, antike Stadt, Prüfungskammer, Schiffswrack, Ozeanruine,
  Portalruine, Mine und Endstadt. Gleiche Regeln wie bei Erzen (Recht „Erze suchen“, nie im Abenteuer).
- **Schematics bauen:** Jede `.schem`, `.litematic` und `.nbt` in `plugins/Baumeister/schematics/` ist ein Bauteil –
  „Baumeister, bau mir einen Taktgeber“ (mit „Variante 3“ eine bestimmte), `/bm schematics [suche]` zum Stöbern,
  `/bm maschine <name>` für Admins. Ohne WorldEdit, neue Dateien gelten ohne Neustart, Blockdaten und Befehle aus
  Dateien werden nie gesetzt (`docs/schematics.md`).
- **Download-Seite neu und mit HTTPS:** Die Seite hinter `/mods` ist jetzt dunkel mit Karten je Launcher, Knöpfen,
  Schritt-für-Schritt-Anleitung, Modliste und Serveradresse. Mit einem Let's-Encrypt-Zertifikat
  (`mods.download.zertifikat`/`schlüssel`) läuft derselbe Port mit HTTPS; neue und verlängerte Zertifikate gelten ohne
  Neustart.
- **Modpaket direkt vom Server.** `/mods` gibt jedem Spieler einen eigenen Download-Link (24 Stunden gültig) auf das
  Modpaket – `.mrpack` für Prism Launcher und Modrinth App, ZIP mit allen Mods für den normalen Launcher. Ohne
  Link kommt niemand an die Dateien. Einschalten mit `mods.download.an` (Vorgabe aus).
- **Installation auf fremden Servern:** Anleitung in drei Stufen (`docs/installation.md`), ein Paket mit Plugin,
  Relay, App-Setup, Client-Mod und Prüfsummen (`tools/paket/Paket-Erstellen.ps1`) und ein Einrichtungsskript für das
  Relay, das mit dem vorhandenen Java arbeitet (`relay/einrichten.sh`).
- **Jeder Block mit Inhalt, Befehlsblöcke eingeschlossen.** Baupläne setzen Zustände und Daten wie `/setblock` –
  Schilder, Truhen samt Inhalt, Bannermuster, Töpfe, Befehlsblöcke. Vier Stufen statt einer Sperrliste, geprüft beim
  Setzen: für alle · Inhalte (Kreativmodus in der Welt des Baus oder `baumeister.bauen.inhalte`) · Gefährliches
  (`baumeister.admin.gefaehrlich`: Lava, TNT, Feuer, Spawner, Grundgestein abtragen …) · Befehle <!-- umlaut-ok: Rechte-/Dateiname -->
  (`baumeister.admin.befehlsblock`). Unbekanntes zählt als höchste Stufe. Wer in LuckPerms `*` oder
  `baumeister.admin` hat, darf alles wie ein OP.
- **Befehle aus dem Modell nur per Klick.** Schreibt das Modell (oder `bauenbei`) Befehlsblöcke, bekommst du jeden
  Befehl ungekürzt aufgelistet – mit Weltkoordinaten und sichtbaren Sonderzeichen – und klickst [Befehle bestätigen],
  [ohne Befehle bauen] oder [abbrechen]. Das gilt 10 Minuten, nur für dich und nur für genau diese Fassung.
  Bestätigbar sind nur reine Impuls- und Ketten-Befehlsblöcke ohne Strom daneben; „immer aktiv“ am Impuls-Block wird
  zu „braucht Redstone“. Ein Auftrag mit Recherche-Text (Wikipedia) baut höchstens bis „Inhalte“.
- `/bm befehlsblock [befehl]` zeigt bzw. setzt den Befehlsblock, auf den du schaust – in jedem Spielmodus.
- **Befehlsknopf und Befehlskette** als geprüfte Bauteile: „bau einen Befehlsknopf mit dem Befehl time set day“,
  „eine Befehlskette mit den Befehlen say a; say b; say c“.
- `/bm selbsttest blöcke|daten|stabil <liste>` (Admins): setzt jeden Blockzustand über den Weg eines Baus in alle vier
  Richtungen und liest ihn zurück, prüft je Block-Entity ein Datenbeispiel und zeigt, was Minecraft von selbst
  angleicht.
- **Baumeister-App über die URL.** Die App verbindet sich per `wss://` mit dem Relay auf dev01 (Port 25580), beide
  Seiten am Fingerabdruck erkannt – kein SSH-Tunnel mehr nötig. Das Relay läuft als gehärteter Dienst, das
  Login-Banner zeigt die angemeldete App. Einrichtung: [docs/fernverbindung.md](docs/fernverbindung.md).
- Gesprochenes aus der Baumeister-App gilt nur für die Spieler, die bei dieser App eingetragen sind
  (`fernverbindung.apps.<name>.spieler`); das Relay sagt bei jeder Antwort, welche App sie geschickt hat. Ohne
  Eintrag bleibt alles wie mit dem SSH-Tunnel.
- Farmen erklären sich: Beim Auftrag sagt der Baumeister, was du bereitlegen sollst („Leg schon bereit: 3 Betten,
  3 Dorfbewohner (Spawn-Eier), einen Zombie und ein Namensschild“), und nach dem Bau Schritt für Schritt, was du noch
  tun musst – auf Deutsch, für alle neun Farmen. Vorher kam nur „Ich baue dir das Bauteil 'eisenfarm'.“
- Acht Farmen ohne LLM, jede auf dem Testserver gebaut und geprüft, mit Gegenprobe (ohne ihr tragendes Teil fällt sie
  durch): **Weizenfarm** (Ackerland 9 × 7, Hebel spült die Ernte in Trichter und Truhe, Hebel aus und an holt das
  Wasser zurück; gemessen Ernte nach 3–31 s in der Truhe, Wasser nach 2,1 s weg), **Eisenfarm ohne Lava** (drei
  Dorfbewohner auf Betten, ein Zombie dahinter, ein Teich trägt die Golems in eine Grube mit Seelenlagerfeuern;
  Golems kommen tagsüber, solange die Bewohner wach sind; gemessen Golem nach 2–26 s in der Grube, Eisen rund 28 s
  später in der Truhe – Betten, Bewohner und Zombie bringt der Spieler), **Hühnerfarm** (Eier in
  die Truhe), **Kürbis- und Melonenfarm** (Beobachter am Stängel, Kolben zerbricht die Frucht), **Bambus-** und
  **Kaktusfarm**, **Bruchsteingenerator** (die Lava gießt der Spieler). Erkannt werden auch „Golemfarm“, „iron farm“, „Kornfarm“, „Eierfarm“, „Cobblestone-Generator“; zwei Farmen
  auf einmal oder Zusätze („mit Lava“) gehen weiter ans Modell.
- `/mods` (auch `/modpaket`): Minecraft-Fassung des Servers, Liste der Mods samt Fassungen und – wenn `mods.link` in
  der config.yml steht – ein Knopf zum Herunterladen. Das Paket baut
  [`tools/modpaket/Modpaket-Erstellen.ps1`](tools/modpaket/Modpaket-Erstellen.ps1) auf dem Spiele-PC aus dem
  Mods-Ordner: `.mrpack` für Prism Launcher und Modrinth App (Mods von Modrinth als geprüfter Link, der
  Baumeister-Client im Paket), ZIP für den normalen Launcher und eine Liste; `-Hochladen` legt alles auf dev01 ab.
  Ein Server kann Mods nicht selbst an Clients schicken.
- Vera schwimmt besser: Taucht der Spieler, taucht sie auf seine Tiefe mit (vorher sank sie nur), und über einen
  1 Block hohen Rand klettert sie selbst aus dem Wasser (vorher nur per Teleport). Gemessen (SwimProbe): Tauchen
  Tiefenfehler 0,3 statt 2,5 Blöcke ohne Teleport, Ufer 0,8 s nach dem Spieler an Land.
- Vera fährt mit: Sitzt du in einem Boot, auf einem Kamel oder einem Glücklichen Ghast mit freiem Platz, steigt sie
  ein – und mit dir wieder aus. Vorher schwebte sie neben dem Boot her.
- Vera craftet: „crafte mir 4 Fackeln“, „stell mir eine Werkbank her“ – aus deinem eigenen Inventar, genau die
  Zutaten, Reste (leere Eimer) zurück. Im Kreativmodus gibt sie direkt. Rezepte auf Paper 26.2 geprüft (CraftProbe).
- XP-Farm ohne LLM: „bau mir eine XP-Farm“ (auch Mobfarm, Monsterfarm, Erfahrungsfarm) baut eine geprüfte Mobfarm
  ohne Spawner – dunkle Plattform 22 Blöcke hoch, Wasserkanäle, Fallschacht, Schlagkammer mit Truhen. Mobs kommen
  mit 1–2 Leben an, Spinnen sterben beim Fall und verstopfen nichts (Testserver: Zombie und Skelett geprüft).
- Server auf Minecraft 26.2 (Paper 26.2, Java 25): neueste Fassung, auf der alle Plugins laufen (CoreProtect endet bei
  26.2). Vorher zwei geprüfte ZIP-Sicherungen; der Server liegt jetzt auf der zweiten Platte (NVMe).
- Baumeister-Client 0.2.0 für Minecraft 26.2.
- Redstone in 3D: Große Logik baut der Baumeister in Stockwerken übereinander – bis 16 Ausgänge auf bis zu
  4 Stockwerken (je 4 Ausgänge und 16 Terme). Die Hebel bleiben unten, je Eingang steigt ein Fackelturm nach oben.
  „3d“ vor der Formel (auch „übereinander“, „gestapelt“, „vertikal“) stellt jeden Ausgang in ein eigenes Stockwerk.
  Ohne Modell: „bau mir einen 3-zu-8-Dekoder“ (8 Lampen, 2 Stockwerke), „2-zu-4-Dekoder“ und „Taschenrechner in
  3D/übereinander/als Turm“. Die Ansage nennt die Lampen je Stockwerk. Auf dem Testserver mit voller Wahrheitstabelle
  geprüft: 4/4 (5-Bit-Parität mit 17 Termen, Dekoder, 2-Bit-Addierer auf 3 Stockwerken).
- Bildschirm mit bewegten Bildern: „bau mir ein Display/einen Bildschirm“, „… mit Uhr“, „… mit Laufschrift Hallo“,
  „zeig katze.gif“ oder `/bm bildschirm` – Karten in unsichtbaren Rahmen vor einer Rückwand; Animation, Uhr,
  Laufschrift, Bilder und GIFs aus `plugins/Baumeister/bildschirm/`. Bildrate nach Größe gedeckelt (Leitung der
  Zuschauer), gerechnet nur, wenn jemand in der Nähe ist; `/bm bildschirm weg` baut ab. „Redstone-Display“,
  „7-Segment“ und „Zahlenanzeige“ bauen weiter die Redstone-Maschine.
- Vera als Mitspieler: Charakter Kumpel, Frech oder Anpassen („sei frech“, im Buch oder `/bm begleiter charakter`),
  Sprüche bei Tod, Sturz, wenig Leben, Hunger, Diamanten, Levelaufstieg, Nacht und Langeweile.
- Vera benutzt auf Zuruf Türen, Tore, Falltüren, Hebel, Knöpfe, Glocken, Notenblöcke und Kisten, öffnet Holztüren
  auf ihrem Weg selbst und probiert herumstehend Glocken, Notenblöcke und Türen aus.
- Rechteverwaltung im Admin-Buch: Stufen Alle/Ops/Aus für Veras Fähigkeiten und für jeden Spieler alle Rechte des
  Plugins (Erlaubt/Verboten/Standard), gespeichert in `vera-rechte.yml`.
- Vera fliegt mit Elytren und im Kreativflug so schnell wie du hinter dir her.
- Vera ohne Baumeister-App: Uhrzeit, Wetter, Reisen (auch verhörte Warp-Namen), Erze suchen, Fliegen, Beschwören,
  Reparieren am Ort, Bedienung aus dem Baubuch, Loch graben und kurze Gespräche ohne Modell – über denselben Weg wie
  die Modellantwort. Gemessen an 171 echten Sätzen: 74 % ohne Modell (vorher 33 %).
- 7-Segment-Anzeige 0–F als geprüfte Maschine `anzeige`: vier Hebel (Binärzahl), Decoder aus dem Logik-Compiler, Ziffer
  aus 27 Lampen im Boden. „bau ein (funktionierendes) Display“ baut sie ohne Modell. Auf dem Testserver mit echtem Bau
  geprüft: 432/432.
- Vera baut Redstone um: „Reparier meine Schaltung“ dreht falsch herum gesetzte Verstärker und setzt Verstärker ans
  Ende aufgebrauchter Signale, prüft nach und baut zurück, was nicht half. „Was macht diese Schaltung?“ schaltet die
  Hebel durch und nennt die Formel je Ausgang (gemessen: UND, ODER, NICHT, durch Block 4/4 richtig).
- Vera findet ihren Weg (A*): um Mauern, durch Höhlen und Tunnel, schmale Stege und Treppen hinauf, Leitern
  hoch – gemessen auf 7 Parcours ohne Teleport und ohne Sturz. Sie duckt sich, schwimmt, gleitet und schläft wie du,
  hält im Dunkeln eine Fackel, schaut sich nach Tieren und Spielern um und springt bei Levelaufstieg vor Freude.
- Stimme: Vera spricht Satz für Satz (erster Ton früher), hört auf, wenn du ihr ins Wort fällst
  (`begleiter.unterbrechen`), und hat in Simple Voice Chat einen eigenen Lautstärkeregler „Begleiterin“.
- Spielmodus: Vera bekommt jeden Wechsel Kreativ/Überleben mit und sagt, was jetzt gilt.
- Vera und der Baumeister verstehen Redstone: „Prüf meine Schaltung“ / „Warum geht die Lampe nicht?“ nennt den
  Fehler aus den echten Blockzuständen (Verstärker falsch herum oder gesperrt, Signal aufgebraucht, Lücke, Fackel als
  NICHT); Redstone-Fragen und Maschinen-Aufträge bekommen die gelesene Schaltung als Kontext. Knopf im Hilfebuch.
  Fehler kommen vor Erklärungen; dunkle Fackeln und gesperrte Verstärker gelten als gewollt (NICHT-Gatter,
  Speicher). „Repariere meine Schaltung“ prüft ebenfalls, umgebaut wird nichts.
- Vera läuft wirklich: mit Schwerkraft, springt auf Blöcke, klettert Leitern, schwimmt an der Oberfläche und
  geht nicht mehr durch Wände. Hängt sie 2 s fest oder ist sie über 20 Blöcke weg, kommt sie per Teleport nach;
  fliegt der Spieler, schwebt sie wie bisher hinterher.
- Vera klingt wie eine Trickfilmfigur: kurzer Plapperton zu jedem Satz (Frage steigt, Ausruf hüpft,
  `begleiter.plapperton`), Notenpartikel beim Witz; in der App neue Tonlage „Cartoon“ als Vorgabe.
- Hilfebuch als Gegenstand im Inventar (wie der Rucksack, `/hilfe holen`); Rechtsklick schlägt es aktuell auf.
  Blättern über ◀ · Inhalt · ▶ unten auf jeder Seite, unabhängig vom Client.
- Hilfebuch `/hilfe` als Bedienpult für neue Spieler: alles per Klick (Vera, Abenteuer, Rucksack, Warps, Einladen,
  Bauwerke), mit aktuellen Listen und nur dem, was man darf; öffnet sich beim ersten Beitreten einmal von selbst.
  Dazu `/vera <satz>` für Sätze an Vera per Klick.
- Rucksack-Suche (`/rucksack suche`, „Vera, wo ist … im Rucksack?“), Einsammeln gewählter Sorten beim Aufheben,
  Teamkisten (`/rucksack team`) und „Vera, gib mir … aus meinem Rucksack“.
- Warps für alle (`/warp`, `/warps`, `/setwarp`, `/delwarp`), ersetzen GWarp; dessen Warps werden übernommen.
- Tagesaufgaben mit Punkten, Serie und Rangliste (`/aufgaben`).
- Abenteuer im Team: Was einer schafft, zählt für alle; Hardcore heißt ein Leben fürs Team.
- Bauwerke merken („Ecke eins/zwei“, „merk dir das Bauwerk als …“) und gespiegelt oder doppelt so groß nachbauen.
- Vera hält Nachtwache, sagt, wer an deiner Kiste war, und repariert Explosions- und Brandschäden (CoreProtect).
- Rucksack: eine Kiste im Inventar mit 100 Seiten × 45 Plätzen (4500 Stapel), Blättern, „Alles einlagern“ und
  „Sortieren“; `/rucksack` mit den Aliasen von Minepacks (`/backpack`, `/bp`). Ersetzt MinepacksStable. Der Inhalt
  liegt auf dem Server, eine kopierte Kiste kopiert nichts; kein Öffnen im Kreativmodus; beschädigte Dateien bleiben
  zu und werden nie überschrieben.

### Geändert
- Baumeister-Client 0.3.0 (Stand nach den Blockstufen; vorher hießen zwei verschiedene Stände 0.2.0).
- Die Server-APIs zum Kompilieren (`lib/*.jar`) stehen nicht mehr im Repo – Herkunft und Hashes in `lib/README.md`.
- Host: Vorgabe der Fernadresse ist ein Platzhalter (`wss://dein-server.example.net:25580/kanal/v1`). Eingerichtete
  Installationen behalten ihre gespeicherte Adresse.

### Behoben
- Ein nach Süden gebauter Akkumulator (`akkumulator_4bit`) startete mit dem Wert 8 statt 0: Ein Speicher fing beim
  Bau einen Zwischenzustand ein. Jeder frisch gebaute Speicher beginnt jetzt bei 0, in jeder Richtung.
- Client-Mod: `/kbau stop` während der Planung verwarf nur das Ergebnis, die Anfrage lief weiter, und der
  Baumeister-Host rechnete den Plan samt Nachbesserungen zu Ende. Jetzt schließt der Stopp die Verbindung, und der Host
  hört auf.
- `/bm gelaende` legte für denselben Ort jedes Mal einen neuen Platz in der hohen Geländewelt an – der Reisepunkt
  zeigte nur noch auf den letzten. Derselbe Ort mit demselben Radius führt jetzt an den ersten Platz zurück.
- `/bm rückgängig` überschrieb, was andere nach dem Bau an derselben Stelle gebaut hatten – auch eine gefüllte Truhe.
  Jetzt bleiben solche Blöcke stehen, und die Meldung sagt, wie viele. Rückgängig-Einträge von vor dem Update
  setzen weiter wie bisher zurück.
- Burg, Mauer, Brücke, Garten oder Baum ohne Materialangabe vom Modell wurden verworfen („Schritt 1: block fehlt“).
  Jetzt nimmt der Baumeister das Standardmaterial (Steinziegel, Eichenzaun, Eichenstamm) und baut.
- Ein Bauplan mit einem Gegenstand statt Block (z. B. Elytren) wurde still mit Stein gebaut. Jetzt geht er zuerst zur
  Nachbesserung ans Modell; erst ohne freie Runde bleibt es beim Ersatzblock.
- Vera fand Diamanten unter y = 0 nicht: gesucht wurde nur `diamond_ore`, dort liegt aber Tiefenschiefer-Erz. Jetzt
  zählen alle Formen eines Erzes, Gold auch im Nether.
- `/mods` nannte auf Paper 26.2 „Minecraft 26.2.build.135“ – eine Fassung, die kein Launcher kennt. Jetzt „26.2“.
- Statt „Verbindung zum LLM abgebrochen (IOException)“ steht im Chat ein verständlicher Satz, wenn der PC mit der KI
  nicht verbunden ist.
- Auf Servern, deren Welt nicht „world“ heißt, zählten keine Tagesaufgaben und Vera bot kein Abenteuer an. Die
  Vorgabe ist jetzt die Hauptwelt samt Nether und End, wie immer sie heißt; der Name im Chat kommt aus
  `spielwelt-name`.
- Das Hilfebuch zeigt die Knöpfe Farmwelt, Plotwelt und Grundstück nur noch, wo es Warp bzw. PlotSquared gibt.
- Eisenfarm läuft rund um die Uhr: Bisher sahen die Bewohner den Zombie im Stehen, gerieten in Panik und schliefen
  nie – nach einem Tag kamen keine Golems mehr. Jetzt ein Zombie für die Nacht, den nur Liegende sehen, und einer für
  den Tag hinter Falltüren, die nachts zuklappen. Auf beiden Testservern in allen vier Richtungen über einen Tag und
  zwei Nächte geprüft.
- XP-Farm: Keine Bahn bleibt nach dem Bau trocken, und kein Zombie springt mehr aus dem Kanal auf die Fläche.
- Lücken aus zwei Sicherheitsprüfungen geschlossen, jede mit Gegenprobe: Paar aus Bauteil- und Plan-Hälfte
  ungeprüft, zu tief verschachteltes Text-JSON, Topfscherben und Feuerwerk ohne Wertprüfung, Konsolenrechte für
  Befehlsblöcke, „merk dir das Bauwerk“ über riesige Bereiche samt falscher CoreProtect-Einträge, Befehle ohne
  vollständige Anzeige bestätigbar, Vera-Befehle nur über eine Sperrliste geprüft.
- Veras Gehör über die App bricht mit zwei Spielern im Voice-Chat nicht mehr ab.
- XP-Farm: Die zwei Beutetruhen ließen sich nicht öffnen – sie lagen unter der Glasscheibe, ringsum Boden und
  Trichter. Jetzt liegen sie vor der Scheibe im Boden; die Beute ist auf dem Testserver bis in die Truhe geprüft.
- Vera hält beim Folgen enger Schritt: Ziel ist jetzt du selbst (Halt bei 3 Blöcken). Gemessen in der Natur-Welt im
  Mittel 2,4–3,8 statt 2,8–5,4 Blöcke Abstand, in den Bergen kein Stehenbleiben mehr 5–6 Blöcke hinter dir.
- Die erste Ansage nach dem Beitreten stockt nicht mehr (Sprach-Kodierer vorgewärmt).
- Veras Stimme im Voice-Chat stockt nicht mehr bei Speicherpausen des Servers und zwischen den Sätzen: eigener Sender
  mit 200 ms Vorlauf, festem Takt und Nachholen, ein durchgehender Strom über alle Sätze.
- Vera bleibt nicht mehr am Startpunkt stehen, wenn ihre Spur abreißt (Schnee, Blockkanten, Boot): Die Spur zählt nur,
  solange sie zu dir passt, sonst geht sie direkt zu dir; ab 22,5 Blöcken Abstand zu dir holt sie sich heran.
- Umlaute an Wortgrenzen: „öffne mein Tagebuch, Vera“ und „… über das ganze Grundstück“ werden jetzt verstanden.
- Vera läuft nicht mehr zum Startpunkt zurück, wenn du einen anderen Weg gehst: Ihr Ziel liegt auf ihrer Seite statt
  „hinter deinem Rücken“ nach der Blickrichtung, und ohne Weg nimmt sie den neuesten erreichbaren Punkt deiner Spur
  statt den nächsten (der oft auf einem alten Wegstück lag). Sie hält jetzt erst bei 3 und läuft ab 4 Blöcken los
  (vorher Anlaufen und Halten bei derselben Schwelle) und holt gleitend auf: in der Natur-Welt im Mittel 2,8–5,4 statt
  4,2–6,5 Blöcke Abstand.
- Vera folgt in Höhlen, auf Wendeltreppen und an Schluchten, statt festzuhängen oder sich in den Fels zu teleportieren:
  Sie läuft deinen Weg nach, wenn der Platz schräg hinter dir im Stein oder hinter einer Wand liegt, und landet bei
  einem Teleport auf deiner Spur. Gemessen im Gang mit Parallelgang: vorher 120 Ticks im Stein, jetzt keiner.
- Hört Vera dich im Voice-Chat nicht (Baumeister-App aus), sagt sie es jetzt – einmal sofort, danach höchstens alle
  10 Minuten – statt deine Sätze still zu verwerfen.
- Vera hört dich im Voice-Chat wieder: Wer sie einmal gerufen hat, bekommt sie jetzt bei jedem Beitreten zurück, bis
  „tschüss“. Vorher fiel man nach einem Wiederkommen oder zwei Neustarts von der Liste, Vera blieb weg, und ohne sie
  hört das Plugin niemanden. Wer im Voice-Chat ist und Vera nie gerufen hat, bekommt einen Hinweis, wie man sie holt.

### Neu
- Abenteuer-Tagebuch: „Vera, zeig mein Tagebuch“ öffnet ein Buch mit allen Etappen und der Zeit, die jede gedauert
  hat; nach dem Drachen öffnet es sich von selbst. Es kommt nie ins Inventar (Fair-Play).
- Vera erzählt Minecraft-Witze („Vera, erzähl mir einen Witz“) – ohne KI, keiner doppelt, bis alle dran waren.
- Rangliste: „Vera, wer ist am weitesten?“ zeigt die fünf Weitesten auf dem Server samt Hardcore-Bestleistungen und
  deinen Platz. Vera hüpft vor Freude, wenn du eine Etappe schaffst, und kommt mit Herzen.
- Vera hat ein Outfit: Elytren, violette Lederhose und Stiefel mit Amethyst-Verzierung, Laterne in der Hand. Sie ist
  fröhlicher und frecher (Prompt), jubelt abwechslungsreich und warnt mit wechselnden Sätzen.
- Kreativmodus im Abenteuer: normal pausiert es (Vera darf dann wieder geben und bauen lassen) und geht im
  Überlebensmodus weiter; im Hardcore endet es.
- Abenteuer: Boss-Leiste mit Etappe und Ziel (rot im Hardcore), dazu Titel, Klang und Totem-Partikel bei jeder
  geschafften Etappe.
- Abenteuer mit Vera in der Farmwelt: „lass uns Minecraft spielen“ (normal) oder „… hardcore“ (ein Leben) startet
  eine Geschichte in 14 Etappen vom ersten Holz bis zum Enderdrachen. Vera erkennt den Fortschritt, sagt das nächste
  Ziel an, zeigt auf „wo geht's lang?“ den Weg (Bäume, Dorf, Ruinenportal, Netherfestung, Festung, Drache) und
  warnt vor Nacht, Hunger, wenig Leben und Creepern. Im normalen Modus kämpft sie mit. Solange das Abenteuer läuft,
  gibt sie nichts, führt keine Befehle aus und teleportiert nicht. `/bm begleiter abenteuer [normal|hardcore|stand|aus]`.
- Schnellaufträge rücken neben den Bestand: Würde ein fester Plan (Fort Knox, Bunker, Pool, Hochhaus …) ein
  bestehendes Bauwerk überbauen, sucht der Baumeister die nächste freie Stelle daneben und sagt, wohin („Da steht
  schon etwas – ich baue 23 Blöcke weiter rechts“). Vorher wurde der Auftrag verworfen, und das Modell schaffte Fort
  Knox neben einem Haus nicht.
- Vera gibt Gegenstände ohne Modell, wenn der Wunsch klar ist („gib mir 16 Fackeln“, „kannst du mir Elytren geben?“,
  „einen Stack Fackeln“) – nur bei sicherem Treffer im Register, Prüfung wie immer (Kreativ oder Recht).
- Vera bündelt die Befehle einer Antwort: Braucht einer eine Rückfrage, warten alle auf das „ja“ und laufen danach
  der Reihe nach; „mach das rückgängig“ deckt das ganze Bündel ab.
- Pool ohne LLM: „bau mir einen (kleinen/großen) Pool“ baut ein eingelassenes Becken mit Wasser auf Bodenhöhe,
  Terrasse, Leiter und Licht. Vorher fünf Anläufe über das Modell, darunter Wasser unter einer Abdeckung und eine
  Mulde mit 14 Blöcken Wasser.
- Vera reicht klare Bauaufträge („bau mir …“, „kannst du mir … bauen?“) ohne Modell an den Baumeister – sofort und
  auch, wenn die App gerade einen Bauplan rechnet (vorher HTTP 503, Auftrag verloren). „Pull“ nach einem Artikel
  wird als „Pool“ gelesen; auf unverständliche Sätze fragt Vera nach, statt etwas zu tun.
- Wer über Simple Voice Chat spricht, wird nicht mehr zusätzlich über das App-Mikrofon gehört: Das Plugin meldet der
  App die Spieler im Voice-Chat, die App lässt ihre Sprechtaste ruhen, und Sätze vom App-Mikrofon dieser Spieler
  verwirft das Plugin.
- Vera versteht Mitspieler: Wer in Hörweite einer Vera steht, redet im Chat und im Voice-Chat mit ihr, auch ohne
  eigenen Begleiter; sie handelt mit dessen Rechten. Wegschicken, Mitnehmen und Rückgängig bleiben beim Besitzer.
- Neue Fähigkeiten von Vera: Orte merken und hinbringen (auch zum letzten Tod und zum Bett, Teleport nur im
  Kreativmodus oder mit `baumeister.begleiter.reisen`), Mitspieler und Orte finden, Erinnerungen nach Minuten,
  „was ist das?“ für den angeschauten Block oder das Tier.
- Begleiter „Vera“ (Name einstellbar): „Hey Vera“ oder `/bm begleiter` ruft einen Helfer, der folgt, im Chat antwortet
  (LLM mit eigener Grammatik `begleiter`), exakt rechnet, Blöcke in der Nähe sucht, Notenblock-Lieder spielt,
  Bauaufträge an den Baumeister weitergibt und Gegenstände gibt – Letzteres nur im Kreativmodus oder mit
  `baumeister.begleiter.geben`, Erze suchen im Überlebensmodus nur mit `baumeister.begleiter.suchen`. Seine Antworten
  liest die Baumeister-App auf dem PC des Spielers vor (`POST /baumeister/v1/begleiter/sagen`).
- Zwischenschicht aus den Minecraft-Bauvorlagen (189 Vorlagen aus dem Server-JAR, auf dev01 alle geladen): ganze Dörfer („ein
  Wüstendorf mit 8 Häusern“ – Straße vor dem Spieler, Häuser mit der Tür zur Straße, Dorfplatz am Ende),
  Dorfgebäude in fünf Stilen (Wiese, Wüste, Savanne, Schnee, Taiga; Vorgabe ist das Biom unter dem Spieler), Iglu,
  Segelschiff, Schiffswrack, Endschiff, Wachturm, Ruinenportal, Zelt, Fossil, Ruine. Reine Vorlagen-Aufträge bauen ohne
  LLM in Millisekunden. Schafft das LLM bei einem neuen Bau keinen Plan oder ist es nicht erreichbar, baut der
  Baumeister die passende Vorlage, statt abzusagen („ein Haus mit Pool“ → Dorfhaus). Vera kennt die Vorlagen.
- Gegenstände und Blöcke aus dem ganzen Register des Servers (ItemNames): Vera und Baupläne finden die echte ID
  auch bei Verwechslungen („redstone_comparator“ → comparator), deutschen Namen („Fackeln“, „Diamant Spitzhacke“)
  und Tippfehlern. „redstone“ als Block ist Redstone-Staub statt Ersatzblock Stein.
- Vera erklärt die Bedienung des Baus, in dem man steht: Das Baubuch hält die Erklärung des Baumeisters und die
  Bedienhinweise der Bauteile (gilt für Bauten ab jetzt).
- Die Reparatur sagt, was sie zurücksetzt („8736× dark_oak_slab“).

- Hörfehler: „Rapstone“, „Redstein“ u. Ä. werden vor jedem Bauauftrag zu „Redstone“ (sonst verfehlte „ein Rapstone
  Computer“ die Prozessor-Werkstatt und das LLM legte Redstone-Staub auf 55 Reihen). Vorlagenwörter ab 5 Zeichen
  vertragen ein falsches Zeichen, „Iglo“ ist ein Iglu, kalte Gewässer-Biome geben Schneedörfer.
- Reparieren: Jeder fertige Bau des Baumeisters kommt ins Baubuch (`plugins/Baumeister/bauten/`, Soll-Zustand Block
  für Block, bis 100 000 Blöcke). „Vera, reparier das“ setzt im Bau, in dem der Spieler steht, fehlende oder durch
  anderes Material ersetzte Blöcke zurück – als normaler Bauauftrag mit Rechteprüfung und Rückgängig. Umgelegte Hebel
  und offene Türen bleiben. „Rückgängig“ und Umbauten halten das Baubuch aktuell. Fremde Maschinen repariert Vera nicht.
- Bauprompt kleiner: Die vollen Beschreibungen der Module (~1850 Token) gehen nur bei Redstone-, Maschinen- und
  Farmaufträgen mit; sonst Name und Größe. Ein Haus spart ~1640 Token – mehr Platz für den Plan (vorher 741 Token
  bei 8192 Kontext) und kürzere Wartezeit.
- Bauauftrag mit neuem eigenständigem Bauwerk („ein Haus“, „einen Redstone Computer“) baut frisch am Standort,
  auch wenn in der Nähe ein Bau im Gedächtnis ist (plan/OrderIntent). Vorher plante das Modell so etwas als Änderung
  des alten Baus – zweimal nur Weg und Zaun am alten Platz.
- Begleiter: Aktion `recipe` – Rezepte aus den Rezepten des Servers (Werkbank, Ofen-Arten, Steinsäge), nicht aus dem
  Gedächtnis des Modells. Vera kennt die getesteten Maschinen und Farmen des Baumeisters und weiß, dass es keinen
  Schnee-Befehl gibt.
- Begleiter: Rückfrage vor fill/setblock/clone in Worten („Das macht 726 Blöcke um dich herum zu redstone“), vorher
  gesicherter Bereich und „mach das rückgängig“ (CoreProtect protokolliert vanilla-fill nicht). Prompt: Maschinen
  reparieren kann Vera nicht – ehrlich sagen statt einen Befehl zu raten.
- Begleiter wartet höchstens 45 s auf das Modell (vorher die Bau-Grenze von 900 s samt zwei Wiederholungen) und
  sagt nach 8 s, dass er noch nachdenkt; danach nennt er den Grund. Prompt: Gegenstand gewünscht → geben, nicht
  suchen; keine Wiederholung der letzten Antwort.
- Begleiter für Admins: Aktion `command` führt beliebige Befehle als der Spieler aus (Recht
  `baumeister.begleiter.befehle`, Vorgabe op). Folgenreiche Befehle erst nach „ja“ (30 s). Der Kontext nennt dem
  Modell, ob Befehle und Geben erlaubt sind.
- Begleiter im Spiel über Simple Voice Chat (softdepend `voicechat`, API 2.6.0): Sprachpakete des Spielers mit
  gerufenem Begleiter gehen gesammelt an den Host (`/begleiter/hoeren`), Veras Antwort kommt als Ton
  (`/begleiter/stimme`) und spielt an ihrer Position. Nur angesprochene Sätze oder ein laufendes Gespräch wirken.
- Weltbau mit Küste: Meer, Häfen und Buchten kommen aus der OSM-Küstenlinie (`natural=coastline`, `SeaMask`) statt
  allein aus dem Höhenmodell. Falsch erhöhtes Land im Meer wird bis auf Meereshöhe abgetragen (höchstens 24 Blöcke),
  der Meeresboden fällt vom Ufer weg ab (bis 12 Blöcke), flaches Land am Meer wird Strand. Eine Etappe, deren Küste
  nicht zum Gelände passt (verdrehte Laufrichtung), bleibt ohne Küste. Anlass: Sydney – das Höhenmodell führt
  Darling Harbour/Cockle Bay als Land (11,7 m).
- Häfen und Buchten als Wasser-Relation: Der Weltbau fragt `natural=water`-Multipolygone mit ab; `water=harbour`,
  `bay`, `sea` u. a. werden Meer am Meeresspiegel, Inseln (innere Ringe) bleiben Land, Seen und Flüsse bleiben
  Wasserflächen. In Sydney gibt es an Circular Quay keine Küstenlinie, nur die Relation „Port Jackson“.
- Gruben am Ufer: Landsäulen bis 4 Zellen vom Meer, die mehr als 3 Blöcke unter dem Meeresspiegel liegen (kaputte
  Tiefenpixel, bis y −200), schüttet der Weltbau mit Stein auf; Wiese, Weg und Strand der Säule stehen obenauf.
- Stadt-/Weltbau unter Paper: fehlende Chunks werden außerhalb des Hauptthreads geladen bzw. erzeugt, das Ticket kommt
  erst danach (`ChunkHolder`). Vorher erzeugte jedes Ticket den Chunk synchron – auf dev01 bis 3,6 s Stillstand je
  Chunk. Unter Spigot bleibt der alte Weg. Die Schlusszeile „stadt | ende“ nennt `chunks_async`.
- Schnellaufträge verstehen Geschosszahlen als Wort („zwanzig Stockwerke“, „zehnstöckig“, bis 49) und Grundflächen
  mit „mal“ oder „×“ („20 mal 24“) – vorher gingen solche Aufträge für Minuten ans LLM.
- Stadt- und Weltbau laufen unabhängig von der KI: Sie warten nicht mehr auf eine laufende LLM-Planung (vorher bis zu
  900 s). Wird ein KI-Plan fertig, während ein Stadt- oder Weltbau läuft, merkt sich der Baumeister ihn und baut ihn
  danach von selbst; „stopp“ verwirft ihn, `/bm status` zeigt, dass er wartet. Gebaut wurde Stadt und Welt schon immer
  ohne KI (OpenStreetMap und Höhendaten).
- Client-Mod: laufende Anzeige in der Aktionsleiste über der Hotbar – Blöcke, Prozent, geschätzte Restzeit bzw.
  Pausengrund mit „[K] weiter“.
- Host-App: „Speichern …“ neben „Leeren“ sichert die Live-Konsole als Textdatei (mit Version und Zeitpunkt).
- Vorbereitung für ein eigenes, kleines Baumeister-Modell (`tools/train/README.md`): ein Lehrset-Generator
  (Auftragstext → Plan-JSON aus den festen Bauwegen, nur Pläne ohne jeden Prüfbefund), ein Messgerät für die
  Annahmequote über 24 feste Testaufträge im Gespräch des Plugins, ein QLoRA-Trainingsskript für Qwen2.5-Coder 1.5B und
  der GGUF-Export für die Host-App. Das Plugin selbst ändert sich nicht.
- Bauwerk-Dateien ohne WorldEdit lesen (.schem v1–v3, .litematic, Strukturdateien .nbt) und in Bauschritte
  zurückübersetzen (fill, walls, cylinder, line, set, door), mit Block-für-Block-Vergleich des Rückbaus. Echte Bauten
  kommen so als Vorbilder ins Lehrset (`--vorbilder`, Auftrag je Datei in `vorbilder.json`).
- Minikarte für Bauwelten: `/bm karte` gibt eine Karte, die mitwandert und sich mit dem Blick dreht (oben ist, wohin
  man schaut; `/bm karte drehen aus` = Norden oben). Darauf: die Plotgrenze als roter Rahmen, Straßen des Stadt- und
  Weltbaus weiß, andere Spieler, die laufende Baustelle (rotes X mit Fortschritt), Baumeister und Helfer sowie die
  Reisepunkte (Städte, Gelände, eigene Orte).
  - Zoom mit der Karte in der Hand: Linksklick hinein, Rechtsklick hinaus, je Klick ×1,25 (mit Schleichen ×2), von
    1/16 Block je Kartenpunkt (ein Block 16 Pixel groß) bis 64 (rund 8 km quer); oder `/bm karte zoom 1,5`. Solange
    man sie hält, setzt und bricht man keine Blöcke.
  - Wegpunkte: `/bm karte punkt <Name> [x z]` · `punkte` · `weg <Name>` · `ziel <Name>` – das Ziel (Wegpunkt oder
    Reisepunkt) steht mit Entfernung auf der Karte, am Rand in seiner Richtung, wenn es weiter weg ist.
  - Die ganze Karte ist aufgedeckt: Ein Vorab-Scan liest die Regionsdateien aller Kartenwelten direkt von der Platte
    (eigener Thread, niedrige Priorität; der Server lädt dafür keinen Chunk) und füllt daraus die Übersicht – alles,
    was je erzeugt wurde, steht sofort auf der Karte. Danach alle 10 Minuten nur geänderte Regionsdateien. Dazu
    schreibt die Karte beim Laden und Entladen jedes Chunks mit; gespeichert unter `plugins/Baumeister/minikarte/<welt>/`.
  - Noch unbekannte Stellen sind ein dunkles Schachbrett statt durchsichtig – vorher schien das beige Kartenpapier
    durch und das Bild wirkte im Rahmen verschoben.
  Erlaubt mit `baumeister.bauen` oder `baumeister.reisen`; Einstellungen unter `karte.*` in der `config.yml`. Gelesen
  wird mit festem Zeitbudget je Tick, die TPS werden nie abgefragt – Ersatz für Cartographer2, das auf dev01 den
  Server-Thread auslastete. Für Admins schreibt `/bm kartenbild <welt> <x> <z> [zoom] [blick]` das Bild als PNG nach
  `plugins/Baumeister/minikarte/`.
- Der Baumeister bessert LLM-Pläne selbst nach, statt sie in eine Reparaturrunde zu schicken oder zu verwerfen
  (`PlanAutoFix`): Grundriss von Haus, Hochhaus, Burg und Garten in den gültigen Bereich, Vollklötze hohl, Pfeiler
  unter schwebenden Teilen (bis 32 Blöcke Höhe), knapp zu große Bauten (bis 30 % über `bau.max-ausdehnung`) gekürzt.
  Der Spieler liest im Chat, was nachgebessert wurde; die App bekommt es mit der Ergebnis-Meldung.
- Bauteile ohne erkanntes Maschinenwort werden nicht mehr verworfen: Besteht der Plan überwiegend aus Bauteilen
  (ein Zahlenschloss, eine Klingel), wird er gebaut; sind sie nur Verzierung eines Gebäudes, fallen sie weg.
- LLM-Anfragen werden bei Verbindungsabbruch, verweigerter Verbindung und HTTP 502/503/504 nach 3 s und 10 s
  wiederholt (nicht bei Zeitüberschreitung und 400).
- App: Der Reiter „Verlauf“ zeigt oben die Zählung (gebaut, davon selbst nachgebessert, verworfen, abgesagt), im
  Tooltip des Ergebnisses steht, was nachgebessert wurde und wie viele LLM-Antworten nötig waren. Die Client-Mod
  meldet dasselbe.
- Bausteine eines Prozessors in der Maschinenbibliothek, jeder im Spiel geprüft (MachineProbe, zwei Baurichtungen):
  `d_speicher` (getakteter D-Speicher über verriegelte Verstärker), `register_4bit` (vier davon, ein Takt),
  `alu_1bit` (Rechenwerk-Scheibe mit UND, ODER, XOR und Addition samt Übertrag, alle 32 Belegungen),
  `vergleicher_2bit` (A>B, A=B, A<B) und `addierer_2bit`.
- Verbundene Prozessor-Teile (Stufe 2), ebenfalls im Spiel geprüft: `addierer_4bit` (vier Volladdierer, der Übertrag
  läuft von Stufe zu Stufe) und `akkumulator_4bit` – jeder Takt addiert B zum gespeicherten Wert (Master-Slave-Speicher
  hinter dem Rechenwerk, Rückführung in dessen A-Eingänge). „Bau mir einen Prozessor/Computer aus Redstone“ baut ohne
  LLM eine Prozessor-Werkstatt mit dem Akkumulator in der Mitte, dazu Takt, Zähler und ALU-Scheibe.
- Baumeister-Client (`client/`): Fabric-Mod für Minecraft 1.21.11, die ohne Server-Plugin mit der eigenen Spielfigur
  baut – wie ein Spieler (Rechtsklick mit Schleichen, Blockzustand über die Item-Komponente `block_state`, Wegsuche zu
  einem Standplatz in Reichweite, Schicht für Schicht) oder per `/setblock`/`/fill` (OP). Jede eigene Eingabe hält den
  Bau im selben Tick an, `K` bzw. `/kbau weiter` macht weiter; Prüfdurchgang mit einmaligem Nachbessern, Rückgängig,
  Anzeige oben links, Ergebnis an die App. Plan-Logik aus dem Plugin (`PlanPrompt`, `LlmProtocol` neu in `plan`).
- Plot-Modus für PlotSquared: „Baumeister, bau das World Trade Center auf meinem Plot“ bebaut den ganzen Plot, auf
  dem der Spieler steht – Hauptbau (LLM, Recherche oder Schnellauftrag) mittig, dazu Rasen, Hecke mit Eingang, Weg mit
  Laternen zur Front, Blumenbeete und Azaleen. Die Recherche verkleinert echte Bauwerke so, dass sie auch in der Breite
  auf den Plot passen; ein zu großer Plan geht zur Nachbesserung, ein zu großer Schnellauftrag (Fort Knox auf 42 × 42)
  wird mit Maßen abgesagt. Fremde Plots sind tabu, was schon auf dem Plot steht, schützt der Überbau-Schutz
  („ersetzen“ hebt das auf).
- Ergebnis im Spiel an die App (`gui.ergebnis-melden`, Vorgabe an): gebaut (Blöcke, Plot), verworfen (mit Grund),
  abgesagt oder abgebrochen – die App zeigt es in Karte, Konsole und im neuen Reiter „Verlauf“.

### Entfernt
- Veras „Moment, ich denke noch nach …“ (hing an Ticks und kam mit `/tick rate 2000` bei fast jeder Frage).

### Geändert
- Vera führt ohne Rückfrage nur noch Harmloses für dich selbst aus: Zeit, Wetter, Chat, eigener Spielmodus, Fliegen,
  Heilen, Effekte, XP, Geben ohne Daten, eigener Teleport. Alles andere – auch jede Zeile mit Daten in `{…}` und jedes
  fremde Ziel – erst nach einem getippten „ja“.
- Drehen und Spiegeln nach den Regeln von Minecraft für jeden Zustand; ausdrücklich genannte Zustände bleiben, und was
  sich nicht umsetzen lässt, meldet der Baumeister, statt es still wegzulassen.
- Rechte gelten beim Setzen, nicht beim Auftrag – auch in Warteschlange, Vorschau und Fortsetzung.
- „Merk dir das Bauwerk“: nur mit Baurecht im ganzen Bereich; der Name gehört dem, der speichert; Daten nur von
  Admins, ältere Bauteile laden ohne Daten.
- Fallende Gegenstände unterdrückt der Bau nur noch direkt neben gerade gesetzten Blöcken, nicht mehr auf der ganzen
  Baustelle.
- Vera führt Befehle nach einer **gesprochenen** Bitte nur noch nach einem **getippten** „ja“ aus – auch harmlose
  wie „mach Tag“. Ein Hörfehler oder ein fremder Satz über die App löst so nie einen Befehl aus.
- dev01: Server unter `/srv/minecraft` (NVMe), Dienst, Start-Skript und Sicherung zeigen dorthin; ohne eingehängte
  Platte startet der Dienst nicht mehr (vorher hätte er eine leere Welt angelegt). Neuer Name in der Serverliste
  („✦ Baumeisterland ✦ CityBuild & Abenteuer / KI baut mit • Begleiterin Vera • 26.2“) und ein neues, flächiges Bild.
  Paper schneller bei Explosionen, Truhen und schnellem Fliegen, Chunks werden im Hintergrund geschrieben; Java 25 mit
  kompakten Objektköpfen (gemessen 8–9 % weniger Heap, 45 s bis 15 min nach dem Start). Ein Chunk speichert höchstens
  16 Pfeile und Erfahrungskugeln und je 8 Enderperlen, Feuer- und Schneebälle (vorher unbegrenzt) – Fallen und
  Farmen machen Chunks nicht mehr schwer.
- Nachbesserung großer Pläne: Die Korrekturrunde schickt nur noch Auftrag, Fakten, den letzten Plan und die
  Fehlerliste – vorher alles noch einmal plus jeden alten Plan. „XP-Farm“ kam so auf 8 028 Token bei 8 192 Kontext
  („Prompt zu lang“), und der Auftrag war verloren.
- Das Gelände-Datapack bekommt beim Start die Pflichtschlüssel neuerer Minecraft-Fassungen (ohne sie startete 26.2
  nicht), die Minikarte findet die Regionsdateien im neuen Weltordner von 26.x.
- Logik-Formeln dürfen 480 statt 160 Zeichen lang sein, auch im Bauplan des Modells (Schema und Host-Grammatik). Für
  das Modell gilt das erst mit der neuen Host-App auf dem Spiele-PC.
- Referenzhöhe (Recherche) ist im letzten Versuch kein Absagegrund mehr, solange der Plan mindestens 30 % der
  Vorbildhöhe erreicht – gebaut wird mit Hinweis.
- Schnellgang realistischer: Die Schätzung „Block für Block“ rechnet das Laufen der Helfer mit ein (gemessen Faktor
  ~3). Ein Bunker mit 2 Ebenen steht in 79 s statt 331 s; vorher liefen Bauten bis 5,5 min Block für Block, obwohl ab
  `bau.zieldauer-sekunden` (120) der Schnellgang gelten soll.
- Bunker und Fort Knox ohne LLM, unter UND über der Erde: „Baumeister, baue mir hier Fort Knox“ baut das Golddepot nach
  den echten Maßen (32 × 37 m, 13 m hoch, Wachposten an den Ecken, Zaun mit Torposten) samt Untergeschoss und Tresor
  über zwei Ebenen voller Gold; „bau mir einen (kleinen/großen) Bunker“ einen getarnten Eingang mit Panzertür und
  darunter 1–3 Ebenen mit Lager, Lüftung und Tresorraum. Innenräume werden ausgehöhlt, Leitern führen durch alle
  Decken, Eisentüren öffnen per Knopf.
- „Bau mir einen Taschenrechner (der rechnen kann)“: der im Spiel geprüfte 2-Bit-Addierer aus dem Logik-Compiler
  (A + B mit je 0–3), ohne LLM – auch mit Zusätzen wie „mit Redstone“ oder „schau im Internet nach einem Plan“.

### Geändert
- Bessere LLM-Pläne: Wortgleiche Schritte zählen nur einmal (an der Stelle des letzten Vorkommens) – kleine Modelle
  schrieben denselben Schritt bis zu 40-mal. Einzelne schwebende Blöcke (Gruppen bis 3, zusammen bis 8) und
  Glasscheiben außerhalb jeder Wand lässt der Baumeister selbst weg, statt den Plan in die Reparaturrunde und danach
  in die Absage zu schicken.
- Ein Bau, der bis an den Weltboden reichen würde, wird mit Begründung abgesagt; vorher wurden diese Zellen still
  übersprungen (in einer flachen Welt hätte ein Bunker das Grundgestein durch Luft ersetzt).
- Mehrere Helfer auch bei normalen Bauten: „bau mir eine Burg mit 6 Helfern“ (nur dieser Bau), `/bm helfer <0–16>`
  (gilt sofort für den laufenden eigenen Bau und für die nächsten, bis `/bm helfer auto` oder Neustart) und im Chat
  „mehr helfer“/„weniger helfer“ (je zwei). Im laufenden Bau verteilt der Baumeister den Rest des Abschnitts sofort
  neu; im Schnellgang steigt das Tempo anteilig mit. `/bm helfer` dürfen jetzt auch Spieler mit `baumeister.bauen`
  (für ihren eigenen Bau); den Stadtbau steuern weiter nur Admins. Ohne Wunsch gilt die Größenregel wie bisher.
- Recherche vor dem Planen: Nennt der Auftrag ein echtes Bauwerk („bau den Kölner Dom“), holt der Baumeister aus
  Wikipedia und Wikidata Höhe, Länge, Breite, Stockwerke, Art, Baustil, Material und drei Sätze Beschreibung und gibt
  sie dem LLM mit – 1:1 oder gleichmäßig auf `bau.max-ausdehnung` verkleinert. Der Plan-Prüfer schickt einen Plan
  zurück, dessen Höhe mehr als 20 % daneben liegt. Fail-open, 30 Tage Zwischenspeicher (`plugins/Baumeister/wissen/`),
  Config `llm.recherche`, `llm.recherche-kontakt` (Wikimedia verlangt eine Kontaktangabe). Abschaltbar auch in der
  Baumeister-App (Einstellungen → „Internet-Recherche erlauben“): Vor jeder Recherche fragt das Plugin die App
  (`GET /baumeister/v1/recherche`); was nachgeschlagen wurde, zeigt die App in Konsole und Karte.
- Zu einer Straße reisen: `/bm ort Eberdingen Keplerstraße`, `/bm ort 71735 Eberdingen kepplerstrasse 5` – Straße
  (tippfehlertolerant, ß/ss, „str“) und Hausnummer aus den Kartendaten der gebauten Stadt (Zwischenspeicher, kein
  Netz); mit Nummer geht es auf die Straße vor das Haus, Blick zum Haus. Dafür merkt sich jeder Stadtbau Mitte, Welt
  und Bauplatz in `plugins/Baumeister/stadt/staedte.json`.
- Maschinenhalle als Schnellauftrag ohne LLM: „bau mir eine (große/fette/kleine) Maschine“ stellt nützliche Anlagen
  aus der Bibliothek (Schmelzanlage, Sortierer, Sammelstelle, Zuckerrohrfarm, Wasser- und Abwärtsfahrstuhl) nach
  ihren echten Maßen mit 2 Blöcken Abstand in eine Halle mit Boden, hohlen Wänden, Tür, Fenstern, Dach und Licht –
  sofort statt nach Minuten. Klein = 2 Anlagen, ohne Zusatz 3, groß/fett/riesig alle 6.
- Städte an ihrer echten Stelle: `/bm stadt los echt` baut den geplanten Ort in der Stadtwelt dort, wo er auch in der
  Erdwelt liegt (dieselbe Projektion, dev01: airocean) – München und Nürnberg liegen dann 157 km auseinander statt
  nebeneinander. Ohne `echt` bleibt die Mitte unter dem Spieler.
- Postleitzahl statt Ortsname: `/bm stadt 71735`, `/bm stadt plz 71735 2000`, `/bm stadt 8001 ch` – strukturierte
  Nominatim-Suche nach der PLZ-Fläche (fünf Ziffern ohne Land: `stadt.plz-land`, Vorgabe `de`); der Ort heißt dann
  „71735 Eberdingen“. Gilt auch für Weltbau, Reise und Gelände.
- Stadtansicht im Baumeister-Host: Stadt- und Weltbau melden je Etappe eine Draufsicht (Kategorien Gebäude, Straße,
  Fußweg, Wasser, Grün, Baum, Gleis, Brücke) und einmal je Sekunde den Fortschritt an `/baumeister/v1/stadt`
  (`HostReporter`, `CityRaster`), asynchron und fail-open; Config `gui.stadt-anzeige`, `gui.url`.
- Weltbau im Host: Kachelübersicht der Erdwelt (fertig, gescheitert, wartend, laufend samt Phase, Automatik,
  Spieler) als Nachricht `welt`; frühere Kacheln bleiben auf der Karte stehen, statt von jeder neuen ersetzt zu werden.
- Ganze Karte vorab: Nach dem Planen legt der Stadtbau alle Kartenkacheln (1280 m) des Orts in Bau-Reihenfolge in den
  Zwischenspeicher, eine Abfrage zugleich; der Fortschritt zeigt „Karte 3/12“. Vorher stand der Bau bei jeder neuen
  Kachel bei „lädt Karte“ (dev01, Sindelfingen: 5,5 von 10 min). Der Weltbau legt die Karte der nächsten Kachel während
  des Baus in den Zwischenspeicher (gleiche Drossel, eine Abfrage zugleich).
- Stadtbau übersteht Pause und Neustart: Der Stand liegt unter `plugins/Baumeister/stadt/fortsetzen.json` (Ort mit
  Koordinaten, Welt, Mitte, erste unfertige Etappe, Pause). 10 s nach dem Serverstart geht es von selbst weiter –
  pausiert bleibt pausiert –, ohne Nominatim und mit der Karte aus dem Zwischenspeicher. `/bm weiter` setzt einen
  unterbrochenen Bau auch von Hand fort, `/bm stop` und das Bauende löschen den Stand. Config
  `stadt.fortsetzen-nach-neustart` (Vorgabe `true`).
- Lokaler Server: `server/windows/start.ps1` startet den von dev01 kopierten Server unter Windows (Java- und
  Datapack-Prüfung, Heap nach Arbeitsspeicher, G1 wie dev01); Anleitung `docs/lokaler-server.md`.
- Syntax-Reparatur der LLM-Antwort (`plan/JsonRepair`): Eine bei max_tokens abgeschnittene Antwort wird gebaut,
  soweit sie vollständig war (der halbe letzte Schritt fällt weg), statt verworfen zu werden und in der
  Reparaturrunde den Kontext zu sprengen. Dazu Markdown-Zäune, Vor-/Nachtext, Kommentare, fehlende und überzählige
  Kommas, einfache Anführungszeichen, Schlüssel ohne Anführungszeichen, `True/False/None`, Zeilenumbrüche in Texten und
  falsche Klammern. Der Spieler erfährt, was repariert wurde; eine nötige Reparaturrunde bekommt die kürzere,
  reparierte Fassung.
- Bauformen `castle` (Burg mit Mauerring, Zinnen, Wehrgang, Ecktürmen und Torhaus), `wall` (Mauerzug mit Zinnen und
  Wehrgang), `bridge` (Bogen- oder Balkenbrücke mit Geländer und Laternen), `garden` (Wege, Beete, Zaun mit Tor) und
  `tree` (Laub- oder Nadelbaum, 4–32 Blöcke); neue Planfelder `width` und `style`, Host-Grammatik nachgezogen. Das LLM
  nennt nur Ort, Maße und Material, die Geometrie rechnet das Plugin. Auch als Schnellauftrag ohne LLM („bau eine
  Burg“, „eine Brücke über 20 Blöcke“, „eine kleine Tanne“) mit Größe und Material („aus Sandstein“).
- Plan-Prüfer im Bauablauf: Tür/Fenster außerhalb der Wand, Angehängtes ohne Halt, eingemauertes Wasser, Vollklotz
  statt Hohlraum, schwebende und überlappende Teile gehen als kurze Rückmeldung in die Reparaturrunde; ein sauberer
  Plan kostet keinen zweiten LLM-Aufruf. Config `llm.plan-prüfer` (`streng` | `reparieren` | `aus`).
- Anbau mit fester Anschlusswand: Das Plugin wählt Bauwerk und Seite, gibt Wandebene, Bodenhöhe und freie Tiefe vor
  und rückt einen daneben, darauf oder hinein geratenen Plan an die Wand; die bestehende Wand wird geteilt statt ersetzt.
- Logik-Compiler mit bis zu 4 Ausgängen: Formeln mit `;` getrennt (`/bm logik S = A xor B xor C; U = A&B | C&(A xor B)`,
  im LLM-Plan im selben `expr`) teilen sich die Hebel, je Ausgang eine Lampe – Volladdierer, 2-Bit-Addierer,
  Vergleicher, Dekoder. Auf dem Testserver 16 Formeln × 4 Richtungen = 64/64.
- Maschinen `volladdierer`, `impulsverlaengerer` (Knopfdruck ~2,5 s länger), `schmelzanlage` (Ofen mit Trichtern) und
  `item_sortierer` (eine Sorte) – alle 28 Maschinen × 4 Richtungen = 112/112 auf dem Testserver.
- Stadt- und Weltbau: Straßenlaternen, Brücken aus OSM (gehobene Fahrbahn mit Rampe und Geländer, über Wasser mit
  Pfeilern), Gleise auf Schotter, Zäune/Hecken/Mauern mit Lücken an Einfahrten, Parkplätze mit Stellplatzlinien,
  Straßennamen-Schilder an Kreuzungen (beidseitig beschriftet, gewachst), Baumarten Eiche/Birke/Fichte.
- Erdwelt: Senken unter Meereshöhe ohne Verbindung zum Ozean bleiben trocken (Death Valley, Qattara), Seen unter 0 m
  bekommen ihre eigene Wasserfläche (Kaspisches Meer, Saltonsee), Polder bleiben trocken (`erde.senken`). Bei
  Netzausfall geschätzte Chunks werden neu gemalt, sobald die Höhendaten da sind, Spielerbauten bleiben
  (`erde/geschätzt.txt`, `erde.neu-malen`). Weltbau über der Datumsgrenze in zwei Overpass-Abfragen. `/bm wo` nennt
  Wasserfläche und trockene Senke.
- Komfort (Config `komfort:`): Fortschrittsbalken (BossBar) für Bau, Stadtbau und Weltbau; Auftrags-Warteschlange
  statt Absage (`/bm warteschlange`, `warteschlange abbrechen [platz|alle]`); Bauvorschau als Partikel-Umriss
  (`/bm vorschau <auftrag>`, „Baumeister, zeig mir …“, „ja“ baut); `/bm rückgängig` überlebt Neustart und `/bm neuladen`.
- Config-Migration: Fehlende Schlüssel kommen beim Start samt Kommentar in die bestehende Config, vorhandene Werte
  bleiben, vorher eine Sicherung unter `sicherung/`; eine kaputte Datei wird nie überschrieben. Die Prüfung beim Start
  und bei `/bm neuladen` meldet unbekannte Schlüssel (mit Vorschlag), falsche Typen und Werte außerhalb der Grenzen.
- `/bm leistung` (Admin): TPS, Tick-Abstand und Rechenzeit des Hauptthreads der letzten Minute, Heap, GC-Pausen,
  geladene hohe Chunks gegen das HeapGuard-Budget, laufende Aufträge und Warteschlangen. `/bm neuladen erzwingen`.
- `server/dev01/`: Startskript mit festem 5-GiB-Heap und begründeten G1-Optionen, systemd-Drop-in
  (`SuccessExitStatus=143`), dazu `docs/server.md` mit Empfehlungen und Rückweg.
- Config `stadt.gleiche-zellen-je-block` (Vorgabe 4, 1 = altes Tempo-Verhalten); `stadt.radius-max-ganz` steht jetzt
  in der Vorlage.
- Die ganze Erde 1:1 als eine Welt (Datapack-Dimension `baumeister:erde`, nach `/bm gelaende einrichten` und
  Neustart): Chunks entstehen beim Erkunden aus echten Höhendaten, Projektion Airocean (BuildTheEarth, MIT),
  Meereshöhe echt, Gebirge gleichmäßig abgesenkt. `/bm erde`, `/bm reise <Ort|lat,lon>`, `/bm wo`; Config `erde:`.
- Weltbau: `/bm weltbau <Ort> [radius]`, `weltbau auto an|aus`, `status`, `stop`, `rückgängig [tx tz]` baut
  OpenStreetMap kachelweise auf das echte Gelände der Erdwelt (Sockel am Hang, Straßen folgen dem Boden), schonend
  zu Overpass (eine Abfrage zugleich, 20 s Abstand). Eigenes Rückgängig je Kachel; Config `weltbau:`.
- Reisepunkte: `/bm orte` (anklickbar), `/bm ort <Name>`, `ort merken`/`löschen`; jede gebaute Stadt, jedes Gelände
  und jedes Reiseziel trägt sich selbst ein. Neues Recht `baumeister.reisen` (reicht allein für orte/ort).
- Logik-Compiler: `/bm logik <Formel>`, im Chat `Baumeister, logik: …` und im LLM-Plan `op: logic` bauen aus einer
  Formel (bis 5 Eingänge, deutsch/englisch/Symbole) eine minimierte, geprüfte Redstone-Schaltung mit Hebeln und Lampe.
  Auf dem Testserver 11 Formeln × 4 Richtungen bestanden.
- Gedächtnis: Der Baumeister merkt sich je Spieler den letzten Bau eine Stunde lang; Nachträge („Dach rot“, „ein
  Stockwerk mehr“) ändern genau diesen Bau (`mode: update`), Überflüssiges wird weggeräumt. „Baumeister, vergiss das“
  fängt neu an.
- Augen: Das LLM sieht den Bauplatz als Draufsicht und Vorderansicht in Zeichen statt nur als Liste.
- Gelände 1:1 aus echten Höhendaten: `/bm gelaende <Ort|lat,lon> [radius]`; mit `/bm gelaende einrichten` (Datapack,
  danach Neustart) in einer 4 064 Blöcke hohen Welt auch senkrecht 1:1 mit echter Meereshöhe. Höhen: Mapzen/AWS
  Terrain Tiles.
- Bauform `house`: Wohnhaus mit 1–4 Geschossen, Tür, Fenstern, Leiter, Satteldach und Einrichtung nach Größe.
- Schnellaufträge ohne LLM (Hochhaus mit N Stockwerken, Maschinen per Name); Auftragsfakten für das LLM mit Planabgleich und Rückfall.
- Maschinen `kombischloss`, `pfeilfalle`, `zaehler` (2 Bit), `zuckerrohrfarm` – je 4 Richtungen auf dem Testserver bestanden.
- Stadt: Innenhöfe (Multipolygone), Plätze als Fläche, keine Tunnel an der Oberfläche, Durchgänge; Overpass gzip, gepackter Kartenspeicher.
- Kisten und andere Behälter mit Inhalt werden nie überbaut.
- Steuerwörter im Chat (stopp, status, rückgängig …) gehen nie ans LLM, auch außerhalb des Stadtbaus.
- Tab-Vervollständigung nach Rechten, mit Aliasen und Vorschlagswerten.
- Ganzer Ort: Ohne Radius baut `/bm stadt <Ort>` den Ort so weit, wie Nominatim ihn umgrenzt (vorher fest 150 m).
- Kirchen und Design: Gebäudeteile (Kirchtürme, Turmhelme) mit eigener Höhe und Unterkante, spitze Helme, Farben und Baustoffe aus OSM für Fassade und Dach, Höhen bis 170 m.
- Zehn weitere geprüfte Maschinen: `nand_gatter`, `nor_gatter`, `xnor_gatter`, `t_flipflop` (Kupferbirne), `lampenreihe`, `nachtlicht`, `klingel`, `glocke`, `abwaerts_fahrstuhl`, `sammelstelle`. Die Funktionsprobe kann in Abläufen Befehle ausführen (Uhrzeit, Gegenstände) und Behälterinhalte prüfen.
- Neue geprüfte Maschinen: `halbaddierer` (Summe und Übertrag – erste Stufe eines Rechenwerks), `rs_speicher` (1-Bit-Speicher), `automatiktuer` (Eisentür mit Druckplatten), `wasserfahrstuhl` (Blasensäule). Die Funktionsprobe prüft jetzt auch Abläufe (Speicher) und Blockzustände (Tür offen, Blasensäule).
- Bauform `skyscraper` für Hochhäuser: Das LLM nennt Grundriss, Geschosse und Material, das Plugin baut Geschosse (4 Blöcke), Decken, Fensterbänder, Licht, Leiterschacht, Eingang und Dach. Bauteile setzt das LLM nur noch, wenn ausdrücklich eine Maschine verlangt ist.
- Schnellgang für große LLM-Bauten: Was Block für Block länger als `bau.zieldauer-sekunden` (120) bräuchte, setzt der Baumeister mit mehreren Blöcken je Tick.
- Großstädte in Etappen (256 m) mit Kartenkacheln (1280 m), Rückgängig auf der Platte ohne Obergrenze, Lastregelung gegen Server-Stillstand beim Erzeugen neuer Chunks, `/bm stadtwelt` für eine flache Bauwelt.
- Städte und Dörfer 1:1 aus OpenStreetMap: `/bm stadt <Ort|lat,lon> [radius]` plant Gebäude (Grundriss, Höhe, Etagen, Fenster, Eingang, Dach), Straßen, Wasser, Grünflächen und Bäume; `/bm stadt los` baut mit dem Baumeister und mehreren Helfern (gemessen rund 40 000–50 000 Blöcke/s). Kartendaten © OpenStreetMap-Mitwirkende, ODbL.
- Stadtbau im Lauf steuern: `/bm pause`, `weiter`, `tempo`, `helfer`, `fortschritt`, `komm` und Chat-Steuerwörter an den Baumeister („schneller“, „mehr helfer“, „stopp“, „komm her“ …) ohne Umweg über das LLM; „<Ort> nachbauen“ im Chat.
- Kartenantworten werden 30 Tage unter `plugins/Baumeister/karten/` zwischengespeichert.
- Eingebaute, auf dem Testserver geprüfte Maschinen (NICHT/ODER/UND/XOR-Gatter, Takt, Kolbentür) als Bauteile im Code; das LLM platziert sie mit `op: module`, Admins bauen sie direkt mit `/bm maschine`. Funktionsprobe `MachineProbe` per RCON.
- Bauplatz-Scan vor jedem Auftrag: bestehende Bauwerke (Box, Materialien, Türen, Grundriss, Anschlussstellen) gehen im Plan-Koordinatensystem an das LLM; Anbauten teilen eine Wand statt durchzubauen.
- Überbau-Schutz: Ein Plan, der mehr als `bau.max-überbauen` Blöcke eines Bauwerks verändert oder bei „an/anbauen/daneben“ nichts berührt, geht zur Nachbesserung zurück; sonst Absage. „umbauen/abreißen/ersetzen“ hebt den Schutz auf.
- Technik: `facing` up/down, Wandbefestigung, Redstone-Leitungen werden wie in Vanilla verbunden; Angehängtes (Fackeln, Hebel, Redstone, Schienen) entsteht erst nach seinem Halt.
- Bauteil-Bibliothek über WorldEdit: `/bm bauteil <name> <vorne> <beschreibung>` speichert die Auswahl, `/bm bauteile` listet, das LLM setzt Bauteile per `op: module` gedreht ein.
- Helfer: ab 300 Blöcken ein zweiter, ab 700 ein dritter NPC; Schichten werden nach Seiten geteilt, mit Sperre zwischen den Schichten.
- Konsole: je Bauschritt und alle 10 % eine Zeile, Dauer des Bauplatz-Scans.

### Behoben
- Vera kommt auch nach einem Serverneustart zurück (`begleiter-wiederkommen.json`). Vorher lebte die Liste nur im
  Speicher: Nach dem Update-Neustart um 22:37 fehlte Vera, und im Voice-Chat hörte dich niemand.
- „Ich höre zwei Stimmen“: Veras Sätze laufen im Voice-Chat nacheinander statt übereinander, in der Reihenfolge,
  in der sie fallen. Im Abenteuer verspricht sie nichts mehr, was danach abgelehnt wird – verbotene Aktionen der KI
  fallen vorher weg, und auf „wo finde ich Diamanten?“ nennt sie die Höhe statt einer Suche.
- Verhörtes „bau mir“ („bom mir“, „Bauch mir“), „Abendreuer“ und der Name am Satzende („Abenteuer starten, Vera.“)
  werden verstanden; „Kannst du mir helfen?“ gibt im Abenteuer den Tipp zur Etappe.
- Im Abenteuer baut der Baumeister auch auf direktem Weg nicht mehr („Baumeister, bau …“, `/bm bau`, Vorschau) –
  vorher sperrte nur Vera. Vera steht am Boden neben dir statt auf deiner Höhe zu schweben. „Wo bin ich gestorben?“
  und „wo liegen meine Sachen?“ beantwortet sie ohne KI. Schickst du sie weg, verschwindet die Abenteuer-Leiste, und
  das Abenteuer wartet, bis du sie wieder rufst. Fehlt eine Welt aus `begleiter.abenteuer.welten`, steht es im Log.
  `/bm` zeigt die Begleiter- und Abenteuerbefehle.
- Vera kommt nach einem Verbindungsabbruch von selbst zurück. Vorher war sie nach dem Wiederkommen weg, und mit
  Simple Voice Chat hörte dich dann niemand mehr: Das Plugin schneidet nur mit, wer eine Vera hat, und die
  Sprechtaste der App ruht im Voice-Chat. „Können wir ein Abenteuer machen?“ startet das Abenteuer, und „wo geht's
  lang?“ ohne Abenteuer teleportiert nicht mehr (die KI hatte `tp` erfunden).
- Der Installer duzt wie der Rest der Anwendung (64 Texte aus dem deutschen Sprachpaket von Inno Setup).
- Veras Befehle mit vertauschten Koordinaten laufen jetzt wie gemeint: „setblock ~ ~ 1 tnt“ setzt einen Block über
  dem Spieler statt auf Höhe 1, eine Zahl hinter dem Block („tnt 10“) fällt weg. Vera weiß, wie TNT gezündet wird.
- „bau mir fort, Max“ (verhört) ist wieder Fort Knox; „Elitren“ sind Elytren.
- Ist die App aus, schreibt das Plugin eine Zeile beim Ausfall, alle 15 Minuten eine Erinnerung und eine bei der
  Rückkehr – vorher zwei Zeilen je Minute. Die Minikarte scannt auch nach `/tick rate` nur alle 10 Minuten (vorher
  alle 6 s bei Tickrate 2000).
- Anbau an den letzten Bau („noch einen Pool zu dem Bunker“) räumt den alten Bau nicht mehr weg. Vorher lieferte
  das Modell bei "mode":"update" nur einen Teil des Plans, und der Baumeister riss 6 410 Bunkerblöcke ab. Jetzt bleibt
  der alte Bau stehen, solange der Auftrag nichts wegnehmen oder ersetzen will, und für reine Anbauten genügen dem
  Modell die neuen Schritte.
- Scheitert die Korrekturrunde (etwa „Prompt zu lang“ bei vollem Kontext), baut der Baumeister den letzten Plan,
  statt den Auftrag ganz zu verlieren.
- Frosteis wird als Packeis gebaut – es schmolz sonst nach Sekunden weg.
- Stadtansicht im Host: Startet die App mitten in einer Kachel neu, schickt das Plugin nach der ersten gelungenen
  Sendung den Auftrag samt bisheriger Etappen und die Kachelübersicht erneut; die Übersicht geht außerdem spätestens
  jede Minute noch einmal raus. Vorher blieb die Karte bis zur nächsten Kachel leer.
- Überbau-Schutz: Wörter wie „dreißig“, „Geländer“, „Länder“ oder „Rändern“ schalteten ihn ab (Teilwort-Treffer auf
  „reiß“/„änder“) – „bau ein Hochhaus mit dreißig Stockwerken“ durfte quer durchs Bestehende bauen. Als Umbauwunsch
  zählen jetzt nur Wörter, die mit umbau/abreiß/ersetz/überbau/renovier/(ver)änder/reiß/einreiß/niederreiß beginnen,
  oder „neu gestalten“.
- Plan-Prüfer meldet auch einen `fill` quer durch eine Logik-Schaltung und übersetzt jede Formel nur noch einmal je
  Schritt statt je Zelle.
- Stadtbau: Nach Stopp oder Neuplanung fragte eine laufende Kartenabfrage öffentliche Overpass-Dienste bis zu ~9-mal
  weiter ab; jetzt ist nach dem Schließen Schluss (auch das Rauschen „karte_vorab_fehlgeschlagen“ fällt weg).
- `stadt.overpass-url` (eigene Einzelinstanz) wirkt wieder – vorher gewann immer die mitgelieferte Liste.
- `/bm ort <Stadt> <Straße>` hält nur noch passende Straßen im Speicher statt aller Kacheln der Stadt.
- Host-App: Spielergebnisse zu Aufträgen über 160 Zeichen (oder mit vielen Umlauten) landeten als eigene Zeile; ältere
  Nachbesserungsrunden zeigen „nachgebessert“ statt dauerhaft „Noch keine Meldung“.
- Client-Mod: Bau und Rückgängig brechen beim Welt- oder Dimensionswechsel ab statt in der fremden Welt weiterzubauen;
  `/kbau stop` wirkt auch während der Planung; zurückgestellte Stellen zählen je Durchgang; Bau- und Restzeit ohne
  Pausen; Einstellungsdatei wird beim Laden geprüft (Tempo 0 hängte den Bau still).
- Redstone-Auffrischen nach dem Bau lief erst, nachdem die Baustelle ihre Chunk-Tickets freigegeben hatte – ohne
  Spieler in der Nähe las es dann in schon entlassenen Chunks. Jetzt hält die Baustelle die Tickets bis danach.
- Nach einer bestätigten Vorschau meldete die App „ohne Nachbesserung“, auch wenn der Plan nachgebessert war.
- `fill` mit `hollow:true` baut jetzt die Hülle wie `hollow` – vorher massiv, obwohl der Plan-Prüfer den Schritt als
  hohl durchließ (`fill air` räumt weiter ganz aus).
- Ein normaler Bauauftrag (auch `logik …`) startet nicht mehr, solange ein Stadt- oder Weltbau läuft – beide hielten
  Chunk-Tickets und gaben sie sich beim Stoppen gegenseitig frei.
- Kaputte `config.yml` beim Start: Das Plugin bleibt aus und fasst die Datei nicht an (Meldung `konfig | kaputt` mit
  Zeile und Spalte). Vorher lief es mit den JAR-Vorgaben weiter, und das nächste Speichern überschrieb die Datei.
- Minikarte gab es in der Erd- und Geländewelt nicht („In dieser Welt gibt es keine Minikarte“): Ohne `karte.welten`
  galt nur Environment NORMAL, die beiden Bauwelten sind aber eigene Dimensionen (CUSTOM). Jetzt jede Welt außer
  Nether und End.
- Weltbau blieb nach dem ersten Vorausladen stehen: Das Vorausladen der nächsten Kachel gab die Drossel des
  Kartendienstes nie frei – nach seiner ersten Abfrage wählte die Automatik nichts mehr („auto an“ in München tat
  nichts), und `/bm stadt` hätte bis zum Neustart „Der Weltbau lädt gerade Kartendaten“ gemeldet. Die Drossel gibt
  sich jetzt nach jeder Antwort selbst frei.
- Weltbau-Automatik baut, wo die Spieler sind: Eine selbst gewählte Kachel, an der noch nicht gearbeitet wird, fällt
  weg, wenn die Automatik ausgeschaltet wird oder kein Spieler mehr in ihrer Nähe ist – vorher stand z. B. die
  Grand-Canyon-Kachel noch an, als der Spieler längst in München war. Laufende Abrufe, Planungen und Bauten bleiben.
- Seelaternen und Kürbislaternen gelten nicht mehr als angehängte Blöcke: Die Planprüfung ließ frei stehende
  Seelaternen als „ohne Halt“ weg, und eine Fackel darauf galt als haltlos.
- „Bau mir eine Fabrik“ und „bau mir einen Computer“ zählen als Maschinenwunsch: Vorher entfernte die Planprüfung
  die Anlagen solcher Pläne als Verzierung.
- Unter Paper startete der Baumeister nicht: Die Log-Weiterleitung hing an sich selbst (Paper liefert für
  `getLogger()` denselben Logger), StackOverflowError beim Aktivieren. Jetzt nur unter Spigot.
- Unter Paper scheiterte jeder NPC-Spawn (NoSuchMethodError `Mannequin.setDescription(String)`). Gegen die Paper-API
  kompiliert waren es genau 5 Stellen (Beschriftung, Skin); `MannequinCompat` wählt jetzt zur Laufzeit den Spigot- oder
  Paper-Weg – ein JAR für beide Server.
- `/bm ort löschen <Ort>` räumt auch das Städte-Verzeichnis: Nach dem Zurücksetzen der Stadtwelt führte
  `/bm ort Eberdingen <Straße>` sonst weiter zum verschwundenen alten Bau statt zu „71735 Eberdingen“.
- Reisepunkt einer Stadt im Acker: `/bm ort <Stadt>` führte zur Mitte laut Nominatim. Für eine Gemeinde aus mehreren
  Dörfern ist das der Schwerpunkt der Gemeindefläche (Eberdingen auf dev01: am Reisepunkt nur Felder, die Dörfer
  1,4–1,8 km entfernt). Jetzt wandert der Reisepunkt beim Bau zur Etappe mit den meisten Gebäuden, in den Schwerpunkt
  ihrer Gebäude; der Bestwert übersteht Pause und Neustart (`reise_gebäude` in `stadt/fortsetzen.json`).
- Absturz auf dev01 beim Stadtbau (2026-10-30, Watchdog nach 60 s): Ein Rückgängig über 4,5 Mio. Blöcke lief neben
  einem neuen Stadtbau und lud jeden entladenen Chunk synchron. PlotSquared ruft in jedem ChunkLoadEvent – in jeder
  Welt – `getEntities()` auf, das auf Spigot einen noch nicht geladenen Chunk verschachtelt nachlädt; daraus wurde eine
  Rekursion bis zum StackOverflowError (895 MB Log). Jetzt:
  - der Baumeister hängt sich vor genau diesen PlotSquared-Handler und reicht das Ereignis erst weiter, wenn der Chunk
    als geladen gilt (sonst einen Tick später),
  - Rückgängig lädt Chunks wie der Stadtbau nur gedrosselt über Tickets (`stadt.neue-chunks-pro-tick`, `stadt.lag-ms`)
    und sieht alle 64 Blöcke auf die Uhr,
  - es läuft immer nur ein Rückgängig; weitere warten, und solange aufgeräumt wird, starten weder Stadtbau noch
    Weltbau noch Warteschlange (`/bm status` zeigt „räumt auf“). Weltbau-Rückgängig wartet, bis keine Kachel läuft.
- Stadtansicht großer Orte: Ab etwa Etappe 244 lehnte der Host jede Etappe mit 400 ab (Grenze 16 Mio. Zellen;
  Eberdingen, 2449 Etappen ≈ 160 Mio. Zellen bei 1 Block je Zelle), die Karte blieb ab dort leer. Die Zellgröße richtet
  sich jetzt nach der Ortsgröße (Eberdingen: 5 Blöcke je Zelle ≈ 6,4 Mio. Zellen).
- Weltbau und Städte auf Gelände: Seit der Stadtansicht (79be30b) warf die Draufsicht am Sockel unter Häusern am Hang
  (Materialnummer −1), jede Etappe blieb bei 0 Blöcken und wurde übersprungen. Das Raster kennt den Sockel jetzt, und
  ein Fehler in der Draufsicht hält den Bau nie mehr auf (Etappe ohne Karte).
- Bau ohne Spieler in der Nähe (`bauenbei` von der Konsole, Auftrag aus der Warteschlange, dessen Spieler weitergegangen
  ist) blieb still bei 0 stehen: Der NPC stand in einem entladenen Chunk. Jetzt hält der Bau Plugin-Chunk-Tickets über
  die Baustelle plus 1 Chunk Rand, vom Start bis zum Ende.
- Blöcke mehr als ~3 unter dem Boden (Brückenbogen, Fundament) waren nie in Reichweite – eine Brücke hing 15 min bei
  0/444. Jetzt setzt der NPC sie vom Standplatz aus, sobald er angekommen ist (live danach 444/444 in 58 s).
- Straßenschilder ohne Text, wenn eine Etappe im Hintergrund vorbereitet wurde; außerdem konnten die zuletzt gebauten
  Schilder leer bleiben, weil die Chunks beim Bauende sofort freigegeben wurden.
- `/bm neuladen` leerte bei jeder Ausführung Rückgängig-Stapel und Gedächtnis, meldete bei kaputter `config.yml`
  „Konfiguration neu geladen.“ und galt danach mit den Vorlagenwerten; es hielt den Server bis zu 15 s an, wenn im
  Hintergrund noch Rückgängig-Daten liefen (gemessen 15 028 ms).
- Rückgängig: Eine Windows-Junction unter `rückgängig/` hätte beim Start den Inhalt ihres Ziels gelöscht; eine
  präparierte Datei konnte den Speicher sprengen (Teile werden jetzt schon beim Entpacken begrenzt); ein unbekannter
  Blockzustand warf im Timer bei jedem Tick; ein kaputter Teil bricht mit Meldung ab statt mit einer Ausnahme im Tick;
  Einträge in Welten mit Umlaut oder Leerzeichen im Namen wurden beim nächsten Start still gelöscht (jetzt gar nicht
  erst geschrieben, mit Logzeile).
- Stadtbau mit Helfern: An Wänden, die sich zwei Gebäude teilen, hing das Material vom Takt ab (4 457 Blöcke
  Abweichung vom Bau nacheinander), Turmhelme konnten vom Kirchenschiff überschrieben werden. Jetzt gilt die
  Reihenfolge des Plans.
- Stadt: Waldbäume an Wegen hingen in der Luft; Wehre und Dämme wurden zu Wasserlinien quer über den Fluss,
  `waterway=riverbank` zum schmalen Rand; Innenstädte mit vielen Gebäudeteilen brauchten je Etappe Sekunden bis Minuten
  zum Planen; separat kartierte Gehwege verhinderten die Laternen ganzer Straßenzüge; `layer>0` ohne Querung wurde als
  Brücke gehoben; eine Fläche mit Zaun auf demselben Way verlor ihre Fläche.
- Erdwelt: Offenes Meer ohne Tiefendaten (Mittelmeer, Biskaya, Schwarzes Meer) war eine Sandebene auf Meereshöhe;
  Senken im Landesinneren liefen bis Meereshöhe voll (Badwater: 78 Blöcke Wasser); kaputte Pixelzeilen standen als
  Wasserwand über dem Kaspischen Meer; SRTM-Land am Kaspischen Meer war voller Pfützen; kaputte Höhenpixel rissen
  trockene Gruben in das IJsselmeer.
- Logik: Ausgangsnamen mit „ß“ („Größer = A & !B“) wurden abgelehnt; eine Formel ohne Namen mit Eingang Q („P und Q“)
  ebenso – der Vorgabename weicht jetzt auf Y aus. Funktionsproben `nachtlicht` (Regen) und `schmelzanlage` (zu
  knapper Puffer) scheiterten unabhängig von der Maschine.
- Anbau: „bau rechts eine Werkstatt an“ (ein „an“ am Satzende) galt nicht als Anbau; eine Tür in der gemeinsamen Wand
  galt dem Linter als „nicht in der Wand“.
- Bauformen: Ein `wall` mit verschiedenen y (Tippfehler für `walls`) wird abgelehnt, statt eine Wehrmauer durch das
  Haus zu bauen; „Kindergarten“, „Biergarten“, „Schlagbaum“ und „ein Tisch aus Birke“ gelten nicht als Bauform;
  „Holzbrücke 12 Blöcke lang“ baut 12 statt 14 Blöcke; „ein Baum aus Stein“ geht ans LLM statt still eine Eiche zu
  bauen; ein eigenes Bauteil „baum“ oder „mauer“ wird nicht mehr von der Bauform überdeckt.
- „ja“ zu einer Vorschau während eines Baus verlor die Vorschau, wenn die Warteschlange aus oder voll war.
- Höhenkacheln: höchstens 256 im Speicher (vorher unbegrenzt), verschiedene Kacheln laden parallel statt hinter
  einem Schloss, eine kaputte Antwort landet nie mehr im Zwischenspeicher, eine tote Kachel wird je Chunk einmal statt
  972-mal angefragt.
- Gelände: steile Säulen unter dem Meeresspiegel bekommen Wasser (vorher Luftschacht im Meer).
- Rechte: `baumeister.reisen` wirkt allein (Bukkit wies `/bm` vorher ohne `baumeister.bauen` ab).
- Redstone, das der Baumeister setzt, startet jetzt im richtigen Zustand: Verstärker werden nach dem Bau einmal
  angestoßen (vorher blieb z. B. im XOR mit 4 Eingängen ein Verstärker aus, die Lampe falsch).
- Stadtbau auf langsamen Servern: Die Lastregelung bremste schon ab 75 ms je Tick und halbierte jedes Mal; auf dev01 wuchs die Stadt nur noch im geladenen Bereich um den Spieler. Jetzt ab 150 ms (`stadt.lag-ms`), sanfter, mit Untergrenze. Fortschritt jede Minute statt nur alle 10 %, keine Unsinns-Schätzung mehr in den ersten Sekunden.
- Stadtbau: Ist ein Kartendienst überlastet (HTTP 429/502/503/504) oder nicht erreichbar, fragt der Baumeister sofort die nächste öffentliche Overpass-Instanz (`stadt.overpass-urls`); gespeicherte Karten aus der Zeit vor den Etappen werden wieder genutzt.

### Geändert
- Plan-Prüfer: Fackeln, Laternen und anderes Angehängte ohne Halt lässt der Baumeister selbst weg, statt dafür eine
  ganze Reparaturrunde des LLM abzuwarten (dev01: 61 s, danach trotzdem verworfen). Neue Regel „Modul überbaut“:
  Ein späterer Schritt, der durch eine Maschine füllt oder schneidet, geht zur Nachbesserung. Die Rückmeldung nennt
  zuerst jede Fehlerart einmal, statt dreimal dieselbe.
- System-Prompt: Eine allgemeine „Maschine“ heißt nützliche Anlagen statt Logikgatter; Hallenregeln (Boden eine
  Schicht unter den Modulen, Wände 2 Blöcke Abstand, nie durch ein Modul füllen).
- Stadt- und Weltbau schneller: Schon passende Blöcke zählen nur ein Viertel aufs Tempo (`/bm tempo` meint gesetzte
  Blöcke), jede Etappe wird im Hintergrund nach Chunks geordnet – auch die erste, die vorher beim Start den Server
  anhielt. 400 m Bad Kreuznach in 41 s statt 55 s (Testserver); die Zeitgrenze je Tick (`stadt.ms-pro-tick`) bleibt.
- Rechteprüfung vor dem ersten Block nach Zeit (10 ms je Tick) statt fest 300 Aktionen: Ein Hochhaus beginnt nach
  0,7 s statt 5,2 s. Ende-Log von Bau und Stadtbau mit Dauer, Blöcken/s, längstem Tick, Lücken und Prüfdauer.
- Rückgängig: Teile zu 50 000 Einträgen mit schnellerem Leser (je Teil 2–4 ms statt 21–43 ms im Tick). Der Ordner
  `rückgängig/` wird beim Start nicht mehr ganz gelöscht, nur verwaiste Einträge.
- `/bm neuladen` geht auch während eines Baus, Stadtbaus oder Weltbaus und sagt, was sofort, nach dem laufenden
  Auftrag oder erst nach einem Neustart gilt; der Baumeister wird nur neu angelegt, wenn sich seine Einstellungen
  ändern. Es läuft nur noch über die neue Prüfung, der alte Weg ist entfernt.
- Logik-Compiler: höchstens 16 Terme für die ganze Schaltung statt 5; Verstärker setzt er nur, wo die Signalstärke
  nicht reicht – Schaltungen bis 5 Terme bleiben Block für Block gleich. Funktionsproben richten Abstand, Räumfläche
  und Wartezeit nach der Maschinengröße.
- Zeichenansichten nur noch um die Bauwerke am Platz (Haus 9 × 11: 1 181 statt 2 695 Zeichen im Prompt); die drei
  Anschlussstellen je Bauwerk entfallen, beim Anbau steht genau eine festgelegte Wand im Prompt.
- Bauform-Maße außerhalb ihrer Grenzen (aber im Schema) werden mit Warnung gekappt statt eine Reparaturrunde
  auszulösen.
- Overpass-Abfrage holt zusätzlich Gleise, Begrenzungen und Parkplätze; der Name im Zwischenspeicher richtet sich nach
  dem Abfrageinhalt, jede Kachel wird einmal neu geholt. Wald steht locker statt in Schrägreihen; Kirchen am Hang
  stehen auf gemeinsamer Basis.
- Erdwelt-Chunks entstehen etwa doppelt so schnell (Testserver unter Last: 36–40 → 11–22 ms je Chunk), Logzeile
  `erde | chunks | ms_je_chunk` alle 1 024 Chunks.
- Bauplan: Feld `mode` (`new`/`update`, im Schema Pflicht) und neue op `logic` mit `expr`. Das Plugin liest Pläne
  ohne `mode` weiter als Neubau; Nachträge und `logic` über das LLM gibt es aber erst mit dem neuen Baumeister-Host,
  denn seine feste Grammatik lässt nur bekannte Felder zu.
- Grenzen für LLM-Bauten: 150 000 Blöcke und 192 je Richtung statt 6000 und 48; Koordinaten im Plan bis 200.
- `stadt.radius-max` 5000 m statt 800 m; `stadt.max-blöcke` und `stadt.rückgängig-max` entfallen (Etappen, Rückgängig auf der Platte).
- `/bm rückgängig` setzt große Bauten schneller zurück (bis 20 000 Blöcke je Tick statt fest 400) und stoppt vorher einen laufenden Stadtbau.
- Mit Nachbarbauten wird der Plan nicht mehr mittig vor den Spieler geschoben (Scan- und Plan-Koordinaten gleich).
- Zwei Dächer über demselben Grundriss lehnt der Compiler ab.
- LLM-Timeout 900 s statt 360 s (Anbau-Pläne auf der Laptop-CPU brauchten länger).

## [0.1.0] – 2026-10-28

### Neu
- Baumeister-NPC (Mannequin) nimmt Bauaufträge im Chat an („Baumeister, bau …“) und baut Block für Block: fliegt in Reichweite, schaut hin, schwingt den Arm, Platzier- und Abbaugeräusche.
- Bauplan vom lokalen LLM (llama-server, JSON-Schema als Grammatik) mit Grundformen inkl. Sattel-, Walm- und Flachdach, Zylinder, Kugel/Kuppel, Türen.
- Baureihenfolge wie von Hand: Schritt für Schritt, Schicht von unten, immer zum nächsten Block; Abriss von oben.
- Treppenecken und Zaun-/Scheiben-/Mauerverbindungen wie beim Setzen durch einen Spieler.
- Rechteprüfung im Namen des Spielers vor dem ersten Block, Sperrliste, Größenlimits.
- `/bm stop`, `/bm rückgängig`, `/bm status`, `/bm spawn`, `/bm bauenbei` (Konsole/RCON).
- `llm-host/start-llm.ps1`: llama-server plus SSH-Rückwärtstunnel zu dev01.
