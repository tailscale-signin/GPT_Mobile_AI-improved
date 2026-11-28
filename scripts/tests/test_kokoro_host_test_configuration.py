"""Exercise production host-test configuration across a dependency cache override.

Only dependency/model construction is replaced. CMake executes the actual
caller option setup and complete host-test target block; no model is compiled.
"""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "local-kokoro-runtime/src/main/cpp"
CMAKE = os.environ.get("KOKORO_TEST_CMAKE") or shutil.which("cmake")
REQUIRED_TESTS = {
    "english_dictionary_misses",
    "speech_provider_execution_evidence",
    "speech_cache_audio_and_device_discovery",
    "speech_compiled_context_cache",
    "speech_finalization_cancellation",
    "speech_live_native_progress",
    "speech_convex_graph_partitions",
    "speech_provider_abi_ownership",
    "speech_asr_checked_qnn_retirement",
}
FIXTURE_TARGETS = {
    "streaming_asr_qnn_fixture",
    "streaming_asr_qnn_retirement_test",
    "streaming_asr_cancellation_fixture",
}


@unittest.skipUnless(CMAKE, "CMake is required for native configuration regressions")
class KokoroHostTestConfigurationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "native"
        self.build = self.root / "build"
        self.cmake = Path(CMAKE).resolve()
        shutil.copytree(NATIVE, self.source)
        production = (NATIVE / "CMakeLists.txt").read_text()
        prefix = production.split("include(FetchContent)", 1)[0]
        boundary = 'include("${CMAKE_CURRENT_SOURCE_DIR}/streaming_asr.cmake")'
        self.assertEqual(1, production.count(boundary))
        host_targets = production[production.index(boundary):]
        # This is the observed pinned Eigen dependency behavior. Its cache write
        # crosses function scopes, unlike a normal local BUILD_TESTING variable.
        (self.source / "streaming_asr.cmake").write_text(
            'function(add_streaming_asr)\n'
            '  set(BUILD_TESTING OFF CACHE BOOL "dependency test policy" FORCE)\n'
            '  add_library(sherpa-onnx-core INTERFACE)\n'
            'endfunction()\n'
            'add_streaming_asr()\n'
        )
        (self.source / "CMakeLists.txt").write_text(
            prefix
            + 'add_library(kokoro_pronunciation INTERFACE)\n'
            + 'set(sherpa_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")\n'
            + host_targets
            + '\nget_property(observed_targets DIRECTORY PROPERTY BUILDSYSTEM_TARGETS)\n'
            + 'file(WRITE "${CMAKE_BINARY_DIR}/host-targets.txt" "${observed_targets}")\n'
        )

    def run_command(self, command):
        return subprocess.run([str(value) for value in command], capture_output=True, text=True, timeout=30)

    def configure(self, *options):
        result = self.run_command([self.cmake, "-S", self.source, "-B", self.build, *options])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def observations(self):
        result = self.run_command([self.cmake.parent / "ctest", "--test-dir", self.build, "--show-only=json-v1"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        tests = {test["name"] for test in json.loads(result.stdout)["tests"]}
        targets = set((self.build / "host-targets.txt").read_text().split(";"))
        return tests, targets

    def test_dependency_cache_override_preserves_all_required_host_tests_and_reconfiguration(self):
        self.configure("-DBUILD_TESTING=ON")
        for reconfigure in (False, True):
            with self.subTest(reconfigure=reconfigure):
                if reconfigure:
                    self.configure()
                tests, targets = self.observations()
                self.assertEqual(REQUIRED_TESTS, tests)
                self.assertTrue(FIXTURE_TARGETS.issubset(targets), targets)
                self.assertIn("BUILD_TESTING:BOOL=ON", (self.build / "CMakeCache.txt").read_text())

    def test_explicit_host_opt_out_remains_disabled(self):
        self.configure("-DBUILD_TESTING=OFF")
        self.configure()
        tests, targets = self.observations()
        self.assertEqual(set(), tests)
        self.assertFalse(FIXTURE_TARGETS.intersection(targets))

    def test_android_does_not_register_host_tests_or_fixtures(self):
        self.configure("-DANDROID=ON", "-DBUILD_TESTING=ON")
        tests, targets = self.observations()
        self.assertEqual(set(), tests)
        self.assertFalse(FIXTURE_TARGETS.intersection(targets))

    def test_mandatory_driver_rejects_an_empty_ctest_configuration(self):
        self.configure("-DBUILD_TESTING=OFF")
        path = ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py"
        spec = importlib.util.spec_from_file_location("kokoro_host_configuration_driver", path)
        driver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(driver)
        result = self.run_command(driver.native_ctest_command(self.cmake, self.build))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No tests were found", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
