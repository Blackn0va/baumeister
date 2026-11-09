// Befehlszeile: eigene Schalter werden erkannt und verbraucht, alles andere geht an Qt.

#include "test_support.hpp"

#include "gui/app_args.hpp"

#include <string>
#include <vector>

using namespace swbh::gui;

TEST_CASE("Argumente: --im-hintergrund und --data-dir werden erkannt, Unbekanntes geht an Qt") {
    const AppArgs a = parse_args({L"C:\\Prog\\BaumeisterHost.exe", L"--im-hintergrund", L"--data-dir",
                                  L"D:\\Bäume Daten", L"-platform", L"minimal"});
    CHECK(a.background);
    REQUIRE(a.data_dir.has_value());
    CHECK(a.data_dir->wstring() == L"D:\\Bäume Daten");
    CHECK_FALSE(a.screenshot.has_value());
    // Der eigene Schalter darf Qt nicht erreichen; -platform minimal muss ankommen.
    CHECK(a.qt_args == std::vector<std::string>{"C:\\Prog\\BaumeisterHost.exe", "-platform", "minimal"});
}

TEST_CASE("Argumente: ohne Schalter kein Hintergrundstart, --screenshot mit Wert") {
    const AppArgs a = parse_args({L"BaumeisterHost.exe", L"--screenshot", L"bild.png"});
    CHECK_FALSE(a.background);
    REQUIRE(a.screenshot.has_value());
    CHECK(a.screenshot->wstring() == L"bild.png");
    CHECK(a.qt_args == std::vector<std::string>{"BaumeisterHost.exe"});
}

TEST_CASE("Argumente: Schalter ohne Wert am Ende und ähnliche Schreibweisen gehen an Qt") {
    // --data-dir ohne Ordner und ein abweichend geschriebener Schalter: kein stilles Raten, Qt bekommt sie.
    const AppArgs a = parse_args({L"BaumeisterHost.exe", L"--Im-Hintergrund", L"--data-dir"});
    CHECK_FALSE(a.background);
    CHECK_FALSE(a.data_dir.has_value());
    CHECK(a.qt_args == std::vector<std::string>{"BaumeisterHost.exe", "--Im-Hintergrund", "--data-dir"});
}

TEST_CASE("Argumente: leere Liste ergibt einen Programmnamen für Qt") {
    const AppArgs a = parse_args({});
    CHECK(a.qt_args == std::vector<std::string>{"BaumeisterHost"});
    CHECK_FALSE(a.background);
}

TEST_CASE("Argumente: Zerlegung nach Windows-Regeln hält Pfade mit Leerzeichen zusammen") {
    const std::vector<std::wstring> v =
        split_command_line(L"\"C:\\Program Files\\Bau Meister\\BaumeisterHost.exe\" --data-dir \"C:\\a b\" x");
    CHECK(v == std::vector<std::wstring>{L"C:\\Program Files\\Bau Meister\\BaumeisterHost.exe", L"--data-dir",
                                         L"C:\\a b", L"x"});
}
