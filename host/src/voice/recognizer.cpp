#include "voice/recognizer.hpp"

#include "accel/runtime.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "voice/audio.hpp"

#include <whisper.h>

#include <cstdio>
#include <mutex>
#include <shared_mutex>
#include <vector>

namespace swbh::voice {

namespace {

// Modell über _wfopen laden: whisper_init_from_file öffnet per std::string (ANSI-Codepage) und scheitert an Umlauten
// im Benutzerpfad (kraken).
struct FileLoader {
    std::FILE* f = nullptr;
};

size_t loader_read(void* ctx, void* out, size_t n) { return std::fread(out, 1, n, static_cast<FileLoader*>(ctx)->f); }
bool loader_eof(void* ctx) { return std::feof(static_cast<FileLoader*>(ctx)->f) != 0; }
void loader_close(void* ctx) {
    auto* l = static_cast<FileLoader*>(ctx);
    if (l->f != nullptr) {
        std::fclose(l->f);
        l->f = nullptr;
    }
}

// whisper.cpp/ggml schreiben viel auf stderr; nur Warnungen und Fehler ins Protokoll.
void whisper_log(enum ggml_log_level level, const char* text, void*) {
    if (level >= GGML_LOG_LEVEL_WARN && text != nullptr) {
        std::string t(text);
        while (!t.empty() && (t.back() == '\n' || t.back() == '\r')) {
            t.pop_back();
        }
        if (!t.empty()) {
            log::warn("whisper: " + t);
        }
    }
}

std::string trim(std::string_view s) {
    std::size_t a = 0;
    std::size_t b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\n' || s[a] == '\t' || s[a] == '\r')) {
        ++a;
    }
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\n' || s[b - 1] == '\t' || s[b - 1] == '\r')) {
        --b;
    }
    return std::string(s.substr(a, b - a));
}

}  // namespace

llm::ModelSpec whisper_model_spec() {
    llm::ModelSpec s;
    s.key = "whisper-turbo-q5";
    s.title = "Whisper Large v3 Turbo (Spracherkennung, 574 MB)";
    s.repo = "ggerganov/whisper.cpp";
    s.revision = "5359861c739e955e79d9a303bcbc70fb988958b1";
    s.file = "ggml-large-v3-turbo-q5_0.bin";
    s.size = 574041195;
    s.sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
    s.license = "MIT";
    return s;
}

std::string recognizer_prompt(std::string_view companion_name) {
    const std::string name(companion_name.empty() ? kDefaultCompanionName : companion_name);
    // Deutscher Satz mit dem Namen vorn: In kraken lenkte ein deutscher Vortext Whisper zuverlässig auf Deutsch
    // (vorher englische Zeilen trotz Sprache „de“), Namen zuerst, weil Whisper kurze Eigennamen sonst verhört.
    // Spielwörter dahinter (2026-11-04): „Du sollst mir Brot geben“ kam als „… Baut geben“ an – Whisper bevorzugt
    // Wörter, die im Vortext stehen. Kurz halten: Ein langer Vortext färbt auch den Stil der Abschrift.
    return "Hey " + name + ", wir spielen Minecraft und unterhalten uns auf Deutsch. " + name +
           ", gib mir Brot, Fackeln und eine Spitzhacke, mach mich kreativ, lass mich fliegen und bau mir ein Haus "
           "aus Holz.";
}

std::string clean_transcript(std::string_view raw) {
    std::string t = trim(raw);
    // Geschweifte/eckige Klammern: Whisper markiert Geräusche so („[Musik]“, „(Applaus)“) – kein Satz an den Begleiter.
    if (!t.empty() && ((t.front() == '[' && t.back() == ']') || (t.front() == '(' && t.back() == ')') ||
                       (t.front() == '*' && t.back() == '*'))) {
        return {};
    }
    // Bekannte Erfindungen auf Stille oder Rauschen (Untertitel aus den Trainingsdaten).
    for (const std::string_view bad : {"Untertitel der Amara.org-Community", "Untertitel im Auftrag des ZDF",
                                       "Untertitelung des ZDF", "Vielen Dank.", "Vielen Dank fürs Zuschauen!",
                                       "Bis zum nächsten Mal.", "..."}) {
        if (t == bad || t.find("Amara.org") != std::string::npos || t.find("Untertitel") == 0) {
            return {};
        }
    }
    return t;
}

