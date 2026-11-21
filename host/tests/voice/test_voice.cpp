// Sprechen mit dem Begleiter: Haltetaste, Aufnahme, Aufräumen der Erkennung, Modellwerte – und die echte Erkennung
// einer deutschen Testaufnahme, wenn das Modell da ist (SWBH_WHISPER_MODEL, 574 MB; sonst übersprungen).

#include "test_support.hpp"

#include "base/json.hpp"
#include "voice/audio.hpp"
#include "voice/push_to_talk.hpp"
#include "voice/recognizer.hpp"
#include "voice/signal.hpp"
#include "voice/speech_input.hpp"

#include <windows.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <thread>
#include <string>
#include <vector>

using namespace swbh::voice;

TEST_CASE("Sprechen: Haltetaste aus der Einstellung, Vorgabe hintere Maus-Seitentaste") {
    const auto def = parse_talk_key(kDefaultTalkKey);
    REQUIRE(def.has_value());
    CHECK(def->kind == TalkKey::Kind::Mouse);
    CHECK(def->code == 1);
    CHECK(talk_key_title(*def) == "Maus-Seitentaste hinten");
    CHECK(parse_talk_key("Maus5")->code == 2);
    CHECK(parse_talk_key("mausmitte")->code == 3);
    const auto v = parse_talk_key(" v ");
    REQUIRE(v.has_value());
    CHECK(v->kind == TalkKey::Kind::Keyboard);
    CHECK(v->code == 'V');
    CHECK(talk_key_title(*v) == "Taste V");
    CHECK(parse_talk_key("f8")->code == VK_F8);
    CHECK(talk_key_title(*parse_talk_key("F8")) == "F8");
    CHECK_FALSE(parse_talk_key("f25").has_value());
    CHECK_FALSE(parse_talk_key("strg").has_value());
    CHECK_FALSE(parse_talk_key("").has_value());
}

TEST_CASE("Sprechen: gehaltene Tastatur-Taste wiederholt sich, zählt aber nur einmal") {
    PressEdge e;
    CHECK(e.feed(true) == std::optional<bool>(true));
    CHECK_FALSE(e.feed(true).has_value());  // Tastenwiederholung beim Halten
    CHECK_FALSE(e.feed(true).has_value());
    CHECK(e.feed(false) == std::optional<bool>(false));
    CHECK_FALSE(e.feed(false).has_value());
}

TEST_CASE("Sprechen: zu kurz geht nicht an die Erkennung, über 30 s wird gekappt") {
    Utterance u;
    const std::vector<float> loud(4000, 0.2f);
    u.append(loud);
    CHECK_FALSE(u.usable());  // 0,25 s
    u.append(loud);
    CHECK(u.usable());
    Utterance long_one;
    for (int i = 0; i < 40; ++i) {
        long_one.append(std::vector<float>(16000, 0.1f));
    }
    CHECK(long_one.pcm().size() == Utterance::kMaxSamples);
    CHECK(long_one.full());
}

namespace {

// 1 s Rauschen (−50 dBFS), dazwischen „Sprache“ als Sinus mit amplitude, danach wieder 1 s Rauschen.
std::vector<float> speech_in_noise(float amplitude, double seconds = 1.0) {
    std::vector<float> out;
    unsigned seed = 1;
    const auto noise = [&] {
        seed = seed * 1103515245u + 12345u;
        return (static_cast<float>((seed >> 16) & 0x7FFF) / 16384.0f - 1.0f) * 0.004f;
    };
    for (int i = 0; i < 16000; ++i) {
        out.push_back(noise());
    }
    for (int i = 0; i < static_cast<int>(16000 * seconds); ++i) {
        out.push_back(amplitude * std::sin(static_cast<float>(i) * 0.1f) + noise());
    }
    for (int i = 0; i < 16000; ++i) {
        out.push_back(noise());
    }
    return out;
}

}  // namespace

TEST_CASE("Mikrofon: leise Sprache wird auf etwa −20 dBFS angehoben, Stille an den Rändern fällt weg") {
    const auto raw = speech_in_noise(0.01f);  // ≈ −43 dBFS
    const Conditioned c = condition(raw, 0, true);
    REQUIRE_FALSE(c.silent);
    CHECK(c.input_dbfs < -40);
    CHECK(c.auto_gain_db > 20);
    CHECK(to_dbfs(rms(c.pcm)) > -24);
    CHECK(to_dbfs(rms(c.pcm)) < -16);
    // 3 s Aufnahme, 1 s Sprache: höchstens Sprache + 0,2 s vorn + 0,3 s hinten bleiben.
    CHECK(c.pcm.size() <= 16000 + 3200 + 4800 + 2 * kFrame);
    CHECK(c.trimmed_ms > 1400);
}

