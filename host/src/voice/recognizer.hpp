// Spracherkennung für den Begleiter: whisper.cpp im eigenen Prozess, gleiches ggml wie das Sprachmodell
// (cmake/SwbhWhisper.cmake). Übernommen und gekürzt aus kraken/src/voice/whisper_stt.cpp (dort gelaufen): ein Zustand,
// kurze Sätze, Sprache fest Deutsch, Vortext mit dem Namen des Begleiters.
//
// Threads: transcribe aus EINEM Thread zugleich (der Sprach-Worker der App). Laden zählt die ggml-Geräteliste unter
// accel::registry_mutex (wie llm::LlamaEngine). Fehler: open liefert nullptr mit deutschem Grund; transcribe nullopt.

#pragma once

#include "llm/models.hpp"

#include <filesystem>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace swbh::voice {

// Erkennungsmodell: Whisper Large v3 Turbo, q5_0 (574 MB). Werte wie kraken/deps.lock.json (dort aus der Hub-API,
// lfs.oid) und host/deps.lock.json „whisper“ – test_voice prüft den Gleichstand. Bewusst nicht im LLM-Katalog: Es ist
// kein auswählbares Sprachmodell.
llm::ModelSpec whisper_model_spec();

// Vortext für Whisper: lenkt auf Deutsch und auf den Namen („Hey Vera, …“). Kurze Eigennamen verhört Whisper sonst
// leicht („Wera“, „Fehra“) – und ohne Namen erkennt das Plugin die Anrede nicht.
std::string recognizer_prompt(std::string_view companion_name);

// Erkannten Text aufräumen: Rand-Leerraum, und typische Whisper-Erfindungen auf Stille („Untertitel der Amara.org-
// Community“, „[Musik]“, „Vielen Dank.“ allein) verwerfen – leer = nichts Verwertbares gehört.
std::string clean_transcript(std::string_view raw);

class Recognizer final {
public:
    struct Options {
        std::filesystem::path model;
        bool gpu = true;
        int gpu_device = 0;  // accel::Runtime::whisper_gpu_ordinal
        int threads = 4;
        std::string prompt;
    };

    // Lädt das Modell und wärmt auf (der erste Lauf übersetzt die Vulkan-Shader: in kraken 7,8 s statt 0,1 s).
    static std::unique_ptr<Recognizer> open(const Options& options, std::string& error);
    ~Recognizer();
    Recognizer(const Recognizer&) = delete;
    Recognizer& operator=(const Recognizer&) = delete;

    // 16 kHz mono. Rohtext (clean_transcript räumt danach auf); nullopt + error bei Fehler.
    std::optional<std::string> transcribe(std::span<const float> pcm, std::string& error);
    void set_prompt(std::string prompt);

private:
    struct Impl;
    explicit Recognizer(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
