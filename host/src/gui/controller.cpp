#include "gui/controller.hpp"

#include "accel/runtime.hpp"
#include "base/clock.hpp"
#include "base/fileio.hpp"
#include "base/log.hpp"
#include "base/version.hpp"
#include "components/catalog.hpp"
#include "components/store.hpp"
#include "llm/models.hpp"
#include "server/result_api.hpp"
#include "voice/recognizer.hpp"
#include "voice/tones.hpp"
#include "win/secret_store.hpp"

#include <chrono>
#include <ctime>
#include <cstdio>
#include <exception>
#include <optional>

namespace swbh::gui {

namespace {

constexpr std::size_t kMaxConsoleEntries = 20000;
constexpr const char* kCudaId = "cuda";

std::string fixed(double v, int digits) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.*f", digits, v);
    std::string out(buf);
    for (char& c : out) {
        if (c == '.') {
            c = ',';
        }
    }
    return out;
}

std::string gb(std::uint64_t bytes) { return fixed(static_cast<double>(bytes) / 1e9, 1) + " GB"; }

// Die Fernverbindung meldet dieselben Zustände wie der Tunnel – Karte, Satz und Meldungen bleiben eine Sprache.
tunnel::State link_state(relay::State s) {
    switch (s) {
    case relay::State::Starting: return tunnel::State::Starting;
    case relay::State::Connected: return tunnel::State::Connected;
    case relay::State::Waiting: return tunnel::State::Waiting;
    case relay::State::Failed: return tunnel::State::Failed;
    case relay::State::Stopped: break;
    }
    return tunnel::State::Stopped;
}

std::string app_id() { return "BaumeisterHost/" + std::string(kAppVersion); }

}  // namespace

Controller::Controller(AppPaths paths, AppConfig config, QObject* parent)
    : QObject(parent),
      paths_(std::move(paths)),
      config_(std::move(config)),
      api_(static_cast<server::CompletionBackend&>(*this), static_cast<server::ApiObserver*>(this)),
      server_([this](const server::HttpRequest& r, const std::function<bool()>& gone) { return route(r, gone); },
              [this](const std::string& line) { server_log("Server", line); }),
      local_server_([this](const server::HttpRequest& r,
                           const std::function<bool()>& gone) { return route(r, gone); },
                    [this](const std::string& line) { server_log("Server (lokal)", line); }),
      tunnel_([this](const tunnel::Tunnel::Event& e) {
          {
              std::lock_guard<std::mutex> lock(mutex_);
              snap_.tunnel = e.state;
              snap_.tunnel_text = e.text;
          }
          console(e.state == tunnel::State::Failed ? ConsoleKind::Error : ConsoleKind::Tunnel, "Tunnel: " + e.text);
      }),
      relay_(
          [this](const relay::RelayLink::Event& e) {
              {
                  std::lock_guard<std::mutex> lock(mutex_);
                  snap_.tunnel = link_state(e.state);
                  snap_.tunnel_text = e.text;
              }
              // Ein falscher Server-Pin (alarm) ist sicherheitsrelevant: als Fehler, damit ihn auch das verborgene
              // Fenster als Sprechblase meldet (main_window.cpp hidden_error_area).
              console(e.state == relay::State::Failed || e.alarm ? ConsoleKind::Error : ConsoleKind::Tunnel,
                      "Fernverbindung: " + e.text);
          },
          relay_options()) {
    // Geräte einmal im Hintergrund erfassen: init_backend lädt die ggml-Kerne (Vulkan-Treiber, ~1 s).
    load_thread_ = std::thread([this] {
        try {
            const auto gpus = llm::LlamaEngine::gpu_devices();
            std::lock_guard<std::mutex> lock(mutex_);
            snap_.gpus = gpus;
        } catch (const std::exception& e) {
            console(ConsoleKind::Error, std::string("GPU-Erkennung: ") + e.what());
            return;
        }
        const Snapshot s = snapshot();
        if (s.gpus.empty()) {
            console(ConsoleKind::Warn, "Keine GPU gefunden (Vulkan). Grafiktreiber prüfen.");
        }
        for (const llm::GpuDevice& g : s.gpus) {
            console(ConsoleKind::Info, "GPU: " + g.description + " (" + g.backend + ", " + gb(g.memory_total) +
                                           (g.integrated ? ", integriert" : "") + ")");
        }
        // Sprechen erst nach der Geräteerfassung: Beide laden ggml-Kerne, nacheinander statt gleichzeitig.
        apply_speech();
        apply_voice();
    });
}

Controller::~Controller() { shutdown(); }

AppConfig Controller::config() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return config_;
}

void Controller::set_config(const AppConfig& config) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        config_ = config;
    }
    try {
        save_config(paths_.config_file, config);
    } catch (const std::exception& e) {
        console(ConsoleKind::Error, e.what());
    }
    apply_speech();
    apply_voice();
}

void Controller::console(ConsoleKind kind, std::string text) {
    if (kind == ConsoleKind::Warn) {
        log::warn(text);
    } else if (kind == ConsoleKind::Error) {
        log::error(text);
    } else if (kind != ConsoleKind::Token) {
        log::info(text);
    }
    std::lock_guard<std::mutex> lock(mutex_);
    // Token-Stücke an den letzten Token-Eintrag hängen statt je Token einen Eintrag (30–90 je Sekunde).
    if (kind == ConsoleKind::Token && !console_.empty() && console_.back().kind == ConsoleKind::Token) {
        console_.back().text += text;
        return;
    }
    console_.push_back({kind, std::move(text)});
    while (console_.size() > kMaxConsoleEntries) {
        console_.pop_front();
        ++console_dropped_;
    }
}

std::vector<ConsoleEntry> Controller::drain_console() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<ConsoleEntry> out(std::make_move_iterator(console_.begin()), std::make_move_iterator(console_.end()));
    console_.clear();
    return out;
}

PlanRecord Controller::last_plan() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return jobs_.last();
}

std::uint64_t Controller::last_plan_seq() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return jobs_.seq();
}

Snapshot Controller::snapshot() const {
    Snapshot s;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        s = snap_;
        s.tunnel_enabled = config_.tunnel_enabled;
        s.relay_enabled = config_.relay_enabled;
    }
    s.server_running = server_.running();
    s.server_port = server_.port();
    s.local_running = local_server_.running();
    s.local_port = local_server_.port();
    s.busy = api_.busy();
    s.cuda_state = cuda_status_text();
    return s;
}

// --- Modell -----------------------------------------------------------------------------------------------------

bool Controller::model_file_present() const {
    const AppConfig cfg = config();
    return model_present(effective_model_key(cfg), cfg.custom_model);
}

bool Controller::model_present(const std::string& key, const std::string& custom_path) const {
    if (key == llm::kCustomModelKey) {
        return llm::model_state(paths_.models_dir, llm::custom_model_spec(u8path(custom_path))) ==
               llm::ModelState::Present;
    }
    const llm::ModelSpec* spec = llm::find_model(key);
    return spec != nullptr && llm::model_state(paths_.models_dir, *spec) == llm::ModelState::Present;
}

bool Controller::select_model(const std::string& key, const std::string& custom_path) {
    if (model_busy()) {
        console(ConsoleKind::Warn, "Modellwechsel erst, wenn Laden oder Download fertig sind.");
        return false;
    }
    AppConfig cfg = config();
    cfg.model_key = key;
    if (key == llm::kCustomModelKey) {
        cfg.custom_model = custom_path;
    }
    set_config(cfg);
    const llm::ModelSpec* spec = llm::find_model(key);
    console(ConsoleKind::Info, "Modell gewählt: " + (spec != nullptr ? spec->title : "eigene Datei " + custom_path));
    if (loaded_) {
        unload_model();
    }
    ensure_model();
    return true;
}

