"""CUDA protocol, queue/cancellation, timing and existing admission regressions.

Synthetic engines exercise orchestration without claiming GPU measurements. The
optional NumPy tests exercise the real backend with a narrowly mocked ORT driver.
"""
import asyncio
import base64
import copy
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from gateway_security import DeviceStore
from v14.config import Config
from v14.identity import Admission
from v14.voice import (BASE, FRONTEND, FRONTEND_SYMBOLS_SHA256, PROTOCOL, PROVIDER, KokoroCudaBackend, VoiceError, VoiceService,
                       Work, executed_cuda_nodes, frontend_symbols, install_voice_routes, load_pack, token_request)
from v14.voice_process import SidecarCudaBackend, child_environment, configuration
from prepare_voice_pack import prepare
from setup_voice_cuda import install

try:
    import numpy as np
except ImportError:
    np = None


def payload(identifier=None):
    return dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=identifier or str(uuid.uuid4()),
                token_ids=[0, 20, 21, 0], word_tokens=[1, 3], speaker=0, speed=1., model_sha256='a' * 64)


def write_pack(directory, model=b'test-model', voices=b'test-voices'):
    root = Path(directory)
    (root / 'model.onnx').write_bytes(model)
    (root / 'voices.bin').write_bytes(voices)
    tokens = json.loads((Path(__file__).with_name('fixtures') / 'voice-frontend-tokens.json').read_text(encoding='utf-8'))['tokens']
    (root / 'tokens.txt').write_text(tokens, encoding='utf-8')
    manifest = dict(protocol=PROTOCOL, frontend_id=FRONTEND, sample_rate=24000, frames_per_duration=600,
                    frontend_symbols_sha256=FRONTEND_SYMBOLS_SHA256,
                    model='model.onnx', voices='voices.bin', model_sha256=hashlib.sha256(model).hexdigest(),
                    voices_sha256=hashlib.sha256(voices).hexdigest(), warmup_token_ids=[0, 20, 21, 0],
                    warmup_word_tokens=[1, 3])
    (root / 'voice-manifest.json').write_text(json.dumps(manifest))
    return manifest


def cuda_event(provider=PROVIDER):
    return dict(cat='Node', ph='X', name='Conv_kernel_time', dur=25, args={'provider': provider})


