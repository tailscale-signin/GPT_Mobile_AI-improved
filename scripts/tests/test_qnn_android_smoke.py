"""QNN qualification requires correlated physical evidence, never a configured backend/log flag."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check_qnn_android_smoke.py"
CLASS = "dev.chungjungsoo.gptmobile.data.localruntime.QualcommNpuInstrumentedTest"
METHOD = "matchingSm8750ModelGeneratesOnNpu"
MODEL = "a" * 64
SOURCE = "b" * 40
RUN = "qnn-123-2"
PROPERTIES = """[ro.build.fingerprint]: [vendor/phone/release]
[ro.product.manufacturer]: [Vendor]
[ro.product.model]: [Phone]
[ro.hardware]: [qcom]
[ro.product.cpu.abi]: [arm64-v8a]
[ro.soc.model]: [SM8750]
[ro.kernel.qemu]: [0]
"""


def instrumentation(code=0):
    return (f"INSTRUMENTATION_STATUS: class={CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\nINSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: class={CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\nINSTRUMENTATION_STATUS_CODE: {code}\n"
            "INSTRUMENTATION_CODE: -1\n")


def evidence():
    turns, releases = [], []
    for cold in range(2):
        for turn in range(2):
            begin = 10 + cold * 30 + turn * 10
            request, generation, engine = f"request-{cold}-{turn}", f"generation-{cold}-{turn}", f"engine-{cold}"
            execution = {
                "runtimeRequestId": request, "nativeGenerationId": generation, "workerGenerationId": "worker", "engineInstanceId": engine,
                "nativeEntered": True, "nativeReturned": True, "terminalKind": "sdk_done", "commandTerminalAcknowledged": True,
                "adapterInvokedAtElapsedMs": begin + 1, "nativeReturnedAtElapsedMs": begin + 2, "observedAtElapsedMs": begin + 3,
                "promptTokens": 5, "sampledTokens": 8,
                "configuration": {"generationId": generation, "engineInstanceId": engine},
                "engineLoad": {"actualBackend": "NPU", "requestedBackend": "npu", "nativeAcknowledgedFields": ["backend"], "artifactDigest": MODEL, "engineInstanceId": engine, "workerGenerationId": "worker", "receiptId": f"load-{cold}", "runtimeRevision": "pinned-sdk"},
            }
            cleanup = {"runtimeRequestId": request, "workerGenerationId": "worker", "engineInstanceId": engine, "obligationsObserved": True, "nativeClosureConfirmed": True, "conversationClosed": True, "engineHealthy": True, "startedAtElapsedMs": begin + 4, "observedAtElapsedMs": begin + 5}
            turns.append({"coldStart": cold, "turn": turn, "startedAtElapsedMs": begin, "finishedAtElapsedMs": begin + 6, "done": True, "nonemptyOutput": True, "visibleCharacters": 8, "outputSha256": "c" * 64, "errors": [], "execution": execution, "cleanup": cleanup})
        release = copy.deepcopy(cleanup)
        release.update(engineReleased=True, startedAtElapsedMs=30 + cold * 30, observedAtElapsedMs=31 + cold * 30)
        releases.append({"coldStart": cold, "cleanup": release, "releaseError": None})
    return {"schemaVersion": 1, "status": "NOT_QUALIFIED", "runId": RUN, "sourceRevision": SOURCE, "modelSha256": MODEL, "runtimeScope": "isolated_process", "coldStartScope": "engine_recreation", "startedAtElapsedMs": 0, "observedAtElapsedMs": 70, "collectionComplete": True, "issues": [], "device": {"fingerprint": "vendor/phone/release", "manufacturer": "Vendor", "model": "Phone", "abi": "arm64-v8a", "soc": "SM8750"}, "turns": turns, "engineReleases": releases}


class QnnAndroidSmokeTest(unittest.TestCase):
    def gate(self, data=None, output=None, properties=PROPERTIES, extra=()):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proof, packets, device, status = (root / name for name in ("evidence.json", "instrumentation.txt", "getprop.txt", "status.json"))
            proof.write_text(json.dumps(evidence() if data is None else data))
            packets.write_text(instrumentation() if output is None else output)
            device.write_text(properties)
            status.write_text('{"status":"QUALIFIED","qualifiedBackends":["npu"]}')
            result = subprocess.run([sys.executable, str(SCRIPT), str(packets), str(proof), "--model-sha256", MODEL, "--source-revision", SOURCE, "--run-id", RUN, "--device-properties", str(device), "--status-output", str(status), *extra], capture_output=True, text=True)
            result.status = json.loads(status.read_text())
            return result

    def rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual("NOT_QUALIFIED", result.status["status"])
        self.assertEqual([], result.status["qualifiedBackends"])

    def test_four_native_requests_and_both_releases_can_qualify_only_after_named_test_passes(self):
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("QUALIFIED", result.status["status"])
        self.assertEqual(2, result.status["engineColdStarts"])
        self.assertEqual(4, len(result.status["requestIds"]))
        self.assertEqual(64, len(result.status["evidenceSha256"]))

    def test_requested_npu_and_success_log_never_replace_actual_backend(self):
        for actual, acknowledged in ((None, ["backend"]), ("cpu", ["backend"]), ("NPU", [])):
            data = evidence()
            data["turns"][0]["execution"]["engineLoad"].update(actualBackend=actual, nativeAcknowledgedFields=acknowledged)
            result = self.gate(data)
            self.rejected(result)
            if actual is None:
                self.assertIn("ACTUAL_BACKEND_UNOBSERVED", result.status["reason"])
        self.rejected(self.gate(output="status=QUALIFIED nonempty=true backend=NPU coldStart=1\n"))

    def test_missing_native_entry_terminal_or_counters_do_not_qualify(self):
        for field, value in (("nativeEntered", None), ("nativeReturned", False), ("promptTokens", None), ("sampledTokens", 0), ("commandTerminalAcknowledged", False), ("terminalKind", "native_error"), ("deadlineKind", "timeout")):
            data = evidence()
            data["turns"][0]["execution"][field] = value
            self.rejected(self.gate(data))

    def test_stale_request_generation_and_cleanup_receipts_are_rejected(self):
        for field in ("runtimeRequestId", "nativeGenerationId"):
            data = evidence()
            data["turns"][1]["execution"][field] = data["turns"][0]["execution"][field]
            self.rejected(self.gate(data))
        for field in ("runtimeRequestId", "workerGenerationId", "engineInstanceId"):
            data = evidence()
            data["turns"][0]["cleanup"][field] = "other"
            self.rejected(self.gate(data))

    def test_unconfirmed_or_failed_cleanup_blocks_qualification(self):
        for field, value in (("obligationsObserved", False), ("nativeClosureConfirmed", None), ("conversationClosed", False), ("engineHealthy", None), ("quarantined", True), ("failureReason", "failed")):
            data = evidence()
            data["turns"][0]["cleanup"][field] = value
            self.rejected(self.gate(data))
        for action in ({"requiredActions": ["release_engine"]}, {"terminationRequested": True}):
            data = evidence()
            data["turns"][0]["cleanup"].update(action)
            self.rejected(self.gate(data))

    def test_malformed_nested_evidence_is_explicitly_unqualified(self):
        cases = [
            ("device", None), ("turns", [None] * 4), ("engineReleases", [None, None]),
            ("status", "QUALIFIED")
        ]
        for field, value in cases:
            data = evidence()
            data[field] = value
            result = self.gate(data)
            self.rejected(result)
            self.assertNotIn("Traceback", result.stderr)

    def test_missing_engine_release_and_success_written_before_cleanup_are_rejected(self):
        data = evidence()
        data["engineReleases"] = data["engineReleases"][:1]
        self.rejected(self.gate(data))
        for field, value in (("engineReleased", None), ("observedAtElapsedMs", 80)):
            data = evidence()
            data["engineReleases"][1]["cleanup"][field] = value
            self.rejected(self.gate(data))
        data = evidence()
        data["engineReleases"][0]["releaseError"] = "native_cleanup_failed"
        self.rejected(self.gate(data))

    def test_stale_run_commit_or_model_is_rejected(self):
        for field in ("runId", "sourceRevision", "modelSha256"):
            data = evidence()
            data[field] = "previous"
            self.rejected(self.gate(data))
        data = evidence()
        data["turns"][0]["execution"]["engineLoad"]["artifactDigest"] = "d" * 64
        self.rejected(self.gate(data))

    def test_skipped_aborted_failed_or_missing_test_is_never_accepted(self):
        for output in (instrumentation(-4), instrumentation(-2), instrumentation().replace("INSTRUMENTATION_CODE: -1\n", ""), "INSTRUMENTATION_ABORTED: worker died\n", ""):
            self.rejected(self.gate(output=output))
        self.rejected(self.gate(extra=("--instrumentation-exit-code", "124")))
        self.rejected(self.gate(extra=("--evidence-exit-code", "1")))

    def test_physical_device_identity_must_match(self):
        self.rejected(self.gate(properties=PROPERTIES.replace("[ro.kernel.qemu]: [0]", "[ro.kernel.qemu]: [1]")))
        self.rejected(self.gate(properties=PROPERTIES.replace("[SM8750]", "[SM8650]")))
        data = evidence()
        data["device"]["fingerprint"] = "other-phone"
        self.rejected(self.gate(data))

    def test_incomplete_or_declared_unqualified_evidence_remains_unqualified(self):
        data = evidence()
        data.update(collectionComplete=False, reason="model unavailable")
        self.rejected(self.gate(data))
        data = evidence()
        data["issues"] = ["ACTUAL_BACKEND_UNOBSERVED: SDK proof unavailable"]
        result = self.gate(data)
        self.rejected(result)
        self.assertIn("SDK proof unavailable", result.status["reason"])

    def test_request_windows_and_fresh_engine_pair_are_enforced(self):
        data = evidence()
        data["turns"][1]["startedAtElapsedMs"] = 10
        self.rejected(self.gate(data))
        data = evidence()
        for turn in data["turns"][2:]:
            turn["execution"]["engineInstanceId"] = "engine-0"
            turn["execution"]["configuration"]["engineInstanceId"] = "engine-0"
            turn["execution"]["engineLoad"]["engineInstanceId"] = "engine-0"
            turn["execution"]["engineLoad"]["receiptId"] = "load-0"
            turn["cleanup"]["engineInstanceId"] = "engine-0"
        self.rejected(self.gate(data))


if __name__ == "__main__":
    unittest.main()
