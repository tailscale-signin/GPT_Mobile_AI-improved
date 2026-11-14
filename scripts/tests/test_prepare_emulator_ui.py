from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_emulator_ui as emulator

ANDROID_RESOLVER = 'android/com.android.internal.app.ResolverActivity'


class HomeDevice:
    """Stateful ADB stand-in: selecting HOME changes subsequent resolution."""
    def __init__(self, homes=(), installed=True, fault=None):
        self.home = 'com.android.launcher3/.Launcher'
        self.homes = list(homes)
        self.home_candidates = f'com.android.launcher3/.Launcher\n{emulator.TEST_HOME}'
        self.candidate_outputs = []
        self.installed = installed
        self.calls = []
        self.fault = fault
        self.accept_selection = True
        self.launch = f'Status: ok\nActivity: {emulator.TEST_HOME}\nComplete'
        self.broadcast_output = emulator.BROADCAST_IDLE
        self.application_output = emulator.APPLICATION_IDLE
        self.process_dump = ('ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n'
                             '  *APP* ProcessRecord{abc 1035:com.android.systemui/u0a99}\n'
                             '    mCrashing=false mNotResponding=false\n')
        self.window_dump = ('WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
                            f'  mCurrentFocus=Window{{abc u0 {emulator.TEST_HOME}}}\n'
                            '  mWaitingForConfig=false\n')
        self.window_states = []
        self.display_size = 'Physical size: 2560x1800\nOverride size: 640x1280'
        self.display_density = 'Physical density: 320\nOverride density: 320'
        self.font_scale = '2.0'

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
        elif 'query-activities' in command:
            output = self.candidate_outputs.pop(0) if self.candidate_outputs else self.home_candidates
            if isinstance(output, BaseException):
                raise output
        elif 'set-home-activity' in command:
            if self.accept_selection:
                self.home = emulator.TEST_HOME
            output = 'Success'
        elif 'start' in command:
            output = self.launch
        elif 'wait-for-broadcast-idle' in command:
            output = self.broadcast_output
        elif 'wait-for-application-barrier' in command:
            output = self.application_output
        elif command[2:] == ['dumpsys', 'activity', 'processes']:
            output = self.process_dump
        elif command[2:] == ['dumpsys', 'window', '-a']:
            output = self.window_states.pop(0) if self.window_states else self.window_dump
        elif command[2:] == ['wm', 'size']:
            output = self.display_size
        elif command[2:] == ['wm', 'density']:
            output = self.display_density
        elif command[2:] == ['settings', 'get', 'system', 'font_scale']:
            output = self.font_scale
        else:
            raise AssertionError(f'Unexpected emulator command: {command}')
        return subprocess.CompletedProcess(command, 0, output)

    def mutations(self):
        reads = [('am', 'wait-for-broadcast-idle', '--flush-broadcast-loopers'),
                 ('am', 'wait-for-application-barrier'), ('dumpsys', 'activity', 'processes'),
                 ('dumpsys', 'window', '-a'), ('wm', 'size'), ('wm', 'density'),
                 ('settings', 'get', 'system', 'font_scale')]
        return [command for command, _ in self.calls
                if 'resolve-activity' not in command and 'query-activities' not in command and tuple(command[2:]) not in reads]


