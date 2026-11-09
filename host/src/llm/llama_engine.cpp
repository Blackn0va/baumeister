// LlamaEngine – llama.cpp (Stand siehe deps.lock.json) auf einer GPU. Reduzierte Fassung von
// kraken/src/llm/llama_engine.cpp: ein Slot, Nachrichtenliste statt System+Frage, ohne complete_many, ohne
// Zwangs-Vorlauf und Schleifenbremse (beide in kraken für dortige Bewertungen; der Baumeister braucht sie nicht).
//
// Belege zur llama.cpp-API beziehen sich auf den Commit aus deps.lock.json (50182a53, Tag b10900), Quellen nach dem
// Konfigurieren unter <build>/_deps/llama-src.

#include "llm/engine.hpp"

#include "accel/runtime.hpp"
#include "base/fileio.hpp"
#include "base/hash.hpp"
#include "base/json.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "llm/models.hpp"

#include <ggml-backend.h>
#include <gguf.h>
#include <llama.h>
// Interne Grammatik von llama.cpp (src/llama-grammar.h, Include-Pfad in src/llm/CMakeLists.txt). Wie in kraken: Die
// Grammatik läuft NEBEN der Sampler-Kette (nur das gezogene Token wird geprüft) – in der Kette prüfte sie bei jedem
// Token alle 152 064 Kandidaten (kraken, RTX 4070 Ti: 13 statt 28 Token/s). Commit festgenagelt, deshalb stabil.
#include <llama-grammar.h>

#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <string_view>
#include <thread>
#include <vector>

namespace swbh::llm {

namespace {

std::once_flag g_backend_once;

// SWBH_LLAMA_VERBOSE=1 leitet auch die Info-Zeilen von llama.cpp ins Log (Diagnose: Schichten auf der GPU,
// Puffergrößen). Sonst nur Warnungen und wenige feste Info-Zeilen – Info ist zu laut.
bool verbose_llama() {
    wchar_t buf[8]{};
    return GetEnvironmentVariableW(L"SWBH_LLAMA_VERBOSE", buf, 8) > 0 && buf[0] == L'1';
}

// Info-Zeilen, die immer ins Protokoll gehören: Flash-Attention, Kontext, KV-Speicher (Texte belegt in kraken,
// llama-context.cpp:306-313, :551, llama-kv-cache.cpp:291/301).
bool always_info(std::string_view line) {
    static constexpr std::string_view kKeys[] = {"Flash Attention", "n_ctx ", "KV buffer size", "llama_kv_cache: size",
                                                  "matrix cores", "offloaded"};
    for (std::string_view k : kKeys) {
        if (line.find(k) != std::string_view::npos) {
            return true;
        }
    }
    return false;
}

void forward_log(ggml_log_level level, const char* text, void*) {
    static const bool verbose = verbose_llama();
    // GGML_LOG_LEVEL_CONT ist eine Fortsetzung der vorigen Zeile (Fortschrittspunkte beim Laden) – in kraken stand
    // sonst jeder Punkt als eigene Warnung im Protokoll.
    if (text == nullptr || level == GGML_LOG_LEVEL_CONT) {
        return;
    }
    if (level < GGML_LOG_LEVEL_WARN && !(verbose && level >= GGML_LOG_LEVEL_INFO) && !always_info(text)) {
        return;
    }
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
        line.pop_back();
    }
    if (!line.empty()) {
        if (level >= GGML_LOG_LEVEL_WARN) {
            log::warn("llama.cpp: " + log::safe(line, 400));
        } else {
            log::info("llama.cpp: " + log::safe(line, 400));
        }
    }
}

// Kerne laden: CPU (beste Variante) und Vulkan aus dem Ordner von ggml-base.dll (accel::Runtime). Der CUDA-Kern kommt
// vorher über die GUI dazu (nach Zustimmung). Ohne Vulkan-Treiber scheitert nur ggml-vulkan.dll – die App startet
// trotzdem, das Modell bleibt aus (fail-closed).
void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(forward_log, nullptr);
        std::string error;
        if (!accel::Runtime::get().load_cpu(error)) {
            log::error(error);
        }
        if (!accel::Runtime::get().load_vulkan(error)) {
            log::warn(error);
        }
        llama_backend_init();
    });
}

std::string reg_of(ggml_backend_dev_t d) {
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(d);
    const char* name = reg != nullptr ? ggml_backend_reg_name(reg) : nullptr;
    return name != nullptr ? name : "";
}

// Gerät mit dem meisten Speicher. backend leer = jedes Backend; allow_integrated = auch IGPU.
ggml_backend_dev_t best_gpu(const std::string& backend, bool allow_integrated) {
    ggml_backend_dev_t best = nullptr;
    std::size_t best_total = 0;
    for (ggml_backend_dev_t d : accel::registered_devices()) {  // Schnappschuss unter registry_mutex
        const auto type = ggml_backend_dev_type(d);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && !(allow_integrated && type == GGML_BACKEND_DEVICE_TYPE_IGPU)) {
            continue;
        }
        if (!backend.empty() && reg_of(d) != backend) {
            continue;
        }
        std::size_t free = 0;
        std::size_t total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        if (best == nullptr || total > best_total) {
            best = d;
            best_total = total;
        }
    }
    return best;
}

bool abort_requested(void* data) { return static_cast<std::atomic<bool>*>(data)->load(); }

// Kandidaten im ersten Grammatik-Rückfall: erst nur die 64 besten Logits prüfen (kraken llama-6: die Grammatik über
// alle 152 064 Token kostete ≈ 41 ms). Bei Temperatur > 0 bleibt top_k 40 ≤ 64.
constexpr std::size_t kFallbackTop = 64;

struct GrammarDeleter {
    void operator()(llama_grammar* g) const { llama_grammar_free_impl(g); }
};
using GrammarPtr = std::unique_ptr<llama_grammar, GrammarDeleter>;

struct SamplerDeleter {
    void operator()(llama_sampler* s) const { llama_sampler_free(s); }
};
using SamplerPtr = std::unique_ptr<llama_sampler, SamplerDeleter>;

// Batch mit expliziter Sequenz und Positionen (llama.h:960-966).
struct BatchGuard {
    llama_batch b;
    explicit BatchGuard(int32_t capacity) : b(llama_batch_init(capacity, 0, 1)) {}
    ~BatchGuard() { llama_batch_free(b); }
    BatchGuard(const BatchGuard&) = delete;
    BatchGuard& operator=(const BatchGuard&) = delete;

    void fill(const llama_token* tokens, int32_t n, llama_pos pos0) {
        b.n_tokens = n;
        for (int32_t i = 0; i < n; ++i) {
            b.token[i] = tokens[i];
            b.pos[i] = pos0 + i;
            b.n_seq_id[i] = 1;
            b.seq_id[i][0] = 0;
            b.logits[i] = i == n - 1 ? 1 : 0;  // Logits nur am letzten Token – nur dort wird gezogen
        }
    }
};

