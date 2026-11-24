// Native QNN GPU graph-configuration diagnostic. No ONNX EP option is assumed.
// Build against the separately installed, audited QAIRT SDK; no SDK is bundled.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <dlfcn.h>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>

#include "GPU/QnnGpuCommon.h"
#include "GPU/QnnGpuGraph.h"
#include "QnnInterface.h"
#include "QnnOpDef.h"

namespace {
void Check(Qnn_ErrorHandle_t result, const char* stage) {
  if (result != QNN_SUCCESS) {
    throw std::runtime_error(std::string(stage) + " failed with QNN code " +
                             std::to_string(static_cast<unsigned long long>(result)));
  }
}

void Log(const char* format, QnnLog_Level_t, uint64_t, va_list arguments) {
  std::vfprintf(stderr, format, arguments);
  std::fputc('\n', stderr);
}

struct Resources {
  void* library = nullptr;
  const QNN_INTERFACE_VER_TYPE* api = nullptr;
  Qnn_LogHandle_t log = nullptr;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;

  ~Resources() {
    if (api) {
      if (context && api->contextFree) api->contextFree(context, nullptr);
      if (device && api->deviceFree) api->deviceFree(device);
      if (backend && api->backendFree) api->backendFree(backend);
      if (log && api->logFree) api->logFree(log);
    }
    if (library) dlclose(library);
  }
};

Qnn_Tensor_t Tensor(const char* name, Qnn_TensorType_t kind, uint32_t* dimensions,
                    float* data = nullptr) {
  Qnn_Tensor_t result = QNN_TENSOR_INIT;
  result.v1.name = name;
  result.v1.type = kind;
  result.v1.dataType = QNN_DATATYPE_FLOAT_32;
  result.v1.rank = 2;
  result.v1.dimensions = dimensions;
  result.v1.memType = QNN_TENSORMEMTYPE_RAW;
  result.v1.clientBuf = {data, static_cast<uint32_t>(data ? 4 * sizeof(float) : 0)};
  return result;
}

void Node(const QNN_INTERFACE_VER_TYPE& api, Qnn_BackendHandle_t backend, Qnn_GraphHandle_t graph,
          const char* name, const char* operation, std::array<Qnn_Tensor_t, 2> inputs,
          Qnn_Tensor_t output) {
  Qnn_OpConfig_t node = QNN_OPCONFIG_INIT;
  node.v1.name = name;
  node.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
  node.v1.typeName = operation;
  node.v1.numOfInputs = inputs.size();
  node.v1.inputTensors = inputs.data();
  node.v1.numOfOutputs = 1;
  node.v1.outputTensors = &output;
  Check(api.backendValidateOpConfig(backend, node), "validate operation");
  Check(api.graphAddNode(graph, node), "add operation");
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 3) {
      throw std::runtime_error("Usage: qnn_gpu_precision_probe /absolute/libQnnGpu.so fp32|fp16|hybrid|user");
    }
    const std::string requested = argv[2];
    QnnGpu_Precision_t precision;
    if (requested == "fp32") precision = QNN_GPU_PRECISION_FP32;
    else if (requested == "fp16") precision = QNN_GPU_PRECISION_FP16;
    else if (requested == "hybrid") precision = QNN_GPU_PRECISION_HYBRID;
    else if (requested == "user") precision = QNN_GPU_PRECISION_USER_PROVIDED;
    else throw std::runtime_error("Unknown precision mode");
    if (argv[1][0] != '/') throw std::runtime_error("An absolute backend path is required");

