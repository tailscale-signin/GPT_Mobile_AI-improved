#include "streaming_asr_qnn_fixture.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"
#include "HTP/QnnHtpDevice.h"
#include <cstdlib>

namespace {
AsrQnnFixture *fixture = nullptr;
Qnn_ErrorHandle_t Record(int stage) {
  if (!fixture || fixture->event_count == 128) std::abort();
  fixture->events[fixture->event_count++] = stage;
  return stage == fixture->fail_first || stage == fixture->fail_second ? 1 : QNN_SUCCESS;
}
void *Handle(int id) { return reinterpret_cast<void *>(static_cast<intptr_t>(id)); }
Qnn_ErrorHandle_t Create(int stage, void **out, int id) {
  const auto result = Record(stage);
  *out = result == QNN_SUCCESS || fixture->error_output ? Handle(id) : nullptr;
  return result;
}
Qnn_ErrorHandle_t CreateLog(QnnLog_Callback_t, QnnLog_Level_t, Qnn_LogHandle_t *out) {
  return Create(kCreateLog, out, 1);
}
Qnn_ErrorHandle_t CreateBackend(Qnn_LogHandle_t, const QnnBackend_Config_t **, Qnn_BackendHandle_t *out) {
  return Create(kCreateBackend, out, 2);
}
Qnn_ErrorHandle_t CreateDevice(Qnn_LogHandle_t, const QnnDevice_Config_t **, Qnn_DeviceHandle_t *out) {
  return Create(kCreateDevice, out, 3);
}
Qnn_ErrorHandle_t CreatePower(uint32_t, uint32_t, uint32_t *out) {
  const auto result = Record(kCreatePower);
  *out = result == QNN_SUCCESS || fixture->error_output ? fixture->power_id : 0;
  return result;
}
Qnn_ErrorHandle_t FreePower(uint32_t) { return Record(kFreePower); }
Qnn_ErrorHandle_t SetPower(uint32_t, const QnnHtpPerfInfrastructure_PowerConfig_t **) { return Record(kSetPower); }
Qnn_ErrorHandle_t Infrastructure(const QnnDevice_Infrastructure_t *out) {
  static QnnHtpDevice_Infrastructure_t value{};
  value.infraType = QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF;
  value.perfInfra.createPowerConfigId = CreatePower;
  value.perfInfra.destroyPowerConfigId = FreePower;
  value.perfInfra.setPowerConfig = SetPower;
  *const_cast<QnnDevice_Infrastructure_t *>(out) = &value;
  return QNN_SUCCESS;
}
Qnn_ErrorHandle_t CreateContext(Qnn_BackendHandle_t, Qnn_DeviceHandle_t, const QnnContext_Config_t **,
    Qnn_ContextHandle_t *out) { return Create(kCreateContext, out, 5); }
Qnn_ErrorHandle_t FromBinary(Qnn_BackendHandle_t, Qnn_DeviceHandle_t, const QnnContext_Config_t **,
    const void *, Qnn_ContextBinarySize_t, Qnn_ContextHandle_t *out, Qnn_ProfileHandle_t) {
  return Create(kCreateContext, out, 5);
}
Qnn_ErrorHandle_t RetrieveGraph(Qnn_ContextHandle_t, const char *, Qnn_GraphHandle_t *out) {
  *out = Handle(6); return Record(kRetrieveGraph);
}
Qnn_ErrorHandle_t FreeContext(Qnn_ContextHandle_t, Qnn_ProfileHandle_t) { return Record(kFreeContext); }
Qnn_ErrorHandle_t FreeDevice(Qnn_DeviceHandle_t) { return Record(kFreeDevice); }
Qnn_ErrorHandle_t FreeBackend(Qnn_BackendHandle_t) { return Record(kFreeBackend); }
Qnn_ErrorHandle_t FreeLog(Qnn_LogHandle_t) { return Record(kFreeLog); }
Qnn_ErrorHandle_t CreateSystem(QnnSystemContext_Handle_t *out) { return Create(kCreateSystem, out, 7); }
Qnn_ErrorHandle_t FreeSystem(QnnSystemContext_Handle_t) { return Record(kFreeSystem); }
Qnn_ErrorHandle_t Metadata(QnnSystemContext_Handle_t, void *, uint64_t,
    const QnnSystemContext_BinaryInfo_t **out, Qnn_ContextBinarySize_t *size) {
  static QnnSystemContext_GraphInfo_t graph{};
  graph.version = QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3;
  graph.graphInfoV3.graphName = "retirement-fixture";
  static QnnSystemContext_BinaryInfo_t binary{};
  binary.version = QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3;
  binary.contextBinaryInfoV3.numGraphs = 1;
  binary.contextBinaryInfoV3.graphs = &graph;
  *out = &binary;
  *size = sizeof(binary);
  return Record(kReadMetadata);
}
__attribute__((destructor)) void Unloaded() { if (fixture) ++fixture->unloads; }
}  // namespace

extern "C" __attribute__((visibility("default"))) void ConfigureFixture(AsrQnnFixture *state) { fixture = state; }
extern "C" __attribute__((visibility("default"))) Qnn_ErrorHandle_t QnnInterface_getProviders(const QnnInterface_t ***out, uint32_t *count) {
  static QnnInterface_t provider{};
  auto &api = provider.QNN_INTERFACE_VER_NAME;
  api.logCreate = CreateLog;
  api.backendCreate = CreateBackend;
  api.deviceCreate = CreateDevice;
  api.deviceGetInfrastructure = Infrastructure;
  api.contextCreate = CreateContext;
  api.contextCreateFromBinary = FromBinary;
  api.graphRetrieve = RetrieveGraph;
  api.contextFree = FreeContext;
  api.deviceFree = FreeDevice;
  api.backendFree = FreeBackend;
  api.logFree = FreeLog;
  static const QnnInterface_t *providers[] = {&provider};
  *out = providers; *count = 1; return QNN_SUCCESS;
}
extern "C" __attribute__((visibility("default"))) Qnn_ErrorHandle_t QnnSystemInterface_getProviders(const QnnSystemInterface_t ***out, uint32_t *count) {
  static QnnSystemInterface_t provider{};
  auto &api = provider.QNN_SYSTEM_INTERFACE_VER_NAME;
  api.systemContextCreate = CreateSystem;
  api.systemContextFree = FreeSystem;
  api.systemContextGetBinaryInfo = Metadata;
  static const QnnSystemInterface_t *providers[] = {&provider};
  *out = providers; *count = 1; return QNN_SUCCESS;
}
