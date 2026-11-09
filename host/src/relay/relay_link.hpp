// Fernverbindung über Kanal v1: Die App verbindet sich NACH AUSSEN mit dem Relay auf dem Minecraft-Server
// (wss://…:25580/kanal/v1, gepinnt beidseitig – win/pinned_ws.hpp), nimmt dort weitergereichte Anfragen des Plugins an
// („anfrage“), schickt jede als Loopback-HTTP an den eigenen Server (relay/forwarder.hpp) und die Antwort zurück
// („antwort“/„daten“). Ersatz für den SSH-Rückwärtstunnel, wenn der Server nur über eine Adresse im Internet erreichbar
// ist (Wunsch 2026-11-08). Der Tunnel bleibt die andere Wahl.
//
// Regeln (docs/kanal-v1.md):
//  - Zuerst „hallo“ der App (v=1, max=6), dann muss binnen 15 s das „hallo“ des Relays kommen – sonst Close 1002.
//  - Höchstens 6 Aufträge gleichzeitig (der eigene Server erlaubt 8 Verbindungen). Ein siebter bekommt „abbruch“.
//  - Auftrags-ids steigen je Verbindung streng; eine alte oder doppelte id ist ein Protokollfehler (Close 1002) – so
//    kann keine Nachricht einem fremden, schon laufenden Auftrag untergeschoben werden.
//  - „abbruch“ vom Relay schließt die lokale Anfrage (der eigene Server hört auf zu rechnen); danach geht zu dieser id
//    nichts mehr hinaus. Verbindungsverlust bricht ALLE lokalen Anfragen ab.
//  - Eigener Server nicht erreichbar → „abbruch“ (das Relay erfindet nie 502/503/504).
//  - Jeder Verstoß des Relays (Kopf, Typ, Pfad, Länge …) beendet die Verbindung mit dem Grund aus den Testvektoren.
//  - Neustart nach 10 → 20 → 40 → 60 s, nach 60 s stabiler Verbindung wieder ab 10 s (wie der Tunnel).
// Lebenszeichen: WinHTTP schickt alle 20 s einen unaufgeforderten Pong und beantwortet Pings selbst; Steuerrahmen
// sieht die App nicht (gemessen, win/pinned_ws.hpp). Eine tote Leitung meldet TCP über receive().
//
// Threads: start/stop aus einem Thread (GUI). Ein Verbindungs-Thread liest; je Verbindung bis zu 6 Arbeits-Threads
// leiten weiter und senden. Ereignisse kommen aus dem Verbindungs-Thread.
// Fehler: start liefert false mit deutschem Grund (Einstellungen); alles andere kommt als Ereignis.

#pragma once

#include "relay/kanal.hpp"
#include "win/http.hpp"
#include "win/pinned_ws.hpp"

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace swbh::relay {


enum class State {
    Stopped,
    Starting,   // Verbindungsaufbau läuft
    Connected,  // hallo ausgetauscht
    Waiting,    // getrennt, Neustart nach Wartezeit
    Failed,     // Einstellungen unbrauchbar – kein Neustart
};

struct LinkConfig {
    std::string url;                       // wss://host[:port]/kanal/v1
    std::vector<std::string> server_pins;  // 1–2
    int local_port = 8080;                 // eigener Server
    std::string app_name;                  // hallo.app, z. B. "BaumeisterHost/0.1.0"
};


// Prüft die Einstellungen. nullopt = in Ordnung, sonst deutscher Grund.
std::optional<std::string> validate(const LinkConfig& config);

// Wartezeit vor dem n-ten Neustart (n ab 1): 10, 20, 40, 60, 60 … s.
std::chrono::seconds backoff(int attempt);

struct LinkStats {
    std::uint64_t jobs = 0;          // angenommene Aufträge
    std::uint64_t refused = 0;       // über max hinaus → abbruch
    std::uint64_t dropped = 0;       // daten/abbruch zu unbekannter id verworfen (Abschnitt 3)
    std::uint64_t local_failed = 0;  // eigener Server nicht erreichbar → abbruch
    std::uint64_t aborted = 0;       // abbruch vom Relay
};

class RelayLink final {
public:
    struct Event {
        State state = State::Stopped;
        std::string text;    // deutsch, eine Zeile
        bool alarm = false;  // sicherheitsrelevant (falscher Server-Pin) – die Oberfläche zeigt es als Fehler
    };
    using EventFn = std::function<void(const Event&)>;
    // Baut eine Kanal-Verbindung auf (Vorgabe in der App: win::connect_pinned_ws mit Client-Zertifikat).
    using Connector = std::function<std::unique_ptr<win::WsConnection>(const LinkConfig&, win::WsConnectError&,
                                                                       win::CancelToken&)>;

    struct Options {
        Connector connect;  // Pflicht
        // Transport zum eigenen Server; leer = win::make_winhttp_transport().
        std::function<std::unique_ptr<win::HttpTransport>()> make_http;
        // Wartezeit vor dem n-ten Neustart; leer = backoff() (Tests verkürzen sie).
        std::function<std::chrono::milliseconds(int)> backoff;
        std::chrono::milliseconds hello_timeout{15000};
    };

    RelayLink(EventFn on_event, Options options);
    ~RelayLink();
    RelayLink(const RelayLink&) = delete;
    RelayLink& operator=(const RelayLink&) = delete;

    bool start(const LinkConfig& config, std::string& error);
    void stop();
    State state() const;
    LinkStats stats() const;

private:
    struct Job;
    struct Session;
    void run(LinkConfig config);
    // Eine Verbindung bis zu ihrem Ende. Rückgabe: Grund des Endes (deutsch); established = hallo ausgetauscht.
    std::string run_session(const LinkConfig& config, std::shared_ptr<win::WsConnection> conn, bool& established);
    void work(Session& session, int port);
    void process(Session& session, const std::shared_ptr<Job>& job, int port);
    bool send(Session& session, const std::shared_ptr<Job>& job, const std::string& payload);
    void emit(State s, std::string text, bool alarm = false);

    EventFn on_event_;
    Options options_;
    std::unique_ptr<win::HttpTransport> http_;
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    bool stop_requested_ = false;
    State state_ = State::Stopped;
    LinkStats stats_;
    std::shared_ptr<win::WsConnection> current_;  // unter mutex_ – stop() schließt sie
    win::CancelToken* connect_cancel_ = nullptr;  // unter mutex_ – stop() bricht den Aufbau ab
    std::thread thread_;
};

}  // namespace swbh::relay
