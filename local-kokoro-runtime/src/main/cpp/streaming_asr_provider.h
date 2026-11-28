#ifndef GPTMOBILE_STREAMING_ASR_PROVIDER_H_
#define GPTMOBILE_STREAMING_ASR_PROVIDER_H_

#include <array>
#include <atomic>
#include <cstdint>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include "kokoro_execution_provider.h"
#include "streaming_asr_retirement.h"

namespace asr_runtime {

struct ComponentEvidence {
  int64_t completed_runs = 0;
  int64_t accelerator_kernels = 0;
  int64_t cpu_kernels = 0;
  bool profile_completed = false;
};

struct Context {
  std::string provider;
  std::string library_dir;
  std::string profile_prefix;
  int threads = 2;
  int window_size = 0;
  int cache_channel_time = 0;
  int cache_time_width = 0;
  std::array<ComponentEvidence, 3> components;
  std::string discovery = "not_started";
  std::atomic<bool> cancelled{false};
  Ort::RunOptions run_options;
  RetainedNativeOwners native_owners;
};

inline thread_local Context *active_context = nullptr;

template <typename Owner>
inline void RetainNativeOwner(const std::shared_ptr<Owner> &owner) {
  if (!active_context) throw std::runtime_error("ASR_LIFETIME_UNAVAILABLE");
  active_context->native_owners.Retain(owner);
}
struct Scope {
  Context *previous;
  explicit Scope(Context &context) : previous(active_context) { active_context = &context; }
  ~Scope() { active_context = previous; }
};

inline void CheckCancellation() {
  if (active_context && active_context->cancelled.load()) throw std::runtime_error("ASR_CANCELLED");
}

inline const Ort::RunOptions &RunOptions() {
  static thread_local Ort::RunOptions defaults;
  return active_context ? active_context->run_options : defaults;
}

inline std::unique_ptr<Ort::Session> MakeSession(Ort::Env &env,
    const std::string &path, const Ort::SessionOptions &baseline, int component) {
  auto *context = active_context;
  if (!context || context->provider != "qnn-gpu")
    return std::make_unique<Ort::Session>(env, path.c_str(), baseline);
  CheckCancellation();
  if (component < 0 || component > 2 || context->window_size <= 0 ||
      context->cache_channel_time <= 0 || context->cache_time_width <= 0)
    throw std::runtime_error("ASR_MODEL_SHAPE");
  auto runtime = kokoro_runtime::Environment();
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(context->threads);
  options.SetInterOpNumThreads(1);
  options.AddConfigEntry("session.intra_op.allow_spinning", "0");
  options.AddConfigEntry("session.inter_op.allow_spinning", "0");
  const auto devices = kokoro_runtime::SelectDevices(*runtime, "qnn-gpu", context->library_dir);
  context->discovery = kokoro_runtime::LastDiscovery();
  std::unordered_map<std::string, std::string> settings{
      {"backend_path", context->library_dir + "/libQnnGpu.so"}};
  // The catalog binds these dimensions to the immutable upstream 560 ms
  // export. Only batch one and a single recurrent prediction step are used.
  for (const auto *name : {"batch", "batch_size"}) options.AddFreeDimensionOverrideByName(name, 1);
  if (component == 0) {
    options.AddFreeDimensionOverrideByName("time", context->window_size);
    options.AddFreeDimensionOverrideByName("cache_channel_time", context->cache_channel_time);
    options.AddFreeDimensionOverrideByName("cache_time_width", context->cache_time_width);
  } else {
    for (const auto *name : {"targets_dynamic_axes_1", "targets_dynamic_axes_2",
        "target_length_dynamic_axes_1", "states.1_dim_1", "encoder_outputs_dynamic_axes_1",
        "encoder_outputs_dynamic_axes_2", "decoder_outputs_dynamic_axes_1", "decoder_outputs_dynamic_axes_2"})
      options.AddFreeDimensionOverrideByName(name, 1);
  }
  options.AppendExecutionProvider_V2(*runtime, devices, settings);
  options.AddConfigEntry("session.record_ep_graph_assignment_info", "1");
  const auto prefix = context->profile_prefix + "-" + std::to_string(component);
  options.EnableProfiling(prefix.c_str());
  return std::make_unique<Ort::Session>(*runtime, path.c_str(), options);
}

inline void ObserveSessionRun(Ort::Session &session, int component) {
  auto *context = active_context;
  if (!context) return;
  CheckCancellation();
  auto &evidence = context->components.at(component);
  ++evidence.completed_runs;
  if (context->provider != "qnn-gpu" || evidence.profile_completed) return;
  Ort::AllocatorWithDefaultOptions allocator;
  const auto name = session.EndProfilingAllocated(allocator);
  const std::string filename(name.get());
  struct RemoveProfile {
    const std::string &path;
    ~RemoveProfile() { std::remove(path.c_str()); }
  } cleanup{filename};
  std::ifstream file(filename, std::ios::binary | std::ios::ate);
  if (!file || file.tellg() < 0 || file.tellg() > 32 * 1024 * 1024)
    throw std::runtime_error("ASR_PROFILE_UNAVAILABLE");
  const auto bytes = static_cast<size_t>(file.tellg());
  std::string json(bytes, '\0');
  file.seekg(0);
  if (!file.read(json.data(), bytes)) throw std::runtime_error("ASR_PROFILE_UNAVAILABLE");
  evidence.accelerator_kernels = KokoroExecutedProviderStats(json, "QNNExecutionProvider").kernels;
  evidence.cpu_kernels = KokoroExecutedProviderStats(json, "CPUExecutionProvider").kernels;
  evidence.profile_completed = true;
  if (component == 0 && evidence.accelerator_kernels == 0)
    throw std::runtime_error("ASR_ACCELERATOR_NOT_EXECUTED");
}

inline void ObserveHtpRun(int component) {
  CheckCancellation();
  if (active_context && active_context->provider == "qnn-htp-context")
    ++active_context->components.at(component).completed_runs;
}

}  // namespace asr_runtime
#endif
