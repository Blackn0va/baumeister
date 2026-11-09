#include "base/log.hpp"

#include "base/hash.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"

#include <windows.h>

#include <chrono>
#include <mutex>
#include <system_error>

namespace swbh::log {
namespace {

constexpr std::string_view kPrefix = "baumeister-";
constexpr std::string_view kSuffix = ".log";
constexpr std::size_t kMaxLineCodepoints = 4000;

struct State {
    std::mutex mutex;
    Sink sink;
    std::function<LocalDate()> date_source;
    std::filesystem::path dir;
    int keep_days = 14;
    bool file_enabled = false;
    HANDLE file = INVALID_HANDLE_VALUE;
    std::filesystem::path file_path;
    std::optional<LocalDate> file_date;
    bool failure_reported = false;
};

State& state() {
    static State s;
    return s;
}

bool same_date(const LocalDate& a, const LocalDate& b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
}

LocalDate today_locked(State& s) { return s.date_source ? s.date_source() : local_date(); }

void close_file_locked(State& s) {
    if (s.file != INVALID_HANDLE_VALUE) {
        CloseHandle(s.file);
        s.file = INVALID_HANDLE_VALUE;
    }
    s.file_path.clear();
    s.file_date.reset();
}

// Öffnet die Tagesdatei für date. Liefert den Windows-Fehler oder 0.
DWORD open_file_locked(State& s, const LocalDate& date) {
    close_file_locked(s);
    const std::filesystem::path path = s.dir / u8path(file_name_for(date));
    // FILE_APPEND_DATA: jede Zeile landet am Ende, auch wenn ein zweiter Prozess
    // (z. B. CLI neben GUI) dieselbe Datei offen hat.
    HANDLE h = CreateFileW(path.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return GetLastError();
    }
    s.file = h;
    s.file_path = path;
    s.file_date = date;
    cleanup_old(s.dir, date, s.keep_days);
    return 0;
}

const char* level_label(Level level) {
    switch (level) {
        case Level::Warn:
            return "WARNUNG";
        case Level::Error:
            return "FEHLER";
        case Level::Info:
        default:
            return "INFO";
    }
}

void debug_output(std::string_view line) {
    std::wstring w = widen(line);
    w.push_back(L'\n');
    OutputDebugStringW(w.c_str());
}

}  // namespace

void set_sink(Sink sink) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    s.sink = std::move(sink);
}

void reset_sink() { set_sink(nullptr); }

bool open_dir(const std::filesystem::path& dir, int keep_days) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    close_file_locked(s);
    s.file_enabled = false;
    s.failure_reported = false;
    s.dir = dir;
    s.keep_days = keep_days < 1 ? 1 : keep_days;
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec) {
        return false;
    }
    if (open_file_locked(s, today_locked(s)) != 0) {
        return false;
    }
    s.file_enabled = true;
    return true;
}

void close() {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    close_file_locked(s);
    s.file_enabled = false;
}

std::optional<std::filesystem::path> current_file() {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (s.file == INVALID_HANDLE_VALUE) {
        return std::nullopt;
    }
    return s.file_path;
}

void write(Level level, std::string_view message) {
    std::string line;
    try {
        line = local_timestamp_ms() + " [" + level_label(level) + "] " + safe(message, kMaxLineCodepoints);
    } catch (const std::exception&) {
        // Nur bad_alloc denkbar; das Log darf den Aufrufer nie zum Absturz bringen.
        return;
    }
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    bool delivered = false;
    std::string failure;
    if (s.file_enabled) {
        const LocalDate today = today_locked(s);
        if (!s.file_date || !same_date(*s.file_date, today) || s.file == INVALID_HANDLE_VALUE) {
            const DWORD err = open_file_locked(s, today);
            if (err != 0) {
                failure = "Logdatei nicht schreibbar: " + u8str(s.dir) + " (Fehler " + std::to_string(err) + ")";
            }
        }
        if (s.file != INVALID_HANDLE_VALUE) {
            const std::string bytes = line + "\n";
            DWORD written = 0;
            if (WriteFile(s.file, bytes.data(), static_cast<DWORD>(bytes.size()), &written, nullptr) &&
                written == bytes.size()) {
                delivered = true;
            } else {
                failure = "Logdatei nicht schreibbar: " + u8str(s.file_path) + " (Fehler " +
                          std::to_string(GetLastError()) + ")";
                // Beim nächsten Eintrag neu öffnen (z. B. nach kurzzeitiger Sperre).
                close_file_locked(s);
            }
        }
    }
    if (!failure.empty() && !s.failure_reported && s.sink) {
        s.failure_reported = true;
        s.sink(Level::Warn, failure);
    }
    if (s.sink) {
        s.sink(level, line);
        delivered = true;
    }
    if (!delivered) {
        debug_output(line);
    }
}

