# Die Grammatik-Prüfung nutzt llama.cpp (swbh::llm) – die ggml-DLLs findet der Test über PATH (SWBH_RUNTIME_PATHS).
swbh_add_test(swbh_test_plan SOURCES test_plan.cpp test_begleiter.cpp LIBS swbh::plan swbh::llm)
