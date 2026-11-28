#ifndef GPTMOBILE_STREAMING_ASR_RETIREMENT_H_
#define GPTMOBILE_STREAMING_ASR_RETIREMENT_H_

#include <functional>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>
#include <vector>

namespace asr_runtime {

[[noreturn]] inline void RetirementUnconfirmed() {
  throw std::runtime_error("ASR_RETIREMENT_UNCONFIRMED");
}

// A failed free may already have consumed its handle. Never retry it or free
// its parents. The owning Session remains in the process registry on failure.
class CheckedRetirement {
 public:
  [[noreturn]] void RejectUnconfirmed() {
    state_ = State::kUnconfirmed;
    RetirementUnconfirmed();
  }

  template <typename Cleanup>
  void Run(Cleanup &&cleanup) {
    if (state_ == State::kRetired) return;
    if (state_ == State::kUnconfirmed) RetirementUnconfirmed();
    state_ = State::kUnconfirmed;
    try {
      std::forward<Cleanup>(cleanup)();
      state_ = State::kRetired;
    } catch (...) {
      RetirementUnconfirmed();
    }
  }

  void RequireActive() const {
    if (state_ == State::kUnconfirmed) RetirementUnconfirmed();
    if (state_ == State::kRetired) throw std::runtime_error("ASR_CLOSED");
  }

 private:
  enum class State { kActive, kUnconfirmed, kRetired };
  State state_ = State::kActive;
};

// Register a fully constructed owner BEFORE it acquires native resources.
// Constructor unwinding can then destroy a Sherpa wrapper without unloading its
// live native state. No owner is released until every checked free succeeds.
class RetainedNativeOwners {
 public:
  template <typename Owner>
  void Retain(const std::shared_ptr<Owner> &owner) {
    retirement_.RequireActive();
    owners_.emplace_back([owner] { owner->Retire(); });
  }

  void Retire() {
    retirement_.Run([this] {
      for (auto owner = owners_.rbegin(); owner != owners_.rend(); ++owner) {
        (*owner)();
      }
      owners_.clear();
    });
  }

 private:
  CheckedRetirement retirement_;
  std::vector<std::function<void()>> owners_;
};

// The production instance is process-owned and is deliberately not destroyed
// at static shutdown: uncertain native owners must survive until process exit.
// Multiple healthy sessions remain independent; uncertainty seals NEW admission.
template <typename Session>
class NativeSessionRegistry {
 public:
  std::pair<int64_t, std::shared_ptr<Session>> Create() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sealed_) throw std::runtime_error("ASR_NATIVE_ADMISSION_BLOCKED");
    auto session = std::make_shared<Session>();
    const auto handle = next_handle_++;
    sessions_.emplace(handle, session);
    return {handle, std::move(session)};
  }

  std::shared_ptr<Session> Lookup(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = sessions_.find(handle);
    if (found == sessions_.end()) throw std::runtime_error("ASR_CLOSED");
    return found->second;
  }

  void Retire(int64_t handle) {
    auto session = Lookup(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    try {
      session->Retire();
    } catch (...) {
      std::lock_guard<std::mutex> registry_lock(mutex_);
      sealed_ = true;
      RetirementUnconfirmed();
    }
    std::lock_guard<std::mutex> registry_lock(mutex_);
    sessions_.erase(handle);
  }

 private:
  std::mutex mutex_;
  std::unordered_map<int64_t, std::shared_ptr<Session>> sessions_;
  int64_t next_handle_ = 1;
  bool sealed_ = false;
};

}  // namespace asr_runtime
#endif
