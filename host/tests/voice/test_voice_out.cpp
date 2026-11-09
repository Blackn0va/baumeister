// Stimme des Begleiters: Wahl aus der Einstellung, Umrechnung für Simple Voice Chat, Windows-Stimme in den Speicher,
// Gleichstand der Piper-Werte mit deps.lock.json – und Piper echt (nur mit SWBH_PIPER_DIR; lädt dort ~85 MB, sonst
// übersprungen).

#include "test_support.hpp"

#include "base/json.hpp"
#include "voice/audio.hpp"
#include "voice/piper_catalog.hpp"
#include "voice/piper_files.hpp"
#include "voice/speech_text.hpp"
#include "voice/tts_sapi.hpp"
#include "voice/voice_out.hpp"
#include "win/http.hpp"

#include <windows.h>

#include <chrono>
#include <cmath>
#include <filesystem>
#include <string>

using namespace swbh::voice;

TEST_CASE("Aussprache: Uhrzeiten, Rechenzeichen und Zahlen klingen wie gesprochen") {
    CHECK(for_speech("Es ist 6:00 Uhr.") == "Es ist 6 Uhr.");
    CHECK(for_speech("Es ist 14:30 Uhr.") == "Es ist 14 Uhr 30.");
    CHECK(for_speech("Treffpunkt 7:05") == "Treffpunkt 7 Uhr 5");
    CHECK(for_speech("um 25:99") == "um 25:99");  // keine Uhrzeit
    CHECK(for_speech("17*23 = 391") == "17 mal 23 ist 391");
    CHECK(for_speech("100/4 = 25") == "100 geteilt durch 4 ist 25");
    CHECK(for_speech("2^10") == "2 hoch 10");
    CHECK(for_speech("5 - 3") == "5 minus 3");
    CHECK(for_speech("10-15 Blöcke") == "10-15 Blöcke");
    CHECK(for_speech("3.5 Herzen") == "3,5 Herzen");
    CHECK(for_speech("1.000 Blöcke") == "1000 Blöcke");
    CHECK(for_speech("16x torch") == "16 Fackel");
}

TEST_CASE("Aussprache: Minecraft-Englisch, Namen mit Ziffern, Abkürzungen und Emojis") {
    CHECK(for_speech("Pass auf, ein Creeper und zwei Spawner im Chunk!") ==
          "Pass auf, ein Krieper und zwei Spohner im Tschank!");
    CHECK(for_speech("In 48 Blöcken finde ich kein redstone_block.") == "In 48 Blöcken finde ich kein Rädstohn block.");
    CHECK(for_speech("Hallo Blackkn0va!") == "Hallo Blackknova!");
    // Abenteuer-Wörter (gemessen 2026-11-04 22:45): Nether, Hardcore, Enderauge – Netherite bleibt Netherite.
    CHECK(for_speech("Hardcore: ab in den Nether, zur Netherfestung, mit zwölf Enderaugen und Netherite!") ==
          "Haadkohr: ab in den Näther, zur Nätherfestung, mit zwölf Ender-Augen und Nethereit!");
    CHECK(for_speech("ca. 20 Blöcke, z.B. Eisen") == "circa 20 Blöcke, zum Beispiel Eisen");
    CHECK(for_speech("Hallo \xF0\x9F\x98\x80!") == "Hallo!");
    CHECK(for_speech("Soll ich wirklich /stop ausführen?") == "Soll ich wirklich stop ausführen?");
    CHECK(for_speech("Stell eine Crafting Table hin") == "Stell eine Werkbank hin");
    // Nicht anfassen: deutsche Wörter, die mit einem Schlüssel beginnen, und „ca.“ mitten im Wort.
    CHECK(for_speech("Ironie in Mexico.") == "Ironie in Mexico.");
    CHECK(for_speech("Die Homepage") == "Die Homepage");
    CHECK(for_speech("Server, okay") == "Server, okay");
}

TEST_CASE("Stimme: Wahl aus der Einstellung, Vorgabe Piper Thorsten wie in kraken") {
    CHECK(parse_voice_choice("").piper);
    CHECK(parse_voice_choice("").key == "thorsten-medium");
    CHECK(parse_voice_choice("piper:kerstin-low").key == "kerstin-low");
    CHECK(parse_voice_choice("piper:gibtsnicht").key == "thorsten-medium");
    const VoiceChoice s = parse_voice_choice("sapi:Hedda");
    CHECK_FALSE(s.piper);
    CHECK(s.key == "Hedda");
    // Alte Einstellung aus der Fassung vor Piper (reiner SAPI-Name): gilt als Piper-Vorgabe.
    CHECK(parse_voice_choice("Microsoft Hedda Desktop - German").piper);
}

TEST_CASE("Stimme: 22,05 kHz werden für Simple Voice Chat auf 48 kHz umgerechnet") {
    Pcm p;
    p.rate = 22050;
    p.samples.assign(22050, 1000);  // 1 s
    const auto out = for_voicechat(p);
    CHECK(std::abs(static_cast<long>(out.size()) - 48000) < 50);
    CHECK(std::abs(out[24000] - 1000) < 5);
    CHECK(for_voicechat(Pcm{}).empty());
}

