#include "voice/tones.hpp"

#include "voice/audio.hpp"

#include <windows.h>

#include <mmsystem.h>

#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>

namespace swbh::voice {

namespace {

std::mutex& sound_mutex() {
    static std::mutex m;
    return m;
}

// Der Puffer, den PlaySound gerade abspielt – muss bis zum nächsten PlaySound-Aufruf leben.
std::string& playing() {
    static std::string s;
    return s;
}

// Ton mit kurzen Rampen (5 ms) gegen Knacken; zwei Frequenzen hintereinander ergeben „hoch“ bzw. „runter“.
std::string tone_wav(double f1, double f2, int ms, double volume) {
    Pcm pcm;
    pcm.rate = kMicRate;
    const int n = kMicRate * ms / 1000;
    const int ramp = kMicRate / 200;
    pcm.samples.resize(static_cast<std::size_t>(n));
    double phase = 0;
    for (int i = 0; i < n; ++i) {
        const double f = i < n / 2 ? f1 : f2;
        phase += 2.0 * 3.14159265358979 * f / kMicRate;
        double env = 1.0;
        if (i < ramp) {
            env = static_cast<double>(i) / ramp;
        } else if (i > n - ramp) {
            env = static_cast<double>(n - i) / ramp;
        }
        pcm.samples[static_cast<std::size_t>(i)] = static_cast<std::int16_t>(std::sin(phase) * env * volume * 32767);
    }
    return wav_bytes(pcm);
}

void play_bytes(std::string bytes) {
    std::lock_guard<std::mutex> lock(sound_mutex());
    PlaySoundW(nullptr, nullptr, 0);  // laufenden Ton beenden, erst dann den Puffer ersetzen
    playing() = std::move(bytes);
    PlaySoundW(reinterpret_cast<LPCWSTR>(playing().data()), nullptr, SND_MEMORY | SND_ASYNC | SND_NODEFAULT);
}

}  // namespace

void play_tone(Tone tone) {
    switch (tone) {
    case Tone::Start: play_bytes(tone_wav(660, 990, 70, 0.18)); break;
    case Tone::Stop: play_bytes(tone_wav(990, 660, 70, 0.18)); break;
    case Tone::Error: play_bytes(tone_wav(330, 220, 160, 0.2)); break;
    }
}

void play_pcm(std::span<const float> pcm) {
    Pcm p;
    p.rate = kMicRate;
    p.samples.reserve(pcm.size());
    for (const float s : pcm) {
        const float c = s < -1.0f ? -1.0f : s > 1.0f ? 1.0f : s;
        p.samples.push_back(static_cast<std::int16_t>(c * 32767));
    }
    play_bytes(wav_bytes(p));
}

void play_pcm(const Pcm& pcm) { play_bytes(wav_bytes(pcm)); }

}  // namespace swbh::voice
