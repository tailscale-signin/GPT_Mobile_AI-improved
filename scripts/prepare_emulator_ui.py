#!/usr/bin/env python3
"""Select a test home and verify bounded emulator readiness without hiding ANRs."""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import time

# These are system-image home applications, never the app being qualified.
LAUNCHERS = {'com.google.android.apps.nexuslauncher', 'com.android.launcher3'}
TEST_HOME = 'dev.melo.gptmobile.improved.test/dev.chungjungsoo.gptmobile.test.EmulatorHomeActivity'
BROADCAST_IDLE = 'All broadcast queues are idle!'
APPLICATION_IDLE = 'Finished application barriers!'


def require_barrier(output, marker):
    # Both Android 14 and 16 return exit zero even when the application barrier
    # prints "Gave up waiting for application barriers!" after its own timeout.
    # Source: aosp-mirror/platform_frameworks_base, android-16.0.0_r1,
    # services/core/java/com/android/server/am/ActivityManagerService.java.
    lines = [line.strip() for line in output.splitlines()]
    if marker not in lines or any(re.match(r'(Gave up\b|Error:|Exception:|Unknown command:|Permission Denial:)', line, re.IGNORECASE) for line in lines):
        raise ValueError(f'Android did not confirm {marker} Last output: {output[-300:]}')


def reject_dump_failure(output):
    # Native dumpsys can return zero after writing a healthy prefix followed by
    # an explicit service-dump timeout. A partial observation is not readiness.
    if re.search(r'^\s*(?:\*{3}.*DUMP (?:TIMEOUT|FAILED)|Error dumping service info|Permission Denial:|Can.t find service:|Exception occurred while dumping)', output, re.MULTILINE | re.IGNORECASE):
        raise ValueError('Android readiness dump failed or timed out')


def reject_process_errors(output):
    reject_dump_failure(output)
    if ('ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)' not in output or
            not re.search(r'ProcessRecord\{[^}\n]*:com\.android\.systemui/[^}\n]+\}', output)):
        raise ValueError('Android process readiness dump was empty or unrecognized')
    error_states = re.findall(r'\bm(?:Crashing|NotResponding)=([^\s]*)', output)
    if any(state not in ('true', 'false') for state in error_states):
        raise ValueError('Android process error state was malformed')
    if 'true' in error_states:
        raise ValueError('Android reports a crashing or nonresponding process; retain diagnostics without dismissing its error dialog')


def effective_display_value(output, kind):
    pattern = r'[1-9][0-9]*x[1-9][0-9]*' if kind == 'size' else r'[1-9][0-9]*'
    matches = [re.fullmatch(r'(Physical|Override) ' + kind + r': (' + pattern + r')', line.strip()) for line in output.splitlines()]
    if not matches or any(match is None for match in matches):
        raise ValueError(f'Android display {kind} was empty, ambiguous or unrecognized')
    values = [match.groups() for match in matches]
    if 'Physical' not in dict(values) or len({key for key, _ in values}) != len(values):
        raise ValueError(f'Android display {kind} was empty, ambiguous or unrecognized')
    return dict(values).get('Override', dict(values).get('Physical'))