class EmulatorReadinessTest(unittest.TestCase):
    def clock(self):
        elapsed = [0.0]
        return (lambda: elapsed[0]), (lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds))

    def resolver_device(self, candidates=None):
        device = HomeDevice()
        device.home = ANDROID_RESOLVER
        if candidates is not None:
            device.home_candidates = candidates
        return device

    def test_exact_android_resolver_with_allowlisted_candidates_selects_fixture_once(self):
        device = self.resolver_device()
        now, wait = self.clock()
        report = emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
        self.assertEqual('PREPARED', report['status'])
        self.assertEqual(ANDROID_RESOLVER, report['home_component'])
        self.assertEqual(device.home_candidates.splitlines(), report['resolver_home_candidates'])
        query = next(command for command, _ in device.calls if 'query-activities' in command)
        self.assertEqual(['adb', 'shell', 'cmd', 'package', 'query-activities', '--components', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'], query)
        self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))
        self.assertEqual(1, sum('start' in command for command, _ in device.calls))
        barrier = next(i for i, (command, _) in enumerate(device.calls) if 'wait-for-broadcast-idle' in command)
        selection = next(i for i, (command, _) in enumerate(device.calls) if 'set-home-activity' in command)
        self.assertLess(barrier, selection)
        self.assertEqual(emulator.TEST_HOME, device.home)
        self.assertFalse(report['system_error_dialogs_suppressed'])

    def test_resolver_accepts_only_stock_launcher_packages_and_exact_packaged_fixture(self):
        for launchers in (
                ['com.google.android.apps.nexuslauncher/.NexusLauncherActivity'],
                ['com.android.launcher3/com.android.launcher3.Launcher', 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity']):
            with self.subTest(launchers=launchers):
                device = self.resolver_device('\n'.join([emulator.TEST_HOME] + launchers))
                now, wait = self.clock()
                self.assertEqual('PREPARED', emulator.prepare(device, now=now, wait=wait, ready_timeout=3)['status'])
                self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))

    def test_resolver_without_exclusive_fixture_and_stock_candidates_never_mutates(self):
        allowed = f'com.android.launcher3/.Launcher\n{emulator.TEST_HOME}'
        for candidates in ('', 'No activities found', 'com.android.launcher3/.Launcher', emulator.TEST_HOME,
                           allowed + '\ncom.android.settings/.FallbackHome',
                           allowed + '\ncom.example.unexpected/.Home',
                           'com.android.launcher3/.Launcher\ndev.melo.gptmobile.improved/.MainActivity'):
            with self.subTest(candidates=candidates):
                device = self.resolver_device(candidates)
                now, wait = self.clock()
                with self.assertRaises((ValueError, TimeoutError)):
                    emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
                self.assertEqual([], device.mutations())
                self.assertTrue(any('query-activities' in command for command, _ in device.calls))
                self.assertLessEqual(now(), 3)

    def test_malformed_duplicate_or_oversized_resolver_candidates_fail_before_mutation(self):
        allowed = f'com.android.launcher3/.Launcher\n{emulator.TEST_HOME}'
        for candidates in ('2 activities found:\n' + allowed,
                           'priority=0 preferredOrder=0\n' + allowed,
                           allowed + '\nError: package query failed',
                           allowed + '\ncom.android.launcher3/.Home; touch file',
                           allowed + '\n' + emulator.TEST_HOME,
                           allowed + '\n\ncom.android.launcher3/.OtherHome',
                           allowed + '\n' + 'x' * 8192,
                           '\n'.join([emulator.TEST_HOME] + [f'com.android.launcher3/.Home{i}' for i in range(17)])):
            with self.subTest(candidates=candidates[:100]):
                device = self.resolver_device(candidates)
                now, wait = self.clock()
                with self.assertRaises(ValueError):
                    emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
                self.assertEqual([], device.mutations())
                self.assertEqual(1, sum('query-activities' in command for command, _ in device.calls))

    def test_resolver_recovery_does_not_apply_to_similar_or_arbitrary_components(self):
        for home in ('android/.ResolverActivity', 'android/com.android.internal.app.ChooserActivity', 'com.example/.ResolverActivity'):
            with self.subTest(home=home):
                device = HomeDevice()
                device.home = home
                now, wait = self.clock()
                with self.assertRaises(TimeoutError):
                    emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
                self.assertFalse(any('query-activities' in command for command, _ in device.calls))
                self.assertEqual([], device.mutations())

    def test_fallback_resolver_candidate_can_settle_read_only_before_one_selection(self):
        device = self.resolver_device()
        device.candidate_outputs = [device.home_candidates + '\ncom.android.settings/.FallbackHome']
        now, wait = self.clock()
        report = emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
        self.assertEqual(2, report['readiness_attempts'])
        self.assertEqual(1, now())
        self.assertEqual(2, sum('query-activities' in command for command, _ in device.calls))
        self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))

    def test_only_transient_candidate_query_transport_failure_can_retry(self):
        for failure in (subprocess.CalledProcessError(1, ['adb'], stderr='error: device offline'), subprocess.TimeoutExpired(['adb'], 1)):
            with self.subTest(failure=type(failure).__name__):
                device = self.resolver_device()
                device.candidate_outputs = [failure]
                now, wait = self.clock()
                report = emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
                self.assertEqual(2, report['readiness_attempts'])
                self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))
                self.assertTrue(all(options['timeout'] <= 3 for _, options in device.calls))

    def test_candidate_query_permission_failure_is_not_retried_or_hidden(self):
        device = self.resolver_device()
        device.candidate_outputs = [subprocess.CalledProcessError(1, ['adb'], stderr='Permission Denial: package query')]
        now, wait = self.clock()
        with self.assertRaises(subprocess.CalledProcessError):
            emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
        self.assertEqual(1, sum('query-activities' in command for command, _ in device.calls))
        self.assertEqual([], device.mutations())
        self.assertEqual(0, now())

    def test_candidate_query_timeout_or_late_acknowledgment_cannot_extend_the_deadline(self):
        for late_reply in (False, True):
            with self.subTest(late_reply=late_reply):
                device = self.resolver_device()
                now, wait = self.clock()
                def delayed_query(command, **options):
                    response = device(command, **options)
                    if 'query-activities' in command:
                        self.assertEqual(3, options['timeout'])
                        wait(options['timeout'])
                        if not late_reply:
                            raise subprocess.TimeoutExpired(command, options['timeout'])
                    return response
                with self.assertRaises(TimeoutError):
                    emulator.prepare(delayed_query, now=now, wait=wait, ready_timeout=3)
                self.assertEqual(3, now())
                self.assertEqual([], device.mutations())
                self.assertEqual(1, sum('query-activities' in command for command, _ in device.calls))

    def test_resolver_candidates_do_not_bypass_fixture_or_process_error_checks(self):
        for fault in ('missing_fixture', 'process_error'):
            with self.subTest(fault=fault):
                device = self.resolver_device()
                if fault == 'missing_fixture':
                    device.installed = False
                else:
                    device.process_dump = device.process_dump.replace('mNotResponding=false', 'mNotResponding=true')
                now, wait = self.clock()
                with self.assertRaises(ValueError):
                    emulator.prepare(device, now=now, wait=wait, ready_timeout=3)
                self.assertEqual([], device.mutations())

    def test_boot_work_drains_before_selecting_home_without_stopping_quickstep(self):
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
        self.assertEqual(2, len(mutations))
        self.assertIsNone(report['stopped_system_launcher'])
        barrier = next(i for i, (command, _) in enumerate(device.calls) if 'wait-for-broadcast-idle' in command)
        selection = next(i for i, (command, _) in enumerate(device.calls) if 'set-home-activity' in command)
        self.assertLess(barrier, selection)
        self.assertFalse(any('force-stop' in command for command, _ in device.calls))
        self.assertFalse(any('disable-user' in command for command, _ in device.calls))

    def test_missing_test_apk_is_rejected_before_any_environment_mutation(self):
        device = HomeDevice(installed=False)
        with self.assertRaisesRegex(ValueError, 'install the test APK'):
            emulator.prepare(device)
        self.assertEqual([], device.mutations())

    def test_slow_home_selection_completes_once_within_the_total_readiness_budget(self):
        device = HomeDevice()
        now, wait = self.clock()
        def slow_selection(command, **options):
            if 'set-home-activity' in command:
                if options['timeout'] < 12:
                    raise subprocess.TimeoutExpired(command, options['timeout'])
                wait(12)
            elif 'wait-for-broadcast-idle' not in command:
                self.assertLessEqual(options['timeout'], 10)
            return device(command, **options)
        report = emulator.prepare(slow_selection, now=now, wait=wait)
        self.assertEqual('PREPARED', report['status'])
        self.assertEqual(12, report['home_selection_seconds'])
        self.assertEqual(12, report['readiness_seconds'])
        self.assertEqual(emulator.TEST_HOME, device.home)
        self.assertEqual(1, sum('set-home-activity' in command for command, _ in device.calls))
        self.assertEqual(['set-home-activity', 'start'],
                         [next(operation for operation in ('set-home-activity', 'start', 'force-stop') if operation in command)
                          for command in device.mutations()])

    def test_stalled_home_selection_uses_only_the_remaining_total_budget_and_fails(self):
        device = HomeDevice(homes=['No activity found', 'com.android.settings/.FallbackHome'])
        now, wait = self.clock()
        selections = []
        def stalled_selection(command, **options):
            if 'set-home-activity' in command:
                selections.append(command)
                self.assertEqual(9, options['timeout'])
                wait(options['timeout'])
                raise subprocess.TimeoutExpired(command, options['timeout'])
            return device(command, **options)
        with self.assertRaises(subprocess.TimeoutExpired):
            emulator.prepare(stalled_selection, now=now, wait=wait, ready_timeout=12)
        self.assertEqual(12, now())
        self.assertEqual(1, len(selections))
        self.assertEqual([], device.mutations())
        self.assertEqual('com.android.launcher3/.Launcher', device.home)

    def test_android_brief_resolution_metadata_does_not_hide_the_component(self):
        device = HomeDevice()
        def with_brief_metadata(command, **options):
            result = device(command, **options)
            if 'resolve-activity' in command:
                result.stdout = 'priority=0 preferredOrder=0 match=0x100000 specificIndex=-1 isDefault=true\n' + result.stdout
            return result
        report = emulator.prepare(with_brief_metadata)
        self.assertEqual('PREPARED', report['status'])
        self.assertEqual(emulator.TEST_HOME, report['selected_test_home'])

    def test_an_unconfirmed_home_selection_is_not_launched(self):
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
        for operation in ('set-home-activity', 'start'):
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
        self.assertIn('wait-for-broadcast-idle', device.calls[4][0])
        self.assertIn('set-home-activity', device.calls[6][0])

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