void Controller::ensure_model() {
    if (loaded_ || loading_) {
        return;
    }
    if (AppConfig cfg = config(); cfg.model_key.empty()) {
        // Noch nie gewählt: passend zur Karte vorwählen (llm::recommended_for_vram) und merken, damit der Nutzer in der
        // Auswahl sieht, was läuft. Vorher galt fest das 14B – auf einer 8-GB-Karte lud die App 8,4 GB herunter und
        // lagerte dann aus.
        std::uint64_t vram = 0;
        for (const llm::GpuDevice& g : llm::LlamaEngine::gpu_devices()) {
            if ((cfg.allow_integrated || !g.integrated) && g.memory_total > vram) {
                vram = g.memory_total;
            }
        }
        const llm::ModelSpec& pick = llm::recommended_for_vram(vram);
        cfg.model_key = pick.key;
        set_config(cfg);
        console(ConsoleKind::Info, "Modell automatisch gewählt: " + pick.title + " (GPU " + gb(vram) +
                                       ") – ändern über die Auswahl in der Modell-Karte.");
    }
    const AppConfig cfg = config();
    const llm::ModelSpec* spec = llm::find_model(effective_model_key(cfg));
    const llm::ModelSpec* draft = spec != nullptr && cfg.speculative ? llm::draft_for(*spec) : nullptr;
    const bool draft_missing =
        draft != nullptr && llm::model_state(paths_.models_dir, *draft) != llm::ModelState::Present;
    if (spec != nullptr && (!model_file_present() || draft_missing)) {
        // Hauptmodell oder Entwurfsmodell fehlt → herunterladen und danach von selbst laden (fortsetzbar).
        load_after_download_ = true;
        download_model();
    } else {
        load_model();
    }
}

std::pair<std::string, bool> Controller::prepare_backend() {
    const AppConfig cfg = config();
    const components::Component* cuda = components::builtin_catalog().find(kCudaId);
    components::Store store(paths_.components_dir);
    if (!components_cleaned_) {
        // Vor dem ersten Laden: bereitgelegte Fassungen einsetzen – danach sind die Dateien gesperrt (aus kraken).
        components_cleaned_ = true;
        for (const std::string& line : store.cleanup(components::builtin_catalog())) {
            console(ConsoleKind::Info, line);
        }
    }
    if (cfg.compute == "vulkan" || cuda == nullptr) {
        return {"Vulkan", cfg.allow_integrated};
    }
    const components::Status st = store.status(*cuda);
    if (st.state != components::State::Installed || !st.terms_accepted) {
        if (cfg.compute == "cuda") {
            console(ConsoleKind::Warn, "Rechenweg CUDA gewählt, aber nicht installiert – es rechnet Vulkan.");
        }
        return {"Vulkan", cfg.allow_integrated};
    }
    std::string error;
    if (!accel::Runtime::get().load_component(*cuda, store.dir(*cuda), error)) {
        console(ConsoleKind::Warn, (error.empty() ? std::string("CUDA nicht ladbar") : error) + " – es rechnet Vulkan.");
        return {"Vulkan", cfg.allow_integrated};
    }
    return {"CUDA", false};
}

void Controller::join_worker(std::thread& t) {
    if (t.joinable()) {
        t.join();
    }
}

void Controller::load_model() {
    if (loading_.exchange(true)) {
        return;
    }
    // Der vorige Lade-Thread kann noch vorwärmen (prewarm_prefix) – abbrechen, statt den GUI-Thread im join so lange
    // warten zu lassen. Das neue Laden bricht die Engine ohnehin ab (engine_.cancel() vor engine_.load()).
    engine_.cancel();
    join_worker(load_thread_);
    loaded_ = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.model = ModelPhase::Loading;
    }
    load_thread_ = std::thread([this] {
        const AppConfig cfg = config();
        const std::string key = effective_model_key(cfg);
        llm::ModelSpec spec;
        std::string error;
        bool ok = false;
        try {
            if (key == llm::kCustomModelKey) {
                spec = llm::custom_model_spec(u8path(cfg.custom_model));
                if (llm::model_state(paths_.models_dir, spec) != llm::ModelState::Present) {
                    throw std::runtime_error("Eigene Modelldatei fehlt oder ist keine .gguf: " + cfg.custom_model);
                }
                console(ConsoleKind::Warn, "Eigene Modelldatei ohne festgelegte Prüfsumme: " + cfg.custom_model);
            } else {
                const llm::ModelSpec* s = llm::find_model(key);
                if (s == nullptr) {
                    throw std::runtime_error("Unbekanntes Modell: " + key);
                }
                spec = *s;
                if (llm::model_state(paths_.models_dir, spec) != llm::ModelState::Present) {
                    throw std::runtime_error(spec.title + " ist nicht heruntergeladen – „Modell herunterladen“ (" +
                                             gb(spec.size) + ").");
                }
            }
            const auto [backend, allow_integrated] = prepare_backend();
            // Erst das alte Modell freigeben, DANN den freien Grafikspeicher messen: Beim Neuladen (Einstellungen
            // geändert) läge es sonst noch im Speicher, und „Automatisch“ sähe nur den Rest frei – fiele also auf 8192
            // zurück. load() verwirft es ohnehin als Erstes (impl_ neu), am Ergebnis ändert das nichts.
            engine_.cancel();
            {
                std::lock_guard<std::mutex> lock(engine_mutex_);
                engine_.unload();
            }
            // VRAM-Schätzung gegen den freien Speicher des Geräts, das die Engine wählen wird (größter Speicher).
            const llm::GpuDevice* best = nullptr;
            const auto gpus = llm::LlamaEngine::gpu_devices();
            for (const llm::GpuDevice& g : gpus) {
                if (g.backend == backend && (allow_integrated || !g.integrated) &&
                    (best == nullptr || g.memory_total > best->memory_total)) {
                    best = &g;
                }
            }
            const llm::ModelSpec* draft = llm::draft_for(spec);
            if (!cfg.speculative || draft == nullptr ||
                llm::model_state(paths_.models_dir, *draft) != llm::ModelState::Present) {
                draft = nullptr;
            }
            const std::uint64_t free_vram = best != nullptr ? best->memory_free : 0;
            // Ein Budget für alles: Kontext/Turbo (auto_context) und Schichten (Engine) rechnen beide mit „frei minus
            // Puffer“. Vorher sah auto_context den ganzen freien Speicher und hätte den Puffer für Spiel und Browser
            // mit Kontext oder Entwurf belegt – die Engine hätte dann wieder Schichten auf die CPU legen müssen.
            const std::uint64_t budget = free_vram == 0                         ? 0
                                         : free_vram > llm::kGpuReserveBytes ? free_vram - llm::kGpuReserveBytes
                                                                               : 1;
            int n_ctx = cfg.n_ctx;
            bool turbo_traded = false;
            if (n_ctx == 0) {
                const llm::AutoContext a = llm::auto_context(spec, draft, budget);
                n_ctx = a.n_ctx;
                if (!a.keep_draft) {
                    draft = nullptr;
                    turbo_traded = true;
                }
            }
            // Mit Entwurf: vorher zählte die Schätzung nur das Hauptmodell.
            const std::uint64_t need = llm::estimate_load_bytes(spec, draft, std::min(n_ctx, spec.n_ctx));
            if (cfg.n_ctx == 0) {
                console(ConsoleKind::Info,
                        "Kontext automatisch: " + std::to_string(n_ctx) + " Token (frei " + gb(free_vram) +
                            ", davon " + gb(llm::kGpuReserveBytes) + " Puffer für Spiel/Browser, geschätzt " + gb(need) +
                            (draft != nullptr ? " samt Turbo-Entwurf)" : ")") +
                            (!turbo_traded ? ""
                             : n_ctx > 8192 ? " – Turbo aus: mit Entwurf passten nur 8192 Token. Turbo erzwingen: "
                                              "Kontext in den Einstellungen auf 8192."
                                            : " – Turbo aus: selbst mit 8192 Token passt der Entwurf nicht in den "
                                              "freien Grafikspeicher, ausgelagert wäre alles langsamer."));
            }
            // Kein „Windows lagert aus“ mehr: Passt es nicht samt Puffer, legt die Engine nur einen Teil der Schichten
            // auf die GPU (Meldung nach dem Laden).
            console(ConsoleKind::Info, "Lade " + spec.title + " auf " + backend + " (geschätzt " + gb(need) +
                                           " Grafikspeicher) …");
            llm::LoadOptions opts;
            opts.model_file = llm::model_path(paths_.models_dir, spec);
            opts.n_ctx = n_ctx;
            opts.backend = backend;
            opts.allow_integrated = allow_integrated;
            if (draft != nullptr) {
                opts.draft_file = llm::model_path(paths_.models_dir, *draft);
            }
            opts.gpu_budget_bytes = budget;
            engine_.cancel();
            std::lock_guard<std::mutex> lock(engine_mutex_);
            ok = engine_.load(opts, error);
            std::lock_guard<std::mutex> slock(mutex_);
            snap_.gpus = gpus;
            if (ok) {
                snap_.model_title = spec.title;
                snap_.model_detail = engine_.model_name() + " · Kontext " + std::to_string(engine_.context_size()) +
                                     " · KV " + (engine_.kv_quantized() ? "Q8_0" : "F16") +
                                     (engine_.speculative() ? " · Turbo (Entwurf 0.5B)" : "") +
                                     (engine_.gpu_layer_count() < engine_.layer_count()
                                          ? " · " + std::to_string(engine_.gpu_layer_count()) + "/" +
                                                std::to_string(engine_.layer_count()) + " Schichten auf der GPU"
                                          : "");
                snap_.device = engine_.device_name();
                snap_.backend = engine_.backend_name();
                for (const llm::GpuDevice& g : gpus) {
                    if (g.description == snap_.device && g.backend == snap_.backend) {
                        snap_.vram_total = g.memory_total;
                        snap_.vram_free = g.memory_free;
                    }
                }
            }
        } catch (const std::exception& e) {
            error = e.what();
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            snap_.model = ok ? ModelPhase::Loaded : ModelPhase::Failed;
            if (!ok) {
                snap_.model_detail = error;
            }
        }
        loaded_ = ok;
        loading_ = false;
        if (ok) {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                jobs_.new_model(snap_.model_title);
                snap_.stats = jobs_.stats();
                snap_.model_hint = model_size_hint_de(snap_.model_detail);
            }
            const Snapshot s = snapshot();
            console(ConsoleKind::Info, "Modell bereit: " + s.model_detail + " auf " + s.device + " (" + s.backend + ")");
            if (const int gl = engine_.gpu_layer_count(), nl = engine_.layer_count(); gl < nl) {
                console(ConsoleKind::Warn,
                        "Grafikspeicher knapp (Puffer " + gb(llm::kGpuReserveBytes) + " für Spiel/Browser): " +
                            std::to_string(gl) + " von " + std::to_string(nl) +
                            " Schichten auf der GPU, der Rest rechnet auf der CPU – langsamer, aber ohne Auslagern. "
                            "Schneller: Spiel und Browser erst nach dem Laden des Modells starten.");
            }
            if (!s.model_hint.empty()) {
                console(ConsoleKind::Warn, s.model_hint);
            }
            // Erst NACH „bereit“: Der Server nimmt ab hier Aufträge an. Kommt einer vor dem Vorwärmen an den
            // engine_mutex_, gewinnt er, und prewarm() tut danach nichts (sein KV-Speicher ist mindestens so gut).
            // Vorher, mit loading_ noch true, hätte das Vorwärmen jeden Auftrag mit 503 abgewiesen.
            prewarm_prefix();
        } else {
            console(ConsoleKind::Error, "Modell nicht geladen: " + error);
        }
    });
}

