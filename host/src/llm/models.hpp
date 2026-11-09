// Modellkatalog und Modell-Download (GGUF von Hugging Face) – übernommen aus kraken/src/llm/models.hpp.
//
// Der Katalog steht genau einmal: deps.lock.json → "models". CMake erzeugt
// daraus beim Konfigurieren generated/llm/model_catalog.inc; Größe und SHA-256
// sind damit Teil der EXE und nicht änderbar ohne neuen Bau.
//
// Download: URL über den Repo-Commit festgenagelt (nicht "main"), Umleitungen
// nur https auf huggingface.co und *.hf.co (CDN, in CaptionBridge gemessen),
// fortsetzbar (.part), Größe und SHA-256 streamend geprüft – eine abweichende
// Datei wird verworfen und nie geladen (fail-closed).
//
// Modelle liegen unter {data}\models\<Datei>, nie im Programmordner.

#pragma once

#include "win/http.hpp"

#include <cstdint>
#include <filesystem>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::llm {

struct ModelSpec {
    std::string key;       // "qwen25-7b"
    std::string title;
    std::string repo;
    std::string revision;  // Commit
    std::string file;
    std::uint64_t size = 0;
    std::string sha256;
    std::string license;
    int min_vram_mb = 0;
    // Kontext je Modell (Token). Vorher galt für alle Modelle fest 4096: Eine Sechsergruppe
    // (≈ 2 800 Prompt + 1 380 Antwort) passte nie und wurde in 3 + 3 geteilt – ein zweiter
    // Prompt-Pass je Gruppe. 7B/3B haben bei 8192 genug VRAM übrig, das 14B nicht (siehe kv_q8).
    int n_ctx = 0;
    // KV-Cache je Token in KiB bei F16: 2 (K+V) × Schichten × KV-Köpfe × Kopfbreite × 2 B.
    // 0 = unbekannt → estimate_vram_bytes rechnet mit der alten Obergrenze 200 KiB.
    int kv_kib_per_token = 0;
    // KV-Cache als Q8_0 statt F16 (verlangt Flash-Attention). Nur nach GPU-Messung je Modell an.
    // Gemessen 2026-10-04, RTX 4070 Ti, 14B, Kontext 4096 (GPU-Probe, je 3 Läufe): F16 768 MiB KV, 42,8 Tok/s,
    // 25,9 ms je Schritt; Q8_0 408 MiB, 45,2 Tok/s, 26,0 ms je Schritt – gleich schnell, 360 MiB weniger am
    // Rand der 12 GB. Deshalb 14B an, 7B/3B aus (genug Luft, kein Grund für eine Verteilungsänderung).
    bool kv_q8 = false;
    // Fester Grafikspeicher rekurrenter Schichten für alle Sequenzen der Engine (MiB), unabhängig vom Kontext.
    // Qwen3.8 ist hybrid: nur jede vierte Schicht hat einen KV-Speicher, die übrigen (DeltaNet) einen Zustand je
    // Sequenz – beim 27B ≈ 150 MiB × 12 Sequenzen. Ohne diesen Posten schätzte die VRAM-Warnung 1,8 GiB zu wenig.
    int state_mib = 0;
    // Entwurfsmodell fürs spekulative Dekodieren: nicht auswählbar, rät für passende Modelle Token voraus.
    bool draft = false;

    std::string url() const;
};

// Alle Einträge samt Entwurfsmodell (Download, Prüfung).
const std::vector<ModelSpec>& catalog();
// Nur die auswählbaren Modelle (ohne Entwurfsmodell) – für Auswahllisten und die Vorwahl.
std::vector<ModelSpec> selectable_models();
// Das Entwurfsmodell für spec, nullptr wenn es keins gibt (nur die Qwen2.5-Reihe teilt sein Vokabular).
const ModelSpec* draft_for(const ModelSpec& spec);

