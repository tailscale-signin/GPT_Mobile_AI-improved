#ifndef MELO_KOKORO_SHAPE_CACHE_H_
#define MELO_KOKORO_SHAPE_CACHE_H_

#include <algorithm>
#include <cstdint>
#include <memory>
#include <limits>
#include <vector>

namespace kokoro_runtime {

constexpr int64_t kShapeCacheSingleBudgetBytes = 640LL * 1024 * 1024;
constexpr int64_t kShapeCacheMaximumBudgetBytes = 2 * kShapeCacheSingleBudgetBytes;
constexpr int64_t kShapeCacheHeadroomBytes = 512LL * 1024 * 1024;

enum class ShapeCacheAdmission : int64_t { CpuSingle, MemoryUnknown, MemoryPressure, BoundedTwoShapes };
struct ShapeCacheAllowance {
  size_t limit;
  int64_t budget;
  ShapeCacheAdmission reason;
};

// MemAvailable excludes resident sessions. Admit only available incremental
// memory above the reserve, with an independent hard retention ceiling. Unknown
// memory never enables an idle accelerator session. These are residency
// estimates, not a native allocator limit or a promise that two shapes fit.
inline ShapeCacheAllowance AdmitShapeCache(bool accelerator, int64_t available,
    int64_t retained, int64_t prospective) {
  if (!accelerator) return {1, kShapeCacheSingleBudgetBytes, ShapeCacheAdmission::CpuSingle};
  if (available <= 0) return {1, kShapeCacheSingleBudgetBytes, ShapeCacheAdmission::MemoryUnknown};
  const auto spare = std::max<int64_t>(0, available - kShapeCacheHeadroomBytes);
  const auto bounded_retained = std::clamp<int64_t>(retained, 0, kShapeCacheMaximumBudgetBytes);
  const auto budget = bounded_retained + std::min(spare, kShapeCacheMaximumBudgetBytes - bounded_retained);
  const bool room = spare > 0 && spare >= std::max<int64_t>(0, prospective);
  return {room ? size_t{2} : size_t{1}, budget,
      room ? ShapeCacheAdmission::BoundedTwoShapes : ShapeCacheAdmission::MemoryPressure};
}

inline int64_t AddResidentGrowth(int64_t estimate, int64_t before, int64_t after) {
  const auto growth = before > 0 && after > before ? after - before : 0;
  return std::max<int64_t>(0, estimate) + std::min(growth,
      std::numeric_limits<int64_t>::max() - std::max<int64_t>(0, estimate));
}

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
    while (entries_.size() > limit_) EvictFirst();
  }
  void Configure(const ShapeCacheAllowance &allowance) {
    budget_ = std::max<int64_t>(0, allowance.budget);
    SetLimit(allowance.limit);
    while (entries_.size() > 1 && Bytes() > budget_) EvictFirst();
  }
  // Evict before creating the next model to avoid an unnecessary allocation
  // spike. A failed build cannot leave a partially initialized cache entry.
  void Reserve(int64_t bytes) {
    while (!entries_.empty() &&
        (entries_.size() >= limit_ || bytes > budget_ || Bytes() > budget_ - bytes))
      EvictFirst();
  }
  T *Insert(int64_t shape, int64_t bytes, std::unique_ptr<T> value) {
    Reserve(bytes);
    entries_.push_back({shape, bytes, std::move(value)});
    return entries_.back().value.get();
  }
  void UpdateActiveBytes(int64_t bytes) {
    if (entries_.empty()) return;
    entries_.back().bytes = std::max(entries_.back().bytes, bytes);
    while (entries_.size() > 1 && Bytes() > budget_) EvictFirst();
  }
  void Erase(int64_t shape) {
    for (size_t i = 0; i < entries_.size(); ++i) if (entries_[i].shape == shape) {
      entries_.erase(entries_.begin() + i); ++evictions_; return;
    }
  }
  int64_t Bytes() const { int64_t sum = 0; for (const auto &e : entries_) sum += e.bytes; return sum; }
  int64_t ActiveBytes() const { return entries_.empty() ? 0 : entries_.back().bytes; }
  int64_t Budget() const { return budget_; }
  size_t Size() const { return entries_.size(); }
  size_t Limit() const { return limit_; }
  int64_t Evictions() const { return evictions_; }
 private:
  void EvictFirst() { entries_.erase(entries_.begin()); ++evictions_; }
  size_t limit_;
  int64_t budget_;
  int64_t evictions_ = 0;
  std::vector<Entry> entries_;
};

}  // namespace kokoro_runtime
#endif
