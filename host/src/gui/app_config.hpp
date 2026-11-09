// Einstellungen des Baumeister-Host ({data}\config.local.json) – ohne Qt, testbar.
//
// Grundsatz wie in kraken: Unbekannte Felder werden ignoriert, Werte außerhalb des Bereichs auf den Rand geklemmt
// (und gemeldet), eine unlesbare Datei liefert die Vorgaben samt Fehler – die App startet trotzdem, die Datei wird erst
// beim Speichern ersetzt.
//
// Threads: reentrant. Fehler: load_config wirft nicht; save_config wirft UserError.

#pragma once

#include "relay/kanal.hpp"

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::gui {

struct AppConfig {
    std::string model_key;     // Katalogschlüssel oder "eigene"; leer = Standardmodell
    std::string custom_model;  // absoluter Pfad (UTF-8) bei model_key == "eigene"
    // Kontext in Token; 0 = automatisch nach freiem Grafikspeicher (llm::auto_context), sonst 2048–32768 von Hand.
    // Gespeichert als „kontext“. Vorher „n_ctx“ mit Vorgabe 8192, die immer mitgeschrieben wurde – eine alte Datei mit
    // 8192 gilt deshalb als automatisch (eine bewusste Wahl von 8192 ist von der alten Vorgabe nicht zu unterscheiden),
    // jeder andere alte Wert bleibt.
    int n_ctx = 0;
    std::string compute = "auto";  // "auto" (CUDA, wenn installiert und zugestimmt, sonst Vulkan) | "vulkan" | "cuda"
    bool allow_integrated = false;  // integrierte Grafik zulassen – nur für Tests ohne dedizierte Karte
    int server_port = 8080;
    // Verbindungsart, gespeichert als „verbindung“ (ab Version 3): "ssh" = tunnel_enabled, "https" = relay_enabled,
    // "lokal" = keins von beiden. Nie beide an – load_config und apply_server_place halten das ein. tunnel_enabled
    // wird für ältere App-Fassungen weiter mitgeschrieben (bei https false: eine alte App startet dann nichts).
    bool tunnel_enabled = true;
    // Fernverbindung über Kanal v1 (relay/relay_link.hpp, Wunsch 2026-11-08): Die App verbindet
    // sich nach außen mit dem Relay auf dem Minecraft-Server, beidseitig gepinnt. Vorgabe AUS.
    bool relay_enabled = false;
    std::string relay_url = std::string(relay::kDefaultRelayUrl);  // „fern_adresse“
    // „fern_server_pins“: 0–2 Pins des Relays (sha256:<64 hex>, klein). Zwei nur für einen Schlüsselwechsel. Leer = die
    // App verbindet sich nicht (fail-closed, kein Vertrauen ohne Pin).
    std::vector<std::string> relay_pins;
    // „fern_eigener_pin“: Pin des eigenen Client-Zertifikats (win/client_cert.hpp) – öffentlich, kein Geheimnis; der
    // Schlüssel selbst liegt nicht exportierbar im TPM bzw. Schlüsselspeicher. Leer = noch keiner angelegt.
    std::string relay_client_pin;
    // "pageant" (Schlüssel, empfohlen) oder "passwort". Das Passwort selbst steht NIE hier, sondern DPAPI-verschlüsselt
    // in {data}\tunnel-passwort.dpapi (Controller) – die Konfigdatei bleibt so weitergebbar.
    std::string tunnel_auth = "pageant";
    std::string plink;   // leer = Standardpfad
    // benutzer@host des Minecraft-Servers. Leer = noch kein Server eingetragen: Dann startet kein Tunnel, die App
    // bedient nur einen Server auf diesem PC (local_server). Vorher stand hier fest dev01 (siehe kLegacyTarget).
    std::string target;
    int ssh_port = 22;
    int remote_port = 18080;
    std::string hostkey;
    // Beim Start Modell laden (fehlt es: erst herunterladen), Server und Tunnel starten. Vorgabe AN (Wunsch
    // 2026-10-28).
    bool autostart = true;
    // Beim Autostart auch das Sprachmodell laden. Aus = nur verbinden (Server und Tunnel): Stadt- und Weltbau laufen
    // im Plugin ohne KI, die App zeigt ihren Fortschritt, und die Grafikkarte bleibt frei (Wunsch 2026-11-01).
    // Gespeichert als „modell_beim_start“.
    bool load_model_on_start = true;
    // Spekulatives Dekodieren mit dem 0.5B-Entwurfsmodell (nur Qwen2.5): schneller, lädt einmalig 0,5 GB nach.
    bool speculative = true;
    // Windows-Meldungen im Infobereich: Tunnel weg/wieder da, Modell bereit, Bauplan fertig/fehlgeschlagen
    // (gui/notifier.hpp). Vorgabe AN; fehlt der Schlüssel in einer älteren config.local.json, gilt ebenfalls AN.
    bool notifications = true;
    // Zusätzlich auf 127.0.0.1:<remote_port> lauschen (Wunsch 2026-10-29):
    // Ein auf diesen PC kopierter Minecraft-Server ruft mit unveränderter Plugin-Config 127.0.0.1:18080 – auf dev01
    // landet das im Tunnel, hier dann direkt bei der App. Vorgabe AN, auch für ältere Dateien ohne Schlüssel.
    bool local_server = true;
    // Internet-Recherche des Plugins (Wikipedia/Wikidata vor dem Planen, server/research_api.hpp) erlauben. Wunsch
    // 2026-10-30. Vorgabe AN wie im Plugin (llm.recherche); aus = keine Abfrage nach außen.
    bool research = true;
    // Begleiter (2026-11-04, server/companion_api.hpp): Sätze des Begleiters vorlesen. Vorgelesen wird nur, was dem
    // Spieler minecraft_name gilt – leer = nichts (auf einem geteilten Server spräche sonst jeder fremde Begleiter hier).
    // Neue Schlüssel ohne Versionswechsel: Fehlen sie in einer älteren Datei, gelten die Vorgaben.
    std::string minecraft_name;
    bool speech_out = true;  // „vorlesen“
    std::string tts_voice;   // „stimme“: Teil des Stimmnamens, leer = erste deutsche Stimme
    // „tonlage“ (2026-11-06): normal | fröhlich | cartoon | extra (voice::voice_style_pitch). Vorgabe cartoon, auch für
    // ältere Dateien ohne Schlüssel – der Nutzer wollte ausdrücklich Cartoon.
    std::string voice_style = "cartoon";
    // Sprechen mit dem Begleiter (voice/speech_input): Taste halten, sprechen, loslassen. Vorgabe AUS – Einschalten
    // lädt einmalig das Erkennungsmodell (574 MB) und richtet einen Tastatur-/Maus-Hook ein; beides nur auf Wunsch.
    bool speech_in = false;                // „sprechen“
    std::string talk_key = "maus4";        // „taste“: voice::parse_talk_key
    std::string microphone;                // „mikrofon“: Gerätename, leer = Windows-Standard
    std::string talk_mode = "halten";      // „modus“: halten | umschalten (voice::parse_talk_mode)
    int mic_gain_db = 0;                   // „mikro_db“: Verstärkung von Hand, −20 … +30
    bool auto_gain = true;                 // „mikro_automatik“: automatische Verstärkung auf −20 dBFS
    bool speech_tones = true;              // „mikro_töne“: Signaltöne bei Beginn und Ende der Aufnahme
    // „fremde_verstehen“ (Wunsch 2026-11-04): Sprache von Mitspielern aus
    // Simple Voice Chat hier erkennen (server::may_hear). Vorgabe AN; aus = nur der eigene Spieler wie vorher.
    bool hear_others = true;
};

struct ConfigLoad {
    AppConfig config;
    std::optional<std::string> error;
    std::vector<std::string> adjusted;  // Felder, die geklemmt wurden
    std::vector<std::string> notes;     // Übernahmen aus älteren Dateien (fürs Protokoll, keine Warnung)
};

// Version der Datei. 2 (2026-11-01): leeres Ziel heißt „kein Server“, nicht mehr dev01. 3 (2026-11-08):
// „verbindung“ (ssh | lokal | https) ist maßgeblich, dazu die Felder der Fernverbindung; Dateien bis Version 2 bekommen
// die Verbindungsart aus tunnel_enabled.
inline constexpr int kConfigVersion = 3;

ConfigLoad load_config(const std::filesystem::path& file);
void save_config(const std::filesystem::path& file, const AppConfig& config);

// Effektive Werte (Vorgaben für leere Felder).
std::string effective_model_key(const AppConfig& config);
std::string effective_plink(const AppConfig& config);
std::string effective_target(const AppConfig& config);

// Für die Einstellungen: „benutzer@host“ in Benutzer und Server-Adresse zerlegen und zurück. Ohne @ ist alles die
// Adresse. Leere Adresse ergibt ein leeres Ziel (kein Server); ein leerer Benutzer bleibt leer und fällt bei der
// Prüfung des Tunnels mit Grund auf – plink nähme sonst still den Windows-Benutzernamen.
std::string target_user(std::string_view target);
std::string target_host(std::string_view target);
std::string make_target(std::string_view user, std::string_view host);

}  // namespace swbh::gui
