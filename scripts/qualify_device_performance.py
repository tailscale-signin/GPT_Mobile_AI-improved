#!/usr/bin/env python3
"""Bind device performance to installed APK receipts and a reviewed matching baseline."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
from pathlib import Path
from check_performance_results import check, identity

PACKAGE = 'dev.melo.gptmobile.improved'
IDENTITY_FORMAT = 'gptmobile.device-performance-identity.v1'
ANIMATION_KEYS = ('animator_duration_scale', 'window_animation_scale', 'transition_animation_scale')


def valid_hash(value, length=64):
    return isinstance(value, str) and re.fullmatch(f'[a-f0-9]{{{length}}}', value) is not None


def apk_set_digest(base, splits):
    manifest = f'installed-apk-set-v1\nbase:{base}\n' + '\n'.join(f'split:{value}' for value in sorted(splits))
    return hashlib.sha256(manifest.encode()).hexdigest()


def adb(*values):
    return subprocess.check_output(['adb', *values], text=True, timeout=30, stderr=subprocess.DEVNULL).strip()


def git(*values):
    return subprocess.check_output(['git', *values], text=True, timeout=10).strip()


def installed_apks(read_adb, package=PACKAGE, *, allow_test_version_zero=False):
    if not isinstance(package, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', package):
        raise ValueError('Invalid installed package identity')
    if allow_test_version_zero and package == PACKAGE:
        raise ValueError('The target application must have a positive version code')
    def paths():
        rows = read_adb('shell', f'pm path {package}').splitlines()
        found = [row.removeprefix('package:').strip() for row in rows if row.startswith('package:')]
        if not found or len(found) > 64 or len(set(found)) != len(found) or any(not p.startswith('/') or not p.endswith('.apk') or any(ord(c) < 32 for c in p) for p in found):
            raise ValueError('Installed target APK paths are unavailable or incomplete')
        return sorted(found)

    def package_version():
        dump = read_adb('shell', f'dumpsys package {package}')
        versions = set(re.findall(r'^\s*versionCode=(\d+)\b', dump, re.MULTILINE))
        updates = set(re.findall(r'^\s*lastUpdateTime=(.+)$', dump, re.MULTILINE))
        # AGP test-only APKs can omit a manifest version, which Android reports as zero.
        # Their explicitly selected runner and exact installed bytes provide identity;
        # the target application's positive-version requirement remains unchanged.
        minimum_version = 0 if allow_test_version_zero else 1
        if len(versions) != 1 or int(next(iter(versions))) < minimum_version or len(updates) != 1:
            raise ValueError('Installed package version and update identity are unavailable')
        return int(next(iter(versions))), next(iter(updates))

    before_paths, before_version = paths(), package_version()
    base_paths = before_paths if len(before_paths) == 1 else [p for p in before_paths if Path(p).name == 'base.apk']
    if len(base_paths) != 1:
        raise ValueError('Installed base APK cannot be distinguished from splits')
    hashes, deadline = {}, time.monotonic() + 120
    for path in before_paths:
        if time.monotonic() >= deadline:
            raise ValueError('Installed APK identity capture exceeded its time budget')
        # adb shell reparses arguments. Treat package-manager paths as quoted remote-shell data.
        value = read_adb('shell', shlex.join(['sha256sum', '--', path]))
        match = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?.+', value)
        if match is None:
            raise ValueError('An installed APK did not produce a complete SHA-256 digest')
        hashes[path] = match[1].lower()
    if paths() != before_paths or package_version() != before_version:
        raise ValueError('Installed package changed during identity capture')
    base = hashes[base_paths[0]]
    splits = sorted(value for path, value in hashes.items() if path != base_paths[0])
    return {'packageName': package, 'versionCode': before_version[0], 'baseApkSha256': base,
            'splitApkSha256': splits, 'installedApkCount': len(before_paths), 'apkSetSha256': apk_set_digest(base, splits),
            'collectionMethod': 'ADB_PACKAGE_MANAGER_AND_INSTALLED_FILE_SHA256'}


def capture_identity(read_adb=adb, source_commit=None, workload_revision=None):
    serial = read_adb('get-serialno')
    if not serial or serial.lower() in {'unknown', 'offline', 'unauthorized', 'null'}:
        raise ValueError('A single available device is required')
    prop = lambda key: read_adb('shell', f'getprop {key}')
    sdk = prop('ro.build.version.sdk')
    if not sdk.isdigit() or not 1 <= int(sdk) <= 200:
        raise ValueError('Observed Android API level is unavailable')
    observed = {'device': prop('ro.product.device'), 'model': prop('ro.product.model'), 'fingerprint': prop('ro.build.fingerprint'), 'version': {'sdk': int(sdk)}}
    snapshot = {
        'format': IDENTITY_FORMAT, 'capturedAtEpochMs': int(time.time() * 1000),
        'candidateCommit': source_commit or git('rev-parse', 'HEAD'),
        'sourceIdentityMeaning': 'Checkout declaration; installed APK hashes do not independently verify source provenance.',
        'observedDevice': observed, 'supportedAbis': prop('ro.product.cpu.abilist').split(','),
        'installedArtifact': installed_apks(read_adb),
        'validationConditions': {
            'deviceSerialHash': hashlib.sha256(serial.encode()).hexdigest(), 'buildType': 'benchmark',
            'workloadRevision': workload_revision or git('rev-parse', 'HEAD:macrobenchmark/src'),
            'animations': {key: read_adb('shell', f'settings get global {key}') for key in ANIMATION_KEYS},
        },
    }
    validate_identity(snapshot)
    return snapshot


def validate_identity(snapshot):
    if not isinstance(snapshot, dict) or snapshot.get('format') != IDENTITY_FORMAT or type(snapshot.get('capturedAtEpochMs')) is not int or snapshot['capturedAtEpochMs'] <= 0:
        raise ValueError('A timestamped installed identity receipt is required')
    artifact = snapshot.get('installedArtifact', {})
    if not isinstance(artifact, dict):
        raise ValueError('Installed artifact identity must be an object')
    base, splits = artifact.get('baseApkSha256'), artifact.get('splitApkSha256')
    if (not valid_hash(base) or not isinstance(splits, list) or len(splits) >= 64 or any(not valid_hash(value) for value in splits) or
            artifact.get('installedApkCount') != len(splits) + 1 or artifact.get('apkSetSha256') != apk_set_digest(base, splits) or
            type(artifact.get('versionCode')) is not int or artifact['versionCode'] <= 0 or artifact.get('packageName') != PACKAGE):
        raise ValueError('A complete installed target APK identity is required')
    identity({'context': {'build': snapshot.get('observedDevice', {})}})
    abis = snapshot.get('supportedAbis', [])
    if not abis or any(value not in {'arm64-v8a', 'armeabi-v7a', 'armeabi', 'x86_64', 'x86', 'riscv64'} for value in abis) or not valid_hash(snapshot.get('candidateCommit'), 40):
        raise ValueError('Device ABI and exact candidate source declaration are required')
    conditions = snapshot.get('validationConditions', {})
    if not isinstance(conditions, dict) or not isinstance(conditions.get('animations'), dict):
        raise ValueError('Device validation conditions must be complete objects')
    if (not valid_hash(conditions.get('deviceSerialHash')) or conditions.get('buildType') != 'benchmark' or
            not valid_hash(conditions.get('workloadRevision'), 40) or set(conditions.get('animations', {})) != set(ANIMATION_KEYS) or
            any(not isinstance(value, str) or not re.fullmatch(r'\d+(?:\.\d+)?', value) for value in conditions['animations'].values())):
        raise ValueError('Complete device, workload and animation conditions are required')


def validate_pair(before, after, measured):
    validate_identity(before)
    validate_identity(after)
    if before.get('capturePhase') != 'BEFORE_MEASUREMENT' or after.get('capturePhase') != 'AFTER_MEASUREMENT' or before['capturedAtEpochMs'] >= after['capturedAtEpochMs']:
        raise ValueError('Before/after receipts must bracket the measurement session')
    for field in ('candidateCommit', 'observedDevice', 'supportedAbis', 'installedArtifact', 'validationConditions'):
        if before[field] != after[field]:
            raise ValueError(f'Observed {field} changed across the benchmark session')
    if identity(measured) != identity({'context': {'build': after['observedDevice']}}):
        raise ValueError('Measured results and observed device identities do not match')


def bind_measurement_identity(before, after, measured, input_files):
    validate_pair(before, after, measured)
    if not input_files:
        raise ValueError('Measured benchmark input files are required')
    inputs, observed_benchmarks = [], {}
    for path in input_files:
        before_stat = path.stat()
        modified = before_stat.st_mtime_ns // 1_000_000
        if not before['capturedAtEpochMs'] <= modified <= after['capturedAtEpochMs']:
            raise ValueError('Benchmark input is stale or outside the captured session')
        content = path.read_bytes()
        after_stat = path.stat()
        if before_stat.st_size != after_stat.st_size or before_stat.st_mtime_ns != after_stat.st_mtime_ns:
            raise ValueError('Benchmark input changed while its identity was captured')
        document = json.loads(content)
        if identity(document) != identity(measured):
            raise ValueError('Benchmark input device identity changed during collection')
        for benchmark in document.get('benchmarks', []):
            if benchmark['name'] in observed_benchmarks and observed_benchmarks[benchmark['name']] != benchmark:
                raise ValueError('Benchmark inputs contain conflicting measurements')
            observed_benchmarks[benchmark['name']] = benchmark
        inputs.append({'sha256': hashlib.sha256(content).hexdigest(), 'modifiedAtEpochMs': modified})
    if observed_benchmarks != {row['name']: row for row in measured.get('benchmarks', [])}:
        raise ValueError('Benchmark input hashes do not describe the measured candidate')
    return {'status': 'BOUND', 'method': 'INSTALLED_APK_BEFORE_AND_AFTER', 'before': before, 'after': after, 'measuredInputs': inputs,
            'meaning': 'Matching installed bytes and device observations bracket benchmark collection; this does not independently attest source provenance or a physical handset.'}


def validate_measurement_identity(document):
    provenance = document.get('environmentProvenance', {})
    if provenance.get('status') != 'BOUND' or provenance.get('method') != 'INSTALLED_APK_BEFORE_AND_AFTER':
        raise ValueError('Performance is not qualified without matching before/after installed APK receipts')
    before, after = provenance.get('before', {}), provenance.get('after', {})
    validate_pair(before, after, document)
    if document.get('candidateCommit') != after['candidateCommit'] or document.get('validationConditions') != after['validationConditions']:
        raise ValueError('Candidate declarations do not match retained identity receipts')
    inputs = provenance.get('measuredInputs', [])
    if not inputs or any(not valid_hash(row.get('sha256')) or not before['capturedAtEpochMs'] <= row.get('modifiedAtEpochMs', 0) <= after['capturedAtEpochMs'] for row in inputs):
        raise ValueError('Timestamped benchmark input identities are required')


def qualify(baseline, current):
    validate_measurement_identity(baseline)
    validate_measurement_identity(current)
    if baseline['validationConditions'] != current['validationConditions']:
        raise ValueError('Physical device, build type, animation settings and workload revision must match the reviewed baseline')
    return check(baseline, current, 0.15)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def file_digest(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(64 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def require_installed_universal(artifact, expected_apk):
    expected = file_digest(expected_apk)
    if artifact['installedApkCount'] != 1 or artifact['splitApkSha256'] or artifact['baseApkSha256'] != expected:
        raise ValueError('Installed APK bytes do not match the freshly built universal artifact')
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='')
    parser.add_argument('--output', type=Path, default=Path('device-evidence/performance-candidate.json'))
    parser.add_argument('--capture-before', type=Path, help='Capture installed identity before running benchmarks, then exit')
    parser.add_argument('--before-identity', type=Path, help='Receipt from --capture-before for this session')
    parser.add_argument('--expected-apk', type=Path, help='Require installed bytes to match this built universal APK during pre-capture')
    parser.add_argument('--measurements', type=Path, default=Path('macrobenchmark/build/outputs'), help='Fresh benchmark output directory for this session')
    args = parser.parse_args()
    write_json(Path('device-evidence/performance-status.json'), {'qualification': 'NOT_QUALIFIED', 'reason': 'CURRENT_SESSION_COLLECTION_IN_PROGRESS'})
    if args.capture_before:
        args.capture_before.unlink(missing_ok=True)
        snapshot = capture_identity()
        if args.expected_apk:
            snapshot['matchedBuiltApkSha256'] = require_installed_universal(snapshot['installedArtifact'], args.expected_apk)
        snapshot['capturePhase'] = 'BEFORE_MEASUREMENT'
        snapshot['capturedAtEpochMs'] = int(time.time() * 1000)
        write_json(args.capture_before, snapshot)
        print('Recorded installed identity before measurement; no performance qualification claimed.')
        return 0
    files = sorted(args.measurements.rglob('*benchmarkData.json'))
    if not files:
        raise ValueError('No measured benchmark data was produced')
    docs = [json.loads(p.read_text()) for p in files]
    if len({identity(doc) for doc in docs}) != 1:
        raise ValueError('Mixed benchmark device identities')
    current, benchmarks = dict(docs[0]), {}
    for doc in docs:
        for benchmark in doc.get('benchmarks', []):
            if benchmark['name'] in benchmarks and benchmarks[benchmark['name']] != benchmark:
                raise ValueError('Ambiguous duplicate benchmark measurements')
            benchmarks[benchmark['name']] = benchmark
    current['benchmarks'] = list(benchmarks.values())
    current['candidateCommit'] = git('rev-parse', 'HEAD')
    try:
        after = capture_identity(source_commit=current['candidateCommit'])
        after['capturePhase'] = 'AFTER_MEASUREMENT'
        current['validationConditions'] = after['validationConditions']
        if args.before_identity is None:
            current['environmentProvenance'] = {'status': 'UNBOUND', 'reason': 'BEFORE_MEASUREMENT_IDENTITY_MISSING', 'after': after}
        else:
            current['environmentProvenance'] = bind_measurement_identity(json.loads(args.before_identity.read_text()), after, current, files)
    except (ValueError, OSError, subprocess.SubprocessError):
        current['environmentProvenance'] = {'status': 'UNAVAILABLE', 'reason': 'INSTALLED_IDENTITY_UNAVAILABLE_OR_MISMATCHED'}
    write_json(args.output, current)
    status = {'qualification': 'NOT_QUALIFIED', 'reason': 'No reviewed reference-device baseline supplied', 'candidateCommit': current['candidateCommit']}
    if current['environmentProvenance']['status'] != 'BOUND':
        status['reason'] = current['environmentProvenance']['reason']
    elif args.baseline:
        baseline_path = Path(args.baseline).resolve()
        if not baseline_path.is_relative_to(Path('docs/implementation/performance-baselines').resolve()):
            raise ValueError('Baseline must be reviewed under docs/implementation/performance-baselines')
        try:
            failures = qualify(json.loads(baseline_path.read_text()), current)
            status = {'qualification': 'FAIL' if failures else 'PASS', 'failures': failures, 'candidateCommit': current['candidateCommit']}
        except (OSError, ValueError):
            status['reason'] = 'REVIEWED_BASELINE_IDENTITY_OR_MEASUREMENTS_INVALID'
    write_json(Path('device-evidence/performance-status.json'), status)
    print(json.dumps(status))
    return 1 if status['qualification'] == 'FAIL' or args.baseline and status['qualification'] != 'PASS' else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        # Never leave a stale PASS behind when collection fails before candidate creation.
        reason = str(failure) if isinstance(failure, ValueError) else 'IDENTITY_OR_MEASUREMENT_COLLECTION_FAILED'
        write_json(Path('device-evidence/performance-status.json'), {'qualification': 'NOT_QUALIFIED', 'reason': reason})
        raise SystemExit(reason)
