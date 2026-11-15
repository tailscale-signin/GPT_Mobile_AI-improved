"""Explicit host policy selection, bounded editing and crash-safe activation."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid


SETTING = 'GATEWAY_ENGINEERING_VALIDATION_POLICY'
DEFAULT_REPOSITORY = 'tailscale-signin/gpt_mobile_ai-improved'
DEFAULT_IMAGE = 'docker.io/library/python:3.12-slim'
DEFAULT_ORIGIN = 'https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git'
POLICY_LIMIT = 65536


class PolicyConflictError(ValueError):
    """The editor no longer describes the selected policy; reload before saving."""


def _path(value):
    if not isinstance(value, str) or len(value) > 4096 or '\0' in value:
        raise ValueError('Validation policy must be a bounded file path')
    if value and not Path(value).is_absolute():
        raise ValueError('Choose an absolute validation policy file path')
    return value


def _selection_path(root):
    return Path(root).resolve() / 'data' / 'validation-settings.json'


def _parse_selection(content):
    if len(content) > 8192:
        raise ValueError('Saved validation policy selection is oversized')
    try:
        value = json.loads(content)
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError('Saved validation policy selection is invalid') from None
    if (not isinstance(value, dict) or set(value) != {'schema', 'policy_path'} or
            type(value['schema']) is not int or value['schema'] != 1):
        raise ValueError('Saved validation policy selection is invalid')
    return _path(value['policy_path'])


def selected_policy_path(root, inherited=None):
    """One schema-1 selector serves current and rollback releases, including disable."""
    # Keep the caller's path spelling for the legacy selector API.
    settings = Path(root) / 'data' / 'validation-settings.json'
    try:
        with settings.open('rb') as stream:
            content = stream.read(8193)
    except FileNotFoundError:
        try:
            settings.lstat()
        except FileNotFoundError:
            pass
        else:
            raise ValueError('Saved validation policy selection is an unreadable link') from None
        return _path((os.environ if inherited is None else inherited).get(SETTING, ''))
    return _parse_selection(content)


def active_policy_path(root):
    selected = selected_policy_path(root)
    if selected or (Path(root) / 'data' / 'validation-settings.json').exists():
        return selected
    # The first built-in release's explicit Save wrote only this file. Preserve
    # that format only before any explicit selector/inherited policy exists.
    # Current immutable revisions and the editor pointer are never authorities.
    legacy = managed_policy_path(root)
    if legacy.exists():
        check_policy_file(str(legacy))
        return str(legacy)
    return ''


def _decode_document(content):
    if len(content) > POLICY_LIMIT:
        raise ValueError('Validation policy exceeds the 64 KiB limit')
    try:
        document = json.loads(content)
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError('Validation policy must contain a JSON repository mapping') from None
    _validate_document(document)
    return document


def _read_document(path):
    try:
        with Path(path).open('rb') as stream:
            content = stream.read(POLICY_LIMIT + 1)
    except OSError:
        raise ValueError('Validation policy file is missing or unreadable') from None
    return _decode_document(content)


def _validate_document(document):
    if (not isinstance(document, dict) or not 1 <= len(document) <= 256 or
            any(not isinstance(repo, str) or not re.fullmatch(r'[a-z0-9_.-]+/[a-z0-9_.-]+', repo)
                or not isinstance(policy, dict) for repo, policy in document.items())):
        raise ValueError('Validation policy must name 1 to 256 repositories as lowercase owner/name')


def _requirements(value):
    # Reuse the request's exact acceptance grammar; this does not admit a job,
    # contact GitHub, run a command, or confer evidence on an unexecuted case.
    from v14.engineering import validate_request
    validate_request({'repository': 'policy/validation', 'branch': 'policy-check', 'base': 'main',
                      'objective': 'Validate saved policy', 'model': 'policy-validation',
                      'request_id': 'policy-validation', 'acceptance': value})


def validate_policy_fields(policy, *, allow_unprepared_image=False):
    from v14.engineering_validation import validate_policy_entry
    if 'desktop_setup' in policy and not isinstance(policy['desktop_setup'], dict):
        raise ValueError('Saved desktop setup metadata must be an object; review this repository before saving a repair')
    if not _path(policy.get('mirror', '')):
        raise ValueError('Enter an absolute path for the Git mirror')
    timeout = policy.get('timeout_seconds', 600)
    if type(timeout) is not int or not 1 <= timeout <= 1800:
        raise ValueError('Timeout must be between 1 and 1800 seconds')
    if not isinstance(policy.get('image'), str):
        raise ValueError('Enter a container image pinned by digest')
    validate_policy_entry(policy, allow_unprepared_image=allow_unprepared_image)
    checks = policy.get('allowed_checks', [])
    if (not isinstance(checks, list) or len(checks) > 256 or
            any(not isinstance(name, str) or not name.strip() or len(name) > 400 or '\0' in name for name in checks)):
        raise ValueError('Allowed CI checks must be a list of at most 256 exact check names')
    templates = policy.get('acceptance_templates', [])
    if not isinstance(templates, list) or len(templates) > 32:
        raise ValueError('At most 32 objective acceptance templates are supported')
    for template in templates:
        if not isinstance(template, dict) or set(template) != {'objectives', 'requirements'}:
            raise ValueError('Acceptance templates need objectives and requirements')
        terms = template['objectives']
        if (not isinstance(terms, list) or not 1 <= len(terms) <= 16 or
                any(not isinstance(term, str) or not 3 <= len(term.strip()) <= 16000 or '\0' in term for term in terms)):
            raise ValueError('Each template needs 1 to 16 bounded exact objectives')
        _requirements(template['requirements'])
    _requirements(policy.get('draft_repair_requirements', []))


def validated_policy_bytes(document):
    _validate_document(document)
    try:
        raw = json.dumps(document, indent=2, allow_nan=False).encode('utf-8')
        if len(raw) > POLICY_LIMIT:
            raise ValueError('Validation policy exceeds the 64 KiB limit')
        for policy in document.values():
            validate_policy_fields(policy)
    except (KeyError, TypeError, OverflowError, RecursionError):
        raise ValueError('Validation policy fields must use the supported bounded JSON forms') from None
    return raw


def check_policy_file(value):
    """Validate configuration only; source, runtime and behavior readiness remain separate."""
    path = _path(value)
    if not path:
        return {'state': 'NOT_CONFIGURED', 'repositories': 0}
    document = _read_document(path)
    validated_policy_bytes(document)
    return {'state': 'VALIDATED', 'repositories': len(document)}


@contextmanager
def _edit_lock(root):
    from v14.state_lock import StateDirectoryAlreadyOwned, StateDirectoryLock
    # A separate directory avoids the running Gateway's durable-store lock.
    lock = StateDirectoryLock(Path(root).resolve() / 'data' / 'validation-editor-lock' / 'owner')
    deadline = time.monotonic() + 2
    while True:
        try:
            lock.acquire()
            break
        except StateDirectoryAlreadyOwned:
            if time.monotonic() >= deadline:
                raise PolicyConflictError('Another Settings window is saving. Reload before trying again.') from None
            time.sleep(.02)
    try:
        yield
    finally:
        lock.release()


def save_policy_path(root, value):
    """Legacy explicit selector API, serialized with managed editor writes."""
    from desktop.configuration import atomic_json
    with _edit_lock(root):
        checked = check_policy_file(value)
        atomic_json(_selection_path(root), {'schema': 1, 'policy_path': _path(value)}, retry_windows_sharing=True)
    return checked


def managed_policy_path(root):
    """The previous release's unmanaged draft location, retained for explicit migration."""
    return Path(root).resolve() / 'data' / 'repository-validation-policy.json'


