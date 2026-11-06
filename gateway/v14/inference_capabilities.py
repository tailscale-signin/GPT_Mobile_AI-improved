"""Bounded runtime observations for the configured backend, never a model selector."""
from __future__ import annotations

import copy
import ipaddress
import json
import threading
import time
from collections import OrderedDict
from urllib.parse import urlsplit

import requests

from v14.execution_binding import ExecutionBindingError, canonical, fingerprint


def positive(value, maximum=2097152):
    return value if type(value) is int and 0 < value <= maximum else None


class InferenceCapabilities:
    def __init__(self, runtime, clock=time.monotonic):
        self.runtime, self.clock = runtime, clock
        self.lock = threading.RLock()
        self.cache = OrderedDict()

    def _json(self, method, url, *, body=None, params=None, maximum=1024 * 1024):
        get = getattr(self.runtime, 'http_get', requests.get)
        session = getattr(self.runtime, 'get_http_session', None)
        call = get if method == 'GET' else (session().post if callable(session) else requests.post)
        kwargs = {'timeout': (2, 5), 'allow_redirects': False, 'stream': True}
        if body is not None:
            kwargs['json'] = body
        if params is not None:
            kwargs['params'] = params
        response = call(url, **kwargs)
        try:
            if response.status_code in {401, 403}:
                raise ExecutionBindingError('EXECUTION_METADATA_DENIED', 'The configured backend did not authorize required model metadata.', response.status_code)
            response.raise_for_status()
            raw = bytearray()
            for chunk in response.iter_content(8192):
                if len(raw) + len(chunk) > maximum:
                    raise ExecutionBindingError('EXECUTION_METADATA_OVERSIZE', 'The configured backend metadata exceeded its bound.')
                raw.extend(chunk)
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError('Expected an object')
            return value
        finally:
            response.close()

    def inspect(self, route, model, *, force=False):
        key = (route['runtime_config_revision'], model)
        # One bounded metadata probe per backend identity at a time. The cache is
        # observation-only; a fresh queue/physical boundary can require revalidation.
        with self.lock:
            cached = self.cache.get(key)
            if not force and cached and self.clock() - cached[0] < 3:
                return copy.deepcopy(cached[1])
            try:
                result = self._probe(route, model)
            except ExecutionBindingError:
                raise
            except requests.HTTPError as error:
                status = getattr(error.response, 'status_code', 0)
                code = 'EXECUTION_CAPABILITY_UNSUPPORTED' if status in {404, 405, 501} else 'EXECUTION_BACKEND_UNAVAILABLE'
                raise ExecutionBindingError(code, 'Required model identity or context metadata is unavailable on the configured backend.', 503) from error
            except (requests.RequestException, TimeoutError) as error:
                raise ExecutionBindingError('EXECUTION_BACKEND_UNAVAILABLE', 'The selected backend is unreachable; no other route was tried.', 503) from error
            except (ValueError, KeyError, TypeError) as error:
                raise ExecutionBindingError('EXECUTION_CAPABILITY_INVALID', 'The configured backend returned invalid model metadata.') from error
            self.cache[key] = self.clock(), result
            self.cache.move_to_end(key)
            while len(self.cache) > 64:
                self.cache.popitem(last=False)
            return copy.deepcopy(result)

    def _probe(self, route, model):
        root = route['metadata_base']
        catalog = self._json('GET', root + '/v1/models', params={'autoload': 'false'})
        rows = catalog.get('data')
        if not isinstance(rows, list) or len(rows) > 1024 or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in rows):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'The configured backend model catalog is invalid.')
        selected = next((row for row in rows if row['id'] == model), None)
        if selected is None:
            raise ExecutionBindingError('EXECUTION_MODEL_UNAVAILABLE', 'The exact selected model is not advertised by the configured backend. Work is retained.')
        if route['provider_kind'] == 'OLLAMA':
            props = self._json('POST', root + '/api/show', body={'model': model, 'verbose': False})
            running = self._json('GET', root + '/api/ps').get('models', [])
            active = next((row for row in running if isinstance(row, dict) and row.get('model', row.get('name')) == model), None)
            if active is None:
                raise ExecutionBindingError('EXECUTION_MODEL_NOT_LOADED', 'Load the exact selected Ollama model before admitting this strict job. No alternative model was loaded.')
            # /api/show describes training capacity; /api/ps observes the actual
            # loaded context allocation. They are not interchangeable.
            context = positive(active.get('context_length'))
            revision = active.get('digest')
            loaded_model = active.get('model', active.get('name'))
            props = dict(props, model_file_digest=revision, memory={'resident_bytes': active.get('size'), 'vram_bytes': active.get('size_vram')})
            tokenizer = None
            template = props.get('template')
            build = props.get('version')
        else:
            props = self._json('GET', root + '/props', params={'model': model, 'autoload': 'false'})
            settings = props.get('default_generation_settings') or {}
            if not isinstance(settings, dict):
                raise ExecutionBindingError('EXECUTION_CAPABILITY_INVALID', 'The active generation settings are malformed.')
            context = positive(settings.get('n_ctx')) or positive(props.get('n_ctx')) or positive(props.get('context_length'))
            # Training context and model filename do not prove the active runtime
            # allocation or an alias's weight revision.
            loaded_model = props.get('model_alias') or props.get('model_id') or selected['id']
            revision = props.get('model_sha256') or selected.get('digest')
            tokenizer = root + '/v1/chat/completions/input_tokens'
            template = props.get('chat_template')
            build = props.get('build_info') or props.get('build')
        if context is None:
            raise ExecutionBindingError('EXECUTION_CONTEXT_UNAVAILABLE', 'The selected model context capacity is unknown. Configure or expose its actual allocation before inference.')
        output = positive(props.get('max_output_tokens')) or positive(selected.get('max_output_tokens'))
        observed = {'schema_version': 1, 'provider_kind': route['provider_kind'], 'backend_endpoint_identity': route['endpoint_identity'],
                    'requested_model_id': model, 'reported_model_id': loaded_model, 'model_revision': revision,
                    'context_tokens': context, 'max_output_tokens': min(output or context, context),
                    'output_limit_source': 'provider_reported' if output else 'context_capacity',
                    'tokenizer_endpoint': tokenizer, 'template_digest': fingerprint(template) if template else None,
                    'backend_build': build if isinstance(build, (str, int, dict)) and len(canonical(build).encode()) <= 4096 else None,
                    'supported': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET', 'JSON_OBJECT'],
                    'model_aliases': selected.get('aliases', []), 'observed_at_ms': int(time.time() * 1000),
                    'evidence_origin': 'GATEWAY_OBSERVED_PROVIDER_METADATA', 'hardware': {},
                    'limits_verified': True}
        for name in ('model_file_digest', 'offloaded_layers', 'accelerator', 'memory'):
            value = props.get(name)
            if value is not None and len(canonical(value).encode()) <= 4096:
                observed['hardware'][name] = {'value': value, 'origin': 'PROVIDER_REPORTED'}
        observed['capability_revision'] = fingerprint({key: value for key, value in observed.items() if key not in {'observed_at_ms', 'capability_revision', 'hardware'}})
        return observed

    @staticmethod
    def require(observation, selected):
        requested = selected['requested_model_id']
        mapping = selected.get('approved_alias_mapping') or {}
        observed = observation.get('reported_model_id')
        if observed != requested:
            declared = observation.get('model_aliases') or []
            if mapping.get(requested) != observed or requested not in declared:
                raise ExecutionBindingError('EXECUTION_MODEL_MISMATCH', 'The loaded model does not match the exact selected model or its explicitly approved advertised alias.')
        if observation.get('limits_verified') is not True:
            raise ExecutionBindingError('EXECUTION_CONTEXT_UNAVAILABLE', 'Selected model limits have not been verified.')
        if set(selected['required_capability_policy']) - set(observation['supported']) - {'LOCAL_ONLY'}:
            raise ExecutionBindingError('EXECUTION_CAPABILITY_UNSUPPORTED', 'The selected backend does not support a mandatory execution capability.')

    def count(self, payload, observation):
        location = observation.get('tokenizer_endpoint')
        if location:
            try:
                data = self._json('POST', location, body={key: value for key, value in payload.items() if key in {'model', 'messages', 'tools', 'tool_choice', 'response_format', 'chat_template_kwargs'}}, maximum=65536)
                measured = positive(data.get('input_tokens')) or positive(data.get('prompt_tokens'))
                if measured is not None:
                    return measured, 'SELECTED_TOKENIZER'
            except (requests.RequestException, ExecutionBindingError, ValueError, TypeError):
                pass
        # UTF-8 bytes plus framing is deliberately conservative for local tokenizers;
        # it is not relabelled as an observed/native token count.
        encoded = canonical({key: value for key, value in payload.items() if key in {'model', 'messages', 'tools', 'tool_choice', 'response_format', 'chat_template_kwargs'}}).encode()
        return len(encoded) + 64 * (len(payload.get('messages', [])) + len(payload.get('tools', [])) + 1), 'CONSERVATIVE_UTF8_BOUND'

    @staticmethod
    def require_local(route, binding):
        if 'LOCAL_ONLY' not in binding['required_capability_policy']:
            return
        host = urlsplit(route['endpoint']).hostname
        try:
            local = host == 'localhost' or ipaddress.ip_address(host or '').is_loopback
        except ValueError:
            local = False
        if not local:
            raise ExecutionBindingError('EXECUTION_PRIVACY_BLOCKED', 'Local-only evidence cannot leave this gateway host through the selected route.')
