// JSON im Python-Format.
//
// Warum eigene Ausgabe statt nlohmann::dump: Die Konfig schreibt die Python-GUI
// mit json.dumps(indent=2, ensure_ascii=False) + "\n", caption.json mit den
// Standardtrennern ", " und ": ", Request-Bodies kompakt. nlohmann sortiert bei
// json Schlüssel alphabetisch (deshalb ordered_json), setzt andere Trenner,
// formatiert Floats anders als Pythons repr und wirft bei ungültigem UTF-8
// (type_error 316, spec 03 Port-Risiken). Die dump_*-Funktionen bilden
// json.dumps exakt nach (Escapes wie json.encoder.ESCAPE_DCT, Floats über
// py_float_repr) und ersetzen ungültiges UTF-8 durch U+FFFD.
//
// Threads: reentrant. Fehler: JsonError beim Parsen; dump wirft nicht.

#pragma once

#include <nlohmann/json.hpp>

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace swbh {

// Schlüsselreihenfolge bleibt erhalten (Python dict). Doppelte Schlüssel: Position
// des ersten, Wert des letzten – wie Pythons json.loads.
using Json = nlohmann::ordered_json;

struct JsonError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

struct ParseOptions {
    // Ein führendes UTF-8-BOM überlesen. Python lehnte es ab ("Unexpected UTF-8
    // BOM"); die C++-Konfig toleriert es bewusst (Architektur §11).
    bool allow_bom = false;
    // Ungültiges UTF-8 vorher durch U+FFFD ersetzen (Netzantworten, IST liest mit
    // errors="replace"). Ohne diese Option ist ungültiges UTF-8 ein JsonError
    // (Konfig: Python read_text(encoding="utf-8") scheiterte ebenso).
    bool replace_invalid_utf8 = false;
};

// Parst bytes. NaN/Infinity-Literale sind ungültig (bewusste Abweichung zu
// Python, Architektur §11). Wirft JsonError mit Bytestelle.
Json parse_utf8(std::string_view bytes, const ParseOptions& options = {});

// Architektur-Signatur: parse_utf8(bytes, allow_bom).
Json parse_utf8(std::string_view bytes, bool allow_bom);

// Wie parse_utf8, aber nullopt statt Ausnahme.
std::optional<Json> try_parse_utf8(std::string_view bytes, const ParseOptions& options = {});

// json.dumps(v, ensure_ascii=False, separators=(",", ":")) – Request-Bodies.
std::string dump_compact(const Json& value);

// json.dumps(v, ensure_ascii=False) – Trenner ", " und ": " (caption.json).
std::string dump_python_separators(const Json& value);

// json.dumps(v, ensure_ascii=False, indent=2) ohne abschließenden Zeilenumbruch.
// Die Konfigdatei hängt selbst "\n" an.
std::string dump_python_indent2(const Json& value);

// Python str(x) für JSON-Werte, wie die IST-Logik str(remote.get(...)) nutzt:
// String unverändert, Ganzzahl dezimal, Float wie repr ("2.0"), true → "True",
// false → "False", null → "None". Listen und Objekte im repr-Format
// (['a', 1] bzw. {'k': 'v'}); bewusst vereinfacht: nicht druckbare Nicht-ASCII-
// Zeichen werden anders als bei Python nicht als \x/\u-Escape geschrieben.
std::string py_str(const Json& value);

// Zeiger auf obj[key], wenn obj ein Objekt ist und den Schlüssel hat, sonst nullptr.
// Entspricht dict.get(key) ohne Ausnahme.
const Json* json_find(const Json& obj, std::string_view key);

}  // namespace swbh
