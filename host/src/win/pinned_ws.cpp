// Gepinnte WebSocket-Verbindung über WinHTTP – Begründungen und Messwerte in pinned_ws.hpp.

#include "win/pinned_ws.hpp"

#include "base/log.hpp"
#include "base/utf8.hpp"
#include "win/cert_pin.hpp"
#include "win/proxy.hpp"
#include "win/tls_diag.hpp"

#include <windows.h>

#include <wincrypt.h>
#include <winhttp.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <span>
#include <thread>
#include <utility>

namespace swbh::win {

namespace {

// HINTERNET mit idempotentem, threadsicherem Schließen. Schließt der Pin-Rückruf, der Abbruch oder der Aufrufer –
// jeder darf, doppelt geschlossen wird nie (ein freigegebener Handle-Wert kann sonst schon einer anderen Anfrage
// gehören).
class SharedHandle final {
public:
    explicit SharedHandle(HINTERNET h) : h_(h) {}
    ~SharedHandle() { close(); }
    SharedHandle(const SharedHandle&) = delete;
    SharedHandle& operator=(const SharedHandle&) = delete;

    HINTERNET get() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return h_;
    }
    void close() {
        HINTERNET h = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            h = std::exchange(h_, nullptr);
        }
        if (h != nullptr) {
            WinHttpCloseHandle(h);
        }
    }

private:
    mutable std::mutex mutex_;
    HINTERNET h_ = nullptr;
};

// Einfaches Besitz-Handle (Sitzung, Verbindung) – gehört genau einem Objekt.
class OwnedHandle final {
public:
    OwnedHandle() = default;
    explicit OwnedHandle(HINTERNET h) : h_(h) {}
    ~OwnedHandle() { reset(); }
    OwnedHandle(OwnedHandle&& o) noexcept : h_(std::exchange(o.h_, nullptr)) {}
    OwnedHandle& operator=(OwnedHandle&& o) noexcept {
        if (this != &o) {
            reset();
            h_ = std::exchange(o.h_, nullptr);
        }
        return *this;
    }
    OwnedHandle(const OwnedHandle&) = delete;
    OwnedHandle& operator=(const OwnedHandle&) = delete;
    HINTERNET get() const { return h_; }
    void reset() {
        if (h_ != nullptr) {
            WinHttpCloseHandle(h_);
            h_ = nullptr;
        }
    }

private:
    HINTERNET h_ = nullptr;
};

// Kontext des Statusrückrufs; lebt bis nach dem Schließen des Anfrage-Handles (connect_pinned_ws). Die Abbruch-Aktion
// greift auf request zu; Unregister meldet sie vor dessen Zerstörung ab, und remove_cancel_action wartet dabei auf
// einen gerade laufenden Abbruch (CancelToken, Sicherheitsprüfung 2026-11-08, N10).
struct PinCheck {
    std::vector<std::string> pins;
    SharedHandle* request = nullptr;
    std::atomic<bool> checked{false};
    std::atomic<bool> mismatch{false};
    std::atomic<DWORD> secure_flags{0};
    std::mutex mutex;
    std::string found;
};

std::string server_pin(HINTERNET request) {
    PCCERT_CONTEXT cert = nullptr;
    DWORD size = sizeof cert;
    if (request == nullptr || !WinHttpQueryOption(request, WINHTTP_OPTION_SERVER_CERT_CONTEXT, &cert, &size) ||
        cert == nullptr) {
        return {};
    }
    std::string pin = pin_of_der(std::span<const std::uint8_t>(cert->pbCertEncoded, cert->cbCertEncoded));
    CertFreeCertificateContext(cert);
    return pin;
}

// Erste Pin-Prüfung: nach dem Handshake, bevor ein Byte der Anfrage hinausgeht (gemessen, siehe pinned_ws.hpp). Bei
// Abweichung – oder wenn das Zertifikat nicht lesbar ist – Handle sofort zu (fail-closed).
void CALLBACK pin_callback(HINTERNET h, DWORD_PTR context, DWORD status, LPVOID info, DWORD info_len) {
    if (context == 0) {
        return;
    }
    auto* check = reinterpret_cast<PinCheck*>(context);
    if (status == WINHTTP_CALLBACK_STATUS_SECURE_FAILURE) {
        if (info != nullptr && info_len >= sizeof(DWORD)) {
            check->secure_flags |= *static_cast<const DWORD*>(info);
        }
        return;
    }
    if (status != WINHTTP_CALLBACK_STATUS_SENDING_REQUEST) {
        return;
    }
    const std::string pin = server_pin(h);
    {
        std::lock_guard<std::mutex> lock(check->mutex);
        check->found = pin;
    }
    check->checked = true;
    if (pin.empty() || !pin_listed(pin, check->pins)) {
        check->mismatch = true;
        check->request->close();
    }
}

