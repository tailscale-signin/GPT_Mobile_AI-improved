#include "gguf_runtime_contract.h"
#include <cassert>
#include <limits>

int main() {
    for (auto type : {GGML_BACKEND_DEVICE_TYPE_GPU, GGML_BACKEND_DEVICE_TYPE_IGPU}) {
        assert(gguf_accelerator_eligible(type, "Vulkan", "gpu"));
        assert(gguf_accelerator_eligible(type, "OpenCL", "opencl"));
        assert(!gguf_accelerator_eligible(type, "OpenCL", "gpu"));
        assert(!gguf_accelerator_eligible(type, "Vulkan", "opencl"));
        assert(!gguf_accelerator_eligible(type, "CPU", "gpu"));
        assert(!gguf_accelerator_eligible(type, "Vulkan", "cpu"));
    }
    for (auto type : {GGML_BACKEND_DEVICE_TYPE_CPU, GGML_BACKEND_DEVICE_TYPE_ACCEL, GGML_BACKEND_DEVICE_TYPE_META}) {
        assert(!gguf_accelerator_eligible(type, "Vulkan", "gpu"));
        assert(!gguf_accelerator_eligible(type, "OpenCL", "opencl"));
    }
    // Ownership is independent of whether an allocator provides an is_host callback.
    assert(gguf_allocation_owner(GGML_BACKEND_DEVICE_TYPE_CPU) == 0);
    assert(gguf_allocation_owner(GGML_BACKEND_DEVICE_TYPE_GPU) == 1);
    assert(gguf_allocation_owner(GGML_BACKEND_DEVICE_TYPE_IGPU) == 2);
    assert(gguf_allocation_owner(GGML_BACKEND_DEVICE_TYPE_ACCEL) == 3);
    assert(gguf_prompt_fits(29, 16, 467, 512));
    assert(!gguf_prompt_fits(29, 16, 468, 512));
    assert(!gguf_prompt_fits(29, 500, 1, 512));
    assert(!gguf_prompt_fits(29, -1, 1, 512));
    assert(!gguf_prompt_fits(std::numeric_limits<int64_t>::max(), 1, 1, std::numeric_limits<int64_t>::max()));
    GgufOperationErrors errors;
    errors.begin(41, "cpu", 1000);
    errors.stage("PREFILL_BATCH_END", 1010);
    errors.append("prior CPU graph failure", true);
    errors.begin(42, "gpu", 2000);
    errors.stage("PROBING_DEVICE", 2005);
    errors.append("selected registry unavailable", true);
    assert(errors.errorFor(41).empty());
    assert(errors.errorFor(42) == "selected registry unavailable");
    assert(errors.archived().size() == 1);
    assert(errors.archived().front().operation == 41);
    assert(errors.archived().front().errors == "prior CPU graph failure");
    assert(errors.active().backend == "gpu");
    assert(errors.active().stageObservedAtMs == 2005);
    for (int i = 0; i < 20; ++i) { errors.begin(100 + i, "opencl", 3000 + i); errors.append("warning", false); }
    assert(errors.archived().size() == 8);
    errors.append(std::string(5000, 'x').c_str(), true);
    assert(errors.active().errors.size() == 4096);
}
