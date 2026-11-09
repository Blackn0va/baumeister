# Modul server – HTTP/1.1 an 127.0.0.1 mit der OpenAI-Teilmenge für das Plugin.

swbh_add_module(server
    SOURCES
        http.hpp http.cpp
        chat_api.hpp chat_api.cpp
        city_api.hpp city_api.cpp
        research_api.hpp research_api.cpp
        result_api.hpp result_api.cpp
        companion_api.hpp companion_api.cpp
        http_server.hpp http_server.cpp
    PUBLIC_DEPS
        swbh::base swbh::llm swbh::plan
    PRIVATE_DEPS
        ws2_32)
