"""Messprobe Cartoon-Stimme: Piper mit length_scale L*r, dann um r schneller abgespielt.

Misst Dauer und Grundfrequenz (Autokorrelation auf stimmhaften Abschnitten) für mehrere r.
Schreibt je r eine WAV in diesen Ordner – zum Anhören.
"""
import json
import os
import struct
import subprocess
import sys
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
P = os.path.join(os.path.expanduser('~'), '.local', 'opt', 'piper-messung')
EXE = os.path.join(P, 'piper', 'piper.exe')
MODEL = os.path.join(P, 'de_DE-thorsten-medium.onnx')
TEXT = 'Hallo! Ich bin Vera. Lass uns zusammen ein Abenteuer erleben – pass auf den Creeper auf!'
BASE = 0.92


def synth(length_scale, out):
    cmd = [EXE, '--model', MODEL, '--output_file', out, '--length_scale', f'{length_scale:.3f}',
           '--noise_scale', '0.8', '--noise_w', '0.9', '--quiet']
    subprocess.run(cmd, input=TEXT.encode('utf-8'), check=True, cwd=os.path.dirname(EXE))


def read(path):
    with wave.open(path, 'rb') as w:
        rate = w.getframerate()
        n = w.getnframes()
        data = struct.unpack('<%dh' % n, w.readframes(n))
    return rate, [s / 32768.0 for s in data]


def write(path, rate, samples):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack('<%dh' % len(samples),
                                  *[max(-32768, min(32767, int(s * 32767))) for s in samples]))


def f0(rate, x):
    """Median-Grundfrequenz über stimmhafte 40-ms-Fenster (Autokorrelation 70–500 Hz)."""
    step = 2  # auf halbe Rate, reicht für 500 Hz
    x = x[::step]
    r = rate // step
    win = int(0.04 * r)
    lo, hi = int(r / 500), int(r / 70)
    found = []
    for start in range(0, len(x) - win - hi, win):
        seg = x[start:start + win + hi]
        energy = sum(v * v for v in seg[:win])
        if energy < 0.02 * win * 0.01:
            continue
        best, best_lag = 0.0, 0
        for lag in range(lo, hi):
            c = sum(seg[i] * seg[i + lag] for i in range(win))
            if c > best:
                best, best_lag = c, lag
        if best_lag and best > 0.5 * energy:
            found.append(r / best_lag)
    found.sort()
    return found[len(found) // 2] if found else 0.0, len(found)


rows = []
for factor in [1.0, 1.2, 1.3, 1.4]:
    raw = os.path.join(HERE, f'roh_{factor:.1f}.wav')
    synth(BASE * factor, raw)
    rate, x = read(raw)
    played_rate = round(rate * factor)  # so gibt die App sie wieder (Pcm.rate hochgesetzt)
    out = os.path.join(HERE, f'vera_cartoon_{factor:.1f}.wav')
    write(out, played_rate, x)
    dur = len(x) / played_rate
    hz, n = f0(played_rate, x)
    rows.append({'faktor': factor, 'dauer_s': round(dur, 2), 'grundton_hz': round(hz, 1), 'fenster': n,
                 'datei': os.path.basename(out)})
    os.remove(raw)
print(json.dumps(rows, ensure_ascii=False, indent=1))
