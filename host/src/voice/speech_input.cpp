#include "voice/speech_input.hpp"

#include "voice/audio.hpp"
#include "voice/microphone.hpp"
#include "voice/recognizer.hpp"
#include "voice/tones.hpp"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <mutex>
#include <thread>

namespace swbh::voice {

namespace {

std::string fixed1(double v) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.1f", v);
    std::string s(buf);
    for (char& c : s) {
        if (c == '.') {
            c = ',';
        }
    }
    return s;
}

std::string signed_db(double v) {
    const long r = std::lround(v);
    return (r > 0 ? "+" : r < 0 ? "−" : "±") + std::to_string(r < 0 ? -r : r) + " dB";
}

std::string level_text(double dbfs) {
    const long r = std::lround(dbfs);
    return r < 0 ? "−" + std::to_string(-r) + " dBFS" : std::to_string(r) + " dBFS";
}

}  // namespace

void Utterance::append(std::span<const float> frames) {
    const std::size_t room = kMaxSamples > pcm_.size() ? kMaxSamples - pcm_.size() : 0;
    const std::size_t n = std::min(room, frames.size());
    pcm_.insert(pcm_.end(), frames.begin(), frames.begin() + static_cast<std::ptrdiff_t>(n));
}

TalkMode parse_talk_mode(std::string_view text) { return text == "umschalten" ? TalkMode::Toggle : TalkMode::Hold; }

struct SpeechInput::Impl {
    // Was der Arbeitsthread tun soll.
    struct Reply {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        std::optional<std::string> text;
        std::string error;
    };
    struct Job {
        enum class Kind { Recognize, Test, External } kind = Kind::Recognize;
        std::vector<float> pcm;
        // External: Ergebnis an den Wartenden (recognize). Gemeinsam besessen – läuft dessen Zeit ab, schreibt der
        // Arbeitsthread trotzdem gefahrlos hinein.
        std::shared_ptr<Reply> reply;
    };

    Options o;
    TextFn on_text;
    StatusFn on_status;
    std::unique_ptr<Recognizer> recognizer;
    std::unique_ptr<Microphone> mic;
    PushToTalk ptt;

    mutable std::mutex mutex;  // alles darunter
    std::condition_variable cv;
    Utterance utterance;
    bool recording = false;
    bool testing = false;
    bool stop_requested = false;  // Umschalten: Ende bei Stille, aus dem Mikrofon-Thread gemeldet
    EndDetector end;
    std::deque<Job> jobs;
    bool quit = false;
    SpeechStatus st;
    std::thread worker;
    std::thread test_thread;  // Mikrofontest; stop() wartet auf ihn (abgelöst griffe er nach einem Neustart ins Leere)
    std::atomic<bool> ready{false};

    void status(bool error, std::string text) {
        if (error) {
            std::lock_guard<std::mutex> lock(mutex);
            st.error = text;
        }
        if (on_status) {
            on_status(error, std::move(text));
        }
    }

    void set_state(SpeechState s) {
        std::lock_guard<std::mutex> lock(mutex);
        st.state = s;
    }

    // Mikrofon-Thread: anhängen, Pegel messen, bei „Umschalten“ das Ende bei Stille erkennen.
    void frames(std::span<const float> f) {
        const float r = rms(f);
        float peak = 0;
        bool at_limit = false;
        for (const float s : f) {
            peak = std::max(peak, std::fabs(s));
            at_limit = at_limit || std::fabs(s) >= 0.999f;
        }
        std::lock_guard<std::mutex> lock(mutex);
        if (!recording && !testing) {
            return;
        }
        st.level_dbfs = to_dbfs(r);
        st.peak_dbfs = std::max(st.peak_dbfs, to_dbfs(peak));
        st.clipping = st.clipping || at_limit;
        utterance.append(f);
        const bool auto_end = recording && o.mode == TalkMode::Toggle && end.feed(f);
        if ((auto_end || utterance.full()) && !stop_requested) {
            stop_requested = true;
            cv.notify_all();
        }
    }

    bool open_mic() {
        std::string error;
        if (!mic->start([this](std::span<const float> f) { frames(f); }, error)) {
            status(true, error);
            if (o.tones) {
                play_tone(Tone::Error);
            }
            return false;
        }
        return true;
    }

