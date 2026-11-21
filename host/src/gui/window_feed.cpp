#include "gui/window_feed.hpp"

namespace swbh::gui {

FeedPoll WindowFeed::poll(const FeedState& s, std::uint64_t plan_seq, const std::function<PlanRecord()>& fetch_plan,
                          const NoticeGate& gate) {
    FeedPoll out;
    // Den Plan (bis einige KiB) nur bei neuer Nummer kopieren, nicht in jeder 250-ms-Abfrage (Prüfbefund 2026-10-29).
    // Die gemerkte Nummer ist die des geholten Plans, nicht plan_seq: Kam zwischen beiden Abfragen ein weiterer Plan,
    // holt die nächste Abfrage ihn nach.
    if (plan_seq != plan_seq_) {
        plan_ = fetch_plan();
        plan_seq_ = plan_.seq;
        out.plan_changed = true;
    }
    NotifyInput in;
    in.model_loaded = s.model_loaded;
    in.model_title = s.model_title;
    in.tunnel = s.tunnel;
    in.tunnel_text = s.tunnel_text;
    in.tunnel_generation = s.tunnel_generation;
    in.via_address = s.via_address;
    in.job = plan_;
    // update() auch ohne Symbol und bei abgeschalteten Meldungen: Der Notifier muss den Zustand weiterführen, sonst
    // meldete er beim Wiedereinschalten Übergänge, die längst vorbei sind.
    const std::vector<Notice> fresh = notifier_.update(in, gate.enabled);
    // Eine Fehler-Sprechblase derselben Abfrage hat Vorrang (Windows zeigt nur die letzte), die Meldung kommt eine
    // Abfrage später; das aktive Fenster braucht keine Meldung – dort steht alles in der Konsole.
    out.notice = notice_to_show(pending_, fresh, gate);
    return out;
}

}  // namespace swbh::gui
