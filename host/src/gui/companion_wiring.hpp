// Verdrahtung der Begleiter-Endpunkte (server/companion_api) mit dem Controller, herausgelöst aus Controller::route.
//
// Warum eigene Datei: Seit 381255a reicht der Controller den Begleiternamen aus dem Long-Poll (?begleiter=…) an die
// Erkennung weiter. Getestet waren nur die beiden Enden (query_companion_name, SpeechInput::set_companion_name) –
// fehlte die Zeile dazwischen, blieb alles grün, und die Erkennung lauschte still weiter auf „Vera“ (Auftrag T-007,
// 2026-11-21). Hier liegt die ganze Kette von CompanionHandlers bis SpeechInput, ohne den schweren Controller
// (GPU-Erkennung, Server, Tunnel) testbar (tests/gui/test_window_wiring.cpp). Wer companion_name wieder im Controller
// von Hand verdrahtet, verliert diesen Test.
//
// Threads: Die Handler laufen im Verbindungs-Thread des Servers; forward_companion_name wartet auf nichts
// (SpeechInput::set_companion_name hat einen eigenen kleinen Mutex, ConsoleThrottle ebenso).
// Fehler: keine Ausnahmen.

#pragma once

#include "gui/console_throttle.hpp"
#include "server/companion_api.hpp"
#include "voice/speech_input.hpp"

#include <chrono>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace swbh::gui {

// Was der Controller für die Begleiter-Endpunkte beisteuert (Server-Thread).
class CompanionHooks {
public:
    CompanionHooks() = default;
    virtual ~CompanionHooks() = default;
    CompanionHooks(const CompanionHooks&) = delete;
    CompanionHooks& operator=(const CompanionHooks&) = delete;

    // Satz des Begleiters: Konsole, und vorlesen, wenn er dem eingetragenen Spieler gilt.
    virtual void companion_said(const server::Spoken& s) = 0;
    // Spieler im Voice-Chat laut Plugin (?imspiel=…).
    virtual void game_voice(const std::vector<std::string>& names) = 0;
    // Ton aus dem Spiel erkennen (/hoeren) und Stimme als Ton fürs Spiel (/stimme).
    virtual std::optional<std::string> hear_from_game(const std::string& player, std::vector<float> pcm,
                                                      std::string& error) = 0;
    virtual std::optional<std::vector<std::int16_t>> voice_for_game(const std::string& text, std::string& error) = 0;
    // Konsolenzeile zum Begleiternamen (schon gedrosselt, siehe forward_companion_name).
    virtual void companion_name_line(const std::string& line) = 0;
};

// Begleitername laut Plugin (beim Abholen, schon geprüft) an die Erkennung. Liefert die Konsolenzeile: nur bei einem
// Wechsel und höchstens eine je Abstand von throttle, sonst nullopt.
std::optional<std::string> forward_companion_name(voice::SpeechInput& speech, ConsoleThrottle& throttle,
                                                  const std::string& name,
                                                  std::chrono::steady_clock::time_point now);

// Handler für server::handle_companion. hooks, speech und throttle müssen die Handler überleben.
server::CompanionHandlers companion_handlers(CompanionHooks& hooks, voice::SpeechInput& speech,
                                             ConsoleThrottle& throttle);

}  // namespace swbh::gui
