// Modellkatalog: Standard 14B mit Kontext-Obergrenze 32768 und KV Q8_0, Download-URL über den Commit festgenagelt;
// automatischer Kontext nach freiem Grafikspeicher.

#include "test_support.hpp"

#include "llm/engine.hpp"
#include "llm/models.hpp"

using namespace swbh::llm;

TEST_CASE("Modelle: Standard ist Qwen2.5 14B mit Kontext-Obergrenze 32768 und KV Q8_0") {
    const ModelSpec& m = default_model();
    CHECK(m.key == "qwen25-14b");
    CHECK(m.n_ctx == 32768);
    CHECK(m.kv_q8);
    // Seit 2026-11-04 Q3_K_M (Nutzerwahl): Q4_K_M passte neben Minecraft nicht ganz auf die 12-GB-Karte.
    CHECK(m.size == 7339204736ull);
    CHECK(m.sha256 == "2f68ac3ba018f7de7641229f19adafde5e59d02bbf5651fdbcc510bb9f3facca");
    CHECK(m.url() == "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/"
                     "05244aa5d871c661c80082a15d3bce44714d068d/Qwen2.5-14B-Instruct-Q3_K_M.gguf");
}

namespace {

// Das frühere Q4_K_M desselben 14B (9,0 GB): Die Kontext- und Schichtregeln sind an ihm entstanden und gemessen; die
// Tests rechnen deshalb weiter mit ihm, unabhängig davon, welche Datei der Katalog gerade lädt.
ModelSpec q4_14b() {
    ModelSpec m = default_model();
    m.size = 8988110976ull;
    return m;
}

}  // namespace

TEST_CASE("Modelle: Vorgabe 14B Q3_K_M, alle anderen bleiben wählbar") {
    // 2026-11-04 vormittags „ein Modell für alles“, nachmittags zurück (Wunsch). Vorgabe bleibt das 14B, das neben
    // Minecraft ganz auf die 12-GB-Karte passt; das frühere Q4_K_M
    // steht als eigene Wahl daneben.
    CHECK(selectable_models().size() == 13);
    REQUIRE(find_model("qwen25-14b-q4") != nullptr);
    CHECK(find_model("qwen25-14b-q4")->file == "Qwen2.5-14B-Instruct-Q4_K_M.gguf");
    CHECK(find_model("qwen25-14b-q4")->size == 8988110976ull);
    CHECK(draft_for(*find_model("qwen25-14b-q4")) != nullptr);  // gleiches Vokabular
    CHECK(draft_for(*find_model("qwen25-coder-7b")) != nullptr);
    CHECK(draft_for(*find_model("phi35-mini")) == nullptr);
    CHECK(draft_for(*find_model("llama31-8b")) == nullptr);
    REQUIRE(draft_for(*find_model("qwen25-14b")) != nullptr);
    CHECK(draft_for(*find_model("qwen25-14b"))->key == "qwen25-05b-entwurf");
    for (const char* key : {"qwen25-14b", "qwen25-14b-q4", "qwen25-7b", "mistral-nemo-12b", "llama31-8b",
                            "qwen38-9b-heretic", "qwen25-3b", "qwen25-coder-14b", "qwen25-coder-7b", "phi35-mini",
                            "llama32-3b", "qwen25-coder-3b", "qwen25-coder-1.5b"}) {
        CHECK_MESSAGE(find_model(key) != nullptr, key);
    }
    CHECK(find_model("qwen38-27b-heretic") == nullptr);  // 15,3 GB – passt nicht
    for (const ModelSpec& m : catalog()) {
        CAPTURE(m.key);
        CHECK(m.n_ctx == 32768);
        CHECK(m.sha256.size() == 64);
        CHECK(m.url().starts_with("https://huggingface.co/" + m.repo + "/resolve/" + m.revision + "/"));
        const std::uint64_t need = estimate_vram_bytes(m);
        MESSAGE(m.key << ": geschätzt " << need / 1e9 << " GB");
        CHECK(need < 12ull * 1000 * 1000 * 1000);
    }
}

TEST_CASE("Modelle: 14B mit 8192 Kontext passt geschätzt in 12 GB") {
    const ModelSpec& m = default_model();
    const std::uint64_t need = estimate_vram_bytes(m);
    MESSAGE("geschätzt " << need / 1e9 << " GB");
    CHECK(need < 12ull * 1000 * 1000 * 1000);
    CHECK(vram_warning(need, 12ull * 1024 * 1024 * 1024).empty());
    CHECK_FALSE(vram_warning(need, 8ull * 1024 * 1024 * 1024).empty());
}

