// Transportunabhängige Teile von http.hpp: CancelToken, HttpResponse::header,
// parse_url, host_allowed. Der WinHTTP-Transport liegt in winhttp_transport.cpp
// und winhttp_download.cpp, Proxy-Regeln in proxy.cpp, Fehlerzuordnung in tls_diag.cpp.

#include "win/http.hpp"

#include "base/pytext.hpp"

#include <charconv>
#include <condition_variable>

namespace swbh::win {

void CancelToken::cancel() {
    std::vector<std::pair<std::uint64_t, std::function<void()>>> to_run;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (cancelled_) {
            return;
        }
        cancelled_ = true;
        running_ = true;
        runner_ = std::this_thread::get_id();
        to_run.swap(actions_);
    }
    // Außerhalb der Sperre: eine Aktion darf selbst remove_cancel_action aufrufen.
    for (auto& entry : to_run) {
        if (entry.second) {
            entry.second();
        }
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        running_ = false;
        runner_ = std::thread::id();
    }
    done_.notify_all();
}

bool CancelToken::cancelled() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return cancelled_;
}

std::uint64_t CancelToken::add_cancel_action(std::function<void()> action) {
    std::unique_lock<std::mutex> lock(mutex_);
    const std::uint64_t id = next_id_++;
    if (cancelled_) {
        lock.unlock();
        if (action) {
            action();
        }
        return id;
    }
    actions_.emplace_back(id, std::move(action));
    return id;
}

CancelWhen::CancelWhen(CancelToken& token, std::function<bool()> stop, std::chrono::milliseconds poll) {
    thread_ = std::jthread([&token, stop = std::move(stop), poll](std::stop_token st) {
        std::mutex m;
        std::condition_variable_any cv;
        std::unique_lock<std::mutex> lock(m);
        while (!st.stop_requested()) {
            if (stop && stop()) {
                token.cancel();
                return;
            }
            // Wacht beim stop-Wunsch des Destruktors sofort auf – kein Warten bis zum nächsten Takt.
            cv.wait_for(lock, st, poll, [] { return false; });
        }
    });
}

CancelWhen::~CancelWhen() = default;  // jthread: request_stop + join

void CancelToken::remove_cancel_action(std::uint64_t id) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (running_ && runner_ != std::this_thread::get_id()) {
        done_.wait(lock, [this] { return !running_; });
    }
    for (auto it = actions_.begin(); it != actions_.end(); ++it) {
        if (it->first == id) {
            actions_.erase(it);
            return;
        }
    }
}

std::optional<std::string> HttpResponse::header(std::string_view lower_name) const {
    std::optional<std::string> found;
    for (const auto& h : headers) {
        if (h.first == lower_name) {
            found = h.second;
        }
    }
    return found;
}

std::optional<ParsedUrl> parse_url(std::string_view url) {
    const std::size_t scheme_end = url.find("://");
    if (scheme_end == std::string_view::npos) {
        return std::nullopt;
    }
    ParsedUrl out;
    out.scheme = ascii_lower(url.substr(0, scheme_end));
    if (out.scheme != "https" && out.scheme != "http") {
        return std::nullopt;
    }
    std::string_view rest = url.substr(scheme_end + 3);
    const std::size_t path_start = rest.find_first_of("/?#");
    std::string_view authority = rest.substr(0, path_start);
    std::string_view path = path_start == std::string_view::npos ? std::string_view{} : rest.substr(path_start);
    // Benutzerangaben (user:pass@host) sind nie gewollt und würden Hostprüfungen täuschen.
    if (authority.empty() || authority.find('@') != std::string_view::npos) {
        return std::nullopt;
    }
    // IPv6-Literale kommen in diesem Programm nicht vor; lieber ablehnen als falsch parsen.
    if (authority.front() == '[') {
        return std::nullopt;
    }
    const std::size_t colon = authority.rfind(':');
    std::string_view host = authority;
    out.port = out.scheme == "https" ? 443 : 80;
    if (colon != std::string_view::npos) {
        host = authority.substr(0, colon);
        const std::string_view port_text = authority.substr(colon + 1);
        int port = 0;
        const auto res = std::from_chars(port_text.data(), port_text.data() + port_text.size(), port);
        if (port_text.empty() || res.ec != std::errc{} || res.ptr != port_text.data() + port_text.size() ||
            port < 1 || port > 65535) {
            return std::nullopt;
        }
        out.port = port;
    }
    if (host.empty()) {
        return std::nullopt;
    }
    for (const char c : host) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' ||
                        c == '.';
        if (!ok) {
            return std::nullopt;
        }
    }
    out.host = ascii_lower(host);
    // Fragment gehört nicht in die Anfrage.
    const std::size_t hash = path.find('#');
    if (hash != std::string_view::npos) {
        path = path.substr(0, hash);
    }
    if (path.empty()) {
        out.path_and_query = "/";
    } else if (path.front() == '?') {
        out.path_and_query = "/" + std::string(path);
    } else {
        out.path_and_query = std::string(path);
    }
    return out;
}

bool host_allowed(std::string_view host, const std::vector<std::string>& allow_list) {
    const std::string h = ascii_lower(host);
    for (const auto& entry_raw : allow_list) {
        const std::string entry = ascii_lower(entry_raw);
        if (entry.rfind("*.", 0) == 0) {
            const std::string suffix = entry.substr(1);  // ".hf.co"
            if (h.size() > suffix.size() && h.compare(h.size() - suffix.size(), suffix.size(), suffix) == 0) {
                return true;
            }
        } else if (h == entry) {
            return true;
        }
    }
    return false;
}

}  // namespace swbh::win
