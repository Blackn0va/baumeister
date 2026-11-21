// Plan und Meldungen aus der 250-ms-Abfrage des Hauptfensters, herausgelöst aus MainWindow::refresh/notify_events.
//
// Warum eigene Klasse: Notifier und notice_to_show waren getestet, die Verdrahtung dazwischen nicht – woher der Plan
// kommt (nur bei neuer Controller::last_plan_seq geholt, Prüfbefund 2026-10-29), welcher Plan in den Notifier geht und
// wie der Schalter „Meldungen“ beide Stufen erreicht (host-Gedächtnis „Offen“, Auftrag T-007 2026-11-21). Ohne Fenster
// und ohne Controller testbar (tests/gui/test_window_wiring.cpp): Ein neuer Plan wird genau einmal gemeldet, ein
// gleichbleibender nicht erneut. Das Fenster liest hier nur noch ab und zeigt.
//
// Threads: nur GUI-Thread. Fehler: keine.

#pragma once

#include "gui/notifier.hpp"
#include "gui/plan_view.hpp"
#include "tunnel/tunnel.hpp"

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace swbh::gui {

// Felder aus Snapshot, die den Meldungen gelten (Snapshot selbst liegt im Qt-Teil, controller.hpp).
struct FeedState {
    bool model_loaded = false;
    std::string model_title;
    tunnel::State tunnel = tunnel::State::Stopped;
    std::string tunnel_text;
    std::uint64_t tunnel_generation = 0;
    bool via_address = false;  // Snapshot::relay_enabled
};

struct FeedPoll {
    bool plan_changed = false;     // plan() ist neu: Vorschau neu zeigen
    std::optional<Notice> notice;  // jetzt im Infobereich zeigen
};

class WindowFeed final {
public:
    // Eine Abfrage. fetch_plan wird nur gerufen, wenn plan_seq von der zuletzt geholten Nummer abweicht – der Plan ist
    // bis einige KiB groß. gate.enabled ist der Schalter „Meldungen“: Der Notifier läuft trotzdem mit (sonst käme
    // beim Wiedereinschalten alles nach), nur gezeigt wird nichts.
    FeedPoll poll(const FeedState& s, std::uint64_t plan_seq, const std::function<PlanRecord()>& fetch_plan,
                  const NoticeGate& gate);
    const PlanRecord& plan() const { return plan_; }

private:
    Notifier notifier_;
    std::vector<Notice> pending_;  // von einer Fehler-Sprechblase verdrängt (notice_to_show)
    std::uint64_t plan_seq_ = 0;
    PlanRecord plan_;  // Kopie von Controller::last_plan(), nur bei neuer seq geholt
};

}  // namespace swbh::gui
