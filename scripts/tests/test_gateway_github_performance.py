import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualify_gateway_github_performance import qualify


class GatewayGitHubPerformanceTest(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.evidence = {"sourceSha": self.sha, "conditions": {"apkSha256": "b" * 64, "deviceSerialHash": "c" * 64,
                         "deviceModel": "fixture", "androidBuild": "fixture", "refreshRateHz": 120, "thermalState": "nominal",
                         "network": "fixture", "debugCapture": False, "workloadRevision": "fixture", "buildType": "benchmark"},
                         "navigation": [{"source": "fixture", "bootId": "fixture", "sequence": index, "cached": True, "origin": "NAVIGATION_ACTION", "clock": "SOURCE_MONOTONIC", "firstUsefulFrameMs": 250, "composerReadyMs": 450} for index in range(30)],
                         "frameWindows": [{"source": "fixture", "bootId": "fixture", "sequence": 1, "frames": 1000, "jankFrames": 5,
                                           "droppedSamples": 0, "stallsOver100Ms": 0, "stallsOver500Ms": 0, "fallbackDeadlines": 0}]}

    def test_exact_observed_workload_has_distributions_and_explicit_denominator(self):
        result = qualify(self.evidence, self.sha)
        self.assertEqual("PASSED_OBSERVED_WORKLOAD", result["status"])
        self.assertEqual(.005, result["measurements"]["frames"]["jankRate"])

    def test_missing_clock_origin_or_dropped_frames_cannot_become_a_pass(self):
        for mutate in [lambda data: data["navigation"].clear(),
                       lambda data: data["navigation"][0].update(origin="COMPOSITION_STARTED"),
                       lambda data: data["frameWindows"][0].update(droppedSamples=1),
                       lambda data: data["frameWindows"][0].update(fallbackDeadlines=1)]:
            value = copy.deepcopy(self.evidence)
            mutate(value)
            self.assertEqual("NOT_QUALIFIED", qualify(value, self.sha)["status"])

    def test_stale_source_duplicate_windows_and_regression_are_rejected(self):
        with self.assertRaises(ValueError):
            qualify(self.evidence, "d" * 40)
        self.evidence["frameWindows"] *= 2
        with self.assertRaises(ValueError):
            qualify(self.evidence, self.sha)
        self.evidence["frameWindows"] = self.evidence["frameWindows"][:1]
        self.evidence["frameWindows"][0].update(stallsOver100Ms=1, stallsOver500Ms=1)
        self.assertEqual("FAILED", qualify(self.evidence, self.sha)["status"])


if __name__ == "__main__":
    unittest.main()