bool token_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
           std::string_view("!#$%&'*+-.^_`|~").find(c) != std::string_view::npos;
}

bool printable(std::string_view s) {
    return std::all_of(s.begin(), s.end(), [](char c) {
        const auto u = static_cast<unsigned char>(c);
        return u >= 0x20 && u <= 0x7E;
    });
}

std::string short_pin(const std::string& pin) { return pin.size() > 23 ? pin.substr(0, 23) + "…" : pin; }

class WinHttpWs final : public WsConnection {
public:
    WinHttpWs(OwnedHandle session, OwnedHandle connect, HINTERNET socket, std::size_t max_bytes)
        : session_(std::move(session)), connect_(std::move(connect)), socket_(socket), max_bytes_(max_bytes),
          buffer_(64 * 1024) {}

    ~WinHttpWs() override { close(1001, ""); }

    WsMessage receive() override {
        reader_ = std::this_thread::get_id();
        std::string message;
        for (;;) {
            HINTERNET socket = socket_.load();
            if (socket == nullptr) {
                return error("Verbindung geschlossen");
            }
            DWORD read = 0;
            WINHTTP_WEB_SOCKET_BUFFER_TYPE type{};
            receiving_ = true;
            const DWORD rc = WinHttpWebSocketReceive(socket, buffer_.data(), static_cast<DWORD>(buffer_.size()), &read,
                                                     &type);
            receiving_ = false;
            if (rc != NO_ERROR) {
                note_peer_closed();
                // 12152: falscher Rahmen des Servers (Maske, RSV, Steuerrahmen zu lang – gemessen), 12030: Leitung weg.
                return error(closing_.load() ? std::string("Verbindung geschlossen")
                                             : "Verbindung abgerissen – " + winhttp_error_text(rc));
            }
            if (type == WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE) {
                USHORT code = 0;
                char reason[128]{};
                DWORD reason_len = 0;
                WinHttpWebSocketQueryCloseStatus(socket, &code, reason, sizeof reason, &reason_len);
                note_peer_closed();
                WsMessage m;
                m.kind = WsMessage::Kind::Closed;
                m.close_code = code;
                m.text = sanitize_utf8(std::string_view(reason, std::min<DWORD>(reason_len, sizeof reason)));
                return m;
            }
            message.append(buffer_.data(), read);
            if (message.size() > max_bytes_) {
                return error("Nachricht größer als " + std::to_string(max_bytes_) + " Bytes");
            }
            if (type == WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE) {
                return WsMessage{WsMessage::Kind::Binary, std::move(message), 0, {}};
            }
            if (type == WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE) {
                return WsMessage{WsMessage::Kind::Text, std::move(message), 0, {}};
            }
            // *_FRAGMENT_BUFFER_TYPE: weiter sammeln (WinHTTP liefert große Rahmen in Stücken, gemessen).
        }
    }

    bool send_binary(std::string_view data) override {
        std::lock_guard<std::mutex> lock(send_mutex_);
        HINTERNET socket = socket_.load();
        if (socket == nullptr || closing_.load()) {
            return false;
        }
        // Leere Nachricht nur mit nullptr: mit Zeiger meldet WinHTTP 87 (gemessen).
        void* ptr = data.empty() ? nullptr : const_cast<char*>(data.data());
        return WinHttpWebSocketSend(socket, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE, ptr,
                                    static_cast<DWORD>(data.size())) == NO_ERROR;
    }

