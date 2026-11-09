#include "components/catalog.hpp"

#include "base/errors.hpp"
#include "embedded/components_catalog_json.hpp"

#include <algorithm>

namespace swbh::components {

namespace {

constexpr std::string_view kReleasePrefix = "https://github.com/ggml-org/llama.cpp/releases/download/";


[[noreturn]] void fail(const std::string& where, const std::string& why) {
    throw UserError("Komponenten-Katalog: " + where + ": " + why);
}

const Json& member(const Json& obj, std::string_view key, const std::string& where) {
    const Json* v = json_find(obj, key);
    if (v == nullptr) {
        fail(where, "Feld „" + std::string(key) + "“ fehlt");
    }
    return *v;
}

std::string text(const Json& obj, std::string_view key, const std::string& where, bool allow_empty = false) {
    const Json& v = member(obj, key, where);
    if (!v.is_string()) {
        fail(where, "„" + std::string(key) + "“ ist kein Text");
    }
    std::string s = v.get<std::string>();
    if (s.empty() && !allow_empty) {
        fail(where, "„" + std::string(key) + "“ ist leer");
    }
    return s;
}

std::string optional_text(const Json& obj, std::string_view key, const std::string& where) {
    if (json_find(obj, key) == nullptr) {
        return {};
    }
    return text(obj, key, where, true);
}

std::uint64_t count(const Json& obj, std::string_view key, const std::string& where) {
    const Json& v = member(obj, key, where);
    // Nur echte nicht-negative Ganzzahlen: 1.0, true oder -1 sind kein Größenwert.
    if (!v.is_number_unsigned()) {
        fail(where, "„" + std::string(key) + "“ ist keine nicht-negative Ganzzahl");
    }
    const std::uint64_t n = v.get<std::uint64_t>();
    if (n == 0) {
        fail(where, "„" + std::string(key) + "“ ist 0");
    }
    return n;
}

bool is_hex64(std::string_view s) {
    return s.size() == 64 &&
           std::all_of(s.begin(), s.end(), [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); });
}

bool is_id(std::string_view s) {
    return !s.empty() && s.size() <= 32 && std::all_of(s.begin(), s.end(), [](char c) {
               return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
           });
}

std::string sha(const Json& obj, const std::string& where) {
    std::string s = text(obj, "sha256", where);
    if (!is_hex64(s)) {
        fail(where, "sha256 muss 64 Hex-Zeichen (klein) haben");
    }
    return s;
}

}  // namespace

bool safe_relative_name(std::string_view name) {
    // Kein führender „-“: extract_members gibt die Namen an tar weiter, das sie sonst als Option läse.
    if (name.empty() || name.size() > 200 || name.front() == '/' || name.back() == '/' || name.front() == '-') {
        return false;
    }
    for (const char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' ||
                        c == '_' || c == '-' || c == '/';
        if (!ok) {
            return false;  // auch „\“, „:“ (Laufwerk, Datenstrom) und Leerzeichen
        }
    }
    // Kein Pfadteil „.“ oder „..“ und keine leeren Teile („a//b“).
    std::size_t start = 0;
    while (start <= name.size()) {
        const std::size_t slash = name.find('/', start);
        const std::string_view part = name.substr(start, slash == std::string_view::npos ? std::string_view::npos
                                                                                          : slash - start);
        if (part.empty() || part == "." || part == "..") {
            return false;
        }
        if (slash == std::string_view::npos) {
            break;
        }
        start = slash + 1;
    }
    return true;
}

std::uint64_t Component::download_size() const {
    std::uint64_t n = 0;
    for (const Archive& a : archives) {
        n += a.size;
    }
    return n;
}

std::uint64_t Component::installed_size() const {
    std::uint64_t n = 0;
    for (const Archive& a : archives) {
        for (const ExtractFile& f : a.extract) {
            n += f.size;
        }
    }
    return n;
}

const Component* Catalog::find(std::string_view id) const {
    for (const Component& c : items) {
        if (c.id == id) {
            return &c;
        }
    }
    return nullptr;
}

