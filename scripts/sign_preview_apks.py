#!/usr/bin/env python3
"""Sign preview APKs with the standard Android debug key, then verify before promotion."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def sign_previews(directory, apksigner, keystore, run=subprocess.run):
    apks = sorted(path for path in directory.glob('*.apk') if not path.name.startswith('.'))
    if not apks:
        raise ValueError('No preview APKs were produced')
    if not apksigner.is_file() or not keystore.is_file():
        raise ValueError('The Android apksigner and debug keystore are required')
    for apk in apks:
        destination = apk.with_name(apk.name.removesuffix('-unsigned.apk') + '.apk') if apk.name.endswith('-unsigned.apk') else apk
        if destination != apk and destination.exists():
            raise ValueError(f'Refusing to replace an existing APK: {destination.name}')
        with tempfile.TemporaryDirectory(prefix='.signing-', dir=directory) as temporary:
            signed = Path(temporary) / apk.name
            run([str(apksigner), 'sign', '--ks', str(keystore), '--ks-key-alias', 'androiddebugkey',
                 '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                 '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
                 '--out', str(signed), str(apk)], check=True, timeout=300)
            verification = run([str(apksigner), 'verify', '--verbose', str(signed)],
                               check=True, timeout=300, capture_output=True, text=True)
            for scheme in ('v2', 'v3'):
                if not any(line.startswith(f'Verified using {scheme} scheme ') and line.endswith(': true')
                           for line in verification.stdout.splitlines()):
                    raise ValueError(f'{apk.name}: {scheme} signature was not verified')
            os.replace(signed, destination)
            if destination != apk:
                apk.unlink()
            print(f'{destination.name}: v2 and v3 signatures verified', flush=True)
    return len(apks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    args = parser.parse_args()
    sign_previews(args.directory, args.apksigner, args.keystore)


if __name__ == '__main__':
    main()
