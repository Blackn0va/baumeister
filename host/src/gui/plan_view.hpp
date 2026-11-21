// Plan-Vorschau ohne Qt: rechnet den zuletzt erzeugten Bauplan (JSON-Antwort des Modells) in Blöcke um und liefert
// daraus Draufsicht und Schnitt für das Widget gui/plan_preview.
//
// Warum im Host und nicht erst im Spiel: Bisher sah man am GPU-PC nur den Token-Strom in der Konsole – ob das Modell
// einen Turm oder einen Klotz geplant hat, zeigte erst der Bau auf dev01, Minuten später. Die Vorschau zeigt es, sobald
// der Plan fertig ist (Auftrag Runde 5, 2026-10-29).
//
// NÄHERUNG, kein zweiter Compiler: Die Grundformen (fill, walls, hollow, line, set, door, cylinder, sphere, roof)
// rechnen genau wie PlanCompiler.java des Plugins (box, line, cylinder mit inDisc, sphere mit inBall, Dach mit
// Überstand, Schichten und Platte am First, Betten und hohe Pflanzen zweiteilig). house und skyscraper rechnen seit
// T-017 (2026-11-21) Zelle für Zelle wie das Plugin, samt Fenstern, Leiter, Einrichtung und Lampen – gemessen gegen
// 50 echte Pläne von dev01 (probes/plan_preview). BEWUSST vereinfacht bleiben: module und logic (Maße und Zellen
// kennt nur die Bibliothek des Plugins) als Markierung, die Bauformen castle/wall/bridge/garden/tree (plan/Structures,
// gut 1 100 Zeilen) als Hinweis, Blockkatalog und Ersatzblöcke des Servers (ein unbekannter Name wird dort Stein),
// Prüfungen und Nachbesserungen von PlanReview. Maßgeblich ist, was der Server baut. Wer Bauformen oder Bibliothek
// hier nachbaut, muss sie bei jeder Plugin-Änderung nachziehen; das war für eine Vorschau bewusst nicht gewollt.
//
// Grenzen gegen Ausreißer: Die Grammatik erlaubt Koordinaten -64 … 200, ein fill über den ganzen Würfel wären
// 18,6 Mio. Blöcke. Ein Schritt über kMaxStepVolume wird übersprungen (Hinweis), die ganze Vorschau hört bei
// kMaxPreviewBlocks verschiedenen Blöcken ODER nach kMaxPreviewWork Blockoperationen auf – der GUI-Thread soll nie
// Sekunden an einer Vorschau rechnen. Die zweite Grenze kam nach der Prüfung (2026-10-29) dazu: Überlappende Schritte
// erhöhen die Zahl verschiedener Blöcke nicht, kosten aber jedes Mal voll – gemessen 70 × fill 84³ = 6,9 s
// eingefrorenes Fenster bei nur 593.000 Blöcken. 70 solche Schritte sind rund 1.750 Token und passen in den Kontext.
// Mit kMaxPreviewWork = 2 Mio. gemessen (Testfall „Gesamtbudget“, Release, Laptop): 10 × fill 84³ in 0,61 s samt
// JSON – der schlimmste Fall einer Vorschau, einmal je neuem Plan, nicht je Neuzeichnen.
//
// Threads: reentrant. Fehler: rasterize_plan wirft nicht – ein unlesbarer Plan liefert ok = false und einen Grund.

#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace swbh::gui {

// Der zuletzt beendete Auftrag, wie ihn der Controller festhält (seq zählt jeden beendeten Auftrag, 0 = noch keiner).
struct PlanRecord {
    std::uint64_t seq = 0;
    std::string request;  // Auftragstext (order_title der Anfrage)
    std::string grammar;  // "bauplan" oder "frei"
    bool ok = false;
    std::string error;
    std::string text;  // Antwort des Modells (bei "bauplan" der Plan als JSON)
    int tokens = 0;
    int prompt_tokens = 0;
    int reused_tokens = 0;
    double seconds = 0.0;
    double tokens_per_second = 0.0;
};

// Auftragstext aus der Protokoll-Zusammenfassung der Anfrage (server::RequestInfo::summary, Zeilenumbrüche dort schon
// als Leerzeichen): Das Plugin schreibt „Bauauftrag: <Text>\n\n<Umgebung> …“ (BuildService.userMessage). Zurück kommt
// nur <Text>; ohne den Vorspann bleibt die Zusammenfassung, wie sie ist (getrimmt).
// Seit der Nachbesserung 2026-10-29 nur noch Rückfall, wenn server::RequestInfo::order leer ist (gui/job_tracker):
// In Reparaturrunden ist die letzte Nachricht der Reparaturtext, nicht der Auftrag.
std::string order_title(std::string_view summary);

