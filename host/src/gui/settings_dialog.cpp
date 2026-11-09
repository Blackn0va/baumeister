#include "gui/settings_dialog.hpp"

#include "gui/simple_mode.hpp"
#include "llm/models.hpp"
#include "relay/kanal.hpp"
#include "tunnel/tunnel.hpp"
#include "voice/microphone.hpp"
#include "voice/push_to_talk.hpp"
#include "voice/piper_catalog.hpp"
#include "voice/tts_sapi.hpp"
#include "voice/voice_out.hpp"

#include <QCheckBox>
#include <QClipboard>
#include <QComboBox>
#include <QDialogButtonBox>
#include <QFileDialog>
#include <QFormLayout>
#include <QGroupBox>
#include <QGuiApplication>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPushButton>
#include <QSpinBox>
#include <QTabWidget>
#include <QVBoxLayout>

#include <algorithm>
#include <utility>

namespace swbh::gui {

namespace {

QString qs(const std::string& s) { return QString::fromUtf8(s.data(), static_cast<qsizetype>(s.size())); }

}  // namespace

SettingsDialog::SettingsDialog(const AppConfig& config, bool has_password, bool windows_start, std::string own_pin,
                               CreateKeyFn create_key, QWidget* parent)
    : QDialog(parent), base_(config), create_key_(std::move(create_key)), windows_start_before_(windows_start) {
    setWindowTitle(tr("Einstellungen – Baumeister-Host"));
    auto* root = new QVBoxLayout(this);
    // Reiter statt einer langen Seite (Wunsch 2026-11-04). Mit den
    // Begleiter-Feldern war die Seite ~1 100 Pixel hoch – auf einem 1080er-Bildschirm samt Taskleiste lagen „Speichern“
    // und die letzten Felder außerhalb. Wer wieder alles auf eine Seite legt, bekommt das zurück.
    auto* tabs = new QTabWidget(this);
    const auto page = [tabs](const QString& title) {
        auto* w = new QWidget(tabs);
        auto* l = new QVBoxLayout(w);
        tabs->addTab(w, title);
        return l;
    };
    QVBoxLayout* ai_page = page(tr("Sprachmodell"));
    QVBoxLayout* net_page = page(tr("Server"));
    QVBoxLayout* buddy_page = page(tr("Begleiter"));
    QVBoxLayout* general_page = page(tr("Allgemein"));

    auto* model_box = new QGroupBox(tr("Sprachmodell"), this);
    auto* mf = new QFormLayout(model_box);
    model_ = new QComboBox(model_box);
    for (const llm::ModelSpec& m : llm::selectable_models()) {
        model_->addItem(tr("%1 – %2 GB").arg(qs(m.title)).arg(static_cast<double>(m.size) / 1e9, 0, 'f', 1), qs(m.key));
    }
    model_->addItem(tr("Eigene GGUF-Datei …"), QString::fromUtf8(llm::kCustomModelKey.data()));
    const int idx = model_->findData(qs(effective_model_key(config)));
    model_->setCurrentIndex(idx >= 0 ? idx : 0);
    mf->addRow(tr("Modell"), model_);

    custom_row_ = new QWidget(model_box);
    auto* cr = new QHBoxLayout(custom_row_);
    cr->setContentsMargins(0, 0, 0, 0);
    custom_ = new QLineEdit(qs(config.custom_model), custom_row_);
    auto* browse = new QPushButton(tr("Wählen …"), custom_row_);
    cr->addWidget(custom_, 1);
    cr->addWidget(browse);
    mf->addRow(tr("Datei"), custom_row_);
    custom_warn_ = new QLabel(tr("Eigene Dateien haben keine festgelegte Prüfsumme – die App kann nicht prüfen, ob die "
                                 "Datei unverändert ist. Nur Dateien aus vertrauenswürdiger Quelle wählen. Kontext und "
                                 "Grafikspeicher sind für eigene Dateien nur geschätzt."),
                              model_box);
    custom_warn_->setWordWrap(true);
    custom_warn_->setObjectName(QStringLiteral("muted"));
    mf->addRow(QString(), custom_warn_);
    n_ctx_ = new QSpinBox(model_box);
    // 0 = automatisch (kleinster Wert der Spinbox zeigt den Text). Schritt 2048: 0, 2048, 4096 … 32768.
    n_ctx_->setRange(0, 32768);
    n_ctx_->setSingleStep(2048);
    n_ctx_->setSpecialValueText(tr("Automatisch (nach Grafikspeicher)"));
    n_ctx_->setValue(config.n_ctx);
    n_ctx_->setToolTip(tr("Automatisch: der größte Kontext bis 32768, der samt Turbo-Entwurf in den freien "
                          "Grafikspeicher passt, mindestens 8192. Der Baumeister braucht mit Bauplatz-Scan ~5500 Token "
                          "Prompt, mit einer Reparaturrunde (vorige Antwort samt Fehler) bis über 10000."));
    mf->addRow(tr("Kontext (Token)"), n_ctx_);
    ai_page->addWidget(model_box);
    connect(model_, &QComboBox::currentIndexChanged, this, [this] { update_custom(); });
    connect(browse, &QPushButton::clicked, this, [this] {
        const QString f = QFileDialog::getOpenFileName(this, tr("GGUF-Datei wählen"), custom_->text(),
                                                       tr("GGUF-Modelle (*.gguf)"));
        if (!f.isEmpty()) {
            custom_->setText(QDir::toNativeSeparators(f));
        }
    });

    auto* compute_box = new QGroupBox(tr("Rechenweg"), this);
    auto* cf = new QFormLayout(compute_box);
    compute_ = new QComboBox(compute_box);
    compute_->addItem(tr("Automatisch (CUDA, wenn installiert, sonst Vulkan)"), QStringLiteral("auto"));
    compute_->addItem(tr("Vulkan"), QStringLiteral("vulkan"));
    compute_->addItem(tr("CUDA (NVIDIA)"), QStringLiteral("cuda"));
    compute_->setCurrentIndex(std::max(0, compute_->findData(qs(config.compute))));
    cf->addRow(tr("Rechnen mit"), compute_);
    integrated_ = new QCheckBox(tr("Integrierte Grafik erlauben (nur für Tests ohne Grafikkarte)"), compute_box);
    integrated_->setChecked(config.allow_integrated);
    cf->addRow(QString(), integrated_);
    speculative_ = new QCheckBox(tr("Turbo: spekulatives Dekodieren (Qwen2.5, lädt einmalig 0,5 GB Hilfsmodell)"),
                                 compute_box);
    speculative_->setChecked(config.speculative);
    speculative_->setToolTip(tr("Ein kleines Modell rät Token voraus, das große prüft mehrere auf einmal – gleiches "
                                "Ergebnis, schneller."));
    cf->addRow(QString(), speculative_);
    ai_page->addWidget(compute_box);

    // Wo läuft der Minecraft-Server? EINE Frage statt der Häkchen „Tunnel mitstarten“ und „lokaler Server“ an zwei
    // Stellen (Wunsch 2026-11-01). Anderer Rechner =
    // Tunnel, dieser PC = kein Tunnel (gui/simple_mode.hpp). Die SSH-Felder gelten nur für den anderen Rechner und sind
    // sonst ausgeblendet. Dritte Wahl seit 2026-11-08 (Wunsch): Fernverbindung über eine Adresse –
    // eine Wahl in derselben Frage, kein neues Häkchen (festgelegte Bedienung).
    auto* net_box = new QGroupBox(tr("Minecraft-Server"), this);
    auto* nf = new QFormLayout(net_box);
    place_ = new QComboBox(net_box);
    place_->addItem(tr("auf einem anderen Rechner (Verbindung per SSH-Tunnel)"));
    place_->addItem(tr("auf diesem PC (kein Tunnel nötig)"));
    place_->addItem(tr("im Internet über eine Adresse (HTTPS)"));
    switch (server_place(config)) {
    case ServerPlace::Remote: place_->setCurrentIndex(0); break;
    case ServerPlace::ThisPc: place_->setCurrentIndex(1); break;
    case ServerPlace::Internet: place_->setCurrentIndex(2); break;
    }
    nf->addRow(tr("Der Server läuft"), place_);
    server_host_ = new QLineEdit(qs(target_host(config.target)), net_box);
    server_host_->setPlaceholderText(tr("z. B. 192.168.1.20 oder mc.example.org"));
    nf->addRow(tr("Server-Adresse"), server_host_);
    ssh_user_ = new QLineEdit(qs(target_user(config.target)), net_box);
    ssh_user_->setPlaceholderText(tr("SSH-Benutzer auf dem Server, z. B. minecraft"));
    nf->addRow(tr("Benutzer"), ssh_user_);
    auth_ = new QComboBox(net_box);
    auth_->addItem(tr("Schlüssel aus Pageant (empfohlen)"), QStringLiteral("pageant"));
    auth_->addItem(tr("Passwort"), QStringLiteral("passwort"));
    auth_->setCurrentIndex(std::max(0, auth_->findData(qs(config.tunnel_auth))));
    nf->addRow(tr("Anmeldung"), auth_);
    password_ = new QLineEdit(net_box);
    password_->setEchoMode(QLineEdit::Password);
    password_->setPlaceholderText(has_password ? tr("gespeichert – leer lassen = unverändert") : tr("Passwort des SSH-Benutzers"));
    nf->addRow(tr("Passwort"), password_);
    ssh_port_ = new QSpinBox(net_box);
    ssh_port_->setRange(1, 65535);
    ssh_port_->setValue(config.ssh_port);
    nf->addRow(tr("SSH-Port"), ssh_port_);
    hostkey_ = new QLineEdit(qs(config.hostkey), net_box);
    hostkey_->setPlaceholderText(tr("optional, z. B. SHA256:… – sonst einmal mit PuTTY verbinden"));
    nf->addRow(tr("Hostschlüssel"), hostkey_);
    plink_ = new QLineEdit(qs(effective_plink(config)), net_box);
    nf->addRow(tr("plink.exe"), plink_);
    remote_port_ = new QSpinBox(net_box);
    remote_port_->setRange(1, 65535);
    remote_port_->setValue(config.remote_port);
    remote_port_->setToolTip(tr("Port, den das Plugin anspricht – muss zu llm.url in dessen config.yml passen "
                                "(Vorgabe 18080). Gilt für beide Fälle."));
    nf->addRow(tr("Port für das Plugin"), remote_port_);
    port_ = new QSpinBox(net_box);
    port_->setRange(1024, 65535);
    port_->setValue(config.server_port);
    port_->setToolTip(tr("Interner Port der App auf 127.0.0.1 – nur ändern, wenn 8080 schon belegt ist."));
    nf->addRow(tr("Interner Port"), port_);
    auto* hint = new QLabel(tr("Empfohlen ist der Schlüssel aus Pageant (PuTTY): Pageant muss laufen und den Schlüssel "
                               "geladen haben. Ein Passwort speichert die App verschlüsselt (Windows-DPAPI, nur dieses "
                               "Windows-Konto) und gibt es plink über eine kurzlebige Datei, nie über die Befehlszeile."),
                            net_box);
    hint->setWordWrap(true);
    hint->setObjectName(QStringLiteral("muted"));
    nf->addRow(QString(), hint);
    remote_rows_ = {server_host_, ssh_user_, auth_, password_, ssh_port_, hostkey_, plink_, hint};

    relay_url_ = new QLineEdit(qs(config.relay_url), net_box);
    relay_url_->setPlaceholderText(qs(std::string(relay::kDefaultRelayUrl)));
    relay_url_->setToolTip(tr("Adresse des Relays auf dem Minecraft-Server: wss://<server>:<port>/kanal/v1"));
    nf->addRow(tr("Adresse"), relay_url_);
    relay_pin1_ = new QLineEdit(qs(config.relay_pins.empty() ? std::string() : config.relay_pins[0]), net_box);
    relay_pin1_->setPlaceholderText(tr("sha256:… – zeigt das Relay mit --fingerabdruck"));
    nf->addRow(tr("Fingerabdruck Server"), relay_pin1_);
    relay_pin2_ = new QLineEdit(qs(config.relay_pins.size() > 1 ? config.relay_pins[1] : std::string()), net_box);
    relay_pin2_->setPlaceholderText(tr("optional – nur für einen Schlüsselwechsel des Servers"));
    nf->addRow(tr("Zweiter Fingerabdruck"), relay_pin2_);
    auto* own_row = new QWidget(net_box);
    auto* orl = new QHBoxLayout(own_row);
    orl->setContentsMargins(0, 0, 0, 0);
    own_pin_ = new QLineEdit(own_row);
    own_pin_->setReadOnly(true);
    own_pin_->setPlaceholderText(tr("noch kein eigener Schlüssel"));
    copy_pin_ = new QPushButton(tr("Kopieren"), own_row);
    make_key_ = new QPushButton(tr("Schlüssel erzeugen"), own_row);
    make_key_->setToolTip(tr("Legt einen nicht exportierbaren Schlüssel an (im TPM, sonst im Schlüsselspeicher von "
                             "Windows) und zeigt seinen Fingerabdruck."));
    orl->addWidget(own_pin_, 1);
    orl->addWidget(copy_pin_);
    orl->addWidget(make_key_);
    nf->addRow(tr("Eigener Fingerabdruck"), own_row);
    auto* relay_hint = new QLabel(tr("Die App verbindet sich verschlüsselt mit dem Relay auf dem Minecraft-Server und "
                                     "erkennt es nur an seinem Fingerabdruck – ohne ihn verbindet sie sich nicht. Den "
                                     "eigenen Fingerabdruck trägst du auf dem Server für diese App ein."),
                                  net_box);
    relay_hint->setWordWrap(true);
    relay_hint->setObjectName(QStringLiteral("muted"));
    nf->addRow(QString(), relay_hint);
    relay_rows_ = {relay_url_, relay_pin1_, relay_pin2_, own_row, relay_hint};
    show_own_pin(own_pin);
    connect(copy_pin_, &QPushButton::clicked, this, [this] {
        QGuiApplication::clipboard()->setText(own_pin_->text());
    });
    connect(make_key_, &QPushButton::clicked, this, [this] {
        if (!create_key_) {
            return;
        }
        std::string error;
        QGuiApplication::setOverrideCursor(Qt::WaitCursor);
        const std::optional<std::string> pin = create_key_(error);
        QGuiApplication::restoreOverrideCursor();
        if (!pin) {
            QMessageBox::warning(this, tr("Eigener Schlüssel"), tr("Schlüssel nicht anlegbar: %1").arg(qs(error)));
            return;
        }
        show_own_pin(*pin);
    });
    connect(place_, &QComboBox::currentIndexChanged, this, [this] { update_place(); });
    connect(auth_, &QComboBox::currentIndexChanged, this, [this] { update_place(); });
    net_page->addWidget(net_box);

    auto* start_box = new QGroupBox(tr("Start"), this);
    auto* sf = new QFormLayout(start_box);
    start_mode_ = new QComboBox(start_box);
    start_mode_->addItem(tr("verbinden und KI einschalten"));
    start_mode_->addItem(tr("nur verbinden (ohne KI – Stadt- und Weltbau, GPU bleibt frei)"));
    start_mode_->addItem(tr("nichts – ich starte von Hand"));
    switch (start_mode(config)) {
    case StartMode::ConnectWithAi: start_mode_->setCurrentIndex(0); break;
    case StartMode::ConnectOnly: start_mode_->setCurrentIndex(1); break;
    case StartMode::Nothing: start_mode_->setCurrentIndex(2); break;
    }
    sf->addRow(tr("Beim Programmstart"), start_mode_);
    general_page->addWidget(start_box);
    windows_start_ = new QCheckBox(tr("Mit Windows starten (nach der Anmeldung still im Infobereich)"), this);
    windows_start_->setChecked(windows_start);
    windows_start_->setToolTip(tr("Trägt den Baumeister-Host in den Autostart dieses Windows-Kontos ein. Im "
                                  "Task-Manager (Reiter „Autostart“) abschaltbar; das Häkchen zeigt das mit an."));
    general_page->addWidget(windows_start_);
    notifications_ = new QCheckBox(tr("Meldungen im Infobereich: Tunnel weg/wieder da, Modell bereit, Bauplan fertig"),
                                   this);
    notifications_->setChecked(config.notifications);
    notifications_->setToolTip(tr("Windows-Meldungen, solange das Fenster nicht im Vordergrund ist. Fehler beim "
                                  "verborgenen Fenster (Download, Laden, Server, Tunnel) meldet die App immer."));
    general_page->addWidget(notifications_);
    research_ = new QCheckBox(tr("Internet-Recherche erlauben: echte Bauwerke vor dem Planen bei Wikipedia/Wikidata "
                                 "nachschlagen"),
                              this);
    research_->setChecked(config.research);
    research_->setToolTip(tr("„bau den Kölner Dom“: Das Plugin holt Maße, Baustil und Material und gibt sie dem "
                             "Modell mit. Aus: keine einzige Abfrage ins Internet, das Modell plant aus dem Gedächtnis. "
                             "Wirkt sofort, ohne Neustart."));
    general_page->addWidget(research_);

    // Begleiter (2026-11-04): Wer bin ich im Spiel, und soll die App die Antworten vorlesen?
    auto* buddy_box = new QGroupBox(tr("Begleiter"), this);
    auto* bf = new QFormLayout(buddy_box);
    minecraft_name_ = new QLineEdit(qs(config.minecraft_name), buddy_box);
    minecraft_name_->setPlaceholderText(tr("z. B. Blackkn0va"));
    minecraft_name_->setMaxLength(16);
    minecraft_name_->setToolTip(tr("Dein Name im Spiel. Vorgelesen wird nur, was dein Begleiter zu dir sagt."));
    bf->addRow(tr("Dein Minecraft-Name"), minecraft_name_);
    speech_out_ = new QCheckBox(tr("Antworten des Begleiters vorlesen"), buddy_box);
    speech_out_->setChecked(config.speech_out);
    bf->addRow(QString(), speech_out_);
    // Natürliche Stimmen (Piper, wie in kraken) zuerst – Wunsch 2026-11-04. Die Windows-Stimmen
    // bleiben wählbar und springen ein, solange Piper lädt.
    tts_voice_ = new QComboBox(buddy_box);
    for (const voice::VoiceSpec& v : voice::piper_voices()) {
        tts_voice_->addItem(tr("%1 – natürlich (lädt einmalig ~%2 MB)")
                                .arg(qs(v.title))
                                .arg((v.size + voice::piper_spec().size) / 1000000),
                            QStringLiteral("piper:") + qs(v.key));
    }
    for (const std::string& v : voice::Speaker::voices()) {
        tts_voice_->addItem(tr("%1 – Windows-Stimme").arg(qs(v)), QStringLiteral("sapi:") + qs(v));
    }
    const voice::VoiceChoice chosen = voice::parse_voice_choice(config.tts_voice);
    const QString chosen_key = (chosen.piper ? QStringLiteral("piper:") : QStringLiteral("sapi:")) + qs(chosen.key);
    if (const int at = tts_voice_->findData(chosen_key); at >= 0) {
        tts_voice_->setCurrentIndex(at);
    }
    tts_voice_->setToolTip(tr("Piper: Programm MIT, das mitgelieferte espeak-ng GPL-3.0 – läuft als eigener Prozess. "
                              "Stimmen: Lizenz je Datensatz (Thorsten CC0). Geladen von github.com und "
                              "huggingface.co, SHA-256 geprüft."));
    bf->addRow(tr("Stimme"), tts_voice_);
    // Tonlage (2026-11-06, „eher wie ein Cartoon“): nur für die natürlichen Stimmen, gemessen in voice_out.hpp.
    voice_style_ = new QComboBox(buddy_box);
    voice_style_->addItem(tr("Normal"), QStringLiteral("normal"));
    voice_style_->addItem(tr("Fröhlich (etwas höher)"), QStringLiteral("fröhlich"));
    voice_style_->addItem(tr("Cartoon (empfohlen)"), QStringLiteral("cartoon"));
    voice_style_->addItem(tr("Extra Cartoon"), QStringLiteral("extra"));
    if (const int at = voice_style_->findData(qs(config.voice_style)); at >= 0) {
        voice_style_->setCurrentIndex(at);
    }
    voice_style_->setToolTip(tr("Wie hoch und quirlig der Begleiter klingt. Gilt für die natürlichen Stimmen."));
    bf->addRow(tr("Tonlage"), voice_style_);
    speech_in_ = new QCheckBox(tr("Mit dem Begleiter sprechen (Taste halten; lädt einmalig 574 MB Spracherkennung)"),
                               buddy_box);
    speech_in_->setChecked(config.speech_in);
    speech_in_->setToolTip(tr("Solange die Taste gedrückt ist, nimmt die App das Mikrofon auf; beim Loslassen erkennt "
                              "sie den Satz hier auf diesem PC und schickt nur den Text an deinen Begleiter."));
    bf->addRow(QString(), speech_in_);
    hear_others_ = new QCheckBox(tr("Andere Spieler verstehen (Simple Voice Chat)"), buddy_box);
    hear_others_->setChecked(config.hear_others);
    hear_others_->setToolTip(tr("Spricht ein Mitspieler im Spiel Vera an, erkennt diese App seinen Satz hier auf "
                                "diesem PC. Aus: nur deine eigene Stimme."));
    bf->addRow(QString(), hear_others_);
    talk_key_ = new QComboBox(buddy_box);
    for (const char* k : {"maus4", "maus5", "mausmitte", "v", "b", "f8", "f9"}) {
        const auto parsed = voice::parse_talk_key(k);
        talk_key_->addItem(qs(voice::talk_key_title(*parsed)), QString::fromLatin1(k));
    }
    if (const int at = talk_key_->findData(qs(config.talk_key)); at >= 0) {
        talk_key_->setCurrentIndex(at);
    }
    bf->addRow(tr("Taste zum Sprechen"), talk_key_);
    talk_mode_ = new QComboBox(buddy_box);
    talk_mode_->addItem(tr("Halten – aufnehmen, solange die Taste gedrückt ist"), QStringLiteral("halten"));
    talk_mode_->addItem(tr("Umschalten – einmal drücken, endet bei Stille von selbst"), QStringLiteral("umschalten"));
    talk_mode_->setCurrentIndex(std::max(0, talk_mode_->findData(qs(config.talk_mode))));
    bf->addRow(tr("Bedienung"), talk_mode_);
    microphone_ = new QComboBox(buddy_box);
    microphone_->addItem(tr("Windows-Standard"), QString());
    for (const voice::InputDevice& d : voice::input_devices()) {
        microphone_->addItem(qs(d.name), qs(d.name));
    }
    if (const int at = microphone_->findData(qs(config.microphone)); at >= 0) {
        microphone_->setCurrentIndex(at);
    }
    bf->addRow(tr("Mikrofon"), microphone_);
    mic_gain_ = new QSpinBox(buddy_box);
    mic_gain_->setRange(-20, 30);
    mic_gain_->setSuffix(tr(" dB"));
    mic_gain_->setValue(config.mic_gain_db);
    mic_gain_->setToolTip(tr("Verstärkung von Hand vor allem anderen – für sehr leise oder sehr laute Mikrofone. Den "
                             "Pegel zeigt der Mikrofontest im Hauptfenster."));
    bf->addRow(tr("Verstärkung"), mic_gain_);
    auto_gain_ = new QCheckBox(tr("Automatische Verstärkung (gleicht leise und laute Aufnahmen auf −20 dBFS an)"),
                               buddy_box);
    auto_gain_->setChecked(config.auto_gain);
    bf->addRow(QString(), auto_gain_);
    speech_tones_ = new QCheckBox(tr("Signaltöne bei Beginn und Ende der Aufnahme"), buddy_box);
    speech_tones_->setChecked(config.speech_tones);
    bf->addRow(QString(), speech_tones_);
    buddy_page->addWidget(buddy_box);
    for (QVBoxLayout* l : {ai_page, net_page, buddy_page, general_page}) {
        l->addStretch(1);
    }
    root->addWidget(tabs, 1);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Save | QDialogButtonBox::Cancel, this);
    buttons->button(QDialogButtonBox::Save)->setText(tr("Speichern"));
    buttons->button(QDialogButtonBox::Cancel)->setText(tr("Abbrechen"));
    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    root->addWidget(buttons);
    update_custom();
    update_place();
}

