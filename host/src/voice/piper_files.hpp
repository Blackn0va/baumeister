// Piper und Stimmen unter {data}\voice laden und prüfen – übernommen aus kraken/src/voice/files.* (dort gelaufen),
// gekürzt auf Piper und Stimmen.
//
// Muster wie beim Sprachmodell: URL festgenagelt, Größe und SHA-256 streamend geprüft, .part fortsetzbar, danach eine
// Prüfmarke „<Datei>.verified“ – nur mit Marke gilt eine Datei als vorhanden. Piper kommt als ZIP und wird mit dem
// tar.exe aus System32 entpackt, erst NACH bestandener SHA-256-Prüfung.
//
// Threads: download_piper blockiert (Arbeitsthread). Fehler: wirft win::HttpError/DownloadError/std::runtime_error.

#pragma once

#include "voice/piper_catalog.hpp"
#include "win/http.hpp"

#include <filesystem>

namespace swbh::voice {

struct VoicePaths {
    std::filesystem::path root;  // {data}\voice
    std::filesystem::path piper_exe() const;
    std::filesystem::path voice_model(const VoiceSpec& v) const;  // .onnx (die .json liegt daneben)
    std::filesystem::path work_dir() const;                        // WAV-Zwischendateien von Piper
};

bool piper_ready(const VoicePaths& p);
bool voice_ready(const VoicePaths& p, const VoiceSpec& v);

// Lädt Piper und die Stimme, soweit sie fehlen. progress(done, total) über alle Teile.
void download_piper(win::HttpTransport& http, const VoicePaths& p, const VoiceSpec& v,
                    const win::ProgressFn& progress, win::CancelToken& cancel);

}  // namespace swbh::voice
