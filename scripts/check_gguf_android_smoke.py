#!/usr/bin/env python3
"""Require real GGUF tests; compatible-device qualification cannot use skipped GPU tests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from check_native_memory_smoke import check_instrumentation


TEST_CLASS = "dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeInstrumentedTest"
CPU_TESTS = (
    "packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage",
    "packagedCpuRuntimeRendersCustomJinja",
)
GPU_TESTS = {
    "gpu": "packagedVulkanRuntimeGeneratesOnCompatibleDevice",
    "opencl": "packagedOpenClRuntimeGeneratesOnCompatibleDevice",
}


def required_backends(value):
    if value in ("", "none"):
        return ()
    backends = tuple(part.strip() for part in value.split(","))
    if len(backends) != len(set(backends)) or any(part not in GPU_TESTS for part in backends):
        raise ValueError("required backends must be none, gpu, opencl, or gpu,opencl")
    return backends


def physical_device_identity(path):
    if path is None:
        raise ValueError("Compatible GPU qualification requires captured physical-device properties")
    properties = dict(re.findall(r"^\[([^\]]+)\]: \[([^\]]*)\]$", path.read_text(), re.MULTILINE))
    names = ("ro.build.fingerprint", "ro.product.manufacturer", "ro.product.model", "ro.hardware", "ro.product.cpu.abi")
    if any(not properties.get(name) for name in names):
        raise ValueError("Compatible GPU qualification is missing device/build/ABI identity")
    if properties.get("ro.kernel.qemu") == "1" or properties.get("ro.boot.qemu") == "1" or properties["ro.hardware"] in {"ranchu", "goldfish", "cutf_cvm", "cuttlefish"}:
        raise ValueError("An emulator cannot qualify a physical-device GPU backend")
    return {name: properties[name] for name in names}


def check_results(output, backends=()):
    optional = {(TEST_CLASS, method) for backend, method in GPU_TESTS.items() if backend not in backends}
    for test in (*CPU_TESTS, *(GPU_TESTS[backend] for backend in backends)):
        check_instrumentation(output, TEST_CLASS, test, optional_assumptions=optional)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instrumentation_output", type=Path)
    parser.add_argument("--required-backends", default="none")
    parser.add_argument("--device-properties", type=Path)
    parser.add_argument("--status-output", type=Path)
    parser.add_argument("--source-revision")
    args = parser.parse_args(argv)
    status = {
        "schemaVersion": 1,
        "status": "NOT_QUALIFIED",
        "requestedBackendSelection": args.required_backends,
        "requiredBackends": [],
        "qualifiedBackends": [],
        "reason": "No compatible GPU qualification was requested.",
        "sourceRevision": args.source_revision,
        "cpuSmoke": "NOT_VERIFIED",
    }

    def write_status():
        if args.status_output:
            args.status_output.parent.mkdir(parents=True, exist_ok=True)
            args.status_output.write_text(json.dumps(status, indent=2, sort_keys=True) + "\n")

    # Overwrite any earlier success before reading new evidence. A missing or
    # aborted run must never leave a stale qualification receipt behind.
    write_status()
    try:
        backends = required_backends(args.required_backends)
        status["requiredBackends"] = list(backends)
        if backends:
            status["deviceIdentity"] = physical_device_identity(args.device_properties)
        check_results(args.instrumentation_output, backends)
        status["cpuSmoke"] = "PASSED"
        status["instrumentationSha256"] = hashlib.sha256(args.instrumentation_output.read_bytes()).hexdigest()
        if backends:
            status.update(
                status="QUALIFIED",
                qualifiedBackends=list(backends),
                reason="Requested GPU tests executed and passed offload, generation, repeated-request, and cancellation-recovery assertions.",
                passedGpuTests=[GPU_TESTS[backend] for backend in backends],
            )
        write_status()
        return 0
    except (OSError, UnicodeError, ValueError) as error:
        status["reason"] = str(error)
        write_status()
        print(f"GGUF qualification: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
