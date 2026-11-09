swbh_add_test(swbh_test_server SOURCES test_http.cpp test_chat_api.cpp test_http_server.cpp test_city_api.cpp test_research_api.cpp test_result_api.cpp test_companion_api.cpp
    LIBS swbh::server ws2_32)
