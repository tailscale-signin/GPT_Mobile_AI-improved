from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_emulator_ui as emulator


class EmulatorReadinessTest(unittest.TestCase):
    def clock(self):
        elapsed = [0.0]
        return (lambda: elapsed[0]), (lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds))

    def test_only_the_resolved_allowlisted_launcher_is_disabled(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity\n'),
                                subprocess.CompletedProcess([], 0, 'disabled-user'), subprocess.CompletedProcess([], 0, '')])
        report = emulator.prepare(run)
        self.assertEqual('PREPARED', report['status'])
        self.assertFalse(report['system_error_dialogs_suppressed'])
        self.assertFalse(report['app_under_test_changed'])
        self.assertEqual(['adb', 'shell', 'pm', 'disable-user', '--user', '0', 'com.google.android.apps.nexuslauncher'], run.call_args_list[1].args[0])
        self.assertEqual(['adb', 'shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher'], run.call_args_list[2].args[0])

    def test_unknown_or_malformed_home_never_changes_any_package(self):
        for home in ('dev.melo.gptmobile.improved/.MainActivity', 'com.example.unexpected/.Home', 'No activity found', 'com.android.launcher3/.Home; touch file'):
            run = Mock(return_value=subprocess.CompletedProcess([], 0, home))
            now, wait = self.clock()
            with self.assertRaises((ValueError, TimeoutError)):
                emulator.prepare(run, now=now, wait=wait, ready_timeout=3)
            self.assertTrue(all('resolve-activity' in call.args[0] for call in run.call_args_list))
            self.assertLessEqual(now(), 3)

    def test_a_failed_environment_command_stops_instead_of_claiming_readiness(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, 'com.android.launcher3/.Launcher'),
                                subprocess.CalledProcessError(1, ['adb'])])
        with self.assertRaises(subprocess.CalledProcessError):
            emulator.prepare(run)
        self.assertEqual(2, run.call_count)

    def test_boot_fallback_and_missing_home_are_polled_before_any_mutation(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, 'No activity found'),
                                subprocess.CompletedProcess([], 0, 'com.android.settings/.FallbackHome'),
                                subprocess.CompletedProcess([], 0, 'com.android.launcher3/.Launcher'),
                                subprocess.CompletedProcess([], 0, 'disabled-user'), subprocess.CompletedProcess([], 0, '')])
        now, wait = self.clock()
        report = emulator.prepare(run, now=now, wait=wait)
        self.assertEqual(3, report['readiness_attempts'])
        self.assertEqual(3, report['readiness_seconds'])
        self.assertTrue(all('resolve-activity' in call.args[0] for call in run.call_args_list[:3]))
        self.assertIn('disable-user', run.call_args_list[3].args[0])

    def test_only_transient_read_discovery_can_retry(self):
        run = Mock(side_effect=[subprocess.CalledProcessError(1, ['adb'], stderr='error: device offline'),
                                subprocess.CompletedProcess([], 0, 'com.android.launcher3/.Launcher'),
                                subprocess.CompletedProcess([], 0, 'disabled-user'), subprocess.CompletedProcess([], 0, '')])
        now, wait = self.clock()
        self.assertEqual(2, emulator.prepare(run, now=now, wait=wait)['readiness_attempts'])
        self.assertEqual(4, run.call_count)

    def test_permission_failures_are_not_retried_or_hidden(self):
        run = Mock(side_effect=subprocess.CalledProcessError(1, ['adb'], stderr='permission denied'))
        now, wait = self.clock()
        with self.assertRaises(subprocess.CalledProcessError):
            emulator.prepare(run, now=now, wait=wait)
        self.assertEqual(1, run.call_count)
        self.assertEqual(0, now())

    def test_read_discovery_timeout_can_recover_without_replaying_a_mutation(self):
        run = Mock(side_effect=[subprocess.TimeoutExpired(['adb'], 1),
                                subprocess.CompletedProcess([], 0, 'com.android.launcher3/.Launcher'),
                                subprocess.CompletedProcess([], 0, 'disabled-user'), subprocess.CompletedProcess([], 0, '')])
        now, wait = self.clock()
        self.assertEqual(2, emulator.prepare(run, now=now, wait=wait)['readiness_attempts'])
        self.assertEqual(1, sum('disable-user' in call.args[0] for call in run.call_args_list))

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


if __name__ == '__main__':
    unittest.main()
