"""Host-owned, rollback-compatible Full Integration Batch admission settings.

Legacy Gateway settings.json deliberately remains schema-1 unchanged. A missing
execution-policy.json means old behavior. Invalid settings fail closed rather than
granting Full Mode or changing a saved job's accepted execution binding.
"""
import json
import os
import tempfile
from pathlib import Path

SCHEMA_VERSION = 1
MODES = ('standard', 'smart', 'full')
MAX_LOCAL_VALIDATION_WORKERS = 4
DEFAULTS = {
    'schema_version': SCHEMA_VERSION, 'default_mode': 'standard',
    'full_enabled': False, 'github_api_max_inflight': 1,
    'native_provider_batch': False, 'local_validation_workers': 1,
}


def validate_policy(value):
    if not isinstance(value, dict) or set(value) - set(DEFAULTS):
        raise ValueError('Unsupported Gateway execution policy fields')
    policy = dict(DEFAULTS, **value)
    if type(policy['schema_version']) is not int or policy['schema_version'] != SCHEMA_VERSION:
        raise ValueError('Unsupported Gateway execution policy version')
    if policy['default_mode'] not in MODES or type(policy['full_enabled']) is not bool:
        raise ValueError('Choose an explicit supported execution mode')
    if policy['default_mode'] == 'full' and not policy['full_enabled']:
        raise ValueError('Full Mode must be explicitly enabled on this host')
    if type(policy['github_api_max_inflight']) is not int or policy['github_api_max_inflight'] != 1:
        raise ValueError('GitHub API admission remains serialized until qualified')
    if policy['native_provider_batch'] is not False:
        raise ValueError('Native provider batching requires separate consent and an implemented host adapter')
    if (type(policy['local_validation_workers']) is not int or
            not 1 <= policy['local_validation_workers'] <= MAX_LOCAL_VALIDATION_WORKERS):
        raise ValueError('Local validation workers must be between 1 and 4')
    return policy


def policy_path(path=None):
    return Path(path) if path is not None else Path(os.environ['GATEWAY_EXECUTION_POLICY_PATH']) if os.environ.get('GATEWAY_EXECUTION_POLICY_PATH') else None


def load_policy(path=None):
    target = policy_path(path)
    if target is None or not target.exists():
        return dict(DEFAULTS)
    if not target.is_file() or target.stat().st_size > 8192:
        raise ValueError('Gateway execution policy file exceeds its safe bounds')
    return validate_policy(json.loads(target.read_text(encoding='utf-8')))


def save_policy(path, value):
    policy = validate_policy(value)
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', encoding='utf-8', dir=target.parent, prefix='.execution-policy-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(policy, stream, sort_keys=True, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    return policy


def capability(policy=None, durable=True):
    policy = validate_policy(policy or load_policy())
    full = bool(durable and policy['full_enabled'])
    return {'protocol_version': SCHEMA_VERSION, 'modes': ['standard', 'smart'] + (['full'] if full else []),
            'default_mode': policy['default_mode'], 'full_enabled': full,
            'github_api_max_inflight': policy['github_api_max_inflight'],
            'local_validation_workers': policy['local_validation_workers'],
            'native_provider_batch': False,
            'execution_owner': 'GATEWAY', 'policy_source': 'host'}


def authorize_request(request, *, durable=True, policy=None):
    mode = request.get('execution_mode', 'standard')
    if mode not in MODES:
        raise ValueError('Unsupported execution mode')
    if mode == 'full':
        policy = validate_policy(policy if policy is not None else load_policy())
        if not durable or not policy['full_enabled']:
            raise ValueError('Full Integration Batch is disabled on the selected Gateway host')
        if request.get('batch_policy_version') != SCHEMA_VERSION:
            raise ValueError('Full Integration Batch requires policy protocol 1')
        preferences = request.get('batch_preferences', {})
        if not isinstance(preferences, dict) or set(preferences) - {
            'enable_provider_native_batch', 'max_git_api_inflight',
            'allow_parallel_local_tests', 'resume_after_transient_limits'}:
            raise ValueError('Unknown Full Integration Batch preferences')
        if preferences.get('enable_provider_native_batch', False) is not False:
            raise ValueError('Native provider batching is not enabled on this Gateway')
        if preferences.get('max_git_api_inflight', 1) != 1 or type(preferences.get('max_git_api_inflight', 1)) is not int:
            raise ValueError('GitHub API concurrency may not exceed the host policy')
        for name in ('allow_parallel_local_tests', 'resume_after_transient_limits'):
            if name in preferences and type(preferences[name]) is not bool:
                raise ValueError('Batch preferences require boolean values')
    return mode