class VoiceProtocolTests(unittest.TestCase):
    def test_bounded_exact_protocol_and_source_spans(self):
        original = payload()
        projected = token_request(original, 'a' * 64)
        original['token_ids'].clear()
        self.assertEqual(projected['token_ids'], [0, 20, 21, 0])
        invalid = [dict(payload(), text='private text'), dict(payload(), protocol='other'),
                   dict(payload(), frontend_id='other'), dict(payload(), request_id='../id'),
                   dict(payload(), token_ids=[0, True, 0]), dict(payload(), token_ids=[0] * 512),
                   dict(payload(), token_ids=[0, 256, 0]), dict(payload(), word_tokens=[1, 2, 1, 3]),
                   dict(payload(), word_tokens=[1, 4]), dict(payload(), word_tokens=[1, 3, 3]),
                   dict(payload(), word_tokens=[False, 2]), dict(payload(), speaker=-1),
                   dict(payload(), speaker=True), dict(payload(), speed=True),
                   dict(payload(), speed=float('nan')), dict(payload(), speed=float('inf')),
                   dict(payload(), speed=10 ** 500)]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(VoiceError) as failure:
                token_request(value, 'a' * 64)
            self.assertEqual(failure.exception.status, 400)
        with self.assertRaises(VoiceError) as changed:
            token_request(payload(), 'b' * 64)
        self.assertEqual(changed.exception.code, 'VOICE_MODEL_CHANGED')

    def test_pack_is_hash_pinned_and_cannot_load_unbounded_or_outside_files(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = write_pack(directory)
            loaded, artifacts, warmup = load_pack(directory)
            self.assertEqual(loaded, manifest)
            self.assertEqual(artifacts['model'], b'test-model')
            self.assertEqual(warmup['word_tokens'], [1, 3])
            (Path(directory) / 'model.onnx').write_bytes(b'swapped')
            with self.assertRaises(VoiceError) as changed:
                load_pack(directory)
            self.assertEqual(changed.exception.code, 'VOICE_PACK_HASH_MISMATCH')
            for name in ('../model.onnx', '/tmp/model.onnx', 'https://example.com/model'):
                manifest['model'] = name
                (Path(directory) / 'voice-manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(VoiceError):
                    load_pack(directory)

    def test_gpu_claim_requires_an_executed_node_not_an_available_provider(self):
        self.assertEqual(executed_cuda_nodes([cuda_event('CPUExecutionProvider')]), 0)
        self.assertEqual(executed_cuda_nodes([{'args': {'provider': PROVIDER}}, dict(cuda_event(), dur=0)]), 0)
        self.assertEqual(executed_cuda_nodes([cuda_event(), cuda_event('CPUExecutionProvider')]), 1)

    def test_frontend_identity_accepts_line_ordering_but_rejects_permuted_symbol_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            write_pack(directory)
            path = Path(directory) / 'tokens.txt'
            original = path.read_bytes()
            symbols = frontend_symbols(original)
            self.assertEqual(symbols[' '], 16)
            reordered = '\r\n'.join(reversed(original.decode('utf-8').splitlines())).encode('utf-8')
            self.assertEqual(frontend_symbols(reordered), symbols)
            permuted = dict(symbols)
            permuted['h'], permuted['l'] = symbols['l'], symbols['h']
            self.assertNotEqual(sorted(permuted.items()), sorted(symbols.items()))
            for newline in ('\n', '\r\n'):
                swapped = newline.join(f'{symbol} {identifier}' for symbol, identifier in permuted.items()).encode('utf-8')
                path.write_bytes(swapped)
                with self.subTest(newline=repr(newline)), self.assertRaises(VoiceError) as mismatch:
                    load_pack(directory)
                self.assertEqual(mismatch.exception.code, 'VOICE_FRONTEND_MISMATCH')
            (Path(directory) / 'lexicon-us-en.txt').write_text('hello h ə l ˈ O\n', encoding='utf-8')
            with self.assertRaises(VoiceError):
                prepare(directory)

    def test_cancel_before_and_during_native_run_terminates_the_exact_run_options(self):
        work = Work('owner', payload(), None, 0)
        work.cancel()
        first = SimpleNamespace(terminate=False)
        work.options(first)
        self.assertTrue(first.terminate)
        work.options(None)
        second_work = Work('owner', payload(), None, 0)
        second = SimpleNamespace(terminate=False)
        second_work.options(second)
        second_work.cancel()
        self.assertTrue(second.terminate)


class SyntheticBackend:
    model_sha256, executed_cuda_nodes, speakers = 'a' * 64, 7, 2

    def __init__(self, blocking=False):
        self.started, self.release = threading.Event(), threading.Event()
        self.closed, self.calls, self.last_options = False, [], None
        if not blocking:
            self.release.set()

    def synthesize(self, request, work):
        self.calls.append(copy.deepcopy(request))
        self.last_options = SimpleNamespace(terminate=False)
        work.options(self.last_options)
        self.started.set()
        try:
            while not self.release.wait(.002):
                if work.cancelled.is_set():
                    raise RuntimeError('A backend must never expose private inputs in error details')
            if work.cancelled.is_set():
                raise VoiceError('VOICE_CANCELLED', 409)
            return dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=request['request_id'],
                        sample_rate=24000, pcm_f32le=base64.b64encode(struct.pack('<f', .25) * 2400).decode(),
                        durations=[1] * 4, word_tokens=list(request['word_tokens']), model_sha256=self.model_sha256,
                        execution_provider=PROVIDER, executed_cuda_nodes=self.executed_cuda_nodes)
        finally:
            work.options(None)

    def close(self):
        self.closed = True


class VoiceQueueTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.backend = SyntheticBackend(blocking=True)
        self.service = VoiceService('configured', lambda _: self.backend, max_pending=3)
        await self.service.start()

    async def asyncTearDown(self):
        self.backend.release.set()
        await self.service.close()

    async def started(self):
        for _ in range(100):
            if self.backend.started.is_set():
                return
            await asyncio.sleep(.002)
        self.fail('Synthetic worker never started')

    async def test_queue_bound_and_fair_share_keep_other_principal_capacity(self):
        first = asyncio.create_task(self.service.synthesize('owner', payload()))
        await self.started()
        second = asyncio.create_task(self.service.synthesize('owner', payload()))
        await asyncio.sleep(.01)
        with self.assertRaises(VoiceError) as full:
            await self.service.synthesize('owner', payload())
        self.assertEqual(full.exception.code, 'VOICE_QUEUE_FULL')
        third = asyncio.create_task(self.service.synthesize('other', payload()))
        await asyncio.sleep(.01)
        with self.assertRaises(VoiceError):
            await self.service.synthesize('third', payload())
        self.backend.release.set()
        answers = await asyncio.gather(first, second, third)
        self.assertEqual([request['request_id'] for request in self.backend.calls], [answer['request_id'] for answer in answers])
        self.assertFalse(self.service.pending)

    async def test_cancellation_is_principal_scoped_and_can_precede_admission(self):
        request = payload()
        first = asyncio.create_task(self.service.synthesize('owner', request))
        await self.started()
        self.service.cancel('other', request['request_id'])
        self.assertFalse(first.done())
        self.assertFalse(self.backend.last_options.terminate)
        self.service.cancel('owner', request['request_id'])
        with self.assertRaises(VoiceError) as cancelled:
            await first
        self.assertEqual(cancelled.exception.code, 'VOICE_CANCELLED')
        self.assertTrue(self.backend.last_options.terminate)
        request = payload()
        self.service.cancel('owner', request['request_id'])
        with self.assertRaises(VoiceError) as early:
            await self.service.synthesize('owner', request)
        self.assertEqual(early.exception.code, 'VOICE_CANCELLED')
        self.assertEqual(len(self.backend.calls), 1)

    async def test_queued_cancel_never_reaches_native_inference(self):
        first = asyncio.create_task(self.service.synthesize('owner', payload()))
        await self.started()
        queued = payload()
        second = asyncio.create_task(self.service.synthesize('owner', queued))
        await asyncio.sleep(.01)
        self.service.cancel('owner', queued['request_id'])
        with self.assertRaises(VoiceError):
            await second
        self.backend.release.set()
        await first
        await self.service.queue.join()
        self.assertEqual(len(self.backend.calls), 1)

    async def test_timeout_disconnect_and_duplicate_requests_cancel_work(self):
        self.service.timeout = .02
        with self.assertRaises(VoiceError) as timeout:
            await self.service.synthesize('owner', payload())
        self.assertEqual(timeout.exception.code, 'VOICE_TIMEOUT')
        await self.service.queue.join()
        self.assertTrue(self.backend.last_options.terminate)
        self.service.timeout = 2
        request = payload()
        first = asyncio.create_task(self.service.synthesize('owner', request))
        await asyncio.sleep(.01)
        with self.assertRaises(VoiceError) as duplicate:
            await self.service.synthesize('owner', request)
        self.assertEqual(duplicate.exception.code, 'VOICE_DUPLICATE_REQUEST')
        self.service.cancel('owner', request['request_id'])
        with self.assertRaises(VoiceError):
            await first
        await self.service.queue.join()
        async def disconnected():
            return True
        with self.assertRaises(VoiceError) as disconnected_error:
            await self.service.synthesize('owner', payload(), disconnected)
        self.assertEqual(disconnected_error.exception.code, 'VOICE_DISCONNECTED')

    async def test_disabled_failed_and_cpu_only_startup_never_advertise_ready(self):
        disabled = VoiceService()
        await disabled.start()
        self.assertFalse(disabled.capabilities()['ready'])
        self.assertEqual(disabled.capabilities()['reason'], 'VOICE_DISABLED')
        await disabled.close()
        bad = SyntheticBackend()
        bad.executed_cuda_nodes = 0
        cpu = VoiceService('configured', lambda _: bad)
        await cpu.start()
        self.assertFalse(cpu.capabilities()['ready'])
        self.assertEqual(cpu.capabilities()['reason'], 'VOICE_CUDA_NOT_EXECUTED')
        await cpu.close()
        self.assertTrue(bad.closed)

    async def test_background_warmup_keeps_main_lifespan_responsive_and_closes_cleanly(self):
        release = threading.Event()
        backend = SyntheticBackend()
        def load(_):
            release.wait(2)
            return backend
        warming = VoiceService('configured', load)
        @asynccontextmanager
        async def inner(app):
            yield 'main-ready'
        async with warming.lifecycle(inner)(None) as state:
            self.assertEqual(state, 'main-ready')
            self.assertFalse(warming.capabilities()['ready'])
            self.assertEqual(warming.capabilities()['reason'], 'VOICE_WARMING')
            await asyncio.sleep(.01)
            release.set()
        self.assertTrue(warming.closed)
        self.assertTrue(backend.closed)


class VoiceAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = DeviceStore(Path(self.temp.name) / 'auth.db')
        self.config = Config(mode='trusted_proxy', trusted_users={
            'voice@example.com': {'principalId': 'voice', 'scopes': ['chat']},
            'tools@example.com': {'principalId': 'tools', 'scopes': ['tools']}}).validate()
        self.backend = SyntheticBackend()
        self.service = VoiceService('configured', lambda _: self.backend)
        @asynccontextmanager
        async def empty_lifespan(app):
            yield
        self.app = FastAPI(lifespan=self.service.lifecycle(empty_lifespan))
        self.app.add_middleware(Admission, config=self.config, store=self.store)
        install_voice_routes(self.app, self.service)

    def tearDown(self):
        self.temp.cleanup()

    def client(self):
        return TestClient(self.app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 1234))

    def ready(self, client):
        for _ in range(100):
            response = client.get(BASE + '/capabilities', headers={'Tailscale-User-Login': 'voice@example.com'})
            if response.json().get('ready'):
                return
            time.sleep(.002)
        self.fail('Synthetic startup did not finish')

    def test_remote_chat_grant_has_only_exact_voice_routes_and_no_tool_escalation(self):
        headers = {'Tailscale-User-Login': 'voice@example.com'}
        with self.client() as client:
            self.ready(client)
            self.assertEqual(client.get(BASE + '/capabilities').status_code, 401)
            capabilities = client.get(BASE + '/capabilities', headers=headers)
            self.assertTrue(capabilities.json()['ready'])
            self.assertEqual(capabilities.headers['cache-control'], 'no-store')
            request = payload()
            response = client.post(BASE + '/synthesize', json=request, headers=headers)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()['request_id'], request['request_id'])
            self.assertEqual(response.json()['word_tokens'], request['word_tokens'])
            self.assertEqual(client.post(BASE + '/cancel', json={'request_id': str(uuid.uuid4())}, headers=headers).status_code, 200)
            for path, method in ((BASE + '/models', 'get'), (BASE + '/capabilities', 'post'), (BASE + '/synthesize', 'get'),
                                 (BASE + '/synthesize/extra', 'post'), ('/chat/completions', 'post')):
                self.assertEqual(getattr(client, method)(path, headers=headers).status_code, 403)
            self.assertEqual(client.get(BASE + '/capabilities', headers={'Tailscale-User-Login': 'tools@example.com'}).status_code, 403)

    def test_browser_csrf_body_limits_model_swap_and_duplicate_fields_fail_closed(self):
        headers = {'Tailscale-User-Login': 'voice@example.com', 'Origin': 'http://127.0.0.1:8090'}
        with self.client() as client:
            self.ready(client)
            self.assertEqual(client.post(BASE + '/synthesize', json=payload(), headers=headers).status_code, 403)
            headers['X-Gateway-CSRF'] = client.get('/gateway/session', headers=headers).json()['csrfToken']
            self.assertEqual(client.post(BASE + '/synthesize', json=dict(payload(), model_sha256='b' * 64), headers=headers).status_code, 409)
            self.assertEqual(client.post(BASE + '/synthesize', content=b' ' * 16385, headers=headers).status_code, 413)
            self.assertEqual(client.post(BASE + '/cancel', content='{"request_id":"a","request_id":"b"}', headers=headers).status_code, 400)
            self.assertEqual(client.post(BASE + '/cancel', json={'request_id': 'not-a-uuid'}, headers=headers).status_code, 400)
            self.assertEqual(len(self.backend.calls), 0)