void SettingsDialog::show_own_pin(const std::string& pin) {
    own_pin_->setText(qs(pin));
    copy_pin_->setEnabled(!pin.empty());
    make_key_->setVisible(pin.empty());
}

void SettingsDialog::accept() {
    if (place_->currentIndex() == 2) {
        if (const std::optional<std::string> problem =
                relay_settings_problem(relay_url_->text().trimmed().toStdString(),
                                       relay_pin1_->text().toStdString(), relay_pin2_->text().toStdString())) {
            QMessageBox::warning(this, tr("Fernverbindung"), qs(*problem));
            return;
        }
    }
    QDialog::accept();
}

void SettingsDialog::update_place() {
    const bool remote = place_->currentIndex() == 0;
    const bool internet = place_->currentIndex() == 2;
    for (QWidget* w : remote_rows_) {
        if (auto* form = qobject_cast<QFormLayout*>(w->parentWidget()->layout())) {
            // Ganze Zeilen ausblenden statt ausgrauen: Wer den Server auf diesem PC hat, braucht keine SSH-Felder.
            form->setRowVisible(w, remote);
        }
    }
    for (QWidget* w : relay_rows_) {
        if (auto* form = qobject_cast<QFormLayout*>(w->parentWidget()->layout())) {
            form->setRowVisible(w, internet);
        }
    }
    password_->setEnabled(auth_->currentData().toString() == QStringLiteral("passwort"));
}

