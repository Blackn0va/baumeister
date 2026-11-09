// Übernommen aus kraken/src/voice/audio.* (dort getestet), 2026-11-04 für die Spracheingabe des Begleiters.
// Audio-Grundlagen ohne Geräte: PCM-Puffer, WAV lesen (Piper-Ausgabe), Pegel, Umwandlung int16 ↔ float.

#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::voice {

// Mikrofon und Spracherkennung arbeiten mit 16 kHz mono (Whisper-Eingang).
inline constexpr int kMicRate = 16000;

struct Pcm {
    int rate = 0;                       // Abtastrate in Hz
    std::vector<std::int16_t> samples;  // mono
    double seconds() const { return rate > 0 ? static_cast<double>(samples.size()) / rate : 0.0; }
};

// RIFF/WAVE mit PCM 16 bit. Mehrkanalig wird zu mono gemischt. nullopt + error bei allem anderen (fail-closed:
// lieber kein Ton als Rauschen aus falsch gedeuteten Bytes).
std::optional<Pcm> parse_wav(std::string_view bytes, std::string& error);
// Gegenstück: RIFF/WAVE, PCM 16 bit mono (Aufnahmen im Stimmlabor).
std::string wav_bytes(const Pcm& pcm);

std::vector<float> to_float(std::span<const std::int16_t> in);
// Lineare Umrechnung der Abtastrate mit Mittelung beim Verkleinern (gegen Aliasing). Für den Rundlauf
// Piper (22,05 kHz) → Whisper (16 kHz) in Proben; das Mikrofon liefert direkt 16 kHz.
std::vector<float> resample(std::span<const float> in, int from_rate, int to_rate);
// Verstärkung in dB → Faktor (0 dB = 1, 20 dB = 10).
float db_to_gain(double db);
// Block verstärken und auf -1 … 1 begrenzen (lieber leicht übersteuert als zu leise für die Erkennung).
void apply_gain(std::span<float> frame, float gain);
// Effektivwert (0 … 1) eines Blocks.
float rms(std::span<const float> frame);

}  // namespace swbh::voice
