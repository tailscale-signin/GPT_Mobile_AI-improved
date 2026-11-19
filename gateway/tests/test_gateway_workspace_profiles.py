import tempfile
from unittest import TestCase

from desktop import development_profiles as profiles
from desktop import repository_validation as setup
from v14.engineering_validation import validate_policy_entry


class PreparedProfileTests(TestCase):
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
