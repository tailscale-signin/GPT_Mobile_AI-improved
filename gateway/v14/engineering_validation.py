"""Host-approved, isolated pre-publication validation. No model-selected shell commands."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import tarfile
import tempfile
import time


def patch_identity(head, changes):
    return hashlib.sha256(json.dumps([head, changes], sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def run_bounded(argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None):
    started = time.monotonic()
    output = bytearray()
    total = 0
    digest = hashlib.sha256()
    process = subprocess.Popen(argv, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env={'PATH': os.defpath, 'HOME': str(cwd), 'LANG': 'C.UTF-8'})
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map():
            if time.monotonic() - started > timeout:
                raise TimeoutError('Approved validation command exceeded its deadline')
            for key, _ in selector.select(.25):
                block = os.read(key.fd, 8192)
                if not block:
                    selector.unregister(key.fd)
                else:
                    if total + len(block) > maximum:
                        raise ValueError('Validation output exceeded its byte budget')
                    total += len(block)
                    digest.update(block)
                    if destination is not None:
                        destination.write(block)
                    else:
                        output.extend(block)
                        del output[:-16000]
        code = process.wait(timeout=max(1, timeout - (time.monotonic() - started)))
        return {'exit_code': code, 'elapsed_ms': round((time.monotonic() - started) * 1000),
                'output_sha256': digest.hexdigest(), 'output_tail': output[-16000:].decode('utf-8', 'replace')}
    finally:
        selector.close()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stdout.close()


def validate(job, changes):
    identity = patch_identity(job['data']['head_sha'], changes)
    policy_path = os.getenv('GATEWAY_ENGINEERING_VALIDATION_POLICY', '')
    if not policy_path:
        return {'status': 'BLOCKED', 'patch_identity': identity, 'reason': 'Configure an approved repository mirror, immutable container image and command list before publication'}
    policy_file = Path(policy_path)
    if not policy_file.is_file() or policy_file.stat().st_size > 65536:
        raise ValueError('Validation policy is missing or oversized')
    policy = json.loads(policy_file.read_text()).get(job['request']['repository'].lower())
    if not isinstance(policy, dict):
        return {'status': 'BLOCKED', 'patch_identity': identity, 'reason': 'Repository has no host-approved validation policy'}
    mirror = Path(policy['mirror']).resolve(strict=True)
    image = policy.get('image', '')
    commands = policy.get('commands')
    if not re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}', image):
        raise ValueError('Validation image must be pinned by digest')
    if not isinstance(commands, list) or not 1 <= len(commands) <= 10 or any(not isinstance(command, list) or not command or len(command) > 32 or any(not isinstance(arg, str) or not arg or len(arg) > 1024 or '\0' in arg for arg in command) for command in commands):
        raise ValueError('Validation commands must be a bounded host-approved argv list')
    head = job['data']['head_sha']
    if not re.fullmatch('[a-f0-9]{40}', head):
        raise ValueError('Validation needs an exact source revision')
    timeout = min(1800, max(1, int(policy.get('timeout_seconds', 600))))
    results = []
    with tempfile.TemporaryDirectory(prefix='engineering-validation-') as directory:
        root = Path(directory)
        # Archive is bounded while streaming, before extraction. No network fetch or credentials.
        archive = root / 'source.tar'
        with archive.open('wb') as output:
            archive_result = run_bounded(['git', '--git-dir=' + str(mirror), 'archive', '--format=tar', head], root, 60, 128 * 1024 * 1024, output)
        if archive_result['exit_code'] != 0:
            raise ValueError('Pinned revision is not available in the approved repository mirror')
        checkout = root / 'checkout'
        checkout.mkdir()
        with tarfile.open(archive) as source:
            members = source.getmembers()
            if len(members) > 100000 or sum(member.size for member in members) > 256 * 1024 * 1024:
                raise ValueError('Expanded repository exceeds the validation budget')
            for member in members:
                path = Path(member.name)
                if path.is_absolute() or '..' in path.parts or '.git' in path.parts or not (member.isfile() or member.isdir()):
                    raise ValueError('Validation archive contains an unsafe path or link')
            source.extractall(checkout, members=members, filter='data')
        for change in changes:
            path = Path(change['path'])
            if path.is_absolute() or '..' in path.parts or '.git' in path.parts:
                raise ValueError('Unsafe patch path')
            target = checkout / path
            if change['content'] is None:
                target.unlink()
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(change['content'])
        # The unprivileged container can modify this disposable checkout only.
        root.chmod(0o755)
        for path in [checkout, *checkout.rglob('*')]:
            path.chmod(0o777 if path.is_dir() or path.stat().st_mode & 0o111 else 0o666)
        for index, command in enumerate(commands):
            cidfile = root / ('container-' + str(index))
            argv = ['docker', 'run', '--rm', '--pull=never', '--cidfile=' + str(cidfile), '--network=none', '--cap-drop=ALL',
                    '--security-opt=no-new-privileges', '--read-only', '--pids-limit=128', '--memory=2g', '--cpus=2', '--user=65534:65534',
                    '--tmpfs=/tmp:rw,nosuid,nodev,size=512m', '--mount=type=bind,source=' + str(checkout) + ',target=/workspace',
                    '--workdir=/workspace', '--env=HOME=/tmp', image, *command]
            try:
                result = run_bounded(argv, root, timeout)
                results.append(dict(result, command=command))
            finally:
                if cidfile.exists():
                    container = cidfile.read_text().strip()
                    if re.fullmatch('[a-f0-9]{64}', container):
                        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
            if result['exit_code'] != 0:
                break
    return {'status': 'PASSED' if len(results) == len(commands) and all(item['exit_code'] == 0 for item in results) else 'FAILED',
            'patch_identity': identity, 'source_sha': head, 'image': image, 'commands': results, 'network': 'disabled', 'device_tested': False}
