#include "fake_relay.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.h>

#include <algorithm>
#include <cctype>
#include <stdexcept>
#include <vector>

namespace swbh::test {

namespace {

bool winsock() {
    static const bool ok = [] {
        WSADATA d{};
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    return ok;
}

SOCKET sock(std::uintptr_t s) { return static_cast<SOCKET>(s); }

void send_all(SOCKET s, std::string_view data) {
    std::size_t sent = 0;
    while (sent < data.size()) {
        const int n = ::send(s, data.data() + sent, static_cast<int>(std::min<std::size_t>(data.size() - sent, 1 << 20)),
                             0);
        if (n <= 0) {
            return;
        }
        sent += static_cast<std::size_t>(n);
    }
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

std::string header_value(const std::string& head, const std::string& name) {
    const std::string l = lower(head);
    const std::size_t at = l.find("\r\n" + lower(name) + ":");
    if (at == std::string::npos) {
        return {};
    }
    const std::size_t start = at + 2 + name.size() + 1;
    const std::size_t end = head.find("\r\n", start);
    std::string v = head.substr(start, end - start);
    while (!v.empty() && v.front() == ' ') {
        v.erase(v.begin());
    }
    while (!v.empty() && v.back() == ' ') {
        v.pop_back();
    }
    return v;
}

}  // namespace

FakeConnection::FakeConnection(std::uintptr_t socket) : socket_(socket) {}

FakeConnection::~FakeConnection() { close_tcp(); }

bool FakeConnection::fill(std::chrono::steady_clock::time_point deadline) {
    if (eof_ || socket_ == static_cast<std::uintptr_t>(INVALID_SOCKET)) {
        return false;
    }
    const auto now = std::chrono::steady_clock::now();
    if (now >= deadline) {
        error_ = "Zeit abgelaufen";
        return false;
    }
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
    fd_set set;
    FD_ZERO(&set);
    FD_SET(sock(socket_), &set);
    timeval tv{static_cast<long>(ms / 1000), static_cast<long>((ms % 1000) * 1000)};
    if (select(0, &set, nullptr, nullptr, &tv) <= 0) {
        error_ = "Zeit abgelaufen";
        return false;
    }
    char buf[64 * 1024];
    const int n = recv(sock(socket_), buf, sizeof buf, 0);
    if (n <= 0) {
        eof_ = true;
        error_ = "EOF";
        return false;
    }
    buffer_.append(buf, static_cast<std::size_t>(n));
    return true;
}

std::optional<Frame> FakeConnection::read_frame(std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    for (;;) {
        FrameParse p = parse_frame(buffer_, FrameDir::AppToRelay);
        if (p.status == FrameParse::Status::Ok) {
            buffer_.erase(0, p.consumed);
            return std::move(p.frame);
        }
        if (p.status == FrameParse::Status::Error) {
            error_ = "Rahmen der App ungültig: " + p.reason;
            return std::nullopt;
        }
        if (!fill(deadline)) {
            return std::nullopt;
        }
    }
}

relay::Decoded FakeConnection::read_decoded(std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    for (;;) {
        const auto left =
            std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now());
        std::optional<Frame> f = read_frame(std::max(left, std::chrono::milliseconds(0)));
        if (!f) {
            return relay::Decoded{std::nullopt, error_};
        }
        if (f->opcode == 0xA) {
            ++pongs_;
            continue;
        }
        if (f->opcode == 0x9) {
            send_raw(encode_frame(0xA, f->payload, std::nullopt));
            continue;
        }
        if (f->opcode == 0x8) {
            close_code_ = f->payload.size() >= 2 ? ((static_cast<unsigned char>(f->payload[0]) << 8) |
                                                    static_cast<unsigned char>(f->payload[1]))
                                                 : 1005;
            close_reason_ = f->payload.size() > 2 ? f->payload.substr(2) : std::string();
            error_ = "Close " + std::to_string(close_code_);
            return relay::Decoded{std::nullopt, error_};
        }
        return relay::decode(f->payload, relay::Sender::App);
    }
}

std::optional<relay::Message> FakeConnection::read_message(std::chrono::milliseconds timeout) {
    relay::Decoded d = read_decoded(timeout);
    if (!d.message && error_.empty()) {
        error_ = "Nachricht der App ungültig: " + d.reason;
    }
    return std::move(d.message);
}

void FakeConnection::send_message(const relay::Message& m) { send_payload(relay::encode(m, relay::Sender::Relay)); }

void FakeConnection::send_payload(std::string_view payload) { send_raw(encode_frame(0x2, payload, std::nullopt)); }

void FakeConnection::send_raw(std::string_view bytes) {
    if (socket_ != static_cast<std::uintptr_t>(INVALID_SOCKET)) {
        send_all(sock(socket_), bytes);
    }
}

void FakeConnection::close_tcp() {
    if (socket_ != static_cast<std::uintptr_t>(INVALID_SOCKET)) {
        shutdown(sock(socket_), SD_BOTH);
        closesocket(sock(socket_));
        socket_ = static_cast<std::uintptr_t>(INVALID_SOCKET);
    }
}

bool FakeConnection::wait_closed(std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (!eof_) {
        if (!fill(deadline)) {
            return eof_;  // false = Zeit abgelaufen, die App hält die Verbindung noch
        }
        buffer_.clear();
    }
    return true;
}

FakeRelay::FakeRelay(FakeRelayOptions options) : options_(std::move(options)) {
    if (!winsock()) {
        throw std::runtime_error("Winsock fehlt");
    }
    SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (::bind(s, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0 || ::listen(s, 8) != 0) {
        closesocket(s);
        throw std::runtime_error("FakeRelay: bind/listen");
    }
    int len = sizeof a;
    getsockname(s, reinterpret_cast<sockaddr*>(&a), &len);
    port_ = ntohs(a.sin_port);
    listener_ = static_cast<std::uintptr_t>(s);
    thread_ = std::thread([this] { run(); });
}

FakeRelay::~FakeRelay() {
    stop_ = true;
    closesocket(sock(listener_));
    if (thread_.joinable()) {
        thread_.join();
    }
}

void FakeRelay::run() {
    while (!stop_) {
        SOCKET c = ::accept(sock(listener_), nullptr, nullptr);
        if (c == INVALID_SOCKET) {
            if (stop_) {
                return;
            }
            continue;
        }
        ++connections_;
        // Upgrade lesen (Kopf ≤ 8 KiB, 5 s) und beantworten.
        DWORD timeout = 5000;
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);
        std::string head;
        char ch = 0;
        while (head.size() < 8192 && head.find("\r\n\r\n") == std::string::npos) {
            if (recv(c, &ch, 1, 0) != 1) {
                break;
            }
            head.push_back(ch);
        }
        DWORD forever = 0;
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&forever), sizeof forever);
        const std::string key = header_value(head, "Sec-WebSocket-Key");
        {
            std::lock_guard<std::mutex> lock(mutex_);
            last_request_ = head;
        }
        if (key.empty() || !head.starts_with("GET /kanal/v1 HTTP/1.1\r\n")) {
            send_all(c, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            closesocket(c);
            continue;
        }
        if (options_.status != 101) {
            send_all(c, "HTTP/1.1 " + std::to_string(options_.status) +
                            " Nein\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            closesocket(c);
            continue;
        }
        std::string reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            "Sec-WebSocket-Accept: " + websocket_accept(key) + "\r\n";
        if (!options_.protocol.empty()) {
            reply += "Sec-WebSocket-Protocol: " + options_.protocol + "\r\n";
        }
        send_all(c, reply + "\r\n");
        std::lock_guard<std::mutex> lock(mutex_);
        ready_.push_back(std::make_unique<FakeConnection>(static_cast<std::uintptr_t>(c)));
        cv_.notify_all();
    }
}

std::unique_ptr<FakeConnection> FakeRelay::accept(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!cv_.wait_for(lock, timeout, [this] { return !ready_.empty(); })) {
        return nullptr;
    }
    auto c = std::move(ready_.front());
    ready_.pop_front();
    return c;
}

std::string FakeRelay::last_request() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_request_;
}

int unused_port() {
    winsock();
    SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ::bind(s, reinterpret_cast<sockaddr*>(&a), sizeof a);
    int len = sizeof a;
    getsockname(s, reinterpret_cast<sockaddr*>(&a), &len);
    closesocket(s);
    return ntohs(a.sin_port);
}

}  // namespace swbh::test
