// Begleiter-Grammatik: Beispiele aus begleiter-prompt.txt gehen durch, Fehler nicht, und die Grammatik passt zu
// begleiter-schema.json (Feldnamen, Aufzählungen, Grenzen) – Plugin und Host liegen in verschiedenen Ordnern, die
// Gleichheit hält deshalb ein Test, wie für den Bauplan (test_plan.cpp).

#include "test_support.hpp"

#include "base/json.hpp"
#include "llm/engine.hpp"
#include "plan/bauplan.hpp"

#include <string>
#include <vector>

namespace {

using swbh::Json;

std::string grammar() { return std::string(swbh::plan::begleiter_grammar()); }

std::filesystem::path plugin_path(const char* name) {
    return swbh::test::source_path(std::string("../src/main/resources/") + name);
}

bool plugin_present() {
    std::error_code ec;
    return std::filesystem::is_regular_file(plugin_path("begleiter-schema.json"), ec);
}

std::string plugin_resource(const char* name) {
    if (plugin_present()) {
        return swbh::test::read_bytes(plugin_path(name));
    }
    return swbh::test::read_bytes(swbh::test::source_path(std::string("tests/plan/data/") + name));
}

// Die JSON-Beispiele aus begleiter-prompt.txt: je Zeile, die mit {"say" beginnt.
std::vector<std::string> prompt_examples() {
    const std::string text = plugin_resource("begleiter-prompt.txt");
    std::vector<std::string> out;
    std::size_t pos = 0;
    while ((pos = text.find("{\"say\"", pos)) != std::string::npos) {
        const std::size_t end = text.find('\n', pos);
        // Nur am Zeilenanfang: Die Erklärzeile „Answer ONLY with JSON: {"say": …“ ist kein Beispiel.
        if (pos == 0 || text[pos - 1] == '\n') {
            out.push_back(text.substr(pos, (end == std::string::npos ? text.size() : end) - pos));
        }
        pos = end == std::string::npos ? text.size() : end;
    }
    return out;
}

bool accepts(const std::string& text) {
    std::string error;
    const bool ok = swbh::llm::grammar_accepts(grammar(), text, error);
    if (!ok) {
        MESSAGE(error);
    }
    return ok;
}

bool rejects(const std::string& text) {
    std::string error;
    return !swbh::llm::grammar_accepts(grammar(), text, error) && !error.empty();
}

}  // namespace

TEST_CASE("Begleiter: alle Beispiele aus begleiter-prompt.txt werden angenommen") {
    const auto examples = prompt_examples();
    REQUIRE(examples.size() == 20);
    for (const std::string& e : examples) {
        CHECK(swbh::try_parse_utf8(e).has_value());  // wirklich JSON
        CHECK(accepts(e));
    }
}

TEST_CASE("Begleiter: Umlaute, alle Felder in Schema-Reihenfolge und leere Aktionsliste") {
    CHECK(accepts(R"({"say":"Grüß dich! Hier ist alles für dich.","actions":[]})"));
    CHECK(accepts(R"({"say":"x","actions":[{"op":"give","item":"torch","count":64,"block":"stone","radius":64,)"
                  R"("expr":"(1+2)*3","text":"Hütte","name":"tusch"}]})"));
    CHECK(accepts(R"({"say":"x","actions":[{"op":"follow"},{"op":"song","name":"tusch"},{"op":"time"}]})"));
}

TEST_CASE("Begleiter: fremde op, fremde Felder und Grenzen werden abgelehnt") {
    CHECK(rejects(R"({"say":"x","actions":[{"op":"explode"}]})"));
    CHECK(accepts(R"({"say":"x","actions":[{"op":"command","text":"gamemode creative"}]})"));
    CHECK(rejects(R"({"say":"x","actions":[{"op":"give","item":"torch","count":65}]})"));      // count > 64
    CHECK(rejects(R"({"say":"x","actions":[{"op":"give","item":"torch","count":0}]})"));       // count < 1
    CHECK(rejects(R"({"say":"x","actions":[{"op":"find","radius":65}]})"));                    // radius > 64
    CHECK(rejects(R"({"say":"x","actions":[{"op":"give","color":"red"}]})"));                  // fremdes Feld
    CHECK(rejects(R"({"say":"x","actions":[{"op":"give","count":1,"item":"torch"}]})"));       // Reihenfolge
    CHECK(rejects(R"({"say":"","actions":[]})"));                                              // say leer
    CHECK(rejects(R"({"actions":[],"say":"x"})"));                                             // Reihenfolge
    CHECK(rejects(R"({"say":"x"})"));                                                          // actions fehlt
    CHECK(rejects(R"({"say":"x","actions":[{"op":"time"},{"op":"time"},{"op":"time"},{"op":"time"}]})"));  // > 3
    CHECK(rejects(R"({"say":")" + std::string(301, 'a') + R"(","actions":[]})"));              // maxLength 300
    CHECK(accepts(R"({"say":")" + std::string(300, 'a') + R"(","actions":[]})"));
}

