#!/usr/bin/env python3
"""Isolate emulator fixtures from the unused launcher's startup ANR windows."""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import time

# These are system-image home applications, never the app being qualified.
LAUNCHERS = {'com.google.android.apps.nexuslauncher', 'com.android.launcher3'}


def prepare(run=subprocess.run, now=time.monotonic, wait=time.sleep, ready_timeout=60):
    if not math.isfinite(ready_timeout) or not 0 < ready_timeout <= 60:
        raise ValueError('Emulator readiness budget must be between zero and 60 seconds')
    started = now()
    deadline = started + ready_timeout
    def shell(*arguments):
        remaining = deadline - now()
        if remaining <= 0:
            raise TimeoutError('Emulator preparation exceeded its readiness budget')
        result = run(['adb', 'shell', *arguments], check=True, text=True, capture_output=True, timeout=min(10, remaining))
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
            # failed disable/force-stop operation below is never replayed.
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
            if package in LAUNCHERS:
                break
        # sys.boot_completed can precede user unlock and the real home. Android's
        # FallbackHome itself waits for that transition; observe it read-only.
        # https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-14.0.0_r1/src/com/android/settings/FallbackHome.java
        wait(min(delay, max(0, deadline - now())))
        delay = min(5, delay * 2)
    # The fixtures launch their own Activity. The stock launcher is unrelated to
    # app navigation and can ANR during density/font changes on cold API 34 images.
    # Do not suppress system error dialogs: app ANRs must remain observable.
    shell('pm', 'disable-user', '--user', '0', package)
    shell('am', 'force-stop', package)
    return {'schema_version': 1, 'status': 'PREPARED', 'home_component': component,
            'readiness_attempts': attempts, 'readiness_seconds': round(now() - started, 3),
            'disabled_system_launcher': package, 'app_under_test_changed': False,
            'system_error_dialogs_suppressed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        report = prepare()
    except (ValueError, TimeoutError, subprocess.SubprocessError) as failure:
        args.output.write_text(json.dumps({'schema_version': 1, 'status': 'BLOCKED', 'reason': str(failure)[:500],
                                          'app_under_test_changed': False, 'system_error_dialogs_suppressed': False}, indent=2) + '\n')
        raise
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Emulator launcher isolated; app ANR reporting remains enabled.')


if __name__ == '__main__':
    main()
