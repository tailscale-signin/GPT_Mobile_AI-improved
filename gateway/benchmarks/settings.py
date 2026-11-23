"""Local operator controls. Benchmark requests cannot choose an execution image."""
import json
from pathlib import Path
import re

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock

DEFAULTS = {'schema': 1, 'sandbox_image': '', 'repository_image': '', 'sandbox_seconds': 30, 'repair_attempts': 1}


def validate(value):
    if not isinstance(value, dict) or set(value) - set(DEFAULTS):
        raise ValueError('Unsupported benchmark settings')
    result = dict(DEFAULTS, **value)
    if result['schema'] != 1 or type(result['schema']) is not int:
        raise ValueError('Unsupported benchmark settings version')
    image = result['sandbox_image']
    if not isinstance(image, str) or image and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:_-]{0,180}@sha256:[a-f0-9]{64}', image):
        raise ValueError('Use a local Python image pinned as name@sha256:<64 hexadecimal characters>')
    repository_image = result['repository_image']
    if not isinstance(repository_image, str) or repository_image and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:_-]{0,180}@sha256:[a-f0-9]{64}', repository_image):
        raise ValueError('Use an immutable local repository runtime image')
    if type(result['sandbox_seconds']) is not int or not 5 <= result['sandbox_seconds'] <= 120:
        raise ValueError('Sandbox timeout must be between 5 and 120 seconds')
    if type(result['repair_attempts']) is not int or not 0 <= result['repair_attempts'] <= 2:
        raise ValueError('Choose zero, one or two bounded repairs')
    return result


def load_settings(root):
    path = Path(root) / 'data' / 'benchmark-settings.json'
    if not path.exists():
        return dict(DEFAULTS)
    if path.stat().st_size > 8192:
        raise ValueError('Benchmark settings exceed their size bound')
    return validate(json.loads(path.read_text(encoding='utf-8')))


def save_settings(root, value, *, expected=None):
    """Serialize edits so background image setup cannot overwrite newer settings."""
    result = validate(value)
    lock = StateDirectoryLock(Path(root).resolve() / 'data' / 'benchmark-settings-lock' / 'owner')
    lock.acquire()
    try:
        if expected is not None and load_settings(root) != validate(expected):
            raise ValueError('Benchmark settings changed; reload before saving.')
        atomic_json(Path(root) / 'data' / 'benchmark-settings.json', result, retry_windows_sharing=True)
    finally:
        lock.release()
    return result


def smoke_sandbox_image(image, *, cancel=None):
    """Exercise unprivileged offline Python, using the approved local Docker context."""
    from desktop import repository_validation as setup
    token = 'GATEWAY_BENCHMARK_PYTHON_READY_V1'
    output = setup.docker_run([
        'run', '--rm', '--pull=never', '--network=none', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--read-only', '--pids-limit=64',
        '--memory=512m', '--cpus=2', '--user=65534:65534',
        '--tmpfs=/tmp:rw,nosuid,nodev,size=64m', '--workdir=/tmp',
        '--env=HOME=/tmp', image, 'python3', '-I', '-B', '-c',
        'print("' + token + '")',
    ], cancel=cancel, timeout=30, label='Verify benchmark sandbox')
    if output != token:
        raise ValueError('Isolated Python did not produce the required sandbox startup receipt.')
    return True


def prepare_sandbox(root, *, expected=None, cancel=None, progress=lambda message: None):
    """Only explicit operator action can pull and save an immutable sandbox image."""
    from desktop import repository_validation as setup
    from v14.validation_host import executable
    from benchmarks.sandbox import preflight

    original = load_settings(root)
    if expected is not None and original != validate(expected):
        raise ValueError('Benchmark settings changed; reload before preparing.')
    setup.cancelled(cancel)
    progress('Checking local Linux Docker engine…')
    docker = executable('docker')
    setup.check_docker(docker, cancel=cancel)
    progress('Pulling official Python and resolving verified registry digest…')
    image = setup.pin_image(docker, setup.DEFAULT_IMAGE, prepare=True, cancel=cancel)
    progress('Inspecting immutable local image…')
    preflight(dict(original, sandbox_image=image))
    progress('Testing restricted offline Python execution…')
    smoke_sandbox_image(image, cancel=cancel)
    setup.cancelled(cancel)
    save_settings(root, dict(original, sandbox_image=image), expected=original)
    return image

def verify_submission(root, suite, task_pack='python'):
    """Fail fast for a coding run, without downloading or preparing any image."""
    if suite == 'coding':
        from benchmarks.sandbox import preflight
        settings = load_settings(root)
        if task_pack == 'repository':
            settings = dict(settings, sandbox_image=settings['repository_image'])
        return preflight(settings)
    return None
