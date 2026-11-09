// Attrappe des Modells für die Server-Tests: liefert festen Text, merkt sich die letzte Anfrage, kann blockieren.

#pragma once

#include "server/chat_api.hpp"

#include <atomic>
#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

namespace swbh::test {

class FakeBackend final : public server::CompletionBackend {
public:
    bool ready_ = true;
    std::string text = R"({"design":"d","reply":"r","steps":[{"op":"set"}]})";
    std::atomic<bool> block{false};     // complete() wartet, bis release oder cancel
    std::atomic<bool> entered{false};
    std::atomic<bool> cancelled{false};
    std::atomic<bool> release{false};
    llm::CompletionRequest last;

    bool ready() const override { return ready_; }
    std::string model_name() const override { return "Attrappe"; }
    std::optional<llm::CompletionResult> complete(const llm::CompletionRequest& request, std::string& error) override {
        last = request;
        entered = true;
        while (block && !release && !cancelled) {
            if (request.on_text) {
                request.on_text("x");  // wie die Engine: je Token ein Stück, darüber prüft der Server die Verbindung
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        if (cancelled) {
            error = "abgebrochen";
            return std::nullopt;
        }
        if (request.on_text) {
            request.on_text(text);
        }
        llm::CompletionResult r;
        r.text = text;
        r.prompt_tokens = 100;
        r.reused_tokens = 60;
        r.generated_tokens = 40;
        r.prompt_seconds = 0.5;
        r.seconds = 2.5;
        return r;
    }
    void cancel() override { cancelled = true; }
};

}  // namespace swbh::test
