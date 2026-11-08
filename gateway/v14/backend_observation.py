"""Bounded, read-only backend observations with public, allowlisted failure receipts."""
import errno
import json
import os
import socket
import threading
import time
from contextlib import closing

import requests

from v14.execution_binding import ExecutionBindingError, configured_route


FAILURES = {
    'connection_refused': ('BACKEND_CONNECTION_REFUSED', True),
    'connect_timeout': ('BACKEND_CONNECT_TIMEOUT', True),
    'read_timeout': ('BACKEND_READ_TIMEOUT', True),
    'dns': ('BACKEND_DNS_FAILED', True),
    'tls': ('BACKEND_TLS_FAILED', False),
    'connection_closed': ('BACKEND_CONNECTION_CLOSED', True),
    'connection': ('BACKEND_CONNECTION_FAILED', True),
    'http': ('BACKEND_HTTP_ERROR', True),
    'denied': ('BACKEND_METADATA_DENIED', False),
    'unsupported': ('BACKEND_ROUTE_UNSUPPORTED', False),
    'invalid_response': ('BACKEND_INVALID_RESPONSE', False),
    'oversize': ('BACKEND_RESPONSE_TOO_LARGE', False),
    'deadline': ('BACKEND_METADATA_DEADLINE', True),
    'configuration': ('BACKEND_CONFIGURATION_INVALID', False),
    'model_unavailable': ('SELECTED_MODEL_NOT_AVAILABLE', False),
    'model_loading': ('SELECTED_MODEL_LOADING', True),
    'generation_unavailable': ('BACKEND_GENERATION_UNAVAILABLE', True),
    'unverified': ('BACKEND_GENERATION_UNVERIFIED', True),
}
DEPENDENCIES = {'model_backend', 'backend_health', 'backend_models', 'backend_properties',
                'backend_template', 'selected_model', 'durable_storage', 'process'}
_recovery_lock = threading.Lock()


