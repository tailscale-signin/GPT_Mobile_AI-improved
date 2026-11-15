from contextlib import nullcontext
import hashlib
import json
from pathlib import Path
import sys
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_release_checks import contract, download, verify


class ReleaseChecksTests(unittest.TestCase):
    def test_normal_settings_round_trip_preserves_release_requirements(self):
        import tempfile
        from desktop import repository_validation as settings
        from desktop.release_checks_dialog import configure
        with tempfile.TemporaryDirectory() as root:
            options = settings.defaults(Path(root), settings.DEFAULT_REPOSITORY)
            before = json.loads(json.dumps(options))
            cfg = {'artifacts': [{'workflow': '.github/workflows/build.yml', 'name': 'package', 'maximum_bytes': 1024}],
                   'installation_checks': [0], 'smoke_checks': [0]}
            configured = configure(options, cfg['artifacts'], [0], [0])
            saved = settings.entry_from_options(configured)
            reloaded = settings.options_for(Path(root), settings.DEFAULT_REPOSITORY, {settings.DEFAULT_REPOSITORY: saved})
            self.assertEqual(cfg, reloaded['post_merge'])
            self.assertEqual(before, options)

    def setUp(self):
        self.body = b'zip fixture bytes'
        self.cfg = {'artifacts': [{'workflow': '.github/workflows/build.yml', 'name': 'package', 'maximum_bytes': 1024}],
                    'installation_checks': [0], 'smoke_checks': [1]}
        self.policy = {'commands': [['install'], ['smoke']], 'post_merge': self.cfg}
        policy_sha = hashlib.sha256(json.dumps(self.policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        self.job = {'request': {'repository': 'owner/repo'}, 'data': {'head_sha': 'a' * 40, 'merge_sha': 'b' * 40,
                    'admission': {'policy_sha256': policy_sha, 'post_merge_contract': self.cfg}}}
        self.artifact = {'id': 7, 'name': 'package', 'expired': False, 'created_at': '2026-11-14T02:01:00Z',
                         'digest': 'sha256:' + hashlib.sha256(self.body).hexdigest(), 'size_in_bytes': len(self.body)}
        self.runs = {'.github/workflows/build.yml': {'id': 3, 'run_attempt': 2, 'run_started_at': '2026-11-14T02:00:00Z'}}
        self.backend = SimpleNamespace(root=lambda job: '/repos/owner/repo', paged=Mock(return_value=[self.artifact]),
            token=Mock(return_value='private-fixture-token'), cancellation=lambda job: threading.Event(),
            api=Mock(return_value={'tree': {'sha': 'c' * 40}}),
            github_governor=SimpleNamespace(lease=lambda *args, **kw: nullcontext(), observe=Mock()))

    def verification(self):
        return patch('v14.engineering_validation.load_policy', return_value=(self.policy, None, None, self.policy['commands'], []))

    def test_download_and_checks_bind_to_merge_and_latest_run_attempt(self):
        with self.verification(), patch('v14.engineering_release_checks.download', return_value=self.body), \
                patch('v14.engineering_source.prepare_source'), patch('v14.engineering_validation.validate', return_value={'status': 'PASSED'}) as validate:
            result = verify(self.backend, self.job, self.runs)
        self.assertEqual('PASSED', result['artifact_verification'])
        self.assertEqual('PASSED', result['installation_verification'])
        self.assertEqual('PASSED', result['smoke_verification'])
        self.assertFalse(result['device_tested'])
        self.assertEqual(2, result['artifacts'][0]['run_attempt'])
        self.assertEqual([0, 1], [call.kwargs['command_index'] for call in validate.call_args_list])
        for call in validate.call_args_list:
            self.assertEqual('b' * 40, call.args[0]['data']['head_sha'])
            self.assertEqual({'package.zip': self.body}, call.kwargs['artifacts'])

    def test_old_attempt_expired_ambiguous_and_missing_digest_never_pass(self):
        for rows in ([dict(self.artifact, created_at='2026-11-14T01:00:00Z')],
                     [dict(self.artifact, expired=True)], [self.artifact, self.artifact], [dict(self.artifact, digest=None)]):
            with self.subTest(rows=rows), self.verification(), patch('v14.engineering_release_checks.download') as read:
                self.backend.paged.return_value = rows
                self.assertEqual('BLOCKED', verify(self.backend, self.job, self.runs)['status'])
                read.assert_not_called()

    def test_digest_mismatch_and_failed_installation_block_release(self):
        with self.verification(), patch('v14.engineering_release_checks.download', return_value=b'corrupted'):
            self.assertEqual('FAILED', verify(self.backend, self.job, self.runs)['status'])
        with self.verification(), patch('v14.engineering_release_checks.download', return_value=self.body), \
                patch('v14.engineering_source.prepare_source'), patch('v14.engineering_validation.validate', return_value={'status': 'FAILED'}) as validate:
            result = verify(self.backend, self.job, self.runs)
            self.assertEqual('MERGE_INSTALLATION_CHECK_FAILED', result['reason'])
            self.assertEqual(1, validate.call_count)

    def test_download_drops_credentials_at_signed_storage_and_bounds_bytes(self):
        redirect = Mock(status_code=302, headers={'Location': 'https://fixture.blob.core.windows.net/file?signature=fixture'})
        redirect.__enter__ = Mock(return_value=redirect)
        redirect.__exit__ = Mock(return_value=False)
        storage = Mock(status_code=200, headers={})
        storage.iter_content.return_value = [b'ab', b'cd']
        storage.__enter__ = Mock(return_value=storage)
        storage.__exit__ = Mock(return_value=False)
        with patch('v14.engineering_release_checks.requests.get', side_effect=[redirect, storage]) as get:
            self.assertEqual(b'abcd', download(self.backend, self.job, 7, 4))
            self.assertIn('Authorization', get.call_args_list[0].kwargs['headers'])
            self.assertNotIn('headers', get.call_args_list[1].kwargs)
            self.assertFalse(get.call_args_list[1].kwargs['allow_redirects'])
        with patch('v14.engineering_release_checks.requests.get', side_effect=[redirect, storage]), self.assertRaises(ValueError):
            download(self.backend, self.job, 7, 3)
        redirect.headers['Location'] = 'https://attacker.invalid/file'
        with patch('v14.engineering_release_checks.requests.get', return_value=redirect) as get, self.assertRaises(ValueError):
            download(self.backend, self.job, 7, 4)
        self.assertEqual(1, get.call_count)

    def test_invalid_contract_and_changed_policy_do_not_execute(self):
        for change in ({'installation_checks': [True]}, {'smoke_checks': [[]]}, {'artifacts': [{'name': '../escape'}]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                contract(dict(self.cfg, **change), 2)
        self.policy['commands'].append(['changed'])
        with self.verification(), patch('v14.engineering_release_checks.download') as read:
            self.assertEqual('MERGE_RELEASE_POLICY_CHANGED', verify(self.backend, self.job, self.runs)['reason'])
            read.assert_not_called()


if __name__ == '__main__':
    unittest.main()