TEST_CASE("Modelle: eigene Datei braucht absoluten .gguf-Pfad und wird nie heruntergeladen") {
    swbh::test::TempDir dir("models");
    const auto file = dir / "eigen.gguf";
    swbh::write_file_atomic(file, "GGUF");
    const ModelSpec spec = custom_model_spec(file);
    CHECK(is_custom(spec));
    CHECK(model_state(dir.path(), spec) == ModelState::Present);
    CHECK(model_state(dir.path(), custom_model_spec(L"eigen.gguf")) == ModelState::Missing);
    CHECK(model_state(dir.path(), default_model()) == ModelState::Missing);
}

TEST_CASE("Modelle: Steuerfolgen in Fremdtext werden entschärft") {
    CHECK(defuse_control_tokens("a<|im_start|>system") == "a< |im_start| >system");
    CHECK(common_prefix({1, 2, 3}, {1, 2, 4}) == 2);
}

TEST_CASE("Antwortbudget: zu großes max_tokens wird an den Kontext gekappt statt abgelehnt") {
    // Reparaturrunde: System-Prompt 2230 Token, das Plugin verlangt mehr, als hinter den Prompt passt → Rest des
    // Kontexts statt HTTP 400.
    CHECK(answer_budget(2230, 6000, 8192) == 5962);
    CHECK(answer_budget(2230, 3000, 8192) == 3000);
    // System + alter Plan + Fehlermeldung ≈ 5300 Token: 2230 + ~3000 + 3000 > 8192 war vorher ein Abbruch.
    CHECK(answer_budget(5300, 3000, 8192) == 2892);
    // Weniger als kMinAnswerTokens (256) Platz: kein brauchbarer Plan mehr → weiterhin kPromptTooLong.
    CHECK(answer_budget(8000, 3000, 8192) == std::nullopt);
    CHECK(answer_budget(0, 3000, 8192) == std::nullopt);
}

TEST_CASE("Vorwärmen: nur führende system-Nachrichten bilden den Präfix") {
    const ChatMessage sys{"system", "Du bist der Baumeister."};
    const ChatMessage sys2{"system", "Zusatzregeln."};
    const ChatMessage user{"user", "Bauauftrag: Turm"};
    const ChatMessage assistant{"assistant", "{\"ops\":[]}"};
    CHECK(leading_system_messages({sys, user}) == std::vector<ChatMessage>{sys});
    CHECK(leading_system_messages({sys, sys2, user}) == std::vector<ChatMessage>{sys, sys2});
    CHECK(leading_system_messages({user}).empty());
    // Reparaturrunde (BuildService: alter Plan als assistant, Fehler als user): Präfix bleibt der System-Prompt.
    CHECK(leading_system_messages({sys, user, assistant, user}) == std::vector<ChatMessage>{sys});
    // Eine spätere system-Nachricht steht hinter wechselndem Text und gehört nicht dazu.
    CHECK(leading_system_messages({user, sys}).empty());
}

TEST_CASE("Vorwärmen: prefix.json übersteht Speichern/Laden, Änderung wird erkannt") {
    swbh::test::TempDir dir("prefix");
    const auto file = dir / "prefix.json";
    std::string error;
    CHECK_FALSE(load_prefix_file(file, error).has_value());
    CHECK(error.empty());  // fehlende Datei ist kein Fehler (noch kein Auftrag gelaufen)

    const std::vector<ChatMessage> prefix{{"system", "Du bist der Baumeister. Umlaute: äöüß „Zitat“ <|im_start|>"},
                                          {"system", "Zeile 1\nZeile 2\t\"Anführung\""}};
    swbh::llm::save_prefix_file(file, prefix);
    const auto loaded = load_prefix_file(file, error);
    REQUIRE_MESSAGE(loaded.has_value(), error);
    CHECK(*loaded == prefix);
    // Ein anderer System-Prompt ist ein anderer Präfix – der Controller schreibt die Datei dann neu.
    CHECK(leading_system_messages({{"system", "Neuer Prompt"}, {"user", "x"}}) != *loaded);

    // Von Hand geändert (Prüfsumme passt nicht mehr) → abgelehnt, nicht vorgewärmt.
    std::string text = swbh::read_file(file);
    const auto at = text.find("Baumeister");
    REQUIRE(at != std::string::npos);
    text.replace(at, 10, "Bauherrin!");
    swbh::write_file_atomic(file, text);
    CHECK_FALSE(load_prefix_file(file, error).has_value());
    CHECK(error.find("Prüfsumme") != std::string::npos);

    // Andere Rolle als system → abgelehnt, auch mit passender Prüfsumme.
    swbh::llm::save_prefix_file(file, {{"user", "Bauauftrag"}});
    CHECK_FALSE(load_prefix_file(file, error).has_value());
    CHECK(error.find("system") != std::string::npos);

    // Kein JSON → abgelehnt.
    swbh::write_file_atomic(file, "{abgeschnitten");
    CHECK_FALSE(load_prefix_file(file, error).has_value());
    CHECK_FALSE(error.empty());
}