// Eigene GGUF-Datei außerhalb des Katalogs (aus kraken; hier nötig, um ohne 8-GB-Download zu testen). sha256 bleibt leer:
// Es gibt keinen festgenagelten Hash, die Datei verantwortet der Betreiber. model_state() zählt sie als vorhanden,
// wenn sie existiert und .gguf heißt; download_model() lehnt sie ab. Kontext 8192 (die Engine kappt auf den
// Trainingskontext), KV-Größe unbekannt → VRAM-Schätzung mit der vorsichtigen Obergrenze.
inline constexpr std::string_view kCustomModelKey = "eigene";
ModelSpec custom_model_spec(const std::filesystem::path& file);
bool is_custom(const ModelSpec& spec);
const ModelSpec* find_model(std::string_view key);
// Katalogeintrag zu einem Dateinamen (ohne Ordner), nullptr wenn unbekannt. Die Engine findet
// darüber Kontext und KV-Typ des geladenen Modells, ohne dass jeder Aufrufer den Katalog kennt.
const ModelSpec* find_model_by_file(std::string_view file_name);
// Empfohlenes Modell (deps.lock.json → models.default).
const ModelSpec& default_model();

// Modell für eine GPU mit `vram_total_bytes` Grafikspeicher (Wunsch 2026-10-28):
// das größte aus der Qwen2.5-Reihe 14B → 7B → 3B, dessen Schätzung (estimate_vram_bytes) höchstens 90 % belegt – den
// Rest braucht Windows für den Bildschirm. Nur Qwen2.5: Diese Reihe ist mit dem Baumeister gemessen; Mistral, Llama und
// das unzensierte Qwen3.8 wählt nur, wer sie ausdrücklich will. 0 Byte (unbekannt) → Standardmodell; passt nichts → 3B.
const ModelSpec& recommended_for_vram(std::uint64_t vram_total_bytes);

// Grober Grafikspeicherbedarf: Gewichte (Dateigröße) + KV-Cache (kv_kib_per_token je Kontext-Token,
// bei Q8_0 × 34/64) + 512 MB Rechenpuffer. kv_kib_per_token 0 = unbekannt → 200 KiB (Obergrenze
// Qwen2.5-14B; für das 7B 3,5× zu hoch, deshalb jetzt je Modell aus dem Katalog).
// Warum: Passt das Modell nicht in den FREIEN Speicher, lagert Windows in den geteilten
// Systemspeicher aus und die Erzeugung bricht ein – gemessen 2026-10-03: dasselbe 7B mit 72
// Token/s allein, mit 5 Token/s, als eine zweite Instanz 6,2 GB belegte.
std::uint64_t estimate_vram_bytes(std::uint64_t file_bytes, int n_ctx, int kv_kib_per_token = 0,
                                  bool kv_q8 = false);
// Dasselbe mit den Katalogwerten des Modells (Kontext spec.n_ctx, 0 → 8192).
std::uint64_t estimate_vram_bytes(const ModelSpec& spec);

// Kontext, mit dem die Engine den llama-Kontext anlegt: das Kleinste aus Wunsch (0 → 8192),
// Katalog-Obergrenze des Modells (0 = keine) und Trainingskontext laut GGUF (0 = unbekannt).
// Über n_ctx_train hinaus rechnet das Modell mit Positionen, die es nie gesehen hat.
int effective_n_ctx(int requested, int model_cap, int n_ctx_train);

// Geschätzter Grafikspeicher für Modell UND Entwurfsmodell (draft, nullptr = ohne Turbo) beim Kontext n_ctx. Der
// Entwurf läuft mit demselben Kontext wie das Hauptmodell (llama_engine.cpp) und belegt Gewichte + eigenen KV-Cache +
// 128 MiB Rechenpuffer. Vorher zählte die Warnung vor dem Laden den Entwurf gar nicht mit.
std::uint64_t estimate_load_bytes(const ModelSpec& spec, const ModelSpec* draft, int n_ctx);

