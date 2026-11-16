// Kleiner HTTP/1.1-Server über Winsock, nur an 127.0.0.1 (nie an 0.0.0.0).
//
// Warum nur Loopback: Das Plugin erreicht den Server ausschließlich über den SSH-Rückwärtstunnel (plink -R), der auf
// diesem PC an 127.0.0.1:<port> ankommt. Ein LAN-Port bräuchte eine Firewall-Regel und ein Geheimnis
// (festgelegt: „kein LAN-Port, keine Firewall-Regel“). ASVS V14.4 / OWASP A05.
// SO_EXCLUSIVEADDRUSE: Kein anderer Prozess kann sich mit SO_REUSEADDR auf denselben Port setzen und Anfragen abfangen
// (Windows-eigene Falle, MS-Doku „Using SO_REUSEADDR and SO_EXCLUSIVEADDRUSE“).
//
// Je Verbindung genau eine Anfrage (Connection: close), eigener Thread, höchstens kMaxConnections gleichzeitig – so
// beantwortet /health auch während einer langen Erzeugung. Lesen mit Zeitgrenze (kReadTimeoutMs), damit eine halb
// offene Verbindung keinen Thread ewig bindet (Slowloris).
//
// Threads: start/stop aus einem Thread (GUI). Der Handler läuft in den Verbindungs-Threads.
// Fehler: start liefert false mit deutschem Grund.

#pragma once

#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace swbh::server {

// 8 statt 4 seit 2026-11-04: Der Long-Poll des Begleiters (companion_api, bis 20 s) hält dauerhaft eine Verbindung;
// mit 4 blieben neben LLM-Auftrag, Stadtfortschritt und Ergebnis keine frei, und das Plugin bekäme 503.
inline constexpr int kMaxConnections = 8;
inline constexpr int kReadTimeoutMs = 15000;
// Gesamtfrist für das Lesen einer Anfrage (2026-11-16). kReadTimeoutMs gilt je recv und beginnt mit jedem Byte neu:
// Wer alle 14 s ein Byte schickte, hielt eine Verbindung beliebig lange, acht solche sperrten den Server (503) und
// damit das Plugin aus. 60 s reichen weit: 1 MiB Ton braucht selbst über einen Tunnel mit 1 Mbit/s 8 s. Wer die Frist
// entfernt, öffnet die Slowloris-Lücke wieder (OWASP A05, ASVS V12.1.1 sinngemäß für Anfragegrößen und -dauer).
inline constexpr int kRequestDeadlineMs = 60000;

class HttpServer final {
public:
    using Handler = std::function<ApiResponse(const HttpRequest&, const std::function<bool()>& client_gone)>;
    // Eine Zeile je Anfrage: "POST /v1/chat/completions → 200 (12,3 s)".
    using LogFn = std::function<void(const std::string&)>;

    HttpServer(Handler handler, LogFn log);
    // request_deadline: Gesamtfrist fürs Lesen einer Anfrage; Tests setzen sie kurz, sonst kRequestDeadlineMs.
    HttpServer(Handler handler, LogFn log, std::chrono::milliseconds request_deadline);
    ~HttpServer();
    HttpServer(const HttpServer&) = delete;
    HttpServer& operator=(const HttpServer&) = delete;

    bool start(std::uint16_t port, std::string& error);
    // Schließt den Lauscher und alle offenen Verbindungen, wartet auf die Threads. Eine laufende Erzeugung muss der
    // Aufrufer vorher abbrechen (sonst wartet stop() auf ihr Ende).
    void stop();
    bool running() const { return running_.load(); }
    std::uint16_t port() const { return port_; }

private:
    struct Connection;
    void accept_loop();
    void serve(const std::shared_ptr<Connection>& conn);
    void reap();

    Handler handler_;
    LogFn log_;
    std::chrono::milliseconds request_deadline_;
    std::atomic<bool> running_{false};
    std::uintptr_t listen_socket_;
    std::uint16_t port_ = 0;
    std::thread accept_thread_;
    std::mutex mutex_;
    std::vector<std::shared_ptr<Connection>> connections_;
};

}  // namespace swbh::server
