// Drossel für eine wiederkehrende Konsolenzeile: höchstens eine je Abstand; was dazwischen fällt, wird gezählt und mit
// der nächsten erlaubten Zeile gemeldet. Zuerst für den Begleiternamen aus dem Plugin (Prüfbefund 2026-11-21): Ein
// Client, der bei jeder Abholung einen anderen Namen schickt, schrieb sonst je Abholung eine Zeile in die Konsole.
//
// Threads: allow() aus jedem Thread (eigener Mutex). Fehler: keine.

#pragma once

#include <chrono>
#include <cstddef>
#include <mutex>
#include <optional>

namespace swbh::gui {

class ConsoleThrottle final {
public:
    explicit ConsoleThrottle(std::chrono::steady_clock::duration gap) : gap_(gap) {}
    ConsoleThrottle(const ConsoleThrottle&) = delete;
    ConsoleThrottle& operator=(const ConsoleThrottle&) = delete;

    // Darf die Zeile jetzt in die Konsole? Ja: Anzahl der seit der letzten Zeile unterdrückten (0 = keine). Nein:
    // nullopt, und dieser Aufruf zählt als unterdrückt.
    std::optional<std::size_t> allow(std::chrono::steady_clock::time_point now);

private:
    const std::chrono::steady_clock::duration gap_;
    std::mutex mutex_;
    std::optional<std::chrono::steady_clock::time_point> last_;
    std::size_t skipped_ = 0;
};

}  // namespace swbh::gui
