"""Host-approved, isolated pre-publication validation. No model-selected shell commands."""
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed


_validation_cancel = ContextVar('validation_cancel', default=None)
_validation_deadline = ContextVar('validation_deadline', default=None)


@contextmanager
def cancellation_scope(cancel, deadline=None):
    token = _validation_cancel.set(cancel)
    deadline_token = _validation_deadline.set(deadline)
    try:
        yield
    finally:
        _validation_cancel.reset(token)
        _validation_deadline.reset(deadline_token)


def _report_path(checkout, relative):
    """A report names one file in the disposable checkout, never a linked target."""
    root = Path(checkout).resolve()
    if (not isinstance(relative, str) or not relative or Path(relative).is_absolute() or '\\' in relative
            or any(part in {'', '.', '..', '.git'} for part in relative.split('/'))):
        raise ValueError('Unsafe acceptance report path')
    path = root
    for part in relative.split('/'):
        path /= part
        # Reject symlink ancestors even when they point back inside the checkout:
        # removing the declared output must never remove another named file.
        if path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction()):
            raise ValueError('Acceptance report cannot traverse a filesystem link')
    if not path.resolve().is_relative_to(root):
        raise ValueError('Acceptance report escaped the isolated checkout')
    return path


def prepare_behavior_reports(coverage, checkout):
    """Invalidate declared outputs before their command, including tracked reports."""
    for relative in sorted({item['report_path'] for item in coverage}):
        path = _report_path(checkout, relative)
        if path.exists() and not path.is_file():
            raise ValueError('Acceptance report output must be a regular file')
        # Unlink only this declared file; never recursively remove report directories.
        path.unlink(missing_ok=True)


def capture_behavior_reports(coverage, checkout):
    """Snapshot the mapped cases immediately after the command has stopped.

    Later commands may overwrite or reuse a report path. Their output must not
    change the evidence already attributed to an earlier command.
    """
    wanted = {}
    for item in coverage:
        wanted.setdefault(item['report_path'], set()).add(item['case_id'])
    reports = {}
    for relative, names in wanted.items():
        try:
            path = _report_path(checkout, relative)
            if not path.is_file() or path.stat().st_size > 1024 * 1024:
                raise ValueError('Report unavailable')
            with path.open('rb') as stream:
                raw = stream.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024 or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                raise ValueError('Unsafe report')
            document = ET.fromstring(raw)
            cases = {}
            for node in document.iter('testcase'):
                name = '.'.join(part for part in (node.get('classname', ''), node.get('name', '')) if part)
                if name not in names:
                    continue
                status = 'FAILED' if node.find('failure') is not None or node.find('error') is not None else 'SKIPPED' if node.find('skipped') is not None else 'PASSED'
                cases[name] = 'UNAVAILABLE' if name in cases else status
            reports[relative] = {'cases': cases, 'sha256': hashlib.sha256(raw).hexdigest()}
        except (OSError, ValueError, ET.ParseError):
            reports[relative] = {'cases': {}, 'sha256': None}
    return reports


def named_test_results(command):
    """Only named cases in freshly captured reports can attest an individual test."""
    cases = []
    for snapshot in command.get('report_snapshots', {}).values():
        report_hash = snapshot.get('sha256')
        if not re.fullmatch(r'[a-f0-9]{64}', str(report_hash)):
            continue
        for case, status in snapshot.get('cases', {}).items():
            if status in {'PASSED', 'FAILED', 'SKIPPED'}:
                cases.append(dict(name=case, status=status, report_sha256=report_hash))
    return dict(case_results=cases[:32], case_results_truncated=len(cases) > 32)


def behavior_receipts(coverage, results, source_sha, image):
    """Only a mapped command's fresh report snapshot can verify its behavior cases."""
    evidence = []
    indexed = {item.get('command_index', index): item for index, item in enumerate(results)}
    for item in coverage:
        if item['command_index'] not in indexed:
            continue
        observed = indexed[item['command_index']]
        key = item['report_path']
        report = observed.get('report_snapshots', {}).get(key, {})
        status = report.get('cases', {}).get(item['case_id'], 'UNAVAILABLE')
        if observed['exit_code'] != 0 and status == 'PASSED':
            status = 'FAILED'
        evidence.append({'requirement_id': item['requirement_id'], 'kind': 'behavior_test', 'name': item['case_id'],
            'scope': 'behavior', 'status': status, 'observed_at_ms': int(time.time() * 1000), 'image': image,
            'report_sha256': report.get('sha256'), 'test_case': item['case_id'], 'report_path': key,
            'command_sha256': hashlib.sha256(json.dumps(observed['command']).encode()).hexdigest(),
            'output_sha256': observed['output_sha256'], 'exit_code': observed['exit_code'], 'source_sha': source_sha})
    return evidence


