# Modul voice – Sprache des Begleiters: Vorlesen über die Windows-Sprachausgabe (SAPI), Sprechen per Haltetaste,
# Mikrofon (WinMM) und whisper.cpp (cmake/SwbhWhisper.cmake, gleiches ggml wie das Sprachmodell).

swbh_add_module(voice
    SOURCES
        tts_sapi.hpp tts_sapi.cpp
        audio.hpp audio.cpp
        signal.hpp signal.cpp
        tones.hpp tones.cpp
        microphone.hpp microphone.cpp
        push_to_talk.hpp push_to_talk.cpp
        recognizer.hpp recognizer.cpp
        speech_input.hpp speech_input.cpp
        piper_catalog.hpp piper_catalog.cpp
        piper_files.hpp piper_files.cpp
        piper.hpp piper.cpp
        speech_text.hpp speech_text.cpp
        voice_out.hpp voice_out.cpp
    PUBLIC_DEPS
        swbh::base swbh::llm swbh::win
    PRIVATE_DEPS
        swbh::accel swbh_whisper_lib ole32 sapi winmm user32)
