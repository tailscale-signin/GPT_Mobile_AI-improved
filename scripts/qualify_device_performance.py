#!/usr/bin/env python3
"""Collect a reference-device candidate and compare only with a reviewed matching baseline."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from check_performance_results import check, identity


def qualify(baseline, current):
    if not current.get('validationConditions') or baseline.get('validationConditions') != current['validationConditions']:
        raise ValueError('Physical device, build type, animation settings and workload revision must match the reviewed baseline')
    return check(baseline, current, 0.15)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='')
    parser.add_argument('--output', type=Path, default=Path('device-evidence/performance-candidate.json'))
    args = parser.parse_args()
    def adb(*values):
        return subprocess.check_output(['adb', *values], text=True).strip()
    files = list(Path('macrobenchmark/build/outputs').rglob('*benchmarkData.json'))
    if not files:
        raise SystemExit('No measured benchmark data was produced')
    docs = [json.loads(p.read_text()) for p in files]
    if len({identity(doc) for doc in docs}) != 1:
        raise SystemExit('Mixed benchmark device identities')
    current = dict(docs[0])
    benchmarks = {}
    for doc in docs:
        for benchmark in doc.get('benchmarks', []):
            if benchmark['name'] in benchmarks and benchmarks[benchmark['name']] != benchmark:
                raise SystemExit('Ambiguous duplicate benchmark measurements')
            benchmarks[benchmark['name']] = benchmark
    current['benchmarks'] = list(benchmarks.values())
    current['candidateCommit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    current['validationConditions'] = {
        'deviceSerialHash': hashlib.sha256(adb('get-serialno').encode()).hexdigest(),
        'buildType': 'benchmark',
        'workloadRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD:macrobenchmark/src'], text=True).strip(),
        'animations': {key: adb('shell', 'settings', 'get', 'global', key) for key in ('animator_duration_scale', 'window_animation_scale', 'transition_animation_scale')},
    }
    args.output.write_text(json.dumps(current, indent=2) + '\n')
    status = {'qualification': 'NOT_QUALIFIED', 'reason': 'No reviewed reference-device baseline supplied', 'candidateCommit': current['candidateCommit']}
    if args.baseline:
        baseline_path = Path(args.baseline).resolve()
        baseline_root = Path('docs/implementation/performance-baselines').resolve()
        if not baseline_path.is_relative_to(baseline_root):
            raise SystemExit('Baseline must be a reviewed repository file under docs/implementation/performance-baselines')
        failures = qualify(json.loads(baseline_path.read_text()), current)
        status = {'qualification': 'FAIL' if failures else 'PASS', 'failures': failures, 'candidateCommit': current['candidateCommit']}
    Path('device-evidence/performance-status.json').write_text(json.dumps(status, indent=2) + '\n')
    print(json.dumps(status))
    if status['qualification'] == 'FAIL':
        raise SystemExit(1)
