"""Host-owned optional coding-agent configuration. Phone requests cannot set endpoints.

Credentials are environment references, never values in config or public rows.
Local runtimes execute in digest-pinned containers, not in the Gateway process.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

from v14.state_lock import StateDirectoryLock

SCHEMA = 'gateway.agent-runs.v1'
CATALOG = {
    'codex': ('Codex', 'codex', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'github_copilot': ('GitHub Copilot', 'copilot', ['start', 'status']),
    'copilot_sdk': ('Copilot SDK', 'sdk', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'jules': ('Jules', 'https', ['start', 'status', 'events', 'steer', 'approve_plan', 'artifacts', 'import']),
    'cursor': ('Cursor Cloud', 'https', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'openhands': ('OpenHands', 'sdk', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'cline': ('Cline', 'sdk', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'claude': ('Claude Agent SDK', 'sdk', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'kiro': ('Kiro ACP', 'acp', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'devin': ('Devin ACP', 'acp', ['start', 'status', 'events', 'steer', 'cancel', 'artifacts', 'import']),
    'aider': ('Aider', 'cli', ['start', 'status', 'events', 'cancel', 'artifacts', 'import'])
}
ORIGINS = {'jules': 'https://jules.googleapis.com', 'cursor': 'https://api.cursor.com'}
ENV = re.compile(r'[A-Z_][A-Z0-9_]{0,63}')
IMAGE = re.compile(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}')
REPOSITORY = re.compile(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}')


class AgentError(Exception):
    def __init__(self, code, message, *, dispatched=False):
        super().__init__(message)
        self.code, self.dispatched = code, dispatched


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def credential(reference, environment=None):
    if reference is None:
        return None
    if not isinstance(reference, str) or not reference.startswith('env:') or not ENV.fullmatch(reference[4:]):
        raise AgentError('AGENT_CREDENTIAL_REFERENCE_INVALID', 'Use an environment variable reference for the agent credential.')
    value = (os.environ if environment is None else environment).get(reference[4:])
    if not isinstance(value, str) or not value or len(value) > 8192 or any(c in value for c in '\r\n\0'):
        raise AgentError('AGENT_CREDENTIAL_UNAVAILABLE', 'The configured agent credential is unavailable on this Gateway.')
    return value


def validate(document):
    if not isinstance(document, dict) or set(document) != {'schema_version', 'agents'} or document['schema_version'] != 1:
        raise AgentError('AGENT_CONFIG_INVALID', 'Agent configuration requires schema version 1 and an agents mapping.')
    agents = document['agents']
    if not isinstance(agents, dict) or set(agents) - CATALOG.keys():
        raise AgentError('AGENT_CONFIG_INVALID', 'Choose a supported agent runtime.')
    for name, row in agents.items():
        fields = {'enabled', 'repositories', 'principals', 'model_id', 'credential_reference', 'image', 'command',
                  'environment_references', 'max_seconds', 'max_runs', 'max_iterations', 'max_budget_usd', 'provider_id'}
        if not isinstance(row, dict) or set(row) - fields or type(row.get('enabled', False)) is not bool:
            raise AgentError('AGENT_CONFIG_INVALID', 'Agent configuration contains unsupported fields.')
        repos, principals = row.get('repositories', []), row.get('principals', [])
        if (not isinstance(repos, list) or not 1 <= len(repos) <= 32
                or any(not isinstance(v, str) or not REPOSITORY.fullmatch(v) or v.split('/')[1] in {'.', '..'} for v in repos)
                or len(set(repos)) != len(repos)):
            raise AgentError('AGENT_CONFIG_INVALID', 'Allow one to 32 exact repositories for each runtime.')
        if (not isinstance(principals, list) or not 1 <= len(principals) <= 32
                or any(not isinstance(v, str) or not 1 <= len(v) <= 128 or any(ord(c) < 32 for c in v) for v in principals)):
            raise AgentError('AGENT_CONFIG_INVALID', 'Allow specific Gateway principals for each runtime.')
        if not isinstance(row.get('model_id'), str) or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,128}', row['model_id']):
            raise AgentError('AGENT_CONFIG_INVALID', 'Select an explicit model for each runtime.')
        reference = row.get('credential_reference')
        if reference is not None and name in {'kiro', 'devin', 'github_copilot'}:
            raise AgentError('AGENT_CREDENTIAL_REFERENCE_UNSUPPORTED',
                             'ACP runtimes use explicit environment references; GitHub Copilot cloud uses its existing host-service account.')
        if reference is not None and (not isinstance(reference, str) or not reference.startswith('env:') or not ENV.fullmatch(reference[4:])):
            raise AgentError('AGENT_CONFIG_INVALID', 'Store credential references, never secret values.')
        if CATALOG[name][1] in {'https', 'copilot'} and set(row) & {'image', 'command', 'environment_references', 'provider_id'}:
            raise AgentError('AGENT_CONFIG_INVALID', 'Cloud agents use their fixed API or existing host service, without local runtime settings.')
        if name != 'cline' and 'provider_id' in row:
            raise AgentError('AGENT_CONFIG_INVALID', 'Only the Cline SDK adapter accepts an explicit provider_id.')
        for field, default, maximum in [('max_seconds', 1800, 7200), ('max_runs', 3, 8), ('max_iterations', 24, 128)]:
            if type(row.get(field, default)) is not int or not 1 <= row.get(field, default) <= maximum:
                raise AgentError('AGENT_CONFIG_INVALID', 'Agent limits must be bounded positive integers.')
        budget = row.get('max_budget_usd', 2.0)
        if type(budget) not in {int, float} or not 0 < budget <= 100:
            raise AgentError('AGENT_CONFIG_INVALID', 'Choose a positive bounded agent budget.')
        if CATALOG[name][1] not in {'https', 'copilot'}:
            if not IMAGE.fullmatch(str(row.get('image', ''))):
                raise AgentError('AGENT_IMAGE_REQUIRED', 'Local agent images must be pinned by SHA-256 digest.')
            command = row.get('command')
            if name == 'copilot_sdk' and command is not None:
                raise AgentError('AGENT_COMMAND_FORBIDDEN', 'Copilot SDK uses its sealed file-only bridge; configure its pinned runtime image instead of a replacement command.')
            if command is not None and (not isinstance(command, list) or not 1 <= len(command) <= 16
                    or any(not isinstance(v, str) or not v or len(v) > 1024 or any(c in v for c in '\0\r\n') for v in command)):
                raise AgentError('AGENT_CONFIG_INVALID', 'Use a bounded executable argument array.')
        references = row.get('environment_references', {})
        if not isinstance(references, dict) or len(references) > 12:
            raise AgentError('AGENT_CONFIG_INVALID', 'Use at most 12 explicit credential environment references.')
        for key, ref in references.items():
            if (not isinstance(key, str) or not ENV.fullmatch(key) or key in {'HOME', 'PATH', 'PYTHONPATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'SSH_AUTH_SOCK'}
                    or key.startswith(('GIT_', 'GH_')) or 'GITHUB' in key):
                raise AgentError('AGENT_CONFIG_INVALID', 'Agent environment cannot override process trust settings.')
            if name == 'copilot_sdk' and key not in {'COPILOT_CLI_PATH', 'COPILOT_CLI_EXTRACT_DIR'}:
                raise AgentError('AGENT_CONFIG_INVALID', 'Copilot SDK environment only selects its installed native runtime; authentication uses its sealed credential reference.')
            if not isinstance(ref, str) or not ref.startswith('env:') or not ENV.fullmatch(ref[4:]):
                raise AgentError('AGENT_CONFIG_INVALID', 'Agent environment accepts references only.')
    return document


def load(path=None):
    selected = path if path is not None else os.getenv('GATEWAY_AGENT_CONFIG', '')
    if not selected:
        document = {'schema_version': 1, 'agents': {}}
    else:
        file = Path(selected)
        if not file.is_file() or file.is_symlink() or file.stat().st_size > 65536:
            raise AgentError('AGENT_CONFIG_UNAVAILABLE', 'The agent configuration is unavailable or oversized.')
        document = validate(json.loads(file.read_text(encoding='utf-8-sig')))
    return document, digest(document)


def save(document, expected_revision, path=None):
    """Local administrator only; callers must enforce management authorization."""
    selected = path if path is not None else os.getenv('GATEWAY_AGENT_CONFIG', '')
    if not selected:
        raise AgentError('AGENT_CONFIG_PATH_REQUIRED', 'Configure the host agent settings file first.')
    document = validate(document)
    file = Path(selected)
    file.parent.mkdir(parents=True, exist_ok=True)
    lock = StateDirectoryLock(file.parent / (file.name + '.lock') / 'lock')
    lock.acquire()
    try:
        current = load(file)[1] if file.exists() else digest({'schema_version': 1, 'agents': {}})
        if expected_revision != current:
            raise AgentError('AGENT_CONFIG_CHANGED', 'Agent settings changed; reload before saving.')
        descriptor, temporary = tempfile.mkstemp(dir=file.parent, prefix=file.name + '.', suffix='.tmp')
        try:
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                stream.write(canonical(document) + '\n')
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, file)
        finally:
            Path(temporary).unlink(missing_ok=True)
    finally:
        lock.release()
    return {'schema_version': 1, 'revision': digest(document), 'agents': document['agents']}


def selected(document, job, backend):
    row = document['agents'].get(backend)
    if not row or not row.get('enabled'):
        raise AgentError('AGENT_DISABLED', 'Enable and configure this agent on the Gateway first.')
    if job['principal'] not in row['principals'] or job['request']['repository'].lower() not in {v.lower() for v in row['repositories']}:
        raise AgentError('AGENT_SCOPE_NOT_ALLOWED', 'The configured runtime does not authorize this account and repository.')
    return row
