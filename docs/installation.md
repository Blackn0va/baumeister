# Baumeister auf deinem Server installieren

Stand: 2026-11-08. Für Betreiber eines eigenen Minecraft-Servers. Das Plugin spricht Deutsch – im Chat, in den
Büchern und im Log.

Der Baumeister kommt in drei Stufen. Jede läuft für sich; die nächste ist nur nötig, wenn du sie willst.

| Stufe | Was du bekommst | Was du brauchst |
|---|---|---|
| 1 – nur das Plugin | Farmen, Maschinen und Redstone-Schaltungen auf Zuruf, Städte und Gelände 1:1 aus OpenStreetMap, Begleiterin Vera (ohne Sprachmodell), Rucksack, Warps, Tagesaufgaben, Minikarte, Hilfebuch | Paper oder Spigot, 5 Minuten |
| 2 – KI auf demselben Rechner | dazu Bauaufträge in freien Worten („bau mir eine Kapelle aus Stein“), Vera mit Sprachmodell | Windows-PC mit Grafikkarte, auf dem auch der Minecraft-Server läuft |
| 3 – KI auf einem anderen PC | dasselbe, der Minecraft-Server läuft woanders (Linux-Server, gemieteter Rechner) | Linux mit systemd auf dem Server, Router-Freigabe eines Ports |

## Stufe 1 – das Plugin

**Voraussetzungen** (geprüft):

| Server | Java | Stand |
|---|---|---|
| Paper 26.2 | 25 | läuft auf dem Testserver des Projekts |
| Spigot 1.21.11 | 21 | Testserver, alle Maschinen-Proben |

Ältere Fassungen als 1.21.9 gehen nicht (der Baumeister ist ein Mannequin, das gibt es erst ab 1.21.9). Andere
Fassungen dazwischen sind nicht geprüft.

1. `Baumeister-<fassung>.jar` nach `plugins/` legen.
2. Server starten. Im Log steht am Ende `[Baumeister] start | bereit` und kurz danach `vorlagen | bereit`.
3. Im Spiel als Admin (OP): `/bm maschine weizenfarm` – der Baumeister baut eine Weizenfarm vor dir.

Beim ersten Start legt das Plugin `plugins/Baumeister/config.yml` an. Jede Zeile darin hat einen Kommentar; die
wichtigsten für einen neuen Server:

| Schlüssel | Vorgabe | Wozu |
|---|---|---|
| `aufgaben.welten`, `begleiter.abenteuer.welten` | `[hauptwelt]` | wo Tagesaufgaben zählen und Veras Abenteuer läuft. `hauptwelt` = die Welt aus `level-name` samt Nether und End, egal wie sie heißt. Feste Namen gehen auch |
| `spielwelt-name` | `Hauptwelt` | so heißt diese Welt in Veras Sätzen („nur in der Hauptwelt“) |
| `mods.link` | leer | Download-Link für das Modpaket deiner Spieler (siehe unten) |
| `llm.recherche-kontakt` | Projektseite | Wikipedia verlangt eine Kontaktangabe; trag deine eigene ein (E-Mail oder URL) |
| `begleiter.name` | `Vera` | Name der Begleiterin |

Fehlende Schlüssel trägt das Plugin bei jedem Update selbst nach (mit Sicherung unter `plugins/Baumeister/sicherung/`),
vorhandene Werte bleiben. Nach Änderungen: `/bm neuladen`.

**Weitere Plugins** – alle freiwillig, ohne sie läuft der Rest:

| Plugin | Wozu |
|---|---|
| WorldEdit | eigene Bauteile aus einer Auswahl (`/bm bauteil`) |
| Simple Voice Chat | mit Vera sprechen (braucht Stufe 2 oder 3) |
| CoreProtect | Vera repariert Explosionsschäden und sagt, wer an einer Kiste war |
| LuckPerms | Rechte für Gruppen statt OP (empfohlen) |
| PlotSquared | Knöpfe „Grundstück holen“ im Hilfebuch |

### Rechte

Ohne Rechte-Plugin gilt: Was „op“ ist, haben OPs, was „true“ ist, alle.

