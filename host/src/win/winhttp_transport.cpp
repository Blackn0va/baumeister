// WinHTTP-Transport: Session, Anfrage, eigene Umleitungen, Proxy-Anmeldung,
// Fehlerdiagnose (Architektur §9, Probe nat (b)). Vertrag in http.hpp.

#include "win/winhttp_internal.hpp"

#include "base/log.hpp"
#include "base/pytext.hpp"
#include "base/utf8.hpp"
#include "win/tls_diag.hpp"

#include <wincrypt.h>

#include <algorithm>
#include <charconv>
#include <limits>

namespace swbh::win {
namespace detail {
namespace {

// Statusrückruf: sammelt die SECURE_FAILURE-Flags (Ursache hinter 12175, Probe
// nat (b)) und meldet dem Wächter die Phase, damit jede Operation ihre eigene
// Frist bekommt. Bei synchronen Anfragen ruft WinHTTP ihn im aufrufenden Thread.
constexpr DWORD kCallbackFlags = WINHTTP_CALLBACK_FLAG_SECURE_FAILURE | WINHTTP_CALLBACK_FLAG_RESOLVE_NAME |
                                 WINHTTP_CALLBACK_FLAG_CONNECT_TO_SERVER | WINHTTP_CALLBACK_FLAG_SEND_REQUEST |
                                 WINHTTP_CALLBACK_FLAG_RECEIVE_RESPONSE;

void CALLBACK status_callback(HINTERNET, DWORD_PTR context, DWORD status, LPVOID info, DWORD info_len) {
    if (context == 0) {
        return;
    }
    auto* ctx = reinterpret_cast<CallbackContext*>(context);
    using Phase = Watchdog::Phase;
    if (status == WINHTTP_CALLBACK_STATUS_SECURE_FAILURE) {
        if (info != nullptr && info_len >= sizeof(DWORD)) {
            ctx->secure_flags |= *static_cast<const DWORD*>(info);
        }
        return;
    }
    if (ctx->watchdog == nullptr) {
        return;
    }
    switch (status) {
    case WINHTTP_CALLBACK_STATUS_RESOLVING_NAME:
        ctx->watchdog->phase(Phase::Resolve);
        break;
    case WINHTTP_CALLBACK_STATUS_CONNECTING_TO_SERVER:
        ctx->watchdog->phase(Phase::Connect);
        break;
    case WINHTTP_CALLBACK_STATUS_SENDING_REQUEST:
        ctx->watchdog->phase(Phase::Send);
        break;
    case WINHTTP_CALLBACK_STATUS_REQUEST_SENT:
    case WINHTTP_CALLBACK_STATUS_RECEIVING_RESPONSE:
    case WINHTTP_CALLBACK_STATUS_RESPONSE_RECEIVED:
        // Jedes empfangene Stück startet die Empfangsfrist neu (urllib: je recv).
        ctx->watchdog->phase(Phase::Receive);
        break;
    default:
        break;
    }
}

const char* phase_text(Watchdog::Phase p) {
    switch (p) {
    case Watchdog::Phase::Resolve:
        return "beim Auflösen des Namens";
    case Watchdog::Phase::Connect:
        return "beim Verbindungsaufbau";
    case Watchdog::Phase::Send:
        return "beim Senden";
    default:
        return "beim Warten auf Daten";
    }
}

bool is_token_char(char c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
        return true;
    }
    return std::string_view("!#$%&'*+-.^_`|~").find(c) != std::string_view::npos;
}

bool valid_token(std::string_view s) {
    return !s.empty() && std::all_of(s.begin(), s.end(), is_token_char);
}

// Druckbares ASCII und Tab. CR/LF würden weitere Kopfzeilen einschleusen; Bytes
// über 0x7F setzt WinHTTP je nach Codepage um – beides lieber ablehnen.
bool valid_header_value(std::string_view s) {
    return std::all_of(s.begin(), s.end(), [](char c) {
        const auto u = static_cast<unsigned char>(c);
        return u == '\t' || (u >= 0x20 && u <= 0x7E);
    });
}

// Pfad und Query exakt so, wie sie gesendet werden: bereits kodierte %XX bleiben,
// Leerraum, Steuerzeichen, Nicht-ASCII und in URLs unsichere Zeichen werden
// prozentkodiert. WINHTTP_FLAG_ESCAPE_DISABLE verhindert, dass WinHTTP danach
// noch einmal (codepageabhängig) umkodiert.
std::string encode_request_target(std::string_view path_and_query) {
    static constexpr char kHex[] = "0123456789ABCDEF";
    std::string out;
    out.reserve(path_and_query.size());
    for (const char c : path_and_query) {
        const auto u = static_cast<unsigned char>(c);
        const bool unsafe = u <= 0x20 || u >= 0x7F || std::string_view("\"<>\\^`{|}").find(c) != std::string_view::npos;
        if (unsafe) {
            out.push_back('%');
            out.push_back(kHex[u >> 4]);
            out.push_back(kHex[u & 0x0F]);
        } else {
            out.push_back(c);
        }
    }
    return out;
}

std::string origin_of(const ParsedUrl& u) { return u.scheme + "://" + u.host + ":" + std::to_string(u.port); }

std::string url_of(const ParsedUrl& u) {
    const bool default_port = (u.scheme == "https" && u.port == 443) || (u.scheme == "http" && u.port == 80);
    std::string s = u.scheme + "://" + u.host;
    if (!default_port) {
        s += ":" + std::to_string(u.port);
    }
    return s + u.path_and_query;
}

// Location relativ zur aktuellen URL auflösen (RFC 3986 §5.2 vereinfacht:
// absolut, schema-relativ "//host/…", pfad-absolut "/…" oder pfad-relativ).
std::optional<std::string> resolve_location(const ParsedUrl& base, std::string_view location) {
    std::string_view loc = location;
    while (!loc.empty() && (loc.front() == ' ' || loc.front() == '\t')) {
        loc.remove_prefix(1);
    }
    while (!loc.empty() && (loc.back() == ' ' || loc.back() == '\t')) {
        loc.remove_suffix(1);
    }
    if (loc.empty()) {
        return std::nullopt;
    }
    // Absolut nur mit Schema AM ANFANG (RFC 3986 §3.1: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"). Vorher
    // zählte „://“ irgendwo: „/resolve?src=https://cdn/x“ kam wörtlich zurück, parse_url las „/resolve?src=https“ als
    // Schema, und eine erlaubte Umleitung auf denselben Host brach mit „Ungültiges Umleitungsziel“ ab. Wer das
    // zurückdreht, bricht jeden Download, dessen CDN eine Adresse unkodiert im Query weiterreicht.
    const std::size_t colon = loc.find(':');
    const std::size_t stop = loc.find_first_of("/?#");
    if (colon != std::string_view::npos && (stop == std::string_view::npos || colon < stop) &&
        loc.substr(colon, 3) == "://") {
        return std::string(loc);
    }
    if (loc.size() >= 2 && loc[0] == '/' && loc[1] == '/') {
        return base.scheme + ":" + std::string(loc);
    }
    const std::string origin = url_of(ParsedUrl{base.scheme, base.host, base.port, "/"});
    const std::string origin_no_slash = origin.substr(0, origin.size() - 1);
    if (loc.front() == '/') {
        return origin_no_slash + std::string(loc);
    }
    std::string dir = base.path_and_query;
    const std::size_t query = dir.find('?');
    if (query != std::string::npos) {
        dir.resize(query);
    }
    const std::size_t slash = dir.rfind('/');
    dir = slash == std::string::npos ? std::string("/") : dir.substr(0, slash + 1);
    if (loc.front() == '?') {
        std::string path = base.path_and_query.substr(0, base.path_and_query.find('?'));
        return origin_no_slash + path + std::string(loc);
    }
    return origin_no_slash + dir + std::string(loc);
}

bool scheme_allowed(const ParsedUrl& u, bool allow_plain_http, const TransportOptions& options) {
    if (u.scheme == "https") {
        return true;
    }
    if (allow_plain_http) {
        return true;
    }
    return options.allow_plain_http_loopback && is_loopback_host(u.host);
}

std::wstring query_raw_headers(HINTERNET request) {
    DWORD size = 0;
    WinHttpQueryHeaders(request, WINHTTP_QUERY_RAW_HEADERS_CRLF, WINHTTP_HEADER_NAME_BY_INDEX, WINHTTP_NO_OUTPUT_BUFFER,
                        &size, WINHTTP_NO_HEADER_INDEX);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0) {
        return {};
    }
    std::wstring raw(size / sizeof(wchar_t) + 1, L'\0');
    if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_RAW_HEADERS_CRLF, WINHTTP_HEADER_NAME_BY_INDEX, raw.data(), &size,
                             WINHTTP_NO_HEADER_INDEX)) {
        return {};
    }
    raw.resize(size / sizeof(wchar_t));
    return raw;
}

