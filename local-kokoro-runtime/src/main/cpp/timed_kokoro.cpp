// Timed frontend for the pinned English Kokoro pack. Sherpa reads the dictionary,
// encodes its symbols and runs the original voice/style/speed model.
#include <jni.h>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <cmath>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>
#include <unistd.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#include "kokoro_measurement.h"
#include "kokoro_cancellation.h"
#include "kokoro_context_cache.h"
#include "kokoro_context_export.h"
#include "kokoro_output_validation.h"
#include "kokoro_pronunciation.h"
#include "kokoro_execution_provider.h"
#include "kokoro_shape_cache.h"
#include "kokoro_progress.h"
#include "sherpa-onnx/csrc/offline-tts-kokoro-model.h"
#include "sherpa-onnx/csrc/symbol-table.h"
#include "sherpa-onnx/csrc/text-utils.h"

namespace {

constexpr int kSampleRate = 24000;
constexpr int kSamplesPerUnit = 600;

void Require(bool condition, const char *message) {
  if (!condition) throw std::runtime_error(message);
}

std::string FromJava(JNIEnv *env, jstring value) {
  Require(value != nullptr, "Missing Kokoro input");
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw std::bad_alloc();
  auto release = [env, value](const char *p) { env->ReleaseStringUTFChars(value, p); };
  std::unique_ptr<const char, decltype(release)> lease(chars, release);
  return std::string(chars);
}

struct Phrase {
  std::vector<int64_t> tokens{0};  // Start PAD has a duration, but owns no word.
  std::vector<jint> word_tokens;
};

struct Frontend {
  Frontend(const std::string &tokens, const std::string &lexicon_path, const char **opening_stage = nullptr) :
      british(lexicon_path.substr(lexicon_path.find_last_of("/\\") + 1) == "lexicon-gb-en.txt") {
    if (opening_stage) *opening_stage = "frontend";
    std::ifstream token_file(tokens);
    std::ifstream dictionary(lexicon_path);
    Require(token_file.good() && dictionary.good(), "Kokoro dictionary unavailable");
    symbols = sherpa_onnx::ReadTokens(token_file);
    for (auto &entry : sherpa_onnx::ParseLexiconFile(dictionary, nullptr)) {
      std::vector<int32_t> ids;
      for (const auto &phone : entry.phonemes) {
        auto symbol = symbols.find(phone);
        if (symbol != symbols.end()) ids.push_back(symbol->second);
      }
      if (!ids.empty()) lexicon[std::move(entry.key)] = std::move(ids);
    }
  }

  Phrase Encode(const std::string &text) const {
    const int bytes = static_cast<int>(std::min<size_t>(text.size(), INT32_MAX));
    if (text.empty()) throw kokoro_runtime::InputFailure("FRONTEND_INPUT_EMPTY", bytes);
    if (text.size() > kokoro_runtime::kInputByteLimit)
      throw kokoro_runtime::InputFailure("FRONTEND_INPUT_TOO_LONG", bytes);
    Phrase phrase;
    for (size_t i = 0; i < text.size();) {
      const auto word_char = [](char c) { return (c >= 'a' && c <= 'z') || c == '\''; };
      if (word_char(text[i])) {
        size_t begin = i++;
        while (i < text.size() && word_char(text[i])) ++i;
        const auto key = text.substr(begin, i - begin);
        const auto word = lexicon.find(key);
        phrase.word_tokens.push_back(static_cast<jint>(phrase.tokens.size()));
        if (word != lexicon.end()) {
          phrase.tokens.insert(phrase.tokens.end(), word->second.begin(), word->second.end());
        } else {
          auto cached = predicted.find(key);
          if (cached == predicted.end()) {
            std::vector<int32_t> ids;
            const auto phones = PredictEnglishPhones(key, british);
            if (phones.empty()) ids = lexicon.at("word");
            else for (const auto &phone : phones) ids.push_back(symbols.at(phone));
            if (predicted.size() >= 512) predicted.erase(predicted.begin());
            cached = predicted.emplace(key, std::move(ids)).first;
          }
          phrase.tokens.insert(phrase.tokens.end(), cached->second.begin(), cached->second.end());
        }
        phrase.word_tokens.push_back(static_cast<jint>(phrase.tokens.size()));
      } else {
        char c = text[i++];
        if (std::string(" ,.:;!?").find(c) == std::string::npos)
          throw kokoro_runtime::InputFailure("FRONTEND_INVALID_SEPARATOR", bytes, phrase.tokens.size());
        // Sherpa's English frontend normalizes a colon to a comma.
        phrase.tokens.push_back(symbols.at(std::string(1, c == ':' ? ',' : c)));
      }
    }
    if (phrase.word_tokens.empty())
      throw kokoro_runtime::InputFailure("FRONTEND_NO_WORDS", bytes, phrase.tokens.size());
    if (phrase.tokens.size() - 1 >= static_cast<size_t>(max_tokens))
      throw kokoro_runtime::InputFailure("FRONTEND_TOKEN_LIMIT", bytes, phrase.tokens.size() + 1, max_tokens + 1);
    phrase.tokens.push_back(0);  // End PAD.
    return phrase;
  }

