# Messprobe Rechte gegen WorldGuard (T-038)

Frage: Verbietet WorldGuard Feldarbeit und Bewohner-Bauten wirklich, wenn der Admin, in dessen Namen die Bewohner
arbeiten, **kein** Op ist? Bis T-037 war das nur mit einem Op-Bot probiert – Op hat jedes Recht, auch
`worldguard.region.bypass.<welt>`, also winkt WorldGuard alles durch und die Probe beweist nichts.

`RechteProbe.java` gibt einem Nicht-Op-Spieler einzelne Rechte über ein `PermissionAttachment` (kein Rechte-Plugin,
kein Download) und fragt WorldGuard zum Vergleich auch ohne Baumeister (`frag`). Nicht im Plugin-Jar.

```bash
javac --release 21 -encoding UTF-8 -cp lib/spigot-api-1.21.11-R0.2-SNAPSHOT.jar -d out probes/rechte/RechteProbe.java
cp probes/rechte/plugin.yml out/ && jar cf RechteProbe.jar -C out .
```

Ablauf (RCON): `deop T035` · `rechteprobe gib T035 baumeister.admin` · `rechteprobe zeig T035` (op=false,
baumeister.admin=true, bypass=false) · `rechteprobe frag T035 <welt> x y z` in und außerhalb der Region ·
Felder/Baufelder in und außerhalb einer Region mit `flags: {build: deny}` setzen, Bewohner arbeiten lassen.
`rechteprobe sag T035 Lotte, komm mit` schickt eine Chatzeile im Namen des Bots (Zuruf ohne Chat-Pakete),
`rechteprobe typen <welt> x0 z0 x1 z1 y` druckt eine Blockschicht (so fiel das Wegkreuz im Baufeld auf).

| Frage | Ergebnis (2026-11-24) | Verwendet in |
|---|---|---|
| WorldGuard ohne Baumeister, Nicht-Op mit `baumeister.admin` | Region: Abbau/Setzen verboten; außen erlaubt (Spigot 1.21.11 + WG 7.0.15, Paper 26.2-135 + WG 7.0.19) | Kontrolle |
| Dasselbe als Op | Region erlaubt (bypass=true) – darum war T-037 unbelegt | Kontrolle |
| Bewohner-Bau im Baufeld in der Region (Spigot) | `bau \| verweigert` beim ersten Block, gesetzt=0, Bau abgebrochen | BuildJob/RegionCheck |
| Bewohner-Bau außerhalb (Spigot) | 2 Bauten fertig, Feldarbeit 16 Änderungen | BuildService.startTown |
| Feldarbeit Weizen in der Region (Spigot) | 24× `feld_verboten`, alle 24 Weizen weiter reif (age=7) | TownWork.hands |
| Feldarbeit Weizen außerhalb (Spigot, Paper) | je 24 geerntet und nachgesät | TownWork.hands |
| Bewohner-Bau in der Region (Paper 26.2) | `bau \| verweigert`, gesetzt=0; Gärtnerin 17× `feld_verboten`, Feld unverändert | BuildJob, TownWork |
| Tür in der Region (Paper 26.2) | 2× verweigert, freie Tür auf/zu | town.use |

Falle: WorldGuard merkt sich „bypass“ einige Sekunden je Spieler und Welt. Direkt nach `deop` fragte `frag` noch
„erlaubt“; vier Sekunden später „verboten“. Proben nach einem Rechtewechsel also kurz warten lassen.
