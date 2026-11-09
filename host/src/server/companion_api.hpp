// Begleiter-Sprache: Das Plugin lässt Sätze des Begleiters vorlesen und holt erkannte Sprache ab (Wunsch 2026-11-04).
// Mikrofon, Erkennung und Vorlesen laufen hier auf dem PC des Spielers –
// Ton verlässt den PC nie, über den Tunnel geht nur Text.
//
// Schnittstelle Version 1, Gegenstück im Plugin CompanionVoiceLink.java – beide Seiten gleich halten:
//   POST /baumeister/v1/begleiter/sagen  {"spieler","text"} → 200 {"ok":true}; 400 bei ungültigem Inhalt
//   GET  /baumeister/v1/begleiter/gehoert → hält bis kHeardHold, dann 200 {"saetze":[{"spieler","text"}]}  umlaut-ok
// Prüfungen (ASVS V5.1.3, Eingaben vom Netz prüfen, auch von 127.0.0.1): spieler wie ein Minecraft-Name (3–16 Zeichen
// A–Z, a–z, 0–9, _), text 1–400 Byte UTF-8. Vorgelesen wird nur, was dem eingetragenen Spieler gilt (Controller): Auf
// einem geteilten Server spräche sonst jeder fremde Begleiter aus diesen Lautsprechern.
//
// Threads: HeardQueue ist threadsicher (Mikrofon-Thread schiebt, Verbindungs-Thread wartet). handle_companion läuft
// im Verbindungs-Thread; der Long-Poll belegt ihn bis zu kHeardHold und gibt früher auf, wenn das Plugin die
// Verbindung schließt. Fehler: keine Ausnahmen nach außen.

#pragma once

#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <functional>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::server {

inline constexpr std::string_view kCompanionSayPath = "/baumeister/v1/begleiter/sagen";
inline constexpr std::string_view kCompanionHeardPath = "/baumeister/v1/begleiter/gehoert";  // umlaut-ok: URL ASCII
// Simple Voice Chat (2026-11-04): Ton aus dem Spiel erkennen und Stimme als Ton fürs Spiel liefern.
//   POST /begleiter/hoeren?spieler=Name  Körper: PCM 16 kHz, 16 bit, mono, little-endian (≤ kMaxAudioBodyBytes)
//                                        → 200 {"text": "…"} ("" = nichts verstanden); 503, wenn die Erkennung aus ist
//   POST /begleiter/stimme {"spieler","text"} → 200 application/octet-stream: PCM 48 kHz, 16 bit, mono, little-endian
inline constexpr std::string_view kCompanionHearPath = "/baumeister/v1/begleiter/hoeren";  // umlaut-ok: URL ASCII
inline constexpr std::string_view kCompanionVoicePath = "/baumeister/v1/begleiter/stimme";
// Unter den 35 s, die das Plugin wartet (CompanionVoiceLink.poll), und über dem 15-s-Lesetimeout des Servers egal:
// Der Server liest die Anfrage zuerst ganz, dann wartet nur der Handler.
inline constexpr std::chrono::milliseconds kHeardHold{20'000};
inline constexpr std::size_t kHeardMax = 16;
inline constexpr std::size_t kCompanionTextMax = 400;

struct Spoken {
    std::string player;
    std::string text;
};

// Erkannte Sätze bis zur Abholung. Begrenzt: Holt das Plugin nichts ab (Tunnel weg), fällt der älteste Satz weg –
// Sätze von vor Minuten wären ohnehin keine Anweisung mehr.
class HeardQueue final {
public:
    void push(Spoken s);
    // Wartet bis zu hold auf mindestens einen Satz, prüft alle 250 ms gone(). Liefert alle wartenden Sätze.
    std::vector<Spoken> take(std::chrono::milliseconds hold, const std::function<bool()>& gone);
    std::size_t dropped() const;

private:
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<Spoken> queue_;
    std::size_t dropped_ = 0;
};

bool is_companion_path(std::string_view target);

// Minecraft-Name: 3–16 Zeichen aus A–Z, a–z, 0–9 und _.
bool valid_player_name(std::string_view name);

// Vorlesen nur für den eingetragenen Spieler (Groß-/Kleinschreibung egal wie in Minecraft). Leer eingetragen = für
// niemanden: fail-closed, sonst spräche auf einem geteilten Server jeder fremde Begleiter hier.
bool speak_for(std::string_view configured, std::string_view player);

// Erkennen für /hoeren: der eingetragene Spieler immer, andere nur mit hear_others (Wunsch 2026-11-04). Vorher nur der
// eigene Spieler – Mitspieler neben Vera wurden still verworfen.
// Ungültige Namen nie. Wer hear_others ausschaltet, rechnet fremde Stimmen nicht mehr auf diesem PC.
bool may_hear(std::string_view configured, std::string_view player, bool hear_others);

// Erkennung für /hoeren: Spieler, 16-kHz-Samples → Text ("" = nichts verstanden) oder nullopt + Fehler.
using HearFn = std::function<std::optional<std::string>(const std::string& player, std::vector<float> pcm,
                                                        std::string& error)>;
// Stimme für /stimme: Text → PCM 48 kHz mono oder nullopt + Fehler.
using VoiceFn = std::function<std::optional<std::vector<std::int16_t>>(const std::string& text, std::string& error)>;

struct CompanionHandlers {
    std::function<void(const Spoken&)> say;
    // Bei jedem Abholen (/gehoert): Spieler, die laut Plugin gerade über Simple Voice Chat sprechen (?imspiel=…).
    std::function<void(const std::vector<std::string>&)> in_game;
    HearFn hear;
    VoiceFn voice;
};

// say(): nur bei gültigem POST /sagen. hold ist im Test kurz.
ApiResponse handle_companion(const HttpRequest& request, HeardQueue& heard, const std::function<bool()>& gone,
                             const CompanionHandlers& handlers, std::chrono::milliseconds hold = kHeardHold);

// Spielername aus „…?spieler=Name“ (nur [A-Za-z0-9_], sonst leer).
std::string query_player(std::string_view target);

// Namen aus „…?imspiel=A,B“ (Plugin: CompanionVoiceLink.heardUri); ungültige fallen weg, höchstens 32.
std::vector<std::string> query_in_game(std::string_view target);

// Spricht der eingetragene Spieler gerade im Spiel? Dann ruht die Sprechtaste der App (Wunsch 2026-11-04) – sonst kam
// jeder Satz doppelt an.
bool own_player_in_game(std::string_view configured, const std::vector<std::string>& in_game);

}  // namespace swbh::server
