#include "gui/app_config.hpp"

#include "base/errors.hpp"
#include "base/fileio.hpp"
#include "base/json.hpp"
#include "llm/models.hpp"
#include "tunnel/tunnel.hpp"
#include "voice/voice_style.hpp"
#include "win/cert_pin.hpp"

#include <algorithm>

namespace swbh::gui {

namespace {

void read_string(const Json& j, const char* key, std::string& out) {
    if (const Json* v = json_find(j, key); v != nullptr && v->is_string()) {
        out = v->get<std::string>();
    }
}

void read_bool(const Json& j, const char* key, bool& out) {
    if (const Json* v = json_find(j, key); v != nullptr && v->is_boolean()) {
        out = v->get<bool>();
    }
}

void read_int(const Json& j, const char* key, int lo, int hi, int& out, std::vector<std::string>& adjusted) {
    const Json* v = json_find(j, key);
    if (v == nullptr || !v->is_number_integer()) {
        return;
    }
    const long long raw = v->get<long long>();
    const long long clamped = std::clamp<long long>(raw, lo, hi);
    if (clamped != raw) {
        adjusted.emplace_back(key);
    }
    out = static_cast<int>(clamped);
}

}  // namespace

ConfigLoad load_config(const std::filesystem::path& file) {
    ConfigLoad out;
    std::optional<std::string> bytes;
    try {
        bytes = read_file_if_exists(file, 1024 * 1024);
    } catch (const std::exception& e) {
        out.error = e.what();
        return out;
    }
    if (!bytes) {
        return out;
    }
    const std::optional<Json> j = try_parse_utf8(*bytes, ParseOptions{true, false});
    if (!j || !j->is_object()) {
        out.error = "keine gültige JSON-Datei";
        return out;
    }
    AppConfig& c = out.config;
    int version = 1;
    if (const Json* v = json_find(*j, "version"); v != nullptr && v->is_number_integer()) {
        version = static_cast<int>(std::clamp<long long>(v->get<long long>(), 0, 1000));
    }
    read_string(*j, "model", c.model_key);
    read_string(*j, "custom_model", c.custom_model);
    if (json_find(*j, "kontext") != nullptr) {
        read_int(*j, "kontext", 0, 32768, c.n_ctx, out.adjusted);
        if (c.n_ctx > 0 && c.n_ctx < 2048) {
            out.adjusted.emplace_back("kontext");
            c.n_ctx = 2048;
        }
    } else {
        // Datei von vor 2026-10-29 (siehe app_config.hpp): 8192 war die Vorgabe → automatisch.
        int legacy = 0;
        read_int(*j, "n_ctx", 2048, 32768, legacy, out.adjusted);
        c.n_ctx = legacy == 8192 ? 0 : legacy;
    }
    read_string(*j, "compute", c.compute);
    if (c.compute != "auto" && c.compute != "vulkan" && c.compute != "cuda") {
        out.adjusted.emplace_back("compute");
        c.compute = "auto";
    }
    read_bool(*j, "allow_integrated", c.allow_integrated);
    read_int(*j, "server_port", 1024, 65535, c.server_port, out.adjusted);
    read_bool(*j, "tunnel_enabled", c.tunnel_enabled);
    // Verbindungsart: ab Version 3 maßgeblich „verbindung“; ältere Dateien kennen nur tunnel_enabled (ssh oder lokal).
    std::string link;
    read_string(*j, "verbindung", link);
    if (version >= 3 && link == "https") {
        c.relay_enabled = true;
        c.tunnel_enabled = false;
    } else if (version >= 3 && (link == "ssh" || link == "lokal")) {
        c.relay_enabled = false;
        c.tunnel_enabled = link == "ssh";
    } else {
        if (version >= 3) {
            out.adjusted.emplace_back("verbindung");  // fehlt oder unbekannt: tunnel_enabled gilt, Fernverbindung aus
        } else if (version == 2) {
            out.notes.push_back(std::string("Verbindungsart aus Version 2 übernommen: ") +
                                (c.tunnel_enabled ? "SSH-Tunnel" : "Server auf diesem PC") +
                                " – „im Internet über eine Adresse (HTTPS)“ ist neu und aus");
        }
        c.relay_enabled = false;
    }
    read_string(*j, "fern_adresse", c.relay_url);
    if (!relay::relay_url_allowed(c.relay_url)) {
        out.adjusted.emplace_back("fern_adresse");
        c.relay_url = std::string(relay::kDefaultRelayUrl);
    }
    if (const Json* pins = json_find(*j, "fern_server_pins"); pins != nullptr) {
        bool bad = !pins->is_array();
        if (pins->is_array()) {
            for (const Json& p : *pins) {
                const std::optional<std::string> pin =
                    p.is_string() ? win::parse_pin(p.get<std::string>()) : std::nullopt;
                if (!pin || c.relay_pins.size() >= 2 ||
                    std::find(c.relay_pins.begin(), c.relay_pins.end(), *pin) != c.relay_pins.end()) {
                    bad = true;  // kaputter, doppelter oder dritter Pin: weglassen statt raten
                    continue;
                }
                c.relay_pins.push_back(*pin);
            }
        }
        if (bad) {
            out.adjusted.emplace_back("fern_server_pins");
        }
    }
    std::string own;
    read_string(*j, "fern_eigener_pin", own);
    if (!own.empty()) {
        if (const std::optional<std::string> pin = win::parse_pin(own)) {
            c.relay_client_pin = *pin;
        } else {
            out.adjusted.emplace_back("fern_eigener_pin");
        }
    }
    read_string(*j, "tunnel_auth", c.tunnel_auth);
    if (c.tunnel_auth != "pageant" && c.tunnel_auth != "passwort") {
        out.adjusted.emplace_back("tunnel_auth");
        c.tunnel_auth = "pageant";
    }
    read_string(*j, "plink", c.plink);
    read_string(*j, "target", c.target);
    if (version < 2 && c.target.empty()) {
        // Bis 0.1.x hieß ein leeres Ziel „dev01“ (die Einstellungen speicherten die Vorgabe als leer). Ohne diese
        // Übernahme verlöre der eingerichtete GPU-PC mit dem Update still seinen Tunnel.
        c.target = std::string(tunnel::kLegacyTarget);
        out.notes.push_back("Server-Ziel aus der früheren Vorgabe übernommen: " + c.target);
    }
    read_int(*j, "ssh_port", 1, 65535, c.ssh_port, out.adjusted);
    read_int(*j, "remote_port", 1, 65535, c.remote_port, out.adjusted);
    read_string(*j, "hostkey", c.hostkey);
    read_bool(*j, "autostart", c.autostart);
    read_bool(*j, "modell_beim_start", c.load_model_on_start);
    read_bool(*j, "speculative", c.speculative);
    read_bool(*j, "notifications", c.notifications);
    read_bool(*j, "local_server", c.local_server);
    read_bool(*j, "research", c.research);
    read_string(*j, "minecraft_name", c.minecraft_name);
    read_bool(*j, "vorlesen", c.speech_out);
    read_string(*j, "stimme", c.tts_voice);
    read_string(*j, "tonlage", c.voice_style);
    if (voice::voice_style_pitch(c.voice_style) <= 0.0) {
        out.adjusted.emplace_back("tonlage");
        c.voice_style = "cartoon";
    }
    read_bool(*j, "sprechen", c.speech_in);
    read_string(*j, "taste", c.talk_key);
    read_string(*j, "mikrofon", c.microphone);
    read_string(*j, "modus", c.talk_mode);
    if (c.talk_mode != "halten" && c.talk_mode != "umschalten") {
        out.adjusted.emplace_back("modus");
        c.talk_mode = "halten";
    }
    read_int(*j, "mikro_db", -20, 30, c.mic_gain_db, out.adjusted);
    read_bool(*j, "mikro_automatik", c.auto_gain);
    read_bool(*j, "mikro_töne", c.speech_tones);
    read_bool(*j, "fremde_verstehen", c.hear_others);
    if (!c.model_key.empty() && c.model_key != llm::kCustomModelKey && llm::find_model(c.model_key) == nullptr) {
        out.adjusted.emplace_back("model");
        c.model_key.clear();
    }
    return out;
}

void save_config(const std::filesystem::path& file, const AppConfig& c) {
    Json j;
    j["version"] = kConfigVersion;
    j["model"] = c.model_key;
    j["custom_model"] = c.custom_model;
    j["kontext"] = c.n_ctx;
    j["compute"] = c.compute;
    j["allow_integrated"] = c.allow_integrated;
    j["server_port"] = c.server_port;
    j["verbindung"] = c.relay_enabled ? "https" : c.tunnel_enabled ? "ssh" : "lokal";
    j["tunnel_enabled"] = c.tunnel_enabled && !c.relay_enabled;
    j["fern_adresse"] = c.relay_url;
    j["fern_server_pins"] = c.relay_pins;
    j["fern_eigener_pin"] = c.relay_client_pin;
    j["tunnel_auth"] = c.tunnel_auth;
    j["plink"] = c.plink;
    j["target"] = c.target;
    j["ssh_port"] = c.ssh_port;
    j["remote_port"] = c.remote_port;
    j["hostkey"] = c.hostkey;
    j["autostart"] = c.autostart;
    j["modell_beim_start"] = c.load_model_on_start;
    j["speculative"] = c.speculative;
    j["notifications"] = c.notifications;
    j["local_server"] = c.local_server;
    j["research"] = c.research;
    j["minecraft_name"] = c.minecraft_name;
    j["vorlesen"] = c.speech_out;
    j["stimme"] = c.tts_voice;
    j["tonlage"] = c.voice_style;
    j["sprechen"] = c.speech_in;
    j["taste"] = c.talk_key;
    j["mikrofon"] = c.microphone;
    j["modus"] = c.talk_mode;
    j["mikro_db"] = c.mic_gain_db;
    j["mikro_automatik"] = c.auto_gain;
    j["mikro_töne"] = c.speech_tones;
    j["fremde_verstehen"] = c.hear_others;
    try {
        write_file_atomic(file, j.dump(2) + "\n");
    } catch (const std::exception& e) {
        throw UserError(std::string("Einstellungen nicht speicherbar: ") + e.what());
    }
}

std::string effective_model_key(const AppConfig& c) {
    return c.model_key.empty() ? llm::default_model().key : c.model_key;
}

std::string effective_plink(const AppConfig& c) {
    return c.plink.empty() ? std::string(tunnel::kDefaultPlink) : c.plink;
}

std::string effective_target(const AppConfig& c) { return c.target; }

std::string target_user(std::string_view target) {
    const std::size_t at = target.find('@');
    return at == std::string_view::npos ? std::string() : std::string(target.substr(0, at));
}

std::string target_host(std::string_view target) {
    const std::size_t at = target.find('@');
    return std::string(at == std::string_view::npos ? target : target.substr(at + 1));
}

std::string make_target(std::string_view user, std::string_view host) {
    if (host.empty()) {
        return {};
    }
    return user.empty() ? std::string(host) : std::string(user) + "@" + std::string(host);
}

}  // namespace swbh::gui
