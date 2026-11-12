"""Static diagnostic vocabulary. No response text, URLs or user-supplied paths are exported."""
import hashlib
import json
import re

from v14.backend_observation import (DEPENDENCIES, FAILURES, MODEL_STATES, UPSTREAM_BODY_STATES,
                                     UPSTREAM_ERROR_CODES, UPSTREAM_ERROR_TYPES, UPSTREAM_MESSAGES)
from v14.diagnostic_schema import ENUM_ATTRIBUTES

ROUTES = {'chat', 'models', 'readiness', 'liveness', 'capabilities', 'dashboard', 'job', 'engineering',
          'management_dashboard', 'maintenance', 'identity', 'job_catalog', 'conversations', 'messages',
          'commands', 'backend_health', 'backend_properties', 'tools', 'connections', 'session',
          'debug', 'doctor', 'browser', 'other'}
ADMISSION_CODES = {'remote_surface_not_admitted', 'scope_not_granted', 'engineering_scope_required',
                   'tool_scope_required_for_runtime', 'local_admin_required', 'transport_identity_required',
                   'invalid_host', 'invalid_origin', 'duplicate_admission_header', 'browser_csrf_required', 'unknown_job'}
FAILURE_CODES = ENUM_ATTRIBUTES['failure_code'] | {value[0] for value in FAILURES.values()} | ADMISSION_CODES


def choice(value, choices):
    return isinstance(value, str) and value in choices


def backend_build_provenance(value):
    """A build label cannot export hostnames, paths, compiler flags or arbitrary prose."""
    result = {'backendBuildStatus': 'not_observed'}
    if type(value) not in {str, int, dict} or value in ('', None):
        return result
    try:
        raw = json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    except (ValueError, TypeError, RecursionError):
        return result
    if len(raw) > 4096:
        return result
    result.update(backendBuildStatus='digest_only', backendBuildDigest=hashlib.sha256(raw).hexdigest())
    fields = value if isinstance(value, dict) else {'version': value}
    for key in ('version', 'build_number', 'build'):
        candidate = fields.get(key)
        if type(candidate) is int and 0 <= candidate <= 2 ** 53:
            candidate = str(candidate)
        if isinstance(candidate, str) and len(candidate) <= 64 and re.fullmatch(r'b?[0-9]+(?:\.[0-9]+){0,3}(?:-g[a-f0-9]{7,40})?', candidate):
            result.update(backendBuildVersion=candidate, backendBuildStatus='observed')
            break
    for key in ('commit', 'revision', 'source_commit'):
        candidate = fields.get(key)
        if isinstance(candidate, str) and re.fullmatch(r'[a-f0-9]{7,40}', candidate):
            result.update(backendBuildRevision=candidate, backendBuildStatus='observed')
            break
    return result


def route_kind(path):
    path = str(path).removeprefix('/v1')
    exact = {'/chat/completions': 'chat', '/models': 'models', '/health': 'backend_health', '/props': 'backend_properties',
             '/gateway/models/prepare': 'models',
             '/gateway/ready': 'readiness', '/gateway/live': 'liveness', '/gateway/capabilities': 'capabilities',
             '/gateway/v13': 'capabilities', '/gateway/v14': 'capabilities', '/gateway/v15': 'capabilities', '/gateway/v16': 'capabilities',
             '/gateway/dashboard': 'dashboard', '/gateway/admin/dashboard': 'management_dashboard',
             '/gateway/admin/status': 'maintenance', '/gateway/admin/maintenance': 'maintenance',
             '/gateway/identity': 'identity', '/gateway/job-catalog': 'job_catalog',
             '/gateway/connections': 'connections', '/gateway/session': 'session', '/gateway/admin/doctor': 'doctor'}
    if path in exact:
        return exact[path]
    for prefix, kind in (('/gateway/jobs', 'job'), ('/gateway/engineering', 'engineering'),
                         ('/gateway/commands', 'commands'), ('/gateway/tools', 'tools'), ('/gateway/mcp', 'tools'),
                         ('/gateway/debug', 'debug'), ('/gateway/admin/debug', 'debug')):
        if path == prefix or path.startswith(prefix + '/'):
            return kind
    if path == '/gateway/conversations' or path.startswith('/gateway/conversations/'):
        return 'messages' if path.endswith('/messages') else 'conversations'
    return 'browser' if path in {'/', '/index.html', '/sw.js', '/favicon.ico'} else 'other'


