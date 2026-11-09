#!/usr/bin/env python3
"""Programm-Icon des Baumeister-Host (BaumeisterHost.ico) – reines Python, ohne Pillow.

Warum eigenes Skript statt kraken/make_branding.py: Das braucht Pillow und das Website-Logo; auf dem Baurechner ist
Pillow nicht installiert (gemessen 2026-10-28), und der Host soll mit einem Blick von kraken unterscheidbar sein.
Motiv: Ziegelmauer (Baumeister) auf der Akzentfläche #8b5cf6 des Hausstils, abgerundetes Quadrat.

Aufruf (aus host/):
    python -B resources/branding/make_icon.py            # Datei neu schreiben
    python -B resources/branding/make_icon.py --check    # nur vergleichen, Exit 1 bei Abweichung

Format: 16, 24, 32, 48, 64, 128, 256 px, je 32-Bit-BMP mit AND-Maske (jeder Windows-Leser versteht das).
"""

import struct
import sys
from pathlib import Path

SIZES = [16, 24, 32, 48, 64, 128, 256]
BG = (0x8B, 0x5C, 0xF6)
BRICK = (0xF4, 0xF7, 0xFB)
MORTAR = (0x6D, 0x28, 0xD9)


def pixel(size, x, y):
    """RGBA eines Pixels; 4×4-Überabtastung für weiche Kanten."""
    acc = [0.0, 0.0, 0.0, 0.0]
    n = 4
    for sy in range(n):
        for sx in range(n):
            fx = (x + (sx + 0.5) / n) / size
            fy = (y + (sy + 0.5) / n) / size
            r = 0.2  # Eckradius relativ
            dx = max(r - fx, 0, fx - (1 - r))
            dy = max(r - fy, 0, fy - (1 - r))
            if dx * dx + dy * dy > r * r:
                continue
            col = BG
            # Mauer im Bereich 0,2 … 0,8, vier Reihen, versetzte Fugen
            if 0.2 <= fx <= 0.8 and 0.22 <= fy <= 0.78:
                row = int((fy - 0.22) / 0.14)
                ry = (fy - 0.22) - row * 0.14
                off = 0.0 if row % 2 == 0 else 0.1
                rx = (fx - 0.2 + off) % 0.2
                joint = 0.025 if size >= 32 else 0.04
                col = MORTAR if (ry < joint or rx < joint) else BRICK
            acc[0] += col[0]
            acc[1] += col[1]
            acc[2] += col[2]
            acc[3] += 255
    k = n * n
    a = acc[3] / k
    if a == 0:
        return (0, 0, 0, 0)
    return (round(acc[0] / (acc[3] / 255)), round(acc[1] / (acc[3] / 255)), round(acc[2] / (acc[3] / 255)), round(a))


def bmp(size):
    rows = []
    for y in range(size - 1, -1, -1):  # BMP von unten nach oben
        row = bytearray()
        for x in range(size):
            r, g, b, a = pixel(size, x, y)
            row += bytes((b, g, r, a))
        rows.append(bytes(row))
    mask_stride = ((size + 31) // 32) * 4
    mask = bytes(mask_stride * size)  # Alpha trägt die Transparenz
    header = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0, size * size * 4 + len(mask), 0, 0, 0, 0)
    return header + b"".join(rows) + mask


def ico():
    images = [bmp(s) for s in SIZES]
    out = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for s, data in zip(SIZES, images):
        out += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    return out + b"".join(images)


def main():
    target = Path(__file__).with_name("BaumeisterHost.ico")
    data = ico()
    if "--check" in sys.argv:
        same = target.exists() and target.read_bytes() == data
        print("gleich" if same else "ABWEICHUNG")
        return 0 if same else 1
    target.write_bytes(data)
    print(f"{target} ({len(data)} Bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
