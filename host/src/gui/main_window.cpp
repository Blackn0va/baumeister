#include "gui/main_window.hpp"

#include "base/errors.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"
#include "components/catalog.hpp"
#include "llm/models.hpp"
#include "relay/kanal.hpp"
#include "gui/autostart.hpp"
#include "gui/console_export.hpp"
#include "gui/exit_report.hpp"
#include "gui/host_modules.hpp"
#include "gui/simple_mode.hpp"
#include "gui/city_view.hpp"
#include "gui/modules_page.hpp"
#include "gui/plan_preview.hpp"
#include "gui/settings_dialog.hpp"
#include "gui/style.hpp"

#include <algorithm>
#include <cmath>
#include <QApplication>
#include <QAbstractItemView>
#include <QCloseEvent>
#include <QComboBox>
#include <QDesktopServices>
#include <QDir>
#include <QFileDialog>
#include <QFontDatabase>
#include <QFrame>
#include <QDateTime>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QMenu>
#include <QMessageBox>
#include <QPlainTextEdit>
#include <QProgressBar>
#include <QPushButton>
#include <QScrollBar>
#include <QSplitter>
#include <QStandardPaths>
#include <QTextCharFormat>
#include <QTime>
#include <QTabWidget>
#include <QTableWidget>
#include <QTimer>
#include <QUrl>
#include <QVBoxLayout>

#include <ctime>
#include <exception>
#include <filesystem>
#include <string_view>

