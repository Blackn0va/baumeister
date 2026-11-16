// Einstieg von BaumeisterHost.exe (Qt 6 Widgets).
//
// Reihenfolge wie kraken/CaptionBridgeGui (dort gemessen und begründet):
//  1. DLL-Suche härten (keine DLL aus Arbeitsverzeichnis/PATH).
//  2. Erhöht gestartet → Abbruch: Die App liest Einstellungen und Modelle aus %LOCALAPPDATA%, die jedes Programm des
//     Kontos ändern kann; erhöht wäre das eine Rechteausweitung (ASVS V1.4). Kein Neustart-Trick wie in kraken –
//     die Meldung reicht hier.
//  3. Plattform-Plugin vorhanden? Sonst klare Meldung statt Qt-Absturzdialog.
//  4. Eine Instanz je Sitzung (Mutex): Zwei Instanzen stritten um Port und Tunnel. Ein zweiter Start mit
//     --im-hintergrund (Run-Wert nach der Anmeldung, während schon eine Instanz läuft) endet still mit Exit 0 und
//     einer Logzeile – eine Fehlerbox beim Anmelden für etwas, das in Ordnung ist, wäre nur lästig. Ohne den Schalter
//     (Doppelklick) bleibt die Box: Dort will der Nutzer wissen, warum kein Fenster kommt.
//  5. Daten unter %LOCALAPPDATA%\StreamWizard\Baumeister, Protokoll öffnen, Absturz-Protokoll einrichten
//     (win/crash_guard.hpp) und den letzten Lauf prüfen (gui/exit_report.hpp). Beides seit dem Tunnelabriss vom
//     2026-11-01, nach dem das Protokoll nicht verriet, ob die App abgestürzt oder beendet worden war.
//  6. QApplication, Stil, Hauptfenster; mit autostart (Vorgabe) sofort Modell, Server und Tunnel starten – der
//     Nutzer startet nur die EXE, gesteuert wird vom Plugin auf dem Minecraft-Server. Mit „modell_beim_start“ aus nur
//     Server und Tunnel (Stadt-/Weltbau ohne KI). Mit --im-hintergrund bleibt das Fenster
//     verborgen, aber nur, wenn es ein Infobereich-Symbol gibt – sonst käme man an das laufende Programm nicht heran.
//
// Schalter (gui/app_args.hpp): --data-dir <ordner>, --screenshot <png> (README-Bild mit Beispielinhalt, ohne
// Autostart), --im-hintergrund (Autostart mit Windows, gui/autostart.hpp).

#include "base/clock.hpp"
#include "base/errors.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"
#include "base/version.hpp"
#include "gui/app_args.hpp"
#include "gui/app_config.hpp"
#include "gui/controller.hpp"
#include "gui/exit_report.hpp"
#include "gui/main_window.hpp"
#include "gui/settings_dialog.hpp"
#include "gui/style.hpp"
#include "win/crash_guard.hpp"
#include "win/dll_guard.hpp"

#include <windows.h>

#include <QApplication>
#include <QSessionManager>
#include <QTimer>
#include <QTranslator>

#include <exception>
#include <optional>
#include <string>
#include <system_error>
#include <vector>

namespace {

constexpr wchar_t kTitle[] = L"StreamWizard Baumeister-Host";
constexpr wchar_t kMutexName[] = L"Local\\StreamWizardBaumeisterHost";

void error_box(const std::string& text_u8) {
    const std::wstring text = swbh::widen(text_u8);
    MessageBoxW(nullptr, text.c_str(), kTitle, MB_OK | MB_ICONERROR);
}

bool elevated() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) {
        return true;  // fail-closed: unbestimmbar zählt als erhöht
    }
    TOKEN_ELEVATION e{};
    DWORD len = 0;
    const BOOL ok = GetTokenInformation(token, TokenElevation, &e, sizeof e, &len);
    CloseHandle(token);
    return !ok || e.TokenIsElevated != 0;
}

// Zweiter Start aus dem Autostart: nur eine Zeile ins Protokoll der laufenden Instanz (gleiche Tagesdatei, die Senke
// öffnet mit FILE_SHARE_WRITE und hängt an), dann Ende.
void log_second_background_start(const swbh::gui::AppArgs& args) {
    const swbh::AppPaths paths = swbh::resolve_paths(std::nullopt, args.data_dir);
    swbh::ensure_data_dirs(paths);
    if (!swbh::log::open_dir(paths.logs_dir)) {
        OutputDebugStringW(L"Baumeister-Host: Logdatei nicht nutzbar\n");
        return;
    }
    swbh::log::info("Zweiter Start mit --im-hintergrund: Baumeister-Host läuft bereits, beende still (Exit 0).");
    swbh::log::close();
}