Catalog parse_catalog(const Json& section) {
    if (!section.is_object()) {
        fail("components", "kein Objekt");
    }
    Catalog cat;
    cat.tag = text(section, "tag", "components");
    const std::string release = text(section, "release", "components");
    if (release != std::string(kReleasePrefix) + cat.tag + "/") {
        fail("components.release", "muss " + std::string(kReleasePrefix) + cat.tag + "/ sein");
    }
    const Json& items = member(section, "items", "components");
    if (!items.is_object() || items.empty()) {
        fail("components.items", "kein Objekt oder leer");
    }
    for (auto it = items.begin(); it != items.end(); ++it) {
        const std::string where = "components.items." + it.key();
        const Json& j = it.value();
        if (!j.is_object()) {
            fail(where, "kein Objekt");
        }
        Component c;
        c.id = it.key();
        if (!is_id(c.id)) {
            fail(where, "Kennung nur aus a–z, 0–9 und „-“");
        }
        c.title = text(j, "title", where);
        c.backend = text(j, "backend", where);
        c.dll = text(j, "dll", where);
        c.vendor = text(j, "vendor", where);
        c.version = text(j, "version", where);
        c.license = text(j, "license", where);
        c.terms = text(j, "terms", where, true);
        c.terms_url = text(j, "terms_url", where, true);
        c.needs = optional_text(j, "needs", where);
        c.note = text(j, "note", where);
        if (c.vendor != "nvidia" && c.vendor != "intel" && c.vendor != "amd") {
            fail(where, "vendor muss nvidia, intel oder amd sein");
        }
        if (!c.terms.empty() && !c.terms_url.starts_with("https://")) {
            fail(where, "terms ohne https-Lizenzadresse");
        }
        if (!c.needs.empty() && c.needs != "hip-sdk") {
            fail(where, "needs unbekannt: " + c.needs);
        }
        const Json& archives = member(j, "archives", where);
        if (!archives.is_array() || archives.empty()) {
            fail(where, "archives fehlt oder ist leer");
        }
        bool dll_found = false;
        std::vector<std::string> names;
        for (std::size_t i = 0; i < archives.size(); ++i) {
            const std::string aw = where + ".archives[" + std::to_string(i) + "]";
            const Json& aj = archives[i];
            if (!aj.is_object()) {
                fail(aw, "kein Objekt");
            }
            Archive a;
            a.file = text(aj, "file", aw);
            if (!safe_relative_name(a.file) || a.file.find('/') != std::string::npos || !a.file.ends_with(".zip")) {
                fail(aw, "file muss ein einfacher .zip-Name sein");
            }
            a.url = release + a.file;
            a.size = count(aj, "size", aw);
            a.sha256 = sha(aj, aw);
            const Json& ex = member(aj, "extract", aw);
            if (!ex.is_object() || ex.empty()) {
                fail(aw, "extract fehlt oder ist leer");
            }
            for (auto e = ex.begin(); e != ex.end(); ++e) {
                const std::string ew = aw + ".extract." + e.key();
                if (!safe_relative_name(e.key())) {
                    fail(ew, "unsicherer Dateiname");
                }
                if (!e.value().is_object()) {
                    fail(ew, "kein Objekt");
                }
                // Zwei Archive dürfen nicht dieselbe Datei liefern: Welche gewinnt, hinge von der Reihenfolge ab.
                for (const std::string& n : names) {
                    if (n == e.key()) {
                        fail(ew, "Datei doppelt");
                    }
                }
                names.push_back(e.key());
                ExtractFile f{e.key(), count(e.value(), "size", ew), sha(e.value(), ew)};
                if (f.name == c.dll) {
                    dll_found = true;
                }
                a.extract.push_back(std::move(f));
            }
            c.archives.push_back(std::move(a));
        }
        if (!dll_found || c.dll.find('/') != std::string::npos) {
            fail(where, "dll „" + c.dll + "“ steht nicht unter den entpackten Dateien (oberste Ebene)");
        }
        cat.items.push_back(std::move(c));
    }
    return cat;
}

const Catalog& builtin_catalog() {
    // Abschnitt deps.lock.json → components, byte-genau eingebettet (src/components/CMakeLists.txt).
    static const Catalog cat = parse_catalog(parse_utf8(embedded::components_catalog_json_view()));
    return cat;
}

}  // namespace swbh::components
