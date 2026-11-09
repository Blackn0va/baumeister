#include "gui/exit_report.hpp"

#include "base/fileio.hpp"
#include "base/log.hpp"

#include <system_error>

namespace swbh::gui {

std::string_view exit_reason_text(ExitReason reason) {
    switch (reason) {
        case ExitReason::TrayQuit: return "„Beenden“ im Infobereich";
        case ExitReason::WindowClosed: return "Fenster geschlossen (kein Infobereich)";
        case ExitReason::SessionEnd: return "Windows-Abmeldung oder Herunterfahren";
        case ExitReason::Screenshot: return "Bildschirmfoto fertig";
        case ExitReason::Unknown: break;
    }
    return "ohne erkannten Auslöser";
}

bool ExitReasonLatch::record(ExitReason reason) {
    ExitReason expected = ExitReason::Unknown;
    return reason != ExitReason::Unknown && value_.compare_exchange_strong(expected, reason);
}

ExitReasonLatch& exit_latch() {
    static ExitReasonLatch latch;
    return latch;
}

std::string exit_log_line(ExitReason reason, int exit_code) {
    return "Baumeister-Host beendet: " + std::string(exit_reason_text(reason)) + " (Exit " + std::to_string(exit_code) +
           ").";
}

std::optional<std::string> read_run_marker(const std::filesystem::path& marker) {
    std::error_code ec;
    if (!std::filesystem::exists(marker, ec)) {
        return std::nullopt;
    }
    try {
        return log::safe(read_file(marker));
    } catch (const FileError&) {
        // Unlesbar, aber vorhanden: Der letzte Lauf endete trotzdem nicht geordnet.
        return std::string("Markierung unlesbar");
    }
}

bool write_run_marker(const std::filesystem::path& marker, std::string_view text) {
    try {
        write_file_atomic(marker, text);
        return true;
    } catch (const FileError&) {
        return false;
    }
}

bool remove_run_marker(const std::filesystem::path& marker) {
    std::error_code ec;
    std::filesystem::remove(marker, ec);
    return !ec;
}

std::string unclean_previous_run_line(std::string_view marker_text) {
    return "Letzter Lauf endete ohne Schlusszeile (" + std::string(marker_text) +
           ") – Absturz, Task-Manager oder Stromausfall. Ein Absturz hinterlässt baumeister-absturz-*.dmp im "
           "Log-Ordner; sonst hilft die Ereignisanzeige (Anwendung, Ereignis 1000/1002).";
}

}  // namespace swbh::gui
