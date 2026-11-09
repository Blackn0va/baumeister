// Benachrichtigungsregeln und Auftragsstatistik (ohne Qt).

#include "test_support.hpp"

#include "gui/job_stats.hpp"
#include "gui/job_tracker.hpp"
#include "gui/notifier.hpp"

using namespace swbh::gui;
using swbh::tunnel::State;

namespace {

NotifyInput state(bool model, State tunnel, std::uint64_t seq = 0, bool ok = true) {
    NotifyInput in;
    in.model_loaded = model;
    in.model_title = "Qwen2.5 3B";
    in.tunnel = tunnel;
    in.job.seq = seq;
    in.job.ok = ok;
    in.job.grammar = "bauplan";
    in.job.request = "ein Turm aus Stein";
    in.job.tokens = 304;
    in.job.seconds = 54.1;
    in.job.tokens_per_second = 5.7;
    in.job.error = "Gegenseite hat die Verbindung geschlossen";
    return in;
}

}  // namespace

TEST_CASE("Benachrichtigung: erster Aufruf legt nur den Ausgangszustand fest") {
    Notifier n;
    CHECK(n.update(state(true, State::Connected, 7), true).empty());
    CHECK(n.update(state(true, State::Connected, 7), true).empty());
}

TEST_CASE("Benachrichtigung: Modell geladen meldet einmal") {
    Notifier n;
    n.update(state(false, State::Stopped), true);
    const auto a = n.update(state(true, State::Stopped), true);
    REQUIRE(a.size() == 1);
    CHECK(a[0].kind == NoticeKind::ModelReady);
    CHECK(a[0].text.find("Qwen2.5 3B") != std::string::npos);
    CHECK(n.update(state(true, State::Stopped), true).empty());
}

TEST_CASE("Benachrichtigung: Tunnel weg nur aus „verbunden“, einmal, „wieder da“ nur danach") {
    Notifier n;
    n.update(state(true, State::Stopped), true);
    // Erster Verbindungsversuch scheitert: keine Meldung (das ist kein „weg“).
    CHECK(n.update(state(true, State::Starting), true).empty());
    CHECK(n.update(state(true, State::Waiting), true).empty());
    // Erstes Verbinden: keine „wieder da“-Meldung.
    CHECK(n.update(state(true, State::Connected), true).empty());
    const auto lost = n.update(state(true, State::Waiting), true);
    REQUIRE(lost.size() == 1);
    CHECK(lost[0].kind == NoticeKind::TunnelLost);
    CHECK(lost[0].warning);
    // Neustartschleife ohne Verbindung: keine weitere Meldung.
    CHECK(n.update(state(true, State::Starting), true).empty());
    CHECK(n.update(state(true, State::Waiting), true).empty());
    const auto back = n.update(state(true, State::Connected), true);
    REQUIRE(back.size() == 1);
    CHECK(back[0].kind == NoticeKind::TunnelBack);
}

TEST_CASE("Benachrichtigung: gestoppter Tunnel ist kein Verlust") {
    Notifier n;
    n.update(state(true, State::Connected), true);
    CHECK(n.update(state(true, State::Stopped), true).empty());
    CHECK(n.update(state(true, State::Connected), true).empty());
}

TEST_CASE("Benachrichtigung: Bauplan fertig und fehlgeschlagen je neuem Auftrag") {
    Notifier n;
    n.update(state(true, State::Connected, 0), true);
    const auto done = n.update(state(true, State::Connected, 1, true), true);
    REQUIRE(done.size() == 1);
    CHECK(done[0].kind == NoticeKind::PlanDone);
    CHECK(done[0].title == "Baumeister-Host: Bauplan fertig");
    CHECK(done[0].text == "„ein Turm aus Stein“: 304 Token in 54,1 s (5,7 Token/s)");
    CHECK(n.update(state(true, State::Connected, 1, true), true).empty());
    const auto failed = n.update(state(true, State::Connected, 2, false), true);
    REQUIRE(failed.size() == 1);
    CHECK(failed[0].kind == NoticeKind::PlanFailed);
    CHECK(failed[0].warning);
    CHECK(failed[0].text.find("Gegenseite") != std::string::npos);
}

TEST_CASE("Benachrichtigung: abgeschaltet nichts melden und beim Einschalten nichts nachholen") {
    Notifier n;
    n.update(state(false, State::Connected, 0), false);
    CHECK(n.update(state(true, State::Waiting, 1), false).empty());
    // Wieder an: Modell, Tunnelverlust und Auftrag 1 sind vorbei – nichts nachmelden.
    CHECK(n.update(state(true, State::Waiting, 1), true).empty());
    // Der Verlust wurde aber verfolgt: „wieder da“ kommt.
    const auto back = n.update(state(true, State::Connected, 1), true);
    REQUIRE(back.size() == 1);
    CHECK(back[0].kind == NoticeKind::TunnelBack);
}

