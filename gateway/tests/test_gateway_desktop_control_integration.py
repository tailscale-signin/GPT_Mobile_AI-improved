"""Single-authority legacy launch and explicit control-port firewall behavior."""
import json
import base64
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import firewall, phone_panel
from desktop import native_bridge
from desktop.configuration import atomic_json, settings_write_lock
from v14.state_lock import StateDirectoryAlreadyOwned


class ControlHostIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_control_firewall_uses_canonical_listener_and_separate_rule(self):
        atomic_json(self.root / 'data' / 'control-listener.json', {'host': '192.168.1.25', 'port': 8091})
        atomic_json(self.root / 'data' / 'phone-control' / 'listener.json', {'host': '192.168.1.99', 'port': 8443})
        with patch('desktop.firewall.os', SimpleNamespace(**dict(vars(os), name='nt'))), \
                patch.dict(os.environ, {'SystemRoot': str(self.root)}), \
                patch('desktop.firewall.ctypes.windll', SimpleNamespace(shell32=SimpleNamespace(IsUserAnAdmin=lambda: True)), create=True), \
                patch('desktop.firewall.subprocess.run') as run:
            run.return_value.returncode = 0
            firewall.apply(self.root, phone=True)
            command = run.call_args.args[0]
            self.assertEqual('8091', command[command.index('-Port') + 1])
            self.assertEqual('192.168.1.25', command[command.index('-BindAddress') + 1])
            self.assertEqual(firewall.rule_name(self.root) + '-phone', command[command.index('-RuleName') + 1])
            self.assertFalse((self.root / 'firewall.json').exists())
            self.assertEqual('LocalSubnet', json.loads((self.root / 'phone-firewall.json').read_text())['remote'])
            (self.root / 'data' / 'control-listener.json').unlink()
            firewall.apply(self.root, phone=True, remove=True)
            self.assertIn('-Remove', run.call_args.args[0])
            self.assertNotIn('-Port', run.call_args.args[0])
            self.assertFalse((self.root / 'phone-firewall.json').exists())

    def test_control_firewall_rejects_loopback_public_and_malformed_settings(self):
        for address, port in [('127.0.0.1', 8091), ('0.0.0.0', 8091), ('8.8.8.8', 8091), ('192.168.1.2', True)]:
            atomic_json(self.root / 'data' / 'control-listener.json', {'host': address, 'port': port})
            with self.subTest(address=address, port=port), self.assertRaises(ValueError):
                firewall.control_listener(self.root)
        atomic_json(self.root / 'data' / 'control-listener.json', {'host': '100.90.1.2', 'port': 8091})
        self.assertEqual('100.90.1.2', firewall.control_listener(self.root)['bind_host'])

    def test_legacy_owner_button_launches_the_canonical_native_page_without_reenrollment(self):
        native = Mock(); native.is_file.return_value = True
        with patch.object(phone_panel, 'native_executable', return_value=native), \
                patch.object(phone_panel, 'read_pointer', return_value={'version': '17.0.1'}), \
                patch.object(phone_panel, 'executable', return_value=['Gateway.exe', 'dashboard', '--root', str(self.root)]), \
                patch.object(phone_panel.subprocess, 'Popen') as launch:
            phone_panel.show(None, self.root)
            command = launch.call_args.args[0]
            self.assertEqual(['--view', 'Android controls', '--quiet'], command[-3:])
            self.assertNotIn('--legacy-ui', command)
            self.assertFalse((self.root / 'data').exists())
            for stream in ('stdin', 'stdout', 'stderr'):
                self.assertEqual(phone_panel.subprocess.DEVNULL, launch.call_args.kwargs[stream])

    def test_host_and_supervisor_settings_writers_share_one_exclusive_guard(self):
        with settings_write_lock(self.root):
            with self.assertRaises(StateDirectoryAlreadyOwned):
                with settings_write_lock(self.root):
                    self.fail('A second writer entered the projection guard')
        with settings_write_lock(self.root):
            atomic_json(self.root / 'settings.json', {'retained': True})
        self.assertTrue(json.loads((self.root / 'settings.json').read_text())['retained'])

    @unittest.skipUnless(importlib.util.find_spec('segno'), 'Packaged QR renderer is installed by requirements-build.txt')
    def test_offline_qr_uses_the_original_canonical_invitation_once(self):
        invitation = {'link': 'gptmobile://gateway/pair?installation=fixture&code=one-time-code',
                      'pairing_id': 'original-pairing', 'expires_at': 1900000000}
        with patch.object(native_bridge, 'request', return_value={'result': invitation}) as request:
            result = native_bridge.control(self.root, 'control-pairing-create', {'name': 'Phone', 'fields': ['engineering_enabled'], 'repositories': []})
        self.assertEqual(invitation['link'], result['link'])
        self.assertEqual(invitation['pairing_id'], result['pairing_id'])
        self.assertEqual(invitation['expires_at'], result['expires_at'])
        self.assertTrue(base64.b64decode(result['qr_png_base64']).startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertEqual(1, request.call_count)


if __name__ == '__main__':
    unittest.main()
