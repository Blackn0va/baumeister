// Uhren.
//
// monotonic_ms ist die Basis von started_at_ms und damit von segment_id
// ("local-<ms>-<zähler>"), die der Server in den Dedupe-Hash nimmt. Python nutzt
// time.monotonic() (unter Windows QueryPerformanceCounter); std::chrono::
// steady_clock hat dieselbe Basis (gemessen: der Python-Wert lag zwischen zwei
// C++-Lesungen). Epoch-Zeit wäre eine Verhaltensänderung.
//
// Threads: reentrant. Fehler: keine.

#pragma once

#include <cstdint>
#include <string>

namespace swbh {

// Monotone Millisekunden seit einem beliebigen festen Punkt (Systemstart),
// abgeschnitten wie int(time.monotonic() * 1000).
std::int64_t monotonic_ms() noexcept;

// Ortszeit "HH:MM:SS" (GUI-Logzeilen, spec 05 §5.10).
std::string local_hms();

// Ortszeit "YYYY-MM-DD HH:MM:SS.mmm" (Logdatei).
std::string local_timestamp_ms();

struct LocalDate {
    int year = 1970;
    int month = 1;
    int day = 1;
};

// Heutiges Datum in Ortszeit.
LocalDate local_date() noexcept;

// "YYYY-MM-DD".
std::string format_date(const LocalDate& d);

// Tage seit 1970-01-01 (proleptisch gregorianisch) – für Altersvergleiche.
std::int64_t days_since_epoch(const LocalDate& d) noexcept;

}  // namespace swbh
