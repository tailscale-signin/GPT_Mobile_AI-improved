"""Optional, ephemeral Kokoro CUDA synthesis under the normal Gateway admission.

No transcript is sent to this service. Token IDs, audio, and request IDs are held
only for an in-flight request; the short cancellation tombstones contain IDs only.
The optional ONNX/Numpy dependencies are loaded only when an operator enables it.
"""
import asyncio
import base64
import hashlib
import hmac
import json
import math
import os
import re
import struct
import sys
import tempfile
import threading
import time
import uuid
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

BASE = '/gateway/voice'
PROTOCOL = 'kokoro-timed-v1'
FRONTEND = 'kokoro-english-v1'
# Canonical JSON of sorted (symbol, ID) pairs from the exact Android pack's
# tokens.txt (raw SHA-256 6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425).
FRONTEND_SYMBOLS_SHA256 = 'ef67465bdf5b8a147574207fdf8eaba3d7b913161440f0896bb0dd90c04ac3f6'
PROVIDER = 'CUDAExecutionProvider'
SAMPLE_RATE = 24000
FRAMES_PER_DURATION = 600
MAX_TOKEN_IDS = 511
MAX_WORDS = 64
MAX_AUDIO_FRAMES = 30 * SAMPLE_RATE
MAX_BODY_BYTES = 16384
PCM_STREAM_PROTOCOL = 'kokoro-pcm-stream-v1'
PCM_STREAM_TYPE = 'application/vnd.gptmobile.kokoro-pcm'
PCM_STREAM_MAGIC = b'KPCM\x01\x00\x00\x00'
PCM_CHUNK_FRAMES = 4096
SHA256 = re.compile(r'[0-9a-f]{64}')
REQUEST_FIELDS = {'protocol', 'frontend_id', 'request_id', 'token_ids', 'word_tokens',
                  'speaker', 'speed', 'model_sha256'}


class VoiceError(Exception):
    def __init__(self, code, status=503):
        self.code, self.status = code, status
        super().__init__(code)


