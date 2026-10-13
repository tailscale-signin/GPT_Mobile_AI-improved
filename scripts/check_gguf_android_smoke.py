#!/usr/bin/env python3
"""Require real GGUF execution in the minified Android app."""
import sys
from check_native_memory_smoke import check_instrumentation

check_instrumentation(sys.argv[1], "dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeInstrumentedTest", "packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage")