std::filesystem::path Controller::prefix_file() const { return paths_.data_dir / L"prefix.json"; }

void Controller::prewarm_prefix() {
    std::string error;
    const std::optional<std::vector<llm::ChatMessage>> prefix = llm::load_prefix_file(prefix_file(), error);
    if (!prefix) {
        if (!error.empty()) {
            console(ConsoleKind::Warn, "Vorwärmen übersprungen: " + error);
        }
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        prefix_saved_ = *prefix;
    }
    const auto started = std::chrono::steady_clock::now();
    std::optional<int> warmed;
    {
        std::lock_guard<std::mutex> lock(engine_mutex_);
        if (!loaded_) {
            return;  // inzwischen entladen – nichts zu wärmen
        }
        warmed = engine_.prewarm(*prefix, error);
    }
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    // Nur Warnung: Ohne Vorwärmen rechnet der erste Auftrag den System-Prompt selbst – langsamer, aber richtig.
    if (!warmed) {
        console(ConsoleKind::Warn, "Vorwärmen fehlgeschlagen: " + error + " – der erste Auftrag rechnet den "
                                                                          "System-Prompt selbst.");
    } else if (*warmed > 0) {
        if (seconds > 0.0) {
            std::lock_guard<std::mutex> lock(mutex_);
            jobs_.set_warm_prompt_tps(*warmed / seconds);
            snap_.stats = jobs_.stats();
        }
        console(ConsoleKind::Info, "System-Prompt vorgewärmt: " + std::to_string(*warmed) + " Token in " +
                                       fixed(seconds, 1) + " s – der erste Auftrag übernimmt sie aus dem Speicher.");
    }
}

void Controller::remember_prefix(const std::vector<llm::ChatMessage>& messages) {
    std::vector<llm::ChatMessage> prefix = llm::leading_system_messages(messages);
    if (prefix.empty()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (prefix == prefix_saved_) {
            return;
        }
    }
    // Nur der Server-Thread schreibt (ein Auftrag zur Zeit) – kein zweiter Schreiber auf dieselbe Datei.
    try {
        llm::save_prefix_file(prefix_file(), prefix);
    } catch (const std::exception& e) {
        console(ConsoleKind::Warn, std::string("prefix.json nicht speicherbar (Vorwärmen beim nächsten Laden fällt "
                                               "aus): ") + e.what());
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    prefix_saved_ = std::move(prefix);
}

void Controller::unload_model() {
    if (loading_) {
        return;
    }
    loaded_ = false;
    engine_.cancel();
    {
        std::lock_guard<std::mutex> lock(engine_mutex_);
        engine_.unload();
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.model = ModelPhase::None;
        snap_.model_detail.clear();
    }
    console(ConsoleKind::Info, "Modell entladen.");
}

void Controller::download_model() {
    const AppConfig cfg = config();
    const llm::ModelSpec* spec = llm::find_model(effective_model_key(cfg));
    if (spec == nullptr) {
        console(ConsoleKind::Warn, "Eigene Modelldatei kann nicht heruntergeladen werden – Datei selbst bereitstellen.");
        return;
    }
    // Hauptmodell und – bei spekulativem Dekodieren – das kleine Entwurfsmodell, jeweils nur was noch fehlt.
    std::vector<llm::ModelSpec> todo;
    if (llm::model_state(paths_.models_dir, *spec) != llm::ModelState::Present) {
        todo.push_back(*spec);
    }
    if (const llm::ModelSpec* d = llm::draft_for(*spec);
        cfg.speculative && d != nullptr && llm::model_state(paths_.models_dir, *d) != llm::ModelState::Present) {
        todo.push_back(*d);
    }
    if (todo.empty()) {
        if (load_after_download_.exchange(false)) {
            load_model();
        }
        return;
    }
    if (download_busy_.exchange(true)) {
        return;
    }
    join_worker(download_thread_);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.downloading = true;
        snap_.download_title = todo.front().title;
        snap_.download_done = 0;
        snap_.download_total = todo.front().size;
    }
    download_thread_ = std::thread([this, todo] {
        win::CancelToken cancel;
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            download_cancel_ = &cancel;
        }
        try {
            auto http = win::make_winhttp_transport();
            for (const llm::ModelSpec& copy : todo) {
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    snap_.download_title = copy.title;
                    snap_.download_done = 0;
                    snap_.download_total = copy.size;
                }
                console(ConsoleKind::Info, "Download " + copy.title + " (" + gb(copy.size) + ") von huggingface.co …");
                llm::download_model(*http, paths_.models_dir, copy,
                                    [this](std::uint64_t done, std::uint64_t total) {
                                        std::lock_guard<std::mutex> lock(mutex_);
                                        snap_.download_done = done;
                                        snap_.download_total = total;
                                    },
                                    cancel);
                console(ConsoleKind::Info, "Download fertig und geprüft (SHA-256): " + copy.file);
            }
            if (load_after_download_.exchange(false)) {
                load_model();
            }
        } catch (const win::HttpError& e) {
            console(e.kind == win::HttpError::Kind::Cancelled ? ConsoleKind::Info : ConsoleKind::Error,
                    e.kind == win::HttpError::Kind::Cancelled ? "Download abgebrochen (wird beim nächsten Mal fortgesetzt)."
                                                             : std::string("Download fehlgeschlagen: ") + e.what());
        } catch (const std::exception& e) {
            console(ConsoleKind::Error, std::string("Download fehlgeschlagen: ") + e.what());
        }
        load_after_download_ = false;  // gescheitert/abgebrochen: kein späteres Laden „von selbst“
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            download_cancel_ = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            snap_.downloading = false;
        }
        download_busy_ = false;
    });
}

