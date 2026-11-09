#include "relay/forwarder.hpp"

#include "base/version.hpp"
#include "relay/kanal.hpp"

#include <utility>

namespace swbh::relay {

namespace {

std::string_view path_only(std::string_view p) { return p.substr(0, p.find('?')); }

}  // namespace

std::chrono::milliseconds receive_timeout_for(std::string_view path) {
    using std::chrono::seconds;
    const std::string_view p = path_only(path);
    if (p == "/v1/chat/completions") {
        return seconds(1900);
    }
    if (p == "/baumeister/v1/begleiter/gehoert") {  // umlaut-ok: URL ASCII
        return seconds(40);
    }
    if (p == "/baumeister/v1/begleiter/hoeren" || p == "/baumeister/v1/begleiter/stimme") {  // umlaut-ok
        return seconds(120);
    }
    return seconds(30);
}

std::optional<LocalResponse> forward_local(win::HttpTransport& http, int port, const LocalRequest& request,
                                           win::CancelToken& cancel, std::string& error) {
    // Zweite Sperre neben decode(): Was hier ankommt, hat path_allowed schon bestanden – ein Aufrufer, der das
    // umgeht, soll trotzdem nichts anderes als den eigenen Server erreichen (fail-closed).
    if (!path_allowed(request.path) || (request.method != "GET" && request.method != "POST") || port < 1 ||
        port > 65535) {
        error = "Auftrag nicht weiterleitbar (Pfad, Methode oder Port)";
        return std::nullopt;
    }
    win::HttpRequest r;
    r.method = request.method;
    r.url = "http://127.0.0.1:" + std::to_string(port) + request.path;
    r.body = request.body;
    r.user_agent = "BaumeisterHost-Kanal/" + std::string(kAppVersion);
    if (request.content_type) {
        r.headers.emplace_back("Content-Type", *request.content_type);
    }
    const auto wait = receive_timeout_for(request.path);
    r.timeouts = win::HttpTimeouts{0, 5000, 30000, static_cast<int>(wait.count())};
    r.redirects = win::Redirects::None;
    r.allow_plain_http = true;  // nur 127.0.0.1 (URL oben fest), nie ins Netz
    r.max_body = static_cast<std::size_t>(kMaxResponseBytes);
    try {
        win::HttpResponse resp = http.send(r, &cancel);
        if (resp.status < 100 || resp.status > 599) {
            error = "eigener Server antwortete mit Status " + std::to_string(resp.status);
            return std::nullopt;
        }
        LocalResponse out;
        out.status = resp.status;
        if (const auto ct = resp.header("content-type"); ct && content_type_allowed(*ct)) {
            out.content_type = *ct;
        }
        out.body = std::move(resp.body);
        return out;
    } catch (const win::HttpError& e) {
        error = e.kind == win::HttpError::Kind::Cancelled ? std::string("abgebrochen")
                                                          : std::string("eigener Server: ") + e.what();
        return std::nullopt;
    }
}

}  // namespace swbh::relay
