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
from v14.backend_observation import exception_failure, failure, model_state, record_backend_result, response_failure
from v14.debug_projection import backend_build_provenance


def positive(value, maximum=2097152):
    return value if type(value) is int and 0 < value <= maximum else None


class InferenceCapabilities:
    def __init__(self, runtime, clock=time.monotonic, *, deadline_at=None):
        self.runtime, self.clock = runtime, clock
        self.deadline_at = deadline_at
        self.lock = threading.RLock()
        self.cache = OrderedDict()
        self.refreshes = {}
        self.reasoning_cache = OrderedDict()
        self.reasoning_states = OrderedDict()
        self.reasoning_refreshes = {}
        self.model_generations = OrderedDict()
        self.generation_counter = 0

    def _clear_model(self, key):
        for cache in (self.cache, self.reasoning_cache, self.reasoning_states):
            for current in list(cache):
                if current[:2] == key:
                    cache.pop(current, None)

    def _generation(self, key, *, invalidate=False):
        # Called under self.lock. Never reuse a token, including after eviction:
        # an old in-flight probe cannot resurrect a pruned model's observations.
        if invalidate or key not in self.model_generations:
            self.generation_counter += 1
            self.model_generations[key] = self.generation_counter
        self.model_generations.move_to_end(key)
        while len(self.model_generations) > 128:
            expired, _ = self.model_generations.popitem(last=False)
            self._clear_model(expired)
        return self.model_generations[key]

    def _require_generation(self, key, generation):
        if self.model_generations.get(key) != generation:
            error = ExecutionBindingError('EXECUTION_CAPABILITY_INVALID',
                'The selected model changed during this observation. Recheck its metadata before execution.', 503)
            error.retryable = True
            error.observation = dict(failure('unverified', 'selected_model'), limitsVerified=False,
                                     modelAdvertised=None, modelLoaded=None, modelState='UNKNOWN',
                                     reasoningControlState='UNVERIFIED')
            raise error

    def _json(self, method, url, *, body=None, params=None, maximum=1024 * 1024, deadline_at=None):
        if self.deadline_at is not None:
            deadline_at = min(deadline_at, self.deadline_at) if deadline_at is not None else self.deadline_at
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
        dependency = ('backend_models' if url.endswith('/models') else 'backend_template' if url.endswith('/apply-template')
                      else 'selected_model' if url.endswith(('/models/load', '/tokenize')) else 'backend_properties')
        response = None
        try:
            response = call(url, **kwargs)
            if not 200 <= response.status_code < 300:
                details = response_failure(response, dependency, (params or {}).get('model') or (body or {}).get('model'))
                code = {'denied': 'EXECUTION_METADATA_DENIED', 'unsupported': 'EXECUTION_CAPABILITY_UNSUPPORTED',
                        'configuration': 'EXECUTION_MODEL_IDENTITY_INVALID',
                        'model_not_loaded': 'EXECUTION_MODEL_NOT_LOADED', 'model_loading': 'EXECUTION_MODEL_LOADING',
                        'model_unavailable': 'EXECUTION_MODEL_UNAVAILABLE'}.get(details['failureCategory'], 'EXECUTION_BACKEND_UNAVAILABLE')
                message = details.get('upstreamMessage') or 'Required metadata is unavailable on the configured backend; no other route was tried.'
                rejected = ExecutionBindingError(code, message, response.status_code if response.status_code in {401, 403} else 503)
                rejected.retryable = details['retryable']
                rejected.observation = details
                raise rejected
            response.raise_for_status()
            raw = bytearray()
            deadline = min(time.monotonic() + 5, deadline_at) if deadline_at is not None else time.monotonic() + 5
            for chunk in response.iter_content(8192):
                if len(raw) + len(chunk) > maximum:
                    raise ExecutionBindingError('EXECUTION_METADATA_OVERSIZE', 'The configured backend metadata exceeded its bound.')
                if time.monotonic() > deadline:
                    raise ExecutionBindingError('EXECUTION_METADATA_DEADLINE', 'The configured backend metadata exceeded its deadline.', 503)
                raw.extend(chunk)
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError('Expected an object')
            return value
        except (ExecutionBindingError, requests.RequestException, TimeoutError, ValueError, TypeError, RecursionError) as error:
            details = getattr(error, 'observation', None) or exception_failure(error, dependency)
            if response is not None and not 200 <= response.status_code < 300:
                details['upstreamStatus'] = response.status_code
            if isinstance(error, ExecutionBindingError):
                error.observation = details
                error.retryable = error.retryable or details.get('retryable') is True
                raise
            code = ('EXECUTION_CAPABILITY_UNSUPPORTED' if details['failureCategory'] == 'unsupported' else
                    'EXECUTION_CAPABILITY_INVALID' if details['failureCategory'] == 'invalid_response' else
                    'EXECUTION_BACKEND_UNAVAILABLE')
            wrapped = ExecutionBindingError(code, 'Required metadata is unavailable on the configured backend; no other route was tried.', 503)
            wrapped.retryable = details.get('retryable') is True
            wrapped.observation = details
            raise wrapped from error
        finally:
            if response is not None:
                response.close()

    def inspect(self, route, model, *, force=False):
        transport = getattr(self.runtime, 'http_get', requests.get)
        key = (route['runtime_config_revision'], model, getattr(self.runtime, 'gateway_backend_recovery_epoch', 0), id(transport))
        # Each backend/model has its own gate. Failed observations have the same
        # short TTL as successes; force=True still measures each physical boundary.
        with self.lock:
            generation = self._generation(key[:2])
            cached = self.cache.get(key)
            if not force and cached and self.clock() - cached[0] < 3:
                return self._cached(cached)
            gate, count = self.refreshes.get(key, (threading.Lock(), 0))
            self.refreshes[key] = gate, count + 1
        try:
            with gate:
                with self.lock:
                    self._require_generation(key[:2], generation)
                    cached = self.cache.get(key)
                    if not force and cached and self.clock() - cached[0] < 3:
                        return self._cached(cached)
                result, rejected, connectivity_failure = None, None, False
                try:
                    result = self._probe(route, model)
                except (ExecutionBindingError, requests.RequestException, TimeoutError, ValueError, KeyError, TypeError) as error:
                    details = dict(getattr(error, 'observation', None) or exception_failure(error))
                    details.update(observedAtMillis=int(time.time() * 1000))
                    code = error.code if isinstance(error, ExecutionBindingError) else ('EXECUTION_BACKEND_UNAVAILABLE'
                        if isinstance(error, (requests.RequestException, TimeoutError)) else 'EXECUTION_CAPABILITY_INVALID')
                    status = error.status if isinstance(error, ExecutionBindingError) else 503
                    retryable = error.retryable if isinstance(error, ExecutionBindingError) else details.get('retryable') is True
                    rejected = (code, str(error) if isinstance(error, ExecutionBindingError) else 'The configured backend returned invalid model metadata.', status, details, retryable)
                    connectivity_failure = details['failureCategory'] in {'connection_refused', 'connect_timeout', 'read_timeout', 'dns', 'tls', 'connection_closed', 'connection'}
                entry = self.clock(), result, rejected, transport
                with self.lock:
                    self._require_generation(key[:2], generation)
                    if connectivity_failure:
                        record_backend_result(self.runtime, False)
                    self.cache[key] = entry
                    self.cache.move_to_end(key)
                    while len(self.cache) > 64:
                        self.cache.popitem(last=False)
                    return self._cached(entry)
        finally:
            with self.lock:
                current, count = self.refreshes[key]
                if count == 1:
                    self.refreshes.pop(key)
                else:
                    self.refreshes[key] = current, count - 1

    def _cached(self, entry):
        age = max(0, int((self.clock() - entry[0]) * 1000))
        if entry[2] is not None:
            code, message, status, details, retryable = entry[2]
            error = ExecutionBindingError(code, message, status)
            error.retryable = retryable
            error.observation = dict(details, observationAgeMs=age)
            raise error
        result = copy.deepcopy(entry[1])
        result['observation_age_ms'] = age
        return result

    def invalidate_model(self, route, model):
        """An explicit repair refreshes only the selected route/model's observations."""
        with self.lock:
            key = route['runtime_config_revision'], model
            self._generation(key, invalidate=True)
            self._clear_model(key)

    def selected_model(self, model, *, force=False):
        """Read-only public handshake. Never expose backend addresses, templates or another model."""
        if not isinstance(model, str) or not 1 <= len(model) <= 512 or model != model.strip() or any(ord(c) < 32 for c in model):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'Select a bounded model identifier.', 400)
        result = {'schema': 'gptmobile.gateway.selected-model.v1', 'schemaVersion': 1,
                  'requestedModel': model, 'limitsVerified': False, 'reasoningControlState': 'UNVERIFIED',
                  'inferenceTested': False, 'automaticSubstitution': False,
                  'modelAdvertised': None, 'modelLoaded': None, 'modelState': 'UNKNOWN'}
        unverified = dict(result)
        try:
            kind = str(getattr(self.runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
            route = configured_route(self.runtime, kind)
            with self.lock:
                generation = self._generation((route['runtime_config_revision'], model))
            observed = self.inspect(route, model, force=force)
            result.update(backend_build_provenance(observed.get('backend_build')))
            debug = getattr(self.runtime, 'gateway_debug', None)
            if debug is not None:
                debug.observe_backend_build(observed.get('backend_build'), observed['observed_at_ms'])
            self.require(observed, {'requested_model_id': model,
                                   'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
            # A context-capacity bound is useful for admission but is not a
            # separately measured provider output-token limit.
            output = observed['max_output_tokens'] if observed.get('output_limit_source') == 'provider_reported' else None
            result.update(reportedModel=observed['reported_model_id'], contextTokens=observed['context_tokens'],
                          maxOutputTokens=output, outputLimitSource=observed['output_limit_source'],
                          limitsVerified=True, capabilityRevision=observed['capability_revision'],
                          observedAtMillis=observed['observed_at_ms'],
                          observationAgeMs=observed.get('observation_age_ms', 0),
                          modelAdvertised=True, modelLoaded=True, modelState='LOADED',
                          evidenceOrigin='GATEWAY_OBSERVED_PROVIDER_METADATA')
            control = self.reasoning_control(route, model, observed, generation=generation)
            if control is not None:
                result['reasoningControl'] = control
                result['reasoningControlState'] = 'VERIFIED'
            else:
                key = (route['runtime_config_revision'], model, observed['capability_revision'])
                with self.lock:
                    result['reasoningControlState'] = self.reasoning_states.get(key, 'UNVERIFIED')
            with self.lock:
                self._require_generation((route['runtime_config_revision'], model), generation)
        except ExecutionBindingError as error:
            result = unverified
            result['failureCode'] = error.code
            result.update(getattr(error, 'observation', None) or exception_failure(error))
            result.setdefault('observedAtMillis', int(time.time() * 1000))
            result.setdefault('observationAgeMs', 0)
        return result

    @staticmethod
    def _verifies_thinking_toggle(enabled, disabled):
        if not isinstance(enabled, str) or not isinstance(disabled, str):
            return False
        on, off = enabled.rfind('<think>'), disabled.rfind('<think>')
        return (on >= 0 and on == off and enabled[:on] == disabled[:off]
                and not enabled[on + 7:].strip() and disabled[off + 7:].strip() == '</think>')

    def reasoning_control(self, route, model, observed, *, generation=None):
        """Verify only synthetic template rendering, never inference or hardware use.

        The proof is tied to the exact model/template/runtime metadata revision.
        An unsupported or slow optional probe cannot invalidate verified limits.
        """
        candidates = observed.get('reasoning_parameters') or []
        if route['provider_kind'] != 'LLAMA' or not candidates or not observed.get('template_digest'):
            return None
        key = (route['runtime_config_revision'], model, observed['capability_revision'])
        with self.lock:
            generation = self._generation(key[:2]) if generation is None else generation
            self._require_generation(key[:2], generation)
            cached = self.reasoning_cache.get(key)
            if cached and self.clock() - cached[0] < (60 if cached[1] is not None else 30):
                return copy.deepcopy(cached[1])
            gate, count = self.reasoning_refreshes.get(key, (threading.Lock(), 0))
            self.reasoning_refreshes[key] = gate, count + 1
        try:
            with gate:
                with self.lock:
                    self._require_generation(key[:2], generation)
                    cached = self.reasoning_cache.get(key)
                    if cached and self.clock() - cached[0] < (60 if cached[1] is not None else 30):
                        return copy.deepcopy(cached[1])
                return self._reasoning_probe(route, model, observed, key, candidates, generation)
        finally:
            with self.lock:
                current, count = self.reasoning_refreshes[key]
                if count == 1:
                    self.reasoning_refreshes.pop(key)
                else:
                    self.reasoning_refreshes[key] = current, count - 1

    def _reasoning_probe(self, route, model, observed, key, candidates, generation):
        # No network operation holds the shared cache lock. Unrelated model
        # readiness remains responsive while this optional proof is measured.
        result = None
        measured, invalidated = 0, False
        deadline = time.monotonic() + 2
        try:
            for parameter in candidates:
                prompts = []
                for enabled in (True, False):
                    with self.lock:
                        self._require_generation(key[:2], generation)
                    response = self._json('POST', route['metadata_base'] + '/apply-template',
                        body={'model': model, 'messages': [{'role': 'user', 'content': 'Template capability check.'}],
                              'chat_template_kwargs': {parameter: enabled}, 'add_generation_prompt': True},
                        params={'model': model, 'autoload': 'false'}, maximum=65536, deadline_at=deadline)
                    prompts.append(response.get('prompt'))
                if all(isinstance(prompt, str) for prompt in prompts):
                    measured += 1
                if not self._verifies_thinking_toggle(*prompts):
                    continue
                current = self._probe(route, model, deadline_at=deadline)
                self.require(current, {'requested_model_id': model,
                                       'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
                if current['capability_revision'] != observed['capability_revision']:
                    invalidated = True
                    break
                result = {'schemaVersion': 1, 'parameter': parameter, 'templateProbeVerified': True,
                          'evidenceOrigin': 'GATEWAY_OBSERVED_TEMPLATE_PROBE',
                          'capabilityRevision': observed['capability_revision'],
                          'templateDigest': observed['template_digest'],
                          'enabledPromptDigest': fingerprint(prompts[0]), 'disabledPromptDigest': fingerprint(prompts[1]),
                          'observedAtMillis': int(time.time() * 1000), 'observedComputationEnabled': None}
                break
        except (ExecutionBindingError, requests.RequestException, ValueError, KeyError, TypeError):
            invalidated = True
        if result is None and measured == len(candidates) and not invalidated:
            try:
                current = self._probe(route, model, deadline_at=deadline)
                self.require(current, {'requested_model_id': model,
                                       'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
                invalidated = current['capability_revision'] != observed['capability_revision']
            except (ExecutionBindingError, requests.RequestException, ValueError, KeyError, TypeError):
                invalidated = True
        with self.lock:
            self._require_generation(key[:2], generation)
            self.reasoning_states[key] = ('VERIFIED' if result is not None else
                                          'UNSUPPORTED' if measured == len(candidates) and not invalidated else 'UNVERIFIED')
            self.reasoning_cache[key] = self.clock(), result
            self.reasoning_cache.move_to_end(key)
            while len(self.reasoning_cache) > 64:
                expired, _ = self.reasoning_cache.popitem(last=False)
                self.reasoning_states.pop(expired, None)
            return copy.deepcopy(result)

    def _probe(self, route, model, deadline_at=None):
        root = route['metadata_base']
        catalog = self._json('GET', root + '/v1/models', params={'autoload': 'false'}, deadline_at=deadline_at)
        rows = catalog.get('data')
        if not isinstance(rows, list) or len(rows) > 1024 or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in rows):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'The configured backend model catalog is invalid.')
        selected = next((row for row in rows if row['id'] == model), None)
        if selected is None:
            error = ExecutionBindingError('EXECUTION_MODEL_UNAVAILABLE', 'The exact selected model is not advertised by the configured backend. Work is retained.')
            error.observation = dict(failure('model_unavailable', 'selected_model'), modelAdvertised=False,
                                     modelLoaded=False, modelState='UNAVAILABLE')
            raise error
        state = model_state(selected)
        if state in {'UNLOADED', 'LOADING', 'SLEEPING'}:
            self._not_loaded(state)
        if route['provider_kind'] == 'OLLAMA':
            props = self._json('POST', root + '/api/show', body={'model': model, 'verbose': False}, deadline_at=deadline_at)
            running = self._json('GET', root + '/api/ps', deadline_at=deadline_at).get('models', [])
            active = next((row for row in running if isinstance(row, dict) and row.get('model', row.get('name')) == model), None)
            if active is None:
                self._not_loaded('UNLOADED')
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
            try:
                props = self._json('GET', root + '/props', params={'model': model, 'autoload': 'false'}, deadline_at=deadline_at)
            except ExecutionBindingError as error:
                # The catalogue proved advertisement, not successful loading.
                details = dict(getattr(error, 'observation', None) or exception_failure(error, 'backend_properties'))
                details.update(modelAdvertised=True)
                details.setdefault('modelState', state)
                error.observation = details
                raise
            if props.get('is_sleeping') is True:
                identity = props.get('model_alias') or props.get('model_id')
                if not identity and len(rows) != 1:
                    raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'The sleeping model properties do not identify the exact selected model.')
                if identity and identity != model:
                    raise ExecutionBindingError('EXECUTION_MODEL_MISMATCH', 'The sleeping model does not match the exact selected model.')
                self._not_loaded('SLEEPING')
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
    def _not_loaded(state):
        loading = state == 'LOADING'
        error = ExecutionBindingError('EXECUTION_MODEL_LOADING' if loading else 'EXECUTION_MODEL_NOT_LOADED',
            'The exact selected model is loading; refresh its qualification when loading finishes.' if loading else
            'The exact selected model is advertised but not loaded. Prepare that model on the gateway host, then recheck its capabilities.', 503)
        # This is a transient readiness observation, not a revoked selection or
        # terminal job failure. Durable workers keep the exact binding and wait;
        # callers that have not admitted a job can still surface the host action.
        error.retryable = True
        error.observation = dict(failure('model_loading' if loading else 'model_not_loaded', 'selected_model'),
                                 modelAdvertised=True, modelLoaded=False, modelState=state)
        raise error

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
