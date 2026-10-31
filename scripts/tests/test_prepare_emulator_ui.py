from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_emulator_ui as emulator


class HomeDevice:
    """Stateful ADB stand-in: selecting HOME changes subsequent resolution."""
    def __init__(self, homes=(), installed=True, fault=None):
        self.home = 'com.android.launcher3/.Launcher'
        self.homes = list(homes)
        self.installed = installed
        self.calls = []
        self.fault = fault
        self.accept_selection = True
        self.launch = f'Status: ok\nActivity: {emulator.TEST_HOME}\nComplete'

    def __call__(self, command, **options):
        self.calls.append((command, options))
        if self.fault and self.fault[0] in command:
            raise self.fault[1]
        if 'resolve-activity' in command:
            if '-n' in command:
                output = emulator.TEST_HOME if self.installed else 'No activity found'
            else:
                output = self.homes.pop(0) if self.homes else self.home
                if isinstance(output, BaseException):
                    raise output
        elif 'set-home-activity' in command:
            if self.accept_selection:
                self.home = emulator.TEST_HOME
            output = 'Success'
        elif 'start' in command:
            output = self.launch
        elif 'force-stop' in command:
            assert command[-1] in emulator.LAUNCHERS
            output = ''
        else:
            raise AssertionError(f'Unexpected emulator command: {command}')
        return subprocess.CompletedProcess(command, 0, output)

    def mutations(self):
        return [command for command, _ in self.calls if 'resolve-activity' not in command]


