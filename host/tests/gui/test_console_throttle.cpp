// Drossel für wiederkehrende Konsolenzeilen (Begleitername aus dem Plugin, Prüfbefund 2026-11-21).

#include "test_support.hpp"

#include "gui/console_throttle.hpp"

#include <chrono>
#include <cstddef>
#include <optional>

using namespace swbh::gui;
using namespace std::chrono_literals;

TEST_CASE("Konsole: wiederkehrende Zeile höchstens einmal je Minute, Unterdrücktes zählt die nächste Zeile mit") {
    ConsoleThrottle t(1min);
    const auto t0 = std::chrono::steady_clock::time_point{} + 1h;
    CHECK(t.allow(t0) == std::optional<std::size_t>(0));  // die erste Zeile immer
    CHECK_FALSE(t.allow(t0 + 1s).has_value());
    CHECK_FALSE(t.allow(t0 + 59s).has_value());
    CHECK(t.allow(t0 + 60s) == std::optional<std::size_t>(2));  // zwei dazwischen nicht gemeldet
    CHECK_FALSE(t.allow(t0 + 61s).has_value());
    CHECK(t.allow(t0 + 10min) == std::optional<std::size_t>(1));
    CHECK(t.allow(t0 + 20min) == std::optional<std::size_t>(0));
}
