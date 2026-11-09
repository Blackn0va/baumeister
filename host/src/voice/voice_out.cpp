#include "voice/voice_out.hpp"

#include "base/log.hpp"
#include "voice/piper.hpp"
#include "voice/speech_text.hpp"
#include "voice/tts_sapi.hpp"

#include <algorithm>
#include <cmath>

namespace swbh::voice {

VoiceChoice parse_voice_choice(std::string_view text) {
    if (text.rfind("sapi:", 0) == 0) {
        return {false, std::string(text.substr(5))};
    }
    const std::string_view key = text.rfind("piper:", 0) == 0 ? text.substr(6) : std::string_view();
    return {true, voice_or_default(key).key};
}

Pcm with_pitch(Pcm pcm, double pitch) {
    if (pitch <= 1.0 || pcm.rate <= 0 || pcm.samples.empty()) {
        return pcm;
    }
    pcm.rate = static_cast<int>(std::lround(pcm.rate * pitch));
    return pcm;
}

std::vector<std::int16_t> for_voicechat(const Pcm& pcm) {
    std::vector<std::int16_t> out;
    if (pcm.rate <= 0 || pcm.samples.empty()) {
        return out;
    }
    const std::vector<float> f = resample(to_float(pcm.samples), pcm.rate, kVoicechatRate);
    out.reserve(f.size());
    for (const float s : f) {
        out.push_back(static_cast<std::int16_t>(std::clamp(s, -1.0f, 1.0f) * 32767.0f));
    }
    return out;
}

struct VoiceOut::Impl {
    std::mutex mutex;
    VoicePaths paths;
    VoiceChoice choice;
    Piper piper;
    std::string piper_key;  // Stimme, mit der piper läuft
    double piper_pitch = 0;  // Tonhöhe, für die piper läuft (length_scale hängt daran)
};

VoiceOut::VoiceOut() : impl_(std::make_unique<Impl>()) {}

VoiceOut::~VoiceOut() = default;

void VoiceOut::configure(const VoicePaths& paths, const VoiceChoice& choice) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->paths = paths;
    impl_->choice = choice;
    if (!choice.piper || impl_->piper_key != choice.key || impl_->piper_pitch != choice.pitch) {
        impl_->piper.stop();
        impl_->piper_key.clear();
    }
}

bool VoiceOut::needs_download() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->choice.piper &&
           (!piper_ready(impl_->paths) || !voice_ready(impl_->paths, voice_or_default(impl_->choice.key)));
}

std::optional<Pcm> VoiceOut::synth(std::string_view original, std::string& error, std::string* used) {
    // Fürs Ohr aufbereitet (Uhrzeiten, Rechenzeichen, Minecraft-Englisch, Abkürzungen) – gemessen, siehe
    // speech_text.hpp. Wer das herausnimmt, hört wieder „sechs Uhr null null Uhr“ und „Kreper“.
    const std::string spoken = for_speech(original);
    const std::string_view text = spoken;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Impl& m = *impl_;
    if (m.choice.piper) {
        const VoiceSpec& v = voice_or_default(m.choice.key);
        if (piper_ready(m.paths) && voice_ready(m.paths, v)) {
            if (!m.piper.running()) {
                PiperOptions o;
                // Fröhlicher (Wunsch 2026-11-04):
                // 8 % schneller und mehr Melodie als die Vorgabe der Stimmen (1,0 / 0,667 / 0,8). Nicht gemessen,
                // nur nach Gehör der Piper-Doku gewählt – wer es leiernd oder hastig findet, dreht hier, nicht im
                // Modell. Über 0,9 bei noise_scale wird die Aussprache nach Piper-Erfahrung unsauber.
                // × pitch: Die höhere Wiedergabe (with_pitch) beschleunigt um denselben Faktor – so bleibt das Tempo.
                o.length_scale = static_cast<float>(0.92 * std::max(1.0, m.choice.pitch));
                o.noise_scale = 0.8f;
                o.noise_w = 0.9f;
                o.exe = m.paths.piper_exe();
                o.model = m.paths.voice_model(v);
                o.work_dir = m.paths.work_dir();
                std::string why;
                if (m.piper.start(o, why)) {
                    m.piper_key = v.key;
                    m.piper_pitch = m.choice.pitch;
                } else {
                    log::warn("Piper startet nicht, Windows-Stimme springt ein: " + why);
                }
            }
            if (m.piper.running()) {
                std::string why;
                if (auto pcm = m.piper.synth(text, why)) {
                    if (used != nullptr) {
                        *used = "Piper " + v.title;
                    }
                    return with_pitch(std::move(*pcm), m.choice.pitch);
                }
                log::warn("Piper ohne Ton, Windows-Stimme springt ein: " + why);
                m.piper.stop();
            }
        }
    }
    // Rückfall (oder bewusst gewählt): Windows-Stimme.
    auto pcm = sapi_synth(text, m.choice.piper ? std::string_view() : std::string_view(m.choice.key), error);
    if (pcm && used != nullptr) {
        *used = "Windows-Stimme";
    }
    return pcm;
}

}  // namespace swbh::voice