std::string ref(std::string_view secret) {
    if (secret.empty()) {
        return "leer";
    }
    return sha256_hex(secret).substr(0, 8);
}

std::string safe(std::string_view value, std::size_t max_codepoints) {
    std::string cleaned;
    cleaned.reserve(value.size());
    std::size_t pos = 0;
    std::size_t count = 0;
    bool truncated = false;
    while (pos < value.size()) {
        char32_t cp = decode_next(value, pos);
        // Zeilen- und Absatztrenner zählen mit: sonst ließen sich über Namen oder
        // Serverantworten gefälschte Logzeilen einschleusen.
        if (cp < 0x20 || cp == 0x7F || cp == 0x85 || cp == 0x2028 || cp == 0x2029) {
            cp = U' ';
        }
        if (count == max_codepoints) {
            truncated = true;
            break;
        }
        append_utf8(cleaned, cp);
        ++count;
    }
    if (truncated) {
        cleaned += "\xE2\x80\xA6";  // …
    }
    return cleaned;
}

std::string file_name_for(const LocalDate& date) {
    return std::string(kPrefix) + format_date(date) + std::string(kSuffix);
}

std::optional<LocalDate> parse_file_name(std::string_view name) {
    if (name.size() != kPrefix.size() + 10 + kSuffix.size() || name.substr(0, kPrefix.size()) != kPrefix ||
        name.substr(name.size() - kSuffix.size()) != kSuffix) {
        return std::nullopt;
    }
    const std::string_view date = name.substr(kPrefix.size(), 10);
    auto digits = [&](std::size_t from, std::size_t len, int& out) {
        out = 0;
        for (std::size_t i = from; i < from + len; ++i) {
            if (date[i] < '0' || date[i] > '9') {
                return false;
            }
            out = out * 10 + (date[i] - '0');
        }
        return true;
    };
    LocalDate d;
    if (date[4] != '-' || date[7] != '-' || !digits(0, 4, d.year) || !digits(5, 2, d.month) ||
        !digits(8, 2, d.day)) {
        return std::nullopt;
    }
    const std::chrono::year_month_day ymd{std::chrono::year{d.year}, std::chrono::month{static_cast<unsigned>(d.month)},
                                          std::chrono::day{static_cast<unsigned>(d.day)}};
    if (!ymd.ok()) {
        return std::nullopt;
    }
    return d;
}

std::size_t cleanup_old(const std::filesystem::path& dir, const LocalDate& today, int keep_days) {
    if (keep_days < 1) {
        keep_days = 1;
    }
    const std::int64_t today_days = days_since_epoch(today);
    std::size_t removed = 0;
    std::error_code ec;
    std::filesystem::directory_iterator it(dir, ec);
    if (ec) {
        return 0;
    }
    // Nicht per Bereichs-for: dessen operator++ wirft filesystem_error bei jedem
    // Lesefehler (Datenordner auf einer getrennten Netzfreigabe), und cleanup_old läuft
    // in log::write beim Tageswechsel – laut Vertrag wirft write() nie (Befund code-rand).
    for (; it != std::filesystem::directory_iterator(); it.increment(ec)) {
        if (ec) {
            break;  // Rest beim nächsten Tageswechsel
        }
        const std::filesystem::directory_entry& entry = *it;
        std::error_code entry_ec;
        if (!entry.is_regular_file(entry_ec)) {
            continue;
        }
        const std::string fname = u8str(entry.path().filename());
        const auto date = parse_file_name(fname);
        if (!date) {
            continue;
        }
        if (today_days - days_since_epoch(*date) >= keep_days) {
            std::error_code rm_ec;
            if (std::filesystem::remove(entry.path(), rm_ec)) {
                ++removed;
            }
            // rm_ec gesetzt: Datei gerade gesperrt; beim nächsten Tageswechsel neuer Versuch.
        }
    }
    return removed;
}

void set_date_source_for_tests(std::function<LocalDate()> source) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    s.date_source = std::move(source);
}

}  // namespace swbh::log