void Controller::cancel_download() {
    std::lock_guard<std::mutex> lock(cancel_mutex_);
    if (download_cancel_ != nullptr) {
        download_cancel_->cancel();
    }
}

// --- CUDA -------------------------------------------------------------------------------------------------------

std::string Controller::cuda_status_text() const {
    const components::Component* c = components::builtin_catalog().find(kCudaId);
    if (c == nullptr) {
        return "nicht im Katalog";
    }
    if (accel::Runtime::get().loaded(accel::Backend::Cuda)) {
        return "geladen";
    }
    const components::Status st = components::Store(paths_.components_dir).status(*c);
    switch (st.state) {
    case components::State::NotInstalled: return "nicht installiert";
    case components::State::Installed: return st.terms_accepted ? "installiert" : "installiert, Zustimmung fehlt";
    case components::State::Outdated: return "veraltet";
    case components::State::Damaged: return "beschädigt (" + st.detail + ")";
    case components::State::PendingRestart: return "wird beim Neustart eingesetzt";
    case components::State::RemovalPending: return "wird beim Neustart entfernt";
    }
    return "unbekannt";
}

bool Controller::cuda_installed() const {
    const components::Component* c = components::builtin_catalog().find(kCudaId);
    return c != nullptr && components::Store(paths_.components_dir).status(*c).state == components::State::Installed;
}

void Controller::install_cuda(bool terms_accepted) {
    const components::Component* c = components::builtin_catalog().find(kCudaId);
    if (c == nullptr || cuda_busy_.exchange(true)) {
        return;
    }
    join_worker(cuda_thread_);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.downloading = true;
        snap_.download_title = c->title;
        snap_.download_done = 0;
        snap_.download_total = c->download_size();
    }
    const bool in_use = accel::Runtime::get().component_in_use(kCudaId);
    cuda_thread_ = std::thread([this, c, terms_accepted, in_use] {
        win::CancelToken cancel;
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            download_cancel_ = &cancel;
        }
        console(ConsoleKind::Info, "Lade " + c->title + " (" + gb(c->download_size()) + ") von github.com/ggml-org …");
        try {
            auto http = win::make_winhttp_transport();
            components::Store store(paths_.components_dir);
            const components::ChangeResult r = store.install(
                *http, *c, terms_accepted, in_use,
                [this](std::uint64_t done, std::uint64_t total) {
                    std::lock_guard<std::mutex> lock(mutex_);
                    snap_.download_done = done;
                    snap_.download_total = total;
                },
                cancel);
            console(ConsoleKind::Info, r.message + (r.needs_restart ? "" : " Modell neu laden, damit es auf CUDA rechnet."));
        } catch (const win::HttpError& e) {
            console(e.kind == win::HttpError::Kind::Cancelled ? ConsoleKind::Info : ConsoleKind::Error,
                    e.kind == win::HttpError::Kind::Cancelled ? std::string("CUDA-Download abgebrochen.")
                                                             : std::string("CUDA-Download fehlgeschlagen: ") + e.what());
        } catch (const std::exception& e) {
            console(ConsoleKind::Error, std::string("CUDA-Installation fehlgeschlagen: ") + e.what());
        }
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            download_cancel_ = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            snap_.downloading = false;
        }
        cuda_busy_ = false;
    });
}

// --- Server und Tunnel ------------------------------------------------------------------------------------------

server::ApiResponse Controller::route(const server::HttpRequest& r, const std::function<bool()>& gone) {
    if (server::is_city_path(r.target)) {
        return server::handle_city(r, city_);
    }
    if (server::is_module_path(r.target)) {
        // Nur Anzeige (Seite „Module“): kein Konsoleneintrag je Meldung – die Serverzeile „→ 204“ steht ohnehin da.
        return server::handle_modules(r, server_modules_, static_cast<std::int64_t>(std::time(nullptr)), local_hms());
    }
    if (server::is_companion_path(r.target)) {
        // Verdrahtung in gui/companion_wiring – dort samt Weiterleitung des Begleiternamens getestet (T-007).
        return server::handle_companion(
            r, heard_, gone, companion_handlers(static_cast<CompanionHooks&>(*this), speech_, name_log_));
    }
    if (server::is_research_path(r.target)) {
        return server::handle_research(
            r, [this] { return config().research; },
            [this](const server::ResearchNote& n) {
                const std::string line = server::research_line_de(n);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    snap_.last_research = line;
                }
                console(ConsoleKind::Info, "Recherche (Internet): " + line +
                                               (n.order.empty() ? std::string() : " – für „" + n.order + "“"));
            });
    }
    if (server::is_result_path(r.target)) {
        return server::handle_result(r, [this](const server::ResultNote& n) { show_result(n); });
    }
    return api_.handle(r, gone);
}

void Controller::show_result(const server::ResultNote& n) {
    const std::string line = server::result_line_de(n);
    const bool failed = server::result_is_failure(n);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.last_result = line;
        snap_.last_result_failed = failed;
        jobs_.note_result(n.order, line, failed, static_cast<std::int64_t>(std::time(nullptr)), n.fixes, n.rounds);
    }
    console(failed ? ConsoleKind::Warn : ConsoleKind::Info,
            "Im Spiel: " + line + (n.order.empty() ? std::string() : " – „" + n.order + "“"));
    if (!n.fixes.empty()) {
        console(ConsoleKind::Info, "Selbst nachgebessert (ohne LLM): " + n.fixes);
    }
}

void Controller::show_demo_result(const server::ResultNote& note) { show_result(note); }

