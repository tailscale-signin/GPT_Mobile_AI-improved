"""HTTP delivery evidence reflects actual ASGI sends, including pre-header disconnects."""
import asyncio
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.debug import DebugMiddleware, digest
from v14.diagnostic_events import event_identity, sanitize_event


class RecordingStore:
    enabled = True

    def __init__(self):
        self.records, self.events = [], []

    def record(self, event, **fields):
        self.records.append(dict(event=event, **fields))

    def record_event(self, principal, session, started, **fields):
        self.events.append(fields)


class DiagnosticDeliveryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.store = RecordingStore()
        self.scope = {'type': 'http', 'method': 'POST', 'path': '/gateway/engineering/jobs',
                      'state': {'principal': SimpleNamespace(id='paired-owner')},
                      'headers': [(b'x-request-id', b'1' * 32),
                                  (b'traceparent', b'00-' + b'1' * 32 + b'-' + b'2' * 16 + b'-01'),
                                  (b'x-gateway-diagnostic-session', b'3' * 32),
                                  (b'x-gateway-diagnostic-started', b'1000')]}

    async def exchange(self, app, fail_send=None):
        async def receive():
            return {'type': 'http.request', 'body': b''}

        async def send(message):
            if message['type'] == fail_send:
                raise BrokenPipeError('private host and credentials')

        await DebugMiddleware(app, self.store, '4' * 32)(self.scope, receive, send)

    async def test_pre_header_failure_does_not_invent_http_500_or_received_response(self):
        async def app(scope, receive, send):
            raise ConnectionError('private transport detail')

        with self.assertRaises(ConnectionError):
            await self.exchange(app)
        self.assertEqual(['client_request', 'transport_failure'], [row['event'] for row in self.store.records])
        self.assertEqual(['request', 'transport'], [row['stage'] for row in self.store.events])
        self.assertTrue(all(row['trace_id'] == '1' * 32 and row['request_id'] == digest('1' * 32) for row in self.store.events))
        failure = self.store.events[-1]
        self.assertEqual('unknown', failure['dispatch_state'])
        self.assertFalse(failure['bounded_attributes']['response_headers_sent'])
        self.assertFalse(failure['bounded_attributes']['response_body_completed'])
        self.assertNotIn('private', str(self.store.events))

    async def test_failed_header_send_is_not_a_response_receipt(self):
        async def app(scope, receive, send):
            await send({'type': 'http.response.start', 'status': 200, 'headers': []})

        with self.assertRaises(BrokenPipeError):
            await self.exchange(app, 'http.response.start')
        self.assertFalse(any(row['stage'] == 'response' for row in self.store.events))

    async def test_body_failure_retains_headers_but_does_not_claim_complete_delivery(self):
        async def app(scope, receive, send):
            await send({'type': 'http.response.start', 'status': 200, 'headers': []})
            await send({'type': 'http.response.body', 'body': b'private content', 'more_body': False})

        with self.assertRaises(BrokenPipeError):
            await self.exchange(app, 'http.response.body')
        self.assertEqual(['request', 'transport', 'response'], [row['stage'] for row in self.store.events])
        response = self.store.events[-1]['bounded_attributes']
        self.assertEqual(200, response['status'])
        self.assertTrue(response['response_headers_sent'])
        self.assertFalse(response['response_body_completed'])
        self.assertEqual(0, response['sent_bytes'])

    async def test_cancellation_keeps_ownership_and_no_retry_evidence(self):
        async def app(scope, receive, send):
            raise asyncio.CancelledError()

        with self.assertRaises(asyncio.CancelledError):
            await self.exchange(app)
        self.assertEqual('CANCELLED', self.store.events[-1]['error_code'])
        self.assertEqual('none', self.store.events[-1]['retry_class'])

    async def test_completed_response_has_allowlisted_route_and_delivery_fields(self):
        async def app(scope, receive, send):
            await send({'type': 'http.response.start', 'status': 200, 'headers': []})
            await send({'type': 'http.response.body', 'body': b'ok', 'more_body': False})

        await self.exchange(app)
        response = self.store.events[-1]
        self.assertTrue(response['bounded_attributes']['response_body_completed'])
        self.assertEqual('engineering', response['bounded_attributes']['route'])
        self.assertEqual('POST', response['bounded_attributes']['method'])
        origin, boot = '5' * 32, '6' * 32
        envelope = dict(response, schema_version=2, origin_device_pseudonym=origin, origin_boot_id=boot,
                        origin_sequence=1, event_id=event_identity(origin, boot, 1), occurred_at_utc=1000)
        projected = sanitize_event(envelope, imported=False)
        self.assertEqual(response['bounded_attributes'], projected['bounded_attributes'])
        envelope['bounded_attributes'] = dict(response['bounded_attributes'], route='/private/repository', method='secret')
        self.assertNotIn('route', sanitize_event(envelope)['bounded_attributes'])
        self.assertNotIn('method', sanitize_event(envelope)['bounded_attributes'])


if __name__ == '__main__':
    unittest.main()
