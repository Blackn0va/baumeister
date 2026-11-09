"""Liste aller Blockzustände einer Minecraft-Fassung aus dem Datengenerator des Vanilla-Server-Jars (Plan B9).

Aufruf aus der Repo-Wurzel (Java 25 für 26.x, Java 21 für 1.21.x):
  python -I probes/blockstates/blockstates.py --java <java.exe> <server.jar> src/test/resources/blockstates

WOZU: Jeder Block in jeder Kombination (2026-11-08) lässt sich nur gegen
eine vollständige Liste prüfen. Die kennt nur Minecraft selbst: Der Datengenerator schreibt mit --reports die Datei
reports/blocks.json mit jedem Zustand jedes Blocks. Abgeschrieben oder aus dem Gedächtnis fehlten immer welche.

Ablauf: Fassung aus version.json im Jar lesen → Generator in einem leeren Temp-Ordner AUSSERHALB des Repos starten
(der Bundler entpackt versions/ und libraries/ ins Arbeitsverzeichnis) → blocks.json lesen → je Zustand eine Zeile
„minecraft:id[k=v,…]“ (Eigenschaften in der Reihenfolge von Minecraft, Blöcke wie in blocks.json – alphabetisch,
gemessen –, Zustände nach ihrer Nummer) → <fassung>.txt.gz schreiben. gzip ohne Zeitstempel und Dateinamen: Gleicher
Jar = gleiche Bytes (zweiter Lauf 2026-11-08 byte-gleich).

FALLE: Nichts wird heruntergeladen. Das Jar muss schon im Cache liegen; ein falsches Jar erkennt version.json.
"""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ZEILE = re.compile(r'^minecraft:[a-z0-9_]+(\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\])?$')


def fassung(jar):
    """(id, java_version) aus version.json im Server-Jar."""
    with zipfile.ZipFile(jar) as z:
        info = json.loads(z.read('version.json').decode('utf-8'))
    return info['id'], int(info['java_version'])


def java_major(java):
    """Hauptversion von java -version (stderr): 'openjdk version "25.0.5"' → 25, '"1.8.0_402"' → 8."""
    out = subprocess.run([java, '-version'], capture_output=True, text=True, check=True).stderr
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        raise SystemExit('Java-Version nicht lesbar: ' + out.strip())
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 else major


def erzeugen(java, jar, arbeit):
    """Datengenerator mit --reports; gibt den Pfad zu reports/blocks.json zurück."""
    ausgabe = arbeit + '/out'
    with open(arbeit + '/gen.log', 'w', encoding='utf-8') as log:
        r = subprocess.run([java, '-DbundlerMainClass=net.minecraft.data.Main', '-jar', jar, '--reports',
                            '--output', ausgabe], cwd=arbeit, stdout=log, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        raise SystemExit('Datengenerator endete mit ' + str(r.returncode) + ', Protokoll: ' + arbeit + '/gen.log')
    return ausgabe + '/reports/blocks.json'


def zeilen(blocks):
    """Je Zustand eine Zeile; Blöcke wie in blocks.json, Zustände nach id."""
    out = []
    for block_id, block in blocks.items():
        for state in sorted(block['states'], key=lambda s: s['id']):
            props = state.get('properties', {})
            text = block_id
            if props:
                text += '[' + ','.join(k + '=' + v for k, v in props.items()) + ']'
            if not ZEILE.match(text):
                raise SystemExit('Unerwartete Form: ' + text)
            out.append(text)
    return out


def schreiben(lines, ziel):
    data = ('\n'.join(lines) + '\n').encode('utf-8')
    with open(ziel, 'wb') as f:
        with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0, compresslevel=9) as gz:
            gz.write(data)


def main():
    # Ausgabe in eine Pipe (Git Bash, Protokoll) sonst in cp1252 – „blöcke“ käme verstümmelt an.
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description='Alle Blockzustände aus dem Vanilla-Datengenerator')
    ap.add_argument('jar', help='Vanilla-Server-Jar (Bundler), z. B. C:/swb/dev01-probe/1.21.11/cache/mojang_26.2.jar')
    ap.add_argument('ziel', help='Ordner für <fassung>.txt.gz, z. B. src/test/resources/blockstates')
    ap.add_argument('--java', default='java', help='java.exe mit mindestens der Version aus version.json')
    ap.add_argument('--behalten', action='store_true', help='Temp-Ordner nicht löschen (Protokoll ansehen)')
    a = ap.parse_args()

    name, braucht = fassung(a.jar)
    hat = java_major(a.java)
    if hat < braucht:
        raise SystemExit(name + ' braucht Java ' + str(braucht) + ', ' + a.java + ' ist Java ' + str(hat))
    arbeit = tempfile.mkdtemp(prefix='blockstates-' + name + '-')
    try:
        with open(erzeugen(a.java, a.jar, arbeit), encoding='utf-8') as f:
            blocks = json.load(f)
        lines = zeilen(blocks)
        if len(set(lines)) != len(lines):
            raise SystemExit('Doppelte Zustände in blocks.json')
        ziel = a.ziel.rstrip('/\\') + '/' + name + '.txt.gz'
        schreiben(lines, ziel)
    finally:
        if a.behalten:
            print('Temp-Ordner:', arbeit)
        else:
            try:
                shutil.rmtree(arbeit)
            except OSError as e:
                # Nur Aufräumen: Die Liste ist geschrieben; ein gesperrter Temp-Ordner (Virenscanner) bleibt liegen.
                print('Temp-Ordner bleibt liegen:', arbeit, e, file=sys.stderr)
    with open(a.jar, 'rb') as f:
        jar_sha = hashlib.sha256(f.read()).hexdigest()
    with open(ziel, 'rb') as f:
        gz_sha = hashlib.sha256(f.read()).hexdigest()
    print('fassung=' + name, 'blöcke=' + str(len(blocks)), 'zustände=' + str(len(lines)), 'datei=' + ziel)
    print('jar_sha256=' + jar_sha, 'gz_sha256=' + gz_sha)


if __name__ == '__main__':
    sys.exit(main())
