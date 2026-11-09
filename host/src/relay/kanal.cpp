#include "relay/kanal.hpp"

#include "base/json.hpp"
#include "base/utf8.hpp"

#include <algorithm>
#include <limits>
#include <set>
#include <stdexcept>
#include <vector>

namespace swbh::relay {

namespace {

// Die Gründe stehen ASCII auf der Leitung (Close-Grund, Testvektoren) – deshalb ohne Umlaute.
constexpr std::string_view kHeadTruncated = "kopf_abgeschnitten";
constexpr std::string_view kHeadEmpty = "kopf_leer";
constexpr std::string_view kHeadTooLong = "kopf_zu_lang";
constexpr std::string_view kHeadNotUtf8 = "kopf_kein_utf8";
constexpr std::string_view kHeadNotObject = "kopf_kein_objekt";
constexpr std::string_view kTypeUnknown = "typ_unbekannt";
constexpr std::string_view kIdMissing = "id_fehlt";
constexpr std::string_view kIdInvalid = "id_ungueltig";             // umlaut-ok: Grund aus der Spezifikation
constexpr std::string_view kStatusInvalid = "status_ungueltig";     // umlaut-ok
constexpr std::string_view kPieceTooBig = "stueck_zu_gross";        // umlaut-ok
constexpr std::string_view kLengthInvalid = "laenge_ungueltig";     // umlaut-ok
constexpr std::string_view kPathInvalid = "pfad_ungueltig";         // umlaut-ok
constexpr std::string_view kMethodInvalid = "methode_ungueltig";    // umlaut-ok
constexpr std::string_view kContentTypeInvalid = "typ_ungueltig";   // umlaut-ok
constexpr std::string_view kVersionInvalid = "version_ungueltig";   // umlaut-ok: Close 1008 (Abschnitt 3)
constexpr std::string_view kPeerInvalid = "kennung_ungueltig";      // umlaut-ok
constexpr std::string_view kMaxInvalid = "max_ungueltig";           // umlaut-ok

// Tiefe Schachtelung in unbekannten Feldern: Der Kopf ist flach, 8 Ebenen reichen für jede Erweiterung.
constexpr std::size_t kMaxDepth = 8;

bool is_alnum(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'); }

bool is_hex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }

bool printable_ascii(std::string_view s) {
    for (const char c : s) {
        const auto u = static_cast<unsigned char>(c);
        if (u < 0x20 || u > 0x7E) {
            return false;
        }
    }
    return true;
}

// SAX-Vorprüfung: doppelte Schlüssel auf oberster Ebene und Schachtelung über kMaxDepth. nlohmann nähme bei
// doppelten Schlüsseln still den letzten, Javas Parser im Relay womöglich den ersten – genau diese Lücke soll es
// nicht geben.
class StrictHead final : public nlohmann::json_sax<Json> {
public:
    bool null() override { return true; }
    bool boolean(bool) override { return true; }
    bool number_integer(number_integer_t) override { return true; }
    bool number_unsigned(number_unsigned_t) override { return true; }
    bool number_float(number_float_t, const string_t&) override { return true; }
    bool string(string_t&) override { return true; }
    bool binary(binary_t&) override { return false; }
    bool start_object(std::size_t) override { return enter(); }
    bool end_object() override { return leave(); }
    bool start_array(std::size_t) override { return enter(); }
    bool end_array() override { return leave(); }
    bool key(string_t& k) override {
        if (depth_ == 1) {
            return keys_.insert(k).second;
        }
        return true;
    }
    bool parse_error(std::size_t, const std::string&, const nlohmann::detail::exception&) override { return false; }

private:
    bool enter() {
        ++depth_;
        return depth_ <= kMaxDepth;
    }
    bool leave() {
        --depth_;
        return true;
    }