    // Close-Handshake (RFC 6455 §7): erst den Close-Rahmen samt Grund senden, dann höchstens 1 s auf das Close des
    // Gegenübers warten, dann das Handle schließen. Vorher wurde das Handle sofort geschlossen: Lag der Close-Rahmen
    // beim Gegenüber noch ungelesen im Puffer, verwarf dessen TCP ihn mit dem Abbruch – der Test „ohne hallo … schließt
    // mit hallo_fehlt“ flackerte (2026-11-08, ein Fehlschlag in zwei Läufen). Das Relay soll den Grund sehen.
    void close(int code, std::string_view reason) override {
        if (closing_.exchange(true)) {
            return;
        }
        HINTERNET socket = socket_.load();
        if (socket == nullptr) {
            return;
        }
        bool sent = false;
        {
            // Nur, wenn gerade nichts gesendet wird: Ein hängendes Senden (Gegenstelle liest nicht) soll das Schließen
            // nicht aufhalten – das Schließen des Handles bricht es ab.
            std::unique_lock<std::mutex> lock(send_mutex_, std::try_to_lock);
            if (lock.owns_lock()) {
                std::string why(reason.substr(0, 123));
                if (!printable(why)) {
                    why.clear();
                }
                sent = WinHttpWebSocketShutdown(socket, static_cast<USHORT>(code),
                                                why.empty() ? nullptr : static_cast<PVOID>(why.data()),
                                                static_cast<DWORD>(why.size())) == NO_ERROR;
            }
        }
        if (sent) {
            if (reader_.load() == std::this_thread::get_id()) {
                drain_until_close(socket);
            } else {
                std::unique_lock<std::mutex> lock(close_mutex_);
                close_cv_.wait_for(lock, std::chrono::seconds(1), [this] { return peer_closed_ || !receiving_.load(); });
            }
        }
        if (HINTERNET h = socket_.exchange(nullptr); h != nullptr) {
            WinHttpCloseHandle(h);  // bricht ein noch hängendes Empfangen oder Senden ab
        }
    }

private:
    WsMessage error(std::string text) const {
        WsMessage m;
        m.kind = WsMessage::Kind::Error;
        m.text = std::move(text);
        return m;
    }

    void note_peer_closed() {
        {
            std::lock_guard<std::mutex> lock(close_mutex_);
            peer_closed_ = true;
        }
        close_cv_.notify_all();
    }

    // Schließt der Lesethread selbst (Protokollfehler), liest er bis zum Close des Gegenübers mit; ein Zeitgeber
    // schließt das Handle nach 1 s, falls keins kommt (WinHTTP kennt beim WebSocket keine Empfangsfrist).
    void drain_until_close(HINTERNET socket) {
        bool done = false;
        std::thread timer([this, &done] {
            std::unique_lock<std::mutex> lock(close_mutex_);
            if (!close_cv_.wait_for(lock, std::chrono::seconds(1), [&done] { return done; })) {
                lock.unlock();
                if (HINTERNET h = socket_.exchange(nullptr); h != nullptr) {
                    WinHttpCloseHandle(h);
                }
            }
        });
        for (;;) {
            DWORD read = 0;
            WINHTTP_WEB_SOCKET_BUFFER_TYPE type{};
            const DWORD rc = WinHttpWebSocketReceive(socket, buffer_.data(), static_cast<DWORD>(buffer_.size()), &read,
                                                     &type);
            if (rc != NO_ERROR || type == WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE) {
                break;
            }
        }
        {
            std::lock_guard<std::mutex> lock(close_mutex_);
            done = true;
        }
        close_cv_.notify_all();
        timer.join();
    }

    OwnedHandle session_;
    OwnedHandle connect_;
    std::atomic<HINTERNET> socket_;
    std::atomic<bool> closing_{false};
    std::atomic<bool> receiving_{false};
    std::atomic<std::thread::id> reader_;
    std::size_t max_bytes_;
    std::mutex send_mutex_;
    std::mutex close_mutex_;
    std::condition_variable close_cv_;
    bool peer_closed_ = false;  // unter close_mutex_
    std::vector<char> buffer_;  // nur der Lesethread
};
std::unique_ptr<WsConnection> fail(WsConnectError& error, WsConnectError::Kind kind, std::string text) {
    error.kind = kind;
    error.text = std::move(text);
    return nullptr;
}

}  // namespace

