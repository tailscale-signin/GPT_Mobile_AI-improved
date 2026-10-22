#include "gguf_prefill_governor.h"
#include <cassert>
int main() {
    GgufPrefillGovernor cpu(256, true);
    assert(cpu.selected(1000) == 8);
    for (int i = 0; i < 6; ++i) cpu.observed(900);
    assert(cpu.selected(1000) == 256);
    assert(cpu.selected(11) == 11);
    cpu.observed(4500);
    assert(cpu.selected(1000) == 128);
    for (int i = 0; i < 12; ++i) cpu.observed(5000);
    assert(cpu.selected(1000) == 1);
    GgufPrefillGovernor small(4, true);
    assert(small.selected(1000) == 4);
    GgufPrefillGovernor gpu(256, false);
    gpu.observed(10000);
    assert(gpu.selected(1000) == 256);
}
