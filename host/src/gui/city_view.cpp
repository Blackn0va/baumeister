#include "gui/city_view.hpp"

#include "gui/city_paint.hpp"
#include "gui/style.hpp"

#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QVBoxLayout>
#include <QWheelEvent>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace swbh::gui {

class CityView::Canvas final : public QWidget {
public:
    explicit Canvas(CityView& owner) : QWidget(&owner), owner_(owner) {
        setMinimumSize(fontMetrics().height() * 12, fontMetrics().height() * 10);
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
        setMouseTracking(false);
    }

protected:
    void paintEvent(QPaintEvent*) override { owner_.paint_canvas(*this); }

    void wheelEvent(QWheelEvent* e) override {
        const double steps = e->angleDelta().y() / 120.0;
        if (steps == 0.0 || owner_.view_ == nullptr) {
            return;
        }
        // Um den Mauszeiger zoomen: Die Weltstelle unter dem Zeiger bleibt, wo sie ist.
        const QPointF mouse = e->position();
        const QPointF mid(width() / 2.0, height() / 2.0);
        const QPointF world = owner_.center_ + (mouse - mid) / owner_.scale_;
        owner_.scale_ = std::clamp(owner_.scale_ * std::pow(1.25, steps), 0.01, 64.0);
        owner_.center_ = world - (mouse - mid) / owner_.scale_;
        owner_.user_moved_ = true;
        update();
    }

    void mousePressEvent(QMouseEvent* e) override {
        if (e->button() == Qt::LeftButton) {
            owner_.dragging_ = true;
            owner_.drag_from_ = e->position();
        }
    }

    void mouseMoveEvent(QMouseEvent* e) override {
        if (!owner_.dragging_) {
            return;
        }
        owner_.center_ -= (e->position() - owner_.drag_from_) / owner_.scale_;
        owner_.drag_from_ = e->position();
        owner_.user_moved_ = true;
        update();
    }

    void mouseReleaseEvent(QMouseEvent*) override { owner_.dragging_ = false; }

    void mouseDoubleClickEvent(QMouseEvent*) override {
        owner_.user_moved_ = false;
        owner_.fit();
        update();
    }

    void resizeEvent(QResizeEvent*) override {
        if (!owner_.user_moved_) {
            owner_.fit();
        }
    }

private:
    CityView& owner_;
};

CityView::CityView(QWidget* parent) : QWidget(parent) {
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    header_ = new QLabel(tr("Noch kein Stadt- oder Weltbau gemeldet. Startet das Plugin einen (/bm stadt los, "
                            "/bm weltbau), erscheint er hier als Karte."),
                         this);
    header_->setWordWrap(true);
    header_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    root->addWidget(header_);
    canvas_ = new Canvas(*this);
    root->addWidget(canvas_, 1);
    // Legende als Rich-Text: farbiges Quadrat je Kategorie.
    QString legend;
    for (std::uint8_t k = 1; k < 10; ++k) {
        const QColor c = QColor::fromRgba(city_color(k));
        legend += QStringLiteral("<span style=\"color:%1\">■</span> %2 &nbsp; ")
                      .arg(c.name(), QString::fromUtf8(city_category_name(k)));
    }
    legend_ = new QLabel(legend + tr("· Mausrad zoomt, Ziehen verschiebt, Doppelklick passt ein"), this);
    legend_->setObjectName(QStringLiteral("muted"));
    legend_->setTextFormat(Qt::RichText);
    legend_->setWordWrap(true);
    root->addWidget(legend_);
}

