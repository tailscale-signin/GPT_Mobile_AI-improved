"""Private CUDA child for frozen Gateway; stdin/stdout only, no network listener."""
import argparse
import json
from pathlib import Path
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

# The host uses -I, so only this validated bundled source directory is added.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from v14.voice import (FRONTEND, MAX_BODY_BYTES, PROTOCOL, PROVIDER, SAMPLE_RATE, KokoroCudaBackend,
                       VoiceError, Work, request_id, token_request)


def serve(directory, factory=KokoroCudaBackend, source=None, target=None):
    source, target = source or sys.stdin.buffer, target or sys.stdout.buffer
    lock, writer = threading.Lock(), threading.Lock()
    active = [None]
    def send(payload):
        encoded = json.dumps(payload, separators=(',', ':'), allow_nan=False).encode('utf-8') + b'\n'
        with writer:
            target.write(encoded)
            target.flush()
    backend = None
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='voice-cuda-native')
    try:
        backend = factory(directory)
        send(dict(protocol=PROTOCOL, frontend_id=FRONTEND, ready=True, execution_provider=PROVIDER,
                  sample_rate=SAMPLE_RATE, model_sha256=backend.model_sha256, speakers=backend.speakers,
                  executed_cuda_nodes=backend.executed_cuda_nodes))
        def generate(work):
            try:
                result = backend.synthesize(work.payload, work)
            except Exception as error:
                result = {'error': 'VOICE_CANCELLED' if work.cancelled.is_set() else
                          error.code if isinstance(error, VoiceError) else 'VOICE_INFERENCE_FAILED',
                          'request_id': work.payload['request_id']}
            finally:
                with lock:
                    active[0] = None
                work.payload.clear()
            send(result)
        while True:
            line = source.readline(MAX_BODY_BYTES + 1)
            if not line:
                break
            if len(line) > MAX_BODY_BYTES or not line.endswith(b'\n'):
                raise VoiceError('VOICE_INVALID_REQUEST', 400)
            message = json.loads(line)
            if not isinstance(message, dict):
                raise VoiceError('VOICE_INVALID_REQUEST', 400)
            command = message.get('command')
            if command == 'close' and set(message) == {'command'}:
                break
            if command == 'cancel' and set(message) == {'command', 'request_id'}:
                identifier = request_id(message['request_id'])
                with lock:
                    if active[0] is not None and active[0].payload['request_id'] == identifier:
                        active[0].cancel()
            elif command == 'synthesize' and set(message) == {'command', 'payload'}:
                payload = token_request(message['payload'], backend.model_sha256, backend.speakers)
                with lock:
                    if active[0] is not None:
                        raise VoiceError('VOICE_QUEUE_FULL', 429)
                    active[0] = work = Work('', payload, None, float('inf'))
                executor.submit(generate, work)
            else:
                raise VoiceError('VOICE_INVALID_REQUEST', 400)
    except Exception as error:
        send({'error': error.code if isinstance(error, VoiceError) else 'VOICE_CUDA_UNAVAILABLE', 'ready': False})
    finally:
        with lock:
            if active[0] is not None:
                active[0].cancel()
        executor.shutdown(wait=True, cancel_futures=True)
        if backend is not None:
            backend.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Gateway private CUDA worker (no HTTP listener)')
    parser.add_argument('--directory', required=True)
    serve(parser.parse_args().directory)
