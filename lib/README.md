# lib – Server-APIs zum Kompilieren

Nur zum Kompilieren. Nichts davon wird ins Plugin gepackt, der Server bringt alles mit.

**Die JARs stehen nicht im Repo** (seit 2026-11-09, `.gitignore`): Es sind fremde Binärdateien unter fremden
Lizenzen (GPL-3.0, Apache-2.0, MIT, BSD, Artistic-2.0). Wer baut, legt sie selbst hierher; `pom.xml` erwartet genau
diese Dateinamen (`systemPath`).

## Woher

| Datei | Herkunft |
|---|---|
| `spigot-api-1.21.11-R0.2-SNAPSHOT.jar`, `gson`, `guava`, `bungeecord-chat`, `joml`, `snakeyaml`, `commons-lang3` | Ordner `bundler/libraries/` eines Spigot-1.21.11-Servers (mit BuildTools gebaut und einmal gestartet) |
| `worldedit-bukkit-7.4.1.jar` | WorldEdit 7.4.1 für Bukkit (enginehub.org), wie es im Ordner `plugins/` liegt |
| `CoreProtect-CE-24.1.jar` | CoreProtect Community Edition 24.1, unverändert |
| `voicechat-api-2.6.0.jar` | Maven-Repo `maven.maxhenkel.de`, `de.maxhenkel.voicechat:voicechat-api:2.6.0` |

## Hashes der Fassungen, gegen die hier gebaut und getestet wird

Abweichende Hashes heißen nicht „kaputt“, sondern „anderer Stand“ – dann laufen die Tests gegen etwas anderes als
den belegten Bau.

| Datei | SHA-256 |
|---|---|
| spigot-api-1.21.11-R0.2-SNAPSHOT.jar | 0ae533c5a93a5ddb5478b992a478e594d9068fd3fdaaf640a771d754007fb3ac |
| gson-2.13.2.jar | dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0 |
| guava-33.5.0-jre.jar | 1e301f0c52ac248b0b14fdc3d12283c77252d4d6f48521d572e7d8c4c2cc4ac7 |
| bungeecord-chat-1.21-R0.4.jar | 3bade7edb09149aff360836012b5e8b469416bed8edd4f63bda2cdcce3d450ca |
| joml-1.10.8.jar | bf19510145178df82cd3bd37edd514c13f411531ec5545299fd3abcbc98fe7c2 |
| snakeyaml-2.2.jar | 1467931448a0817696ae2805b7b8b20bfb082652bf9c4efaed528930dc49389b |
| worldedit-bukkit-7.4.1.jar | 8e676deb70f33e12881a02ab5b295bcd706a2a689dcd79fc1f0b405cb2755dfd |
| commons-lang3-3.19.0.jar | 32733ab4bc90b45b63eb72677d886961003fd4ed113e07b1028f9877cb2ac735 |
| CoreProtect-CE-24.1.jar | a2acef7c06ef201355cef07d591a6055dfed358ad768faaaaaf4e276865ecc97 |
| voicechat-api-2.6.0.jar (SHA-1 a5c57adaa993e15e5f190a4bcb307347a45a10ad wie im Maven-Repo) | 4e7f7a9c8e318a7ce6aea12bac030975d14db7a298fdd1511d61d66c6e634c43 |

Prüfen:

```bash
sha256sum lib/*.jar
```
