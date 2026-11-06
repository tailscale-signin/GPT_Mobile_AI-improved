"""Immutable selected-route authority; boot and lease observations belong to attempts."""
from __future__ import annotations

import copy
import hashlib
import json
import math
import os
import re
import uuid
from urllib.parse import urlsplit, urlunsplit


class ExecutionBindingError(ValueError):
    def __init__(self, code, message, status=409):
        super().__init__(message)
        self.code, self.status, self.retryable = code, status, False


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def endpoint(value):
    """Normalize harmless spelling only. Do not change route, protocol, port or API path."""
    if not isinstance(value, str) or not 1 <= len(value) <= 2048 or any(ord(c) < 33 for c in value.strip()):
        raise ExecutionBindingError('EXECUTION_ENDPOINT_INVALID', 'Select a valid configured inference endpoint.')
    try:
        parsed = urlsplit(value.strip())
        port = parsed.port
    except ValueError as error:
        raise ExecutionBindingError('EXECUTION_ENDPOINT_INVALID', 'Select a valid configured inference endpoint.') from error
    if parsed.scheme.lower() not in {'http', 'https'} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ExecutionBindingError('EXECUTION_ENDPOINT_INVALID', 'Inference endpoints cannot contain credentials, queries or fragments.')
    host = parsed.hostname.lower()
    if ':' in host:
        host = '[' + host + ']'
    # Explicit ports remain explicit; aliases are not rewritten to localhost.
    authority = host + (':' + str(port) if port is not None else '')
    path = parsed.path.rstrip('/')
    if any(part in {'.', '..'} for part in path.split('/')) or '\\' in path:
        raise ExecutionBindingError('EXECUTION_ENDPOINT_INVALID', 'Inference endpoint path is invalid.')
    return urlunsplit((parsed.scheme.lower(), authority, path, '', ''))