namespace swbh::gui {

namespace {

QString qs(const std::string& s) { return QString::fromUtf8(s.data(), static_cast<qsizetype>(s.size())); }

QString num(double v, int digits) { return QLocale(QLocale::German).toString(v, 'f', digits); }

QString gb(std::uint64_t bytes) { return num(static_cast<double>(bytes) / 1e9, 1) + QStringLiteral(" GB"); }

QString dot(const QColor& c) {
    return QStringLiteral("<span style='color:%1'>●</span> ").arg(style::css_color(c));
}

QLabel* card(QGridLayout* grid, int row, int col, const QString& title, QWidget* parent) {
    auto* frame = new QFrame(parent);
    frame->setObjectName(QStringLiteral("card"));
    auto* v = new QVBoxLayout(frame);
    auto* t = new QLabel(title, frame);
    t->setObjectName(QStringLiteral("muted"));
    auto* body = new QLabel(frame);
    body->setTextFormat(Qt::RichText);
    body->setWordWrap(true);
    body->setTextInteractionFlags(Qt::TextSelectableByMouse);
    body->setMinimumHeight(body->fontMetrics().height() * 3);
    v->addWidget(t);
    v->addWidget(body, 1);
    grid->addWidget(frame, row, col);
    return body;
}

QColor kind_color(ConsoleKind k) {
    switch (k) {
    case ConsoleKind::Warn: return style::kWarn;
    case ConsoleKind::Error: return style::kSell;
    case ConsoleKind::Request: return style::kAccent;
    case ConsoleKind::Token: return style::kReading;
    case ConsoleKind::Done: return style::kBuy;
    case ConsoleKind::Tunnel: return style::kInfo;
    case ConsoleKind::Info: break;
    }
    return style::kMuted;
}

enum class HiddenErrorArea { None, Download, Model, Server, Tunnel };

// Welcher Fehler beim verborgenen Fenster eine Warnung im Infobereich bekommt.
// LÜCKE: Für Download- und Serverfehler trägt der Snapshot kein Fehlerfeld (nur downloading/server_running, die nach
// einem Fehler genauso aussehen wie nach einem Abbruch bzw. vor dem Start). Den Controller dafür zu ändern, gehört
// nicht hierher – erkannt wird deshalb der Textanfang, den controller.cpp für ConsoleKind::Error schreibt. Wer dort
// die Texte ändert, muss diese Liste mitziehen, sonst bleibt der Fehler beim verborgenen Fenster wieder stumm.
// Absichtlich ohne Warnung: „Auftrag fehlgeschlagen“ (je Anfrage, das Plugin meldet es selbst), CUDA und
// Tunnel-Passwort (beides startet nur aus dem sichtbaren Fenster).
HiddenErrorArea hidden_error_area(const ConsoleEntry& e) {
    if (e.kind != ConsoleKind::Error) {
        return HiddenErrorArea::None;
    }
    const std::string_view t = e.text;
    if (t.starts_with("Download fehlgeschlagen")) {
        return HiddenErrorArea::Download;
    }
    if (t.starts_with("Modell nicht geladen")) {
        return HiddenErrorArea::Model;
    }
    if (t.starts_with("Server nicht gestartet")) {
        return HiddenErrorArea::Server;
    }
    // „Tunnel: …“ schreibt der Controller als Error nur bei tunnel::State::Failed (Einstellungen falsch, kein
    // Neustart); „getrennt, neuer Versuch“ (State::Waiting) bleibt bewusst ohne Sprechblase, sonst käme bei fehlendem
    // Pageant-Schlüssel alle paar Sekunden eine.
    if (t.starts_with("Tunnel: ") || t.starts_with("Tunnel nicht gestartet")) {
        return HiddenErrorArea::Tunnel;
    }
    // Fernverbindung (2026-11-08): als Error nur bei „Failed“ und bei falschem Server-Pin (Alarm) – Warten auf einen
    // neuen Versuch bleibt wie beim Tunnel ohne Sprechblase.
    if (t.starts_with("Fernverbindung: ") || t.starts_with("Fernverbindung nicht gestartet")) {
        return HiddenErrorArea::Tunnel;
    }
    return HiddenErrorArea::None;
}

}  // namespace

MainWindow::MainWindow(Controller& controller, QWidget* parent) : QMainWindow(parent), c_(controller) {
    setWindowTitle(tr("StreamWizard Baumeister-Host"));
    auto* central = new QWidget(this);
    auto* root = new QVBoxLayout(central);

    auto* head = new QLabel(tr("<b style='font-size:13pt'>Baumeister-Host</b><br><span style='color:%1'>Lokales "
                               "Sprachmodell für den Baumeister-NPC auf deinem Minecraft-Server – über einen SSH-Tunnel oder "
                               "direkt auf diesem PC, nur an 127.0.0.1.</span>")
                                .arg(style::css_color(style::kMuted)),
                            central);
    root->addWidget(head);

    auto* grid = new QGridLayout();
    model_ = card(grid, 0, 0, tr("MODELL"), central);
    // Modellwahl direkt in der Karte (Nutzer 2026-10-28): Wählen lädt um, eine fehlende Datei wird nach Rückfrage
    // heruntergeladen. Reihenfolge: empfohlenes Modell zuerst, dann der Katalog (deps.lock.json), dann eigene Datei.
    model_choice_ = new QComboBox(model_->parentWidget());
    model_choice_->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
    model_choice_->setMinimumContentsLength(20);
    {
        const llm::ModelSpec& def = llm::default_model();
        model_choice_->addItem(qs(def.title), qs(def.key));
        for (const llm::ModelSpec& m : llm::selectable_models()) {
            if (m.key != def.key) {
                model_choice_->addItem(qs(m.title), qs(m.key));
            }
        }
        model_choice_->addItem(tr("Eigene GGUF-Datei …"), QString::fromUtf8(llm::kCustomModelKey.data()));
    }
    static_cast<QVBoxLayout*>(model_->parentWidget()->layout())->insertWidget(1, model_choice_);
    // Wo die Modelle liegen, sichtbar machen (Wunsch 2026-10-28): außerhalb von Programm-
    // und Bauordner, sie überstehen Neubau, Neuinstallation und Deinstallation.
    {
        const QString dir = QString::fromStdWString(c_.paths().models_dir.wstring());
        auto* where = new QLabel(tr("<a href='open'>Modellordner öffnen</a> – bleibt bei Neubau und Neuinstallation "
                                    "erhalten"),
                                 model_->parentWidget());
        where->setObjectName(QStringLiteral("muted"));
        where->setToolTip(dir);
        where->setWordWrap(true);
        connect(where, &QLabel::linkActivated, this, [dir] {
            QDir().mkpath(dir);
            QDesktopServices::openUrl(QUrl::fromLocalFile(dir));
        });
        static_cast<QVBoxLayout*>(model_->parentWidget()->layout())->addWidget(where);
    }
    connect(model_choice_, &QComboBox::activated, this, [this](int index) { choose_model(index); });
    compute_ = card(grid, 0, 1, tr("RECHENWEG"), central);
    last_ = card(grid, 0, 2, tr("LETZTER AUFTRAG"), central);
    server_ = card(grid, 1, 0, tr("SERVER"), central);
    // „VERBINDUNG“ statt „TUNNEL“ (2026-11-08): Die Karte zeigt den SSH-Tunnel ODER die Fernverbindung über die Adresse.
    tunnel_ = card(grid, 1, 1, tr("VERBINDUNG"), central);
    // Link „Server eintragen …“ bzw. „Fingerabdruck eintragen …“ öffnet die Einstellungen.
    tunnel_->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::LinksAccessibleByMouse);
    connect(tunnel_, &QLabel::linkActivated, this, [this] { open_settings(); });
    auto* buttons_frame = new QFrame(central);
    buttons_frame->setObjectName(QStringLiteral("card"));
    auto* bl = new QGridLayout(buttons_frame);
    // Zwei unabhängige Schalter statt sechs Knöpfen (Nutzer 2026-11-01, gui/simple_mode.hpp): Verbinden = Server und,
    // wenn der Minecraft-Server woanders läuft, Tunnel; KI = Sprachmodell (lädt es bei Bedarf erst herunter). Darüber ein
    // Satz, was damit gerade geht.
    mode_line_ = new QLabel(buttons_frame);
    mode_line_->setWordWrap(true);
    connect_ = new QPushButton(tr("Verbinden"), buttons_frame);
    connect_->setObjectName(QStringLiteral("primary"));
    ai_ = new QPushButton(tr("KI einschalten"), buttons_frame);
    ai_->setToolTip(tr("Lädt das Sprachmodell auf die Grafikkarte (fehlt die Datei, erst herunterladen). Nur für "
                       "Bauaufträge im Chat nötig – Stadt- und Weltbau brauchen keine KI."));
    cuda_ = new QPushButton(tr("CUDA aktivieren …"), buttons_frame);
    auto* settings = new QPushButton(tr("Einstellungen …"), buttons_frame);
    bl->addWidget(mode_line_, 0, 0, 1, 2);
    bl->addWidget(connect_, 1, 0);
    bl->addWidget(ai_, 1, 1);
    bl->addWidget(settings, 2, 0);
    bl->addWidget(cuda_, 2, 1);
    bl->setRowStretch(0, 1);
    grid->addWidget(buttons_frame, 1, 2);
    for (int i = 0; i < 3; ++i) {
        grid->setColumnStretch(i, 1);
    }
    {
        // Begleiter über die ganze Breite: links Zustand und zuletzt Gehörtes, rechts Pegel und Mikrofontest. Der Pegel
        // bewegt sich nur während einer Aufnahme – das Mikrofon ist sonst zu (voice/speech_input.hpp).
        auto* frame = new QFrame(central);
        frame->setObjectName(QStringLiteral("card"));
        auto* h = new QHBoxLayout(frame);
        auto* left = new QVBoxLayout();
        auto* t = new QLabel(tr("BEGLEITER – SPRECHEN"), frame);
        t->setObjectName(QStringLiteral("muted"));
        buddy_ = new QLabel(frame);
        buddy_->setTextFormat(Qt::RichText);
        buddy_->setWordWrap(true);
        buddy_->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::LinksAccessibleByMouse);
        connect(buddy_, &QLabel::linkActivated, this, [this] { open_settings(); });
        left->addWidget(t);
        left->addWidget(buddy_, 1);
        auto* right = new QVBoxLayout();
        level_ = new QProgressBar(frame);
        level_->setRange(0, 60);  // −60 … 0 dBFS
        level_->setTextVisible(true);
        level_->setFormat(tr("Pegel –"));
        level_->setMinimumWidth(level_->fontMetrics().horizontalAdvance(QStringLiteral("M")) * 18);
        mic_test_ = new QPushButton(tr("Mikrofon testen"), frame);
        mic_test_->setToolTip(tr("4 Sekunden sprechen: Die App spielt die Aufnahme vor und zeigt in der Konsole, was "
                                 "sie verstanden hat, mit Pegel und Verstärkung. Geht nur bei eingeschaltetem Sprechen."));
        connect(mic_test_, &QPushButton::clicked, this, [this] { c_.test_microphone(); });
        right->addWidget(level_);
        right->addWidget(mic_test_);
        right->addStretch(1);
        h->addLayout(left, 1);
        h->addLayout(right);
        grid->addWidget(frame, 2, 0, 1, 3);
    }
    root->addLayout(grid);

    auto* prog = new QHBoxLayout();
    progress_text_ = new QLabel(central);
    progress_ = new QProgressBar(central);
    progress_->setRange(0, 1000);
    cancel_ = new QPushButton(tr("Abbrechen"), central);
    prog->addWidget(progress_text_);
    prog->addWidget(progress_, 1);
    prog->addWidget(cancel_);
    root->addLayout(prog);

    // Konsole links, Plan-Vorschau rechts (Runde 5): nebeneinander statt Reiter, damit Token-Strom und fertiger Plan
    // gleichzeitig zu sehen sind. Der Teiler lässt sich ziehen.
    auto* split = new QSplitter(Qt::Horizontal, central);
    auto* console_box = new QWidget(split);
    auto* console_layout = new QVBoxLayout(console_box);
    console_layout->setContentsMargins(0, 0, 0, 0);
    auto* console_head = new QHBoxLayout();
    auto* console_title = new QLabel(tr("LIVE-KONSOLE – Anfragen, Token-Strom des Modells, Tunnel"), console_box);
    console_title->setObjectName(QStringLiteral("muted"));
    auto* clear = new QPushButton(tr("Leeren"), console_box);
    auto* save_console = new QPushButton(tr("Speichern …"), console_box);
    save_console->setToolTip(tr("Konsole als Textdatei sichern – z. B. für eine Fehlermeldung"));
    console_head->addWidget(console_title, 1);
    console_head->addWidget(save_console);
    console_head->addWidget(clear);
    console_layout->addLayout(console_head);
    console_ = new QPlainTextEdit(console_box);
    console_->setReadOnly(true);
    console_->setMaximumBlockCount(5000);
    console_->setFont(QFontDatabase::systemFont(QFontDatabase::FixedFont));
    console_->setLineWrapMode(QPlainTextEdit::WidgetWidth);
    console_layout->addWidget(console_, 1);
    auto* preview_box = new QWidget(split);
    auto* preview_layout = new QVBoxLayout(preview_box);
    preview_layout->setContentsMargins(0, 0, 0, 0);
    auto* preview_title = new QLabel(tr("PLAN-VORSCHAU – letzter Bauplan, Näherung (maßgeblich baut der Server)"),
                                     preview_box);
    preview_title->setObjectName(QStringLiteral("muted"));
    preview_title->setWordWrap(true);
    preview_layout->addWidget(preview_title);
    preview_ = new PlanPreview(preview_box);
    // Reiter: Plan (LLM-Aufträge) und Stadt (Stadt-/Weltbau, ohne LLM). Vorher gab es nur den Plan – vom Stadtbau sah
    // man in der App nichts (Nutzer 2026-10-29).
    tabs_ = new QTabWidget(preview_box);
    tabs_->addTab(preview_, tr("Plan"));
    city_ = new CityView(tabs_);
    tabs_->addTab(city_, tr("Stadt"));
    // Verlauf (2026-10-31): jeder Auftrag mit Modell, Tempo und – vom Plugin gemeldet – was im Spiel daraus wurde.
    // Vorher sah die App nur die letzte Antwort des Modells; ob gebaut oder verworfen wurde, stand im Log auf dev01.
    // Vier Spalten: Die Vorschau-Spalte ist schmal (~450 px); mit Modell, Dauer und Token/s einzeln rutschte das
    // Ergebnis aus dem Bild (Bildschirmfoto 2026-10-31). Modellname im Tooltip der LLM-Spalte.
    // Kopfzeile mit der Zählung (gebaut / selbst nachgebessert / verworfen), darunter die Tabelle.
    auto* history_page = new QWidget(tabs_);
    auto* history_layout = new QVBoxLayout(history_page);
    history_layout->setContentsMargins(0, 0, 0, 0);
    history_counts_ = new QLabel(history_page);
    history_counts_->setObjectName(QStringLiteral("muted"));
    history_counts_->setWordWrap(true);
    history_layout->addWidget(history_counts_);
    history_ = new QTableWidget(0, 4, history_page);
    history_->setHorizontalHeaderLabels({tr("Zeit"), tr("Auftrag"), tr("LLM"), tr("Ergebnis im Spiel")});
    history_->verticalHeader()->setVisible(false);
    history_->setEditTriggers(QAbstractItemView::NoEditTriggers);
    history_->setSelectionBehavior(QAbstractItemView::SelectRows);
    history_->setWordWrap(false);
    // Ergebnis bekommt den Rest, der Auftrag eine feste, verstellbare Breite – vorher teilten sich beide den Platz,
    // und beide waren abgeschnitten (Bildschirmfoto 2026-10-31). Volltext steht im Tooltip.
    history_->horizontalHeader()->setStretchLastSection(true);
    history_->setColumnWidth(1, 150);
    history_layout->addWidget(history_, 1);
    tabs_->addTab(history_page, tr("Verlauf"));
    // Module (Vertrag v012, 2026-11-22): eigene Funktionen der App als Schalter, darunter die Module des Servers.
    modules_ = new ModulesPage([this](const std::string& id, bool on) { toggle_module(id, on); }, tabs_);
    tabs_->addTab(modules_, tr("Module"));
    apply_city_tab(c_.config().city_preview);
    preview_layout->addWidget(tabs_, 1);
    split->addWidget(console_box);
    split->addWidget(preview_box);
    split->setStretchFactor(0, 3);
    split->setStretchFactor(1, 2);
    split->setChildrenCollapsible(false);
    root->addWidget(split, 1);
    setCentralWidget(central);
    resize(1240, 820);

    connect(connect_, &QPushButton::clicked, this, [this] {
        if (c_.snapshot().server_running) {
            c_.stop_all();
        } else {
            c_.connect_only();
        }
    });
    connect(ai_, &QPushButton::clicked, this, [this] {
        const Snapshot snap = c_.snapshot();
        if (snap.model == ModelPhase::Loaded) {
            c_.unload_model();
        } else if (snap.model != ModelPhase::Loading && !snap.downloading) {
            c_.enable_ai();
        }
    });
    connect(cancel_, &QPushButton::clicked, this, [this] { c_.cancel_download(); });
    connect(cuda_, &QPushButton::clicked, this, [this] { ask_cuda(); });
    connect(settings, &QPushButton::clicked, this, [this] { open_settings(); });
    connect(clear, &QPushButton::clicked, this, [this] {
        console_->clear();
        in_tokens_ = false;
    });
    connect(save_console, &QPushButton::clicked, this, [this] { save_console_to_file(); });

    if (QSystemTrayIcon::isSystemTrayAvailable()) {
        tray_ = new QSystemTrayIcon(style::app_icon(), this);
        tray_->setToolTip(tr("Baumeister-Host"));
        auto* menu = new QMenu(this);
        menu->addAction(tr("Fenster zeigen"), this, [this] {
            showNormal();
            raise();
            activateWindow();
        });
        menu->addAction(tr("Verbinden"), this, [this] { c_.connect_only(); });
        menu->addAction(tr("Trennen"), this, [this] { c_.stop_all(); });
        menu->addSeparator();
        menu->addAction(tr("KI einschalten"), this, [this] { c_.enable_ai(); });
        menu->addAction(tr("KI ausschalten"), this, [this] { c_.unload_model(); });
        menu->addSeparator();
        menu->addAction(tr("Beenden"), this, [this] { quit_app(); });
        tray_->setContextMenu(menu);
        connect(tray_, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason r) {
            if (r == QSystemTrayIcon::Trigger || r == QSystemTrayIcon::DoubleClick) {
                showNormal();
                raise();
                activateWindow();
            }
        });
        tray_->show();
    }

    timer_ = new QTimer(this);
    connect(timer_, &QTimer::timeout, this, [this] { refresh(); });
    timer_->start(250);
    refresh();
}

