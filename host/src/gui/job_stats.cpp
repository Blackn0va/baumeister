#include "gui/job_stats.hpp"

#include "base/format.hpp"

#include <algorithm>
#include <cmath>

namespace swbh::gui {

namespace {

// Dezimalkomma wie im Rest der Oberfläche (fmt_fixed liest keine Locale).
std::string de(double v, int digits) {
    std::string s = fmt_fixed(v, digits);
    std::replace(s.begin(), s.end(), '.', ',');
    return s;
}

}  // namespace

void JobStats::add(bool ok, const llm::CompletionResult& r) {
    ++jobs;
    if (!ok) {
        ++failed;
        return;
    }
    tokens += r.generated_tokens;
    prompt_tokens += r.prompt_tokens;
    reused_tokens += r.reused_tokens;
    seconds += r.seconds;
    gen_seconds += std::max(0.0, r.seconds - r.prompt_seconds);
    const double gen = r.seconds - r.prompt_seconds;
    if (r.generated_tokens >= kPaceMinTokens && gen > 0.0) {
        best_tps = std::max(best_tps, r.generated_tokens / gen);
    }
    if (succeeded() == 1) {
        fastest = slowest = r.seconds;
    } else {
        fastest = std::min(fastest, r.seconds);
        slowest = std::max(slowest, r.seconds);
    }
}

std::string JobStats::slow_warning_de(const llm::CompletionResult& r) const {
    const int fresh = r.prompt_tokens - r.reused_tokens;
    if (fresh >= kPaceMinPrompt && warm_prompt_tps > 0.0 && r.prompt_seconds > 0.0) {
        const double read = fresh / r.prompt_seconds;
        if (read < 0.25 * warm_prompt_tps) {
            return "Langsam: Prompt mit " + de(read, 0) + " Token/s gelesen, beim Vorwärmen " + de(warm_prompt_tps, 0) +
                   " – belegt ein Spiel (Minecraft im Vordergrund) oder ein anderes Programm die Grafikkarte, oder ist "
                   "der Grafikspeicher voll?";
        }
    }
    const double gen = r.seconds - r.prompt_seconds;
    if (r.generated_tokens >= kPaceMinTokens && gen > 0.0 && best_tps > 0.0) {
        const double tps = r.generated_tokens / gen;
        if (tps < 0.35 * best_tps) {
            return "Langsam: " + de(tps, 1) + " Token/s, sonst bis " + de(best_tps, 1) +
                   " – belegt ein Spiel (Minecraft im Vordergrund) oder ein anderes Programm die Grafikkarte?";
        }
    }
    return {};
}

double JobStats::avg_seconds() const { return succeeded() > 0 ? seconds / succeeded() : 0.0; }

double JobStats::tokens_per_second() const {
    return gen_seconds > 0.0 ? static_cast<double>(tokens) / gen_seconds : 0.0;
}

double JobStats::reuse_share() const {
    return prompt_tokens > 0 ? static_cast<double>(reused_tokens) / static_cast<double>(prompt_tokens) : 0.0;
}

std::string JobStats::summary_de() const {
    if (jobs == 0) {
        return {};
    }
    std::string s = std::to_string(jobs) + (jobs == 1 ? " Auftrag" : " Aufträge");
    if (failed > 0) {
        s += " (" + std::to_string(failed) + " fehlgeschlagen)";
    }
    if (succeeded() > 0) {
        s += " · Ø " + de(avg_seconds(), 1) + " s · Ø " + de(tokens_per_second(), 1) + " Token/s";
        if (succeeded() > 1) {
            s += " · " + de(fastest, 1) + "–" + de(slowest, 1) + " s";
        }
        if (prompt_tokens > 0) {
            s += " · " + de(std::round(reuse_share() * 100.0), 0) + " % Prompt aus dem Speicher";
        }
    }
    return s;
}

}  // namespace swbh::gui
