// Sprechen mit dem Begleiter: Taste → Mikrofon nimmt auf → Aufbereitung (voice/signal) → Whisper → Text an den
// Begleiter.
//
// Zwei Bedienarten (Wunsch 2026-11-04):
//   Halten     – aufnehmen, solange die Taste gedrückt ist (Vorgabe).
//   Umschalten – einmal drücken startet, die Aufnahme endet bei 1,2 s Stille nach dem Satz von selbst oder beim
//                zweiten Druck.
// Das Mikrofon ist nur während einer Aufnahme offen, nie dauernd – keine dauernde Mikrofon-Anzeige von Windows, kein
// Mitschnitt des Raums. Der Pegel ist deshalb nur während Aufnahme und Mikrofontest zu sehen.
//
// Threads: Haltetaste (Hook-Thread) → Mikrofon (eigener Thread) → Erkennung im Arbeitsthread dieser Klasse. on_text
// und on_status laufen im Arbeitsthread; status() und set_companion_name gehen aus jedem Thread. Fehler: als Status,
// nie als Ausnahme.

#pragma once

#include "voice/push_to_talk.hpp"
#include "voice/recognizer.hpp"
#include "voice/signal.hpp"

#include <atomic>
#include <chrono>
#include <cstddef>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace swbh::voice {

// Aufnahme zwischen Start und Ende. Rein, ohne Gerät – im Test befüllbar.
class Utterance final {
public:
    static constexpr std::size_t kMaxSamples = 30 * 16000;  // 30 s, danach wird nicht mehr angehängt
    static constexpr std::size_t kMinSamples = 4800;        // 0,3 s: kürzer ist ein versehentlicher Tastendruck

    void clear() { pcm_.clear(); }
    void append(std::span<const float> frames);
    bool usable() const { return pcm_.size() >= kMinSamples; }
    bool full() const { return pcm_.size() >= kMaxSamples; }
    const std::vector<float>& pcm() const { return pcm_; }

private:
    std::vector<float> pcm_;
};

enum class TalkMode { Hold, Toggle };

// „halten“ (Vorgabe) | „umschalten“ – Unbekanntes gilt als halten.
TalkMode parse_talk_mode(std::string_view text);

enum class SpeechState { Off, Loading, Ready, Listening, Recognizing, Testing, Error };

struct SpeechStatus {
    SpeechState state = SpeechState::Off;
    double level_dbfs = -120;   // letzter 50-ms-Block (nur während Aufnahme/Test)
    double peak_dbfs = -120;    // Spitze der laufenden Aufnahme
    bool clipping = false;      // laufende Aufnahme übersteuert
    std::string last_text;      // zuletzt erkannt
    std::string last_detail;    // „Pegel −31 dBFS, +11 dB automatisch, 0,8 s“
    std::string error;
};

class SpeechInput final {
public:
    struct Options {
        std::filesystem::path model;
        bool gpu = true;
        int gpu_device = 0;
        TalkKey key;
        TalkMode mode = TalkMode::Hold;
        std::string microphone;  // Gerätename, leer = Windows-Standard
        double gain_db = 0;      // Verstärkung von Hand
        bool auto_gain = true;
        bool tones = true;
        // Erkennung öffnen; leer = Recognizer::open (whisper). Der Test setzt hier einen Ersatz ein, der den Vortext
        // zurückgibt – so ist der Weg Begleitername → Vortext ohne Modell prüfbar. Den Vortext baut SpeechInput selbst
        // aus dem Begleiternamen (set_companion_name); vorher stand hier ein fertiger Vortext, den der Controller fest
        // mit „Vera“ füllte, und kein Test sah, was er hineinschrieb.
        std::function<std::unique_ptr<Transcriber>(const Recognizer::Options&, std::string& error)> open_recognizer;
    };
    using TextFn = std::function<void(std::string text)>;
    using StatusFn = std::function<void(bool error, std::string text)>;

    SpeechInput();
    ~SpeechInput();
    SpeechInput(const SpeechInput&) = delete;
    SpeechInput& operator=(const SpeechInput&) = delete;

    // Lädt die Erkennung im Arbeitsthread und richtet danach die Taste ein; Fortschritt und Fehler über on_status.
    void start(Options options, TextFn on_text, StatusFn on_status);
    void stop();
    bool running() const;
    SpeechStatus status() const;

    // Mikrofontest: seconds aufnehmen (Pegel live in status()), danach vorspielen und erkennen. Das Ergebnis kommt über
    // on_status („Test: …“), der Text geht NICHT an den Begleiter. false = Sprechen läuft nicht oder schon ein Test.
    bool test_microphone(int seconds);

    // Fertige Aufnahme erkennen (Ton aus dem Spiel über Simple Voice Chat, 16 kHz mono): gleiche Aufbereitung wie das
    // Mikrofon, Ergebnis zurück statt an on_text. Wartet höchstens timeout_ms. nullopt + error, wenn das Sprechen nicht
    // bereit ist oder die Erkennung scheitert; "" = nichts verstanden.
    std::optional<std::string> recognize(std::vector<float> pcm, std::string& error, int timeout_ms = 20000);

    // Begleitername für den Vortext der Erkennung (Plugin, ?begleiter=; 2026-11-20). Aus jedem Thread, auch vor und
    // während start()/stop(): legt nur den Namen unter einem eigenen kleinen Mutex ab und kehrt sofort zurück. Vorher
    // wartete der Long-Poll-Thread dafür auf speech_apply_mutex_, den apply_speech über stop() samt join hält
    // (Prüfbefund 2026-11-21). Das Laden nimmt den Namen mit; ein Wechsel gilt ab der nächsten Erkennung, ohne das
    // Modell neu zu laden (574 MB laden und Shader aufwärmen – so lange hörte die App nichts). Den Namen prüft der
    // Aufrufer (server::valid_companion_name); leer = kDefaultCompanionName. true = er hat sich geändert.
    bool set_companion_name(std::string name);

    // Sprechtaste ruhen lassen bis until (Spieler spricht im Spiel über Simple Voice Chat; Wunsch 2026-11-04). Gedrückt
    // wird dann weder aufgenommen noch getönt; eine
    // schon laufende Aufnahme endet normal. Mit einer Frist statt eines Schalters, damit ein verstummtes Plugin die
    // Taste nicht für immer abschaltet.
    void pause_until(std::chrono::steady_clock::time_point until);

private:
    struct Impl;
    // Begleitername außerhalb von Impl: start() ersetzt Impl, der Name überlebt das und ist ohne Impl setzbar. Nie neu
    // zugewiesen – deshalb aus jedem Thread lesbar.
    struct Name;
    const std::shared_ptr<Name> name_;
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