void MainWindow::refresh_now() { refresh(); }

void MainWindow::save_console_to_file() {
    const std::time_t now = std::time(nullptr);
    std::tm local{};
    localtime_s(&local, &now);
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
    const QString suggested = dir + QLatin1Char('/') + QString::fromStdWString(console_file_name(local));
    const QString f = QFileDialog::getSaveFileName(this, tr("Konsole speichern"), suggested,
                                                   tr("Textdateien (*.txt)"));
    if (f.isEmpty()) {
        return;
    }
    const QByteArray version = QCoreApplication::applicationVersion().toUtf8();
    const QByteArray text = console_->toPlainText().toUtf8();
    try {
        save_console_text(std::filesystem::path(f.toStdWString()), std::string_view(version.data(), version.size()),
                          local, std::string_view(text.data(), text.size()));
    } catch (const std::exception& e) {
        QMessageBox::warning(this, tr("Konsole speichern"),
                             tr("Speichern fehlgeschlagen: %1").arg(QString::fromUtf8(e.what())));
        return;
    }
    console_->appendPlainText(tr("Konsole gespeichert: %1").arg(QDir::toNativeSeparators(f)));
    in_tokens_ = false;
}

void MainWindow::append_demo_console() {
    // Wörtlich aus dem Protokoll der Messung vom 2026-10-28 (Laptop, Intel-Arc-iGPU, Qwen2.5-VL-7B, LlmProbe) – das
    // README-Bild soll keine geschönten Zahlen zeigen. Wer hier Werte ändert, nimmt sie aus einem echten Lauf.
    append_console(
        {{ConsoleKind::Info, "Modell bereit: qwen2vl ?B Q4_K - Medium · Kontext 8192 · KV F16 auf Intel(R) Graphics "
                             "(Vulkan)"},
         {ConsoleKind::Info, "Server läuft: http://127.0.0.1:8080 (nur dieser PC, /health und /v1/chat/completions)"},
         {ConsoleKind::Request, "Auftrag: „Bauauftrag: ein Turm aus Stein“ (2 Nachrichten, max. 3000 Token, Temperatur "
                                "0,40, Grammatik bauplan)"},
         {ConsoleKind::Token, "{\"design\":\"Steinturm mit Radius 3, zentriert bei x4, z4. Boden aus stone_bricks y0. "
                              "Steinrohr y1-12. …\",\"reply\":\"Ein starker Stein-Turm steht kurz vor der "
                              "Fertigstellung!\",\"steps\":[{\"op\":\"cylinder\",\"block\":\"stone_bricks\" …"},
         {ConsoleKind::Done, "Fertig in 54,1 s: 304 Token, 5,7 Token/s (Prompt 2231 Token, davon 2222 aus dem Speicher, "
                             "0,7 s)"},
         {ConsoleKind::Info, "Server: POST /v1/chat/completions → 200 (54,1 s)"}});
    // Plan-Vorschau: der Turm-Beispielplan aus dem System-Prompt des Plugins (tests/plan/data/system-prompt.txt), NICHT
    // die Antwort der Messung oben – deren Text ist nicht erhalten. Deshalb ohne Token und Zeit (die Vorschau blendet
    // Messwerte bei 0 aus) und mit Vermerk im Auftragstext.
    PlanRecord demo;
    demo.seq = 1;
    demo.request = "ein Turm aus Stein (Beispielplan aus dem System-Prompt)";
    demo.grammar = "bauplan";
    demo.ok = true;
    demo.text =
        R"({"design":"round tower radius 4 center x4 z4. floor stone_bricks y0. tube stone_bricks y1-12. windows )"
        R"(glass_pane at y5 and y9. door front at x4 z0. top floor y13, battlement ring y14 with cobblestone_wall.",)"
        R"("reply":"Ein stolzer Steinturm kommt sofort!","mode":"new","steps":[)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,0,4],"radius":4,"height":1},)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,1,4],"radius":4,"height":12,"hollow":true},)"
        R"({"op":"door","block":"spruce_door","pos":[4,1,0],"facing":"front"},)"
        R"({"op":"set","block":"glass_pane","pos":[0,5,4]},{"op":"set","block":"glass_pane","pos":[8,5,4]},)"
        R"({"op":"set","block":"glass_pane","pos":[4,9,8]},)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,13,4],"radius":4,"height":1},)"
        R"({"op":"cylinder","block":"cobblestone_wall","pos":[4,14,4],"radius":4,"height":1,"hollow":true},)"
        R"({"op":"set","block":"lantern","pos":[4,14,4]}]})";
    preview_->show_record(demo);
    preview_->set_mode(PlanPreview::Mode::Section);
    // Karte „Letzter Auftrag“: der langsame Auftrag von dev01 (2026-10-30 18:09, RTX 4070 Ti, Qwen2.5 14B) und die
    // Recherche zum Kölner Dom (Messprobe desselben Tags) – echte Zahlen, die Warnung rechnet JobStats daraus.
    llm::CompletionResult slow;
    slow.prompt_tokens = 5780;
    slow.reused_tokens = 3845;
    slow.prompt_seconds = 49.5;
    slow.generated_tokens = 396;
    slow.seconds = 152.2;
    c_.show_demo_job(slow, 3845 / 1.5, "Kölner Dom – 157 m, 1:1");
    append_console(
        {{ConsoleKind::Info, "Recherche (Internet): Kölner Dom – 157 m, 1:1 – für „bau mir den Kölner Dom“"},
         {ConsoleKind::Warn, c_.snapshot().last_warning}});
    // Ergebnisse im Spiel aus den Läufen auf der Paper-Testkopie vom 2026-10-31 (Plot-Modus, Schnellaufträge) und dem
    // verworfenen Bunker-Plan von dev01 (2026-10-30, Llama 3.1 8B) – echte Meldungen, keine erfundenen.
    server::ResultNote bunker;
    bunker.order = "baue mir einen unterirdische bunkeranlage";
    bunker.outcome = "verworfen";
    bunker.reason = "der Plan hat weiter Baufehler (Fenster nicht in der Wand).";
    c_.show_demo_result(bunker);
    server::ResultNote knox;
    knox.order = "bau mir fort knox";
    knox.outcome = "abgesagt";
    knox.reason = "Das passt nicht auf Plot 1;2 (42 × 42): Der Bau ist 44 × 53 groß.";
    c_.show_demo_result(knox);
    server::ResultNote tower;
    tower.order = "bau ein hochhaus mit 2 stockwerken";
    tower.outcome = "gebaut";
    tower.blocks = 8652;
    tower.plot = "1;1";
    c_.show_demo_result(tower);
    append_console(c_.drain_console());
    tabs_->setCurrentWidget(history_);
}

