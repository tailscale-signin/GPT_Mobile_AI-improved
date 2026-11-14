"""Execute candidate code only inside a local, pinned, unprivileged container.

The candidate sees inputs, never expected outputs or host scoring code. It cannot
earn points by manufacturing a unittest report; the host compares returned data.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import uuid

from v14.engineering_validation import run_bounded

START_MARKER = 'GATEWAY_BENCHMARK_EVALUATOR_STARTED_V1'
DRIVER = '''import json, sys
print('GATEWAY_BENCHMARK_EVALUATOR_STARTED_V1', flush=True)
sys.path.insert(0, '/candidate')
import service
requests = json.load(open('/evaluator/inputs.json'))
outputs = []
for request in requests:
    try:
        value = service.handle(request)
        first = json.loads(json.dumps(value, allow_nan=False))
        outputs.append({'output': first})
    except BaseException as error:
        outputs.append({'error': type(error).__name__})
print(json.dumps({'outputs': outputs}, ensure_ascii=True, allow_nan=False))
'''


class SandboxUnavailable(ValueError):
    pass


class SandboxInterrupted(InterruptedError):
    def __init__(self, receipt):
        super().__init__('Benchmark sandbox stopped')
        self.cancellation = receipt


def preflight(settings):
    image = settings['sandbox_image']
    if not image:
        raise SandboxUnavailable('Configure a pinned local Python sandbox image in Settings → Advanced → Benchmarks.')
    docker = shutil.which('docker')
    if not docker:
        raise SandboxUnavailable('Docker is unavailable. Install and start the local container runtime, then retry.')
    try:
        result = run_bounded([docker, 'image', 'inspect', '--format', '{{json .RepoDigests}}', image], Path.cwd(), 10, 65536)
    except (OSError, TimeoutError, ValueError) as error:
        raise SandboxUnavailable('The configured local sandbox image could not be verified.') from error
    if result['exit_code'] != 0:
        raise SandboxUnavailable('The pinned sandbox image is not available locally. No image is downloaded automatically.')
    try:
        digests = json.loads(result['output_tail'])
    except ValueError as error:
        raise SandboxUnavailable('The sandbox image digest was not reported.') from error
    if not isinstance(digests, list) or not any(isinstance(x, str) and x.endswith('@' + image.split('@', 1)[1]) for x in digests):
        raise SandboxUnavailable('The local image does not match the configured immutable digest.')
    return docker


def execute(sources, inputs, settings, cancel=None, *, docker=None):
    docker = docker or preflight(settings)
    if cancel and cancel.is_set():
        raise InterruptedError('Benchmark stopped')
    name = 'gateway-benchmark-' + uuid.uuid4().hex
    cancellation = {'kind': 'sandbox', 'outcome': 'unknown', 'container': name}
    with tempfile.TemporaryDirectory(prefix='gateway-benchmark-') as directory:
        root = Path(directory)
        candidate, evaluator = root / 'candidate', root / 'evaluator'
        candidate.mkdir()
        evaluator.mkdir()
        for name_path, source in sources.items():
            # Only the fixture-owned filenames are admitted by the patch parser.
            if Path(name_path).name != name_path or not name_path.endswith('.py'):
                raise ValueError('Unsupported sandbox file')
            (candidate / name_path).write_text(source, encoding='utf-8')
        (evaluator / 'runner.py').write_text(DRIVER, encoding='utf-8')
        (evaluator / 'inputs.json').write_text(json.dumps(inputs, ensure_ascii=True, allow_nan=False), encoding='utf-8')
        for path in (root, candidate, evaluator):
            path.chmod(0o755)
        for path in (*candidate.iterdir(), *evaluator.iterdir()):
            path.chmod(0o444)
        argv = [docker, 'run', '--rm', '--pull=never', '--name', name,
                '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--read-only', '--pids-limit=64', '--memory=512m', '--cpus=2',
                '--user=65534:65534', '--tmpfs=/tmp:rw,nosuid,nodev,size=64m',
                '--mount=type=bind,source=' + str(candidate) + ',target=/candidate,readonly',
                '--mount=type=bind,source=' + str(evaluator) + ',target=/evaluator,readonly',
                '--workdir=/tmp', '--env=HOME=/tmp', settings['sandbox_image'],
                'python3', '-I', '-B', '/evaluator/runner.py']
        finished = threading.Event()
        def remove():
            environment = {'PATH': os.defpath}
            if os.name == 'nt' and os.environ.get('SystemRoot'):
                environment['SystemRoot'] = os.environ['SystemRoot']
            try:
                removed = subprocess.run([docker, 'rm', '-f', name], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, timeout=10, check=False, env=environment)
                if removed.returncode == 0:
                    cancellation['outcome'] = 'confirmed'
            except (OSError, subprocess.TimeoutExpired):
                pass
        def watch():
            while not finished.wait(.1):
                if cancel and cancel.is_set():
                    remove()
        watcher = threading.Thread(target=watch, name='benchmark-sandbox-cancel', daemon=True)
        watcher.start()
        output_path = root / 'output.txt'
        def captured():
            raw = output_path.read_bytes() if output_path.exists() else b''
            marker = (START_MARKER + '\n').encode()
            started = raw.startswith(marker)
            return started, raw[len(marker):].decode('utf-8', 'replace') if started else ''
        try:
            with output_path.open('wb') as output:
                result = run_bounded(argv, root, settings['sandbox_seconds'], 262144, destination=output)
            execution_started, output_text = captured()
            if cancel and cancel.is_set():
                raise SandboxInterrupted(cancellation)
            if not execution_started:
                return {'status': 'ERROR', 'executed': False, 'outputs': [], 'exitCode': result['exit_code'],
                        'error': 'SANDBOX_EXECUTION_NOT_CONFIRMED', 'outputDigest': result['output_sha256'], 'durationMs': result['elapsed_ms']}
            if result['exit_code'] != 0:
                return {'status': 'FAILED', 'executed': True, 'outputs': [], 'exitCode': result['exit_code'],
                        'outputDigest': result['output_sha256'], 'durationMs': result['elapsed_ms']}
            try:
                payload = json.loads(output_text)
                outputs = payload.get('outputs')
                if not isinstance(outputs, list) or len(outputs) != len(inputs):
                    raise ValueError('Missing case outputs')
                if any(not isinstance(row, dict) or set(row) - {'output', 'error'} for row in outputs):
                    raise ValueError('Malformed case outputs')
            except (ValueError, AttributeError, TypeError):
                return {'status': 'FAILED', 'executed': True, 'outputs': [], 'exitCode': 0,
                        'outputDigest': result['output_sha256'], 'durationMs': result['elapsed_ms'], 'error': 'INVALID_CANDIDATE_OUTPUT'}
            return {'status': 'COMPLETED', 'executed': True, 'outputs': outputs, 'exitCode': 0,
                    'outputDigest': result['output_sha256'], 'durationMs': result['elapsed_ms']}
        except TimeoutError:
            if cancel and cancel.is_set():
                raise SandboxInterrupted(cancellation)
            started, _ = captured()
            return {'status': 'FAILED' if started else 'ERROR', 'executed': started, 'outputs': [],
                    'error': 'CANDIDATE_DEADLINE' if started else 'SANDBOX_START_DEADLINE', 'durationMs': settings['sandbox_seconds'] * 1000}
        except ValueError:
            started, _ = captured()
            return {'status': 'FAILED' if started else 'ERROR', 'executed': started, 'outputs': [],
                    'error': 'CANDIDATE_OUTPUT_LIMIT' if started else 'SANDBOX_OUTPUT_INVALID'}
        finally:
            finished.set()
            remove()
            watcher.join(timeout=1)
