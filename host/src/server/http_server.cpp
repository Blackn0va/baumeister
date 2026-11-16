#include "server/http_server.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <exception>

namespace swbh::server {

namespace {

constexpr std::uintptr_t kInvalid = static_cast<std::uintptr_t>(INVALID_SOCKET);

bool winsock_ready() {
    static const bool ok = [] {
        WSADATA data{};
        return WSAStartup(MAKEWORD(2, 2), &data) == 0;
    }();
    return ok;
}

std::string wsa_text(int code) { return "Winsock-Fehler " + std::to_string(code); }

void send_all(SOCKET s, const std::string& data) {
    std::size_t sent = 0;
    while (sent < data.size()) {
        const int chunk = static_cast<int>(std::min<std::size_t>(data.size() - sent, 64 * 1024));
        const int n = ::send(s, data.data() + sent, chunk, 0);
        if (n <= 0) {
            return;  // Gegenseite weg – nichts mehr zu tun
        }
        sent += static_cast<std::size_t>(n);
    }
}

// true, wenn die Gegenseite die Verbindung geschlossen hat (lesbar + 0 Bytes bzw. Fehler).
bool peer_closed(SOCKET s) {
    fd_set set;
    FD_ZERO(&set);
    FD_SET(s, &set);
    timeval tv{0, 0};
    if (select(0, &set, nullptr, nullptr, &tv) <= 0) {
        return false;
    }
    char c = 0;
    const int n = recv(s, &c, 1, MSG_PEEK);
    return n <= 0;
}

std::string seconds_text(double s) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.1f s", s);
    std::string out(buf);
    for (char& ch : out) {
        if (ch == '.') {
            ch = ',';
        }
    }
    return out;
}

}  // namespace

struct HttpServer::Connection {
    SOCKET socket = INVALID_SOCKET;
    std::thread thread;
    std::atomic<bool> done{false};
};

HttpServer::HttpServer(Handler handler, LogFn log)
    : HttpServer(std::move(handler), std::move(log), std::chrono::milliseconds(kRequestDeadlineMs)) {}

HttpServer::HttpServer(Handler handler, LogFn log, std::chrono::milliseconds request_deadline)
    : handler_(std::move(handler)),
      log_(std::move(log)),
      request_deadline_(request_deadline),
      listen_socket_(kInvalid) {}

HttpServer::~HttpServer() { stop(); }

bool HttpServer::start(std::uint16_t port, std::string& error) {
    if (running_) {
        return true;
    }
    if (!winsock_ready()) {
        error = "Winsock nicht verfügbar";
        return false;
    }
    SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        error = "Socket nicht anlegbar (" + wsa_text(WSAGetLastError()) + ")";
        return false;
    }
    BOOL exclusive = TRUE;
    setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char*>(&exclusive), sizeof exclusive);
    // Der Socket darf an kein Kind vererbt werden (plink startet mit eigener Handle-Liste, aber sicher ist sicher).
    SetHandleInformation(reinterpret_cast<HANDLE>(s), HANDLE_FLAG_INHERIT, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // nur 127.0.0.1 – siehe Kopfkommentar
    if (::bind(s, reinterpret_cast<const sockaddr*>(&addr), sizeof addr) != 0) {
        const int code = WSAGetLastError();
        closesocket(s);
        error = code == WSAEADDRINUSE || code == WSAEACCES
                    ? "Port " + std::to_string(port) + " ist belegt (läuft noch llama-server oder start-llm.ps1?)"
                    : "Port " + std::to_string(port) + " nicht nutzbar (" + wsa_text(code) + ")";
        return false;
    }
    if (::listen(s, 8) != 0) {
        error = "listen fehlgeschlagen (" + wsa_text(WSAGetLastError()) + ")";
        closesocket(s);
        return false;
    }
    listen_socket_ = static_cast<std::uintptr_t>(s);
    port_ = port;
    running_ = true;
    accept_thread_ = std::thread([this] { accept_loop(); });
    return true;
}

void HttpServer::stop() {
    if (!running_.exchange(false)) {
        return;
    }
    closesocket(static_cast<SOCKET>(listen_socket_));  // accept() kehrt mit Fehler zurück
    listen_socket_ = kInvalid;
    if (accept_thread_.joinable()) {
        accept_thread_.join();
    }
    std::vector<std::shared_ptr<Connection>> open;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        open.swap(connections_);
    }
    for (const auto& c : open) {
        shutdown(c->socket, SD_BOTH);  // blockierendes recv/send endet
    }
    for (const auto& c : open) {
        if (c->thread.joinable()) {
            c->thread.join();
        }
    }
}