| Recht | Vorgabe | Erlaubt |
|---|---|---|
| `baumeister.bauen` | op | Bauaufträge, Vorschau, Rückgängig |
| `baumeister.bauen.inhalte` | op | Blöcke mit Inhalt (Truhen mit Gegenständen …). Im Kreativmodus auch ohne dieses Recht |
| `baumeister.admin.gefaehrlich` <!-- umlaut-ok: Rechtename --> | op | Lava, TNT, Spawner, Grundgestein … setzen und abreißen |
| `baumeister.admin.befehlsblock` | op | Befehlsblöcke – Befehle aus einem Bauplan erst nach Klick |
| `baumeister.admin` | op | alles oben und die Admin-Befehle (`/bm spawn`, `neuladen`, `stadt` …) |
| `baumeister.reisen` | op | `/bm ort`, Reisen in der Erdwelt |
| `baumeister.begleiter` | alle | Vera rufen |
| `baumeister.rucksack`, `baumeister.warp`, `baumeister.aufgaben` | alle | Rucksack, Warps, Tagesaufgaben |

Mit LuckPerms bekommen Admins alles wie ein OP:

```
lp group admin permission set baumeister.admin true
```

Spieler dürfen bauen:

```
lp group default permission set baumeister.bauen true
```

Was Vera für wen tut (geben, suchen, Befehle …), stellt ein Admin im Hilfebuch auf der Seite „Rechte“ per Klick ein.

### Schematics

Jede `.schem`, `.litematic` oder `.nbt` in `plugins/Baumeister/schematics/` (auch in Unterordnern) baut der Baumeister:
„Baumeister, bau mir einen Taktgeber“, `/bm schematics` zum Stöbern, `/bm maschine <name>` für Admins. Einzelheiten,
Namen und Quellen: [`docs/schematics.md`](schematics.md).

### Mods für deine Spieler

Ein Minecraft-Server kann Mods nicht selbst an Spieler schicken. `/mods` im Spiel sagt jedem, welche
Minecraft-Fassung und welche Mods er braucht: Fabric mit Fabric API, Simple Voice Chat, um mit Vera zu sprechen,
und was du sonst gern dabeihast (Sodium, Iris …). Der Baumeister-Client ist freiwillig – er baut mit der eigenen
Spielfigur, auch ohne dieses Plugin. Das Paket baust du auf deinem Spiele-PC:

```
pwsh -File tools\modpaket\Modpaket-Erstellen.ps1
```

Es macht aus deinem Mods-Ordner ein `.mrpack` (Prism Launcher, Modrinth App) und ein ZIP. Zwei Wege zu den Spielern:

- **Vom Server selbst** (empfohlen): beide Dateien nach `plugins/Baumeister/modpaket/`, dann in der config.yml
  `mods.download.an: true`, `adresse` (dein DNS-Name) und den Port (Vorgabe 25581, im Router freigeben). `/mods` gibt
  jedem Spieler einen eigenen Link, 24 Stunden gültig; ohne Link kommt niemand an die Dateien. Für HTTPS ein
  Zertifikat von Let's Encrypt (certbot, dafür Port 80 freigeben) und unter `zertifikat`/`schlüssel` die PEM-Dateien
  eintragen – das Plugin schaltet ohne Neustart um.
- **Irgendwo ablegen**, wo deine Spieler laden können, und den Link in `mods.link` eintragen.

## Stufe 2 – KI auf demselben Rechner

Gilt, wenn der Minecraft-Server auf dem Windows-PC mit der Grafikkarte läuft.

1. `StreamWizard-Baumeister-Host-Setup-<fassung>.exe` ausführen (Windows 10/11 x64; Grafikkarte mit Vulkan,
   12 GB Grafikspeicher empfohlen). Das Setup ist nicht signiert – Windows SmartScreen fragt einmal nach.
2. BaumeisterHost starten, in den Einstellungen unter „Der Server läuft“ **auf diesem PC (kein Tunnel nötig)**
   wählen, **KI einschalten**. Beim
   ersten Mal lädt die App das Modell (8,4 GB, Prüfsumme geprüft).
3. Im Spiel: `/bm status` zeigt „LLM: erreichbar“.

Die App lauscht auf `127.0.0.1:18080` – genau dort sucht das Plugin (`llm.url`). Die Config bleibt, wie sie ist.

### Stufe 2 mit Ollama statt der Baumeister-App

