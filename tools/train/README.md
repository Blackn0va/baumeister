# Eigenes Baumeister-Modell – Lehrset, Messung, Training

**Ein kleines Coder-Modell (Qwen2.5-Coder 1.5B), das Baupläne im Plan-JSON des Plugins schreibt – schnell auf kleiner
GPU und mit weniger verworfenen Plänen.**

Drei Teile, alle mit derselben Prüfkette wie im Plugin (`PlanJudge` → `PlanReview`, Plan-Prüfer streng, freie Wiese):

| Teil | Wo | Ergebnis |
|---|---|---|
| Lehrset | `src/test/java/.../dataset/DatasetGenerator.java` | `target/dataset/train.jsonl`, `eval.jsonl`, `statistik.md` |
| Messgerät | `src/test/java/.../dataset/PlanBench.java` | `target/bench/<modell>-<zeit>.md` + `.jsonl` |
| Training | `tools/train/train_qlora.py`, `tools/train/export-gguf.ps1` | GGUF + `modell-eintrag.json` |

## 1. Lehrset erzeugen (beliebiger PC, ~35 s für 4000 Einträge)

```bash
mvn -o test-compile
java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.DatasetGenerator --anzahl 4000
```

Optional echte LLM-Pläne von dev01 dazu (`plugins/Baumeister/pläne/`, die letzten 50): Ordner lokal holen und
`--pläne <ordner>` anhängen. Nur Pläne, die die strenge Prüfung bestehen, kommen hinein; Anbau-, Plot- und
Folgeaufträge bleiben draußen (dort fehlt der Bauplatz bzw. das Gedächtnis aus dem Gespräch).

Aus demselben Ordner kommen Reparaturrunden (Art `llm_reparatur`): Folgen auf einen Auftrag Versuch 0, 1 …
desselben Spielers, wird das letzte Paar zum Gespräch Nutzer → verworfener Plan → Rückmeldung des Prüfers →
korrigierter Plan – genau so, wie das Plugin die Korrekturrunde schickt (`PlanPrompt.repairConversation`,
Rückmeldung aus `PlanReview`). Nur, wenn der letzte Versuch die strenge Prüfung besteht. `train_qlora.py` lernt
dabei nur auf die letzte Antwort; der verworfene Plan steht maskiert im Prompt.

Ins Lehrset kommt nur, was **ohne jeden Befund** durchgeht: angenommen im ersten Versuch, keine Nachbesserung durch
PlanReview (Hohlmachen, weggelassene Blöcke, Stützen), keine Compiler-Warnung, kein Linter-Hinweis. Die festen
Testaufträge des Messgeräts (`src/test/resources/dataset/bench-orders.txt`) sind ausgeschlossen.

### Vorbilder: echte Bauten als Lehrpaare

```bash
java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.DatasetGenerator --anzahl 4000 --vorbilder <ordner>
```

Der Ordner enthält Bauwerk-Dateien (`.schem` aus WorldEdit, `.litematic`, `.nbt` aus dem Strukturblock) und eine
`vorbilder.json`:

```json
[{"datei": "turm.schem", "auftrag": "ein runder Turm aus Steinziegeln", "vorne": "s"}]
```

`vorne` ist die Himmelsrichtung, in die die Vorderseite beim Speichern zeigte (wie in `bauteile.yml`). Der
Rückübersetzer (`structure/PlanDecompiler`) zerlegt den Bau in höchstens 40 Schritte (fill, walls, cylinder, line,
set, door), baut ihn mit dem echten Compiler zurück und vergleicht Block für Block. Ins Lehrset kommt er nur bei
mindestens 85 % Übereinstimmung und ohne Prüfbefund. Ein Bericht ohne `vorbilder.json` (Vorderseite s):

```bash
java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.VorbildImport <ordner>
```

Gemessen an den eigenen Server-Schematics (2026-11-01): verlustfrei exakt, aber 121–2054 Schritte; in 40 Schritten
36–92 % des Baus. Keine davon besteht die strenge Prüfung (frei stehende Türen, Säulen ohne Halt) – es braucht frei
stehende, „ordentliche“ Bauten. Altes MCEdit-Format (`.schematic` vor 1.13) wird abgelehnt: in WorldEdit laden und
als `.schem` neu speichern.

## 2. Messen (Host-App oder llama-server läuft)

```bash
java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.PlanBench --url http://127.0.0.1:8080/v1/chat/completions --modell qwen25-7b
```

