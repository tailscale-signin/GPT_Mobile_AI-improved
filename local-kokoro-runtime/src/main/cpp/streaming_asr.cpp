#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "streaming_asr_provider.h"
#include "sherpa-onnx/csrc/online-recognizer.h"

namespace {
constexpr int kSampleRate = 16000;
constexpr int64_t kMaximumCaptureFrames = 30LL * 60 * kSampleRate;

std::string String(JNIEnv *env, jstring value) {
  if (!value) throw std::runtime_error("ASR_INVALID_ARGUMENT");
  const auto *bytes = env->GetStringUTFChars(value, nullptr);
  if (!bytes) throw std::runtime_error("ASR_ALLOCATION_FAILED");
  std::string result(bytes);
  env->ReleaseStringUTFChars(value, bytes);
  return result;
}

// NewStringUTF expects modified UTF-8 and cannot represent arbitrary model
// output. Decode standard UTF-8 through Java, retaining supplementary text.
jstring JavaString(JNIEnv *env, const std::string &text) {
  auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
  if (!bytes) throw std::runtime_error("ASR_ALLOCATION_FAILED");
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
  auto cls = env->FindClass("java/lang/String");
  auto charset = env->NewStringUTF("UTF-8");
  auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
  auto result = static_cast<jstring>(env->NewObject(cls, constructor, bytes, charset));
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(charset);
  env->DeleteLocalRef(cls);
  return result;
}

void Failure(JNIEnv *env, const std::exception &error, const char *fallback) {
  if (env->ExceptionCheck()) return;
  std::string code(error.what());
  if (code == "PROVIDER_LOAD") code = "ASR_PROVIDER_LOAD";
  if (code == "No Qualcomm speech device" || code == "Qualcomm speech requires Android arm64")
    code = "ASR_ACCELERATOR_UNAVAILABLE";
  if (code.rfind("ASR_", 0) != 0 || code.size() > 80) code = fallback;
  auto cls = env->FindClass("dev/melo/kokoro/StreamingAsr$Failure");
  auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
  auto value = env->NewStringUTF(code.c_str());
  auto exception = static_cast<jthrowable>(env->NewObject(cls, constructor, value));
  env->Throw(exception);
  env->DeleteLocalRef(exception);
  env->DeleteLocalRef(value);
  env->DeleteLocalRef(cls);
}

void CheckFile(const std::string &path) {
  if (path.empty() || path.front() != '/' || path.find(',') != std::string::npos ||
      !std::filesystem::is_regular_file(path) || std::filesystem::file_size(path) == 0)
    throw std::runtime_error("ASR_MODEL_UNAVAILABLE");
}

struct Session {
  asr_runtime::Context context;
  std::unique_ptr<sherpa_onnx::OnlineRecognizer> recognizer;
  std::unique_ptr<sherpa_onnx::OnlineStream> stream;
  std::mutex mutex;
  int64_t accepted_frames = 0;
  int64_t completed_chunks = 0;
  bool finished = false;
  bool failed = false;
  int chunk_millis = 0;

  ~Session() {
    // ORT can write a profile when a never-executed warm session is destroyed.
    // Retire model sessions first, then remove only this handle's bounded
    // profile namespace; their existence is never treated as execution proof.
    stream.reset();
    recognizer.reset();
    if (context.provider == "qnn-gpu" && !context.profile_prefix.empty()) {
      try {
        const std::filesystem::path prefix(context.profile_prefix);
        const auto stem = prefix.filename().string() + "-";
        std::error_code error;
        std::filesystem::directory_iterator files(prefix.parent_path(), error), end;
        size_t visited = 0;
        for (; !error && files != end && visited++ < 1024; files.increment(error)) {
          const auto name = files->path().filename().string();
          if (name.rfind(stem, 0) == 0 && files->path().extension() == ".json") {
            std::error_code ignored;
            std::filesystem::remove(files->path(), ignored);
          }
        }
      } catch (...) {}  // Optional file cleanup cannot make model destruction throw.
    }
  }

