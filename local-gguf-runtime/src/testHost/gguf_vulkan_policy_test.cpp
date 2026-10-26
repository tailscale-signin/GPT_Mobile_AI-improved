#include "gguf_vulkan_policy.h"
#include <cassert>
int main() {
    assert(gguf_portable_vulkan(0x5143, true));
    assert(!gguf_portable_vulkan(0x5143, false));
    assert(!gguf_portable_vulkan(0x10de, true));
    assert(!gguf_portable_vulkan(0x13b5, true));
    assert(!gguf_portable_vulkan(0, true));
}
