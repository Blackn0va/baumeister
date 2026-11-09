// Buchführung über die Aufträge für Plan-Vorschau, Benachrichtigung und Statistik: Was request_started meldet
// (Auftragstext, Grammatik), wird bis request_finished gehalten und dort mit dem Ergebnis zum PlanRecord.
//
// Warum eine eigene Klasse statt Feldern im Controller: Der Controller bringt llama.cpp, die GPU-Erkennung (Vulkan)
// und Qt mit und ist im Unit-Test nicht zu bauen. Die Durchreichung war deshalb ungetestet (Prüfbefund 2026-10-29) –
// und genau dort lag der Fehler, dass Reparaturrunden den Reparaturtext als Auftrag zeigten. Hier ist sie ohne Qt
// und ohne Modell prüfbar; der Controller ruft nur noch started/finished unter seinem mutex_.
//
// Threads: keine eigene Sperre – der Controller hält sie unter seinem mutex_. Fehler: keine.

#pragma once

#include "gui/job_stats.hpp"
#include "gui/plan_view.hpp"
#include "llm/engine.hpp"

#include <cstdint>
#include <deque>
#include <string>
#include <string_view>

namespace swbh::gui {

// Eine Zeile im Reiter „Verlauf“: ein LLM-Auftrag oder ein Bau ohne LLM (Schnellauftrag), samt Ergebnis im Spiel.
struct HistoryEntry {
    std::int64_t when = 0;  // Unix-Zeit (s)
    std::string request;
    std::string model;  // leer = ohne LLM
    bool llm = true;
    bool ok = false;  // Modell hat geantwortet (bei llm = false immer true)
    double seconds = 0.0;
    int tokens = 0;
    double tokens_per_second = 0.0;
    std::string result;  // „gebaut: 8 652 Blöcke auf Plot 1;1“, leer = (noch) keine Meldung aus dem Spiel
    bool failed = false;  // Ergebnis war kein Bau
    std::string fixes;    // Nachbesserungen ohne LLM (server::ResultNote::fixes), leer = keine
    int rounds = 0;       // LLM-Antworten laut Plugin (0 = unbekannt oder Schnellauftrag)
    // Frühere LLM-Antwort desselben Auftrags (Nachbesserungsrunde). Vorher bekam nur die jüngste Zeile das Ergebnis,
    // die älteren Runden zeigten für immer „–“ / „Noch keine Meldung aus dem Spiel“, als hinge der Auftrag. result
    // bleibt leer, damit count_history sie nicht als eigenes Ergebnis zählt.
    bool superseded = false;
};

// Zählung über den Verlauf (Kopfzeile im Reiter „Verlauf“). Wunsch 2026-10-31 – die Zeile zeigt, wie oft gebaut, selbst
// nachgebessert oder verworfen wurde, statt dass
// man es aus 50 Zeilen zusammenzählt.
struct HistoryCounts {
    int results = 0;   // Zeilen mit Ergebnis aus dem Spiel
    int built = 0;     // gebaut
    int fixed = 0;     // davon selbst nachgebessert
    int rejected = 0;  // verworfen
    int other = 0;     // abgesagt oder abgebrochen
};

HistoryCounts count_history(const std::deque<HistoryEntry>& history);

// „12 Ergebnisse: 9 gebaut (3 selbst nachgebessert) · 2 verworfen · 1 abgesagt/abgebrochen“; leer ohne Ergebnis.
std::string history_counts_de(const HistoryCounts& c);

// Hinweis, wenn das geladene Modell für freie Baupläne zu klein ist; leer sonst. detail ist die Beschreibung aus
// llama.cpp („qwen2 3B Q4_K - Medium“, „llama 8B Q4_K - Medium“). Gemessen 2026-10-30 auf dev01: Qwen2.5 3B und
// Llama 3.1 8B wiederholten Schritte bis zu 40-mal und lieferten für Bunker, Taschenrechner und Display keinen
// brauchbaren Plan; das 14B plante dieselben Aufträge. Unter 12 Milliarden Parametern also ein Hinweis.
std::string model_size_hint_de(std::string_view detail);

class JobTracker final {
public:
    // order: server::RequestInfo::order (erste Nachricht mit „Bauauftrag:“), summary: letzte Nutzer-Nachricht.
    // Ohne order gilt order_title(summary) – so bleibt der Text bei Clients ohne Vorspann (LlmProbe) sichtbar.
    void started(std::string_view order, std::string_view summary, std::string_view grammar);
    // Schließt den Auftrag ab: seq + 1, Antworttext nur bei Erfolg, Statistik nachgeführt.
    void finished(bool ok, std::string_view error, const llm::CompletionResult& r, double tokens_per_second);

    const PlanRecord& last() const { return plan_; }
    std::uint64_t seq() const { return plan_.seq; }
    const JobStats& stats() const { return stats_; }
    // Lesetempo beim Vorwärmen (Controller::prewarm_prefix) – Maßstab für JobStats::slow_warning_de.
    void set_warm_prompt_tps(double tps) { stats_.warm_prompt_tps = tps; }
    // Nach jedem erfolgreichen Laden (Controller): Tempo-Maßstab des alten Modells gilt nicht mehr.
    void new_model(std::string_view name = {}) {
        stats_.new_model();
        model_ = std::string(name);
    }

    // Ergebnis aus dem Spiel (server::ResultNote) dem passenden Auftrag zuordnen: dem jüngsten ohne Ergebnis mit
    // gleichem Text (unter den letzten fünf), sonst eine eigene Zeile – Schnellaufträge laufen ohne LLM. Bei
    // rounds > 1 werden im selben Fenster bis zu rounds - 1 ältere, erfolgreiche Zeilen desselben Auftrags ohne
    // Ergebnis als superseded markiert (die Nachbesserungsrunden).
    void note_result(std::string_view order, std::string_view line, bool failed, std::int64_t now,
                     std::string_view fixes = {}, int rounds = 0);

    // Jüngster zuerst, höchstens kMaxHistory. rev zählt jede Änderung (die Oberfläche baut nur dann neu auf).
    static constexpr std::size_t kMaxHistory = 50;
    const std::deque<HistoryEntry>& history() const { return history_; }
    std::uint64_t history_rev() const { return history_rev_; }

private:
    PlanRecord plan_;
    JobStats stats_;
    std::deque<HistoryEntry> history_;
    std::uint64_t history_rev_ = 0;
    std::string model_;
    // Aus started bis finished (ein Auftrag zur Zeit, ChatApi weist den zweiten mit 503 ab).
    std::string pending_request_;
    std::string pending_grammar_;
};

}  // namespace swbh::gui