void MainWindow::append_console(const std::vector<ConsoleEntry>& entries) {
    if (entries.empty()) {
        return;
    }
    QScrollBar* bar = console_->verticalScrollBar();
    const bool at_bottom = bar->value() >= bar->maximum() - 4;
    QTextCursor cur(console_->document());
    cur.movePosition(QTextCursor::End);
    for (const ConsoleEntry& e : entries) {
        QTextCharFormat fmt;
        fmt.setForeground(kind_color(e.kind));
        if (e.kind == ConsoleKind::Token) {
            // Token-Strom: an die laufende Zeile anhängen, damit die Antwort beim Schreiben sichtbar wächst.
            if (!in_tokens_) {
                cur.insertBlock();
                in_tokens_ = true;
            }
            cur.insertText(qs(e.text), fmt);
            continue;
        }
        in_tokens_ = false;
        if (!console_->document()->isEmpty()) {
            cur.insertBlock();
        }
        const QString stamp = QTime::currentTime().toString(QStringLiteral("HH:mm:ss "));
        QTextCharFormat time_fmt;
        time_fmt.setForeground(style::kDim);
        cur.insertText(stamp, time_fmt);
        cur.insertText(qs(e.text), fmt);
    }
    if (at_bottom) {
        bar->setValue(bar->maximum());
    }
}

bool MainWindow::notify_hidden_errors(const std::vector<ConsoleEntry>& entries) {
    // Sichtbares Fenster: Der Fehler steht rot in der Konsole, eine Sprechblase obendrauf wäre doppelt. Minimiert zählt
    // als verborgen – die Konsole sieht dann auch niemand.
    if (tray_ == nullptr || (isVisible() && !isMinimized())) {
        return false;
    }
    const ConsoleEntry* last = nullptr;
    HiddenErrorArea area = HiddenErrorArea::None;
    int count = 0;
    for (const ConsoleEntry& e : entries) {
        const HiddenErrorArea a = hidden_error_area(e);
        if (a != HiddenErrorArea::None) {
            last = &e;
            area = a;
            ++count;
        }
    }
    if (last == nullptr) {
        return false;
    }
    QString title;
    switch (area) {
    case HiddenErrorArea::Download: title = tr("Baumeister-Host: Modell-Download fehlgeschlagen"); break;
    case HiddenErrorArea::Model: title = tr("Baumeister-Host: Modell nicht geladen"); break;
    case HiddenErrorArea::Server: title = tr("Baumeister-Host: Server nicht gestartet"); break;
    case HiddenErrorArea::Tunnel: title = tr("Baumeister-Host: Verbindungsfehler"); break;
    case HiddenErrorArea::None: return false;
    }
    // Eine Sprechblase je Abfrage (250 ms), nicht je Fehler: Windows zeigt ohnehin nur die letzte.
    QString text = qs(truncate_cp(last->text, 200));
    if (count > 1) {
        text += tr("\n(+%1 weitere Fehler – Details im Fenster)").arg(count - 1);
    }
    tray_->showMessage(title, text, QSystemTrayIcon::Warning);
    return true;
}

