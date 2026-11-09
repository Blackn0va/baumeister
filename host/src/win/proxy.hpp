// Proxy-Entscheidung je Anfrage (Architektur §9, spec 01 §3.1, spec 06 §3.8).
//
// IST: urllib nahm HTTP(S)_PROXY/NO_PROXY aus der Umgebung, sonst den manuellen
// WinINet-Proxy der Registry, nie PAC/WPAD. Architektur: Umgebung als Named
// Proxy, sonst WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY (deckt manuellen Proxy UND
// PAC/WPAD ab – bewusste Erweiterung, damit Firmenproxys mit PAC funktionieren).
//
// Regeln (in dieser Reihenfolge):
// 1. Loopback-Ziele (localhost, 127.0.0.0/8) gehen IMMER direkt. Grund: Der
//    LLM-Endpoint (Standard http://127.0.0.1:11434) und Tests dürfen nie über
//    einen Proxy laufen; ein Proxy ohne „<local>“-Ausnahme schickte sonst lokale
//    Anfragen samt Bearer-Key an einen fremden Rechner.
// 2. Proxy-Variable des Ziel-Schemas (HTTPS_PROXY für https, HTTP_PROXY für
//    http; unter Windows ist die Groß-/Kleinschreibung der Variablen egal) leer
//    → Automatic.
// 3. NO_PROXY passt (Semantik von urllib proxy_bypass_environment: "*" = alle,
//    kommagetrennte Einträge, führende Punkte ignoriert, exakter Host oder
//    Subdomain, mit oder ohne Port) → Direct.
// 4. Sonst Named mit host:port aus der Proxy-URL (Schema http:// oder https://
//    oder keins; Port fehlt → 80). Benutzer/Kennwort aus der URL werden
//    prozentdekodiert und nur auf eine 407-Aufforderung des Proxys gesendet.
//    Ein unbrauchbarer Wert (z. B. socks5://) ergibt error: Die Anfrage scheitert
//    dann mit klarer Meldung, statt still am Proxy vorbei zu gehen.
//
// Threads: reentrant. read_proxy_env liest die Umgebung bei jedem Aufruf neu.

#pragma once

#include "win/http.hpp"

#include <string>
#include <string_view>

namespace swbh::win {

struct ProxyEnv {
    std::string https_proxy;
    std::string http_proxy;
    std::string no_proxy;
};

// Liest HTTPS_PROXY, HTTP_PROXY und NO_PROXY (UTF-8).
ProxyEnv read_proxy_env();

struct ProxyChoice {
    enum class Mode { Automatic, Direct, Named };
    Mode mode = Mode::Automatic;
    std::string server;    // "host:port" bei Named
    std::string user;      // leer = keine Zugangsdaten
    std::string password;
    std::string error;     // nicht leer: Proxy-Angabe unbrauchbar (Anfrage scheitert)
};

ProxyChoice choose_proxy(const ParsedUrl& target, const ProxyEnv& env);

// NO_PROXY-Abgleich wie urllib proxy_bypass_environment (host klein, port des Ziels).
bool no_proxy_matches(std::string_view host, int port, std::string_view no_proxy);

// localhost, localhost. und 127.x.y.z (vier Dezimaloktette).
bool is_loopback_host(std::string_view host);

}  // namespace swbh::win
