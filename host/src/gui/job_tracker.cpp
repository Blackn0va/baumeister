#include "gui/job_tracker.hpp"

#include <cctype>
#include <ctime>
#include <iterator>

namespace swbh::gui {

std::string model_size_hint_de(std::string_view detail) {
    // Erste Zahl direkt vor „B“ (Milliarden): „qwen2 3B …“, „llama 8B …“, „qwen2 14B …“, auch „0.5B“.
    for (std::size_t i = 0; i < detail.size(); ++i) {
        if (detail[i] != 'B' || i == 0 || !std::isdigit(static_cast<unsigned char>(detail[i - 1]))) {
            continue;
        }
        std::size_t start = i;
        while (start > 0 && (std::isdigit(static_cast<unsigned char>(detail[start - 1])) || detail[start - 1] == '.')) {
            --start;
        }
        const double billions = std::stod(std::string(detail.substr(start, i - start)));
        if (billions < 12.0) {
            return "Für freie Baupläne zu klein (" + std::string(detail.substr(start, i - start + 1)) +
                   "): Wiederholungen und verworfene Pläne – Qwen2.5 14B empfohlen.";
        }
        return {};
    }
    return {};
}

HistoryCounts count_history(const std::deque<HistoryEntry>& history) {
    HistoryCounts c;
    for (const HistoryEntry& e : history) {
        if (e.result.empty()) {
            continue;
        }
        ++c.results;
        if (e.result.rfind("gebaut", 0) == 0) {
            ++c.built;
            c.fixed += e.fixes.empty() ? 0 : 1;
        } else if (e.result.rfind("verworfen", 0) == 0) {
            ++c.rejected;
        } else {
            ++c.other;
        }
    }
    return c;
}

std::string history_counts_de(const HistoryCounts& c) {
    if (c.results == 0) {
        return {};
    }
    std::string s = std::to_string(c.results) + (c.results == 1 ? " Ergebnis: " : " Ergebnisse: ") +
                    std::to_string(c.built) + " gebaut";
    if (c.fixed > 0) {
        s += " (" + std::to_string(c.fixed) + " selbst nachgebessert)";
    }
    s += " · " + std::to_string(c.rejected) + " verworfen";
    if (c.other > 0) {
        s += " · " + std::to_string(c.other) + " abgesagt/abgebrochen";
    }
    return s;
}

void JobTracker::note_result(std::string_view order, std::string_view line, bool failed, std::int64_t now,
                             std::string_view fixes, int rounds) {
    std::size_t looked = 0;
    for (auto it = history_.begin(); it != history_.end(); ++it) {
        if (++looked > 5) {
            break;
        }
        HistoryEntry& e = *it;
        // !superseded: Eine schon als Nachbesserungsrunde markierte Zeile ist erledigt und soll kein zweites
        // Ergebnis desselben Textes mehr fangen.
        if (e.result.empty() && !e.superseded && e.llm && e.request == order) {
            e.result = std::string(line);
            e.failed = failed;
            e.fixes = std::string(fixes);
            e.rounds = rounds;
            // Die übrigen LLM-Antworten dieses Auftrags (Nachbesserungsrunden) stehen weiter unten mit leerem
            // Ergebnis. Nur erfolgreiche Runden: fehlgeschlagene tragen schon „Modellfehler: …“. Nicht „die Zeile
            // darüber“ – ein fremder Auftrag kann dazwischen liegen. Wer die Schleife entfernt, hat wieder Zeilen,
            // die nie ein Ergebnis bekommen.
            int left = rounds - 1;
            std::size_t seen = looked;
            for (auto older = std::next(it); older != history_.end() && left > 0 && ++seen <= 5; ++older) {
                if (older->llm && older->ok && older->result.empty() && !older->superseded &&
                    older->request == order) {
                    older->superseded = true;
                    --left;
                }
            }
            ++history_rev_;
            return;
        }
    }
    HistoryEntry e;
    e.when = now;
    e.request = std::string(order);
    e.llm = false;
    e.ok = true;
    e.result = std::string(line);
    e.failed = failed;
    e.fixes = std::string(fixes);
    e.rounds = rounds;
    history_.push_front(std::move(e));
    if (history_.size() > kMaxHistory) {
        history_.pop_back();
    }
    ++history_rev_;
}

void JobTracker::started(std::string_view order, std::string_view summary, std::string_view grammar) {
    // Vorher stand hier order_title(summary): In der Reparaturrunde ist die letzte Nutzer-Nachricht der Reparaturtext,
    // und der landete als „Auftrag“ in Vorschau und Meldung. Wer das zurückdreht, bekommt ihn wieder.
    pending_request_ = order.empty() ? order_title(summary) : std::string(order);
    pending_grammar_ = std::string(grammar);
}

void JobTracker::finished(bool ok, std::string_view error, const llm::CompletionResult& r, double tokens_per_second) {
    stats_.add(ok, r);
    // seq zählt, damit die Oberfläche einen neuen Auftrag auch dann erkennt, wenn er wörtlich gleich ausfällt.
    plan_.seq += 1;
    plan_.request = pending_request_;
    plan_.grammar = pending_grammar_;
    plan_.ok = ok;
    plan_.error = std::string(error);
    plan_.text = ok ? r.text : std::string();
    plan_.tokens = r.generated_tokens;
    plan_.prompt_tokens = r.prompt_tokens;
    plan_.reused_tokens = r.reused_tokens;
    plan_.seconds = r.seconds;
    plan_.tokens_per_second = tokens_per_second;
    HistoryEntry e;
    e.when = static_cast<std::int64_t>(std::time(nullptr));
    e.request = plan_.request;
    e.model = model_;
    e.ok = ok;
    e.seconds = r.seconds;
    e.tokens = r.generated_tokens;
    e.tokens_per_second = tokens_per_second;
    if (!ok) {
        e.result = "Modellfehler: " + std::string(error);
        e.failed = true;
    }
    history_.push_front(std::move(e));
    if (history_.size() > kMaxHistory) {
        history_.pop_back();
    }
    ++history_rev_;
}

}  // namespace swbh::gui