std::string trim_ows(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) {
        s.remove_prefix(1);
    }
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) {
        s.remove_suffix(1);
    }
    return std::string(s);
}

std::vector<std::pair<std::string, std::string>> parse_raw_headers(std::wstring_view raw) {
    std::vector<std::pair<std::string, std::string>> out;
    const std::string text = narrow(raw);
    std::size_t pos = 0;
    bool first = true;
    while (pos < text.size()) {
        std::size_t end = text.find("\r\n", pos);
        if (end == std::string::npos) {
            end = text.size();
        }
        const std::string_view line(text.data() + pos, end - pos);
        pos = end + 2;
        if (first) {
            first = false;  // Statuszeile
            continue;
        }
        if (line.empty()) {
            continue;
        }
        const std::size_t colon = line.find(':');
        if (colon == std::string_view::npos) {
            continue;
        }
        out.emplace_back(ascii_lower(trim_ows(line.substr(0, colon))), trim_ows(line.substr(colon + 1)));
    }
    return out;
}

std::optional<std::string> last_header(const std::vector<std::pair<std::string, std::string>>& headers,
                                       std::string_view lower_name) {
    std::optional<std::string> found;
    for (const auto& h : headers) {
        if (h.first == lower_name) {
            found = h.second;
        }
    }
    return found;
}

