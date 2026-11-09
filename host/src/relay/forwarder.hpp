// Ein Kanal-Auftrag als Loopback-HTTP-Anfrage an den EIGENEN Server (127.0.0.1:<server_port>) – Kanal v1, Abschnitt 7.
//
// Warum über HTTP statt direkt an die Handler: So gelten alle Prüfungen des eigenen Servers unverändert (Host nur
// Loopback, Content-Type, Größen 256 KiB bzw. 1 MiB für den Ton-Pfad, höchstens 8 Verbindungen, Abbruch der Erzeugung,
// wenn der Client auflegt). Ein zweiter Eingang mit eigenen Regeln wäre eine zweite Angriffsfläche (OWASP A04). Den
// Host-Kopf setzt WinHTTP (127.0.0.1:<port>), vom Relay geht nur Methode, Pfad, Content-Type und Body mit.
//
// Fristen (Warten auf die Antwort; der eigene Server schreibt erst, wenn er fertig ist):
//  - /v1/chat/completions: 1 900 s – das Plugin erlaubt llm.timeout-sekunden bis 1 800, das Relay wartet 1 900 s.
//  - …/begleiter/gehoert: 40 s – der Long-Poll hält bis 20 s (server/companion_api), dazu Puffer.  umlaut-ok: URL
//  - …/begleiter/hoeren und …/stimme: 120 s – Ton erkennen bzw. erzeugen (erstes Laden ~4,5 s).  umlaut-ok: URL
//  - alles andere: 30 s.
// Die Antwort darf 16 MiB groß sein (Kanal v1); Status und Content-Type gehen unverändert zurück, auch 4xx/5xx.
//
// Threads: forward_local ist reentrant (je Aufruf eigene WinHTTP-Handles im geteilten Transport).
// Fehler: nullopt mit deutschem Grund (eigener Server nicht erreichbar, abgebrochen, Antwort zu groß) – der Aufrufer
// schickt dann „abbruch“.

#pragma once

#include "win/http.hpp"

#include <chrono>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::relay {

struct LocalRequest {
    std::string method;  // GET | POST
    std::string path;    // Pfad samt Abfrage, schon gegen relay::path_allowed geprüft
    std::optional<std::string> content_type;
    std::string body;
};

struct LocalResponse {
    int status = 0;
    std::optional<std::string> content_type;
    std::string body;
};

// Frist für das Warten auf die Antwort je Pfad (siehe oben).
std::chrono::milliseconds receive_timeout_for(std::string_view path);

std::optional<LocalResponse> forward_local(win::HttpTransport& http, int port, const LocalRequest& request,
                                           win::CancelToken& cancel, std::string& error);

}  // namespace swbh::relay
