// SSH-Rückwärtstunnel zum Minecraft-Server über plink.exe (PuTTY) mit dem Schlüssel aus Pageant – Ersatz für den Tunnel-Teil
// von llm-host/start-llm.ps1.
//
// Aufruf: plink.exe -batch -v -N -R 127.0.0.1:<fern>:127.0.0.1:<lokal> [-P <ssh-port>] [-hostkey <fp>] <ziel>
//  - -batch: plink fragt nie interaktiv (Kennwort, unbekannter Hostschlüssel) – es gibt kein Konsolenfenster, eine
//    Frage würde den Tunnel still hängen lassen. Stattdessen endet plink mit Meldung, und der Status sagt warum.
//  - -v: Ohne -v schreibt plink bei Erfolg NICHTS. Erst die ausführliche Ausgabe zeigt „Remote port forwarding …
//    enabled“ (verbunden), „Pageant has 0 SSH-2 keys“ und „refused“ (Port auf dem Server belegt). Gemessen an PuTTY 0.8x.
//  - -R mit 127.0.0.1 vorn: Der Tunnel bindet auf dem Server nur an Loopback (GatewayPorts no) – kein offener Port im LAN.
//  - -hostkey (optional): legt den Hostschlüssel fest. Ohne ihn muss der Schlüssel des Servers auf diesem PC einmal mit
//    PuTTY bestätigt worden sein, sonst bricht -batch mit „host key is not cached“ ab.
//
// Sicherheit: plink nur über den eingestellten ABSOLUTEN Pfad, nie über PATH (eine untergeschobene plink.exe im
// Suchpfad bekäme sonst Zugang zu Pageant). Ziel, Ports und Fingerabdruck werden streng geprüft (keine Leerzeichen,
// keine Anführungszeichen) – die Kommandozeile lässt sich so nicht um eigene Schalter erweitern (OWASP A03).
// Das Kind läuft in einem Job-Objekt mit KILL_ON_JOB_CLOSE: Endet die App (auch durch Absturz), endet plink mit.
//
// Threads: Tunnel::start/stop aus einem Thread (GUI); Ereignisse kommen aus dem Überwachungs-Thread.
// Fehler: start liefert false mit deutschem Grund; Laufzeitfehler kommen als Ereignis.

#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <thread>