struct Recognizer::Impl {
    whisper_context* ctx = nullptr;
    whisper_state* state = nullptr;
    Options o;
    std::mutex prompt_mutex;
};

Recognizer::Recognizer(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}

Recognizer::~Recognizer() {
    if (impl_->state != nullptr) {
        whisper_free_state(impl_->state);
    }
    if (impl_->ctx != nullptr) {
        whisper_free(impl_->ctx);
    }
}

std::unique_ptr<Recognizer> Recognizer::open(const Options& o, std::string& error) {
    static std::once_flag once;
    std::call_once(once, [] { whisper_log_set(whisper_log, nullptr); });
    // FALLE (kraken, 2026-10-14): Seit die ggml-Kerne DLLs sind, findet whisper ohne geladene Kerne kein Gerät
    // („GGML_ASSERT(device) failed“) – also CPU immer, Vulkan bei GPU. CUDA lädt die App nur über ihre Komponente.
    std::string why;
    if (!accel::Runtime::get().load_cpu(why)) {
        error = "Rechenkern für die Spracherkennung fehlt: " + why;
        return nullptr;
    }
    if (o.gpu && !accel::Runtime::get().loaded(accel::Backend::Cuda)) {
        std::string vk_error;
        accel::Runtime::get().load_vulkan(vk_error);  // ohne Vulkan rechnet whisper auf der CPU weiter
    }
    FileLoader file;
    if (_wfopen_s(&file.f, o.model.wstring().c_str(), L"rb") != 0 || file.f == nullptr) {
        error = "Erkennungsmodell nicht lesbar: " + u8str(o.model);
        return nullptr;
    }
    whisper_model_loader loader{&file, loader_read, loader_eof, loader_close};
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = o.gpu;
    cp.flash_attn = o.gpu;
    cp.gpu_device = o.gpu_device;
    auto impl = std::make_unique<Impl>();
    impl->o = o;
    {
        // whisper zählt die ggml-Geräteliste beim Laden selbst durch – geteilt sperren wie llm::LlamaEngine.
        std::shared_lock<std::shared_mutex> registry(accel::registry_mutex());
        impl->ctx = whisper_init_with_params_no_state(&loader, cp);
        if (impl->ctx != nullptr) {
            impl->state = whisper_init_state(impl->ctx);
        }
    }
    loader_close(&file);
    if (impl->ctx == nullptr || impl->state == nullptr) {
        error = "Erkennungsmodell lädt nicht (" + u8str(o.model.filename()) + ")";
        if (impl->ctx != nullptr) {
            whisper_free(impl->ctx);
        }
        return nullptr;
    }
    auto r = std::unique_ptr<Recognizer>(new Recognizer(std::move(impl)));
    const std::vector<float> silence(kMicRate, 0.0f);
    std::string warm_error;
    (void)r->transcribe(silence, warm_error);
    return r;
}

std::optional<std::string> Recognizer::transcribe(std::span<const float> pcm, std::string& error) {
    std::string prompt;
    {
        std::lock_guard<std::mutex> lock(impl_->prompt_mutex);
        prompt = impl_->o.prompt;
    }
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = impl_->o.threads;
    p.language = "de";
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    // Obergrenze je Segment gegen Wiederholschleifen (kraken: Base lief auf 1 s Audio 10 s lang in eine Schleife).
    p.max_tokens = 128;
    if (whisper_full_with_state(impl_->ctx, impl_->state, p, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        error = "Spracherkennung fehlgeschlagen";
        return std::nullopt;
    }
    std::string text;
    const int n = whisper_full_n_segments_from_state(impl_->state);
    for (int i = 0; i < n; ++i) {
        // Segmente, die Whisper selbst für „keine Sprache“ hält, sind meist Erfindungen auf Rauschen (kraken).
        if (whisper_full_get_segment_no_speech_prob_from_state(impl_->state, i) > 0.6f) {
            continue;
        }
        if (const char* t = whisper_full_get_segment_text_from_state(impl_->state, i)) {
            text += t;
        }
    }
    return text;
}

void Recognizer::set_prompt(std::string prompt) {
    std::lock_guard<std::mutex> lock(impl_->prompt_mutex);
    impl_->o.prompt = std::move(prompt);
}

}  // namespace swbh::voice
