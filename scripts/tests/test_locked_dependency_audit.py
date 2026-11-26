import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_locked_dependencies import inventory, scan


class LockedDependencyAuditTest(unittest.TestCase):
    def test_all_ecosystems_include_transitive_dependencies(self):
        packages = inventory(Path(__file__).resolve().parents[2])
        self.assertEqual({'Maven', 'npm', 'PyPI'}, {row[0] for row in packages})
        names = {name for _, name, _ in packages}
        self.assertTrue({'androidx.sqlite:sqlite-android', 'com.android.tools.build:gradle', 'androidx.room:room-compiler'} <= names)

    def test_speech_runtime_and_smoke_pins_reach_the_advisory_query(self):
        root = Path(__file__).resolve().parents[2]
        expected = set()
        for path in ('scripts/requirements-voice-mediapipe-smoke.txt', 'gateway/requirements-voice.txt'):
            for line in (root / path).read_text().splitlines():
                if line and line[0].isalnum():
                    name, version = line.split()[0].split('==')
                    expected.add(('PyPI', name, version))
        self.assertTrue({'mediapipe', 'pillow', 'onnxruntime-gpu'} <= {name for _, name, _ in expected})
        self.assertGreaterEqual(len({version for _, name, version in expected if name == 'numpy'}), 2)
        pillow = next(package for package in expected if package[1] == 'pillow')
        for verified in (False, True):
            with self.subTest(verified_inventory=verified):
                queried = set()

                def query(batch):
                    results = []
                    for item in batch:
                        identity = (item['package']['ecosystem'], item['package']['name'], item['version'])
                        queried.add(identity)
                        results.append({'vulns': [{'id': 'TEST-SPEECH-PIN'}]} if identity == pillow else {})
                    return results

                matches = scan(inventory(root, verified_inventory=verified), query)
                self.assertTrue(expected <= queried, f'Speech pins were not queried: {sorted(expected - queried)}')
                self.assertEqual([(*pillow, 'TEST-SPEECH-PIN')], matches)

    def test_pagination_retains_package_identity(self):
        def query(batch):
            if len(batch) == 2:
                return [{}, {'vulns': [{'id': 'A'}], 'next_page_token': 'more'}]
            self.assertEqual('second', batch[0]['package']['name'])
            self.assertEqual('more', batch[0]['page_token'])
            return [{'vulns': [{'id': 'B'}]}]
        rows = scan([('npm', 'first', '1'), ('npm', 'second', '2')], query)
        self.assertEqual([('npm', 'second', '2', 'A'), ('npm', 'second', '2', 'B')], rows)

    def test_incomplete_response_cannot_pass(self):
        with self.assertRaises(ValueError):
            scan([('PyPI', 'example', '1')], lambda _: [])
