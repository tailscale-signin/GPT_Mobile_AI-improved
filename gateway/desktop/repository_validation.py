"""Local, host-approved repository validation setup. Never runs repository code on the host."""
from __future__ import annotations

import base64
import copy
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit

from desktop import development_profiles
from desktop.validation_policy import (
    PolicyConflictError, load_policy_snapshot, save_policy_snapshot, validate_policy_fields, validated_policy_bytes,
)
from v14.state_lock import StateDirectoryLock
from v14.validation_host import executable, command as host_command, environment as host_environment

DEFAULT_REPOSITORY = 'tailscale-signin/gpt_mobile_ai-improved'
DEFAULT_IMAGE = 'docker.io/library/python:3.12-slim-bookworm'
SOURCE_PROFILE = 'Recommended source checks'
CUSTOM_PROFILE = 'Custom / imported commands'
PIN = re.compile(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}')
MAX_POLICY_BYTES = 65536

# These are stdlib-only checks, not Android builds or behavior-test receipts.
SOURCE_COMMANDS = [
    ['python3', '-c', 'import ast; from pathlib import Path; roots=[Path(p) for p in ("gateway","authentication","scripts")]; assert all(p.is_dir() for p in roots), "Expected project source folders are missing"; files=sorted(p for r in roots for p in r.rglob("*.py")); assert files, "No Python sources found"; [ast.parse(p.read_bytes(), filename=str(p)) for p in files]; print("Python syntax checked:",len(files))'],
    ['python3', 'scripts/generate_diagnostic_schema.py', '--check'],
    ['python3', 'scripts/check_ci_policy.py'],
]

PROFILES = {SOURCE_PROFILE: SOURCE_COMMANDS, **development_profiles.COMMANDS}


class SetupCancelled(ValueError):
    pass


def cancelled(event):
    if event is not None and event.is_set():
        raise SetupCancelled('Setup cancelled. The previous validation settings remain selected.')


def repository_name(value):
    if not isinstance(value, str) or len(value) > 300:
        raise ValueError('Repository: enter owner/name or its GitHub HTTPS URL.')
    value = value.strip()
    if value.startswith('https://'):
        parsed = urlsplit(value)
        if parsed.netloc.lower() != 'github.com' or parsed.query or parsed.fragment:
            raise ValueError('Repository: use a github.com repository URL without credentials or query parameters.')
        value = parsed.path.strip('/')
    if value.endswith('.git'):
        value = value[:-4]
    value = value.lower()
    if (not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,99}/[a-z0-9_.-]{1,100}', value)
            or any(p in {'.', '..'} for p in value.split('/'))):
        raise ValueError('Repository: enter one owner/name. Uppercase names are normalized automatically.')
    return value


def mirror_path(value):
    if not isinstance(value, str) or not value or len(value) > 4096 or any(c in value for c in '\0\r\n'):
        raise ValueError('Mirror folder: choose an absolute local folder.')
    path = Path(value)
    if not path.is_absolute() or path == Path(path.anchor) or '..' in path.parts:
        raise ValueError('Mirror folder: choose an absolute folder, not a drive root or relative path.')
    for parent in (path, *path.parents):
        if parent.is_symlink() or (hasattr(parent, 'is_junction') and parent.is_junction()):
            raise ValueError('Mirror folder: filesystem links and junctions are not supported.')
    return path


