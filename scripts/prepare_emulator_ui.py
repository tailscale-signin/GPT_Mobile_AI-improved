#!/usr/bin/env python3
"""Isolate emulator fixtures from the unused launcher's startup ANR windows."""
import argparse
import json
from pathlib import Path
import re
import subprocess

# These are system-image home applications, never the app being qualified.
LAUNCHERS = {'com.google.android.apps.nexuslauncher', 'com.android.launcher3'}


def prepare(run=subprocess.run):
    def shell(*arguments):
        result = run(['adb', 'shell', *arguments], check=True, text=True, capture_output=True, timeout=30)
        return result.stdout.strip()
    home = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    component = home.splitlines()[-1] if home else ''
    if not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', component):
        raise ValueError('Emulator home component is unavailable; no package was changed')
    package = component.split('/')[0]
    if package not in LAUNCHERS:
        raise ValueError('Unrecognized emulator launcher; no package was changed')
    # The fixtures launch their own Activity. The stock launcher is unrelated to
    # app navigation and can ANR during density/font changes on cold API 34 images.
    # Do not suppress system error dialogs: app ANRs must remain observable.
    shell('pm', 'disable-user', '--user', '0', package)
    shell('am', 'force-stop', package)
    return {'schema_version': 1, 'status': 'PREPARED', 'home_component': component,
            'disabled_system_launcher': package, 'app_under_test_changed': False,
            'system_error_dialogs_suppressed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = prepare()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Emulator launcher isolated; app ANR reporting remains enabled.')


if __name__ == '__main__':
    main()