def backend_root(runtime):
    provider = getattr(runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA'
    return configured_route(runtime, str(provider).upper())['metadata_base']


def record_backend_result(runtime, available):
    """A measured recovery invalidates old failed observations without selecting another route."""
    with _recovery_lock:
        if available and getattr(runtime, '_gateway_backend_available', None) is False:
            runtime.gateway_backend_recovery_epoch = getattr(runtime, 'gateway_backend_recovery_epoch', 0) + 1
        runtime._gateway_backend_available = available


def failure(category, dependency='model_backend', status=None):
    category = category if category in FAILURES else 'connection'
    code, retryable = FAILURES[category]
    result = {'reasonCode': code, 'failureCategory': category,
              'dependency': dependency if dependency in DEPENDENCIES else 'model_backend', 'retryable': retryable}
    if type(status) is int and 100 <= status <= 599:
        result['upstreamStatus'] = status
        if category == 'http':
            result['retryable'] = status in {408, 425, 429} or status >= 500
    return result


def http_failure(status, dependency):
    category = 'denied' if status in {401, 403} else 'unsupported' if status in {404, 405, 501} else 'http'
    return failure(category, dependency, status)


def _causes(error):
    # requests/urllib3 wrap socket causes in args/reason as well as __cause__.
    pending, seen = [error], set()
    while pending and len(seen) < 32:
        value = pending.pop()
        if not isinstance(value, BaseException) or id(value) in seen:
            continue
        seen.add(id(value))
        yield value
        pending.extend([value.__cause__, value.__context__, getattr(value, 'reason', None), *value.args])


def exception_failure(error, dependency='model_backend'):
    """Classify types/errno only; exception strings may contain endpoints or credentials."""
    causes = list(_causes(error))
    if isinstance(error, ExecutionBindingError):
        category = {
            'EXECUTION_METADATA_DENIED': 'denied', 'EXECUTION_METADATA_DEADLINE': 'deadline',
            'EXECUTION_METADATA_OVERSIZE': 'oversize', 'EXECUTION_CAPABILITY_UNSUPPORTED': 'unsupported',
            'EXECUTION_BACKEND_UNAVAILABLE': 'connection', 'EXECUTION_MODEL_UNAVAILABLE': 'model_unavailable',
            'EXECUTION_MODEL_NOT_LOADED': 'model_unavailable', 'EXECUTION_ENDPOINT_INVALID': 'configuration',
            'EXECUTION_PROVIDER_UNAVAILABLE': 'configuration',
        }.get(error.code, 'invalid_response')
        return failure(category, dependency)
    if any(isinstance(e, requests.exceptions.SSLError) for e in causes):
        category = 'tls'
    elif any(isinstance(e, requests.exceptions.ConnectTimeout) or type(e).__name__ == 'ConnectTimeoutError' for e in causes):
        category = 'connect_timeout'
    elif any(isinstance(e, requests.exceptions.ReadTimeout) or type(e).__name__ == 'ReadTimeoutError' for e in causes):
        category = 'read_timeout'
    elif any(isinstance(e, socket.gaierror) or type(e).__name__ == 'NameResolutionError' for e in causes):
        category = 'dns'
    elif any(isinstance(e, ConnectionRefusedError) or getattr(e, 'errno', None) == errno.ECONNREFUSED for e in causes):
        category = 'connection_refused'
    elif any(isinstance(e, (ConnectionResetError, BrokenPipeError, requests.exceptions.ChunkedEncodingError))
             or type(e).__name__ in {'ProtocolError', 'RemoteDisconnected'} for e in causes):
        category = 'connection_closed'
    elif isinstance(error, requests.HTTPError):
        return http_failure(getattr(error.response, 'status_code', None), dependency)
    elif isinstance(error, (requests.Timeout, TimeoutError)):
        category = 'read_timeout'
    elif isinstance(error, (ValueError, TypeError, KeyError)):
        category = 'invalid_response'
    else:
        category = 'connection'
    return failure(category, dependency)


class MetadataFailure(ValueError):
    def __init__(self, observation, status=502):
        super().__init__(observation['reasonCode'])
        self.observation, self.status = observation, status


def read_metadata(response, dependency, limit=1024 * 1024, seconds=2):
    raw, deadline = bytearray(), time.monotonic() + seconds
    for chunk in response.iter_content(8192):
        if len(raw) + len(chunk) > limit:
            raise MetadataFailure(failure('oversize', dependency))
        if time.monotonic() > deadline:
            raise MetadataFailure(failure('deadline', dependency))
        raw.extend(chunk)
    try:
        return json.loads(raw)
    except (ValueError, TypeError, UnicodeDecodeError, RecursionError):
        raise MetadataFailure(failure('invalid_response', dependency)) from None


def model_catalog(runtime):
    """GET only; bounded, closed and exact catalogue. No load, inference or fallback."""
    dependency = 'backend_models'
    try:
        root = backend_root(runtime)
        with closing(runtime.http_get(root + '/v1/models', params={'autoload': 'false'},
                                      timeout=(0.5, 1), stream=True, allow_redirects=False)) as response:
            if response.status_code != 200:
                raise MetadataFailure(http_failure(response.status_code, dependency))
            body = read_metadata(response, dependency)
            rows = body.get('data') if isinstance(body, dict) else None
            if (not isinstance(rows, list) or len(rows) > 1024
                    or any(not isinstance(row, dict) or not isinstance(row.get('id'), str)
                           or not 1 <= len(row['id']) <= 512 or any(ord(c) < 32 for c in row['id']) for row in rows)):
                raise MetadataFailure(failure('invalid_response', dependency))
            # Preserve successful OpenAI-compatible catalogue metadata. Only
            # failures become gateway receipts; diagnostics never export this body.
            return body
    except MetadataFailure:
        raise
    except (requests.RequestException, ExecutionBindingError, ValueError, TypeError) as error:
        observation = exception_failure(error, dependency)
        if observation['failureCategory'] in {'connection_refused', 'connect_timeout', 'read_timeout', 'dns', 'tls', 'connection_closed', 'connection'}:
            record_backend_result(runtime, False)
        raise MetadataFailure(observation) from error


def model_response(runtime):
    """Return a stable public body while preserving upstream status separately."""
    observed_at = int(time.time() * 1000)
    try:
        return 200, model_catalog(runtime)
    except MetadataFailure as error:
        receipt = dict(error.observation, failureCode=error.observation['reasonCode'],
                       observedAtMillis=observed_at, observationAgeMs=0,
                       inferenceTested=False, automaticSubstitution=False)
        return error.status, {'error': dict(receipt, code=receipt['failureCode'],
                                           message='Model discovery failed on the configured backend. Use the failure code to repair that backend.')}
