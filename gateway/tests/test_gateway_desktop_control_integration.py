"""Single-authority legacy launch and explicit control-port firewall behavior."""
import json
import base64
import importlib.util
import os
import shutil
import subprocess
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import firewall, phone_panel
from desktop import native_bridge
from desktop.configuration import atomic_json, settings_write_lock, validate
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

    def test_network_setup_repairs_retained_lan_and_wildcard_control_access(self):
        for bind_host, saved in [('192.168.1.25', None), ('0.0.0.0', None),
                                 ('0.0.0.0', {'host': '192.168.1.42', 'port': 9443}),
                                 ('::', {'host': 'fd00::42', 'port': 9443})]:
            with self.subTest(bind_host=bind_host, saved=saved), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                cfg = validate({'bind_host': bind_host, 'port': 9234, 'workers': 3})
                atomic_json(root / 'settings.json', cfg)
                original = (root / 'settings.json').read_bytes()
                listener = saved or {'host': '192.168.1.25', 'port': 8091}
                if saved:
                    atomic_json(root / 'data' / 'control-listener.json', saved)
                with patch('desktop.firewall.os', SimpleNamespace(**dict(vars(os), name='nt'))), \
                        patch.dict(os.environ, {'SystemRoot': str(root)}), \
                        patch('desktop.configuration.setup_interface', return_value='192.168.1.25'), \
                        patch('desktop.firewall.ctypes.windll', SimpleNamespace(shell32=SimpleNamespace(IsUserAnAdmin=lambda: True)), create=True), \
                        patch('desktop.firewall.subprocess.run') as run:
                    run.return_value.returncode = 0
                    firewall.apply(root, include_control=True)
                    self.assertEqual(listener, json.loads((root / 'data' / 'control-listener.json').read_text()))
                    self.assertEqual(original, (root / 'settings.json').read_bytes())
                    command = run.call_args.args[0]
                    self.assertEqual('9234', command[command.index('-Port') + 1])
                    self.assertEqual(bind_host, command[command.index('-BindAddress') + 1])
                    self.assertEqual(str(listener['port']), command[command.index('-ControlPort') + 1])
                    self.assertEqual(listener['host'], command[command.index('-ControlAddress') + 1])
                    self.assertEqual(1, run.call_count, 'One explicit action must use one elevation request')
                    marker = json.loads((root / 'phone-firewall.json').read_text())
                    self.assertEqual(listener['host'], marker['bind_host'])
                    self.assertEqual('Private', marker['profile'])
                    self.assertEqual('LocalSubnet', marker['remote'])
                    firewall.apply(root, remove=True, include_control=True)
                    self.assertIn('-Remove', run.call_args.args[0])
                    self.assertFalse((root / 'firewall.json').exists())
                    self.assertFalse((root / 'phone-firewall.json').exists())

    def test_native_network_repair_includes_control_port(self):
        from desktop.workspace_actions import connection
        with patch('desktop.firewall.apply') as apply:
            self.assertEqual({'applied': True}, connection(self.root, 'connection-firewall', {}))
            apply.assert_called_once_with(self.root, remove=False, include_control=True)

    def test_network_setup_keeps_local_only_settings_private(self):
        atomic_json(self.root / 'settings.json', validate({'bind_host': '127.0.0.1'}))
        with patch('desktop.firewall.os', SimpleNamespace(**dict(vars(os), name='nt'))), \
                patch('desktop.firewall.subprocess.run') as run, self.assertRaises(ValueError):
            firewall.apply(self.root, include_control=True)
        run.assert_not_called()
        self.assertFalse((self.root / 'data' / 'control-listener.json').exists())
        self.assertFalse((self.root / 'phone-firewall.json').exists())

    def test_unsaved_ipv6_wildcard_requires_a_matching_control_interface(self):
        atomic_json(self.root / 'settings.json', validate({'bind_host': '::'}))
        with patch('desktop.firewall.os', SimpleNamespace(**dict(vars(os), name='nt'))), \
                patch('desktop.firewall.subprocess.run') as run, self.assertRaisesRegex(ValueError, 'IPv6'):
            firewall.apply(self.root, include_control=True)
        run.assert_not_called()
        self.assertFalse((self.root / 'data' / 'control-listener.json').exists())
        self.assertFalse((self.root / 'phone-firewall.json').exists())

    def test_cancelled_network_setup_does_not_claim_firewall_success(self):
        atomic_json(self.root / 'settings.json', validate({'bind_host': '192.168.1.25'}))
        with patch('desktop.firewall.os', SimpleNamespace(**dict(vars(os), name='nt'))), \
                patch.dict(os.environ, {'SystemRoot': str(self.root)}), \
                patch('desktop.firewall.ctypes.windll', SimpleNamespace(shell32=SimpleNamespace(IsUserAnAdmin=lambda: True)), create=True), \
                patch('desktop.firewall.subprocess.run', return_value=SimpleNamespace(returncode=1)), \
                self.assertRaises(RuntimeError):
            firewall.apply(self.root, include_control=True)
        self.assertTrue((self.root / 'data' / 'control-listener.json').exists())
        self.assertFalse((self.root / 'firewall.json').exists())
        self.assertFalse((self.root / 'phone-firewall.json').exists())

    @unittest.skipUnless(shutil.which('pwsh') or shutil.which('powershell'), 'PowerShell is available on the Windows host check')
    def test_powershell_network_rules_keep_separate_addresses_and_remove_both(self):
        # Exercise the real script with in-process stand-ins for the three firewall
        # cmdlets. This never reads or modifies the test host's firewall.
        harness = self.root / 'firewall-contract.ps1'
        harness.write_text(r"""param([string]$Firewall, [string]$Rule, [switch]$Remove, [switch]$FailPhone)
$global:GatewayFirewallTestCreated = @(); $global:GatewayFirewallTestRemoved = @()
$global:GatewayFirewallTestFailPhone = $FailPhone
function Get-NetFirewallRule { param($Name, $ErrorAction) [pscustomobject]@{Name=$Name} }
function Remove-NetFirewallRule { process { $global:GatewayFirewallTestRemoved += $_.Name } }
function New-NetFirewallRule {
    param($Name, $DisplayName, $Direction, $Action, $Enabled, $Profile, $Protocol,
          $LocalPort, $LocalAddress, $RemoteAddress, $EdgeTraversalPolicy)
    if ($global:GatewayFirewallTestFailPhone -and $Name.EndsWith('-phone')) { throw 'Simulated phone rule failure' }
    $global:GatewayFirewallTestCreated += @{name=$Name; port=$LocalPort; address=$LocalAddress;
                        profile=$Profile; remote=$RemoteAddress}
}
$failed = $false
try {
    if ($Remove) { & $Firewall -RuleName $Rule -Remove }
    else { & $Firewall -RuleName $Rule -BindAddress '0.0.0.0' -Port 9234 -ControlAddress '192.168.1.42' -ControlPort 9443 }
} catch { $failed = $true }
@{created=$global:GatewayFirewallTestCreated; removed=$global:GatewayFirewallTestRemoved; failed=$failed} | ConvertTo-Json -Depth 4 -Compress
""")
        executable = shutil.which('pwsh') or shutil.which('powershell')
        command = [executable, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(harness),
                   '-Firewall', str(Path(firewall.__file__).with_name('Firewall.ps1')), '-Rule', firewall.rule_name(self.root)]
        result = subprocess.run(command, capture_output=True, text=True, check=True, timeout=15)
        rules = json.loads(result.stdout)['created']
        self.assertEqual(2, len(rules))
        self.assertEqual('Any', rules[0]['address'])
        self.assertEqual('192.168.1.42', rules[1]['address'])
        self.assertEqual(9443, rules[1]['port'])
        self.assertEqual(firewall.rule_name(self.root) + '-phone', rules[1]['name'])
        self.assertTrue(all(rule['profile'] == 'Private' and rule['remote'] == 'LocalSubnet' for rule in rules))
        removed = subprocess.run(command + ['-Remove'], capture_output=True, text=True, check=True, timeout=15)
        receipt = json.loads(removed.stdout)
        self.assertEqual([], receipt['created'])
        self.assertEqual([firewall.rule_name(self.root), firewall.rule_name(self.root) + '-phone'], receipt['removed'])
        failed = subprocess.run(command + ['-FailPhone'], capture_output=True, text=True, check=True, timeout=15)
        receipt = json.loads(failed.stdout)
        self.assertTrue(receipt['failed'])
        self.assertEqual(1, len(receipt['created']))
        self.assertEqual([firewall.rule_name(self.root), firewall.rule_name(self.root) + '-phone'], receipt['removed'][-2:])

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
