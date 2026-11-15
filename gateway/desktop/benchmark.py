"""Run fixed, tool-free fixtures through the configured Gateway and retain measurements."""
from pathlib import Path
from statistics import median
import json
import time
import uuid
from urllib.parse import urlencode

import requests

from benchmark_selected_model import measure
from desktop.configuration import atomic_json, authority, control_host, load
from v14 import VERSION

SUITE = 1
RESULTS = 'benchmark-scoreboard.json'
HEADERS = {'User-Agent': 'GPT-Mobile-Gateway-Desktop-Benchmark'}
_RECORDS = '\n'.join(f'Parcel {i:03}: shelf R{i % 13:02}' for i in range(250))


def messages(prompt):
    return [{'role': 'user', 'content': prompt}]


CASES = (
    ('Exact output', messages('Reply with only this exact identifier: ORBIT-729'), 'ORBIT-729'),
    ('Arithmetic', messages('What is (37 × 14) − 29? Reply with only the integer.'), '489'),
    ('Structured output', messages('Return only a JSON object with key "active" set to true and key "count" set to the integer 7.'), {'active': True, 'count': 7}),
    ('Long-context lookup', messages(_RECORDS + '\nWhat shelf contains parcel 237? Reply with only its shelf code.'), 'R03'),
    ('Conversation recall', [
        {'role': 'user', 'content': 'The backup site is Cedar-41. Acknowledge.'},
        {'role': 'assistant', 'content': 'Acknowledged.'},
        {'role': 'user', 'content': 'What was the backup site? Reply with only the site identifier.'},
    ], 'Cedar-41'),
)


def matches(text, expected):
    if isinstance(expected, str):
        return text.strip() == expected
    try:
        value = json.loads(text)
        return isinstance(value, dict) and value.keys() == expected.keys() and all(
            type(value[key]) is type(wanted) and value[key] == wanted for key, wanted in expected.items()
        )
    except ValueError:
        return False


def endpoint(cfg):
    return 'http://' + authority(control_host(cfg), cfg['port'])


def get_json(client, url):
    with client.get(url, timeout=(3, 8), stream=True, allow_redirects=False) as response:
        if response.status_code != 200:
            raise ValueError(f'Gateway returned HTTP {response.status_code}')
        body = bytearray()
        for chunk in response.iter_content(16384):
            body.extend(chunk)
            if len(body) > 1024 * 1024:
                raise ValueError('Gateway response exceeded the benchmark limit')
        value = json.loads(body)
        if not isinstance(value, dict):
            raise ValueError('Gateway returned an invalid benchmark response')
        return value


def client_session():
    client = requests.Session()
    client.trust_env = False
    client.headers.update(HEADERS)
    return client


def discover(root):
    with client_session() as client:
        return models(client, endpoint(load(root)))


def models(client, origin):
    catalog = get_json(client, origin + '/v1/models')
    entries = catalog.get('data')
    if not isinstance(entries, list):
        raise ValueError('Gateway returned an invalid model catalog')
    return sorted({row['id'] for row in entries if isinstance(row, dict)
                   and isinstance(row.get('id'), str) and 0 < len(row['id']) <= 512})


def scoreboard(root):
    try:
        path = Path(root) / RESULTS
        if path.stat().st_size > 2 * 1024 * 1024:
            return []
        value = json.loads(path.read_bytes())
        return value['results'] if isinstance(value, dict) and value.get('suite') == SUITE and isinstance(value.get('results'), list) else []
    except (OSError, ValueError):
        return []


def run(root, model, progress):
    cfg = load(root)
    origin = endpoint(cfg)
    observations = []
    with client_session() as client:
        started = time.perf_counter()
        live = get_json(client, origin + '/gateway/live')
        if live.get('live') is not True or live.get('draining') or live.get('version') != VERSION:
            raise ValueError('Start this version of Gateway before benchmarking')
        gateway_ms = round((time.perf_counter() - started) * 1000, 1)
        if model not in models(client, origin):
            raise ValueError('The selected model is no longer advertised')
        for index, (name, turns, expected) in enumerate(CASES, 1):
            progress(f'{index}/{len(CASES)} · {name}')
            try:
                timing = measure(client.post, origin, model, turns, output_tokens=512,
                                 validate_output=lambda text: matches(text, expected),
                                 gateway_options={'store_conversation': False, 'budget': {'totalDeadlineMs': 120_000}})
                usage = timing['usage'] or {}
                timings = timing['backendTimings'] or {}
                generated = timings.get('predicted_n')
                generation_ms = timings.get('predicted_ms')
                observations.append(dict(name=name, passed=timing['passed'],
                                         firstMs=round(timing['firstContentMs'], 1),
                                         totalMs=round(timing['totalMs'], 1),
                                         gapMs=round(timing['maximumContentGapMs'], 1),
                                         tokens=usage.get('completion_tokens'),
                                         tokensPerSecond=round(1000 * generated / generation_ms, 1)
                                         if generated and generation_ms else None))
            except requests.RequestException:
                observations.append(dict(name=name, error='Connection or timeout'))
            except (ValueError, TimeoutError, TypeError, AttributeError):
                observations.append(dict(name=name, error='Invalid or incomplete model response'))
        if cfg != load(root) or get_json(client, origin + '/gateway/live').get('instanceId') != live.get('instanceId'):
            raise ValueError('Gateway changed during the benchmark. Run it again after it settles.')
    completed = [case for case in observations if 'passed' in case]
    errors = len(CASES) - len(completed)
    result = dict(model=model, backend=cfg['backend'], gateway=VERSION, gatewayMs=gateway_ms, at=time.time(),
                  score=round(100 * sum(case['passed'] for case in completed) / len(CASES)) if not errors else None,
                  firstMs=round(median(case['firstMs'] for case in completed), 1) if completed else None,
                  cases=observations)
    existing = [row for row in scoreboard(root) if isinstance(row, dict)
                and (row.get('model'), row.get('backend')) != (model, cfg['backend'])]
    atomic_json(Path(root) / RESULTS, {'suite': SUITE, 'results': [result, *existing][:100]})
    return result


