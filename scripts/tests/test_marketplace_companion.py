import importlib.util
import json
import http.client
import io
import re
import hashlib
import socket
import threading
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).parents[2] / 'mcp/marketplace/location_mcp.py'
spec = importlib.util.spec_from_file_location('location_mcp', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class CompanionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = dict(MARKETPLACE_ENABLED=','.join(m.OPERATIONS), MARKETPLACE_MCP_TOKEN='a' * 40,
                        MARKETPLACE_DAILY_REQUESTS='2', NOMINATIM_ENDPOINT='https://maps.example.com/search',
                        OVERPASS_ENDPOINT='https://maps.example.com/interpreter')
        self.env.update({key: 'secret-example' for key in m.KEYS.values()})
        self.companion = m.Companion(self.env, Path(self.temp.name) / 'usage.db')

    def tearDown(self):
        self.temp.cleanup()

    def request(self, method, **params):
        return dict(jsonrpc='2.0', id=1, method=method, params=params)

    def test_all_provider_requests_are_fixed_and_bounded(self):
        samples = dict(query='washrooms', location='Toronto', latitude=43.65, longitude=-79.38,
                       end_latitude=43.66, end_longitude=-79.4, resource_id='dataset-uuid', organization_id='1234')
        for provider, operations in m.OPERATIONS.items():
            for operation, fields in operations.items():
                with self.subTest(provider=provider, operation=operation):
                    req = m.request_spec(provider, operation, {f: samples[f] for f in fields}, self.env)
                    self.assertTrue(req.full_url.startswith('https://'))
                    self.assertNotIn('YOUR_', req.full_url)
                    self.assertLess(len(req.full_url), 2000)

    def test_no_arbitrary_url_or_unknown_argument(self):
        with self.assertRaises(m.SafeError):
            m.request_spec('refuge', 'restrooms', dict(latitude=0, longitude=0, url='https://evil.test'), self.env)

    def test_no_shared_public_osm_backend(self):
        for endpoint in ('http://localhost:8080/search', 'https://nominatim.openstreetmap.org/search',
                         'https://user:pass@host.test/search', 'https://host.test/search?token=secret'):
            with self.assertRaises(m.SafeError):
                m.request_spec('nominatim', 'geocode', {'query': 'Toronto'}, dict(self.env, NOMINATIM_ENDPOINT=endpoint))

    def test_coordinate_and_identifier_validation(self):
        for bad in (True, float('nan'), float('inf'), 91, '0'):
            with self.assertRaises(m.SafeError):
                m.request_spec('refuge', 'restrooms', dict(latitude=bad, longitude=0), self.env)
        with self.assertRaises(m.SafeError):
            m.request_spec('eventbrite', 'organization_events', {'organization_id': '../../me'}, self.env)

    def test_eventbrite_is_not_citywide_search(self):
        self.assertEqual(set(m.OPERATIONS['eventbrite']), {'organization_events'})

    def test_google_uses_explicit_field_mask(self):
        req = m.request_spec('google-places', 'places', {'query': 'toilets'}, self.env)
        headers = {k.lower(): v for k, v in req.header_items()}
        self.assertNotIn('*', headers['x-goog-fieldmask'])
        self.assertEqual(json.loads(req.data)['pageSize'], 10)

    def test_initialize_and_discovery_spend_no_provider_requests(self):
        result = self.companion.dispatch('ticketmaster', self.request('initialize', protocolVersion='2025-06-18'))
        self.assertEqual(result['result']['protocolVersion'], '2025-06-18')
        tools = self.companion.dispatch('ticketmaster', self.request('tools/list'))['result']['tools']
        self.assertEqual(tools[0]['name'], 'events')
        self.assertFalse(self.companion.last)

    def test_notifications_never_execute_tools(self):
        with self.assertRaises(m.SafeError):
            self.companion.dispatch('refuge', dict(jsonrpc='2.0', method='tools/call', params={}))
        self.assertIsNone(self.companion.dispatch('refuge', dict(jsonrpc='2.0', method='notifications/initialized')))
        self.assertFalse(self.companion.last)

    def test_quota_survives_restart(self):
        with patch.object(m.time, 'monotonic', side_effect=[1, 3, 5]):
            self.companion.reserve('refuge')
            self.companion.reserve('refuge')
            restarted = m.Companion(self.env, Path(self.temp.name) / 'usage.db')
            with self.assertRaises(m.SafeError):
                restarted.reserve('refuge')

    def test_upstream_failure_never_exposes_secret(self):
        with patch.object(m.urllib.request, 'build_opener', side_effect=OSError('https://host/?key=secret-example')):
            result = self.companion.dispatch('refuge', self.request('tools/call', name='restrooms', arguments=dict(latitude=0, longitude=0)))
        self.assertTrue(result['result']['isError'])
        self.assertNotIn('secret-example', json.dumps(result))

    def test_disabled_provider_and_unknown_method(self):
        with self.assertRaises(m.SafeError):
            self.companion.dispatch('unconfigured', self.request('tools/list'))
        result = self.companion.dispatch('refuge', self.request('write_everything'))
        self.assertEqual(result['error']['code'], -32601)

    def test_no_automatic_redirects(self):
        body = io.BytesIO(b'private provider response')
        with self.assertRaises(m.SafeError):
            m.NoRedirect().redirect_request(None, body, 302, '', {}, 'https://evil.test')
        self.assertTrue(body.closed)

    def test_allowlist_accepts_spaces_without_enabling_extra_providers(self):
        companion = m.Companion(dict(self.env, MARKETPLACE_ENABLED=' refuge, toronto, '),
                                Path(self.temp.name) / 'spaced.db')
        self.assertEqual(companion.enabled, {'refuge', 'toronto'})

    def test_parallel_reservations_enforce_one_shared_cooldown(self):
        barrier = threading.Barrier(8)
        accepted = []

        def reserve():
            barrier.wait(timeout=2)
            try:
                self.companion.reserve('refuge')
                accepted.append(True)
            except m.SafeError:
                accepted.append(False)

        with patch.object(m.time, 'monotonic', return_value=100):
            threads = [threading.Thread(target=reserve) for _ in range(8)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=3)
        self.assertEqual(accepted.count(True), 1)
        self.assertEqual(len(accepted), 8)
        with m.closing(m.sqlite3.connect(self.companion.db)) as db:
            self.assertEqual(db.execute('SELECT calls FROM usage').fetchone(), (1,))

    def test_http_error_body_closed_and_429_cooldown_shared(self):
        body = io.BytesIO(b'secret-example')
        error = m.urllib.error.HTTPError('https://provider/?key=secret-example', 429, 'limited', {}, body)
        with patch.object(m.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            result = self.companion.dispatch('refuge', self.request('tools/call', name='restrooms',
                                            arguments=dict(latitude=0, longitude=0)))
        self.assertTrue(body.closed)
        self.assertTrue(result['result']['isError'])
        self.assertNotIn('secret-example', json.dumps(result))
        with self.assertRaisesRegex(m.SafeError, 'cooldown'):
            self.companion.reserve('refuge')


class TransportTests(unittest.TestCase):
    # Exercise the actual Streamable HTTP handler locally, never a real provider.
    def setUp(self):
        CompanionTests.setUp(self)
        self.server = m.CompanionServer(('127.0.0.1', 0), m.handler_for(self.companion))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        CompanionTests.tearDown(self)

    def request(self, method, **params):
        return dict(jsonrpc="2.0", id=1, method=method, params=params)

    def http(self, payload=None, headers=None, method='POST'):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=2)
        body = json.dumps(payload).encode() if payload is not None else b''
        defaults = {'Authorization': 'Bearer ' + self.env['MARKETPLACE_MCP_TOKEN'],
                    'Content-Type': 'application/json', 'MCP-Protocol-Version': '2025-06-18'}
        defaults.update(headers or {})
        try:
            connection.request(method, '/mcp/refuge', body, defaults)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_unauthenticated_and_browser_origin_rejected(self):
        self.assertEqual(self.http(self.request('tools/list'), {'Authorization': 'Bearer wrong'})[0], 401)
        self.assertEqual(self.http(self.request('tools/list'), {'Origin': 'https://evil.test'})[0], 403)
        self.assertFalse(self.companion.last)

    def test_initialize_and_notification_transport(self):
        status, body = self.http(self.request('initialize', protocolVersion='2025-06-18'))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['result']['protocolVersion'], '2025-06-18')
        self.assertEqual(self.http(dict(jsonrpc='2.0', method='notifications/initialized')), (202, b''))
        self.assertEqual(self.http(method='GET')[0], 405)

    def test_protocol_mismatch_rejected(self):
        self.assertEqual(self.http(self.request('tools/list'), {'MCP-Protocol-Version': 'invalid'})[0], 400)

    def test_idle_client_does_not_block_discovery(self):
        with socket.create_connection(('127.0.0.1', self.server.server_port), timeout=2) as idle:
            idle.sendall(b'POST /mcp/refuge HTTP/1.1\r\n')
            status, body = self.http(self.request('tools/list'))
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)['result']['tools'])

    def test_slow_provider_does_not_block_ping(self):
        started, release = threading.Event(), threading.Event()
        responses = []

        def upstream(*args, **kwargs):
            started.set()
            if not release.wait(timeout=3):
                raise TimeoutError()
            return io.BytesIO(b'[]')

        with patch.object(m.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = upstream
            worker = threading.Thread(target=lambda: responses.append(self.http(
                self.request('tools/call', name='restrooms', arguments=dict(latitude=0, longitude=0)))))
            worker.start()
            try:
                self.assertTrue(started.wait(timeout=1))
                self.assertEqual(self.http(self.request('ping'))[0], 200)
            finally:
                release.set()
                worker.join(timeout=3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(responses[0][0], 200)

    def test_worker_limit_closes_excess_clients_and_recovers(self):
        # Occupy every worker permit without depending on socket accept timing.
        for _ in range(self.server.max_workers):
            self.assertTrue(self.server.workers.acquire(blocking=False))
        try:
            with socket.create_connection(('127.0.0.1', self.server.server_port), timeout=2) as extra:
                self.assertEqual(extra.recv(1), b'')
        finally:
            for _ in range(self.server.max_workers):
                self.server.workers.release()
        self.assertEqual(self.http(self.request('ping'))[0], 200)

    def test_valid_tool_response_and_provider_error(self):
        request = self.request('tools/call', name='restrooms', arguments=dict(latitude=0, longitude=0))
        with patch.object(m.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = io.BytesIO(b'[]')
            status, body = self.http(request)
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)['result']['isError'])
        self.companion.last.clear()
        with patch.object(m.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = io.BytesIO(b'{"error":{"message":"secret-example"}}')
            status, body = self.http(request)
        self.assertTrue(json.loads(body)['result']['isError'])
        self.assertNotIn(b'secret-example', body)


class PackagePinsTests(unittest.TestCase):
    def test_assets_match_apk_anchored_hashes(self):
        root = Path(__file__).parents[2]
        catalog = (root / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/catalog/GitHubMarketplaceCatalog.kt').read_text()
        for name, constant in [('README.md', 'GUIDE_SHA256'), ('location_mcp.py', 'COMPANION_SHA256')]:
            digest = re.search(r'const val ' + constant + r' = "([0-9a-f]{64})"', catalog).group(1)
            self.assertEqual(hashlib.sha256((MODULE.parent / name).read_bytes()).hexdigest(), digest)
        self.assertRegex(catalog, r'SOURCE_COMMIT = "[0-9a-f]{40}"')


if __name__ == '__main__':
    unittest.main()