  void Reset(const std::string &language) {
    context.cancelled.store(false);
    context.run_options.UnsetTerminate();
    asr_runtime::Scope scope(context);
    stream = recognizer->CreateStream();
    stream->SetOption("language", language.empty() ? "auto" : language);
    accepted_frames = 0;
    completed_chunks = 0;
    finished = false;
    failed = false;
    for (auto &component : context.components) component.completed_runs = 0;
  }

  void Decode() {
    asr_runtime::Scope scope(context);
    try {
      while (recognizer->IsReady(stream.get())) {
        asr_runtime::CheckCancellation();
        recognizer->DecodeStream(stream.get());
        asr_runtime::CheckCancellation();
        ++completed_chunks;
      }
    } catch (...) {
      failed = true;
      // ORT's termination status is intentionally normalized to cancellation,
      // rather than surfacing it as an unrelated model/inference error.
      asr_runtime::CheckCancellation();
      throw;
    }
  }
};

std::mutex registry_mutex;
std::unordered_map<int64_t, std::shared_ptr<Session>> registry;
std::atomic<int64_t> next_handle{1};

std::shared_ptr<Session> Lookup(jlong handle) {
  std::lock_guard<std::mutex> guard(registry_mutex);
  auto found = registry.find(handle);
  if (found == registry.end()) throw std::runtime_error("ASR_CLOSED");
  return found->second;
}

jobject Result(JNIEnv *env, Session &session) {
  auto result = session.recognizer->GetResult(session.stream.get());
  if (result.tokens.size() != result.timestamps.size() || result.tokens.size() > 100000)
    throw std::runtime_error("ASR_INVALID_TIMING");
  auto string_class = env->FindClass("java/lang/String");
  auto tokens = env->NewObjectArray(static_cast<jsize>(result.tokens.size()), string_class, nullptr);
  for (size_t i = 0; i != result.tokens.size(); ++i) {
    auto token = JavaString(env, result.tokens[i]);
    env->SetObjectArrayElement(tokens, static_cast<jsize>(i), token);
    env->DeleteLocalRef(token);
  }
  auto times = env->NewFloatArray(static_cast<jsize>(result.timestamps.size()));
  env->SetFloatArrayRegion(times, 0, static_cast<jsize>(result.timestamps.size()), result.timestamps.data());
  auto text = JavaString(env, result.text);
  auto cls = env->FindClass("dev/melo/kokoro/StreamingAsr$Result");
  auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[Ljava/lang/String;[FFIZJ)V");
  auto value = env->NewObject(cls, constructor, text, tokens, times, result.start_time,
      static_cast<jint>(result.segment), static_cast<jboolean>(session.recognizer->IsEndpoint(session.stream.get())),
      static_cast<jlong>(session.accepted_frames));
  for (auto local : {static_cast<jobject>(string_class), static_cast<jobject>(tokens), static_cast<jobject>(times),
      static_cast<jobject>(text), static_cast<jobject>(cls)}) env->DeleteLocalRef(local);
  return value;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeCreate(JNIEnv *env, jclass,
    jstring encoder, jstring decoder, jstring joiner, jstring tokens, jstring model_type,
    jstring provider, jstring language, jstring library_dir, jstring work_dir, jint threads, jint chunk_millis,
    jfloat endpoint_silence_seconds) {
  try {
    auto session = std::make_shared<Session>();
    auto &context = session->context;
    context.provider = String(env, provider);
    context.library_dir = String(env, library_dir);
    context.threads = threads;
    const auto kind = String(env, model_type);
    if (threads < 1 || threads > 8 || chunk_millis < 0 || chunk_millis > 1120 ||
        !std::isfinite(endpoint_silence_seconds) || endpoint_silence_seconds < .2f || endpoint_silence_seconds > 3.f ||
        (kind != "nemo_transducer" && kind != "zipformer" && kind != "zipformer2") ||
        (context.provider != "cpu" && context.provider != "qnn-gpu" && context.provider != "qnn-htp-context"))
      throw std::runtime_error("ASR_INVALID_ARGUMENT");
    if (context.provider != "cpu" && kind != "nemo_transducer")
      throw std::runtime_error("ASR_ACCELERATOR_UNAVAILABLE");
    session->chunk_millis = chunk_millis;
    const auto work = String(env, work_dir);
    if (work.empty() || work.front() != '/') throw std::runtime_error("ASR_INVALID_ARGUMENT");
    std::filesystem::create_directories(work);
    const auto handle = next_handle.fetch_add(1);
    context.profile_prefix = work + "/asr-" + std::to_string(handle);
    // These are the metadata of the catalog's pinned multilingual 560 ms
    // float export; other GPU graph shapes need their own verified pack.
    if (context.provider == "qnn-gpu") {
      if (chunk_millis != 560) throw std::runtime_error("ASR_MODEL_SHAPE");
      context.window_size = 65;
      context.cache_channel_time = 56;
      context.cache_time_width = 8;
    }
    sherpa_onnx::OnlineRecognizerConfig config;
    config.model_config.transducer.encoder = String(env, encoder);
    config.model_config.transducer.decoder = String(env, decoder);
    config.model_config.transducer.joiner = String(env, joiner);
    config.model_config.tokens = String(env, tokens);
    for (const auto &path : {config.model_config.transducer.encoder, config.model_config.transducer.decoder,
        config.model_config.transducer.joiner, config.model_config.tokens}) CheckFile(path);
    config.model_config.num_threads = threads;
    config.model_config.model_type = kind;
    config.model_config.provider_config.provider = "cpu";
    config.feat_config.sampling_rate = kSampleRate;
    config.enable_endpoint = true;
    config.endpoint_config.rule2.min_trailing_silence = endpoint_silence_seconds;
    config.endpoint_config.rule3.min_utterance_length = 30 * 60;
    config.reset_encoder = true;
    if (context.provider == "qnn-htp-context") {
#if defined(__ANDROID__) && defined(__aarch64__)
      config.model_config.provider_config.provider = "qnn";
      auto &qnn = config.model_config.transducer.qnn_config;
      qnn.backend_lib = context.library_dir + "/libQnnHtp.so";
      qnn.system_lib = context.library_dir + "/libQnnSystem.so";
      CheckFile(qnn.backend_lib);
      CheckFile(qnn.system_lib);
      qnn.context_binary = config.model_config.transducer.encoder + "," +
          config.model_config.transducer.decoder + "," + config.model_config.transducer.joiner;
      // Published context files are loaded directly. No recompilation, unsafe
      // cross-SoC cache reuse, or automatic CPU graph recovery is possible.
      context.discovery = "htp_contexts_requested";
#else
      throw std::runtime_error("ASR_ACCELERATOR_UNAVAILABLE");
#endif
    }
    asr_runtime::Scope scope(context);
    if (!config.Validate()) throw std::runtime_error("ASR_INVALID_MODEL_CONFIG");
    session->recognizer = std::make_unique<sherpa_onnx::OnlineRecognizer>(config);
    session->Reset(String(env, language));
    if (context.provider == "qnn-htp-context") context.discovery = "htp_contexts_loaded;cpu_fallback=0";
    if (context.provider == "cpu") context.discovery = "cpu;profile=not_requested";
    std::lock_guard<std::mutex> guard(registry_mutex);
    registry.emplace(handle, std::move(session));
    return handle;
  } catch (const std::exception &error) { Failure(env, error, "ASR_INITIALIZATION_FAILED"); return 0; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeAccept(JNIEnv *env, jclass, jlong handle, jfloatArray audio) {
  try {
    auto session = Lookup(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!audio || session->finished) throw std::runtime_error("ASR_INVALID_ARGUMENT");
    const auto count = env->GetArrayLength(audio);
    if (count < 0 || count > 2 * kSampleRate || session->accepted_frames + count > kMaximumCaptureFrames)
      throw std::runtime_error("ASR_CAPTURE_LIMIT");
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    if (!std::all_of(samples.begin(), samples.end(), [](float v) { return std::isfinite(v) && std::abs(v) <= 1.0f; }))
      throw std::runtime_error("ASR_INVALID_PCM");
    asr_runtime::Scope scope(session->context);
    asr_runtime::CheckCancellation();
    if (count != 0) session->stream->AcceptWaveform(kSampleRate, samples.data(), count);
    session->accepted_frames += count;
    session->Decode();
    return Result(env, *session);
  } catch (const std::exception &error) { Failure(env, error, "ASR_INFERENCE_FAILED"); return nullptr; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeFinish(JNIEnv *env, jclass, jlong handle) {
  try {
    auto session = Lookup(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    asr_runtime::Scope scope(session->context);
    asr_runtime::CheckCancellation();
    if (!session->finished) {
      if (session->accepted_frames > 0) {
        // Flush a remaining model window and frontend right context. Padding
        // is never counted as captured speech or used to manufacture word ends.
        std::vector<float> tail((session->chunk_millis + 300) * kSampleRate / 1000, 0.f);
        session->stream->AcceptWaveform(kSampleRate, tail.data(), static_cast<int32_t>(tail.size()));
      }
      session->stream->InputFinished();
      session->Decode();
      session->finished = true;
    }
    return Result(env, *session);
  } catch (const std::exception &error) { Failure(env, error, "ASR_INFERENCE_FAILED"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeReset(JNIEnv *env, jclass, jlong handle, jstring language) {
  try {
    auto session = Lookup(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    session->Reset(String(env, language));
  } catch (const std::exception &error) { Failure(env, error, "ASR_RESET_FAILED"); }
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeEvidence(JNIEnv *env, jclass, jlong handle) {
  try {
    auto session = Lookup(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    auto &context = session->context;
    bool executed = session->completed_chunks > 0 && session->accepted_frames > 0 &&
        !session->failed && !context.cancelled.load();
    std::string actual = executed ? context.provider : "not_executed";
    std::array<jlong, 3> accelerator{}, cpu{}, runs{};
    for (size_t i = 0; i < 3; ++i) {
      accelerator[i] = context.components[i].accelerator_kernels;
      cpu[i] = context.components[i].cpu_kernels;
      runs[i] = context.components[i].completed_runs;
    }
    if (context.provider == "qnn-gpu") {
      executed = executed && context.components[0].profile_completed && accelerator[0] > 0;
      actual = !executed ? "not_executed" : (cpu[0] + cpu[1] + cpu[2] > 0 ? "qnn-gpu+cpu" : "qnn-gpu");
    } else if (context.provider == "qnn-htp-context") {
      executed = executed && runs[0] > 0 && runs[1] > 0 && runs[2] > 0;
      actual = executed ? "qnn-htp-context" : "not_executed";
    }
    auto requested = JavaString(env, context.provider);
    auto observed = JavaString(env, actual);
    auto discovery = JavaString(env, context.discovery);
    auto gpu_array = env->NewLongArray(3), cpu_array = env->NewLongArray(3), runs_array = env->NewLongArray(3);
    env->SetLongArrayRegion(gpu_array, 0, 3, accelerator.data());
    env->SetLongArrayRegion(cpu_array, 0, 3, cpu.data());
    env->SetLongArrayRegion(runs_array, 0, 3, runs.data());
    auto cls = env->FindClass("dev/melo/kokoro/StreamingAsr$Evidence");
    auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;JJ[J[J[JZLjava/lang/String;)V");
    auto value = env->NewObject(cls, constructor, requested, observed, static_cast<jlong>(session->completed_chunks),
        static_cast<jlong>(session->accepted_frames), gpu_array, cpu_array, runs_array, static_cast<jboolean>(executed), discovery);
    for (auto local : {static_cast<jobject>(requested), static_cast<jobject>(observed), static_cast<jobject>(discovery),
        static_cast<jobject>(gpu_array), static_cast<jobject>(cpu_array), static_cast<jobject>(runs_array), static_cast<jobject>(cls)})
      env->DeleteLocalRef(local);
    return value;
  } catch (const std::exception &error) { Failure(env, error, "ASR_EVIDENCE_FAILED"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeCancel(JNIEnv *, jclass, jlong handle) {
  try {
    auto session = Lookup(handle);
    session->context.cancelled.store(true);
    session->context.run_options.SetTerminate();
  } catch (const std::exception &) {}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeDelete(JNIEnv *, jclass, jlong handle) {
  std::shared_ptr<Session> retired;
  {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto found = registry.find(handle);
    if (found == registry.end()) return;
    retired = std::move(found->second);
    registry.erase(found);
  }
  retired->context.cancelled.store(true);
  // The final in-flight JNI holder owns destruction if cancellation races a run.
}
