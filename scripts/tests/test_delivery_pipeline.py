import binascii
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_pipeline as pipeline
import artifact_manifest as artifacts
import build_metrics
import check_android_compile_memory as compile_memory
import prepare_release as release
import visual_regression as visual
import workflow_service_levels as service


def write_png(path, color, width=1, filter_mode=0):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, 1, 8, 6, 0, 0, 0)) +
                     chunk(b'IDAT', zlib.compress(bytes([filter_mode]) + bytes(color) * width)) + chunk(b'IEND', b''))


class AndroidCompileMemoryTest(unittest.TestCase):
    def files(self, available_mib=12_000, swap_mib=0):
        mib = compile_memory.MIB
        return {
            '/proc/meminfo': f'MemTotal: {16_384 * 1024} kB\nMemFree: {16_000 * 1024} kB\nMemAvailable: {available_mib * 1024} kB\nSwapTotal: {swap_mib * 1024} kB\nSwapFree: {swap_mib * 1024} kB',
            '/proc/self/cgroup': '0::/actions/job',
            '/proc/self/mountinfo': '31 24 0:25 / /sys/fs/cgroup rw - cgroup2 cgroup rw',
            '/sys/fs/cgroup/cgroup.controllers': 'memory cpu',
            '/sys/fs/cgroup/memory.current': str(500 * mib),
            '/sys/fs/cgroup/actions/memory.max': 'max',
            '/sys/fs/cgroup/actions/memory.current': str(400 * mib),
            '/sys/fs/cgroup/actions/job/memory.max': 'max',
            '/sys/fs/cgroup/actions/job/memory.current': str(300 * mib),
        }

    def test_exact_fixed_budget_uses_available_ram_and_accepts_unlimited_root(self):
        self.assertEqual(8960 * 1024 * 1024, compile_memory.REQUIRED_BYTES)
        result = compile_memory.observe(self.files(available_mib=8960).get)
        self.assertEqual('SUFFICIENT', result['status'])
        self.assertEqual(8960 * 1024 * 1024, result['availableBytes'])
        self.assertEqual(['UNLIMITED', 'UNLIMITED', 'UNLIMITED_ROOT'], [row['state'] for row in result['cgroups']])

    def test_swap_and_memfree_cannot_rescue_insufficient_available_ram(self):
        result = compile_memory.observe(self.files(available_mib=8959, swap_mib=64_000).get)
        self.assertEqual('INSUFFICIENT', result['status'])
        self.assertEqual(8959 * 1024 * 1024, result['availableBytes'])
        self.assertEqual(64_000 * 1024 * 1024, result['ram']['SwapFree'])
        self.assertFalse(result['swapCountsAsHeadroom'])

    def test_finite_leaf_requires_budget_after_existing_usage(self):
        files = self.files()
        files['/sys/fs/cgroup/actions/job/memory.max'] = str(10_000 * compile_memory.MIB)
        for usage, expected in ((1040, 'SUFFICIENT'), (1041, 'INSUFFICIENT'), (10_001, 'INSUFFICIENT')):
            with self.subTest(usage_mib=usage):
                files['/sys/fs/cgroup/actions/job/memory.current'] = str(usage * compile_memory.MIB)
                result = compile_memory.observe(files.get)
                self.assertEqual(expected, result['status'])
                self.assertEqual(max(0, 10_000 - usage) * compile_memory.MIB, result['availableBytes'])

    def test_finite_parent_constrains_unlimited_and_unaccounted_children(self):
        files = self.files()
        files['/sys/fs/cgroup/actions/memory.max'] = str(9000 * compile_memory.MIB)
        files['/sys/fs/cgroup/actions/memory.current'] = str(100 * compile_memory.MIB)
        for inherited in (False, True):
            with self.subTest(inherited=inherited):
                if inherited:
                    files.pop('/sys/fs/cgroup/actions/job/memory.max')
                    files['/sys/fs/cgroup/actions/cgroup.subtree_control'] = 'cpu'
                result = compile_memory.observe(files.get)
                self.assertEqual('INSUFFICIENT', result['status'])
                self.assertEqual(8900 * compile_memory.MIB, result['availableBytes'])

    def test_unknown_memory_or_unreadable_cgroup_cannot_pass(self):
        for missing in ('/proc/meminfo', '/proc/self/cgroup', '/proc/self/mountinfo', '/sys/fs/cgroup/actions/job/memory.current'):
            with self.subTest(missing=missing):
                files = self.files()
                files.pop(missing)
                self.assertEqual('UNKNOWN', compile_memory.observe(files.get)['status'])
        files = self.files()
        def unreadable(path):
            if path.endswith('/memory.max'):
                raise PermissionError('private-runner-label-must-not-be-printed')
            return files.get(path)
        result = compile_memory.observe(unreadable)
        self.assertEqual('UNKNOWN', result['status'])
        self.assertNotIn('private-runner-label', json.dumps(result))

    def test_missing_enabled_limit_and_hidden_ancestry_remain_unknown(self):
        files = self.files()
        files.pop('/sys/fs/cgroup/actions/job/memory.max')
        files['/sys/fs/cgroup/actions/cgroup.subtree_control'] = 'cpu memory'
        self.assertEqual('UNKNOWN', compile_memory.observe(files.get)['status'])
        files = self.files()
        files['/proc/self/mountinfo'] = '31 24 0:25 /actions /sys/fs/cgroup rw - cgroup2 cgroup rw'
        self.assertEqual('UNKNOWN', compile_memory.observe(files.get)['status'])

    def test_malformed_limits_and_excessive_ancestry_are_bounded_unknowns(self):
        for limit in ('-1', 'unknown', '9' * 100):
            with self.subTest(limit=limit[:12]):
                files = self.files()
                files['/sys/fs/cgroup/actions/job/memory.max'] = limit
                self.assertEqual('UNKNOWN', compile_memory.observe(files.get)['status'])
        files = self.files()
        files['/proc/self/cgroup'] = '0::/' + '/'.join(['nested'] * 40)
        result = compile_memory.observe(files.get)
        self.assertEqual('UNKNOWN', result['status'])
        self.assertLess(len(json.dumps(result)), 1000)


