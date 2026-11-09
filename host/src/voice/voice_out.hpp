// Stimme des Begleiters: Piper (natürlich, wie in kraken) mit der Windows-Sprachausgabe als Rückfall.
//
// Wahl in den Einstellungen als „piper:<Stimme>“ (Vorgabe piper:thorsten-medium) oder „sapi:<Name>“. Ist Piper gewählt,
// aber noch nicht geladen (Download läuft) oder fällt der Prozess aus, spricht die Windows-Stimme – lieber eine
// schlechtere Stimme als Schweigen. Wer den Rückfall entfernt, lässt den Begleiter bis zum Ende des Downloads stumm.
//
// Ausgabe als Pcm in der Rate der Stimme (Piper 22,05 kHz, SAPI 22,05 kHz). Für Simple Voice Chat rechnet for_voicechat
// auf 48 kHz um (Opus-Rate des Mods).
//
// Threads: synth ist gesperrt (ein Satz zugleich – Piper verarbeitet ohnehin nur einen). Fehler: nullopt + Grund.

#pragma once

#include "voice/audio.hpp"
#include "voice/piper_files.hpp"
#include "voice/voice_style.hpp"

#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::voice {

inline constexpr int kVoicechatRate = 48000;

struct VoiceChoice {
    bool piper = true;
    std::string key;  // Piper-Stimme (piper_voices) oder Teil des SAPI-Namens
    // Tonhöhe der Piper-Stimme (voice_style_pitch): 1 = wie aufgenommen, 1,3 = Cartoon (Vorgabe seit 2026-11-06).
    double pitch = 1.3;
};

// Cartoon-Klang ohne Zusatzbibliothek: Piper spricht um pitch langsamer (length_scale × pitch, siehe VoiceOut), dann
// gilt der Ton als um pitch schneller abgetastet (rate × pitch). Tempo bleibt etwa gleich, Stimme und Klangfarbe
// liegen um pitch höher – wie bei Trickfilmfiguren. pitch ≤ 1 oder leerer Ton: unverändert.
Pcm with_pitch(Pcm pcm, double pitch);

// „piper:thorsten-medium“, „sapi:Hedda“; leer oder unbekannt = Piper mit Vorgabestimme.
VoiceChoice parse_voice_choice(std::string_view text);

// 22,05 kHz → 48 kHz, int16 (Simple Voice Chat erwartet 48 kHz mono, 960 Samples je 20-ms-Block).
std::vector<std::int16_t> for_voicechat(const Pcm& pcm);

class VoiceOut final {
public:
    VoiceOut();
    ~VoiceOut();
    VoiceOut(const VoiceOut&) = delete;
    VoiceOut& operator=(const VoiceOut&) = delete;

    void configure(const VoicePaths& paths, const VoiceChoice& choice);
    // Piper gewählt, aber Programm oder Stimme fehlen (der Controller lädt sie dann).
    bool needs_download() const;
    // used: „Piper Thorsten …“ bzw. „Windows-Stimme“ – für die Konsole.
    std::optional<Pcm> synth(std::string_view text, std::string& error, std::string* used = nullptr);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