TEST_CASE("Mikrofon: ohne Automatik bleibt der Pegel, Verstärkung von Hand wirkt in dB") {
    const auto raw = speech_in_noise(0.05f);
    const Conditioned plain = condition(raw, 0, false);
    const Conditioned plus6 = condition(raw, 6, false);
    REQUIRE_FALSE(plain.silent);
    CHECK(plain.auto_gain_db == 0);
    CHECK(std::fabs(to_dbfs(rms(plus6.pcm)) - to_dbfs(rms(plain.pcm)) - 6.0) < 0.5);
}

TEST_CASE("Mikrofon: Stille und ein einzelner Klick gelten nicht als Sprache") {
    CHECK(condition(std::vector<float>(48000, 0.0005f), 0, true).silent);
    std::vector<float> click(48000, 0.0005f);
    for (int i = 24000; i < 24000 + 300; ++i) {
        click[static_cast<std::size_t>(i)] = 0.8f;
    }
    CHECK(condition(click, 0, true).silent);
    CHECK(condition(std::vector<float>{}, 0, true).silent);
}

TEST_CASE("Mikrofon: Übersteuerung wird gemessen, der Begrenzer hält die Ausgabe unter 1") {
    auto raw = speech_in_noise(1.2f);  // weit über dem Anschlag
    for (float& s : raw) {
        s = std::clamp(s, -1.0f, 1.0f);
    }
    const Conditioned c = condition(raw, 0, true);
    CHECK(c.clipped > 0.005);
    for (const float s : c.pcm) {
        REQUIRE(std::fabs(s) <= 1.0f);
    }
    // Hier braucht es den Begrenzer wirklich: +30 dB von Hand ohne Automatik auf normale Sprache.
    const Conditioned hot = condition(speech_in_noise(0.1f), 30, false);
    REQUIRE_FALSE(hot.silent);
    for (const float s : hot.pcm) {
        REQUIRE(std::fabs(s) <= 1.0f);
    }
}

TEST_CASE("Mikrofon: Umschalten endet erst nach Sprache und 1,2 s Stille") {
    EndDetector d(1200);
    const std::vector<float> quiet(320, 0.001f);
    std::vector<float> voice(320);
    for (int i = 0; i < 320; ++i) {
        voice[static_cast<std::size_t>(i)] = 0.1f * std::sin(static_cast<float>(i) * 0.2f);
    }
    for (int i = 0; i < 100; ++i) {
        REQUIRE_FALSE(d.feed(quiet));  // 2 s Stille vor dem ersten Wort: kein Ende
    }
    for (int i = 0; i < 25; ++i) {
        REQUIRE_FALSE(d.feed(voice));
    }
    CHECK(d.heard_speech());
    int frames = 0;
    while (!d.feed(quiet)) {
        ++frames;
        REQUIRE(frames < 200);
    }
    CHECK(frames + 1 == 60);  // 60 × 20 ms = 1,2 s
}

TEST_CASE("Mikrofon: Bedienart aus der Einstellung") {
    CHECK(parse_talk_mode("umschalten") == TalkMode::Toggle);
    CHECK(parse_talk_mode("halten") == TalkMode::Hold);
    CHECK(parse_talk_mode("quatsch") == TalkMode::Hold);
}

