#include "gui/console_export.hpp"

#include "base/fileio.hpp"

#include <cstdio>

namespace swbh::gui {

namespace {

std::string two(int v) {
    char buf[4];
    std::snprintf(buf, sizeof buf, "%02d", v);
    return buf;
}

std::string stamp_date(const std::tm& t) {
    return std::to_string(t.tm_year + 1900) + two(t.tm_mon + 1) + two(t.tm_mday);
}

std::string stamp_time(const std::tm& t) {
    return two(t.tm_hour) + two(t.tm_min) + two(t.tm_sec);
}

}  // namespace

std::wstring console_file_name(const std::tm& local) {
    const std::string name = "baumeister-konsole-" + stamp_date(local) + "-" + stamp_time(local) + ".txt";
    return std::wstring(name.begin(), name.end());  // nur ASCII, Umwandlung ist verlustfrei
}

std::string console_export_text(std::string_view version, const std::tm& local, std::string_view console_utf8) {
    std::string out = "StreamWizard Baumeister-Host " + std::string(version) + " – Konsole gesichert " +
                      two(local.tm_mday) + "." + two(local.tm_mon + 1) + "." +
                      std::to_string(local.tm_year + 1900) + " " + two(local.tm_hour) + ":" + two(local.tm_min) +
                      ":" + two(local.tm_sec) + "\r\n\r\n";
    out.reserve(out.size() + console_utf8.size() + console_utf8.size() / 40 + 2);
    for (std::size_t i = 0; i < console_utf8.size(); ++i) {
        const char c = console_utf8[i];
        if (c == '\r') {
            continue;  // vorhandene CRLF nicht verdoppeln
        }
        if (c == '\n') {
            out += "\r\n";
        } else {
            out += c;
        }
    }
    if (out.size() < 2 || out.compare(out.size() - 2, 2, "\r\n") != 0) {
        out += "\r\n";
    }
    return out;
}

void save_console_text(const std::filesystem::path& file, std::string_view version, const std::tm& local,
                       std::string_view console_utf8) {
    write_file_atomic(file, console_export_text(version, local, console_utf8));
}

}  // namespace swbh::gui
