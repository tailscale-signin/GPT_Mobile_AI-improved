#if GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif
#if GGUF_OPENCL
#include "ggml-opencl.h"
#endif
#include <jni.h>
#include "llama.h"
#include "llama-ext.h"
#include "llama-model.h"
#include <cstdlib>
#include <cstdio>
#if GGUF_OPENCL
#include <CL/cl.h>
#include "gguf_opencl_trace.h"
#endif
#if GGML_USE_VULKAN
#include <vulkan/vulkan.h>
#endif
#include "ggml-backend.h"
#include "gguf_chat_template.h"
#include "gguf_prefill_governor.h"
#include "gguf_runtime_contract.h"
#include "gguf_vulkan_policy.h"
#include <sstream>
#include <iomanip>
#include <functional>
#include <unistd.h>
#include <algorithm>
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
    bool cpuOnly = true;
    int requestedLayers = 0;
    std::string backend = "cpu";
    std::string deviceInfo;
    std::string deviceName = "CPU";
    std::string deviceRegistry = "CPU";
    std::string deviceType = "CPU";
    int deviceIndex = 0;
    std::string driverIdentity;
    int threads = 0;
    int promptThreads = 0;
    int microBatchTokens = 0;
    std::string templateIdentity;
    std::string tokenizerIdentity;
    std::string lastPromptIdentity;
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
std::once_flag initialized, vulkanInitialized, openclInitialized;
bool portableVulkan = false;
using Clock = std::chrono::steady_clock;
long elapsed(Clock::time_point from) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - from).count(); }
std::mutex errorMutex;
GgufOperationErrors nativeErrors;
std::string nativeErrorPath;
std::string diagnosticWorkerGeneration;
std::vector<std::string> previousWorkerDiagnostics;
std::atomic<int64_t> probeSequence{0};
std::mutex traceMutex;
std::function<void(const std::string &)> traceObserver;
int64_t occurredAtMs() { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count(); }
std::string jsonText(const std::string & text) {
    std::string out = "\"";
    for (unsigned char c : text) {
        if (c == '\\' || c == '"') { out += '\\'; out += static_cast<char>(c); }
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else if (c < 32) out += " ";
        else out += static_cast<char>(c);
    }
    return out + "\"";
}
std::string diagnosticJson(const GgufOperationError & value) {
    return "{\"operationId\":" + std::to_string(value.operation) + ",\"occurredAtMillis\":" + std::to_string(value.occurredAtMs) +
        ",\"stageObservedAtMillis\":" + std::to_string(value.stageObservedAtMs) + ",\"processId\":" + std::to_string(getpid()) +
        ",\"workerGenerationId\":" + (diagnosticWorkerGeneration.empty() ? "null" : jsonText(diagnosticWorkerGeneration)) + ",\"backend\":" + jsonText(value.backend) + ",\"stage\":" + jsonText(value.stage) +
        ",\"errors\":" + jsonText(value.errors) + ",\"warnings\":" + jsonText(value.warnings) + "}";
}
void persistNativeDiagnostics(bool historyChanged = true, bool sync = false) {
    if (nativeErrorPath.empty()) return;
    auto write = [sync](const std::string & path, const std::string & content) {
        if (FILE * output = std::fopen(path.c_str(), "wb")) {
            std::fwrite(content.data(), 1, content.size(), output); std::fflush(output); if (sync) fsync(fileno(output)); std::fclose(output);
        }
    };
    write(nativeErrorPath, diagnosticJson(nativeErrors.active()));
    if (!historyChanged) return;
    std::vector<std::string> rows = previousWorkerDiagnostics;
    for (const auto & old : nativeErrors.archived()) rows.push_back(diagnosticJson(old));
    if (rows.size() > 8) rows.erase(rows.begin(), rows.end() - 8);
    std::string history;
    for (const auto & row : rows) history += row + "\n";
    write(nativeErrorPath + ".history", history);
}
void beginNativeDiagnostics(int64_t operation, const std::string & backend) {
    std::lock_guard<std::mutex> lock(errorMutex);
    nativeErrors.begin(operation, backend.substr(0, 64), occurredAtMs());
    persistNativeDiagnostics();
}
void nativeStage(const std::string & stage) {
    { std::lock_guard<std::mutex> lock(errorMutex); nativeErrors.stage(stage, occurredAtMs()); persistNativeDiagnostics(false, stage.rfind("OPENCL_", 0) == 0 || stage.rfind("REGISTER_", 0) == 0); }
    std::lock_guard<std::mutex> lock(traceMutex);
    if (traceObserver) traceObserver(stage);
}
std::string stableIdentity(const std::string & value) {
    uint64_t hash = 14695981039346656037ULL;
    for (unsigned char byte : value) { hash ^= byte; hash *= 1099511628211ULL; }
    std::ostringstream output; output << "fnv1a64:" << std::hex << std::setfill('0') << std::setw(16) << hash;
    return output.str();
}
void initialize() {
#if GGUF_OPENCL && defined(__ANDROID__)
    setenv("OCL_ICD_FILENAMES", "libOpenCL.so", 0);
#endif
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if ((level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_WARN) || !text) return;
        std::lock_guard<std::mutex> lock(errorMutex);
        nativeErrors.append(text, level == GGML_LOG_LEVEL_ERROR);
        persistNativeDiagnostics();
    }, nullptr);
    llama_backend_init();
}
// Global initialization now registers CPU only (see select_backends.cmake).
// A blocked vendor initializer is contained in the disposable inference worker.
// Must run before ggml registers/probes Vulkan: feature flags are read once.
void configureVulkan() {
#if GGML_USE_VULKAN && defined(__ANDROID__)
    VkInstanceCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    VkInstance instance;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return;
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count > 0 && count <= 32) {
        std::vector<VkPhysicalDevice> devices(count);
        const auto status = vkEnumeratePhysicalDevices(instance, &count, devices.data());
        if (status == VK_SUCCESS || status == VK_INCOMPLETE) for (uint32_t i = 0; i < count; ++i) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(devices[i], &properties);
            if (gguf_portable_vulkan(properties.vendorID, true)) portableVulkan = true;
        }
    }
    vkDestroyInstance(instance, nullptr);
    if (portableVulkan) {
        for (const char * flag : {"GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2",
                "GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "GGML_VK_DISABLE_BFLOAT16",
                "GGML_VK_DISABLE_F16", "GGML_VK_DISABLE_DOT2"}) setenv(flag, "1", 0);
        nativeStage("VULKAN_ADRENO_PORTABLE_SHADERS");
    }
