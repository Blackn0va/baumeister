// Verdrahtung zwischen den getesteten Bausteinen (Auftrag T-007, 2026-11-21): Begleitername vom Long-Poll bis zur
// Erkennung (gui/companion_wiring) und Plan/Meldungen der 250-ms-Abfrage des Hauptfensters (gui/window_feed).
// Vorher waren nur die Enden getestet – fehlte die Zeile dazwischen, blieb alles grün.

#include "test_support.hpp"

#include "gui/companion_wiring.hpp"
#include "gui/console_throttle.hpp"
#include "gui/window_feed.hpp"
#include "server/companion_api.hpp"
#include "server/http.hpp"
#include "voice/speech_input.hpp"

#include <chrono>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

using namespace swbh;
using namespace swbh::gui;
using namespace std::chrono_literals;

namespace {

server::HttpRequest heard_request(std::string_view query) {
    server::HttpRequest r;
    r.method = "GET";
    r.target = std::string(server::kCompanionHeardPath) + std::string(query);
    r.version = "HTTP/1.1";
    return r;
}

// Steht für den Controller: zählt nur mit, was ankommt.
class FakeHooks final : public CompanionHooks {
public:
    void companion_said(const server::Spoken& s) override { said.push_back(s.text); }
    void game_voice(const std::vector<std::string>& names) override { in_game = names; }
    std::optional<std::string> hear_from_game(const std::string&, std::vector<float>, std::string&) override {
        return std::string();
    }
    std::optional<std::vector<std::int16_t>> voice_for_game(const std::string&, std::string&) override {
        return std::vector<std::int16_t>{};
    }
    void companion_name_line(const std::string& line) override { lines.push_back(line); }

    std::vector<std::string> said;
    std::vector<std::string> in_game;
    std::vector<std::string> lines;
};

server::ApiResponse poll_heard(server::HeardQueue& q, FakeHooks& hooks, voice::SpeechInput& speech,
                               ConsoleThrottle& throttle, std::string_view query) {
    return server::handle_companion(heard_request(query), q, {}, companion_handlers(hooks, speech, throttle), 10ms);
}

PlanRecord plan(std::uint64_t seq, bool ok = true) {
    PlanRecord p;
    p.seq = seq;
    p.request = "ein kleines Haus";
    p.grammar = "bauplan";
    p.ok = ok;
    p.error = ok ? "" : "Grammatik verletzt";
    p.tokens = 400;
    p.seconds = 40.0;
    p.tokens_per_second = 10.0;
    return p;
}

// Verborgenes Fenster mit Symbol, Meldungen an: So kommt eine Meldung an.
NoticeGate hidden_gate() {
    NoticeGate g;
    g.enabled = true;
    g.has_tray = true;
    g.window_active = false;
    return g;
}

}  // namespace

TEST_CASE("Begleitername: der Name aus dem Long-Poll landet in der Erkennung, ein gleicher schreibt keine Zeile") {
    server::HeardQueue q;
    FakeHooks hooks;
    voice::SpeechInput speech;
    ConsoleThrottle throttle(1min);

    REQUIRE(poll_heard(q, hooks, speech, throttle, "?begleiter=Lotte&imspiel=Steve").status == 200);
    // Abgelegt: Derselbe Name ist für die Erkennung kein Wechsel mehr. Ohne Weiterleitung stünde dort noch „Vera“,
    // und set_companion_name("Lotte") meldete einen Wechsel.
    CHECK_FALSE(speech.set_companion_name("Lotte"));
    REQUIRE(hooks.lines.size() == 1);
    CHECK(hooks.lines[0].find("„Lotte“") != std::string::npos);
    CHECK(hooks.in_game == std::vector<std::string>{"Steve"});  // die übrigen Handler hängen am selben Bündel

    // Jedes Abholen meldet den Namen; unverändert entsteht keine weitere Zeile.
    REQUIRE(poll_heard(q, hooks, speech, throttle, "?begleiter=Lotte").status == 200);
    CHECK(hooks.lines.size() == 1);

    // Ohne Parameter (älteres Plugin) gilt wieder die Vorgabe – auch das erreicht die Erkennung.
    REQUIRE(poll_heard(q, hooks, speech, throttle, "").status == 200);
    CHECK_FALSE(speech.set_companion_name(std::string(server::kDefaultCompanionName)));
    CHECK(hooks.lines.size() == 1);  // Wechsel innerhalb der Minute: gedrosselt
}

TEST_CASE("Begleitername: forward_companion_name schreibt nur bei Wechsel und zählt Gedrosseltes mit") {
    voice::SpeechInput speech;
    ConsoleThrottle throttle(1min);
    const auto t0 = std::chrono::steady_clock::time_point{} + 1h;
    const std::optional<std::string> first = forward_companion_name(speech, throttle, "Lotte", t0);
    REQUIRE(first.has_value());
    CHECK(*first == "Begleitername vom Plugin: „Lotte“ – die Spracherkennung achtet auf diesen Namen.");
    CHECK_FALSE(forward_companion_name(speech, throttle, "Lotte", t0 + 1s).has_value());  // kein Wechsel
    CHECK_FALSE(forward_companion_name(speech, throttle, "Anna", t0 + 2s).has_value());   // Wechsel, gedrosselt
    CHECK_FALSE(speech.set_companion_name("Anna"));  // trotzdem abgelegt
    const std::optional<std::string> later = forward_companion_name(speech, throttle, "Lena", t0 + 61s);
    REQUIRE(later.has_value());
    CHECK(later->find("(1 Wechsel davor nicht gemeldet)") != std::string::npos);
}

