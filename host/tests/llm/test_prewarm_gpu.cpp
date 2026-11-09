// GPU-Probe (Label gpu): Vorwärmen und gekapptes Antwortbudget mit einem echten Modell.
//
// Läuft nur mit SWBH_TEST_GGUF=<datei.gguf> (build.ps1 -GpuTestModel), sonst übersprungen – Unit-Läufe haben weder
// Modell noch GPU. Integrierte Grafik ist zugelassen (Arc-iGPU des Entwicklungsrechners, Qwen2.5 3B).
//
// Gegenprobe ohne Neubau: SWBH_TEST_OHNE_PREWARM=1 lässt prewarm() weg – dann übernimmt der erste Auftrag 0 Token aus
// dem Speicher, und „load → prewarm → complete“ wird rot.
// Optional SWBH_TEST_DRAFT_GGUF=<entwurf.gguf>: dieselben Fälle mit Turbo (auf der iGPU nur mit SWBH_TURBO=immer, die
// Messprobe in load() schaltet ihn dort sonst ab).

#include "test_support.hpp"

#include "llm/engine.hpp"

#include <windows.h>

#include <filesystem>
#include <optional>
#include <string>

using namespace swbh::llm;

namespace {

std::optional<std::wstring> env(const wchar_t* name) {
    wchar_t buf[2048]{};
    const DWORD n = GetEnvironmentVariableW(name, buf, 2048);
    if (n == 0 || n >= 2048) {
        return std::nullopt;
    }
    return std::wstring(buf, n);
}

bool have_model() { return env(L"SWBH_TEST_GGUF").has_value(); }

LoadOptions options(int n_ctx) {
    LoadOptions o;
    o.model_file = std::filesystem::path(*env(L"SWBH_TEST_GGUF"));
    o.n_ctx = n_ctx;
    o.allow_integrated = true;
    if (const auto draft = env(L"SWBH_TEST_DRAFT_GGUF")) {
        o.draft_file = std::filesystem::path(*draft);
    }
    return o;
}

// Ein System-Prompt mit einigen hundert Token – lang genug, dass „aus dem Speicher“ nicht zufällig passt.
std::string system_prompt() {
    std::string s = "Du bist der Baumeister, ein Bau-NPC in Minecraft. Antworte nur mit einem Bauplan als JSON.\n";
    for (int i = 0; i < 40; ++i) {
        s += "Regel " + std::to_string(i + 1) + ": Jeder Block steht auf festem Grund, Türen zeigen zur Straße.\n";
    }
    return s;
}

CompletionRequest request(const std::string& user, int max_tokens) {
    CompletionRequest r;
    r.messages = {{"system", system_prompt()}, {"user", user}};
    r.max_tokens = max_tokens;
    r.temperature = 0.0f;
    return r;
}

}  // namespace

TEST_CASE("Vorwärmen (GPU): load → prewarm → complete übernimmt den System-Prompt aus dem Speicher" *
          doctest::skip(!have_model())) {
    LlamaEngine engine;
    std::string error;
    REQUIRE_MESSAGE(engine.load(options(4096), error), error);
    const std::optional<int> n = engine.prewarm({{"system", system_prompt()}}, error);
    REQUIRE_MESSAGE(n.has_value(), error);
    const int warmed = *n;
    CHECK(warmed > 100);
    if (env(L"SWBH_TEST_OHNE_PREWARM")) {
        // Gegenprobe: Präfixlänge kennen wir jetzt, also neu laden – der KV-Speicher ist danach leer wie ohne prewarm.
        MESSAGE("Gegenprobe: ohne prewarm");
        engine.unload();
        REQUIRE_MESSAGE(engine.load(options(4096), error), error);
    }
    const auto r = engine.complete(request("Bauauftrag: kleiner Turm", 8), error);
    REQUIRE_MESSAGE(r.has_value(), error);
    MESSAGE("vorgewärmt " << warmed << " Token, Prompt " << r->prompt_tokens << ", aus dem Speicher "
                          << r->reused_tokens << ", Prompt-Zeit " << r->prompt_seconds << " s");
    // −2: Am Übergang vom Präfix zum Rest darf die Vorlage ein, zwei Token anders schneiden.
    CHECK(r->reused_tokens >= warmed - 2);
}

