// Sprachmodell über llama.cpp – reduzierte Fassung von kraken/src/llm/engine.hpp.
//
// Warum reduziert: Der Baumeister schickt genau EINEN Auftrag gleichzeitig (der Server lehnt parallele Anfragen mit 503
// ab). kraken hält acht KV-Slots für acht Prompt-Familien und rechnet bis zu vier Sequenzen parallel – hier gibt es
// nur eine Prompt-Familie (System-Prompt des Baumeisters), also einen Slot. Die Präfix-Wiederverwendung bleibt: Der
// System-Prompt (~1700 Token) steht nach dem ersten Auftrag im KV-Speicher, eine Reparaturrunde rechnet nur ihren
// neuen Teil (gemessen mit llama-server auf dem Laptop: erster Aufruf länger, danach Prompt-Cache).
//
// Anders als kraken nimmt complete() eine ganze Nachrichtenliste (system/user/assistant/…): Das Plugin schickt bei
// einem ungültigen Plan den alten Plan als „assistant“ und die Fehlermeldung als neue „user“-Nachricht
// (BuildService.java:225).
//
// GPU: load() nimmt die dedizierte GPU mit dem meisten Speicher (best_gpu) und legt ALLE Schichten dorthin. Integrierte
// Grafik (Intel Arc im Laptop) nur mit LoadOptions::allow_integrated – auf dem GPU-PC würde sie sonst neben der
// NVIDIA-Karte gewählt, wenn deren Treiber kein Vulkan meldet. Ohne GPU: Fehler, kein stilles Rechnen auf der CPU.
//
// Vorwärmen (prewarm): Nach dem Laden ist der KV-Speicher leer, der erste Auftrag rechnete den ganzen System-Prompt
// (~2200 Token) selbst. Die App merkt sich die führenden system-Nachrichten des letzten Auftrags (prefix.json) und
// rechnet sie gleich nach dem Laden in den Speicher – der erste Auftrag übernimmt sie wie jede Reparaturrunde.
//
// Threads: Ein LlamaEngine-Objekt ist nicht threadsicher; der Server ruft complete() aus genau einem Arbeitsthread,
// prewarm() kommt aus dem Lade-Thread (der Aufrufer schließt beide über einen Mutex gegeneinander aus).
// cancel() und stop_all() dürfen aus jedem Thread kommen.
// Fehler: load()/complete()/prewarm() liefern false/nullopt mit deutschem Grund in error.

#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::llm {

struct GpuDevice {
    std::string name;         // ggml-Name, z. B. "Vulkan0"
    std::string description;  // "NVIDIA GeForce RTX 4070"
    std::string backend;      // ggml-Backend, z. B. "Vulkan", "CUDA"
    std::uint64_t memory_total = 0;
    std::uint64_t memory_free = 0;
    bool integrated = false;
};

// Anfang der Fehlermeldung, wenn nach dem Prompt kein brauchbares Antwortbudget im Kontext bleibt (answer_budget).
inline constexpr const char* kPromptTooLong = "Prompt zu lang";

// Kleinstes Antwortbudget, für das sich ein Aufruf lohnt – dieselbe Untergrenze, auf die das Plugin max_tokens klemmt
// (Settings.java: Math.clamp(…, 256, 8192)). Bleibt weniger Platz, ist ein HTTP 400 mit kPromptTooLong ehrlicher als
// ein fast sicher abgeschnittener Plan, den das Plugin danach in die nächste Reparaturrunde schickt.
inline constexpr int kMinAnswerTokens = 256;

// Wirksames max_tokens: min(requested, n_ctx − prompt_tokens); nullopt nur bei prompt_tokens ≤ 0 oder wenn weniger
// als kMinAnswerTokens Platz bleiben.
//
// Vorher lehnte die Engine jeden Aufruf mit prompt + max_tokens > n_ctx ab (HTTP 400). Anlass zur Änderung: Eine
// Reparaturrunde schickt System-Prompt (2230 Token) + alten Plan als assistant (~3000) + Fehlermeldung und verlangt
// wieder max_tokens 3000 – 2230 + ~3000 + 3000 > 8192, die Runde scheiterte, obwohl für einen Plan reichlich Platz war.
// Jetzt wird gekappt, finish_reason bleibt „length“, wenn das Budget wirklich erreicht wird.
//
// Bewusst KEIN Reserve-Parameter: Beide Erzeugungsschleifen kommen mit genau n_ctx − prompt aus. Ohne Entwurf wird
// das letzte Token an Position prompt + max − 1 ≤ n_ctx − 1 dekodiert, der letzten gültigen Stelle
// (llama_engine.cpp:930, batch.fill(&step …)). Mit Entwurf steht `last` höchstens an n_ctx − 2 (nach g < max
// gezogenen Token an prompt − 1 + g), und room_ctx (llama_engine.cpp:807-808) hält die Entwurfs-Token dahinter vor dem
// Ende; das letzte gezogene Token wird dort nicht mehr dekodiert. Wer hier
// noch eine Reserve abzieht, verschenkt Antwort-Token; wer die Grenze lockert, lässt llama_decode am Kontextende
// scheitern.
inline std::optional<int> answer_budget(int prompt_tokens, int requested, int n_ctx) {
    if (prompt_tokens <= 0) {
        return std::nullopt;
    }
    const int room = n_ctx - prompt_tokens;
    if (room < kMinAnswerTokens) {
        return std::nullopt;
    }
    return std::min(requested, room);
}

