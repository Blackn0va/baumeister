// Loopback-Gegenstelle für die Tests der Fernverbindung: spielt das Relay über ws://127.0.0.1:<frei> (ohne TLS – die
// TLS-Seite misst probes/winhttp_pin gegen JSSE). Prüft jeden Rahmen der App streng (ws_frame, wie das Relay) und
// liest Nachrichten mit dem echten Kanal-Leser (relay::decode, Absender App).
//
// Threads: FakeRelay nimmt in einem eigenen Thread an; jede FakeConnection gehört dem Test-Thread, der sie abholt.
// Fehler: Lesefunktionen liefern nullopt (Zeit abgelaufen, EOF, Regelverstoß – Grund in last_error()).

#pragma once

#include "relay/kanal.hpp"
#include "ws_frame.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <utility>

namespace swbh::test {

class FakeConnection final {
public:
    explicit FakeConnection(std::uintptr_t socket);
    ~FakeConnection();
    FakeConnection(const FakeConnection&) = delete;
    FakeConnection& operator=(const FakeConnection&) = delete;

    // Nächster Rahmen der App (Steuerrahmen eingeschlossen), streng geprüft.
    std::optional<Frame> read_frame(std::chrono::milliseconds timeout);
    // Nächste Kanal-Nachricht der App; Pongs/Pings werden gezählt und übersprungen. Close-Rahmen → nullopt und
    // close_code()/close_reason() gesetzt.
    std::optional<relay::Message> read_message(std::chrono::milliseconds timeout);
    // Wie read_message, aber auch eine unlesbare Nutzlast liefert den Grund statt nullopt.
    relay::Decoded read_decoded(std::chrono::milliseconds timeout);

    void send_message(const relay::Message& m);
    void send_payload(std::string_view payload);  // ein unmaskierter Binärrahmen
    void send_raw(std::string_view bytes);
    void close_tcp();  // geordnet (FIN)

    // true, wenn die App die Verbindung geschlossen hat (EOF/Fehler).
    bool wait_closed(std::chrono::milliseconds timeout);

    int close_code() const { return close_code_; }
    const std::string& close_reason() const { return close_reason_; }
    int pongs() const { return pongs_; }
    const std::string& last_error() const { return error_; }

private:
    bool fill(std::chrono::steady_clock::time_point deadline);

    std::uintptr_t socket_;
    std::string buffer_;
    int close_code_ = 0;
    std::string close_reason_;
    int pongs_ = 0;
    std::string error_;
    bool eof_ = false;
};

struct FakeRelayOptions {
    int status = 101;                                     // Antwort auf das Upgrade
    std::string protocol = std::string(relay::kProtocol);  // Sec-WebSocket-Protocol der Antwort (leer = keins)
};

class FakeRelay final {
public:
    explicit FakeRelay(FakeRelayOptions options = {});
    ~FakeRelay();
    FakeRelay(const FakeRelay&) = delete;
    FakeRelay& operator=(const FakeRelay&) = delete;

    int port() const { return port_; }
    std::string url() const { return "ws://127.0.0.1:" + std::to_string(port_) + "/kanal/v1"; }
    // Nächste Verbindung der App mit erledigtem Upgrade. nullptr bei Zeitüberschreitung.
    std::unique_ptr<FakeConnection> accept(std::chrono::milliseconds timeout);
    // Kopf der letzten Upgrade-Anfrage (für Prüfungen).
    std::string last_request() const;
    int connections() const { return connections_.load(); }

private:
    void run();

    FakeRelayOptions options_;
    std::uintptr_t listener_ = 0;
    int port_ = 0;
    std::atomic<bool> stop_{false};
    std::atomic<int> connections_{0};
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::unique_ptr<FakeConnection>> ready_;
    std::string last_request_;
    std::thread thread_;
};

// Freier Port auf 127.0.0.1, auf dem niemand lauscht.
int unused_port();

}  // namespace swbh::test
