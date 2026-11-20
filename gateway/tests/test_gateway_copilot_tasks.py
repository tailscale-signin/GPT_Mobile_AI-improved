"""Real journal, HTTP boundary and supervisor tests; GitHub HTTP is isolated."""
import copy
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import urlsplit

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from desktop.configuration import copilot_enabled, environment, save_copilot_enabled, validate
from v14.config import Config
from v14.copilot_tasks import API_VERSION, CopilotTaskError, CopilotTasks
from v14.engineering import EngineeringStore
from v14.engineering_backend import GitHubEngineeringBackend
from v14.engineering_routes import install_engineering_routes
from v14.github_api_governor import GitHubApiGovernor
from v14.identity import Admission
from v14.maintenance import pending_copilot_tasks
from v14.activity import ActivityTracker, desktop_snapshot


class GitHubFixture:
    def __init__(self):
        self.calls = []
        self.actor = 17
        self.base_sha = 'a' * 40
        self.token_value = 'host-fixture-secret'
        self.remote = dict(id='5fb80b5d-4142-4c0a-8c0a-f4470acd8866', state='queued',
                           creator={'id': 17}, owner={'id': 28}, repository={'id': 39})
        self.on_post = None
        self.created = threading.Event()
        self.github_governor = GitHubApiGovernor()
        self.read_response = GitHubEngineeringBackend.read_response

    def token(self):
        return self.token_value

    def request(self, method, url, **kwargs):
        path = urlsplit(url).path
        self.calls.append((method, path, copy.deepcopy(kwargs.get('json')), dict(kwargs['headers'])))
        assert url.startswith('https://api.github.com/')
        assert kwargs['allow_redirects'] is False
        assert kwargs['headers']['X-GitHub-Api-Version'] == API_VERSION
        assert kwargs['headers']['Authorization'] == 'Bearer ' + self.token_value
        if method == 'POST':
            if self.on_post:
                self.on_post()
            self.created.set()
            value, code = self.remote, 201
        elif path == '/user':
            value, code = {'id': self.actor, 'login': 'fixture-user'}, 200
        elif path == '/repos/owner/repo':
            value, code = {'id': 39, 'owner': {'id': 28}, 'full_name': 'owner/repo', 'permissions': {'push': True}}, 200
        elif '/git/ref/heads/' in path:
            value, code = {'object': {'sha': self.base_sha}}, 200
        elif '/agents/repos/owner/repo/tasks/' in path:
            value, code = self.remote, 200
        else:
            raise AssertionError('Unexpected GitHub endpoint: ' + path)
        response = requests.Response()
        response.status_code = code
        response._content = json.dumps(value).encode()
        response._content_consumed = True
        return response

    def posts(self):
        return [call for call in self.calls if call[0] == 'POST']


class CopilotTaskTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.now = 1000.
        self.runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
                                       durable_job_lock=threading.RLock(),
                                       _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'jobs.sqlite3'))
        self.store = EngineeringStore(self.runtime, lambda: self.now)
        self.backend = GitHubFixture()
        self.service = SimpleNamespace(store=self.store, backend=self.backend, stop=threading.Event(),
                                       authorized=lambda task: True, public_graph=None)
        self.enabled = True
        self.tasks = self.service.copilot = CopilotTasks(self.service, enabled=lambda: self.enabled, request=self.backend.request)
        self.instances = [self.tasks]
        self.payload = dict(request_id='fixture-request', repository='owner/repo', prompt='Repair the fixture task',
                            base_ref='main', expected_base_sha='a' * 40, expected_actor_id=17, execution_owner='GATEWAY',
                            copilot_enabled=True, conversation_copilot_enabled=True, create_pull_request=True)

    def tearDown(self):
        for instance in self.instances:
            instance.close()
        self.temp.cleanup()

    def restart(self):
        self.tasks.close()
        # A new service with the same durable database has no client state.
        self.store = EngineeringStore(self.runtime, lambda: self.now)
        self.service.store = self.store
        self.tasks = self.service.copilot = CopilotTasks(self.service, enabled=lambda: self.enabled, request=self.backend.request)
        self.instances.append(self.tasks)
        return self.tasks

    def client(self):
        app = FastAPI()
        app.add_middleware(Admission, config=Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(),
            trusted_users={'owner@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                           'other@example.test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                           'reader@example.test': {'principalId': 'reader', 'scopes': ['chat', 'jobs', 'tools']}}))
        install_engineering_routes(app, self.service)
        return TestClient(app, client=('127.0.0.1', 12000))

    def headers(self, who='owner'):
        return {'Tailscale-User-Login': who + '@example.test', 'X-Gateway-Installation-ID': self.store.conversations.gateway_id(),
                'X-Gateway-Principal-ID': 'tailnet:' + who}

    def wait_state(self, identifier, expected):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if self.tasks.status('tailnet:owner', identifier)['state'] == expected:
                return
            self.tasks.wake.wait(.01)
        self.fail('Supervisor did not checkpoint ' + expected)

    def test_catalog_projects_only_permitted_copilot_population(self):
        from v14.job_catalog import catalog
        owned = self.tasks.submit('owner', self.payload)['id']
        other = self.tasks.submit('second', dict(self.payload, request_id='separate-request'))['id']
        data = self.store.conversations
        ownership = SimpleNamespace(owned_job_ids=lambda ids, principal: set())
        owner = SimpleNamespace(id='owner', scopes={'engineering'}, local_admin=False)
        page = catalog(data, self.service, owner, ownership, include_copilot=True)
        self.assertEqual([owned], [row['job_id'] for row in page['items']])
        self.assertNotIn(other, str(page))
        self.assertEqual(1, sum(page['state_counts'].values()))
        self.assertNotIn('cancel', page['items'][0]['allowed_actions'])
        self.assertEqual([], catalog(data,self.service,owner,ownership)['items'])
        owner.scopes = set()
        self.assertEqual([], catalog(data,self.service,owner,ownership,include_copilot=True)['items'])

    def test_disabled_and_conversation_consent_never_make_remote_calls(self):
        self.enabled = False
        self.assertFalse(self.tasks.capabilities('owner')['enabled'])
        with self.assertRaises(CopilotTaskError) as failed:
            self.tasks.submit('owner', self.payload)
        self.assertEqual('COPILOT_DISABLED', failed.exception.code)
        self.enabled = True
        for flag in ('copilot_enabled', 'conversation_copilot_enabled'):
            with self.subTest(flag=flag), self.assertRaises(CopilotTaskError):
                self.tasks.submit('owner', dict(self.payload, **{flag: False}))
        with self.assertRaises(CopilotTaskError):
            self.tasks.submit('owner', dict(self.payload, execution_owner='ANDROID_DIRECT'))
        self.assertEqual([], self.backend.calls)
        self.assertEqual([], self.tasks.list('owner')['tasks'])

    def test_default_host_setting_is_off_and_desktop_cannot_inherit_enablement(self):
        with patch.dict(os.environ, {'GATEWAY_COPILOT_ENABLED': 'true'}):
            env = environment(self.temp.name, validate({}))
        self.assertEqual('false', env['GATEWAY_COPILOT_ENABLED'])
        self.assertFalse(copilot_enabled(self.temp.name))
        save_copilot_enabled(self.temp.name, True)
        self.assertEqual('true', environment(self.temp.name, validate({}))['GATEWAY_COPILOT_ENABLED'])
        (Path(self.temp.name) / 'data' / 'copilot-settings.json').write_text('{"enabled":"true"}')
        self.assertFalse(copilot_enabled(self.temp.name))
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(CopilotTasks(self.service).enabled())

    def test_concurrent_duplicate_submission_dispatches_once_and_payload_is_real_api(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            receipts = list(pool.map(lambda _: self.tasks.submit('owner', self.payload), range(6)))
        identifier = receipts[0]['id']
        self.assertEqual({identifier}, {receipt['id'] for receipt in receipts})
        self.backend.on_post = lambda: self.assertEqual('DISPATCHING', self.tasks.get(identifier)['state'])
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: self.tasks.tick(identifier), range(4)))
        replay = self.tasks.submit('owner', self.payload)
        self.assertEqual('QUEUED', replay['state'])
        self.assertEqual(1, len(self.backend.posts()))
        method, path, body, _ = self.backend.posts()[0]
        self.assertEqual('/agents/repos/owner/repo/tasks', path)
        self.assertEqual({'prompt': self.payload['prompt'], 'base_ref': 'main', 'create_pull_request': True}, body)
        self.assertNotIn('prompt', replay)
        self.assertNotIn('host-fixture-secret', json.dumps(replay))
        self.assertEqual('GATEWAY', replay['orchestration_owner'])
        self.assertEqual('GITHUB_CLOUD', replay['execution_owner'])
        self.assertEqual('PREFLIGHT_ONLY', replay['sha_verification'])
        with self.assertRaises(CopilotTaskError):
            self.tasks.submit('owner', dict(self.payload, prompt='Different task'))

    def test_closed_android_client_does_not_own_host_execution_or_polling(self):
        client = self.client()
        accepted = client.post('/gateway/engineering/copilot/tasks', json=self.payload, headers=self.headers())
        self.assertEqual(202, accepted.status_code, accepted.text)
        identifier = accepted.json()['gateway_job_id']
        client.close()
        self.tasks.start()
        self.assertTrue(self.backend.created.wait(3))
        self.wait_state(identifier, 'QUEUED')
        self.backend.remote['state'] = 'completed'
        self.now += 60
        self.tasks.wake.set()
        self.wait_state(identifier, 'COMPLETED')
        with self.client() as reconnect:
            result = reconnect.get('/gateway/engineering/copilot/tasks/' + identifier, headers=self.headers()).json()
        self.assertEqual('COMPLETED', result['state'])
        self.assertFalse(result['ci_verified'])
        self.assertFalse(result['merge_authorized'])
        self.assertEqual(1, len(self.backend.posts()))

    def test_restart_recovers_known_task_using_only_get(self):
        created = self.tasks.submit('owner', self.payload)
        self.tasks.tick(created['id'])
        self.restart()
        self.backend.remote['state'] = 'completed'
        self.tasks.tick(created['id'])
        result = self.tasks.status('owner', created['id'])
        self.assertEqual('COMPLETED', result['state'])
        self.assertEqual(1, len(self.backend.posts()))
        self.assertEqual(self.backend.remote['id'], result['task_id'])

    def test_crash_after_dispatch_fence_is_never_replayed_after_restart(self):
        created = self.tasks.submit('owner', self.payload)
        def crash():
            raise SystemExit('simulated process loss')
        self.backend.on_post = crash
        with self.assertRaises(SystemExit):
            self.tasks.tick(created['id'])
        self.assertEqual('DISPATCHING', self.tasks.get(created['id'])['state'])
        self.backend.on_post = None
        self.restart().start()
        self.assertEqual('RECONCILE_REQUIRED', self.tasks.status('owner', created['id'])['state'])
        self.tasks.tick(created['id'])
        self.tasks.submit('owner', self.payload)
        self.assertEqual(1, len(self.backend.posts()))

    def test_lost_post_response_stays_unknown_and_is_lookupable_when_disabled(self):
        def lost():
            raise requests.Timeout('private provider detail')
        self.backend.on_post = lost
        created = self.tasks.submit('owner', self.payload)
        self.tasks.tick(created['id'])
        self.enabled = False
        self.restart().tick(created['id'])
        result = self.tasks.list('owner', 'fixture-request', 'owner/repo')['tasks'][0]
        self.assertEqual('RECONCILE_REQUIRED', result['state'])
        self.assertEqual('COPILOT_SUBMISSION_UNCONFIRMED', result['error_code'])
        self.assertNotIn('private', json.dumps(result))
        self.assertEqual(created['id'], self.tasks.submit('owner', self.payload)['id'])
        self.assertEqual([], self.tasks.list('owner', 'fixture-request', 'another/repo')['tasks'])
        self.assertEqual(1, len(self.backend.posts()))

    def test_wrong_owner_and_changed_request_never_rebind_a_receipt(self):
        created = self.tasks.submit('owner', self.payload)
        with self.assertRaises(CopilotTaskError) as failed:
            self.tasks.status('other', created['id'])
        self.assertEqual(404, failed.exception.status)
        self.assertEqual([], self.tasks.list('other', 'fixture-request')['tasks'])
        with self.assertRaises(CopilotTaskError):
            self.tasks.submit('owner', dict(self.payload, expected_actor_id=55))
        self.assertEqual([], self.backend.calls)

    def test_actor_credential_ref_and_revoked_host_consent_block_before_post(self):
        for change, expected in [('actor', 'COPILOT_ACCOUNT_CHANGED'), ('credential', 'COPILOT_CREDENTIAL_CHANGED'),
                                 ('ref', 'COPILOT_SOURCE_CHANGED'), ('disabled', 'COPILOT_DISABLED')]:
            with self.subTest(change=change):
                self.backend.actor, self.backend.base_sha, self.backend.token_value, self.enabled = 17, 'a' * 40, 'host-fixture-secret', True
                created = self.tasks.submit('owner', dict(self.payload, request_id=change))
                if change == 'actor':
                    self.backend.actor = 88
                elif change == 'credential':
                    self.backend.token_value = 'other-secret'
                elif change == 'ref':
                    self.backend.base_sha = 'b' * 40
                else:
                    self.enabled = False
                self.tasks.tick(created['id'])
                result = self.tasks.status('owner', created['id'])
                self.assertEqual('REJECTED', result['state'])
                self.assertEqual(expected, result['error_code'])
        self.assertEqual([], self.backend.posts())

    def test_head_branch_sha_is_checked_and_never_sent_as_an_invented_precondition(self):
        created = self.tasks.submit('owner', dict(self.payload, head_ref='feature/work', expected_head_sha='a' * 40))
        self.tasks.tick(created['id'])
        self.assertIn(('GET', '/repos/owner/repo/git/ref/heads/feature%2Fwork'), [(call[0], call[1]) for call in self.backend.calls])
        body = self.backend.posts()[0][2]
        self.assertEqual('feature/work', body['head_ref'])
        self.assertNotIn('expected_head_sha', body)
        self.assertFalse(self.tasks.status('owner', created['id'])['head_sha_verified'])

    def test_unknown_remote_state_and_waiting_states_are_not_completed(self):
        created = self.tasks.submit('owner', self.payload)
        self.backend.remote['state'] = 'new-future-state'
        self.tasks.tick(created['id'])
        self.assertEqual('UNKNOWN', self.tasks.status('owner', created['id'])['state'])
        for state in ('idle', 'waiting_for_user', 'in_progress'):
            self.backend.remote['state'] = state
            self.tasks.tick(created['id'])
            self.assertEqual(state.upper(), self.tasks.status('owner', created['id'])['state'])
        self.assertEqual(1, len(self.backend.posts()))

    def test_remote_wrong_repository_or_actor_is_unconfirmed_not_retried(self):
        created = self.tasks.submit('owner', self.payload)
        self.backend.remote['repository'] = {'id': 900}
        self.tasks.tick(created['id'])
        self.assertEqual('RECONCILE_REQUIRED', self.tasks.status('owner', created['id'])['state'])
        self.tasks.tick(created['id'])
        self.assertEqual(1, len(self.backend.posts()))

    def test_disabled_read_routes_reconnect_by_request_identity_and_reject_other_owner(self):
        with self.client() as client:
            created = client.post('/v1/gateway/engineering/copilot/tasks', headers=self.headers(), json=self.payload)
            self.assertEqual(202, created.status_code, created.text)
            self.enabled = False
            restored = client.get('/gateway/engineering/copilot/tasks', params={'request_id': self.payload['request_id'], 'repository': 'owner/repo'}, headers=self.headers())
            self.assertEqual(created.json()['id'], restored.json()['tasks'][0]['id'])
            denied = client.get('/gateway/engineering/copilot/tasks/' + created.json()['id'], headers=self.headers('other'))
            self.assertEqual(404, denied.status_code)
            reader = client.get('/gateway/engineering/copilot/tasks', headers=self.headers('reader'))
            self.assertEqual(403, reader.status_code)
        self.assertEqual([], self.backend.posts())

    def test_mutation_identity_and_explicit_owner_are_required_at_http_boundary(self):
        with self.client() as client:
            no_identity = client.post('/gateway/engineering/copilot/tasks', headers={'Tailscale-User-Login': 'owner@example.test'}, json=self.payload)
            self.assertEqual(400, no_identity.status_code)
            changed_identity = client.post('/gateway/engineering/copilot/tasks', headers=dict(self.headers(), **{'X-Gateway-Principal-ID': 'other'}), json=self.payload)
            self.assertEqual(409, changed_identity.status_code)
            wrong_owner = client.post('/gateway/engineering/copilot/tasks', headers=self.headers(), json=dict(self.payload, execution_owner='ANDROID_DIRECT'))
            self.assertEqual(400, wrong_owner.status_code)
            capability = client.get('/gateway/engineering/copilot/capabilities', headers=self.headers()).json()
            self.assertEqual(17, capability['actor_id'])
            self.assertEqual('tailnet:owner', capability['principal_id'])
            self.assertTrue(capability['configured'])
        self.assertEqual([], self.backend.posts())

    def test_standalone_dashboard_and_idle_check_use_safe_owned_task_metadata(self):
        created = self.tasks.submit('owner', self.payload)
        other = self.tasks.submit('other', dict(self.payload, request_id='other-request', prompt='Other private prompt'))
        self.store.tx(lambda db: db.execute('CREATE TABLE gateway_jobs(job_id,public_json,status,created_at,updated_at)'))
        self.assertEqual(2, self.store.tx(pending_copilot_tasks))
        snapshot = desktop_snapshot(self.runtime, SimpleNamespace(snapshot=lambda: {}), ActivityTracker(),
                                    principal='owner', ownership=SimpleNamespace(owned_job_ids=lambda ids, principal: set()))
        self.assertIsNone(snapshot['historyError'])
        self.assertEqual([created['id']], [task['id'] for task in snapshot['engineering']])
        self.assertEqual(1, snapshot['openJobs'])
        self.assertNotIn(other['id'], json.dumps(snapshot))
        self.assertNotIn(self.payload['prompt'], json.dumps(snapshot))
        self.assertNotIn(self.backend.token_value, json.dumps(snapshot))
        self.tasks.tick(created['id'])
        self.assertEqual(2, self.store.tx(pending_copilot_tasks))
        self.backend.remote['state'] = 'completed'
        self.tasks.tick(created['id'])
        self.assertEqual(1, self.store.tx(pending_copilot_tasks))

    def test_repository_and_request_filter_precede_bounded_page_after_large_other_history(self):
        oldest = self.tasks.submit('owner', self.payload)
        for index in range(101):
            self.now += 1
            self.tasks.submit('owner', dict(self.payload, request_id='other-' + str(index), repository='owner/other-repo'))
        self.now += 1
        middle = self.tasks.submit('owner', dict(self.payload, request_id='selected-middle'))
        self.now += 1
        newest = self.tasks.submit('owner', dict(self.payload, request_id='selected-newest'))
        first = self.tasks.list('owner', repository='OWNER/REPO', page=1, per_page=2)
        self.assertEqual([newest['id'], middle['id']], [task['id'] for task in first['tasks']])
        self.assertEqual((1, 2, True, False), (first['page'], first['per_page'], first['has_more'], first['complete']))
        second = self.tasks.list('owner', repository='owner/repo', page=2, per_page=2)
        self.assertEqual([oldest['id']], [task['id'] for task in second['tasks']])
        self.assertEqual((2, 2, False, True), (second['page'], second['per_page'], second['has_more'], second['complete']))
        recovered = self.tasks.list('owner', request_id='fixture-request', repository='owner/repo', per_page=1)
        self.assertEqual([oldest['id']], [task['id'] for task in recovered['tasks']])
        self.assertFalse(recovered['has_more'])
        self.assertEqual([], self.backend.calls)

    def test_polling_does_not_shuffle_task_pages_and_unrecognized_state_retains_remote_id(self):
        tasks = [self.tasks.submit('tailnet:owner', dict(self.payload, request_id=str(n))) for n in range(3)]
        first = self.tasks.list('tailnet:owner', page=1, per_page=2)
        self.backend.remote = dict(self.backend.remote, state={})
        self.tasks.tick(tasks[0]['id'])
        observed = self.tasks.get(tasks[0]['id'])
        self.assertEqual('UNKNOWN', observed['state'])
        self.assertEqual(self.backend.remote['id'], observed['upstream_task_id'])
        self.tasks.tick(tasks[0]['id'])
        self.assertEqual(1, len(self.backend.posts()))
        last = self.tasks.list('tailnet:owner', page=2, per_page=2)
        ids = [t['id'] for t in first['tasks'] + last['tasks']]
        self.assertEqual([t['id'] for t in reversed(tasks)], ids)

    def test_http_task_page_is_bounded_and_echoes_pagination_without_remote_calls(self):
        for index in range(3):
            self.tasks.submit('tailnet:owner', dict(self.payload, request_id='page-' + str(index)))
        with self.client() as client:
            response = client.get('/gateway/engineering/copilot/tasks', headers=self.headers(),
                                  params={'repository': 'owner/repo', 'page': 1, 'per_page': 2})
            self.assertEqual(200, response.status_code)
            self.assertEqual(2, len(response.json()['tasks']))
            self.assertTrue(response.json()['has_more'])
            for parameters in ({'page': 0}, {'page': 1001}, {'per_page': 0}, {'per_page': 101}):
                with self.subTest(parameters=parameters):
                    rejected = client.get('/gateway/engineering/copilot/tasks', headers=self.headers(), params=parameters)
                    self.assertEqual(400, rejected.status_code)
                    self.assertEqual('COPILOT_PAGE_INVALID', rejected.json()['error']['code'])
        self.assertEqual([], self.backend.calls)


if __name__ == '__main__':
    unittest.main()
