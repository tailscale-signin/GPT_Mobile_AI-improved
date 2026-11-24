#include "kokoro_context_cache.h"

#include <iostream>
#include <limits>
#include <sys/wait.h>

namespace {
using namespace kokoro_runtime;
namespace fs = std::filesystem;

void Check(bool condition, const char *message) {
  if (!condition) throw std::runtime_error(message);
}
template <typename Action>
bool Rejected(Action action) {
  try { action(); return false; } catch (const std::exception &) { return true; }
}
void Put(const fs::path &path, const std::string &value) {
  std::ofstream file(path, std::ios::binary); file << value;
  Check(file.good(), "Fixture write failed");
}
struct Directory {
  fs::path path;
  Directory() {
    std::string pattern = (fs::temp_directory_path() / "kokoro-context-test-XXXXXX").string();
    const auto result = mkdtemp(pattern.data());
    Check(result != nullptr, "Fixture directory failed"); path = result;
  }
  ~Directory() { fs::remove_all(path); }
};
void Store(const fs::path &root, const std::string &key, ContextCacheLimits limits = {}) {
  CompiledContextCache cache(root.string(), key, limits);
  Check(cache.Lookup().empty(), "Unexpected existing context");
  const auto staging = cache.BeginExport();
  Check(!staging.empty(), "Context export unavailable");
  Put(staging, std::string(300, 'm'));
  Put(fs::path(staging).parent_path() / "weights.bin", "cpu-initializer");
  Check(cache.Commit() && cache.status == ContextCacheStatus::Stored, "Atomic context commit failed");
}
void TestDigests() {
  Check(Digest("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "Empty SHA-256");
  Check(Digest("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 vector");
  Check(Digest(std::string(1000000, 'a')) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", "Chunked SHA-256 vector");
  Directory directory;
  Put(directory.path / "source", "abc");
  Check(DigestFile((directory.path / "source").string(), 3) == Digest("abc"), "File digest differs");
}
void TestPublicationAndIdentity() {
  Directory directory;
  const auto key = Digest("model1|runtime1|phone1|htp-balanced|shape93");
  {
    CompiledContextCache first(directory.path.string(), key);
    Check(first.Lookup().empty(), "New cache unexpectedly hit");
    const auto staging = first.BeginExport();
    Put(staging, "incomplete");
    Check(!fs::exists(directory.path / key), "Partial export became visible");
    CompiledContextCache competing(directory.path.string(), key);
    Check(competing.status == ContextCacheStatus::Busy && competing.Lookup().empty(), "Busy cache waited or shared a writer");
  }
  {
    CompiledContextCache abandoned(directory.path.string(), key);
    Check(abandoned.Lookup().empty(), "Abandoned export became a cache hit");
  }
  Store(directory.path, key);
  {
    CompiledContextCache loaded(directory.path.string(), key);
    Check(!loaded.Lookup().empty() && loaded.status == ContextCacheStatus::Hit && loaded.bytes > 300,
        "Same-process transaction did not load complete context + CPU sidecar");
  }
  const auto child = fork();
  Check(child >= 0, "Second process could not start");
  if (child == 0) {
    CompiledContextCache prior(directory.path.string(), key);
    _exit(!prior.Lookup().empty() && prior.status == ContextCacheStatus::HitPreviousProcess ? 0 : 1);
  }
  int child_status = 0;
  Check(waitpid(child, &child_status, 0) == child && WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0,
      "A different process did not identify the validated disk context");
  {
    const auto path = directory.path / key / "manifest";
    std::ifstream modern(path);
    std::string version, identity, private_origin;
    std::getline(modern, version); std::getline(modern, identity); std::getline(modern, private_origin);
    std::ostringstream legacy;
    legacy << "kokoro-ep-context-v1\n" << identity << '\n' << modern.rdbuf();
    Put(path, legacy.str());
    CompiledContextCache old(directory.path.string(), key);
    Check(!old.Lookup().empty() && old.status == ContextCacheStatus::Hit, "Legacy entry acquired a false process marker");
  }
  for (const auto *different : {"model2|runtime1|phone1|htp-balanced|shape93", "model1|runtime2|phone1|htp-balanced|shape93",
      "model1|runtime1|phone2|htp-balanced|shape93", "model1|runtime1|phone1|htp-burst|shape93",
      "model1|runtime1|phone1|htp-balanced|shape94"}) {
    CompiledContextCache changed(directory.path.string(), Digest(different));
    Check(changed.Lookup().empty(), "A mismatched runtime/device/options/shape reused a context");
  }
  Put(directory.path / key / "context.onnx", std::string(300, 'x'));
  {
    CompiledContextCache corrupt(directory.path.string(), key);
    Check(corrupt.Lookup().empty() && corrupt.status == ContextCacheStatus::Invalidated && !fs::exists(directory.path / key),
        "Same-size corrupt context survived digest validation");
  }
  Store(directory.path, key);
}
void TestConfinement() {
  Directory directory, outside;
  const auto key = Digest("protected");
  Put(outside.path / "important", "keep");
  {
    CompiledContextCache cache(directory.path.string(), key);
    const auto stage = cache.BeginExport();
    fs::create_symlink(outside.path / "important", stage);
    Check(!cache.Commit(), "Symlink context published");
  }
  Check(fs::exists(outside.path / "important"), "Cleanup followed a symlink outside cache");
  Store(directory.path, key);
  Put(directory.path / key / "manifest", "kokoro-ep-context-v1\n" + key + "\n4 " + Digest("keep") + " ../important\n");
  {
    CompiledContextCache cache(directory.path.string(), key);
    Check(cache.Lookup().empty(), "Traversal manifest loaded");
  }
  fs::create_directory_symlink(directory.path, outside.path / "alias");
  CompiledContextCache alias((outside.path / "alias").string(), key);
  Check(!alias.available(), "Noncanonical cache root admitted");
  fs::create_directories(directory.path / "pending-abandoned");
  fs::create_symlink(outside.path / "important", directory.path / "pending-abandoned" / "context.onnx");
  {
    CompiledContextCache recovered(directory.path.string(), key);
    Check(!fs::exists(directory.path / "pending-abandoned"), "Crash export was not retired");
  }
  Check(fs::exists(outside.path / "important"), "Crash cleanup escaped app cache");
}
void TestRetentionAndWriteFailure() {
  Directory directory;
  ContextCacheLimits limits{2, 1600, 768, 0};
  const auto a = Digest("a"), b = Digest("b"), c = Digest("c");
  Store(directory.path, a, limits);
  fs::last_write_time(directory.path / a, fs::file_time_type::clock::now() - std::chrono::hours(1));
  Store(directory.path, b, limits);
  Store(directory.path, c, limits);
  Check(!fs::exists(directory.path / a) && fs::exists(directory.path / b) && fs::exists(directory.path / c),
      "Retention did not retire least recently used context");
  {
    CompiledContextCache large(directory.path.string(), a, limits);
    Put(large.BeginExport(), std::string(769, 'x'));
    Check(!large.Commit() && large.status == ContextCacheStatus::TooLarge && !fs::exists(directory.path / a),
        "Oversized context was retained");
  }
  {
    CompiledContextCache missing(directory.path.string(), a, limits);
    Check(!missing.BeginExport().empty(), "Export start missing");
    Check(!missing.Commit() && missing.status == ContextCacheStatus::WriteFailed, "Incomplete provider export was accepted");
  }
  CompiledContextCache unavailable((directory.path / "missing").string(), a);
  Check(!unavailable.available() && unavailable.BeginExport().empty() && unavailable.status == ContextCacheStatus::DirectoryUnavailable, "Missing app cache was treated as prepared");
}
void TestPrivatePreparationSource() {
  Directory directory, outside;
  const auto key = Digest("prepared-source");
  {
    CompiledContextCache cache(directory.path.string(), key);
    Check(cache.BeginExport(std::numeric_limits<int64_t>::max()).empty() &&
        cache.status == ContextCacheStatus::InsufficientStorage, "Transient preparation bytes were not included in free-space admission");
  }
  {
    CompiledContextCache cache(directory.path.string(), key);
    const auto context = cache.BeginExport();
    const auto source_path = fs::path(context).parent_path() / ".source.onnx";
    {
      ContextSourceSnapshot source(context);
      Check(Rejected([&] { source.Validate(); }), "An empty source snapshot was accepted");
      Put(source.path(), "canonical-source");
      source.Validate();
      Check(truncate(source.path().c_str(), ContextSourceSnapshot::kMaximumBytes + 1) == 0, "Sparse size fixture failed");
      Check(Rejected([&] { source.Validate(); }), "Oversized BASIC source was accepted");
    }
    Check(!fs::exists(source_path), "Source snapshot was retained after unwinding");
    Put(outside.path / "keep", "unchanged");
    fs::create_symlink(outside.path / "keep", source_path);
    Check(Rejected([&] { ContextSourceSnapshot invalid(context); }), "Pre-existing source symlink was followed");
    Check(DigestFile((outside.path / "keep").string(), 9) == Digest("unchanged"), "Source admission mutated an outside file");
    fs::remove(source_path);
    ContextSourceSnapshot source(context);
    Put(source.path(), "canonical-source");
    Put(context, "compiled-context");
    Check(!cache.Commit() && cache.status == ContextCacheStatus::WriteFailed,
        "A temporary source was included in a published compiled-context entry");
  }
  {
    CompiledContextCache cache(directory.path.string(), key);
    const auto context = cache.BeginExport();
    ContextSourceSnapshot source(context);
    Put(source.path(), "canonical-source");
    source.Validate();
    source.Remove();
    Put(context, "compiled-context");
    Check(cache.Commit(), "Removing the source prevented valid context publication");
  }
  Check(!fs::exists(directory.path / key / ".source.onnx"), "Published cache retained its intermediate");
  Check(Rejected([&] { ContextSourceSnapshot invalid((outside.path / "context.onnx").string()); }),
      "Source snapshot escaped the private pending transaction");
}
}

int main() {
  TestDigests(); TestPublicationAndIdentity(); TestConfinement(); TestRetentionAndWriteFailure(); TestPrivatePreparationSource();
  std::cout << "Compiled-context atomic publication, identity, corruption, confinement, locking and LRU controls passed\n";
}
