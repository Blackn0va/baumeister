"""QLoRA-Feintuning eines kleinen Coder-Modells auf das Baumeister-Lehrset.

Zweck: Ein eigenes Modell, das Baupläne im Plan-JSON des Plugins schreibt (Qwen2.5-Coder 1.5B oder 3B), damit
kleine GPUs schnell planen und weniger Pläne verworfen werden (dev01 2026-10-31: 55 von 136 verworfen).

Eingabe: das Ausgabeverzeichnis von DatasetGenerator (train.jsonl, eval.jsonl, system-prompt.txt). Jeder Eintrag
wird als Gespräch system → user → assistant mit der Chatvorlage des Grundmodells gesetzt – derselbe System-Prompt
und dieselbe Nutzernachricht, die das Plugin schickt. Gelernt wird NUR auf den Antwort-Token (Plan-JSON); der Rest
ist mit -100 maskiert. Wer das Maskieren entfernt, bringt dem Modell bei, den System-Prompt nachzuplappern, und
verbraucht ~97 % der Rechenzeit für Token, die es nie erzeugen soll.

Zu lange Einträge werden übersprungen (gezählt), nie abgeschnitten: Ein abgeschnittener System-Prompt wäre ein
anderes Gespräch als im Plugin.

Aufruf (GPU-PC, NVIDIA, ~12 GB):
    python tools/train/train_qlora.py --daten target/dataset --ziel out/baumeister-coder-1.5b
Probe ohne GPU (winziges Zufallsmodell, Tokenizer aus einer vorhandenen Qwen2.5-GGUF, wenige Schritte):
    python tools/train/train_qlora.py --daten target/dataset --ziel out/probe --probe <pfad.gguf>

Exitcodes: 0 fertig, 1 Fehler, 2 fertig, aber Eval-Verlust nicht gesunken.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

import torch

# Qwen2.5 schreibt das Ende einer Antwort als <|im_end|>. Ohne dieses Token im Ziel lernt das Modell nicht
# aufzuhören und schreibt bis max_tokens weiter – die Grammatik im Host beendet zwar das JSON, aber erst nach
# Leerlauf-Token.
END_OF_TURN = "<|im_end|>"


def load_rows(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def encode(tokenizer, system: str, row: dict, max_len: int):
    """Token und Labels eines Eintrags; None, wenn er zu lang ist.

    Prompt-Teil über apply_chat_template mit add_generation_prompt=True – genau so, wie llama.cpp im Host die
    Vorlage aus der GGUF setzt. Die Antwort wird angehängt und mit END_OF_TURN abgeschlossen.
    """
    user = next(m["content"] for m in row["messages"] if m["role"] == "user")
    answer = next(m["content"] for m in row["messages"] if m["role"] == "assistant")
    prompt_ids = tokenizer.apply_chat_template(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        add_generation_prompt=True, tokenize=True)
    if isinstance(prompt_ids, dict):  # neuere transformers liefern BatchEncoding
        prompt_ids = prompt_ids["input_ids"]
    answer_ids = tokenizer(answer + END_OF_TURN, add_special_tokens=False)["input_ids"]
    ids = list(prompt_ids) + list(answer_ids)
    if len(ids) > max_len:
        return None
    labels = [-100] * len(prompt_ids) + list(answer_ids)
    return ids, labels, len(prompt_ids), len(answer_ids)


def check_template(tokenizer, system: str, row: dict) -> None:
    """Gegenprobe: Prompt + Antwort muss als Präfix der vollständig gesetzten Vorlage stehen.

    Sonst setzt das Training die Antwort anders, als die Vorlage es im Host tut, und das Modell lernt ein Format,
    das es beim Planen nie sieht.
    """
    user = next(m["content"] for m in row["messages"] if m["role"] == "user")
    answer = next(m["content"] for m in row["messages"] if m["role"] == "assistant")
    full = tokenizer.apply_chat_template(
        [{"role": "system", "content": system}, {"role": "user", "content": user},
         {"role": "assistant", "content": answer}], tokenize=False)
    prompt = tokenizer.apply_chat_template(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        add_generation_prompt=True, tokenize=False)
    if not full.startswith(prompt + answer + END_OF_TURN):
        raise SystemExit("Chatvorlage passt nicht zur Annahme prompt + antwort + <|im_end|> – Abbruch. "
                         "Anderes Grundmodell? Dann END_OF_TURN und encode() anpassen.")


def build_probe_model(gguf: Path):
    """Winziges Qwen2 mit Zufallsgewichten und dem echten Qwen2.5-Tokenizer aus einer GGUF.

    WOZU: Die Probe soll den ganzen Weg prüfen (Vorlage, Maskierung, LoRA, Speichern, Zusammenführen, GGUF-Export),
    ohne ein Modell herunterzuladen und ohne GPU. Was sie NICHT prüft: ob das Lernen etwas taugt – dafür ist das
    Modell zu klein und zu kurz trainiert.
    """
    from transformers import AutoTokenizer, Qwen2Config, Qwen2ForCausalLM
    tokenizer = AutoTokenizer.from_pretrained(str(gguf.parent), gguf_file=gguf.name)
    config = Qwen2Config(vocab_size=len(tokenizer), hidden_size=64, intermediate_size=128, num_hidden_layers=2,
                         num_attention_heads=4, num_key_value_heads=2, max_position_embeddings=32768,
                         tie_word_embeddings=True)
    torch.manual_seed(0)
    model = Qwen2ForCausalLM(config)
    return tokenizer, model


def answer_loss(model, ids, n_answer, device):
    """Mittlerer Kreuzentropie-Verlust nur über die Antwort-Token.

    Logits nur für die letzten n_answer + 1 Positionen (logits_to_keep): Die Antwort steht am Ende, und Position i
    sagt Token i + 1 voraus. Vorher (labels=… an das Modell) entstanden Logits für ALLE ~6500 Positionen über das
    ganze Vokabular (151 646 bei Qwen2.5) – gemessen 2026-10-31 in der CPU-Probe ~30 s je Schritt, auf der GPU
    ~2 GB bf16 plus die fp32-Kopie im Verlust, nur um sie danach mit -100 wegzuwerfen. Wer das zurückdreht, läuft
    auf 12 GB beim 1.5B-Modell an den Speicherrand. Gleichheit beider Wege prüft self_test().
    """
    x = torch.tensor([ids], device=device)
    logits = model(input_ids=x, logits_to_keep=n_answer + 1).logits[0, :-1].float()
    return torch.nn.functional.cross_entropy(logits, x[0, -n_answer:])


def self_test(model, sample, device) -> None:
    """Gegenprobe: answer_loss == Verlust über volle Logits mit -100-Maske (nur in der Probe, braucht Speicher)."""
    ids, labels, _, n_answer = sample
    with torch.no_grad():
        fast = answer_loss(model, ids, n_answer, device).item()
        full = model(input_ids=torch.tensor([ids], device=device),
                     labels=torch.tensor([labels], device=device)).loss.item()
    print(f"Selbsttest Verlust: nur Antwort-Logits {fast:.6f}, volle Logits {full:.6f}")
    if abs(fast - full) > 1e-3 * max(1.0, abs(full)):
        raise SystemExit("Selbsttest: answer_loss weicht vom vollen Verlust ab – Abbruch")


def evaluate(model, data, device) -> float:
    model.eval()
    total, count = 0.0, 0
    with torch.no_grad():
        for ids, _, _, n_answer in data:
            total += answer_loss(model, ids, n_answer, device).item() * n_answer
            count += n_answer
    model.train()
    return total / max(count, 1)


def main() -> int:
    ap = argparse.ArgumentParser(description="QLoRA-Feintuning auf das Baumeister-Lehrset")
    ap.add_argument("--daten", type=Path, required=True, help="Ausgabe von DatasetGenerator")
    ap.add_argument("--ziel", type=Path, required=True, help="Ausgabeordner (Adapter, zusammengeführtes Modell)")
    ap.add_argument("--modell", default="Qwen/Qwen2.5-Coder-1.5B-Instruct",
                    help="Grundmodell (Hugging-Face-Name oder Ordner)")
    ap.add_argument("--revision", default=None, help="Commit des Grundmodells (festnageln wie in deps.lock.json)")
    ap.add_argument("--epochen", type=float, default=2.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rang", type=int, default=16, help="LoRA-Rang")
    ap.add_argument("--akkumulieren", type=int, default=8, help="Gradientenakkumulation (Batch je Schritt = 1)")
    ap.add_argument("--max-len", type=int, default=8192, help="längere Einträge werden übersprungen")
    ap.add_argument("--eval-alle", type=int, default=200, help="Eval-Verlust alle N Optimiererschritte")
    ap.add_argument("--seed", type=int, default=20261031)
    ap.add_argument("--probe", type=Path, default=None,
                    help="CPU-Probe: Pfad zu einer Qwen2.5-GGUF (nur für den Tokenizer), winziges Zufallsmodell")
    ap.add_argument("--probe-schritte", type=int, default=4)
    args = ap.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    system = (args.daten / "system-prompt.txt").read_text(encoding="utf-8")
    train_rows = load_rows(args.daten / "train.jsonl")
    eval_rows = load_rows(args.daten / "eval.jsonl")
    args.ziel.mkdir(parents=True, exist_ok=True)

    from peft import LoraConfig, get_peft_model

    cuda = torch.cuda.is_available() and args.probe is None
    device = torch.device("cuda" if cuda else "cpu")
    if args.probe is not None:
        tokenizer, model = build_probe_model(args.probe)
        targets = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]
        train_rows = train_rows[:args.probe_schritte * args.akkumulieren]
        eval_rows = eval_rows[:4]
    else:
        if not cuda:
            print("FEHLER: keine CUDA-GPU gefunden. Für einen Probelauf ohne GPU --probe <gguf> nutzen.",
                  file=sys.stderr)
            return 1
        from peft import prepare_model_for_kbit_training
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
        tokenizer = AutoTokenizer.from_pretrained(args.modell, revision=args.revision)
        # QLoRA: Grundmodell 4-bit NF4 eingefroren, Rechnen in bf16. Bei 1.5B passen so auch 8k-Kontexte mit
        # Gradient Checkpointing in 12 GB; 3B ebenfalls, aber langsamer.
        bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                                 bnb_4bit_compute_dtype=torch.bfloat16)
        model = AutoModelForCausalLM.from_pretrained(args.modell, revision=args.revision, quantization_config=bnb,
                                                     torch_dtype=torch.bfloat16, device_map={"": 0})
        model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
        targets = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]

    check_template(tokenizer, system, train_rows[0])
    lora = LoraConfig(r=args.rang, lora_alpha=2 * args.rang, lora_dropout=0.05, target_modules=targets,
                      task_type="CAUSAL_LM")
    model = get_peft_model(model, lora)
    model.to(device)
    model.print_trainable_parameters()

    def encode_all(rows):
        out, skipped = [], 0
        for r in rows:
            e = encode(tokenizer, system, r, args.max_len)
            if e is None:
                skipped += 1
            else:
                out.append(e)
        return out, skipped

    train, skipped = encode_all(train_rows)
    evalset, eval_skipped = encode_all(eval_rows)
    lengths = sorted(len(e[0]) for e in train)
    answer_tokens = sum(e[3] for e in train)
    print(f"Lehrset: {len(train)} Einträge ({skipped} zu lang), Prüfset {len(evalset)} ({eval_skipped} zu lang); "
          f"Länge Median {lengths[len(lengths) // 2]} max {lengths[-1]} Token, Antwort-Token gesamt {answer_tokens}")
    if not train:
        print("FEHLER: kein Eintrag passt in --max-len", file=sys.stderr)
        return 1

    steps_total = max(1, math.ceil(len(train) * args.epochen / args.akkumulieren))
    warmup = max(1, steps_total // 30)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=0.0)

    def lr_at(step):
        if step < warmup:
            return args.lr * (step + 1) / warmup
        return args.lr * 0.5 * (1 + math.cos(math.pi * (step - warmup) / max(1, steps_total - warmup)))

    if args.probe is not None:
        self_test(model, train[0], device)
    first_eval = evaluate(model, evalset, device) if evalset else float("nan")
    print(f"Eval-Verlust vor dem Training: {first_eval:.4f}")
    model.train()
    step, micro, t0 = 0, 0, time.time()
    order = []
    running = 0.0
    while step < steps_total:
        if not order:
            order = list(range(len(train)))
            random.shuffle(order)
        ids, _, _, n_answer = train[order.pop()]
        loss = answer_loss(model, ids, n_answer, device) / args.akkumulieren
        loss.backward()
        running += loss.item()
        micro += 1
        if micro % args.akkumulieren == 0:
            for g in opt.param_groups:
                g["lr"] = lr_at(step)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            opt.zero_grad(set_to_none=True)
            step += 1
            print(f"Schritt {step}/{steps_total} Verlust {running:.4f} lr {lr_at(step - 1):.2e} "
                  f"{time.time() - t0:.0f} s", flush=True)
            running = 0.0
            if evalset and step % args.eval_alle == 0:
                print(f"Eval-Verlust nach Schritt {step}: {evaluate(model, evalset, device):.4f}", flush=True)

    last_eval = evaluate(model, evalset, device) if evalset else float("nan")
    print(f"Eval-Verlust nach dem Training: {last_eval:.4f} (vorher {first_eval:.4f})")
    adapter = args.ziel / "adapter"
    model.save_pretrained(adapter)
    tokenizer.save_pretrained(adapter)

    # Zusammenführen in voller Genauigkeit (nicht auf dem 4-bit-Modell): llama.cpp braucht ein normales
    # Hugging-Face-Modell als Eingang für convert_hf_to_gguf.py.
    merged_dir = args.ziel / "merged"
    if args.probe is not None:
        merged = model.merge_and_unload()
    else:
        from peft import PeftModel
        from transformers import AutoModelForCausalLM
        del model
        torch.cuda.empty_cache()
        base = AutoModelForCausalLM.from_pretrained(args.modell, revision=args.revision, torch_dtype=torch.bfloat16)
        merged = PeftModel.from_pretrained(base, adapter).merge_and_unload()
    merged.save_pretrained(merged_dir, safe_serialization=True)
    tokenizer.save_pretrained(merged_dir)
    (args.ziel / "training.json").write_text(json.dumps({
        "grundmodell": args.modell if args.probe is None else "probe (Zufallsgewichte)",
        "revision": args.revision, "epochen": args.epochen, "lr": args.lr, "rang": args.rang,
        "akkumulieren": args.akkumulieren, "einträge": len(train), "übersprungen": skipped,
        "eval_vorher": first_eval, "eval_nachher": last_eval, "schritte": steps_total,
        "sekunden": round(time.time() - t0)}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Fertig: {merged_dir}")
    if evalset and not last_eval < first_eval:
        print("WARNUNG: Eval-Verlust nicht gesunken", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