std::string cert_issuer(HINTERNET request) {
    if (request == nullptr) {
        return {};
    }
    PCCERT_CONTEXT cert = nullptr;
    DWORD size = sizeof(cert);
    // Gemessen: Auch nach 12175 liefert WinHTTP das Serverzertifikat (badssl.com).
    if (!WinHttpQueryOption(request, WINHTTP_OPTION_SERVER_CERT_CONTEXT, &cert, &size) || cert == nullptr) {
        return {};
    }
    wchar_t issuer[256] = {};
    const DWORD n = CertGetNameStringW(cert, CERT_NAME_SIMPLE_DISPLAY_TYPE, CERT_NAME_ISSUER_FLAG, nullptr, issuer,
                                       static_cast<DWORD>(std::size(issuer)));
    CertFreeCertificateContext(cert);
    if (n <= 1) {
        return {};
    }
    return narrow(std::wstring_view(issuer, n - 1));
}

[[noreturn]] void throw_transport_error(DWORD code, const ParsedUrl& target, bool body_started,
                                        const CancelToken* cancel, HINTERNET request_for_tls, DWORD tls_flags) {
    throw_if_cancelled(cancel);
    const HttpError::Kind kind = classify_winhttp_error(code, body_started);
    const std::string text = winhttp_error_text(code);
    const std::string where = host_port(target);
    std::string message;
    std::string issuer;
    switch (kind) {
    case HttpError::Kind::Resolve:
        message = "Name nicht auflösbar: " + target.host + " – " + text;
        break;
    case HttpError::Kind::Connect:
        message = "Verbindung zu " + where + " nicht möglich – " + text;
        break;
    case HttpError::Kind::Timeout:
        message = "Zeitüberschreitung bei " + where + " – " + text;
        break;
    case HttpError::Kind::Tls:
        issuer = cert_issuer(request_for_tls);
        message = "TLS-Fehler bei " + where + ": " + tls_failure_reason(tls_flags);
        if (!issuer.empty()) {
            message += ", Aussteller „" + issuer + "“";
        }
        message += " – " + text;
        break;
    case HttpError::Kind::Cancelled:
        message = "Anfrage an " + where + " abgebrochen";
        break;
    case HttpError::Kind::Protocol:
        message = "Ungültige Antwort von " + where + " – " + text;
        break;
    case HttpError::Kind::Redirect:
        message = "Umleitung bei " + where + " gescheitert – " + text;
        break;
    default:
        message = "HTTP-Fehler bei " + where + " – " + text;
        break;
    }
    HttpError error(kind, message, code);
    error.tls_flags = tls_flags;
    error.cert_issuer = issuer;
    throw error;
}

[[noreturn]] void throw_watchdog_timeout(const ParsedUrl& target, const Watchdog& watchdog) {
    throw HttpError(HttpError::Kind::Timeout,
                    "Zeitüberschreitung bei " + host_port(target) + " " + phase_text(watchdog.fired_phase()) + " (" +
                        std::to_string(watchdog.fired_ms()) + " ms)",
                    ERROR_WINHTTP_TIMEOUT);
}

void set_option_dword(HINTERNET h, DWORD option, DWORD value) {
    WinHttpSetOption(h, option, &value, sizeof(value));
}

// Antwort auf eine 407 des Proxys vorbereiten. true = neu senden.
bool prepare_proxy_auth(HINTERNET request, const ProxyChoice& proxy) {
    DWORD supported = 0;
    DWORD first = 0;
    DWORD target = 0;
    if (!WinHttpQueryAuthSchemes(request, &supported, &first, &target) || target != WINHTTP_AUTH_TARGET_PROXY) {
        return false;
    }
    if (!proxy.user.empty()) {
        // Zugangsdaten aus der Proxy-URL: stärkstes angebotenes Verfahren.
        const std::wstring user = widen(proxy.user);
        const std::wstring password = widen(proxy.password);
        for (const DWORD scheme : {static_cast<DWORD>(WINHTTP_AUTH_SCHEME_NEGOTIATE),
                                   static_cast<DWORD>(WINHTTP_AUTH_SCHEME_NTLM),
                                   static_cast<DWORD>(WINHTTP_AUTH_SCHEME_DIGEST),
                                   static_cast<DWORD>(WINHTTP_AUTH_SCHEME_BASIC)}) {
            if ((supported & scheme) != 0) {
                return WinHttpSetCredentials(request, WINHTTP_AUTH_TARGET_PROXY, scheme, user.c_str(),
                                             password.c_str(), nullptr) != FALSE;
            }
        }
        return false;
    }
    // Ohne Zugangsdaten nur die Windows-Anmeldung (Firmenproxy mit Kerberos/NTLM).
    // Basic/Digest ginge nur mit Kennwort – dann bleibt es bei der 407.
    for (const DWORD scheme :
         {static_cast<DWORD>(WINHTTP_AUTH_SCHEME_NEGOTIATE), static_cast<DWORD>(WINHTTP_AUTH_SCHEME_NTLM)}) {
        if ((supported & scheme) != 0) {
            return WinHttpSetCredentials(request, WINHTTP_AUTH_TARGET_PROXY, scheme, nullptr, nullptr, nullptr) !=
                   FALSE;
        }
    }
    return false;
}

}  // namespace

