"""Native recognition preparation must remain pinned, bounded and required by CI."""

import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "local-kokoro-runtime/src/main/cpp"
SDK_MANIFEST = ROOT / "docs/audits/qnn-runtime-candidate.json"
HOST_MANIFEST = ROOT / "docs/audits/qnn-host-probe-publisher-2.8.json"

sys.path.insert(0, str(ROOT / "scripts"))
import prepare_kokoro_native_host as kokoro_host


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


headers = load("asr_headers", NATIVE / "prepare_asr_qnn_headers.py")
dependencies = load("asr_dependencies", NATIVE / "prepare_streaming_asr_dependencies.py")
host = load("asr_host", ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py")


class Response(io.BytesIO):
    def __init__(self, data, status=206, content_range="bytes 0-7/16"):
        super().__init__(data)
        self.status = status
        self.headers = {"Content-Range": content_range}


class StreamingAsrInputsTest(unittest.TestCase):
    def test_remote_sdk_reads_exact_ranges_and_never_downloads_the_whole_archive(self):
        data = b"abcdefghijklmnop"
        ranges = []

        def request(value, timeout):
            bounds = value.get_header("Range")
            ranges.append(bounds)
            start, stop = map(int, bounds.removeprefix("bytes=").split("-"))
            return Response(data[start:stop + 1], content_range=f"bytes {start}-{stop}/16")

        stream = headers.RemoteZip("https://example.invalid/sdk.zip", len(data))
        stream.block_size = 8
        with patch.object(headers.urllib.request, "urlopen", side_effect=request):
            stream.seek(5)
            self.assertEqual(b"fghijk", stream.read(6))
            stream.seek(7)
            self.assertEqual(b"hij", stream.read(3))
        self.assertEqual(["bytes=0-7", "bytes=8-15"], ranges)

    def test_ignored_mislabeled_or_truncated_sdk_ranges_fail(self):
        for response in (Response(b"abcdefgh", status=200), Response(b"abcdefgh", content_range="bytes 1-8/16"), Response(b"abc")):
            stream = headers.RemoteZip("https://example.invalid/sdk.zip", 16)
            stream.block_size = 8
            with self.subTest(status=response.status, headers=response.headers), patch.object(headers.urllib.request, "urlopen", return_value=response):
                with self.assertRaisesRegex(ValueError, "range"):
                    stream.read(4)

    def test_offline_qnn_headers_cannot_fall_back_to_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(headers.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                with self.assertRaisesRegex(ValueError, "missing or differ"):
                    headers.prepare(SDK_MANIFEST, root / "headers", offline=True)

    def test_verified_header_cache_is_readable_without_opening_private_staging(self):
        version = "2.52.0.261104"
        relative = "include/QNN/QnnInterface.h"
        payload = b"verified fixture header"
        pin = hashlib.sha256(relative.encode() + b"\0" + payload).hexdigest()
        record = {"dispatchBuild": {"qairtSdkVersion": version},
                  "qairtSdk": {"resolvedUrl": "https://example.invalid/sdk.zip",
                               "bytes": 1, "qnnHeadersSha256": pin}}
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr(f"qairt/{version}/{relative}", payload)
        archive.seek(0)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "published"
            real_copy = headers.shutil.copytree

            def observe_copy(staged, target, *args, **kwargs):
                if Path(target) == output:
                    self.assertEqual(0o700, stat.S_IMODE(Path(staged).stat().st_mode))
                return real_copy(staged, target, *args, **kwargs)

            with patch.object(headers, "sdk_manifest", return_value=record), \
                    patch.object(headers, "RemoteZip", return_value=archive), \
                    patch.object(headers.shutil, "copytree", side_effect=observe_copy):
                headers.prepare(SDK_MANIFEST, output)
            self.assertEqual(0o755, stat.S_IMODE(output.stat().st_mode))
            self.assertEqual(payload, (output / relative).read_bytes())
            self.assertEqual(pin, headers.headers_digest(output))
            with patch.object(headers, "sdk_manifest", return_value=record), \
                    patch.object(headers, "RemoteZip", side_effect=AssertionError("network forbidden")):
                headers.prepare(SDK_MANIFEST, output, offline=True)
            self.assertEqual([output], list(Path(temporary).iterdir()))

    def test_actual_sdk_manifest_matches_production_version_and_header_provenance(self):
        record = headers.sdk_manifest(SDK_MANIFEST)
        approved = json.loads((ROOT / "docs/audits/qualcomm-dispatch-inputs.json").read_text())
        for key in ("qairtSdkVersion", "qairtVersion", "qnnHeadersSha256"):
            self.assertEqual(approved[key], record["dispatchBuild"][key])
        self.assertEqual(approved["qnnHeadersSha256"], record["qairtSdk"]["qnnHeadersSha256"])
        cmake = (NATIVE / "streaming_asr.cmake").read_text()
        self.assertIn("asr-qairt-" + approved["qairtSdkVersion"], cmake)
        self.assertIn("docs/audits/qnn-runtime-candidate.json", cmake)
        self.assertEqual(json.loads(SDK_MANIFEST.read_text()), record)

    def test_host_flat_missing_and_mismatched_sdk_manifests_fail_before_cache_or_network(self):
        original = json.loads(SDK_MANIFEST.read_text())
        cases = [("host_wheel", json.loads(HOST_MANIFEST.read_text())),
                 ("flat_dispatch_pins", json.loads((ROOT / "docs/audits/qualcomm-dispatch-inputs.json").read_text())),
                 ("non_object", []), ("missing_sections", {})]
        mutations = (
            (("schemaVersion",), 2),
            (("dispatchBuild",), None),
            (("qairtSdk",), None),
            (("dispatchBuild", "qairtSdkVersion"), None),
            (("dispatchBuild", "qairtSdkVersion"), "2.52.0/../../untrusted"),
            (("dispatchBuild", "qairtVersion"), "2.51.0"),
            (("dispatchBuild", "qnnHeadersSha256"), "0" * 64),
            (("qairtSdk", "qnnHeadersSha256"), "not-a-digest"),
            (("qairtSdk", "sha256"), "not-a-digest"),
            (("qairtSdk", "bytes"), 0),
            (("qairtSdk", "bytes"), True),
            (("qairtSdk", "resolvedUrl"), None),
            (("qairtSdk", "resolvedUrl"), "https://publisher.invalid/2.51.0.1/v2.51.0.1.zip"),
            (("qairtSdk", "url"), original["qairtSdk"]["url"].replace("https:", "http:")),
            (("qairtSdk", "publisherChecksumSource"), "onnxruntime/onnxruntime-qnn@main:qcom/packages.yml"),
        )
        for keys, value in mutations:
            candidate = copy.deepcopy(original)
            target = candidate
            for key in keys[:-1]:
                target = target[key]
            target[keys[-1]] = value
            cases.append((".".join(keys) + "=" + str(value), candidate))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "invalid-sdk.json"
            for name, candidate in cases:
                manifest.write_text(json.dumps(candidate))
                with self.subTest(case=name), \
                        patch.object(headers, "headers_digest", return_value=original["qairtSdk"]["qnnHeadersSha256"]) as cached, \
                        patch.object(dependencies, "checked_download", side_effect=AssertionError("invalid manifests must fail before downloads")) as download, \
                        patch.object(headers.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                    with self.assertRaises(ValueError):
                        headers.prepare(manifest, root / "headers", offline=True)
                    with self.assertRaises(ValueError):
                        dependencies.prepare(root / "dependencies", manifest)
                    cached.assert_not_called()
                    download.assert_not_called()

    def test_valid_sdk_manifest_still_requires_the_complete_audited_header_tree(self):
        record = json.loads(SDK_MANIFEST.read_text())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            header = root / "include/QNN/QnnInterface.h"
            header.parent.mkdir(parents=True)
            header.write_text("corrupted cached header")
            self.assertNotEqual(record["qairtSdk"]["qnnHeadersSha256"], headers.headers_digest(root))
            with patch.object(headers.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                with self.assertRaisesRegex(ValueError, "missing or differ"):
                    headers.prepare(SDK_MANIFEST, root, offline=True)

    def test_complete_host_preparation_routes_distinct_sdk_and_host_manifests(self):
        observed = []

        def download(record, target):
            target.write_bytes(b"fixture archive")
            return target

        def asr_dependencies(output, manifest):
            self.assertEqual(SDK_MANIFEST, manifest)
            self.assertEqual(kokoro_host.QNN_SDK_MANIFEST, manifest)
            self.assertEqual("2.52.0.261104", headers.sdk_manifest(manifest)["dispatchBuild"]["qairtSdkVersion"])
            observed.append(("asr", manifest))

        def qnn_libraries(manifest, output):
            self.assertEqual(HOST_MANIFEST, manifest)
            self.assertEqual(kokoro_host.QNN_HOST_MANIFEST, manifest)
            self.assertEqual(1, json.loads(manifest.read_text())["schemaVersion"])
            observed.append(("host", manifest))

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "prepared"
            with patch.object(kokoro_host, "checked_download", side_effect=download), \
                    patch.object(kokoro_host, "native_preparer", return_value=SimpleNamespace(prepare=asr_dependencies)), \
                    patch.object(kokoro_host, "prepare_asr"), \
                    patch.object(kokoro_host, "extract_ort"), \
                    patch.object(kokoro_host, "extract_model"), \
                    patch.object(kokoro_host, "prepare_qnn", side_effect=qnn_libraries):
                kokoro_host.prepare(output)
            self.assertEqual(["asr", "host"], [kind for kind, _ in observed])
            receipt = json.loads((output / "inputs.json").read_text())
            self.assertEqual(kokoro_host.digest(observed[0][1]), receipt["qnnSdkManifestSha256"])
            self.assertEqual(kokoro_host.digest(observed[1][1]), receipt["qnnPublisherManifestSha256"])
            self.assertFalse(receipt["modelExecuted"])
            self.assertFalse(receipt["physicalDeviceQualified"])

    def test_failed_dependency_download_preserves_existing_cache_bytes(self):
        good = b"verified archive"
        record = {"bytes": len(good), "sha256": hashlib.sha256(good).hexdigest(), "url": "https://example.invalid/model"}
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "archive"
            target.write_bytes(b"existing")
            for data in (b"corrupt", b"x" * (len(good) + 1)):
                with self.subTest(data=data), patch.object(dependencies.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                    with self.assertRaisesRegex(ValueError, "pin"):
                        dependencies.checked_download(record, target)
                self.assertEqual(b"existing", target.read_bytes())
            with patch.object(dependencies.urllib.request, "urlopen", return_value=io.BytesIO(good)):
                dependencies.checked_download(record, target)
            with patch.object(dependencies.urllib.request, "urlopen", side_effect=AssertionError("verified cache must be reusable offline")):
                dependencies.checked_download(record, target)
            self.assertEqual(good, target.read_bytes())
            self.assertEqual([target], list(Path(temporary).iterdir()))

    def test_real_jni_fixtures_use_current_catalog_pins_and_immutable_revisions(self):
        fixtures = json.loads((ROOT / "local-kokoro-runtime/src/testHost/streaming_asr_fixtures.json").read_text())["fixtures"]
        self.assertEqual({"zipformer-en", "zipformer-fr", "nemotron-cpu"}, {fixture["id"] for fixture in fixtures})
        catalog = (ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceRecognitionModelCatalog.kt").read_text()
        for fixture in fixtures:
            self.assertTrue(fixture["expectedPrefix"].strip())
            for record in fixture["files"]:
                self.assertRegex(record["url"], r"/resolve/[0-9a-f]{40}/")
                self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
                if not record["file"].startswith("test_wavs/"):
                    self.assertIn(record["sha256"], catalog)
                    self.assertIn('"' + record["file"] + '"', catalog)

    def test_production_jni_execution_is_part_of_the_required_native_gate(self):
        driver = (ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py").read_text()
        self.assertIn('"dev.melo.kokoro.StreamingAsrNativeIntegration"', driver)
        self.assertIn('results[label] = run(label,', driver)
        self.assertIn("results[label] = 78", driver)
        self.assertIn('"asr_fixture_sha256": asr_input_pins', driver)
        self.assertIn("all(code == 0 for code in results.values())", driver)
        fixture_java = (ROOT / "local-kokoro-runtime/src/test/java/dev/melo/kokoro/StreamingAsrNativeIntegration.java").read_text()
        self.assertIn("model.accept(", fixture_java)
        self.assertIn("Arrays.equals(batched.timestampsSeconds, streamed.timestampsSeconds)", fixture_java)
        self.assertIn("CPU_HOST_PRODUCTION_JNI_REGRESSION_ONLY", fixture_java)


class NativeCTestEvidenceTest(unittest.TestCase):
    def check(self, cases):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "ctest-results.xml"
            report.write_text("<testsuite>" + "".join(cases) + "</testsuite>")
            return host.checked_native_ctest(report)

    def cases(self):
        return {name: f'<testcase name="{name}" status="run" />' for name in host.REQUIRED_NATIVE_TESTS}

    def test_complete_executed_native_suite_is_bound_to_exact_required_names(self):
        result = self.check(self.cases().values())
        self.assertEqual(sorted(host.REQUIRED_NATIVE_TESTS), result["executed_tests"])
        self.assertEqual(sorted(host.REQUIRED_NATIVE_TESTS), result["required_tests"])
        self.assertEqual(64, len(result["junit_sha256"]))

    def test_empty_or_nonempty_incomplete_suites_fail(self):
        with self.assertRaisesRegex(ValueError, "did not execute"):
            self.check([])
        for missing in host.REQUIRED_NATIVE_TESTS:
            cases = self.cases()
            del cases[missing]
            cases["unrelated"] = '<testcase name="unrelated" status="run" />'
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "did not execute"):
                self.check(cases.values())

    def test_failure_skip_duplicate_or_unexecuted_native_cases_fail(self):
        name = "speech_asr_checked_qnn_retirement"
        outcomes = [f'<testcase name="{name}" status="run"><{tag}/></testcase>'
                    for tag in ("failure", "error", "skipped")]
        outcomes += [f'<testcase name="{name}" status="notrun" />', f'<testcase name="{name}" />']
        for outcome in outcomes:
            cases = self.cases()
            cases[name] = outcome
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                self.check(cases.values())
        cases = list(self.cases().values())
        with self.assertRaisesRegex(ValueError, "duplicated"):
            self.check(cases + [cases[0]])
        with self.assertRaisesRegex(ValueError, "Malformed"):
            self.check(["<broken>"])


class StreamingAsrMemoryAdmissionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures = json.loads((ROOT / "local-kokoro-runtime/src/testHost/streaming_asr_fixtures.json").read_text())["fixtures"]
        cls.fixture = next(fixture for fixture in fixtures if fixture["id"] == "nemotron-cpu")
        cls.required = 2_583_516_980

    def files(self, available_mib=12_000, swap_mib=0):
        mib = 1024 * 1024
        return {
            "/proc/meminfo": f"MemTotal: {16_384 * 1024} kB\nMemFree: {16_000 * 1024} kB\nMemAvailable: {available_mib * 1024} kB\nSwapTotal: {swap_mib * 1024} kB\nSwapFree: {swap_mib * 1024} kB",
            "/proc/self/cgroup": "0::/actions/job",
            "/proc/self/mountinfo": "31 24 0:25 / /sys/fs/cgroup rw - cgroup2 cgroup rw",
            "/sys/fs/cgroup/cgroup.controllers": "memory cpu",
            "/sys/fs/cgroup/memory.current": str(500 * mib),
            "/sys/fs/cgroup/actions/memory.max": "max",
            "/sys/fs/cgroup/actions/memory.current": str(400 * mib),
            "/sys/fs/cgroup/actions/job/memory.max": "max",
            "/sys/fs/cgroup/actions/job/memory.current": str(300 * mib),
        }

    def test_exact_model_budget_uses_shared_observations_without_changing_compile_policy(self):
        files = self.files()
        used = int(files["/sys/fs/cgroup/actions/job/memory.current"])
        files["/sys/fs/cgroup/actions/job/memory.max"] = str(used + self.required)
        before = host.host_memory.observe(files.get)
        result = host.asr_memory_admission(self.fixture, files.get)
        self.assertEqual("INSUFFICIENT", before["status"])
        self.assertEqual("SUFFICIENT", result["status"])
        self.assertTrue(result["admitted"])
        self.assertEqual(self.required, result["estimatedRequiredBytes"])
        self.assertEqual(self.required, result["availableEffectiveBytes"])
        self.assertEqual(12_000 * 1024 * 1024, result["availableHostBytes"])
        for key in ("ram", "cgroups", "swapCountsAsHeadroom"):
            self.assertEqual(before[key], result[key])
        self.assertEqual(before["availableBytes"], result["availableEffectiveBytes"])
        self.assertEqual(before, host.host_memory.observe(files.get))
        self.assertEqual(8960 * 1024 * 1024, host.host_memory.REQUIRED_BYTES)
        self.assertFalse(result["physicalDeviceQualified"])
        self.assertFalse(result["isMeasuredModelRss"])

    def test_finite_leaf_one_byte_short_is_not_replaced_by_swap(self):
        files = self.files(swap_mib=64_000)
        used = int(files["/sys/fs/cgroup/actions/job/memory.current"])
        files["/sys/fs/cgroup/actions/job/memory.max"] = str(used + self.required - 1)
        result = host.asr_memory_admission(self.fixture, files.get)
        self.assertEqual("INSUFFICIENT", result["status"])
        self.assertEqual(self.required - 1, result["availableEffectiveBytes"])
        self.assertEqual(12_000 * 1024 * 1024, result["availableHostBytes"])
        self.assertFalse(result["admitted"])
        self.assertFalse(result["swapCountsAsHeadroom"])

    def test_low_available_ram_is_not_replaced_by_free_ram_or_swap(self):
        result = host.asr_memory_admission(self.fixture, self.files(available_mib=2000, swap_mib=64_000).get)
        self.assertEqual("INSUFFICIENT", result["status"])
        self.assertEqual(2000 * 1024 * 1024, result["availableHostBytes"])
        self.assertEqual(result["availableHostBytes"], result["availableEffectiveBytes"])
        self.assertEqual(64_000 * 1024 * 1024, result["ram"]["SwapFree"])
        self.assertFalse(result["admitted"])
        self.assertFalse(result["swapCountsAsHeadroom"])

    def test_finite_ancestor_constrains_unlimited_and_inheriting_child(self):
        for inherited in (False, True):
            with self.subTest(inherited=inherited):
                files = self.files()
                used = int(files["/sys/fs/cgroup/actions/memory.current"])
                files["/sys/fs/cgroup/actions/memory.max"] = str(used + self.required - 1)
                if inherited:
                    files.pop("/sys/fs/cgroup/actions/job/memory.max")
                    files["/sys/fs/cgroup/actions/cgroup.subtree_control"] = "cpu"
                result = host.asr_memory_admission(self.fixture, files.get)
                self.assertEqual("INSUFFICIENT", result["status"])
                self.assertEqual(self.required - 1, result["availableEffectiveBytes"])
                self.assertEqual(12_000 * 1024 * 1024, result["availableHostBytes"])
                self.assertFalse(result["admitted"])

    def test_missing_inputs_fail_unknown_despite_large_ram(self):
        for missing in ("/proc/meminfo", "/proc/self/cgroup", "/proc/self/mountinfo",
                        "/sys/fs/cgroup/actions/job/memory.current"):
            with self.subTest(missing=missing):
                files = self.files()
                files.pop(missing)
                result = host.asr_memory_admission(self.fixture, files.get)
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["availableEffectiveBytes"])
                self.assertEqual(None if missing == "/proc/meminfo" else 12_000 * 1024 * 1024, result["availableHostBytes"])
                self.assertEqual(self.required, result["estimatedRequiredBytes"])
                self.assertFalse(result["admitted"])

    def test_hidden_or_unreadable_ancestry_fails_without_exposing_host_details(self):
        files = self.files()
        files["/proc/self/mountinfo"] = "31 24 0:25 /actions /sys/fs/cgroup rw - cgroup2 cgroup rw"
        hidden = host.asr_memory_admission(self.fixture, files.get)
        files = self.files()

        def unreadable(path):
            if path.endswith("/memory.max"):
                raise PermissionError("private-runner-canary")
            return files.get(path)

        denied = host.asr_memory_admission(self.fixture, unreadable)
        for result in (hidden, denied):
            with self.subTest(observation=result):
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["availableEffectiveBytes"])
                self.assertEqual(12_000 * 1024 * 1024, result["availableHostBytes"])
                self.assertFalse(result["admitted"])
                self.assertNotIn("private-runner-canary", json.dumps(result))
                self.assertNotIn("/actions", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
