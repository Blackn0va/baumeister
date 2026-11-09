#!/bin/bash
# Richtet das Baumeister-Relay auf einem Linux-Server ein – einmal, als root (docs/installation.md, Stufe 3).
#
# WOZU (2026-11-08):
# Die Anleitung von Hand (relay/deploy/README.md) sind zehn Befehle mit festen Pfaden (/opt/temurin-25-jre).
# Dieses Skript macht dasselbe mit dem Java, das auf dem Server liegt, und bricht ab, statt etwas zu überschreiben.
#
# Aufruf (im ausgepackten Paket, neben baumeister-relay.jar, baumeister-relay.service, relay.properties.beispiel und
# schluessel-erzeugen.sh):  # umlaut-ok: Dateiname
#   sudo bash einrichten.sh                         # Java aus PATH (mindestens 21)
#   sudo bash einrichten.sh --java /opt/jdk-21/bin/java
#   sudo bash einrichten.sh --probelauf             # nur zeigen, was passieren würde
#
# Voraussetzungen: systemd ≥ 249, Java ≥ 21 mit keytool, Port 18080 auf 127.0.0.1 frei (das Plugin spricht dorthin),
# Port 25580 erreichbar (Router-Freigabe TCP 25580 auf diesen Rechner).
# Ergebnis: Dienst baumeister-relay läuft, Server-Pin auf dem Bildschirm (kommt in die Baumeister-App).
# Exitcodes: 0 fertig · 1 Fehler, nichts verändert oder bis zum genannten Schritt · 2 schon eingerichtet.
#
# FALLE: Läuft schon ein SSH-Tunnel auf 18080, startet das Relay nicht (es versucht es alle 5 s). Erst den Tunnel
# beenden. FALLE: ProtectProc/SocketBindAllow fehlen in systemd < 247/249 – dann startet die Unit nicht; das Skript
# prüft die Fassung vorher.
set -euo pipefail
umask 022

HERE=$(cd "$(dirname "$0")" && pwd)
JAVA=""
DRY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --java) JAVA=${2:-}; shift 2 ;;
        --probelauf) DRY=1; shift ;;
        -h|--hilfe) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "einrichten.sh | FEHLER | unbekannter Schalter: $1 (--java <pfad>, --probelauf)" >&2; exit 1 ;;
    esac
done

step() { echo "==> $*"; }
fail() { echo "einrichten.sh | FEHLER | $*" >&2; exit 1; }
run() {
    if [ "$DRY" = 1 ]; then
        echo "    (probelauf) $*"
    else
        "$@"
    fi
}

[ "$(id -u)" -eq 0 ] || fail "nur als root (Schlüssel gehören root, die Unit nach /etc/systemd/system)"
for f in baumeister-relay.jar baumeister-relay.service relay.properties.beispiel schluessel-erzeugen.sh; do  # umlaut-ok
    [ -f "$HERE/$f" ] || fail "$f fehlt neben dem Skript ($HERE)"
done

step "systemd"
SYSTEMD=$(systemctl --version | head -n 1 | awk '{print $2}')
[ "${SYSTEMD:-0}" -ge 249 ] 2>/dev/null || fail "systemd $SYSTEMD – gebraucht wird mindestens 249 (SocketBindAllow)"
echo "    systemd $SYSTEMD"

step "Java"
if [ -z "$JAVA" ]; then
    JAVA=$(command -v java || true)
    [ -n "$JAVA" ] || fail "kein java im PATH – mit --java /pfad/zu/bin/java angeben"