Gleiches Gespräch wie das Plugin (System-Prompt, `PlanPrompt`, Schema → Grammatik im Host, Temperatur 0,4,
3000 Token, eine Reparaturrunde). Spalten: *sofort* angenommen, *nach Reparatur*, *verworfen*, *sauber* (erste
Antwort besteht sogar den Lehrset-Maßstab). Vorher und nachher mit demselben Host und demselben Kontext messen.

## 3. Training auf dem GPU-PC (NVIDIA, ~12 GB)

```powershell
py -m venv C:\Users\<du>\.local\opt\baumeister-train-venv
C:\Users\<du>\.local\opt\baumeister-train-venv\Scripts\python -m pip install -r tools\train\requirements-gpu.txt
C:\Users\<du>\.local\opt\baumeister-train-venv\Scripts\python tools\train\train_qlora.py --daten target\dataset --ziel out\baumeister-coder-1.5b
pwsh -File tools\train\export-gguf.ps1 -Merged out\baumeister-coder-1.5b\merged -LlamaCpp <llama.cpp-b10900-Quellen> -Python C:\Users\<du>\.local\opt\baumeister-train-venv\Scripts\python.exe
```

- Ungeprüft: die CUDA-Räder (`requirements-gpu.txt`, Index cu128) und bitsandbytes. Passt die CUDA-Fassung nicht,
  die Index-URL anpassen; die CPU-Probe lief mit Python 3.14.7.
- Grundmodell `Qwen/Qwen2.5-Coder-1.5B-Instruct` (Apache-2.0) wird beim ersten Lauf von Hugging Face geladen
  (~3,1 GB). `--revision <commit>` nagelt die Fassung fest. 3B geht mit `--modell Qwen/Qwen2.5-Coder-3B-Instruct`,
  ist aber nur nicht kommerziell nutzbar (Qwen-Research).
- QLoRA: Grundmodell 4-bit NF4, LoRA-Rang 16 auf allen Projektionen, lr 2e-4, 2 Epochen, Batch 1 × 8 akkumuliert.
  Gelernt wird nur auf den Antwort-Token; Einträge über `--max-len 8192` werden übersprungen, nie abgeschnitten.
- Ein Eintrag hat ~6500 Token (gemessen: Median 6511, max 6553; System-Prompt 4065, Antwort ~180) – das Training
  rechnet also vor allem Prompt. Logits entstehen nur für die Antwort (`logits_to_keep`, in der CPU-Probe 15× schneller
  bei gleichem Verlust). Rechenzeit auf dem GPU-PC ist **nicht gemessen**.
- Exitcode 2 = fertig, aber der Eval-Verlust ist nicht gesunken.
- `llama.cpp`-Quellen: dieselbe Fassung wie die App (b10900, `host/deps.lock.json`), z. B. aus einem Host-Bau unter
  `<bau>\_deps\llama-src`. Export als Q8_0 (~1,6 GB) braucht nur Python; Q4_K_M zusätzlich `llama-quantize.exe`.

## 4. In der App benutzen

Bis das Modell irgendwo mit festem Commit liegt: in der Host-App **Eigene GGUF-Datei** wählen (Kontext dort 8192).
Danach erneut messen (Schritt 2) und die Zahlen neben die Grundlinie schreiben (Qwen2.5 7B,
iGPU, Kontext 8192: 20/24 sofort, 6/24 sauber, 4,2 Token/s). Die Grundlinie lief mit Kontext 8192 – das eigene Modell
genauso messen, sonst sind die Zahlen nicht vergleichbar.

Ein Katalogeintrag in `host/deps.lock.json` braucht `repo` + `revision` auf Hugging Face, denn die App lädt
Katalogmodelle von dort (fail-closed mit SHA-256). `export-gguf.ps1` schreibt Größe und SHA-256 schon in
`modell-eintrag.json`; Hochladen ist eine Veröffentlichung und bleibt deine Entscheidung.

## CPU-Probe (ohne NVIDIA)

```bash
python tools/train/train_qlora.py --daten target/dataset --ziel target/train-probe --probe <Qwen2.5-GGUF>
pwsh -File tools/train/export-gguf.ps1 -Merged target/train-probe/merged -LlamaCpp C:\swb\bh-r6\_deps\llama-src -Name probe -Typ f16
```

Winziges Qwen2 mit Zufallsgewichten, Tokenizer aus der GGUF, wenige Schritte. Prüft Vorlage, Maskierung, LoRA,
Speichern, Zusammenführen und GGUF-Export – **nicht**, ob das Lernen etwas taugt.
