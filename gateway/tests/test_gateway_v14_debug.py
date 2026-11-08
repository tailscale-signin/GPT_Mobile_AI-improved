"""Metadata-only debug retention and the real admitted HTTP exchange contract."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient
from v14.config import Config
from v14.debug import CLIENT_HEADER, DebugLogStore, DebugMiddleware, MAX_LINES, client_metadata, encode_records, install_routes
from v14.identity import Admission


class DebugLogTests(unittest.TestCase):
    def test_disabled_is_quiet_and_memory_disk_and_reload_keep_only_5000_lines(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            store = DebugLogStore(path)
            store.record('client_request')
            store.flush()
            self.assertFalse(path.exists())
            store.set_enabled(True)
            for number in range(MAX_LINES + 11):
                store.record('gateway_response', status=200, sentBytes=number, prompt='secret prompt')
            store.flush()
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(MAX_LINES, len(rows))
            self.assertEqual(MAX_LINES, store.snapshot()['retainedLines'])
            self.assertEqual(11, rows[0]['sentBytes'])
            self.assertNotIn('secret prompt', path.read_text())
            loaded = DebugLogStore(path, enabled=True)
            self.assertEqual(rows, [json.loads(line) for line in loaded.export().splitlines()])
            loaded.record('client_request')
            self.assertEqual(rows[-1]['sequence'] + 1, loaded.snapshot()['lastSequence'])
            loaded.set_enabled(False)
            before = loaded.export()
            loaded.record('gateway_response')
            self.assertEqual(before, loaded.export())

    def test_reload_and_client_schema_drop_arbitrary_text_and_malformed_fields(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            values = [
                {'event': 'client_request', 'client': {'schemaVersion': 1, 'requestRole': [], 'token': 'secret'}},
                {'event': [], 'payload': 'secret'},
                {'event': 'gateway_response', 'status': True, 'route': [], 'failure': {}, 'token': 'secret'},
                {'event': 'client_request', 'client': {'schemaVersion': 1, 'profileDigest': 'private profile name',
                                                      'modelDigest': 'a' * 64, 'androidSdk': 36}},
            ]
            path.write_text('bad-json\n' + '\n'.join(json.dumps(value) for value in values))
            exported = DebugLogStore(path).export()
            self.assertNotIn('secret', exported)
            self.assertNotIn('private profile name', exported)
            rows = [json.loads(line) for line in exported.splitlines()]
            self.assertEqual(3, len(rows))
            self.assertNotIn('status', rows[1])
            self.assertEqual(36, rows[2]['client']['androidSdk'])
            self.assertEqual({}, client_metadata('{"schemaVersion":true}'))
            self.assertEqual({}, client_metadata('x' * 3000))
            self.assertEqual({}, client_metadata('[' * 1000 + ']' * 1000))

    def test_disk_failure_keeps_last_good_snapshot_and_can_retry_without_losing_records(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            store = DebugLogStore(path, enabled=True)
            store.record('client_request')
            store.flush()
            saved = path.read_text()
            store.record('gateway_response', status=200)
            with patch('v14.debug.os.replace', side_effect=OSError('private disk path')):
                store.flush()
            self.assertEqual(saved, path.read_text())
            self.assertEqual('DEBUG_LOG_WRITE_FAILED', store.snapshot()['storageError'])
            self.assertEqual(2, len(store.export().splitlines()))
            store.flush()
            self.assertIsNone(store.snapshot()['storageError'])
            self.assertEqual(2, len(path.read_text().splitlines()))
            self.assertEqual([], list(Path(temp).glob('*.tmp-*')))

    def test_serializing_snapshot_does_not_block_new_request_records_or_lose_their_order(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            store = DebugLogStore(path, enabled=True)
            store.record('client_request')
            started, release = threading.Event(), threading.Event()
            def slow_encode(records):
                started.set()
                if not release.wait(3):
                    raise AssertionError('The snapshot writer did not resume')
                return encode_records(records)
            with ThreadPoolExecutor(max_workers=2) as workers:
                with patch('v14.debug.encode_records', side_effect=slow_encode):
                    pending = workers.submit(store.flush)
                    self.assertTrue(started.wait(1))
                    try:
                        workers.submit(store.record, 'gateway_response', status=200).result(timeout=1)
                    finally:
                        release.set()
                    pending.result(timeout=2)
                self.assertEqual(1, len(path.read_text().splitlines()))
                store.flush()
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(['client_request', 'gateway_response'], [row['event'] for row in rows])
            self.assertEqual([1, 2], [row['sequence'] for row in rows])

    def test_disabled_lifecycle_does_not_create_a_diagnostic_log(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            store = DebugLogStore(path)
            store.start()
            store.record('client_request')
            store.stop()
            self.assertEqual('', store.export())
            self.assertFalse(path.exists())

    def test_background_writer_flushes_final_request_on_shutdown(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'debug.jsonl'
            store = DebugLogStore(path, enabled=True)
            store.start()
            store.record('gateway_response', status=200)
            store.stop()
            self.assertFalse(store.thread.is_alive())
            records = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(['gateway_started', 'gateway_response', 'gateway_stopped'], [row['event'] for row in records])
            self.assertTrue(records[-1]['cleanShutdown'])


class DebugHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = DebugLogStore(Path(self.temp.name) / 'debug.jsonl', enabled=True)
        self.app = FastAPI()
        install_routes(self.app, self.store)

        @self.app.post('/v1/chat/completions')
        async def chat(request: Request):
            await request.body()
            return StreamingResponse(iter(['data: private reply\n', 'data: [DONE]\n']), media_type='text/event-stream')

        self.app.add_middleware(Admission, config=Config())
        self.app.add_middleware(DebugMiddleware, store=self.store, instance_id='a' * 32)
        self.client = TestClient(self.app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 4444))
        self.addCleanup(self.client.close)

    def test_client_and_server_exchange_metadata_without_exporting_bodies_or_credentials(self):
        profile = hashlib.sha256(b'profile private').hexdigest()
        request_body = b'{"model":"private model","prompt":"private user prompt"}'
        response = self.client.post('/v1/chat/completions', content=request_body, headers={
            CLIENT_HEADER: json.dumps({'schemaVersion': 1, 'profileDigest': profile, 'androidSdk': 36, 'deviceId': 'private device'}),
            'Authorization': 'Bearer private credential', 'X-Request-ID': 'request1'})
        self.assertEqual(200, response.status_code)
        self.assertEqual('a' * 32, response.headers['X-Gateway-Instance-ID'])
        self.assertEqual('1', response.headers['X-Gateway-Client-Diagnostics-Accepted'])
        self.assertEqual('accepted', response.headers['X-Gateway-Client-Diagnostics-Status'])
        self.assertEqual('request1', response.headers['X-Gateway-Request-ID'])
        exported = self.client.get('/gateway/admin/debug/export')
        self.assertEqual(200, exported.status_code)
        self.assertEqual('no-store', exported.headers['cache-control'])
        self.assertNotIn('private', exported.text)
        rows = [json.loads(line) for line in exported.text.splitlines()]
        self.assertEqual(['client_request', 'gateway_response'], [row['event'] for row in rows])
        self.assertEqual(profile, rows[0]['client']['profileDigest'])
        self.assertEqual(36, rows[0]['client']['androidSdk'])
        self.assertEqual(len(request_body), rows[1]['receivedBytes'])
        self.assertEqual(len(response.content), rows[1]['sentBytes'])
        self.assertEqual(rows[0]['requestDigest'], rows[1]['requestDigest'])

    def test_disabling_stops_new_records_and_rejects_invalid_configuration(self):
        self.assertEqual(200, self.client.post('/gateway/admin/debug', json={'enabled': False}).status_code)
        before = self.store.export()
        response = self.client.post('/v1/chat/completions', content=b'hello', headers={CLIENT_HEADER: '{"schemaVersion":1}'})
        self.assertEqual('false', response.headers['X-Gateway-Debug-Enabled'])
        self.assertNotIn('X-Gateway-Client-Diagnostics-Accepted', response.headers)
        self.assertEqual('capture_disabled', response.headers['X-Gateway-Client-Diagnostics-Status'])
        self.assertEqual(before, self.store.export())
        for payload in [{'enabled': 'true'}, {'enabled': True, 'secret': 'anything'}, {'enabled': True, 'padding': 'x' * 1000}]:
            with self.subTest(payload=payload):
                self.assertEqual(400, self.client.post('/gateway/admin/debug', json=payload).status_code)
        self.assertFalse(self.store.snapshot()['enabled'])

    def test_malformed_client_metadata_is_explicit_without_echoing_it(self):
        response = self.client.post('/v1/chat/completions', content=b'hello', headers={CLIENT_HEADER: 'private-invalid-metadata'})
        self.assertEqual('invalid_metadata', response.headers['X-Gateway-Client-Diagnostics-Status'])
        self.assertNotIn('X-Gateway-Client-Diagnostics-Accepted', response.headers)
        self.assertNotIn('private-invalid-metadata', self.store.export())

    def test_restricted_health_response_is_recorded_without_accepting_unadmitted_client_metadata(self):
        self.app.user_middleware[1].kwargs['config'] = Config(mode='open_network', host='0.0.0.0')
        self.app.middleware_stack = None
        with TestClient(self.app, base_url='http://127.0.0.1:8090', client=('192.168.1.50', 4444)) as remote:
            response = remote.get('/health', headers={CLIENT_HEADER: '{"schemaVersion":1,"androidSdk":36}'})
        self.assertEqual(403, response.status_code)
        self.assertEqual('a' * 32, response.headers['X-Gateway-Instance-ID'])
        self.assertEqual('remote_surface_not_admitted', response.headers['X-Gateway-Admission-Error'])
        self.assertNotIn('X-Gateway-Client-Diagnostics-Accepted', response.headers)
        rows = [json.loads(line) for line in self.store.export().splitlines()]
        self.assertEqual(403, rows[-1]['status'])
        self.assertNotIn('client', rows[0])

    def test_remote_client_cannot_export_or_enable_desktop_diagnostics(self):
        self.app.user_middleware[1].kwargs['config'] = Config(mode='open_network', host='0.0.0.0')
        self.app.middleware_stack = None
        with TestClient(self.app, base_url='http://127.0.0.1:8090', client=('192.168.1.50', 4444)) as remote:
            self.assertEqual(403, remote.get('/gateway/admin/debug/export').status_code)
            self.assertEqual(403, remote.post('/gateway/admin/debug', json={'enabled': False}).status_code)
        self.assertTrue(self.store.snapshot()['enabled'])
        self.assertEqual('', self.store.export())


if __name__ == '__main__':
    unittest.main()
