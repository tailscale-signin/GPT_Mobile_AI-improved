#pragma once
#include <algorithm>

// Bound cancellation latency using measured batch time, within the configured context capacity.
class GgufPrefillGovernor {
public:
    GgufPrefillGovernor(int capacity, bool cpu) : capacity_(std::max(1, capacity)), cpu_(cpu), batch_(cpu ? std::min(8, capacity_) : capacity_) {}
    int selected(int remaining) const { return std::min(batch_, remaining); }
    void observed(long elapsedMs) {
        if (!cpu_) return;
        if (elapsedMs < 1800) batch_ = std::min(capacity_, batch_ * 2);
        else if (elapsedMs > 4000) batch_ = std::max(1, batch_ / 2);
    }
private:
    int capacity_;
    bool cpu_;
    int batch_;
};