class DisplayReadinessTest(unittest.TestCase):
    def clock(self):
        elapsed = [0.0]
        return (lambda: elapsed[0]), (lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds))

    def device(self):
        device = HomeDevice()
        device.home = emulator.TEST_HOME
        return device

    def test_boot_barrier_drains_before_any_home_mutation(self):
        device = HomeDevice()
        now, wait = self.clock()
        def boot_work(command, **options):
            if 'wait-for-broadcast-idle' in command:
                self.assertEqual([], device.mutations())
                wait(7)
            return device(command, **options)
        report = emulator.prepare(boot_work, now=now, wait=wait)
        self.assertEqual(7, report['boot_barrier_seconds'])
        self.assertEqual(7, report['readiness_seconds'])
        self.assertEqual(2, len(device.mutations()))

    def test_barrier_exit_zero_without_confirmation_never_claims_readiness(self):
        invalid = ('', 'All broadcast queues are idle', 'Finished application barriers',
                   'Unknown command: wait-for-broadcast-idle',
                   'Gave up waiting for application barriers!',
                   emulator.APPLICATION_IDLE + '\nGave up waiting for application barriers!',
                   emulator.BROADCAST_IDLE + '\nError: interrupted')
        for output in invalid:
            with self.subTest(output=output):
                device = HomeDevice()
                device.broadcast_output = output
                with self.assertRaisesRegex(ValueError, 'did not confirm'):
                    emulator.prepare(device)
                self.assertEqual([], device.mutations())
                device = self.device()
                device.application_output = output
                with self.assertRaisesRegex(ValueError, 'did not confirm'):
                    emulator.settle_display(device)
                self.assertEqual([], device.mutations())

    def test_display_barriers_precede_readiness_observations_without_mutations(self):
        device = self.device()
        report = emulator.settle_display(device)
        self.assertEqual('READY', report['status'])
        self.assertEqual('640x1280', report['display_size'])
        self.assertEqual(320, report['density_dpi'])
        self.assertEqual(2.0, report['font_scale'])
        self.assertTrue(report['broadcast_idle'])
        self.assertTrue(report['application_threads_drained'])
        self.assertFalse(report['system_error_dialogs_suppressed'])
        self.assertEqual(['adb', 'shell', 'am', 'wait-for-broadcast-idle', '--flush-broadcast-loopers'], device.calls[0][0])
        self.assertEqual(['adb', 'shell', 'am', 'wait-for-application-barrier'], device.calls[1][0])
        self.assertEqual([], device.mutations())

    def test_pending_configuration_and_null_focus_are_only_observed_until_ready(self):
        device = self.device()
        ready = device.window_dump
        device.window_states = [ready.replace(f'Window{{abc u0 {emulator.TEST_HOME}}}', 'null'),
                                ready.replace('mWaitingForConfig=false', 'mWaitingForConfig=true')]
        now, wait = self.clock()
        report = emulator.settle_display(device, now=now, wait=wait, ready_timeout=10)
        self.assertEqual(3, report['readiness_attempts'])
        self.assertEqual(3, report['readiness_seconds'])
        self.assertEqual([], device.mutations())
        self.assertEqual(1, sum('wait-for-broadcast-idle' in command for command, _ in device.calls))
        self.assertEqual(1, sum('wait-for-application-barrier' in command for command, _ in device.calls))

    def test_application_and_window_waits_share_the_remaining_phase_budget(self):
        device = self.device()
        device.window_dump = device.window_dump.replace('mWaitingForConfig=false', 'mWaitingForConfig=true')
        now, wait = self.clock()
        def slow_barriers(command, **options):
            self.assertLessEqual(options['timeout'], 12 - now())
            if 'wait-for-broadcast-idle' in command:
                self.assertEqual(12, options['timeout'])
                wait(7)
            elif 'wait-for-application-barrier' in command:
                self.assertEqual(5, options['timeout'])
                wait(4)
            return device(command, **options)
        with self.assertRaises(TimeoutError):
            emulator.settle_display(slow_barriers, now=now, wait=wait, ready_timeout=12)
        self.assertEqual(12, now())
        self.assertEqual([], device.mutations())

    def test_a_late_barrier_acknowledgment_cannot_exceed_the_deadline(self):
        device = self.device()
        now, wait = self.clock()
        def late(command, **options):
            wait(4)
            return device(command, **options)
        with self.assertRaises(TimeoutError):
            emulator.settle_display(late, now=now, wait=wait, ready_timeout=3)
        self.assertEqual(1, len(device.calls))

    def test_barrier_timeout_or_permission_failure_is_not_replayed(self):
        for operation in ('wait-for-broadcast-idle', 'wait-for-application-barrier'):
            for failure in (subprocess.TimeoutExpired(['adb'], 1), subprocess.CalledProcessError(1, ['adb'], stderr='permission denied')):
                with self.subTest(operation=operation, failure=type(failure)):
                    device = self.device()
                    device.fault = (operation, failure)
                    with self.assertRaises(type(failure)):
                        emulator.settle_display(device)
                    self.assertEqual(1, sum(operation in command for command, _ in device.calls))
                    self.assertEqual([], device.mutations())

    def test_crashing_or_nonresponding_process_blocks_home_and_display_without_dismissal(self):
        for field in ('mCrashing', 'mNotResponding'):
            for phase in (emulator.prepare, emulator.settle_display):
                with self.subTest(field=field, phase=phase.__name__):
                    device = self.device()
                    device.process_dump = device.process_dump.replace(field + '=false', field + '=true')
                    with self.assertRaisesRegex(ValueError, 'crashing or nonresponding'):
                        phase(device)
                    self.assertEqual([], device.mutations())

    def test_error_window_and_foreign_focus_do_not_become_ready(self):
        device = self.device()
        device.window_dump = device.window_dump.replace(emulator.TEST_HOME, 'Application Not Responding: com.android.systemui')
        with self.assertRaisesRegex(ValueError, 'error window obstructs'):
            emulator.settle_display(device)
        self.assertEqual([], device.mutations())
        for focus in ('com.android.settings/.FallbackHome', 'com.android.systemui/.SomeWindow',
                      emulator.TEST_HOME.replace('.test/', '/')):
            with self.subTest(focus=focus):
                device = self.device()
                device.window_dump = device.window_dump.replace(emulator.TEST_HOME, focus)
                now, wait = self.clock()
                with self.assertRaisesRegex(TimeoutError, 'last focus='):
                    emulator.settle_display(device, now=now, wait=wait, ready_timeout=3)
                self.assertEqual(3, now())
                self.assertEqual([], device.mutations())

    def test_exiting_home_waits_for_a_live_foreground_without_relaunching(self):
        device = self.device()
        ready = device.window_dump
        exiting = ready.replace(emulator.TEST_HOME + '}', emulator.TEST_HOME + ' EXITING}')
        device.window_dump = exiting
        now, wait = self.clock()
        with self.assertRaisesRegex(TimeoutError, 'last focus=.*EXITING'):
            emulator.settle_display(device, now=now, wait=wait, ready_timeout=3)
        self.assertEqual(3, now())
        self.assertEqual([], device.mutations())

        device = self.device()
        device.window_states = [exiting]
        now, wait = self.clock()
        report = emulator.settle_display(device, now=now, wait=wait, ready_timeout=3)
        self.assertEqual('READY', report['status'])
        self.assertEqual(2, report['readiness_attempts'])
        self.assertEqual(1, now())
        self.assertEqual([], device.mutations())
        self.assertEqual(1, sum('wait-for-broadcast-idle' in command for command, _ in device.calls))
        self.assertEqual(1, sum('wait-for-application-barrier' in command for command, _ in device.calls))

    def test_empty_malformed_or_ambiguous_dumps_fail_closed(self):
        valid_processes = self.device().process_dump
        invalid_processes = ('', 'permission denied', 'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)',
                             valid_processes.replace('mNotResponding=false', 'mNotResponding='),
                             valid_processes.replace('mCrashing=false', 'mCrashing=unknown'),
                             valid_processes.replace('/u0a99}', '/'),
                             valid_processes + "*** SERVICE 'activity' DUMP TIMEOUT (10000ms) EXPIRED ***\n")
        for output in invalid_processes:
            device = self.device()
            device.process_dump = output
            with self.assertRaises(ValueError):
                emulator.settle_display(device)
        ready = self.device().window_dump
        invalid_windows = ('', 'WINDOW MANAGER', ready.replace('mWaitingForConfig=false', 'mWaitingForConfig=unknown'),
                           ready.replace('Window{abc u0 ', '').replace('}', ''),
                           ready + 'mCurrentFocus=null\n', ready + 'mWaitingForConfig=unknown\n',
                           ready + 'mDisplayFrozen=unknown\n',
                           ready + "*** SERVICE 'window' DUMP TIMEOUT (10000ms) EXPIRED ***\n",
                           ready + 'Error dumping service info: failed\n')
        for output in invalid_windows:
            with self.subTest(output=output):
                device = self.device()
                device.window_dump = output
                with self.assertRaises(ValueError):
                    emulator.settle_display(device)

    def test_exact_display_configuration_and_selected_home_are_required(self):
        invalid = [('display_size', ''), ('display_size', 'Physical size: 2560x1800'),
                   ('display_size', 'Override size: 640x1280\nOverride size: 640x1280'),
                   ('display_size', 'Physical size: 640x1280\nOverride size: malformed'),
                   ('display_density', 'Override density: 240'), ('display_density', 'unknown'),
                   ('font_scale', '1.0'), ('font_scale', 'NaN'), ('font_scale', ''),
                   ('home', 'com.android.launcher3/.Launcher')]
        for field, value in invalid:
            with self.subTest(field=field, value=value):
                device = self.device()
                setattr(device, field, value)
                with self.assertRaises(ValueError):
                    emulator.settle_display(device)
                self.assertEqual([], device.mutations())

    def test_wide_viewport_can_be_verified_without_changing_any_setting(self):
        device = self.device()
        device.display_size = 'Physical size: 2560x1800\nOverride size: 1200x2560'
        device.window_dump += 'mDisplayFrozen=false\n'
        self.assertEqual('1200x2560', emulator.settle_display(device, size='1200x2560')['display_size'])
        self.assertEqual([], device.mutations())

    def test_invalid_budgets_and_configuration_never_call_adb(self):
        for options in ({'ready_timeout': 0}, {'ready_timeout': 61}, {'ready_timeout': float('nan')},
                        {'size': '640x1280; reboot'}, {'density': 0}, {'font_scale': float('inf')}):
            device = self.device()
            with self.assertRaises(ValueError):
                emulator.settle_display(device, **options)
            self.assertEqual([], device.calls)

    def test_failed_display_phase_retains_blocked_report_and_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'display.json'
            with patch.object(sys, 'argv', ['prepare_emulator_ui.py', '--settle-display', '--output', str(output)]), patch.object(emulator, 'settle_display', side_effect=ValueError('System UI is not responding')):
                with self.assertRaises(ValueError):
                    emulator.main()
            report = json.loads(output.read_text())
            self.assertEqual('BLOCKED', report['status'])
            self.assertEqual('display', report['phase'])
            self.assertFalse(report['app_under_test_changed'])
            self.assertFalse(report['system_error_dialogs_suppressed'])