void CityView::show_city(std::shared_ptr<const server::CityView> view) {
    if (view == nullptr) {
        return;
    }
    if (view->job != job_) {
        job_ = view->job;
        user_moved_ = false;  // neuer Auftrag: wieder einpassen
    }
    view_ = std::move(view);
    // Alle Etappen dieses Schnappschusses in Zeichenreihenfolge; Bilder, die nicht mehr dazugehören, fallen weg.
    std::vector<const server::CityStage*> order;
    order.reserve(view_->background.size() + view_->stage_list.size());
    for (const auto& s : view_->background) {
        order.push_back(s.get());
    }
    for (const auto& s : view_->stage_list) {
        order.push_back(s.get());
    }
    std::map<const server::CityStage*, QImage> keep;
    bool added = false;
    for (const server::CityStage* s : order) {
        if (auto it = images_.find(s); it != images_.end()) {
            keep.emplace(s, std::move(it->second));  // schon gemalt
            continue;
        }
        const std::vector<std::uint32_t> px = city_pixels(s->w, s->h, s->cells);
        if (px.empty()) {
            continue;
        }
        QImage img(s->w, s->h, QImage::Format_ARGB32);
        for (int y = 0; y < s->h; ++y) {
            std::memcpy(img.scanLine(y), px.data() + static_cast<std::size_t>(y) * s->w,
                        static_cast<std::size_t>(s->w) * sizeof(std::uint32_t));
        }
        keep.emplace(s, std::move(img));
        ++image_builds_;
        added = true;
    }
    images_ = std::move(keep);
    order_.clear();
    for (const server::CityStage* s : order) {
        if (images_.contains(s)) {
            order_.push_back(s);
        }
    }
    if ((added || order_.empty()) && !user_moved_) {
        fit();
    }
    update_header();
    canvas_->update();
}

bool CityView::show_tiles() const {
    // Kachelraster nur beim Weltbau (oder solange nur die Übersicht da ist) – über einer Stadt in der Stadtwelt wäre es
    // ein Gitter aus einer anderen Welt.
    return view_ != nullptr && view_->overview != nullptr && (view_->kind == "weltbau" || view_->job.empty()) &&
           view_->overview->world == view_->world;
}

QRectF CityView::world_bounds() const {
    QRectF r;
    if (view_ == nullptr) {
        return r;
    }
    for (const server::CityStage* s : order_) {
        r = r.united(QRectF(s->x0, s->z0, double(s->w) * s->cell, double(s->h) * s->cell));
    }
    if (show_tiles()) {
        const server::WorldOverview& o = *view_->overview;
        const double t = o.tile;
        auto add = [&](int tx, int tz) { r = r.united(QRectF(tx * t, tz * t, t, t)); };
        if (o.has_current) {
            add(o.current_tx, o.current_tz);
        }
        for (const auto& [tx, tz] : o.queue) {
            add(tx, tz);
        }
        // Fertige Kacheln nur einbeziehen, solange sonst nichts da ist – sonst zöge eine ferne alte Kachel die Ansicht
        // auseinander.
        if (r.isEmpty()) {
            for (const auto& [tx, tz] : o.done) {
                add(tx, tz);
            }
        }
        for (const auto& [x, z] : o.players) {
            r = r.united(QRectF(x - 32, z - 32, 64, 64));
        }
    }
    if (r.isEmpty()) {
        r = QRectF(view_->origin_x - 64, view_->origin_z - 64, 128, 128);
    }
    return r;
}

void CityView::fit() {
    const QRectF b = world_bounds();
    if (b.isEmpty() || canvas_ == nullptr) {
        return;
    }
    center_ = b.center();
    const double sx = std::max(1, canvas_->width() - 16) / b.width();
    const double sz = std::max(1, canvas_->height() - 16) / b.height();
    scale_ = std::clamp(std::min(sx, sz), 0.01, 64.0);
}