  int max_tokens = 510;
  bool british;
  std::unordered_map<std::string, int32_t> symbols;
  std::unordered_map<std::string, std::vector<int32_t>> lexicon;
  mutable std::unordered_map<std::string, std::vector<int32_t>> predicted;
};

constexpr int64_t kCacheBudgetBytes = kokoro_runtime::kShapeCacheSingleBudgetBytes;

int64_t FileBytes(const std::string &path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  const auto size = input ? static_cast<int64_t>(input.tellg()) : 0;
  return std::max<int64_t>(0, size);
}

int64_t AvailableMemoryBytes() {
  std::ifstream input("/proc/meminfo");
  std::string key, unit;
  int64_t kb = 0;
  // /proc contains no phrase data; unavailable readings conservatively disable
  // the additional cached shape. Do not retain the contents in diagnostics.
  for (int i = 0; i < 128 && input >> key >> kb >> unit; ++i)
    if (key == "MemAvailable:" && unit == "kB" && kb > 0 && kb < INT64_MAX / 1024) return kb * 1024;
  return 0;
}

int64_t ResidentBytes() {
  std::ifstream input("/proc/self/statm");
  int64_t pages = 0, resident = 0;
  const auto page_size = sysconf(_SC_PAGESIZE);
  return input >> pages >> resident && resident > 0 && page_size > 0 && resident < INT64_MAX / page_size ?
      resident * page_size : 0;
}

struct ModelSession {
  std::unique_ptr<sherpa_onnx::OfflineTtsKokoroModel> model;
  int64_t prepare_ms = 0, profile_id = 0;
  bool reusable = true;
  kokoro_runtime::Qualification qualification;
  kokoro_runtime::SelectedDevice selected_device;
  std::string discovery;
  std::string context_identity;
  std::unique_ptr<kokoro_runtime::CompiledContextCache> context_cache;
};

struct Model : Frontend {
  Model(const sherpa_onnx::OfflineTtsModelConfig &config, const std::string &library_dir,
      const std::string &profile_prefix, const std::string &context_directory,
      const std::string &context_identity, const std::string &htp_performance_mode,
      const char **opening_stage = nullptr) : Frontend(config.kokoro.tokens, config.kokoro.lexicon, opening_stage),
      config(config), library_dir(library_dir), profile_prefix(profile_prefix), context_directory(context_directory),
      context_identity(context_identity), htp_performance_mode(htp_performance_mode) {
    if (opening_stage) *opening_stage = "preparing";
    // Include model transformation/workspace headroom and per-session voices.
    // This estimate is disclosed, supplemented by observed RSS growth, and is
    // never described as an allocator-enforced RAM bound.
    const auto model_bytes = FileBytes(config.kokoro.model);
    const auto voice_bytes = FileBytes(config.kokoro.voices);
    estimated_session_bytes = model_bytes > 0 && model_bytes < kCacheBudgetBytes && voice_bytes < kCacheBudgetBytes ?
        2 * model_bytes + voice_bytes + 64LL * 1024 * 1024 : kCacheBudgetBytes;
    if (config.provider == "cpu") EnsureSession(0);
  }

  std::string ContextIdentity(int64_t token_count) const {
    if (config.provider != "qnn-htp" || context_directory.empty() || !kokoro_runtime::IsSha256(context_identity)) return {};
    // Discover before loading a context, and bind the actual device rather than
    // trusting a phone label. Every binary affecting the compiled graph is
    // content-bound as well as the Java runtime/device/options identity.
    kokoro_runtime::SelectDevices(*kokoro_runtime::Environment(), config.provider, library_dir);
    const auto device = kokoro_runtime::LastSelectedDevice();
    try {
      if (context_seed.empty()) {
        auto seed = context_identity + "|" + Ort::GetVersionString() + "|" +
            kokoro_runtime::DigestFile(config.kokoro.model, 512LL * 1024 * 1024);
        for (const auto *name : {"libtimed_kokoro.so", "libkokoro_qnn_adapter.so", "libonnxruntime_providers_qnn.so", "libQnnHtp.so"})
          seed += "|" + kokoro_runtime::DigestFile(library_dir + "/" + name, 512LL * 1024 * 1024);
        std::vector<std::string> helpers;
        for (const auto &entry : std::filesystem::directory_iterator(library_dir)) {
          const auto name = entry.path().filename().string();
          if ((name.rfind("libQnnHtp", 0) == 0 && name != "libQnnHtp.so") || name == "libQnnSystem.so" || name == "libHtpPrepare.so")
            helpers.push_back(name);
          if (helpers.size() > 32) throw std::runtime_error("Speech runtime identity bounds");
        }
        std::sort(helpers.begin(), helpers.end());
        for (const auto &name : helpers) seed += "|" + name + "=" +
            kokoro_runtime::DigestFile(library_dir + "/" + name, 512LL * 1024 * 1024);
#if defined(__ANDROID__)
        // Vendor/DSP firmware can change separately from the system image.
        // Hash detected versions; never expose these strings in diagnostics.
        for (const auto *property : {"ro.vendor.build.fingerprint", "ro.bootimage.build.fingerprint"}) {
          char value[PROP_VALUE_MAX]{};
          const auto size = __system_property_get(property, value);
          if (size <= 0) { context_status = kokoro_runtime::ContextCacheStatus::Unavailable; return {}; }
          seed += "|" + std::string(value, size);
        }
#endif
        context_seed = kokoro_runtime::Digest(seed);
      }
      return kokoro_runtime::Digest(context_seed + "|" + kokoro_runtime::kContextExportRecipe + "|qnn-htp|" + std::to_string(config.num_threads) + "|" +
          htp_performance_mode + "|" + std::to_string(token_count) + "|" + std::to_string(device.type) + "|" +
          std::to_string(device.vendor) + "|" + std::to_string(device.id));
    } catch (const std::exception &) {
      context_status = kokoro_runtime::ContextCacheStatus::Unavailable;
      return {};  // Cache identity I/O cannot prevent the original model run.
    }
  }

