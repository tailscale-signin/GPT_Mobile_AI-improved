"""Migration from the first built-in mutable policy; external tooling is mocked."""
import copy
from contextlib import ExitStack
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import repository_validation as setup
from desktop.configuration import atomic_json, environment, validate
from desktop.validation_policy import SETTING, load_policy_snapshot, save_policy_path, selected_policy_path

PIN = 'python@sha256:' + 'a' * 64  # Test receipt only.


class ManagedFormatMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {SETTING: ''})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)
        self.options = setup.defaults(self.root)
        self.options['mirror'] = str(self.root / 'mirror.git')

    def entry(self):
        return dict(mirror=self.options['mirror'], image=PIN, commands=copy.deepcopy(setup.SOURCE_COMMANDS),
                    timeout_seconds=600, acceptance_map=[], allowed_checks=['Required verification'])

    def prepare(self):
        with ExitStack() as stack:
            for name, result in [('executable', '/fixture/tool'), ('check_docker', None),
                                 ('prepare_mirror', 'b' * 40), ('pin_image', PIN)]:
                stack.enter_context(patch.object(setup, name, return_value=result))
            return setup.setup(self.root, self.options, setup.read_settings(self.root)[1], prepare=True)

    def test_first_managed_format_migrates_without_losing_entries(self):
        from desktop.validation_policy import managed_policy_path, active_policy_path
        legacy = managed_policy_path(self.root)
        document = {setup.DEFAULT_REPOSITORY: self.entry(), 'other/repo': self.entry()}
        atomic_json(legacy, document)
        before = legacy.read_bytes()
        self.assertEqual(document, setup.read_settings(self.root)[0])
        self.prepare()
        chosen = Path(selected_policy_path(self.root))
        self.assertNotEqual(legacy, chosen)
        self.assertEqual(str(chosen), active_policy_path(self.root))
        self.assertEqual(document['other/repo'], setup.read_settings(self.root)[0]['other/repo'])
        self.assertEqual(before, legacy.read_bytes())
        with patch.dict(os.environ, {}, clear=True):
            first = environment(self.root, validate({}))
        with patch.dict(os.environ, first, clear=True):
            second = environment(self.root, validate({}))
        self.assertEqual(str(chosen), second[SETTING])

    def test_disable_never_reactivates_first_managed_format(self):
        from desktop.validation_policy import managed_policy_path, active_policy_path
        atomic_json(managed_policy_path(self.root), {setup.DEFAULT_REPOSITORY: self.entry()})
        setup.reset_settings(self.root, expected_revision=load_policy_snapshot(self.root)['revision'])
        self.assertEqual('', active_policy_path(self.root))
        self.assertFalse(load_policy_snapshot(self.root)['enabled'])
        self.assertIn(setup.DEFAULT_REPOSITORY, setup.read_settings(self.root)[0])
        self.assertNotIn(SETTING, environment(self.root, validate({})))

    def test_explicit_external_selection_wins_over_a_first_format_managed_file(self):
        from desktop.validation_policy import managed_policy_path, active_policy_path
        atomic_json(managed_policy_path(self.root), {setup.DEFAULT_REPOSITORY: self.entry()})
        external = self.root / 'selected.json'
        atomic_json(external, {'other/repo': self.entry()})
        save_policy_path(self.root, str(external))
        self.assertEqual(str(external), active_policy_path(self.root))
        self.assertEqual({'other/repo'}, set(setup.read_settings(self.root)[0]))
        self.assertEqual(str(external), environment(self.root, validate({}))[SETTING])

    def test_corrupt_selector_is_not_hidden_by_first_managed_format(self):
        from desktop.validation_policy import managed_policy_path
        atomic_json(managed_policy_path(self.root), {setup.DEFAULT_REPOSITORY: self.entry()})
        (self.root / 'data/validation-settings.json').write_text('{broken')
        with self.assertRaises(ValueError):
            setup.read_settings(self.root)
        self.assertNotIn(SETTING, environment(self.root, validate({})))


if __name__ == '__main__':
    unittest.main()
