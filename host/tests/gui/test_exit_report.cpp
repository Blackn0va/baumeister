// Ende-Grund und Lauf-Markierung: erster Grund gewinnt, Schlusszeile, unsauberer letzter Lauf wird erkannt.

#include "test_support.hpp"

#include "gui/exit_report.hpp"

#include <filesystem>
#include <string>

using namespace swbh::gui;
using swbh::test::TempDir;

TEST_CASE("Ende-Grund: der erste Grund gilt, eine Abmeldung wird nicht vom Fensterschließen überschrieben") {
    ExitReasonLatch latch;
    CHECK(latch.value() == ExitReason::Unknown);
    CHECK(latch.record(ExitReason::SessionEnd));
    CHECK_FALSE(latch.record(ExitReason::WindowClosed));
    CHECK(latch.value() == ExitReason::SessionEnd);
}

TEST_CASE("Ende-Grund: „unbekannt“ belegt den Riegel nicht") {
    ExitReasonLatch latch;
    CHECK_FALSE(latch.record(ExitReason::Unknown));
    CHECK(latch.record(ExitReason::TrayQuit));
    CHECK(latch.value() == ExitReason::TrayQuit);
}

TEST_CASE("Ende-Grund: Schlusszeile nennt Auslöser und Exitcode") {
    CHECK(exit_log_line(ExitReason::TrayQuit, 0) == "Baumeister-Host beendet: „Beenden“ im Infobereich (Exit 0).");
    CHECK(exit_log_line(ExitReason::SessionEnd, 0) ==
          "Baumeister-Host beendet: Windows-Abmeldung oder Herunterfahren (Exit 0).");
    CHECK(exit_log_line(ExitReason::Unknown, 1) == "Baumeister-Host beendet: ohne erkannten Auslöser (Exit 1).");
}

TEST_CASE("Lauf-Markierung: steht sie beim Start noch da, endete der letzte Lauf unsauber") {
    TempDir dir("lauf-markierung");
    const std::filesystem::path marker = dir.path() / kRunMarkerName;
    CHECK_FALSE(read_run_marker(marker).has_value());  // erster Start: nichts zu melden
    REQUIRE(write_run_marker(marker, "gestartet 2026-11-01 11:20:00.000, Version 0.1.0, Prozess 42"));
    const auto previous = read_run_marker(marker);
    REQUIRE(previous.has_value());
    CHECK(*previous == "gestartet 2026-11-01 11:20:00.000, Version 0.1.0, Prozess 42");
    CHECK(unclean_previous_run_line(*previous).rfind(
              "Letzter Lauf endete ohne Schlusszeile (gestartet 2026-11-01 11:20:00.000, Version 0.1.0, Prozess 42)",
              0) == 0);
    REQUIRE(remove_run_marker(marker));
    CHECK_FALSE(read_run_marker(marker).has_value());  // geordnetes Ende: nächster Start bleibt still
    CHECK(remove_run_marker(marker));                  // fehlende Markierung ist kein Fehler
}

TEST_CASE("Lauf-Markierung: Zeilenumbrüche aus der Datei landen nicht als eigene Logzeilen") {
    TempDir dir("lauf-markierung-steuer");
    const std::filesystem::path marker = dir.path() / kRunMarkerName;
    REQUIRE(write_run_marker(marker, "gestartet\r\nFEHLER gefälscht"));
    const auto previous = read_run_marker(marker);
    REQUIRE(previous.has_value());
    CHECK(previous->find('\n') == std::string::npos);
    CHECK(previous->find('\r') == std::string::npos);
}
