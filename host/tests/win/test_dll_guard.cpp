// Ladesperre für Bibliotheken: Nach harden_image_loads() meldet Windows selbst die Bits NoRemoteImages und
// NoLowMandatoryLabelImages für diesen Prozess, und ein zweiter Aufruf nimmt nichts zurück.

#include "test_support.hpp"

#include "win/dll_guard.hpp"

#include <windows.h>

namespace {

PROCESS_MITIGATION_IMAGE_LOAD_POLICY current() {
    PROCESS_MITIGATION_IMAGE_LOAD_POLICY p{};
    REQUIRE(GetProcessMitigationPolicy(GetCurrentProcess(), ProcessImageLoadPolicy, &p, sizeof p));
    return p;
}

}  // namespace

TEST_CASE("Ladesperre: keine DLL vom Netzlaufwerk und keine mit niedriger Integritätsstufe") {
    REQUIRE(swbh::win::harden_image_loads());
    const PROCESS_MITIGATION_IMAGE_LOAD_POLICY p = current();
    CHECK(p.NoRemoteImages == 1);
    CHECK(p.NoLowMandatoryLabelImages == 1);
    CHECK(p.PreferSystem32Images == 0);  // app-lokale VC-Laufzeit bleibt vorn

    // Erneut aufrufen: schaltet nur zu, nichts geht verloren.
    REQUIRE(swbh::win::harden_image_loads());
    const PROCESS_MITIGATION_IMAGE_LOAD_POLICY again = current();
    CHECK(again.NoRemoteImages == 1);
    CHECK(again.NoLowMandatoryLabelImages == 1);
}

TEST_CASE("Ladesperre: Systembibliotheken laden danach weiter") {
    REQUIRE(swbh::win::harden_image_loads());
    const swbh::win::LoadedModule m = swbh::win::load_library_absolute(L"C:\\Windows\\System32\\version.dll");
    CHECK(m.handle != nullptr);
    swbh::win::free_library(m.handle);
}