void MainWindow::notify_events(const Snapshot& s, bool error_shown) {
    // Plan holen und Meldung wählen: gui/window_feed (dort getestet, T-007). Hier nur ablesen und zeigen.
    FeedState fs;
    fs.model_loaded = s.model == ModelPhase::Loaded;
    fs.model_title = s.model_title;
    fs.tunnel = s.tunnel;
    fs.tunnel_text = s.tunnel_text;
    fs.tunnel_generation = s.tunnel_generation;
    fs.via_address = s.relay_enabled;
    NoticeGate gate;
    gate.enabled = c_.config().notifications;
    gate.has_tray = tray_ != nullptr;
    gate.window_active = isActiveWindow() && isVisible() && !isMinimized();
    gate.error_shown = error_shown;
    const FeedPoll p = feed_.poll(fs, c_.last_plan_seq(), [this] { return c_.last_plan(); }, gate);
    if (p.plan_changed) {
        preview_->show_record(feed_.plan());
    }
    if (!p.notice) {
        return;
    }
    tray_->showMessage(qs(p.notice->title), qs(p.notice->text),
                       p.notice->warning ? QSystemTrayIcon::Warning : QSystemTrayIcon::Information);
}

void MainWindow::sync_model_choice() {
    // Einträge mit Größe und ✓ für vorhandene Dateien; Auswahl folgt der Konfiguration (activated feuert nur bei
    // Bedienung, nicht bei setCurrentIndex – kein Rückkopplungsschleife).
    const AppConfig cfg = c_.config();
    for (int i = 0; i < model_choice_->count(); ++i) {
        const std::string key = model_choice_->itemData(i).toString().toStdString();
        const llm::ModelSpec* m = llm::find_model(key);
        if (m == nullptr) {
            // Eigene Datei: ihren Namen zeigen, sobald eine gewählt ist.
            const QString name = QString::fromStdWString(u8path(cfg.custom_model).filename().wstring());
            model_choice_->setItemText(i, cfg.custom_model.empty() ? tr("Eigene GGUF-Datei …")
                                                                   : tr("Eigene Datei: %1").arg(name));
            continue;
        }
        const bool present = c_.model_present(key, {});
        // Grafikspeicher je Modell, damit man auf kleineren Karten passend wählt (geschätzt, llm::estimate_vram_bytes).
        model_choice_->setItemText(i, (present ? QStringLiteral("✓ ") : QString()) + qs(m->title) +
                                          tr(" · ab %1 GPU").arg(gb(llm::estimate_vram_bytes(*m))) +
                                          (present ? QString() : tr(" · %1 laden").arg(gb(m->size))));
    }
    const int want = model_choice_->findData(qs(effective_model_key(cfg)));
    if (want >= 0 && want != model_choice_->currentIndex() && !model_choice_->view()->isVisible()) {
        model_choice_->setCurrentIndex(want);
    }
    model_choice_->setEnabled(!c_.model_busy());
}

void MainWindow::choose_model(int index) {
    const std::string key = model_choice_->itemData(index).toString().toStdString();
    std::string custom;
    if (key == llm::kCustomModelKey) {
        const QString f = QFileDialog::getOpenFileName(this, tr("GGUF-Datei wählen"), qs(c_.config().custom_model),
                                                       tr("GGUF-Modelle (*.gguf)"));
        if (f.isEmpty()) {
            sync_model_choice();
            return;
        }
        custom = QDir::toNativeSeparators(f).toStdString();
        QMessageBox::information(this, tr("Eigene Modelldatei"),
                                 tr("Eigene Dateien haben keine festgelegte Prüfsumme – nur Dateien aus "
                                    "vertrauenswürdiger Quelle verwenden. Kontext und Grafikspeicher sind geschätzt."));
    } else if (const llm::ModelSpec* m = llm::find_model(key); m != nullptr && !c_.model_present(key, {})) {
        // Eindeutige Knöpfe, „Abbrechen“ als Vorgabe: Beim Test am 2026-10-28 kam ein Klick auf „Nein“ der Ja/Nein-Frage
        // als Zustimmung an und startete 7,5 GB Download – Ursache nicht geklärt. Gewertet wird nur noch, ob genau der
        // Download-Knopf gedrückt wurde; alles andere (Abbrechen, Esc, Schließen) lässt alles, wie es ist.
        QMessageBox box(QMessageBox::Question, tr("Modell herunterladen"),
                        tr("%1 ist noch nicht auf diesem PC.\n\n%2 von huggingface.co herunterladen (Prüfsumme wird "
                           "geprüft) und danach laden?\n\nLizenz: %3")
                            .arg(qs(m->title), gb(m->size), qs(m->license)),
                        QMessageBox::NoButton, this);
        QPushButton* download = box.addButton(tr("Herunterladen und laden"), QMessageBox::AcceptRole);
        QPushButton* cancel = box.addButton(tr("Abbrechen"), QMessageBox::RejectRole);
        box.setDefaultButton(cancel);
        box.setEscapeButton(cancel);
        box.exec();
        if (box.clickedButton() != download) {
            c_.console(ConsoleKind::Info, "Modellwechsel abgebrochen – " + m->title + " wird nicht heruntergeladen.");
            sync_model_choice();
            return;
        }
    }
    c_.select_model(key, custom);
    sync_model_choice();
}

void MainWindow::refresh_buddy() {
    const AppConfig cfg = c_.config();
    const voice::SpeechStatus st = c_.speech_status();
    const auto key = voice::parse_talk_key(cfg.talk_key);
    const QString key_name = key ? qs(voice::talk_key_title(*key)) : qs(cfg.talk_key);
    QString line;
    switch (st.state) {
    case voice::SpeechState::Off:
        line = cfg.speech_in ? dot(style::kWarn) + tr("aus – Minecraft-Name fehlt oder Erkennung lädt herunter")
                             : dot(style::kDim) + tr("aus – <a href='settings'>in den Einstellungen einschalten</a>");
        break;
    case voice::SpeechState::Loading: line = dot(style::kWarn) + tr("Spracherkennung lädt …"); break;
    case voice::SpeechState::Ready:
        line = dot(style::kBuy) + tr("bereit – <b>%1</b> %2")
                                      .arg(key_name, cfg.talk_mode == "umschalten" ? tr("drücken und sprechen")
                                                                                   : tr("halten und sprechen"));
        break;
    case voice::SpeechState::Listening: line = dot(style::kAccent) + tr("<b>hört zu …</b>"); break;
    case voice::SpeechState::Recognizing: line = dot(style::kWarn) + tr("erkennt …"); break;
    case voice::SpeechState::Testing: line = dot(style::kAccent) + tr("<b>Mikrofontest – jetzt sprechen</b>"); break;
    case voice::SpeechState::Error: line = dot(style::kSell) + qs(st.error).toHtmlEscaped(); break;
    }
    if (st.clipping) {
        line += QStringLiteral("<br><span style='color:%1'>%2</span>")
                    .arg(style::css_color(style::kSell), tr("Übersteuert – Mikrofon leiser stellen oder Verstärkung "
                                                            "senken"));
    }
    if (!st.last_text.empty()) {
        line += tr("<br>Zuletzt: „%1“").arg(qs(st.last_text).toHtmlEscaped());
    }
    if (!st.last_detail.empty()) {
        line += QStringLiteral("<br><span style='color:%1'>%2</span>")
                    .arg(style::css_color(style::kDim), qs(st.last_detail).toHtmlEscaped());
    }
    buddy_->setText(line);
    const bool live = st.state == voice::SpeechState::Listening || st.state == voice::SpeechState::Testing;
    const int value = live ? std::clamp(static_cast<int>(std::lround(st.level_dbfs + 60.0)), 0, 60) : 0;
    level_->setValue(value);
    level_->setFormat(live ? tr("Pegel %1 dBFS").arg(static_cast<int>(std::lround(st.level_dbfs))) : tr("Pegel –"));
    mic_test_->setEnabled(st.state == voice::SpeechState::Ready);
}

