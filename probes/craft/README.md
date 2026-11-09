# Messprobe Craften

Liest `CraftRecipes` die Werkbank-Rezepte des laufenden Servers so, wie `CompanionCraft` sie braucht? Die Probe
übersetzt genau diese beiden Klassen, plant für zehn Gegenstände gegen ein festes Inventar und schreibt
`plugins/CraftProbe/rezepte.txt`. Ohne Baumeister-Jar auf dem Server laufen lassen (gleiche Klassen in zwei Plugins).

| Frage | Ergebnis (2026-11-07, Paper 26.2-135) | Verwendet in |
|---|---|---|
| Holzarten als Auswahl? | Stöcke: erstes Feld 12 Plankenarten, 1 Eiche + 1 Fichte → 4 Stöcke | `CompanionCraft.plan` |
| Kohle oder Holzkohle? | Fackel: Feld [charcoal, coal], 2 Kohle + 2 Stöcke → 8 Fackeln | `CompanionCraft.plan` |
| Große Rezepte? | Kuchen 9 Felder (3 Milcheimer, 2 Zucker, Ei, 3 Weizen); Truhe 8, Leiter 7 Stöcke → 3 | `CraftRecipes.slots` |
| Alle zehn lesbar? | Stock, Fackel, Werkbank, Eisenspitzhacke, Brot, Truhe, Leiter, Eimer, Kuchen, Boot | `CompanionService.craft` |

Rohdaten: `rezepte-2026-11-07.txt`. Nicht geprüft: das Craften mit einem echten Spieler (Inventar, Reste, Antwort).

```bash
javac --release 21 -encoding UTF-8 -cp lib/spigot-api-1.21.11-R0.2-SNAPSHOT.jar -d out probes/craft/CraftProbe.java src/main/java/com/blackn0va/baumeister/CraftRecipes.java src/main/java/com/blackn0va/baumeister/companion/CompanionCraft.java
```
