// Grammatik für Baupläne: wählt anhand von response_format die feste GBNF (plan/bauplan.gbnf) oder keine.
//
// Warum fest statt aus dem Schema erzeugt: Das Plugin schickt sein plan-schema.json bei jedem Auftrag mit
// (LlmClient.java, response_format.json_schema). llama-server wandelte es selbst in eine Grammatik; ein eigener
// Schema→GBNF-Wandler wäre viel Code für genau ein Schema. Die Datei bauplan.gbnf bildet dieses eine Schema ab;
// tests/plan prüft, dass beide zusammenpassen.
//
// Seit 2026-11-04 zweite feste Grammatik „begleiter“ (plan/begleiter.gbnf, Plugin begleiter-schema.json) für die
// Antworten des Begleiters: ein Satz und höchstens drei Aktionen.
//
// Fail-closed: Ein anderes Schema als „bauplan“ oder „begleiter“ lehnt der Server ab (400), statt still frei zu antworten – das Plugin
// würde sonst ungeprüften Text als Plan lesen.
//
// Threads: reentrant. Fehler: choose_grammar liefert nullopt und einen snake_case-Code.

#pragma once

#include "base/json.hpp"

#include <optional>
#include <string>
#include <string_view>

namespace swbh::plan {

// Name im Feld response_format.json_schema.name, für den die Bauplan-Grammatik gilt.
inline constexpr std::string_view kBauplanSchemaName = "bauplan";

// Name für die Begleiter-Grammatik.
inline constexpr std::string_view kBegleiterSchemaName = "begleiter";

// Die eingebettete Grammatik (Bytes von src/plan/bauplan.gbnf).
std::string_view bauplan_grammar();

// Die eingebettete Begleiter-Grammatik (Bytes von src/plan/begleiter.gbnf).
std::string_view begleiter_grammar();

struct GrammarChoice {
    std::string grammar;  // leer = freie Antwort
    std::string label;    // fürs Protokoll: "bauplan", "begleiter" oder "frei"
};

// Ohne response_format oder mit type "text": keine Grammatik. type "json_schema" mit name "bauplan" bzw.
// "begleiter": die jeweilige feste Grammatik.
// Alles andere: nullopt, error_code "response_format_abgelehnt".
std::optional<GrammarChoice> choose_grammar(const Json& body, std::string& error_code);

}  // namespace swbh::plan