inline constexpr std::size_t kMaxStepVolume = 600'000;
inline constexpr std::size_t kMaxPreviewBlocks = 1'500'000;
// Summe der Volumina aller gerechneten Schritte (fill, walls, hollow, cylinder, sphere, roof, house, skyscraper).
// set, door, line und Markierungen zählen nicht – sie kosten höchstens einige hundert Blöcke.
inline constexpr std::size_t kMaxPreviewWork = 2'000'000;
// Darstellbarer Bereich je Achse. Ein Hochhaus mit 45 Geschossen auf y = 200 reicht bis 381, eine Kugel mit Radius 24
// um 200 bis 224 – beides passt. Was darüber hinausgeht, fällt still weg (zählt in clipped).
inline constexpr int kCoordLow = -256;
inline constexpr int kCoordHigh = 767;

// Blockname ohne „minecraft:“ und ohne Zustände in eckigen Klammern, klein geschrieben.
std::string block_base_name(std::string_view block);

// Farbe eines Blocks als 0xRRGGBB für die Vorschau. Bekannte Materialien (Holzarten, Stein, Glas, Beton, Wolle …)
// in ihrer ungefähren Spielfarbe, alles andere als feste, aus dem Namen abgeleitete gedeckte Farbe – gleicher Name,
// gleiche Farbe, auch über Programmstarts.
std::uint32_t block_rgb(std::string_view block);

// Markierungen für Bauteile ohne Maße (module, logic). Beginnen mit „#“ – das kommt in keinem Blocknamen vor.
inline constexpr std::string_view kModuleMarker = "#modul";
inline constexpr std::string_view kLogicMarker = "#logik";

struct PlanBlocks {
    bool ok = false;
    std::string error;  // deutsch, wenn ok == false
    std::string design;
    std::string reply;
    std::string mode;
    int steps = 0;
    std::vector<std::string> palette;  // Index → Blockname (ohne Zustände)
    std::unordered_map<std::uint32_t, std::uint16_t> cells;
    std::vector<std::string> notes;  // Hinweise: Markierungen, übersprungene Schritte
    std::size_t clipped = 0;         // außerhalb kCoordLow … kCoordHigh
    int min_x = 0, max_x = -1, min_y = 0, max_y = -1, min_z = 0, max_z = -1;  // Hülle (leer: max < min)

    bool empty() const { return cells.empty(); }
    std::optional<std::string_view> at(int x, int y, int z) const;
};

// Plan-JSON → Blöcke. Leerer oder unlesbarer Text, fehlende steps: ok = false. Eine abgeschnittene Antwort
// (max_tokens) zeigt die vollständigen Schritte, wie JsonRepair im Plugin sie baut.
PlanBlocks rasterize_plan(std::string_view json_text);

// Abgeschnittenes Plan-JSON hinter dem letzten vollständigen Schritt kappen und schließen (wie JsonRepair im Plugin).
// std::nullopt, wenn der Text nicht abgeschnitten aussieht (vollständig, kein „{“, keine Schrittliste).
std::optional<std::string> cut_truncated_plan(std::string_view text);

struct ViewCell {
    int block = -1;  // Index in PlanBlocks::palette, -1 = leer
    int level = 0;   // Draufsicht: y des obersten Blocks; Schnitt: 0
};

// Zweidimensionales Raster. u nach rechts (x), v nach oben: Draufsicht v = z (vorn = kleinstes z unten),
// Schnitt v = y. Welt-Koordinate = origin + Index.
struct PlanView {
    int width = 0;
    int height = 0;
    int origin_u = 0;
    int origin_v = 0;
    std::vector<ViewCell> cells;  // Zeile für Zeile, v = 0 zuerst

    const ViewCell& at(int u, int v) const { return cells[static_cast<std::size_t>(v) * width + u]; }
};

// Je Spalte (x, z) der oberste Block.
PlanView top_view(const PlanBlocks& plan);
// Senkrechter Schnitt in der Ebene z = const über die ganze Breite und Höhe des Plans.
PlanView section_view(const PlanBlocks& plan, int z);

}  // namespace swbh::gui
