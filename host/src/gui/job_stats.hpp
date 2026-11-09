// Statistik der Aufträge seit dem Programmstart: Zahl, Fehlschläge, Durchschnittszeit, Token/s, Anteil des Prompts aus
// dem KV-Speicher.
//
// Warum: Die Karte „Letzter Auftrag“ zeigte nur den einen letzten Auftrag. Ob ein Modell- oder Rechenwegwechsel
// schneller ist oder ob die Präfix-Wiederverwendung greift, sah man erst, wenn man Konsolenzeilen abschrieb (so
// entstanden die Messwerte im Gedächtnis). Gewichtet wird über die Summen, nicht über den Mittelwert der Raten: Ein
// Auftrag mit 20 Token darf den Schnitt nicht so stark ziehen wie einer mit 400.
//
// Threads: keine eigene Sperre – der Controller hält sie unter seinem mutex_. Fehler: keine.

#pragma once

#include "llm/engine.hpp"

#include <cstdint>
#include <string>

namespace swbh::gui {

struct JobStats {
    int jobs = 0;
    int failed = 0;
    std::int64_t tokens = 0;
    std::int64_t prompt_tokens = 0;
    std::int64_t reused_tokens = 0;
    double seconds = 0.0;      // Summe über erfolgreiche Aufträge
    double gen_seconds = 0.0;  // davon Erzeugung (ohne Prompt)
    double fastest = 0.0;
    double slowest = 0.0;
    // Schnellste Erzeugung (Token/s) eines Auftrags mit mindestens kPaceMinTokens Token – Maßstab für „langsam“.
    double best_tps = 0.0;
    // Lesetempo beim Vorwärmen des System-Prompts (Token/s, 0 = nicht gemessen) – setzt der Controller nach dem Laden.
    double warm_prompt_tps = 0.0;

    // Ab so vielen erzeugten bzw. neu gelesenen Token zählt ein Auftrag für das Tempo (darunter streuen Messungen).
    static constexpr int kPaceMinTokens = 40;
    static constexpr int kPaceMinPrompt = 200;

    // Anderes Modell geladen: Maßstab für „langsam“ vergessen, Zähler und Summen bleiben. Vorher (2026-10-30) blieb
    // der Bestwert des 3B-Modells mit Turbo (200,7 Token/s) nach dem Wechsel auf Llama 3.1 8B stehen, und jeder
    // Auftrag des 8B (30–65 Token/s, für seine Größe normal) hieß „Langsam … belegt ein Spiel die Grafikkarte?“.
    void new_model() {
        best_tps = 0.0;
        warm_prompt_tps = 0.0;
    }

    // Fehlgeschlagene Aufträge zählen nur in jobs und failed – ihre Zeiten sind Teilzeiten.
    void add(bool ok, const llm::CompletionResult& r);

    int succeeded() const { return jobs - failed; }
    double avg_seconds() const;
    double tokens_per_second() const;
    // 0 … 1; 0 ohne Prompt-Token.
    double reuse_share() const;

    // Warnung, wenn dieser Auftrag weit unter dem eigenen Tempo lag: Lesen unter 25 % des Vorwärm-Tempos oder Schreiben
    // unter 35 % des Bestwerts. Aufruf VOR add() für denselben Auftrag. Leer = unauffällig.
    // Warum: dev01 2026-10-30, Qwen2.5 14B auf RTX 4070 Ti – ein Auftrag las 39 Token/s und schrieb 3,9 Token/s, eine
    // Probe kurz danach 3 209 bzw. 89. Die Konsole zeigte die Zahlen, aber niemand sah, dass das 30- bis 80-mal zu
    // langsam war; Ursache lag außerhalb der App (Grafikkarte belegt).
    std::string slow_warning_de(const llm::CompletionResult& r) const;

    // Eine Zeile für die Karte, deutsch mit Dezimalkomma, z. B.
    // „3 Aufträge (1 fehlgeschlagen) · Ø 41,7 s · Ø 5,6 Token/s · 92 % Prompt aus dem Speicher“. Leer ohne Auftrag.
    std::string summary_de() const;
};

}  // namespace swbh::gui