void Controller::show_demo_modules() {
    // Beispiel nach dem Vertrag v012 (interface.modul_json), keine Meldung eines echten Servers.
    const std::string_view demo =
        R"({"version":1,"modules":[)"
        R"({"id":"bau-ki","name":"Bau-KI","description":"Bauaufträge im Chat über die KI dieser App","on":true,)"
        R"("state":"aktiv","reason":"","needs":["ki"]},)"
        R"({"id":"stadtleben","name":"Stadtleben","description":"KI-Bewohner in gebauten Städten","on":true,)"
        R"("state":"wartet","reason":"KI nicht erreichbar","needs":["ki"]},)"
        R"({"id":"weltbau","name":"Weltbau","description":"Erdwelt Kachel für Kachel bauen","on":false,)"
        R"("state":"aus","reason":"ausgeschaltet","needs":[]},)"
        R"({"id":"karte","name":"Karte","description":"Kartenbilder für die Mod","on":true,"state":"fehler",)"
        R"("reason":"Welt fehlt","needs":[]}]})";
    if (const std::string code = server_modules_.apply(demo, static_cast<std::int64_t>(std::time(nullptr)), local_hms());
        !code.empty()) {
        console(ConsoleKind::Error, "Beispiel-Module abgelehnt: " + code);
    }
}

void Controller::companion_said(const server::Spoken& s) {
    const AppConfig cfg = config();
    console(ConsoleKind::Info, "Begleiter zu " + s.player + ": " + s.text);
    if (!cfg.speech_out) {
        return;
    }
    if (cfg.minecraft_name.empty()) {
        if (!name_hint_shown_.exchange(true)) {
            console(ConsoleKind::Warn, "Vorlesen: In den Einstellungen unter „Begleiter“ deinen Minecraft-Namen "
                                       "eintragen – vorgelesen wird nur, was dein Begleiter zu dir sagt.");
        }
        return;
    }
    if (!server::speak_for(cfg.minecraft_name, s.player)) {
        return;
    }
    // Hier nur noch lokal (das Plugin nimmt diesen Weg, wenn kein Simple Voice Chat da ist). Erzeugen dauert mit Piper
    // 0,1–0,4 s – nicht im Verbindungs-Thread des Servers warten lassen.
    std::lock_guard<std::mutex> lock(say_mutex_);
    if (say_thread_.joinable()) {
        say_thread_.join();  // voriger Satz ist in 0,1–0,4 s erzeugt; PlaySound ersetzt ihn beim Abspielen ohnehin
    }
    say_thread_ = std::thread([this, text = s.text] {
        std::string error;
        std::string used;
        const auto pcm = voice_.synth(text, error, &used);
        if (!pcm) {
            console(ConsoleKind::Warn, "Vorlesen nicht möglich: " + error);
            return;
        }
        voice::play_pcm(*pcm);
    });
}

void Controller::apply_voice() {
    const AppConfig cfg = config();
    voice::VoiceChoice choice = voice::parse_voice_choice(cfg.tts_voice);
    choice.pitch = voice::voice_style_pitch(cfg.voice_style);
    voice::VoicePaths paths;
    paths.root = paths_.data_dir / L"voice";
    voice_.configure(paths, choice);
    if (!cfg.speech_out || !choice.piper || !voice_.needs_download() || voice_download_busy_.exchange(true)) {
        return;
    }
    join_worker(voice_download_thread_);
    const voice::VoiceSpec spec = voice::voice_or_default(choice.key);
    voice_download_thread_ = std::thread([this, paths, spec] {
        win::CancelToken cancel;
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            voice_cancel_ = &cancel;
        }
        try {
            console(ConsoleKind::Info, "Download natürliche Stimme: Piper (MIT; espeak-ng GPL-3.0, läuft als eigener "
                                       "Prozess) und " + spec.title + " (" + spec.license + ") …");
            auto http = win::make_winhttp_transport();
            int last_tenth = -1;
            voice::download_piper(*http, paths, spec,
                                  [this, &last_tenth](std::uint64_t done, std::uint64_t total) {
                                      const int tenth = total > 0 ? static_cast<int>(done * 10 / total) : 0;
                                      if (tenth != last_tenth) {
                                          last_tenth = tenth;
                                          console(ConsoleKind::Info, "Stimme: " + std::to_string(tenth * 10) + " %");
                                      }
                                  },
                                  cancel);
            console(ConsoleKind::Info, "Stimme bereit (SHA-256 geprüft): " + spec.title);
        } catch (const std::exception& e) {
            console(ConsoleKind::Error, std::string("Download der Stimme fehlgeschlagen – bis dahin spricht die "
                                                    "Windows-Stimme: ") + e.what());
        }
        {
            std::lock_guard<std::mutex> lock(cancel_mutex_);
            voice_cancel_ = nullptr;
        }
        voice_download_busy_ = false;
    });
}

std::optional<std::string> Controller::hear_from_game(const std::string& player, std::vector<float> pcm,
                                                      std::string& error) {
    // Eigener Spieler immer, Mitspieler nur mit „Andere Spieler verstehen“ (server::may_hear). Vorher nur der eigene
    // – wer neben Vera stand und sie ansprach, bekam keine Antwort.
    const AppConfig cfg = config();
    if (!server::may_hear(cfg.minecraft_name, player, cfg.hear_others)) {
        error = "fremder Spieler, Verstehen anderer ist aus";
        return std::nullopt;
    }
    const auto seconds = static_cast<double>(pcm.size()) / voice::kMicRate;
    auto text = speech_.recognize(std::move(pcm), error);
    if (text && !text->empty()) {
        console(ConsoleKind::Info, "Gehört im Spiel (" + std::to_string(static_cast<int>(seconds * 10) / 10) + "," +
                                       std::to_string(static_cast<int>(seconds * 10) % 10) + " s): " + *text);
    }
    return text;
}

std::optional<std::vector<std::int16_t>> Controller::voice_for_game(const std::string& text, std::string& error) {
    std::string used;
    const auto pcm = voice_.synth(text, error, &used);
    if (!pcm) {
        return std::nullopt;
    }
    return voice::for_voicechat(*pcm);
}

void Controller::apply_speech() {
    std::lock_guard<std::mutex> apply(speech_apply_mutex_);
    const AppConfig cfg = config();
    const std::string sig = cfg.speech_in ? cfg.minecraft_name + '|' + cfg.talk_key + '|' + cfg.microphone + '|' +
                                                cfg.talk_mode + '|' + std::to_string(cfg.mic_gain_db) + '|' +
                                                (cfg.auto_gain ? "a" : "-") + (cfg.speech_tones ? "t" : "-")
                                          : std::string();
    if (sig == speech_sig_ && (sig.empty() || speech_.running())) {
        return;
    }
    speech_.stop();
    speech_sig_.clear();
    if (!cfg.speech_in) {
        return;
    }
    if (!server::valid_player_name(cfg.minecraft_name)) {
        console(ConsoleKind::Warn, "Sprechen: In den Einstellungen unter „Begleiter“ deinen Minecraft-Namen eintragen – "
                                   "das Plugin muss wissen, wer spricht.");
        return;
    }
    const std::optional<voice::TalkKey> key = voice::parse_talk_key(cfg.talk_key);
    if (!key) {
        console(ConsoleKind::Warn, "Sprechen: Taste „" + cfg.talk_key + "“ unbekannt – in den Einstellungen wählen.");
        return;
    }
    const llm::ModelSpec spec = voice::whisper_model_spec();
    if (llm::model_state(paths_.models_dir, spec) != llm::ModelState::Present) {
        if (speech_download_busy_.exchange(true)) {
            return;
        }
        join_worker(speech_download_thread_);
        speech_download_thread_ = std::thread([this, spec] {
            win::CancelToken cancel;
            {
                std::lock_guard<std::mutex> lock(cancel_mutex_);
                speech_cancel_ = &cancel;
            }
            bool ok = false;
            try {
                console(ConsoleKind::Info, "Download " + spec.title + " von huggingface.co …");
                auto http = win::make_winhttp_transport();
                int last_tenth = -1;
                llm::download_model(*http, paths_.models_dir, spec,
                                    [this, &last_tenth](std::uint64_t done, std::uint64_t total) {
                                        const int tenth = total > 0 ? static_cast<int>(done * 10 / total) : 0;
                                        if (tenth != last_tenth) {
                                            last_tenth = tenth;
                                            console(ConsoleKind::Info, "Spracherkennung: " +
                                                                           std::to_string(tenth * 10) + " %");
                                        }
                                    },
                                    cancel);
                console(ConsoleKind::Info, "Download fertig und geprüft (SHA-256): " + spec.file);
                ok = true;
            } catch (const std::exception& e) {
                console(ConsoleKind::Error, std::string("Download der Spracherkennung fehlgeschlagen: ") + e.what());
            }
            {
                std::lock_guard<std::mutex> lock(cancel_mutex_);
                speech_cancel_ = nullptr;
            }
            speech_download_busy_ = false;
            if (ok) {
                apply_speech();
            }
        });
        return;
    }
    voice::SpeechInput::Options o;
    o.model = llm::model_path(paths_.models_dir, spec);
    o.gpu = true;
    o.gpu_device = 0;
    o.key = *key;
    o.microphone = cfg.microphone;
    // Kein Vortext mehr von hier: Vorher stand an dieser Stelle fest recognizer_prompt("Vera"), ein anderer
    // begleiter.name wurde schlechter erkannt (bis 2026-11-20). Jetzt baut SpeechInput den Vortext aus dem Namen, den
    // gui/companion_wiring beim Abholen ablegt – dort ist er getestet (test_voice, „Begleitername …“; die Weiterleitung
    // in test_window_wiring).
    o.mode = voice::parse_talk_mode(cfg.talk_mode);
    o.gain_db = cfg.mic_gain_db;
    o.auto_gain = cfg.auto_gain;
    o.tones = cfg.speech_tones;
    speech_.start(
        std::move(o), [this](std::string text) { companion_heard(std::move(text)); },
        [this](bool error, std::string text) { console(error ? ConsoleKind::Warn : ConsoleKind::Info, text); });
    speech_sig_ = sig;
}

