import json
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
from unittest import TestCase

from desktop import development_profiles as profiles
from desktop import repository_validation as setup
from v14.engineering_validation import validate_policy_entry


class PreparedProfileTests(TestCase):
    def test_android_profile_seeds_every_kokoro_archive_for_offline_native_builds(self):
        expected = {
            'sherpa-onnx-af2d8d81dbfa61c4cea4c568da78f3982edca005.tar.gz':
                'afcafe3c34bc2f15a363ad791fa1644a69bf0977a58c1b4f617468976c2faeb5',
            'flite-e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88.tar.gz':
                '1eebf476797cea10b4706cfbb9db9ba34f7b8ccb1fe7e0069b0b7528e0b0be46',
            'utf8proc-v2.12.0.tar.gz':
                'f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111',
            'onnxruntime-android-arm64-v8a-static_lib-1.28.2.zip':
                'bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0',
            'onnxruntime-android-x86_64-static_lib-1.28.2.zip':
                'f49dbf456ffe71dd417afb2123ef15202f8f57a4b2a05f12af1cf71f55448137',
        }
        self.assertIn('GPTMOBILE_KOKORO_DEPENDENCY_CACHE=/opt/gateway-kokoro-deps', profiles.ANDROID_TOOLS)
        for name, digest in expected.items():
            with self.subTest(name=name):
                self.assertIn('/opt/gateway-kokoro-deps/' + name, profiles.ANDROID_TOOLS)
                self.assertIn(digest, profiles.ANDROID_TOOLS)

    def test_each_profile_has_readable_commands_and_frozen_execution_image(self):
        with tempfile.TemporaryDirectory() as directory:
            for name, image in profiles.IMAGES.items():
                with self.subTest(profile=name):
                    pin = image.split(':')[0] + '@sha256:' + 'a' * 64
                    meta = dict(profile=name, base_image=pin, source_sha='b' * 40, image_id='sha256:' + 'c' * 64)
                    options = dict(setup.defaults(directory), profile=name, image_source=image, image=pin, prepared_environment=meta)
                    checked = setup.validate_options(options)
                    self.assertEqual(profiles.COMMANDS[name], checked['commands'])
                    entry = setup.entry_from_options(checked, {})
                    self.assertEqual(meta['image_id'], validate_policy_entry(entry)[2])
                    self.assertEqual(name, setup.options_for(directory, setup.DEFAULT_REPOSITORY, {setup.DEFAULT_REPOSITORY: entry})['profile'])
                    with self.assertRaises(ValueError):
                        validate_policy_entry(dict(entry, image=pin.replace('a' * 64, 'd' * 64)))
                    revised = setup.validate_options(dict(options, image='', image_source='other:1'))
                    self.assertNotIn('prepared_environment', revised)

    def test_existing_custom_policy_and_default_catalog_keep_original_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            options = setup.defaults(directory)
            self.assertFalse(options['workspace_terminal'])
            options.update(profile=setup.CUSTOM_PROFILE, commands=[['python3', '-V']], workspace_terminal=True)
            result = setup.entry_from_options(setup.validate_options(options), {})
            self.assertTrue(result['workspace_terminal'])
            self.assertEqual([['python3', '-V']], result['commands'])

    def test_custom_setup_pin_tracks_commands_and_public_environment(self):
        from v14.workspace_environment import read, recipe_id
        with tempfile.TemporaryDirectory() as directory:
            pin = 'python@sha256:' + 'e' * 64
            recipe = {'setup_commands': [['python3', '-V']], 'environment': {'MODE': 'dev'}, 'cpus': 3}
            options = dict(setup.defaults(directory), profile=setup.CUSTOM_PROFILE, commands=[['python3', '-V']],
                           image_source='python:latest', image=pin, workspace_environment=recipe)
            commands, values, limits = read(options)
            self.assertEqual(3, limits['cpus'])
            with self.assertRaises(ValueError):
                validate_policy_entry(setup.entry_from_options(options))
            meta = dict(profile=setup.CUSTOM_PROFILE, base_image=pin, source_sha='b'*40, image_id='sha256:'+'c'*64,
                        recipe_sha256=recipe_id(commands, values))
            entry = setup.entry_from_options(dict(options, prepared_environment=meta))
            self.assertEqual(meta['image_id'], validate_policy_entry(entry)[2])
            entry['workspace_environment']['setup_commands'] = [['python3','-m','site']]
            with self.assertRaises(ValueError):
                validate_policy_entry(entry)


    def launch(self, *, mode='offline', repository=True, memory_exit=0, fail_phase=''):
        if not shutil.which('sh'):
            self.skipTest('requires a POSIX shell')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            if repository:
                (root/'scripts').mkdir()
                # This file must never be executed or imported by the wrapper.
                (root/'scripts/android_pipeline.py').write_text('raise AssertionError("untrusted checkout helper ran")\n')
                (root/'app/src/main/kotlin/dev/chungjungsoo/gptmobile').mkdir(parents=True)
            (root/'gradlew').write_text(
                'printf "%s\\0" "$@" >> invocations\nprintf "\\n" >> invocations\n' +
                ('case " $* " in *' + fail_phase + '*) exit 7 ;; esac\n' if fail_phase else ''), newline='\n')
            binary = root/'bin'
            binary.mkdir()
            probe = binary/'python3'
            probe.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > probe-arguments\n'
                             'cat > observed-memory-check.py\nexit ' + str(memory_exit) + '\n', newline='\n')
            probe.chmod(0o755)
            (root/'launcher').write_text(profiles.GRADLE_BUILD, newline='\n')
            target = ':app:testDebugUnitTest'
            original = [target, '--tests', 'example.Test.case', '-Pmessage=value with spaces']
            result = subprocess.run([shutil.which('sh'), 'launcher', mode, *original], cwd=root, check=False,
                                    env=dict(os.environ, PATH=str(binary) + os.pathsep + os.environ.get('PATH', '')),
                                    capture_output=True, text=True, timeout=30)
            calls = [[part.decode() for part in row.split(b'\0') if part]
                     for row in (root/'invocations').read_bytes().splitlines()]
            observed = root/'observed-memory-check.py'
            return result, calls, original, observed.read_bytes() if observed.exists() else None, \
                (root/'probe-arguments').read_text().strip() if observed.exists() else None

    def test_android_gradle_releases_each_compiler_before_the_next_offline_phase(self):
        result, calls, original, observed, probe_arguments = self.launch()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, len(calls))
        self.assertTrue(all('--offline' in row and '--max-workers=1' in row and '--no-daemon' in row for row in calls))
        self.assertIn(':app:kspDebugKotlin', calls[0])
        self.assertIn(':app:kaptDebugKotlin', calls[1])
        self.assertIn(':app:compileDebugKotlin', calls[2])
        self.assertEqual(original, calls[3][3:7])
        self.assertFalse(any('org.gradle.jvmargs' in arg for arg in calls[0]))
        self.assertTrue(any('-Xmx4096m' in arg for arg in calls[1]))
        self.assertTrue(any('-Xmx6144m' in arg and 'MaxMetaspaceSize=768m' in arg for arg in calls[2]))
        self.assertTrue(any('-Xmx4096m' in arg for arg in calls[3]))
        self.assertEqual(profiles.PRODUCTION_MEMORY_CHECK.encode(), observed)
        self.assertEqual('-I -', probe_arguments)

    def test_preparation_retains_its_mode_and_generic_repositories_do_not_run_the_probe(self):
        prepared, calls, original, observed, _ = self.launch(mode='prepare')
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        self.assertEqual(4, len(calls))
        self.assertTrue(all('--offline' not in row for row in calls))
        self.assertEqual(original, calls[-1][2:6])
        self.assertIsNotNone(observed)
        generic, calls, original, observed, _ = self.launch(repository=False)
        self.assertEqual(0, generic.returncode, generic.stderr)
        self.assertEqual([['--no-daemon', '--max-workers=1', '--offline', *original]], calls)
        self.assertIsNone(observed)

    def test_resource_or_prior_stage_failure_never_starts_production_or_final_phase(self):
        for options in ({'memory_exit': 1}, {'fail_phase': ':app:kaptDebugKotlin'}):
            with self.subTest(options=options):
                result, calls, _, observed, _ = self.launch(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(2, len(calls))
                self.assertTrue(all(':app:compileDebugKotlin' not in row and ':app:testDebugUnitTest' not in row for row in calls))
                self.assertEqual('memory_exit' in options, observed is not None)

    def test_embedded_memory_probe_matches_reviewed_source_and_keeps_resource_policy(self):
        source = Path(__file__).resolve().parents[2]/'scripts/check_android_compile_memory.py'
        self.assertEqual(source.read_text(encoding='utf-8'), profiles.PRODUCTION_MEMORY_CHECK)
        from v14.workspace_environment import read
        self.assertEqual(8, read({'prepared_environment': {'image_id': 'sha256:' + 'a' * 64}})[2]['memory_gib'])
        self.assertEqual(6, read({})[2]['memory_gib'])
        self.assertEqual(4, read({'prepared_environment': {}, 'workspace_environment': {'memory_gib': 4}})[2]['memory_gib'])

    def test_visible_memory_probe_rejects_eight_gib_unknown_and_swap_only_capacity(self):
        namespace = {'__name__': 'gateway_trusted_memory_contract'}
        exec(compile(profiles.PRODUCTION_MEMORY_CHECK, '<trusted installed memory probe>', 'exec'), namespace)
        mib = 1024 * 1024
        required = 8960 * mib
        values = {
            '/proc/meminfo': 'MemTotal: 16777216 kB\nMemAvailable: 12582912 kB\nSwapTotal: 33554432 kB\nSwapFree: 33554432 kB',
            '/proc/self/cgroup': '0::/worker',
            '/proc/self/mountinfo': '29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw',
            '/sys/fs/cgroup/cgroup.controllers': 'memory cpu',
            '/sys/fs/cgroup/worker/memory.max': str(10 * 1024 * mib),
            '/sys/fs/cgroup/worker/memory.current': str(512 * mib),
        }
        for maximum, used, expected in [(10 * 1024 * mib, 512 * mib, 'SUFFICIENT'),
                                         (8 * 1024 * mib, 0, 'INSUFFICIENT'),
                                         (required, 0, 'SUFFICIENT'),
                                         (required, 1, 'INSUFFICIENT')]:
            with self.subTest(maximum=maximum, used=used):
                observations = dict(values, **{'/sys/fs/cgroup/worker/memory.max': str(maximum),
                                              '/sys/fs/cgroup/worker/memory.current': str(used)})
                receipt = namespace['observe'](observations.get)
                self.assertEqual(expected, receipt['status'])
                self.assertEqual(required, receipt['requiredBytes'])
                self.assertFalse(receipt['swapCountsAsHeadroom'])
                self.assertNotIn('/worker', json.dumps(receipt))
        missing = dict(values)
        del missing['/sys/fs/cgroup/worker/memory.current']
        self.assertEqual('UNKNOWN', namespace['observe'](missing.get)['status'])
