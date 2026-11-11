import copy
import hashlib
import json
import os
from pathlib import Path
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualify_device_performance import ANIMATION_KEYS, IDENTITY_FORMAT, PACKAGE, apk_set_digest, bind_measurement_identity, capture_identity, installed_apks, qualify, require_installed_universal
import qualify_device_performance as performance


class DevicePerformanceQualificationTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {
            'context': {'build': {'device': 'reference', 'model': 'phone', 'fingerprint': 'build', 'version': {'sdk': 36}}},
            'candidateCommit': 'a' * 40,
            'validationConditions': {'deviceSerialHash': 'b' * 64, 'buildType': 'benchmark', 'workloadRevision': 'c' * 40, 'animations': {key: '0' for key in ANIMATION_KEYS}},
            'benchmarks': [{'name': 'startup', 'metrics': {'timeToInitialDisplayMs': {'median': 100}}}],
        }
        artifact = {'packageName': PACKAGE, 'versionCode': 96607, 'baseApkSha256': 'd' * 64, 'splitApkSha256': ['e' * 64], 'installedApkCount': 2, 'apkSetSha256': apk_set_digest('d' * 64, ['e' * 64])}
        before = {'format': IDENTITY_FORMAT, 'capturedAtEpochMs': 1000, 'capturePhase': 'BEFORE_MEASUREMENT', 'candidateCommit': self.baseline['candidateCommit'], 'observedDevice': copy.deepcopy(self.baseline['context']['build']), 'supportedAbis': ['arm64-v8a'], 'installedArtifact': artifact, 'validationConditions': copy.deepcopy(self.baseline['validationConditions'])}
        after = copy.deepcopy(before)
        after.update(capturePhase='AFTER_MEASUREMENT', capturedAtEpochMs=3000)
        self.baseline['environmentProvenance'] = {'status': 'BOUND', 'method': 'INSTALLED_APK_BEFORE_AND_AFTER', 'before': before, 'after': after, 'measuredInputs': [{'sha256': 'f' * 64, 'modifiedAtEpochMs': 2000}]}

    def test_matched_measurements_pass_and_regression_fails(self):
        self.assertEqual([], qualify(self.baseline, copy.deepcopy(self.baseline)))
        current = copy.deepcopy(self.baseline)
        current['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['median'] = 130
        self.assertTrue(qualify(self.baseline, current))

    def test_different_conditions_or_missing_metrics_are_not_qualified(self):
        for field in self.baseline['validationConditions']:
            current = copy.deepcopy(self.baseline)
            current['validationConditions'][field] = 'different'
            with self.subTest(field=field), self.assertRaises(ValueError):
                qualify(self.baseline, current)
        current = copy.deepcopy(self.baseline)
        current['benchmarks'] = []
        with self.assertRaises(ValueError):
            qualify(self.baseline, current)

    def test_missing_partial_or_after_only_artifact_identity_cannot_qualify(self):
        for mutation in ('missing', 'after-only', 'omitted-split', 'digest-mismatch', 'version-mismatch', 'wrong-device', 'stale-measurement'):
            current = copy.deepcopy(self.baseline)
            provenance = current['environmentProvenance']
            if mutation == 'missing':
                del current['environmentProvenance']
            elif mutation == 'after-only':
                provenance['status'] = 'UNBOUND'
            elif mutation == 'omitted-split':
                provenance['after']['installedArtifact']['splitApkSha256'] = []
            elif mutation == 'digest-mismatch':
                provenance['after']['installedArtifact']['apkSetSha256'] = '0' * 64
            elif mutation == 'version-mismatch':
                provenance['after']['installedArtifact']['versionCode'] += 1
            elif mutation == 'wrong-device':
                current['context']['build']['model'] = 'another'
            else:
                provenance['measuredInputs'][0]['modifiedAtEpochMs'] = 500
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                qualify(self.baseline, current)

    def test_candidate_can_differ_from_baseline_apk_but_must_be_stable_during_its_session(self):
        current = copy.deepcopy(self.baseline)
        current['candidateCommit'] = '9' * 40
        for phase in ('before', 'after'):
            receipt = current['environmentProvenance'][phase]
            receipt['candidateCommit'] = current['candidateCommit']
            receipt['installedArtifact']['baseApkSha256'] = '8' * 64
            receipt['installedArtifact']['apkSetSha256'] = apk_set_digest('8' * 64, ['e' * 64])
        self.assertEqual([], qualify(self.baseline, current))

    def test_binding_rejects_stale_inputs_and_records_exact_measured_file_hash(self):
        provenance = self.baseline['environmentProvenance']
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'benchmarkData.json'
            path.write_text(json.dumps(self.baseline))
            os.utime(path, ns=(2_000_000_000, 2_000_000_000))
            bound = bind_measurement_identity(provenance['before'], provenance['after'], self.baseline, [path])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), bound['measuredInputs'][0]['sha256'])
            os.utime(path, ns=(500_000_000, 500_000_000))
            with self.assertRaises(ValueError):
                bind_measurement_identity(provenance['before'], provenance['after'], self.baseline, [path])

    def fake_adb(self, *, missing=False, bad_split=False, changed=False, version='96607'):
        calls = []
        properties = {'ro.build.version.sdk': '36', 'ro.product.device': 'reference', 'ro.product.model': 'phone', 'ro.build.fingerprint': 'build', 'ro.product.cpu.abilist': 'arm64-v8a'}
        paths = ["/data/app/fixture 'private'/base.apk", "/data/app/fixture 'private'/split_config.arm64.apk"]
        def read(*values):
            calls.append(values)
            if values == ('get-serialno',):
                return 'private-device-serial'
            command = shlex.split(values[1])
            if command[:2] == ['pm', 'path']:
                if missing:
                    return ''
                if changed and sum('pm path' in row[-1] for row in calls) > 1:
                    return 'package:/data/app/replaced/base.apk'
                return '\n'.join('package:' + path for path in reversed(paths))
            if command[:2] == ['dumpsys', 'package']:
                return f'  versionCode={version} minSdk=31\n  lastUpdateTime=2026-11-10 00:00:00'
            if command[0] == 'sha256sum':
                self.assertEqual(['sha256sum', '--'], command[:2])
                self.assertIn(command[2], paths)
                if bad_split and command[2] != paths[0]:
                    return 'unreadable'
                return ('d' if command[2] == paths[0] else 'e') * 64 + '  ' + command[2]
            if command[0] == 'getprop':
                return properties[command[1]]
            if command[:3] == ['settings', 'get', 'global']:
                return '0'
            raise AssertionError(values)
        return read

    def test_capture_hashes_installed_base_and_splits_without_exporting_paths_or_serial(self):
        snapshot = capture_identity(self.fake_adb(), source_commit='a' * 40, workload_revision='c' * 40)
        artifact = snapshot['installedArtifact']
        self.assertEqual(96607, artifact['versionCode'])
        self.assertEqual(2, artifact['installedApkCount'])
        self.assertEqual(apk_set_digest('d' * 64, ['e' * 64]), artifact['apkSetSha256'])
        self.assertNotIn('private', json.dumps(snapshot))
        self.assertEqual(hashlib.sha256(b'private-device-serial').hexdigest(), snapshot['validationConditions']['deviceSerialHash'])

    def test_uninstalled_unreadable_or_replaced_packages_never_yield_partial_identity(self):
        for failure in ('missing', 'bad_split', 'changed'):
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                installed_apks(self.fake_adb(**{failure: True}))

    def test_explicit_test_apk_identity_accepts_zero_version_without_weakening_target_identity(self):
        test_package = 'dev.chungjungsoo.gptmobile.benchmark.test'
        artifact = installed_apks(self.fake_adb(version='0'), test_package, allow_test_version_zero=True)
        self.assertEqual(test_package, artifact['packageName'])
        self.assertEqual(0, artifact['versionCode'])
        self.assertEqual(apk_set_digest('d' * 64, ['e' * 64]), artifact['apkSetSha256'])
        with self.assertRaises(ValueError):
            installed_apks(self.fake_adb(version='0'), test_package)
        with self.assertRaises(ValueError):
            installed_apks(self.fake_adb(version='0'))
        with self.assertRaises(ValueError):
            installed_apks(self.fake_adb(version='0'), allow_test_version_zero=True)

    def test_test_apk_zero_version_exception_still_rejects_missing_or_ambiguous_versions(self):
        test_package = 'dev.chungjungsoo.gptmobile.benchmark.test'
        for version in ('', '-1', 'unknown', '0 minSdk=31\n  versionCode=1'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                installed_apks(self.fake_adb(version=version), test_package, allow_test_version_zero=True)

    def test_installed_universal_must_match_fresh_artifact_bytes_with_no_remaining_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'fixture.apk'
            apk.write_bytes(b'fresh APK bytes')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            artifact = {'installedApkCount': 1, 'splitApkSha256': [], 'baseApkSha256': digest}
            self.assertEqual(digest, require_installed_universal(artifact, apk))
            with self.assertRaises(ValueError):
                require_installed_universal({**artifact, 'baseApkSha256': 'f' * 64}, apk)
            with self.assertRaises(ValueError):
                require_installed_universal({**artifact, 'installedApkCount': 2, 'splitApkSha256': ['e' * 64]}, apk)

    def test_failed_new_pre_capture_removes_old_receipt_and_clears_stale_pass(self):
        prior = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                status = Path('device-evidence/performance-status.json')
                status.parent.mkdir()
                status.write_text('{"qualification":"PASS"}')
                receipt = Path('device-evidence/before.json')
                receipt.write_text(json.dumps(self.baseline['environmentProvenance']['before']))
                with patch.object(sys, 'argv', ['qualify_device_performance.py', '--capture-before', str(receipt)]), patch.object(performance, 'capture_identity', side_effect=ValueError('No installed APK')):
                    with self.assertRaises(ValueError):
                        performance.main()
                self.assertFalse(receipt.exists())
                self.assertEqual('NOT_QUALIFIED', json.loads(status.read_text())['qualification'])
            finally:
                os.chdir(prior)

    def test_after_only_cli_capture_remains_unqualified_and_retains_usable_measurements(self):
        prior = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                measured = Path('macrobenchmark/build/outputs/suite-benchmarkData.json')
                measured.parent.mkdir(parents=True)
                measured.write_text(json.dumps(self.baseline))
                after = copy.deepcopy(self.baseline['environmentProvenance']['after'])
                with patch.object(sys, 'argv', ['qualify_device_performance.py']), patch.object(performance, 'capture_identity', return_value=after), patch.object(performance, 'git', return_value='a' * 40):
                    self.assertEqual(0, performance.main())
                status = json.loads(Path('device-evidence/performance-status.json').read_text())
                candidate = json.loads(Path('device-evidence/performance-candidate.json').read_text())
                self.assertEqual('NOT_QUALIFIED', status['qualification'])
                self.assertEqual('UNBOUND', candidate['environmentProvenance']['status'])
                self.assertEqual(self.baseline['benchmarks'], candidate['benchmarks'])
            finally:
                os.chdir(prior)
