# Testdaten Config-Migration

Frühere Fassungen der mitgelieferten `config.yml`, unverändert aus der Git-Historie – so sahen Configs auf Servern aus,
die damals zum ersten Mal gestartet sind (dev01: erster Deploy 2026-10-28, `stadt:` von Hand ergänzt).

| Datei | Erzeugt mit |
|---|---|
| `config-4460e9e.yml` | `git show 4460e9e:src/main/resources/config.yml` (erster Stand, ohne `stadt:`) |
| `config-c2a5ae9.yml` | `git show c2a5ae9:src/main/resources/config.yml` (Etappen, noch `stadt.overpass-url` als Einzelwert) |