class EmulatorReadinessTest(unittest.TestCase):
    def clock(self):
        elapsed = [0.0]
        return (lambda: elapsed[0]), (lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds))

    def test_valid_test_home_is_selected_and_started_before_the_stock_launcher_stops(self):
        device = HomeDevice(homes=['com.google.android.apps.nexuslauncher/.NexusLauncherActivity'])
        report = emulator.prepare(device)
        self.assertEqual('PREPARED', report['status'])
        self.assertEqual(emulator.TEST_HOME, device.home)
        self.assertFalse(report['system_error_dialogs_suppressed'])
        self.assertFalse(report['app_under_test_changed'])
        self.assertFalse(report['system_launcher_disabled_by_setup'])
        mutations = device.mutations()
        self.assertEqual(['adb', 'shell', 'cmd', 'package', 'set-home-activity', '--user', '0', emulator.TEST_HOME], mutations[0])
        self.assertEqual(['adb', 'shell', 'am', 'start', '-W', '--user', '0', '-n', emulator.TEST_HOME], mutations[1])
        self.assertEqual(['adb', 'shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher'], mutations[2])
        self.assertFalse(any('disable-user' in command for command, _ in device.calls))

    def test_missing_test_apk_is_rejected_before_any_environment_mutation(self):
        device = HomeDevice(installed=False)
        with self.assertRaisesRegex(ValueError, 'install the test APK'):
            emulator.prepare(device)
        self.assertEqual([], device.mutations())

    def test_an_unconfirmed_home_selection_is_not_used_to_stop_the_launcher(self):
        device = HomeDevice()
        device.accept_selection = False
        with self.assertRaisesRegex(ValueError, 'does not resolve'):
            emulator.prepare(device)
        self.assertEqual(1, len(device.mutations()))
        self.assertIn('set-home-activity', device.mutations()[0])

    def test_launch_failure_or_wrong_activity_never_claims_readiness(self):
        for launch in ('Error: Activity not started', 'Status: ok\nActivity: another.package/.Home'):
            device = HomeDevice()
            device.launch = launch
            with self.assertRaisesRegex(ValueError, 'successful launch'):
                emulator.prepare(device)
            self.assertFalse(any('force-stop' in command for command in device.mutations()))

    def test_an_already_selected_test_home_is_observed_without_killing_it(self):
        device = HomeDevice()
        device.home = emulator.TEST_HOME
        report = emulator.prepare(device)
        self.assertIsNone(report['stopped_system_launcher'])
        self.assertEqual(1, len(device.mutations()))
        self.assertIn('start', device.mutations()[0])

    def test_unknown_or_malformed_home_never_changes_any_package(self):
        for home in ('dev.melo.gptmobile.improved/.MainActivity', 'com.example.unexpected/.Home', 'No activity found', 'com.android.launcher3/.Home; touch file'):
            run = Mock(return_value=subprocess.CompletedProcess([], 0, home))
            now, wait = self.clock()
            with self.assertRaises((ValueError, TimeoutError)):
                emulator.prepare(run, now=now, wait=wait, ready_timeout=3)
            self.assertTrue(all('resolve-activity' in call.args[0] for call in run.call_args_list))
            self.assertLessEqual(now(), 3)

    def test_mutation_errors_and_timeouts_are_never_replayed(self):
        for operation in ('set-home-activity', 'start', 'force-stop'):
            for error in (subprocess.CalledProcessError(1, ['adb']), subprocess.TimeoutExpired(['adb'], 1)):
                device = HomeDevice(fault=(operation, error))
                with self.assertRaises(type(error)):
                    emulator.prepare(device)
                self.assertEqual(1, sum(operation in command for command, _ in device.calls))

    def test_boot_fallback_and_missing_home_are_polled_before_any_mutation(self):
        device = HomeDevice(homes=['No activity found', 'com.android.settings/.FallbackHome', 'com.android.launcher3/.Launcher'])
        now, wait = self.clock()
        report = emulator.prepare(device, now=now, wait=wait)
        self.assertEqual(3, report['readiness_attempts'])
        self.assertEqual(3, report['readiness_seconds'])
        self.assertTrue(all('resolve-activity' in command for command, _ in device.calls[:4]))
        self.assertIn('set-home-activity', device.calls[4][0])

    def test_only_transient_read_discovery_can_retry(self):
        device = HomeDevice(homes=[subprocess.CalledProcessError(1, ['adb'], stderr='error: device offline')])
        now, wait = self.clock()
        self.assertEqual(2, emulator.prepare(device, now=now, wait=wait)['readiness_attempts'])
        self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))

    def test_permission_failures_are_not_retried_or_hidden(self):
        run = Mock(side_effect=subprocess.CalledProcessError(1, ['adb'], stderr='permission denied'))
        now, wait = self.clock()
        with self.assertRaises(subprocess.CalledProcessError):
            emulator.prepare(run, now=now, wait=wait)
        self.assertEqual(1, run.call_count)
        self.assertEqual(0, now())

    def test_read_discovery_timeout_can_recover_without_replaying_a_mutation(self):
        device = HomeDevice(homes=[subprocess.TimeoutExpired(['adb'], 1)])
        now, wait = self.clock()
        self.assertEqual(2, emulator.prepare(device, now=now, wait=wait)['readiness_attempts'])
        self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))

    def test_failed_setup_retains_a_blocked_artifact_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'emulator-ui.json'
            with patch.object(sys, 'argv', ['prepare_emulator_ui.py', '--output', str(output)]), patch.object(emulator, 'prepare', side_effect=TimeoutError('last home=com.example.unexpected/.Home')):
                with self.assertRaises(TimeoutError):
                    emulator.main()
            report = json.loads(output.read_text())
            self.assertEqual('BLOCKED', report['status'])
            self.assertIn('com.example.unexpected/.Home', report['reason'])
            self.assertFalse(report['app_under_test_changed'])

    def test_readiness_budget_and_unknown_home_diagnostics_are_bounded(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0, 'com.example.unexpected/.Home'))
        now, wait = self.clock()
        with self.assertRaisesRegex(TimeoutError, 'com.example.unexpected/.Home'):
            emulator.prepare(run, now=now, wait=wait, ready_timeout=3)
        self.assertEqual(3, now())
        self.assertTrue(all(call.kwargs['timeout'] <= 3 for call in run.call_args_list))
        for budget in (0, -1, 61, float('nan')):
            with self.assertRaises(ValueError):
                emulator.prepare(run, ready_timeout=budget)

    def test_home_candidate_is_packaged_only_with_instrumentation(self):
        root = Path(__file__).resolve().parents[2]
        manifest = ET.parse(root / 'app/src/androidTest/AndroidManifest.xml')
        android = '{http://schemas.android.com/apk/res/android}'
        application = manifest.find('./application')
        self.assertEqual('android.app.Application', application.get(android + 'name'))
        self.assertEqual('android.app.AppComponentFactory', application.get(android + 'appComponentFactory'))
        activity = manifest.find('./application/activity')
        self.assertEqual(emulator.TEST_HOME.split('/')[1], activity.get(android + 'name'))
        self.assertEqual('true', activity.get(android + 'exported'))
        categories = {row.get(android + 'name') for row in activity.findall('./intent-filter/category')}
        self.assertIn('android.intent.category.HOME', categories)
        self.assertIn('android.intent.category.DEFAULT', categories)
        self.assertNotIn('EmulatorHomeActivity', (root / 'app/src/main/AndroidManifest.xml').read_text())


if __name__ == '__main__':
    unittest.main()