TEST_CASE("Benachrichtigung: mehrere Meldungen einer Abfrage – die wichtigste vorn") {
    CHECK_FALSE(merge_notices({}).has_value());
    const auto m = merge_notices({{NoticeKind::ModelReady, "Baumeister-Host: Modell bereit", "geladen", false},
                                  {NoticeKind::TunnelLost, "Baumeister-Host: Tunnel getrennt", "weg", true}});
    REQUIRE(m.has_value());
    CHECK(m->kind == NoticeKind::TunnelLost);
    CHECK(m->warning);
    CHECK(m->text == "weg\nAußerdem: Modell bereit");
}

TEST_CASE("Statistik: gewichtet über Summen, Fehlschläge nur gezählt") {
    JobStats s;
    CHECK(s.summary_de().empty());
    swbh::llm::CompletionResult a;
    a.generated_tokens = 400;
    a.seconds = 50.0;
    a.prompt_seconds = 10.0;  // 400 Token in 40 s
    a.prompt_tokens = 2000;
    a.reused_tokens = 0;
    swbh::llm::CompletionResult b;
    b.generated_tokens = 20;
    b.seconds = 6.0;
    b.prompt_seconds = 1.0;  // 20 Token in 5 s
    b.prompt_tokens = 2000;
    b.reused_tokens = 1990;
    s.add(true, a);
    s.add(true, b);
    swbh::llm::CompletionResult partial;
    partial.generated_tokens = 999;
    partial.seconds = 99.0;
    s.add(false, partial);
    CHECK(s.jobs == 3);
    CHECK(s.failed == 1);
    CHECK(s.tokens == 420);
    CHECK(s.avg_seconds() == doctest::Approx(28.0));
    // 420 Token in 45 s = 9,33 – NICHT der Mittelwert der Raten (10 und 4 → 7).
    CHECK(s.tokens_per_second() == doctest::Approx(420.0 / 45.0));
    CHECK(s.reuse_share() == doctest::Approx(0.4975));
    CHECK(s.summary_de() ==
          "3 Aufträge (1 fehlgeschlagen) · Ø 28,0 s · Ø 9,3 Token/s · 6,0–50,0 s · 50 % Prompt aus dem Speicher");
}

TEST_CASE("Benachrichtigung: Tunnel-Neustart aus den Einstellungen ist kein Verlust") {
    // Die Einstellungen rufen stop_tunnel() und start_tunnel() im selben GUI-Takt – die Abfrage sieht nie „Stopped“,
    // nur Connected → Starting, aber eine neue tunnel_generation.
    Notifier n;
    NotifyInput in = state(true, State::Connected);
    n.update(in, true);
    in.tunnel_generation = 1;
    in.tunnel = State::Starting;
    CHECK(n.update(in, true).empty());
    in.tunnel = State::Connected;
    CHECK(n.update(in, true).empty());
    // Ein echter Abbruch danach meldet weiter.
    in.tunnel = State::Waiting;
    const auto lost = n.update(in, true);
    REQUIRE(lost.size() == 1);
    CHECK(lost[0].kind == NoticeKind::TunnelLost);
}

TEST_CASE("Benachrichtigung: von einer Fehler-Sprechblase verdrängte Meldung kommt eine Abfrage später") {
    std::vector<Notice> pending;
    const std::vector<Notice> done = {{NoticeKind::PlanDone, "Baumeister-Host: Bauplan fertig", "Turm", false}};
    NoticeGate gate;
    gate.has_tray = true;
    gate.error_shown = true;
    CHECK_FALSE(notice_to_show(pending, done, gate).has_value());
    gate.error_shown = false;
    const auto later = notice_to_show(pending, {}, gate);
    REQUIRE(later.has_value());
    CHECK(later->kind == NoticeKind::PlanDone);
    CHECK(pending.empty());
    CHECK_FALSE(notice_to_show(pending, {}, gate).has_value());  // nur einmal
}