TEST_CASE("Hauptfenster: ein neuer Plan wird genau einmal gemeldet, ein gleichbleibender nicht erneut") {
    WindowFeed feed;
    const FeedState s;
    int fetched = 0;
    PlanRecord current;  // was Controller::last_plan() gerade liefert
    std::uint64_t seq = 0;
    const auto poll = [&] {
        return feed.poll(s, seq, [&] {
            ++fetched;
            return current;
        }, hidden_gate());
    };

    // Erste Abfrage legt nur den Ausgangszustand fest.
    FeedPoll p = poll();
    CHECK_FALSE(p.plan_changed);
    CHECK_FALSE(p.notice.has_value());
    CHECK(fetched == 0);

    current = plan(1);
    seq = 1;
    p = poll();
    CHECK(p.plan_changed);
    REQUIRE(p.notice.has_value());
    CHECK(p.notice->kind == NoticeKind::PlanDone);
    CHECK(p.notice->title == "Baumeister-Host: Bauplan fertig");
    CHECK(p.notice->text.find("„ein kleines Haus“") != std::string::npos);
    CHECK(feed.plan().seq == 1);
    CHECK(fetched == 1);

    // Gleiche Nummer: weder geholt noch erneut gemeldet.
    for (int i = 0; i < 5; ++i) {
        p = poll();
        CHECK_FALSE(p.plan_changed);
        CHECK_FALSE(p.notice.has_value());
    }
    CHECK(fetched == 1);

    current = plan(2, false);
    seq = 2;
    p = poll();
    CHECK(p.plan_changed);
    REQUIRE(p.notice.has_value());
    CHECK(p.notice->kind == NoticeKind::PlanFailed);
    CHECK(fetched == 2);
    CHECK_FALSE(poll().notice.has_value());
}

TEST_CASE("Hauptfenster: abgeschaltet oder aktives Fenster – der Plan kommt in die Vorschau, gemeldet wird nichts") {
    WindowFeed feed;
    const FeedState s;
    PlanRecord current;
    std::uint64_t seq = 0;
    NoticeGate gate = hidden_gate();
    const auto poll = [&] { return feed.poll(s, seq, [&] { return current; }, gate); };
    (void)poll();

    gate.enabled = false;
    current = plan(1);
    seq = 1;
    FeedPoll p = poll();
    CHECK(p.plan_changed);
    CHECK_FALSE(p.notice.has_value());
    // Wieder an: der Plan von vorhin kommt nicht nach (der Notifier hat ihn mitverfolgt).
    gate.enabled = true;
    CHECK_FALSE(poll().notice.has_value());

    gate.window_active = true;
    current = plan(2);
    seq = 2;
    p = poll();
    CHECK(p.plan_changed);
    CHECK_FALSE(p.notice.has_value());
}

TEST_CASE("Hauptfenster: verdrängt eine Fehler-Sprechblase die Meldung, kommt sie eine Abfrage später") {
    WindowFeed feed;
    const FeedState s;
    PlanRecord current;
    std::uint64_t seq = 0;
    NoticeGate gate = hidden_gate();
    const auto poll = [&] { return feed.poll(s, seq, [&] { return current; }, gate); };
    (void)poll();

    current = plan(1);
    seq = 1;
    gate.error_shown = true;
    CHECK_FALSE(poll().notice.has_value());
    gate.error_shown = false;
    const FeedPoll p = poll();
    REQUIRE(p.notice.has_value());
    CHECK(p.notice->kind == NoticeKind::PlanDone);
    CHECK_FALSE(poll().notice.has_value());
}

TEST_CASE("Hauptfenster: die Felder der Abfrage erreichen den Notifier (Modell bereit, Verbindung getrennt)") {
    WindowFeed feed;
    FeedState s;
    s.tunnel = tunnel::State::Connected;
    const auto poll = [&] { return feed.poll(s, 0, [] { return PlanRecord{}; }, hidden_gate()); };
    (void)poll();

    s.model_loaded = true;
    s.model_title = "Qwen2.5 14B";
    FeedPoll p = poll();
    REQUIRE(p.notice.has_value());
    CHECK(p.notice->kind == NoticeKind::ModelReady);
    CHECK(p.notice->text.find("Qwen2.5 14B") != std::string::npos);

    s.tunnel = tunnel::State::Failed;
    s.tunnel_text = "Weiterleitung abgelehnt";
    s.via_address = true;
    p = poll();
    REQUIRE(p.notice.has_value());
    CHECK(p.notice->title == "Baumeister-Host: Verbindung getrennt");
    CHECK(p.notice->text == "Weiterleitung abgelehnt");
}
