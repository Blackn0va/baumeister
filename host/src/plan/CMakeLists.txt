# Modul plan – feste Bauplan-Grammatik (GBNF) und ihre Auswahl über response_format.

swbh_add_module(plan
    SOURCES
        bauplan.hpp bauplan.cpp
        bauplan.gbnf
        begleiter.gbnf
    PUBLIC_DEPS
        swbh::base)
swbh_embed_file(TARGET swbh_plan FILE "${CMAKE_CURRENT_SOURCE_DIR}/bauplan.gbnf" SYMBOL bauplan_gbnf)
swbh_embed_file(TARGET swbh_plan FILE "${CMAKE_CURRENT_SOURCE_DIR}/begleiter.gbnf" SYMBOL begleiter_gbnf)
