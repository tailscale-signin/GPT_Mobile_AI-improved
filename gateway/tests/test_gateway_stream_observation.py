"""Real StreamingResponse send boundary with deliberately delayed events and backpressure."""
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_stream_observation import ObservedCompletionStream


class StreamObservationTests(unittest.IsolatedAsyncioTestCase):
    async def test_delivery_observation_distinguishes_generator_delay_from_asgi_send_and_unknown_flush(self):
        now = [0.0]
        sent = []
        async def chunks():
            now[0] += 0.010
            yield 'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n'
            self.assertEqual(1, len(sent))
            now[0] += 0.100
            yield 'data: {"choices":[{"delta":{"content":"first"}}]}\n\n'
            self.assertEqual(2, len(sent))
            now[0] += 0.200
            yield 'data: {"choices":[{"delta":{"content":"second"},"finish_reason":"stop"}]}\n\n'
            yield 'data: [DONE]\n\n'
        async def send(message):
            if message['type'] == 'http.response.body' and message.get('body'):
                sent.append(message['body'])
                now[0] += 0.005
        response = ObservedCompletionStream(chunks(), media_type='text/event-stream', clock=lambda: now[0])
        await response.stream_response(send)
        self.assertEqual(b'data: [DONE]\n\n', sent[-1])
        receipt = json.loads(sent[-2][6:])['gateway_delivery']
        self.assertEqual('gateway_asgi_stream', receipt['measurement_scope'])
        self.assertEqual('completion_buffered', receipt['delivery_mode'])
        self.assertEqual((10.0, 15.0, 115.0, 120.0, 325.0), tuple(receipt[key] for key in (
            'first_event_submit_ms', 'first_event_send_completed_ms', 'first_content_submit_ms',
            'first_content_send_completed_ms', 'observed_elapsed_ms')))
        self.assertEqual(3, receipt['forwarded_events'])
        self.assertIsNone(receipt['socket_flush_confirmed'])
        self.assertNotIn('second', json.dumps(receipt))

    async def test_tool_frames_are_forwarded_without_becoming_content_or_being_executed(self):
        sent = []
        packet = 'data: {"choices":[{"delta":{"tool_calls":[{"id":"c","function":{"name":"write_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}\n\n'
        async def chunks():
            yield packet
            yield 'data: [DONE]\n\n'
        async def send(message):
            if message.get('body'):
                sent.append(message['body'])
        await ObservedCompletionStream(chunks(), clock=lambda: 1.0).stream_response(send)
        self.assertEqual(packet.encode(), sent[0])
        receipt = json.loads(sent[-2][6:])['gateway_delivery']
        self.assertEqual(1, receipt['forwarded_events'])
        self.assertIsNone(receipt['first_content_submit_ms'])
        self.assertIsNone(receipt['first_content_send_completed_ms'])

    async def test_failed_send_never_produces_a_successful_delivery_receipt(self):
        async def chunks():
            yield 'data: {"choices":[{"delta":{"content":"answer"}}]}\n\n'
            yield 'data: [DONE]\n\n'
        async def send(message):
            if message.get('body'):
                raise ConnectionError('Disconnected client')
        with self.assertRaises(ConnectionError):
            await ObservedCompletionStream(chunks()).stream_response(send)


if __name__ == '__main__':
    unittest.main()
