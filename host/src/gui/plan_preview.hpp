// Plan-Vorschau im Hauptfenster: der zuletzt erzeugte Bauplan als Draufsicht oder senkrechter Schnitt, darüber
// Auftragstext, Token-Zahl, Dauer, Schritte und Blöcke, darunter Hinweise der Vorschau (Markierungen, Ausgelassenes).
//
// Gezeichnet mit QPainter in ein QImage (ein Bildpunkt je Block) und ohne Glättung hochskaliert – kein OpenGL: Die App
// läuft neben llama.cpp auf derselben GPU, und ein zweiter GPU-Kontext für eine 40 × 40-Fläche wäre Aufwand ohne Nutzen.
// Die Umrechnung Plan → Blöcke steht ohne Qt in gui/plan_view.hpp (dort auch, was sie NICHT kann).
//
// Nur Aufträge mit der Grammatik „bauplan“ ersetzen die Vorschau; freie Antworten lassen den letzten Plan stehen. Ein
// fehlgeschlagener Bauplan-Auftrag zeigt seinen Fehler statt eines alten Plans – sonst sähe ein Fehlschlag wie
// Erfolg aus.
//
// Threads: nur GUI-Thread. Fehler: ein unlesbarer Plan erscheint als Hinweistext, nie als Ausnahme.

#pragma once

#include "gui/plan_view.hpp"

#include <QColor>
#include <QImage>
#include <QString>
#include <QWidget>

#include <vector>

class QButtonGroup;
class QLabel;
class QPushButton;
class QSlider;

namespace swbh::gui {

class PlanPreview final : public QWidget {
    Q_OBJECT

public:
    enum class Mode { Top, Section };

    explicit PlanPreview(QWidget* parent = nullptr);

    // true, wenn die Vorschau gewechselt hat (Bauplan-Auftrag), false bei freien Antworten.
    bool show_record(const PlanRecord& record);
    void set_mode(Mode mode);
    Mode mode() const { return mode_; }
    // Schnittebene; wird auf die Ausdehnung des Plans geklemmt.
    void set_section_z(int z);
    int section_z() const { return section_z_; }

    const PlanBlocks& blocks() const { return blocks_; }
    const PlanRecord& record() const { return record_; }
    // Kopfzeilen als Klartext (für Tests und Bildschirmleser).
    QString header_plain() const;
    // Aktuelle Ansicht, ein Bildpunkt je Block, v nach oben (Zeile 0 = oben im Bild). Leeres Bild ohne Plan.
    // Zwischengespeichert: entsteht nur in rebuild() (neuer Plan, andere Ansicht, andere Ebene), nicht je paintEvent.
    // Vorher baute jedes Neuzeichnen das Bild samt block_rgb je Zelle neu – gemessen 40 ms bei 265 × 265, spürbar beim
    // Ziehen des Teilers (Prüfbefund 2026-10-29).
    const QImage& view_image() const { return image_; }
    // Zahl der Bildaufbauten seit dem Start (für den Test, dass Neuzeichnen das Bild nicht neu rechnet).
    int image_builds() const { return image_builds_; }

    QSize sizeHint() const override;

private:
    class Canvas;
    friend class Canvas;

    void rebuild();
    QImage render_image() const;
    void update_header();
    void paint_canvas(QWidget& canvas) const;

    PlanRecord record_;
    PlanBlocks blocks_;
    PlanView view_;
    std::vector<QColor> colors_;  // block_rgb je Paletteneintrag, einmal je Plan
    QImage image_;
    int image_builds_ = 0;
    Mode mode_ = Mode::Top;
    int section_z_ = 0;
    bool has_record_ = false;

    QLabel* title_ = nullptr;
    QLabel* facts_ = nullptr;
    QLabel* notes_ = nullptr;
    QPushButton* top_ = nullptr;
    QPushButton* section_ = nullptr;
    QButtonGroup* modes_ = nullptr;
    QSlider* slider_ = nullptr;
    QLabel* slider_label_ = nullptr;
    QWidget* canvas_ = nullptr;
};

}  // namespace swbh::gui