void MainWindow::refresh() {
    const std::vector<ConsoleEntry> entries = c_.drain_console();
    append_console(entries);
    const bool error_shown = notify_hidden_errors(entries);
    const Snapshot s = c_.snapshot();
    sync_model_choice();
    // Stadt wie der Plan (gui/window_feed) nur bei neuer Nummer holen, nicht in jeder 250-ms-Abfrage.
    if (const std::uint64_t cs = c_.city_seq(); cs != city_seq_) {
        const bool first = city_seq_ == 0;
        city_seq_ = cs;
        city_->show_city(c_.city());
        // Beim ersten Stadtbau einmal auf den Reiter wechseln – danach entscheidet der Nutzer. Nicht bei
        // ausgeschalteter Stadt-Vorschau: setCurrentWidget auf einen verborgenen Reiter zeigt eine leere Fläche.
        if (first && tabs_ != nullptr && c_.config().city_preview) {
            tabs_->setCurrentWidget(city_);
        }
    }
    notify_events(s, error_shown);
    refresh_modules(s);

    switch (s.model) {
    case ModelPhase::None:
        model_->setText(dot(style::kDim) + tr("nicht geladen") +
                        (c_.model_file_present() ? QString() : tr("<br>Datei fehlt – „KI einschalten“ lädt sie herunter")));
        break;
    case ModelPhase::Loading: model_->setText(dot(style::kWarn) + tr("wird geladen …")); break;
    case ModelPhase::Loaded:
        model_->setText(dot(style::kBuy) + tr("<b>%1</b><br>%2").arg(qs(s.model_title).toHtmlEscaped(),
                                                                     qs(s.model_detail).toHtmlEscaped()) +
                        (s.model_hint.empty() ? QString()
                                              : QStringLiteral("<br><span style='color:%1'>%2</span>")
                                                    .arg(style::css_color(style::kWarn),
                                                         qs(s.model_hint).toHtmlEscaped())));
        break;
    case ModelPhase::Failed: model_->setText(dot(style::kSell) + qs(s.model_detail).toHtmlEscaped()); break;
    }
    ai_->setText(s.model == ModelPhase::Loaded                   ? tr("KI ausschalten")
                 : s.model == ModelPhase::Loading || s.downloading ? tr("KI wird geladen …")
                                                                   : tr("KI einschalten"));
    ai_->setEnabled(s.model != ModelPhase::Loading && !s.downloading);
    connect_->setText(s.server_running ? tr("Trennen") : tr("Verbinden"));
    {
        ModeInput mi;
        mi.connected = s.server_running;
        const AppConfig cfg = c_.config();
        mi.tunnel_needed = s.tunnel_enabled || s.relay_enabled;
        mi.via_address = s.relay_enabled;
        mi.tunnel_up = s.tunnel == tunnel::State::Connected;
        mi.server_set = s.relay_enabled ? !cfg.relay_pins.empty() : !cfg.target.empty();
        mi.ai = s.model == ModelPhase::Loaded     ? AiState::On
                : s.model == ModelPhase::Loading || s.downloading ? AiState::Loading
                : s.model == ModelPhase::Failed   ? AiState::Failed
                                                  : AiState::Off;
        mode_line_->setText(qs(mode_line_de(mi)));
    }

    QString gpu_lines;
    for (const llm::GpuDevice& g : s.gpus) {
        gpu_lines += tr("%1 · %2 · %3%4<br>")
                         .arg(qs(g.description).toHtmlEscaped(), qs(g.backend), gb(g.memory_total),
                              g.integrated ? tr(" · integriert") : QString());
    }
    if (gpu_lines.isEmpty()) {
        gpu_lines = tr("keine GPU erkannt<br>");
    }
    const QString active = s.model == ModelPhase::Loaded
                               ? tr("Rechnet auf <b>%1</b> (%2)<br>").arg(qs(s.device).toHtmlEscaped(), qs(s.backend))
                               : QString();
    compute_->setText(active + gpu_lines + tr("CUDA: %1").arg(qs(s.cuda_state)));

    if (s.server_running) {
        // Ohne geladenes Modell antwortet der Server mit 503 – vorher stand dann trotzdem „bereit“ da (gesehen beim
        // Modellwechsel 2026-10-28).
        const bool ready = s.model == ModelPhase::Loaded;
        server_->setText(dot(s.busy || !ready ? style::kWarn : style::kBuy) +
                         tr("läuft auf 127.0.0.1:%1<br>%2")
                             .arg(s.server_port)
                             .arg(s.busy ? tr("Auftrag läuft …")
                                         : ready ? tr("bereit") : tr("wartet auf das Modell (meldet 503)")) +
                         (s.local_running ? tr("<br>lokaler Minecraft-Server: 127.0.0.1:%1").arg(s.local_port)
                                          : QString()));
    } else {
        server_->setText(dot(style::kDim) + tr("gestoppt"));
    }

    QColor tc = style::kDim;
    QString tstate = tr("gestoppt");
    switch (s.tunnel) {
    case tunnel::State::Stopped: break;
    case tunnel::State::Starting: tc = style::kWarn; tstate = tr("verbindet …"); break;
    case tunnel::State::Connected: tc = style::kBuy; tstate = tr("verbunden"); break;
    case tunnel::State::Waiting: tc = style::kWarn; tstate = tr("getrennt, neuer Versuch folgt"); break;
    case tunnel::State::Failed: tc = style::kSell; tstate = tr("Fehler"); break;
    }
    refresh_buddy();
    {
        const AppConfig cfg = c_.config();
        QString hint;
        if (s.relay_enabled) {
            hint = tr("<br>über die Adresse (HTTPS): %1").arg(qs(relay::relay_host_port(cfg.relay_url)).toHtmlEscaped());
            if (cfg.relay_pins.empty()) {
                hint += tr("<br>Kein Fingerabdruck des Servers eingetragen – <a href='settings'>eintragen …</a>");
            }
        } else if (!s.tunnel_enabled) {
            hint = tr("<br>nicht nötig – Server auf diesem PC");
        } else if (cfg.target.empty()) {
            hint = tr("<br>Kein Server eingetragen – <a href='settings'>Server eintragen …</a>");
        }
        const QString text =
            s.tunnel_text.empty() ? QString() : QStringLiteral("<br>") + qs(s.tunnel_text).toHtmlEscaped();
        tunnel_->setText(dot(tc) + tstate + text + hint);
    }

    if (!s.has_last) {
        last_->setText(tr("noch keiner"));
    } else if (!s.last_ok) {
        last_->setText(dot(style::kSell) + qs(s.last_error).toHtmlEscaped());
    } else {
        last_->setText(dot(style::kBuy) + tr("<b>%1 s</b> · %2 Token · <b>%3 Token/s</b><br>Prompt %4 Token (%5 aus dem "
                                             "Speicher), %6 s")
                                              .arg(num(s.last_seconds, 1))
                                              .arg(s.last_tokens)
                                              .arg(num(s.last_tps, 1))
                                              .arg(s.last_prompt_tokens)
                                              .arg(s.last_reused)
                                              .arg(num(s.last_prompt_seconds, 1)));
    }
    if (const std::string stats = s.stats.summary_de(); !stats.empty()) {
        last_->setText(last_->text() + QStringLiteral("<br><span style='color:%1'>%2</span>")
                                           .arg(style::css_color(style::kMuted), tr("Seit Start: %1").arg(qs(stats))));
    }
    // Tempo-Warnung (JobStats::slow_warning_de) sichtbar in der Karte, nicht nur als Konsolenzeile, die wegscrollt.
    if (!s.last_warning.empty()) {
        last_->setText(last_->text() + QStringLiteral("<br><span style='color:%1'>%2</span>")
                                           .arg(style::css_color(style::kWarn), qs(s.last_warning).toHtmlEscaped()));
    }
    if (!s.last_research.empty()) {
        last_->setText(last_->text() + QStringLiteral("<br><span style='color:%1'>%2</span>")
                                           .arg(style::css_color(style::kMuted),
                                                tr("Nachgeschlagen: %1").arg(qs(s.last_research).toHtmlEscaped())));
    }
    if (!s.last_result.empty()) {
        last_->setText(last_->text() + QStringLiteral("<br><span style='color:%1'>%2</span>")
                                           .arg(style::css_color(s.last_result_failed ? style::kWarn : style::kBuy),
                                                tr("Im Spiel: %1").arg(qs(s.last_result).toHtmlEscaped())));
    }
    if (const std::uint64_t rev = c_.history_rev(); rev != history_rev_) {
        history_rev_ = rev;
        fill_history();
    }

    progress_->setVisible(s.downloading);
    progress_text_->setVisible(s.downloading);
    cancel_->setVisible(s.downloading);
    if (s.downloading) {
        progress_->setValue(s.download_total > 0
                                ? static_cast<int>(1000.0 * static_cast<double>(s.download_done) /
                                                   static_cast<double>(s.download_total))
                                : 0);
        progress_text_->setText(tr("%1: %2 von %3").arg(qs(s.download_title), gb(s.download_done), gb(s.download_total)));
    }
    cuda_->setEnabled(!s.downloading);
    if (tray_ != nullptr) {
        tray_->setToolTip(tr("Baumeister-Host – Modell %1, Server %2, Verbindung %3")
                              .arg(s.model == ModelPhase::Loaded ? tr("bereit") : tr("aus"),
                                   s.server_running ? tr("an") : tr("aus"), tstate));
    }
}

