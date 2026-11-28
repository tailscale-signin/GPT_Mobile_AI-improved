#include "streaming_asr_qnn_fixture.h"
#include "streaming_asr_provider.h"
#include "sherpa-onnx/csrc/qnn/qnn-backend.h"
#include "sherpa-onnx/csrc/qnn/qnn-model.h"
#include <dlfcn.h>
#include <sys/wait.h>
#include <unistd.h>
#include <algorithm>
#include <fstream>
#include <iostream>
#include <sstream>

namespace {
void Check(bool condition, const char *message) {
  if (!condition) throw std::runtime_error(message);
}
template <typename Action>
void Throws(Action action, const char *code) {
  try { action(); } catch (const std::exception &error) {
    Check(error.what() == std::string(code), "Unexpected failure code"); return;
  }
  throw std::runtime_error("Expected failure was not raised");
}
std::vector<int> Events(const AsrQnnFixture &fixture, bool frees = false) {
  std::vector<int> values;
  for (int i = 0; i < fixture.event_count; ++i)
    if (!frees || fixture.events[i] >= kFreeContext) values.push_back(fixture.events[i]);
  return values;
}
struct Session {
  asr_runtime::Context context;
  std::mutex mutex;
  asr_runtime::CheckedRetirement retirement;
  std::unique_ptr<sherpa_onnx::QnnBackend> backend;
  std::unique_ptr<sherpa_onnx::QnnModel> model;
  void Retire() {
    retirement.Run([this] { context.native_owners.Retire(); model.reset(); backend.reset(); });
  }
};
using Registry = asr_runtime::NativeSessionRegistry<Session>;
struct Fixture {
  AsrQnnFixture state;
  std::string library;
  void *observer;
  // Match production quarantine: do not destroy uncertain states at exit.
  Registry *registry = new Registry;
  explicit Fixture(const char *path) : library(path), observer(dlopen(path, RTLD_NOW | RTLD_LOCAL)) {
    Check(observer != nullptr, "Cannot load fixture API");
    auto configure = reinterpret_cast<ConfigureAsrQnnFixture>(dlsym(observer, "ConfigureFixture"));
    Check(configure != nullptr, "Cannot resolve fixture configuration");
    configure(&state);
  }
  void ReleaseObserver() { if (observer) { dlclose(observer); observer = nullptr; } }
  void Init(const std::shared_ptr<Session> &session, bool context = true) {
    asr_runtime::Scope scope(session->context);
    sherpa_onnx::QnnBackendOptions options;
    options.htp_perf_mode = true;
    session->backend = std::make_unique<sherpa_onnx::QnnBackend>(library, options);
    if (context) session->backend->InitContext();
  }
  void Binary(const std::shared_ptr<Session> &session, const std::string &path) {
    asr_runtime::Scope scope(session->context);
    session->model = std::make_unique<sherpa_onnx::QnnModel>(path, library,
        session->backend.get(), sherpa_onnx::BinaryContextTag{}, false);
  }
};
struct BinaryFile {
  std::string path;
  BinaryFile() {
    char pattern[] = "/tmp/asr-retirement-test-XXXXXX";
    const int file = mkstemp(pattern);
    Check(file >= 0, "Cannot create fixture binary");
    Check(write(file, "fixture", 7) == 7, "Cannot write fixture binary");
    close(file); path = pattern;
  }
  ~BinaryFile() { unlink(path.c_str()); }
  bool Mapped() const {
    std::ifstream maps("/proc/self/maps");
    std::stringstream contents; contents << maps.rdbuf();
    return contents.str().find(path) != std::string::npos;
  }
};
void FailedFree(const char *library, int failed) {
  Fixture fixture(library);
  auto [handle, session] = fixture.registry->Create();
  fixture.Init(session);
  fixture.ReleaseObserver();
  fixture.state.fail_first = failed;
  Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
  std::vector<int> expected;
  for (int stage : {kFreeContext, kFreePower, kFreeDevice, kFreeBackend, kFreeLog}) {
    expected.push_back(stage);
    if (stage == failed) break;
  }
  Check(Events(fixture.state, true) == expected, "Parents freed after failed child retirement");
  Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
  Check(Events(fixture.state, true) == expected, "Uncertain native free retried");
  std::weak_ptr<Session> retained = session;
  session.reset();
  Check(!retained.expired() && fixture.state.unloads == 0, "Uncertain session or library released");
  Throws([&] { fixture.registry->Create(); }, "ASR_NATIVE_ADMISSION_BLOCKED");
}
void HealthyOwners(const char *library) {
  Fixture fixture(library);
  auto [first_handle, first] = fixture.registry->Create();
  auto [second_handle, second] = fixture.registry->Create();
  fixture.Init(first); fixture.Init(second);
  fixture.ReleaseObserver();
  std::weak_ptr<Session> weak_first = first, weak_second = second;
  fixture.registry->Retire(first_handle); first.reset();
  Check(weak_first.expired() && !weak_second.expired(), "Healthy owners not independently released");
  Check(fixture.state.unloads == 0, "Shared library unloaded while another owner is live");
  fixture.registry->Retire(second_handle); second.reset();
  Check(weak_second.expired() && fixture.state.unloads == 1, "Successful close did not release ownership");
}
void PartialBackend(const char *library, bool rollback_fails) {
  Fixture fixture(library);
  auto [handle, session] = fixture.registry->Create();
  fixture.state.fail_first = kCreateDevice;
  fixture.state.fail_second = rollback_fails ? kFreeBackend : 0;
  Throws([&] { fixture.Init(session); }, "ASR_NATIVE_FAILURE");
  fixture.ReleaseObserver();
  Check(!session->backend && fixture.state.unloads == 0, "Failed constructor lost its native owner");
  if (rollback_fails) {
    Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
    Check(Events(fixture.state, true) == std::vector<int>({kFreeBackend}), "Failed rollback freed a parent");
    Check(fixture.state.unloads == 0, "Failed rollback unloaded backend library");
  } else {
    fixture.registry->Retire(handle);
    Check(Events(fixture.state, true) == std::vector<int>({kFreeBackend, kFreeLog}), "Partial constructor rollback skipped an acquired handle");
    Check(fixture.state.unloads == 1, "Successful rollback did not unload the library");
  }
}
void FailedMetadata(const char *library) {
  Fixture fixture(library); BinaryFile binary;
  auto [handle, session] = fixture.registry->Create();
  fixture.Init(session, false);
  fixture.state.fail_first = kReadMetadata;
  fixture.state.fail_second = kFreeSystem;
  Throws([&] { fixture.Binary(session, binary.path); }, "ASR_RETIREMENT_UNCONFIRMED");
  fixture.ReleaseObserver();
  Check(!session->model && binary.Mapped(), "Failed model constructor lost its binary mapping");
  const auto before = Events(fixture.state);
  Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
  Check(Events(fixture.state) == before && fixture.state.unloads == 0 && binary.Mapped(),
      "Uncertain metadata was retried or released with its parents");
}
void HealthyBinaryModel(const char *library) {
  Fixture fixture(library); BinaryFile binary;
  auto [handle, session] = fixture.registry->Create();
  fixture.Init(session, false);
  fixture.Binary(session, binary.path);
  fixture.ReleaseObserver();
  Check(session->model->IsInitialized() && binary.Mapped(), "Successful binary model did not retain its owner");
  fixture.registry->Retire(handle);
  Check(Events(fixture.state, true) == std::vector<int>({kFreeSystem, kFreeContext, kFreePower, kFreeDevice, kFreeBackend, kFreeLog}),
      "Successful model retirement skipped or reordered native dependencies");
  Check(!binary.Mapped() && fixture.state.unloads == 1, "Successful model retirement did not release its mapping/library");
}
void FailedContextConstruction(const char *library, bool after_context) {
  Fixture fixture(library); BinaryFile binary;
  auto [handle, session] = fixture.registry->Create();
  fixture.Init(session, false);
  fixture.state.fail_first = after_context ? kRetrieveGraph : kCreateContext;
  fixture.state.fail_second = after_context ? kFreeContext : 0;
  Throws([&] { fixture.Binary(session, binary.path); }, "ASR_CONTEXT_LOAD");
  fixture.ReleaseObserver();
  Check((session->backend->ContextHandle() != nullptr) == after_context && binary.Mapped(), "Context ownership or mapped bytes lost");
  if (after_context) {
    Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
    Check(Events(fixture.state, true) == std::vector<int>({kFreeSystem, kFreeContext}), "Uncertain context lost parents");
    Check(binary.Mapped() && fixture.state.unloads == 0, "Uncertain constructor rollback lost retained bytes");
  } else {
    fixture.registry->Retire(handle);
    Check(!binary.Mapped() && fixture.state.unloads == 1, "Confirmed construction rollback retained dead resources");
  }
}
void AmbiguousCreation(const char *library, int stage) {
  Fixture fixture(library); BinaryFile binary;
  auto [handle, session] = fixture.registry->Create();
  fixture.state.fail_first = stage;
  fixture.state.error_output = true;
  if (stage == kCreateSystem || stage == kCreateContext) {
    fixture.Init(session, false);
    Throws([&] { fixture.Binary(session, binary.path); }, "ASR_RETIREMENT_UNCONFIRMED");
    Check(binary.Mapped(), "Uncertain create lost its binary owner");
  } else {
    Throws([&] { fixture.Init(session); }, "ASR_RETIREMENT_UNCONFIRMED");
  }
  fixture.ReleaseObserver();
  const auto before = Events(fixture.state);
  Throws([&] { fixture.registry->Retire(handle); }, "ASR_RETIREMENT_UNCONFIRMED");
  Check(Events(fixture.state) == before, "An ambiguous error output was passed to free");
  Check(fixture.state.unloads == 0, "Ambiguous create unloaded its library");
  Throws([&] { fixture.registry->Create(); }, "ASR_NATIVE_ADMISSION_BLOCKED");
}
void ZeroPowerId(const char *library) {
  Fixture fixture(library);
  auto [handle, session] = fixture.registry->Create();
  fixture.state.power_id = 0;
  Throws([&] { fixture.Init(session); }, "ASR_NATIVE_FAILURE");
  const auto events = Events(fixture.state);
  Check(std::find(events.begin(), events.end(), kSetPower) == events.end(), "Global power settings were changed");
  fixture.ReleaseObserver();
  fixture.registry->Retire(handle);
  Check(Events(fixture.state, true) == std::vector<int>({kFreeDevice, kFreeBackend, kFreeLog}),
      "The global/NULL power id was freed");
  Check(fixture.state.unloads == 1, "Zero-id rejection failed safe rollback");
}
void ExistingOwnerAfterSeal(const char *library) {
  Fixture fixture(library);
  auto [failed_handle, failed] = fixture.registry->Create();
  auto [healthy_handle, healthy] = fixture.registry->Create();
  fixture.Init(failed); fixture.Init(healthy);
  fixture.state.fail_first = kFreeContext;
  Throws([&] { fixture.registry->Retire(failed_handle); }, "ASR_RETIREMENT_UNCONFIRMED");
  fixture.state.fail_first = 0;
  fixture.registry->Retire(healthy_handle);
  std::weak_ptr<Session> released = healthy; healthy.reset();
  Check(released.expired(), "Sealed admission prevented another healthy owner from retiring");
  Throws([&] { fixture.registry->Create(); }, "ASR_NATIVE_ADMISSION_BLOCKED");
}
}  // namespace

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::vector<std::function<void()>> cases;
  for (int stage : {kFreeContext, kFreePower, kFreeDevice, kFreeBackend, kFreeLog})
    cases.emplace_back([&, stage] { FailedFree(argv[1], stage); });
  cases.emplace_back([&] { HealthyOwners(argv[1]); });
  cases.emplace_back([&] { PartialBackend(argv[1], false); });
  cases.emplace_back([&] { PartialBackend(argv[1], true); });
  cases.emplace_back([&] { FailedMetadata(argv[1]); });
  cases.emplace_back([&] { HealthyBinaryModel(argv[1]); });
  cases.emplace_back([&] { FailedContextConstruction(argv[1], false); });
  cases.emplace_back([&] { FailedContextConstruction(argv[1], true); });
  cases.emplace_back([&] { ExistingOwnerAfterSeal(argv[1]); });
  for (int stage : {kCreateLog, kCreateBackend, kCreateDevice, kCreatePower, kCreateSystem, kCreateContext})
    cases.emplace_back([&, stage] { AmbiguousCreation(argv[1], stage); });
  cases.emplace_back([&] { ZeroPowerId(argv[1]); });
  for (size_t i = 0; i < cases.size(); ++i) {
    const auto child = fork();
    if (child == 0) {
      try { cases[i](); _exit(0); }
      catch (const std::exception &error) { std::cerr << "case " << i << ": " << error.what() << '\n'; _exit(1); }
    }
    if (child < 0) return 3;
    int status = 0;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) return 1;
  }
  std::cout << cases.size() << " actual patched QNN lifetime cases passed; fake API only, no device qualification\n";
}
