// Piper (natürliche Stimme des Begleiters) und seine deutschen Stimmen – Werte aus kraken/deps.lock.json „voice“ (dort
// gegen die geladenen Dateien geprüft), hier in host/deps.lock.json „piper“ wiederholt; test_voice prüft den
// Gleichstand. Wunsch 2026-11-04.
//
// Lizenzen (stehen in den Einstellungen neben der Wahl): Piper MIT, das mitgelieferte espeak-ng GPL-3.0-or-later –
// deshalb läuft Piper wie in kraken als eigener Prozess und wird nie gelinkt. Stimmen: Lizenz je Datensatz.
//
// Threads: unveränderliche Daten. Fehler: voice_or_default liefert nie nullptr.

#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::voice {

struct PiperSpec {
    std::string title;
    std::string url;
    std::uint64_t size = 0;
    std::string sha256;
    std::string license;
    std::string exe;  // relativ zum Sprachordner: "piper/piper.exe"
};

struct VoiceSpec {
    std::string key;
    std::string title;
    std::string repo;
    std::string revision;
    std::string path;  // Ordner im Repo, z. B. "de/de_DE/thorsten/medium"
    std::string file;  // "de_DE-thorsten-medium.onnx"; die Konfiguration heißt file + ".json"
    std::uint64_t size = 0;
    std::string sha256;
    std::uint64_t json_size = 0;
    std::string json_sha256;
    std::string license;
    std::string onnx_url() const;
    std::string json_url() const;
};

inline constexpr std::string_view kDefaultPiperVoice = "thorsten-medium";

const PiperSpec& piper_spec();
const std::vector<VoiceSpec>& piper_voices();
// Unbekannter Schlüssel → Vorgabe (Thorsten medium wie in kraken); nie nullptr.
const VoiceSpec& voice_or_default(std::string_view key);

}  // namespace swbh::voice