void throw_if_cancelled(const CancelToken* cancel) {
    if (cancel != nullptr && cancel->cancelled()) {
        throw HttpError(HttpError::Kind::Cancelled, "Anfrage abgebrochen", ERROR_WINHTTP_OPERATION_CANCELLED);
    }
}

std::string host_port(const ParsedUrl& target) { return target.host + ":" + std::to_string(target.port); }

bool is_redirect_status(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
}

Watchdog::Watchdog(std::shared_ptr<RequestHandles> handles, HttpTimeouts timeouts)
    : handles_(std::move(handles)), timeouts_(timeouts) {
    timer_ = CreateThreadpoolTimer(&Watchdog::on_timer, this, nullptr);
    if (timer_ == nullptr) {
        // Ohne Timer bleibt nur WinHTTPs eigene (grobe) Frist – kein Grund, die
        // Anfrage scheitern zu lassen.
        log::warn("WinHTTP: Threadpool-Timer nicht verfügbar, Fristen nur über WinHTTP (Fehler " +
                  std::to_string(GetLastError()) + ")");
    }
}

Watchdog::~Watchdog() {
    if (timer_ != nullptr) {
        end();
        CloseThreadpoolTimer(timer_);
    }
}

int Watchdog::limit_for(Phase p) const {
    switch (p) {
    case Phase::Resolve:
        return timeouts_.resolve_ms;
    case Phase::Connect:
        return timeouts_.connect_ms;
    case Phase::Send:
        return timeouts_.send_ms;
    default:
        return timeouts_.receive_ms;
    }
}

void Watchdog::set_timer_locked(Phase p) {
    current_ = p;
    current_ms_ = limit_for(p);
    if (timer_ == nullptr) {
        return;
    }
    if (current_ms_ <= 0) {
        // 0 = ohne Frist (WinHTTP-Vorgabe für die Namensauflösung, wie urllib).
        SetThreadpoolTimer(timer_, nullptr, 0, 0);
        return;
    }
    // Relative Zeit in 100-ns-Schritten, negativ; Fenster 0 = keine Bündelung.
    ULARGE_INTEGER due{};
    due.QuadPart = static_cast<ULONGLONG>(-static_cast<LONGLONG>(current_ms_) * 10000LL);
    FILETIME ft{};
    ft.dwLowDateTime = due.LowPart;
    ft.dwHighDateTime = due.HighPart;
    SetThreadpoolTimer(timer_, &ft, 0, 0);
}

void Watchdog::begin(Phase p) {
    std::lock_guard<std::mutex> lock(mutex_);
    active_ = true;
    set_timer_locked(p);
}

void Watchdog::phase(Phase p) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (active_ && !fired_) {
        set_timer_locked(p);
    }
}

bool Watchdog::end() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        active_ = false;
        if (timer_ != nullptr) {
            SetThreadpoolTimer(timer_, nullptr, 0, 0);
        }
    }
    // Außerhalb der Sperre warten: Der Timer-Rückruf nimmt sie ebenfalls.
    if (timer_ != nullptr) {
        WaitForThreadpoolTimerCallbacks(timer_, TRUE);
    }
    return fired_.load();
}

void CALLBACK Watchdog::on_timer(PTP_CALLBACK_INSTANCE, PVOID context, PTP_TIMER) {
    auto* self = static_cast<Watchdog*>(context);
    {
        std::lock_guard<std::mutex> lock(self->mutex_);
        if (!self->active_) {
            return;  // gerade beendet – nichts schließen
        }
        self->fired_phase_ = self->current_;
        self->fired_ms_ = self->current_ms_;
        self->fired_ = true;
    }
    self->handles_->close();
}

OpenResponse::OpenResponse(CancelToken* cancel, ParsedUrl t, HttpTimeouts timeouts)
    : handles(std::make_shared<RequestHandles>()), target(std::move(t)), cancel_(cancel) {
    watchdog = std::make_unique<Watchdog>(handles, timeouts);
    context.watchdog = watchdog.get();
}

OpenResponse::~OpenResponse() {
    // Reihenfolge: Abbruch abmelden, Handles schließen (danach kommen keine
    // Statusrückrufe mehr, die context.watchdog benutzen), zuletzt den Wächter
    // abbauen (wartet einen laufenden Timer-Rückruf ab). close() ist idempotent,
    // falls Abbruch oder Wächter parallel schließen.
    if (armed_ && cancel_ != nullptr) {
        cancel_->remove_cancel_action(cancel_id_);
    }
    handles->close();
    context.watchdog = nullptr;
    watchdog.reset();
}

void OpenResponse::arm_cancel() {
    if (cancel_ == nullptr || armed_) {
        return;
    }
    std::shared_ptr<RequestHandles> h = handles;
    // Ist die Marke schon gesetzt, läuft die Aktion sofort; der nächste
    // WinHTTP-Aufruf scheitert und throw_if_cancelled meldet Cancelled.
    cancel_id_ = cancel_->add_cancel_action([h] { h->close(); });
    armed_ = true;
}

