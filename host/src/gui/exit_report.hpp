// Warum endete der Baumeister-Host? Grund beim Beenden und Hinweis auf einen unsauberen letzten Lauf.
//
// Warum: Am 2026-11-01 riss der LLM-Tunnel ab und kam nicht wieder; das Protokoll hatte zwischen 11:20 und 11:26 keine
// Zeile und am Ende nur „beendet (Exit 0)“ – ob Infobereich „Beenden“, Windows-Abmeldung oder Absturz, war nicht zu
// sehen. Jetzt:
//  - ExitReasonLatch hält den ERSTEN Grund fest (Infobereich, Fenster ohne Infobereich, Windows-Abmeldung,
//    Bildschirmfoto); die Schlusszeile nennt ihn. Erster gewinnt, weil eine Abmeldung danach auch noch das Fenster
//    schließt – sonst stünde dort „Fenster geschlossen“.
//  - Eine Lauf-Markierung (sitzung-offen.txt im Datenordner) entsteht beim Start und verschwindet beim geordneten Ende.
//    Steht sie beim nächsten Start noch da, endete der letzte Lauf ohne Schlusszeile (Absturz, der am Filter vorbeiging,
//    Task-Manager, Stromausfall) – das meldet der Start als Warnung. Wer die Markierung beim Start nicht prüft, sieht
//    genau diese Fälle wieder nur als Lücke im Protokoll.
//
// Threads: ExitReasonLatch ist threadsicher (atomar). Fehler: Die Dateifunktionen werfen nicht – das Protokoll darf am
// Start und am Ende nie an der Markierung scheitern; ein Fehler kommt als false bzw. nullopt zurück.

#pragma once

#include <atomic>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::gui {

enum class ExitReason { Unknown, TrayQuit, WindowClosed, SessionEnd, Screenshot };

// Satzteil für „Baumeister-Host beendet: <Text> (Exit n)“.
std::string_view exit_reason_text(ExitReason reason);

class ExitReasonLatch final {
public:
    // true, wenn dies der erste Grund war (dann gilt er).
    bool record(ExitReason reason);
    ExitReason value() const { return value_.load(); }

private:
    std::atomic<ExitReason> value_{ExitReason::Unknown};
};

// Der eine Riegel des Prozesses (Hauptfenster, Sitzungsende und main_gui teilen ihn).
ExitReasonLatch& exit_latch();

// Schlusszeile fürs Protokoll.
std::string exit_log_line(ExitReason reason, int exit_code);

// Lauf-Markierung: Name der Datei im Datenordner.
inline constexpr std::wstring_view kRunMarkerName = L"sitzung-offen.txt";

// Inhalt der Markierung eines noch offenen (= unsauber beendeten) Laufs, sonst nullopt. Steuerzeichen entschärft.
std::optional<std::string> read_run_marker(const std::filesystem::path& marker);

// Legt die Markierung an bzw. überschreibt sie (atomar). text: Startzeit, Version, Prozess-ID.
bool write_run_marker(const std::filesystem::path& marker, std::string_view text);

// Entfernt die Markierung beim geordneten Ende.
bool remove_run_marker(const std::filesystem::path& marker);

// Warnzeile beim Start, wenn der letzte Lauf nicht geordnet endete.
std::string unclean_previous_run_line(std::string_view marker_text);

}  // namespace swbh::gui