class BinarySpeech:
    """One validated native phrase; transport chunks are not neural streaming."""
    def __init__(self, metadata, pcm):
        if not isinstance(pcm, bytes) or not 0 < len(pcm) <= MAX_AUDIO_FRAMES * 4 or len(pcm) % 4:
            raise VoiceError('VOICE_TIMING_INVALID')
        durations = metadata.get('durations')
        if (not isinstance(durations, list) or not 3 <= len(durations) <= MAX_TOKEN_IDS
                or any(type(value) is not int or not 1 <= value <= 1200 for value in durations)
                or sum(durations) * FRAMES_PER_DURATION * 4 != len(pcm)):
            raise VoiceError('VOICE_TIMING_INVALID')
        self.metadata, self.pcm = dict(metadata), pcm

    def header(self):
        metadata = dict(self.metadata, stream_protocol=PCM_STREAM_PROTOCOL, pcm_encoding='f32le', channels=1,
                        generation_mode='complete_phrase', audio_frames=len(self.pcm) // 4,
                        max_chunk_frames=PCM_CHUNK_FRAMES)
        encoded = json.dumps(metadata, separators=(',', ':'), allow_nan=False).encode('utf-8')
        if not 0 < len(encoded) <= MAX_BODY_BYTES:
            raise VoiceError('VOICE_TIMING_INVALID')
        return PCM_STREAM_MAGIC + struct.pack('<I', len(encoded)) + encoded

    def chunks(self):
        pcm = memoryview(self.pcm)
        for offset in range(0, len(pcm), PCM_CHUNK_FRAMES * 4):
            chunk = pcm[offset:offset + PCM_CHUNK_FRAMES * 4]
            yield struct.pack('<I', len(chunk) // 4) + chunk
        yield struct.pack('<I', 0)


def frontend_symbols(data):
    """Bind the frontend label to the phone's exact symbol-to-ID mapping."""
    try:
        symbols, identifiers = {}, set()
        for row in data.decode('utf-8').splitlines():
            if not row:
                continue
            symbol, separator, encoded = row.rpartition(' ')
            if not symbol or not separator or not re.fullmatch(r'[0-9]+', encoded):
                raise ValueError()
            identifier = int(encoded)
            if symbol in symbols or identifier in identifiers or not 1 <= identifier <= 255:
                raise ValueError()
            symbols[symbol] = identifier
            identifiers.add(identifier)
        canonical = json.dumps(sorted(symbols.items()), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        if not hmac.compare_digest(hashlib.sha256(canonical).hexdigest(), FRONTEND_SYMBOLS_SHA256):
            raise ValueError()
        return symbols
    except (ValueError, UnicodeError):
        raise VoiceError('VOICE_FRONTEND_MISMATCH') from None


def request_id(value):
    if not isinstance(value, str) or len(value) != 36:
        raise VoiceError('VOICE_INVALID_REQUEST', 400)
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except ValueError:
        raise VoiceError('VOICE_INVALID_REQUEST', 400) from None
    return value


def token_request(payload, model_sha256, speakers=256):
    if (not isinstance(payload, dict) or set(payload) != REQUEST_FIELDS
            or payload['protocol'] != PROTOCOL or payload['frontend_id'] != FRONTEND):
        raise VoiceError('VOICE_INVALID_REQUEST', 400)
    request_id(payload['request_id'])
    if (not isinstance(payload['model_sha256'], str) or not SHA256.fullmatch(payload['model_sha256'])
            or not hmac.compare_digest(payload['model_sha256'], model_sha256)):
        raise VoiceError('VOICE_MODEL_CHANGED', 409)
    tokens, words = payload['token_ids'], payload['word_tokens']
    if (not isinstance(tokens, list) or not 3 <= len(tokens) <= MAX_TOKEN_IDS
            or any(type(token) is not int or not 0 <= token <= 255 for token in tokens)
            or tokens[0] != 0 or tokens[-1] != 0
            or not isinstance(words, list) or not 2 <= len(words) <= MAX_WORDS * 2 or len(words) % 2):
        raise VoiceError('VOICE_INVALID_REQUEST', 400)
    end = 1
    for start, stop in zip(words[::2], words[1::2]):
        if type(start) is not int or type(stop) is not int or not end <= start < stop <= len(tokens) - 1:
            raise VoiceError('VOICE_INVALID_REQUEST', 400)
        end = stop
    if (type(payload['speaker']) is not int or not 0 <= payload['speaker'] < speakers
            or type(payload['speed']) not in (int, float) or not .5 <= payload['speed'] <= 2
            or not math.isfinite(payload['speed'])):
        raise VoiceError('VOICE_INVALID_REQUEST', 400)
    # Own the small arrays so the worker never observes caller mutation.
    return dict(payload, token_ids=list(tokens), word_tokens=list(words))


async def bounded_json(request):
    body = bytearray()
    async for part in request.stream():
        if len(body) + len(part) > MAX_BODY_BYTES:
            raise VoiceError('VOICE_REQUEST_TOO_LARGE', 413)
        body.extend(part)
    try:
        def unique_object(pairs):
            value = dict(pairs)
            if len(value) != len(pairs):
                raise ValueError()
            return value
        return json.loads(body, object_pairs_hook=unique_object)
    except (ValueError, RecursionError):
        raise VoiceError('VOICE_INVALID_REQUEST', 400) from None


def _artifact_path(directory, name, maximum):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', name):
        raise VoiceError('VOICE_PACK_INVALID')
    path = (directory / name).resolve()
    if path.parent != directory or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise VoiceError('VOICE_PACK_INVALID')
    return path


def _artifact(directory, name, maximum):
    with _artifact_path(directory, name, maximum).open('rb') as source:
        size = os.fstat(source.fileno()).st_size
        if not 0 < size <= maximum:
            raise VoiceError('VOICE_PACK_INVALID')
        data = source.read(size)
        if len(data) != size or source.read(1):
            raise VoiceError('VOICE_PACK_INVALID')
        return data


def load_pack(directory, *, candidate=None):
    directory = Path(directory).resolve()
    try:
        # Setup verifies its candidate before replacing a working manifest. Inference
        # owns the verified bytes it passes to ONNX Runtime.
        manifest = json.loads(_artifact(directory, 'voice-manifest.json', MAX_BODY_BYTES)) if candidate is None else candidate
        if (manifest['protocol'] != PROTOCOL or manifest['frontend_id'] != FRONTEND
                or manifest['sample_rate'] != SAMPLE_RATE or manifest['frames_per_duration'] != FRAMES_PER_DURATION):
            raise VoiceError('VOICE_PACK_INVALID')
        if manifest.get('frontend_symbols_sha256') != FRONTEND_SYMBOLS_SHA256:
            raise VoiceError('VOICE_FRONTEND_MISMATCH')
        frontend_symbols(_artifact(directory, 'tokens.txt', 65536))
        artifacts = {}
        for kind, maximum in (('model', 768 * 1024 * 1024), ('voices', 256 * 1024 * 1024)):
            checksum = manifest[kind + '_sha256']
            if not isinstance(checksum, str) or not SHA256.fullmatch(checksum):
                raise VoiceError('VOICE_PACK_INVALID')
            if candidate is None:
                data = _artifact(directory, manifest[kind], maximum)
                digest = hashlib.sha256(data)
                artifacts[kind] = data
            else:
                with _artifact_path(directory, manifest[kind], maximum).open('rb') as source:
                    digest = hashlib.sha256()
                    size = 0
                    while chunk := source.read(1024 * 1024):
                        size += len(chunk)
                        if size > maximum:
                            raise VoiceError('VOICE_PACK_INVALID')
                        digest.update(chunk)
                    if size == 0:
                        raise VoiceError('VOICE_PACK_INVALID')
            if not hmac.compare_digest(digest.hexdigest(), checksum):
                raise VoiceError('VOICE_PACK_HASH_MISMATCH')
        warmup = token_request(dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=str(uuid.uuid4()),
                                    token_ids=manifest['warmup_token_ids'], word_tokens=manifest['warmup_word_tokens'],
                                    speaker=0, speed=1.0, model_sha256=manifest['model_sha256']), manifest['model_sha256'])
        return manifest, artifacts, warmup
    except (OSError, ValueError, KeyError, TypeError, RecursionError):
        raise VoiceError('VOICE_PACK_INVALID') from None


def executed_cuda_nodes(events):
    if not isinstance(events, list):
        return 0
    return sum(1 for event in events if isinstance(event, dict) and event.get('cat') == 'Node'
               and event.get('ph') == 'X' and event.get('name', '').endswith('_kernel_time')
               and isinstance(event.get('args'), dict) and event['args'].get('provider') == PROVIDER
               and type(event.get('dur')) in (int, float) and event['dur'] > 0)


class Work:
    """Cancellation can race queue admission, native Run, or transport teardown."""
    def __init__(self, principal, payload, completion, deadline, binary=False):
        self.principal, self.payload, self.completion, self.deadline = principal, payload, completion, deadline
        self.key = principal, payload['request_id']
        self.binary = binary
        self.cancelled = threading.Event()
        self._lock, self._options = threading.Lock(), None

    def cancel(self):
        with self._lock:
            self.cancelled.set()
            if self._options is not None:
                self._options.terminate = True

    def options(self, options):
        with self._lock:
            self._options = options
            if options is not None and self.cancelled.is_set():
                options.terminate = True


class KokoroCudaBackend:
    """One warmed, measured CUDA session; no transparent runtime CPU fallback."""
    def __init__(self, directory):
        import numpy as np
        import onnxruntime as ort
        ort.disable_telemetry_events()
        self.np, self.ort, self.session = np, ort, None
        if PROVIDER not in ort.get_available_providers():
            raise VoiceError('VOICE_CUDA_UNAVAILABLE')
        manifest, artifacts, warmup = load_pack(directory)
        self.model_sha256 = manifest['model_sha256']
        with tempfile.TemporaryDirectory(prefix='gateway-voice-qualification-') as scratch:
            options = ort.SessionOptions()
            options.intra_op_num_threads = 2
            options.inter_op_num_threads = 1
            options.add_session_config_entry('session.intra_op.allow_spinning', '0')
            options.add_session_config_entry('session.inter_op.allow_spinning', '0')
            options.enable_profiling = True
            options.profile_file_prefix = str(Path(scratch) / 'warmup')
            options.log_severity_level = 3
            # Loading bytes rejects unpinned external-data sidecars: the protocol
            # deliberately accepts self-contained timed ONNX exports only.
            self.session = ort.InferenceSession(artifacts['model'], sess_options=options,
                                                providers=[PROVIDER, 'CPUExecutionProvider'])
            self.session.disable_fallback()
            try:
                self._metadata(artifacts['voices'])
                self._run(warmup, Work('', warmup, None, float('inf')))
            finally:
                profile = self.session.end_profiling()
            profile_data = _artifact(Path(scratch).resolve(), Path(profile).name, 32 * 1024 * 1024)
            self.executed_cuda_nodes = executed_cuda_nodes(json.loads(profile_data))
            if self.executed_cuda_nodes <= 0 or PROVIDER not in self.session.get_providers():
                self.close()
                raise VoiceError('VOICE_CUDA_NOT_EXECUTED')

    def _metadata(self, voices):
        metadata = self.session.get_modelmeta().custom_metadata_map
        try:
            dimensions = [int(value.strip()) for value in metadata['style_dim'].split(',')]
            self.speakers = int(metadata['n_speakers'])
            if (int(metadata['sample_rate']) != SAMPLE_RATE or dimensions != [510, 1, 256]
                    or not 1 <= self.speakers <= 256 or len(voices) != self.speakers * 510 * 256 * 4):
                raise ValueError()
            inputs, outputs = self.session.get_inputs(), self.session.get_outputs()
            if ([item.type for item in inputs] != ['tensor(int64)', 'tensor(float)', 'tensor(float)']
                    or [item.type for item in outputs] != ['tensor(float)', 'tensor(int64)']):
                raise ValueError()
            self.input_names, self.output_names = [item.name for item in inputs], [item.name for item in outputs]
            self.styles = self.np.frombuffer(voices, dtype='<f4').reshape(self.speakers, 510, 256)
            if not self.np.isfinite(self.styles).all():
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            raise VoiceError('VOICE_PACK_INVALID') from None

    def _run(self, payload, work):
        np = self.np
        if work.cancelled.is_set():
            raise VoiceError('VOICE_CANCELLED', 409)
        if PROVIDER not in self.session.get_providers():
            raise VoiceError('VOICE_CUDA_UNAVAILABLE')
        tokens = payload['token_ids']
        options = self.ort.RunOptions()
        options.log_severity_level = 3
        work.options(options)
        try:
            values = self.session.run(self.output_names, dict(zip(self.input_names, (
                np.asarray([tokens], dtype=np.int64),
                self.styles[payload['speaker'], len(tokens) - 2].reshape(1, 256),
                np.asarray([payload['speed']], dtype=np.float32)))), options)
        finally:
            work.options(None)
        if work.cancelled.is_set():
            raise VoiceError('VOICE_CANCELLED', 409)
        if len(values) != 2:
            raise VoiceError('VOICE_TIMING_INVALID')
        samples, durations = values[0].reshape(-1), values[1].reshape(-1)
        if (samples.dtype != np.float32 or durations.dtype != np.int64
                or not 0 < samples.size <= MAX_AUDIO_FRAMES or durations.size != len(tokens)
                or not np.isfinite(samples).all() or not (durations > 0).all()
                or not (durations <= MAX_AUDIO_FRAMES // FRAMES_PER_DURATION).all()
                or sum(int(value) for value in durations) * FRAMES_PER_DURATION != samples.size):
            raise VoiceError('VOICE_TIMING_INVALID')
        return samples.astype('<f4', copy=False).tobytes(), durations.tolist()

    def synthesize_binary(self, payload, work):
        started = time.monotonic()
        pcm, durations = self._run(payload, work)
        return BinarySpeech(dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=payload['request_id'],
                                 frontend_symbols_sha256=FRONTEND_SYMBOLS_SHA256,
                                 sample_rate=SAMPLE_RATE, frames_per_duration=FRAMES_PER_DURATION, durations=durations,
                                 word_tokens=payload['word_tokens'], model_sha256=self.model_sha256,
                                 execution_provider=PROVIDER, executed_cuda_nodes=self.executed_cuda_nodes,
                                 evidence_scope='warmed_session', synthesis_ms=round((time.monotonic() - started) * 1000)), pcm)

    def synthesize(self, payload, work):
        speech = self.synthesize_binary(payload, work)
        return dict(speech.metadata, pcm_f32le=base64.b64encode(speech.pcm).decode('ascii'))

    def close(self):
        self.session = None
        self.styles = None


class VoiceService:
    def __init__(self, directory=None, backend_factory=KokoroCudaBackend, max_pending=8, per_principal=2, timeout=25):
        self.directory, self.backend_factory = directory, backend_factory
        self.max_pending, self.per_principal, self.timeout = max_pending, per_principal, timeout
        self.backend, self.executor, self.worker = None, None, None
        self.queue = asyncio.Queue(max_pending)
        self.pending, self.cancelled = {}, OrderedDict()
        # Binary responses retain admission until transport completion, so slow
        # readers cannot accumulate unbounded completed PCM outside the queue.
        self.streams = {}
        self.reason = 'VOICE_DISABLED' if not directory else 'VOICE_WARMING'
        self.closed = False
        self.stopping = threading.Event()
        self.startup = None

    @classmethod
    def from_env(cls):
        from .voice_process import SidecarCudaBackend, configuration
        directory, interpreter = configuration()
        service = cls(directory)
        if directory:
            # Even source deployments use a child, which makes a stalled model
            # load cancellable and keeps CUDA libraries out of the HTTP process.
            service.backend_factory = lambda path: SidecarCudaBackend(path, interpreter or sys.executable, stopping=service.stopping)
        return service

    async def start(self):
        if not self.directory or self.closed or self.executor is not None:
            return
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='gateway-voice-cuda')
        try:
            self.backend = await asyncio.get_running_loop().run_in_executor(self.executor, self.backend_factory, self.directory)
            if self.closed:
                self.backend.close()
                self.backend = None
                return
            if self.backend.executed_cuda_nodes <= 0:
                raise VoiceError('VOICE_CUDA_NOT_EXECUTED')
            self.reason = None
            self.worker = asyncio.create_task(self._worker())
        except Exception as error:
            self.reason = error.code if isinstance(error, VoiceError) else 'VOICE_CUDA_UNAVAILABLE'
            if self.backend is not None:
                self.backend.close()
                self.backend = None

    def capabilities(self):
        result = dict(protocol=PROTOCOL, frontend_id=FRONTEND, ready=self.backend is not None and not self.closed and getattr(self.backend, 'ready', True),
                      frontend_symbols_sha256=FRONTEND_SYMBOLS_SHA256,
                      sample_rate=SAMPLE_RATE, frames_per_duration=FRAMES_PER_DURATION, execution_provider=PROVIDER,
                      max_token_ids=MAX_TOKEN_IDS, max_words=MAX_WORDS, max_audio_frames=MAX_AUDIO_FRAMES)
        if result['ready']:
            result.update(model_sha256=self.backend.model_sha256, executed_cuda_nodes=self.backend.executed_cuda_nodes,
                          speakers=self.backend.speakers, evidence_scope='warmed_session')
            if callable(getattr(self.backend, 'synthesize_binary', None)):
                result.update(stream_protocol=PCM_STREAM_PROTOCOL, generation_mode='complete_phrase')
        else:
            result.update(reason=self.reason or 'VOICE_WORKER_UNAVAILABLE', executed_cuda_nodes=0)
        return result

    def _remember_cancel(self, key):
        now = time.monotonic()
        self.cancelled[key] = now
        self.cancelled.move_to_end(key)
        while self.cancelled and (len(self.cancelled) > 256 or now - next(iter(self.cancelled.values())) > 60):
            self.cancelled.popitem(last=False)

    def cancel(self, principal, identifier):
        key = principal, request_id(identifier)
        self._remember_cancel(key)
        work = self.pending.get(key) or self.streams.get(key)
        if work:
            work.cancel()
            if not work.completion.done():
                work.completion.set_exception(VoiceError('VOICE_CANCELLED', 409))
        # A different principal cannot infer whether an ID is in flight.
        return dict(protocol=PROTOCOL, frontend_id=FRONTEND, request_id=identifier, cancelled=True)

    def release_stream(self, work):
        if self.streams.get(work.key) is work:
            self.streams.pop(work.key)

    def stream_cancelled(self, work):
        return self.closed or self.streams.get(work.key) is not work or work.cancelled.is_set()

    async def synthesize(self, principal, payload, disconnected=None, binary=False):
        if self.backend is None or self.closed or not getattr(self.backend, 'ready', True):
            raise VoiceError(self.reason or 'VOICE_WORKER_UNAVAILABLE')
        if binary and not callable(getattr(self.backend, 'synthesize_binary', None)):
            raise VoiceError('VOICE_STREAM_UNAVAILABLE')
        payload = token_request(payload, self.backend.model_sha256, self.backend.speakers)
        key = principal, payload['request_id']
        cancelled_at = self.cancelled.get(key)
        if cancelled_at is not None and time.monotonic() - cancelled_at <= 60:
            raise VoiceError('VOICE_CANCELLED', 409)
        admitted = self.pending.keys() | self.streams.keys()
        if key in admitted:
            raise VoiceError('VOICE_DUPLICATE_REQUEST', 409)
        if len(admitted) >= self.max_pending or sum(owner == principal for owner, _ in admitted) >= self.per_principal:
            raise VoiceError('VOICE_QUEUE_FULL', 429)
        completion = asyncio.get_running_loop().create_future()
        deadline = time.monotonic() + self.timeout
        work = Work(principal, payload, completion, deadline, binary=binary)
        self.pending[key] = work
        if binary:
            self.streams[key] = work
        self.queue.put_nowait(work)
        delivered = False
        try:
            while not completion.done():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise VoiceError('VOICE_TIMEOUT', 504)
                await asyncio.wait((completion,), timeout=min(.1, remaining))
                if completion.done():
                    break
                if disconnected is not None and await disconnected():
                    raise VoiceError('VOICE_DISCONNECTED', 499)
            result = completion.result()
            delivered = True
            return result
        finally:
            if not completion.done():
                work.cancel()
                completion.cancel()
            if binary and not delivered:
                self.streams.pop(key, None)

    async def _worker(self):
        while True:
            work = await self.queue.get()
            if work is None:
                self.queue.task_done()
                return
            try:
                await self._run_work(work)
            finally:
                self.queue.task_done()
                # Waiting on the next queue item must not retain a completed PCM result.
                del work

    async def _run_work(self, work):
        try:
            if work.cancelled.is_set() or work.completion.done():
                return
            if time.monotonic() >= work.deadline:
                raise VoiceError('VOICE_TIMEOUT', 504)
            generate = self.backend.synthesize_binary if work.binary else self.backend.synthesize
            result = await asyncio.get_running_loop().run_in_executor(self.executor, generate, work.payload, work)
            if work.binary and not isinstance(result, BinarySpeech):
                raise VoiceError('VOICE_TIMING_INVALID')
            if not work.completion.done():
                if work.cancelled.is_set():
                    raise VoiceError('VOICE_CANCELLED', 409)
                work.completion.set_result(result)
        except Exception as error:
            if not work.completion.done():
                code = error if isinstance(error, VoiceError) else VoiceError('VOICE_INFERENCE_FAILED')
                work.completion.set_exception(code)
        finally:
            self.pending.pop(work.key, None)
            work.payload.clear()

    async def close(self):
        self.closed = True
        self.stopping.set()
        self.reason = 'VOICE_UNAVAILABLE'
        if self.startup is not None and self.startup is not asyncio.current_task():
            await self.startup
            self.startup = None
        for work in tuple(self.pending.values()) + tuple(self.streams.values()):
            work.cancel()
            if not work.completion.done():
                work.completion.set_exception(VoiceError('VOICE_UNAVAILABLE'))
        if self.worker is not None:
            await self.queue.put(None)
            await self.worker
            self.worker = None
        if self.backend is not None:
            await asyncio.get_running_loop().run_in_executor(self.executor, self.backend.close)
            self.backend = None
        if self.executor is not None:
            self.executor.shutdown(wait=True, cancel_futures=True)
            self.executor = None
        self.pending.clear()
        self.streams.clear()
        self.cancelled.clear()

    def lifecycle(self, inner):
        @asynccontextmanager
        async def lifespan(app):
            # Model warmup cannot hold ordinary Gateway readiness hostage.
            self.startup = asyncio.create_task(self.start())
            try:
                async with inner(app) as state:
                    yield state
            finally:
                await self.close()
        return lifespan


