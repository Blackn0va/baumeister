// Interne Bausteine des WinHTTP-Transports (nur winhttp_transport.cpp und
// winhttp_download.cpp binden das ein; kein Vertrag für andere Module).

#pragma once

#include "win/http.hpp"
#include "win/proxy.hpp"

#include <windows.h>
#include <winhttp.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace swbh::win::detail {

// Request- und Connect-Handle einer Anfrage. close() ist idempotent und darf aus
// jedem Thread laufen: Die Abbruch-Aktion des CancelToken und der Arbeitsthread
// rufen es beide auf, der Mutex verhindert ein doppeltes WinHttpCloseHandle
// (ein freigegebener Handle-Wert kann sonst schon einer fremden Anfrage gehören).
class RequestHandles {
public:
    RequestHandles() = default;
    RequestHandles(const RequestHandles&) = delete;
    RequestHandles& operator=(const RequestHandles&) = delete;
    ~RequestHandles() { close(); }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_) {
            return;
        }
        closed_ = true;
        if (request != nullptr) {
            WinHttpCloseHandle(request);
        }
        if (connect != nullptr) {
            WinHttpCloseHandle(connect);
        }
    }

    HINTERNET connect = nullptr;
    HINTERNET request = nullptr;

private:
    std::mutex mutex_;
    bool closed_ = false;
};

// Beschreibung einer Anfrage für open_request (gemeinsam für send und download).
struct RequestSpec {
    std::string method = "GET";
    std::string url;
    std::string_view body;
    std::string user_agent;
    std::vector<std::pair<std::string, std::string>> headers;
    std::string range;  // "bytes=N-" (nur Download) oder leer
    HttpTimeouts timeouts;
    Redirects redirects = Redirects::None;
    std::vector<std::string> allow_list;
    int max_redirects = 5;
    bool allow_plain_http = false;
    // Nur Download (HF): Weicht X-Linked-ETag bzw. X-Linked-Size irgendeiner
    // Antwort der Kette hiervon ab, bricht open_request mit DownloadError ab,
    // BEVOR der nächste Host (CDN) gefragt wird. Leer bzw. 0 = keine Prüfung.
    std::string expected_linked_etag;
    std::uint64_t expected_linked_size = 0;
};

// Genaue Frist je Operation für blockierende WinHTTP-Aufrufe.
//
// Warum: WinHTTP rastert seine Timeouts grob (gemessen auf diesem Rechner:
// Empfangs-Timeout 300 ms bis 3 s → Fehler nach 4,0 s; 4,5 s und 6 s → nach
// 8,0 s). Für das Ingest (2,5 s) und die Bridge-Config (4 s) wäre das doppelt so
// lang wie im IST (urllib: Frist je Socket-Operation). Außerdem wartet das
// synchrone WinHttpSendRequest selbst schon auf den Beginn der Antwort (gemessen:
// SendRequest kehrt erst nach 4 s zurück) – eine Frist nur um ReceiveResponse
// griffe nie. Deshalb folgt der Wächter den Statusrückrufen von WinHTTP
// (Auflösen, Verbinden, Senden, Empfangen, jedes empfangene Stück) und setzt die
// Frist der jeweiligen Phase neu – wie urllib je Socket-Operation. Läuft sie ab,
// schließt ein Threadpool-Timer die Handles (derselbe Weg wie der Abbruch) und
// der Aufrufer meldet Timeout. Rückrufe außerhalb eines blockierenden Aufrufs
// stellen nichts, damit kein Timer nachläuft. WinHttpSetTimeouts bleibt als
// Rückfall gesetzt.
class Watchdog {
public:
    enum class Phase { Resolve, Connect, Send, Receive };

    Watchdog(std::shared_ptr<RequestHandles> handles, HttpTimeouts timeouts);
    ~Watchdog();
    Watchdog(const Watchdog&) = delete;
    Watchdog& operator=(const Watchdog&) = delete;

    // Beginn eines blockierenden Aufrufs in der Phase p (Frist der Phase).
    void begin(Phase p);
    // Phasenwechsel aus dem Statusrückruf; wirkt nur während begin()…end().
    void phase(Phase p);
    // Ende des Aufrufs: stellt ab und wartet laufende Timer-Rückrufe ab.
    // true = Frist abgelaufen (die Handles sind dann geschlossen).
    bool end();

    // Phase und Frist beim Ablauf (für die Meldung).
    Phase fired_phase() const { return fired_phase_; }
    int fired_ms() const { return fired_ms_; }

private:
    static void CALLBACK on_timer(PTP_CALLBACK_INSTANCE instance, PVOID context, PTP_TIMER timer);
    int limit_for(Phase p) const;
    void set_timer_locked(Phase p);

