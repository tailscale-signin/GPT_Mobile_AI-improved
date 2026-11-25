// Built twice: a test GPU provider and a client of the unmodified production adapter.
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "QnnInterface.h"
#include "GPU/QnnGpuCommon.h"
#include "GPU/QnnGpuGraph.h"

#ifdef TEST_GPU_PROVIDER
static Qnn_ErrorHandle_t create(Qnn_ContextHandle_t ctx, const char* name,
    const QnnGraph_Config_t** configs, Qnn_GraphHandle_t* result) {
  (void)ctx;
  assert(configs && result);
  size_t count = 0;
  int precision = 0;
  while (configs[count]) {
    const QnnGraph_Config_t* c = configs[count++];
    if (c->option != QNN_GRAPH_CONFIG_OPTION_CUSTOM) continue;
    uint32_t version;
    memcpy(&version, c->customConfig, sizeof version);
    if (version == QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG) {
      const QnnGpuGraph_CustomConfigV2_t* gpu = c->customConfig;
      if (gpu->option != QNN_GPU_GRAPH_CONFIG_OPTION_PRECISION) continue;
      assert(gpu->precision == QNN_GPU_PRECISION_FP32);
    } else {
      const QnnGpuGraph_CustomConfig_t* gpu = c->customConfig;
      assert(gpu->precision == QNN_GPU_PRECISION_FP32 && gpu->disableNodeOptimizations == 1);
    }
    precision++;
  }
  assert(precision == 1);
  if (!strcmp(name, "multiple")) assert(count == 3);
  *result = (Qnn_GraphHandle_t)(uintptr_t)0x1234;
  return !strcmp(name, "failure") ? QNN_GRAPH_ERROR_CREATE_FAILED : QNN_SUCCESS;
}

static Qnn_ErrorHandle_t set_config(Qnn_GraphHandle_t graph, const QnnGraph_Config_t** configs) {
  assert(graph == (Qnn_GraphHandle_t)(uintptr_t)0x1234);
  if (configs && configs[0]->option == QNN_GRAPH_CONFIG_OPTION_CUSTOM) {
    const QnnGpuGraph_CustomConfigV2_t* gpu = configs[0]->customConfig;
    assert(gpu->precision == QNN_GPU_PRECISION_FP32);
  }
  if (configs) assert(!configs[1]);
  return QNN_SUCCESS;
}

Qnn_ErrorHandle_t QnnInterface_getProviders(const QnnInterface_t*** out, uint32_t* count) {
  static QnnInterface_t native;
  static const QnnInterface_t* providers[] = {&native, &native};
  native.backendId = getenv("TEST_WRONG_BACKEND") ? 0 : QNN_BACKEND_ID_GPU;
  native.apiVersion.coreApiVersion.major = QNN_API_VERSION_MAJOR;
  native.apiVersion.coreApiVersion.minor = QNN_API_VERSION_MINOR;
  native.QNN_INTERFACE_VER_NAME.graphCreate = create;
  native.QNN_INTERFACE_VER_NAME.graphSetConfig = set_config;
  *out = providers;
  *count = getenv("TEST_AMBIGUOUS") ? 2 : 1;
  return QNN_SUCCESS;
}

#else
int main(void) {
  const QnnInterface_t** providers = NULL;
  uint32_t count = 99;
  assert(QnnInterface_getProviders(NULL, &count) == QNN_INTERFACE_ERROR_INVALID_PARAMETER);
  Qnn_ErrorHandle_t status = QnnInterface_getProviders(&providers, &count);
  if (getenv("TEST_WRONG_BACKEND") || getenv("TEST_AMBIGUOUS")) {
    assert(status != QNN_SUCCESS && count == 0 && !providers);
    return 0;
  }
  assert(status == QNN_SUCCESS && count == 1 && providers[0]->backendId == QNN_BACKEND_ID_GPU);
  const QNN_INTERFACE_VER_TYPE* api = &providers[0]->QNN_INTERFACE_VER_NAME;
  Qnn_GraphHandle_t handle = NULL;
  assert(api->graphCreate(NULL, "empty", NULL, &handle) == QNN_SUCCESS);
  assert(handle && api->graphSetConfig(handle, NULL) == QNN_SUCCESS);
  QnnGraph_Config_t generic = {.option = QNN_GRAPH_CONFIG_OPTION_SET_PROFILING_STATE};
  QnnGpuGraph_CustomConfigV2_t unrelated = {.handshake = QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG,
                                            .option = QNN_GPU_GRAPH_CONFIG_OPTION_DISABLE_QUEUE_RECORDING};
  QnnGraph_Config_t other = {.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM, .customConfig = &unrelated};
  const QnnGraph_Config_t* many[] = {&generic, &other, NULL};
  assert(api->graphCreate(NULL, "multiple", many, &handle) == QNN_SUCCESS);
  QnnGpuGraph_CustomConfig_t legacy = QNN_GPU_GRAPH_CUSTOM_CONFIG_INIT;
  legacy.disableNodeOptimizations = 1;
  QnnGraph_Config_t custom = {.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM, .customConfig = &legacy};
  const QnnGraph_Config_t* one[] = {&custom, NULL};
  assert(api->graphCreate(NULL, "old", one, &handle) == QNN_SUCCESS);
  assert(legacy.precision == QNN_GPU_PRECISION_USER_PROVIDED);
  QnnGpuGraph_CustomConfigV2_t v2 = {.handshake = QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG,
      .option = QNN_GPU_GRAPH_CONFIG_OPTION_PRECISION, .precision = QNN_GPU_PRECISION_FP16};
  custom.customConfig = &v2;
  assert(api->graphCreate(NULL, "new", one, &handle) == QNN_SUCCESS);
  assert(api->graphSetConfig(handle, one) == QNN_SUCCESS && v2.precision == QNN_GPU_PRECISION_FP16);
  const QnnGraph_Config_t* only_generic[] = {&generic, NULL};
  assert(api->graphSetConfig(handle, only_generic) == QNN_SUCCESS);
  const QnnGraph_Config_t* duplicate[] = {&custom, &custom, NULL};
  assert(api->graphCreate(NULL, "duplicate", duplicate, &handle) == QNN_GRAPH_ERROR_INVALID_ARGUMENT);
  assert(api->graphCreate(NULL, "failure", NULL, &handle) == QNN_GRAPH_ERROR_CREATE_FAILED);
  return 0;
}
#endif
