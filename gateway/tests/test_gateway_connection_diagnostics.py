"""Truthful read-only host observations, including the PowerShell producer on Windows."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import connection_diagnostics as diagnostics
from desktop.configuration import DEFAULTS
from desktop.workspace_actions import connection


class HostConnectionDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'settings.json').write_text(json.dumps(DEFAULTS))
        (self.root / 'status.json').write_text(json.dumps({'state': 'error', 'updateState': 'startup-failed', 'updatedAt': 990,
                                                        'error': 'private service command and credential'}))

    def observed(self, raw, *, returncode=0):
        with patch.object(diagnostics, 'os', SimpleNamespace(name='nt', environ={'SystemRoot': r'C:\Windows'})), \
                patch.object(diagnostics, '_startup_registration', return_value='REGISTERED_FOR_INSTALLATION'), \
                patch.object(diagnostics.subprocess, 'run', return_value=SimpleNamespace(returncode=returncode, stdout=json.dumps(raw).encode(), stderr=b'private failure')) as run:
            return diagnostics.collect(self.root, now=1000), run

    def test_read_only_probe_keeps_observations_without_claiming_remote_reachability(self):
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        raw = dict(listener_state='PRESENT', listener_binding='MATCHED', listener_count=1,
                   default_route_state='PRESENT', ipv4_default_route_count=1, ipv6_default_route_count=0,
                   tunnel_adapter_signal='PRESENT', tunnel_adapter_count=1,
                   firewall_rule_state='PRESENT', firewall_rule_action='ALLOW', firewall_rule_enabled='ENABLED',
                   firewall_rule_port='MATCHED', active_network_profiles=['Private'],
                   adapter_name='private-vpn', address='100.70.2.3', command='private-service-command')
        result, run = self.observed(raw)
        self.assertEqual('OBSERVED', result['collection_state'])
        self.assertEqual('STARTUP_FAILURE_RECORDED', result['service_start_state'])
        self.assertEqual(10, result['supervisor_observation_age_seconds'])
        self.assertEqual('NOT_VERIFIED', result['listener_ownership'])
        self.assertEqual('NOT_ESTABLISHED', result['firewall_connectivity_verdict'])
        self.assertNotIn('private-', json.dumps(result))
        self.assertNotIn('100.70.2.3', json.dumps(result))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})
        command = run.call_args.args[0]
        self.assertIn('-File', command)
        self.assertIn(str(Path(diagnostics.__file__).with_name('Inspect-Connection.ps1')), command)
        self.assertNotIn('-Command', command)
        self.assertEqual(diagnostics.PROBE_TIMEOUT, run.call_args.kwargs['timeout'])
        self.assertFalse(run.call_args.kwargs.get('shell', False))

    def test_unknown_timeout_and_absence_are_different_observations(self):
        empty, _ = self.observed({'listener_state': 'NOT_OBSERVED', 'listener_binding': 'NOT_OBSERVED',
                                 'firewall_rule_state': 'NOT_OBSERVED', 'default_route_state': 'NOT_OBSERVED'})
        self.assertEqual('PARTIAL', empty['collection_state'])
        self.assertEqual('NOT_OBSERVED', empty['listener_state'])
        self.assertIn('does not prove traffic is blocked', empty['summary'])
        failed, _ = self.observed({'listener_state': 'PRESENT'}, returncode=1)
        self.assertEqual('UNKNOWN', failed['listener_state'])
        self.assertEqual('UNAVAILABLE', failed['collection_state'])
        with patch.object(diagnostics, 'os', SimpleNamespace(name='nt', environ={})), \
                patch.object(diagnostics, '_startup_registration', return_value='UNKNOWN'), \
                patch.object(diagnostics.subprocess, 'run', side_effect=subprocess.TimeoutExpired('private command', 12)):
            timed_out = diagnostics.collect(self.root, now=1000)
        self.assertEqual('TIMEOUT', timed_out['collection_state'])
        self.assertEqual('UNKNOWN', timed_out['firewall_rule_state'])
        self.assertNotIn('private command', json.dumps(timed_out))

    def test_unsupported_host_and_invalid_saved_settings_do_not_spawn_or_create_files(self):
        with patch.object(diagnostics, 'os', SimpleNamespace(name='posix', environ={})), patch.object(diagnostics.subprocess, 'run') as run:
            self.assertEqual('UNSUPPORTED', diagnostics.collect(self.root)['collection_state'])
            (self.root / 'settings.json').write_text('{invalid')
            self.assertEqual('INVALID_SETTINGS', diagnostics.collect(self.root)['collection_state'])
            run.assert_not_called()
        self.assertEqual({'settings.json', 'status.json'}, {p.name for p in self.root.iterdir()})

    def test_untrusted_probe_shapes_are_unknown_and_counts_remain_bounded(self):
        result = diagnostics.normalize_probe({'listener_state': ['private'], 'firewall_rule_action': {'raw': 'secret'},
                                             'listener_count': True, 'tunnel_adapter_count': -1,
                                             'ipv4_default_route_count': 65_536, 'active_network_profiles': ['Private', 'private network name']})
        self.assertEqual('UNKNOWN', result['listener_state'])
        self.assertEqual('UNKNOWN', result['firewall_rule_action'])
        self.assertFalse(diagnostics.COUNTS & result.keys())
        self.assertNotIn('active_network_profiles', result)

    def test_local_dispatch_does_not_acquire_a_write_lock_or_expose_phone_inspection(self):
        with patch('desktop.connection_diagnostics.collect', return_value={'summary': 'Observed'}) as collect, \
                patch('desktop.configuration.settings_write_lock', side_effect=AssertionError('Read-only observation must not lock/write settings')):
            self.assertEqual({'summary': 'Observed'}, connection(self.root, 'connection-diagnostics', {}))
            collect.assert_called_once_with(self.root)
            for args, phone in [({'port': 443}, False), ({}, True)]:
                with self.assertRaises(ValueError):
                    connection(self.root, 'connection-diagnostics', args, phone=phone)

    def test_startup_query_uses_read_access_and_never_retains_the_registered_command(self):
        class Key:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
        opened = []
        fake = SimpleNamespace(HKEY_CURRENT_USER=1, KEY_READ=2, REG_SZ=3,
                               OpenKey=lambda *args: opened.append(args) or Key(),
                               QueryValueEx=lambda *args: ('private unrelated startup command', 3))
        with patch.dict(sys.modules, {'winreg': fake}):
            self.assertEqual('DIFFERENT_REGISTRATION', diagnostics._startup_registration(self.root))
        self.assertEqual(2, opened[0][-1])

    @unittest.skipUnless(shutil.which('pwsh') or shutil.which('powershell'), 'PowerShell producer runs on the Windows host check')
    def test_powershell_producer_reads_mocked_host_observations_and_contains_cmdlet_failure(self):
        harness = self.root / 'inspect-contract.ps1'
        harness.write_text(r'''param([string]$Probe, [switch]$FailReads)
$global:FailReads = $FailReads
function Get-NetTCPConnection { param($State, $ErrorAction) if ($global:FailReads) { throw 'private access error' }; [pscustomobject]@{ LocalPort=8090; LocalAddress='0.0.0.0'; OwningProcess=717 } }
function Get-NetRoute { param($ErrorAction) [pscustomobject]@{ DestinationPrefix='0.0.0.0/0'; NextHop='private address' } }
function Get-NetAdapter { param([switch]$IncludeHidden, $ErrorAction) [pscustomobject]@{ Status='Up'; InterfaceType=6; InterfaceDescription='private Tailscale adapter' } }
function Get-NetConnectionProfile { param($ErrorAction) [pscustomobject]@{ NetworkCategory='Private'; Name='private network' } }
function Get-NetFirewallRule { param($PolicyStore, $ErrorAction) if ($global:FailReads) { throw 'private firewall error' }; [pscustomobject]@{ Name=('GPTMobileGateway-' + ('a' * 24)); Action='Allow'; Enabled='True'; Profile='Private' } }
function Get-NetFirewallPortFilter { param([Parameter(ValueFromPipeline=$true)]$InputObject, $ErrorAction) process { [pscustomobject]@{Protocol='TCP'; LocalPort='8090'} } }
& $Probe -Port 8090 -BindAddress '127.0.0.1' -RuleName ('GPTMobileGateway-' + ('a' * 24))
''')
        executable = shutil.which('pwsh') or shutil.which('powershell')
        command = [executable, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(harness), '-Probe', str(Path(diagnostics.__file__).with_name('Inspect-Connection.ps1'))]
        for fail in (False, True):
            response = subprocess.run(command + (['-FailReads'] if fail else []), capture_output=True, check=True, timeout=15)
            value = json.loads(response.stdout.decode('utf-8-sig'))
            self.assertEqual('UNKNOWN' if fail else 'PRESENT', value['listener_state'])
            self.assertEqual('UNKNOWN' if fail else 'PRESENT', value['firewall_rule_state'])
            self.assertEqual('PRESENT', value['tunnel_adapter_signal'])
            self.assertNotIn('private', json.dumps(value))
