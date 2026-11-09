#include "voice/signal.hpp"

#include "voice/audio.hpp"

#include <algorithm>
#include <cmath>

namespace swbh::voice {

namespace {

double from_db(double db) { return std::pow(10.0, db / 20.0); }

// Weicher Begrenzer: bis 0,9 linear, darüber sanft gegen 1 – lieber leicht gestaucht als hart abgeschnitten (hartes
// Abschneiden klingt für Whisper wie Knacken).
float soft_limit(float x) {
    const float a = std::fabs(x);
    if (a <= 0.9f) {
        return x;
    }
    const float y = 0.9f + 0.1f * std::tanh((a - 0.9f) / 0.1f);
    return x < 0 ? -y : y;
}

}  // namespace

double to_dbfs(float r) { return r <= 1e-6f ? -120.0 : 20.0 * std::log10(static_cast<double>(r)); }

Conditioned condition(std::span<const float> raw, double manual_db, bool agc) {
    Conditioned out;
    if (raw.empty()) {
        return out;
    }
    std::size_t at_limit = 0;
    for (const float s : raw) {
        out.peak = std::max(out.peak, static_cast<double>(std::fabs(s)));
        at_limit += std::fabs(s) >= 0.999f ? 1 : 0;
    }
    out.clipped = static_cast<double>(at_limit) / static_cast<double>(raw.size());

    const auto manual = static_cast<float>(from_db(std::clamp(manual_db, -20.0, 30.0)));
    std::vector<float> x(raw.begin(), raw.end());
    for (float& s : x) {
        s *= manual;
    }

    // Blockpegel; Grundrauschen = 10-%-Quantil, Sprache = deutlich darüber und über der festen Untergrenze.
    const std::size_t frames = x.size() / kFrame;
    std::vector<float> level(frames);
    for (std::size_t f = 0; f < frames; ++f) {
        level[f] = rms(std::span<const float>(x).subspan(f * kFrame, kFrame));
    }
    if (frames == 0) {
        return out;
    }
    std::vector<float> sorted = level;
    std::nth_element(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(frames / 10), sorted.end());
    const float noise = sorted[frames / 10];
    const float threshold = std::max(kSpeechFloor, noise * 3.0f);
    std::size_t first = frames;
    std::size_t last = 0;
    double speech_energy = 0;
    std::size_t speech_frames = 0;
    for (std::size_t f = 0; f < frames; ++f) {
        if (level[f] > threshold) {
            first = std::min(first, f);
            last = f;
            speech_energy += static_cast<double>(level[f]) * level[f];
            ++speech_frames;
        }
    }
    // Zwei Blöcke (40 ms) über der Schwelle sind ein Klick, keine Sprache.
    if (speech_frames < 3) {
        return out;
    }
    out.silent = false;
    out.input_dbfs = to_dbfs(static_cast<float>(std::sqrt(speech_energy / static_cast<double>(speech_frames))));

    // Ränder: 200 ms vor der ersten und 300 ms nach der letzten Sprache bleiben (Anlaute, auslaufende Silben).
    const std::size_t from = (first > 10 ? first - 10 : 0) * kFrame;
    const std::size_t to = std::min(x.size(), (last + 16) * kFrame);
    out.trimmed_ms = static_cast<double>(x.size() - (to - from)) / 16.0;

    double gain_db = 0;
    if (agc) {
        gain_db = std::clamp(kTargetDbfs - out.input_dbfs, kMinAutoGainDb, kMaxAutoGainDb);
    }
    out.auto_gain_db = gain_db;
    const auto g = static_cast<float>(from_db(gain_db));
    out.pcm.reserve(to - from);
    for (std::size_t i = from; i < to; ++i) {
        out.pcm.push_back(soft_limit(x[i] * g));
    }
    return out;
}

bool EndDetector::feed(std::span<const float> samples) {
    pending_.insert(pending_.end(), samples.begin(), samples.end());
    bool done = false;
    std::size_t used = 0;
    while (pending_.size() - used >= static_cast<std::size_t>(kFrame)) {
        const float r = rms(std::span<const float>(pending_).subspan(used, kFrame));
        used += kFrame;
        // Grundrauschen der ersten 10 Blöcke (200 ms) als Bezug – vor dem ersten Wort ist es meist still.
        if (seen_ < 10) {
            floor_ = seen_ == 0 ? r : std::min(floor_, r);
            ++seen_;
        }
        const float threshold = std::max(kSpeechFloor, floor_ * 3.0f);
        if (r > threshold) {
            ++speech_frames_;
            quiet_ = 0;
        } else if (heard_speech()) {
            ++quiet_;
            done = done || quiet_ >= silence_frames_;
        }
    }
    pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(used));
    return done;
}

void EndDetector::reset() {
    pending_.clear();
    speech_frames_ = 0;
    quiet_ = 0;
    floor_ = 0;
    seen_ = 0;
}

}  // namespace swbh::voice