bool Controller::test_microphone() {
    if (!speech_.test_microphone(4)) {
        console(ConsoleKind::Warn, "Mikrofontest geht nur, wenn „Mit dem Begleiter sprechen“ eingeschaltet und bereit "
                                   "ist (und gerade keine Aufnahme läuft).");
        return false;
    }
    console(ConsoleKind::Info, "Mikrofontest: 4 Sekunden sprechen – danach Wiedergabe und Erkennung.");
    return true;
}

void Controller::game_voice(const std::vector<std::string>& names) {
    // Das Plugin holt alle ≤ 20 s ab; 60 s Frist überbrückt das sicher. Verstummt es (Server weg), wird die Taste
    // danach von selbst wieder frei.
    const bool active = server::own_player_in_game(config().minecraft_name, names);
    speech_.pause_until(active ? std::chrono::steady_clock::now() + std::chrono::seconds(60)
                               : std::chrono::steady_clock::time_point{});
    if (active != game_voice_.exchange(active)) {
        console(ConsoleKind::Info, active ? "Du sprichst im Spiel über Simple Voice Chat – die Sprechtaste der App ruht."
                                          : "Simple Voice Chat aus – die Sprechtaste der App ist wieder aktiv.");
    }
}

void Controller::companion_name_line(const std::string& line) {
    // Ablegen beim Sprechen und Drosseln: gui/companion_wiring (forward_companion_name).
    console(ConsoleKind::Info, line);
}

void Controller::companion_heard(std::string text) {
    const AppConfig cfg = config();
    if (!server::valid_player_name(cfg.minecraft_name)) {
        console(ConsoleKind::Warn, "Gesprochen, aber kein gültiger Minecraft-Name in den Einstellungen – verworfen.");
        return;
    }
    console(ConsoleKind::Info, "Gehört: " + text);
    heard_.push(server::Spoken{cfg.minecraft_name, std::move(text)});
}

std::deque<HistoryEntry> Controller::history() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return jobs_.history();
}

std::uint64_t Controller::history_rev() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return jobs_.history_rev();
}

void Controller::server_log(const char* who, const std::string& line) {
    // Die Stadtansicht meldet sich bis zu einmal je Sekunde – angenommene Meldungen fluteten sonst die Konsole.
    // Abgelehnte (4xx) bleiben sichtbar: Dann passen Plugin und App nicht zusammen.
    if (line.find(server::kCityPath) != std::string::npos && line.find("→ 200") != std::string::npos) {
        return;
    }
    // Die Schalter-Abfrage vor jedem Auftrag ist Routine; die Meldung selbst erscheint als eigene Konsolenzeile.
    if (line.find(server::kResearchPath) != std::string::npos && line.find("→ 200") != std::string::npos) {
        return;
    }
    // Ebenso die Ergebnis-Meldung: Sie erscheint als „Im Spiel: …“.
    if (line.find(server::kResultPath) != std::string::npos && line.find("→ 200") != std::string::npos) {
        return;
    }
    // Begleiter: Der Long-Poll kommt alle 20 s, Sätze stehen als „Begleiter zu …“ / „Gehört: …“ schon in der Konsole.
    if (line.find("/baumeister/v1/begleiter/") != std::string::npos && line.find("→ 200") != std::string::npos) {
        return;
    }
    console(ConsoleKind::Info, std::string(who) + ": " + line);
}

bool Controller::start_server() {
    const AppConfig cfg = config();
    if (!server_.running()) {
        std::string error;
        if (!server_.start(static_cast<std::uint16_t>(cfg.server_port), error)) {
            console(ConsoleKind::Error, "Server nicht gestartet: " + error);
            return false;
        }
        console(ConsoleKind::Info, "Server läuft: http://127.0.0.1:" + std::to_string(cfg.server_port) +
                                       " (nur dieser PC, /health, /v1/chat/completions, /baumeister/v1/stadt)");
    }
    start_local_server();
    return true;
}

void Controller::start_local_server() {
    const AppConfig cfg = config();
    if (!cfg.local_server || local_server_.running()) {
        return;
    }
    if (cfg.remote_port == cfg.server_port) {
        // Derselbe Port: Der Hauptlauscher bedient einen lokalen Server ohnehin.
        console(ConsoleKind::Info, "Lokaler Server: Plugin auf diesem PC erreicht die App schon über Port " +
                                       std::to_string(cfg.server_port) + ".");
        return;
    }
    std::string error;
    if (!local_server_.start(static_cast<std::uint16_t>(cfg.remote_port), error)) {
        // Kein Abbruch: der Server über den Tunnel geht weiter. Meist läuft eine zweite App oder ein anderes Programm
        // belegt den Port.
        console(ConsoleKind::Warn, "Lokaler Server: Port " + std::to_string(cfg.remote_port) +
                                       " nicht nutzbar (" + error +
                                       ") – ein Minecraft-Server auf diesem PC erreicht die App so nicht. Der Tunnel "
                                       "zum Server ist davon nicht betroffen.");
        return;
    }
    console(ConsoleKind::Info, "Lokaler Server: Ein Minecraft-Server auf diesem PC erreicht die App unter "
                               "http://127.0.0.1:" + std::to_string(cfg.remote_port) +
                                   " (Plugin-Vorgabe llm.url, ohne Tunnel).");
}

void Controller::stop_server() {
    if (!server_.running() && !local_server_.running()) {
        return;
    }
    engine_.cancel();  // eine laufende Erzeugung hielte stop() sonst bis zu ihrem Ende auf
    server_.stop();
    local_server_.stop();
    console(ConsoleKind::Info, "Server gestoppt.");
}

