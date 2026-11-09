# Messprobe: Wie spricht Piper (espeak-ng, Deutsch) Minecraft-Wörter aus – und welche Umschreibung trifft es besser?
#
# WOZU: Wunsch 2026-11-04. Statt zu raten, zeigt piper --debug die
# Phoneme. Je Zeile: Original | Umschreibung → beide Phonemfolgen. Ergebnis steht in probes/README.md und bestimmt die
# Tabelle in src/voice/speech_text.cpp.
#
# Aufruf: python -I probes/piper_aussprache_probe.py <piper-Ordner> <stimme.onnx> [<zeilen.txt>]
# Ohne zeilen.txt nimmt sie die eingebaute Liste.

import os
import re
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')

PAIRS = [
    ('Creeper', 'Krieper'), ('Redstone', 'Redstoun'), ('Netherite', 'Nesserait'), ('Nether', 'Nesser'),
    ('Enderman', 'Endermän'), ('Zombie', 'Zombi'), ('Skeleton', 'Skelleten'), ('Spawn', 'Spahn'),
    ('Spawner', 'Spahner'), ('Cobblestone', 'Kobbelstoun'), ('Minecraft', 'Mainkraft'), ('Server', 'Sörver'),
    ('Villager', 'Willätscher'), ('Wither', 'Wisser'), ('Elytra', 'Elitra'), ('Slime', 'Slaim'),
    ('Blaze', 'Bläis'), ('Ghast', 'Gast'), ('Beacon', 'Bieken'), ('Chunk', 'Tschank'), ('Craften', 'Krafften'),
    ('Plot', 'Plott'), ('Home', 'Houm'), ('Admin', 'Admin'), ('Survival', 'Sörwaiwel'), ('Creative', 'Krieitiw'),
    ('Gamemode', 'Gäimmoud'), ('okay', 'okeh'), ('Hey', 'Hei'), ('Torch', 'Fackel'), ('Diamond', 'Diamant'),
    ('Pickaxe', 'Spitzhacke'), ('Blackkn0va', 'Blackknova'), ('Vera', 'Vera'), ('Shulker', 'Schulker'),
    ('Piglin', 'Piglin'), ('Warden', 'Woarden'), ('Respawn', 'Riespahn'),
]


def phonemes(piper_dir, model, lines):
    out = {}
    with tempfile.TemporaryDirectory() as tmp:
        proc = subprocess.run([os.path.join(piper_dir, 'piper.exe'), '--model', model, '--output_dir', tmp,
                               '--debug'], input='\n'.join(lines) + '\n', capture_output=True, text=True,
                              encoding='utf-8', cwd=tmp)
        text = None
        for line in (proc.stderr + proc.stdout).splitlines():
            m = re.search(r'Phonemizing text: (.*)$', line)
            if m:
                text = m.group(1).strip()
            m = re.search(r'Converting \d+ phoneme\(s\) to ids: (.*)$', line)
            if m and text is not None:
                out[text] = m.group(1).strip()
                text = None
    return out


def main():
    piper_dir, model = sys.argv[1], sys.argv[2]
    pairs = PAIRS
    if len(sys.argv) > 3:
        pairs = [tuple(x.strip() for x in l.split('|')) for l in open(sys.argv[3], encoding='utf-8') if '|' in l]
    lines = sorted({w for p in pairs for w in p})
    ph = phonemes(piper_dir, model, lines)
    for a, b in pairs:
        print(f'{a:<14} {ph.get(a, "?"):<22} | {b:<14} {ph.get(b, "?")}')


if __name__ == '__main__':
    main()