#endif
}
void initializeBackend(const std::string & backend) {
    nativeStage("REGISTER_CPU_BEGIN");
    std::call_once(initialized, initialize);
    nativeStage("REGISTER_CPU_END");
#if GGML_USE_VULKAN
    if (backend == "gpu" && getenv("GGML_DISABLE_VULKAN") == nullptr) { nativeStage("REGISTER_VULKAN_BEGIN"); std::call_once(vulkanInitialized, [] { configureVulkan(); ggml_backend_register(ggml_backend_vk_reg()); }); nativeStage("REGISTER_VULKAN_END"); }
#endif
#if GGUF_OPENCL
    if (backend == "opencl") { nativeStage("REGISTER_OPENCL_BEGIN"); nativeStage("OPENCL_LOADER_pinned_static_ICD_vendor_libOpenCL.so"); std::call_once(openclInitialized, [] { ggml_backend_register(ggml_backend_opencl_reg()); }); nativeStage("REGISTER_OPENCL_END"); }
#endif
}
void fail(JNIEnv * env, const char * message) {
    std::string detail(message);
    { std::lock_guard<std::mutex> lock(errorMutex); if (nativeErrors.active().errors.find(message) == std::string::npos) { if (!nativeErrors.active().errors.empty()) nativeErrors.append("\n", true); nativeErrors.append(message, true); } }
    { std::lock_guard<std::mutex> lock(errorMutex); const auto & current = nativeErrors.active(); detail += " | operation=" + std::to_string(current.operation) + ";backend=" + current.backend + ";stage=" + current.stage; if (!current.errors.empty()) detail += " | native: " + current.errors; persistNativeDiagnostics(); }
    std::string category = "UNKNOWN";
    if (detail.find("GGUF_GPU_DEVICE_UNAVAILABLE") != std::string::npos) category = "DEVICE_UNAVAILABLE";
    else if (detail.find("createComputePipeline") != std::string::npos || detail.find("CL_BUILD_PROGRAM_FAILURE") != std::string::npos) category = "SHADER_COMPILATION";
    else if (detail.find("GGUF_GPU_MODEL_LOAD") != std::string::npos) category = "MODEL_LOAD";
    else if (detail.find("GGUF_GPU_ALLOCATION") != std::string::npos) category = "ALLOCATION";
    else if (detail.find("GGUF_GPU_UNSUPPORTED_OPERATION") != std::string::npos) category = "UNSUPPORTED_OPERATION";
    else if (detail.find("canceled") != std::string::npos) category = "CANCELLED";
    jclass type = env->FindClass("dev/melo/gguf/GgufNativeException");
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    auto code = env->NewStringUTF(category.c_str());
    auto original = env->NewStringUTF(detail.c_str());
    auto error = static_cast<jthrowable>(env->NewObject(type, constructor, code, original));
    env->Throw(error);
    env->DeleteLocalRef(code); env->DeleteLocalRef(original); env->DeleteLocalRef(error); env->DeleteLocalRef(type);
}
std::string backendName(JNIEnv * env, jstring value) {
    if (!value) return "cpu";
    const char * text = env->GetStringUTFChars(value, nullptr);
    std::string result(text); env->ReleaseStringUTFChars(value, text); return result;
}
ggml_backend_dev_t selectedDevice(const std::string & backend) {
    if (backend != "gpu" && backend != "opencl") return nullptr;
    const char * wanted = gguf_requested_registry(backend);
    auto reg = ggml_backend_reg_by_name(wanted);
    if (!reg) return nullptr;
    for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
        auto dev = ggml_backend_reg_dev_get(reg, i);
        if (gguf_accelerator_eligible(dev, backend)) return dev;
    }
    return nullptr;
}
std::string driverInfo(const std::string & backend, const std::string & expectedDescription = "") {
#if GGUF_OPENCL
    if (backend == "opencl") {
        cl_platform_id platforms[8]; cl_uint count = 0;
        if (clGetPlatformIDs(8, platforms, &count) == CL_SUCCESS) for (cl_uint i = 0; i < std::min(count, 8u); ++i) {
            cl_device_id devices[16]; cl_uint devicesCount = 0;
            if (clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 16, devices, &devicesCount) != CL_SUCCESS) continue;
            for (cl_uint j = 0; j < std::min(devicesCount, 16u); ++j) {
                char name[512] = {}, driver[512] = {};
                if (clGetDeviceInfo(devices[j], CL_DEVICE_NAME, sizeof(name), name, nullptr) != CL_SUCCESS) continue;
                if (!expectedDescription.empty() && expectedDescription != name) continue;
                if (clGetDeviceInfo(devices[j], CL_DRIVER_VERSION, sizeof(driver), driver, nullptr) != CL_SUCCESS) continue;
                return std::string(name) + ";driver=" + driver;
            }
        }
    }
