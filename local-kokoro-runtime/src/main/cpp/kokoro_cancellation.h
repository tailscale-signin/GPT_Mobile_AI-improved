#ifndef MELO_KOKORO_CANCELLATION_H_
#define MELO_KOKORO_CANCELLATION_H_

#include <atomic>
#include <mutex>
#include <stdexcept>
#include <utility>

namespace kokoro_runtime {

template <typename Terminate>
void CancelSpeechGeneration(std::mutex &lock, std::atomic<bool> &cancelled, Terminate &&terminate) {
  std::lock_guard<std::mutex> guard(lock);
  cancelled.store(true);
  std::forward<Terminate>(terminate)();
}

// The serial synthesis owner resets only after the preceding attempt retires.
template <typename Reset>
void ResetSpeechGeneration(std::mutex &lock, std::atomic<bool> &cancelled, Reset &&reset) {
  std::lock_guard<std::mutex> guard(lock);
  std::forward<Reset>(reset)();
  cancelled.store(false);
}

template <typename Finalize, typename Publish>
void FinalizeSpeechGeneration(std::mutex &lock, const std::atomic<bool> &cancelled,
    Finalize &&finalize, Publish &&publish) {
  // File publication, hashing, fsync and context destruction must never hold
  // the cancellation lock. Cancellation can finish while those operations run.
  std::forward<Finalize>(finalize)();
  std::lock_guard<std::mutex> guard(lock);
  if (cancelled.load()) throw std::runtime_error("Kokoro generation cancelled");
  // This short state transition linearizes success against Cancel/Reset.
  // A cancellation that won the lock cannot expose Audio or a Ready receipt.
  std::forward<Publish>(publish)();
}

}  // namespace kokoro_runtime
#endif
