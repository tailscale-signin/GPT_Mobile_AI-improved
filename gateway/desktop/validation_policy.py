"""Local host selection of the existing engineering validation policy."""
import json
import os
from pathlib import Path
import re


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


def check_policy_file(value):
    """Validate configuration only, without executing commands or claiming runtime readiness."""
    path = _path(value)
    if not path:
        return {'state': 'NOT_CONFIGURED', 'repositories': 0}
    from v14.engineering_validation import load_policy
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
    if (not isinstance(document, dict) or not 1 <= len(document) <= 256 or
            any(not isinstance(repo, str) or repo != repo.lower() or
                not re.fullmatch(r'[a-z0-9_.-]+/[a-z0-9_.-]+', repo) for repo in document)):
        raise ValueError('Validation policy must name 1 to 256 repositories as lowercase owner/name')
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