#endif
#if GGML_USE_VULKAN
    if (backend == "gpu") {
        VkInstanceCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        VkInstance instance;
        if (vkCreateInstance(&info, nullptr, &instance) == VK_SUCCESS) {
            uint32_t count = 8; VkPhysicalDevice devices[8]; std::string result;
            auto status = vkEnumeratePhysicalDevices(instance, &count, devices);
            if ((status == VK_SUCCESS || status == VK_INCOMPLETE) && count > 0) {
                for (uint32_t i = 0; i < std::min(count, 8u); ++i) {
                    VkPhysicalDeviceProperties properties; vkGetPhysicalDeviceProperties(devices[i], &properties);
                    if (!expectedDescription.empty() && expectedDescription != properties.deviceName) continue;
                    result = std::string(properties.deviceName) + ";driver=" + std::to_string(properties.driverVersion) + ";api=" + std::to_string(properties.apiVersion);
                    break;
                }
            }
            vkDestroyInstance(instance, nullptr); return result;
        }
    }
#endif
    return "driver=unavailable";
}
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
    const std::string info = std::string("compiler=") + __VERSION__ + ";variant=" + GGUF_BUILD_TYPE + ";unoptimizedStepping=" + std::to_string(GGUF_NATIVE_DEBUG) + ";nativeProgress=1;operationControl=1;cpuPrefill=adaptive-v2;kleidiai=" + std::to_string(GGUF_KLEIDIAI);
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_gguf_GgufNative_supportsGpuOffload(JNIEnv * env, jclass) {
    try {
        beginNativeDiagnostics(-1 - probeSequence.fetch_add(1), "gpu");
        initializeBackend("gpu");
        return selectedDevice("gpu") ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) { fail(env, e.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_configureDiagnosticWorker(JNIEnv * env, jclass, jbyteArray value) {
    try {
        const auto worker = bytes(env, value);
        if (worker.size() > 128) throw std::runtime_error("Invalid diagnostic worker identity");
        std::lock_guard<std::mutex> lock(errorMutex);
        if (!diagnosticWorkerGeneration.empty() && diagnosticWorkerGeneration != worker) throw std::runtime_error("Native worker identity cannot change within one process");
        diagnosticWorkerGeneration = worker;
    } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_configureDiagnosticFile(JNIEnv * env, jclass, jbyteArray path) {
    try {
        const auto nextPath = bytes(env, path);
        if (nextPath.empty() || nextPath.size() > 4096) throw std::runtime_error("Invalid diagnostic file path");
        std::lock_guard<std::mutex> lock(errorMutex);
        if (nativeErrorPath != nextPath) {
            auto read = [](const std::string & file, size_t maximum) {
                std::string value;
                if (FILE * input = std::fopen(file.c_str(), "rb")) {
                    std::fseek(input, 0, SEEK_END); const long count = std::ftell(input); std::rewind(input);
                    if (count > 0 && static_cast<size_t>(count) <= maximum) { value.resize(count); if (std::fread(value.data(), 1, count, input) != static_cast<size_t>(count)) value.clear(); }
                    std::fclose(input);
                }
                return value;
            };
            previousWorkerDiagnostics.clear();
            std::istringstream history(read(nextPath + ".history", 160 * 1024));
            for (std::string line; std::getline(history, line);) if (!line.empty() && line.size() <= 20 * 1024 && line.front() == '{') previousWorkerDiagnostics.push_back(line);
            const auto previous = read(nextPath, 20 * 1024);
            if (!previous.empty()) previousWorkerDiagnostics.push_back(previous.front() == '{' ? previous :
                "{\"operationId\":null,\"occurredAtMillis\":null,\"stageObservedAtMillis\":null,\"processId\":null,\"workerGenerationId\":null,\"backend\":null,\"stage\":\"LEGACY_UNKNOWN\",\"errors\":" + jsonText(previous.substr(0, 4096)) + ",\"warnings\":\"\"}");
            if (previousWorkerDiagnostics.size() > 8) previousWorkerDiagnostics.erase(previousWorkerDiagnostics.begin(), previousWorkerDiagnostics.end() - 8);
        }
        nativeErrorPath = nextPath;
        persistNativeDiagnostics();
    } catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_gguf_GgufNative_supportsBackend(JNIEnv * env, jclass, jstring backend) {
    try { const auto name = backendName(env, backend); beginNativeDiagnostics(-1 - probeSequence.fetch_add(1), name); initializeBackend(name); return name == "cpu" || selectedDevice(name) ? JNI_TRUE : JNI_FALSE; }
    catch (const std::exception & e) { fail(env, e.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_validateChatTemplate(JNIEnv * env, jclass, jbyteArray templateBytes) {
    try {
        beginNativeDiagnostics(-1 - probeSequence.fetch_add(1), "template");
        nativeStage("VALIDATING_TEMPLATE");
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
    std::string lastPhase;
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
        if (fraction >= 0 && percent == lastPercent && lastPhase == phase) return !cancelLoad.load();
        lastPercent = percent;
        if (lastPhase != phase) { std::lock_guard<std::mutex> errorLock(errorMutex); nativeErrors.stage(phase, occurredAtMs()); persistNativeDiagnostics(false); }
        lastPhase = phase;
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
struct ScopedLoadTrace {
    explicit ScopedLoadTrace(LoadProgressObserver & observer) {
        std::lock_guard<std::mutex> lock(traceMutex);
        traceObserver = [&observer](const std::string & stage) { observer.emit(stage.c_str(), -1); };
    }
    ~ScopedLoadTrace() { std::lock_guard<std::mutex> lock(traceMutex); traceObserver = nullptr; }
};
}
extern "C" void gptmobile_opencl_trace(const char * api, bool before, int code) {
    nativeStage(std::string("OPENCL_") + api + (before ? "_BEGIN" : "_END_rc_" + std::to_string(code)));
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_loadBackendProgress(JNIEnv * env, jclass, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers, jlong operationId, jint promptThreads, jint batchTokens, jint microBatchTokens, jobject progress, jstring backendValue) {
    try {
        beginNativeDiagnostics(operationId, backendName(env, backendValue));
        if (contextTokens < 128 || contextTokens > 32768 || threads < 1 || threads > 64 || gpuLayers < 0 || gpuLayers > 999) throw std::runtime_error("Unsupported GGUF context, thread, or GPU layer limit");
        if (promptThreads < 1 || promptThreads > 64 || (batchTokens != 64 && batchTokens != 128 && batchTokens != 256) || (microBatchTokens != 64 && microBatchTokens != 128 && microBatchTokens != 256) || microBatchTokens > batchTokens) throw std::runtime_error("Invalid GGUF prompt thread or batch configuration");
        const auto backend = backendName(env, backendValue);
        LoadProgressObserver observer(env, progress, operationId);
        ScopedLoadTrace tracing(observer);
        if (!observer.emit("INITIALIZING_BACKEND", -1)) throw std::runtime_error("GGUF load canceled");
        initializeBackend(backend);
        if (!observer.emit("PROBING_DEVICE", -1)) throw std::runtime_error("GGUF load canceled");
        if (backend != "cpu" && backend != "gpu" && backend != "opencl") throw std::runtime_error("Unsupported GGUF backend");
        auto device = gpuLayers > 0 ? selectedDevice(backend) : nullptr;
        if (gpuLayers > 0 && !device) throw std::runtime_error("GGUF_GPU_DEVICE_UNAVAILABLE: selected GPU backend has no compatible device");
        nativeStage("PROBING_DEVICE");
        if (loadOperation != operationId || cancelLoad) throw std::runtime_error("GGUF load canceled before entry");
        auto engine = std::make_shared<Engine>();
        auto mp = llama_model_default_params();
        ggml_backend_dev_t selected[] = {device, nullptr};
        mp.devices = selected;
        engine->backend = backend;
        engine->requestedLayers = gpuLayers;
        if (device) {
            ggml_backend_dev_props props{}; ggml_backend_dev_get_props(device, &props);
            const auto type = ggml_backend_dev_type(device);
            const std::string registry = ggml_backend_reg_name(ggml_backend_dev_backend_reg(device));
            size_t index = 0;
            auto reg = ggml_backend_dev_backend_reg(device);
            while (index < ggml_backend_reg_dev_count(reg) && ggml_backend_reg_dev_get(reg, index) != device) ++index;
            engine->deviceName = ggml_backend_dev_description(device);
            engine->deviceRegistry = registry;
            engine->deviceType = type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "IGPU" : "GPU";
            engine->deviceIndex = static_cast<int>(index);
            engine->driverIdentity = driverInfo(backend, engine->deviceName);
            if (engine->driverIdentity == "driver=unavailable") engine->driverIdentity.clear();
            engine->deviceInfo = std::string(ggml_backend_dev_name(device)) + ";description=" + ggml_backend_dev_description(device) +
                ";registry=" + registry + ";deviceType=" + (type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "IGPU" : "GPU") +
                ";deviceIndex=" + std::to_string(index) + ";deviceId=" + (props.device_id ? props.device_id : "unknown") + ";" + (engine->driverIdentity.empty() ? "driver=unavailable" : engine->driverIdentity);
        } else engine->deviceInfo = "CPU;registry=CPU;deviceType=CPU;deviceIndex=0;driver=unavailable";
        observer.emit("LOADING_WEIGHTS", 0);
        mp.n_gpu_layers = gpuLayers;
        mp.progress_callback = [](float fraction, void * data) { return static_cast<LoadProgressObserver *>(data)->emit("LOADING_WEIGHTS", fraction); };
        mp.progress_callback_user_data = &observer;
        const auto filename = bytes(env, path);
        if (filename.find('\0') != std::string::npos) throw std::runtime_error("Invalid model path");
        engine->model = llama_model_load_from_file(filename.c_str(), mp);
        if (!engine->model) {
            if (cancelLoad || loadOperation != operationId) throw std::runtime_error("GGUF load canceled");
            throw std::runtime_error(gpuLayers > 0 ? "GGUF_GPU_MODEL_LOAD: model load failed; cause is in the original native error" : "GGUF load failed: unsupported artifact or insufficient resources");
        }
        observer.emit("PREPARING_TEMPLATE", -1);
        const char * tmpl = llama_model_chat_template(engine->model, nullptr);
        if (!tmpl || !*tmpl) throw std::runtime_error("GGUF has no embedded chat template; import a chat-tuned GGUF export");
        const auto vocab = llama_model_get_vocab(engine->model);
        engine->templateIdentity = "embedded-jinja:" + stableIdentity(tmpl);
        engine->tokenizerIdentity = "llama.cpp-vocab:" + std::to_string(llama_vocab_type(vocab)) + ":" + std::to_string(llama_vocab_n_tokens(vocab));
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
        engine->cpuOnly = gpuLayers == 0;
        cp.n_threads = threads;
        cp.n_threads_batch = promptThreads;
        engine->threads = threads;
        engine->promptThreads = promptThreads;
        engine->microBatchTokens = microBatchTokens;
        // The portable path also avoids extension-dependent flash-attention shaders.
        if (backend == "gpu" && portableVulkan) cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.offload_kqv = gpuLayers > 0;
        cp.op_offload = gpuLayers > 0;
        cp.no_perf = false;
        cp.abort_callback = aborted;
        cp.abort_callback_data = engine.get();
        observer.emit("ALLOCATING_CONTEXT", -1);
        engine->context = llama_init_from_model(engine->model, cp);
        if (cancelLoad || loadOperation != operationId) throw std::runtime_error("GGUF context allocation canceled");
        if (!engine->context) throw std::runtime_error(gpuLayers > 0 ? "GGUF_GPU_ALLOCATION: context allocation failed" : "GGUF context allocation failed");
        const jlong handle = nextId.fetch_add(1);
        std::lock_guard<std::mutex> lock(registryMutex);
        engines.emplace(handle, engine);
        observer.emit("NATIVE_READY", 1);
        return handle;
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_loadConfiguredProgress(JNIEnv * env, jclass type, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers, jlong operationId, jint promptThreads, jint batchTokens, jint microBatchTokens, jobject progress) {
    auto backend = env->NewStringUTF(gpuLayers > 0 ? "gpu" : "cpu");
    auto result = Java_dev_melo_gguf_GgufNative_loadBackendProgress(env, type, path, contextTokens, threads, gpuLayers, operationId, promptThreads, batchTokens, microBatchTokens, progress, backend);
    env->DeleteLocalRef(backend); return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_gguf_GgufNative_backendDeviceInfo(JNIEnv * env, jclass, jstring value) {
    try { const auto backend = backendName(env, value); beginNativeDiagnostics(-1 - probeSequence.fetch_add(1), backend); std::call_once(initialized, initialize); return env->NewStringUTF(driverInfo(backend).c_str()); }
    catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_gguf_GgufNative_executionInfo(JNIEnv * env, jclass, jlong handle) {
    try {
        auto engine = get(handle);
        int layers = 0;
        for (int i = 0; i < llama_model_n_layer(engine->model); ++i) {
            auto device = engine->model->dev_layer(i);
            if (gguf_accelerator_eligible(device, engine->backend)) ++layers;
        }
        std::string buffers;
        for (const auto & entry : llama_get_memory_breakdown(engine->context)) { if (!buffers.empty()) buffers += ","; buffers += ggml_backend_buft_name(entry.first); }
        const std::string info = "buffers=" + buffers + ";backend=" + engine->backend + ";device=" + engine->deviceInfo + ";requestedLayers=" + std::to_string(engine->requestedLayers) + ";actualRepeatingLayers=" + std::to_string(layers) + ";templateIdentity=" + engine->templateIdentity + ";tokenizerIdentity=" + engine->tokenizerIdentity + ";threads=" + std::to_string(engine->threads) + ";promptThreads=" + std::to_string(engine->promptThreads) + ";batch=" + std::to_string(llama_n_batch(engine->context)) + ";microbatch=" + std::to_string(llama_n_ubatch(engine->context));
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_gguf_GgufNative_engineReceipt(JNIEnv * env, jclass, jlong handle) {
    try {
        auto engine = get(handle);
        int layers = 0;
        for (int i = 0; i < llama_model_n_layer(engine->model); ++i) if (gguf_accelerator_eligible(engine->model->dev_layer(i), engine->backend)) ++layers;
        const std::string value = "{\"backend\":" + jsonText(layers > 0 ? engine->backend : "cpu") +
            ",\"registry\":" + jsonText(engine->deviceRegistry) + ",\"device\":" + jsonText(engine->deviceName) +
            ",\"deviceType\":" + jsonText(engine->deviceType) + ",\"deviceIndex\":" + std::to_string(engine->deviceIndex) +
            ",\"driver\":" + (engine->driverIdentity.empty() ? "null" : jsonText(engine->driverIdentity)) +
            ",\"actualRepeatingLayers\":" + std::to_string(layers) + ",\"threads\":" + std::to_string(engine->threads) +
            ",\"promptThreads\":" + std::to_string(engine->promptThreads) + ",\"batch\":" + std::to_string(llama_n_batch(engine->context)) +
            ",\"microbatch\":" + std::to_string(llama_n_ubatch(engine->context)) + ",\"context\":" + std::to_string(llama_n_ctx(engine->context)) +
            ",\"templateIdentity\":" + jsonText(engine->templateIdentity) + ",\"tokenizerIdentity\":" + jsonText(engine->tokenizerIdentity) + "}";
        return env->NewStringUTF(value.c_str());
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_melo_gguf_GgufNative_loadConfigured(JNIEnv * env, jclass type, jbyteArray path, jint contextTokens, jint threads, jint gpuLayers, jlong operationId, jint promptThreads, jint batchTokens, jint microBatchTokens) {
    return Java_dev_melo_gguf_GgufNative_loadConfiguredProgress(env, type, path, contextTokens, threads, gpuLayers, operationId, promptThreads, batchTokens, microBatchTokens, nullptr);
}

// The pinned CPU and CPU_Mapped allocators deliberately expose a null device.
// Compare their exact allocator identities; is_host also includes GPU-owned buffers.
int allocationOwner(ggml_backend_buffer_type_t type) {
    if (auto device = ggml_backend_buft_get_device(type)) return gguf_allocation_owner(ggml_backend_dev_type(device));
    if (type == ggml_backend_cpu_buffer_type()) return 0;
    static const auto mappedCpuType = [] {
        auto descriptor = ggml_backend_cpu_buffer_from_ptr(nullptr, 0);
        auto mappedType = ggml_backend_buffer_get_type(descriptor);
        ggml_backend_buffer_free(descriptor);
        return mappedType;
    }();
    return type == mappedCpuType ? 0 : 3;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_gguf_GgufNative_allocationObservation(JNIEnv * env, jclass, jlong handle) {
    try {
        auto engine = get(handle);
        std::lock_guard<std::mutex> lock(engine->generation);
        jlong values[20] = { static_cast<jlong>(llama_n_ctx(engine->context)), static_cast<jlong>(llama_n_batch(engine->context)), static_cast<jlong>(llama_n_ubatch(engine->context)), 0, 0, 0, 0, 0, 0, static_cast<jlong>(llama_model_size(engine->model)) };
        for (const auto & entry : llama_get_memory_breakdown(engine->context)) {
            const int owner = allocationOwner(entry.first);
            const int offset = owner == 0 ? 3 : owner == 1 ? 6 : owner == 2 ? 10 : 13;
            values[offset] += entry.second.model;
            values[offset + 1] += entry.second.context;
            values[offset + 2] += entry.second.compute;
            if (ggml_backend_buft_is_host(entry.first)) {
                values[16] += entry.second.model; values[17] += entry.second.context; values[18] += entry.second.compute;
            }
        }
        values[19] = 2; // Ownership schema; accessibility is an overlapping dimension.
        jlongArray result = env->NewLongArray(20);
        if (result) env->SetLongArrayRegion(result, 0, 20, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_melo_gguf_GgufNative_generateBudgeted(JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents, jint outputLimit, jint topK, jfloat topP, jfloat temperature, jobject callback, jobject progress, jint safetyTokens, jlong operationId) {
    try {
        beginNativeDiagnostics(operationId, "generation");
        std::shared_ptr<Engine> engine;
        const auto operationStart = Clock::now();
        jmethodID onProgress = nullptr;
        if (progress) {
            auto type = env->GetObjectClass(progress);
            onProgress = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;JJJJI)V");
            env->DeleteLocalRef(type);
            if (!onProgress) return nullptr;
        }
        auto report = [&](const char * phase, jlong total, jlong processed, jlong sampled, int code = 0) {
            { std::lock_guard<std::mutex> lock(errorMutex); nativeErrors.stage(phase, occurredAtMs()); }
            if (!progress || env->ExceptionCheck()) return;
            auto name = env->NewStringUTF(phase);
            env->CallVoidMethod(progress, onProgress, name, total, processed, sampled, static_cast<jlong>(elapsed(operationStart)), code);
            env->DeleteLocalRef(name);
            if (env->ExceptionCheck() && engine) engine->stop = true;
        };
        report("NATIVE_ENTRY", -1, -1, 0);
        if (env->ExceptionCheck()) return nullptr;
        engine = get(handle);
        { std::lock_guard<std::mutex> lock(errorMutex); nativeErrors.backend(engine->backend); }
        std::unique_lock<std::mutex> lease(engine->generation, std::try_to_lock);
        if (!lease.owns_lock()) throw std::runtime_error("GGUF generation is already running");
        if (engine->operation != operationId) throw std::runtime_error("Stale GGUF operation rejected");
        if (engine->stop) {
            report("NATIVE_RETURN", -1, 0, 0, 2);
            jlong values[] = {0, 0, 0, 0, 2, 0, 0};
            auto result = env->NewLongArray(7);
            env->SetLongArrayRegion(result, 0, 7, values);
            return result;
        }

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
        const auto capacity = llama_n_ctx(engine->context);
        engine->lastPromptIdentity = stableIdentity(prompt);
        report("TOKEN_COUNT_MEASURED", tokenCount, 0, 0);
        report("ADMISSION_SAFETY_TOKENS", tokenCount, 0, 0, safetyTokens);
        report("ADMISSION_OUTPUT_TOKENS", tokenCount, 0, 0, outputLimit);
        report("ADMISSION_CONTEXT_TOKENS", tokenCount, 0, 0, static_cast<int>(capacity));
        if (!gguf_prompt_fits(tokenCount, safetyTokens, outputLimit, capacity)) {
            report("CONTEXT_ADMISSION_REJECTED", tokenCount, 0, 0, static_cast<int>(capacity));
            throw std::runtime_error("GGUF context limit: rendered input=" + std::to_string(tokenCount) +
                "; safety=" + std::to_string(safetyTokens) + "; requested output=" + std::to_string(outputLimit) +
                "; capacity=" + std::to_string(capacity) + "; no prefill was dispatched; adjust the explicit request budget");
        }
        const int effectiveOutputLimit = outputLimit;
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
        GgufPrefillGovernor governor(engine->batchTokens, engine->cpuOnly);
        for (int offset = static_cast<int>(reused); offset < tokenCount && !engine->stop;) {
            const int n = governor.selected(tokenCount - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, n);
            report("PREFILL_BATCH_BEGIN", tokenCount, input, output, n);
            const auto batchStart = Clock::now();
            const int result = llama_decode(engine->context, batch);
            report("PREFILL_BATCH_END", tokenCount, result == 0 ? input + n : input, output, result);
            if (result != 0) { stop = engine->stop ? 2 : 3; break; }
            input += n;
            offset += n;
            governor.observed(elapsed(batchStart), n);
        }
        const jlong prefillMs = elapsed(start);
        const auto decodeStart = Clock::now();
        report("PREFILL_END", tokenCount, input, output, static_cast<int>(stop));
        if (input == tokenCount) for (int i = 0; i < effectiveOutputLimit && !engine->stop; ++i) {
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
        int offloaded = 0;
        for (int i = 0; i < llama_model_n_layer(engine->model); ++i) if (gguf_accelerator_eligible(engine->model->dev_layer(i), engine->backend)) ++offloaded;
        jlong values[] = {input, output, prefillMs, elapsed(decodeStart), stop, static_cast<jlong>(reused), input - static_cast<jlong>(reused), tokenCount, outputLimit, effectiveOutputLimit, safetyTokens, capacity, operationId, engine->threads, engine->promptThreads, llama_n_batch(engine->context), llama_n_ubatch(engine->context), offloaded, 2};
        auto result = env->NewLongArray(19);
        env->SetLongArrayRegion(result, 0, 19, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_melo_gguf_GgufNative_generationPromptIdentity(JNIEnv * env, jclass, jlong handle, jlong operationId) {
    try { auto engine = get(handle); if (engine->operation != operationId) throw std::runtime_error("Stale GGUF prompt receipt"); return env->NewStringUTF(engine->lastPromptIdentity.c_str()); }
    catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_gguf_GgufNative_prepareOperation(JNIEnv * env, jclass, jlong handle, jlong id) {
    try {
        std::lock_guard<std::mutex> lock(operationMutex);
        beginNativeDiagnostics(id, handle == 0 ? "load" : "generation");
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
