#ifndef MELO_KOKORO_SHAPE_CACHE_H_
#define MELO_KOKORO_SHAPE_CACHE_H_

#include <algorithm>
#include <cstdint>
#include <memory>
#include <vector>

namespace kokoro_runtime {

// One worker owns this cache. We keep exact shapes, never padded tokens. The
// additional idle session has both a count limit and a conservative residency
// estimate budget. The active model may itself exceed that budget; in that
// case it is the only retained entry. This is not a native allocator limit.
template <typename T> class ShapeCache {
 public:
  struct Entry { int64_t shape, bytes; std::unique_ptr<T> value; };
  ShapeCache(size_t limit, int64_t budget) : limit_(std::max<size_t>(1, limit)), budget_(budget) {}
  T *Find(int64_t shape) {
    for (size_t i = 0; i < entries_.size(); ++i) {
      if (entries_[i].shape != shape) continue;
      auto entry = std::move(entries_[i]);
      entries_.erase(entries_.begin() + i);
      entries_.push_back(std::move(entry));
      return entries_.back().value.get();
    }
    return nullptr;
  }
  void SetLimit(size_t limit) {
    limit_ = std::max<size_t>(1, limit);
    while (entries_.size() > limit_) entries_.erase(entries_.begin());
  }
  // Evict before creating the next model to avoid an unnecessary allocation
  // spike. A failed build cannot leave a partially initialized cache entry.
  void Reserve(int64_t bytes) {
    while (!entries_.empty() &&
        (entries_.size() >= limit_ || bytes > budget_ || Bytes() > budget_ - bytes))
      entries_.erase(entries_.begin());
  }
  T *Insert(int64_t shape, int64_t bytes, std::unique_ptr<T> value) {
    Reserve(bytes);
    entries_.push_back({shape, bytes, std::move(value)});
    return entries_.back().value.get();
  }
  void UpdateActiveBytes(int64_t bytes) {
    if (entries_.empty()) return;
    entries_.back().bytes = std::max(entries_.back().bytes, bytes);
    while (entries_.size() > 1 && Bytes() > budget_) entries_.erase(entries_.begin());
  }
  void Erase(int64_t shape) {
    for (size_t i = 0; i < entries_.size(); ++i) if (entries_[i].shape == shape) {
      entries_.erase(entries_.begin() + i); return;
    }
  }
  int64_t Bytes() const { int64_t sum = 0; for (const auto &e : entries_) sum += e.bytes; return sum; }
  size_t Size() const { return entries_.size(); }
  size_t Limit() const { return limit_; }
 private:
  size_t limit_;
  int64_t budget_;
  std::vector<Entry> entries_;
};

}  // namespace kokoro_runtime
#endif
