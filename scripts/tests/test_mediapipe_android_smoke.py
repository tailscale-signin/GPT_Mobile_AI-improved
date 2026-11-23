from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import check_mediapipe_android_smoke as smoke


RUN_ID = "12345678-1234-4234-9234-123456789abc"
SOURCE = "b" * 40


def native_receipt(engine="phonon", run_id=RUN_ID):
    model = smoke.MODELS[engine]
    return {
        "schema": "mediapipe-android-jni-smoke-v1",
        "scope": "android_jni_checkpoint_smoke",
        "status": "passed",
        "engine": engine.upper(),
        "runtime": "mediapipe-tasks-audio-1.2.3",
        "run_id": run_id,
        "model_sha256": model["sha256"],
        "model_bytes": model["bytes"],
        "requested_delegate": "CPU",
        "sample_rate_hz": 24000,
        "utf8_estimated_cue_observed": True,
        "abis": "x86_64,x86",
        "frames": 72000,
        "recovered_frames": 72000,
        "emitted_chunks": 3,
        "prepare_ms": 1000,
        "first_pcm_ms": 200,
        "cancel_before_first_read": "end_of_stream",
        "cancel_after_first_chunk": "native_cancelled",
    }


def packets(receipt, *, code=0):
    identity = f"INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}\nINSTRUMENTATION_STATUS: test={smoke.TEST_METHOD}\n"
    return (
        identity + "INSTRUMENTATION_STATUS_CODE: 1\n"
        + smoke.RECEIPT_PREFIX + json.dumps(receipt) + "\nINSTRUMENTATION_STATUS_CODE: 2\n"
        + identity + f"INSTRUMENTATION_STATUS_CODE: {code}\n"
        + "INSTRUMENTATION_CODE: -1\n"
    )


