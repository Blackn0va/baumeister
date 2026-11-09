#include "llm/models.hpp"

#include "base/fileio.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "win/cng.hpp"

#include <algorithm>
#include <cstdio>
#include <optional>
#include <system_error>

namespace swbh::llm {

namespace {

const std::vector<ModelSpec> kCatalog = {
#include "llm/model_catalog.inc"
};

constexpr std::string_view kDefaultKey =
#include "llm/model_default.inc"
    ;

std::filesystem::path marker_path(const std::filesystem::path& file) {
    std::filesystem::path m = file;
    m += L".verified";
    return m;
}

}  // namespace

std::string ModelSpec::url() const {
    return "https://huggingface.co/" + repo + "/resolve/" + revision + "/" + file;
}

const std::vector<ModelSpec>& catalog() { return kCatalog; }

std::vector<ModelSpec> selectable_models() {
    std::vector<ModelSpec> out;
    for (const ModelSpec& m : kCatalog) {
        if (!m.draft) {
            out.push_back(m);
        }
    }
    return out;
}

const ModelSpec* draft_for(const ModelSpec& spec) {
    // Das 0.5B teilt Vokabular und Chat-Vorlage nur mit Qwen2.5 (llama.cpp prüft beim Laden zusätzlich Token für
    // Token, siehe llama_engine.cpp). Mistral, Llama und Qwen3.8 haben andere Vokabulare – ohne Entwurf.
    if (spec.draft || spec.key.rfind("qwen25-", 0) != 0) {
        return nullptr;
    }
    for (const ModelSpec& m : kCatalog) {
        if (m.draft) {
            return &m;
        }
    }
    return nullptr;
}

ModelSpec custom_model_spec(const std::filesystem::path& file) {
    ModelSpec m;
    m.key = std::string(kCustomModelKey);
    m.title = "Eigene Datei: " + u8str(file.filename());
    m.file = u8str(file);  // absolut: model_path() hängt ihn nicht an models_dir an (operator/ ersetzt)
    std::error_code ec;
    const auto size = std::filesystem::file_size(file, ec);
    m.size = ec ? 0 : size;
    m.license = "eigene Verantwortung";
    // Obergrenze wie im Katalog; „Automatisch“ rechnet mit 200 KiB je Token (kv_kib_per_token unbekannt) und bleibt
    // damit vorsichtig.
    m.n_ctx = 32768;
    return m;
}

bool is_custom(const ModelSpec& spec) { return spec.key == kCustomModelKey; }

const ModelSpec* find_model(std::string_view key) {
    for (const ModelSpec& m : kCatalog) {
        if (m.key == key) {
            return &m;
        }
    }
    return nullptr;
}

const ModelSpec* find_model_by_file(std::string_view file_name) {
    for (const ModelSpec& m : kCatalog) {
        if (m.file == file_name) {
            return &m;
        }
    }
    return nullptr;
}

std::uint64_t estimate_vram_bytes(std::uint64_t file_bytes, int n_ctx, int kv_kib_per_token, bool kv_q8) {
    // 200 KiB nur noch als Rückfall für unbekannte Modelle: lieber eine Warnung zu viel als eine
    // Auslagerung ohne Hinweis.
    const std::uint64_t kib = kv_kib_per_token > 0 ? static_cast<std::uint64_t>(kv_kib_per_token) : 200ull;
    std::uint64_t kv_per_token = kib * 1024;
    if (kv_q8) {
        // Q8_0: 32 Werte in 34 Byte (32 × int8 + fp16-Skala) statt 64 Byte bei F16.
        kv_per_token = kv_per_token * 34 / 64;
    }
    constexpr std::uint64_t kCompute = 512ull * 1024 * 1024;
    return file_bytes + static_cast<std::uint64_t>(n_ctx > 0 ? n_ctx : 0) * kv_per_token + kCompute;
}

std::uint64_t estimate_vram_bytes(const ModelSpec& spec) {
    return estimate_vram_bytes(spec.size, effective_n_ctx(0, spec.n_ctx, 0), spec.kv_kib_per_token, spec.kv_q8) +
           static_cast<std::uint64_t>(spec.state_mib) * 1024 * 1024;
}

std::uint64_t estimate_load_bytes(const ModelSpec& spec, const ModelSpec* draft, int n_ctx) {
    std::uint64_t need = estimate_vram_bytes(spec.size, n_ctx, spec.kv_kib_per_token, spec.kv_q8) +
                         static_cast<std::uint64_t>(spec.state_mib) * 1024 * 1024;
    if (draft != nullptr) {
        const std::uint64_t kib = draft->kv_kib_per_token > 0 ? static_cast<std::uint64_t>(draft->kv_kib_per_token)
                                                              : 200ull;
        need += draft->size + static_cast<std::uint64_t>(n_ctx > 0 ? n_ctx : 0) * kib * 1024 + 128ull * 1024 * 1024;
    }
    return need;
}

namespace {

// Größter Kontext über floor (Schritte von 4096 ab top abwärts), der samt draft in 95 % von free passt; 0 = keiner.
int largest_fitting(const ModelSpec& spec, const ModelSpec* draft, std::uint64_t free_bytes, int top, int floor) {
    for (int n = top; n > floor; n -= 4096) {
        if (estimate_load_bytes(spec, draft, n) * 100 <= free_bytes * 95) {
            return n;
        }
    }
    return 0;
}

}  // namespace

AutoContext auto_context(const ModelSpec& spec, const ModelSpec* draft, std::uint64_t free_bytes, int n_ctx_train) {
    constexpr int kFloor = 8192;
    const int top = effective_n_ctx(32768, spec.n_ctx, n_ctx_train);
    AutoContext out;
    out.n_ctx = std::min(kFloor, top);
    if (free_bytes == 0) {
        return out;
    }
    if (const int with = largest_fitting(spec, draft, free_bytes, top, kFloor); with > 0) {
        out.n_ctx = with;
        return out;
    }
    if (draft != nullptr) {
        // Schritt 2: lieber Kontext als Turbo – aber nur, wenn es wirklich spürbar mehr wird.
        if (const int without = largest_fitting(spec, nullptr, free_bytes, top, kTurboTradeCtx - 1); without > 0) {
            out.n_ctx = without;
            out.keep_draft = false;
        } else if (estimate_load_bytes(spec, draft, out.n_ctx) * 100 > free_bytes * 95) {
            // Schritt 3: Passt nicht einmal 8192 samt Entwurf, lagert Windows ohnehin aus – und der Entwurf liegt dann
            // mit im ausgelagerten Speicher und rechnet je Token mehrere Schritte mehr. Gemessen 2026-11-04 auf dem
            // GPU-PC (14B, 11,1 GB nötig, 10,7 GB frei neben Minecraft): System-Prompt 1369 Token in 62 s, 145 ms je
            // Token statt sonst ~25. Wer das zurückdreht, behält Turbo genau dann, wenn es am meisten schadet.
            out.keep_draft = false;
        }
    }
    return out;
}

int gpu_layers_for_budget(std::uint64_t file_bytes, int n_layer, std::uint64_t kv_bytes_total,
                          std::uint64_t budget_bytes) {
    if (n_layer <= 0 || budget_bytes == 0) {
        return -1;
    }
    constexpr std::uint64_t kCompute = 512ull * 1024 * 1024;
    if (file_bytes + kv_bytes_total + kCompute <= budget_bytes) {
        return -1;
    }
    if (budget_bytes <= kCompute) {
        return 0;
    }
    // Gleichmäßig je Schicht: Gewichte und KV-Cache wandern mit der Schicht; Einbettung und Kopf sind darin grob
    // mitgezählt (lieber eine Schicht zu wenig auf der GPU als ausgelagert).
    const std::uint64_t per_layer = (file_bytes + kv_bytes_total) / static_cast<std::uint64_t>(n_layer) + 1;
    const std::uint64_t fit = (budget_bytes - kCompute) / per_layer;
    return static_cast<int>(std::min<std::uint64_t>(fit, static_cast<std::uint64_t>(n_layer - 1)));
}

int effective_n_ctx(int requested, int model_cap, int n_ctx_train) {
    int n = requested > 0 ? requested : 8192;
    if (model_cap > 0) {
        n = std::min(n, model_cap);
    }
    if (n_ctx_train > 0) {
        n = std::min(n, n_ctx_train);
    }
    return n;
}

std::uint64_t kv_bytes_per_token(int n_layer, int n_head_kv, int head_dim, bool q8) {
    if (n_layer <= 0 || n_head_kv <= 0 || head_dim <= 0) {
        return 0;
    }
    const std::uint64_t values = 2ull * static_cast<std::uint64_t>(n_layer) * static_cast<std::uint64_t>(n_head_kv) *
                                 static_cast<std::uint64_t>(head_dim);
    return q8 ? values * 34 / 32 : values * 2;
}

std::string vram_warning(std::uint64_t need_bytes, std::uint64_t free_bytes) {
    if (free_bytes == 0 || need_bytes * 100 <= free_bytes * 95) {
        return {};
    }
    char buf[256];
    std::snprintf(buf, sizeof buf,
                  "Modell braucht ca. %.1f GB Grafikspeicher, frei sind %.1f GB – Windows lagert aus und die "
                  "Pläne werden sehr langsam. Spiele/Browser schließen oder ein kleineres Modell wählen.",
                  static_cast<double>(need_bytes) / 1e9, static_cast<double>(free_bytes) / 1e9);
    return buf;
}

const ModelSpec& recommended_for_vram(std::uint64_t vram_total_bytes) {
    if (vram_total_bytes == 0) {
        return default_model();
    }
    static constexpr std::string_view kLadder[] = {"qwen25-14b", "qwen25-7b", "qwen25-3b"};
    const ModelSpec* last = nullptr;
    for (std::string_view key : kLadder) {
        const ModelSpec* m = find_model(key);
        if (m == nullptr) {
            continue;
        }
        last = m;
        if (estimate_vram_bytes(*m) * 10 <= vram_total_bytes * 9) {
            return *m;
        }
    }
    return last != nullptr ? *last : default_model();
}

const ModelSpec& default_model() {
    const ModelSpec* m = find_model(kDefaultKey);
    // CMake bricht ab, wenn models.default kein Katalogeintrag ist – hier also nie nullptr.
    return m != nullptr ? *m : kCatalog.front();
}

std::filesystem::path model_path(const std::filesystem::path& models_dir, const ModelSpec& spec) {
    return models_dir / u8path(spec.file);
}

ModelState model_state(const std::filesystem::path& models_dir, const ModelSpec& spec) {
    const std::filesystem::path file = model_path(models_dir, spec);
    std::error_code ec;
    if (is_custom(spec)) {
        // Nur absolute Pfade auf eine vorhandene .gguf – ein relativer Pfad landete sonst still im Modellordner.
        const bool ok = file.is_absolute() && file.extension() == L".gguf" &&
                        std::filesystem::is_regular_file(file, ec) && spec.size > 0;
        return ok ? ModelState::Present : ModelState::Missing;
    }
    const auto size = std::filesystem::file_size(file, ec);
    if (!ec && size == spec.size) {
        const auto marker = read_file_if_exists(marker_path(file), 256);
        if (marker && *marker == spec.sha256 + "\n") {
            return ModelState::Present;
        }
    }
    std::filesystem::path part = file;
    part += L".part";
    if (std::filesystem::exists(part, ec) || std::filesystem::exists(file, ec)) {
        return ModelState::Partial;
    }
    return ModelState::Missing;
}

void download_model(win::HttpTransport& http, const std::filesystem::path& models_dir, const ModelSpec& spec,
                    const win::ProgressFn& progress, win::CancelToken& cancel) {
    if (is_custom(spec) || spec.repo.empty() || spec.sha256.size() != 64) {
        // Fail-closed: ohne festgenagelten Hash wird nie etwas heruntergeladen.
        throw win::DownloadError(win::DownloadError::Kind::BadRequest, "Eigene Modelldatei kann nicht heruntergeladen werden – Datei selbst bereitstellen");
    }
    ensure_directory(models_dir);
    const std::filesystem::path file = model_path(models_dir, spec);
    std::error_code ec;
    // Vorhandene Datei behalten (Wunsch 2026-10-28): Liegt sie in
    // voller Größe da, aber ohne Prüfmarke – vom anderen PC kopiert, Marke verloren –, wird sie hier geprüft statt neu
    // geladen. Vorher lud die App dann 9 GB neu herunter. Stimmt der SHA-256 nicht, geht es mit dem Download weiter
    // (der Transport ersetzt die Datei erst nach erfolgreicher Prüfung der neuen; fail-closed bleibt).
    if (const auto size = std::filesystem::file_size(file, ec); !ec && size == spec.size) {
        log::info("Modell vorhanden ohne Prüfmarke – prüfe SHA-256 von " + spec.file + " (" +
                  std::to_string(spec.size / (1024 * 1024)) + " MB) …");
        const std::optional<std::string> actual = win::sha256_file_hex(file);
        if (actual && *actual == spec.sha256) {
            write_file_atomic(marker_path(file), spec.sha256 + "\n");
            log::info("Vorhandenes Modell geprüft und übernommen, kein Download: " + spec.file);
            if (progress) {
                progress(spec.size, spec.size);
            }
            return;
        }
        log::warn("Vorhandene Datei " + spec.file + " hat einen anderen SHA-256 – wird neu geladen");
    }
    std::filesystem::remove(marker_path(file), ec);  // alte Marke gilt nicht für einen neuen Lauf

    win::DownloadRequest req;
    req.url = spec.url();
    req.target = file;
    req.expected_size = spec.size;
    req.expected_sha256 = spec.sha256;
    req.user_agent = "StreamWizard-Baumeister";
    req.redirects = win::Redirects::HttpsAllowList;
    req.redirect_host_suffixes = {"huggingface.co", "*.hf.co"};
    req.resume = true;
    req.check_linked_etag = true;
    req.finalize = true;
    log::info("Modell-Download: " + spec.key + " (" + std::to_string(spec.size / (1024 * 1024)) + " MB) von " +
              spec.repo + "@" + spec.revision.substr(0, 8));
    const win::DownloadResult r = http.download(req, progress, cancel);
    // Größe und SHA-256 hat der Transport streamend geprüft (sonst DownloadError).
    write_file_atomic(marker_path(r.file), spec.sha256 + "\n");
    log::info("Modell geprüft: " + spec.file + " SHA-256 " + spec.sha256.substr(0, 12) + "…");
}

}  // namespace swbh::llm
