// Auftrags-Buchführung des Controllers ohne Qt und ohne Modell: Auftragstext, seq, Antworttext, Statistik.

#include "test_support.hpp"

#include "gui/job_tracker.hpp"
#include "server/chat_api.hpp"
#include "server/result_api.hpp"

#include <optional>

using namespace swbh::gui;

namespace {

swbh::llm::CompletionResult result(int tokens, double seconds) {
    swbh::llm::CompletionResult r;
    r.text = R"({"steps":[]})";
    r.generated_tokens = tokens;
    r.prompt_tokens = 2000;
    r.reused_tokens = 1900;
    r.seconds = seconds;
    r.prompt_seconds = 1.0;
    return r;
}

}  // namespace

TEST_CASE("Auftragsbuch: Reparaturrunde zeigt den Auftrag, nicht den Reparaturtext") {
    JobTracker t;
    t.started("ein Turm aus Stein", "The plan is invalid: Step 3 puts a second roof on the build.", "bauplan");
    t.finished(true, "", result(300, 51.0), 6.0);
    CHECK(t.last().request == "ein Turm aus Stein");
    CHECK(t.last().grammar == "bauplan");
}

TEST_CASE("Auftragsbuch: ohne Auftragstext gilt die Zusammenfassung der letzten Nachricht") {
    JobTracker t;
    t.started("", "Bauauftrag: kleines Haus  EXISTING SURROUNDINGS: nothing", "bauplan");
    t.finished(true, "", result(300, 51.0), 6.0);
    CHECK(t.last().request == "kleines Haus");
    t.started("", "Wie spät ist es?", "frei");
    t.finished(true, "", result(10, 2.0), 10.0);
    CHECK(t.last().request == "Wie spät ist es?");
    CHECK(t.last().grammar == "frei");
}

TEST_CASE("Auftragsbuch: seq je Auftrag, Antworttext nur bei Erfolg, Statistik nachgeführt") {
    JobTracker t;
    CHECK(t.seq() == 0);
    t.started("Turm", "", "bauplan");
    t.finished(true, "", result(300, 51.0), 6.0);
    CHECK(t.seq() == 1);
    CHECK(t.last().ok);
    CHECK(t.last().text == R"({"steps":[]})");
    CHECK(t.last().tokens == 300);
    CHECK(t.last().reused_tokens == 1900);
    CHECK(t.last().tokens_per_second == doctest::Approx(6.0));
    t.started("Turm", "", "bauplan");
    t.finished(false, "Gegenseite hat die Verbindung geschlossen", result(120, 20.0), 6.0);
    CHECK(t.seq() == 2);
    CHECK_FALSE(t.last().ok);
    CHECK(t.last().text.empty());
    CHECK(t.last().error == "Gegenseite hat die Verbindung geschlossen");
    CHECK(t.stats().jobs == 2);
    CHECK(t.stats().failed == 1);
    CHECK(t.stats().tokens == 300);
}

TEST_CASE("Verlauf: jeder Auftrag eine Zeile, das Ergebnis aus dem Spiel landet beim passenden") {
    JobTracker t;
    t.new_model("Llama 3.1 8B Instruct");
    t.started("baue mir einen bunker", "baue mir einen bunker", "bauplan");
    t.finished(true, "", result(1823, 42.8), 43.4);
    REQUIRE(t.history().size() == 1);
    CHECK(t.history().front().model == "Llama 3.1 8B Instruct");
    CHECK(t.history().front().result.empty());
    const auto rev = t.history_rev();
    t.note_result("baue mir einen bunker", "verworfen: schwebende Teile", true, 0);
    CHECK(t.history().size() == 1);
    CHECK(t.history().front().result == "verworfen: schwebende Teile");
    CHECK(t.history().front().failed);
    CHECK(t.history_rev() > rev);
    // Schnellauftrag ohne LLM: eigene Zeile oben.
    t.note_result("bau mir fort knox", "gebaut: 28 191 Blöcke", false, 0);
    REQUIRE(t.history().size() == 2);
    CHECK_FALSE(t.history().front().llm);
    CHECK(t.history().front().request == "bau mir fort knox");
}

TEST_CASE("Verlauf: Zählung gebaut, selbst nachgebessert, verworfen für die Kopfzeile") {
    JobTracker t;
    CHECK(history_counts_de(count_history(t.history())).empty());
    t.note_result("a", "gebaut: 10 Blöcke", false, 0);
    t.note_result("b", "gebaut, selbst nachgebessert: 412 Blöcke", false, 0, "Schritt 4: cylinder innen hohl gemacht", 1);
    t.note_result("c", "verworfen: Wasser eingemauert", true, 0, {}, 2);
    t.note_result("d", "abgesagt: zu tief", true, 0);
    const HistoryCounts c = count_history(t.history());
    CHECK(c.results == 4);
    CHECK(c.built == 2);
    CHECK(c.fixed == 1);
    CHECK(c.rejected == 1);
    CHECK(c.other == 1);
    CHECK(history_counts_de(c) ==
          "4 Ergebnisse: 2 gebaut (1 selbst nachgebessert) · 1 verworfen · 1 abgesagt/abgebrochen");
    CHECK(t.history()[2].fixes == "Schritt 4: cylinder innen hohl gemacht");
    CHECK(t.history()[1].rounds == 2);
}

