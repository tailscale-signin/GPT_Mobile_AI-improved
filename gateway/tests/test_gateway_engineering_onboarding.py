"""Headless opt-in and isolation regressions; no real system commands."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import engineering_setup as setup


class OnboardingTests(unittest.TestCase):
    def test_no_install_without_approval(self):
        with self.assertRaisesRegex(ValueError, 'Confirm'):
            setup.install_prerequisite('docker')
        with self.assertRaisesRegex(ValueError, 'Confirm'):
            setup.install_prerequisite('wsl')

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
             patch.object(setup.validation, 'run', return_value=''), \
             patch.object(setup, 'executable', return_value='fixture'), \
             patch.object(setup.validation, 'check_docker'), \
             patch.object(setup, 'credential_status',
                          return_value={'state': 'ACTION', 'detail': 'helper missing', 'repairable': False}):
            report = setup.check_environment()
        self.assertTrue(report['ready_for_validation'])
        self.assertEqual('ACTION', report['checks']['credentials'][0])


if __name__ == '__main__':
    unittest.main()