class PipelineTest(unittest.TestCase):
    def assert_android_compile_stages(self, filename, stages, job_timeout, device_abi=False, heap_by_stage=None):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows' / filename).read_text()
        steps = re.split(r'(?m)^      - ', workflow.split('    steps:\n', 1)[1])[1:]
        tasks = {task for stage in stages for task in stage}
        heap_by_stage = heap_by_stage or [4096] * len(stages)
        self.assertEqual(len(stages), len(heap_by_stage))
        matched = []
        for index, step in enumerate(steps):
            if '        run:' not in step:
                continue
            command = step.split('        run:', 1)[1]
            present = {task for task in tasks if re.search(r'(?<![\w:])(?::app:)?' + re.escape(task) + r'(?!\w)', command)}
            if not present:
                continue
            matched.append((index, present, step))
            for flag in ('--no-daemon', '--max-workers=1'):
                self.assertIn(flag, command)
            self.assertEqual([str(heap_by_stage[len(matched) - 1])], re.findall(r'-Xmx(\d+)m', command))
            self.assertEqual(['768'], re.findall(r'-XX:MaxMetaspaceSize=(\d+)m', command))
            self.assertNotRegex(command, r'(?<!\S)-x(?:\s|=)')
            self.assertNotIn('--continue', command)
            self.assertNotIn('continue-on-error:', step)
            self.assertNotIn('|| true', command)
            if device_abi:
                self.assertIn('-Pandroid.injected.build.abi=x86_64', command)
        # Each task group must run in its own process, after its prerequisites;
        # a monolithic compile pulls generation heaps into the same invocation.
        self.assertEqual([set(stage) for stage in stages], [present for _, present, _ in matched])
        for _, _, step in matched[:-1]:
            timeout = re.search(r'(?m)^        timeout-minutes: (\d+)$', step)
            self.assertIsNotNone(timeout)
            self.assertGreater(int(timeout[1]), 0)
            self.assertLessEqual(int(timeout[1]), 25)
        self.assertIn(f'    timeout-minutes: {job_timeout}', workflow)
        properties = (root / 'gradle.properties').read_text()
        self.assertIn('kotlin.compiler.execution.strategy=in-process', properties)
        self.assertIn('ksp.run.in.process=true', properties)
        return workflow, steps, matched[-1][0]

    def test_acceptance_build_separates_both_variants_generation_and_compilation(self):
        workflow, steps, package_index = self.assert_android_compile_stages(
            'release-0950-acceptance.yml',
            [('kspDebugKotlin',), ('kaptDebugKotlin',),
             ('compileDebugKotlin', 'compileDebugJavaWithJavac'),
             ('kspDebugAndroidTestKotlin',),
             ('kaptGenerateStubsDebugAndroidTestKotlin', 'kaptDebugAndroidTestKotlin'),
             ('compileDebugAndroidTestKotlin', 'compileDebugAndroidTestJavaWithJavac'),
             ('assembleDebug', 'assembleDebugAndroidTest')],
            job_timeout=60, device_abi=True, heap_by_stage=[4096, 4096, 6144, 4096, 4096, 4096, 4096],
        )
        self.assertIn('timeout-minutes: 25', steps[package_index])
        verification = next(i for i, step in enumerate(steps) if 'scripts/select_android_test_apk.py' in step)
        emulator = next(i for i, step in enumerate(steps) if 'uses: ./build/emulator-action-upstream' in step)
        self.assertLess(package_index, verification)
        self.assertLess(verification, emulator)
        self.assertIn('api: 34', workflow)
        self.assertIn('api: 36', workflow)
        self.assertIn('bash scripts/run_release0950_acceptance.sh', steps[emulator])

    def test_proven_compile_oom_phases_keep_a_fixed_cap_and_observed_headroom(self):
        root = Path(__file__).resolve().parents[2]
        for filename, name, prefix in (
            ('release-0950-acceptance.yml', 'Compile device regression production sources in a fresh process', 'acceptance'),
            ('audit-workspace.yml', 'Compile production sources in a separate process', 'audit'),
        ):
            with self.subTest(workflow=filename):
                workflow = (root / '.github/workflows' / filename).read_text()
                steps = re.split(r'(?m)^      - ', workflow)
                step = next(step for step in steps if step.startswith('name: ' + name + '\n'))
                self.assertIn('timeout-minutes: 20', step)
                self.assertIn('set -o pipefail', step)
                self.assertLess(step.index('scripts/check_android_compile_memory.py'), step.index('scripts/gradle_retry.py'))
                self.assertEqual(['6144'], re.findall(r'-Xmx(\d+)m', step))
                self.assertEqual(['768'], re.findall(r'-XX:MaxMetaspaceSize=(\d+)m', step))
                for flag in ('--timeout-seconds 1140', '--no-daemon', '--max-workers=1', ':app:compileDebugKotlin', ':app:compileDebugJavaWithJavac'):
                    self.assertIn(flag, step)
                for bypass in ('continue-on-error:', '|| true', '--continue', ' -x '):
                    self.assertNotIn(bypass, step)
                self.assertIn(f'tee {prefix}-production-memory.log', step)
                self.assertIn(f'-Xlog:gc:file=${{RUNNER_TEMP}}/{prefix}-production-gc.log:time,level,tags:filecount=2,filesize=8M', step)
                uploads = [candidate for candidate in steps if 'actions/upload-artifact@' in candidate and 'if: always()' in candidate]
                self.assertTrue(any(f'{prefix}-production-gc.log*' in candidate for candidate in uploads))
                self.assertTrue(any(f'{prefix}-production-memory.log' in candidate or f'{prefix}-*.log' in candidate for candidate in uploads))
        audit = (root / '.github/workflows/audit-workspace.yml').read_text()
        self.assertEqual(['4096', '6144', '4096', '4096', '4096'], re.findall(r'-Xmx(\d+)m', audit))
        for required in ('timeout-minutes: 135', ':app:compileDebugUnitTestKotlin', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'):
            self.assertIn(required, audit)

    def test_distribution_apk_build_preserves_native_and_size_gates_after_staged_compilation(self):
        _, steps, package_index = self.assert_android_compile_stages(
            'build-apk.yml',
            [('kspDebugKotlin',), ('kaptDebugKotlin',),
             ('compileDebugKotlin', 'compileDebugJavaWithJavac'), ('assembleDebug',)],
            job_timeout=90, heap_by_stage=[4096, 4096, 6144, 4096],
        )
        provenance = next(i for i, step in enumerate(steps) if 'python3 scripts/check_qnn_provenance.py' in step)
        generation = next(i for i, step in enumerate(steps) if ':app:kspDebugKotlin' in step)
        native = next(i for i, step in enumerate(steps) if 'scripts/check_local_runtime_apk.py' in step)
        size = next(i for i, step in enumerate(steps) if 'scripts/check_apk_size.py' in step)
        self.assertLess(provenance, generation)
        self.assertLess(generation, package_index)
        self.assertLess(package_index, native)
        self.assertLess(native, size)

    def test_native_smoke_releases_generation_heaps_without_bypassing_device_gates(self):
        workflow, steps, shrink_index = self.assert_android_compile_stages(
            'native-memory-smoke.yml',
            [('kspNativeSmokeKotlin',), ('kaptNativeSmokeKotlin',),
             ('compileNativeSmokeKotlin', 'compileNativeSmokeJavaWithJavac'),
             ('minifyNativeSmokeWithR8',)],
            job_timeout=50, heap_by_stage=[4096, 4096, 6144, 4096],
        )
        for step in steps:
            if 'scripts/gradle_retry.py' in step:
                self.assertIn('-PnativeMemorySmoke=true', step)
                self.assertNotIn('continue-on-error:', step)
                self.assertNotIn('|| true', step)
                self.assertNotRegex(step, r'(?<!\S)-x(?:\s|=)')
        lint = next(i for i, step in enumerate(steps) if ':app:lintVitalNativeSmoke ' in step)
        package = next(i for i, step in enumerate(steps) if ':app:assembleNativeSmoke ' in step)
        test_package = next(i for i, step in enumerate(steps) if ':app:assembleNativeSmokeAndroidTest ' in step)
        native = next(i for i, step in enumerate(steps) if 'scripts/check_local_runtime_apk.py' in step)
        emulator = next(i for i, step in enumerate(steps) if 'ReactiveCircus/android-emulator-runner@' in step)
        evidence = next(i for i, step in enumerate(steps) if 'name: minified-native-memory-evidence' in step)
        self.assertEqual(
            sorted([shrink_index, lint, package, test_package, native, emulator, evidence]),
            [shrink_index, lint, package, test_package, native, emulator, evidence],
        )
        for index in (package, test_package):
            self.assertIn('ForkJoinPool.common.parallelism=1', steps[index])
        self.assertIn('scripts/check_no_telemetry.py', steps[native])
        self.assertIn('api-level: 36', steps[emulator])
        self.assertIn('arch: x86_64', steps[emulator])
        self.assertIn('bash scripts/run_native_memory_smoke.sh', steps[emulator])
        self.assertIn('if: always()', steps[evidence])
        self.assertIn('app/build/native-memory-smoke/', steps[evidence])

    def test_apk_download_contracts_release_generation_heaps_before_selected_tests(self):
        _, steps, test_index = self.assert_android_compile_stages(
            'github-build-downloads.yml',
            [('kspDebugKotlin',), ('kaptDebugKotlin',),
             ('compileDebugKotlin', 'compileDebugJavaWithJavac'), ('testDebugUnitTest',)],
            job_timeout=40, heap_by_stage=[4096, 4096, 6144, 4096],
        )
        classes = [
            'dev.chungjungsoo.gptmobile.data.github.GitHubBuildCatalogTest',
            'dev.chungjungsoo.gptmobile.data.github.GitHubApkFilesTest',
        ]
        self.assertEqual(classes, re.findall(r"--tests '([^']+)'", steps[test_index]))
        self.assertIn('timeout-minutes: 30', steps[test_index])
        provisioning = next(i for i, step in enumerate(steps) if 'scripts/provision_memory_model.py' in step)
        generation = next(i for i, step in enumerate(steps) if ':app:kspDebugKotlin' in step)
        evidence = next(i for i, step in enumerate(steps) if 'name: github-build-downloads-test-report-' in step)
        self.assertLess(provisioning, generation)
        self.assertLess(test_index, evidence)
        self.assertIn('if: always()', steps[evidence])
        self.assertIn('retention-days: 14', steps[evidence])
        for name in classes:
            self.assertIn(f'app/build/test-results/testDebugUnitTest/TEST-{name}.xml', steps[evidence])
        self.assertIn('app/build/reports/tests/testDebugUnitTest/', steps[evidence])

    def test_geniex_packaging_has_bounded_parallelism_and_stall_evidence(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/geniex-preview.yml').read_text()
        debug = workflow.split('- name: Build isolated preview APK', 1)[1].split('- name: Validate release shrinker', 1)[0]
        self.assertIn(':app:validateSigningDebug', workflow.split('- name: Build isolated preview APK', 1)[0])
        self.assertIn('ForkJoinPool.common.parallelism=2', debug)
        self.assertLess(debug.index('-PgeniexSeparateSigning=true'), debug.index('scripts/sign_preview_apks.py'))
        self.assertIn('app/build/outputs/apk/debug --apksigner', debug)
        self.assertEqual(2, workflow.count('--keystore "$ANDROID_USER_HOME/debug.keystore"'))
        package = workflow.split('- name: Package the minified GenieX APK in a fresh process', 1)[1]
        package = package.split('- name: Audit minified GenieX native stack', 1)[0]
        self.assertIn('timeout-minutes: 90', package)
        self.assertIn('ForkJoinPool.common.parallelism=2', package)
        self.assertNotIn('ForkJoinPool.common.parallelism=1', package)
        self.assertIn('sleep 900', package)
        self.assertIn('jcmd "$pid" GC.heap_info', package)
        self.assertIn('jcmd "$pid" Thread.print -l', package)
        self.assertIn('-PgeniexSeparateSigning=true', package)
        self.assertIn('scripts/sign_preview_apks.py', package)
        self.assertLess(package.index('-PgeniexSeparateSigning=true'), package.index('scripts/sign_preview_apks.py'))

    def test_incomplete_build_artifacts_do_not_mask_the_original_command_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'app/build/outputs/apk/debug/incomplete.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'partial output')
            output = root / 'metrics.json'
            result = subprocess.run([sys.executable, str(Path(build_metrics.__file__).resolve()), '--label', 'failure',
                                     '--output', str(output), '--', sys.executable, '-c', 'raise SystemExit(7)'], cwd=root,
                                    capture_output=True, text=True)
            self.assertEqual(7, result.returncode)
            self.assertEqual(7, json.loads(output.read_text())['exit_code'])
            self.assertEqual('UNREADABLE_OR_INCOMPLETE', json.loads(output.read_text())['apk_errors'][0]['status'])

    def test_variant_runtime_matrix_and_command_injection_are_bounded(self):
        for runtime in ('legacy', 'geniex'):
            for variant in ('release', 'nativeSmoke'):
                shrink = pipeline.command_for(runtime, variant, 'shrink')
                package = pipeline.command_for(runtime, variant, 'package')
                self.assertIn('--max-workers=1', shrink)
                self.assertTrue(any('ForkJoinPool.common.parallelism=2' in arg for arg in package))
                self.assertIn(f'-PgeniexRuntime={str(runtime == "geniex").lower()}', shrink)
                self.assertTrue(any('minify' in arg for arg in shrink))
        for args in [('legacy', 'debug', 'shrink'), ('legacy', 'debug', 'lint'), ('evil;touch file', 'debug', 'debug'), ('legacy', 'debug', 'bundle')]:
            with self.assertRaises(ValueError):
                pipeline.command_for(*args)

    def test_failure_and_interruption_checkpoint_do_not_claim_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'state.json'
            run = Mock(return_value=subprocess.CompletedProcess([], 7))
            self.assertEqual(7, pipeline.execute('legacy', 'debug', 'debug', output, run))
            self.assertEqual('FAILED', json.loads(output.read_text())['status'])
            with self.assertRaises(KeyboardInterrupt):
                pipeline.execute('legacy', 'debug', 'debug', output, Mock(side_effect=KeyboardInterrupt))
            self.assertEqual('INTERRUPTED', json.loads(output.read_text())['status'])
            self.assertFalse(json.loads(output.read_text())['remote_state_changed'])

    def test_release_packaging_waits_for_separate_lint_process(self):
        for variant in ('release', 'nativeSmoke'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'package.json'
                run = Mock(return_value=subprocess.CompletedProcess([], 0))
                self.assertEqual(0, pipeline.execute('legacy', variant, 'package', output, run))
                self.assertEqual(2, run.call_count)
                lint, package = [call.args[0] for call in run.call_args_list]
                suffix = variant[0].upper() + variant[1:]
                self.assertIn(f':app:lintVital{suffix}', lint)
                self.assertNotIn(f':app:assemble{suffix}', lint)
                self.assertIn(f':app:assemble{suffix}', package)
                for command in (lint, package):
                    self.assertIn('--no-daemon', command)
                    self.assertIn('--max-workers=1', command)
                    self.assertNotIn('-x', command)
                self.assertEqual('COMPLETED', json.loads(output.read_text())['status'])
                self.assertEqual('COMPLETED', json.loads(output.with_name('package-lint.json').read_text())['status'])

    def test_failed_release_lint_prevents_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'package.json'
            run = Mock(return_value=subprocess.CompletedProcess([], 23))
            self.assertEqual(23, pipeline.execute('legacy', 'release', 'package', output, run))
            self.assertEqual(1, run.call_count)
            self.assertIn(':app:lintVitalRelease', run.call_args.args[0])
            checkpoint = json.loads(output.read_text())
            self.assertEqual('FAILED', checkpoint['status'])
            self.assertEqual('lint', checkpoint['failed_phase'])
            self.assertEqual(23, checkpoint['exit_code'])


class VisualRegressionTest(unittest.TestCase):
    def test_pixel_diff_dimensions_and_integrity(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'a.png', Path(directory) / 'b.png'
            write_png(a, [2, 4, 8, 255])
            write_png(b, [2, 4, 9, 255])
            self.assertEqual(1, visual.compare(a, b)['changed_pixels'])
            self.assertEqual(0, visual.compare(a, b, 1)['changed_pixels'])
            write_png(b, [2, 4, 9, 255], width=2)
            self.assertEqual('DIMENSION_MISMATCH', visual.compare(a, b)['status'])
            b.write_bytes(b.read_bytes()[:-3])
            with self.assertRaises(ValueError):
                visual.png(b)

    def test_every_png_predictor_is_decoded(self):
        with tempfile.TemporaryDirectory() as directory:
            for mode in range(5):
                path = Path(directory) / f'{mode}.png'
                # At the first pixel, left/up/corner are zero for every filter.
                write_png(path, [2, 4, 8, 255], filter_mode=mode)
                self.assertEqual(bytearray([2, 4, 8, 255]), visual.png(path)[2])

    def test_missing_unreviewed_changed_or_extra_images_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidates = root / 'current'
            candidates.mkdir()
            manifest = root / 'manifest.json'
            self.assertEqual('NOT_QUALIFIED', visual.assess(candidates, manifest)['status'])
            write_png(root / 'ui.png', [1, 2, 3, 255])
            write_png(candidates / 'ui.png', [1, 2, 3, 255])
            data = {'schema_version': 1, 'commit_sha': 'a' * 40, 'reviewed_by': 'fixture reviewer', 'renderer': 'native-sdk34',
                    'images': {'ui.png': hashlib.sha256((root / 'ui.png').read_bytes()).hexdigest()}}
            manifest.write_text(json.dumps(data))
            self.assertEqual('PASS', visual.assess(candidates, manifest)['status'])
            write_png(candidates / 'new.png', [1, 2, 3, 255])
            self.assertEqual('FAIL', visual.assess(candidates, manifest)['status'])
            data['images']['ui.png'] = '0' * 64
            manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                visual.assess(candidates, manifest)


class ReleaseAndArtifactTest(unittest.TestCase):
    def test_version_bump_is_forward_and_does_not_reuse_android_code(self):
        source = 'versionCode = 122\nversionName = "0.9.64.0"\n'
        changed, code = release.bump(source, '0.9.65.0')
        self.assertEqual(123, code)
        self.assertIn('versionName = "0.9.65.0"', changed)
        with self.assertRaises(ValueError):
            release.bump(source, '0.9.64.0')
        with self.assertRaises(ValueError):
            release.bump(source, '1.0; command')
        reverted = 'versionCode = 100\nversionName = "0.9.40.0"\n'
        self.assertEqual(123, release.bump(reverted, '0.9.65.0', source)[1])

    def test_index_streams_actual_digests_and_semantic_search(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'candidate.apk').write_bytes(b'actual artifact bytes')
            (root / 'artifact-index.json').write_text('exclude self')
            result = artifacts.build(root, 'geniex', 'a' * 40, '0.9.65.0')
            self.assertEqual(1, len(result['artifacts']))
            self.assertEqual(hashlib.sha256(b'actual artifact bytes').hexdigest(), result['artifacts'][0]['sha256'])
            self.assertEqual(result['artifacts'], artifacts.search(result, 'GenieX'))
            self.assertEqual([], artifacts.search(result, 'missing'))

    def test_geniex_artifacts_are_verified_and_tampering_or_missing_variants_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'debug').mkdir()
            (root / 'nativeSmoke').mkdir()
            (root / 'debug/app.apk').write_bytes(b'debug')
            (root / 'nativeSmoke/app.apk').write_bytes(b'cold packaging')
            manifest = artifacts.build(root, 'geniex', 'a' * 40, '0.9.65.0')
            required = ['debug/*.apk', 'nativeSmoke/*.apk']
            self.assertEqual('VERIFIED', artifacts.verify(root, manifest, 'geniex', 'a' * 40, required)['status'])
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'legacy', 'a' * 40, required)
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'geniex', 'b' * 40, required)
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'geniex', 'a' * 40, required, '0.9.64.0')
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'geniex', 'a' * 40, ['release/*.apk'])
            (root / 'debug/app.apk').write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'geniex', 'a' * 40, required)

    def test_artifact_verification_rejects_paths_outside_the_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app.apk').write_bytes(b'candidate')
            manifest = artifacts.build(root, 'geniex', 'a' * 40, '1')
            manifest['artifacts'][0]['path'] = '../app.apk'
            with self.assertRaises(ValueError):
                artifacts.verify(root, manifest, 'geniex', 'a' * 40)