    void begin_recording() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (recording || testing) {
                return;
            }
            utterance.clear();
            end.reset();
            recording = true;
            stop_requested = false;
            st.state = SpeechState::Listening;
            st.peak_dbfs = -120;
            st.level_dbfs = -120;
            st.clipping = false;
        }
        if (o.tones) {
            play_tone(Tone::Start);
        }
        if (!open_mic()) {
            std::lock_guard<std::mutex> lock(mutex);
            recording = false;
            st.state = SpeechState::Ready;
        }
    }

    // Aus Hook-Thread (Loslassen, zweiter Druck) oder Arbeitsthread (Ende bei Stille).
    void end_recording() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!recording) {
                return;
            }
        }
        mic->stop();
        if (o.tones) {
            play_tone(Tone::Stop);
        }
        std::lock_guard<std::mutex> lock(mutex);
        recording = false;
        stop_requested = false;
        st.state = SpeechState::Ready;
        st.level_dbfs = -120;
        if (utterance.usable()) {
            jobs.push_back({Job::Kind::Recognize, utterance.pcm()});
            cv.notify_all();
        }
        utterance.clear();
    }

    // Bis wann die Taste ruht (steady_clock-Nanosekunden, pause_until); 0 = nie.
    std::atomic<std::int64_t> paused_until_ns{0};

    bool paused() const {
        return std::chrono::steady_clock::now().time_since_epoch().count() < paused_until_ns.load();
    }

    // Hook-Thread: kurz halten.
    void key(bool pressed) {
        if (pressed && paused()) {
            bool active = false;
            {
                std::lock_guard<std::mutex> lock(mutex);
                active = recording;
            }
            if (!active) {
                return;  // Im Spiel wird über Simple Voice Chat gehört – die App-Taste ruht (pause_until).
            }
        }
        if (o.mode == TalkMode::Hold) {
            pressed ? begin_recording() : end_recording();
            return;
        }
        if (!pressed) {
            return;  // Umschalten zählt nur das Drücken
        }
        bool active = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            active = recording;
        }
        active ? end_recording() : begin_recording();
    }

    // Aufbereiten, erkennen, melden. test: Ergebnis nur als Status, nicht an den Begleiter.
    void recognize(const std::vector<float>& pcm, bool test) {
        const Conditioned c = condition(pcm, o.gain_db, o.auto_gain);
        const std::string detail = "Pegel " + level_text(c.input_dbfs) +
                                   (o.auto_gain ? ", " + signed_db(c.auto_gain_db) + " automatisch" : std::string()) +
                                   ", " + fixed1(static_cast<double>(c.pcm.size()) / kMicRate) + " s" +
                                   (c.clipped > 0.005 ? ", ÜBERSTEUERT – Mikrofon leiser stellen" : std::string());
        if (c.silent) {
            status(false, std::string(test ? "Test: " : "") + "Nichts gehört – nur Stille (Spitze " +
                              level_text(to_dbfs(static_cast<float>(c.peak))) + "). Mikrofon und Verstärkung prüfen.");
            return;
        }
        set_state(SpeechState::Recognizing);
        const auto s0 = std::chrono::steady_clock::now();
        std::string err;
        const std::optional<std::string> raw = recognizer->transcribe(c.pcm, err);
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - s0);
        set_state(SpeechState::Ready);
        if (!raw) {
            status(true, err);
            return;
        }
        const std::string text = clean_transcript(*raw);
        {
            std::lock_guard<std::mutex> lock(mutex);
            st.last_detail = detail + ", erkannt in " + std::to_string(ms.count()) + " ms";
            if (!text.empty()) {
                st.last_text = text;
            }
        }
        if (test) {
            status(false, "Test: " + (text.empty() ? std::string("nichts verstanden") : "„" + text + "“") + " – " +
                              detail + ", erkannt in " + std::to_string(ms.count()) + " ms");
            return;
        }
        if (text.empty()) {
            status(false, "Nichts verstanden (" + detail + ")");
            return;
        }
        if (on_text) {
            on_text(text);
        }
    }

    // Ton aus dem Spiel: aufbereiten, erkennen, Text zurück (auch „“ für nichts verstanden).
    std::optional<std::string> recognize_external(const std::vector<float>& pcm, std::string& error) {
        const Conditioned c = condition(pcm, 0, o.auto_gain);
        if (c.silent) {
            return std::string();
        }
        set_state(SpeechState::Recognizing);
        const std::optional<std::string> raw = recognizer->transcribe(c.pcm, error);
        set_state(SpeechState::Ready);
        if (!raw) {
            return std::nullopt;
        }
        std::string text = clean_transcript(*raw);
        if (!text.empty()) {
            std::lock_guard<std::mutex> lock(mutex);
            st.last_text = text;
            st.last_detail = "aus dem Spiel, Pegel " + level_text(c.input_dbfs) + ", " +
                             fixed1(static_cast<double>(c.pcm.size()) / kMicRate) + " s";
        }
        return text;
    }

    void run() {
        Recognizer::Options ro;
        ro.model = o.model;
        ro.gpu = o.gpu;
        ro.gpu_device = o.gpu_device;
        ro.prompt = o.prompt;
        set_state(SpeechState::Loading);
        status(false, "Spracherkennung lädt …");
        const auto t0 = std::chrono::steady_clock::now();
        std::string error;
        recognizer = Recognizer::open(ro, error);
        if (!recognizer) {
            set_state(SpeechState::Error);
            status(true, error);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (quit) {
                return;  // während des Ladens abgeschaltet – keine Taste mehr einrichten
            }
        }
        mic = std::make_unique<Microphone>(find_input_device(input_devices(), o.microphone));
        if (!ptt.start(o.key, [this](bool pressed) { key(pressed); }, error)) {
            set_state(SpeechState::Error);
            status(true, error);
            return;
        }
        ready = true;
        set_state(SpeechState::Ready);
        const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        status(false, "Sprechen bereit (" + fixed1(secs) + " s geladen): " + talk_key_title(o.key) +
                          (o.mode == TalkMode::Hold ? " halten und sprechen"
                                                    : " drücken, sprechen – endet bei Stille von selbst"));
        while (true) {
            Job job;
            bool auto_stop = false;
            {
                std::unique_lock<std::mutex> lock(mutex);
                cv.wait(lock, [this] { return quit || !jobs.empty() || stop_requested; });
                if (quit) {
                    break;
                }
                if (stop_requested && recording) {
                    auto_stop = true;
                } else if (!jobs.empty()) {
                    job = std::move(jobs.front());
                    jobs.pop_front();
                } else {
                    stop_requested = false;
                    continue;
                }
            }
            if (auto_stop) {
                end_recording();  // Mikrofon schließen darf nicht im Mikrofon-Thread passieren (join auf sich selbst)
                continue;
            }
            if (job.kind == Job::Kind::External) {
                std::string err;
                std::optional<std::string> text = recognize_external(job.pcm, err);
                std::lock_guard<std::mutex> lock(job.reply->m);
                job.reply->text = std::move(text);
                job.reply->error = std::move(err);
                job.reply->done = true;
                job.reply->cv.notify_all();
                continue;
            }
            if (job.kind == Job::Kind::Test) {
                play_pcm(job.pcm);  // vorspielen, dann erkennen – beides zeigt, wie das Mikrofon klingt
                recognize(job.pcm, true);
            } else {
                recognize(job.pcm, false);
            }
        }
    }

    bool test(int seconds) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!ready || recording || testing) {
                return false;
            }
            testing = true;
            utterance.clear();
            st.state = SpeechState::Testing;
            st.peak_dbfs = -120;
            st.clipping = false;
        }
        if (test_thread.joinable()) {
            test_thread.join();  // voriger Test ist fertig (testing war false)
        }
        test_thread = std::thread([this, seconds] {
            if (open_mic()) {
                // In Scheiben warten, damit stop() einen laufenden Test nicht erst nach Sekunden beendet.
                const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
                while (std::chrono::steady_clock::now() < until) {
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        if (quit) {
                            break;
                        }
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                }
                mic->stop();
            }
            std::lock_guard<std::mutex> lock(mutex);
            testing = false;
            st.state = SpeechState::Ready;
            st.level_dbfs = -120;
            jobs.push_back({Job::Kind::Test, utterance.pcm()});
            utterance.clear();
            cv.notify_all();
        });
        return true;
    }
};

