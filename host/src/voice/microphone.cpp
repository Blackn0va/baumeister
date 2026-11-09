#include "voice/microphone.hpp"

#include "base/utf8.hpp"
#include "voice/audio.hpp"

#include <windows.h>

#include <mmsystem.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <thread>

namespace swbh::voice {

namespace {

WAVEFORMATEX pcm16_mono(int rate) {
    WAVEFORMATEX f{};
    f.wFormatTag = WAVE_FORMAT_PCM;
    f.nChannels = 1;
    f.nSamplesPerSec = static_cast<DWORD>(rate);
    f.wBitsPerSample = 16;
    f.nBlockAlign = 2;
    f.nAvgBytesPerSec = f.nSamplesPerSec * 2;
    return f;
}

}  // namespace

struct Microphone::Impl {
    struct Buffer {
        std::vector<std::int16_t> data;
        WAVEHDR hdr{};
    };

    int device;
    HWAVEIN in = nullptr;
    HANDLE event = nullptr;
    std::array<Buffer, 4> buffers;
    std::atomic<bool> running{false};
    std::thread thread;
    FramesFn on_frames;

    // Puffer in Abgabereihenfolge abholen (Ring), nicht in Feldreihenfolge: WinMM füllt sie der Reihe nach, und nach
    // dem ersten Umlauf ergäbe die Feldreihenfolge vertauschte 50-ms-Stücke (kraken, gemessen).
    void loop() {
        std::vector<float> f;
        std::size_t next = 0;
        while (running) {
            WaitForSingleObject(event, 200);
            while (running && (buffers[next].hdr.dwFlags & WHDR_DONE) != 0) {
                Buffer& b = buffers[next];
                const std::size_t n = b.hdr.dwBytesRecorded / sizeof(std::int16_t);
                f.resize(n);
                for (std::size_t i = 0; i < n; ++i) {
                    f[i] = static_cast<float>(b.data[i]) / 32768.0f;
                }
                if (n > 0 && on_frames) {
                    on_frames(f);
                }
                b.hdr.dwFlags &= ~static_cast<DWORD>(WHDR_DONE);
                waveInAddBuffer(in, &b.hdr, sizeof(WAVEHDR));
                next = (next + 1) % buffers.size();
            }
        }
    }
};

Microphone::Microphone(int device_id) : impl_(std::make_unique<Impl>()) { impl_->device = device_id; }

Microphone::~Microphone() { stop(); }

bool Microphone::start(FramesFn on_frames, std::string& error) {
    stop();
    Impl& m = *impl_;
    m.on_frames = std::move(on_frames);
    m.event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    WAVEFORMATEX f = pcm16_mono(kMicRate);
    const UINT dev = m.device < 0 ? WAVE_MAPPER : static_cast<UINT>(m.device);
    const MMRESULT r = waveInOpen(&m.in, dev, &f, reinterpret_cast<DWORD_PTR>(m.event), 0, CALLBACK_EVENT);
    if (r != MMSYSERR_NOERROR) {
        error = "Mikrofon lässt sich nicht öffnen (WinMM-Fehler " + std::to_string(r) +
                ") – unter Windows den Mikrofonzugriff für Desktop-Apps erlauben";
        CloseHandle(m.event);
        m.event = nullptr;
        m.in = nullptr;
        return false;
    }
    // 4 Puffer à 50 ms: kurz genug, dass nach dem Loslassen der Taste wenig fehlt, lang genug gegen Aussetzer.
    for (Impl::Buffer& b : m.buffers) {
        b.data.assign(kMicRate / 20, 0);
        b.hdr = WAVEHDR{};
        b.hdr.lpData = reinterpret_cast<LPSTR>(b.data.data());
        b.hdr.dwBufferLength = static_cast<DWORD>(b.data.size() * sizeof(std::int16_t));
        waveInPrepareHeader(m.in, &b.hdr, sizeof(WAVEHDR));
        waveInAddBuffer(m.in, &b.hdr, sizeof(WAVEHDR));
    }
    m.running = true;
    waveInStart(m.in);
    Impl* raw = impl_.get();
    m.thread = std::thread([raw] { raw->loop(); });
    return true;
}

void Microphone::stop() {
    Impl& m = *impl_;
    if (!m.running.exchange(false)) {
        return;
    }
    SetEvent(m.event);
    if (m.thread.joinable()) {
        m.thread.join();
    }
    waveInReset(m.in);
    for (Impl::Buffer& b : m.buffers) {
        waveInUnprepareHeader(m.in, &b.hdr, sizeof(WAVEHDR));
    }
    waveInClose(m.in);
    m.in = nullptr;
    CloseHandle(m.event);
    m.event = nullptr;
}

std::vector<InputDevice> input_devices() {
    std::vector<InputDevice> out;
    const UINT n = waveInGetNumDevs();
    for (UINT i = 0; i < n; ++i) {
        WAVEINCAPSW caps{};
        if (waveInGetDevCapsW(i, &caps, sizeof caps) == MMSYSERR_NOERROR) {
            out.push_back({static_cast<int>(i), narrow(caps.szPname)});
        }
    }
    return out;
}

int find_input_device(const std::vector<InputDevice>& devices, const std::string& name) {
    if (name.empty()) {
        return -1;
    }
    for (const InputDevice& d : devices) {
        if (d.name == name || (!d.name.empty() && name.starts_with(d.name))) {
            return d.id;
        }
    }
    return -1;
}

}  // namespace swbh::voice