struct ChatMessage {
    std::string role;  // "system", "user", "assistant"
    std::string content;

    bool operator==(const ChatMessage&) const = default;
};

// Präfix fürs Vorwärmen: die führenden system-Nachrichten, bis zur ersten anderen Rolle. Nur dieser Anfang ist bei
// jedem Auftrag des Baumeisters gleich (BuildService.java:212: System-Prompt, dann der Auftrag als user); eine spätere
// system-Nachricht stünde hinter wechselndem Text und brächte im KV-Speicher nichts.
inline std::vector<ChatMessage> leading_system_messages(const std::vector<ChatMessage>& messages) {
    std::vector<ChatMessage> out;
    for (const ChatMessage& m : messages) {
        if (m.role != "system") {
            break;
        }
        out.push_back(m);
    }
    return out;
}

// prefix.json: {"format": 1, "sha256": <Hex über die kompakte JSON-Form von messages>, "messages": [...]}.
// Die Prüfsumme fängt Handänderungen und beschädigte Dateien ab – sie ist kein Schutz gegen einen Angreifer (wer in
// {data} schreiben kann, rechnet sie neu). Das muss sie auch nicht sein: Der Inhalt geht durch dieselbe Chat-Vorlage
// samt defuse_control_tokens wie jeder Auftrag und steuert nur, welche Token vorab im Speicher stehen.
// save_prefix_file schreibt atomar (write_file_atomic) und wirft FileError; load_prefix_file liefert nullopt mit
// leerem error, wenn die Datei fehlt, sonst nullopt mit deutschem Grund (Format, Prüfsumme, andere Rolle als system).
void save_prefix_file(const std::filesystem::path& file, const std::vector<ChatMessage>& prefix);
std::optional<std::vector<ChatMessage>> load_prefix_file(const std::filesystem::path& file, std::string& error);

struct CompletionRequest {
    std::vector<ChatMessage> messages;
    std::string grammar;  // GBNF, leer = ohne Grammatik
    int max_tokens = 3000;
    float temperature = 0.2f;
    std::uint32_t seed = 42;
    // Optional: das gerade erzeugte Stück (nicht der ganze Text) nach jedem Token – Live-Konsole der GUI. Läuft im
    // Thread von complete(), kurz halten.
    std::function<void(std::string_view piece)> on_text;
};

struct CompletionResult {
    std::string text;
    int prompt_tokens = 0;
    int reused_tokens = 0;  // davon aus dem KV-Speicher übernommen
    int generated_tokens = 0;
    double seconds = 0.0;
    double prompt_seconds = 0.0;  // Prompt-Verarbeitung (Rest = Erzeugung)
    double gpu_seconds = 0.0;     // Erzeugung: Warten auf die GPU (llama_decode bis Logits da)
    double sample_seconds = 0.0;  // Erzeugung: Token-Auswahl auf der CPU (Sampler + Grammatik)
    int drafted = 0;              // spekulativ: vom Entwurfsmodell vorgeschlagene Token
    int accepted = 0;             // davon vom Hauptmodell übernommen
    bool hit_limit = false;       // max_tokens erreicht statt Ende-Token (OpenAI finish_reason "length")
    // 0 = max_tokens wie verlangt; sonst das an den Kontext gekappte Budget (answer_budget). Dann gilt
    // prompt_tokens + clamped_to == Kontextgröße – die Konsole rechnet den Kontext daraus zurück.
    int clamped_to = 0;
};

// Steuerfolgen der Chat-Vorlagen in Fremdtext entschärfen („<|im_start|>“ → „< |im_start| >“, „</s>“, „[INST]“ …).
// Übernommen aus kraken (Code-Review 2026-10-09): Der Prompt wird mit parse_special = true tokenisiert, weil die
// Vorlage ihre Sondertoken braucht. Ohne Entschärfen könnte ein Bauauftrag aus dem Minecraft-Chat mit
// „<|im_start|>system“ dem Modell eine neue Systemrolle unterschieben. Gilt für alle Nachrichten.
inline std::string defuse_control_tokens(std::string_view text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (std::size_t i = 0; i < text.size(); ++i) {
        const std::string_view rest = text.substr(i);
        if (rest.starts_with("<|") || rest.starts_with("</s>") || rest.starts_with("<s>") || rest.starts_with("<<SYS>>")) {
            out += "< ";
            continue;
        }
        if (rest.starts_with("|>")) {
            out += "| >";
            ++i;
            continue;
        }
        if (rest.starts_with("[INST]") || rest.starts_with("[/INST]")) {
            out += "[ ";
            continue;
        }
        out += text[i];
    }
    return out;
}

