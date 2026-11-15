"""Headless opt-in and isolation regressions; no real system commands."""
import json
from types import SimpleNamespace
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import engineering_setup as setup
from desktop import github_setup


class OnboardingTests(unittest.TestCase):
    def test_no_install_without_approval(self):
        with self.assertRaisesRegex(ValueError, 'Confirm'):
            setup.install_prerequisite('docker')
        with self.assertRaisesRegex(ValueError, 'Confirm'):
            setup.install_prerequisite('wsl')

    def test_install_git_uses_pinned_package_id_only_after_approval(self):
        with patch.object(setup, 'is_windows', return_value=True), \
             patch.object(setup.shutil, 'which', return_value='winget.exe'), \
             patch.object(setup.validation, 'run', return_value='') as run:
            result = setup.install_prerequisite('git', approved=True)
        self.assertIn('Install Git', result)
        self.assertEqual(['winget.exe', 'install', '--id', 'Git.Git', '--exact',
                          '--source', 'winget', '--accept-package-agreements',
                          '--accept-source-agreements'], run.call_args.args[0])

    def test_missing_or_failed_wsl_status_blocks_preparation(self):
        with patch.object(setup, 'is_windows', return_value=True), \
             patch.object(setup.shutil, 'which', return_value='wsl.exe'), \
             patch.object(setup.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
             patch.object(setup, 'executable', return_value='fixture'), \
             patch.object(setup.validation, 'check_docker'), \
             patch.object(setup, 'credential_status', return_value={
                 'state': 'PASS', 'detail': 'ok', 'repairable': False}):
            result = setup.check_environment()
        self.assertFalse(result['ready_for_validation'])
        self.assertEqual('ACTION', result['checks']['wsl'][0])

    def test_invalid_action_never_runs(self):
        with patch.object(setup, 'is_windows', return_value=True), patch.object(setup.validation, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'Unsupported'):
                setup.install_prerequisite('unexpected', approved=True)
            command.assert_not_called()

    def test_no_credentials_are_exposed_or_changed(self):
        with tempfile.TemporaryDirectory() as temp:
            conf = Path(temp) / '.docker' / 'config.json'
            conf.parent.mkdir()
            raw = '{"credsStore":"bad helper","auths":{"x":"supersecret"}}'
            conf.write_text(raw)
            info = setup.credential_status(temp)
            self.assertEqual('ACTION', info['state'])
            self.assertNotIn('supersecret', str(info))
            self.assertEqual(raw, conf.read_text())

    def test_helper_existing_without_path_has_safe_repair_offer(self):
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / '.docker' / 'config.json'
            config.parent.mkdir()
            config.write_text(json.dumps({'credsStore': 'desktop'}))
            helper = Path(temp) / 'docker-credential-desktop.exe'
            helper.touch()
            with patch.object(setup, 'is_windows', return_value=True), \
                 patch.object(setup, '_helpers', return_value=[helper]), \
                 patch.object(setup.shutil, 'which', return_value=None):
                info = setup.credential_status(temp)
            self.assertTrue(info['repairable'])

    def test_smoke_requires_immutable_digest(self):
        with patch.object(setup.validation, 'docker_run') as run:
            with self.assertRaisesRegex(ValueError, 'digest'):
                setup.smoke_image('python:3.12-slim-bookworm')
            run.assert_not_called()

    def test_smoke_no_network_or_privileges(self):
        pin = 'docker.io/library/python@sha256:' + 'a' * 64
        with patch.object(setup.validation, 'docker_run', return_value='Python 3.12.9') as run:
            self.assertIn('Verified', setup.smoke_image(pin))
        args = run.call_args.args[0]
        for flag in ('--pull=never', '--network=none', '--read-only', '--cap-drop=ALL', pin):
            self.assertIn(flag, args)

    def test_gateway_engine_independent_of_user_cli_helper(self):
        with patch.object(setup, 'is_windows', return_value=True), \
             patch.object(setup.shutil, 'which', return_value='wsl.exe'), \
             patch.object(setup.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
             patch.object(setup, 'executable', return_value='fixture'), \
             patch.object(setup.validation, 'check_docker'), \
             patch.object(setup, 'credential_status',
                          return_value={'state': 'ACTION', 'detail': 'helper missing', 'repairable': False}):
            report = setup.check_environment()
        self.assertTrue(report['ready_for_validation'])
        self.assertEqual('ACTION', report['checks']['credentials'][0])


    def test_saved_policy_smoke_failure_reports_unverified_without_rollback(self):
        from desktop import repository_validation as validation
        with patch.object(setup, 'check_environment', return_value={'ready_for_validation': True}), \
             patch('desktop.validation_policy.load_policy_snapshot',
                   return_value={'state':'OK', 'revision':'r1', 'repositories':{}}), \
             patch.object(validation, 'options_for', return_value={'repository':'o/r'}), \
             patch('desktop.configuration.load', return_value={}), \
             patch.object(github_setup, 'configured_connection', return_value=('none', None)), \
             patch.object(validation, 'setup', side_effect=[
                 {'image':'image@sha256:'+'a'*64, 'snapshot':{'revision':'r2'}},
                 {'image':'image@sha256:'+'a'*64}]) as execute, \
             patch.object(setup, 'smoke_image', side_effect=ValueError('container unavailable')):
            result = setup.prepare_and_verify(Path('/tmp/fixture'))
        self.assertEqual('PREPARED_UNVERIFIED', result['state'])
        self.assertIn('saved', result['message'])
        self.assertEqual(2, execute.call_count)

    def test_preparation_smoke_success_requires_real_observation(self):
        from desktop import repository_validation as validation
        with patch.object(setup, 'check_environment', return_value={'ready_for_validation': True}), \
             patch('desktop.validation_policy.load_policy_snapshot',
                   return_value={'state':'OK', 'revision':'r1', 'repositories':{}}), \
             patch.object(validation, 'options_for', return_value={'repository':'o/r'}), \
             patch('desktop.configuration.load', return_value={}), \
             patch.object(github_setup, 'configured_connection', return_value=('none', None)), \
             patch.object(validation, 'setup', side_effect=[
                 {'image':'image@sha256:'+'a'*64, 'snapshot':{'revision':'r2'}},
                 {'image':'image@sha256:'+'a'*64}]), \
             patch.object(setup, 'smoke_image', return_value='Verified container'):
            result = setup.prepare_and_verify(Path('/tmp/fixture'))
        self.assertEqual('VERIFIED', result['state'])
        self.assertIn('Verified container', result['message'])


if __name__ == '__main__':
    unittest.main()