int run_gui() {
    if (elevated()) {
        error_box("Der Baumeister-Host startet nicht mit Administratorrechten. Bitte normal über das Startmenü öffnen.");
        return 1;
    }
    swbh::gui::AppArgs args = swbh::gui::parse_command_line();
    std::error_code ec;
    if (!std::filesystem::exists(swbh::exe_dir() / L"platforms" / L"qwindows.dll", ec)) {
        error_box("Die Datei platforms\\qwindows.dll fehlt im Programmordner. Bitte den Baumeister-Host neu installieren.");
        return 1;
    }
    HANDLE mutex = CreateMutexW(nullptr, FALSE, kMutexName);
    const DWORD mutex_error = GetLastError();
    if (mutex != nullptr && mutex_error == ERROR_ALREADY_EXISTS && args.background) {
        CloseHandle(mutex);
        log_second_background_start(args);
        return 0;
    }
    if (mutex == nullptr || mutex_error == ERROR_ALREADY_EXISTS) {
        // fail-closed: Ein Mutex, der sich gar nicht anlegen ließ (mutex == nullptr), zählt weiter als „läuft schon“,
        // auch mit --im-hintergrund – lieber eine Box als zwei Instanzen, die um Port und Tunnel streiten.
        if (mutex != nullptr) {
            CloseHandle(mutex);
        }
        error_box("Der Baumeister-Host läuft bereits (Symbol im Infobereich).");
        return 1;
    }

    const swbh::AppPaths paths = swbh::resolve_paths(std::nullopt, args.data_dir);
    swbh::ensure_data_dirs(paths);
    if (!swbh::log::open_dir(paths.logs_dir)) {
        OutputDebugStringW(L"Baumeister-Host: Logdatei nicht nutzbar\n");
    }
    swbh::log::info("Baumeister-Host " + std::string(swbh::kAppVersion) + " gestartet, Daten: " +
                    swbh::log::safe(swbh::u8str(paths.data_dir)));
    if (!swbh::win::install_crash_guard(paths.logs_dir)) {
        swbh::log::warn("dbghelp.dll nicht geladen – ein Absturz hinterlässt nur eine Logzeile, kein Abbild.");
    }
    if (const std::size_t pruned = swbh::win::prune_crash_dumps(paths.logs_dir, swbh::win::kKeepCrashDumps)) {
        swbh::log::info("Alte Absturzabbilder gelöscht: " + std::to_string(pruned));
    }
    const std::filesystem::path run_marker = paths.data_dir / swbh::gui::kRunMarkerName;
    if (const auto previous = swbh::gui::read_run_marker(run_marker)) {
        swbh::log::warn(swbh::gui::unclean_previous_run_line(*previous));
    }
    const std::string run_text = "gestartet " + swbh::local_timestamp_ms() + ", Version " +
                                 std::string(swbh::kAppVersion) + ", Prozess " + std::to_string(GetCurrentProcessId());
    if (!swbh::gui::write_run_marker(run_marker, run_text)) {
        swbh::log::warn("Lauf-Markierung nicht schreibbar – ein Absturz fällt beim nächsten Start nicht auf.");
    }
    swbh::gui::ConfigLoad loaded = swbh::gui::load_config(paths.config_file);
    if (loaded.error) {
        swbh::log::error("Einstellungen unlesbar (" + *loaded.error + ") – Vorgaben aktiv");
    }
    for (const auto& n : loaded.notes) {
        swbh::log::info(n);
    }
    for (const auto& a : loaded.adjusted) {
        swbh::log::warn("Einstellung " + a + " lag außerhalb des erlaubten Bereichs und wurde angepasst");
    }
    if (!std::filesystem::exists(paths.config_file, ec)) {
        // Erste Ausführung: Vorgaben sichtbar ablegen, damit man sie in der Datei findet.
        swbh::gui::save_config(paths.config_file, loaded.config);
    }

    std::vector<char*> argv;
    for (auto& s : args.qt_args) {
        argv.push_back(s.data());
    }
    argv.push_back(nullptr);
    int argc = static_cast<int>(args.qt_args.size());
    QApplication app(argc, argv.data());
    QApplication::setApplicationName(QStringLiteral("Baumeister-Host"));
    QApplication::setApplicationVersion(QString::fromUtf8(swbh::kAppVersion.data(),
                                                          static_cast<qsizetype>(swbh::kAppVersion.size())));
    // Das Fenster darf geschlossen werden (Infobereich); beendet wird ausdrücklich.
    QApplication::setQuitOnLastWindowClosed(false);
    QTranslator qt_de;
    if (qt_de.load(QStringLiteral("qtbase_de"), QString::fromStdWString((swbh::exe_dir() / L"translations").wstring()))) {
        QApplication::installTranslator(&qt_de);
    }
    swbh::gui::style::apply(app);
    // Windows-Abmeldung/Herunterfahren (WM_QUERYENDSESSION): Die Zeile muss JETZT stehen – nach WM_ENDSESSION darf
    // Windows den Prozess beenden, bevor exec() zurückkehrt und die Schlusszeile schreibt. Die Markierung bekommt den
    // Vermerk, damit ein danach abgewürgter Lauf beim nächsten Start als „Abmeldung“ erkennbar ist.
    QObject::connect(&app, &QGuiApplication::commitDataRequest, &app, [&run_marker, &run_text](QSessionManager&) {
        if (swbh::gui::exit_latch().record(swbh::gui::ExitReason::SessionEnd)) {
            swbh::log::info("Windows meldet Abmeldung oder Herunterfahren – Tunnel und Server enden mit der App.");
            swbh::gui::write_run_marker(run_marker, run_text + ", Windows-Abmeldung angekündigt " +
                                                        swbh::local_timestamp_ms());
        }
    });

    int rc = 0;
    {
        swbh::gui::Controller controller(paths, loaded.config);
        swbh::gui::MainWindow window(controller);
        // Verborgen nur mit Infobereich-Symbol: Ohne Symbol (kein Explorer-Tray) gäbe es keinen Weg zum Fenster, und
        // der Prozess liefe unsichtbar weiter, bis man ihn im Task-Manager beendet. Das Bildschirmfoto braucht das
        // Fenster immer.
        if (args.background && !args.screenshot && window.has_tray()) {
            swbh::log::info("Start mit --im-hintergrund: Fenster bleibt verborgen (Symbol im Infobereich).");
        } else {
            if (args.background && !window.has_tray()) {
                swbh::log::warn("Start mit --im-hintergrund, aber kein Infobereich verfügbar – Fenster wird gezeigt.");
            }
            window.show();
        }
        if (args.screenshot) {
            window.append_demo_console();
            window.refresh_now();
            const std::filesystem::path target = *args.screenshot;
            QTimer::singleShot(1500, &window, [&window, &controller, target] {
                window.grab().save(QString::fromStdWString(target.wstring()));
                // Zweites Bild: der Einstellungsdialog (<name>-einstellungen.png) – für README und um Änderungen an den
                // Einstellungen ohne Klicken anzusehen (Umbau 2026-11-01).
                swbh::gui::SettingsDialog dlg(controller.config(), false, false, std::string(), {}, &window);
                dlg.show();
                dlg.adjustSize();
                QApplication::processEvents();
                std::filesystem::path second = target;
                second.replace_filename(target.stem().wstring() + L"-einstellungen" + target.extension().wstring());
                dlg.grab().save(QString::fromStdWString(second.wstring()));
                swbh::gui::exit_latch().record(swbh::gui::ExitReason::Screenshot);
                QApplication::quit();
            });
        } else if (loaded.config.autostart) {
            if (loaded.config.load_model_on_start) {
                controller.start_all();
            } else {
                controller.connect_only();
            }
        }
        rc = QApplication::exec();
        controller.shutdown();
    }
    swbh::log::info(swbh::gui::exit_log_line(swbh::gui::exit_latch().value(), rc));
    swbh::gui::remove_run_marker(run_marker);
    swbh::log::close();
    CloseHandle(mutex);
    return rc;
}

}  // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    if (!swbh::win::harden_dll_search()) {
        error_box("Der DLL-Suchpfad ließ sich nicht absichern.");
        return 1;
    }
    if (!swbh::win::harden_image_loads()) {
        error_box("Die Ladesperre für fremde DLLs ließ sich nicht setzen (Fehler " + std::to_string(GetLastError())
                  + ").");
        return 1;
    }
    try {
        return run_gui();
    } catch (const std::exception& e) {
        swbh::log::error(std::string("Baumeister-Host-Fehler: ") + swbh::log::safe(e.what()));
        error_box(std::string("Baumeister-Host-Fehler: ") + e.what());
        return 1;
    }
}
