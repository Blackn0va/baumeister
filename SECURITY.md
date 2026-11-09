# Sicherheit

## Lücke melden

Bitte **nicht** als öffentliches Issue. Stattdessen auf GitHub unter **Security → Report a vulnerability** (privat,
nur der Maintainer sieht es). Hilfreich sind:

- welcher Teil (Plugin, Relay, Host, Client-Mod) und welche Fassung,
- was ein Angreifer braucht (Spieler auf dem Server? Netzwerkzugang? lokaler Zugriff?),
- Schritte zum Nachstellen.

Du bekommst eine Antwort, sobald die Meldung gelesen ist. Behobene Lücken stehen im `CHANGELOG.md`, auf Wunsch mit
Namensnennung.

## Was als Lücke zählt

- Ein Spieler bringt den Baumeister oder Vera dazu, etwas zu bauen, abzureißen oder auszuführen, das er selbst nicht
  dürfte (Schutzgebiete, Befehlsblöcke, gefährliche Blöcke, fremde Plots).
- Umgehung von Pinning, Client-Zertifikat oder Grenzen am Relay.
- Prompt-Injection über Chat, Schilder, Bücher oder Recherche, die zu Aktionen führt.
- Geheimnisse (Tokens, Schlüssel) in Logs oder Dateien, wo sie nicht hingehören.

Nicht gemeint: Lücken in Minecraft, Paper, Spigot oder anderen Plugins selbst – die bitte dort melden.
