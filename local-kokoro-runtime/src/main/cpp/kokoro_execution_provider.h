#ifndef MELO_KOKORO_EXECUTION_PROVIDER_H_
#define MELO_KOKORO_EXECUTION_PROVIDER_H_

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <memory>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_cxx_api.h"
#include "kokoro_profile.h"

namespace kokoro_runtime {

struct Qualification {
  KokoroProfileStats accelerator;
  KokoroProfileStats cpu;
};
inline Qualification &LastQualification() { static thread_local Qualification value; return value; }
struct SelectedDevice { int type = -1; uint32_t vendor = 0; uint32_t id = 0; };
inline SelectedDevice &LastSelectedDevice() { static thread_local SelectedDevice value; return value; }

// ORT keeps a process-wide environment. Keep the plugin registered for its whole
// lifetime so another warm session cannot unload a provider still in use.
inline std::shared_ptr<Ort::Env> Environment() {
  static auto env = std::make_shared<Ort::Env>(ORT_LOGGING_LEVEL_ERROR, "kokoro");
  return env;
}

inline Ort::SessionOptions Options(Ort::Env &env, int threads,
    const std::string &provider, const std::string &library_dir,
    const std::string &profile_prefix, int64_t token_count) {
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(threads);
  options.SetInterOpNumThreads(1);
  options.AddConfigEntry("session.intra_op.allow_spinning", "0");
  options.AddConfigEntry("session.inter_op.allow_spinning", "0");
  if (provider == "cpu") return options;
  if ((provider != "qnn-gpu" && provider != "qnn-htp") ||
      library_dir.empty() || library_dir.front() != '/' || profile_prefix.empty())
    throw std::runtime_error("Invalid Kokoro execution provider configuration");
#if defined(__ANDROID__) && defined(__aarch64__)
  static std::once_flag registered;
  std::call_once(registered, [&] {
    env.RegisterExecutionProviderLibrary("QNNExecutionProvider",
        library_dir + "/libkokoro_qnn_adapter.so");
  });
  std::vector<Ort::ConstEpDevice> selected;
  const auto requested_type = provider == "qnn-gpu" ? OrtHardwareDeviceType_GPU : OrtHardwareDeviceType_NPU;
  for (const auto &device : env.GetEpDevices()) {
    if (std::string(device.EpName()) == "QNNExecutionProvider" && device.Device().Type() == requested_type) {
      selected.push_back(device);
      LastSelectedDevice() = {static_cast<int>(device.Device().Type()), device.Device().VendorId(), device.Device().DeviceId()};
      break;  // One exact hardware device, never the GPU and HTP together.
    }
  }
  if (selected.empty()) throw std::runtime_error("No Qualcomm speech device");
  // QAIRT 2.51 and the plugin 2.7.0 are pinned together in Gradle. Explicit
  // backend paths ensure the GPU selection cannot accidentally use HTP or CPU.
  std::unordered_map<std::string, std::string> settings{
      {"backend_path", library_dir + (provider == "qnn-gpu" ? "/libQnnGpu.so" : "/libQnnHtp.so")}};
  if (provider == "qnn-htp") settings.emplace("htp_performance_mode", "balanced");
  if (token_count < 3 || token_count > 511) throw std::runtime_error("Invalid speech graph shape");
  // The pinned Kokoro export names its input sequence dimension explicitly.
  // Specialize real token counts; padding would alter durations and spoken audio.
  options.AddFreeDimensionOverrideByName("sequence_length", token_count);
  options.AppendExecutionProvider_V2(env, selected, settings);
  options.AddConfigEntry("session.record_ep_graph_assignment_info", "1");
  options.EnableProfiling(profile_prefix.c_str());
  return options;
#else
  throw std::runtime_error("Qualcomm speech requires Android arm64");
#endif
}

// Qualification is based on a completed inference's kernel events, not the list
// of installed providers. Profiles contain operator metadata, never phrase text.
inline int FinishQualification(Ort::Session &session) {
  Ort::AllocatorWithDefaultOptions allocator;
  const auto filename = session.EndProfilingAllocated(allocator);
  const std::string path(filename.get());
  struct Cleanup { const std::string &path; ~Cleanup() { std::remove(path.c_str()); } } cleanup{path};
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file || file.tellg() < 0 || file.tellg() > 8 * 1024 * 1024)
    throw std::runtime_error("Speech accelerator profile unavailable");
  const auto size = static_cast<size_t>(file.tellg());
  std::string json(size, '\0');
  file.seekg(0);
  if (!file.read(json.data(), size)) throw std::runtime_error("Incomplete speech profile");
  LastQualification() = {KokoroExecutedProviderStats(json, "QNNExecutionProvider"),
      KokoroExecutedProviderStats(json, "CPUExecutionProvider")};
  if (LastQualification().accelerator.kernels == 0) throw std::runtime_error("Kokoro graph executed on CPU only");
  return LastQualification().accelerator.kernels;
}

}  // namespace kokoro_runtime
#endif
