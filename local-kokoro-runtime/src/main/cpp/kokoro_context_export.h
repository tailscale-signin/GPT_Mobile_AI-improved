#ifndef MELO_KOKORO_CONTEXT_EXPORT_H_
#define MELO_KOKORO_CONTEXT_EXPORT_H_

#include <atomic>
#include "kokoro_context_cache.h"
#include "kokoro_execution_provider.h"

namespace kokoro_runtime {

inline constexpr const char *kContextExportRecipe = "basic-source1-all-embedded-initializers1";

inline void RequireContextActive(const std::atomic<bool> &cancelled) {
  if (cancelled.load()) throw std::runtime_error("Kokoro generation cancelled");
}

struct ContextInitializerState { const std::atomic<bool> &cancelled; };
inline OrtStatus *ORT_API_CALL EmbedContextInitializer(void *state, const char *, const OrtValue *,
    const OrtExternalInitializerInfo *, OrtExternalInitializerInfo **location) noexcept {
  // This documented callback changes storage only. ORT retains ownership of the
  // original values and embeds them when the returned external location is null.
  *location = nullptr;
  if (static_cast<ContextInitializerState *>(state)->cancelled.load())
    return Ort::GetApi().CreateStatus(ORT_FAIL, "Kokoro generation cancelled");
  return nullptr;
}

inline void CompileEmbeddedContext(Ort::Env &env, int threads, const std::string &model_path,
    const std::string &provider, const std::string &library_dir, const std::string &profile_prefix,
    int64_t token_count, const std::string &context_path, const std::string &htp_performance_mode,
    const std::atomic<bool> &cancelled) {
  RequireContextActive(cancelled);
  if (provider != "qnn-htp" || token_count < 3 || token_count > 511)
    throw std::runtime_error("Invalid speech context compilation");
  // Minimal ORT builds can omit this API. Failure follows the existing optional
  // cache fallback; a missing compile capability never grants NPU evidence.
  const auto &compile_api = Ort::GetCompileApi();
  if (!compile_api.ModelCompilationOptions_SetOutputModelGetInitializerLocationFunc ||
      !compile_api.ModelCompilationOptions_SetGraphOptimizationLevel || !compile_api.ModelCompilationOptions_SetFlags)
    throw std::runtime_error("Speech context compilation unavailable");
  ContextSourceSnapshot source(context_path);
  {
    auto options = Options(env, threads, "cpu", "", "", 0);
    options.AddFreeDimensionOverrideByName("sequence_length", token_count);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_BASIC);
    options.SetOptimizedModelFilePath(source.path().c_str());
    // ORT1.28.2's context-node cloning otherwise copies stale control-flow
    // attributes after constant folding. A BASIC-only save synchronizes them
    // without applying CPU-specific extended or layout transformations.
    Ort::Session canonical(env, model_path.c_str(), options);
  }  // Release the CPU preparation session before allocating QNN graphs.
  RequireContextActive(cancelled);
  source.Validate();
  {
    auto options = Options(env, threads, provider, library_dir, profile_prefix, token_count, "", htp_performance_mode);
    options.DisableProfiling();  // Compilation has no executed PCM to qualify.
    Ort::ModelCompilationOptions compilation(env, options);
    compilation.SetInputModelPath(source.path().c_str());
    compilation.SetOutputModelPath(context_path.c_str());
    compilation.SetEpContextEmbedMode(true);
    // Compile API defaults to DISABLE_ALL even when SessionOptions was copied.
    // Preserve the ordinary QNN session's default optimization level explicitly.
    compilation.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    compilation.SetFlags(OrtCompileApiFlags_ERROR_IF_NO_NODES_COMPILED | OrtCompileApiFlags_ERROR_IF_OUTPUT_FILE_EXISTS);
    ContextInitializerState state{cancelled};
    // The pinned runtime's default external-initializer serializer appends
    // duplicate nested initializers. Its supported callback serializer rebuilds
    // those lists correctly; values, operators and metadata remain ORT-owned.
    compilation.SetOutputModelGetInitializerLocationFunc(EmbedContextInitializer, &state);
    RequireContextActive(cancelled);
    Ort::ThrowOnError(compile_api.CompileModel(env, compilation));
  }
  RequireContextActive(cancelled);
  source.Remove();  // The context must load independently of this intermediate.
}

}  // namespace kokoro_runtime
#endif
