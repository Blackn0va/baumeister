// Reiter „Module“ im Hauptfenster (Vertrag v012, AK-38): oben die eigenen Module der App als Schalter
// (gui/host_modules), darunter die Module des Minecraft-Servers, wie das Plugin sie zuletzt gemeldet hat
// (server/module_api) – nur Anzeige, geschaltet wird im Spiel mit /bm module.
//
// Die Zeilen der eigenen Module entstehen einmal und werden danach nur aktualisiert: Neu aufgebaut im 250-ms-Takt verlöre
// ein Häkchen Fokus und Mausklick mitten im Drücken. Geschaltet wird nur auf `clicked` (feuert bei setChecked nicht);
// zusätzlich setzt show_host das Häkchen unter QSignalBlocker, damit auch ein späterer Umbau auf `toggled` nicht bei
// jeder Aktualisierung schaltet. Die Server-Liste baut show_server nur bei neuer Meldung neu.
//
// Jeder Text vom Server geht maskiert (toHtmlEscaped) in die Rich-Text-Labels – modul_json ist zwar geprüft, aber ein
// „<b>“ oder „<img>“ im Namen soll nicht als HTML wirken (OWASP A03 sinngemäß).
//
// Threads: nur GUI-Thread. Fehler: keine (ein Umschalten meldet der Aufrufer über die Konsole).

#pragma once

#include "gui/host_modules.hpp"
#include "server/module_api.hpp"

#include <QWidget>

#include <functional>
#include <memory>
#include <string>
#include <vector>

class QCheckBox;
class QLabel;
class QVBoxLayout;

namespace swbh::gui {

class ModulesPage final : public QWidget {
    Q_OBJECT

public:
    // toggle: Nutzer hat ein eigenes Modul umgeschaltet (id, neuer Zustand). Der Aufrufer speichert und wendet an.
    using ToggleFn = std::function<void(const std::string& id, bool on)>;

    explicit ModulesPage(ToggleFn toggle, QWidget* parent = nullptr);

    void show_host(const std::vector<HostModule>& modules);
    // nullptr = das Plugin hat (noch) nichts gemeldet.
    void show_server(std::shared_ptr<const server::ModuleReport> report);

    // Für Tests und Bildschirmfotos.
    QCheckBox* host_switch(const std::string& id) const;
    QString host_text(const std::string& id) const;
    QString server_text() const;

private:
    struct HostRow {
        std::string id;
        QCheckBox* box = nullptr;
        QLabel* state = nullptr;
        QLabel* description = nullptr;
    };

    ToggleFn toggle_;
    QVBoxLayout* host_layout_ = nullptr;
    std::vector<HostRow> host_rows_;
    QLabel* server_head_ = nullptr;
    QWidget* server_list_ = nullptr;
    QVBoxLayout* server_layout_ = nullptr;
    std::vector<QLabel*> server_labels_;
};

}  // namespace swbh::gui
