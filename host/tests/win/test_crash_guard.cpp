// Absturz-Protokoll: Namen, Logzeile, Aufräumen – und echte Abstürze, einmal im Prozess abgefangen und zweimal in einem
// Kindprozess über den Filter bzw. std::terminate (der Weg, den die App im Ernstfall nimmt).

#include "test_support.hpp"

#include "base/fileio.hpp"
#include "base/log.hpp"
#include "base/utf8.hpp"
#include "win/crash_guard.hpp"

#include <windows.h>

#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <stdexcept>
#include <string>

using namespace swbh::win;
using swbh::test::TempDir;

namespace {

constexpr wchar_t kChildDirVar[] = L"SWBH_CRASH_CHILD_DIR";
constexpr wchar_t kChildModeVar[] = L"SWBH_CRASH_CHILD_MODE";

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

void touch(const std::filesystem::path& file) { swbh::write_file_atomic(file, "x"); }

std::size_t count_dumps(const std::filesystem::path& dir, std::filesystem::path* last = nullptr) {
    std::size_t n = 0;
    for (const auto& e : std::filesystem::directory_iterator(dir)) {
        if (e.path().extension() == L".dmp") {
            ++n;
            if (last != nullptr) {
                *last = e.path();
            }
        }
    }
    return n;
}

std::string all_logs(const std::filesystem::path& dir) {
    std::string text;
    for (const auto& e : std::filesystem::directory_iterator(dir)) {
        if (e.path().extension() == L".log") {
            text += swbh::read_file(e.path());
        }
    }
    return text;
}

// Nur PODs in dieser Funktion: __try verträgt keine Objekte mit Destruktor.
int report_filter(EXCEPTION_POINTERS* ep, const std::filesystem::path* dir, bool* written) {
    *written = write_crash_report(*dir, ep, "Testabsturz im Prozess").has_value();
    return EXCEPTION_EXECUTE_HANDLER;
}

bool crash_and_catch(const std::filesystem::path* dir) {
    bool written = false;
    __try {
        volatile int* volatile p = nullptr;
        *p = 1;
    } __except (report_filter(GetExceptionInformation(), dir, &written)) {
    }
    return written;
}

std::wstring env(const wchar_t* name) {
    wchar_t buf[1024];
    const DWORD n = GetEnvironmentVariableW(name, buf, 1024);
    return n > 0 && n < 1024 ? std::wstring(buf, n) : std::wstring();
}

// Startet diese Test-EXE erneut, nur mit dem Kind-Fall, und liefert den Exitcode.
DWORD run_child(const std::filesystem::path& dir, const wchar_t* mode) {
    wchar_t exe[MAX_PATH];
    REQUIRE(GetModuleFileNameW(nullptr, exe, MAX_PATH) > 0);
    std::wstring cmd = L"\"" + std::wstring(exe) + L"\" --no-intro=true \"-tc=Absturz-Kind*\"";
    REQUIRE(SetEnvironmentVariableW(kChildDirVar, dir.c_str()));
    REQUIRE(SetEnvironmentVariableW(kChildModeVar, mode));
    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};
    const BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr,
                                   &si, &pi);
    SetEnvironmentVariableW(kChildDirVar, nullptr);
    SetEnvironmentVariableW(kChildModeVar, nullptr);
    REQUIRE(ok);
    const DWORD wait = WaitForSingleObject(pi.hProcess, 120000);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    if (wait != WAIT_OBJECT_0) {
        TerminateProcess(pi.hProcess, 1);
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    REQUIRE(wait == WAIT_OBJECT_0);
    return code;
}

[[noreturn]] void throw_through_noexcept() noexcept(false) { throw std::runtime_error("Testfehler im Kind"); }

void terminate_now() noexcept {
    // noexcept + throw = std::terminate, wie eine Ausnahme, die aus einem Qt-Slot oder Thread fällt.
    throw_through_noexcept();
}

}  // namespace

TEST_CASE("Absturz-Kind: stürzt nur ab, wenn der Elterntest es verlangt") {
    const std::wstring dir = env(kChildDirVar);
    if (dir.empty()) {
        return;  // normaler Lauf: nichts zu tun
    }
    // Kein Windows-Fehlerdialog im Test – er hielte das Kind fest, bis jemand klickt.
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);
    REQUIRE(swbh::log::open_dir(dir));
    REQUIRE(install_crash_guard(dir));
    if (env(kChildModeVar) == L"terminate") {
        terminate_now();
    }
    volatile int* volatile p = nullptr;
    *p = 1;
}

