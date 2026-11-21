// Timed frontend for the pinned English Kokoro pack. Sherpa reads the dictionary,
// encodes its symbols and runs the original voice/style/speed model.
#include <jni.h>
#include <array>
#include <atomic>
#include <cmath>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "kokoro_pronunciation.h"
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
  Frontend(const std::string &tokens, const std::string &lexicon_path) :
      british(lexicon_path.substr(lexicon_path.find_last_of("/\\") + 1) == "lexicon-gb-en.txt") {
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
    Require(!text.empty() && text.size() <= 128, "Invalid Kokoro phrase length");
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
        Require(std::string(" ,.:;!?").find(c) != std::string::npos, "Invalid Kokoro separator");
        // Sherpa's English frontend normalizes a colon to a comma.
        phrase.tokens.push_back(symbols.at(std::string(1, c == ':' ? ',' : c)));
      }
    }
    Require(!phrase.word_tokens.empty() && phrase.tokens.size() - 1 <
            static_cast<size_t>(max_tokens), "Invalid Kokoro token count");
    phrase.tokens.push_back(0);  // End PAD.
    return phrase;
  }

  int max_tokens = 510;
  bool british;
  std::unordered_map<std::string, int32_t> symbols;
  std::unordered_map<std::string, std::vector<int32_t>> lexicon;
  mutable std::unordered_map<std::string, std::vector<int32_t>> predicted;
};

struct Model : Frontend {
  Model(const sherpa_onnx::OfflineTtsModelConfig &config, const std::string &library_dir,
      const std::string &profile_prefix) : Frontend(config.kokoro.tokens, config.kokoro.lexicon),
      config(config), library_dir(library_dir), profile_prefix(profile_prefix) {
    if (config.provider == "cpu") EnsureSession(0);
  }

  void EnsureSession(int64_t token_count) const {
    if (kokoro && (config.provider == "cpu" || specialized_tokens == token_count)) return;
    // Keep at most one specialized graph. These opt-in prototypes include graph
    // preparation in their measured latency instead of hiding it as a speedup.
    kokoro.reset();
    kokoro = std::make_unique<sherpa_onnx::OfflineTtsKokoroModel>(config, library_dir,
        profile_prefix + "-" + std::to_string(token_count), token_count);
    Require(kokoro->GetMetaData().sample_rate == kSampleRate &&
            kokoro->GetMetaData().max_token_len == max_tokens, "Unexpected Kokoro pack");
    specialized_tokens = token_count;
  }

  jobject Generate(JNIEnv *env, const std::string &text, jint sid, jfloat speed) const {
    Require(!cancelled.load(), "Kokoro generation cancelled");
    Phrase phrase = Encode(text);
    EnsureSession(phrase.tokens.size());
    Require(sid >= 0 && sid < kokoro->GetMetaData().num_speakers &&
            std::isfinite(speed) && speed >= .5f && speed <= 2.f, "Invalid Kokoro voice or speed");
    auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    std::array<int64_t, 2> shape{1, static_cast<int64_t>(phrase.tokens.size())};
    auto input = Ort::Value::CreateTensor<int64_t>(memory, phrase.tokens.data(), phrase.tokens.size(),
                                                    shape.data(), shape.size());
    auto out = kokoro->Run(std::move(input), sid, speed, run_options);
    Require(!cancelled.load(), "Kokoro generation cancelled");
    Require(out.size() == 2, "Kokoro timing output missing");
    auto audio = out[0].GetTensorTypeAndShapeInfo();
    auto timing = out[1].GetTensorTypeAndShapeInfo();
    size_t frames = audio.GetElementCount();
    Require(audio.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT &&
            frames > 0 && frames <= 30 * kSampleRate &&
            timing.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 &&
            timing.GetElementCount() == phrase.tokens.size(), "Invalid Kokoro output shape");
    auto durations = out[1].GetTensorData<int64_t>();
    int64_t units = 0;
    for (size_t i = 0; i < phrase.tokens.size(); ++i) {
      Require(durations[i] > 0 && durations[i] <= static_cast<int64_t>(frames) / kSamplesPerUnit - units,
              "Invalid Kokoro token duration");
      units += durations[i];
    }
    Require(units * kSamplesPerUnit == static_cast<int64_t>(frames), "Kokoro timing and PCM disagree");

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
    return env->NewObject(cls, constructor, samples, times, words);
  }

  void Cancel() {
    std::lock_guard<std::mutex> lock(cancellation_lock);
    cancelled.store(true);
    run_options.SetTerminate();
  }

  // Called by the serial synthesis owner once at the start of a new reply.
  void ResetCancellation() {
    std::lock_guard<std::mutex> lock(cancellation_lock);
    run_options.UnsetTerminate();
    cancelled.store(false);
  }

  sherpa_onnx::OfflineTtsModelConfig config;
  std::string library_dir;
  std::string profile_prefix;
  mutable std::unique_ptr<sherpa_onnx::OfflineTtsKokoroModel> kokoro;
  mutable int64_t specialized_tokens = 0;
  Ort::RunOptions run_options;
  std::mutex cancellation_lock;
  std::atomic<bool> cancelled{false};
};

// No exception or phrase text crosses the JNI boundary in a diagnostic.
void ReportError(JNIEnv *env, const char *kind, const char *message) {
  if (env->ExceptionCheck()) return;
  if (auto cls = env->FindClass(kind)) env->ThrowNew(cls, message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_kokoro_TimedKokoro_open(
    JNIEnv *env, jclass, jstring model, jstring voices, jstring tokens, jstring lexicon, jint threads,
    jstring provider, jstring library_dir, jstring profile_prefix) {
  try {
    sherpa_onnx::OfflineTtsModelConfig config;
    Require(threads >= 1 && threads <= 4, "Invalid Kokoro thread count");
    config.num_threads = threads;
    config.provider = FromJava(env, provider);
    config.kokoro.model = FromJava(env, model);
    config.kokoro.voices = FromJava(env, voices);
    config.kokoro.tokens = FromJava(env, tokens);
    config.kokoro.lexicon = FromJava(env, lexicon);
    return reinterpret_cast<jlong>(new Model(config, FromJava(env, library_dir), FromJava(env, profile_prefix)));
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro allocation failed");
  } catch (const std::exception &) {
    ReportError(env, "java/lang/IllegalStateException", "Kokoro model could not be opened");
  }
  return 0;
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_melo_kokoro_TimedKokoro_generatePhrase(
    JNIEnv *env, jclass, jlong handle, jstring phrase, jint speaker, jfloat speed) {
  try {
    return reinterpret_cast<Model *>(handle)->Generate(env, FromJava(env, phrase), speaker, speed);
  } catch (const std::bad_alloc &) {
    ReportError(env, "java/lang/OutOfMemoryError", "Kokoro allocation failed");
  } catch (const std::exception &) {
    if (reinterpret_cast<Model *>(handle)->cancelled.load())
      ReportError(env, "java/util/concurrent/CancellationException", "Kokoro generation cancelled");
    else ReportError(env, "java/lang/IllegalStateException", "Kokoro could not generate aligned phrase audio");
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
  const auto &model = reinterpret_cast<Model *>(handle)->kokoro;
  return model ? model->ExecutedAcceleratorNodes() : 0;
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