    std::size_t depth_ = 0;
    std::set<std::string> keys_;
};

std::optional<Json> parse_head(std::string_view head) {
    StrictHead sax;
    try {
        if (!Json::sax_parse(head, &sax, nlohmann::detail::input_format_t::json, true)) {
            return std::nullopt;
        }
    } catch (const std::exception&) {
        return std::nullopt;
    }
    std::optional<Json> j = try_parse_utf8(head);
    if (!j || !j->is_object()) {
        return std::nullopt;
    }
    return j;
}

// Ganzzahl ohne Vorzeichen aus einem JSON-Wert (auch als number_integer ≥ 0); nullopt bei Bruch, Text, negativ.
std::optional<std::uint64_t> as_u64(const Json& v) {
    if (v.is_number_unsigned()) {
        return v.get<std::uint64_t>();
    }
    if (v.is_number_integer()) {
        const auto i = v.get<std::int64_t>();
        if (i >= 0) {
            return static_cast<std::uint64_t>(i);
        }
    }
    return std::nullopt;
}

// id: Pflicht, 1 … 4 294 967 295.
std::optional<std::uint32_t> read_id(const Json& head, std::string& reason) {
    const Json* v = json_find(head, "id");
    if (v == nullptr) {
        reason = kIdMissing;
        return std::nullopt;
    }
    const std::optional<std::uint64_t> id = as_u64(*v);
    if (!id || *id == 0 || *id > std::numeric_limits<std::uint32_t>::max()) {
        reason = kIdInvalid;
        return std::nullopt;
    }
    return static_cast<std::uint32_t>(*id);
}

bool read_content_type(const Json& head, Message& m, std::string& reason) {
    const Json* v = json_find(head, "ct");
    if (v == nullptr) {
        return true;
    }
    if (!v->is_string() || !content_type_allowed(v->get_ref<const std::string&>())) {
        reason = kContentTypeInvalid;
        return false;
    }
    m.content_type = v->get<std::string>();
    return true;
}

bool read_length(const Json& head, std::uint64_t limit, Message& m, std::string& reason) {
    const Json* v = json_find(head, "len");
    const std::optional<std::uint64_t> len = v != nullptr ? as_u64(*v) : std::nullopt;
    // Mehr Bytes im ersten Stück als len: Protokollfehler (Abschnitt 3).
    if (!len || *len > limit || m.data.size() > *len) {
        reason = kLengthInvalid;
        return false;
    }
    m.length = *len;
    return true;
}

Decoded fail(std::string_view reason) { return Decoded{std::nullopt, std::string(reason)}; }

std::string head_json(const Message& m, Sender from) {
    Json h = Json::object();
    h["t"] = std::string(type_name(m.type));
    switch (m.type) {
    case Type::Hallo:
        h["v"] = m.version;
        if (from == Sender::App) {
            h["app"] = m.peer;
            h["max"] = m.max_jobs;
        } else {
            h["relay"] = m.peer;
        }
        break;
    case Type::Anfrage:
        h["id"] = m.id;
        h["m"] = m.method;
        h["p"] = m.path;
        if (m.content_type) {
            h["ct"] = *m.content_type;
        }
        h["len"] = m.length;
        break;
    case Type::Antwort:
        h["id"] = m.id;
        h["s"] = m.status;
        if (m.content_type) {
            h["ct"] = *m.content_type;
        }
        h["len"] = m.length;
        break;
    case Type::Daten:
    case Type::Abbruch:
        h["id"] = m.id;
        break;
    }
    return dump_compact(h);
}

}  // namespace

std::string_view type_name(Type type) {
    switch (type) {
    case Type::Hallo: return "hallo";
    case Type::Anfrage: return "anfrage";
    case Type::Antwort: return "antwort";
    case Type::Daten: return "daten";
    case Type::Abbruch: return "abbruch";
    }
    return "?";
}

bool content_type_allowed(std::string_view ct) {
    return !ct.empty() && ct.size() <= kMaxContentTypeBytes && printable_ascii(ct);
}

bool path_allowed(std::string_view p) {
    if (p.empty() || p.size() > kMaxPathBytes) {
        return false;
    }
    std::string_view path = p;
    std::string_view query;
    bool has_query = false;
    if (const std::size_t q = p.find('?'); q != std::string_view::npos) {
        path = p.substr(0, q);
        query = p.substr(q + 1);
        has_query = true;
    }
    if (has_query) {
        if (query.empty()) {
            return false;
        }
        for (std::size_t i = 0; i < query.size(); ++i) {
            const char c = query[i];
            if (c == '%') {
                // Nur gültige %XX und nie %2e/%2E (Abschnitt 5) – ein „.“ hat in Spielernamen nichts kodiert verloren.
                if (i + 2 >= query.size() || !is_hex(query[i + 1]) || !is_hex(query[i + 2])) {
                    return false;
                }
                if (query[i + 1] == '2' && (query[i + 2] == 'e' || query[i + 2] == 'E')) {
                    return false;
                }
                // Kodierte Steuerzeichen %00–%1F und %7F lehnt das Relay ab, jetzt auch die App (Sicherheitsprüfung
                // 2026-11-08, N6): Ein kodiertes NUL kann im eigenen C++-Server einen String abschneiden.
                const auto nibble = [](char ch) { return ch <= '9' ? ch - '0' : (ch | 0x20) - 'a' + 10; };
                const int value = nibble(query[i + 1]) * 16 + nibble(query[i + 2]);
                if (value < 0x20 || value == 0x7F) {
                    return false;
                }
                i += 2;
                continue;
            }
            // Komma seit 2026-11-08: Das Plugin schickt „?imspiel=Name1,Name2“ (CompanionVoiceLink.heardUri, liest
            // companion_api.cpp query_in_game). Ohne Komma bekam der Long-Poll ab zwei Spielern im Voice-Chat 404.
            if (!is_alnum(c) && std::string_view("_.~=&+,-").find(c) == std::string_view::npos) {
                return false;
            }
        }
    }
    if (path == "/health") {
        return !has_query;
    }
    std::string_view rest;
    if (path.starts_with("/v1/")) {
        rest = path.substr(4);
    } else if (path.starts_with("/baumeister/v1/")) {
        rest = path.substr(15);
    } else {
        return false;
    }
    // Segmente nicht leer (kein „//“, kein „/“ am Ende), nur [A-Za-z0-9_-] – damit sind „.“, „..“, „%2e“, „\“ und
    // Leerraum schon ausgeschlossen.
    if (rest.empty()) {
        return false;
    }
    std::size_t start = 0;
    while (start <= rest.size()) {
        const std::size_t slash = rest.find('/', start);
        const std::string_view seg = rest.substr(start, slash == std::string_view::npos ? std::string_view::npos
                                                                                       : slash - start);
        if (seg.empty()) {
            return false;
        }
        for (const char c : seg) {
            if (!is_alnum(c) && c != '_' && c != '-') {
                return false;
            }
        }
        if (slash == std::string_view::npos) {
            break;
        }
        start = slash + 1;
    }
    return true;
}

bool relay_url_allowed(std::string_view url) {
    constexpr std::string_view scheme = "wss://";
    if (!url.starts_with(scheme) || url.size() > 300) {
        return false;
    }
    const std::string_view rest = url.substr(scheme.size());
    const std::size_t slash = rest.find('/');
    if (slash == std::string_view::npos || rest.substr(slash) != kChannelPath) {
        return false;
    }
    const std::string_view host_port = rest.substr(0, slash);
    std::string_view host = host_port;
    if (const std::size_t colon = host_port.find(':'); colon != std::string_view::npos) {
        host = host_port.substr(0, colon);
        const std::string_view port = host_port.substr(colon + 1);
        if (port.empty() || port.size() > 5 || port.front() == '0' ||
            !std::all_of(port.begin(), port.end(), [](char c) { return c >= '0' && c <= '9'; })) {
            return false;
        }
        int value = 0;
        for (const char c : port) {
            value = value * 10 + (c - '0');
        }
        if (value < 1 || value > 65535) {
            return false;
        }
    }
    if (host.empty() || host.size() > 253 || host.front() == '-' || host.front() == '.' || host.back() == '-' ||
        host.back() == '.' || host.find("..") != std::string_view::npos) {
        return false;
    }
    return std::all_of(host.begin(), host.end(), [](char c) { return is_alnum(c) || c == '.' || c == '-'; });
}

std::string relay_host_port(std::string_view url) {
    if (!relay_url_allowed(url)) {
        return {};
    }
    const std::string_view rest = url.substr(6);
    const std::string_view host_port = rest.substr(0, rest.find('/'));
    return host_port.find(':') == std::string_view::npos ? std::string(host_port) + ":443" : std::string(host_port);
}

int close_code(std::string_view reason) {
    if (reason == "textrahmen") {
        return kCloseDataType;
    }
    if (reason == kVersionInvalid) {
        return kClosePolicy;
    }
    if (reason == "rahmen_zu_gross" || reason == kPieceTooBig) {  // umlaut-ok: Grund aus der Spezifikation
        return kCloseTooBig;
    }
    return kCloseProtocol;
}

Decoded decode(std::string_view payload, Sender from) {
    if (payload.size() < 2) {
        return fail(kHeadTruncated);
    }
    const std::size_t head_len =
        (static_cast<std::size_t>(static_cast<unsigned char>(payload[0])) << 8) |
        static_cast<std::size_t>(static_cast<unsigned char>(payload[1]));
    if (head_len == 0) {
        return fail(kHeadEmpty);
    }
    if (head_len > kMaxHeadBytes) {
        return fail(kHeadTooLong);
    }
    if (head_len > payload.size() - 2) {
        return fail(kHeadTruncated);
    }
    const std::string_view head = payload.substr(2, head_len);
    if (!is_valid_utf8(head)) {
        return fail(kHeadNotUtf8);
    }
    const std::optional<Json> j = parse_head(head);
    if (!j) {
        return fail(kHeadNotObject);
    }
    const Json* t = json_find(*j, "t");
    if (t == nullptr || !t->is_string()) {
        return fail(kTypeUnknown);
    }
    const std::string& tn = t->get_ref<const std::string&>();
    Message m;
    if (tn == "hallo") {
        m.type = Type::Hallo;
    } else if (tn == "anfrage" && from == Sender::Relay) {
        m.type = Type::Anfrage;
    } else if (tn == "antwort" && from == Sender::App) {
        m.type = Type::Antwort;
    } else if (tn == "daten") {
        m.type = Type::Daten;
    } else if (tn == "abbruch") {
        m.type = Type::Abbruch;
    } else {
        return fail(kTypeUnknown);
    }
    const std::string_view data = payload.substr(2 + head_len);
    if (data.size() > kMaxPieceBytes) {
        return fail(kPieceTooBig);
    }
    m.data.assign(data);
    std::string reason;
    switch (m.type) {
    case Type::Hallo: {
        const Json* v = json_find(*j, "v");
        if (v == nullptr || as_u64(*v) != std::optional<std::uint64_t>(static_cast<std::uint64_t>(kVersion))) {
            return fail(kVersionInvalid);
        }
        m.version = kVersion;
        const Json* peer = json_find(*j, from == Sender::App ? "app" : "relay");
        if (peer == nullptr || !peer->is_string() || peer->get_ref<const std::string&>().empty() ||
            peer->get_ref<const std::string&>().size() > kMaxPeerNameBytes ||
            !printable_ascii(peer->get_ref<const std::string&>())) {
            return fail(kPeerInvalid);
        }
        m.peer = peer->get<std::string>();
        if (from == Sender::App) {
            const Json* mx = json_find(*j, "max");
            const std::optional<std::uint64_t> n = mx != nullptr ? as_u64(*mx) : std::nullopt;
            if (!n || *n < 1 || *n > static_cast<std::uint64_t>(kMaxJobs)) {
                return fail(kMaxInvalid);
            }
            m.max_jobs = static_cast<int>(*n);
        }
        if (!m.data.empty()) {
            return fail(kPieceTooBig);
        }
        return Decoded{std::move(m), {}};
    }
    case Type::Anfrage: {
        const std::optional<std::uint32_t> id = read_id(*j, reason);
        if (!id) {
            return fail(reason);
        }
        m.id = *id;
        const Json* method = json_find(*j, "m");
        if (method == nullptr || !method->is_string() ||
            (method->get_ref<const std::string&>() != "GET" && method->get_ref<const std::string&>() != "POST")) {
            return fail(kMethodInvalid);
        }
        m.method = method->get<std::string>();
        const Json* path = json_find(*j, "p");
        if (path == nullptr || !path->is_string() || !path_allowed(path->get_ref<const std::string&>())) {
            return fail(kPathInvalid);
        }
        m.path = path->get<std::string>();
        if (!read_content_type(*j, m, reason) || !read_length(*j, kMaxRequestBytes, m, reason)) {
            return fail(reason);
        }
        return Decoded{std::move(m), {}};
    }
    case Type::Antwort: {
        const std::optional<std::uint32_t> id = read_id(*j, reason);
        if (!id) {
            return fail(reason);
        }
        m.id = *id;
        const Json* s = json_find(*j, "s");
        const std::optional<std::uint64_t> status = s != nullptr ? as_u64(*s) : std::nullopt;
        // 200…599 (Sicherheitsprüfung 2026-11-08, M1): 1xx verwirft der HttpClient des Plugins und liest den Body als
        // endgültige Antwort – ein Weg, Kopfzeilen wie X-Baumeister-App unterzuschieben.
        if (!status || *status < 200 || *status > 599) {
            return fail(kStatusInvalid);
        }
        m.status = static_cast<int>(*status);
        if (!read_content_type(*j, m, reason) || !read_length(*j, kMaxResponseBytes, m, reason)) {
            return fail(reason);
        }
        return Decoded{std::move(m), {}};
    }
    case Type::Daten:
    case Type::Abbruch: {
        const std::optional<std::uint32_t> id = read_id(*j, reason);
        if (!id) {
            return fail(reason);
        }
        m.id = *id;
        if (m.type == Type::Abbruch && !m.data.empty()) {
            return fail(kPieceTooBig);
        }
        return Decoded{std::move(m), {}};
    }
    }
    return fail(kTypeUnknown);
}

std::string encode(const Message& m, Sender from) {
    // Eigene Nachrichten durchlaufen dieselben Regeln wie fremde: Was hier durchgeht, liest auch das Relay.
    if ((m.type == Type::Anfrage && from != Sender::Relay) || (m.type == Type::Antwort && from != Sender::App)) {
        throw std::invalid_argument("Kanal: Nachrichtentyp passt nicht zum Absender");
    }
    if (m.type == Type::Hallo && (m.peer.empty() || m.peer.size() > kMaxPeerNameBytes || !printable_ascii(m.peer) ||
                                  (from == Sender::App && (m.max_jobs < 1 || m.max_jobs > kMaxJobs)))) {
        throw std::invalid_argument("Kanal: hallo mit ungültiger Kennung oder max");
    }
    if (m.type != Type::Hallo && m.id == 0) {
        throw std::invalid_argument("Kanal: Nachricht ohne id");
    }
    if (m.data.size() > kMaxPieceBytes) {
        throw std::invalid_argument("Kanal: Datenstück größer als 64 KiB");
    }
    if (m.content_type && !content_type_allowed(*m.content_type)) {
        throw std::invalid_argument("Kanal: ungültiger Content-Type");
    }
    if ((m.type == Type::Anfrage || m.type == Type::Antwort) && m.data.size() > m.length) {
        throw std::invalid_argument("Kanal: erstes Stück länger als len");
    }
    if (m.type == Type::Antwort && (m.status < 200 || m.status > 599 || m.length > kMaxResponseBytes)) {
        throw std::invalid_argument("Kanal: Status oder Länge der Antwort außerhalb der Grenzen");
    }
    if ((m.type == Type::Hallo || m.type == Type::Abbruch) && !m.data.empty()) {
        throw std::invalid_argument("Kanal: hallo/abbruch tragen keine Daten");
    }
    const std::string head = head_json(m, from);
    if (head.size() > kMaxHeadBytes) {
        throw std::invalid_argument("Kanal: Kopf länger als 4096 Bytes");
    }
    std::string out;
    out.reserve(2 + head.size() + m.data.size());
    out.push_back(static_cast<char>((head.size() >> 8) & 0xFF));
    out.push_back(static_cast<char>(head.size() & 0xFF));
    out += head;
    out += m.data;
    return out;
}

}  // namespace swbh::relay
