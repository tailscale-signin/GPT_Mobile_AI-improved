"""Real journal and local-supervisor queue controls; no model or GitHub calls."""
from copy import deepcopy
import asyncio
import io
import json
import sqlite3
import threading
from types import SimpleNamespace
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

from fastapi import FastAPI
from fastapi.testclient import TestClient

import test_gateway_v14_engineering as fixtures
import test_gateway_v14_jobs as journal_fixtures
from v14.activity import ActivityTracker, desktop_snapshot
from v14.config import Config
from v14.control_settings import install_control_settings
from v14.identity import Admission
from v14.service import ServiceState
from v14.retained_work_control import RetainedWorkControl, cancellation_outcome
from v14.conversations import admission_context, QueueFull


class EngineeringQueueControlTests(unittest.TestCase):
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown

    def paused_full_queue(self):
        first = self.store.create('owner', dict(self.request, request_id='first'))
        claimed, lease, _ = self.store.claim(first['id'])
        self.store.complete(claimed, lease, 'NEEDS_INPUT', {})
        for index in range(31):
            self.store.create('owner', dict(self.request, request_id='pending-' + str(index)))
        return self.store.get(first['id'])

    def active_count(self):
        return self.store.tx(lambda db: db.execute(
            "SELECT COUNT(*) FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED','FAILED')").fetchone()[0])

    def app(self):
        self.runtime.gateway_debug = SimpleNamespace(enabled=False)
        app = FastAPI()
        config = Config(mode='open_network', host='0.0.0.0', allowed_hosts=('testserver',),
                        allowed_origins=(), management_token='fixture-supervisor')
        app.add_middleware(Admission, config=config)
        install_control_settings(app, self.runtime, ServiceState(1), self.service, config)
        return app

    def command(self, job, command_id='stop-original'):
        return {'command_id': command_id, 'gateway_id': self.store.conversations.gateway_id(),
                'principal_id': job['principal'], 'job_kind': 'engineering',
                'revision': job['revision'], 'generation': None}

    def test_full_queue_allows_exact_paused_continuation_without_increasing_occupancy(self):
        first = self.paused_full_queue()
        snapshot = self.store.conversations.snapshot('owner', first['conversation_id'])
        continued = self.store.create('owner', dict(self.request, request_id='continued',
            conversation_id=first['conversation_id'], expected_revision=snapshot['revision']))
        self.assertEqual(32, self.active_count())
        self.assertEqual('CANCELLED', self.store.get(first['id'])['state'])
        self.assertEqual(first['id'], continued['continuation_of'])
        self.assertEqual(first['conversation_id'], continued['conversation_id'])
        self.assertEqual([], self.backend.calls)
        with self.assertRaisesRegex(ValueError, 'capacity'):
            self.store.create('owner', dict(self.request, request_id='unrelated'))
        self.assertIsNone(self.store.conversations.command('owner', 'unrelated'))
        self.assertEqual(32, self.active_count())

    def test_local_supervisor_can_cancel_foreign_paused_job_and_release_its_slot(self):
        first = self.paused_full_queue()
        target = '/gateway/admin/retained-work/' + first['id']
        headers = {'X-Gateway-Management': 'fixture-supervisor'}
        with TestClient(self.app(), client=('127.0.0.1', 9021)) as client:
            response = client.post(target + '/cancel', headers=headers, json=self.command(first))
            self.assertEqual(200, response.status_code, response.text)
            receipt = response.json()
            self.assertEqual('confirmed', receipt['cancellation_outcome'])
            self.assertEqual('local:manager', receipt['receipt']['actor'])
            self.assertEqual('owner', receipt['principal_id'])
            repeated = client.post(target + '/cancel', headers=headers, json=self.command(first))
            self.assertEqual(200, repeated.status_code, repeated.text)
            self.assertEqual(receipt['receipt'], repeated.json()['receipt'])
        stopped = self.store.get(first['id'])
        self.assertEqual('owner', stopped['principal'])
        self.assertEqual(first['request'], stopped['request'])
        self.assertEqual(first['revision'] + 1, stopped['revision'])
        self.assertEqual('CANCELLED', stopped['state'])
        self.assertEqual(31, self.active_count())
        self.store.create('owner', dict(self.request, request_id='after-stop'))
        self.assertEqual(32, self.active_count())
        for _ in range(3):
            self.store.recover()
            self.service.tick(first['id'])
        self.assertEqual([], self.backend.calls)

    def test_failed_jobs_do_not_fill_the_observed_open_queue(self):
        first = self.store.create('owner', self.request)
        claimed, lease, _ = self.store.claim(first['id'])
        self.store.complete(claimed, lease, 'FAILED', {})
        self.store.tx(lambda db: db.execute('CREATE TABLE gateway_jobs(job_id,public_json,status,created_at,updated_at)'))
        self.store.tx(lambda db: db.execute('INSERT INTO gateway_jobs VALUES(?,?,?,?,?)', ('stopped-old-generation', '{}', 'interrupted', 1, 2)))
        observed = desktop_snapshot(self.runtime, ServiceState(1), ActivityTracker(), include_inventory=False)
        self.assertIsNone(observed['historyError'])
        self.assertEqual(0, self.active_count())
        self.assertEqual(0, observed['openJobs'])

    def test_supervisor_cancel_rejects_remote_forwarded_missing_secret_and_proxy(self):
        job = self.store.create('owner', self.request)
        path = '/gateway/admin/retained-work/' + job['id'] + '/cancel'
        with TestClient(self.app(), client=('192.0.2.1', 9010)) as remote, TestClient(self.app(), client=('127.0.0.1', 9011)) as local:
            for client, headers in [(remote, {'X-Gateway-Management': 'fixture-supervisor'}), (local, {}),
                    (local, {'X-Gateway-Management': 'wrong'}),
                    (local, {'X-Gateway-Management': 'fixture-supervisor', 'Origin': 'https://untrusted.example'}),
                    (local, {'X-Gateway-Management': 'fixture-supervisor', 'X-Forwarded-For': '127.0.0.1'}),
                    (local, {'X-Gateway-Management': 'fixture-supervisor', 'X-Gateway-Control-Principal': 'paired:old-owner'})]:
                self.assertIn(client.post(path, headers=headers, json=self.command(job)).status_code, (401, 403))
                self.assertIn(client.get('/gateway/admin/retained-work', headers=headers).status_code, (401, 403))
        self.assertEqual(job, self.store.get(job['id']))
        self.assertIsNone(RetainedWorkControl(self.runtime, self.service)._receipt('stop-original'))

    def test_stop_fences_original_owner_gateway_revision_kind_and_command(self):
        job = self.store.create('owner', self.request)
        target = '/gateway/admin/retained-work/' + job['id']
        headers = {'X-Gateway-Management': 'fixture-supervisor'}
        with TestClient(self.app(), client=('127.0.0.1', 9011)) as client:
            for index, change in enumerate(({'principal_id': 'another-owner'}, {'gateway_id': 'another-gateway'}, {'revision': 5},
                                           {'job_kind': 'generation', 'generation': 'another-generation'})):
                response = client.post(target + '/cancel', headers=headers, json=dict(self.command(job, 'refused-' + str(index)), **change))
                self.assertEqual(409, response.status_code, response.text)
                self.assertEqual(job, self.store.get(job['id']))
            response = client.post(target + '/cancel', headers=headers, json=self.command(job))
            self.assertEqual(200, response.status_code, response.text)
            changed = dict(self.command(job), revision=1)
            self.assertEqual(409, client.post(target + '/cancel', headers=headers, json=changed).status_code)
            original = client.get(target + '/cancel/stop-original', headers=headers).json()
            self.assertEqual(self.command(job)['revision'], original['receipt']['target']['revision'])
            self.assertEqual('owner', original['receipt']['target']['principal_id'])

    def test_stale_admin_target_has_a_durable_rejection_and_allows_a_fresh_reviewed_stop(self):
        job = self.store.create('owner', self.request)
        original = self.command(job)
        claimed, lease, _ = self.store.claim(job['id'])
        self.store.complete(claimed, lease, 'NEEDS_INPUT', {})
        path = '/gateway/admin/retained-work/' + job['id']
        headers = {'X-Gateway-Management': 'fixture-supervisor'}
        with TestClient(self.app(), client=('127.0.0.1', 9011)) as client:
            rejected = client.post(path + '/cancel', headers=headers, json=original)
            self.assertEqual(409, rejected.status_code, rejected.text)
            retained = client.get(path + '/cancel/stop-original', headers=headers).json()
            self.assertEqual('not_dispatched', retained['dispatch_state'])
            self.assertEqual('NOT_DISPATCHED', retained['receipt']['status'])
            self.assertEqual({key: value for key, value in original.items() if key != 'command_id'}, retained['receipt']['target'])
            repeated = client.post(path + '/cancel', headers=headers, json=original)
            self.assertEqual(200, repeated.status_code, repeated.text)
            self.assertEqual(retained['receipt'], repeated.json()['receipt'])
            self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
            fresh = client.get(path, headers=headers).json()['control_target']
            self.assertEqual(409, client.post(path + '/cancel', headers=headers, json=dict(fresh, command_id='stop-original')).status_code)
            stopped = client.post(path + '/cancel', headers=headers, json=dict(fresh, command_id='new-confirmed-stop'))
            self.assertEqual(200, stopped.status_code, stopped.text)
            self.assertEqual('confirmed', stopped.json()['cancellation_outcome'])
            self.assertEqual(retained['receipt'], client.get(path + '/cancel/stop-original', headers=headers).json()['receipt'])

    def test_transactional_stop_precheck_race_records_only_definite_rejection(self):
        job = self.store.create('owner', self.request)
        original_control = self.store.control
        def progress_then_stop(*args, **kwargs):
            claimed, lease, _ = self.store.claim(job['id'])
            self.store.complete(claimed, lease, 'NEEDS_INPUT', {})
            return original_control(*args, **kwargs)
        control = RetainedWorkControl(self.runtime, self.service)
        with patch.object(self.store, 'control', side_effect=progress_then_stop) as stop:
            with self.assertRaisesRegex(ValueError, 'changed before Stop'):
                control.cancel(job['id'], self.command(job))
            self.assertEqual(1, stop.call_count)
        retained = control.receipt(job['id'], 'stop-original')
        self.assertEqual('NOT_DISPATCHED', retained['receipt']['status'])
        self.assertEqual('not_dispatched', retained['dispatch_state'])
        self.assertEqual('NEEDS_INPUT', retained['job']['state'])
        with patch.object(self.store, 'control', wraps=self.store.control) as stop:
            self.assertEqual(retained['receipt'], control.cancel(job['id'], self.command(job))['receipt'])
            stop.assert_not_called()

    def test_leased_stop_stays_pending_and_retains_unknown_physical_operation(self):
        job = self.store.create('owner', self.request)
        claimed, _, _ = self.store.claim(job['id'])
        intent = self.service.artifacts.put_json(claimed, {'method': 'POST', 'path': '/repos/owner/repo/git/commits', 'body': {'message': 'original'}}, 'operation-intent')
        operation = dict(operation_id='operation_original', job_id=job['id'], revision=claimed['revision'],
                         binding_id=claimed['request']['execution_binding_id'], lease_id=claimed['_execution_lease'],
                         owner_epoch=claimed['_owner_epoch'], method='POST', path='/repos/owner/repo/git/commits',
                         request_ref=intent, state='DISPATCHED', dispatched=True)
        self.service.operations.save(claimed, operation)
        self.service.operations.failed(claimed, operation, TimeoutError('Synthetic lost acknowledgement'))
        original = deepcopy(self.service.operations.page(claimed)['operations'])
        control = RetainedWorkControl(self.runtime, self.service)
        result = control.cancel(job['id'], self.command(job))
        self.assertEqual('pending', result['cancellation_outcome'])
        self.assertEqual('CANCELLING', result['job']['state'])
        self.assertFalse(result['removal_available'])
        self.assertTrue(result['reconciliation_required'])
        self.assertEqual('local_execution', result['cancellation_scope'])
        self.assertEqual(original, self.service.operations.page(claimed)['operations'])
        self.store.confirm_cancellation(job['id'], claimed['_owner_epoch'] + 1)
        self.assertEqual('pending', control.receipt(job['id'], 'stop-original')['cancellation_outcome'])
        self.store.confirm_cancellation(job['id'], claimed['_owner_epoch'])
        final = control.receipt(job['id'], 'stop-original')
        self.assertEqual('confirmed', final['cancellation_outcome'])
        self.assertEqual('pending', final['receipt']['cancellation_outcome'])
        self.assertEqual(result['receipt'], final['receipt'])
        self.assertTrue(final['reconciliation_required'])
        self.assertEqual(original, self.service.operations.page(claimed)['operations'])
        for _ in range(3):
            self.store.recover()
            self.service.tick(job['id'])
        self.assertEqual([], self.backend.calls)
        self.assertEqual(original, self.service.operations.page(claimed)['operations'])

    def test_lost_final_admin_receipt_reads_observed_stop_without_reissuing_it(self):
        job = self.store.create('owner', self.request)
        control = RetainedWorkControl(self.runtime, self.service)
        self.store.tx(control.schema)
        self.store.tx(lambda db: db.execute("CREATE TRIGGER reject_admin_result BEFORE UPDATE ON gateway_job_admin_commands BEGIN SELECT RAISE(ABORT,'synthetic final receipt failure'); END"))
        target = '/gateway/admin/retained-work/' + job['id']
        headers = {'X-Gateway-Management': 'fixture-supervisor'}
        with TestClient(self.app(), client=('127.0.0.1', 9011)) as client, patch.object(self.store, 'control', wraps=self.store.control) as stop:
            self.assertEqual(503, client.post(target + '/cancel', headers=headers, json=self.command(job)).status_code)
            self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])
            receipt = client.get(target + '/cancel/stop-original', headers=headers).json()
            self.assertEqual('INTENT_RECORDED', receipt['receipt']['status'])
            self.assertEqual('confirmed', receipt['cancellation_outcome'])
            repeated = client.post(target + '/cancel', headers=headers, json=self.command(job))
            self.assertEqual(200, repeated.status_code, repeated.text)
            self.assertEqual(receipt['receipt'], repeated.json()['receipt'])
            self.assertEqual(1, stop.call_count)
        self.assertEqual([], self.backend.calls)

    def test_crash_after_admin_intent_does_not_retry_or_claim_stopped(self):
        job = self.store.create('owner', self.request)
        control = RetainedWorkControl(self.runtime, self.service)
        with patch.object(self.store, 'control', side_effect=RuntimeError('synthetic process boundary')) as stop:
            with self.assertRaisesRegex(RuntimeError, 'process boundary'):
                control.cancel(job['id'], self.command(job))
            recovered = RetainedWorkControl(self.runtime, self.service)
            self.assertEqual('unconfirmed', recovered.receipt(job['id'], 'stop-original')['cancellation_outcome'])
            repeated = recovered.cancel(job['id'], self.command(job))
            self.assertEqual('INTENT_RECORDED', repeated['receipt']['status'])
            self.assertEqual('PLAN', repeated['job']['state'])
            self.assertEqual(1, stop.call_count)
        # A separate explicit reviewed action may stop the still-unchanged job.
        self.assertEqual('confirmed', recovered.cancel(job['id'], self.command(job, 'explicit-new-stop'))['cancellation_outcome'])

    def test_admin_intent_must_commit_before_stop_signal(self):
        job = self.store.create('owner', self.request)
        control = RetainedWorkControl(self.runtime, self.service)
        self.store.tx(control.schema)
        self.store.tx(lambda db: db.execute("CREATE TRIGGER reject_admin_intent BEFORE INSERT ON gateway_job_admin_commands BEGIN SELECT RAISE(ABORT,'synthetic intent failure'); END"))
        with patch.object(self.store, 'control', wraps=self.store.control) as stop:
            with self.assertRaises(sqlite3.IntegrityError):
                control.cancel(job['id'], self.command(job))
            stop.assert_not_called()
        self.assertEqual(job, self.store.get(job['id']))
        self.assertIsNone(control._receipt('stop-original'))

    def test_host_inventory_pages_reach_old_active_work_and_keep_complete_counts(self):
        first = self.store.create('owner', dict(self.request, request_id='old-active'))
        claimed, lease, _ = self.store.claim(first['id'])
        self.store.complete(claimed, lease, 'NEEDS_INPUT', {})
        for index in range(105):
            job = self.store.create('owner', dict(self.request, request_id='old-done-' + str(index)))
            self.store.control(job['id'], 'owner', 'cancel', job['revision'])
        control = RetainedWorkControl(self.runtime, self.service)
        first_page = control.listing()
        self.assertEqual(first['id'], first_page['items'][0]['job_id'])
        self.assertEqual('owner', first_page['items'][0]['principal_id'])
        self.assertTrue(first_page['counts_complete'])
        self.assertEqual(1, first_page['state_counts']['needs_attention'])
        self.assertEqual(105, first_page['state_counts']['cancelled'])
        self.assertEqual(100, len(first_page['items']))
        self.assertTrue(first_page['has_more'])
        second_page = control.listing(first_page['next_cursor'])
        self.assertEqual(6, len(second_page['items']))
        self.assertFalse(second_page['has_more'])
        self.assertEqual(106, len({item['job_id'] for item in first_page['items'] + second_page['items']}))
        self.assertNotIn('Repair the build', json.dumps(first_page))

    def test_uncertain_copilot_task_cannot_be_removed_as_confirmed_terminal(self):
        for state in ('RECONCILE_REQUIRED', 'TIMED_OUT', 'DISPATCHING', 'UNKNOWN'):
            self.assertEqual('unconfirmed', cancellation_outcome('copilot', {'state': state, 'progress': {'remote_task_confirmed': True}}))
        self.assertEqual('already_terminal', cancellation_outcome('copilot', {'state': 'COMPLETED', 'progress': {'remote_task_confirmed': True}}))
        self.assertEqual('unconfirmed', cancellation_outcome('copilot', {'state': 'COMPLETED', 'progress': {'remote_task_confirmed': False}}))

    def test_native_bridge_manager_http_stop_and_receipt_preserve_original_owner(self):
        from desktop import native_bridge
        from desktop.configuration import DEFAULTS
        from desktop.manager import retained_work_request
        job = self.store.create('owner', self.request)
        identity = dict(gateway_id=self.store.conversations.gateway_id(), principal_id='local:single-user')
        connected = Mock()
        connected.identity.return_value = identity
        with TestClient(self.app(), client=('127.0.0.1', 9011)) as client:
            class Http:
                calls = []
                def get(self, url, **kwargs):
                    self.calls.append(('GET', url, kwargs))
                    return client.get(urlsplit(url).path, headers=kwargs['headers'], params=kwargs.get('params'))
                def post(self, url, **kwargs):
                    self.calls.append(('POST', url, kwargs))
                    return client.post(urlsplit(url).path, headers=kwargs['headers'], json=kwargs['json'])
            http = Http()
            host = SimpleNamespace(http=http, cfg=DEFAULTS, token='fixture-supervisor')
            def ipc(root, action, payload):
                return {'result': retained_work_request(host, action, payload)}
            with patch.object(native_bridge, 'Client', return_value=connected), patch.object(native_bridge, 'request', side_effect=ipc):
                root = Path(self.temp.name)
                args = dict(identity=identity, job_id=job['id'])
                page = native_bridge.dispatch(root, 'job-owner-list', dict(identity=identity), io.StringIO())
                self.assertEqual('owner', page['items'][0]['principal_id'])
                original = native_bridge.dispatch(root, 'job-owner-read', args, io.StringIO())
                stopped = native_bridge.dispatch(root, 'job-owner-cancel', dict(args, command_id='native-stop', target=original['control_target']), io.StringIO())
                self.assertEqual('confirmed', stopped['cancellation_outcome'])
                reread = native_bridge.dispatch(root, 'job-owner-cancel-receipt', dict(args, command_id='native-stop'), io.StringIO())
                self.assertEqual(stopped['receipt'], reread['receipt'])
                self.assertEqual('owner', reread['principal_id'])
            posts = [call for call in http.calls if call[0] == 'POST']
            self.assertEqual(1, len(posts))
            self.assertEqual({'X-Gateway-Management': 'fixture-supervisor'}, posts[0][2]['headers'])
            self.assertEqual('owner', posts[0][2]['json']['principal_id'])
            self.assertNotIn('fixture-supervisor', json.dumps(stopped))
        self.assertEqual('owner', self.store.get(job['id'])['principal'])

    def test_native_stop_fails_closed_for_changed_installation_or_mismatched_receipt(self):
        from desktop import native_bridge
        from desktop.conversation_client import ClientError
        job = self.store.create('owner', self.request)
        control = RetainedWorkControl(self.runtime, self.service)
        target = control.read(job['id'])['control_target']
        identity = dict(gateway_id=target['gateway_id'], principal_id='local:single-user')
        args = dict(identity=identity, job_id=job['id'], command_id='native-stop', target=target)
        changed = Mock()
        changed.identity.return_value = dict(identity, gateway_id='another-pc')
        with patch.object(native_bridge, 'Client', return_value=changed), patch.object(native_bridge, 'request') as ipc:
            with self.assertRaises(ClientError):
                native_bridge.retained_work(Path(self.temp.name), 'job-owner-cancel', args)
            ipc.assert_not_called()
        result = control.cancel(job['id'], dict(target, command_id='native-stop'))
        changed.identity.return_value = identity
        for wrong in (dict(result, gateway_id='another-pc'), dict(result, command_id='another-command'),
                      dict(result, receipt=dict(result['receipt'], target=dict(target, principal_id='another-owner')))):
            with patch.object(native_bridge, 'Client', return_value=changed), patch.object(native_bridge, 'request', return_value={'result': wrong}) as ipc:
                with self.assertRaises(ClientError):
                    native_bridge.retained_work(Path(self.temp.name), 'job-owner-cancel', args)
                self.assertEqual(1, ipc.call_count)


