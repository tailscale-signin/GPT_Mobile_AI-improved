#pragma once
#include "ggml-backend.h"
#include <cstdint>
#include <string>
#include <vector>

inline const char * gguf_requested_registry(const std::string & backend) {
    return backend == "gpu" ? "Vulkan" : backend == "opencl" ? "OpenCL" : nullptr;
}
inline bool gguf_is_accelerator_type(enum ggml_backend_dev_type type) {
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}
inline bool gguf_accelerator_eligible(enum ggml_backend_dev_type type, const std::string & registry, const std::string & requested) {
    const char * expected = gguf_requested_registry(requested);
    return expected && registry == expected && gguf_is_accelerator_type(type);
}
inline bool gguf_accelerator_eligible(ggml_backend_dev_t device, const std::string & requested) {
    if (!device) return false;
    auto registry = ggml_backend_dev_backend_reg(device);
    return registry && gguf_accelerator_eligible(ggml_backend_dev_type(device), ggml_backend_reg_name(registry), requested);
}
// Disjoint ownership categories. Host accessibility is a separate overlapping measurement.
inline int gguf_allocation_owner(enum ggml_backend_dev_type type) {
    if (type == GGML_BACKEND_DEVICE_TYPE_CPU) return 0;
    if (type == GGML_BACKEND_DEVICE_TYPE_GPU) return 1;
    if (type == GGML_BACKEND_DEVICE_TYPE_IGPU) return 2;
    return 3;
}
inline bool gguf_prompt_fits(int64_t input, int64_t safety, int64_t output, int64_t context) {
    return input > 0 && safety >= 0 && output > 0 && context > 0 &&
        input <= context && safety <= context - input && output <= context - input - safety;
}

struct GgufOperationError {
    int64_t operation = 0;
    int64_t occurredAtMs = 0;
    int64_t stageObservedAtMs = 0;
    std::string backend;
    std::string stage;
    std::string errors;
    std::string warnings;
};

// Caller owns synchronization. Bounded history keeps earlier operations separate.
class GgufOperationErrors {
public:
    void begin(int64_t operation, const std::string & backend, int64_t now) {
        if (!current.errors.empty() || !current.warnings.empty()) {
            history.push_back(current);
            if (history.size() > 8) history.erase(history.begin());
        }
        current = GgufOperationError{operation, now, now, backend, "ENTRY", "", ""};
    }
    void stage(const std::string & value, int64_t now = 0) { current.stage = value; if (now > 0) current.stageObservedAtMs = now; }
    void backend(const std::string & value) { current.backend = value; }
    void append(const char * text, bool error) {
        auto & output = error ? current.errors : current.warnings;
        output += text;
        if (output.size() > 4096) output.erase(0, output.size() - 4096);
    }
    std::string errorFor(int64_t operation) const { return operation == current.operation ? current.errors : ""; }
    const GgufOperationError & active() const { return current; }
    const std::vector<GgufOperationError> & archived() const { return history; }
private:
    GgufOperationError current;
    std::vector<GgufOperationError> history;
};