std::optional<std::string> OpenResponse::header(std::string_view lower_name) const {
    return last_header(headers, lower_name);
}

std::size_t OpenResponse::read(char* buffer, std::size_t n) {
    // Vor jedem Aufruf prüfen: Nach einem Abbruch ist das Handle geschlossen und
    // darf nicht mehr benutzt werden. Ein Abbruch genau zwischen Prüfung und
    // Aufruf trifft ein geschlossenes Handle; WinHTTP meldet dann einen Fehler,
    // der unten als Cancelled erkannt wird.
    throw_if_cancelled(cancel_);
    // Erst QueryDataAvailable, dann genau so viel lesen: Synchrones
    // WinHttpReadData wartet, bis der ganze Puffer voll ist (gemessen: 2000 Byte
    // bei 1 KB/s → 2,1 s). Fortschritt, Mindestrate und die Frist je Operation
    // brauchen aber jede Teillieferung (QueryDataAvailable: nach ~0,1 s).
    DWORD available = 0;
    watchdog->begin(Watchdog::Phase::Receive);
    const BOOL ok = WinHttpQueryDataAvailable(handles->request, &available);
    const DWORD err = ok ? 0 : GetLastError();
    if (watchdog->end()) {
        throw_if_cancelled(cancel_);
        throw_watchdog_timeout(target, *watchdog);
    }
    if (!ok) {
        throw_transport_error(err, target, true, cancel_, nullptr, 0);
    }
    if (available == 0) {
        return 0;  // Ende des Bodys
    }
    DWORD got = 0;
    const DWORD want = static_cast<DWORD>(std::min<std::size_t>({n, available, std::size_t{1} << 30}));
    if (!WinHttpReadData(handles->request, buffer, want, &got)) {
        throw_transport_error(GetLastError(), target, true, cancel_, nullptr, 0);
    }
    return got;
}

std::string normalize_etag(std::string_view raw) {
    std::string_view v = raw;
    while (!v.empty() && (v.front() == ' ' || v.front() == '\t')) {
        v.remove_prefix(1);
    }
    while (!v.empty() && (v.back() == ' ' || v.back() == '\t')) {
        v.remove_suffix(1);
    }
    if (v.size() >= 2 && (v[0] == 'W' || v[0] == 'w') && v[1] == '/') {
        v.remove_prefix(2);
    }
    if (v.size() >= 2 && v.front() == '"' && v.back() == '"') {
        v = v.substr(1, v.size() - 2);
    }
    return ascii_lower(v);
}

std::optional<std::uint64_t> parse_u64(std::string_view s) {
    std::uint64_t value = 0;
    const auto res = std::from_chars(s.data(), s.data() + s.size(), value);
    if (s.empty() || res.ec != std::errc{} || res.ptr != s.data() + s.size()) {
        return std::nullopt;
    }
    return value;
}

