from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_emulator_ui as emulator


class EmulatorReadinessTest(unittest.TestCase):
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
            with self.assertRaises(ValueError):
                emulator.prepare(run)
            self.assertEqual(1, run.call_count)

    def test_a_failed_environment_command_stops_instead_of_claiming_readiness(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, 'com.android.launcher3/.Launcher'),
                                subprocess.CalledProcessError(1, ['adb'])])
        with self.assertRaises(subprocess.CalledProcessError):
            emulator.prepare(run)
        self.assertEqual(2, run.call_count)


if __name__ == '__main__':
    unittest.main()
