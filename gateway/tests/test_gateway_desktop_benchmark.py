import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from desktop import benchmark
from desktop.configuration import atomic_json, DEFAULTS


class DesktopBenchmarkTest(unittest.TestCase):
    def test_fixture_score_uses_exact_model_and_replaces_only_its_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            atomic_json(root / 'settings.json', DEFAULTS)
            progress = []
            replies = ('ORBIT-729', '489', '{"count":7,"active":true}', 'R03', 'Cedar-41')

            def run(model, outputs):
                outputs = iter(outputs)

                def fake_measure(post, origin, selected, turns, **options):
                    self.assertEqual(model, selected)
                    self.assertEqual('http://127.0.0.1:8090', origin)
                    self.assertIs(False, options['gateway_options']['store_conversation'])
                    return dict(passed=options['validate_output'](next(outputs)), usage=None,
                                backendTimings=None, firstContentMs=10, totalMs=20, maximumContentGapMs=2)

                live = {'live': True, 'instanceId': 'current', 'version': benchmark.VERSION}
                with patch.object(benchmark, 'get_json', side_effect=[live, {'data': [{'id': model}]}, live]), \
                        patch.object(benchmark, 'measure', side_effect=fake_measure):
                    return benchmark.run(root, model, progress.append)

            self.assertEqual(100, run('first', replies)['score'])
            self.assertEqual(80, run('second', (*replies[:-1], 'wrong'))['score'])
            self.assertEqual(80, run('first', (*replies[:-1], 'wrong'))['score'])
            self.assertEqual(15, len(progress))
            results = benchmark.scoreboard(root)
            self.assertEqual([('first', 80), ('second', 80)], [(row['model'], row['score']) for row in results])
            self.assertNotIn('ORBIT-729', json.dumps(results))

    def test_incomplete_transport_cannot_report_a_model_accuracy_score(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            atomic_json(root / 'settings.json', DEFAULTS)
            live = {'live': True, 'instanceId': 'current', 'version': benchmark.VERSION}
            with patch.object(benchmark, 'get_json', side_effect=[live, {'data': [{'id': 'model'}]}, live]), \
                    patch.object(benchmark, 'measure', side_effect=ValueError('broken stream')):
                result = benchmark.run(root, 'model', lambda _: None)
            self.assertIsNone(result['score'])
            self.assertIsNone(result['firstMs'])
            self.assertTrue(all(case.get('error') for case in result['cases']))

    def test_json_validation_distinguishes_integer_and_boolean(self):
        self.assertFalse(benchmark.matches('{"active":1,"count":7}', {'active': True, 'count': 7}))
        self.assertFalse(benchmark.matches('{"active":true,"count":7.0}', {'active': True, 'count': 7}))
