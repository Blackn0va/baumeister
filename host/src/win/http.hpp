// HTTP(S) über WinHTTP/Schannel hinter einer kleinen Schnittstelle (Architektur §9).
//
// Grundsätze (alle gemessen bzw. aus der Spezifikation):
// - TLS 1.2 und 1.3 ausdrücklich (WINHTTP_OPTION_SECURE_PROTOCOLS; TLS 1.3 nur so).
//   Zertifikatsprüfung ist NIE abschaltbar – es gibt dafür keinen Schalter.
// - Proxy: HTTPS_PROXY/HTTP_PROXY/NO_PROXY aus der Umgebung als Named Proxy,
//   sonst WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY; Loopback-Ziele immer direkt
//   (Regeln und Gründe in proxy.hpp). Verlangt der Proxy Anmeldung (407): mit
//   Zugangsdaten aus der Proxy-URL Basic, sonst Negotiate/NTLM mit der
//   Windows-Anmeldung; je Anfrage höchstens ein zweiter Versuch, danach kommt 407
//   als Antwort (send) bzw. als DownloadError::HttpStatus zurück.
// - Timeouts je Operation wie die urllib-Socket-Timeouts der Python-Version –
//   kein Gesamt-Timeout (spec 03/06 Port-Risiken). WinHTTP allein rastert sie
//   grob (gemessen: 0,3 bis 3 s → 4,0 s; 4,5/6 s → 8,0 s), deshalb hält ein
//   eigener Wächter je Phase (Verbinden, Senden, Warten auf Daten; jedes
//   empfangene Stück startet die Frist neu) sie auf etwa ±20 ms genau
//   (gemessen: 500 ms → 502–514 ms). resolve_ms = 0 heißt wie in Python: DNS ohne
//   eigene Frist. Timeout → HttpError Timeout mit win32 = 12002.
// - Content-Length wird immer gesendet, nie chunked (Server-Grenzen 4096/131072
//   prüfen CONTENT_LENGTH). Bodies sind rohe Bytes (UTF-8 nicht \u-escaped).
//   Kopfzeilen müssen druckbares ASCII ohne CR/LF sein (sonst HttpError Other,
//   bevor eine Verbindung aufgebaut wird) – gegen Header-Injection über Tokens.
// - Umleitungen: Standard KEINE (WINHTTP_DISABLE_REDIRECTS). Gemessen: WinHTTP
//   reicht per WinHttpAddRequestHeaders gesetzte Kopfzeilen (X-StreamWizard-Agent-Token)
//   bei automatischer Umleitung an fremde Hosts weiter. Mit HttpsAllowList folgt
//   der Transport selbst: nur https, nur erlaubte Hosts, nur bei GET/HEAD (ein
//   POST folgt nie, der 3xx kommt als Antwort zurück), und bei Wechsel von
//   Schema/Host/Port gehen nur Range und Accept mit – eigene Kopfzeilen entfallen
//   endgültig. Damit ist die Weitergabe von Tokens an fremde Hosts konstruktiv
//   ausgeschlossen (Loopback-Test belegt das).
// - Abbruch: CancelToken schließt die WinHTTP-Handles aus einem anderen Thread
//   (gemessen: 12017 nach 0,4 ms). Joins bleiben dadurch deterministisch.
// - Zertifikatsfehler: 12175 + Flags aus WINHTTP_CALLBACK_STATUS_SECURE_FAILURE
//   (0x20 abgelaufen, 0x08 unbekannte CA, 0x10 falscher Name) und Aussteller aus
//   WINHTTP_OPTION_SERVER_CERT_CONTEXT landen in HttpError (swapi formt daraus
//   die Nutzermeldung mit dem Hinweis vorn; tls_failure_reason liefert den Grund).
// - Meldungen nennen nur Host und Port, nie die URL: signierte CDN-URLs und
//   Abfrageparameter gehören nicht in Logs.
//
// Threads: HttpTransport::send/download sind aus mehreren Threads gleichzeitig
// aufrufbar (je Aufruf eigene Request-Handles). CancelToken ist threadsicher.

#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

namespace swbh::win {

// Timeouts je Operation in ms; 0 = kein Limit (WinHTTP-Standard für DNS).
struct HttpTimeouts {
    int resolve_ms = 0;
    int connect_ms = 15000;
    int send_ms = 15000;
    int receive_ms = 30000;

