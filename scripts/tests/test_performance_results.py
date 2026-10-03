import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("performance", Path(__file__).resolve().parents[1] / "check_performance_results.py")
performance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(performance)


class PerformanceGateTest(unittest.TestCase):
    def fixture(self):
        return {"context": {"build": {"device": "device", "model": "model", "fingerprint": "build", "version": {"sdk": 37}}}, "benchmarks": [{"name": "streamedResponse", "metrics": {"timeToInitialDisplayMs": {"median": 100}}, "sampledMetrics": {"frameDurationCpuMs": {"P50": 7, "P95": 12}, "frameOverrunMs": {"P50": -5}}}]}

    def test_sampled_frame_regression_is_detected(self):
        baseline = self.fixture()
        current = copy.deepcopy(baseline)
        current["benchmarks"][0]["sampledMetrics"]["frameDurationCpuMs"]["P95"] = 20
        self.assertEqual(1, len(performance.check(baseline, current, 0.15)))

    def test_negative_overrun_improvement_is_not_a_regression(self):
        baseline = self.fixture()
        current = copy.deepcopy(baseline)
        current["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P50"] = -8
        self.assertEqual([], performance.check(baseline, current, 0.15))

    def test_unknown_device_and_mismatched_suites_fail_closed(self):
        for baseline, current in [({}, {}), (self.fixture(), {}), (self.fixture(), {**self.fixture(), "benchmarks": []})]:
            with self.assertRaises(ValueError):
                performance.check(baseline, current, 0.15)


if __name__ == "__main__":
    unittest.main()