GrammarPtr make_grammar(const llama_vocab* vocab, const std::string& gbnf, std::string& error) {
    GrammarPtr g;
    try {
        // vocab = nullptr ist für Zeichen-Grammatiken zulässig (llama-grammar.h:130, so nutzen es die Tests von
        // llama.cpp) – grammar_accepts() prüft damit ohne geladenes Modell.
        g.reset(llama_grammar_init_impl(vocab, gbnf.c_str(), "root", false, nullptr, 0, nullptr, 0));
    } catch (const std::exception& e) {
        error = std::string("Grammatik ungültig: ") + e.what();
        return nullptr;
    }
    if (!g) {
        error = "Grammatik ungültig";
    }
    return g;
}

SamplerPtr make_chain(const CompletionRequest& request) {
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    SamplerPtr chain(llama_sampler_chain_init(sp));
    if (request.temperature <= 0.0f) {
        llama_sampler_chain_add(chain.get(), llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_temp(request.temperature));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_dist(request.seed));
    }
    return chain;
}

bool is_rejected(const llama_token_data& d) { return std::isinf(d.logit) && d.logit < 0; }

// Nächstes Token aus `logits` ziehen, gegen die Grammatik prüfen und übernehmen (wie kraken sample_next).
std::optional<llama_token> sample_next(llama_sampler* chain, llama_grammar* grammar, const float* logits,
                                       std::vector<llama_token_data>& candidates, std::string& error) {
    auto fill = [&] {
        for (std::size_t t = 0; t < candidates.size(); ++t) {
            candidates[t] = llama_token_data{static_cast<llama_token>(t), logits[t], 0.0f};
        }
        return llama_token_data_array{candidates.data(), candidates.size(), -1, false};
    };
    llama_token_data_array cur = fill();
    llama_sampler_apply(chain, &cur);
    if (cur.selected < 0 || static_cast<std::size_t>(cur.selected) >= cur.size) {
        error = "Sampler wählte kein Token";
        return std::nullopt;
    }
    llama_token tok = cur.data[cur.selected].id;
    if (grammar != nullptr) {
        llama_token_data single{tok, 1.0f, 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_grammar_apply_impl(*grammar, &one);
        if (is_rejected(single)) {
            cur = fill();
            const std::size_t k = std::min(kFallbackTop, cur.size);
            std::partial_sort(cur.data, cur.data + k, cur.data + cur.size,
                              [](const llama_token_data& a, const llama_token_data& b) { return a.logit > b.logit; });
            llama_token_data_array top{cur.data, k, -1, false};
            llama_grammar_apply_impl(*grammar, &top);
            if (std::any_of(top.data, top.data + top.size, [](const llama_token_data& d) { return !is_rejected(d); })) {
                llama_sampler_apply(chain, &top);
                cur = top;
            } else {
                cur = fill();
                llama_grammar_apply_impl(*grammar, &cur);
                llama_sampler_apply(chain, &cur);
            }
            if (cur.selected < 0 || static_cast<std::size_t>(cur.selected) >= cur.size ||
                is_rejected(cur.data[cur.selected])) {
                error = "Grammatik lässt kein Token zu";
                return std::nullopt;
            }
            tok = cur.data[cur.selected].id;
        }
        try {
            llama_grammar_accept_impl(*grammar, tok);
        } catch (const std::exception& e) {
            error = std::string("Grammatik: ") + e.what();
            return std::nullopt;
        }
    }
    llama_sampler_accept(chain, tok);
    return tok;
}

// Entwurfs-Token: das wahrscheinlichste, das die Grammatik zulässt (gierig – der Entwurf soll das Hauptmodell treffen,
// nicht streuen). Nur die besten 32 Logits werden gegen die Grammatik geprüft; ist keins gültig, endet der Entwurf.
std::optional<llama_token> best_draft(const float* logits, int n_vocab, llama_grammar* grammar,
                                      std::vector<llama_token_data>& buf) {
    buf.resize(static_cast<std::size_t>(n_vocab));
    for (int t = 0; t < n_vocab; ++t) {
        buf[static_cast<std::size_t>(t)] = llama_token_data{t, logits[t], 0.0f};
    }
    const std::size_t k = std::min<std::size_t>(32, buf.size());
    std::partial_sort(buf.begin(), buf.begin() + static_cast<std::ptrdiff_t>(k), buf.end(),
                      [](const llama_token_data& a, const llama_token_data& b) { return a.logit > b.logit; });
    llama_token_data_array top{buf.data(), k, -1, false};
    if (grammar != nullptr) {
        llama_grammar_apply_impl(*grammar, &top);
    }
    for (std::size_t i = 0; i < k; ++i) {
        if (!is_rejected(buf[i])) {
            return buf[i].id;
        }
    }
    return std::nullopt;
}

// Passt das Entwurfsmodell zum Hauptmodell? Wie llama.cpp common/speculative.cpp (common_speculative_are_compatible):
// gleiche Vokabelart, gleiche BOS/EOS, Größen höchstens 128 auseinander, Token ab Nummer 5 textgleich.
bool vocab_compatible(const llama_vocab* tgt, const llama_vocab* dft, std::string& why) {
    if (llama_vocab_type(tgt) != llama_vocab_type(dft)) {
        why = "andere Vokabelart";
        return false;
    }
    if (llama_vocab_get_add_bos(tgt) != llama_vocab_get_add_bos(dft) ||
        (llama_vocab_get_add_bos(tgt) && llama_vocab_bos(tgt) != llama_vocab_bos(dft)) ||
        llama_vocab_get_add_eos(tgt) != llama_vocab_get_add_eos(dft) ||
        (llama_vocab_get_add_eos(tgt) && llama_vocab_eos(tgt) != llama_vocab_eos(dft))) {
        why = "andere BOS/EOS-Token";
        return false;
    }
    const int n_tgt = llama_vocab_n_tokens(tgt);
    const int n_dft = llama_vocab_n_tokens(dft);
    if (std::abs(n_tgt - n_dft) > 128) {
        why = "Vokabular " + std::to_string(n_tgt) + " gegen " + std::to_string(n_dft) + " Token";
        return false;
    }
    for (int i = 5; i < std::min(n_tgt, n_dft); ++i) {
        if (std::strcmp(llama_vocab_get_text(tgt, i), llama_vocab_get_text(dft, i)) != 0) {
            why = "Token " + std::to_string(i) + " verschieden";
            return false;
        }
    }
    return true;
}

}  // namespace

