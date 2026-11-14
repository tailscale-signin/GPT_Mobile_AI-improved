"""Local host settings and preparation for repository validation."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


SETTING = 'GATEWAY_ENGINEERING_VALIDATION_POLICY'


def _path(value):
    if not isinstance(value, str) or len(value) > 4096 or '\0' in value:
        raise ValueError('Validation policy must be a bounded file path')
    if value and not Path(value).is_absolute():
        raise ValueError('Choose an absolute validation policy file path')
    return value


def selected_policy_path(root, inherited=None):
    """A saved empty selection disables inheritance; old installs keep explicit host configuration."""
    settings = Path(root) / 'data' / 'validation-settings.json'
    try:
        with settings.open('rb') as stream:
            content = stream.read(8193)
    except FileNotFoundError:
        return _path((os.environ if inherited is None else inherited).get(SETTING, ''))
    if len(content) > 8192:
        raise ValueError('Saved validation policy selection is oversized')
    try:
        value = json.loads(content)
    except (ValueError, UnicodeError):
        raise ValueError('Saved validation policy selection is invalid') from None
    if (not isinstance(value, dict) or set(value) != {'schema', 'policy_path'} or
            type(value['schema']) is not int or value['schema'] != 1):
        raise ValueError('Saved validation policy selection is invalid')
    return _path(value['policy_path'])


def _read_document(path):
    try:
        with Path(path).open('rb') as stream:
            content = stream.read(65537)
    except OSError:
        raise ValueError('Validation policy file is missing or unreadable') from None
    if len(content) > 65536:
        raise ValueError('Validation policy exceeds the 64 KiB limit')
    try:
        document = json.loads(content)
    except (ValueError, UnicodeError):
        raise ValueError('Validation policy must contain a JSON repository mapping') from None
    _validate_document(document)
    return document


def _validate_document(document):
    if (not isinstance(document, dict) or not 1 <= len(document) <= 256 or
            any(not isinstance(repo, str) or not re.fullmatch(r'[a-z0-9_.-]+/[a-z0-9_.-]+', repo)
                or not isinstance(policy, dict) for repo, policy in document.items())):
        raise ValueError('Validation policy must name 1 to 256 repositories as lowercase owner/name')


def check_policy_file(value):
    """Validate configuration only, without executing commands or claiming runtime readiness."""
    path = _path(value)
    if not path:
        return {'state': 'NOT_CONFIGURED', 'repositories': 0}
    from v14.engineering_validation import load_policy
    document = _read_document(path)
    try:
        for repository in document:
            load_policy(repository, policy_path=path)
    except (OSError, ValueError, KeyError, TypeError):
        raise ValueError('Validation policy needs a repository mirror, digest-pinned image and approved commands; check its acceptance mappings') from None
    return {'state': 'VALIDATED', 'repositories': len(document)}


def save_policy_path(root, value):
    checked = check_policy_file(value)
    # Separate from schema-1 settings so rollback releases can still read them.
    from desktop.configuration import atomic_json
    atomic_json(Path(root) / 'data' / 'validation-settings.json', {'schema': 1, 'policy_path': _path(value)})
    return checked


DEFAULT_REPOSITORY = 'tailscale-signin/gpt_mobile_ai-improved'
DEFAULT_IMAGE = 'docker.io/library/python:3.12-slim'
DEFAULT_ORIGIN = 'https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git'


def managed_policy_path(root):
    return Path(root).resolve() / 'data' / 'repository-validation-policy.json'


def default_policy(root):
    return {
        'mirror': str(managed_policy_path(root).parent / 'mirrors' / 'GPT_Mobile_AI-improved.git'),
        'image': '',
        'timeout_seconds': 600,
        'commands': [
            ['python3', '-m', 'compileall', '-q', 'gateway', 'authentication', 'scripts'],
            ['python3', 'scripts/check_ci_policy.py'],
            ['python3', 'scripts/check_android_regex.py'],
        ],
        'parallel_command_groups': [],
        'acceptance_map': [],
    }


def active_policy_path(root):
    managed = managed_policy_path(root)
    try:
        managed.stat()
    except FileNotFoundError:
        return selected_policy_path(root)
    return str(managed)


def read_desktop_policy(root):
    path = active_policy_path(root)
    return _read_document(path) if path else {}


def save_desktop_policy(root, document):
    from desktop.configuration import atomic_json
    from v14.engineering_validation import validate_policy_entry

    _validate_document(document)
    for policy in document.values():
        if not _path(policy.get('mirror', '')):
            raise ValueError('Enter an absolute path for the Git mirror')
        timeout = policy.get('timeout_seconds', 600)
        if type(timeout) is not int or not 1 <= timeout <= 1800:
            raise ValueError('Timeout must be between 1 and 1800 seconds')
        if not isinstance(policy.get('image'), str):
            raise ValueError('Enter a container image pinned by digest')
        validate_policy_entry(policy)
    if len(json.dumps(document, indent=2).encode('utf-8')) > 65536:
        raise ValueError('Validation policy exceeds the 64 KiB limit')
    atomic_json(managed_policy_path(root), document, retry_windows_sharing=True)


def prepare_default_policy(root):
    """Refresh the fixed GitHub origin and resolve the public image to its immutable digest."""
    def run(tool, *args, timeout=300):
        try:
            return subprocess.run([tool, *args], check=True, capture_output=True, text=True,
                                  stdin=subprocess.DEVNULL, timeout=timeout,
                                  creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)).stdout.strip()
        except FileNotFoundError:
            raise ValueError(f'Install {tool} and make it available on PATH.') from None
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if tool == 'docker':
                raise ValueError('Docker setup failed. Start Docker Desktop in Linux container mode and check registry access.') from None
            raise ValueError('Git mirror setup failed. Check GitHub access and local disk permissions.') from None

    policy = default_policy(root)
    if run('docker', 'info', '--format', '{{.OSType}}', timeout=30) != 'linux':
        raise ValueError('Switch Docker Desktop to Linux containers.')
    run('docker', 'pull', DEFAULT_IMAGE)
    digests = json.loads(run('docker', 'image', 'inspect', DEFAULT_IMAGE, '--format', '{{json .RepoDigests}}', timeout=30))
    image = next((digest for digest in digests or [] if re.fullmatch(
        r'(?:(?:docker\.io|index\.docker\.io)/)?(?:library/)?python@sha256:[a-f0-9]{64}', digest)), '')
    if not image:
        raise ValueError('Docker did not report an immutable digest for the default image.')
    run('docker', 'run', '--rm', '--pull=never', '--network=none', '--cap-drop=ALL', image, 'python3', '--version', timeout=30)

    mirror = Path(policy['mirror'])
    mirror.parent.mkdir(parents=True, exist_ok=True)
    if mirror.exists():
        git_dir = '--git-dir=' + str(mirror)
        if (run('git', git_dir, 'rev-parse', '--is-bare-repository', timeout=30) != 'true' or
                run('git', git_dir, 'remote', 'get-url', 'origin', timeout=30).lower() != DEFAULT_ORIGIN.lower()):
            raise ValueError('The default mirror folder is already in use by another repository.')
        run('git', git_dir, 'fetch', '--prune', DEFAULT_ORIGIN, '+refs/heads/*:refs/heads/*')
    else:
        with tempfile.TemporaryDirectory(dir=mirror.parent) as work:
            staged = Path(work) / 'mirror.git'
            run('git', 'clone', '--mirror', DEFAULT_ORIGIN, str(staged))
            staged.rename(mirror)
    return dict(policy, image=image)
