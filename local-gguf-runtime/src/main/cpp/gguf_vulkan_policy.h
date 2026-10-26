#pragma once
#include <cstdint>

// Qualcomm's Android driver may advertise shader extensions which fail when a
// model first compiles its prefill pipelines. Keep real Vulkan layer offload,
// using the portable compute path rather than reporting CPU fallback as GPU.
inline bool gguf_portable_vulkan(uint32_t vendor, bool android) {
    return android && vendor == 0x5143;
}
