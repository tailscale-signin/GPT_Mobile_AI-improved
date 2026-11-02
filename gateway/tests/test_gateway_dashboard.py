"""Dashboard observations must preserve request/tool behavior and stay local."""
import asyncio
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import atomic_json
from desktop.dashboard_model import counters, read_snapshot, rows
from v14.activity import ActivityMiddleware, ActivityTracker, MAX_ACTIVITIES, MAX_DEVICES, current_peer, desktop_snapshot
from v14.actions import current_gateway_job_id


class ActivityTests(unittest.TestCase):
    def test_streaming_passthrough_tracks_socket_not_forwarded_identity(self):
        tracker = ActivityTracker(now=lambda: 100)
        sent = []
        async def app(scope, receive, send):
            self.assertEqual(b'private prompt', (await receive())['body'])
            self.assertEqual('192.168.1.42', current_peer.get())
            await send({'type': 'http.response.start', 'status': 200})
            await send({'type': 'http.response.body', 'body': b'one', 'more_body': True})
            await send({'type': 'http.response.body', 'body': b'two', 'more_body': False})
        async def receive():
            return {'type': 'http.request', 'body': b'private prompt'}
        async def send(message):
            sent.append(message)
        scope = {'type': 'http', 'path': '/v1/chat/completions', 'client': ('192.168.1.42', 1234),
                 'headers': [(b'user-agent', b'Android'), (b'x-forwarded-for', b'10.0.0.1'), (b'authorization', b'secret')]}
        asyncio.run(ActivityMiddleware(app, tracker)(scope, receive, send))
        snapshot = tracker.snapshot()
        self.assertEqual(6, snapshot['sentBytes'])
        self.assertEqual(14, snapshot['receivedBytes'])
        self.assertEqual('192.168.1.42', snapshot['devices'][0]['address'])
        self.assertEqual(0, snapshot['devices'][0]['activeRequests'])
        self.assertEqual('completed', snapshot['activities'][0]['status'])
        self.assertEqual([b'one', b'two'], [m['body'] for m in sent[1:]])
        self.assertNotIn('private prompt', json.dumps(snapshot))
        self.assertNotIn('secret', json.dumps(snapshot))
        self.assertIsNone(current_peer.get())

    def test_cancellation_cleans_active_requests_and_context(self):
        tracker = ActivityTracker()
        async def broken(*args):
            raise asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            asyncio.run(ActivityMiddleware(broken, tracker)({'type': 'http', 'path': '/chat/completions', 'client': ('pc', 1)}, None, None))
        snapshot = tracker.snapshot()
        self.assertEqual(0, snapshot['devices'][0]['activeRequests'])
        self.assertEqual('interrupted', snapshot['activities'][0]['status'])
        self.assertEqual(1, snapshot['errors'])
        self.assertIsNone(current_peer.get())

    def test_admin_polls_do_not_appear_as_connected_devices(self):
        tracker = ActivityTracker()
        async def empty(*args):
            pass
        for path in ['/gateway/admin/dashboard', '/v1/gateway/admin/maintenance', '/gateway/live', '/gateway/ready']:
            asyncio.run(ActivityMiddleware(empty, tracker)({'type': 'http', 'path': path}, None, None))
        self.assertEqual(0, tracker.snapshot()['requests'])

    def test_tools_keep_results_exceptions_and_job_context_without_arguments(self):
        result = {'content': 'private result'}
        runtime = SimpleNamespace(_execute_mcp_tool_single=lambda *args, **kw: result)
        tracker = ActivityTracker()
        tracker.install_tools(runtime)
        token = current_gateway_job_id.set('job-123')
        try:
            self.assertIs(result, runtime._execute_mcp_tool_single('web__search', {'query': 'private query'}))
        finally:
            current_gateway_job_id.reset(token)
        item = tracker.snapshot()['activities'][0]
        self.assertEqual(('search', 'completed', 'job-123'), (item['kind'], item['status'], item['job']))
        self.assertNotIn('private', json.dumps(item))
        def broken(*args):
            raise ValueError('original failure')
        runtime = SimpleNamespace(_execute_mcp_tool_single=broken)
        tracker.install_tools(runtime)
        with self.assertRaisesRegex(ValueError, 'original failure'):
            runtime._execute_mcp_tool_single('github__fetch', {})
        self.assertEqual('failed', tracker.snapshot()['activities'][-1]['status'])

    def test_bounded_history_and_interrupted_restart(self):
        clock = [100]
        tracker = ActivityTracker(now=lambda: clock[0])
        for i in range(MAX_ACTIVITIES + 5):
            tracker.begin('search', 'Search ' + str(i))
        for i in range(MAX_DEVICES + 5):
            tracker.enter(str(i), 'client')
            tracker.leave(str(i), 200, 0, 0)
        clock[0] = 200
        snapshot = tracker.snapshot()
        self.assertEqual(MAX_DEVICES, len(snapshot['devices']))
        self.assertEqual(MAX_ACTIVITIES, len(snapshot['activities']))
        self.assertEqual('idle', snapshot['devices'][0]['state'])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'activity.json'
            atomic_json(path, {'activities': snapshot['activities']})
            restored = ActivityTracker(path).snapshot()
        self.assertEqual(MAX_ACTIVITIES, len(restored['activities']))
        self.assertTrue(all(item['status'] == 'interrupted' for item in restored['activities']))
        self.assertEqual([], restored['devices'])
        self.assertEqual(0, restored['requests'])

    def test_saved_jobs_project_metadata_only(self):
        with tempfile.TemporaryDirectory() as temp:
            path = str(Path(temp) / 'jobs.sqlite3')
            with closing(sqlite3.connect(path)) as db, db:
                db.execute('CREATE TABLE gateway_jobs(job_id, public_json, status, created_at, updated_at)')
                db.execute('INSERT INTO gateway_jobs VALUES(?,?,?,?,?)', ('chat-1', json.dumps({'model': 'model', 'prompt': 'private prompt', 'result': 'private answer'}), 'completed', 1, 2))
                db.execute('CREATE TABLE engineering_jobs(id,state,data,next_at)')
                db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?)', ('eng-1', 'CI', json.dumps({'request': {'repository': 'owner/repo', 'branch': 'feature', 'token': 'secret'}, 'created_at': 1, 'updated_at': 3, 'message': 'Checking CI'}), 3))
            runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(), _durable_connect=lambda: sqlite3.connect(path))
            snapshot = desktop_snapshot(runtime, SimpleNamespace(snapshot=lambda: {'activeWorkers': 0}), ActivityTracker())
        self.assertIsNone(snapshot['historyError'])
        self.assertEqual('model', snapshot['jobs'][0]['title'])
        self.assertEqual('owner/repo', snapshot['engineering'][0]['title'])
        self.assertEqual('feature', snapshot['engineering'][0]['branch'])
        self.assertNotIn('private', json.dumps(snapshot))
        self.assertNotIn('secret', json.dumps(snapshot))