def provenance(value):
    if not isinstance(value, dict):
        return {}
    result = {}
    patterns = {'sourceCommit': r'[a-f0-9]{40}', 'gatewayVersion': r'[0-9]+(?:\.[0-9]+){0,3}',
                'instanceId': r'[a-f0-9]{32}', 'bootId': r'[a-f0-9]{32}',
                'configurationFingerprint': r'[a-f0-9]{64}', 'backendIdentity': r'[a-f0-9]{64}',
                'manifestDigest': r'[a-f0-9]{64}', 'backendBuildDigest': r'[a-f0-9]{64}',
                'backendBuildRevision': r'[a-f0-9]{7,40}',
                'backendBuildVersion': r'b?[0-9]+(?:\.[0-9]+){0,3}(?:-g[a-f0-9]{7,40})?'}
    for key, pattern in patterns.items():
        item = value.get(key)
        if isinstance(item, str) and len(item) <= 64 and re.fullmatch(pattern, item):
            result[key] = item
    for key in ('processId', 'diagnosticProtocolVersion', 'backendBuildObservedAtMs'):
        item = value.get(key)
        if type(item) is int and 0 <= item <= 2 ** 53:
            result[key] = item
    if choice(value.get('backendType'), {'LLAMA', 'OLLAMA'}):
        result['backendType'] = value['backendType']
    if choice(value.get('buildStatus'), {'development', 'packaged'}):
        result['buildStatus'] = value['buildStatus']
    if choice(value.get('startupReason'), {'process_start', 'supervisor_start', 'crash_recovery', 'requested_restart', 'update', 'unknown'}):
        result['startupReason'] = value['startupReason']
    if choice(value.get('backendBuildStatus'), {'observed', 'digest_only', 'not_observed'}):
        result['backendBuildStatus'] = value['backendBuildStatus']
    return result


