# Drittkomponenten – Baumeister (Spigot-Plugin)

Das Plugin selbst steht unter GPL-3.0-or-later ([LICENSE](LICENSE)). Diese Datei listet, was von anderen stammt.

Fremder Code im Plugin-JAR `Baumeister-<version>.jar`. Die Server-APIs unter `lib/` (Spigot, Gson, WorldEdit …) sind
nur zum Kompilieren da und liefert der Server selbst; sie stehen in [`lib/README.md`](lib/README.md). Für den
Baumeister-Host gilt [`host/THIRD-PARTY-NOTICES.md`](host/THIRD-PARTY-NOTICES.md).

| Komponente | Stand | Lizenz | Enthalten in |
|---|---|---|---|
| BuildTheEarth/terraplusplus – Dymaxion-/Airocean-Projektion nach Gray (`DymaxionProjection`, `MathUtils`) | Branch `master`, abgerufen 2026-10-28; portiert und geändert (nur Grays Variante, Ecken auf exaktes Ikosaeder, Drehung, Maßstab) | MIT | `com/blackn0va/baumeister/earth/AiroceanProjection.class` |
| retrooper/packetevents – `packetevents-api`, `packetevents-spigot`, `packetevents-netty-common` (Tab-Einträge der Stadtleben-Bewohner) | 2.14.0, Maven-Repo CodeMC, unverändert; verschoben nach `com.blackn0va.baumeister.lib.packetevents` | GPL-3.0 | `com/blackn0va/baumeister/lib/packetevents/**`, `assets/mappings/**` |
| KyoriPowered/adventure – `adventure-api`, `adventure-key`, `adventure-nbt` (braucht PacketEvents) | 4.26.1, Maven Central, unverändert; verschoben nach `com.blackn0va.baumeister.lib.kyori` | MIT | `com/blackn0va/baumeister/lib/kyori/adventure/**` |
| KyoriPowered/examination – `examination-api`, `examination-string` (braucht Adventure) | 1.3.0, Maven Central, unverändert; verschoben nach `com.blackn0va.baumeister.lib.kyori` | MIT | `com/blackn0va/baumeister/lib/kyori/examination/**` |

**Daten, die das Plugin zur Laufzeit holt (nicht im JAR):**

| Daten | Quelle | Bedingungen | Wofür |
|---|---|---|---|
| Höhenkacheln (Terrarium) | Mapzen/AWS Open Data „elevation-tiles-prod“ (u. a. SRTM, USGS 3DEP, GMTED, ETOPO) | Quellen nennen | `/bm gelaende`, Erdwelt |
| Kartendaten, Ortssuche | OpenStreetMap über Overpass und Nominatim | © OpenStreetMap-Mitwirkende, ODbL; Nutzungsregeln der Dienste | `/bm stadt`, `/bm gelaende`, `/bm reise` |

## BuildTheEarth/terraplusplus (MIT)

```
MIT License

Copyright (c) 2020 BuildTheEarth.net

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## retrooper/packetevents (GPL-3.0)

PacketEvents © retrooper und Mitwirkende, <https://github.com/retrooper/packetevents>. Lizenz: GNU General Public
License, Version 3. Der Lizenztext im Repo von PacketEvents (Tag `v2.14.0`, Datei `LICENSE`) ist byte-gleich mit
[LICENSE](LICENSE) dieses Repos (SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`, verglichen
2026-11-22) und liegt deshalb einmal im Plugin-JAR als `META-INF/LICENSE`. Den Quelltext der eingepackten Fassung gibt
es unter <https://github.com/retrooper/packetevents/tree/v2.14.0>; die Jars stammen unverändert aus
<https://repo.codemc.io/repository/maven-releases/> (SHA-256 in [`lib/README.md`](lib/README.md)), geändert sind nur die
Paketnamen (maven-shade-plugin, `pom.xml`).

## KyoriPowered/adventure (MIT)

```
MIT License

Copyright (c) 2017-2025 KyoriPowered

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## KyoriPowered/examination (MIT)

```
MIT License

Copyright (c) 2018-2019 KyoriPowered

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