void CityView::paint_canvas(QWidget& canvas) {
    QPainter p(&canvas);
    p.fillRect(canvas.rect(), style::kField);
    if (view_ == nullptr) {
        return;
    }
    // Weltkoordinate → Bildpunkt: (welt − center) · scale + Mitte. Ohne Glättung: Zellen bleiben scharfe Quadrate.
    p.translate(canvas.width() / 2.0, canvas.height() / 2.0);
    p.scale(scale_, scale_);
    p.translate(-center_.x(), -center_.y());
    p.setRenderHint(QPainter::SmoothPixmapTransform, false);
    for (const server::CityStage* s : order_) {
        p.drawImage(QRectF(s->x0, s->z0, double(s->w) * s->cell, double(s->h) * s->cell), images_.at(s));
    }
    // Linien in Bildpunkten, nicht in Blöcken: Breite / scale.
    auto pen = [&](const QColor& c, double px, Qt::PenStyle st = Qt::SolidLine) {
        QPen q(c);
        q.setWidthF(px / scale_);
        q.setStyle(st);
        return q;
    };
    p.setBrush(Qt::NoBrush);
    if (show_tiles()) {
        const server::WorldOverview& o = *view_->overview;
        const double t = o.tile;
        p.setPen(pen(style::kBuy, 1.5));
        for (const auto& [tx, tz] : o.done) {
            p.drawRect(QRectF(tx * t, tz * t, t, t));
        }
        p.setPen(pen(style::kSell, 1.5));
        for (const auto& [tx, tz] : o.failed) {
            p.drawRect(QRectF(tx * t, tz * t, t, t));
        }
        p.setPen(pen(style::kDim, 1.5, Qt::DashLine));
        for (const auto& [tx, tz] : o.queue) {
            p.drawRect(QRectF(tx * t, tz * t, t, t));
        }
        if (o.has_current) {
            p.setPen(pen(style::kAccent, 3.0));
            p.drawRect(QRectF(o.current_tx * t, o.current_tz * t, t, t));
        }
        p.setPen(pen(style::kField, 1.0));
        p.setBrush(style::kText);
        for (const auto& [x, z] : o.players) {
            p.drawEllipse(QPointF(x + 0.5, z + 0.5), 4.0 / scale_, 4.0 / scale_);
        }
        p.setBrush(Qt::NoBrush);
    }
    // Rahmen der laufenden Etappe.
    if (view_->has_progress && !view_->ended) {
        const int running = view_->progress.stages_done - 1;
        for (const auto& s : view_->stage_list) {
            if (s->index == running) {
                p.setPen(pen(style::kAccent, 2.0));
                p.drawRect(QRectF(s->x0, s->z0, double(s->w) * s->cell, double(s->h) * s->cell));
            }
        }
    }
    if (view_->has_progress && view_->progress.has_npc && !view_->ended) {
        p.setPen(pen(style::kText, 1.5));
        p.setBrush(style::kAccent);
        p.drawEllipse(QPointF(view_->progress.npc_x + 0.5, view_->progress.npc_z + 0.5), 5.0 / scale_, 5.0 / scale_);
    }
}

void CityView::update_header() {
    if (view_ == nullptr) {
        return;
    }
    std::string text;
    if (!view_->job.empty()) {
        CityStatus s;
        s.kind = view_->kind;
        s.place = view_->place;
        s.stages = view_->stages;
        s.has_progress = view_->has_progress;
        s.stages_done = view_->progress.stages_done;
        s.blocks = view_->progress.blocks;
        s.planned = view_->progress.planned;
        s.rate = view_->progress.rate;
        s.paused = view_->progress.paused;
        s.ended = view_->ended;
        s.end_reason = view_->end_reason;
        s.end_blocks = view_->end_blocks;
        s.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - view_->started).count();
        text = city_status_text(s);
    }
    if (show_tiles()) {
        const server::WorldOverview& o = *view_->overview;
        WorldStatus w;
        w.done = static_cast<int>(o.done.size());
        w.failed = static_cast<int>(o.failed.size());
        w.queued = static_cast<int>(o.queue.size());
        w.players = static_cast<int>(o.players.size());
        w.auto_on = o.auto_on;
        w.phase = o.phase;
        w.has_current = o.has_current;
        w.current_tx = o.current_tx;
        w.current_tz = o.current_tz;
        const std::string line = world_status_text(w);
        text = text.empty() ? line : text + "\n" + line;
    }
    header_->setText(QString::fromStdString(text));
}

QString CityView::header_plain() const {
    return header_->text();
}

std::uint32_t CityView::color_at(int world_x, int world_z) const {
    // Später Gezeichnetes liegt oben – rückwärts suchen.
    for (auto it = order_.rbegin(); it != order_.rend(); ++it) {
        const server::CityStage& s = **it;
        const int x = (world_x - s.x0) / s.cell;
        const int z = (world_z - s.z0) / s.cell;
        if (world_x >= s.x0 && world_z >= s.z0 && x < s.w && z < s.h) {
            const std::uint32_t c = images_.at(*it).pixel(x, z);
            if ((c >> 24) != 0) {
                return c;
            }
        }
    }
    return 0;
}

}  // namespace swbh::gui
