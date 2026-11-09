// Mikrofon über WinMM (waveIn), 16 kHz mono – übernommen aus kraken/src/voice/audio_io.* (WaveInput, dort gelaufen).
//
// Warum WinMM: Teil von Windows, der Wave-Mapper wandelt das Format selbst (Mikrofon direkt als 16 kHz mono für
// Whisper), keine weiteren DLLs im Setup (Qt Multimedia brächte drei samt Hash-Pflege) und läuft ohne GUI im Test.
//
// Threads: eigener Arbeitsthread; der Rückruf läuft dort – kurz halten, keine GUI-Aufrufe.
// Fehler: start liefert false und einen deutschen Satz (z. B. Mikrofonzugriff in Windows gesperrt).

#pragma once

#include <functional>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace swbh::voice {

struct InputDevice {
    int id = -1;  // -1 = Windows-Standardgerät (Wave-Mapper)
    std::string name;
};

std::vector<InputDevice> input_devices();
// Gerät per Name (Teilübereinstimmung, WinMM kürzt Namen auf 31 Zeichen); leer/unbekannt = Standard (-1).
int find_input_device(const std::vector<InputDevice>& devices, const std::string& name);

class Microphone final {
public:
    using FramesFn = std::function<void(std::span<const float>)>;  // 16 kHz mono, -1 … 1, Stücke à 50 ms

    explicit Microphone(int device_id);
    ~Microphone();
    Microphone(const Microphone&) = delete;
    Microphone& operator=(const Microphone&) = delete;

    bool start(FramesFn on_frames, std::string& error);
    void stop();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
