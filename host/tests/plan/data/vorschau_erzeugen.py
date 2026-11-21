"""Erzeugt die Testdaten für „Plan-Vorschau gegen den Bau“ (host/tests/gui/test_plan_view.cpp, Auftrag T-017).

WOZU: Die Vorschau der Host-App soll genau die Zellen zeigen, die das Plugin baut. Die erwarteten Zellen schreibt hier
niemand von Hand ab – sie kommen aus dem echten PlanCompiler (PreviewCellsProbe.java). Ändert sich der Compiler, zeigt
der Test die Abweichung, und dieses Skript zieht die Erwartung nach, sobald die Vorschau wieder passt.

Die Pläne sind NACHGEBAUT (kein Auftragstext eines Spielers – das Repo ist öffentlich), aber so gewählt, dass sie
jede in T-017 behobene Abweichung treffen: Hausfenster, Leiter und Einrichtung, Dachfirst als Platte, Hochhaus mit
Leiterschacht und Lampen, fill mit hollow, Aliase und frosted_ice, Bett und hohe Pflanze, halbe Tür, abgeschnittene
Antwort.

Aufruf (aus dem Repo-Wurzelordner, nach „mvn test-compile“; lib/ mit den Server-JARs):
  python -I host/tests/plan/data/vorschau_erzeugen.py [<lib-ordner>]

Schreibt je Plan <name>.json (Form wie die Aufzeichnungen des Hosts: auftrag, antwort, versuch) und
<name>.cells.txt (Ausgabe des PlanCompilers, „x y z block“). Exit 0 sauber, 1 Fehler.
"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def step(**kw):
    return kw


PLANS = {
    # Haus 11 × 11, zwei Geschosse: Innenmaß 9 → volle Einrichtung samt Teppich; Leiter und Zwischendecke.
    "vorschau-haus": {
        "design": "Testhaus",
        "reply": "Test",
        "mode": "new",
        "steps": [
            step(op="house", block="spruce_planks", **{"from": [0, 0, 0], "to": [10, 0, 10]}, floors=2,
                 roof_block="spruce_stairs"),
            step(op="set", block="red_bed", pos=[14, 0, 2], facing="left"),
            step(op="set", block="sunflower", pos=[14, 0, 5]),
            # Tür, deren untere Hälfte danach überschrieben wird: Das Plugin baut die obere allein nicht.
            step(op="set", block="door", pos=[14, 0, 8]),
            step(op="set", block="stone", pos=[14, 0, 8]),
        ],
    },
    # Hochhaus 9 × 11, drei Geschosse, dazu Walm- und Flachdach aus Treppen und Kleinkram mit Aliasnamen.
    "vorschau-hochhaus": {
        "design": "Testhochhaus",
        "reply": "Test",
        "mode": "new",
        "steps": [
            step(op="skyscraper", block="light_gray_concrete", **{"from": [0, 0, 0], "to": [8, 0, 10]}, floors=3),
            step(op="roof", block="stone_brick_stairs", shape="hip", **{"from": [12, 0, 0], "to": [16, 0, 4]}),
            step(op="roof", block="oak_stairs", shape="flat", **{"from": [12, 0, 8], "to": [14, 0, 10]}),
            step(op="fill", block="frosted_ice", hollow=True, **{"from": [18, 0, 0], "to": [21, 3, 3]}),
            step(op="set", block="sign", pos=[18, 0, 6]),
            step(op="set", block="redstone", pos=[19, 0, 6]),
        ],
    },
}

# Abgeschnitten wie bei max_tokens: mitten in „pos“ des dritten Schritts. Das Plugin baut die ersten beiden.
TRUNCATED = (
    '{"design":"Testabbruch","reply":"Test","mode":"new","steps":['
    '{"op":"fill","block":"stone","from":[0,0,0],"to":[4,0,4]},\n'
    '{"op":"walls","block":"oak_planks","from":[0,1,0],"to":[4,3,4]},\n'
    '{"op":"set","block":"lantern","pos":[2,1,'
)


def main() -> int:
    lib = sys.argv[1] if len(sys.argv) > 1 else str(ROOT / "lib")
    with tempfile.TemporaryDirectory() as tmp:
        src = pathlib.Path(tmp) / "ein"
        out = pathlib.Path(tmp) / "aus"
        src.mkdir()
        answers = {name: json.dumps(plan, ensure_ascii=False, separators=(",", ":")) for name, plan in PLANS.items()}
        answers["vorschau-abgeschnitten"] = TRUNCATED
        for name, answer in answers.items():
            record = {"auftrag": f"Testplan {name}", "versuch": "0", "antwort": answer}
            text = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
            (HERE / f"{name}.json").write_text(text, encoding="utf-8", newline="\n")
            (src / f"{name}.json").write_text(text, encoding="utf-8", newline="\n")
        cp = ";".join([str(ROOT / "target" / "classes"), str(ROOT / "target" / "test-classes"), f"{lib}/*"])
        run = subprocess.run(["java", "-cp", cp, "com.blackn0va.baumeister.dataset.PreviewCellsProbe", str(src),
                              str(out)], capture_output=True, text=True, encoding="utf-8", errors="replace")
        if run.returncode != 0:
            print(run.stdout, run.stderr, sep="\n")
            return 1
        for name in answers:
            produced = out / f"{name}.compiler.txt"
            status = produced.read_text(encoding="utf-8").splitlines()[0]
            if not status.startswith("# status gebaut"):
                print(f"{name}: Plugin baut den Plan nicht ({status}) – Testplan anpassen.")
                return 1
            shutil.copyfile(produced, HERE / f"{name}.cells.txt")
            print(f"{name}: {status}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
