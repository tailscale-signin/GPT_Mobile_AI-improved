// QNN backend entry point used by native Kokoro's original FP32 model.
// The vendor owns every graph and kernel. This adapter sets one published GPU option.
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include "QnnInterface.h"
#include "GPU/QnnGpuCommon.h"
#include "GPU/QnnGpuGraph.h"

static pthread_once_t once = PTHREAD_ONCE_INIT;
static QnnInterface_t provider;
static const QnnInterface_t* interfaces[] = {&provider};
static QnnGraph_CreateFn_t vendor_create;
static QnnGraph_SetConfigFn_t vendor_set_config;
static Qnn_ErrorHandle_t status = QNN_INTERFACE_ERROR_NOT_SUPPORTED;

// Keep other custom options, flags, ordering and generic QNN configs intact.
enum { MAX_CONFIGS = 128 };
struct GraphOptions {
  const QnnGraph_Config_t* list[MAX_CONFIGS + 2];
  QnnGpuGraph_CustomConfig_t legacy;
  QnnGpuGraph_CustomConfigV2_t precision;
  QnnGraph_Config_t replacement;
};

static int configure(struct GraphOptions* out, const QnnGraph_Config_t** input, int creating) {
  const QnnGpuGraph_CustomConfigV2_t fp32 = {
      .handshake = QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG,
      .option = QNN_GPU_GRAPH_CONFIG_OPTION_PRECISION, .precision = QNN_GPU_PRECISION_FP32};
  out->precision = fp32;
  out->replacement.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
  out->replacement.customConfig = &out->precision;
  size_t count = 0;
  int replaced = 0;
  for (; input && input[count]; ++count) {
    if (count == MAX_CONFIGS) return 0;
    const QnnGraph_Config_t* option = input[count];
    out->list[count] = option;
    if (option->option != QNN_GRAPH_CONFIG_OPTION_CUSTOM) continue;
    if (!option->customConfig) return 0;
    uint32_t format;
    memcpy(&format, option->customConfig, sizeof format);
    if (format == QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG) {
      const QnnGpuGraph_CustomConfigV2_t* gpu = option->customConfig;
      if (gpu->option != QNN_GPU_GRAPH_CONFIG_OPTION_PRECISION) continue;
    } else {
      memcpy(&out->legacy, option->customConfig, sizeof out->legacy);
      out->legacy.precision = QNN_GPU_PRECISION_FP32;
      out->replacement.customConfig = &out->legacy;
    }
    if (replaced) return 0;
    out->list[count] = &out->replacement;
    replaced = 1;
  }
  if (creating && !replaced) out->list[count++] = &out->replacement;
  out->list[count] = NULL;
  return 1;
}

static Qnn_ErrorHandle_t create_graph(Qnn_ContextHandle_t context, const char* name,
    const QnnGraph_Config_t** configs, Qnn_GraphHandle_t* graph) {
  struct GraphOptions out;
  if (!configure(&out, configs, 1)) return QNN_GRAPH_ERROR_INVALID_ARGUMENT;
  return vendor_create(context, name, out.list, graph);
}

static Qnn_ErrorHandle_t set_graph_config(Qnn_GraphHandle_t graph, const QnnGraph_Config_t** configs) {
  struct GraphOptions out;
  if (!configs) return vendor_set_config(graph, NULL);
  if (!configure(&out, configs, 0)) return QNN_GRAPH_ERROR_INVALID_ARGUMENT;
  return vendor_set_config(graph, out.list);
}

static void load_gpu(void) {
  void* lib = dlopen("libQnnGpu.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) return;
  typedef Qnn_ErrorHandle_t (*GetProviders)(const QnnInterface_t***, uint32_t*);
  GetProviders get = (GetProviders)dlsym(lib, "QnnInterface_getProviders");
  uint32_t count = 0;
  const QnnInterface_t** list = NULL;
  const QnnInterface_t* selected = NULL;
  if (get && get(&list, &count) == QNN_SUCCESS && list) {
    for (uint32_t i = 0; i < count; ++i) {
      const QnnInterface_t* p = list[i];
      if (!p || p->backendId != QNN_BACKEND_ID_GPU ||
          p->apiVersion.coreApiVersion.major != QNN_API_VERSION_MAJOR ||
          p->apiVersion.coreApiVersion.minor != QNN_API_VERSION_MINOR ||
          !p->QNN_INTERFACE_VER_NAME.graphCreate) continue;
      if (selected) { dlclose(lib); return; }
      selected = p;
    }
  }
  if (!selected) { dlclose(lib); return; }
  provider = *selected;
  vendor_create = provider.QNN_INTERFACE_VER_NAME.graphCreate;
  vendor_set_config = provider.QNN_INTERFACE_VER_NAME.graphSetConfig;
  provider.QNN_INTERFACE_VER_NAME.graphCreate = create_graph;
  if (vendor_set_config) provider.QNN_INTERFACE_VER_NAME.graphSetConfig = set_graph_config;
  // Returned function pointers need the vendor library for the lifetime of the process.
  status = QNN_SUCCESS;
}

__attribute__((visibility("default")))
Qnn_ErrorHandle_t QnnInterface_getProviders(const QnnInterface_t*** result, uint32_t* count) {
  if (!result || !count) return QNN_INTERFACE_ERROR_INVALID_PARAMETER;
  *result = NULL;
  *count = 0;
  pthread_once(&once, load_gpu);
  if (status == QNN_SUCCESS) { *result = interfaces; *count = 1; }
  return status;
}
