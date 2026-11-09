// Aufbereitung der Mikrofon-Aufnahme vor Whisper (Wunsch 2026-11-04). Rein, ohne Gerät – jede Stufe hat ihren Test.
//
// Kette: Verstärkung von Hand (dB) → Stille vorn und hinten abschneiden → automatische Verstärkung auf einen
// gleichmäßigen Sprachpegel (−20 dBFS) → weicher Begrenzer gegen Übersteuern. Dazu die Messwerte für Anzeige und
// Warnungen (Pegel, Spitze, Anteil übersteuerter Samples).
//
// Warum vor Whisper: Ein leises Headset-Mikrofon lieferte in kraken −40 dBFS – Whisper verlor dann Silben oder
// erfand Text. Lange Stille an den Rändern kostet Rechenzeit und lädt zu Erfindungen ein („Untertitel der Amara …“).
//
// Threads: zustandslos bis auf EndDetector (gehört einem Thread). Fehler: keine.

#pragma once

#include <cstddef>
#include <span>
#include <vector>

namespace swbh::voice {

inline constexpr int kFrame = 320;                // 20 ms bei 16 kHz
inline constexpr double kTargetDbfs = -20.0;      // Sprachpegel nach der automatischen Verstärkung
inline constexpr double kMaxAutoGainDb = 30.0;    // mehr hebt nur Rauschen an
inline constexpr double kMinAutoGainDb = -12.0;   // zu laute Aufnahmen werden höchstens so weit abgesenkt
inline constexpr float kSpeechFloor = 0.006f;     // ≈ −44 dBFS: darunter ist es nie Sprache (kraken vad.hpp)

// Effektivwert → dBFS (−120 für Stille).
double to_dbfs(float rms_value);

struct Conditioned {
    std::vector<float> pcm;   // aufbereitet, an Whisper
    bool silent = true;       // keine Sprache gefunden → nicht erkennen
    double input_dbfs = -120; // Sprachpegel der Aufnahme vor der Automatik (nach der Verstärkung von Hand)
    double auto_gain_db = 0;  // von der Automatik angewendet
    double peak = 0;          // höchster Betrag der Rohaufnahme
    double clipped = 0;       // Anteil der Rohsamples am Anschlag (|x| ≥ 0,999) – über 0,5 % ist das Mikro zu laut
    double trimmed_ms = 0;    // abgeschnittene Stille
};

// manual_db: Verstärkung von Hand (−20 … +30). agc: automatische Verstärkung an/aus.
Conditioned condition(std::span<const float> raw, double manual_db, bool agc);

// Ende einer Äußerung bei „Umschalten“: Nachdem Sprache kam, so viel Stille → fertig. Pegel je 20-ms-Block.
class EndDetector final {
public:
    explicit EndDetector(int silence_ms = 1200) : silence_frames_(silence_ms / 20) {}
    // true, sobald nach Sprache genug Stille kam.
    bool feed(std::span<const float> samples);
    bool heard_speech() const { return speech_frames_ >= 5; }
    void reset();

private:
    int silence_frames_;
    std::vector<float> pending_;
    int speech_frames_ = 0;
    int quiet_ = 0;
    float floor_ = 0.0f;
    int seen_ = 0;
};

}  // namespace swbh::voice
