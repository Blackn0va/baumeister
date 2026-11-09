# Drittkomponenten – StreamWizard Baumeister-Host

Die App selbst steht unter GPL-3.0-or-later (`LICENSE.txt` im Programmordner, Quelle: `LICENSE` im Repo).

Die Lizenztexte liegen im Programmordner unter `licenses\` (Quelle: `licenses/`, Prüfsummen in
`licenses/licenses.json`, beim Bau geprüft).

| Komponente | Stand | Lizenz | Enthalten in |
|---|---|---|---|
| llama.cpp mit ggml | b10900 (Commit `50182a53`) | MIT | `BaumeisterHost.exe` (llama statisch), `ggml*.dll` |
| whisper.cpp | v1.9.4 (Commit `927cfce3`) | MIT | `BaumeisterHost.exe` (statisch, nutzt die `ggml*.dll` oben) |
| Piper | 2023.11.14-2 | MIT (Piper), GPL-3.0-or-later (espeak-ng, mitgeliefert), MIT (ONNX Runtime) | nicht im Setup – lädt die App bei Wahl einer Piper-Stimme; läuft als eigener Prozess, nicht gelinkt |
| Piper-Stimmen (rhasspy/piper-voices) | Revision `c10ece1a` | je Datensatz (Thorsten, Kerstin: CC0-1.0; Ramona, Eva K.: M-AILABS) | nicht im Setup |
| Whisper Large v3 Turbo q5_0 (ggml) | Hub-Revision `5359861c` | MIT | nicht im Setup – lädt die App beim Einschalten von „Sprechen“ |
| Qt | 6.11.1 (obs-deps 2026-08-26) | LGPL-3.0 | `Qt6Core.dll`, `Qt6Gui.dll`, `Qt6Widgets.dll`, `platforms\qwindows.dll` |
| nlohmann/json | 3.11.3 | MIT | `BaumeisterHost.exe` |
| Vulkan-SDK (Header, SPIR-V) | 1.4.357.0 | Apache-2.0 / MIT | `ggml-vulkan.dll` |
| Inno Setup | 6.7.3 | Inno-Setup-Lizenz | Setup und Deinstallation |
| doctest | 2.4.11 | MIT | nur Tests, nicht ausgeliefert |

**Nicht im Setup, auf Wunsch nachgeladen:**

| Komponente | Stand | Lizenz | Wann |
|---|---|---|---|
| ggml-cuda + NVIDIA cuBLAS/CUDA-Laufzeit | b10900 · CUDA 13.3 | MIT (ggml), NVIDIA CUDA EULA | nur nach Zustimmung im Dialog „CUDA aktivieren“ |
| Qwen2.5 14B (Q3_K_M, Q4_K_M), 7B, Coder 14B/7B/1.5B, 0.5B (Turbo-Entwurf), Mistral Nemo 12B, Qwen3.8 9B (GGUF) | Commit laut `deps.lock.json` | Apache-2.0 | beim ersten Start bzw. bei Auswahl |
| Qwen2.5 3B Instruct, Qwen2.5 Coder 3B (GGUF) | Commit laut `deps.lock.json` | Qwen-Research (nicht kommerziell) | nur bei Auswahl |
| Llama 3.1 8B, Llama 3.2 3B Instruct (GGUF) | Commit laut `deps.lock.json` | Llama 3.1/3.2 Community License | nur bei Auswahl |
| Phi-3.5 mini Instruct (GGUF) | Commit laut `deps.lock.json` | MIT | nur bei Auswahl |

## Qt (LGPL-3.0) – Hinweis zum Neu-Verlinken

Qt wird unverändert als DLL ausgeliefert. Wer Qt ersetzen möchte, kann die DLLs im Programmordner durch eine
ABI-kompatible eigene Qt-6.11-Fassung ersetzen; der Quelltext von Qt ist unter <https://download.qt.io/> erhältlich,
die genaue Fassung nennt `licenses\Qt\qtbase-6.11.1.spdx`.
