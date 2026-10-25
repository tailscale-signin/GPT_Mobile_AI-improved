"""Exercise the GGUF gate's required CPU tests and optional hardware probes."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_gguf_android_smoke.py"
CLASS = "dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeInstrumentedTest"
CPU_TESTS = (
    "packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage",
    "packagedCpuRuntimeRendersCustomJinja",
)
GPU_TESTS = (
    "packagedVulkanRuntimeGeneratesOnCompatibleDevice",
    "packagedOpenClRuntimeGeneratesOnCompatibleDevice",
)
DEVICE_PROPERTIES = """[ro.build.fingerprint]: [vendor/device/build:16/revision/release-keys]
[ro.product.manufacturer]: [Device Vendor]
[ro.product.model]: [Compatible Physical Device]
[ro.hardware]: [qcom]
[ro.product.cpu.abi]: [arm64-v8a]
[ro.kernel.qemu]: [0]
"""


def packet(method, code):
    return (
        f"INSTRUMENTATION_STATUS: class={CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {code}\n"
    )


class GgufAndroidSmokeTest(unittest.TestCase):
    def run_gate(self, results, backends="none", properties=DEVICE_PROPERTIES, ending="INSTRUMENTATION_CODE: -1\n"):
        output = "".join(packet(method, 1) + packet(method, code) for method, code in results)
        output += ending
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "instrumentation.txt"
            path.write_text(output)
            status = Path(directory) / "status.json"
            status.write_text('{"status":"QUALIFIED","qualifiedBackends":["gpu","opencl"]}')
            args = [sys.executable, str(SCRIPT), str(path), "--required-backends", backends, "--status-output", str(status)]
            if properties is not None:
                device = Path(directory) / "getprop.txt"
                device.write_text(properties)
                args.extend(["--device-properties", str(device)])
            result = subprocess.run(args, capture_output=True, text=True)
            result.status = json.loads(status.read_text())
            return result

    def test_unavailable_gpu_backends_do_not_fail_real_cpu_inference(self):
        result = self.run_gate([(test, 0) for test in CPU_TESTS] + [(test, -4) for test in GPU_TESTS])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, result.stdout.count("executed and passed"))
        self.assertEqual("NOT_QUALIFIED", result.status["status"])
        self.assertEqual([], result.status["qualifiedBackends"])

    def test_unrequested_gpu_success_is_still_not_a_qualification(self):
        result = self.run_gate([(test, 0) for test in (*CPU_TESTS, *GPU_TESTS)])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", result.status["cpuSmoke"])
        self.assertEqual("NOT_QUALIFIED", result.status["status"])

    def test_compatible_backend_requires_its_actual_pass_not_assumption_or_missing_test(self):
        for backend, test in zip(("gpu", "opencl"), GPU_TESTS):
            for terminal in (None, -4, -2):
                with self.subTest(backend=backend, terminal=terminal):
                    results = [(method, 0) for method in CPU_TESTS]
                    if terminal is not None:
                        results.append((test, terminal))
                    result = self.run_gate(results, backends=backend)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("NOT_QUALIFIED", result.status["status"])
                    self.assertEqual([], result.status["qualifiedBackends"])

    def test_explicit_single_backend_pass_allows_other_capability_skip(self):
        for backend, method in zip(("gpu", "opencl"), GPU_TESTS):
            with self.subTest(backend=backend):
                result = self.run_gate([(test, 0) for test in CPU_TESTS] + [(test, 0 if test == method else -4) for test in GPU_TESTS], backends=backend)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("QUALIFIED", result.status["status"])
                self.assertEqual([backend], result.status["qualifiedBackends"])
                self.assertEqual([method], result.status["passedGpuTests"])
                self.assertEqual(64, len(result.status["instrumentationSha256"]))

    def test_both_compatible_backends_must_pass_and_complete(self):
        passes = [(test, 0) for test in (*CPU_TESTS, *GPU_TESTS)]
        qualified = self.run_gate(passes, backends="gpu,opencl")
        self.assertEqual(0, qualified.returncode, qualified.stderr)
        self.assertEqual(["gpu", "opencl"], qualified.status["qualifiedBackends"])
        for results, ending in ((passes[:-1], "INSTRUMENTATION_CODE: -1\n"), (passes, ""), (passes, "INSTRUMENTATION_ABORTED: worker crashed\n")):
            with self.subTest(results=results, ending=ending):
                result = self.run_gate(results, backends="gpu,opencl", ending=ending)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("NOT_QUALIFIED", result.status["status"])

    def test_missing_identity_and_emulator_evidence_cannot_qualify(self):
        passes = [(test, 0) for test in (*CPU_TESTS, *GPU_TESTS)]
        for properties in (None, "", DEVICE_PROPERTIES.replace("[ro.kernel.qemu]: [0]", "[ro.kernel.qemu]: [1]"), DEVICE_PROPERTIES.replace("[ro.hardware]: [qcom]", "[ro.hardware]: [ranchu]")):
            with self.subTest(properties=properties):
                result = self.run_gate(passes, backends="gpu", properties=properties)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("NOT_QUALIFIED", result.status["status"])

    def test_unknown_backend_cannot_silently_become_optional(self):
        result = self.run_gate([(test, 0) for test in (*CPU_TESTS, *GPU_TESTS)], backends="vulakn")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("NOT_QUALIFIED", result.status["status"])

    def test_gpu_execution_failures_remain_fatal(self):
        for backend in GPU_TESTS:
            for code in (-1, -2, -3):
                with self.subTest(backend=backend, code=code):
                    result = self.run_gate([(test, 0) for test in CPU_TESTS] + [(backend, code)])
                    self.assertNotEqual(0, result.returncode)

    def test_both_cpu_tests_must_execute_without_skipping(self):
        for required in CPU_TESTS:
            with self.subTest(required=required):
                missing = self.run_gate([(test, 0) for test in CPU_TESTS if test != required])
                skipped = self.run_gate([(test, -4 if test == required else 0) for test in CPU_TESTS])
                self.assertNotEqual(0, missing.returncode)
                self.assertNotEqual(0, skipped.returncode)


if __name__ == "__main__":
    unittest.main()
