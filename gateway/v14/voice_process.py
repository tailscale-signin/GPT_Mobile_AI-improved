"""Private, bounded pipe transport for the optional CUDA venv in frozen Gateway.

The child never opens a listener. Only the already-admitted service can submit
work; the separately installed interpreter receives no Gateway credentials.
"""
import contextlib
import ctypes
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time

from .voice import (FRONTEND, MAX_AUDIO_FRAMES, MAX_BODY_BYTES, PROTOCOL, PROVIDER, SAMPLE_RATE,
                    SHA256, BinarySpeech, VoiceError)

MAX_RESPONSE_BYTES = MAX_AUDIO_FRAMES * 4 * 4 // 3 + 65536
_DLL_LOCK = threading.Lock()


def configuration():
    managed = os.getenv('GATEWAY_DESKTOP_ROOT')
    if not managed:
        return os.getenv('GATEWAY_VOICE_MODEL_DIR') or None, os.getenv('GATEWAY_VOICE_PYTHON') or None
    root = Path(managed).resolve()
    setting = root / 'data' / 'voice-cuda.json'
    try:
        if not setting.is_file() or setting.stat().st_size > 8192:
            return None, None
        config = json.loads(setting.read_text(encoding='utf-8'))
        if (set(config) != {'schema_version', 'enabled', 'python', 'model_directory'}
                or type(config['schema_version']) is not int or config['schema_version'] != 1 or config['enabled'] is not True):
            return None, None
        interpreter, directory = Path(config['python']), Path(config['model_directory'])
        expected = root / 'addons' / 'voice-cuda' / 'venv' / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
        if (not interpreter.is_absolute() or not interpreter.is_file()
                or not directory.is_absolute() or not directory.is_dir()):
            return None, None
        # Windows may spell the same installed directory using an 8.3 alias.
        # Resolve its parent, keeping the venv executable entry point: resolving
        # a POSIX bin/python symlink would also admit its system interpreter.
        interpreter = interpreter.parent.resolve(strict=True) / interpreter.name
        expected = expected.parent.resolve(strict=True) / expected.name
        if interpreter != expected:
            return None, None
        return str(directory.resolve()), str(interpreter)
    except (OSError, ValueError, TypeError, KeyError):
        return None, None


def child_environment():
    # A CUDA worker does not need owner grants, tokens, provider credentials, or
    # PyInstaller's loader state. Preserve only OS/CUDA library discovery paths.
    allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'TEMP', 'TMP', 'TMPDIR',
               'HOME', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'LANG', 'LC_ALL', 'CUDA_PATH',
               'CUDA_HOME', 'CUDA_VISIBLE_DEVICES', 'NVIDIA_VISIBLE_DEVICES', 'LD_LIBRARY_PATH',
               'DYLD_LIBRARY_PATH'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed or key.upper().startswith('CUDA_PATH_V')}
    for key in ('LD_LIBRARY_PATH', 'DYLD_LIBRARY_PATH'):
        if getattr(sys, 'frozen', False):
            original = os.environ.get(key + '_ORIG')
            if original is None:
                env.pop(key, None)
            else:
                env[key] = original
    bundled = getattr(sys, '_MEIPASS', None)
    if bundled and env.get('PATH'):
        root = str(Path(bundled).resolve()).casefold()
        env['PATH'] = os.pathsep.join(part for part in env['PATH'].split(os.pathsep)
                                    if not str(Path(part).resolve()).casefold().startswith(root + os.sep)
                                    and str(Path(part).resolve()).casefold() != root)
    return env


@contextlib.contextmanager
def external_dll_search():
    # PyInstaller's DLL directory is inherited by Windows children. Restore the
    # system search for this spawn and immediately restore the host's directory.
    if os.name != 'nt' or not getattr(sys, 'frozen', False):
        yield
        return
    with _DLL_LOCK:
        kernel = ctypes.windll.kernel32
        kernel.GetDllDirectoryW.argtypes = [ctypes.c_uint, ctypes.c_wchar_p]
        kernel.GetDllDirectoryW.restype = ctypes.c_uint
        kernel.SetDllDirectoryW.argtypes = [ctypes.c_wchar_p]
        kernel.SetDllDirectoryW.restype = ctypes.c_int
        size = kernel.GetDllDirectoryW(0, None)
        previous = ctypes.create_unicode_buffer(size + 1)
        kernel.GetDllDirectoryW(size + 1, previous)
        kernel.SetDllDirectoryW(None)
        try:
            yield
        finally:
            kernel.SetDllDirectoryW(previous.value or None)