TEST_CASE("Verlauf: höchstens 50 Zeilen, die ältesten fallen weg") {
    JobTracker t;
    for (int i = 0; i < 60; ++i) {
        t.note_result("auftrag " + std::to_string(i), "gebaut: 1 Blöcke", false, 0);
    }
    CHECK(t.history().size() == JobTracker::kMaxHistory);
    CHECK(t.history().front().request == "auftrag 59");
}

TEST_CASE("Verlauf: Ergebnis eines langen Auftrags landet beim LLM-Auftrag, nicht in einer eigenen Zeile") {
    // Ganze Kette wie im Controller: order_text aus dem Chat, ResultNote::order aus /baumeister/v1/ergebnis.
    const std::string order(170, 'a');
    JobTracker t;
    t.started(swbh::server::order_text({{"user", "Bauauftrag: " + order + "\n\nEXISTING"}}), "", "bauplan");
    t.finished(true, "", result(300, 51.0), 6.0);
    std::optional<swbh::server::ResultNote> note;
    swbh::server::HttpRequest req;
    req.method = "POST";
    req.target = std::string(swbh::server::kResultPath);
    req.body = R"({"auftrag":")" + order + R"(","ergebnis":"gebaut","bloecke":5})";  // umlaut-ok: Schlüssel ASCII
    swbh::server::handle_result(req, [&](const swbh::server::ResultNote& n) { note = n; });
    REQUIRE(note.has_value());
    t.note_result(note->order, swbh::server::result_line_de(*note), false, 0);
    CHECK(t.history().size() == 1);
    CHECK(t.history().front().llm);
    CHECK(t.history().front().result == "gebaut: 5 Blöcke");
}

TEST_CASE("Verlauf: Nachbesserungsrunden bekommen keine hängende Zeile, fremde Aufträge bleiben unberührt") {
    JobTracker t;
    for (int i = 0; i < 3; ++i) {
        t.started("ein Turm", "", "bauplan");
        t.finished(true, "", result(300, 51.0), 6.0);
    }
    t.note_result("ein Turm", "gebaut: 400 Blöcke", false, 0, {}, 3);
    REQUIRE(t.history().size() == 3);
    CHECK(t.history()[0].result == "gebaut: 400 Blöcke");
    CHECK_FALSE(t.history()[0].superseded);
    CHECK(t.history()[1].superseded);
    CHECK(t.history()[2].superseded);
    CHECK(t.history()[1].result.empty());
    CHECK(count_history(t.history()).results == 1);
    // Ein zweites Ergebnis mit gleichem Text fängt keine erledigte Runde, sondern wird eine eigene Zeile.
    t.note_result("ein Turm", "gebaut: 400 Blöcke", false, 0);
    CHECK(t.history().size() == 4);
    CHECK_FALSE(t.history().front().llm);

    // Fremder Auftrag zwischen den Runden: bleibt offen.
    JobTracker u;
    u.started("ein Turm", "", "bauplan");
    u.finished(true, "", result(300, 51.0), 6.0);
    u.started("eine Brücke", "", "bauplan");
    u.finished(true, "", result(300, 51.0), 6.0);
    u.started("ein Turm", "", "bauplan");
    u.finished(true, "", result(300, 51.0), 6.0);
    u.note_result("ein Turm", "gebaut: 400 Blöcke", false, 0, {}, 2);
    REQUIRE(u.history().size() == 3);
    CHECK_FALSE(u.history()[1].superseded);
    CHECK(u.history()[1].result.empty());
    CHECK(u.history()[2].superseded);

    // rounds bleibt die Obergrenze: runden=2 markiert eine Runde, runden=1 keine.
    JobTracker v;
    for (int i = 0; i < 3; ++i) {
        v.started("ein Turm", "", "bauplan");
        v.finished(true, "", result(300, 51.0), 6.0);
    }
    v.note_result("ein Turm", "gebaut: 400 Blöcke", false, 0, {}, 2);
    CHECK(v.history()[1].superseded);
    CHECK_FALSE(v.history()[2].superseded);
    JobTracker w;
    for (int i = 0; i < 2; ++i) {
        w.started("ein Turm", "", "bauplan");
        w.finished(true, "", result(300, 51.0), 6.0);
    }
    w.note_result("ein Turm", "gebaut: 400 Blöcke", false, 0, {}, 1);
    CHECK_FALSE(w.history()[1].superseded);
}

TEST_CASE("Modellhinweis: unter 12 Milliarden Parametern ein Hinweis, das 14B nicht") {
    CHECK(model_size_hint_de("qwen2 3B Q4_K - Medium").find("zu klein (3B)") != std::string::npos);
    CHECK(model_size_hint_de("llama 8B Q4_K - Medium").find("14B empfohlen") != std::string::npos);
    CHECK(model_size_hint_de("qwen2 0.5B Q8_0").find("0.5B") != std::string::npos);
    CHECK(model_size_hint_de("qwen2 14B Q4_K - Medium").empty());
    CHECK(model_size_hint_de("qwen2vl ?B Q4_K - Medium").empty());  // unbekannt: kein Urteil
}
