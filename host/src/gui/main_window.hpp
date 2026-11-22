// Hauptfenster: fünf Karten (Modell, Rechenweg, Server, Tunnel, Letzter Auftrag), Knöpfe, Fortschritt und die
// Live-Konsole (Wunsch 2026-10-28 – jede Anfrage, der Token-Strom des
// Modells mitlaufend, Dauer, Token/s, Tunnel-Ereignisse). Schließen legt das Fenster in den Infobereich; beendet wird
// über das Tray-Menü oder „Beenden“.
//
// Verborgenes Fenster (Start mit --im-hintergrund oder geschlossen): Fehler aus Modell-Download, Modell-Laden, Server
// und Tunnel kommen zusätzlich als Warnung des Infobereich-Symbols. Vorher landeten sie nur in der Konsole – beim
// stillen Start nach der Anmeldung sah sie niemand.
//
// Rechts neben der Konsole die Plan-Vorschau (gui/plan_preview.hpp), und Meldungen im Infobereich bei Tunnel weg/wieder
// da, Modell bereit und Bauplan fertig/fehlgeschlagen (gui/notifier.hpp, abschaltbar in den Einstellungen). Diese
// Meldungen kommen auch bei sichtbarem Fenster, nur nicht, solange es das aktive Fenster ist: Wer in Minecraft steht,
// hat den Host offen im Hintergrund und sieht die Konsole trotzdem nicht.
//
// Threads: nur GUI-Thread. Fehler: über die Konsole.

#pragma once

#include "gui/controller.hpp"
#include "gui/notifier.hpp"
#include "gui/window_feed.hpp"

#include <QMainWindow>
#include <QSystemTrayIcon>

class QComboBox;
class QLabel;
class QPlainTextEdit;
class QProgressBar;
class QPushButton;
class QTabWidget;
class QTableWidget;
class QTimer;

namespace swbh::gui {

class PlanPreview;
class CityView;
class ModulesPage;

class MainWindow final : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(Controller& controller, QWidget* parent = nullptr);

    // Für Bildschirmfotos (--screenshot): Zustand jetzt einmal übernehmen.
    void refresh_now();
    void append_demo_console();
    // Für Bildschirmfotos: Beispiel-Server-Module einspielen und den Reiter „Module“ zeigen.
    void show_demo_modules();
    QWidget* modules_page() const;
    // Infobereich-Symbol vorhanden: Nur dann darf das Fenster verborgen starten (sonst käme man nicht mehr heran).
    bool has_tray() const { return tray_ != nullptr; }

protected:
    void closeEvent(QCloseEvent* event) override;

private:
    void refresh();
    void refresh_buddy();
    void append_console(const std::vector<ConsoleEntry>& entries);
    // true, wenn eine Fehler-Sprechblase gezeigt wurde.
    bool notify_hidden_errors(const std::vector<ConsoleEntry>& entries);
    // Plan in die Vorschau und Meldung in den Infobereich (gui/window_feed).
    void notify_events(const Snapshot& s, bool error_shown);
    // „Speichern …“ der Live-Konsole (console_export).
    void save_console_to_file();
    void open_settings();
    void ask_cuda();
    void choose_model(int index);
    void sync_model_choice();
    void quit_app();
    void fill_history();
    // Reiter „Module“ (gui/modules_page): eigene Module nach Konfiguration und Zustand, Server-Module bei neuer Meldung.
    void refresh_modules(const Snapshot& s);
    // Schalter auf der Seite „Module“: Schlüssel schreiben (set_config speichert und wendet Sprache/Stimme an), dazu
    // was das Modul sofort kann (Modell laden/entladen, Reiter „Stadt“ ein-/ausblenden).
    void toggle_module(const std::string& id, bool on);
    void apply_city_tab(bool visible);

    Controller& c_;
    QLabel* model_ = nullptr;
    QComboBox* model_choice_ = nullptr;
    QLabel* compute_ = nullptr;
    QLabel* server_ = nullptr;
    QLabel* tunnel_ = nullptr;
    QLabel* last_ = nullptr;
    // Karte „Begleiter“ (2026-11-04): Zustand des Sprechens, Pegel, zuletzt gehört, Mikrofontest.
    QLabel* buddy_ = nullptr;
    QProgressBar* level_ = nullptr;
    QPushButton* mic_test_ = nullptr;
    QLabel* mode_line_ = nullptr;
    QPushButton* connect_ = nullptr;
    QPushButton* ai_ = nullptr;
    QPushButton* cuda_ = nullptr;
    QPushButton* cancel_ = nullptr;
    QProgressBar* progress_ = nullptr;
    QLabel* progress_text_ = nullptr;
    QPlainTextEdit* console_ = nullptr;
    PlanPreview* preview_ = nullptr;
    QTabWidget* tabs_ = nullptr;
    QTableWidget* history_ = nullptr;
    QLabel* history_counts_ = nullptr;
    std::uint64_t history_rev_ = 0;
    CityView* city_ = nullptr;
    std::uint64_t city_seq_ = 0;
    ModulesPage* modules_ = nullptr;
    std::uint64_t modules_seq_ = 0;
    WindowFeed feed_;  // Plan (nur bei neuer seq geholt) und Meldungen im Infobereich
    QSystemTrayIcon* tray_ = nullptr;
    QTimer* timer_ = nullptr;
    bool quitting_ = false;
    bool tray_hint_shown_ = false;
    bool in_tokens_ = false;  // letzte Konsolenzeile ist ein laufender Token-Strom
};

}  // namespace swbh::gui