std::unique_ptr<OpenResponse> open_request(SessionPool& sessions, const RequestSpec& spec, CancelToken* cancel,
                                           const TransportOptions& options) {
    // Regelprüfungen vor jeder Verbindung (fail-closed, nichts verlässt den Rechner).
    if (!valid_token(spec.method)) {
        throw HttpError(HttpError::Kind::Other, "Ungültige HTTP-Methode");
    }
    if (spec.user_agent.empty() || !valid_header_value(spec.user_agent)) {
        throw HttpError(HttpError::Kind::Other, "HTTP-Anfrage ohne gültigen User-Agent");
    }
    for (const auto& h : spec.headers) {
        if (!valid_token(h.first) || !valid_header_value(h.second)) {
            throw HttpError(HttpError::Kind::Other, "Ungültige Kopfzeile: " + log::safe(h.first, 60));
        }
        const std::string lower = ascii_lower(h.first);
        // Diese Kopfzeilen setzt der Transport selbst; ein zweiter Wert vom
        // Aufrufer führte zu widersprüchlichen Anfragen.
        if (lower == "content-length" || lower == "transfer-encoding" || lower == "host" || lower == "user-agent") {
            throw HttpError(HttpError::Kind::Other, "Kopfzeile wird vom Transport gesetzt: " + h.first);
        }
    }
    if (!spec.range.empty() && !valid_header_value(spec.range)) {
        throw HttpError(HttpError::Kind::Other, "Ungültiger Range-Wert");
    }
    if (spec.body.size() > std::numeric_limits<DWORD>::max()) {
        throw HttpError(HttpError::Kind::Other, "HTTP-Body zu groß");
    }

    auto first = parse_url(spec.url);
    if (!first) {
        throw HttpError(HttpError::Kind::Other, "Ungültige URL");
    }
    if (!scheme_allowed(*first, spec.allow_plain_http, options)) {
        throw HttpError(HttpError::Kind::Scheme, "Nur https erlaubt (" + first->host + ")");
    }

    const bool follow =
        spec.redirects == Redirects::HttpsAllowList && (spec.method == "GET" || spec.method == "HEAD");
    const std::string first_origin = origin_of(*first);
    bool strip_custom_headers = false;
    int redirect_count = 0;
    std::optional<std::string> linked_etag;
    std::optional<std::string> linked_size;
    ParsedUrl target = *first;

    for (;;) {
        throw_if_cancelled(cancel);
        const ProxyChoice proxy = choose_proxy(target, read_proxy_env());
        if (!proxy.error.empty()) {
            throw HttpError(HttpError::Kind::Other, "Proxy-Einstellung (HTTPS_PROXY/HTTP_PROXY) unbrauchbar: " +
                                                        proxy.error);
        }

        HINTERNET session = sessions.get(proxy);
        auto response = std::make_unique<OpenResponse>(cancel, target, spec.timeouts);
        RequestHandles& h = *response->handles;
        h.connect = WinHttpConnect(session, widen(target.host).c_str(), static_cast<INTERNET_PORT>(target.port), 0);
        if (h.connect == nullptr) {
            throw_transport_error(GetLastError(), target, false, cancel, nullptr, 0);
        }
        const bool secure = target.scheme == "https";
        h.request = WinHttpOpenRequest(h.connect, widen(spec.method).c_str(),
                                       widen(encode_request_target(target.path_and_query)).c_str(), nullptr,
                                       WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                       WINHTTP_FLAG_ESCAPE_DISABLE | (secure ? WINHTTP_FLAG_SECURE : 0));
        if (h.request == nullptr) {
            throw_transport_error(GetLastError(), target, false, cancel, nullptr, 0);
        }

        // Timeouts je Operation, wie urllib (kein Gesamt-Timeout).
        if (!WinHttpSetTimeouts(h.request, spec.timeouts.resolve_ms, spec.timeouts.connect_ms, spec.timeouts.send_ms,
                                spec.timeouts.receive_ms)) {
            throw_transport_error(GetLastError(), target, false, cancel, nullptr, 0);
        }
        // Umleitungen folgen wir nur selbst (Regeln in http.hpp); Cookies kennt
        // die Python-Version nicht und sie sollen nicht still mitreisen.
        set_option_dword(h.request, WINHTTP_OPTION_DISABLE_FEATURE, WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES);
        // Windows-Anmeldung höchstens an einen Proxy, nie an Zielserver. Ältere
        // Windows-Versionen kennen PROXY_ONLY nicht; dann bleibt die Vorgabe MEDIUM,
        // die ohnehin nur auf WinHttpSetCredentials(NULL) hin anmeldet.
        set_option_dword(h.request, WINHTTP_OPTION_AUTOLOGON_POLICY, WINHTTP_AUTOLOGON_SECURITY_LEVEL_PROXY_ONLY);

        WinHttpSetStatusCallback(h.request, status_callback, kCallbackFlags, 0);

        std::string header_block = "User-Agent: " + spec.user_agent + "\r\n";
        bool caller_encoding = false;
        for (const auto& hdr : spec.headers) {
            const std::string lower = ascii_lower(hdr.first);
            if (strip_custom_headers && lower != "range" && lower != "accept") {
                continue;
            }
            caller_encoding = caller_encoding || lower == "accept-encoding";
            header_block += hdr.first + ": " + hdr.second + "\r\n";
        }
        // Wie urllib der Python-Fassung (spec 03): ausdrücklich keine Kompression. Ohne
        // Kopf heißt es nach RFC 9110 §12.5.3 „jede Kodierung annehmbar“; eine
        // Zwischenstation dürfte dann brotli/gzip liefern, und weil WinHTTP hier ohne
        // WINHTTP_OPTION_DECOMPRESSION läuft, wären JSON, Modelle und Setups unlesbar
        // (Befund protokolle). Setzt der Aufrufer den Kopf selbst, gilt seiner.
        if (!caller_encoding) {
            header_block += "Accept-Encoding: identity\r\n";
        }
        if (!spec.range.empty()) {
            header_block += "Range: " + spec.range + "\r\n";
        }
        const std::wstring header_wide = widen(header_block);
        if (!WinHttpAddRequestHeaders(h.request, header_wide.c_str(), static_cast<DWORD>(header_wide.size()),
                                      WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE)) {
            throw_transport_error(GetLastError(), target, false, cancel, nullptr, 0);
        }

        response->arm_cancel();

        int auth_attempts = 0;
        DWORD status = 0;
        for (;;) {
            throw_if_cancelled(cancel);
            // Body in einem Stück: WinHTTP setzt damit Content-Length (nie chunked).
            const DWORD body_size = static_cast<DWORD>(spec.body.size());
            void* body_ptr = body_size > 0 ? const_cast<char*>(spec.body.data()) : WINHTTP_NO_REQUEST_DATA;
            // Genaue Fristen je Phase über die Statusrückrufe (siehe Watchdog);
            // SendRequest wartet selbst schon auf den Beginn der Antwort.
            response->watchdog->begin(Watchdog::Phase::Connect);
            BOOL ok = WinHttpSendRequest(h.request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, body_ptr, body_size, body_size,
                                         reinterpret_cast<DWORD_PTR>(&response->context));
            if (ok) {
                ok = WinHttpReceiveResponse(h.request, nullptr);
            }
            const DWORD err = ok ? 0 : GetLastError();
            if (response->watchdog->end()) {
                throw_if_cancelled(cancel);
                throw_watchdog_timeout(target, *response->watchdog);
            }
            if (!ok) {
                throw_transport_error(err, target, false, cancel, h.request, response->context.secure_flags.load());
            }
            DWORD size = sizeof(status);
            if (!WinHttpQueryHeaders(h.request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                     WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX)) {
                throw_transport_error(GetLastError(), target, false, cancel, nullptr, 0);
            }
            if (status == HTTP_STATUS_PROXY_AUTH_REQ && auth_attempts == 0 &&
                proxy.mode != ProxyChoice::Mode::Direct && prepare_proxy_auth(h.request, proxy)) {
                ++auth_attempts;
                continue;
            }
            break;
        }

        response->status = static_cast<int>(status);
        response->headers = parse_raw_headers(query_raw_headers(h.request));
        response->final_url = url_of(target);
        if (!linked_etag) {
            linked_etag = response->header("x-linked-etag");
        }
        if (!linked_size) {
            linked_size = response->header("x-linked-size");
        }
        // HF-Prüfung noch VOR dem Folgen der Umleitung: Passt der LFS-Hash nicht,
        // wird der CDN-Host gar nicht erst gefragt (spart die signierte URL und Bytes).
        if (!spec.expected_linked_etag.empty()) {
            if (const auto etag = response->header("x-linked-etag");
                etag && normalize_etag(*etag) != spec.expected_linked_etag) {
                throw DownloadError(DownloadError::Kind::EtagMismatch,
                                    "X-Linked-ETag von " + host_port(target) + " passt nicht zum erwarteten SHA-256");
            }
        }
        if (spec.expected_linked_size > 0) {
            if (const auto size = response->header("x-linked-size")) {
                const auto value = parse_u64(*size);
                if (!value || *value != spec.expected_linked_size) {
                    throw DownloadError(DownloadError::Kind::SizeMismatch,
                                        "X-Linked-Size von " + host_port(target) + " passt nicht zur erwarteten Größe " +
                                            std::to_string(spec.expected_linked_size));
                }
            }
        }

        if (follow && is_redirect_status(response->status)) {
            const auto location = response->header("location");
            if (!location) {
                throw HttpError(HttpError::Kind::Redirect,
                                "Umleitung ohne Ziel von " + host_port(target) + " (HTTP " +
                                    std::to_string(response->status) + ")");
            }
            const auto next_url = resolve_location(target, *location);
            const auto next = next_url ? parse_url(*next_url) : std::nullopt;
            if (!next) {
                throw HttpError(HttpError::Kind::Redirect, "Ungültiges Umleitungsziel von " + host_port(target));
            }
            // Nur https (bzw. Loopback-http im Testmodus), nie das allow_plain_http
            // des Aufrufers: Eine Umleitung darf nie auf Klartext herabstufen.
            if (!(next->scheme == "https" || (options.allow_plain_http_loopback && is_loopback_host(next->host)))) {
                throw HttpError(HttpError::Kind::Redirect, "Umleitung auf unverschlüsselte Verbindung abgelehnt (" +
                                                               next->host + ")");
            }
            if (!host_allowed(next->host, spec.allow_list)) {
                throw HttpError(HttpError::Kind::Redirect, "Umleitung zu nicht erlaubtem Host abgelehnt: " + next->host);
            }
            if (++redirect_count > spec.max_redirects) {
                throw HttpError(HttpError::Kind::Redirect,
                                "Zu viele Umleitungen (mehr als " + std::to_string(spec.max_redirects) + ")");
            }
            if (origin_of(*next) != first_origin) {
                // Ab dem ersten Wechsel des Ursprungs gehen eigene Kopfzeilen
                // (Token!) nie wieder mit, auch nicht bei einer Rückkehr.
                strip_custom_headers = true;
            }
            target = *next;
            continue;  // response wird hier zerstört: abmelden + Handles schließen
        }

        response->linked_etag = linked_etag;
        response->linked_size = linked_size;
        if (const auto cl = response->header("content-length")) {
            response->content_length = parse_u64(*cl);
        }
        return response;
    }
}

