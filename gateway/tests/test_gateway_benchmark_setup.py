"""Regressions for explicit, digest-pinned benchmark sandbox preparation."""
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import sandbox, settings
from desktop import repository_validation as validation
from v14 import validation_host

PIN = 'docker.io/library/python@sha256:' + 'a' * 64


class BenchmarkSandboxSetupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_blank_pin_blocks_before_docker(self):
        with patch.object(sandbox, 'local_docker') as found, self.assertRaises(sandbox.SandboxUnavailable) as failure:
            sandbox.preflight(settings.load_settings(self.root))
        self.assertEqual('IMAGE_NOT_CONFIGURED', failure.exception.reason)
        found.assert_not_called()

    def test_preparation_persists_only_after_isolated_python_smoke(self):
        inspected = []
        def smoke(args, **kwargs):
            inspected.extend(args)
            return 'GATEWAY_BENCHMARK_PYTHON_READY_V1'
        with patch.object(validation_host, 'executable', return_value='/local/docker'), \
                patch.object(validation, 'check_docker') as checked, \
                patch.object(validation, 'pin_image', return_value=PIN) as pinned, \
                patch.object(sandbox, 'preflight', return_value='/local/docker'), \
                patch.object(validation, 'docker_run', side_effect=smoke):
            image = settings.prepare_sandbox(self.root)
        self.assertEqual(PIN, image)
        self.assertEqual(PIN, settings.load_settings(self.root)['sandbox_image'])
        checked.assert_called_once_with('/local/docker', cancel=None)
        self.assertEqual(validation.DEFAULT_IMAGE, pinned.call_args.args[1])
        self.assertTrue(pinned.call_args.kwargs['prepare'])
        for flag in ('--pull=never', '--network=none', '--read-only', '--cap-drop=ALL',
                     '--security-opt=no-new-privileges', '--user=65534:65534'):
            self.assertIn(flag, inspected)

    def test_smoke_failure_does_not_store_unverified_pin(self):
        with patch.object(validation_host, 'executable', return_value='/local/docker'), \
                patch.object(validation, 'check_docker'), \
                patch.object(validation, 'pin_image', return_value=PIN), \
                patch.object(sandbox, 'preflight', return_value='/local/docker'), \
                patch.object(validation, 'docker_run', return_value='incorrect'):
            with self.assertRaisesRegex(ValueError, 'startup receipt'):
                settings.prepare_sandbox(self.root)
        self.assertEqual('', settings.load_settings(self.root)['sandbox_image'])

    def test_stale_settings_and_cancel_do_not_change_active_configuration(self):
        original = settings.load_settings(self.root)
        saved = settings.save_settings(self.root, dict(original, repair_attempts=2))
        with self.assertRaisesRegex(ValueError, 'changed'):
            settings.prepare_sandbox(self.root, expected=original)
        cancelled = threading.Event()
        cancelled.set()
        with self.assertRaises(validation.SetupCancelled):
            settings.prepare_sandbox(self.root, cancel=cancelled)
        self.assertEqual(saved, settings.load_settings(self.root))

    def test_preflight_distinguishes_missing_docker_from_missing_image(self):
        with patch.object(sandbox, 'local_docker', return_value=None), self.assertRaises(sandbox.SandboxUnavailable) as failure:
            sandbox.preflight({'sandbox_image': PIN})
        self.assertEqual('DOCKER_NOT_INSTALLED', failure.exception.reason)
        def observed(argv, *_args, **_kwargs):
            return {'exit_code': 1, 'output_tail': ''} if argv[5] == 'image' else {'exit_code': 0, 'output_tail': 'linux'}
        with patch.object(sandbox, 'local_docker', return_value='docker'), \
                patch.object(sandbox, 'run_bounded', side_effect=observed), \
                self.assertRaises(sandbox.SandboxUnavailable) as failure:
            sandbox.preflight({'sandbox_image': PIN})
        self.assertEqual('IMAGE_NOT_INSTALLED', failure.exception.reason)


if __name__ == '__main__':
    unittest.main()
