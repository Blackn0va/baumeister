# Messprobe: Vera ohne Modell

Welcher Anteil echter Sätze an Vera läuft ohne Baumeister-App (ohne LLM)? `Cover.java` schickt jeden Satz durch alle
Wege, die das Plugin selbst versteht (Bauauftrag, Geben, Redstone, Reparatur, Abenteuer, Bedienung, CompanionIntent
Befehl und Gespräch), und zählt den Rest.

Eingabe: eine Datei mit einem Satz je Zeile – aus dem Log von dev01, nicht im Repo (Spielersätze):

```bash
plink -batch root@<server> "cd /srv/minecraft/logs && (zcat *.log.gz; cat latest.log) | grep 'begleiter | frage'" | sed 's/.*text=//' > fragen.txt
```

Übersetzen und gegen den Stand laufen lassen (mit zweitem Argument die Liste der übrigen Sätze):

```bash
javac -cp "target/classes;lib/spigot-api-1.21.11-R0.2-SNAPSHOT.jar" -d out probes/offline/Cover.java
java -cp "target/classes;lib/*;out" Cover fragen.txt x
```

| Frage | Ergebnis (2026-11-07, 171 Sätze aus dem Log bis dahin) |
|---|---|
| Stand vorher (be0e2ce) | 57 ohne Modell, 114 brauchten es – **33 %** |
| Stand nachher | 126 ohne Modell, 45 brauchen es – **74 %** |
| Was übrig bleibt | Verhörer der Spracherkennung („Wergebomir ein Moe ver“), Folgesätze ohne Zusammenhang („Nochmal bitte“, „hat nicht funktioniert“), Gestaltungswünsche („mach das Haus schöner“, „bau den Pool um“) |

Gemessen wird das Erkennen, nicht das Ausführen: Ob Vera geben, reisen oder Befehle ausführen darf, entscheidet wie
mit Modell `CompanionService.perform` (Rechte, Fair-Play, Rückfrage).
