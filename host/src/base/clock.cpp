#include "base/clock.hpp"

#include <windows.h>

#include <chrono>
#include <cstdio>

namespace swbh {

std::int64_t monotonic_ms() noexcept {
    const auto since = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::milliseconds>(since).count();
}

std::string local_hms() {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    char buf[16];
    std::snprintf(buf, sizeof buf, "%02u:%02u:%02u", static_cast<unsigned>(st.wHour),
                  static_cast<unsigned>(st.wMinute), static_cast<unsigned>(st.wSecond));
    return buf;
}

std::string local_timestamp_ms() {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    char buf[40];
    std::snprintf(buf, sizeof buf, "%04u-%02u-%02u %02u:%02u:%02u.%03u", static_cast<unsigned>(st.wYear),
                  static_cast<unsigned>(st.wMonth), static_cast<unsigned>(st.wDay), static_cast<unsigned>(st.wHour),
                  static_cast<unsigned>(st.wMinute), static_cast<unsigned>(st.wSecond),
                  static_cast<unsigned>(st.wMilliseconds));
    return buf;
}

LocalDate local_date() noexcept {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    return LocalDate{static_cast<int>(st.wYear), static_cast<int>(st.wMonth), static_cast<int>(st.wDay)};
}

std::string format_date(const LocalDate& d) {
    char buf[16];
    std::snprintf(buf, sizeof buf, "%04d-%02d-%02d", d.year, d.month, d.day);
    return buf;
}

std::int64_t days_since_epoch(const LocalDate& d) noexcept {
    const std::chrono::year_month_day ymd{std::chrono::year{d.year},
                                          std::chrono::month{static_cast<unsigned>(d.month)},
                                          std::chrono::day{static_cast<unsigned>(d.day)}};
    return std::chrono::sys_days{ymd}.time_since_epoch().count();
}

}  // namespace swbh
