// Exercise the public ABI boundary with opaque graph fixtures. The real plugin
// remains the owner of its original factory pointer and all returned statuses.
#include "kokoro_qnn_adapter.cpp"

struct OrtStatus { OrtErrorCode code; };
struct OrtTensorTypeAndShapeInfo { int64_t dimension; };
struct OrtTypeInfo { OrtTensorTypeAndShapeInfo tensor; };
struct OrtValueInfo { const OrtNode *producer; OrtTypeInfo type{{4}}; };
struct OrtNode {
  const OrtGraph *graph;
  std::vector<const OrtValueInfo *> inputs;
  std::vector<const OrtValueInfo *> implicit_inputs;
  std::vector<const OrtValueInfo *> outputs;
};
struct OrtGraph { std::vector<const OrtNode *> nodes; };
struct OrtHardwareDevice { OrtHardwareDeviceType type; uint32_t vendor, id; };
struct OrtKeyValuePairs {};

namespace {
OrtApi fixture_api{};
OrtEpApi fixture_ep_api{};
OrtApiBase fixture_base{};
OrtEpFactory fixture_factory{};
OrtStatus fixture_failure{ORT_EP_FAIL};
const OrtNode *const *last_nodes = nullptr;
const OrtNodeFusionOptions *last_options = nullptr;
std::vector<std::vector<const OrtNode *>> submitted;
int created = 0, released = 0;
int discovered = 0, hardware_created = 0, hardware_released = 0;
constexpr uint32_t kQnnVendorId = static_cast<uint32_t>('Q') | (static_cast<uint32_t>('C') << 8) |
    (static_cast<uint32_t>('O') << 16) | (static_cast<uint32_t>('M') << 24);
kokoro_runtime::GpuDiscovery probe_result{kokoro_runtime::GpuDiscoveryCode::NoAvailableQualcommGpu};
std::vector<const OrtHardwareDevice *> passed_devices;
std::vector<const OrtHardwareDevice *> supported_devices;
bool reject_group = false;
void Require(bool value, const char *message = "QNN adapter ABI regression") { if (!value) throw std::runtime_error(message); }

void SetUp() {
  fixture_api.CreateStatus = [](OrtErrorCode code, const char *) noexcept -> OrtStatus * { return new OrtStatus{code}; };
  fixture_api.ReleaseStatus = [](OrtStatus *status) noexcept { delete status; };
  fixture_api.HardwareDevice_Type = [](const OrtHardwareDevice *device) noexcept { return device->type; };
  fixture_api.HardwareDevice_VendorId = [](const OrtHardwareDevice *device) noexcept { return device->vendor; };
  fixture_api.CreateKeyValuePairs = [](OrtKeyValuePairs **pairs) noexcept { *pairs = new OrtKeyValuePairs; };
  fixture_api.AddKeyValuePair = [](OrtKeyValuePairs *, const char *, const char *) noexcept {};
  fixture_api.ReleaseKeyValuePairs = [](OrtKeyValuePairs *pairs) noexcept { delete pairs; };
  fixture_ep_api.CreateHardwareDevice = [](OrtHardwareDeviceType type, uint32_t vendor, uint32_t id,
      const char *, const OrtKeyValuePairs *, OrtHardwareDevice **device) noexcept -> OrtStatus * {
    *device = new OrtHardwareDevice{type, vendor, id}; ++hardware_created; return nullptr;
  };
  fixture_ep_api.ReleaseHardwareDevice = [](OrtHardwareDevice *device) noexcept { delete device; ++hardware_released; };
  fixture_factory.GetVendorId = [](const OrtEpFactory *) noexcept -> uint32_t { return kQnnVendorId; };
  fixture_factory.GetSupportedDevices = [](OrtEpFactory *factory, const OrtHardwareDevice *const *devices,
      size_t count, OrtEpDevice **, size_t maximum, size_t *returned) noexcept -> OrtStatus * {
    Require(factory == &fixture_factory);
    passed_devices.clear();
    supported_devices.clear();
    for (size_t i = 0; i < count; ++i) {
      passed_devices.push_back(devices[i]);
      // The pinned QNN factory accepts GPU hardware only when its ORT vendor
      // matches GetVendorId (packed QCOM), not the OpenCL vendor value 0x5143.
      if (devices[i]->type == OrtHardwareDeviceType_GPU && devices[i]->vendor == factory->GetVendorId(factory) &&
          supported_devices.size() < maximum) supported_devices.push_back(devices[i]);
    }
    *returned = supported_devices.size(); ++discovered; return nullptr;
  };
  probe_gpu = []() { return probe_result; };
  fixture_api.GetValueInfoTypeInfo = [](const OrtValueInfo *value, const OrtTypeInfo **type) noexcept -> OrtStatus * {
    *type = &value->type; return nullptr;
  };
  fixture_api.CastTypeInfoToTensorInfo = [](const OrtTypeInfo *type, const OrtTensorTypeAndShapeInfo **tensor) noexcept -> OrtStatus * {
    *tensor = &type->tensor; return nullptr;
  };
  fixture_api.TensorTypeAndShape_HasShape = [](const OrtTensorTypeAndShapeInfo *) noexcept { return true; };
  fixture_api.GetDimensionsCount = [](const OrtTensorTypeAndShapeInfo *, size_t *rank) noexcept -> OrtStatus * {
    *rank = 1; return nullptr;
  };
  fixture_api.GetDimensions = [](const OrtTensorTypeAndShapeInfo *tensor, int64_t *dimensions, size_t) noexcept -> OrtStatus * {
    *dimensions = tensor->dimension; return nullptr;
  };
  fixture_api.Node_GetGraph = [](const OrtNode *node, const OrtGraph **graph) noexcept -> OrtStatus * {
    *graph = node->graph; return nullptr;
  };
  fixture_api.Graph_GetNumNodes = [](const OrtGraph *graph, size_t *count) noexcept -> OrtStatus * {
    *count = graph->nodes.size(); return nullptr;
  };
  fixture_api.Graph_GetNodes = [](const OrtGraph *graph, const OrtNode **nodes, size_t count) noexcept -> OrtStatus * {
    std::copy_n(graph->nodes.begin(), count, nodes); return nullptr;
  };
  fixture_api.Node_GetNumInputs = [](const OrtNode *node, size_t *count) noexcept -> OrtStatus * {
    *count = node->inputs.size(); return nullptr;
  };
  fixture_api.Node_GetInputs = [](const OrtNode *node, const OrtValueInfo **inputs, size_t count) noexcept -> OrtStatus * {
    std::copy_n(node->inputs.begin(), count, inputs); return nullptr;
  };
  fixture_api.Node_GetNumImplicitInputs = [](const OrtNode *node, size_t *count) noexcept -> OrtStatus * {
    *count = node->implicit_inputs.size(); return nullptr;
  };
  fixture_api.Node_GetImplicitInputs = [](const OrtNode *node, const OrtValueInfo **inputs, size_t count) noexcept -> OrtStatus * {
    std::copy_n(node->implicit_inputs.begin(), count, inputs); return nullptr;
  };
  fixture_api.Node_GetNumOutputs = [](const OrtNode *node, size_t *count) noexcept -> OrtStatus * {
    *count = node->outputs.size(); return nullptr;
  };
  fixture_api.Node_GetOutputs = [](const OrtNode *node, const OrtValueInfo **outputs, size_t count) noexcept -> OrtStatus * {
    std::copy_n(node->outputs.begin(), count, outputs); return nullptr;
  };
  fixture_api.ValueInfo_GetValueProducer = [](const OrtValueInfo *input, const OrtNode **producer, size_t *index) noexcept -> OrtStatus * {
    *producer = input->producer; *index = 0; return nullptr;
  };
  fixture_ep_api.EpGraphSupportInfo_AddNodesToFuse = [](OrtEpGraphSupportInfo *, const OrtNode *const *nodes,
      size_t count, const OrtNodeFusionOptions *options) noexcept -> OrtStatus * {
    last_nodes = nodes; last_options = options;
    if (reject_group) return &fixture_failure;
    submitted.emplace_back(nodes, nodes + count);
    return nullptr;
  };
  fixture_api.GetEpApi = []() noexcept -> const OrtEpApi * { return &fixture_ep_api; };
  fixture_base.GetApi = [](uint32_t version) noexcept -> const OrtApi * {
    return version <= ORT_API_VERSION ? &fixture_api : nullptr;
  };
  fixture_base.GetVersionString = []() noexcept -> const char * { return "fixture"; };
  // Only the loader is substituted. Runtime and EP table forwarding below is
  // the identical implementation compiled into the production shared library.
  std::call_once(initialized, [] {
    original_base = &fixture_base; original_api = &fixture_api; original_ep_api = &fixture_ep_api;
    adapted_api = fixture_api; adapted_ep_api = fixture_ep_api;
    adapted_ep_api.EpGraphSupportInfo_AddNodesToFuse = AddConvexGroups;
    adapted_api.GetEpApi = GetAdaptedEpApi;
    adapted_base.GetApi = GetAdaptedApi; adapted_base.GetVersionString = RuntimeVersion;
  });
  create_factories = [](const char *name, const OrtApiBase *base, const OrtLogger *, OrtEpFactory **factories,
      size_t maximum, size_t *count) -> OrtStatus * {
    Require(std::string(name) == "QNNExecutionProvider" && maximum == 1);
    Require(base != original_base && base->GetApi(ORT_API_VERSION) == &adapted_api);
    Require(base->GetApi(ORT_API_VERSION + 1) == nullptr);
    Require(base->GetApi(ORT_API_VERSION)->GetEpApi()->EpGraphSupportInfo_AddNodesToFuse == AddConvexGroups);
    Require(base->GetApi(ORT_API_VERSION)->Node_GetGraph == fixture_api.Node_GetGraph);
    factories[0] = &fixture_factory; *count = 1; ++created; return nullptr;
  };
  release_factory = [](OrtEpFactory *factory) -> OrtStatus * {
    Require(factory == &fixture_factory); ++released; return nullptr;
  };
}
}  // namespace