    Resources resources;
    resources.library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!resources.library) throw std::runtime_error("Cannot load the specified GPU backend");
    const auto get_providers = reinterpret_cast<decltype(&QnnInterface_getProviders)>(
        dlsym(resources.library, "QnnInterface_getProviders"));
    if (!get_providers) throw std::runtime_error("Backend does not export QnnInterface_getProviders");
    const QnnInterface_t** providers = nullptr;
    uint32_t count = 0;
    Check(get_providers(&providers, &count), "enumerate interfaces");
    for (uint32_t index = 0; index < count; ++index) {
      const auto* provider = providers[index];
      if (provider && provider->backendId == QNN_BACKEND_ID_GPU &&
          provider->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
          provider->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
        if (resources.api) throw std::runtime_error("GPU interface is ambiguous");
        resources.api = &provider->QNN_INTERFACE_VER_NAME;
      }
    }
    if (!resources.api) throw std::runtime_error("A compatible, explicitly identified GPU interface is required");
    const auto& api = *resources.api;
    if (!api.logCreate || !api.backendCreate || !api.contextCreate || !api.graphCreate ||
        !api.tensorCreateGraphTensor || !api.backendValidateOpConfig || !api.graphAddNode ||
        !api.graphFinalize || !api.graphExecute) {
      throw std::runtime_error("GPU backend is missing a required QNN function");
    }
    Check(api.logCreate(Log, QNN_LOG_LEVEL_INFO, &resources.log), "create logger");
    Check(api.backendCreate(resources.log, nullptr, &resources.backend), "create GPU backend");
    if (api.deviceCreate) {
      const auto result = api.deviceCreate(resources.log, nullptr, &resources.device);
      if (result != QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) Check(result, "create device");
    }
    Check(api.contextCreate(resources.backend, resources.device, nullptr, &resources.context), "create context");

    // This is the SDK's real graph-configuration transport. The V1 config is
    // sufficient for precision and avoids inventing any QNN execution-provider key.
    QnnGpuGraph_CustomConfig_t gpu = QNN_GPU_GRAPH_CUSTOM_CONFIG_INIT;
    gpu.precision = precision;
    gpu.disableNodeOptimizations = 1;
    QnnGraph_Config_t custom = QNN_GRAPH_CONFIG_INIT;
    custom.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    custom.customConfig = &gpu;
    const QnnGraph_Config_t* configurations[] = {&custom, nullptr};
    Qnn_GraphHandle_t graph = nullptr;
    Check(api.graphCreate(resources.context, "precision_residual", configurations, &graph), "create precision-configured graph");

    uint32_t dimensions[] = {1, 4};
    std::array<float, 4> base{1.f, 2.f, 4.f, 8.f};
    std::array<float, 4> delta{.0001f, .0001f, .0001f, .0001f};
    std::array<float, 4> result{};
    auto input = Tensor("base", QNN_TENSOR_TYPE_APP_WRITE, dimensions, base.data());
    auto increment = Tensor("increment", QNN_TENSOR_TYPE_APP_WRITE, dimensions, delta.data());
    auto intermediate = Tensor("intermediate", QNN_TENSOR_TYPE_NATIVE, dimensions);
    auto output = Tensor("residual", QNN_TENSOR_TYPE_APP_READ, dimensions, result.data());
    for (auto* tensor : {&input, &increment, &intermediate, &output}) {
      Check(api.tensorCreateGraphTensor(graph, tensor), "create tensor");
    }
    Node(api, resources.backend, graph, "add_small_increment", QNN_OP_ELEMENT_WISE_ADD, {input, increment}, intermediate);
    Node(api, resources.backend, graph, "recover_increment", QNN_OP_ELEMENT_WISE_SUBTRACT, {intermediate, input}, output);
    Check(api.graphFinalize(graph, nullptr, nullptr), "finalize graph");
    std::array<Qnn_Tensor_t, 2> inputs{input, increment};
    Check(api.graphExecute(graph, inputs.data(), inputs.size(), &output, 1, nullptr, nullptr), "execute graph");
    double maximum_error = 0;
    for (size_t index = 0; index < result.size(); ++index) {
      if (!std::isfinite(result[index])) throw std::runtime_error("GPU probe returned nonfinite output");
      const float reference = (base[index] + delta[index]) - base[index];
      maximum_error = std::max(maximum_error, std::abs(static_cast<double>(result[index]) - reference));
    }
    std::cout << std::setprecision(10)
              << "{\"schemaVersion\":1,\"backend\":\"QNN_GPU\",\"precision\":\"" << requested
              << "\",\"graphConfigurationAccepted\":true,\"nodeOptimizationsDisabled\":true,"
              << "\"maximumAbsoluteResidualError\":" << maximum_error << ",\"outputs\":[";
    for (size_t index = 0; index < result.size(); ++index) {
      if (index) std::cout << ',';
      std::cout << result[index];
    }
    std::cout << "],\"kokoroQualified\":false,\"listeningQualified\":false}" << std::endl;
    return requested == "fp32" && maximum_error > 1e-6 ? 2 : 0;
  } catch (const std::exception& failure) {
    std::cerr << "GPU precision diagnostic failed: " << failure.what() << std::endl;
    return 1;
  }
}