void MainWindow::show_demo_modules() {
    c_.show_demo_modules();
    refresh_now();
    if (tabs_ != nullptr && modules_ != nullptr) {
        tabs_->setCurrentWidget(modules_);
    }
}

QWidget* MainWindow::modules_page() const { return modules_; }

void MainWindow::refresh_modules(const Snapshot& s) {
    if (modules_ == nullptr) {
        return;
    }
    HostModuleFacts f;
    switch (s.model) {
    case ModelPhase::None: f.model = s.downloading ? LoadState::Busy : LoadState::Off; break;
    case ModelPhase::Loading: f.model = LoadState::Busy; break;
    case ModelPhase::Loaded: f.model = LoadState::Ready; break;
    case ModelPhase::Failed: f.model = LoadState::Failed; break;
    }
    f.downloading = s.downloading;
    const voice::SpeechStatus st = c_.speech_status();
    switch (st.state) {
    case voice::SpeechState::Off: f.speech = LoadState::Off; break;
    case voice::SpeechState::Loading: f.speech = LoadState::Busy; break;
    case voice::SpeechState::Error: f.speech = LoadState::Failed; break;
    case voice::SpeechState::Ready:
    case voice::SpeechState::Listening:
    case voice::SpeechState::Recognizing:
    case voice::SpeechState::Testing: f.speech = LoadState::Ready; break;
    }
    f.speech_error = st.error;
    f.server_running = s.server_running;
    f.link = s.tunnel;
    f.link_text = s.tunnel_text;
    f.city_reported = city_seq_ != 0;
    modules_->show_host(host_modules(c_.config(), f));
    if (const std::uint64_t seq = c_.server_modules_seq(); seq != modules_seq_) {
        modules_seq_ = seq;
        modules_->show_server(c_.server_modules());
    }
}

void MainWindow::toggle_module(const std::string& id, bool on) {
    AppConfig cfg = c_.config();
    if (!set_host_module(cfg, id, on)) {
        return;
    }
    // set_config speichert sofort und ruft apply_speech/apply_voice – Mikrofon, Stimme, Recherche, Mitspieler und
    // Meldungen wirken damit ohne weiteren Schritt (dieselben Schlüssel wie in den Einstellungen).
    c_.set_config(cfg);
    QString name = QString::fromStdString(id);
    for (const HostModule& m : host_modules(cfg, HostModuleFacts{})) {
        if (m.id == id) {
            name = qs(m.name);
        }
    }
    c_.console(ConsoleKind::Info, "Modul „" + name.toStdString() + "“: " + (on ? "an" : "aus") + " (gespeichert).");
    if (id == "plan-ki") {
        // Sofort, soweit ohne Rückfrage möglich: vorhandenes Modell laden bzw. entladen. Einen Download (bis 8 GB)
        // startet nur „KI einschalten“ – ein Häkchen auf einer Übersichtsseite soll das nicht nebenbei auslösen.
        const Snapshot s = c_.snapshot();
        if (on && s.model == ModelPhase::None && !s.downloading && !c_.model_busy()) {
            if (c_.model_file_present()) {
                c_.enable_ai();
            } else {
                c_.console(ConsoleKind::Info, "Plan-KI: Modelldatei fehlt – „KI einschalten“ lädt sie herunter.");
            }
        } else if (!on && s.model == ModelPhase::Loaded) {
            c_.unload_model();
        }
    } else if (id == "stadt-vorschau") {
        apply_city_tab(on);
    }
}

void MainWindow::apply_city_tab(bool visible) {
    if (tabs_ == nullptr || city_ == nullptr) {
        return;
    }
    const int index = tabs_->indexOf(city_);
    if (index >= 0) {
        tabs_->setTabVisible(index, visible);
    }
}

void MainWindow::fill_history() {
    const std::deque<HistoryEntry> h = c_.history();
    history_->setRowCount(static_cast<int>(h.size()));
    int row = 0;
    for (const HistoryEntry& e : h) {
        const auto cell = [&](int col, const QString& text, const QColor& color = QColor(),
                              const QString& tip = QString()) {
            auto* item = new QTableWidgetItem(text);
            item->setToolTip(tip.isEmpty() ? text : tip);
            if (color.isValid()) {
                item->setForeground(color);
            }
            history_->setItem(row, col, item);
        };
        cell(0, QDateTime::fromSecsSinceEpoch(e.when).toString(QStringLiteral("HH:mm:ss")));
        cell(1, qs(e.request));
        const QString llm = !e.llm ? tr("ohne")
                            : e.ok ? tr("%1 s · %2 T/s").arg(num(e.seconds, 0), num(e.tokens_per_second, 0))
                                   : tr("Fehler");
        cell(2, llm, e.llm ? QColor() : style::kMuted,
             e.llm ? tr("%1 – %2 s, %3 Token, %4 Token/s")
                         .arg(qs(e.model), num(e.seconds, 1))
                         .arg(e.tokens)
                         .arg(num(e.tokens_per_second, 1))
                   : tr("Schnellauftrag ohne LLM"));
        // Nachbesserungsrunde: Vorher „–“ mit „Noch keine Meldung“, als hinge der Auftrag (JobTracker::note_result).
        if (e.superseded) {
            cell(3, tr("nachgebessert"), style::kMuted,
                 tr("Frühere LLM-Antwort zu diesem Auftrag – das Ergebnis steht in der jüngeren Zeile desselben "
                    "Auftrags"));
            ++row;
            continue;
        }
        QString tip = e.result.empty() ? tr("Noch keine Meldung aus dem Spiel") : qs(e.result);
        if (!e.fixes.empty()) {
            tip += QStringLiteral("\n") + tr("Selbst nachgebessert: %1").arg(qs(e.fixes));
        }
        if (e.rounds > 1) {
            tip += QStringLiteral("\n") + tr("%1 LLM-Antworten (mit Nachbesserung)").arg(e.rounds);
        }
        cell(3, e.result.empty() ? tr("–") : qs(e.result),
             e.result.empty() ? style::kMuted : e.failed ? style::kWarn : style::kBuy, tip);
        ++row;
    }
    for (int col : {0, 2}) {
        history_->resizeColumnToContents(col);
    }
    const std::string counts = history_counts_de(count_history(h));
    history_counts_->setText(counts.empty() ? tr("Noch keine Ergebnisse aus dem Spiel.") : qs(counts));
}

