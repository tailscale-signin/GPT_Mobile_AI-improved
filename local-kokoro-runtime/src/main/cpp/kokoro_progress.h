#ifndef MELO_KOKORO_PROGRESS_H_
#define MELO_KOKORO_PROGRESS_H_

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>

namespace kokoro_runtime {

enum class SpeechPhase : int64_t { Frontend, Preparing, Synthesis, Validating, Ready };

inline int64_t SteadyMillis() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct ElapsedMilliseconds {
  explicit ElapsedMilliseconds(int64_t &result) : result(result), started(SteadyMillis()) {}
  ~ElapsedMilliseconds() { result = SteadyMillis() - started; }
  int64_t &result;
  int64_t started;
};

struct SpeechProgressSnapshot {
  int64_t attempt = 0, phase = 0, sequence = 0, phase_elapsed_ms = 0, attempt_elapsed_ms = 0;
  bool active = false;
};

// One synthesis writer; concurrent watchdog readers never touch session/cache
// state or wait for graph compilation. Every payload field is atomic, including
// during an unsuccessful snapshot. Bounded retries avoid blocking the UI.
class SpeechProgress {
 public:
  void Begin(int64_t now) {
    Write([&] {
      attempt_.fetch_add(1, std::memory_order_relaxed);
      started_.store(now, std::memory_order_relaxed);
      phase_started_.store(now, std::memory_order_relaxed);
      phase_.store(static_cast<int64_t>(SpeechPhase::Frontend), std::memory_order_relaxed);
      active_.store(true, std::memory_order_relaxed);
      finished_.store(0, std::memory_order_relaxed);
    });
  }
  void Transition(SpeechPhase phase, int64_t now) {
    Write([&] {
      phase_.store(static_cast<int64_t>(phase), std::memory_order_relaxed);
      phase_started_.store(now, std::memory_order_relaxed);
    });
  }
  void Finish(int64_t now) {
    Write([&] {
      finished_.store(now, std::memory_order_relaxed);
      active_.store(false, std::memory_order_relaxed);
    });
  }

  bool Snapshot(int64_t now, SpeechProgressSnapshot &out) const {
    for (int retry = 0; retry < 8; ++retry) {
      const auto before = sequence_.load(std::memory_order_acquire);
      if (before & 1) continue;
      SpeechProgressSnapshot next;
      next.attempt = attempt_.load(std::memory_order_relaxed);
      next.phase = phase_.load(std::memory_order_relaxed);
      const auto phase_started = phase_started_.load(std::memory_order_relaxed);
      const auto started = started_.load(std::memory_order_relaxed);
      next.active = active_.load(std::memory_order_relaxed);
      const auto finished = finished_.load(std::memory_order_relaxed);
      std::atomic_thread_fence(std::memory_order_acquire);
      const auto after = sequence_.load(std::memory_order_acquire);
      if (before != after) continue;
      next.sequence = static_cast<int64_t>(after / 2);
      const auto observed = next.active ? now : finished;
      next.phase_elapsed_ms = next.attempt ? std::max<int64_t>(0, observed - phase_started) : 0;
      next.attempt_elapsed_ms = next.attempt ? std::max<int64_t>(0, observed - started) : 0;
      out = next;
      return true;
    }
    return false;
  }

 private:
  template <typename F> void Write(F update) {
    sequence_.fetch_add(1, std::memory_order_acq_rel);
    // If a reader observes any newer payload, this release fence pairs with
    // its acquire fence through that payload atomic ([atomics.fences]/2).
    // The odd publication then happens-before its final sequence read.
    std::atomic_thread_fence(std::memory_order_release);
    update();
    sequence_.fetch_add(1, std::memory_order_release);
  }
  std::atomic<uint64_t> sequence_{0};
  std::atomic<int64_t> attempt_{0}, started_{0}, phase_started_{0}, phase_{0}, finished_{0};
  std::atomic<bool> active_{false};
};

}  // namespace kokoro_runtime
#endif
