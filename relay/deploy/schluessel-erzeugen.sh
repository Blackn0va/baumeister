#!/bin/sh
# Erzeugt Schlüssel und Zertifikat des Baumeister-Relays auf dem Server – einmal, als root.  # umlaut-ok: Dateiname
# Aufruf: sh schluessel-erzeugen.sh   (Repo: relay/deploy/, Anleitung: README.md daneben)  # umlaut-ok: Dateiname
#
# Ergebnis:
#   /etc/baumeister-relay/relay.p12   PKCS12, EC P-256, selbst signiert, 3650 Tage, 0600 root:root
#   /etc/baumeister-relay/relay.pass  Zufallspasswort (32 Byte, Base64), 0600 root:root
# und der Server-Pin auf stdout (sha256:…) – der kommt in die Host-App.
#
# Warum so:
# - EC P-256, selbst signiert, keine CA: Kanal v1 pinnt das Blattzertifikat (docs/kanal-v1.md, Abschnitt 1). Das
#   Relay startet mit nichts anderem (KeyMaterial prüft Kurve und Kettenlänge).
# - 10 Jahre: Der Pin ist der Vertrauensanker; ein Ablaufdatum brächte nur einen Stichtag, an dem die App ausfällt.
#   Wechsel geht jederzeit über zwei Server-Pins in der App (README.md, „Schlüssel wechseln“).
# - Das Passwort steht nie auf einer Kommandozeile (ps, Shell-Verlauf): keytool liest es mit -storepass:file, das
#   Relay bekommt Schlüssel und Passwort über LoadCredential= (systemd kopiert beide nur für den Dienst).
# - keytool aus demselben JDK, mit dem das Relay läuft (/opt/temurin-25-jre, siehe baumeister-relay.service).
# - Neutraler Name ohne SAN (zweite Sicherheitsprüfung 2026-11-08, N7): Jeder, der einen TLS-Handshake beginnt, sieht
#   das Zertifikat – vorher stand dort „baumeister-relay“ samt DDNS-Name und LAN-Adresse. Die App prüft nur den Pin.
#
# Bricht ab, wenn schon ein Schlüssel da ist: Ein Versehen soll nie den Pin ändern, den die App kennt.
# Geprüft 2026-11-08 mit dem keytool von Temurin 21.0.12 (Windows): dieselben Aufrufe ergeben EC secp256r1, und der
# Pin aus exportcert | sha256sum ist gleich dem von „java -jar … --fingerabdruck“ (damals noch mit SAN-Einträgen).

set -eu
umask 077

DIR=/etc/baumeister-relay
KS="$DIR/relay.p12"
PASS="$DIR/relay.pass"

if [ "$(id -u)" -ne 0 ]; then
    echo "schluessel-erzeugen.sh | FEHLER | nur als root (Schlüssel gehören root, 0600)" >&2  # umlaut-ok: Dateiname
    exit 1
fi
# Vorgabe: /opt/temurin-25-jre. relay/einrichten.sh gibt das keytool neben dem gefundenen java mit.
KEYTOOL=${KEYTOOL:-/opt/temurin-25-jre/bin/keytool}
if [ ! -x "$KEYTOOL" ]; then
    echo "schluessel-erzeugen.sh | FEHLER | kein keytool unter $KEYTOOL" >&2  # umlaut-ok: Dateiname
    exit 1
fi
if [ -e "$KS" ] || [ -e "$PASS" ]; then
    echo "schluessel-erzeugen.sh | FEHLER | $KS bzw. $PASS gibt es schon – Wechsel siehe README.md" >&2  # umlaut-ok
    exit 1
fi

install -d -m 0755 -o root -g root "$DIR"
# Zufallspasswort: 32 Byte aus /dev/urandom als Base64, ohne Zeilenumbruch (keytool und Relay lesen die erste Zeile).
head -c 32 /dev/urandom | base64 | tr -d '\n' > "$PASS.neu"
"$KEYTOOL" -genkeypair -alias relay -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 3650 \
    -dname "CN=relay" \
    -keystore "$KS.neu" -storetype PKCS12 -storepass:file "$PASS.neu"
chown root:root "$KS.neu" "$PASS.neu"
chmod 0600 "$KS.neu" "$PASS.neu"
mv "$PASS.neu" "$PASS"
mv "$KS.neu" "$KS"

PIN="sha256:$("$KEYTOOL" -exportcert -alias relay -keystore "$KS" -storetype PKCS12 -storepass:file "$PASS" \
    | sha256sum | cut -d' ' -f1)"
echo "schluessel-erzeugen.sh | fertig | $KS und $PASS (0600 root)"  # umlaut-ok: Dateiname
echo "Server-Pin für die Host-App: $PIN"