def failure_fields(value, *, conditions=True):
    if not isinstance(value, dict):
        return {}
    result = {}
    for field in ('failureCode', 'reasonCode', 'code'):
        candidate = value.get(field)
        if isinstance(candidate, str) and candidate in FAILURE_CODES:
            result['failureCode' if field == 'code' else field] = candidate
    if choice(value.get('dependency'), DEPENDENCIES | ENUM_ATTRIBUTES['dependency']):
        result['dependency'] = value['dependency']
    if choice(value.get('failureCategory'), set(FAILURES) | {'storage', 'draining'}):
        result['failureCategory'] = value['failureCategory']
    for field in ('retryable', 'ready', 'networkReachable', 'modelAvailable', 'generationReady',
                  'limitsVerified', 'inferenceTested', 'automaticSubstitution', 'draining',
                  'toolsReady', 'schemaReady', 'executionVerified', 'canQueue', 'canAccept', 'modelLoading', 'inferenceVerified',
                  'modelAdvertised', 'modelLoaded', 'inference_attempted'):
        if type(value.get(field)) is bool:
            result[field] = value[field]
        elif field in value and value[field] is None and field in {'ready', 'networkReachable', 'modelAvailable', 'generationReady',
                                                                  'toolsReady', 'schemaReady', 'executionVerified', 'inferenceVerified', 'modelAdvertised', 'modelLoaded'}:
            result[field] = None
    for field in ('upstreamStatus', 'healthHttpStatus', 'modelsHttpStatus', 'observedAtMillis', 'observedAtMs', 'observationAgeMs',
                  'toolCount', 'toolConnectionCount'):
        candidate = value.get(field)
        if type(candidate) is int and 0 <= candidate <= 2 ** 53:
            if 'Status' not in field or 100 <= candidate <= 599:
                result[field] = candidate
    for field, choices in {'failed_stage': ENUM_ATTRIBUTES['failed_stage'],
                           'dispatch_state': ENUM_ATTRIBUTES['execution_dispatch_state'],
                           'admission_state': ENUM_ATTRIBUTES['admission_state'],
                           'recovery_action': ENUM_ATTRIBUTES['recovery_action'],
                           'recovery_condition': ENUM_ATTRIBUTES['recovery_condition'],
                           'metadataAccess': {'unknown', 'available', 'restricted'},
                           'backend': {'unreachable', 'restricted', 'ready', 'loading', 'unavailable'},
                           'reasoningControlState': {'VERIFIED', 'UNVERIFIED', 'UNSUPPORTED'},
                           'modelState': MODEL_STATES,
                           'upstreamErrorType': UPSTREAM_ERROR_TYPES,
                           'upstreamErrorBodyState': UPSTREAM_BODY_STATES,
                           'upstreamMessageCode': set(UPSTREAM_MESSAGES),
                           'toolsState': {'NOT_CONFIGURED', 'AUTH_REQUIRED', 'UNAVAILABLE', 'NOT_DISCOVERED', 'STALE', 'READY', 'NOT_MEASURED'},
                           'journal': {'ready', 'disabled', 'unavailable', 'write_failed'}}.items():
        if choice(value.get(field), choices):
            result[field] = value[field]
    code = value.get('upstreamCode')
    if (type(code) is int and 100 <= code <= 599) or choice(code, UPSTREAM_ERROR_CODES):
        result['upstreamCode'] = code
    # Derive the public summary from its code, never copy an upstream body field.
    if result.get('upstreamMessageCode') in UPSTREAM_MESSAGES:
        result['upstreamMessage'] = UPSTREAM_MESSAGES[result['upstreamMessageCode']]
    result.update({key: item for key, item in provenance(value).items() if key.startswith('backendBuild')})
    nested = value.get('conditions')
    if conditions and isinstance(nested, dict):
        projected = {key: item for key in ('backend', 'health', 'models', 'selectedModel', 'storage', 'process')
                     if (item := failure_fields(nested.get(key), conditions=False))}
        if projected:
            result['conditions'] = projected
    return result


def response_summary(body, admission=None):
    result = {}
    if isinstance(body, dict):
        result = failure_fields(body, conditions=False)
        # Select one primary error envelope. Separate readiness conditions must
        # never lend their dependency/category to a different top-level blocker.
        for key in ('error', 'detail', 'selectedModel'):
            candidate = failure_fields(body.get(key), conditions=False)
            if candidate and not any(field in result for field in ('reasonCode', 'failureCode')):
                result = dict(candidate, **result)
                break
        backend = body.get('capabilities')
        checks = body.get('checks')
        service = body.get('service')
        if isinstance(backend, dict) and isinstance(checks, dict):
            states = {'backend': failure_fields(backend, conditions=False),
                      'health': failure_fields(backend.get('healthFailure'), conditions=False),
                      'models': failure_fields(backend.get('modelsFailure'), conditions=False),
                      'storage': failure_fields({'journal': checks.get('journal')}, conditions=False)}
            reason = result.get('reasonCode')
            if reason in {'DURABLE_STORAGE_UNAVAILABLE', 'DURABLE_STORAGE_WRITE_FAILED'}:
                result.update(dependency='durable_storage', failureCategory='storage')
            elif reason == 'GATEWAY_DRAINING':
                result.update(dependency='process', failureCategory='draining')
            elif reason and reason == backend.get('reasonCode'):
                result.update({key: value for key, value in failure_fields(backend, conditions=False).items()
                               if key in {'dependency', 'failureCategory', 'upstreamStatus'}})
            if isinstance(service, dict):
                states['process'] = failure_fields({'draining': service.get('draining')}, conditions=False)
            result['conditions'] = {key: value for key, value in states.items() if value}
    if choice(admission, ADMISSION_CODES):
        result.update(failureCode=admission, dependency='process', retryable=False)
    return result
