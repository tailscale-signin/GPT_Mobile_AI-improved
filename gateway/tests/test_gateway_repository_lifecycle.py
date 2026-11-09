"""Real local Git object/ref tests; no request or mutation reaches GitHub."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from v14.engineering import EngineeringStore
from v14.repository_lifecycle import RepositoryLifecycle, RepositoryLifecycleError, SafeGit, canonical, creation_push_arguments, ref_map, require_safe_lfs_version, validate_request


def git(cwd, *args):
    return subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', *args], cwd=cwd,
                          env=dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull),
                          check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


class LocalFixtureGit(SafeGit):
    """Only the fixture replaces the fixed production HTTPS remotes with local files."""
    def __init__(self, root, source, destination):
        super().__init__(root)
        self.source, self.destination = source, destination
        self.calls = []
        self.lose_push_response = False
        self.before_push = None
        self.allow_lfs = False
        self.lfs_version = b'git-lfs/3.7.1 (fixture)\n'
        self.lfs_contents = {}

    def environment(self, token, lfs_url=None):
        env = super().environment(token, lfs_url)
        index = int(env['GIT_CONFIG_COUNT'])
        env.update(GIT_CONFIG_COUNT=str(index + 1))
        env['GIT_CONFIG_KEY_' + str(index)] = 'protocol.file.allow'
        env['GIT_CONFIG_VALUE_' + str(index)] = 'always'
        return env

    def run(self, args, cwd, token, **kwargs):
        self.calls.append(list(args))
        if args[0] == 'lfs':
            if not self.allow_lfs:
                raise RepositoryLifecycleError('REPOSITORY_GIT_FAILED', 'Fixture has no Git LFS')
            if args[1] == 'version':
                return self.lfs_version
            if args[1] == 'fetch':
                for oid, contents in self.lfs_contents.items():
                    target = Path(cwd).parent / 'lfs-storage' / 'objects' / oid[:2] / oid[2:4] / oid
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(contents)
            if args[1] == 'checkout':
                asset = Path(cwd) / 'asset.bin'
                pointer = asset.read_text()
                oid = pointer.split('oid sha256:', 1)[1].splitlines()[0]
                asset.write_bytes(self.lfs_contents[oid])
            return b''
        source_url, destination_url = 'https://github.com/source/project.git', 'https://github.com/actor/copied.git'
        actual = [str(self.source) if arg == source_url else str(self.destination) if arg == destination_url else arg for arg in args]
        if args[0] == 'push' and self.before_push:
            self.before_push()
            self.before_push = None
        result = super().run(actual, cwd, token, **kwargs)
        if args[0] == 'clone':
            super().run(['remote', 'set-url', 'origin', source_url], args[-1], token)
        if args[0] == 'push' and self.lose_push_response:
            raise RepositoryLifecycleError('REPOSITORY_GIT_TIMEOUT', 'Fixture lost the push response')
        return result


class FixtureLifecycle(RepositoryLifecycle):
    def __init__(self, service, source, destination, git_runner, start=None):
        super().__init__(service, git_runner, start or (lambda action: action()))
        self.source, self.destination = source, destination
        self.http_calls = []
        self.actor_id = 42
        self.source_id = 11
        self.created = False
        self.destination_private = True
        self.default_branch = 'main'
        self.source_sha = git(source, 'rev-parse', 'main').decode().strip()
        self.source_tree = git(source, 'rev-parse', 'main^{tree}').decode().strip()
        self.lose_create_response = False
        self.revoke_after_absence = False

    def _api(self, path, token, method='GET', body=None):
        self.http_calls.append((method, path, body))
        if token != 'host-fixture-token':
            raise AssertionError('Unexpected credential')
        if path == '/user':
            return {'id': self.actor_id, 'login': 'actor'}
        if path == '/repos/source/project':
            return {'id': self.source_id, 'full_name': 'source/project', 'private': False, 'size': 10, 'default_branch': 'main'}
        if path.startswith('/repos/source/project/commits/'):
            ref = path.rsplit('/', 1)[-1]
            if len(ref) == 40 and all(char in '0123456789abcdef' for char in ref):
                return {'sha': ref, 'commit': {'tree': {'sha': git(self.source, 'rev-parse', ref + '^{tree}').decode().strip()}}}
            return {'sha': self.source_sha, 'commit': {'tree': {'sha': self.source_tree}}}
        if path == '/user/repos' and method == 'POST':
            self.created = True
            git(self.destination.parent, 'init', '--bare', str(self.destination))
            if self.lose_create_response:
                raise TimeoutError('Fixture response missing')
            return {'id': 99, 'full_name': 'actor/copied', 'private': True, 'visibility': 'private'}
        if path == '/repos/actor/copied':
            if not self.created:
                if self.revoke_after_absence:
                    self.service.authorized = lambda job: False
                raise RepositoryLifecycleError('REPOSITORY_HTTP_REJECTED', 'Fixture absent', 404)
            if method == 'PATCH':
                self.default_branch = body['default_branch']
            return {'id': 99, 'full_name': 'actor/copied', 'private': self.destination_private,
                    'visibility': 'private' if self.destination_private else 'public', 'default_branch': self.default_branch}
        if path.startswith('/repos/actor/copied/git/commits/'):
            sha = path.rsplit('/', 1)[-1]
            return {'sha': sha, 'tree': {'sha': git(self.destination, 'rev-parse', sha + '^{tree}').decode().strip()}}
        raise AssertionError('Unexpected fixture API operation: ' + method + ' ' + path)


@unittest.skipUnless(shutil.which('git'), 'Git CLI required for local object/ref tests')
class RepositoryLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        git(self.source, 'init', '-b', 'main')
        (self.source / 'readme.txt').write_text('first revision\n')
        git(self.source, 'add', 'readme.txt')
        git(self.source, 'commit', '-m', 'first')
        self.first = git(self.source, 'rev-parse', 'HEAD').decode().strip()
        git(self.source, 'branch', 'retained', self.first)
        git(self.source, 'tag', '-a', 'v1', '-m', 'annotated release', self.first)
        (self.source / 'readme.txt').write_text('second revision\n')
        git(self.source, 'commit', '-am', 'second')
        self.destination = self.root / 'destination.git'
        self.runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=str(self.root / 'jobs.db'), GATEWAY_DURABLE_JOBS=True,
                                       durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                       _durable_connect=lambda: sqlite3.connect(self.root / 'jobs.db'))
        self.store = EngineeringStore(self.runtime)
        self.backend = SimpleNamespace(available=lambda: True, token=lambda: 'host-fixture-token', stopping=threading.Event())
        self.service = SimpleNamespace(store=self.store, backend=self.backend, authorized=lambda job: job['principal'] == 'owner')
        self.runner = LocalFixtureGit(self.root / 'repository-workspaces', self.source, self.destination)
        self.lifecycle = FixtureLifecycle(self.service, self.source, self.destination, self.runner)
        self.payload = {'operation': 'import_repository', 'source_owner': 'source', 'source_repo': 'project', 'source_ref': 'main',
                        'expected_actor_id': 42, 'destination_owner': 'actor', 'destination_repo': 'copied',
                        'owner_type': 'user', 'visibility': 'private', 'copy_mode': 'history'}

    def tearDown(self):
        self.backend.stopping.set()
        self.temp.cleanup()

    def add_lfs_pointer(self, contents=b'fixture asset contents\n'):
        oid = hashlib.sha256(contents).hexdigest()
        # Keep the canonical fixture bytes independent of Windows text newline translation.
        (self.source / 'asset.bin').write_bytes(('version https://git-lfs.github.com/spec/v1\noid sha256:' + oid + '\nsize ' + str(len(contents)) + '\n').encode('ascii'))
        git(self.source, 'add', 'asset.bin')
        git(self.source, 'commit', '-m', 'LFS pointer')
        self.lifecycle.source_sha = git(self.source, 'rev-parse', 'HEAD').decode().strip()
        self.lifecycle.source_tree = git(self.source, 'rev-parse', 'HEAD^{tree}').decode().strip()
        self.runner.lfs_contents[oid] = contents
        return oid

    def test_import_preserves_real_commits_annotated_tags_all_heads_and_exact_tree(self):
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('VERIFIED', result['status'], result)
        self.assertTrue(result['history_preserved'])
        self.assertTrue(result['remote_state_verified'])
        self.assertEqual(result['source_tree_sha'], result['destination_tree_sha'])
        source = git(self.source, 'for-each-ref', '--format=%(objectname)%09%(refname)', 'refs/heads/', 'refs/tags/')
        destination = git(self.destination, 'for-each-ref', '--format=%(objectname)%09%(refname)', 'refs/heads/', 'refs/tags/')
        self.assertEqual(ref_map(source), ref_map(destination))
        self.assertEqual(self.first, git(self.destination, 'rev-parse', 'v1^{}').decode().strip())
        self.assertEqual(3, result['ref_count'])
        self.assertEqual(99, result['repository_id'])
        self.assertNotIn('host-fixture-token', json.dumps(result))
        self.assertNotIn(self.temp.name, json.dumps(result))
        stored = self.lifecycle._get('owner', result['operation_id'])
        self.assertNotIn('_deadline', stored)
        again = self.lifecycle.submit('owner', self.payload)
        self.assertEqual({key: value for key, value in result.items() if key != 'observed_at_ms'},
                         {key: value for key, value in again.items() if key != 'observed_at_ms'})
        self.assertGreaterEqual(again['observed_at_ms'], result['observed_at_ms'])
        expected_digest = hashlib.sha256(canonical(['owner', self.payload]).encode()).hexdigest()
        self.assertEqual(expected_digest, result['request_digest'])
        self.assertEqual('repoop_' + expected_digest[:32], result['operation_id'])
        self.assertEqual(1, len([call for call in self.lifecycle.http_calls if call[0] == 'POST']))
        self.assertEqual(1, len([call for call in self.runner.calls if call[0] == 'push']))

    def test_clone_opens_an_exact_isolated_checkout_and_preserves_the_https_remote(self):
        payload = {key: value for key, value in self.payload.items() if key in {'source_owner', 'source_repo', 'source_ref', 'expected_actor_id'}}
        result = self.lifecycle.submit('owner', dict(payload, operation='clone_repository'))
        self.assertEqual('VERIFIED', result['status'], result)
        checkout = self.root / 'repository-workspaces' / result['workspace_id'] / 'checkout'
        self.assertEqual('second revision\n', (checkout / 'readme.txt').read_text())
        self.assertEqual(result['source_commit_sha'], result['checked_out_sha'])
        self.assertEqual('https://github.com/source/project.git', result['remote_url'])
        self.assertEqual([], [call for call in self.lifecycle.http_calls if call[0] != 'GET'])

    def test_concurrent_destination_ref_is_never_overwritten_and_atomic_push_has_no_partial_tags(self):
        def competing_writer():
            git(self.destination, 'fetch', str(self.source), 'main')
            git(self.destination, 'update-ref', 'refs/heads/main', self.first)
            git(self.destination, 'update-ref', 'refs/heads/unrelated', self.first)
        self.runner.before_push = competing_writer
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('RECONCILE_REQUIRED', result['status'], result)
        self.assertFalse(result['remote_state_verified'])
        actual = ref_map(git(self.destination, 'for-each-ref', '--format=%(objectname)%09%(refname)', 'refs/heads/', 'refs/tags/'))
        self.assertEqual({'refs/heads/main': self.first, 'refs/heads/unrelated': self.first}, actual)
        push = next(call for call in self.runner.calls if call[0] == 'push')
        self.assertIn('--atomic', push)
        self.assertIn('--force-with-lease=refs/heads/main:', push)
        self.assertNotIn('--mirror', push)

    def test_unknown_push_is_read_back_without_repeating_creation_or_push(self):
        self.runner.lose_push_response = True
        submitted = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('RECONCILE_REQUIRED', submitted['status'])
        result = self.lifecycle.status('owner', submitted['operation_id'])
        self.assertEqual('VERIFIED', result['status'], result)
        self.assertTrue(result['history_preserved'])
        self.lifecycle.submit('owner', self.payload)
        self.assertEqual(1, len([call for call in self.runner.calls if call[0] == 'push']))
        self.assertEqual(1, len([call for call in self.lifecycle.http_calls if call[0] == 'POST']))

    def test_unknown_creation_never_adopts_a_repository_or_replays_the_post(self):
        self.lifecycle.lose_create_response = True
        first = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('RECONCILE_REQUIRED', first['status'])
        for _ in range(2):
            self.assertEqual('RECONCILE_REQUIRED', self.lifecycle.submit('owner', self.payload)['status'])
        self.assertNotIn('repository_id', first)
        self.assertEqual(1, len([call for call in self.lifecycle.http_calls if call[0] == 'POST']))
        self.assertFalse(any(call[0] == 'push' for call in self.runner.calls))

    def test_actor_mismatch_and_revoked_principal_block_remote_creation(self):
        self.lifecycle.actor_id = 77
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('BLOCKED', result['status'])
        self.assertEqual('REPOSITORY_ACTOR_CHANGED', result['error_code'])
        self.assertEqual([], self.runner.calls)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_authorization_is_rechecked_after_absence_read_before_create_dispatch(self):
        self.lifecycle.revoke_after_absence = True
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('BLOCKED', result['status'], result)
        self.assertEqual('REPOSITORY_PERMISSION_REVOKED', result['error_code'])
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_a_changed_source_is_rejected_before_creation(self):
        self.lifecycle.source_sha = self.first
        self.lifecycle.source_tree = git(self.source, 'rev-parse', self.first + '^{tree}').decode().strip()
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_SOURCE_CHANGED', result['error_code'])
        self.assertFalse(self.lifecycle.created)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_an_existing_destination_is_never_adopted_or_overwritten(self):
        self.lifecycle.created = True
        git(self.root, 'init', '--bare', str(self.destination))
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_DESTINATION_EXISTS', result['error_code'])
        self.assertEqual('BLOCKED', result['status'])
        self.assertFalse(any(call[0] != 'GET' for call in self.lifecycle.http_calls))
        self.assertFalse(any(call[0] == 'push' for call in self.runner.calls))

    def test_pull_only_source_commit_is_blocked_before_destination_creation(self):
        git(self.source, 'checkout', '--orphan', 'pull-only')
        (self.source / 'readme.txt').write_text('unexported pull commit\n')
        git(self.source, 'add', '.')
        git(self.source, 'commit', '-m', 'pull-only')
        self.lifecycle.source_sha = git(self.source, 'rev-parse', 'HEAD').decode().strip()
        self.lifecycle.source_tree = git(self.source, 'rev-parse', 'HEAD^{tree}').decode().strip()
        git(self.source, 'update-ref', 'refs/pull/1/head', self.lifecycle.source_sha)
        git(self.source, 'checkout', 'main')
        git(self.source, 'branch', '-D', 'pull-only')
        result = self.lifecycle.submit('owner', dict(self.payload, source_ref='refs/pull/1/head'))
        self.assertEqual('REPOSITORY_SOURCE_NOT_EXPORTED', result['error_code'], result)
        self.assertFalse(any(call[0] != 'GET' for call in self.lifecycle.http_calls))

    def test_source_lfs_without_tool_is_blocked_before_destination_creation(self):
        self.add_lfs_pointer()
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('BLOCKED', result['status'], result)
        self.assertEqual('REPOSITORY_LFS_REQUIRED', result['error_code'])
        self.assertFalse(self.lifecycle.created)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_crlf_lfs_pointers_still_require_tool_before_remote_creation(self):
        self.add_lfs_pointer()
        pointer = self.source / 'asset.bin'
        pointer.write_bytes(pointer.read_bytes().replace(b'\n', b'\r\n'))
        git(self.source, 'commit', '-am', 'noncanonical CRLF pointer')
        self.lifecycle.source_sha = git(self.source, 'rev-parse', 'HEAD').decode().strip()
        self.lifecycle.source_tree = git(self.source, 'rev-parse', 'HEAD^{tree}').decode().strip()
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_LFS_REQUIRED', result['error_code'], result)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_vulnerable_lfs_version_is_blocked_before_download_or_creation(self):
        self.add_lfs_pointer()
        self.runner.allow_lfs = True
        self.runner.lfs_version = b'git-lfs/3.7.0 (fixture)\n'
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_LFS_UPDATE_REQUIRED', result['error_code'], result)
        self.assertFalse(any(call[:2] == ['lfs', 'fetch'] for call in self.runner.calls))
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_import_checks_lfs_bytes_and_copies_only_exported_history_refs(self):
        self.add_lfs_pointer()
        self.runner.allow_lfs = True
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('VERIFIED', result['status'], result)
        self.assertEqual(1, result['lfs_objects'])
        refs = ['refs/heads/main', 'refs/heads/retained', 'refs/tags/v1']
        self.assertIn(['lfs', 'fetch', '--all', 'origin', *refs], self.runner.calls)
        self.assertIn(['lfs', 'push', '--all', 'https://github.com/actor/copied.git', *refs], self.runner.calls)
        self.assertTrue(self.lifecycle._get('owner', result['operation_id'])['lfs_uploaded'])

    def test_corrupted_lfs_object_is_blocked_before_remote_creation(self):
        oid = self.add_lfs_pointer()
        self.runner.allow_lfs = True
        self.runner.lfs_contents[oid] = b'x' * len(self.runner.lfs_contents[oid])
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_LFS_UNVERIFIED', result['error_code'], result)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_clone_hydrates_and_verifies_exact_lfs_bytes(self):
        oid = self.add_lfs_pointer()
        self.runner.allow_lfs = True
        payload = {key: value for key, value in self.payload.items() if key in {'source_owner', 'source_repo', 'source_ref', 'expected_actor_id'}}
        result = self.lifecycle.submit('owner', dict(payload, operation='clone_repository'))
        self.assertEqual('VERIFIED', result['status'], result)
        checkout = self.root / 'repository-workspaces' / result['workspace_id'] / 'checkout'
        self.assertEqual(self.runner.lfs_contents[oid], (checkout / 'asset.bin').read_bytes())
        (checkout / 'asset.bin').write_bytes(b'x' * len(self.runner.lfs_contents[oid]))
        changed = self.lifecycle.status('owner', result['operation_id'])
        self.assertEqual('BLOCKED', changed['status'], changed)
        self.assertFalse(changed['remote_state_verified'])

    def test_import_status_freshly_verifies_refs_and_recovers_without_mutation(self):
        result = self.lifecycle.submit('owner', self.payload)
        writes = [call for call in self.lifecycle.http_calls if call[0] != 'GET']
        expected = git(self.destination, 'rev-parse', 'refs/tags/v1').decode().strip()
        git(self.destination, 'update-ref', 'refs/tags/v1', self.first)
        changed = self.lifecycle.status('owner', result['operation_id'])
        self.assertEqual('BLOCKED', changed['status'], changed)
        self.assertEqual('REPOSITORY_IMPORT_UNVERIFIED', changed['error_code'])
        self.assertFalse(changed['remote_state_verified'])
        self.assertFalse(changed['history_preserved'])
        git(self.destination, 'update-ref', 'refs/tags/v1', expected)
        repaired = self.lifecycle.status('owner', result['operation_id'])
        self.assertEqual('VERIFIED', repaired['status'], repaired)
        self.assertTrue(repaired['history_preserved'])
        self.assertEqual(writes, [call for call in self.lifecycle.http_calls if call[0] != 'GET'])
        self.assertEqual(1, len([call for call in self.runner.calls if call[0] == 'push']))

    def test_status_checks_actor_source_identity_and_destination_visibility(self):
        result = self.lifecycle.submit('owner', self.payload)
        self.lifecycle.actor_id = 43
        self.assertEqual('REPOSITORY_ACTOR_CHANGED', self.lifecycle.status('owner', result['operation_id'])['error_code'])
        self.lifecycle.actor_id = 42
        self.lifecycle.source_id = 12
        self.assertEqual('REPOSITORY_SOURCE_CHANGED', self.lifecycle.status('owner', result['operation_id'])['error_code'])
        self.lifecycle.source_id = 11
        self.lifecycle.destination_private = False
        changed = self.lifecycle.status('owner', result['operation_id'])
        self.assertEqual('REPOSITORY_DESTINATION_CHANGED', changed['error_code'])
        self.assertFalse(changed['remote_state_verified'])
        self.assertEqual(1, len([call for call in self.lifecycle.http_calls if call[0] == 'POST']))

    def test_clone_status_rejects_tracked_changes_and_does_not_reclone(self):
        payload = {key: value for key, value in self.payload.items() if key in {'source_owner', 'source_repo', 'source_ref', 'expected_actor_id'}}
        result = self.lifecycle.submit('owner', dict(payload, operation='clone_repository'))
        checkout = self.root / 'repository-workspaces' / result['workspace_id'] / 'checkout'
        (checkout / 'readme.txt').write_text('changed after checkout\n')
        changed = self.lifecycle.status('owner', result['operation_id'])
        self.assertEqual('REPOSITORY_CLONE_CHANGED', changed['error_code'], changed)
        self.assertFalse(changed['remote_state_verified'])
        self.assertEqual(1, len([call for call in self.runner.calls if call[0] == 'clone']))

    def test_busy_verification_does_not_relabel_an_old_receipt_as_fresh_proof(self):
        result = self.lifecycle.submit('owner', self.payload)
        self.lifecycle.slots.acquire()
        self.lifecycle.slots.acquire()
        try:
            with self.assertRaises(RepositoryLifecycleError) as busy:
                self.lifecycle.status('owner', result['operation_id'])
            self.assertEqual('REPOSITORY_VERIFICATION_BUSY', busy.exception.code)
            self.assertEqual(503, busy.exception.status)
            self.assertEqual(result['observed_at_ms'], self.lifecycle._get('owner', result['operation_id'])['observed_at_ms'])
        finally:
            self.lifecycle.slots.release()
            self.lifecycle.slots.release()

    def test_pending_requests_are_bounded_and_an_interrupted_process_never_redispatches(self):
        starts = []
        self.lifecycle.start = starts.append
        first = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('PENDING', first['status'])
        self.assertEqual(first['operation_id'], self.lifecycle.submit('owner', self.payload)['operation_id'])
        self.assertEqual(1, len(starts))
        for index in range(7):
            self.lifecycle.submit('owner', dict(self.payload, destination_repo='other' + str(index)))
        with self.assertRaises(RepositoryLifecycleError) as full:
            self.lifecycle.submit('owner', dict(self.payload, destination_repo='too-many'))
        self.assertEqual('REPOSITORY_QUEUE_FULL', full.exception.code)
        reopened = FixtureLifecycle(self.service, self.source, self.destination, self.runner, start=lambda _: self.fail('Restart must not repeat work'))
        self.assertEqual('PENDING', reopened.submit('owner', self.payload)['status'])
        self.lifecycle._tx(lambda db: db.execute('UPDATE repository_lifecycle_instances SET heartbeat_ms=0'))
        self.assertEqual('BLOCKED', reopened.submit('owner', self.payload)['status'])
        self.assertEqual([], self.runner.calls)
        with self.assertRaises(RepositoryLifecycleError) as hidden:
            self.lifecycle.status('different-account', first['operation_id'])
        self.assertEqual(404, hidden.exception.status)

    def test_stale_worker_revision_cannot_resume_after_interruption_reconciliation(self):
        actions = []
        self.lifecycle.start = actions.append
        result = self.lifecycle.submit('owner', self.payload)
        self.lifecycle._tx(lambda db: db.execute('UPDATE repository_lifecycle_instances SET heartbeat_ms=0'))
        reopened = FixtureLifecycle(self.service, self.source, self.destination, self.runner)
        self.assertEqual('BLOCKED', reopened.status('owner', result['operation_id'])['status'])
        actions[0]()
        self.assertEqual([], self.runner.calls)
        self.assertFalse(any(call[0] != 'GET' for call in self.lifecycle.http_calls))
        self.assertEqual('BLOCKED', reopened.status('owner', result['operation_id'])['status'])

    def test_worker_start_failure_is_retained_without_a_stuck_active_owner(self):
        self.lifecycle.start = lambda _: (_ for _ in ()).throw(RuntimeError('cannot start'))
        result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('BLOCKED', result['status'])
        self.assertEqual('REPOSITORY_WORKER_UNAVAILABLE', result['error_code'])
        self.assertEqual(set(), self.lifecycle.active)
        self.assertEqual([], self.runner.calls)

    def test_workspace_disk_budget_blocks_before_clone_and_remote_mutation(self):
        with patch('v14.repository_lifecycle.MIN_FREE_BYTES', 2**62):
            result = self.lifecycle.submit('owner', self.payload)
        self.assertEqual('REPOSITORY_DISK_LIMIT', result['error_code'])
        self.assertFalse(self.lifecycle.created)
        self.assertFalse(any(call[0] == 'POST' for call in self.lifecycle.http_calls))

    def test_credentials_are_scoped_environment_only_and_untrusted_protocols_disabled(self):
        env = SafeGit(self.root).environment('secret-fixture-token', 'https://github.com/source/project.git')
        config = {env['GIT_CONFIG_KEY_' + str(index)]: env['GIT_CONFIG_VALUE_' + str(index)] for index in range(int(env['GIT_CONFIG_COUNT']))}
        self.assertEqual('never', config['protocol.allow'])
        self.assertEqual('always', config['protocol.https.allow'])
        self.assertEqual('false', config['http.followRedirects'])
        self.assertEqual('', config['credential.helper'])
        self.assertEqual(os.devnull, env['GIT_CONFIG_GLOBAL'])
        self.assertEqual('https://github.com/source/project.git/info/lfs', config['lfs.url'])
        self.assertFalse(any('secret-fixture-token' in value for value in env.values()))


class RepositoryRequestTests(unittest.TestCase):
    def test_lfs_checkout_requires_a_known_fixed_release(self):
        for version in (b'git-lfs/3.7.1 (linux)', b'git-lfs/3.8.0', b'git-lfs/4.0.0'):
            require_safe_lfs_version(version)
        for version in (b'git-lfs/3.7.0', b'git-lfs/3.6.1', b'unknown', b'git-lfs/3.7.1-rc1'):
            with self.subTest(version=version), self.assertRaises(RepositoryLifecycleError) as rejected:
                require_safe_lfs_version(version)
            self.assertEqual('REPOSITORY_LFS_UPDATE_REQUIRED', rejected.exception.code)

    def test_no_paths_protocols_commands_or_ambiguous_owner_type_can_be_injected(self):
        base = {'operation': 'clone_repository', 'source_owner': 'source', 'source_repo': 'project', 'source_ref': 'main', 'expected_actor_id': 42}
        for changed in ({'source_owner': '../owner'}, {'source_repo': 'file:///tmp/repo'}, {'source_ref': '--upload-pack=unsafe'},
                        {'source_ref': 'main~1'}, {'expected_actor_id': True}, {'command': 'anything'}, {'operation': 'create_repository'}):
            with self.subTest(changed=changed), self.assertRaises(RepositoryLifecycleError):
                validate_request(dict(base, **changed))

    def test_atomic_creation_does_not_use_force_or_mirror_or_deletion_refspecs(self):
        result = creation_push_arguments('https://github.com/actor/new.git', {'refs/heads/main': 'a' * 40, 'refs/tags/v1': 'b' * 40})
        self.assertIn('--atomic', result)
        self.assertIn('--force-with-lease=refs/tags/v1:', result)
        self.assertFalse(any(value == '--force' or value == '--mirror' or value.startswith(':') for value in result))


if __name__ == '__main__':
    unittest.main()
