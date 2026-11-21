// Timed frontend for the pinned English Kokoro pack. Sherpa reads the dictionary,
// encodes its symbols and runs the original voice/style/speed model.
#include <jni.h>
#include <array>
#include <cmath>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

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

struct Model {
  explicit Model(const sherpa_onnx::OfflineTtsModelConfig &config) : kokoro(config) {
    Require(kokoro.GetMetaData().sample_rate == kSampleRate, "Unexpected Kokoro sample rate");
    std::ifstream token_file(config.kokoro.tokens);
    std::ifstream dictionary(config.kokoro.lexicon);
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
        auto word = lexicon.find(text.substr(begin, i - begin));
        Require(word != lexicon.end(), "Unmapped Kokoro word");
        phrase.word_tokens.push_back(static_cast<jint>(phrase.tokens.size()));
        phrase.tokens.insert(phrase.tokens.end(), word->second.begin(), word->second.end());
        phrase.word_tokens.push_back(static_cast<jint>(phrase.tokens.size()));
      } else {
        char c = text[i++];
        Require(std::string(" ,.:;!?").find(c) != std::string::npos, "Invalid Kokoro separator");
        // Sherpa's English frontend normalizes a colon to a comma.
        phrase.tokens.push_back(symbols.at(std::string(1, c == ':' ? ',' : c)));
      }
    }
    Require(!phrase.word_tokens.empty() && phrase.tokens.size() - 1 <
            static_cast<size_t>(kokoro.GetMetaData().max_token_len), "Invalid Kokoro token count");
    phrase.tokens.push_back(0);  // End PAD.
    return phrase;
  }

  jobject Generate(JNIEnv *env, const std::string &text, jint sid, jfloat speed) const {
    Require(sid >= 0 && sid < kokoro.GetMetaData().num_speakers &&
            std::isfinite(speed) && speed >= .5f && speed <= 2.f, "Invalid Kokoro voice or speed");
    Phrase phrase = Encode(text);
    auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    std::array<int64_t, 2> shape{1, static_cast<int64_t>(phrase.tokens.size())};
    auto input = Ort::Value::CreateTensor<int64_t>(memory, phrase.tokens.data(), phrase.tokens.size(),
                                                    shape.data(), shape.size());
    auto out = kokoro.Run(std::move(input), sid, speed);
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

  sherpa_onnx::OfflineTtsKokoroModel kokoro;
  std::unordered_map<std::string, int32_t> symbols;
  std::unordered_map<std::string, std::vector<int32_t>> lexicon;
};

// No exception or phrase text crosses the JNI boundary in a diagnostic.
void ReportError(JNIEnv *env, const char *kind, const char *message) {
  if (env->ExceptionCheck()) return;
  if (auto cls = env->FindClass(kind)) env->ThrowNew(cls, message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_kokoro_TimedKokoro_open(
    JNIEnv *env, jclass, jstring model, jstring voices, jstring tokens, jstring lexicon, jint threads) {
  try {
    sherpa_onnx::OfflineTtsModelConfig config;
    Require(threads >= 1 && threads <= 4, "Invalid Kokoro thread count");
    config.num_threads = threads;
    config.kokoro.model = FromJava(env, model);
    config.kokoro.voices = FromJava(env, voices);
    config.kokoro.tokens = FromJava(env, tokens);
    config.kokoro.lexicon = FromJava(env, lexicon);
    return reinterpret_cast<jlong>(new Model(config));
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
    ReportError(env, "java/lang/IllegalStateException", "Kokoro could not generate aligned phrase audio");
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_TimedKokoro_release(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Model *>(handle);
}
