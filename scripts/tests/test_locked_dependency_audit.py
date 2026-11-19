import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_locked_dependencies import inventory, scan


class LockedDependencyAuditTest(unittest.TestCase):
    def test_all_ecosystems_include_transitive_dependencies(self):
        packages = inventory(Path(__file__).resolve().parents[2])
        self.assertEqual({'Maven', 'npm', 'PyPI'}, {row[0] for row in packages})
        self.assertTrue(any(name == 'androidx.sqlite:sqlite-android' for _, name, _ in packages))

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