SpeechInput::SpeechInput() : impl_(std::make_unique<Impl>()) {}

SpeechInput::~SpeechInput() { stop(); }

void SpeechInput::start(Options options, TextFn on_text, StatusFn on_status) {
    stop();
    impl_ = std::make_unique<Impl>();
    impl_->o = std::move(options);
    impl_->on_text = std::move(on_text);
    impl_->on_status = std::move(on_status);
    Impl* raw = impl_.get();
    impl_->worker = std::thread([raw] { raw->run(); });
}

void SpeechInput::stop() {
    if (!impl_ || !impl_->worker.joinable()) {
        return;
    }
    impl_->ptt.stop();  // zuerst: kein neuer Tastendruck mehr
    if (impl_->mic) {
        impl_->mic->stop();
    }
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->quit = true;
        impl_->recording = false;
    }
    impl_->cv.notify_all();
    impl_->worker.join();
    if (impl_->test_thread.joinable()) {
        impl_->test_thread.join();
    }
    // Noch einmal: Hat der Arbeitsthread die Taste erst nach dem ersten stop() eingerichtet, hängt sie sonst weiter.
    impl_->ptt.stop();
    if (impl_->mic) {
        impl_->mic->stop();
    }
    impl_->ready = false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->st.state = SpeechState::Off;
}

bool SpeechInput::running() const { return impl_ && impl_->ready; }

SpeechStatus SpeechInput::status() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->st;
}

std::optional<std::string> SpeechInput::recognize(std::vector<float> pcm, std::string& error, int timeout_ms) {
    if (!impl_ || !impl_->ready) {
        error = "Spracherkennung ist nicht bereit (in der App „Mit dem Begleiter sprechen“ einschalten)";
        return std::nullopt;
    }
    auto reply = std::make_shared<Impl::Reply>();
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->jobs.push_back({Impl::Job::Kind::External, std::move(pcm), reply});
    }
    impl_->cv.notify_all();
    std::unique_lock<std::mutex> lock(reply->m);
    if (!reply->cv.wait_for(lock, std::chrono::milliseconds(timeout_ms), [&] { return reply->done; })) {
        error = "Spracherkennung zu langsam (über " + std::to_string(timeout_ms / 1000) + " s)";
        return std::nullopt;
    }
    error = reply->error;
    return reply->text;
}

void SpeechInput::pause_until(std::chrono::steady_clock::time_point until) {
    if (impl_) {
        impl_->paused_until_ns.store(std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                                         until.time_since_epoch()).count());
    }
}

bool SpeechInput::test_microphone(int seconds) { return impl_ && impl_->test(std::clamp(seconds, 1, 10)); }

}  // namespace swbh::voice