fi
JAVA=$(readlink -f "$JAVA")
[ -x "$JAVA" ] || fail "$JAVA ist nicht ausführbar"
# „openjdk version "21.0.5" …“ bzw. „java version "25.0.1" …“ → Hauptnummer
MAJOR=$("$JAVA" -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')
[ "${MAJOR:-0}" -ge 21 ] 2>/dev/null || fail "$JAVA ist Java ${MAJOR:-?} – gebraucht wird mindestens 21"
KEYTOOL="$(dirname "$JAVA")/keytool"
[ -x "$KEYTOOL" ] || fail "kein keytool neben $JAVA (ein JRE ohne keytool reicht nicht)"
echo "    $JAVA (Java $MAJOR)"

step "Schon eingerichtet?"
if [ -e /etc/systemd/system/baumeister-relay.service ] || [ -e /etc/baumeister-relay/relay.p12 ]; then
    echo "    Ja – /etc/systemd/system/baumeister-relay.service bzw. /etc/baumeister-relay/relay.p12 gibt es schon."
    echo "    Nichts verändert. Pin anzeigen:"
    echo "    env CREDENTIALS_DIRECTORY=/etc/baumeister-relay $JAVA -jar /opt/baumeister-relay/baumeister-relay.jar --fingerabdruck"
    exit 2
fi
echo "    nein"

step "Port 18080 frei?"
if ss -Hltn 'sport = :18080' | grep -q .; then
    ss -Hltnp 'sport = :18080' >&2 || true
    fail "127.0.0.1:18080 ist belegt (SSH-Tunnel der App? Den erst beenden)"
fi
echo "    frei"

step "Benutzer baumeister-relay"
if id baumeister-relay >/dev/null 2>&1; then
    echo "    gibt es schon"
else
    run useradd --system --user-group --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin \
        baumeister-relay
fi

step "Dateien"
run install -d -m 0755 -o root -g root /opt/baumeister-relay /etc/baumeister-relay
run install -m 0644 -o root -g root "$HERE/baumeister-relay.jar" /opt/baumeister-relay/
run install -m 0644 -o root -g root "$HERE/relay.properties.beispiel" /etc/baumeister-relay/relay.properties
# Die Unit nennt /opt/temurin-25-jre; hier das gefundene. Sonst bleibt sie Zeichen für Zeichen gleich (Härtung, Grenzen).
UNIT=$(sed "s#/opt/temurin-25-jre/bin/java#$JAVA#" "$HERE/baumeister-relay.service")
grep -q "^ExecStart=$JAVA " <<<"$UNIT" || fail "ExecStart in baumeister-relay.service nicht erkannt"
if [ "$DRY" = 1 ]; then
    echo "    (probelauf) Unit nach /etc/systemd/system/baumeister-relay.service mit ExecStart=$JAVA …"
else
    printf '%s\n' "$UNIT" > /etc/systemd/system/baumeister-relay.service
    chmod 0644 /etc/systemd/system/baumeister-relay.service
fi

step "Schlüssel"
if [ "$DRY" = 1 ]; then
    echo "    (probelauf) KEYTOOL=$KEYTOOL sh schluessel-erzeugen.sh"  # umlaut-ok: Dateiname
else
    KEYTOOL="$KEYTOOL" sh "$HERE/schluessel-erzeugen.sh"  # umlaut-ok: Dateiname
fi

step "Prüfen und starten"
if [ "$DRY" = 1 ]; then
    echo "    (probelauf) --prüfen, systemctl daemon-reload, systemctl enable --now baumeister-relay"
    exit 0
fi
set +e
env CREDENTIALS_DIRECTORY=/etc/baumeister-relay "$JAVA" -jar /opt/baumeister-relay/baumeister-relay.jar --pruefen  # umlaut-ok: ASCII-Schalter
RC=$?
set -e
# 2 = alles gut, nur noch keine App eingetragen – das ist hier der Normalfall.
[ "$RC" -eq 0 ] || [ "$RC" -eq 2 ] || fail "--prüfen endete mit $RC – Meldung oben"
systemctl daemon-reload
systemctl enable --now baumeister-relay
sleep 3
systemctl is-active -q baumeister-relay || { journalctl -u baumeister-relay -n 20 --no-pager >&2; fail "Dienst läuft nicht"; }

echo
echo "Fertig. Nächste Schritte (docs/installation.md, Stufe 3):"
echo "  1. Router: TCP 25580 auf diesen Rechner freigeben."
echo "  2. Baumeister-App: „im Internet über eine Adresse (HTTPS)“, Adresse dieses Servers und den Server-Pin oben"
echo "     eintragen, „Schlüssel erzeugen“."
echo "  3. Den Fingerabdruck der App hier eintragen (gilt nach 2 s, ohne Neustart):"
echo "     echo 'erlaubt.mein-pc=sha256:…' >> /etc/baumeister-relay/relay.properties"
