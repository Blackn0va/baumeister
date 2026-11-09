// Aussehen von StreamWizard Agent: Fusion mit dunkler Palette im StreamWizard-Design-System.
//
// Warum Fusion: Es zeichnet unter jedem Windows gleich und hält sich an die Palette;
// der Windows-Stil bräuchte das Plugin qmodernwindowsstyle.dll im Setup und ließe
// sich nicht durchgehend dunkel färben. Gleiches Vorgehen wie CaptionBridge.
//
// Größen nur in Punkt bzw. aus QFontMetrics – nie in festen Pixeln: Qt 6 skaliert
// mit dem Monitor (Per-Monitor-DPI v2), feste Pixel wären bei 200 % halb so groß.

#pragma once

#include <QColor>
#include <QIcon>
#include <QPalette>
#include <QString>

class QApplication;

namespace swbh::gui::style {

// Farben aus dem StreamWizard-Design-System (Artifact „streamwizard“, tokens.json, Stand 2026-10-24; dieselben Werte
// wie streamwizard.de global.css/swa-core.css). Vorher eigene Töne (Fenster 11121a, Karte 1b1d28, Akzent b39dff,
// Kauf 4ade80, Info 7dd3fc) – die App sah neben Website und Dashboard wie ein fremdes Produkt aus.
// Qt-Paletten und QSS-Farben über css_color() sind deckend: Halbtransparente Token sind über sws-bg (#151224)
// verrechnet, je Kanal round(Token × Alpha + Grund × (1 − Alpha)); der Rechenweg steht an jedem Wert.
//
// Kontrast (Betreiber 2026-10-10: „die Kontraste der App sind schwierig zu lesen“) bleibt die harte Regel und gewinnt
// gegen das Design (WCAG 2.1, geprüft in tests/gui/test_widgets.cpp):
//   Text und Nebentext auf allen Flächen ≥ 7:1, Farbtext (Kauf/Verkauf/Warnung/Info/Akzent) ≥ 4,5:1,
//   Schrift auf gefüllten Knöpfen ≥ 4,5:1, Rahmen gegen Fläche ≥ 1,8:1 (sichtbare Kanten), Fokusring ≥ 3:1.
// Wer eine Farbe ändert, muss den Test grün halten – sonst kommt die alte Unlesbarkeit zurück.
inline const QColor kBackground{0x15, 0x12, 0x24};   // sws-bg
// sws-surface rgba(15, 27, 50, .66) über sws-bg: 15·.66+21·.34 = 17, 27·.66+18·.34 = 24, 50·.66+36·.34 = 45.
inline const QColor kPanel{0x11, 0x18, 0x2d};
// Sekundärknopf-Fläche rgba(148, 163, 184, .08) über sws-bg (31, 30, 48): Knöpfe, Kopfzeilen, Wechselzeilen, Hover.
inline const QColor kPanelAlt{0x1f, 0x1e, 0x30};
// Hover des Sekundärknopfs rgba(148, 163, 184, .15) über sws-bg (40, 40, 58).
inline const QColor kPanelHover{0x28, 0x28, 0x3a};
// swa-bg-field: Eingabefelder und vertiefte Flächen (Tabellenkopf, Menüs, Tooltips – sws-surface-2 rgba(12, 21, 40,
// .88) ergibt über sws-bg 0d1528 und ist davon nicht zu unterscheiden, deshalb ein Ton für beides).
inline const QColor kField{0x0b, 0x14, 0x24};
// Rahmen: KOMPROMISS. Das Design nimmt sws-line rgba(148, 163, 184, .16) (= 29293c, 1,29:1 auf sws-bg) und für
// Hover/Knöpfe .28 (= 393b4d, 1,67:1) – beide unter der 1,8:1-Regel, auf kPanelAlt nur 1,15:1 bzw. 1,48:1. Genau
// diese kaum sichtbaren Kanten hatte der Betreiber 2026-10-10 beklagt. Gleicher Farbton, Deckung .38:
// 21+127·.38 = 69, 18+145·.38 = 73, 36+148·.38 = 92 → 1,83:1 auf kPanelAlt, 2,06:1 auf sws-bg.
// Wer auf .16 zurückgeht, macht Kartenränder und Felder wieder unsichtbar (der Test schlägt an).
inline const QColor kBorder{0x45, 0x49, 0x5c};
// Violetter Rand: Hover des Sekundärknopfs und aktiver Eintrag (Design rgba(196, 181, 253, .45) über sws-bg:
// 196·.45+21·.55 = 100, 181·.45+18·.55 = 91, 253·.45+36·.55 = 134). Steht für sws-brand-line (.32 = 443968 wäre
// auf kSelectSoft nur 1,4:1).
inline const QColor kAccentLine{0x64, 0x5b, 0x86};
inline const QColor kText{0xf4, 0xf7, 0xfb};         // sws-text
inline const QColor kMuted{0xb8, 0xc4, 0xd8};        // sws-muted
// Akzent als Schrift/Linie: sws-brand-2. sws-brand (8b5cf6) ist laut Design eine Fläche, keine Textfarbe (4,34:1).
inline const QColor kAccent{0xa7, 0x8b, 0xfa};
// Akzent als Fläche mit weißer Schrift: Primärknopf-Verlauf --sws-cta-grad #7c3aed → #6d28d9 (Weiß 5,7–7,1:1),
// Hover --sws-cta-grad-hover #8349f1 → #7432e6 (5,0–6,3:1). Flache Akzentflächen (Auswahl, An-Zustand) nehmen den
// Anfang des Verlaufs. Hellere Verläufe fallen unter WCAG 1.4.3 (Design-Kommentar Runde 2 der Website).
inline const QColor kAccentFill{0x7c, 0x3a, 0xed};
inline const QColor kAccentFillEnd{0x6d, 0x28, 0xd9};
inline const QColor kAccentFillHover{0x83, 0x49, 0xf1};
inline const QColor kAccentFillHoverEnd{0x74, 0x32, 0xe6};
inline const QColor kBuy{0x34, 0xd3, 0x99};          // sws-ok
// Gefüllte Status-Flächen mit weißer Schrift kennt das Design nicht (es nimmt helle Schrift auf 12-%-Tönung) – für
// Kauf-/Verkaufknöpfe und Plaketten die 700er-Stufe desselben Farbtons (Weiß ≥ 4,5:1).
inline const QColor kBuyFill{0x04, 0x78, 0x57};      // Smaragd 700 zu sws-ok
inline const QColor kSell{0xf8, 0x71, 0x71};         // sws-err
inline const QColor kSellFill{0xb9, 0x1c, 0x1c};     // Rot 700 zu sws-err
inline const QColor kWarn{0xfb, 0xbf, 0x24};         // sws-warn
inline const QColor kInfo{0x60, 0xa5, 0xfa};         // sws-info
inline const QColor kOnFill{0xff, 0xff, 0xff};  // Schrift auf allen *Fill-Flächen
// Getönte Status-Karten („JETZT KAUFEN“, Kaufkachel, Sperre): swa-ok-bg rgba(52, 211, 153, .12) über sws-bg
// (25, 41, 50) bzw. swa-err-bg rgba(248, 113, 113, .14) (53, 31, 47). Vorher fest 0f2a1a/2e1214 im QSS.
inline const QColor kBuySoft{0x19, 0x29, 0x32};
inline const QColor kSellSoft{0x35, 0x1f, 0x2f};
// Langer Lesetext (Erkenntnisse, Zusammenfassung, Faktencheck, Briefing): etwas weicher als kText. Betreiber
// 2026-10-16 zum Forschungsreiter: „das geht schon etwas auf die Augen“ – reines Fast-Weiß auf Fast-Schwarz über
// 190 Zeichen breite Zeilen. Kontrast bleibt ≥ 7:1 (Test). Design: sws-text-2 („Fließtext zweiter Ordnung“).
inline const QColor kReading{0xd8, 0xe2, 0xef};
inline const QColor kDim{0x8d, 0x97, 0xb3};          // sws-faint: Nebenzeilen (Quellen, Hinweise) – ≥ 4,5:1 überall
// Ruhige Auswahl (lange Listen, Seitenleiste, Menüs, Frageblase im Chat): sws-brand-bg, aber mit .22 statt .12
// Deckung (139·.22+21·.78 = 47, 92·.22+18·.78 = 34, 246·.22+36·.78 = 82). Mit .12 (231b3d) hob sich die Auswahl ohne
// Rand kaum von kPanel ab; das Design gibt ihr dafür einen Rand, die Frageblase im Chat (Rich-Text) kann keinen.
inline const QColor kSelectSoft{0x2f, 0x22, 0x52};
inline const QColor kWarnFill{0xb4, 0x53, 0x09};     // Plakette „unsicher“/„teils“: Bernstein 700 zu sws-warn
inline const QColor kModelFill{0x43, 0x38, 0xca};    // Plakette „Modellwissen“: Indigo 700 zu swa-tone-blue-2
// Der eine Fokusring des Designs (sws-focus, 12,7:1). Qt kennt kein :focus-visible – der Rand erscheint auch nach
// einem Mausklick, bis der Fokus weiterwandert.
inline const QColor kFocus{0xfc, 0xd3, 0x4d};

// Kontrastverhältnis nach WCAG 2.1 (1 … 21).
double contrast(const QColor& a, const QColor& b);

// Palette und Stylesheet, die apply() setzt – einzeln, damit der Test sie prüfen kann, ohne die Test-App umzufärben.
QPalette palette();
QString stylesheet();
void apply(QApplication& app);
QIcon app_icon();
QString css_color(const QColor& c);

// Vorgabe-CSS für Lesetexte (gui/reading_view): Farbe kReading, Zeilenabstand, Links ohne Unterstrich.
QString reading_css();
// Plakette (gefüllt, weiße Schrift) für Stufe oder Urteil – text wird maskiert.
QString pill_html(const QString& text, const QColor& fill);
// Karte im Lesetext: farbiger Strich links, Fläche kPanel, Abstand zur nächsten. inner ist fertiges HTML.
QString card_html(const QColor& stripe, const QString& inner);

}  // namespace swbh::gui::style
