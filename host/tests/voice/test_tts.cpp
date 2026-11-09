// Vorlesen über SAPI: Stimme wählen, sprechen, unterbrechen. Leise (Lautstärke 0), damit der Testlauf nicht spricht.
// Läuft auf jedem Windows (SAPI gehört zum System); ob eine deutsche Stimme da ist, hängt am Sprachpaket und wird nur
// gemeldet, nicht verlangt.

#include "test_support.hpp"

#include "voice/tts_sapi.hpp"

#include <string>

TEST_CASE("Vorlesen: SAPI öffnet, nennt Stimmen und spricht leise einen Satz zu Ende") {
    std::string error;
    auto speaker = swbh::voice::Speaker::open("", error);
    REQUIRE_MESSAGE(speaker != nullptr, error);
    const auto voices = swbh::voice::Speaker::voices();
    CHECK_FALSE(voices.empty());
    MESSAGE("Stimme: " << speaker->voice_name()
                       << std::string(speaker->german() ? " (deutsch)" : " (keine deutsche Stimme)"));
    speaker->set_volume(0);
    speaker->say("Hallo, ich bin Vera.");
    CHECK(speaker->wait_idle(15000));
}

TEST_CASE("Vorlesen: ein neuer Satz bricht den alten ab, stop beendet sofort") {
    std::string error;
    auto speaker = swbh::voice::Speaker::open("", error);
    REQUIRE(speaker != nullptr);
    speaker->set_volume(0);
    speaker->say(std::string(40, 'a') + " Dies ist ein sehr langer Satz, der gleich abgebrochen wird. " +
                 "Und noch einer, und noch einer, und noch einer.");
    speaker->say("Kurz.");
    speaker->stop();
    CHECK(speaker->wait_idle(3000));
}

TEST_CASE("Vorlesen: Hinweis auf eine Stimme, die es nicht gibt, fällt auf eine vorhandene zurück") {
    std::string error;
    auto speaker = swbh::voice::Speaker::open("GibtEsNicht", error);
    REQUIRE(speaker != nullptr);
    CHECK_FALSE(speaker->voice_name().empty());
}