def fixture(directory, engine="phonon", source=SOURCE):
    apks = directory / "apks"
    apks.mkdir()
    manifest = {"schema": "native-smoke-apks-v1", "source_revision": source, "apks": {}}
    # Controlled binary identities test the transport gate, not APK execution.
    for name in ("app-x86_64.apk", "test.apk"):
        data = ("fixture-" + name).encode()
        (apks / name).write_bytes(data)
        manifest["apks"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    (apks / "manifest.json").write_text(json.dumps(manifest))
    evidence = directory / "evidence"
    evidence.mkdir()
    provisioning = {
        "schema": "mediapipe-speech-checkpoint-smoke-v1",
        "engine": engine,
        "runtime_version": "1.2.3",
        "model": smoke.MODELS[engine],
        "status": "model_verified",
        "inference_executed": False,
    }
    (evidence / "provisioning.json").write_text(json.dumps(provisioning))
    (evidence / "instrumentation.txt").write_text(packets(native_receipt(engine)))
    return apks, evidence


class MediaPipeAndroidReceiptTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.apks, self.evidence = fixture(self.directory)
        self.output = self.evidence / "instrumentation.txt"

    def check(self, content, engine="phonon"):
        self.output.write_text(content)
        with redirect_stdout(io.StringIO()):
            return smoke.check_results(self.output, engine, RUN_ID)

    def test_each_engine_requires_its_named_test_and_exact_checkpoint(self):
        for engine in ("kokoro", "phonon", "pocket"):
            with self.subTest(engine=engine):
                self.assertEqual(engine.upper(), self.check(packets(native_receipt(engine)), engine)["engine"])
        self.check(packets(native_receipt()).replace("\n", "\r\n"))

    def test_empty_skipped_aborted_and_incomplete_tests_cannot_pass(self):
        valid = packets(native_receipt())
        invalid = [
            "INSTRUMENTATION_CODE: -1\n",
            valid.replace(smoke.TEST_METHOD, "anotherTest"),
            valid.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -4"),
            valid.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2"),
            valid.replace("INSTRUMENTATION_CODE: -1\n", ""),
            valid + "INSTRUMENTATION_ABORTED: Native process crashed\n",
            valid + "INSTRUMENTATION_CODE: -1\n",
        ]
        for content in invalid:
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.check(content)

    def test_missing_duplicate_and_malformed_native_receipts_cannot_pass(self):
        valid = packets(native_receipt())
        line = next(line for line in valid.splitlines() if line.startswith(smoke.RECEIPT_PREFIX))
        invalid = [
            valid.replace(line + "\n", ""),
            valid.replace(line, line + "\n" + line),
            valid.replace(line, smoke.RECEIPT_PREFIX + "{bad"),
            valid.replace(line, smoke.RECEIPT_PREFIX + '{"status":"passed","status":"failed"}'),
            valid.replace(line, smoke.RECEIPT_PREFIX + '{"first_pcm_ms":NaN}'),
        ]
        for content in invalid:
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.check(content)

    def test_wrong_identity_or_missing_pcm_cancellation_recovery_evidence_fails(self):
        changes = {
            "run_id": "87654321-1234-4234-9234-123456789abc",
            "engine": "KOKORO",
            "runtime": "different-runtime",
            "model_sha256": "0" * 64,
            "model_bytes": 1,
            "requested_delegate": "GPU",
            "sample_rate_hz": 16000,
            "utf8_estimated_cue_observed": False,
            "abis": "arm64-v8a",
            "frames": 0,
            "recovered_frames": 0,
            "emitted_chunks": 0,
            "prepare_ms": -1,
            "first_pcm_ms": True,
            "cancel_before_first_read": "failed",
            "cancel_after_first_chunk": "unrelated_native_error",
        }
        for key, value in changes.items():
            with self.subTest(field=key), self.assertRaises(ValueError):
                self.check(packets(native_receipt() | {key: value}))
        for key in native_receipt():
            changed = native_receipt()
            del changed[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                self.check(packets(changed))

    def test_provisioning_requires_verified_pinned_model_without_inference_claim(self):
        path = self.evidence / "provisioning.json"
        valid = json.loads(path.read_text())
        smoke.verify_provisioning(path, "phonon")
        for field, value in (("engine", "kokoro"), ("status", "passed"), ("inference_executed", True), ("model", {})):
            path.write_text(json.dumps(valid | {field: value}))
            with self.subTest(field=field), self.assertRaises((ValueError, RuntimeError)):
                smoke.verify_provisioning(path, "phonon")

    def test_apks_are_bound_to_source_size_and_content_before_installation(self):
        smoke.verify_apks(self.apks, SOURCE)
        with self.assertRaisesRegex(ValueError, "source commit"):
            smoke.verify_apks(self.apks, "c" * 40)
        path = self.apks / "app-x86_64.apk"
        original = path.read_bytes()
        path.write_bytes(b"x" * len(original))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            smoke.verify_apks(self.apks, SOURCE)
        path.write_bytes(original + b"x")
        with self.assertRaisesRegex(ValueError, "size"):
            smoke.verify_apks(self.apks, SOURCE)

    def run_checker(self, process_exit=0):
        arguments = [
            "--engine", "phonon", "--instrumentation-output", str(self.output),
            "--provisioning-receipt", str(self.evidence / "provisioning.json"),
            "--apk-dir", str(self.apks), "--source-revision", SOURCE, "--run-id", RUN_ID,
            "--process-exit", str(process_exit), "--status-output", str(self.evidence / "status.json"),
        ]
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return smoke.main(arguments)

    def test_failed_process_or_missing_output_replaces_prior_success(self):
        status = self.evidence / "status.json"
        self.assertEqual(0, self.run_checker())
        self.assertEqual("PASSED", json.loads(status.read_text())["status"])
        self.assertEqual(1, self.run_checker(process_exit=124))
        failed = json.loads(status.read_text())
        self.assertEqual("NOT_VERIFIED", failed["status"])
        self.assertNotIn("native_receipt", failed)
        self.assertEqual(0, self.run_checker())
        self.output.unlink()
        self.assertEqual(1, self.run_checker())
        self.assertEqual("NOT_VERIFIED", json.loads(status.read_text())["status"])


class MediaPipeAndroidRunnerTest(unittest.TestCase):
    def run_fixture(self, *, engine="phonon", skipped=False, native_exit=0):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        apks, evidence = fixture(directory, engine, revision)
        models, binary = directory / "models", directory / "bin"
        models.mkdir()
        binary.mkdir()
        (models / smoke.MODELS[engine]["file_name"]).write_bytes(b"device hashing belongs to the real JNI test")
        adb = binary / "adb"
        adb.write_text(f"#!{sys.executable}\n" + textwrap.dedent('''\
            import json, os, sys
            from pathlib import Path
            arguments = sys.argv[1:]
            with Path(os.environ["SMOKE_TEST_ADB_CALLS"]).open("a") as log:
                log.write(json.dumps(arguments) + "\\n")
            if arguments[:3] == ["shell", "am", "instrument"]:
                options = {arguments[i + 1]: arguments[i + 2] for i, item in enumerate(arguments) if item == "-e"}
                expected = os.environ["SMOKE_TEST_IDENTITY"]
                assert options["class"] == expected
                assert options["mediapipeSmokeEngine"] == os.environ["SMOKE_TEST_ENGINE"]
                receipt = json.loads(os.environ["SMOKE_TEST_RECEIPT"])
                receipt["run_id"] = options["mediapipeSmokeRunId"]
                owner, method = expected.split("#")
                identity = f"INSTRUMENTATION_STATUS: class={owner}\\nINSTRUMENTATION_STATUS: test={method}\\n"
                print(identity + "INSTRUMENTATION_STATUS_CODE: 1")
                print("INSTRUMENTATION_STATUS: mediapipe_smoke_evidence=" + json.dumps(receipt))
                print("INSTRUMENTATION_STATUS_CODE: 2")
                code = -4 if os.environ["SMOKE_TEST_SKIPPED"] == "true" else 0
                print(identity + f"INSTRUMENTATION_STATUS_CODE: {code}")
                print("INSTRUMENTATION_CODE: -1")
                raise SystemExit(int(os.environ["SMOKE_TEST_NATIVE_EXIT"]))
            print("Controlled adb boundary")
            '''))
        adb.chmod(0o755)
        calls = directory / "adb-calls.jsonl"
        environment = dict(
            os.environ,
            PATH=str(binary) + os.pathsep + os.environ["PATH"],
            MEDIAPIPE_SMOKE_MODEL_DIR=str(models), MEDIAPIPE_SMOKE_APK_DIR=str(apks),
            MEDIAPIPE_SMOKE_EVIDENCE_DIR=str(evidence), SMOKE_TEST_ADB_CALLS=str(calls),
            SMOKE_TEST_IDENTITY=smoke.TEST_CLASS + "#" + smoke.TEST_METHOD,
            SMOKE_TEST_ENGINE=engine, SMOKE_TEST_RECEIPT=json.dumps(native_receipt(engine)),
            SMOKE_TEST_SKIPPED=str(skipped).lower(), SMOKE_TEST_NATIVE_EXIT=str(native_exit),
        )
        result = subprocess.run(["bash", "scripts/run_mediapipe_android_smoke.sh", engine], cwd=ROOT,
                                env=environment, text=True, capture_output=True, timeout=15)
        return result, evidence, [json.loads(line) for line in calls.read_text().splitlines()]

    def test_actual_runner_selects_each_named_engine_and_collects_bound_evidence(self):
        for engine in ("kokoro", "phonon", "pocket"):
            with self.subTest(engine=engine):
                result, evidence, calls = self.run_fixture(engine=engine)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                status = json.loads((evidence / "status.json").read_text())
                invocation = json.loads((evidence / "invocation.json").read_text())
                self.assertEqual("PASSED", status["status"])
                self.assertEqual(engine.upper(), status["native_receipt"]["engine"])
                self.assertEqual(invocation["run_id"], status["native_receipt"]["run_id"])
                self.assertNotEqual(RUN_ID, invocation["run_id"])
                self.assertTrue(any(call[:3] == ["shell", "am", "force-stop"] for call in calls))
                self.assertTrue(any(call[:3] == ["shell", "rm", "-f"] for call in calls))

    def test_skips_and_nonzero_adb_exit_fail_even_with_success_shaped_receipt(self):
        for options in ({"skipped": True}, {"native_exit": 124}):
            with self.subTest(options=options):
                result, evidence, _ = self.run_fixture(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("NOT_VERIFIED", json.loads((evidence / "status.json").read_text())["status"])

    def test_ci_reuses_one_minified_build_and_runs_exact_main_and_three_engines(self):
        workflow = (ROOT / ".github/workflows/native-memory-smoke.yml").read_text()
        self.assertRegex(workflow, r"on:\n  push:\n    branches: \[main\]")
        self.assertIn("engine: [kokoro, phonon, pocket]", workflow)
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("needs: smoke", workflow)
        self.assertEqual(1, workflow.count(":app:minifyNativeSmokeWithR8"))
        self.assertLess(workflow.index("python3 scripts/check_mediapipe_jni.py"), workflow.index("name: Share the single minified"))
        self.assertIn("digest-mismatch: error", workflow)
        self.assertIn("--download-only", workflow)
        test = (ROOT / "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/voice/MediaPipeSpeechNativeSmokeTest.kt").read_text()
        self.assertRegex(test, r"@Test\(timeout = 300_000\)\s+fun " + smoke.TEST_METHOD)
        keeps = (ROOT / "app/native-smoke-test-rules.pro").read_text()
        self.assertIn("-keep class " + smoke.TEST_CLASS + " { *; }", keeps)


if __name__ == "__main__":
    unittest.main()