SessionPool::SessionPool() {
    // AUTOMATIC_PROXY (ab Windows 8.1) nimmt die Internet-Einstellungen des
    // Benutzers samt PAC/WPAD. Sofort anlegen, damit ein kaputtes WinHTTP beim
    // Erzeugen des Transports auffällt und nicht erst bei der ersten Anfrage.
    sessions_.emplace_back("auto", open(WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, std::wstring()));
}

SessionPool::~SessionPool() {
    for (auto& entry : sessions_) {
        WinHttpCloseHandle(entry.second);
    }
}

HINTERNET SessionPool::open(DWORD access_type, const std::wstring& proxy_server) {
    // Kein Agent auf Session-Ebene: Der User-Agent kommt je Anfrage vom Aufrufer
    // (swapi "StreamWizard-Agent/0.1", Update "StreamWizardAgent/<version>").
    HINTERNET session =
        WinHttpOpen(nullptr, access_type,
                    access_type == WINHTTP_ACCESS_TYPE_NAMED_PROXY ? proxy_server.c_str() : WINHTTP_NO_PROXY_NAME,
                    WINHTTP_NO_PROXY_BYPASS, 0);
    if (session == nullptr) {
        const DWORD err = GetLastError();
        throw HttpError(HttpError::Kind::Other, "WinHTTP nicht verfügbar – " + winhttp_error_text(err), err);
    }
    // TLS 1.3 kommt nur zustande, wenn es ausdrücklich erlaubt ist (gemessen).
    // Windows-Versionen ohne TLS 1.3 lehnen das Flag ab; dann bleibt TLS 1.2 –
    // das Minimum sinkt dadurch nicht.
    DWORD protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3;
    if (!WinHttpSetOption(session, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof(protocols))) {
        protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
        if (!WinHttpSetOption(session, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof(protocols))) {
            const DWORD err = GetLastError();
            WinHttpCloseHandle(session);
            throw HttpError(HttpError::Kind::Other, "TLS 1.2 nicht einstellbar – " + winhttp_error_text(err), err);
        }
        if (!tls13_warned_) {
            tls13_warned_ = true;
            log::warn("WinHTTP: TLS 1.3 auf diesem Windows nicht verfügbar, nur TLS 1.2");
        }
    }
    return session;
}

