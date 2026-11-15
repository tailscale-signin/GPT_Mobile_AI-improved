"""Real validator/command selection, with Docker transport substituted on the host."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
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
from v14.engineering import EngineeringStore
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_validation import run_bounded, validate
from v14.engineering_workspace import act
from v14.execution_binding import ExecutionBindingError


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
                                    [sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"first\\nsecond\\nthird\\n")']],
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
        return run_bounded(argv[argv.index(self.image) + 1:], checkout, timeout, maximum, destination)

    def action(self, **values):
        return act(self.backend, self.job, dict(head_sha=self.head, **values))

    def write_policy(self):
        self.policy_path.write_text(json.dumps({'owner/repo': self.policy}))
        self.job['data']['admission']['policy_sha256'] = hashlib.sha256(
            json.dumps(self.policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    def enable_private_artifacts(self):
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
                                  durable_job_lock=threading.RLock(),
                                  _durable_connect=lambda: sqlite3.connect(self.root / 'jobs.db'))
        store = EngineeringStore(runtime)
        store.tx(lambda db: db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,?,?,?,?)',
                                      (self.job['id'], self.job['principal'], 'fixture', 'fixture',
                                       'IMPLEMENT', 0, time.time(), None, json.dumps(self.job))))
        self.backend.private_artifacts = EngineeringArtifacts(store)
        return store

    def test_private_output_retains_complete_bytes_and_rejects_changed_or_missing_artifacts(self):
        raw = b'early\n' + b'middle\n' * 12000 + b'late\n'
        self.policy['commands'][1] = [sys.executable, '-c',
                                     'import sys; sys.stdout.buffer.write(b"early\\n" + b"middle\\n" * 12000 + b"late\\n")']
        self.write_policy()
        store = self.enable_private_artifacts()
        with patch('v14.engineering_source.prepare_source'), \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            receipt = self.action(action='check', check_index=1)
        self.assertEqual('PASSED', receipt['status'])
        self.assertEqual(len(raw), receipt['output_bytes'])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['output_sha256'])
        reference = receipt['output_ref']
        self.backend.private_artifacts = EngineeringArtifacts(EngineeringStore(store.runtime))
        self.assertEqual(raw, self.backend.private_artifacts.read_bytes(self.job, reference))
        first = self.action(action='check_output', check_id=receipt['check_id'], start_line=1, line_count=1)
        last = self.action(action='check_output', check_id=receipt['check_id'], start_line=12002, line_count=1)
        self.assertEqual(('early\n', 'FULL_OUTPUT', 12002),
                         (first['content'], first['retention'], first['retained_lines']))
        self.assertEqual('late\n', last['content'])
        self.assertIsNone(last['next_line'])
        saved = self.job['data']['workspace_checks'][receipt['check_id']]
        self.assertTrue(saved['output_tail'])
        self.assertNotIn('early\n', saved['output_tail'])
        saved['output_ref'] = dict(reference, sha256='0' * 64)
        with self.assertRaises(ExecutionBindingError) as changed:
            self.action(action='check_output', check_id=receipt['check_id'])
        self.assertEqual('EXECUTION_ARTIFACT_CHANGED', changed.exception.code)
        saved['output_ref'] = reference
        digest = store.tx(lambda db: db.execute(
            'SELECT digest FROM engineering_artifact_chunks WHERE artifact_id=? AND sequence=0',
            (reference['result_ref'],)).fetchone()[0])
        store.tx(lambda db: db.execute(
            'UPDATE engineering_artifact_chunks SET digest=? WHERE artifact_id=? AND sequence=0',
            ('0' * 64, reference['result_ref'])))
        with self.assertRaises(ExecutionBindingError) as corrupt:
            self.action(action='check_output', check_id=receipt['check_id'])
        self.assertEqual('EXECUTION_ARTIFACT_CORRUPT', corrupt.exception.code)
        store.tx(lambda db: db.execute(
            'UPDATE engineering_artifact_chunks SET digest=? WHERE artifact_id=? AND sequence=0',
            (digest, reference['result_ref'])))
        store.tx(lambda db: db.execute('DELETE FROM engineering_artifacts WHERE id=?', (reference['result_ref'],)))
        with self.assertRaises(ExecutionBindingError) as missing:
            self.action(action='check_output', check_id=receipt['check_id'])
        self.assertEqual('EXECUTION_ARTIFACT_UNAVAILABLE', missing.exception.code)
        self.assertEqual(1, len(self.calls))

    def test_candidate_reuse_is_bound_to_retained_patch_source_policy_and_selected_command(self):
        from v14.engineering_workspace import record_model_exposure

        self.policy['commands'][1] = [sys.executable, '-c',
                                     'from pathlib import Path; import sys; sys.stdout.buffer.write(Path("file.py").read_bytes())']
        self.write_policy()
        self.enable_private_artifacts()
        content = 'answer = 1\n'
        sha = blob_sha(content)
        self.job['data'].update(tree={'file.py': {'mode': '100644', 'sha': sha}},
                                files={'file.py': {'sha': sha, 'content': content, 'preview_characters': 0}})
        record_model_exposure(self.job['data'], {'messages': [{'content': json.dumps({'evidence': {
            'head_sha': self.head, 'files': {'file.py': {'sha': sha, 'content': content}}}})}]})
        proposal = {'changes': [{'path': 'file.py', 'expected_blob_sha': sha,
                                 'edits': [{'old': 'answer = 1', 'new': 'answer = 2'}]}]}
        with patch('v14.engineering_source.prepare_source'), \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            receipt = self.action(action='check', check_index=1, proposal=proposal)
            self.assertEqual(b'answer = 2\n', self.backend.private_artifacts.read_bytes(self.job, receipt['output_ref']))
            self.assertEqual(receipt, self.action(action='check', check_index=1, candidate_check_id=receipt['check_id']))
            self.assertEqual(1, len(self.calls))
            # Another approved command must execute and retain its own verdict;
            # it cannot borrow the earlier command's successful receipt.
            other = self.action(action='check', check_index=0, candidate_check_id=receipt['check_id'])
            self.assertEqual(('FAILED', 0), (other['status'], other['check_index']))
            self.assertNotEqual(receipt['check_id'], other['check_id'])
            self.assertEqual(2, len(self.calls))
        saved = self.job['data']['workspace_checks'][receipt['check_id']]
        reference = saved['candidate_ref']
        candidate = self.backend.private_artifacts.json_value(self.job, reference)
        saved['candidate_ref'] = self.backend.private_artifacts.put_json(
            self.job, [dict(candidate[0], content='answer = 3\n')], 'fixture-different-candidate')
        with self.assertRaisesRegex(ValueError, 'tested patch'):
            self.action(action='check', check_index=1, candidate_check_id=receipt['check_id'])
        saved['candidate_ref'] = reference
        self.job['data']['head_sha'] = 'c' * 40
        with self.assertRaisesRegex(ValueError, 'current source'):
            act(self.backend, self.job, {'action': 'check', 'head_sha': 'c' * 40,
                                        'check_index': 1, 'candidate_check_id': receipt['check_id']})
        self.job['data']['head_sha'] = self.head
        self.policy['commands'][1].append('changed-policy')
        self.policy_path.write_text(json.dumps({'owner/repo': self.policy}))
        with self.assertRaisesRegex(ValueError, 'unchanged admitted'):
            self.action(action='check', check_index=1, candidate_check_id=receipt['check_id'])
        self.write_policy()
        with self.assertRaisesRegex(ValueError, 'validation policy'):
            self.action(action='check', check_index=1, candidate_check_id=receipt['check_id'])
        self.policy['commands'][1].pop()
        self.write_policy()
        self.job['data']['workspace_checks'].pop(receipt['check_id'])
        with self.assertRaisesRegex(ValueError, 'retained candidate'):
            self.action(action='check', check_index=1, candidate_check_id=receipt['check_id'])
        self.job['data']['workspace_checks'][receipt['check_id']] = saved
        with self.assertRaisesRegex(ValueError, 'retained candidate'):
            self.action(action='check', check_index=1, candidate_check_id=receipt['check_id'], proposal=proposal)
        self.assertEqual(2, len(self.calls))
        self.assertEqual(content, (self.root / 'source/file.py').read_text())

    def test_explicit_attempts_reuse_receipts_and_stop_before_dispatch_at_retained_execution_cap(self):
        store = self.enable_private_artifacts()
        with patch('v14.engineering_source.prepare_source') as prepare, \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            first = self.action(action='check', check_index=1, attempt_id='attempt-0')
            self.assertEqual(first, self.action(action='check', check_index=1, attempt_id='attempt-0'))
            self.assertEqual(1, len(self.calls))
            for value in ('', 'not valid', True, 'x' * 65):
                with self.subTest(attempt_id=value), self.assertRaises(ValueError):
                    self.action(action='check', check_index=1, attempt_id=value)
            identities = {first['check_id']}
            for number in range(1, 24):
                receipt = self.action(action='check', check_index=1, attempt_id='attempt-' + str(number))
                self.assertEqual('PASSED', receipt['status'])
                self.assertEqual(first['patch_identity'], receipt['patch_identity'])
                identities.add(receipt['check_id'])
            self.assertEqual((24, 24, 24), (len(identities), len(self.calls), prepare.call_count))
            store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?',
                                          (json.dumps(self.job), self.job['id'])))
            self.job = EngineeringStore(store.runtime).get(self.job['id'], self.job['principal'])
            self.backend.private_artifacts = EngineeringArtifacts(EngineeringStore(store.runtime))
            self.assertEqual(24, self.job['data']['workspace_check_executions'])
            self.assertEqual(first, self.action(action='check', check_index=1, attempt_id='attempt-0'))
            with self.assertRaisesRegex(ValueError, 'execution allowance'):
                self.action(action='check', check_index=1, attempt_id='attempt-24')
            self.assertEqual((24, 24, 24), (len(self.calls), prepare.call_count, self.backend.api.call_count))

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

    def test_fresh_attempt_reuses_candidate_and_retains_output_before_the_tail(self):
        self.policy['commands'][1] = [sys.executable, '-c',
                                     'import sys; sys.stdout.buffer.write(b"first error\\n" + b"padding\\n" * 2500 + b"\\n")']
        self.policy_path.write_text(json.dumps({'owner/repo': self.policy}))
        self.job['data']['admission']['policy_sha256'] = hashlib.sha256(
            json.dumps(self.policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        retained = {}
        def save(raw):
            reference = {'id': len(retained)}
            retained[reference['id']] = raw
            return reference
        self.backend.private_artifacts = SimpleNamespace(
            put_json=lambda _, value, __: save(value),
            put=lambda _, chunks, **__: save(b''.join(chunks)),
            json_value=lambda _, ref: retained[ref['id']],
            read_bytes=lambda _, ref: retained[ref['id']])
        with patch('v14.engineering_source.prepare_source'), \
                patch('v14.engineering_validation.run_bounded', side_effect=self.execute):
            first = self.action(action='check', check_index=1)
            self.assertIsNotNone(first['candidate_ref'])
            self.assertEqual('first error\n', self.action(action='check_output', check_id=first['check_id'], line_count=1)['content'])
            fresh = self.action(action='check', check_index=1, candidate_check_id=first['check_id'], attempt_id='fresh-1')
            self.assertNotEqual(first['check_id'], fresh['check_id'])
            self.assertEqual(first['patch_identity'], fresh['patch_identity'])
            self.assertEqual(fresh, self.action(action='check', check_index=1, candidate_check_id=first['check_id'], attempt_id='fresh-1'))
            self.assertEqual(2, len(self.calls))
            self.assertEqual(2, self.job['data']['workspace_check_executions'])

    def test_check_executes_inspected_patch_and_preserves_real_source(self):
        content = 'answer = 1\n'
        sha = blob_sha(content)
        self.job['data'].update(tree={'file.py': {'mode': '100644', 'sha': sha}},
                                files={'file.py': {'sha': sha, 'content': content, 'preview_characters': 0}}, plan={})
        retained = {}
        def save(raw):
            reference = {'id': len(retained)}
            retained[reference['id']] = raw
            return reference
        self.backend.private_artifacts = SimpleNamespace(
            put_json=lambda _, value, __: save(value),
            put=lambda _, chunks, **__: save(b''.join(chunks)),
            json_value=lambda _, ref: retained[ref['id']])
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
        check_id = receipt['check_id']
        self.assertEqual(check_id, self.action(action='candidates')['candidates'][0]['check_id'])
        self.assertEqual('file.py', self.action(action='candidate_files', candidate_check_id=check_id)['files'][0]['path'])
        self.backend.identity = Mock(return_value={'id': 1})
        self.backend.model = Mock(return_value={'candidate_check_id': check_id})
        finalized = self.backend.step(self.job, 'IMPLEMENT')
        self.assertFalse(finalized['workspace_continue'])
        self.assertEqual('answer = 2\n', finalized['changes'][0]['content'])
        self.assertNotIn('local_validation', finalized)

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
