// QNN keeps ownership of every factory, EP, graph and compute callback. This
// adapter narrows proposed fusion groups and supplements driver-proven GPU
// discovery; ORT still validates and compiles through the original provider API.
#include <dlfcn.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "onnxruntime_c_api.h"
#include "kokoro_gpu_discovery.h"
#include "kokoro_partition.h"

namespace {
const OrtApiBase *original_base = nullptr;
const OrtApi *original_api = nullptr;
const OrtEpApi *original_ep_api = nullptr;
OrtApi adapted_api{};
OrtEpApi adapted_ep_api{};
OrtApiBase adapted_base{};
CreateEpApiFactoriesFn create_factories = nullptr;
ReleaseEpApiFactoryFn release_factory = nullptr;
std::once_flag initialized;
using SupportedDevices = decltype(OrtEpFactory::GetSupportedDevices);
struct DeviceFactory {
  SupportedDevices original = nullptr;
  OrtHardwareDevice *gpu = nullptr;
};
std::mutex device_factories_lock;
std::unordered_map<OrtEpFactory *, DeviceFactory> device_factories;
std::atomic<int> gpu_discovery_code{static_cast<int>(kokoro_runtime::GpuDiscoveryCode::NotAttempted)};

kokoro_runtime::GpuDiscovery ProbeGpuHardware() {
#if defined(__ANDROID__) && defined(__aarch64__)
  void *library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
  if (!library) return {kokoro_runtime::GpuDiscoveryCode::LibraryUnavailable};
  kokoro_runtime::OpenClApi api;
  api.platforms = reinterpret_cast<decltype(api.platforms)>(dlsym(library, "clGetPlatformIDs"));
  api.devices = reinterpret_cast<decltype(api.devices)>(dlsym(library, "clGetDeviceIDs"));
  api.info = reinterpret_cast<decltype(api.info)>(dlsym(library, "clGetDeviceInfo"));
  const auto result = kokoro_runtime::ProbeQualcommGpu(api);
  dlclose(library);
  return result;
#else
  return {kokoro_runtime::GpuDiscoveryCode::NotAttempted};
#endif
}
auto probe_gpu = ProbeGpuHardware;

OrtStatus *ORT_API_CALL SupportedDevicesWithGpu(OrtEpFactory *factory,
    const OrtHardwareDevice *const *devices, size_t count, OrtEpDevice **ep_devices,
    size_t maximum, size_t *returned) noexcept {
  try {
    std::lock_guard<std::mutex> lock(device_factories_lock);
    const auto found = device_factories.find(factory);
    if (found == device_factories.end() || !found->second.original || count > 256 || (count && !devices))
      return original_api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid speech device enumeration");
    auto &owner = found->second;
    // QNN uses its ORT factory vendor namespace (packed QCOM in the pinned
    // plugin). OpenCL's Qualcomm ID 0x5143 proves physical hardware but is not
    // an accepted ORT vendor ID. Let the real provider supply its identity.
    const auto provider_vendor = factory->GetVendorId(factory);
    for (size_t i = 0; i < count; ++i) {
      if (devices[i] && original_api->HardwareDevice_Type(devices[i]) == OrtHardwareDeviceType_GPU &&
          original_api->HardwareDevice_VendorId(devices[i]) == provider_vendor) {
        gpu_discovery_code.store(static_cast<int>(kokoro_runtime::GpuDiscoveryCode::AlreadyEnumerated));
        return owner.original(factory, devices, count, ep_devices, maximum, returned);
      }
    }
    // The pinned QNN factory repairs missing NPU discovery but has no Android
    // GPU repair. Add a device only after the vendor OpenCL driver returned an
    // available Qualcomm GPU. A phone model, SoC or NPU is never GPU evidence.
    if (!owner.gpu) {
      const auto probe = probe_gpu();
      gpu_discovery_code.store(static_cast<int>(probe.code));
      if (probe.code == kokoro_runtime::GpuDiscoveryCode::Available) {
        OrtKeyValuePairs *metadata = nullptr;
        original_api->CreateKeyValuePairs(&metadata);
        original_api->AddKeyValuePair(metadata, "is_virtual", "0");
        original_api->AddKeyValuePair(metadata, "discovery_source", "opencl");
        const auto status = original_ep_api->CreateHardwareDevice(OrtHardwareDeviceType_GPU,
            provider_vendor, probe.ordinal, "Qualcomm", metadata, &owner.gpu);
        original_api->ReleaseKeyValuePairs(metadata);
        if (status) return status;
      }
    }
    if (!owner.gpu) return owner.original(factory, devices, count, ep_devices, maximum, returned);
    gpu_discovery_code.store(static_cast<int>(kokoro_runtime::GpuDiscoveryCode::Available));
    std::vector<const OrtHardwareDevice *> actual_devices;
    actual_devices.reserve(count + 1);
    for (size_t i = 0; i < count; ++i) actual_devices.push_back(devices[i]);
    actual_devices.push_back(owner.gpu);
    return owner.original(factory, actual_devices.data(), actual_devices.size(), ep_devices, maximum, returned);
  } catch (const std::exception &) {
    return original_api->CreateStatus(ORT_EP_FAIL, "Speech device discovery failed");
  }
}

void AdaptDeviceDiscovery(OrtEpFactory *factory) {
  if (!factory || !factory->GetSupportedDevices || !factory->GetVendorId) return;
  std::lock_guard<std::mutex> lock(device_factories_lock);
  device_factories.emplace(factory, DeviceFactory{factory->GetSupportedDevices, nullptr});
  factory->GetSupportedDevices = SupportedDevicesWithGpu;
}

OrtStatus *ReleaseAdaptedFactory(OrtEpFactory *factory) {
  OrtHardwareDevice *gpu = nullptr;
  {
    std::lock_guard<std::mutex> lock(device_factories_lock);
    const auto found = device_factories.find(factory);
    if (found != device_factories.end()) {
      factory->GetSupportedDevices = found->second.original;
      gpu = found->second.gpu;
      device_factories.erase(found);
    }
  }
  const auto status = release_factory ? release_factory(factory) : nullptr;
  // Provider/session users are finished before the factory is released. Keep
  // its hardware record alive through the original factory's destructor.
  if (gpu) original_ep_api->ReleaseHardwareDevice(gpu);
  return status;
}

void Check(OrtStatus *status) {
  if (!status) return;
  original_api->ReleaseStatus(status);
  throw std::runtime_error("Speech partition graph inspection failed");
}

bool HasStaticShape(const OrtValueInfo *value) {
  if (!value) return true;  // An absent optional input has no tensor to bind.
  const OrtTypeInfo *type = nullptr;
  Check(original_api->GetValueInfoTypeInfo(value, &type));
  if (!type) return false;
  const OrtTensorTypeAndShapeInfo *tensor = nullptr;
  Check(original_api->CastTypeInfoToTensorInfo(type, &tensor));
  if (!tensor || !original_api->TensorTypeAndShape_HasShape(tensor)) return false;
  size_t rank = 0;
  Check(original_api->GetDimensionsCount(tensor, &rank));
  if (rank > 16) return false;
  std::vector<int64_t> dimensions(rank);
  if (rank) Check(original_api->GetDimensions(tensor, dimensions.data(), rank));
  return std::all_of(dimensions.begin(), dimensions.end(), [](int64_t size) { return size > 0; });
}

bool HasStaticInterfaces(const OrtNode *node) {
  // A fixed text-token count does not fix the duration-expanded acoustic
  // tensors. QNN 2.7 can claim those nodes and then omit a dynamic output from
  // its graph tensor map. Keep these nodes on ORT CPU, while compiling the
  // statically resolved encoder and other supported islands through QNN.
  for (int kind = 0; kind < 3; ++kind) {
    size_t count = 0;
    Check(kind == 0 ? original_api->Node_GetNumInputs(node, &count) :
        kind == 1 ? original_api->Node_GetNumImplicitInputs(node, &count) :
        original_api->Node_GetNumOutputs(node, &count));
    if (count > 100000) return false;
    std::vector<const OrtValueInfo *> values(count);
    if (count) Check(kind == 0 ? original_api->Node_GetInputs(node, values.data(), count) :
        kind == 1 ? original_api->Node_GetImplicitInputs(node, values.data(), count) :
        original_api->Node_GetOutputs(node, values.data(), count));
    for (const auto *value : values) if (!HasStaticShape(value)) return false;
  }
  return true;
}

OrtStatus *ORT_API_CALL AddConvexGroups(OrtEpGraphSupportInfo *info,
    const OrtNode *const *nodes, size_t count, const OrtNodeFusionOptions *options) noexcept {
  try {
    if (!nodes || count == 0 || !nodes[0] || count > 100000)
      throw std::invalid_argument("Invalid speech provider proposal");
    const OrtGraph *graph = nullptr;
    Check(original_api->Node_GetGraph(nodes[0], &graph));
    if (!graph) throw std::invalid_argument("Speech provider graph is unavailable");
    size_t graph_size = 0;
    Check(original_api->Graph_GetNumNodes(graph, &graph_size));
    if (graph_size == 0 || graph_size > 100000)
      throw std::invalid_argument("Invalid speech graph size");
    std::vector<const OrtNode *> ordered(graph_size);
    Check(original_api->Graph_GetNodes(graph, ordered.data(), ordered.size()));
    std::unordered_map<const OrtNode *, size_t> indices;
    for (size_t i = 0; i < ordered.size(); ++i) indices.emplace(ordered[i], i);
    std::vector<std::vector<size_t>> parents(graph_size);
    for (size_t i = 0; i < ordered.size(); ++i) {
      // Control-flow nodes also depend on implicit captures from their parent
      // graph. Omitting these edges can recreate the original invalid fusion.
      for (const bool implicit : {false, true}) {
        size_t input_count = 0;
        Check(implicit ? original_api->Node_GetNumImplicitInputs(ordered[i], &input_count) :
            original_api->Node_GetNumInputs(ordered[i], &input_count));
        if (input_count > 100000) throw std::invalid_argument("Invalid speech graph inputs");
        std::vector<const OrtValueInfo *> inputs(input_count);
        if (input_count) Check(implicit ?
            original_api->Node_GetImplicitInputs(ordered[i], inputs.data(), input_count) :
            original_api->Node_GetInputs(ordered[i], inputs.data(), input_count));
        for (const auto *input : inputs) {
          if (!input) continue;
          const OrtNode *producer = nullptr;
          size_t output_index = 0;
          Check(original_api->ValueInfo_GetValueProducer(input, &producer, &output_index));
          const auto found = indices.find(producer);
          // Graph inputs/initializers and captures produced in an outer graph
          // are not nodes in this graph and must not invent local edges.
          if (found != indices.end()) parents[i].push_back(found->second);
        }
      }
    }
    std::vector<size_t> proposed;
    proposed.reserve(count);
    for (size_t i = 0; i < count; ++i) {
      const auto found = indices.find(nodes[i]);
      if (found == indices.end()) throw std::invalid_argument("Speech proposal crosses graphs");
      if (HasStaticInterfaces(nodes[i])) proposed.push_back(found->second);
    }
    if (proposed.empty()) return nullptr;
    const auto groups = kokoro_runtime::ConvexSpeechPartitions(parents, proposed);
    if (groups.size() == 1 && proposed.size() == count)
      return original_ep_api->EpGraphSupportInfo_AddNodesToFuse(info, nodes, count, options);
    for (const auto &group : groups) {
      std::vector<const OrtNode *> partition;
      partition.reserve(group.size());
      for (const auto i : group) partition.push_back(ordered[i]);
      if (auto *status = original_ep_api->EpGraphSupportInfo_AddNodesToFuse(
              info, partition.data(), partition.size(), options)) return status;
    }
    return nullptr;
  } catch (const std::bad_alloc &) {
    return original_api->CreateStatus(ORT_FAIL, "Speech partition allocation failed");
  } catch (const std::exception &) {
    return original_api->CreateStatus(ORT_EP_FAIL, "Speech provider partition could not be validated");
  }
}

const OrtEpApi *ORT_API_CALL GetAdaptedEpApi() noexcept { return &adapted_ep_api; }
const OrtApi *ORT_API_CALL GetAdaptedApi(uint32_t version) noexcept {
  // The adapter is compiled with the same pinned headers as Kokoro. A newer
  // provider can negotiate an older supported API; never advertise unknown tail
  // fields by copying a shorter ABI table into a newer version.
  return version <= ORT_API_VERSION && original_base->GetApi(version) ? &adapted_api : nullptr;
}
const char *ORT_API_CALL RuntimeVersion() noexcept { return original_base->GetVersionString(); }

void Initialize(const OrtApiBase *base) {
  const auto *api = base ? base->GetApi(ORT_API_VERSION) : nullptr;
  if (!api) throw std::runtime_error("Speech adapter ORT ABI mismatch");
  original_base = base;
  original_api = api;
  original_ep_api = api->GetEpApi();
  adapted_api = *api;
  adapted_ep_api = *original_ep_api;
  adapted_ep_api.EpGraphSupportInfo_AddNodesToFuse = AddConvexGroups;
  adapted_api.GetEpApi = GetAdaptedEpApi;
  adapted_base.GetApi = GetAdaptedApi;
  adapted_base.GetVersionString = RuntimeVersion;

  Dl_info self{};
  if (!dladdr(reinterpret_cast<void *>(&Initialize), &self) || !self.dli_fname)
    throw std::runtime_error("Speech adapter library directory unavailable");
  const std::string location(self.dli_fname);
  const auto slash = location.find_last_of('/');
  if (slash == std::string::npos) throw std::runtime_error("Speech adapter requires an absolute library path");
  // Resolve only the packaged sibling. Do not search environment-controlled
  // paths or unload the provider while a factory/session can still reference it.
  const auto sibling = location.substr(0, slash + 1) + "libonnxruntime_providers_qnn.so";
  void *library = dlopen(sibling.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!library) throw std::runtime_error("Packaged QNN provider could not load");
  create_factories = reinterpret_cast<CreateEpApiFactoriesFn>(dlsym(library, "CreateEpFactories"));
  release_factory = reinterpret_cast<ReleaseEpApiFactoryFn>(dlsym(library, "ReleaseEpFactory"));
  if (!create_factories || !release_factory) {
    dlclose(library);
    throw std::runtime_error("Packaged QNN provider API unavailable");
  }
}
}  // namespace