TEST_CASE("Begleiter: Grammatik und begleiter-schema.json laufen nicht auseinander") {
    const Json schema = swbh::parse_utf8(plugin_resource("begleiter-schema.json"));
    const std::string g = grammar();
    const auto quoted = [](const std::string& s) { return "\"\\\"" + s + "\\\"\""; };  // "\"name\"" in GBNF

    CHECK(schema["required"] == Json::array({"say", "actions"}));
    CHECK(schema["additionalProperties"] == false);
    CHECK(schema["properties"]["say"]["minLength"] == 1);
    CHECK(schema["properties"]["say"]["maxLength"] == 300);
    CHECK(g.find("char{1,300}") != std::string::npos);
    const Json& actions = schema["properties"]["actions"];
    CHECK(actions["maxItems"] == 3);
    CHECK(g.find("){0,2}") != std::string::npos);

    const Json& item = actions["items"];
    CHECK(item["required"] == Json::array({"op"}));
    CHECK(item["additionalProperties"] == false);
    std::size_t last = 0;
    for (const auto& [name, prop] : item["properties"].items()) {
        const std::size_t at = g.find(quoted(name));
        CHECK_MESSAGE(at != std::string::npos, "Feld fehlt in begleiter.gbnf: " << name);
        CHECK_MESSAGE(at >= last, "Feld außer der Reihe: " << name);
        last = at;
        if (prop.contains("enum")) {
            for (const Json& v : prop["enum"]) {
                CHECK_MESSAGE(g.find(quoted(v.get<std::string>())) != std::string::npos,
                              "Wert fehlt in begleiter.gbnf: " << name << "=" << v.get<std::string>());
            }
        }
    }
    const std::size_t op_rule = g.find("\nop ::=");
    REQUIRE(op_rule != std::string::npos);
    const std::string op_line = g.substr(op_rule, g.find('\n', op_rule + 1) - op_rule);
    std::size_t count = 0;
    for (std::size_t p = op_line.find("\\\""); p != std::string::npos; p = op_line.find("\\\"", p + 2)) {
        ++count;
    }
    CHECK(count / 2 == item["properties"]["op"]["enum"].size());
    CHECK(item["properties"]["count"]["minimum"] == 1);
    CHECK(item["properties"]["count"]["maximum"] == 64);
    CHECK(item["properties"]["radius"]["maximum"] == 64);
    CHECK(item["properties"]["item"]["maxLength"] == 48);
    CHECK(item["properties"]["block"]["maxLength"] == 48);
    CHECK(item["properties"]["expr"]["maxLength"] == 80);
    CHECK(item["properties"]["text"]["maxLength"] == 200);
    CHECK(item["properties"]["name"]["maxLength"] == 32);
}

TEST_CASE("Begleiter: Abschriften in tests/plan/data sind aktuell") {
    if (!plugin_present()) {
        MESSAGE("Plugin-Ordner fehlt (host/ allein kopiert) – geprüft wird gegen die Abschrift in tests/plan/data");
        return;
    }
    const Json live = swbh::parse_utf8(swbh::test::read_bytes(plugin_path("begleiter-schema.json")));
    const Json copy =
        swbh::parse_utf8(swbh::test::read_bytes(swbh::test::source_path("tests/plan/data/begleiter-schema.json")));
    CHECK_MESSAGE(live == copy, "begleiter-schema.json geändert – begleiter.gbnf prüfen und die Abschrift kopieren");
    std::string a = swbh::test::read_bytes(plugin_path("begleiter-prompt.txt"));
    std::string b = swbh::test::read_bytes(swbh::test::source_path("tests/plan/data/begleiter-prompt.txt"));
    std::erase(a, '\r');
    std::erase(b, '\r');
    CHECK_MESSAGE(a == b, "begleiter-prompt.txt geändert – Abschrift nach host/tests/plan/data kopieren");
}

TEST_CASE("Begleiter: response_format mit name begleiter wählt die Begleiter-Grammatik") {
    std::string code;
    const auto choice = swbh::plan::choose_grammar(
        Json::parse(R"({"response_format":{"type":"json_schema","json_schema":{"name":"begleiter","schema":{}}}})"),
        code);
    REQUIRE(choice);
    CHECK(choice->label == "begleiter");
    CHECK(choice->grammar == grammar());
}