HINTERNET SessionPool::get(const ProxyChoice& proxy) {
    std::string key;
    DWORD access_type = WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY;
    switch (proxy.mode) {
    case ProxyChoice::Mode::Direct:
        key = "direct";
        access_type = WINHTTP_ACCESS_TYPE_NO_PROXY;
        break;
    case ProxyChoice::Mode::Named:
        key = "named:" + proxy.server;
        access_type = WINHTTP_ACCESS_TYPE_NAMED_PROXY;
        break;
    default:
        key = "auto";
        break;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& entry : sessions_) {
        if (entry.first == key) {
            return entry.second;
        }
    }
    HINTERNET session = open(access_type, widen(proxy.server));
    sessions_.emplace_back(key, session);
    return session;
}

WinHttpTransport::WinHttpTransport(const TransportOptions& options) : options_(options) {}

WinHttpTransport::~WinHttpTransport() = default;

HttpResponse WinHttpTransport::send(const HttpRequest& request, CancelToken* cancel) {
    RequestSpec spec;
    spec.method = request.method;
    spec.url = request.url;
    spec.body = request.body;
    spec.user_agent = request.user_agent;
    spec.headers = request.headers;
    spec.timeouts = request.timeouts;
    spec.redirects = request.redirects;
    spec.allow_list = request.redirect_host_suffixes;
    spec.max_redirects = request.max_redirects;
    spec.allow_plain_http = request.allow_plain_http;

    auto open = open_request(sessions_, spec, cancel, options_);
    HttpResponse response;
    response.status = open->status;
    response.headers = open->headers;
    response.final_url = open->final_url;
    const bool success = response.status < 400;
    const std::string where = host_port(open->target);
    if (success && open->content_length && *open->content_length > request.max_body) {
        throw HttpError(HttpError::Kind::TooLarge, "Antwort von " + where + " zu groß (" +
                                                       std::to_string(*open->content_length) + " Bytes, erlaubt " +
                                                       std::to_string(request.max_body) + ")");
    }
    std::vector<char> buffer(64 * 1024);
    for (;;) {
        const std::size_t n = open->read(buffer.data(), buffer.size());
        if (n == 0) {
            break;
        }
        if (response.body.size() + n > request.max_body) {
            if (success) {
                throw HttpError(HttpError::Kind::TooLarge,
                                "Antwort von " + where + " zu groß (mehr als " + std::to_string(request.max_body) +
                                    " Bytes)");
            }
            // Fehlerbodies liest auch der IST nur teilweise; Rest verwerfen.
            response.body.append(buffer.data(), request.max_body - response.body.size());
            response.body_truncated = true;
            break;
        }
        response.body.append(buffer.data(), n);
    }
    const bool bodyless = request.method == "HEAD" || response.status == 204 || response.status == 304 ||
                          (response.status >= 100 && response.status < 200);
    if (!response.body_truncated && !bodyless && open->content_length &&
        response.body.size() < *open->content_length) {
        throw HttpError(HttpError::Kind::Protocol, "Verbindung zu " + where + " vor Ende des Bodys abgebrochen (" +
                                                       std::to_string(response.body.size()) + " von " +
                                                       std::to_string(*open->content_length) + " Bytes)");
    }
    return response;
}

}  // namespace detail

std::unique_ptr<HttpTransport> make_winhttp_transport() { return make_winhttp_transport(TransportOptions{}); }

std::unique_ptr<HttpTransport> make_winhttp_transport(const TransportOptions& options) {
    return std::make_unique<detail::WinHttpTransport>(options);
}

}  // namespace swbh::win