// Kontext für „Automatisch“ (Einstellung 0) und ob der Turbo-Entwurf dabei bleibt:
// 1. der größte aus 32768, 28672, … 12288, den Katalog-Obergrenze und Trainingskontext (0 = unbekannt) zulassen und
//    dessen Schätzung samt Entwurf (estimate_load_bytes) höchstens 95 % des freien Grafikspeichers belegt – dieselbe
//    Grenze wie vram_warning;
// 2. passt mit Entwurf nichts über 8192, aber ohne ihn mindestens kTurboTradeCtx: dieser Kontext, Entwurf aus;
// 3. sonst 8192; mit Entwurf nur, wenn 8192 samt Entwurf passt (oder der freie Speicher unbekannt ist, 0) – sonst
//    Entwurf aus (2026-11-04: ausgelagert 145 ms je Token). Passt es auch ohne nicht, warnt vram_warning.
// Warum: Auf dem GPU-PC des Wunsch, 2026-10-29: Prompt ~5500 Token mit Bauplatz-Scan, dazu die vorige Antwort).
// Geschätzt passt dort mit
// Turbo nur 8192 (12288 bräuchte 11,6 GB samt Entwurf), ohne Turbo 16384 (11,2 GB). Ein Plan, der überhaupt
// zustande kommt, wiegt schwerer als die Beschleunigung. Wer Turbo erzwingen will, stellt den Kontext von Hand auf
// 8192. Feste große Zahlen hätten auf voller Karte still in den geteilten Systemspeicher ausgelagert (Erzeugung von
// 72 auf 5 Token/s, gemessen 2026-10-03).
inline constexpr int kTurboTradeCtx = 12288;

// Puffer für Spiel und Browser auf derselben Karte. Gemessen 2026-11-04 auf dem GPU-PC des Nutzers: Beim Start der App
// 10,7 GB frei, das 14B mit 10,4 GB geladen – trotzdem 168–179 ms je Token und nach einem Weltwechsel in Minecraft
// 1389 ms (0,7 Token/s): Minecraft belegt beim Spielen mehr, als beim Laden frei war. 1,5 GiB ist ein erster Wert,
// am GPU-PC nachzumessen (Zeit je Token im Log, „Schichten auf der GPU“ in der Modellzeile).
inline constexpr std::uint64_t kGpuReserveBytes = 1536ull * 1024 * 1024;

// Wie viele Schichten auf die GPU, wenn das Modell höchstens budget_bytes belegen darf: -1 = alle passen (oder
// unbekannt: n_layer ≤ 0 bzw. budget 0); sonst 0 … n_layer-1, der Rest rechnet auf der CPU. Teilweise auf der CPU ist
// planbar langsamer; ausgelagerter Grafikspeicher war 5- bis 50-mal langsamer (siehe kGpuReserveBytes).
// kv_bytes_total = KV-Cache des ganzen Kontexts; dazu kommen 512 MiB Rechenpuffer wie in estimate_vram_bytes.
int gpu_layers_for_budget(std::uint64_t file_bytes, int n_layer, std::uint64_t kv_bytes_total,
                          std::uint64_t budget_bytes);
struct AutoContext {
    int n_ctx = 8192;
    bool keep_draft = true;
};
AutoContext auto_context(const ModelSpec& spec, const ModelSpec* draft, std::uint64_t free_bytes, int n_ctx_train = 0);

// KV-Bytes je Token aus den Modellmaßen (Kontrollrechnung beim Laden gegen den Katalog).
std::uint64_t kv_bytes_per_token(int n_layer, int n_head_kv, int head_dim, bool q8);
// Deutscher Warnsatz, wenn need mehr als 95 % von free braucht; sonst leer.
std::string vram_warning(std::uint64_t need_bytes, std::uint64_t free_bytes);

// Pfad der Modelldatei unter models_dir.
std::filesystem::path model_path(const std::filesystem::path& models_dir, const ModelSpec& spec);

enum class ModelState { Missing, Partial, Present };
// Present heißt: Datei mit erwarteter Größe vorhanden UND Prüfmarke mit gleichem
// SHA-256 daneben. Die Marke schreibt nur ein vollständig geprüfter Download –
// so muss nicht bei jedem Start 4,7 GB gehasht werden.
ModelState model_state(const std::filesystem::path& models_dir, const ModelSpec& spec);

// Lädt (oder setzt fort) und prüft. Wirft win::DownloadError / win::HttpError.
void download_model(win::HttpTransport& http, const std::filesystem::path& models_dir, const ModelSpec& spec,
                    const win::ProgressFn& progress, win::CancelToken& cancel);

}  // namespace swbh::llm
