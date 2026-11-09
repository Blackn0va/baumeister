// Natürliche Stimme über Piper als eigener, verborgener Prozess – übernommen aus kraken/src/voice/piper.* (dort
// gemessen: erster Satz 1,8 s inklusive Laden, danach 0,1–0,4 s je Satz auf der CPU).
//
// Warum ein Prozess und kein Linken: Piper bringt espeak-ng mit (GPL-3.0-or-later). Vorher sollte die App so
// proprietär bleiben; seit 2026-11-09 steht sie selbst unter GPL-3.0-or-later. Der getrennte Prozess bleibt trotzdem:
// Ein Absturz in Piper reißt die App nicht mit, und Piper lässt sich ohne Neubau tauschen.
//
// Ablauf: piper.exe läuft dauerhaft mit --json-input; je Satz eine JSON-Zeile {"text", "output_file"}, Piper schreibt
// die WAV und meldet den Pfad auf stdout – saubere Satzgrenzen.
//
// Fallen (kraken): piper.exe liest Pfade in der ANSI-Codepage – Pfade mit Umlauten gehen über 8.3-Kurznamen
// (ascii_path); geht das nicht, meldet start() einen Fehler statt still falsche Dateien zu schreiben. Der Prozess hängt
// an einem Job-Objekt mit KILL_ON_JOB_CLOSE: Endet die App, endet Piper mit.
//
// Threads: synth nicht gleichzeitig aus mehreren Threads (VoiceOut sperrt). Fehler: start false + Grund, synth nullopt.

#pragma once

#include "voice/audio.hpp"

#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::voice {

// Pfad als reines ASCII (für piper.exe), notfalls über GetShortPathNameW; nullopt, wenn das nicht geht.
std::optional<std::string> ascii_path(const std::filesystem::path& p);

// Eine JSON-Zeile für piper --json-input (mit \n am Ende).
std::string piper_line(std::string_view text, std::string_view output_file);

struct PiperOptions {
    std::filesystem::path exe;
    std::filesystem::path model;     // .onnx; die .onnx.json muss daneben liegen
    std::filesystem::path work_dir;  // für die WAV-Zwischendateien (wird angelegt)
    float length_scale = 1.0f;       // < 1 schneller, > 1 langsamer
    float noise_scale = 0.667f;      // Vorgabe der Stimmen; höher = lebendiger
    float noise_w = 0.8f;
};

class Piper final {
public:
    Piper();
    ~Piper();
    Piper(const Piper&) = delete;
    Piper& operator=(const Piper&) = delete;

    bool start(const PiperOptions& options, std::string& error);
    // Blockiert, bis der Satz fertig ist.
    std::optional<Pcm> synth(std::string_view text, std::string& error);
    void stop();
    bool running() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
