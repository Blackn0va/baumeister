# lib – Server-APIs zum Kompilieren

Nur zum Kompilieren. Nichts davon wird ins Plugin gepackt, der Server bringt alles mit. Ausnahme ist PacketEvents (unten):
Es liegt nicht hier, sondern kommt über Maven und wird ins Plugin-Jar geschattet.

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

## Ins Plugin-Jar geschattet (seit 2026-11-22, T-033)

PacketEvents für die Tab-Einträge der Stadtleben-Bewohner. Maven holt die Jars einmal (PacketEvents aus dem offiziellen
Repo von CodeMC `https://repo.codemc.io/repository/maven-releases/`, Adventure/Examination aus Maven Central) und prüft
dabei SHA-1 gegen das Repo (`checksumPolicy fail`); danach baut `mvn -o` ohne Netz. SHA-256 hält
`PacketEventsPinTest` fest – stimmt eine Datei im lokalen Maven-Lager nicht, wird der Bau rot. Geschattet und
verschoben werden nur diese Gruppen (`pom.xml`, maven-shade-plugin 3.6.0); netty bringt der Server mit.

| Datei | SHA-256 |
|---|---|
| packetevents-api-2.14.0.jar | cf1afcfd1ccffba5108b3227918f1d8d4d0f10fc6b654901cff42d0070e427ca |
| packetevents-spigot-2.14.0.jar | 13e771d25e75aab7c7c9d3f383464bd956a7bee49f3d948cc74a1870e8440a0f |
| packetevents-netty-common-2.14.0.jar | ca76c618085561a3cb578c743ef88c0be0c559d3e1e7a7d9934cafb3da2fa7d8 |
| adventure-api-4.26.1.jar | 551e536b9ea868f30e72c7900a309b35124ee7d4889fa3b3aed0910299751a26 |
| adventure-key-4.26.1.jar | eec172d63db77b40eb7abeeb25f65eedea89bd30264d057b68b12fecb731be5e |
| adventure-nbt-4.26.1.jar | f26ef6bff5fcdd817902b4dfd353561d98cb131b4ecb67341ff00ef2ed1336f8 |
| examination-api-1.3.0.jar | c9237ffecb05428f6eff86216246ac70ce0b47b04c08ea7ca35020fde57f8492 |
| examination-string-1.3.0.jar | 7d01fc25a4bb3af0e1662685455f4541fbf4626216ea5846e455c1491e156b8c |