@unittest.skipIf(np is None, 'Install optional Voice NumPy dependency to exercise ORT boundary validation')
class VoiceNativeBoundaryTests(unittest.TestCase):
    def driver(self, provider=PROVIDER, output=None):
        sessions = []
        class Options:
            def add_session_config_entry(self, *_):
                pass
        class Session:
            def __init__(self, data, sess_options, providers):
                self.options, self.fallback, self.feeds = sess_options, True, []
                sessions.append(self)
            def disable_fallback(self):
                self.fallback = False
            def get_providers(self):
                return [PROVIDER, 'CPUExecutionProvider']
            def get_modelmeta(self):
                return SimpleNamespace(custom_metadata_map={'style_dim': '510,1,256', 'n_speakers': '1', 'sample_rate': '24000'})
            def get_inputs(self):
                return [SimpleNamespace(name=name, type=kind) for name, kind in (
                    ('tokens', 'tensor(int64)'), ('style', 'tensor(float)'), ('speed', 'tensor(float)'))]
            def get_outputs(self):
                return [SimpleNamespace(name='audio', type='tensor(float)'), SimpleNamespace(name='durations', type='tensor(int64)')]
            def run(self, names, feeds, options):
                self.feeds.append(feeds)
                return output or [np.ones(2400, dtype=np.float32) / 4, np.ones(4, dtype=np.int64)]
            def end_profiling(self):
                path = self.options.profile_file_prefix + '.json'
                Path(path).write_text(json.dumps([cuda_event(provider)]))
                return path
        driver = SimpleNamespace(SessionOptions=Options, RunOptions=Options, InferenceSession=Session,
                                 get_available_providers=lambda: [PROVIDER, 'CPUExecutionProvider'])
        return driver, sessions

    def test_warmed_session_proves_cuda_and_preserves_native_style_timing_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            voices = np.arange(510 * 256, dtype=np.float32).tobytes()
            manifest = write_pack(directory, voices=voices)
            driver, sessions = self.driver()
            with patch.dict('sys.modules', {'onnxruntime': driver}):
                backend = KokoroCudaBackend(directory)
                self.assertFalse(sessions[0].fallback)
                self.assertEqual(backend.executed_cuda_nodes, 1)
                request = dict(payload(), model_sha256=manifest['model_sha256'])
                reply = backend.synthesize(request, Work('owner', request, None, 0))
                self.assertEqual(reply['durations'], [1, 1, 1, 1])
                self.assertEqual(len(base64.b64decode(reply['pcm_f32le'])), 2400 * 4)
                self.assertEqual(float(sessions[0].feeds[-1]['style'][0, 0]), 2 * 256)
                self.assertEqual(reply['evidence_scope'], 'warmed_session')
                backend.close()

    def test_cpu_fallback_nonfinite_or_inconsistent_timing_never_returns_audio(self):
        cases = [('CPUExecutionProvider', None),
                 (PROVIDER, [np.full(2400, np.nan, dtype=np.float32), np.ones(4, dtype=np.int64)]),
                 (PROVIDER, [np.zeros(2400, dtype=np.float32), np.asarray([1, 1, 1, 2], dtype=np.int64)]),
                 (PROVIDER, [np.zeros(2400, dtype=np.float32), np.asarray([0, 1, 1, 2], dtype=np.int64)])]
        for provider, output in cases:
            with self.subTest(provider=provider, output=output), tempfile.TemporaryDirectory() as directory:
                write_pack(directory, voices=np.zeros(510 * 256, dtype=np.float32).tobytes())
                driver, _ = self.driver(provider, output)
                with patch.dict('sys.modules', {'onnxruntime': driver}), self.assertRaises(VoiceError):
                    KokoroCudaBackend(directory)


class VoiceSidecarTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.worker = Path(self.temp.name) / 'synthetic_worker.py'
        self.worker.write_text('''import argparse, base64, struct, sys, time
from pathlib import Path
sys.path.insert(0, SOURCE)
from voice_worker import serve
from v14.voice import VoiceError, PROTOCOL, FRONTEND, PROVIDER
class Synthetic:
    model_sha256, executed_cuda_nodes, speakers = 'a' * 64, 2, 2
    def __init__(self, directory): pass
    def close(self): pass
    def synthesize(self, request, work):
        if request['speed'] == .5:
            while not work.cancelled.wait(.002): pass
            raise VoiceError('VOICE_CANCELLED', 409)
        if request['speed'] == 1.5:
            time.sleep(10)
        return dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=request['request_id'],
            model_sha256=self.model_sha256, execution_provider=PROVIDER, executed_cuda_nodes=2,
            sample_rate=24000, durations=[1]*4, word_tokens=request['word_tokens'],
            pcm_f32le=base64.b64encode(struct.pack('<f', .25)*2400).decode())
parser=argparse.ArgumentParser()
parser.add_argument('--directory')
serve(parser.parse_args().directory, factory=Synthetic)
'''.replace('SOURCE', repr(str(Path(__file__).resolve().parents[1]))))

    def tearDown(self):
        self.temp.cleanup()

    def test_real_isolated_child_supports_repeated_requests_and_native_cancel(self):
        from concurrent.futures import ThreadPoolExecutor
        backend = SidecarCudaBackend(self.temp.name, sys.executable, self.worker)
        try:
            for _ in range(10):
                request = payload()
                response = backend.synthesize(request, Work('owner', request, None, time.monotonic() + 5))
                self.assertEqual(response['request_id'], request['request_id'])
                self.assertEqual(response['durations'], [1] * 4)
            request = dict(payload(), speed=.5)
            work = Work('owner', request, None, time.monotonic() + 5)
            with ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(backend.synthesize, request, work)
                for _ in range(100):
                    if work._options is not None:
                        break
                    time.sleep(.002)
                work.cancel()
                with self.assertRaises(VoiceError) as cancelled:
                    future.result(timeout=2)
                self.assertEqual(cancelled.exception.code, 'VOICE_CANCELLED')
        finally:
            backend.close()
        self.assertIsNotNone(backend.process.poll())

    def test_stuck_child_is_killed_at_request_deadline(self):
        backend = SidecarCudaBackend(self.temp.name, sys.executable, self.worker)
        try:
            request = dict(payload(), speed=1.5)
            with self.assertRaises(VoiceError) as timeout:
                backend.synthesize(request, Work('owner', request, None, time.monotonic() + .025))
            self.assertEqual(timeout.exception.code, 'VOICE_TIMEOUT')
            backend.process.wait(timeout=2)
            self.assertFalse(backend.ready)
        finally:
            backend.close()

    def test_stuck_warmup_cancels_and_malformed_or_cpu_only_handshake_is_rejected(self):
        from concurrent.futures import ThreadPoolExecutor
        self.worker.write_text('import time\ntime.sleep(30)\n')
        stopping = threading.Event()
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(SidecarCudaBackend, self.temp.name, sys.executable, self.worker, stopping)
            stopping.set()
            with self.assertRaises(VoiceError) as cancelled:
                future.result(timeout=3)
            self.assertEqual(cancelled.exception.code, 'VOICE_CANCELLED')
        for value in ({'ready': True, 'executed_cuda_nodes': 0}, []):
            self.worker.write_text('import time\nprint(' + repr(json.dumps(value)) + ', flush=True)\ntime.sleep(30)\n')
            with self.subTest(value=value), self.assertRaises(VoiceError):
                SidecarCudaBackend(self.temp.name, sys.executable, self.worker)

    def test_worker_inherits_only_os_and_cuda_paths(self):
        with patch.dict(os.environ, {'PATH': '/usr/bin', 'GATEWAY_MANAGEMENT_TOKEN': 'not-for-worker',
                                     'OPENAI_API_KEY': 'not-for-worker', 'PYTHONPATH': '/untrusted',
                                     'CUDA_PATH': '/cuda', 'LD_LIBRARY_PATH': '/driver'}, clear=True):
            env = child_environment()
            self.assertEqual(env, {'PATH': '/usr/bin', 'CUDA_PATH': '/cuda', 'LD_LIBRARY_PATH': '/driver'})

    def test_bootstrap_imports_need_no_http_or_gpu_packages_in_fresh_python(self):
        source = str(Path(__file__).resolve().parents[1])
        code = 'import sys; sys.path.insert(0, ' + repr(source) + '); import prepare_voice_pack, setup_voice_cuda, voice_worker; '
        code += 'assert not {"fastapi", "numpy", "onnxruntime"}.intersection(sys.modules)'
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_managed_host_uses_explicit_saved_addon_configuration(self):
        root = Path(self.temp.name)
        (root / 'data').mkdir()
        interpreter = root / 'addons' / 'voice-cuda' / 'venv' / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
        interpreter.parent.mkdir(parents=True)
        interpreter.touch()
        setting = root / 'data' / 'voice-cuda.json'
        with patch.dict(os.environ, {'GATEWAY_DESKTOP_ROOT': str(root), 'GATEWAY_VOICE_PYTHON': '/inherited',
                                     'GATEWAY_VOICE_MODEL_DIR': '/inherited'}):
            self.assertEqual(configuration(), (None, None))
            config = dict(schema_version=1, enabled=True, python=str(interpreter), model_directory=str(root))
            setting.write_text(json.dumps(config))
            expected = (str(root.resolve()), str(interpreter.parent.resolve() / interpreter.name))
            self.assertEqual(configuration(), expected)
            setting.write_text(json.dumps(dict(config, python=sys.executable)))
            self.assertEqual(configuration(), (None, None))
            setting.write_text(json.dumps(dict(config, enabled=False)))
            self.assertEqual(configuration(), (None, None))
            if os.name != 'nt':
                # Real POSIX venvs commonly link bin/python to the system binary.
                # The managed entry point stays valid, but the target is not an
                # interchangeable configured interpreter outside that venv.
                interpreter.unlink()
                interpreter.symlink_to(sys.executable)
                setting.write_text(json.dumps(config))
                self.assertEqual(configuration(), expected)
                setting.write_text(json.dumps(dict(config, python=sys.executable)))
                self.assertEqual(configuration(), (None, None))
                alias = root / 'directory-alias'
                alias.symlink_to(root, target_is_directory=True)
                setting.write_text(json.dumps(dict(config, python=str(alias / interpreter.relative_to(root)), model_directory=str(alias))))
                with patch.dict(os.environ, {'GATEWAY_DESKTOP_ROOT': str(alias)}):
                    self.assertEqual(configuration(), expected)

    def test_oversize_child_frame_is_bounded_and_child_is_stopped(self):
        from v14.voice_process import MAX_RESPONSE_BYTES
        self.worker.write_text('import sys,time\nsys.stdout.write("x"*' + str(MAX_RESPONSE_BYTES + 1) + ')\nsys.stdout.flush()\ntime.sleep(30)\n')
        with self.assertRaises(VoiceError) as failure:
            SidecarCudaBackend(self.temp.name, sys.executable, self.worker)
        self.assertEqual(failure.exception.code, 'VOICE_WORKER_UNAVAILABLE')


