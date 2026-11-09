// Einstellungen: Modell (Katalog oder eigene GGUF-Datei mit Warnhinweis), Rechenweg, Server, Tunnel, Autostart.
//
// „Mit Windows starten“ ist kein Feld von AppConfig, sondern der Run-Wert in der Registry (autostart.hpp). Der Dialog
// schreibt ihn nicht selbst: Er meldet über windows_start_change() nur, ob der Nutzer das Häkchen umgestellt hat, und
// das Hauptfenster schreibt erst nach „Speichern“.
//
// Fernverbindung (2026-11-08, „im Internet über eine Adresse (HTTPS)“): Adresse, ein oder zwei Fingerabdrücke des
// Servers und der eigene Fingerabdruck zum Kopieren. Den eigenen Schlüssel legt nicht der Dialog an, sondern der
// Rückruf create_key (Controller) – der Dialog kennt weder TPM noch Zertifikatsspeicher.
//
// Threads: nur GUI-Thread. Fehler: ungültige Eingaben verhindert das Formular (Bereiche der Drehfelder); Adresse und
// Fingerabdrücke der Fernverbindung prüft „Speichern“ (gui::relay_settings_problem) und bleibt mit Meldung offen; den
// plink-Pfad und das Ziel prüft der Tunnel beim Start (tunnel::validate) mit Meldung in der Konsole.

#pragma once

#include "gui/app_config.hpp"

#include <QDialog>

#include <functional>
#include <optional>
#include <string>
#include <vector>

class QCheckBox;
class QComboBox;
class QLabel;
class QLineEdit;
class QPushButton;
class QSpinBox;

namespace swbh::gui {

class SettingsDialog final : public QDialog {
    Q_OBJECT

public:
    // Legt den eigenen Schlüssel der Fernverbindung an; liefert seinen Pin oder nullopt + Grund.
    using CreateKeyFn = std::function<std::optional<std::string>(std::string& error)>;

    // windows_start: aktueller Stand von autostart_enabled() (Run-Wert und Task-Manager-Vermerk). own_pin: Pin des
    // eigenen Client-Zertifikats, leer = noch keiner.
    SettingsDialog(const AppConfig& config, bool has_password, bool windows_start, std::string own_pin,
                   CreateKeyFn create_key, QWidget* parent = nullptr);
    AppConfig result_config() const;
    // Neu eingegebenes Passwort; leer = unverändert lassen.
    std::string new_password() const;
    // Gewünschter Stand von „Mit Windows starten“, nur wenn der Nutzer das Häkchen umgestellt hat; sonst leer.
    std::optional<bool> windows_start_change() const;

protected:
    // „Speichern“: Felder der Fernverbindung prüfen, bei Fehler Meldung und offen bleiben.
    void accept() override;

private:
    void update_custom();
    void update_place();
    void show_own_pin(const std::string& pin);

    AppConfig base_;
    QComboBox* model_ = nullptr;
    QLineEdit* custom_ = nullptr;
    QLabel* custom_warn_ = nullptr;
    QWidget* custom_row_ = nullptr;
    QSpinBox* n_ctx_ = nullptr;
    QComboBox* compute_ = nullptr;
    QCheckBox* integrated_ = nullptr;
    QCheckBox* speculative_ = nullptr;
    QSpinBox* port_ = nullptr;
    QComboBox* place_ = nullptr;
    QComboBox* start_mode_ = nullptr;
    std::vector<QWidget*> remote_rows_;  // Zeilen, die nur für „anderer Rechner“ gelten
    std::vector<QWidget*> relay_rows_;   // Zeilen, die nur für „im Internet über eine Adresse“ gelten
    QLineEdit* relay_url_ = nullptr;
    QLineEdit* relay_pin1_ = nullptr;
    QLineEdit* relay_pin2_ = nullptr;
    QLineEdit* own_pin_ = nullptr;
    QPushButton* copy_pin_ = nullptr;
    QPushButton* make_key_ = nullptr;
    CreateKeyFn create_key_;
    QLineEdit* plink_ = nullptr;
    QLineEdit* server_host_ = nullptr;
    QLineEdit* ssh_user_ = nullptr;
    QSpinBox* ssh_port_ = nullptr;
    QSpinBox* remote_port_ = nullptr;
    QLineEdit* hostkey_ = nullptr;
    QComboBox* auth_ = nullptr;
    QLineEdit* password_ = nullptr;
    QCheckBox* windows_start_ = nullptr;
    QCheckBox* notifications_ = nullptr;
    QCheckBox* research_ = nullptr;
    QLineEdit* minecraft_name_ = nullptr;
    QCheckBox* speech_out_ = nullptr;
    QCheckBox* hear_others_ = nullptr;
    QComboBox* tts_voice_ = nullptr;
    QComboBox* voice_style_ = nullptr;
    QCheckBox* speech_in_ = nullptr;
    QComboBox* talk_key_ = nullptr;
    QComboBox* microphone_ = nullptr;
    QComboBox* talk_mode_ = nullptr;
    QSpinBox* mic_gain_ = nullptr;
    QCheckBox* auto_gain_ = nullptr;
    QCheckBox* speech_tones_ = nullptr;
    bool windows_start_before_ = false;
};

}  // namespace swbh::gui