def install_voice_routes(app, service):
    # The private GPU worker imports this module without HTTP dependencies.
    from anyio import CancelScope
    from fastapi import Request
    from fastapi.responses import JSONResponse, StreamingResponse
    from starlette.background import BackgroundTask
    from starlette.requests import ClientDisconnect

    class VoiceStreamingResponse(StreamingResponse):
        def __init__(self, packets, work, audio_frames):
            self.work = work
            # Synthesis keeps its original Work.deadline. Completed PCM gets one
            # fixed transport window: its validated playback duration plus the
            # existing timeout (at most 55 seconds by default), never renewed.
            duration = min(audio_frames, MAX_AUDIO_FRAMES) / SAMPLE_RATE
            self.deadline = time.monotonic() + service.timeout + duration
            async def retire():
                # Keep ownership mutations on the event loop, including the
                # normal Starlette background callback and repeated cleanup.
                service.release_stream(work)
            super().__init__(packets, media_type=PCM_STREAM_TYPE,
                             headers={'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no'},
                             background=BackgroundTask(retire))

        async def __call__(self, scope, receive, send):
            response = asyncio.create_task(super().__call__(scope, receive, send))
            failure = None
            try:
                while not response.done():
                    remaining = self.deadline - time.monotonic()
                    if service.stream_cancelled(self.work) or remaining <= 0:
                        self.work.cancel()
                        raise ClientDisconnect()
                    await asyncio.wait((response,), timeout=min(.1, remaining))
                await response
            except BaseException as error:
                failure = error
            finally:
                response.cancel()
                try:
                    # Starlette 1.7 skips background cleanup on send failures.
                    # Retire its task before closing the possibly active iterator.
                    with CancelScope(shield=True):
                        await asyncio.gather(response, return_exceptions=True)
                        await self.body_iterator.aclose()
                except BaseException as error:
                    if failure is None:
                        failure = error
                finally:
                    service.release_stream(self.work)
            if failure is not None:
                raise failure

    def owner(request):
        principal = getattr(request.state, 'principal', None)
        if principal is None or 'chat' not in principal.scopes:
            raise VoiceError('VOICE_ADMISSION_REQUIRED', 401)
        return principal.id

    def failure(error):
        return JSONResponse({'error': {'code': error.code}}, status_code=error.status,
                            headers={'Cache-Control': 'no-store'})

    @app.get(BASE + '/capabilities')
    async def capabilities(request: Request):
        try:
            owner(request)
            return JSONResponse(service.capabilities(), headers={'Cache-Control': 'no-store'})
        except VoiceError as error:
            return failure(error)

    @app.post(BASE + '/synthesize')
    async def synthesize(request: Request):
        try:
            principal = owner(request)
            payload = await bounded_json(request)
            result = await service.synthesize(principal, payload, request.is_disconnected)
            return JSONResponse(result, headers={'Cache-Control': 'no-store'})
        except VoiceError as error:
            return failure(error)

    @app.post(BASE + '/synthesize/stream')
    async def synthesize_stream(request: Request):
        work = None
        try:
            principal = owner(request)
            payload = await bounded_json(request)
            result = await service.synthesize(principal, payload, request.is_disconnected, binary=True)
            identifier = result.metadata['request_id']
            work = service.streams.get((principal, identifier))
            if work is None:
                raise VoiceError('VOICE_CANCELLED', 409)
            header = result.header()

            async def packets():
                if not service.stream_cancelled(work):
                    yield header
                for chunk in result.chunks():
                    if service.stream_cancelled(work):
                        return  # Missing terminal frame is cancellation, never a complete utterance.
                    yield chunk

            return VoiceStreamingResponse(packets(), work, len(result.pcm) // 4)
        except VoiceError as error:
            if work is not None:
                service.release_stream(work)
            return failure(error)

    @app.post(BASE + '/cancel')
    async def cancel(request: Request):
        try:
            principal = owner(request)
            payload = await bounded_json(request)
            if (not isinstance(payload, dict) or not {'request_id'} <= set(payload) <= {'request_id', 'protocol', 'frontend_id'}
                    or payload.get('protocol', PROTOCOL) != PROTOCOL or payload.get('frontend_id', FRONTEND) != FRONTEND):
                raise VoiceError('VOICE_INVALID_REQUEST', 400)
            return JSONResponse(service.cancel(principal, payload['request_id']), headers={'Cache-Control': 'no-store'})
        except VoiceError as error:
            return failure(error)
