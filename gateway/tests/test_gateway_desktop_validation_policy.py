"""Desktop policy selection survives both launcher stages without inheriting unrelated overrides."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import atomic_json, environment, load, validate
from desktop.validation_policy import SETTING, check_policy_file, save_policy_path, selected_policy_path
from v14.engineering_validation import ValidationPolicyError, load_policy


class DesktopValidationPolicyTests(unittest.TestCase):
    def policy(self, root):
        path = Path(root) / 'approved.json'
        path.write_text(json.dumps({'owner/repo': {'mirror': str(Path(root) / 'mirror.git'),
            'image': 'registry.example/validation@sha256:' + 'a' * 64,
            'commands': [['python', '-m', 'unittest']]}}), encoding='utf-8')
        return path

    def test_bad_saved_selection_keeps_gateway_available_without_inheriting_a_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approved = self.policy(root)
            settings = root / 'data' / 'validation-settings.json'
            settings.parent.mkdir()
            cases = ['{broken', ' ' * 8193, json.dumps({'schema': 1, 'policy_path': 'relative.json'})]
            for content in cases:
                with self.subTest(content=content[:30]), patch.dict(os.environ, {SETTING: str(approved)}, clear=True):
                    settings.write_text(content, encoding='utf-8')
                    with self.assertRaises(ValueError):
                        selected_policy_path(root)
                    env = environment(root, validate({}))
                    self.assertNotIn(SETTING, env)
                    self.assertEqual('127.0.0.1', env['GATEWAY_HOST'])
                    with patch.dict(os.environ, env, clear=True), self.assertRaises(ValidationPolicyError) as blocked:
                        load_policy('owner/repo')
                    self.assertEqual('NOT_CONFIGURED', blocked.exception.state)
                    # Saving an empty selection repairs the invalid file and
                    # explicitly disables the still-present inherited policy.
                    save_policy_path(root, '')
                    self.assertEqual('', selected_policy_path(root))
                    self.assertNotIn(SETTING, environment(root, validate({})))

    def test_unreadable_saved_selection_does_not_stop_gateway_or_reinherit_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approved = self.policy(root)
            settings = root / 'data' / 'validation-settings.json'
            atomic_json(settings, {'schema': 1, 'policy_path': str(approved)})
            original_open = Path.open

            def restricted_open(path, *args, **kwargs):
                # Windows may expand the temporary directory's short-name
                # spelling when environment() resolves the desktop root.
                # Compare the resolved file identity so the mock still
                # exercises the unreadable-selection recovery path.
                if path.resolve() == settings.resolve():
                    raise PermissionError('Saved policy selection cannot be read')
                return original_open(path, *args, **kwargs)

            with patch.dict(os.environ, {SETTING: str(approved)}, clear=True), patch.object(Path, 'open', restricted_open):
                with self.assertRaises(PermissionError):
                    selected_policy_path(root)
                self.assertNotIn(SETTING, environment(root, validate({})))

    def test_invalid_inherited_selection_blocks_only_implementation_until_repaired(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: 'relative.json'}, clear=True):
            with self.assertRaises(ValueError):
                selected_policy_path(temp)
            self.assertNotIn(SETTING, environment(temp, validate({})))
            approved = self.policy(temp)
            save_policy_path(temp, str(approved))
            self.assertEqual(str(approved), environment(temp, validate({}))[SETTING])

    def test_windows_utf8_bom_policy_preserves_non_ascii_mirror_through_selection_and_load(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            policy = self.policy(root)
            document = json.loads(policy.read_text(encoding='utf-8'))
            mirror = root / 'mirror ü-日本.git'
            document['owner/repo']['mirror'] = str(mirror)
            policy.write_text(json.dumps(document, ensure_ascii=False), encoding='utf-8-sig')
            self.assertTrue(policy.read_bytes().startswith(b'\xef\xbb\xbf'))
            self.assertEqual('VALIDATED', save_policy_path(root, str(policy))['state'])
            with patch.dict(os.environ, {}, clear=True):
                env = environment(root, validate({}))
            with patch.dict(os.environ, env, clear=True):
                loaded, loaded_mirror, _, _, _ = load_policy('owner/repo')
            self.assertEqual(str(mirror), loaded['mirror'])
            self.assertEqual(mirror.resolve(), loaded_mirror)

    def test_explicit_host_policy_survives_supervisor_and_frozen_engine(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            inherited = {SETTING: str(policy), 'GATEWAY_HOST': '0.0.0.0', 'GATEWAY_LEGACY_BEARER': 'true',
                         'GATEWAY_REMOTE_ENGINEERING': 'true'}
            with patch.dict(os.environ, inherited, clear=True):
                manager = environment(temp, validate({}))
            with patch.dict(os.environ, manager, clear=True):
                engine = environment(temp, validate({}))
            self.assertEqual(str(policy), engine[SETTING])
            self.assertEqual('127.0.0.1', engine['GATEWAY_HOST'])
            self.assertEqual('false', engine['GATEWAY_REMOTE_ENGINEERING'])
            self.assertNotIn('GATEWAY_LEGACY_BEARER', engine)
            with patch.dict(os.environ, engine, clear=True):
                approved, _, _, commands, _ = load_policy('owner/repo')
            self.assertEqual([['python', '-m', 'unittest']], commands)
            self.assertEqual('a' * 64, approved['image'].split('@sha256:')[1])

    def test_saved_selection_survives_clean_restart_and_keeps_rollback_settings(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cfg = validate({})
            atomic_json(root / 'settings.json', cfg)
            policy = self.policy(root)
            self.assertEqual({'state': 'VALIDATED', 'repositories': 1}, save_policy_path(root, str(policy)))
            with patch.dict(os.environ, {}, clear=True):
                first = environment(root, load(root))
            with patch.dict(os.environ, first, clear=True):
                second = environment(root, load(root))
            self.assertEqual(str(policy), second[SETTING])
            self.assertEqual(cfg, json.loads((root / 'settings.json').read_text()))
            self.assertEqual(cfg, load(root))

    def test_saved_selection_wins_and_explicit_removal_does_not_reinherit(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            with patch.dict(os.environ, {SETTING: '/other/host-policy.json'}, clear=True):
                save_policy_path(temp, str(policy))
                self.assertEqual(str(policy), environment(temp, validate({}))[SETTING])
                save_policy_path(temp, '')
                self.assertNotIn(SETTING, environment(temp, validate({})))

    def test_disappearing_policy_remains_identified_for_admission_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            save_policy_path(temp, str(policy))
            policy.unlink()
            with patch.dict(os.environ, {}, clear=True):
                env = environment(temp, validate({}))
            with patch.dict(os.environ, env, clear=True), self.assertRaises(ValidationPolicyError) as failed:
                load_policy('owner/repo')
            self.assertEqual('MISSING_OR_OVERSIZED', failed.exception.state)

    def test_repository_without_mapping_remains_unconfigured(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.policy(temp)
            with self.assertRaises(ValidationPolicyError) as failed:
                load_policy('owner/other', policy_path=str(path))
            self.assertEqual('REPOSITORY_UNCONFIGURED', failed.exception.state)

    def test_invalid_policy_cannot_replace_saved_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            save_policy_path(temp, str(policy))
            invalid = Path(temp) / 'invalid.json'
            cases = ['{}', '[]', '{invalid', json.dumps({'owner/repo': {'mirror': temp, 'image': 'unpinned', 'commands': [['true']]}}), ' ' * 65537]
            for content in cases:
                invalid.write_text(content)
                with self.subTest(content=content[:30]), self.assertRaises(ValueError):
                    save_policy_path(temp, str(invalid))
                self.assertEqual(str(policy), selected_policy_path(temp))
            with self.assertRaises(ValueError):
                save_policy_path(temp, 'relative.json')
            with self.assertRaises(ValueError):
                save_policy_path(temp, str(Path(temp) / 'missing.json'))

    def test_policy_check_does_not_modify_environment_or_execute_commands(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: '/existing.json'}, clear=True), \
                patch('v14.engineering_validation.run_bounded') as execute:
            policy = self.policy(temp)
            self.assertEqual('VALIDATED', check_policy_file(str(policy))['state'])
            self.assertEqual('/existing.json', os.environ[SETTING])
            execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
