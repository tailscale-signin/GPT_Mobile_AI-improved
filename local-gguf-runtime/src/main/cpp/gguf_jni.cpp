#include <jni.h>
#include "llama.h"
#include "llama-ext.h"
#include "ggml-backend.h"
#include "gguf_chat_template.h"
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
    int batchTokens = 256;
    std::unique_ptr<GgufChatTemplate> chatTemplate;
    std::atomic<bool> stop{false};
    std::atomic<jlong> operation{0};
    std::mutex generation;
    std::vector<llama_token> committedTokens;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> engines;
std::atomic<jlong> nextId{1};
std::atomic<bool> cancelLoad{false};
std::atomic<jlong> loadOperation{0};
std::mutex operationMutex;
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

extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_gguf_GgufNative_buildInfo(JNIEnv * env, jclass) {
    const std::string info = std::string("compiler=") + __VERSION__ + ";variant=" + GGUF_BUILD_TYPE + ";unoptimizedStepping=" + std::to_string(GGUF_NATIVE_DEBUG) + ";nativeProgress=1;operationControl=1";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_gguf_GgufNative_supportsGpuOffload(JNIEnv * env, jclass) {
    try {
        std::call_once(initialized, [] { llama_backend_init(); });
        return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) { fail(env, e.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_validateChatTemplate(JNIEnv * env, jclass, jbyteArray templateBytes) {
    try {
        GgufChatTemplate::validateSyntax(bytes(env, templateBytes));
    } catch (const std::exception & e) { fail(env, e.what()); }
}

namespace {
struct LoadProgressObserver {
    JavaVM * vm = nullptr;
    JNIEnv * owner = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    std::mutex mutex;
    int lastPercent = -1;
    jlong operation = 0;
    std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
    LoadProgressObserver(JNIEnv * env, jobject progress, jlong operationId) : owner(env), operation(operationId) {
        if (!progress) return;
        env->GetJavaVM(&vm);
        callback = env->NewGlobalRef(progress);
        jclass type = env->GetObjectClass(progress);
        method = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;FJ)V");
        env->DeleteLocalRef(type);
        if (!callback || !method) { if (callback) env->DeleteGlobalRef(callback); callback = nullptr; throw std::runtime_error("GGUF load progress callback unavailable"); }
    }
    ~LoadProgressObserver() { if (callback) owner->DeleteGlobalRef(callback); }
    bool emit(const char * phase, float fraction) {
        if (loadOperation != operation) return false;
        if (!callback) return !cancelLoad.load();
        std::lock_guard<std::mutex> lock(mutex);
        int percent = fraction >= 0 ? static_cast<int>(fraction * 100) : -1;
        if (fraction >= 0 && percent == lastPercent) return !cancelLoad.load();
        lastPercent = percent;
        JNIEnv * env = nullptr;
        bool attached = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
#if defined(__ANDROID__)
        if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
#else
        if (attached && vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return false;
#endif
        jstring name = env->NewStringUTF(phase);
        const jlong ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        if (name) env->CallVoidMethod(callback, method, name, fraction, ms);
        if (name) env->DeleteLocalRef(name);
        bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        if (attached) vm->DetachCurrentThread();
        return !failed && !cancelLoad.load();
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_loadConfiguredProgress(JNIEnv * env, jclass, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers, jlong operationId, jint promptThreads, jint batchTokens, jint microBatchTokens, jobject progress) {
    try {
        if (contextTokens < 128 || contextTokens > 32768 || threads < 1 || threads > 64 || gpuLayers < 0 || gpuLayers > 999) throw std::runtime_error("Unsupported GGUF context, thread, or GPU layer limit");
        if (promptThreads < 1 || promptThreads > 64 || (batchTokens != 64 && batchTokens != 128 && batchTokens != 256) || (microBatchTokens != 64 && microBatchTokens != 128 && microBatchTokens != 256) || microBatchTokens > batchTokens) throw std::runtime_error("Invalid GGUF prompt thread or batch configuration");
        std::call_once(initialized, [] { llama_backend_init(); });
        if (gpuLayers > 0 && !llama_supports_gpu_offload()) throw std::runtime_error("GGUF Vulkan GPU offload is unavailable on this device or build; select CPU");
        if (loadOperation != operationId || cancelLoad) throw std::runtime_error("GGUF load canceled before entry");
        LoadProgressObserver observer(env, progress, operationId);
        observer.emit("LOADING_WEIGHTS", 0);
        auto engine = std::make_shared<Engine>();
        auto mp = llama_model_default_params();
        ggml_backend_dev_t cpuOnly[] = {nullptr};
        mp.devices = gpuLayers > 0 ? nullptr : cpuOnly;
        mp.n_gpu_layers = gpuLayers;
        mp.progress_callback = [](float fraction, void * data) { return static_cast<LoadProgressObserver *>(data)->emit("LOADING_WEIGHTS", fraction); };
        mp.progress_callback_user_data = &observer;
        const auto filename = bytes(env, path);
        if (filename.find('\0') != std::string::npos) throw std::runtime_error("Invalid model path");
        engine->model = llama_model_load_from_file(filename.c_str(), mp);
        if (!engine->model) throw std::runtime_error("GGUF load failed: unsupported artifact, canceled load, or insufficient resources");
        observer.emit("PREPARING_TEMPLATE", -1);
        const char * tmpl = llama_model_chat_template(engine->model, nullptr);
        if (!tmpl || !*tmpl) throw std::runtime_error("GGUF has no embedded chat template; import a chat-tuned GGUF export");
        const auto vocab = llama_model_get_vocab(engine->model);
        const auto tokenText = [vocab](llama_token token) -> std::string {
            if (token == LLAMA_TOKEN_NULL) return "";
            std::string piece(256, '\0');
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (size < 0) {
                piece.resize(-size);
                size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            }
            if (size < 0) throw std::runtime_error("GGUF chat template special token decoding failed");
            piece.resize(size);
            return piece;
        };
        engine->chatTemplate = std::make_unique<GgufChatTemplate>(tmpl,
            tokenText(llama_vocab_bos(vocab)), tokenText(llama_vocab_eos(vocab)),
            llama_vocab_get_add_bos(vocab), llama_vocab_get_add_eos(vocab));
        if (llama_model_has_encoder(engine->model)) throw std::runtime_error("This GGUF route supports decoder-only text models");
        auto cp = llama_context_default_params();
        cp.n_ctx = contextTokens;
        cp.n_batch = batchTokens;
        cp.n_ubatch = microBatchTokens;
        engine->batchTokens = batchTokens;
        cp.n_threads = threads;
        cp.n_threads_batch = promptThreads;
        cp.offload_kqv = gpuLayers > 0;
        cp.op_offload = gpuLayers > 0;
        cp.no_perf = false;
        cp.abort_callback = aborted;
        cp.abort_callback_data = engine.get();
        observer.emit("ALLOCATING_CONTEXT", -1);
        engine->context = llama_init_from_model(engine->model, cp);
        if (!engine->context || cancelLoad || loadOperation != operationId) throw std::runtime_error("GGUF context allocation failed or was canceled");
        const jlong handle = nextId.fetch_add(1);
        std::lock_guard<std::mutex> lock(registryMutex);
        engines.emplace(handle, engine);
        observer.emit("NATIVE_READY", 1);
        return handle;
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_loadConfigured(JNIEnv * env, jclass type, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers, jlong operationId, jint promptThreads, jint batchTokens, jint microBatchTokens) {
    return Java_dev_melo_gguf_GgufNative_loadConfiguredProgress(env, type, path, contextTokens, threads, gpuLayers, operationId, promptThreads, batchTokens, microBatchTokens, nullptr);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_gguf_GgufNative_allocationObservation(JNIEnv * env, jclass, jlong handle) {
    try {
        auto engine = get(handle);
        std::lock_guard<std::mutex> lock(engine->generation);
        jlong values[10] = { static_cast<jlong>(llama_n_ctx(engine->context)), static_cast<jlong>(llama_n_batch(engine->context)), static_cast<jlong>(llama_n_ubatch(engine->context)), 0, 0, 0, 0, 0, 0, static_cast<jlong>(llama_model_size(engine->model)) };
        for (const auto & entry : llama_get_memory_breakdown(engine->context)) {
            const int offset = ggml_backend_buft_is_host(entry.first) ? 3 : 6;
            values[offset] += entry.second.model;
            values[offset + 1] += entry.second.context;
            values[offset + 2] += entry.second.compute;
        }
        jlongArray result = env->NewLongArray(10);
        if (result) env->SetLongArrayRegion(result, 0, 10, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_gguf_GgufNative_generatePrepared(JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents, jint outputLimit, jint topK, jfloat topP, jfloat temperature, jobject callback, jobject progress, jlong operationId) {
    try {
        auto engine = get(handle);
        std::unique_lock<std::mutex> lease(engine->generation, std::try_to_lock);
        if (!lease.owns_lock()) throw std::runtime_error("GGUF generation is already running");
        if (engine->operation != operationId) throw std::runtime_error("Stale GGUF operation rejected");
        if (engine->stop) {
            jlong values[] = {0, 0, 0, 0, 2, 0, 0};
            auto result = env->NewLongArray(7);
            env->SetLongArrayRegion(result, 0, 7, values);
            return result;
        }
        const auto operationStart = Clock::now();
        jmethodID onProgress = nullptr;
        if (progress) {
            auto type = env->GetObjectClass(progress);
            onProgress = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;JJJJI)V");
            env->DeleteLocalRef(type);
            if (!onProgress) return nullptr;
        }
        auto report = [&](const char * phase, jlong total, jlong processed, jlong sampled, int code = 0) {
            if (!progress || env->ExceptionCheck()) return;
            auto name = env->NewStringUTF(phase);
            env->CallVoidMethod(progress, onProgress, name, total, processed, sampled, static_cast<jlong>(elapsed(operationStart)), code);
            env->DeleteLocalRef(name);
            if (env->ExceptionCheck()) engine->stop = true;
        };
        report("RENDER_BEGIN", -1, -1, 0);
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
        GgufChatTemplate::Messages messages;
        for (int i = 0; i < count; ++i) messages.emplace_back(roleText[i], contentText[i]);
        const auto prompt = engine->chatTemplate->render(messages);
        report("RENDER_END", -1, -1, 0);
        report("TOKENIZE_BEGIN", -1, -1, 0);
        const auto vocab = llama_model_get_vocab(engine->model);
        const int tokenCount = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (tokenCount <= 0 || static_cast<uint64_t>(tokenCount) + outputLimit > llama_n_ctx(engine->context)) throw std::runtime_error("GGUF context limit: shorten input or select a larger compatible context");
        std::vector<llama_token> tokens(tokenCount);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true) != tokenCount) throw std::runtime_error("GGUF tokenization failed");
        report("TOKENIZE_END", tokenCount, 0, 0);
        // Reuse only exact token identity. Always re-evaluate the last prompt
        // token to obtain current logits; text-prefix equality is insufficient.
        size_t reused = 0;
        while (reused < engine->committedTokens.size() && reused + 1 < tokens.size() &&
               engine->committedTokens[reused] == tokens[reused]) ++reused;
        engine->committedTokens.clear(); // Any failure/cancellation invalidates reuse.
        if (reused == 0 || !llama_memory_seq_rm(llama_get_memory(engine->context), 0, reused, -1)) {
            llama_memory_clear(llama_get_memory(engine->context), true);
            reused = 0;
        }
        std::vector<llama_token> decodedTokens = tokens;
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
        jlong input = reused, output = 0, stop = 1;
        const auto start = Clock::now();
        report("PREFILL_BEGIN", tokenCount, input, output);
        for (int offset = static_cast<int>(reused); offset < tokenCount && !engine->stop; offset += engine->batchTokens) {
            const int n = std::min(engine->batchTokens, tokenCount - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, n);
            report("PREFILL_BATCH_BEGIN", tokenCount, input, output);
            const int result = llama_decode(engine->context, batch);
            report("PREFILL_BATCH_END", tokenCount, result == 0 ? input + n : input, output, result);
            if (result != 0) { stop = engine->stop ? 2 : 3; break; }
            input += n;
        }
        const jlong prefillMs = elapsed(start);
        const auto decodeStart = Clock::now();
        report("PREFILL_END", tokenCount, input, output, static_cast<int>(stop));
        if (input == tokenCount) for (int i = 0; i < outputLimit && !engine->stop; ++i) {
            const auto token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (i == 0) report("FIRST_SAMPLE", tokenCount, input, output);
            if (llama_vocab_is_eog(vocab, token)) { stop = 0; break; }
            llama_sampler_accept(sampler.get(), token);
            std::vector<char> piece(256);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
            if (n < 0) { stop = 3; break; }
            ++output;
            report(output == 1 ? "FIRST_BYTES" : "DECODING", tokenCount, input, output);
            auto chunk = env->NewByteArray(n);
            env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
            const bool more = env->CallBooleanMethod(callback, onBytes, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) return nullptr;
            if (!more) { engine->stop = true; break; }
            auto next = token;
            if (llama_decode(engine->context, llama_batch_get_one(&next, 1)) != 0) { stop = engine->stop ? 2 : 3; break; }
            decodedTokens.push_back(token);
        }
        if (engine->stop) stop = 2;
        if (stop == 0 || stop == 1) engine->committedTokens = std::move(decodedTokens);
        report("NATIVE_RETURN", tokenCount, input, output, static_cast<int>(stop));
        jlong values[] = {input, output, prefillMs, elapsed(decodeStart), stop, static_cast<jlong>(reused), input - static_cast<jlong>(reused)};
        auto result = env->NewLongArray(7);
        env->SetLongArrayRegion(result, 0, 7, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_prepareOperation(JNIEnv * env, jclass, jlong handle, jlong id) {
    try {
        std::lock_guard<std::mutex> lock(operationMutex);
        if (id <= 0) throw std::runtime_error("Invalid GGUF operation identity");
        if (handle == 0) { loadOperation = id; cancelLoad = false; }
        else {
            auto engine = get(handle);
            std::unique_lock<std::mutex> lease(engine->generation, std::try_to_lock);
            if (!lease.owns_lock()) throw std::runtime_error("Cannot prepare an active GGUF engine");
            engine->operation = id;
            engine->stop = false;
        }
    } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_cancelOperation(JNIEnv *, jclass, jlong handle, jlong id) {
    std::lock_guard<std::mutex> lock(operationMutex);
    if (handle == 0) { if (loadOperation == id) cancelLoad = true; }
    else { try { auto engine = get(handle); if (engine->operation == id) engine->stop = true; } catch (...) {} }
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