class AcceptanceSetupOrderTest(unittest.TestCase):
    """Execute the real shell runner with fake ADB/Python boundaries, never a device."""
    def run_fixture(self, directory, failure):
        folder = Path(directory)
        binaries = folder / 'bin'
        binaries.mkdir()
        log = folder / 'commands.txt'
        adb = binaries / 'adb'
        adb.write_text('''#!/bin/sh
printf 'adb %s\\n' "$*" >> "$EMULATOR_TEST_LOG"
if [ "$1" = logcat ]; then
  count=0
  if [ -f "$EMULATOR_TEST_LOG.count" ]; then read -r count < "$EMULATOR_TEST_LOG.count"; fi
  count=$((count + 1))
  printf '%s\\n' "$count" > "$EMULATOR_TEST_LOG.count"
  if [ "$EMULATOR_TEST_FAILURE" = normal-logcat ] && [ "$count" = 3 ]; then exit 37; fi
fi
printf 'retained emulator evidence\\n'
if [ "$1 $2" = 'shell am' ] && [ "$3" = instrument ] && [ "$EMULATOR_TEST_FAILURE" = after-setup ]; then exit 55; fi
if [ "$EMULATOR_TEST_FAILURE" = voice-conversation ]; then
  case "$*" in
    *VoiceConversationInstrumentedTest*) exit 56 ;;
  esac
fi
if [ "$EMULATOR_TEST_FAILURE" = voice-message-controls ]; then
  case "$*" in
    *VoiceMessageControlsInstrumentedTest*) exit 57 ;;
  esac
fi
if [ "$EMULATOR_TEST_FAILURE" = voice-transcript ]; then
  case "$*" in
    *VoiceTranscriptInstrumentedTest*) exit 58 ;;
  esac
fi
''')
        python = binaries / 'python3'
        python.write_text('''#!/bin/sh
printf 'python3 %s\\n' "$*" >> "$EMULATOR_TEST_LOG"
case "$1" in
  scripts/select_android_test_apk.py) printf 'fixture.apk\\n' ;;
  scripts/prepare_emulator_ui.py)
    case "$*" in
      *--settle-display*) if [ "$EMULATOR_TEST_FAILURE" = display ]; then exit 32; fi ;;
      *) if [ "$EMULATOR_TEST_FAILURE" = home ]; then exit 31; fi ;;
    esac ;;
esac
''')
        adb.chmod(0o755)
        python.chmod(0o755)
        env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                   EMULATOR_TEST_LOG=str(log), EMULATOR_TEST_FAILURE=failure)
        runner = Path(__file__).resolve().parents[1] / 'run_release0950_acceptance.sh'
        result = subprocess.run(['bash', str(runner)], cwd=folder, env=env, text=True, capture_output=True, timeout=20)
        return result, log.read_text().splitlines(), folder / 'app/build/acceptance-0950'

    def test_home_or_display_failure_retains_diagnostics_and_prevents_instrumentation(self):
        for phase, status in (('home', 31), ('display', 32)):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                result, commands, artifacts = self.run_fixture(directory, phase)
                self.assertEqual(status, result.returncode, result.stderr)
                self.assertFalse(any('am instrument' in command for command in commands))
                self.assertEqual('adb logcat -d', commands[0])
                self.assertFalse(any('logcat -c' in command for command in commands))
                for name in ('boot-logcat.txt', 'exit-logcat.txt', 'window-state.txt', 'process-state.txt', 'last-anr.txt'):
                    self.assertIn('retained emulator evidence', (artifacts / name).read_text())

    def test_display_readiness_precedes_tests_and_diagnostics_do_not_replace_test_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            result, commands, artifacts = self.run_fixture(directory, 'after-setup')
            self.assertEqual(55, result.returncode, result.stderr)
            home = next(i for i, command in enumerate(commands) if 'prepare_emulator_ui.py --output' in command)
            size = commands.index('adb shell wm size 640x1280')
            configured = commands.index('adb shell settings put system font_scale 2.0')
            ready = next(i for i, command in enumerate(commands) if 'prepare_emulator_ui.py --settle-display' in command)
            test = next(i for i, command in enumerate(commands) if 'am instrument' in command)
            self.assertLess(home, size)
            self.assertLess(configured, ready)
            self.assertLess(ready, test)
            self.assertFalse(any('logcat -c' in command for command in commands))
            for name in ('boot-logcat.txt', 'setup-logcat.txt', 'logcat.txt', 'exit-logcat.txt'):
                self.assertIn('retained emulator evidence', (artifacts / name).read_text())

    def test_normal_logcat_capture_failure_remains_fatal_and_exit_capture_is_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _, artifacts = self.run_fixture(directory, 'normal-logcat')
            self.assertEqual(37, result.returncode, result.stderr)
            self.assertEqual('', (artifacts / 'logcat.txt').read_text())
            self.assertIn('retained emulator evidence', (artifacts / 'exit-logcat.txt').read_text())

    def test_voice_conversation_failure_remains_fatal_and_retains_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            result, commands, artifacts = self.run_fixture(directory, 'voice-conversation')
            self.assertEqual(56, result.returncode, result.stderr)
            invocations = [command for command in commands if 'am instrument' in command and 'VoiceConversationInstrumentedTest' in command]
            self.assertEqual(1, len(invocations))
            for name in ('voice-conversation-instrumentation.txt', 'logcat.txt', 'exit-logcat.txt', 'window-state.txt', 'process-state.txt', 'last-anr.txt'):
                self.assertIn('retained emulator evidence', (artifacts / name).read_text())

    def test_voice_message_controls_failure_remains_fatal_and_retains_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            result, commands, artifacts = self.run_fixture(directory, 'voice-message-controls')
            self.assertEqual(57, result.returncode, result.stderr)
            invocations = [command for command in commands if 'am instrument' in command and 'VoiceMessageControlsInstrumentedTest' in command]
            self.assertEqual(1, len(invocations))
            for name in ('voice-message-controls-instrumentation.txt', 'logcat.txt', 'exit-logcat.txt', 'window-state.txt', 'process-state.txt', 'last-anr.txt'):
                self.assertIn('retained emulator evidence', (artifacts / name).read_text())

    def test_voice_transcript_failure_remains_fatal_and_retains_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            result, commands, artifacts = self.run_fixture(directory, 'voice-transcript')
            self.assertEqual(58, result.returncode, result.stderr)
            invocations = [command for command in commands if 'am instrument' in command and 'VoiceTranscriptInstrumentedTest' in command]
            self.assertEqual(1, len(invocations))
            for name in ('voice-transcript-instrumentation.txt', 'logcat.txt', 'exit-logcat.txt', 'window-state.txt', 'process-state.txt', 'last-anr.txt'):
                self.assertIn('retained emulator evidence', (artifacts / name).read_text())


if __name__ == '__main__':
    unittest.main()