TEST_CASE("Benachrichtigung: aktives Fenster, kein Symbol oder abgeschaltet – nichts zeigen, nichts zurückhalten") {
    const std::vector<Notice> done = {{NoticeKind::PlanDone, "Baumeister-Host: Bauplan fertig", "Turm", false}};
    for (int variant = 0; variant < 3; ++variant) {
        std::vector<Notice> pending = done;  // aus einer früheren Abfrage zurückgehalten
        NoticeGate gate;
        gate.has_tray = variant != 1;
        gate.window_active = variant == 0;
        gate.enabled = variant != 2;
        CHECK_FALSE(notice_to_show(pending, done, gate).has_value());
        CHECK(pending.empty());
    }
    std::vector<Notice> pending;
    NoticeGate gate;
    gate.has_tray = true;
    const auto shown = notice_to_show(pending, done, gate);
    REQUIRE(shown.has_value());
    CHECK(shown->text == "Turm");
}

namespace {

swbh::llm::CompletionResult job(int prompt, int reused, double prompt_s, int generated, double gen_s) {
    swbh::llm::CompletionResult r;
    r.prompt_tokens = prompt;
    r.reused_tokens = reused;
    r.prompt_seconds = prompt_s;
    r.generated_tokens = generated;
    r.seconds = prompt_s + gen_s;
    return r;
}

}  // namespace

TEST_CASE("Tempo: der Auftrag von dev01 (39 Token/s gelesen, Vorwärmen 2 563) wird als langsam gemeldet") {
    JobStats s;
    s.warm_prompt_tps = 3845 / 1.5;  // „System-Prompt vorgewärmt: 3845 Token in 1,5 s“ (2026-10-30)
    // „Fertig in 152,2 s: 396 Token, 3,9 Token/s (Prompt 5780 Token, davon 3845 aus dem Speicher, 49,5 s)“
    const std::string w = s.slow_warning_de(job(5780, 3845, 49.5, 396, 102.7));
    CHECK(w.find("Langsam: Prompt mit 39 Token/s gelesen") == 0);
    CHECK(w.find("Grafikkarte") != std::string::npos);
}

TEST_CASE("Tempo: Schreiben weit unter dem eigenen Bestwert wird gemeldet, normales Streuen nicht") {
    JobStats s;
    s.add(true, job(100, 100, 0.1, 300, 3.6));  // 83 Token/s – die Messprobe von 2026-10-30
    CHECK(s.best_tps > 80.0);
    CHECK(s.slow_warning_de(job(100, 100, 0.1, 225, 10.5)).find("Langsam: 21,4 Token/s, sonst bis 83,3") == 0);
    CHECK(s.slow_warning_de(job(100, 100, 0.1, 300, 4.5)).empty());   // 67 Token/s: unauffällig
    CHECK(s.slow_warning_de(job(100, 100, 0.1, 20, 10.0)).empty());   // zu kurz für ein Urteil
}

TEST_CASE("Tempo: nach einem Modellwechsel gilt der Bestwert des alten Modells nicht mehr") {
    // 2026-10-30: Qwen2.5 3B mit Turbo 200,7 Token/s, danach Llama 3.1 8B mit 46,7 – vorher „Langsam“ bei jedem Auftrag.
    JobTracker t;
    t.started("Bauauftrag: Haus", "Haus", "bauplan");
    t.finished(true, "", job(5673, 4065, 0.3, 1755, 8.7), 200.7);
    CHECK(t.stats().best_tps > 200.0);
    CHECK_FALSE(t.stats().slow_warning_de(job(7827, 4009, 6.8, 412, 8.8)).empty());
    t.new_model();
    CHECK(t.stats().slow_warning_de(job(7827, 4009, 6.8, 412, 8.8)).empty());
    CHECK(t.stats().jobs == 1);  // Zähler bleiben
}

TEST_CASE("Tempo: ohne Maßstab (erster Auftrag, kein Vorwärmen) keine Warnung") {
    JobStats s;
    CHECK(s.slow_warning_de(job(5780, 0, 49.5, 396, 102.7)).empty());
}

TEST_CASE("Benachrichtigung: Fernverbindung spricht von „Verbindung“ statt „Tunnel“, gleiche Regeln") {
    Notifier n;
    NotifyInput in = state(true, State::Connected);
    in.via_address = true;
    n.update(in, true);
    in.tunnel = State::Waiting;
    in.tunnel_text = "Verbindung zu dein-server.example.net:25580 getrennt";
    const auto lost = n.update(in, true);
    REQUIRE(lost.size() == 1);
    CHECK(lost[0].kind == NoticeKind::TunnelLost);
    CHECK(lost[0].title == "Baumeister-Host: Verbindung getrennt");
    in.tunnel = State::Connected;
    const auto back = n.update(in, true);
    REQUIRE(back.size() == 1);
    CHECK(back[0].title == "Baumeister-Host: Verbindung wieder da");
}