    // Gleiches Limit für Verbindungsaufbau, Senden und jedes Lesen, DNS ohne
    // Limit – entspricht urllib timeout=<s> der Python-Version.
    static HttpTimeouts uniform(int ms) { return HttpTimeouts{0, ms, ms, ms}; }
};

enum class Redirects {
    None,            // 3xx wird als Antwort zurückgegeben (Aufrufer behandelt ihn als Fehler)
    HttpsAllowList,  // nur https und nur Hosts aus redirect_host_suffixes
};

struct HttpRequest {
    std::string method = "GET";
    std::string url;
    std::string body;        // mit Content-Length gesendet; leer = kein Body
    std::string user_agent;  // Pflicht, z. B. "StreamWizard-Agent/0.1"
    std::vector<std::pair<std::string, std::string>> headers;
    HttpTimeouts timeouts;
    Redirects redirects = Redirects::None;
    // Einträge: exakter Host ("huggingface.co") oder Subdomains ("*.hf.co").
    std::vector<std::string> redirect_host_suffixes;
    int max_redirects = 5;
    // http:// nur für den LLM-Client (lokaler Endpoint, Architektur §8); überall
    // sonst lehnt der Transport http mit HttpError::Kind::Scheme ab.
    bool allow_plain_http = false;
    // Obergrenze des Antwort-Bodys. Bei Status < 400 ist Überschreiten ein Fehler
    // (Kind::TooLarge); bei Fehlerstatus wird still abgeschnitten
    // (HttpResponse::body_truncated) – der IST las Fehlerbodies ebenfalls nur teilweise.
    std::size_t max_body = 256 * 1024;
};

struct HttpResponse {
    int status = 0;
    // Namen klein und getrimmt, Reihenfolge wie empfangen.
    std::vector<std::pair<std::string, std::string>> headers;
    std::string body;
    bool body_truncated = false;
    std::string final_url;  // nach eventuellen Umleitungen

    // Wert der letzten Kopfzeile mit diesem (klein geschriebenen) Namen.
    std::optional<std::string> header(std::string_view lower_name) const;
};

struct HttpError : std::runtime_error {
    enum class Kind {
        Resolve,    // Name nicht auflösbar (12007)
        Connect,    // Verbindung abgelehnt/unerreichbar (12029 …)
        Timeout,    // 12002
        Tls,        // 12175 u. a.; tls_flags/cert_issuer gesetzt, soweit ermittelbar
        Cancelled,  // CancelToken ausgelöst (12017)
        Protocol,   // ungültige Antwort, Verbindung mitten im Body abgebrochen
        TooLarge,   // Body größer als max_body bei Erfolgsstatus
        Scheme,     // URL nicht https (bzw. nicht http/https mit allow_plain_http)
        Redirect,   // Umleitung außerhalb der Erlaubnisliste oder zu viele Umleitungen
        Other,
    };
    HttpError(Kind k, std::string message, unsigned long win32_error = 0)
        : std::runtime_error(std::move(message)), kind(k), win32(win32_error) {}

    Kind kind;
    unsigned long win32 = 0;
    unsigned long tls_flags = 0;  // WINHTTP_CALLBACK_STATUS_FLAG_* (0x08, 0x10, 0x20 …)
    std::string cert_issuer;      // Aussteller des Serverzertifikats (z. B. ein Virenscanner)
};

// Abbruch-Marke. cancel() ist idempotent und threadsicher; registrierte
// Abbruch-Aktionen (z. B. WinHttpCloseHandle) laufen genau einmal – sofort,
// falls die Marke schon ausgelöst ist.
// Kopplung an std::stop_token: std::stop_callback cb(st, [&] { token.cancel(); });
class CancelToken {
public:
    CancelToken() = default;
    CancelToken(const CancelToken&) = delete;
    CancelToken& operator=(const CancelToken&) = delete;

    void cancel();
    bool cancelled() const;