bool Controller::start_tunnel() {
    const AppConfig cfg = config();
    tunnel::TunnelConfig tc;
    tc.plink = u8path(effective_plink(cfg));
    tc.target = effective_target(cfg);
    if (tc.target.empty()) {
        // Kein Fehler: Ohne eingetragenen Server bedient die App nur einen Minecraft-Server auf diesem PC.
        console(ConsoleKind::Info, "Tunnel aus: noch kein Minecraft-Server eingetragen (Einstellungen → Server). Ein "
                                   "Server auf diesem PC erreicht die App trotzdem über 127.0.0.1:" +
                                       std::to_string(cfg.remote_port) + ".");
        return false;
    }
    tc.ssh_port = cfg.ssh_port;
    tc.remote_port = cfg.remote_port;
    tc.local_port = cfg.server_port;
    tc.hostkey = cfg.hostkey;
    if (cfg.tunnel_auth == "passwort") {
        const std::optional<std::string> pw = tunnel_password();
        if (!pw) {
            console(ConsoleKind::Error, "Tunnel nicht gestartet: Anmeldung per Passwort gewählt, aber kein Passwort "
                                        "gespeichert (Einstellungen → Server und Tunnel).");
            return false;
        }
        tc.password = *pw;
        tc.secret_dir = paths_.data_dir / L"tmp";
    }
    std::string error;
    if (!tunnel_.start(tc, error)) {
        console(ConsoleKind::Error, "Tunnel nicht gestartet: " + error);
        return false;
    }
    return true;
}

void Controller::stop_tunnel() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ++snap_.tunnel_generation;
    }
    tunnel_.stop();
}

bool Controller::start_link() {
    const AppConfig cfg = config();
    if (cfg.relay_enabled) {
        return start_relay();
    }
    if (cfg.tunnel_enabled) {
        return start_tunnel();
    }
    return false;  // Server auf diesem PC: keine Verbindung nötig
}

void Controller::stop_link() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ++snap_.tunnel_generation;
    }
    tunnel_.stop();
    relay_.stop();
}

bool Controller::start_relay() {
    const AppConfig cfg = config();
    relay::LinkConfig lc;
    lc.url = cfg.relay_url;
    lc.server_pins = cfg.relay_pins;
    lc.local_port = cfg.server_port;
    lc.app_name = app_id();
    if (lc.server_pins.empty()) {
        // Kein Fehler, aber auch keine Verbindung: Ohne Pin vertraut die App keinem Server (fail-closed).
        console(ConsoleKind::Info, "Fernverbindung aus: noch kein Fingerabdruck des Servers eingetragen (Einstellungen "
                                   "→ Server). Ohne ihn verbindet sich die App nicht.");
        return false;
    }
    std::string error;
    if (!relay_.start(lc, error)) {
        console(ConsoleKind::Error, "Fernverbindung nicht gestartet: " + error);
        return false;
    }
    return true;
}

void Controller::stop_relay() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ++snap_.tunnel_generation;
    }
    relay_.stop();
}

relay::RelayLink::Options Controller::relay_options() {
    relay::RelayLink::Options o;
    o.connect = [this](const relay::LinkConfig& lc, win::WsConnectError& e,
                       win::CancelToken& cancel) -> std::unique_ptr<win::WsConnection> {
        std::string error;
        const std::shared_ptr<const win::ClientCert> cert = ensure_client_cert(error);
        if (!cert) {
            e.kind = win::WsConnectError::Kind::Config;
            e.text = "Eigener Schlüssel nicht anlegbar – " + error;
            return nullptr;
        }
        win::PinnedWsOptions w;
        w.url = lc.url;
        w.server_pins = lc.server_pins;
        w.client_cert = cert->context();
        w.protocol = std::string(relay::kProtocol);
        w.user_agent = app_id();
        return win::connect_pinned_ws(w, e, &cancel);
    };
    return o;
}

std::shared_ptr<const win::ClientCert> Controller::ensure_client_cert(std::string& error) {
    std::lock_guard<std::mutex> lock(cert_mutex_);
    const std::string pin = config().relay_client_pin;
    if (client_cert_ && client_cert_->pin() == pin) {
        return client_cert_;
    }
    if (!pin.empty()) {
        if (std::optional<win::ClientCert> found = win::find_client_cert(pin)) {
            client_cert_ = std::make_shared<const win::ClientCert>(std::move(*found));
            return client_cert_;
        }
        // Anderes Windows-Konto, Profil neu, Zertifikat gelöscht: Ein neuer Schlüssel hat einen neuen Pin, den das Relay
        // noch nicht kennt – es lehnt ab, bis er dort eingetragen ist (fail-closed, nichts wird still übernommen).
        console(ConsoleKind::Warn, "Fernverbindung: eigener Schlüssel (" + pin.substr(0, 19) + "…) ist nicht mehr "
                                   "greifbar – lege einen neuen an. Dessen Fingerabdruck muss auf dem Server neu "
                                   "eingetragen werden.");
    }
    std::string note;
    try {
        client_cert_ = std::make_shared<const win::ClientCert>(win::create_client_cert({}, &note));
    } catch (const win::ClientCertError& e) {
        error = e.what();
        return nullptr;
    }
    AppConfig cfg;
    {
        std::lock_guard<std::mutex> cfg_lock(mutex_);
        config_.relay_client_pin = client_cert_->pin();
        cfg = config_;
    }
    try {
        save_config(paths_.config_file, cfg);
    } catch (const std::exception& e) {
        console(ConsoleKind::Error, e.what());
    }
    console(ConsoleKind::Info, std::string("Fernverbindung: eigener Schlüssel angelegt (") +
                                   (client_cert_->tpm() ? "im TPM" : "Software-Schlüsselspeicher") +
                                   (note.empty() ? std::string() : ", " + note) + ", nicht exportierbar). Fingerabdruck "
                                   "der App: " + client_cert_->pin() + " – auf dem Server für diese App eintragen.");
    return client_cert_;
}

std::optional<std::string> Controller::own_client_pin() const {
    std::lock_guard<std::mutex> lock(cert_mutex_);
    const std::string pin = config().relay_client_pin;
    if (pin.empty()) {
        return std::nullopt;
    }
    if ((client_cert_ && client_cert_->pin() == pin) || win::find_client_cert(pin)) {
        return pin;
    }
    return std::nullopt;
}

std::optional<std::string> Controller::create_client_pin(std::string& error) {
    const std::shared_ptr<const win::ClientCert> cert = ensure_client_cert(error);
    if (!cert) {
        return std::nullopt;
    }
    return cert->pin();
}
namespace {
constexpr std::string_view kPasswordPurpose = "baumeister-tunnel-passwort";
}  // namespace

std::filesystem::path Controller::password_file() const { return paths_.data_dir / L"tunnel-passwort.dpapi"; }

bool Controller::has_tunnel_password() const {
    std::error_code ec;
    return std::filesystem::is_regular_file(password_file(), ec);
}

bool Controller::store_tunnel_password(const std::string& password) {
    if (password.empty()) {
        std::error_code ec;
        std::filesystem::remove(password_file(), ec);
        console(ConsoleKind::Info, "Tunnel-Passwort gelöscht.");
        return true;
    }
    const std::optional<std::string> sealed = win::protect_secret(password, kPasswordPurpose);
    if (!sealed) {
        console(ConsoleKind::Error, "Tunnel-Passwort nicht verschlüsselbar (DPAPI) – nicht gespeichert.");
        return false;
    }
    try {
        write_file_atomic(password_file(), *sealed);
    } catch (const std::exception& e) {
        console(ConsoleKind::Error, std::string("Tunnel-Passwort nicht speicherbar: ") + e.what());
        return false;
    }
    console(ConsoleKind::Info, "Tunnel-Passwort verschlüsselt gespeichert (nur dieses Windows-Konto kann es lesen).");
    return true;
}

std::optional<std::string> Controller::tunnel_password() const {
    const auto stored = read_file_if_exists(password_file(), 64 * 1024);
    if (!stored) {
        return std::nullopt;
    }
    std::optional<std::string> plain = win::unprotect_secret(*stored, kPasswordPurpose);
    if (!plain) {
        // fail-closed: lieber kein Tunnel als ein falsches/leeres Passwort (z. B. Datei von einem anderen Konto).
        log::warn("Tunnel-Passwort nicht entschlüsselbar – anderes Windows-Konto oder Datei beschädigt");
    }
    return plain;
}