class GenerationQueueControlTests(unittest.TestCase):
    setUpClass = classmethod(journal_fixtures.JournalTests.setUpClass.__func__)
    setUp = journal_fixtures.JournalTests.setUp

    def app(self):
        app = FastAPI()
        config = Config(mode='open_network', host='0.0.0.0', allowed_hosts=('testserver',),
                        allowed_origins=(), management_token='fixture-supervisor')
        app.add_middleware(Admission, config=config)
        install_control_settings(app, self.runtime, self.gateway.service, self.gateway.engineering, config)
        return app

    def register(self, job_id, principal='paired:old-owner'):
        self.runtime.init_durable_job_store()
        payload = {'model': 'local', 'stream': False, 'gateway': {'client_conversation_id': job_id,
            'command_id': job_id, 'profile_id': 'original-profile', 'store_conversation': True},
            'messages': [{'role': 'user', 'content': 'private original request'}]}
        marker = admission_context.set(dict(principal=principal, command_id=job_id, payload=payload, retention='full'))
        try:
            self.runtime.register_gateway_job(job_id, 'local', 'interactive', threading.Event(), threading.Event(), task_text='Original task')
            self.store.claim(job_id, principal)
        finally:
            admission_context.reset(marker)
        return payload

    def controller(self):
        return RetainedWorkControl(self.runtime, self.gateway.engineering)

    def test_owner_stop_frees_full_durable_generation_admission_without_rebinding(self):
        self.runtime.gateway_max_queued = 2
        for name in ('original-one', 'original-two'):
            self.register(name)
        with self.assertRaises(QueueFull):
            self.register('after-stop')
        owner = self.controller().read('original-one')
        self.assertEqual('paired:old-owner', owner['principal_id'])
        target = owner['control_target']
        with TestClient(self.app(), client=('127.0.0.1', 9011)) as client, patch.object(self.runtime, 'process_chat_payload') as model:
            response = client.post('/gateway/admin/retained-work/original-one/cancel',
                headers={'X-Gateway-Management': 'fixture-supervisor'}, json=dict(target, command_id='stop-queued-generation'))
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual('confirmed', response.json()['cancellation_outcome'])
            self.assertEqual('paired:old-owner', response.json()['principal_id'])
            self.register('after-stop')
            self.assertEqual(2, len(self.runtime.gateway_conversations.pending_generation()))
            original = self.runtime.gateway_conversations.command('paired:old-owner', 'original-one')
            self.assertEqual('original-one', original['job_id'])
            self.assertEqual('original-profile', original['profile_id'])
            self.assertTrue(self.store.owns('original-one', 'paired:old-owner'))
            self.assertFalse(self.store.owns('original-one', 'local:manager'))
            self.assertEqual(target['generation'], self.runtime.job_registry['original-one']['generation'])
            model.assert_not_called()

    def test_confirmed_generation_stop_survives_restart_without_dispatch(self):
        self.register('stopped-generation')
        control = self.controller()
        target = control.read('stopped-generation')['control_target']
        stopped = control.cancel('stopped-generation', dict(target, command_id='stop-original'))
        self.assertEqual('confirmed', stopped['cancellation_outcome'])
        original_receipt = stopped['receipt']
        self.runtime.job_registry.clear()
        self.runtime.singleflight_workers.clear()
        self.runtime.durable_job_initialized = False
        with patch.object(self.runtime, 'process_chat_payload') as model:
            self.runtime.init_durable_job_store()
            asyncio.run(self.gateway.service.recover_queued(self.runtime))
            self.assertFalse(self.gateway.service.recovery_tasks)
            reread = self.controller().receipt('stopped-generation', 'stop-original')
            self.assertEqual(original_receipt, reread['receipt'])
            self.assertEqual('confirmed', reread['cancellation_outcome'])
            self.assertEqual('cancelled', reread['job']['status'])
            self.assertEqual(target['generation'], reread['job']['generation'])
            model.assert_not_called()

    def test_dispatched_generation_stop_is_pending_until_original_worker_finishes(self):
        self.register('dispatched-generation')
        job = self.runtime.job_registry['dispatched-generation']
        job.update(status='running', stage='model', _claim_dispatch=True)
        self.runtime.durable_persist_job('dispatched-generation')
        self.assertEqual('dispatched', self.runtime.gateway_conversations.pending_generation('dispatched-generation')['dispatch_state'])
        control = self.controller()
        target = control.read('dispatched-generation')['control_target']
        stopped = control.cancel('dispatched-generation', dict(target, command_id='stop-running'))
        self.assertEqual('pending', stopped['cancellation_outcome'])
        self.assertFalse(stopped['removal_available'])
        self.assertTrue(job['_hard_cancel_event'].is_set())
        self.runtime.finish_gateway_job('dispatched-generation', 499, None, expected_generation=target['generation'])
        reread = control.receipt('dispatched-generation', 'stop-running')
        self.assertEqual('confirmed', reread['cancellation_outcome'])
        self.assertEqual('pending', reread['receipt']['cancellation_outcome'])

    def test_generation_revision_and_original_ownership_conflict_refuse_stop(self):
        self.register('fenced-generation')
        control = self.controller()
        target = control.read('fenced-generation')['control_target']
        self.runtime.update_gateway_job('fenced-generation', {'phase': 'queued', 'message': 'Waiting for capacity', 'sequence': 1})
        with self.assertRaisesRegex(ValueError, 'changed'):
            control.cancel('fenced-generation', dict(target, command_id='stale-stop'))
        self.assertFalse(self.runtime.job_registry['fenced-generation']['_hard_cancel_event'].is_set())
        self.runtime.gateway_conversations.tx(lambda db: db.execute('UPDATE gateway_generation_admissions SET principal=? WHERE job_id=?', ('another-owner', 'fenced-generation')))
        conflicted = control.read('fenced-generation')
        self.assertIsNone(conflicted['control_target'])
        self.assertFalse(conflicted['cancel_available'])
        self.assertEqual('NOT_DISPATCHED', control.receipt('fenced-generation', 'stale-stop')['receipt']['status'])

    def test_restarted_client_tool_wait_can_stop_locally_without_erasing_or_replaying_handoff(self):
        self.register('retained-client-tool')
        job = self.runtime.job_registry['retained-client-tool']
        job.update(status='running', _claim_dispatch=True)
        self.runtime.durable_persist_job('retained-client-tool')
        handoff = {'choices': [{'message': {'role': 'assistant', 'content': None, 'tool_calls': [
            {'id': 'original-tool-call', 'type': 'function', 'function': {'name': 'github_update_file', 'arguments': '{"path":"original.py"}'}}]}}]}
        self.runtime.finish_gateway_job('retained-client-tool', 200, handoff, expected_generation=job['generation'])
        original = self.controller().read('retained-client-tool')['job']
        self.runtime.job_registry.clear()
        self.runtime.singleflight_workers.clear()
        self.runtime.durable_job_initialized = False
        self.runtime.init_durable_job_store()
        self.assertIsNone(self.runtime.job_registry['retained-client-tool']['_hard_cancel_event'])
        control = self.controller()
        target = control.read('retained-client-tool')['control_target']
        for index, change in enumerate(({'principal_id': 'another-owner'}, {'generation': 'another-generation'})):
            with self.assertRaisesRegex(ValueError, 'changed'):
                control.cancel('retained-client-tool', dict(target, command_id='stale-client-stop-' + str(index), **change))
        live = self.runtime.job_registry['retained-client-tool']
        live['generation'] = 'replacement-worker'
        with self.assertRaisesRegex(ValueError, 'generation changed'):
            control.cancel('retained-client-tool', dict(target, command_id='stale-worker-stop'))
        live['generation'] = target['generation']
        self.assertEqual('NOT_DISPATCHED', control.receipt('retained-client-tool', 'stale-worker-stop')['receipt']['status'])
        self.assertEqual('waiting_for_client_tool', control.read('retained-client-tool')['job']['status'])
        with patch.object(self.runtime, 'process_chat_payload') as model:
            stopped = control.cancel('retained-client-tool', dict(target, command_id='stop-client-wait'))
            self.assertEqual('confirmed', stopped['cancellation_outcome'])
            self.assertEqual('cancelled', stopped['job']['status'])
            self.assertEqual('local_execution', stopped['cancellation_scope'])
            self.assertTrue(stopped['reconciliation_required'])
            self.assertEqual('unconfirmed', stopped['job']['remote_tool_outcome'])
            for key in ('generation', 'model', 'profile_id', 'principal_id', 'pending_client_tool_call_ids', 'pending_client_tool_fingerprints'):
                self.assertEqual(original[key], stopped['job'][key])
            saved = self.runtime.gateway_conversations.read(lambda db: db.execute('SELECT result_json FROM gateway_jobs WHERE job_id=?', ('retained-client-tool',)).fetchone()[0])
            self.assertEqual(handoff, json.loads(saved))
            with self.assertRaisesRegex(Exception, 'already terminal'):
                self.runtime.register_gateway_job('retained-client-tool', 'local', 'interactive', threading.Event(), threading.Event())
            self.runtime.job_registry.clear()
            self.runtime.durable_job_initialized = False
            self.runtime.init_durable_job_store()
            asyncio.run(self.gateway.service.recover_queued(self.runtime))
            reread = control.receipt('retained-client-tool', 'stop-client-wait')
            self.assertEqual(stopped['receipt'], reread['receipt'])
            self.assertEqual('confirmed', reread['cancellation_outcome'])
            self.assertTrue(reread['reconciliation_required'])
            model.assert_not_called()

    def test_client_handoff_commit_failure_returns_structured_uncertainty_without_stop_or_replay(self):
        self.register('failed-client-stop')
        job = self.runtime.job_registry['failed-client-stop']
        job.update(status='running', _claim_dispatch=True)
        self.runtime.durable_persist_job('failed-client-stop')
        handoff = {'choices': [{'message': {'role': 'assistant', 'content': None, 'tool_calls': [
            {'id': 'retained-tool-call', 'type': 'function', 'function': {'name': 'github_update_file', 'arguments': '{}'}}]}}]}
        self.runtime.finish_gateway_job('failed-client-stop', 200, handoff, expected_generation=job['generation'])
        control = self.controller()
        original = control.read('failed-client-stop')
        self.runtime.gateway_conversations.tx(lambda db: db.execute("CREATE TRIGGER reject_local_stop BEFORE UPDATE ON gateway_jobs WHEN NEW.status='cancelled' BEGIN SELECT RAISE(ABORT,'synthetic terminal commit failure'); END"))
        payload = dict(original['control_target'], command_id='stop-original')
        path = '/gateway/admin/retained-work/failed-client-stop'
        headers = {'X-Gateway-Management': 'fixture-supervisor'}
        with TestClient(self.app(), client=('127.0.0.1', 9011), raise_server_exceptions=False) as client, patch.object(self.runtime, 'process_chat_payload') as model:
            failed = client.post(path + '/cancel', headers=headers, json=payload)
            self.assertEqual(503, failed.status_code, failed.text)
            self.assertEqual('retained_work_storage_unavailable', failed.json()['error']['code'])
            receipt = client.get(path + '/cancel/stop-original', headers=headers).json()
            self.assertEqual('unconfirmed', receipt['cancellation_outcome'])
            self.assertEqual('INTENT_RECORDED', receipt['receipt']['status'])
            self.assertEqual('waiting_for_client_tool', job['status'])
            self.assertFalse(job['_hard_cancel_event'].is_set())
            self.assertEqual(original['job']['generation'], job['generation'])
            saved = self.runtime.gateway_conversations.read(lambda db: db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', ('failed-client-stop',)).fetchone())
            self.assertEqual('waiting_for_client_tool', saved[0])
            self.assertEqual(handoff, json.loads(saved[1]))
            repeated = client.post(path + '/cancel', headers=headers, json=payload)
            self.assertEqual(200, repeated.status_code, repeated.text)
            self.assertEqual(receipt['receipt'], repeated.json()['receipt'])
            self.assertEqual('unconfirmed', repeated.json()['cancellation_outcome'])
            self.assertFalse(job['_hard_cancel_event'].is_set())
            model.assert_not_called()


if __name__ == '__main__':
    unittest.main()