    // Für Transport-Implementierungen: Aktion beim Abbruch. Rückgabe: Kennung zum Abmelden.
    std::uint64_t add_cancel_action(std::function<void()> action);
    // Abmelden. Läuft gerade cancel() auf einem anderen Thread, wartet das, bis dessen Aktionen fertig sind – danach
    // läuft garantiert keine Aktion mehr (Sicherheitsprüfung 2026-11-08, N10: vorher konnte eine Aktion, die cancel()
    // schon übernommen hatte, nach dem Abmelden auf ein zerstörtes Objekt des Aufrufers zugreifen). Aus einer Aktion
    // heraus (derselbe Thread wie cancel) wartet es nicht.
    void remove_cancel_action(std::uint64_t id);

private:
    mutable std::mutex mutex_;
    std::condition_variable done_;
    bool running_ = false;
    std::thread::id runner_;
    bool cancelled_ = false;
    std::uint64_t next_id_ = 1;
    std::vector<std::pair<std::uint64_t, std::function<void()>>> actions_;
};

// Bedingung des Aufrufers (z. B. ein Abbruch-Flag) → CancelToken: Ein Wächter-Thread prüft stop() alle poll und ruft
// dann token.cancel() – laufende Anfragen enden sofort statt nach ihren Timeouts. Vorher prüfte die Forschung ihr Flag
// nur VOR einem Abruf; Beenden wartete auf laufende Anfragen, ein tröpfelnder Server hielt sie beliebig lange fest
// (Prüfung 2026-10-25). Der Destruktor beendet den Wächter ohne Wartezeit. token muss den Wächter überleben.
class CancelWhen final {
public:
    CancelWhen(CancelToken& token, std::function<bool()> stop,
               std::chrono::milliseconds poll = std::chrono::milliseconds(100));
    ~CancelWhen();
    CancelWhen(const CancelWhen&) = delete;
    CancelWhen& operator=(const CancelWhen&) = delete;

private:
    std::jthread thread_;
};

struct DownloadRequest {
    std::string url;
    // Endname. Geschrieben wird immer nach <target>.part.
    std::filesystem::path target;
    std::uint64_t expected_size = 0;   // Pflicht (> 0); weicht die Größe ab: DownloadError
    std::string expected_sha256;       // Pflicht, 64 Hex klein; streamend geprüft (CNG)
    std::string user_agent;
    // Eigene Kopfzeilen, z. B. X-StreamWizard-Agent-Token. Mit Umleitungen erlaubt der
    // Transport sie nur bis zum ersten Hostwechsel (siehe oben).
    std::vector<std::pair<std::string, std::string>> headers;
    Redirects redirects = Redirects::None;
    std::vector<std::string> redirect_host_suffixes;
    HttpTimeouts timeouts{0, 15000, 15000, 30000};
    // Abbruch, wenn über min_rate_window_s die mittlere Rate darunter liegt
    // (Architektur §6.3: 16 KiB/s über 120 s). 0 = aus.
    std::uint64_t min_bytes_per_second = 16 * 1024;
    int min_rate_window_s = 120;
    // Vorhandene <target>.part per Range fortsetzen. Die URL wird dabei neu
    // aufgerufen, weil signierte CDN-URLs ablaufen (gemessen: Range bleibt über den
    // Hostwechsel erhalten, 206).
    bool resume = false;
    // HF: X-Linked-ETag (= LFS-SHA-256) bzw. X-Linked-Size einer Antwort der
    // Umleitungskette müssen expected_sha256/expected_size entsprechen, sonst
    // Abbruch, bevor der nächste Host (CDN) gefragt wird. Fehlt die Kopfzeile,
    // entscheidet allein der SHA-256 am Ende.
    bool check_linked_etag = false;
    // true: nach bestandener Größen- und Hashprüfung .part → target (MoveFileExW
    // REPLACE_EXISTING|WRITE_THROUGH). false: die geprüfte Datei bleibt als .part
    // liegen – das Update prüft vorher noch Signatur und PE-Kopf.
    bool finalize = true;
};

struct DownloadResult {
    std::filesystem::path file;  // target oder <target>.part (finalize=false)
    std::uint64_t bytes = 0;
    bool resumed = false;
};

struct DownloadError : std::runtime_error {
    // HttpStatus: Der Server antwortete, aber nicht mit 200/206 (z. B. 401, 404,
    // 503); http_status trägt den Code, damit Aufrufer „Token abgelehnt“ von
    // „Datei fehlt“ unterscheiden können.
    enum class Kind { SizeMismatch, HashMismatch, EtagMismatch, DiskSpace, Write, BadRequest, HttpStatus };
    DownloadError(Kind k, std::string message) : std::runtime_error(std::move(message)), kind(k) {}
    DownloadError(Kind k, std::string message, int status)
        : std::runtime_error(std::move(message)), kind(k), http_status(status) {}
    Kind kind;
    int http_status = 0;  // nur bei Kind::HttpStatus
};

// Fortschritt in Bytes; höchstens alle 250 ms und einmal zum Schluss.
using ProgressFn = std::function<void(std::uint64_t done, std::uint64_t total)>;

class HttpTransport {
public:
    virtual ~HttpTransport() = default;

