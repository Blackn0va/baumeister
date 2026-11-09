// Vorhandene Modelldatei wird behalten: volle Größe ohne Prüfmarke → lokal per SHA-256 geprüft, kein Download.
// Wunsch 2026-10-28

#include "test_support.hpp"

#include "base/hash.hpp"
#include "llm/models.hpp"
#include "win/http.hpp"

#include <stdexcept>

namespace {

// Zählt Download-Aufrufe und schlägt fehl – ein echter Netzzugriff darf im Unit-Test nie passieren.
class CountingTransport final : public swbh::win::HttpTransport {
public:
    int downloads = 0;
    swbh::win::HttpResponse send(const swbh::win::HttpRequest&, swbh::win::CancelToken*) override {
        throw std::runtime_error("kein Netz im Test");
    }
    swbh::win::DownloadResult download(const swbh::win::DownloadRequest&, const swbh::win::ProgressFn&,
                                       swbh::win::CancelToken&) override {
        ++downloads;
        throw swbh::win::DownloadError(swbh::win::DownloadError::Kind::BadRequest, "Download im Test");
    }
};

swbh::llm::ModelSpec small_spec(const std::string& content) {
    swbh::llm::ModelSpec m;
    m.key = "test";
    m.title = "Testmodell";
    m.repo = "test/test";
    m.revision = "0123456789abcdef";
    m.file = "test.gguf";
    m.size = content.size();
    m.sha256 = swbh::sha256_hex(content);
    return m;
}

}  // namespace

TEST_CASE("Modelle: vorhandene Datei ohne Prüfmarke wird geprüft und behalten, nicht neu geladen") {
    swbh::test::TempDir dir("keep");
    const std::string content = "GGUF-Testinhalt";
    const auto spec = small_spec(content);
    swbh::write_file_atomic(dir / "test.gguf", content);
    CHECK(swbh::llm::model_state(dir.path(), spec) == swbh::llm::ModelState::Partial);  // keine Marke

    CountingTransport http;
    swbh::win::CancelToken cancel;
    swbh::llm::download_model(http, dir.path(), spec, {}, cancel);
    CHECK(http.downloads == 0);
    CHECK(swbh::llm::model_state(dir.path(), spec) == swbh::llm::ModelState::Present);
    CHECK(swbh::read_file(dir / "test.gguf") == content);  // Datei unverändert
}

TEST_CASE("Modelle: vorhandene Datei mit falschem Inhalt wird nicht übernommen (fail-closed)") {
    swbh::test::TempDir dir("keep-bad");
    const auto spec = small_spec("GGUF-Testinhalt");
    swbh::write_file_atomic(dir / "test.gguf", "GGUF-Fremdinhal");  // gleiche Größe, anderer Hash
    CountingTransport http;
    swbh::win::CancelToken cancel;
    CHECK_THROWS_AS(swbh::llm::download_model(http, dir.path(), spec, {}, cancel), swbh::win::DownloadError);
    CHECK(http.downloads == 1);
    CHECK(swbh::llm::model_state(dir.path(), spec) != swbh::llm::ModelState::Present);
}