class VoiceSetupTests(unittest.TestCase):
    def test_manifest_encodes_fixed_publisher_word_and_setup_enables_only_after_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'data').mkdir()
            (root / 'current.json').write_text('{"version":"18.1.0"}')
            model = root / 'models'
            model.mkdir()
            write_pack(model)
            (model / 'lexicon-us-en.txt').write_text('hello h ə l ˈ O\n', encoding='utf-8')
            manifest = prepare(model)
            self.assertEqual(manifest['warmup_token_ids'], [0, 50, 83, 54, 156, 31, 0])
            self.assertEqual(manifest['warmup_word_tokens'], [1, 6])
            original = (model / 'voice-manifest.json').read_bytes()
            (model / 'candidate.onnx').write_bytes(b'a newly selected model')
            with patch('setup_voice_cuda.subprocess.run') as process, patch('setup_voice_cuda.SidecarCudaBackend', side_effect=VoiceError('VOICE_CUDA_NOT_EXECUTED')):
                with self.assertRaises(VoiceError):
                    install(root, model, sys.executable, model='candidate.onnx')
                self.assertFalse((root / 'data' / 'voice-cuda.json').exists())
                self.assertEqual((model / 'voice-manifest.json').read_bytes(), original)
                self.assertIn('--require-hashes', process.call_args_list[-1].args[0])
            backend = SyntheticBackend()
            backend.model_sha256 = manifest['model_sha256']
            with patch('setup_voice_cuda.subprocess.run'), patch('setup_voice_cuda.SidecarCudaBackend', return_value=backend):
                evidence = install(root, model, sys.executable)
            self.assertEqual(evidence['model_sha256'], manifest['model_sha256'])
            self.assertTrue(backend.closed)
            enabled = json.loads((root / 'data' / 'voice-cuda.json').read_text())
            self.assertIs(enabled['enabled'], True)
            self.assertEqual(enabled['model_directory'], str(model.resolve()))


if __name__ == '__main__':
    unittest.main()
