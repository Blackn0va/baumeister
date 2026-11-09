#include "voice/audio.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace swbh::voice {

namespace {

std::uint32_t u32(std::string_view b, std::size_t at) {
    std::uint32_t v = 0;
    std::memcpy(&v, b.data() + at, 4);
    return v;
}

std::uint16_t u16(std::string_view b, std::size_t at) {
    std::uint16_t v = 0;
    std::memcpy(&v, b.data() + at, 2);
    return v;
}

}  // namespace

std::string wav_bytes(const Pcm& pcm) {
    const auto data = static_cast<std::uint32_t>(pcm.samples.size() * 2);
    std::string out;
    out.reserve(44 + data);
    const auto u32 = [&](std::uint32_t v) {
        for (int i = 0; i < 4; ++i) {
            out += static_cast<char>((v >> (8 * i)) & 0xFF);
        }
    };
    const auto u16 = [&](std::uint16_t v) {
        out += static_cast<char>(v & 0xFF);
        out += static_cast<char>(v >> 8);
    };
    out += "RIFF";
    u32(36 + data);
    out += "WAVEfmt ";
    u32(16);
    u16(1);  // PCM
    u16(1);  // mono
    u32(static_cast<std::uint32_t>(pcm.rate));
    u32(static_cast<std::uint32_t>(pcm.rate) * 2);
    u16(2);
    u16(16);
    out += "data";
    u32(data);
    for (std::int16_t s : pcm.samples) {
        u16(static_cast<std::uint16_t>(s));
    }
    return out;
}

std::optional<Pcm> parse_wav(std::string_view b, std::string& error) {
    if (b.size() < 12 || b.substr(0, 4) != "RIFF" || b.substr(8, 4) != "WAVE") {
        error = "keine WAV-Datei";
        return std::nullopt;
    }
    int channels = 0;
    int rate = 0;
    int bits = 0;
    std::size_t pos = 12;
    while (pos + 8 <= b.size()) {
        const std::string_view id = b.substr(pos, 4);
        const std::uint32_t len = u32(b, pos + 4);
        const std::size_t body = pos + 8;
        if (len > b.size() - body) {
            // Piper schreibt die Länge korrekt; ein abgeschnittener data-Block wird bis zum Dateiende gelesen.
            if (id != "data") {
                error = "WAV-Block zu lang";
                return std::nullopt;
            }
        }
        if (id == "fmt ") {
            if (len < 16) {
                error = "fmt-Block zu kurz";
                return std::nullopt;
            }
            const std::uint16_t format = u16(b, body);
            channels = u16(b, body + 2);
            rate = static_cast<int>(u32(b, body + 4));
            bits = u16(b, body + 14);
            if (format != 1 || bits != 16 || channels < 1 || channels > 8 || rate < 4000 || rate > 192000) {
                error = "nur PCM 16 bit wird unterstützt";
                return std::nullopt;
            }
        } else if (id == "data") {
            if (channels == 0) {
                error = "data vor fmt";
                return std::nullopt;
            }
            const std::size_t n = std::min<std::size_t>(len, b.size() - body) / 2;
            const std::size_t frames = n / static_cast<std::size_t>(channels);
            Pcm pcm;
            pcm.rate = rate;
            pcm.samples.resize(frames);
            for (std::size_t f = 0; f < frames; ++f) {
                int sum = 0;
                for (int c = 0; c < channels; ++c) {
                    sum += static_cast<std::int16_t>(u16(b, body + (f * static_cast<std::size_t>(channels) + c) * 2));
                }
                pcm.samples[f] = static_cast<std::int16_t>(sum / channels);
            }
            return pcm;
        }
        pos = body + len + (len & 1u);  // Blöcke sind auf gerade Länge aufgefüllt
    }
    error = "kein data-Block";
    return std::nullopt;
}

std::vector<float> to_float(std::span<const std::int16_t> in) {
    std::vector<float> out(in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        out[i] = static_cast<float>(in[i]) / 32768.0f;
    }
    return out;
}

std::vector<float> resample(std::span<const float> in, int from, int to) {
    if (from <= 0 || to <= 0 || in.empty() || from == to) {
        return std::vector<float>(in.begin(), in.end());
    }
    const double step = static_cast<double>(from) / to;
    const auto n = static_cast<std::size_t>(static_cast<double>(in.size()) / step);
    std::vector<float> out(n);
    for (std::size_t i = 0; i < n; ++i) {
        const double pos = static_cast<double>(i) * step;
        if (step > 1.0) {
            // Mittel über das Quellfenster dieses Ausgabewerts.
            const auto a = static_cast<std::size_t>(pos);
            const auto b = std::min(in.size(), static_cast<std::size_t>(pos + step) + 1);
            double sum = 0.0;
            for (std::size_t k = a; k < b; ++k) {
                sum += in[k];
            }
            out[i] = static_cast<float>(sum / static_cast<double>(b - a));
        } else {
            const auto a = static_cast<std::size_t>(pos);
            const std::size_t b = std::min(in.size() - 1, a + 1);
            const double t = pos - static_cast<double>(a);
            out[i] = static_cast<float>(in[a] * (1.0 - t) + in[b] * t);
        }
    }
    return out;
}

float db_to_gain(double db) { return static_cast<float>(std::pow(10.0, db / 20.0)); }

void apply_gain(std::span<float> frame, float gain) {
    if (gain == 1.0f) {
        return;
    }
    for (float& s : frame) {
        s = std::clamp(s * gain, -1.0f, 1.0f);
    }
}

float rms(std::span<const float> frame) {
    if (frame.empty()) {
        return 0.0f;
    }
    double sum = 0.0;
    for (float s : frame) {
        sum += static_cast<double>(s) * s;
    }
    return static_cast<float>(std::sqrt(sum / static_cast<double>(frame.size())));
}

}  // namespace swbh::voice
