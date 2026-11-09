// Abbruch-Marke (win/http.hpp): Nach remove_cancel_action läuft keine Aktion mehr – auch nicht eine, die ein anderer
// Thread mit cancel() gerade übernommen hat (zweite Sicherheitsprüfung 2026-11-08, N10). Vorher konnte connect_pinned_ws
// zurückkehren und sein Anfrage-Handle zerstören, während die Aktion es noch schloss.

#include "test_support.hpp"

#include "win/http.hpp"

#include <atomic>
#include <chrono>
#include <thread>

using namespace std::chrono_literals;

TEST_CASE("Abbruch: Abmelden wartet, bis eine laufende Abbruch-Aktion fertig ist") {
    swbh::win::CancelToken token;
    std::atomic<bool> started{false};
    std::atomic<bool> finished{false};
    const std::uint64_t id = token.add_cancel_action([&] {
        started = true;
        std::this_thread::sleep_for(200ms);
        finished = true;
    });
    std::thread canceller([&] { token.cancel(); });
    while (!started) {
        std::this_thread::sleep_for(1ms);
    }
    token.remove_cancel_action(id);
    CHECK(finished.load());  // ohne das Warten: Abmelden kehrt mitten in der Aktion zurück
    canceller.join();
}

TEST_CASE("Abbruch: eine Aktion darf sich selbst abmelden, ohne sich zu verklemmen") {
    swbh::win::CancelToken token;
    std::uint64_t id = 0;
    std::atomic<bool> ran{false};
    id = token.add_cancel_action([&] {
        token.remove_cancel_action(id);
        ran = true;
    });
    token.cancel();
    CHECK(ran.load());
    CHECK(token.cancelled());
}