bool grammar_accepts(const std::string& grammar, const std::string& text, std::string& error) {
    GrammarPtr g = make_grammar(nullptr, grammar, error);
    if (!g) {
        return false;
    }
    try {
        llama_grammar_accept_str(*g, text);  // wirft bei unzulässigem Text (llama-grammar.cpp:1466)
    } catch (const std::exception& e) {
        error = std::string("Text passt nicht zur Grammatik: ") + e.what();
        return false;
    }
    // Ende erlaubt = mindestens ein Stack ist leer (so prüft llama_grammar_apply_impl das Ende-Token).
    const bool can_end = std::any_of(g->stacks.begin(), g->stacks.end(),
                                     [](const llama_grammar_stack& st) { return st.empty(); });
    if (!can_end) {
        error = "Text ist nach der Grammatik unvollständig";
    }
    return can_end;
}

struct LlamaEngine::Impl {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::string name;
    std::string device;
    std::string backend;
    std::string chat_template;
    bool empty_think = false;
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    // Token, die gerade im KV-Speicher stehen (Prompt + Antwort des letzten Aufrufs). Leer = unbestimmt.
    std::vector<llama_token> cached;
    int n_ctx = 0;
    bool kv_q8 = false;
    int n_layer = 0;
    int gpu_layers = 0;
    // Entwurfsmodell (spekulatives Dekodieren), nullptr = aus. Eigener KV-Speicher, eigener Präfix.
    llama_model* draft_model = nullptr;
    llama_context* draft_ctx = nullptr;
    std::vector<llama_token> draft_cached;
    int n_draft = 8;

    ~Impl() {
        if (draft_ctx != nullptr) {
            llama_free(draft_ctx);
        }
        if (draft_model != nullptr) {
            llama_model_free(draft_model);
        }
        if (ctx != nullptr) {
            llama_free(ctx);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }

    void drop_cache() {
        if (draft_ctx != nullptr) {
            draft_cached.clear();
            llama_memory_clear(llama_get_memory(draft_ctx), true);
        }
        cached.clear();
        llama_memory_clear(llama_get_memory(ctx), true);
    }

    // Bis ein Aufruf (complete, prewarm) sauber endet, ist der Speicherinhalt unbestimmt – bei Fehler/Abbruch nichts
    // wiederverwenden. cached/draft_cached setzt der Aufrufer erst ganz am Ende, danach ok = true.
    struct FailGuard {
        Impl* impl;
        bool ok = false;
        ~FailGuard() {
            if (!ok) {
                impl->drop_cache();
            }
        }
    };

    // Token ab Position 0 in Stücken von n_batch in den KV-Speicher von c rechnen (ohne zu ziehen).
    static bool decode_all(llama_context* c, const std::vector<llama_token>& tokens) {
        const auto n = static_cast<int32_t>(tokens.size());
        const int32_t n_batch = std::max(static_cast<int32_t>(llama_n_batch(c)), 1);
        BatchGuard batch(n_batch);
        for (int32_t off = 0; off < n; off += n_batch) {
            const int32_t len = std::min(n_batch, n - off);
            batch.fill(tokens.data() + off, len, off);
            if (llama_decode(c, batch.b) != 0) {
                return false;
            }
        }
        return true;
    }

    // Nachrichten nach der Chat-Vorlage des Modells in Token; effective_max = answer_budget(…) für max_tokens. false +
    // error, wenn die Vorlage nicht passt oder nach dem Prompt kein Antwortbudget bleibt (Meldung beginnt mit
    // kPromptTooLong). add_generation_prompt = false fürs Vorwärmen: nur die Nachrichten selbst, ohne den Kopf der
    // Antwort und ohne leeren Denkblock – genau der Anfang, mit dem jeder spätere Auftrag beginnt.
    bool tokenize(const std::vector<ChatMessage>& messages, int max_tokens, std::vector<llama_token>& tokens,
                  int& effective_max, std::string& error, bool add_generation_prompt = true) const {
        const llama_vocab* vocab = llama_model_get_vocab(model);
        std::vector<std::string> contents;
        std::vector<std::string> roles;
        std::size_t total = 0;
        for (const ChatMessage& m : messages) {
            roles.push_back(m.role);
            contents.push_back(defuse_control_tokens(m.content));
            total += contents.back().size() + 16;
        }
        std::vector<llama_chat_message> chat;
        for (std::size_t i = 0; i < contents.size(); ++i) {
            chat.push_back({roles[i].c_str(), contents[i].c_str()});
        }
        std::vector<char> buf(total + 1024);
        int32_t n = llama_chat_apply_template(chat_template.c_str(), chat.data(), chat.size(), add_generation_prompt,
                                              buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<std::size_t>(n));
            n = llama_chat_apply_template(chat_template.c_str(), chat.data(), chat.size(), add_generation_prompt,
                                          buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n < 0) {
            error = "Chat-Vorlage nicht anwendbar";
            return false;
        }
        std::string prompt(buf.data(), static_cast<std::size_t>(n));
        if (empty_think && add_generation_prompt) {
            // Qwen3.x denkt sonst erst Hunderte Token vor der Antwort (kraken, Jinja enable_thinking=false).
            prompt += "<think>\n\n</think>\n\n";
        }
        const int32_t count =
            -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
        const std::optional<int> budget = answer_budget(count, max_tokens, n_ctx);
        if (!budget) {
            error = std::string(kPromptTooLong) + " (" + std::to_string(count) + " Token, Kontext " +
                    std::to_string(n_ctx) + ", für die Antwort blieben " + std::to_string(n_ctx - count) +
                    ", mindestens " + std::to_string(kMinAnswerTokens) + " nötig)";
            return false;
        }
        effective_max = *budget;
        tokens.resize(static_cast<std::size_t>(count));
        llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), count, true, true);
        return true;
    }
};

void ensure_llama_backend() { init_backend(); }

LlamaEngine::LlamaEngine() : impl_(std::make_unique<Impl>()) {}
LlamaEngine::~LlamaEngine() = default;

std::vector<GpuDevice> LlamaEngine::gpu_devices() {
    init_backend();
    std::vector<GpuDevice> out;
    for (ggml_backend_dev_t d : accel::registered_devices()) {
        const auto type = ggml_backend_dev_type(d);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
            continue;
        }
        GpuDevice g;
        g.name = ggml_backend_dev_name(d);
        g.description = ggml_backend_dev_description(d);
        g.backend = reg_of(d);
        g.integrated = type == GGML_BACKEND_DEVICE_TYPE_IGPU;
        std::size_t free = 0;
        std::size_t total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        g.memory_free = free;
        g.memory_total = total;
        out.push_back(std::move(g));
    }
    return out;
}