std::unique_ptr<WsConnection> connect_pinned_ws(const PinnedWsOptions& o, WsConnectError& error, CancelToken* cancel) {
    using Kind = WsConnectError::Kind;
    error = WsConnectError{};
    const bool secure = o.url.starts_with("wss://");
    const bool plain = !secure && o.url.starts_with("ws://") && o.allow_plain_loopback_for_tests;
    if (!secure && !plain) {
        return fail(error, Kind::Config, "Adresse muss mit wss:// beginnen");
    }
    const std::optional<ParsedUrl> url = parse_url((secure ? "https://" : "http://") + o.url.substr(secure ? 6 : 5));
    if (!url || url->path_and_query.empty() || !printable(url->path_and_query)) {
        return fail(error, Kind::Config, "Adresse nicht lesbar");
    }
    if (plain && url->host != "127.0.0.1") {
        return fail(error, Kind::Config, "ws:// nur zu 127.0.0.1 (Tests)");
    }
    const std::string where = url->host + ":" + std::to_string(url->port);
    std::vector<std::string> pins;
    if (secure) {
        // Ohne Pin keine Verbindung: Nur ein Pin rechtfertigt das Überspringen der CA-Prüfung (Kopfkommentar).
        for (const std::string& p : o.server_pins) {
            const std::optional<std::string> pin = parse_pin(p);
            if (!pin) {
                return fail(error, Kind::Config, "Fingerabdruck des Servers hat nicht die Form sha256:<64 Hex>");
            }
            pins.push_back(*pin);
        }
        if (pins.empty() || pins.size() > 2) {
            return fail(error, Kind::Config, "Ein oder zwei Fingerabdrücke des Servers nötig – ohne Fingerabdruck keine "
                                             "Verbindung");
        }
        if (o.client_cert == nullptr) {
            return fail(error, Kind::Config, "Eigenes Zertifikat fehlt");
        }
    }
    if (o.protocol.empty() || !std::all_of(o.protocol.begin(), o.protocol.end(), token_char)) {
        return fail(error, Kind::Config, "WebSocket-Protokoll fehlt oder ist ungültig");
    }
    if (o.user_agent.empty() || !printable(o.user_agent)) {
        return fail(error, Kind::Config, "User-Agent fehlt oder ist ungültig");
    }
    if (cancel != nullptr && cancel->cancelled()) {
        return fail(error, Kind::Cancelled, "abgebrochen");
    }

    // Eigene Sitzung OHNE Proxy und ohne Verbindungs-Wiederverwendung: jede Verbindung ein frischer Handshake mit
    // frischer Pin-Prüfung.
    OwnedHandle session(WinHttpOpen(widen(o.user_agent).c_str(), WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME,
                                    WINHTTP_NO_PROXY_BYPASS, 0));
    if (session.get() == nullptr) {
        return fail(error, Kind::Connect, "WinHTTP nicht verfügbar – " + winhttp_error_text(GetLastError()));
    }
    // TLS 1.3, sonst 1.2 (Windows 10 kennt 1.3 in Schannel nicht und lehnt das Flag ab). Nie darunter.
    DWORD protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3;
    if (!WinHttpSetOption(session.get(), WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof protocols)) {
        protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
        if (!WinHttpSetOption(session.get(), WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof protocols)) {
            return fail(error, Kind::Tls, "TLS 1.2 nicht einstellbar – " + winhttp_error_text(GetLastError()));
        }
    }
    const int t = std::max(1000, o.connect_timeout_ms);
    WinHttpSetTimeouts(session.get(), t, t, t, t);
    OwnedHandle connect(WinHttpConnect(session.get(), widen(url->host).c_str(),
                                       static_cast<INTERNET_PORT>(url->port), 0));
    if (connect.get() == nullptr) {
        return fail(error, Kind::Connect, "Verbindung zu " + where + " nicht anlegbar – " +
                                              winhttp_error_text(GetLastError()));
    }
    SharedHandle request(WinHttpOpenRequest(connect.get(), L"GET", widen(url->path_and_query).c_str(), nullptr,
                                            WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                            WINHTTP_FLAG_ESCAPE_DISABLE | (secure ? WINHTTP_FLAG_SECURE : 0)));
    if (request.get() == nullptr) {
        return fail(error, Kind::Connect, "Anfrage an " + where + " nicht anlegbar – " +
                                              winhttp_error_text(GetLastError()));
    }
    DWORD features = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_KEEP_ALIVE;
    WinHttpSetOption(request.get(), WINHTTP_OPTION_DISABLE_FEATURE, &features, sizeof features);
    DWORD logon = WINHTTP_AUTOLOGON_SECURITY_LEVEL_HIGH;  // nie die Windows-Anmeldung anbieten
    WinHttpSetOption(request.get(), WINHTTP_OPTION_AUTOLOGON_POLICY, &logon, sizeof logon);
    if (secure) {
        // Nur zusammen mit den Pins oben (Kopfkommentar). Datum, Signatur und Schlüsselbesitz prüft Schannel weiter.
        DWORD flags = SECURITY_FLAG_IGNORE_UNKNOWN_CA | SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
        if (!WinHttpSetOption(request.get(), WINHTTP_OPTION_SECURITY_FLAGS, &flags, sizeof flags)) {
            return fail(error, Kind::Tls, "Zertifikatsprüfung nicht einstellbar – " + winhttp_error_text(GetLastError()));
        }
        if (!WinHttpSetOption(request.get(), WINHTTP_OPTION_CLIENT_CERT_CONTEXT, const_cast<void*>(o.client_cert),
                              sizeof(CERT_CONTEXT))) {
            return fail(error, Kind::Config, "Eigenes Zertifikat nicht setzbar – " + winhttp_error_text(GetLastError()));
        }
    }
    if (!WinHttpSetOption(request.get(), WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET, nullptr, 0)) {
        return fail(error, Kind::Connect, "WebSocket nicht verfügbar – " + winhttp_error_text(GetLastError()));
    }
    const std::wstring header = L"Sec-WebSocket-Protocol: " + widen(o.protocol) + L"\r\n";
    WinHttpAddRequestHeaders(request.get(), header.c_str(), static_cast<DWORD>(header.size()), WINHTTP_ADDREQ_FLAG_ADD);

    PinCheck check;
    check.pins = pins;
    check.request = &request;
    if (secure) {
        WinHttpSetStatusCallback(request.get(), pin_callback,
                                 WINHTTP_CALLBACK_FLAG_SEND_REQUEST | WINHTTP_CALLBACK_FLAG_SECURE_FAILURE, 0);
    }
    std::uint64_t cancel_id = 0;
    if (cancel != nullptr) {
        cancel_id = cancel->add_cancel_action([&request] { request.close(); });
    }
    struct Unregister {
        CancelToken* token;
        std::uint64_t id;
        ~Unregister() {
            if (token != nullptr) {
                token->remove_cancel_action(id);
            }
        }
    } unregister{cancel, cancel_id};

    BOOL ok = WinHttpSendRequest(request.get(), WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0,
                                 reinterpret_cast<DWORD_PTR>(&check));
    if (ok) {
        ok = WinHttpReceiveResponse(request.get(), nullptr);
    }
    const DWORD err = ok ? 0 : GetLastError();
    if (check.mismatch) {
        std::lock_guard<std::mutex> lock(check.mutex);
        error.found_pin = check.found;
        return fail(error, Kind::PinMismatch,
                    "Fingerabdruck des Servers " + where + " passt nicht (gezeigt " +
                        (check.found.empty() ? std::string("kein lesbares Zertifikat") : short_pin(check.found)) +
                        ") – abgebrochen, bevor etwas gesendet wurde");
    }
    if (cancel != nullptr && cancel->cancelled()) {
        return fail(error, Kind::Cancelled, "abgebrochen");
    }
    if (!ok) {
        // Schannel-Fehler (0x8009xxxx): Gemessen meldet ein Relay, das den eigenen Pin nicht kennt, über den TLS-Alarm
        // certificate_unknown 0x80090327 (SEC_E_CERT_UNKNOWN) – bei TLS 1.2 schon im Senden, bei 1.3 erst beim Lesen
        // der Antwort (der Client hält seinen Handshake da schon für fertig).
        if ((err & 0xFFFF0000u) == 0x80090000u) {
            char code[16];
            std::snprintf(code, sizeof code, "0x%08lX", static_cast<unsigned long>(err));
            return fail(error, Kind::Rejected, where + " lehnt das eigene Zertifikat ab – ist der eigene Fingerabdruck "
                                                       "dort eingetragen? (Schannel " + std::string(code) + ")");
        }
        switch (err) {
        case ERROR_WINHTTP_NAME_NOT_RESOLVED:
            return fail(error, Kind::Connect, "Name nicht auflösbar: " + url->host);
        case ERROR_WINHTTP_CANNOT_CONNECT:
            return fail(error, Kind::Connect, where + " nicht erreichbar (Relay aus oder Port nicht freigegeben?)");
        case ERROR_WINHTTP_TIMEOUT:
            return fail(error, Kind::Connect, "Zeitüberschreitung bei " + where);
        case ERROR_WINHTTP_SECURE_FAILURE:
            return fail(error, Kind::Tls,
                        "TLS-Fehler bei " + where + ": " + tls_failure_reason(check.secure_flags.load()));
        case ERROR_WINHTTP_CLIENT_CERT_NO_PRIVATE_KEY:
        case ERROR_WINHTTP_CLIENT_CERT_NO_ACCESS_PRIVATE_KEY:
            return fail(error, Kind::Config, "Kein Zugriff auf den eigenen Schlüssel – " + winhttp_error_text(err));
        case ERROR_WINHTTP_CONNECTION_ERROR:
        case ERROR_WINHTTP_INVALID_SERVER_RESPONSE:
        case ERROR_WINHTTP_SECURE_CHANNEL_ERROR:
            return fail(error, Kind::Rejected, where + " bricht die Verbindung ab – ist der eigene Fingerabdruck dort "
                                                       "eingetragen? (" + winhttp_error_text(err) + ")");
        default:
            return fail(error, Kind::Connect, "Verbindung zu " + where + " gescheitert – " + winhttp_error_text(err));
        }
    }
    DWORD status = 0;
    DWORD size = sizeof status;
    WinHttpQueryHeaders(request.get(), WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX);
    if (status != 101) {
        return fail(error, Kind::Rejected, where + " lehnt die Verbindung ab (HTTP " + std::to_string(status) +
                                               ") – Adresse prüfen (Pfad /kanal/v1)");
    }
    wchar_t proto[128]{};
    DWORD proto_len = sizeof proto;
    const BOOL has_proto = WinHttpQueryHeaders(request.get(), WINHTTP_QUERY_CUSTOM, L"Sec-WebSocket-Protocol", proto,
                                               &proto_len, WINHTTP_NO_HEADER_INDEX);
    if (!has_proto || narrow(std::wstring_view(proto, proto_len / sizeof(wchar_t))) != o.protocol) {
        return fail(error, Kind::Rejected, where + " spricht nicht " + o.protocol);
    }
    if (secure) {
        // Zweite Prüfung vor dem Abschluss des Upgrades – die erste muss gelaufen sein und dasselbe sagen.
        const std::string pin = server_pin(request.get());
        if (!check.checked || pin.empty() || !pin_listed(pin, pins)) {
            error.found_pin = pin;
            return fail(error, Kind::PinMismatch,
                        "Fingerabdruck des Servers " + where + " passt nicht (gezeigt " +
                            (pin.empty() ? std::string("kein lesbares Zertifikat") : short_pin(pin)) +
                            ") – abgebrochen vor der ersten Nachricht");
        }
        // Der WebSocket soll weder Rückruf noch Kontext erben (der Kontext liegt auf diesem Stapel).
        WinHttpSetStatusCallback(request.get(), nullptr, 0, 0);
        DWORD_PTR zero = 0;
        WinHttpSetOption(request.get(), WINHTTP_OPTION_CONTEXT_VALUE, &zero, sizeof zero);
    }
    HINTERNET socket = WinHttpWebSocketCompleteUpgrade(request.get(), 0);
    if (socket == nullptr) {
        return fail(error, Kind::Rejected, "WebSocket-Upgrade bei " + where + " gescheitert – " +
                                               winhttp_error_text(GetLastError()));
    }
    request.close();
    // Lebenszeichen: unaufgeforderter Pong (nur am WebSocket-Handle setzbar, mindestens 15 s – gemessen).
    DWORD keepalive = static_cast<DWORD>(std::max(15000, o.keepalive_ms));
    if (!WinHttpSetOption(socket, WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL, &keepalive, sizeof keepalive)) {
        log::warn("Fernverbindung: Keepalive nicht einstellbar (" + winhttp_error_text(GetLastError()) +
                  ") – WinHTTP-Vorgabe 30 s");
    }
    return std::make_unique<WinHttpWs>(std::move(session), std::move(connect), socket, o.max_message_bytes);
}

}  // namespace swbh::win
