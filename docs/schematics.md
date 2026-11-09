# Schematics bauen

Stand: 2026-11-08. Der Baumeister baut jede Bauwerk-Datei, die im Ordner `plugins/Baumeister/schematics/` liegt –
ohne WorldEdit.

## Formate

| Endung | Herkunft | Gelesen |
|---|---|---|
| `.schem` | WorldEdit (Sponge v1–v3) | ja |
| `.litematic` | Litematica | ja |
| `.nbt` | Strukturblock des Spiels | ja |
| `.schematic` | MCEdit, vor 1.13 (Zahlen-IDs) | nein – mit WorldEdit in `.schem` umspeichern |

Blockdaten (Truhen-Inhalte, Schildtexte, Befehle) werden nie mitgelesen. Jeder Block geht beim Setzen durch die
Rechte-Stufen wie bei jedem Bau: Lava, TNT oder Befehlsblöcke in einer Datei setzt der Baumeister nur für Admins.

## Bauen

- Im Chat: `Baumeister, bau mir einen Taktgeber` (Variante 1) oder `… Taktgeber Variante 3`.
- Als Admin direkt: `/bm maschine taktgeber_3` (vor dir) oder mit Welt, Ort und Richtung.
- Stöbern: `/bm schematics` (alle Familien), `/bm schematics farm` (Suche).
- Freie Aufträge, die einen Familiennamen enthalten („ein Haus mit Taktgeber im Keller“), gehen ans LLM; es bekommt
  die passenden Bauteile als Hinweis.

Neue Dateien gelten nach spätestens 30 Sekunden, ohne Neustart.

## Namen

Ohne weitere Angaben heißt ein Bauteil wie seine Datei (klein, Umlaute ausgeschrieben, `_` statt Sonderzeichen,
höchstens 32 Zeichen): `Meine Burg (v2).schem` → `meine_burg_v2`. Mit einer `index.tsv` im selben Ordner gelten deren
Angaben, eine Zeile je Datei, Spalten durch Tabulator getrennt:

```
# datei	name	anzeige	beschreibung	quelle
taktgeber_3.nbt	taktgeber_3	Taktgeber, Variante 3	Taktgeber aus der Redstone-Welt	Technik Wiki
```

„Anzeige“ mit `, Variante N` am Ende bildet Familien – darüber laufen Chat-Aufträge und `/bm schematics`.

## Woher

- **Planet Minecraft:** Einzelne Schematics, die dir gefallen, von Hand laden und in den Ordner legen. Automatisch
  abgreifen verbieten die Nutzungsbedingungen (Terms of Use, Punkt 5 „systematically extract data“ und Punkt 7
  „Redistribute any content“); jedes Werk gehört seinem Ersteller.
- **Technik Wiki (de.minecraft.wiki):** Die drei Download-Welten (Redstone, Mechanik, Schienen; GitHub
  `MinecraftTechnikWiki/*-Welt`, Release 1.21a) schneidet `TechnikWikiImport` in einzelne Bauten (unten). Wiki-Inhalte
  stehen unter CC BY-NC-SA 3.0, die Welt-Repos haben keine eigene Lizenz: Die Dateien bleiben auf dem eigenen Server –
  nicht im Repo, nicht im Paket für Dritte.

## Technik-Wiki-Welten ausschneiden

```
gh release download 1.21a -R MinecraftTechnikWiki/Redstone-Welt -p "Redstone-Welt-1.21a.zip"
java -Xmx5g -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.structure.TechnikWikiImport <weltordner> Redstone-Welt <ausgabe>
```

Gemessen 2026-11-08: Die Welten haben Boden bis y = 25, je Technik ein Feld im 32er-Raster mit einem Schilderhaus
(Name auf y = 28), die Varianten als Inseln darum. Geschnitten wird je Insel samt dem Boden darunter.

| Welt | Inseln | Dateien | Felder | weggelassen |
|---|---:|---:|---:|---|
| Redstone | 912 | 382 | 72 | 218 mit Befehls-/Strukturblöcken, 142 ohne Namensschild, 114 Schilderhäuser, 54 unter 8 Blöcken, 2 zu groß |
| Mechanik | 743 | 331 | 96 | 82 mit Befehlsblöcken, 166 ohne Namensschild, 132 Schilderhäuser, 24 klein, 8 zu groß; 537 517 Barrieren entfernt |
| Schienen | 212 | 91 | 28 | 18 mit Befehlsblöcken, 42 ohne Namensschild, 39 Schilderhäuser, 22 zu groß (verbundene Bahnnetze) |

Bauten mit Befehlsblöcken fehlen absichtlich: Ohne ihre Befehle tun sie nichts, und Befehle aus fremden Dateien setzt
der Baumeister nie. Die Bauten sind **ungeprüft**: baubar (siehe Gedächtnis, Probe), ob jede Schaltung so funktioniert
wie in der Wiki-Welt, ist nicht gemessen.
