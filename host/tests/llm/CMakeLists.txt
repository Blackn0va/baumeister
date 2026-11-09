swbh_add_test(swbh_test_llm SOURCES test_models.cpp test_keep_model.cpp LIBS swbh::llm swbh::win)
# Echtes Modell auf der GPU: nur über build.ps1 -GpuTestModel (SWBH_TEST_GGUF), ohne Datei übersprungen.
swbh_add_test(swbh_test_llm_gpu SOURCES test_prewarm_gpu.cpp LIBS swbh::llm swbh::win LABELS gpu)
