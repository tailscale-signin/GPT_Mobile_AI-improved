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


    def test_android_gradle_releases_each_compiler_before_the_next_offline_phase(self):
        from pathlib import Path
        import shutil
        import subprocess
        if not shutil.which('sh'):
            self.skipTest('requires a POSIX shell')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'scripts').mkdir()
            (root/'scripts/android_pipeline.py').touch()
            (root/'app/src/main/kotlin/dev/chungjungsoo/gptmobile').mkdir(parents=True)
            (root/'gradlew').write_text('printf "%s\\n" "$*" >> invocations\n', newline='\n')
            (root/'launcher').write_text(profiles.GRADLE_BUILD, newline='\n')
            target = ':app:testDebugUnitTest'
            subprocess.run(['sh','launcher','offline',target,'--tests','example.Test.case'], cwd=root, check=True)
            calls = (root/'invocations').read_text().splitlines()
            self.assertEqual(4, len(calls))
            self.assertTrue(all('--offline' in line and '--max-workers=1' in line for line in calls))
            self.assertIn(':app:kspDebugKotlin', calls[0])
            self.assertIn(':app:kaptDebugKotlin', calls[1])
            self.assertIn(':app:compileDebugKotlin', calls[2])
            self.assertIn(target+' --tests example.Test.case', calls[3])
            self.assertTrue(all('-Xmx4096m' in line for line in calls[1:]))