class BenchmarkRejected(ValueError):
    """A definitive rejection is safe to correct without retrying admission."""


def execution_binding(root):
    origin = endpoint(load(root))
    with client_session() as client:
        identity = get_json(client, origin + '/gateway/identity')
    return {'origin': origin, 'gateway_id': identity['gateway_id'], 'principal_id': identity['principal_id']}


def service_request(root, path='', *, method='GET', payload=None, binding=None):
    """Observe gateway-owned work; the desktop never owns the worker."""
    origin = endpoint(load(root))
    if binding is not None and binding['origin'] != origin:
        raise ValueError('Reconnect to the original Gateway before reconciling this submission.')
    with client_session() as client:
        headers = {}
        if method != 'GET' or binding is not None:
            identity = get_json(client, origin + '/gateway/identity')
            if binding is not None and any(identity.get(key) != binding[key] for key in ('gateway_id', 'principal_id')):
                raise ValueError('The Gateway or account changed. Reconnect to the original identity to reconcile this submission.')
            headers = {'X-Gateway-Installation-ID': identity['gateway_id'],
                       'X-Gateway-Principal-ID': identity['principal_id']}
        with client.request(method, origin + '/gateway/benchmarks' + path, json=payload,
                            headers=headers, timeout=(3, 15), stream=True, allow_redirects=False) as response:
            raw = bytearray()
            for chunk in response.iter_content(16384):
                raw.extend(chunk)
                if len(raw) > 16 * 1024 * 1024:
                    raise ValueError('Benchmark response exceeds its display bound')
            value = json.loads(raw)
            if response.status_code != 200:
                error = value.get('error', {}) if isinstance(value, dict) else {}
                code, detail = error.get('code', 'benchmark_unavailable'), error.get('message')
                messages = {'benchmark_scope_required': 'Enable engineering access on this Gateway for coding tests.',
                            'benchmark_storage_unavailable': 'Benchmark storage is unavailable.',
                            'benchmark_not_found': 'This saved benchmark is no longer available.'}
                exception = BenchmarkRejected if response.status_code in {400, 403, 404, 409, 413, 422} else ValueError
                raise exception(messages.get(code, detail if isinstance(detail, str) else 'Gateway rejected the benchmark request: ' + str(code)))
            if not isinstance(value, dict):
                raise ValueError('Invalid benchmark response')
            return value


def start_run(root, model, suite='quick_check', preset='quick', *, request_id=None, seed=17, concurrency=2, binding=None):
    return service_request(root, method='POST', binding=binding, payload={'request_id': request_id or 'benchmark-' + uuid.uuid4().hex,
                           'model': model, 'suite': suite, 'preset': preset, 'seed': seed, 'concurrency': concurrency})


def reconcile_run(root, request_id, binding):
    """Read the original request receipt. Absence never dispatches a model."""
    return service_request(root, '?' + urlencode({'request_id': request_id}), binding=binding)


def save_pending(root, value):
    path = Path(root) / 'data' / 'benchmark-pending.json'
    if value is None:
        path.unlink(missing_ok=True)
    else:
        atomic_json(path, value)


def load_pending(root):
    path = Path(root) / 'data' / 'benchmark-pending.json'
    try:
        if path.stat().st_size > 4096:
            return None
        value = json.loads(path.read_bytes())
        if not isinstance(value, dict) or not isinstance(value.get('_binding'), dict):
            return None
        from benchmarks.contracts import validate_request
        validate_request({key: item for key, item in value.items() if not key.startswith('_')})
        if any(not isinstance(value['_binding'].get(key), str) for key in ('origin', 'gateway_id', 'principal_id')):
            return None
        value['_uncertain'] = True
        return value
    except (OSError, ValueError, TypeError):
        return None


def history(root, before=None):
    return service_request(root, '?before=' + str(before) if before else '')


def run_snapshot(root, ident):
    if not isinstance(ident, str) or not __import__('re').fullmatch(r'bench_[a-f0-9]{32}', ident):
        raise ValueError('Invalid benchmark identity')
    return service_request(root, '/' + ident)


def stop_run(root, ident):
    run_snapshot(root, ident)
    return service_request(root, '/' + ident + '/cancel', method='POST', payload={})


def remove_run(root, ident):
    run_snapshot(root, ident)
    return service_request(root, '/' + ident, method='DELETE')


def export_run(root, ident, path):
    snapshot = run_snapshot(root, ident)
    atomic_json(Path(path), snapshot)
    return snapshot
