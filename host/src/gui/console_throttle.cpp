#include "gui/console_throttle.hpp"

namespace swbh::gui {

std::optional<std::size_t> ConsoleThrottle::allow(std::chrono::steady_clock::time_point now) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (last_ && now - *last_ < gap_) {
        ++skipped_;
        return std::nullopt;
    }
    last_ = now;
    const std::size_t skipped = skipped_;
    skipped_ = 0;
    return skipped;
}

}  // namespace swbh::gui
