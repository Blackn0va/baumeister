# Modul llm – Modellkatalog/-Download und die llama.cpp-Engine auf der GPU (aus kraken, reduziert).

# Katalog aus deps.lock.json → generated/llm/model_catalog.inc. Größe und SHA-256
# stehen damit genau einmal im Repo und werden fest in die EXE übersetzt.
set(_cs_cat "")
string(JSON _cs_mcount LENGTH "${SWBH_LOCK_JSON}" models)
math(EXPR _cs_mlast "${_cs_mcount} - 1")
foreach(_i RANGE ${_cs_mlast})
    string(JSON _key MEMBER "${SWBH_LOCK_JSON}" models ${_i})
    if(_key STREQUAL "about" OR _key STREQUAL "default")
        continue()
    endif()
    foreach(_f title repo revision file sha256 license)
        string(JSON _v_${_f} GET "${SWBH_LOCK_JSON}" models ${_key} ${_f})
        if(_v_${_f} MATCHES "[\"\\\\]")
            message(FATAL_ERROR "deps.lock.json models.${_key}.${_f}: Anführungszeichen/Backslash nicht erlaubt")
        endif()
    endforeach()
    string(JSON _v_size GET "${SWBH_LOCK_JSON}" models ${_key} size)
    string(JSON _v_vram GET "${SWBH_LOCK_JSON}" models ${_key} min_vram_mb)
    # Kontext, KV-Größe und KV-Typ je Modell sind Pflicht: Ein fehlendes Feld fiele sonst still auf
    # die Vorgaben der Engine zurück – beim 14B hieße das 8192 Kontext und Auslagerung in den RAM.
    string(JSON _v_ctx GET "${SWBH_LOCK_JSON}" models ${_key} n_ctx)
    string(JSON _v_kvkib GET "${SWBH_LOCK_JSON}" models ${_key} kv_kib_per_token)
    string(JSON _v_kvq8 GET "${SWBH_LOCK_JSON}" models ${_key} kv_q8)
    # Fester Zustand rekurrenter Schichten (Qwen3.8: DeltaNet) für alle Sequenzen der Engine, MiB. Optional: Reine
    # Transformer haben keinen.
    string(JSON _v_state ERROR_VARIABLE _cs_state_err GET "${SWBH_LOCK_JSON}" models ${_key} state_mib)
    if(_cs_state_err)
        set(_v_state 0)
    elseif(NOT _v_state MATCHES "^[0-9]+$")
        message(FATAL_ERROR "deps.lock.json models.${_key}.state_mib muss eine Ganzzahl sein")
    endif()
    # Entwurfsmodell fürs spekulative Dekodieren (optional, Vorgabe nein): nicht auswählbar, nur Hilfe für die
    # Qwen2.5-Modelle (llm/llama_engine.cpp).
    string(JSON _v_draft ERROR_VARIABLE _cs_draft_err GET "${SWBH_LOCK_JSON}" models ${_key} draft)
    if(_cs_draft_err OR _v_draft STREQUAL "OFF")
        set(_v_draft "false")
    elseif(_v_draft STREQUAL "ON")
        set(_v_draft "true")
    else()
        message(FATAL_ERROR "deps.lock.json models.${_key}.draft muss true oder false sein")
    endif()
    if(NOT _v_ctx MATCHES "^[1-9][0-9]*$" OR NOT _v_kvkib MATCHES "^[1-9][0-9]*$")
        message(FATAL_ERROR "deps.lock.json models.${_key}: n_ctx/kv_kib_per_token müssen positive Ganzzahlen sein")
    endif()
    # string(JSON GET) liefert JSON-Wahrheitswerte als ON/OFF.
    if(_v_kvq8 STREQUAL "ON")
        set(_v_kvq8 "true")
    elseif(_v_kvq8 STREQUAL "OFF")
        set(_v_kvq8 "false")
    else()
        message(FATAL_ERROR "deps.lock.json models.${_key}.kv_q8 muss true oder false sein (ist '${_v_kvq8}')")
    endif()
    if(NOT _v_sha256 MATCHES "^[0-9a-f]+$" OR NOT _v_revision MATCHES "^[0-9a-f]+$")
        message(FATAL_ERROR "deps.lock.json models.${_key}: sha256/revision müssen Hex sein")
    endif()
    string(LENGTH "${_v_sha256}" _len)
    if(NOT _len EQUAL 64)
        message(FATAL_ERROR "deps.lock.json models.${_key}: sha256 muss 64 Zeichen haben")
    endif()
    string(APPEND _cs_cat "    ModelSpec{\"${_key}\", \"${_v_title}\", \"${_v_repo}\", \"${_v_revision}\", \"${_v_file}\", ${_v_size}ull, \"${_v_sha256}\", \"${_v_license}\", ${_v_vram}, ${_v_ctx}, ${_v_kvkib}, ${_v_kvq8}, ${_v_state}, ${_v_draft}},\n")
endforeach()
# string(JSON MEMBER) liefert die Schlüssel alphabetisch, nicht in Dateireihenfolge –
# das Standardmodell steht deshalb ausdrücklich unter models.default.
string(JSON _cs_default GET "${SWBH_LOCK_JSON}" models default)
string(JSON _cs_default_check ERROR_VARIABLE _cs_default_err GET "${SWBH_LOCK_JSON}" models ${_cs_default} file)
if(_cs_default_err)
    message(FATAL_ERROR "deps.lock.json models.default='${_cs_default}' ist kein Katalogeintrag")
endif()
file(CONFIGURE OUTPUT "${CMAKE_BINARY_DIR}/generated/llm/model_default.inc"
     CONTENT "// Erzeugt von src/llm/CMakeLists.txt aus deps.lock.json – nicht von Hand ändern.\n\"${_cs_default}\"\n"
     @ONLY)
file(CONFIGURE OUTPUT "${CMAKE_BINARY_DIR}/generated/llm/model_catalog.inc"
     CONTENT "// Erzeugt von src/llm/CMakeLists.txt aus deps.lock.json – nicht von Hand ändern.\n${_cs_cat}"
     @ONLY)

swbh_add_module(llm
    SOURCES
        models.hpp models.cpp
        engine.hpp llama_engine.cpp
    PUBLIC_DEPS
        swbh::base swbh::win
    PRIVATE_DEPS
        llama ggml swbh::accel)
target_include_directories(swbh_llm PRIVATE "${CMAKE_BINARY_DIR}/generated")
# Interne Grammatik-Header (src/llama-grammar.h) für Grammatik neben der Sampler-Kette. SYSTEM, damit /W4 /WX unserer
# Ziele nicht an fremdem Code scheitert. Nur privat: Kein anderes Modul darf an llama.cpp-Interna hängen. Stabil, weil
# der Commit festgenagelt ist (cmake/SwbhLlama.cmake).
target_include_directories(swbh_llm SYSTEM PRIVATE "${llama_SOURCE_DIR}/src")