def prepare(run=subprocess.run, now=time.monotonic, wait=time.sleep, ready_timeout=60):
    if not math.isfinite(ready_timeout) or not 0 < ready_timeout <= 60:
        raise ValueError('Emulator readiness budget must be between zero and 60 seconds')
    started = now()
    deadline = started + ready_timeout
    def shell(*arguments, command_timeout=10):
        remaining = deadline - now()
        if remaining <= 0:
            raise TimeoutError('Emulator preparation exceeded its readiness budget')
        result = run(['adb', 'shell', *arguments], check=True, text=True, capture_output=True, timeout=min(command_timeout, remaining))
        if now() >= deadline:
            raise TimeoutError('Emulator preparation exceeded its readiness budget')
        return result.stdout.strip()
    attempts, delay, component = 0, 1, 'not observed'
    while True:
        remaining = deadline - now()
        if remaining <= 0:
            raise TimeoutError(f'No allowlisted emulator launcher became ready within {ready_timeout}s; last home={component}; no package was changed')
        attempts += 1
        try:
            home = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
            component = home.splitlines()[-1] if home else 'No activity found'
        except subprocess.CalledProcessError as failure:
            # Only read-only boot discovery can retry transport readiness. A
            # failed home selection/start below is never replayed.
            detail = (failure.stderr or '').lower()
            if not any(reason in detail for reason in ('device offline', 'device not found', 'no devices/emulators found', "can't find service: package")):
                raise
            component = 'ADB boot transport not ready'
        except subprocess.TimeoutExpired:
            component = 'ADB boot discovery timed out'
        else:
            if component != 'No activity found' and not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', component):
                raise ValueError('Malformed emulator home component; no package was changed')
            package = component.split('/')[0]
            if package in LAUNCHERS or component == TEST_HOME:
                break
        # sys.boot_completed can precede user unlock and the real home. Android's
        # FallbackHome itself waits for that transition; observe it read-only.
        # https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-14.0.0_r1/src/com/android/settings/FallbackHome.java
        wait(min(delay, max(0, deadline - now())))
        delay = min(5, delay * 2)
    # Keep a real HOME candidate selected before density/font changes. SystemUI
    # still binds the stock launcher's QuickStep service independently of HOME;
    # killing that process forces taskbar/service reconstruction during setup.
    installed = shell('cmd', 'package', 'resolve-activity', '--brief', '-n', TEST_HOME)
    if (installed.splitlines()[-1] if installed else '') != TEST_HOME:
        raise ValueError('The instrumentation home is missing; install the test APK before preparing the emulator; no home was changed')
    boot_barrier_started = now()
    require_barrier(shell('am', 'wait-for-broadcast-idle', '--flush-broadcast-loopers', command_timeout=ready_timeout), BROADCAST_IDLE)
    reject_process_errors(shell('dumpsys', 'activity', 'processes'))
    boot_barrier_seconds = round(now() - boot_barrier_started, 3)
    selection_started = now()
    if component != TEST_HOME:
        # Android waits for an asynchronous RoleManager callback here. On cold
        # API 34 runners this has exceeded the 10-second read-command limit.
        # Give this single mutation the remaining readiness budget; never replay
        # it or accept a timeout as success. The total budget remains <= 60s.
        selected = shell('cmd', 'package', 'set-home-activity', '--user', '0', TEST_HOME, command_timeout=ready_timeout)
        if selected != 'Success':
            raise ValueError('Android did not acknowledge selecting the instrumentation home; inspect emulator state before retrying')
    selection_seconds = round(now() - selection_started, 3)
    observed = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    if (observed.splitlines()[-1] if observed else '') != TEST_HOME:
        raise ValueError('The selected home does not resolve to the instrumentation fixture; inspect emulator state before retrying')
    launch = shell('am', 'start', '-W', '--user', '0', '-n', TEST_HOME)
    if not re.search(r'^Status: ok\s*$', launch, re.MULTILINE) or not re.search(r'^Activity: ' + re.escape(TEST_HOME) + r'\s*$', launch, re.MULTILINE):
        raise ValueError('The instrumentation home did not report a successful launch; inspect emulator state before retrying')
    return {'schema_version': 3, 'status': 'PREPARED', 'home_component': component,
            'readiness_attempts': attempts, 'readiness_seconds': round(now() - started, 3),
            'boot_barrier_seconds': boot_barrier_seconds, 'home_selection_seconds': selection_seconds,
            'selected_test_home': TEST_HOME, 'stopped_system_launcher': None,
            'system_launcher_disabled_by_setup': False, 'app_under_test_changed': False,
            'system_error_dialogs_suppressed': False}