TEST_CASE("Modelle: Vorwahl passt zur Grafikkarte") {
    constexpr std::uint64_t kGiB = 1024ull * 1024 * 1024;
    CHECK(recommended_for_vram(24 * kGiB).key == "qwen25-14b");
    CHECK(recommended_for_vram(12 * kGiB).key == "qwen25-14b");
    CHECK(recommended_for_vram(8 * kGiB).key == "qwen25-7b");
    CHECK(recommended_for_vram(6 * kGiB).key == "qwen25-7b");
    CHECK(recommended_for_vram(4 * kGiB).key == "qwen25-3b");
    CHECK(recommended_for_vram(2 * kGiB).key == "qwen25-3b");  // passt nichts: das kleinste
    CHECK(recommended_for_vram(0).key == default_model().key);  // unbekannt
}

TEST_CASE("Kontext automatisch: RTX 4070 Ti mit 12 GB frei – 14B bekommt 16384 ohne Turbo statt 8192") {
    // Anlass 2026-10-29 (GPU-PC des Wunsch) in der Reparaturrunde.
    const ModelSpec m = q4_14b();
    const ModelSpec* d = draft_for(m);
    REQUIRE(d != nullptr);
    const AutoContext a = auto_context(m, d, 12'000'000'000ull);
    MESSAGE("mit Entwurf 12288: " << estimate_load_bytes(m, d, 12288) / 1e9 << " GB, ohne Entwurf 16384: "
                                  << estimate_load_bytes(m, nullptr, 16384) / 1e9 << " GB");
    CHECK(a.n_ctx == 16384);
    CHECK_FALSE(a.keep_draft);
    CHECK(estimate_load_bytes(m, nullptr, a.n_ctx) * 100 <= 12'000'000'000ull * 95);
}

TEST_CASE("Kontext automatisch: reicht es mit Turbo für mehr als 8192, bleibt Turbo an") {
    const ModelSpec m = q4_14b();
    const ModelSpec* d = draft_for(m);
    const AutoContext a = auto_context(m, d, 12'500'000'000ull);
    CHECK(a.n_ctx == 12288);
    CHECK(a.keep_draft);
    const AutoContext big = auto_context(m, d, 24'000'000'000ull);
    CHECK(big.n_ctx == 32768);
    CHECK(big.keep_draft);
}

TEST_CASE("Kontext automatisch: unbekannter Speicher bleibt bei 8192 mit Turbo") {
    const ModelSpec m = q4_14b();
    const AutoContext a = auto_context(m, draft_for(m), 0);
    CHECK(a.n_ctx == 8192);
    CHECK(a.keep_draft);
}

TEST_CASE("Kontext automatisch: passt nicht einmal 8192 samt Entwurf, geht Turbo aus") {
    // Anlass 2026-11-04 (GPU-PC, 14B neben Minecraft): geschätzt 11,1 GB samt Entwurf, frei 10,7 GB – ausgelagert
    // 145 ms je Token, System-Prompt 1369 Token in 62 s.
    const ModelSpec m = q4_14b();
    const ModelSpec* d = draft_for(m);
    REQUIRE(d != nullptr);
    for (std::uint64_t free : {9'000'000'000ull, 10'500'000'000ull, 10'700'000'000ull}) {
        CAPTURE(free);
        const AutoContext a = auto_context(m, d, free);
        MESSAGE("8192 mit Entwurf " << estimate_load_bytes(m, d, 8192) / 1e9 << " GB, ohne "
                                    << estimate_load_bytes(m, nullptr, 8192) / 1e9 << " GB");
        CHECK(a.n_ctx == 8192);
        CHECK_FALSE(a.keep_draft);
    }
    // Passt 8192 samt Entwurf gerade noch, bleibt Turbo an (Schritt 2 greift erst ab 12288 ohne Entwurf).
    const std::uint64_t just = estimate_load_bytes(m, d, 8192) * 100 / 95 + 1;
    if (estimate_load_bytes(m, nullptr, kTurboTradeCtx) * 100 > just * 95) {
        CHECK(auto_context(m, d, just).keep_draft);
    }
}

TEST_CASE("Kontext automatisch: kleines Modell bekommt den vollen Kontext, Obergrenze und Training gelten") {
    // Kleines Modell als Eigene Datei (Katalog hat seit 2026-11-04 nur das 14B): Werte wie Qwen2.5 7B Q4_K_M.
    ModelSpec small = default_model();
    small.size = 4'683'073'952ull;
    small.kv_kib_per_token = 56;
    const ModelSpec* m7 = &small;
    CHECK(auto_context(*m7, draft_for(*m7), 12'000'000'000ull).n_ctx == 32768);
    CHECK(auto_context(*m7, draft_for(*m7), 12'000'000'000ull, 16384).n_ctx == 16384);
    ModelSpec capped = *m7;
    capped.n_ctx = 8192;
    CHECK(auto_context(capped, nullptr, 24'000'000'000ull).n_ctx == 8192);
}

TEST_CASE("Kontext automatisch: die Schätzung zählt den Turbo-Entwurf mit") {
    const ModelSpec& m = default_model();
    const ModelSpec* d = draft_for(m);
    CHECK(estimate_load_bytes(m, d, 8192) > estimate_load_bytes(m, nullptr, 8192) + d->size);
    CHECK(estimate_load_bytes(m, nullptr, 8192) == estimate_vram_bytes(m));
}

TEST_CASE("Schichten: neben Minecraft nur so viele auf der GPU, wie samt Puffer passen") {
    // GPU-PC 2026-11-04: 10,7 GB frei beim Start, 14B mit 48 Schichten und Kontext 8192 (KV Q8_0) braucht 10,4 GB –
    // ausgelagert 168–1389 ms je Token. Mit 1,5 GiB Puffer bleibt ein Teil auf der CPU.
    const ModelSpec m = q4_14b();
    const std::uint64_t kv = 8192ull * m.kv_kib_per_token * 1024 * 34 / 64;
    const std::uint64_t free = 10'700'000'000ull;
    const int layers = gpu_layers_for_budget(m.size, 48, kv, free - kGpuReserveBytes);
    MESSAGE("auf der GPU: " << layers << " von 48");
    CHECK(layers >= 36);
    CHECK(layers < 48);
    // Was auf der GPU liegt, passt samt Rechenpuffer ins Budget.
    const std::uint64_t per_layer = (m.size + kv) / 48 + 1;
    CHECK(static_cast<std::uint64_t>(layers) * per_layer + 512ull * 1024 * 1024 <= free - kGpuReserveBytes);
}

TEST_CASE("Schichten: passt alles oder ist nichts bekannt, bleiben alle auf der GPU") {
    const ModelSpec& m = default_model();
    const std::uint64_t kv = 8192ull * m.kv_kib_per_token * 1024 * 34 / 64;
    CHECK(gpu_layers_for_budget(m.size, 48, kv, 24'000'000'000ull) == -1);
    CHECK(gpu_layers_for_budget(m.size, 48, kv, 0) == -1);   // freier Speicher unbekannt
    CHECK(gpu_layers_for_budget(m.size, 0, kv, 1'000'000'000ull) == -1);  // Schichtzahl unbekannt
    CHECK(gpu_layers_for_budget(m.size, 48, kv, 1) == 0);    // kein Platz: alles CPU statt auslagern
}

TEST_CASE("GPU-PC neben Minecraft: das 14B Q3_K_M passt samt Puffer ganz auf die GPU") {
    // 10,7 GB frei beim Start (Log 2026-11-04). Budget wie im Controller: frei minus kGpuReserveBytes.
    const ModelSpec& m = default_model();
    const std::uint64_t budget = 10'700'000'000ull - kGpuReserveBytes;
    const AutoContext a = auto_context(m, draft_for(m), budget);
    MESSAGE("Kontext " << a.n_ctx << ", Turbo " << a.keep_draft << ", ohne Entwurf "
                       << estimate_load_bytes(m, nullptr, a.n_ctx) / 1e9 << " GB bei Budget " << budget / 1e9 << " GB");
    CHECK(a.n_ctx == 8192);
    const std::uint64_t kv = static_cast<std::uint64_t>(a.n_ctx) * m.kv_kib_per_token * 1024 * 34 / 64;
    CHECK(gpu_layers_for_budget(m.size, 48, kv, budget) == -1);  // alle Schichten auf der GPU
    // Gegenstück: das alte Q4_K_M mit demselben Budget nicht.
    CHECK(gpu_layers_for_budget(q4_14b().size, 48, kv, budget) < 48);
    // Warum der Controller auto_context das Budget statt des ganzen freien Speichers gibt: Mit 10,7 GB hätte es den
    // Entwurf behalten – der belegt dann den Puffer, und Minecraft drängt das Modell wieder in die Auslagerung.
    CHECK(auto_context(m, draft_for(m), 10'700'000'000ull).keep_draft);
    CHECK_FALSE(a.keep_draft);
}