def default_policy(root):
    return {
        'mirror': str(Path(root).resolve() / 'data' / 'mirrors' / 'GPT_Mobile_AI-improved.git'),
        'image': '',
        'timeout_seconds': 600,
        'commands': [
            ['python3', '-m', 'compileall', '-q', 'gateway', 'authentication', 'scripts'],
            ['python3', 'scripts/check_ci_policy.py'],
        ],
        'parallel_command_groups': [],
        'acceptance_map': [],
        'allowed_checks': [],
        'acceptance_templates': [],
        'draft_repair_requirements': [],
    }


def _observe(path, maximum):
    """Bounded bytes and error identity used by CAS, including malformed saved files."""
    path = Path(path)
    try:
        info = path.stat()
        if not stat.S_ISREG(info.st_mode):
            return {'error': 'Saved validation state is not a regular file', 'token': ['not-file', info.st_mode, info.st_mtime_ns]}
        with path.open('rb') as stream:
            raw = stream.read(maximum + 1)
        token = ['bytes', hashlib.sha256(raw).hexdigest()]
        if len(raw) > maximum:
            token += [info.st_size, info.st_mtime_ns]
        return {'raw': raw, 'token': token}
    except FileNotFoundError:
        try:
            entry = path.lstat()
        except FileNotFoundError:
            return {'missing': True, 'token': ['missing']}
        except OSError as error:
            return {'error': 'Saved validation state is unreadable', 'token': ['unreadable', error.errno]}
        return {'error': 'Saved validation state is an unreadable filesystem link',
                'token': ['unreadable-entry', entry.st_mode, entry.st_size, entry.st_mtime_ns]}
    except OSError as error:
        return {'error': 'Saved validation state is unreadable', 'token': ['unreadable', error.errno]}


def _editor_path(root):
    return Path(root).resolve() / 'data' / 'validation-editor.json'


def _revision_path(root, digest):
    return Path(root).resolve() / 'data' / 'validation-policies' / (digest + '.json')