void SettingsDialog::update_custom() {
    const bool custom = model_->currentData().toString() == QString::fromUtf8(llm::kCustomModelKey.data());
    // Ganze Formularzeilen samt Beschriftung ein-/ausblenden – setVisible am Feld ließ „Datei“ allein stehen.
    if (auto* form = qobject_cast<QFormLayout*>(custom_row_->parentWidget()->layout())) {
        form->setRowVisible(custom_row_, custom);
        form->setRowVisible(custom_warn_, custom);
    }
}

AppConfig SettingsDialog::result_config() const {
    AppConfig c = base_;
    c.model_key = model_->currentData().toString().toStdString();
    c.custom_model = custom_->text().trimmed().toStdString();
    // Zwischen 1 und 2047 nur durch Eintippen erreichbar – die Engine braucht mindestens 2048.
    c.n_ctx = n_ctx_->value() == 0 ? 0 : std::max(2048, n_ctx_->value());
    c.compute = compute_->currentData().toString().toStdString();
    c.allow_integrated = integrated_->isChecked();
    c.speculative = speculative_->isChecked();
    c.server_port = port_->value();
    apply_server_place(c, place_->currentIndex() == 1   ? ServerPlace::ThisPc
                          : place_->currentIndex() == 2 ? ServerPlace::Internet
                                                        : ServerPlace::Remote);
    // Eine ungültige Adresse (nur möglich, wenn „im Internet“ nicht gewählt ist – sonst hält accept() an) nicht
    // speichern: die alte bleibt.
    const std::string url = relay_url_->text().trimmed().toStdString();
    c.relay_url = relay::relay_url_allowed(url) ? url : base_.relay_url;
    c.relay_pins = relay_pins_from(relay_pin1_->text().toStdString(), relay_pin2_->text().toStdString());
    const std::string plink = plink_->text().trimmed().toStdString();
    c.plink = plink == tunnel::kDefaultPlink ? std::string() : plink;
    c.target = make_target(ssh_user_->text().trimmed().toStdString(), server_host_->text().trimmed().toStdString());
    c.ssh_port = ssh_port_->value();
    c.remote_port = remote_port_->value();
    c.hostkey = hostkey_->text().trimmed().toStdString();
    c.tunnel_auth = auth_->currentData().toString().toStdString();
    apply_start_mode(c, start_mode_->currentIndex() == 0   ? StartMode::ConnectWithAi
                        : start_mode_->currentIndex() == 1 ? StartMode::ConnectOnly
                                                           : StartMode::Nothing);
    c.notifications = notifications_->isChecked();
    c.research = research_->isChecked();
    c.minecraft_name = minecraft_name_->text().trimmed().toStdString();
    c.speech_out = speech_out_->isChecked();
    c.tts_voice = tts_voice_->currentData().toString().toStdString();
    c.voice_style = voice_style_->currentData().toString().toStdString();
    c.speech_in = speech_in_->isChecked();
    c.hear_others = hear_others_->isChecked();
    c.talk_key = talk_key_->currentData().toString().toStdString();
    c.microphone = microphone_->currentData().toString().toStdString();
    c.talk_mode = talk_mode_->currentData().toString().toStdString();
    c.mic_gain_db = mic_gain_->value();
    c.auto_gain = auto_gain_->isChecked();
    c.speech_tones = speech_tones_->isChecked();
    return c;
}

std::string SettingsDialog::new_password() const {
    if (auth_->currentData().toString() != QStringLiteral("passwort")) {
        return {};
    }
    return password_->text().toStdString();
}

std::optional<bool> SettingsDialog::windows_start_change() const {
    const bool now = windows_start_->isChecked();
    if (now == windows_start_before_) {
        return std::nullopt;
    }
    return now;
}

}  // namespace swbh::gui