def image_source(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 300 or value.startswith('-'):
        raise ValueError('Container image: enter a registry image name with a tag or SHA-256 digest.')
    if not (PIN.fullmatch(value) or re.fullmatch(r'(?:[a-z0-9][a-z0-9.-]*:[0-9]{1,5}/)?[a-z0-9][a-z0-9./_-]*:[A-Za-z0-9_.-]+', value)):
        raise ValueError('Container image: use an explicit tag or image@sha256:digest; do not paste a command.')
    return value


def image_repository(value):
    name = value.split('@', 1)[0]
    if ':' in name.rsplit('/', 1)[-1]:
        name = name.rsplit(':', 1)[0]
    if name.startswith('docker.io/'):
        name = name[len('docker.io/'):]
    if name.startswith('index.docker.io/'):
        name = name[len('index.docker.io/'):]
    if '/' not in name:
        name = 'library/' + name
    return name


def defaults(root, repository=DEFAULT_REPOSITORY):
    repository = repository_name(repository)
    # Preserve the first built-in default location. Explicit saved locations,
    # including a user-selected D: folder, are retained by options_for below.
    mirrors = Path(root).resolve() / 'data' / 'mirrors'
    mirror = (mirrors / 'GPT_Mobile_AI-improved.git' if repository == DEFAULT_REPOSITORY else
              mirrors / repository.split('/')[0] / (repository.split('/')[1] + '.git'))
    return {'repository': repository, 'mirror': str(mirror),
            'image_source': DEFAULT_IMAGE, 'image': '', 'timeout_seconds': 600,
            'workspace_terminal': False, 'profile': SOURCE_PROFILE, 'commands': copy.deepcopy(SOURCE_COMMANDS),
            'acceptance_map': [], 'parallel_command_groups': [],
            'allowed_checks': ['Required verification', 'Analyze (java-kotlin)'] if repository == DEFAULT_REPOSITORY else [],
            'post_merge': {'artifacts': [], 'installation_checks': [], 'smoke_checks': []},
            'acceptance_templates': [], 'draft_repair_requirements': []}


def read_settings(root):
    """Read legacy or managed settings without writing, downloading, or executing."""
    snapshot = load_policy_snapshot(root)
    if snapshot['error']:
        raise ValueError(snapshot['error'])
    return snapshot['repositories'], snapshot['revision']


def options_for(root, repository, document):
    name = repository_name(repository)
    result = defaults(root, name)
    saved = document.get(name)
    if saved is not None:
        if not isinstance(saved, dict):
            raise ValueError('Saved repository policy is invalid.')
        repairs = []
        result.update(copy.deepcopy(saved))
        for key in ('mirror', 'image', 'commands'):
            if key not in saved:
                repairs.append(key)
        if not isinstance(result['mirror'], str):
            result['mirror'] = defaults(root, name)['mirror']
            repairs.append('mirror')
        if type(result['timeout_seconds']) is not int:
            result['timeout_seconds'] = 600
            repairs.append('timeout')
        if not isinstance(result.get('image'), str) or not PIN.fullmatch(result['image']):
            result['image'] = ''
            repairs.append('image')
        if 'commands' not in saved:
            result['commands'] = []
        # Missing optional imported fields retain their unconfigured state;
        # suggested defaults apply only to a genuinely new repository entry.
        for key in ('allowed_checks', 'acceptance_map', 'parallel_command_groups',
                    'acceptance_templates', 'draft_repair_requirements'):
            result.setdefault(key, [])
            if key not in saved:
                result[key] = []
        meta = saved.get('desktop_setup', {})
        if not isinstance(meta, dict):
            meta = {}
            repairs.append('setup metadata')
            result['desktop_setup'] = {}
        source = meta.get('image_source', result['image'] or DEFAULT_IMAGE)
        try:
            result['image_source'] = image_source(source)
        except ValueError:
            result['image_source'] = result['image'] or DEFAULT_IMAGE
            repairs.append('image source')
        try:
            validate_policy_fields(saved)
        except (ValueError, KeyError, TypeError):
            repairs.append('saved policy fields')
        result['profile'] = (SOURCE_PROFILE if not repairs and saved.get('commands') == SOURCE_COMMANDS
                             and not saved.get('acceptance_map') and not saved.get('parallel_command_groups') else CUSTOM_PROFILE)
        saved_profile = meta.get('profile')
        if not repairs and saved_profile in development_profiles.COMMANDS and saved.get('commands') == PROFILES[saved_profile] and not saved.get('acceptance_map') and not saved.get('parallel_command_groups'):
            result['profile'] = saved_profile
        if repairs:
            result['repair_notice'] = ('Saved entry needs repair: ' + ', '.join(dict.fromkeys(repairs)) +
                                       '. Review these unsaved fields before saving.')
    result['repository'] = name
    return result


def validate_options(options):
    if not isinstance(options, dict):
        raise ValueError('Validation settings must be a JSON object.')
    result = copy.deepcopy(options)
    result['repository'] = repository_name(result.get('repository'))
    result['mirror'] = str(mirror_path(result.get('mirror')))
    result['image_source'] = image_source(result.get('image_source', DEFAULT_IMAGE))
    if result.get('profile') not in (*PROFILES, CUSTOM_PROFILE):
        raise ValueError('Choose a validation profile.')
    if type(result.get('workspace_terminal', False)) is not bool:
        raise ValueError('Choose whether this repository permits offline workspace commands')
    if result['profile'] in PROFILES:
        if result['profile'] == SOURCE_PROFILE and result['repository'] != DEFAULT_REPOSITORY:
            raise ValueError('The recommended checks target GPT Mobile. Choose Custom for a different repository.')
        result['commands'] = copy.deepcopy(PROFILES[result['profile']])
        if result.get('acceptance_map') or result.get('parallel_command_groups'):
            raise ValueError('Existing acceptance mappings require the Custom profile; they are never silently removed.')
    commands = result.get('commands')
    if (not isinstance(commands, list) or not 1 <= len(commands) <= 10
            or any(not isinstance(c, list) or not 1 <= len(c) <= 32
                   or any(not isinstance(a, str) or not a or len(a) > 1024 or '\0' in a for a in c) for c in commands)):
        raise ValueError('Commands: supply 1–10 argument arrays, each with 1–32 nonempty arguments. No shell expansion is applied.')
    timeout = result.get('timeout_seconds')
    if type(timeout) is not int or not 1 <= timeout <= 1800:
        raise ValueError('Timeout: enter a whole number from 1 to 1800 seconds per command.')
    fields = ('acceptance_map', 'parallel_command_groups', 'allowed_checks', 'acceptance_templates', 'draft_repair_requirements')
    if any(not isinstance(result.get(k, []), list) or len(result.get(k, [])) > 256 for k in fields):
        raise ValueError('Acceptance and CI fields must be bounded lists, not strings or objects.')
    checks = result.get('allowed_checks', [])
    if any(not isinstance(c, str) or not 1 <= len(c) <= 400 for c in checks):
        raise ValueError('Allowed CI checks must contain exact, nonempty check names.')
    if len(json.dumps(result, allow_nan=False).encode('utf-8')) > MAX_POLICY_BYTES:
        raise ValueError('Validation settings exceed 64 KiB.')
    pin = result.get('image', '')
    if pin and (not isinstance(pin, str) or not PIN.fullmatch(pin)):
        raise ValueError('Saved image digest is invalid. Clear it and prepare the image again.')
    if pin and (image_repository(pin) != image_repository(result['image_source']) or
                PIN.fullmatch(result['image_source']) and pin.split('@')[1] != result['image_source'].split('@')[1]):
        raise ValueError('Image source changed. Use Refresh image to approve a new digest.')
    from v14 import workspace_environment
    setup_commands, env, _ = workspace_environment.read(result)
    if setup_commands and result['profile'] != CUSTOM_PROFILE:
        raise ValueError('Choose Custom to prepare explicit setup commands')
    prepared = result.get('prepared_environment') or {}
    if (prepared.get('base_image') != pin or result['profile'] not in (prepared.get('profile'), CUSTOM_PROFILE) or
            (setup_commands or prepared.get('recipe_sha256')) and prepared.get('recipe_sha256') != workspace_environment.recipe_id(setup_commands, env)):
        result.pop('prepared_environment', None)
    validate_policy_fields(dict(result, image=pin), allow_unprepared_image=True)
    return result


def entry_from_options(options, existing=None):
    """One pure mapper for direct Save and setup; no provision or activation."""
    options = validate_options(options)
    entry = copy.deepcopy(existing or {})
    if not isinstance(entry, dict):
        raise ValueError('Saved repository policy is invalid.')
    metadata = entry.get('desktop_setup', {})
    if not isinstance(metadata, dict):
        # This pure mapper is used only for an explicit reviewed Save/Prepare.
        # The original selected file is immutable and remains available; valid
        # metadata dictionaries and all their unknown keys are retained.
        metadata = {}
    for key in ('mirror', 'image', 'commands', 'timeout_seconds', 'acceptance_map', 'parallel_command_groups',
                'allowed_checks', 'acceptance_templates', 'draft_repair_requirements'):
        entry[key] = copy.deepcopy(options.get(key, [] if key not in {'mirror', 'image', 'timeout_seconds'} else ''))
    if options.get('workspace_environment') or 'workspace_environment' in entry:
        entry['workspace_environment'] = copy.deepcopy(options.get('workspace_environment', {}))
    if options.get('workspace_hooks') or 'workspace_hooks' in entry:
        entry['workspace_hooks'] = copy.deepcopy(options.get('workspace_hooks', {}))
    if options.get('workspace_terminal') or 'workspace_terminal' in entry:
        entry['workspace_terminal'] = options.get('workspace_terminal', False)
    prepared = options.get('prepared_environment') or {}
    if (prepared.get('profile') == options.get('profile') or options.get('profile') == CUSTOM_PROFILE) and prepared.get('base_image') == options.get('image') and prepared:
        entry['prepared_environment'] = copy.deepcopy(prepared)
    else:
        entry.pop('prepared_environment', None)
    entry['post_merge'] = copy.deepcopy(options.get('post_merge', {'artifacts': [], 'installation_checks': [], 'smoke_checks': []}))
    entry['desktop_setup'] = dict(metadata, schema=1, image_source=options['image_source'], profile=options['profile'])
    return entry


def run(argv, *, timeout=60, env=None, cancel=None, label='Setup command'):
    """Bounded subprocess output; no shell, secret arguments or raw failures in the UI."""
    import queue
    from desktop import windows_job
    cancelled(cancel)
    job = windows_job.create()
    process = None
    reader = None
    blocks = queue.Queue(maxsize=2)
    stopped = threading.Event()
    read_failure = object()

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
        except Exception:
            send(read_failure)
        finally:
            # The reader owns the pipe; do not close it under a blocked read.
            try:
                process.stdout.close()
            except OSError:
                pass

    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   env=env, shell=False, start_new_session=os.name != 'nt',
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        windows_job.attach(job, process)
        deadline = time.monotonic() + timeout
        reader = threading.Thread(target=read_output, name='validation-setup-output', daemon=True)
        reader.start()
        raw = bytearray()
        eof = False
        while not eof or process.poll() is None:
            cancelled(cancel)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError(label + ' timed out. Settings were not changed; check connectivity and try again.')
            if eof:
                # Closed stdout does not mean the child has exited. Keep both
                # cancellation and the deadline active until it actually does.
                time.sleep(min(.05, remaining))
                continue
            try:
                block = blocks.get(timeout=min(.05, remaining))
            except queue.Empty:
                continue
            if block is read_failure:
                raise ValueError(label + ' output could not be read. Settings were not changed.')
            if not block:
                eof = True
                continue
            if len(raw) + len(block) > 1024 * 1024:
                raise ValueError(label + ' exceeded its output budget. Settings were not changed.')
            raw.extend(block)
        cancelled(cancel)
        if process.returncode:
            raise ValueError(f'{label} failed (exit {process.returncode}). Check GitHub access, Git and Docker Desktop. Settings were not changed.')
        try:
            return raw.decode('utf-8', errors='strict').strip()
        except UnicodeDecodeError:
            raise ValueError(label + ' returned invalid text. Settings were not changed.') from None
    finally:
        stopped.set()
        try:
            windows_job.close(job)
            if process is not None:
                if os.name != 'nt':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                elif process.poll() is None:
                    process.kill()
                process.wait(timeout=15)
        finally:
            if reader is not None and reader.ident is not None:
                reader.join(timeout=.25)
            elif process is not None and process.stdout is not None:
                process.stdout.close()


def git_environment(token=None):
    # Only this Git child receives the PC-selected GitHub credential. Never store it in a URL or policy.
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    if token:
        from desktop.github_credentials import token_bytes
        token_bytes(token)
        encoded = base64.b64encode(('x-access-token:' + token).encode('utf-8')).decode('ascii')
        env.update(GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.https://github.com/.extraheader',
                   GIT_CONFIG_VALUE_0='Authorization: Basic ' + encoded)
    return env


def git_prefix(git):
    return [git, '-c', 'core.hooksPath=' + os.devnull, '-c', 'core.fsmonitor=false',
            '-c', 'credential.helper=', '-c', 'http.followRedirects=false', '-c', 'protocol.allow=never',
            '-c', 'protocol.https.allow=always', '-c', 'gc.auto=0', '-c', 'maintenance.auto=false']


def check_mirror(git, repository, mirror, *, cancel=None):
    mirror_path(str(mirror))
    if not mirror.is_dir():
        raise ValueError('Mirror is missing. Choose Prepare & save to create it.')
    prefix = git_prefix(git) + ['--git-dir=' + str(mirror)]
    env = git_environment()
    config = run(prefix + ['config', '--no-includes', '--local', '--list'], env=env, cancel=cancel, label='Read mirror configuration')
    for line in config.splitlines():
        key, _, value = line.partition('=')
        if key.casefold() == 'extensions.worktreeconfig' and value.strip().casefold() not in {'false', 'no', 'off', '0'}:
            raise ValueError('Mirror has separate worktree configuration. Use a clean dedicated mirror.')
    if any(line.lower().startswith(('include.', 'includeif.', 'url.', 'http.', 'core.sshcommand=')) for line in config.splitlines()):
        raise ValueError('Mirror has custom redirect, include or HTTP settings. Use a clean dedicated mirror.')
    if run(prefix + ['rev-parse', '--is-bare-repository'], env=env, cancel=cancel, label='Check bare mirror') != 'true':
        raise ValueError('This is a working checkout, not a bare mirror. Choose a new mirror folder; nothing was overwritten.')
    origin = run(prefix + ['config', '--get-all', 'remote.origin.url'], env=env, cancel=cancel, label='Check mirror origin')
    if repository_name(origin) != repository:
        raise ValueError('Mirror belongs to a different repository. Choose its correct folder or a new empty destination.')
    head = run(prefix + ['rev-parse', '--verify', 'HEAD^{commit}'], env=env, cancel=cancel, label='Check mirror commit')
    if not re.fullmatch('[a-f0-9]{40}', head):
        raise ValueError('Mirror does not contain a supported source commit.')
    return head


def prepare_mirror(git, repository, mirror, *, token=None, cancel=None):
    mirror_path(str(mirror))
    url = 'https://github.com/' + repository + '.git'
    if mirror.exists():
        check_mirror(git, repository, mirror, cancel=cancel)
        # Explicit verified origin; no push, no object pruning and no repository hooks.
        run(git_prefix(git) + ['--git-dir=' + str(mirror), 'fetch', '--quiet', '--prune', url, '+refs/*:refs/*'],
            env=git_environment(token), timeout=900, cancel=cancel, label='Refresh GitHub mirror')
    else:
        mirror.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.gateway-mirror-', dir=mirror.parent) as temp:
            staged = Path(temp) / 'source.git'
            run(git_prefix(git) + ['clone', '--quiet', '--mirror', '--', url, str(staged)],
                env=git_environment(token), timeout=900, cancel=cancel, label='Create GitHub mirror')
            check_mirror(git, repository, staged, cancel=cancel)
            cancelled(cancel)
            if mirror.exists():
                raise ValueError('Mirror destination appeared during setup. It was not overwritten; reload and check it.')
            staged.rename(mirror)
    return check_mirror(git, repository, mirror, cancel=cancel)


def docker_run(arguments, *, cancel=None, timeout=60, label='Docker setup'):
    # Use the exact same local daemon/configuration isolation as job execution.
    with host_command(['docker', *arguments]) as argv:
        return run(argv, cancel=cancel, timeout=timeout, label=label, env=host_environment(Path.cwd()))


def check_docker(docker, *, cancel=None):
    kind = docker_run(['info', '--format', '{{.OSType}}'], cancel=cancel, label='Connect to local Docker Desktop')
    if kind != 'linux':
        raise ValueError('Switch Docker Desktop to Linux containers before preparing validation.')


def pin_image(docker, source, existing='', *, prepare=False, cancel=None):
    source = image_source(source)
    requested = existing or (source if PIN.fullmatch(source) else '')
    if existing and (image_repository(existing) != image_repository(source) or
                     PIN.fullmatch(source) and existing.split('@')[1] != source.split('@')[1]):
        raise ValueError('Image source changed. Use Refresh image to approve a new digest.')
    if prepare:
        docker_run(['pull', '--quiet', requested or source], timeout=900, cancel=cancel, label='Download validation image')
    elif not requested:
        raise ValueError('Image is not prepared yet. Choose Prepare & save to resolve and pin its real digest.')
    try:
        digests = json.loads(docker_run(['image', 'inspect', '--format', '{{json .RepoDigests}}', requested or source],
                                cancel=cancel, label='Inspect validation image'))
    except (TypeError, json.JSONDecodeError):
        raise ValueError('Docker did not return a valid registry digest. No policy was saved.') from None
    candidates = sorted({d for d in digests if isinstance(d, str) and PIN.fullmatch(d)
                         and image_repository(d) == image_repository(source)}) if isinstance(digests, list) else []
    if requested:
        candidates = [d for d in candidates if d.split('@')[1] == requested.split('@')[1]]
    if len(candidates) != 1:
        raise ValueError('Docker did not confirm one matching registry digest. Local image IDs are not accepted as registry digests.')
    pinned = candidates[0]
    # Resolve the exact digest locally, not just a possibly moving tag.
    docker_run(['image', 'inspect', pinned], cancel=cancel, label='Verify pinned image is installed')
    return pinned


def save_snapshot(root, repository, entry, revision, *, cancel=None, enabled=True):
    """Merge one reviewed entry only against its observed snapshot, then use the shared CAS."""
    cancelled(cancel)
    observed = load_policy_snapshot(root)
    if observed['revision'] != revision:
        raise PolicyConflictError('Validation settings changed in another window. Reload before saving; newer settings were preserved.')
    document = copy.deepcopy(observed['repositories'])
    document[repository_name(repository)] = entry
    # Prepare & save promises to retain this reviewed draft. The separate UI
    # disable path deliberately tolerates broken drafts; setup never claims it
    # saved an invalid one merely because disabling the selector succeeded.
    validated_policy_bytes(document)
    try:
        return save_policy_snapshot(root, document, enabled=enabled, expected_revision=revision, cancel=cancel)
    except InterruptedError as failure:
        raise SetupCancelled(str(failure)) from failure


def setup(root, options, revision, *, prepare, token=None, cancel=None, progress=lambda message: None, enabled=True):
    options = validate_options(options)
    root = Path(root).resolve()
    lock = StateDirectoryLock(root / 'data' / 'repository-validation-setup' / 'owner')
    lock.acquire()
    try:
        observed = load_policy_snapshot(root)
        if observed['revision'] != revision:
            raise PolicyConflictError('Validation settings changed. Reload before preparing or saving.')
        # Validate the complete edited entry before any external command. An
        # unprepared image is allowed here only; persistence remains strict.
        entry_from_options(options, observed['repositories'].get(options['repository'], {}))
        progress('Checking Git and Docker Desktop…')
        git, docker = executable('git'), executable('docker')
        check_docker(docker, cancel=cancel)
        repository, mirror = options['repository'], Path(options['mirror'])
        progress('Creating / refreshing the approved mirror…' if prepare else 'Checking the existing mirror…')
        head = (prepare_mirror(git, repository, mirror, token=token, cancel=cancel) if prepare
                else check_mirror(git, repository, mirror, cancel=cancel))
        progress('Preparing the digest-pinned image…' if prepare else 'Checking the pinned image…')
        pinned = pin_image(docker, options['image_source'], options.get('image', ''), prepare=prepare, cancel=cancel)
        custom = options.get('workspace_environment') or {}
        if options['profile'] in development_profiles.COMMANDS or custom.get('setup_commands'):
            if prepare:
                progress('Preparing the repository toolchain and dependencies in a local image…')
                options['prepared_environment'] = development_profiles.prepare_image(options['profile'], pinned, mirror, head, cancel, **({'custom': custom} if custom.get('setup_commands') else {}))
            else:
                retained = options.get('prepared_environment') or {}
                if retained.get('base_image') != pinned or retained.get('profile') != options['profile'] or not re.fullmatch(r'sha256:[a-f0-9]{64}', retained.get('image_id', '')):
                    raise ValueError('Choose Prepare & save to build the selected development profile')
                docker_run(['image', 'inspect', retained['image_id']], cancel=cancel, label='Inspect prepared environment')
        entry = entry_from_options(dict(options, image=pinned, mirror=str(mirror)),
                                   observed['repositories'].get(repository, {}))
        snapshot = None
        if prepare:
            snapshot = save_snapshot(root, repository, entry, revision, cancel=cancel, enabled=enabled)
        return {'repository': repository, 'image': entry.get('prepared_environment', {}).get('image_id', pinned),
                'base_image': pinned, 'mirror_head': head, 'saved': prepare,
                'state': 'PREREQUISITES_READY', 'tests_executed': False,
                'acceptance_mappings': len(entry['acceptance_map']), 'snapshot': snapshot, 'entry': entry}
    finally:
        lock.release()


def reset_settings(root, *, expected_revision):
    """Explicit revision-checked disable; pending preparation cannot re-enable it."""
    snapshot = load_policy_snapshot(root)
    if snapshot['revision'] != expected_revision:
        raise PolicyConflictError('Validation settings changed. Reload before disabling.')
    try:
        return save_policy_snapshot(root, snapshot['repositories'], enabled=False, expected_revision=expected_revision)
    except InterruptedError as failure:
        raise SetupCancelled(str(failure)) from failure
