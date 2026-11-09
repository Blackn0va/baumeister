// Recherche-Schalter und -Anzeige: Das Plugin fragt vor jeder Internet-Recherche (Wikipedia/Wikidata, im Plugin
// ReferenceLookup.java), ob die App sie erlaubt, und meldet danach, was es nachgeschlagen hat (Wunsch 2026-10-30).
//
// Schnittstelle /baumeister/v1/recherche, Gegenstück ReferenceLookup.allowedByHost/report – beide Seiten gleich
// halten. GET → 200 {"erlaubt":true|false}. POST {"auftrag","titel","hoehe_m","zielhoehe","massstab"} → 200
// {"ok":true}, 400 {"error":"snake_code"} bei ungültigem Inhalt. Texte werden gekürzt (Auftrag 200, Titel 120 Byte),
// Zahlen auf vernünftige Bereiche geprüft – die Meldung landet in Konsole und Karte (ASVS V5.1.3: Eingaben vom Netz
// prüfen, auch von 127.0.0.1).
//
// Wer den Schalter entfernt, nimmt dem Nutzer die einzige Stelle in der App, an der das Internet abzuschalten ist; das
// Plugin hält sich daran (ohne Antwort entscheidet dort llm.recherche).
//
// Threads: handle_research aus Verbindungs-Threads; allowed und noted sind Rückrufe des Controllers (eigene Sperre).
// Fehler: keine Ausnahmen nach außen.

#pragma once

#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <functional>
#include <string>
#include <string_view>

namespace swbh::server {

inline constexpr std::string_view kResearchPath = "/baumeister/v1/recherche";

struct ResearchNote {
    std::string order;  // Auftrag wie getippt (gekürzt)
    std::string title;  // Wikipedia-Artikel
    double height_m = 0.0;
    int target_blocks = 0;  // Zielhöhe im Plan (0 = unbekannt)
    double scale = 1.0;     // 1 = 1:1, kleiner = verkleinert
};

bool is_research_path(std::string_view target);

// allowed(): Schalter aus den Einstellungen. noted(): nur bei gültigem POST.
ApiResponse handle_research(const HttpRequest& request, const std::function<bool()>& allowed,
                            const std::function<void(const ResearchNote&)>& noted);

// Eine Zeile für Konsole und Karte, z. B. „Kölner Dom – 157 m, 1:1“ oder „Eiffelturm – 330 m, verkleinert auf 56 %
// (184 Blöcke)“.
std::string research_line_de(const ResearchNote& note);

}  // namespace swbh::server