void Controller::start_all() {
    if (!download_busy_) {
        ensure_model();
    }
    start_server();
    start_link();
}

void Controller::connect_only() {
    // Server und Tunnel ohne Sprachmodell: Stadt- und Weltbau brauchen keine KI, nur die Rückmeldung an die App. Ein
    // LLM-Auftrag bekommt in dieser Zeit 503 („Modell nicht geladen“), das Plugin sagt das im Chat.
    console(ConsoleKind::Info, "Nur verbinden: Server und Verbindung ohne Sprachmodell (Stadt- und Weltbau laufen "
                               "ohne KI; für Bauaufträge „Modell laden“).");
    start_server();
    start_link();
}

void Controller::stop_all() {
    // Steht im Protokoll, weil der Tunnel danach nicht von selbst zurückkommt – ohne die Zeile sah ein „Alles stoppen“
    // dort aus wie ein Abriss.
    log::info("Alles stoppen: Verbindung und Server angehalten, bis „Alles starten“.");
    stop_link();
    stop_server();
}

void Controller::shutdown() {
    engine_.stop_all();
    cancel_download();
    tunnel_.stop();
    relay_.stop();
    server_.stop();
    local_server_.stop();
    {
        std::lock_guard<std::mutex> lock(cancel_mutex_);
        if (voice_cancel_ != nullptr) {
            voice_cancel_->cancel();
        }
    }
    join_worker(voice_download_thread_);
    {
        std::lock_guard<std::mutex> lock(say_mutex_);
        join_worker(say_thread_);
    }
    {
        std::lock_guard<std::mutex> lock(cancel_mutex_);
        if (speech_cancel_ != nullptr) {
            speech_cancel_->cancel();
        }
    }
    join_worker(speech_download_thread_);
    speech_.stop();
    join_worker(load_thread_);
    join_worker(download_thread_);
    join_worker(cuda_thread_);
}

// --- Server-Seite -----------------------------------------------------------------------------------------------

bool Controller::ready() const { return loaded_ && !loading_; }

std::string Controller::model_name() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snap_.model_title.empty() ? std::string("lokal") : snap_.model_title;
}

std::optional<llm::CompletionResult> Controller::complete(const llm::CompletionRequest& request, std::string& error) {
    std::optional<llm::CompletionResult> result;
    {
        std::lock_guard<std::mutex> lock(engine_mutex_);
        if (!loaded_) {
            error = "Modell nicht geladen";
            return std::nullopt;
        }
        result = engine_.complete(request, error);
    }
    // Erst nach Erfolg und außerhalb von engine_mutex_: Ein gelungener Auftrag belegt, dass sein System-Prompt in den
    // Kontext passt; die Datei zu schreiben soll den nächsten Auftrag nicht aufhalten.
    if (result) {
        remember_prefix(request.messages);
    }
    return result;
}

void Controller::cancel() { engine_.cancel(); }

void Controller::show_demo_job(const llm::CompletionResult& r, double warm_prompt_tps, const std::string& research) {
    const double gen_s = r.seconds - r.prompt_seconds;
    std::lock_guard<std::mutex> lock(mutex_);
    jobs_.set_warm_prompt_tps(warm_prompt_tps);
    snap_.last_warning = jobs_.stats().slow_warning_de(r);
    snap_.has_last = true;
    snap_.last_ok = true;
    snap_.last_seconds = r.seconds;
    snap_.last_tokens = r.generated_tokens;
    snap_.last_tps = gen_s > 0.0 ? r.generated_tokens / gen_s : 0.0;
    snap_.last_prompt_seconds = r.prompt_seconds;
    snap_.last_prompt_tokens = r.prompt_tokens;
    snap_.last_reused = r.reused_tokens;
    snap_.last_research = research;
}

void Controller::request_started(const server::RequestInfo& info) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        jobs_.started(info.order, info.summary, info.grammar);
    }
    console(ConsoleKind::Request, "Auftrag: „" + info.summary + "“ (" + std::to_string(info.messages) +
                                      " Nachrichten, max. " + std::to_string(info.max_tokens) + " Token, Temperatur " +
                                      fixed(info.temperature, 2) + ", Grammatik " + info.grammar + ")");
}

void Controller::token(std::string_view piece) { console(ConsoleKind::Token, std::string(piece)); }

void Controller::request_finished(const server::FinishInfo& info) {
    const llm::CompletionResult& r = info.result;
    const double gen_s = r.seconds - r.prompt_seconds;
    const double tps = gen_s > 0.0 ? r.generated_tokens / gen_s : 0.0;
    std::string warning;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap_.has_last = true;
        snap_.last_ok = info.ok;
        snap_.last_error = info.error;
        snap_.last_seconds = r.seconds;
        snap_.last_tokens = r.generated_tokens;
        snap_.last_tps = tps;
        snap_.last_prompt_seconds = r.prompt_seconds;
        snap_.last_prompt_tokens = r.prompt_tokens;
        snap_.last_reused = r.reused_tokens;
        // Vor finished(): Der Maßstab ist das Tempo OHNE diesen Auftrag.
        warning = info.ok ? jobs_.stats().slow_warning_de(r) : std::string();
        snap_.last_warning = warning;
        // Plan-Vorschau und Benachrichtigung lesen das über last_plan() (gui/job_tracker.hpp).
        jobs_.finished(info.ok, info.error, r, tps);
        snap_.stats = jobs_.stats();
    }
    if (!info.ok) {
        console(ConsoleKind::Error, "Auftrag fehlgeschlagen: " + info.error);
        return;
    }
    if (r.clamped_to > 0) {
        // Gekappt heißt: Prompt + Budget füllen den Kontext genau (llm::answer_budget) – daher der Kontext als Summe.
        console(ConsoleKind::Warn, "max_tokens auf " + std::to_string(r.clamped_to) + " gekappt (Prompt " +
                                       std::to_string(r.prompt_tokens) + " Token, Kontext " +
                                       std::to_string(r.prompt_tokens + r.clamped_to) + ")");
    }
    console(ConsoleKind::Done, "Fertig in " + fixed(r.seconds, 1) + " s: " + std::to_string(r.generated_tokens) +
                                   " Token, " + fixed(tps, 1) + " Token/s (Prompt " + std::to_string(r.prompt_tokens) +
                                   " Token, davon " + std::to_string(r.reused_tokens) + " aus dem Speicher, " +
                                   fixed(r.prompt_seconds, 1) + " s)" + (r.hit_limit ? " – max_tokens erreicht!" : "") +
                                   (r.drafted > 0 ? ", Turbo: " + std::to_string(r.accepted) + " von " +
                                                        std::to_string(r.drafted) + " Entwurfs-Token übernommen (" +
                                                        fixed(100.0 * r.accepted / r.drafted, 0) + " %)"
                                                  : ""));
    if (r.generated_tokens > 0) {
        // Aufschlüsselung je Token: GPU-Rechnung gegen Token-Auswahl auf der CPU – zeigt, wo Tempo verloren geht.
        const double n = r.generated_tokens;
        const double rest = gen_s - r.gpu_seconds - r.sample_seconds;
        console(ConsoleKind::Info, "Je Token: GPU " + fixed(r.gpu_seconds * 1000.0 / n, 1) + " ms, Auswahl (CPU) " +
                                       fixed(r.sample_seconds * 1000.0 / n, 1) + " ms, Rest " +
                                       fixed(rest * 1000.0 / n, 1) + " ms");
    }
    if (!warning.empty()) {
        console(ConsoleKind::Warn, warning);
    }
}

}  // namespace swbh::gui