def settle_display(run=subprocess.run, now=time.monotonic, wait=time.sleep, ready_timeout=60,
                   size='640x1280', density=320, font_scale=2.0):
    if not math.isfinite(ready_timeout) or not 0 < ready_timeout <= 60:
        raise ValueError('Emulator readiness budget must be between zero and 60 seconds')
    if not re.fullmatch(r'[1-9][0-9]*x[1-9][0-9]*', size) or density <= 0 or not math.isfinite(font_scale) or font_scale <= 0:
        raise ValueError('Expected display configuration must be positive and finite')
    started = now()
    deadline = started + ready_timeout
    def shell(*arguments, command_timeout=10):
        remaining = deadline - now()
        if remaining <= 0:
            raise TimeoutError('Emulator display exceeded its readiness budget')
        result = run(['adb', 'shell', *arguments], check=True, text=True, capture_output=True, timeout=min(command_timeout, remaining))
        if now() >= deadline:
            raise TimeoutError('Emulator display exceeded its readiness budget')
        return result.stdout.strip()
    # sys.boot_completed and successful settings writes do not drain boot
    # receivers or configuration callbacks. These platform barriers exist on
    # both API 34 and 36; they observe work without restarting any process.
    require_barrier(shell('am', 'wait-for-broadcast-idle', '--flush-broadcast-loopers', command_timeout=ready_timeout), BROADCAST_IDLE)
    require_barrier(shell('am', 'wait-for-application-barrier', command_timeout=ready_timeout), APPLICATION_IDLE)
    observed_home = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    if (observed_home.splitlines()[-1] if observed_home else '') != TEST_HOME:
        raise ValueError('The configured display no longer resolves to the instrumentation home')
    actual_size = effective_display_value(shell('wm', 'size'), 'size')
    actual_density = effective_display_value(shell('wm', 'density'), 'density')
    actual_font = float(shell('settings', 'get', 'system', 'font_scale'))
    if actual_size != size or actual_density != str(density) or not math.isfinite(actual_font) or not math.isclose(actual_font, font_scale, abs_tol=0.0001):
        raise ValueError(f'Display configuration mismatch: size={actual_size}, density={actual_density}, font_scale={actual_font}')
    attempts, delay, focus = 0, 1, 'not observed'
    while now() < deadline:
        attempts += 1
        reject_process_errors(shell('dumpsys', 'activity', 'processes'))
        windows = shell('dumpsys', 'window', '-a')
        reject_dump_failure(windows)
        focuses = re.findall(r'^\s*mCurrentFocus=(.*)$', windows, re.MULTILINE)
        waiting = [value.strip() for value in re.findall(r'^\s*mWaitingForConfig=(.*)$', windows, re.MULTILINE)]
        frozen = re.findall(r'\bmDisplayFrozen=([^\s]*)', windows)
        if ('WINDOW MANAGER' not in windows or len(focuses) != 1 or len(waiting) != 1 or
                any(value not in ('true', 'false') for value in waiting + frozen)):
            raise ValueError('Android window readiness dump was empty, ambiguous or unrecognized')
        focus = focuses[0].strip()
        if focus != 'null' and not re.fullmatch(r'Window\{[^{}\r\n]+\}', focus):
            raise ValueError('Android focused window was malformed')
        if re.search(r'Not Responding|Application Error|App Error|aerr|anr', focus, re.IGNORECASE):
            raise ValueError(f'Android error window obstructs the fixture: {focus}')
        # WindowState appends " EXITING" while a window is being removed;
        # its component name alone does not establish a live HOME foreground.
        if re.fullmatch(r'Window\{[0-9a-fA-F]+ u0 ' + re.escape(TEST_HOME) + r'\}', focus) and 'true' not in waiting + frozen:
            return {'schema_version': 3, 'status': 'READY', 'selected_test_home': TEST_HOME,
                    'display_size': actual_size, 'density_dpi': density, 'font_scale': actual_font,
                    'foreground_window': focus, 'readiness_attempts': attempts,
                    'readiness_seconds': round(now() - started, 3), 'broadcast_idle': True,
                    'application_threads_drained': True, 'app_under_test_changed': False,
                    'system_error_dialogs_suppressed': False}
        # Only observe a pending window/configuration transition. Never dismiss
        # dialogs, relaunch HOME or repeat instrumentation to obtain readiness.
        wait(min(delay, max(0, deadline - now())))
        delay = min(5, delay * 2)
    raise TimeoutError(f'Emulator display did not settle within {ready_timeout}s; last focus={focus}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--settle-display', action='store_true')
    parser.add_argument('--size', default='640x1280')
    parser.add_argument('--density', type=int, default=320)
    parser.add_argument('--font-scale', type=float, default=2.0)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        report = settle_display(size=args.size, density=args.density, font_scale=args.font_scale) if args.settle_display else prepare()
    except (ValueError, TimeoutError, subprocess.SubprocessError) as failure:
        args.output.write_text(json.dumps({'schema_version': 3, 'status': 'BLOCKED', 'phase': 'display' if args.settle_display else 'home', 'reason': str(failure)[:500],
                                          'app_under_test_changed': False, 'system_error_dialogs_suppressed': False}, indent=2) + '\n')
        raise
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Emulator display ready; ANR reporting remains enabled.' if args.settle_display else 'Instrumentation home selected; ANR reporting remains enabled.')


if __name__ == '__main__':
    main()
