"""Exercise the GGUF gate's required CPU tests and optional hardware probes."""
from pathlib import Path
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


def packet(method, code):
    return (
        f"INSTRUMENTATION_STATUS: class={CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {code}\n"
    )


class GgufAndroidSmokeTest(unittest.TestCase):
    def run_gate(self, results):
        output = "".join(packet(method, 1) + packet(method, code) for method, code in results)
        output += "INSTRUMENTATION_CODE: -1\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "instrumentation.txt"
            path.write_text(output)
            return subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)

    def test_unavailable_gpu_backends_do_not_fail_real_cpu_inference(self):
        result = self.run_gate([(test, 0) for test in CPU_TESTS] + [(test, -4) for test in GPU_TESTS])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, result.stdout.count("executed and passed"))

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
