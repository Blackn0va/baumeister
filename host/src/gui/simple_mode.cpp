#include "gui/simple_mode.hpp"

#include "relay/kanal.hpp"
#include "win/cert_pin.hpp"

#include <algorithm>

namespace swbh::gui {

ServerPlace server_place(const AppConfig& c) {
    if (c.relay_enabled) {
        return ServerPlace::Internet;
    }
    return c.tunnel_enabled ? ServerPlace::Remote : ServerPlace::ThisPc;
}

void apply_server_place(AppConfig& c, ServerPlace place) {
    c.tunnel_enabled = place == ServerPlace::Remote;
    c.relay_enabled = place == ServerPlace::Internet;
    // Auch beim anderen Rechner an: lauscht nur auf 127.0.0.1 und schadet nicht, und ein Wechsel auf „dieser PC“ braucht
    // dann keinen zweiten Schalter mehr.
    c.local_server = true;
}

StartMode start_mode(const AppConfig& c) {
    if (!c.autostart) {
        return StartMode::Nothing;
    }
    return c.load_model_on_start ? StartMode::ConnectWithAi : StartMode::ConnectOnly;
}

void apply_start_mode(AppConfig& c, StartMode mode) {
    c.autostart = mode != StartMode::Nothing;
    // Bei „nichts“ bleibt die Modellwahl stehen – schaltet der Nutzer den Start später wieder an, gilt seine alte Wahl.
    if (mode != StartMode::Nothing) {
        c.load_model_on_start = mode == StartMode::ConnectWithAi;
    }
}

std::string mode_line_de(const ModeInput& in) {
    if (!in.connected) {
        return "Nicht verbunden – das Plugin erreicht diese App nicht. Stadt- und Weltbau laufen trotzdem (ohne Anzeige "
               "hier), Bauaufträge an die KI nicht.";
    }
    if (in.tunnel_needed && !in.server_set) {
        return in.via_address ? "Kein Fingerabdruck des Servers eingetragen – Einstellungen öffnen und ihn eintragen "
                                "(ohne ihn verbindet sich die App nicht)."
                              : "Kein Server eingetragen – Einstellungen öffnen und die Server-Adresse eintragen.";
    }
    if (in.tunnel_needed && !in.tunnel_up) {
        return in.via_address ? "Verbindung über die Adresse (HTTPS) wird aufgebaut …"
                              : "Verbindung zum Server wird aufgebaut …";
    }
    switch (in.ai) {
    case AiState::On: return "Verbunden mit KI: Bauaufträge im Chat plant die KI, Stadt- und Weltbau laufen ohne sie.";
    case AiState::Loading: return "Verbunden, KI wird geladen … Stadt- und Weltbau laufen schon.";
    case AiState::Failed:
        return "Verbunden, KI nicht geladen (Fehler in der Modell-Karte). Stadt- und Weltbau laufen, Bauaufträge an die "
               "KI nicht.";
    case AiState::Off: break;
    }
    return "Verbunden ohne KI: Stadt- und Weltbau laufen, Bauaufträge an die KI lehnt das Plugin ab. Für KI-Bau: „KI "
           "einschalten“.";
}

std::optional<std::string> relay_settings_problem(std::string_view url, std::string_view pin1, std::string_view pin2) {
    if (!relay::relay_url_allowed(url)) {
        return "Adresse muss die Form wss://<server>:<port>/kanal/v1 haben (z. B. " +
               std::string(relay::kDefaultRelayUrl) + ").";
    }
    const std::optional<std::string> first = win::parse_pin(pin1);
    if (!first) {
        return "Fingerabdruck des Servers fehlt oder hat nicht die Form sha256:<64 Hex-Zeichen> – ihn zeigt das Relay "
               "mit --fingerabdruck.";
    }
    const bool has_second = pin2.find_first_not_of(" \t\r\n") != std::string_view::npos;
    if (has_second) {
        const std::optional<std::string> second = win::parse_pin(pin2);
        if (!second) {
            return "Zweiter Fingerabdruck hat nicht die Form sha256:<64 Hex-Zeichen> (oder leer lassen).";
        }
        if (*second == *first) {
            return "Zweiter Fingerabdruck ist derselbe wie der erste – leer lassen oder den neuen Schlüssel eintragen.";
        }
    }
    return std::nullopt;
}

std::vector<std::string> relay_pins_from(std::string_view pin1, std::string_view pin2) {
    std::vector<std::string> out;
    for (const std::string_view p : {pin1, pin2}) {
        if (const std::optional<std::string> pin = win::parse_pin(p);
            pin && std::find(out.begin(), out.end(), *pin) == out.end()) {
            out.push_back(*pin);
        }
    }
    return out;
}

}  // namespace swbh::gui