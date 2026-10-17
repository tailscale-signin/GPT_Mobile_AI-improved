#include <jni.h>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> stop{false};
    std::mutex generation;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> engines;
std::atomic<jlong> nextId{1};
std::atomic<bool> cancelLoad{false};
std::once_flag initialized;
using Clock = std::chrono::steady_clock;
long elapsed(Clock::time_point from) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - from).count(); }
void fail(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::runtime_error("Missing UTF-8 value");
    const auto size = env->GetArrayLength(value);
    if (size > 4 * 1024 * 1024) throw std::runtime_error("GGUF input exceeds the bounded transport");
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
std::shared_ptr<Engine> get(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = engines.find(handle);
    if (it == engines.end()) throw std::runtime_error("GGUF engine is not loaded");
    return it->second;
}
bool aborted(void * data) { return static_cast<Engine *>(data)->stop.load(); }
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_gguf_GgufNative_supportsGpuOffload(JNIEnv * env, jclass) {
    try {
        std::call_once(initialized, [] { llama_backend_init(); });
        return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) { fail(env, e.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_load(JNIEnv * env, jclass, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers) {
    try {
        if (contextTokens < 128 || contextTokens > 32768 || threads < 1 || threads > 64 || gpuLayers < 0 || gpuLayers > 999) throw std::runtime_error("Unsupported GGUF context, thread, or GPU layer limit");
        std::call_once(initialized, [] { llama_backend_init(); });
        if (gpuLayers > 0 && !llama_supports_gpu_offload()) throw std::runtime_error("GGUF Vulkan GPU offload is unavailable on this device or build; select CPU");
        cancelLoad = false;
        auto engine = std::make_shared<Engine>();
        auto mp = llama_model_default_params();
        ggml_backend_dev_t cpuOnly[] = {nullptr};
        mp.devices = gpuLayers > 0 ? nullptr : cpuOnly;
        mp.n_gpu_layers = gpuLayers;
        mp.progress_callback = [](float, void *) { return !cancelLoad.load(); };
        const auto filename = bytes(env, path);
        if (filename.find('\0') != std::string::npos) throw std::runtime_error("Invalid model path");
        engine->model = llama_model_load_from_file(filename.c_str(), mp);
        if (!engine->model) throw std::runtime_error("GGUF load failed: unsupported artifact, canceled load, or insufficient resources");
        const char * tmpl = llama_model_chat_template(engine->model, nullptr);
        if (!tmpl || !*tmpl) throw std::runtime_error("GGUF has no embedded chat template; import a chat-tuned GGUF export");
        const llama_chat_message probe{"user", "Hello"};
        const int templateSize = llama_chat_apply_template(tmpl, &probe, 1, true, nullptr, 0);
        if (templateSize <= 0 || templateSize > 4 * 1024 * 1024) throw std::runtime_error("GGUF embedded chat template is unsupported by this llama.cpp revision; choose an export using a supported template");
        if (llama_model_has_encoder(engine->model)) throw std::runtime_error("This GGUF route supports decoder-only text models");
        auto cp = llama_context_default_params();
        cp.n_ctx = contextTokens;
        cp.n_batch = 256;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.offload_kqv = gpuLayers > 0;
        cp.op_offload = gpuLayers > 0;
        cp.no_perf = false;
        cp.abort_callback = aborted;
        cp.abort_callback_data = engine.get();
        engine->context = llama_init_from_model(engine->model, cp);
        if (!engine->context || cancelLoad) throw std::runtime_error("GGUF context allocation failed or was canceled");
        const jlong handle = nextId.fetch_add(1);
        std::lock_guard<std::mutex> lock(registryMutex);
        engines.emplace(handle, engine);
        return handle;
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_gguf_GgufNative_generate(JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents, jint outputLimit, jint topK, jfloat topP, jfloat temperature, jobject callback) {
    try {
        auto engine = get(handle);
        std::unique_lock<std::mutex> lease(engine->generation, std::try_to_lock);
        if (!lease.owns_lock()) throw std::runtime_error("GGUF generation is already running");
        engine->stop = false;
        if (!roles || !contents || !callback || outputLimit <= 0 || outputLimit > 8192) throw std::runtime_error("Invalid GGUF generation parameters");
        const int count = env->GetArrayLength(roles);
        if (count < 1 || count > 512 || env->GetArrayLength(contents) != count) throw std::runtime_error("Invalid bounded GGUF history");
        std::vector<std::string> roleText, contentText;
        roleText.reserve(count); contentText.reserve(count);
        size_t totalBytes = 0;
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            if (!role) throw std::runtime_error("Missing GGUF role");
            const char * value = env->GetStringUTFChars(role, nullptr);
            if (!value) return nullptr;
            roleText.emplace_back(value);
            env->ReleaseStringUTFChars(role, value); env->DeleteLocalRef(role);
            if (roleText.back() != "user" && roleText.back() != "assistant" && roleText.back() != "system") throw std::runtime_error("Unsupported GGUF role");
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            contentText.push_back(bytes(env, content)); env->DeleteLocalRef(content);
            totalBytes += contentText.back().size();
            if (totalBytes > 4 * 1024 * 1024 || contentText.back().find('\0') != std::string::npos) throw std::runtime_error("GGUF history exceeds bounds or contains a null byte");
        }
        std::vector<llama_chat_message> messages;
        for (int i = 0; i < count; ++i) messages.push_back({roleText[i].c_str(), contentText[i].c_str()});
        const char * tmpl = llama_model_chat_template(engine->model, nullptr);
        int size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (size <= 0 || size > 4 * 1024 * 1024) throw std::runtime_error("Unsupported GGUF chat template; choose a compatible export");
        std::string prompt(size, '\0');
        if (llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, prompt.data(), size) != size) throw std::runtime_error("GGUF template formatting failed");
        const auto vocab = llama_model_get_vocab(engine->model);
        const int tokenCount = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (tokenCount <= 0 || static_cast<uint64_t>(tokenCount) + outputLimit > llama_n_ctx(engine->context)) throw std::runtime_error("GGUF context limit: shorten input or select a larger compatible context");
        std::vector<llama_token> tokens(tokenCount);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true) != tokenCount) throw std::runtime_error("GGUF tokenization failed");
        llama_memory_clear(llama_get_memory(engine->context), true);
        llama_perf_context_reset(engine->context);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (topK < 1 || topK > 1024 || !std::isfinite(topP) || topP < 0 || topP > 1 || !std::isfinite(temperature) || temperature < 0 || temperature > 5) throw std::runtime_error("Invalid GGUF sampler");
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(topK));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(topP, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        auto callbackClass = env->GetObjectClass(callback);
        const auto onBytes = env->GetMethodID(callbackClass, "onBytes", "([B)Z");
        env->DeleteLocalRef(callbackClass);
        if (!onBytes) return nullptr;
        jlong input = 0, output = 0, stop = 1;
        const auto start = Clock::now();
        for (int offset = 0; offset < tokenCount && !engine->stop; offset += 256) {
            const int n = std::min(256, tokenCount - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, n);
            const int result = llama_decode(engine->context, batch);
            if (result != 0) { stop = engine->stop ? 2 : 3; break; }
            input += n;
        }
        const jlong prefillMs = elapsed(start);
        const auto decodeStart = Clock::now();
        if (input == tokenCount) for (int i = 0; i < outputLimit && !engine->stop; ++i) {
            const auto token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) { stop = 0; break; }
            llama_sampler_accept(sampler.get(), token);
            std::vector<char> piece(256);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
            if (n < 0) { stop = 3; break; }
            ++output;
            auto chunk = env->NewByteArray(n);
            env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
            const bool more = env->CallBooleanMethod(callback, onBytes, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) return nullptr;
            if (!more) { engine->stop = true; break; }
            auto next = token;
            if (llama_decode(engine->context, llama_batch_get_one(&next, 1)) != 0) { stop = engine->stop ? 2 : 3; break; }
        }
        if (engine->stop) stop = 2;
        jlong values[] = {input, output, prefillMs, elapsed(decodeStart), stop};
        auto result = env->NewLongArray(5);
        env->SetLongArrayRegion(result, 0, 5, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_cancel(JNIEnv *, jclass, jlong handle) {
    cancelLoad = true;
    if (handle != 0) { try { get(handle)->stop = true; } catch (...) {} }
}
extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_unload(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = engines.find(handle);
    if (found != engines.end()) { found->second->stop = true; engines.erase(found); }
}
