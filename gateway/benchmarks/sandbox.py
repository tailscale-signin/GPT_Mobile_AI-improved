"""Execute candidate code only inside a local, pinned, unprivileged container.

The candidate sees inputs, never expected outputs or host scoring code. It cannot
earn points by manufacturing a unittest report; the host compares returned data.
"""
import hashlib
import json
import os
from pathlib import Path
from contextlib import contextmanager
import re
import shutil
import subprocess
import tempfile
import threading
import time
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
    def __init__(self, receipt, observation=None):
        super().__init__('Benchmark sandbox stopped')
        self.cancellation = dict(receipt)
        self.observation = observation


@contextmanager
def docker_command(docker, *arguments):
    # Launch and later ownership checks must address the same local daemon.
    # An empty explicit config and default context prevent HOME, saved user
    # contexts or credentials from changing that endpoint across a restart.
    with tempfile.TemporaryDirectory(prefix='gateway-benchmark-docker-') as config:
        yield [docker, '--config', config, '--context', 'default', *arguments]


def remove_container(name, *, docker=None):
    """Only the exact, locally assigned benchmark container may be removed."""
    if not isinstance(name, str) or not re.fullmatch(r'gateway-benchmark-[a-f0-9]{32}', name):
        raise ValueError('Invalid benchmark sandbox identity')
    receipt = {'kind': 'sandbox', 'outcome': 'unknown', 'container': name}
    docker = docker or shutil.which('docker')
    if not docker:
        return receipt
    environment = {'PATH': os.defpath}
    if os.name == 'nt' and os.environ.get('SystemRoot'):
        environment['SystemRoot'] = os.environ['SystemRoot']
    try:
        with docker_command(docker, 'rm', '-f', name) as argv:
            removed = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, timeout=10, check=False, env=environment)
        if removed.returncode == 0:
            receipt['outcome'] = 'confirmed'
    except (OSError, subprocess.TimeoutExpired):
        pass
    return receipt


def confirm_container_absent(name, *, docker):
    if not isinstance(name, str) or not re.fullmatch(r'gateway-benchmark-[a-f0-9]{32}', name):
        raise ValueError('Invalid benchmark sandbox identity')
    receipt = {'kind': 'sandbox', 'outcome': 'unknown', 'container': name}
    try:
        with docker_command(docker, 'container', 'ls', '--all', '--filter', 'name=^/' + name + '$', '--format', '{{.Names}}') as argv:
            observed = run_bounded(argv, Path.cwd(), 10, 4096)
        # A failed inspect/rm also means an unavailable daemon. Only a successful
        # exact-name inventory can prove an already auto-removed container absent.
        if observed['exit_code'] == 0 and not observed['output_tail'].strip():
            receipt['outcome'] = 'confirmed'
    except (OSError, TimeoutError, ValueError):
        pass
    return receipt


def reconcile_container(name):
    """Bounded cleanup observation, never candidate execution or a new model call."""
    docker = shutil.which('docker')
    receipt = remove_container(name, docker=docker)
    if receipt['outcome'] == 'confirmed' or not docker:
        return receipt
    return confirm_container_absent(name, docker=docker)


def preflight(settings):
    image = settings['sandbox_image']
    if not image:
        raise SandboxUnavailable('Configure a pinned local Python sandbox image in Settings → Advanced → Benchmarks.')
    docker = shutil.which('docker')
    if not docker:
        raise SandboxUnavailable('Docker is unavailable. Install and start the local container runtime, then retry.')
    try:
        with docker_command(docker, 'image', 'inspect', '--format', '{{json .RepoDigests}}', image) as argv:
            result = run_bounded(argv, Path.cwd(), 10, 65536)
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


def execute(sources, inputs, settings, cancel=None, *, docker=None, retain=None, release=None):
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
        arguments = ['run', '--rm', '--pull=never', '--name', name,
                '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--read-only', '--pids-limit=64', '--memory=512m', '--cpus=2',
                '--user=65534:65534', '--tmpfs=/tmp:rw,nosuid,nodev,size=64m',
                '--mount=type=bind,source=' + str(candidate) + ',target=/candidate,readonly',
                '--mount=type=bind,source=' + str(evaluator) + ',target=/evaluator,readonly',
                '--workdir=/tmp', '--env=HOME=/tmp', settings['sandbox_image'],
                'python3', '-I', '-B', '/evaluator/runner.py']
        if retain is not None:
            # Persist ownership before the subprocess can create its container.
            retain(name)
        finished = threading.Event()
        removal_lock = threading.Lock()
        def remove():
            with removal_lock:
                if cancellation['outcome'] != 'confirmed':
                    cancellation.update(remove_container(name, docker=docker))
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
            return started, raw[len(marker):].decode('utf-8', 'replace') if started else '', hashlib.sha256(raw).hexdigest()
        successful_exit = False
        started_at = time.perf_counter()
        def evaluate():
            nonlocal successful_exit
            with docker_command(docker, *arguments) as argv, output_path.open('wb') as output:
                result = run_bounded(argv, root, settings['sandbox_seconds'], 262144, destination=output)
            # A successful attached run is terminal. A watchdog or nonzero client
            # exit may instead be lost Docker transport; cleanup still needs proof.
            successful_exit = result['exit_code'] == 0
            execution_started, output_text, _ = captured()
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
        try:
            observation = evaluate()
        except (TimeoutError, ValueError, OSError) as error:
            started, _, output_digest = captured()
            if isinstance(error, TimeoutError):
                code = 'CANDIDATE_DEADLINE' if started else 'SANDBOX_START_DEADLINE'
            elif isinstance(error, ValueError):
                code = 'CANDIDATE_OUTPUT_LIMIT' if started else 'SANDBOX_OUTPUT_INVALID'
            else:
                code = 'SANDBOX_OBSERVATION_UNAVAILABLE'
            observation = {'status': 'FAILED' if started and code != 'SANDBOX_OBSERVATION_UNAVAILABLE' else 'ERROR', 'executed': started, 'outputs': [],
                           'error': code, 'durationMs': round((time.perf_counter() - started_at) * 1000),
                           'outputDigest': output_digest}
        finally:
            finished.set()
            remove()
            watcher.join(timeout=1)
        if successful_exit and cancellation['outcome'] != 'confirmed':
            cancellation['outcome'] = 'terminal'
        elif cancellation['outcome'] != 'confirmed':
            cancellation.update(confirm_container_absent(name, docker=docker))
        observation['cleanup'] = dict(cancellation)
        if cancellation['outcome'] in {'confirmed', 'terminal'} and release is not None:
            release(name)
        if cancel and cancel.is_set() or cancellation['outcome'] == 'unknown':
            raise SandboxInterrupted(cancellation, observation)
        return observation
