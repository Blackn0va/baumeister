#include "gui/style.hpp"

#include "embedded/app_icon_ico.hpp"

#include <QApplication>
#include <QCheckBox>
#include <QPainter>
#include <QPainterPath>
#include <QProxyStyle>
#include <QRadioButton>
#include <QStyleOption>
#include <QFont>
#include <QImage>
#include <QList>
#include <QPalette>
#include <QPixmap>
#include <QStyleFactory>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string_view>
#include <utility>

namespace swbh::gui::style {

QString css_color(const QColor& c) { return c.name(QColor::HexRgb); }

double contrast(const QColor& a, const QColor& b) {
    const auto lin = [](double c) { return c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4); };
    const auto lum = [&](const QColor& c) {
        return 0.2126 * lin(c.redF()) + 0.7152 * lin(c.greenF()) + 0.0722 * lin(c.blueF());
    };
    const double x = lum(a);
    const double y = lum(b);
    return (std::max(x, y) + 0.05) / (std::min(x, y) + 0.05);
}

namespace {

// ICO-Leser aus CaptionBridgeCPP (gui/style.cpp, unverändert übernommen): Qt liest .ico
// nur über das Plugin imageformats/qico.dll, das nicht ausgeliefert wird. Die von
// make_branding.py erzeugten Dateien haben genau zwei Eintragsarten – 32-Bit-DIB bis
// 128 px und PNG bei 256 px –, beide liest diese Funktion selbst.
QList<QImage> decode_ico(std::string_view bytes) {
    QList<QImage> out;
    const auto* data = reinterpret_cast<const unsigned char*>(bytes.data());
    const std::size_t size = bytes.size();
    const auto u16 = [&](std::size_t off) -> std::uint32_t {
        return static_cast<std::uint32_t>(data[off]) | (static_cast<std::uint32_t>(data[off + 1]) << 8);
    };
    const auto u32 = [&](std::size_t off) -> std::uint32_t { return u16(off) | (u16(off + 2) << 16); };
    // ICONDIR: reserviert 0, Typ 1 (Icon), Anzahl.
    if (size < 6 || u16(0) != 0 || u16(2) != 1) {
        return out;
    }
    const std::size_t count = u16(4);
    for (std::size_t i = 0; i < count; ++i) {
        const std::size_t entry = 6 + 16 * i;
        if (entry + 16 > size) {
            break;
        }
        const std::size_t length = u32(entry + 8);
        const std::size_t offset = u32(entry + 12);
        if (offset > size || length > size - offset || length < 8) {
            continue;
        }
        const unsigned char* payload = data + offset;
        static constexpr unsigned char kPng[] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (std::memcmp(payload, kPng, sizeof(kPng)) == 0) {
            QImage image;
            if (image.loadFromData(payload, static_cast<int>(length), "PNG")) {
                out.append(image.convertToFormat(QImage::Format_ARGB32));
            }
            continue;
        }
        // BITMAPINFOHEADER: Größe 40, Breite, Höhe (doppelt: Farbe + AND-Maske), 1 Ebene,
        // 32 Bit, BI_RGB (0). Die Pixel folgen direkt (keine Palette bei 32 Bit).
        if (length < 40 || u32(offset) != 40) {
            continue;
        }
        const auto width = static_cast<std::int32_t>(u32(offset + 4));
        const auto double_height = static_cast<std::int32_t>(u32(offset + 8));
        if (width <= 0 || width > 1024 || double_height <= 0 || double_height % 2 != 0 || double_height / 2 > 1024 ||
            u16(offset + 12) != 1 || u16(offset + 14) != 32 || u32(offset + 16) != 0) {
            continue;
        }
        const int height = double_height / 2;
        const std::size_t stride = static_cast<std::size_t>(width) * 4;
        if (40 + stride * static_cast<std::size_t>(height) > length) {
            continue;
        }
        // Format_ARGB32 liegt auf x86 als B, G, R, A im Speicher – wie die DIB-Zeilen.
        QImage image(width, height, QImage::Format_ARGB32);
        for (int y = 0; y < height; ++y) {
            const unsigned char* row = payload + 40 + stride * static_cast<std::size_t>(height - 1 - y);
            std::memcpy(image.scanLine(y), row, stride);
        }
        out.append(image);
    }
    return out;
}

// Fusion zeichnet den Rahmen eines leeren Kontrollkästchens als Fenster-Farbe × 1,4 dunkler – auf dem dunklen Grund
// praktisch unsichtbar (Bild Stimmlabor 2026-10-10). Eigene Zeichnung: sichtbarer Rahmen (kMuted), gefüllt mit
// Akzent und weißem Häkchen, wenn an. Eine QSS-Regel dafür hätte das Häkchen geschluckt (siehe Gedaechtnis).
class SwaStyle final : public QProxyStyle {
public:
    using QProxyStyle::QProxyStyle;
    void drawPrimitive(PrimitiveElement pe, const QStyleOption* opt, QPainter* p, const QWidget* w) const override {
        // Fokus von Kontrollkästchen und Optionsfeldern im einen Fokusring des Designs (kFocus) statt in Fusions
        // halbdurchsichtigem Ton aus der Auswahlfarbe – derselben Farbe, mit der ein Häkchen-Kästchen „an“ zeigt.
        // Fusion zeichnet ihn nur nach Tastaturbedienung (State_KeyboardFocusChange) – das entspricht :focus-visible
        // und bleibt so.
        if (pe == PE_FrameFocusRect && opt != nullptr &&
            (qobject_cast<const QCheckBox*>(w) != nullptr || qobject_cast<const QRadioButton*>(w) != nullptr)) {
            if ((opt->state & State_KeyboardFocusChange) == 0) {
                return;
            }
            const QRectF r = QRectF(opt->rect).adjusted(0.5, 0.5, -0.5, -0.5);
            p->save();
            p->setRenderHint(QPainter::Antialiasing);
            p->setPen(QPen(kFocus, std::max(1.0, r.height() / 12.0)));
            p->setBrush(Qt::NoBrush);
            p->drawRoundedRect(r, r.height() / 4.0, r.height() / 4.0);
            p->restore();
            return;
        }
        if (pe != PE_IndicatorCheckBox || opt == nullptr) {
            QProxyStyle::drawPrimitive(pe, opt, p, w);
            return;
        }
        const bool on = (opt->state & State_On) != 0;
        const bool enabled = (opt->state & State_Enabled) != 0;
        const QRectF r = QRectF(opt->rect).adjusted(1.5, 1.5, -1.5, -1.5);
        p->save();
        p->setRenderHint(QPainter::Antialiasing);
        const QColor frame = enabled ? ((opt->state & State_MouseOver) ? kAccent : kMuted) : kBorder;
        p->setPen(QPen(frame, std::max(1.5, r.width() / 9.0)));
        // Aus: Feldfläche wie alle Eingaben (swa-bg-field), vorher kPanel.
        p->setBrush(on ? (enabled ? kAccentFill : kBorder) : kField);
        p->drawRoundedRect(r, r.width() / 6.0, r.width() / 6.0);
        if (on) {
            QPainterPath tick;
            tick.moveTo(r.left() + r.width() * 0.22, r.top() + r.height() * 0.52);
            tick.lineTo(r.left() + r.width() * 0.43, r.top() + r.height() * 0.72);
            tick.lineTo(r.left() + r.width() * 0.78, r.top() + r.height() * 0.30);
            p->setPen(QPen(kOnFill, std::max(1.8, r.width() / 7.0), Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
            p->setBrush(Qt::NoBrush);
            p->drawPath(tick);
        }
        p->restore();
    }
};

}  // namespace

QIcon app_icon() {
    // Einmal entpacken (UI-Thread); QPixmap braucht die laufende QGuiApplication, deshalb
    // bleiben die Bilder QImage und werden je Aufruf in Pixmaps übernommen.
    static const QList<QImage> images = decode_ico(swbh::embedded::app_icon_ico_view());
    QIcon icon;
    for (const QImage& image : images) {
        icon.addPixmap(QPixmap::fromImage(image));
    }
    return icon;
}

QPalette palette() {
    QPalette p;
    p.setColor(QPalette::Window, kBackground);
    p.setColor(QPalette::WindowText, kText);
    // Listen, Tabellen und Lesetexte stehen wie Karten auf der Fläche; Eingabefelder bekommen kField im Stylesheet.
    p.setColor(QPalette::Base, kPanel);
    p.setColor(QPalette::AlternateBase, kPanelAlt);
    // Tooltips wie Menüs als vertiefte Fläche mit Rand (Design: Popover auf sws-surface-2).
    p.setColor(QPalette::ToolTipBase, kField);
    p.setColor(QPalette::ToolTipText, kText);
    p.setColor(QPalette::Text, kText);
    // Platzhalter in sws-faint. Vorher kMuted (c9ccda) – kaum leiser als eingegebener Text (f3f4f8).
    p.setColor(QPalette::PlaceholderText, kDim);
    p.setColor(QPalette::Button, kPanelAlt);
    p.setColor(QPalette::ButtonText, kText);
    p.setColor(QPalette::BrightText, kOnFill);
    p.setColor(QPalette::Highlight, kAccentFill);
    p.setColor(QPalette::HighlightedText, kOnFill);
    p.setColor(QPalette::Link, kInfo);
    // Gesperrt in sws-faint (≥ 4,5:1, also lesbar), aber deutlich leiser als kText. Vorher kMuted (c9ccda), fast so
    // hell wie die Schrift freier Knöpfe (f3f4f8). Das Design dimmt auf 55 %; Qt-Stylesheets kennen keine Deckkraft.
    p.setColor(QPalette::Disabled, QPalette::Text, kDim);
    p.setColor(QPalette::Disabled, QPalette::ButtonText, kDim);
    p.setColor(QPalette::Disabled, QPalette::WindowText, kDim);
    return p;
}

QString stylesheet() {
    // Platzhalter ${name} statt %1 … %14: Mit den Design-Farben sind es 25 Töne – zwei arg()-Ketten mit Nummern
    // waren nicht mehr zu prüfen (ein vertauschtes %10/%11 färbt still den falschen Knopf). Ein vergessener Name
    // bleibt als „${…}“ stehen; der Test „Stil: Design …“ schlägt dann an.
    //
    // Radien in em (QSS-em = Zeilenhöhe der Schrift; gemessen 2026-10-24: der 1 em breite Reglergriff ist bei 9,5 pt
    // Segoe UI und 100 % 17 px breit): Knöpfe und Felder
    // 0,6 em ≈ 10 px, Kennzahlen/Hinweise 0,7 em ≈ 12 px, Karten 0,9 em ≈ 15 px (Design 10/12/16 px). Qt verwirft einen
    // Radius ganz, sobald 2 × Radius größer als die Höhe ist (Quadrat statt Rundung, Runde 9) – deshalb Pillen mit
    // 0,6 em statt „999px“ und einzeilige Kästen mit Hinweis-Radius statt Karten-Radius.
    // Eingabefelder mit Bildlauf (QPlainTextEdit, QTextBrowser, Listen) bleiben eckig: ihr Sichtbereich ist ein eigenes
    // Widget und übermalt die runden Ecken.
    QString qss = QStringLiteral(
        "QMainWindow, QDialog { background: ${bg}; }"
        // Tabellen nach swa-table: Fläche wie Karten, Kopf vertieft. Auswahl als Akzentfläche mit weißer Schrift –
        // vorher selection-background-color = kAccent (hell) unter weißer Schrift aus der Palette: 2,4:1.
        "QTableWidget, QTableView { background: ${panel}; alternate-background-color: ${panelAlt}; "
        "  gridline-color: ${border}; border: 1px solid ${border}; selection-background-color: ${accentFill}; "
        "  selection-color: ${onFill}; }"
        "QHeaderView::section { background: ${field}; color: ${muted}; border: none; "
        "  border-bottom: 1px solid ${border}; padding: 0.15em 0.4em; font-weight: 600; }"
        "QTabWidget::pane { border: 1px solid ${border}; border-radius: 0.5em; top: -1px; }"
        // Flache Reiter ohne Kastenrahmen: nur Text + Akzentstrich – vorher wirkte die Leiste wie
        // eine Reihe großer Knöpfe.
        "QTabBar::tab { background: transparent; color: ${muted}; padding: 0.3em 0.9em; border: none; "
        "  border-bottom: 2px solid transparent; }"
        // Gewählter Reiter: heller Text und Akzentstrich unten – vorher nur ein Hauch dunkler.
        "QTabBar::tab:selected { color: ${text}; font-weight: 600; border-bottom: 2px solid ${accent}; }"
        "QTabBar::tab:hover { color: ${text}; }"
        // Sekundärknopf (.swa-btn / .btn-secondary): helle Tönung, Rand; Hover nur über Fläche, Rand und Schrift, kein
        // Sprung. Vorher fast eckig (0,3 em) mit Akzentrand beim Hover.
        "QPushButton { background: ${panelAlt}; color: ${text}; border: 1px solid ${border}; border-radius: 0.6em; "
        "  padding: 0.2em 0.7em; }"
        "QPushButton:hover { background: ${panelHover}; border-color: ${accentLine}; color: ${onFill}; }"
        // Umschalter (Internet, Mikro-Test) sichtbar an: Akzentfläche statt nur „gedrückt“.
        "QPushButton:checked { background: ${accentFill}; color: ${onFill}; border: 1px solid ${accent}; }"
        // Primärknopf (--sws-cta-grad): Verlauf von oben, heller Rand. Vorher flache Fläche ohne Rand.
        "QPushButton#primary { background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 ${accentFill}, "
        "  stop:1 ${accentFillEnd}); color: ${onFill}; border: 1px solid ${accent}; font-weight: 600; }"
        "QPushButton#primary:hover { background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 ${accentFillHover}, "
        "  stop:1 ${accentFillHoverEnd}); }"
        "QLineEdit, QComboBox, QAbstractSpinBox { background: ${field}; color: ${text}; "
        "  border: 1px solid ${border}; border-radius: 0.6em; padding: 0.1em 0.35em; }"
        "QLineEdit:hover, QComboBox:hover, QAbstractSpinBox:hover { border-color: ${accentLine}; }"
        // Aufklappfläche und Drehfeld-Knöpfe zeichnet Qt als eckige 3D-Kästen (Windows-Stil) am rechten Rand – mit
        // 0,6 em Radius überdeckten sie die runde Ecke (gerendert 2026-10-24). Nur verschoben, in den Inhaltsbereich:
        // Sobald diese Teile Fläche oder Rand im Stylesheet bekommen, zeichnet Qt den Pfeil nur noch als Bild aus
        // ::down-arrow – und Bilddateien gibt es hier nicht (keine .qrc; Rand-Dreiecke zeichnet Qt als Rechtecke,
        // ausprobiert). Die Kastenkanten leitet Qt aus der Feldfarbe ab (gemessen 0d1729/070d18/04070c auf 0b1424,
        // ≈ 1,1:1) – praktisch unsichtbar, die Palette kann sie nicht überstimmen.
        "QComboBox::drop-down { subcontrol-origin: content; subcontrol-position: center right; width: 1.1em; "
        "  height: 0.9em; }"
        "QAbstractSpinBox::up-button { subcontrol-origin: content; subcontrol-position: top right; width: 1.1em; }"
        "QAbstractSpinBox::down-button { subcontrol-origin: content; subcontrol-position: bottom right; "
        "  width: 1.1em; }"
        "QComboBox QAbstractItemView { background: ${field}; color: ${text}; border: 1px solid ${border}; "
        "  selection-background-color: ${selectSoft}; selection-color: ${text}; }"
        // Mikro-Regler war auf dem dunklen Grund unsichtbar (Bildprüfung 2026-10-10).
        // Mindesthöhe: mit 9,5 pt war das Element niedriger als der Griff, der Griff wurde oben und
        // unten abgeschnitten und sah eckig aus (gerendert 2026-10-10).
        "QSlider:horizontal { min-height: 1.3em; }"
        "QSlider::groove:horizontal { height: 0.35em; background: ${border}; border-radius: 0.17em; }"
        "QSlider::sub-page:horizontal { background: ${accentFill}; border-radius: 0.17em; }"
        "QSlider::handle:horizontal { background: ${accent}; width: 1em; margin: -0.35em 0; "
        // 0,45 statt 0,5 em: Qt rundet em getrennt in Pixel; ergibt 2 × Radius mehr als die Breite,
        // verwirft es den Radius und zeichnet ein Quadrat (bei 9,5 pt gesehen).
        "  border-radius: 0.45em; }"
        "QProgressBar { background: ${field}; color: ${text}; border: 1px solid ${border}; border-radius: 0.3em; "
        "  text-align: center; }"
        "QProgressBar::chunk { background: ${accentFill}; border-radius: 0.25em; }"
        "QScrollBar:vertical { background: transparent; width: 0.6em; margin: 0; }"
        "QScrollBar::handle:vertical { background: ${border}; border-radius: 0.3em; min-height: 2em; }"
        "QScrollBar::handle:vertical:hover { background: ${muted}; }"
        "QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }"
        "QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background: none; }"
        "QScrollBar:horizontal { background: transparent; height: 0.6em; margin: 0; }"
        "QScrollBar::handle:horizontal { background: ${border}; border-radius: 0.3em; min-width: 2em; }"
        "QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal { width: 0; }"
        "QMenuBar { background: ${bg}; color: ${text}; padding: 0; }"
        "QMenuBar::item { padding: 0.15em 0.6em; border-radius: 0.4em; }"
        // Gewählte Menüzeile ruhig (sws-brand-bg) statt voller Akzentfläche – wie der aktive Navigationslink im Design.
        "QMenuBar::item:selected, QMenu::item:selected { background: ${selectSoft}; color: ${text}; }"
        // Menü kompakt mit runden Ecken: Fusion gab jeder Zeile ~2 em Höhe.
        "QMenu { background: ${field}; color: ${text}; border: 1px solid ${border}; border-radius: 0.5em; "
        "  padding: 0.25em; }"
        "QMenu::item { padding: 0.2em 1.4em 0.2em 0.7em; border-radius: 0.4em; }"
        "QMenu::separator { height: 1px; background: ${border}; margin: 0.2em 0.4em; }"
        // Menüknopf neben den Reitern (ersetzt die Menüleiste): größeres Zeichen, kein Pfeil –
        // mit Pfeil und Standardgröße war er kaum zu erkennen (gerendert 2026-10-14).
        "QToolButton#mainMenu { color: ${text}; font-size: 125%; padding: 0.05em 0.5em; border: none; "
        "  border-radius: 0.4em; }"
        "QToolButton#mainMenu:hover, QToolButton#mainMenu:pressed { background: ${panelHover}; }"
        "QToolButton#mainMenu::menu-indicator { image: none; width: 0; }"
        // Gruppen als Karten (swa-card: Fläche sws-surface, Rand, großer Radius). Vorher nur ein Rahmen auf dem
        // Fenstergrund – Einstellungsseiten wirkten wie Linien auf Schwarz.
        "QGroupBox { background: ${panel}; border: 1px solid ${border}; border-radius: 0.9em; margin-top: 0.7em; "
        "  padding: 0.5em 0.4em 0.3em 0.4em; }"
        "QGroupBox::title { subcontrol-origin: margin; left: 0.6em; padding: 0 0.3em; color: ${muted}; "
        "  font-weight: 600; }"
        // Seitenleiste der Einstellungen: flache Einträge; aktiver Eintrag wie der aktive Navigationslink des Designs
        // (sws-brand-bg + violetter Rand) statt voller Akzentfläche. Der transparente Rand an jedem Eintrag hält die
        // Breite gleich, wenn die Auswahl wechselt.
        "QListWidget#nav { background: ${panel}; border: none; border-radius: 0.7em; padding: 0.25em; "
        "  outline: 0; }"
        "QListWidget#nav::item { padding: 0.25em 0.5em; border-radius: 0.45em; color: ${text}; "
        "  border: 1px solid transparent; }"
        "QListWidget#nav::item:hover { background: ${panelHover}; }"
        "QListWidget#nav::item:selected { background: ${selectSoft}; color: ${text}; border: 1px solid ${accentLine}; }"
        // Befehlspalette (Strg+K): schwebend mit violettem Rand, Auswahl wie die Seitenleiste.
        "QDialog#commandPalette { background: ${panel}; border: 1px solid ${accentLine}; }"
        "QListWidget#paletteList { background: ${panel}; border: none; outline: 0; }"
        "QListWidget#paletteList::item { padding: 0.3em 0.5em; border-radius: 0.45em; color: ${text}; "
        "  border: 1px solid transparent; }"
        "QListWidget#paletteList::item:hover { background: ${panelHover}; }"
        "QListWidget#paletteList::item:selected { background: ${selectSoft}; color: ${text}; "
        "  border: 1px solid ${accentLine}; }"
        // Gesperrt sah „Herunterladen“ genauso aus wie freigegeben (Modelldialog, gerendert
        // 2026-10-06) – Klick tat dann scheinbar nichts.
        "QPushButton#primary:disabled, QPushButton:disabled { background: ${panelAlt}; color: ${dim}; "
        "  border: 1px solid ${border}; }"
        // An, aber gesperrt (Internet im Modus Agent): Akzentrahmen – sonst sah „immer an“ aus wie aus.
        "QPushButton:checked:disabled { background: ${panelAlt}; color: ${accent}; border: 1px solid ${accent}; }"
        "QPushButton#buy { background: ${buyFill}; color: ${onFill}; border: 1px solid ${buy}; font-weight: 700; }"
        "QPushButton#sell { background: ${sellFill}; color: ${onFill}; border: 1px solid ${sell}; font-weight: 700; }"
        // Vorschlags-Chip wie Kicker/Chip des Designs: sws-brand-bg mit violettem Rand.
        "QPushButton#chipBtn { background: ${selectSoft}; border: 1px solid ${accentLine}; border-radius: 0.6em; "
        "  padding: 0.1em 0.7em; color: ${text}; font-weight: 600; }"
        "QPushButton#chipBtn:hover { border-color: ${accent}; }"
        // Klappknopf „▸ Erweitert“ (dialogs.hpp make_more_toggle) als Textverweis: Ohne eigene Regel sah er wie ein
        // Aktionsknopf aus und wurde aufgeklappt (checkable) voll violett wie ein An-Schalter.
        "QPushButton#moreToggle, QPushButton#moreToggle:checked { background: transparent; border: 1px solid "
        "  transparent; color: ${accent}; font-weight: 600; padding: 0.1em 0.2em; }"
        "QPushButton#moreToggle:hover { color: ${text}; }"
        "QPlainTextEdit { background: ${field}; color: ${text}; border: 1px solid ${border}; }"
        "QTextBrowser { background: ${panel}; border: 1px solid ${border}; }"
        // Der eine Fokusring des Designs (sws-focus) – zuletzt, damit er Hover und die Knopfarten mit eigener
        // ID (#primary, #buy …) überstimmt; Qt wertet bei gleicher Spezifität die spätere Regel.
        "QPushButton:focus, QPushButton#primary:focus, QPushButton#buy:focus, QPushButton#sell:focus, "
        "QPushButton#chipBtn:focus, QPushButton#moreToggle:focus, QLineEdit:focus, QComboBox:focus, "
        "QAbstractSpinBox:focus, QPlainTextEdit:focus "
        "{ border: 1px solid ${focus}; }"
        "QStatusBar { background: ${panel}; color: ${muted}; }"
        "QStatusBar::item { border: none; }"
        "QSplitter::handle { background: ${border}; }"
        "QFrame#card { background: ${panel}; border: 1px solid ${border}; border-radius: 0.9em; }"
        "QFrame#cardBuy { background: ${panel}; border: 2px solid ${buy}; border-radius: 0.9em; }"
        "QFrame#cardSell { background: ${panel}; border: 2px solid ${sell}; border-radius: 0.9em; }"
        "QFrame#nowIdle { background: ${panel}; border: 1px solid ${border}; border-radius: 0.9em; }"
        "QFrame#nowHold { background: ${panel}; border: 2px solid ${accent}; border-radius: 0.9em; }"
        "QFrame#nowBuy { background: ${buySoft}; border: 3px solid ${buy}; border-radius: 0.9em; }"
        "QFrame#nowSell { background: ${sellSoft}; border: 3px solid ${sell}; border-radius: 0.9em; }"
        "QFrame#tile { background: ${panel}; border: 1px solid ${border}; border-radius: 0.7em; }"
        "QFrame#tileBuy { background: ${buySoft}; border: 2px solid ${buy}; border-radius: 0.7em; }"
        "QFrame#tileBlocked { background: ${sellSoft}; border: 1px solid ${sell}; border-radius: 0.7em; }"
        // Kennzahl im Kasten: vertiefte Fläche statt rgba(0,0,0,.25) – gleiche Wirkung, aber ein Ton des Designs.
        "QFrame#stat { background: ${field}; border: 1px solid ${border}; border-radius: 0.7em; }"
        "QFrame#kpi { background: ${panel}; border: 1px solid ${border}; border-radius: 0.7em; }"
        "QLabel#panel { background: ${panel}; border: 1px solid ${border}; border-radius: 0.7em; padding: 0.3em; }"
        // Pille (.swa-pill): helle Tönung mit Rand, Text zweiter Ordnung.
        "QLabel#chip { background: ${panelAlt}; color: ${reading}; border: 1px solid ${border}; border-radius: 0.6em; "
        "  padding: 0.1em 0.7em; }"
        "QLabel#muted { color: ${muted}; }"
        // Modulseite (gui/modules_page): Karte je Modul, Rahmen nach Stand (dynamische Eigenschaft
        // „state“), Symbol auf einer Kachel, Kern-Karte mit Akzentfläche links.
        "QFrame#moduleCard { background: ${panel}; border: 1px solid ${border}; border-radius: 0.9em; }"
        "QFrame#moduleCard[state=\"on\"] { border: 1px solid ${accentFill}; }"
        "QFrame#moduleCard[state=\"waiting\"] { border: 1px dashed ${muted}; }"
        // Kästen mit Akzentkante links nur 0,5 em rund: Qt biegt die 3-px-Kante mit dem Radius, bei Karten-Radius sah
        // sie an kurzen Kästen wie eine Klammer „(“ aus (gerendert 2026-10-24).
        "QFrame#coreCard { background: ${panelAlt}; border: 1px solid ${border}; border-left: 3px solid ${accent}; "
        "  border-radius: 0.5em; }"
        "QLabel#moduleIcon { background: ${panelAlt}; border: 1px solid ${border}; border-radius: 0.7em; }"
        // Listen der Modul-Reiter (Gedächtnis, Forschung, Quiz …): Zeilen mit etwas Luft, Auswahl
        // ruhig (dunkles Lila + Akzentstrich) statt einer grell-lila Fläche über die ganze Zeile.
        "QListWidget#memoryFacts::item, QListWidget#memoryReminders::item, "
        "QListWidget#researchTopics::item, QListWidget#researchQuestions::item, "
        "QListWidget#quizScores::item, QListWidget#factcheckHistory::item, "
        "QListWidget#dictationFiles::item, QListWidget#briefingHistory::item { "
        "  padding: 0.35em 0.5em; border-left: 3px solid transparent; }"
        "QListWidget#memoryFacts::item:selected, QListWidget#memoryReminders::item:selected, "
        "QListWidget#researchTopics::item:selected, QListWidget#researchQuestions::item:selected, "
        "QListWidget#quizScores::item:selected, QListWidget#factcheckHistory::item:selected, "
        "QListWidget#dictationFiles::item:selected, QListWidget#briefingHistory::item:selected { "
        "  background: ${selectSoft}; color: ${text}; border-left: 3px solid ${accent}; }"
        // Statuszeile der Forschung: Kasten mit Akzentkante wie die Alerts des Designs – vorher Fließtext zwischen
        // Fließtext.
        "QLabel#researchStatus { background: ${panel}; border: 1px solid ${border}; border-left: 3px solid ${accent}; "
        "  border-radius: 0.5em; padding: 0.35em 0.7em; }"
        // Lesetexte ohne eigenen Rahmen (der Reiterrahmen reicht), Protokoll gedämpft.
        "QTextBrowser#researchFindings, QTextBrowser#researchSummary, QTextBrowser#factcheckResult "
        "{ background: ${bg}; border: none; }"
        "QPlainTextEdit#researchLog { background: ${bg}; border: none; color: ${muted}; }"
        "QTextBrowser[reading=\"true\"] { border: none; }"
        "QToolTip { background: ${field}; color: ${text}; border: 1px solid ${border}; }");
    const std::pair<const char*, const QColor*> tokens[] = {
        {"bg", &kBackground},
        {"panel", &kPanel},
        {"panelAlt", &kPanelAlt},
        {"panelHover", &kPanelHover},
        {"field", &kField},
        {"border", &kBorder},
        {"accentLine", &kAccentLine},
        {"text", &kText},
        {"muted", &kMuted},
        {"reading", &kReading},
        {"dim", &kDim},
        {"accent", &kAccent},
        {"accentFill", &kAccentFill},
        {"accentFillEnd", &kAccentFillEnd},
        {"accentFillHover", &kAccentFillHover},
        {"accentFillHoverEnd", &kAccentFillHoverEnd},
        {"buy", &kBuy},
        {"buyFill", &kBuyFill},
        {"buySoft", &kBuySoft},
        {"sell", &kSell},
        {"sellFill", &kSellFill},
        {"sellSoft", &kSellSoft},
        {"onFill", &kOnFill},
        {"selectSoft", &kSelectSoft},
        {"focus", &kFocus},
    };
    for (const auto& [name, color] : tokens) {
        qss.replace(QStringLiteral("${%1}").arg(QLatin1String(name)), css_color(*color));
    }
    return qss;
}

void apply(QApplication& app) {
    app.setStyle(new SwaStyle(QStyleFactory::create(QStringLiteral("Fusion"))));
    app.setPalette(palette());

    QFont f = app.font();
    // Design: Systemschrift, keine Webfonts – unter Windows ist das Segoe UI.
    f.setFamily(QStringLiteral("Segoe UI"));
    // 9,5 pt (vorher 11): Betreiber 2026-10-10 „Menüs riesig – modern, klein, übersichtlich“. Bei 1920 × 1080 und
    // Faktor 1,0 waren Formularzeilen 38 px hoch und die Einstellungen brauchten 1100 × 950 px. Größere Monitore
    // gleicht auto_ui_scale aus; wer es wieder größer will, stellt „Größe der Oberfläche“ hoch statt hier.
    f.setPointSizeF(9.5);
    app.setFont(f);

    app.setStyleSheet(stylesheet());
    app.setWindowIcon(app_icon());
}

QString reading_css() {
    // 125 %: 140 % sah gerendert fast nach doppeltem Zeilenabstand aus – der Blick sprang zwischen den Zeilen.
    return QStringLiteral("body { color: %1; } p { line-height: 125%; } li { line-height: 125%; margin-bottom: 3px; } "
                          "a { color: %2; text-decoration: none; }")
        .arg(css_color(kReading), css_color(kAccent));
}

QString pill_html(const QString& text, const QColor& fill) {
    return QStringLiteral("<span style='background-color:%1; color:%2; font-weight:600'>&nbsp;%3&nbsp;</span>")
        .arg(css_color(fill), css_color(kOnFill), text.toHtmlEscaped());
}

QString card_html(const QColor& stripe, const QString& inner) {
    // Tabelle statt div: Qts Rich-Text kennt weder border-left noch Hintergrund an Blöcken mit Innenabstand. Die
    // schmale erste Zelle ist der Strich. Mehrfach-arg() ersetzt in einem Durchgang – ein „%1“ im Text bleibt Text.
    return QStringLiteral("<table width='100%' cellspacing='0' cellpadding='0' style='margin-bottom:10px'><tr>"
                          "<td width='4' bgcolor='%1'></td>"
                          "<td bgcolor='%2' style='padding:10px 14px 10px 14px'>%3</td></tr></table>")
        .arg(css_color(stripe), css_color(kPanel), inner);
}

}  // namespace swbh::gui::style
