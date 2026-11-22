// HTTP/1.1-Anfragen lesen und prüfen – reiner Code ohne Sockets (testbar).
//
// Warum ein eigener Mini-Parser statt einer Bibliothek: Der Server spricht nur mit EINEM Gegenüber, dem Plugin über
// den SSH-Tunnel (Java HttpClient, HTTP/1.1, Content-Length). Alles darüber hinaus (chunked, Pipelining, Keep-Alive,
// Fortsetzungszeilen) ist hier Angriffsfläche ohne Nutzen und wird abgelehnt statt unterstützt.
//
// Abwehrregeln (ASVS v4.0.3, OWASP A05):
//  - Größen begrenzt: Kopf ≤ 16 KiB, ≤ 64 Kopfzeilen, Body ≤ 256 KiB (V13.1.3 / V12.1.1 – Ressourcen schonen).
//  - POST nur mit Content-Length (411), Transfer-Encoding wird abgelehnt (501), doppelte oder widersprüchliche
//    Content-Length und Fortsetzungszeilen sind 400 – das schließt Request-Smuggling-Mehrdeutigkeiten aus (V13.2).
//  - Host muss 127.0.0.1, localhost oder [::1] sein (Port beliebig): Schutz gegen DNS-Rebinding. Eine Webseite, die
//    ihren Namen auf 127.0.0.1 umbiegt, schickt ihren eigenen Namen als Host (V14.5.3 sinngemäß, OWASP A01).
//    Port beliebig, weil das Plugin auf dev01 den TUNNEL-Port (18080) anspricht – der Host-Kopf trägt dessen Port,
//    nicht den lokalen.
//  - Anfragen mit Origin oder Sec-Fetch-Site → 403: Beide setzt nur ein Browser. llama-server erlaubte ohne
//    --cors-origins jeder Webseite den Zugriff (gemessen) – das Plugin schickt keinen dieser Köpfe.
//  - POST mit anderem Content-Type als application/json → 415 (keine „einfache“ Formular-Anfrage aus dem Browser).
//
// Threads: reentrant. Fehler: über ParseResult/Rejection, keine Ausnahmen.

#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace swbh::server {

inline constexpr std::size_t kMaxHeaderBytes = 16 * 1024;
inline constexpr std::size_t kMaxHeaderCount = 64;
inline constexpr std::size_t kMaxBodyBytes = 256 * 1024;
// Ton aus dem Spiel (POST /baumeister/v1/begleiter/hoeren, 16 kHz × 16 bit): 1 MiB ≈ 32 s. Nur für diesen Pfad – alle
// anderen bleiben bei 256 KiB (2026-11-04). Wer die Grenze für alle hebt, lässt jeden Endpunkt 4× mehr puffern.
inline constexpr std::size_t kMaxAudioBodyBytes = 1024 * 1024;
inline constexpr std::string_view kAudioBodyPath = "/baumeister/v1/begleiter/hoeren";  // umlaut-ok: URL ASCII

struct HttpRequest {
    std::string method;
    std::string target;
    std::string version;
    std::vector<std::pair<std::string, std::string>> headers;  // Namen klein geschrieben, Werte ohne Rand-Leerraum
    std::string body;

    // Erster Kopf dieses Namens (Name klein), nullptr wenn keiner.
    const std::string* header(std::string_view lower_name) const;
};

enum class ParseStatus { Incomplete, Complete, Error };

struct ParseResult {
    ParseStatus status = ParseStatus::Incomplete;
    int http_status = 0;  // bei Error: 400, 411, 413, 431, 501, 505
    std::string error;    // snake_case-Code für Antwort und Protokoll
    HttpRequest request;  // bei Complete
};

struct Limits {
    std::size_t max_header_bytes = kMaxHeaderBytes;
    std::size_t max_header_count = kMaxHeaderCount;
    std::size_t max_body_bytes = kMaxBodyBytes;
};

// Liest eine Anfrage vom Anfang von `buffer`. Incomplete = mehr Bytes nötig (Kopf noch nicht zu Ende oder Body noch
// nicht vollständig). Bytes nach dem Body werden ignoriert (eine Anfrage je Verbindung).
ParseResult parse_request(std::string_view buffer, const Limits& limits = {});

struct Rejection {
    int http_status = 0;
    std::string error;  // snake_case-Code
};

// Prüft Host/Origin/Sec-Fetch-Site und Content-Type (siehe oben). nullopt = zulässig.
std::optional<Rejection> check_request(const HttpRequest& request);

// Nur der Hostname-Teil der Host-Prüfung, öffentlich für die Tests.
bool host_is_loopback(std::string_view host_header);

// Fertige HTTP/1.1-Antwort mit Content-Length, Connection: close, Cache-Control: no-store und
// X-Content-Type-Options: nosniff. extra_headers: weitere Zeilen ohne CRLF ("Retry-After: 5"). Status 204 ohne
// Content-Type, Content-Length und Körper (RFC 9110 §8.6).
std::string make_response(int status, std::string_view content_type, std::string_view body,
                          const std::vector<std::string>& extra_headers = {});

// Standardtext zum Status ("OK", "Bad Request" …).
std::string_view reason_phrase(int status);

}  // namespace swbh::server