int main() {
  SetUp();
  OrtEpFactory *factory = nullptr;
  size_t count = 0;
  Require(CreateEpFactories("QNNExecutionProvider", &fixture_base, nullptr, &factory, 1, &count) == nullptr);
  Require(factory == &fixture_factory && count == 1 && created == 1);
  size_t returned = 99;
  Require(factory->GetSupportedDevices(factory, nullptr, 0, nullptr, 1, &returned) == nullptr);
  Require(discovered == 1 && returned == 0 && passed_devices.empty() && hardware_created == 0);
  OrtHardwareDevice wrong_vendor{OrtHardwareDeviceType_GPU, 0x5143, 7};
  const OrtHardwareDevice *opencl_identity[] = {&wrong_vendor};
  Require(factory->GetSupportedDevices(factory, opencl_identity, 1, nullptr, 1, &returned) == nullptr);
  Require(returned == 0 && supported_devices.empty() && hardware_created == 0);
  OrtHardwareDevice already{OrtHardwareDeviceType_GPU, kQnnVendorId, 7};
  const OrtHardwareDevice *existing[] = {&already};
  Require(factory->GetSupportedDevices(factory, existing, 1, nullptr, 1, &returned) == nullptr);
  Require(returned == 1 && passed_devices == std::vector<const OrtHardwareDevice *>{&already} && hardware_created == 0);
  probe_result = {kokoro_runtime::GpuDiscoveryCode::Available, 0x5143, 3};
  Require(factory->GetSupportedDevices(factory, nullptr, 0, nullptr, 1, &returned) == nullptr);
  Require(returned == 1 && passed_devices.size() == 1 && passed_devices[0]->type == OrtHardwareDeviceType_GPU &&
      passed_devices[0]->vendor == kQnnVendorId && passed_devices[0]->id == 3 && hardware_created == 1,
      "OpenCL-proven GPU was rejected by original QNN vendor filter");
  Require(factory->GetSupportedDevices(factory, nullptr, 0, nullptr, 1, &returned) == nullptr);
  Require(hardware_created == 1 && hardware_released == 0);  // Stable borrowed device lifetime.
  Require(factory->GetSupportedDevices(factory, existing, 1, nullptr, 1, &returned) == nullptr);
  Require(returned == 1 && passed_devices == std::vector<const OrtHardwareDevice *>{&already} && hardware_created == 1);
  Require(KokoroQnnGpuDiscoveryCode() == static_cast<int>(kokoro_runtime::GpuDiscoveryCode::AlreadyEnumerated));
  Require(ReleaseEpFactory(factory) == nullptr && released == 1);
  Require(hardware_released == 1 && fixture_factory.GetSupportedDevices != SupportedDevicesWithGpu);

  OrtGraph graph, outer;
  OrtNode a{&graph, {}, {}, {}}, b{&graph, {}, {}, {}}, c{&graph, {}, {}, {}}, capture{&outer, {}, {}, {}};
  OrtValueInfo av{&a}, bv{&b}, outside{&capture};
  b.inputs = {&av}; c.inputs = {&av}; c.implicit_inputs = {&bv, &outside};
  graph.nodes = {&a, &b, &c};
  const OrtNode *nonconvex[] = {&a, &c};
  OrtNodeFusionOptions options{};
  Require(AddConvexGroups(nullptr, nonconvex, 2, &options) == nullptr);
  Require(submitted == std::vector<std::vector<const OrtNode *>>{{&a}, {&c}});
  Require(last_options == &options);

  submitted.clear();
  const OrtNode *convex[] = {&c, &b};
  Require(AddConvexGroups(nullptr, convex, 2, &options) == nullptr);
  Require(submitted.size() == 1 && last_nodes == convex && last_options == &options);

  // Static tokens do not imply static duration-expanded output tensors. An
  // unresolved interface is left to CPU instead of entering an invalid QNN IO map.
  submitted.clear();
  OrtValueInfo dynamic{&c, {{-1}}};
  c.outputs = {&dynamic};
  Require(AddConvexGroups(nullptr, nonconvex, 2, &options) == nullptr);
  Require(submitted == std::vector<std::vector<const OrtNode *>>{{&a}});
  c.outputs.clear();

  const OrtNode *wrong_graph[] = {&a, &capture};
  auto *invalid = AddConvexGroups(nullptr, wrong_graph, 2, &options);
  Require(invalid != nullptr && submitted.size() == 1);
  fixture_api.ReleaseStatus(invalid);
  reject_group = true;
  Require(AddConvexGroups(nullptr, nonconvex, 2, &options) == &fixture_failure);
  Require(submitted.size() == 1);  // Preserve provider/runtime errors without manufacturing support.
  return 0;
}
