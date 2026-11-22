#include "gui/modules_page.hpp"

#include "gui/style.hpp"

#include <QCheckBox>
#include <QFrame>
#include <QLabel>
#include <QScrollArea>
#include <QSignalBlocker>
#include <QTextDocument>
#include <QVBoxLayout>

namespace swbh::gui {

namespace {

QString qs(const std::string& s) { return QString::fromUtf8(s.data(), static_cast<qsizetype>(s.size())); }

QString esc(const std::string& s) { return qs(s).toHtmlEscaped(); }

QString colored(const QColor& c, const QString& html) {
    return QStringLiteral("<span style='color:%1'>%2</span>").arg(style::css_color(c), html);
}

const QColor& host_color(HostModuleState s) {
    switch (s) {
    case HostModuleState::Active: return style::kBuy;
    case HostModuleState::Waiting: return style::kWarn;
    case HostModuleState::Failed: return style::kSell;
    case HostModuleState::Off: return style::kDim;
    }
    return style::kDim;
}

const QColor& server_color(server::ModuleRunState s) {
    switch (s) {
    case server::ModuleRunState::Active: return style::kBuy;
    case server::ModuleRunState::Waiting: return style::kWarn;
    case server::ModuleRunState::Failed: return style::kSell;
    case server::ModuleRunState::Off: return style::kDim;
    }
    return style::kDim;
}

QLabel* rich_label(QWidget* parent) {
    auto* l = new QLabel(parent);
    l->setTextFormat(Qt::RichText);
    l->setWordWrap(true);
    l->setTextInteractionFlags(Qt::TextSelectableByMouse);
    return l;
}

QString plain(const QString& html) {
    QTextDocument doc;
    doc.setHtml(html);
    return doc.toPlainText();
}

QLabel* section_title(const QString& text, QWidget* parent) {
    auto* t = new QLabel(text, parent);
    t->setObjectName(QStringLiteral("muted"));
    t->setWordWrap(true);
    return t;
}

}  // namespace

ModulesPage::ModulesPage(ToggleFn toggle, QWidget* parent) : QWidget(parent), toggle_(std::move(toggle)) {
    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    // Rollbar: Die Spalte rechts neben der Konsole ist schmal (~450 px, Fallen in Gedaechtnis.md), acht eigene und
    // bis zu 64 Server-Module passen dort nie ohne Rollen.
    auto* scroll = new QScrollArea(this);
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* inner = new QWidget(scroll);
    auto* v = new QVBoxLayout(inner);
    v->addWidget(section_title(tr("MODULE DIESER APP – an/aus wird gespeichert"), inner));
    host_layout_ = new QVBoxLayout();
    v->addLayout(host_layout_);
    server_head_ = section_title(QString(), inner);
    v->addSpacing(fontMetrics().height() / 2);
    v->addWidget(server_head_);
    server_list_ = new QWidget(inner);
    server_layout_ = new QVBoxLayout(server_list_);
    server_layout_->setContentsMargins(0, 0, 0, 0);
    v->addWidget(server_list_);
    v->addStretch(1);
    scroll->setWidget(inner);
    outer->addWidget(scroll);
    show_server(nullptr);
}

void ModulesPage::show_host(const std::vector<HostModule>& modules) {
    bool same = modules.size() == host_rows_.size();
    for (std::size_t i = 0; same && i < modules.size(); ++i) {
        same = modules[i].id == host_rows_[i].id;
    }
    if (!same) {
        for (HostRow& r : host_rows_) {
            delete r.box->parentWidget();  // die Karte samt Inhalt
        }
        host_rows_.clear();
        for (const HostModule& m : modules) {
            auto* frame = new QFrame(this);
            frame->setObjectName(QStringLiteral("card"));
            auto* fl = new QVBoxLayout(frame);
            fl->setSpacing(fontMetrics().height() / 6);
            HostRow row;
            row.id = m.id;
            row.box = new QCheckBox(qs(m.name), frame);
            row.state = rich_label(frame);
            row.description = rich_label(frame);
            fl->addWidget(row.box);
            fl->addWidget(row.state);
            fl->addWidget(row.description);
            const std::string id = m.id;
            // clicked statt toggled: Nur ein Klick des Nutzers schaltet, nicht das Nachführen in show_host.
            connect(row.box, &QCheckBox::clicked, this, [this, id](bool on) {
                if (toggle_) {
                    toggle_(id, on);
                }
            });
            host_layout_->addWidget(frame);
            host_rows_.push_back(row);
        }
    }
    for (std::size_t i = 0; i < modules.size(); ++i) {
        const HostModule& m = modules[i];
        HostRow& r = host_rows_[i];
        {
            const QSignalBlocker block(r.box);
            r.box->setChecked(m.on);
        }
        r.box->setEnabled(m.switchable);
        QString state = colored(host_color(m.state), QStringLiteral("● ") + qs(std::string(host_module_state_de(m.state))));
        if (!m.reason.empty()) {
            state += QStringLiteral(" – ") + esc(m.reason);
        }
        if (r.state->text() != state) {
            r.state->setText(state);
        }
        // Wirkung des Schalters als Tooltip: Als dritte Zeile war jede Karte in der ~450 px schmalen Spalte sieben
        // Zeilen hoch, und die Server-Module lagen ganz außer Sicht (Bildschirmfoto 2026-11-22).
        const QString desc = colored(style::kDim, esc(m.description));
        if (r.description->text() != desc) {
            r.description->setText(desc);
            const QString tip = qs(m.effect);
            r.box->setToolTip(tip);
            r.state->setToolTip(tip);
            r.description->setToolTip(tip);
        }
    }
}

void ModulesPage::show_server(std::shared_ptr<const server::ModuleReport> report) {
    for (QLabel* l : server_labels_) {
        delete l;
    }
    server_labels_.clear();
    if (!report) {
        server_head_->setText(tr("MODULE AUF DEM SERVER"));
        auto* l = rich_label(server_list_);
        l->setText(colored(style::kDim, tr("Server meldet keine Module – Plugin noch nicht verbunden oder älterer "
                                           "Stand. Geschaltet wird im Spiel mit /bm module.")));
        server_layout_->addWidget(l);
        server_labels_.push_back(l);
        return;
    }
    server_head_->setText(tr("MODULE AUF DEM SERVER – gemeldet um %1, schalten im Spiel mit /bm module")
                              .arg(qs(report->received_hms)));
    if (report->modules.empty()) {
        auto* l = rich_label(server_list_);
        l->setText(colored(style::kDim, tr("Das Plugin meldet keine Module.")));
        server_layout_->addWidget(l);
        server_labels_.push_back(l);
        return;
    }
    for (const server::ServerModule& m : report->modules) {
        auto* l = rich_label(server_list_);
        QString html = colored(server_color(m.state), QStringLiteral("●")) + QStringLiteral(" <b>") + esc(m.name) +
                       QStringLiteral("</b> – ") +
                       colored(server_color(m.state), qs(std::string(server::module_state_name(m.state))));
        if (!m.on) {
            html += colored(style::kDim, tr(" (ausgeschaltet)"));
        }
        if (!m.reason.empty()) {
            html += QStringLiteral("<br>") + esc(m.reason);
        }
        QString extra = esc(m.description);
        if (!m.needs.empty()) {
            QString needs;
            for (const std::string& n : m.needs) {
                needs += (needs.isEmpty() ? QString() : QStringLiteral(", ")) + esc(n);
            }
            extra += (extra.isEmpty() ? QString() : QStringLiteral(" · ")) + tr("braucht: %1").arg(needs);
        }
        if (!extra.isEmpty()) {
            html += QStringLiteral("<br>") + colored(style::kDim, extra);
        }
        l->setText(html);
        l->setToolTip(QStringLiteral("id: ") + qs(m.id));
        server_layout_->addWidget(l);
        server_labels_.push_back(l);
    }
}

QCheckBox* ModulesPage::host_switch(const std::string& id) const {
    for (const HostRow& r : host_rows_) {
        if (r.id == id) {
            return r.box;
        }
    }
    return nullptr;
}

// Reiner Text (ohne HTML), damit Tests nach sichtbaren Wörtern fragen – und ein maskiertes „<b>“ als Text sehen.
QString ModulesPage::host_text(const std::string& id) const {
    for (const HostRow& r : host_rows_) {
        if (r.id == id) {
            return r.box->text() + QStringLiteral("\n") + plain(r.state->text()) + QStringLiteral("\n") +
                   plain(r.description->text()) + QStringLiteral("\n") + r.box->toolTip();
        }
    }
    return {};
}

QString ModulesPage::server_text() const {
    QString out = server_head_->text();
    for (const QLabel* l : server_labels_) {
        out += QStringLiteral("\n") + plain(l->text());
    }
    return out;
}

}  // namespace swbh::gui
