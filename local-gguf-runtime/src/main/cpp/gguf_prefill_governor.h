#pragma once
#include <algorithm>

// Learn tokens/ms from completed batches. Aim for a 2.5s cancellation window,
// grow at most 2x per observation, and shrink immediately after a slow batch.
class GgufPrefillGovernor {
public:
    GgufPrefillGovernor(int capacity, bool cpu) : capacity_(std::max(1, capacity)), cpu_(cpu), batch_(cpu ? std::min(8, capacity_) : capacity_) {}
    int selected(int remaining) const { return std::min(batch_, remaining); }
    void observed(long elapsedMs, int processed = 0) {
        if (!cpu_) return;
        const int actual = processed > 0 ? processed : batch_;
        const long target = static_cast<long>(actual) * 2500 / std::max(1L, elapsedMs);
        const int next = static_cast<int>(std::min<long>(capacity_, std::max(1L, target)));
        if (elapsedMs > 4000) batch_ = std::max(1, std::min(batch_ / 2, next));
        else batch_ = std::min(std::max(1, batch_ * 2), next);
    }
private:
    int capacity_;
    bool cpu_;
    int batch_;
};
