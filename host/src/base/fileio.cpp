#include "base/fileio.hpp"

#include "base/errors.hpp"
#include "base/paths.hpp"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <string>

namespace swbh {
namespace {

class Handle {
public:
    explicit Handle(HANDLE h) : h_(h) {}
    ~Handle() {
        if (valid()) {
            CloseHandle(h_);
        }
    }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    bool valid() const { return h_ != INVALID_HANDLE_VALUE && h_ != nullptr; }
    HANDLE get() const { return h_; }
    // Schließt vorzeitig und liefert false, wenn CloseHandle scheitert.
    bool close() {
        if (!valid()) {
            return true;
        }
        const BOOL ok = CloseHandle(h_);
        h_ = INVALID_HANDLE_VALUE;
        return ok != FALSE;
    }

private:
    HANDLE h_;
};

[[noreturn]] void fail(const char* what, const std::filesystem::path& p, DWORD err) {
    throw FileError(std::string(what) + u8str(p) + ": " + system_error_text(err), err);
}

void write_all(HANDLE h, std::string_view bytes, const std::filesystem::path& p) {
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const std::size_t chunk = std::min<std::size_t>(bytes.size() - offset, 1u << 30);
        DWORD written = 0;
        if (!WriteFile(h, bytes.data() + offset, static_cast<DWORD>(chunk), &written, nullptr)) {
            fail("Datei nicht schreibbar: ", p, GetLastError());
        }
        if (written == 0) {
            fail("Datei nicht schreibbar: ", p, ERROR_WRITE_FAULT);
        }
        offset += written;
    }
}

std::optional<std::string> read_impl(const std::filesystem::path& file, std::uint64_t max_bytes, bool missing_ok) {
    Handle h(CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    if (!h.valid()) {
        const DWORD err = GetLastError();
        if (missing_ok && (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND)) {
            return std::nullopt;
        }
        fail("Datei nicht lesbar: ", file, err);
    }
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(h.get(), &size)) {
        fail("Datei nicht lesbar: ", file, GetLastError());
    }
    if (static_cast<std::uint64_t>(size.QuadPart) > max_bytes) {
        throw FileError("Datei zu groß: " + u8str(file) + " (" + std::to_string(size.QuadPart) + " Bytes)", 0);
    }
    std::string data(static_cast<std::size_t>(size.QuadPart), '\0');
    std::size_t offset = 0;
    while (offset < data.size()) {
        const std::size_t chunk = std::min<std::size_t>(data.size() - offset, 1u << 30);
        DWORD read = 0;
        if (!ReadFile(h.get(), data.data() + offset, static_cast<DWORD>(chunk), &read, nullptr)) {
            fail("Datei nicht lesbar: ", file, GetLastError());
        }
        if (read == 0) {
            // Datei wurde während des Lesens kürzer – das Gelesene ist unvollständig.
            fail("Datei nicht lesbar: ", file, ERROR_HANDLE_EOF);
        }
        offset += read;
    }
    return data;
}

std::atomic<unsigned> g_temp_counter{0};

}  // namespace

std::string read_file(const std::filesystem::path& file, std::uint64_t max_bytes) {
    return *read_impl(file, max_bytes, false);
}

std::optional<std::string> read_file_if_exists(const std::filesystem::path& file, std::uint64_t max_bytes) {
    return read_impl(file, max_bytes, true);
}

void write_file_atomic(const std::filesystem::path& target, std::string_view bytes) {
    std::filesystem::path temp = target;
    temp += L".tmp-" + std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(++g_temp_counter);
    {
        Handle h(CreateFileW(temp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr));
        if (!h.valid()) {
            fail("Datei nicht schreibbar: ", temp, GetLastError());
        }
        try {
            write_all(h.get(), bytes, temp);
            // Erst auf die Platte, dann umbenennen: sonst kann nach einem Absturz
            // eine leere Datei unter dem Zielnamen liegen.
            if (!FlushFileBuffers(h.get())) {
                fail("Datei nicht schreibbar: ", temp, GetLastError());
            }
            if (!h.close()) {
                fail("Datei nicht schreibbar: ", temp, GetLastError());
            }
        } catch (...) {
            h.close();
            DeleteFileW(temp.c_str());
            throw;
        }
    }
    // Ein fremdes offenes Handle auf dem Ziel (Virenscanner, Backup, Sync-Client,
    // Editor) lässt das Ersetzen mit Fehler 5 bzw. 32 scheitern – auch mit
    // FILE_SHARE_DELETE (gemessen). Meist ist es Millisekunden später weg. Vorher
    // gab es genau einen Versuch: Ein automatischer Neustart nach einer
    // Dashboard-Änderung brach dann mit einem modalen Fehlerdialog ab, und die
    // Bridge blieb aus, bis jemand am PC war (Befund robustheit). Bis zu 10
    // Versuche im Abstand von 100 ms wie beim Umbenennen des Downloads (win/http.hpp).
    constexpr int kAttempts = 10;
    DWORD err = ERROR_SUCCESS;
    for (int attempt = 1; attempt <= kAttempts; ++attempt) {
        if (MoveFileExW(temp.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            return;
        }
        err = GetLastError();
        if ((err != ERROR_ACCESS_DENIED && err != ERROR_SHARING_VIOLATION) || attempt == kAttempts) {
            break;
        }
        Sleep(100);
    }
    DeleteFileW(temp.c_str());
    fail("Datei nicht ersetzbar: ", target, err);
}

void ensure_directory(const std::filesystem::path& dir) {
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec) {
        throw FileError("Ordner nicht anlegbar: " + u8str(dir) + ": " +
                            system_error_text(static_cast<unsigned long>(ec.value())),
                        static_cast<unsigned long>(ec.value()));
    }
    if (!std::filesystem::is_directory(dir, ec)) {
        throw FileError("Ordner nicht anlegbar: " + u8str(dir), ERROR_DIRECTORY);
    }
}

}  // namespace swbh