Wer schon [Ollama](https://ollama.com) nutzt (Windows, Linux, macOS), braucht die App nicht. In der config.yml:

```
llm:
  url: http://127.0.0.1:11434/v1/chat/completions
  modell: qwen2.5:7b
```

und vorher `ollama pull qwen2.5:7b`. Gemessen 2026-11-08 (Ollama 0.40.15): Bauplan und Vera-Antwort kommen als gültiges
JSON nach dem Schema, zwei Aufträge (Haus aus Fichtenholz, runder Turm) bestanden den strengen Plan-Prüfer. Das war
mit dem kleinen `qwen2.5:1.5b` auf der CPU (16–25 s je Auftrag) – für schöne Bauten das 7B-Modell oder größer und eine
Grafikkarte nehmen. Ohne die App fehlen nur Anzeigen (Stadtansicht, Verlauf) und Veras Sprache; die Recherche-Freigabe
entscheidet dann allein `llm.recherche`.

## Stufe 3 – KI auf einem anderen PC

Der Minecraft-Server läuft auf Linux, die KI auf deinem Windows-PC. Dazwischen steht das **Relay**: ein kleiner
Dienst auf dem Server, der das Plugin auf `127.0.0.1:18080` bedient und die App über TLS annimmt. Beide Seiten prüfen
sich gegenseitig über Fingerabdrücke (Pins); ohne eingetragenen Pin kommt niemand durch.

```
Plugin ──▶ 127.0.0.1:18080 [Relay] ◀══ TLS, beide Seiten gepinnt ══ Baumeister-App (dein PC) über Router-Port 25580
```

**Auf dem Server** (als root, im ausgepackten Ordner `relay/` des Pakets; Java 21 oder neuer mit keytool):

```
sudo bash einrichten.sh
```

Das Skript legt Benutzer, Dateien, Schlüssel und den Dienst an und zeigt am Ende den **Server-Pin**
(`sha256:…`). Mit `--probelauf` zeigt es nur, was es tun würde; mit `--java /pfad/zu/bin/java` nimmt es ein
bestimmtes Java.

**Im Router:** TCP 25580 auf den Server freigeben. Sonst nichts – Port 22 und „Exposed Host“ gehören nicht dazu.

**In der App:** Einstellungen → „Der Server läuft“ **im Internet über eine Adresse (HTTPS)** → Adresse deines Servers
(DNS-Name oder IP) und den Server-Pin eintragen → „Schlüssel erzeugen“ → eigenen Fingerabdruck mit „Kopieren“ holen.

**Wieder auf dem Server:** den Fingerabdruck der App eintragen. Er gilt nach 2 Sekunden, ohne Neustart:

```
echo 'erlaubt.mein-pc=sha256:…' >> /etc/baumeister-relay/relay.properties
```

Entziehen = Zeile löschen; die laufende Verbindung endet binnen 2 s. Mehr (Schlüsselwechsel, Rückweg, Messwerte):
[`relay/deploy/README.md`](../relay/deploy/README.md), Hintergrund: [`docs/fernverbindung.md`](fernverbindung.md), Protokoll: [`docs/kanal-v1.md`](kanal-v1.md).

**Prüfen:** Im Spiel `/bm status` → „LLM: erreichbar“. Auf dem Server `cat /run/baumeister-relay/status` → `app=mein-pc`.
Einmal über ein anderes Netz (Handy-Hotspot) probieren: Im eigenen Netz beweist ein Test nur, dass der Router seine
eigene Adresse auflöst.

## Fehlersuche

| Im Chat / Log | Ursache |
|---|---|
| „Der PC mit der KI ist gerade nicht verbunden“ | App aus, nicht verbunden, oder ihr Pin fehlt in `relay.properties` |
| `abenteuer \| welt_fehlt \| welt=…` | ein fester Weltname in `begleiter.abenteuer.welten`, den es nicht gibt (oder `hauptwelt` eintragen) |
| `bauteile \| nur_eingebaute \| grund=WorldEdit fehlt` | kein Fehler – ohne WorldEdit nur die eingebauten Bauteile |
| `erde \| welt_fehlt` | kein Fehler – die Erdwelt legt `/bm gelände einrichten` an |
| `/mods` zeigt keinen Knopf | `mods.link` ist leer |

## Update und Entfernen

- **Update:** Neue Jar nach `plugins/update/` (bzw. alte ersetzen), Server neu starten. Config, Bauteile, Rucksäcke,
  Warps und Aufgaben bleiben in `plugins/Baumeister/`.
- **Entfernen:** Jar löschen. `plugins/Baumeister/` enthält alle Daten (Rucksäcke!) – erst sichern, dann löschen.
  Relay: `systemctl disable --now baumeister-relay`, dann `/etc/baumeister-relay`, `/opt/baumeister-relay` und
  `/etc/systemd/system/baumeister-relay.service` löschen, `userdel baumeister-relay`, Router-Freigabe entfernen.
