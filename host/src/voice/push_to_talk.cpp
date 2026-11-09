#include "voice/push_to_talk.hpp"

#include <windows.h>

#include <atomic>
#include <cctype>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace swbh::voice {

namespace {

std::string lower(std::string_view s) {
    std::string out;
    for (const char c : s) {
        out += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return out;
}

}  // namespace

std::optional<TalkKey> parse_talk_key(std::string_view text) {
    std::string t = lower(text);
    while (!t.empty() && t.front() == ' ') {
        t.erase(t.begin());
    }
    while (!t.empty() && t.back() == ' ') {
        t.pop_back();
    }
    if (t == "maus4" || t == "maus-zurueck" || t == "xbutton1") {  // umlaut-ok: Eingabe ohne Umlaut-Tastatur
        return TalkKey{TalkKey::Kind::Mouse, 1};
    }
    if (t == "maus5" || t == "xbutton2") {
        return TalkKey{TalkKey::Kind::Mouse, 2};
    }
    if (t == "mausmitte" || t == "maus3") {
        return TalkKey{TalkKey::Kind::Mouse, 3};
    }
    if (t.size() == 1 && ((t[0] >= 'a' && t[0] <= 'z') || (t[0] >= '0' && t[0] <= '9'))) {
        return TalkKey{TalkKey::Kind::Keyboard, std::toupper(static_cast<unsigned char>(t[0]))};
    }
    if (t.size() >= 2 && t.size() <= 3 && t[0] == 'f') {
        int n = 0;
        for (std::size_t i = 1; i < t.size(); ++i) {
            if (t[i] < '0' || t[i] > '9') {
                return std::nullopt;
            }
            n = n * 10 + (t[i] - '0');
        }
        if (n >= 1 && n <= 24) {
            return TalkKey{TalkKey::Kind::Keyboard, VK_F1 + n - 1};
        }
    }
    return std::nullopt;
}

std::string talk_key_title(const TalkKey& key) {
    if (key.kind == TalkKey::Kind::Mouse) {
        return key.code == 1 ? "Maus-Seitentaste hinten" : key.code == 2 ? "Maus-Seitentaste vorn" : "mittlere Maustaste";
    }
    if (key.code >= VK_F1 && key.code <= VK_F24) {
        return "F" + std::to_string(key.code - VK_F1 + 1);
    }
    return std::string("Taste ") + static_cast<char>(key.code);
}

// Die Hook-Rückrufe sind statisch und kennen nur diesen Zeiger (höchstens ein PushToTalk zugleich).
struct PushToTalk::Impl {
    TalkKey key;
    ChangeFn on_change;
    PressEdge edge;
    std::thread thread;
    std::atomic<DWORD> thread_id{0};
    HHOOK hook = nullptr;
    std::mutex start_mutex;
    std::string start_error;
    bool started = false;
    bool start_done = false;
    std::condition_variable_any start_cv;

    static std::atomic<Impl*>& current() {
        static std::atomic<Impl*> p{nullptr};
        return p;
    }

    void report(bool down) {
        if (const std::optional<bool> e = edge.feed(down); e && on_change) {
            on_change(*e);
        }
    }

    static LRESULT CALLBACK mouse_proc(int code, WPARAM w, LPARAM l) {
        Impl* self = current().load();
        if (code == HC_ACTION && self != nullptr && self->key.kind == TalkKey::Kind::Mouse) {
            const auto* info = reinterpret_cast<const MSLLHOOKSTRUCT*>(l);
            if (self->key.code == 3 && (w == WM_MBUTTONDOWN || w == WM_MBUTTONUP)) {
                self->report(w == WM_MBUTTONDOWN);
            } else if ((w == WM_XBUTTONDOWN || w == WM_XBUTTONUP) && self->key.code != 3) {
                const int button = HIWORD(info->mouseData) == XBUTTON1 ? 1 : 2;
                if (button == self->key.code) {
                    self->report(w == WM_XBUTTONDOWN);
                }
            }
        }
        return CallNextHookEx(nullptr, code, w, l);
    }

    static LRESULT CALLBACK keyboard_proc(int code, WPARAM w, LPARAM l) {
        Impl* self = current().load();
        if (code == HC_ACTION && self != nullptr && self->key.kind == TalkKey::Kind::Keyboard) {
            const auto* info = reinterpret_cast<const KBDLLHOOKSTRUCT*>(l);
            if (static_cast<int>(info->vkCode) == self->key.code) {
                self->report(w == WM_KEYDOWN || w == WM_SYSKEYDOWN);
            }
        }
        return CallNextHookEx(nullptr, code, w, l);
    }

    void run() {
        thread_id = GetCurrentThreadId();
        // Nachrichtenschlange sofort anlegen: PostThreadMessage aus stop() ginge sonst verloren, wenn es vor dem ersten
        // GetMessage käme.
        MSG first;
        PeekMessageW(&first, nullptr, WM_USER, WM_USER, PM_NOREMOVE);
        hook = SetWindowsHookExW(key.kind == TalkKey::Kind::Mouse ? WH_MOUSE_LL : WH_KEYBOARD_LL,
                                 key.kind == TalkKey::Kind::Mouse ? mouse_proc : keyboard_proc,
                                 GetModuleHandleW(nullptr), 0);
        {
            std::lock_guard<std::mutex> lock(start_mutex);
            started = hook != nullptr;
            if (!started) {
                start_error = "Haltetaste nicht einrichtbar (SetWindowsHookEx, Fehler " +
                              std::to_string(GetLastError()) + ")";
            }
            start_done = true;
        }
        start_cv.notify_all();
        if (hook == nullptr) {
            return;
        }
        MSG msg;
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        UnhookWindowsHookEx(hook);
        hook = nullptr;
    }
};

PushToTalk::PushToTalk() : impl_(std::make_unique<Impl>()) {}

PushToTalk::~PushToTalk() { stop(); }

bool PushToTalk::start(const TalkKey& key, ChangeFn on_change, std::string& error) {
    stop();
    Impl* expected = nullptr;
    if (!Impl::current().compare_exchange_strong(expected, impl_.get())) {
        error = "Es läuft schon eine Haltetaste";
        return false;
    }
    impl_->key = key;
    impl_->on_change = std::move(on_change);
    impl_->edge = PressEdge{};
    impl_->start_done = false;
    impl_->start_error.clear();
    Impl* raw = impl_.get();
    impl_->thread = std::thread([raw] { raw->run(); });
    std::unique_lock<std::mutex> lock(impl_->start_mutex);
    impl_->start_cv.wait(lock, [raw] { return raw->start_done; });
    if (!impl_->started) {
        error = impl_->start_error;
        lock.unlock();
        impl_->thread.join();
        Impl::current() = nullptr;
        return false;
    }
    return true;
}

void PushToTalk::stop() {
    if (!impl_->thread.joinable()) {
        return;
    }
    if (const DWORD id = impl_->thread_id.load(); id != 0) {
        PostThreadMessageW(id, WM_QUIT, 0, 0);
    }
    impl_->thread.join();
    impl_->thread_id = 0;
    Impl* self = impl_.get();
    Impl::current().compare_exchange_strong(self, nullptr);
    // Losgelassen melden, falls die Taste beim Abschalten gedrückt war – sonst bliebe die Aufnahme offen.
    if (impl_->edge.down() && impl_->on_change) {
        impl_->edge.feed(false);
        impl_->on_change(false);
    }
}

}  // namespace swbh::voice
