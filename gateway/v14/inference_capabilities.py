"""Bounded runtime observations for the configured backend, never a model selector."""
from __future__ import annotations

import copy
import ipaddress
import json
import os
import re
import threading
import time
from collections import OrderedDict
from urllib.parse import urlsplit

import requests

from v14.execution_binding import ExecutionBindingError, canonical, configured_route, fingerprint


def positive(value, maximum=2097152):
    return value if type(value) is int and 0 < value <= maximum else None


class InferenceCapabilities:
    def __init__(self, runtime, clock=time.monotonic):
        self.runtime, self.clock = runtime, clock
        self.lock = threading.RLock()
        self.cache = OrderedDict()
        self.reasoning_cache = OrderedDict()

    def _json(self, method, url, *, body=None, params=None, maximum=1024 * 1024, deadline_at=None):
        get = getattr(self.runtime, 'http_get', requests.get)
        session = getattr(self.runtime, 'get_http_session', None)
        call = get if method == 'GET' else (session().post if callable(session) else requests.post)
        kwargs = {'timeout': (2, 5), 'allow_redirects': False, 'stream': True}
        if deadline_at is not None:
            remaining = deadline_at - time.monotonic()
            if remaining <= 0:
                raise ExecutionBindingError('EXECUTION_METADATA_DEADLINE', 'The optional metadata probe reached its deadline.', 503)
            connect = min(0.5, remaining / 2)
            kwargs['timeout'] = (connect, min(1, remaining - connect))
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
            deadline = min(time.monotonic() + 5, deadline_at) if deadline_at is not None else time.monotonic() + 5
            for chunk in response.iter_content(8192):
                if len(raw) + len(chunk) > maximum or time.monotonic() > deadline:
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

    def selected_model(self, model):
        """Read-only public handshake. Never expose backend addresses, templates or another model."""
        if not isinstance(model, str) or not 1 <= len(model) <= 512 or model != model.strip() or any(ord(c) < 32 for c in model):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'Select a bounded model identifier.', 400)
        result = {'schema': 'gptmobile.gateway.selected-model.v1', 'schemaVersion': 1,
                  'requestedModel': model, 'limitsVerified': False}
        try:
            kind = str(getattr(self.runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
            route = configured_route(self.runtime, kind)
            observed = self.inspect(route, model)
            self.require(observed, {'requested_model_id': model,
                                   'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
            # A context-capacity bound is useful for admission but is not a
            # separately measured provider output-token limit.
            output = observed['max_output_tokens'] if observed.get('output_limit_source') == 'provider_reported' else None
            result.update(reportedModel=observed['reported_model_id'], contextTokens=observed['context_tokens'],
                          maxOutputTokens=output, outputLimitSource=observed['output_limit_source'],
                          limitsVerified=True, capabilityRevision=observed['capability_revision'],
                          observedAtMillis=observed['observed_at_ms'],
                          evidenceOrigin='GATEWAY_OBSERVED_PROVIDER_METADATA')
            control = self.reasoning_control(route, model, observed)
            if control is not None:
                result['reasoningControl'] = control
        except ExecutionBindingError as error:
            result['failureCode'] = error.code
        return result

    @staticmethod
    def _verifies_thinking_toggle(enabled, disabled):
        if not isinstance(enabled, str) or not isinstance(disabled, str):
            return False
        on, off = enabled.rfind('<think>'), disabled.rfind('<think>')
        return (on >= 0 and on == off and enabled[:on] == disabled[:off]
                and not enabled[on + 7:].strip() and disabled[off + 7:].strip() == '</think>')

    def reasoning_control(self, route, model, observed):
        """Verify only synthetic template rendering, never inference or hardware use.

        The proof is tied to the exact model/template/runtime metadata revision.
        An unsupported or slow optional probe cannot invalidate verified limits.
        """
        candidates = observed.get('reasoning_parameters') or []
        if route['provider_kind'] != 'LLAMA' or not candidates or not observed.get('template_digest'):
            return None
        key = (route['runtime_config_revision'], model, observed['capability_revision'])
        with self.lock:
            cached = self.reasoning_cache.get(key)
            if cached and self.clock() - cached[0] < (60 if cached[1] is not None else 30):
                return copy.deepcopy(cached[1])
            result = None
            deadline = time.monotonic() + 2
            try:
                for parameter in candidates:
                    prompts = []
                    for enabled in (True, False):
                        response = self._json('POST', route['metadata_base'] + '/apply-template',
                            body={'model': model, 'messages': [{'role': 'user', 'content': 'Template capability check.'}],
                                  'chat_template_kwargs': {parameter: enabled}, 'add_generation_prompt': True},
                            params={'model': model, 'autoload': 'false'}, maximum=65536, deadline_at=deadline)
                        prompts.append(response.get('prompt'))
                    if not self._verifies_thinking_toggle(*prompts):
                        continue
                    # Reject a proof crossing a model reload or template change.
                    current = self._probe(route, model, deadline_at=deadline)
                    self.require(current, {'requested_model_id': model,
                                           'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
                    if current['capability_revision'] != observed['capability_revision']:
                        break
                    result = {'schemaVersion': 1, 'parameter': parameter, 'templateProbeVerified': True,
                              'evidenceOrigin': 'GATEWAY_OBSERVED_TEMPLATE_PROBE',
                              'capabilityRevision': observed['capability_revision'],
                              'templateDigest': observed['template_digest'],
                              'enabledPromptDigest': fingerprint(prompts[0]), 'disabledPromptDigest': fingerprint(prompts[1]),
                              'observedAtMillis': int(time.time() * 1000), 'observedComputationEnabled': None}
                    break
            except (ExecutionBindingError, requests.RequestException, ValueError, KeyError, TypeError):
                pass
            self.reasoning_cache[key] = self.clock(), result
            self.reasoning_cache.move_to_end(key)
            while len(self.reasoning_cache) > 64:
                self.reasoning_cache.popitem(last=False)
            return copy.deepcopy(result)

    def _probe(self, route, model, deadline_at=None):
        root = route['metadata_base']
        catalog = self._json('GET', root + '/v1/models', params={'autoload': 'false'}, deadline_at=deadline_at)
        rows = catalog.get('data')
        if not isinstance(rows, list) or len(rows) > 1024 or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in rows):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'The configured backend model catalog is invalid.')
        selected = next((row for row in rows if row['id'] == model), None)
        if selected is None:
            raise ExecutionBindingError('EXECUTION_MODEL_UNAVAILABLE', 'The exact selected model is not advertised by the configured backend. Work is retained.')
        if route['provider_kind'] == 'OLLAMA':
            props = self._json('POST', root + '/api/show', body={'model': model, 'verbose': False}, deadline_at=deadline_at)
            running = self._json('GET', root + '/api/ps', deadline_at=deadline_at).get('models', [])
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
            props = self._json('GET', root + '/props', params={'model': model, 'autoload': 'false'}, deadline_at=deadline_at)
            settings = props.get('default_generation_settings') or {}
            if not isinstance(settings, dict):
                raise ExecutionBindingError('EXECUTION_CAPABILITY_INVALID', 'The active generation settings are malformed.')
            context = positive(settings.get('n_ctx')) or positive(props.get('n_ctx')) or positive(props.get('context_length'))
            # Training context and model filename do not prove the active runtime
            # allocation or an alias's weight revision.
            loaded_model = props.get('model_alias') or props.get('model_id')
            if not loaded_model:
                # A single-model server's catalog identifies its active props.
                # An unscoped response from a router cannot attest which of its
                # advertised models owns this allocation merely from the query.
                if len(rows) != 1:
                    raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'The active model allocation does not identify the exact selected model.')
                loaded_model = selected['id']
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
                    'reasoning_parameters': [parameter for parameter in ('enable_thinking', 'thinking')
                                             if isinstance(template, str) and re.search(r'\b' + parameter + r'\b', template)],
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