def _snapshot(root):
    root = Path(root).resolve()
    observations = []

    def observe(path, maximum):
        result = _observe(path, maximum)
        observations.append([str(path), result['token']])
        return result

    selector = observe(_selection_path(root), 8192)
    editor = observe(_editor_path(root), 8192)
    selection, error, repositories = '', '', {}
    legacy_selection = False
    try:
        if selector.get('error'):
            raise ValueError(selector['error'])
        if selector.get('missing'):
            inherited = os.environ.get(SETTING, '')
            observations.append(['inherited-selection', inherited])
            selection = _path(inherited)
            if not selection and managed_policy_path(root).exists():
                selection = str(managed_policy_path(root))
                legacy_selection = True
        else:
            selection = _parse_selection(selector['raw'])
    except ValueError as failure:
        error = str(failure)

    draft_path = None
    try:
        if editor.get('error'):
            raise ValueError(editor['error'])
        if not editor.get('missing'):
            pointer = _parse_selection(editor['raw'])
            if pointer:
                candidate = Path(pointer)
                if not re.fullmatch(r'[a-f0-9]{64}(?:-[a-f0-9]{32})?\.json', candidate.name) or candidate != _revision_path(root, candidate.stem):
                    raise ValueError('Saved validation draft reference is invalid')
                draft_path = candidate
        else:
            draft_path = managed_policy_path(root)
    except ValueError as failure:
        if not selection and not error:
            error = str(failure)

    documents = {}
    for path in dict.fromkeys(([Path(selection)] if selection else []) + ([draft_path] if draft_path else [])):
        document = observe(path, POLICY_LIMIT)
        documents[str(path)] = document
    editing_path = str(Path(selection)) if selection else str(draft_path) if draft_path else ''
    observed = documents.get(editing_path)
    if observed:
        try:
            if observed.get('error'):
                raise ValueError(observed['error'])
            if observed.get('missing'):
                if selection or editor.get('raw'):
                    raise ValueError('Validation policy file is missing or unreadable')
            else:
                repositories = _decode_document(observed['raw'])
                validated_policy_bytes(repositories)
        except ValueError as failure:
            if not error:
                error = str(failure)
            if legacy_selection:
                selection = ''
    revision = hashlib.sha256(json.dumps(observations, sort_keys=True).encode()).hexdigest()
    return {'enabled': bool(selection), 'repositories': repositories, 'revision': revision, 'selection': selection,
            'state': 'INVALID' if error else 'READY' if selection else 'DISABLED', 'error': error}


def load_policy_snapshot(root):
    """Return an editable snapshot; malformed selection stays visible and repairable."""
    with _edit_lock(root):
        return _snapshot(root)


def read_desktop_policy(root):
    snapshot = load_policy_snapshot(root)
    if snapshot['error']:
        raise ValueError(snapshot['error'])
    return snapshot['repositories']


def save_policy_snapshot(root, document, *, enabled, expected_revision, cancel=None):
    """Activate only by an atomic schema-1 selector swap after writing an immutable draft."""
    from desktop.configuration import atomic_json
    if type(enabled) is not bool:
        raise ValueError('Validation enabled must be a boolean')
    if not isinstance(expected_revision, str) or not re.fullmatch(r'[a-f0-9]{64}', expected_revision):
        raise PolicyConflictError('Reload the validation policy before saving.')
    raw = None
    try:
        raw = validated_policy_bytes(document)
    except ValueError:
        if enabled:
            raise
        # Disable remains possible even when the editor contains a broken draft.
        # Invalid edits are neither saved nor activated.
    with _edit_lock(root):
        current = _snapshot(root)
        if current['revision'] != expected_revision:
            raise PolicyConflictError('Validation settings changed in another window or on disk. Reload before saving.')
        if cancel is not None and cancel.is_set():
            raise InterruptedError('Validation save cancelled before activation. The previous selection is unchanged.')
        policy_path = ''
        if raw is not None:
            digest = hashlib.sha256(raw).hexdigest()
            destination = _revision_path(root, digest)
            existing = _observe(destination, POLICY_LIMIT)
            if not existing.get('missing') and existing.get('raw') != raw:
                # A local edit may have damaged an earlier selected revision.
                # Repair into a fresh path; never overwrite bytes an old engine
                # or rollback reader can still have selected.
                destination = _revision_path(root, digest + '-' + uuid.uuid4().hex)
                existing = _observe(destination, POLICY_LIMIT)
            if existing.get('missing'):
                atomic_json(destination, document, retry_windows_sharing=True)
            elif existing.get('raw') != raw:
                raise ValueError('A saved policy revision changed during repair; reload before saving.')
            policy_path = str(destination)
            # Only the selector below is an activation point. A failed selector
            # write can leave a retained inactive draft, never changed active bytes.
            atomic_json(_editor_path(root), {'schema': 1, 'policy_path': policy_path}, retry_windows_sharing=True)
        if cancel is not None and cancel.is_set():
            raise InterruptedError('Validation save cancelled before activation. The previous selection is unchanged.')
        atomic_json(_selection_path(root), {'schema': 1, 'policy_path': policy_path if enabled else ''}, retry_windows_sharing=True)
        result = _snapshot(root)
        result['draft_saved'] = raw is not None
        return result


def save_desktop_policy(root, document, *, expected_revision):
    return save_policy_snapshot(root, document, enabled=True, expected_revision=expected_revision)
