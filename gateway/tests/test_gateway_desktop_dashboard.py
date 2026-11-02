import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.addons import load_addons, save_addons
from desktop.dashboard import number


class DesktopDashboardTests(unittest.TestCase):
    def test_enablement_preserves_private_configuration_and_rejects_stale_edits(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'mcp.json'
            original = {'mcpServers': {'search': {'command': 'server', 'args': ['--run'], 'env': {'KEY': 'secret'}}}, 'custom': True}
            path.write_text(json.dumps(original))
            config, digest = load_addons(path)
            updated = save_addons(path, digest, {'search': False})
            expected = original['mcpServers']['search'] | {'disabled': True}
            self.assertEqual(expected, json.loads(path.read_text())['mcpServers']['search'])
            self.assertTrue(json.loads(path.read_text())['custom'])
            with self.assertRaises(ValueError):
                save_addons(path, digest, {'search': True})
            save_addons(path, updated, {'search': True})
            self.assertFalse(json.loads(path.read_text())['mcpServers']['search']['disabled'])

    def test_malformed_configuration_and_unknown_service_never_write(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'mcp.json'
            path.write_text('{"mcpServers":[]}')
            with self.assertRaises(ValueError):
                load_addons(path)
            path.write_text('{"mcpServers":{}}')
            before = path.read_bytes()
            with self.assertRaises(ValueError):
                save_addons(path, hashlib.sha256(before).hexdigest(), {'unknown': True})
            self.assertEqual(before, path.read_bytes())

    def test_unknown_statistics_stay_unknown(self):
        self.assertEqual('Not measured', number(None))
        self.assertEqual('0', number(0))
        self.assertEqual('15,250', number(15250))


if __name__ == '__main__':
    unittest.main()
