// Offline partition policy for the SHA-pinned SM8750 Kokoro decoder.
#include <assert.h>
#include "litert/vendors/c/litert_compiler_plugin_api.h"
#include <dlfcn.h>
#include <stdlib.h>

// The host tool compiles one model on one worker. Only vendor-approved ops in
// these 384/768-frame residual blocks are selected. The long FFT path stays on CPU.
enum { first_op = 165, end_op = 455 };
static LiteRtCompilerPluginInterface_V1 vendor, filtered;
static LiteRtCompilerContext original, context;
static _Thread_local LiteRtOp* eligible;
static LiteRtStatus push(LiteRtOpList list, LiteRtOp op, LiteRtParamIndex group) {
  if (eligible) {
    LiteRtOpCode code;
    LiteRtStatus status = original.get_op_code(op, &code);
    if (status != kLiteRtStatusOk) return status;
    // QAIRT regards per-channel INT8 dequantization as eligible but rejects it at graph creation.
    if (code == kLiteRtOpCodeTflDequantize) return kLiteRtStatusOk;
    for (int i = 0; i < end_op - first_op; ++i)
      if (eligible[i] == op) return original.push_op(list, op, group);
    return kLiteRtStatusOk;
  }
  return original.push_op(list, op, group);
}
static LiteRtStatus create(const LiteRtCompilerContext* source, LiteRtCompilerPlugin* plugin,
                           LiteRtEnvironmentOptions env, LiteRtOptions options) {
  original = *source;
  context = original;
  context.push_op = push;
  return vendor.create_compiler_plugin(&context, plugin, env, options);
}
static LiteRtStatus partition(LiteRtCompilerPlugin plugin, const char* soc, LiteRtSubgraph graph, LiteRtOpList list) {
  LiteRtOp ops[end_op - first_op];
  LiteRtParamIndex count = 0;
  LiteRtStatus status = original.get_num_subgraph_ops(graph, &count);
  if (status != kLiteRtStatusOk || count < end_op) return kLiteRtStatusErrorInvalidArgument;
  for (LiteRtParamIndex i = first_op; i < end_op; ++i) {
    status = original.get_subgraph_op(graph, i, &ops[i - first_op]);
    if (status != kLiteRtStatusOk) return status;
  }
  eligible = ops;
  status = vendor.compiler_plugin_partition(plugin, soc, graph, list);
  eligible = NULL;
  return status;
}

LITERT_CAPI_EXPORT LiteRtStatus LiteRtCompilerPluginQueryInterface(
    LiteRtCompilerPluginInterfaceId id, LiteRtApiVersion version, LiteRtInterface* out) {
  const char* path = getenv("KOKORO_QNN_COMPILER");
  if (!out || !path || path[0] != '/') return kLiteRtStatusErrorInvalidArgument;
  static void* library;
  if (!library) library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!library) return kLiteRtStatusErrorNotFound;
  LiteRtCompilerPluginQueryInterfaceT query = (LiteRtCompilerPluginQueryInterfaceT)dlsym(library, "LiteRtCompilerPluginQueryInterface");
  if (!query) return kLiteRtStatusErrorNotFound;
  LiteRtInterface api = NULL;
  LiteRtStatus status = query(id, version, &api);
  if (status != kLiteRtStatusOk) return status;
  if (id != kLiteRtCompilerPluginInterfaceBasic) return kLiteRtStatusErrorUnsupported;
  vendor = *(const LiteRtCompilerPluginInterface_V1*)api;
  filtered = vendor;
  filtered.create_compiler_plugin = create;
  filtered.compiler_plugin_partition = partition;
  *out = &filtered;
  return kLiteRtStatusOk;
}
