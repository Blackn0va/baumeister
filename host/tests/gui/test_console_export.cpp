// Live-Konsole sichern: Dateiname, Kopfzeile, Zeilenenden, Umlaute und atomares Schreiben.

#include "test_support.hpp"

#include "base/fileio.hpp"
#include "gui/console_export.hpp"

#include <ctime>
#include <string>

using namespace swbh::gui;

namespace {

std::tm at(int y, int mo, int d, int h, int mi, int s) {
    std::tm t{};
    t.tm_year = y - 1900;
    t.tm_mon = mo - 1;
    t.tm_mday = d;
    t.tm_hour = h;
    t.tm_min = mi;
    t.tm_sec = s;
    return t;
}

}  // namespace

TEST_CASE("Konsole sichern: Dateiname sortiert sich nach Datum, mit führenden Nullen") {
    CHECK(console_file_name(at(2026, 11, 1, 9, 5, 7)) == L"baumeister-konsole-20261101-090507.txt");
}

TEST_CASE("Konsole sichern: Kopfzeile, CRLF ohne Verdopplung und abschließender Umbruch") {
    const std::string text = console_export_text("1.2.3", at(2026, 11, 1, 14, 3, 9), "Anfrage\nHäuschen\r\nEnde");
    CHECK(text.rfind("StreamWizard Baumeister-Host 1.2.3 – Konsole gesichert 01.11.2026 14:03:09\r\n\r\n", 0) == 0);
    CHECK(text.find("Anfrage\r\nHäuschen\r\nEnde\r\n") != std::string::npos);
    CHECK(text.find("\r\r") == std::string::npos);
    CHECK(text.size() >= 2);
    CHECK(text.substr(text.size() - 2) == "\r\n");
}

TEST_CASE("Konsole sichern: leere Konsole ergibt nur die Kopfzeile") {
    const std::string text = console_export_text("1.0", at(2026, 1, 2, 3, 4, 5), "");
    CHECK(text == "StreamWizard Baumeister-Host 1.0 – Konsole gesichert 02.01.2026 03:04:05\r\n\r\n");
}

TEST_CASE("Konsole sichern: Datei enthält genau den Text, Umlaute als UTF-8") {
    swbh::test::TempDir dir("konsole");
    const auto file = dir.path() / L"Konsole Ärger.txt";
    save_console_text(file, "1.0", at(2026, 11, 1, 0, 0, 0), "Grüße\n");
    const std::string back = swbh::read_file(file);
    CHECK(back == console_export_text("1.0", at(2026, 11, 1, 0, 0, 0), "Grüße\n"));
    CHECK(back.find("Gr\xC3\xBC" "\xC3\x9F" "e") != std::string::npos);
}