TEST_CASE("Absturz-Protokoll: Abbildname sortiert sich nach Datum, mit führenden Nullen") {
    CHECK(crash_dump_name(at(2026, 11, 1, 9, 5, 7)) == L"baumeister-absturz-20261101-090507.dmp");
}

TEST_CASE("Absturz-Protokoll: Ausnahme mit Code, deutschem Namen und Adresse") {
    CHECK(exception_cause(0xC0000005UL, 0x7FF6A1B2C3D4ULL) ==
          "Ausnahme 0xC0000005 (Zugriffsverletzung) bei 0x00007FF6A1B2C3D4");
    CHECK(exception_cause(0xC00000FDUL, 0) == "Ausnahme 0xC00000FD (Stapelüberlauf) bei 0x0000000000000000");
    CHECK(exception_cause(0x12345678UL, 1).find("(unbekannte Ausnahme)") != std::string::npos);
}

TEST_CASE("Absturz-Protokoll: Logzeile nennt Abbild oder den Grund, warum es fehlt") {
    CHECK(crash_log_line("Ausnahme X", std::wstring(L"baumeister-absturz-20261101-090507.dmp"), "") ==
          "Absturz: Ausnahme X – Abbild gespeichert: baumeister-absturz-20261101-090507.dmp (im Log-Ordner, bitte mit "
          "dieser Logdatei weitergeben)");
    CHECK(crash_log_line("std::terminate", std::nullopt, "dbghelp.dll nicht geladen") ==
          "Absturz: std::terminate – kein Abbild (dbghelp.dll nicht geladen)");
}

TEST_CASE("Absturz-Protokoll: Aufräumen hält die neuesten Abbilder und lässt fremde Dateien stehen") {
    TempDir dir("absturz-aufraeumen");
    for (int i = 1; i <= 7; ++i) {
        touch(dir.path() / crash_dump_name(at(2026, 10, i, 12, 0, 0)));
    }
    touch(dir.path() / L"baumeister-2026-10-01.log");
    touch(dir.path() / L"fremd.dmp");
    CHECK(prune_crash_dumps(dir.path(), 5) == 2);
    CHECK_FALSE(std::filesystem::exists(dir.path() / crash_dump_name(at(2026, 10, 1, 12, 0, 0))));
    CHECK_FALSE(std::filesystem::exists(dir.path() / crash_dump_name(at(2026, 10, 2, 12, 0, 0))));
    CHECK(std::filesystem::exists(dir.path() / crash_dump_name(at(2026, 10, 7, 12, 0, 0))));
    CHECK(std::filesystem::exists(dir.path() / L"baumeister-2026-10-01.log"));
    CHECK(std::filesystem::exists(dir.path() / L"fremd.dmp"));
    CHECK(prune_crash_dumps(dir.path(), 5) == 0);
}

TEST_CASE("Absturz-Protokoll: echte Zugriffsverletzung im Prozess ergibt ein lesbares Abbild und eine Logzeile") {
    TempDir dir("absturz-prozess");
    REQUIRE(swbh::log::open_dir(dir.path()));
    const bool written = crash_and_catch(&dir.path());
    swbh::log::close();
    CHECK(written);
    std::filesystem::path dump;
    REQUIRE(count_dumps(dir.path(), &dump) == 1);
    CHECK(swbh::read_file(dump).rfind("MDMP", 0) == 0);  // Signatur einer Minidump-Datei
    CHECK(all_logs(dir.path()).find("Absturz: Testabsturz im Prozess – Abbild gespeichert: baumeister-absturz-") !=
          std::string::npos);
}

TEST_CASE("Absturz-Protokoll: unbehandelte Zugriffsverletzung im Kindprozess landet über den Filter im Log-Ordner") {
    TempDir dir("absturz-kind");
    const DWORD code = run_child(dir.path(), L"zugriff");
    CHECK(code == 0xC0000005UL);
    std::filesystem::path dump;
    REQUIRE(count_dumps(dir.path(), &dump) == 1);
    CHECK(swbh::read_file(dump).rfind("MDMP", 0) == 0);
    CHECK(all_logs(dir.path()).find("Absturz: Ausnahme 0xC0000005 (Zugriffsverletzung) bei 0x") != std::string::npos);
}

TEST_CASE("Absturz-Protokoll: Ausnahme durch noexcept im Kindprozess landet über std::terminate im Log-Ordner") {
    TempDir dir("absturz-terminate");
    run_child(dir.path(), L"terminate");
    CHECK(count_dumps(dir.path()) == 1);
    CHECK(all_logs(dir.path()).find("Absturz: std::terminate: Testfehler im Kind – Abbild gespeichert") !=
          std::string::npos);
}
