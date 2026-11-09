"""Messprobe Vorlagen (2026-11-04): Wo liegt bei den Vanilla-Bauvorlagen die Vorderseite?

Frage: Kann VanillaTemplates die Vorderseite eines Dorfhauses aus der Vorlage selbst ablesen? Gemessen werden je
Vorlage Größe, Zahl der Blöcke ohne Luft, alle Verbund-Blöcke (jigsaw) mit Lage und orientation sowie alle Türen.

Aufruf: python -I tools/vorlagen_probe.py <ordner mit data/minecraft/structure/...>
Die .nbt-Dateien stammen aus dem Server-JAR (Mojang) und liegen bewusst NICHT im Repo.
"""
import gzip
import os
import struct
import sys


def read_nbt(path):
    data = gzip.open(path).read()
    pos = 0

    def take(n):
        nonlocal pos
        out = data[pos:pos + n]
        pos += n
        return out

    def string():
        (n,) = struct.unpack('>H', take(2))
        return take(n).decode('utf-8', 'replace')

    def payload(tag):
        if tag == 1:
            return struct.unpack('>b', take(1))[0]
        if tag == 2:
            return struct.unpack('>h', take(2))[0]
        if tag == 3:
            return struct.unpack('>i', take(4))[0]
        if tag == 4:
            return struct.unpack('>q', take(8))[0]
        if tag == 5:
            return struct.unpack('>f', take(4))[0]
        if tag == 6:
            return struct.unpack('>d', take(8))[0]
        if tag == 7:
            (n,) = struct.unpack('>i', take(4))
            return take(n)
        if tag == 8:
            return string()
        if tag == 9:
            sub = take(1)[0]
            (n,) = struct.unpack('>i', take(4))
            return [payload(sub) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = take(1)[0]
                if t == 0:
                    return out
                name = string()
                out[name] = payload(t)
        if tag == 11:
            (n,) = struct.unpack('>i', take(4))
            return list(struct.unpack('>%di' % n, take(4 * n)))
        if tag == 12:
            (n,) = struct.unpack('>i', take(4))
            return list(struct.unpack('>%dq' % n, take(8 * n)))
        raise ValueError('tag %d' % tag)

    t = take(1)[0]
    string()
    return payload(t)


def describe(path, root):
    nbt = read_nbt(path)
    size = nbt['size']
    palette = nbt['palette'] if 'palette' in nbt else nbt['palettes'][0]
    solid = 0
    jig = []
    doors = []
    for b in nbt['blocks']:
        st = palette[b['state']]
        name = st['Name']
        props = st.get('Properties', {})
        if name in ('minecraft:air', 'minecraft:structure_void'):
            continue
        solid += 1
        if name == 'minecraft:jigsaw':
            jig.append((b['pos'], props.get('orientation'), b.get('nbt', {}).get('pool', '')))
        if name.endswith('_door') and props.get('half') == 'lower':
            doors.append((b['pos'], props.get('facing')))
    rel = os.path.relpath(path, root).replace(os.sep, '/')
    print('%s size=%s blöcke=%d' % (rel, size, solid))
    for p, o, pool in jig:
        print('   jigsaw %s %s %s' % (p, o, pool))
    for p, f in doors:
        print('   tür %s facing=%s' % (p, f))


def main():
    root = sys.argv[1]
    base = os.path.join(root, 'data', 'minecraft', 'structure')
    for dirpath, _, files in os.walk(base):
        for f in sorted(files):
            if f.endswith('.nbt'):
                describe(os.path.join(dirpath, f), base)


if __name__ == '__main__':
    main()
