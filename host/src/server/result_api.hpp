// Ergebnis im Spiel: Das Plugin meldet nach jedem Auftrag, was daraus wurde – gebaut (mit Blöcken und Plot),
// verworfen (Plan nach der Nachbesserung unbrauchbar, mit Grund) oder abgesagt (passt nicht auf den Plot, zu tief, Plot
// belegt). Wunsch 2026-10-31 – vorher sah die App nur, dass das Modell geantwortet hatte;
// ob im Spiel daraus ein Bau wurde, stand allein im Serverlog auf dev01.
//
// Schnittstelle /baumeister/v1/ergebnis, Gegenstück im Plugin ResultReporter.java – beide Seiten gleich halten.
// POST {"auftrag","ergebnis":"gebaut|verworfen|abgesagt|abgebrochen","grund","bloecke","plot",  umlaut-ok
// "korrekturen","runden"} → 200 {"ok":true}. korrekturen/runden seit 2026-10-31: was der Baumeister ohne LLM
// nachgebessert hat (PlanAutoFix) und wie viele LLM-Antworten der Auftrag brauchte – beides optional (ältere Plugins).
// 400 {"error":"snake_code"} bei ungültigem Inhalt, 405 sonst. Texte gekürzt (Auftrag über order_key wie order_text,
// Grund und Korrekturen 300, Plot 40 Byte), runden 0..20,
// ergebnis aus einer festen Liste, Blöcke 0..100 Mio. (ASVS V5.1.3: Eingaben vom Netz prüfen, auch von 127.0.0.1).
//
// Threads: handle_result aus Verbindungs-Threads; noted ist ein Rückruf des Controllers (eigene Sperre).
// Fehler: keine Ausnahmen nach außen.

#pragma once

#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <cstdint>
#include <functional>
#include <string>
#include <string_view>

namespace swbh::server {

inline constexpr std::string_view kResultPath = "/baumeister/v1/ergebnis";

struct ResultNote {
    std::string order;    // Auftrag, wie er ans LLM ging (ohne Plot- und Helfer-Angabe), als order_key
    std::string outcome;  // gebaut | verworfen | abgesagt | abgebrochen
    std::string reason;   // leer bei gebaut
    std::int64_t blocks = 0;
    std::string plot;  // PlotSquared-ID („1;1“), leer ohne Plot-Modus
    bool client = false;  // "quelle":"client" – gebaut von der Client-Mod (client/ResultSender.java), nicht vom Plugin
    std::string fixes;    // „Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst; …“, leer = nichts nachgebessert
    int rounds = 0;       // LLM-Antworten (0 = Schnellauftrag, 1 = ohne Nachbesserung)
};

bool is_result_path(std::string_view target);

// noted(): nur bei gültigem POST.
ApiResponse handle_result(const HttpRequest& request, const std::function<void(const ResultNote&)>& noted);

// Kurzzeile für Konsole, Karte und Verlauf: „gebaut: 8 652 Blöcke auf Plot 1;1“, „verworfen: schwebende Teile“,
// mit Nachbesserung „gebaut, selbst nachgebessert: 412 Blöcke“ (was genau: note.fixes, im Tooltip).
std::string result_line_de(const ResultNote& note);

// true, wenn das Ergebnis kein Bau war (Karte und Konsole zeigen es als Warnung).
bool result_is_failure(const ResultNote& note);

}  // namespace swbh::server
