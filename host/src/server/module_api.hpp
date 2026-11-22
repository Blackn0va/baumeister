// Server-Module (Vertrag v012, AK-38): Das Plugin meldet, welche Baumeister-Module es auf dem Minecraft-Server gibt,
// ob sie an sind, in welchem Zustand und warum (POST /baumeister/v1/module). Die App zeigt den letzten Stand nur an –
// geschaltet wird im Spiel (/bm module, Rechte im Plugin). Wunsch 2026-11-22: alles modular und an einer Stelle sichtbar.
//
// Schnittstelle modul_json Version 1, Gegenstück im Plugin: Modulverzeichnis (T-030) – beide Seiten gleich halten.
//   {"version":1,"modules":[{"id","name","description","on","state","reason","needs"}]}
// Grenzen laut Vertrag: id [a-z0-9-]{1,32}; name 1–40, description ≤ 160, reason ≤ 120 Zeichen (Codepoints, nicht
// Byte); state aktiv | aus | wartet | fehler; nur diese Felder. Darüber hinaus hier festgelegt: höchstens
// kModuleMaxCount Module, je Modul höchstens kModuleMaxNeeds needs (gleiche id-Regel), keine doppelten ids, keine
// Steuerzeichen in Texten, Körper höchstens kModuleMaxBodyBytes. Unbekannte Felder → 400 statt still ignoriert: Der
// Vertrag schließt Spielernamen, Adressen und Geheimnisse aus, und ein Feld, das hier niemand prüft, wäre genau der Weg,
// auf dem so etwas doch in Anzeige und Protokoll landet (ASVS V5.1.3/V5.1.4: Eingaben auch von 127.0.0.1 nach Positivliste
// prüfen). Fehlende description/reason/needs gelten als leer.
//
// Antwort 204 ohne Körper (RFC 9110 §15.3.5; make_response lässt dafür Content-Length weg), 400 {"error":"snake_code"}
// bei kaputtem, zu großem oder unzulässigem Inhalt, 405 bei anderer Methode. Eine abgelehnte Meldung lässt den
// vorigen Stand stehen.
//
// Threads: apply/handle_modules aus Verbindungs-Threads, snapshot/seq aus der GUI. Jede Meldung ersetzt den
// unveränderlichen Schnappschuss als Ganzes. Fehler: keine Ausnahmen nach außen.

#pragma once

#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::server {

inline constexpr std::string_view kModulePath = "/baumeister/v1/module";
// 64 Module à höchstens ~2 KiB (Texte bis 4 Byte je Zeichen, 16 needs) passen; mehr meldet kein ehrliches Plugin.
inline constexpr std::size_t kModuleMaxBodyBytes = 128 * 1024;
inline constexpr std::size_t kModuleMaxCount = 64;
inline constexpr std::size_t kModuleMaxNeeds = 16;
inline constexpr std::size_t kModuleIdMax = 32;
inline constexpr std::size_t kModuleNameMax = 40;
inline constexpr std::size_t kModuleDescriptionMax = 160;
inline constexpr std::size_t kModuleReasonMax = 120;

enum class ModuleRunState { Active, Off, Waiting, Failed };

struct ServerModule {
    std::string id;
    std::string name;
    std::string description;
    bool on = false;
    ModuleRunState state = ModuleRunState::Off;
    std::string reason;
    std::vector<std::string> needs;
};

struct ModuleReport {
    std::vector<ServerModule> modules;  // in der Reihenfolge des Plugins
    std::int64_t received_unix = 0;     // Eingang (Sekunden seit 1970, UTC)
    std::string received_hms;           // Eingang als Ortszeit „HH:MM:SS“ für die Anzeige
};

// Prüft eine Nutzlast. nullopt + snake_case-Code in error bei jedem Verstoß gegen die Grenzen oben.
std::optional<std::vector<ServerModule>> parse_modules(std::string_view body, std::string& error);

// „aktiv“, „aus“, „wartet“, „fehler“ – wie auf der Leitung.
std::string_view module_state_name(ModuleRunState state);

class ModuleState final {
public:
    // Nutzlast übernehmen. Leer = angenommen, sonst snake_case-Code (Stand bleibt dann unverändert).
    std::string apply(std::string_view body, std::int64_t now_unix, std::string now_hms);
    // nullptr, solange das Plugin nichts gemeldet hat.
    std::shared_ptr<const ModuleReport> snapshot() const;
    // Zählt jede angenommene Meldung – die GUI baut die Liste nur bei neuer Nummer neu.
    std::uint64_t seq() const;

private:
    mutable std::mutex mutex_;
    std::shared_ptr<const ModuleReport> current_;
    std::uint64_t seq_ = 0;
};

bool is_module_path(std::string_view target);
// now_unix/now_hms: Zeitpunkt des Eingangs (der Controller gibt die Uhr, Tests feste Werte).
ApiResponse handle_modules(const HttpRequest& request, ModuleState& state, std::int64_t now_unix,
                           const std::string& now_hms);

}  // namespace swbh::server
