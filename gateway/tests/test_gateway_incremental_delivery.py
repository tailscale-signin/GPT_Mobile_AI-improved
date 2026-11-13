"""Real transport, shared worker, ASGI delivery and durable-result streaming regressions."""
import asyncio
import copy
import importlib
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from contextlib import ExitStack, closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_security import DeviceStore
from gateway_stream_observation import ObservedCompletionStream
from gateway_v13_runtime import GatewayContractError, completion_chunks
from v14.text_delivery import TextDelivery, current_delivery, final_answer, visible_output


def objects(packets):
    return [json.loads(packet[6:]) for packet in packets if packet.startswith(b'data: ') and packet.strip() != b'data: [DONE]']


def content(packets):
    return ''.join(choice.get('delta', {}).get('content', '') for packet in objects(packets) for choice in packet.get('choices', []))


class TextDeliveryTests(unittest.TestCase):
    def test_fragmented_private_tags_never_enter_visible_prefix_and_final_tail_is_exact(self):
        delivery = TextDelivery()
        raw = ''
        for part, expected in [('<thi', ''), ('nk mode="private">secret', ''), ('</think> First ', ' First '),
                               ('answer<rea', ' First answer'), ('soning>hidden</reasoning> tail', ' First answer tail')]:
            raw += part
            delivery.offer(raw)
            self.assertEqual(expected, delivery.snapshot())
        prefix = ' First'
        result = {'choices': [{'message': {'content': raw}, 'finish_reason': 'stop'}], 'usage': {'completion_tokens': 12}}
        packets = [packet.encode() for packet in completion_chunks(result, emitted_content=prefix)]
        self.assertEqual(' First answer tail', prefix + content(packets))
        self.assertNotIn('secret', content(packets))
        self.assertEqual(1, sum('usage' in value for value in objects(packets)))
        self.assertEqual(1, packets.count(b'data: [DONE]\n\n'))

    def test_nested_controls_never_publish_the_outer_block_tail(self):
        delivery = TextDelivery()
        raw = ''
        for part in ('Answer <thi', 'nk>outer <think>inner</think> still private',
                     '<tool_call id="private">tool</tool_call> outer tail'):
            raw += part
            delivery.offer(raw)
            self.assertEqual('Answer ', delivery.snapshot())
        raw += '</think>\nVisible tail.'
        delivery.offer(raw)
        self.assertEqual('Answer \nVisible tail.', delivery.snapshot())
        result = {'choices': [{'message': {'content': raw}, 'finish_reason': 'stop'}]}
        for prefix in ('', 'Answer '):
            packets = [packet.encode() for packet in completion_chunks(result, emitted_content=prefix)]
            self.assertEqual('Answer \nVisible tail.', prefix + content(packets))

    def test_bounded_prefix_keeps_all_remaining_characters_for_final_delivery(self):
        delivery = TextDelivery(maximum=5)
        delivery.offer('A long answer')
        delivery.offer('A long answer with more text')
        self.assertEqual('A lon', delivery.snapshot())
        prefix = ''.join(delta for delta, _ in delivery.chunks('', 'local', 'id', 1, chunk_chars=2))
        result = {'choices': [{'message': {'content': 'A long answer with more text'}, 'finish_reason': 'stop'}]}
        packets = [packet.encode() for packet in completion_chunks(result, emitted_content=prefix)]
        self.assertEqual('A long answer with more text', prefix + content(packets))
        with self.assertRaises(GatewayContractError):
            delivery.offer('Different answer')

    def test_only_explicit_text_final_roles_enable_publication(self):
        marker = current_delivery.set(TextDelivery())
        try:
            for role, payload, allowed in [
                ('primary', {}, True), ('github-primary', {'tool_choice': 'none'}, True),
                ('synthesis', {}, True), ('follow_up_synthesis', {}, True),
                ('reviewer', {}, False), ('delegate', {}, False), ('follow_up', {}, False),
                ('primary', {'tools': [{'type': 'function'}]}, False),
                ('primary', {'tool_choice': 'required'}, False),
                ('primary', {'response_format': {'type': 'json_schema'}}, False),
            ]:
                with self.subTest(role=role, payload=payload), final_answer(payload, role):
                    self.assertEqual(allowed, visible_output.get() is not None)
            self.assertIsNone(visible_output.get())
        finally:
            current_delivery.reset(marker)


class IncrementalRuntimeTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.db = Path(self.temp) / 'jobs.sqlite3'
        self.runtime = self.gateway.runtime
        store = DeviceStore(Path(self.temp) / 'auth.sqlite3')
        for name, value in {
            'GATEWAY_JOB_DB_PATH': self.db, 'GATEWAY_DURABLE_JOBS': True,
            'durable_job_initialized': False, 'startup_services_started': False,
            'job_registry': {}, 'singleflight_workers': {}, 'singleflight_request_index': {},
            'device_store': store, 'gateway_draining': False,
            'durable_job_metrics': dict.fromkeys(self.runtime.durable_job_metrics, 0),
        }.items():
            self.stack.enter_context(patch.object(self.runtime, name, value))
        self.stack.enter_context(patch.object(self.runtime, 'apply_context_guard'))
        self.stack.enter_context(patch.object(self.runtime, 'configure_llama_model_round'))
        self.assertTrue(self.runtime.init_durable_job_store())

    def backend(self, *, disconnect=False, first_text='First', final_text=' answer', empty_attempt=None):
        release, first_event = threading.Event(), threading.Event()
        received, errors = [], []

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_POST(self):
                received.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                empty = empty_attempt is not None and len(received) == 1
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Transfer-Encoding', 'chunked')
                self.send_header('Connection', 'close')
                self.end_headers()
                self.close_connection = True

                def write(value):
                    packet = ('data: ' + (value if isinstance(value, str) else json.dumps(value)) + '\n\n').encode()
                    self.wfile.write(f'{len(packet):x}\r\n'.encode() + packet + b'\r\n')
                    self.wfile.flush()

                try:
                    write({'choices': [{'delta': {'content': empty_attempt if empty else first_text}, 'finish_reason': None}]})
                    if not empty:
                        first_event.set()
                        if not release.wait(5):
                            errors.append('Backend was not released by the observing test')
                        if disconnect:
                            self.close_connection = True
                            return
                    write({'choices': [{'delta': {'content': '' if empty else final_text}, 'finish_reason': 'stop'}]})
                    write({'choices': [], 'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5}})
                    write('[DONE]')
                    self.wfile.write(b'0\r\n\r\n')
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        def cleanup():
            release.set()
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)
        self.addCleanup(cleanup)
        self.stack.enter_context(patch.object(self.runtime, 'LLAMA_BASE', f'http://127.0.0.1:{server.server_port}'))
        return release, first_event, received, errors

    def body(self, job_id, role='primary'):
        return {'model': 'fixture', 'stream': True, 'max_tokens': 128, 'tool_choice': 'none',
                'messages': [{'role': 'user', 'content': 'Answer the question'}],
                '_gateway_job_id': job_id,
                '_gateway_performance': {'request_role': role, 'delegated_worker': True,
                                         'allow_gateway_local_tools': False, 'client_tool_choice': 'none'}}

    async def observe(self, body, job_id, packets, first_visible, *, follower=False):
        async def send(message):
            packet = message.get('body')
            if packet:
                packets.append(packet)
                if content([packet]):
                    first_visible.set()
        stream = self.runtime.stream_chat_with_keepalive(copy.deepcopy(body), job_id, 'request-' + job_id,
                                                       singleflight_follower=follower)
        await ObservedCompletionStream(stream, media_type='text/event-stream').stream_response(send)

    async def test_first_answer_reaches_asgi_before_backend_completes_and_followers_share_one_result(self):
        release, _, received, errors = self.backend()
        job_id = 'visible-first-answer'
        first, second = [], []
        first_visible, second_visible = asyncio.Event(), asyncio.Event()
        body = self.body(job_id)
        leader = asyncio.create_task(self.observe(body, job_id, first, first_visible))
        follower = None
        try:
            # The backend cannot complete until both clients receive its prefix.
            await asyncio.wait_for(first_visible.wait(), 3)
            self.assertFalse(leader.done())
            self.assertEqual('First', content(first))
            with closing(sqlite3.connect(self.db)) as db:
                self.assertNotEqual('completed', db.execute('SELECT status FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
            follower = asyncio.create_task(self.observe(body, job_id, second, second_visible, follower=True))
            await asyncio.wait_for(second_visible.wait(), 2)
            self.assertEqual('First', content(second))
        finally:
            release.set()
            await asyncio.wait_for(asyncio.gather(leader, *([follower] if follower else [])), 3)
        self.assertFalse(errors)
        self.assertEqual(1, len(received))
        self.assertEqual('fixture', received[0]['model'])
        self.assertEqual(128, received[0]['max_tokens'])
        for packets in (first, second):
            self.assertEqual('First answer', content(packets))
            self.assertEqual(1, sum('usage' in value for value in objects(packets)))
            self.assertEqual(1, packets.count(b'data: [DONE]\n\n'))
            answer_ids = {value['id'] for value in objects(packets) if any(
                choice.get('delta', {}).get('content') or choice.get('finish_reason')
                for choice in value.get('choices', []))}
            self.assertEqual(1, len(answer_ids))
            receipt = next(value['gateway_delivery'] for value in objects(packets) if 'gateway_delivery' in value)
            self.assertEqual('incremental_final_answer', receipt['delivery_mode'])
            self.assertIsNone(receipt['socket_flush_confirmed'])
        with closing(sqlite3.connect(self.db)) as db:
            status, result = db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
            self.assertEqual('completed', status)
            self.assertEqual('First answer', json.loads(result)['choices'][0]['message']['content'])
        replay = []
        await self.observe(body, job_id, replay, asyncio.Event(), follower=True)
        self.assertEqual('First answer', content(replay))
        self.assertEqual(1, len(received))

    async def test_partial_backend_disconnect_is_terminal_and_does_not_restart_generation(self):
        release, _, received, _ = self.backend(disconnect=True)
        packets, first_visible = [], asyncio.Event()
        job_id = 'partial-backend-disconnect'
        worker = asyncio.create_task(self.observe(self.body(job_id, role='github-primary'), job_id, packets, first_visible))
        try:
            await asyncio.wait_for(first_visible.wait(), 3)
        finally:
            release.set()
            await asyncio.wait_for(worker, 3)
        self.assertEqual('First', content(packets))
        self.assertEqual(1, len(received))
        error = next(value['error'] for value in objects(packets) if 'error' in value)
        self.assertEqual('backend_stream_interrupted', error['code'])
        self.assertTrue(error['partial_output_may_have_been_published'])
        self.assertFalse(error['retryable'])
        self.assertFalse(error['automatic_replay'])
        self.assertEqual('failed', self.runtime.job_registry[job_id]['status'])
        self.assertFalse(self.runtime.android_job_result_shape(job_id, 'failed', {'error': error})['continuation_allowed'])

    async def test_layout_is_identical_for_incremental_buffered_fast_and_saved_completion(self):
        prefix, tail = '    def example():\n', '\treturn "🌸"\n\n'
        expected = prefix + tail
        release, first_event, received, errors = self.backend(first_text=prefix, final_text=tail)
        for mode, role in (('incremental', 'primary'), ('buffered', 'reviewer'), ('fast', 'primary')):
            with self.subTest(mode=mode):
                release.clear()
                first_event.clear()
                packets, first_visible = [], asyncio.Event()
                job_id = 'layout-' + mode
                body = self.body(job_id, role=role)
                if mode == 'fast':
                    release.set()
                poll_seconds = 5 if mode == 'fast' else self.runtime.PROGRESS_POLL_SECONDS
                with patch.object(self.runtime, 'PROGRESS_POLL_SECONDS', poll_seconds):
                    worker = asyncio.create_task(self.observe(body, job_id, packets, first_visible))
                    try:
                        if mode == 'incremental':
                            await asyncio.wait_for(first_visible.wait(), 3)
                            self.assertFalse(worker.done())
                            self.assertEqual(prefix, content(packets))
                        elif mode == 'buffered':
                            self.assertTrue(await asyncio.to_thread(first_event.wait, 2))
                            with self.assertRaises(asyncio.TimeoutError):
                                await asyncio.wait_for(first_visible.wait(), poll_seconds * 1.5)
                    finally:
                        release.set()
                        await asyncio.wait_for(worker, 3)
                self.assertFalse(any('error' in value for value in objects(packets)))
                self.assertEqual(expected, content(packets))
                receipt = next(value['gateway_delivery'] for value in objects(packets) if 'gateway_delivery' in value)
                self.assertEqual('incremental_final_answer' if mode == 'incremental' else 'completion_buffered', receipt['delivery_mode'])
                with closing(sqlite3.connect(self.db)) as db:
                    status, result = db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
                    self.assertEqual('completed', status)
                    self.assertEqual(expected, json.loads(result)['choices'][0]['message']['content'])
                replay, requests_before = [], len(received)
                await self.observe(body, job_id, replay, asyncio.Event(), follower=True)
                self.assertEqual(expected, content(replay))
                self.assertEqual(requests_before, len(received))
        self.assertFalse(errors)
        self.assertEqual(3, len(received))

    async def test_whitespace_and_hidden_only_attempts_remain_repairable_before_publication(self):
        for empty in (' \t\n', '<think>private</think> \t\n'):
            with self.subTest(empty=empty):
                prefix, tail = '    Repaired', ' answer\n'
                release, backend_prefix, received, errors = self.backend(first_text=prefix, final_text=tail, empty_attempt=empty)
                packets, first_visible = [], asyncio.Event()
                job_id = 'repair-layout-' + str(len(self.runtime.job_registry))
                worker = asyncio.create_task(self.observe(self.body(job_id), job_id, packets, first_visible))
                try:
                    # Repair readiness includes the empty attempt and durable progress
                    # writes. Measure delivery only after the repair backend has emitted
                    # its prefix; its terminal marker remains held until our finally.
                    self.assertTrue(await asyncio.to_thread(backend_prefix.wait, 3),
                                    f'Repair backend not ready: requests={len(received)}, worker_done={worker.done()}')
                    await asyncio.wait_for(first_visible.wait(), 3)
                    self.assertEqual(2, len(received))
                    self.assertEqual(prefix, content(packets))
                    self.assertFalse(worker.done())
                finally:
                    release.set()
                    # First-visible delivery retains its three-second bound above.
                    # Terminal transport and real durable writes need a separate
                    # bounded cleanup allowance on slow Windows filesystems.
                    await asyncio.wait_for(worker, 10)
                self.assertFalse(errors)
                self.assertEqual(2, len(received))
                self.assertEqual(126, received[1]['max_tokens'])
                self.assertFalse(any('error' in value for value in objects(packets)))
                self.assertEqual(prefix + tail, content(packets))
                with closing(sqlite3.connect(self.db)) as db:
                    saved = json.loads(db.execute('SELECT result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
                self.assertEqual(prefix + tail, saved['choices'][0]['message']['content'])
                self.assertEqual(2, saved['gateway_recovery']['attempts'])

    async def test_reviewer_stays_buffered_until_completion(self):
        release, first_event, received, _ = self.backend()
        packets, first_visible = [], asyncio.Event()
        job_id = 'reviewer-buffered'
        worker = asyncio.create_task(self.observe(self.body(job_id, role='reviewer'), job_id, packets, first_visible))
        try:
            self.assertTrue(await asyncio.to_thread(first_event.wait, 2))
            # Wait through a real progress poll while the backend holds its terminal marker.
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(first_visible.wait(), self.runtime.PROGRESS_POLL_SECONDS * 1.5)
        finally:
            release.set()
            await asyncio.wait_for(worker, 3)
        self.assertEqual('First answer', content(packets))
        self.assertEqual(1, len(received))
        receipt = next(value['gateway_delivery'] for value in objects(packets) if 'gateway_delivery' in value)
        self.assertEqual('completion_buffered', receipt['delivery_mode'])


if __name__ == '__main__':
    unittest.main()