class PresentationTests(unittest.TestCase):
    def test_stale_or_stopped_data_never_claims_live_connections(self):
        with tempfile.TemporaryDirectory() as temp:
            atomic_json(Path(temp) / 'dashboard.json', {'capturedAt': 100, 'devices': [{'state': 'recent'}]})
            atomic_json(Path(temp) / 'status.json', {'state': 'running'})
            self.assertEqual(1, counters(read_snapshot(temp, now=105))[0])
            self.assertIsNone(counters(read_snapshot(temp, now=111))[0])
            atomic_json(Path(temp) / 'status.json', {'state': 'stopped'})
            self.assertIsNone(counters(read_snapshot(temp, now=105))[0])

    def test_tabs_filter_and_order_actual_metadata(self):
        snapshot = {'activities': [{'kind': 'search', 'title': 'Web search', 'updated': 5}, {'kind': 'github', 'title': 'github fetch', 'updated': 6}],
                    'engineering': [{'title': 'owner/repo', 'branch': 'feature', 'status': 'COMPLETED', 'updated': 10}],
                    'jobs': [{'title': 'Chat', 'status': 'running', 'updated': 8}], 'fresh': True}
        self.assertEqual('Web search', rows(snapshot, 'Search jobs')[0]['title'])
        self.assertEqual(2, len(rows(snapshot, 'GitHub jobs')))
        self.assertEqual('owner/repo', rows(snapshot, 'Recent activity', 'FEATURE')[0]['title'])
        self.assertEqual(1, counters(snapshot)[1])
        self.assertEqual([], rows(snapshot, 'Devices'))

    def test_dashboard_entry_dispatch_does_not_run_firewall(self):
        from desktop.entry import main
        # A module stand-in also allows this test on Python installs without Tk.
        fake = SimpleNamespace(show=lambda *a, **k: None)
        with tempfile.TemporaryDirectory() as temp, patch.dict(sys.modules, {'desktop.dashboard': fake}), \
                patch.object(fake, 'show') as show, patch('desktop.firewall.apply') as firewall, \
                patch.object(sys, 'argv', ['Gateway', 'dashboard', '--root', temp, '--smoke-ui']):
            self.assertEqual(0, main())
            show.assert_called_once_with(Path(temp).resolve(), smoke=True)
            firewall.assert_not_called()


if __name__ == '__main__':
    unittest.main()