void MainWindow::open_settings() {
    // Das Häkchen „Mit Windows starten“ zeigt den Stand der Registry (samt Task-Manager-Vermerk), nicht config.local.json
    // – sonst widersprächen sich App und Task-Manager, sobald der Nutzer dort abschaltet.
    // Eigener Fingerabdruck der Fernverbindung: anzeigen, falls schon ein Schlüssel da ist; „Schlüssel erzeugen“ legt
    // ihn über den Controller an (TPM, sonst Software) und merkt den Pin dort.
    SettingsDialog dlg(c_.config(), c_.has_tunnel_password(), autostart_enabled(), c_.own_client_pin().value_or(""),
                       [this](std::string& error) { return c_.create_client_pin(error); }, this);
    if (dlg.exec() != QDialog::Accepted) {
        return;
    }
    const AppConfig before = c_.config();
    AppConfig after = dlg.result_config();
    // Den eigenen Pin führt der Controller (er legt den Schlüssel an, auch aus dem Verbindungs-Thread) – der Stand des
    // Dialogs von seinem Öffnen dürfte ihn nicht überschreiben.
    after.relay_client_pin = before.relay_client_pin;
    c_.set_config(after);
    c_.console(ConsoleKind::Info, "Einstellungen gespeichert.");
    // Registry nur nach „Speichern“ und nur, wenn das Häkchen umgestellt wurde: Öffnen und Speichern ohne Änderung
    // ließe sonst einen im Task-Manager abgeschalteten Eintrag wieder aufleben.
    if (const std::optional<bool> want = dlg.windows_start_change()) {
        try {
            set_autostart(*want, exe_path());
            c_.console(ConsoleKind::Info, *want ? "Mit Windows starten: an – startet nach der Anmeldung still im "
                                                  "Infobereich."
                                                : "Mit Windows starten: aus.");
        } catch (const UserError& e) {
            c_.console(ConsoleKind::Error, e.what());
        }
    }
    const std::string password = dlg.new_password();
    bool password_changed = false;
    if (!password.empty()) {
        if (password.find_first_of("\r\n") != std::string::npos) {
            c_.console(ConsoleKind::Error, "Passwort mit Zeilenumbruch nicht gespeichert (plink liest nur eine Zeile).");
        } else {
            password_changed = c_.store_tunnel_password(password);
        }
    }
    const bool model_changed = before.model_key != after.model_key || before.custom_model != after.custom_model ||
                               before.n_ctx != after.n_ctx || before.compute != after.compute ||
                               before.allow_integrated != after.allow_integrated || before.speculative != after.speculative;
    const bool net_changed = before.server_port != after.server_port || before.plink != after.plink ||
                             before.local_server != after.local_server ||
                             before.target != after.target || before.ssh_port != after.ssh_port ||
                             before.remote_port != after.remote_port || before.hostkey != after.hostkey ||
                             before.tunnel_auth != after.tunnel_auth || password_changed ||
                             before.tunnel_enabled != after.tunnel_enabled ||
                             before.relay_enabled != after.relay_enabled || before.relay_url != after.relay_url ||
                             before.relay_pins != after.relay_pins;
    if (model_changed && c_.snapshot().model == ModelPhase::Loaded) {
        c_.console(ConsoleKind::Warn, "Modell oder Rechenweg geändert – „Modell entladen“ und neu laden, damit es wirkt.");
    }
    // Port/Verbindung/Anmeldung geändert: Laufendes gleich neu starten, statt den Wunsch drücken zu
    // lassen – so wirkt ein eben eingegebenes Passwort oder ein neuer Server-Pin sofort. Wer verbunden ist (Server
    // läuft), bekommt nach einem Wechsel der Verbindungsart gleich die neue. Vorher zählte der Wechsel der
    // Verbindungsart gar nicht als Netzänderung (laut Code, nicht im Fenster nachgestellt): Ein Tunnel lief nach „dieser
    // PC“ weiter, und „anderer Rechner“ startete keinen.
    if (net_changed) {
        const Snapshot s = c_.snapshot();
        const bool server_was = s.server_running;
        const bool link_was = s.tunnel != tunnel::State::Stopped;
        if (link_was) {
            c_.stop_link();
        }
        if (server_was && (before.server_port != after.server_port || before.local_server != after.local_server ||
                           before.remote_port != after.remote_port)) {
            c_.stop_server();
            c_.start_server();
        }
        if ((link_was || server_was) && (after.tunnel_enabled || after.relay_enabled)) {
            c_.start_link();
        }
    }
}

void MainWindow::ask_cuda() {
    const components::Component* c = components::builtin_catalog().find("cuda");
    if (c == nullptr) {
        return;
    }
    if (c_.cuda_installed()) {
        QMessageBox::information(this, tr("CUDA"),
                                 tr("CUDA ist installiert. Unter Einstellungen → Rechenweg „Automatisch“ oder „CUDA“ "
                                    "wählen und das Modell neu laden."));
        return;
    }
    // Zustimmungsdialog wie kraken (gui/components_page.cpp: start_install): Quelle, Größe, Prüfung, Lizenz.
    const QString text =
        tr("<b>%1</b> laden?<br><br>Quelle: github.com/ggml-org/llama.cpp (Release %2), %3 Download, installiert %4 unter "
           "deinem Benutzerordner. Jede Datei wird gegen die im Baumeister-Host festgelegte Prüfsumme geprüft.<br><br>"
           "%5<br><br>Für cuBLAS und die CUDA-Laufzeit gelten die Lizenzbedingungen von NVIDIA: "
           "<a href='%6'>%6</a>. Mit „Zustimmen und laden“ akzeptierst du sie. Ohne Zustimmung rechnet die App mit "
           "Vulkan (auch auf NVIDIA-Karten).")
            .arg(qs(c->title).toHtmlEscaped(), qs(components::builtin_catalog().tag), gb(c->download_size()),
                 gb(c->installed_size()), qs(c->note).toHtmlEscaped(), qs(c->terms_url).toHtmlEscaped());
    QMessageBox box(QMessageBox::Question, tr("CUDA aktivieren"), text, QMessageBox::NoButton, this);
    box.setTextFormat(Qt::RichText);
    box.setTextInteractionFlags(Qt::TextBrowserInteraction);
    QPushButton* yes = box.addButton(tr("Zustimmen und laden"), QMessageBox::AcceptRole);
    box.addButton(tr("Abbrechen"), QMessageBox::RejectRole);
    box.exec();
    if (box.clickedButton() != yes) {
        return;
    }
    c_.install_cuda(true);
}

void MainWindow::quit_app() {
    gui::exit_latch().record(gui::ExitReason::TrayQuit);
    quitting_ = true;
    close();
    QApplication::quit();
}

void MainWindow::closeEvent(QCloseEvent* event) {
    if (!quitting_ && tray_ != nullptr && tray_->isVisible()) {
        // Schließen = in den Infobereich: Der Host soll weiterlaufen, solange der Baumeister ihn braucht.
        hide();
        if (!tray_hint_shown_) {
            tray_->showMessage(tr("Baumeister-Host läuft weiter"),
                               tr("Beenden über das Symbol im Infobereich (Rechtsklick → Beenden)."));
            tray_hint_shown_ = true;
        }
        event->ignore();
        return;
    }
    event->accept();
    if (!quitting_) {
        // Ohne Infobereich ist das Fenster der einzige Weg zur App. Vorher blieb der Prozess nach dem Schließen
        // unsichtbar stehen (setQuitOnLastWindowClosed(false)) und hielt Port und Tunnel, bis zum Task-Manager.
        gui::exit_latch().record(gui::ExitReason::WindowClosed);
        QApplication::quit();
    }
}

}  // namespace swbh::gui
