// Autostart: Run-Wert mit Anführungszeichen und Schalter, Ein-/Ausschalten, Task-Manager-Vermerk.
//
// Alle Registry-Zugriffe gehen nach HKCU\Software\StreamWizard\BaumeisterHostTest – der echte Run-Schlüssel wird nie
// berührt (der erste Test prüft, dass die Testschlüssel woanders liegen). Der Ordner wird vor und nach jedem Fall mit
// RegDeleteTreeW entfernt, auch wenn ein REQUIRE abbricht.

#include "test_support.hpp"

#include "base/paths.hpp"
#include "gui/app_args.hpp"
#include "gui/autostart.hpp"

#include <windows.h>

#include <array>
#include <string>
#include <vector>

using namespace swbh::gui;

namespace {

constexpr wchar_t kTestRoot[] = L"Software\\StreamWizard\\BaumeisterHostTest";

AutostartKeys test_keys() {
    AutostartKeys k;
    k.run = std::wstring(kTestRoot) + L"\\Run";
    k.approved = std::wstring(kTestRoot) + L"\\StartupApproved\\Run";
    return k;
}

// Räumt den Testschlüssel beim Betreten und Verlassen ab.
struct TestRegistry {
    TestRegistry() { RegDeleteTreeW(HKEY_CURRENT_USER, kTestRoot); }
    ~TestRegistry() { RegDeleteTreeW(HKEY_CURRENT_USER, kTestRoot); }
    TestRegistry(const TestRegistry&) = delete;
    TestRegistry& operator=(const TestRegistry&) = delete;
};

// Schreibt einen StartupApproved-Vermerk wie der Task-Manager: Kennbyte, drei Nullbytes, 8 Byte FILETIME.
void write_approved(const AutostartKeys& k, BYTE flag) {
    HKEY key = nullptr;
    REQUIRE(RegCreateKeyExW(HKEY_CURRENT_USER, k.approved.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key,
                            nullptr) == ERROR_SUCCESS);
    const std::array<BYTE, 12> data{flag, 0, 0, 0, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x01};
    const LSTATUS st = RegSetValueExW(key, k.value_name.c_str(), 0, REG_BINARY, data.data(),
                                      static_cast<DWORD>(data.size()));
    RegCloseKey(key);
    REQUIRE(st == ERROR_SUCCESS);
}

bool approved_present(const AutostartKeys& k) {
    return RegGetValueW(HKEY_CURRENT_USER, k.approved.c_str(), k.value_name.c_str(), RRF_RT_ANY, nullptr, nullptr,
                        nullptr) == ERROR_SUCCESS;
}

}  // namespace

TEST_CASE("Autostart: Pfad mit Leerzeichen und Umlaut steht in Anführungszeichen, Schalter dahinter") {
    const std::filesystem::path exe = swbh::u8path("C:\\Program Files\\StreamWizard Bäume-Host\\BaumeisterHost.exe");
    const std::wstring v = run_value(exe);
    CHECK(v == L"\"C:\\Program Files\\StreamWizard Bäume-Host\\BaumeisterHost.exe\" --im-hintergrund");
    // Wie Windows den Wert beim Anmelden zerlegt: Programm als EIN Argument, der Schalter wird erkannt.
    const std::vector<std::wstring> argv = split_command_line(v);
    REQUIRE(argv.size() == 2);
    CHECK(argv[0] == exe.wstring());
    const AppArgs a = parse_args(argv);
    CHECK(a.background);
    CHECK(a.qt_args.size() == 1);
}

TEST_CASE("Autostart: Ein- und Ausschalten unter einem Testschlüssel") {
    const AutostartKeys real;
    const AutostartKeys k = test_keys();
    // Schutz gegen einen Umbau der Hilfe: Der Test darf nie den echten Schlüssel treffen.
    REQUIRE(k.run != real.run);
    REQUIRE(k.approved != real.approved);
    REQUIRE(k.run.rfind(L"Software\\StreamWizard\\BaumeisterHostTest\\", 0) == 0);
    TestRegistry guard;
    const std::filesystem::path exe = swbh::u8path("C:\\Program Files\\StreamWizard Baumeister-Host\\BaumeisterHost.exe");

    CHECK_FALSE(autostart_enabled(k));
    CHECK_FALSE(autostart_command(k).has_value());

    set_autostart(true, exe, k);
    CHECK(autostart_enabled(k));
    REQUIRE(autostart_command(k).has_value());
    CHECK(*autostart_command(k) == run_value(exe));

    // Zweimal einschalten ist harmlos (gleicher Wert).
    set_autostart(true, exe, k);
    CHECK(*autostart_command(k) == run_value(exe));

    set_autostart(false, exe, k);
    CHECK_FALSE(autostart_enabled(k));
    CHECK_FALSE(autostart_command(k).has_value());
    // Ausschalten ohne Eintrag ist kein Fehler.
    CHECK_NOTHROW(set_autostart(false, exe, k));
}

TEST_CASE("Autostart: im Task-Manager deaktivierter Eintrag gilt als aus") {
    const AutostartKeys k = test_keys();
    TestRegistry guard;
    const std::filesystem::path exe = swbh::u8path("C:\\Programme\\Baumeister\\BaumeisterHost.exe");
    set_autostart(true, exe, k);
    REQUIRE(autostart_enabled(k));

    write_approved(k, 0x03);
    CHECK_FALSE(autostart_enabled(k));
    // Der Run-Wert selbst steht noch – nur Windows startet ihn nicht.
    CHECK(autostart_command(k).has_value());

    write_approved(k, 0x02);
    CHECK(autostart_enabled(k));

    // Einschalten aus der App hebt den Deaktiviert-Vermerk auf; ein „an“-Vermerk bleibt unberührt.
    write_approved(k, 0x03);
    set_autostart(true, exe, k);
    CHECK(autostart_enabled(k));
    CHECK_FALSE(approved_present(k));
    write_approved(k, 0x02);
    set_autostart(true, exe, k);
    CHECK(approved_present(k));

    // Ohne Run-Wert hilft auch ein „an“-Vermerk nicht.
    set_autostart(false, exe, k);
    CHECK_FALSE(autostart_enabled(k));
}