TEST_CASE("Stimme: Cartoon-Tonlage hebt die Tonhöhe, gleiche Samples, schnellere Wiedergabe") {
    CHECK(voice_style_pitch("normal") == 1.0);
    CHECK(voice_style_pitch("fröhlich") == doctest::Approx(1.15));
    CHECK(voice_style_pitch("cartoon") == doctest::Approx(1.3));
    CHECK(voice_style_pitch("extra") == doctest::Approx(1.4));
    CHECK(voice_style_pitch("quietsch") == 0.0);
    Pcm p;
    p.rate = 22050;
    p.samples.assign(22050, 500);
    const Pcm c = with_pitch(p, 1.3);
    CHECK(c.rate == 28665);
    CHECK(c.samples == p.samples);  // kein Sample geht verloren, nur die Abtastrate ändert sich
    // Für Simple Voice Chat: 1 s Rohton wird bei 1,3 zu ~0,77 s – Piper spricht dafür um 1,3 langsamer.
    CHECK(std::abs(static_cast<long>(for_voicechat(c).size()) - 36923) < 50);
    CHECK(with_pitch(p, 1.0).rate == 22050);
    CHECK(with_pitch(p, 0.5).rate == 22050);
    CHECK(with_pitch(Pcm{}, 1.3).rate == 0);
    // Vorgabe der Wahl ist Cartoon (Nutzer 2026-11-06).
    CHECK(VoiceChoice{}.pitch == doctest::Approx(1.3));
}

TEST_CASE("Stimme: Windows-Stimme spricht in den Speicher (Rückfall und Ton fürs Spiel)") {
    std::string error;
    const auto pcm = sapi_synth("Hallo, ich bin Vera.", "", error);
    REQUIRE_MESSAGE(pcm.has_value(), error);
    CHECK(pcm->rate == 22050);
    CHECK(pcm->seconds() > 0.5);
    CHECK(pcm->seconds() < 10);
    double energy = 0;
    for (const auto s : pcm->samples) {
        energy += std::abs(static_cast<double>(s));
    }
    CHECK(energy / static_cast<double>(pcm->samples.size()) > 50);  // kein stiller Puffer
}

TEST_CASE("Stimme: Piper-Werte im Code und in deps.lock.json stimmen überein") {
    const swbh::Json lock = swbh::parse_utf8(swbh::test::read_bytes(swbh::test::source_path("deps.lock.json")));
    const swbh::Json& p = lock["piper"];
    CHECK(p["url"] == piper_spec().url);
    CHECK(p["size"] == piper_spec().size);
    CHECK(p["sha256"] == piper_spec().sha256);
    REQUIRE(p["voices"].size() == piper_voices().size());
    for (const VoiceSpec& v : piper_voices()) {
        const swbh::Json& j = p["voices"][v.key];
        CHECK_MESSAGE(j["file"] == v.file, v.key);
        CHECK_MESSAGE(j["size"] == v.size, v.key);
        CHECK_MESSAGE(j["sha256"] == v.sha256, v.key);
        CHECK_MESSAGE(j["json_sha256"] == v.json_sha256, v.key);
    }
}

TEST_CASE("Stimme: Piper echt laden und sprechen (nur mit SWBH_PIPER_DIR)") {
    wchar_t buf[1024]{};
    const DWORD n = GetEnvironmentVariableW(L"SWBH_PIPER_DIR", buf, 1024);
    if (n == 0 || n >= 1024) {
        MESSAGE("SWBH_PIPER_DIR nicht gesetzt – Piper übersprungen (Download ~85 MB)");
        return;
    }
    VoicePaths paths;
    paths.root = std::filesystem::path(buf);
    const VoiceSpec& v = voice_or_default("thorsten-medium");
    if (!piper_ready(paths) || !voice_ready(paths, v)) {
        auto http = swbh::win::make_winhttp_transport();
        swbh::win::CancelToken cancel;
        download_piper(*http, paths, v, {}, cancel);
    }
    REQUIRE(piper_ready(paths));
    REQUIRE(voice_ready(paths, v));
    VoiceOut out;
    out.configure(paths, VoiceChoice{true, v.key});
    CHECK_FALSE(out.needs_download());
    std::string error;
    std::string used;
    const auto t0 = std::chrono::steady_clock::now();
    const auto first = out.synth("Hallo, ich bin Vera. Wie kann ich dir helfen?", error, &used);
    const auto t1 = std::chrono::steady_clock::now();
    const auto second = out.synth("Hier sind sechzehn Fackeln für dich!", error, &used);
    const auto t2 = std::chrono::steady_clock::now();
    REQUIRE_MESSAGE(first.has_value(), error);
    REQUIRE_MESSAGE(second.has_value(), error);
    CHECK(used.rfind("Piper", 0) == 0);
    MESSAGE("Piper: " << used << ", erster Satz "
                      << std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count() << " ms für "
                      << first->seconds() << " s, zweiter "
                      << std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count() << " ms für "
                      << second->seconds() << " s");
    CHECK(first->seconds() > 1.0);
}