void HttpServer::reap() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto it = connections_.begin(); it != connections_.end();) {
        if ((*it)->done) {
            if ((*it)->thread.joinable()) {
                (*it)->thread.join();
            }
            it = connections_.erase(it);
        } else {
            ++it;
        }
    }
}

void HttpServer::accept_loop() {
    while (running_) {
        sockaddr_in peer{};
        int len = sizeof peer;
        SOCKET c = ::accept(static_cast<SOCKET>(listen_socket_), reinterpret_cast<sockaddr*>(&peer), &len);
        if (c == INVALID_SOCKET) {
            if (!running_) {
                return;
            }
            Sleep(50);
            continue;
        }
        SetHandleInformation(reinterpret_cast<HANDLE>(c), HANDLE_FLAG_INHERIT, 0);
        reap();
        auto conn = std::make_shared<Connection>();
        conn->socket = c;
        std::lock_guard<std::mutex> lock(mutex_);
        if (static_cast<int>(connections_.size()) >= kMaxConnections) {
            send_all(c, make_response(503, "application/json; charset=utf-8",
                                      error_json(503, "Zu viele Verbindungen"), {"Retry-After: 30"}));
            shutdown(c, SD_SEND);
            closesocket(c);
            if (log_) {
                log_("Verbindung abgewiesen: schon " + std::to_string(kMaxConnections) + " offen → 503");
            }
            continue;
        }
        connections_.push_back(conn);
        conn->thread = std::thread([this, conn] { serve(conn); });
    }
}

void HttpServer::serve(const std::shared_ptr<Connection>& conn) {
    const SOCKET s = conn->socket;
    const auto started = std::chrono::steady_clock::now();
    const auto deadline = started + request_deadline_;
    std::string buffer;
    ParseResult parsed;
    char chunk[16 * 1024];
    while (true) {
        parsed = parse_request(buffer);
        if (parsed.status != ParseStatus::Incomplete) {
            break;
        }
        // Je recv höchstens kReadTimeoutMs, aber nie über die Gesamtfrist hinaus (siehe kRequestDeadlineMs).
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline -
                                                                               std::chrono::steady_clock::now());
        if (left.count() <= 0) {
            parsed.status = ParseStatus::Error;
            parsed.http_status = 408;
            parsed.error = "lesen_gesamtfrist";
            break;
        }
        DWORD timeout = static_cast<DWORD>(std::min<long long>(left.count(), kReadTimeoutMs));
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);
        const int n = recv(s, chunk, sizeof chunk, 0);
        if (n <= 0) {
            parsed.status = ParseStatus::Error;
            parsed.http_status = n == 0 ? 0 : 408;  // 0 = Gegenseite weg, keine Antwort nötig
            parsed.error = n == 0 ? "verbindung_geschlossen"
                           : std::chrono::steady_clock::now() >= deadline ? "lesen_gesamtfrist"
                                                                          : "lesen_zeitgrenze";
            break;
        }
        buffer.append(chunk, static_cast<std::size_t>(n));
    }

    std::string line;
    int status = 0;
    if (parsed.status == ParseStatus::Error) {
        status = parsed.http_status;
        if (status != 0) {
            send_all(s, make_response(status, "application/json; charset=utf-8", error_json(status, parsed.error)));
        }
        line = "Anfrage abgewiesen: " + parsed.error + (status != 0 ? " → " + std::to_string(status) : "");
    } else {
        const HttpRequest& req = parsed.request;
        ApiResponse resp;
        if (const std::optional<Rejection> rej = check_request(req)) {
            resp.status = rej->http_status;
            resp.body = error_json(rej->http_status, rej->error);
            line = req.method + " " + req.target + " abgewiesen: " + rej->error;
        } else {
            try {
                resp = handler_(req, [s] { return peer_closed(s); });
            } catch (const std::exception& e) {
                resp = ApiResponse{};
                resp.status = 500;
                resp.body = error_json(500, std::string("Ausnahme: ") + e.what());
            }
            line = req.method + " " + req.target;
        }
        status = resp.status;
        send_all(s, make_response(resp.status, resp.content_type, resp.body, resp.extra_headers));
        const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        line += " → " + std::to_string(resp.status) + " (" + seconds_text(secs) + ")";
    }
    shutdown(s, SD_SEND);
    closesocket(s);
    // /health fragt das Plugin regelmäßig – erfolgreiche Abfragen nicht protokollieren, sonst ertrinkt die Konsole.
    if (log_ && !(status == 200 && line.starts_with("GET /health"))) {
        log_(line);
    }
    conn->done = true;
}

}  // namespace swbh::server
