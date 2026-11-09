#include "plan/bauplan.hpp"

#include "embedded/bauplan_gbnf.hpp"
#include "embedded/begleiter_gbnf.hpp"

namespace swbh::plan {

std::string_view bauplan_grammar() { return embedded::bauplan_gbnf_view(); }

std::string_view begleiter_grammar() { return embedded::begleiter_gbnf_view(); }

std::optional<GrammarChoice> choose_grammar(const Json& body, std::string& error_code) {
    const Json* format = json_find(body, "response_format");
    if (format == nullptr || format->is_null()) {
        return GrammarChoice{"", "frei"};
    }
    const Json* type = format->is_object() ? json_find(*format, "type") : nullptr;
    if (type != nullptr && type->is_string() && type->get<std::string>() == "text") {
        return GrammarChoice{"", "frei"};
    }
    if (type != nullptr && type->is_string() && type->get<std::string>() == "json_schema") {
        const Json* schema = json_find(*format, "json_schema");
        const Json* name = schema != nullptr && schema->is_object() ? json_find(*schema, "name") : nullptr;
        if (name != nullptr && name->is_string() && name->get<std::string>() == kBauplanSchemaName) {
            return GrammarChoice{std::string(bauplan_grammar()), std::string(kBauplanSchemaName)};
        }
        if (name != nullptr && name->is_string() && name->get<std::string>() == kBegleiterSchemaName) {
            return GrammarChoice{std::string(begleiter_grammar()), std::string(kBegleiterSchemaName)};
        }
    }
    error_code = "response_format_abgelehnt";
    return std::nullopt;
}

}  // namespace swbh::plan
