// Tunnel-Passwort: DPAPI hin und zurück, Klartext nie in der Datei, falscher Zweck scheitert (fail-closed).

#include "test_support.hpp"

#include "gui/app_config.hpp"
#include "win/secret_store.hpp"

TEST_CASE("Passwort: DPAPI-Ablage ohne Klartext, nur mit gleichem Zweck lesbar") {
    const auto sealed = swbh::win::protect_secret("geheim-123", "baumeister-tunnel-passwort");
    REQUIRE(sealed.has_value());
    CHECK(sealed->find("geheim") == std::string::npos);
    const auto plain = swbh::win::unprotect_secret(*sealed, "baumeister-tunnel-passwort");
    REQUIRE(plain.has_value());
    CHECK(*plain == "geheim-123");
    CHECK_FALSE(swbh::win::unprotect_secret(*sealed, "anderer-zweck").has_value());
}

TEST_CASE("Einstellungen: Anmeldeart wird gespeichert, Unbekanntes fällt auf Pageant") {
    swbh::test::TempDir dir("cfg-auth");
    swbh::gui::AppConfig c;
    c.tunnel_auth = "passwort";
    swbh::gui::save_config(dir / "a.json", c);
    CHECK(swbh::gui::load_config(dir / "a.json").config.tunnel_auth == "passwort");
    CHECK(swbh::read_file(dir / "a.json").find("geheim") == std::string::npos);
    swbh::write_file_atomic(dir / "b.json", R"({"tunnel_auth":"klartext"})");
    CHECK(swbh::gui::load_config(dir / "b.json").config.tunnel_auth == "pageant");
}