    std::shared_ptr<RequestHandles> handles_;
    HttpTimeouts timeouts_;
    PTP_TIMER timer_ = nullptr;
    std::mutex mutex_;
    bool active_ = false;
    Phase current_ = Phase::Connect;
    int current_ms_ = 0;
    std::atomic<bool> fired_{false};
    Phase fired_phase_ = Phase::Connect;
    int fired_ms_ = 0;
};

// Kontext der Statusrückrufe einer Anfrage (dwContext von WinHttpSendRequest).
// Liegt in OpenResponse, damit die Adresse so lange gilt wie das Request-Handle.
struct CallbackContext {
    std::atomic<DWORD> secure_flags{0};
    Watchdog* watchdog = nullptr;
};

// Antwort nach allen Umleitungen mit offenem Request-Handle; der Body wird über
// read() gelesen. Der Destruktor meldet die Abbruch-Aktion ab und schließt.
class OpenResponse {
public:
    OpenResponse(CancelToken* cancel, ParsedUrl target, HttpTimeouts timeouts);
    ~OpenResponse();
    OpenResponse(const OpenResponse&) = delete;
    OpenResponse& operator=(const OpenResponse&) = delete;

    // Meldet die Abbruch-Aktion an (schließt die Handles aus dem Abbruch-Thread).
    void arm_cancel();

    // Liest bis zu n Bytes; 0 = Ende des Bodys. Wirft HttpError.
    std::size_t read(char* buffer, std::size_t n);

    std::optional<std::string> header(std::string_view lower_name) const;

    std::shared_ptr<RequestHandles> handles;
    ParsedUrl target;
    int status = 0;
    std::vector<std::pair<std::string, std::string>> headers;
    std::string final_url;
    std::optional<std::uint64_t> content_length;
    // Erste X-Linked-ETag/X-Linked-Size der Umleitungskette (HF setzt sie im 302).
    std::optional<std::string> linked_etag;
    std::optional<std::string> linked_size;
    std::unique_ptr<Watchdog> watchdog;
    CallbackContext context;

private:
    CancelToken* cancel_ = nullptr;
    std::uint64_t cancel_id_ = 0;
    bool armed_ = false;
};

// "W/\"abc\"" → "abc", klein (X-Linked-ETag trägt den LFS-SHA-256 in Anführungszeichen).
std::string normalize_etag(std::string_view raw);

// Dezimalzahl ohne Vorzeichen, sonst nullopt.
std::optional<std::uint64_t> parse_u64(std::string_view s);

// Eine WinHTTP-Session je Proxy-Modus. Warum nicht eine Session mit
// WINHTTP_OPTION_PROXY je Anfrage: Auf Anfragen einer AUTOMATIC_PROXY-Session
// ist die Option nicht setzbar (gemessen: 12011 ERROR_WINHTTP_OPTION_NOT_SETTABLE).
// Deshalb: "auto" (AUTOMATIC_PROXY), "direct" (NO_PROXY) und je Proxy-Server eine
// NAMED_PROXY-Session. Alle mit TLS 1.2/1.3. Threadsicher; Sessions leben bis
// zum Ende des Transports.
class SessionPool {
public:
    SessionPool();  // legt die automatische Session sofort an (wirft HttpError)
    ~SessionPool();
    SessionPool(const SessionPool&) = delete;
    SessionPool& operator=(const SessionPool&) = delete;

    HINTERNET get(const ProxyChoice& proxy);

private:
    HINTERNET open(DWORD access_type, const std::wstring& proxy_server);

    std::mutex mutex_;
    std::vector<std::pair<std::string, HINTERNET>> sessions_;
    bool tls13_warned_ = false;
};

// Führt die Anfrage samt eigener Umleitungs- und Proxy-Anmelde-Logik aus.
// Wirft HttpError (auch Kind::Scheme/Redirect/Other bei Regelverstößen).
std::unique_ptr<OpenResponse> open_request(SessionPool& sessions, const RequestSpec& spec, CancelToken* cancel,
                                           const TransportOptions& options);

// Wirft HttpError(Cancelled), wenn die Marke gesetzt ist.
void throw_if_cancelled(const CancelToken* cancel);

// "host:port" für Meldungen (nie die URL, siehe http.hpp).
std::string host_port(const ParsedUrl& target);

// true für 301, 302, 303, 307, 308.
bool is_redirect_status(int status);

class WinHttpTransport final : public HttpTransport {
public:
    explicit WinHttpTransport(const TransportOptions& options);
    ~WinHttpTransport() override;
    WinHttpTransport(const WinHttpTransport&) = delete;
    WinHttpTransport& operator=(const WinHttpTransport&) = delete;

    HttpResponse send(const HttpRequest& request, CancelToken* cancel) override;
    DownloadResult download(const DownloadRequest& request, const ProgressFn& progress, CancelToken& cancel) override;

private:
    SessionPool sessions_;
    TransportOptions options_;
};

}  // namespace swbh::win::detail
