"""Production-method regressions; fixture execution is not live-model qualification."""
import base64
from copy import deepcopy
import io
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
from benchmarks import coding, fixtures, sandbox
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.engineering_post_merge import inspect
from v14.engineering_source import prepare_source
from v14.engineering_workspace import act


class CompletionTests(unittest.TestCase):
    def setUp(self):
        self.backend = GitHubEngineeringBackend(SimpleNamespace())
        self.job = {'id': 'eng_second', 'principal': 'owner', 'deadline': time.time() + 60,
                    'request': {'repository': 'owner/repo', 'branch': 'feature', 'base': 'main'},
                    'data': {'head_sha': 'a' * 40, 'actor_id': 1, 'merge_sha': 'b' * 40}}

    def test_followup_inherits_only_verified_pr_ownership(self):
        self.job.update(continuation_of='eng_first', pr_owner_job_id='eng_first', continuation_actor_id=1,
                        continuation_branch='feature', continuation_pr=7)
        pr = {'number': 7, 'html_url': 'https://github.com/owner/repo/pull/7', 'body': 'Autonomous engineering job `eng_first`.',
              'state': 'open', 'head': {'sha': 'a' * 40, 'ref': 'feature', 'repo': {'full_name': 'owner/repo'}}, 'base': {'ref': 'main'}}
        self.backend.paged = Mock(return_value=[pr])
        self.assertEqual(7, self.backend.find_pr(self.job)['pr'])
        for field, value in [('continuation_actor_id', 2), ('continuation_branch', 'other'), ('continuation_pr', 8)]:
            bad = dict(self.job, **{field: value})
            with self.assertRaises(ValueError):
                self.backend.find_pr(bad)
        pr['head']['sha'] = 'c' * 40
        with self.assertRaises(ValueError):
            self.backend.find_pr(self.job)

    def test_large_file_tail_discovered_read_and_anchored_against_exact_blob(self):
        text = ('# filler for a large source file\n' * 20000) + 'answer = 41\n'
        sha = blob_sha(text)
        self.job['data'].update(tree={'large.py': {'sha': sha, 'mode': '100644'}}, files={})
        self.backend.api = Mock(return_value={'encoding': 'base64', 'size': len(text.encode()), 'content': base64.b64encode(text.encode()).decode()})
        found = act(self.backend, self.job, {'action': 'search', 'head_sha': 'a' * 40, 'paths': ['large.py'], 'query': 'answer'})
        self.assertEqual(20001, found['matches'][0]['line'])
        proposal = {'changes': [{'path': 'large.py', 'expected_blob_sha': sha, 'edits': [{'old': 'answer = 41', 'new': 'answer = 42'}]}]}
        with self.assertRaisesRegex(ValueError, 'Read the exact patch anchor'):
            prepare_changes(proposal, self.job['data']['files'], self.job['data']['tree'])
        page = act(self.backend, self.job, {'action': 'read', 'head_sha': 'a' * 40, 'path': 'large.py', 'start_line': 20001})
        self.assertEqual('answer = 41\n', page['content'])
        changes = prepare_changes(proposal, self.job['data']['files'], self.job['data']['tree'])
        self.assertTrue(changes[0]['content'].endswith('answer = 42\n'))
        self.assertEqual(1, self.backend.api.call_count)
        with self.assertRaisesRegex(ValueError, 'pinned head'):
            act(self.backend, self.job, {'action': 'read', 'head_sha': 'c' * 40, 'path': 'large.py'})

    def test_post_merge_needs_exact_revision_stable_inventory_and_green_runs(self):
        self.backend.ref = Mock(return_value='c' * 40)
        self.backend.api = Mock(return_value={'status': 'ahead', 'merge_base_commit': {'sha': 'b' * 40}})
        run = {'id': 20, 'workflow_id': 1, 'name': 'Build', 'event': 'push', 'head_sha': 'b' * 40,
               'run_attempt': 1, 'status': 'completed', 'conclusion': 'success'}
        self.backend.paged = Mock(return_value=[run])
        first = inspect(self.backend, self.job)
        self.assertEqual('PENDING', first['status'])
        first['observed_at'] -= 30
        self.job['data']['post_merge'] = first
        self.assertEqual('PASSED', inspect(self.backend, self.job)['status'])
        run['conclusion'] = 'failure'
        failed = inspect(self.backend, self.job)
        self.assertTrue(failed['merged'])
        self.assertEqual('FAILED', failed['status'])
        run['head_sha'] = 'a' * 40
        self.assertEqual('PENDING', inspect(self.backend, self.job)['status'])
        self.backend.api.return_value['merge_base_commit']['sha'] = 'd' * 40
        self.assertEqual('BLOCKED', inspect(self.backend, self.job)['status'])

    def test_driver_observes_nested_mutation_and_grader_denies_perfect_score(self):
        def mutate(request):
            request['nested']['items'].clear()
            return {'correct': True}
        driver = sandbox.DRIVER.replace("sys.path.insert(0, '/candidate')", '')
        output = io.StringIO()
        with patch.dict(sys.modules, {'service': SimpleNamespace(handle=mutate)}), \
                patch('builtins.open', return_value=io.StringIO('[{"nested":{"items":[1,2]}}]')), patch('sys.stdout', output):
            exec(driver, {})
        self.assertIs(False, json.loads(output.getvalue().splitlines()[-1])['outputs'][0]['input_preserved'])
        task = fixtures.suite('quick')[0]
        text = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source), 'content': task['referenceSources'][path]}
                                       for path, source in task['sources'].items()]})
        def execute(candidate, inputs, *_args, **_kwargs):
            return {'executed': True, 'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, value), 'input_preserved': False} for value in inputs]}
        result = coding.grade(task, text, {'sandbox_image': 'fixture@sha256:' + 'a' * 64}, None, execute=execute)
        self.assertFalse(result['resolved'])
        self.assertEqual('FAILED', result['inputMutationVerification'])
        self.assertLess(result['score'], 1000)

    def test_stale_mirror_fetches_and_verifies_new_published_parent(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        with tempfile.TemporaryDirectory() as directory:
            source, mirror = Path(directory) / 'source', Path(directory) / 'mirror.git'
            def git(*args):
                return subprocess.check_output(['git', *args], env=env, stderr=subprocess.DEVNULL, text=True).strip()
            git('init', '-q', str(source))
            git('-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'initial')
            git('clone', '--mirror', str(source), str(mirror))
            git('--git-dir=' + str(mirror), 'config', 'remote.origin.url', 'https://github.com/owner/repo.git')
            (source / 'file').write_text('new commit')
            git('-C', str(source), 'add', '.')
            git('-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'published')
            head, tree = git('-C', str(source), 'rev-parse', 'HEAD'), git('-C', str(source), 'rev-parse', 'HEAD^{tree}')
            self.job['data']['head_sha'] = head
            from v14 import engineering_source
            original = engineering_source.run
            def transport(argv, **kwargs):
                if 'fetch' in argv:
                    self.assertIn('https://github.com/owner/repo.git', argv)
                    git('--git-dir=' + str(mirror), 'fetch', str(source), head)
                    return ''
                return original(argv, **kwargs)
            with patch.object(engineering_source, 'load_policy', return_value=({}, mirror, None, None, None)), \
                    patch.object(engineering_source, 'run', side_effect=transport):
                result = prepare_source(self.job, None, tree, threading.Event())
                self.assertEqual({'head_sha': head, 'tree_sha': tree, 'status': 'VERIFIED'}, result)
                with self.assertRaisesRegex(ValueError, 'differs'):
                    prepare_source(self.job, None, 'f' * 40, threading.Event())
