// Ablaufsteuerung der App: Modell laden/herunterladen, CUDA-Kern, Server, Tunnel bzw. Fernverbindung, Live-Konsole.
//
// Die Oberfläche fragt den Zustand alle 250 ms über snapshot() ab und holt neue Konsolen-Einträge über
// drain_console(). Warum Abfragen statt Signale: Der Token-Strom kommt mit 30–90 Token/s aus dem Server-Thread; je
// Token ein Qt-Signal in die Ereignisschleife zu stellen, wäre unnötig, gesammelt alle 250 ms reicht fürs Auge.
//
// Threads: Öffentliche Funktionen aus dem GUI-Thread. Laden, Download und CUDA-Installation laufen in je einem
// Arbeitsthread; die Engine schützt engine_mutex_ (complete() aus dem Server-Thread, load() aus dem Lade-Thread).
// Fehler: kommen als Konsolen-Eintrag und im Zustand, nie als Ausnahme in den GUI-Thread.

#pragma once

#include "base/paths.hpp"
#include "gui/app_config.hpp"
#include "gui/companion_wiring.hpp"
#include "gui/console_throttle.hpp"
#include "gui/job_stats.hpp"
#include "gui/job_tracker.hpp"
#include "gui/plan_view.hpp"
#include "llm/engine.hpp"
#include "relay/relay_link.hpp"
#include "server/chat_api.hpp"
#include "server/city_api.hpp"
#include "server/companion_api.hpp"
#include "server/research_api.hpp"
#include "server/result_api.hpp"
#include "server/http_server.hpp"
#include "tunnel/tunnel.hpp"
#include "voice/speech_input.hpp"
#include "voice/tts_sapi.hpp"
#include "voice/voice_out.hpp"
#include "win/client_cert.hpp"
#include "win/http.hpp"

#include <QObject>

#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace swbh::gui {

enum class ConsoleKind { Info, Warn, Error, Request, Token, Done, Tunnel };

struct ConsoleEntry {
    ConsoleKind kind = ConsoleKind::Info;
    std::string text;
};

enum class ModelPhase { None, Loading, Loaded, Failed };

struct Snapshot {
    ModelPhase model = ModelPhase::None;
    std::string model_title;
    std::string model_detail;   // Name laut GGUF, Kontext, KV
    std::string device;
    std::string backend;
    std::uint64_t vram_total = 0;
    std::uint64_t vram_free = 0;
    std::vector<llm::GpuDevice> gpus;
    std::string cuda_state;     // deutsch
    bool server_running = false;
    int server_port = 0;
    bool local_running = false;  // zweiter Lauscher für einen Minecraft-Server auf diesem PC
    int local_port = 0;
    bool busy = false;
    // Zustand der Verbindung zum Minecraft-Server: SSH-Tunnel ODER Fernverbindung (relay_enabled) – beide melden
    // dieselben Zustände, die Karte „VERBINDUNG“, der Satz über den Schaltern und die Meldungen bleiben gleich.
    tunnel::State tunnel = tunnel::State::Stopped;
    std::string tunnel_text;
    bool tunnel_enabled = true;
    bool relay_enabled = false;
    bool has_last = false;
    bool last_ok = false;
    std::string last_error;
    double last_seconds = 0.0;
    int last_tokens = 0;
    double last_tps = 0.0;
    double last_prompt_seconds = 0.0;
    int last_reused = 0;
    int last_prompt_tokens = 0;
    JobStats stats;  // alle Aufträge seit dem Start (Karte „Letzter Auftrag“)
    std::string last_warning;   // Tempo- oder Speicherwarnung zum letzten Auftrag, leer = unauffällig
    std::string last_research;  // „Kölner Dom – 157 m, 1:1“, leer = noch nichts nachgeschlagen
    std::string last_result;    // Ergebnis im Spiel (server::result_line_de), leer = noch keine Meldung
    bool last_result_failed = false;
    std::string model_hint;  // model_size_hint_de zum geladenen Modell, leer = passt
    // Zählt absichtliche Tunnel-Stopps (stop_tunnel). Die Einstellungen stoppen und starten den Tunnel im selben
    // GUI-Takt – die Abfrage sah nie „Stopped“, sondern Connected → Starting, und der Notifier meldete „Tunnel
    // getrennt“ und danach „wieder da“ (Prüfbefund 2026-10-29). Ein Wechsel hier gilt dem Notifier als „Stopped“.
    std::uint64_t tunnel_generation = 0;
    bool downloading = false;
    std::string download_title;
    std::uint64_t download_done = 0;
    std::uint64_t download_total = 0;
};

