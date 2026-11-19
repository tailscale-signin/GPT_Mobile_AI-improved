"""Build and smoke the one-time update understood by installed v17 clients."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from build_windows import ROOT, VERSION

TRANSITION = '17.0.2'


def main():
    if VERSION != '18.0.0':
        return
    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp) / 'gateway-transition'
        subprocess.run(['git', 'worktree', 'add', '--detach', str(work), 'HEAD'], cwd=ROOT, check=True)
        try:
            # The v17 supervisor checks the ZIP name, manifest and live runtime
            # version. Build a genuine v17 patch with the new discovery logic.
            version = work / 'gateway/v14/__init__.py'
            source = version.read_text(encoding='utf-8')
            if source.count('VERSION = "18.0.0"') != 1:
                raise ValueError('Unexpected transition source')
            version.write_text(source.replace('VERSION = "18.0.0"', f'VERSION = "{TRANSITION}"'),
                               encoding='utf-8', newline='\n')
            for script in (['gateway/build_v14_package.py', '--manifest-only'], ['gateway/desktop/build_windows.py'],
                           ['gateway/desktop/smoke_windows.py', '--upgrade-archive',
                            str(ROOT / 'dist/windows/Gateway-18.0.0-windows-x64.zip')]):
                subprocess.run([sys.executable, *script], cwd=work, check=True)
            out = ROOT / 'dist/windows/v17-transition'
            out.mkdir(parents=True, exist_ok=True)
            for name in (f'Gateway-{TRANSITION}-windows-x64.zip', f'Gateway-{TRANSITION}-windows-x64-setup.exe',
                         'SHA256SUMS.txt', 'smoke-results.json'):
                shutil.copy2(work / 'dist/windows' / name, out / name)
        finally:
            subprocess.run(['git', 'worktree', 'remove', '--force', str(work)], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
