"""Real validator/command selection, with Docker transport substituted on the host."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha
from v14.engineering_validation import run_bounded, validate
from v14.engineering_workspace import act


class WorkspaceChecksTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = self.root / 'source'
        source.mkdir()
        (source / 'file.py').write_text('answer = 1\n')
        env = {key: value for key, value in os.environ.items() if not key.upper().startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        for argv in (['git', 'init', '-q', str(source)], ['git', '-C', str(source), 'add', '.'],
                     ['git', '-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                      '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Fixture']):
            subprocess.run(argv, check=True, capture_output=True, env=env)
        self.head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True, env=env).strip()
        self.image = 'fixture@sha256:' + 'a' * 64
        self.policy = {'mirror': str(source / '.git'), 'image': self.image,
                       'commands': [[sys.executable, '-c', 'raise RuntimeError("unselected")'],
                                    [sys.executable, '-c', 'print("first\\nsecond\\nthird")']],
                       'acceptance_map': []}
        self.policy_path = self.root / 'policy.json'
        self.policy_path.write_text(json.dumps({'owner/repo': self.policy}))
        policy_sha = hashlib.sha256(json.dumps(self.policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        self.job = {'id': 'job', 'principal': 'owner', 'deadline': time.time() + 60,
                    'request': {'repository': 'owner/repo'},
                    'data': {'head_sha': self.head, 'admission': {'policy_sha256': policy_sha}, 'files': {}, 'tree': {}}}
        self.backend = GitHubEngineeringBackend(SimpleNamespace())
        self.backend.api = Mock(return_value={'tree': {'sha': 'b' * 40}})
        self.backend.token = Mock(return_value='fixture')
        self.backend.cancellation = Mock(return_value=threading.Event())
        self.environment = patch.dict(os.environ, {'GATEWAY_ENGINEERING_VALIDATION_POLICY': str(self.policy_path)})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.calls = []

    def execute(self, argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None):
        if argv[0] != 'docker':
            return run_bounded(argv, cwd, timeout, maximum, destination)
        self.calls.append(argv)
        mount = next(value for value in argv if value.startswith('--mount=type=bind,source='))
        checkout = mount.removeprefix('--mount=type=bind,source=').removesuffix(',target=/workspace')
        return run_bounded(argv[argv.index(self.image) + 1:], checkout, timeout)

    def action(self, **values):
        return act(self.backend, self.job, dict(head_sha=self.head, **values))

    def test_select_second_check_and_page_retained_output_without_running_first(self):
        self.assertEqual([0, 1], [row['index'] for row in self.action(action='checks')['checks']])
        with patch('v14.engineering_source.prepare_source') as prepare, \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            receipt = self.action(action='check', check_index=1)
            self.assertEqual('PASSED', receipt['status'])
            self.assertEqual(1, receipt['check_index'])
            self.assertFalse(receipt['complete_validation'])
            self.assertNotIn('output_tail', receipt)
            self.assertEqual(1, len(self.calls))
            prepare.assert_called_once()
            self.assertEqual(receipt, self.action(action='check', check_index=1))
            self.assertEqual(1, len(self.calls))
        page = self.action(action='check_output', check_id=receipt['check_id'], start_line=2, line_count=1)
        self.assertEqual('second\n', page['content'])
        self.assertEqual(3, page['next_line'])
        self.assertEqual(19, page['output_bytes'])
        with self.assertRaises(ValueError):
            self.action(action='check_output', check_id=receipt['check_id'], start_line=8)

    def test_check_executes_inspected_patch_and_preserves_real_source(self):
        content = 'answer = 1\n'
        sha = blob_sha(content)
        self.job['data'].update(tree={'file.py': {'mode': '100644', 'sha': sha}},
                                files={'file.py': {'sha': sha, 'content': content, 'preview_characters': 0}})
        proposal = {'changes': [{'path': 'file.py', 'expected_blob_sha': sha,
                                 'edits': [{'old': 'answer = 1', 'new': 'answer = 2'}]}]}
        with self.assertRaises(ValueError):
            self.action(action='check', check_index=1, proposal=proposal)
        # Use whole-file exposure exactly as the production model delivery path.
        from v14.engineering_workspace import record_model_exposure
        request = {'messages': [{'role': 'user', 'content': json.dumps({'evidence': {'head_sha': self.head, 'files': {
            'file.py': {'sha': sha, 'content': content, 'partial': False}}}})}]}
        record_model_exposure(self.job['data'], request)
        def observe(argv, *args, **kwargs):
            if argv[0] == 'docker':
                mount = next(value for value in argv if value.startswith('--mount=type=bind,source='))
                checkout = mount.removeprefix('--mount=type=bind,source=').removesuffix(',target=/workspace')
                self.assertEqual('answer = 2\n', (Path(checkout) / 'file.py').read_text())
            return self.execute(argv, *args, **kwargs)
        with patch('v14.engineering_source.prepare_source'), \
                patch('v14.engineering_validation.run_bounded', side_effect=observe):
            receipt = self.action(action='check', check_index=1, proposal=proposal)
        self.assertEqual('PASSED', receipt['status'])
        self.assertEqual(content, (self.root / 'source/file.py').read_text())

    def test_stale_policy_and_unapproved_command_are_rejected_before_execution(self):
        for value in (-1, 2, True, '1'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.action(action='check', check_index=value)
        self.policy['commands'].append(['arbitrary'])
        self.policy_path.write_text(json.dumps({'owner/repo': self.policy}))
        with self.assertRaises(ValueError):
            self.action(action='checks')
        self.backend.api.assert_not_called()

    def test_selected_receipt_cannot_authorize_commit(self):
        from v14.engineering_validation import patch_identity
        self.job['data'].update(review={'approved': True}, changes=[], local_validation={
            'status': 'PASSED', 'scope': 'exploratory', 'patch_identity': patch_identity(self.head, [])})
        self.backend.identity = Mock(return_value={'id': 1})
        with self.assertRaisesRegex(ValueError, 'Exploratory'):
            self.backend.step(self.job, 'COMMIT')
        self.backend.api.assert_not_called()

    def test_complete_validation_still_requires_all_commands(self):
        with patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            result = validate(self.job, [])
        self.assertEqual('FAILED', result['status'])
        self.assertTrue(result['complete_validation'])
        self.assertEqual('complete', result['scope'])
        self.assertEqual([0], [row['command_index'] for row in result['commands']])

    def test_cancellation_prevents_command_dispatch(self):
        self.backend.cancellation.return_value.set()
        with patch('v14.engineering_source.prepare_source'), \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute), self.assertRaises(InterruptedError):
            self.action(action='check', check_index=1)
        self.assertEqual([], self.calls)

    def test_downloaded_artifacts_are_copied_into_only_the_isolated_checkout(self):
        def observe(argv, *args, **kwargs):
            if argv[0] == 'docker':
                mount = next(value for value in argv if value.startswith('--mount=type=bind,source='))
                checkout = mount.removeprefix('--mount=type=bind,source=').removesuffix(',target=/workspace')
                self.assertEqual(b'actual downloaded bytes', (Path(checkout) / '.gateway-artifacts/release.zip').read_bytes())
            return self.execute(argv, *args, **kwargs)
        with patch('v14.engineering_validation.run_bounded', side_effect=observe):
            result = validate(self.job, [], command_index=1, artifacts={'release.zip': b'actual downloaded bytes'})
        self.assertEqual('PASSED', result['status'])
        self.assertFalse((self.root / 'source/.gateway-artifacts').exists())


if __name__ == '__main__':
    unittest.main()
