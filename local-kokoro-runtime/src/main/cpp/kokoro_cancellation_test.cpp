#include "kokoro_cancellation.h"
#include "kokoro_context_cache.h"
#include "kokoro_progress.h"

#include <future>
#include <iostream>
#include <memory>

namespace {
using namespace kokoro_runtime;
using namespace std::chrono_literals;
namespace fs = std::filesystem;

void Check(bool value, const char *message) {
  if (!value) throw std::runtime_error(message);
}
struct Directory {
  fs::path path;
  Directory() {
    std::string pattern = (fs::temp_directory_path() / "kokoro-cancel-test-XXXXXX").string();
    const auto created = mkdtemp(pattern.data());
    Check(created != nullptr, "Fixture directory failed"); path = created;
  }
  ~Directory() { fs::remove_all(path); }
};
void Put(const fs::path &path) {
  std::ofstream file(path, std::ios::binary); file << "test-context-artifact";
  Check(file.good(), "Fixture write failed");
}

// Uses the production completion/cancel/reset guards, progress publication and
// real disk cache. It does not run JNI, ORT, a speech model or an accelerator.
void CancelDuringFinalization(bool after_commit) {
  Directory directory;
  const auto key = Digest(after_commit ? "after-commit" : "before-commit");
  auto cache = std::make_unique<CompiledContextCache>(directory.path.string(), key);
  Put(cache->BeginExport());
  std::mutex lock;
  std::atomic<bool> cancelled{false};
  bool terminated = false, completed = false, audio_returned = false, cancellation_thrown = false;
  SpeechProgress progress;
  progress.Begin(1); progress.Transition(SpeechPhase::Validating, 2);
  std::promise<void> entered, release;
  auto entered_future = entered.get_future();
  auto release_future = release.get_future();
  auto generation = std::async(std::launch::async, [&] {
    try {
      FinalizeSpeechGeneration(lock, cancelled, [&] {
        const auto barrier = [&] { entered.set_value(); release_future.wait(); };
        if (!after_commit) barrier();
        Check(cache->Commit(), "Qualified context did not commit");
        if (after_commit) barrier();
        cache.reset();
      }, [&] {
        progress.Transition(SpeechPhase::Ready, 3);
        completed = true;
      });
      audio_returned = true;
    } catch (const std::runtime_error &error) {
      cancellation_thrown = std::string(error.what()) == "Kokoro generation cancelled";
    }
    progress.Finish(4);
  });
  Check(entered_future.wait_for(2s) == std::future_status::ready, "Finalization barrier not reached");
  auto cancel = std::async(std::launch::async, [&] {
    CancelSpeechGeneration(lock, cancelled, [&] { terminated = true; });
  });
  const bool cancel_finished = cancel.wait_for(1s) == std::future_status::ready;
  release.set_value();
  generation.get(); cancel.get();
  Check(cancel_finished, "Disk finalization blocked cancellation");
  Check(terminated && cancellation_thrown && !completed && !audio_returned,
      "Cancellation during finalization published successful audio");
  SpeechProgressSnapshot snapshot;
  Check(progress.Snapshot(5, snapshot) && !snapshot.active &&
      snapshot.phase == static_cast<int64_t>(SpeechPhase::Validating), "Cancelled attempt published Ready");
  Check(!cache, "Context finalization retained its writer lock");
  {
    CompiledContextCache retained(directory.path.string(), key);
    Check(!retained.Lookup().empty() && retained.status == ContextCacheStatus::Hit,
        "A fully committed context was left partial or locked after cancellation");
  }
  ResetSpeechGeneration(lock, cancelled, [&] { terminated = false; });
  progress.Begin(6); progress.Transition(SpeechPhase::Validating, 7);
  FinalizeSpeechGeneration(lock, cancelled, [] {}, [&] { completed = true; progress.Transition(SpeechPhase::Ready, 8); });
  progress.Finish(9);
  Check(!cancelled && !terminated && completed && progress.Snapshot(10, snapshot) &&
      snapshot.phase == static_cast<int64_t>(SpeechPhase::Ready), "Reset did not permit the next serial attempt");
}

void CompletionLinearizesBeforeLaterCancellation() {
  std::mutex lock;
  std::atomic<bool> cancelled{false};
  std::promise<void> publishing, release, cancel_started;
  auto publishing_future = publishing.get_future();
  auto release_future = release.get_future();
  auto cancel_started_future = cancel_started.get_future();
  bool completed = false;
  auto generation = std::async(std::launch::async, [&] {
    FinalizeSpeechGeneration(lock, cancelled, [] {}, [&] {
      publishing.set_value(); release_future.wait(); completed = true;
    });
  });
  Check(publishing_future.wait_for(2s) == std::future_status::ready, "Publication barrier not reached");
  auto cancel = std::async(std::launch::async, [&] {
    cancel_started.set_value();
    CancelSpeechGeneration(lock, cancelled, [] {});
  });
  Check(cancel_started_future.wait_for(2s) == std::future_status::ready, "Cancel thread did not start");
  const bool waited = cancel.wait_for(20ms) == std::future_status::timeout;
  release.set_value(); generation.get(); cancel.get();
  Check(waited && completed && cancelled, "Cancellation crossed the atomic completion transition");
}

void FailedFinalizationAndResetCannotPublishSuccess() {
  std::mutex lock;
  std::atomic<bool> cancelled{false};
  bool published = false, failed = false;
  try {
    FinalizeSpeechGeneration(lock, cancelled, [] { throw std::runtime_error("finalization failed"); }, [&] { published = true; });
  } catch (const std::runtime_error &) { failed = true; }
  Check(failed && !published, "Failed finalization published success");
  CancelSpeechGeneration(lock, cancelled, [] {});
  failed = false;
  try {
    ResetSpeechGeneration(lock, cancelled, [] { throw std::runtime_error("reset failed"); });
  } catch (const std::runtime_error &) { failed = true; }
  Check(failed && cancelled, "Failed native reset cleared cancellation");
  ResetSpeechGeneration(lock, cancelled, [] {});
  FinalizeSpeechGeneration(lock, cancelled, [] {}, [&] { published = true; });
  Check(published, "Successful reset did not recover publication");
}
}  // namespace

int main() {
  CancelDuringFinalization(false);
  CancelDuringFinalization(true);
  CompletionLinearizesBeforeLaterCancellation();
  FailedFinalizationAndResetCannotPublishSuccess();
  std::cout << "Native finalization cancellation, context retirement, publication ordering and reset controls passed\n";
}
