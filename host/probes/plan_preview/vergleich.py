"""Vergleicht die Zellen der Host-Vorschau mit denen des PlanCompilers (Auftrag T-017, AK-19).

WOZU: Weicht die Plan-Vorschau am GPU-PC vom echten Bau ab, sieht der Spieler dort etwas anderes, als im Spiel
entsteht. Dieses Skript zählt die Abweichungen je Plan und je Art, damit sie benannt, behoben oder als bewusst
festgehalten werden können – gemessen statt vermutet.

Eingabe: die Ausgabeordner von PreviewCellsProbe.java (<name>.compiler.txt, <name>.review.txt) und
swbh_probe_plan_preview.exe (<name>.host.txt), je Zeile „x y z block“, erste Zeile „# status …“.

Aufruf:  python -I vergleich.py <java-ordner> <host-ordner> [compiler|review] [-v]

Arten:
  fehlt     – das Plugin baut dort einen Block, die Vorschau zeigt nichts (gruppiert nach Block des Plugins)
  zuviel    – die Vorschau zeigt einen Block, das Plugin baut dort nichts (gruppiert nach Block der Vorschau)
  anders    – beide haben dort einen Block, aber verschiedene (gruppiert nach „Vorschau → Plugin“)
Plangenau = keine einzige Abweichung.
"""

import collections
import pathlib
import sys


def read(path: pathlib.Path):
    status = ""
    cells = {}
    if not path.exists():
        return "fehlt_datei", cells
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# status"):
            status = line[len("# status "):]
            continue
        if line.startswith("#") or not line.strip():
            continue
        x, y, z, block = line.split(" ", 3)
        cells[(int(x), int(y), int(z))] = block.removeprefix("minecraft:")
    return status, cells


def main() -> int:
    # FALLE: python -I überliest PYTHONIOENCODING; die Windows-Konsole (cp1252) kennt „→“ nicht.
    sys.stdout.reconfigure(encoding="utf-8")
    java_dir = pathlib.Path(sys.argv[1])
    host_dir = pathlib.Path(sys.argv[2])
    which = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("-") else "compiler"
    verbose = "-v" in sys.argv
    kinds = collections.Counter()
    detail = {"fehlt": collections.Counter(), "zuviel": collections.Counter(), "anders": collections.Counter()}
    plans_with = collections.Counter()
    exact = 0
    compared = 0
    skipped = collections.Counter()
    for jf in sorted(java_dir.glob(f"*.{which}.txt")):
        name = jf.name[: -len(f".{which}.txt")]
        jstatus, jcells = read(jf)
        hstatus, hcells = read(host_dir / f"{name}.host.txt")
        if not jstatus.startswith("gebaut") or not hstatus.startswith("gebaut"):
            skipped[f"plugin {jstatus.split(' ')[0]} / vorschau {hstatus.split(' ')[0]}"] += 1
            continue
        compared += 1
        per = collections.Counter()
        for p, b in jcells.items():
            h = hcells.get(p)
            if h is None:
                per["fehlt"] += 1
                detail["fehlt"][b] += 1
            elif h != b:
                per["anders"] += 1
                detail["anders"][f"{h} → {b}"] += 1
        for p, h in hcells.items():
            if p not in jcells:
                per["zuviel"] += 1
                detail["zuviel"][h] += 1
        if not per:
            exact += 1
        for k, v in per.items():
            kinds[k] += v
            plans_with[k] += 1
        if verbose:
            print(f"{name}: plugin {len(jcells)} vorschau {len(hcells)} {dict(per)}")
    print(f"Vergleich gegen „{which}“: {compared} Pläne verglichen, {exact} plangenau")
    for k, v in sorted(skipped.items()):
        print(f"  nicht verglichen: {k}: {v}")
    for k in ("fehlt", "zuviel", "anders"):
        print(f"{k}: {kinds[k]} Zellen in {plans_with[k]} Plänen")
        for name, n in detail[k].most_common(25):
            print(f"    {n:7d}  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