class ServiceLevelsTest(unittest.TestCase):
    objectives = {'schema_version': 1, 'default': {'minimum_samples': 20, 'success_rate': .95, 'p95_seconds': 600}}

    def test_skips_cancellations_pending_and_small_samples_do_not_invent_success(self):
        runs = [{'id': 1, 'status': 'completed', 'conclusion': 'cancelled', 'name': 'build'},
                {'id': 2, 'status': 'in_progress', 'name': 'build'},
                {'id': 3, 'status': 'completed', 'conclusion': 'skipped', 'name': 'build'}]
        report = service.evaluate(runs, self.objectives, 1000)
        row = report['workflows'][0]
        self.assertIsNone(row['success_rate'])
        self.assertEqual('INSUFFICIENT_SAMPLES', row['availability'])
        self.assertEqual(1, report['capacity']['active'])

    def test_real_failure_trends_and_latency_quantile(self):
        rows = [{'id': n, 'name': 'build', 'workflow_id': 1, 'status': 'completed', 'run_attempt': 2 if n == 20 else 1,
                 'conclusion': 'success' if n <= 15 else 'failure', 'run_started_at': '2026-10-30T01:00:00Z',
                 'updated_at': '2026-10-30T01:01:00Z'} for n in range(1, 21)]
        result = service.evaluate(rows, self.objectives)['workflows'][0]
        self.assertEqual(.75, result['success_rate'])
        self.assertEqual('MISSED', result['availability'])
        self.assertEqual(60, result['p95_success_seconds'])
        self.assertEqual('INSUFFICIENT_SAMPLES', result['latency'])
        self.assertEqual('DEGRADING', result['trend'])
        self.assertEqual(1, result['retried_run_count'])


if __name__ == '__main__':
    unittest.main()
