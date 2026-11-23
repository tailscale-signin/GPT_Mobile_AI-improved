import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import run_benchmark_native_controls as controls


class BenchmarkCompilerControlTests(unittest.TestCase):
    def test_complete_compiler_runtime_is_pinned_by_existing_dependency_verification(self):
        rows = controls.artifacts()
        self.assertEqual(8, len(rows))
        self.assertEqual({'kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-stdlib',
                          'kotlin-script-runtime', 'kotlin-daemon-embeddable', 'kotlin-reflect',
                          'kotlinx-coroutines-core-jvm', 'annotations'}, {row['name'] for row in rows})
        self.assertTrue(all(row['hashes'] for row in rows))

    def test_foreign_cached_compiler_bytes_cannot_be_executed_or_silently_replaced_offline(self):
        trusted = b'repository-reviewed artifact bytes'
        row = dict(group='example', name='compiler', version='1.0', filename='compiler-1.0.jar',
                   hashes={hashlib.sha256(trusted).hexdigest()})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = root / 'output'
            destination.mkdir()
            cached = root / 'cache/example/compiler/1.0/hash/compiler-1.0.jar'
            cached.parent.mkdir(parents=True)
            cached.write_bytes(b'unverified cache')
            with patch.object(controls.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'unavailable offline'):
                    controls.provision(row, destination, root / 'cache', offline=True)
                network.assert_not_called()
            self.assertFalse((destination / row['filename']).exists())
            cached.write_bytes(trusted)
            resolved = controls.provision(row, destination, root / 'cache', offline=True)
            self.assertEqual(trusted, resolved.read_bytes())


if __name__ == '__main__':
    unittest.main()
