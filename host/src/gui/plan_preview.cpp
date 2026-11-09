#include "gui/plan_preview.hpp"

#include "gui/style.hpp"

#include <QButtonGroup>
#include <QHBoxLayout>
#include <QLabel>
#include <QLocale>
#include <QPainter>
#include <QPushButton>
#include <QRegularExpression>
#include <QSlider>
#include <QVBoxLayout>

#include <algorithm>
#include <cmath>

namespace swbh::gui {

namespace {

QString qs(const std::string& s) { return QString::fromUtf8(s.data(), static_cast<qsizetype>(s.size())); }

QString num(double v, int digits) { return QLocale(QLocale::German).toString(v, 'f', digits); }

QString thousands(long long v) { return QLocale(QLocale::German).toString(v); }

// Höchstens so viele Hinweise im Kopf; der Rest als „+n weitere“ – sonst schöbe ein Plan voller Module die Fläche weg.
constexpr int kMaxNotes = 3;

}  // namespace

class PlanPreview::Canvas final : public QWidget {
public:
    explicit Canvas(PlanPreview& owner) : QWidget(&owner), owner_(owner) {
        setMinimumSize(fontMetrics().height() * 12, fontMetrics().height() * 10);
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
    }

protected:
    void paintEvent(QPaintEvent*) override { owner_.paint_canvas(*this); }

private:
    PlanPreview& owner_;
};

PlanPreview::PlanPreview(QWidget* parent) : QWidget(parent) {
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    title_ = new QLabel(this);
    title_->setTextFormat(Qt::RichText);
    title_->setWordWrap(true);
    title_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    facts_ = new QLabel(this);
    facts_->setObjectName(QStringLiteral("muted"));
    facts_->setWordWrap(true);
    notes_ = new QLabel(this);
    notes_->setTextFormat(Qt::RichText);
    notes_->setWordWrap(true);
    root->addWidget(title_);
    root->addWidget(facts_);
    root->addWidget(notes_);

    auto* controls = new QHBoxLayout();
    top_ = new QPushButton(tr("Draufsicht"), this);
    section_ = new QPushButton(tr("Schnitt"), this);
    top_->setCheckable(true);
    section_->setCheckable(true);
    top_->setChecked(true);
    top_->setToolTip(tr("Je Spalte der oberste Block, hellere Farbe = höher. Vorn (Seite des Spielers) ist unten."));
    section_->setToolTip(tr("Senkrechter Schnitt quer durch den Bau – die Ebene mit dem Regler wählen."));
    modes_ = new QButtonGroup(this);
    modes_->setExclusive(true);
    modes_->addButton(top_);
    modes_->addButton(section_);
    slider_ = new QSlider(Qt::Horizontal, this);
    slider_label_ = new QLabel(this);
    slider_label_->setObjectName(QStringLiteral("muted"));
    controls->addWidget(top_);
    controls->addWidget(section_);
    controls->addWidget(slider_, 1);
    controls->addWidget(slider_label_);
    root->addLayout(controls);

    canvas_ = new Canvas(*this);
    root->addWidget(canvas_, 1);

    connect(top_, &QPushButton::clicked, this, [this] { set_mode(Mode::Top); });
    connect(section_, &QPushButton::clicked, this, [this] { set_mode(Mode::Section); });
    connect(slider_, &QSlider::valueChanged, this, [this](int z) { set_section_z(z); });
    update_header();
    rebuild();
}

QSize PlanPreview::sizeHint() const { return {fontMetrics().height() * 22, fontMetrics().height() * 24}; }

bool PlanPreview::show_record(const PlanRecord& record) {
    if (record.grammar != "bauplan") {
        return false;
    }
    record_ = record;
    has_record_ = true;
    blocks_ = record.ok ? rasterize_plan(record.text) : PlanBlocks{};
    colors_.clear();
    colors_.reserve(blocks_.palette.size());
    for (const std::string& name : blocks_.palette) {
        const std::uint32_t rgb = block_rgb(name);
        colors_.emplace_back(static_cast<int>((rgb >> 16) & 0xff), static_cast<int>((rgb >> 8) & 0xff),
                             static_cast<int>(rgb & 0xff));
    }
    // Neuer Plan: Schnitt durch die Mitte – dort liegen bei Häusern und Türmen Tür, Innenraum und Dachfirst.
    section_z_ = blocks_.empty() ? 0 : (blocks_.min_z + blocks_.max_z) / 2;
    update_header();
    rebuild();
    return true;
}

void PlanPreview::set_mode(Mode mode) {
    mode_ = mode;
    top_->setChecked(mode == Mode::Top);
    section_->setChecked(mode == Mode::Section);
    rebuild();
}

void PlanPreview::set_section_z(int z) {
    if (!blocks_.empty()) {
        z = std::clamp(z, blocks_.min_z, blocks_.max_z);
    }
    section_z_ = z;
    rebuild();
}

void PlanPreview::rebuild() {
    view_ = mode_ == Mode::Top ? top_view(blocks_) : section_view(blocks_, section_z_);
    image_ = render_image();
    ++image_builds_;
    const bool section = mode_ == Mode::Section && !blocks_.empty();
    slider_->setVisible(section);
    slider_label_->setVisible(section);
    if (section) {
        const QSignalBlocker block(slider_);
        slider_->setRange(blocks_.min_z, blocks_.max_z);
        slider_->setValue(section_z_);
        slider_label_->setText(tr("z = %1").arg(section_z_));
    }
    top_->setEnabled(!blocks_.empty());
    section_->setEnabled(!blocks_.empty());
    canvas_->update();
}

void PlanPreview::update_header() {
    if (!has_record_) {
        title_->setText(tr("<b>Noch kein Bauplan</b>"));
        facts_->setText(tr("Die Vorschau erscheint, sobald das Modell einen Plan fertig hat."));
        notes_->clear();
        notes_->setVisible(false);
        return;
    }
    const QString request = record_.request.empty() ? tr("(ohne Auftragstext)") : qs(record_.request);
    title_->setText(QStringLiteral("<b>„%1“</b>").arg(request.toHtmlEscaped()));
    title_->setToolTip(qs(blocks_.design));

    QStringList facts;
    // Ohne Messwerte (Beispielplan im Bildschirmfoto) keine „0 Token in 0,0 s“ vortäuschen.
    if (record_.tokens > 0 || record_.seconds > 0.0) {
        facts << tr("%1 Token").arg(record_.tokens) << tr("%1 s").arg(num(record_.seconds, 1))
              << tr("%1 Token/s").arg(num(record_.tokens_per_second, 1));
    }
    if (record_.ok && blocks_.ok) {
        facts << (blocks_.steps == 1 ? tr("1 Schritt") : tr("%1 Schritte").arg(blocks_.steps))
              << tr("%1 Blöcke").arg(thousands(static_cast<long long>(blocks_.cells.size())));
        if (!blocks_.empty()) {
            facts << tr("%1 × %2 × %3 (B × T × H)")
                         .arg(blocks_.max_x - blocks_.min_x + 1)
                         .arg(blocks_.max_z - blocks_.min_z + 1)
                         .arg(blocks_.max_y - blocks_.min_y + 1);
        }
        if (blocks_.mode == "update") {
            facts << tr("ersetzt den letzten Bau");
        }
    }
    facts_->setText(facts.join(QStringLiteral(" · ")));

    QStringList lines;
    if (!record_.ok) {
        lines << QStringLiteral("<span style='color:%1'>%2</span>")
                     .arg(style::css_color(style::kSell),
                          tr("Fehlgeschlagen: %1").arg(qs(record_.error)).toHtmlEscaped());
    } else if (!blocks_.ok) {
        lines << QStringLiteral("<span style='color:%1'>%2</span>")
                     .arg(style::css_color(style::kSell),
                          tr("Antwort nicht als Bauplan lesbar: %1").arg(qs(blocks_.error)).toHtmlEscaped());
    } else {
        if (!blocks_.reply.empty()) {
            lines << QStringLiteral("<i>%1</i>").arg(qs(blocks_.reply).toHtmlEscaped());
        }
        const int shown = std::min<int>(kMaxNotes, static_cast<int>(blocks_.notes.size()));
        for (int i = 0; i < shown; ++i) {
            const QString note = qs(blocks_.notes[static_cast<std::size_t>(i)]).toHtmlEscaped();
            lines << QStringLiteral("<span style='color:%1'>%2</span>").arg(style::css_color(style::kWarn), note);
        }
        if (static_cast<int>(blocks_.notes.size()) > shown) {
            lines << tr("(+%1 weitere Hinweise)").arg(static_cast<int>(blocks_.notes.size()) - shown);
        }
    }
    notes_->setText(lines.join(QStringLiteral("<br>")));
    notes_->setVisible(!lines.isEmpty());
}

QString PlanPreview::header_plain() const {
    QString s = title_->text() + QLatin1Char('\n') + facts_->text() + QLatin1Char('\n') + notes_->text();
    // Für Tests und Bildschirmleser: Auszeichnungen raus, Zeichenreferenzen zurück.
    s.remove(QRegularExpression(QStringLiteral("<[^>]*>")));
    s.replace(QStringLiteral("&amp;"), QStringLiteral("&"))
        .replace(QStringLiteral("&lt;"), QStringLiteral("<"))
        .replace(QStringLiteral("&gt;"), QStringLiteral(">"))
        .replace(QStringLiteral("&quot;"), QStringLiteral("\""));
    return s;
}

QImage PlanPreview::render_image() const {
    if (view_.width <= 0 || view_.height <= 0) {
        return {};
    }
    QImage img(view_.width, view_.height, QImage::Format_RGB32);
    img.fill(style::kField);
    // Draufsicht: Helligkeit nach Höhe (0,55 … 1,0), sonst sähe ein Turm von oben aus wie sein Fundament.
    const int y_span = std::max(1, blocks_.max_y - blocks_.min_y);
    for (int v = 0; v < view_.height; ++v) {
        for (int u = 0; u < view_.width; ++u) {
            const ViewCell& c = view_.at(u, v);
            if (c.block < 0) {
                continue;
            }
            QColor color = colors_[static_cast<std::size_t>(c.block)];
            if (mode_ == Mode::Top && blocks_.max_y > blocks_.min_y) {
                const double f = 0.55 + 0.45 * static_cast<double>(c.level - blocks_.min_y) / y_span;
                color = QColor(static_cast<int>(std::lround(color.red() * f)),
                               static_cast<int>(std::lround(color.green() * f)),
                               static_cast<int>(std::lround(color.blue() * f)));
            }
            img.setPixelColor(u, view_.height - 1 - v, color);
        }
    }
    return img;
}

void PlanPreview::paint_canvas(QWidget& canvas) const {
    QPainter p(&canvas);
    const QRect area = canvas.rect();
    p.fillRect(area, style::kField);
    p.setPen(style::kBorder);
    p.drawRect(area.adjusted(0, 0, -1, -1));
    const QImage& img = image_;
    const int line = canvas.fontMetrics().height();
    if (img.isNull()) {
        p.setPen(style::kMuted);
        QString text = tr("Noch kein Bauplan");
        if (has_record_ && !record_.ok) {
            text = tr("Kein Plan – der Auftrag ist fehlgeschlagen.");
        } else if (has_record_ && !blocks_.ok) {
            text = tr("Die Antwort ist kein lesbarer Bauplan.");
        } else if (has_record_ && mode_ == Mode::Section) {
            text = tr("In dieser Ebene liegt kein Block.");
        } else if (has_record_) {
            text = tr("Der Plan setzt keine Blöcke.");
        }
        p.drawText(area.adjusted(line, line, -line, -line), Qt::AlignCenter | Qt::TextWordWrap, text);
        return;
    }
    // Unten eine Zeile für die Achsen; Rest für das Bild, ganzzahlig vergrößert, solange es passt (scharfe Kanten).
    const QRect room = area.adjusted(line / 2, line / 2, -line / 2, -(line + line / 2));
    double scale = std::min(static_cast<double>(room.width()) / img.width(),
                            static_cast<double>(room.height()) / img.height());
    if (scale >= 1.0) {
        scale = std::floor(scale);
    }
    const int w = std::max(1, static_cast<int>(img.width() * scale));
    const int h = std::max(1, static_cast<int>(img.height() * scale));
    const QRect target(room.left() + (room.width() - w) / 2, room.top() + (room.height() - h) / 2, w, h);
    p.setRenderHint(QPainter::SmoothPixmapTransform, false);
    p.drawImage(target, img);
    if (scale >= 6.0) {
        // Blockraster ab 6 Bildpunkten je Block – darunter wäre es nur Grauschleier.
        QColor grid = style::kBackground;
        grid.setAlpha(110);
        p.setPen(grid);
        for (int u = 1; u < img.width(); ++u) {
            const int x = target.left() + static_cast<int>(u * scale);
            p.drawLine(x, target.top(), x, target.bottom());
        }
        for (int v = 1; v < img.height(); ++v) {
            const int y = target.top() + static_cast<int>(v * scale);
            p.drawLine(target.left(), y, target.right(), y);
        }
    }
    p.setPen(style::kMuted);
    const int x_lo = view_.origin_u;
    const int x_hi = view_.origin_u + view_.width - 1;
    const int v_lo = view_.origin_v;
    const int v_hi = view_.origin_v + view_.height - 1;
    const QString axes = mode_ == Mode::Top
                             ? tr("Draufsicht · x %1 … %2 · z %3 … %4 · vorn = unten")
                                   .arg(x_lo)
                                   .arg(x_hi)
                                   .arg(v_lo)
                                   .arg(v_hi)
                             : tr("Schnitt bei z = %1 · x %2 … %3 · y %4 … %5")
                                   .arg(section_z_)
                                   .arg(x_lo)
                                   .arg(x_hi)
                                   .arg(v_lo)
                                   .arg(v_hi);
    p.drawText(QRect(area.left() + line / 2, area.bottom() - line - line / 4, area.width() - line, line),
               Qt::AlignLeft | Qt::AlignVCenter, axes);
}

}  // namespace swbh::gui