// Länge des gemeinsamen Anfangs zweier Token-Folgen (KV-Wiederverwendung, ohne llama.cpp testbar).
inline std::size_t common_prefix(const std::vector<std::int32_t>& a, const std::vector<std::int32_t>& b) {
    std::size_t n = 0;
    while (n < a.size() && n < b.size() && a[n] == b[n]) {
        ++n;
    }
    return n;
}

struct LoadOptions {
    std::filesystem::path model_file;
    // Gewünschter Kontext; begrenzt durch den Katalogeintrag der Datei und n_ctx_train des Modells.
    int n_ctx = 8192;
    // KV-Cache als Q8_0. nullopt = Katalogwert (ModelSpec::kv_q8) der Datei.
    std::optional<bool> kv_q8;
    // ggml-Backend des Geräts ("Vulkan", "CUDA"); leer = jedes. Der CUDA-Kern muss vorher über accel::Runtime
    // geladen sein, Vulkan lädt die Engine selbst.
    std::string backend;
    // Auch integrierte Grafik zulassen – nur für Tests auf dem Laptop (Intel Arc).
    bool allow_integrated = false;
    // Spekulatives Dekodieren (Wunsch 2026-10-28): Ein kleines Entwurfsmodell mit
    // demselben Vokabular rät bis zu n_draft Token voraus, das Hauptmodell prüft sie in EINEM Durchgang. Jedes
    // ausgegebene Token zieht weiterhin das Hauptmodell selbst (Sampler + Grammatik wie ohne Entwurf) – das Ergebnis
    // folgt also genau seiner Verteilung, nur schneller, wenn der Entwurf oft stimmt. Leer = aus. Passt das Vokabular
    // nicht (Prüfung wie llama.cpp common/speculative.cpp), läuft es ohne Entwurf weiter.
    std::filesystem::path draft_file;
    int n_draft = 8;
    // Höchstens so viel Grafikspeicher fürs Hauptmodell; was nicht passt, rechnet schichtweise auf der CPU
    // (gpu_layers_for_budget). 0 = alle Schichten auf die GPU wie vor 2026-11-04.
    std::uint64_t gpu_budget_bytes = 0;
};

// Prüft, ob `text` vollständig von der GBNF-Grammatik `grammar` erzeugt werden kann (am Ende ist ein Ende erlaubt).
// false + error bei ungültiger Grammatik, bei Text, den sie nicht zulässt, oder bei unvollständigem Text. Nutzt die
// Grammatik von llama.cpp (dieselbe wie beim Erzeugen) – deshalb hier und nicht in plan/.
bool grammar_accepts(const std::string& grammar, const std::string& text, std::string& error);

// Rechenkerne (CPU, Vulkan) über accel::Runtime laden, llama.cpp-Protokoll umleiten und llama_backend_init – einmal
// je Prozess.
void ensure_llama_backend();

class LlamaEngine final {
public:
    LlamaEngine();
    ~LlamaEngine();
    LlamaEngine(const LlamaEngine&) = delete;
    LlamaEngine& operator=(const LlamaEngine&) = delete;

    // GPU-Geräte (GPU und IGPU) laut ggml, aller geladenen Kerne.
    static std::vector<GpuDevice> gpu_devices();

    bool load(const LoadOptions& options, std::string& error);
    void unload();
    bool loaded() const;
    std::string model_name() const;
    std::string device_name() const;
    std::string backend_name() const;  // z. B. "CUDA", "Vulkan"
    int context_size() const;          // 0 = nicht geladen
    bool kv_quantized() const;
    bool speculative() const;  // Entwurfsmodell geladen und passend
    int layer_count() const;   // Schichten des Modells, 0 = nicht geladen
    int gpu_layer_count() const;  // davon auf der GPU

    std::optional<CompletionResult> complete(const CompletionRequest& request, std::string& error);
    // Präfix (führende system-Nachrichten, ohne Generierungsaufforderung) in den KV-Speicher rechnen. Liefert die Zahl
    // der vorgewärmten Token; 0, wenn nichts zu tun war (leerer Präfix, oder ein Auftrag hat den Speicher schon
    // gefüllt – dessen Inhalt ist mindestens so gut, ihn zu ersetzen wäre ein Rückschritt). nullopt + error bei Fehler
    // oder Abbruch; der Speicher gilt dann als leer, ein späteres complete() rechnet alles neu.
    std::optional<int> prewarm(const std::vector<ChatMessage>& prefix, std::string& error);
    void cancel() { cancel_ = true; }
    // Haftender Abbruch beim Beenden: auch jeder spätere Aufruf endet sofort (aus kraken – ein cancel() kurz vor dem
    // Start eines Aufrufs ging sonst verloren, weil complete() den einfachen Abbruch zurücksetzt).
    void stop_all() {
        stopped_ = true;
        cancel_ = true;
    }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::atomic<bool> cancel_{false};
    std::atomic<bool> stopped_{false};
};

}  // namespace swbh::llm