class Controller final : public QObject,
                         private server::CompletionBackend,
                         private server::ApiObserver,
                         private CompanionHooks {
    Q_OBJECT

public:
    Controller(AppPaths paths, AppConfig config, QObject* parent = nullptr);
    ~Controller() override;

    const AppPaths& paths() const { return paths_; }
    AppConfig config() const;
    void set_config(const AppConfig& config);

    Snapshot snapshot() const;
    // Nur für --screenshot: letzter Auftrag und Recherche wie in einem echten Lauf (Werte aus dem Aufrufer).
    void show_demo_job(const llm::CompletionResult& r, double warm_prompt_tps, const std::string& research);
    // Der zuletzt beendete Auftrag samt Antworttext für Plan-Vorschau und Benachrichtigung. Eigene Abfrage statt Feld im
    // Snapshot: Der Plan ist bis zu einigen KiB groß, der Snapshot wird alle 250 ms kopiert. Die Oberfläche fragt
    // last_plan_seq() in jeder Abfrage und last_plan() nur, wenn sich die Nummer geändert hat.
    PlanRecord last_plan() const;
    std::uint64_t last_plan_seq() const;
    // Reiter „Verlauf“ (gui/job_tracker): Kopie nur bei neuer rev holen, wie beim Plan.
    std::deque<HistoryEntry> history() const;
    std::uint64_t history_rev() const;
    // Erkannter Satz vom Mikrofon an den Begleiter (Long-Poll des Plugins holt ihn ab). Ohne Minecraft-Name in den
    // Einstellungen verworfen – das Plugin wüsste sonst nicht, welcher Spieler gesprochen hat.
    void companion_heard(std::string text);
    // Sprechen nach der Konfiguration ein-, aus- oder umschalten (Start, Einstellungen gespeichert). Fehlt das
    // Erkennungsmodell, lädt es zuerst (574 MB, SHA-256 geprüft) und schaltet danach ein.
    void apply_speech();
    // Zustand des Sprechens für die Karte „Begleiter“ (Pegel, zuletzt gehört). Aus dem GUI-Takt.
    voice::SpeechStatus speech_status() const { return speech_.status(); }
    // Mikrofontest: 4 s aufnehmen, vorspielen, erkennen – Ergebnis in der Konsole. false = Sprechen ist aus.
    bool test_microphone();
    // Nur für --screenshot: Ergebnis im Spiel wie aus dem Plugin (/baumeister/v1/ergebnis).
    void show_demo_result(const server::ResultNote& note);
    // Stadtansicht: Schnappschuss des zuletzt gemeldeten Stadt-/Weltbaus (nullptr = keiner) und seine Änderungsnummer –
    // wie beim Plan holt die Oberfläche den Schnappschuss nur bei neuer Nummer.
    std::shared_ptr<const server::CityView> city() const { return city_.snapshot(); }
    std::uint64_t city_seq() const { return city_.seq(); }
    std::vector<ConsoleEntry> drain_console();

    void load_model();
    void unload_model();
    void download_model();
    void cancel_download();
    bool model_file_present() const;
    bool model_present(const std::string& key, const std::string& custom_path) const;
    // Modell wählen und gleich nutzen (Wunsch 2026-10-28): Auswahl speichern,
    // laufendes Modell entladen, vorhandene Datei laden, fehlende erst herunterladen und danach laden. false, solange
    // ein Laden oder Download läuft (dann bleibt alles, wie es ist).
    bool select_model(const std::string& key, const std::string& custom_path);
    bool model_busy() const { return loading_ || download_busy_; }

    // CUDA: Zustand und Installation (terms_accepted nach dem Zustimmungsdialog).
    std::string cuda_status_text() const;
    bool cuda_installed() const;
    void install_cuda(bool terms_accepted);

    bool start_server();
    // Zweiten Lauscher starten, falls eingeschaltet und noch nicht aktiv (start_server ruft das mit).
    void start_local_server();
    void stop_server();
    bool start_tunnel();
    // Tunnel-Passwort (Anmeldung „passwort“): DPAPI-verschlüsselt unter {data}\tunnel-passwort.dpapi, nie im Log.
    // Leeres Passwort löscht die Datei.
    bool has_tunnel_password() const;
    bool store_tunnel_password(const std::string& password);
    void stop_tunnel();
    // Verbindung nach „Der Server läuft …“: SSH-Tunnel, Fernverbindung (HTTPS) oder – auf diesem PC – keine.
    bool start_link();
    void stop_link();
    bool start_relay();
    void stop_relay();
    // Eigener Client-Schlüssel der Fernverbindung (win/client_cert.hpp): Pin des vorhandenen Zertifikats; nullopt =
    // noch keiner (oder nicht mehr greifbar, z. B. anderes Windows-Konto).
    std::optional<std::string> own_client_pin() const;
    // Legt ihn an, falls er fehlt (TPM, sonst Software-Schlüsselspeicher), und merkt den Pin in den Einstellungen.
    std::optional<std::string> create_client_pin(std::string& error);

    void start_all();
    // Server und Tunnel ohne Modell (Stadt-/Weltbau ohne KI).
    void connect_only();
    // „KI einschalten“: Modell laden, fehlt die Datei, erst herunterladen (danach lädt es von selbst).
    void enable_ai() { ensure_model(); }
    void stop_all();
    void shutdown();

    void console(ConsoleKind kind, std::string text);

private:
    // CompanionHooks (Server-Thread, Verdrahtung in gui/companion_wiring)
    // Satz des Begleiters: Konsole, und vorlesen, wenn er dem eingetragenen Spieler gilt.
    void companion_said(const server::Spoken& s) override;
    // Ton aus dem Spiel erkennen (/hoeren) und Stimme als Ton fürs Spiel (/stimme).
    void game_voice(const std::vector<std::string>& names) override;
    std::optional<std::string> hear_from_game(const std::string& player, std::vector<float> pcm,
                                              std::string& error) override;
    std::optional<std::vector<std::int16_t>> voice_for_game(const std::string& text, std::string& error) override;
    // Begleitername laut Plugin: abgelegt und gedrosselt hat ihn schon forward_companion_name, hier nur die Zeile.
    void companion_name_line(const std::string& line) override;
    // Stimme nach der Einstellung einrichten; fehlt Piper oder die Stimme, im Hintergrund laden.
    void apply_voice();
    // Ergebnis im Spiel in Karte, Verlauf und Konsole (aus route und show_demo_result).
    void show_result(const server::ResultNote& n);
    // CompletionBackend (Server-Thread)
    bool ready() const override;
    std::string model_name() const override;
    std::optional<llm::CompletionResult> complete(const llm::CompletionRequest& request, std::string& error) override;
    void cancel() override;
    // ApiObserver (Server-Thread)
    void request_started(const server::RequestInfo& info) override;
    void token(std::string_view piece) override;
    void request_finished(const server::FinishInfo& info) override;

    std::pair<std::string, bool> prepare_backend();
    // Fernverbindung: Verbindungsaufbau mit eigenem Zertifikat (Verbindungs-Thread des Links).
    relay::RelayLink::Options relay_options();
    std::shared_ptr<const win::ClientCert> ensure_client_cert(std::string& error);
    void ensure_model();
    // Vorwärmen (Lade-Thread, nach „Modell bereit“): prefix.json lesen und engine_.prewarm() unter engine_mutex_.
    void prewarm_prefix();
    // Server-Thread nach einem erfolgreichen Auftrag: führende system-Nachrichten in prefix.json, wenn sie sich
    // geändert haben.
    void remember_prefix(const std::vector<llm::ChatMessage>& messages);
    std::filesystem::path prefix_file() const;
    std::filesystem::path password_file() const;
    std::optional<std::string> tunnel_password() const;
    void join_worker(std::thread& t);
    server::ApiResponse route(const server::HttpRequest& r, const std::function<bool()>& gone);
    void server_log(const char* who, const std::string& line);

    AppPaths paths_;
    mutable std::mutex mutex_;  // config_, snap_, console_, prefix_saved_, jobs_
    AppConfig config_;
    Snapshot snap_;
    std::deque<ConsoleEntry> console_;
    std::size_t console_dropped_ = 0;
    // Inhalt von prefix.json, soweit bekannt – damit nicht jeder Auftrag die Datei neu schreibt.
    std::vector<llm::ChatMessage> prefix_saved_;
    JobTracker jobs_;

    std::mutex engine_mutex_;
    llm::LlamaEngine engine_;
    std::atomic<bool> loaded_{false};
    std::atomic<bool> loading_{false};
    bool components_cleaned_ = false;

    server::ChatApi api_;
    server::CityState city_;
    server::HttpServer server_;
    // Zweiter Lauscher für einen Minecraft-Server auf diesem PC (AppConfig::local_server), gleicher Handler.
    server::HttpServer local_server_;
    tunnel::Tunnel tunnel_;
    // Eigenes Client-Zertifikat (einmal gesucht bzw. angelegt) – unter cert_mutex_, der nie unter mutex_ genommen wird.
    mutable std::mutex cert_mutex_;
    std::shared_ptr<const win::ClientCert> client_cert_;
    relay::RelayLink relay_;
    // Begleiter: erkannte Sätze bis zur Abholung; Stimme (Piper, Rückfall Windows-Stimme) für Vorlesen und Spiel.
    server::HeardQueue heard_;
    voice::VoiceOut voice_;
    std::string voice_choice_;  // Einstellung, mit der voice_ eingerichtet ist (unter speech_apply_mutex_)
    std::thread voice_download_thread_;
    std::atomic<bool> voice_download_busy_{false};
    // Lokales Vorlesen: ein Satz nach dem anderen; shutdown wartet (abgelöst griffe er nach dem Ende ins Leere).
    std::mutex say_mutex_;
    std::thread say_thread_;
    win::CancelToken* voice_cancel_ = nullptr;  // unter cancel_mutex_
    std::atomic<bool> name_hint_shown_{false};
    // Sprechen: speech_apply_mutex_ hält apply_speech zusammen (Start/Stopp aus GUI- und Download-Thread);
    // speech_sig_ = Einstellungen, mit denen speech_ läuft (leer = aus).
    std::mutex speech_apply_mutex_;
    std::string speech_sig_;
    voice::SpeechInput speech_;
    // Konsolenzeile zum Begleiternamen: höchstens eine je Minute (forward_companion_name).
    ConsoleThrottle name_log_{std::chrono::minutes(1)};
    std::atomic<bool> game_voice_{false};  // eigener Spieler laut Plugin im Voice-Chat (game_voice)
    std::thread speech_download_thread_;
    std::atomic<bool> speech_download_busy_{false};
    win::CancelToken* speech_cancel_ = nullptr;  // unter cancel_mutex_

    std::thread load_thread_;
    std::thread download_thread_;
    std::thread cuda_thread_;
    std::atomic<bool> download_busy_{false};
    std::atomic<bool> cuda_busy_{false};
    std::atomic<bool> load_after_download_{false};
    std::mutex cancel_mutex_;
    win::CancelToken* download_cancel_ = nullptr;
};

}  // namespace swbh::gui
