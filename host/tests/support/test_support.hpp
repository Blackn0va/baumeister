// Hilfen für alle Tests: Pfade ins Quellverzeichnis, eigene Temp-Ordner, Dateien lesen.
//
// Regeln für Tests : kein Netz (HTTP nur über FakeTransport,
// Loopback nur gegen 127.0.0.1), keine Zugriffe auf %LOCALAPPDATA% (resolve_paths
// immer mit --data-dir bzw. resolve_paths_from), Temp-Dateien nur unter TempDir.

#pragma once

#include "doctest.h"

#include "base/fileio.hpp"
#include "base/paths.hpp"

#include <process.h>

#include <atomic>
#include <filesystem>
#include <string>
#include <string_view>
#include <system_error>

#ifndef SWBH_SOURCE_DIR
#error "SWBH_SOURCE_DIR fehlt – Tests über swbh_add_test anlegen"
#endif
#ifndef SWBH_TEST_BINARY_DIR
#error "SWBH_TEST_BINARY_DIR fehlt – Tests über swbh_add_test anlegen"
#endif

namespace swbh::test {

// Pfad relativ zur Wurzel von Baumeister-Host, z. B. source_path("src/plan/bauplan.gbnf").
inline std::filesystem::path source_path(std::string_view relative) {
    return swbh::u8path(SWBH_SOURCE_DIR) / swbh::u8path(relative);
}

// Eigener, anfangs leerer Ordner unter <Bauordner>/tmp/<name>-<pid>-<n>; wird im
// Destruktor gelöscht. Bewusst nicht %TEMP%: Tests sollen nur im Bauordner schreiben.
class TempDir {
public:
    explicit TempDir(std::string_view name) {
        static std::atomic<unsigned> counter{0};
        path_ = swbh::u8path(SWBH_TEST_BINARY_DIR) / L"tmp" /
                swbh::u8path(std::string(name) + "-" + std::to_string(_getpid()) + "-" +
                             std::to_string(++counter));
        std::error_code ec;
        std::filesystem::remove_all(path_, ec);
        swbh::ensure_directory(path_);
    }
    ~TempDir() {
        std::error_code ec;
        std::filesystem::remove_all(path_, ec);
    }
    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;

    const std::filesystem::path& path() const { return path_; }
    std::filesystem::path operator/(std::string_view relative_u8) const { return path_ / swbh::u8path(relative_u8); }

private:
    std::filesystem::path path_;
};

inline std::string read_bytes(const std::filesystem::path& file) { return swbh::read_file(file); }

}  // namespace swbh::test
