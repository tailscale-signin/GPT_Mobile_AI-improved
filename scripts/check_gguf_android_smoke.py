#!/usr/bin/env python3
"""Require real GGUF execution in the minified Android app."""
import sys
from check_native_memory_smoke import check_instrumentation

test_class = "dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeInstrumentedTest"
check_instrumentation(
    sys.argv[1],
    test_class,
    "packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage",
    optional_assumptions={(test_class, "packagedVulkanRuntimeGeneratesOnCompatibleDevice")},
)
