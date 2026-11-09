// Reiter „Stadt“ im Hauptfenster: Stadt- und Weltbau, wie das Plugin sie meldet (server/city_api.hpp), als Karte.
//
// Jede Etappe wird beim Eintreffen EINMAL in ein QImage gemalt (ein Bildpunkt je Rasterzelle, city_pixels); paintEvent
// legt nur die fertigen Bilder skaliert übereinander. So kostet Ziehen, Zoomen und der Sekundentakt des Fortschritts
// kein erneutes Rastern (eine Großstadt hat ~200 Etappen à bis zu 360²).
// Die laufende Etappe (die zuletzt übernommene) bekommt einen Rahmen in Akzentfarbe, der NPC einen Punkt. Mausrad
// zoomt um den Zeiger, Ziehen verschiebt, Doppelklick passt wieder ein; ein neuer Auftrag passt ebenfalls ein.
//
// Weltbau: Die Raster früherer Kacheln (CityView::background) bleiben unter der laufenden Kachel stehen. Darüber das
// Kachelraster aus der Übersicht (fertig grün, gescheitert rot, wartend grau gestrichelt, laufend Akzent) und die
// Spieler als weiße Punkte. Bilder sind deshalb nach Etappe (Zeiger) geführt, nicht nach Etappennummer – die Nummern
// fangen je Kachel bei 0 an.
//
// Threads: nur GUI-Thread. Fehler: keine (ein Raster mit falscher Länge wird übersprungen).

#pragma once

#include "server/city_api.hpp"

#include <QImage>
#include <QPointF>
#include <QRectF>
#include <QWidget>

#include <map>
#include <memory>
#include <string>
#include <vector>

class QLabel;

namespace swbh::gui {

class CityView final : public QWidget {
    Q_OBJECT

public:
    explicit CityView(QWidget* parent = nullptr);

    // Neuer Schnappschuss (nullptr = noch kein Auftrag). Malt nur Etappen, die noch kein Bild haben.
    void show_city(std::shared_ptr<const server::CityView> view);

    // Für Tests: Kopfzeile, Zahl gemalter Etappen, Farbe der Karte an einer Weltstelle (0 = nichts dort).
    QString header_plain() const;
    int stage_images() const { return static_cast<int>(images_.size()); }
    int image_builds() const { return image_builds_; }
    std::uint32_t color_at(int world_x, int world_z) const;

private:
    class Canvas;
    friend class Canvas;

    void fit();
    void paint_canvas(QWidget& canvas);
    void update_header();
    QRectF world_bounds() const;
    bool show_tiles() const;

    QLabel* header_ = nullptr;
    QLabel* legend_ = nullptr;
    Canvas* canvas_ = nullptr;
    std::shared_ptr<const server::CityView> view_;
    // Etappe → Bild. Die Zeiger gehören dem Schnappschuss view_ (shared_ptr hält die Etappen am Leben).
    std::map<const server::CityStage*, QImage> images_;
    std::vector<const server::CityStage*> order_;  // Zeichenreihenfolge: Hintergrund, dann laufender Auftrag
    std::string job_;
    int image_builds_ = 0;
    // Ansicht: Weltkoordinate in der Bildmitte und Bildpunkte je Block.
    QPointF center_{0, 0};
    double scale_ = 1.0;
    bool user_moved_ = false;
    QPointF drag_from_;
    bool dragging_ = false;
};

}  // namespace swbh::gui