    // Wirft HttpError bei Transportfehlern. Jeder HTTP-Status (auch 4xx/5xx) ist
    // eine normale Antwort; die Bewertung liegt beim Aufrufer.
    virtual HttpResponse send(const HttpRequest& request, CancelToken* cancel = nullptr) = 0;

    // Lädt in <target>.part, prüft freien Platz (GetDiskFreeSpaceExW), Größe und
    // SHA-256. Bei jedem Fehler wird die .part gelöscht – außer resume=true und
    // reinem Transportfehler (dann bleibt sie zum Fortsetzen liegen).
    // Wirft HttpError (Transport, Kind::Cancelled beim Abbruch) oder DownloadError.
    //
    // Umsetzung WinHTTP im Einzelnen: nur https (http nur mit
    // TransportOptions::allow_plain_http_loopback und Loopback-Ziel); der
    // Zielordner wird angelegt. resume: vorhandene .part wird zuerst gehasht und
    // per "Range: bytes=N-" fortgesetzt; 206 mit passendem Content-Range hängt an,
    // 200 beginnt neu, 416 beginnt einmal ohne Range neu; eine .part in voller
    // Größe mit richtigem Hash wird ohne Netz übernommen. Content-Length bzw.
    // Content-Range-Gesamtgröße ≠ expected_size → SizeMismatch vor dem Body.
    // X-Linked-ETag (irgendeine Antwort der Umleitungskette) ≠ expected_sha256 →
    // EtagMismatch, bevor der CDN-Host gefragt wird; fehlt die Kopfzeile,
    // entscheidet allein der SHA-256 am Ende (er bleibt die maßgebliche Prüfung).
    // Gelesen wird über WinHttpQueryDataAvailable, weil synchrones ReadData erst
    // bei vollem Puffer zurückkehrt (gemessen: 2000 Byte bei 1 KB/s → 2,1 s);
    // Fortschritt und Mindestrate sehen so jede Teillieferung. Endet die Verbindung
    // vor expected_size Bytes: HttpError Protocol (Fortsetzen möglich). Mindestrate
    // unterschritten: HttpError Timeout. Nicht-3xx-Status außer 200/206:
    // DownloadError HttpStatus; 3xx ohne erlaubte Umleitung: HttpError Redirect.
    // Umbenennen wiederholt bis zu 10-mal bei Freigabe-/Zugriffsfehlern, weil
    // Virenscanner frische Dateien kurz sperren (spec 06 §3.6, gemessen im IST).
    virtual DownloadResult download(const DownloadRequest& request, const ProgressFn& progress,
                                    CancelToken& cancel) = 0;
};

// Einstellungen des WinHTTP-Transports. Produktivcode nimmt die Vorgabe.
struct TransportOptions {
    // Erlaubt http:// für Loopback-Ziele (127.0.0.0/8, localhost) auch in
    // download() und als Umleitungsziel. Nur für die Loopback-Tests: Ein lokaler
    // TLS-Server mit vertrauenswürdigem Zertifikat ist ohne Eingriff in den
    // Zertifikatspeicher des Nutzers nicht möglich. Verlässt den Rechner nie.
    bool allow_plain_http_loopback = false;
};

// WinHTTP-Implementierung. Eine Instanz je Prozess genügt (Session wird geteilt).
// Wirft HttpError (Other), wenn WinHTTP nicht initialisierbar ist.
std::unique_ptr<HttpTransport> make_winhttp_transport();
std::unique_ptr<HttpTransport> make_winhttp_transport(const TransportOptions& options);

// Kurzer deutscher Grund zu den SECURE_FAILURE-Flags aus HttpError::tls_flags,
// mehrere mit "; " verbunden, z. B. 0x28 → "abgelaufen oder noch nicht gültig;
// unbekannte Zertifizierungsstelle". 0 → "Zertifikat konnte nicht geprüft werden".
std::string tls_failure_reason(unsigned long tls_flags);

// Hilfen für Aufrufer und Tests.
struct ParsedUrl {
    std::string scheme;  // "https" oder "http", klein
    std::string host;    // klein
    int port = 0;        // 443/80, wenn nicht angegeben
    std::string path_and_query;  // beginnt mit "/"
};
// nullopt bei ungültiger oder nicht absoluter URL.
std::optional<ParsedUrl> parse_url(std::string_view url);

// true, wenn host zu einem Eintrag der Erlaubnisliste passt ("a.b" exakt, "*.b" Subdomains).
bool host_allowed(std::string_view host, const std::vector<std::string>& allow_list);

}  // namespace swbh::win