  void ObserveContext(const ModelSession &session) const {
    if (!session.context_cache) return;
    context_status = session.context_cache->status;
    context_bytes = session.context_cache->bytes;
    context_evictions = session.context_cache->evictions;
  }

  void RejectContext(ModelSession &session, bool invalid_output) const noexcept {
    try {
      if (session.context_cache) {
        if (invalid_output) session.context_cache->Invalidate();
        ObserveContext(session);
        session.context_cache.reset();
      } else if (invalid_output && !session.context_identity.empty()) {
        kokoro_runtime::CompiledContextCache cache(context_directory, session.context_identity);
        cache.Invalidate();
        context_status = cache.status;
        context_evictions += cache.evictions;
      }
    } catch (...) {}  // Preserve the original inference/cancellation failure.
  }

  void EnsureSession(int64_t token_count) const {
    const auto key = config.provider == "cpu" ? 0 : token_count;
    current_prepare_ms = 0;
    graph_prepared_this_call = false;
    cache_hit = false;
    preparation_source = kokoro_runtime::GraphPreparation::None;
    context_status = config.provider == "qnn-htp" && !context_directory.empty() ?
        kokoro_runtime::ContextCacheStatus::NotAccessed : kokoro_runtime::ContextCacheStatus::Disabled;
    context_load_ms = context_store_ms = context_bytes = context_evictions = 0;
    context_fallback = false;
    active = sessions.Find(key);
    if (active && !active->reusable) {
      // Keep a failed attempt alive until its evidence has been copied. On its
      // next use, destruction ends/removes any unfinished ORT profile before a
      // new session starts. Other successfully qualified shapes remain cached.
      active = nullptr;
      sessions.Erase(key);
    }
    // Promote the requested shape before reducing retention so memory pressure
    // cannot evict the model we are about to use. A hit needs no new allocation.
    ConfigureCache(sessions.Bytes(), active ? 0 : estimated_session_bytes);
    if (active) {
      cache_hit = true;
      preparation_source = kokoro_runtime::GraphPreparation::Resident;
      RestoreEvidence();
      return;
    }
    SetStage("preparing", kokoro_runtime::SpeechPhase::Preparing);
    // Include failed or cancelled construction in this attempt's preparation
    // cost. ORT session construction is not interruptible by RunOptions.
    kokoro_runtime::ElapsedMilliseconds preparation_timer(current_prepare_ms);
    sessions.Reserve(estimated_session_bytes);
    qualification = {};
    selected_device = {};
    kokoro_runtime::LastQualification() = {};
    kokoro_runtime::LastSelectedDevice() = {};
    kokoro_runtime::LastDiscovery() = "stage=preparing";
    auto session = std::make_unique<ModelSession>();
    static std::atomic<int64_t> next_profile_id{1};
    session->profile_id = next_profile_id.fetch_add(1);
    const auto resident_before = ResidentBytes();
    const auto started = kokoro_runtime::SteadyMillis();
    const auto profile = profile_prefix + "-" + std::to_string(token_count) + "-" + std::to_string(session->profile_id);
    const auto create = [&](const std::string &model_path, const std::string &export_path, const std::string &attempt) {
      auto selected_config = config;
      selected_config.kokoro.model = model_path;
      if (!export_path.empty()) {
        kokoro_runtime::CompileEmbeddedContext(*kokoro_runtime::Environment(), config.num_threads, model_path,
            config.provider, library_dir, profile + "-compile-only", token_count, export_path, htp_performance_mode, cancelled);
        selected_config.kokoro.model = export_path;
      }
      Require(!cancelled.load(), "Kokoro generation cancelled");
      // A newly compiled context is loaded and executed before Commit just like
      // a later disk hit. A successful compile alone never publishes a cache.
      auto value = std::make_unique<sherpa_onnx::OfflineTtsKokoroModel>(selected_config, library_dir,
          profile + attempt, token_count, "", htp_performance_mode);
      Require(value->GetMetaData().sample_rate == kSampleRate &&
              value->GetMetaData().max_token_len == max_tokens, "Unexpected Kokoro pack");
      return value;
    };
    session->context_identity = ContextIdentity(token_count);
    if (!session->context_identity.empty()) {
      session->context_cache = std::make_unique<kokoro_runtime::CompiledContextCache>(context_directory, session->context_identity);
      std::string cached;
      {
        kokoro_runtime::ElapsedMilliseconds timer(context_load_ms);
        cached = session->context_cache->Lookup();
        context_fallback = session->context_cache->status == kokoro_runtime::ContextCacheStatus::Invalidated;
        if (!cached.empty()) {
          try {
            session->model = create(cached, "", "-context");
            preparation_source = kokoro_runtime::GraphPreparation::DiskContext;
          } catch (const std::bad_alloc &) { throw; }
          catch (const std::exception &) {
            Require(!cancelled.load(), "Kokoro generation cancelled");
            session->context_cache->Invalidate();
            context_fallback = true;
          }
        }
      }
      ObserveContext(*session);
    }
    if (!session->model) {
      const auto export_path = session->context_cache ?
          session->context_cache->BeginExport(kokoro_runtime::ContextSourceSnapshot::kMaximumBytes) : std::string{};
      preparation_source = config.provider == "cpu" ? kokoro_runtime::GraphPreparation::CpuDynamic : kokoro_runtime::GraphPreparation::Compiled;
      try {
        session->model = create(config.kokoro.model, export_path, "-compile");
      } catch (const std::bad_alloc &) { throw; }
      catch (const std::exception &) {
        Require(!cancelled.load(), "Kokoro generation cancelled");
        if (export_path.empty()) throw;
        // Unsupported export/cache I/O is optional. Retry the original graph
        // exactly once without EPContext configuration; retain normal errors.
        session->context_cache->Discard();
        session->context_cache->status = kokoro_runtime::ContextCacheStatus::WriteFailed;
        ObserveContext(*session);
        session->context_cache.reset();
        context_fallback = true;
        session->model = create(config.kokoro.model, "", "-uncached");
      }
      ObserveContext(*session);
    }
    session->selected_device = config.provider == "cpu" ? kokoro_runtime::SelectedDevice{} : kokoro_runtime::LastSelectedDevice();
    session->discovery = kokoro_runtime::LastDiscovery();
    session->prepare_ms = kokoro_runtime::SteadyMillis() - started;
    const auto resident_after = ResidentBytes();
    const auto resident_estimate = std::max(estimated_session_bytes,
        resident_before > 0 && resident_after > resident_before ? resident_after - resident_before : int64_t{0});
    ConfigureCache(sessions.Bytes() + resident_estimate, 0);
    active = sessions.Insert(key, resident_estimate, std::move(session));
    current_prepare_ms = active->prepare_ms;
    graph_prepared_this_call = true;
    RestoreEvidence();
  }

