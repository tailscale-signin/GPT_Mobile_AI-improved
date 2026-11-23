#include "kokoro_progress.h"

#include <atomic>
#include <stdexcept>
#include <thread>

namespace {
void Require(bool pass) { if (!pass) throw std::runtime_error("Speech progress regression"); }
}

int main() {
  using kokoro_runtime::SpeechPhase;
  kokoro_runtime::SpeechProgress progress;
  kokoro_runtime::SpeechProgressSnapshot current;
  Require(progress.Snapshot(100, current) && current.attempt == 0 && !current.active);
  progress.Begin(100);
  progress.Transition(SpeechPhase::Preparing, 110);
  Require(progress.Snapshot(130, current) && current.active && current.attempt == 1 &&
      current.phase == static_cast<int64_t>(SpeechPhase::Preparing) &&
      current.phase_elapsed_ms == 20 && current.attempt_elapsed_ms == 30);
  const auto preparing_sequence = current.sequence;
  Require(progress.Snapshot(140, current) && current.sequence == preparing_sequence && current.phase_elapsed_ms == 30);
  progress.Transition(SpeechPhase::Synthesis, 150);
  Require(progress.Snapshot(160, current) && current.sequence > preparing_sequence &&
      current.phase_elapsed_ms == 10 && current.attempt_elapsed_ms == 60);
  progress.Finish(170);
  Require(progress.Snapshot(990, current) && !current.active && current.phase_elapsed_ms == 20 && current.attempt_elapsed_ms == 70);
  progress.Begin(1000);
  Require(progress.Snapshot(1010, current) && current.active && current.attempt == 2 && current.attempt_elapsed_ms == 10);

  std::atomic<bool> done{false}, observed{false};
  std::thread writer([&] {
    for (int64_t i = 1; i <= 50000; ++i) {
      progress.Begin(2000 + i * 10);
      progress.Transition(SpeechPhase::Preparing, 2001 + i * 10);
      if (i == 1) while (!observed.load(std::memory_order_acquire)) std::this_thread::yield();
      progress.Transition(SpeechPhase::Synthesis, 2002 + i * 10);
      progress.Transition(SpeechPhase::Ready, 2003 + i * 10);
      progress.Finish(2004 + i * 10);
    }
    done.store(true, std::memory_order_release);
  });
  int64_t last_attempt = 0, last_sequence = 0;
  size_t observations = 0;
  while (!done.load(std::memory_order_acquire)) {
    if (!progress.Snapshot(1000000, current)) continue;
    Require(current.attempt >= last_attempt && current.sequence >= last_sequence &&
        current.phase >= 0 && current.phase <= 4 &&
        current.phase_elapsed_ms >= 0 && current.phase_elapsed_ms <= current.attempt_elapsed_ms);
    if (current.attempt >= 3) {
      // Link every observed field to the same attempt, rather than accepting
      // independently plausible times from different concurrent updates.
      const auto started = 2000 + (current.attempt - 2) * 10;
      const auto offset = current.phase == static_cast<int64_t>(SpeechPhase::Ready) ? 3 : current.phase;
      Require(current.phase != static_cast<int64_t>(SpeechPhase::Validating));
      Require(current.attempt_elapsed_ms == (current.active ? 1000000 - started : 4));
      Require(current.phase_elapsed_ms == current.attempt_elapsed_ms - offset);
      Require(current.active || current.phase == static_cast<int64_t>(SpeechPhase::Ready));
    }
    last_attempt = current.attempt;
    last_sequence = current.sequence;
    ++observations;
    observed.store(true, std::memory_order_release);
  }
  writer.join();
  Require(observations > 0 && progress.Snapshot(1000000, current) && !current.active && current.attempt == 50002);
  return 0;
}
