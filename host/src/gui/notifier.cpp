#include "gui/notifier.hpp"

#include "base/format.hpp"
#include "base/utf8.hpp"

#include <algorithm>

namespace swbh::gui {

namespace {

std::string de(double v, int digits) {
    std::string s = fmt_fixed(v, digits);
    std::replace(s.begin(), s.end(), '.', ',');
    return s;
}

std::string quoted_request(const PlanRecord& job) {
    if (job.request.empty()) {
        return "Auftrag";
    }
    return "„" + truncate_cp(job.request, 80) + "“";
}

}  // namespace

std::vector<Notice> Notifier::update(const NotifyInput& in, bool enabled) {
    std::vector<Notice> out;
    if (!primed_) {
        primed_ = true;
        model_loaded_ = in.model_loaded;
        tunnel_ = in.tunnel;
        tunnel_generation_ = in.tunnel_generation;
        job_seq_ = in.job.seq;
        return out;
    }

    if (in.model_loaded && !model_loaded_) {
        out.push_back({NoticeKind::ModelReady, "Baumeister-Host: Modell bereit",
                       (in.model_title.empty() ? std::string("Modell") : in.model_title) +
                           " ist geladen – Aufträge vom Plugin werden angenommen.",
                       false});
    }
    model_loaded_ = in.model_loaded;

    // Neue Generation = absichtlich gestoppt und neu gestartet (Einstellungen): wie „Stopped“, kein Verlust.
    const bool restarted = in.tunnel_generation != tunnel_generation_;
    tunnel_generation_ = in.tunnel_generation;
    const bool was_connected = tunnel_ == tunnel::State::Connected && !restarted;
    if (in.tunnel == tunnel::State::Stopped || restarted) {
        // Absichtlich gestoppt (Alles stoppen, Einstellungen): keine Meldung, und auch kein „wieder da“ danach.
        tunnel_lost_ = false;
    } else if (in.tunnel == tunnel::State::Connected) {
        if (tunnel_lost_) {
            out.push_back({NoticeKind::TunnelBack,
                           in.via_address ? "Baumeister-Host: Verbindung wieder da" : "Baumeister-Host: Tunnel wieder da",
                           "Das Plugin auf dem Server erreicht die App wieder.", false});
        }
        tunnel_lost_ = false;
    } else if (was_connected && !tunnel_lost_) {
        tunnel_lost_ = true;
        out.push_back({NoticeKind::TunnelLost,
                       in.via_address ? "Baumeister-Host: Verbindung getrennt" : "Baumeister-Host: Tunnel getrennt",
                       in.tunnel_text.empty()
                           ? std::string("Das Plugin erreicht das Modell nicht – neuer Versuch folgt.")
                           : truncate_cp(in.tunnel_text, 200),
                       true});
    }
    tunnel_ = in.tunnel;

    if (in.job.seq != job_seq_ && in.job.seq != 0) {
        const PlanRecord& j = in.job;
        const bool plan = j.grammar == "bauplan";
        if (j.ok) {
            out.push_back({NoticeKind::PlanDone,
                           plan ? "Baumeister-Host: Bauplan fertig" : "Baumeister-Host: Antwort fertig",
                           quoted_request(j) + ": " + std::to_string(j.tokens) + " Token in " + de(j.seconds, 1) +
                               " s (" + de(j.tokens_per_second, 1) + " Token/s)",
                           false});
        } else {
            out.push_back({NoticeKind::PlanFailed,
                           plan ? "Baumeister-Host: Bauplan fehlgeschlagen" : "Baumeister-Host: Auftrag fehlgeschlagen",
                           quoted_request(j) + ": " + truncate_cp(j.error.empty() ? "unbekannter Fehler" : j.error, 160),
                           true});
        }
    }
    job_seq_ = in.job.seq;

    if (!enabled) {
        out.clear();
    }
    return out;
}

std::optional<Notice> merge_notices(const std::vector<Notice>& notices) {
    if (notices.empty()) {
        return std::nullopt;
    }
    const auto first = std::min_element(notices.begin(), notices.end(), [](const Notice& a, const Notice& b) {
        return static_cast<int>(a.kind) < static_cast<int>(b.kind);
    });
    Notice merged = *first;
    for (auto it = notices.begin(); it != notices.end(); ++it) {
        if (it == first) {
            continue;
        }
        // Titel ohne „Baumeister-Host: “ als Zusatzzeile – der steht schon in der Überschrift.
        std::string_view t = it->title;
        constexpr std::string_view kHead = "Baumeister-Host: ";
        if (t.starts_with(kHead)) {
            t.remove_prefix(kHead.size());
        }
        merged.text += "\nAußerdem: " + std::string(t);
        merged.warning = merged.warning || it->warning;
    }
    return merged;
}

std::optional<Notice> notice_to_show(std::vector<Notice>& pending, const std::vector<Notice>& fresh,
                                     const NoticeGate& gate) {
    if (!gate.enabled || !gate.has_tray || gate.window_active) {
        pending.clear();
        return std::nullopt;
    }
    pending.insert(pending.end(), fresh.begin(), fresh.end());
    if (gate.error_shown) {
        // Windows zeigt nur die letzte Sprechblase – die Fehlerwarnung hat Vorrang, der Rest kommt eine Abfrage später.
        return std::nullopt;
    }
    std::optional<Notice> merged = merge_notices(pending);
    pending.clear();
    return merged;
}

}  // namespace swbh::gui