class SidecarCudaBackend:
    def __init__(self, directory, interpreter, worker_path=None, stopping=None):
        executable = Path(interpreter or '')
        worker = Path(worker_path) if worker_path else Path(__file__).resolve().parents[1] / 'voice_worker.py'
        if not executable.is_absolute() or not executable.is_file() or not worker.is_file():
            raise VoiceError('VOICE_WORKER_UNAVAILABLE')
        self.process, self._writer, self._responses = None, threading.Lock(), queue.Queue(maxsize=2)
        self.stopping = stopping or threading.Event()
        try:
            with external_dll_search():
                self.process = subprocess.Popen([str(executable), '-I', '-u', str(worker), '--directory', str(Path(directory).resolve())],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, shell=False,
                    env=child_environment(), creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.reader = threading.Thread(target=self._reader, name='voice-cuda-pipe', daemon=True)
            self.reader.start()
            hello = self._receive(120)
            if (hello.get('protocol') != PROTOCOL or hello.get('frontend_id') != FRONTEND or hello.get('ready') is not True
                    or hello.get('execution_provider') != PROVIDER or hello.get('sample_rate') != SAMPLE_RATE
                    or not isinstance(hello.get('model_sha256'), str) or not SHA256.fullmatch(hello['model_sha256'])
                    or type(hello.get('executed_cuda_nodes')) is not int or hello['executed_cuda_nodes'] <= 0
                    or type(hello.get('speakers')) is not int or not 1 <= hello['speakers'] <= 256):
                raise VoiceError('VOICE_CUDA_UNAVAILABLE')
            self.model_sha256, self.speakers = hello['model_sha256'], hello['speakers']
            self.executed_cuda_nodes = hello['executed_cuda_nodes']
        except Exception:
            self.close()
            raise

    @property
    def ready(self):
        return self.process is not None and self.process.poll() is None and not self.stopping.is_set()

    def _reader(self):
        try:
            while True:
                line = self.process.stdout.readline(MAX_RESPONSE_BYTES + 1)
                if not line or len(line) > MAX_RESPONSE_BYTES or not line.endswith(b'\n'):
                    raise ValueError()
                response = json.loads(line)
                if not isinstance(response, dict) or '_pcm' in response:
                    raise ValueError()
                if '_binary_pcm_bytes' in response:
                    count = response.pop('_binary_pcm_bytes')
                    if type(count) is not int or not 0 < count <= MAX_AUDIO_FRAMES * 4 or count % 4 or len(line) > MAX_BODY_BYTES:
                        raise ValueError()
                    pcm = bytearray()
                    while len(pcm) < count:
                        chunk = self.process.stdout.read(min(65536, count - len(pcm)))
                        if not chunk:
                            raise ValueError()
                        pcm.extend(chunk)
                    response['_pcm'] = bytes(pcm)
                self._responses.put(response, timeout=1)
        except (OSError, ValueError, RecursionError, queue.Full):
            try:
                self._responses.put_nowait(None)
            except queue.Full:
                pass
            if self.process.poll() is None:
                try:
                    self.process.kill()
                except OSError:
                    pass

    def _send(self, payload):
        frame = json.dumps(payload, separators=(',', ':'), allow_nan=False).encode('utf-8') + b'\n'
        if len(frame) > MAX_BODY_BYTES:
            raise VoiceError('VOICE_INVALID_REQUEST', 400)
        with self._writer:
            if self.process is None or self.process.poll() is not None:
                raise VoiceError('VOICE_WORKER_UNAVAILABLE')
            try:
                self.process.stdin.write(frame)
                self.process.stdin.flush()
            except OSError:
                raise VoiceError('VOICE_WORKER_UNAVAILABLE') from None

    def _receive(self, timeout):
        deadline = time.monotonic() + max(0, timeout)
        result = None
        while not self.stopping.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                result = self._responses.get(timeout=min(.1, remaining))
                break
            except queue.Empty:
                continue
        else:
            if self.process is not None and self.process.poll() is None:
                self.process.kill()
            raise VoiceError('VOICE_CANCELLED', 409)
        if time.monotonic() >= deadline and result is None:
            if self.process is not None and self.process.poll() is None:
                self.process.kill()
            raise VoiceError('VOICE_TIMEOUT', 504)
        if result is None:
            raise VoiceError('VOICE_WORKER_UNAVAILABLE')
        if result.get('error'):
            code = result['error']
            if code not in {'VOICE_PACK_INVALID', 'VOICE_PACK_HASH_MISMATCH', 'VOICE_CUDA_UNAVAILABLE',
                            'VOICE_CUDA_NOT_EXECUTED', 'VOICE_TIMING_INVALID', 'VOICE_CANCELLED', 'VOICE_INFERENCE_FAILED', 'VOICE_FRONTEND_MISMATCH'}:
                code = 'VOICE_WORKER_UNAVAILABLE'
            raise VoiceError(code, 409 if code == 'VOICE_CANCELLED' else 503)
        return result

    def synthesize(self, payload, work):
        return self._synthesize(payload, work, binary=False)

    def synthesize_binary(self, payload, work):
        result = self._synthesize(payload, work, binary=True)
        pcm = result.pop('_pcm', None)
        return BinarySpeech(result, pcm)

    def _synthesize(self, payload, work, binary):
        backend = self
        identifier = payload['request_id']
        class Cancellation:
            @property
            def terminate(self):
                return work.cancelled.is_set()
            @terminate.setter
            def terminate(self, value):
                if value:
                    try:
                        backend._send({'command': 'cancel', 'request_id': identifier})
                    except VoiceError:
                        pass
        self._send({'command': 'synthesize_binary' if binary else 'synthesize', 'payload': payload})
        work.options(Cancellation())
        try:
            result = self._receive(work.deadline - time.monotonic())
        finally:
            work.options(None)
        if (result.get('protocol') != PROTOCOL or result.get('frontend_id') != FRONTEND
                or result.get('request_id') != identifier or result.get('model_sha256') != self.model_sha256
                or result.get('execution_provider') != PROVIDER or result.get('sample_rate') != SAMPLE_RATE
                or result.get('executed_cuda_nodes') != self.executed_cuda_nodes
                or result.get('word_tokens') != payload['word_tokens']
                or (binary and not isinstance(result.get('_pcm'), bytes))
                or (not binary and '_pcm' in result)):
            self.close()
            raise VoiceError('VOICE_WORKER_UNAVAILABLE')
        return result

    def close(self):
        process = self.process
        if process is None:
            return
        try:
            if process.poll() is None:
                self._send({'command': 'close'})
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
        except (OSError, VoiceError, subprocess.TimeoutExpired):
            if process.poll() is None:
                process.kill()
        finally:
            for stream in (process.stdin, process.stdout):
                if stream is not None:
                    try:
                        stream.close()
                    except OSError:
                        pass