  void ConfigureCache(int64_t retained, int64_t prospective) const {
    available_memory_bytes = config.provider == "cpu" ? 0 : AvailableMemoryBytes();
    const auto allowance = kokoro_runtime::AdmitShapeCache(config.provider != "cpu",
        available_memory_bytes, retained, prospective);
    cache_admission = allowance.reason;
    sessions.Configure(allowance);
  }

  void SetStage(const char *name, kokoro_runtime::SpeechPhase phase) const {
    stage = name;
    progress.Transition(phase, kokoro_runtime::SteadyMillis());
  }

  void RestoreEvidence() const {
    qualification = active->qualification;
    selected_device = active->selected_device;
    prepare_ms = active->prepare_ms;
    discovery = active->discovery;
  }

  std::string DiscoveryEvidence() const {
    return KokoroObservedOperatorSummary(discovery, qualification.cpu, qualification.accelerator, profile_reused);
  }

  jobject Generate(JNIEnv *env, const std::string &text, jint sid, jfloat speed) const {
    progress.Begin(kokoro_runtime::SteadyMillis());
    ModelSession *attempt_session = nullptr;
    bool completed = false;
    const auto finish = [&](const Model *) {
      // A previously qualified profile remains valid. An interrupted first
      // attempt must retire its unfinished profile before the shape is reused.
      if (!completed && attempt_session && config.provider != "cpu") {
        const bool output_failed = !cancelled.load() &&
            (std::strcmp(stage, "synthesis") == 0 || std::strcmp(stage, "validating") == 0);
        if (!profile_reused || output_failed) attempt_session->reusable = false;
        RejectContext(*attempt_session, output_failed);
      }
      progress.Finish(kokoro_runtime::SteadyMillis());
    };
    std::unique_ptr<const Model, decltype(finish)> attempt(this, finish);
    SetStage("frontend", kokoro_runtime::SpeechPhase::Frontend);
    phrase_tokens = 0;
    input_bytes = std::min<size_t>(text.size(), INT32_MAX);
    synthesis_ms = 0;
    frontend_ms = 0;
    validation_ms = 0;
    profile_finalize_ms = 0;
    cache_evictions_before = sessions.Evictions();
    audio_measurement = {};
    output_validation = {};
    qualification = {};
    active = nullptr;
    prepare_ms = 0;
    selected_device = {};
    available_memory_bytes = 0;
    cache_admission = kokoro_runtime::ShapeCacheAdmission::MemoryUnknown;
    cache_hit = false;
    profile_reused = false;
    current_prepare_ms = 0;
    graph_prepared_this_call = false;
    preparation_source = kokoro_runtime::GraphPreparation::None;
    context_status = kokoro_runtime::ContextCacheStatus::NotAccessed;
    context_load_ms = context_store_ms = context_bytes = context_evictions = 0;
    context_fallback = false;
    Require(!cancelled.load(), "Kokoro generation cancelled");
    if (!std::isfinite(speed) || speed < .5f || speed > 2.f)
      throw kokoro_runtime::InputFailure("FRONTEND_INVALID_SPEED", input_bytes);
    Phrase phrase;
    {
      kokoro_runtime::ElapsedMilliseconds timer(frontend_ms);
      phrase = Encode(text);
    }
    phrase_tokens = phrase.tokens.size();
    if (sid < 0) throw kokoro_runtime::InputFailure("FRONTEND_INVALID_VOICE", input_bytes, phrase_tokens);
    Require(!cancelled.load(), "Kokoro generation cancelled");
    EnsureSession(phrase.tokens.size());
    attempt_session = active;
    profile_reused = qualification.accelerator.kernels > 0;
    Require(!cancelled.load(), "Kokoro generation cancelled");
    SetStage("frontend", kokoro_runtime::SpeechPhase::Frontend);
    if (sid >= active->model->GetMetaData().num_speakers)
      throw kokoro_runtime::InputFailure("FRONTEND_INVALID_VOICE", input_bytes, phrase_tokens);
    auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    std::array<int64_t, 2> shape{1, static_cast<int64_t>(phrase.tokens.size())};
    auto input = Ort::Value::CreateTensor<int64_t>(memory, phrase.tokens.data(), phrase.tokens.size(),
                                                    shape.data(), shape.size());
    SetStage("synthesis", kokoro_runtime::SpeechPhase::Synthesis);
    profile_reused = qualification.accelerator.kernels > 0;
    // A cached shape can exist before its first inference (for example after an
    // invalid voice). A run that throws before profiling must not borrow the
    // thread's completed profile from another shape or model.
    if (!profile_reused) kokoro_runtime::LastQualification() = {};
    kokoro_runtime::LastProfileFinalizeMs() = 0;
    const auto started = std::chrono::steady_clock::now();
    const auto resident_before_run = ResidentBytes();
    std::vector<Ort::Value> out;
    try {
      out = active->model->Run(std::move(input), sid, speed, run_options);
    } catch (...) {
      profile_finalize_ms = kokoro_runtime::LastProfileFinalizeMs();
      synthesis_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
      // CPU-only qualification failures still have a useful actual profile.
      if (config.provider != "cpu" && !profile_reused) {
        qualification = kokoro_runtime::LastQualification();
        // ORT retains events when Run throws before EndProfiling. A later run
        // must not label those interrupted kernels as its first-inference
        // profile. Preserve this failure's session identity until next use.
        active->reusable = false;
      }
      throw;
    }
    profile_finalize_ms = kokoro_runtime::LastProfileFinalizeMs();
    synthesis_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
    if (config.provider != "cpu" && qualification.accelerator.kernels == 0) {
      qualification = kokoro_runtime::LastQualification();
      active->qualification = qualification;
    }
    const auto resident_after = ResidentBytes();
    // Only growth during this call belongs to the active estimate. Comparing
    // against its original construction baseline would charge another cached
    // shape to this one on every alternating hit and spuriously evict it.
    const auto active_bytes = kokoro_runtime::AddResidentGrowth(sessions.ActiveBytes(), resident_before_run, resident_after);
    ConfigureCache(sessions.Bytes() - sessions.ActiveBytes() + active_bytes, 0);
    sessions.UpdateActiveBytes(active_bytes);
    SetStage("validating", kokoro_runtime::SpeechPhase::Validating);
    jobject result = nullptr;
    {
    kokoro_runtime::ElapsedMilliseconds validation_timer(validation_ms);
    Require(!cancelled.load(), "Kokoro generation cancelled");
    using OutputReason = kokoro_runtime::OutputValidationReason;
    output_validation.output_count = out.size();
    kokoro_runtime::RequireOutput(out.size() == 2, OutputReason::OutputCount, output_validation);
    kokoro_runtime::RequireOutput(out[0].IsTensor(), OutputReason::AudioTensorType, output_validation);
    kokoro_runtime::RequireOutput(out[1].IsTensor(), OutputReason::TimingTensorType, output_validation);
    // If ORT itself rejects metadata, retain an allowlisted reason rather than
    // misreporting untouched zero-valued audio measurements as observed silence.
    output_validation.reason = OutputReason::TensorMetadataUnavailable;
    auto audio = out[0].GetTensorTypeAndShapeInfo();
    auto timing = out[1].GetTensorTypeAndShapeInfo();
    size_t frames = audio.GetElementCount();
    output_validation.audio_frames = frames <= INT64_MAX ? static_cast<int64_t>(frames) : INT64_MAX;
    output_validation.audio_type = audio.GetElementType();
    output_validation.audio_rank = audio.GetDimensionsCount();
    const auto timing_count = timing.GetElementCount();
    output_validation.timing_count = timing_count <= INT64_MAX ? static_cast<int64_t>(timing_count) : INT64_MAX;
    output_validation.timing_type = timing.GetElementType();
    output_validation.timing_rank = timing.GetDimensionsCount();
    output_validation.reason = OutputReason::None;
    if (timing.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 &&
        timing_count == phrase.tokens.size() && timing_count <= 511) {
      kokoro_runtime::ObserveDurations(out[1].GetTensorData<int64_t>(), timing_count, output_validation);
    }
    kokoro_runtime::ValidateOutputStructure(output_validation, phrase.tokens.size(), kSampleRate, kSamplesPerUnit,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    const auto durations = out[1].GetTensorData<int64_t>();
    const auto *pcm = out[0].GetTensorData<float>();
    audio_measurement = kokoro_runtime::MeasureAudio(pcm, frames);
    kokoro_runtime::ValidateMeasuredAudio(audio_measurement, output_validation);

    auto cls = env->FindClass("dev/melo/kokoro/TimedKokoro$Audio");
    if (!cls) return nullptr;
    auto constructor = env->GetMethodID(cls, "<init>", "([F[J[I)V");
    if (!constructor) return nullptr;
    auto samples = env->NewFloatArray(frames);
    if (!samples) return nullptr;
    auto times = env->NewLongArray(phrase.tokens.size());
    if (!times) return nullptr;
    auto words = env->NewIntArray(phrase.word_tokens.size());
    if (!words) return nullptr;
    env->SetFloatArrayRegion(samples, 0, frames, out[0].GetTensorData<float>());
    std::vector<jlong> java_times(durations, durations + phrase.tokens.size());
    env->SetLongArrayRegion(times, 0, java_times.size(), java_times.data());
    env->SetIntArrayRegion(words, 0, phrase.word_tokens.size(), phrase.word_tokens.data());
    result = env->NewObject(cls, constructor, samples, times, words);
    }
    Require(!cancelled.load(), "Kokoro generation cancelled");
    if (result && !env->ExceptionCheck()) {
      kokoro_runtime::FinalizeSpeechGeneration(cancellation_lock, cancelled, [&] {
        if (active->context_cache) {
          // A compiled file is not an execution receipt. Publish only after this
          // exact session has produced structurally valid PCM and real EP events.
          if (active->context_cache->status == kokoro_runtime::ContextCacheStatus::Pending) {
            kokoro_runtime::ElapsedMilliseconds timer(context_store_ms);
            active->context_cache->Commit();
          }
          ObserveContext(*active);
          active->context_cache.reset();
        }
      }, [&] {
        SetStage("ready", kokoro_runtime::SpeechPhase::Ready);
        completed = true;
      });
    }
    return result;
  }

  void Cancel() {
    kokoro_runtime::CancelSpeechGeneration(cancellation_lock, cancelled, [&] { run_options.SetTerminate(); });
  }

  // Called by the serial synthesis owner once at the start of a new reply.
  void ResetCancellation() {
    kokoro_runtime::ResetSpeechGeneration(cancellation_lock, cancelled, [&] { run_options.UnsetTerminate(); });
  }

  sherpa_onnx::OfflineTtsModelConfig config;
  std::string library_dir;
  std::string profile_prefix;
  std::string context_directory, context_identity, htp_performance_mode;
  mutable std::string context_seed;
  mutable kokoro_runtime::GraphPreparation preparation_source = kokoro_runtime::GraphPreparation::None;
  mutable kokoro_runtime::ContextCacheStatus context_status = kokoro_runtime::ContextCacheStatus::Disabled;
  mutable int64_t context_load_ms = 0, context_store_ms = 0, context_bytes = 0, context_evictions = 0;
  mutable bool context_fallback = false;
  mutable kokoro_runtime::ShapeCache<ModelSession> sessions{1, kCacheBudgetBytes};
  mutable ModelSession *active = nullptr;
  int64_t estimated_session_bytes = kCacheBudgetBytes;
  mutable int64_t phrase_tokens = 0;
  mutable int64_t input_bytes = 0;
  mutable int64_t prepare_ms = 0;
  mutable int64_t current_prepare_ms = 0;
  mutable int64_t synthesis_ms = 0;
  mutable int64_t frontend_ms = 0, validation_ms = 0, profile_finalize_ms = 0, cache_evictions_before = 0;
  mutable int64_t available_memory_bytes = 0;
  mutable kokoro_runtime::ShapeCacheAdmission cache_admission = kokoro_runtime::ShapeCacheAdmission::MemoryUnknown;
  mutable kokoro_runtime::SpeechProgress progress;
  mutable bool cache_hit = false, profile_reused = false, graph_prepared_this_call = false;
  mutable kokoro_runtime::AudioMeasurement audio_measurement;
  mutable kokoro_runtime::OutputValidationObservation output_validation;
  mutable std::string discovery;
  mutable kokoro_runtime::Qualification qualification;
  mutable kokoro_runtime::SelectedDevice selected_device;
  mutable const char *stage = "frontend";
  Ort::RunOptions run_options;
  mutable std::mutex cancellation_lock;
  std::atomic<bool> cancelled{false};
};

// No exception or phrase text crosses the JNI boundary in a diagnostic.
void ReportError(JNIEnv *env, const char *kind, const char *message) {
  if (env->ExceptionCheck()) return;
  if (auto cls = env->FindClass(kind)) env->ThrowNew(cls, message);
}

jlongArray ModelEvidence(JNIEnv *env, const Model *model) {
  // Profile metadata is tied to the compiled session; times/audio refer only to
  // the latest attempt. The snapshot is also used inside an inference failure.
  jlong rms_bits = 0, peak_bits = 0;
  static_assert(sizeof(double) == sizeof(jlong));
  std::memcpy(&rms_bits, &model->audio_measurement.rms, sizeof(rms_bits));
  std::memcpy(&peak_bits, &model->audio_measurement.peak, sizeof(peak_bits));
  const bool profiled = model->qualification.accelerator.kernels > 0 || model->qualification.cpu.kernels > 0;
  const auto &validation = model->output_validation;
  const std::array<jlong, 56> values{6, model->qualification.accelerator.kernels,
      model->qualification.cpu.kernels, model->qualification.accelerator.microseconds,
      model->qualification.cpu.microseconds, model->prepare_ms, model->synthesis_ms,
      model->phrase_tokens, model->selected_device.type, model->selected_device.vendor,
      model->selected_device.id, std::strcmp(model->stage, "ready") == 0 ? 1 : 0,
      profiled ? 1 : 0, profiled && model->active ? model->active->profile_id : 0,
      model->cache_hit ? 1 : 0, model->current_prepare_ms,
      static_cast<jlong>(model->sessions.Size()), static_cast<jlong>(model->sessions.Limit()), model->sessions.Bytes(),
      rms_bits, peak_bits, model->audio_measurement.clipped_samples, model->audio_measurement.non_silent_samples,
      model->profile_reused ? 1 : 0, model->graph_prepared_this_call ? 1 : 0,
      model->frontend_ms, model->validation_ms, model->sessions.Evictions() - model->cache_evictions_before,
      model->profile_finalize_ms, model->sessions.Budget(), model->active ? model->sessions.ActiveBytes() : -1,
      model->available_memory_bytes, static_cast<jlong>(model->cache_admission),
      static_cast<jlong>(validation.reason), validation.audio_measured ? 1 : 0,
      validation.output_count, validation.audio_frames, validation.audio_type, validation.audio_rank,
      validation.timing_count, validation.timing_type, validation.timing_rank,
      validation.duration_stats_available ? 1 : 0, validation.duration_sum,
      validation.duration_min, validation.duration_max, validation.duration_sum_overflow ? 1 : 0,
      validation.audio_measured && model->audio_measurement.finite ? 1 : 0,
      static_cast<jlong>(model->preparation_source), static_cast<jlong>(model->context_status),
      model->context_load_ms, model->context_store_ms, model->context_bytes, model->context_evictions,
      model->context_fallback ? 1 : 0,
      model->config.provider != "qnn-htp" ? -1 : model->htp_performance_mode == "burst" ? 1 :
      model->htp_performance_mode == "high_performance" ? 2 : 0};
  auto result = env->NewLongArray(values.size());
  if (result) env->SetLongArrayRegion(result, 0, values.size(), values.data());
  return result;
}

void ReportFailure(JNIEnv *env, const char *stage_name, const char *code, int native_code,
    int token_count, const char *provider_name, int bytes, int token_limit, const char *discovery,
    const Model *current_attempt = nullptr) {
  if (env->ExceptionCheck()) return;
  const auto cls = env->FindClass("dev/melo/kokoro/TimedKokoro$Failure");
  if (!cls) return;
  const auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;IIILjava/lang/String;[J)V");
  if (!constructor) return;
  auto stage = env->NewStringUTF(stage_name);
  auto reason = env->NewStringUTF(code);
  auto provider = env->NewStringUTF(provider_name);
  auto device_info = env->NewStringUTF(discovery);
  if (!stage || !reason || !provider || !device_info) return;
  const auto evidence = current_attempt ? ModelEvidence(env, current_attempt) : nullptr;
  if (env->ExceptionCheck()) return;
  auto failure = static_cast<jthrowable>(env->NewObject(cls, constructor, stage, reason,
      static_cast<jint>(native_code), static_cast<jint>(token_count), provider,
      static_cast<jint>(bytes), static_cast<jint>(kokoro_runtime::kInputByteLimit), static_cast<jint>(token_limit), device_info, evidence));
  if (failure) env->Throw(failure);
}

void ReportModelFailure(JNIEnv *env, const Model *model, const std::exception &error, int native_code) {
  if (const auto *input = dynamic_cast<const kokoro_runtime::InputFailure *>(&error)) {
    ReportFailure(env, "frontend", input->code, native_code, input->tokens, model->config.provider.c_str(),
        input->bytes, input->token_limit, "stage=frontend");
    return;
  }
  const std::string_view detail(error.what()), stage(model->stage);
  const char *code = "EXECUTION_FAILED";
  if (detail.find("fused together") != std::string::npos || detail.find("partition") != std::string::npos)
    code = "GRAPH_PARTITION";
  else if (detail.find("CPU only") != std::string::npos) code = "CPU_ONLY";
  else if (detail.find("No Qualcomm speech device") != std::string::npos) code = "DEVICE_UNAVAILABLE";
  else if (detail.find("could not load") != std::string::npos || detail.find("register") != std::string::npos ||
      detail.find("PROVIDER_LOAD") != std::string::npos) code = "PROVIDER_LOAD";
  else if (stage == "validating") code = "OUTPUT_INVALID";
  else if (stage == "preparing") code = "GRAPH_PREPARATION";
  else if (stage == "frontend") code = "FRONTEND_INPUT_INVALID";
  const bool attempted_model = stage == "preparing" || stage == "synthesis" || stage == "validating";
  std::string enriched;
  const char *discovery = stage == "preparing" ? kokoro_runtime::LastDiscovery().c_str() :
      stage == "frontend" ? "stage=frontend" : KokoroBestEffortEvidenceSummary(model->discovery, enriched,
          [&] { return model->DiscoveryEvidence(); }).c_str();
  ReportFailure(env, model->stage, code, native_code, model->phrase_tokens, model->config.provider.c_str(),
      model->input_bytes, model->max_tokens + 1, discovery,
      attempted_model ? model : nullptr);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_kokoro_TimedKokoro_open(
    JNIEnv *env, jclass, jstring model, jstring voices, jstring tokens, jstring lexicon, jint threads,
    jstring provider, jstring library_dir, jstring profile_prefix, jstring context_directory,
    jstring context_identity, jstring htp_performance_mode) {
  const char *opening_stage = "configuration";
  const char *requested_provider = "unverified";
  const auto report = [&](int native_code) {
    const bool frontend = std::strcmp(opening_stage, "frontend") == 0;
    const bool configuration = std::strcmp(opening_stage, "configuration") == 0;
    ReportFailure(env, frontend ? "frontend" : "preparing",
        frontend ? "FRONTEND_LOAD" : configuration ? "MODEL_CONFIGURATION" : "MODEL_OPEN",
        native_code, 0, requested_provider, 0, 511,
        frontend ? "stage=frontend_load" : configuration ? "stage=model_configuration" : "stage=model_open");
  };
  try {
    sherpa_onnx::OfflineTtsModelConfig config;
    config.provider = FromJava(env, provider);
    if (config.provider == "cpu") requested_provider = "cpu";
    else if (config.provider == "qnn-gpu") requested_provider = "qnn-gpu";
    else if (config.provider == "qnn-htp") requested_provider = "qnn-htp";
    Require(threads >= 1 && threads <= 4, "Invalid Kokoro thread count");
    Require(config.provider == "cpu" || config.provider == "qnn-gpu" || config.provider == "qnn-htp", "Invalid Kokoro provider");
    config.num_threads = threads;
    config.kokoro.model = FromJava(env, model);
    config.kokoro.voices = FromJava(env, voices);
    config.kokoro.tokens = FromJava(env, tokens);
    config.kokoro.lexicon = FromJava(env, lexicon);
    const auto mode = FromJava(env, htp_performance_mode);
    Require(kokoro_runtime::ValidHtpPerformanceMode(mode), "Invalid HTP performance mode");
    return reinterpret_cast<jlong>(new Model(config, FromJava(env, library_dir), FromJava(env, profile_prefix),
        FromJava(env, context_directory), FromJava(env, context_identity), mode, &opening_stage));
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro allocation failed");
  } catch (const Ort::Exception &error) {
    report(error.GetOrtErrorCode());
  } catch (const std::exception &) {
    report(-1);
  }
  return 0;
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_melo_kokoro_TimedKokoro_generatePhrase(
    JNIEnv *env, jclass, jlong handle, jstring phrase, jint speaker, jfloat speed) {
  try {
    return reinterpret_cast<Model *>(handle)->Generate(env, FromJava(env, phrase), speaker, speed);
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro allocation failed");
  } catch (const Ort::Exception &error) {
    if (reinterpret_cast<Model *>(handle)->cancelled.load())
      ReportError(env, "java/util/concurrent/CancellationException", "Kokoro generation cancelled");
    else ReportModelFailure(env, reinterpret_cast<Model *>(handle), error, error.GetOrtErrorCode());
  } catch (const std::exception &error) {
    if (reinterpret_cast<Model *>(handle)->cancelled.load())
      ReportError(env, "java/util/concurrent/CancellationException", "Kokoro generation cancelled");
    else ReportModelFailure(env, reinterpret_cast<Model *>(handle), error, -1);
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_TimedKokoro_cancelGeneration(
    JNIEnv *env, jclass, jlong handle) {
  try { reinterpret_cast<Model *>(handle)->Cancel(); }
  catch (const std::exception &) { ReportError(env, "java/lang/IllegalStateException", "Kokoro cancellation failed"); }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_TimedKokoro_resetGeneration(
    JNIEnv *env, jclass, jlong handle) {
  try { reinterpret_cast<Model *>(handle)->ResetCancellation(); }
  catch (const std::exception &) { ReportError(env, "java/lang/IllegalStateException", "Kokoro reset failed"); }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_TimedKokoro_release(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Model *>(handle);
}

extern "C" JNIEXPORT jint JNICALL Java_dev_melo_kokoro_TimedKokoro_executedAcceleratorNodes(
    JNIEnv *, jclass, jlong handle) {
  const auto *model = reinterpret_cast<Model *>(handle);
  return model->active ? model->active->model->ExecutedAcceleratorNodes() : 0;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_kokoro_TimedKokoro_executionEvidence(
    JNIEnv *env, jclass, jlong handle) {
  return ModelEvidence(env, reinterpret_cast<Model *>(handle));
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_kokoro_TimedKokoro_executionProgress(
    JNIEnv *env, jclass, jlong handle) {
  const auto *model = reinterpret_cast<Model *>(handle);
  kokoro_runtime::SpeechProgressSnapshot progress;
  if (!model->progress.Snapshot(kokoro_runtime::SteadyMillis(), progress)) return nullptr;
  const std::array<jlong, 8> values{1, progress.attempt, progress.phase, progress.sequence,
      progress.phase_elapsed_ms, progress.attempt_elapsed_ms, model->cancelled.load() ? 1 : 0,
      progress.active ? 1 : 0};
  auto result = env->NewLongArray(values.size());
  if (result) env->SetLongArrayRegion(result, 0, values.size(), values.data());
  return result;
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_kokoro_TimedKokoro_executionDiscovery(
    JNIEnv *env, jclass, jlong handle) {
  try {
    const auto summary = reinterpret_cast<Model *>(handle)->DiscoveryEvidence();
    return env->NewStringUTF(summary.c_str());
  } catch (const std::exception &) {
    ReportError(env, "java/lang/IllegalStateException", "Speech discovery evidence unavailable");
    return nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_kokoro_KokoroFrontend_open(
    JNIEnv *env, jclass, jstring tokens, jstring lexicon) {
  try {
    return reinterpret_cast<jlong>(new Frontend(FromJava(env, tokens), FromJava(env, lexicon)));
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro frontend allocation failed");
  } catch (const std::exception &) {
    ReportError(env, "java/lang/IllegalStateException", "Kokoro frontend unavailable");
  }
  return 0;
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_melo_kokoro_KokoroFrontend_encodePhrase(
    JNIEnv *env, jclass, jlong handle, jstring text) {
  try {
    const auto phrase = reinterpret_cast<Frontend *>(handle)->Encode(FromJava(env, text));
    const auto cls = env->FindClass("dev/melo/kokoro/KokoroFrontend$EncodedPhrase");
    if (!cls) return nullptr;
    const auto constructor = env->GetMethodID(cls, "<init>", "([J[I)V");
    if (!constructor) return nullptr;
    const auto tokens = env->NewLongArray(phrase.tokens.size());
    if (!tokens) return nullptr;
    const auto words = env->NewIntArray(phrase.word_tokens.size());
    if (!words) return nullptr;
    const std::vector<jlong> java_tokens(phrase.tokens.begin(), phrase.tokens.end());
    env->SetLongArrayRegion(tokens, 0, java_tokens.size(), java_tokens.data());
    env->SetIntArrayRegion(words, 0, phrase.word_tokens.size(), phrase.word_tokens.data());
    return env->NewObject(cls, constructor, tokens, words);
  } catch (const kokoro_runtime::InputFailure &error) {
    ReportFailure(env, "frontend", error.code, -1, error.tokens, "frontend", error.bytes, error.token_limit, "stage=frontend");
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro frontend allocation failed");
  } catch (const std::exception &) {
    ReportError(env, "java/lang/IllegalStateException", "Kokoro could not encode the phrase");
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_KokoroFrontend_release(
    JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Frontend *>(handle);
}
