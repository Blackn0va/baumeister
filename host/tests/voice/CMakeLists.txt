# test_whisper braucht das Erkennungsmodell (574 MB): Pfad über SWBH_WHISPER_MODEL, sonst wird der Fall übersprungen.
swbh_add_test(swbh_test_voice SOURCES test_tts.cpp test_voice.cpp test_voice_out.cpp LIBS swbh::voice swbh::win)
