"""Run isolated legacy suites, v14 integration/fault tests and browser checks."""
import subprocess
import os
import sys
from pathlib import Path

STRESS_SUITE = 'test_gateway_diagnostic_stress.py'


def run_suite(root, pattern, timeout=120):
    # Print the blocked test and all Python thread stacks before the hard
    # deadline. Keep the deadline and failure result, including on Windows.
    # The former aggregate's first module added this runtime import root for
    # later files. Each isolated child needs the same explicit source root.
    runner = ('import faulthandler,runpy,sys; '
              f'sys.path.insert(0, {str(root)!r}); '
              f'faulthandler.dump_traceback_later({max(1, timeout - 20)}); '
              'runpy.run_module("unittest", run_name="__main__")')
    try:
        result = subprocess.run([sys.executable, '-u', '-c', runner, 'discover', '-v', '-s', str(root / 'tests'), '-p', pattern],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', timeout=timeout,
                                env=dict(os.environ, PYTHONIOENCODING='utf-8'))
    except subprocess.TimeoutExpired as error:
        output = error.output or ''
        if isinstance(output, bytes):
            output = output.decode('utf-8', errors='replace')
        print(f'{pattern}: FAILED (suite exceeded {timeout}s)', flush=True)
        print(output, flush=True)
        return False
    summary = [line for line in result.stdout.splitlines()
               if line.startswith(('Ran ', 'OK', 'FAILED')) or 'Durable diagnostic stress:' in line]
    print(pattern + ': ' + '; '.join(summary), flush=True)
    if result.returncode:
        print(result.stdout, flush=True)
    return result.returncode == 0


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    root = Path(__file__).resolve().parent
    failures = []
    # Apply the existing per-file deadline to v14 too. Bundling its 294 cases
    # into one subprocess makes cumulative Windows I/O compete for one 120s
    # deadline even when every individual test continues making progress.
    suites = [p.name for p in sorted((root / 'tests').glob('test_gateway_*.py'))
              if p.name != STRESS_SUITE]
    suites.append(STRESS_SUITE)
    for pattern in suites:
        # Fifty thousand durable events require 782 FULL-sync batch commits.
        # This is a liveness watchdog, not a throughput acceptance threshold.
        # Windows FULL-sync commits can keep progressing beyond 300 seconds;
        # allow this isolated workload 600s and keep ordinary groups at 120s.
        if not run_suite(root, pattern, timeout=600 if pattern == STRESS_SUITE else 120):
            failures.append(pattern)
    result = subprocess.run(['node', '--test', str(root / 'tests/test_gateway_browser_bridge.mjs')], timeout=30)
    if result.returncode:
        failures.append('browser bridge')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