namespace {

// Schichtzahl aus dem GGUF-Kopf (<architektur>.block_count), ohne Gewichte zu laden. Vorher über llama mit
// vocab_only: lieferte gemessen 0 Schichten (2026-11-04, GPU-Probe mit Qwen2.5 7B) – in dem Modus liest llama die
// Modellwerte nicht. 0 = nicht lesbar.
int gguf_block_count(const std::string& path) {
    gguf_init_params ip{};
    ip.no_alloc = true;
    ip.ctx = nullptr;
    gguf_context* g = gguf_init_from_file(path.c_str(), ip);
    if (g == nullptr) {
        return 0;
    }
    int n = 0;
    const std::int64_t arch = gguf_find_key(g, "general.architecture");
    if (arch >= 0 && gguf_get_kv_type(g, arch) == GGUF_TYPE_STRING) {
        const std::string key = std::string(gguf_get_val_str(g, arch)) + ".block_count";
        const std::int64_t k = gguf_find_key(g, key.c_str());
        if (k >= 0 && gguf_get_kv_type(g, k) == GGUF_TYPE_UINT32) {
            n = static_cast<int>(gguf_get_val_u32(g, k));
        }
    }
    gguf_free(g);
    return n;
}

}  // namespace

bool LlamaEngine::load(const LoadOptions& options, std::string& error) {
    init_backend();
    impl_ = std::make_unique<Impl>();
    // Der Aufrufer bricht vor dem Laden eine laufende Erzeugung ab (cancel()). Bliebe der Merker stehen, bräche der
    // Abbruch-Rückruf jede Turbo-Messprobe unten ab (gemessen: alle Proben −1 ms, Turbo fälschlich aus).
    cancel_ = stopped_.load();
    ggml_backend_dev_t gpu = best_gpu(options.backend, options.allow_integrated);
    if (gpu == nullptr) {
        error = options.backend.empty() || options.backend == "Vulkan"
                    ? std::string("Keine dedizierte GPU gefunden (Vulkan). Der Baumeister-Host rechnet nur auf der GPU – "
                                  "Grafiktreiber prüfen.")
                    : "Kein Gerät für " + options.backend + " gefunden – Rechenweg in den Einstellungen prüfen.";
        return false;
    }
    llama_model_params mp = llama_model_default_params();
    // Vorgabe alle Schichten in den VRAM. Mit Budget: erst die Schichtzahl aus den Metadaten lesen (vocab_only lädt
    // keine Gewichte), dann nur so viele auf die GPU, wie hineinpassen – siehe kGpuReserveBytes.
    int gpu_layers = -1;
    if (options.gpu_budget_bytes > 0) {
        const int layers = gguf_block_count(u8str(options.model_file));
        if (layers > 0) {
            const ModelSpec* bspec = find_model_by_file(u8str(options.model_file.filename()));
            std::error_code ec;
            const std::uint64_t file_bytes = std::filesystem::file_size(options.model_file, ec);
            const bool q8 = options.kv_q8.has_value() ? *options.kv_q8 : (bspec != nullptr && bspec->kv_q8);
            std::uint64_t kv_per_token =
                (bspec != nullptr && bspec->kv_kib_per_token > 0 ? bspec->kv_kib_per_token : 200ull) * 1024;
            if (q8) {
                kv_per_token = kv_per_token * 34 / 64;
            }
            const int ctx = effective_n_ctx(options.n_ctx, bspec != nullptr ? bspec->n_ctx : 0, 0);
            if (!ec) {
                gpu_layers = gpu_layers_for_budget(file_bytes, layers,
                                                   static_cast<std::uint64_t>(ctx) * kv_per_token,
                                                   options.gpu_budget_bytes);
            }
        } else {
            log::warn("Schichtzahl nicht lesbar – alle Schichten auf die GPU");
        }
    }
    mp.n_gpu_layers = gpu_layers;
    impl_->devices[0] = gpu;
    impl_->devices[1] = nullptr;
    mp.devices = impl_->devices;
    mp.split_mode = LLAMA_SPLIT_MODE_NONE;
    impl_->device = std::string(ggml_backend_dev_description(gpu));
    impl_->backend = reg_of(gpu);
    const std::string path = u8str(options.model_file);
    {
        // llama zählt die ggml-Geräteliste beim Laden selbst durch – geteilt sperren (accel::registry_mutex).
        std::shared_lock<std::shared_mutex> registry(accel::registry_mutex());
        impl_->model = llama_model_load_from_file(path.c_str(), mp);
    }
    if (impl_->model == nullptr) {
        error = "Modell nicht ladbar: " + path + " (zu wenig Grafikspeicher oder Datei defekt)";
        impl_ = std::make_unique<Impl>();
        return false;
    }

    // Katalogwerte über den Dateinamen; unbekannte Datei (eigene GGUF): Wunschwert, F16.
    const ModelSpec* spec = find_model_by_file(u8str(options.model_file.filename()));
    const int cap = spec != nullptr ? spec->n_ctx : 0;
    const int n_ctx_train = llama_model_n_ctx_train(impl_->model);
    const int n_ctx = effective_n_ctx(options.n_ctx, cap, n_ctx_train);
    const bool kv_q8 = options.kv_q8.has_value() ? *options.kv_q8 : (spec != nullptr && spec->kv_q8);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<std::uint32_t>(n_ctx);
    cp.n_seq_max = 1;
    if (gpu_layers >= 0) {
        // Schichten auf der CPU: llama.cpp nimmt sonst 4 Threads (GGML_DEFAULT_N_THREADS). Gemessen 2026-11-04 auf dem
        // GPU-PC: 7 von 48 Schichten auf der CPU kosteten 61–106 ms je Token, mehr als die 41 auf der GPU (45–49 ms).
        // Erzeugen: halbe logische Kerne (≈ physische, Hyperthreading bringt bei Speicherdurchsatz nichts); Prompt:
        // alle. Ganz auf der GPU bleibt die Vorgabe – dort rechnet die CPU kaum, und das Tempo ist so gemessen.
        const int logical = static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
        cp.n_threads = std::clamp(logical / 2, 4, 32);
        cp.n_threads_batch = std::clamp(logical, 4, 64);
        log::info("CPU-Schichten: " + std::to_string(cp.n_threads) + " Threads zum Erzeugen, " +
                  std::to_string(cp.n_threads_batch) + " für den Prompt (" + std::to_string(logical) + " logische Kerne)");
    }
    if (kv_q8) {
        // Q8_0-KV halbiert den KV-Speicher. Quantisiertes V verlangt Flash-Attention (llama-context.cpp:3702-3710);
        // ausdrücklich ENABLED: kann das Gerät kein FA, scheitert der Kontext mit Meldung statt still langsamer zu
        // rechnen (fail-closed, aus kraken).
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    cp.no_perf = true;
    {
        std::shared_lock<std::shared_mutex> registry(accel::registry_mutex());
        impl_->ctx = llama_init_from_model(impl_->model, cp);
    }
    if (impl_->ctx == nullptr) {
        error = std::string("Kontext nicht anlegbar (Grafikspeicher?") +
                (kv_q8 ? ", KV Q8_0 braucht Flash-Attention" : "") + ")";
        impl_ = std::make_unique<Impl>();
        return false;
    }
    impl_->n_ctx = static_cast<int>(llama_n_ctx(impl_->ctx));
    impl_->kv_q8 = kv_q8;
    impl_->n_layer = llama_model_n_layer(impl_->model);
    impl_->gpu_layers = gpu_layers < 0 ? impl_->n_layer : gpu_layers;
    llama_set_abort_callback(impl_->ctx, abort_requested, &cancel_);
    char desc[256]{};
    llama_model_desc(impl_->model, desc, sizeof desc);
    impl_->name = desc;
    const char* tmpl = llama_model_chat_template(impl_->model, nullptr);
    impl_->chat_template = tmpl != nullptr ? tmpl : "";
    impl_->empty_think = impl_->chat_template.find("enable_thinking") != std::string::npos;
    if (impl_->chat_template.empty()) {
        error = "Modell ohne Chat-Vorlage – nicht nutzbar";
        impl_ = std::make_unique<Impl>();
        return false;
    }
    log::info("LLM geladen: " + impl_->name + " auf " + impl_->device + " (" + impl_->backend + ") – Kontext " +
              std::to_string(impl_->n_ctx) + " (Wunsch " + std::to_string(options.n_ctx) + ", Katalog " +
              std::to_string(cap) + ", Training " + std::to_string(n_ctx_train) + "), KV " + (kv_q8 ? "Q8_0" : "F16") +
              ", n_batch " + std::to_string(llama_n_batch(impl_->ctx)) + ", Schichten auf der GPU " +
              std::to_string(impl_->gpu_layers) + "/" + std::to_string(impl_->n_layer));

    // Entwurfsmodell auf demselben Gerät. Scheitert es oder passt das Vokabular nicht, läuft alles ohne – das ist nur
    // Tempo, an den Ergebnissen ändert es nichts (deshalb hier bewusst kein Abbruch).
    if (!options.draft_file.empty()) {
        const std::string dpath = u8str(options.draft_file);
        llama_model_params dmp = llama_model_default_params();
        dmp.n_gpu_layers = -1;
        dmp.devices = impl_->devices;
        dmp.split_mode = LLAMA_SPLIT_MODE_NONE;
        {
            std::shared_lock<std::shared_mutex> registry(accel::registry_mutex());
            impl_->draft_model = llama_model_load_from_file(dpath.c_str(), dmp);
        }
        std::string why;
        if (impl_->draft_model == nullptr) {
            log::warn("Entwurfsmodell nicht ladbar: " + dpath + " – ohne spekulatives Dekodieren");
        } else if (!vocab_compatible(llama_model_get_vocab(impl_->model), llama_model_get_vocab(impl_->draft_model),
                                     why)) {
            log::warn("Entwurfsmodell passt nicht zum Hauptmodell (" + why + ") – ohne spekulatives Dekodieren");
            llama_model_free(impl_->draft_model);
            impl_->draft_model = nullptr;
        } else {
            llama_context_params dcp = llama_context_default_params();
            dcp.n_ctx = static_cast<std::uint32_t>(impl_->n_ctx);
            dcp.n_seq_max = 1;
            dcp.no_perf = true;
            {
                std::shared_lock<std::shared_mutex> registry(accel::registry_mutex());
                impl_->draft_ctx = llama_init_from_model(impl_->draft_model, dcp);
            }
            if (impl_->draft_ctx == nullptr) {
                log::warn("Kontext des Entwurfsmodells nicht anlegbar – ohne spekulatives Dekodieren");
                llama_model_free(impl_->draft_model);
                impl_->draft_model = nullptr;
            } else {
                llama_set_abort_callback(impl_->draft_ctx, abort_requested, &cancel_);
                impl_->n_draft = std::clamp(options.n_draft, 1, 16);
                // Lohnt es sich auf DIESER Karte? Gemessen 2026-10-28 auf der Intel-Arc-iGPU (Qwen2.5 3B + 0.5B): 97 %
                // der Entwurfs-Token übernommen und trotzdem langsamer (9,7 statt 11,9 Token/s) – eine iGPU rechnet 9
                // Token fast 9-mal so lange wie eines. Dedizierte Karten sind durch den Speicherdurchsatz begrenzt, dort
                // kostet der Prüf-Durchgang kaum mehr als ein Token. Deshalb kurz messen und nur einschalten, wenn die
                // Vorhersage (80 % Treffer) mindestens 10 % schneller ist. Wer das Messen entfernt, macht iGPUs langsamer.
                const auto bench = [](llama_context* c, llama_token tok, int n) {
                    BatchGuard b(n);
                    std::vector<llama_token> t(static_cast<std::size_t>(n), tok);
                    for (int i = 0; i < n; ++i) {
                        b.b.token[i] = t[static_cast<std::size_t>(i)];
                        b.b.pos[i] = i;
                        b.b.n_seq_id[i] = 1;
                        b.b.seq_id[i][0] = 0;
                        b.b.logits[i] = 1;
                    }
                    b.b.n_tokens = n;
                    const auto t0 = std::chrono::steady_clock::now();
                    double s = -1.0;
                    if (llama_decode(c, b.b) == 0) {
                        llama_get_logits_ith(c, n - 1);
                        s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
                    }
                    llama_memory_clear(llama_get_memory(c), true);
                    return s;
                };
                const llama_token probe = 13;  // beliebiges gewöhnliches Token
                bench(impl_->ctx, probe, 1);   // Aufwärmen (Pipelines, Shader)
                bench(impl_->draft_ctx, probe, 1);
                bench(impl_->ctx, probe, impl_->n_draft + 1);
                double t1 = 1e9;
                double tn = 1e9;
                double td = 1e9;
                for (int i = 0; i < 3; ++i) {
                    t1 = std::min(t1, bench(impl_->ctx, probe, 1));
                    tn = std::min(tn, bench(impl_->ctx, probe, impl_->n_draft + 1));
                    td = std::min(td, bench(impl_->draft_ctx, probe, 1));
                }
                const double per_token = (impl_->n_draft * td + tn) / (0.8 * impl_->n_draft + 1.0);
                const auto ms = [](double s) {
                    char buf[32];
                    std::snprintf(buf, sizeof buf, "%.1f", s * 1000.0);
                    return std::string(buf);
                };
                const std::string numbers = "ein Token " + ms(t1) + " ms, " + std::to_string(impl_->n_draft + 1) +
                                            " Token " + ms(tn) + " ms, Entwurf " + ms(td) + " ms → erwartet " +
                                            ms(per_token) + " ms je Token";
                // SWBH_TURBO=immer erzwingt Turbo (nur Messproben auf Rechnern, wo es nicht lohnt).
                wchar_t force[16]{};
                const bool forced = GetEnvironmentVariableW(L"SWBH_TURBO", force, 16) > 0 &&
                                    std::wstring_view(force) == L"immer";
                if (!forced && (t1 <= 0.0 || tn <= 0.0 || td <= 0.0 || per_token > 0.9 * t1)) {
                    log::info("Turbo lohnt auf dieser GPU nicht (" + numbers + ") – ohne spekulatives Dekodieren");
                    llama_free(impl_->draft_ctx);
                    impl_->draft_ctx = nullptr;
                    llama_model_free(impl_->draft_model);
                    impl_->draft_model = nullptr;
                } else {
                    log::info("Spekulatives Dekodieren an (" + numbers + "): Entwurf " +
                              u8str(options.draft_file.filename()) + ", bis " + std::to_string(impl_->n_draft) +
                              " Token voraus");
                }
            }
        }
    }
    return true;
}

void LlamaEngine::unload() { impl_ = std::make_unique<Impl>(); }
bool LlamaEngine::speculative() const { return impl_ && impl_->draft_ctx != nullptr; }

int LlamaEngine::layer_count() const { return impl_ ? impl_->n_layer : 0; }

int LlamaEngine::gpu_layer_count() const { return impl_ ? impl_->gpu_layers : 0; }
bool LlamaEngine::loaded() const { return impl_ && impl_->ctx != nullptr; }
std::string LlamaEngine::model_name() const { return impl_ ? impl_->name : std::string(); }
std::string LlamaEngine::device_name() const { return impl_ ? impl_->device : std::string(); }
std::string LlamaEngine::backend_name() const { return impl_ ? impl_->backend : std::string(); }
int LlamaEngine::context_size() const { return impl_ ? impl_->n_ctx : 0; }
bool LlamaEngine::kv_quantized() const { return impl_ && impl_->kv_q8; }

std::optional<CompletionResult> LlamaEngine::complete(const CompletionRequest& request, std::string& error) {
    if (!loaded()) {
        error = "Modell nicht geladen";
        return std::nullopt;
    }
    cancel_ = stopped_.load();
    if (stopped_) {
        error = "abgebrochen (App wird beendet)";
        return std::nullopt;
    }
    const auto started = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(impl_->model);

    std::vector<llama_token> tokens;
    // Wirksames max_tokens (answer_budget): an den Kontext gekappt statt abgelehnt. Beide Schleifen unten zählen
    // NUR gegen max_tokens – wer dort wieder request.max_tokens nimmt, läuft bei gekapptem Budget über das
    // Kontextende, und llama_decode scheitert mitten in der Antwort.
    int max_tokens = 0;
    if (!impl_->tokenize(request.messages, request.max_tokens, tokens, max_tokens, error)) {
        return std::nullopt;
    }
    const auto count = static_cast<int32_t>(tokens.size());

    // Gemeinsamen Anfang mit dem letzten Aufruf wiederverwenden (System-Prompt). Mindestens ein Token wird neu
    // verarbeitet, sonst fehlen die Logits für das erste Antworttoken. API: llama_memory_seq_rm (llama.h:748); kann das
    // Modell nicht teilweise kürzen (rekurrente Architekturen), wird alles geleert.
    llama_memory_t mem = llama_get_memory(impl_->ctx);
    std::size_t reuse = std::min(common_prefix(impl_->cached, tokens), tokens.size() - 1);
    if (reuse == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(reuse), -1)) {
        impl_->drop_cache();
        reuse = 0;
    }
    // Bis der Aufruf sauber endet, ist der Speicherinhalt unbestimmt – bei Fehler/Abbruch nichts wiederverwenden.
    impl_->cached.clear();
    Impl::FailGuard fail_guard{impl_.get()};

    GrammarPtr grammar;
    if (!request.grammar.empty()) {
        grammar = make_grammar(vocab, request.grammar, error);
        if (!grammar) {
            return std::nullopt;
        }
    }
    SamplerPtr chain = make_chain(request);

    const bool spec = impl_->draft_ctx != nullptr;
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(impl_->ctx));
    BatchGuard batch(n_batch);
    // Spekulativ bleibt das letzte Prompt-Token draußen: Es geht mit den Entwurfs-Token zusammen in den ersten
    // Prüf-Durchgang (dort entstehen seine Logits).
    const int32_t prompt_end = spec ? count - 1 : count;
    for (int32_t off = static_cast<int32_t>(reuse); off < prompt_end; off += n_batch) {
        const int32_t len = std::min(n_batch, prompt_end - off);
        batch.fill(tokens.data() + off, len, off);
        if (llama_decode(impl_->ctx, batch.b) != 0) {
            error = cancel_ ? "abgebrochen" : "Prompt-Verarbeitung fehlgeschlagen";
            return std::nullopt;
        }
    }

    CompletionResult result;
    result.prompt_tokens = count;
    result.reused_tokens = static_cast<int>(reuse);
    result.clamped_to = max_tokens < request.max_tokens ? max_tokens : 0;
    std::string piece(256, '\0');
    std::vector<llama_token_data> candidates(static_cast<std::size_t>(llama_vocab_n_tokens(vocab)));
    auto emit = [&](llama_token tok) {
        int32_t len = llama_token_to_piece(vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (len < 0) {
            piece.resize(static_cast<std::size_t>(-len));
            len = llama_token_to_piece(vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (len > 0) {
            const std::string_view part(piece.data(), static_cast<std::size_t>(len));
            result.text.append(part);
            if (request.on_text) {
                request.on_text(part);
            }
        }
        ++result.generated_tokens;
    };

    if (spec) {
        // ---- Spekulatives Dekodieren -------------------------------------------------------------------------------
        // Je Runde: Entwurf rät bis n_draft Token (gierig, mit Grammatik-Kopie), das Hauptmodell rechnet
        // [letztes Token, Entwurf…] in EINEM llama_decode mit Logits an jeder Stelle. Danach zieht das Hauptmodell an
        // jeder Stelle sein Token selbst (sample_next, wie ohne Entwurf); solange es dem Entwurf gleicht, geht es weiter,
        // beim ersten Unterschied gilt sein eigenes Token und die Runde endet. So entspricht jede Ausgabe genau dem, was
        // das Hauptmodell an dieser Stelle gezogen hätte (bei Temperatur 0 bitgleich – Gegenprobe in tests/llm).
        Impl& im = *impl_;
        const llama_vocab* dvocab = llama_model_get_vocab(im.draft_model);
        const int n_vocab_draft = llama_vocab_n_tokens(dvocab);
        llama_memory_t dmem = llama_get_memory(im.draft_ctx);
        std::size_t dreuse = std::min(common_prefix(im.draft_cached, tokens), tokens.size() - 1);
        if (dreuse == 0 || !llama_memory_seq_rm(dmem, 0, static_cast<llama_pos>(dreuse), -1)) {
            llama_memory_clear(dmem, true);
            dreuse = 0;
        }
        im.draft_cached.clear();
        const int32_t d_batch = static_cast<int32_t>(llama_n_batch(im.draft_ctx));
        BatchGuard dbatch(std::max(d_batch, 1));
        auto draft_decode = [&](const llama_token* t, int32_t n, llama_pos pos0) {
            for (int32_t off = 0; off < n; off += d_batch) {
                const int32_t len = std::min(d_batch, n - off);
                dbatch.fill(t + off, len, pos0 + off);
                if (llama_decode(im.draft_ctx, dbatch.b) != 0) {
                    return false;
                }
            }
            return true;
        };
        std::vector<llama_token> kv(tokens.begin(), tokens.end() - 1);  // steht im KV des Hauptmodells
        std::vector<llama_token> dkv(tokens.begin(), tokens.begin() + static_cast<std::ptrdiff_t>(dreuse));
        result.prompt_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        llama_token last = tokens.back();
        std::vector<llama_token> drafts;
        std::vector<llama_token> step;
        std::vector<llama_token_data> dbuf;
        BatchGuard tbatch(std::max(n_batch, im.n_draft + 1));
        bool ended = false;
        while (result.generated_tokens < max_tokens) {
            if (cancel_) {
                error = "abgebrochen";
                return std::nullopt;
            }
            const auto t_round = std::chrono::steady_clock::now();
            // 1) Entwurf: KV nachziehen (Prompt bzw. zuletzt übernommene Token), dann `last` und raten.
            drafts.clear();
            bool draft_ok = true;
            if (dkv.size() < kv.size()) {
                draft_ok = draft_decode(kv.data() + dkv.size(), static_cast<int32_t>(kv.size() - dkv.size()),
                                        static_cast<llama_pos>(dkv.size()));
                dkv = kv;
            }
            const int room_ctx = im.n_ctx - static_cast<int>(kv.size()) - 2;
            const int budget = std::min({im.n_draft, max_tokens - result.generated_tokens - 1, room_ctx});
            if (draft_ok && budget > 0 && draft_decode(&last, 1, static_cast<llama_pos>(dkv.size()))) {
                dkv.push_back(last);
                GrammarPtr dg(grammar ? llama_grammar_clone_impl(*grammar) : nullptr);
                for (int j = 0; j < budget; ++j) {
                    const auto t = best_draft(llama_get_logits_ith(im.draft_ctx, -1), n_vocab_draft, dg.get(), dbuf);
                    if (!t || llama_vocab_is_eog(vocab, *t)) {
                        break;
                    }
                    if (dg) {
                        try {
                            llama_grammar_accept_impl(*dg, *t);
                        } catch (const std::exception&) {
                            break;
                        }
                    }
                    drafts.push_back(*t);
                    if (j + 1 < budget) {
                        if (!draft_decode(&*t, 1, static_cast<llama_pos>(dkv.size()))) {
                            break;
                        }
                        dkv.push_back(*t);
                    }
                }
            }
            // 2) Hauptmodell: [last, Entwurf…] in einem Durchgang, Logits an jeder Stelle.
            step.assign(1, last);
            step.insert(step.end(), drafts.begin(), drafts.end());
            tbatch.b.n_tokens = static_cast<int32_t>(step.size());
            for (std::size_t i = 0; i < step.size(); ++i) {
                tbatch.b.token[i] = step[i];
                tbatch.b.pos[i] = static_cast<llama_pos>(kv.size() + i);
                tbatch.b.n_seq_id[i] = 1;
                tbatch.b.seq_id[i][0] = 0;
                tbatch.b.logits[i] = 1;
            }
            if (llama_decode(impl_->ctx, tbatch.b) != 0) {
                error = cancel_ ? "abgebrochen" : "Erzeugung fehlgeschlagen";
                return std::nullopt;
            }
            const float* first_logits = llama_get_logits_ith(impl_->ctx, 0);  // wartet auf die GPU
            result.gpu_seconds += std::chrono::duration<double>(std::chrono::steady_clock::now() - t_round).count();
            result.drafted += static_cast<int>(drafts.size());
            // 3) Prüfen: an jeder Stelle zieht das Hauptmodell selbst.
            std::size_t acc = 0;
            bool have_next = false;
            llama_token next = 0;
            for (std::size_t i = 0; i <= drafts.size(); ++i) {
                const auto t_sample = std::chrono::steady_clock::now();
                const float* logits = i == 0 ? first_logits : llama_get_logits_ith(impl_->ctx, static_cast<int32_t>(i));
                const auto tok = sample_next(chain.get(), grammar.get(), logits, candidates, error);
                result.sample_seconds +=
                    std::chrono::duration<double>(std::chrono::steady_clock::now() - t_sample).count();
                if (!tok) {
                    return std::nullopt;
                }
                if (llama_vocab_is_eog(vocab, *tok)) {
                    ended = true;
                    break;
                }
                emit(*tok);
                const bool matches = i < drafts.size() && *tok == drafts[i];
                if (matches) {
                    ++acc;
                }
                if (result.generated_tokens >= max_tokens) {
                    break;
                }
                if (!matches) {
                    next = *tok;
                    have_next = true;
                    break;
                }
            }
            result.accepted += static_cast<int>(acc);
            // 4) KV: gültig sind `last` und die übernommenen Entwurfs-Token; alles dahinter weg (beide Modelle).
            kv.push_back(last);
            kv.insert(kv.end(), drafts.begin(), drafts.begin() + static_cast<std::ptrdiff_t>(acc));
            llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(kv.size()), -1);
            const std::size_t dvalid = common_prefix(dkv, kv);
            dkv.resize(dvalid);
            llama_memory_seq_rm(dmem, 0, static_cast<llama_pos>(dvalid), -1);
            if (ended || !have_next) {
                break;
            }
            last = next;
        }
        result.hit_limit = !ended;
        result.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        impl_->cached = std::move(kv);
        im.draft_cached = std::move(dkv);
        fail_guard.ok = true;
        return result;
    }

    result.prompt_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    std::vector<llama_token> decoded = tokens;
    bool ended = false;
    while (result.generated_tokens < max_tokens) {
        if (cancel_) {
            error = "abgebrochen";
            return std::nullopt;
        }
        // llama_decode kehrt zurück, bevor die GPU fertig ist; llama_get_logits_ith wartet auf sie. Die Zeit bis hier
        // ist GPU, die danach Token-Auswahl auf der CPU (Sampler + Grammatik) – getrennt gemessen, um zu sehen, wo die
        // Token/s verloren gehen (Wunsch 2026-10-28).
        const auto t_gpu = std::chrono::steady_clock::now();
        const float* logits = llama_get_logits_ith(impl_->ctx, -1);
        const auto t_sample = std::chrono::steady_clock::now();
        const auto tok = sample_next(chain.get(), grammar.get(), logits, candidates, error);
        const auto t_done = std::chrono::steady_clock::now();
        result.gpu_seconds += std::chrono::duration<double>(t_sample - t_gpu).count();
        result.sample_seconds += std::chrono::duration<double>(t_done - t_sample).count();
        if (!tok) {
            return std::nullopt;
        }
        if (llama_vocab_is_eog(vocab, *tok)) {
            ended = true;
            break;
        }
        emit(*tok);
        const llama_token step = *tok;
        batch.fill(&step, 1, static_cast<llama_pos>(decoded.size()));
        if (llama_decode(impl_->ctx, batch.b) != 0) {
            error = cancel_ ? "abgebrochen" : "Erzeugung fehlgeschlagen";
            return std::nullopt;
        }
        decoded.push_back(step);
    }
    result.hit_limit = !ended;
    result.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    impl_->cached = std::move(decoded);
    fail_guard.ok = true;
    return result;
}

std::optional<int> LlamaEngine::prewarm(const std::vector<ChatMessage>& prefix, std::string& error) {
    if (!loaded()) {
        error = "Modell nicht geladen";
        return std::nullopt;
    }
    // Wie complete(): ein einfacher Abbruch von vorher gilt nicht mehr, der haftende beim Beenden schon.
    cancel_ = stopped_.load();
    if (stopped_) {
        error = "abgebrochen (App wird beendet)";
        return std::nullopt;
    }
    // Ein Auftrag kam dem Vorwärmen zuvor: Sein KV-Speicher beginnt mit demselben System-Prompt und enthält mehr.
    // Ersetzen hieße, die Wiederverwendung der nächsten Reparaturrunde wegzuwerfen.
    if (!impl_->cached.empty() || prefix.empty()) {
        return 0;
    }
    std::vector<llama_token> tokens;
    int unused_budget = 0;
    // Budget kMinAnswerTokens: Ein Präfix, hinter dem keine Antwort mehr Platz hat, braucht niemand vorzuwärmen.
    if (!impl_->tokenize(prefix, kMinAnswerTokens, tokens, unused_budget, error, false)) {
        return std::nullopt;
    }
    Impl::FailGuard fail_guard{impl_.get()};
    // cached ist leer = Speicher unbestimmt (z. B. Reste der Turbo-Messprobe aus load()) – erst leeren.
    impl_->drop_cache();
    if (!Impl::decode_all(impl_->ctx, tokens)) {
        error = cancel_ ? "abgebrochen" : "Vorwärmen fehlgeschlagen (llama_decode)";
        return std::nullopt;
    }
    if (impl_->draft_ctx != nullptr && !Impl::decode_all(impl_->draft_ctx, tokens)) {
        error = cancel_ ? "abgebrochen" : "Vorwärmen des Entwurfsmodells fehlgeschlagen (llama_decode)";
        return std::nullopt;
    }
    // Erst jetzt gilt der Speicher als belegt – ein Abbruch oben ließ cached leer, und FailGuard leerte den Speicher.
    if (impl_->draft_ctx != nullptr) {
        impl_->draft_cached = tokens;
    }
    impl_->cached = std::move(tokens);
    fail_guard.ok = true;
    return static_cast<int>(impl_->cached.size());
}

namespace {

// Ein System-Prompt hat einige KB; 4 MiB fängt nur eine offensichtlich falsche Datei ab, bevor sie geparst wird.
constexpr std::uint64_t kMaxPrefixFileBytes = 4ull * 1024 * 1024;

Json prefix_messages_json(const std::vector<ChatMessage>& prefix) {
    Json msgs = Json::array();
    for (const ChatMessage& m : prefix) {
        Json one = Json::object();
        one["role"] = m.role;
        one["content"] = m.content;
        msgs.push_back(std::move(one));
    }
    return msgs;
}

}  // namespace

void save_prefix_file(const std::filesystem::path& file, const std::vector<ChatMessage>& prefix) {
    const Json msgs = prefix_messages_json(prefix);
    Json doc = Json::object();
    doc["format"] = 1;
    doc["sha256"] = sha256_hex(dump_compact(msgs));
    doc["messages"] = msgs;
    write_file_atomic(file, dump_python_indent2(doc) + "\n");
}

std::optional<std::vector<ChatMessage>> load_prefix_file(const std::filesystem::path& file, std::string& error) {
    error.clear();
    std::optional<std::string> bytes;
    try {
        bytes = read_file_if_exists(file, kMaxPrefixFileBytes);
    } catch (const FileError& e) {
        error = e.what();
        return std::nullopt;
    }
    if (!bytes) {
        return std::nullopt;  // noch kein Auftrag gelaufen – kein Fehler
    }
    const std::optional<Json> doc = try_parse_utf8(*bytes);
    if (!doc || !doc->is_object()) {
        error = "prefix.json ist kein gültiges JSON";
        return std::nullopt;
    }
    const Json* format = json_find(*doc, "format");
    const Json* hash = json_find(*doc, "sha256");
    const Json* msgs = json_find(*doc, "messages");
    if (format == nullptr || !format->is_number_integer() || format->get<long long>() != 1 || hash == nullptr ||
        !hash->is_string() || msgs == nullptr || !msgs->is_array() || msgs->empty()) {
        error = "prefix.json hat ein unbekanntes Format";
        return std::nullopt;
    }
    // Vergleich ohne konstante Laufzeit: Die Prüfsumme ist kein Geheimnis, sie erkennt nur Änderungen.
    if (sha256_hex(dump_compact(*msgs)) != hash->get<std::string>()) {
        error = "prefix.json wurde verändert oder ist beschädigt (Prüfsumme stimmt nicht)";
        return std::nullopt;
    }
    std::vector<ChatMessage> out;
    for (const Json& m : *msgs) {
        const Json* role = m.is_object() ? json_find(m, "role") : nullptr;
        const Json* content = m.is_object() ? json_find(m, "content") : nullptr;
        if (role == nullptr || !role->is_string() || role->get<std::string>() != "system" || content == nullptr ||
            !content->is_string()) {
            error = "prefix.json enthält etwas anderes als system-Nachrichten";
            return std::nullopt;
        }
        out.push_back({role->get<std::string>(), content->get<std::string>()});
    }
    return out;
}

}  // namespace swbh::llm