// Echter Hook mit simulierter Taste (SendInput): F24 belegt keine Anwendung. Die Maus-Seitentaste wird bewusst nicht
// simuliert – „Zurück“ löste im gerade aktiven Fenster etwas aus.
TEST_CASE("Sprechen: Haltetaste meldet Drücken und Loslassen genau einmal, auch bei Tastenwiederholung") {
    std::mutex m;
    std::vector<bool> seen;
    PushToTalk ptt;
    std::string error;
    REQUIRE_MESSAGE(ptt.start(*parse_talk_key("f24"), [&](bool pressed) {
        std::lock_guard<std::mutex> lock(m);
        seen.push_back(pressed);
    }, error), error);
    const auto send = [](bool down) {
        INPUT in{};
        in.type = INPUT_KEYBOARD;
        in.ki.wVk = VK_F24;
        in.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
        SendInput(1, &in, sizeof in);
    };
    send(true);
    send(true);  // Wiederholung wie beim Halten
    send(true);
    send(false);
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < until) {
        {
            std::lock_guard<std::mutex> lock(m);
            if (seen.size() >= 2) {
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    ptt.stop();
    std::lock_guard<std::mutex> lock(m);
    CHECK(seen == std::vector<bool>{true, false});
}

TEST_CASE("Sprechen: Erfindungen auf Stille werden verworfen, echte Sätze bleiben") {
    CHECK(clean_transcript("  Hey Vera, komm mal her.  ") == "Hey Vera, komm mal her.");
    CHECK(clean_transcript(" [Musik]").empty());
    CHECK(clean_transcript("(Applaus)").empty());
    CHECK(clean_transcript("Untertitel der Amara.org-Community").empty());
    CHECK(clean_transcript("Untertitel im Auftrag des ZDF, 2020").empty());
    CHECK(clean_transcript("Vielen Dank.").empty());
    CHECK(clean_transcript("Vielen Dank, Vera!") == "Vielen Dank, Vera!");
    CHECK(clean_transcript(" Tschüss.") == "Tschüss.");  // Steuerwort für den Begleiter, keine Erfindung
}

TEST_CASE("Sprechen: Vortext nennt den Namen des Begleiters zuerst") {
    const std::string p = recognizer_prompt("Max");
    CHECK(p.rfind("Hey Max,", 0) == 0);
    CHECK(p.find("Deutsch") != std::string::npos);
    CHECK(recognizer_prompt("").rfind("Hey Vera,", 0) == 0);
    CHECK(p.find("Brot") != std::string::npos);  // 2026-11-04 als „Baut“ verhört
}

TEST_CASE("Sprechen: Modellwerte im Code und in deps.lock.json stimmen überein") {
    const swbh::Json lock = swbh::parse_utf8(swbh::test::read_bytes(swbh::test::source_path("deps.lock.json")));
    const swbh::Json& w = lock["whisper_model"];
    const auto spec = whisper_model_spec();
    CHECK(w["repo"] == spec.repo);
    CHECK(w["revision"] == spec.revision);
    CHECK(w["file"] == spec.file);
    CHECK(w["size"] == spec.size);
    CHECK(w["sha256"] == spec.sha256);
}

TEST_CASE("Sprechen: echte Erkennung einer deutschen Aufnahme (nur mit SWBH_WHISPER_MODEL)") {
    wchar_t buf[1024]{};
    const DWORD n = GetEnvironmentVariableW(L"SWBH_WHISPER_MODEL", buf, 1024);
    const std::filesystem::path env = n > 0 && n < 1024 ? std::filesystem::path(buf) : std::filesystem::path();
    if (env.empty() || !std::filesystem::is_regular_file(env)) {
        MESSAGE("SWBH_WHISPER_MODEL nicht gesetzt – Erkennung übersprungen (574-MB-Modell)");
        return;
    }
    std::string error;
    const auto wav = parse_wav(swbh::test::read_bytes(swbh::test::source_path("tests/voice/data/vera_fackeln.wav")),
                               error);
    REQUIRE_MESSAGE(wav.has_value(), error);
    REQUIRE(wav->rate == kMicRate);
    Recognizer::Options o;
    o.model = env;
    o.gpu = true;
    o.prompt = recognizer_prompt("Vera");
    const auto t0 = std::chrono::steady_clock::now();
    auto rec = Recognizer::open(o, error);
    REQUIRE_MESSAGE(rec != nullptr, error);
    const auto t1 = std::chrono::steady_clock::now();
    const auto pcm = to_float(wav->samples);
    const auto text = rec->transcribe(pcm, error);
    const auto t2 = std::chrono::steady_clock::now();
    REQUIRE_MESSAGE(text.has_value(), error);
    std::string lower = clean_transcript(*text);
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    MESSAGE("erkannt: „" << *text << "“ – laden "
                         << std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count() << " ms, erkennen "
                         << std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count() << " ms für "
                         << wav->seconds() << " s");
    CHECK(lower.find("vera") != std::string::npos);
    CHECK(lower.find("fackeln") != std::string::npos);
}

namespace {

// Ersatz für whisper (SpeechInput::Options::open_recognizer): „erkennt“ immer den Vortext, mit dem er gerade läuft.
// So zeigt die Antwort von recognize(), welchen Vortext die echte Erkennung an dieser Stelle bekommen hätte.
class EchoTranscriber final : public Transcriber {
public:
    explicit EchoTranscriber(std::string prompt) : prompt_(std::move(prompt)) {}

    std::optional<std::string> transcribe(std::span<const float>, std::string&) override {
        std::lock_guard<std::mutex> lock(mutex_);
        return prompt_;
    }

    void set_prompt(std::string prompt) override {
        std::lock_guard<std::mutex> lock(mutex_);
        prompt_ = std::move(prompt);
    }

private:
    std::mutex mutex_;
    std::string prompt_;
};

// Was beim Öffnen ankam; hold hält das Öffnen an wie whisper beim Laden der 574 MB.
struct OpenLog {
    std::mutex m;
    std::condition_variable cv;
    int opened = 0;
    std::string prompt;
    bool hold = false;
    bool entered = false;
    std::string last_status;
};

// Gibt ein angehaltenes Öffnen frei, auch wenn eine Prüfung vorher abbricht – sonst wartete stop() ewig darauf.
struct ReleaseOpen {
    std::shared_ptr<OpenLog> log;
    ~ReleaseOpen() {
        {
            std::lock_guard<std::mutex> lock(log->m);
            log->hold = false;
        }
        log->cv.notify_all();
    }
};

void start_echo(SpeechInput& s, const std::shared_ptr<OpenLog>& log) {
    SpeechInput::Options o;
    o.key = *parse_talk_key("f24");  // F24 belegt keine Anwendung (wie der Haltetasten-Test oben)
    o.tones = false;
    o.open_recognizer = [log](const Recognizer::Options& ro, std::string&) -> std::unique_ptr<Transcriber> {
        std::unique_lock<std::mutex> lock(log->m);
        ++log->opened;
        log->prompt = ro.prompt;
        log->entered = true;
        log->cv.notify_all();
        log->cv.wait(lock, [&log] { return !log->hold; });
        return std::make_unique<EchoTranscriber>(ro.prompt);
    };
    s.start(std::move(o), [](std::string) {}, [log](bool, std::string text) {
        std::lock_guard<std::mutex> lock(log->m);
        log->last_status = std::move(text);
    });
}

bool wait_running(const SpeechInput& s) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (!s.running()) {
        if (std::chrono::steady_clock::now() > until) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return true;
}

// Satz aus dem Spiel erkennen lassen (derselbe Arbeitsthread wie das Mikrofon) – beim Echo-Ersatz kommt der Vortext.
std::string heard(SpeechInput& s) {
    std::string error;
    const std::optional<std::string> text = s.recognize(speech_in_noise(0.3f), error);
    REQUIRE_MESSAGE(text.has_value(), error);
    return *text;
}

bool starts_with(const std::string& text, const std::string& prefix) { return text.rfind(prefix, 0) == 0; }

std::string status_of(OpenLog& log) {
    std::lock_guard<std::mutex> lock(log.m);
    return log.last_status;
}

}  // namespace

// AK-4 (2026-11-20), Prüfbefund 2026-11-21: Vorher setzte der Controller den Vortext selbst (fest „Vera“, dann den
// Namen) – das sah kein Test. Jetzt baut SpeechInput ihn aus set_companion_name, und das prüft dieser Fall.
TEST_CASE("Begleitername: die Erkennung nimmt den Namen aus dem Plugin, beim Laden und nach einem Wechsel") {
    auto log = std::make_shared<OpenLog>();
    SpeechInput s;
    CHECK(s.set_companion_name("Lotte"));
    CHECK_FALSE(s.set_companion_name("Lotte"));  // gleicher Name: kein Wechsel (der Controller schreibt dann nichts)
    start_echo(s, log);
    REQUIRE_MESSAGE(wait_running(s), status_of(*log));
    {
        std::lock_guard<std::mutex> lock(log->m);
        CHECK(starts_with(log->prompt, "Hey Lotte,"));  // geladen mit dem Namen
    }
    CHECK(starts_with(heard(s), "Hey Lotte,"));
    CHECK(s.set_companion_name("Anna Lena"));
    CHECK(starts_with(heard(s), "Hey Anna Lena,"));  // Wechsel im Lauf
    CHECK(s.set_companion_name(""));                // leer = Vorgabe
    CHECK(starts_with(heard(s), "Hey Vera,"));
    std::lock_guard<std::mutex> lock(log->m);
    CHECK(log->opened == 1);  // ohne das Modell neu zu laden
}

TEST_CASE("Begleitername: setzen wartet nicht auf das Laden, ein Wechsel währenddessen geht nicht verloren") {
    auto log = std::make_shared<OpenLog>();
    log->hold = true;
    SpeechInput s;
    const ReleaseOpen release{log};  // nach s angelegt, also vor s abgebaut
    start_echo(s, log);
    {
        std::unique_lock<std::mutex> lock(log->m);
        REQUIRE(log->cv.wait_for(lock, std::chrono::seconds(10), [&log] { return log->entered; }));
    }
    // Das Öffnen hängt noch: Der Long-Poll-Thread des Servers darf hier nicht warten.
    const auto t0 = std::chrono::steady_clock::now();
    CHECK(s.set_companion_name("Lotte"));
    CHECK(std::chrono::steady_clock::now() - t0 < std::chrono::milliseconds(500));
    {
        std::lock_guard<std::mutex> lock(log->m);
        log->hold = false;
    }
    log->cv.notify_all();
    REQUIRE_MESSAGE(wait_running(s), status_of(*log));
    {
        std::lock_guard<std::mutex> lock(log->m);
        CHECK(starts_with(log->prompt, "Hey Vera,"));  // geladen mit dem Namen von vor dem Wechsel
    }
    CHECK(starts_with(heard(s), "Hey Lotte,"));  // der Wechsel greift vor der ersten Erkennung
}
