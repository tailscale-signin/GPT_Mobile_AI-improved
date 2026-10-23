import copy
import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualify_device_performance import qualify


class DevicePerformanceQualificationTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {
            'context': {'build': {'device': 'reference', 'model': 'phone', 'fingerprint': 'build', 'version': {'sdk': 36}}},
            'validationConditions': {'deviceSerialHash': 'one-device', 'buildType': 'benchmark', 'workloadRevision': 'workload', 'animations': {'animator_duration_scale': '0'}},
            'benchmarks': [{'name': 'startup', 'metrics': {'timeToInitialDisplayMs': {'median': 100}}}],
        }

    def test_matched_measurements_pass_and_regression_fails(self):
        self.assertEqual([], qualify(self.baseline, copy.deepcopy(self.baseline)))
        current = copy.deepcopy(self.baseline)
        current['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['median'] = 130
        self.assertTrue(qualify(self.baseline, current))

    def test_different_conditions_or_missing_metrics_are_not_qualified(self):
        for field in self.baseline['validationConditions']:
            current = copy.deepcopy(self.baseline)
            current['validationConditions'][field] = 'different'
            with self.subTest(field=field), self.assertRaises(ValueError):
                qualify(self.baseline, current)
        current = copy.deepcopy(self.baseline)
        current['benchmarks'] = []
        with self.assertRaises(ValueError):
            qualify(self.baseline, current)