def configured_route(runtime, provider_kind):
    target = endpoint(getattr(runtime, 'LLAMA_BASE', ''))
    configured_kind = getattr(runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER')
    if configured_kind and str(configured_kind).upper() != provider_kind:
        raise ExecutionBindingError('EXECUTION_PROVIDER_UNAVAILABLE', 'The selected provider does not match the configured gateway backend.')
    if provider_kind not in {'LLAMA', 'OLLAMA'}:
        raise ExecutionBindingError('SELECTED_ONLY_UNSUPPORTED', 'This gateway can enforce selected-only execution only for its configured local adapter.')
    route = {'provider_kind': provider_kind, 'endpoint': target, 'transport_kind': 'OPENAI_HTTP',
             'backend_connection_id': str(getattr(runtime, 'GATEWAY_INFERENCE_CONNECTION_ID', 'configured-local-model'))}
    controls = {key: os.getenv(key) for key in ('GATEWAY_INFERENCE_CONFIG_REVISION', 'GATEWAY_LLAMA_MODEL_FILE',
                'GATEWAY_LLAMA_QUANTIZATION', 'GATEWAY_LLAMA_ADAPTER', 'GATEWAY_LLAMA_CHAT_TEMPLATE',
                'GATEWAY_LLAMA_ACCELERATOR_POLICY') if os.getenv(key) is not None}
    route['runtime_config_revision'] = fingerprint({'route': route, 'controls': controls})
    route['endpoint_identity'] = fingerprint(target)
    route['completion_url'] = target + ('/chat/completions' if target.endswith('/v1') else '/v1/chat/completions')
    route['metadata_base'] = target[:-3] if target.endswith('/v1') else target
    return route


def selection(value):
    required = {'profile_uid', 'profile_revision', 'provider_connection_id', 'provider_connection_revision',
                'provider_kind', 'configured_endpoint', 'requested_model_id', 'generation_parameters',
                'privacy_scope', 'tool_owner', 'required_capability_policy'}
    optional = {'credential_reference', 'credential_account_identity', 'approved_alias_mapping'}
    if not isinstance(value, dict) or not required <= value.keys() or value.keys() - required - optional:
        raise ExecutionBindingError('EXECUTION_SELECTION_INVALID', 'Supply the complete selected profile snapshot.')
    result = copy.deepcopy(value)
    for key in required - {'generation_parameters', 'required_capability_policy', 'configured_endpoint'}:
        item = result[key]
        maximum = 512 if key == 'privacy_scope' else 256
        if not isinstance(item, str) or not 1 <= len(item) <= maximum or item != item.strip() or any(ord(c) < 32 for c in item):
            raise ExecutionBindingError('EXECUTION_SELECTION_INVALID', 'Selected profile identity fields must be bounded non-empty strings.')
    if result['provider_kind'] not in {'LLAMA', 'OLLAMA'} or result['tool_owner'] != 'GATEWAY':
        raise ExecutionBindingError('SELECTED_ONLY_UNSUPPORTED', 'The selected provider or execution owner is not supported by this adapter.')
    result['configured_endpoint'] = endpoint(result['configured_endpoint'])
    policy = result['required_capability_policy']
    supported = {'SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET', 'JSON_OBJECT', 'LOCAL_ONLY'}
    if not isinstance(policy, list) or any(not isinstance(item, str) for item in policy) or len(policy) != len(set(policy)) or not {'SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET'} <= set(policy) or set(policy) - supported:
        raise ExecutionBindingError('EXECUTION_CAPABILITY_UNSUPPORTED', 'The requested strict execution capabilities are not supported.')
    result['required_capability_policy'] = sorted(policy)
    parameters = result['generation_parameters']
    limits = {'temperature': (0, 2), 'top_p': (0, 1), 'top_k': (0, 1000), 'repeat_penalty': (0, 4),
              'max_tokens': (1, 1048576), 'context_tokens': (256, 1048576), 'seed': (-1, 2**32 - 1)}
    if not isinstance(parameters, dict) or set(parameters) - set(limits) - {'reasoning'}:
        raise ExecutionBindingError('EXECUTION_PARAMETERS_UNSUPPORTED', 'A selected generation parameter is not supported by this adapter.')
    for key, item in parameters.items():
        if key == 'reasoning':
            valid = type(item) is bool
        else:
            low, high = limits[key]
            valid = type(item) in {int, float} and math.isfinite(item) and low <= item <= high
            if key in {'top_k', 'max_tokens', 'context_tokens', 'seed'}:
                valid = valid and type(item) is int
        if not valid:
            raise ExecutionBindingError('EXECUTION_PARAMETERS_INVALID', 'A selected generation parameter is outside its supported range.')
    for key in ('credential_reference', 'credential_account_identity'):
        item = result.get(key)
        if item is not None and (not isinstance(item, str) or not 1 <= len(item) <= 256 or any(ord(c) < 32 for c in item)):
            raise ExecutionBindingError('EXECUTION_SELECTION_INVALID', 'Credential identity must be a reference, not a credential value.')
        result[key] = item
    aliases = result.get('approved_alias_mapping') or {}
    if not isinstance(aliases, dict) or len(aliases) > 1 or any(key != result['requested_model_id'] or not isinstance(item, str) or not 1 <= len(item) <= 256 for key, item in aliases.items()):
        raise ExecutionBindingError('EXECUTION_MODEL_ALIAS_INVALID', 'Only an explicitly selected, advertised alias mapping is permitted.')
    result['approved_alias_mapping'] = aliases
    return result


class ExecutionBindings:
    def __init__(self, store, runtime, capabilities):
        self.store, self.runtime, self.capabilities = store, runtime, capabilities
        self.boot_id = str(getattr(runtime, 'gateway_instance_id', None) or uuid.uuid4().hex)

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_identity (name TEXT PRIMARY KEY, value TEXT NOT NULL)')
        db.execute('INSERT OR IGNORE INTO engineering_identity VALUES(?,?)', ('installation', 'gw_' + uuid.uuid4().hex))
        db.execute('CREATE TABLE IF NOT EXISTS engineering_bindings ('
                   'id TEXT PRIMARY KEY, principal TEXT NOT NULL, digest TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, '
                   'created_at REAL NOT NULL, payload TEXT NOT NULL)')

    def installation_id(self):
        def read(db):
            self.schema(db)
            return db.execute('SELECT value FROM engineering_identity WHERE name=?', ('installation',)).fetchone()[0]
        return self.store.tx(read)

    def prepare(self, principal, value, gateway_endpoint=None):
        selected = selection(value)
        route = configured_route(self.runtime, selected['provider_kind'])
        allowed = {route['endpoint']}
        if gateway_endpoint is not None:
            public = endpoint(gateway_endpoint)
            allowed.update({public, public + '/v1'} if not public.endswith('/v1') else {public, public[:-3]})
        if selected['configured_endpoint'] not in allowed:
            raise ExecutionBindingError('EXECUTION_ENDPOINT_UNAVAILABLE', 'The selected address is not an authorized route to this configured backend.')
        observation = self.capabilities.inspect(route, selected['requested_model_id'], force=True)
        self.capabilities.require(observation, selected)
        self.capabilities.require_local(route, selected)
        binding = {key: selected[key] for key in ('profile_uid', 'profile_revision', 'provider_connection_id',
                   'provider_connection_revision', 'provider_kind', 'requested_model_id', 'approved_alias_mapping',
                   'credential_reference', 'credential_account_identity', 'privacy_scope', 'tool_owner', 'required_capability_policy')}
        binding.update(schema_version=1, routing_policy='SELECTED_ONLY', transport_kind=route['transport_kind'],
                       configured_endpoint_identity=fingerprint(selected['configured_endpoint']),
                       backend_endpoint_identity=route['endpoint_identity'], backend_connection_id=route['backend_connection_id'],
                       generation_parameters_digest=fingerprint(selected['generation_parameters']),
                       generation_parameters=selected['generation_parameters'], runtime_config_revision=route['runtime_config_revision'],
                       gateway_installation_id=self.installation_id(),
                       admitted_model_revision=observation.get('model_revision'),
                       admitted_template_digest=observation.get('template_digest'))
        binding['binding_id'] = 'bind_' + fingerprint([principal, binding])[:32]
        raw = canonical(binding)
        def save(db):
            self.schema(db)
            old = db.execute('SELECT principal,digest,revoked FROM engineering_bindings WHERE id=?', (binding['binding_id'],)).fetchone()
            if old and (old[0] != principal or old[1] != fingerprint(binding) or old[2]):
                raise ExecutionBindingError('EXECUTION_BINDING_REVOKED', 'This selected profile binding is no longer authorized.')
            if not old and db.execute('SELECT COUNT(*) FROM engineering_bindings').fetchone()[0] >= 8192:
                raise ExecutionBindingError('EXECUTION_BINDING_CAPACITY', 'Archive inactive execution bindings before creating more work.', 507)
            db.execute('INSERT OR IGNORE INTO engineering_bindings(id,principal,digest,created_at,payload) VALUES(?,?,?,?,?)',
                       (binding['binding_id'], principal, fingerprint(binding), self.store.now(), raw))
        self.store.tx(save)
        return {'binding': copy.deepcopy(binding), 'capabilities': observation}

    def read(self, principal, binding_id):
        if not isinstance(binding_id, str) or not re.fullmatch(r'bind_[a-f0-9]{32}', binding_id):
            raise ExecutionBindingError('EXECUTION_BINDING_REQUIRED', 'Select and verify an execution profile before starting or resuming inference.')
        def read(db):
            self.schema(db)
            row = db.execute('SELECT digest,revoked,payload FROM engineering_bindings WHERE id=? AND principal=?', (binding_id, principal)).fetchone()
            if row is None or row[1]:
                raise ExecutionBindingError('EXECUTION_BINDING_UNAVAILABLE', 'The saved execution binding is unavailable for this account.')
            binding = json.loads(row[2])
            if fingerprint(binding) != row[0] or binding.get('binding_id') != binding_id:
                raise ExecutionBindingError('EXECUTION_BINDING_CORRUPT', 'The saved execution binding failed its integrity check.')
            return binding
        return self.store.tx(read)

    def validate(self, principal, binding_id, *, force=False):
        binding = self.read(principal, binding_id)
        route = configured_route(self.runtime, binding['provider_kind'])
        if binding['gateway_installation_id'] != self.installation_id() or any(binding[field] != route[key] for field, key in
                (('runtime_config_revision', 'runtime_config_revision'), ('backend_endpoint_identity', 'endpoint_identity'), ('backend_connection_id', 'backend_connection_id'))):
            raise ExecutionBindingError('EXECUTION_CONFIGURATION_CHANGED', 'The original configured backend is unavailable. Work is retained; select a new binding explicitly to change it.')
        observation = self.capabilities.inspect(route, binding['requested_model_id'], force=force)
        self.capabilities.require(observation, binding)
        self.capabilities.require_local(route, binding)
        for field, observed in (('admitted_model_revision', 'model_revision'), ('admitted_template_digest', 'template_digest')):
            if binding.get(field) is not None and observation.get(observed) != binding[field]:
                raise ExecutionBindingError('EXECUTION_MODEL_REVISION_CHANGED', 'The selected model weights or prompt template changed after admission. A new binding requires explicit selection.')
        return binding, route, observation

    def revoke(self, principal, binding_id):
        self.read(principal, binding_id)
        self.store.tx(lambda db: db.execute('UPDATE engineering_bindings SET revoked=1 WHERE id=? AND principal=?', (binding_id, principal)))