TEST_CASE("Vorwärmen (GPU): ein Auftrag vor prewarm behält seinen Speicher" * doctest::skip(!have_model())) {
    LlamaEngine engine;
    std::string error;
    REQUIRE_MESSAGE(engine.load(options(4096), error), error);
    const auto first = engine.complete(request("Bauauftrag: Brunnen", 8), error);
    REQUIRE_MESSAGE(first.has_value(), error);
    const std::optional<int> n = engine.prewarm({{"system", system_prompt()}}, error);
    REQUIRE_MESSAGE(n.has_value(), error);
    CHECK(*n == 0);  // nichts getan
    // Derselbe Auftrag noch einmal: ganzer Prompt aus dem Speicher (bis auf das letzte Token, das die Logits liefert).
    // Hätte prewarm den Speicher durch den bloßen Präfix ersetzt, wären es nur die System-Token.
    const auto again = engine.complete(request("Bauauftrag: Brunnen", 8), error);
    REQUIRE_MESSAGE(again.has_value(), error);
    CHECK(again->reused_tokens == again->prompt_tokens - 1);
}

TEST_CASE("Antwortbudget (GPU): max_tokens über dem Kontext läuft bis ans Kontextende ohne llama_decode-Fehler" *
          doctest::skip(!have_model())) {
    LlamaEngine engine;
    std::string error;
    // Kleiner Kontext, damit das Ende in Sekunden erreicht ist.
    REQUIRE_MESSAGE(engine.load(options(1024), error), error);
    CompletionRequest r;
    r.messages = {{"system", "Du zählst. Nur Zahlen, eine je Zeile, nie aufhören."},
                  {"user", "Zähle von 1 bis 100000."}};
    r.max_tokens = 8000;
    r.temperature = 0.0f;
    const auto res = engine.complete(r, error);
    REQUIRE_MESSAGE(res.has_value(), error);
    MESSAGE("Prompt " << res->prompt_tokens << ", gekappt auf " << res->clamped_to << ", erzeugt "
                      << res->generated_tokens << (res->hit_limit ? " (Kontextende erreicht)" : " (Ende-Token)")
                      << (engine.speculative() ? ", Turbo" : ""));
    CHECK(res->clamped_to == engine.context_size() - res->prompt_tokens);
    CHECK(res->generated_tokens <= res->clamped_to);
    // Ein zweiter Auftrag danach muss den Speicher sauber weiterverwenden können.
    r.max_tokens = 16;
    const auto next = engine.complete(r, error);
    REQUIRE_MESSAGE(next.has_value(), error);
}

TEST_CASE("Schichten (GPU): mit knappem Budget liegt nur ein Teil auf der GPU, und die Antwort kommt trotzdem" *
          doctest::skip(!have_model())) {
    // Anlass 2026-11-04: 14B neben Minecraft ausgelagert (168–1389 ms je Token). Budget = halbe Dateigröße zwingt
    // einen Teil der Schichten auf die CPU; die Engine muss damit rechnen können.
    LoadOptions o = options(2048);
    o.draft_file.clear();
    o.gpu_budget_bytes = std::filesystem::file_size(o.model_file) / 2;
    LlamaEngine engine;
    std::string error;
    REQUIRE_MESSAGE(engine.load(o, error), error);
    MESSAGE("Schichten auf der GPU: " << engine.gpu_layer_count() << " von " << engine.layer_count());
    CHECK(engine.layer_count() > 0);
    CHECK(engine.gpu_layer_count() > 0);
    CHECK(engine.gpu_layer_count() < engine.layer_count());
    const auto r = engine.complete(request("Bauauftrag: kleiner Turm", 32), error);
    REQUIRE_MESSAGE(r.has_value(), error);
    CHECK_FALSE(r->text.empty());
    const double gen = r->seconds - r->prompt_seconds;
    MESSAGE("Erzeugen: " << r->generated_tokens << " Token in " << gen << " s = "
                         << (r->generated_tokens > 0 ? gen * 1000.0 / r->generated_tokens : 0.0) << " ms je Token, Prompt "
                         << r->prompt_tokens << " Token in " << r->prompt_seconds << " s");
}