def patch_identity(head, changes):
    return hashlib.sha256(json.dumps([head, changes], sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def check_cancellation():
    cancel, deadline = _validation_cancel.get(), _validation_deadline.get()
    if cancel is not None and cancel.is_set():
        raise InterruptedError('Approved validation command cancelled')
    if deadline is not None and time.time() >= deadline:
        raise TimeoutError('Engineering job deadline exhausted before validation launch')


def run_bounded(argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None, *, stdin=subprocess.DEVNULL):
    check_cancellation()
    from v14.validation_host import command
    with command(argv) as approved:
        return _run_bounded(approved, cwd, timeout, maximum, destination, stdin=stdin)


def _run_bounded(argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None, *, stdin=subprocess.DEVNULL):
    started = time.monotonic()
    output = bytearray()
    total = 0
    digest = hashlib.sha256()
    environment = {'PATH': os.defpath, 'HOME': str(cwd), 'LANG': 'C.UTF-8'}
    # Windows needs SystemRoot to initialize runtime DLLs, including Winsock.
    # Keep the environment otherwise isolated from host credentials and hooks.
    if os.name == 'nt' and os.environ.get('SystemRoot'):
        environment['SystemRoot'] = os.environ['SystemRoot']
    check_cancellation()
    process = subprocess.Popen(argv, cwd=cwd, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env=environment)
    # Windows selectors accept sockets, not subprocess pipes. A bounded reader
    # also lets the deadline run while the child is silent or emits no newline.
    blocks = queue.Queue(maxsize=2)
    stopped = threading.Event()

    def send(value):
        while not stopped.is_set():
            try:
                blocks.put(value, timeout=.05)
                return
            except queue.Full:
                pass

    def read_output():
        try:
            while not stopped.is_set():
                block = process.stdout.read1(8192)
                send(block)
                if not block:
                    break
        except Exception as failure:
            send(failure)
        finally:
            process.stdout.close()

    reader = threading.Thread(target=read_output, name='validation-output', daemon=True)
    reader.start()
    try:
        while True:
            check_cancellation()
            remaining = timeout - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError('Approved validation command exceeded its deadline')
            try:
                block = blocks.get(timeout=min(.25, remaining))
            except queue.Empty:
                continue
            if isinstance(block, Exception):
                raise block
            if not block:
                break
            if total + len(block) > maximum:
                raise ValueError('Validation output exceeded its byte budget')
            total += len(block)
            digest.update(block)
            if destination is not None:
                destination.write(block)
            output.extend(block)
            del output[:-16000]
        # EOF does not mean the child exited. Keep observing the job's stop
        # and deadline while a command that closed its output is still alive.
        while True:
            check_cancellation()
            remaining = max(0, timeout - (time.monotonic() - started))
            try:
                code = process.wait(timeout=min(.25, remaining))
                break
            except subprocess.TimeoutExpired as failure:
                check_cancellation()
                if time.monotonic() - started >= timeout:
                    raise TimeoutError('Approved validation command exceeded its deadline') from failure
        return {'exit_code': code, 'elapsed_ms': round((time.monotonic() - started) * 1000),
                'output_sha256': digest.hexdigest(), 'output_bytes': total,
                'output_tail': output[-16000:].decode('utf-8', 'replace')}
    finally:
        stopped.set()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        # The reader owns the pipe. Do not close it under a blocked read: an
        # inherited pipe may stay open until container cleanup kills descendants.
        reader.join(timeout=.25)


class ValidationPolicyError(ValueError):
    """Closed admission category; never includes the configured host path or policy content."""
    def __init__(self, state, message):
        super().__init__(message)
        self.state = state


def load_policy(repository, *, policy_path=None):
    """One parser shared by admission and execution; host policy is never model-selected."""
    if policy_path is None:
        policy_path = os.getenv('GATEWAY_ENGINEERING_VALIDATION_POLICY', '')
    if not policy_path:
        raise ValidationPolicyError('NOT_CONFIGURED', 'Configure an approved repository mirror, immutable container image and command list before admission')
    policy_file = Path(policy_path)
    if not policy_file.is_file() or policy_file.stat().st_size > 65536:
        raise ValidationPolicyError('MISSING_OR_OVERSIZED', 'Validation policy is missing or oversized')
    document = json.loads(policy_file.read_text(encoding='utf-8-sig'))
    if not isinstance(document, dict):
        raise ValidationPolicyError('INVALID_DOCUMENT', 'Validation policy must be a repository mapping')
    policy = document.get(repository.lower())
    if not isinstance(policy, dict):
        raise ValidationPolicyError('REPOSITORY_UNCONFIGURED', 'Repository has no host-approved validation policy')
    return validate_policy_entry(policy)


def validate_policy_entry(policy, *, allow_unprepared_image=False):
    if type(policy.get('remote_profile_checks', False)) is not bool:
        raise ValueError('The host must explicitly enable checks requested by paired profiles')
    mirror = Path(policy['mirror']).resolve()
    image = policy.get('image', '')
    commands = policy.get('commands')
    if not (allow_unprepared_image is True and image == '') and not re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}', image):
        raise ValueError('Validation image must be pinned by digest')
    if not isinstance(commands, list) or not 1 <= len(commands) <= 10 or any(not isinstance(command, list) or not command or len(command) > 32 or any(not isinstance(arg, str) or not arg or len(arg) > 1024 or '\0' in arg for arg in command) for command in commands):
        raise ValueError('Validation commands must be a bounded host-approved argv list')
    from v14.workspace_hooks import settings as hook_settings
    hook_settings(policy, commands)
    groups = policy.get('parallel_command_groups', [])
    if not isinstance(groups, list) or len(groups) > 5:
        raise ValueError('Parallel validation groups exceed their host-approved bound')
    occupied = set()
    for group in groups:
        if (not isinstance(group, list) or not 2 <= len(group) <= len(commands) or
                any(type(index) is not int or not 0 <= index < len(commands) for index in group) or
                group != list(range(group[0], group[0] + len(group))) or occupied.intersection(group)):
            raise ValueError('Parallel validation groups must contain distinct contiguous command indexes')
        occupied.update(group)
    coverage = policy.get('acceptance_map', [])
    if not isinstance(coverage, list) or len(coverage) > 256:
        raise ValueError('Host acceptance coverage exceeds its bound')
    for item in coverage:
        if not isinstance(item, dict) or set(item) != {'requirement_id', 'case_id', 'command_index', 'scope', 'report_path'} or item['scope'] != 'behavior' or type(item['command_index']) is not int or not 0 <= item['command_index'] < len(commands) or any(not isinstance(item[key], str) or not 1 <= len(item[key]) <= 200 for key in ('requirement_id', 'case_id', 'report_path')):
            raise ValueError('Host acceptance coverage must identify an exact behavior case and approved command')
        if Path(item['report_path']).is_absolute() or any(part in {'', '.', '..', '.git'} for part in item['report_path'].split('/')) or '\\' in item['report_path']:
            raise ValueError('Acceptance report must be a bounded checkout-relative path')
    from v14.engineering_release_checks import contract
    contract(policy.get('post_merge'), len(commands))
    from v14 import workspace_environment
    setup_commands, public_env, _ = workspace_environment.read(policy)
    prepared = policy.get('prepared_environment')
    if setup_commands and not prepared and not allow_unprepared_image:
        raise ValueError('Prepare the custom setup commands before activating validation')
    if prepared is not None:
        if (not isinstance(prepared, dict) or prepared.get('base_image') != image or
                not re.fullmatch(r'sha256:[a-f0-9]{64}', str(prepared.get('image_id'))) or
                not re.fullmatch(r'[a-f0-9]{40}', str(prepared.get('source_sha')))):
            raise ValueError('Prepared environment does not match its approved source and base image')
        if (setup_commands or prepared.get('recipe_sha256')) and workspace_environment.recipe_id(setup_commands, public_env) != prepared.get('recipe_sha256'):
            raise ValueError('Prepare the changed custom setup before validation')
        image = prepared['image_id']
    return policy, mirror, image, commands, coverage


def _validation_workers(job):
    if job.get('request', {}).get('execution_mode') != 'full':
        return 1
    plan = job.get('data', {}).get('integration_batch')
    controls = plan.get('controls', {}) if isinstance(plan, dict) else {}
    workers = controls.get('local_validation_workers', 1) if isinstance(controls, dict) else 1
    if type(workers) is not int or not 1 <= workers <= 4:
        raise ValueError('The retained Full Batch validation worker limit is invalid')
    return workers


def checkout_container_user():
    # Linux bind mounts retain numeric owners. The host must be able to read
    # reports and remove private build-cache directories after the run.
    uid = os.geteuid() if sys.platform.startswith('linux') else 65534
    return f'{uid or 65534}:65534'


def materialize_checkout(job, mirror, changes, root, cancel):
    # Archive is bounded while streaming, before extraction. No network fetch or credentials.
    head = job['data']['head_sha']
    archive = root / 'source.tar'
    with archive.open('wb') as output, cancellation_scope(cancel, job.get('deadline')):
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
            target.write_bytes(change['content'].encode('utf-8'))
            if change.get('mode') in {'100755', '100644'}:
                target.chmod(int(change['mode'][-3:], 8))
    return checkout


def validate(job, changes, cancel=None, *, command_index=None, artifacts=None, retain_output=None, retain_build_output=None):
    identity = patch_identity(job['data']['head_sha'], changes)
    try:
        policy, mirror, image, commands, coverage = load_policy(job['request']['repository'])
    except (OSError, ValueError, KeyError, TypeError):
        return {'status': 'BLOCKED', 'patch_identity': identity, 'reason': 'Repository validation policy is unavailable or invalid; repair the host policy before continuing'}
    admitted_policy = job['data'].get('admission', {}).get('policy_sha256')
    if admitted_policy and admitted_policy != hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest():
        return {'status': 'BLOCKED', 'patch_identity': identity, 'reason': 'The host validation contract changed after admission; review the new policy before a new job'}
    if command_index is not None and (type(command_index) is not int or not 0 <= command_index < len(commands)):
        raise ValueError('Select an existing approved validation command')
    selected = list(range(len(commands))) if command_index is None else [command_index]
    selected_coverage = [item for item in coverage if item['command_index'] in selected]
    head = job['data']['head_sha']
    if not re.fullmatch('[a-f0-9]{40}', head):
        raise ValueError('Validation needs an exact source revision')
    timeout = min(1800, max(1, int(policy.get('timeout_seconds', 600))), max(1, job.get('deadline', time.time() + 1800) - time.time()))
    results = []
    workers = _validation_workers(job)
    parallel_groups = policy.get('parallel_command_groups', []) if workers > 1 and command_index is None else []
    groups_by_start = {group[0]: group for group in parallel_groups}
    with tempfile.TemporaryDirectory(prefix='engineering-validation-') as directory:
        root = Path(directory)
        checkout = materialize_checkout(job, mirror, changes, root, cancel)
        if artifacts:
            if (not isinstance(artifacts, dict) or len(artifacts) > 8
                    or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,103}', name)
                           or not isinstance(raw, bytes) or len(raw) > 128 * 1024 * 1024 for name, raw in artifacts.items())
                    or sum(map(len, artifacts.values())) > 256 * 1024 * 1024):
                raise ValueError('Invalid bounded release artifact inputs')
            artifact_root = checkout / '.gateway-artifacts'
            artifact_root.mkdir()  # A source path cannot impersonate downloaded artifacts.
            for name, raw in artifacts.items():
                (artifact_root / name).write_bytes(raw)
        # The unprivileged container can modify this disposable checkout only.
        root.chmod(0o755)
        for path in [checkout, *checkout.rglob('*')]:
            path.chmod(0o777 if path.is_dir() or path.stat().st_mode & 0o111 else 0o666)
        from v14.workspace_environment import read as read_environment
        _, public_env, limits = read_environment(policy)
        def run_command(index, command, command_checkout):
            command_coverage = [item for item in coverage if item['command_index'] == index]
            from v14 import workspace_outputs
            output_patterns = workspace_outputs.patterns(policy) if retain_build_output else ()
            baseline = workspace_outputs.files(command_checkout, output_patterns)
            prepare_behavior_reports(command_coverage, command_checkout)
            cidfile = root / ('container-' + str(index))
            argv = ['docker', 'run', '--rm', '--pull=never', '--cidfile=' + str(cidfile), '--network=none', '--cap-drop=ALL',
                    '--security-opt=no-new-privileges', '--read-only', '--pids-limit=256' if policy.get('prepared_environment') else '--pids-limit=128',
                    '--memory=' + str(limits['memory_gib'] if policy.get('workspace_environment') or policy.get('prepared_environment') else 2) + 'g', '--cpus=' + str(limits['cpus']), '--user=' + checkout_container_user(),
                    '--tmpfs=/tmp:rw,nosuid,nodev,size=2g' if policy.get('prepared_environment') else '--tmpfs=/tmp:rw,nosuid,nodev,size=512m', '--mount=type=bind,source=' + str(command_checkout) + ',target=/workspace',
                    '--workdir=/workspace', '--env=HOME=/tmp', *['--env=' + k + '=' + v for k, v in (public_env | {'GITHUB_SHA': head}).items()], image, *command]
            try:
                with cancellation_scope(cancel, job.get('deadline')):
                    seconds = min(timeout, max(1, job.get('deadline', time.time() + timeout) - time.time()))
                    if retain_output is None:
                        result = run_bounded(argv, root, seconds)
                    else:
                        with tempfile.TemporaryFile() as log:
                            result = run_bounded(argv, root, seconds, destination=log)
                            log.seek(0)
                            result['output_ref'] = retain_output(log)
            finally:
                if cidfile.exists():
                    container = cidfile.read_text().strip()
                    if re.fullmatch('[a-f0-9]{64}', container):
                        from v14.validation_host import command as host_command, environment as host_environment
                        with host_command(['docker', 'rm', '-f', container]) as cleanup:
                            subprocess.run(cleanup, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL, timeout=15, check=False, env=host_environment(root))
            if output_patterns:
                result['build_outputs'] = workspace_outputs.capture(command_checkout, output_patterns, baseline, retain_build_output)
            return dict(result, command=command, command_index=index, report_snapshots=capture_behavior_reports(command_coverage, command_checkout))

        index = 0
        while index < len(commands):
            if index not in selected:
                index += 1
                continue
            group = groups_by_start.get(index)
            if group and workers > 1:
                sandboxes = {}
                for command_index in group:
                    sandbox = root / ('parallel-checkout-' + str(command_index))
                    shutil.copytree(checkout, sandbox)
                    sandboxes[command_index] = sandbox
                group_results = {}
                with ThreadPoolExecutor(max_workers=min(workers, len(group)), thread_name_prefix='validation-suite') as pool:
                    pending = {pool.submit(run_command, command_index, commands[command_index], sandboxes[command_index]): command_index
                               for command_index in group}
                    for future in as_completed(pending):
                        command_index = pending[future]
                        group_results[command_index] = future.result()
                results.extend(group_results[command_index] for command_index in group)
                index += len(group)
                if any(item['exit_code'] != 0 for item in group_results.values()):
                    break
                continue
            result = run_command(index, commands[index], checkout)
            results.append(result)
            index += 1
            if result['exit_code'] != 0:
                break
        evidence = behavior_receipts(selected_coverage, results, head, image)
    passed = (len(results) == len(selected) and all(item['exit_code'] == 0 for item in results)
              and len(evidence) == len(selected_coverage) and all(item['status'] == 'PASSED' for item in evidence))
    return {'status': 'PASSED' if passed else 'FAILED',
            'scope': 'complete' if command_index is None else 'exploratory', 'complete_validation': command_index is None,
            'patch_identity': identity, 'source_sha': head, 'image': image, 'commands': results, 'network': 'disabled', 'device_tested': False,
            'validation_workers': workers, 'requirement_evidence': evidence}