extern "C" __attribute__((visibility("default"))) OrtStatus *CreateEpFactories(
    const char *name, const OrtApiBase *base, const OrtLogger *logger,
    OrtEpFactory **factories, size_t maximum, size_t *count) noexcept {
  if (!base || !count) return nullptr;  // ORT supplies both; no unknown API can allocate a status.
  *count = 0;
  const auto *api = base->GetApi(ORT_API_VERSION);
  if (!api) return nullptr;
  try {
    std::call_once(initialized, [base] { Initialize(base); });
    if (original_base != base)
      return api->CreateStatus(ORT_EP_FAIL, "Speech adapter cannot share different ORT runtimes");
    const auto status = create_factories(name, &adapted_base, logger, factories, maximum, count);
    if (status) return status;
    for (size_t i = 0; i < *count; ++i) AdaptDeviceDiscovery(factories[i]);
    return nullptr;
  } catch (const std::exception &) {
    if (factories) for (size_t i = 0; i < std::min(*count, maximum); ++i) {
      const auto status = ReleaseAdaptedFactory(factories[i]);
      if (status) api->ReleaseStatus(status);
      factories[i] = nullptr;
    }
    *count = 0;
    return api->CreateStatus(ORT_EP_FAIL, "Speech QNN adapter could not initialize");
  }
}

extern "C" __attribute__((visibility("default"))) OrtStatus *ReleaseEpFactory(OrtEpFactory *factory) noexcept {
  return ReleaseAdaptedFactory(factory);
}

extern "C" __attribute__((visibility("default"))) int KokoroQnnGpuDiscoveryCode() noexcept {
  return gpu_discovery_code.load();
}