namespace swbh::tunnel {

inline constexpr std::string_view kDefaultPlink = "C:\\Program Files\\PuTTY\\plink.exe";
// Ziel bis 0.1.x: fest dev01 im Heimnetz des Entwicklers. Seit 2026-11-01 gibt es KEIN Vorgabeziel mehr – jeder trägt
// seinen eigenen Server ein. Der Wert dient nur noch der Übernahme alter config.local.json (gui/app_config.cpp), in
// denen ein leeres Ziel „dev01“ bedeutete. Wer ihn wieder als Vorgabe nimmt, schickt jede neue Installation per SSH
// an eine fremde Adresse im eigenen LAN.
inline constexpr std::string_view kLegacyTarget = "root@192.168.178.47";
inline constexpr int kDefaultRemotePort = 18080;

struct TunnelConfig {
    std::filesystem::path plink;
    std::string target;                                // benutzer@host; leer = kein Server eingetragen
    int ssh_port = 22;
    int remote_port = kDefaultRemotePort;              // auf dem Server (Plugin: llm.url)
    int local_port = 8080;                             // Server dieser App
    std::string hostkey;                               // optional, z. B. "SHA256:…"
    // Anmeldung per Passwort statt Pageant (Wunsch 2026-10-28). Leer = Pageant.
    // Warum -pwfile statt -pw: Die Kommandozeile eines Prozesses kann jedes Programm des PCs lesen (PuTTY-Doku warnt
    // vor -pw). Die Datei liegt in secret_dir (unter %LOCALAPPDATA%, nur für dieses Konto lesbar) und wird gelöscht,
    // sobald plink die erste Zeile schreibt – gemessen 2026-10-28 (PuTTY 0.85): plink liest sie beim Start der
    // Befehlszeile, vor dem Verbinden („unable to open password file“ kommt vor „Looking up host“). ASVS V2.10.4,
    // V8.3.4. Das Passwort steht nie im Protokoll.
    std::string password;
    std::filesystem::path secret_dir;
};

// Prüft die Einstellungen. nullopt = in Ordnung, sonst deutscher Grund. Prüft auch, dass plink existiert.
std::optional<std::string> validate(const TunnelConfig& config);

// Kommandozeile für CreateProcessW (erstes Element = gequoteter plink-Pfad). Setzt validate() voraus.
// password_file: Pfad für -pwfile (leer = Pageant).
std::wstring command_line(const TunnelConfig& config, const std::filesystem::path& password_file = {});

enum class State {
    Stopped,
    Starting,       // plink läuft, noch keine Bestätigung
    Connected,      // „Remote port forwarding … enabled“
    Waiting,        // plink beendet, Neustart nach Backoff
    Failed,         // Einstellungen falsch – kein Neustart
};

// Was eine Zeile der plink-Ausgabe bedeutet.
enum class Signal {
    None,
    Forwarding,       // Weiterleitung steht → verbunden
    ForwardRefused,   // Port auf dem Server belegt (läuft noch ein alter Tunnel?)
    PageantMissing,   // Pageant läuft nicht
    PageantNoKeys,    // Pageant ohne Schlüssel
    AuthFailed,       // Schlüssel abgelehnt / keine Methode
    HostKeyUnknown,   // Hostschlüssel nicht bestätigt
    Unreachable,      // Netzwerkfehler
};

Signal classify_line(std::string_view line);
// Deutscher Text zum Signal fürs Protokoll und den Status (leer bei None/Forwarding).
std::string_view signal_text(Signal s);

// Wartezeit vor dem n-ten Neustart (n ab 1): 10, 20, 40, 60, 60 … Sekunden (start-llm.ps1 wartete fest 10 s; bei
// einem länger ausgeschalteten Server füllte das die Konsole).
std::chrono::seconds backoff(int attempt);

// true, wenn ein Pageant erreichbar aussieht: Fenster „Pageant“ ODER benannte Pipe pageant.* (Pageant ab 0.75,
// gpg-agent mit PuTTY-Unterstützung). Nur Hinweis – maßgeblich ist plinks Ausgabe.
bool pageant_running();

// Gehört eine Pipe dieses Namens (ohne \\.\pipe\) zu Pageant? „pageant.“ am Anfang, Groß-/Kleinschreibung egal.
bool is_pageant_pipe(std::wstring_view name);

// Hinweis vor dem Start, wenn pageant_running() nichts findet – bewusst kein Befehl: plink versucht es trotzdem, und
// erst seine Antwort sagt, ob wirklich ein Schlüssel fehlt.
inline constexpr std::string_view kPageantNotSeen =
    "Kein Pageant gefunden (Fenster oder Pipe) – plink versucht es trotzdem; meldet er danach eine fehlende Anmeldung, "
    "den Schlüssel in Pageant laden";

class Tunnel final {
public:
    struct Event {
        State state = State::Stopped;
        std::string text;  // deutsch, eine Zeile
    };
    using EventFn = std::function<void(const Event&)>;

    explicit Tunnel(EventFn on_event);
    ~Tunnel();
    Tunnel(const Tunnel&) = delete;
    Tunnel& operator=(const Tunnel&) = delete;

    bool start(const TunnelConfig& config, std::string& error);
    void stop();
    State state() const;

private:
    void run(TunnelConfig config);
    void emit(State s, std::string text);

    EventFn on_event_;
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    bool stop_requested_ = false;
    State state_ = State::Stopped;
    void* job_ = nullptr;      // HANDLE Job-Objekt (KILL_ON_JOB_CLOSE)
    void* process_ = nullptr;  // HANDLE des laufenden plink
    std::thread thread_;
};

}  // namespace swbh::tunnel
