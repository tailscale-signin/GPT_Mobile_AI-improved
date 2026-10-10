"""Run isolated legacy suites, v14 integration/fault tests and browser checks."""
import subprocess
import os
import sys
from pathlib import Path


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    root = Path(__file__).resolve().parent
    failures = []
    suites = [p.name for p in sorted((root / 'tests').glob('test_gateway_*.py')) if not p.name.startswith('test_gateway_v14')]
    suites.append('test_gateway_v14*.py')
    for pattern in suites:
        result = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(root / 'tests'), '-p', pattern],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', timeout=120,
                                env=dict(os.environ, PYTHONIOENCODING='utf-8'))
        summary = [line for line in result.stdout.splitlines() if line.startswith(('Ran ', 'OK', 'FAILED'))]
        print(pattern + ': ' + '; '.join(summary), flush=True)
        if result.returncode:
            print(result.stdout)
            failures.append(pattern)
    result = subprocess.run(['node', '--test', str(root / 'tests/test_gateway_browser_bridge.mjs')], timeout=30)
    if result.returncode:
        failures.append('browser bridge')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
